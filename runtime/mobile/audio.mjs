import { createHash, randomUUID } from 'node:crypto';
import { mkdir, realpath, rm, stat, writeFile } from 'node:fs/promises';
import { basename, dirname, join, relative, resolve } from 'node:path';
import { setTimeout as delay } from 'node:timers/promises';
import { BridgeError } from './bridge.mjs';
import { resolveMobileLocale } from './locale.mjs';
import { translateBackendText } from './backend-i18n.mjs';

export const AUDIO_PROVIDER_ID = 'droy-sensevoice-local';
const terminal = new Set(['completed', 'failed', 'cancelled', 'interrupted']);
const stateOf = value => value?.state ?? value?.status;
const settled = value => terminal.has(stateOf(value)) && value.finished !== false;

function identity(exec, locale) {
  const message = text => translateBackendText(text, locale);
  if (!exec?.agent?.id || !exec.callId) throw new BridgeError('IDENTITY_REQUIRED', message('音频工具调用缺少引擎身份'));
  return { taskId: String(exec.agent.id), callId: String(exec.callId) };
}

function failure(value, code = 'AUDIO_TRANSCRIPTION_FAILED', locale) {
  const message = typeof value?.error === 'string' ? value.error : value?.error?.message;
  return new BridgeError(code, message || translateBackendText('音频处理失败', locale), { state: stateOf(value) });
}

/** Translate native preparation state into DSH's existing listening-input UI. */
export function speechPreparation(value, locale) {
  const message = text => translateBackendText(text, locale);
  switch (stateOf(value)) {
    case 'ready': return { phase: 'ready' };
    case 'not_prepared': case 'unprepared': return { phase: 'unprepared' };
    case 'cancelled': return { phase: 'cancelled' };
    case 'preparing':
      if (value.stage === 'download') return { phase: 'downloading', step: 'model', resource: value.resource ?? 'SenseVoice',
        completedBytes: value.downloadedBytes, ...(Number.isFinite(value.totalBytes) ? { totalBytes: value.totalBytes } : {}) };
      return { phase: value.stage === 'load' ? 'loading' : 'checking', step: value.stage === 'load' ? 'load' : 'verify',
        startedAt: value.startedAt ?? Date.now() };
    case 'failed': return { phase: 'failed', message: failure(value, undefined, locale).message };
    default: throw new BridgeError('INVALID_AUDIO_STATE', message('本地识别器返回了未知准备状态'));
  }
}

/** Audio jobs stay native-owned, so an ordinary Agent idle does not stop them. */
export function createAudioService({ bridge, attachments, home = process.env.DSH_HOME, pollMs = 250, getLocale = () => process.env.DROY_LOCALE }) {
  const message = text => translateBackendText(text, getLocale());
  const lifetime = new AbortController();
  const pending = new Set();
  const published = new Map();
  const listeners = new Set();
  const turns = new Map(), cleaning = new Map();
  let preparation = { phase: 'unprepared' }, preparing;

  const updatePreparation = value => {
    const next = speechPreparation(value, getLocale());
    if (next.startedAt && next.phase === preparation.phase && next.step === preparation.step && !value.startedAt)
      next.startedAt = preparation.startedAt;
    if (JSON.stringify(next) !== JSON.stringify(preparation)) {
      preparation = next;
      for (const listener of listeners) listener();
    }
    return value;
  };
  const native = (operation, args = {}, signal = lifetime.signal) => bridge.audio({
    taskId: 'droy-input-dictation', callId: randomUUID(), operation, ...args,
  }, signal);
  const refreshPreparation = async (signal = lifetime.signal) => {
    const result = updatePreparation(await native('preparation', {}, signal));
    // Native initialization also starts automatically on installation/upgrade.
    watchPreparation();
    return result;
  };
  const watchPreparation = () => {
    if (preparing) return;
    const operation = (async () => {
      while (['downloading', 'loading', 'checking', 'waking'].includes(preparation.phase)) {
        await delay(500, undefined, { signal: lifetime.signal });
        await refreshPreparation();
      }
    })();
    preparing = operation;
    operation.catch(error => {
      if (!lifetime.signal.aborted) {
        preparation = { phase: 'failed', message: error.message };
        for (const listener of listeners) listener();
      }
    }).finally(() => { if (preparing === operation) preparing = undefined; });
  };

  async function stopJob(transcriptId, owner) {
    let result = await native('stop_transcription', { transcriptId, ...owner }, AbortSignal.timeout(10000));
    while (!settled(result)) {
      await delay(100);
      result = await native('transcript', { transcriptId, statusOnly: true, ...owner }, AbortSignal.timeout(10000));
    }
  }

  function track(turn, kind, id, owner) {
    if (!turn || !id) return;
    if (turn.resources.some(item => item.kind === kind && item.id === id)) return;
    turn.resources.push({ kind, id, owner });
  }

  function cancelTurn(turn) {
    const owners = new Set(turn.resources.map(item => item.owner.taskId));
    const operation = (async () => {
      const results = await Promise.allSettled(turn.resources.map(item => item.kind === 'job'
        ? stopJob(item.id, item.owner)
        : bridge.call('audio_stop', { captureId: item.id }, {
          agent: { id: item.owner.taskId }, callId: `audio-turn-stop-${randomUUID()}`, signal: AbortSignal.timeout(15000),
        })));
      const failures = results.filter(item => item.status === 'rejected').map(item => item.reason);
      if (failures.length) throw new AggregateError(failures, message('本轮音频停止尚未全部确认'));
    })();
    for (const owner of owners) cleaning.set(owner, operation);
    operation.finally(() => { for (const owner of owners) if (cleaning.get(owner) === operation) cleaning.delete(owner); }).catch(() => {});
    return operation;
  }

  function sessionEvent(session, event) {
    if (event.type === 'turn/start') turns.set(String(session.id), { turn: event.data.turn, resources: [] });
    if (event.type === 'step/start') {
      const turn = turns.get(String(session.id));
      if (turn?.turn === event.data.turn) turn.step = event.data.step;
    }
    if (event.type !== 'turn/end') return;
    const turn = turns.get(String(session.id));
    if (!turn || turn.turn !== event.data.turn) return;
    turns.delete(String(session.id));
    if (event.data.reason?.kind === 'aborted' && event.data.reason.reason?.kind === 'user') return cancelTurn(turn);
  }

  async function inputPath(args, signal) {
    let ref = args.audioRef;
    if (!ref && typeof args.filePath === 'string') {
      const path = resolve(args.filePath), digest = basename(dirname(path));
      if (!/^[a-f0-9]{64}$/u.test(digest)) throw new BridgeError('INVALID_AUDIO_FILE', message('请使用上传附件的完整只读路径或音频引用'));
      ref = { attachmentId: `sha256:${digest}`, name: basename(path), bytes: 0 };
      if (attachments.fileHostPath(ref) !== path) throw new BridgeError('INVALID_AUDIO_FILE', message('音频路径不属于当前附件库'));
      ref.bytes = (await stat(path)).size;
    }
    if (!ref) throw new BridgeError('AUDIO_INPUT_REQUIRED', message('请提供 audioRef、上传音频的 filePath 或正在采集的 captureId'));
    const path = attachments.fileHostPath(ref);
    const canonicalRoot = await realpath(attachments.root);
    if (await realpath(path) !== join(canonicalRoot, relative(attachments.root, path)))
      throw new BridgeError('INVALID_AUDIO_FILE', message('音频附件不能通过符号链接读取'));
    // Verification is provided by the shared attachment store, including byte
    // count and digest. Native ASR receives only the verified immutable object.
    for await (const _ of attachments.readFileStream(ref, signal)) { /* integrity check */ }
    return path;
  }

  async function captureResult(value, exec) {
    if (Array.isArray(value.captures)) return { ...value, captures: await Promise.all(value.captures.map(item => captureResult(item, exec))) };
    const { filePath: _nativePath, ...metadata } = value;
    if (!value.fileReady || !value.captureId) return metadata;
    const owner = identity(exec, getLocale());
    const key = `${owner.taskId}:${value.captureId}`;
    let saving = published.get(key);
    if (!saving) {
      saving = (async () => {
        const response = await bridge.audioFile({ captureId: value.captureId, ...owner }, exec.signal);
        return attachments.saveFileStream({ name: `${value.captureId}.wav`, data: response.body, signal: exec.signal });
      })();
      published.set(key, saving);
      saving.catch(() => { if (published.get(key) === saving) published.delete(key); });
    }
    let audioRef;
    try { audioRef = await saving; }
    catch (error) {
      // Stopping remains available while operations are paused; reading audio
      // bytes does not. Keep the native stop receipt and defer only the export.
      if (error instanceof BridgeError && error.details.reason === 'operations_paused')
        return { ...metadata, fileTransferState: 'paused' };
      throw error;
    }
    return { ...metadata, audioRef, filePath: attachments.fileHostPath(audioRef) };
  }

  async function execute(action, args, exec) {
    exec.signal.throwIfAborted();
    const owner = identity(exec, getLocale());
    await cleaning.get(owner.taskId);
    const turn = turns.get(String(exec.agent.session?.id ?? exec.agent.id));
    // Provider tool-call ids may repeat in another model response. Native
    // persistent-job deduplication needs the engine-owned turn/step as well.
    if (turn) owner.callId = createHash('sha256').update(JSON.stringify([turn.turn, turn.step, owner.callId])).digest('hex');
    if (action === 'audio_start' || action === 'audio_status' || action === 'audio_stop') {
      const result = await bridge.call(action, args, { ...exec, callId: owner.callId });
      if (action === 'audio_start') track(turn, 'capture', result.captureId, owner);
      return captureResult(result, exec);
    }
    if (action === 'audio_transcribe') {
      if (args.operation === 'prepare') {
        const result = updatePreparation(await native('prepare', owner, exec.signal));
        watchPreparation();
        return result;
      }
      if (args.operation === 'status') return refreshPreparation(exec.signal);
      if (args.operation !== undefined && args.operation !== 'start')
        throw new BridgeError('AUDIO_OPERATION_UNKNOWN', message('未知转写操作'));
      if (Boolean(args.captureId) === Boolean(args.audioRef || args.filePath))
        throw new BridgeError('AUDIO_INPUT_REQUIRED', message('实时 captureId 与音频文件应选择其一'));
      const input = args.captureId ? { captureId: args.captureId }
        : { inputPath: await inputPath(args, exec.signal) };
      const result = await native('transcribe', { ...input, language: args.language ?? 'auto', ...owner }, exec.signal);
      track(turn, 'job', result.transcriptId, owner);
      return result;
    }
    if (action === 'audio_transcript_read')
      return native('transcript', { transcriptId: args.transcriptId, cursor: args.cursor ?? 0, ...owner }, exec.signal);
    if (action === 'audio_transcription_stop')
      return native('stop_transcription', { transcriptId: args.transcriptId, ...owner }, exec.signal);
    throw new BridgeError('AUDIO_OPERATION_UNKNOWN', message('未知音频工具'));
  }

  async function waitForTranscript(id, owner, signal) {
    for (;;) {
      signal.throwIfAborted();
      const result = await native('transcript', { transcriptId: id, cursor: 0, ...owner }, signal);
      if (settled(result) && stateOf(result) === 'completed') {
        if (typeof result.text !== 'string' || !Number.isFinite(result.audioSeconds) || !Number.isFinite(result.inferenceSeconds))
          throw new BridgeError('INVALID_AUDIO_RESULT', message('本地识别器未返回完整识别结果'));
        return { text: result.text, audioSeconds: result.audioSeconds, inferenceSeconds: result.inferenceSeconds };
      }
      if (settled(result)) throw failure(result, undefined, getLocale());
      await delay(pollMs, undefined, { signal });
    }
  }

  const provider = {
    info: { id: AUDIO_PROVIDER_ID, get name() { return message('SenseVoice 本地识别（Android）'); }, location: 'host-local',
      languages: ['auto', 'zh', 'en', 'yue', 'ja', 'ko'] },
    preparation: {
      snapshot: () => preparation,
      subscribe(listener) { listeners.add(listener); return () => listeners.delete(listener); },
      prepare() {
        if (preparing) return;
        native('prepare').then(result => { updatePreparation(result); watchPreparation(); }).catch(error => {
          if (!lifetime.signal.aborted) {
            preparation = { phase: 'failed', message: error.message };
            for (const listener of listeners) listener();
          }
        });
      },
      async cancel() {
        let result = await native('cancel_preparation');
        while (stateOf(result) === 'preparing') {
          await delay(100, undefined, { signal: lifetime.signal });
          result = await refreshPreparation();
        }
        await preparing;
        updatePreparation(result);
      },
    },
    transcribe(input, caller) {
      const signal = AbortSignal.any([caller, lifetime.signal]);
      const operation = (async () => {
        signal.throwIfAborted();
        if (!home) throw new BridgeError('AUDIO_HOME_REQUIRED', message('缺少 Droy 音频工作目录'));
        if (!(input.audio instanceof Uint8Array) || input.audio.byteLength === 0)
          throw new BridgeError('INVALID_AUDIO_INPUT', message('请提供完整 WAV 录音'));
        const directory = join(home, 'speech-to-text', 'droy-input');
        await mkdir(directory, { recursive: true, mode: 0o700 });
        const path = join(directory, `${randomUUID()}.wav`);
        const owner = { taskId: 'droy-input-dictation', callId: randomUUID() };
        let job, submitted = false, completed = false;
        try {
          await writeFile(path, input.audio, { mode: 0o600, flag: 'wx', signal });
          signal.throwIfAborted();
          submitted = true;
          job = await native('transcribe', { inputPath: path, language: input.language, ...owner }, signal);
          if (typeof job.transcriptId !== 'string') throw new BridgeError('INVALID_AUDIO_RESULT', message('本地识别器未返回任务引用'));
          const result = await waitForTranscript(job.transcriptId, owner, signal);
          completed = true;
          return result;
        } finally {
          // A failed/aborted bridge reply is not proof that native code stopped
          // reading. Native owns cleanup after admission; confirm stop first.
          if (typeof job?.transcriptId === 'string' && !completed) {
            await stopJob(job.transcriptId, owner);
            completed = true;
          }
          if (!submitted || completed) await rm(path, { force: true });
        }
      })();
      pending.add(operation);
      operation.finally(() => pending.delete(operation)).catch(() => {});
      return operation;
    },
  };
  return {
    provider, execute, captureResult, refreshPreparation, sessionEvent,
    settle: owner => cleaning.get(String(owner)),
    snapshot: () => ({ provider: provider.info, preparation }),
    async dispose() {
      lifetime.abort(new Error(message('Droy 音频服务已关闭')));
      await Promise.allSettled([...pending, preparing, ...cleaning.values()].filter(Boolean));
      listeners.clear(); published.clear(); turns.clear();
    },
  };
}

export function installAudioServices(ctx, bridge) {
  const audio = createAudioService({ bridge, attachments: ctx.attachments, getLocale: () => resolveMobileLocale(ctx) });
  ctx.provide('droyAudio', audio);
  ctx.effect(() => () => audio.dispose());
  ctx.on('session/event', (session, event) => audio.sessionEvent(session, event));
  ctx.inject(['speechToText'], scope => {
    scope.effect(() => {
      const unregister = scope.speechToText.register(audio.provider);
      audio.refreshPreparation().catch(error => scope.logger.warn('DROY_AUDIO_PREPARATION_UNAVAILABLE', error.code));
      return unregister;
    });
  });
  return audio;
}
