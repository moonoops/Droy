import test, { after } from 'node:test';
import assert from 'node:assert/strict';
import { once } from 'node:events';
import { mkdtemp, readFile, writeFile, symlink, rm } from 'node:fs/promises';
import { createRequire, registerHooks } from 'node:module';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { pathToFileURL } from 'node:url';
import { createMaintenanceAdmission, installMaintenanceAdmission, classifyMaintenanceHttp,
  classifyMaintenanceRemote } from '../companions/maintenance-admission.mjs';
import { requireMobileSession } from '../auth.mjs';
import { patchNativeMaintenanceWebServer, patchNativeMaintenanceGateway } from '../../../tools/native_maintenance_admission_patch.mjs';

const installed = createRequire(new URL('../../dsh/package.json', import.meta.url));
const directory = await mkdtemp(join(tmpdir(), 'droy-maintenance-admission-sdk-'));
await Promise.all([['dsh-host-webserver', 'web.mjs', patchNativeMaintenanceWebServer],
  ['dsh-api-gateway', 'gateway.mjs', patchNativeMaintenanceGateway]].map(async ([name, file, patch]) => {
  await writeFile(join(directory, file), patch(await readFile(installed.resolve('@deepseek-ai/' + name), 'utf8')));
}));
await symlink(new URL('../../dsh/node_modules', import.meta.url), join(directory, 'node_modules'), 'dir');
const hooks = registerHooks({ resolve(specifier, context, next) {
  if (specifier.startsWith('@deepseek-ai/')) return { url: pathToFileURL(installed.resolve(specifier)).href, shortCircuit: true };
  return next(specifier, context);
} });
const [{ Context }, Connection, { default: Registry }, { TypertRemoteService, RemoteError }, { TYPERT: TerminalApi },
  { TYPERT: SessionApi }, { TYPERT: WorkspaceApi }, llm, Jsonl, { default: Sessions }, { default: Projections },
  { default: SystemPrompt }, { default: Tools, defineTool }, { default: Agents }, { default: Loop }, Web, Gateway, { default: WebSocket }] = await Promise.all([
  import('@deepseek-ai/cordis'), import('@deepseek-ai/dsh-client-connection'), import('@deepseek-ai/dsh-typert-registry'),
  import('@deepseek-ai/dsh-typert-protocol'), import('@deepseek-ai/dsh-api-terminal-controller/typert'),
  import('@deepseek-ai/dsh-api-session-controller/typert'), import('@deepseek-ai/dsh-api-workspace-controller/typert'),
  import('@deepseek-ai/dsh-llm'), import('@deepseek-ai/dsh-session-persistence-jsonl'), import('@deepseek-ai/dsh-session'),
  import('@deepseek-ai/dsh-session-projection'), import('@deepseek-ai/dsh-system-prompt'), import('@deepseek-ai/dsh-tools'),
  import('@deepseek-ai/dsh-agent'), import('@deepseek-ai/dsh-agent-loop'), import(pathToFileURL(join(directory, 'web.mjs')).href),
  import(pathToFileURL(join(directory, 'gateway.mjs')).href), import(pathToFileURL(installed.resolve('ws')).href),
]);
hooks.deregister();
after(() => rm(directory, { recursive: true, force: true }));
const deferred = () => Promise.withResolvers();
const code = value => cause => cause.code === value;
const emptyExecution = () => ({ writers: [], busy: [], unfenced: [] });
const meta = (role = 'operation') => ({ carrier: 'fixture', endpoint: 'fixture/request', role });
const acquire = (gate, id = 'maintenance-a') => gate.acquire({ operationId: id, admissionEpoch: 'epoch-' + id });
const settled = () => new Promise(resolve => setImmediate(resolve));

class Queue {
  values = []; waiters = []; ended = false;
  push(value) { if (this.waiters.length) this.waiters.shift()({ value, done: false }); else this.values.push(value); }
  next() {
    if (this.values.length) return Promise.resolve({ value: this.values.shift(), done: false });
    if (this.ended) return Promise.resolve({ done: true });
    return new Promise(resolve => this.waiters.push(resolve));
  }
  return() { this.ended = true; for (const wake of this.waiters.splice(0)) wake({ done: true }); return Promise.resolve({ done: true }); }
  [Symbol.asyncIterator]() { return this; }
}

function localGate(t, options = {}) {
  const ctx = new Context();
  ctx.provide('webServer', {}); ctx.provide('typertGateway', {});
  ctx.provide('settings', { describe: () => [{ ns: 'locale', value: { preference: options.locale ?? 'en' } }] });
  const gate = createMaintenanceAdmission({ inspectExecution: emptyExecution, ...options });
  const dispose = installMaintenanceAdmission(ctx, { gate,
    inspectWebIngress: () => ({ version: 1, requests: [], upgrades: [] }),
    inspectRemoteIngress: () => ({ version: 1, streams: [], muxRoutes: [] }) });
  t.after(async () => { dispose(); await ctx.fiber.dispose(); });
  return { gate, ctx, dispose };
}

async function fixture(t, options = {}) {
  const ctx = new Context(), calls = [], lookups = [], connections = [], requests = [], queues = [];
  let savedCredential, locale = options.locale ?? 'en', stopEvents, dispose;
  const originalToken = process.env.DROY_WEB_TOKEN;
  t.after(async () => {
    for (const ws of connections) ws.terminate();
    for (const queue of queues) await queue.return();
    await stopEvents?.(); dispose?.();
    await ctx.fiber.dispose();
    if (originalToken === undefined) delete process.env.DROY_WEB_TOKEN; else process.env.DROY_WEB_TOKEN = originalToken;
  });
  ctx.logger.warn = value => { throw value; };
  ctx.provide('settings', { describe: () => [{ ns: 'locale', value: { preference: locale } }] });
  ctx.provide('credentials', { async modifyRecord(_key, update) {
    const value = await update(savedCredential); if (value !== undefined) savedCredential = value; return savedCredential;
  } });
  await ctx.plugin(Web.default, { host: '127.0.0.1', port: 0, compression: 'none' });
  await ctx.plugin(Connection, { maxRequestBodyBytes: 1024 * 1024 });
  await ctx.plugin(Registry); ctx.typert.register(TerminalApi); ctx.typert.register(SessionApi); ctx.typert.register(WorkspaceApi);
  ctx.provide('remoteAccess', { decide() { return { kind: 'deny', reason: 'fixture-only-owner' }; } });
  ctx.typert.lookups.register('agent', { parameter: 'agent', wire: 'agentId',
    hostTypeSymbol: '@deepseek-ai/dsh-agent#Agent', wireTypeSymbol: '@deepseek-ai/dsh-session/types#SessionId',
    resolve(id) { lookups.push(id); return { id }; } });
  class Terminal extends TypertRemoteService {
    constructor(scope) { super(scope, 'terminalController', { namespace: 'terminal' }); }
    attach(agent, id, signal) {
      calls.push({ type: 'attach', agent: agent.id, id }); const uplink = this.ctx.invocation.uplink();
      return (async function* () {
        yield { type: 'state', info: { id, title: 'Fixture', shell: { path: '/fixture/sh', name: 'Fixture', args: [] },
          cwd: '/fixture', cols: 80, rows: 24, state: 'running', exitCode: null }, writable: true };
        for await (const input of uplink) { calls.push({ type: 'input', input }); yield { type: 'ack', sequence: input.sequence }; }
      })();
    }
    async *retain(sessionId, id, signal) {
      calls.push({ type: 'retain', sessionId, id });
      const queue = new Queue(); queues.push(queue); signal.addEventListener('abort', () => queue.return(), { once: true });
      try { yield { type: 'retained', id }; yield* queue; } finally { calls.push({ type: 'retained-end' }); }
    }
  }
  class Session extends TypertRemoteService {
    constructor(scope) { super(scope, 'sessionController', { namespace: 'session' }); }
    cancel(request) {
      calls.push({ type: 'cancel', request });
      if (request.sessionId !== 'active') throw new RemoteError('session/not-found', 'Synthetic target is absent.', {});
      return { accepted: true };
    }
    async *control(signal) {
      const queue = new Queue(); queues.push(queue); signal.addEventListener('abort', () => queue.return(), { once: true });
      try { yield { type: 'baseline', sessions: [] }; yield* queue; } finally { calls.push({ type: 'control-end' }); }
    }
  }
  class Workspace extends TypertRemoteService {
    constructor(scope) { super(scope, 'workspaceController', { namespace: 'workspace' }); }
    async *follow(signal) {
      const queue = new Queue(); queues.push(queue); signal.addEventListener('abort', () => queue.return(), { once: true });
      yield { type: 'baseline', value: { items: [], archivedSessionIds: [], pinnedSessionIds: [] } }; yield* queue;
    }
  }
  new Terminal(ctx); new Session(ctx); new Workspace(ctx);
  if (options.deferMux) ctx.provide('appReady', { onReady() { return () => {}; } });
  await ctx.plugin(Gateway.default, { websocketHeartbeatIntervalMs: 60000, streamInboxBytes: 8192 });
  const events = new Queue(); stopEvents = ctx.typertGateway.registerRemoteEvents(signal => {
    signal.addEventListener('abort', () => events.return(), { once: true }); return events;
  }, { home: '/synthetic/admission-home' });
  ctx.webServer.register({ kind: 'exact', path: '/', handler(req, res) {
    if (ctx.connection.authorizeIndex(req, res)) res.end('Synthetic authenticated fixture');
  } });
  const mobileToken = 'synthetic-maintenance-admission-token-123456';
  process.env.DROY_WEB_TOKEN = mobileToken;
  ctx.webServer.register({ kind: 'prefix', path: '/droy', async handler(req, res) {
    if (!requireMobileSession(req, res)) return;
    requests.push(req.url);
    if (req.url === '/droy/native/session/cancel') {
      let text = ''; for await (const value of req) text += value;
      let value; try { value = JSON.parse(text); } catch { res.writeHead(400); res.end(); return; }
      if (!value || value.sessionId !== 'active' || Object.keys(value).length !== 1) { res.writeHead(409); res.end(); return; }
      calls.push({ type: 'native-cancel' }); res.end('{}'); return;
    }
    if (options.request) return options.request(req, res);
    res.end('{}');
  } });
  const origin = ctx.webServer.getOrigin();
  async function cookie(interactions) {
    let url = ctx.connection.authenticatedUrl(origin + (interactions === undefined ? '/' : '/connect'));
    if (interactions !== undefined) { const target = new URL(url); target.searchParams.set('interactions', interactions); url = target.href; }
    const response = await fetch(url, { redirect: 'manual' }); await response.text(); assert.equal(response.status, interactions === undefined ? 303 : 302);
    return response.headers.get('set-cookie').split(';')[0];
  }
  const browserCookie = await cookie();
  const gate = createMaintenanceAdmission({ inspectExecution: options.inspectExecution ?? emptyExecution });
  let installed = false;
  const install = () => {
    assert.equal(installed, false); installed = true;
    dispose = installMaintenanceAdmission(ctx, { gate, inspectWebIngress: Web.inspectMaintenanceWebIngress,
      inspectRemoteIngress: Gateway.inspectMaintenanceRemoteIngress });
  };
  if (!options.lateInstall) install();
  async function socket(auth = browserCookie, path = '/api/remote.mux') {
    const ws = new WebSocket(origin.replace('http:', 'ws:') + path, { headers: auth ? { Cookie: auth } : {} });
    const queue = new Queue(); ws.on('message', raw => queue.push(JSON.parse(String(raw))));
    ws.on('close', () => queue.return()); connections.push(ws); await once(ws, 'open');
    return { ws, queue, open(streamId, endpoint, args = {}) { ws.send(JSON.stringify({ type: 'open', streamId, endpoint, payload: { args } })); },
      async next() { const frame = await queue.next(); assert.equal(frame.done, false, 'The Remote socket must remain connected'); return frame.value; },
      send(value) { ws.send(JSON.stringify(value)); } };
  }
  async function rpc(endpoint, args, headers = { Cookie: browserCookie }) {
    const response = await fetch(origin + '/api/' + endpoint, { method: 'POST', headers: { 'Content-Type': 'application/json', ...headers },
      body: JSON.stringify({ type: 'client-request', rpcId: 'synthetic-rpc', method: endpoint, payload: { args } }) });
    const text = await response.text(); return { status: response.status, body: text.startsWith('{') ? JSON.parse(text) : text };
  }
  const native = (path, body = {}, headers = { Cookie: 'droy_session=' + mobileToken }) => fetch(origin + path, {
    method: 'POST', headers: { 'Content-Type': 'application/json', ...headers }, body: JSON.stringify(body),
  });
  return { ctx, calls, lookups, requests, gate, origin, cookie, browserCookie, mobileToken, install, events, socket, rpc, native,
    setLocale(value) { locale = value; } };
}

test('closeNew is synchronous, Promise leases preserve identity and actual settlement, and single owners cannot cross epochs', async t => {
  const { gate } = localGate(t), entered = deferred(); let executions = 0;
  assert.equal(gate.status().phase, 'open'); assert.equal(gate.status().newOperationsClosed, false);
  assert.equal(gate.status().quiet, false); assert.equal(gate.status().admissionsClosed, false);
  assert.equal(gate.runExternal(meta(), () => { executions++; return entered.promise; }), entered.promise);
  const lease = acquire(gate); assert.equal(acquire(gate), lease); lease.closeNew();
  assert.throws(() => gate.runExternal(meta(), () => executions++), code('DROY_MAINTENANCE_CLOSED'));
  assert.equal(executions, 1); assert.equal(lease.status().writers, 1);
  let finished = false; const wait = lease.waitForQuiet().then(value => { finished = true; return value; });
  await settled(); assert.equal(finished, false);
  entered.resolve('original receipt'); assert.equal(await entered.promise, 'original receipt'); assert.equal((await wait).quiet, true);
  assert.equal(lease.seal().admissionsClosed, true);
  assert.throws(() => gate.runExternal(meta('control'), () => null), code('DROY_MAINTENANCE_CLOSED'));
  assert.throws(() => acquire(gate, 'other'), code('DROY_MAINTENANCE_CONFLICT'));
  assert.equal(lease.resume().admissionsClosed, false);
  const next = acquire(gate, 'other'); next.closeNew(); lease.resume();
  assert.equal(next.status().newOperationsClosed, true); assert.equal(lease.status().phase, 'released');
  assert.throws(() => lease.seal(), code('DROY_MAINTENANCE_CONFLICT')); next.resume();
  assert.equal(gate.runExternal(meta(), () => ++executions), 2);
});

test('a cancelled quiet waiter does not reopen, retry or cancel its real writer', async t => {
  const { gate } = localGate(t), writer = deferred(), abort = new AbortController(), reason = new Error('Synthetic waiter cancellation');
  const result = gate.runExternal(meta(), () => writer.promise), lease = acquire(gate); lease.closeNew();
  const wait = lease.waitForQuiet({ signal: abort.signal }); abort.abort(reason); await assert.rejects(wait, cause => cause === reason);
  assert.equal(lease.status().newOperationsClosed, true); assert.equal(lease.status().writers, 1);
  assert.throws(() => gate.runExternal(meta(), () => assert.fail()), code('DROY_MAINTENANCE_CLOSED'));
  writer.resolve(23); assert.equal(await result, 23); assert.equal((await lease.waitForQuiet()).quiet, true);
  lease.resume();
});

test('explicit detached writes invalidate a sealed receipt and retain original success or rejection', async t => {
  const { gate } = localGate(t), lease = acquire(gate); lease.closeNew(); assert.equal(lease.seal().admissionsClosed, true);
  const pending = deferred(); assert.equal(gate.trackExplicit('fixture-export', pending.promise), pending.promise);
  assert.equal(lease.status().admissionsClosed, false); assert.equal(lease.status().writers, 1);
  assert.throws(() => lease.seal(), code('DROY_MAINTENANCE_BUSY'));
  const failure = new Error('Synthetic export error'); const observed = assert.rejects(pending.promise, cause => cause === failure);
  pending.reject(failure); await observed; await settled();
  assert.equal(lease.status().quiet, true); assert.equal(lease.status().admissionsClosed, false);
  assert.equal(lease.seal().admissionsClosed, true);
});

test('accepted continuations may delegate while closing, but long observers cannot turn into new writes', async t => {
  const { gate } = localGate(t), begin = deferred(), observer = deferred(); let delegated = 0;
  const pending = gate.runExternal(meta(), async () => { await begin.promise; return gate.runExternal(meta(), () => ++delegated); });
  const observing = gate.runExternal(meta('observer'), async () => {
    await observer.promise; assert.throws(() => gate.runExternal(meta(), () => assert.fail()), code('DROY_MAINTENANCE_CLOSED'));
  });
  const lease = acquire(gate); lease.closeNew(); begin.resolve(); assert.equal(await pending, 1);
  assert.equal(lease.seal().admissionsClosed, true); observer.resolve(); await observing;
  assert.equal(lease.status().admissionsClosed, true, 'A completed read observer does not reopen the sealed admission boundary');
  assert.equal(delegated, 1); lease.resume();
});

test('a delegated Promise that outlives its accepted parent retains its own lease', async t => {
  const { gate } = localGate(t), child = deferred(), entered = deferred(), proceed = deferred();
  const parent = gate.runExternal(meta(), async () => {
    entered.resolve(); await proceed.promise;
    gate.runExternal(meta(), () => child.promise);
  });
  await entered.promise; const lease = acquire(gate); lease.closeNew(); proceed.resolve(); await parent;
  assert.equal(lease.status().writers, 1); assert.throws(() => lease.seal(), code('DROY_MAINTENANCE_BUSY'));
  child.resolve(); assert.equal((await lease.waitForQuiet()).quiet, true); lease.resume();
});

test('missing execution proof, unsupported carriers and malformed observations cannot mint a quiet receipt', async t => {
  const ctx = new Context(); t.after(() => ctx.fiber.dispose());
  const gate = createMaintenanceAdmission(), lease = acquire(gate); lease.closeNew();
  assert.deepEqual(lease.status().unfenced.map(row => row.kind), ['transport-not-installed', 'execution-not-proven']);
  assert.equal((await lease.waitForQuiet()).quiet, false); assert.throws(() => lease.seal(), code('DROY_MAINTENANCE_UNFENCED'));
  const f = localGate(t, { inspectExecution: () => ({ writers: [], busy: [{ kind: 'active-pty', count: 1 }], unfenced: [] }) });
  const held = acquire(f.gate); held.closeNew(); assert.equal((await held.waitForQuiet()).busy[0].kind, 'active-pty');
  assert.throws(() => held.seal(), code('DROY_MAINTENANCE_BUSY'));
  const broken = createMaintenanceAdmission({ inspectExecution: () => { throw new Error('/private/secret'); } });
  assert.throws(() => acquire(broken).closeNew(), cause => cause.code === 'DROY_MAINTENANCE_OBSERVATION_FAILED' && !cause.message.includes('secret'));
});

test('all error text has live Chinese and English choices; invalid contracts are rejected rather than defaulted', async t => {
  const { gate } = localGate(t, { locale: 'zh' }), lease = acquire(gate); lease.closeNew();
  assert.throws(() => gate.runExternal(meta(), () => null), cause => cause.message === '正在维护，请稍后重试');
  for (const value of [{}, { operationId: 'a', admissionEpoch: '../b' }, { operationId: 'a', admissionEpoch: 'b', allowed: true }])
    assert.throws(() => gate.acquire(value), code('DROY_MAINTENANCE_INVALID'));
  assert.throws(() => gate.trackExplicit('fixture', { then() {} }), code('DROY_MAINTENANCE_INVALID'));
  assert.throws(() => lease.waitForQuiet({ signal: {} }), code('DROY_MAINTENANCE_INVALID'));
  const f = localGate(t), held = acquire(f.gate); held.closeNew();
  assert.throws(() => f.gate.runExternal(meta(), () => null), cause => cause.message === 'Maintenance is in progress. Try again later.');
  assert.equal(classifyMaintenanceHttp({ method: 'GET', path: '/droy/native/session/cold/follow' }), 'operation');
  assert.equal(classifyMaintenanceRemote({ endpoint: 'session/follow', input: 'none' }), 'operation');
});

test('real established mux refuses new open before Agent lookup/resume; original stream and subsequent opens are not replayed', async t => {
  const f = await fixture(t), stream = await f.socket();
  stream.open('before', 'session/control'); assert.equal((await stream.next()).type, 'item');
  const lease = acquire(f.gate); lease.closeNew();
  stream.open('rejected', 'terminal/attach', { agentId: 'cold-target', id: 't-1' });
  const denied = await stream.next(); assert.equal(denied.type, 'error'); assert.equal(denied.error.code, 'gateway/maintenance');
  assert.equal(denied.error.details.reason, 'DROY_MAINTENANCE_CLOSED'); assert.deepEqual(f.lookups, []); assert.deepEqual(f.calls, []);
  stream.open('observer', 'workspace/follow'); assert.equal((await stream.next()).value.type, 'baseline');
  assert.equal((await lease.waitForQuiet()).quiet, true); assert.equal(stream.ws.readyState, WebSocket.OPEN);
  assert.equal(lease.seal().admissionsClosed, true);
  stream.open('sealed-observer', 'session/control'); assert.equal((await stream.next()).error.code, 'gateway/maintenance');
  lease.resume(); await settled(); assert.deepEqual(f.lookups, []);
  stream.open('after', 'terminal/attach', { agentId: 'cold-target', id: 't-1' });
  assert.equal((await stream.next()).value.type, 'state'); assert.ok(f.lookups.length > 0);
  assert.equal(f.calls.filter(row => row.type === 'attach').length, 1);
  stream.send({ type: 'cancel', streamId: 'after' });
});

test('real HTTP controls keep original credential, Host/Origin and target validation during closeNew, and seal closes them', async t => {
  const f = await fixture(t), lease = acquire(f.gate); lease.closeNew();
  assert.equal((await f.rpc('session/cancel', { request: { sessionId: 'active' } }, {})).status, 401);
  assert.equal((await f.rpc('session/cancel', { request: { sessionId: 'active' } }, { Cookie: f.browserCookie, Origin: 'https://foreign.invalid' })).status, 403);
  const invalid = await f.rpc('session/cancel', { request: { sessionId: 'missing' } }); assert.equal(invalid.body.result.ok, false);
  assert.equal(invalid.body.result.error.code, 'session/not-found');
  const malformed = await f.rpc('session/cancel', { request: { sessionId: 'active' }, extra: true }); assert.equal(malformed.body.result.ok, false);
  assert.equal((await f.rpc('session/cancel', { request: { sessionId: 'active' } })).body.result.value.accepted, true);
  assert.equal((await f.native('/droy/native/session/cancel', { sessionId: 'active' }, {})).status, 401);
  assert.equal((await f.native('/droy/native/session/cancel', { sessionId: 'wrong' })).status, 409);
  assert.equal((await f.native('/droy/native/session/cancel', { sessionId: 'active' })).status, 200);
  const rejected = await f.native('/droy/native/session/prompt', { role: 'control' }); assert.equal(rejected.status, 423);
  const english = await rejected.json(); assert.equal(english.error.code, 'DROY_MAINTENANCE_CLOSED');
  assert.equal(english.error.message, 'Maintenance is in progress. Try again later.');
  f.setLocale('zh');
  assert.equal((await (await f.native('/droy/native/session/prompt')).json()).error.message, '正在维护，请稍后重试');
  f.setLocale('en');
  assert.equal(f.requests.includes('/droy/native/session/prompt'), false);
  await settled(); assert.equal(lease.seal().admissionsClosed, true);
  assert.equal((await f.rpc('session/cancel', { request: { sessionId: 'active' } })).status, 423);
});

test('real forwarded pending question remains answerable and enforces Peer ownership; sealed result is refused', async t => {
  const f = await fixture(t), ws = await f.socket(); ws.open('events', '$events'); const ready = (await ws.next()).value;
  const agent = { id: 'accepted-task' }, answer = deferred();
  f.events.push({ event: 'fixture/question', request: { agent, question: 'Synthetic question' },
    context: { subject: agent, agentId: agent.id, value: f.ctx }, resolve: answer.resolve, reject: answer.reject });
  const event = (await ws.next()).value; assert.equal(event.type, 'waterfall');
  const foreignCookie = await f.cookie('answer');
  const lease = acquire(f.gate); lease.closeNew();
  const args = { clientId: ready.clientId, eventId: event.eventId, outcome: { kind: 'result', value: 'Synthetic answer' } };
  const wrongPeer = await f.rpc('$events/result', args, { Cookie: foreignCookie });
  assert.equal(wrongPeer.body.result.error.code, 'gateway/interactions-forbidden');
  assert.equal((await f.rpc('$events/result', args)).body.result.ok, true);
  assert.deepEqual(await answer.promise, { kind: 'result', value: 'Synthetic answer' });
  const badClient = await f.rpc('$events/result', { ...args, clientId: 'absent-client' }); assert.equal(badClient.body.result.ok, false);
  await settled(); assert.equal(lease.seal().admissionsClosed, true);
  assert.equal((await f.rpc('$events/result', args)).status, 423);
});

test('real carrier inventory covers preinstall HTTP handlers past res.finish and blocks until their Promise settles', async t => {
  const handler = deferred(), entered = deferred();
  const f = await fixture(t, { lateInstall: true, request: async (_req, res) => { res.end('{}'); entered.resolve(); await handler.promise; } });
  const response = await f.native('/droy/accepted'); await response.text(); await entered.promise;
  f.install(); const lease = acquire(f.gate); lease.closeNew(); assert.equal(lease.status().writers, 1);
  let ended = false; const waiting = lease.waitForQuiet().then(receipt => { ended = true; return receipt; });
  await settled(); assert.equal(ended, false); handler.resolve();
  assert.equal((await waiting).quiet, true); assert.equal(lease.seal().admissionsClosed, true);
});

test('preinstall duplex stream stays connected, retains valid input/ACK and reports unfenced until its own lifecycle ends', async t => {
  const f = await fixture(t, { lateInstall: true }), ws = await f.socket();
  ws.open('terminal', 'terminal/attach', { agentId: 'active', id: 'existing' }); assert.equal((await ws.next()).value.type, 'state');
  f.install(); const lease = acquire(f.gate); lease.closeNew();
  const receipt = await lease.waitForQuiet(); assert.equal(receipt.quiet, false);
  assert.ok(receipt.unfenced.some(row => row.kind === 'remote-duplex-input'));
  assert.throws(() => lease.seal(), code('DROY_MAINTENANCE_UNFENCED'));
  ws.send({ type: 'item', streamId: 'terminal', value: { type: 'write', sequence: 1, data: 'Synthetic terminal input' } });
  const ack = await ws.next(); assert.equal(ack.value.type, 'ack'); assert.equal(ack.value.sequence, 1);
  assert.equal(ws.ws.readyState, WebSocket.OPEN); assert.equal(f.calls.filter(row => row.type === 'input').length, 1);
  const row = Gateway.inspectMaintenanceRemoteIngress(f.ctx.typertGateway).streams[0];
  ws.send({ type: 'end', streamId: 'terminal' }); await row.done;
  assert.equal((await lease.waitForQuiet()).quiet, true); assert.equal(lease.seal().admissionsClosed, true);
});

test('unknown non-mux upgrade is never treated as read-only; new negotiation is denied without closing old carrier', async t => {
  const f = await fixture(t, { lateInstall: true });
  const { WebSocketServer } = installed('ws'), rawServer = new WebSocketServer({ noServer: true });
  f.ctx.webServer.registerUpgrade({ path: '/fixture/duplex', handler(req, socket, head) { rawServer.handleUpgrade(req, socket, head, () => {}); } });
  const old = await f.socket(f.browserCookie, '/fixture/duplex');
  f.install(); const lease = acquire(f.gate); lease.closeNew();
  assert.ok((await lease.waitForQuiet()).unfenced.some(row => row.kind === 'web-upgrade-unfenced'));
  assert.equal(old.ws.readyState, WebSocket.OPEN); assert.throws(() => lease.seal(), code('DROY_MAINTENANCE_UNFENCED'));
  const denied = new WebSocket(f.origin.replace('http:', 'ws:') + '/fixture/duplex');
  denied.on('error', () => {}); const [request, response] = await once(denied, 'unexpected-response'); assert.equal(response.statusCode, 423); denied.terminate();
  const pending = Web.inspectMaintenanceWebIngress(f.ctx.webServer).upgrades.find(row => row.path === '/fixture/duplex');
  old.ws.terminate(); await pending.done; await settled();
  assert.equal((await lease.waitForQuiet()).quiet, true); await new Promise(resolve => rawServer.close(resolve));
});

test('a foreign upgrade at the mux pathname is unfenced unless the actual Gateway owns that exact route object', async t => {
  const f = await fixture(t, { lateInstall: true, deferMux: true });
  const { WebSocketServer } = installed('ws'), foreign = new WebSocketServer({ noServer: true });
  f.ctx.webServer.registerUpgrade({ path: '/api/remote.mux', handler(req, socket, head) { foreign.handleUpgrade(req, socket, head, () => {}); } });
  const ws = await f.socket(); f.install(); const lease = acquire(f.gate); lease.closeNew();
  assert.deepEqual(Gateway.inspectMaintenanceRemoteIngress(f.ctx.typertGateway).muxRoutes, []);
  assert.ok((await lease.waitForQuiet()).unfenced.some(row => row.kind === 'web-upgrade-unfenced'));
  assert.equal(ws.ws.readyState, WebSocket.OPEN); assert.throws(() => lease.seal(), code('DROY_MAINTENANCE_UNFENCED'));
  const row = Web.inspectMaintenanceWebIngress(f.ctx.webServer).upgrades[0]; ws.ws.terminate(); await row.done;
  assert.equal((await lease.waitForQuiet()).quiet, true); await new Promise(resolve => foreign.close(resolve));
});

test('an upgrade handler Promise remains unfenced after its physical socket closes until the actual handler settles', async t => {
  const f = await fixture(t, { lateInstall: true }), pending = deferred();
  const { WebSocketServer } = installed('ws'), foreign = new WebSocketServer({ noServer: true });
  f.ctx.webServer.registerUpgrade({ path: '/fixture/late', handler(req, socket, head) {
    foreign.handleUpgrade(req, socket, head, () => {}); return pending.promise;
  } });
  const ws = await f.socket(f.browserCookie, '/fixture/late'); f.install();
  const lease = acquire(f.gate); lease.closeNew();
  const row = Web.inspectMaintenanceWebIngress(f.ctx.webServer).upgrades[0]; let completed = false;
  row.done.then(() => { completed = true; }); const closed = once(ws.ws, 'close'); ws.ws.terminate(); await closed; await settled();
  assert.equal(completed, false); assert.equal((await lease.waitForQuiet()).quiet, false);
  assert.ok(lease.status().unfenced.some(value => value.kind === 'web-upgrade-unfenced'));
  pending.resolve(); await row.done; assert.equal((await lease.waitForQuiet()).quiet, true);
  await new Promise(resolve => foreign.close(resolve));
});

test('a reconnecting control mux still uses actual authentication; sealing also closes new authenticated negotiation', async t => {
  const f = await fixture(t), lease = acquire(f.gate); lease.closeNew();
  const rejected = new WebSocket(f.origin.replace('http:', 'ws:') + '/api/remote.mux'); rejected.on('error', () => {});
  const [, rejection] = await once(rejected, 'unexpected-response'); assert.equal(rejection.statusCode, 401); rejected.terminate();
  const ws = await f.socket(); ws.open('events', '$events'); assert.equal((await ws.next()).value.type, 'ready');
  const before = Web.inspectMaintenanceWebIngress(f.ctx.webServer).upgrades.filter(row => row.socket.destroyed).map(row => row.done);
  await Promise.all(before); await settled(); assert.equal(lease.seal().admissionsClosed, true);
  const sealed = new WebSocket(f.origin.replace('http:', 'ws:') + '/api/remote.mux', { headers: { Cookie: f.browserCookie } }); sealed.on('error', () => {});
  const [, response] = await once(sealed, 'unexpected-response'); assert.equal(response.statusCode, 423); sealed.terminate();
});

test('direct service Gateway invocation from an accepted continuation remains available after closeNew', async t => {
  const f = await fixture(t), start = deferred();
  const pending = f.gate.runExternal(meta(), async () => {
    await start.promise;
    return f.ctx.typertGateway.invoke({ namespace: 'session', method: 'cancel', args: { request: { sessionId: 'active' } },
      peer: f.ctx.connection.browserPeer });
  });
  const lease = acquire(f.gate); lease.closeNew(); start.resolve();
  assert.deepEqual(await pending, { accepted: true }); assert.equal((await lease.waitForQuiet()).quiet, true);
  assert.equal(f.calls.filter(row => row.type === 'cancel').length, 1); assert.equal(lease.seal().admissionsClosed, true);
});

test('owning Context disposal invalidates a sealed receipt even if its caller does not explicitly uninstall the gate', async t => {
  const f = localGate(t), lease = acquire(f.gate); lease.closeNew(); assert.equal(lease.seal().admissionsClosed, true);
  await f.ctx.fiber.dispose(); assert.equal(lease.status().admissionsClosed, false);
  assert.ok(lease.status().unfenced.some(row => row.kind === 'transport-not-installed'));
  assert.throws(() => lease.seal(), code('DROY_MAINTENANCE_UNFENCED'));
});

test('unknown stream input remains unfenced even when a trusted classifier labels its downlink observer', async t => {
  const ctx = new Context(); ctx.provide('webServer', {}); ctx.provide('typertGateway', {});
  const row = { endpoint: 'future/stream', input: 'unknown', done: deferred().promise };
  const gate = createMaintenanceAdmission({ inspectExecution: emptyExecution });
  const dispose = installMaintenanceAdmission(ctx, { gate,
    inspectWebIngress: () => ({ version: 1, requests: [], upgrades: [] }),
    inspectRemoteIngress: () => ({ version: 1, streams: [row], muxRoutes: [] }), classifyRemote: () => 'observer' });
  t.after(async () => { dispose(); await ctx.fiber.dispose(); });
  const lease = acquire(gate); lease.closeNew(); assert.equal((await lease.waitForQuiet()).quiet, false);
  assert.equal(lease.status().unfenced[0].kind, 'remote-input-unknown');
  assert.throws(() => lease.seal(), code('DROY_MAINTENANCE_UNFENCED'));
});

test('accepted real Agent task keeps later model steps and tools; its genuine idle Promise remains a user lease', async t => {
  const home = await mkdtemp(join(tmpdir(), 'droy-admission-agent-')), ctx = new Context();
  t.after(async () => { await ctx.fiber.dispose(); await rm(home, { recursive: true, force: true }); });
  await ctx.plugin(llm.default); await ctx.plugin(Sessions); await ctx.plugin(Projections);
  await ctx.plugin(SystemPrompt, { personaPrefix: 'Synthetic admission regression.' }); await ctx.plugin(Tools, { mode: 'native' });
  await ctx.plugin(Agents); await ctx.plugin(Loop, { agents: [] }); await ctx.plugin(Jsonl, { root: join(home, 'sessions'), compression: 'none' });
  const entered = deferred(), proceed = deferred(), errors = []; let steps = 0, tools = 0;
  ctx.on('agent/error', event => errors.push(event.error));
  class LocalModel extends llm.LlmAdapter {
    async resolveModel(provider, id) { return { provider, id, name: 'Synthetic local fixture', inputModalities: ['text'] }; }
    async *stream() {
      steps++;
      if (steps === 1) {
        entered.resolve(); await proceed.promise;
        yield { type: 'block-start', index: 0, blockType: 'tool-call' };
        yield { type: 'tool-call-delta', index: 0, id: 'fixture-call', name: 'admission_fixture', argumentsDelta: '{}' };
        yield { type: 'block-end', index: 0, block: { type: 'tool-call', id: 'fixture-call', name: 'admission_fixture', arguments: '{}' } };
        yield { type: 'finish', reason: { kind: 'tool-calls' } };
      } else {
        yield { type: 'block-start', index: 0, blockType: 'text' };
        yield { type: 'text-delta', index: 0, text: 'Synthetic completed response.' };
        yield { type: 'block-end', index: 0, block: { type: 'text', text: 'Synthetic completed response.' } };
        yield { type: 'finish', reason: { kind: 'stop' } };
      }
    }
  }
  ctx.llm.registerAdapter(['admission-local'], new LocalModel());
  ctx.tools.register(defineTool({ name: 'admission_fixture', description: 'Synthetic local operation', parameters: {},
    output: { schema: { type: 'json' }, render: (_args, value) => [{ type: 'text', text: JSON.stringify(value) }] },
    async execute() { tools++; return { completed: true }; } }));
  const { gate } = localGate(t), handle = await ctx.agents.create({ sessionId: 'synthetic-accepted', agentOptions: { provider: 'admission-local', model: 'fixture' } });
  const accepted = gate.runExternal(meta(), async () => {
    handle.agent.followup(llm.createUserMessage({ content: [{ type: 'text', text: 'Synthetic task' }], source: { kind: 'user' } }));
    await handle.agent.whenIdle(); await ctx.sessions.flush(handle.agent.session);
  });
  await entered.promise; const lease = acquire(gate); lease.closeNew(); assert.equal(lease.status().quiet, false);
  const waiting = lease.waitForQuiet(); proceed.resolve(); await accepted; assert.equal((await waiting).quiet, true);
  assert.equal(steps, 2); assert.equal(tools, 1); assert.deepEqual(errors, []);
  const records = handle.agent.session.snapshotEvents();
  assert.equal(records.filter(row => row.type === 'step/start').length, 2);
  assert.equal(records.findLast(row => row.type === 'turn/end').data.reason.kind, 'completed');
  assert.equal(lease.seal().admissionsClosed, true); await handle.dispose();
});

test('sealed maintenance owner route keeps authentication and does not reopen neighboring writers', async t => {
  const f = await fixture(t), lease = acquire(f.gate);
  lease.closeNew(); await lease.waitForQuiet(); assert.equal(lease.seal().admissionsClosed, true);
  const allowed = await f.native('/droy/native/maintenance/companion');
  assert.equal(allowed.status, 200); await allowed.text();
  const unauthorized = await f.native('/droy/native/maintenance/companion', {}, {});
  assert.equal(unauthorized.status, 401); await unauthorized.text();
  const neighbor = await f.native('/droy/native/maintenance/companion/other');
  assert.equal(neighbor.status, 423); await neighbor.text();
  assert.equal(lease.status().admissionsClosed, true);
});
