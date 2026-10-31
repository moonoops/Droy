import { mkdir, readFile, rename, rm, writeFile } from 'node:fs/promises';
import { join } from 'node:path';
import { randomUUID } from 'node:crypto';

export const GEMINI_ENDPOINT = 'wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent';
export const DEFAULT_CONFIG = {
  entries: { standard: true, realtimeVoice: false, realtimeVideo: false },
  interruptible: false,
  realtime: { provider: 'minicpm', url: '', model: '', voice: '' },
  tts: { provider: 'system', voiceId: '', rate: 1, url: '', model: 'tts-1' },
  voices: [],
  wake: { enabled: false, chinese: '你好Droy', english: 'Hey Dory', target: 'standard', timing: 'screen_on', useCompanionName: false },
};
const REFS = { realtime: 'DROY_VOICE_REALTIME_API_KEY', tts: 'DROY_VOICE_TTS_API_KEY' };

export class VoiceError extends Error {
  constructor(code, message, status = 400) { super(message); this.code = code; this.status = status; }
}

export function endpoint(value, protocols = ['http:', 'https:', 'ws:', 'wss:']) {
  let url;
  try { url = new URL(value); }
  catch { throw new VoiceError('INVALID_URL', '请填写完整的服务地址'); }
  if (!protocols.includes(url.protocol) || url.username || url.password || url.hash)
    throw new VoiceError('INVALID_URL', '服务地址格式不支持');
  // API credentials have a separate store and must not be echoed in URLs.
  for (const name of url.searchParams.keys())
    if (/^(?:key|api[_-]?key|token|access_token)$/iu.test(name))
      throw new VoiceError('KEY_IN_URL', '请将凭据填写在 API Key 中');
  return url;
}

export function defaultVoiceId(provider) {
  return provider === 'melo' ? 'zh' : provider === 'qwen3' ? 'Serena' : '';
}

function text(value, limit, label) {
  if (typeof value !== 'string' || value.length > limit)
    throw new VoiceError('INVALID_CONFIG', `${label}格式无效`);
  return value.trim();
}
function rate(value) {
  if (typeof value !== 'number' || !Number.isFinite(value) || value < 0.5 || value > 2)
    throw new VoiceError('INVALID_CONFIG', '语速应在 0.5 到 2 之间');
  return value;
}
function bool(value) {
  if (typeof value !== 'boolean') throw new VoiceError('INVALID_CONFIG', '开关值无效');
  return value;
}
function source(value, values, label) {
  if (!values.includes(value)) throw new VoiceError('INVALID_CONFIG', `${label}不支持`);
  return value;
}

export function mergeConfig(current, patch) {
  if (!patch || typeof patch !== 'object' || Array.isArray(patch))
    throw new VoiceError('INVALID_CONFIG', '设置格式无效');
  const next = structuredClone(current);
  if (patch.entries) for (const name of ['standard', 'realtimeVoice', 'realtimeVideo'])
    if (Object.hasOwn(patch.entries, name)) next.entries[name] = bool(patch.entries[name]);
  // The entry preference is retired; microphone and product authorization still gate capture.
  next.entries.standard = true;
  if (Object.hasOwn(patch, 'interruptible')) next.interruptible = bool(patch.interruptible);
  for (const section of ['realtime', 'tts']) {
    const incoming = patch[section];
    if (!incoming) continue;
    if (Object.hasOwn(incoming, 'provider')) next[section].provider = source(incoming.provider,
      section === 'realtime' ? ['minicpm', 'comni', 'gemini', 'qwen'] : ['system', 'melo', 'qwen3', 'openai'], '语音服务');
    if (section === 'tts' && next.tts.provider !== current.tts.provider) {
      if (!Object.hasOwn(incoming, 'voiceId')) next.tts.voiceId = defaultVoiceId(next.tts.provider);
      if (next.tts.provider === 'qwen3' && !Object.hasOwn(incoming, 'rate')) next.tts.rate = 1;
    }
    for (const name of ['url', 'model', 'voice', 'voiceId']) {
      if (!Object.hasOwn(next[section], name) || !Object.hasOwn(incoming, name)) continue;
      next[section][name] = text(incoming[name], name === 'url' ? 2048 : 256, '服务配置');
      if (name === 'url' && next[section][name]) endpoint(next[section][name]);
    }
    if (section === 'tts' && Object.hasOwn(incoming, 'rate')) next.tts.rate = rate(incoming.rate);
    if (section === 'tts' && next.tts.provider === 'qwen3' && next.tts.rate !== 1)
      throw new VoiceError('INVALID_CONFIG', 'Qwen3-TTS 当前仅支持标准语速');
  }
  if (patch.realtime?.provider === 'gemini' && !Object.hasOwn(patch.realtime, 'url')) next.realtime.url = GEMINI_ENDPOINT;
  if (patch.realtime?.provider === 'gemini' && !Object.hasOwn(patch.realtime, 'model')) next.realtime.model = 'gemini-3.8-live';
  if (patch.wake) {
    if (Object.hasOwn(patch.wake, 'enabled')) next.wake.enabled = bool(patch.wake.enabled);
    if (Object.hasOwn(patch.wake, 'timing')) next.wake.timing = source(patch.wake.timing, ['screen_on', 'foreground'], '唤醒时机');
    if (Object.hasOwn(patch.wake, 'useCompanionName')) bool(patch.wake.useCompanionName);
    next.wake.useCompanionName = false;
    for (const name of ['chinese', 'english'])
      if (Object.hasOwn(patch.wake, name)) next.wake[name] = text(patch.wake[name], 80, '唤醒词');
    if (Object.hasOwn(patch.wake, 'target')) next.wake.target = source(patch.wake.target, ['standard', 'realtime'], '唤醒目标');
    if (next.wake.enabled && !next.wake.useCompanionName && !next.wake.chinese && !next.wake.english)
      throw new VoiceError('INVALID_CONFIG', '请至少填写一个唤醒词');
  }
  return next;
}

export function voiceProfile(input) {
  if (!input || typeof input !== 'object') throw new VoiceError('INVALID_VOICE', '音色配置无效');
  const name = text(input.name, 80, '音色名称'), voiceId = text(input.voiceId, 256, '音色 ID');
  if (!name || !voiceId) throw new VoiceError('INVALID_VOICE', '请填写音色名称和 ID');
  const provider = source(input.provider, ['system', 'melo', 'qwen3', 'openai'], '朗读服务');
  const speed = input.rate === undefined ? 1 : rate(input.rate);
  if (provider === 'qwen3' && speed !== 1) throw new VoiceError('INVALID_VOICE', 'Qwen3-TTS 当前仅支持标准语速');
  return { id: randomUUID(), name, provider, voiceId, rate: speed };
}

function referenceAudio(input) {
  if (!input || input.sampleRate !== 16000 || input.channels !== 1 || input.encoding !== 'pcm_f32le')
    throw new VoiceError('INVALID_REFERENCE_AUDIO', '参考音频应为 16kHz 单声道 float32');
  const name = text(input.name, 180, '参考音频名称');
  const maxBytes = 16000 * 4 * 30, data = input.data;
  if (!name || typeof data !== 'string' || data.length > Math.ceil(maxBytes / 3) * 4
      || data.length % 4 || !/^[A-Za-z0-9+/]*={0,2}$/u.test(data))
    throw new VoiceError('INVALID_REFERENCE_AUDIO', '参考音频格式无效或超过 30 秒');
  const bytes = Buffer.from(data, 'base64');
  if (bytes.length % 4 || bytes.length < 16000 * 4 * 0.5 || bytes.length > maxBytes)
    throw new VoiceError('INVALID_REFERENCE_AUDIO', '参考音频应为 0.5 到 30 秒');
  for (let i = 0; i < bytes.length; i += 4) {
    const value = bytes.readFloatLE(i);
    if (!Number.isFinite(value) || Math.abs(value) > 1)
      throw new VoiceError('INVALID_REFERENCE_AUDIO', '参考音频包含无效采样值');
  }
  return { name, data, sampleRate: 16000, channels: 1, encoding: 'pcm_f32le',
    seconds: bytes.length / 64000, bytes: bytes.length };
}

/** Configuration only carries settings; API keys belong to DSH's credential provider. */
export function createConfigStore({ home, credentials, env = process.env } = {}) {
  if (!home) throw new Error('Droy Voice requires DSH_HOME');
  const directory = join(home, 'voice'), file = join(directory, 'config.json');
  const referenceFile = join(directory, 'comni-reference.json');
  let current, loading, reference, referenceLoading, mutation = Promise.resolve();
  async function load() {
    if (current) return current;
    return loading ??= (async () => {
      try {
        const saved = JSON.parse(await readFile(file, 'utf8'));
        // Kokoro was a development candidate and is no longer shipped.
        if (saved.tts?.provider === 'kokoro') saved.tts = { ...saved.tts, provider: 'system', voiceId: '' };
        current = mergeConfig(DEFAULT_CONFIG, saved);
        current.voices = Array.isArray(saved.voices) ? saved.voices.filter(value => value?.provider !== 'kokoro').map(value => ({ ...voiceProfile(value), id: text(value.id, 80, '音色记录') })) : [];
      } catch (error) {
        if (error.code !== 'ENOENT') throw error;
        current = structuredClone(DEFAULT_CONFIG);
      }
      return current;
    })();
  }
  async function writeAtomic(destination, value) {
    await mkdir(directory, { recursive: true, mode: 0o700 });
    const staging = `${destination}.${randomUUID()}.tmp`;
    await writeFile(staging, `${JSON.stringify(value, null, 2)}\n`, { mode: 0o600 });
    await rename(staging, destination);
  }
  async function persist(value) {
    await writeAtomic(file, value);
    current = value;
  }
  async function loadReference() {
    if (reference !== undefined) return reference;
    return referenceLoading ??= (async () => {
      try { reference = referenceAudio(JSON.parse(await readFile(referenceFile, 'utf8'))); }
      catch (error) { if (error.code !== 'ENOENT') throw error; reference = null; }
      return reference;
    })();
  }
  const serialize = action => {
    const result = mutation.then(action);
    mutation = result.catch(() => {});
    return result;
  };
  async function key(section) {
    return (await credentials?.resolve(REFS[section]))?.value || env[REFS[section]] || '';
  }
  async function publicConfig() {
    const value = structuredClone(await load());
    for (const section of ['realtime', 'tts']) value[section].hasKey = Boolean(await key(section));
    // This release keeps dormant configuration on disk without reopening its
    // entry points or reading reference audio during ordinary voice startup.
    value.entries.realtimeVoice = false;
    value.entries.realtimeVideo = false;
    value.interruptible = false;
    value.wake.target = 'standard';
    return value;
  }
  return {
    read: async () => structuredClone(await load()), publicConfig, key,
    referenceAudio: async () => structuredClone(await loadReference()),
    setReferenceAudio(input) { return serialize(async () => {
      await loadReference();
      const next = referenceAudio(input);
      await writeAtomic(referenceFile, next); reference = next;
      return publicConfig();
    }); },
    removeReferenceAudio() { return serialize(async () => {
      await loadReference();
      await rm(referenceFile, { force: true }); reference = null;
      return publicConfig();
    }); },
    save(patch) { return serialize(async () => {
      const next = mergeConfig(await load(), patch);
      for (const section of ['realtime', 'tts']) {
        const incoming = patch[section];
        if (!incoming) continue;
        if (incoming.clearKey === true) {
          if (!credentials?.unset) throw new VoiceError('CREDENTIALS_UNAVAILABLE', '凭据存储尚未就绪', 503);
          await credentials.unset(REFS[section]);
        } else if (incoming.key !== undefined && incoming.key !== '') {
          const value = text(incoming.key, 8192, 'API Key');
          if (!value || /[\r\n\u0000]/u.test(value)) throw new VoiceError('INVALID_CONFIG', 'API Key 格式无效');
          if (!credentials?.set) throw new VoiceError('CREDENTIALS_UNAVAILABLE', '凭据存储尚未就绪', 503);
          await credentials.set(REFS[section], value);
        }
      }
      await persist(next);
      return publicConfig();
    }); },
    addVoice(input) { return serialize(async () => {
      const next = structuredClone(await load()), item = voiceProfile(input);
      if (next.voices.length >= 64) throw new VoiceError('VOICE_LIMIT', '已添加的音色过多，请先移除不用的音色');
      next.voices.push(item); await persist(next); return item;
    }); },
    removeVoice(id) { return serialize(async () => {
      const next = structuredClone(await load());
      next.voices = next.voices.filter(item => item.id !== id);
      await persist(next); return publicConfig();
    }); },
    async findVoice(id) {
      const value = await load(), found = value.voices.filter(item => item.id === id || item.name === id);
      if (found.length !== 1) throw new VoiceError('VOICE_NOT_FOUND', found.length ? '有多个同名音色，请选择一个' : '找不到这个音色');
      return structuredClone(found[0]);
    },
    async selectVoice(id) {
      const item = await this.findVoice(id);
      return this.save({ tts: { provider: item.provider, voiceId: item.voiceId, rate: item.rate } });
    },
  };
}
