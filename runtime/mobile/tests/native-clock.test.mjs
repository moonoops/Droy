import test from 'node:test';
import assert from 'node:assert/strict';
import { createBridge } from '../bridge.mjs';
import { createServer } from 'node:http';

test('the companion clock uses the authenticated native endpoint and reflects a changed device zone', async () => {
  let zone = 'Asia/Shanghai';
  const calls = [];
  const token = 'fictional-native-clock-token-for-local-tests';
  const bridge = createBridge({ port: 12345, token, fetchImpl: async (url, options) => {
    calls.push({ url, options });
    return Response.json({ ok: true, result: { now: 1793142000000, timeZone: zone } });
  } });
  assert.equal((await bridge.clock()).timeZone, 'Asia/Shanghai');
  zone = 'Asia/Kathmandu';
  assert.equal((await bridge.clock()).timeZone, 'Asia/Kathmandu');
  assert.equal(calls.length, 2);
  for (const { url, options } of calls) {
    assert.equal(url, 'http://127.0.0.1:12345/clock');
    assert.equal(options.method, 'POST');
    assert.equal(options.headers.Authorization, `Bearer ${token}`);
    assert.equal(options.redirect, 'error');
    assert.deepEqual(JSON.parse(options.body), {});
  }
});

test('clock failures are surfaced instead of being treated as a UTC device clock', async () => {
  const bridge = createBridge({ port: 12345, token: 'fictional-native-clock-token-for-local-tests',
    fetchImpl: async () => Response.json({ ok: false, error: { code: 'CLOCK_UNAVAILABLE', message: 'Fixture clock unavailable' } }, { status: 503 }) });
  await assert.rejects(bridge.clock(), { code: 'CLOCK_UNAVAILABLE' });
});

test('an unresponsive native clock times out even with a live conversation cancellation signal', async t => {
  const server = createServer(() => {});
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  t.after(async () => { server.closeAllConnections(); await new Promise(resolve => server.close(resolve)); });
  const caller = new AbortController();
  const bridge = createBridge({ port: server.address().port, token: 'fictional-native-clock-token-for-local-tests' });
  await assert.rejects(bridge.clock(caller.signal), { name: 'TimeoutError' });
  assert.equal(caller.signal.aborted, false);
});
