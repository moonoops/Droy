import test, { after } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, mkdir, readFile, readdir, rm, symlink, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { createRequire } from 'node:module';
import { createServer } from 'node:http';
import { PrivacyStore } from '../../privacy/store.mjs';
import { MemoryStore } from '../../memory/store.mjs';
import { createMemoryMaintenance } from '../../memory/maintenance.mjs';
import { createDshPrivacyService } from '../../privacy/dsh-plugin.mjs';
import { patchPrivacyLlm, patchPrivacyPiAi } from '../../privacy/upstream-patch.mjs';
import { createDshMemoryExtraction } from '../memory-extraction.mjs';

const installed = createRequire(new URL('../../dsh/package.json', import.meta.url));
const modules = fileURLToPath(new URL('../../dsh/node_modules/', import.meta.url));
const load = name => import(pathToFileURL(installed.resolve(name)).href);
let patchedRoot;
const patched = (async () => {
  patchedRoot = await mkdtemp(join(tmpdir(), 'droy-extraction-dsh-'));
  const target = join(patchedRoot, 'node_modules'); await mkdir(join(target, '@deepseek-ai'), { recursive: true });
  for (const name of await readdir(modules)) if (name !== '@deepseek-ai') await symlink(join(modules, name), join(target, name));
  for (const name of await readdir(join(modules, '@deepseek-ai'))) {
    const source = join(modules, '@deepseek-ai', name), destination = join(target, '@deepseek-ai', name);
    if (!['dsh-llm', 'dsh-llm-pi-ai'].includes(name)) { await symlink(source, destination); continue; }
    await mkdir(join(destination, 'lib'), { recursive: true });
    await writeFile(join(destination, 'package.json'), await readFile(join(source, 'package.json')));
    const js = await readFile(join(source, 'lib/index.js'), 'utf8');
    await writeFile(join(destination, 'lib/index.js'), name === 'dsh-llm' ? patchPrivacyLlm(js) : patchPrivacyPiAi(js));
  }
  return { llm: await import(pathToFileURL(join(target, '@deepseek-ai/dsh-llm/lib/index.js')).href),
    pi: await import(pathToFileURL(join(target, '@deepseek-ai/dsh-llm-pi-ai/lib/index.js')).href) };
})();
after(async () => { await patched.catch(() => {}); if (patchedRoot) await rm(patchedRoot, { recursive: true, force: true }); });
const tick = () => new Promise(resolve => setImmediate(resolve));
async function within(promise, label, timeoutMs = 5000) {
  let timer;
  try { return await Promise.race([promise, new Promise((_, reject) => {
    timer = setTimeout(() => reject(new Error(`${label}: timed out after ${timeoutMs}ms`)), timeoutMs);
  })]); } finally { clearTimeout(timer); }
}
async function fixture(t, { executionInstalled = true } = {}) {
  const [{ llm, pi }, { Context }, { default: Sessions }, Jsonl] = await Promise.all([patched, load('@deepseek-ai/cordis'), load('@deepseek-ai/dsh-session'), load('@deepseek-ai/dsh-session-persistence-jsonl')]);
  const dir = await mkdtemp(join(tmpdir(), 'droy-extraction-'));
  const ctx = new Context(); await ctx.plugin(llm.default); await ctx.plugin(Sessions); await ctx.plugin(Jsonl, { root: join(dir, 'sessions'), compression: 'none' });
  let locale = 'zh'; ctx.provide('settings', { describe: () => [{ ns: 'locale', value: { preference: locale } }] });
  const memory = await MemoryStore.open({ directory: join(dir, 'memory'), initialize: true, minFreeBytes: 0 });
  const privateStore = await PrivacyStore.open({ directory: join(dir, 'privacy'), markerPath: join(dir, 'marker'), initialize: true });
  const privacy = createDshPrivacyService({ ctx, store: privateStore, host: { status: async () => ({ active: true, ready: true }), routingIdentity: () => 'synthetic' } });
  ctx.provide('droyPrivacy', privacy); ctx.provide('droyExecution', { installed: () => executionInstalled, beforeDisclosure: async () => {} });
  ctx.provide('credentials', { resolve: async () => ({ value: 'synthetic' }) });
  const sent = []; let requestReceived; const requestSeen = new Promise(resolve => { requestReceived = resolve; }); let response = JSON.stringify({ candidates: [{ kind: 'preference', text: '合成用户可能喜欢步行。' }] }), hold = false;
  const server = createServer(async (req, res) => {
    let body = ''; for await (const chunk of req) body += chunk;
    sent.push(JSON.parse(body)); requestReceived(); res.writeHead(200, { 'content-type': 'text/event-stream' });
    res.write('data: ' + JSON.stringify({ id: 'synthetic', object: 'chat.completion.chunk', created: 0, model: 'synthetic', choices: [{ index: 0, delta: { role: 'assistant', content: response }, finish_reason: null }] }) + '\n\n');
    if (!hold) res.end('data: ' + JSON.stringify({ id: 'synthetic', object: 'chat.completion.chunk', created: 0, model: 'synthetic', choices: [{ index: 0, delta: {}, finish_reason: 'stop' }] }) + '\n\ndata: [DONE]\n\n');
  });
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  const config = pi.Config({ providers: { synthetic: { api: 'openai-completions', baseURL: `http://127.0.0.1:${server.address().port}/v1`,
    apiKeyEnv: 'SYNTHETIC_TEST_KEY', models: [{ id: 'synthetic', input: ['text'], contextWindow: 32768, maxTokens: 4096 }], retryPolicy: { mode: 'normal', maxRetries: 0 } } } });
  await ctx.plugin({ name: 'synthetic-pi', inject: ['llm'], apply: child => pi.apply(child, config) });
  const settings = { enabled: true, automaticExtraction: true }; const writers = new Map(), errors = [];
  ctx.on('session/flush', async session => { await writers.get(session.id)?.flush(); });
  const helper = createDshMemoryExtraction({ ctx, privacy, settings: async () => settings, getModel: () => ({ provider: 'synthetic', model: 'synthetic' }), reportError: code => errors.push(code) });
  const maintenance = createMemoryMaintenance({ store: memory, settings: async () => settings,
    executionStatus: async () => ({ ready: true }), extraction: helper.extraction });
  t.after(async () => {
    await helper.dispose(); await maintenance.close(); privacy.dispose();
    for (const writer of writers.values()) await writer.close(); await ctx.fiber.dispose();
    server.closeAllConnections(); await new Promise(resolve => server.close(resolve));
    await memory.close(); await privateStore.close(); await rm(dir, { recursive: true, force: true });
  });
  async function source(id = 'synthetic-session', text = '合成用户说：我喜欢步行。') {
    const session = ctx.sessions.create(id, { meta: { cwd: '/synthetic/workspace' } });
    const events = [session.append('turn/start', { turn: 1 })];
    events.push(session.append('user/message', llm.createUserMessage({ content: [{ type: 'text', text }], source: { kind: 'user' } }), { surfaceOp: 'append' }));
    events.push(session.append('turn/end', { turn: 1, reason: { kind: 'completed' } }));
    const writer = await ctx.sessionPersistence.create(session.header); writers.set(id, writer); await writer.append(events); await writer.flush();
    await privateStore.registerCleanSession(id);
    return { session, start: { seq: events[0].seq, turn: 1 }, end: events.at(-1) };
  }
  async function grant(categories = ['memory']) {
    const route = await helper.extraction.resolveRoute({ purpose: 'extraction' }); await privacy.grantEndpoint(route, { categories }); return route;
  }
  async function enqueue(value) { return helper.enqueueCompletedTurn(value.session, value.end, value.start, descriptor => maintenance.enqueueExtraction(descriptor)); }
  const waitForRequest = running => within(Promise.race([requestSeen, running.then(result => {
    throw new Error(`Extraction ended before the expected request: ${result.code ?? result.status}`);
  })]), 'Synthetic extraction request');
  return { ctx, helper, maintenance, memory, privateStore, privacy, settings, errors, sent, waitForRequest, source, grant, enqueue,
    setResponse: value => { response = value; }, setHold: value => { hold = value; }, setLocale: value => { locale = value; } };
}

test('genuine DSH prepared model + durable session slice produces a candidate via approved extraction purpose', async t => {
  const f = await fixture(t); const source = await f.source(); await f.grant(); await f.enqueue(source);
  const result = await f.maintenance.extractOnce(); assert.equal(result.status, 'completed', JSON.stringify({result,errors:f.errors,sent:f.sent.length})); assert.equal(f.sent.length, 1);
  assert.equal(f.sent[0].max_tokens ?? f.sent[0].max_completion_tokens, 1000);
  const row = (await f.memory.query()).items[0]; assert.equal(row.assertion, 'model_inference'); assert.equal(row.state, 'candidate');
  assert.ok(row.sourceRefs.includes('dsh-session:synthetic-session'));
  assert.ok(JSON.stringify(f.sent[0]).includes('合成用户说'));
  const cursor = JSON.parse((await f.memory.getSourceCursor({ source: 'maintenance.state.v1' })).cursor);
  assert.equal(cursor.queue.length, 0); assert.ok(cursor.budget.tokens > 1000);
});

test('missing execution protection blocks the actual dispatcher before sending a model request', async t => {
  const f = await fixture(t, { executionInstalled: false }); await f.grant(); await f.enqueue(await f.source());
  const result = await f.maintenance.extractOnce();
  assert.equal(result.code, 'MEMORY_EXTRACTION_MODEL_FAILED'); assert.equal(f.sent.length, 0);
  assert.equal(f.maintenance.status().held, 1); assert.equal((await f.memory.query()).total, 0);
  await assert.rejects(f.waitForRequest(Promise.resolve(result)), /Extraction ended before the expected request/);
});

test('the same extraction service uses the live request locale without translating sources or changing candidate protocol', async t => {
  const f = await fixture(t), original = 'Synthetic 原文保持不变。';
  await f.grant(); await f.enqueue(await f.source('locale-zh', original));
  assert.equal((await f.maintenance.extractOnce()).status, 'completed');
  const systemText = request => request.messages.find(message => ['system', 'developer'].includes(message.role)).content;
  assert.match(systemText(f.sent[0]), /^整理获准来源/);
  await f.enqueue(await f.source('locale-en', original));
  const before = (await f.memory.getSourceCursor({ source: 'maintenance.state.v1' })).cursor;
  const count = f.helper.extraction.countTokens;
  f.helper.extraction.countTokens = async input => { const tokens = await count(input); f.setLocale('en'); return tokens; };
  assert.equal((await f.memory.getSourceCursor({ source: 'maintenance.state.v1' })).cursor, before);
  assert.equal((await f.maintenance.extractOnce({ manual: true })).status, 'completed');
  assert.equal(f.sent.length, 2); assert.match(systemText(f.sent[1]), /^Extract candidate personal memories/);
  assert.doesNotMatch(systemText(f.sent[1]), /[\u4e00-\u9fff]/u);
  for (const request of f.sent) {
    assert.ok(JSON.stringify(request.messages.find(message => message.role === 'user')).includes(original));
    assert.equal(request.model, 'synthetic'); assert.equal(request.max_tokens ?? request.max_completion_tokens, 1000);
  }
  const rows = (await f.memory.query()).items;
  assert.equal(rows.length, 2);
  for (const row of rows) { assert.equal(row.kind, 'preference'); assert.equal(row.state, 'candidate'); assert.equal(row.assertion, 'model_inference'); }
  assert.ok(rows.some(row => row.sourceRefs.includes('dsh-session:locale-en')));
});

test('conversation grant cannot authorize extraction, and failed authorization is held without retry', async t => {
  const f = await fixture(t), value = await f.source();
  const route = await f.privacy.prepareRoute({ provider: 'synthetic', model: 'synthetic' }, 'conversation');
  await f.privacy.grantEndpoint(route, { categories: ['memory'] }); await f.enqueue(value);
  assert.equal((await f.maintenance.extractOnce()).code, 'PRIVACY_ENDPOINT_NOT_AUTHORIZED'); assert.equal(f.sent.length, 0);
  assert.equal(f.maintenance.status().held, 1); assert.equal((await f.maintenance.extractOnce()).status, 'waiting');
});

test('protected source categories and original lineage follow extraction rather than becoming memory-only', async t => {
  const f = await fixture(t), value = await f.source(); const route = await f.grant(['memory', 'location']);
  await f.privacy.beforeDisclosure({ sessionId: value.session.id, categories: ['location'], sourceRefs: ['location:synthetic'], route });
  await f.enqueue(value); assert.equal((await f.maintenance.extractOnce()).status, 'completed');
  const row = (await f.memory.query()).items[0]; assert.ok(row.privacy.categories.includes('location')); assert.ok(row.sourceRefs.includes('location:synthetic'));
  await f.privacy.forgetSources(['location:synthetic']);
  await assert.rejects(f.helper.enqueueCompletedTurn(value.session, value.end, value.start, () => assert.fail()), /SESSION_FORGOTTEN/);
});

test('a source made private after enqueue is refused before body dispatch', async t => {
  const f = await fixture(t), value = await f.source(); const route = await f.grant(['memory', 'location']); await f.enqueue(value);
  await f.privacy.beforeDisclosure({ sessionId: value.session.id, categories: ['location'], sourceRefs: ['location:later'], route });
  assert.equal((await f.maintenance.extractOnce()).code, 'MEMORY_EXTRACTION_SOURCE_PRIVACY_CHANGED'); assert.equal(f.sent.length, 0); assert.equal(f.maintenance.status().held, 1);
});

test('completed turn subscription enqueues metadata only and ignores disabled or cancelled work', async t => {
  const f = await fixture(t); const value = await f.source(); const descriptors = [];
  let received; const enqueued = new Promise(resolve => { received = resolve; });
  f.helper.subscribe(async value => { descriptors.push(value); received(); return { status: 'queued' }; });
  const session = value.session;
  const start = session.append('turn/start', { turn: 2 }); session.append('turn/end', { turn: 2, reason: { kind: 'completed' } });
  await within(enqueued, 'Completed turn subscription');
  assert.equal(descriptors.length, 1, JSON.stringify(f.errors)); assert.equal(descriptors[0].sourceId, 'dsh:synthetic-session');
  assert.ok(descriptors[0].revision.startsWith(`v1:${start.seq}:`)); assert.ok(!JSON.stringify(descriptors).includes('合成用户说'));
  f.settings.automaticExtraction = false;
  session.append('turn/start', { turn: 3 }); session.append('turn/end', { turn: 3, reason: { kind: 'completed' } }); await tick(); await tick();
  assert.equal(descriptors.length, 1); assert.equal(f.sent.length, 0);
});

test('malformed model output cannot create records and retains a held job', async t => {
  const f = await fixture(t); await f.grant(); await f.enqueue(await f.source()); f.setResponse('not JSON');
  assert.equal((await f.maintenance.extractOnce()).code, 'MEMORY_INVALID_EXTRACTION_RESULT');
  assert.equal((await f.memory.query()).total, 0); assert.equal(f.maintenance.status().held, 1);
});


test('revoking extraction permission aborts the real in-flight SDK stream and preserves held work', async t => {
  const f = await fixture(t); const route = await f.grant(); await f.enqueue(await f.source()); f.setHold(true);
  const running = f.maintenance.extractOnce(); await f.waitForRequest(running); await f.privacy.revokeEndpoint(route);
  const result = await running; assert.equal(result.status, 'blocked'); assert.equal(f.sent.length, 1);
  assert.equal((await f.memory.query()).total, 0); assert.equal(f.maintenance.status().held, 1);
});

test('disabling memory cancels the in-flight extraction without storing a late answer', async t => {
  const f = await fixture(t); await f.grant(); await f.enqueue(await f.source()); f.setHold(true);
  const running = f.maintenance.extractOnce(); await f.waitForRequest(running); f.settings.enabled = false; await f.maintenance.stop('MEMORY_DISABLED');
  assert.equal((await running).code, 'MEMORY_DISABLED'); assert.equal((await f.memory.query()).total, 0);
  assert.equal(f.maintenance.status().held, 1);
});
