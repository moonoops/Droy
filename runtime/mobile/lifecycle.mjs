export const name = 'droy-lifecycle';
export const inject = ['agents', 'sessions', 'sessionPersistence', 'llm', 'tools', 'webServer', 'appExit'];

export function apply(ctx) {
  let stopping = false;
  let draining;
  const drain = () => draining ??= (async () => {
    stopping = true;
    const agents = ctx.agents.list();
    for (const agent of agents) agent.cancel({ kind: 'disposed' });
    await Promise.all(agents.map(agent => agent.whenIdle()));
    // The store barrier drains asynchronous event delivery before the backend
    // barrier. Flushing the backend alone can miss the final buffered events.
    await Promise.all(ctx.sessions.list().map(session => ctx.sessions.flush(session)));
    await ctx.sessionPersistence.flush();
  })();
  ctx.provide('droyLifecycle', { get stopping() { return stopping; }, drain });
  ctx.on('llm/stream', (_options, next) => {
    if (stopping) throw new Error('DROY_STOPPING');
    return next();
  });
  ctx.tools.guard(() => stopping ? 'DROY_STOPPING' : undefined);
  ctx.webServer.register({ kind: 'exact', path: '/droy/shutdown', async handler(req, res) {
    if (req.method !== 'POST') { res.writeHead(405); res.end(); return; }
    let success = false;
    try { await drain(); success = true; } catch { /* Return a fixed code; the parent owns the kill deadline. */ }
    res.once('finish', () => ctx.appExit(success ? 0 : 1));
    res.writeHead(success ? 200 : 500, { 'Content-Type': 'application/json; charset=utf-8', 'Cache-Control': 'no-store' });
    res.end(JSON.stringify({ ok: success, code: success ? 'DROY_SHUTDOWN_READY' : 'DROY_SHUTDOWN_FAILED' }));
  } });
}
