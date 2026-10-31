import { createUserMessage } from '@deepseek-ai/dsh-llm';

const get = (ctx, name) => typeof ctx.get === 'function' ? ctx.get(name) : ctx[name];
const failure = code => Object.assign(new Error(code), { code });
const terminalJob = job => ['completed', 'killed', 'failed'].includes(job.status);
async function abortable(operation, signal) {
  signal.throwIfAborted();
  let reject;
  const aborted = new Promise((_, rejectPromise) => { reject = rejectPromise; });
  const onAbort = () => reject(signal.reason);
  signal.addEventListener('abort', onAbort, { once: true });
  try { return await Promise.race([operation, aborted]); }
  finally { signal.removeEventListener('abort', onAbort); }
}

/** The product owns pause state; this adapter only contains DSH-specific driving and ownership. */
export function createTaskControlEngine(ctx, { held, retainInbox = held, onCreated = async () => {}, onWake = () => {} }) {
  const agents = get(ctx, 'agents');
  const bindings = new Map();
  const observations = new Map();
  let closed = false;
  const blocked = agent => closed || held(String(agent.id));
  function attach(agent) {
    if (bindings.has(agent)) return;
    const original = agent.send;
    const originalCancel = agent.cancel;
    const targets = new Map();
    for (const item of agent.inbox.nextTurn) targets.set(item.id, 'next-turn');
    for (const item of agent.inbox.nextStep) targets.set(item.id, 'next-step');
    const binding = { original, targets, claimed: new Map() };
    bindings.set(agent, binding);
    agent.send = function(message, target, wakeup) {
      if (wakeup) onWake(this, message);
      targets.set(message.id, target);
      return original.call(this, message, target, wakeup && !blocked(this));
    };
    agent.cancel = function(cause, options = {}) {
      // DSH's normal teardown cancels pending input. A product pause owns that
      // durable inbox across host exit; disposal must still abort the driver.
      if (cause.kind === 'disposed' && retainInbox(String(this.id))) {
        const result = originalCancel.call(this, cause, { ...options, keepInbox: true });
        restoreClaimed(this);
        return result;
      }
      return originalCancel.call(this, cause, options);
    };
  }
  function restoreClaimed(agent) {
    const binding = bindings.get(agent);
    if (!binding) return;
    for (const { message, target } of [...binding.claimed.values()].toReversed()) {
      if (agent.inbox.nextTurn.some(item => item.id === message.id) || agent.inbox.nextStep.some(item => item.id === message.id)) continue;
      agent.inbox.prepend(target, message);
    }
    binding.claimed.clear();
  }
  for (const agent of agents.list()) attach(agent);
  ctx.on('agent/created', async ({ agent }) => { attach(agent); await onCreated(agent); }, { prepend: true });
  ctx.on('agent/inbox/inserted', ({ agent, message }) => {
    const binding = bindings.get(agent);
    if (binding) binding.targets.set(message.id, agent.inbox.nextTurn.some(item => item.id === message.id) ? 'next-turn' : 'next-step');
  });
  ctx.on('agent/inbox/claimed', ({ agent, message }) => {
    const binding = bindings.get(agent);
    if (binding) binding.claimed.set(message.id, { message, target: binding.targets.get(message.id) ?? 'next-step' });
  });
  ctx.on('session/event', (session, event) => {
    if (event.type !== 'user/message') return;
    const agent = agents.get(session.id), binding = bindings.get(agent);
    if (binding) { binding.claimed.delete(event.data.id); binding.targets.delete(event.data.id); }
  });
  ctx.on('agent/pre-step', async ({ agent }, next) => {
    if (blocked(agent)) { restoreClaimed(agent); return { kind: 'reject' }; }
    const decision = await next();
    if (blocked(agent)) { restoreClaimed(agent); return { kind: 'reject' }; }
    return decision;
  }, { prepend: true });
  ctx.on('agent/request', async ({ agent }, next) => {
    if (blocked(agent)) throw failure('TASK_CONTROL_HELD');
    const request = await next();
    if (blocked(agent)) throw failure('TASK_CONTROL_HELD');
    return request;
  }, { prepend: true });
  get(ctx, 'tools').guard(exec => exec.agent && blocked(exec.agent)
    && !(exec.name === 'android_observe' && observations.get(String(exec.agent.id)) === exec.callId)
    ? 'TASK_CONTROL_HELD' : undefined);
  ctx.on('agent/disposed', ({ agent }) => bindings.delete(agent));
  ctx.inject(['subagents'], scope => {
    const service = get(scope, 'subagents'), original = service.prompt;
    service.prompt = function(request, signal) {
      if (closed || held(String(request.parentSessionId)) || held(String(request.childSessionId)))
        throw failure('TASK_CONTROL_HELD');
      return original.call(this, request, signal);
    };
  });

  function liveMembers(rootId) {
    const root = agents.get(rootId);
    if (!root) return [];
    const result = [root];
    for (let index = 0; index < result.length; index++)
      for (const candidate of agents.list())
        if (!result.includes(candidate) && agents.isOwnedBy(candidate.id, result[index])) result.push(candidate);
    return result;
  }
  async function settleJobs(member, signal) {
    const jobs = get(ctx, 'jobs');
    if (!jobs) return;
    const owned = jobs.list(member.id).filter(job => job.owner === member.id), live = owned.filter(job => !terminalJob(job));
    for (const job of live) jobs.kill(job.id, member.id, 'DROY_TASK_CONTROL');
    for (const job of live) {
      const settled = await jobs.wait(job.id, 10_000, member.id, signal);
      if (!terminalJob(settled)) throw failure('TASK_CONTROL_JOB_STOP_UNCONFIRMED');
    }
    for (const job of owned) {
      const receipt = member.session.snapshotEvents().findLast(event => event.type === 'job/settled' && event.data.jobId === job.id && event.data.owner === member.id);
      if (receipt?.data.producerReported !== true) throw failure('TASK_CONTROL_JOB_STOP_UNCONFIRMED');
    }
  }
  return {
    liveMembers,
    rootId(id) {
      let current = agents.get(id);
      if (!current) return id;
      const seen = new Set();
      while (!seen.has(current)) {
        seen.add(current);
        const parent = agents.list().find(candidate => agents.isOwnedBy(current.id, candidate));
        if (!parent) return String(current.id);
        current = parent;
      }
      throw failure('TASK_CONTROL_OWNERSHIP_INVALID');
    },
    get: id => agents.get(id),
    async descendants(rootId, signal) {
      const service = get(ctx, 'subagents');
      if (!service) return [];
      return await service.listDescendants(rootId, signal);
    },
    interruptExternal(rootId, rows) {
      const root = agents.get(rootId), service = get(ctx, 'subagents');
      for (const row of rows) if (row.kind === 'child' && row.activity === 'running' && !agents.get(row.id)) {
        if (!root || !service) throw failure('TASK_CONTROL_CHILD_STOP_UNCONFIRMED');
        service.interrupt(row.id, { kind: 'ancestor', agent: root });
      }
    },
    async resolve(id) {
      const present = agents.get(id);
      if (present) return present;
      const result = await get(ctx, 'sessionController').resolveAgent(id);
      if (result.error) throw result.error;
      if (!result.agent || String(result.agent.id) !== id) throw failure('TASK_CONTROL_AGENT_UNAVAILABLE');
      attach(result.agent);
      return result.agent;
    },
    goalSnapshot(agent) {
      const goal = get(ctx, 'goals')?.get(agent);
      return goal ? { id: goal.id, revision: goal.revision, phase: goal.phase, activation: goal.activation } : null;
    },
    disarm(agent) { get(ctx, 'goals')?.disarm(agent); },
    stopGoal(agent) {
      const goals = get(ctx, 'goals'), goal = goals?.get(agent);
      if (goal?.phase === 'active') goals.pause(agent, { id: goal.id, revision: goal.revision });
      else goals?.disarm(agent);
    },
    async settle(members, { keepInbox, signal }) {
      for (const agent of members) { get(ctx, 'goals')?.disarm(agent); agent.cancel({ kind: 'user' }, { keepInbox }); }
      await abortable(Promise.all(members.map(agent => agent.whenIdle())), signal);
      for (const agent of members) {
        signal.throwIfAborted();
        if (keepInbox) restoreClaimed(agent); else { agent.inbox.clear(); bindings.get(agent)?.claimed.clear(); }
        await settleJobs(agent, signal);
        signal.throwIfAborted();
        if (agents.get(agent.id) === agent && await get(ctx, 'sessions').flush(agent.session) !== true)
          throw failure('TASK_CONTROL_FLUSH_UNCONFIRMED');
      }
      await get(ctx, 'sessionPersistence').flush();
    },
    rearmGoal(agent, ref) {
      const goals = get(ctx, 'goals'), current = goals?.get(agent);
      if (ref?.activation === 'armed' && ref.phase === 'active' && current?.id === ref.id && current.revision === ref.revision
        && current.phase === 'active' && current.roundsStarted < current.maxGoalRounds)
        goals.resume(agent, { id: current.id, revision: current.revision });
    },
    isObservationCall(taskId, callId) { return observations.get(taskId) === callId; },
    async observe(agent, callId, signal) {
      if (observations.has(String(agent.id))) throw failure('TASK_CONTROL_OBSERVATION_PENDING');
      observations.set(String(agent.id), callId);
      try {
        const result = await get(ctx, 'tools').execute({ agent, callId, name: 'android_observe', arguments: {}, signal });
        if (result.isError) throw failure('TASK_CONTROL_OBSERVATION_FAILED');
        return result.content;
      } finally { if (observations.get(String(agent.id)) === callId) observations.delete(String(agent.id)); }
    },
    wake(agent, content) {
      const binding = bindings.get(agent);
      if (!binding) throw failure('TASK_CONTROL_AGENT_UNAVAILABLE');
      binding.original.call(agent, createUserMessage({ content, source: { kind: 'user' } }), 'next-step', true);
    },
    dispose() { closed = true; },
  };
}
