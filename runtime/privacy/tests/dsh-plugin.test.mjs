import test, { after } from 'node:test';
import assert from 'node:assert/strict';
import { createServer } from 'node:http';
import { mkdtemp, mkdir, readFile, readdir, rm, symlink, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { createRequire } from 'node:module';
import { PrivacyStore } from '../store.mjs';
import { createDshPrivacyService, PREPARED_ROUTE } from '../dsh-plugin.mjs';
import { patchPrivacyLlm, patchPrivacyPiAi } from '../upstream-patch.mjs';

const installed = createRequire(new URL('../../dsh/package.json', import.meta.url));
const modules = fileURLToPath(new URL('../../dsh/node_modules/', import.meta.url));
const load = name => import(pathToFileURL(installed.resolve(name)).href);
let patchedRoot;
const patched = (async () => {
  patchedRoot = await mkdtemp(join(tmpdir(), 'droy-privacy-adapters-'));
  const target = join(patchedRoot, 'node_modules');
  await mkdir(join(target, '@deepseek-ai'), { recursive: true });
  for (const name of await readdir(modules)) {
    if (name !== '@deepseek-ai') await symlink(join(modules, name), join(target, name));
  }
  for (const name of await readdir(join(modules, '@deepseek-ai'))) {
    if (!['dsh-llm', 'dsh-llm-pi-ai'].includes(name)) {
      await symlink(join(modules, '@deepseek-ai', name), join(target, '@deepseek-ai', name));
      continue;
    }
    const destination = join(target, '@deepseek-ai', name);
    await mkdir(join(destination, 'lib'), { recursive: true });
    await writeFile(join(destination, 'package.json'), await readFile(join(modules, '@deepseek-ai', name, 'package.json')));
    const source = await readFile(join(modules, '@deepseek-ai', name, 'lib/index.js'), 'utf8');
    await writeFile(join(destination, 'lib/index.js'), name === 'dsh-llm' ? patchPrivacyLlm(source) : patchPrivacyPiAi(source));
  }
  const llm = await import(pathToFileURL(join(target, '@deepseek-ai/dsh-llm/lib/index.js')).href);
  const pi = await import(pathToFileURL(join(target, '@deepseek-ai/dsh-llm-pi-ai/lib/index.js')).href);
  return { llm, pi };
})();
after(async () => { await patched.catch(() => {}); if (patchedRoot) await rm(patchedRoot, { recursive: true, force: true }); });

async function fixture(t, { active = true, ready = true, execution = true, unavailableStore = false, api = 'openai-completions', model = 'synthetic' } = {}) {
  const [{ llm, pi }, { Context }] = await Promise.all([patched, load('@deepseek-ai/cordis')]);
  const directory = await mkdtemp(join(tmpdir(), 'droy-privacy-plugin-'));
  const store = await PrivacyStore.open({ directory: join(directory, 'store'), markerPath: join(directory, 'marker'), initialize: true });
  const receipts = [];
  let mode = 'success', activeFlag = active, readyFlag = ready, identity = 'synthetic-direct', locale = 'zh';
  let storeError = unavailableStore ? 'PRIVACY_NOT_INITIALIZED' : undefined;
  const server = createServer(async (req, res) => {
    let body = ''; for await (const chunk of req) body += chunk;
    receipts.push({ path: req.url, private: body.includes('SYNTHETIC_PRIVATE_RECORD') });
    if (mode === 'redirect') { res.writeHead(307, { location: '/unapproved' }); res.end(); return; }
    res.writeHead(200, { 'content-type': 'text/event-stream' });
    if (req.url.endsWith('/responses')) {
      const item = { id: 'synthetic-message', type: 'message', role: 'assistant', status: 'completed', content: [{ type: 'output_text', text: 'Synthetic summary', annotations: [] }] };
      const events = [
        { type: 'response.created', response: { id: 'synthetic-response' } },
        { type: 'response.output_item.added', output_index: 0, item: { ...item, content: [] } },
        { type: 'response.output_text.delta', output_index: 0, content_index: 0, delta: 'Synthetic summary' },
        { type: 'response.output_item.done', output_index: 0, item },
        { type: 'response.completed', response: { id: 'synthetic-response', status: 'completed', output: [item], usage: { input_tokens: 1, output_tokens: 2, total_tokens: 3 } } },
      ];
      res.end(events.map(event => `event: ${event.type}\ndata: ${JSON.stringify(event)}\n\n`).join(''));
      return;
    }
    res.write('data: ' + JSON.stringify({ id: 'synthetic', object: 'chat.completion.chunk', created: 0, model: 'synthetic',
      choices: [{ index: 0, delta: { role: 'assistant', content: 'Synthetic summary' }, finish_reason: null }] }) + '\n\n');
    if (mode === 'hold') return;
    res.end('data: ' + JSON.stringify({ id: 'synthetic', object: 'chat.completion.chunk', created: 0, model: 'synthetic',
      choices: [{ index: 0, delta: {}, finish_reason: 'stop' }] }) + '\n\ndata: [DONE]\n\n');
  });
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  const url = `http://127.0.0.1:${server.address().port}/v1`;
  const ctx = new Context();
  ctx.provide('settings', { describe: () => [{ ns: 'locale', value: { preference: locale } }] });
  await ctx.plugin(llm.default);
  ctx.provide('credentials', { resolve: async () => ({ value: 'synthetic-not-a-secret' }) });
  const host = { status: async () => ({ active: activeFlag, ready: readyFlag }),
    routingIdentity: () => identity, activate: async () => { activeFlag = true; } };
  const service = createDshPrivacyService({ ctx, store: async () => {
    if (storeError) throw Object.assign(new Error(storeError), { code: storeError });
    return store;
  }, host });
  ctx.provide('droyPrivacy', service);
  if (execution) ctx.provide('droyExecution', { beforeDisclosure: async () => {}, installed: () => true });
  let raw = { providers: { synthetic: { api, baseURL: url, apiKeyEnv: 'SYNTHETIC_TEST_KEY',
    models: [{ id: model, input: ['text'], contextWindow: 32768, maxTokens: 4096 }], retryPolicy: { mode: 'normal', maxRetries: 0 } } } };
  const validated = pi.Config(raw);
  let providers = validated.providers.get();
  const config = { ...validated, providers: { get: () => providers } };
  t.after(async () => { service.dispose(); await ctx.fiber.dispose(); server.closeAllConnections();
    await new Promise(resolve => server.close(resolve)); await store.close(); await rm(directory, { recursive: true, force: true }); });
  await ctx.plugin({ name: 'synthetic-pi', inject: ['llm'], apply: child => pi.apply(child, config) });
  const route = purpose => service.prepareRoute({ provider: 'synthetic', model }, purpose);
  const request = (sessionId = 'subject', purpose = 'conversation') => ({ provider: 'synthetic', model, sessionId, purpose,
    messages: [{ role: 'user', content: [{ type: 'text', text: 'SYNTHETIC_PRIVATE_RECORD' }] }] });
  const send = async options => { const chunks = []; for await (const chunk of ctx.llm.stream(options)) chunks.push(chunk); return chunks; };
  return { ctx, llm, pi, store, service, receipts, route, request, send, url,
    setMode: value => { mode = value; }, setReady: value => { readyFlag = value; }, setIdentity: value => { identity = value; },
    setStoreError: value => { storeError = value; },
    setNativePrompts: value => { host.nativeAccessPrompts = value; },
    setLocale: value => { locale = value; },
    setActive: value => { activeFlag = value; }, setProviders: value => { providers = value; }, config, raw };
}
function failed(chunks, code) {
  const last = chunks.at(-1);
  assert.equal(last.type, 'finish'); assert.equal(last.reason.kind, 'error');
  assert.ok(JSON.stringify(last).includes(code), JSON.stringify(last));
}

test('patches reject drift and repeated application', async () => {
  const source = await readFile(installed.resolve('@deepseek-ai/dsh-llm'), 'utf8');
  assert.throws(() => patchPrivacyLlm(source + '\n'), /input changed/);
  assert.throws(() => patchPrivacyLlm(patchPrivacyLlm(source)), /input changed/);
  const pi = await readFile(installed.resolve('@deepseek-ai/dsh-llm-pi-ai'), 'utf8');
  assert.throws(() => patchPrivacyPiAi(pi + '\n'), /input changed/);
});

test('missing mandatory execution protection never falls back to unguarded model dispatch', async t => {
  const f = await fixture(t, { active: false, execution: false });
  failed(await f.send(f.request('legacy')), 'DROY_REQUIRED_PROTECTION_UNAVAILABLE');
  assert.equal(f.receipts.length, 0);
});

test('only live product-restricted companions dispatch when legacy protection is active but unready', async t => {
  const f = await fixture(t, { active: true, ready: false, unavailableStore: true });
  const agent = { session: { id: 'companion-session' } };
  let restricted = true;
  f.ctx.provide('droyCompanions', {
    ownsSession: id => id === agent.session.id,
    isRestrictedAgent: candidate => restricted && candidate === agent,
    isRestrictedSession: id => restricted && id === agent.session.id,
  });
  await f.service.onAgentCreated({ agent, source: 'startup' });
  const chunks = await f.send(f.request(agent.session.id));
  assert.equal(chunks.at(-1).reason.kind, 'stop');
  assert.equal(f.receipts.length, 1);
  await assert.rejects(f.service.onAgentCreated({ agent: { session: { id: agent.session.id } }, source: 'startup' }), /PRIVACY_NOT_INITIALIZED/);
  failed(await f.send(f.request('legacy-work')), 'PRIVACY_NOT_INITIALIZED');
  // Disposal/unrestricted scopes and other model-facing privacy APIs must not
  // gain access merely because the product still owns the session identifier.
  await assert.rejects(f.service.authorizeExecution(agent), /PRIVACY_NOT_INITIALIZED/);
  await assert.rejects(f.service.getSourceSession(agent.session.id), /PRIVACY_EXECUTION_UNVERIFIED/);
  restricted = false;
  failed(await f.send(f.request(agent.session.id)), 'PRIVACY_NOT_INITIALIZED');
  assert.equal(f.receipts.length, 1);
});

test('companion dispatch leaves existing protected and forgotten work records gated', async t => {
  const f = await fixture(t);
  await f.store.registerCleanSession('private-work');
  await f.store.protectSession('private-work', { categories: ['memory'], sourceRefs: ['fixture-source'] });
  f.ctx.provide('droyCompanions', {
    ownsSession: id => id === 'companion-live',
    isRestrictedSession: id => id === 'companion-live',
  });
  f.setReady(false);
  assert.equal((await f.send(f.request('companion-live'))).at(-1).reason.kind, 'stop');
  failed(await f.send(f.request('private-work')), 'PRIVACY_EXECUTION_UNVERIFIED');
  await f.store.forgetSources(['fixture-source']);
  failed(await f.send(f.request('private-work')), 'PRIVACY_SESSION_FORGOTTEN');
  assert.equal(f.receipts.length, 1);
  assert.equal((await f.store.sessionState('private-work')).state, 'forgotten');
});

test('an activated but unready host exposes its diagnostic status without opening an uninitialized store', async t => {
  const f = await fixture(t, { active: true, ready: false, unavailableStore: true });
  assert.deepEqual(await f.service.status(), { active: true, ready: false, reason: 'PRIVACY_EXECUTION_UNVERIFIED' });
  const chinese = await f.send(f.request());
  failed(chinese, 'PRIVACY_NOT_INITIALIZED');
  assert.match(chinese.at(-1).reason.failure.message, /记忆服务正在初始化.*刷新/);
  f.setLocale('en');
  const english = (await f.send(f.request())).at(-1).reason.failure;
  assert.equal(english.code, 'PRIVACY_NOT_INITIALIZED'); assert.match(english.message, /Memory is initializing.*Refresh Memory settings/);
  assert.equal(f.receipts.length, 0);
});

test('early uninitialized lifecycle failure recovers after activation while integrity failure remains closed', async t => {
  const f = await fixture(t, { active: true, ready: false, unavailableStore: true });
  const agent = { session: { id: 'fresh-after-activation', header: { id: 'fresh-after-activation' }, snapshotEvents: () => [] } };
  await assert.rejects(f.service.onAgentCreated({ agent, source: 'startup' }), { code: 'PRIVACY_NOT_INITIALIZED' });
  f.setStoreError(undefined); f.setReady(true);
  await f.service.onAgentCreated({ agent, source: 'startup' });
  assert.equal((await f.store.sessionState(agent.session.id)).state, 'known-clean');
  assert.equal((await f.send(f.request(agent.session.id))).at(-1).reason.kind, 'stop');
  assert.equal(f.receipts.length, 1);

  const corrupt = await fixture(t, { active: true });
  corrupt.setStoreError('PRIVACY_CORRUPT');
  await assert.rejects(corrupt.service.status(), { code: 'PRIVACY_CORRUPT' });
  corrupt.setStoreError(undefined);
  failed(await corrupt.send(corrupt.request('unknown')), 'PRIVACY_CORRUPT');
  assert.equal(corrupt.receipts.length, 0);
});

test('real pi-ai SDK request is authorized before disclosure and final HTTP', { timeout: 30000 }, async t => {
  const f = await fixture(t);
  const route = await f.route('conversation');
  assert.deepEqual(f.service.describeRoute(route).url, f.url + '/chat/completions');
  await assert.rejects(f.service.beforeDisclosure({ sessionId: 'subject', categories: ['memory'], sourceRefs: ['memory-record:fictional'], route }), /NOT_AUTHORIZED/);
  assert.equal(f.receipts.length, 0);
  await f.service.grantEndpoint(route, { categories: ['memory'] });
  await f.service.beforeDisclosure({ sessionId: 'subject', categories: ['memory'], sourceRefs: ['memory-record:fictional'], route });
  const agent = { session: { id: 'subject', requestHeader: () => ({ config: { provider: 'synthetic', model: 'synthetic' } }) } };
  assert.deepEqual(await f.service.authorizeExecution(agent), { state: 'protected' });
  const chunks = await f.send(f.request());
  assert.equal(chunks.at(-1).reason.kind, 'stop');
  assert.deepEqual(f.receipts, [{ path: '/v1/chat/completions', private: true }]);
  await f.service.revokeEndpoint(route);
  await assert.rejects(f.service.authorizeExecution(agent), /NOT_AUTHORIZED/);
  failed(await f.send(f.request()), 'PRIVACY_ENDPOINT_NOT_AUTHORIZED');
  assert.equal(f.receipts.length, 1);
});

test('configured third-party Sol stays on Chat Completions without inferred controls', async t => {
  const f = await fixture(t, { api: 'openai-completions', model: 'gpt-6.1-sol' });
  assert.equal((await f.ctx.llm.resolveModelInfo('synthetic', 'gpt-6.1-sol')).reasoning, undefined);
  const route = await f.route();
  assert.equal(f.service.describeRoute(route).url, f.url + '/chat/completions');
  await f.service.grantEndpoint(route, { categories: ['memory'] });
  await f.store.protectSession('subject', { categories: ['memory'] });
  await f.service.beforeDisclosure({ sessionId: 'subject', categories: ['memory'], route });
  const chunks = await f.send({ ...f.request(), tools: [{ name: 'synthetic_echo', description: 'Synthetic local fixture', parameters: { type: 'object', properties: {} } }] });
  assert.equal(chunks.at(-1).reason.kind, 'stop', JSON.stringify(chunks.at(-1)));
  assert.deepEqual(f.receipts, [{ path: '/v1/chat/completions', private: true }]);
  assert.equal(f.raw.providers.synthetic.api, 'openai-completions');
});

test('Responses uses its exact granted HTTP endpoint and revocation blocks further private dispatch', async t => {
  const f = await fixture(t, { api: 'openai-responses' });
  const route = await f.route();
  assert.equal(f.service.describeRoute(route).url, f.url + '/responses');
  await f.service.grantEndpoint(route, { categories: ['memory'] });
  await f.service.beforeDisclosure({ sessionId: 'subject', categories: ['memory'], route });
  const chunks = await f.send(f.request());
  assert.equal(chunks.at(-1).reason.kind, 'stop', JSON.stringify(chunks.at(-1)));
  assert.ok(JSON.stringify(chunks).includes('Synthetic summary'));
  assert.deepEqual(f.receipts, [{ path: '/v1/responses', private: true }]);
  await assert.rejects(async () => {
    for await (const _ of f.service.wrapDispatch(f.request(), route, async function* (options) {
      await f.service.fetchFor(options)(f.url + '/chat/completions', { method: 'POST', body: 'SYNTHETIC_PRIVATE_RECORD' });
    })) { /* Consume the guarded iterator. */ }
  }, /PRIVACY_DESTINATION_MISMATCH/);
  assert.equal(f.receipts.length, 1);
  await f.service.revokeEndpoint(route);
  failed(await f.send(f.request()), 'PRIVACY_ENDPOINT_NOT_AUTHORIZED');
  assert.equal(f.receipts.length, 1);
});

test('Responses grants stay separate from Chat Completions and unreviewed transports stay closed', async t => {
  const f = await fixture(t, { api: 'openai-responses' });
  const chat = f.service.capturePiRoute({ provider: 'synthetic', model: 'synthetic', profile: {},
    resolvedModel: { api: 'openai-completions', provider: 'synthetic', baseUrl: f.url } });
  await f.service.grantEndpoint(chat, { categories: ['memory'] });
  await f.store.protectSession('subject', { categories: ['memory'] });
  failed(await f.send(f.request()), 'PRIVACY_ENDPOINT_NOT_AUTHORIZED');
  assert.equal(f.receipts.length, 0);
  for (const transport of ['websocket', 'ws', 'auto']) {
    assert.equal(f.service.capturePiRoute({ provider: 'synthetic', model: 'synthetic', profile: { transport },
      resolvedModel: { api: 'openai-responses', provider: 'synthetic', baseUrl: f.url } }), undefined);
  }
  const sse = f.service.capturePiRoute({ provider: 'synthetic', model: 'synthetic', profile: { transport: 'sse' },
    resolvedModel: { api: 'openai-responses', provider: 'synthetic', baseUrl: f.url } });
  assert.equal(f.service.describeRoute(sse).url, f.url + '/responses');
});

test('unknown, anonymous, forged and forgotten inputs never reach HTTP', async t => {
  const f = await fixture(t);
  failed(await f.send(f.request('unknown')), 'PRIVACY_SESSION_UNKNOWN');
  failed(await f.send({ ...f.request(), sessionId: undefined }), 'PRIVACY_INVALID_SESSION');
  await assert.rejects(f.service.beforeDisclosure({ sessionId: 'subject', categories: ['memory'], route: {} }), /UNREVIEWED_ROUTE/);
  const route = await f.route(); await f.service.grantEndpoint(route, { categories: ['memory'] });
  await f.service.beforeDisclosure({ sessionId: 'subject', categories: ['memory'], sourceRefs: ['memory-record:fictional'], route });
  await f.service.forgetSources(['memory-record:fictional']);
  failed(await f.send(f.request()), 'PRIVACY_SESSION_FORGOTTEN');
  assert.equal(f.receipts.length, 0);
});

test('actual DSH error chunks preserve privacy codes and follow live Chinese/English locale', async t => {
  const f = await fixture(t);
  const expect = async (sessionId, code, chinese, english) => {
    for (const [locale, message] of [['zh-CN', chinese], ['en', english]]) {
      f.setLocale(locale);
      const chunks = await f.send(f.request(sessionId));
      assert.equal(chunks.at(-1).reason.kind, 'error');
      assert.equal(chunks.at(-1).reason.failure.code, code);
      assert.match(chunks.at(-1).reason.failure.message, message);
    }
  };
  await expect('unknown', 'PRIVACY_SESSION_UNKNOWN', /新建对话/, /Start a new conversation/);
  const route = await f.route();
  await assert.rejects(f.service.beforeDisclosure({ sessionId: 'subject', categories: ['memory'], sourceRefs: ['synthetic-source'], route }),
    error => error.code === 'PRIVACY_ENDPOINT_NOT_AUTHORIZED' && error.message === error.code);
  await expect('subject', 'PRIVACY_ENDPOINT_NOT_AUTHORIZED', /记忆访问请求/, /memory access request/);
  await f.service.grantEndpoint(route, { categories: ['memory'] });
  f.setReady(false);
  await expect('subject', 'PRIVACY_EXECUTION_UNVERIFIED', /记忆服务尚未就绪.*刷新/, /Memory is not ready.*Refresh Memory settings/);
  f.setReady(true); await f.service.forgetSources(['synthetic-source']);
  await expect('subject', 'PRIVACY_SESSION_FORGOTTEN', /新建对话/, /forgotten information.*new conversation/);
  f.setLocale(undefined);
  assert.match((await f.send(f.request('unknown'))).at(-1).reason.failure.message, /Start a new conversation/);
  assert.equal(f.receipts.length, 0);
});

test('inactive mode preserves ordinary DSH and first disclosure persists activation', async t => {
  const f = await fixture(t, { active: false });
  assert.equal((await f.send(f.request('legacy'))).at(-1).reason.kind, 'stop');
  const route = await f.route(); await f.service.grantEndpoint(route, { categories: ['memory'] });
  assert.equal((await f.service.status()).active, false);
  await f.service.beforeDisclosure({ sessionId: 'subject', categories: ['memory'], route });
  assert.equal((await f.service.status()).active, true);
  failed(await f.send(f.request('legacy')), 'PRIVACY_SESSION_UNKNOWN');
  f.setActive(false);
  await assert.rejects(f.service.status(), /ACTIVATION_DOWNGRADE/);
});

test('output metadata and synchronous invalidation keep side-channel delivery behind session protection', async t => {
  const f = await fixture(t, { active: false });
  assert.deepEqual(await f.service.outputState(), { active: false, state: 'inactive' });
  f.setActive(true);
  assert.deepEqual(await f.service.outputState(), { active: true, state: 'unknown' });
  await f.store.registerCleanSession('subject');
  assert.deepEqual(await f.service.outputState('subject'), { active: true, state: 'known-clean' });
  const events = [];
  const unsubscribe = f.service.onInvalidated(event => events.push({ event, priorState: f.store.sessionState('subject') }));
  const route = await f.route(); await f.service.grantEndpoint(route, { categories: ['memory'] });
  await f.service.beforeDisclosure({ sessionId: 'subject', categories: ['memory'], sourceRefs: ['synthetic-source'], route });
  assert.deepEqual(events[0].event, { sessionId: 'subject' });
  assert.equal((await events[0].priorState).state, 'known-clean', 'notification precedes the protection write');
  assert.deepEqual(await f.service.outputState('subject'), { active: true, state: 'protected' });
  await f.service.revokeEndpoint(route); await f.service.forgetSources(['synthetic-source']); f.service.invalidate();
  assert.deepEqual(events.slice(1).map(item => item.event), [{}, {}, {}]);
  assert.deepEqual(await f.service.outputState('subject'), { active: true, state: 'forgotten' });
  await Promise.all(events.map(item => item.priorState));
  unsubscribe(); f.service.dispose(); assert.equal(events.length, 4);
  const unavailable = await fixture(t, { ready: false, unavailableStore: true });
  await assert.rejects(unavailable.service.outputState('subject'), { code: 'PRIVACY_NOT_INITIALIZED' });
});

test('output metadata never reports clean during a pending or concurrently completed protection write', async t => {
  const f = await fixture(t); await f.store.registerCleanSession('subject');
  const route = await f.route(); await f.service.grantEndpoint(route, { categories: ['memory'] });
  const protect = f.store.protectSession.bind(f.store);
  let release, announced;
  const waiting = new Promise(resolve => { announced = resolve; });
  const gate = new Promise(resolve => { release = resolve; });
  f.store.protectSession = async (...args) => { announced(); await gate; return protect(...args); };
  const disclosure = f.service.beforeDisclosure({ sessionId: 'subject', categories: ['memory'], route });
  await waiting;
  assert.equal((await f.store.sessionState('subject')).state, 'known-clean');
  assert.equal((await f.service.outputState('subject')).state, 'protected');
  release(); await disclosure;
  assert.equal((await f.service.outputState('subject')).state, 'protected');

  await f.store.registerCleanSession('race');
  const read = f.store.sessionState.bind(f.store);
  let releaseRead, readStarted;
  const readReady = new Promise(resolve => { readStarted = resolve; });
  const readGate = new Promise(resolve => { releaseRead = resolve; });
  f.store.sessionState = async id => { const result = await read(id); if (id === 'race') { readStarted(); await readGate; } return result; };
  const output = f.service.outputState('race'); await readReady;
  await f.service.beforeDisclosure({ sessionId: 'race', categories: ['memory'], route });
  releaseRead(); assert.equal((await output).state, 'unknown', 'a stale clean read cannot outlive protection promotion');
});

test('overlapping protection writes retain the output fence when one write fails', async t => {
  const f = await fixture(t); await f.store.registerCleanSession('subject');
  const route = await f.route(); await f.service.grantEndpoint(route, { categories: ['memory'] });
  const protect = f.store.protectSession.bind(f.store); const releases = [];
  let started;
  const bothStarted = new Promise(resolve => { started = resolve; });
  f.store.protectSession = async (...args) => {
    const index = releases.length;
    await new Promise(resolve => { releases.push(resolve); if (releases.length === 2) started(); });
    if (!index) throw new Error('SYNTHETIC_WRITE_REJECTED');
    return protect(...args);
  };
  const first = f.service.beforeDisclosure({ sessionId: 'subject', categories: ['memory'], route });
  const rejected = assert.rejects(first, /SYNTHETIC_WRITE_REJECTED/);
  const second = f.service.beforeDisclosure({ sessionId: 'subject', categories: ['memory'], route });
  await bothStarted; releases[0](); await rejected;
  assert.equal((await f.store.sessionState('subject')).state, 'known-clean');
  assert.equal((await f.service.outputState('subject')).state, 'protected');
  releases[1](); await second;
  assert.equal((await f.service.outputState('subject')).state, 'protected');
});

test('loss of execution readiness blocks private processing without restricting known-clean requests', async t => {
  const f = await fixture(t);
  await f.store.registerCleanSession('clean');
  const route = await f.route(); await f.service.grantEndpoint(route, { categories: ['memory'] });
  await f.service.beforeDisclosure({ sessionId: 'subject', categories: ['memory'], route });
  f.setReady(false);
  failed(await f.send(f.request()), 'PRIVACY_EXECUTION_UNVERIFIED');
  assert.equal((await f.send(f.request('clean'))).at(-1).reason.kind, 'stop');
  assert.equal(f.receipts.length, 1);
});

test('real title executor and compaction summarizer enforce separate purpose grants', { timeout: 30000 }, async t => {
  const f = await fixture(t);
  const [{ executeSessionTitleLlm }, { Session }, { BasicCompactionEngine }] = await Promise.all([
    load('@deepseek-ai/dsh-session-title-llm'), load('@deepseek-ai/dsh-session'), load('@deepseek-ai/dsh-compaction-basic')]);
  const route = await f.route(); await f.service.grantEndpoint(route, { categories: ['memory'] });
  await f.service.beforeDisclosure({ sessionId: 'subject', categories: ['memory'], route });
  const session = Session.create('subject');
  const title = () => executeSessionTitleLlm(f.ctx, { provider: 'synthetic', model: 'synthetic', maxInputBytes: 8192, maxOutputTokens: 256, timeoutMs: 5000 },
    { session, signal: new AbortController().signal }, 'synthetic-title', { system: 'Create a title.', input: 'SYNTHETIC_PRIVATE_RECORD', messageSeqs: [0], selectReasoningEffort: () => undefined });
  await assert.rejects(title(), error => error.code === 'PRIVACY_ENDPOINT_NOT_AUTHORIZED' && /记忆访问请求/.test(error.message));
  assert.equal(f.receipts.length, 0);
  await f.service.grantEndpoint(await f.route('session-title'), { categories: ['memory'] });
  assert.equal((await title()).finish.kind, 'stop');
  const config = { ...BasicCompactionEngine.Config({ maxTokens: 256 }), summarizationProvider: '', summarizationModel: '', modelPolicies: [] };
  const owner = { ctx: f.ctx, config };
  const agent = { options: { provider: 'synthetic', model: 'synthetic' }, session };
  const compact = () => BasicCompactionEngine.prototype.summarize.call(owner,
    { messages: f.request().messages }, agent, new AbortController().signal);
  await assert.rejects(compact(), error => error.code === 'PRIVACY_ENDPOINT_NOT_AUTHORIZED' && /记忆访问请求/.test(error.message));
  assert.equal(f.receipts.length, 1);
  await f.service.grantEndpoint(await f.route('compaction'), { categories: ['memory'] });
  assert.equal((await compact()).llmStreamCall, true);
  assert.equal(f.receipts.length, 2);
});

test('new session proof and fork inheritance are durable while unknown resume stays unknown', async t => {
  const f = await fixture(t);
  const { Session } = await load('@deepseek-ai/dsh-session');
  const fresh = Session.create('fresh');
  await f.service.onAgentCreated({ agent: { session: fresh }, source: 'startup' });
  assert.equal((await f.store.sessionState('fresh')).state, 'known-clean');
  const restored = Session.create('restored');
  await f.service.onAgentCreated({ agent: { session: restored }, source: 'resume' });
  assert.equal((await f.store.sessionState('restored')).state, 'unknown');
  await f.store.protectSession('fresh', { categories: ['memory'], sourceRefs: ['memory-record:fictional'] });
  const child = Session.create('child', undefined, { ...fresh.header, id: 'child', parentSession: 'fresh' });
  await f.service.onAgentCreated({ agent: { session: child }, source: 'startup' });
  assert.equal((await f.store.sessionState('child')).state, 'protected');
});

test('a conversation created before first activation keeps running and still requires memory endpoint consent', async t => {
  const f = await fixture(t, { active: false, ready: false, unavailableStore: true });
  const plugins = await Promise.all(['@deepseek-ai/dsh-session', '@deepseek-ai/dsh-session-projection',
    '@deepseek-ai/dsh-system-prompt', '@deepseek-ai/dsh-tools', '@deepseek-ai/dsh-agent', '@deepseek-ai/dsh-agent-loop'].map(load));
  for (let index = 0; index < plugins.length; index++) await f.ctx.plugin(plugins[index].default,
    index === plugins.length - 1 ? { agents: [] } : undefined);
  f.ctx.on('agent/created', payload => f.service.onAgentCreated(payload));
  const handle = await f.ctx.agents.create({ sessionId: 'before-memory', agentOptions: { provider: 'synthetic', model: 'synthetic' } });
  const agent = handle.agent;
  const sendMessage = async () => {
    agent.followup(f.llm.createUserMessage({ content: [{ type: 'text', text: 'SYNTHETIC_PRIVATE_RECORD' }], source: { kind: 'user' } }));
    await agent.whenIdle();
  };
  await sendMessage();
  assert.equal(f.receipts.length, 1);
  assert.ok(agent.session.snapshotEvents().length > 0);
  assert.equal((await f.store.sessionState(agent.id)).state, 'unknown');
  f.setStoreError(undefined); f.setReady(true); f.setActive(true);
  assert.deepEqual(await f.service.authorizeExecution(agent), { state: 'known-clean' });
  await sendMessage();
  assert.equal(f.receipts.length, 2);
  const route = await f.service.routeForAgent(agent);
  await assert.rejects(f.service.beforeDisclosure({ sessionId: agent.id, categories: ['memory'],
    sourceRefs: ['memory-record:first-activation'], route }), { code: 'PRIVACY_ENDPOINT_NOT_AUTHORIZED' });
  assert.equal(f.receipts.length, 2);
  await assert.rejects(f.service.authorizeExecution(agent), { code: 'PRIVACY_ENDPOINT_NOT_AUTHORIZED' });
  await f.service.grantEndpoint(route, { categories: ['memory'] });
  await f.service.beforeDisclosure({ sessionId: agent.id, categories: ['memory'], sourceRefs: ['memory-record:first-activation'], route });
  assert.deepEqual(await f.service.authorizeExecution(agent), { state: 'protected' });
  await sendMessage();
  assert.equal(f.receipts.length, 3);
  await f.service.revokeEndpoint(route);
  failed(await f.send(f.request(agent.id)), 'PRIVACY_ENDPOINT_NOT_AUTHORIZED');
  assert.equal(f.receipts.length, 3);
  await handle.dispose();
});

test('pre-activation provenance preserves forks and never cleans imported, protected or forgotten history', async t => {
  const f = await fixture(t, { active: false, ready: false, unavailableStore: true });
  const { Session } = await load('@deepseek-ai/dsh-session');
  const created = async (id, meta = {}, source = 'startup') => {
    const session = Session.create(id, meta.isSeeded ? [] : undefined,
      { ...Session.create(id).header, ...meta }, meta.isSeeded ? 0 : undefined);
    await f.service.onAgentCreated({ agent: { session }, source });
    return session;
  };
  await created('fresh-before-memory');
  await created('child-before-memory', { parentSession: 'fresh-before-memory', isSeeded: true });
  await created('grandchild-before-memory', { parentSession: 'child-before-memory', isSeeded: true });
  await created('imported-before-memory', { isSeeded: true });
  await created('resumed-before-memory', {}, 'resume');
  await created('unknown-parent-child', { parentSession: 'legacy-unknown', isSeeded: true });
  await created('previously-protected');
  await created('previously-forgotten');
  await f.store.protectSession('previously-protected', { categories: ['memory'], sourceRefs: ['prior-protected'] });
  await f.store.protectSession('previously-forgotten', { categories: ['memory'], sourceRefs: ['prior-forgotten'] });
  await f.store.forgetSources(['prior-forgotten']);
  await created('protected-child', { parentSession: 'previously-protected', isSeeded: true });
  await created('forgotten-child', { parentSession: 'previously-forgotten', isSeeded: true });
  f.setStoreError(undefined); f.setReady(true); f.setActive(true);
  await Promise.all([f.service.status(), f.service.status(), f.service.authorizeExecution({ session: { id: 'grandchild-before-memory' } })]);
  for (const id of ['fresh-before-memory', 'child-before-memory', 'grandchild-before-memory']) {
    assert.equal((await f.store.sessionState(id)).state, 'known-clean', id);
  }
  for (const id of ['imported-before-memory', 'resumed-before-memory', 'unknown-parent-child', 'legacy-unknown']) {
    assert.equal((await f.store.sessionState(id)).state, 'unknown', id);
    failed(await f.send(f.request(id)), 'PRIVACY_SESSION_UNKNOWN');
  }
  for (const id of ['previously-protected', 'protected-child']) assert.equal((await f.store.sessionState(id)).state, 'protected', id);
  for (const id of ['previously-forgotten', 'forgotten-child']) assert.equal((await f.store.sessionState(id)).state, 'forgotten', id);
  assert.equal(f.receipts.length, 0);
});

test('first activation preserves a real resumed local conversation while seeded history remains unknown', async t => {
  const first = await fixture(t, { active: false, ready: false, unavailableStore: true });
  const resumed = await fixture(t, { active: false, ready: false, unavailableStore: true });
  const history = await mkdtemp(join(tmpdir(), 'droy-before-memory-resume-'));
  t.after(() => rm(history, { recursive: true, force: true }));
  const [Jsonl, ...plugins] = await Promise.all(['@deepseek-ai/dsh-session-persistence-jsonl', '@deepseek-ai/dsh-session',
    '@deepseek-ai/dsh-session-projection', '@deepseek-ai/dsh-system-prompt', '@deepseek-ai/dsh-tools',
    '@deepseek-ai/dsh-agent', '@deepseek-ai/dsh-agent-loop'].map(load));
  const mount = async f => {
    for (let index = 0; index < plugins.length; index++) await f.ctx.plugin(plugins[index].default,
      index === plugins.length - 1 ? { agents: [] } : undefined);
    await f.ctx.plugin(Jsonl, { root: history, compression: 'none' });
    f.ctx.on('agent/created', payload => f.service.onAgentCreated(payload));
  };
  const chat = async (f, agent) => {
    agent.followup(f.llm.createUserMessage({ content: [{ type: 'text', text: 'SYNTHETIC_PRIVATE_RECORD' }], source: { kind: 'user' } }));
    await agent.whenIdle();
  };
  const agentOptions = { provider: 'synthetic', model: 'synthetic' };
  await mount(first);
  const original = await first.ctx.agents.create({ sessionId: 'ordinary-local-history', agentOptions });
  await chat(first, original.agent);
  assert.equal(first.receipts.length, 1);
  const imported = await first.ctx.agents.create({ sessionId: 'imported-seed-history', agentOptions,
    seed: [], meta: { isSeeded: true }, inheritedEventCount: 0 });
  await first.ctx.sessionPersistence.flush();
  await imported.dispose(); await original.dispose(); first.service.dispose();

  await mount(resumed);
  const sources = [];
  resumed.ctx.on('agent/created', ({ source }) => { sources.push(source); });
  const restored = await resumed.ctx.agents.resume({ resumeSessionId: 'ordinary-local-history', agentOptions });
  const restoredImport = await resumed.ctx.agents.resume({ resumeSessionId: 'imported-seed-history', agentOptions });
  assert.deepEqual(sources, ['resume', 'resume']);
  assert.equal(restored.agent.session.header.isSeeded, false);
  assert.ok(restored.agent.session.snapshotEvents().length > 0);
  resumed.setStoreError(undefined); resumed.setReady(true); resumed.setActive(true);
  assert.deepEqual(await resumed.service.authorizeExecution(restored.agent), { state: 'known-clean' });
  await chat(resumed, restored.agent);
  assert.equal(resumed.receipts.length, 1);
  failed(await resumed.send(resumed.request(restoredImport.agent.id)), 'PRIVACY_SESSION_UNKNOWN');
  assert.equal(resumed.receipts.length, 1);
  const route = await resumed.service.routeForAgent(restored.agent);
  await assert.rejects(resumed.service.beforeDisclosure({ sessionId: restored.agent.id, categories: ['memory'], route }),
    { code: 'PRIVACY_ENDPOINT_NOT_AUTHORIZED' });
  await resumed.service.grantEndpoint(route, { categories: ['memory'] });
  assert.deepEqual(await resumed.service.authorizeExecution(restored.agent), { state: 'protected' });
  await chat(resumed, restored.agent);
  assert.equal(resumed.receipts.length, 2);
  await restoredImport.dispose(); await restored.dispose();
});

test('route metadata is immutable, config/provider changes are distinct and proxy identity invalidates old capability', async t => {
  const f = await fixture(t);
  const route = await f.route();
  assert.equal(Object.isFrozen(route), true);
  const other = f.service.capturePiRoute({ provider: 'synthetic', model: 'synthetic', profile: {},
    resolvedModel: { api: 'openai-completions', provider: 'synthetic', baseUrl: f.url + '/other' } });
  assert.notEqual(f.service.describeRoute(route).configVersion, f.service.describeRoute(other).configVersion);
  f.setIdentity('synthetic-proxy-changed');
  assert.throws(() => f.service.describeRoute(route), /ROUTING_CHANGED/);
});

test('unreviewed protocol cannot issue a private route', async t => {
  const f = await fixture(t);
  assert.equal(f.service.capturePiRoute({ provider: 'x', model: 'x', profile: {}, resolvedModel: { api: 'anthropic-messages' } }), undefined);
  class RawAdapter extends f.llm.LlmAdapter { async *stream() { throw new Error('must not execute'); } }
  f.ctx.llm.registerAdapter(['unreviewed'], new RawAdapter());
  await f.store.protectSession('subject', { categories: ['memory'] });
  failed(await f.send({ ...f.request(), provider: 'unreviewed' }), 'PRIVACY_UNREVIEWED_ROUTE');
  assert.equal(f.receipts.length, 0);
});

test('actual pi-ai prepared snapshot survives settings change but a new route needs a new grant', async t => {
  const f = await fixture(t);
  const route = await f.route(); await f.service.grantEndpoint(route, { categories: ['memory'] });
  await f.service.beforeDisclosure({ sessionId: 'subject', categories: ['memory'], route });
  const prepared = await f.ctx.llm.prepareCall({ provider: 'synthetic', model: 'synthetic' });
  assert.ok(prepared[PREPARED_ROUTE]);
  const next = structuredClone(f.raw.providers); next.synthetic.baseURL = f.url + '/replacement';
  // Volatile.get is the public config input consumed by the real plugin.
  f.setProviders(next);
  let last; for await (const chunk of prepared.stream({ ...f.request(), ...prepared.config })) last = chunk;
  assert.equal(last.reason.kind, 'stop');
  assert.equal(f.receipts.at(-1).path, '/v1/chat/completions');
  failed(await f.send(f.request()), 'PRIVACY_ENDPOINT_NOT_AUTHORIZED');
  assert.equal(f.receipts.length, 1);
  const changedRoute = await f.route();
  await f.service.grantEndpoint(changedRoute, { categories: ['memory'] });
  assert.equal((await f.send(f.request())).at(-1).reason.kind, 'stop');
  assert.equal(f.receipts.at(-1).path, '/v1/replacement/chat/completions');
});

test('request-scoped transport refuses redirects and unexpected actual destinations', { timeout: 20000 }, async t => {
  const f = await fixture(t);
  const route = await f.route(); await f.service.grantEndpoint(route, { categories: ['memory'] });
  await f.service.beforeDisclosure({ sessionId: 'subject', categories: ['memory'], route });
  await assert.rejects(async () => {
    for await (const _ of f.service.wrapDispatch(f.request(), route, async function* (options) {
      await f.service.fetchFor(options)(f.url + '/unapproved', { method: 'POST', body: 'SYNTHETIC_PRIVATE_RECORD' });
    })) { /* Consume the guarded iterator. */ }
  }, /PRIVACY_DESTINATION_MISMATCH/);
  assert.equal(f.receipts.length, 0);
  f.setMode('redirect');
  const chunks = await f.send(f.request());
  assert.equal(chunks.at(-1).reason.kind, 'error');
  assert.ok(f.receipts.length > 0);
  assert.ok(f.receipts.every(receipt => receipt.path === '/v1/chat/completions'));
});

test('revoking a grant aborts an actual in-flight pi-ai response stream', { timeout: 15000 }, async t => {
  const f = await fixture(t);
  const route = await f.route(); await f.service.grantEndpoint(route, { categories: ['memory'] });
  await f.service.beforeDisclosure({ sessionId: 'subject', categories: ['memory'], route });
  f.setMode('hold');
  const iterator = f.ctx.llm.stream(f.request())[Symbol.asyncIterator]();
  let foundText = false;
  for (let index = 0; index < 10; index++) {
    const row = await iterator.next();
    if (JSON.stringify(row).includes('Synthetic summary')) { foundText = true; break; }
    if (row.done) break;
  }
  assert.equal(foundText, true);
  await f.service.revokeEndpoint(route);
  const remainder = [];
  for (let index = 0; index < 10; index++) {
    const row = await iterator.next(); if (row.done) break; remainder.push(row.value);
  }
  assert.equal(remainder.at(-1).type, 'finish');
  assert.notEqual(remainder.at(-1).reason.kind, 'stop');
  assert.equal(f.receipts.length, 1);
});

test('real DSH agent creation awaits protection and its conversation reaches the guarded adapter', { timeout: 15000 }, async t => {
  const f = await fixture(t);
  const plugins = await Promise.all(['@deepseek-ai/dsh-session', '@deepseek-ai/dsh-session-projection',
    '@deepseek-ai/dsh-system-prompt', '@deepseek-ai/dsh-tools', '@deepseek-ai/dsh-agent', '@deepseek-ai/dsh-agent-loop'].map(load));
  for (let index = 0; index < plugins.length; index++) await f.ctx.plugin(plugins[index].default,
    index === plugins.length - 1 ? { agents: [] } : undefined);
  f.ctx.on('agent/created', payload => f.service.onAgentCreated(payload));
  let observedBeforePublication = false;
  f.ctx.on('agent/created', async ({ agent }) => {
    observedBeforePublication = (await f.store.sessionState(agent.id)).state === 'known-clean';
  });
  const handle = await f.ctx.agents.create({ sessionId: 'live', agentOptions: { provider: 'synthetic', model: 'synthetic' } });
  const agent = handle.agent;
  assert.equal(observedBeforePublication, true);
  const route = await f.service.routeForAgent(agent);
  await f.service.grantEndpoint(route, { categories: ['memory'] });
  await f.service.beforeDisclosure({ sessionId: agent.id, categories: ['memory'], sourceRefs: ['memory-record:live'], route });
  agent.followup(f.llm.createUserMessage({ content: [{ type: 'text', text: 'SYNTHETIC_PRIVATE_RECORD' }], source: { kind: 'user' } }));
  await agent.whenIdle();
  assert.equal(f.receipts.length, 1);
  assert.equal(f.receipts[0].private, true);
  await handle.dispose();
});

async function waitForAccess(service) {
  for (let i = 0; i < 100; i++) {
    const pending = service.pendingEndpointAccess();
    if (pending.length) return pending[0];
    await new Promise(resolve => setTimeout(resolve, 5));
  }
  assert.fail('Missing endpoint authorization request');
}
test('native first use waits for explicit endpoint/category consent then resumes without a second model request', async t => {
  const f = await fixture(t); f.setNativePrompts(true);
  const route = await f.route('conversation');
  const pending = f.service.beforeDisclosure({ sessionId: 'first-use', categories: ['memory', 'location'], sourceRefs: ['memory:fixture'], route });
  const request = await waitForAccess(f.service);
  assert.equal(request.url, f.url + '/chat/completions');
  assert.equal(request.purpose, 'conversation');
  assert.deepEqual([...request.categories].sort(), ['location', 'memory']);
  assert.equal(request.configVersion.length, 64); assert.equal(f.receipts.length, 0);
  assert.equal((await f.store.listGrants()).length, 0);
  f.service.resolveEndpointAccess(request.id, true); await pending;
  assert.equal(f.service.pendingEndpointAccess().length, 0);
  assert.equal((await f.store.listGrants()).length, 1);
  const result = await f.send(f.request('first-use'));
  assert.equal(result.at(-1).reason.kind, 'stop'); assert.equal(f.receipts.length, 1);
});
test('declining, cancelling and changed routing never create a grant or expose private data', async t => {
  for (const action of ['decline', 'cancel', 'routing']) {
    const f = await fixture(t); f.setNativePrompts(true);
    const route = await f.route('conversation'); const controller = new AbortController();
    const pending = f.service.beforeDisclosure({ sessionId: action, categories: ['memory'], sourceRefs: ['memory:fixture'], route, signal: controller.signal });
    const outcome = assert.rejects(pending);
    const request = await waitForAccess(f.service);
    if (action === 'cancel') controller.abort();
    else { if (action === 'routing') f.setIdentity('changed'); f.service.resolveEndpointAccess(request.id, action === 'routing'); }
    await outcome;
    assert.equal(f.service.pendingEndpointAccess().length, 0);
    assert.equal((await f.store.listGrants()).length, 0); assert.equal(f.receipts.length, 0);
    assert.throws(() => f.service.resolveEndpointAccess(request.id, true), /EXPIRED/);
  }
});

test('revocation or changing the protected sources while a prompt is open invalidates approval', async t => {
  for (const action of ['revoke', 'source']) {
    const f = await fixture(t); f.setNativePrompts(true);
    const route = await f.route('conversation');
    const pending = f.service.beforeDisclosure({ sessionId: 'changed', categories: ['memory'], sourceRefs: ['memory:fixture'], route });
    const outcome = assert.rejects(pending);
    const request = await waitForAccess(f.service);
    if (action === 'revoke') await f.service.revokeEndpoint(route);
    else {
      await f.store.protectSession('changed', { categories: ['location'], sourceRefs: ['location:new'] });
      f.service.resolveEndpointAccess(request.id, true);
    }
    await outcome;
    assert.equal((await f.store.listGrants()).length, 0);
    assert.equal(f.receipts.length, 0);
  }
});

test('granting a new category preserves existing consent for the same endpoint and purpose', async t => {
  const f = await fixture(t); f.setNativePrompts(true);
  const route = await f.route('conversation');
  await f.service.grantEndpoint(route, { categories: ['memory'] });
  const pending = f.service.beforeDisclosure({ sessionId: 'new-category', categories: ['location'], sourceRefs: ['location:fixture'], route });
  const request = await waitForAccess(f.service);
  assert.deepEqual(request.categories, ['location']);
  f.service.resolveEndpointAccess(request.id, true); await pending;
  assert.deepEqual((await f.store.listGrants())[0].categories, ['location', 'memory']);
});
