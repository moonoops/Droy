import { createServer, request as httpRequest } from 'node:http';
import { connect, BlockList, isIP } from 'node:net';
import { lookup } from 'node:dns/promises';

// Node matches IPv4 addresses against their IPv4-mapped IPv6 equivalents.
// Keep the lists separate: denying ::ffff:0:0/96 in a shared list denies all IPv4.
const deniedV4 = new BlockList(), deniedV6 = new BlockList();
for (const [address, bits] of [['0.0.0.0',8],['10.0.0.0',8],['100.64.0.0',10],['127.0.0.0',8],
  ['169.254.0.0',16],['172.16.0.0',12],['192.0.0.0',24],['192.0.2.0',24],['192.168.0.0',16],
  ['198.18.0.0',15],['198.51.100.0',24],['203.0.113.0',24],['224.0.0.0',4],['240.0.0.0',4]]) deniedV4.addSubnet(address, bits, 'ipv4');
for (const [address, bits] of [['::',128],['::1',128],['::ffff:0:0',96],['64:ff9b::',96],['64:ff9b:1::',48],
  ['100::',64],['2001:db8::',32],['2001::',32],['2002::',16],['fc00::',7],['fe80::',10],['ff00::',8]]) deniedV6.addSubnet(address,bits,'ipv6');
export function publicAddress(address) {
  const family = isIP(address);
  return family === 4 ? !deniedV4.check(address, 'ipv4') : family === 6 && !deniedV6.check(address, 'ipv6');
}
function target(value, connectRequest = false) {
  let url;
  try { url = new URL(connectRequest ? `https://${value}` : value); } catch { throw new Error('EXECUTION_PROXY_TARGET'); }
  if (url.username || url.password || url.hash || !url.hostname || (connectRequest && (url.pathname !== '/' || url.search))
      || (!connectRequest && url.protocol !== 'http:')) throw new Error('EXECUTION_PROXY_TARGET');
  const port = Number(url.port || (connectRequest ? 443 : 80));
  if (!Number.isInteger(port) || port < 1 || port > 65535) throw new Error('EXECUTION_PROXY_TARGET');
  return { url, hostname: url.hostname.replace(/^\[|\]$/g, ''), port };
}
const hopHeaders = new Set(['proxy-authorization','proxy-connection','connection','keep-alive','te','trailer','transfer-encoding','upgrade']);
function headers(input) {
  const omitted = new Set([...hopHeaders, ...(input.connection ?? '').split(',').map(value => value.trim().toLowerCase())]);
  return Object.fromEntries(Object.entries(input).filter(([key]) => !omitted.has(key.toLowerCase())));
}

/** Per-job public HTTP proxy. Protected jobs receive no network capability. */
export async function createExecutionProxy({ workspaceId = '', resolve = lookup, allowAddress = publicAddress,
  maxConnections = 16, maxBodyBytes = 64 * 1024 * 1024, timeoutMs = 300000 } = {}) {
  if (workspaceId) return { port: 0, env: {}, async close() {} };
  const sockets = new Set(); let closing = false, active = 0;
  function track(socket) { sockets.add(socket); socket.once('close', () => sockets.delete(socket)); return socket; }
  async function destination(row) {
    const addresses = isIP(row.hostname) ? [{ address: row.hostname, family: isIP(row.hostname) }]
      : await resolve(row.hostname, { all: true, verbatim: true });
    if (closing || !addresses.length || addresses.some(entry => !allowAddress(entry.address))) throw new Error('EXECUTION_PROXY_DESTINATION_DENIED');
    // Connect to this numeric address, so a second DNS answer cannot change the destination.
    return addresses[0];
  }
  function acquire() { if (closing || active >= maxConnections) throw new Error('EXECUTION_PROXY_BUSY'); active++; let released = false; return () => { if (!released) { released = true; active--; } }; }
  const server = createServer(async (req, res) => {
    let upstream, release;
    try {
      release = acquire(); res.once('close', release);
      const row = target(req.url), address = await destination(row);
      if (closing || req.destroyed) throw new Error('EXECUTION_PROXY_CLOSED');
      upstream = httpRequest({ hostname: address.address, family: address.family, port: row.port,
        method: req.method, path: row.url.pathname + row.url.search, headers: { ...headers(req.headers), host: row.url.host }, agent: false });
      upstream.on('socket', track); upstream.setTimeout(timeoutMs, () => upstream.destroy());
      let sent = 0;
      req.on('data', bytes => { sent += bytes.length; if (sent > maxBodyBytes) { upstream.destroy(); req.destroy(); } });
      req.once('aborted', () => upstream.destroy());
      res.once('close', () => upstream.destroy());
      upstream.on('response', incoming => {
        res.writeHead(incoming.statusCode, headers(incoming.headers));
        incoming.on('error', () => res.destroy()); incoming.pipe(res);
      });
      upstream.on('error', () => { if (!res.headersSent) res.writeHead(502); res.end(); });
      req.pipe(upstream);
    } catch {
      upstream?.destroy(); release?.(); if (!res.headersSent) res.writeHead(403); res.end();
    }
  });
  server.on('connect', async (req, socket, head) => {
    let upstream, release;
    try {
      release = acquire(); socket.once('close', release);
      const row = target(req.url, true), address = await destination(row);
      if (closing || socket.destroyed) throw new Error('EXECUTION_PROXY_CLOSED');
      upstream = track(connect({ host: address.address, family: address.family, port: row.port }));
      upstream.setTimeout(timeoutMs, () => upstream.destroy()); socket.setTimeout(timeoutMs, () => socket.destroy());
      socket.once('error', () => upstream.destroy()); socket.once('close', () => upstream.destroy());
      upstream.once('error', () => socket.destroy()); upstream.once('close', () => socket.destroy());
      upstream.once('connect', () => {
        if (closing || socket.destroyed) { upstream.destroy(); return; }
        socket.write('HTTP/1.1 200 Connection Established\r\n\r\n');
        if (head.length) upstream.write(head);
        socket.pipe(upstream); upstream.pipe(socket);
      });
    } catch {
      release?.(); upstream?.destroy(); if (!socket.destroyed) socket.end('HTTP/1.1 403 Forbidden\r\nConnection: close\r\nContent-Length: 0\r\n\r\n');
    }
  });
  server.on('connection', socket => {
    track(socket); socket.setTimeout(timeoutMs, () => socket.destroy()); socket.on('error', () => {});
    if (closing || sockets.size > maxConnections * 3) socket.destroy();
  });
  server.on('upgrade', (_request, socket) => socket.destroy());
  server.on('clientError', (_error, socket) => socket.destroy());
  server.requestTimeout = timeoutMs; server.headersTimeout = Math.min(timeoutMs, 15000); server.maxHeadersCount = 64;
  await new Promise((done, reject) => { server.once('error', reject); server.listen(0, '127.0.0.1', () => { server.off('error', reject); done(); }); });
  const port = server.address().port;
  return {
    port,
    // RootExecutor rewrites these host addresses to its fixed guest relay port.
    env: { HTTP_PROXY: `http://127.0.0.1:${port}`, HTTPS_PROXY: `http://127.0.0.1:${port}`,
      http_proxy: `http://127.0.0.1:${port}`, https_proxy: `http://127.0.0.1:${port}`, NO_PROXY: '', no_proxy: '', NODE_USE_ENV_PROXY: '1' },
    async close() {
      if (closing) return;
      closing = true; for (const socket of sockets) socket.destroy();
      await new Promise((done, reject) => server.close(error => error ? reject(error) : done()));
    },
  };
}
