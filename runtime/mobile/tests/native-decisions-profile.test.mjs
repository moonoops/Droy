import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdir, mkdtemp, rm, writeFile, copyFile } from 'node:fs/promises';
import { createRequire, registerHooks } from 'node:module';
import { createServer } from 'node:http';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { setTimeout as delay } from 'node:timers/promises';
import { companionState } from './fixtures/companion-state.mjs';

const runtime = fileURLToPath(new URL('../../generated/dsh/', import.meta.url));
const mobile = fileURLToPath(new URL('../', import.meta.url));
const token = 'fictional-native-profile-cookie-12345678901234567890';
const textOf = message => (message?.content ?? []).filter(block => block.type === 'text').map(block => block.text).join('');
async function eventually(read, label) {
  const until = Date.now() + 10000;
  while (Date.now() < until) { const value = read(); if (value) return value; await delay(15); }
  assert.fail(`Timed out: ${label}`);
}

test('real mobile profile routes approval, rejection, questions and cancellation before Remote forwarding', { timeout: 60000 }, async t => {
  const home = await mkdtemp(join(tmpdir(), 'droy-native-decisions-profile-'));
  const profile = join(home, 'profiles/mobile');
  await mkdir(profile, { recursive: true });
  const fixture = join(home, 'native-fixture.mjs');
  await copyFile(new URL('../../../app/src/androidTest/assets/native-workbench-provider.mjs', import.meta.url), fixture);
  await writeFile(join(profile, 'cordis.patch.yml'), JSON.stringify([
    { id: 'agent-default-model', config: { provider: 'droy-native-fixture', model: 'native-local' } },
    { id: 'locale', config: { preference: 'en' } },
    { insert: [{ id: 'native-local-fixture', name: fixture }] },
  ]));
  const reservation = createServer();
  await new Promise(resolve => reservation.listen(0, '127.0.0.1', resolve));
  const port = reservation.address().port;
  await new Promise(resolve => reservation.close(resolve));
  const nativeCalls = [];
  const bridge = createServer(async (req, res) => {
    assert.equal(req.headers.authorization, `Bearer ${token}`);
    for await (const _ of req) { /* consume the fixture-only request */ }
    nativeCalls.push(req.url);
    const result = req.url === '/companion' ? companionState
      : req.url === '/notify' ? { pluginActive: true, localEnabled: true, appsEnabled: false, channels: [] }
        : req.url === '/memory' ? { settings: { enabled: false, recallEnabled: true, automaticExtraction: false, privacyEverActivated: false },
          root: { authorized: false }, execution: { ready: false, reason: 'MEMORY_DISABLED' } }
          : req.url === '/history' ? { config: { activityEnabled: false, locationEnabled: false }, running: false }
            : req.url === '/clock' ? { now: Date.now(), timeZone: 'Asia/Shanghai' }
              : ['/progress', '/task/end', '/voice'].includes(req.url) ? {} : undefined;
    res.writeHead(result === undefined ? 404 : 200, { 'content-type': 'application/json' });
    res.end(JSON.stringify(result === undefined ? { ok: false, error: { code: 'DEVICE_ACTION_OUTSIDE_FIXTURE' } } : { ok: true, result }));
  });
  await new Promise(resolve => bridge.listen(0, '127.0.0.1', resolve));
  const environment = { DSH_HOME: home, DROY_RUNTIME_DIR: runtime, DROY_LOCALE: 'en', DROY_WEB_TOKEN: token,
    DROY_BRIDGE_TOKEN: token, DROY_BRIDGE_PORT: String(bridge.address().port), DROY_WEB_PORT: String(port),
    DSH_TOOLS_MODE: 'native', DSH_TELEMETRY_DISABLED: '1', DSH_PERMISSION_MODE: undefined };
  const previous = Object.fromEntries(Object.keys(environment).map(key => [key, process.env[key]]));
  for (const [key, value] of Object.entries(environment)) {
    if (value === undefined) delete process.env[key]; else process.env[key] = value;
  }
  const installed = createRequire(pathToFileURL(join(runtime, 'fixture-entry.mjs')));
  const hooks = registerHooks({ resolve(specifier, context, next) {
    if (specifier === '@deepseek-ai/node-addon-system/flock') return { url: new URL('../../dsh/node_modules/@deepseek-ai/node-addon-system/lib/flock.js', import.meta.url).href, shortCircuit: true };
    if (specifier === '@droyagent/mobile/publish') return { url: new URL('./fixtures/host-publish.mjs', import.meta.url).href, shortCircuit: true };
    // Exercise the current API installation and broker against the full generated profile.
    if (specifier === './native-api.mjs' && [join(runtime, 'mobile/plugin.mjs'), join(runtime, 'node_modules/@droyagent/mobile/plugin.mjs')]
      .some(path => context.parentURL === pathToFileURL(path).href)) return { url: pathToFileURL(join(mobile, 'native-api.mjs')).href, shortCircuit: true };
    if (specifier.startsWith('@deepseek-ai/') && context.parentURL?.startsWith(pathToFileURL(mobile).href))
      return { url: pathToFileURL(installed.resolve(specifier)).href, shortCircuit: true };
    return next(specifier, context);
  } });
  let running, agent, pump;
  const streamAbort = new AbortController();
  t.after(async () => {
    try {
      if (agent?.status === 'running') { agent.cancel(); await agent.whenIdle(); }
      streamAbort.abort(); if (pump) await pump;
      await running?.shutdown.shutdown(0);
    } finally {
      hooks.deregister(); bridge.closeAllConnections(); await new Promise(resolve => bridge.close(resolve));
      for (const [key, value] of Object.entries(previous)) { if (value === undefined) delete process.env[key]; else process.env[key] = value; }
      await rm(home, { recursive: true, force: true });
    }
  });
  const load = path => import(pathToFileURL(join(runtime, path)).href);
  await load('mobile/bootstrap.mjs');
  const { runProfile } = await load('node_modules/@deepseek-ai/dsh/lib/profile-boot.js');
  const { createLaunchEnvironmentSnapshot } = await load('node_modules/@deepseek-ai/dsh-launch-environment/lib/index.js');
  const { mobilePackageManager } = await load('mobile/package-manager.mjs');
  const manager = mobilePackageManager(); manager.env = { ...manager.env, npm_config_offline: 'true', CI: 'true' };
  running = await runProfile({ profile: 'mobile', patchFiles: [], args: ['--no-open'], packageManager: manager,
    environment: createLaunchEnvironmentSnapshot([{ source: 'process', values: process.env }]) });
  const { ctx } = running, errors = [];
  ctx.on('agent/error', event => errors.push(event.error));
  ctx.on('llm/stream', (options, next) => { assert.equal(options.provider, 'droy-native-fixture'); return next(); });
  await eventually(() => ctx.get('droyNativeApi') && ctx.get('droyInitialization')?.ready, 'native service startup');
  const base = `http://127.0.0.1:${port}/droy/native/`;
  const headers = { Cookie: `droy_session=${token}`, 'content-type': 'application/json' };
  const post = async (path, body, status = 200) => {
    const response = await fetch(base + path, { method: 'POST', headers, body: JSON.stringify(body) });
    const envelope = await response.json(); assert.equal(response.status, status, JSON.stringify(envelope));
    assert.equal(envelope.ok, status === 200); return status === 200 ? envelope.result : envelope.error;
  };
  const response = await fetch(base + 'decisions', { headers, signal: streamAbort.signal });
  assert.equal(response.status, 200);
  const frames = [], pending = new Map();
  let streamFailure;
  pump = (async () => {
    const reader = response.body.getReader(), decoder = new TextDecoder(); let buffered = '';
    try {
      for (;;) {
        const next = await reader.read(); if (next.done) return;
        buffered += decoder.decode(next.value, { stream: true });
        let end;
        while ((end = buffered.indexOf('\n\n')) >= 0) {
          const raw = buffered.slice(0, end); buffered = buffered.slice(end + 2);
          const data = raw.split('\n').filter(line => line.startsWith('data: ')).map(line => line.slice(6)).join('\n');
          if (!data) continue;
          const frame = JSON.parse(data); frames.push(frame);
          if (frame.type === 'snapshot') { pending.clear(); for (const item of frame.decisions) pending.set(item.id, item); }
          else if (frame.type === 'upsert') pending.set(frame.decision.id, frame.decision);
          else if (frame.type === 'remove') pending.delete(frame.id);
          else throw new Error('Unexpected native frame: ' + data);
        }
      }
    } catch (error) { if (!streamAbort.signal.aborted) streamFailure = error; }
  })();
  const wait = (predicate, label) => eventually(() => { if (streamFailure) throw streamFailure; return predicate(); }, label);
  await wait(() => frames.some(frame => frame.type === 'snapshot'), 'initial native snapshot');
  const created = await post('session/create', { workspaceId: ctx.get('droyInitialization').defaultWorkspaceId });
  agent = ctx.agents.get(created.sessionId); assert.ok(agent);
  assert.equal(ctx.approval.effectivePolicy(agent.session), 'ask');
  assert.equal(ctx.droyCompanions.ownsSession(agent.id), false);
  let request = 0;
  const send = marker => post('session/prompt', { sessionId: agent.id, requestId: `native-decisions-profile-${++request}`, mode: 'queue',
    clientTimeZone: 'Asia/Shanghai', content: [{ type: 'text', text: marker }] });
  const card = kind => wait(() => [...pending.values()].find(item => item.kind === kind), kind + ' native card');
  const messages = () => agent.session.snapshotEvents().filter(event => event.type === 'assistant/message').map(event => textOf(event.data.message));
  for (const outcome of ['allowed-once', 'rejected']) {
    await send(outcome === 'allowed-once' ? 'NATIVE_APPROVAL_ALLOW' : 'NATIVE_APPROVAL_REJECT');
    const decision = await card('approval');
    assert.equal(decision.sessionId, agent.id); assert.equal(decision.canApprove, true); assert.equal(decision.toolArguments, '{}');
    const asked = agent.session.snapshotEvents().find(event => event.type === 'approval/asked' && event.data.callId === decision.callId);
    assert.ok(asked);
    assert.deepEqual(await post(`decisions/${decision.id}/answer`, { sessionId: agent.id, outcome }), { accepted: true });
    await agent.whenIdle();
    assert.ok(messages().includes(`Native approval: ${outcome}.`));
    assert.deepEqual(agent.session.snapshotEvents().filter(event => event.type === 'approval/decided' && event.data.id === asked.data.id)
      .map(event => event.data.outcome), [outcome]);
    await wait(() => !pending.has(decision.id), 'approval card withdrawn');
    assert.equal((await post(`decisions/${decision.id}/answer`, { sessionId: agent.id, outcome }, 410)).code, 'NATIVE_DECISION_ENDED');
  }
  await send('NATIVE_QUESTION');
  const question = await card('question');
  assert.deepEqual(question.questions[0].options, [{ label: 'Alpha' }, { label: 'Beta' }]);
  const answers = [{ id: 'choice', selected: ['Alpha'] }];
  await post(`decisions/${question.id}/answer`, { sessionId: agent.id, answers }); await agent.whenIdle();
  assert.ok(messages().includes('Native question: Alpha.'));
  await wait(() => !pending.has(question.id), 'answered question withdrawn');
  const before = messages().filter(message => message === 'Native question: Alpha.').length;
  await send('NATIVE_QUESTION_CANCEL');
  const cancelled = await card('question');
  await post('session/cancel', { sessionId: agent.id }); await agent.whenIdle();
  await wait(() => !pending.has(cancelled.id), 'cancelled question withdrawn');
  assert.equal((await post(`decisions/${cancelled.id}/answer`, { sessionId: agent.id, answers }, 410)).code, 'NATIVE_DECISION_ENDED');
  assert.equal(messages().filter(message => message === 'Native question: Alpha.').length, before);
  assert.deepEqual(errors, []);
  assert.equal(nativeCalls.includes('/tool'), false);
  assert.ok(nativeCalls.includes('/progress'), 'existing Pet progress observer still participates');
});
