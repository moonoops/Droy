import test from 'node:test';
import assert from 'node:assert/strict';
import { createServer } from 'node:http';
import { once } from 'node:events';
import { createSetupService, installSetupServices, SetupError } from '../setup.mjs';

const provider = 'fixture-provider', model = 'fixture-text';
const key = 'fictional-installation-key-do-not-use';
const token = 'fictional-setup-session-token-1234567890';
const greeting = [{ type: 'text-delta', index: 0, text: '你好。' }, { type: 'finish', reason: { kind: 'stop' } }];
function fixture(options = {}) {
  const requests = [], nativeCalls = [];
  const saved = { provider, model };
  const native = { required: true, completed: false, mode: 'dialogue', modelVerification: { passed: false, toolsPassed: false } };
  const credential = { value: key };
  let count = 0, recordedFingerprint;
  const config = { apiKeyEnv: 'FIXTURE_KEY', baseURL: 'https://fixture.invalid/v1', models: [{ id: model }] };
  const ctx = {
    settings: { describe: () => [{ ns: 'fixture-adapter', value: config }, { ns: 'locale', value: { preference: options.locale ?? 'zh' } }] },
    get(name) {
      if (name === 'settings') return this.settings;
      if (name === 'agentDefaultModel') return { currentSelection: () => ({ ...saved }),
        async saveSelection(next) { Object.assign(saved, next); } };
      if (name === 'credentials') return { async resolve(ref) { assert.equal(ref, 'FIXTURE_KEY'); return credential; },
        async readRecord() { return undefined; } };
    },
    llm: {
      listProviders: () => [{ id: provider, name: 'Fictional provider' }],
      listConfigurableProviders: () => [{ provider, settingsNs: 'fixture-adapter', settingsPath: [] }],
      async listModels() { return [{ id: model, name: 'Fictional text model' }]; },
      async resolveModelInfo() { return { inputModalities: options.image ? ['text', 'image'] : ['text'] }; },
      async *stream(request) {
        requests.push(request); count++;
        const scripted = options.script?.(request, count);
        if (scripted) { for await (const chunk of scripted) yield chunk; return; }
        if (!request.tools) { yield* greeting; return; }
        const value = JSON.parse(request.messages[0].content[0].text).value;
        yield { type: 'block-end', index: 0, block: { type: 'tool-call', id: 'fixture-echo', name: 'droy_setup_echo', arguments: JSON.stringify({ value }) } };
        yield { type: 'finish', reason: { kind: 'tool-calls' } };
      },
    },
  };
  const bridge = { async setup(payload) {
    nativeCalls.push({ ...payload });
    if (payload.operation === 'checkModelFingerprint') return { matches: native.modelVerification.passed && recordedFingerprint === payload.configFingerprint };
    if (payload.operation === 'recordModel') {
      recordedFingerprint = payload.configFingerprint;
      native.modelVerification = { ...payload }; delete native.modelVerification.operation;
    }
    if (payload.operation === 'complete') native.completed = true;
    return structuredClone(native);
  } };
  return { ctx, bridge, native, requests, nativeCalls, credential, config,
    service: createSetupService(ctx, bridge), saved, get fingerprint() { return recordedFingerprint; } };
}

test('model setup uses two isolated one-shot requests and records independently verified tools', async () => {
  const f = fixture({ image: true });
  const result = await f.service.test({ provider, model });
  assert.equal(result.ok, true);
  assert.equal(result.test.passed, true);
  assert.equal(result.test.toolsPassed, true);
  assert.equal(result.test.imageInput, true);
  assert.equal(f.requests.length, 2);
  assert.equal(f.requests[0].messages.length, 1);
  assert.equal(f.requests[0].messages[0].content[0].text, '你好');
  assert.equal(f.requests[0].sessionId, undefined);
  assert.equal(f.requests[0].tools, undefined);
  assert.deepEqual(f.requests[1].tools.map(tool => tool.name), ['droy_setup_echo']);
  for (const request of f.requests) assert.equal(request.messages.some(message => message.role !== 'user'), false);
  assert.match(f.fingerprint, /^[a-f0-9]{64}$/);
  assert.ok(!JSON.stringify(result).includes(f.fingerprint));
  assert.ok(!JSON.stringify(result).includes(key));
  const status = await f.service.status();
  assert.equal(status.modelVerification.passed, true);
  assert.deepEqual(status.models.map(row => [row.provider, row.model]), [[provider, model]]);
});

test('a text-only response may pass dialogue but never reports phone tools as available', async () => {
  const f = fixture({ script: () => greeting });
  const result = await f.service.test();
  assert.equal(result.test.passed, true);
  assert.equal(result.test.toolsPassed, false);
  assert.match(result.test.message, /可先使用对话/);
});

test('setup greeting and echo instructions use the same current language as General settings', async () => {
  for (const locale of ['zh', 'en']) {
    const f = fixture({ locale });
    await f.service.test();
    assert.equal(f.requests[0].messages[0].content[0].text, locale === 'zh' ? '你好' : 'Hello');
    assert.match(f.requests[0].system, locale === 'zh' ? /简短中文/ : /short English greeting/);
    assert.match(f.requests[1].system, locale === 'zh' ? /工具协议测试/ : /tool protocol test/);
  }
});

test('bad echo arguments and unexpected tool names fail the tool protocol without executing anything', async () => {
  for (const argumentsValue of ['{}', '{"value":"wrong"}', '{"value":"wrong","command":"execute"}', 'invalid-json']) {
    const f = fixture({ script: (request) => request.tools ? [
      { type: 'block-end', index: 0, block: { type: 'tool-call', name: 'android_tap', id: 'bad-tool', arguments: argumentsValue } },
      { type: 'finish', reason: { kind: 'tool-calls' } },
    ] : greeting });
    assert.equal((await f.service.test()).test.toolsPassed, false);
    assert.equal(f.nativeCalls.some(call => call.operation === 'tool'), false);
  }
});

test('credential and endpoint changes invalidate a previously passed receipt', async () => {
  const f = fixture(); await f.service.test();
  const old = f.fingerprint;
  f.credential.value = 'fictional-replacement-key';
  assert.equal((await f.service.status()).modelVerification.passed, false);
  await f.service.test(); assert.notEqual(f.fingerprint, old);
  f.config.baseURL = 'https://other.fixture.invalid/v1';
  assert.equal((await f.service.verify()).modelVerification.passed, false);
});

test('configuration changes during a test cannot validate untested credentials', async () => {
  const f = fixture({ script: (_request, count) => { if (count === 2) f.credential.value = 'changed-during-test'; return greeting; } });
  await assert.rejects(f.service.test(), error => error.code === 'MODEL_CONFIG_CHANGED');
  assert.equal(f.native.modelVerification.passed, false);
});

test('provider failures are classified without leaking messages or secret values', async () => {
  const f = fixture({ script: () => [{ type: 'finish', reason: { kind: 'error', failure: {
    code: 'MISSING_CREDENTIAL', status: 401, message: `Authorization Bearer ${key}`, requestBody: key,
  } } }] });
  await assert.rejects(f.service.test(), error => {
    assert.equal(error.code, 'MODEL_CREDENTIAL_REQUIRED');
    assert.ok(!error.message.includes(key)); return true;
  });
  assert.equal(f.native.modelVerification.passed, false);
});

test('incomplete and oversized greetings never record passed status', async () => {
  for (const script of [
    [{ type: 'text-delta', index: 0, text: 'unfinished' }],
    [{ type: 'finish', reason: { kind: 'max-tokens' } }],
    [{ type: 'text-delta', index: 0, text: 'x'.repeat(9000) }],
  ]) {
    const f = fixture({ script: () => script });
    await assert.rejects(f.service.test(), error => error instanceof SetupError);
    assert.equal(f.native.modelVerification.passed, false);
  }
});

test('API keys, fake pass flags and partial selections are refused before model I/O', async () => {
  const f = fixture();
  for (const payload of [{ apiKey: key }, { passed: true }, { provider }, { provider, model, configFingerprint: '0'.repeat(64) }, []])
    await assert.rejects(f.service.test(payload), error => error.code === 'INVALID_MODEL_SELECTION');
  assert.equal(f.requests.length, 0);
});

test('client cancellation prevents either a late receipt or another request', async () => {
  const cancellation = new AbortController();
  const f = fixture({ script: () => { cancellation.abort(); return greeting; } });
  await assert.rejects(f.service.test({}, cancellation.signal), error => error.code === 'MODEL_TEST_INTERRUPTED');
  assert.equal(f.native.modelVerification.passed, false);
  assert.equal(f.requests.length, 1);
});

test('concurrent model tests cannot dispatch duplicate paid requests', async () => {
  let release;
  const held = new Promise(resolve => { release = resolve; });
  const f = fixture({ script: (_request, count) => count === 1 ? (async function* () { await held; yield* greeting; })() : undefined });
  const first = f.service.test();
  await assert.rejects(f.service.test(), error => error.code === 'MODEL_TEST_BUSY');
  release(); await first;
  assert.equal(f.requests.length, 2);
});

test('HTTP endpoints require the app session and reject cross-origin, oversized and secret-bearing requests', async t => {
  const previous = process.env.DROY_WEB_TOKEN; process.env.DROY_WEB_TOKEN = token;
  t.after(() => { if (previous === undefined) delete process.env.DROY_WEB_TOKEN; else process.env.DROY_WEB_TOKEN = previous; });
  const f = fixture(); const routes = new Map();
  f.ctx.webServer = { register(route) { routes.set(route.path, route.handler); } };
  installSetupServices(f.ctx, f.bridge);
  const server = createServer((req, res) => {
    const handler = routes.get(req.url);
    if (!handler) { res.writeHead(404); res.end(); return; }
    handler(req, res);
  });
  server.listen(0, '127.0.0.1'); await once(server, 'listening');
  t.after(async () => { server.closeAllConnections(); await new Promise(resolve => server.close(resolve)); });
  const base = `http://127.0.0.1:${server.address().port}`;
  const headers = { cookie: `droy_session=${token}`, 'content-type': 'application/json' };
  assert.equal((await fetch(`${base}/droy/setup/status`)).status, 401);
  assert.equal((await fetch(`${base}/droy/setup/status`, { headers: { ...headers, origin: 'https://untrusted.invalid' } })).status, 401);
  const status = await fetch(`${base}/droy/setup/status`, { headers }); assert.equal(status.status, 200);
  assert.equal((await status.json()).selection.model, model);
  assert.equal((await fetch(`${base}/droy/setup/status`, { method: 'POST', headers })).status, 405);
  const rejected = await fetch(`${base}/droy/setup/model-test`, { method: 'POST', headers, body: JSON.stringify({ apiKey: key }) });
  assert.equal(rejected.status, 400); assert.ok(!(await rejected.text()).includes(key));
  assert.equal((await fetch(`${base}/droy/setup/model-test`, { method: 'POST', headers, body: JSON.stringify({ model: 'x'.repeat(5000), provider }) })).status, 413);
  const tested = await fetch(`${base}/droy/setup/model-test`, { method: 'POST', headers, body: '{}' });
  const body = await tested.text(); assert.equal(tested.status, 200);
  assert.ok(!body.includes(f.fingerprint)); assert.ok(!body.includes(key));
  assert.equal(f.requests.length, 2);
});

test('REAL DSH LlmRuntime accepts the one-shot greeting and echo schema without constructing a session', async t => {
  const { createRequire } = await import('node:module');
  const { pathToFileURL } = await import('node:url');
  const installed = createRequire(new URL('../../dsh/package.json', import.meta.url));
  const load = name => import(pathToFileURL(installed.resolve(name)).href);
  const { Context } = await load('@deepseek-ai/cordis');
  const { default: LlmRuntime, LlmAdapter } = await load('@deepseek-ai/dsh-llm');
  const ctx = new Context(); await ctx.plugin(LlmRuntime); t.after(() => ctx.fiber.dispose());
  const seen = [];
  class ScriptedProvider extends LlmAdapter {
    async resolveModel(provider, id) { return { provider, id, name: 'Fictional local model', inputModalities: ['text'] }; }
    async listModels(provider) { return [{ provider, id: model, name: 'Fictional local model' }]; }
    async *stream(request) {
      seen.push(request);
      if (!request.tools) { yield* greeting; return; }
      const value = JSON.parse(request.messages[0].content[0].text).value;
      yield { type: 'block-start', index: 0, blockType: 'tool-call' };
      yield { type: 'tool-call-delta', index: 0, id: 'real-llm-fixture', name: 'droy_setup_echo', argumentsDelta: JSON.stringify({ value }) };
      yield { type: 'block-end', index: 0, block: { type: 'tool-call', id: 'real-llm-fixture', name: 'droy_setup_echo', arguments: JSON.stringify({ value }) } };
      yield { type: 'finish', reason: { kind: 'tool-calls' } };
    }
  }
  ctx.llm.registerAdapter([provider], new ScriptedProvider());
  ctx.provide('agentDefaultModel', { currentSelection: () => ({ provider, model }) });
  ctx.provide('settings', { describe: () => [{ ns: 'locale', value: { preference: 'zh' } }] });
  const f = fixture();
  const result = await createSetupService(ctx, f.bridge).test();
  assert.equal(result.test.toolsPassed, true);
  assert.equal(seen.length, 2);
  assert.equal(ctx.get('sessions'), undefined);
  assert.equal(ctx.get('agents'), undefined);
  assert.equal(ctx.get('systemPrompt'), undefined);
  assert.equal(seen[0].system.includes('首次安装'), true);
  assert.equal(seen[1].tools.length, 1);
  assert.equal(seen[1].tools[0].parameters.additionalProperties, false);
});

test('disposing setup interrupts a stalled adapter without waiting for its next chunk', async () => {
  let release;
  const held = new Promise(resolve => { release = resolve; });
  const f = fixture({ script: () => (async function* () { await held; yield* greeting; })() });
  const pending = f.service.test();
  while (f.requests.length === 0) await new Promise(resolve => setImmediate(resolve));
  f.service.dispose();
  await assert.rejects(pending, error => error.code === 'MODEL_TEST_INTERRUPTED');
  assert.equal(f.native.modelVerification.passed, false);
  release(); await new Promise(resolve => setImmediate(resolve));
  assert.equal(f.requests.length, 1);
});
