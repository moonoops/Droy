import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import vm from 'node:vm';

const runtime = {};
vm.runInNewContext(
  `${await readFile(new URL('../dsh-adapter.js', import.meta.url), 'utf8')}\n${await readFile(new URL('../client-controller.js', import.meta.url), 'utf8')}\nObject.assign(runtime, {createVoiceController, createDshVoiceAdapter});`,
  { runtime, AbortController, Uint8Array, console },
);
const { createVoiceController, createDshVoiceAdapter } = runtime;
const settle = async () => { for (let i = 0; i < 8; i++) await new Promise(resolve => setImmediate(resolve)); };
const deferred = () => { let resolve, reject; const promise = new Promise((yes, no) => { resolve = yes; reject = no; }); return { promise, resolve, reject }; };
const config = () => ({ entries: { standard: true, realtimeVoice: false, realtimeVideo: false }, interruptible: false,
  tts: { provider: 'system', voiceId: 'voice', rate: 1 }, voices: [],
  wake: { enabled: false, chinese: '你好小黑', english: 'Hey Droy', target: 'standard' } });

/** Uses the real adapter against the published InputActions span/revision contract. */
function fixture(options = {}) {
  const calls = [], nativeCalls = [], polls = [], submissions = [], admissions = [], recordings = [], transcriptions = [];
  const timeouts = new Map(), hooks = {}, sessions = new Map(), selectionListeners = new Set(), consumed = new Set();
  let selected = options.selected === undefined ? 'session' : options.selected, sequence = 0, created = 0, configValue = config(), adapter;
  let speaking = false, captureSequence = 0, eventSequence = 0, startingEventId = 0, screenAvailable = true;
  const catalog = { selection: { providerId: 'droy-sensevoice-local', language: 'zh' },
    providers: [{ id: 'droy-sensevoice-local', preparation: { phase: 'ready' } }], maxDurationSeconds: 60, maxAudioBytes: 1024 * 1024 };
  const readiness = { connected: true, catalog };
  function session(id) {
    if (sessions.has(id)) return sessions.get(id);
    const state = { draft: '', draftRev: 0, phase: 'plain', attachmentIds: [], occurrences: [] };
    const listeners = new Set(); let eventSnapshot = { entries: [], change: { kind: 'reset' } };
    const row = { id, state, retained: 0, released: 0, persisted: 0, abandoned: 0, cancelled: 0 };
    const actions = {
      captureInsertion: () => ({ start: state.draft.length, end: state.draft.length, draftRev: state.draftRev }),
      insertText(text, span) {
        if (state.phase !== 'plain' || state.draftRev !== span.draftRev || span.start < 0 || span.end > state.draft.length) return false;
        state.draft = state.draft.slice(0, span.start) + text + state.draft.slice(span.end); state.draftRev++; return true;
      },
      persistDraft() { row.persisted++; },
      setDraft(text) { state.draft = text; state.draftRev++; },
    };
    row.actions = actions;
    row.binding = { sessionId: id, ctx: { sessionId: id }, eventSource: {
      getSnapshot: () => eventSnapshot,
      subscribe(listener) { listeners.add(listener); return () => listeners.delete(listener); },
    }, session: {
      beginSubmission(value) {
        const requestId = `rpc-${admissions.length + 1}`;
        admissions.push({ sessionId: id, requestId, ...value });
        return { requestId, abandon() { row.abandoned++; } };
      },
      async prompt(blocks, mode, reserved, requestId) {
        submissions.push({ sessionId: id, text: blocks[0].text, mode, reserved, requestId, blocks });
        return hooks.prompt ? hooks.prompt({ id, blocks, mode, requestId }) : { ok: true, value: { accepted: true } };
      },
      async cancel() { row.cancelled++; return { ok: true, value: { cancelled: true } }; },
    } };
    row.emit = (event, attemptId) => {
      const value = { ...event, seq: ++eventSequence }, entry = { type: 'event', event: value };
      if (event.type === 'assistant/live-chunk') entry.type = 'transient';
      eventSnapshot = { entries: [...eventSnapshot.entries, entry], change: attemptId
        ? { kind: 'settle-assistant', attemptId, entry } : { kind: 'append', entries: [entry] } };
      for (const listener of listeners) listener();
    };
    sessions.set(id, row); adapter?.attachInput(id, actions); return row;
  }
  const ctx = {
    conversation: { input: { for: ({ sessionId }) => ({ state: { getSnapshot: () => session(sessionId).state } }) } },
    sessions: {
      list: { getSnapshot: () => ({ byId: Object.fromEntries([...sessions.keys()].map(id => [id, { id, retainedBy: { mainView: id === selected ? 1 : 0 } }])) }),
        subscribe(listener) { selectionListeners.add(listener); return () => selectionListeners.delete(listener); } },
      async create() { const id = `new-${++created}`; session(id); calls.push({ operation: 'createSession', sessionId: id }); return id; },
      retain(id) { const row = session(id); row.retained++; return { ready: hooks.retainReady?.(id, row.binding) || Promise.resolve(row.binding), release() { row.released++; } }; },
    },
    workspaces: { list: { getSnapshot: () => ({ items: [{ workspaceId: 'workspace', sessionIds: [...sessions.keys()] }] }) } },
    uiWorkspace: { openSession(id) { selected = id; for (const listener of selectionListeners) listener(); } },
  };
  if (selected) session(selected);
  adapter = createDshVoiceAdapter(ctx);
  for (const row of sessions.values()) adapter.attachInput(row.id, row.actions);
  const request = async (operation, args = {}) => {
    calls.push({ operation, ...args });
    if (hooks[operation]) return hooks[operation](args);
    if (operation === 'start') return { state: 'recording', captureId: `capture-${++captureSequence}` };
    if (operation === 'poll') return { state: 'recording', captureId: `capture-${captureSequence}`, transcriptId: `asr-${captureSequence}`,
      nextCursor: 9600, nextTranscriptCursor: 1, segments: [], speaking, ...polls.shift() };
    if (operation === 'mute') return { muted: args.muted, capturePositionMs: 0 };
    if (operation === 'speak') { speaking = true; return {}; }
    if (operation === 'stop') { speaking = false; return {}; }
    if (operation === 'voices') return { ready: true, voices: [{ id: 'voice', name: '系统声音' }] };
    if (operation === 'wake.status') return { enabled: false, eventId: 0, screenAvailable, startingEventId, preparation: { ready: true, state: 'ready' } };
    if (operation === 'wake.consume') { const accepted = screenAvailable && !consumed.has(args.eventId); consumed.add(args.eventId); if (accepted) startingEventId = args.eventId; return { consumed: accepted }; }
    if (operation === 'wake.release') { startingEventId = 0; return {}; }
    if (operation === 'save_config') { configValue = { ...configValue, ...args.patch }; return { config: configValue }; }
    return {};
  };
  const environment = {
    get: async () => ({ config: configValue, state: options.backendState || { status: 'idle' } }),
    native: async (action, args = {}) => { nativeCalls.push({ action, ...args }); return {}; },
    setTimeout(fn, delay) { const id = ++sequence; timeouts.set(id, { fn, delay }); return id; },
    clearTimeout(id) { timeouts.delete(id); },
    btoa: value => Buffer.from(value, 'binary').toString('base64'),
    speech: {
      readiness: { getSnapshot: () => readiness },
      createRecording() {
        const recording = { started: 0, stopped: 0, disposed: 0, fatal: null,
          async start(fatal) { this.started++; this.fatal = fatal; calls.push({ operation: 'record.start' }); if (hooks.recordStart) await hooks.recordStart(this); },
          async stop(limit) { this.stopped++; calls.push({ operation: 'record.stop', limit });
            return hooks.recordStop ? hooks.recordStop(this) : Uint8Array.from([82, 73, 70, 70, 1, 2, 3, 255]).buffer; },
          async dispose() { this.disposed++; calls.push({ operation: 'record.dispose' }); if (hooks.recordDispose) await hooks.recordDispose(this); },
          amplitude() { return .42; },
        }; recordings.push(recording); return recording;
      },
      async transcribe(args, signal) {
        transcriptions.push({ ...args, signal });
        if (hooks.transcribe) return hooks.transcribe(args, signal);
        return { ok: true, value: { text: '你好尾音。' } };
      },
    },
  };
  const controller = createVoiceController(adapter, request, environment);
  return { controller, adapter, ctx, environment, readiness, catalog, hooks, calls, polls, nativeCalls, sessions, submissions, admissions, recordings, transcriptions,
    session, select: id => ctx.uiWorkspace.openSession(id),
    event: (event, attemptId, id = controller.getSnapshot().sessionId || selected) => session(id).emit(event, attemptId),
    voiceTurn(turn, index = submissions.length - 1) {
      this.event({ type: 'turn/start', data: { turn } });
      this.event({ type: 'user/message', data: { source: { kind: 'user', rpcId: submissions[index].requestId } } });
    },
    async tick(delay = 140) {
      const row = [...timeouts].find(([, value]) => value.delay === delay);
      assert.ok(row, `timer ${delay} exists`); timeouts.delete(row[0]); await row[1].fn(); await settle();
    },
    set speaking(value) { speaking = value; },
    set screenAvailable(value) { screenAvailable = value; if (!value) startingEventId = 0; },
    get created() { return created; },
  };
}
const segment = (text, id = 1, utteranceId = 1, end = true) => ({ id, utteranceId, text, endOfUtterance: end });
const live = (text, turn = 1, attemptId = 'reply', step = 1, type = 'text-delta') => ({ type: 'assistant/live-chunk', data: { turn, step, attemptId, chunk: { type, index: 0, text } } });
const final = (text, turn = 1, step = 1) => ({ type: 'assistant/message', data: { turn, step, message: { content: [{ type: 'text', text }] } } });
const speechCalls = f => f.calls.filter(row => row.operation === 'speak');
const submittedTexts = f => f.submissions.map(row => row.text);

async function start(f, t) { t.after(() => f.controller.dispose()); await f.controller.start(); await settle(); }
async function question(f, text = '你好', id = 1) { f.polls.push({ segments: [segment(text, id, id)] }); await f.tick(); }

test('previews in the real composer and submits one final utterance across decode blocks', async t => {
  const f = fixture(); f.polls.push({ segments: [segment('这是一段很长的', 1, 1, false)] });
  await start(f, t); assert.equal(f.session('session').state.draft, '这是一段很长的'); assert.equal(f.submissions.length, 0);
  f.polls.push({ segments: [segment('语音指令', 2, 1, false), segment('', 3, 1)] }); await f.tick();
  assert.deepEqual(submittedTexts(f), ['这是一段很长的语音指令']); assert.equal(f.session('session').state.draft, '');
  f.polls.push({ segments: [segment('', 3, 1)] }); await f.tick(); assert.equal(f.submissions.length, 1);
});

test('equal measured levels still advance waveform samples without UI actions inventing samples', async t => {
  const f = fixture(); f.polls.push({ level: .04 }); await start(f, t);
  const first = f.controller.getSnapshot().levelSequence;
  f.polls.push({ level: .04 }); await f.tick();
  assert.equal(f.controller.getSnapshot().level, .04);
  assert.equal(f.controller.getSnapshot().levelSequence, first + 1);
  f.controller.toggleReadAloud();
  assert.equal(f.controller.getSnapshot().levelSequence, first + 1);
});

test('filters punctuation and model tags while keeping meaningful one-word replies', async t => {
  const f = fixture(); await start(f, t);
  for (const [index, text] of [' ', '.', '。！？', '<|zh|><|NEUTRAL|><|Speech|>.', '嗯', '好', '一', '0', 'The.'].entries()) await question(f, text, index + 1);
  assert.deepEqual(submittedTexts(f), ['嗯', '好', '一', '0', 'The.']);
});

test('existing text and attachments retain transcription without sending or removing the draft', async t => {
  const f = fixture(), row = f.session('session'); row.actions.setDraft('原草稿'); row.state.attachmentIds = ['fictional-image'];
  await start(f, t); await question(f, '新语音');
  assert.equal(f.submissions.length, 0); assert.equal(row.state.draft, '原草稿新语音'); assert.deepEqual(row.state.attachmentIds, ['fictional-image']);
  assert.equal(f.controller.getSnapshot().muted, true); assert.match(f.controller.getSnapshot().error, /保留/);
});

test('editing after preview preserves the edit and offers explicit insertion of the final result', async t => {
  const f = fixture(); f.polls.push({ segments: [segment('预览', 1, 1, false)] }); await start(f, t);
  f.session('session').actions.setDraft('用户手工修改');
  f.polls.push({ segments: [segment('尾音', 2, 1)] }); await f.tick();
  assert.equal(f.submissions.length, 0); assert.equal(f.session('session').state.draft, '用户手工修改');
  assert.equal(f.controller.getSnapshot().pendingTranscript, '预览尾音'); assert.equal(f.controller.getSnapshot().muted, true);
  await f.controller.insertPending(); assert.equal(f.session('session').state.draft, '用户手工修改预览尾音');
});

test('same-text editor revision change still prevents stale automatic submission', async t => {
  const f = fixture(); await start(f, t); f.session('session').actions.setDraft(''); await question(f, '新问题');
  assert.equal(f.submissions.length, 0); assert.equal(f.controller.getSnapshot().pendingTranscript, '新问题');
});

test('failed submission preserves recognized text, pauses capture, abandons the pending echo and never retries', async t => {
  const f = fixture(); f.hooks.prompt = async () => ({ ok: false, error: { message: '队列拒绝' } }); await start(f, t); await question(f, '失败问题');
  assert.equal(f.session('session').state.draft, '失败问题'); assert.equal(f.controller.getSnapshot().muted, true);
  assert.equal(f.controller.getSnapshot().error, '队列拒绝'); assert.equal(f.session('session').abandoned, 1);
  await f.tick(); assert.equal(f.submissions.length, 1);
});

test('switching the selected conversation ends capture and rejects an outstanding old poll', async t => {
  const f = fixture(), pending = deferred(); f.controller.initialize(); await settle(); await start(f, t);
  f.hooks.poll = () => pending.promise; const ticking = f.tick(); await settle();
  f.select('other'); await settle(); pending.resolve({ state: 'recording', segments: [segment('旧会话音频')] }); await ticking;
  assert.equal(f.submissions.length, 0); assert.equal(f.controller.getSnapshot().status, 'ended'); assert.equal(f.session('session').released, 1);
});

test('manual mute invalidates an in-flight poll and drops old partial words after resume', async t => {
  const f = fixture(), pending = deferred(); await start(f, t);
  f.hooks.poll = () => pending.promise; const ticking = f.tick(); await settle(); await f.controller.mute();
  pending.resolve({ state: 'recording', captureId: 'capture-1', transcriptId: 'asr-1', segments: [segment('静音前迟到音频')] }); await ticking;
  assert.equal(f.submissions.length, 0); assert.equal(f.controller.getSnapshot().muted, true);
  delete f.hooks.poll; await f.controller.mute(); await question(f, '恢复后的新问题', 2);
  assert.deepEqual(submittedTexts(f), ['恢复后的新问题']);
});

test('read aloud defaults off and unrelated model turns never enter TTS', async t => {
  const f = fixture(); await start(f, t); assert.equal(f.controller.getSnapshot().readAloud, false); await question(f); f.voiceTurn(1);
  f.event(live('默认不朗读。')); f.event(final('默认不朗读。'), 'reply'); await settle(); assert.equal(speechCalls(f).length, 0);
  f.controller.toggleReadAloud(); f.event(live('无关任务。', 22, 'other')); f.event(final('无关任务。', 22), 'other'); await settle();
  assert.equal(speechCalls(f).length, 0);
});

test('one reply locks its readout setting and voice; the next reply uses new settings', async t => {
  const f = fixture(); await start(f, t); f.controller.toggleReadAloud(); await question(f); f.voiceTurn(1);
  f.event(live('第一句。')); await settle();
  f.controller.toggleReadAloud(); await f.controller.saveConfig({ tts: { provider: 'system', voiceId: 'new-voice', rate: .85 } });
  f.event(live('第二句。')); f.event(final('第一句。第二句。')); await settle();
  assert.deepEqual(speechCalls(f).map(row => row.text), ['第一句。', '第二句。']);
  assert.deepEqual(speechCalls(f).map(row => row.voiceId), ['voice', 'voice']);
  f.speaking = false; await f.tick(); await question(f, '第二个问题', 2); f.voiceTurn(2);
  f.event(live('第二轮保持关闭。', 2, 'reply-2')); await settle(); assert.equal(speechCalls(f).length, 2);
  f.controller.toggleReadAloud(); f.event(live('此轮中途打开也不朗读。', 2, 'reply-2')); await settle(); assert.equal(speechCalls(f).length, 2);
  await question(f, '第三个问题', 3); f.voiceTurn(3); f.event(live('新回复。', 3, 'reply-3')); await settle();
  assert.equal(speechCalls(f).at(-1).voiceId, 'new-voice'); assert.equal(speechCalls(f).at(-1).text, '新回复。');
});

test('all steps and attempts of one turn keep the initial speaker setting and voice', async t => {
  const f = fixture(); await start(f, t); f.controller.toggleReadAloud(); await question(f); f.voiceTurn(1);
  f.event(live('第一步。', 1, 'attempt-1', 1)); await settle();
  f.controller.toggleReadAloud(); await f.controller.saveConfig({ tts: { provider: 'system', voiceId: 'voice-b', rate: .8 } });
  f.event(live('第二步。', 1, 'attempt-2', 2)); f.event(final('第二步。', 1, 2)); await settle();
  assert.deepEqual(speechCalls(f).map(row => row.text), ['第一步。', '第二步。']);
  assert.deepEqual(speechCalls(f).map(row => row.voiceId), ['voice', 'voice']);
  f.speaking = false; await f.tick(); await question(f, '下一轮', 2); f.voiceTurn(2);
  f.event(live('下一轮保持关闭。', 2, 'attempt-3', 1)); await settle();
  f.controller.toggleReadAloud(); f.event(live('同轮后续也关闭。', 2, 'attempt-4', 2)); await settle();
  assert.equal(speechCalls(f).length, 2);
  await question(f, '第三轮', 3); f.voiceTurn(3); f.event(live('第三轮用新声音。', 3, 'attempt-5', 1)); await settle();
  assert.equal(speechCalls(f).at(-1).voiceId, 'voice-b'); assert.equal(speechCalls(f).at(-1).text, '第三轮用新声音。');
});

test('reads only public reply text and does not repeat streamed text on durable settlement', async t => {
  const f = fixture(); await start(f, t); f.controller.toggleReadAloud(); await question(f); f.voiceTurn(1);
  f.event(live('私有推理', 1, 'reply', 1, 'reasoning-delta'));
  f.event(live('你好。还有一句')); f.event(final('你好。还有一句'), 'reply'); await settle();
  assert.deepEqual(speechCalls(f).map(row => row.text), ['你好。', '还有一句']);
  assert.notEqual(speechCalls(f)[0].utteranceId, speechCalls(f)[1].utteranceId);
  assert.deepEqual(speechCalls(f).map(row => row.sessionId), ['session', 'session']);
  assert.deepEqual(speechCalls(f).map(row => row.replyId), ['session:1', 'session:1']);
});

test('switching chats cancels queued chunks without rebinding the old reply to the new selection', async t => {
  const f = fixture(), speaking = deferred();
  f.controller.initialize(); await settle(); await start(f, t);
  f.hooks.speak = () => speaking.promise;
  f.controller.toggleReadAloud(); await question(f); f.voiceTurn(1);
  f.event(live('原会话第一句。')); await settle();
  f.event(live('原会话排队句。')); await settle();
  assert.equal(speechCalls(f).length, 1);
  f.session('other'); f.select('other'); await settle();
  assert.equal(f.controller.getSnapshot().mode, null);
  speaking.resolve({}); await settle();
  assert.equal(speechCalls(f).length, 1);
  assert.equal(speechCalls(f)[0].sessionId, 'session');
  assert.equal(speechCalls(f)[0].replyId, 'session:1');
  delete f.hooks.speak;
  await f.controller.start(); await settle(); f.controller.toggleReadAloud();
  await question(f, '新会话问题', 2); f.voiceTurn(1);
  f.event(live('新会话回答。', 1, 'new-reply')); await settle();
  assert.equal(speechCalls(f).at(-1).sessionId, 'other');
  assert.equal(speechCalls(f).at(-1).replyId, 'other:1');
});

test('TTS pauses capture; choosing mute during playback keeps it muted after playback', async t => {
  const f = fixture(); await start(f, t); f.controller.toggleReadAloud(); await question(f); f.voiceTurn(1); f.event(live('回答。')); await settle();
  assert.equal(f.calls.filter(row => row.operation === 'mute').at(-1).muted, true);
  assert.equal(f.controller.getSnapshot().paused, true); assert.equal(f.controller.getSnapshot().muted, false);
  await f.controller.mute(); f.speaking = false; await f.tick();
  assert.equal(f.controller.getSnapshot().muted, true); assert.equal(f.calls.filter(row => row.operation === 'mute').at(-1).muted, true);
});

for (const phrase of ['没事了。', '可以了', '闭嘴！', '停止对话', '请结束语音对话，谢谢'])
  test(`spoken exit ends locally without submitting: ${phrase}`, async t => {
    const f = fixture(); await start(f, t); await question(f, phrase);
    assert.equal(f.controller.getSnapshot().mode, null);
    assert.equal(f.session('session').state.draft, '');
    assert.equal(f.submissions.length, 0);
    assert.ok(f.calls.some(row => row.operation === 'stop'));
    assert.ok(f.calls.some(row => row.operation === 'wake.release'));
  });

test('exit phrases inside an ordinary sentence remain messages', async t => {
  const f = fixture(); await start(f, t);
  await question(f, '完成以后就可以了。'); await question(f, '请解释闭嘴这个词。', 2);
  assert.deepEqual(submittedTexts(f), ['完成以后就可以了。', '请解释闭嘴这个词。']);
  assert.equal(f.controller.getSnapshot().mode, 'standard');
});

test('spoken exit preserves an existing user draft and the previous queued task', async t => {
  const f = fixture(); await start(f, t); await question(f, '继续处理刚才的任务');
  f.session('session').actions.setDraft('用户尚未发送的草稿');
  await question(f, '停止对话', 2);
  assert.equal(f.controller.getSnapshot().mode, null);
  assert.equal(f.session('session').state.draft, '用户尚未发送的草稿');
  assert.equal(f.session('session').cancelled, 0); assert.equal(f.submissions.length, 1);
});

test('a split exit phrase waits for the endpoint then removes its preview', async t => {
  const f = fixture(); await start(f, t);
  f.polls.push({ segments: [segment('停止', 1, 1, false)] }); await f.tick();
  assert.equal(f.session('session').state.draft, '停止');
  assert.equal(f.controller.getSnapshot().mode, 'standard');
  f.polls.push({ segments: [segment('对话。', 2, 1, true)] }); await f.tick();
  assert.equal(f.session('session').state.draft, ''); assert.equal(f.controller.getSnapshot().mode, null);
  assert.equal(f.submissions.length, 0);
});

test('ending speech stops voice resources without cancelling the task or clearing its queued input', async t => {
  const f = fixture(); await start(f, t); await question(f); await f.controller.end();
  assert.equal(f.session('session').cancelled, 0); assert.equal(f.submissions.length, 1);
  assert.ok(f.calls.some(row => row.operation === 'stop')); assert.ok(f.calls.some(row => row.operation === 'wake.release'));
  assert.equal(f.controller.getSnapshot().mode, null);
});

test('wake consumes a fresh event, creates a new chat, acknowledges before resuming capture, and does not enable readout', async t => {
  const f = fixture(); t.after(() => f.controller.dispose()); await f.controller.handleWake({ eventId: 5 }); await settle();
  assert.equal(f.created, 1); assert.equal(f.controller.getSnapshot().sessionId, 'new-1'); assert.equal(f.controller.getSnapshot().readAloud, false);
  assert.equal(speechCalls(f)[0].text, '我在'); assert.equal(f.submissions.length, 0);
  assert.equal(speechCalls(f)[0].sessionId, 'new-1');
  const operations = f.calls.map(row => row.operation);
  assert.ok(operations.indexOf('wake.consume') < operations.indexOf('createSession'));
  assert.ok(operations.indexOf('mute') < operations.indexOf('speak'));
  assert.equal(f.calls.find(row => row.operation === 'mute').muted, true);
  f.speaking = false; await f.tick(); assert.equal(f.calls.filter(row => row.operation === 'mute').at(-1).muted, false);
  await f.controller.end(); await f.controller.handleWake({ eventId: 5 }); assert.equal(f.created, 1);
  await f.controller.handleWake({ eventId: 6 }); assert.equal(f.created, 2);
});

test('rejected or concurrent wake events do not create extra conversations', async t => {
  const f = fixture(); t.after(() => f.controller.dispose()); f.hooks['wake.consume'] = async () => ({ consumed: false });
  await f.controller.handleWake({ eventId: 1 }); assert.equal(f.created, 0);
  delete f.hooks['wake.consume']; await f.controller.handleWake({ eventId: 2 }); await f.controller.handleWake({ eventId: 3 });
  assert.equal(f.created, 1);
});

test('locking the screen during a wake bind cancels capture and acknowledgement without replay after unlock', async t => {
  const f = fixture(), binding = deferred(); t.after(() => f.controller.dispose());
  f.hooks.retainReady = id => id === 'new-1' ? binding.promise : undefined;
  const waking = f.controller.handleWake({ eventId: 8 }); await settle(); assert.equal(f.created, 1);
  f.screenAvailable = false; binding.resolve(f.session('new-1').binding); await waking;
  assert.equal(f.calls.filter(row => row.operation === 'start').length, 0); assert.equal(speechCalls(f).length, 0);
  assert.match(f.controller.getSnapshot().error, /唤醒已取消/);
  f.screenAvailable = true; await f.controller.handleWake({ eventId: 8 }); assert.equal(f.created, 1);
  await f.controller.handleWake({ eventId: 9 }); assert.equal(f.created, 2); assert.equal(speechCalls(f).length, 1);
});

test('wake setup never requires enrollment and always configures the standard target', async t => {
  const f = fixture(); t.after(() => f.controller.dispose()); await f.controller.refresh();
  await f.controller.saveConfig({ wake: { ...config().wake, target: 'realtime', timing: 'foreground', useCompanionName: true } });
  f.hooks['wake.status'] = async () => ({ preparation: { ready: false } });
  await f.controller.toggleWake(true);
  const call = f.calls.find(row => row.operation === 'wake.configure');
  assert.equal(call.target, 'standard'); assert.equal(call.timing, 'foreground'); assert.equal(call.useCompanionName, true);
  assert.equal(f.calls.filter(row => row.operation.startsWith('wake.enroll')).length, 0);
});

test('old realtime configuration and stored active state cannot restore hidden audio/video modes', async t => {
  const f = fixture({ backendState: { status: 'active', mode: 'realtime', videoEnabled: true } }); t.after(() => f.controller.dispose());
  await f.controller.saveConfig({ entries: { standard: true, realtimeVoice: true, realtimeVideo: true }, realtime: { provider: 'minicpm', url: 'wss://example.test/realtime' } });
  f.controller.initialize(); await settle();
  await f.controller.start('realtime'); assert.match(f.controller.getSnapshot().error, /暂未开放/);
  assert.equal(f.calls.filter(row => ['start_realtime', 'set_video', 'send_video', 'start'].includes(row.operation)).length, 0);
  assert.equal(f.recordings.length, 0);
});

test('hold release transcribes the complete stopped audio and sends exactly once', async t => {
  const f = fixture(); t.after(() => f.controller.dispose()); await f.controller.beginHold();
  assert.equal(f.controller.getSnapshot().status, 'recording'); assert.equal(f.controller.getSnapshot().level, .42);
  await f.controller.finishHold();
  assert.equal(f.recordings[0].stopped, 1); assert.equal(f.transcriptions.length, 1);
  assert.equal(Buffer.from(f.transcriptions[0].audioBase64, 'base64').at(-1), 255, 'final audio sample reaches ASR');
  assert.equal(f.transcriptions[0].language, 'auto'); assert.deepEqual(submittedTexts(f), ['你好尾音。']);
  assert.equal(f.controller.getSnapshot().status, 'ended');
});

test('upward cancellation disposes recording without ASR or submission', async t => {
  const f = fixture(); t.after(() => f.controller.dispose()); await f.controller.beginHold();
  f.controller.moveHold(true); await f.controller.finishHold();
  assert.equal(f.recordings[0].disposed, 1); assert.equal(f.recordings[0].stopped, 0);
  assert.equal(f.transcriptions.length, 0); assert.equal(f.submissions.length, 0);
});

test('release during microphone permission wait prevents late capture and sends nothing', async t => {
  const f = fixture(), permission = deferred(); t.after(() => f.controller.dispose()); f.hooks.recordStart = () => permission.promise;
  const beginning = f.controller.beginHold(); await settle(); assert.equal(f.controller.getSnapshot().status, 'starting');
  const released = f.controller.finishHold(); await settle(); permission.resolve(); await beginning; await released;
  assert.ok(f.recordings[0].disposed >= 1); assert.equal(f.recordings[0].stopped, 0);
  assert.equal(f.transcriptions.length, 0); assert.equal(f.submissions.length, 0); assert.equal(f.controller.getSnapshot().status, 'ended');
});

test('duplicate hold releases while ASR is pending do not cancel or submit a second recording', async t => {
  const f = fixture(), result = deferred(); t.after(() => f.controller.dispose()); f.hooks.transcribe = () => result.promise;
  await f.controller.beginHold(); const first = f.controller.finishHold(); await settle();
  await f.controller.finishHold(); await f.controller.beginHold();
  result.resolve({ ok: true, value: { text: '一段完整语音' } }); await first;
  assert.equal(f.recordings.length, 1); assert.equal(f.recordings[0].stopped, 1); assert.equal(f.transcriptions.length, 1);
  assert.deepEqual(submittedTexts(f), ['一段完整语音']);
});

test('hold transcript failure is visible and preserves the preexisting composer', async t => {
  const f = fixture(); t.after(() => f.controller.dispose()); f.session('session').actions.setDraft('原稿');
  f.hooks.transcribe = async () => ({ ok: false, error: { message: '识别服务失败' } });
  await f.controller.beginHold(); await f.controller.finishHold();
  assert.equal(f.submissions.length, 0); assert.equal(f.session('session').state.draft, '原稿');
  assert.equal(f.controller.getSnapshot().error, '识别服务失败'); assert.equal(f.controller.getSnapshot().status, 'ended');
});

test('hold submission failure retains recognized text for manual retry', async t => {
  const f = fixture(); t.after(() => f.controller.dispose()); f.hooks.prompt = async () => { throw new Error('发送失败'); };
  await f.controller.beginHold(); await f.controller.finishHold();
  assert.equal(f.session('session').state.draft, '你好尾音。'); assert.equal(f.controller.getSnapshot().error, '发送失败');
  assert.equal(f.session('session').abandoned, 1); assert.equal(f.submissions.length, 1);
});

test('hold limits end the original recording without starting another capture', async t => {
  const f = fixture(); t.after(() => f.controller.dispose()); await f.controller.beginHold(); await f.tick(60000);
  assert.equal(f.recordings.length, 1); assert.equal(f.recordings[0].stopped, 1); assert.equal(f.submissions.length, 1);
});

test('late hold transcription after cancellation cannot replace a later draft or send', async t => {
  const f = fixture(), result = deferred(); t.after(() => f.controller.dispose()); f.hooks.transcribe = () => result.promise;
  await f.controller.beginHold(); const sending = f.controller.finishHold(); await settle(); await f.controller.cancelHold();
  f.session('session').actions.setDraft('后来的草稿'); result.resolve({ ok: true, value: { text: '取消的旧文本' } }); await sending;
  assert.equal(f.submissions.length, 0); assert.equal(f.session('session').state.draft, '后来的草稿');
});

test('hold with an existing attachment retains text for review and never sends the attachment', async t => {
  const f = fixture(); t.after(() => f.controller.dispose()); f.session('session').state.attachmentIds = ['fictional-attachment'];
  await f.controller.beginHold(); await f.controller.finishHold();
  assert.equal(f.submissions.length, 0); assert.equal(f.session('session').state.draft, '你好尾音。');
  assert.deepEqual(f.session('session').state.attachmentIds, ['fictional-attachment']); assert.match(f.controller.getSnapshot().error, /确认草稿/);
});

test('continuous voice and hold cannot acquire the microphone at the same time', async t => {
  const f = fixture(); await start(f, t); await f.controller.beginHold();
  assert.equal(f.recordings.length, 0); assert.equal(f.controller.getSnapshot().mode, 'standard');
  await f.controller.end(); await f.controller.beginHold(); await f.controller.start();
  assert.equal(f.calls.filter(row => row.operation === 'start').length, 1); assert.equal(f.controller.getSnapshot().mode, 'hold');
});

test('both input modes use bundled local recognition despite a persisted remote provider and language', async t => {
  const f = fixture(); t.after(() => f.controller.dispose());
  f.catalog.selection = { providerId: 'fictional-remote-asr', language: 'en' };
  f.catalog.providers.push({ id: 'fictional-remote-asr', preparation: { phase: 'unprepared' } });
  await f.controller.start();
  assert.equal(f.controller.getSnapshot().status, 'active');
  assert.equal(f.calls.find(row => row.operation === 'start').language, 'auto');
  await f.controller.end(); await f.controller.beginHold(); await f.controller.finishHold();
  assert.equal(f.transcriptions.length, 1);
  assert.equal(f.transcriptions[0].providerId, 'droy-sensevoice-local');
  assert.equal(f.transcriptions[0].language, 'auto');
  assert.equal(f.catalog.selection.providerId, 'fictional-remote-asr', 'legacy settings remain stored');
  assert.equal(f.catalog.selection.language, 'en');
});

for (const [name, configure, message, state] of [
  ['connecting', f => { f.readiness.connected = false; }, /正在连接/, 'connecting'],
  ['connection failure', f => { f.readiness.connected = false; f.readiness.error = '连接中断'; }, /连接中断/, 'connecting'],
  ['provider missing', f => { f.catalog.providers = []; }, /手机语音服务未就绪/, 'unavailable'],
  ['first initialization', f => { f.catalog.providers[0].preparation.phase = 'unprepared'; }, /正在初始化/, 'initializing'],
  ['copying bundled models', f => { f.catalog.providers[0].preparation.phase = 'checking'; }, /正在初始化/, 'initializing'],
  ['loading local runtime', f => { f.catalog.providers[0].preparation.phase = 'loading'; }, /正在初始化/, 'initializing'],
  ['initialization failure', f => { f.catalog.providers[0].preparation = { phase: 'failed', message: '本地模型损坏' }; }, /本地模型损坏/, 'failed'],
]) test(`both input modes reject ${name} before recording UI, session creation or microphone acquisition`, async t => {
  const f = fixture({ selected: null }); t.after(() => f.controller.dispose()); configure(f);
  const states = []; f.controller.subscribe(() => states.push(f.controller.getSnapshot()));
  await f.controller.beginHold(); await f.controller.finishHold(); await f.controller.start();
  assert.match(f.controller.getSnapshot().error, message);
  assert.equal(f.controller.inputStatus().state, state);
  assert.equal(f.recordings.length, 0); assert.equal(f.created, 0); assert.equal(f.transcriptions.length, 0);
  assert.equal(f.calls.filter(row => ['start', 'wake.claim', 'stop_speaking'].includes(row.operation)).length, 0);
  assert.ok(states.every(row => row.mode === null && row.status === 'idle'));
  f.readiness.connected = true; delete f.readiness.error;
  f.catalog.providers = [{ id: 'droy-sensevoice-local', preparation: { phase: 'ready' } }];
  await f.controller.beginHold(); assert.equal(f.controller.getSnapshot().status, 'recording');
  await f.controller.finishHold(true);
});

test('releasing an unavailable hold button keeps the immediate readiness feedback visible', async t => {
  const f = fixture(); t.after(() => f.controller.dispose()); f.catalog.providers[0].preparation.phase = 'loading';
  await f.controller.beginHold(); const feedback = f.controller.getSnapshot().error;
  assert.match(feedback, /正在初始化/);
  await f.controller.finishHold(); await f.controller.cancelHold();
  assert.equal(f.controller.getSnapshot().error, feedback);
  assert.equal(f.recordings.length, 0);
});

test('failed wake readiness releases the consumed startup event without creating a conversation', async t => {
  const f = fixture(); t.after(() => f.controller.dispose());
  f.catalog.providers[0].preparation.phase = 'loading';
  await f.controller.handleWake({ eventId: 1 });
  assert.equal(f.created, 0); assert.equal(f.calls.some(row => row.operation === 'start'), false);
  assert.equal(f.calls.filter(row => row.operation === 'wake.release').length, 1);
  assert.match(f.controller.getSnapshot().error, /正在初始化/);
});

test('a delayed rejected submission from an ended call cannot mute or report errors in the next call', async t => {
  const f = fixture(), old = deferred(); await start(f, t); f.hooks.prompt = () => old.promise;
  f.polls.push({ segments: [segment('旧对话待提交')] }); const submitting = f.tick(); await settle();
  assert.equal(f.submissions.length, 1); await f.controller.end();
  await f.controller.start('standard', 'next-session'); await settle();
  old.reject(new Error('旧请求拒绝')); await submitting;
  assert.equal(f.controller.getSnapshot().sessionId, 'next-session'); assert.equal(f.controller.getSnapshot().status, 'active');
  assert.equal(f.controller.getSnapshot().error, null); assert.equal(f.controller.getSnapshot().muted, false);
  assert.equal(f.session('next-session').state.draft, ''); assert.equal(f.calls.filter(row => row.operation === 'mute').length, 0);
});

test('adapter submission uses DSH queue admission with no composer attachments', async () => {
  const f = fixture(), row = f.session('session'); row.state.attachmentIds = ['fictional-attachment'];
  const handle = await f.adapter.acquire('session'); await f.adapter.submit(handle, '语音问题'); handle.release();
  assert.equal(f.admissions[0].mode, 'queue'); assert.equal(f.admissions[0].attachments.length, 0);
  assert.equal(f.submissions[0].mode, 'queue'); assert.equal(f.submissions[0].requestId, f.admissions[0].requestId);
  assert.equal(f.submissions[0].blocks[0].text, '语音问题'); assert.equal(row.released, 1);
});

test('adapter releases a retained session when acquisition fails', async () => {
  const f = fixture(); f.hooks.retainReady = () => Promise.reject(new Error('会话已删除'));
  await assert.rejects(f.adapter.acquire('session'), /会话已删除/); assert.equal(f.session('session').released, 1);
});

test('event observation skips history and correlates live text with durable settlement', () => {
  const f = fixture(), row = f.session('session'), events = [];
  row.emit(final('历史回复')); const stop = f.adapter.observe({ binding: row.binding }, (event, metadata) => events.push({ event, metadata }));
  row.emit(live('新增')); row.emit(final('新增'), 'reply'); stop();
  assert.equal(events.length, 2); assert.equal(events[0].event.type, 'assistant/live-chunk'); assert.equal(events[1].metadata.attemptId, 'reply');
});

test('ending a delayed session bind prevents native start and releases only the stale handle', async t => {
  const f = fixture(), pending = deferred(); t.after(() => f.controller.dispose()); f.hooks.retainReady = id => id === 'first' ? pending.promise : undefined;
  const first = f.controller.start('standard', 'first'); await settle(); await f.controller.end();
  await f.controller.start('standard', 'second'); await settle(); pending.resolve(f.session('first').binding); await first;
  assert.deepEqual(f.calls.filter(row => row.operation === 'start').map(row => row.sessionId), ['second']);
  assert.equal(f.session('first').released, 1); assert.equal(f.session('second').released, 0); assert.equal(f.controller.getSnapshot().sessionId, 'second');
});

test('ending waits for delayed native start and cleans it before another conversation starts', async t => {
  const f = fixture(), pending = deferred(); t.after(() => f.controller.dispose()); let recording = false;
  f.hooks.start = async args => { if (args.sessionId === 'first') await pending.promise; recording = true; return { state: 'recording', captureId: args.sessionId }; };
  f.hooks.stop = async () => { recording = false; return {}; };
  const first = f.controller.start('standard', 'first'); await settle(); const ending = f.controller.end(); await settle();
  assert.equal(f.controller.getSnapshot().status, 'ending'); await f.controller.start('standard', 'too-early');
  pending.resolve(); await first; await ending; assert.equal(recording, false);
  await f.controller.start('standard', 'second'); await settle(); assert.equal(recording, true);
  assert.deepEqual(f.calls.filter(row => row.operation === 'start').map(row => row.sessionId), ['first', 'second']);
});

test('mute removes only the owned unfinished preview and resumes with a clean utterance', async t => {
  const f = fixture(); f.polls.push({ segments: [segment('尚未说完的半句', 1, 1, false)] });
  await start(f, t);
  assert.equal(f.session('session').state.draft, '尚未说完的半句');
  await f.controller.mute();
  assert.equal(f.session('session').state.draft, '');
  await f.controller.mute(); await question(f, '重新开始的问题', 2);
  assert.deepEqual(submittedTexts(f), ['重新开始的问题']);
});

test('voice previews and tool readout pause continuous capture without enabling reply readout', async t => {
  const f = fixture(); await start(f, t);
  f.polls.push({ speaking: true, segments: [segment('不应识别正在试听的声音')] }); await f.tick();
  assert.equal(f.submissions.length, 0);
  assert.equal(f.controller.getSnapshot().readAloud, false);
  assert.equal(f.controller.getSnapshot().paused, true);
  f.speaking = false; await f.tick();
  assert.equal(f.calls.filter(row => row.operation === 'mute').at(-1).muted, false);
});

test('hold to talk stops app playback before acquiring its recording', async t => {
  const f = fixture(); t.after(() => f.controller.dispose());
  await f.controller.beginHold();
  const operations = f.calls.map(row => row.operation);
  assert.ok(operations.indexOf('stop_speaking') < operations.indexOf('record.start'));
  assert.equal(f.controller.getSnapshot().readAloud, false);
  await f.controller.finishHold(true);
});
