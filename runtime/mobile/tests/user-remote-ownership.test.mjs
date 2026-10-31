import test from 'node:test';
import assert from 'node:assert/strict';
import { createRequire, registerHooks } from 'node:module';
import { pathToFileURL } from 'node:url';

const installed = createRequire(new URL('../../dsh/package.json', import.meta.url));
const hook = registerHooks({ resolve(specifier, context, next) {
  if (specifier.startsWith('@deepseek-ai/')) return { url: pathToFileURL(installed.resolve(specifier)).href, shortCircuit: true };
  return next(specifier, context);
} });
const { installUserRemoteOwnership } = await import('../user-remote-ownership.mjs');
const { TypertGatewayService, TypertGatewayError } = await import('@deepseek-ai/dsh-api-gateway');
hook.deregister();

function fixture() {
  const calls = [], effects = [], values = new Map(), streams = new Map();
  const gateway = {
    async invokeRpc(endpoint, payload, signal, peer) { calls.push({ endpoint, payload, signal, peer }); return { ok: true, value: structuredClone(values.get(endpoint)) }; },
    async openWireStream(endpoint, payload, uplink, peer, signal) { calls.push({ endpoint, payload, peer, signal }); return streams.get(endpoint)(); },
    async dispatchRpc(endpoint, payload) { calls.push({ endpoint, payload }); return { ok: true }; },
    async invoke() { return values.get('session/list'); },
  };
  const originals = { invokeRpc: gateway.invokeRpc, openWireStream: gateway.openWireStream, invoke: gateway.invoke };
  const ctx = { droyCompanions: { ownsSession: id => id?.startsWith('companion-'), store: { profiles: async () => [{ id: 'one', workspaceId: 'companion-space' }] } },
    typertGateway: gateway, settings: { describe: () => [{ ns: 'locale', value: { preference: 'en' } }] },
    get(name) { return this[name]; }, inject(_names, run) { return run(this); }, effect(run) { effects.push(run()); } };
  installUserRemoteOwnership(ctx);
  return { gateway, calls, values, streams, originals, ctx, close() { for (const effect of effects) effect(); } };
}
const payload = request => ({ args: request === undefined ? {} : { request } });
const peer = { principal: { kind: 'owner' } };

test('remote user list/catalog omit companion work while internal Gateway invokes retain their complete view', async () => {
  const f = fixture(), list = { items: [{ sessionId: 'user' }, { sessionId: 'user-child', origin: 'subagent' }, { sessionId: 'companion-work' }] };
  f.values.set('session/list', list);
  f.values.set('schedule/catalog', [{ id: 'user-time', sessionId: 'user' }, { id: 'companion-time', sessionId: 'companion-work' }]);
  assert.deepEqual(await f.gateway.invokeRpc('session/list', payload({}), undefined, peer), { ok: true, value: { items: list.items.slice(0, 2) } });
  assert.deepEqual(await f.gateway.invokeRpc('schedule/catalog', payload(), undefined, peer), { ok: true, value: [{ id: 'user-time', sessionId: 'user' }] });
  assert.equal(await f.gateway.invoke(), list);
  assert.equal(f.gateway.invoke, f.originals.invoke);
  assert.equal(list.items.length, 3);
  f.close(); assert.equal(f.gateway.invokeRpc, f.originals.invokeRpc); assert.equal(f.gateway.openWireStream, f.originals.openWireStream);
});

test('remote carrier rejects direct private targets before any user command or observation starts', async () => {
  const f = fixture();
  for (const [endpoint, request] of [
    ['session/create', { sessionId: 'companion-chat' }], ['session/create', { workspaceId: 'companion-space' }],
    ['session/page', { address: { kind: 'session', sessionId: 'companion-work' } }],
    ['session/rename', { sessionId: 'companion-work', title: 'Changed' }],
    ['workspace/delete', { workspaceId: 'companion-space' }], ['workspace/archiveSession', { sessionId: 'companion-work' }],
    ['schedule/history', { sessionId: 'companion-work', id: 'time' }],
    ['terminal/create', { agentId: 'companion-work', request: {} }],
  ]) {
    const receipt = await f.gateway.invokeRpc(endpoint, payload(request), undefined, peer);
    assert.equal(receipt.ok, false); assert.equal(receipt.error.code, 'droy/companion-managed');
  }
  await assert.rejects(f.gateway.openWireStream('session/follow', payload({ address: { kind: 'session', sessionId: 'companion-work' } }), undefined, peer),
    error => error.code === 'droy/companion-managed');
  const files = await f.gateway.invokeRpc('workspaceFiles/read', { args: { workspaceFileScopeId: 'companion-work', path: 'internal.txt', range: {} } }, undefined, peer);
  assert.equal(files.error.code, 'droy/companion-managed');
  const terminal = await f.gateway.invokeRpc('terminal/create', { args: { agentId: 'companion-work', request: { id: 'terminal' } } }, undefined, peer);
  assert.equal(terminal.error.code, 'droy/companion-managed');
  assert.deepEqual(f.calls, []);
  f.close();
});

test('the installed SDK carrier uses the adapted view while direct SDK invokes remain complete', async () => {
  const signal = new AbortController().signal, full = { items: [{ sessionId: 'user' }, { sessionId: 'companion-work' }] };
  const sdkPeer = { principal: { kind: 'owner' }, signal, disposed: false };
  const gateway = Object.create(TypertGatewayService.prototype);
  gateway.prepareInvocation = async request => ({ descriptor: { result: { mode: 'runtime' } }, endpoint: request.namespace + '/' + request.method,
    method: () => full, receiver: null, args: [], invocation: { signal, close: async () => {} } });
  gateway.openStream = async request => {
    assert.equal(request.namespace, 'session'); assert.equal(request.method, 'control');
    return (async function* () { yield { type: 'baseline', value: { projections: { user: { asOfSeq: 7, values: {} }, 'companion-work': { asOfSeq: 20, values: {} } } } }; })();
  };
  let cleanup;
  const ctx = { typertGateway: gateway, droyCompanions: { ownsSession: id => id === 'companion-work', store: { profiles: async () => [] } },
    get(name) { return this[name]; }, inject(_names, run) { return run(this); }, effect(run) { cleanup = run(); } };
  installUserRemoteOwnership(ctx);
  assert.deepEqual(await gateway.dispatchRpc('session/list', payload({}), signal, sdkPeer), { ok: true, value: { items: [{ sessionId: 'user' }] } });
  assert.equal(await gateway.invoke({ namespace: 'session', method: 'list', args: { request: {} }, peer: sdkPeer, signal }), full);
  const frames = await Array.fromAsync(await gateway.openWireStream('session/control', payload(), undefined, sdkPeer, signal, new AbortController()));
  assert.deepEqual(frames, [{ type: 'baseline', value: { projections: { user: { asOfSeq: 7, values: {} } } } }]);
  cleanup();
});

test('SDK structured stream failures keep their original code and details through the carrier adapter', async () => {
  const gateway = { invokeRpc() {}, async openWireStream() { throw new TypertGatewayError('gateway/method-unavailable', 'session/follow', 'Unavailable', { field: 'method' }); } };
  const ctx = { typertGateway: gateway, droyCompanions: { ownsSession: () => false },
    get(name) { return this[name]; }, inject(_names, run) { return run(this); }, effect() {} };
  installUserRemoteOwnership(ctx);
  await assert.rejects(gateway.openWireStream('session/follow', payload({ address: { kind: 'session', sessionId: 'user' } })), error => {
    assert.equal(error.code, 'gateway/method-unavailable'); assert.equal(error.details.field, 'method'); return true;
  });
});

test('remote control and workspace generations retain user order, child watermarks and cleanup', async () => {
  const f = fixture(); let closed = 0;
  f.streams.set('session/control', async function* () {
    try {
      yield { type: 'baseline', value: { projections: { user: { asOfSeq: 11, values: {} }, 'companion-chat': { asOfSeq: 55, values: {} } } } };
      yield { type: 'projection', sessionId: 'companion-chat', key: 'title', value: 'Internal', seq: 56 };
      yield { type: 'projection', sessionId: 'user-child', key: 'title', value: 'Child', seq: 8 };
    } finally { closed++; }
  });
  const spaces = { items: [{ workspaceId: 'user-space', sessionIds: ['user', 'user-child'] }, { workspaceId: 'companion-space', sessionIds: ['companion-work'] }],
    archivedSessionIds: ['user-child', 'companion-work'], pinnedSessionIds: ['companion-work', 'user'] };
  f.streams.set('workspace/follow', async function* () {
    try {
      yield { type: 'baseline', value: spaces };
      yield { type: 'upsert', workspace: spaces.items[1] };
      yield { type: 'order', workspaceIds: ['companion-space', 'user-space'] };
      yield { type: 'pinned', pinnedSessionIds: ['companion-work', 'user-child'] };
    } finally { closed++; }
  });
  const control = await Array.fromAsync(await f.gateway.openWireStream('session/control', payload(), undefined, peer));
  assert.deepEqual(control, [{ type: 'baseline', value: { projections: { user: { asOfSeq: 11, values: {} } } } },
    { type: 'projection', sessionId: 'user-child', key: 'title', value: 'Child', seq: 8 }]);
  const workspace = await Array.fromAsync(await f.gateway.openWireStream('workspace/follow', payload(), undefined, peer));
  assert.deepEqual(workspace, [
    { type: 'baseline', value: { items: spaces.items.slice(0, 1), archivedSessionIds: ['user-child'], pinnedSessionIds: ['user'] } },
    { type: 'order', workspaceIds: ['user-space'] }, { type: 'pinned', pinnedSessionIds: ['user-child'] },
  ]);
  assert.equal(closed, 2); assert.equal(spaces.items.length, 2); f.close();
});

test('remote companion events stay background and real confirmation requests delegate to Host policy', async () => {
  const f = fixture(), signal = new AbortController().signal, sdkPeer = { principal: { kind: 'owner' }, interactions: 'answer', signal };
  const cancellations = []; let settled;
  Object.setPrototypeOf(f.gateway, TypertGatewayService.prototype);
  const client = { peer: sdkPeer, deliveries: new Map(), queue: { push: frame => cancellations.push(frame) } };
  const pending = { id: 'private-approval', deliveries: new Set([client]), source: { resolve: outcome => { settled = outcome; } },
    releaseContext() {}, releaseSignal() {} };
  client.deliveries.set(pending.id, pending);
  f.gateway.remoteEventClients = new Map([['client', client]]);
  f.gateway.pendingRemoteEvents = new Map([[pending.id, pending]]);
  f.gateway.dispatchRpc = function(endpoint, value, ...rest) {
    f.calls.push({ endpoint, payload: value });
    return TypertGatewayService.prototype.dispatchRpc.call(this, endpoint, value, ...rest);
  };
  f.streams.set('$events', async function* () {
    yield { type: 'ready', clientId: 'client' };
    yield { type: 'emit', event: 'api-session/added', args: [{ sessionId: 'companion-work' }] };
    yield { type: 'emit', event: 'api-session/status', args: ['companion-chat', true] };
    yield { type: 'waterfall', event: 'approval/request', eventId: 'private-approval', agentId: 'companion-work', request: {} };
    assert.deepEqual(settled, { kind: 'next' });
    assert.deepEqual(cancellations, []);
    yield { type: 'emit', event: 'api-session/status', args: ['user', true] };
    yield { type: 'waterfall', event: 'user-questions/request', eventId: 'user-question', agentId: 'user', request: {} };
  });
  const frames = await Array.fromAsync(await f.gateway.openWireStream('$events', payload(), undefined, sdkPeer, signal));
  assert.deepEqual(frames, [{ type: 'ready', clientId: 'client' },
    { type: 'emit', event: 'api-session/status', args: ['user', true] },
    { type: 'waterfall', event: 'user-questions/request', eventId: 'user-question', agentId: 'user', request: {} }]);
  assert.deepEqual(f.calls.find(call => call.endpoint === '$events/result').payload,
    { args: { clientId: 'client', eventId: 'private-approval', outcome: { kind: 'next' } } });
  assert.equal(f.gateway.pendingRemoteEvents.size, 0); assert.equal(client.deliveries.size, 0);
  f.close();
});

test('an idle shutdown fence rejects new remote admission without invoking the engine', async () => {
  const f = fixture(); f.ctx.droyLifecycle = { stopping: true };
  const result = await f.gateway.invokeRpc('session/create', payload({ sessionId: 'new-user' }), undefined, peer);
  assert.equal(result.ok, false); assert.equal(result.error.code, 'DROY_STOPPING');
  assert.equal(f.calls.length, 0);
  await assert.rejects(f.gateway.openWireStream('session/control', payload(), undefined, peer), error => error.code === 'DROY_STOPPING');
  assert.equal(f.calls.length, 0); f.close();
});
