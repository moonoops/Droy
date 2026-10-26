import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { createServer } from 'node:http';
import { createRequire } from 'node:module';
import { pathToFileURL } from 'node:url';
import { MemoryStore } from '../store.mjs';
import { PrivacyStore } from '../../privacy/store.mjs';
import { createMemoryService, createMemoryHandler, createMemoryArchiveHandler, memoryToolDefinitions, resolveMemoryLocale, confirmMemoryForget, installMemoryToolLocale, localizeMemoryToolSchemas, MemoryAccessError, MEMORY_ARCHIVE_MAX_BYTES } from '../adapter.mjs';

const installed = createRequire(new URL('../../dsh/package.json', import.meta.url));
const load = name => import(pathToFileURL(installed.resolve(name)).href);
const { estimateMessage } = await load('@deepseek-ai/dsh-token-meter/estimate');
const endpoint = Object.freeze({ url: 'https://model.example.invalid/v1/chat/completions', purpose: 'conversation', configVersion: 'a'.repeat(64) });
const exec = (session = 'session-one', callId = 'call-one') => ({ agent: { id: session, session: { id: session }, options: { provider: 'fixture', model: 'fixture' } }, callId, signal: new AbortController().signal });
async function fixture(t, options = {}) {
  const directory = await mkdtemp(join(tmpdir(), 'droy-memory-plugin-'));
  const store = await MemoryStore.open({ directory: join(directory, 'memory'), initialize: true });
  const privacyStore = await PrivacyStore.open({ directory: join(directory, 'privacy'), markerPath: join(directory, 'marker'), initialize: true });
  for (const id of ['session-one', 'session-two', 'session-three']) await privacyStore.registerCleanSession(id);
  if (options.granted !== false) await privacyStore.grantEndpoint({ ...endpoint, categories: ['memory', 'activity', 'location', 'derived'] });
  let settings = { enabled: true, recallEnabled: true, automaticExtraction: false }, gate = { ready: true, rootAuthorized: true };
  let storesOpened = 0, confirmation = true, disclosures = 0;
  const captures = [], operations = [], routes = new WeakSet([endpoint]);
  const privacy = {
    async status() { return { ready: gate.ready, active: true }; },
    async routeForAgent() { return endpoint; },
    async beforeDisclosure(input) {
      assert.ok(routes.has(input.route));
      await privacyStore.protectSession(input.sessionId, { categories: input.categories, sourceRefs: input.sourceRefs });
      await privacyStore.assertAllowed({ sessionId: input.sessionId, endpoint: input.route }); disclosures++;
    },
    forgetSources: refs => privacyStore.forgetSources(refs),
    grantEndpoint: (route, grant) => { assert.ok(routes.has(route)); return privacyStore.grantEndpoint({ ...route, ...grant }); },
    revokeEndpoint: route => { assert.ok(routes.has(route)); return privacyStore.revokeEndpoint(route); },
  };
  const host = {
    executionStatus: async () => gate,
    store: async () => { storesOpened++; return store; },
    getSettings: async () => settings,
    saveSettings: async patch => {
      if (patch.enabled && !gate.rootAuthorized) throw new MemoryAccessError('MEMORY_ROOT_REQUIRED', 403);
      if (patch.enabled && !gate.ready) throw new MemoryAccessError('MEMORY_PROTECTION_NOT_READY', 503);
      settings = { ...settings, ...patch }; return settings;
    },
    authorizeUi: (req, res) => {
      if (req.headers['x-fixture-ui'] === 'authorized') return true;
      res.writeHead(401); res.end(); return false;
    },
    capture: async (operation, args) => { captures.push({ operation, args }); return { state: 'off', configuration: { enabled: false } }; },
    processingEndpoints: async () => [{ id: 'reviewed-fixture', ...endpoint }],
    resolveProcessingEndpoint: async (id, purpose) => { assert.equal(id, 'reviewed-fixture'); assert.equal(purpose, 'conversation'); return endpoint; },
    assertWritable: async () => { if (options.readOnly) throw new MemoryAccessError('MEMORY_READ_ONLY'); },
  };
  const service = createMemoryService({ host, privacy,
    estimateTokens: options.estimateTokens ?? (text => estimateMessage({ role: 'user', content: [{ type: 'text', text }] })),
    confirmForget: async () => { operations.push('confirm'); return confirmation; } });
  t.after(async () => { await service.close(); await store.close(); await privacyStore.close(); await rm(directory, { recursive: true, force: true }); });
  return { service, host, privacy, privacyStore, store, captures, operations,
    get storesOpened() { return storesOpened; }, get disclosures() { return disclosures; },
    set gate(value) { gate = value; }, set settings(value) { settings = value; }, set confirmation(value) { confirmation = value; },
  };
}
const textRecord = (text = 'Fictional preference') => ({ kind: 'preference', text });

test('execution gate denies tools and management without obtaining the store', async t => {
  const f = await fixture(t); f.gate = { ready: false, rootAuthorized: true };
  const before = f.storesOpened;
  await assert.rejects(f.service.tool('query', {}, exec()), { code: 'MEMORY_PROTECTION_NOT_READY' });
  await assert.rejects(f.service.manage({ operation: 'query', args: {} }), { code: 'MEMORY_PROTECTION_NOT_READY' });
  const status = await f.service.status();
  assert.equal(status.available, false); assert.equal(status.managementAvailable, false); assert.equal(status.store, undefined);
  assert.equal(f.storesOpened, before);
});

test('master off prevents model memory while authenticated management and deletion remain possible', async t => {
  const f = await fixture(t);
  const receipt = await f.service.manage({ operation: 'record', args: { operationId: 'ui:create', ...textRecord() } });
  await f.service.manage({ operation: 'settings', args: { enabled: false } });
  await assert.rejects(f.service.tool('query', {}, exec()), { code: 'MEMORY_DISABLED' });
  await assert.rejects(f.service.manage({ operation: 'record', args: { operationId: 'ui:blocked', ...textRecord() } }), { code: 'MEMORY_DISABLED' });
  assert.equal((await f.service.manage({ operation: 'query', args: {} })).items.length, 1);
  const status = await f.service.status(); assert.equal(status.managementAvailable, true); assert.equal(status.available, false);
  await f.service.manage({ operation: 'forget', args: { operationId: 'ui:delete', recordId: receipt.recordId, expectedRevisionId: receipt.revisionId } });
  assert.equal((await f.service.manage({ operation: 'query', args: {} })).items.length, 0);
});

test('Root authorization is checked by the host when the master switch is enabled', async t => {
  const f = await fixture(t); f.settings = { enabled: false, recallEnabled: true, automaticExtraction: false };
  f.gate = { ready: false, rootAuthorized: false };
  await assert.rejects(f.service.manage({ operation: 'settings', args: { enabled: true } }), { code: 'MEMORY_ROOT_REQUIRED' });
  assert.equal((await f.service.status()).settings.enabled, false);
  await f.service.manage({ operation: 'capture', args: { operation: 'configure', args: { enabled: false, paused: true } } });
  assert.deepEqual(f.captures.at(-1), { operation: 'configure', args: { enabled: false, paused: true } });
});

test('unapproved model cannot create a stored record or receive query content', async t => {
  const f = await fixture(t, { granted: false });
  await assert.rejects(f.service.tool('record', textRecord(), exec()), { code: 'PRIVACY_ENDPOINT_NOT_AUTHORIZED' });
  assert.equal((await f.store.query()).items.length, 0);
  await f.service.manage({ operation: 'record', args: { operationId: 'ui:create', ...textRecord('Private synthetic content') } });
  await assert.rejects(f.service.tool('query', {}, exec()), { code: 'PRIVACY_ENDPOINT_NOT_AUTHORIZED' });
  assert.equal(f.disclosures, 0);
  assert.equal((await f.privacyStore.sessionState('session-one')).state, 'protected');
});

test('record tool binds source identity and deduplicates exact call retries', async t => {
  const f = await fixture(t);
  const receipt = await f.service.tool('record', textRecord(), exec());
  const retry = await f.service.tool('record', textRecord(), exec());
  assert.equal(retry.recordId, receipt.recordId); assert.equal(retry.duplicate, true);
  const record = await f.store.get({ recordId: receipt.recordId });
  assert.equal(record.actor, 'user'); assert.match(record.sourceRefs[0], /^session-call:[a-f0-9]{64}$/);
  await assert.rejects(f.service.tool('record', { ...textRecord(), spaceId: 'untrusted-space' }, exec()), { code: 'MEMORY_INVALID_REQUEST' });
});

test('a derived location fact retains canonical location consent after its direct parent is removed', async t => {
  const f = await fixture(t, { granted: false });
  await f.privacyStore.grantEndpoint({ ...endpoint, categories: ['memory', 'derived'] });
  const location = await f.store.record({ operationId: 'location:create', record: { kind: 'event', payload: {
    eventType: 'location', latitude: 30, longitude: 120, accuracyMeters: 15, provider: 'fixture', sampledAt: '2026-10-26T01:00:00.000Z',
  } } });
  const derived = await f.store.record({ operationId: 'derived:create', record: { kind: 'fact', payload: { text: 'Fictional location-derived fact' },
    derivedFrom: [{ recordId: location.recordId, revisionId: location.revisionId }], assertion: 'model_inference' } });
  const amended = await f.store.revise({ operationId: 'derived:detach', recordId: derived.recordId, expectedRevisionId: derived.revisionId,
    patch: { derivedFrom: [], assertion: 'user_statement' } });
  const record = await f.store.get({ recordId: amended.recordId });
  assert.ok(record.privacy.categories.includes('location'));
  await assert.rejects(f.service.tool('get', { record_id: record.recordId }, exec()), { code: 'PRIVACY_ENDPOINT_NOT_AUTHORIZED' });
  assert.equal(f.disclosures, 0);
});

test('real DSH tool registry executes the plugin definitions with host-bound agent identity', async t => {
  const f = await fixture(t);
  const [{ Context }, { default: Tools, defineTool }, { default: Prompt }] = await Promise.all([
    load('@deepseek-ai/cordis'), load('@deepseek-ai/dsh-tools'), load('@deepseek-ai/dsh-system-prompt'),
  ]);
  const ctx = new Context(); t.after(() => ctx.fiber.dispose());
  await ctx.plugin(Tools, { mode: 'native' }); await ctx.plugin(Prompt);
  for (const definition of memoryToolDefinitions(f.service)) ctx.tools.register(defineTool(definition));
  const result = await ctx.tools.execute({ name: 'droy_memory_record', arguments: textRecord('Real registry fixture'), ...exec() });
  assert.equal(result.isError, false, JSON.stringify(result));
  const query = await ctx.tools.execute({ name: 'droy_memory_query', arguments: {}, ...exec('session-two', 'query-call') });
  assert.equal(query.isError, false, JSON.stringify(query));
  assert.ok(JSON.stringify(query.content).includes('Real registry fixture'));
  assert.deepEqual(ctx.tools.schemas().map(tool => tool.name).sort(), memoryToolDefinitions(f.service).map(tool => tool.name).sort());
});

test('summary query returns bounded previews while get expands a selected long record', async t => {
  const f = await fixture(t), text = '测试 '.repeat(1500);
  const saved = await f.service.manage({ operation: 'record', args: { operationId: 'ui:long', ...textRecord(text) } });
  const list = await f.service.tool('query', {}, exec());
  assert.equal(list.items.length, 1); assert.equal(list.items[0].contentTruncated, true);
  assert.ok(Buffer.byteLength(list.items[0].payload.text) <= 512);
  const detail = await f.service.tool('get', { record_id: saved.recordId }, exec('session-one', 'get'));
  assert.equal(detail.record.payload.text, [...text].slice(0, 1200).join(''));
  assert.equal(detail.record.bodyRange.nextOffset, 1200);
  const next = await f.service.tool('get', { record_id: saved.recordId, offset: 1200 }, exec('session-one', 'get-next'));
  assert.equal(next.record.payload.text, [...text].slice(1200, 2400).join(''));
  const protection = await f.privacyStore.sessionState('session-one');
  assert.ok(protection.sourceRefs.includes(`memory-record:${saved.recordId}`));
  assert.ok(protection.sourceRefs.includes(`memory-revision:${saved.revisionId}`));
});

test('recall is bounded across one real agent turn and resets only on a new turn', async t => {
  const f = await fixture(t, { estimateTokens: () => 1490 });
  await f.service.manage({ operation: 'record', args: { operationId: 'ui:budget', ...textRecord() } });
  const execution = exec(); f.service.beginTurn(execution.agent, 1);
  for (let index = 0; index < 4; index++) await f.service.tool('query', {}, execution);
  f.service.beginTurn(execution.agent, 1);
  await assert.rejects(f.service.tool('query', {}, execution), { code: 'MEMORY_CONTEXT_BUDGET' });
  f.service.beginTurn(execution.agent, 2);
  assert.equal((await f.service.tool('query', {}, execution)).items.length, 1);
});

test('correction freezes old disclosed revision and fresh sessions can read the correction', async t => {
  const f = await fixture(t);
  const saved = await f.service.manage({ operation: 'record', args: { operationId: 'ui:old', ...textRecord('Earlier synthetic fact') } });
  await f.service.tool('query', {}, exec());
  const changed = await f.service.manage({ operation: 'revise', args: { operationId: 'ui:update', recordId: saved.recordId,
    expectedRevisionId: saved.revisionId, patch: { payload: { text: 'Corrected synthetic fact' } } } });
  assert.notEqual(changed.revisionId, saved.revisionId);
  assert.equal((await f.privacyStore.sessionState('session-one')).state, 'forgotten');
  await assert.rejects(f.service.tool('query', {}, exec()), { code: 'PRIVACY_SESSION_FORGOTTEN' });
  const result = await f.service.tool('query', {}, exec('session-two'));
  assert.equal(result.items[0].payload.text, 'Corrected synthetic fact');
});

test('invalid corrections do not freeze an unchanged memory or its aggregate epoch', async t => {
  const f = await fixture(t);
  const receipt = await f.service.manage({ operation: 'record', args: { operationId: 'valid:create', ...textRecord() } });
  await f.service.tool('get', { record_id: receipt.recordId }, exec());
  await assert.rejects(f.service.manage({ operation: 'revise', args: { operationId: 'invalid:revision', recordId: receipt.recordId,
    expectedRevisionId: receipt.revisionId, patch: { payload: { text: '' } } } }), { code: 'MEMORY_INVALID_INPUT' });
  assert.equal((await f.store.status()).disclosureEpoch, 0);
  const reread = await f.service.tool('get', { record_id: receipt.recordId }, exec('session-one', 'read-again'));
  assert.equal(reread.record.revisionId, receipt.revisionId);
});

test('an explicit local correction confirms a candidate while retaining its location restriction', async t => {
  const f = await fixture(t);
  const receipt = await f.store.record({ operationId: 'visit:candidate', record: { kind: 'event', state: 'candidate', assertion: 'model_inference',
    payload: { eventType: 'visit', latitude: 30, longitude: 120, accuracyMeters: 15,
      startedAt: '2026-10-26T01:00:00.000Z', endedAt: '2026-10-26T01:30:00.000Z' } } });
  const before = await f.store.get({ recordId: receipt.recordId });
  await f.service.manage({ operation: 'revise', args: { operationId: 'visit:confirm', recordId: receipt.recordId, expectedRevisionId: receipt.revisionId,
    patch: { payload: { ...before.payload, placeName: '虚构地点' } } } });
  const after = await f.store.get({ recordId: receipt.recordId });
  assert.equal(after.state, 'active'); assert.equal(after.actor, 'user'); assert.equal(after.assertion, 'user_statement');
  assert.ok(after.privacy.categories.includes('location')); assert.ok(after.privacy.categories.includes('derived'));
});

test('management retries return original update/delete receipts after network ambiguity', async t => {
  const f = await fixture(t), saved = await f.service.manage({ operation: 'record', args: { operationId: 'ui:retry-create', ...textRecord() } });
  const args = { operationId: 'ui:retry-update', recordId: saved.recordId, expectedRevisionId: saved.revisionId, patch: { payload: { text: 'Updated once' } } };
  const first = await f.service.manage({ operation: 'revise', args }), second = await f.service.manage({ operation: 'revise', args });
  assert.equal(second.revisionId, first.revisionId); assert.equal(second.duplicate, true);
  const deletion = { operationId: 'ui:retry-forget', recordId: saved.recordId, expectedRevisionId: first.revisionId };
  const removed = await f.service.manage({ operation: 'forget', args: deletion });
  const removedAgain = await f.service.manage({ operation: 'forget', args: deletion });
  assert.equal(removedAgain.revisionId, removed.revisionId); assert.equal(removedAgain.duplicate, true);
});

test('forget requires actual human confirmation and does not interpret model parameters as consent', async t => {
  const f = await fixture(t), saved = await f.service.tool('record', textRecord(), exec());
  f.confirmation = false;
  assert.deepEqual(await f.service.tool('forget', { record_id: saved.recordId, revision_id: saved.revisionId }, exec('session-one', 'forget-one')), { forgotten: false, cancelled: true });
  assert.equal((await f.store.query()).items.length, 1);
  await assert.rejects(f.service.tool('forget', { record_id: saved.recordId, revision_id: saved.revisionId, approved: true }, exec()), { code: 'MEMORY_INVALID_REQUEST' });
  f.confirmation = true;
  await f.service.tool('forget', { record_id: saved.recordId, revision_id: saved.revisionId }, exec('session-one', 'forget-two'));
  assert.equal((await f.store.query()).items.length, 0);
  assert.equal((await f.privacyStore.sessionState('session-one')).state, 'forgotten');
});

test('forget freezes the original recording session before later extraction can send its input again', async t => {
  const f = await fixture(t);
  const receipt = await f.service.tool('record', textRecord('Original synthetic private input'), exec());
  const original = await f.store.get({ recordId: receipt.recordId });
  await f.service.manage({ operation: 'forget', args: { operationId: 'ui:forget-original', recordId: receipt.recordId, expectedRevisionId: receipt.revisionId } });
  await assert.rejects(f.privacyStore.assertAllowed({ sessionId: 'session-one', endpoint }), { code: 'PRIVACY_SESSION_FORGOTTEN' });
  await assert.rejects(f.privacy.beforeDisclosure({ sessionId: 'session-two', route: endpoint, categories: ['memory'], sourceRefs: original.sourceRefs }), { code: 'PRIVACY_SOURCE_FORGOTTEN' });
});

test('forget freezes historical own sources after a source replacement', async t => {
  const f = await fixture(t);
  const receipt = await f.service.tool('record', textRecord(), exec());
  const original = await f.store.get({ recordId: receipt.recordId });
  const changed = await f.store.revise({ operationId: 'host:replace-source', recordId: receipt.recordId, expectedRevisionId: receipt.revisionId,
    patch: { sourceRefs: ['new-own-source'] } });
  await f.privacy.beforeDisclosure({ sessionId: 'session-two', route: endpoint, categories: ['memory'], sourceRefs: ['new-own-source'] });
  await f.service.manage({ operation: 'forget', args: { operationId: 'ui:forget-history', recordId: receipt.recordId, expectedRevisionId: changed.revisionId } });
  await assert.rejects(f.privacyStore.assertAllowed({ sessionId: 'session-one', endpoint }), { code: 'PRIVACY_SESSION_FORGOTTEN' });
  await assert.rejects(f.privacyStore.assertAllowed({ sessionId: 'session-two', endpoint }), { code: 'PRIVACY_SESSION_FORGOTTEN' });
  await assert.rejects(f.privacy.beforeDisclosure({ sessionId: 'session-three', route: endpoint, categories: ['memory'], sourceRefs: original.sourceRefs }), { code: 'PRIVACY_SOURCE_FORGOTTEN' });
});

test('full interval aggregate is protected by a durable epoch and correction invalidates it', async t => {
  const f = await fixture(t);
  const saved = await f.service.manage({ operation: 'record', args: { operationId: 'ui:aggregate', ...textRecord() } });
  const range = { summary: 'days', from: '2000-01-01T00:00:00Z', to: '2099-01-01T00:00:00Z' };
  const result = await f.service.tool('query', range, exec()); assert.equal(result.complete, true);
  assert.ok((await f.privacyStore.sessionState('session-one')).sourceRefs.some(ref => ref.startsWith('memory-epoch:')));
  await f.service.manage({ operation: 'forget', args: { operationId: 'ui:aggregate-forget', recordId: saved.recordId, expectedRevisionId: saved.revisionId } });
  assert.equal((await f.privacyStore.sessionState('session-one')).state, 'forgotten');
});

test('endpoint management resolves an existing host capability and rejects user-supplied URLs', async t => {
  const f = await fixture(t, { granted: false });
  await assert.rejects(f.service.manage({ operation: 'grant', args: { id: 'reviewed-fixture', purpose: 'conversation', url: 'https://evil.invalid', categories: ['memory'] } }), { code: 'MEMORY_INVALID_REQUEST' });
  await f.service.manage({ operation: 'grant', args: { id: 'reviewed-fixture', purpose: 'conversation', categories: ['memory'] } });
  await f.service.tool('record', textRecord(), exec());
  await f.service.manage({ operation: 'revoke', args: { id: 'reviewed-fixture', purpose: 'conversation' } });
  await assert.rejects(f.service.tool('query', {}, exec()), { code: 'PRIVACY_ENDPOINT_NOT_AUTHORIZED' });
});

test('turn off during pending disclosure prevents content from returning to the model', async t => {
  const f = await fixture(t);
  await f.service.manage({ operation: 'record', args: { operationId: 'ui:race', ...textRecord() } });
  const original = f.privacy.beforeDisclosure;
  f.privacy.beforeDisclosure = async input => {
    await original(input);
    await f.service.manage({ operation: 'settings', args: { enabled: false } });
  };
  await assert.rejects(f.service.tool('query', {}, exec()), { code: 'MEMORY_DISABLED' });
});

test('authenticated HTTP API bounds requests and rejects hidden data/authority fields', async t => {
  const f = await fixture(t), server = createServer(createMemoryHandler({ host: f.host, service: f.service }));
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  t.after(async () => { server.closeAllConnections(); await new Promise(resolve => server.close(resolve)); });
  const url = `http://127.0.0.1:${server.address().port}`;
  assert.equal((await fetch(url)).status, 401);
  const headers = { 'x-fixture-ui': 'authorized', 'content-type': 'application/json' };
  assert.equal((await fetch(url, { headers })).status, 200);
  const forged = await fetch(url, { method: 'POST', headers, body: JSON.stringify({ operation: 'record', args: { operationId: 'ui:forged', ...textRecord(), spaceId: 'forged' } }) });
  assert.equal(forged.status, 400); assert.equal((await forged.json()).error.code, 'MEMORY_INVALID_REQUEST');
  const large = await fetch(url, { method: 'POST', headers, body: 'x'.repeat(65537) }); assert.equal(large.status, 413);
  const wrongType = await fetch(url, { method: 'POST', headers: { 'x-fixture-ui': 'authorized' }, body: '{}' }); assert.equal(wrongType.status, 415);
});

async function archiveServer(t, f) {
  const server = createServer(createMemoryArchiveHandler({ host: f.host, service: f.service }));
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  t.after(async () => { server.closeAllConnections(); await new Promise(resolve => server.close(resolve)); });
  const url = `http://127.0.0.1:${server.address().port}/droy/memory/archive`;
  const headers = { 'x-fixture-ui': 'authorized', 'content-type': 'application/json' };
  return { url, headers, post: (path, body, overrides = {}) => fetch(`${url}/${path}`, { method: 'POST', headers, body: JSON.stringify(body), ...overrides }) };
}
const archivePassword = 'synthetic-backup-password';
const archiveId = 'a'.repeat(64);
const archiveUrl = `/droy/memory/archive/download/${archiveId}`;

test('archives expose only authenticated bounded management routes and host-owned download handles', async t => {
  const f = await fixture(t), calls = [];
  f.host.exportArchive = async (args, context) => { assert.equal(context.req.headers['x-fixture-ui'], 'authorized'); calls.push(args); return {
    downloadUrl: archiveUrl, expiresAt: Date.now() + 60000, path: '/host/private/path', archiveId,
  }; };
  f.host.downloadArchive = async (req, res, id) => { assert.equal(id, archiveId); res.writeHead(200, { 'Content-Type': 'application/octet-stream' }); res.end('synthetic-encrypted-fixture'); };
  const server = await archiveServer(t, f);
  assert.equal((await fetch(`${server.url}/download/${archiveId}`)).status, 401);
  assert.equal((await server.post('export', { recordIds: ['record'], passphrase: archivePassword }, { headers: { 'content-type': 'application/json' } })).status, 401);
  const forged = await server.post('export', { recordIds: ['record'], passphrase: archivePassword, path: '/untrusted' });
  assert.equal(forged.status, 400); assert.equal(calls.length, 0);
  const response = await server.post('export', { recordIds: ['record', 'record'], passphrase: archivePassword });
  const body = await response.json(); assert.equal(response.status, 200); assert.deepEqual(Object.keys(body.result).sort(), ['downloadUrl', 'expiresAt']);
  assert.deepEqual(calls[0].recordIds, ['record']);
  const download = await fetch(`${server.url}/download/${archiveId}`, { headers: server.headers });
  assert.equal(await download.text(), 'synthetic-encrypted-fixture');
  assert.equal((await server.post(`download/${archiveId}`, {})).status, 405);
  assert.equal((await fetch(`${server.url}/download/short`, { headers: server.headers })).status, 404);
  f.gate = { ready: false, rootAuthorized: false };
  assert.equal((await fetch(`${server.url}/download/${archiveId}`, { headers: server.headers })).status, 503);
  assert.ok(memoryToolDefinitions(f.service).every(tool => !/archive|maintenance/.test(tool.name)));
});

test('archive validation rejects unsafe hosts, passwords and noncanonical base64 without passing paths', async t => {
  const f = await fixture(t); let imports = 0;
  f.host.exportArchive = async () => ({ downloadUrl: 'https://example.invalid/leak', expiresAt: Date.now() + 60000 });
  f.host.importArchive = async () => { imports++; return { imported: 1 }; };
  await assert.rejects(f.service.archive('export', { recordIds: ['record'], passphrase: 'short' }), { code: 'MEMORY_INVALID_PASSPHRASE' });
  await assert.rejects(f.service.archive('export', { recordIds: ['record'], passphrase: archivePassword }), { code: 'MEMORY_ARCHIVE_RESPONSE_INVALID' });
  for (const archiveBase64 of ['', 'AB==', 'AQ=\n=', '!!!!']) await assert.rejects(f.service.archive('import', { archiveBase64, passphrase: archivePassword }), { code: 'MEMORY_ARCHIVE_INVALID' });
  await assert.rejects(f.service.archive('import', { archiveBase64: 'AQ==', passphrase: archivePassword, path: '/untrusted' }), { code: 'MEMORY_INVALID_REQUEST' });
  assert.equal(imports, 0);
  const bytes = Buffer.alloc(MEMORY_ARCHIVE_MAX_BYTES, 0x61).toString('base64');
  assert.deepEqual(await f.service.archive('import', { archiveBase64: bytes, passphrase: archivePassword }), { imported: 1 });
  await assert.rejects(f.service.archive('import', { archiveBase64: Buffer.alloc(MEMORY_ARCHIVE_MAX_BYTES + 1).toString('base64'), passphrase: archivePassword }), { code: 'MEMORY_ARCHIVE_INVALID' });
  assert.equal(imports, 1);
  await f.service.manage({ operation: 'settings', args: { enabled: false } });
  await assert.rejects(f.service.archive('import', { archiveBase64: 'AQ==', passphrase: archivePassword }), { code: 'MEMORY_DISABLED' });
});

test('archive import accepts a binary body above ordinary JSON limits and reauthentication cannot double-end responses', async t => {
  const f = await fixture(t); let bytes = 0;
  f.host.importArchive = async (args, context) => { assert.ok(context.req); bytes = Buffer.from(args.archiveBase64, 'base64').length; return { imported: 1 }; };
  const server = await archiveServer(t, f);
  const imported = await server.post('import', { archiveBase64: Buffer.alloc(1024 * 1024).toString('base64'), passphrase: archivePassword });
  assert.equal(imported.status, 200); assert.equal(bytes, 1024 * 1024);
  f.host.exportArchive = async (_args, { res }) => { res.writeHead(401); res.end('denied'); return undefined; };
  const denied = await server.post('export', { recordIds: ['record'], passphrase: archivePassword });
  assert.equal(denied.status, 401); assert.equal(await denied.text(), 'denied');
  const oversized = await server.post('export', { recordIds: ['record'], passphrase: 'x'.repeat(70000) });
  assert.equal(oversized.status, 413);
});

test('held extraction retries need active memory while discard remains a safe local operation', async t => {
  const f = await fixture(t), calls = [];
  f.host.maintenanceAction = async (operation, args) => { calls.push({ operation, args }); return { submitted: true }; };
  const id = 'a'.repeat(64);
  await f.service.manage({ operation: 'maintenance', args: { operation: 'retry', id } });
  await f.service.manage({ operation: 'settings', args: { enabled: false } });
  await assert.rejects(f.service.manage({ operation: 'maintenance', args: { operation: 'retry', id } }), { code: 'MEMORY_DISABLED' });
  await f.service.manage({ operation: 'maintenance', args: { operation: 'discard', id } });
  assert.deepEqual(calls.map(call => call.operation), ['retry', 'discard']);
  await assert.rejects(f.service.manage({ operation: 'maintenance', args: { operation: 'retry', id, endpoint: 'https://untrusted.invalid' } }), { code: 'MEMORY_INVALID_REQUEST' });
});

test('real DSH prompt assembly follows the live locale for the same memory agent without changing validation', async t => {
  const [{ Context }, { default: Tools, defineTool }, { default: Prompt }] = await Promise.all([
    load('@deepseek-ai/cordis'), load('@deepseek-ai/dsh-tools'), load('@deepseek-ai/dsh-system-prompt'),
  ]);
  const ctx = new Context(); t.after(() => ctx.fiber.dispose());
  let preference = 'zh';
  ctx.provide('settings', { describe: () => [{ ns: 'locale', value: { preference } }] });
  await ctx.plugin(Tools, { mode: 'native' }); await ctx.plugin(Prompt);
  for (const tool of memoryToolDefinitions({ tool: async () => ({}) })) ctx.tools.register(defineTool(tool));
  installMemoryToolLocale(ctx);
  const agent = exec().agent;
  const chinese = await ctx.systemPrompt.assemble({ agent });
  assert.equal(resolveMemoryLocale(ctx), 'zh');
  assert.ok(chinese.tools.every(tool => /[\u4e00-\u9fff]/u.test(tool.description)));
  preference = 'en';
  const english = await ctx.systemPrompt.assemble({ agent });
  assert.equal(english.tools.length, 5);
  assert.ok(english.tools.every(tool => !/[\u4e00-\u9fff]/u.test(tool.description)));
  const stripDescriptions = value => JSON.parse(JSON.stringify(value, (key, nested) => key === 'description' ? undefined : nested));
  assert.deepEqual(stripDescriptions(english.tools), stripDescriptions(chinese.tools));
  assert.match(english.tools.find(tool => tool.name === 'droy_memory_get').parameters.properties.record_id.description, /Record ID/);
  const restricted = [{ ...english.tools[0], parameters: { ...english.tools[0].parameters, additionalProperties: false,
    properties: { ...english.tools[0].parameters.properties, text: { type: 'string', maxLength: 42, description: 'old copy' } } } }];
  assert.equal(localizeMemoryToolSchemas(restricted, 'zh')[0].parameters.properties.text.maxLength, 42);
  preference = 'zh-CN';
  const chineseAgain = await ctx.systemPrompt.assemble({ agent });
  assert.deepEqual(chineseAgain.tools, chinese.tools);
});

test('forget confirmation captures the live language and never treats custom text as consent', async () => {
  let preference = 'en', custom = false, lastQuestion;
  const ctx = { settings: { describe: () => [{ ns: 'locale', value: { preference } }] },
    userQuestions: { async ask(input) {
      lastQuestion = input.questions[0];
      const captured = lastQuestion.options[1].label;
      preference = preference === 'en' ? 'zh' : 'en';
      return { answers: [{ id: 'forget-memory', selected: [captured], custom }] };
    } } };
  assert.equal(await confirmMemoryForget(ctx, { recordId: 'fixture-id' }, exec()), true);
  assert.match(lastQuestion.question, /^Forget this memory\?/);
  assert.equal(lastQuestion.options[0].label, 'Cancel');
  assert.equal(await confirmMemoryForget(ctx, { recordId: 'fixture-id' }, exec()), true);
  assert.match(lastQuestion.question, /^忘记这条记忆/);
  assert.equal(lastQuestion.options[0].label, '取消');
  custom = true;
  assert.equal(await confirmMemoryForget(ctx, { recordId: 'fixture-id' }, exec()), false);
});

test('real PTC SDK compilation updates memory prose in the same agent without changing executable signatures', async t => {
  const [{ Context }, { default: Tools, defineTool }, { default: Prompt }] = await Promise.all([
    load('@deepseek-ai/cordis'), load('@deepseek-ai/dsh-tools'), load('@deepseek-ai/dsh-system-prompt'),
  ]);
  const ctx = new Context(); t.after(() => ctx.fiber.dispose());
  let preference = 'zh';
  ctx.provide('settings', { describe: () => [{ ns: 'locale', value: { preference } }] });
  // Only SDK assembly runs here; no synthetic runtime claims code execution.
  ctx.provide('ptcRuntime', { language: 'typescript', programFormat: 'function-body' });
  await ctx.plugin(Tools, { mode: 'ptc' }); await ctx.plugin(Prompt);
  for (const tool of memoryToolDefinitions({ tool: async () => ({}) })) ctx.tools.register(defineTool(tool));
  installMemoryToolLocale(ctx);
  const agent = exec().agent;
  const chinese = await ctx.systemPrompt.assemble({ agent });
  const originalSdk = chinese.sections.find(section => section.name === 'tools:sdk').text;
  assert.ok(originalSdk.includes('仅在询问过去'));
  assert.deepEqual(chinese.tools.map(tool => tool.name), ['run_code']);
  preference = 'en';
  const english = await ctx.systemPrompt.assemble({ agent });
  const englishSdk = english.sections.find(section => section.name === 'tools:sdk').text;
  assert.ok(englishSdk.includes('Query memory only for past events'));
  assert.ok(!/[\u4e00-\u9fff]/u.test(englishSdk));
  const executable = value => value.replace(/\/\*\*[\s\S]*?\*\//g, '').split('\n').filter(line => !line.trim().startsWith('//')).join('\n');
  assert.equal(executable(englishSdk), executable(originalSdk));
  preference = 'zh';
  assert.equal((await ctx.systemPrompt.assemble({ agent })).sections.find(section => section.name === 'tools:sdk').text, originalSdk);
});
