import { VoiceError, endpoint, mergeConfig, defaultVoiceId } from './config.mjs';
import { setTimeout as delay } from 'node:timers/promises';

export const VOICE_ROUTE = '/droy/voice';
const nativeOperations = new Set(['start', 'poll', 'status', 'stop', 'voices', 'mute', 'play', 'clear_playback', 'tts.models', 'tts.download', 'tts.cancel_download']);

export function createVoiceOutputState({ getPrivacy, nativeStatus, protectionRequired = false }) {
  return async sessionId => {
    const privacy = getPrivacy();
    if (privacy?.outputState) {
      const state = await privacy.outputState(sessionId);
      protectionRequired ||= state.active;
      return protectionRequired && !state.active ? { active: true, state: 'unknown' } : state;
    }
    // The persisted native latch survives a missing or unloaded privacy plugin.
    // This check neither initializes a private store nor reads record bodies.
    const status = await nativeStatus();
    if (typeof status?.settings?.privacyEverActivated !== 'boolean') throw new Error('TTS_PRIVACY_STATE_UNAVAILABLE');
    protectionRequired ||= status.settings.privacyEverActivated;
    return { active: protectionRequired, state: protectionRequired ? 'unknown' : 'inactive' };
  };
}

export function createSpeechService({ store, native, fetchImpl = fetch, getLocale = () => process.env.DROY_LOCALE,
  outputState = async () => ({ active: false, state: 'inactive' }) }) {
  let generation = 0, chain = Promise.resolve(), request, nextPlaybackAt = 0, activeReply;
  let everProtected = false, playbackUnscoped = false;
  const jobs = new Set(), playbackSessions = new Set(), publicPreview = Symbol('fixed-preview');
  const localProvider = provider => provider === 'melo' || provider === 'qwen3';
  function privacyError(code = 'TTS_PRIVATE_SESSION') {
    const messages = code === 'TTS_REPLY_SESSION_MISMATCH'
      ? ['这段朗读的回复与会话不匹配，请重新发起朗读', 'This readout does not match its source conversation. Start it again.']
      : ['这段对话暂不能使用系统或联网朗读，请选择本地 Melo 或 Qwen3 音色',
        'System and network readout are unavailable for this conversation. Choose a local Melo or Qwen3 voice.'];
    return new VoiceError(code, getLocale() === 'zh' ? messages[0] : messages[1], 409);
  }
  async function authorize(job, provider) {
    if (job.public || localProvider(provider)) return;
    const revision = job.revision;
    let state;
    try { state = await outputState(job.sessionId); } catch { throw privacyError(); }
    if (!state || typeof state.active !== 'boolean' || everProtected && !state.active) throw privacyError();
    everProtected ||= state.active;
    if (revision !== job.revision || state.active && (!job.sessionId || state.state !== 'known-clean')) throw privacyError();
    return revision;
  }
  function rememberPlayback(job, provider) {
    if (job.public || localProvider(provider)) return;
    if (!job.sessionId || playbackSessions.size >= 64) playbackUnscoped = true;
    else playbackSessions.add(job.sessionId);
  }
  async function voices() {
    const result = await native({ operation: 'voices' });
    const config = await store.read();
    return { ...result, voices: [
      ...(result.voices || []).map(item => ({ ...item, id: `${item.provider || 'system'}:${item.id}`,
        voiceId: item.voiceId || item.id, provider: item.provider || 'system' })),
      ...config.voices,
    ], customVoices: config.voices, selectedVoiceId: config.tts.voiceId };
  }
  async function requireReady(provider) {
    if (!['melo', 'qwen3'].includes(provider)) return;
    const result = await native({ operation: 'tts.models' });
    if (!result.models?.some(model => model.provider === provider && model.ready === true))
      throw new VoiceError('TTS_MODEL_NOT_READY', '请先在语音设置中下载并准备所选朗读资源', 409);
  }
  async function profile(id) {
    if (!id) return (await store.read()).tts;
    try { return await store.findVoice(id); }
    catch (error) {
      if (error.code !== 'VOICE_NOT_FOUND') throw error;
      const list = await voices();
      const matches = list.voices.filter(item => item.id === id || item.voiceId === id || item.name === id);
      if (matches.length !== 1) throw error;
      return matches[0];
    }
  }
  async function stop() {
    const oldGeneration = generation++;
    request?.abort();
    nextPlaybackAt = 0;
    playbackSessions.clear(); playbackUnscoped = false;
    const stopped = (async () => {
      await native({ operation: 'stop_speaking' });
      await native({ operation: 'clear_playback', responseId: `tts:${oldGeneration}` });
      return { stopped: true };
    })();
    chain = Promise.all([chain, stopped]).then(() => {}, () => {});
    return stopped;
  }
  function invalidate({ sessionId } = {}) {
    for (const job of jobs) if (!sessionId || job.sessionId === sessionId) job.revision++;
    // Native TTS may have acknowledged queued text before playback finishes.
    // Keep its source identities until a stop, rather than only tracking fetches.
    if (playbackUnscoped || playbackSessions.size && (!sessionId || playbackSessions.has(sessionId))) return stop();
    return Promise.resolve();
  }
  function speak(input, chosen, permission) {
    if (typeof input.text !== 'string' || !input.text.trim() || input.text.length > 4000)
      throw new VoiceError('INVALID_SPEECH', '朗读文字应为 1 到 4000 字');
    if (input.replyId !== undefined && (typeof input.replyId !== 'string' || input.replyId.length > 256))
      throw new VoiceError('INVALID_SPEECH', '回复身份格式无效');
    if (input.sessionId !== undefined && (typeof input.sessionId !== 'string' || !input.sessionId || input.sessionId.length > 256))
      throw privacyError('TTS_REPLY_SESSION_MISMATCH');
    const job = { sessionId: input.sessionId, revision: 0, public: permission === publicPreview };
    // Caller mutation while a previous chunk is playing cannot replace the
    // source identity, provider or text that was queued for this operation.
    input = { ...input }; chosen = chosen ? { ...chosen } : chosen;
    jobs.add(job);
    const token = generation;
    const operation = chain.then(async () => {
      if (token !== generation) return { cancelled: true };
      let selected, key;
      if (input.replyId && activeReply?.id === input.replyId) {
        if (activeReply.sessionId !== job.sessionId) throw privacyError('TTS_REPLY_SESSION_MISMATCH');
        ({ selected, key } = activeReply);
      }
      else {
        const config = await store.read();
        if (token !== generation) return { cancelled: true };
        if (input.provider !== undefined && !['system', 'melo', 'qwen3', 'openai'].includes(input.provider))
          throw new VoiceError('INVALID_SPEECH', '朗读服务无效');
        const selectedProvider = chosen?.provider ?? input.provider ?? config.tts.provider;
        selected = { ...config.tts, ...chosen,
          provider: selectedProvider,
          url: chosen?.url ?? input.url ?? config.tts.url,
          model: chosen?.model ?? input.model ?? config.tts.model,
          voiceId: chosen?.voiceId ?? input.voiceId ?? (selectedProvider === config.tts.provider
            ? config.tts.voiceId : defaultVoiceId(selectedProvider)),
          rate: chosen?.rate ?? input.rate ?? (selectedProvider === 'qwen3' ? 1 : config.tts.rate) };
        if (selected.provider === 'qwen3' && selected.rate !== 1)
          throw new VoiceError('INVALID_SPEECH', 'Qwen3-TTS 当前仅支持标准语速');
        key = selected.provider === 'openai' ? await store.key('tts') : '';
        if (token !== generation) return { cancelled: true };
        if (input.replyId) activeReply = { id: input.replyId, sessionId: job.sessionId, selected, key };
      }
      if (token !== generation) return { cancelled: true };
      await requireReady(selected.provider);
      if (token !== generation) return { cancelled: true };
      const authorization = await authorize(job, selected.provider);
      if (token !== generation) return { cancelled: true };
      if (authorization !== undefined && authorization !== job.revision) throw privacyError();
      const controller = new AbortController(); request = controller;
      if (['system', 'melo', 'qwen3'].includes(selected.provider)) {
        rememberPlayback(job, selected.provider);
        try { return await native({ operation: 'speak', provider: selected.provider, text: input.text,
          voiceId: selected.voiceId, rate: selected.rate, utteranceId: input.utteranceId,
          ...(input.wakeEventId === undefined ? {} : { wakeEventId: input.wakeEventId }) }, controller.signal); }
        catch (error) { if (token !== generation || controller.signal.aborted) return { cancelled: true }; throw error; }
        finally { if (request === controller) request = undefined; }
      }
      const url = endpoint(selected.url, ['http:', 'https:']);
      if (!/\/audio\/speech\/?$/u.test(url.pathname)) url.pathname = `${url.pathname.replace(/\/$/u, '')}/audio/speech`;
      let response;
      try {
        rememberPlayback(job, selected.provider);
        response = await fetchImpl(url, { method: 'POST', headers: { 'Content-Type': 'application/json',
          ...(key ? { Authorization: `Bearer ${key}` } : {}) },
        body: JSON.stringify({ model: selected.model || 'tts-1', input: input.text,
          voice: selected.voiceId, speed: selected.rate, response_format: 'pcm' }),
        signal: AbortSignal.any([controller.signal, AbortSignal.timeout(60000)]), redirect: 'error' });
        if (!response.ok) {
          await response.body?.cancel();
          throw new VoiceError('TTS_REJECTED', response.status === 401 || response.status === 403
            ? '朗读服务未授权，请检查 API Key' : '朗读服务未接受请求，请检查配置', 502);
        }
        if (!response.body) throw new VoiceError('TTS_EMPTY', '朗读服务没有返回音频', 502);
        let tail = Buffer.alloc(0), total = 0;
        for await (const chunk of response.body) {
          if (token !== generation) return { cancelled: true };
          const bytes = Buffer.concat([tail, Buffer.from(chunk)]), size = bytes.length - bytes.length % 2;
          tail = bytes.subarray(size); total += size;
          if (total > 16 * 1024 * 1024) throw new VoiceError('TTS_TOO_LARGE', '朗读音频过长，请缩短文字', 502);
          for (let offset = 0; offset < size; offset += 24000) {
            if (token !== generation) return { cancelled: true };
            const wait = nextPlaybackAt - performance.now() - 500;
            if (wait > 0) await delay(wait, undefined, { signal: controller.signal });
            if (token !== generation) return { cancelled: true };
            const audio = bytes.subarray(offset, Math.min(size, offset + 24000));
            await native({ operation: 'play', sampleRate: 24000,
              audio: audio.toString('base64'), responseId: `tts:${token}`,
              ...(input.wakeEventId === undefined ? {} : { wakeEventId: input.wakeEventId }) }, controller.signal);
            nextPlaybackAt = Math.max(nextPlaybackAt, performance.now()) + audio.length / 48;
          }
        }
        if (tail.length || !total) throw new VoiceError('TTS_INVALID_PCM', '朗读服务返回的 PCM 音频无效', 502);
        return { queued: true, utteranceId: input.utteranceId };
      } catch (error) {
        if (token !== generation || controller.signal.aborted) return { cancelled: true };
        generation++;
        nextPlaybackAt = 0;
        await native({ operation: 'clear_playback', responseId: `tts:${token}` });
        if (error instanceof VoiceError) throw error;
        throw new VoiceError('TTS_UNREACHABLE', '无法连接朗读服务，请检查地址与网络', 502);
      } finally { if (request === controller) request = undefined; }
    }).finally(() => jobs.delete(job));
    chain = operation.catch(() => {});
    return operation;
  }
  return {
    voices, speak, stop, invalidate, requireReady,
    async select(id) {
      const selected = await profile(id);
      await requireReady(selected.provider);
      return store.save({ tts: { provider: selected.provider, voiceId: selected.voiceId, rate: selected.rate || 1 } });
    },
    async preview(id, draft = {}) {
      if (!id && draft.provider !== undefined && !['system', 'melo', 'qwen3'].includes(draft.provider))
        throw new VoiceError('INVALID_SPEECH', '请先保存自定义朗读服务再试听');
      const stopped = stop(), token = generation;
      await stopped;
      const selected = !id && draft.provider ? { provider: draft.provider, voiceId: draft.voiceId || '', rate: draft.rate ?? 1 } : await profile(id);
      if (token !== generation) return { cancelled: true };
      return speak({ text: getLocale() === 'zh' ? '你好，我是 Droy。我们可以用这个声音聊天。' : 'Hi, I am Droy. We can chat using this voice.', utteranceId: 'preview' }, selected, publicPreview);
    },
  };
}

async function body(req) {
  let size = 0; const chunks = [];
  for await (const chunk of req) {
    size += chunk.length;
    if (size > 3 * 1024 * 1024) throw new VoiceError('TOO_LARGE', '请求内容过大', 413);
    chunks.push(chunk);
  }
  let value;
  try { value = JSON.parse(Buffer.concat(chunks).toString('utf8')); }
  catch { throw new VoiceError('INVALID_JSON', '请求格式无效'); }
  if (!value || typeof value !== 'object' || Array.isArray(value)) throw new VoiceError('INVALID_JSON', '请求格式无效');
  return value;
}
function reply(res, status, value) {
  res.writeHead(status, { 'Content-Type': 'application/json; charset=utf-8', 'Cache-Control': 'no-store' });
  res.end(JSON.stringify(value));
}

export function createVoiceService({ store, native, realtime, fetchImpl, getLocale, outputState }) {
  const speech = createSpeechService({ store, native, fetchImpl, getLocale, outputState });
  let mediaEpoch = 0;
  async function run(input) {
    const operation = input.operation;
    switch (operation) {
      case 'save_config': {
        if (input.patch?.tts) {
          const next = mergeConfig(await store.read(), input.patch);
          await speech.requireReady(next.tts.provider);
        }
        return store.save(input.patch);
      }
      case 'voices': return speech.voices();
      case 'add_voice': return store.addVoice(input.voice);
      case 'remove_voice': return store.removeVoice(input.id);
      case 'set_voice': return speech.select(input.id);
      case 'preview_voice':
        if (realtime.active) throw new VoiceError('VOICE_BUSY', '请先结束实时通话，再试听音色', 409);
        return speech.preview(input.id, input);
      case 'speak':
        if (realtime.active) throw new VoiceError('VOICE_BUSY', '实时通话正在使用模型原声', 409);
        return speech.speak(input);
      case 'stop_speaking': return speech.stop();
      case 'start_realtime':
      case 'poll_realtime':
      case 'interrupt_realtime':
      case 'send_video':
      case 'set_video':
      case 'claim_tool':
      case 'tool_response':
      case 'test_connection':
      case 'set_reference_audio':
      case 'remove_reference_audio':
        throw new VoiceError('FEATURE_UNAVAILABLE', '本版暂不开放实时音视频对话');
      case 'stop_realtime': mediaEpoch++; return realtime.stop();
      case 'wake.configure': return native({ ...input, target: 'standard' });
      case 'mute': return realtime.active ? realtime.mute(input.muted) : native(input);
      case 'stop': {
        mediaEpoch++;
        let result;
        try { await speech.stop(); }
        finally { result = await native(input); }
        return result;
      }
      default:
        if (typeof operation === 'string' && operation.startsWith('wake.enroll_'))
          throw new VoiceError('FEATURE_UNAVAILABLE', '本版暂不开放声纹功能');
        if (operation === 'start') {
          if (input.mode && input.mode !== 'standard')
            throw new VoiceError('FEATURE_UNAVAILABLE', '本版仅支持普通语音对话');
          const epoch = mediaEpoch;
          if (realtime.active) throw new VoiceError('VOICE_BUSY', '已有实时通话正在进行', 409);
          if (!(await store.read()).entries.standard) throw new VoiceError('ENTRY_DISABLED', '请先开启语音对话入口');
          if (epoch !== mediaEpoch) return { state: 'ended', reason: 'cancelled_start' };
        }
        if (nativeOperations.has(operation) || (typeof operation === 'string' && operation.startsWith('wake.')))
          return native(input);
        throw new VoiceError('UNKNOWN_OPERATION', '不支持的语音操作');
    }
  }
  return {
    run, speech, realtime,
    async snapshot() { return { config: await store.publicConfig(), state: { status: 'idle' } }; },
    async dispose() {
      mediaEpoch++;
      const failures = [];
      try { await native({ operation: 'wake.suspend' }); } catch (error) { failures.push(error); }
      const results = await Promise.allSettled([realtime.stop('plugin_disabled'), speech.stop()]);
      for (const result of results) if (result.status === 'rejected') failures.push(result.reason);
      try { await native({ operation: 'dispose' }); } catch (error) { failures.push(error); }
      if (failures.length) throw new AggregateError(failures, '语音资源未能全部释放');
    },
  };
}

export function createVoiceHandler({ authorize, service, reportError = () => {} }) {
  return async (req, res) => {
    if (!authorize(req, res)) return;
    try {
      if (req.method === 'GET') reply(res, 200, { ok: true, result: await service.snapshot() });
      else if (req.method === 'POST') reply(res, 200, { ok: true, result: await service.run(await body(req)) });
      else { res.writeHead(405, { Allow: 'GET, POST' }); res.end(); }
    } catch (error) {
      const expected = error instanceof VoiceError;
      const nativeError = ['PERMISSION_DENIED', 'REJECTED', 'CANCELLED', 'IDENTITY_REQUIRED'].includes(error.code);
      const nativeMessage = nativeError ? error.message.split('\n')[0].replace(`${error.code}: `, '') : '';
      if (!expected) reportError(error.code || 'VOICE_OPERATION_FAILED');
      reply(res, expected ? error.status : nativeError ? 409 : 500, { ok: false, error: {
        code: error.code || 'VOICE_OPERATION_FAILED', message: expected ? error.message : nativeError ? nativeMessage : '语音操作失败，请重试',
      } });
    }
  };
}
