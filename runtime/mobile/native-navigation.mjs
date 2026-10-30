import { isAbsolute } from 'node:path';
import { TYPERT as SESSIONS } from '@deepseek-ai/dsh-api-session-controller/typert';
import { TYPERT as WORKSPACES } from '@deepseek-ai/dsh-api-workspace-controller/typert';
import { SESSION_FORMAT_VERSION } from '@deepseek-ai/dsh-session';
import { resolveMobileLocale } from './locale.mjs';

const copy = {
  invalid: ['导航目标无效。', 'The navigation target is invalid.'],
  missing: ['未找到此内容。', 'This content was not found.'],
  unavailable: ['内容尚未就绪，请稍后重试。', 'This content is not ready. Try again later.'],
  denied: ['无法访问此会话。', 'This conversation is not accessible.'],
  mismatch: ['目标信息已变化，请重新打开。', 'The target changed. Open it again.'],
  unsupported: ['此目标暂不支持打开。', 'This target cannot be opened yet.'],
  cancelled: ['打开请求已取消。', 'The open request was cancelled.'],
  failed: ['暂时无法打开，请重试。', 'This content could not be opened. Try again.'],
};
const object = value => value !== null && typeof value === 'object' && !Array.isArray(value);
const identity = (value, maximum = 256) => typeof value === 'string' && value.length <= maximum
  && /^[A-Za-z0-9][A-Za-z0-9_.:-]*$/u.test(value);
const fields = (value, allowed, required = allowed) => object(value)
  && Object.keys(value).every(key => allowed.includes(key)) && required.every(key => Object.hasOwn(value, key));
const get = (ctx, name) => typeof ctx.get === 'function' ? ctx.get(name) : ctx[name];

/** Safe product errors; raw provider exceptions and personal paths never cross this boundary. */
export class NativeNavigationError extends Error {
  constructor(code, status, reason, message) { super(message); this.code = code; this.status = status; this.reason = reason; }
}

/**
 * Read-only resolver for engine-session, companion-chat and companion-task inputs.
 * A result is a navigation snapshot, never authority to issue a later command.
 * Pet request IDs remain opaque pet correlators; this service never resolves decisions.
 */
export function createNativeNavigationService(ctx) {
  const fail = (code, status, reason, key) => {
    throw new NativeNavigationError('NATIVE_NAVIGATION_' + code, status, reason,
      copy[key][resolveMobileLocale(ctx) === 'zh' ? 0 : 1]);
  };
  const bad = () => fail('BAD_REQUEST', 400, 'INVALID_RESOLUTION', 'invalid');
  const unavailable = () => fail('NOT_READY', 503, 'TARGET_NOT_READY', 'unavailable');
  const mismatch = () => fail('TARGET_MISMATCH', 409, 'INVALID_RESOLUTION', 'mismatch');
  const missing = () => fail('NOT_FOUND', 404, 'NOT_FOUND', 'missing');
  function service(name, method) {
    const value = get(ctx, name);
    if (!value || typeof value[method] !== 'function') unavailable();
    return value;
  }
  function spec(api, namespace, method) {
    const value = api.invocations.find(row => row.namespace === namespace && row.method === method);
    if (!value) unavailable();
    return value;
  }
  function checked(api, namespace, method, value) {
    try { return spec(api, namespace, method).result.create().parse(value); }
    catch (error) { if (error instanceof NativeNavigationError) throw error; unavailable(); }
  }
  function input(value) {
    if (!object(value)) bad();
    const shape = new Map([
      ['engine-session', [['kind', 'sessionId', 'petRequestId'], ['kind', 'sessionId']]],
      ['companion-chat', [['kind', 'companionId'], ['kind', 'companionId']]],
      ['companion-task', [['kind', 'companionId', 'taskId', 'sessionId', 'petRequestId'], ['kind', 'companionId', 'taskId', 'sessionId']]],
    ]).get(value.kind);
    if (!shape || !fields(value, ...shape)) bad();
    for (const key of ['sessionId', 'taskId', 'petRequestId'])
      if (Object.hasOwn(value, key) && !identity(value[key])) bad();
    if (Object.hasOwn(value, 'companionId') && !identity(value.companionId, 160)) bad();
    return { ...value };
  }
  function header(value, sessionId) {
    if (!object(value) || value.id !== sessionId || value.version !== SESSION_FORMAT_VERSION
      || !Number.isSafeInteger(value.createdAt) || value.createdAt < 0 || typeof value.isSeeded !== 'boolean'
      || typeof value.cwd !== 'string' || !isAbsolute(value.cwd) || /[\x00-\x1f\x7f]/u.test(value.cwd)
      || value.origin !== undefined && value.origin !== 'subagent'
      || value.parentSession !== undefined && !identity(value.parentSession)
      || value.delegationDepth !== undefined && (!Number.isSafeInteger(value.delegationDepth) || value.delegationDepth < 0)) unavailable();
    return value;
  }
  function store() {
    const runtime = service('droyCompanions', 'ownsSession'), value = runtime.store;
    if (!value || ['getProfile', 'sessionOwner', 'profiles'].some(method => typeof value[method] !== 'function')) unavailable();
    return { runtime, value };
  }
  async function profile(store, companionId) {
    const value = await store.getProfile(companionId);
    if (!object(value) || value.id !== companionId
      || value.workspaceId !== null && !identity(value.workspaceId)
      || value.chatSessionId !== null && !identity(value.chatSessionId)) unavailable();
    return value;
  }
  async function workspaceBaseline(signal) {
    const iterator = service('workspaceController', 'follow').follow(signal)[Symbol.asyncIterator]();
    try {
      const first = await iterator.next();
      if (first.done) unavailable();
      const frame = checked(WORKSPACES, 'workspace', 'follow', first.value);
      if (frame.type !== 'baseline') unavailable();
      const value = frame.value, workspaceIds = new Set(), sessionIds = new Set();
      for (const row of value.items) {
        if (!identity(row.workspaceId) || workspaceIds.has(row.workspaceId)) unavailable();
        workspaceIds.add(row.workspaceId);
        for (const id of row.sessionIds) {
          if (!identity(id) || sessionIds.has(id)) unavailable();
          sessionIds.add(id);
        }
      }
      if ([...value.archivedSessionIds, ...value.pinnedSessionIds].some(id => !identity(id))) unavailable();
      return value;
    } finally { await iterator.return?.(); }
  }
  async function readHeader(sessionId, row, signal) {
    const attached = service('sessions', 'get').get(sessionId);
    const persisted = await service('sessionPersistence', 'stat').stat(sessionId, { signal });
    signal.throwIfAborted();
    if (!attached && persisted === undefined) missing();
    if (persisted !== undefined && (!object(persisted) || !object(persisted.header))) unavailable();
    const value = header(attached?.header ?? persisted.header, sessionId);
    if (persisted !== undefined) {
      const stored = header(persisted.header, sessionId);
      for (const key of ['id', 'version', 'createdAt', 'isSeeded', 'cwd', 'origin', 'parentSession', 'delegationDepth'])
        if (stored[key] !== value[key]) mismatch();
    }
    if (row === undefined) fail('ACCESS_DENIED', 403, 'ACCESS_DENIED', 'denied');
    if (row.cwd !== undefined && row.cwd !== value.cwd || row.origin !== value.origin || row.parentSessionId !== value.parentSession) mismatch();
    if (row.formatStatus === 'migration-required' || !attached && persisted?.formatStatus === 'migration-required')
      fail('MIGRATION_REQUIRED', 409, 'TARGET_NOT_READY', 'unavailable');
    return value;
  }
  async function resolveSession(sessionId, signal) {
    const { runtime, value: companionStore } = store();
    const controller = service('sessionController', 'list');
    const [listed, workspaces, owner, profiles] = await Promise.all([
      controller.list({}, signal), workspaceBaseline(signal), companionStore.sessionOwner(sessionId), companionStore.profiles(),
    ]);
    signal.throwIfAborted();
    const summaries = checked(SESSIONS, 'session', 'list', listed).items, rows = new Map();
    for (const row of summaries) {
      if (!identity(row.sessionId) || rows.has(row.sessionId)) unavailable();
      rows.set(row.sessionId, row);
    }
    if (!Array.isArray(profiles) || profiles.some(row => !object(row) || !identity(row.id, 160)
      || row.workspaceId !== null && !identity(row.workspaceId))) unavailable();
    const meta = await readHeader(sessionId, rows.get(sessionId), signal);
    const workspace = workspaces.items.find(row => row.sessionIds.includes(sessionId));
    const archived = workspaces.archivedSessionIds.includes(sessionId);
    const managedWorkspace = workspace && profiles.some(row => row.workspaceId === workspace.workspaceId);
    const session = { id: sessionId, workspaceId: workspace?.workspaceId ?? null, owner: 'workbench',
      readOnly: archived || !!managedWorkspace, archived, address: { kind: 'session', sessionId } };
    if (owner !== null) {
      if (!object(owner) || owner.sessionId !== sessionId || !identity(owner.companionId, 160)
        || !identity(owner.workspaceId) || !['chat', 'work'].includes(owner.kind)) unavailable();
      if (meta.origin === 'subagent' || meta.delegationDepth > 0) mismatch();
      const saved = await profile(companionStore, owner.companionId);
      if (saved.workspaceId !== owner.workspaceId || workspace?.workspaceId !== owner.workspaceId) mismatch();
      if (owner.kind === 'chat' && owner.taskId !== null) mismatch();
      if (owner.kind === 'work') {
        if (!identity(owner.taskId) || typeof companionStore.getMessage !== 'function') unavailable();
        const message = await companionStore.getMessage(owner.companionId, owner.taskId);
        if (message === null) missing();
        if (!object(message) || message.id !== owner.taskId || message.type !== 'task' || message.role !== 'assistant'
          || !object(message.task) || message.task.id !== owner.taskId || message.task.sessionId !== sessionId) mismatch();
      }
      Object.assign(session, { owner: 'companion', readOnly: true, companionId: owner.companionId,
        ...(owner.kind === 'work' ? { taskId: owner.taskId } : {}) });
      signal.throwIfAborted();
      return { session, profile: saved, binding: owner };
    }
    if (runtime.ownsSession(sessionId)) unavailable();
    if (meta.origin === 'subagent') {
      if (!identity(meta.parentSession) || meta.parentSession === sessionId) mismatch();
      await readHeader(meta.parentSession, rows.get(meta.parentSession), signal);
      const address = { kind: 'subagent', parentSessionId: meta.parentSession, childSessionId: sessionId, mode: 'unknown' };
      // The public empty-page read validates same-cut origin, direct parent and
      // descriptor ownership; unlike follow it never promotes an ordinary Agent.
      const request = { address, throughSeq: -1, maxMessages: 1 };
      spec(SESSIONS, 'session', 'page').input({ strict: true }).create().parse({ request });
      checked(SESSIONS, 'session', 'page', await service('sessionController', 'page').page(request, signal));
      Object.assign(session, { owner: 'subagent', readOnly: true, address });
    } else if (meta.delegationDepth > 0) fail('SUBAGENT_UNPROVEN', 409, 'UNSUPPORTED', 'unsupported');
    signal.throwIfAborted();
    return { session };
  }
  const result = (verified, request) => {
    const { session, profile, binding } = verified;
    if (binding?.kind === 'work') return { kind: 'companion-task', session,
      ...(request.petRequestId === undefined ? {} : { petRequestId: request.petRequestId }) };
    // An archived or superseded chat must keep its exact read-only Session.
    // A pet correlator also retains that Session instead of becoming a decision ID.
    if (binding?.kind === 'chat' && profile.chatSessionId === session.id && !session.archived && request.petRequestId === undefined)
      return { kind: 'companion-chat', companionId: binding.companionId, chatSessionId: session.id };
    return { kind: 'engine-session', session, ...(request.petRequestId === undefined ? {} : { petRequestId: request.petRequestId }) };
  };
  return Object.freeze({
    async resolve(value, options = {}) {
      try {
        const request = input(value);
        if (!fields(options, ['signal'], []) || options.signal !== undefined && !(options.signal instanceof AbortSignal)) bad();
        const signal = options.signal ?? new AbortController().signal;
        signal.throwIfAborted();
        if (request.kind === 'companion-chat') {
          const saved = await profile(store().value, request.companionId);
          signal.throwIfAborted();
          if (saved.chatSessionId === null) return { kind: 'companion-chat', companionId: saved.id, chatSessionId: null };
          const verified = await resolveSession(saved.chatSessionId, signal);
          signal.throwIfAborted();
          if (verified.binding?.kind !== 'chat' || verified.binding.companionId !== request.companionId
            || verified.profile.chatSessionId !== saved.chatSessionId) mismatch();
          return result(verified, request);
        }
        const verified = await resolveSession(request.sessionId, signal);
        signal.throwIfAborted();
        if (request.kind === 'companion-task' && (verified.binding?.kind !== 'work'
          || verified.binding.companionId !== request.companionId || verified.binding.taskId !== request.taskId)) mismatch();
        return result(verified, request);
      } catch (error) {
        if (error instanceof NativeNavigationError) throw error;
        if (options?.signal?.aborted) fail('CANCELLED', 499, 'DISCONNECTED', 'cancelled');
        if (['COMPANION_NOT_FOUND', 'COMPANION_TASK_NOT_FOUND', 'session/not-found', 'subagent/not-found',
          'SESSION_QUERY_SESSION_NOT_FOUND'].includes(error?.code)) missing();
        if (['subagent/unauthorized', 'remote-access/denied'].includes(error?.code)) fail('ACCESS_DENIED', 403, 'ACCESS_DENIED', 'denied');
        if (error?.code === 'subagent/catalog-diagnostic') fail('SUBAGENT_UNPROVEN', 409, 'UNSUPPORTED', 'unsupported');
        if (error?.code === 'session/migration-required')
          fail('MIGRATION_REQUIRED', 409, 'TARGET_NOT_READY', 'unavailable');
        if (error?.name === 'SessionFormatUnsupportedError') fail('FORMAT_UNSUPPORTED', 422, 'UNSUPPORTED', 'unsupported');
        if (error?.code === 'COMPANION_STORE_CLOSED') unavailable();
        if (error?.code === 'SESSION_QUERY_ABORTED') fail('CANCELLED', 499, 'DISCONNECTED', 'cancelled');
        fail('FAILED', 500, 'RESOLVER_FAILED', 'failed');
      }
    },
  });
}
