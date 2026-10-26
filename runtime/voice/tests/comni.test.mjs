import test from 'node:test';
import assert from 'node:assert/strict';
import WebSocket, { WebSocketServer } from '../../dsh/node_modules/ws/wrapper.mjs';
import { comniEndpoint } from '../comni.mjs';
import { DEFAULT_CONFIG, mergeConfig } from '../config.mjs';
import { createRealtimeManager } from '../realtime.mjs';
import { floatsToBytes } from '../pcm.mjs';

const tick = ms => new Promise(resolve => setTimeout(resolve, ms));
async function until(check, timeout = 2500) {
  const start = Date.now();
  while (!check()) { if (Date.now() - start > timeout) assert.fail('Comni fixture timed out'); await tick(5); }
}
async function fixture(t, connected) {
  const server = new WebSocketServer({ host: '127.0.0.1', port: 0 });
  await new Promise(resolve => server.once('listening', resolve));
  server.on('connection', connected);
  t.after(() => { for (const socket of server.clients) socket.terminate(); return new Promise(resolve => server.close(resolve)); });
  return `ws://127.0.0.1:${server.address().port}`;
}
function media() {
  const calls = []; let cursor = 0;
  const data = Buffer.alloc(9600);
  for (let i = 0; i < data.length; i += 2) data.writeInt16LE(4096, i);
  return { calls, native: async input => {
    calls.push(input);
    if (input.operation === 'start') return { state: 'recording', captureId: 'fixture' };
    if (input.operation === 'poll') return { state: 'recording', audio: data.toString('base64'),
      sampleRate: 48000, nextCursor: ++cursor, level: 0.1, muted: false, speaking: false };
    return {};
  } };
}
const connect = (url, headers) => new WebSocket(url, { headers });
const referenceAudio = { data: floatsToBytes(new Float32Array(8000).fill(0.125)).toString('base64') };

test('Comni has an explicit provider and distinct direct-worker and gateway URLs', () => {
  assert.equal(mergeConfig(DEFAULT_CONFIG, { realtime: { provider: 'comni' } }).realtime.provider, 'comni');
  const worker = comniEndpoint('http://model.example.test/ws/duplex', true, 'fixture-id');
  assert.equal(worker.url.protocol, 'ws:'); assert.equal(worker.gateway, false);
  assert.equal(worker.url.searchParams.get('session_id'), 'omni_droy_fixture-id');
  const gateway = comniEndpoint('https://model.example.test/mobile/', false, 'fixture-id');
  assert.equal(gateway.gateway, true); assert.equal(gateway.url.pathname, '/ws/duplex/adx_droy_fixture-id');
  assert.throws(() => comniEndpoint('https://model.example.test/v1/realtime', true, 'fixture'), /Comni/u);
});

test('direct Comni prepares on open, streams audio and optional frames, accepts C++ audio_only, and acknowledges stop', async t => {
  const messages = []; let socket, path;
  const url = await fixture(t, (client, request) => {
    socket = client; path = request.url;
    client.on('message', data => {
      const value = JSON.parse(data); messages.push(value);
      if (value.type === 'prepare') client.send(JSON.stringify({ type: 'prepared', prompt_length: 100 }));
      if (value.type === 'stop') client.send(JSON.stringify({ type: 'stopped' }));
    });
  });
  const native = media(), manager = createRealtimeManager({ native: native.native, connect, pollInterval: 2 });
  t.after(() => manager.stop());
  await manager.start({ provider: 'comni', url: `${url}/ws/duplex`, model: '', voice: '', referenceAudio }, '', { sessionId: 'dsh-fixture', video: false, videoCapable: true });
  await until(() => messages.some(item => item.type === 'audio_chunk'));
  assert.match(path, /^\/ws\/duplex\?session_id=omni_droy_/u);
  assert.equal(messages[0].type, 'prepare'); assert.equal(messages[0].max_slice_nums, 1);
  assert.equal(messages[0].ref_audio_base64, referenceAudio.data);
  assert.equal(messages[0].tts_ref_audio_base64, referenceAudio.data);
  assert.equal(messages[0].config.length_penalty, 1.1);
  assert.match(messages[0].system_prompt, /一边听一边说/u);
  const input = messages.find(item => item.type === 'audio_chunk');
  assert.equal(Buffer.from(input.audio_base64, 'base64').length, 64000);
  assert.equal(Buffer.from(input.audio_base64, 'base64').readFloatLE(0), 0.125);
  assert.equal(input.frame_base64_list, undefined);
  const sent = manager.snapshot();
  assert(sent.sentAudioFrames > 0); assert.equal(sent.sentAudioBytes, sent.sentAudioFrames * 64000);
  assert.equal(sent.sentVideoFrames, 0);
  socket.send(JSON.stringify({ type: 'result', is_listen: false, text: '真实协议的模型字幕', audio_data: null, current_time: 1 }));
  socket.send(JSON.stringify({ type: 'audio_only', audio_data: floatsToBytes(Float32Array.from([0.5, -0.5])).toString('base64') }));
  await until(() => native.calls.some(item => item.operation === 'play'));
  assert.equal(manager.snapshot().transcript, '真实协议的模型字幕');
  const firstAudio = native.calls.find(item => item.operation === 'play');
  assert.equal(firstAudio.sampleRate, 24000); assert.equal(Buffer.from(firstAudio.audio, 'base64').readInt16LE(0), 16384);
  manager.setVideo(true);
  manager.video({ data: Buffer.from('fixture-jpeg').toString('base64') });
  await until(() => messages.some(item => item.frame_base64_list?.length));
  assert.equal(manager.snapshot().sentVideoFrames, 1);
  manager.setVideo(false);
  await manager.interrupt();
  const before = native.calls.filter(item => item.operation === 'play').length;
  socket.send(JSON.stringify({ type: 'audio_only', audio_data: floatsToBytes(Float32Array.from([0.1])).toString('base64') }));
  await until(() => messages.some(item => item.type === 'audio_chunk' && item.force_listen));
  socket.send(JSON.stringify({ type: 'result', is_listen: true, text: '', current_time: 3 }));
  socket.send(JSON.stringify({ type: 'audio_only', audio_data: floatsToBytes(Float32Array.from([0.2])).toString('base64') }));
  await tick(15); assert.equal(native.calls.filter(item => item.operation === 'play').length, before);
  socket.send(JSON.stringify({ type: 'result', is_listen: false, text: '新回复', current_time: 4 }));
  socket.send(JSON.stringify({ type: 'audio_only', audio_data: floatsToBytes(Float32Array.from([0.3])).toString('base64') }));
  await until(() => native.calls.filter(item => item.operation === 'play').length > before);
  const stopped = await manager.stop();
  assert.equal(stopped.status, 'ended'); assert.equal(stopped.remoteStopAcknowledged, true);
  assert(messages.some(item => item.type === 'stop'));
});

test('Comni gateway waits for queue_done before prepare; connection tests do not start media', async t => {
  let assigned = false, prepares = 0, socketPath;
  const url = await fixture(t, (client, request) => {
    socketPath = request.url;
    client.send(JSON.stringify({ type: 'queued', position: 1 }));
    setTimeout(() => { assigned = true; client.send(JSON.stringify({ type: 'queue_done' })); }, 10);
    client.on('message', data => {
      const value = JSON.parse(data);
      if (value.type === 'prepare') {
        assert.equal(assigned, true); prepares++;
        client.send(JSON.stringify({ type: 'prepared' }));
      }
      if (value.type === 'stop') client.send(JSON.stringify({ type: 'stopped' }));
    });
  });
  const native = media(), manager = createRealtimeManager({ native: native.native, connect });
  const result = await manager.testConnection({ provider: 'comni', url: `${url}/mobile/`, model: '', voice: '', referenceAudio }, '');
  assert.equal(result.connected, true); assert.equal(prepares, 1);
  assert.match(socketPath, /^\/ws\/duplex\/adx_droy_/u);
  assert.equal(native.calls.length, 0);
});

test('Comni requires a configured reference before microphone capture or a connection probe', async () => {
  const manager = createRealtimeManager({ native: async () => assert.fail('missing reference must not open microphone'),
    connect: () => assert.fail('missing reference must not connect') });
  const config = { provider: 'comni', url: 'ws://model.example.test/ws/duplex' };
  await assert.rejects(manager.start(config, '', { sessionId: 'fixture' }), error => error.code === 'REFERENCE_AUDIO_REQUIRED');
  await assert.rejects(manager.testConnection(config, ''), error => error.code === 'REFERENCE_AUDIO_REQUIRED');
});
