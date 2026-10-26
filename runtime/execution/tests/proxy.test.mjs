import test from 'node:test';
import assert from 'node:assert/strict';
import { createServer as httpServer, request } from 'node:http';
import { createServer as netServer, connect } from 'node:net';
import { once } from 'node:events';
import { createExecutionProxy, publicAddress } from '../proxy.mjs';

async function listen(server, t) {
  const sockets = new Set(); server.on('connection', socket => { sockets.add(socket); socket.once('close', () => sockets.delete(socket)); });
  server.listen(0, '127.0.0.1'); await once(server, 'listening');
  t.after(async () => { for (const socket of sockets) socket.destroy(); await new Promise(resolve => server.close(resolve)); });
  return server.address().port;
}
async function proxy(t, options = {}) {
  const value = await createExecutionProxy(options); t.after(() => value.close()); return value;
}
function http(port, path, options = {}) {
  return new Promise((resolve, reject) => {
    const client = request({ host: '127.0.0.1', port, path, method: options.method ?? 'GET', headers: options.headers, agent: false }, response => {
      const chunks = []; response.on('data', bytes => chunks.push(bytes)); response.once('error', reject);
      response.once('end', () => resolve({ status: response.statusCode, headers: response.headers, body: Buffer.concat(chunks).toString() }));
    });
    client.once('error', reject); client.end(options.body);
  });
}
async function tunnel(port, authority, head = '') {
  const socket = connect(port, '127.0.0.1'); socket.on('error', () => {}); await once(socket, 'connect');
  let bytes = Buffer.alloc(0);
  const received = new Promise((resolve, reject) => {
    const receive = chunk => {
      bytes = Buffer.concat([bytes, chunk]); const end = bytes.indexOf('\r\n\r\n');
      if (end !== -1) { socket.off('data', receive); resolve({ socket, headers: bytes.subarray(0, end).toString(), remaining: bytes.subarray(end + 4) }); }
    };
    socket.on('data', receive); socket.once('error', reject);
  });
  socket.write(`CONNECT ${authority} HTTP/1.1\r\nHost: ${authority}\r\n\r\n${head}`);
  return received;
}

test('public routing rejects local, reserved, mapped, NAT64 and private IPv4/IPv6 destinations', () => {
  for (const address of ['127.0.0.1', '0.0.0.0', '10.1.2.3', '100.64.0.1', '169.254.169.254', '172.16.0.1', '192.168.1.1',
    '192.0.2.3', '198.18.0.1', '203.0.113.9', '224.0.0.1', '255.255.255.255', '::', '::1', '::1%lo0', 'fe80::1%lo0',
    'fc00::1', '::ffff:127.0.0.1', '0:0:0:0:0:ffff:7f00:1', '64:ff9b::7f00:1', '2001:db8::1', '2002:7f00:1::', 'not-an-ip']) assert.equal(publicAddress(address), false, address);
  assert.equal(publicAddress('93.184.215.14'), true); assert.equal(publicAddress('2606:4700:4700::1111'), true);
});

test('protected workspaces have no listener, credentials, or DNS work', async () => {
  const value = await createExecutionProxy({ workspaceId: 'a'.repeat(64), resolve() { throw new Error('must not resolve'); } });
  assert.deepEqual(value.env, {}); assert.equal(value.port, 0); await value.close();
});

test('HTTP pins one DNS result, forwards body and Host, and removes proxy and hop headers', async t => {
  let observed, resolutions = 0;
  const upstream = httpServer(async (req, res) => {
    const chunks = []; for await (const bytes of req) chunks.push(bytes);
    observed = { headers: req.headers, url: req.url, body: Buffer.concat(chunks).toString() };
    res.writeHead(201, { 'Content-Type': 'text/plain', 'Connection': 'x-hidden-response', 'X-Hidden-Response': 'drop', 'X-Visible': 'keep' }); res.end('synthetic response');
  });
  const port = await listen(upstream, t), value = await proxy(t, {
    resolve: async hostname => { resolutions++; assert.equal(hostname, 'synthetic.invalid'); return [{ address: '127.0.0.1', family: 4 }]; },
    allowAddress: address => address === '127.0.0.1',
  });
  const response = await http(value.port, `http://synthetic.invalid:${port}/sample?q=1`, {
    method: 'POST', body: 'fictional body', headers: { 'Host': 'untrusted.invalid', 'Proxy-Authorization': 'synthetic-secret', 'Connection': 'x-private', 'X-Private': 'drop', 'X-Visible': 'keep' },
  });
  assert.equal(response.status, 201); assert.equal(response.body, 'synthetic response'); assert.equal(resolutions, 1);
  assert.equal(observed.headers.host, `synthetic.invalid:${port}`); assert.equal(observed.body, 'fictional body'); assert.equal(observed.url, '/sample?q=1');
  assert.equal(observed.headers['proxy-authorization'], undefined); assert.equal(observed.headers['x-private'], undefined); assert.equal(observed.headers['x-visible'], 'keep');
  assert.equal(response.headers['x-hidden-response'], undefined); assert.equal(response.headers['x-visible'], 'keep');
});

test('private literals, mixed DNS answers, URL credentials and invalid schemes never reach a server', async t => {
  let contacts = 0;
  const port = await listen(httpServer((_req, res) => { contacts++; res.end('forbidden'); }), t);
  const value = await proxy(t, { resolve: async () => [{ address: '93.184.215.14', family: 4 }, { address: '127.0.0.1', family: 4 }] });
  for (const path of [`http://127.0.0.1:${port}/`, `http://2130706433:${port}/`, `http://0x7f000001:${port}/`,
    `http://mixed.invalid:${port}/`, `http://synthetic:credential@public.invalid:${port}/`, 'https://public.invalid/']) {
    assert.equal((await http(value.port, path)).status, 403, path);
  }
  const refused = await tunnel(value.port, `127.0.0.1:${port}`); assert.match(refused.headers, /^HTTP\/1.1 403/); refused.socket.destroy();
  assert.equal(contacts, 0);
});

test('CONNECT preserves immediate head bytes and close destroys both ends of active tunnels', async t => {
  const target = netServer(socket => socket.pipe(socket)), port = await listen(target, t);
  const value = await proxy(t, { resolve: async () => [{ address: '127.0.0.1', family: 4 }], allowAddress: address => address === '127.0.0.1' });
  const opened = await tunnel(value.port, `synthetic.invalid:${port}`, 'sent-with-headers');
  assert.match(opened.headers, /^HTTP\/1.1 200/);
  const echo = opened.remaining.length ? opened.remaining : (await once(opened.socket, 'data'))[0];
  assert.equal(echo.toString(), 'sent-with-headers');
  const closed = once(opened.socket, 'close'); await value.close(); await closed;
});

test('closing while DNS is pending does not connect after the lookup completes', async t => {
  let resolveDns, contacts = 0;
  const lookupStarted = new Promise(resolve => { resolveDns = resolve; });
  let answer;
  const port = await listen(httpServer((_req, res) => { contacts++; res.end('late'); }), t);
  const value = await proxy(t, { allowAddress: () => true, resolve: () => { resolveDns(); return new Promise(resolve => { answer = resolve; }); } });
  const pending = http(value.port, `http://synthetic.invalid:${port}/`).catch(error => error);
  await lookupStarted; await value.close(); answer([{ address: '127.0.0.1', family: 4 }]); await pending;
  await new Promise(resolve => setImmediate(resolve)); assert.equal(contacts, 0);
});
