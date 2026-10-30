import { resolveMobileLocale } from './locale.mjs';

const base = '/droy/native/agent-team';
const get = (ctx, name) => typeof ctx.get === 'function' ? ctx.get(name) : ctx[name];

/** Read the same durable projection used by DSH's team panel; never start agents. */
export async function nativeAgentTeamSnapshot(ctx, sessionId, signal) {
  if (typeof sessionId !== 'string' || !sessionId || sessionId.length > 1024 || /[\x00-\x1f\x7f]/.test(sessionId))
    throw Object.assign(new Error('invalid'), { status: 400 });
  const companions = get(ctx, 'droyCompanions');
  const sessions = get(ctx, 'sessionController');
  if (!companions?.ownsSession || !sessions?.projections) throw Object.assign(new Error('unavailable'), { status: 503 });
  if (companions.ownsSession(sessionId)) throw Object.assign(new Error('managed'), { status: 409 });
  const projection = await sessions.projections({ sessionId }, signal);
  if (!projection) throw Object.assign(new Error('missing'), { status: 404 });
  if (projection.kind !== 'sequenced') throw Object.assign(new Error('unavailable'), { status: 409 });
  const team = projection.values.agentTeam;
  if (!team) return { sessionId, available: false, members: [], tasks: [] };
  if (!Array.isArray(team.members) || !Array.isArray(team.tasks)) throw new Error('invalid_projection');
  return { sessionId, available: true, failed: typeof team.failure === 'string',
    members: team.members.map(member => ({ id: member.id, name: member.name, role: member.role, phase: member.phase,
      // Only rostered, non-managed identities are navigable through the existing session resolver.
      canOpen: member.phase === 'active' && !companions.ownsSession(member.id) })),
    tasks: team.tasks.filter(task => task.status !== 'deleted').map(task => ({ id: task.id, subject: task.subject,
      description: task.description, status: task.status, ownerName: task.ownerName ?? null,
      blockedBy: task.blockedBy, writeScopes: task.writeScopes, ready: task.ready, writeScopeWarnings: task.writeScopeWarnings })) };
}

export function createNativeAgentTeam(ctx) {
  return { async handle(req, res, signal) {
    let status = 200, value;
    try {
      const url = new URL(req.url, 'http://localhost');
      if (url.pathname !== base || [...url.searchParams.keys()].some(key => key !== 'sessionId') || url.searchParams.getAll('sessionId').length !== 1)
        throw Object.assign(new Error('invalid'), { status: 400 });
      if (req.method !== 'GET') throw Object.assign(new Error('method'), { status: 405 });
      signal?.throwIfAborted();
      value = { ok: true, result: await nativeAgentTeamSnapshot(ctx, url.searchParams.get('sessionId'), signal) };
    } catch (error) {
      status = error.status ?? 500;
      value = { ok: false, error: { code: `NATIVE_TEAM_${status}`, message: resolveMobileLocale(ctx) === 'zh' ? '暂时无法读取团队。' : 'The team could not be loaded.' } };
    }
    if (res.destroyed || res.writableEnded) return;
    res.writeHead(status, { 'Content-Type': 'application/json; charset=utf-8', 'Cache-Control': 'no-store' });
    res.end(JSON.stringify(value));
  } };
}
