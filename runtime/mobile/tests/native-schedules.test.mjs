import test, { after } from 'node:test';
import assert from 'node:assert/strict';
import { createServer, request as httpRequest } from 'node:http';
import { EventEmitter, once } from 'node:events';
import { createRequire, registerHooks } from 'node:module';
import { pathToFileURL } from 'node:url';
import { Readable } from 'node:stream';
import { requireMobileSession } from '../auth.mjs';

const installed = createRequire(new URL('../../dsh/package.json', import.meta.url));
const hooks = registerHooks({ resolve(specifier, context, next) {
  if (specifier.startsWith('@deepseek-ai/')) return { url: pathToFileURL(installed.resolve(specifier)).href, shortCircuit: true };
  return next(specifier, context);
} });
const { createNativeSchedules } = await import('../native-schedules.mjs');
const { ScheduleService, decodeScheduleRecord } = await import('@deepseek-ai/dsh-schedule');
const { TYPERT } = await import('@deepseek-ai/dsh-schedule/typert');
hooks.deregister();

const token = 'fictional-native-schedules-session-token-123456789';
const secret = 'fictional-schedule-private-error-never-return';
const previousToken = process.env.DROY_WEB_TOKEN;
process.env.DROY_WEB_TOKEN = token;
after(() => { if (previousToken === undefined) delete process.env.DROY_WEB_TOKEN; else process.env.DROY_WEB_TOKEN = previousToken; });
const record = { id: 'fixture-after', kind: 'after', title: 'Fictional reminder', prompt: 'Fictional instruction',
  afterSeconds: 90, scheduledAt: '2099-01-01T12:00:00.000Z' };
const managed = { id: 'fixture-managed', kind: 'weekly', title: 'Fictional companion reminder', prompt: 'Fictional companion instruction',
  weekdays: [1, 3], time: '09:00:00.000', timeZone: 'UTC', scheduledAt: '2099-01-02T09:00:00.000Z' };
const ended = { id: 'fixture-ended', kind: 'at', title: 'Fictional completed reminder', prompt: 'Fictional earlier instruction',
  scheduledAt: '2020-01-01T12:00:00.000Z' };
const deliveries = [1, 2, 3].map(index => ({ scheduledAt: `2020-01-0${index}T12:00:00.000Z`,
  deliveredAt: `2020-01-0${index}T12:00:01.000Z`, messageId: 'delivery-' + index, prompt: 'Previously sent instruction ' + index }));
const methods = new Map(TYPERT.invocations.filter(row => row.namespace === 'schedule').map(row => [row.method, row]));
const createRequest = (request = { title: 'New fictional reminder', prompt: 'New fictional instruction', after_seconds: 90 }, sessionId = 'work-a') => ({ sessionId, request });
function contract(method, args, result) {
  if (method === 'create') { if (result !== undefined) decodeScheduleRecord(result); return; }
  const descriptor = methods.get(method);
  descriptor.input({ strict: true }).create().parse(Object.fromEntries(descriptor.parameters.map((parameter, index) => [parameter.wire, args[index]])));
  if (result !== undefined) descriptor.result.create().parse(result);
}

async function fixture(t, options = {}) {
  const calls = [], events = new EventEmitter(), rows = new Map();
  const history = { records: structuredClone(deliveries), earlierRecordsUnavailable: true, earlierRecordsPruned: true };
  for (const [value, sessionId, status] of [[record, 'work-a', 'active'], [managed, 'managed-session', 'active'], [ended, 'work-a', 'inactive']]) {
    const { prompt: _prompt, ...lastDelivery } = deliveries.at(-1);
    rows.set(value.id, { record: structuredClone(value), sessionId, status, lastDelivery, deliveryHistory: structuredClone(history) });
  }
  const ctx = {
    get(name) { return this[name]; },
    settings: { describe: () => [{ ns: 'locale', value: { preference: options.locale || 'en' } }] },
    logger: { warn() {} }, emit(name) { calls.push({ kind: 'event', name }); },
    droyCompanions: { ownsSession: id => id === 'managed-session' },
    agents: { get(id) {
      calls.push({ kind: 'agent-read', id });
      return id === 'delegated-live' ? { options: { subagentDepth: 1 }, session: { header: {} } } : undefined;
    } },
    sessionController: { resolveAgent() { assert.fail('Schedule management must not resume an Agent'); } },
    sessionPersistence: { async stat(id, statOptions) {
      calls.push({ kind: 'stat', id, signal: statOptions.signal });
      if (options.statFailure) throw options.statFailure;
      if (!['work-a', 'work-b', 'managed-session', 'delegated-cold', 'delegated-live'].includes(id)) return undefined;
      return { header: { version: 1, id, createdAt: 1, isSeeded: false, cwd: '/fictional/work', ...(id === 'delegated-cold' ? { delegationDepth: 1 } : {}) }, revision: 'fictional-revision' };
    } },
  };
  const table = {
    entries: () => rows.entries(), get: id => rows.get(id),
    async put(id, value) {
      calls.push({ kind: 'put-start', id }); await options.beforeWrite?.('put', id, value);
      rows.set(id, structuredClone(value)); calls.push({ kind: 'put-committed', id });
    },
    async delete(id) {
      calls.push({ kind: 'delete-start', id }); await options.beforeWrite?.('delete', id);
      rows.delete(id); calls.push({ kind: 'delete-committed', id });
    },
  };
  const domain = { table(name) { assert.equal(name, 'tasks'); return table; } };
  // Real public methods and their FIFO, binding, rule, observed-record and
  // receipt behavior run over an isolated domain. No delivery runtime starts.
  const schedule = Object.create(ScheduleService.prototype, { ctx: { value: ctx } });
  Object.assign(schedule, { chain: Promise.resolve(), ready: Promise.resolve(domain), initialized: Promise.resolve(),
    stopping: false, retention: { days: 30, records: 200 }, runtime: { requestDrive() { calls.push({ kind: 'drive-request' }); } } });
  ctx.schedule = schedule;
  for (const method of ['catalog', 'create', 'update', 'delete', 'history']) {
    const original = ScheduleService.prototype[method];
    schedule[method] = async (...args) => {
      contract(method, args); calls.push({ kind: method, args }); events.emit('invoke', method);
      if (options.failure) throw options.failure;
      const value = options.resultOverride ?? await original.apply(schedule, args);
      contract(method, args, value); return value;
    };
  }
  const api = createNativeSchedules(ctx), controller = options.controller ?? new AbortController();
  const server = createServer(async (req, res) => {
    if (!requireMobileSession(req, res)) return;
    const disconnected = () => { if (!res.writableEnded) controller.abort(); };
    res.once('close', disconnected);
    try { await api.handle(req, res, controller.signal); } finally { res.removeListener('close', disconnected); }
  });
  server.listen(0, '127.0.0.1'); await once(server, 'listening');
  t.after(async () => { controller.abort(); server.closeAllConnections(); await new Promise(resolve => server.close(resolve)); });
  const url = `http://127.0.0.1:${server.address().port}/droy/native/schedules`;
  const headers = { Cookie: `droy_session=${token}`, 'Content-Type': 'application/json' };
  async function request(path = '', input, extra = {}) {
    const response = await fetch(url + path, { headers, ...(input === undefined ? {} : { method: 'POST', body: JSON.stringify(input) }), ...extra });
    const text = await response.text(); assert.ok(!text.includes(secret), 'Never expose a raw exception, secret-bearing code or diagnostic');
    return { status: response.status, headers: response.headers, text, value: text.startsWith('{') ? JSON.parse(text) : null };
  }
  return { ctx, calls, rows, events, controller, request, url, headers };
}

test('the shared authentication boundary precedes schedule or target access', async t => {
  const f = await fixture(t);
  for (const path of ['', '/create', '/history']) assert.equal((await f.request(path, {}, { headers: {} })).status, 401);
  assert.equal((await f.request('', undefined, { headers: { ...f.headers, Origin: 'https://foreign.invalid' } })).status, 401);
  assert.equal(f.calls.length, 0);
});

test('catalog preserves original bindings, inactive tasks and receipts without activating or reading Sessions', async t => {
  const f = await fixture(t), response = await f.request();
  assert.equal(response.status, 200); assert.equal(response.headers.get('cache-control'), 'no-store');
  const result = response.value.result;
  contract('catalog', [], result);
  assert.equal(result.length, 3);
  assert.deepEqual(result.map(row => row.id), [ended.id, record.id, managed.id]);
  assert.equal(result[0].status, 'inactive'); assert.equal(result[2].sessionId, 'managed-session');
  assert.equal(result[1].lastDelivery.messageId, 'delivery-3');
  assert.deepEqual(f.calls.map(call => call.kind), ['catalog']);
});

test('create supports all public selectors and the actual service owns normalization and persistence', async t => {
  const f = await fixture(t);
  const rules = [['after', { after_seconds: 90 }], ['at', { at: '2099-03-01T12:00:00Z' }],
    ['at', { at: { date: '2099-03-02', time: '09:00:00', time_zone: 'UTC' } }], ['every', { every_seconds: 120 }],
    ['daily', { daily: { time: '09:00:00', time_zone: 'Asia/Shanghai' } }],
    ['weekly', { weekly: { time: '10:00:00', time_zone: 'UTC', weekdays: [3, 1] } }],
    ['cron', { cron: { expression: '*/15 * * * *', time_zone: 'UTC' } }]];
  for (const [kind, rule] of rules) {
    const input = createRequest({ title: '  Trimmed fictional title  ', prompt: 'Fictional instruction', ...rule });
    const response = await f.request('/create', input); assert.equal(response.status, 200, kind);
    const created = response.value.result; assert.equal(created.kind, kind); assert.equal(created.title, 'Trimmed fictional title');
    decodeScheduleRecord(created); assert.equal(f.rows.get(created.id).sessionId, 'work-a');
    const invocation = f.calls.findLast(call => call.kind === 'create');
    assert.deepEqual(invocation.args.slice(0, 2), [input.sessionId, input.request]);
    assert.equal(invocation.args[2], f.controller.signal);
  }
  assert.equal(f.rows.size, 10);
  assert.equal(f.calls.filter(call => call.kind === 'stat').length, rules.length);
});

test('strict request shapes refuse unknown fields, spoofed binding, missing observations and unsupported enable flags', async t => {
  const f = await fixture(t);
  for (const [path, input] of [
    ['/create', { ...createRequest(), enabled: true }], ['/create', createRequest({ title: 'A', prompt: 'B', after_seconds: 60, apiKey: secret })],
    ['/create', createRequest({ title: 'A', prompt: 'B', weekly: { time: '09:00:00', time_zone: 'UTC', weekdays: [1], sessionId: 'other' } })],
    ['/create', createRequest({ title: 'A', prompt: 'B', after_seconds: '60' })], ['/create', []],
    ['/update', { sessionId: 'work-a', id: record.id, title: 'No observed record' }],
    ['/update', { sessionId: 'work-a', id: record.id, expected: { ...record, sessionId: 'work-a' } }],
    ['/update', { sessionId: 'work-a', id: record.id, expected: record, enabled: false }],
    ['/update', { sessionId: 'work-a', id: record.id, expected: record, change: { kind: 'after', after_seconds: 90 } }],
    ['/delete', { sessionId: 'work-a', id: record.id, cancelAgent: true }], ['/delete', { sessionId: '', id: record.id }],
    ['/history', { sessionId: 'work-a', id: record.id, limit: 0 }], ['/history', { sessionId: 'work-a', id: record.id, limit: 1.5 }],
    ['/history', { sessionId: 'work-a', id: record.id, limit: 101 }], ['/history', { sessionId: 'work-a', id: record.id, limit: 5, before: '' }],
  ]) {
    const response = await f.request(path, input); assert.equal(response.status, 400, path);
    assert.equal(response.value.error.code, 'NATIVE_SCHEDULES_BAD_REQUEST');
  }
  assert.equal(f.calls.length, 0); assert.equal(f.rows.size, 3);
});

test('rule validation uses public service outcomes instead of persisting invalid schedules', async t => {
  const f = await fixture(t);
  const invalid = [[{ title: ' ', prompt: 'A', after_seconds: 60 }, 'invalid_prompt'],
    [{ title: 'A', prompt: ' ', after_seconds: 60 }, 'invalid_prompt'], [{ title: 'A', prompt: 'B' }, 'invalid_selector'],
    [{ title: 'A', prompt: 'B', after_seconds: 60, every_seconds: 120 }, 'invalid_selector'],
    [{ title: 'A', prompt: 'B', every_seconds: 59 }, 'frequency_too_high'],
    [{ title: 'A', prompt: 'B', at: '2020-01-01T00:00:00Z' }, 'not_future'],
    [{ title: 'A', prompt: 'B', daily: { time: '09:00:00', time_zone: 'Fictional/Invalid' } }, 'invalid_time_zone'],
    [{ title: 'A', prompt: 'B', weekly: { time: '09:00:00', time_zone: 'UTC', weekdays: [1, 1] } }, 'invalid_rule']];
  for (const [input, code] of invalid) {
    const response = await f.request('/create', createRequest(input)); assert.equal(response.status, 422, code);
    assert.equal(response.value.error.code, code);
  }
  assert.equal(f.rows.size, 3); assert.equal(f.calls.some(call => call.kind === 'put-start'), false);
});

test('creation requires a real ordinary Session and never treats a failed observation as absence', async t => {
  const f = await fixture(t);
  for (const [sessionId, status, code] of [['missing', 404, 'NATIVE_SCHEDULES_SESSION_NOT_FOUND'],
    ['managed-session', 409, 'COMPANION_SESSION_MANAGED'], ['delegated-cold', 422, 'subagent_session'], ['delegated-live', 422, 'subagent_session']]) {
    const response = await f.request('/create', createRequest(undefined, sessionId));
    assert.equal(response.status, status); assert.equal(response.value.error.code, code);
  }
  const original = f.ctx.sessionPersistence.stat;
  f.ctx.sessionPersistence.stat = async () => { throw new Error(secret); };
  assert.equal((await f.request('/create', createRequest())).value.error.code, 'NATIVE_SCHEDULES_FAILED');
  f.ctx.sessionPersistence.stat = async () => ({ header: { id: 'wrong-binding', cwd: '/fictional' } });
  assert.equal((await f.request('/create', createRequest())).status, 503);
  f.ctx.sessionPersistence.stat = async id => ({ header: { id } });
  assert.equal((await f.request('/create', createRequest())).value.error.code, 'NATIVE_SCHEDULES_TARGET_UNAVAILABLE');
  f.ctx.sessionPersistence.stat = original; delete f.ctx.droyCompanions.ownsSession;
  assert.equal((await f.request('/create', createRequest())).status, 503);
  assert.equal(f.rows.size, 3);
});

test('observed-record updates preserve committed timing, saved deliveries and genuine no-op or conflict outcomes', async t => {
  const f = await fixture(t), before = structuredClone(f.rows.get(record.id));
  const input = { sessionId: 'work-a', id: record.id, expected: record, title: 'Renamed fictional reminder', prompt: 'A revised instruction' };
  const response = await f.request('/update', input); assert.equal(response.status, 200);
  const changed = response.value.result; assert.equal(changed.updated, true); assert.equal(changed.record.scheduledAt, record.scheduledAt);
  assert.equal(changed.record.title, input.title); assert.equal(changed.record.prompt, input.prompt);
  assert.deepEqual(f.rows.get(record.id).deliveryHistory, before.deliveryHistory); assert.equal(f.rows.get(record.id).sessionId, 'work-a');
  const noop = await f.request('/update', { sessionId: 'work-a', id: record.id, expected: changed.record });
  assert.deepEqual(noop.value.result, { id: record.id, updated: false, record: changed.record });
  const stale = await f.request('/update', input);
  assert.deepEqual(stale.value, { ok: true, result: { id: record.id, updated: false, code: 'schedule_conflict' } });
  assert.equal(f.calls.filter(call => call.kind === 'put-committed').length, 1);
});

test('concurrent edits compare the entire observed record inside the original FIFO', async t => {
  const f = await fixture(t);
  const input = { sessionId: 'work-a', id: record.id, expected: record };
  const responses = await Promise.all([f.request('/update', { ...input, title: 'First edit' }), f.request('/update', { ...input, title: 'Second edit' })]);
  assert.equal(responses.filter(response => response.value.result.updated === true).length, 1);
  assert.equal(responses.filter(response => response.value.result.code === 'schedule_conflict').length, 1);
  assert.equal(f.calls.filter(call => call.kind === 'put-committed').length, 1);
  const current = f.rows.get(record.id).record;
  const moved = await f.request('/update', { ...input, expected: { ...current, scheduledAt: '2099-01-01T12:01:00.000Z' }, prompt: 'Wrong target snapshot' });
  assert.equal(moved.value.result.code, 'schedule_conflict');
});

test('existing companion tasks stay manageable and changes cannot move their original Session binding', async t => {
  const f = await fixture(t);
  f.ctx.droyCompanions.ownsSession = () => { assert.fail('Existing schedule management does not impose a new companion policy'); };
  const update = await f.request('/update', { sessionId: 'managed-session', id: managed.id, expected: managed, title: 'New companion task name' });
  assert.equal(update.value.result.updated, true); assert.equal(f.rows.get(managed.id).sessionId, 'managed-session');
  const wrong = await f.request('/update', { sessionId: 'work-a', id: managed.id, expected: update.value.result.record, title: 'Wrong owner' });
  assert.deepEqual(wrong.value.result, { id: managed.id, updated: false, code: 'schedule_not_found' });
  const history = await f.request('/history', { sessionId: 'managed-session', id: managed.id, limit: 1 });
  assert.equal(history.value.result.records[0].messageId, 'delivery-3');
  const deleted = await f.request('/delete', { sessionId: 'managed-session', id: managed.id });
  assert.deepEqual(deleted.value.result, { id: managed.id, deleted: true });
  assert.equal(f.calls.some(call => call.kind === 'stat'), false);
});

test('legacy child-bound tasks retain the original non-mutating edit refusal and remain deletable', async t => {
  const f = await fixture(t), id = 'fixture-legacy-child', stored = structuredClone(f.rows.get(record.id));
  stored.sessionId = 'delegated-live'; stored.record.id = id; f.rows.set(id, stored);
  const edit = await f.request('/update', { sessionId: 'delegated-live', id, expected: stored.record, title: 'A refused timing edit' });
  assert.equal(edit.status, 200); assert.equal(edit.value.result.code, 'subagent_session');
  assert.equal(f.rows.get(id).record.title, record.title);
  assert.equal(f.calls.some(call => call.kind === 'put-start'), false);
  const deleted = await f.request('/delete', { sessionId: 'delegated-live', id });
  assert.deepEqual(deleted.value.result, { id, deleted: true }); assert.equal(f.rows.has(id), false);
});

test('timing edits use the public rule union and inactive schedules are never re-armed', async t => {
  const f = await fixture(t);
  const response = await f.request('/update', { sessionId: 'work-a', id: record.id, expected: record,
    change: { kind: 'weekly', weekly: { time: '10:00:00', time_zone: 'UTC', weekdays: [1, 5] } } });
  assert.equal(response.value.result.record.kind, 'weekly'); assert.equal(response.value.result.updated, true);
  assert.deepEqual(response.value.result.record.weekdays, [1, 5]);
  const inactive = await f.request('/update', { sessionId: 'work-a', id: ended.id, expected: ended, title: 'Cannot rearm' });
  assert.deepEqual(inactive.value.result, { id: ended.id, updated: false, code: 'schedule_ended' });
  assert.equal(f.rows.get(ended.id).status, 'inactive');
});

test('history preserves newest-first delivery identity pagination, retention and immutable sent prompts', async t => {
  const f = await fixture(t), input = { sessionId: 'work-a', id: record.id, limit: 2 };
  const first = await f.request('/history', input);
  assert.deepEqual(first.value.result.records.map(item => item.messageId), ['delivery-3', 'delivery-2']);
  assert.equal(first.value.result.nextBefore, 'delivery-2'); assert.equal(first.value.result.records[0].prompt, 'Previously sent instruction 3');
  assert.deepEqual(first.value.result.retention, { days: 30, records: 200 });
  assert.equal(first.value.result.earlierRecordsUnavailable, true); assert.equal(first.value.result.earlierRecordsPruned, true);
  const next = await f.request('/history', { ...input, before: first.value.result.nextBefore });
  assert.deepEqual(next.value.result.records.map(item => item.messageId), ['delivery-1']);
  assert.equal(next.value.result.nextBefore, undefined);
  const missing = await f.request('/history', { ...input, before: 'missing-delivery' });
  assert.deepEqual(missing.value.result, { id: record.id, code: 'delivery_cursor_not_found' });
  const wrong = await f.request('/history', { ...input, sessionId: 'work-b' });
  assert.deepEqual(wrong.value.result, { id: record.id, code: 'schedule_not_found' });
  assert.ok(f.calls.every(call => call.kind === 'history'));
});

test('deletion reports actual row removal and does not cancel the current Agent or claim to remove queued messages', async t => {
  const f = await fixture(t);
  const wrong = await f.request('/delete', { sessionId: 'work-b', id: record.id });
  assert.deepEqual(wrong.value.result, { id: record.id, deleted: false, code: 'schedule_not_found' });
  assert.ok(f.rows.has(record.id));
  const response = await f.request('/delete', { sessionId: 'work-a', id: record.id });
  assert.deepEqual(response.value, { ok: true, result: { id: record.id, deleted: true } });
  assert.equal(f.rows.has(record.id), false);
  const repeated = await f.request('/delete', { sessionId: 'work-a', id: record.id });
  assert.deepEqual(repeated.value.result, { id: record.id, deleted: false, code: 'schedule_not_found' });
  assert.equal(f.calls.some(call => call.kind === 'agent-read'), false);
});

test('a cancellation before execution returns no fabricated mutation receipt', async t => {
  const f = await fixture(t); f.controller.abort(new Error(secret));
  const response = await f.request('/create', createRequest());
  assert.equal(response.status, 409); assert.equal(response.value.error.code, 'NATIVE_SCHEDULES_CANCELLED');
  assert.equal(response.value.result, undefined); assert.equal(f.calls.length, 0); assert.equal(f.rows.size, 3);
});

test('cancellation while queued prevents the actual write after the FIFO barrier', async t => {
  const entered = Promise.withResolvers(), release = Promise.withResolvers(); t.after(() => release.resolve());
  let writes = 0;
  const f = await fixture(t, { beforeWrite: async () => { if (++writes === 1) { entered.resolve(); await release.promise; } } });
  const first = f.ctx.schedule.create('work-a', createRequest().request); await entered.promise;
  const invoked = once(f.events, 'invoke');
  const second = f.request('/create', createRequest()); await invoked;
  f.controller.abort(new Error(secret)); release.resolve(); await first;
  const response = await second; assert.equal(response.status, 409); assert.equal(response.value.error.code, 'NATIVE_SCHEDULES_CANCELLED');
  assert.equal(f.calls.filter(call => call.kind === 'put-committed').length, 1); assert.equal(f.rows.size, 4);
});

test('a write already in progress keeps its real receipt when cancellation arrives after persistence began', async t => {
  for (const method of ['create', 'update', 'delete']) await t.test(method, async child => {
    const entered = Promise.withResolvers(), release = Promise.withResolvers(); child.after(() => release.resolve());
    const f = await fixture(child, { beforeWrite: async () => { entered.resolve(); await release.promise; } });
    const input = method === 'create' ? createRequest() : method === 'update'
      ? { sessionId: 'work-a', id: record.id, expected: record, title: 'Committed late edit' } : { sessionId: 'work-a', id: record.id };
    const pending = f.request('/' + method, input); await entered.promise;
    f.controller.abort(new Error(secret)); release.resolve();
    const response = await pending; assert.equal(response.status, 200); assert.equal(response.value.ok, true);
    if (method === 'create') assert.ok(f.rows.has(response.value.result.id));
    if (method === 'update') { assert.equal(response.value.result.updated, true); assert.equal(f.rows.get(record.id).record.title, 'Committed late edit'); }
    if (method === 'delete') { assert.equal(response.value.result.deleted, true); assert.equal(f.rows.has(record.id), false); }
  });
});

test('safe paired error copy replaces exception text and structured rule diagnostics', async t => {
  const messages = [];
  for (const locale of ['zh', 'en']) {
    const failure = await fixture(t, { locale, failure: Object.assign(new Error(secret), { code: 'PRIVATE_' + secret }) });
    const response = await failure.request(); assert.equal(response.status, 500); assert.equal(response.value.error.code, 'NATIVE_SCHEDULES_FAILED');
    messages.push(response.value.error.message);
    const rule = await fixture(t, { locale, resultOverride: { code: 'invalid_rule', message: secret } });
    const invalid = await rule.request('/update', { sessionId: 'work-a', id: record.id, expected: record, title: 'Invalid fictional rule' });
    assert.equal(invalid.status, 200); assert.equal(invalid.value.result.code, 'invalid_rule');
    assert.match(invalid.value.result.message, locale === 'zh' ? /定时规则/ : /schedule rule/);
    delete failure.ctx.schedule;
    assert.equal((await failure.request()).value.error.code, 'NATIVE_SCHEDULES_NOT_READY');
  }
  assert.notEqual(messages[0], messages[1]);
});

test('routing, request framing and strict UTF-8 or body bounds fail before touching services', async t => {
  const f = await fixture(t);
  for (const [path, input, options, status] of [['/unknown', undefined, {}, 404], ['/create', undefined, {}, 405],
    ['', {}, {}, 405], ['?limit=3', undefined, {}, 400], ['/pause', {}, {}, 404],
    ['/delete', {}, { headers: { Cookie: f.headers.Cookie } }, 400],
    ['/delete', {}, { body: '{' }, 400], ['/delete', {}, { body: Buffer.from([0xff]) }, 400],
    ['/delete', {}, { body: ' '.repeat(256 * 1024 + 1) }, 413],
    ['/delete', {}, { body: Readable.from([Buffer.alloc(128 * 1024, 32), Buffer.alloc(128 * 1024 + 1, 32)]), duplex: 'half' }, 413]]) {
    const response = await f.request(path, input, options); assert.equal(response.status, status, path);
  }
  const raw = await new Promise((resolve, reject) => {
    const address = new URL(f.url), req = httpRequest({ hostname: address.hostname, port: address.port,
      path: 'http://foreign.invalid/droy/native/schedules', headers: f.headers }, res => { res.resume(); res.on('end', () => resolve(res.statusCode)); });
    req.once('error', reject); req.end();
  });
  assert.equal(raw, 400); assert.equal(f.calls.length, 0);
});
