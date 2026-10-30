import test, { after } from 'node:test';
import assert from 'node:assert/strict';
import { createServer } from 'node:http';
import { EventEmitter, once } from 'node:events';
import { createRequire, registerHooks } from 'node:module';
import { pathToFileURL } from 'node:url';
import { Readable } from 'node:stream';

const installed = createRequire(new URL('../../dsh/package.json', import.meta.url));
const hooks = registerHooks({ resolve(specifier, context, next) {
  if (specifier.startsWith('@deepseek-ai/')) return { url: pathToFileURL(installed.resolve(specifier)).href, shortCircuit: true };
  return next(specifier, context);
} });
const { createNativeApi, installNativeApi, createNativeUserTasks } = await import('../native-api.mjs');
const { TYPERT } = await import('@deepseek-ai/dsh-api-session-controller/typert');
hooks.deregister();

const token = 'fictional-native-api-test-session-token-123456789';
const previousToken = process.env.DROY_WEB_TOKEN;
process.env.DROY_WEB_TOKEN = token;
after(() => {
  if (previousToken === undefined) delete process.env.DROY_WEB_TOKEN;
  else process.env.DROY_WEB_TOKEN = previousToken;
});

const snapshot = { type: 'snapshot', header: { version: 1, id: 'fixture-session', createdAt: 1, isSeeded: false },
  cursor: 0, records: [{ type: 'event', event: { type: 'user/message', seq: 0, time: 1, data: { id: 'fixture-message' } } }],
  hasMore: false, projections: { asOfSeq: 0, values: {} } };
const workspaceBaseline = { items: [{ workspaceId: 'fixture-workspace', title: 'Fixture', path: '/fixture/workspace', sessionIds: ['fixture-session'],
  createdAt: '2026-01-01T00:00:00.000Z', updatedAt: '2026-01-01T00:00:00.000Z' },
  { workspaceId: 'managed-companion-workspace', title: 'Fixture companion', path: '/fixture/companion', sessionIds: ['managed-companion-session'],
    createdAt: '2026-01-01T00:00:00.000Z', updatedAt: '2026-01-01T00:00:00.000Z' }],
  archivedSessionIds: [], pinnedSessionIds: [] };
const modelCatalog = { default: { provider: 'fixture-provider', model: 'fixture-model' }, routableProviders: ['fixture-provider'],
  groups: [{ id: 'fixture-provider', name: 'Fixture provider', models: [{ id: 'fixture-model', name: 'Fixture model' }] }], failures: [] };
const results = {
  list: { items: [{ sessionId: 'fixture-session', agentAvailable: true, updatedAt: 1, running: false, blank: false },
    { sessionId: 'managed-companion-session', agentAvailable: true, updatedAt: 2, running: false, blank: false }] },
  create: { sessionId: 'fixture-session' }, page: { records: snapshot.records, hasMore: false },
  prompt: { accepted: true }, cancel: { accepted: true }, projections: { kind: 'sequenced', asOfSeq: 0, values: {} },
};

async function fixture(t, options = {}) {
  const calls = [], events = new EventEmitter();
  const ctx = {
    get(name) { return this[name]; },
    settings: { describe: () => [{ ns: 'locale', value: { preference: options.locale ?? 'en' } }] },
    droyInitialization: { defaultWorkspaceId: 'fixture-workspace' },
    droyCompanions: {
      ownsSession: id => id === 'managed-companion-session',
      store: { async profiles() { return [{ id: 'kaka', workspaceId: 'managed-companion-workspace' }, { id: 'maimai', workspaceId: null }]; } },
    },
    sessionController: Object.fromEntries(['list', 'create', 'page', 'prompt', 'cancel', 'projections'].map(method => [method,
      (request, signal) => {
        calls.push({ method, request, signal });
        if (options.failure) throw options.failure;
        return structuredClone(results[method]);
      }])),
    workspaceController: {
      async *follow(signal) {
        calls.push({ method: 'workspaces', signal });
        try { yield { type: 'baseline', value: workspaceBaseline }; }
        finally { events.emit('workspace-closed'); }
      },
    },
  };
  ctx.sessionController.modelCatalog = async () => structuredClone(modelCatalog);
  ctx.sessionController.selectModel = async request => {
    calls.push({ method: 'selectModel', request });
    return { selected: { provider: request.provider, model: request.model } };
  };
  for (const method of ['follow', 'control']) ctx.sessionController[method] = async function*(input, callerSignal) {
    const signal = method === 'control' ? input : callerSignal;
    calls.push({ method, request: method === 'follow' ? input : undefined, signal });
    if (options.streamFailure) throw options.streamFailure;
    try {
      yield method === 'follow' ? snapshot : { type: 'baseline', value: { projections: {} } };
      if (options.lateFailure) throw options.lateFailure;
      await once(signal, 'abort');
    } finally { events.emit(`${method}-closed`); }
  };
  const api = createNativeApi(ctx, { decisions: options.decisions });
  const server = createServer(api.handle);
  server.listen(0, '127.0.0.1'); await once(server, 'listening');
  t.after(async () => {
    api.dispose(); server.closeAllConnections();
    await new Promise(resolve => server.close(resolve));
  });
  const url = `http://127.0.0.1:${server.address().port}/droy/native`;
  const headers = { Cookie: `droy_session=${token}`, 'Content-Type': 'application/json' };
  return { ctx, calls, events, api, url, headers,
    post: (method, value, extra = {}) => fetch(`${url}/session/${method}`, { method: 'POST', headers, body: JSON.stringify(value), ...extra }),
    get: (path, extra = {}) => fetch(`${url}/${path}`, { headers, ...extra }),
  };
}

test('native schedule dispatch keeps the shared session authentication boundary', async t => {
  const f = await fixture(t);
  f.ctx.schedule = { catalog: async () => [] };
  const accepted = await f.get('schedules');
  assert.equal(accepted.status, 200);
  assert.deepEqual(await accepted.json(), { ok: true, result: [] });
  const rejected = await f.get('schedules', { headers: {} });
  assert.equal(rejected.status, 401);
  const wrongMethod = await f.get('schedules', { method: 'POST', body: '{}' });
  assert.equal(wrongMethod.status, 405);
  assert.equal((await wrongMethod.json()).error.code, 'NATIVE_SCHEDULES_METHOD_NOT_ALLOWED');
});

test('external target resolver keeps authentication, strict method and safe typed errors', async t => {
  const f = await fixture(t);
  const path = `${f.url}/navigation/resolve`;
  assert.equal((await fetch(path, { method: 'POST', body: '{}' })).status, 401);
  assert.equal((await f.get('navigation/resolve')).status, 405);
  const invalid = await fetch(path, { method: 'POST', headers: f.headers, body: JSON.stringify({ kind: 'engine-session', sessionId: '../private' }) });
  assert.equal(invalid.status, 400);
  assert.equal((await invalid.json()).error.code, 'NATIVE_NAVIGATION_BAD_REQUEST');
  const notReady = await fetch(path, { method: 'POST', headers: f.headers, body: JSON.stringify({ kind: 'companion-chat', companionId: 'kaka' }) });
  assert.equal(notReady.status, 503);
  assert.equal((await notReady.json()).error.code, 'NATIVE_NAVIGATION_NOT_READY');
  assert.equal(f.calls.length, 0);
});

test('subagent transcript follow forwards the typed parent address and rejects ambiguous queries', async t => {
  const f = await fixture(t);
  assert.equal((await f.get('transcript/follow?parentSessionId=parent&childSessionId=child', { headers: {} })).status, 401);
  for (const query of ['parentSessionId=parent&childSessionId=parent', 'parentSessionId=parent',
    'parentSessionId=parent&childSessionId=child&childSessionId=other', 'parentSessionId=parent&childSessionId=child&mode=session'])
    assert.equal((await f.get(`transcript/follow?${query}`)).status, 400);
  assert.equal(f.calls.length, 0);
  const abort = new AbortController();
  const response = await f.get('transcript/follow?parentSessionId=parent&childSessionId=child&maxMessages=20', { signal: abort.signal });
  assert.equal(response.status, 200);
  const reader = response.body.getReader();
  assert.equal((await reader.read()).done, false);
  assert.deepEqual(f.calls.at(-1).request, { address: { kind: 'subagent', parentSessionId: 'parent', childSessionId: 'child', mode: 'unknown' }, assistantStream: true, maxMessages: 20 });
  const closed = once(f.events, 'follow-closed'); abort.abort(); await closed;
  assert.equal(f.calls.some(call => ['create', 'prompt', 'cancel'].includes(call.method)), false);
});

test('native commands preserve the DSH request and receipt, including stable prompt identity', async t => {
  const f = await fixture(t);
  const requests = {
    list: {}, create: { workspaceId: 'fixture-workspace', sessionId: 'fixture-session' },
    page: { address: { kind: 'session', sessionId: 'fixture-session' }, throughSeq: 4, beforeSeq: 3, maxMessages: 20 },
    prompt: { requestId: 'fixture-request-1', sessionId: 'fixture-session', mode: 'queue', content: [{ type: 'text', text: 'Hello fixture' }] },
    cancel: { sessionId: 'fixture-session' }, projections: { sessionId: 'fixture-session' },
  };
  for (const [method, request] of Object.entries(requests)) {
    const response = await f.post(method, request);
    assert.equal(response.status, 200, method);
    const body = await response.json();
    assert.equal(body.ok, true);
    assert.deepEqual(f.calls.at(-1).request, request);
    TYPERT.invocations.find(entry => entry.namespace === 'session' && entry.method === method).result.create().parse(results[method]);
    assert.deepEqual(body.result, method === 'list' ? { ...results.list, managedSessionIds: ['managed-companion-session'] } : results[method]);
  }
  assert.equal(f.calls.find(call => call.method === 'prompt').signal instanceof AbortSignal, true);
  assert.equal(f.calls.find(call => call.method === 'create').signal, undefined);
});

test('all native operations require the existing loopback cookie and origin checks before service access', async t => {
  const f = await fixture(t);
  for (const path of ['models', 'workspaces', 'session/control', 'session/fixture-session/follow']) {
    const response = await f.get(path, { headers: {} });
    assert.equal(response.status, 401, path);
    await response.text();
  }
  for (const headers of [{ Origin: 'https://untrusted.invalid' }, { Cookie: `droy_session=${token}; droy_session=${token}` }]) {
    const response = await f.post('prompt', {}, { headers: { ...f.headers, ...headers } });
    assert.equal(response.status, 401); await response.text();
  }
  assert.equal(f.calls.length, 0);
});

test('generated strict schemas reject credentials, wrong content, arrays and missing history cuts', async t => {
  const f = await fixture(t);
  for (const [method, body] of [
    ['prompt', { sessionId: 'fixture', requestId: 'stable', mode: 'queue', content: [{ type: 'text', text: 'Hi', injected: true }] }],
    ['prompt', { sessionId: 'fixture', requestId: 'stable', mode: 'queue', content: [{ type: 'text', text: 'Hi' }], apiKey: 'fictional-do-not-use' }],
    ['prompt', { sessionId: 'fixture', requestId: '', mode: 'queue', content: [] }],
    ['create', { workspaceId: 'fixture', cwd: 2 }], ['cancel', { sessionId: '' }], ['list', []],
    ['page', { address: { kind: 'session', sessionId: 'fixture' } }],
  ]) {
    const response = await f.post(method, body);
    assert.equal(response.status, 400, `${method} ${JSON.stringify(body)}`);
    assert.equal((await response.json()).error.code, 'NATIVE_BAD_REQUEST');
  }
  assert.equal(f.calls.length, 0);
});

test('malformed UTF-8 and JSON do not reach the service, and oversized bodies are refused', async t => {
  const f = await fixture(t);
  for (const body of ['{', Buffer.from([0xff, 0xfe]), 'null']) {
    const response = await f.post('list', {}, { body });
    assert.equal(response.status, 400); await response.text();
  }
  const response = await f.post('list', {}, { body: ' '.repeat(32 * 1024 * 1024 + 1) });
  assert.equal(response.status, 413); await response.text();
  const chunked = await f.post('list', {}, { body: Readable.from([Buffer.alloc(16 * 1024 * 1024, 32), Buffer.alloc(16 * 1024 * 1024 + 1, 32)]), duplex: 'half' });
  assert.equal(chunked.status, 413); await chunked.text();
  assert.equal(f.calls.length, 0);
});

test('HTTP methods, unknown routes, malformed identifiers and unsupported stream query are explicit failures', async t => {
  const f = await fixture(t);
  for (const [path, expected] of [['session/list', 405], ['unknown', 404], ['session/%E0%A4/follow', 400],
    ['session/id/follow?afterSeq=3', 400], ['session/id/follow?maxMessages=0', 400],
    ['session/id/follow?maxMessages=2&maxMessages=3', 400]]) {
    const response = await f.get(path); assert.equal(response.status, expected, path); await response.text();
  }
  const response = await f.post('list', {}, { headers: { Cookie: f.headers.Cookie } });
  assert.equal(response.status, 400); await response.text();
  assert.equal(f.calls.length, 0);
});

test('workspace baseline preserves all workspaces, exposes their managed identities and closes its observation', async t => {
  const f = await fixture(t);
  const closed = once(f.events, 'workspace-closed');
  const response = await f.get('workspaces');
  const { ok, result } = await response.json();
  assert.equal(ok, true);
  const { workspaces, sessions, directoryBrowsingAvailable, ...baseline } = result;
  assert.deepEqual(baseline, { ...workspaceBaseline,
    defaultWorkspaceId: 'fixture-workspace', managedWorkspaceIds: ['managed-companion-workspace'] });
  assert.equal(workspaces[1].readOnly, true);
  assert.equal(sessions[1].readOnly, true);
  assert.equal(directoryBrowsingAvailable, false);
  await closed;
  assert.deepEqual(await (await f.get('models')).json(), { ok: true, result: modelCatalog });
  assert.equal(f.calls.some(call => call.method === 'prompt'), false);
});

test('managed metadata follows the Store and actual returned Session identities without inferring ownership from names', async t => {
  const f = await fixture(t), checked = [];
  f.ctx.droyCompanions.ownsSession = id => { checked.push(id); return id === 'fixture-session' || id === 'not-in-this-list'; };
  const list = await (await f.post('list', {})).json();
  assert.deepEqual(list, { ok: true, result: { ...results.list, managedSessionIds: ['fixture-session'] } });
  assert.deepEqual(checked, results.list.items.map(item => item.sessionId));
  f.ctx.droyCompanions.store.profiles = async () => [
    { id: 'kaka', workspaceId: 'fixture-workspace' }, { id: 'maimai', workspaceId: 'fixture-workspace' },
  ];
  const workspaces = await (await f.get('workspaces')).json();
  assert.deepEqual(workspaces.result.managedWorkspaceIds, ['fixture-workspace']);
  for (const key of Object.keys(workspaceBaseline)) assert.deepEqual(workspaces.result[key], workspaceBaseline[key]);
  f.ctx.droyCompanions.store.profiles = async () => [{ id: 'kaka', workspaceId: null }, { id: 'maimai' }];
  assert.deepEqual((await (await f.get('workspaces')).json()).result.managedWorkspaceIds, []);
});

test('missing companion ownership contracts are unavailable rather than permitting unmanaged commands', async t => {
  for (const remove of [ctx => delete ctx.droyCompanions, ctx => delete ctx.droyCompanions.ownsSession,
    ctx => delete ctx.droyCompanions.store, ctx => delete ctx.droyCompanions.store.profiles]) {
    const f = await fixture(t); remove(f.ctx);
    for (const request of [() => f.get('workspaces'), () => f.post('list', {}), () => f.post('cancel', { sessionId: 'fixture-session' }),
      () => f.post('model', { sessionId: 'fixture-session', provider: 'fixture-provider', model: 'fixture-model' })]) {
      const response = await request();
      assert.equal(response.status, 503);
      const body = await response.json();
      assert.ok(['NATIVE_SERVICE_NOT_READY', 'NATIVE_WORKSPACES_NOT_READY'].includes(body.error.code));
      assert.equal('result' in body, false);
    }
    assert.equal(f.calls.length, 0);
  }
});

test('ownership read failures never produce successful unmanaged metadata', async t => {
  const f = await fixture(t);
  f.ctx.droyCompanions.ownsSession = () => { throw new Error('private ownership failure'); };
  const list = await f.post('list', {});
  assert.equal(list.status, 500);
  const listBody = await list.json();
  assert.equal(listBody.error.code, 'NATIVE_OPERATION_FAILED');
  assert.equal('result' in listBody, false);
  f.ctx.droyCompanions.store.profiles = async () => { throw new Error('private Store failure'); };
  const workspaces = await f.get('workspaces');
  assert.equal(workspaces.status, 500);
  const workspaceBody = await workspaces.json();
  assert.equal(workspaceBody.error.code, 'NATIVE_WORKSPACES_FAILED');
  assert.equal('result' in workspaceBody, false);
  assert.equal(JSON.stringify([listBody, workspaceBody]).includes('private'), false);
  for (const profiles of [null, {}, [null], [{ workspaceId: 2 }]]) {
    f.ctx.droyCompanions.store.profiles = async () => profiles;
    const invalid = await f.get('workspaces');
    assert.equal(invalid.status, 503);
    assert.equal((await invalid.json()).error.code, 'NATIVE_WORKSPACES_NOT_READY');
  }
});

test('native model route forces Session-only persistence and refuses user-supplied persistence flags', async t => {
  const f = await fixture(t);
  const request = { sessionId: 'fixture-session', provider: 'fixture-provider', model: 'fixture-model' };
  const response = await f.post('model', request);
  assert.equal(response.status, 200);
  assert.deepEqual(await response.json(), { ok: true, result: { selected: { provider: request.provider, model: request.model } } });
  assert.deepEqual(f.calls, [{ method: 'selectModel', request: { ...request, persistDefault: false } }]);
  for (const bad of [{ ...request, persistDefault: true }, { ...request, persistDefault: false }, { ...request, unknown: true }]) {
    const refused = await f.post('model', bad); assert.equal(refused.status, 400); await refused.text();
  }
  const owned = await f.post('model', { ...request, sessionId: 'managed-companion-session' });
  assert.equal(owned.status, 409); assert.equal((await owned.json()).error.code, 'COMPANION_SESSION_MANAGED');
  assert.equal(f.calls.length, 1);
});

test('decisions dispatch before Session service lookup and still require native session authentication', async t => {
  let decisionsCalls = 0;
  const decisions = { async handle(_req, res) {
    decisionsCalls++; res.writeHead(200, { 'Content-Type': 'application/json' }); res.end(JSON.stringify({ ok: true, result: { delegated: true } }));
  } };
  const f = await fixture(t, { decisions }); delete f.ctx.sessionController;
  const unauthorized = await f.get('decisions', { headers: {} });
  assert.equal(unauthorized.status, 401); await unauthorized.text(); assert.equal(decisionsCalls, 0);
  const response = await f.get('decisions');
  assert.deepEqual(await response.json(), { ok: true, result: { delegated: true } });
  assert.equal(decisionsCalls, 1);
  const notDecision = await f.get('decisions-extra');
  assert.notEqual(notDecision.status, 200); await notDecision.text(); assert.equal(decisionsCalls, 1);
});

test('follow returns raw snapshots with the requested window; disconnect releases observation without cancelling work', async t => {
  const f = await fixture(t), cancellation = new AbortController();
  const response = await f.get('session/fixture-session/follow?maxMessages=125', { signal: cancellation.signal });
  assert.equal(response.status, 200);
  assert.match(response.headers.get('content-type'), /text\/event-stream/);
  const reader = response.body.getReader();
  const text = new TextDecoder().decode((await reader.read()).value);
  assert.deepEqual(JSON.parse(text.slice(6).trim()), snapshot);
  assert.deepEqual(f.calls[0].request, { address: { kind: 'session', sessionId: 'fixture-session' }, assistantStream: true, maxMessages: 125 });
  const closed = once(f.events, 'follow-closed', { signal: AbortSignal.timeout(3000) });
  cancellation.abort(); await closed;
  assert.equal(f.calls.some(call => call.method === 'cancel'), false);
});

test('control is a distinct stream and plugin disposal aborts active observers', async t => {
  const f = await fixture(t), response = await f.get('session/control');
  const reader = response.body.getReader();
  assert.match(new TextDecoder().decode((await reader.read()).value), /"type":"baseline"/);
  const closed = once(f.events, 'control-closed', { signal: AbortSignal.timeout(3000) });
  f.api.dispose(); await closed;
  await reader.cancel();
  assert.equal(f.calls[0].method, 'control');
  assert.equal(f.calls[0].signal.aborted, true);
});

test('first-frame failure is an HTTP error; failures after opening are error events rather than successful frames', async t => {
  const missing = await fixture(t, { streamFailure: Object.assign(new Error('private fixture path'), { code: 'session/not-found' }) });
  const response = await missing.get('session/missing/follow');
  assert.equal(response.status, 404);
  const body = await response.json(); assert.equal(body.ok, false); assert.equal(body.error.code, 'session/not-found');
  assert.equal(JSON.stringify(body).includes('private fixture path'), false);
  const late = await fixture(t, { lateFailure: Object.assign(new Error('fictional-secret-key'), { code: 'stream/failed' }) });
  const stream = await late.get('session/fixture/follow');
  const content = await stream.text();
  assert.match(content, /event: error\ndata: \{"ok":false/);
  assert.equal(content.includes('fictional-secret-key'), false);
});

test('service availability and companion-owned cancellation preserve failure identity and bilingual copy', async t => {
  for (const locale of ['zh', 'en']) {
    const f = await fixture(t, { locale });
    const managed = await f.post('cancel', { sessionId: 'managed-companion-session' });
    assert.equal(managed.status, 409);
    const body = await managed.json(); assert.equal(body.error.code, 'COMPANION_SESSION_MANAGED');
    assert.match(body.error.message, locale === 'zh' ? /伙伴/ : /companion/);
    delete f.ctx.sessionController;
    const unavailable = await f.get('models');
    assert.equal(unavailable.status, 503);
    assert.equal((await unavailable.json()).error.code, 'NATIVE_SERVICE_NOT_READY');
    assert.equal(f.calls.length, 0);
  }
});

test('model catalog failures omit raw provider exceptions and diagnostic fields', async t => {
  for (const locale of ['zh', 'en']) {
    const f = await fixture(t, { locale });
    f.ctx.sessionController.modelCatalog = async () => ({ ...structuredClone(modelCatalog), failures: [
      { id: 'fixture-provider', name: 'Fixture', message: 'fictional-secret-header', details: { apiKey: 'fictional-secret-value' } },
    ] });
    const response = await f.get('models');
    assert.equal(response.status, 200);
    const value = await response.json();
    assert.deepEqual(value.result.failures.map(({ id, name }) => ({ id, name })), [{ id: 'fixture-provider', name: 'Fixture' }]);
    assert.match(value.result.failures[0].message, locale === 'zh' ? /模型/ : /model/);
    assert.equal(JSON.stringify(value).includes('fictional-secret'), false);
    assert.equal(Object.hasOwn(value.result.failures[0], 'details'), false);
  }
});

test('installation waits for the required host services and cleans up all observations', () => {
  let injection, dispose;
  const routes = [], provided = [], effects = [], observations = new Map();
  const ctx = {
    inject(names, callback) { injection = { names, callback }; return 'disposer'; },
    on(name, listener, options) { observations.set(name, { listener, options }); return () => observations.delete(name); },
    effect(callback) { effects.push(callback()); },
  };
  assert.equal(installNativeApi(ctx), 'disposer');
  assert.equal(observations.get('approval/request').options.prepend, true);
  assert.equal(observations.get('user-questions/request').options.prepend, true);
  assert.equal(routes.length, 0);
  assert.deepEqual(injection.names, ['sessionController', 'workspaceController', 'droyCompanions', 'sessionProjections']);
  let projection;
  injection.callback({ sessionProjections: { register(value) { projection = value; } }, webServer: { register(route) { routes.push(route); } }, on() { return () => {}; },
    provide(...args) { provided.push(args); }, effect(callback) { dispose = callback(); } });
  assert.equal(projection.key, 'droyTurnBoundary');
  assert.equal(typeof projection.wire.view, 'function');
  assert.deepEqual(routes.map(({ kind, path }) => [kind, path]), [['exact', '/droy/native'], ['prefix', '/droy/native']]);
  assert.deepEqual(provided.map(([name]) => name), ['droyNativeApi', 'droyNativeUserTasks']);
  assert.deepEqual(provided[0], ['droyNativeApi', true]);
  assert.equal(provided[1][1].scope, 'explicit-native-user-tasks');
  assert.deepEqual(provided[1][1].inspect().unfenced, [{ kind: 'pet-generation-owner-unavailable', count: 1 }]);
  dispose();
  for (const cleanup of effects) cleanup();
  assert.equal(observations.size, 0);
});

test('trusted user task inspection stays empty without new waiters and reports a missing actual owner', async () => {
  const owner = { pendingWork: () => Object.freeze([]), waitForIdle() { assert.fail('Inspection must not create an idle waiter'); }, dispose() { assert.fail(); } };
  const ctx = {}, tasks = createNativeUserTasks(ctx, { engine: owner, advanced: owner });
  assert.deepEqual(tasks.inspect().unfenced, [{ kind: 'pet-generation-owner-unavailable', count: 1 }]);
  await assert.rejects(tasks.whenIdle(), error => error.code === 'NATIVE_USER_TASKS_NOT_READY');
  ctx.droyPetGeneration = owner;
  const idle = tasks.whenIdle(); assert.ok(idle instanceof Promise); assert.equal(tasks.whenIdle(), idle);
  for (let i = 0; i < 10; i++) {
    const value = tasks.inspect(); assert.ok(Object.isFrozen(value)); assert.deepEqual(value, { writers: [], busy: [], unfenced: [] });
    assert.equal(tasks.whenIdle(), idle);
  }
  await idle;
});

test('user task aggregation waits for genuine completion and newly registered work without cancelling or replaying callers', async () => {
  const first = Promise.withResolvers(), second = Promise.withResolvers(), compact = new Set([first.promise]), install = new Set();
  first.promise.then(() => compact.delete(first.promise));
  second.promise.then(() => install.delete(second.promise), () => install.delete(second.promise));
  const engine = { pendingWork: () => Object.freeze([...compact]) }, advanced = { pendingWork: () => Object.freeze([...install]) };
  const ctx = { droyPetGeneration: { pendingWork: () => Object.freeze([]) } }, tasks = createNativeUserTasks(ctx, { engine, advanced });
  const original = tasks.inspect(); assert.equal(original.writers[0].done, first.promise); assert.equal(original.writers[0].kind, 'native-compact');
  assert.equal(tasks.inspect().writers[0].done, first.promise);
  let completed = false; const waiting = tasks.whenIdle(); waiting.then(() => { completed = true; });
  assert.equal(tasks.whenIdle(), waiting); install.add(second.promise); first.resolve('synthetic-success'); await first.promise;
  await new Promise(resolve => setImmediate(resolve)); assert.equal(completed, false);
  const current = tasks.inspect(); assert.equal(current.writers[0].done, second.promise); assert.equal(current.writers[0].kind, 'native-plugin-install');
  const failure = new Error('synthetic-private-install-error'), originalFailure = assert.rejects(second.promise, error => error === failure);
  second.reject(failure); await originalFailure; await waiting; assert.equal(completed, true);
  assert.deepEqual(tasks.inspect(), { writers: [], busy: [], unfenced: [] });
});

test('broken user task observations are explicit failures and cannot be replaced by state or controller counts', async () => {
  const owner = { pendingWork: () => [] }, ctx = { droyPetGeneration: owner };
  for (const bad of [{ state: 'idle' }, { pendingWork: () => [new AbortController()] }, { pendingWork() { throw new Error('fictional-private-observer'); } }]) {
    const tasks = createNativeUserTasks(ctx, { engine: bad, advanced: owner });
    if (!bad.pendingWork) {
      assert.equal(tasks.inspect().unfenced[0].kind, 'native-compact-owner-unavailable');
      await assert.rejects(tasks.whenIdle(), error => error.code === 'NATIVE_USER_TASKS_NOT_READY');
    } else {
      assert.throws(tasks.inspect, error => error.code === 'NATIVE_USER_TASKS_OBSERVATION_FAILED' && !error.message.includes('private'));
      await assert.rejects(tasks.whenIdle(), error => error.code === 'NATIVE_USER_TASKS_OBSERVATION_FAILED');
    }
  }
});

test('new explicit work cannot reuse an already completed drain during the Promise completion handoff', async () => {
  const first = Promise.withResolvers(), second = Promise.withResolvers(), entered = Promise.withResolvers(), pending = new Set([first.promise]);
  first.promise.then(() => pending.delete(first.promise)); second.promise.then(() => pending.delete(second.promise));
  const owner = { pendingWork: () => [...pending] }, empty = { pendingWork: () => [] };
  const tasks = createNativeUserTasks({ droyPetGeneration: empty }, { engine: owner, advanced: empty });
  const previous = tasks.whenIdle();
  first.promise.then(() => queueMicrotask(() => { pending.add(second.promise); entered.resolve({ waiter: tasks.whenIdle() }); }));
  first.resolve(); await previous;
  const { waiter: next } = await entered.promise; assert.notEqual(next, previous);
  let idle = false; next.then(() => { idle = true; });
  await new Promise(resolve => setImmediate(resolve)); assert.equal(idle, false);
  second.resolve(); await next; assert.deepEqual(tasks.inspect().writers, []);
});

test('native API publishes actual compact and install handles after their HTTP receipts return', async t => {
  const f = await fixture(t), command = Promise.withResolvers(), install = Promise.withResolvers();
  f.ctx.droyPetGeneration = { pendingWork: () => [] };
  f.ctx.commands = { find: () => ({ definitionId: '@deepseek-ai/dsh-command-compact' }), execute: () => command.promise };
  f.ctx.sessionController.resolveAgent = async id => ({ agent: { id, session: { id } } });
  f.ctx.sessions = { flush: async () => true };
  f.ctx.pluginManager = { registries: async () => ({ registry: 'https://registry.fixture.invalid/', fallbackRegistries: [], resolved: 'https://registry.fixture.invalid/' }),
    installBundle: () => install.promise };
  const send = async (path, value) => {
    const response = await fetch(f.url + path, { method: 'POST', headers: f.headers, body: JSON.stringify(value) });
    assert.equal(response.status, 200); return (await response.json()).result;
  };
  assert.equal((await send('/engine/compact', { sessionId: 'fixture-session', requestId: 'aggregate-compact' })).state, 'running');
  assert.equal((await send('/advanced/plugins/install', { spec: '@fixture/plugin', requestId: 'aggregate-install' })).state, 'running');
  const rows = f.api.userTasks.inspect().writers;
  assert.deepEqual(rows.map(row => row.kind), ['native-compact', 'native-plugin-install']);
  const waiting = f.api.userTasks.whenIdle();
  command.resolve({ commandId: 'synthetic-compact', result: { kind: 'success', text: 'Synthetic compacted history.' } });
  await rows[0].done; assert.equal(f.api.userTasks.inspect().writers[0].done, rows[1].done);
  install.resolve({ changed: true, application: 'applied', stage: 'install', target: '@fixture/plugin' });
  await waiting; assert.deepEqual(f.api.userTasks.inspect(), { writers: [], busy: [], unfenced: [] });
});
