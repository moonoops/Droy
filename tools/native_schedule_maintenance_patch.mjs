import { createHash } from 'node:crypto';

export const NATIVE_SCHEDULE_MAINTENANCE_PATCH_VERSION = 1;
const exportAnchor = 'export { MAX_TITLE_LENGTH,';
const changes = [
  ['\trequested = false;\n\t/**\n\t* @param ctx - Host services used to resume and enqueue.',
    '\trequested = false;\n\t_nativeMaintenanceLease;\n\t/**\n\t* @param ctx - Host services used to resume and enqueue.'],
  ['\t\tthis.requested = true;\n\t\tthis.clearTimer();\n\t\tif (this.running !== void 0) return;',
    '\t\tthis.requested = true;\n\t\tthis.clearTimer();\n\t\tif (this._nativeMaintenanceLease !== void 0) return;\n\t\tif (this.running !== void 0) return;'],
  ['\t\t\t\twhile (this.requested && !this.stopping) {',
    '\t\t\t\twhile (this.requested && !this.stopping && this._nativeMaintenanceLease === void 0) {'],
  ['\t\t\tif (this.requested && !this.stopping) this.requestDrive();',
    '\t\t\tif (this.requested && !this.stopping && this._nativeMaintenanceLease === void 0) this.requestDrive();'],
  ['\t\tfor (const task of due) {\n\t\t\tif (this.stopping) return;',
    '\t\tfor (const task of due) {\n\t\t\tif (this.stopping || this._nativeMaintenanceLease !== void 0) return;'],
  ['\t\tif (this.stopping) return;\n\t\tconst next = this.tasks().filter',
    '\t\tif (this.stopping || this._nativeMaintenanceLease !== void 0) return;\n\t\tconst next = this.tasks().filter'],
];
const anchors = [exportAnchor, 'var ScheduleRuntime = class {', 'let ScheduleService = (() => {',
  '\tasync dispose() {\n\t\tthis.stopping = true;\n\t\tthis.clearTimer();\n\t\tawait this.running?.catch(() => void 0);',
  '\t\t\t\tresolved.agent.followup(message);', '\t\tserialize(work) {\n\t\t\tif (this.stopping)',
  '\t\t\t\t\t\tawait this.runtime?.dispose();', ...changes.map(([before]) => before)];

// The provider owns its timer, current delivery and management FIFO. No Agent
// admission decision or persisted schedule field changes at this boundary.
const addition = String.raw`
const nativeScheduleMaintenanceOwners = new WeakMap();
function nativeScheduleMaintenanceError(code, cause) {
  return Object.assign(new Error(code, cause === void 0 ? void 0 : { cause }), { code });
}
/**
 * A transient delivery lease, not an all-Host idle proof. External admissions
 * must be closed separately; accepted Agent continuations may still manage
 * tasks. A later management write invalidates the observed quiet barrier.
 */
function createScheduleMaintenanceLease(schedule, options) {
  const fail = code => { throw nativeScheduleMaintenanceError(code); };
  if (options === null || typeof options !== 'object' || Array.isArray(options)
    || Object.keys(options).some(key => key !== 'operationId')
    || typeof options.operationId !== 'string' || !/^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$/.test(options.operationId))
    fail('DSH_SCHEDULE_MAINTENANCE_INVALID');
  if (!(schedule instanceof ScheduleService) || !(schedule.runtime instanceof ScheduleRuntime))
    fail('DSH_SCHEDULE_MAINTENANCE_NOT_READY');
  const runtime = schedule.runtime, operationId = options.operationId;
  const stopped = () => schedule.stopping || runtime.stopping;
  if (stopped()) fail('DSH_SCHEDULE_MAINTENANCE_STOPPED');
  const previous = nativeScheduleMaintenanceOwners.get(schedule);
  if (previous?.operationId === operationId) return previous.lease;
  if (previous && previous.phase !== 'released') fail('DSH_SCHEDULE_MAINTENANCE_CONFLICT');
  const released = Promise.withResolvers();
  const state = { operationId, phase: 'ready', observedChain: void 0, pending: void 0 };
  const status = () => {
    const held = runtime._nativeMaintenanceLease === state;
    const managementBarrierSettled = held && state.phase === 'suspended' && state.observedChain === schedule.chain;
    return Object.freeze({ version: 1, scope: 'schedule-runtime', operationId,
      phase: stopped() ? 'stopped' : state.phase,
      deliveriesSuspended: held && !stopped(), managementBarrierSettled: managementBarrierSettled && !stopped(),
      quiet: held && !stopped() && managementBarrierSettled && runtime.running === void 0 });
  };
  const drain = async () => {
    while (runtime._nativeMaintenanceLease === state) {
      const chain = schedule.chain, running = runtime.running;
      try { await Promise.all([chain, running]); }
      catch (cause) { throw nativeScheduleMaintenanceError('DSH_SCHEDULE_MAINTENANCE_DRAIN_FAILED', cause); }
      if (runtime._nativeMaintenanceLease !== state) break;
      if (stopped()) fail('DSH_SCHEDULE_MAINTENANCE_STOPPED');
      if (runtime.running === void 0 && schedule.chain === chain) {
        state.observedChain = chain; state.phase = 'suspended';
        return status();
      }
    }
    return status();
  };
  const wait = (promise, signal) => {
    if (signal === void 0) return promise;
    return new Promise((resolve, reject) => {
      const aborted = () => { signal.removeEventListener('abort', aborted); reject(signal.reason); };
      signal.addEventListener('abort', aborted, { once: true });
      promise.then(value => { signal.removeEventListener('abort', aborted); resolve(value); },
        error => { signal.removeEventListener('abort', aborted); reject(error); });
      if (signal.aborted) aborted();
    });
  };
  const lease = Object.freeze({
    suspend(waitOptions = {}) {
      if (waitOptions === null || typeof waitOptions !== 'object' || Array.isArray(waitOptions)
        || Object.keys(waitOptions).some(key => key !== 'signal')
        || waitOptions.signal !== void 0 && !(waitOptions.signal instanceof AbortSignal))
        fail('DSH_SCHEDULE_MAINTENANCE_INVALID');
      const signal = waitOptions.signal;
      signal?.throwIfAborted();
      if (state.phase === 'released') return Promise.resolve(status());
      if (stopped()) fail('DSH_SCHEDULE_MAINTENANCE_STOPPED');
      if (runtime._nativeMaintenanceLease !== void 0 && runtime._nativeMaintenanceLease !== state)
        fail('DSH_SCHEDULE_MAINTENANCE_CONFLICT');
      runtime._nativeMaintenanceLease = state;
      runtime.clearTimer();
      if (state.pending === void 0) {
        state.phase = 'suspending'; state.observedChain = void 0;
        const pending = Promise.race([drain(), released.promise.then(status)]);
        state.pending = pending;
        const settled = () => { if (state.pending === pending) state.pending = void 0; };
        pending.then(settled, settled);
      }
      // Aborting a waiter must not silently reopen a globally held maintenance
      // window. The owner cancels the lease explicitly with resume().
      return wait(state.pending, signal);
    },
    resume() {
      if (state.phase === 'released') return status();
      if (stopped()) fail('DSH_SCHEDULE_MAINTENANCE_STOPPED');
      if (runtime._nativeMaintenanceLease !== void 0 && runtime._nativeMaintenanceLease !== state)
        fail('DSH_SCHEDULE_MAINTENANCE_CONFLICT');
      runtime._nativeMaintenanceLease = void 0;
      state.phase = 'released'; state.observedChain = void 0;
      released.resolve();
      runtime.requestDrive();
      return status();
    },
    status,
  });
  state.lease = lease;
  nativeScheduleMaintenanceOwners.set(schedule, state);
  return lease;
}
`;

export function patchNativeScheduleMaintenance(source) {
  if (typeof source !== 'string' || source.includes('function createScheduleMaintenanceLease(')
    || anchors.some(anchor => source.split(anchor).length !== 2))
    throw new Error('DSH schedule maintenance contract changed');
  for (const [before, after] of changes) source = source.replace(before, after);
  return source.replace(exportAnchor, addition + '\nexport { createScheduleMaintenanceLease, MAX_TITLE_LENGTH,');
}

export function nativeScheduleMaintenancePatchFingerprint(source) {
  const patched = patchNativeScheduleMaintenance(source);
  return { version: NATIVE_SCHEDULE_MAINTENANCE_PATCH_VERSION,
    before: createHash('sha256').update(source).digest('hex'), after: createHash('sha256').update(patched).digest('hex') };
}
