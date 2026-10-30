import test from 'node:test';
import assert from 'node:assert/strict';
import { createRequire, registerHooks } from 'node:module';
import { pathToFileURL } from 'node:url';
import { Readable } from 'node:stream';
import { EventEmitter } from 'node:events';
import { createHostMaintenanceExecution } from '../companions/maintenance-host-execution.mjs';
import { createMaintenanceAdmission, installMaintenanceAdmission } from '../companions/maintenance-admission.mjs';
import { createCompanionMaintenanceWindow } from '../companions/maintenance-window.mjs';

const installed = createRequire(new URL('../../dsh/package.json', import.meta.url));
const hooks = registerHooks({ resolve(specifier, context, next) {
  if (specifier.startsWith('@deepseek-ai/')) return { url: pathToFileURL(installed.resolve(specifier)).href, shortCircuit: true };
  return next(specifier, context);
} });
const { createCompanionMaintenanceHandler } = await import('../companions/maintenance-host.mjs');
hooks.deregister();

function fixture() {
  const callbacks = new Map(), rows = [], jobs = [], terminals = [];
  const services = { jobs: { list: () => jobs }, terminalController: { list: () => terminals } };
  const ctx = { on(event, callback) { callbacks.set(event, callback); return () => callbacks.delete(event); },
    agents: { list: () => rows }, sessions: { list: () => [{ id: 'synthetic' }] }, get: name => services[name],
    droyNativeUserTasks: { inspect: () => ({ writers: [], busy: [], unfenced: [] }) } };
  return { callbacks, rows, jobs, terminals, services, inspector: createHostMaintenanceExecution(ctx) };
}
test('agent idle promises and queued input are authoritative even when no UI task is visible', async () => {
  const f = fixture(), idle = Promise.withResolvers();
  const agent = { whenIdle: () => idle.promise, inbox: { nextTurn: [], nextStep: [] } };
  f.rows.push(agent); f.callbacks.get('agent/created')({ agent });
  assert.equal(f.inspector.inspect().writers.length, 1);
  idle.resolve(); await new Promise(resolve => setImmediate(resolve));
  assert.equal(f.inspector.inspect().writers.length, 0);
  agent.inbox.nextStep.push({ id: 'input' });
  assert.deepEqual(f.inspector.inspect().busy, [{ kind: 'agent-input', count: 1 }]);
  f.inspector.dispose(); assert.equal(f.callbacks.size, 0);
});
test('jobs, terminal work and missing observation fence cannot be mistaken for idle', () => {
  const f = fixture(); f.jobs.push({ id: 'one', status: 'running' }); f.terminals.push({ state: 'running' });
  assert.deepEqual(f.inspector.inspect().busy, [{ kind: 'background-jobs', count: 1 }, { kind: 'web-terminal', count: 1 }]);
  delete f.services.jobs;
  assert.deepEqual(f.inspector.inspect().unfenced, [{ kind: 'jobs-unavailable', count: 1 }]);
});
test('rejected idle barrier remains unverified rather than becoming success', async () => {
  const f = fixture(), agent = { whenIdle: () => Promise.reject(new Error('synthetic')), inbox: { nextTurn: [], nextStep: [] } };
  f.rows.push(agent); f.callbacks.get('agent/status')({ agent }); await new Promise(resolve => setImmediate(resolve));
  assert.deepEqual(f.inspector.inspect().unfenced, [{ kind: 'agent-idle-failed', count: 1 }]);
});

function endpointFixture(t, { space, idle } = {}) {
  const token = 'synthetic-memory-maintenance-session-1234567890', original = process.env.DROY_WEB_TOKEN;
  process.env.DROY_WEB_TOKEN = token;
  t.after(() => { if (original === undefined) delete process.env.DROY_WEB_TOKEN; else process.env.DROY_WEB_TOKEN = original; });
  const events = [], operations = [], gate = createMaintenanceAdmission({ inspectExecution: () => ({ writers: [], busy: [], unfenced: [] }) });
  const ctx = { on: () => () => {}, get: () => ({}), effect: callback => callback() };
  const dispose = installMaintenanceAdmission(ctx, { gate,
    inspectWebIngress: () => ({ version: 1, requests: [], upgrades: [] }),
    inspectRemoteIngress: () => ({ version: 1, streams: [], muxRoutes: [] }) });
  t.after(dispose);
  const agents = idle ? [{ inbox: { nextTurn: [], nextStep: [] }, whenIdle: () => idle.promise }] : [];
  const windows = createCompanionMaintenanceWindow({ gate, agents: () => agents,
    producers: operationId => {
      operations.push(operationId);
      return [{ suspend: async () => { events.push('suspend'); }, resume: async () => { events.push('resume'); }, status: () => ({ quiet: true }) }];
    }, flushSessions: async () => { events.push('sessions'); }, flushPersistence: async () => { events.push('persistence'); } });
  const runtime = { memorySpaces: { async get(id) { return space?.memorySpaceId === id ? space : null; } },
    store: { async getProfile() { events.push('profile'); throw Object.assign(new Error('missing profile'), { code: 'COMPANION_NOT_FOUND' }); } } };
  const endpoint = createCompanionMaintenanceHandler({ runtime, windows,
    lifecycle: { async prepareMaintenanceExit(check) { assert.equal(check(), true); events.push('exit-prepared'); } }, exit: code => { events.push('exit:' + code); } });
  const request = async (method, input, authorized = true) => {
    const req = Readable.from(input === undefined ? [] : [JSON.stringify(input)]);
    req.method = method; req.headers = { host: '127.0.0.1:39001', ...(authorized ? { cookie: 'droy_session=' + token } : {}) };
    const res = new EventEmitter(); let status, body;
    res.writeHead = value => { status = value; }; res.end = value => { body = value; res.emit('finish'); };
    await endpoint.handler(req, res);
    return { status, body: body?.startsWith('{') ? JSON.parse(body) : body };
  };
  return { request, endpoint, gate, events, operations, runtime };
}
const purgeOperation = 'memory-management-' + 'a'.repeat(64);
const purgeSpace = () => ({ memorySpaceId: 'retained-memory-space', companionId: 'removed-companion', state: 'purging', purgePreviousState: 'retained', purgeOperation });

test('memory purge rejects unauthenticated requests and unjournaled or mismatched authorization', async t => {
  const space = purgeSpace(), f = endpointFixture(t, { space });
  const input = { operationId: purgeOperation, memorySpaceId: space.memorySpaceId, action: 'prepare-memory-purge' };
  assert.equal((await f.request('POST', input, false)).status, 401);
  assert.equal(f.endpoint.pending(), false);
  assert.equal((await f.request('POST', { ...input, operationId: 'another-operation' })).body.error, 'COMPANION_MEMORY_PURGE_NOT_AUTHORIZED');
  assert.equal((await f.request('POST', { ...input, memorySpaceId: 'missing-space' })).body.error, 'COMPANION_MEMORY_PURGE_NOT_AUTHORIZED');
  for (const state of ['active', 'retained', 'purged']) {
    space.state = state;
    assert.equal((await f.request('POST', input)).body.error, 'COMPANION_MEMORY_PURGE_NOT_AUTHORIZED');
  }
  assert.deepEqual(f.operations, []);
  assert.deepEqual(f.events, []);
});

test('retained-space purge uses the real admission window and waits for accepted work before shutdown', async t => {
  const space = purgeSpace(), idle = Promise.withResolvers(), f = endpointFixture(t, { space, idle });
  const input = { operationId: purgeOperation, memorySpaceId: space.memorySpaceId, action: 'prepare-memory-purge' };
  const prepared = await f.request('POST', input);
  assert.equal(prepared.status, 202); assert.equal(prepared.body.phase, 'waiting');
  assert.equal(prepared.body.companionId, 'removed-companion'); assert.equal(prepared.body.memorySpaceId, space.memorySpaceId);
  assert.equal(prepared.body.hostPid, process.pid); assert.ok(prepared.body.admissionEpoch);
  assert.deepEqual(f.operations, [purgeOperation]); assert.equal(f.events.includes('profile'), false);
  const shutdown = { ...input, companionId: space.companionId, action: 'shutdown' };
  assert.equal((await f.request('POST', shutdown)).body.error, 'COMPANION_MAINTENANCE_BUSY');
  assert.throws(() => f.gate.runExternal({ carrier: 'fixture', endpoint: 'new-work', role: 'operation' }, () => {}), { code: 'DROY_MAINTENANCE_CLOSED' });
  idle.resolve(); await new Promise(resolve => setImmediate(resolve));
  const ready = await f.request('GET'); assert.equal(ready.body.phase, 'ready');
  assert.equal(ready.body.admissionEpoch, prepared.body.admissionEpoch);
  assert.equal((await f.request('POST', { ...shutdown, memorySpaceId: 'another-space' })).body.error, 'COMPANION_MAINTENANCE_CONFLICT');
  assert.equal((await f.request('POST', shutdown)).body.phase, 'exiting');
  assert.deepEqual(f.events, ['suspend', 'sessions', 'persistence', 'exit-prepared', 'exit:0']);
});

test('purge authorization is rechecked before stop and cannot be replaced by a live profile identity', async t => {
  const space = purgeSpace(), f = endpointFixture(t, { space });
  const input = { operationId: purgeOperation, memorySpaceId: space.memorySpaceId, action: 'prepare-memory-purge' };
  assert.equal((await f.request('POST', { ...input, companionId: 'different-companion' })).body.error, 'COMPANION_MAINTENANCE_CONFLICT');
  await f.request('POST', input); await new Promise(resolve => setImmediate(resolve));
  space.purgeOperation = 'different-persisted-operation';
  assert.equal((await f.request('POST', { ...input, companionId: space.companionId, action: 'shutdown' })).body.error, 'COMPANION_MEMORY_PURGE_NOT_AUTHORIZED');
  assert.equal(f.events.includes('exit-prepared'), false);
  const released = await f.request('DELETE', { operationId: purgeOperation, companionId: space.companionId, memorySpaceId: space.memorySpaceId });
  assert.equal(released.body.phase, 'cancelled'); assert.equal(f.endpoint.pending(), false);
  assert.equal(f.gate.runExternal({ carrier: 'fixture', endpoint: 'new-work', role: 'operation' }, () => 'resumed'), 'resumed');
});

test('ordinary companion deletion keeps its original prepare and shutdown request shape', async t => {
  const f = endpointFixture(t);
  f.runtime.store.getProfile = async companionId => { f.events.push('profile:' + companionId); return { id: companionId }; };
  const input = { operationId: 'ordinary-deletion', companionId: 'active-companion', action: 'prepare' };
  const prepared = await f.request('POST', input);
  assert.equal(prepared.status, 202); assert.equal(prepared.body.memorySpaceId, undefined);
  await new Promise(resolve => setImmediate(resolve));
  assert.equal((await f.request('GET')).body.phase, 'ready');
  assert.equal((await f.request('POST', { ...input, action: 'shutdown' })).body.phase, 'exiting');
  assert.equal(f.events[0], 'profile:active-companion');
  assert.equal(f.events.at(-1), 'exit:0');
});
