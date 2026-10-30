import test, { after } from 'node:test';
import assert from 'node:assert/strict';
import { createServer } from 'node:http';
import { once } from 'node:events';
import { createRequire, registerHooks } from 'node:module';
import { pathToFileURL } from 'node:url';

const installed = createRequire(new URL('../../dsh/package.json', import.meta.url));
const hooks = registerHooks({ resolve(specifier, context, next) {
  if (specifier.startsWith('@deepseek-ai/')) return { url: pathToFileURL(installed.resolve(specifier)).href, shortCircuit: true };
  return next(specifier, context);
} });
const { createNativeApi } = await import('../native-api.mjs');
const { SettingsController, CredentialsController } = await import('@deepseek-ai/dsh-api-settings-controller');
const { TYPERT: SETTINGS } = await import('@deepseek-ai/dsh-api-settings-controller/typert');
const { TYPERT: LLM } = await import('@deepseek-ai/dsh-llm/typert');
const { AgentDefaultModelConfig } = await import('@deepseek-ai/dsh-agent-default-model');
const officialDeepSeek = await import('@deepseek-ai/dsh-llm-deepseek-api-key');
hooks.deregister();

const token = 'fictional-native-settings-session-token-123456789';
const previousToken = process.env.DROY_WEB_TOKEN;
process.env.DROY_WEB_TOKEN = token;
after(() => { if (previousToken === undefined) delete process.env.DROY_WEB_TOKEN; else process.env.DROY_WEB_TOKEN = previousToken; });
const secret = 'fictional-settings-secret-never-return';
function contract(table, namespace, method, args, result) {
  const descriptor = table.invocations.find(row => row.namespace === namespace && row.method === method);
  assert.ok(descriptor, `${namespace}/${method} remains a public DSH method`);
  descriptor.input({ strict: true }).create().parse(Object.fromEntries(descriptor.parameters.map((parameter, index) => [parameter.wire, args[index]])));
  if (result !== undefined) descriptor.result.create().parse(result);
}
const descriptor = (ns, value) => ({ ns, value, user: structuredClone(value), schema: {}, autoGenerate: false, applies: 'live', revision: 4, secrets: [] });

async function fixture(t, options = {}) {
  const calls = [], savedKeys = new Map([['SHARED_API_KEY', secret], ['DEEPSEEK_API_KEY', secret]]);
  const entries = [
    descriptor('locale', { preference: options.locale || 'en' }), descriptor('ui-theme', { preference: 'system' }),
    descriptor('llm-pi-ai', { providers: {
      relay: { api: 'openai-completions', baseURL: 'https://fixture.invalid/v1', apiKeyEnv: 'SHARED_API_KEY',
        models: [{ id: 'model-a' }], headers: { 'X-Fixture-Secret': secret }, compat: { supportsStore: false } },
      another: { apiKeyEnv: 'SHARED_API_KEY', models: [{ id: 'model-b' }] },
    } }),
    descriptor('llm-deepseek', { apiKeyEnv: 'DEEPSEEK_API_KEY' }),
    descriptor('unrelated-plugin', { fixture: 'not exposed' }),
  ];
  let selected = { provider: 'relay', model: 'model-a' };
  const ctx = { get(name) { return this[name]; } };
  ctx.settings = {
    writable: true,
    describe(readOptions) {
      if (!readOptions) return entries; // locale resolution, not an HTTP response
      assert.deepEqual(readOptions, { redactSecrets: true });
      calls.push({ kind: 'describe' });
      return entries.map(entry => {
        const view = structuredClone(entry);
        if (entry.ns === 'llm-pi-ai' && view.value.providers.relay) {
          delete view.value.providers.relay.headers;
          delete view.user.providers.relay.headers;
          view.secrets = [{ path: ['providers', 'relay', 'headers'], set: true }];
        }
        return view;
      });
    },
    async mutate(ns, ops, expectedRevision) {
      contract(SETTINGS, 'settings', 'mutate', [ns, ops, expectedRevision]);
      const entry = entries.find(row => row.ns === ns);
      if (entry.revision !== expectedRevision) throw Object.assign(new Error(secret), { code: 'SETTINGS_CONFLICT', expected: expectedRevision, actual: entry.revision });
      if (options.settingsFailure) throw new Error(secret);
      calls.push({ kind: 'mutate', ns, ops: structuredClone(ops), expectedRevision });
      for (const target of [entry.value, entry.user]) for (const op of ops) {
        let parent = target;
        for (const key of op.path.slice(0, -1)) parent = parent[key] ??= {};
        if (op.op === 'unset') delete parent[op.path.at(-1)];
        else parent[op.path.at(-1)] = structuredClone(op.value);
      }
      entry.revision++;
    },
  };
  ctx.credentials = {
    describe(ref) { return { configured: savedKeys.has(ref), source: 'fixture-source', writable: true, value: secret, extra: secret }; },
    async set(ref, value) {
      if (options.credentialsFailure) throw new Error(`upstream echoed ${secret} ${value}`);
      calls.push({ kind: 'credential-set', ref }); savedKeys.set(ref, value);
    },
    async unset(ref) { calls.push({ kind: 'credential-unset', ref }); savedKeys.delete(ref); },
  };
  // Exercise the actual installed public controller implementations over an
  // isolated in-memory provider; no credential files or ambient values are read.
  ctx.settingsController = Object.create(SettingsController.prototype, { ctx: { value: ctx } });
  ctx.credentialsController = Object.create(CredentialsController.prototype, { ctx: { value: ctx } });
  ctx.llm = {
    listProviders: () => [{ id: 'relay', name: 'Fixture relay' }],
    listConfigurableProviders: () => [{ provider: 'relay', displayName: 'Fixture relay', settingsNs: 'llm-pi-ai',
      settingsPath: ['providers', 'relay'], declared: true, nativeAuth: { env: ['FIXTURE_NATIVE_AUTH'], oauth: false } }],
    async remoteDiscoverModels(settingsNs, request, signal) {
      contract(LLM, 'llm', 'discoverModels', [settingsNs, request]);
      assert.ok(signal instanceof AbortSignal);
      calls.push({ kind: 'discovery', settingsNs, request: structuredClone(request), signal });
      if (options.discoveryFailure) throw Object.assign(new Error(secret), { code: options.discoveryFailure });
      const result = [{ id: 'model-discovered', name: 'Fixture model', inputModalities: ['text'] }];
      contract(LLM, 'llm', 'discoverModels', [settingsNs, request], result);
      return result;
    },
  };
  ctx.sessionController = { modelCatalog: async () => ({ default: selected, routableProviders: ['relay'], failures: [],
    groups: [{ id: 'relay', name: 'Fixture relay', models: [{ id: 'model-a', name: 'A' }, { id: 'model-b', name: 'B' }] }] }) };
  ctx.agentDefaultModel = { currentSelection: () => selected, saveSelection: async value => { calls.push({ kind: 'default', value }); selected = value; } };
  const api = createNativeApi(ctx), server = createServer(api.handle);
  server.listen(0, '127.0.0.1'); await once(server, 'listening');
  t.after(async () => { api.dispose(); server.closeAllConnections(); await new Promise(resolve => server.close(resolve)); });
  const url = `http://127.0.0.1:${server.address().port}/droy/native/settings`;
  const headers = { Cookie: `droy_session=${token}`, 'Content-Type': 'application/json' };
  async function request(path = '', input, extra = {}) {
    const response = await fetch(url + path, { headers, ...(input === undefined ? {} : { method: 'POST', body: JSON.stringify(input) }), ...extra });
    const text = await response.text();
    assert.ok(!text.includes(secret), 'HTTP never returns the fictional stored key or upstream echo');
    return { status: response.status, text, value: text.startsWith('{') ? JSON.parse(text) : null };
  }
  return { ctx, calls, entries, savedKeys, headers, request };
}

test('settings dispatch authenticates before accessing any configuration or credential service', async t => {
  const f = await fixture(t);
  for (const path of ['', '/providers', '/credentials/set', '/discover-models']) {
    const response = await f.request(path, {}, { headers: {} });
    assert.equal(response.status, 401);
  }
  assert.equal((await f.request('', undefined, { headers: { ...f.headers, Origin: 'https://foreign.invalid' } })).status, 401);
  assert.equal(f.calls.length, 0);
});

test('describe uses real upstream redaction and returns only mounted native settings namespaces', async t => {
  const f = await fixture(t), response = await f.request();
  assert.equal(response.status, 200);
  const result = response.value.result;
  contract(SETTINGS, 'settings', 'describe', [], result);
  assert.deepEqual(result.namespaces.map(row => row.ns), ['locale', 'ui-theme', 'llm-pi-ai', 'llm-deepseek']);
  assert.equal(result.namespaces[2].value.providers.relay.headers, undefined);
  assert.deepEqual(result.namespaces[2].secrets, [{ path: ['providers', 'relay', 'headers'], set: true }]);
});

test('changed-path mutation preserves unseen provider fields, shared keys, other providers and model default', async t => {
  const f = await fixture(t);
  const input = { ns: 'llm-pi-ai', expectedRevision: 4, ops: [{ op: 'set', path: ['providers', 'relay', 'displayName'], value: 'Updated relay' }] };
  const response = await f.request('/mutate', input);
  assert.equal(response.status, 200);
  contract(SETTINGS, 'settings', 'mutate', [input.ns, input.ops, input.expectedRevision], response.value.result);
  assert.equal(response.value.result.revision, 5);
  const providers = f.entries[2].value.providers;
  assert.equal(providers.relay.headers['X-Fixture-Secret'], secret);
  assert.equal(providers.relay.compat.supportsStore, false);
  assert.equal(providers.another.apiKeyEnv, 'SHARED_API_KEY');
  assert.equal(f.savedKeys.get('SHARED_API_KEY'), secret);
  assert.equal(f.calls.some(call => ['default', 'credential-set', 'credential-unset'].includes(call.kind)), false);
  const conflict = await f.request('/mutate', input);
  assert.equal(conflict.status, 409);
  assert.equal(conflict.value.error.code, 'settings/conflict');
});

test('strict mutations reject root replacement, existing provider replacement, unknown fields and unsupported namespaces', async t => {
  const f = await fixture(t);
  const inputs = [
    { ns: 'locale', ops: [{ op: 'set', path: ['preference'], value: 'fr' }] },
    { ns: 'locale', ops: [{ op: 'unset', path: ['preference'] }] },
    { ns: 'llm-pi-ai', ops: [{ op: 'set', path: [], value: {} }] },
    { ns: 'llm-pi-ai', ops: [{ op: 'set', path: ['providers', 'relay'], value: {} }] },
    { ns: 'llm-pi-ai', ops: [{ op: 'set', path: ['providers', '__proto__'], value: {} }] },
    { ns: 'llm-pi-ai', ops: [{ op: 'set', path: ['providers', 'relay', 'apiKey'], value: secret }] },
    { ns: 'llm-pi-ai', ops: [{ op: 'set', path: ['providers', 'relay', 'baseURL'], value: 'https://user:password@fixture.invalid' }] },
    { ns: 'unknown', ops: [] },
    { ns: 'ui-theme', ops: [{ op: 'set', path: ['preference'], value: 'dark', extra: true }] },
  ];
  for (const input of inputs) assert.equal((await f.request('/mutate', { ...input, expectedRevision: 4 })).status, 400);
  assert.equal((await f.request('/mutate', { ns: 'locale', ops: [{ op: 'set', path: ['preference'], value: 'zh' }] })).status, 400);
  assert.equal(f.calls.some(call => call.kind === 'mutate'), false);
});

test('provider creation and credentials are separate commits; a failed key step does not undo saved settings', async t => {
  const f = await fixture(t, { credentialsFailure: true });
  const response = await f.request('/mutate', { ns: 'llm-pi-ai', expectedRevision: 4, ops: [{ op: 'set', path: ['providers', 'new-route'],
    value: { api: 'anthropic-messages', baseURL: 'https://fixture.invalid', apiKeyEnv: 'NEW_ROUTE_API_KEY', models: [{ id: 'claude-fixture' }] } }] });
  assert.equal(response.status, 200);
  const credential = await f.request('/credentials/set', { ref: 'NEW_ROUTE_API_KEY', value: 'fictional-new-value' });
  assert.equal(credential.status, 422);
  assert.equal(credential.value.error.code, 'credential/rejected');
  assert.equal(f.entries[2].value.providers['new-route'].api, 'anthropic-messages');
  assert.equal(f.entries[2].revision, 5);
  assert.equal(f.savedKeys.has('NEW_ROUTE_API_KEY'), false);
});

test('credential metadata exposes no value, permits native-auth metadata, and requires explicit configured refs for writes', async t => {
  const f = await fixture(t);
  const response = await f.request('/credentials/describe', { refs: ['SHARED_API_KEY', 'FIXTURE_NATIVE_AUTH'] });
  assert.equal(response.status, 200);
  assert.deepEqual(response.value.result.SHARED_API_KEY, { configured: true, source: 'fixture-source', writable: true });
  assert.deepEqual(response.value.result.FIXTURE_NATIVE_AUTH, { configured: false, source: 'fixture-source', writable: true });
  contract(SETTINGS, 'credentials', 'describe', [['SHARED_API_KEY', 'FIXTURE_NATIVE_AUTH']], response.value.result);
  for (const value of ['', '   ', 'NAME=value', '"quoted"', 'line\nbreak'])
    assert.equal((await f.request('/credentials/set', { ref: 'SHARED_API_KEY', value })).status, 400);
  for (const ref of ['UNKNOWN_ENV', 'FIXTURE_NATIVE_AUTH'])
    assert.equal((await f.request('/credentials/unset', { ref })).status, 400);
  assert.equal((await f.request('/credentials/describe', { refs: ['UNKNOWN_ENV'] })).status, 400);
  assert.equal((await f.request('/credentials/describe', { refs: Array(65).fill('SHARED_API_KEY') })).status, 400);
  assert.equal(f.savedKeys.get('SHARED_API_KEY'), secret);
  assert.deepEqual((await f.request('/credentials/set', { ref: 'SHARED_API_KEY', value: '  fictional-replacement  ' })).value.result,
    { committed: true, ref: 'SHARED_API_KEY', operation: 'set' });
  assert.equal(f.savedKeys.get('SHARED_API_KEY'), 'fictional-replacement');
  assert.equal((await f.request('/credentials/unset', { ref: 'SHARED_API_KEY' })).value.result.committed, true);
  assert.equal(f.savedKeys.has('SHARED_API_KEY'), false);
});

test('deleting a provider never removes a shared credential', async t => {
  const f = await fixture(t);
  const response = await f.request('/mutate', { ns: 'llm-pi-ai', expectedRevision: 4, ops: [{ op: 'unset', path: ['providers', 'relay'] }] });
  assert.equal(response.status, 200);
  assert.equal(f.savedKeys.get('SHARED_API_KEY'), secret);
  assert.equal(f.entries[2].value.providers.another.apiKeyEnv, 'SHARED_API_KEY');
  assert.equal(f.calls.some(call => call.kind === 'credential-unset'), false);
});

test('discovery forwards the actual public contract without persisting drafts or reading keys into native state', async t => {
  const f = await fixture(t);
  for (const api of ['openai-completions', 'openai-responses', 'anthropic-messages']) {
    const request = { provider: 'relay', baseURL: 'https://fixture.invalid/v1', api };
    const response = await f.request('/discover-models', { settingsNs: 'llm-pi-ai', request });
    assert.equal(response.status, 200);
    assert.deepEqual(f.calls.at(-1).request, request);
    assert.equal(response.value.result[0].id, 'model-discovered');
  }
  assert.equal((await f.request('/discover-models', { settingsNs: 'llm-pi-ai', request: { apiKey: 'fictional-one-shot', api: 'openai-completions', baseURL: 'https://fixture.invalid/v1' } })).status, 200);
  assert.equal(f.calls.at(-1).request.apiKey, 'fictional-one-shot');
  assert.equal(f.calls.some(call => ['mutate', 'credential-set', 'default'].includes(call.kind)), false);
  assert.equal(f.savedKeys.get('SHARED_API_KEY'), secret);
  for (const request of [{ api: 'unknown' }, { baseURL: 'file:///fixture' }, { headers: { Authorization: secret } }, { apiKey: '' }])
    assert.equal((await f.request('/discover-models', { settingsNs: 'llm-pi-ai', request })).status, 400);
});

test('provider failures cannot echo credentials in messages, details or unknown codes', async t => {
  for (const code of ['llm/model-discovery-rejected', secret, '__proto__']) {
    const f = await fixture(t, { discoveryFailure: code });
    const response = await f.request('/discover-models', { settingsNs: 'llm-pi-ai', request: { provider: 'relay' } });
    assert.equal(response.status, code === 'llm/model-discovery-rejected' ? 422 : 500);
    assert.equal(response.value.error.code, code === 'llm/model-discovery-rejected' ? code : 'NATIVE_SETTINGS_FAILED');
  }
});

test('global default selection validates catalog and remains separate from provider edits', async t => {
  const f = await fixture(t);
  assert.equal((await f.request('/default-model', { provider: 'relay', model: 'missing' })).status, 422);
  assert.equal((await f.request('/default-model', { provider: 'relay', model: 'model-b', sessionId: 'other' })).status, 400);
  const response = await f.request('/default-model', { provider: 'relay', model: 'model-b' });
  assert.deepEqual(response.value.result, { committed: true, selection: { provider: 'relay', model: 'model-b' } });
  assert.equal(f.calls.filter(call => call.kind === 'default').length, 1);
});

test('read-only or non-applying default service cannot yield a successful save receipt', async t => {
  const f = await fixture(t);
  f.ctx.settings.writable = false;
  assert.equal((await f.request('/default-model', { provider: 'relay', model: 'model-b' })).status, 422);
  assert.equal(f.calls.some(call => call.kind === 'default'), false);
  f.ctx.settings.writable = true;
  f.ctx.agentDefaultModel.saveSelection = async () => {};
  const response = await f.request('/default-model', { provider: 'relay', model: 'model-b' });
  assert.equal(response.status, 409);
  assert.equal(response.value.error.code, 'NATIVE_SETTINGS_DEFAULT_NOT_APPLIED');
});

test('provider directory returns declared fields and source references without leaking provider diagnostics or extra values', async t => {
  const f = await fixture(t);
  f.ctx.llm.listProviders = () => [{ id: 'relay', name: 'Fixture', secret }];
  f.ctx.llm.listConfigurableProviders = () => [{ provider: 'relay', displayName: 'Fixture', settingsNs: 'llm-pi-ai',
    settingsPath: ['providers', 'relay'], error: secret, secret, nativeAuth: { env: ['FIXTURE_NATIVE_AUTH'], oauth: false, secret } }];
  const response = await f.request('/providers');
  assert.equal(response.status, 200);
  assert.deepEqual(response.value.result, { active: [{ id: 'relay', name: 'Fixture' }], configurable: [{
    provider: 'relay', displayName: 'Fixture', settingsNs: 'llm-pi-ai', settingsPath: ['providers', 'relay'],
    nativeAuth: { env: ['FIXTURE_NATIVE_AUTH'], oauth: false }, unavailable: true,
  }] });
});

test('public settings schema refusal remains an error without leaking the failing value', async t => {
  const f = await fixture(t, { settingsFailure: true });
  const response = await f.request('/mutate', { ns: 'ui-theme', expectedRevision: 4, ops: [{ op: 'set', path: ['preference'], value: 'dark' }] });
  assert.equal(response.status, 422);
  assert.equal(response.value.error.code, 'settings/rejected');
  assert.equal(f.entries[1].revision, 4);
  delete f.ctx.settingsController;
  assert.equal((await f.request()).status, 503);
});

test('HTTP routes enforce exact method, query, JSON, UTF-8 and bounded bodies', async t => {
  const f = await fixture(t);
  assert.equal((await f.request('/unknown')).status, 404);
  assert.equal((await f.request('/replace', {})).status, 404);
  assert.equal((await f.request('', {})).status, 405);
  assert.equal((await f.request('/mutate')).status, 405);
  assert.equal((await f.request('?extra=1')).status, 400);
  assert.equal((await f.request('/mutate', {}, { headers: { ...f.headers, 'Content-Type': 'text/plain' } })).status, 400);
  assert.equal((await f.request('/mutate', {}, { body: Buffer.from([0xc3, 0x28]) })).status, 400);
  assert.equal((await f.request('/mutate', {}, { body: 'x'.repeat(256 * 1024 + 1) })).status, 413);
});

test('locale edits use the existing namespace and localized failures follow its current value', async t => {
  const f = await fixture(t);
  assert.equal((await f.request('/mutate', { ns: 'locale', expectedRevision: 4, ops: [{ op: 'set', path: ['preference'], value: 'zh' }] })).status, 200);
  assert.match((await f.request('/unknown')).value.error.message, /未找到/);
  assert.equal((await f.request('/mutate', { ns: 'locale', expectedRevision: 5, ops: [{ op: 'set', path: ['preference'], value: 'en' }] })).status, 200);
  assert.match((await f.request('/unknown')).value.error.message, /not found/);
});


test('built-in model overrides patch only the provider override map', async t => {
  const { request, entries } = await fixture(t);
  const result = await request('/mutate', { ns: 'llm-pi-ai', expectedRevision: 4,
    ops: [{ op: 'set', path: ['providers', 'relay', 'modelOverrides'], value: { 'model-a': { reasoningEfforts: { off: null, high: 'deep' }, contextWindow: 8000 } } }] });
  assert.equal(result.status, 200);
  const profile = entries.find(entry => entry.ns === 'llm-pi-ai').value.providers.relay;
  assert.deepEqual(profile.models, [{ id: 'model-a' }]);
  assert.equal(profile.modelOverrides['model-a'].reasoningEfforts.off, null);
  assert.deepEqual(profile.compat, { supportsStore: false });
});

test('search settings retain service routing and authorize only their credential references', async t => {
  const { request, entries, savedKeys } = await fixture(t);
  entries.push(descriptor('web', { searchProvider: 'deepseek-search-api', fetchProvider: 'http' }));
  entries.push(descriptor('web-deepseek-api', { apiKeyEnv: 'SEARCH_KEY', count: 10, sendOriginalQuestion: false }));
  let response = await request('/mutate', { ns: 'web-deepseek-api', expectedRevision: 4, ops: [{ op: 'set', path: ['count'], value: 5 }] });
  assert.equal(response.status, 200);
  assert.equal(entries.find(entry => entry.ns === 'web').value.searchProvider, 'deepseek-search-api');
  assert.equal(entries.find(entry => entry.ns === 'web').value.fetchProvider, 'http');
  response = await request('/credentials/set', { ref: 'SEARCH_KEY', value: 'fictional-search-key' });
  assert.equal(response.status, 200);
  assert.equal(savedKeys.get('SEARCH_KEY'), 'fictional-search-key');
  response = await request('/mutate', { ns: 'web', expectedRevision: 4, ops: [{ op: 'set', path: ['searchProvider'], value: 'unverified-plugin' }] });
  assert.equal(response.status, 400);
  response = await request('/mutate', { ns: 'web-deepseek-api', expectedRevision: 5, ops: [{ op: 'set', path: ['apiKey'], value: 'inline-key' }] });
  assert.equal(response.status, 400);
});

test('subtask model selection requires exact unique provider/model routes and preserves routes when disabled', async t => {
  const { request, entries } = await fixture(t);
  entries.push(descriptor('subagent-model-selection-settings', { enabled: false, allowedModels: [] }));
  const change = ops => request('/mutate', { ns: 'subagent-model-selection-settings', expectedRevision: 4, ops });
  assert.equal((await change([{ op: 'set', path: ['enabled'], value: true }])).status, 400);
  assert.equal((await change([{ op: 'set', path: ['allowedModels'], value: ['model-a'] }])).status, 400);
  const routes = [{ provider: 'relay', model: 'model-a' }, { provider: 'other', model: 'model-a' }];
  assert.equal((await change([{ op: 'set', path: ['allowedModels'], value: routes }, { op: 'set', path: ['enabled'], value: true }])).status, 200);
  const response = await request('/mutate', { ns: 'subagent-model-selection-settings', expectedRevision: 5, ops: [{ op: 'set', path: ['enabled'], value: false }] });
  assert.equal(response.status, 200);
  assert.deepEqual(entries.find(entry => entry.ns === 'subagent-model-selection-settings').value.allowedModels, routes);
});

test('native provider removal clears its default, removes only its profile and retains shared credentials', async t => {
  const f = await fixture(t);
  const response = await f.request('/providers/remove', { provider: 'relay', expectedRevision: 4 });
  assert.equal(response.status, 200);
  assert.equal(response.value.result.committed, true);
  assert.equal(f.entries[2].value.providers.relay, undefined);
  assert.equal(f.entries[2].value.providers.another.apiKeyEnv, 'SHARED_API_KEY');
  assert.equal(f.savedKeys.get('SHARED_API_KEY'), secret);
  assert.deepEqual(f.ctx.agentDefaultModel.currentSelection(), { provider: '', model: '' });
});

test('builtin removal empties its real catalog without falling back to inherited credentials or changing another default', async t => {
  const f = await fixture(t);
  f.ctx.llm.listConfigurableProviders = () => [{ provider: 'deepseek-official', settingsNs: 'llm-deepseek', settingsPath: [] }];
  assert.equal((await f.request('/providers/remove', { provider: 'deepseek-official', expectedRevision: 4 })).status, 200);
  assert.deepEqual(f.entries[3].value.models, []);
  assert.equal(f.entries[3].value.apiKeyEnv, 'DEEPSEEK_API_KEY');
  assert.deepEqual(f.ctx.agentDefaultModel.currentSelection(), { provider: 'relay', model: 'model-a' });
  assert.equal(f.savedKeys.has('DEEPSEEK_API_KEY'), true);
});

test('provider removal checks revision and actual committed default before changing provider settings', async t => {
  const f = await fixture(t);
  assert.equal((await f.request('/providers/remove', { provider: 'relay', expectedRevision: 3 })).status, 409);
  assert.equal((await f.request('/providers/remove', { provider: 'unknown', expectedRevision: 4 })).status, 404);
  assert.equal(f.calls.some(call => call.kind === 'default'), false);
  f.ctx.agentDefaultModel.saveSelection = async () => {};
  assert.equal((await f.request('/providers/remove', { provider: 'relay', expectedRevision: 4 })).status, 409);
  assert.ok(f.entries[2].value.providers.relay);
});


test('actual installed schemas preserve cleared default and empty official catalog without a network listing', () => {
  const selection = AgentDefaultModelConfig.Config({ provider: '', model: '' });
  assert.equal(selection.provider.get(), '');
  assert.equal(selection.model.get(), '');
  const options = officialDeepSeek.resolveAdapterOptions(
    officialDeepSeek.plainOptions(officialDeepSeek.Config({ models: [] })), { get() {} });
  assert.deepEqual(options.models, []);
  assert.equal(options.listing, undefined);
});

test('installed pi-ai provider removal removes its profile instead of using its inheriting empty model list', async t => {
  const f = await fixture(t);
  f.ctx.llm.listConfigurableProviders = () => [{ provider: 'relay', settingsNs: 'llm-pi-ai', settingsPath: ['providers', 'relay'], declared: false }];
  assert.equal((await f.request('/providers/remove', { provider: 'relay', expectedRevision: 4 })).status, 200);
  assert.equal(f.entries[2].value.providers.relay, undefined);
});

test('subagent runtime limits expose only supported integer fields and preserve unrelated config', async t => {
  const { request, entries } = await fixture(t);
  entries.push(descriptor('subagent', { maxDepth: 1, maxActiveSubagents: 8, retained: 'fixture' }));
  for (const [field, value] of [['maxDepth', -1], ['maxDepth', 1.5], ['maxActiveSubagents', 0], ['other', 2]]) {
    assert.equal((await request('/mutate', { ns: 'subagent', expectedRevision: 4,
      ops: [{ op: 'set', path: [field], value }] })).status, 400);
  }
  assert.equal((await request('/mutate', { ns: 'subagent', expectedRevision: 4,
    ops: [{ op: 'set', path: ['maxDepth'], value: 0 }, { op: 'set', path: ['maxActiveSubagents'], value: 12 }] })).status, 200);
  assert.deepEqual(entries.find(row => row.ns === 'subagent').value, { maxDepth: 0, maxActiveSubagents: 12, retained: 'fixture' });
});

test('new web API switch routes search and fetch together without overwriting endpoint parameters', async t => {
  const { request, entries } = await fixture(t);
  entries.push(descriptor('web', { searchProvider: 'deepseek-official', fetchProvider: 'http' }));
  entries.push(descriptor('web-search-deepseek', { maxUses: 5, model: 'unchanged-model' }));
  assert.equal((await request('/mutate', { ns: 'web', expectedRevision: 4, ops: [
    { op: 'set', path: ['searchProvider'], value: 'deepseek-search-api' },
    { op: 'set', path: ['fetchProvider'], value: 'deepseek-fetch-api' },
  ] })).status, 200);
  assert.deepEqual(entries.find(row => row.ns === 'web').value,
    { searchProvider: 'deepseek-search-api', fetchProvider: 'deepseek-fetch-api' });
  assert.deepEqual(entries.find(row => row.ns === 'web-search-deepseek').value, { maxUses: 5, model: 'unchanged-model' });
});
