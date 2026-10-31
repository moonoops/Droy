import { createHostMaintenanceExecution } from './companions/maintenance-host-execution.mjs';

export const name = 'droy-lifecycle';
export const inject = ['agents', 'sessions', 'sessionPersistence', 'llm', 'tools', 'webServer', 'appExit'];

export function apply(ctx) {
  let stopping = false;
  let draining;
  const execution = createHostMaintenanceExecution(ctx);
  ctx.effect(() => () => execution.dispose());
  const drain = (cancel = true) => draining ??= (async () => {
    stopping = true;
    const agents = ctx.agents.list();
    if (cancel) for (const agent of agents) agent.cancel({ kind: 'disposed' });
    await Promise.all(agents.map(agent => agent.whenIdle()));
    // The store barrier drains asynchronous event delivery before the backend
    // barrier. Flushing the backend alone can miss the final buffered events.
    await Promise.all(ctx.sessions.list().map(session => ctx.sessions.flush(session)));
    await ctx.sessionPersistence.flush();
  })();
  const prepareMaintenanceExit = async assertQuiet => {
    if (stopping || typeof assertQuiet !== 'function' || !assertQuiet()) throw Object.assign(new Error('COMPANION_MAINTENANCE_BUSY'), { code: 'COMPANION_MAINTENANCE_BUSY' });
    await Promise.all(ctx.sessions.list().map(session => ctx.sessions.flush(session)));
    await ctx.sessionPersistence.flush();
    if (!assertQuiet()) throw Object.assign(new Error('COMPANION_MAINTENANCE_BUSY'), { code: 'COMPANION_MAINTENANCE_BUSY' });
    stopping = true;
  };
  ctx.provide('droyLifecycle', { get stopping() { return stopping; }, drain, prepareMaintenanceExit });
  ctx.on('llm/stream', (_options, next) => {
    if (stopping) throw new Error('DROY_STOPPING');
    return next();
  });
  ctx.tools.guard(() => stopping ? 'DROY_STOPPING' : undefined);
  const idle = () => {
    const companions=ctx.get('droyCompanions');
    if(typeof companions?.activity!=='function'||companions.activity().busy!==false)return false;
    if(ctx.agents.list().some(agent=>agent.status!=='idle'))return false;
    const snapshot=execution.inspect();
    return !snapshot.writers.length&&!snapshot.busy.length&&!snapshot.unfenced.length;
  };
  ctx.webServer.register({ kind: 'exact', path: '/droy/shutdown', async handler(req, res) {
    if (req.method !== 'POST') { res.writeHead(405); res.end(); return; }
    let ifIdle=false;
    try {
      let body='';
      if(Number(req.headers['content-length'])>1024) {res.writeHead(413);res.end();req.resume();return;}
      for await(const part of req) {
        body+=part.toString('utf8');
        if(Buffer.byteLength(body)>1024){res.writeHead(413);res.end();req.resume();return;}
      }
      const value=body?JSON.parse(body):{};
      if(!value||Array.isArray(value)||typeof value!=='object'||Object.keys(value).some(key=>key!=='ifIdle')
        ||Object.hasOwn(value,'ifIdle')&&value.ifIdle!==true)throw new Error('DROY_SHUTDOWN_INVALID_REQUEST');
      ifIdle=value.ifIdle===true;
    } catch {
      res.writeHead(400,{'Content-Type':'application/json; charset=utf-8','Cache-Control':'no-store'});
      res.end(JSON.stringify({ok:false,code:'DROY_SHUTDOWN_INVALID_REQUEST'}));return;
    }
    if(ifIdle&&!stopping) {
      try {
        // Claim the exit in the same synchronous turn as the final observation.
        // New HTTP bodies and timer callbacks then meet DROY_STOPPING admission.
        if(!idle()) {
          res.writeHead(409,{'Content-Type':'application/json; charset=utf-8','Cache-Control':'no-store'});
          res.end(JSON.stringify({ok:false,code:'DROY_SHUTDOWN_BUSY'}));return;
        }
        stopping=true;
      } catch {
        res.writeHead(500,{'Content-Type':'application/json; charset=utf-8','Cache-Control':'no-store'});
        res.end(JSON.stringify({ok:false,code:'DROY_SHUTDOWN_FAILED'}));return;
      }
    }
    let success = false;
    try { await drain(!ifIdle); success = true; } catch { /* Return a fixed code; the parent owns the kill deadline. */ }
    res.once('finish', () => ctx.appExit(success ? 0 : 1));
    res.writeHead(success ? 200 : 500, { 'Content-Type': 'application/json; charset=utf-8', 'Cache-Control': 'no-store' });
    res.end(JSON.stringify({ ok: success, code: success ? 'DROY_SHUTDOWN_READY' : 'DROY_SHUTDOWN_FAILED' }));
  } });
}
