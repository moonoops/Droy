import { RemoteError, remoteErrorOf } from '@deepseek-ai/dsh-typert-protocol';
import { resolveMobileLocale } from './locale.mjs';
import { assertUserCreate, assertUserDirectory, assertUserSession, companionProfiles, requireCompanionRuntime,
  userControlFrames, userSessionList } from './task-ownership.mjs';

const get = (ctx, name) => typeof ctx.get === 'function' ? ctx.get(name) : ctx[name];
const object = value => value !== null && typeof value === 'object' && !Array.isArray(value);
const message = ctx => resolveMobileLocale(ctx) === 'zh' ? '请从伙伴对话处理此内容。' : 'Manage this content from its companion conversation.';
const denied = ctx => new RemoteError('droy/companion-managed', message(ctx), {});

async function guard(ctx, endpoint, payload) {
  if (get(ctx, 'droyLifecycle')?.stopping) throw new RemoteError('DROY_STOPPING', resolveMobileLocale(ctx) === 'zh'
    ? '引擎正在停止，请稍后重试。' : 'The runtime is stopping. Try again shortly.', {});
  const [namespace, method] = endpoint.split('/');
  const args = payload?.args, request = object(args?.request) ? args.request : args;
  if (!object(request)) return; // The original codec reports malformed requests.
  const ids = [args, request].flatMap(value => [value.sessionId, value.agentId, value.workspaceFileScopeId, value.address?.sessionId,
    value.address?.parentSessionId, value.address?.childSessionId, value.beforeSessionId]).filter(id => typeof id === 'string');
  if (!ids.length && !['session', 'workspace', 'schedule'].includes(namespace)) return;
  requireCompanionRuntime(ctx);
  for (const id of ids) assertUserSession(ctx, id);
  if (namespace === 'session' && method === 'create') await assertUserCreate(ctx, request);
  if (namespace === 'workspace') {
    const ids = new Set((await companionProfiles(ctx)).map(row => row.workspaceId).filter(Boolean));
    if ([request.workspaceId, request.beforeWorkspaceId].some(id => ids.has(id))) throw denied(ctx);
    if (typeof request.path === 'string') await assertUserDirectory(ctx, request.path);
  }
}

async function userWorkspaceBaseline(ctx, value) {
  const runtime = requireCompanionRuntime(ctx), ids = new Set((await companionProfiles(ctx)).map(row => row.workspaceId).filter(Boolean));
  const userSession = id => !runtime.ownsSession(id);
  return { ...value, items: value.items.filter(row => !ids.has(row.workspaceId))
    .map(row => ({ ...row, sessionIds: row.sessionIds.filter(userSession) })),
  archivedSessionIds: value.archivedSessionIds.filter(userSession), pinnedSessionIds: value.pinnedSessionIds.filter(userSession) };
}

async function projectResult(ctx, endpoint, value) {
  const runtime = requireCompanionRuntime(ctx);
  if (endpoint === 'session/list') {
    const { managedSessionIds: _managed, ...result } = userSessionList(ctx, value); return result;
  }
  if (endpoint === 'schedule/catalog') return value.filter(row => !runtime.ownsSession(row.sessionId));
  if (!endpoint.startsWith('workspace/')) return value;
  if (!object(value)) return value;
  const ids = new Set((await companionProfiles(ctx)).map(row => row.workspaceId).filter(Boolean));
  if (value?.workspace && ids.has(value.workspace.workspaceId)) throw denied(ctx);
  const result = { ...value };
  if (value?.workspace) result.workspace = { ...value.workspace, sessionIds: value.workspace.sessionIds.filter(id => !runtime.ownsSession(id)) };
  if (value?.workspaceIds) result.workspaceIds = value.workspaceIds.filter(id => !ids.has(id));
  for (const key of ['archivedSessionIds', 'pinnedSessionIds']) if (value?.[key]) result[key] = value[key].filter(id => !runtime.ownsSession(id));
  return result;
}

async function* workspaceFrames(ctx, source) {
  for await (const frame of source) {
    const runtime = requireCompanionRuntime(ctx), ids = new Set((await companionProfiles(ctx)).map(row => row.workspaceId).filter(Boolean));
    if (frame.type === 'baseline' || frame.type === 'replace') yield { ...frame, value: await userWorkspaceBaseline(ctx, frame.value) };
    else if (frame.type === 'upsert') {
      if (!ids.has(frame.workspace.workspaceId)) yield { ...frame, workspace: { ...frame.workspace, sessionIds: frame.workspace.sessionIds.filter(id => !runtime.ownsSession(id)) } };
    } else if (frame.type === 'remove') { if (!ids.has(frame.workspaceId)) yield frame; }
    else if (frame.type === 'order') yield { ...frame, workspaceIds: frame.workspaceIds.filter(id => !ids.has(id)) };
    else if (frame.type === 'archived') yield { ...frame, archivedSessionIds: frame.archivedSessionIds.filter(id => !runtime.ownsSession(id)) };
    else if (frame.type === 'pinned') yield { ...frame, pinnedSessionIds: frame.pinnedSessionIds.filter(id => !runtime.ownsSession(id)) };
    else yield frame;
  }
}

async function* eventFrames(ctx, gateway, source, signal, peer) {
  let clientId;
  for await (const frame of source) {
    const runtime = requireCompanionRuntime(ctx);
    if (frame.type === 'ready') clientId = frame.clientId;
    if (frame.type === 'emit' && frame.event.startsWith('api-session/')) {
      const id = frame.event === 'api-session/added' ? frame.args[0]?.sessionId : frame.args[0];
      if (typeof id === 'string' && runtime.ownsSession(id)) continue;
    }
    if (frame.type === 'waterfall' && runtime.ownsSession(frame.agentId)) {
      // Delegate to the Host's real policy; a background request never receives an invented approval.
      const receipt = await gateway.dispatchRpc('$events/result', { args: { clientId, eventId: frame.eventId, outcome: { kind: 'next' } } }, signal, peer);
      if (receipt.ok !== true) throw denied(ctx);
      continue;
    }
    yield frame;
  }
}

/** Adapt only carrier entry points. Internal controller and Gateway calls keep the complete engine view. */
export function installUserRemoteOwnership(ctx) {
  return ctx.inject(['typertGateway', 'droyCompanions'], scope => {
    const gateway = get(scope, 'typertGateway'), invoke = gateway.invokeRpc, open = gateway.openWireStream;
    if (typeof invoke !== 'function' || typeof open !== 'function') throw new Error('DROY_REMOTE_OWNERSHIP_CONTRACT');
    const failure = error => {
      const remote = remoteErrorOf(error);
      if (remote) return new RemoteError(remote.code, remote.message, remote.details);
      return ['COMPANION_SESSION_MANAGED', 'COMPANION_WORKSPACE_MANAGED'].includes(error?.code)
        ? denied(scope) : new RemoteError('droy/ownership-unavailable', resolveMobileLocale(scope) === 'zh'
          ? '任务归属暂时无法确认，请稍后重试。' : 'Task ownership could not be confirmed. Try again later.', {});
    };
    const wrappedInvoke = async function(endpoint, payload, signal, peer) {
      try {
        await guard(scope, endpoint, payload);
        const result = await invoke.call(this, endpoint, payload, signal, peer);
        if (!result.ok || !['session/list', 'schedule/catalog'].includes(endpoint) && !endpoint.startsWith('workspace/')) return result;
        return { ...result, value: await projectResult(scope, endpoint, result.value) };
      } catch (error) {
        const rejected = failure(error); return { ok: false, error: { code: rejected.code, message: rejected.message, details: rejected.details } };
      }
    };
    const wrappedOpen = async function(endpoint, payload, uplink, peer, signal, ...rest) {
      try {
        await guard(scope, endpoint, payload);
        const source = await open.call(this, endpoint, payload, uplink, peer, signal, ...rest);
        if (endpoint === 'session/control') return userControlFrames(scope, source);
        if (endpoint === 'workspace/follow') return workspaceFrames(scope, source);
        if (endpoint === '$events') return eventFrames(scope, this, source, signal, peer);
        return source;
      } catch (error) { throw failure(error); }
    };
    gateway.invokeRpc = wrappedInvoke; gateway.openWireStream = wrappedOpen;
    scope.effect(() => () => {
      if (gateway.invokeRpc === wrappedInvoke) gateway.invokeRpc = invoke;
      if (gateway.openWireStream === wrappedOpen) gateway.openWireStream = open;
    });
  });
}
