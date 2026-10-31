import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, rm } from 'node:fs/promises';
import { join } from 'node:path';
import { tmpdir } from 'node:os';
import { setTimeout as delay } from 'node:timers/promises';
import { TaskControlStore } from '../task-control-store.mjs';
import { createTaskControl, resumePrompt } from '../task-control.mjs';
import { createBridge } from '../bridge.mjs';

const deferred = () => Promise.withResolvers();
async function eventually(predicate) {
  for (let index = 0; index < 300; index++) { if (predicate()) return; await delay(5); }
  assert.fail('Condition did not settle');
}
const failure = code => Object.assign(new Error(code), { code });

// Native execution is a deterministic local contract fixture, never a phone or paid model.
async function fixture(t, options = {}) {
  const directory = options.directory ?? await mkdtemp(join(tmpdir(), 'droy-task-control-'));
  const store = new TaskControlStore(directory), nativeStates = new Map(), calls = [], changes = [], errors = [], trace = [];
  const agents = new Map(['task-a', 'task-b', ...(options.child ? ['child-a'] : [])].map(id => [id,
    { id, status: 'running', cancel() { this.status = 'idle'; } }]));
  let control, disposed = false;
  const engine = {
    get: id => agents.get(id), rootId: id => id === 'child-a' ? 'task-a' : id,
    liveMembers: id => [...agents.values()].filter(agent => agent.id === id || options.child && id === 'task-a' && agent.id === 'child-a'),
    descendants: async () => options.rows?.() ?? [],
    interruptExternal() { trace.push('interrupt-external'); },
    goalSnapshot: () => null,
    disarm(agent) { trace.push(`disarm:${agent.id}`); },
    stopGoal(agent) { trace.push(`stop-goal:${agent.id}`); },
    async settle(members, input) {
      trace.push(`settle:${input.keepInbox}`);
      for (const agent of members) agent.cancel();
      if (options.settle) await options.settle(members, input);
      input.signal.throwIfAborted();
      trace.push('flushed');
    },
    resolve: async id => agents.get(id),
    isObservationCall: (id, callId) => engine.observation?.id === id && engine.observation.callId === callId,
    async observe(agent, callId, signal) {
      trace.push(`observe:${agent.id}`);
      engine.observation = { id: agent.id, callId };
      try {
        await (engine.control ?? control).admitNative({ taskId: agent.id, callId, action: 'observe', signal });
        if (options.observe) await options.observe(agent, signal);
        const state = nativeStates.get(agent.id);
        state.needsObservation = false;
        return [{ type: 'text', text: 'Fresh fictional observation; no private screen data.' }];
      } finally { engine.observation = undefined; }
    },
    wake(agent, content) { trace.push(`wake:${agent.id}`); engine.woken = { agent, content }; (engine.wakes ??= []).push(engine.woken); },
    rearmGoal(agent) { trace.push(`rearm:${agent.id}`); },
    dispose() { disposed = true; },
  };
  async function native(input, signal) {
    calls.push(structuredClone(input)); trace.push(`native:${input.operation}:${input.taskId}`);
    let state = nativeStates.get(input.taskId);
    if (options.nativeBefore) await options.nativeBefore(input, signal);
    if (input.operation === 'open') {
      if (!state || state.operationId !== input.operationId) state = { taskId: input.taskId, operationId: input.operationId, nativeInstanceId: options.nativeInstanceId?.() ?? 'native-instance-a',
        epoch: 1, pauseId: '', phase: 'active', quiescent: true, pending: [], paused: false, outcomeUnknown: false, needsObservation: false };
      nativeStates.set(input.taskId, state);
    } else {
      if (!state) throw Object.assign(failure('TASK_SCOPE_REQUIRED'), { details: { nativeInstanceId: options.nativeInstanceId?.() ?? 'native-instance-a',
        scopeRestorationAllowed: options.scopeRestorationAllowed === true } });
      if (input.operation !== 'status') {
        if (input.expectedEpoch !== state.epoch && !(input.operation === 'pause' && input.pauseId === state.pauseId))
          throw failure('TASK_CONTROL_CONFLICT');
        if (input.operation === 'pause') {
          if (state.phase !== 'paused') state.epoch++;
          Object.assign(state, { phase: 'paused', paused: true, pauseId: input.pauseId || 'fixture-pause' });
        } else if (input.operation === 'resume') {
          assert.equal(state.phase, 'paused'); assert.equal(input.pauseId, state.pauseId);
          Object.assign(state, { phase: 'observing', epoch: state.epoch + 1, paused: false });
        } else if (input.operation === 'activate') {
          assert.equal(state.needsObservation, false, 'A write epoch cannot open before observation');
          Object.assign(state, { phase: 'active', epoch: state.epoch + 1 });
        } else if (input.operation === 'stop') Object.assign(state, { phase: 'stopped', paused: false, epoch: state.epoch + 1 });
      }
    }
    if (options.nativeAfter) await options.nativeAfter(input, state, signal);
    return structuredClone(state);
  }
  control = createTaskControl({ store, engine, native, isManaged: id => id === 'managed-task', getLocale: () => options.locale ?? 'en',
    onChange: (id, state) => changes.push({ id, ...state }), reportError: code => errors.push(code),
    settleTimeoutMs: options.timeout ?? 2000, pollMs: 5 });
  t.after(async () => {
    await control.dispose(); store.close();
    if (!options.directory) await rm(directory, { recursive: true, force: true });
  });
  return { control, store, directory, engine, agents, native, nativeStates, calls, changes, errors, trace, disposed: () => disposed,
    admit: id => control.admitNative({ taskId: id, callId: 'fixture-call', action: 'tap', signal: new AbortController().signal }),
    pause: (id = 'task-a') => control.command({ taskId: id, action: 'pause', expectedRevision: control.revision(id) }),
    resume: (id = 'task-a') => control.command({ taskId: id, action: 'resume', expectedRevision: control.revision(id) }),
    stop: (id = 'task-a') => control.command({ taskId: id, action: 'stop', expectedRevision: control.revision(id) }) };
}

test('pause persists its fence before aborting, waits for both drivers and native work, and isolates another task', async t => {
  const driver = deferred(); let nativeQuiet = false;
  const f = await fixture(t, { settle: async (_members, { signal }) => {
    await Promise.race([driver.promise, new Promise((_, reject) => signal.addEventListener('abort', () => reject(signal.reason), { once: true }))]);
  }, nativeAfter(input, state) {
    if (input.operation === 'pause' || input.operation === 'status') Object.assign(state,
      { quiescent: nativeQuiet, pending: nativeQuiet ? [] : [{ kind: 'request', id: 'old-call', callId: 'old-call', dispatched: true }] });
  } });
  await f.admit('task-a');
  const accepted = f.pause();
  assert.equal(accepted.control.phase, 'pausing');
  assert.equal(f.store.get('task-a').phase, 'pausing');
  assert.equal(f.control.held('task-a'), true);
  await assert.rejects(f.admit('task-a'), { code: 'TASK_CONTROL_HELD' });
  assert.equal(f.control.held('task-b'), false);
  await f.admit('task-b');
  await eventually(() => f.calls.some(call => call.operation === 'pause'));
  driver.resolve(); await delay(15);
  assert.equal(f.control.get('task-a').phase, 'pausing');
  nativeQuiet = true;
  assert.equal((await f.control.settled('task-a')).phase, 'paused');
  assert.deepEqual(f.errors, []);
  assert.equal(f.agents.get('task-b').status, 'running');
  assert.ok(f.changes.every((change, index) => index === 0 || change.controlRevision >= f.changes[index - 1].controlRevision));
});

test('CAS rejects stale controls, invalid revisions and managed sessions; accepted responses do not wait for stop', async t => {
  const f = await fixture(t);
  f.pause();
  assert.throws(() => f.control.command({ taskId: 'task-a', action: 'stop', expectedRevision: 0 }), { code: 'TASK_CONTROL_CHANGED' });
  for (const expectedRevision of [-1, 0.5, Number.MAX_SAFE_INTEGER + 1])
    assert.throws(() => f.control.command({ taskId: 'task-a', action: 'pause', expectedRevision }), { code: 'TASK_CONTROL_INVALID_INPUT' });
  assert.throws(() => f.control.command({ taskId: 'managed-task', action: 'pause', expectedRevision: 0 }), { code: 'TASK_CONTROL_SESSION_MANAGED' });
  await f.control.settled('task-a');
  const stop = f.stop(); assert.equal(stop.control.phase, 'stopping');
  assert.equal((await f.control.settled('task-a')).phase, 'stopped');
  assert.ok(f.trace.includes('settle:false'));
  assert.ok(f.trace.includes('stop-goal:task-a'));
});

test('phone resume observes in the same session before activation and keeps uncertain results explicit', async t => {
  const f = await fixture(t);
  await f.admit('task-a');
  Object.assign(f.nativeStates.get('task-a'), { needsObservation: true, outcomeUnknown: true });
  f.pause(); await f.control.settled('task-a');
  assert.equal(f.control.get('task-a').outcomeUnknown, true);
  await assert.rejects(f.admit('task-a'), { code: 'TASK_CONTROL_HELD' });
  f.resume();
  assert.equal((await f.control.settled('task-a')).phase, 'running');
  const observe = f.trace.indexOf('observe:task-a'), activate = f.trace.indexOf('native:activate:task-a'), wake = f.trace.indexOf('wake:task-a');
  assert.ok(observe >= 0 && activate > observe && wake > activate);
  assert.equal(f.engine.woken.agent, f.agents.get('task-a'));
  assert.match(f.engine.woken.content[0].text, /do not blindly replay/);
  assert.match(f.engine.woken.content[1].text, /Fresh fictional observation/);
  assert.equal(f.control.get('task-a').outcomeUnknown, true);
  assert.equal(f.nativeStates.get('task-a').phase, 'active');
  assert.deepEqual(f.errors, []);
});

test('pure model tasks resume without phone access and prompts follow the currently chosen language', async t => {
  for (const locale of ['zh', 'en']) {
    const f = await fixture(t, { locale });
    f.pause(); await f.control.settled('task-a');
    f.resume(); await f.control.settled('task-a');
    assert.deepEqual(f.calls, []);
    assert.equal(f.engine.woken.content[0].text, resumePrompt[locale]);
    assert.equal(f.engine.woken.agent.id, 'task-a');
  }
});

test('failed resume observation never activates writes or wakes the model; an explicit retry can recover', async t => {
  let failing = true;
  const f = await fixture(t, { observe: async () => { if (failing) throw failure('PRIVACY_ENDPOINT_NOT_AUTHORIZED'); } });
  await f.admit('task-a'); Object.assign(f.nativeStates.get('task-a'), { needsObservation: true });
  f.pause(); await f.control.settled('task-a');
  f.resume(); await f.control.settled('task-a');
  assert.equal(f.control.get('task-a').phase, 'resuming');
  assert.equal(f.control.get('task-a').errorCode, 'PRIVACY_ENDPOINT_NOT_AUTHORIZED');
  assert.equal(f.control.held('task-a'), true);
  assert.equal(f.engine.woken, undefined);
  assert.ok(!f.calls.some(call => call.operation === 'activate'));
  failing = false; f.resume();
  assert.equal((await f.control.settled('task-a')).phase, 'running');
  assert.equal(f.engine.woken.agent.id, 'task-a');
});

test('stop supersedes an in-flight resume and a delayed observation cannot wake or activate it', async t => {
  const observation = deferred();
  const f = await fixture(t, { observe: async (_agent, signal) => {
    await Promise.race([observation.promise, new Promise((_, reject) => signal.addEventListener('abort', () => reject(signal.reason), { once: true }))]);
  } });
  await f.admit('task-a'); f.nativeStates.get('task-a').needsObservation = true;
  f.pause(); await f.control.settled('task-a');
  f.resume(); await eventually(() => f.trace.includes('observe:task-a'));
  f.stop();
  assert.equal((await f.control.settled('task-a')).phase, 'stopped');
  observation.resolve(); await delay(10);
  assert.equal(f.engine.woken, undefined);
  assert.ok(!f.calls.some(call => call.operation === 'activate'));
  assert.equal(f.nativeStates.get('task-a').phase, 'stopped');
});

test('stop closes the old run but a later explicit user prompt opens a fresh scope and monotonic control revision', async t => {
  const f = await fixture(t);
  const oldScope = await f.admit('task-a'); f.stop(); await f.control.settled('task-a');
  const revision = f.control.revision('task-a');
  f.control.onWake(f.agents.get('task-a'), { source: { kind: 'schedule' } });
  assert.equal(f.control.held('task-a'), true);
  f.control.onWake(f.agents.get('task-a'), { source: { kind: 'user' } });
  assert.equal(f.control.get('task-a').phase, 'running');
  assert.ok(f.control.revision('task-a') > revision);
  const newScope = await f.admit('task-a'); assert.notEqual(newScope.operationId, oldScope.operationId);
  assert.throws(() => f.control.command({ taskId: 'task-a', action: 'stop', expectedRevision: revision }), { code: 'TASK_CONTROL_CHANGED' });
});

test('native and descendant failures stay unconfirmed while the known driver still receives cancellation', async t => {
  const f = await fixture(t, { rows: () => [{ kind: 'diagnostic', id: 'unreadable-child', reason: 'unavailable' }] });
  f.pause(); await f.control.settled('task-a');
  assert.equal(f.agents.get('task-a').status, 'idle');
  assert.equal(f.control.get('task-a').phase, 'pausing');
  assert.equal(f.control.get('task-a').errorCode, 'TASK_CONTROL_CHILD_STATE_UNCONFIRMED');
  assert.equal(f.control.held('task-a'), true);
});

test('native receipts must describe the exact authenticated scope and cannot claim quiescence through malformed data', async t => {
  const f = await fixture(t, { nativeAfter(_input, state) { state.quiescent = 'yes'; } });
  await assert.rejects(f.admit('task-a'), { code: 'TASK_CONTROL_NATIVE_STATE_INVALID' });
  f.pause(); await f.control.settled('task-a');
  assert.equal(f.control.get('task-a').phase, 'pausing');
  assert.equal(f.control.get('task-a').errorCode, 'TASK_CONTROL_NATIVE_STATE_INVALID');
});

test('real SQLite reopening restores a fence without waking agents, changing sessions or replaying actions', async t => {
  const directory = await mkdtemp(join(tmpdir(), 'droy-control-restore-'));
  t.after(() => rm(directory, { recursive: true, force: true }));
  const f = await fixture(t, { directory });
  await f.admit('task-a'); f.pause(); await f.control.settled('task-a');
  const before = f.control.revision('task-a');
  const reopened = new TaskControlStore(directory);
  const restoredEngine = { ...f.engine, dispose() {} };
  const restored = createTaskControl({ store: reopened, engine: restoredEngine,
    native: async input => structuredClone(f.nativeStates.get(input.taskId)), onChange() {}, settleTimeoutMs: 200, pollMs: 5 });
  try {
    restored.recover();
    assert.equal(restored.held('task-a'), true);
    assert.equal((await restored.settled('task-a')).phase, 'paused');
    assert.ok(restored.revision('task-a') > before);
    assert.equal(f.engine.woken, undefined);
    assert.equal(restored.currentScope('task-a').operationId, f.control.currentScope('task-a').operationId);
  } finally { await restored.dispose(); reopened.close(); }
});

test('a bridge call keeps one scope across awaited preflight, execution and cancellation', async () => {
  let epoch = 7;
  const calls = [], preflight = deferred(), abort = new AbortController();
  const bridge = createBridge({ port: 12345, token: 'fictional-scope-token-1234567890123456789',
    getScope: async () => ({ operationId: 'run-before-pause', epoch }),
    fetchImpl: async (url, options) => {
      const body = JSON.parse(options.body); calls.push({ url, body });
      if (url.endsWith('/permissions/preflight')) await preflight.promise;
      if (url.endsWith('/tool')) {
        assert.equal(body.epoch, 7);
        return await new Promise((_, reject) => options.signal.addEventListener('abort', () => reject(options.signal.reason), { once: true }));
      }
      return { ok: true, json: async () => ({ ok: true, result: {} }) };
    } });
  const call = bridge.call('tap', {}, { agent: { id: 'task-a' }, callId: 'old-call', signal: abort.signal });
  await eventually(() => calls.length === 1); epoch = 8; preflight.resolve();
  await eventually(() => calls.some(row => row.url.endsWith('/tool')));
  abort.abort(new Error('explicit stop'));
  await assert.rejects(call, /explicit stop/);
  assert.ok(calls.every(row => row.body.operationId === 'run-before-pause' && row.body.epoch === 7));
  assert.ok(calls.some(row => row.url.endsWith('/cancel')));
});

test('persistent scopes survive a full native restart only with a new instance and proven old execution quiescence', async t => {
  let instance = 'native-instance-a';
  const f = await fixture(t, { nativeInstanceId: () => instance, scopeRestorationAllowed: true });
  await f.admit('task-a'); f.pause(); await f.control.settled('task-a');
  const oldScope = f.control.currentScope('task-a');
  const reopened = new TaskControlStore(f.directory);
  instance = 'native-instance-b'; f.nativeStates.clear();
  const restored = createTaskControl({ store: reopened, engine: f.engine, native: f.native, settleTimeoutMs: 1000, pollMs: 5 });
  f.engine.control = restored;
  try {
    restored.recover();
    assert.equal(restored.held('task-a'), true);
    assert.equal((await restored.settled('task-a')).phase, 'paused');
    assert.notEqual(restored.currentScope('task-a').operationId, oldScope.operationId);
    assert.equal(restored.get('task-a').outcomeUnknown, true);
    assert.equal(f.engine.woken, undefined);
    restored.command({ taskId: 'task-a', action: 'resume', expectedRevision: restored.revision('task-a') });
    assert.equal((await restored.settled('task-a')).phase, 'running');
    assert.ok(f.trace.includes('observe:task-a'));
    assert.ok(f.trace.indexOf('observe:task-a') < f.trace.indexOf('native:activate:task-a'));
  } finally { await restored.dispose(); reopened.close(); }
});

test('missing scopes in the same native instance or unconfirmed old processes never manufacture a quiet replacement', async t => {
  for (const [nextInstance, allowed] of [['native-instance-a', true], ['native-instance-b', false]]) {
    let instance = 'native-instance-a';
    const f = await fixture(t, { nativeInstanceId: () => instance, scopeRestorationAllowed: allowed });
    await f.admit('task-a'); f.pause(); await f.control.settled('task-a');
    const oldScope = f.control.currentScope('task-a');
    instance = nextInstance; f.nativeStates.clear();
    f.control.recover(); await f.control.settled('task-a');
    assert.equal(f.control.get('task-a').phase, 'pausing');
    assert.equal(f.control.get('task-a').errorCode, 'TASK_CONTROL_NATIVE_RECOVERY_UNCONFIRMED');
    assert.equal(f.control.currentScope('task-a').operationId, oldScope.operationId);
    assert.equal(f.calls.filter(input => input.operation === 'open').length, 1);
  }
});

test('each continuing live family member observes its own scope before any member wakes', async t => {
  const f = await fixture(t, { child: true });
  await f.admit('task-a'); await f.admit('child-a');
  for (const native of f.nativeStates.values()) native.needsObservation = true;
  f.pause(); await f.control.settled('task-a');
  f.resume(); assert.equal((await f.control.settled('task-a')).phase, 'running');
  assert.deepEqual(f.engine.wakes.map(wake => wake.agent.id), ['child-a', 'task-a']);
  const firstWake = f.trace.findIndex(item => item.startsWith('wake:'));
  for (const id of ['task-a', 'child-a']) {
    const observe = f.trace.indexOf(`observe:${id}`), activate = f.trace.indexOf(`native:activate:${id}`);
    assert.ok(observe >= 0 && activate > observe && firstWake > activate);
    assert.match(f.engine.wakes.find(wake => wake.agent.id === id).content[1].text, /Fresh fictional observation/);
  }
});

test('completed controlled tasks keep an inactive tombstone across storage recovery', async t => {
  const f = await fixture(t);
  f.agents.get('task-a').whenIdle = async () => {};
  f.pause(); await f.control.settled('task-a'); f.resume(); await f.control.settled('task-a');
  f.agents.get('task-a').status = 'idle';
  await f.control.completed('task-a');
  assert.equal(f.control.get('task-a').phase, 'completed');
  const revision = f.control.revision('task-a');
  f.control.recover();
  assert.equal(f.control.get('task-a').phase, 'completed');
  assert.equal(f.control.get('task-a').active, false);
  assert.equal(f.control.revision('task-a'), revision);
  assert.equal(f.control.held('task-a'), false);
  f.control.onWake(f.agents.get('task-a'), { source: { kind: 'schedule' } });
  assert.equal(f.control.get('task-a').phase, 'running');
  assert.ok(f.control.revision('task-a') > revision);
});
