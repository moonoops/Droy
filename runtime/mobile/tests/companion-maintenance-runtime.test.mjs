import test, { after } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, mkdir, rm } from 'node:fs/promises';
import { join } from 'node:path';
import { tmpdir } from 'node:os';
import { createRequire, registerHooks } from 'node:module';
import { pathToFileURL } from 'node:url';
import { setTimeout as delay } from 'node:timers/promises';
import { CompanionStore } from '../companions/store.mjs';
import { prepareReply } from '../companions/delivery.mjs';
import { createCompanionMaintenanceLease } from '../companions/maintenance-runtime.mjs';

const installed = createRequire(new URL('../../dsh/package.json', import.meta.url));
const hooks = registerHooks({ resolve(specifier, context, next) {
  if (specifier === '@droyagent/memory/memos') return { url: new URL('../../memory/memos/store.mjs', import.meta.url).href, shortCircuit: true };
  if (specifier === '@droyagent/memory/store') return { url: new URL('../../memory/store.mjs', import.meta.url).href, shortCircuit: true };
  if (specifier.startsWith('@deepseek-ai/')) return { url: pathToFileURL(installed.resolve(specifier)).href, shortCircuit: true };
  return next(specifier, context);
} });
after(() => hooks.deregister());
const [{ CompanionRuntime }, { Context }, { createScope }, { default: Tools }, { default: Prompt }] = await Promise.all([
  import('../companions/runtime.mjs'), import('@deepseek-ai/cordis'), import('@deepseek-ai/dsh-scope'),
  import('@deepseek-ai/dsh-tools'), import('@deepseek-ai/dsh-system-prompt'),
]);
const NOW = Date.UTC(2030, 0, 1, 12), model = { provider: 'synthetic-maintenance', model: 'local-fixture' };
const deferred = () => Promise.withResolvers();
const code = value => error => error.code === value;
function clock(t) { t.mock.timers.enable({ apis: ['Date', 'setTimeout'], now: NOW }); }
async function eventually(read, description) {
  const deadline = performance.now() + 3000;
  while (performance.now() < deadline) { if (await read()) return; await delay(2); }
  assert.fail('Timed out: ' + description);
}

async function fixture(t, options = {}) {
  const directory = await mkdtemp(join(tmpdir(), 'droy-companion-maintenance-'));
  const store = await CompanionStore.open({ directory }), ctx = new Context();
  const calls = { created: [], cancelled: [], disposed: [], model: [], notifications: [], warnings: [] }, agents = new Map(), workspaces = new Map();
  let runtime, runtimeContext = ctx;
  let closed = false;
  const close = async () => { if (closed) return; closed = true;
    try { await runtime?.close(); } finally { await ctx.fiber.dispose(); await store.close(); await rm(directory, { recursive: true, force: true }); }
  };
  t.after(close);
  await ctx.plugin(Tools, { mode: 'native' }); await ctx.plugin(Prompt);
  ctx.provide('settings', { describe: () => [{ ns: 'locale', value: { preference: 'en' } }] });
  ctx.provide('workingDirectory', { defaultDirectory: join(directory, 'default') });
  ctx.provide('workspaceRegistry', {
    list: () => [...workspaces.values()], get: id => workspaces.get(id),
    async create(path, title) { const id = 'workspace-' + workspaces.size;
      const item = { id, path, title, async attachSession() {}, async setTitle(value) { this.title = value; } };
      workspaces.set(id, item); return item;
    },
    async initializeDefault(resolve) { const path = await resolve(); await mkdir(path, { recursive: true }); return this.create(path); },
  });
  ctx.provide('agentDefaultModel', { currentSelection: () => model });
  ctx.provide('sessionController', { async create() {}, async prompt() {}, async fork() {}, async selectModel() {} });
  ctx.logger.warn = value => calls.warnings.push(value);
  async function makeAgent(input, resumed) {
    await options.beforeAgent?.(input);
    const id = resumed ? input.resumeSessionId : input.sessionId, nextTurn = [], nextStep = [];
    let status = 'idle', activity = Promise.resolve(), settled;
    const agent = { id, options: input.agentOptions, session: { id }, deliveries: [], inbox: { nextTurn, nextStep },
      get status() { return status; },
      set status(value) {
        if (value === status) return;
        if (value === 'running') { const next = deferred(); activity = next.promise; settled = next.resolve; }
        status = value; if (value === 'idle') settled?.(); runtimeContext.emit('agent/status', { agent, status: value });
      },
      async whenIdle() { let current; do { await (current = activity); } while (current !== activity); },
      followup(message) { nextTurn.push(message); this.deliveries.push(message); this.status = 'running'; },
      steer(message) { nextStep.push(message); this.deliveries.push(message); this.status = 'running'; },
      retire() { nextTurn.length = nextStep.length = 0; this.status = 'idle'; },
      cancel(reason) { calls.cancelled.push({ id, reason }); this.retire(); },
    };
    const scope = createScope(runtimeContext, agent); agent.ctx = scope.ctx;
    await input.setup(scope.ctx, agent); agents.set(id, agent); calls.created.push(agent);
    return { agent, async dispose() { calls.disposed.push(id); agent.cancel({ kind: 'disposed' }); agents.delete(id); await scope.dispose(); } };
  }
  ctx.provide('agents', { get: id => agents.get(id), create: input => makeAgent(input, false), resume: input => makeAgent(input, true) });
  ctx.provide('llm', { async prepareCall(config, signal) {
    return { config, async *stream(input) {
      let data; try { data = JSON.parse(input.messages[0].content[0].text); } catch {}
      const kind = data?.activity && Array.isArray(data?.evidence) ? 'proactive'
        : data?.existing ? 'learning' : data?.stage ? 'learning-stage' : data?.candidates ? 'recall' : 'reply';
      const call = { kind, config, signal, input }; calls.model.push(call); await options.beforeStream?.(call);
      signal.throwIfAborted();
      const proactiveSource=kind==='proactive'?data.evidence.find(item=>item.kind==='message'):null;
      if(kind==='proactive')assert.ok(proactiveSource,'proactive fixture decisions require a supplied user source');
      yield { type: 'text-delta', text: kind === 'proactive' ? JSON.stringify({action:'contact',kind:'check_in',
        reason:'Continue the fictional shared hiking topic',sourceIds:[proactiveSource.id],nextCheckMinutes:5})
        : kind === 'learning-stage' ? '[]' : kind === 'learning' ? JSON.stringify({ impressions: [], expressions: [], jargon: [], memories: [] })
        : kind === 'recall' ? JSON.stringify({ recordIds: [] }) : 'Synthetic companion response.' };
      yield { type: 'finish', reason: { kind: 'stop' } };
    } };
  } });
  await ctx.plugin({ name: 'maintenance-runtime-fixture', inject: ['tools','systemPrompt','settings','workingDirectory',
    'workspaceRegistry','agentDefaultModel','sessionController','agents','llm'], apply(scope) { runtimeContext = scope; } });
  runtime = new CompanionRuntime({ ctx: runtimeContext, store, directory, options: { debounceMs: 20, learningMessages: 1,
    proactiveIntervalMs: 1000, proactiveIdleMs: 100, getClock: async () => ({ now: Date.now(), timeZone: 'UTC' }) },
    notify: async value => { calls.notifications.push(value); } });
  await runtime.initialize();
  const id = (await store.profiles())[0].id;
  await store.updateProfile(id, { settings: { quietHours: { enabled: false, start: '22:00', end: '09:00' } } });
  return { runtime, store, ctx, id, calls, agents, close, lease: operationId => createCompanionMaintenanceLease(runtime, { operationId }) };
}

test('proactive pause preserves its deadline and stored state; resume fires once and freezes its next trigger', async t => {
  clock(t); const f = await fixture(t);
  await f.store.updateProfile(f.id, { settings: { proactiveEnabled: true } });
  await f.store.appendMessage(f.id,{id:'proactive-source',role:'user',text:'I enjoy fictional weekend hiking.',
    createdAt:new Date(NOW-60000).toISOString(),status:'sent'});
  await f.store.updateState(f.id, { lastUserAt: new Date(NOW - 60000).toISOString(), proactiveNextAt: new Date(NOW + 100).toISOString() });
  const saved = await f.store.getState(f.id); f.runtime.scheduleProactive(f.id, saved.proactiveNextAt);
  const lease = f.lease('proactive-window'); assert.equal((await lease.suspend()).quiet, true);
  t.mock.timers.tick(10000);
  assert.equal(f.calls.created.length, 0); assert.deepEqual(await f.store.getState(f.id), saved);
  lease.resume(); lease.resume(); t.mock.timers.tick(0);
  await eventually(() => f.calls.created.some(agent => agent.deliveries.length), 'one resumed proactive delivery');
  const agent = f.calls.created[0]; assert.equal(agent.deliveries.length, 1); agent.retire();
  const second = f.lease('next-proactive-window'); assert.equal((await second.suspend()).quiet, true);
  const nextAt=Date.parse((await f.store.getState(f.id)).proactiveNextAt),modelCalls=f.calls.model.length;
  assert.ok(nextAt>Date.now());t.mock.timers.tick(nextAt-Date.now()+10000);
  assert.equal(agent.deliveries.length, 1); assert.equal(f.calls.created.length, 1);assert.equal(f.calls.model.length,modelCalls);
  assert.deepEqual(f.calls.cancelled, []); assert.equal((await f.store.getProfile(f.id)).model, null);
});

test('an already accepted Replyer completes publication and notification without abort or false idle status', async t => {
  const entered = deferred(), finish = deferred(); t.after(() => finish.resolve());
  const f = await fixture(t, { beforeStream: async () => { entered.resolve(); await finish.promise; } });
  const agent = await f.runtime.agent(f.id), owner = await f.store.sessionOwner(agent.id);
  await f.store.appendMessage(f.id, { id: 'reply-source', role: 'user', text: 'Synthetic input', status: 'sent' });
  const replying = f.runtime.reply(owner, { msg_id: 'reply-source', reply_reference: 'Synthetic reply' }); await entered.promise;
  const lease = f.lease('reply-window'); let completed = false;
  const observing = lease.suspend().then(value => { completed = true; return value; });
  assert.equal(agent.status, 'idle'); assert.equal(lease.status().quiet, false); assert.ok(lease.status().activeWriters > 0);
  assert.equal(f.calls.model[0].signal.aborted, false); assert.equal(completed, false);
  finish.resolve(); const result = await replying; const receipt = await observing;
  assert.equal(result.delivered, true); assert.equal(receipt.quiet, true);
  assert.equal(f.calls.notifications.length, 1); assert.deepEqual(f.calls.cancelled, []);
  assert.equal((await f.store.listMessages(f.id)).messages.filter(message => message.role === 'assistant').length, 1);
});

test('an entered proactive callback completes after hold while its next timer remains suspended', async t => {
  clock(t); const f = await fixture(t), entered = deferred(), finish = deferred(); t.after(() => finish.resolve());
  await f.store.updateProfile(f.id, { settings: { proactiveEnabled: true } });
  await f.store.appendMessage(f.id,{id:'proactive-source',role:'user',text:'I enjoy fictional weekend hiking.',
    createdAt:new Date(NOW-60000).toISOString(),status:'sent'});
  await f.store.updateState(f.id, { lastUserAt: new Date(NOW - 60000).toISOString() });
  const getState = f.store.getState.bind(f.store); let gated = false;
  f.store.getState = async id => { const saved = await getState(id); if (!gated) { gated = true; entered.resolve(); await finish.promise; } return saved; };
  f.runtime.scheduleProactive(f.id, new Date(NOW).toISOString()); t.mock.timers.tick(0); await entered.promise;
  const lease = f.lease('entered-proactive-window'), observing = lease.suspend();
  assert.equal(lease.status().quiet, false); finish.resolve();
  await eventually(() => f.calls.created.some(agent => agent.deliveries.length), 'already entered proactive callback');
  const agent = f.calls.created[0], owner = await f.store.sessionOwner(agent.id);
  const finishing = f.runtime.finishTurn(owner, { data: { reason: { kind: 'completed' } } }); agent.retire(); await finishing;
  assert.equal((await observing).quiet, true);
  const nextAt=Date.parse((await f.store.getState(f.id)).proactiveNextAt),modelCalls=f.calls.model.length;
  assert.ok(nextAt>Date.now());t.mock.timers.tick(nextAt-Date.now()+10000);
  assert.equal(agent.deliveries.length, 1);assert.equal(f.calls.model.length,modelCalls);
  lease.resume(); t.mock.timers.tick(0);
  await eventually(() => agent.deliveries.length === 2, 'next proactive callback resumes once');
  lease.resume(); assert.equal(agent.deliveries.length, 2); assert.deepEqual(f.calls.cancelled, []);
});

test('work and finishTurn result writes continue, then their chat continuation drains naturally', async t => {
  clock(t); const f = await fixture(t);
  const task = await f.runtime.startWork(f.id, { title: 'Synthetic work', goal: 'Synthetic goal' }), agent = f.agents.get(task.sessionId);
  const owner = await f.store.sessionOwner(task.sessionId), lease = f.lease('work-window');
  let complete = false; const observing = lease.suspend().then(value => { complete = true; return value; });
  assert.equal(lease.status().quiet, false); assert.equal(complete, false);
  f.runtime.state(f.id).workResults = new Map([[agent.id, 'Synthetic completed work']]);
  const finishing = f.runtime.finishTurn(owner, { data: { reason: { kind: 'completed' } } });
  agent.retire(); await finishing;
  const pending = await observing; assert.equal(pending.quiet, false);
  assert.equal((await f.store.getMessage(f.id, task.taskId)).task.state, 'completed');
  t.mock.timers.tick(20);
  await eventually(() => f.calls.created.some(item => item !== agent && item.deliveries.length), 'work-result chat continuation');
  const chat = f.calls.created.find(item => item !== agent), chatOwner = await f.store.sessionOwner(chat.id);
  const chatFinish = f.runtime.finishTurn(chatOwner, { data: { reason: { kind: 'completed' } } }); chat.retire(); await chatFinish;
  assert.equal((await lease.suspend()).quiet, true); assert.deepEqual(f.calls.cancelled, []);
});

test('unpublished work startup remains active across hold and its outstanding card stays busy after Agent retirement', async t => {
  const entered = deferred(), finish = deferred(); t.after(() => finish.resolve());
  const f = await fixture(t, { beforeAgent: async () => { entered.resolve(); await finish.promise; } });
  const starting = f.runtime.startWork(f.id, { title: 'Synthetic opening work', goal: 'Synthetic opening goal' }); await entered.promise;
  const lease = f.lease('opening-window'), observing = lease.suspend();
  assert.equal(f.calls.created.length, 0); assert.ok(lease.status().activeWriters > 0); assert.equal(lease.status().quiet, false);
  finish.resolve(); const task = await starting, agent = f.agents.get(task.sessionId);
  assert.equal(agent.deliveries.length, 1); assert.equal(agent.status, 'running'); assert.deepEqual(f.calls.cancelled, []);
  agent.retire(); const result = await observing;
  assert.equal(result.quiet, false); assert.ok(result.busy.some(item => item.kind === 'work-task'));
  assert.equal((await f.store.getMessage(f.id, task.taskId)).task.state, 'running');
});

test('public whenIdle is awaited even when the Agent status already says idle', async t => {
  const f = await fixture(t), agent = await f.runtime.agent(f.id);
  await eventually(() => f.runtime.chatExecution(f.id).quiescent, 'initial public retirement');
  const finish = deferred(), whenIdle = agent.whenIdle; t.after(() => finish.resolve());
  agent.whenIdle = () => finish.promise;
  const lease = f.lease('public-idle-window'); let completed = false;
  const observing = lease.suspend().then(value => { completed = true; return value; });
  assert.equal(agent.status, 'idle'); assert.equal(completed, false); assert.equal(lease.status().quiet, false);
  finish.resolve(); assert.equal((await observing).quiet, true); agent.whenIdle = whenIdle;
});

test('accepted MaiBot learning settles its real model and durable cursor without being cancelled', async t => {
  const entered = deferred(), finish = deferred(); t.after(() => finish.resolve());
  const f = await fixture(t, { beforeStream: async call => { if (call.kind === 'learning') { entered.resolve(); await finish.promise; } } });
  await f.store.appendMessage(f.id, { id: 'learning-source', role: 'user', text: 'Synthetic learning fact', status: 'sent' });
  const learning = f.runtime.learn(f.id); await entered.promise;
  const lease = f.lease('learning-window'), observing = lease.suspend();
  assert.ok(lease.status().activeWriters > 0); assert.equal(f.calls.model[0].signal.aborted, false);
  finish.resolve(); await learning;
  assert.equal((await observing).quiet, true);
  assert.ok((await f.store.getState(f.id)).learningCursor); assert.deepEqual(f.calls.cancelled, []);
  assert.equal((await f.store.getProfile(f.id)).model, null);
});

test('a recovery registered after hold stays busy and untouched until resume, then publishes its saved draft only once', async t => {
  clock(t); const f = await fixture(t);
  await f.store.appendMessage(f.id, { id: 'recover-source', role: 'user', text: 'Synthetic pending input', status: 'received' });
  await prepareReply({ store: f.store, companionId: f.id, sourceMessageIds: ['recover-source'], parts: ['Synthetic saved response'] });
  const before = await f.store.getState(f.id), reading = deferred(), read = deferred(), getState = f.store.getState.bind(f.store);
  let gated = false;
  f.store.getState = async id => { const saved = await getState(id); if (!gated) { gated = true; reading.resolve(); await read.promise; } return saved; };
  const starting = f.runtime.startInstance(f.id); await reading.promise;
  const lease = f.lease('cold-recovery-window'), observing = lease.suspend(); read.resolve(); await starting;
  const receipt = await observing; assert.equal(receipt.quiet, false); assert.ok(receipt.busy.some(item => item.kind === 'recovery'));
  t.mock.timers.tick(5000); assert.equal(f.calls.created.length, 0); assert.equal(f.calls.model.length, 0);
  assert.deepEqual((await getState(f.id)).replyDraft, before.replyDraft);
  lease.resume(); lease.resume(); t.mock.timers.tick(0);
  await eventually(async () => (await f.store.listMessages(f.id)).messages.some(message => message.role === 'assistant'), 'saved recovery publication');
  const second = f.lease('after-cold-recovery'); assert.equal((await second.suspend()).quiet, true);
  t.mock.timers.tick(5000);
  assert.equal((await f.store.listMessages(f.id)).messages.filter(message => message.role === 'assistant').length, 1);
  assert.equal(f.calls.model.length, 0); assert.deepEqual(f.calls.cancelled, []);
});

test('a recovery already armed before hold may finish naturally and cannot be reported idle while queued', async t => {
  clock(t); const f = await fixture(t);
  await f.store.appendMessage(f.id, { id: 'armed-source', role: 'user', text: 'Synthetic armed input', status: 'received' });
  await prepareReply({ store: f.store, companionId: f.id, sourceMessageIds: ['armed-source'], parts: ['Synthetic armed response'] });
  await f.runtime.startInstance(f.id);
  const lease = f.lease('armed-recovery-window'); assert.equal((await lease.suspend()).quiet, false);
  t.mock.timers.tick(20);
  await eventually(async () => (await f.store.listMessages(f.id)).messages.some(message => message.role === 'assistant'), 'accepted recovery completes');
  assert.equal((await lease.suspend()).quiet, true); assert.deepEqual(f.calls.cancelled, []);
});

test('new cold work recovery remains busy while held and resumes its existing Session only once', async t => {
  clock(t); const f = await fixture(t), taskId = 'cold-work-task', sessionId = 'cold-work-session';
  await f.store.appendMessage(f.id, { id: taskId, role: 'assistant', type: 'task', text: 'Synthetic cold work',
    task: { id: taskId, sessionId, title: 'Synthetic cold work', goal: 'Synthetic goal', state: 'queued', summary: '' }, status: 'sent' });
  await f.store.bindSession(f.id, { sessionId, kind: 'work', taskId, workspaceId: await f.runtime.prepareWorkspace(f.id) });
  const lease = f.lease('cold-work-window'); assert.equal((await lease.suspend()).quiet, false);
  await f.runtime.startInstance(f.id);
  const receipt = await lease.suspend(); assert.equal(receipt.quiet, false); assert.ok(receipt.busy.some(item => item.kind === 'recovery'));
  t.mock.timers.tick(5000); assert.equal(f.calls.created.length, 0);
  assert.equal((await f.store.getMessage(f.id, taskId)).task.state, 'queued');
  lease.resume(); lease.resume(); t.mock.timers.tick(0);
  await eventually(() => f.agents.get(sessionId)?.deliveries.length, 'same cold work Session resumes');
  assert.equal(f.calls.created.length, 1); assert.equal(f.agents.get(sessionId).deliveries.length, 1);
  t.mock.timers.tick(5000); assert.equal(f.agents.get(sessionId).deliveries.length, 1); assert.deepEqual(f.calls.cancelled, []);
});

test('explicit user cancellation retires a deferred restored wait without a stale wake on resume', async t => {
  clock(t); const f = await fixture(t);
  await f.store.updateState(f.id, { waitingUntil: new Date(NOW + 100).toISOString(),
    pendingIntent: { kind: 'wait', sessionId: 'unopened-wait', sourceMessageIds: [] } });
  const lease = f.lease('cancel-deferred-wait'); await lease.suspend(); await f.runtime.startInstance(f.id);
  assert.equal((await lease.suspend()).quiet, false); t.mock.timers.tick(1000); assert.equal(f.calls.created.length, 0);
  await f.runtime.cancel(f.id);
  assert.equal((await lease.suspend()).quiet, true); assert.equal((await f.store.getState(f.id)).waitingUntil, null);
  lease.resume(); t.mock.timers.tick(10000); assert.equal(f.calls.created.length, 0); assert.deepEqual(f.calls.cancelled, []);
});

test('stored waiting and work obligations without a live Agent remain busy and are never cleared for maintenance', async t => {
  clock(t); const f = await fixture(t), waiting = { waitingUntil: new Date(NOW + 60000).toISOString(),
    pendingIntent: { kind: 'wait', sessionId: 'unopened-synthetic-chat', sourceMessageIds: [] } };
  await f.store.updateState(f.id, waiting);
  const lease = f.lease('stored-wait-window'), receipt = await lease.suspend();
  assert.equal(receipt.quiet, false); assert.ok(receipt.busy.some(item => item.kind === 'stored-wait'));
  assert.deepEqual((await f.store.getState(f.id)).pendingIntent, waiting.pendingIntent); assert.equal(f.calls.created.length, 0);
  lease.resume();
  const taskId = 'stored-work-task', sessionId = 'stored-work-session';
  await f.store.updateState(f.id, { waitingUntil: null, pendingIntent: null });
  await f.store.appendMessage(f.id, { id: taskId, role: 'assistant', type: 'task', text: 'Synthetic queued work',
    task: { id: taskId, sessionId, title: 'Synthetic work', goal: 'Synthetic goal', state: 'queued', summary: '' }, status: 'sent' });
  await f.store.bindSession(f.id, { sessionId, kind: 'work', taskId, workspaceId: await f.runtime.prepareWorkspace(f.id) });
  const second = f.lease('stored-work-window'); const result = await second.suspend();
  assert.equal(result.quiet, false); assert.ok(result.busy.some(item => item.kind === 'work-task'));
  assert.equal((await f.store.getMessage(f.id, taskId)).task.state, 'queued'); assert.deepEqual(f.calls.cancelled, []);
});

test('future writers invalidate prior receipts, aborted waiters keep the gate, and stale leases cannot release a new owner', async t => {
  clock(t); const f = await fixture(t), lease = f.lease('first-window');
  assert.equal((await lease.suspend()).quiet, true); assert.equal(f.lease('first-window'), lease);
  assert.throws(() => f.lease('conflicting-window'), code('COMPANION_MAINTENANCE_CONFLICT'));
  await f.runtime.submit(f.id, { clientId: 'later-writer', text: 'Synthetic accepted continuation' });
  assert.equal(lease.status().quiet, false);
  const agent = await f.runtime.agent(f.id); await f.runtime.flush(f.id);
  const controller = new AbortController(), reason = new Error('Synthetic cancelled observer');
  const observing = lease.suspend({ signal: controller.signal }); controller.abort(reason);
  await assert.rejects(observing, error => error === reason); assert.equal(lease.status().producersSuspended, true);
  const waiting = lease.suspend(); lease.resume(); assert.equal((await waiting).phase, 'released');
  assert.equal(agent.status, 'running'); assert.deepEqual(f.calls.cancelled, []);
  const second = f.lease('second-window'), next = second.suspend(); lease.resume(); await lease.suspend();
  assert.equal(second.status().producersSuspended, true);
  const owner = await f.store.sessionOwner(agent.id), finishing = f.runtime.finishTurn(owner, { data: { reason: { kind: 'completed' } } });
  agent.retire(); await finishing; assert.equal((await next).quiet, true);
});

test('unknown observations, failed public retirement and stopped runtime cannot yield a successful quiet receipt', async t => {
  const f = await fixture(t), lease = f.lease('observation-window'), profiles = f.store.profiles.bind(f.store);
  f.store.profiles = async () => { throw new Error('Synthetic private storage failure'); };
  await assert.rejects(lease.suspend(), code('COMPANION_MAINTENANCE_OBSERVATION_FAILED')); assert.equal(lease.status().quiet, false);
  f.store.profiles = profiles; assert.equal((await lease.suspend()).quiet, true);
  const agent = await f.runtime.agent(f.id); await eventually(() => f.runtime.chatExecution(f.id).quiescent, 'initial idle observation');
  const whenIdle = agent.whenIdle;
  agent.whenIdle = async () => { throw new Error('Synthetic idle observation failure'); };
  await assert.rejects(lease.suspend(), code('COMPANION_MAINTENANCE_OBSERVATION_FAILED')); assert.equal(lease.status().quiet, false);
  agent.whenIdle = whenIdle; assert.equal((await lease.suspend()).quiet, true);
  await f.close(); assert.equal(lease.status().phase, 'stopped'); assert.equal(lease.status().quiet, false);
  assert.throws(() => lease.suspend(), code('COMPANION_MAINTENANCE_STOPPED'));
});

test('resolved instance and workspace promises are not active work; invalid or pre-aborted leases have no producer side effect', async t => {
  clock(t); const f = await fixture(t);
  await f.runtime.prepareWorkspace(f.id); await f.runtime.startInstance(f.id);
  assert.ok(f.runtime.workspaceStarts.size); assert.ok(f.runtime.instanceStarts.size);
  for (const options of [undefined, null, [], {}, { operationId: '../unsafe' }, { operationId: 'valid', extra: true }])
    assert.throws(() => createCompanionMaintenanceLease(f.runtime, options), code('COMPANION_MAINTENANCE_INVALID'));
  assert.throws(() => createCompanionMaintenanceLease({}, { operationId: 'valid' }), code('COMPANION_MAINTENANCE_NOT_READY'));
  const lease = f.lease('strict-window'), controller = new AbortController(), reason = new Error('Synthetic pre-aborted observer');
  controller.abort(reason);
  assert.throws(() => lease.suspend({ signal: controller.signal }), error => error === reason);
  assert.equal(lease.status().phase, 'ready'); assert.equal(lease.status().producersSuspended, false);
  for (const options of [null, [], { extra: true }, { signal: {} }])
    assert.throws(() => lease.suspend(options), code('COMPANION_MAINTENANCE_INVALID'));
  assert.equal((await lease.suspend()).quiet, true);
});

test('removed work cards are inert under the real resume guard, while unknown stored task state is not assumed idle', async t => {
  const f = await fixture(t), taskId = 'removed-work-card', sessionId = 'removed-work-session';
  await f.store.appendMessage(f.id, { id: taskId, role: 'assistant', type: 'task', text: 'Synthetic work card', status: 'sent',
    task: { id: taskId, sessionId, title: 'Synthetic work', goal: 'Synthetic goal', state: 'queued', summary: '' } });
  await f.store.bindSession(f.id, { sessionId, kind: 'work', taskId, workspaceId: await f.runtime.prepareWorkspace(f.id) });
  const lease = f.lease('removed-card-window'); assert.equal((await lease.suspend()).quiet, false);
  const task = (await f.store.getMessage(f.id, taskId)).task;
  await f.store.updateMessage(f.id, taskId, { task: { ...task, state: 'unknown-state' } });
  await assert.rejects(lease.suspend(), code('COMPANION_MAINTENANCE_OBSERVATION_FAILED'));
  await f.store.removeMessage(f.id, taskId);
  assert.equal((await lease.suspend()).quiet, true);
  await f.runtime.resumeWork(await f.store.sessionOwner(sessionId));
  assert.equal(f.calls.created.length, 0); assert.equal((await lease.suspend()).quiet, true);
});

test('outside a hold, restored chat timers keep their original liveness while passive timers remain unreferenced', async t => {
  const f = await fixture(t);
  await f.store.appendMessage(f.id, { id: 'ref-source', role: 'user', text: 'Synthetic ref input', status: 'received' });
  await prepareReply({ store: f.store, companionId: f.id, sourceMessageIds: ['ref-source'], parts: ['Synthetic ref response'] });
  f.runtime.options.debounceMs = 60000; await f.runtime.startInstance(f.id);
  assert.equal(f.runtime.state(f.id).timer.hasRef(), true);
  assert.equal(f.runtime.state(f.id).proactiveTimer.hasRef(), false);
  f.runtime.scheduleWait(f.id, { waitingUntil: new Date(Date.now() + 60000).toISOString(), pendingIntent: { kind: 'wait' } });
  assert.equal(f.runtime.state(f.id).waitTimer.hasRef(), false);
});
