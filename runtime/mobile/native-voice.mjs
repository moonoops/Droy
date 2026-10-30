import { TextDecoder } from 'node:util';
import { resolveMobileLocale } from './locale.mjs';

const base = '/droy/native/voice', limit = 32768;
const providers = ['system', 'melo', 'qwen3', 'openai'];
const copy = {
  invalid: ['语音设置无效，请检查输入。', 'Check the voice settings.'],
  unavailable: ['语音服务尚未就绪。', 'Voice service is not ready.'],
  method: ['不支持此语音操作。', 'This voice operation is not supported.'],
  permission: ['请先在权限中允许语音能力。', 'Allow voice access in Permissions first.'],
  resource: ['请先下载所选语音资源。', 'Download the selected voice resources first.'],
  busy: ['请先结束当前语音。', 'End the current voice session first.'],
  credential: ['凭据保存结果未确认，请重试。', 'Credential save was not confirmed. Try again.'],
  wake: ['唤醒已应用，配置未保存，请重试同步。', 'Wake settings applied; configuration was not saved. Retry syncing.'],
  conflict: ['唤醒设置已变化，请刷新。', 'Wake settings changed. Refresh first.'],
  cancelled: ['请求已取消，请刷新确认状态。', 'Request cancelled. Refresh to confirm the state.'],
  failed: ['语音操作未完成，请重试。', 'Voice operation did not complete. Try again.'],
};
class NativeVoiceError extends Error {
  constructor(code, status, text) { super(code); this.code = code; this.status = status; this.text = text; }
}
const bad = () => new NativeVoiceError('NATIVE_VOICE_BAD_REQUEST', 400, 'invalid');
const unavailable = () => new NativeVoiceError('NATIVE_VOICE_NOT_READY', 503, 'unavailable');
const object = value => value !== null && typeof value === 'object' && !Array.isArray(value);
function fields(value, allowed, required = allowed) {
  if (!object(value) || Object.keys(value).some(key => !allowed.includes(key)) || required.some(key => !Object.hasOwn(value, key))) throw bad();
}
function text(value, size) { if (typeof value !== 'string' || value.length > size || /[\x00-\x1f\x7f]/u.test(value)) throw bad(); return value; }
function bool(value) { if (typeof value !== 'boolean') throw bad(); return value; }
function rate(value) { if (!Number.isFinite(value) || value < .5 || value > 2) throw bad(); return value; }
function url(value, httpOnly = true) {
  text(value, 2048); if (!value) return value;
  let parsed; try { parsed = new URL(value); } catch { throw bad(); }
  if (!(httpOnly ? ['http:', 'https:'] : ['http:', 'https:', 'ws:', 'wss:']).includes(parsed.protocol) || parsed.username || parsed.password || parsed.hash
    || [...parsed.searchParams.keys()].some(name => /^(?:key|api[_-]?key|token|access_token)$/iu.test(name))) throw bad();
  return value;
}
function service(ctx, name, methods) {
  const value = typeof ctx.get === 'function' ? ctx.get(name) : ctx[name];
  if (!value || methods.some(method => typeof value[method] !== 'function')) throw unavailable();
  return value;
}
function machine(value) { return typeof value === 'string' && /^[\w.:-]{1,64}$/u.test(value) ? value : 'unknown'; }
function progress(value) {
  if (!object(value)) throw unavailable();
  return { available: true, state: machine(value.state ?? value.phase), ready: value.ready === true || ['ready', 'standby', 'waking'].includes(value.phase),
    downloadedBytes: Number.isSafeInteger(value.downloadedBytes ?? value.completedBytes) && (value.downloadedBytes ?? value.completedBytes) >= 0 ? value.downloadedBytes ?? value.completedBytes : 0,
    totalBytes: Number.isSafeInteger(value.totalBytes) && value.totalBytes >= 0 ? value.totalBytes : 0,
    failed: Boolean(value.error) || ['failed', 'failure'].includes(value.state ?? value.phase) };
}
function config(value) {
  if (!object(value) || !providers.includes(value.tts?.provider)) throw unavailable();
  return { entries: { standard: bool(value.entries.standard) }, tts: {
    provider: value.tts.provider, voiceId: text(value.tts.voiceId, 256), rate: rate(value.tts.rate),
    url: url(value.tts.url, false), model: text(value.tts.model, 256), hasKey: bool(value.tts.hasKey),
  }, wake: wakePatch(value.wake, 80) };
}
function wakePatch(value, maxLength = 48) {
  fields(value, ['enabled', 'timing', 'useCompanionName', 'chinese', 'english', 'target'], ['enabled', 'timing', 'useCompanionName', 'chinese', 'english']);
  if (!['screen_on', 'foreground'].includes(value.timing) || value.target !== undefined && value.target !== 'standard') throw bad();
  return { enabled: bool(value.enabled), timing: value.timing, useCompanionName: bool(value.useCompanionName),
    chinese: text(value.chinese, maxLength), english: text(value.english, maxLength) };
}
function wake(value) {
  if (!object(value)) throw unavailable();
  return { available: true, ...wakePatch({ enabled: value.enabled, timing: value.timing, useCompanionName: value.useCompanionName,
    chinese: value.chineseKeyword, english: value.englishKeyword }), state: machine(value.state),
    screenAvailable: bool(value.screenAvailable), suspended: bool(value.suspended), pausedForVoice: bool(value.pausedForVoice),
    voiceClaimed: bool(value.voiceClaimed), companionName: text(value.companionName ?? '', 256),
    failed: Boolean(value.error), preparation: progress(value.preparation) };
}
function resources(value) {
  if (!Array.isArray(value?.models)) throw unavailable();
  return value.models.filter(row => ['melo', 'qwen3'].includes(row.provider)).map(row => ({ provider: row.provider, ...progress(row) }));
}
function voices(value) {
  if (!Array.isArray(value?.voices) || typeof value.systemReady !== 'boolean') throw unavailable();
  return { available: true, systemReady: value.systemReady, failed: Boolean(value.systemError || value.error),
    items: value.voices.filter(row => providers.includes(row.provider)).map(row => ({
      id: text(row.id, 512), provider: row.provider, voiceId: text(row.voiceId ?? row.id, 256), name: text(row.name ?? row.id, 256),
      ...(typeof row.networkRequired === 'boolean' ? { networkRequired: row.networkRequired } : {}),
      ...(typeof row.language === 'string' ? { language: text(row.language, 128) } : {}),
    })) };
}
function runtime(value) {
  if (!object(value) || typeof value.speaking !== 'boolean') throw unavailable();
  return { available: true, state: machine(value.state), speaking: value.speaking, failed: Boolean(value.error || value.speechError) };
}
function projected(result, project) {
  if (result.status !== 'fulfilled') return { available: false };
  try { return project(result.value); } catch { return { available: false }; }
}
function reply(res, status, body) {
  if (res.destroyed || res.writableEnded) return;
  const data = JSON.stringify(body); res.writeHead(status, { 'Content-Type': 'application/json; charset=utf-8', 'Cache-Control': 'no-store' }); res.end(data);
}
async function body(req) {
  if (!/^application\/json(?:\s*;|\s*$)/iu.test(req.headers['content-type'] ?? '') || Number(req.headers['content-length']) > limit) {
    req.resume(); throw bad();
  }
  const chunks = []; let size = 0;
  for await (const chunk of req) { size += chunk.length; if (size > limit) { req.resume(); throw bad(); } chunks.push(chunk); }
  try { return JSON.parse(new TextDecoder('utf-8', { fatal: true }).decode(Buffer.concat(chunks))); } catch { throw bad(); }
}

export function createNativeVoice(ctx) {
  const voice = () => service(ctx, 'droyVoice', ['snapshot', 'run']);
  async function snapshot() {
    const current = voice();
    const saved = config((await current.snapshot()).config);
    const states = await Promise.allSettled([current.run({ operation: 'status' }), current.run({ operation: 'wake.status' }),
      current.run({ operation: 'voices' }), current.run({ operation: 'tts.models' }),
      Promise.resolve().then(async () => {
        const audio = service(ctx, 'droyAudio', ['refreshPreparation', 'snapshot']);
        await audio.refreshPreparation(); return audio.snapshot();
      })]);
    const currentRuntime = projected(states[0], runtime);
    let preview = { active: false };
    const previewAvailable = currentRuntime.available && typeof current.speech?.previewOwnership === 'function';
    if (previewAvailable)
      preview = current.speech.previewOwnership(states[0].value);
    return { config: saved, runtime: currentRuntime, wake: projected(states[1], wake), voices: projected(states[2], voices),
      resources: projected(states[3], value => ({ available: true, items: resources(value) })),
      asr: projected(states[4], value => ({ ...progress(value.preparation), provider: 'sensevoice-local' })),
      preview: { available: previewAvailable, active: preview.active === true, ...(preview.active ? { owner: text(preview.owner, 128), phase: machine(preview.phase) } : {}) } };
  }
  async function settings(input) {
    fields(input, ['patch']); fields(input.patch, ['entries', 'tts'], []);
    if (!Object.keys(input.patch).length) throw bad();
    if (input.patch.entries) { fields(input.patch.entries, ['standard']); bool(input.patch.entries.standard); }
    if (input.patch.tts) {
      const tts = input.patch.tts;
      fields(tts, ['provider', 'voiceId', 'rate', 'url', 'model']);
      if (!providers.includes(tts.provider) || tts.provider === 'qwen3' && tts.rate !== 1) throw bad();
      text(tts.voiceId, 256); rate(tts.rate); url(tts.url); text(tts.model, 256);
      if (tts.provider === 'openai' && (!tts.url || !tts.model.trim())) throw bad();
    }
    return { committed: true, config: config(await voice().run({ operation: 'save_config', patch: input.patch })) };
  }
  async function credentials(input) {
    fields(input, ['operation', 'value'], ['operation']);
    if (!['set', 'clear'].includes(input.operation) || input.operation === 'clear' && input.value !== undefined) throw bad();
    const patch = input.operation === 'clear' ? { clearKey: true } : { key: text(input.value, 8192).trim() };
    if (input.operation === 'set' && !patch.key) throw bad();
    try { return { committed: true, hasKey: bool((await voice().run({ operation: 'save_config', patch: { tts: patch } })).tts.hasKey) }; }
    catch { throw new NativeVoiceError('NATIVE_VOICE_CREDENTIAL_NOT_CONFIRMED', 409, 'credential'); }
  }
  async function configureWake(input) {
    fields(input, ['patch', 'commitOnly'], ['patch']);
    let patch = wakePatch(input.patch);
    const current = voice();
    if (input.commitOnly !== undefined) bool(input.commitOnly);
    if (input.commitOnly) {
      const actual = wake(await current.run({ operation: 'wake.status' }));
      if (Object.keys(patch).some(key => actual[key] !== patch[key])) throw new NativeVoiceError('NATIVE_VOICE_WAKE_CONFLICT', 409, 'conflict');
    } else {
      const applied = wake(await current.run({ operation: 'wake.configure', ...patch, target: 'standard' }));
      patch = Object.fromEntries(Object.keys(patch).map(key => [key, applied[key]]));
    }
    try { return { committed: true, config: config(await current.run({ operation: 'save_config', patch: { wake: { ...patch, target: 'standard' } } })) }; }
    catch { throw new NativeVoiceError('NATIVE_VOICE_WAKE_NOT_SAVED', 409, 'wake'); }
  }
  async function resource(input) {
    fields(input, ['provider', 'operation']);
    if (!['melo', 'qwen3'].includes(input.provider) || !['download', 'cancel'].includes(input.operation)) throw bad();
    const result = await voice().run({ operation: input.operation === 'download' ? 'tts.download' : 'tts.cancel_download', provider: input.provider });
    return { items: resources(result) };
  }
  async function preview(input) {
    fields(input, ['operation', 'owner', 'provider', 'voiceId', 'rate'], ['operation', 'owner']);
    if (!['start', 'stop'].includes(input.operation) || !/^[A-Za-z0-9_-]{1,128}$/u.test(input.owner)) throw bad();
    const current = voice(), speech = current.speech;
    if (typeof speech?.previewOwned !== 'function' || typeof speech?.stopOwned !== 'function') throw unavailable();
    if (input.operation === 'stop') {
      if (Object.keys(input).some(key => !['operation', 'owner'].includes(key))) throw bad();
      return speech.stopOwned(input.owner);
    }
    if (current.realtime?.active) throw new NativeVoiceError('NATIVE_VOICE_BUSY', 409, 'busy');
    if (!['system', 'melo', 'qwen3'].includes(input.provider)) throw bad();
    text(input.voiceId, 256); rate(input.rate);
    if (input.provider === 'qwen3' && input.rate !== 1) throw bad();
    return speech.previewOwned(input.owner, undefined, { provider: input.provider, voiceId: input.voiceId, rate: input.rate });
  }
  const routes = new Map([['/settings', settings], ['/credentials', credentials], ['/wake', configureWake], ['/resources', resource], ['/preview', preview],
    ['/wake/prepare', async input => { fields(input, []); return { wake: wake(await voice().run({ operation: 'wake.prepare' })) }; }]]);
  return { async handle(req, res, signal) {
    try {
      const target = new URL(req.url, `http://${req.headers.host}`);
      if (target.search || target.pathname !== base && !target.pathname.startsWith(`${base}/`)) throw bad();
      signal?.throwIfAborted();
      let result;
      if (req.method === 'GET' && target.pathname === base) result = await snapshot();
      else {
        const route = routes.get(target.pathname.slice(base.length));
        if (req.method !== 'POST' || !route) throw new NativeVoiceError('NATIVE_VOICE_METHOD_NOT_ALLOWED', 405, 'method');
        result = await route(await body(req));
      }
      signal?.throwIfAborted(); reply(res, 200, { ok: true, result });
    } catch (error) {
      const own = error instanceof NativeVoiceError, cancelled = signal?.aborted || error?.name === 'AbortError';
      const key = own ? error.text : cancelled ? 'cancelled' : error?.code === 'TTS_MODEL_NOT_READY' ? 'resource'
        : error?.code === 'PERMISSION_DENIED' ? 'permission' : error?.code === 'VOICE_BUSY' ? 'busy'
        : ['INVALID_CONFIG', 'INVALID_SPEECH', 'INVALID_URL', 'KEY_IN_URL'].includes(error?.code) ? 'invalid' : 'failed';
      const code = own ? error.code : cancelled ? 'NATIVE_VOICE_CANCELLED' : `NATIVE_VOICE_${key.toUpperCase()}`;
      reply(res, own ? error.status : cancelled || key === 'permission' || key === 'resource' || key === 'busy' ? 409 : 500,
        { ok: false, error: { code, message: copy[key][resolveMobileLocale(ctx) === 'zh' ? 0 : 1] } });
    }
  } };
}
