import { createMemoryHandler } from '../memory/adapter.mjs';

const path = '/droy/native/memory';
const reply = (res, status, code) => {
  if (res.destroyed || res.writableEnded) return;
  res.writeHead(status, { 'Content-Type': 'application/json; charset=utf-8', 'Cache-Control': 'no-store' });
  res.end(JSON.stringify({ ok: false, error: { code } }));
};

/** The authenticated manager remains the only memory-management boundary. */
export function createNativeMemory(ctx) {
  return {
    async handle(req, res, signal) {
      const host = ctx.get?.('droyMemoryHost') ?? ctx.droyMemoryHost;
      const service = ctx.get?.('droyMemory') ?? ctx.droyMemory;
      if (typeof host?.authorizeUi !== 'function') {
        req.resume(); reply(res, 503, 'MEMORY_HOST_CONTRACT_MISSING'); return;
      }
      // Keep authorization here too, so this adapter cannot become a bypass
      // if it is mounted separately from the native-api dispatcher.
      if (!await host.authorizeUi(req, res)) { req.resume(); return; }
      let url;
      try {
        if (!req.url?.startsWith('/') || req.url.startsWith('//')) throw new Error();
        url = new URL(req.url, 'http://memory.local');
      } catch { req.resume(); reply(res, 400, 'MEMORY_INVALID_REQUEST'); return; }
      if (url.pathname !== path) { req.resume(); reply(res, 404, 'MEMORY_NOT_FOUND'); return; }
      if (url.search || req.method === 'GET' && (Number(req.headers['content-length']) > 0 || req.headers['transfer-encoding'])) {
        req.resume(); reply(res, 400, 'MEMORY_INVALID_REQUEST'); return;
      }
      if (signal?.aborted) { req.resume(); reply(res, 409, 'MEMORY_REQUEST_CANCELLED'); return; }
      if (typeof service?.status !== 'function' || typeof service?.manage !== 'function') {
        req.resume(); reply(res, 503, 'MEMORY_HOST_CONTRACT_MISSING'); return;
      }
      // The manager owns field validation, revision checks, privacy source
      // invalidation, capture consent, and endpoint-grant semantics.
      await createMemoryHandler({ host: { authorizeUi: async () => true }, service,
        reportError: code => ctx.logger?.warn?.(code) })(req, res);
    },
  };
}
