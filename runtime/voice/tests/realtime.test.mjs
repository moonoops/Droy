import test from 'node:test';
import assert from 'node:assert/strict';
import WebSocket, { WebSocketServer } from '../../dsh/node_modules/ws/wrapper.mjs';
import { createRealtimeManager } from '../realtime.mjs';
import { createResampler, floatsToBytes, float32ToPcm16 } from '../pcm.mjs';

const tick = ms => new Promise(resolve => setTimeout(resolve, ms));
async function until(check, message = 'condition', timeout = 2500) {
  const start = Date.now();
  while (!check()) { if (Date.now() - start > timeout) assert.fail(`Timed out: ${message}`); await tick(5); }
}
async function fixture(t, onConnection) {
  const server = new WebSocketServer({ host: '127.0.0.1', port: 0 });
  await new Promise(resolve => server.once('listening', resolve));
  server.on('connection', onConnection);
  t.after(() => { for (const socket of server.clients) socket.terminate(); return new Promise(resolve => server.close(resolve)); });
  return `ws://127.0.0.1:${server.address().port}`;
}
function media() {
  const calls = []; let cursor = 0;
  const bytes = Buffer.alloc(9600);
  for (let i = 0; i < bytes.length; i += 2) bytes.writeInt16LE(4096, i);
  return { calls, native: async input => {
    calls.push(input);
    if (input.operation === 'start') return { state: 'recording', captureId: 'fixture-capture' };
    if (input.operation === 'poll') return { state: 'recording', audio: bytes.toString('base64'), sampleRate: 48000,
      captureId: 'fixture-capture', nextCursor: ++cursor, level: 0.125, muted: false, speaking: false };
    return {};
  } };
}
const socketFactory = (url, headers) => new WebSocket(url, { headers });

test('streaming resampling gives the same result when native chunk sizes change', () => {
  const input = Float32Array.from({ length: 4800 }, (_, i) => Math.sin(i / 10));
  const one = createResampler(48000, 16000).push(input);
  const resampler = createResampler(48000, 16000), pieces = [];
  for (let i = 0; i < input.length; i += 137) pieces.push(...resampler.push(input.subarray(i, i + 137)));
  assert.deepEqual(pieces, [...one]);
  assert.equal(one.length, 1600);
  assert.deepEqual([...float32ToPcm16(floatsToBytes(Float32Array.from([0, 1, -1])) )], [0, 0, 255, 127, 0, 128]);
});

test('MiniCPM uses queue/init protocol, converts native PCM, accepts independent text and audio, and ignores interrupted old response', async t => {
  const received = []; let socket, requestUrl;
  const url = await fixture(t, (client, request) => {
    socket = client; requestUrl = request.url;
    client.send(JSON.stringify({ type: 'session.queue_done' }));
    client.on('message', data => {
      const value = JSON.parse(data); received.push(value);
      if (value.type === 'session.init') client.send(JSON.stringify({ type: 'session.created', session_id: 'fixture' }));
    });
  });
  const native = media(), manager = createRealtimeManager({ native: native.native, connect: socketFactory, pollInterval: 2 });
  t.after(() => manager.stop());
  await manager.start({ provider: 'minicpm', url }, '', { sessionId: 'fixture-session', videoCapable: true });
  await until(() => received.some(value => value.type === 'input.append'), 'MiniCPM audio');
  assert.equal(requestUrl, '/v1/realtime?mode=video');
  assert.equal(received[0].type, 'session.init');
  const bytes = Buffer.from(received.find(value => value.type === 'input.append').input.audio, 'base64');
  assert.equal(bytes.length, 64000); assert.equal(bytes.readFloatLE(0), 0.125);
  socket.send(JSON.stringify({ type: 'response.output.delta', kind: 'text', text: '你好' }));
  socket.send(JSON.stringify({ type: 'response.output.delta', kind: 'audio', response_id: 'old', audio: floatsToBytes(Float32Array.from([0.5, -0.5])).toString('base64') }));
  await until(() => native.calls.some(value => value.operation === 'play'), 'native playback');
  assert.equal(manager.snapshot().transcript, '你好');
  const first = native.calls.find(value => value.operation === 'play');
  assert.equal(first.sampleRate, 24000); assert.equal(Buffer.from(first.audio, 'base64').readInt16LE(0), 16384);
  await manager.interrupt();
  const count = native.calls.filter(value => value.operation === 'play').length;
  socket.send(JSON.stringify({ type: 'response.output.delta', kind: 'audio', response_id: 'old', audio: first.audio }));
  await tick(20);
  assert.equal(native.calls.filter(value => value.operation === 'play').length, count);
  await until(() => received.some(value => value.input?.force_listen === true), 'force listen');
  await manager.stop(); assert.equal(manager.snapshot().status, 'ended');
});

test('Gemini streams media, exposes only declared structured tasks, and sends true task results by call id', async t => {
  const received = []; let socket, headers;
  const url = await fixture(t, (client, request) => {
    socket = client; headers = request.headers;
    client.on('message', data => {
      const value = JSON.parse(data); received.push(value);
      if (value.setup) client.send(JSON.stringify({ setupComplete: {} }));
    });
  });
  const native = media(), manager = createRealtimeManager({ native: native.native, connect: socketFactory, pollInterval: 3 });
  t.after(() => manager.stop());
  await manager.start({ provider: 'gemini', model: 'gemini-3.8-live', url }, 'fixture-key', { sessionId: 'session-fixture', video: true, videoCapable: true, context: '用户正在做本地测试' });
  await until(() => received.some(value => value.realtimeInput?.audio), 'Gemini input');
  assert.equal(headers['x-goog-api-key'], 'fixture-key');
  const setup = received[0].setup;
  assert.deepEqual(setup.generationConfig.responseModalities, ['AUDIO']);
  assert.equal(setup.tools[0].functionDeclarations[0].name, 'droy_task');
  assert(setup.contextWindowCompression); assert(setup.sessionResumption);
  socket.send(JSON.stringify({ serverContent: { inputTranscription: { text: '打开计算器' }, outputTranscription: { text: '正在处理' },
    modelTurn: { parts: [{ inlineData: { data: Buffer.from([1, 0, 2, 0]).toString('base64'), mimeType: 'audio/pcm;rate=24000' } }] } } }));
  socket.send(JSON.stringify({ toolCall: { functionCalls: [{ id: 'call-fixture', name: 'droy_task', args: { instruction: '打开计算器' } }] } }));
  await until(() => manager.snapshot().pendingToolCalls.length === 1 && native.calls.some(value => value.operation === 'play'));
  assert.equal(manager.snapshot().inputTranscript, '打开计算器');
  assert.equal(manager.claimTool('call-fixture').claimed, true); assert.equal(manager.claimTool('call-fixture').claimed, false);
  manager.toolResponse('call-fixture', { status: 'completed', result: '已在计算器显示 4' });
  await until(() => received.some(value => value.toolResponse));
  assert.equal(received.find(value => value.toolResponse).toolResponse.functionResponses[0].response.status, 'completed');
  assert.equal(manager.snapshot().pendingToolCalls.length, 0);
  assert.throws(() => manager.toolResponse('call-fixture', { status: 'completed' }), error => error.code === 'TOOL_CALL_CLOSED');
  socket.send(JSON.stringify({ toolCall: { functionCalls: [{ id: 'unknown', name: 'native_action', args: { operation: 'start' } }] } }));
  await until(() => received.some(value => value.toolResponse?.functionResponses[0]?.id === 'unknown'));
  assert.equal(manager.snapshot().pendingToolCalls.length, 0);
  assert.equal(manager.video({ data: Buffer.from('fixture-image').toString('base64') }).sent, true);
  assert.equal(manager.video({ data: Buffer.from('fixture-image').toString('base64') }).sent, false);
  manager.setVideo(false); assert.equal(manager.video({ data: Buffer.from('fixture-image').toString('base64') }).sent, false);
  socket.send(JSON.stringify({ serverContent: { interrupted: true } }));
  await until(() => native.calls.some(value => value.operation === 'clear_playback'));
});

test('Gemini resumption uses the provider handle without restarting native capture', async t => {
  const setups = []; let connection = 0;
  const url = await fixture(t, client => {
    const index = ++connection;
    client.on('message', data => {
      const value = JSON.parse(data);
      if (!value.setup) return;
      setups.push(value.setup); client.send(JSON.stringify({ setupComplete: {} }));
      if (index === 1) {
        client.send(JSON.stringify({ sessionResumptionUpdate: { resumable: true, newHandle: 'fixture-resume-handle' } }));
        client.send(JSON.stringify({ goAway: { timeLeft: '10s' } }));
      }
    });
  });
  const native = media(), manager = createRealtimeManager({ native: native.native, connect: socketFactory, pollInterval: 3 });
  t.after(() => manager.stop());
  const start = await manager.start({ provider: 'gemini', model: 'gemini-3.8-live', url }, 'fixture-key', { sessionId: 'fixture-session' });
  await until(() => setups.length === 2 && manager.snapshot().status === 'active');
  assert.equal(setups[1].sessionResumption.handle, 'fixture-resume-handle');
  assert.equal(manager.snapshot().id, start.id);
  assert.equal(native.calls.filter(value => value.operation === 'start').length, 1);
});

test('connection testing does not open the microphone, and unsupported Qwen fails before capture', async t => {
  const url = await fixture(t, client => client.on('message', data => {
    if (JSON.parse(data).setup) client.send(JSON.stringify({ setupComplete: {} }));
  }));
  const native = media(), manager = createRealtimeManager({ native: native.native, connect: socketFactory });
  assert.equal((await manager.testConnection({ provider: 'gemini', model: 'gemini-3.8-live', url }, 'fixture-key')).connected, true);
  assert.equal(native.calls.length, 0);
  await assert.rejects(manager.start({ provider: 'qwen', url }, '', { sessionId: 'fixture' }), error => error.code === 'REALTIME_UNSUPPORTED');
  assert.equal(native.calls.length, 0);
});

test('stop while native start is pending releases the newly created capture', async () => {
  let finish, stopped = 0;
  const manager = createRealtimeManager({ connect() { assert.fail('cancelled start must not connect'); }, native: async input => {
    if (input.operation === 'start') return new Promise(resolve => { finish = resolve; });
    if (input.operation === 'stop') stopped++;
    return {};
  } });
  const starting = manager.start({ provider: 'minicpm', url: 'ws://localhost:1234' }, '', { sessionId: 'fixture' });
  await manager.stop(); finish({ state: 'recording', captureId: 'fixture' });
  assert.equal((await starting).status, 'ended');
  assert.equal(manager.active, false); assert(stopped >= 1);
});
