function createVoiceController(adapter, request, environment) {
  const listeners = new Set(), utterances = new Map(), submitted = new Set(), attempts = new Map(), attemptsByStep = new Map(), voiceRequests = new Map(), voiceTurns = new Map();
  let snapshot = { config: null, status: 'idle', mode: null, sessionId: null, muted: false, level: 0,
    speaking: false, readAloud: false, transcript: '', reply: '', error: null, busy: null,
    settingsOpen: false, wake: null, voices: [], recognizer: null, pendingTranscript: '', holdCancelled: false };
  let disposed = false, handle, composer, draft, stopEvents, stopSelection, timer, wakeTimer, epoch = 0, captureEpoch = 0;
  let cursor = 0, transcriptCursor = 0, captureId, transcriptId, currentTurn, wakeEvent = 0, wakeFlight = false;
  let nativeStart, muteFlight, ending, startAbort, held, speechSequence = 0, output = Promise.resolve(), speechSession = { pending: 0 };
  let userMuted = false, automaticMute = false;
  const active = () => !['idle', 'ended', 'error'].includes(snapshot.status);
  const native = (action, args = {}) => environment.native(action, args);
  function publish(patch) {
    if (disposed) return;
    snapshot = { ...snapshot, ...patch };
    for (const listener of listeners) listener();
  }
  function report(error) { publish({ error: error?.message || String(error) }); }
  async function run(label, operation) {
    publish({ busy: label, error: null });
    try { return await operation(); }
    catch (error) { report(error); return undefined; }
    finally { publish({ busy: null }); }
  }
  function inputStatus(readiness = environment.speech.readiness.getSnapshot()) {
    if (!readiness.connected) return { ready: false, state: 'connecting',
      message: readiness.error ? `语音服务连接失败：${readiness.error}` : '语音服务正在连接，请稍候再试' };
    const provider = readiness.catalog?.providers.find(row => row.id === 'droy-sensevoice-local');
    if (!provider) return { ready: false, state: 'unavailable', message: '手机语音服务未就绪，请重新打开 Droy' };
    const preparation = provider.preparation;
    if (['ready', 'standby', 'waking'].includes(preparation.phase)) return { ready: true, state: 'ready', message: '离线识别已就绪' };
    if (preparation.phase === 'failed') return { ready: false, state: 'failed', message: preparation.message || '语音初始化失败，请重新打开 Droy' };
    if (preparation.phase === 'cancelled') return { ready: false, state: 'cancelled', message: '语音初始化已中断，请重新打开 Droy' };
    return { ready: false, state: 'initializing', message: '语音正在初始化，请稍候再试' };
  }
  function requireInput() {
    const readiness = environment.speech.readiness.getSnapshot(), status = inputStatus(readiness);
    if (!status.ready) throw new Error(status.message);
    return readiness.catalog;
  }
  function cleanTranscript(text) {
    const value = String(text || '').replace(/<\|[^|]*\|>/gu, '').trim();
    return /[\p{L}\p{N}]/u.test(value) ? value : '';
  }
  function isExitPhrase(text) {
    const phrase = cleanTranscript(text).replace(/[\p{P}\p{Z}\s]/gu, '');
    return /^(?:请)?(?:没事了|没事儿了|可以了|闭嘴|停止对话|结束对话|退出语音对话|停止语音对话|结束语音对话|退出语音)(?:谢谢|谢谢你)?$/u.test(phrase);
  }
  function cleanSpeech(text) {
    return text.replace(/```[\s\S]*?(?:```|$)/g, ' ').replace(/!\[[^\]]*\]\([^)]*\)/g, '')
      .replace(/\[([^\]]+)\]\([^)]*\)/g, '$1').replace(/https?:\/\/\S+/g, '')
      .replace(/^[\s#>*-]+/gm, '').replace(/[`*_~]/g, '').replace(/\s+/g, ' ').trim();
  }
  function resetCapture() {
    captureEpoch++; cursor = 0; transcriptCursor = 0; captureId = undefined; transcriptId = undefined;
    utterances.clear();
    if (composer) draft = adapter.captureDraft(composer);
  }
  async function setCaptureMuted(value) {
    const generation = epoch;
    if (value && utterances.size && draft?.owned) adapter.clearDraft(draft);
    resetCapture();
    const operation = Promise.resolve(request('mute', { muted: value, sessionId: snapshot.sessionId }));
    muteFlight = operation;
    try { await operation; }
    finally { if (muteFlight === operation) muteFlight = null; }
    if (generation === epoch) publish({ muted: userMuted, paused: value, level: 0 });
  }
  function say(text, attemptId) {
    const attempt = attempts.get(attemptId), clean = cleanSpeech(text);
    if (!clean || !attempt?.readAloud || snapshot.mode !== 'standard') return;
    const generation = epoch, speech = speechSession;
    speech.pending++;
    output = output.then(async () => {
      try {
        if (disposed || generation !== epoch || attempt.interrupted || snapshot.status !== 'active') return;
        if (!automaticMute) { automaticMute = true; await setCaptureMuted(true); }
        if (disposed || generation !== epoch) return;
        await request('speak', { text: clean, ...attempt.tone, sessionId: attempt.sessionId, replyId: attempt.replyId,
          utteranceId: `${attemptId}-${++speechSequence}` });
      } finally { speech.pending--; }
    }).catch(error => { if (generation === epoch) report(error); });
  }
  function flushAttempt(id, final = false) {
    const attempt = attempts.get(id);
    if (!attempt || attempt.interrupted) return;
    const boundary = /[。！？!?；;\n](?:[”’"']?)/g;
    let cut = 0, match;
    while ((match = boundary.exec(attempt.pending))) cut = match.index + match[0].length;
    if (!cut && attempt.pending.length > 220) cut = Math.max(attempt.pending.lastIndexOf('，', 220), attempt.pending.lastIndexOf(' ', 220));
    if (final) cut = attempt.pending.length;
    if (cut > 0) { say(attempt.pending.slice(0, cut), id); attempt.pending = attempt.pending.slice(cut); }
  }
  function appendText(id, text, turn, sessionId) {
    let attempt = attempts.get(id);
    if (!attempt) {
      let tone = voiceTurns.get(turn);
      if (!tone) { tone = { readAloud: snapshot.readAloud, tts: { ...snapshot.config?.tts }, sessionId }; voiceTurns.set(turn, tone); }
      attempt = { raw: '', pending: '', interrupted: false, deltaBlocks: new Set(),
        readAloud: voiceTurns.has(turn) && tone.readAloud, tone: tone.tts, sessionId: tone.sessionId, replyId: `${tone.sessionId}:${turn}` };
      attempts.set(id, attempt);
    }
    attempt.raw += text; attempt.pending += text;
    publish({ reply: attempt.raw }); flushAttempt(id);
  }
  function onEvent(event, metadata = {}, sessionId) {
    if (event.type === 'turn/start') currentTurn = event.data.turn;
    if (event.type === 'user/message' && voiceRequests.has(event.data.source?.rpcId)) voiceTurns.set(currentTurn, null);
    if (event.type === 'assistant/live-chunk') {
      const { chunk, attemptId, turn } = event.data;
      if (!voiceTurns.has(turn)) return;
      attemptsByStep.set(`${turn}:${event.data.step}`, attemptId);
      if (!attempts.has(attemptId)) appendText(attemptId, '', turn, sessionId);
      const attempt = attempts.get(attemptId);
      if (chunk.type === 'text-delta') { attempt.deltaBlocks.add(chunk.index); appendText(attemptId, chunk.text, turn, sessionId); }
      else if (chunk.type === 'block-end' && chunk.block.type === 'text' && !attempt.deltaBlocks.has(chunk.index)) appendText(attemptId, chunk.block.text, turn, sessionId);
    }
    if (event.type === 'assistant/message' && voiceTurns.has(event.data.turn)) {
      const id = metadata.attemptId || attemptsByStep.get(`${event.data.turn}:${event.data.step}`) || `${event.data.turn}:${event.data.step}`;
      const full = adapter.textOf(event.data.message), attempt = attempts.get(id);
      if (!attempt) appendText(id, full, event.data.turn, sessionId);
      else if (full.startsWith(attempt.raw)) appendText(id, full.slice(attempt.raw.length), event.data.turn, sessionId);
      flushAttempt(id, true);
    }
    if (event.type === 'assistant/attempt') {
      const attempt = attempts.get(metadata.attemptId);
      if (attempt) { attempt.interrupted = true; void request('stop_speaking').catch(report); }
    }
  }
  async function bind(sessionId, generation, signal) {
    const acquired = await adapter.acquire(sessionId);
    if (disposed || generation !== epoch) { acquired.release(); return false; }
    stopEvents?.(); handle?.release(); handle = acquired;
    composer = await adapter.composer(handle, signal);
    if (disposed || generation !== epoch) return false;
    draft = adapter.captureDraft(composer);
    stopEvents = adapter.observe(handle, (event, metadata) => { if (generation === epoch) onEvent(event, metadata, sessionId); });
    return true;
  }
  function releaseBinding() {
    stopEvents?.(); stopEvents = null; handle?.release(); handle = null; composer = null; draft = null;
  }
  async function pauseForDraft(text, retained) {
    userMuted = true;
    await setCaptureMuted(true);
    publish({ pendingTranscript: retained ? '' : text,
      error: retained ? '识别结果已保留在输入框。处理草稿后可开启麦克风继续。' : '草稿已更改，识别结果已保留，可插入输入框。' });
  }
  async function submit(text, sourceDraft = draft) {
    const generation = epoch, submittedHandle = handle, submittedComposer = composer;
    const value = cleanTranscript(text);
    if (!value) return;
    publish({ transcript: value });
    const owned = sourceDraft?.owned && adapter.previewDraft(sourceDraft, value);
    if (!owned) {
      const retained = sourceDraft && adapter.retainDraft(sourceDraft, value);
      if (snapshot.mode === 'standard') await pauseForDraft(value, retained);
      else publish({ pendingTranscript: retained ? '' : value, error: '识别结果已保留，请确认草稿后发送。' });
      return;
    }
    try {
      await adapter.submit(submittedHandle, value, id => voiceRequests.set(id, true));
      if (generation !== epoch || disposed) return;
      adapter.clearDraft(sourceDraft);
      if (submittedComposer) draft = adapter.captureDraft(submittedComposer);
    } catch (error) {
      if (generation !== epoch || disposed) return;
      if (snapshot.mode === 'standard') { userMuted = true; await setCaptureMuted(true); }
      report(error);
    }
  }
  async function poll(generation) {
    const captureGeneration = captureEpoch;
    try {
      const result = await request('poll', { cursor, transcriptCursor, captureId, transcriptId });
      if (disposed || generation !== epoch) return;
      if (captureGeneration !== captureEpoch) { timer = environment.setTimeout(() => poll(generation), 140); return; }
      if (result.transcriptId && transcriptId && transcriptId !== result.transcriptId) { utterances.clear(); draft = adapter.captureDraft(composer); }
      captureId = result.captureId; transcriptId = result.transcriptId;
      cursor = result.nextCursor ?? cursor; transcriptCursor = result.nextTranscriptCursor ?? transcriptCursor;
      const status = result.state === 'recording' ? 'active' : result.state;
      publish({ status, captureId, level: userMuted || automaticMute ? 0 : result.level || 0, levelSequence: (snapshot.levelSequence || 0) + 1,
        muted: userMuted, paused: userMuted || automaticMute, speaking: !!result.speaking,
        ...(result.error ? { error: result.error } : {}) });
      if (result.speaking && !automaticMute) { automaticMute = true; await setCaptureMuted(true); }
      if (automaticMute && !speechSession.pending && !result.speaking) {
        automaticMute = false; await setCaptureMuted(userMuted);
      }
      if (!userMuted && !automaticMute) for (const segment of result.segments || []) {
        const id = `${result.transcriptId || ''}:${segment.utteranceId ?? segment.id}`;
        if (submitted.has(id)) continue;
        let row = utterances.get(id);
        if (!row) { row = new Map(); utterances.set(id, row); }
        row.set(segment.id, segment.text || '');
        const text = cleanTranscript([...row.values()].join(''));
        publish({ transcript: text });
        if (text && draft?.owned && !adapter.previewDraft(draft, text)) draft.owned = false;
        if (segment.endOfUtterance === true || segment.endOfUtterance === undefined && segment.final) {
          submitted.add(id); utterances.delete(id);
          if (isExitPhrase(text)) {
            // End locally before queue admission; normal sentences containing
            // these words remain ordinary requests. Only clear our own preview.
            if (draft?.owned) adapter.clearDraft(draft);
            publish({ transcript: '', pendingTranscript: '' });
            await end(); return;
          }
          await submit(text);
          if (disposed || generation !== epoch || userMuted) break;
        }
      }
      if (result.finished || ['completed', 'failed', 'interrupted'].includes(result.state)) {
        const error = result.error || result.message;
        await end(); if (error) report(error); return;
      }
      if (generation === epoch) timer = environment.setTimeout(() => poll(generation), 140);
    } catch (error) {
      if (generation === epoch) { await end(); report(error); }
    }
  }
  async function claim() { nativeStart = Promise.resolve(request('wake.claim')); try { await nativeStart; } finally { nativeStart = null; } }
  async function start(mode = 'standard', sessionId, options = {}) {
    if (mode !== 'standard') throw new Error('实时音视频暂未开放');
    if (disposed || active()) return;
    try { requireInput(); }
    catch (error) { if (options.wake) await request('wake.release'); throw error; }
    const generation = ++epoch; startAbort = new AbortController();
    releaseBinding();
    userMuted = false; automaticMute = false; speechSession = { pending: 0 }; output = Promise.resolve();
    submitted.clear(); attempts.clear(); attemptsByStep.clear(); voiceRequests.clear(); voiceTurns.clear();
    publish({ mode, sessionId: null, status: 'connecting', error: null, transcript: '', reply: '', readAloud: false, muted: false, paused: false, pendingTranscript: '' });
    try {
      if (!snapshot.config) await refresh();
      if (generation !== epoch || disposed) return;
      const validateWake = async () => {
        if (generation !== epoch || disposed) throw new Error('本次唤醒已取消');
        const wake = await request('wake.status');
        if (generation !== epoch || !wake.screenAvailable || wake.startingEventId !== wakeEvent) throw new Error('本次唤醒已取消，请在亮屏解锁后重新唤醒');
      };
      if (options.wake) await validateWake();
      const chosen = options.wake ? await adapter.createSession(startAbort.signal, validateWake)
        : sessionId || adapter.selectedSession() || await adapter.createSession(startAbort.signal);
      if (generation !== epoch || disposed) return;
      publish({ sessionId: chosen });
      if (!await bind(chosen, generation, startAbort.signal)) return;
      if (options.wake) await validateWake();
      await claim(); if (generation !== epoch || disposed) return;
      if (options.wake) {
        await request('voices');
        if (generation !== epoch || disposed) return;
        // Prepare the input first, then release its capture during the greeting.
      }
      nativeStart = Promise.resolve(request('start', { mode, sessionId: chosen, language: 'auto',
        ...(options.wake ? { wakeEventId: wakeEvent } : {}) }));
      let result; try { result = await nativeStart; } finally { nativeStart = null; }
      if (generation !== epoch || disposed) return;
      publish({ captureId: result.captureId, status: result.state === 'recording' ? 'active' : result.state || 'starting' });
      if (result.state === 'awaiting_user') await native('voiceAuthorize', { captureId: result.captureId });
      if (generation !== epoch || disposed) return;
      if (options.wake) {
        await validateWake();
        await setCaptureMuted(true);
        await request('speak', { text: environment.translate?.('我在') ?? '我在', sessionId: chosen, utteranceId: `wake-${wakeEvent}`, wakeEventId: wakeEvent });
        if (generation !== epoch || disposed) return;
        automaticMute = true;
      }
      resetCapture(); void poll(generation);
    } catch (error) {
      if (generation !== epoch || disposed) return;
      await end(); report(error);
    }
  }
  async function cancelHold() {
    const old = held;
    if (!old) return;
    held = null; old.abort.abort(); environment.clearTimeout(old.timer); environment.clearTimeout(old.meter);
    try { await old.capture?.dispose(); await old.preparing; }
    finally { await request('wake.release'); if (epoch === old.generation) publish({ status: 'ended', mode: null, level: 0 }); }
  }
  async function beginHold(sessionId) {
    if (disposed || active()) return;
    const catalog = requireInput();
    const generation = ++epoch, abort = new AbortController();
    releaseBinding();
    const item = { generation, abort, phase: 'requesting', cancelled: false }; held = item;
    publish({ mode: 'hold', sessionId: null, status: 'starting', error: null, holdCancelled: false, pendingTranscript: '' });
    item.preparing = (async () => {
      const speech = environment.speech;
      const chosen = sessionId || adapter.selectedSession();
      if (chosen) {
        publish({ sessionId: chosen });
        if (!await bind(chosen, generation, abort.signal) || held !== item) return;
        item.draft = adapter.captureDraft(composer);
      }
      await claim(); if (held !== item) return;
      await request('stop_speaking'); if (held !== item) return;
      item.capture = speech.createRecording();
      await item.capture.start(error => { report(error); void cancelHold().catch(report); });
      if (held !== item) { await item.capture.dispose(); return; }
      item.phase = 'recording'; publish({ status: 'recording' });
      item.timer = environment.setTimeout(() => finishHold(), catalog.maxDurationSeconds * 1000);
      const meter = () => {
        if (held !== item || item.phase !== 'recording') return;
        publish({ level: item.capture.amplitude(), levelSequence: (snapshot.levelSequence || 0) + 1 }); item.meter = environment.setTimeout(meter, 80);
      }; meter();
      item.catalog = catalog;
    })();
    try { await item.preparing; }
    catch (error) { if (held === item) { await cancelHold(); report(error); } }
  }
  async function finishHold(cancelled = false) {
    const item = held;
    if (!item) return;
    if (item.phase === 'transcribing') return;
    if (cancelled || item.cancelled || item.phase !== 'recording') { await cancelHold(); return; }
    item.phase = 'transcribing'; environment.clearTimeout(item.timer); environment.clearTimeout(item.meter);
    publish({ status: 'transcribing', level: 0 });
    try {
      const audio = await item.capture.stop(item.catalog.maxDurationSeconds);
      if (held !== item) return;
      if (audio.byteLength > item.catalog.maxAudioBytes) throw new Error('录音超过识别服务大小上限，请缩短后重试');
      const bytes = new Uint8Array(audio); let binary = '';
      for (let offset = 0; offset < bytes.length; offset += 32768) binary += String.fromCharCode(...bytes.subarray(offset, offset + 32768));
      const result = await environment.speech.transcribe({ audioBase64: environment.btoa(binary), providerId: 'droy-sensevoice-local', language: 'auto' }, item.abort.signal);
      if (held !== item) return;
      if (!result.ok) throw new Error(result.error.message);
      const text = cleanTranscript(result.value.text);
      if (!text) throw new Error('没有听清，请再说一次');
      if (!item.draft) {
        const chosen = await adapter.createSession(item.abort.signal);
        if (held !== item) return;
        publish({ sessionId: chosen });
        if (!await bind(chosen, item.generation, item.abort.signal) || held !== item) return;
        item.draft = adapter.captureDraft(composer);
      }
      await submit(text, item.draft);
    } catch (error) { if (held === item) report(error); }
    finally {
      if (held === item) { held = null; await request('wake.release'); publish({ status: 'ended', mode: null, level: 0 }); }
    }
  }
  async function end() {
    if (ending) return ending;
    ++epoch; startAbort?.abort(); environment.clearTimeout(timer);
    const mode = snapshot.mode, pending = nativeStart, pendingMute = muteFlight;
    publish({ status: 'ending' });
    ending = (async () => {
      try {
        await cancelHold(); await Promise.allSettled([pending, pendingMute]);
        if (mode === 'standard') await request('stop');
      } finally {
        try { await request('wake.release'); }
        finally {
          releaseBinding();
          publish({ status: 'ended', mode: null, speaking: false, level: 0, muted: false, paused: false });
        }
      }
    })().finally(() => { ending = null; });
    return ending;
  }
  async function handleWake(detail) {
    if (disposed || active() || !detail.eventId || detail.eventId <= wakeEvent) return;
    const result = await request('wake.consume', { eventId: detail.eventId });
    if (!result.consumed || active()) return;
    wakeEvent = detail.eventId;
    await start('standard', undefined, { wake: true });
  }
  async function refreshWake() {
    const wake = await request('wake.status'); publish({ wake });
    if (wake.eventId > wakeEvent) await handleWake(wake);
    return wake;
  }
  async function wakeLoop() {
    if (disposed) return;
    if (!wakeFlight) {
      wakeFlight = true;
      try {
        if (snapshot.config?.wake?.enabled || snapshot.settingsOpen) await refreshWake();
        if (snapshot.settingsOpen && snapshot.recognizer?.state === 'preparing') await loadRecognizer();
      } catch (error) { report(error); }
      finally { wakeFlight = false; }
    }
    if (!disposed) wakeTimer = environment.setTimeout(wakeLoop, 1000);
  }
  async function refresh() { const result = await environment.get(); publish({ config: result.config }); return result; }
  async function saveConfig(patch) {
    const result = await request('save_config', { patch }); publish({ config: result.config || result }); return result;
  }
  async function loadRecognizer(operation = 'preparation') {
    const recognizer = await native('audioTranscription', { operation }); publish({ recognizer }); return recognizer;
  }
  return {
    getSnapshot: () => snapshot,
    subscribe(listener) { listeners.add(listener); return () => listeners.delete(listener); },
    initialize() {
      void refresh().catch(report); void wakeLoop();
      stopSelection = adapter.observeSelection(session => { if (active() && composer && snapshot.sessionId && session !== snapshot.sessionId) void end().catch(report); });
    },
    refresh, report, request, cleanTranscript, inputStatus,
    handleWake: detail => run('wake', () => handleWake(detail)),
    start: (mode, sessionId) => run('start', () => start(mode, sessionId)),
    beginHold: sessionId => run('hold', () => beginHold(sessionId)),
    moveHold(cancelled) { if (held?.phase === 'recording' || held?.phase === 'requesting') { held.cancelled = cancelled; publish({ holdCancelled: cancelled }); } },
    finishHold: cancelled => held ? run('hold', () => finishHold(cancelled)) : Promise.resolve(),
    cancelHold: () => held ? run('hold', cancelHold) : Promise.resolve(),
    end: () => run('end', end),
    mute: () => run('mute', async () => { userMuted = !userMuted; await setCaptureMuted(userMuted || automaticMute); }),
    toggleReadAloud() { publish({ readAloud: !snapshot.readAloud }); },
    insertPending: () => run('draft', async () => {
      if (!composer || !adapter.insertPending(composer, snapshot.pendingTranscript)) throw new Error('输入框暂时无法插入，请稍后再试');
      publish({ pendingTranscript: '', error: null });
    }),
    saveConfig: patch => run('save', () => saveConfig(patch)),
    prepareRecognizer: () => run('prepare', () => loadRecognizer('prepare')),
    cancelRecognizerPreparation: () => run('prepare', () => loadRecognizer('cancel_preparation')),
    openSettings(navigate = true) { if (navigate) environment.openSettings?.(); publish({ settingsOpen: true }); void refreshWake().catch(report); void loadRecognizer().catch(report); },
    closeSettings() { publish({ settingsOpen: false }); },
    async loadVoices() { const result = await request('voices'); publish({ voices: result.voices || [] }); return result; },
    toggleWake: enabled => run('wake', async () => {
      await request('wake.configure', { ...snapshot.config.wake, enabled, target: 'standard' });
      await saveConfig({ wake: { ...snapshot.config.wake, enabled, target: 'standard' } }); await refreshWake();
    }),
    async dispose() {
      if (disposed) return;
      await end(); disposed = true; stopSelection?.();
      environment.clearTimeout(timer); environment.clearTimeout(wakeTimer); listeners.clear();
    },
  };
}
