import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, rm, readFile, writeFile, copyFile, unlink, stat } from 'node:fs/promises';
import { join } from 'node:path';
import { tmpdir } from 'node:os';
import { spawn } from 'node:child_process';
import { once } from 'node:events';
import { DatabaseSync } from 'node:sqlite';
import { MemoryStore } from '../store.mjs';

const code = expected => error => error.code === expected;
const fact = (value = '虚构用户喜欢步行', extra = {}) => ({ kind: 'fact', payload: { text: value }, ...extra });
async function fixture(t, options = {}) {
  const root = await mkdtemp(join(tmpdir(), 'droy-memory-test-'));
  const directory = join(root, 'memory');
  let store = await MemoryStore.open({ directory, initialize: true, ...options });
  t.after(async () => { await store?.close().catch(() => {}); await rm(root, { recursive: true, force: true }); });
  return { root, directory, get store() { return store; },
    async close() { await store.close(); store = undefined; },
    async reopen() { store = await MemoryStore.open({ directory, ...options }); return store; } };
}

test('new initialization is explicit and ownership identities survive restart', async t => {
  const f = await fixture(t), before = await f.store.status();
  for (const id of ['spaceId', 'companionId', 'nodeId']) assert.match(before[id], /^[0-9a-f-]{36}$/);
  await f.close(); const after = await (await f.reopen()).status();
  for (const id of ['spaceId', 'companionId', 'nodeId']) assert.equal(after[id], before[id]);
  await assert.rejects(MemoryStore.open({ directory: join(f.root, 'absent') }), code('MEMORY_NOT_INITIALIZED'));
});
test('operation retries are durable and conflicting reuse does not write', async t => {
  const f = await fixture(t), args = { operationId: 'one', record: fact() };
  const first = await f.store.record(args), duplicate = await f.store.record(args);
  assert.equal(first.duplicate, false); assert.deepEqual(duplicate, { ...first, duplicate: true });
  await assert.rejects(f.store.record({ ...args, record: fact('不同内容') }), code('MEMORY_OPERATION_CONFLICT'));
  await f.close(); await f.reopen(); assert.deepEqual(await f.store.record(args), duplicate);
  assert.equal((await f.store.query()).total, 1); assert.equal((await f.store.changes()).items.length, 1);
});
test('forget planning pages all historical own sources without bodies or parent lineage and checks every revision', async t => {
  const f = await fixture(t);
  const parent = await f.store.record({ operationId: 'plan-parent', record: fact('fictional private parent', { sourceRefs: ['parent-owned-source'] }) });
  const first = Array.from({ length: 65 }, (_, i) => 'self-first-' + String(i).padStart(3, '0'));
  const second = Array.from({ length: 65 }, (_, i) => 'self-second-' + String(i).padStart(3, '0'));
  const record = await f.store.record({ operationId: 'plan-record', record: fact('fictional private body', {
    sourceRefs: first, derivedFrom: [{ recordId: parent.recordId, revisionId: parent.revisionId }] }) });
  const updated = await f.store.revise({ operationId: 'plan-revise', recordId: record.recordId,
    expectedRevisionId: record.revisionId, patch: { sourceRefs: second } });
  const input = { recordId: record.recordId, expectedRevisionId: updated.revisionId }, collected = [];
  let afterSource;
  do {
    const page = await f.store.forgetPlan({ ...input, ...(afterSource ? { afterSource } : {}) });
    assert.ok(page.sourceRefs.length <= 64);
    assert.equal(JSON.stringify(page).includes('fictional private'), false);
    assert.equal(page.sourceRefs.includes('parent-owned-source'), false);
    collected.push(...page.sourceRefs); afterSource = page.hasMore ? page.nextSource : undefined;
  } while (afterSource);
  assert.deepEqual(collected, [...first, ...second]);
  await assert.rejects(f.store.forgetPlan({ ...input, expectedRevisionId: record.revisionId }), code('MEMORY_REVISION_CONFLICT'));
  await assert.rejects(f.store.forgetPlan({ ...input, limit: 65 }), code('MEMORY_INVALID_INPUT'));
  const latest = await f.store.revise({ operationId: 'plan-revise-again', ...input, patch: { payload: { text: 'updated fixture' } } });
  await assert.rejects(f.store.forgetPlan({ ...input, afterSource: first[0] }), code('MEMORY_REVISION_CONFLICT'));
  await f.store.forget({ operationId: 'plan-forget', recordId: record.recordId, expectedRevisionId: latest.revisionId });
  for (const source of [first[0], second[0]]) await assert.rejects(f.store.record({ operationId: source, record: fact('late', { sourceRefs: [source] }) }), code('MEMORY_SOURCE_FORGOTTEN'));
  assert.ok((await f.store.get({ recordId: parent.recordId })).payload);
});
test('conditional correction preserves revisions and invalidates derived chains', async t => {
  const f = await fixture(t), base = await f.store.record({ operationId: 'base', record: fact('旧值') });
  const derived = await f.store.record({ operationId: 'derived', record: fact('推断', {
    assertion: 'model_inference', derivedFrom: [{ recordId: base.recordId, revisionId: base.revisionId }], state: 'candidate' }) });
  const leaf = await f.store.record({ operationId: 'leaf', record: fact('二级推断', {
    derivedFrom: [{ recordId: derived.recordId, revisionId: derived.revisionId }] }) });
  const updated = await f.store.revise({ operationId: 'fix', recordId: base.recordId,
    expectedRevisionId: base.revisionId, patch: { payload: { text: '新值' } } });
  assert.notEqual(updated.revisionId, base.revisionId); assert.equal(updated.invalidated.length, 2);
  assert.equal((await f.store.get({ recordId: leaf.recordId })).payload, null);
  assert.equal((await f.store.get({ recordId: derived.recordId })).state, 'expired');
  await assert.rejects(f.store.revise({ operationId: 'stale', recordId: base.recordId,
    expectedRevisionId: base.revisionId, patch: { payload: { text: '错误覆盖' } } }), code('MEMORY_REVISION_CONFLICT'));
  await assert.rejects(f.store.record({ operationId: 'late', record: fact('迟到推断', {
    derivedFrom: [{ recordId: base.recordId, revisionId: base.revisionId }] }) }), code('MEMORY_SOURCE_CHANGED'));
  assert.equal((await f.store.status()).disclosureEpoch, 1);
});
test('derived provenance includes transitive source and revision identities', async t => {
  const f = await fixture(t);
  const base = await f.store.record({ operationId: 'base', record: fact('虚构来源', { sourceRefs: ['node-a:session:one:range1'] }) });
  const child = await f.store.record({ operationId: 'child', record: fact('派生', { derivedFrom: [{ recordId: base.recordId, revisionId: base.revisionId }] }) });
  const row = await f.store.get({ recordId: child.recordId });
  assert.ok(row.lineageRefs.includes('node-a:session:one:range1'));
  assert.ok(row.lineageRefs.includes('memory-record:' + base.recordId));
  assert.ok(row.lineageRefs.includes('memory-revision:' + base.revisionId));
});
test('derived records inherit sensitive source categories instead of downgrading them', async t => {
  const f = await fixture(t);
  const raw = await f.store.record({ operationId: 'location', record: { kind: 'event', payload: {
    eventType: 'location', latitude: 0, longitude: 0, accuracyMeters: 100, sampledAt: '2026-10-01T00:00:00Z' } } });
  const result = await f.store.record({ operationId: 'summary', record: fact('合成地点推断', { derivedFrom: [{ recordId: raw.recordId, revisionId: raw.revisionId }] }) });
  assert.deepEqual((await f.store.get({ recordId: result.recordId })).privacy.categories, ['derived', 'location', 'memory']);
  await f.store.revise({ operationId: 'detach-correction', recordId: result.recordId, expectedRevisionId: result.revisionId,
    patch: { derivedFrom: [], payload: { text: '用户补充修正' } } });
  assert.deepEqual((await f.store.get({ recordId: result.recordId })).privacy.categories, ['derived', 'location', 'memory']);
});
test('forget suppresses sources removed by past corrections without suppressing independent parent sources', async t => {
  const f = await fixture(t), created = await f.store.record({ operationId: 'source-a', record: fact('初始事实', { sourceRefs: ['source:A'] }) });
  const changed = await f.store.revise({ operationId: 'source-b', recordId: created.recordId, expectedRevisionId: created.revisionId,
    patch: { sourceRefs: ['source:B'], payload: { text: '更正事实' } } });
  await f.store.forget({ operationId: 'forget', recordId: changed.recordId, expectedRevisionId: changed.revisionId });
  for (const source of ['source:A', 'source:B']) await assert.rejects(f.store.record({ operationId: 'late-' + source,
    record: fact('迟到旧提炼', { sourceRefs: [source] }) }), code('MEMORY_SOURCE_FORGOTTEN'));
});
test('explicit forget erases current and historical bodies and suppresses late source recreation', async t => {
  const f = await fixture(t), sourceRefs = ['node-a:session:one:range2'];
  const first = await f.store.record({ operationId: 'first', record: fact('敏感旧值', { sourceRefs }) });
  const second = await f.store.revise({ operationId: 'second', recordId: first.recordId,
    expectedRevisionId: first.revisionId, patch: { payload: { text: '敏感新值' } } });
  await f.store.forget({ operationId: 'forget', recordId: first.recordId, expectedRevisionId: second.revisionId });
  assert.equal((await f.store.get({ recordId: first.recordId })).payload, null);
  assert.equal((await f.store.query()).total, 0);
  const changes = await f.store.changes();
  assert.ok(changes.items.every(v => v.record.payload === null));
  await assert.rejects(f.store.record({ operationId: 'late', record: fact('重建', { sourceRefs }) }), code('MEMORY_SOURCE_FORGOTTEN'));
  await f.close(); await f.reopen();
  await assert.rejects(f.store.record({ operationId: 'late-restart', record: fact('重建', { sourceRefs }) }), code('MEMORY_SOURCE_FORGOTTEN'));
});
test('forget cascades to dependent material without forgetting its independent parent', async t => {
  const f = await fixture(t), parent = await f.store.record({ operationId: 'p', record: fact('父记录') });
  const child = await f.store.record({ operationId: 'c', record: fact('子记录', { sourceRefs: ['source:child'],
    derivedFrom: [{ recordId: parent.recordId, revisionId: parent.revisionId }] }) });
  await f.store.forget({ operationId: 'forget-child', recordId: child.recordId, expectedRevisionId: child.revisionId });
  assert.equal((await f.store.get({ recordId: parent.recordId })).state, 'active');
  const replacement = await f.store.record({ operationId: 'another-child', record: fact('独立新推断', {
    derivedFrom: [{ recordId: parent.recordId, revisionId: parent.revisionId }] }) });
  assert.ok(replacement.recordId);
});
test('ingest deduplicates across different batch retries and commits cursor atomically', async t => {
  const f = await fixture(t), event = { kind: 'event', sourceRefs: ['phone:android:event1'],
    occurredAt: '2026-10-01T08:00:00+08:00', retention: { mode: 'automatic', expiresAt: '2030-01-01T00:00:00Z' },
    payload: { eventType: 'activity', appId: 'example.synthetic', text: '虚构活动' } };
  const first = await f.store.ingest({ operationId: 'batch-1', records: [event], recordKeys: ['phone:android:event1'], sourceCursor: { source: 'phone', cursor: '1' } });
  const again = await f.store.ingest({ operationId: 'batch-2', records: [event], recordKeys: ['phone:android:event1'], sourceCursor: { source: 'phone', cursor: '1' } });
  assert.equal(first.items[0].recordId, again.items[0].recordId); assert.equal(again.items[0].duplicate, true);
  assert.deepEqual(await f.store.getSourceCursor({ source: 'phone' }), { source: 'phone', cursor: '1' });
  await assert.rejects(f.store.ingest({ operationId: 'broken-batch', records: [
    { ...event, sourceRefs: ['phone:android:event2'] }, { ...event, payload: { ...event.payload, text: '同来源不同值' } },
  ], recordKeys: ['phone:android:event2', 'phone:android:event1'], sourceCursor: { source: 'phone', cursor: '3' } }), code('MEMORY_INGEST_KEY_CONFLICT'));
  assert.equal((await f.store.query()).total, 1);
  assert.equal((await f.store.getSourceCursor({ source: 'phone' })).cursor, '1');
});
test('technical continuity gaps retain their first detection time across ingestion replay and restart', async t => {
  const f = await fixture(t);
  for (const reason of ['source_continuity_changed', 'source_continuity_unknown']) {
    const key = 'synthetic-node:continuity:' + reason;
    const record = { kind: 'event', sourceRefs: [key], retention: { mode: 'automatic' },
      payload: { eventType: 'gap', source: 'all', reason } };
    const input = { operationId: key, records: [record], recordKeys: [key] };
    const before = Date.now(), first = await f.store.ingest(input), after = Date.now();
    const saved = await f.store.get({ recordId: first.items[0].recordId });
    const detected = Date.parse(saved.occurredAt);
    assert.ok(detected >= before && detected <= after);
    assert.deepEqual(saved.payload, record.payload);
    assert.equal(Date.parse(saved.retention.expiresAt) - detected, 30 * 86400000);
    await f.close(); await f.reopen();
    assert.equal((await f.store.ingest(input)).duplicate, true);
    const resent = await f.store.ingest({ ...input, operationId: key + ':retry' });
    assert.equal(resent.items[0].duplicate, true);
    assert.deepEqual(await f.store.get({ recordId: resent.items[0].recordId }), saved);
    const included = await f.store.query({ eventTypes: ['gap'], from: saved.occurredAt,
      to: new Date(detected + 1).toISOString(), maxBytes: 12000 });
    assert.ok(included.items.some(row => row.recordId === saved.recordId));
    assert.ok(included.coverage.gaps >= 1);
    const earlier = await f.store.query({ eventTypes: ['gap'], from: new Date(detected - 1).toISOString(), to: saved.occurredAt });
    assert.equal(earlier.items.some(row => row.recordId === saved.recordId), false);
  }
  assert.equal((await f.store.query({ eventTypes: ['gap'] })).total, 2);
});
test('only explicit all-source continuity gaps may omit both range endpoints', async t => {
  const f = await fixture(t), start = '2026-10-01T00:00:00Z', end = '2026-10-01T01:00:00Z';
  const base = { eventType: 'gap', source: 'all', reason: 'source_continuity_changed' };
  const invalid = [
    { ...base, reason: 'paused' }, { ...base, reason: 'unknown' }, { ...base, source: 'location' },
    { ...base, startedAt: start }, { ...base, endedAt: end },
    { ...base, startedAt: null, endedAt: null },
  ];
  for (const [index, payload] of invalid.entries()) await assert.rejects(f.store.record({
    operationId: 'invalid-point-' + index, record: { kind: 'event', payload },
  }), code('MEMORY_INVALID_INPUT'));
  await assert.rejects(f.store.record({ operationId: 'reversed-gap', record: { kind: 'event',
    payload: { ...base, startedAt: end, endedAt: start } } }), code('MEMORY_INVALID_TIME'));
  const result = await f.store.record({ operationId: 'observed-gap', record: { kind: 'event',
    payload: { eventType: 'gap', source: 'location', reason: 'paused', startedAt: start, endedAt: end } } });
  assert.equal((await f.store.get({ recordId: result.recordId })).occurredAt, start);
});
test('one source may produce multiple independent facts and derived events without key collision', async t => {
  const f = await fixture(t), sourceRefs = ['phone:android:location1'];
  const raw = await f.store.ingest({ operationId: 'raw', records: [{ kind: 'event', sourceRefs,
    retention: { mode: 'manual' }, payload: { eventType: 'location', latitude: 0, longitude: 0, accuracyMeters: 10, sampledAt: '2026-10-01T00:00:00Z' } }], recordKeys: ['raw:location1'] });
  const pair = await f.store.ingest({ operationId: 'facts', recordKeys: ['job:one:fact1', 'job:one:fact2'], records: [
    fact('同来源第一事实', { sourceRefs }), fact('同来源第二事实', { sourceRefs }),
  ] });
  assert.equal(pair.items.length, 2); assert.notEqual(pair.items[0].recordId, pair.items[1].recordId);
  const derived = await f.store.ingest({ operationId: 'derived', recordKeys: ['stay:one'], records: [{ kind: 'event', sourceRefs,
    derivedFrom: [{ recordId: raw.items[0].recordId, revisionId: raw.items[0].revisionId }], retention: { mode: 'manual' },
    payload: { eventType: 'visit', latitude: 0, longitude: 0, accuracyMeters: 10, startedAt: '2026-10-01T00:00:00Z', endedAt: '2026-10-01T01:00:00Z' } }] });
  assert.equal(derived.items.length, 1); assert.equal((await f.store.query({ maxBytes: 12000 })).total, 4);
});
test('Chinese text query and stable pagination do not claim truncated results are complete', async t => {
  const f = await fixture(t);
  for (let i = 0; i < 12; i++) await f.store.record({ operationId: 'city-' + i, record: fact('虚构中文城市游记 ' + i,
    { occurredAt: new Date(Date.UTC(2026, 9, 1, i)).toISOString() }) });
  const first = await f.store.query({ text: '中文城市', limit: 3, maxBytes: 10000 });
  assert.equal(first.total, 12); assert.equal(first.items.length, 3); assert.equal(first.truncated, true);
  const second = await f.store.query({ text: '中文城市', limit: 3, maxBytes: 10000, cursor: first.nextCursor });
  assert.ok(second.items.every(row => !first.items.some(old => old.recordId === row.recordId)));
  await f.store.record({ operationId: 'new', record: fact() });
  await assert.rejects(f.store.query({ text: '中文城市', limit: 3, maxBytes: 10000, cursor: second.nextCursor }), code('MEMORY_CURSOR_STALE'));
});
test('whole-interval city aggregation covers records beyond recall page and unresolved locations', async t => {
  const f = await fixture(t);
  for (let i = 0; i < 15; i++) await f.store.ingest({ operationId: 'loc-' + i, records: [{ kind: 'event', sourceRefs: ['phone:location:' + i],
    retention: { mode: 'automatic', expiresAt: '2030-01-01T00:00:00Z' },
    payload: { eventType: 'location', latitude: 0, longitude: 0, accuracyMeters: 100, sampledAt: '2026-10-10T08:00:00+08:00',
      ...(i < 14 ? { city: '虚构城市' + i } : {}) } }] });
  const result = await f.store.aggregate({ groupBy: 'city', from: '2026-10-01T00:00:00+08:00', to: '2026-11-01T00:00:00+08:00' });
  assert.equal(result.complete, true); assert.equal(result.items.length, 14); assert.equal(result.total, 15);
  assert.equal(result.coverage.unresolvedLocations, 1); assert.equal(result.disclosureEpoch, 0);
});
test('city aggregation excludes explicitly mocked locations while retaining missing-status and unresolved evidence', async t => {
  const f = await fixture(t);
  const samples = [
    { city: '虚构甲城', isMock: false }, { city: '虚构甲城', isMock: true },
    { city: '模拟专用城', isMock: true }, { city: '旧记录城市' }, {}, { isMock: true },
  ];
  for (const [index, sample] of samples.entries()) await f.store.record({ operationId: 'mock-aggregate-' + index,
    record: { kind: 'event', occurredAt: '2026-10-10T00:00:00Z', retention: { mode: 'manual' }, payload: {
      eventType: index === 2 ? 'visit' : 'location', latitude: 0, longitude: 0, accuracyMeters: 10,
      ...(index === 2 ? { startedAt: '2026-10-10T00:00:00Z', endedAt: '2026-10-10T00:20:00Z' }
        : { sampledAt: '2026-10-10T00:00:00Z' }), ...sample } } });
  const cities = await f.store.aggregate({ groupBy: 'city' });
  assert.deepEqual(Object.fromEntries(cities.items.map(row => [row.value, row.count])), { '旧记录城市': 1, '虚构甲城': 1 });
  assert.equal(cities.total, 6); assert.equal(cities.coverage.mockLocations, 3);
  assert.equal(cities.coverage.unresolvedLocations, 2); assert.equal(cities.coverage.continuousObservationVerified, false);
  for (const groupBy of ['kind', 'day']) {
    const result = await f.store.aggregate({ groupBy });
    assert.equal(result.total, 6); assert.equal(result.items.reduce((count, row) => count + row.count, 0), 6);
  }
  assert.equal((await f.store.query()).coverage.mockLocations, 3);
});
test('output budget refuses a single oversized record without truncating its metadata', async t => {
  const f = await fixture(t); await f.store.record({ operationId: 'long', record: fact('内容'.repeat(1000)) });
  await assert.rejects(f.store.query({ maxBytes: 256 }), code('MEMORY_RESULT_TOO_LARGE'));
  assert.equal((await f.store.query({ maxBytes: 16000 })).items.length, 1);
});
test('summary projection returns a bounded UTF-8 preview and preserves identity for full expansion', async t => {
  const f = await fixture(t), content = '虚构🍀内容'.repeat(1000);
  const created = await f.store.record({ operationId: 'long', record: fact(content, { sourceRefs: ['fictional:source:long'] }) });
  const result = await f.store.query({ projection: 'summary' });
  assert.equal(result.items.length, 1); const row = result.items[0];
  assert.ok(Buffer.byteLength(row.payload.text) <= 512); assert.equal(row.contentTruncated, true);
  assert.ok(!row.payload.text.includes('\uFFFD')); assert.equal(row.recordId, created.recordId);
  assert.deepEqual(row.sourceRefs, ['fictional:source:long']);
  assert.equal((await f.store.get({ recordId: created.recordId })).payload.text, content);
});
test('metadata-only multi-cursor ingest is atomic and does not invent record changes or disclosure epochs', async t => {
  const f = await fixture(t), journal = JSON.stringify({ pending: 'x'.repeat(4096) });
  const result = await f.store.ingest({ operationId: 'journal', records: [], sourceCursors: [
    { source: 'maintenance.state.v1', cursor: journal }, { source: 'processed:fixture', cursor: 'done' },
  ] });
  assert.deepEqual(result.items, []); assert.equal((await f.store.getSourceCursor({ source: 'maintenance.state.v1' })).cursor, journal);
  assert.ok(!JSON.stringify(result).includes('pending'));
  assert.equal((await f.store.changes()).items.length, 0); assert.equal((await f.store.status()).disclosureEpoch, 0);
  await assert.rejects(f.store.ingest({ operationId: 'bad-journal', records: [], sourceCursors: [
    { source: 'processed:fixture', cursor: 'new' }, { source: 'invalid/source', cursor: 'not-committed' },
  ] }), code('MEMORY_INVALID_ID'));
  assert.equal((await f.store.getSourceCursor({ source: 'processed:fixture' })).cursor, 'done');
});
test('host-known extraction categories are retained even when its source is outside the record database', async t => {
  const f = await fixture(t);
  const result = await f.store.ingest({ operationId: 'extract', sourceCategories: ['activity', 'location'], recordKeys: ['extraction:external:1'],
    records: [fact('合成外部来源总结', { sourceRefs: ['phone:session:source1'], assertion: 'model_inference', retention: { mode: 'manual' } })] });
  assert.deepEqual((await f.store.get({ recordId: result.items[0].recordId })).privacy.categories, ['activity', 'derived', 'location', 'memory']);
  await assert.rejects(f.store.ingest({ operationId: 'reclassify', sourceCategories: [], recordKeys: ['extraction:external:1'],
    records: [fact('合成外部来源总结', { sourceRefs: ['phone:session:source1'], assertion: 'model_inference', retention: { mode: 'manual' } })] }), code('MEMORY_INGEST_KEY_CONFLICT'));
});
test('replaced cursor values are not retained in idempotency receipts', async t => {
  const f = await fixture(t), prior = 'SYNTHETIC_OLD_JOURNAL_VALUE';
  await f.store.ingest({ operationId: 'cursor-before', records: [], sourceCursor: { source: 'journal', cursor: prior } });
  await f.store.ingest({ operationId: 'cursor-after', records: [], sourceCursor: { source: 'journal', cursor: 'current' } });
  const db = new DatabaseSync(join(f.directory, 'memory.sqlite'), { readOnly: true });
  try {
    assert.ok(!JSON.stringify(db.prepare('SELECT receipt FROM receipts').all()).includes(prior));
    assert.equal(db.prepare('SELECT cursor FROM source_cursors WHERE source=?').get('journal').cursor, 'current');
  } finally { db.close(); }
});
test('delayed automatic input past retention commits only an expired receipt and never exposes its body', async t => {
  const f = await fixture(t), record = { kind: 'event', sourceRefs: ['phone:android:old'],
    occurredAt: '2020-01-01T00:00:00Z', payload: { eventType: 'activity', appId: 'example.synthetic', text: '过期缓冲正文' } };
  const batch = await f.store.ingest({ operationId: 'old-spool', records: [record], sourceCursor: { source: 'phone', cursor: 'ack-old' } });
  assert.equal(batch.items[0].state, 'expired'); assert.equal((await f.store.query()).total, 0);
  assert.equal((await f.store.get({ recordId: batch.items[0].recordId })).payload, null);
  assert.ok(!JSON.stringify(await f.store.changes()).includes('过期缓冲正文'));
  assert.equal((await f.store.getSourceCursor({ source: 'phone' })).cursor, 'ack-old');
});
test('large dependent deletion is complete while its receipt stays bounded', async t => {
  const f = await fixture(t), parent = await f.store.record({ operationId: 'p', record: fact('父记录') });
  for (let i = 0; i < 68; i++) await f.store.record({ operationId: 'c-' + i, record: fact('子记录 ' + i, {
    derivedFrom: [{ recordId: parent.recordId, revisionId: parent.revisionId }] }) });
  const result = await f.store.forget({ operationId: 'delete-tree', recordId: parent.recordId, expectedRevisionId: parent.revisionId });
  assert.equal(result.affectedCount, 69); assert.equal(result.affected.length, 64); assert.equal(result.affectedTruncated, true);
  assert.equal((await f.store.query()).total, 0); assert.equal((await f.store.status()).counts.deleted, 69);
});
test('retention plan is metadata only, races reject, and explicit retention survives expiry', async t => {
  const f = await fixture(t), expiresAt = '2026-01-01T00:00:00Z';
  const raw = await f.store.record({ operationId: 'raw', record: fact('到期原件', { retention: { mode: 'automatic', expiresAt } }) });
  const retained = await f.store.record({ operationId: 'keep', record: fact('用户保留的独立摘要', { derivedFrom: [{ recordId: raw.recordId, revisionId: raw.revisionId }] }) });
  let plan = await f.store.maintenancePlan({ now: '2026-10-01T00:00:00Z' });
  assert.equal(plan.targets.length, 1); assert.ok(!JSON.stringify(plan).includes('到期原件'));
  await f.store.record({ operationId: 'racing', record: fact('同时新增') });
  await assert.rejects(f.store.maintain({ now: plan.now, limit: plan.limit, expectedRevision: plan.revision }), code('MEMORY_REVISION_CONFLICT'));
  plan = await f.store.maintenancePlan({ now: plan.now });
  const result = await f.store.maintain({ now: plan.now, limit: plan.limit, expectedRevision: plan.revision });
  assert.equal(result.expired.length, 1); assert.equal((await f.store.get({ recordId: retained.recordId })).state, 'active');
  assert.equal((await f.store.get({ recordId: raw.recordId })).payload, null);
  assert.equal((await f.store.status()).disclosureEpoch, 1);
  const expired = await f.store.get({ recordId: raw.recordId });
  await f.store.forget({ operationId: 'explicit-forget-after-expiry', recordId: raw.recordId, expectedRevisionId: expired.revisionId });
  assert.equal((await f.store.get({ recordId: retained.recordId })).state, 'deleted');
});
test('dependency cycles are rejected without altering the original record', async t => {
  const f = await fixture(t), parent = await f.store.record({ operationId: 'parent', record: fact('父') });
  const child = await f.store.record({ operationId: 'child', record: fact('子', { derivedFrom: [{ recordId: parent.recordId, revisionId: parent.revisionId }] }) });
  await assert.rejects(f.store.revise({ operationId: 'cycle', recordId: parent.recordId, expectedRevisionId: parent.revisionId,
    patch: { derivedFrom: [{ recordId: child.recordId, revisionId: child.revisionId }] } }), code('MEMORY_DEPENDENCY_CYCLE'));
  assert.equal((await f.store.get({ recordId: parent.recordId })).revisionId, parent.revisionId);
});
test('consistent backup includes confirmed WAL records and stale raw restore is rejected', async t => {
  const f = await fixture(t), backupPath = join(f.root, 'snapshot.sqlite');
  const one = await f.store.record({ operationId: 'one', record: fact('WAL中的确认记录') });
  assert.ok((await stat(join(f.directory, 'memory.sqlite-wal'))).size > 0);
  await f.store.backup({ path: backupPath });
  assert.equal((await stat(backupPath)).mode & 0o077, 0);
  const snapshot = new DatabaseSync(backupPath, { readOnly: true });
  assert.equal(snapshot.prepare('SELECT record_id FROM records').get().record_id, one.recordId); snapshot.close();
  await f.store.record({ operationId: 'two', record: fact('后续确认记录') }); await f.close();
  await copyFile(backupPath, join(f.directory, 'memory.sqlite'));
  await assert.rejects(f.reopen(), code('MEMORY_STORE_ROLLBACK'));
});
test('encrypted selected export imports into a fresh space with fresh IDs and no foreign grants', async t => {
  const f = await fixture(t), one = await f.store.record({ operationId: 'one', record: fact('选中的虚构秘密') });
  await f.store.record({ operationId: 'two', record: fact('未选中的内容') });
  const path = join(f.root, 'selection.drmemory'), passphrase = 'fictional-test-passphrase-only';
  await f.store.exportArchive({ path, passphrase, recordIds: [one.recordId] });
  const bytes = await readFile(path); assert.ok(!bytes.includes(Buffer.from('选中的虚构秘密')));
  const destination = await MemoryStore.open({ directory: join(f.root, 'imported'), initialize: true });
  try {
    await assert.rejects(destination.importArchive({ operationId: 'bad', path, passphrase: 'wrong-test-passphrase' }), code('MEMORY_ARCHIVE_AUTHENTICATION'));
    const result = await destination.importArchive({ operationId: 'import', path, passphrase });
    assert.equal(result.imported.length, 1); assert.notEqual(result.imported[0].recordId, one.recordId);
    assert.notEqual((await destination.status()).spaceId, (await f.store.status()).spaceId);
    assert.equal((await destination.query()).items[0].payload.text, '选中的虚构秘密');
    assert.equal((await destination.importArchive({ operationId: 'import', path, passphrase })).duplicate, true);
    await assert.rejects(f.store.importArchive({ operationId: 'nonempty', path, passphrase }), code('MEMORY_IMPORT_REQUIRES_EMPTY_SPACE'));
  } finally { await destination.close(); }
});
test('tampered archive and export overwrite are rejected', async t => {
  const f = await fixture(t), item = await f.store.record({ operationId: 'one', record: fact() });
  const path = join(f.root, 'encrypted'), passphrase = 'fictional-long-passphrase';
  await f.store.exportArchive({ path, passphrase, recordIds: [item.recordId] });
  await assert.rejects(f.store.exportArchive({ path, passphrase, recordIds: [item.recordId] }), code('MEMORY_INVALID_DESTINATION'));
  const bytes = await readFile(path); bytes[bytes.length - 1] ^= 1; await writeFile(path, bytes);
  await assert.rejects(f.store.importArchive({ operationId: 'tampered', path, passphrase }), code('MEMORY_ARCHIVE_AUTHENTICATION'));
});
test('archive import preserves inherited private categories and relationships between selected records', async t => {
  const f = await fixture(t), raw = await f.store.record({ operationId: 'loc', record: { kind: 'event', payload: {
    eventType: 'location', latitude: 0, longitude: 0, accuracyMeters: 100, sampledAt: '2026-10-01T00:00:00Z' } } });
  const summary = await f.store.record({ operationId: 'summary', record: fact('虚构地点总结', {
    derivedFrom: [{ recordId: raw.recordId, revisionId: raw.revisionId }] }) });
  const passphrase = 'fictional-archive-passphrase', path = join(f.root, 'private-selection');
  await f.store.exportArchive({ path, passphrase, recordIds: [summary.recordId, raw.recordId] });
  const destination = await MemoryStore.open({ directory: join(f.root, 'restored'), initialize: true });
  try {
    const result = await destination.importArchive({ operationId: 'restore', path, passphrase });
    const child = result.imported.find(v => v.importedRecordId === summary.recordId), parent = result.imported.find(v => v.importedRecordId === raw.recordId);
    const row = await destination.get({ recordId: child.recordId });
    assert.deepEqual(row.privacy.categories, ['derived', 'location', 'memory']);
    assert.deepEqual(row.derivedFrom, [{ recordId: parent.recordId, revisionId: parent.revisionId }]);
    await destination.forget({ operationId: 'delete-imported-source', recordId: parent.recordId, expectedRevisionId: parent.revisionId });
    assert.equal((await destination.get({ recordId: child.recordId })).state, 'deleted');
  } finally { await destination.close(); }
  const soloPath = join(f.root, 'private-child-only');
  await f.store.exportArchive({ path: soloPath, passphrase, recordIds: [summary.recordId] });
  const solo = await MemoryStore.open({ directory: join(f.root, 'solo'), initialize: true });
  try {
    await solo.importArchive({ operationId: 'solo', path: soloPath, passphrase });
    assert.deepEqual((await solo.query()).items[0].privacy.categories, ['derived', 'location', 'memory']);
  } finally { await solo.close(); }
});
test('unknown schema never becomes an empty replacement store', async t => {
  const f = await fixture(t); await f.store.record({ operationId: 'one', record: fact() }); await f.close();
  const db = new DatabaseSync(join(f.directory, 'memory.sqlite')); db.exec('PRAGMA user_version=999'); db.close();
  await assert.rejects(MemoryStore.open({ directory: f.directory, initialize: true }), code('MEMORY_FORMAT_UNSUPPORTED'));
  const verify = new DatabaseSync(join(f.directory, 'memory.sqlite'), { readOnly: true }); assert.equal(verify.prepare('SELECT COUNT(*) n FROM records').get().n, 1); verify.close();
});
test('missing marker and owner cannot silently recreate authority', async t => {
  const f = await fixture(t); await f.store.record({ operationId: 'one', record: fact() });
  await unlink(f.directory + '.identity.json');
  await assert.rejects(f.store.query(), code('MEMORY_MARKER_MISSING'));
  await f.close().catch(() => {});
  await assert.rejects(MemoryStore.open({ directory: f.directory, initialize: true }), code('MEMORY_INITIALIZATION_INCOMPLETE'));
});
test('single-writer lock rejects a second owner and survives owner-file deletion', async t => {
  const f = await fixture(t);
  await assert.rejects(MemoryStore.open({ directory: f.directory }), code('MEMORY_STORE_IN_USE'));
  await unlink(join(f.directory, 'memory-owner.sqlite'));
  await assert.rejects(f.store.status(), code('MEMORY_OWNER_MISSING'));
  await assert.rejects(MemoryStore.open({ directory: f.directory }), code('MEMORY_OWNER_MISSING'));
});
test('bounded queue rejects excess work and close drains accepted writes', async t => {
  const f = await fixture(t, { maxPending: 1 });
  const first = f.store.record({ operationId: 'one', record: fact() });
  await assert.rejects(f.store.record({ operationId: 'two', record: fact() }), code('MEMORY_BUSY'));
  const closing = f.close(); await first; await closing; await f.reopen(); assert.equal((await f.store.query()).total, 1);
});
test('invalid event coordinates, time, unknown fields and excess lineage are rejected', async t => {
  const f = await fixture(t);
  await assert.rejects(f.store.record({ operationId: 'badloc', record: { kind: 'event', payload: { eventType: 'location', latitude: 91, longitude: 0, accuracyMeters: 1, sampledAt: '2026-10-01T00:00:00Z' } } }), code('MEMORY_INVALID_INPUT'));
  await assert.rejects(f.store.record({ operationId: 'unknown', record: { ...fact(), executable: 'never' } }), code('MEMORY_INVALID_INPUT'));
  await assert.rejects(f.store.record({ operationId: 'bad-date', record: fact('日期', { occurredAt: '2026-02-30T00:00:00Z' }) }), code('MEMORY_INVALID_TIME'));
  await assert.rejects(f.store.record({ operationId: 'sources', record: fact('多来源', { sourceRefs: Array.from({ length: 127 }, (_, i) => 'source:' + i) }) }), code('MEMORY_SOURCE_LIMIT'));
  assert.equal((await f.store.query()).total, 0);
});
test('location and visit preserve explicit mock flags without relabeling older records', async t => {
  const f = await fixture(t), expected = [];
  for (const eventType of ['location', 'visit']) {
    const payload = { eventType, latitude: 0, longitude: 0, accuracyMeters: 10,
      ...(eventType === 'location' ? { sampledAt: '2026-10-01T00:00:00Z' }
        : { startedAt: '2026-10-01T00:00:00Z', endedAt: '2026-10-01T00:20:00Z' }) };
    for (const isMock of [true, false, undefined]) {
      const receipt = await f.store.record({ operationId: `${eventType}-mock-${isMock}`,
        record: { kind: 'event', retention: { mode: 'manual' }, payload: { ...payload,
          ...(isMock === undefined ? {} : { isMock }) } } });
      expected.push({ recordId: receipt.recordId, isMock });
    }
    for (const isMock of ['false', 0, null]) await assert.rejects(f.store.record({
      operationId: `${eventType}-invalid-mock-${isMock}`, record: { kind: 'event', payload: { ...payload, isMock } },
    }), code('MEMORY_INVALID_INPUT'));
  }
  await f.close(); await f.reopen();
  for (const row of expected) {
    const stored = await f.store.get({ recordId: row.recordId });
    assert.equal(stored.payload.isMock, row.isMock);
    assert.equal(Object.hasOwn(stored.payload, 'isMock'), row.isMock !== undefined);
  }
});
test('acknowledged child-process write survives SIGKILL without stale owner lock', async t => {
  const f = await fixture(t); await f.close();
  const moduleUrl = new URL('../store.mjs', import.meta.url).href;
  const child = spawn(process.execPath, ['--input-type=module', '--eval', `
    import {MemoryStore} from ${JSON.stringify(moduleUrl)};
    const store=await MemoryStore.open({directory:${JSON.stringify(f.directory)}});
    const result=await store.record({operationId:'crash-write',record:{kind:'fact',payload:{text:'合成已确认记录'}}});
    process.send(result);setInterval(()=>{},1000);
  `], { stdio: ['ignore', 'ignore', 'pipe', 'ipc'] });
  t.after(() => { if (!child.killed) child.kill('SIGKILL'); });
  const [receipt] = await once(child, 'message'); const exited = once(child, 'exit'); child.kill('SIGKILL'); await exited;
  await f.reopen(); assert.equal((await f.store.get({ recordId: receipt.recordId })).payload.text, '合成已确认记录');
  assert.equal((await f.store.changes()).items.length, 1);
});
