// Genuine packaged DSH loop, tools, goals and JSONL. Inference is a local scripted fixture.
import test from 'node:test';
import assert from 'node:assert/strict';
import { createRequire, registerHooks } from 'node:module';
import { pathToFileURL } from 'node:url';
import { mkdtemp, rm } from 'node:fs/promises';
import { join } from 'node:path';
import { tmpdir } from 'node:os';
import { setTimeout as delay } from 'node:timers/promises';
import { createTaskControl } from '../task-control.mjs';
import { TaskControlStore } from '../task-control-store.mjs';
import { createExecutionProgress } from '../execution-progress.mjs';

const installed = createRequire(new URL('../../dsh/package.json', import.meta.url));
const hook = registerHooks({ resolve(specifier, context, next) {
  if (specifier === 'zod' || specifier.startsWith('@deepseek-ai/')) return { url: pathToFileURL(installed.resolve(specifier)).href, shortCircuit: true };
  return next(specifier, context);
} });
const { Context } = await import('@deepseek-ai/cordis');
const { default: LlmRuntime, LlmAdapter, createUserMessage } = await import('@deepseek-ai/dsh-llm');
const { default: SessionStore } = await import('@deepseek-ai/dsh-session');
const { default: Projections } = await import('@deepseek-ai/dsh-session-projection');
const { default: Prompt } = await import('@deepseek-ai/dsh-system-prompt');
const { default: Tools, defineTool } = await import('@deepseek-ai/dsh-tools');
const { default: Agents } = await import('@deepseek-ai/dsh-agent');
const { default: Loop } = await import('@deepseek-ai/dsh-agent-loop');
const { default: Goals } = await import('@deepseek-ai/dsh-goal');
const Jsonl = await import('@deepseek-ai/dsh-session-persistence-jsonl');
const GoalDriver = await import('@deepseek-ai/dsh-goal-round-driver');
const { createTaskControlEngine } = await import('../task-control-engine.mjs');
hook.deregister();

const message = (value, kind = 'user') => createUserMessage({ content: [{ type: 'text', text: value }], source: { kind } });
const textChunks = value => [
  { type: 'block-start', index: 0, blockType: 'text' }, { type: 'text-delta', index: 0, text: value },
  { type: 'block-end', index: 0, block: { type: 'text', text: value } }, { type: 'finish', reason: { kind: 'stop' } },
];
const deferred = () => Promise.withResolvers();
async function eventually(predicate, label) {
  for (let index = 0; index < 500; index++) { if (await predicate()) return; await delay(5); }
  assert.fail(`Not settled: ${label}`);
}

async function harness(t, options = {}) {
  const home = options.home ?? await mkdtemp(join(tmpdir(), 'droy-pause-sdk-')), ctx = new Context(), requests = [], errors = [], events = [];
  await ctx.plugin(LlmRuntime); await ctx.plugin(SessionStore); await ctx.plugin(Projections);
  await ctx.plugin(Prompt); await ctx.plugin(Tools, { mode: 'native' }); await ctx.plugin(Agents);
  await ctx.plugin(Jsonl, { root: join(home, 'sessions'), compression: 'none' });
  await ctx.plugin(Loop, { agents: [] }); await ctx.plugin(Goals);
  if (options.goalDriver) await ctx.plugin(GoalDriver);
  ctx.provide('sessionController', { async resolveAgent(id) { return { agent: ctx.agents.get(id) }; } });
  class LocalModel extends LlmAdapter {
    async resolveModel(provider, id) { return { provider, id, name: 'Local pause regression', inputModalities: ['text'] }; }
    async *stream(input) {
      requests.push(input);
      const result = options.model ? await options.model(input, requests) : textChunks('Fictional result.');
      for (const chunk of result) { input.signal.throwIfAborted(); yield chunk; }
    }
  }
  ctx.llm.registerAdapter(['pause-mock'], new LocalModel());
  const payloads = [], progress = createExecutionProgress(async row => payloads.push(row), () => errors.push('PROGRESS_FAILED'), undefined, () => 'en');
  ctx.on('agent/error', event => errors.push(event.error));
  ctx.on('session/event', (session, event) => { events.push({ sessionId: session.id, ...event }); progress.sessionEvent(session, event); });
  ctx.on('agent/status', ({ agent, status }) => progress.status(agent, status));
  const store = new TaskControlStore(join(home, 'controls'));
  options.seedControls?.(store);
  let control;
  const engine = createTaskControlEngine(ctx, { held: id => control?.held(id) ?? false,
    retainInbox: id => control?.preserveInbox(id) ?? false,
    onCreated: agent => control?.onCreated(agent), onWake: (agent, input) => control?.onWake(agent, input) });
  control = createTaskControl({ store, engine, native: async () => assert.fail('Pure model regressions must not request phone access'),
    onChange: (taskId, state) => progress.control(taskId, state), onBind: (taskId, state) => progress.bind(taskId, state),
    reportError: code => errors.push(code), getLocale: () => 'en', settleTimeoutMs: 3000, pollMs: 5 });
  if (options.resume) control.recover();
  const create = async (id, parentAgent) => (await ctx.agents.create({ sessionId: id, ...(parentAgent ? { parentAgent } : {}),
    agentOptions: { provider: 'pause-mock', model: 'scripted' } })).agent;
  const agent = options.resume ? (await ctx.agents.resume({ resumeSessionId: 'sdk-task-a',
    agentOptions: { provider: 'pause-mock', model: 'scripted' } })).agent : await create('sdk-task-a');
  const other = await create(options.resume ? 'sdk-task-b-new-context' : 'sdk-task-b');
  let closed = false;
  const close = async () => {
    if (closed) return; closed = true;
    await control.dispose(); await ctx.fiber.dispose(); progress.dispose(); store.close();
    if (!options.home) await rm(home, { recursive: true, force: true });
  };
  t.after(close);
  return { ctx, control, engine, agent, other, create, requests, errors, events, payloads, home, close,
    pause() { return control.command({ taskId: agent.id, action: 'pause', expectedRevision: control.revision(agent.id) }); },
    resume() { return control.command({ taskId: agent.id, action: 'resume', expectedRevision: control.revision(agent.id) }); },
    stop() { return control.command({ taskId: agent.id, action: 'stop', expectedRevision: control.revision(agent.id) }); } };
}

test('real SDK abort preserves session context, parks all waking sends and rejects tool dispatch while another task runs', async t => {
  let first = true;
  const f = await harness(t, { model: async input => {
    if (input.sessionId === 'sdk-task-a' && first) {
      first = false;
      await new Promise((_, reject) => input.signal.addEventListener('abort', () => reject(input.signal.reason), { once: true }));
    }
    return textChunks('Local completed response.');
  } });
  let physicalWrites = 0;
  f.ctx.tools.register(defineTool({ name: 'fictional_write', description: 'Test-only controlled write', parameters: {},
    output: { schema: { type: 'json' }, render: () => [] }, async execute() { physicalWrites++; return {}; } }));
  const original = message('Preserve this original task context.');
  f.agent.followup(original);
  await eventually(() => f.requests.length === 1, 'initial model request');
  f.pause(); assert.equal(f.control.held(f.agent.id), true);
  assert.equal((await f.control.settled(f.agent.id)).phase, 'paused');
  assert.equal(f.agent.inbox.nextTurn.some(item => item.id === original.id), false, 'Committed prompts are not duplicated');
  const queued = message('An explicit user message while paused must remain queued.');
  const schedule = message('A scheduled reminder while paused must remain queued.', 'schedule');
  f.agent.followup(queued); f.agent.steer(schedule);
  const before = f.requests.length;
  await delay(30);
  assert.equal(f.requests.length, before);
  assert.ok(f.agent.inbox.nextTurn.some(item => item.id === queued.id));
  assert.ok(f.agent.inbox.nextStep.some(item => item.id === schedule.id));
  const denied = await f.ctx.tools.execute({ agent: f.agent, callId: 'paused-direct-call', name: 'fictional_write', arguments: {}, signal: new AbortController().signal });
  assert.equal(denied.isError, true); assert.equal(physicalWrites, 0);
  f.other.followup(message('Unrelated task continues.')); await f.other.whenIdle();
  assert.ok(f.requests.some(request => request.sessionId === f.other.id));
  f.resume(); assert.equal((await f.control.settled(f.agent.id)).phase, 'running');
  await f.agent.whenIdle();
  const resumed = f.requests.find(request => request.sessionId === f.agent.id && request !== f.requests[0]);
  const text = JSON.stringify(resumed.messages);
  assert.match(text, /Preserve this original task context/);
  assert.match(text, /do not blindly replay/);
  assert.match(text, /scheduled reminder/);
  assert.equal(f.agent.session.snapshotEvents().filter(event => event.type === 'user/message' && event.data.id === original.id).length, 1);
  assert.equal(f.agent.session.snapshotEvents().filter(event => event.type === 'user/message' && event.data.id === queued.id).length, 1);
  assert.deepEqual(f.errors, []);
});

test('a pause during real pre-step restores claimed input once and stop terminates the parked inbox', async t => {
  const f = await harness(t), entered = deferred(), release = deferred(); let first = true;
  f.ctx.on('agent/pre-step', async ({ agent }, next) => {
    if (agent === f.agent && first) { first = false; entered.resolve(); await release.promise; }
    return next();
  });
  const prompt = message('This prompt is claimed but has not reached the model.');
  f.agent.followup(prompt); await entered.promise;
  f.pause(); release.resolve();
  assert.equal((await f.control.settled(f.agent.id)).phase, 'paused');
  assert.equal(f.requests.length, 0);
  assert.equal([...f.agent.inbox.nextStep, ...f.agent.inbox.nextTurn].filter(item => item.id === prompt.id).length, 1);
  f.stop(); assert.equal((await f.control.settled(f.agent.id)).phase, 'stopped');
  assert.equal(f.agent.inbox.hasPending, false);
  f.agent.followup(message('A post-stop reminder.', 'schedule')); await delay(20);
  assert.equal(f.requests.length, 0);
  const stoppedRevision = f.control.revision(f.agent.id);
  f.agent.followup(message('A genuinely new user task.')); await f.agent.whenIdle();
  assert.equal(f.control.get(f.agent.id).phase, 'running');
  assert.ok(f.control.revision(f.agent.id) > stoppedRevision);
  assert.ok(f.requests.length > 0);
  assert.ok(!JSON.stringify(f.requests).includes('This prompt is claimed'));
  assert.ok(!JSON.stringify(f.requests).includes('A post-stop reminder'));
  assert.deepEqual(f.errors, []);
});

test('pause covers real runtime-owned children without freezing another root', async t => {
  const f = await harness(t, { model: async input => {
    if (input.sessionId === 'sdk-child') await new Promise((_, reject) => input.signal.addEventListener('abort', () => reject(input.signal.reason), { once: true }));
    return textChunks('Fictional result.');
  } });
  const child = await f.create('sdk-child', f.agent);
  child.followup(message('Child work.')); await eventually(() => f.requests.length === 1, 'child request');
  f.pause();
  assert.equal((await f.control.settled(f.agent.id)).phase, 'paused');
  assert.equal(child.status, 'idle');
  assert.equal(f.control.held(child.id), true);
  child.followup(message('Child follow-up.')); await delay(20);
  assert.equal(f.requests.length, 1);
  f.other.followup(message('Another root.')); await f.other.whenIdle();
  assert.equal(f.requests.length, 2);
  const bornWhilePaused = await f.create('sdk-new-child', f.agent);
  bornWhilePaused.followup(message('A newly materialized child cannot escape.')); await delay(20);
  assert.equal(f.requests.length, 2);
  assert.equal(f.control.held(bornWhilePaused.id), true);
  assert.deepEqual(f.errors, []);
});

test('goal disarming preserves the exact objective and resume cannot override a later user goal pause', async t => {
  const f = await harness(t, { goalDriver: true, model: async input => {
    if (input.sessionId === 'sdk-task-a' && !input.messages.some(message => JSON.stringify(message).includes('do not blindly replay')))
      await new Promise((_, reject) => input.signal.addEventListener('abort', () => reject(input.signal.reason), { once: true }));
    return textChunks('Fictional resumed result.');
  } });
  f.ctx.goals.create(f.agent, { objective: 'Keep the existing objective.', maxGoalRounds: 3 });
  await eventually(() => f.requests.length === 1, 'goal round');
  const original = f.ctx.goals.get(f.agent);
  f.pause(); await f.control.settled(f.agent.id);
  const paused = f.ctx.goals.get(f.agent);
  assert.equal(paused.id, original.id); assert.equal(paused.revision, original.revision);
  assert.equal(paused.phase, 'active'); assert.equal(paused.activation, 'disarmed');
  f.ctx.goals.pause(f.agent, { id: paused.id, revision: paused.revision });
  const explicitlyPaused = f.ctx.goals.get(f.agent);
  f.resume(); await f.control.settled(f.agent.id); await f.agent.whenIdle();
  assert.equal(f.ctx.goals.get(f.agent).phase, 'paused');
  assert.equal(f.ctx.goals.get(f.agent).revision, explicitlyPaused.revision);
  assert.equal(f.ctx.goals.get(f.agent).activation, 'disarmed');
  assert.equal(f.ctx.goals.get(f.agent).objective, original.objective);
  assert.deepEqual(f.errors, []);
});

test('resuming a family explicitly wakes its live child with preserved queued input and resume context', async t => {
  let first = true;
  const f = await harness(t, { model: async input => {
    if (input.sessionId === 'sdk-child' && first) {
      first = false;
      await new Promise((_, reject) => input.signal.addEventListener('abort', () => reject(input.signal.reason), { once: true }));
    }
    return textChunks('Fictional family continuation.');
  } });
  const child = await f.create('sdk-child', f.agent);
  child.followup(message('Original child context.')); await eventually(() => f.requests.length === 1, 'child model');
  f.pause(); await f.control.settled(f.agent.id);
  const pending = message('Child work queued during pause.'); child.followup(pending);
  const before = f.requests.length; await delay(20); assert.equal(f.requests.length, before);
  f.resume(); await f.control.settled(f.agent.id); await child.whenIdle(); await f.agent.whenIdle();
  const resumed = f.requests.filter(input => input.sessionId === child.id).slice(1);
  assert.ok(resumed.length > 0);
  assert.match(JSON.stringify(resumed), /do not blindly replay/);
  assert.match(JSON.stringify(resumed), /Original child context/);
  assert.equal(child.session.snapshotEvents().filter(event => event.type === 'user/message' && event.data.id === pending.id).length, 1);
  assert.deepEqual(f.errors, []);
});

for (const childPhase of ['running', 'paused']) test(`held parent owns a child with a previous ${childPhase} control record`, async t => {
  const f = await harness(t, { seedControls(store) {
    store.put({ taskId: 'sdk-child', phase: childPhase, revision: 1, members: ['sdk-child'], commandId: 'earlier-child-control', goals: {} }, 0);
  }, model: async input => {
    if (input.sessionId === 'sdk-task-a' || input.sessionId === 'sdk-child') {
      await new Promise((_, reject) => input.signal.addEventListener('abort', () => reject(input.signal.reason), { once: true }));
    }
    return textChunks('Fictional child response.');
  } });
  const child = await f.create('sdk-child', f.agent);
  if (childPhase === 'running') {
    child.followup(message('Earlier child work.')); await eventually(() => f.requests.length === 1, 'earlier child');
  }
  f.agent.followup(message('The parent task now runs.')); await eventually(() => f.agent.status === 'running' && f.requests.some(input => input.sessionId === f.agent.id), 'parent model');
  f.pause(); await f.control.settled(f.agent.id);
  assert.equal(f.control.get(child.id).taskId, f.agent.id);
  assert.equal(f.control.held(child.id), true);
  assert.throws(() => f.control.command({ taskId: child.id, action: 'resume', expectedRevision: f.control.revision(child.id) }), { code: 'TASK_CONTROL_PARENT_OWNS_TASK' });
  const before = f.requests.length; child.followup(message('This must remain parked.')); await delay(20);
  assert.equal(f.requests.length, before);
  assert.deepEqual(f.errors, []);
});

test('children bind to the real root at revision zero and a newly created child after resume receives its latest revision', async t => {
  const f = await harness(t, { model: async input => {
    if (input.sessionId === 'sdk-after-resume')
      await new Promise((_, reject) => input.signal.addEventListener('abort', () => reject(input.signal.reason), { once: true }));
    return textChunks('Fictional root binding result.');
  } });
  const firstChild = await f.create('sdk-first-child', f.agent);
  assert.equal(f.control.get(firstChild.id).taskId, f.agent.id);
  assert.equal(f.control.get(firstChild.id).controlRevision, 0);
  assert.throws(() => f.control.command({ taskId: firstChild.id, action: 'pause', expectedRevision: 0 }), { code: 'TASK_CONTROL_PARENT_OWNS_TASK' });
  firstChild.followup(message('First child work.')); await firstChild.whenIdle();
  await eventually(() => f.payloads.some(row => row.taskId === firstChild.id), 'initial child progress');
  assert.ok(f.payloads.filter(row => row.taskId === firstChild.id).every(row => row.controlTaskId === f.agent.id && row.controlRevision === 0));
  f.agent.followup(message('Parent work before the pause.'));
  f.pause(); await f.control.settled(f.agent.id); f.resume(); await f.control.settled(f.agent.id); await f.agent.whenIdle();
  const nextChild = await f.create('sdk-after-resume', f.agent), revision = f.control.revision(f.agent.id);
  assert.equal(f.control.get(nextChild.id).controlRevision, revision);
  nextChild.followup(message('New child after resuming.'));
  await eventually(() => f.requests.some(row => row.sessionId === nextChild.id), 'new child request');
  assert.ok(f.payloads.filter(row => row.taskId === nextChild.id).every(row => row.controlTaskId === f.agent.id && row.controlRevision === revision));
  const accepted = f.control.command({ taskId: f.control.get(nextChild.id).taskId, action: 'pause', expectedRevision: revision });
  assert.equal(accepted.control.phase, 'pausing'); await f.control.settled(f.agent.id);
  assert.deepEqual(f.errors, []);
});

test('a new real SDK context loads paused SQLite and JSONL without automatic replay, retaining every parked message once', async t => {
  const home = await mkdtemp(join(tmpdir(), 'droy-pause-restart-'));
  t.after(() => rm(home, { recursive: true, force: true }));
  const first = await harness(t, { home, model: async input => {
    await new Promise((_, reject) => input.signal.addEventListener('abort', () => reject(input.signal.reason), { once: true }));
  } });
  const original = message('Original cross-process context.'); first.agent.followup(original);
  await eventually(() => first.requests.length === 1, 'first request');
  first.pause(); await first.control.settled(first.agent.id);
  const queued = message('The next user prompt remains parked.');
  const scheduled = message('The next reminder remains parked.', 'schedule');
  first.agent.followup(queued); first.agent.steer(scheduled);
  await first.ctx.sessions.flush(first.agent.session); await first.ctx.sessionPersistence.flush();
  await first.close();
  let explicitlyResumed = false;
  const second = await harness(t, { home, resume: true, model: async () => {
    assert.equal(explicitlyResumed, true, 'Loading persisted inboxes is not permission to execute');
    return textChunks('Fictional resumed result.');
  } });
  await second.control.settled(second.agent.id); await delay(30);
  assert.equal(second.requests.length, 0);
  assert.equal(second.control.get(second.agent.id).phase, 'paused');
  for (const saved of [queued, scheduled])
    assert.equal([...second.agent.inbox.nextTurn, ...second.agent.inbox.nextStep].filter(item => item.id === saved.id).length, 1);
  explicitlyResumed = true; second.resume(); await second.control.settled(second.agent.id); await second.agent.whenIdle();
  assert.match(JSON.stringify(second.requests), /Original cross-process context/);
  for (const saved of [original, queued, scheduled])
    assert.equal(second.agent.session.snapshotEvents().filter(event => event.type === 'user/message' && event.data.id === saved.id).length, 1);
  assert.deepEqual(second.errors, []);
});

test('disposing during blocked prompt assembly preserves an uncommitted claimed prompt across real JSONL recovery', async t => {
  const home = await mkdtemp(join(tmpdir(), 'droy-pause-claimed-restart-'));
  t.after(() => rm(home, { recursive: true, force: true }));
  const first = await harness(t, { home }), entered = deferred(), release = deferred();
  first.ctx.on('system-prompt/assemble', async (_assembly, context, next) => {
    if (context.scope === first.agent) { entered.resolve(); await release.promise; }
    return next();
  });
  const claimed = message('Uncommitted input must survive pause and immediate host exit.');
  first.agent.followup(claimed); await entered.promise;
  first.pause();
  const closing = first.close();
  try {
    await eventually(() => [...first.agent.inbox.nextStep, ...first.agent.inbox.nextTurn].some(item => item.id === claimed.id), 'disposed claimed restoration');
    assert.equal(first.requests.length, 0);
  } finally { release.resolve(); await closing; }
  const second = await harness(t, { home, resume: true });
  await second.control.settled(second.agent.id);
  assert.equal(second.requests.length, 0);
  assert.equal([...second.agent.inbox.nextStep, ...second.agent.inbox.nextTurn].filter(item => item.id === claimed.id).length, 1);
  second.resume(); await second.control.settled(second.agent.id); await second.agent.whenIdle();
  assert.equal(second.agent.session.snapshotEvents().filter(event => event.type === 'user/message' && event.data.id === claimed.id).length, 1);
  assert.match(JSON.stringify(second.requests), /Uncommitted input must survive/);
  assert.deepEqual(second.errors, []);
});

for (const producerReported of [true, false]) test(`owned jobs require a terminal positive producer receipt (${producerReported}) and do not stop foreign jobs`, async t => {
  const f = await harness(t), done = deferred(), killed = [];
  const owned = { id: 'bash-owned-fixture', owner: f.agent.id, status: 'running' };
  const foreign = { id: 'bash-foreign-fixture', owner: f.other.id, status: 'running' };
  const unowned = { id: 'bash-unowned-fixture', status: 'running' };
  f.ctx.provide('jobs', {
    list: () => [owned, foreign, unowned],
    kill(id, owner) { assert.equal(id, owned.id); assert.equal(owner, f.agent.id); killed.push(id); owned.status = 'stopping'; },
    async wait(id, _timeout, owner, signal) {
      assert.equal(id, owned.id); assert.equal(owner, f.agent.id);
      await done.promise; signal.throwIfAborted(); owned.status = 'killed';
      f.agent.session.append('job/settled', { jobId: owned.id, owner, kind: 'bash', label: 'Fictional job', startedAt: 1,
        finishedAt: 2, status: 'killed', cause: 'kill', producerReported });
      return owned;
    },
  });
  f.agent.followup(message('Pause before this turn runs.')); f.pause();
  await eventually(() => killed.length > 0, 'owned job cancellation');
  assert.equal(f.control.get(f.agent.id).phase, 'pausing');
  assert.equal(foreign.status, 'running'); assert.equal(unowned.status, 'running');
  done.resolve(); await f.control.settled(f.agent.id);
  assert.equal(f.control.get(f.agent.id).phase, producerReported ? 'paused' : 'pausing');
  if (!producerReported) assert.equal(f.control.get(f.agent.id).errorCode, 'TASK_CONTROL_JOB_STOP_UNCONFIRMED');
});
