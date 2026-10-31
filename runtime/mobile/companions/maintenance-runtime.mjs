const installed = new WeakMap();
const writers = ['initialize','start','prepareWorkspace','startInstance','memory','context','generate','agent','decorate',
  'submit','enqueue','transcribeVoice','retry','flush','reply','sendEmoji','wait','publish','recoverReply','markRead','deliverNotifications','syncReadNotifications','finishTurn','learn',
  'startWork','resumeWork','refreshProactiveSchedule','proactiveContext','considerProactive','wake','invalidate','forget','reviseMemory','removeMessage','resetMemories','clearHistory','resetChat','cancel'];
const fail = (code, cause) => { throw Object.assign(new Error(code, cause === undefined ? undefined : { cause }), { code }); };
const validOptions = value => value !== null && typeof value === 'object' && !Array.isArray(value);

/** Tracks this product runtime's returned work without changing its results or cancellation. */
export function installCompanionMaintenance(runtime) {
  if (installed.has(runtime)) fail('COMPANION_MAINTENANCE_INVALID');
  const active = new Set(), proactive = new Map(), recoveries = new Map();
  let revision = 0, owner, stopped = false;
  const changed = () => { revision++; runtime.activityChanged?.(); };
  const begin = (kind,companionId) => {
    const done = Promise.withResolvers(), entry = { kind, companionId, done: done.promise };
    active.add(entry); changed(); let finished = false;
    return () => { if (!finished) { finished = true; active.delete(entry); changed(); done.resolve(); runtime.executionChanged?.(companionId); } };
  };
  const run = (kind, operation, companionId) => {
    const finish = begin(kind,companionId);
    let result;
    try { result = operation(); } catch (error) { finish(); throw error; }
    if (result !== null && (typeof result === 'object' || typeof result === 'function') && typeof result.then === 'function')
      Promise.resolve(result).then(finish, finish);
    else finish();
    return result;
  };
  const held = () => !!owner && !['ready','released'].includes(owner.phase) && !stopped;
  const exiting = () => runtime.ctx.get('droyLifecycle')?.stopping===true;
  const arm = (map, key, entry) => {
    entry.timer = setTimeout(() => {
      if (map.get(key) !== entry || stopped) return;
      if(exiting()){map.delete(key);changed();return;}
      entry.timer = undefined;
      if (held() && (map === proactive || entry.deferred)) return;
      map.delete(key); changed();
      run(entry.kind, entry.callback, entry.companionId);
    }, Math.max(0, entry.at - Date.now()));
    if (entry.unref) entry.timer.unref?.();
    return entry.timer;
  };
  const cancel = (map, key) => {
    const entry = map.get(key);
    if (entry) { clearTimeout(entry.timer); map.delete(key); changed(); }
  };
  const schedule = (map, key, at, callback, unref) => {
    if(exiting())fail('DROY_STOPPING');
    cancel(map, key);
    if (stopped || runtime.closed) return;
    const companionId=map===proactive?key:key.startsWith('work:')?runtime.owners.get(key.slice(5))?.companionId
      :key.startsWith('chat:')||key.startsWith('wait:')?key.slice(5):undefined;
    const entry = { at, callback, unref, companionId, kind: map === proactive ? 'proactive' : 'recovery', deferred: held() };
    map.set(key, entry); changed();
    if (!held()) return arm(map, key, entry);
  };
  const agents = () => {
    const result = new Set(runtime.live.values());
    for (const record of runtime.owners.values()) {
      const agent = runtime.ctx.agents.get(record.sessionId); if (agent) result.add(agent);
    }
    return [...result];
  };
  const obligations = async currentAgents => {
    const counts = new Map(), add = (kind, count = 1) => { if (count) counts.set(kind, (counts.get(kind) ?? 0) + count); };
    for (const agent of currentAgents) {
      if (!Array.isArray(agent.inbox?.nextTurn) || !Array.isArray(agent.inbox?.nextStep))
        fail('COMPANION_MAINTENANCE_OBSERVATION_FAILED');
      add('agent-inbox', agent.inbox.nextTurn.length + agent.inbox.nextStep.length);
    }
    for (const state of runtime.states.values()) {
      add('chat-pending', state.pending.length);
      if (state.chatRecoveryScheduled) add('chat-recovery');
      if (state.chatWaitScheduled) add('chat-wait');
      if (state.chatStopUnconfirmed) add('chat-unconfirmed');
    }
    add('recovery', recoveries.size);
    for (const profile of await runtime.store.profiles()) {
      const saved = await runtime.store.getState(profile.id);
      add('stored-input', (await runtime.activePending(profile.id, [
        ...(saved.pendingMessageIds ?? []), ...(await runtime.store.pendingMessages(profile.id)).map(message => message.id),
      ])).length);
      if (saved.waitingUntil || saved.pendingIntent) add('stored-wait');
      if (saved.replyDraft) add('stored-reply');
      for (const record of await runtime.store.listSessions(profile.id, { kind: 'work' })) {
        const message = await runtime.store.getMessage(profile.id, record.taskId);
        // resumeWork() deliberately ignores removed or excluded task cards.
        if (!message?.task || message.contextExcluded) continue;
        if (!['running','queued','cancelling','completed','cancelled','failed'].includes(message.task.state))
          fail('COMPANION_MAINTENANCE_OBSERVATION_FAILED');
        if (['running','queued','cancelling'].includes(message.task.state)) add('work-task');
      }
    }
    return [...counts].sort(([a], [b]) => a.localeCompare(b)).map(([kind, count]) => Object.freeze({ kind, count }));
  };
  const acquire = options => {
    if (!validOptions(options) || Object.keys(options).some(key => key !== 'operationId')
      || typeof options.operationId !== 'string' || !/^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$/.test(options.operationId))
      fail('COMPANION_MAINTENANCE_INVALID');
    if (stopped || runtime.closed) fail('COMPANION_MAINTENANCE_STOPPED');
    if (owner?.operationId === options.operationId) return owner.lease;
    if (owner && owner.phase !== 'released') fail('COMPANION_MAINTENANCE_CONFLICT');
    const released = Promise.withResolvers();
    const state = { operationId: options.operationId, phase: 'ready', proof: undefined, pending: undefined, released };
    const status = () => {
      const isHeld = owner === state && held(), observed = state.proof?.revision === revision;
      const busy = observed ? state.proof.busy : Object.freeze([Object.freeze({ kind: 'unobserved', count: 1 })]);
      return Object.freeze({ version: 1, scope: 'companion-runtime', operationId: state.operationId,
        phase: stopped || runtime.closed ? 'stopped' : state.phase, revision,
        producersSuspended: isHeld, activeWriters: active.size,
        busy, quiet: isHeld && state.phase === 'suspended' && observed && !active.size && !busy.length });
    };
    const drain = async () => {
      while (owner === state && held()) {
        const before = revision;
        await Promise.all([...active].map(entry => entry.done));
        if (owner !== state || !held()) break;
        const currentAgents = agents();
        try {
          await Promise.all(currentAgents.map(agent => {
            if (typeof agent.whenIdle !== 'function') fail('COMPANION_MAINTENANCE_OBSERVATION_FAILED');
            return agent.whenIdle();
          }));
        } catch (cause) { fail('COMPANION_MAINTENANCE_OBSERVATION_FAILED', cause); }
        if (owner !== state || !held()) break;
        if (active.size || revision !== before) continue;
        let busy;
        try { busy = await obligations(currentAgents); }
        catch (cause) { fail('COMPANION_MAINTENANCE_OBSERVATION_FAILED', cause); }
        if (owner !== state || !held()) break;
        if (active.size || revision !== before) continue;
        state.proof = { revision, busy: Object.freeze(busy) }; state.phase = 'suspended';
        return status();
      }
      return status();
    };
    const wait = (promise, signal) => signal === undefined ? promise : new Promise((resolve, reject) => {
      const aborted = () => { signal.removeEventListener('abort', aborted); reject(signal.reason); };
      signal.addEventListener('abort', aborted, { once: true });
      promise.then(value => { signal.removeEventListener('abort', aborted); resolve(value); },
        error => { signal.removeEventListener('abort', aborted); reject(error); });
      if (signal.aborted) aborted();
    });
    const lease = Object.freeze({
      suspend(waitOptions = {}) {
        if (!validOptions(waitOptions) || Object.keys(waitOptions).some(key => key !== 'signal')
          || waitOptions.signal !== undefined && !(waitOptions.signal instanceof AbortSignal)) fail('COMPANION_MAINTENANCE_INVALID');
        waitOptions.signal?.throwIfAborted();
        if (state.phase === 'released') return Promise.resolve(status());
        if (stopped || runtime.closed) fail('COMPANION_MAINTENANCE_STOPPED');
        if (owner !== state) fail('COMPANION_MAINTENANCE_CONFLICT');
        if (state.pending === undefined) {
          state.phase = 'suspending'; state.proof = undefined;
          for (const entry of proactive.values()) { clearTimeout(entry.timer); entry.timer = undefined; }
          const pending = Promise.race([drain(), released.promise.then(status)]);
          state.pending = pending;
          const settled = () => { if (state.pending === pending) state.pending = undefined; };
          pending.then(settled, settled);
        }
        // A cancelled observer cannot reopen the host's maintenance window.
        return wait(state.pending, waitOptions.signal);
      },
      resume() {
        if (state.phase === 'released') return status();
        if (stopped || runtime.closed) fail('COMPANION_MAINTENANCE_STOPPED');
        if (owner !== state) fail('COMPANION_MAINTENANCE_CONFLICT');
        state.phase = 'released'; state.proof = undefined; released.resolve();
        for (const [key, entry] of proactive) if (entry.timer === undefined) arm(proactive, key, entry);
        for (const [key, entry] of recoveries) if (entry.deferred && entry.timer === undefined) {
          entry.deferred = false; arm(recoveries, key, entry);
        }
        return status();
      },
      status,
    });
    state.lease = lease; owner = state;
    return lease;
  };
  const control = Object.freeze({
    begin, run, changed, acquire,
    active(id, excluded = []) { return [...active].filter(entry => (id === undefined || entry.companionId === id) && !excluded.includes(entry.kind)); },
    async drainCompanion(id, excluded = []) {
      for (;;) {
        const pending = [...active].filter(entry => entry.companionId === id && !excluded.includes(entry.kind));
        if (!pending.length) return;
        await Promise.all(pending.map(entry => entry.done));
      }
    },
    async drainMemorySpace(id) {
      for(;;) {
        const pending=[...active].filter(entry=>entry.companionId===id);
        if(!pending.length)return;
        await Promise.all(pending.map(entry=>entry.done));
      }
    },
    proactive(id, at, callback) { return schedule(proactive, id, at, callback, true); },
    cancelProactive(id) { cancel(proactive,id); },
    recovery(key, at, callback, { unref = false } = {}) { return schedule(recoveries, key, at, callback, unref); },
    cancelRecovery(key) { cancel(recoveries, key); },
    close() {
      stopped = true; changed();
      for (const map of [proactive, recoveries]) { for (const entry of map.values()) clearTimeout(entry.timer); map.clear(); }
      // Existing operations settle through their original cancellation paths;
      // closing this observer only prevents a stale successful idle receipt.
      owner?.released?.resolve();
    },
  });
  const memoryProducers=new Set(['submit','enqueue','startWork','context','generate','agent','reply','sendEmoji','publish','recoverReply','learn','considerProactive','wake','wait']);
  const exitAdmissions=new Set([...memoryProducers,'initialize','start','startInstance','prepareWorkspace','resumeWork','flush','retry','transcribeVoice','refreshProactiveSchedule']);
  for (const key of writers) {
    const original = runtime[key];
    runtime[key] = function (...args) {
      const id=typeof args[0]==='string'?args[0]:args[0]?.companionId??args[0]?.id;
      if(exitAdmissions.has(key)&&exiting())return Promise.reject(Object.assign(new Error('DROY_STOPPING'),{code:'DROY_STOPPING'}));
      if(memoryProducers.has(key)&&this.memoryManagement?.has(id))return Promise.reject(Object.assign(new Error('COMPANION_MEMORY_MANAGEMENT_IN_PROGRESS'),{code:'COMPANION_MEMORY_MANAGEMENT_IN_PROGRESS'}));
      return run(key, () => Reflect.apply(original, this, args),id);
    };
  }
  for (const key of ['maintain','contextMessages','recall']) {
    const original = runtime.learner[key];
    runtime.learner[key] = function (...args) {
      if(runtime.memoryManagement?.has(args[0]))return Promise.reject(Object.assign(new Error('COMPANION_MEMORY_MANAGEMENT_IN_PROGRESS'),{code:'COMPANION_MEMORY_MANAGEMENT_IN_PROGRESS'}));
      return run('learning:' + key, () => Reflect.apply(original, this, args),args[0]);
    };
  }
  installed.set(runtime, control);
  return control;
}

export function createCompanionMaintenanceLease(runtime, options) {
  const control = installed.get(runtime);
  if (!control) fail('COMPANION_MAINTENANCE_NOT_READY');
  return control.acquire(options);
}
