import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, rm } from 'node:fs/promises';
import { join } from 'node:path';
import { tmpdir } from 'node:os';
import { MemoryStore } from '../store.mjs';
import { createMemoryMaintenance, historyEventRecord, deriveStayCandidates, MAINTENANCE_POLICY } from '../maintenance.mjs';

const iso = value => new Date(value).toISOString();
const deferred = () => { let resolve; const promise = new Promise(r => { resolve = r; }); return { promise, resolve }; };
async function fixture(t) {
  const directory = await mkdtemp(join(tmpdir(), 'droy-maintenance-'));
  const store = await MemoryStore.open({ directory: join(directory, 'memory'), initialize: true, minFreeBytes: 0 });
  const state = { enabled: true, automaticExtraction: true, ready: true, time: Date.now() };
  const f = { store, state, instances: [],
    make(options = {}) {
      const instance = createMemoryMaintenance({ store, executionStatus: async () => ({ ready: state.ready }),
        settings: async () => ({ enabled: state.enabled, automaticExtraction: state.automaticExtraction }), clock: () => state.time, ...options });
      f.instances.push(instance); return instance;
    } };
  t.after(async () => {
    for (const instance of f.instances) await instance.close().catch(() => {});
    await store.close(); await rm(directory, { recursive: true, force: true });
  }); return f;
}
function event(time, id = 'event-1', type = 'android_activity', payload = {}) {
  const defaults = type === 'android_location' ? { latitude: 0, longitude: 0, accuracyMeters: 10,
    provider: 'synthetic', elapsedRealtimeNanos: '123', approximate: false, stale: false }
    : type === 'android_device' ? { networkType: 'wifi', screenInteractive: true, batteryPercent: 80, charging: false }
    : { packageName: 'example.synthetic', transition: 'foreground_candidate', coverage: 'observed_window_event' };
  return { eventId: id, continuityId: '00000000-0000-4000-8000-000000000001', occurredAtMs: time, recordedAtMs: time, expiresAtMs: time + 86400000,
    sourceType: type, actor: { kind: 'unknown' }, payload: { ...defaults, ...payload } };
}
const source = (time, id = 'source:one') => ({ sourceId: id, revision: 'revision:1', occurredAt: iso(time), categories: ['memory'], sourceRefs: [id] });
function extractor(log = [], additions = {}) {
  return { resolveRoute: async () => { log.push('route'); return Object.freeze({ id: 'synthetic-approved-route' }); },
    authorize: async () => { log.push('authorize'); },
    readSource: async job => { log.push('read'); return { revision: job.revision, text: '虚构用户说喜欢步行。' }; },
    countTokens: async () => 100,
    generate: async () => { log.push('generate'); return { candidates: [{ kind: 'preference', text: '虚构用户可能喜欢步行。' }] }; }, ...additions };
}

test('master switch and execution readiness block all automatic acquisition before reading a source', async t => {
  const f = await fixture(t); let reads = 0;
  const m = f.make({ history: { readBatch() { reads++; }, acknowledge() {} }, extraction: extractor() });
  f.state.enabled = false;
  assert.equal((await m.ingestOnce()).code, 'MEMORY_DISABLED');
  assert.equal((await m.enqueueExtraction(source(f.state.time))).code, 'MEMORY_DISABLED');
  assert.equal((await m.maintainOnce()).code, 'MEMORY_DISABLED');
  f.state.enabled = true; f.state.ready = false;
  assert.equal((await m.ingestOnce()).code, 'MEMORY_EXECUTION_NOT_READY');
  assert.equal(reads, 0); assert.equal((await f.store.query()).total, 0);
});

test('native projection preserves observed actor, typed device state, source identity and retention', () => {
  const at = Date.now();
  const row = historyEventRecord({ ...event(at, 'synthetic-device', 'android_device'), actor: { kind: 'droy_activity' } }, 'node-a');
  assert.equal(row.actor, 'droy_active'); assert.equal(row.assertion, 'tool_observation');
  assert.equal(row.payload.screenInteractive, true); assert.deepEqual(row.sourceRefs, ['node-a:android:synthetic-device']);
  assert.equal(row.retention.expiresAt, iso(at + 86400000));
  assert.throws(() => historyEventRecord({ ...event(at), occurredAtMs: NaN }, 'node-a'), /MEMORY_INVALID_SOURCE_TIME/);
  assert.throws(() => historyEventRecord(event(at, 'invalid/slash'), 'node-a'), /MEMORY_INVALID_ID/);
});

test('spool acknowledgement follows durable raw and derivation commits, and retry does not duplicate', async t => {
  const f = await fixture(t), batch = { batchId: 'batch-1', items: [event(f.state.time)], hasMore: false };
  let failAck = true, calls = 0;
  const m = f.make({ history: { readBatch: async () => batch, acknowledge: async id => {
    calls++; assert.equal(id, 'batch-1'); assert.equal((await f.store.query()).total, 1);
    assert.ok((await f.store.getSourceCursor({ source: 'maintenance.locations.v1' })).cursor);
    if (failAck) throw Object.assign(new Error(), { code: 'SYNTHETIC_ACK_INTERRUPTED' });
  } } });
  assert.equal((await m.ingestOnce()).code, 'SYNTHETIC_ACK_INTERRUPTED');
  failAck = false;
  assert.equal((await m.ingestOnce()).status, 'completed');
  assert.equal((await f.store.query()).total, 1); assert.equal(calls, 2);
});

test('a late spool callback after disabling memory does not enter the store or acknowledge', async t => {
  const f = await fixture(t); let acknowledgements = 0;
  const m = f.make({ history: { readBatch: async () => { f.state.enabled = false; return { batchId: 'late', items: [event(f.state.time)] }; },
    acknowledge: async () => { acknowledgements++; } } });
  assert.equal((await m.ingestOnce()).code, 'MEMORY_DISABLED');
  assert.equal((await f.store.query()).total, 0); assert.equal(acknowledgements, 0);
});

test('activity intervals span durable batches and reconstruction without duplicate results on acknowledgement retry', async t => {
  const f = await fixture(t), at = f.state.time - 60000;
  const observation = (time, id, transition) => ({ ...event(time, id, 'android_activity', { transition }), actor: { kind: 'droy_activity' } });
  const batches = [{ batchId: 'activity-open', items: [observation(at, 'open', 'foreground_candidate')] },
    { batchId: 'activity-leave', items: [observation(at + 30000, 'leave', 'leave')] }];
  let index = 0, failAck = true;
  const history = { readBatch: async () => batches[index], acknowledge: async () => {
    if (index === 1 && failAck) { failAck = false; throw Object.assign(new Error(), { code: 'SYNTHETIC_ACK_INTERRUPTED' }); }
    index++;
  } };
  assert.equal((await f.make({ history }).ingestOnce()).status, 'completed');
  const saved = JSON.parse((await f.store.getSourceCursor({ source: 'maintenance.activity.v1' })).cursor);
  assert.deepEqual(Object.keys(saved.open).sort(), ['recordId', 'revisionId']);
  assert.equal(JSON.stringify(saved).includes('example.synthetic'), false);
  const reconstructed = f.make({ history });
  assert.equal((await reconstructed.ingestOnce()).code, 'SYNTHETIC_ACK_INTERRUPTED');
  assert.equal((await reconstructed.ingestOnce()).status, 'completed');
  const intervals = (await f.store.query({ eventTypes: ['activity'] })).items.filter(row => row.payload.source === 'droy-activity-rule');
  assert.equal(intervals.length, 1);
  const row = intervals[0]; assert.equal(row.state, 'candidate'); assert.equal(row.actor, 'droy_active');
  assert.equal(row.assertion, 'tool_observation'); assert.equal(row.payload.coverage, 'matched_window_events');
  assert.equal(row.payload.transition, 'observed_interval'); assert.equal(row.payload.startedAt, iso(at));
  assert.equal(row.payload.endedAt, iso(at + 30000)); assert.equal(row.derivedFrom.length, 2);
  assert.equal(row.sourceRefs.length, 2); assert.deepEqual(row.derivedFrom[0], saved.open);
  await f.store.forget({ operationId: 'forget-completed-activity', recordId: saved.open.recordId, expectedRevisionId: saved.open.revisionId });
  index = 0; await reconstructed.ingestOnce(); await reconstructed.ingestOnce();
  assert.equal((await f.store.query({ eventTypes: ['activity'] })).items.filter(record => record.payload.source === 'droy-activity-rule').length, 0);
});

test('incomplete activity observations cannot fabricate a matched interval', async t => {
  for (const interruption of ['duplicate-start', 'wrong-app', 'gap', 'screen-off', 'backward-time']) await t.test(interruption, async t => {
    const f = await fixture(t), at = f.state.time - 60000;
    const first = event(at, 'first');
    const end = event(at + 30000, 'last', 'android_activity', { transition: 'leave' });
    let middle;
    if (interruption === 'duplicate-start') middle = event(at + 1000, 'duplicate');
    if (interruption === 'wrong-app') end.payload.packageName = 'example.other';
    if (interruption === 'gap') middle = event(at + 1000, 'gap', 'android_coverage', { source: 'activity', state: 'paused', reason: 'synthetic' });
    if (interruption === 'screen-off') middle = event(at + 1000, 'off', 'android_device', { screenInteractive: false });
    if (interruption === 'backward-time') end.occurredAtMs = at - 1;
    const m = f.make({ history: { readBatch: async () => ({ batchId: `activity-${interruption}`, items: [first, ...(middle ? [middle] : []), end] }), acknowledge: async () => {} } });
    assert.equal((await m.ingestOnce()).status, 'completed');
    assert.equal((await f.store.query({ eventTypes: ['activity'] })).items.filter(row => row.payload.source === 'droy-activity-rule').length, 0);
  });
});

test('activity cursors revalidate forgotten, revised and expired starts before matching', async t => {
  for (const change of ['forgotten', 'revised', 'expired']) await t.test(change, async t => {
    const f = await fixture(t), at = f.state.time - 60000;
    const start = event(at, 'start'); if (change === 'expired') start.expiresAtMs = f.state.time + 1;
    const batches = [{ batchId: 'activity-first', items: [start] },
      { batchId: 'activity-final', items: [event(f.state.time + 20, 'end', 'android_activity', { transition: 'leave' })] }];
    let index = 0;
    const history = { readBatch: async () => batches[index], acknowledge: async () => { index++; } };
    assert.equal((await f.make({ history }).ingestOnce()).status, 'completed');
    const open = JSON.parse((await f.store.getSourceCursor({ source: 'maintenance.activity.v1' })).cursor).open;
    if (change === 'forgotten') await f.store.forget({ operationId: 'forget-activity', recordId: open.recordId, expectedRevisionId: open.revisionId });
    if (change === 'revised') await f.store.revise({ operationId: 'revise-activity', recordId: open.recordId, expectedRevisionId: open.revisionId, patch: { actor: 'droy_active' } });
    if (change === 'expired') f.state.time += 10;
    assert.equal((await f.make({ history }).ingestOnce()).status, 'completed');
    assert.equal((await f.store.query({ eventTypes: ['activity'] })).items.filter(row => row.payload.source === 'droy-activity-rule').length, 0);
    assert.equal(JSON.parse((await f.store.getSourceCursor({ source: 'maintenance.activity.v1' })).cursor).open, null);
  });
});

test('stopping clears incomplete activity and prevents old queued starts bridging the pause', async t => {
  const f = await fixture(t), at = f.state.time;
  const batches = [{ batchId: 'pause-before', items: [event(at - 1000, 'before')] },
    { batchId: 'pause-old-queued', items: [event(at - 500, 'queued')] },
    { batchId: 'pause-end', items: [event(at + 1000, 'end', 'android_activity', { transition: 'leave' })] },
    { batchId: 'pause-fresh', items: [event(at + 2000, 'fresh'), event(at + 3000, 'fresh-end', 'android_activity', { transition: 'leave' })] }];
  let index = 0;
  const m = f.make({ history: { readBatch: async () => batches[index], acknowledge: async () => { index++; } } });
  await m.ingestOnce(); f.state.enabled = false; await m.stop();
  f.state.enabled = true; m.resume();
  await m.ingestOnce(); await m.ingestOnce();
  assert.equal((await f.store.query({ eventTypes: ['activity'] })).items.filter(row => row.payload.source === 'droy-activity-rule').length, 0);
  assert.equal((await m.ingestOnce()).status, 'completed');
  const intervals = (await f.store.query({ eventTypes: ['activity'] })).items.filter(row => row.payload.source === 'droy-activity-rule');
  assert.equal(intervals.length, 1); assert.equal(intervals[0].actor, 'unknown');
  assert.equal(intervals[0].payload.startedAt, iso(at + 2000));
});

test('stay rules reject poor coverage and never invent city or confirmed user action', () => {
  const start = Date.now() - 20 * 60000;
  const point = (offset, id, extra = {}) => ({ ...historyEventRecord(event(start + offset, id, 'android_location', extra), 'node-a'),
    recordId: `record-${id}`, revisionId: `revision-${id}` });
  const rows = deriveStayCandidates([point(0, 'a'), point(5 * 60000, 'b'), point(10 * 60000, 'c')]);
  assert.equal(rows.length, 1); assert.equal(rows[0].state, 'candidate'); assert.equal(rows[0].actor, 'unknown');
  assert.equal(rows[0].payload.city, undefined); assert.equal(rows[0].derivedFrom.length, 3);
  assert.equal(deriveStayCandidates([point(0, 'a'), point(20 * 60000, 'b')]).length, 0);
  assert.equal(deriveStayCandidates([point(0, 'a'), point(5 * 60000, 'b', { accuracyMeters: 10000 }), point(10 * 60000, 'c')]).length, 0);
  assert.equal(deriveStayCandidates([point(0, 'a'), { payload: { eventType: 'gap', source: 'location' } }, point(10 * 60000, 'c')]).length, 0);
});

test('mock location flags survive projection but cannot create or bridge stay evidence', async t => {
  const f = await fixture(t), start = f.state.time - 20 * 60000;
  const point = (offset, id, isMock) => ({ ...historyEventRecord(event(start + offset, id, 'android_location',
    isMock === undefined ? {} : { isMock }), 'node-a'), recordId: `record-${id}`, revisionId: `revision-${id}` });
  for (const flag of [true, false, undefined]) assert.equal(point(0, 'a', flag).payload.isMock, flag);
  assert.throws(() => point(0, 'a', 'true'), /MEMORY_INVALID_/);
  assert.equal(deriveStayCandidates([point(0, 'a', true), point(10 * 60000, 'b', true)]).length, 0);
  assert.equal(deriveStayCandidates([point(0, 'a', false), point(5 * 60000, 'b', true), point(10 * 60000, 'c', false)]).length, 0);
  assert.equal(deriveStayCandidates([point(0, 'a', false), point(10 * 60000, 'b', false)])[0].payload.isMock, false);
  assert.equal(deriveStayCandidates([point(0, 'a'), point(10 * 60000, 'b', false)])[0].payload.isMock, undefined);
  const batches = [
    { batchId: 'mock-location-first', items: [event(start, 'a', 'android_location', { isMock: false })] },
    { batchId: 'mock-location-middle', items: [event(start + 5 * 60000, 'b', 'android_location', { isMock: true })] },
    { batchId: 'mock-location-last', items: [event(start + 10 * 60000, 'c', 'android_location', { isMock: false })] },
  ];
  let index = 0;
  const m = f.make({ history: { readBatch: async () => batches[index], acknowledge: async () => { index++; } } });
  for (let i = 0; i < batches.length; i++) assert.equal((await m.ingestOnce()).status, 'completed');
  assert.equal((await f.store.query({ eventTypes: ['visit'] })).total, 0);
  const locations = await f.store.query({ eventTypes: ['location'] });
  assert.equal(locations.items.filter(row => row.payload.isMock === true).length, 1);
});

test('rolling stay provenance crosses native batches and commits before final acknowledgement', async t => {
  const f = await fixture(t); const at = f.state.time - 15 * 60000;
  const batches = [
    { batchId: 'location-batch-1', items: [event(at, 'a', 'android_location'), event(at + 5 * 60000, 'b', 'android_location')] },
    { batchId: 'location-batch-2', items: [event(at + 10 * 60000, 'c', 'android_location'), event(at + 11 * 60000, 'd', 'android_location', { longitude: 0.1 })] },
  ];
  let index = 0;
  const m = f.make({ history: { readBatch: async () => batches[index], acknowledge: async () => { index++; } } });
  const first = await m.ingestOnce(), second = await m.ingestOnce();
  assert.equal(first.status, 'completed', JSON.stringify(first)); assert.equal(second.status, 'completed', JSON.stringify(second));
  const result = await f.store.query({ eventTypes: ['visit'] });
  assert.equal(result.total, 1); assert.equal(result.items[0].derivedFrom.length, 3);
  assert.equal(result.items[0].payload.endedAt, iso(at + 10 * 60000));
});

test('rolling location cursor holds identities only and forgetting a sample breaks the candidate', async t => {
  const f = await fixture(t), at = f.state.time - 15 * 60000;
  const batches = [
    { batchId: 'forget-location-batch-1', items: [event(at, 'a', 'android_location'), event(at + 5 * 60000, 'b', 'android_location')] },
    { batchId: 'forget-location-batch-2', items: [event(at + 10 * 60000, 'c', 'android_location'), event(at + 11 * 60000, 'd', 'android_location', { longitude: 0.1 })] },
  ];
  let index = 0;
  const m = f.make({ history: { readBatch: async () => batches[index], acknowledge: async () => { index++; } } });
  assert.equal((await m.ingestOnce()).status, 'completed');
  const cursor = JSON.parse((await f.store.getSourceCursor({ source: 'maintenance.locations.v1' })).cursor);
  assert.equal(cursor.version, 4);
  for (const sample of cursor.samples) assert.deepEqual(Object.keys(sample).sort(), ['recordId', 'revisionId']);
  assert.equal(JSON.stringify(cursor).includes('latitude'), false);
  await f.store.forget({ operationId: 'forget-location-sample', recordId: cursor.samples[1].recordId, expectedRevisionId: cursor.samples[1].revisionId });
  const second = await m.ingestOnce(); assert.equal(second.status, 'completed', JSON.stringify(second));
  assert.equal((await f.store.query({ eventTypes: ['visit'] })).total, 0);
});

test('a short maintenance pause cannot join location samples into a stay', async t => {
  const f = await fixture(t), at = f.state.time - 10 * 60000;
  const batches = [{ batchId: 'location-before-pause', items: [event(at, 'a', 'android_location'), event(at + 5 * 60000, 'b', 'android_location')] },
    { batchId: 'location-after-pause', items: [event(at + 10 * 60000, 'c', 'android_location'), event(at + 11 * 60000, 'd', 'android_location', { longitude: 0.1 })] }];
  let index = 0;
  const m = f.make({ history: { readBatch: async () => batches[index], acknowledge: async () => { index++; } } });
  assert.equal((await m.ingestOnce()).status, 'completed');
  f.state.time = at + 6 * 60000; await m.stop(); m.resume(); f.state.time = at + 12 * 60000;
  assert.equal((await m.ingestOnce()).status, 'completed');
  assert.equal((await f.store.query({ eventTypes: ['visit'] })).total, 0);
});

test('native continuity changes split activity and location observations even inside one batch', async t => {
  const f = await fixture(t), at = f.state.time - 15 * 60000;
  const newer = value => ({ ...value, continuityId: '00000000-0000-4000-8000-000000000002' });
  const batch = { batchId: 'continuity-mixed', items: [event(at, 'foreground'), event(at, 'a', 'android_location'),
    event(at + 5 * 60000, 'b', 'android_location'), newer(event(at + 10 * 60000, 'c', 'android_location')),
    newer(event(at + 10 * 60000, 'leave', 'android_activity', { transition: 'leave' })),
    newer(event(at + 11 * 60000, 'd', 'android_location', { longitude: 0.1 }))] };
  let acknowledgements = 0;
  const m = f.make({ history: { readBatch: async () => batch, acknowledge: async () => { acknowledgements++; } } });
  assert.equal((await m.ingestOnce()).status, 'completed'); assert.equal((await m.ingestOnce()).status, 'completed');
  const rows = (await f.store.query()).items;
  assert.equal(rows.filter(row => row.payload.eventType === 'visit' || row.payload.source === 'droy-activity-rule').length, 0);
  assert.equal(JSON.stringify(rows).includes('continuityId'), false); assert.equal(acknowledgements, 2);
  const gaps = rows.filter(row => row.payload.eventType === 'gap');
  assert.equal(gaps.filter(row => row.payload.reason === 'source_continuity_changed').length, 1);
  for (const gap of gaps) { assert.equal(gap.payload.startedAt, undefined); assert.equal(gap.payload.endedAt, undefined); }
});

test('an empty native gap persists a bounded reset across reconstruction without acknowledgement or repeated writes', async t => {
  const f = await fixture(t), at = f.state.time - 15 * 60000;
  const batches = [{ batchId: 'continuity-prefix', items: [event(at, 'foreground'), event(at, 'a', 'android_location'), event(at + 5 * 60000, 'b', 'android_location')] },
    { batchId: null, items: [], continuityId: '00000000-0000-4000-8000-000000000002' }];
  let index = 0, acknowledgements = 0;
  const history = { readBatch: async () => batches[index], acknowledge: async () => { index++; acknowledgements++; } };
  assert.equal((await f.make({ history }).ingestOnce()).status, 'completed');
  const reconstructed = f.make({ history });
  assert.equal((await reconstructed.ingestOnce()).status, 'empty');
  const revision = (await f.store.status()).revision;
  assert.equal((await reconstructed.ingestOnce()).status, 'empty');
  assert.equal((await f.store.status()).revision, revision); assert.equal(acknowledgements, 1);
  const activity = JSON.parse((await f.store.getSourceCursor({ source: 'maintenance.activity.v1' })).cursor);
  const location = JSON.parse((await f.store.getSourceCursor({ source: 'maintenance.locations.v1' })).cursor);
  assert.equal(activity.open, null); assert.deepEqual(location.samples, []);
  assert.equal(activity.continuityId, batches[1].continuityId); assert.equal(location.continuityId, batches[1].continuityId);
  const gaps = await f.store.query({ eventTypes: ['gap'] }); assert.equal(gaps.total, 1);
  assert.equal(gaps.items[0].payload.reason, 'source_continuity_changed');
});

test('legacy native rows without continuity identities cannot establish cross-record intervals', async t => {
  const f = await fixture(t), at = f.state.time - 15 * 60000;
  const items = [event(at, 'foreground'), event(at, 'a', 'android_location'), event(at + 5 * 60000, 'b', 'android_location'),
    event(at + 10 * 60000, 'c', 'android_location'), event(at + 10 * 60000, 'leave', 'android_activity', { transition: 'leave' }),
    event(at + 11 * 60000, 'd', 'android_location', { longitude: 0.1 })];
  for (const item of items) delete item.continuityId;
  const m = f.make({ history: { readBatch: async () => ({ batchId: 'legacy-no-continuity', items }), acknowledge: async () => {} } });
  assert.equal((await m.ingestOnce()).status, 'completed');
  assert.equal((await m.ingestOnce()).status, 'completed');
  assert.equal((await f.store.query()).items.filter(row => row.payload.eventType === 'visit' || row.payload.source === 'droy-activity-rule').length, 0);
  const gaps = await f.store.query({ eventTypes: ['gap'] }); assert.equal(gaps.total, 1);
  assert.equal(gaps.items[0].payload.reason, 'source_continuity_unknown');
});

test('location derivation revalidates current raw rows after an interrupted ingest and can acknowledge', async t => {
  for (const mutation of ['forgotten', 'revised', 'expired']) await t.test(mutation, async t => {
    const f = await fixture(t), at = f.state.time - 15 * 60000;
    const batch = { batchId: `location-raw-${mutation}`, items: [event(at, 'a', 'android_location'),
      event(at + 5 * 60000, 'b', 'android_location'), event(at + 10 * 60000, 'c', 'android_location'),
      event(at + 11 * 60000, 'd', 'android_location', { longitude: 0.1 })] };
    let interrupted = false, acknowledgements = 0;
    const storage = new Proxy(f.store, { get(target, key) {
      if (key === 'ingest') return async input => {
        const receipt = await target.ingest(input);
        if (!interrupted && input.operationId.startsWith('history.ingest.')) {
          interrupted = true; const row = receipt.items[1];
          if (mutation === 'forgotten') await target.forget({ operationId: 'forget-before-derive', recordId: row.recordId, expectedRevisionId: row.revisionId });
          if (mutation === 'revised') await target.revise({ operationId: 'revise-before-derive', recordId: row.recordId, expectedRevisionId: row.revisionId, patch: { actor: 'droy_active' } });
          if (mutation === 'expired') f.state.time += 2 * 86400000;
          throw Object.assign(new Error(), { code: 'SYNTHETIC_RAW_COMMIT_INTERRUPTED' });
        }
        return receipt;
      };
      const value = Reflect.get(target, key, target); return typeof value === 'function' ? value.bind(target) : value;
    } });
    const m = f.make({ store: storage, history: { readBatch: async () => batch, acknowledge: async () => { acknowledgements++; } } });
    assert.equal((await m.ingestOnce()).code, 'SYNTHETIC_RAW_COMMIT_INTERRUPTED');
    const result = await m.ingestOnce(); assert.equal(result.status, 'completed', JSON.stringify(result));
    assert.equal(acknowledgements, 1); assert.equal((await f.store.query({ eventTypes: ['visit'] })).total, 0);
  });
});

test('automatic extraction remains separately opt-in; explicit host request can use the same bounded path', async t => {
  const f = await fixture(t); f.state.automaticExtraction = false;
  const m = f.make({ extraction: extractor() });
  assert.equal((await m.enqueueExtraction(source(f.state.time))).code, 'MEMORY_EXTRACTION_DISABLED');
  assert.equal((await m.enqueueExtraction(source(f.state.time), { manual: true })).status, 'queued');
  assert.equal((await m.extractOnce()).code, 'MEMORY_EXTRACTION_DISABLED');
  assert.equal((await m.extractOnce({ manual: true })).status, 'completed');
});

test('one batch reads authorized immutable sources and creates candidates with host-owned provenance', async t => {
  const f = await fixture(t), log = [];
  const m = f.make({ extraction: extractor(log) });
  await m.enqueueExtraction(source(f.state.time, 'source:a')); await m.enqueueExtraction(source(f.state.time, 'source:b'));
  assert.equal((await m.extractOnce()).status, 'completed');
  assert.deepEqual(log, ['route', 'authorize', 'read', 'read', 'authorize', 'generate', 'authorize']);
  const row = (await f.store.query()).items[0];
  assert.equal(row.state, 'candidate'); assert.equal(row.assertion, 'model_inference'); assert.equal(row.actor, 'unknown');
  assert.deepEqual(row.sourceRefs, ['source:a', 'source:b']);
  assert.equal(m.status().budget.calls, 1); assert.equal(m.status().budget.tokens, 1100);
  assert.equal((await m.enqueueExtraction(source(f.state.time, 'source:a'))).status, 'duplicate');
  await m.enqueueExtraction(source(f.state.time, 'source:c'));
  assert.equal((await m.extractOnce()).status, 'waiting');
  assert.equal(log.filter(value => value === 'generate').length, 1);
});

test('authorization failure occurs before source body reads or a model request', async t => {
  const f = await fixture(t), log = [];
  const m = f.make({ extraction: extractor(log, { authorize: async () => { throw Object.assign(new Error(), { code: 'PRIVACY_ENDPOINT_NOT_AUTHORIZED' }); } }) });
  await m.enqueueExtraction(source(f.state.time));
  assert.equal((await m.extractOnce()).code, 'PRIVACY_ENDPOINT_NOT_AUTHORIZED'); assert.deepEqual(log, ['route']);
  assert.equal((await f.store.query()).total, 0);
});

test('daily reservation is persisted before generation and failure is held instead of automatically retried', async t => {
  const f = await fixture(t); let requests = 0;
  const m = f.make({ extraction: extractor([], { generate: async () => {
    requests++;
    const state = JSON.parse((await f.store.getSourceCursor({ source: 'maintenance.state.v1' })).cursor);
    assert.equal(state.budget.calls, 1); assert.equal(state.active.ids.length, 1);
    throw Object.assign(new Error('synthetic'), { code: 'SYNTHETIC_MODEL_UNAVAILABLE' });
  } }) });
  await m.enqueueExtraction(source(f.state.time));
  assert.equal((await m.extractOnce()).code, 'SYNTHETIC_MODEL_UNAVAILABLE'); assert.equal(m.status().held, 1);
  f.state.time += MAINTENANCE_POLICY.intervalMs;
  assert.equal((await m.extractOnce()).status, 'waiting'); assert.equal(requests, 1);
  const id = m.status().heldSources[0].id;
  assert.equal((await m.retryExtraction(id)).status, 'queued');
  await m.stop(); assert.equal(m.status().held, 1);
  assert.equal((await m.discardExtraction(id)).status, 'discarded');
});

test('stopping or revocation rejects late model results and does not erase protection metadata', async t => {
  const f = await fixture(t), entered = deferred(), release = deferred();
  let forgets = 0;
  const m = f.make({ privacy: { forgetSources: async () => { forgets++; } }, extraction: extractor([], {
    generate: async () => { entered.resolve(); await release.promise; return { candidates: [{ kind: 'fact', text: '迟到结果' }] }; },
  }) });
  await m.enqueueExtraction(source(f.state.time)); const pending = m.extractOnce(); await entered.promise;
  await m.stop(); release.resolve();
  assert.equal((await pending).code, 'MEMORY_MAINTENANCE_STOPPED'); assert.equal((await f.store.query()).total, 0);
  assert.equal(m.status().held, 1); assert.equal(forgets, 0);
  m.resume(); f.state.time += MAINTENANCE_POLICY.intervalMs;
  assert.equal((await m.extractOnce()).status, 'waiting');
});

test('unsupported candidate fields and obvious secret values cannot mutate memory settings or records', async t => {
  const f = await fixture(t);
  const m = f.make({ extraction: extractor([], { generate: async () => ({ candidates: [{ kind: 'fact', text: 'password: fictional-secret', enabled: true }] }) }) });
  await m.enqueueExtraction(source(f.state.time));
  assert.equal((await m.extractOnce()).code, 'MEMORY_INVALID_EXTRACTION_RESULT');
  assert.equal((await f.store.query()).total, 0); assert.equal(m.status().held, 1);
});

test('expiry invalidates aggregate epoch before conditional store maintenance but preserves manual summaries', async t => {
  const f = await fixture(t), order = [];
  const receipt = await f.store.record({ operationId: 'expiring', record: { kind: 'event', payload: { eventType: 'note', text: '到期原件' },
    retention: { mode: 'automatic', expiresAt: iso(f.state.time + 1000) } } });
  await f.store.record({ operationId: 'summary', record: { kind: 'fact', payload: { text: '用户明确保留的总结' },
    derivedFrom: [{ recordId: receipt.recordId, revisionId: receipt.revisionId }], retention: { mode: 'manual' } } });
  f.state.time += 2000;
  const wrapped = { status: () => f.store.status(), maintenancePlan: args => f.store.maintenancePlan(args),
    maintain: async args => { order.push('expire'); return f.store.maintain(args); } };
  const m = f.make({ store: wrapped, privacy: { forgetSources: async refs => { order.push('invalidate'); assert.match(refs[0], /^memory-epoch:/); } },
    aggregateSourceRef: status => `memory-epoch:${status.spaceId}:${status.disclosureEpoch}` });
  assert.equal((await m.maintainOnce()).status, 'completed'); assert.deepEqual(order, ['invalidate', 'expire']);
  assert.equal((await f.store.query()).total, 1); assert.equal((await f.store.query()).items[0].payload.text, '用户明确保留的总结');
});

test('incremental token budgeting leaves unsent sources pending rather than dropping or overfilling context', async t => {
  const f = await fixture(t); let sent;
  const m = f.make({ extraction: extractor([], {
    countTokens: async ({ input }) => input.sources.length * 2500,
    generate: async ({ input }) => { sent = input.sources; return { candidates: [] }; },
  }) });
  await m.enqueueExtraction(source(f.state.time, 'source:first')); await m.enqueueExtraction(source(f.state.time, 'source:second'));
  assert.equal((await m.extractOnce()).status, 'completed'); assert.equal(sent.length, 1);
  assert.equal(m.status().pending, 1); assert.equal(m.status().budget.tokens, 3500);
  assert.equal((await m.enqueueExtraction(source(f.state.time, 'source:first'))).status, 'duplicate');
});

test('multiple candidates from one source keep distinct stable ingestion identities', async t => {
  const f = await fixture(t);
  const m = f.make({ extraction: extractor([], { generate: async () => ({ candidates: [
    { kind: 'fact', text: '虚构事实一' }, { kind: 'fact', text: '虚构事实二' },
  ] }) }) });
  await m.enqueueExtraction(source(f.state.time));
  assert.equal((await m.extractOnce()).status, 'completed');
  const records = await f.store.query(); assert.equal(records.total, 2);
  assert.deepEqual(records.items[0].sourceRefs, records.items[1].sourceRefs);
});

test('daily budget survives reconstruction and prevents another model dispatch', async t => {
  const f = await fixture(t); const first = f.make();
  await first.enqueueExtraction(source(f.state.time));
  const saved = JSON.parse((await f.store.getSourceCursor({ source: 'maintenance.state.v1' })).cursor);
  saved.budget = { day: iso(f.state.time).slice(0, 10), calls: 24, tokens: 100 };
  await f.store.ingest({ operationId: 'synthetic-budget', records: [], sourceCursor: { source: 'maintenance.state.v1', cursor: JSON.stringify(saved) } });
  let generated = 0;
  const restarted = f.make({ extraction: extractor([], { generate: async () => { generated++; return { candidates: [] }; } }) });
  assert.equal((await restarted.extractOnce()).code, 'MEMORY_EXTRACTION_DAILY_BUDGET'); assert.equal(generated, 0);
});

test('an interrupted reserved job is held after reconstruction and cannot silently retry', async t => {
  const f = await fixture(t); const first = f.make();
  const queued = await first.enqueueExtraction(source(f.state.time));
  const saved = JSON.parse((await f.store.getSourceCursor({ source: 'maintenance.state.v1' })).cursor);
  saved.active = { ids: [queued.id] }; saved.budget = { day: iso(f.state.time).slice(0, 10), calls: 1, tokens: 1100 };
  await f.store.ingest({ operationId: 'synthetic-interruption', records: [], sourceCursor: { source: 'maintenance.state.v1', cursor: JSON.stringify(saved) } });
  const restarted = f.make({ extraction: extractor() });
  assert.equal((await restarted.extractOnce()).status, 'waiting');
  assert.equal(restarted.status().held, 1); assert.equal(restarted.status().budget.calls, 1);
  assert.equal(restarted.status().heldSources[0].code, 'MEMORY_EXTRACTION_INTERRUPTED');
});

test('expired queued extraction does not read its source body or automatically requeue', async t => {
  const f = await fixture(t), log = [], m = f.make({ extraction: extractor(log) });
  await m.enqueueExtraction({ ...source(f.state.time), expiresAt: iso(f.state.time + 1000) });
  f.state.time += 2000;
  assert.equal((await m.extractOnce()).status, 'waiting'); assert.deepEqual(log, []);
  assert.equal(m.status().heldSources[0].code, 'MEMORY_EXTRACTION_SOURCE_EXPIRED');
});

test('a cursor returning to an earlier queue value remains a new durable transition', async t => {
  const f = await fixture(t), m = f.make(), input = source(f.state.time);
  const queued = await m.enqueueExtraction(input);
  await m.stop(); await m.discardExtraction(queued.id); m.resume();
  assert.equal((await m.enqueueExtraction(input)).status, 'queued');
  const saved = JSON.parse((await f.store.getSourceCursor({ source: 'maintenance.state.v1' })).cursor);
  assert.equal(saved.queue.length, 1); assert.equal(saved.queue[0].state, 'pending');
  const reconstructed = f.make({ extraction: extractor() });
  assert.equal((await reconstructed.extractOnce()).status, 'completed');
});

test('extraction preserves private categories from sources even without a memory-record dependency', async t => {
  const f = await fixture(t), m = f.make({ extraction: extractor() });
  await m.enqueueExtraction({ ...source(f.state.time), categories: ['memory', 'location', 'activity'] });
  assert.equal((await m.extractOnce()).status, 'completed');
  const record = (await f.store.query()).items[0];
  assert.ok(record.privacy.categories.includes('location')); assert.ok(record.privacy.categories.includes('activity'));
});

test('deletion-only housekeeping expires an existing library while memory is disabled and stopped', async t => {
  const f = await fixture(t), order = [];
  const receipt = await f.store.record({ operationId: 'housekeeping-expiry', record: { kind: 'event', payload: { eventType: 'note', text: '合成到期原始记录' },
    retention: { mode: 'automatic', expiresAt: iso(f.state.time + 1000) } } });
  let initializations = 0;
  const m = f.make({ store: async () => { initializations++; throw new Error('must not initialize'); }, existingStore: async () => f.store,
    privacy: { forgetSources: async () => { order.push('invalidate'); } },
    aggregateSourceRef: status => `memory-epoch:${status.spaceId}:${status.disclosureEpoch}` });
  await m.stop(); f.state.enabled = false; f.state.time += 2000;
  assert.equal((await m.maintainOnce()).code, 'MEMORY_MAINTENANCE_STOPPED');
  assert.equal((await m.maintainOnce({ housekeeping: true })).status, 'completed');
  assert.equal(initializations, 0); assert.deepEqual(order, ['invalidate']);
  assert.equal((await f.store.get({ recordId: receipt.recordId })).payload, null);
  assert.equal((await m.ingestOnce()).code, 'MEMORY_MAINTENANCE_STOPPED');
});

test('housekeeping never initializes an absent library and still requires the execution safety gate', async t => {
  const f = await fixture(t); let opens = 0, existingCalls = 0;
  const m = f.make({ store: async () => { opens++; throw new Error('must not initialize'); },
    existingStore: async () => { existingCalls++; return null; } });
  f.state.enabled = false;
  assert.equal((await m.maintainOnce({ housekeeping: true })).status, 'empty'); assert.equal(opens, 0); assert.equal(existingCalls, 1);
  f.state.ready = false;
  assert.equal((await m.maintainOnce({ housekeeping: true })).code, 'MEMORY_EXECUTION_NOT_READY'); assert.equal(existingCalls, 1);
});

test('explicit discard removes pending metadata while disabled without opening or initializing a source', async t => {
  const f = await fixture(t); let lazyOpens = 0;
  const m = f.make({ store: async () => { lazyOpens++; return f.store; }, existingStore: async () => f.store });
  const receipt = await m.enqueueExtraction(source(f.state.time)); const before = lazyOpens;
  f.state.enabled = false; f.state.ready = false;
  assert.equal((await m.discardExtraction(receipt.id)).status, 'discarded'); assert.equal(lazyOpens, before); assert.equal(m.status().pending, 0);
  await assert.rejects(m.retryExtraction(receipt.id), /MEMORY_DISABLED/);
});


test('disabling and re-enabling during ingestion preserves the normal stop reason', async t => {
  const f = await fixture(t), entered = deferred(), release = deferred();
  const m = f.make({ history: { readBatch: async () => {
    entered.resolve(); await release.promise;
    return { batchId: null, items: [], continuityId: null };
  }, acknowledge: async () => assert.fail('Stopped ingestion must not acknowledge') } });
  const pending = m.ingestOnce(); await entered.promise;
  f.state.enabled = false; await m.stop('MEMORY_DISABLED');
  f.state.enabled = true; m.resume(); release.resolve();
  assert.equal((await pending).code, 'MEMORY_DISABLED');
  assert.equal(m.status().lastCode, 'MEMORY_DISABLED');
  assert.equal((await f.store.query()).total, 0);
});
