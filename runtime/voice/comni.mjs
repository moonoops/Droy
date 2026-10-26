import { endpoint, VoiceError } from './config.mjs';

/** Comni's worker protocol differs from OpenBMB's public Realtime API. */
export function comniEndpoint(value, video, id) {
  const url = endpoint(value);
  if (url.protocol === 'http:') url.protocol = 'ws:';
  if (url.protocol === 'https:') url.protocol = 'wss:';
  const sessionId = `${video ? 'omni' : 'adx'}_droy_${id.replace(/[^a-zA-Z0-9_-]/gu, '').slice(0, 64)}`;
  let gateway = false;
  if (/^\/mobile\/?$/u.test(url.pathname) || /^\/ws\/duplex\/[^/]+\/?$/u.test(url.pathname)) {
    url.pathname = `/ws/duplex/${sessionId}`;
    gateway = true;
  } else if (url.pathname === '/' || /^\/ws\/duplex\/?$/u.test(url.pathname)) {
    url.pathname = '/ws/duplex';
    url.searchParams.set('session_id', sessionId);
  } else throw new VoiceError('INVALID_COMNI_URL', '请填写 Comni 的 /ws/duplex 服务地址');
  return { url, gateway, sessionId };
}

export function comniPrepare(systemPrompt, referenceAudio, video = false) {
  if (!referenceAudio?.data)
    throw new VoiceError('REFERENCE_AUDIO_REQUIRED', '请在实时对话设置中导入 Comni 参考音频');
  const voicePrompt = '请作为一个自然、口语化的语音助手与用户实时对话。你处于音频双工模式，可以一边听一边说。';
  return { type: 'prepare', system_prompt: `${voicePrompt}\n${systemPrompt}`,
    config: { length_penalty: video ? 1.1 : 1.05 },
    ref_audio_base64: referenceAudio.data, tts_ref_audio_base64: referenceAudio.data,
    ...(video ? { max_slice_nums: 1, deferred_finalize: true } : {}) };
}

export function comniAudio(audio, { forceListen = false, videoFrame } = {}) {
  return { type: 'audio_chunk', audio_base64: audio, force_listen: forceListen,
    ...(videoFrame ? { frame_base64_list: [videoFrame], max_slice_nums: 1 } : {}) };
}

/** Wait for the stop receipt when possible; closing alone releases the worker too. */
export function stopComni(socket, timeoutMs = 2500) {
  if (!socket || socket.readyState !== 1) { socket?.close(); return Promise.resolve(false); }
  return new Promise(resolve => {
    let finished = false;
    const done = acknowledged => {
      if (finished) return; finished = true;
      clearTimeout(timer);
      socket.off('message', message); socket.off('close', closed); socket.off('error', closed);
      socket.close(); resolve(acknowledged);
    };
    const message = data => {
      let parsed;
      try { parsed = JSON.parse(data.toString()); } catch { return; }
      if (parsed.type === 'stopped') done(true);
    };
    const closed = () => done(false);
    const timer = setTimeout(() => done(false), timeoutMs); timer.unref?.();
    socket.on('message', message); socket.once('close', closed); socket.once('error', closed);
    socket.send(JSON.stringify({ type: 'stop' }));
  });
}
