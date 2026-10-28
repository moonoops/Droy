import assert from 'node:assert/strict';
import { test } from 'node:test';
import { readFile } from 'node:fs/promises';
import vm from 'node:vm';

const source = await readFile(new URL('../companions/client.js', import.meta.url), 'utf8');
const copy = await readFile(new URL('../companions/copy.js', import.meta.url), 'utf8');
const loaded = vm.runInNewContext(copy + '\n' + source + '\n({createCompanionClientState, createCompanionUi, createCompanionVoiceRecovery, companionConversations, DROY_COMPANION_COPY})', { console, URL, Date, Intl, Map, Set, Object, JSON, Math, Promise, encodeURIComponent });
const deferred = () => { let resolve, reject; const promise = new Promise((yes, no) => { resolve = yes; reject = no; }); return { promise, resolve, reject }; };
const row = (id, day, text = id, extra = {}) => ({ id, role: 'assistant', type: 'text', text, createdAt: `2026-10-${String(day).padStart(2, '0')}T12:00:00.000Z`, status: 'sent', ...extra });
const root = () => ({ companions: [{ id: 'kaka', name: '咔咔', unread: 0 }, { id: 'maimai', name: '麦麦', unread: 0 }], settings: { defaultMode: 'messages' }, models: [], stickers: [] });
function fixture(handler) {
  const calls = [];
  const api = async (path, options = {}) => { calls.push({ path, ...options }); return handler(path, options); };
  const target = { crypto: { randomUUID: () => 'fixture-client-id' } };
  const state = loaded.createCompanionClientState({ target, api });
  return { state, calls, target };
}

 test('drafts, attachments and reply targets stay with their companion across navigation and refresh', async () => {
  const { state } = fixture(path => path === '' ? root() : { messages: [], status: { phase: 'idle' } });
  await state.initialize();
  state.setDraft('kaka', { text: 'Kaka draft', attachments: [{ id: 'kaka-photo' }], replyTo: { id: 'kaka-message', text: 'Original' } });
  state.setDraft('maimai', { text: 'MaiMai draft' });
  state.open('kaka'); await state.refreshMessages('kaka');
  state.open('maimai'); await state.refreshMessages('maimai');
  await state.refreshRoot(); state.back(); state.open('kaka');
  assert.equal(state.draft('kaka').text, 'Kaka draft');
  assert.equal(state.draft('kaka').attachments[0].id, 'kaka-photo');
  assert.equal(state.draft('kaka').replyTo.id, 'kaka-message');
  assert.equal(state.draft('maimai').text, 'MaiMai draft');
  assert.equal(state.draft('maimai').attachments.length, 0);
  state.dispose();
});

 test('a delayed send cannot erase newer input and canonical acknowledgement replaces the optimistic message', async () => {
  const pending = deferred(); const messages = [];
  const { state } = fixture((path, options) => {
    if (path === '') return root();
    if (options.method === 'POST' && path === '/kaka/messages') return pending.promise;
    if (path === '/kaka/messages') return { messages: [...messages], status: { phase: 'thinking' } };
    return {};
  });
  await state.initialize();
  state.setDraft('kaka', { text: 'First message' });
  const sending = state.send('kaka');
  assert.equal(state.draft('kaka').text, '');
  assert.equal(state.getSnapshot().messages.kaka[0].status, 'pending');
  state.setDraft('kaka', { text: 'The next message' });
  const saved = row('saved-id', 28, 'First message', { role: 'user', clientId: 'fixture-client-id' });
  messages.push(saved); pending.resolve({ message: saved }); await sending;
  assert.equal(state.draft('kaka').text, 'The next message');
  assert.equal(state.getSnapshot().messages.kaka.length, 1);
  assert.equal(state.getSnapshot().messages.kaka[0].id, 'saved-id');
  state.dispose();
});

 test('sending a sticker or voice does not consume an existing text draft', async () => {
  const { state } = fixture((path, options) => path === '' ? root() : options.method === 'POST'
    ? { message: { ...options.body, id: options.body.clientId, role: 'user', type: 'sticker', createdAt: '2026-10-28T12:00:00.000Z', status: 'sent' } }
    : { messages: [] });
  await state.initialize(); state.setDraft('kaka', { text: 'Still editing', attachments: [{ id: 'not-sent' }] });
  await state.send('kaka', { stickerId: 'test-ok', text: '', attachments: [] });
  assert.equal(state.draft('kaka').text, 'Still editing');
  assert.equal(state.draft('kaka').attachments.length, 1);
  state.dispose();
});

 test('latest updates preserve loaded history and earlier pagination does not overwrite newer message text', async () => {
  let latest = [row('middle', 20), row('latest', 21)];
  const { state } = fixture(path => path === '' ? root() : path.includes('?before=')
    ? { messages: [row('old', 19), row('middle', 20, 'stale overlap')], hasMore: false, status: { phase: 'idle' } }
    : { messages: latest, hasMore: true, status: { phase: 'typing' } });
  await state.initialize(); await state.refreshMessages('kaka');
  assert.equal(state.getSnapshot().hasMore.kaka, true);
  await state.refreshMessages('kaka', { before: 'middle' });
  assert.equal(state.getSnapshot().messages.kaka.map(message => message.id).join(), 'old,middle,latest');
  assert.equal(state.getSnapshot().messages.kaka.find(message => message.id === 'middle').text, 'middle');
  assert.equal(state.getSnapshot().hasMore.kaka, false);
  latest = [row('latest', 21, 'finished latest'), row('new', 22)];
  await state.refreshMessages('kaka');
  assert.equal(state.getSnapshot().messages.kaka.map(message => message.id).join(), 'old,middle,latest,new');
  assert.equal(state.getSnapshot().messages.kaka.find(message => message.id === 'latest').text, 'finished latest');
  assert.equal(state.getSnapshot().hasMore.kaka, false);
  state.dispose();
});

 test('a stale response from another companion never selects or displays that companion', async () => {
  const kaka = deferred();
  const { state } = fixture(path => path === '' ? root() : path === '/kaka/messages' ? kaka.promise
    : path === '/maimai/messages' ? { messages: [row('maimai-only', 21)], status: { phase: 'idle' } } : {});
  await state.initialize(); state.open('kaka'); state.open('maimai');
  await state.refreshMessages('maimai');
  kaka.resolve({ messages: [row('kaka-only', 21)], status: { phase: 'idle' } });
  await new Promise(resolve => setImmediate(resolve));
  assert.equal(state.getSnapshot().companionId, 'maimai');
  assert.equal(state.getSnapshot().messages.maimai[0].id, 'maimai-only');
  assert.equal(state.getSnapshot().messages.kaka[0].id, 'kaka-only');
  state.dispose();
});

 test('failed sends keep one retryable identity and do not alter another companion', async () => {
  let failed = true;
  const { state, calls } = fixture((path, options) => {
    if (path === '') return root();
    if (options.method === 'POST' && path === '/kaka/messages') {
      if (failed) throw new Error('OFFLINE');
      return { message: row(options.body.clientId, 28, options.body.text, { role: 'user' }) };
    }
    return { messages: [], status: { phase: 'idle' } };
  });
  await state.initialize(); state.setDraft('maimai', { text: 'Other draft' });
  await assert.rejects(state.send('kaka', { text: 'Retry this' }), /OFFLINE/);
  const original = state.getSnapshot().messages.kaka[0];
  assert.equal(original.status, 'failed'); failed = false;
  await state.send('kaka', { ...original, clientId: original.clientId });
  assert.equal(calls.filter(call => call.method === 'POST' && call.path === '/kaka/messages').map(call => call.body.clientId).join(), 'fixture-client-id,fixture-client-id');
  assert.equal(state.draft('maimai').text, 'Other draft');
  state.dispose();
});

 test('profile and startup setting writes use their separate product endpoints', async () => {
  const { state, calls } = fixture((path, options) => path === '' ? root() : path === '/settings' ? { settings: options.body } : {});
  await state.initialize();
  await state.updateProfile('kaka', { model: { provider: 'fixture-provider', model: 'fixture-model' } });
  await state.updateSettings({ defaultMode: 'work' });
  assert.equal(calls.find(call => call.path === '/kaka').method, 'PATCH');
  assert.equal(state.getSnapshot().settings.defaultMode, 'work');
  assert.equal(calls.some(call => /session|default-model/.test(call.path)), false);
  state.dispose();
});

 test('the paired IM dictionaries include matching placeholders and all static message keys', () => {
  const dictionaries = loaded.DROY_COMPANION_COPY;
  assert.deepEqual(Object.keys(dictionaries.zh).sort(), Object.keys(dictionaries.en).sort());
  const placeholders = value => [...value.matchAll(/\{(\w+)\}/g)].map(match => match[1]).sort().join();
  for (const key of Object.keys(dictionaries.zh)) assert.equal(placeholders(dictionaries.zh[key]), placeholders(dictionaries.en[key]), key);
  for (const [, key] of source.matchAll(/\bt\('([^']+)'/g)) assert.ok(dictionaries.zh[key] && dictionaries.en[key], key);
});

 test('background delivery does not mark an unseen message read', async () => {
  const { state, target, calls } = fixture(path => path === '' ? root() : { messages: [row('unseen', 28)], status: { phase: 'idle' } });
  target.document = { visibilityState: 'hidden' };
  await state.initialize(); state.open('kaka'); await state.refreshMessages('kaka');
  assert.equal(calls.some(call => call.path === '/kaka/read'), false);
  target.document.visibilityState = 'visible';
  await state.refreshMessages('kaka');
  assert.equal(calls.filter(call => call.path === '/kaka/read').length, 0, 'Loading a mounted or cached chat does not establish visibility');
  state.markRead('kaka', 'unseen');
  assert.equal(calls.filter(call => call.path === '/kaka/read').length, 1);
  await state.refreshMessages('kaka');
  assert.equal(calls.filter(call => call.path === '/kaka/read').length, 1);
  state.dispose();
});

 test('an empty canonical history clears previously loaded messages', async () => {
  let rows = [row('gone', 28)];
  const { state } = fixture(path => path === '' ? root() : { messages: rows, status: { phase: 'idle' } });
  await state.initialize(); await state.refreshMessages('kaka');
  rows = [];
  await state.refreshMessages('kaka');
  assert.equal(state.getSnapshot().messages.kaka.length, 0);
  state.dispose();
});

 test('SSE invalidation refreshes the correct cached chat and disposal closes the stream', async () => {
  let stream, rows = [row('first', 27)];
  const { state, target } = fixture(path => path === '' ? root() : { messages: rows, status: { phase: 'typing' } });
  target.EventSource = class {
    constructor() { stream = this; this.listeners = new Map(); this.closed = false; }
    addEventListener(name, callback) { this.listeners.set(name, callback); }
    close() { this.closed = true; }
  };
  await state.initialize(); state.open('kaka'); await state.refreshMessages('kaka');
  state.setDraft('kaka', { text: 'Editing while a reply arrives' });
  rows = [row('first', 27), row('second', 28)];
  stream.listeners.get('invalidate')({ data: JSON.stringify({ companionId: 'kaka' }) });
  await new Promise(resolve => setImmediate(resolve));
  assert.equal(state.getSnapshot().messages.kaka.map(message => message.id).join(), 'first,second');
  assert.equal(state.draft('kaka').text, 'Editing while a reply arrives');
  assert.equal(state.getSnapshot().companionId, 'kaka');
  state.dispose(); assert.equal(stream.closed, true);
});

const retainedAudio = () => ({ attachmentId: 'fixture-recording', url: '/droy/companions/kaka/attachments/fixture-recording', durationMs: 2400 });
const recognitionFailure = (asText = false) => Object.assign(new Error('ASR unavailable'), { retainedVoice: retainedAudio(), asText });

 test('ASR failure retains the original recording per companion without clearing the current draft', async () => {
  const { state, calls } = fixture(path => path === '' ? root() : { messages: [] });
  await state.initialize();
  const recovery = loaded.createCompanionVoiceRecovery({ state, recording: {} });
  state.setDraft('kaka', { text: 'Keep this typed draft', attachments: [{ id: 'keep-photo' }] });
  recovery.retain('kaka', recognitionFailure(true));
  state.open('maimai'); state.open('kaka');
  assert.equal(state.draft('kaka').text, 'Keep this typed draft');
  assert.equal(state.draft('kaka').attachments[0].id, 'keep-photo');
  assert.equal(recovery.pending('kaka').voice.attachmentId, 'fixture-recording');
  assert.equal(recovery.pending('kaka').asText, true);
  assert.equal(recovery.pending('maimai'), null);
  assert.equal(calls.some(call => call.method === 'POST' && call.path.endsWith('/messages')), false);
  state.dispose();
});

 test('retrying text conversion adds editable text to newer input without sending or discarding audio', async () => {
  const pending = deferred(), calls = [];
  const { state } = fixture(path => path === '' ? root() : { messages: [] });
  const recovery = loaded.createCompanionVoiceRecovery({ state, recording: {
    retry: input => { calls.push(input); return pending.promise; }, discard: () => { throw new Error('Should not discard'); },
  } });
  recovery.retain('kaka', recognitionFailure(true));
  const retry = recovery.retry('kaka');
  state.setDraft('kaka', { text: 'Newer typed text' });
  pending.resolve({ text: 'Recognized words' }); await retry;
  assert.equal(calls.length, 1); assert.equal(calls[0].companionId, 'kaka'); assert.equal(calls[0].asText, true);
  assert.equal(state.draft('kaka').text, 'Newer typed text\nRecognized words');
  assert.equal(recovery.pending('kaka'), null);
  assert.equal(state.getSnapshot().messages.kaka, undefined);
  state.dispose();
});

 test('successful voice retry sends its transcript with the original recording and leaves other draft content intact', async () => {
  const rows = [];
  const { state, calls } = fixture((path, options) => {
    if (path === '') return root();
    if (options.method === 'POST' && path === '/kaka/messages') {
      const saved = row(options.body.clientId, 28, options.body.text, { role: 'user', type: 'voice', voice: options.body.voice });
      rows.push(saved); return saved;
    }
    return { messages: rows };
  });
  const recovery = loaded.createCompanionVoiceRecovery({ state, recording: { retry: async () => ({ text: 'Actual transcription' }) } });
  state.setDraft('kaka', { text: 'Still editing', attachments: [{ id: 'not-part-of-voice' }] });
  recovery.retain('kaka', recognitionFailure());
  await recovery.retry('kaka');
  const posted = calls.find(call => call.method === 'POST' && call.path === '/kaka/messages').body;
  assert.equal(posted.text, 'Actual transcription');
  assert.equal(posted.voice.attachmentId, 'fixture-recording');
  assert.notEqual(posted.voice.transcriptionState, 'failed');
  assert.equal(posted.attachments.length, 0);
  assert.equal(state.draft('kaka').text, 'Still editing');
  assert.equal(state.draft('kaka').attachments.length, 1);
  assert.equal(recovery.pending('kaka'), null);
  state.dispose();
});

 test('repeated ASR failure or empty output keeps the recording and never creates a fake transcription', async () => {
  let empty = false;
  const { state, calls } = fixture(path => path === '' ? root() : { messages: [] });
  const recovery = loaded.createCompanionVoiceRecovery({ state, recording: { retry: async () => {
    if (empty) return { text: '   ' }; throw recognitionFailure();
  } } });
  recovery.retain('kaka', recognitionFailure());
  await assert.rejects(recovery.retry('kaka'), /ASR unavailable/);
  empty = true; await assert.rejects(recovery.retry('kaka'), /COMPANION_TRANSCRIPTION_EMPTY/);
  assert.equal(recovery.pending('kaka').voice.url, retainedAudio().url);
  assert.equal(state.draft('kaka').text, '');
  assert.equal(calls.some(call => call.method === 'POST'), false);
  state.dispose();
});

 test('sending original audio explicitly marks transcription failed and rapid repeats use one send identity', async () => {
  const accepted = deferred();
  const { state, calls } = fixture((path, options) => path === '' ? root() : options.method === 'POST' && path === '/kaka/messages'
    ? accepted.promise : { messages: [] });
  const recovery = loaded.createCompanionVoiceRecovery({ state, recording: {} });
  recovery.retain('kaka', recognitionFailure(true));
  const first = recovery.sendOriginal('kaka'), repeated = recovery.sendOriginal('kaka');
  assert.equal(first, repeated);
  await Promise.resolve();
  const posted = calls.find(call => call.method === 'POST' && call.path === '/kaka/messages').body;
  assert.equal(posted.text, ''); assert.equal(posted.attachments.length, 0);
  assert.equal(posted.voice.transcriptionState, 'failed');
  assert.equal(posted.clientId, 'voice:fixture-recording');
  accepted.resolve(row(posted.clientId, 28, '', { role: 'user', type: 'voice', voice: posted.voice }));
  await first;
  assert.equal(recovery.pending('kaka'), null);
  assert.equal(calls.filter(call => call.method === 'POST' && call.path === '/kaka/messages').length, 1);
  state.dispose();
});

 test('discard clears the pending recording only after the explicit cleanup succeeds', async () => {
  let failed = true; const discards = [];
  const { state } = fixture(path => path === '' ? root() : { messages: [] });
  const recovery = loaded.createCompanionVoiceRecovery({ state, recording: { discard: async input => {
    discards.push(input); if (failed) throw new Error('Cleanup failed');
  } } });
  recovery.retain('maimai', recognitionFailure());
  state.setDraft('maimai', { text: 'Leave this alone' });
  await assert.rejects(recovery.discard('maimai'), /Cleanup failed/);
  assert.ok(recovery.pending('maimai'));
  failed = false; await recovery.discard('maimai');
  assert.equal(discards[1].companionId, 'maimai');
  assert.equal(recovery.pending('maimai'), null);
  assert.equal(state.draft('maimai').text, 'Leave this alone');
  state.dispose();
});

 test('retrying an already sent voice updates that message through the adapter without posting another message', async () => {
  const retries = [];
  const { state, calls } = fixture(path => path === '' ? root() : { messages: [row('original-message', 28, 'Recognized now', { type: 'voice', voice: retainedAudio() })] });
  const recovery = loaded.createCompanionVoiceRecovery({ state, recording: { retryMessage: async input => retries.push(input) } });
  await recovery.retryMessage('kaka', { id: 'original-message', voice: retainedAudio() });
  assert.equal(retries[0].messageId, 'original-message');
  assert.equal(retries[0].companionId, 'kaka');
  assert.equal(state.getSnapshot().messages.kaka[0].id, 'original-message');
  assert.equal(state.getSnapshot().messages.kaka[0].text, 'Recognized now');
  assert.equal(calls.some(call => call.method === 'POST'), false);
  state.dispose();
});

 test('retained audio references survive page recreation while typed text and audio bytes are not duplicated in browser storage', () => {
  const saved = new Map(), target = { localStorage: { getItem: key => saved.get(key), setItem: (key, value) => saved.set(key, value) } };
  const state = loaded.createCompanionClientState({ target, api: async () => root() });
  const recovery = loaded.createCompanionVoiceRecovery({ state, recording: {} });
  state.setDraft('kaka', { text: 'Fictional unsaved text' });
  recovery.retain('kaka', recognitionFailure(true));
  const serialized = [...saved.values()][0];
  assert.equal(serialized.includes('Fictional unsaved text'), false);
  assert.equal(serialized.includes('base64'), false);
  const reopened = loaded.createCompanionClientState({ target, api: async () => root() });
  assert.equal(reopened.draft('kaka').retainedRecording.voice.attachmentId, 'fixture-recording');
  assert.equal(reopened.draft('kaka').retainedRecording.asText, true);
  reopened.setDraft('kaka', { retainedRecording: null });
  const cleared = loaded.createCompanionClientState({ target, api: async () => root() });
  assert.equal(cleared.draft('kaka').retainedRecording, null);
  state.dispose(); reopened.dispose(); cleared.dispose();
});

 test('a successful send acknowledgement is not turned into a failed message when refresh is unavailable', async () => {
  const { state } = fixture((path, options) => {
    if (path === '') return root();
    if (options.method === 'POST') return row('accepted', 28, options.body.text, { role: 'user', clientId: options.body.clientId });
    throw new Error('Refresh unavailable');
  });
  const result = await state.send('kaka', { text: 'Accepted message' });
  assert.equal(result.id, 'accepted');
  assert.equal(state.getSnapshot().messages.kaka[0].status, 'sent');
  state.dispose();
});

 test('retrying an unfinished turn uses the retry endpoint once without duplicating the user message', async () => {
  const retry = deferred();
  const { state, calls } = fixture((path, options) => path === '' ? root() : path === '/kaka/retry' ? retry.promise : {
    messages: [row('original-user', 28, 'Question', { role: 'user' })], status: { phase: 'thinking' } });
  const first = state.retryTurn('kaka'), second = state.retryTurn('kaka');
  assert.equal(first, second); retry.resolve({ accepted: true }); await first;
  assert.equal(calls.filter(call => call.path === '/kaka/retry').length, 1);
  assert.equal(calls.some(call => call.method === 'POST' && call.path === '/kaka/messages'), false);
  assert.equal(state.getSnapshot().messages.kaka[0].id, 'original-user'); state.dispose();
});

 test('memory correction includes the observed revision and resetting memories does not clear chat or drafts', async () => {
  const { state, calls } = fixture((path, options) => path === '' ? root() : options.method ? {} : { messages: [row('kept', 28)] });
  await state.initialize(); await state.refreshMessages('kaka'); state.setDraft('kaka', { text: 'Keep this draft' });
  await state.correctMemory('kaka', { recordId: 'memory-1', revisionId: 'revision-2' }, 'Corrected fact');
  const patch = calls.find(call => call.method === 'PATCH');
  assert.equal(patch.path, '/kaka/memories');
  assert.equal(JSON.stringify(patch.body), JSON.stringify({ recordId: 'memory-1', expectedRevisionId: 'revision-2', text: 'Corrected fact' }));
  await state.resetMemories('kaka');
  const reset = calls.find(call => call.method === 'DELETE');
  assert.equal(reset.path, '/kaka/memories'); assert.equal(reset.body.all, true);
  assert.equal(state.getSnapshot().messages.kaka[0].id, 'kept'); assert.equal(state.draft('kaka').text, 'Keep this draft'); state.dispose();
});

 test('clearing a chat removes only that companion history and unsent content and invalidates late reads', async () => {
  const stale = deferred(); let reads = 0;
  const { state, calls } = fixture((path, options) => path === '' ? root() : options.method === 'DELETE' ? {} : ++reads === 1
    ? { messages: [row('old', 28)] } : stale.promise);
  await state.initialize(); await state.refreshMessages('kaka');
  state.setDraft('kaka', { text: 'Clear me', attachments: [{ id: 'photo' }], replyTo: { id: 'old' }, retainedRecording: { voice: retainedAudio(), asText: true } });
  state.setDraft('maimai', { text: 'Keep this other draft' });
  const oldRead = state.refreshMessages('kaka', { before: 'old' });
  await state.clearChat('kaka');
  stale.resolve({ messages: [row('resurrected', 27)], hasMore: true }); await oldRead;
  assert.equal(state.getSnapshot().messages.kaka.length, 0); assert.equal(state.getSnapshot().hasMore.kaka, false);
  assert.equal(state.draft('kaka').text, ''); assert.equal(state.draft('kaka').attachments.length, 0);
  assert.equal(state.draft('kaka').retainedRecording, null); assert.equal(state.draft('kaka').replyTo, null);
  assert.equal(state.draft('maimai').text, 'Keep this other draft');
  assert.equal(calls.filter(call => call.method === 'DELETE')[0].path, '/kaka/messages');
  assert.equal(calls.some(call => call.path.includes('/memories')), false); state.dispose();
});

 test('a send acknowledged after clearing the chat cannot restore a deleted message', async () => {
  const acknowledgement = deferred();
  const { state } = fixture((path, options) => path === '' ? root() : options.method === 'POST' ? acknowledgement.promise : {});
  const sending = state.send('kaka', { text: 'Before clearing' });
  await state.clearChat('kaka'); acknowledgement.resolve({ message: row('old-ack', 28) }); await sending;
  assert.equal(state.getSnapshot().messages.kaka.length, 0); state.dispose();
});

 test('a refused clear preserves history, pending audio and typed text', async () => {
  const { state } = fixture((path, options) => {
    if (path === '') return root(); if (options.method === 'DELETE') throw new Error('Not cleared');
    return { messages: [row('kept', 28)] };
  });
  await state.refreshMessages('kaka'); state.setDraft('kaka', { text: 'Still here', retainedRecording: { voice: retainedAudio() } });
  await assert.rejects(state.clearChat('kaka'), /Not cleared/);
  assert.equal(state.getSnapshot().messages.kaka[0].id, 'kept'); assert.equal(state.draft('kaka').text, 'Still here');
  assert.equal(state.draft('kaka').retainedRecording.voice.attachmentId, 'fixture-recording'); state.dispose();
});

function companionUiFixture(handler, language = 'en', { recording, media, nativeBridge, deferUpdates = false } = {}) {
  const effects = [], documentEvents = new Map(), panels = [], calls = [], confirmations = [], hookStores = new Map(), localeListeners = new Set(), events = new Map();
  const updates = [];
  let hooks, cursor, confirmResult = true, active = language;
  const document = { visibilityState: 'hidden', head: { appendChild() {} }, createElement: () => ({ remove() {} }), addEventListener(name, callback) { if (!documentEvents.has(name)) documentEvents.set(name, new Set()); documentEvents.get(name).add(callback); }, removeEventListener(name, callback) { documentEvents.get(name)?.delete(callback); } };
  const target = { document, addEventListener(name, listener) { if (!events.has(name)) events.set(name, new Set()); events.get(name).add(listener); },
    AbortController, setTimeout, clearTimeout, requestAnimationFrame(callback) { callback(); },
    removeEventListener(name, listener) { events.get(name)?.delete(listener); }, confirm(text) { confirmations.push(text); return confirmResult; } };
  const React = { createElement: (type, props, ...children) => ({ type, props: props || {}, children: children.flat(Infinity) }),
    memo: component => component, useMemo: callback => callback(), useEffect(callback) { effects.push(callback); }, useLayoutEffect() {}, useCallback: callback => callback, useSyncExternalStore: (_subscribe, read) => read(),
    useState(initial) { const owner = hooks, index = cursor++; if (!(index in owner)) owner[index] = typeof initial === 'function' ? initial() : initial;
      return [owner[index], value => {
        const apply = () => { owner[index] = typeof value === 'function' ? value(owner[index]) : value; };
        if (deferUpdates) updates.push(apply); else apply();
      }]; },
    useRef(current) { const index = cursor++; return hooks[index] ||= { current }; } };
  const ui = loaded.createCompanionUi({ React, target, ctx: { locale: { getSnapshot: () => ({ active }), subscribe(listener) { localeListeners.add(listener); return () => localeListeners.delete(listener); } }, layout: { selectPanel(id) { panels.push(id); } } },
    recording, media, nativeBridge, api: async (path, options = {}) => { calls.push({ path, ...options }); return handler(path, options); } });
  const callComponent = (component, props = {}) => { hooks = hookStores.get(component) || []; hookStores.set(component, hooks); cursor = 0; return component(props); };
  return { ui, target, calls, panels, confirmations, callComponent,
    runEffects() { return effects.splice(0).map(callback => callback()).filter(Boolean); },
    emitDocument(name) { for (const callback of documentEvents.get(name) || []) callback(); }, decline(value = true) { confirmResult = !value; },
    flushUpdates() { while (updates.length) updates.shift()(); },
    changeLocale(value) { active = value; for (const listener of localeListeners) listener(); },
    emit(name) { for (const listener of events.get(name) || []) listener(); },
    render() { const panel = callComponent(ui.Panel); const child = panel.children[0]; return callComponent(child.type, child.props); } };
}
const treeNodes = tree => tree && typeof tree === 'object' ? [tree, ...(tree.children || []).flatMap(treeNodes)] : [];
const tick = () => new Promise(resolve => setImmediate(resolve));

test('queued voice gesture updates survive preparation failure, cancellation and completion in both languages', async () => {
  for (const language of ['zh', 'en']) for (const terminal of ['failure', 'cancel', 'finish']) {
    const pending = deferred(); let cancelled = 0, finished = 0;
    const handle = { async cancel() { cancelled++; }, async finish() { finished++; return { text: 'A fictional transcript' }; } };
    const f = companionUiFixture(path => path === '' ? root() : { messages: [], status: { phase: 'idle' } }, language,
      { deferUpdates: true, recording: { start: () => pending.promise } });
    const composer = () => {
      const node = treeNodes(f.render()).find(node => node.type.name === 'Composer');
      return f.callComponent(node.type, node.props);
    };
    try {
      await f.ui.state.initialize(); f.ui.state.open('kaka'); await tick();
      f.ui.state.setDraft('kaka', { text: 'Keep the existing draft' });
      treeNodes(composer()).find(node => node.props['aria-label'] === loaded.DROY_COMPANION_COPY[language].voiceMode).props.onClick();
      f.flushUpdates();
      const hold = treeNodes(composer()).find(node => node.props.className === 'droy-im-hold');
      const starting = hold.props.onPointerDown({ clientX: 0, clientY: 0, currentTarget: {}, preventDefault() {} });
      if (terminal === 'finish') { pending.resolve(handle); await starting; f.flushUpdates(); }
      hold.props.onPointerMove({ clientX: 90, clientY: 0 });
      if (terminal === 'failure') { pending.reject(new Error('FICTIONAL_RECORDING_DENIED')); await starting; }
      if (terminal === 'cancel') { hold.props.onPointerCancel(); pending.resolve(handle); await starting; }
      if (terminal === 'finish') await hold.props.onPointerUp();
      assert.doesNotThrow(() => f.flushUpdates(), language + ': queued move after ' + terminal);
      assert.equal(treeNodes(composer()).some(node => node.props.className?.startsWith('droy-im-recording')), false);
      assert.equal(f.ui.state.getSnapshot().screen, 'chat');
      assert.equal(f.calls.some(call => call.method === 'POST'), false, 'These gestures must not send a message');
      assert.equal(cancelled, terminal === 'cancel' ? 1 : 0);
      assert.equal(finished, terminal === 'finish' ? 1 : 0);
      assert.equal(f.ui.state.draft('kaka').text,
        terminal === 'finish' ? 'Keep the existing draft\nA fictional transcript' : 'Keep the existing draft');
    } finally { f.ui.dispose(); }
  }
});

 test('personal information hides memory management and keeps explicit clear/reset confirmations in both languages', async () => {
  for (const language of ['zh', 'en']) {
    const f = companionUiFixture((path, options) => path === '' ? root() : options.method ? {} : { messages: [], status: { phase: 'idle' } }, language);
    await f.ui.state.initialize(); f.ui.state.open('kaka'); await tick(); f.ui.state.showProfile();
    const dictionary = loaded.DROY_COMPANION_COPY[language];
    let tree = f.render();
    assert.equal(treeNodes(tree).some(node => node.props.className === 'droy-im-memory-entry'), false);
    assert.equal(treeNodes(tree).some(node => node.children.includes(dictionary.memoryHint)), false);
    assert.equal(treeNodes(tree).some(node => node.children.includes(dictionary.modelHint)), false);
    let clear = treeNodes(tree).find(node => node.type === 'button' && node.children.includes(dictionary.clearChat));
    f.decline(); clear.props.onClick(); await tick(); assert.equal(f.calls.some(call => call.method === 'DELETE'), false);
    f.decline(false); clear.props.onClick(); await tick();
    assert.equal(f.calls.find(call => call.method === 'DELETE').path, '/kaka/messages');
    assert.ok(f.confirmations.at(-1).includes(dictionary.clearChatHint));
    tree = f.render(); treeNodes(tree).find(node => node.type === 'button' && node.children.includes(dictionary.resetMemories)).props.onClick(); await tick();
    assert.equal(f.calls.find(call => call.path === '/kaka/memories' && call.method === 'DELETE').body.all, true);
    assert.ok(f.confirmations.at(-1).includes(dictionary.resetMemoriesHint));
    assert.equal(f.calls.some(call => call.path.endsWith('/memories') && !call.method), false); f.ui.dispose();
  }
});

 test('task details use service state and summary, and the interrupted-turn action retries actual work', async () => {
  const f = companionUiFixture((path, options) => path === '' ? root() : options.method ? {} : {
    messages: [], status: { phase: 'error', error: 'Connection interrupted' } });
  await f.ui.state.initialize(); f.ui.state.open('kaka'); await tick();
  f.ui.state.showTask({ id: 'service-task', title: 'Lookup', state: 'completed', summary: 'The real service result' });
  let tree = f.render();
  assert.ok(treeNodes(tree).some(node => node.children.includes('The real service result')));
  assert.equal(treeNodes(tree).some(node => node.type === 'button' && node.children.includes('Stop task')), false);
  f.ui.state.back(); tree = f.render();
  await treeNodes(tree).find(node => node.type.name === 'ErrorNotice').props.reload();
  assert.equal(f.calls.find(call => call.method === 'POST').path, '/kaka/retry'); f.ui.dispose();
});

 test('a first empty chat response finishes loading and shows the real empty conversation in both languages', async () => {
  for (const language of ['zh', 'en']) {
    const response = deferred();
    const f = companionUiFixture(path => path === '' ? root() : response.promise, language);
    await f.ui.state.initialize();
    assert.equal(Object.hasOwn(f.ui.state.getSnapshot().messages, 'kaka'), false);
    f.ui.state.open('kaka');
    let tree = f.render();
    assert.ok(treeNodes(tree).some(node => node.props.className === 'droy-im-empty' && node.children.includes(loaded.DROY_COMPANION_COPY[language].loading)));
    response.resolve({ messages: [], hasMore: false, status: { phase: 'idle' } }); await tick();
    assert.equal(Object.hasOwn(f.ui.state.getSnapshot().messages, 'kaka'), true);
    assert.equal(f.ui.state.getSnapshot().messages.kaka.length, 0);
    tree = f.render();
    assert.equal(treeNodes(tree).some(node => node.props.className === 'droy-im-empty'), false);
    assert.ok(treeNodes(tree).some(node => node.props.className === 'droy-im-chat-empty'));
    assert.equal(Object.hasOwn(f.ui.state.getSnapshot().messages, 'maimai'), false); f.ui.dispose();
  }
});

 test('companion profiles refresh only after native locale synchronization and preserve drafts and custom names', async () => {
  const synchronized = deferred(); let backendLanguage = 'en';
  const f = companionUiFixture(path => path === '' ? { ...root(), companions: [
    { id: 'kaka', name: backendLanguage === 'en' ? 'Kaka' : '咔咔', personality: backendLanguage === 'en' ? 'Warm companion.' : '温和的伙伴。' },
    { id: 'maimai', name: 'My chosen name', nameCustomized: true, personality: backendLanguage === 'en' ? 'Daily conversation.' : '日常聊天。' }] } : { messages: [] });
  f.target.__DSH_LOCALE__ = { whenSynced: () => synchronized.promise };
  await f.ui.state.initialize(); f.ui.state.open('kaka'); await tick(); f.ui.state.showProfile();
  f.ui.state.setDraft('kaka', { text: 'Keep typed text during a locale change' });
  f.changeLocale('zh'); await tick();
  assert.equal(f.calls.filter(call => call.path === '').length, 1);
  f.emit('droy-native-locale-change'); await tick();
  assert.equal(f.calls.filter(call => call.path === '').length, 1, 'Localized data must wait for the accepted native language');
  backendLanguage = 'zh'; synchronized.resolve(); await tick();
  assert.equal(f.ui.state.profile('kaka').name, '咔咔'); assert.equal(f.ui.state.profile('kaka').personality, '温和的伙伴。');
  assert.equal(f.ui.state.profile('maimai').name, 'My chosen name');
  assert.equal(f.ui.state.draft('kaka').text, 'Keep typed text during a locale change'); assert.equal(f.ui.state.getSnapshot().screen, 'profile');
  const requests = f.calls.length; f.ui.dispose(); f.emit('droy-native-locale-change'); f.changeLocale('en'); await tick();
  assert.equal(f.calls.length, requests);
});

 test('saving companion settings submits a name only after the user edits it', async () => {
  const f = companionUiFixture((path, options) => path === '' ? { ...root(), companions: [{ id: 'kaka', name: 'Kaka', settings: {}, model: null }] }
    : options.method ? {} : { messages: [] });
  await f.ui.state.initialize(); f.ui.state.open('kaka'); await tick(); f.ui.state.showProfile();
  let tree = f.render(); treeNodes(tree).find(node => node.props.className === 'droy-im-profile-form').props.onSubmit({ preventDefault() {} }); await tick();
  const first = f.calls.find(call => call.method === 'PATCH'); assert.equal(Object.hasOwn(first.body, 'name'), false);
  tree = f.render(); treeNodes(tree).find(node => node.type === 'input' && node.props.maxLength === 40).props.onChange({ target: { value: 'My actual rename' } });
  tree = f.render(); treeNodes(tree).find(node => node.props.className === 'droy-im-profile-form').props.onSubmit({ preventDefault() {} }); await tick();
  assert.equal(f.calls.filter(call => call.method === 'PATCH').at(-1).body.name, 'My actual rename'); f.ui.dispose();
});

 test('typing publishes only the current draft view and identical message refreshes keep row identity', async () => {
  const rows = [row('one', 27), row('two', 28)];
  const { state } = fixture(path => path === '' ? root() : { messages: rows.map(item => ({ ...item })), status: { phase: 'idle' } });
  await state.initialize(); state.open('kaka'); await tick();
  const view = state.getViewSnapshot(), messages = state.getSnapshot().messages.kaka;
  let viewUpdates = 0, draftUpdates = 0, otherDraftUpdates = 0;
  const unsubscribe = [state.subscribeView(() => viewUpdates++), state.subscribeDraft('kaka', () => draftUpdates++), state.subscribeDraft('maimai', () => otherDraftUpdates++)];
  for (let index = 0; index < 20; index++) state.setDraft('kaka', { text: 'Typed ' + index });
  assert.equal(state.getViewSnapshot(), view); assert.equal(viewUpdates, 0); assert.equal(draftUpdates, 20); assert.equal(otherDraftUpdates, 0);
  await state.refreshMessages('kaka');
  assert.equal(state.getSnapshot().messages.kaka, messages); assert.equal(state.getSnapshot().messages.kaka[0], messages[0]);
  for (const remove of unsubscribe) remove(); state.dispose();
});

 test('typed status events update the header state without requesting root data or message history', async () => {
  let stream;
  const { state, target, calls } = fixture(path => path === '' ? root() : { messages: [row('one', 28)], status: { phase: 'idle' } });
  target.EventSource = class { constructor() { stream = this; this.listeners = new Map(); } addEventListener(name, listener) { this.listeners.set(name, listener); } close() {} };
  await state.initialize(); state.open('kaka'); await tick();
  const before = calls.length, rows = state.getSnapshot().messages.kaka;
  stream.listeners.get('invalidate')({ data: JSON.stringify({ companionId: 'kaka', kind: 'status', status: { phase: 'thinking' } }) });
  await tick(); assert.equal(calls.length, before); assert.equal(state.getSnapshot().statuses.kaka.phase, 'thinking'); assert.equal(state.getSnapshot().messages.kaka, rows);
  stream.listeners.get('invalidate')({ data: JSON.stringify({ companionId: 'kaka', kind: 'message', status: { phase: 'typing' } }) });
  await tick(); assert.equal(calls.slice(before).filter(call => call.path === '').length, 1, 'A real message refreshes activity time and unread state even while reading older history'); assert.equal(calls.slice(before).filter(call => call.path === '/kaka/messages').length, 1);
  state.dispose();
});

const componentNode = (tree, name) => treeNodes(tree).find(node => typeof node.type === 'function' && node.type.name === name);
const byAction = (tree, action) => treeNodes(tree).find(node => node.props['data-droy-im-action'] === action);
function renderedMessages(f, tree) {
  const rows = componentNode(tree, 'MessageRows');
  return f.callComponent(rows.type, rows.props).flatMap(fragment => treeNodes(fragment).filter(node => typeof node.type === 'function' && node.type.name === 'Message').map(node => f.callComponent(node.type, node.props)));
}

 test('chat shows only header typing and renders a local time on text, photo and voice messages', async () => {
  for (const language of ['zh', 'en']) {
    const messages = [row('text', 28, 'A reply', { quoteReply: false, replyTo: 'older' }), row('voice', 28, '', { type: 'voice', voice: { url: '/voice' } }),
      row('photo', 28, '', { attachments: [{ id: 'pic', mediaType: 'image/png', url: '/picture' }] })];
    const f = companionUiFixture(path => path === '' ? root() : { messages, status: { phase: 'thinking' } }, language);
    await f.ui.state.initialize(); f.ui.state.open('kaka'); await tick();
    const tree = f.render(), rows = renderedMessages(f, tree);
    assert.ok(treeNodes(tree).some(node => node.props.role === 'status' && node.children.includes(loaded.DROY_COMPANION_COPY[language].typing)));
    assert.equal(treeNodes(tree).some(node => node.props.className === 'droy-im-typing'), false);
    assert.equal(treeNodes(tree).some(node => node.children.includes(loaded.DROY_COMPANION_COPY[language].thinking)), false);
    for (const item of rows) {
      const time = treeNodes(item).find(node => node.props.className === 'droy-im-message-time');
      assert.equal(time.props.dateTime, messages[0].createdAt); assert.match(time.children[0], /^\d{2}:\d{2}$/);
    }
    assert.equal(treeNodes(rows[0]).some(node => node.props.className === 'droy-im-message-quote'), false);
    f.ui.dispose();
  }
});

 test('visible quote intent is independent of reply anchors and revoked previews never expose cached text', async () => {
  const messages = [row('older', 27, 'A secret that was removed from context'),
    row('plain', 28, 'Ordinary reply', { replyTo: 'older', quoteReply: false }),
    row('quoted', 28, 'Relevant reply', { replyTo: 'outside-page', quoteReply: true, replyPreview: { id: 'outside-page', text: 'Safe server preview' } }),
    row('revoked', 28, 'Unavailable reference', { replyTo: 'older', quoteReply: true, replyPreview: null }),
    row('manual', 28, 'My manual reply', { role: 'user', replyTo: 'older', replyPreview: { id: 'older', text: 'Manual preview' } })];
  const f = companionUiFixture(path => path === '' ? root() : { messages });
  await f.ui.state.initialize(); f.ui.state.open('kaka'); await tick();
  const rows = renderedMessages(f, f.render());
  const quote = index => treeNodes(rows[index]).find(node => node.props.className === 'droy-im-message-quote');
  assert.equal(quote(1), undefined); assert.equal(quote(2).children[0], 'Safe server preview');
  assert.equal(quote(3).children[0], loaded.DROY_COMPANION_COPY.en.quotedUnavailable);
  assert.equal(quote(4).children[0], 'Manual preview'); f.ui.dispose();
});

 test('civil-language and do-not-disturb switches persist correct semantics without discarding selected hours', async () => {
  const initial = { ...root(), companions: [{ id: 'kaka', name: 'Kaka', settings: { allowProfanity: true, quietHours: { enabled: true, start: '22:00', end: '09:00' } } }] };
  const f = companionUiFixture((path, options) => path === '' ? initial : options.method ? {} : { messages: [] });
  await f.ui.state.initialize(); f.ui.state.open('kaka'); await tick(); f.ui.state.showProfile();
  let tree = f.render(); const setting = key => treeNodes(tree).find(node => node.props['data-droy-im-setting'] === key);
  assert.equal(setting('civilLanguage').props.checked, false); assert.equal(setting('dnd').props.checked, true);
  setting('civilLanguage').props.onChange({ target: { checked: true } }); setting('dnd').props.onChange({ target: { checked: false } });
  tree = f.render(); assert.equal(setting('dndStart'), undefined);
  treeNodes(tree).find(node => node.props.className === 'droy-im-profile-form').props.onSubmit({ preventDefault() {} }); await tick();
  const patch = f.calls.find(call => call.method === 'PATCH').body;
  assert.equal(patch.settings.allowProfanity, false); assert.equal(patch.settings.quietHours.enabled, false); assert.equal(patch.settings.quietHours.start, '22:00'); assert.equal(patch.settings.quietHours.end, '09:00');
  f.ui.dispose();
});

 test('personality modal cancels without writes and saves user text verbatim through its own endpoint', async () => {
  const initial = { ...root(), companions: [{ id: 'kaka', name: 'Kaka', personality: 'Preset personality', settings: {} }] };
  const f = companionUiFixture((path, options) => path === '' ? initial : options.method ? {} : { messages: [] }, 'zh');
  await f.ui.state.initialize(); f.ui.state.open('kaka'); await tick(); f.ui.state.showProfile();
  byAction(f.render(), 'personality').props.onClick(); let tree = f.render();
  treeNodes(tree).find(node => node.props.className === 'droy-im-personality-editor').props.onChange({ target: { value: 'Keep 我的 original language' } });
  byAction(f.render(), 'cancel').props.onClick(); assert.equal(f.calls.some(call => call.method === 'PATCH'), false);
  byAction(f.render(), 'personality').props.onClick(); tree = f.render();
  treeNodes(tree).find(node => node.props.className === 'droy-im-personality-editor').props.onChange({ target: { value: 'Keep 我的 original language' } });
  tree = f.render(); treeNodes(tree).find(node => node.props.className === 'droy-im-dialog-form').props.onSubmit({ preventDefault() {} }); await tick();
  const patch = f.calls.find(call => call.method === 'PATCH'); assert.equal(patch.path, '/kaka'); assert.equal(patch.body.personalityOverride, 'Keep 我的 original language');
  assert.equal(treeNodes(f.render()).some(node => node.props['data-droy-im-dialog']), false); f.ui.dispose();
});

 test('model picker is an in-page list with search and only changes the companion model on save', async () => {
  const initial = { ...root(), companions: [{ id: 'kaka', name: 'Kaka', settings: {} }], models: [{ provider: 'fictional', model: 'one', name: 'Model One' }, { provider: 'fictional', model: 'two', name: 'Model Two' }] };
  const f = companionUiFixture((path, options) => path === '' ? initial : options.method ? {} : { messages: [] });
  await f.ui.state.initialize(); f.ui.state.open('kaka'); await tick(); f.ui.state.showProfile();
  assert.equal(treeNodes(f.render()).some(node => node.type === 'select'), false);
  byAction(f.render(), 'model-picker').props.onClick(); let tree = f.render();
  treeNodes(tree).find(node => node.props.className === 'droy-im-model-search').props.onChange({ target: { value: 'Two' } }); tree = f.render();
  assert.equal(treeNodes(tree).some(node => node.props['data-droy-im-model'] === 'fictional:one'), false);
  treeNodes(tree).find(node => node.props['data-droy-im-model'] === 'fictional:two').props.onClick();
  assert.equal(f.calls.some(call => call.method === 'PATCH'), false);
  tree = f.render(); treeNodes(tree).find(node => node.props.className === 'droy-im-profile-form').props.onSubmit({ preventDefault() {} }); await tick();
  const patch = f.calls.find(call => call.method === 'PATCH'); assert.equal(patch.path, '/kaka'); assert.equal(patch.body.model.model, 'two');
  assert.equal(f.calls.some(call => /session|default-model/.test(call.path)), false); f.ui.dispose();
});

 test('background selector has six unnamed previews and applies only to the current companion', async () => {
  const f = companionUiFixture((path, options) => path === '' ? root() : options.method ? {} : { messages: [] });
  await f.ui.state.initialize(); f.ui.state.open('kaka'); await tick(); f.ui.state.showProfile();
  byAction(f.render(), 'background').props.onClick(); let tree = f.render();
  const options = treeNodes(tree).filter(node => node.props['data-droy-im-background']);
  assert.equal(options.length, 6); assert.equal(options.every(node => node.children.every(child => typeof child !== 'string')), true);
  assert.match(options[1].props.style.backgroundImage, /wallpaper-01-thumb\.webp/);
  options.find(node => node.props['data-droy-im-background'] === '03').props.onClick(); tree = f.render();
  byAction(tree, 'apply').props.onClick(); await tick();
  const patch = f.calls.find(call => call.method === 'PATCH'); assert.equal(patch.path, '/kaka'); assert.equal(patch.body.chatBackground.id, '03'); assert.equal(patch.body.chatBackground.type, 'preset'); f.ui.dispose();
});

 test('user profile uses a global endpoint and is shared by every companion conversation', async () => {
  const user = { name: 'Original', avatarUrl: '/droy/companions/assets/user-default.png' };
  const f = companionUiFixture((path, options) => path === '' ? { ...root(), userProfile: user } : path === '/user-profile' ? { ...user, ...options.body } : { messages: [row('user-message', 28, 'Hello', { role: 'user' })] });
  await f.ui.state.initialize(); f.ui.state.open('kaka'); await tick();
  const messages = renderedMessages(f, f.render()); byAction(messages[0], 'user-profile').props.onClick({ stopPropagation() {} });
  let node = componentNode(f.render(), 'UserProfileDialog'), tree = f.callComponent(node.type, node.props);
  treeNodes(tree).find(node => node.props['data-droy-im-field'] === 'user-name').props.onChange({ target: { value: 'New global name' } });
  node = componentNode(f.render(), 'UserProfileDialog'); tree = f.callComponent(node.type, node.props);
  await treeNodes(tree).find(node => node.props.className === 'droy-im-profile-form').props.onSubmit({ preventDefault() {} });
  assert.equal(f.calls.find(call => call.method === 'PATCH').path, '/user-profile'); assert.equal(f.ui.state.getSnapshot().userProfile.name, 'New global name');
  f.ui.state.open('maimai'); await tick();
  const other = renderedMessages(f, f.render()); assert.equal(treeNodes(other[0]).find(node => node.type === 'img' && node.props.alt === 'New global name').props.src, user.avatarUrl);
  assert.equal(f.calls.some(call => call.method === 'PATCH' && ['/kaka', '/maimai'].includes(call.path)), false); f.ui.dispose();
});

 test('partial photo imports stay in the draft with an error and never send automatically', async () => {
  const upload = { id: 'retained-photo', url: '/photo', mediaType: 'image/png' };
  const f = companionUiFixture(path => path === '' ? root() : { messages: [] }, 'en', { media: { async pick() { throw Object.assign(new Error('FICTIONAL_IMPORT_FAILED'), { retainedUploads: [upload] }); } } });
  await f.ui.state.initialize(); f.ui.state.open('kaka'); await tick(); f.ui.state.setDraft('kaka', { text: 'Keep my text' });
  let node = componentNode(f.render(), 'Composer'), tree = f.callComponent(node.type, node.props);
  f.ui.state.setDraft('kaka', { text: '' }); node = componentNode(f.render(), 'Composer'); tree = f.callComponent(node.type, node.props);
  treeNodes(tree).find(node => node.props['aria-label'] === loaded.DROY_COMPANION_COPY.en.attachments).props.onClick();
  node = componentNode(f.render(), 'Composer'); tree = f.callComponent(node.type, node.props);
  f.ui.state.setDraft('kaka', { text: 'Keep my text' });
  await treeNodes(tree).find(node => node.type === 'button' && node.props.key === 'album').props.onClick();
  assert.equal(f.ui.state.draft('kaka').text, 'Keep my text'); assert.equal(f.ui.state.draft('kaka').attachments[0].id, upload.id);
  assert.equal(f.ui.state.getSnapshot().error, 'FICTIONAL_IMPORT_FAILED'); assert.equal(f.calls.some(call => call.method === 'POST'), false); f.ui.dispose();
});

 test('user avatar import blocks dialog dismissal until settled and retained uploads remain reviewable', async () => {
  const pending = deferred(), upload = { id: 'global-photo', url: '/user-profile/attachments/global-photo' };
  const f = companionUiFixture((path, options) => path === '' ? root() : path === '/user-profile' ? { name: '', ...options.body } : { messages: [row('mine', 28, 'Hello', { role: 'user' })] }, 'en', { media: { pick: () => pending.promise } });
  await f.ui.state.initialize(); f.ui.state.open('kaka'); await tick();
  byAction(renderedMessages(f, f.render())[0], 'user-profile').props.onClick({ stopPropagation() {} });
  let node = componentNode(f.render(), 'UserProfileDialog'), tree = f.callComponent(node.type, node.props);
  const importing = byAction(tree, 'user-avatar').props.onClick();
  node.props.onClose(); assert.ok(componentNode(f.render(), 'UserProfileDialog'), 'The same close guard must protect shade, Escape and native Back');
  pending.reject(Object.assign(new Error('FICTIONAL_COMPLETE_FAILED'), { retainedUploads: [upload] })); await importing;
  node = componentNode(f.render(), 'UserProfileDialog'); tree = f.callComponent(node.type, node.props);
  assert.ok(treeNodes(tree).some(node => node.type === 'img' && node.props.src === upload.url));
  assert.equal(f.calls.some(call => call.method === 'PATCH'), false);
  await treeNodes(tree).find(node => node.props.className === 'droy-im-profile-form').props.onSubmit({ preventDefault() {} });
  assert.equal(f.ui.state.getSnapshot().userProfile.avatarUrl, upload.url); assert.equal(componentNode(f.render(), 'UserProfileDialog'), undefined); f.ui.dispose();
});

 test('a retained background upload preserves preview position and is only saved by Apply', async () => {
  const upload = { id: 'wallpaper-upload', url: '/kaka/attachments/wallpaper-upload' };
  const f = companionUiFixture((path, options) => path === '' ? root() : options.method ? {} : { messages: [] }, 'en', { media: { async pick() { throw Object.assign(new Error('FICTIONAL_COMPLETE_FAILED'), { retainedUploads: [upload] }); } } });
  await f.ui.state.initialize(); f.ui.state.open('kaka'); await tick(); f.ui.state.showProfile(); byAction(f.render(), 'background').props.onClick();
  await byAction(f.render(), 'upload-background').props.onClick(); let tree = f.render();
  assert.ok(treeNodes(tree).find(node => node.props.className === 'droy-im-background-preview').props.style.backgroundImage.includes(upload.url));
  assert.equal(f.calls.some(call => call.method === 'PATCH'), false);
  const sliders = treeNodes(tree).filter(node => node.props.type === 'range'); assert.equal(sliders.length, 2);
  sliders[0].props.onChange({ target: { value: '28' } }); tree = f.render();
  await byAction(tree, 'apply').props.onClick(); const patch = f.calls.find(call => call.method === 'PATCH');
  assert.equal(patch.body.chatBackground.id, upload.id); assert.equal(patch.body.chatBackground.position.x, 28); assert.equal(patch.body.chatBackground.position.y, 50);
  assert.equal(Object.hasOwn(patch.body.chatBackground, 'url'), false); f.ui.dispose();
});

 test('messages received on a companion profile or in the background update unread counts before returning to the list', async () => {
  for (const surface of ['profile', 'hidden']) {
    let stream, unread = 0, rows = [row('read-before-leaving', 27)];
    const { state, target, calls } = fixture((path, options) => path === ''
      ? { ...root(), companions: root().companions.map(item => item.id === 'kaka' ? { ...item, unread } : item) }
      : options.method === 'POST' ? {} : { messages: rows, status: { phase: 'idle' } });
    target.document = { visibilityState: 'visible' };
    target.EventSource = class { constructor() { stream = this; this.listeners = new Map(); } addEventListener(name, listener) { this.listeners.set(name, listener); } close() {} };
    await state.initialize(); state.open('kaka'); await tick();
    if (surface === 'profile') state.showProfile(); else target.document.visibilityState = 'hidden';
    const previousCalls = calls.length;
    unread = 1; rows = [...rows, row('unseen-reply', 28)];
    stream.listeners.get('invalidate')({ data: JSON.stringify({ companionId: 'kaka', kind: 'message' }) });
    await tick();
    assert.equal(calls.slice(previousCalls).filter(call => call.path === '').length, 1, surface + ': unread data must refresh');
    assert.equal(calls.slice(previousCalls).some(call => call.path === '/kaka/read'), false, surface + ': the reply was not viewed');
    assert.equal(state.profile('kaka').unread, 1);
    target.document.visibilityState = 'visible'; state.open();
    assert.equal(state.getSnapshot().screen, 'list'); assert.equal(state.profile('kaka').unread, 1, surface + ': list retains the unread badge');
    state.dispose();
  }
});

 test('newer idle and error status events survive a delayed history response without causing additional reads', async () => {
  for (const eventStatus of [{ phase: 'idle' }, { phase: 'error', error: 'FICTIONAL_GENERATION_FAILED' }]) {
    let stream, delayed = false;
    const response = deferred();
    const { state, target, calls } = fixture(path => path === '' ? root() : delayed ? response.promise : { messages: [row('existing', 28)], status: { phase: 'idle' } });
    target.document = { visibilityState: 'hidden' };
    target.EventSource = class { constructor() { stream = this; this.listeners = new Map(); } addEventListener(name, listener) { this.listeners.set(name, listener); } close() {} };
    await state.initialize(); state.open('kaka'); await tick();
    delayed = true; const read = state.refreshMessages('kaka'), beforeEvent = calls.length;
    stream.listeners.get('invalidate')({ data: JSON.stringify({ companionId: 'kaka', kind: 'status', status: eventStatus }) });
    await tick(); assert.equal(calls.length, beforeEvent, 'Status delivery must not issue another GET');
    response.resolve({ messages: [row('existing', 28), row('arrived-with-history', 28)], status: { phase: 'typing' } }); await read;
    assert.equal(state.getSnapshot().statuses.kaka.phase, eventStatus.phase);
    assert.equal(state.getSnapshot().statuses.kaka.error, eventStatus.error);
    assert.equal(state.getSnapshot().messages.kaka.at(-1).id, 'arrived-with-history', 'Fresh message data is still accepted');
    assert.equal(calls.length, beforeEvent); state.dispose();
  }
});

test('conversation order uses pinned groups and actual message time, ignoring drafts and profile edits', () => {
  const rows = [
    { id: 'older', updatedAt: '2099-01-01', lastMessage: row('old', 20) },
    { id: 'newer', lastActivityAt: '2026-10-28T13:00:00Z', lastMessage: row('latest', 28) },
    { id: 'pin-old', pinned: true, lastMessage: row('pin', 19) },
    { id: 'pin-new', pinned: true, lastMessage: row('pin2', 22) },
    { id: 'empty' }, { id: 'empty2' }, { id: 'hidden', hidden: true, pinned: true, lastMessage: row('gone', 28) },
  ];
  assert.equal(loaded.companionConversations(rows).map(item => item.id).join(), 'pin-new,pin-old,newer,older,empty,empty2');
  assert.equal(rows[0].id, 'older', 'Sorting must not mutate the service snapshot');
});

test('conversation actions use the product endpoint and failed deletion preserves visible history and draft', async () => {
  let hidden = false, fail = true;
  const { state, calls } = fixture((path, options) => {
    if (path === '') return { ...root(), companions: [{ id: 'kaka', hidden, unread: hidden ? 0 : 2 }] };
    if (path === '/kaka/conversation') { if (fail) throw new Error('Not saved'); hidden = true; return { hidden, unreadCount: 0 }; }
    return { messages: [row('retained', 28)] };
  });
  await state.initialize(); await state.refreshMessages('kaka'); state.setDraft('kaka', { text: 'Keep draft' });
  await assert.rejects(state.conversationAction('kaka', 'hide'), /Not saved/);
  assert.equal(state.profile('kaka').hidden, false); assert.equal(state.profile('kaka').unread, 2);
  fail = false; await state.conversationAction('kaka', 'hide');
  assert.equal(state.profile('kaka').hidden, true); assert.equal(state.profile('kaka').unread, 0);
  assert.equal(state.getSnapshot().messages.kaka[0].id, 'retained'); assert.equal(state.draft('kaka').text, 'Keep draft');
  assert.equal(calls.some(call => call.method === 'DELETE'), false);
  assert.equal(calls.find(call => call.path === '/kaka/conversation').body.action, 'hide'); state.dispose();
});

test('visible read positions advance only through displayed messages, leaving later incoming messages unread', async () => {
  let unread = 3;
  const { state, calls } = fixture((path, options) => path === ''
    ? { ...root(), companions: [{ id: 'kaka', unread }] }
    : path === '/kaka/read' ? (unread = options.body.messageId === 'second' ? 1 : 0, {})
      : { messages: [row('first', 20), row('second', 21), row('third', 22)] });
  await state.initialize(); await state.refreshMessages('kaka');
  assert.equal(calls.some(call => call.path === '/kaka/read'), false);
  state.markRead('kaka', 'second'); await tick();
  assert.equal(state.profile('kaka').unread, 1);
  state.markRead('kaka', 'first');
  assert.equal(calls.filter(call => call.path === '/kaka/read').length, 1, 'Scrolling backward cannot move the read boundary backward');
  state.markRead('kaka', 'third'); await tick(); assert.equal(state.profile('kaka').unread, 0); state.dispose();
});

test('mode switch is one click, closes keyboard, retains drafts and includes only visible conversation unread counts', async () => {
  for (const language of ['zh', 'en']) {
    const native = [], f = companionUiFixture(path => path === '' ? { ...root(), companions: [{ id: 'kaka', unread: 3 }, { id: 'maimai', hidden: true, unread: 7 }] } : {}, language,
      { nativeBridge: { request: async action => native.push(action) } });
    let blurred = false;
    f.target.document.activeElement = { blur() { blurred = true; } };
    await f.ui.state.initialize(); f.ui.state.setDraft('kaka', { text: 'Still editing' });
    const node = componentNode(f.render(), 'ModeMenu'), mode = f.callComponent(node.type, node.props);
    const trigger = treeNodes(mode).find(item => item.props.className === 'droy-im-mode-trigger');
    assert.equal(trigger.props['aria-expanded'], undefined);
    assert.equal(treeNodes(mode).find(item => item.props.className?.includes('droy-im-mode-unread')).children[0], 3);
    trigger.props.onClick(); assert.equal(blurred, true); assert.equal(native[0], 'hideKeyboard');
    assert.equal(f.panels.at(-1), null); assert.equal(f.ui.state.draft('kaka').text, 'Still editing');
    const switchNode = f.callComponent(f.ui.WorkModeSwitch), work = f.callComponent(switchNode.type, switchNode.props);
    treeNodes(work).find(item => item.props.className === 'droy-im-mode-trigger').props.onClick();
    assert.equal(f.panels.at(-1), 'droy-companions'); assert.equal(f.ui.state.getSnapshot().screen, 'list');
    f.ui.dispose();
  }
});

test('list gestures separate vertical scrolling, horizontal actions and long-press pin, in both languages', async () => {
  for (const language of ['zh', 'en']) {
    const f = companionUiFixture(path => path === '' ? root() : {}, language);
    let hold, cancelled = 0;
    f.target.setTimeout = callback => { hold = callback; return 1; }; f.target.clearTimeout = () => { hold = null; cancelled++; };
    await f.ui.state.initialize();
    let tree = f.render(), button = treeNodes(tree).find(node => node.props['data-droy-companion'] === 'kaka');
    button.props.onPointerDown({ clientX: 180, clientY: 100 });
    button.props.onPointerMove({ clientX: 178, clientY: 160 });
    button.props.onPointerUp(); assert.ok(cancelled); assert.equal(hold, null);
    tree = f.render(); assert.equal(treeNodes(tree).some(node => node.props['data-droy-conversation-menu']), false);
    button = treeNodes(tree).find(node => node.props['data-droy-companion'] === 'kaka');
    button.props.onPointerDown({ clientX: 180, clientY: 100 });
    button.props.onPointerMove({ clientX: 90, clientY: 102, currentTarget: {} }); button.props.onPointerUp();
    assert.ok(treeNodes(f.render()).find(node => node.props['data-conversation-id'] === 'kaka').props.className.includes('is-open'));
    button.props.onClick({ preventDefault() {} }); assert.equal(f.ui.state.getSnapshot().screen, 'list');
    button.props.onPointerDown({ clientX: 180, clientY: 100 }); hold(); button.props.onPointerUp();
    tree = f.render(); assert.equal(treeNodes(tree).find(node => node.props['data-conversation-action'] === 'pin').children[0], loaded.DROY_COMPANION_COPY[language].pinConversation);
    f.ui.dispose();
  }
});

test('partner message avatar opens the matching personal information page without opening a message menu', async () => {
  const f = companionUiFixture(path => path === '' ? root() : { messages: [row('hello', 28)] });
  await f.ui.state.initialize(); f.ui.state.open('kaka'); await tick();
  const message = renderedMessages(f, f.render())[0];
  const avatar = treeNodes(message).find(node => node.props['data-droy-im-action'] === 'companion-profile');
  let stopped = false; avatar.props.onClick({ stopPropagation() { stopped = true; } });
  assert.equal(stopped, true); assert.equal(f.ui.state.getSnapshot().screen, 'profile');
  assert.equal(f.ui.state.getSnapshot().companionId, 'kaka'); f.ui.dispose();
});

test('companion surfaces inherit the real DSH light and dark palette tokens', async () => {
  const css = await readFile(new URL('../companions/client.css', import.meta.url), 'utf8');
  const theme = await readFile(new URL('../../dsh/node_modules/@deepseek-ai/dsh-client-ui-theme/lib/client.js', import.meta.url), 'utf8');
  for (const token of new Set(css.match(/--dsw-alias-[a-z0-9-]+/g))) assert.ok(theme.includes(token), token + ' must exist in the installed theme');
  assert.match(css, /--im-bar:var\(--dsw-alias-bg-base,/);
  assert.match(css, /--im-bg:var\(--dsw-alias-bg-base,/);
});

test('pending and failed optimistic sends are never submitted as persisted read positions', async () => {
  for (const status of ['pending', 'failed']) {
    const { state, calls } = fixture(path => path === '' ? root() : { messages: [row('unsaved-client-id', 28, 'Unacknowledged', { role: 'user', status })] });
    await state.initialize(); await state.refreshMessages('kaka'); state.markRead('kaka', 'unsaved-client-id');
    assert.equal(calls.some(call => call.path === '/kaka/read'), false, status); state.dispose();
  }
});

test('a failed read restores the canonical unread badge and keeps the failure visible', async () => {
  const { state } = fixture((path, options) => path === '' ? { ...root(), companions: [{ id: 'kaka', unread: 1 }] }
    : options.method === 'POST' ? Promise.reject(new Error('Read receipt not saved')) : { messages: [row('unread', 28)] });
  await state.initialize(); await state.refreshMessages('kaka'); state.markRead('kaka', 'unread'); await tick();
  assert.equal(state.profile('kaka').unread, 1); assert.equal(state.getSnapshot().error, 'Read receipt not saved'); state.dispose();
});

test('returning to the foreground reads already rendered incoming messages without requiring another scroll', async () => {
  const f = companionUiFixture(path => path === '' ? root() : { messages: [row('background-delivery', 28)] });
  await f.ui.state.initialize(); f.ui.state.open('kaka'); await tick();
  const tree = f.render(), list = treeNodes(tree).find(node => node.props.className === 'droy-im-messages');
  list.props.ref.current = { getClientRects: () => [{}], getBoundingClientRect: () => ({ top: 50, bottom: 300 }),
    querySelectorAll: () => [{ getBoundingClientRect: () => ({ top: 100, bottom: 200 }), getAttribute: key => key === 'data-message-id' ? 'background-delivery' : 'sent' }] };
  const disposeEffects = f.runEffects();
  f.target.document.visibilityState = 'hidden'; f.emitDocument('visibilitychange'); await tick();
  assert.equal(f.calls.some(call => call.path === '/kaka/read'), false);
  const messages = f.ui.state.getSnapshot().messages.kaka;
  f.target.document.visibilityState = 'visible'; f.emitDocument('visibilitychange'); await tick();
  assert.equal(f.ui.state.getSnapshot().messages.kaka, messages, 'Unchanged messages preserve their identity');
  assert.equal(f.calls.filter(call => call.path === '/kaka/read').length, 1);
  for (const dispose of disposeEffects) dispose(); f.ui.dispose();
});


test('swipe actions activate completed touch taps once with or without a browser click, and keep keyboard activation', async () => {
  for (const browserClick of [false, true]) {
    const pending = deferred();
    const f = companionUiFixture((path, options) => path === '' ? root() : path.endsWith('/conversation') ? pending.promise : {});
    await f.ui.state.initialize();
    const tree = f.render(), actions = treeNodes(tree).filter(node => node.props['data-conversation-action']);
    const read = actions.find(node => node.props['data-conversation-action'] === 'read');
    const target = { disabled: false, getBoundingClientRect: () => ({ left: 150, right: 230, top: 100, bottom: 190 }) };
    const tap = { pointerType: 'touch', pointerId: 1, clientX: 190, clientY: 140, currentTarget: target, preventDefault() {} };
    read.props.onPointerDown(tap); read.props.onPointerUp(tap);
    if (browserClick) read.props.onClick({ detail: 1, preventDefault() {} });
    assert.equal(f.calls.filter(call => call.path.endsWith('/conversation')).length, 1, 'One completed touch executes once');
    pending.resolve({}); await tick();
    read.props.onClick({ detail: 0 }); await tick();
    assert.equal(f.calls.filter(call => call.path.endsWith('/conversation')).length, 2, 'Keyboard activation does not depend on pointer events');
    f.ui.dispose();
  }
});

test('dragged, cancelled and released-outside action touches cannot mark read or delete a conversation', async () => {
  for (const ending of ['drag', 'cancel', 'outside']) {
    const f = companionUiFixture(path => path === '' ? root() : {}); await f.ui.state.initialize();
    const action = treeNodes(f.render()).find(node => node.props['data-conversation-action'] === 'hide');
    const tap = { pointerType: 'touch', pointerId: 1, clientX: 280, clientY: 140,
      currentTarget: { disabled: false, getBoundingClientRect: () => ({ left: 240, right: 320, top: 100, bottom: 190 }) }, preventDefault() {} };
    action.props.onPointerDown(tap);
    if (ending === 'drag') action.props.onPointerMove({ ...tap, clientY: 170 });
    if (ending === 'cancel') action.props.onPointerCancel();
    action.props.onPointerUp(ending === 'outside' ? { ...tap, clientX: 330 } : tap);
    action.props.onClick({ detail: 1, preventDefault() {} }); await tick();
    assert.equal(f.calls.some(call => call.path.endsWith('/conversation')), false, ending); f.ui.dispose();
  }
});
