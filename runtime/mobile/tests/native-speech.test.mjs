import test from 'node:test';
import assert from 'node:assert/strict';
import { createServer } from 'node:http';
import { once } from 'node:events';
import { randomUUID } from 'node:crypto';
import { createRequire, registerHooks } from 'node:module';
import { pathToFileURL } from 'node:url';
const installed = createRequire(new URL('../../dsh/package.json', import.meta.url));
const hooks = registerHooks({ resolve(specifier, context, next) {
  if (specifier.startsWith('@deepseek-ai/')) return { url: pathToFileURL(installed.resolve(specifier)).href, shortCircuit: true };
  return next(specifier, context);
} });
const { default: SpeechController } = await import('@deepseek-ai/dsh-experimental-api-speech-to-text');
const { createNativeSpeech } = await import('../native-speech.mjs');
hooks.deregister();

function wave() {
  const bytes = Buffer.alloc(48); bytes.write('RIFF'); bytes.writeUInt32LE(40, 4); bytes.write('WAVEfmt ', 8);
  bytes.writeUInt32LE(16, 16); bytes.writeUInt16LE(1, 20); bytes.writeUInt16LE(1, 22); bytes.writeUInt32LE(48000, 24);
  bytes.writeUInt32LE(96000, 28); bytes.writeUInt16LE(2, 32); bytes.writeUInt16LE(16, 34); bytes.write('data', 36); bytes.writeUInt32LE(4, 40);
  return bytes;
}
function fixture() {
  const catalog = { providers: [{ id: 'droy-sensevoice-local', name: 'Fixture local speech', location: 'host-local', languages: ['auto', 'zh', 'en'], preparation: { phase: 'ready' } }],
    selection: { providerId: 'droy-sensevoice-local', language: 'auto' }, maxAudioBytes: 4096, maxDurationSeconds: 10 };
  const calls = [];
  const controller = { catalog: () => structuredClone(catalog), async transcribe(input, signal) {
    calls.push(input);
    return SpeechController.prototype.transcribe.call({ config: catalog, ctx: { speechToText: {
      resolve(value) { assert.equal(value.providerId, 'droy-sensevoice-local'); return value; },
      async transcribe(value, actualSignal) { assert.equal(actualSignal, signal); return { text: 'Fixture speech', audioSeconds: value.audio.length / 96000, inferenceSeconds: .01 }; },
    } } }, input, signal);
  } };
  const ctx = { get: name => name === 'speechController' ? controller : undefined,
    settings: { describe: () => [{ ns: 'locale', value: { preference: 'en' } }] } };
  return { catalog, controller, calls, ctx, service: createNativeSpeech(ctx) };
}
test('speech adapter forwards a canonical recording through the real SDK without any Session operation', async () => {
  const f = fixture();
  const result = await f.service.transcribe({ audioBase64: wave().toString('base64'), providerId: 'droy-sensevoice-local', language: 'auto' }, new AbortController().signal);
  assert.equal(result.text, 'Fixture speech'); assert.equal(f.calls.length, 1);
  assert.equal(Buffer.from(f.calls[0].audioBase64, 'base64').readUInt32LE(24), 16000);
  assert.equal(wave().readUInt32LE(24), 48000);
  const catalog = await f.service.catalog();
  assert.equal(catalog.maxAudioBytes, 4096); assert.equal(catalog.maxDurationSeconds, 10);
  assert.equal(catalog.nativeMaxAudioBytes, 20 * 1024 * 1024);
});
test('forged transcript flags and oversized input never enter the recognizer', async () => {
  const f = fixture(), signal = new AbortController().signal;
  for (const input of [{ text: 'forged', passed: true }, { audioBase64: wave().toString('base64'), completed: true }])
    await assert.rejects(f.service.transcribe(input, signal), error => error.code === 'NATIVE_SPEECH_BAD_REQUEST');
  await assert.rejects(f.service.transcribe({ audioBase64: Buffer.alloc(5000).toString('base64') }, signal), error => error.code === 'NATIVE_SPEECH_TOO_LARGE');
  assert.equal(f.calls.length, 0);
});
test('real SDK rejects malformed WAV and provider exceptions never disclose paths or secret text', async () => {
  const f = fixture(), signal = new AbortController().signal;
  await assert.rejects(f.service.transcribe({ audioBase64: Buffer.from('not a wave').toString('base64') }, signal),
    error => ['NATIVE_SPEECH_INVALID_AUDIO', 'NATIVE_SPEECH_TRANSCRIPTION_FAILED'].includes(error.code));
  f.controller.transcribe = async () => { throw new Error('fictional-private-key /data/private/fixture.wav'); };
  await assert.rejects(f.service.transcribe({ audioBase64: wave().toString('base64') }, signal), error => {
    assert.equal(error.code, 'NATIVE_SPEECH_TRANSCRIPTION_FAILED'); assert.ok(!String(error).includes('/data')); assert.ok(!String(error).includes('private-key')); return true;
  });
});
test('cancellation reaches the same SDK signal and settles before returning cancelled', async () => {
  const f = fixture(), cancellation = new AbortController();
  let entered, settled = false;
  const started = new Promise(resolve => { entered = resolve; });
  f.controller.transcribe = async (_request, signal) => {
    assert.equal(signal, cancellation.signal); entered();
    await new Promise(resolve => signal.addEventListener('abort', resolve, { once: true }));
    settled = true; signal.throwIfAborted();
  };
  const pending = f.service.transcribe({ audioBase64: wave().toString('base64') }, cancellation.signal);
  await started; cancellation.abort();
  await assert.rejects(pending, error => error.code === 'NATIVE_SPEECH_CANCELLED'); assert.equal(settled, true);
});
test('HTTP adapter accepts only exact routes and localized safe errors', async t => {
  const f = fixture(), cancellation = new AbortController();
  f.catalog.providers[0].preparation = { phase: 'failed', message: '/data/private/fictional-model-path' };
  const server = createServer((req, res) => f.service.handle(req, res, cancellation.signal));
  server.listen(0, '127.0.0.1'); await once(server, 'listening');
  t.after(async () => { server.closeAllConnections(); await new Promise(resolve => server.close(resolve)); });
  const base = `http://127.0.0.1:${server.address().port}/droy/native/speech`;
  const response = await fetch(`${base}/catalog`); assert.equal(response.status, 200);
  const value = await response.json(); assert.ok(!JSON.stringify(value).includes('/data/private'));
  assert.equal((await fetch(`${base}/catalog?provider=other`)).status, 400);
  const invalid = await fetch(`${base}/transcribe`, { method: 'POST', headers: { 'content-type': 'application/json' }, body: '{"passed":true}' });
  assert.equal(invalid.status, 400); assert.equal((await invalid.json()).error.message, 'The voice input is invalid.');
});

test('an immutable operation id never repeats SDK work and cannot change its recording', async () => {
  const f = fixture(), id = randomUUID(), signal = new AbortController().signal;
  const request = { audioBase64: wave().toString('base64'), providerId: 'droy-sensevoice-local', language: 'auto' };
  const first = await f.service.submit(id, request, signal);
  assert.deepEqual(await f.service.submit(id, request, signal), first);
  assert.equal(f.calls.length, 1);
  await assert.rejects(f.service.submit(id, { ...request, language: 'en' }, signal), error => error.code === 'NATIVE_SPEECH_OPERATION_CONFLICT');
  assert.equal(f.calls.length, 1);
  assert.equal(f.service.operation(id).quiescent, true);
});

test('cancelled SDK settlement does not invent native quiescence after an uncertain start', async () => {
  const f = fixture(), id = randomUUID(); let entered;
  const started = new Promise(resolve => { entered = resolve; });
  f.controller.transcribe = async (_request, signal) => {
    entered(); await new Promise(resolve => signal.addEventListener('abort', resolve, { once: true })); signal.throwIfAborted();
  };
  const pending = f.service.submit(id, { audioBase64: wave().toString('base64'), providerId: 'droy-sensevoice-local' }, new AbortController().signal);
  const rejection = assert.rejects(pending, error => error.code === 'NATIVE_SPEECH_CANCELLED');
  await started; const cancelled = await f.service.cancel(id); await rejection;
  assert.equal(cancelled.settled, true); assert.equal(cancelled.quiescent, false);
  assert.equal(cancelled.phase, 'cancelled');
  assert.throws(() => f.service.operation(randomUUID()), error => error.code === 'NATIVE_SPEECH_OPERATION_UNKNOWN');
});

test('bounded result retention keeps an old identity retired instead of repeating recognition', async () => {
  const f = fixture(), first = randomUUID(), signal = new AbortController().signal;
  const request = { audioBase64: wave().toString('base64'), providerId: 'droy-sensevoice-local' };
  await f.service.submit(first, request, signal);
  for (let index = 0; index < 64; index++) await f.service.submit(randomUUID(), request, signal);
  assert.equal(f.calls.length, 65);
  assert.equal(f.service.operation(first).quiescent, true);
  assert.equal(f.service.operation(first).result, undefined);
  await assert.rejects(f.service.submit(first, request, signal), error => error.code === 'NATIVE_SPEECH_RESULT_EXPIRED');
  assert.equal(f.calls.length, 65);
});

test('cancelling before a delayed POST retires that exact id and never starts its recognizer', async () => {
  const f = fixture(), id = randomUUID();
  assert.equal((await f.service.cancel(id)).settled, true);
  await assert.rejects(f.service.submit(id, { audioBase64: wave().toString('base64'), providerId: 'droy-sensevoice-local' }, new AbortController().signal),
    error => error.code === 'NATIVE_SPEECH_CANCELLED');
  assert.equal(f.calls.length, 0);
});
