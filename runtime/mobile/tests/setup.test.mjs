import test from 'node:test';
import assert from 'node:assert/strict';
import { createServer } from 'node:http';
import { once } from 'node:events';
import { mkdtemp, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { randomUUID } from 'node:crypto';
import { createSetupService, installSetupServices, SetupError } from '../setup.mjs';

const provider = 'fixture-provider', model = 'fixture-text';
const key = 'fictional-installation-key-do-not-use';
const token = 'fictional-setup-session-token-1234567890';
const greeting = [{ type: 'text-delta', index: 0, text: '你好。' }, { type: 'finish', reason: { kind: 'stop' } }];
function fixture(options = {}) {
  const requests = [], nativeCalls = [], testSessions = [];
  const privacy = { openSetupTestSession() {
    const session = { sessionId: randomUUID(), closed: false, close() { this.closed = true; } };
    testSessions.push(session); return session;
  } };
  const saved = { provider, model };
  const native = { required: true, completed: false, mode: 'dialogue', modelVerification: { passed: false, toolsPassed: false } };
  const credential = { value: key };
  let count = 0, recordedFingerprint;
  const config = { apiKeyEnv: 'FIXTURE_KEY', baseURL: 'https://fixture.invalid/v1', models: [{ id: model }] };
  const ctx = {
    settings: { describe: () => [{ ns: 'fixture-adapter', value: config }, { ns: 'locale', value: { preference: options.locale ?? 'zh' } }] },
    get(name) {
      if (name === 'settings') return this.settings;
      if (name === 'droyPrivacy') return options.noPrivacy ? undefined : privacy;
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
  return { ctx, bridge, native, requests, nativeCalls, credential, config, testSessions,
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
  assert.equal(f.requests[0].sessionId, f.testSessions[0].sessionId);
  assert.equal(f.requests[1].sessionId, f.requests[0].sessionId);
  assert.equal(f.testSessions[0].closed, true);
  assert.ok(!JSON.stringify(result).includes(f.requests[0].sessionId));
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

test('status distinguishes an unavailable saved default from configured models without replacing it', async () => {
  const f = fixture();
  f.saved.provider = 'old-unconfigured-route';
  const status = await f.service.status();
  assert.deepEqual(status.selection, { provider: 'old-unconfigured-route', model });
  assert.equal(status.selectionAvailable, false);
  assert.deepEqual(status.models.map(row => row.provider), [provider]);
  assert.equal(f.saved.provider, 'old-unconfigured-route');
  assert.equal(f.requests.length, 0);
  f.saved.provider = provider;
  assert.equal((await f.service.status()).selectionAvailable, true);
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

test('missing credentials and service denials keep distinct actionable messages', async () => {
  for (const [failure, expected] of [[{ code: 'MISSING_CREDENTIAL' }, /尚未配置/], [{ status: 403 }, /HTTP 403/]]) {
    const f = fixture({ script: () => [{ type: 'finish', reason: { kind: 'error', failure } }] });
    await assert.rejects(f.service.test(), error => error.code === 'MODEL_CREDENTIAL_REQUIRED' && expected.test(error.message));
  }
});

test('unavailable privacy protection fails explicitly and closes the isolated test session', async () => {
  const missing = fixture({ noPrivacy: true });
  await assert.rejects(missing.service.test(), { code: 'MODEL_TEST_PRIVACY_UNAVAILABLE' });
  assert.equal(missing.requests.length, 0);
  for (const code of ['PRIVACY_NOT_INITIALIZED', 'PRIVACY_INVALID_SESSION', 'DROY_REQUIRED_PROTECTION_UNAVAILABLE']) {
    for (const phase of [1, 2]) {
      const f = fixture({ script: (_request, count) => count === phase
        ? [{ type: 'finish', reason: { kind: 'error', failure: { code, message: key } } }] : undefined });
      await assert.rejects(f.service.test(), error => error.code === 'MODEL_TEST_PRIVACY_UNAVAILABLE' && !error.message.includes(key));
      assert.equal(f.requests.length, phase);
      assert.equal(f.testSessions[0].closed, true);
      assert.equal(f.native.modelVerification.passed, false);
    }
  }
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
  assert.equal(f.testSessions[0].closed, true);
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
  ctx.provide('droyPrivacy', f.ctx.get('droyPrivacy'));
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


test('REAL official DeepSeek adapter uses stored credentials for both setup requests and classifies HTTP 401', async t => {
  const { createRequire } = await import('node:module');
  const { pathToFileURL } = await import('node:url');
  const installed = createRequire(new URL('../../dsh/package.json', import.meta.url));
  const load = name => import(pathToFileURL(installed.resolve(name)).href);
  const [{ Context }, { default: LlmRuntime }, official] = await Promise.all([
    load('@deepseek-ai/cordis'), load('@deepseek-ai/dsh-llm'), load('@deepseek-ai/dsh-llm-deepseek-api-key'),
  ]);
  const home = await mkdtemp(join(tmpdir(), 'droy-setup-official-'));
  const previousHome = process.env.DSH_HOME; process.env.DSH_HOME = home;
  t.after(async () => {
    if (previousHome === undefined) delete process.env.DSH_HOME; else process.env.DSH_HOME = previousHome;
    await rm(home, { recursive: true, force: true });
  });
  const received = [];
  let unauthorized = false;
  const server = createServer(async (req, res) => {
    let text = ''; for await (const part of req) text += part;
    const body = JSON.parse(text);
    received.push({ path: req.url, authenticated: req.headers['x-api-key'] === key, body });
    if (unauthorized) {
      res.writeHead(401, { 'content-type': 'application/json' });
      res.end(JSON.stringify({ type: 'error', error: { type: 'authentication_error', message: 'Fictional invalid credential' } }));
      return;
    }
    const tools = Array.isArray(body.tools) && body.tools.some(tool => tool.name === 'droy_setup_echo');
    const events = [{ type: 'message_start', message: { id: 'fixture', role: 'assistant', model: 'deepseek-flash', content: [], usage: { input_tokens: 1, output_tokens: 0 } } }];
    if (tools) {
      const userText = body.messages.at(-1).content.find(part => part.type === 'text').text;
      const value = JSON.parse(userText).value;
      events.push({ type: 'content_block_start', index: 0, content_block: { type: 'tool_use', id: 'official-echo', name: 'droy_setup_echo', input: {} } },
        { type: 'content_block_delta', index: 0, delta: { type: 'input_json_delta', partial_json: JSON.stringify({ value }) } });
    } else events.push({ type: 'content_block_start', index: 0, content_block: { type: 'text', text: '' } },
      { type: 'content_block_delta', index: 0, delta: { type: 'text_delta', text: 'Hello from the local official adapter fixture.' } });
    events.push({ type: 'content_block_stop', index: 0 },
      { type: 'message_delta', delta: { stop_reason: tools ? 'tool_use' : 'end_turn' }, usage: { output_tokens: 1 } }, { type: 'message_stop' });
    res.writeHead(200, { 'content-type': 'text/event-stream' });
    res.end(events.map(event => 'event: ' + event.type + '\ndata: ' + JSON.stringify(event) + '\n\n').join(''));
  });
  server.listen(0, '127.0.0.1'); await once(server, 'listening');
  t.after(async () => { server.closeAllConnections(); await new Promise(resolve => server.close(resolve)); });
  const configValue = { baseURL: `http://127.0.0.1:${server.address().port}/anthropic`, apiKeyEnv: 'FICTIONAL_OFFICIAL_KEY', retryPolicy: { mode: 'normal', maxRetries: 0 } };
  const ctx = new Context(); await ctx.plugin(LlmRuntime); t.after(() => ctx.fiber.dispose());
  const saved = { provider: 'old-route', model: 'old-model' };
  let credentialWrites = 0;
  const credentials = { async resolve(ref) {
    assert.equal(ref, 'FICTIONAL_OFFICIAL_KEY'); return { value: key };
  }, async readRecord() { return undefined; }, async set() { credentialWrites++; } };
  ctx.provide('credentials', credentials);
  ctx.provide('settings', { configure: () => () => {}, describe: () => [
    { ns: 'llm-deepseek-api-key', value: configValue }, { ns: 'locale', value: { preference: 'en' } },
  ] });
  ctx.provide('agentDefaultModel', { currentSelection: () => ({ ...saved }), async saveSelection(next) { Object.assign(saved, next); } });
  const config = official.Config(configValue);
  await ctx.plugin({ name: 'setup-official-fixture', inject: ['llm'], apply: child => official.apply(child, config) });
  const f = fixture();
  ctx.provide('droyPrivacy', f.ctx.get('droyPrivacy'));
  const service = createSetupService(ctx, f.bridge);
  t.after(() => service.dispose());
  assert.equal((await service.status()).selectionAvailable, false);
  const result = await service.test({ provider: 'deepseek-official', model: 'deepseek-flash' });
  assert.equal(result.test.passed, true);
  assert.equal(result.test.toolsPassed, true);
  assert.deepEqual(saved, { provider: 'deepseek-official', model: 'deepseek-flash' });
  assert.equal(credentialWrites, 0);
  assert.equal(received.length, 2);
  assert.ok(received.every(row => row.authenticated && row.path === '/anthropic/v1/messages'));
  assert.equal((await service.status()).selectionAvailable, true);
  unauthorized = true;
  await assert.rejects(service.test(), error => error.code === 'MODEL_CREDENTIAL_REQUIRED' && error.message.includes('HTTP 401') && !error.message.includes(key));
  assert.equal(received.length, 3);
  assert.equal(f.native.modelVerification.passed, false);
  assert.equal(credentialWrites, 0);
});
