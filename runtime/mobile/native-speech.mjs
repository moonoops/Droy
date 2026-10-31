import { TYPERT } from '@deepseek-ai/dsh-experimental-api-speech-to-text/typert';
import { resolveMobileLocale } from './locale.mjs';
import { createResampler, pcm16ToFloat32, floatsToPcm16 } from '../voice/pcm.mjs';
import { setImmediate } from 'node:timers/promises';
import { createHash } from 'node:crypto';

const base = '/droy/native/speech';
const nativeMaxAudioBytes = 20 * 1024 * 1024;
const methods = new Map(TYPERT.invocations.filter(item => item.namespace === 'speech').map(item => [item.method, item]));
const copy = {
  invalid: ['语音输入无效。', 'The voice input is invalid.'],
  unavailable: ['语音输入服务尚未就绪。', 'Voice input is not ready.'],
  preparation: ['语音资源准备失败，请在设置中检查。', 'Speech resource preparation failed. Check Voice settings.'],
  large: ['录音超过当前识别上限。', 'The recording exceeds the current recognition limit.'],
  failed: ['语音识别未完成，可保留录音后重试。', 'Recognition did not complete. Keep the recording and retry.'],
  cancelled: ['语音识别已取消。', 'Recognition was cancelled.'],
  conflict: ['这次识别的录音已变化，请重新开始。', 'This recognition recording changed. Start a new request.'],
  unknown: ['这次识别状态不可用，请核实原录音。', 'This recognition state is unavailable. Check the original recording.'],
  expired: ['识别结果已过期，请保留录音后重新识别。', 'The result expired. Keep the recording and recognize it again.'],
  capacity: ['语音识别记录已满，请重启引擎后重试。', 'Speech records are full. Restart the engine and retry.'],
  busy: ['语音识别正在使用，请稍后重试。', 'Speech recognition is busy. Try again shortly.'],
};
class NativeSpeechError extends Error {
  constructor(code, status, key) { super(code); this.code = code; this.status = status; this.key = key; }
}
const bad = () => new NativeSpeechError('NATIVE_SPEECH_BAD_REQUEST', 400, 'invalid');
const unavailable = () => new NativeSpeechError('NATIVE_SPEECH_NOT_READY', 503, 'unavailable');
function controller(ctx) {
  const value = ctx.get?.('speechController') ?? ctx.speechController;
  if (!value || typeof value.catalog !== 'function' || typeof value.transcribe !== 'function') throw unavailable();
  return value;
}
function json(res, status, value) {
  if (res.destroyed || res.writableEnded) return;
  res.writeHead(status, { 'Content-Type': 'application/json; charset=utf-8', 'Cache-Control': 'no-store' });
  res.end(JSON.stringify(value));
}
function parseInput(value) {
  const method = methods.get('transcribe');
  try { return method.input({ strict: true }).create().parse({ [method.parameters[0].wire]: value })[method.parameters[0].wire]; }
  catch { throw bad(); }
}
async function body(req, limit, signal) {
  if (!/^application\/json(?:\s*;|\s*$)/i.test(req.headers['content-type'] ?? '') || req.headers['content-encoding']) throw bad();
  if (Number(req.headers['content-length']) > limit) throw new NativeSpeechError('NATIVE_SPEECH_TOO_LARGE', 413, 'large');
  const parts = []; let bytes = 0;
  for await (const chunk of req) {
    signal?.throwIfAborted(); bytes += chunk.length;
    if (bytes > limit) throw new NativeSpeechError('NATIVE_SPEECH_TOO_LARGE', 413, 'large');
    parts.push(chunk);
  }
  try { return JSON.parse(new TextDecoder('utf-8', { fatal: true }).decode(Buffer.concat(parts))); }
  catch { throw bad(); }
}

/** Auth is owned by native-api. This adapter never creates or admits a Session message. */
export function createNativeSpeech(ctx) {
  const operations = new Map();
  const idPattern = /^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$/u;
  async function catalog() {
    let value;
    try { value = methods.get('catalog').result.create().parse(await controller(ctx).catalog()); }
    catch (error) { if (error instanceof NativeSpeechError) throw error; throw unavailable(); }
    if (!Number.isSafeInteger(value.maxAudioBytes) || value.maxAudioBytes <= 0
      || !Number.isFinite(value.maxDurationSeconds) || value.maxDurationSeconds <= 0) throw unavailable();
    return { ...value, nativeMaxAudioBytes, providers: value.providers.map(provider => ({ ...provider,
      preparation: provider.preparation.phase === 'failed' ? { ...provider.preparation,
        message: copy.preparation[resolveMobileLocale(ctx) === 'zh' ? 0 : 1], download: undefined } : provider.preparation })) };
  }
  async function transcribe(input, signal) {
    if (signal.aborted) throw new NativeSpeechError('NATIVE_SPEECH_CANCELLED', 409, 'cancelled');
    const request = parseInput(input), limits = await catalog();
    if (request.audioBase64.length > Math.ceil(nativeMaxAudioBytes / 3) * 4)
      throw new NativeSpeechError('NATIVE_SPEECH_TOO_LARGE', 413, 'large');
    try {
      const normalized = await recognitionWave(request.audioBase64, limits, signal);
      const result = await controller(ctx).transcribe({ ...request, audioBase64: normalized }, signal);
      signal.throwIfAborted();
      const value = methods.get('transcribe').result.create().parse(result);
      if (value.text.length > 32768) throw new NativeSpeechError('NATIVE_SPEECH_TRANSCRIPTION_FAILED', 422, 'failed');
      return value;
    } catch (error) {
      if (signal.aborted) throw new NativeSpeechError('NATIVE_SPEECH_CANCELLED', 409, 'cancelled');
      if (error instanceof NativeSpeechError) throw error;
      if (error?.code === 'speech/invalid-audio') throw new NativeSpeechError('NATIVE_SPEECH_INVALID_AUDIO', 422, 'invalid');
      throw new NativeSpeechError('NATIVE_SPEECH_TRANSCRIPTION_FAILED', 422, 'failed');
    }
  }
  function operationValue(entry) {
    return { phase: entry.phase, settled: entry.settled,
      // A successful local provider result follows its native finished=true read.
      // Failed or uncertain native admission still needs the Android Host's idle fact.
      quiescent: entry.settled && entry.phase === 'completed', ...(entry.result ? { result: entry.result } : {}) };
  }
  function operation(id) {
    const entry = operations.get(id);
    if (!entry) throw new NativeSpeechError('NATIVE_SPEECH_OPERATION_UNKNOWN', 404, 'unknown');
    return operationValue(entry);
  }
  async function cancel(id) {
    if (!idPattern.test(id)) throw bad();
    let entry = operations.get(id);
    if (!entry) {
      if (operations.size >= 1024) throw new NativeSpeechError('NATIVE_SPEECH_CAPACITY', 409, 'capacity');
      const cancellation = new AbortController(); cancellation.abort();
      const rejected = Promise.reject(new NativeSpeechError('NATIVE_SPEECH_CANCELLED', 409, 'cancelled')); rejected.catch(() => {});
      entry = { fingerprint: null, controller: cancellation, settled: true, phase: 'cancelled', promise: rejected };
      operations.set(id, entry);
    }
    entry.controller.abort();
    try { await entry.promise; } catch { /* Settlement is retained independently of its error receipt. */ }
    return operationValue(entry);
  }
  async function submit(id, input, caller) {
    if (!idPattern.test(id)) throw bad();
    const request = parseInput(input);
    if (request.providerId !== 'droy-sensevoice-local') throw bad();
    const fingerprint = createHash('sha256').update(JSON.stringify({ audioBase64: request.audioBase64,
      providerId: request.providerId, language: request.language ?? null })).digest('hex');
    let entry = operations.get(id);
    if (entry) {
      if (entry.fingerprint === null) throw new NativeSpeechError('NATIVE_SPEECH_CANCELLED', 409, 'cancelled');
      if (entry.fingerprint !== fingerprint) throw new NativeSpeechError('NATIVE_SPEECH_OPERATION_CONFLICT', 409, 'conflict');
      if (entry.settled && entry.phase === 'completed' && !entry.result) throw new NativeSpeechError('NATIVE_SPEECH_RESULT_EXPIRED', 409, 'expired');
      return entry.promise;
    }
    // Retired identities remain bounded tombstones; filling this run fails closed, never replays an old id.
    if (operations.size >= 1024) throw new NativeSpeechError('NATIVE_SPEECH_CAPACITY', 409, 'capacity');
    if ([...operations.values()].filter(item => !item.settled).length >= 2) throw new NativeSpeechError('NATIVE_SPEECH_BUSY', 409, 'busy');
    const cancellation = new AbortController();
    entry = { fingerprint, controller: cancellation, settled: false, phase: 'recognizing' };
    operations.set(id, entry);
    const interrupted = () => cancellation.abort();
    caller.addEventListener('abort', interrupted, { once: true });
    if (caller.aborted) cancellation.abort();
    entry.promise = transcribe(request, cancellation.signal).then(result => { entry.result = result; entry.phase = 'completed'; return result; }, error => {
      entry.phase = cancellation.signal.aborted ? 'cancelled' : 'failed'; throw error;
    }).finally(() => {
      entry.settled = true; caller.removeEventListener('abort', interrupted);
      const retained = [...operations.values()].filter(item => item.result);
      for (const old of retained.slice(0, Math.max(0, retained.length - 64))) { old.result = undefined; old.promise = Promise.reject(new NativeSpeechError('NATIVE_SPEECH_RESULT_EXPIRED', 409, 'expired')); old.promise.catch(() => {}); }
    });
    entry.promise.catch(() => {});
    return entry.promise;
  }
  return { catalog, transcribe, submit, operation, cancel, async handle(req, res, signal) {
    try {
      if (!req.url?.startsWith('/') || req.url.startsWith('//')) throw bad();
      const url = new URL(req.url, 'http://127.0.0.1');
      if (url.search || url.hash) throw bad();
      if (url.pathname === `${base}/catalog` && req.method === 'GET') {
        json(res, 200, { ok: true, result: await catalog() }); return;
      }
      const route = new RegExp(`^${base}/operations/(${idPattern.source.slice(1, -1)})(/cancel)?$`, 'u').exec(url.pathname);
      if (route) {
        if (!route[2] && req.method === 'GET') { json(res, 200, { ok: true, result: operation(route[1]) }); return; }
        if (route[2] && req.method === 'POST') {
          const input = await body(req, 1024, signal);
          if (!input || Array.isArray(input) || typeof input !== 'object' || Object.keys(input).length) throw bad();
          json(res, 200, { ok: true, result: await cancel(route[1]) }); return;
        }
        throw bad();
      }
      if (url.pathname !== `${base}/transcribe` || req.method !== 'POST') throw bad();
      const limits = await catalog();
      const input = await body(req, Math.ceil(nativeMaxAudioBytes / 3) * 4 + 65536, signal);
      if (!input || Array.isArray(input) || typeof input !== 'object' || Object.keys(input).sort().join(',') !== 'operationId,request') throw bad();
      json(res, 200, { ok: true, result: await submit(input.operationId, input.request, signal) });
    } catch (error) {
      req.resume();
      const failure = error instanceof NativeSpeechError ? error : signal?.aborted
        ? new NativeSpeechError('NATIVE_SPEECH_CANCELLED', 409, 'cancelled') : unavailable();
      json(res, failure.status, { ok: false, error: { code: failure.code,
        message: copy[failure.key][resolveMobileLocale(ctx) === 'zh' ? 0 : 1] } });
    }
  } };
}

/** The saved original remains 48 kHz. Only the disposable recognition view is normalized. */
async function recognitionWave(encoded, limits, signal) {
  const bytes = Buffer.from(encoded, 'base64');
  if (bytes.length > nativeMaxAudioBytes || bytes.toString('base64') !== encoded) throw bad();
  if (bytes.length >= 46 && bytes.toString('ascii', 0, 4) === 'RIFF' && bytes.toString('ascii', 8, 16) === 'WAVEfmt '
    && bytes.readUInt32LE(16) === 16 && bytes.readUInt16LE(20) === 1 && bytes.readUInt16LE(22) === 1
    && bytes.readUInt32LE(24) === 48000 && bytes.readUInt32LE(28) === 96000 && bytes.readUInt16LE(32) === 2
    && bytes.readUInt16LE(34) === 16 && bytes.toString('ascii', 36, 40) === 'data'
    && bytes.readUInt32LE(4) === bytes.length - 8 && bytes.readUInt32LE(40) === bytes.length - 44 && (bytes.length - 44) % 2 === 0) {
    if ((bytes.length - 44) / 96000 > limits.maxDurationSeconds) throw new NativeSpeechError('NATIVE_SPEECH_TOO_LARGE', 413, 'large');
    const resampler = createResampler(48000, 16000), parts = []; let size = 0;
    for (let offset = 44, index = 0; offset < bytes.length; offset += 65536, index++) {
      signal.throwIfAborted();
      const pcm = floatsToPcm16(resampler.push(pcm16ToFloat32(bytes.subarray(offset, Math.min(bytes.length, offset + 65536)))));
      parts.push(pcm); size += pcm.length;
      if (size + 44 > limits.maxAudioBytes) throw new NativeSpeechError('NATIVE_SPEECH_TOO_LARGE', 413, 'large');
      if (index % 16 === 15) await setImmediate(undefined, { signal });
    }
    if (size < 2) throw bad();
    const header = Buffer.from(bytes.subarray(0, 44));
    header.writeUInt32LE(size + 36, 4); header.writeUInt32LE(16000, 24); header.writeUInt32LE(32000, 28); header.writeUInt32LE(size, 40);
    return Buffer.concat([header, ...parts]).toString('base64');
  }
  if (bytes.length > limits.maxAudioBytes) throw new NativeSpeechError('NATIVE_SPEECH_TOO_LARGE', 413, 'large');
  return encoded; // The SDK remains the validator for its canonical 16 kHz wire format.
}
