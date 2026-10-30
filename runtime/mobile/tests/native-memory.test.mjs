import test from 'node:test';
import assert from 'node:assert/strict';
import { createServer } from 'node:http';
import { mkdtemp, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { MemoryStore } from '../../memory/store.mjs';
import { createMemoryService } from '../../memory/adapter.mjs';
import { createNativeMemory } from '../native-memory.mjs';

const base = '/droy/native/memory';
async function fixture(t) {
  const directory = await mkdtemp(join(tmpdir(), 'droy-native-memory-'));
  const store = await MemoryStore.open({ directory: join(directory, 'store'), initialize: true });
  const settings = { enabled: true, recallEnabled: true, automaticExtraction: false };
  const gate = { ready: true, rootAuthorized: true, reason: 'ready' };
  const calls = [], forgotten = [], route = { id: 'fixture-route' };
  let managementReady = true, available = true;
  const host = {
    authorizeUi(req, res) {
      calls.push('authorize');
      if (req.headers['x-fixture-ui'] === 'authorized') return true;
      res.writeHead(401, { 'Content-Type': 'application/json' }); res.end(JSON.stringify({ ok: false })); return false;
    },
    executionStatus: async () => gate,
    managementStatus: async () => ({ ready: managementReady && gate.ready }),
    store: async () => { calls.push('store'); return store; },
    getSettings: async () => ({ ...settings }),
    saveSettings: async patch => { calls.push(['settings', patch]); if (patch.enabled && !gate.ready) throw Object.assign(new Error(), { code: 'MEMORY_PROTECTION_NOT_READY' }); Object.assign(settings, patch); return { ...settings }; },
    capture: async (operation, args) => { calls.push(['capture', operation, args]); return { state: 'paused' }; },
    processingEndpoints: async () => [],
    resolveProcessingEndpoint: async (id, purpose) => { calls.push(['resolve', id, purpose]); return route; },
  };
  const privacy = {
    pendingEndpointAccess: () => [{ id: 'live-request', provider: 'fixture', model: 'model', categories: ['memory'] }],
    resolveEndpointAccess: (id, approved) => { calls.push(['resolveAccess', id, approved]); return { resolved: true }; },
    status: async () => ({ ready: available, active: true }),
    routeForAgent: async () => route,
    beforeDisclosure: async () => assert.fail('Manager reads must not send private records to a model'),
    forgetSources: async refs => { forgotten.push(...refs); },
    grantEndpoint: async (actual, value) => { assert.equal(actual, route); calls.push(['grant', value]); return { granted: true }; },
    revokeEndpoint: async actual => { assert.equal(actual, route); calls.push('revoke'); return { revoked: true }; },
  };
  const memory = createMemoryService({ host, privacy });
  const ctx = { droyMemoryHost: host, droyMemory: memory, get(name) { return this[name]; }, logger: { warn: code => calls.push(['warn', code]) } };
  const native = createNativeMemory(ctx);
  const server = createServer((req, res) => { native.handle(req, res, new AbortController().signal).catch(() => { res.writeHead(500); res.end(); }); });
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  t.after(async () => { await new Promise(resolve => server.close(resolve)); await memory.close(); await store.close(); await rm(directory, { recursive: true, force: true }); });
  const origin = `http://127.0.0.1:${server.address().port}`;
  async function request(operation, args = {}, options = {}) {
    const response = await fetch(origin + (options.path ?? base), { method: options.method ?? (operation ? 'POST' : 'GET'),
      headers: { ...(options.authorized === false ? {} : { 'x-fixture-ui': 'authorized' }), ...(operation ? { 'content-type': 'application/json' } : {}), ...options.headers },
      ...(operation ? { body: JSON.stringify({ operation, args }) } : {}) });
    return { status: response.status, body: await response.json() };
  }
  return { request, store, calls, forgotten, gate, settings, ctx,
    set managementReady(value) { managementReady = value; }, set available(value) { available = value; } };
}

test('native memory authenticates before any service read or mutation', async t => {
  const f = await fixture(t);
  let status = 0, writes = 0;
  f.ctx.droyMemory = { status: async () => { status++; }, manage: async () => { writes++; } };
  assert.equal((await f.request(undefined, {}, { authorized: false })).status, 401);
  assert.equal((await f.request('settings', { enabled: true }, { authorized: false })).status, 401);
  assert.equal(status, 0); assert.equal(writes, 0); assert.equal(f.calls.filter(value => value === 'authorize').length, 2);
});

test('unknown route and query strings do not become memory operations', async t => {
  const f = await fixture(t);
  assert.equal((await f.request(undefined, {}, { path: `${base}/archive` })).status, 404);
  assert.equal((await f.request(undefined, {}, { path: `${base}?operation=record` })).status, 400);
  assert.equal((await f.request(undefined, {}, { method: 'DELETE' })).status, 405);
  assert.equal(f.calls.includes('store'), false);
});

test('missing memory service reports unavailable rather than an empty library', async t => {
  const f = await fixture(t); f.ctx.droyMemory = null;
  const result = await f.request();
  assert.equal(result.status, 503); assert.equal(result.body.error.code, 'MEMORY_HOST_CONTRACT_MISSING');
});

test('execution gates remain authoritative, with no store access on denial', async t => {
  const f = await fixture(t); f.gate.ready = false;
  const result = await f.request('query');
  assert.equal(result.body.ok, false); assert.equal(result.body.error.code, 'MEMORY_PROTECTION_NOT_READY');
  assert.equal(f.calls.includes('store'), false);
});

test('turning off, pausing and revoking remain available when protection is lost', async t => {
  const f = await fixture(t); f.gate.ready = false; f.available = false;
  assert.equal((await f.request('settings', { enabled: false })).body.result.enabled, false);
  assert.equal((await f.request('capture', { operation: 'pause', args: {} })).body.ok, true);
  assert.equal((await f.request('revoke', { id: 'fixture-endpoint', purpose: 'conversation' })).body.result.revoked, true);
  const grant = await f.request('grant', { id: 'fixture-endpoint', purpose: 'conversation', categories: ['memory'] });
  assert.equal(grant.body.ok, false); assert.equal(f.calls.some(call => Array.isArray(call) && call[0] === 'grant'), false);
});

test('record writes retain manager provenance and idempotent receipts', async t => {
  const f = await fixture(t);
  const args = { operationId: 'native:fixture-create', kind: 'preference', text: 'Fictional preference' };
  const first = (await f.request('record', args)).body.result;
  const retry = (await f.request('record', args)).body.result;
  assert.equal(first.recordId, retry.recordId); assert.equal(retry.duplicate, true);
  const record = (await f.request('get', { recordId: first.recordId })).body.result;
  assert.deepEqual(record.sourceRefs, ['manager:native:fixture-create']);
  assert.equal(record.assertion, 'user_statement'); assert.equal(record.actor, 'user');
  const rows = (await f.request('query', { kinds: ['preference'] })).body.result;
  assert.equal(rows.items.length, 1); assert.equal(rows.coverage.continuousObservationVerified, false);
});

test('concurrent revisions are rejected without overwriting the newer record', async t => {
  const f = await fixture(t);
  const first = (await f.request('record', { operationId: 'native:first', kind: 'fact', text: 'Original fictional text' })).body.result;
  const args = { recordId: first.recordId, expectedRevisionId: first.revisionId };
  const saved = await f.request('revise', { ...args, operationId: 'native:edit-one', patch: { payload: { text: 'New fictional text' } } });
  assert.equal(saved.body.ok, true);
  const conflict = await f.request('revise', { ...args, operationId: 'native:edit-two', patch: { payload: { text: 'Stale fictional edit' } } });
  assert.equal(conflict.status, 409); assert.equal(conflict.body.error.code, 'MEMORY_REVISION_CONFLICT');
  assert.equal((await f.request('get', { recordId: first.recordId })).body.result.payload.text, 'New fictional text');
});

test('forget retains source-aware descendant invalidation through the original service', async t => {
  const f = await fixture(t);
  const first = (await f.request('record', { operationId: 'native:source', kind: 'fact', text: 'Fictional source' })).body.result;
  const child = await f.store.record({ operationId: 'fixture:derived', record: { kind: 'fact', payload: { text: 'Fictional derived fact' },
    sourceRefs: ['fixture:derived-source'], derivedFrom: [{ recordId: first.recordId, revisionId: first.revisionId }], assertion: 'model_inference' } });
  const result = await f.request('forget', { operationId: 'native:forget', recordId: first.recordId, expectedRevisionId: first.revisionId });
  assert.equal(result.body.ok, true);
  assert.equal((await f.store.get({ recordId: first.recordId })).state, 'deleted');
  assert.equal((await f.store.get({ recordId: child.recordId })).state, 'deleted');
  assert.ok(f.forgotten.includes(`memory-record:${first.recordId}`));
  assert.ok(f.forgotten.includes('manager:native:source'));
});

test('management while disabled permits reads and forgetting, but never new recording', async t => {
  const f = await fixture(t);
  const first = (await f.request('record', { operationId: 'native:pre-disable', kind: 'fact', text: 'Fictional kept record' })).body.result;
  await f.request('settings', { enabled: false });
  assert.equal((await f.request('query')).body.result.items.length, 1);
  assert.equal((await f.request('record', { operationId: 'native:disabled', kind: 'fact', text: 'Must not save' })).body.error.code, 'MEMORY_DISABLED');
  assert.equal((await f.request('forget', { operationId: 'native:forget-disabled', recordId: first.recordId, expectedRevisionId: first.revisionId })).body.ok, true);
});

test('native manager does not accept store paths, fabricated authority, or archive requests', async t => {
  const f = await fixture(t);
  assert.equal((await f.request('query', { path: '/fictional/file', states: ['deleted'] })).body.error.code, 'MEMORY_INVALID_REQUEST');
  assert.equal((await f.request('grant', { id: 'fixture', purpose: 'conversation', categories: ['memory'], url: 'https://attacker.invalid' })).body.error.code, 'MEMORY_INVALID_REQUEST');
  assert.equal((await f.request('archive', { passphrase: 'fictional-only' })).body.error.code, 'MEMORY_UNKNOWN_OPERATION');
});

test('pending memory consent is authenticated and accepts no client-supplied endpoint or categories', async t => {
  const f = await fixture(t);
  assert.equal((await f.request('pendingAccess', {}, { authorized: false })).status, 401);
  assert.equal((await f.request('resolveAccess', { id: 'live-request', approved: true }, { authorized: false })).status, 401);
  assert.equal((await f.request('pendingAccess')).body.result[0].id, 'live-request');
  assert.equal((await f.request('resolveAccess', { id: 'live-request', approved: true, categories: ['location'] })).status, 400);
  assert.equal((await f.request('resolveAccess', { id: 'live-request', approved: 'true' })).status, 400);
  assert.equal(f.calls.some(call => Array.isArray(call) && call[0] === 'resolveAccess'), false);
  assert.equal((await f.request('resolveAccess', { id: 'live-request', approved: false })).body.result.resolved, true);
  assert.deepEqual(f.calls.filter(call => Array.isArray(call) && call[0] === 'resolveAccess'), [['resolveAccess', 'live-request', false]]);
});
