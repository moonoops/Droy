/** Coordinates live barriers only. This receipt never authorizes offline deletion. */
export function createCompanionMaintenanceWindow({ gate, producers, agents, flushSessions, flushPersistence }) {
  if (typeof gate?.acquire !== 'function' || typeof producers !== 'function' || typeof agents !== 'function'
    || typeof flushSessions !== 'function' || typeof flushPersistence !== 'function') throw new TypeError('Invalid maintenance participants');
  let current;
  const fail = code => { throw Object.assign(new Error(code), { code }); };
  const same = (a, b) => a.length === b.length && a.every(value => b.includes(value));
  const inboxEmpty = agent => {
    if (!Array.isArray(agent.inbox?.nextTurn) || !Array.isArray(agent.inbox?.nextStep)) fail('COMPANION_MAINTENANCE_OBSERVATION_FAILED');
    return !agent.inbox.nextTurn.length && !agent.inbox.nextStep.length;
  };
  function open({ operationId, admissionEpoch }) {
    if (current && current.phase !== 'released') {
      if (current.operationId === operationId && current.admissionEpoch === admissionEpoch) return current.api;
      fail('COMPANION_MAINTENANCE_CONFLICT');
    }
    const admission = gate.acquire({ operationId, admissionEpoch });
    const state = { operationId, admissionEpoch, phase: 'ready', holds: [], pending: null, idleAgents: [] };
    const status = () => {
      const ingress = admission.status(), held = state.holds.map(lease => lease.status());
      const agentQuiet = same(state.idleAgents, agents()) && state.idleAgents.every(inboxEmpty);
      return { version: 1, scope: 'host-live-barriers', operationId, admissionEpoch,
        phase: state.phase, admissionsClosed: ingress.admissionsClosed,
        quiet: state.phase === 'sealed' && ingress.admissionsClosed && agentQuiet && held.every(receipt => receipt.quiet),
        admission: ingress, producers: held, hostStopped: false };
    };
    const release = async () => {
      if (state.phase === 'released') return status();
      state.phase = 'released';
      const errors = [];
      for (const lease of [...state.holds].reverse()) { try { await lease.resume(); } catch (error) { errors.push(error); } }
      try { admission.resume(); } catch (error) { errors.push(error); }
      if (errors.length) throw new AggregateError(errors, 'COMPANION_MAINTENANCE_RESUME_FAILED');
      return status();
    };
    const prepare = async () => {
      if (state.phase === 'released') fail('COMPANION_MAINTENANCE_RELEASED');
      if (state.phase === 'sealed') return status();
      state.phase = 'waiting';
      try {
        admission.closeNew();
        if (!state.holds.length) state.holds = producers(operationId);
        await Promise.all(state.holds.map(lease => lease.suspend()));
        // Accepted work may create another Agent. Observe each stable inventory
        // through whenIdle, preserving its original continuation and outcome.
        let observed;
        do {
          observed = agents();
          await Promise.all(observed.map(agent => {
            if (typeof agent.whenIdle !== 'function') fail('COMPANION_MAINTENANCE_OBSERVATION_FAILED');
            return agent.whenIdle();
          }));
          if (state.phase === 'released') return status();
        } while (!same(observed, agents()));
        state.idleAgents = observed;
        const ingress = await admission.waitForQuiet();
        if (state.phase === 'released') return status();
        if (!ingress.quiet || !observed.every(inboxEmpty) || state.holds.some(lease => !lease.status().quiet)) return status();
        await flushSessions();
        await flushPersistence();
        if (state.phase === 'released') return status();
        // A flush may execute queued delivery, so all barriers must still hold.
        if (!same(observed, agents()) || !observed.every(inboxEmpty) || state.holds.some(lease => !lease.status().quiet)) return status();
        admission.seal(); state.phase = 'sealed';
        return status();
      } catch (cause) {
        try { await release(); } catch (recovery) { throw new AggregateError([cause, recovery], 'COMPANION_MAINTENANCE_FAILED'); }
        throw cause;
      }
    };
    state.api = Object.freeze({ status, release,
      prepare() {
        if (!state.pending) {
          const pending = prepare(); state.pending = pending;
          const finish = () => { if (state.pending === pending) state.pending = null; };
          pending.then(finish, finish);
        }
        return state.pending;
      },
    });
    current = state; return state.api;
  }
  return Object.freeze({ open });
}
