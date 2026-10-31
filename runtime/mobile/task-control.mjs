import { randomUUID } from 'node:crypto';
import { setTimeout as delay } from 'node:timers/promises';

const heldPhases = new Set(['pausing', 'paused', 'resuming', 'stopping', 'stopped']);
const nativePhases = new Set(['active', 'pausing', 'paused', 'observing', 'stopping', 'stopped']);
const validId = value => typeof value === 'string' && /^[A-Za-z0-9][A-Za-z0-9_.:-]{0,255}$/u.test(value);
const fail = code => { throw Object.assign(new Error(code), { code }); };
const copy = (locale, pair) => pair[locale === 'zh' ? 'zh' : 'en'];
const controlCopy = {
  pausing: { zh: '正在暂停，等待当前操作停止', en: 'Pausing. Waiting for the current operation to stop.' },
  paused: { zh: '任务已暂停，可以继续或停止', en: 'Task paused. You can resume or stop it.' },
  resuming: { zh: '正在恢复，先核实当前状态', en: 'Resuming. Checking the current state first.' },
  stopping: { zh: '正在停止任务', en: 'Stopping the task.' },
  stopped: { zh: '任务已停止', en: 'Task stopped.' },
  completed: { zh: '本轮执行已结束，点我查看结果', en: 'This turn has ended. Open the task to see the result.' },
  unconfirmed: { zh: '尚未确认停止，请保持连接后重试或停止任务', en: 'Stopping is not yet confirmed. Keep the connection and retry, or stop the task.' },
  observeFailed: { zh: '恢复前的状态核实未完成，请重试继续或停止任务', en: 'The state check before resuming did not complete. Retry resume, or stop the task.' },
  unknown: { zh: '任务已暂停，刚才的操作结果待核实；继续时会先观察', en: 'Task paused. The last operation needs verification; resuming will observe first.' },
};
export const resumePrompt = {
  zh: '用户明确选择继续已暂停的这个任务。沿用当前会话、目标和上下文，先检查最新观察与已完成步骤，再决定下一步。暂停前被中断的操作可能已生效；不得盲目重放，特别是发送、支付、删除等操作。若结果仍不确定，先观察核实或向用户说明并询问。遵守当前产品授权和回答语言。',
  en: 'The user explicitly resumed this paused task. Keep this session, objective, and context. Check the latest observation and completed steps before choosing the next step. An operation interrupted before the pause may already have taken effect; do not blindly replay it, especially sending, payment, or deletion. If its outcome is still uncertain, observe and verify or explain the uncertainty and ask the user. Follow current product authorization and response language.',
};

/** Durable product state. Engine driving and native execution each retain their own contracts. */
export function createTaskControl({ store, engine, native, isManaged = () => false, getLocale = () => 'zh',
  onChange = () => {}, onBind = () => {}, reportError = () => {}, settleTimeoutMs = 15000, pollMs = 100 }) {
  const states = new Map(store.list().map(state => [state.taskId, state]));
  const scopes = new Map(store.scopes().map(scope => [scope.taskId, scope]));
  const opening = new Map(), commands = new Map();
  let disposed = false;
  function rootFor(taskId) {
    // A child's previous control record is not authority to escape a held parent.
    for (const state of states.values()) if (state.taskId !== taskId && heldPhases.has(state.phase)
      && (state.members.includes(taskId) || engine?.liveMembers(state.taskId).some(agent => String(agent.id) === taskId))) return state.taskId;
    if (!heldPhases.has(states.get(taskId)?.phase))
      for (const state of states.values()) if (state.taskId !== taskId && state.members.includes(taskId)) return state.taskId;
    if (states.has(taskId)) return taskId;
    return scopes.get(taskId)?.rootId ?? engine?.rootId(taskId) ?? taskId;
  }
  function independentlyHeld(taskId, rootId) {
    return [...states.values()].some(state => state.taskId !== rootId && heldPhases.has(state.phase)
      && (state.taskId === taskId || state.members.includes(taskId)
        || engine.liveMembers(state.taskId).some(agent => String(agent.id) === taskId)));
  }
  function stateFor(taskId) { return states.get(rootFor(taskId)); }
  function held(taskId) { return disposed || heldPhases.has(stateFor(taskId)?.phase); }
  function view(state) {
    if (!state) return undefined;
    const key = state.finished ? 'completed' : state.errorCode ? state.phase === 'resuming' ? 'observeFailed' : 'unconfirmed'
      : state.phase === 'paused' && state.outcomeUnknown ? 'unknown' : state.phase;
    return { taskId: state.taskId, phase: state.finished ? 'completed' : state.phase, controlRevision: state.revision,
      active: !state.finished && state.phase !== 'stopped', ...(controlCopy[key] ? { summary: copy(getLocale(), controlCopy[key]) } : {}),
      ...(state.errorCode || state.phase === 'paused' && state.outcomeUnknown ? { summaryKind: 'error' } : {}),
      ...(state.errorCode ? { errorCode: state.errorCode } : {}), outcomeUnknown: state.outcomeUnknown === true };
  }
  function identity(taskId) {
    const rootId = engine.rootId(taskId);
    const state = view(states.get(rootId) ?? (rootId === taskId ? stateFor(taskId) : undefined));
    if (state) return state;
    if (!engine.get(taskId) && !engine.get(rootId)) return undefined;
    return { taskId: rootId, phase: 'running', controlRevision: 0,
      active: engine.liveMembers(rootId).some(agent => agent.status === 'running') };
  }
  function publish(state) {
    for (const taskId of state.members) onChange(taskId, view(state));
  }
  function save(previous, patch) {
    const next = { ...previous, ...patch, revision: (previous?.revision ?? 0) + 1 };
    store.put(next, previous?.revision ?? 0);
    states.set(next.taskId, next);
    publish(next);
    return next;
  }
  function scopeSave(scope) {
    store.putScope(scope);
    scopes.set(scope.taskId, scope);
    return scope;
  }
  function scopeReceipt(scope, receipt, persist = true) {
    if (!receipt || receipt.taskId !== scope.taskId || receipt.operationId !== scope.operationId
      || !Number.isSafeInteger(receipt.epoch) || receipt.epoch < 0 || !nativePhases.has(receipt.phase)
      || !validId(receipt.nativeInstanceId)
      || ['quiescent', 'paused', 'outcomeUnknown', 'needsObservation'].some(key => typeof receipt[key] !== 'boolean')
      || !Array.isArray(receipt.pending))
      fail('TASK_CONTROL_NATIVE_STATE_INVALID');
    const next = { ...scope, ...receipt, rootId: scope.rootId };
    return persist ? scopeSave(next) : next;
  }
  function currentScope(taskId) {
    const scope = scopes.get(taskId);
    return scope?.epoch === undefined ? undefined : { operationId: scope.operationId, epoch: scope.epoch };
  }
  async function request(scope, operation, signal, extra = {}) {
    signal?.throwIfAborted();
    const receipt = await native({ taskId: scope.taskId, operationId: scope.operationId, operation,
      ...(scope.epoch === undefined || operation === 'status' || operation === 'open' ? {} : { expectedEpoch: scope.epoch }),
      ...(scope.pauseId ? { pauseId: scope.pauseId } : {}), ...extra }, signal);
    // A late response from an abandoned run must never overwrite the new run's scope.
    if (scopes.get(scope.taskId)?.operationId !== scope.operationId) fail('TASK_CONTROL_SUPERSEDED');
    const latest = scopes.get(scope.taskId);
    scopeReceipt(latest, receipt, false);
    if (latest.nativeInstanceId && latest.nativeInstanceId !== receipt.nativeInstanceId) fail('TASK_CONTROL_NATIVE_INSTANCE_CHANGED');
    if (latest.epoch !== undefined && receipt.epoch < latest.epoch) return latest;
    return scopeReceipt(latest, receipt);
  }
  async function admitNative({ taskId, callId, action, signal }) {
    const agent = engine.get(taskId);
    // Input dictation and companion-owned services retain their existing product owner.
    if (!agent || isManaged(taskId)) return undefined;
    signal?.throwIfAborted();
    const observation = stateFor(taskId)?.phase === 'resuming' && action === 'observe' && engine.isObservationCall(taskId, callId);
    if (held(taskId) && !observation) fail('TASK_CONTROL_HELD');
    let scope = scopes.get(taskId);
    if (!scope || scope.phase === 'stopped' || scope.epoch === undefined) {
      if (!opening.has(taskId)) {
        if (!scope || scope.phase === 'stopped')
          scope = scopeSave({ taskId, rootId: rootFor(taskId), operationId: randomUUID(), ended: false });
        const operation = request(scope, 'open', signal);
        opening.set(taskId, operation);
        operation.finally(() => { if (opening.get(taskId) === operation) opening.delete(taskId); }).catch(() => {});
      }
    }
    if (opening.has(taskId)) await opening.get(taskId);
    scope = scopes.get(taskId);
    if (!scope || scope.epoch === undefined) fail('TASK_CONTROL_NATIVE_OPEN_UNCONFIRMED');
    signal?.throwIfAborted();
    if (held(taskId) && !observation) fail('TASK_CONTROL_HELD');
    if (observation && scope.phase !== 'observing' || !observation && scope.phase !== 'active') fail('TASK_CONTROL_HELD');
    scopeSave({ ...scope, ended: false, used: scope.used || action !== 'capabilities' });
    return currentScope(taskId);
  }
  function valid(command) {
    command.signal.throwIfAborted();
    if (disposed || states.get(command.taskId)?.commandId !== command.id) fail('TASK_CONTROL_SUPERSEDED');
    return states.get(command.taskId);
  }
  function update(command, patch) { return save(valid(command), patch); }
  async function members(command) {
    let state = valid(command);
    const rows = await engine.descendants(command.taskId, command.signal);
    state = valid(command);
    if (rows.some(row => row.kind !== 'child')) fail('TASK_CONTROL_CHILD_STATE_UNCONFIRMED');
    const live = engine.liveMembers(command.taskId);
    const ids = [...new Set([...state.members, ...live.map(agent => String(agent.id)), ...rows.map(row => String(row.id))])];
    if (ids.some(id => !state.members.includes(id))) state = update(command, { members: ids });
    engine.interruptExternal(command.taskId, rows);
    return { state, live, rows };
  }
  async function nativeFence(command, operation) {
    const state = valid(command);
    await Promise.allSettled(state.members.map(taskId => opening.get(taskId)).filter(Boolean));
    valid(command);
    const owned = state.members.map(taskId => scopes.get(taskId)).filter(Boolean);
    const receipts = await Promise.all(owned.map(async scope => {
      let next = scope;
      // An open response can be lost. Reusing the same operation id is idempotent.
      if (next.epoch === undefined) next = await request(next, 'open', command.signal);
      else {
        try { next = await request(next, 'status', command.signal); }
        catch (error) {
          if (error?.code !== 'TASK_SCOPE_REQUIRED') throw error;
          valid(command);
          // Android's execution registry is process-local. Keep the durable JS
          // fence while establishing a new native run; its epochs start afresh.
          if (!validId(error.details?.nativeInstanceId) || error.details.nativeInstanceId === next.nativeInstanceId
            || !next.nativeInstanceId || error.details.scopeRestorationAllowed !== true) fail('TASK_CONTROL_NATIVE_RECOVERY_UNCONFIRMED');
          update(command, { outcomeUnknown: true });
          next = scopeSave({ taskId: next.taskId, rootId: next.rootId, operationId: randomUUID(),
            ended: false, used: true, outcomeUnknown: true });
          next = await request(next, 'open', command.signal);
        }
      }
      valid(command);
      if (operation === 'pause' && next.phase === 'stopped') return next;
      for (let attempt = 0; next.phase !== (operation === 'pause' ? 'paused' : 'stopped'); attempt++) {
        valid(command);
        try {
          next = await request(next, operation, command.signal, operation === 'pause' ? { pauseId: next.pauseId || command.id } : {});
          break;
        } catch (error) {
          if (error?.code !== 'TASK_CONTROL_CONFLICT' || attempt >= 2) throw error;
          next = await request(scopes.get(next.taskId), 'status', command.signal);
        }
      }
      while (!next.quiescent || next.pending.length !== 0) {
        await delay(pollMs, undefined, { signal: command.signal });
        valid(command);
        next = await request(next, 'status', command.signal);
      }
      if (operation === 'pause' && (next.phase !== 'paused' || !next.paused) || operation === 'stop' && next.phase !== 'stopped')
        fail('TASK_CONTROL_NATIVE_STOP_UNCONFIRMED');
      return next;
    }));
    return receipts;
  }
  async function settle(command, stop) {
    const live = engine.liveMembers(command.taskId);
    for (const agent of live) { if (stop) engine.stopGoal(agent); else engine.disarm(agent); }
    // Cancel the known driver immediately. A slow or corrupt descendant catalog
    // cannot postpone stopping the current turn or fencing known phone scopes.
    const driverWork = engine.settle(live, { keepInbox: !stop, signal: command.signal });
    const nativeWork = nativeFence(command, stop ? 'stop' : 'pause');
    const descendantsWork = (async () => {
      const seen = new Set(live);
      while (true) {
        const next = await members(command);
        const added = next.live.filter(agent => !seen.has(agent));
        for (const agent of added) { seen.add(agent); if (stop) engine.stopGoal(agent); else engine.disarm(agent); }
        if (added.length) await engine.settle(added, { keepInbox: !stop, signal: command.signal });
        await nativeWork;
        await nativeFence(command, stop ? 'stop' : 'pause');
        if (!next.live.some(agent => agent.status === 'running')
          && !next.rows.some(row => row.activity === 'running' && !engine.get(String(row.id)))) return;
        await delay(pollMs, undefined, { signal: command.signal });
      }
    })();
    // Observe both failures: a rejected HTTP fence cannot leave driver cancellation unawaited.
    const results = await Promise.allSettled([nativeWork, driverWork, descendantsWork]);
    valid(command);
    for (const result of results) if (result.status === 'rejected') throw result.reason;
    const receipts = valid(command).members.map(id => scopes.get(id)).filter(Boolean);
    update(command, { phase: stop ? 'stopped' : 'paused', errorCode: undefined,
      outcomeUnknown: valid(command).outcomeUnknown || receipts.some(scope => scope.outcomeUnknown), ...(stop ? { goals: {}, pauseId: undefined } : {}) });
  }
  async function resume(command) {
    const state = valid(command);
    const root = await engine.resolve(command.taskId);
    valid(command);
    const observations = new Map();
    for (const taskId of state.members) {
      if (independentlyHeld(taskId, command.taskId)) continue;
      let scope = scopes.get(taskId);
      if (!scope) continue;
      scope = await request(scope, 'status', command.signal);
      valid(command);
      if (taskId !== command.taskId && !engine.get(taskId)) {
        // An ended one-shot child has no continuation to resume. Keep its old run closed.
        if (scope.phase !== 'stopped') await request(scope, 'stop', command.signal);
        continue;
      }
      if (scope.phase === 'active') scope = await request(scope, 'pause', command.signal, { pauseId: command.id });
      if (!scope.quiescent || scope.phase !== 'paused' && scope.phase !== 'observing') fail('TASK_CONTROL_NATIVE_STOP_UNCONFIRMED');
      if (scope.phase === 'paused') scope = await request(scope, 'resume', command.signal);
      valid(command);
      if (scope.phase !== 'observing' || !scope.quiescent || scope.paused) fail('TASK_CONTROL_NATIVE_STATE_INVALID');
      if (scope.needsObservation || scope.used) {
        const observer = engine.get(taskId) ?? root;
        observations.set(taskId, await engine.observe(observer, randomUUID(), command.signal));
        valid(command);
        scope = await request(scopes.get(taskId), 'status', command.signal);
        if (scope.needsObservation) fail('TASK_CONTROL_OBSERVATION_UNCONFIRMED');
      }
    }
    valid(command);
    for (const taskId of state.members) {
      if (independentlyHeld(taskId, command.taskId)) continue;
      const scope = scopes.get(taskId);
      if (!scope || scope.phase === 'stopped') continue;
      if (scope.needsObservation) fail('TASK_CONTROL_OBSERVATION_UNCONFIRMED');
      const activated = await request(scope, 'activate', command.signal);
      valid(command);
      if (activated.phase !== 'active' || activated.paused || !activated.quiescent) fail('TASK_CONTROL_NATIVE_STATE_INVALID');
    }
    for (const taskId of state.members) {
      if (independentlyHeld(taskId, command.taskId)) continue;
      const agent = engine.get(taskId);
      if (agent) engine.rearmGoal(agent, state.goals?.[taskId]);
    }
    if (engine.get(command.taskId) !== root) fail('TASK_CONTROL_AGENT_UNAVAILABLE');
    // Persist running before the one explicit wake. Every failure before this point remains fenced.
    update(command, { phase: 'running', errorCode: undefined });
    for (const taskId of state.continuations ?? [command.taskId]) {
      if (taskId === command.taskId || independentlyHeld(taskId, command.taskId)) continue;
      const agent = engine.get(taskId);
      if (agent) engine.wake(agent, [{ type: 'text', text: copy(getLocale(), resumePrompt) }, ...(observations.get(taskId) ?? [])]);
    }
    engine.wake(root, [{ type: 'text', text: copy(getLocale(), resumePrompt) }, ...(observations.get(command.taskId) ?? [])]);
  }
  function launch(state, action) {
    commands.get(state.taskId)?.controller.abort(Object.assign(new Error('TASK_CONTROL_SUPERSEDED'), { code: 'TASK_CONTROL_SUPERSEDED' }));
    const controller = new AbortController();
    const command = { id: state.commandId, taskId: state.taskId, controller,
      signal: AbortSignal.any([controller.signal, AbortSignal.timeout(settleTimeoutMs)]) };
    commands.set(state.taskId, command);
    command.promise = (async () => {
      try { if (action === 'resume') await resume(command); else await settle(command, action === 'stop'); }
      catch (error) {
        if (disposed || states.get(state.taskId)?.commandId !== command.id) return;
        const code = /^[A-Z][A-Z0-9_]{2,80}$/u.test(error?.code ?? '') ? error.code : 'TASK_CONTROL_STOP_UNCONFIRMED';
        reportError(code);
        // No exception or elapsed timeout is evidence of physical quiescence.
        const current = states.get(state.taskId);
        save(current, { ...(current.phase === 'running' ? { phase: 'resuming' } : {}), errorCode: code, outcomeUnknown: true });
      } finally { if (commands.get(state.taskId) === command) commands.delete(state.taskId); }
    })();
  }
  function command({ taskId, action, expectedRevision }) {
    if (disposed) fail('TASK_CONTROL_UNAVAILABLE');
    if (!validId(taskId) || !['pause', 'resume', 'stop'].includes(action) || !Number.isSafeInteger(expectedRevision) || expectedRevision < 0)
      fail('TASK_CONTROL_INVALID_INPUT');
    if (isManaged(taskId)) fail('TASK_CONTROL_SESSION_MANAGED');
    if (engine.rootId(taskId) !== taskId) fail('TASK_CONTROL_PARENT_OWNS_TASK');
    const current = stateFor(taskId);
    if (current && current.taskId !== taskId) fail('TASK_CONTROL_PARENT_OWNS_TASK');
    if ((current?.revision ?? 0) !== expectedRevision) fail('TASK_CONTROL_CHANGED');
    const agent = engine.get(taskId);
    const live = engine.liveMembers(taskId);
    if (!current && !agent) fail('TASK_CONTROL_TASK_UNAVAILABLE');
    if (action === 'resume' && !['paused', 'resuming'].includes(current?.phase)) fail('TASK_CONTROL_NOT_PAUSED');
    if (action === 'pause' && ['paused', 'stopped', 'stopping'].includes(current?.phase)
      || action === 'stop' && current?.phase === 'stopped') return { accepted: false, control: view(current) };
    if (action === 'pause' && !current && !live.some(member => member.status === 'running') && !scopes.get(taskId) && engine.goalSnapshot(agent)?.activation !== 'armed')
      fail('TASK_CONTROL_TASK_IDLE');
    const ids = [...new Set([taskId, ...(current?.members ?? []), ...live.map(member => String(member.id)),
      ...[...scopes.values()].filter(scope => scope.rootId === taskId).map(scope => scope.taskId)])];
    const goals = !current || current.phase === 'running'
      ? Object.fromEntries(live.map(member => [String(member.id), engine.goalSnapshot(member)])) : current.goals ?? {};
    const continuations = !current || current.phase === 'running'
      ? [...new Set([taskId, ...live.filter(member => member.status === 'running' || member.inbox?.hasPending
        || engine.goalSnapshot(member)?.activation === 'armed').map(member => String(member.id))])] : current.continuations ?? [taskId];
    const next = save(current, { taskId, members: ids, goals, phase: action === 'pause' ? 'pausing' : action === 'resume' ? 'resuming' : 'stopping',
      commandId: randomUUID(), continuations, finished: false, errorCode: undefined, outcomeUnknown: current?.outcomeUnknown ?? false });
    // No await separates durable state publication from the engine wake fence.
    for (const member of live) engine.disarm(member);
    launch(next, action);
    return { accepted: true, control: view(next) };
  }
  return {
    held, admitNative, currentScope, command,
    preserveInbox: taskId => ['pausing', 'paused', 'resuming'].includes(stateFor(taskId)?.phase),
    revision: taskId => stateFor(taskId)?.revision ?? 0,
    get: identity,
    async settled(taskId) { await commands.get(rootFor(taskId))?.promise; return view(stateFor(taskId)); },
    onWake(agent, message) {
      const state = stateFor(String(agent.id));
      if (state && ['pausing', 'paused', 'resuming'].includes(state.phase) && !(state.continuations ?? []).includes(String(agent.id)))
        save(state, { continuations: [...(state.continuations ?? [state.taskId]), String(agent.id)] });
      if (state?.finished) {
        save(state, { finished: false, commandId: randomUUID(), errorCode: undefined, outcomeUnknown: false });
        return;
      }
      if (state?.phase !== 'stopped' || state.taskId !== String(agent.id) || message.source?.kind !== 'user') return;
      for (const taskId of state.members) engine.get(taskId)?.inbox?.clear();
      for (const taskId of state.members) { scopes.delete(taskId); store.deleteScope(taskId); }
      save(state, { phase: 'running', finished: false, commandId: randomUUID(), members: [state.taskId], goals: {}, errorCode: undefined, outcomeUnknown: false });
    },
    onCreated(agent) {
      const taskId = String(agent.id), rootId = engine.rootId(taskId);
      let state = states.get(rootId) ?? stateFor(taskId);
      if (state && !state.members.includes(taskId)) state = save(state, { members: [...state.members, taskId] });
      onBind(taskId, identity(taskId));
      if (!state || !held(taskId)) return;
      engine.disarm(agent);
      agent.cancel({ kind: 'user' }, { keepInbox: state.phase !== 'stopped' && state.phase !== 'stopping' });
    },
    ended(taskId, captured) {
      const scope = scopes.get(taskId);
      if (scope && captured?.operationId === scope.operationId && captured.epoch === scope.epoch) scopeSave({ ...scope, ended: true });
    },
    async completed(taskId) {
      const before = stateFor(taskId), agent = before && engine.get(before.taskId);
      if (!before || before.phase !== 'running' || !agent) return;
      taskId = before.taskId;
      await agent.whenIdle();
      const current = states.get(taskId), live = engine.liveMembers(taskId);
      if (disposed || current?.commandId !== before.commandId || current.phase !== 'running' || current.finished
        || live.some(member => member.status === 'running' || member.inbox?.hasPending || engine.goalSnapshot(member)?.activation === 'armed')) return;
      save(current, { finished: true });
    },
    recover() {
      // Process-local continuation authority never survives a process boundary.
      for (const scope of scopes.values()) if (!scope.ended && !states.has(scope.rootId))
        save(undefined, { taskId: scope.rootId, members: [...new Set([scope.rootId, ...[...scopes.values()].filter(item => item.rootId === scope.rootId).map(item => item.taskId)])],
          phase: 'pausing', commandId: randomUUID(), goals: {}, outcomeUnknown: true });
      for (const state of [...states.values()]) {
        if (state.phase === 'stopped' || state.finished) { publish(state); continue; }
        const next = save(state, { phase: 'pausing', commandId: randomUUID(), errorCode: undefined });
        launch(next, 'pause');
      }
    },
    async dispose() {
      disposed = true;
      for (const operation of commands.values()) operation.controller.abort();
      await Promise.allSettled([...commands.values()].map(operation => operation.promise));
      engine.dispose();
    },
  };
}
