import { randomUUID } from 'node:crypto';
import { setTimeout as delay } from 'node:timers/promises';
import { endpoint, VoiceError, GEMINI_ENDPOINT } from './config.mjs';
import { createResampler, decodeBase64, floatsToBytes, floatsToPcm16, float32ToPcm16, pcm16ToFloat32 } from './pcm.mjs';
import { comniEndpoint, comniPrepare, comniAudio, stopComni } from './comni.mjs';

const sleep = (ms, signal) => new Promise(resolve => {
  const done = () => { clearTimeout(timer); signal?.removeEventListener('abort', done); resolve(); };
  const timer = setTimeout(done, ms); timer.unref?.();
  signal?.addEventListener('abort', done, { once: true });
});
export function realtimePrompts(locale) {
  const zh = /^zh(?:[-_]|$)/i.test(locale ?? '');
  const instruction = zh ? '你是 Droy 的语音伙伴，默认用简洁自然的中文交谈。除非用户明确要求另一种语言或翻译，不要被历史、摘要或工具结果的语言带偏。'
    : 'You are the Droy voice companion. Speak concise, natural English unless the user explicitly requests another language or a translation. History, summaries, and tool results do not change this default.';
  return {
    taskInstruction: instruction + (zh ? '需要操作手机、查阅项目或执行任务时，调用 droy_task，将用户的要求完整交给 Droy。只依据任务返回的实际结果说明是否完成。插话与任务取消是不同操作。'
      : 'For phone actions, project queries, or tasks, call droy_task with the complete user request. Report completion only from actual task results. Interrupting speech and cancelling a task are separate operations.'),
    miniInstruction: instruction + (zh ? '当前实时服务只用于交谈，尚未接入手机工具。需要执行任务时如实说明，不能声称已经操作手机或查询了外部数据。'
      : 'This realtime service supports conversation only and has no phone tools. State this honestly when tasks are requested; never claim to have operated the phone or queried external data.'),
    contextLabel: zh ? '当前 Droy 会话摘要：' : 'Current Droy conversation summary:',
    declaration: {
      name: 'droy_task', description: zh ? '将手机操作、项目查询或其他需要工具的任务交给当前 Droy 会话。'
        : 'Delegate phone actions, project queries, and other tool tasks to the current Droy conversation.',
      parameters: { type: 'OBJECT', properties: { instruction: { type: 'STRING', description: zh ? '用户要求执行的完整任务' : 'The complete task requested by the user' } }, required: ['instruction'] },
      behavior: 'NON_BLOCKING',
    },
  };
}
const blankState = () => ({ status: 'idle', id: null, sessionId: null, provider: null, video: false,
  transcript: '', inputTranscript: '', pendingToolCalls: [], level: 0, muted: false, speaking: false, error: null,
  sentAudioFrames: 0, sentAudioBytes: 0, sentVideoFrames: 0 });

export function realtimeEndpoint(config, video, sessionId = randomUUID()) {
  if (config.provider === 'qwen') throw new VoiceError('REALTIME_UNSUPPORTED', '当前 Qwen3-Omni 接口尚不支持持续双工，请使用已验证的实时服务', 409);
  if (config.provider === 'comni') return comniEndpoint(config.url, video, sessionId).url;
  const url = endpoint(config.url || (config.provider === 'gemini' ? GEMINI_ENDPOINT : '' ));
  if (url.protocol === 'http:') url.protocol = 'ws:';
  if (url.protocol === 'https:') url.protocol = 'wss:';
  if (config.provider === 'minicpm') {
    if (url.pathname === '/' || !url.pathname) url.pathname = '/v1/realtime';
    url.searchParams.set('mode', video ? 'video' : 'audio');
  }
  return url;
}

/** A single background media session; DSH remains the task executor. */
export function createRealtimeManager({ native, connect, pollInterval = 80, getLocale = () => process.env.DROY_LOCALE }) {
  let current, state = blankState(), starting = false, startEpoch = 0;
  const snapshot = () => structuredClone(state);
  const isCurrent = session => current === session && !session.abort.signal.aborted;
  const publish = (session, patch) => { if (isCurrent(session)) state = { ...state, ...patch }; };
  function publicTools(session) {
    return [...session.calls.values()].filter(call => call.status !== 'completed').map(call => ({ ...call }));
  }
  async function stop(reason = 'user_stop', failedMessage) {
    startEpoch++;
    const session = current;
    if (!session) {
      if (starting) await native({ operation: 'stop' });
      return snapshot();
    }
    session.abort.abort();
    clearTimeout(session.handshakeTimer);
    let remoteStop;
    try {
      if (session.socket?.readyState === 1 && session.config.provider === 'minicpm') session.socket.send(JSON.stringify({ type: 'session.close', reason }));
      if (session.config.provider === 'comni') remoteStop = stopComni(session.socket);
      else session.socket?.close();
    } catch { session.socket?.terminate?.(); }
    try { await native({ operation: 'stop' }); }
    catch {
      current = undefined;
      state = { ...state, status: 'failed', pendingToolCalls: [], error: '尚未确认麦克风停止，请使用系统录音通知停止', reason: 'stop_unconfirmed' };
      throw new VoiceError('STOP_UNCONFIRMED', state.error, 502);
    }
    if (remoteStop) state.remoteStopAcknowledged = await remoteStop;
    current = undefined;
    state = { ...state, status: failedMessage ? 'failed' : 'ended', speaking: false, level: 0,
      pendingToolCalls: [], error: failedMessage || null, reason };
    return snapshot();
  }
  function fail(session, message) {
    if (!isCurrent(session)) return;
    void stop('service_error', message).catch(() => {
      state = { ...state, status: 'failed', error: message, speaking: false };
    });
  }
  function send(session, value) {
    if (!isCurrent(session) || session.socket?.readyState !== 1) return false;
    if (session.socket.bufferedAmount > 2 * 1024 * 1024)
      throw new VoiceError('AUDIO_BACKLOG', '实时服务处理不过来，请重新连接', 502);
    session.socket.send(JSON.stringify(value)); return true;
  }
  function clearPlayback(session) {
    const responseId = session.nativeResponseId || '';
    session.outputEpoch++;
    session.nextPlaybackAt = 0;
    publish(session, { speaking: false });
    const cleared = native({ operation: 'clear_playback', responseId });
    session.playback = Promise.all([session.playback, cleared]).then(() => {}, () => {});
    return cleared;
  }
  function play(session, bytes, sampleRate, responseId) {
    const epoch = session.outputEpoch;
    if (session.config.provider === 'minicpm' && session.blockedResponses.has(responseId)) return;
    if (session.queuedOutputBytes + bytes.length > sampleRate * 2 * 12)
      throw new VoiceError('PLAYBACK_BACKLOG', '语音播放积压过多，请重新连接', 502);
    session.queuedOutputBytes += bytes.length;
    session.playback = session.playback.then(async () => {
      for (let offset = 0; offset < bytes.length; offset += 24000) {
        if (!isCurrent(session) || session.outputEpoch !== epoch) return;
        const wait = session.nextPlaybackAt - performance.now() - 500;
        if (wait > 0) await delay(wait, undefined, { signal: session.abort.signal });
        if (!isCurrent(session) || session.outputEpoch !== epoch) return;
        const audio = bytes.subarray(offset, offset + 24000);
        session.nativeResponseId = `${session.id}:${epoch}:${responseId || 'audio'}`;
        await native({ operation: 'play', audio: audio.toString('base64'),
          sampleRate, responseId: session.nativeResponseId }, session.abort.signal);
        session.nextPlaybackAt = Math.max(session.nextPlaybackAt, performance.now()) + audio.length / (sampleRate * 2) * 1000;
      }
    }).catch(() => fail(session, '语音播放失败，请重新连接'))
      .finally(() => { session.queuedOutputBytes -= bytes.length; });
  }
  function ready(session) {
    session.ready = true;
    clearTimeout(session.handshakeTimer);
    publish(session, { status: state.nativeState === 'awaiting_user' ? 'awaiting_user' : 'active', error: null });
  }
  function geminiSetup(session, handle) {
    const config = session.config;
    const { taskInstruction, contextLabel, declaration } = realtimePrompts(session.locale ?? getLocale());
    return { setup: {
      model: config.model.startsWith('models/') ? config.model : `models/${config.model || 'gemini-3.8-live'}`,
      generationConfig: { responseModalities: ['AUDIO'], ...(config.voice ? { speechConfig: {
        voiceConfig: { prebuiltVoiceConfig: { voiceName: config.voice } },
      } } : {}) },
      systemInstruction: { parts: [{ text: `${taskInstruction}${session.context ? `\n${contextLabel}\n${session.context}` : ''}` }] },
      tools: [{ functionDeclarations: [declaration] }],
      inputAudioTranscription: {}, outputAudioTranscription: {},
      contextWindowCompression: { slidingWindow: {} },
      sessionResumption: handle ? { handle } : {},
    } };
  }
  function receiveGemini(session, message) {
    if (message.error) { fail(session, 'Gemini 实时服务未接受请求，请检查模型与配置'); return; }
    if (message.setupComplete) ready(session);
    if (message.sessionResumptionUpdate?.resumable && message.sessionResumptionUpdate.newHandle)
      session.resumeHandle = message.sessionResumptionUpdate.newHandle;
    if (message.goAway) {
      if (session.resumeHandle && !session.resuming) {
        session.resuming = true; session.ready = false;
        publish(session, { status: 'reconnecting' });
        open(session, session.resumeHandle);
      } else fail(session, '实时会话已结束，请重新连接');
      return;
    }
    const content = message.serverContent;
    if (content) {
      if (content.interrupted) void clearPlayback(session).catch(() => fail(session, '无法停止语音播放'));
      if (content.interrupted) session.suppressOutput = false;
      if (content.inputTranscription?.text) publish(session, { inputTranscript: (state.inputTranscript + content.inputTranscription.text).slice(-16000) });
      if (content.outputTranscription?.text) publish(session, { transcript: (state.transcript + content.outputTranscription.text).slice(-16000) });
      for (const part of content.modelTurn?.parts || []) {
        if (part.inlineData?.data && !session.suppressOutput) {
          const mime = part.inlineData.mimeType || 'audio/pcm;rate=24000';
          if (!mime.startsWith('audio/pcm')) throw new VoiceError('UNSUPPORTED_AUDIO', '实时服务返回了不支持的音频格式', 502);
          const sampleRate = Number(/rate=(\d+)/u.exec(mime)?.[1] || 24000);
          const bytes = decodeBase64(part.inlineData.data);
          if (bytes.length % 2) throw new VoiceError('INVALID_PCM', '语音服务返回了无效音频', 502);
          play(session, bytes, sampleRate, 'gemini');
        }
      }
      if (content.turnComplete) session.suppressOutput = false;
    }
    for (const call of message.toolCall?.functionCalls || []) {
      if (typeof call.id !== 'string' || !call.id || session.calls.has(call.id)) continue;
      if (call.name !== 'droy_task' || typeof call.args?.instruction !== 'string'
          || !call.args.instruction.trim() || call.args.instruction.length > 12000) {
        send(session, { toolResponse: { functionResponses: [{ id: call.id, name: call.name,
          response: { status: 'failed', error: '工具请求格式不支持' } }] } });
        continue;
      }
      session.calls.set(call.id, { id: call.id, name: call.name, args: { instruction: call.args.instruction }, status: 'pending' });
    }
    for (const id of message.toolCallCancellation?.ids || []) {
      const call = session.calls.get(id);
      if (call) call.status = 'cancelled';
    }
    publish(session, { pendingToolCalls: publicTools(session) });
  }
  function receiveMini(session, message) {
    if (message.type === 'session.queue_done') {
      send(session, { type: 'session.init', payload: { system_prompt: `${realtimePrompts(session.locale).miniInstruction}${session.context ? `\n${session.context}` : ''}` } });
    } else if (message.type === 'session.created') ready(session);
    else if (message.type === 'session.closed') {
      void stop(message.reason || 'service_closed').catch(() => fail(session, '无法结束通话'));
    } else if (message.type === 'error' || message.error) fail(session, '实时服务未接受请求，请检查服务配置');
    else if (message.type === 'response.output.delta') {
      if (message.kind === 'listen') session.suppressMini = false;
      else if (message.kind === 'text' && typeof message.text === 'string' && !session.suppressMini)
        publish(session, { transcript: (state.transcript + message.text).slice(-16000) });
      else if (message.kind === 'audio' && !session.suppressMini) {
        session.lastResponse = message.response_id || session.lastResponse;
        play(session, float32ToPcm16(decodeBase64(message.audio)), 24000, message.response_id);
      }
    }
  }
  function receiveComni(session, message) {
    if (message.type === 'queue_done' && !session.prepareSent) {
      session.prepareSent = true;
      send(session, session.comniInit);
    } else if (message.type === 'queued' || message.type === 'queue_update') {
      publish(session, { status: 'connecting', queuePosition: message.position });
    } else if (message.type === 'prepared') ready(session);
    else if (message.type === 'stopped' || message.type === 'timeout') {
      void stop(message.type === 'timeout' ? 'service_timeout' : 'service_stopped').catch(() => {});
    } else if (message.type === 'error') fail(session, 'Comni 未接受请求，请检查模型与服务状态');
    else if (message.type === 'result') {
      if (typeof message.is_listen !== 'boolean') throw new VoiceError('INVALID_COMNI_RESULT', 'Comni 返回的双工结果格式无效');
      if (session.suppressComni) {
        if (message.is_listen && session.comniForceSent) session.comniListenSeen = true;
        if (!message.is_listen && session.comniListenSeen) session.suppressComni = false;
      }
      publish(session, { modelListening: message.is_listen, modelTime: message.current_time,
        inferenceMs: message.wall_clock_ms ?? message.cost_all_ms });
      if (!session.suppressComni && !message.is_listen) {
        if (message.text) publish(session, { transcript: (state.transcript + message.text).slice(-16000) });
        if (message.audio_data) play(session, float32ToPcm16(decodeBase64(message.audio_data)), 24000, 'comni');
      }
    } else if (message.type === 'audio_only' && message.audio_data && !session.suppressComni) {
      play(session, float32ToPcm16(decodeBase64(message.audio_data)), 24000, 'comni');
    }
  }
  function open(session, handle) {
    if (!isCurrent(session)) return;
    const url = realtimeEndpoint(session.config, session.videoCapable, session.id);
    const previous = session.socket;
    const socket = connect(url.toString(), session.config.provider === 'gemini'
      ? { 'x-goog-api-key': session.key } : session.key ? { Authorization: `Bearer ${session.key}` } : {});
    session.socket = socket;
    clearTimeout(session.handshakeTimer);
    session.handshakeTimer = setTimeout(() => fail(session, '实时服务连接超时，请重试'), 60000);
    session.handshakeTimer.unref?.();
    socket.on('open', () => {
      if (!isCurrent(session) || session.socket !== socket) { socket.close(); return; }
      if (session.config.provider === 'gemini') send(session, geminiSetup(session, handle));
      if (session.config.provider === 'comni' && !comniEndpoint(session.config.url, session.videoCapable, session.id).gateway) {
        session.prepareSent = true;
        send(session, session.comniInit);
      }
      if (previous) previous.close();
    });
    socket.on('message', data => {
      if (!isCurrent(session) || session.socket !== socket) return;
      try {
        const message = JSON.parse(data.toString());
        if (session.config.provider === 'gemini') receiveGemini(session, message);
        else if (session.config.provider === 'comni') receiveComni(session, message);
        else receiveMini(session, message);
        if (session.ready) session.resuming = false;
      } catch { fail(session, '实时服务返回了无效数据'); }
    });
    socket.on('error', () => { if (session.socket === socket) fail(session, '无法连接实时服务，请检查地址与凭据'); });
    socket.on('close', () => {
      if (isCurrent(session) && session.socket === socket) fail(session, '实时服务连接已断开，请重新连接');
    });
  }
  function sendAudio(session, audio, sampleRate) {
    if (!session.resampler) session.resampler = createResampler(sampleRate, 16000);
    if (session.inputRate && session.inputRate !== sampleRate) throw new VoiceError('AUDIO_FORMAT_CHANGED', '麦克风格式已变化，请重新连接');
    session.inputRate = sampleRate;
    const samples = session.resampler.push(pcm16ToFloat32(decodeBase64(audio)));
    if (session.config.provider === 'gemini') {
      if (!state.muted && samples.length) {
        const bytes = floatsToPcm16(samples);
        if (send(session, { realtimeInput: { audio: { data: bytes.toString('base64'), mimeType: 'audio/pcm;rate=16000' } } }))
          publish(session, { sentAudioFrames: state.sentAudioFrames + 1, sentAudioBytes: state.sentAudioBytes + bytes.length });
      }
      return;
    }
    session.miniAudio = Buffer.concat([session.miniAudio, floatsToBytes(samples)]);
    while (session.miniAudio.length >= 64000) {
      const frame = session.miniAudio.subarray(0, 64000);
      session.miniAudio = session.miniAudio.subarray(64000);
      const input = { audio: (state.muted ? Buffer.alloc(frame.length) : frame).toString('base64'), force_listen: session.forceListen };
      if (session.config.provider === 'comni' && session.forceListen) session.comniForceSent = true;
      session.forceListen = false;
      if (state.video && session.videoFrame) {
        input.video_frames = [session.videoFrame]; input.max_slice_nums = 1; session.videoFrame = null;
      }
      const packet = session.config.provider === 'comni' ? comniAudio(input.audio, {
        forceListen: input.force_listen, videoFrame: input.video_frames?.[0],
      }) : { type: 'input.append', input };
      if (send(session, packet)) publish(session, { sentAudioFrames: state.sentAudioFrames + 1,
        sentAudioBytes: state.sentAudioBytes + frame.length,
        sentVideoFrames: state.sentVideoFrames + (input.video_frames?.length || 0) });
    }
  }
  async function pump(session) {
    while (isCurrent(session)) {
      try {
        const media = await native({ operation: 'poll', cursor: session.cursor, transcriptCursor: 0 }, session.abort.signal);
        if (!isCurrent(session)) break;
        session.cursor = media.nextCursor ?? media.cursor ?? session.cursor;
        publish(session, { nativeState: media.state, captureId: media.captureId || state.captureId,
          level: Number(media.level) || 0, muted: Boolean(media.muted), speaking: Boolean(media.speaking) });
        if (media.state === 'awaiting_user' || media.state === 'starting') {
          publish(session, { status: media.state === 'awaiting_user' ? 'awaiting_user' : 'connecting' });
        } else if (media.state === 'recording' || media.state === 'active') {
          if (!session.socket) open(session);
          if (session.ready) {
            publish(session, { status: 'active' });
            if (media.discontinuity) throw new VoiceError('AUDIO_GAP', '音频输入已中断，请重新连接');
            if (media.audio) sendAudio(session, media.audio, media.sampleRate || 48000);
          }
        } else if (media.state && !['idle', 'connecting'].includes(media.state)) {
          throw new VoiceError('MICROPHONE_STOPPED', media.message || '麦克风采集已结束');
        }
      } catch (error) {
        if (isCurrent(session)) fail(session, error instanceof VoiceError ? error.message : '音频采集已中断，请重新连接');
        break;
      }
      await sleep(pollInterval, session.abort.signal);
    }
  }
  return {
    snapshot, get active() { return Boolean(current) || starting; },
    async start(config, key, { sessionId, video = false, context = '', videoCapable = video } = {}) {
      if (starting || current) throw new VoiceError('VOICE_BUSY', '已有通话正在进行', 409);
      realtimeEndpoint(config, videoCapable);
      if (config.provider === 'gemini' && !key) throw new VoiceError('NOT_CONFIGURED', '请先配置 Gemini API Key');
      if (typeof sessionId !== 'string' || !sessionId || sessionId.length > 256) throw new VoiceError('SESSION_REQUIRED', '请先打开对话');
      if (typeof context !== 'string' || context.length > 12000) throw new VoiceError('INVALID_CONTEXT', '会话摘要过长');
      const locale = getLocale();
      const { miniInstruction } = realtimePrompts(locale);
      const comniInit = config.provider === 'comni'
        ? comniPrepare(`${miniInstruction}${context ? `\n${context}` : ''}`, config.referenceAudio, videoCapable) : undefined;
      starting = true;
      const epoch = ++startEpoch;
      try {
        const media = await native({ operation: 'start', mode: 'realtime', sessionId });
        if (epoch !== startEpoch) {
          await native({ operation: 'stop' });
          return { ...snapshot(), status: 'ended' };
        }
        const session = { id: randomUUID(), locale, config: { ...config }, key, videoCapable, context, comniInit, abort: new AbortController(),
          cursor: 0, ready: false, outputEpoch: 0, playback: Promise.resolve(), calls: new Map(),
          miniAudio: Buffer.alloc(0), blockedResponses: new Set(), forceListen: false, queuedOutputBytes: 0, nextPlaybackAt: 0 };
        current = session;
        state = { ...blankState(), id: session.id, sessionId, provider: config.provider, video,
          captureId: media.captureId, nativeState: media.state, status: media.state === 'awaiting_user' ? 'awaiting_user' : 'connecting' };
        void pump(session);
        return snapshot();
      } finally { starting = false; }
    },
    stop,
    async interrupt() {
      const session = current;
      if (!session) return snapshot();
      if (session.config.provider === 'minicpm') {
        if (session.lastResponse) session.blockedResponses.add(session.lastResponse);
        session.suppressMini = true; session.forceListen = true;
      } else if (session.config.provider === 'comni') {
        session.suppressComni = true; session.forceListen = true;
        session.comniForceSent = false; session.comniListenSeen = false;
      } else if (session.ready) {
        // Keep incoming audio live for Gemini VAD; stop only this spoken output.
        // A local stop button must not fabricate a user message in the history.
        session.suppressOutput = true;
      }
      await clearPlayback(session);
      return snapshot();
    },
    setVideo(enabled) {
      if (!current) throw new VoiceError('NO_CALL', '当前没有实时通话', 409);
      if (enabled && !current.videoCapable) throw new VoiceError('VIDEO_DISABLED', '请先开启实时视频入口');
      state.video = Boolean(enabled);
      if (!enabled) current.videoFrame = null;
      return snapshot();
    },
    video({ data, mimeType = 'image/jpeg' }) {
      const session = current;
      if (!session || !session.ready || !state.video) return { sent: false };
      if (!['image/jpeg', 'image/png'].includes(mimeType)) throw new VoiceError('INVALID_FRAME', '画面格式不支持');
      if (!decodeBase64(data, 1024 * 1024).length) throw new VoiceError('INVALID_FRAME', '画面内容为空');
      const now = Date.now();
      if (session.lastVideoAt && now - session.lastVideoAt < 950) return { sent: false };
      session.lastVideoAt = now;
      if (session.config.provider === 'gemini') {
        const sent = send(session, { realtimeInput: { video: { data, mimeType } } });
        if (sent) publish(session, { sentVideoFrames: state.sentVideoFrames + 1 });
        return { sent };
      }
      else {
        if (mimeType !== 'image/jpeg') throw new VoiceError('INVALID_FRAME', 'MiniCPM 视频需要 JPEG 画面');
        session.videoFrame = data;
      }
      return { sent: false, queued: true };
    },
    claimTool(id) {
      const call = current?.calls.get(id);
      if (!call || call.status !== 'pending') return { claimed: false };
      call.status = 'running'; state.pendingToolCalls = publicTools(current);
      return { claimed: true, call: structuredClone(call) };
    },
    toolResponse(id, result) {
      const session = current, call = session?.calls.get(id);
      if (!call || call.status === 'completed' || call.status === 'cancelled')
        throw new VoiceError('TOOL_CALL_CLOSED', '该任务请求已经结束', 409);
      if (!result || typeof result !== 'object' || JSON.stringify(result).length > 64000)
        throw new VoiceError('INVALID_RESULT', '任务结果格式无效');
      if (!session.ready || !send(session, { toolResponse: { functionResponses: [{ id, name: call.name, response: result }] } }))
        throw new VoiceError('NO_CONNECTION', '实时连接尚未就绪，任务结果未回传', 409);
      call.status = 'completed'; state.pendingToolCalls = publicTools(session);
      return { delivered: true };
    },
    async mute(muted) {
      const result = await native({ operation: 'mute', muted: Boolean(muted) });
      if (current && current.config.provider === 'gemini' && current.ready && muted)
        send(current, { realtimeInput: { audioStreamEnd: true } });
      state.muted = Boolean(muted); return result;
    },
    async testConnection(config, key) {
      const locale = getLocale();
      const { miniInstruction } = realtimePrompts(locale);
      const url = realtimeEndpoint(config, false);
      if (config.provider === 'gemini' && !key) throw new VoiceError('NOT_CONFIGURED', '请先配置 Gemini API Key');
      const comniInit = config.provider === 'comni' ? comniPrepare(miniInstruction, config.referenceAudio) : undefined;
      return new Promise((resolve, reject) => {
        let finished = false;
        const socket = connect(url.toString(), config.provider === 'gemini'
          ? { 'x-goog-api-key': key } : key ? { Authorization: `Bearer ${key}` } : {});
        const done = error => {
          if (finished) return; finished = true; clearTimeout(timer);
          if (socket.readyState === 1 && config.provider === 'minicpm')
            socket.send(JSON.stringify({ type: 'session.close', reason: 'connection_test' }));
          const release = config.provider === 'comni' ? stopComni(socket) : Promise.resolve().then(() => socket.close());
          void release.then(() => {
            if (error) reject(error);
            else resolve({ connected: true, provider: config.provider,
              message: '会话连接成功；音频、视频和手机操作需实际通话验证。' });
          }, () => reject(new VoiceError('CONNECTION_STOP_FAILED', '连接测试未正常结束，请检查服务状态', 502)));
        };
        const timer = setTimeout(() => done(new VoiceError('CONNECTION_TIMEOUT', '服务未及时建立会话，请重试', 502)), 30000);
        timer.unref?.();
        socket.on('open', () => {
          if (config.provider === 'gemini') socket.send(JSON.stringify(geminiSetup({ config, locale, context: '' })));
          else if (config.provider === 'comni' && !comniEndpoint(config.url, false, 'connection-test').gateway)
            socket.send(JSON.stringify(comniInit));
        });
        socket.on('message', data => {
          if (finished) return;
          let message;
          try { message = JSON.parse(data.toString()); }
          catch { done(new VoiceError('INVALID_SERVICE', '服务返回的协议不支持', 502)); return; }
          if (config.provider === 'minicpm' && message.type === 'session.queue_done')
            socket.send(JSON.stringify({ type: 'session.init', payload: { system_prompt: miniInstruction } }));
          if (config.provider === 'comni' && message.type === 'queue_done')
            socket.send(JSON.stringify(comniInit));
          if ((config.provider === 'gemini' && message.setupComplete)
              || (config.provider === 'minicpm' && message.type === 'session.created')
              || (config.provider === 'comni' && message.type === 'prepared')) done();
          else if (message.error || message.type === 'error') done(new VoiceError('PROVIDER_REJECTED', '服务未接受配置，请检查模型和凭据', 502));
        });
        socket.on('error', () => done(new VoiceError('SERVICE_UNREACHABLE', '无法连接实时服务，请检查地址和凭据', 502)));
        socket.on('close', () => { if (!finished) done(new VoiceError('SERVICE_CLOSED', '服务尚未就绪就已断开', 502)); });
      });
    },
  };
}
