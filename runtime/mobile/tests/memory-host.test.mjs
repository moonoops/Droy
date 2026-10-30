import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { PrivacyStore } from '../../privacy/store.mjs';
import { createMobileMemoryHost } from '../memory-host.mjs';

async function fixture(t, options = {}) {
  const directory = await mkdtemp(join(tmpdir(), 'droy-memory-host-'));
  const calls = [], opened = [];
  const state = { settings: { enabled: false, recallEnabled: true, automaticExtraction: false, privacyEverActivated: options.active ?? true },
    root: { authorized: true, checkedAt: 1 }, execution: { ready: false, epoch: 'native-epoch', reason: 'unqualified' },
    activation: { pendingEnable: options.pending ?? false, cleanupReady: true } };
  let initialized = false, broken = false, closed = false, invalidations = 0, qualification = 0;
  const host = { filesDir: directory, nativeDir: join(directory, 'native'), appUid: 10123 };
  const services = {
    settings: { describe: () => [{ ns: 'locale', value: { preference: options.language ?? 'zh' } }] },
    droyPrivacy: { invalidate() { invalidations++; }, forgetSources: async () => ({}) },
    droyExecution: { installed: () => true },
  };
  const bridge = {
    async memory(input) {
      calls.push(input.operation);
      if (broken) throw Object.assign(new Error('lost'), { code: 'MEMORY_NATIVE_UNAVAILABLE' });
      await options.beforeOperation?.(input, state);
      switch (input.operation) {
        case 'status': return structuredClone(state);
        case 'root-check': return state.root;
        case 'activation.prepare':
          if (options.restart) { state.settings.privacyEverActivated = true; return { restartRequired: true }; }
          if (!state.root.authorized) throw Object.assign(new Error('root'), { code: 'MEMORY_ROOT_REQUIRED' });
          return { host, runtimeEpoch: state.execution.epoch, challenge: 'native-secret', nativeQualificationId: 'a'.repeat(64) };
        case 'protection-activate':
          assert.equal(input.challenge, 'native-secret'); assert.match(input.qualificationId, /^[a-f0-9]{64}$/); return {};
        case 'authorization.inspect': return { available: initialized || options.existingAuthorizationStore === true, host };
        case 'initialization.prepare': { const first = !initialized; initialized = true; return { host, initialize: first }; }
        case 'host-ready': state.execution.ready = true; return {};
        case 'activation.invalidate': state.execution.ready = false; state.activation.pendingEnable = false; return {};
        case 'host-stop': state.execution.ready = false; return {};
        case 'settings': Object.assign(state.settings, input.patch); state.activation.pendingEnable = false; return structuredClone(state.settings);
        default: throw new Error(input.operation);
      }
    },
    async history(input) { calls.push('history:' + input.operation); return { batchId: null, items: [] }; },
  };
  const llm = {};
  const service = createMobileMemoryHost({ ctx: { get: name => services[name], llm }, bridge,
    paths: value => value, environment: {},
    executorFactory: () => ({ close: async () => { closed = true; } }),
    qualify: async () => { qualification++; if (options.failQualification) throw Object.assign(new Error('bad'), { code: 'MEMORY_HOST_QUALIFICATION_FAILED' }); return { synthetic: true }; },
    ...(options.openMemory ? { openMemory: options.openMemory } : {}),
    ...(options.openPrivacy ? { openPrivacy: options.openPrivacy } : {}),
    ...(options.featureAvailabilityOmitted ? {} : { featureAvailable: options.featureAvailable ?? (() => true) }),
  });
  t.after(async () => { await service.close(); await rm(directory, { recursive: true, force: true }); });
  return { service, state, calls, services, llm, directory, opened, setBroken: () => { broken = true; },
    get invalidations() { return invalidations; }, get qualification() { return qualification; }, get executorClosed() { return closed; } };
}

test('inactive installation stays off, creates no store and never probes Root on startup', async t => {
  const f = await fixture(t, { active: false });
  await f.service.start();
  assert.equal((await f.service.memoryHost.getSettings()).enabled, false);
  assert.equal((await f.service.privacyHost.status()).active, false);
  assert.equal(f.qualification, 0); assert.ok(!f.calls.includes('root-check'));
  await assert.rejects(f.service.memoryHost.store(), { code: 'MEMORY_NOT_INITIALIZED' });
});

test('activation persists both stores before native host-ready and enables only pending user intent', async t => {
  const f = await fixture(t, { pending: true }); await f.service.start();
  const state = await f.service.memoryHost.executionStatus(); assert.equal(state.ready, true);
  assert.equal((await f.service.memoryHost.getSettings()).enabled, true);
  const store = await f.service.memoryHost.store(); assert.ok((await store.status()).spaceId);
  assert.ok(f.calls.indexOf('initialization.prepare') < f.calls.indexOf('host-ready'));
  assert.ok(f.calls.indexOf('host-ready') < f.calls.indexOf('settings'));
  assert.equal(f.qualification, 1);
});

test('missing feature disables capture before protection becomes ready and cannot enable from a stale caller', async t => {
  const f = await fixture(t, { pending: true, featureAvailable: () => false,
    beforeOperation(input, state) {
      if (input.operation === 'host-ready') assert.equal(state.settings.enabled, false);
    } });
  f.state.settings.enabled = true;
  await f.service.start();
  assert.equal((await f.service.privacyHost.status()).ready, true);
  assert.equal(f.state.settings.enabled, false); assert.equal(f.state.activation.pendingEnable, false);
  assert.ok(f.calls.indexOf('settings') < f.calls.indexOf('host-ready'));
  await assert.rejects(f.service.memoryHost.saveSettings({ enabled: true }), { code: 'MEMORY_FEATURE_UNAVAILABLE' });
});

test('host factory never assumes an optional memory feature exists without a lifecycle provider', async t => {
  const f = await fixture(t, { pending: true, featureAvailabilityOmitted: true });
  await f.service.start();
  assert.equal((await f.service.privacyHost.status()).ready, true);
  assert.equal(f.state.settings.enabled, false);
  await assert.rejects(f.service.memoryHost.saveSettings({ enabled: true }), { code: 'MEMORY_FEATURE_UNAVAILABLE' });
});

test('feature loss during enable acknowledgement cannot leave collection enabled', async t => {
  let available = true;
  const f = await fixture(t, { featureAvailable: () => available,
    beforeOperation(input) { if (input.operation === 'settings' && input.patch.enabled === true) available = false; } });
  await f.service.start();
  await assert.rejects(f.service.memoryHost.saveSettings({ enabled: true }), { code: 'MEMORY_FEATURE_UNAVAILABLE' });
  assert.equal(f.state.settings.enabled, false);
  assert.equal((await f.service.privacyHost.status()).ready, true);
});

test('failed native/JS qualification cannot initialize or enable memory', async t => {
  const f = await fixture(t, { failQualification: true }); await f.service.start();
  assert.equal((await f.service.memoryHost.executionStatus()).ready, false);
  assert.ok(!f.calls.includes('initialization.prepare')); assert.ok(!f.calls.includes('host-ready'));
  await assert.rejects(f.service.memoryHost.saveSettings({ enabled: true }), { code: 'MEMORY_HOST_QUALIFICATION_FAILED' });
  assert.equal(f.state.settings.enabled, false);
});

test('first enable requests native restart without collecting or creating a database', async t => {
  const f = await fixture(t, { active: false, restart: true }); await f.service.start();
  await assert.rejects(f.service.memoryHost.saveSettings({ enabled: true }), { code: 'MEMORY_RESTART_REQUIRED' });
  assert.equal(f.state.settings.enabled, false); assert.equal(f.qualification, 0);
  assert.ok(!f.calls.includes('initialization.prepare')); assert.ok(!f.calls.includes('history:read'));
});

test('engine shutdown preserves native pending enable across the mandatory first restart', async t => {
  const f = await fixture(t, { active: false, restart: true, pending: true }); await f.service.start();
  await assert.rejects(f.service.memoryHost.saveSettings({ enabled: true }), { code: 'MEMORY_RESTART_REQUIRED' });
  await f.service.close();
  assert.equal(f.state.activation.pendingEnable, true);
  assert.ok(f.calls.includes('host-stop')); assert.ok(!f.calls.includes('activation.invalidate'));
});

test('disable leaves durable privacy active and existing records locally manageable', async t => {
  const f = await fixture(t, { pending: true }); await f.service.start();
  await f.service.memoryHost.saveSettings({ enabled: false });
  const privacy = await f.service.privacyHost.status(); assert.equal(privacy.active, true); assert.equal(privacy.ready, true);
  assert.equal((await f.service.memoryHost.getSettings()).enabled, false);
  assert.equal((await f.service.memoryHost.managementStatus()).ready, true); assert.ok(f.invalidations > 0);
});

test('Root revocation and bridge loss retain the active latch and block management', async t => {
  const f = await fixture(t); await f.service.start();
  f.state.root.authorized = false; f.state.execution.ready = false; f.state.execution.reason = 'MEMORY_ROOT_REQUIRED';
  assert.equal((await f.service.memoryHost.executionStatus()).ready, false);
  assert.equal((await f.service.privacyHost.status()).active, true);
  assert.equal((await f.service.memoryHost.managementStatus()).ready, false);
  f.setBroken(); assert.equal((await f.service.privacyHost.status()).active, true);
  assert.ok(f.invalidations > 0);
});

test('unreviewed host-code, Root process-memory and private network tools cannot bypass execution provider', async t => {
  const f = await fixture(t);
  const authorize = (name, state = 'protected') => f.service.executionHost.authorizeTool({ name }, { state });
  for (const name of ['droy_memory_query', 'android_observe', 'grep']) assert.equal(await authorize(name), true);
  for (const name of ['droy_compute_run', 'android_memory_read', 'cordis_install', 'web_fetch', 'android_tap', 'unknown_plugin']) assert.equal(await authorize(name), false);
  assert.equal(await authorize('web_fetch', 'known-clean'), true);
});

test('private outward actions need one user answer bound to the actual dispatch', async t => {
  const f = await fixture(t);
  const seen = [];
  f.services.userQuestions = { async ask(request) {
    seen.push(request);
    return { answers: [{ id: request.questions[0].id, selected: ['允许本次操作'] }] };
  } };
  const exec = { name: 'android_input_text', callId: 'call-1', agent: { session: { id: 'private-session' } },
    arguments: { text: 'fictional meal entry' } };
  assert.equal(await f.service.executionHost.authorizeTool(exec, { state: 'protected' }), true);
  assert.match(seen[0].questions[0].question, /fictional meal entry/);
  await f.service.executionHost.authorizeTool({ ...exec, callId: 'call-2' }, { state: 'protected' });
  assert.notEqual(seen[0].questions[0].id, seen[1].questions[0].id);
  f.services.userQuestions.ask = async request => ({ answers: [{ id: request.questions[0].id, selected: ['取消'] }] });
  assert.equal(await f.service.executionHost.authorizeTool(exec, { state: 'protected' }), false);
});

test('English disclosure prompt authorizes only the option actually presented', async t => {
  const f = await fixture(t, { language: 'en' });
  const exec = { name: 'android_input_text', callId: 'english-disclosure', agent: { session: { id: 'fixture' } },
    arguments: { text: '原始用户内容' } };
  f.services.userQuestions = { async ask(request) {
    const question = request.questions[0];
    assert.match(question.question, /personal memory/);
    assert.match(question.question, /原始用户内容/);
    assert.deepEqual(question.options.map(option => option.label), ['Cancel', 'Allow this operation']);
    return { answers: [{ id: question.id, selected: ['允许本次操作'] }] };
  } };
  assert.equal(await f.service.executionHost.authorizeTool(exec, { state: 'protected' }), false);
  f.services.userQuestions.ask = async request => ({ answers: [{ id: request.questions[0].id, selected: ['Allow this operation'] }] });
  assert.equal(await f.service.executionHost.authorizeTool(exec, { state: 'protected' }), true);
});

test('plan mode rejects direct memory mutation before store write', async t => {
  const f = await fixture(t); f.services.planMode = { get: () => ({ active: true }) };
  assert.throws(() => f.service.memoryHost.assertWritable({ agent: {} }), { code: 'MEMORY_READ_ONLY' });
});

test('runtime decline inside a serialized extraction check aborts without waiting on its own journal', { timeout: 2000 }, async t => {
  let armed = false, reads = 0, enqueue;
  const f = await fixture(t, { pending: true, beforeOperation(input, state) {
    if (armed && input.operation === 'status' && ++reads === 3) {
      state.execution.epoch = 'changed-during-enqueue'; state.execution.ready = false;
    }
  } });
  await f.service.start({ extractionAdapter: { subscribe(callback) { enqueue = callback; } } });
  f.state.settings.automaticExtraction = true; armed = true;
  const result = await enqueue({ sourceId: 'fictional-source', revision: 'one', occurredAt: new Date().toISOString(),
    categories: ['memory'], sourceRefs: ['fictional-source-ref'], actor: 'unknown' });
  assert.equal(result.status, 'blocked'); assert.equal(result.code, 'MEMORY_EXECUTION_NOT_READY');
  assert.equal(f.service.memoryHost.maintenanceStatus().stopped, true);
});

test('shutdown waits for an in-flight store open and never announces host-ready afterwards', { timeout: 2000 }, async t => {
  let release, opening, memoryClosed = 0, privacyClosed = 0, finished = false;
  const started = new Promise(resolve => { opening = resolve; });
  const pending = new Promise(resolve => { release = resolve; });
  const f = await fixture(t, {
    openPrivacy: async () => ({ close: async () => { privacyClosed++; } }),
    openMemory: async () => { opening(); await pending; return { close: async () => { memoryClosed++; } }; },
  });
  const startup = f.service.start(); await started;
  const shutdown = f.service.close().then(() => { finished = true; });
  await new Promise(resolve => setImmediate(resolve)); assert.equal(finished, false);
  release(); await Promise.all([startup, shutdown]);
  assert.equal(memoryClosed, 1); assert.equal(privacyClosed, 1);
  assert.ok(!f.calls.includes('host-ready')); assert.ok(f.calls.includes('activation.invalidate'));
  assert.equal((await f.service.memoryHost.executionStatus()).ready, false);
});

test('feature unload disables collection while durable protection remains active and workspace matches DSH', async t => {
  const f = await fixture(t, { pending: true }); await f.service.start();
  assert.equal(f.service.executionHost.policy().workspaceRoot, join(f.directory, 'dsh-home', 'workspace'));
  assert.deepEqual(f.service.executionHost.policy().workspaceRoots, [join(f.directory, 'dsh-home', 'workspace'), join(f.directory, 'workspace')]);
  await f.service.memoryHost.featureStop();
  assert.equal(f.state.settings.enabled, false); assert.equal((await f.service.privacyHost.status()).active, true);
  assert.equal(f.service.memoryHost.maintenanceStatus().stopped, true);
});

test('processing grants match the actual URL, purpose and configuration version and reject stale selections', async t => {
  const f = await fixture(t); await f.service.start();
  let configVersion = 'a'.repeat(64);
  f.llm.listProviders = () => [{ id: 'synthetic-provider' }];
  f.llm.listModels = async () => [{ id: 'synthetic-model' }];
  f.services.droyPrivacy.prepareRoute = async (config, purpose) => ({ ...config, purpose, configVersion, url: 'https://synthetic.invalid/v1/chat/completions' });
  f.services.droyPrivacy.describeRoute = value => value;
  const storage = await f.service.privacyHost.store();
  await storage.grantEndpoint({ url: 'https://synthetic.invalid/v1/chat/completions', purpose: 'extraction', configVersion, categories: ['memory'] });
  const endpoints = await f.service.memoryHost.processingEndpoints();
  assert.deepEqual(endpoints.filter(row => row.authorized).map(row => row.purpose), ['extraction']);
  assert.ok(endpoints.every(row => !row.label.includes('undefined')));
  const selected = endpoints.find(row => row.purpose === 'extraction');
  assert.equal((await f.service.memoryHost.resolveProcessingEndpoint(selected.id, 'extraction')).configVersion, configVersion);
  configVersion = 'b'.repeat(64);
  await assert.rejects(f.service.memoryHost.resolveProcessingEndpoint(selected.id, 'extraction'), { code: 'PRIVACY_ROUTING_CHANGED' });
});

test('temporary deletion window waits for memory tick without disabling settings or probing again', async t => {
  let block = false;
  const entered = Promise.withResolvers(), finished = Promise.withResolvers();
  const f = await fixture(t, { beforeOperation: async input => {
    if (block && input.operation === 'root-check') { entered.resolve(); await finished.promise; }
  } });
  await f.service.start();
  const before = structuredClone(f.state.settings);
  block = true; const tick = f.service.tick(); await entered.promise;
  const lease = f.service.memoryHost.maintenanceLease({ operationId: 'synthetic-delete' });
  const held = lease.suspend(); await new Promise(resolve => setImmediate(resolve));
  assert.equal(lease.status().quiet, false);
  const count = f.calls.filter(value => value === 'root-check').length;
  assert.equal(f.service.tick(), undefined);
  finished.resolve(); await tick;
  assert.equal((await held).quiet, true);
  assert.deepEqual(f.state.settings, before);
  assert.equal(f.calls.filter(value => value === 'root-check').length, count);
  assert.equal(lease.resume().producersSuspended, false);
});

test('saved endpoint grants remain inspectable and exactly revocable after execution readiness is lost', async t => {
  const f = await fixture(t); await f.service.start();
  const store = await f.service.privacyHost.store();
  const endpoint = { url: 'https://synthetic.invalid/v1/chat/completions', purpose: 'conversation', configVersion: 'a'.repeat(64) };
  await store.grantEndpoint({ ...endpoint, categories: ['memory'] });
  await store.grantEndpoint({ ...endpoint, purpose: 'extraction', categories: ['memory'] });
  f.state.root.authorized = false; f.state.execution.ready = false;
  f.services.droyPrivacy.prepareRoute = () => assert.fail('Revocation must not prepare a model route');
  const entries = await f.service.memoryHost.storedAuthorizations();
  assert.equal(entries.length, 2);
  const target = entries.find(value => value.purpose === 'conversation');
  await assert.rejects(f.service.memoryHost.revokeStoredAuthorization(target.id, 'extraction'), { code: 'MEMORY_ENDPOINT_REFRESH_REQUIRED' });
  assert.equal((await f.service.memoryHost.revokeStoredAuthorization(target.id, target.purpose)).revoked, true);
  assert.deepEqual((await store.listGrants()).map(value => value.purpose), ['extraction']);
  assert.equal(f.state.execution.ready, false);
});
test('cold unready host opens only existing authorization storage without initializing memory or probing Root', async t => {
  const f = await fixture(t, { active: true, existingAuthorizationStore: true });
  const store = await PrivacyStore.open({ directory: join(f.directory, 'droy-privacy'), markerPath: join(f.directory, 'droy-privacy.identity.json'), initialize: true });
  await store.grantEndpoint({ url: 'https://synthetic.invalid/v1/chat/completions', purpose: 'conversation', configVersion: 'a'.repeat(64), categories: ['memory'] });
  await store.close();
  f.state.root.authorized = false;
  await f.service.start();
  const before = f.calls.length;
  const entries = await f.service.memoryHost.storedAuthorizations();
  assert.equal(entries.length, 1);
  assert.equal((await f.service.memoryHost.revokeStoredAuthorization(entries[0].id, entries[0].purpose)).revoked, true);
  assert.deepEqual(f.calls.slice(before), ['authorization.inspect']);
  assert.equal(f.qualification, 0);
  await assert.rejects(f.service.memoryHost.store(), { code: 'MEMORY_NOT_INITIALIZED' });
  assert.equal((await f.service.memoryHost.getSettings()).enabled, false);
});
