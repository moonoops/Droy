import test from 'node:test';
import assert from 'node:assert/strict';
import { createRequire, registerHooks } from 'node:module';
import { pathToFileURL } from 'node:url';
import { createServer } from 'node:http';
import { mkdtemp, readFile, readdir, rm, symlink, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { createAudioService, installAudioServices, AUDIO_PROVIDER_ID, speechPreparation } from '../audio.mjs';
import { createBridge, BridgeError } from '../bridge.mjs';

const installed = createRequire(new URL('../../dsh/package.json', import.meta.url));
const attachmentsUrl = new URL('../attachments.mjs', import.meta.url).href;
const hooks = registerHooks({ resolve(specifier, context, next) {
  if (specifier.startsWith('@deepseek-ai/')) return { url: pathToFileURL(installed.resolve(specifier)).href, shortCircuit: true };
  if (context.parentURL === attachmentsUrl && specifier === './publish.mjs')
    return { url: new URL('./fixtures/host-publish.mjs', import.meta.url).href, shortCircuit: true };
  return next(specifier, context);
} });
const { Context } = await import('@deepseek-ai/cordis');
const { default: MobileAttachments } = await import('../attachments.mjs');
const { default: SpeechToText } = await import('@deepseek-ai/dsh-experimental-speech-to-text');
hooks.deregister();

const TOKEN = 'fictional-local-audio-test-token-123456789';
const exec = signal => ({ agent: { id: 'audio-test-agent' }, callId: 'audio-test-call', signal: signal ?? new AbortController().signal });
const wav = Buffer.from('RIFF-fictional-test-audio-WAVE');
const result = value => new Response(JSON.stringify({ ok: true, result: value }), { headers: { 'Content-Type': 'application/json' } });

async function fixture(t, bridge) {
  const home = await mkdtemp(join(tmpdir(), 'droy-audio-'));
  const ctx = new Context();
  await ctx.plugin(MobileAttachments, { dshHome: home });
  const service = createAudioService({ bridge, attachments: ctx.attachments, home, pollMs: 1, getLocale: () => 'zh' });
  t.after(async () => { await service.dispose(); await ctx.fiber.dispose(); await rm(home, { recursive: true, force: true }); });
  return { home, ctx, service, attachments: ctx.attachments };
}

test('completed native recording is streamed into the shared attachment store and keeps trusted ownership', async t => {
  const requests = [];
  const server = createServer(async (req, res) => {
    assert.equal(req.headers.authorization, `Bearer ${TOKEN}`);
    let body = ''; for await (const part of req) body += part;
    const call = { path: req.url, ...JSON.parse(body) }; requests.push(call);
    assert.equal(call.taskId, 'audio-test-agent');
    if (req.url === '/audio/file') {
      res.writeHead(200, { 'Content-Type': 'audio/wav' }); res.end(wav); return;
    }
    const value = req.url === '/tool'
      ? { captureId: 'capture-test', state: 'completed', fileReady: true, filePath: '/native/private/audio.wav' }
      : { transcriptId: 'transcript-test', state: 'running' };
    res.writeHead(200, { 'Content-Type': 'application/json' }); res.end(JSON.stringify({ ok: true, result: value }));
  });
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  t.after(() => new Promise(resolve => server.close(resolve)));
  const bridge = createBridge({ port: server.address().port, token: TOKEN });
  const { service, attachments } = await fixture(t, bridge);
  const stopped = await service.execute('audio_stop', { captureId: 'capture-test', taskId: 'forged' }, exec());
  assert.equal(stopped.audioRef.bytes, wav.length);
  assert.deepEqual(await readFile(attachments.fileHostPath(stopped.audioRef)), wav);
  assert.notEqual(stopped.filePath, '/native/private/audio.wav');
  const started = await service.execute('audio_transcribe', { audioRef: stopped.audioRef, taskId: 'forged' }, exec());
  assert.equal(started.transcriptId, 'transcript-test');
  assert.equal(requests.at(-1).inputPath, stopped.filePath);
  assert.equal(requests.at(-1).taskId, 'audio-test-agent');
  assert.ok(!requests.some(call => call.audio || call.base64));
  await service.execute('audio_status', { captureId: 'capture-test' }, exec());
  assert.equal(requests.filter(call => call.path === '/audio/file').length, 1, 'immutable recording is published once');
});

test('pure stream capture needs no file and does not invoke an attachment transfer', async t => {
  const calls = [];
  const { service } = await fixture(t, {
    async call(action) { calls.push(action); return { captureId: 'stream-only', state: 'completed', fileReady: false }; },
    async audioFile() { throw new Error('stream-only capture has no file'); },
  });
  const value = await service.execute('audio_stop', { captureId: 'stream-only' }, exec());
  assert.equal(value.state, 'completed'); assert.equal(value.audioRef, undefined);
  assert.deepEqual(calls, ['audio_stop']);
});

test('a paused file transfer preserves the successful stop receipt and can be retried after resume', async t => {
  let paused = true;
  const { service } = await fixture(t, {
    async call() { return { captureId: 'paused-file', state: 'completed', fileReady: true }; },
    async audioFile() {
      if (paused) throw new BridgeError('PERMISSION_DENIED', '音频处理已暂停', { reason: 'operations_paused' });
      return new Response(wav, { headers: { 'Content-Type': 'audio/wav' } });
    },
  });
  const stopped = await service.execute('audio_stop', { captureId: 'paused-file' }, exec());
  assert.equal(stopped.state, 'completed');
  assert.equal(stopped.fileTransferState, 'paused');
  assert.equal(stopped.audioRef, undefined);
  paused = false;
  const resumed = await service.execute('audio_status', { captureId: 'paused-file' }, exec());
  assert.equal(resumed.audioRef.bytes, wav.length);
});

test('file transcription accepts the actual uploaded attachment path and rejects forged, linked and corrupt objects', async t => {
  const calls = [];
  const { service, attachments, home } = await fixture(t, { async audio(value) { calls.push(value); return { transcriptId: 'file-job' }; } });
  const ref = await attachments.saveFile({ name: 'meeting.wav', data: wav });
  const path = attachments.fileHostPath(ref);
  await service.execute('audio_transcribe', { filePath: path }, exec());
  assert.equal(calls[0].inputPath, path);
  await assert.rejects(service.execute('audio_transcribe', { filePath: join(home, 'private.wav') }, exec()), /INVALID_AUDIO_FILE/);
  const target = join(home, 'other.wav'); await writeFile(target, wav);
  await rm(path); await symlink(target, path);
  await assert.rejects(service.execute('audio_transcribe', { audioRef: ref }, exec()), /符号链接/);
  await rm(path); await writeFile(path, Buffer.from('corrupt'));
  await assert.rejects(service.execute('audio_transcribe', { audioRef: ref }, exec()), /integrity/);
  assert.equal(calls.length, 1, 'invalid input must never reach native recognition');
});

test('native transcription jobs retain engine identity, explicit preparation and incremental cursors', async t => {
  const calls = [];
  const { service } = await fixture(t, { async audio(value) {
    calls.push(value);
    if (value.operation === 'prepare' || value.operation === 'preparation') return { state: 'ready' };
    if (value.operation === 'transcript') return { state: 'running', segments: [{ id: 'seg-2', text: '会议继续', final: true }], nextCursor: 2 };
    return { transcriptId: 'live-job', state: value.operation === 'stop_transcription' ? 'cancelled' : 'running' };
  } });
  await service.execute('audio_transcribe', { operation: 'prepare' }, exec());
  assert.equal(service.snapshot().preparation.phase, 'ready');
  await service.execute('audio_transcribe', { captureId: 'live-capture', language: 'zh', taskId: 'forged', callId: 'forged' }, exec());
  const read = await service.execute('audio_transcript_read', { transcriptId: 'live-job', cursor: 1 }, exec());
  assert.equal(read.nextCursor, 2);
  assert.equal(calls.at(-1).cursor, 1);
  const stopped = await service.execute('audio_transcription_stop', { transcriptId: 'live-job' }, exec());
  assert.equal(stopped.state, 'cancelled');
  assert.ok(calls.every(call => call.taskId === 'audio-test-agent' && call.callId === 'audio-test-call'));
  await assert.rejects(service.execute('audio_transcribe', { captureId: 'both', filePath: '/some.wav' }, exec()), /选择其一/);
  await assert.rejects(service.execute('audio_transcript_read', { transcriptId: 'live-job' }, { signal: exec().signal }), /IDENTITY_REQUIRED/);
});

test('DSH dictation writes a temporary WAV, uses local native ASR and removes audio after settlement', async t => {
  let inputPath, polls = 0;
  const { service, home } = await fixture(t, { async audio(value) {
    if (value.operation === 'transcribe') {
      inputPath = value.inputPath;
      assert.deepEqual(await readFile(inputPath), wav);
      assert.equal(value.taskId, 'droy-input-dictation');
      return { transcriptId: 'dictation-job' };
    }
    assert.equal(value.operation, 'transcript');
    return ++polls === 1 ? { state: 'running', finished: false }
      : { state: 'completed', finished: polls > 2, text: '明天下午三点开会', audioSeconds: 2, inferenceSeconds: 0.2 };
  } });
  const value = await service.provider.transcribe({ audio: wav, language: 'zh' }, exec().signal);
  assert.deepEqual(value, { text: '明天下午三点开会', audioSeconds: 2, inferenceSeconds: 0.2 });
  assert.equal((await readdir(join(home, 'speech-to-text', 'droy-input'))).length, 0);
  await assert.rejects(readFile(inputPath), { code: 'ENOENT' });
});

test('dictation cancellation acknowledges native stop before deleting its temporary input', async t => {
  const controller = new AbortController(); let path, stopRequested = false, stopAcknowledged = false, notify;
  const running = new Promise(resolve => { notify = resolve; });
  const { service } = await fixture(t, { async audio(value, signal) {
    if (value.operation === 'transcribe') { path = value.inputPath; return { transcriptId: 'cancel-job' }; }
    if (value.operation === 'stop_transcription') {
      assert.deepEqual(await readFile(path), wav);
      assert.equal(signal.aborted, false);
      stopRequested = true; return { state: 'cancelling', finished: false };
    }
    if (stopRequested) {
      assert.deepEqual(await readFile(path), wav);
      stopAcknowledged = true; return { state: 'cancelled', finished: true };
    }
    notify();
    return new Promise((_, reject) => signal.addEventListener('abort', () => reject(signal.reason), { once: true }));
  } });
  const pending = service.provider.transcribe({ audio: wav, language: 'auto' }, controller.signal);
  await running; controller.abort(new Error('user cancelled dictation'));
  await assert.rejects(pending, /user cancelled/);
  assert.equal(stopAcknowledged, true);
  await assert.rejects(readFile(path), { code: 'ENOENT' });
});

test('dictation cleanup confirms completion with status-only reads after transcription permission is paused', async t => {
  const controller = new AbortController(); let path, paused = false, notify;
  const reads = [], running = new Promise(resolve => { notify = resolve; });
  const { service } = await fixture(t, { async audio(value, signal) {
    if (value.operation === 'transcribe') { path = value.inputPath; return { transcriptId: 'paused-dictation' }; }
    if (value.operation === 'stop_transcription') return { state: 'cancelling', finished: false };
    assert.equal(value.operation, 'transcript'); reads.push(value);
    if (paused) {
      if (!value.statusOnly) throw new BridgeError('PERMISSION_DENIED', '本地转写已暂停');
      return { state: 'cancelled', finished: true };
    }
    assert.equal(value.statusOnly, undefined, 'normal recognition still reads the transcript body');
    notify();
    return new Promise((_, reject) => signal.addEventListener('abort', () => reject(signal.reason), { once: true }));
  } });
  const pending = service.provider.transcribe({ audio: wav, language: 'auto' }, controller.signal);
  await running; paused = true; controller.abort(new Error('user paused transcription'));
  await assert.rejects(pending, /user paused transcription/);
  assert.equal(reads.length, 2);
  assert.equal(reads[1].statusOnly, true);
  assert.equal(reads[1].cursor, undefined);
  await assert.rejects(readFile(path), { code: 'ENOENT' });
});

test('native audio errors and binary stream metadata remain failures or bytes, never transcript text', async () => {
  const bodies = [];
  const bridge = createBridge({ port: 3031, token: TOKEN, fetchImpl: async (url, options) => {
    bodies.push(JSON.parse(options.body));
    if (url.endsWith('/audio/frames')) return new Response(Buffer.from([0, 1, 2, 3]), { headers: {
      'Content-Type': 'application/octet-stream', 'X-Droy-Audio-Metadata': JSON.stringify({ nextCursor: 4, sampleRate: 48000, encoding: 'pcm_s16le' }),
    } });
    return new Response(JSON.stringify({ ok: false, error: { code: 'PERMISSION_DENIED', message: '本地转写已关闭' } }), { headers: { 'Content-Type': 'application/json' } });
  } });
  await assert.rejects(bridge.audio({ operation: 'transcribe' }), /IDENTITY_REQUIRED/);
  await assert.rejects(bridge.audio({ operation: 'transcribe', taskId: 'owner', callId: 'asr-call' }), /PERMISSION_DENIED/);
  await assert.rejects(bridge.audioFile({ captureId: 'denied' }), /PERMISSION_DENIED/);
  const response = await bridge.audioFrames({ captureId: 'capture', taskId: 'owner', cursor: 0 });
  assert.deepEqual(Buffer.from(await response.arrayBuffer()), Buffer.from([0, 1, 2, 3]));
  assert.equal(JSON.parse(response.headers.get('X-Droy-Audio-Metadata')).nextCursor, 4);
  assert.equal(bodies.at(-1).taskId, 'owner');
});

test('transcription admission cancellation confirms the native call cancellation even before a job id arrives', async () => {
  const controller = new AbortController(), calls = [];
  const bridge = createBridge({ port: 3031, token: TOKEN, fetchImpl: async (url, options) => {
    calls.push({ path: new URL(url).pathname, body: JSON.parse(options.body) });
    if (url.endsWith('/audio')) return new Promise((_, reject) => options.signal.addEventListener('abort', () => reject(options.signal.reason), { once: true }));
    return result({ cancelled: true });
  } });
  const pending = bridge.audio({ operation: 'transcribe', taskId: 'engine-owner', callId: 'engine-call', inputPath: '/controlled.wav' }, controller.signal);
  controller.abort(new Error('cancel before admission receipt'));
  await assert.rejects(pending, /cancel before admission/);
  assert.deepEqual(calls.map(call => call.path), ['/audio', '/cancel']);
  assert.deepEqual(calls[1].body, { taskId: 'engine-owner', callId: 'engine-call' });
});

test('dictation retains temporary audio for native cleanup when cancellation cannot be confirmed', async t => {
  const controller = new AbortController(); let path, notify;
  const running = new Promise(resolve => { notify = resolve; });
  const { service } = await fixture(t, { async audio(value, signal) {
    if (value.operation === 'transcribe') { path = value.inputPath; return { transcriptId: 'unconfirmed-job' }; }
    if (value.operation === 'stop_transcription') throw new BridgeError('BRIDGE_UNAVAILABLE', '原生停止尚未确认');
    notify();
    return new Promise((_, reject) => signal.addEventListener('abort', () => reject(signal.reason), { once: true }));
  } });
  const pending = service.provider.transcribe({ audio: wav, language: 'auto' }, controller.signal);
  await running; controller.abort(new Error('cancel dictation'));
  await assert.rejects(pending, error => error.code === 'BRIDGE_UNAVAILABLE');
  assert.deepEqual(await readFile(path), wav);
});

test('dictation retains input when admission receipt is lost and service restart does not erase it', async t => {
  let path;
  const bridge = { async audio(value) {
    if (value.operation === 'transcribe') {
      path = value.inputPath;
      throw new BridgeError('BRIDGE_UNAVAILABLE', 'admission receipt lost');
    }
    return { state: 'ready' };
  } };
  const { service, home, attachments } = await fixture(t, bridge);
  await assert.rejects(service.provider.transcribe({ audio: wav }, exec().signal), /admission receipt lost/);
  assert.deepEqual(await readFile(path), wav);
  const restarted = createAudioService({ bridge, attachments, home, pollMs: 1 });
  try { assert.deepEqual(await readFile(path), wav); }
  finally { await restarted.dispose(); }
});

test('native provider registers against the real DSH speech registry without changing the main model', async t => {
  const home = await mkdtemp(join(tmpdir(), 'droy-dsh-speech-'));
  const ctx = new Context();
  t.after(async () => { await ctx.fiber.dispose(); await rm(home, { recursive: true, force: true }); });
  await ctx.plugin(MobileAttachments, { dshHome: home });
  await ctx.plugin(SpeechToText, { defaultProvider: AUDIO_PROVIDER_ID, language: 'auto' });
  const requests = [];
  installAudioServices(ctx, { async audio(value) { requests.push(value.operation); return { state: 'not_prepared' }; } });
  await new Promise(resolve => setImmediate(resolve));
  assert.equal(ctx.speechToText.listProviders()[0].id, AUDIO_PROVIDER_ID);
  assert.equal(ctx.speechToText.resolve({ audio: wav }).provider.info.location, 'host-local');
  assert.deepEqual(requests, ['preparation'], 'registration inspects state without downloading a model');
  assert.deepEqual(ctx.speechToText.snapshot().providers[0].preparation, { phase: 'unprepared' });
});

test('preparation mapping preserves actual native download, verify and load phases', () => {
  assert.deepEqual(speechPreparation({ state: 'preparing', stage: 'download', downloadedBytes: 12, totalBytes: 100 }),
    { phase: 'downloading', step: 'model', resource: 'SenseVoice', completedBytes: 12, totalBytes: 100 });
  assert.deepEqual(speechPreparation({ state: 'preparing', stage: 'verify', startedAt: 100 }),
    { phase: 'checking', step: 'verify', startedAt: 100 });
  assert.deepEqual(speechPreparation({ state: 'preparing', stage: 'load', startedAt: 200 }),
    { phase: 'loading', step: 'load', startedAt: 200 });
  assert.equal(speechPreparation({ state: 'ready' }).phase, 'ready');
  assert.equal(speechPreparation({ state: 'failed', error: '模型校验失败' }).phase, 'failed');
  assert.throws(() => speechPreparation({ state: 'unexpected' }), /INVALID_AUDIO_STATE/);
});

test('cancelling model preparation waits for native cleanup and does not restore stale preparing state', async t => {
  let cancelled = false;
  const { service } = await fixture(t, { async audio(value) {
    if (value.operation === 'cancel_preparation') { cancelled = true; return { state: 'preparing', stage: 'download', downloadedBytes: 4, totalBytes: 8 }; }
    return cancelled ? { state: 'not_prepared', stage: 'not_prepared' }
      : { state: 'preparing', stage: 'download', downloadedBytes: 4, totalBytes: 8 };
  } });
  service.provider.preparation.prepare();
  await new Promise(resolve => setImmediate(resolve));
  assert.equal(service.provider.preparation.snapshot().phase, 'downloading');
  await service.provider.preparation.cancel();
  assert.equal(service.provider.preparation.snapshot().phase, 'unprepared');
});

test('startup observes native bundled initialization through readiness without requesting a download', async t => {
  const calls = [];
  let checks = 0;
  const { service } = await fixture(t, { async audio(request) {
    calls.push(request.operation);
    assert.equal(request.operation, 'preparation');
    return ++checks === 1 ? { state: 'preparing', stage: 'verify' } : { state: 'ready' };
  } });
  const states = [];
  service.provider.preparation.subscribe(() => states.push(service.provider.preparation.snapshot().phase));
  await service.refreshPreparation();
  assert.equal(service.provider.preparation.snapshot().phase, 'checking');
  await new Promise((resolve, reject) => {
    const timeout = setTimeout(() => reject(new Error('Native initialization was not observed')), 2000);
    const unsubscribe = service.provider.preparation.subscribe(() => {
      if (service.provider.preparation.snapshot().phase !== 'ready') return;
      clearTimeout(timeout); unsubscribe(); resolve();
    });
  });
  assert.deepEqual(states, ['checking', 'ready']);
  assert.deepEqual(calls, ['preparation', 'preparation']);
});
