const fail = code => { throw Object.assign(new Error(code), { code }); };
/** Observe returned Agent idle barriers, not the UI's running/idle status label. */
export function createHostMaintenanceExecution(ctx) {
  const observed = new Map(), subscriptions = [];
  function observe(agent) {
    const token = { done: null, settled: false };
    token.done = Promise.resolve().then(() => agent.whenIdle());
    observed.set(agent, token);
    token.done.then(() => { token.settled = true; }, () => { token.failed = true; token.settled = true; });
  }
  for (const event of ['agent/created', 'agent/status', 'agent/inbox/inserted']) subscriptions.push(ctx.on(event, ({ agent }) => observe(agent)));
  subscriptions.push(ctx.on('agent/disposed', ({ agent }) => observed.delete(agent)));
  for (const agent of ctx.agents.list()) observe(agent);
  return {
    inspect() {
      const nativeTasks=ctx.get('droyNativeUserTasks');
      if(typeof nativeTasks?.inspect!=='function')fail('COMPANION_MAINTENANCE_OBSERVATION_FAILED');
      const values = nativeTasks.inspect();
      const writers = [...values.writers], busy = [...values.busy], unfenced = [...values.unfenced];
      const jobs = new Map(), owners = new Set([undefined, ...ctx.sessions.list().map(session => session.id)]);
      if (typeof ctx.get('jobs')?.list !== 'function') unfenced.push({ kind: 'jobs-unavailable', count: 1 });
      else for (const owner of owners) for (const job of ctx.get('jobs').list(owner)) jobs.set(job.id, job);
      const running = [...jobs.values()].filter(job => ['running', 'stopping'].includes(job.status));
      if (running.length) busy.push({ kind: 'background-jobs', count: running.length });
      for (const agent of ctx.agents.list()) {
        if (!observed.has(agent)) observe(agent);
        const entry = observed.get(agent);
        if (entry.failed) unfenced.push({ kind: 'agent-idle-failed', count: 1 });
        else if (!entry.settled) writers.push({ kind: 'agent', done: entry.done });
        if (!Array.isArray(agent.inbox?.nextTurn) || !Array.isArray(agent.inbox?.nextStep)) unfenced.push({ kind: 'agent-inbox-unknown', count: 1 });
        else if (agent.inbox.nextTurn.length || agent.inbox.nextStep.length) busy.push({ kind: 'agent-input', count: agent.inbox.nextTurn.length + agent.inbox.nextStep.length });
        const terminal = ctx.get('terminals');
        if (terminal?.hasOwnerActivity(agent)) busy.push({ kind: 'agent-terminal', count: 1 });
      }
      const webTerminal = ctx.get('terminalController');
      if (webTerminal) for (const owner of owners) if (owner !== undefined) {
        const values = webTerminal.list(owner);
        if (!Array.isArray(values)) fail('COMPANION_MAINTENANCE_OBSERVATION_FAILED');
        const running = values.filter(row => row.state === 'running').length;
        if (running) busy.push({ kind: 'web-terminal', count: running });
      }
      return { writers, busy, unfenced };
    },
    dispose() { for (const stop of subscriptions) stop(); },
  };
}

