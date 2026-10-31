import { AsyncLocalStorage } from 'node:async_hooks';
import { normalizeMobileLocale, resolveMobileLocale } from '../locale.mjs';

const gates = new WeakMap();
const roles = new Set(['operation', 'control', 'observer']);
const identity = /^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$/u;
const messages = {
  closed: ['正在维护，请稍后重试', 'Maintenance is in progress. Try again later.'],
  busy: ['还有任务正在进行', 'Tasks are still in progress.'],
  unfenced: ['尚未确认所有操作入口已关闭', 'Not all operation channels are confirmed closed.'],
  invalid: ['维护请求无效', 'Invalid maintenance request.'],
  conflict: ['已有其他维护操作', 'Another maintenance operation owns this window.'],
  notReady: ['维护接纳门尚未就绪', 'The maintenance admission gate is not ready.'],
  observation: ['无法确认当前任务状态', 'The current task state could not be verified.'],
};
const object = value => value !== null && typeof value === 'object' && !Array.isArray(value);
const exact = (value, keys) => object(value) && Object.keys(value).every(key => keys.includes(key));
const promise = value => value instanceof Promise;
const counts = rows => Object.freeze([...rows].map(([kind, count]) => Object.freeze({ kind, count })));
const add = (table, kind, count = 1) => table.set(kind, (table.get(kind) ?? 0) + count);
const identifier = value => typeof value === 'string' && identity.test(value);

function error(code, key, locale = 'en', cause) {
  return Object.assign(new Error(messages[key][normalizeMobileLocale(locale) === 'zh' ? 0 : 1],
    cause === undefined ? undefined : { cause }), { code: 'DROY_MAINTENANCE_' + code });
}
function abortable(source, signal) {
  if (signal === undefined) return source;
  return new Promise((resolve, reject) => {
    const abort = () => { signal.removeEventListener('abort', abort); reject(signal.reason); };
    signal.addEventListener('abort', abort, { once: true });
    source.then(value => { signal.removeEventListener('abort', abort); resolve(value); },
      reason => { signal.removeEventListener('abort', abort); reject(reason); });
    if (signal.aborted) abort();
  });
}

/**
 * This gate proves external user admission only. Producer holds, accepted Agent
 * work, the complete root disposer, Node exit and Java writes need their own
 * authoritative barriers before an offline storage capability can be minted.
 */
export function createMaintenanceAdmission(options = {}) {
  if (!exact(options, ['inspectExecution', 'locale'])
    || options.inspectExecution !== undefined && typeof options.inspectExecution !== 'function'
    || options.locale !== undefined && typeof options.locale !== 'function' && !['en', 'zh'].includes(options.locale))
    throw error('INVALID', 'invalid');
  const context = new AsyncLocalStorage(), active = new Map();
  let owner, revision = 0, installation, lastInventory = new Set(), lastFacts = '';
  let locale = () => typeof options.locale === 'function' ? options.locale() : options.locale;
  const fail = (code, key, cause) => { throw error(code, key, locale(), cause); };
  const changed = () => { revision++; };
  const run = (meta, operation, key = {}) => {
    if (!exact(meta, ['carrier', 'endpoint', 'role']) || !identifier(meta.carrier)
      || typeof meta.endpoint !== 'string' || !meta.endpoint
      || !roles.has(meta.role) || typeof operation !== 'function') fail('INVALID', 'invalid');
    const inherited = context.getStore();
    // Only a still-live, already accepted writer may delegate synchronously or
    // asynchronously. A read observer cannot launder a new write after closeNew.
    const delegated = inherited?.active && inherited.role !== 'observer';
    if (!delegated && (owner?.phase === 'sealed' || owner?.phase === 'closing' && meta.role === 'operation')) fail('CLOSED', 'closed');
    const done = Promise.withResolvers();
    const token = { active: true, role: meta.role, done: done.promise };
    active.set(key, token); if (token.role !== 'observer') changed();
    const finish = () => {
      if (!token.active) return;
      token.active = false; active.delete(key); done.resolve(); if (token.role !== 'observer') changed();
    };
    try {
      const result = context.run(token, operation);
      if (result !== null && (typeof result === 'object' || typeof result === 'function') && typeof result.then === 'function')
        Promise.resolve(result).then(finish, finish);
      else finish();
      return result;
    } catch (cause) { finish(); throw cause; }
  };
  const classify = (row, kind) => {
    let role;
    try { role = kind === 'http' ? installation.classifyHttp(row) : installation.classifyRemote(row); }
    catch (cause) { fail('OBSERVATION_FAILED', 'observation', cause); }
    if (!roles.has(role)) fail('OBSERVATION_FAILED', 'observation');
    return role;
  };
  function inspect() {
    const writers = new Map(), observers = new Set(), unfenced = new Map(), busy = new Map();
    const entries = new Set();
    for (const [key, token] of active) {
      if (token.role === 'observer') observers.add(key); else { entries.add(key); writers.set(key, token.done); }
    }
    if (!installation) add(unfenced, 'transport-not-installed');
    else {
      let web, remote;
      try { web = installation.web(); remote = installation.remote(); }
      catch (cause) { fail('OBSERVATION_FAILED', 'observation', cause); }
      if (!exact(web, ['version', 'requests', 'upgrades']) || web.version !== 1 || !Array.isArray(web.requests) || !Array.isArray(web.upgrades)
        || !exact(remote, ['version', 'streams', 'muxRoutes']) || remote.version !== 1 || !Array.isArray(remote.streams)
        || !Array.isArray(remote.muxRoutes)) fail('OBSERVATION_FAILED', 'observation');
      for (const row of web.requests) {
        if (!object(row) || !promise(row.done) || !row.req || !row.res) fail('OBSERVATION_FAILED', 'observation');
        if (classify(row, 'http') === 'observer') observers.add(row); else { entries.add(row); writers.set(row, row.done); }
      }
      for (const row of web.upgrades) {
        if (!object(row) || !promise(row.done) || !row.socket || !object(row.route) || typeof row.path !== 'string') fail('OBSERVATION_FAILED', 'observation');
        if (row.path === '/api/remote.mux' && remote.muxRoutes.includes(row.route)) observers.add(row);
        else { entries.add(row); add(unfenced, 'web-upgrade-unfenced'); }
      }
      for (const row of remote.streams) {
        if (!object(row) || !promise(row.done) || typeof row.endpoint !== 'string'
          || !['none', 'duplex', 'unknown'].includes(row.input)) fail('OBSERVATION_FAILED', 'observation');
        if (row.input !== 'none') { entries.add(row); add(unfenced, row.input === 'duplex' ? 'remote-duplex-input' : 'remote-input-unknown'); continue; }
        if (classify(row, 'remote') === 'observer') observers.add(row); else { entries.add(row); writers.set(row, row.done); }
      }
    }
    if (!options.inspectExecution) add(unfenced, 'execution-not-proven');
    else {
      let execution;
      try { execution = options.inspectExecution(); }
      catch (cause) { fail('OBSERVATION_FAILED', 'observation', cause); }
      if (!exact(execution, ['writers', 'busy', 'unfenced'])
        || !Array.isArray(execution.writers) || !Array.isArray(execution.busy) || !Array.isArray(execution.unfenced)) fail('OBSERVATION_FAILED', 'observation');
      for (const row of execution.writers) {
        if (!exact(row, ['kind', 'done']) || !identifier(row.kind) || !promise(row.done)) fail('OBSERVATION_FAILED', 'observation');
        entries.add(row.done); writers.set(row.done, row.done);
      }
      for (const [rows, target] of [[execution.busy, busy], [execution.unfenced, unfenced]]) for (const row of rows) {
        if (!exact(row, ['kind', 'count']) || !identifier(row.kind) || !Number.isSafeInteger(row.count) || row.count < 1)
          fail('OBSERVATION_FAILED', 'observation');
        add(target, row.kind, row.count);
      }
    }
    // Identity comes from carrier-owned rows / actual promises, not a mirror of
    // TaskProgress or Agent.status. Snapshots are invalidated by later entrants.
    const facts = JSON.stringify([counts(unfenced), counts(busy), writers.size]);
    if (facts !== lastFacts || entries.size !== lastInventory.size || [...entries].some(value => !lastInventory.has(value))) {
      lastInventory = entries; lastFacts = facts; changed();
    }
    return { writers, observers, unfenced, busy };
  }
  function status(state) {
    const observed = inspect(), held = state !== undefined && owner === state && state.phase !== 'released';
    const quiet = held && state.phase !== 'ready' && !observed.writers.size && !observed.unfenced.size && !observed.busy.size;
    return Object.freeze({ version: 1, scope: 'user-admissions', operationId: state?.operationId ?? null,
      admissionEpoch: state?.admissionEpoch ?? null, revision, phase: state?.phase ?? 'open',
      newOperationsClosed: held && state.phase !== 'ready',
      admissionsClosed: quiet && state.phase === 'sealed' && state.sealedRevision === revision,
      quiet, writers: observed.writers.size, observers: observed.observers.size,
      busy: counts(observed.busy), unfenced: counts(observed.unfenced) });
  }
  function waitForQuiet(state, waitOptions = {}) {
    if (!exact(waitOptions, ['signal']) || waitOptions.signal !== undefined && !(waitOptions.signal instanceof AbortSignal)) fail('INVALID', 'invalid');
    waitOptions.signal?.throwIfAborted();
    if (state.phase === 'ready') fail('NOT_CLOSED', 'notReady');
    const drain = async () => {
      while (owner === state && state.phase !== 'released') {
        const observed = inspect();
        // Unfenced carriers have no safe drain operation here. Return an honest
        // busy receipt instead of terminating them or waiting without a bound.
        if (observed.unfenced.size || observed.busy.size || !observed.writers.size) return status(state);
        await Promise.race([Promise.allSettled([...new Set(observed.writers.values())]), state.released.promise]);
      }
      return status(state);
    };
    return abortable(drain(), waitOptions.signal);
  }
  const gate = Object.freeze({
    acquire(request) {
      if (!exact(request, ['operationId', 'admissionEpoch']) || !identifier(request.operationId) || !identifier(request.admissionEpoch)) fail('INVALID', 'invalid');
      if (owner?.operationId === request.operationId && owner.admissionEpoch === request.admissionEpoch) return owner.lease;
      if (owner && owner.phase !== 'released') fail('CONFLICT', 'conflict');
      const state = { ...request, phase: 'ready', released: Promise.withResolvers(), sealedRevision: undefined };
      const lease = Object.freeze({
        closeNew() {
          if (state.phase === 'released') return status(state);
          if (owner !== state) fail('CONFLICT', 'conflict');
          if (state.phase === 'ready') { state.phase = 'closing'; changed(); }
          return status(state);
        },
        waitForQuiet: options => waitForQuiet(state, options),
        status: () => status(state),
        seal() {
          if (state.phase === 'released' || owner !== state) fail('CONFLICT', 'conflict');
          if (state.phase === 'ready') fail('NOT_CLOSED', 'notReady');
          const receipt = status(state);
          if (receipt.unfenced.length) fail('UNFENCED', 'unfenced');
          if (!receipt.quiet) fail('BUSY', 'busy');
          state.phase = 'sealed'; changed(); state.sealedRevision = revision;
          return status(state);
        },
        resume() {
          if (state.phase === 'released') return status(state);
          if (owner !== state) fail('CONFLICT', 'conflict');
          state.phase = 'released'; state.sealedRevision = undefined; changed(); state.released.resolve();
          return status(state);
        },
      });
      state.lease = lease; owner = state; changed(); return lease;
    },
    runExternal: (meta, operation) => run(meta, operation),
    trackExplicit(scope, pending) {
      if (!identifier(scope) || !promise(pending)) fail('INVALID', 'invalid');
      // Register an already accepted detached operation, including one spawned
      // by a live continuation while new admissions are closed. Never replay it.
      const token = { active: true, role: 'operation', done: pending }, key = {};
      active.set(key, token); changed();
      const finish = () => { if (token.active) { token.active = false; active.delete(key); changed(); } };
      pending.then(finish, finish); return pending;
    },
    status: () => status(owner),
  });
  gates.set(gate, {
    run,
    attach(value, readLocale) {
      if (installation) fail('CONFLICT', 'conflict');
      installation = value; locale = readLocale; changed();
      try { inspect(); }
      catch (cause) { installation = undefined; changed(); throw cause; }
      return () => { if (installation === value) { installation = undefined; changed(); } };
    },
  });
  return gate;
}

// Session.follow can prepare and promote a cold Session; it is deliberately
// outside the read-only set even though its downlink is a history stream.
const remoteObservers = new Set(['$events', 'session/control', 'workspace/follow', 'workspaceFiles/changes']);
/** Host route identities only. Every allowed control still calls its original authenticated handler. */
export function classifyMaintenanceHttp(row) {
  const { method, path } = row;
  if (path === '/droy/native/maintenance/companion' && ['GET', 'POST', 'DELETE'].includes(method)) return 'observer';
  if (method === 'POST' && (path === '/api/$events/result' || path === '/api/session/cancel'
    || path === '/droy/native/session/cancel' || path === '/droy/pet/action'
    || /^\/droy\/native\/decisions\/[a-f0-9-]{36}\/answer$/iu.test(path ?? '')
    || /^\/droy\/companions\/[A-Za-z0-9][A-Za-z0-9_-]{0,127}\/cancel$/u.test(path ?? ''))) return 'control';
  if (method === 'GET' && ['/droy/native/decisions', '/droy/native/session/control', '/droy/companions/events'].includes(path)) return 'observer';
  return 'operation';
}
export function classifyMaintenanceRemote(row) {
  return row.input === 'none' && remoteObservers.has(row.endpoint) ? 'observer' : 'operation';
}

/**
 * Install only with the two provider-owned inventory exports from the matching
 * patches. In-process Gateway.invoke/stream and accepted service calls are not
 * intercepted. The disposer unregisters hooks; it never closes a carrier.
 */
export function installMaintenanceAdmission(ctx, options) {
  if (!exact(options, ['gate', 'inspectWebIngress', 'inspectRemoteIngress', 'classifyHttp', 'classifyRemote'])
    || !gates.has(options.gate) || typeof ctx?.on !== 'function' || typeof ctx?.get !== 'function' || typeof ctx?.effect !== 'function'
    || typeof options.inspectWebIngress !== 'function' || typeof options.inspectRemoteIngress !== 'function'
    || options.classifyHttp !== undefined && typeof options.classifyHttp !== 'function'
    || options.classifyRemote !== undefined && typeof options.classifyRemote !== 'function')
    throw error('INVALID', 'invalid', resolveMobileLocale(ctx));
  const { gate } = options, internal = gates.get(gate);
  const classifyHttp = options.classifyHttp ?? classifyMaintenanceHttp;
  const classifyRemote = options.classifyRemote ?? classifyMaintenanceRemote;
  const role = (classifier, row) => {
    let value;
    try { value = classifier(row); }
    catch (cause) { throw error('OBSERVATION_FAILED', 'observation', resolveMobileLocale(ctx), cause); }
    if (!roles.has(value)) throw error('OBSERVATION_FAILED', 'observation', resolveMobileLocale(ctx));
    return value;
  };
  const muxRole = row => {
    let observed;
    try { observed = options.inspectRemoteIngress(ctx.get('typertGateway')); }
    catch (cause) { throw error('OBSERVATION_FAILED', 'observation', resolveMobileLocale(ctx), cause); }
    if (observed?.version !== 1 || !Array.isArray(observed.muxRoutes))
      throw error('OBSERVATION_FAILED', 'observation', resolveMobileLocale(ctx));
    return row.path === '/api/remote.mux' && observed.muxRoutes.includes(row.route) ? 'observer' : 'operation';
  };
  const release = internal.attach({
    web: () => options.inspectWebIngress(ctx.get('webServer')),
    remote: () => options.inspectRemoteIngress(ctx.get('typertGateway')),
    classifyHttp, classifyRemote,
  }, () => resolveMobileLocale(ctx));
  const disposers = [];
  try {
    disposers.push(ctx.on('webserver/request', (req, res, row, next) => {
      // This fixed owner endpoint can inspect/resume a sealed window. Its handler
      // still enforces the original session credential and exact operation ID.
      if (row.path === '/droy/native/maintenance/companion' && ['GET', 'POST', 'DELETE'].includes(row.method)) return next();
      try { return internal.run({ carrier: 'http', endpoint: row.path ?? '/', role: role(classifyHttp, row) }, next, row); }
      catch (cause) {
        if (cause?.code !== 'DROY_MAINTENANCE_CLOSED') throw cause;
        res.writeHead(423, { 'Content-Type': 'application/json; charset=utf-8', 'Cache-Control': 'no-store' });
        res.end(JSON.stringify({ ok: false, error: { code: cause.code, message: cause.message } }));
      }
    }, { prepend: true }));
    disposers.push(ctx.on('webserver/upgrade', (req, socket, head, row, next) => {
      try { return internal.run({ carrier: 'upgrade', endpoint: row.path, role: muxRole(row) }, next); }
      catch (cause) {
        if (cause?.code !== 'DROY_MAINTENANCE_CLOSED') throw cause;
        // Only a new, not-yet-upgraded socket is rejected. Existing upgraded
        // sockets remain intact and must appear in the inventory until closed.
        socket.end('HTTP/1.1 423 Locked\r\nConnection: close\r\nContent-Length: 0\r\n\r\n');
      }
    }, { prepend: true }));
    disposers.push(ctx.on('gateway/wire-stream-open', (row, next) => {
      try { return internal.run({ carrier: 'remote-stream', endpoint: row.endpoint, role: role(classifyRemote, row) }, next, row); }
      catch (cause) {
        if (cause?.code !== 'DROY_MAINTENANCE_CLOSED') throw cause;
        throw row.failure(cause.code, cause.message);
      }
    }, { prepend: true }));
  } catch (cause) { for (const dispose of disposers.reverse()) dispose(); release(); throw cause; }
  let disposed = false;
  const dispose = () => {
    if (disposed) return;
    disposed = true;
    for (const dispose of disposers.reverse()) dispose();
    release();
  };
  try { return ctx.effect(() => dispose, 'droy: maintenance admission'); }
  catch (cause) { dispose(); throw cause; }
}
