import { realpath } from 'node:fs/promises';
import { isAbsolute, join, relative, sep } from 'node:path';

const get = (ctx, name) => typeof ctx.get === 'function' ? ctx.get(name) : ctx[name];
const failure = code => Object.assign(new Error(code), { code });

export function requireCompanionRuntime(ctx) {
  const runtime = get(ctx, 'droyCompanions');
  if (!runtime || typeof runtime.ownsSession !== 'function') throw failure('DROY_OWNERSHIP_NOT_READY');
  return runtime;
}

export function isCompanionSession(ctx, id) {
  const runtime = get(ctx, 'droyCompanions');
  return typeof runtime?.ownsSession === 'function' && runtime.ownsSession(String(id));
}

// An uninitialized ownership index cannot establish that a public event belongs to the user.
export function isUserSession(ctx, id) {
  const runtime = get(ctx, 'droyCompanions');
  return !!id && typeof runtime?.ownsSession === 'function' && !runtime.ownsSession(String(id));
}

export function assertUserSession(ctx, id) {
  if (requireCompanionRuntime(ctx).ownsSession(String(id))) throw failure('COMPANION_SESSION_MANAGED');
}

export async function companionProfiles(ctx, runtime = requireCompanionRuntime(ctx)) {
  if (typeof runtime.store?.profiles !== 'function') throw failure('DROY_OWNERSHIP_NOT_READY');
  const profiles = await runtime.store.profiles();
  if (!Array.isArray(profiles) || profiles.some(row => !row || typeof row !== 'object'
    || row.workspaceId != null && (typeof row.workspaceId !== 'string' || !row.workspaceId)))
    throw failure('DROY_OWNERSHIP_NOT_READY');
  return profiles;
}

const within = (root, path) => {
  const part = relative(root, path);
  return !part || part !== '..' && !part.startsWith('..' + sep) && !isAbsolute(part);
};

/** User creation must not reuse a companion's Session or any of its private directories. */
export async function assertUserCreate(ctx, request, runtime = requireCompanionRuntime(ctx)) {
  if (request.sessionId && runtime.ownsSession(String(request.sessionId))) throw failure('COMPANION_SESSION_MANAGED');
  const profiles = await companionProfiles(ctx, runtime);
  if (request.workspaceId && profiles.some(row => row.workspaceId === request.workspaceId))
    throw failure('COMPANION_WORKSPACE_MANAGED');
  if (!request.cwd) return;
  await assertUserDirectory(ctx, request.cwd, runtime, profiles);
}

export async function assertUserDirectory(ctx, path, runtime = requireCompanionRuntime(ctx), profiles) {
  profiles ??= await companionProfiles(ctx, runtime);
  const target = await realpath(path), roots = new Set();
  for (const profile of profiles) {
    const workspace = profile.workspaceId ? get(ctx, 'workspaceRegistry')?.get(profile.workspaceId) : undefined;
    if (workspace?.path) roots.add(workspace.path);
    if (typeof runtime.directory === 'string' && typeof profile.id === 'string') {
      roots.add(join(runtime.directory, profile.id, 'chat'));
      roots.add(join(runtime.directory, profile.id, 'work'));
    }
  }
  for (const root of roots) {
    let canonical;
    try { canonical = await realpath(root); }
    catch (error) { if (error.code === 'ENOENT') continue; throw error; }
    if (within(canonical, target)) throw failure('COMPANION_WORKSPACE_MANAGED');
  }
}

export function userSessionList(ctx, value) {
  const runtime = requireCompanionRuntime(ctx);
  const managedSessionIds = value.items.filter(row => runtime.ownsSession(row.sessionId)).map(row => row.sessionId);
  const managed = new Set(managedSessionIds);
  return { ...value, items: value.items.filter(row => !managed.has(row.sessionId)), managedSessionIds };
}

/** Projection watermarks are per Session; unrelated companion frames carry no user cursor. */
export async function* userControlFrames(ctx, iterable) {
  for await (const frame of iterable) {
    const runtime = requireCompanionRuntime(ctx);
    if (frame.type === 'baseline') {
      yield { ...frame, value: { ...frame.value, projections: Object.fromEntries(
        Object.entries(frame.value.projections).filter(([id]) => !runtime.ownsSession(id))) } };
    } else if (!runtime.ownsSession(frame.sessionId)) yield frame;
  }
}
