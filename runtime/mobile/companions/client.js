const EMPTY_COMPANION_MESSAGES = Object.freeze([]);
const EMPTY_COMPANION_DRAFT = Object.freeze({ text: '', attachments: EMPTY_COMPANION_MESSAGES, replyTo: null, retainedRecording: null });
const DEFAULT_USER_PROFILE = Object.freeze({ name: '', avatarUrl: '/droy/companions/assets/user-default.png' });

function companionConversations(companions) {
  const activity = item => {
    const time = Date.parse(item.lastActivityAt || item.lastMessage?.createdAt);
    return Number.isFinite(time) ? time : 0;
  };
  return companions.filter(item => !item.hidden).sort((a, b) => Number(Boolean(b.pinned)) - Number(Boolean(a.pinned)) || activity(b) - activity(a));
}

function createCompanionClientState({ target = window, api: suppliedApi } = {}) {
  const listeners = new Set(), viewListeners = new Set(), draftListeners = new Map();
  const emptyDraft = () => ({ text: '', attachments: [], replyTo: null, retainedRecording: null });
  const retainedVoiceKey = 'droy:companion-retained-voice:v1';
  function restoreRetainedVoices() {
    try {
      const saved = JSON.parse(target.localStorage?.getItem(retainedVoiceKey) || '{}');
      if (!saved || typeof saved !== 'object' || Array.isArray(saved)) return {};
      return Object.fromEntries(Object.entries(saved).filter(([, item]) => item?.voice && typeof (item.voice.attachmentId || item.voice.id) === 'string')
        .map(([id, item]) => [id, { ...emptyDraft(), retainedRecording: { voice: item.voice, asText: item.asText === true } }]));
    } catch { return {}; }
  }
  function persistRetainedVoices() {
    const saved = Object.fromEntries(Object.entries(snapshot.drafts).filter(([, item]) => item.retainedRecording)
      .map(([id, item]) => [id, item.retainedRecording]));
    try { target.localStorage?.setItem(retainedVoiceKey, JSON.stringify(saved)); }
    catch { target.console?.warn('COMPANION_RETAINED_VOICE_STORAGE_UNAVAILABLE'); }
  }
  let snapshot = { companions: [], defaultWorkspaceId: null, userProfile: DEFAULT_USER_PROFILE, settings: { defaultMode: 'messages' }, stickers: [], models: [],
    screen: 'list', direction: 'forward', companionId: null, task: null, messages: {}, statuses: {}, hasMore: {}, drafts: restoreRetainedVoices(),
    loading: false, error: null, revision: 0 };
  let disposed = false, rootRequest = 0, rootPending, events;
  let viewSnapshot = snapshot;
  const eventReads = new Map();
  const messageRequests = new Map(), statusEpochs = new Map(), scrollPositions = new Map(), readThrough = new Map(), historyEpoch = new Map(), retryRequests = new Map();
  let listScroll = 0;
  const api = suppliedApi || (async (path, options = {}) => {
    const response = await target.fetch('/droy/companions' + path, {
      credentials: 'same-origin', ...options,
      ...(options.body === undefined ? {} : { headers: { 'Content-Type': 'application/json', ...options.headers }, body: JSON.stringify(options.body) }),
    });
    const body = await response.json();
    if (!response.ok) throw Object.assign(new Error(body.message || body.error || 'COMPANION_REQUEST_FAILED'), { code: body.code || body.error });
    return body;
  });
  function publish(patch) {
    if (disposed) return;
    const previous = snapshot;
    snapshot = { ...snapshot, ...patch, revision: snapshot.revision + 1 };
    for (const listener of listeners) listener();
    // Keystrokes belong to the composer, not to the entire conversation tree.
    if (Object.keys(patch).some(key => key !== 'drafts') || snapshot.screen === 'list') {
      viewSnapshot = snapshot;
      for (const listener of viewListeners) listener();
    }
    if (patch.drafts) for (const [id, subscribers] of draftListeners)
      if (previous.drafts[id] !== snapshot.drafts[id]) for (const listener of subscribers) listener();
  }
  const stable = (previous, next) => JSON.stringify(previous) === JSON.stringify(next) ? previous : next;
  const stableRows = (previous, next) => {
    const byId = new Map(previous.map(item => [item.id, item]));
    const rows = next.map(item => stable(byId.get(item.id), item));
    return rows.length === previous.length && rows.every((item, index) => item === previous[index]) ? previous : rows;
  };
  function profile(id) { return snapshot.companions.find(item => item.id === id); }
  function draft(id) { return snapshot.drafts[id] || EMPTY_COMPANION_DRAFT; }
  function setDraft(id, patch) {
    publish({ drafts: { ...snapshot.drafts, [id]: { ...draft(id), ...patch } } });
    if (Object.hasOwn(patch, 'retainedRecording')) persistRetainedVoices();
  }
  function replaceMessages(id, rows, { prepend = false } = {}) {
    const previous = snapshot.messages[id] || [];
    const incomingIds = new Set(rows.flatMap(row => [row.id, row.clientId].filter(Boolean)));
    const firstTime = rows.length ? Date.parse(rows[0].createdAt) : 0;
    const retained = previous.filter(row => prepend || rows.length > 0 && Date.parse(row.createdAt) < firstTime
      || (row.status === 'pending' || row.status === 'failed') && !incomingIds.has(row.id) && !incomingIds.has(row.clientId));
    const previousById = new Map(previous.map(row => [row.id, row]));
    const merged = new Map([...rows.map(row => stable(previousById.get(row.id), row)), ...retained].map(row => [row.id, row]));
    const next = [...merged.values()].sort((a, b) => Date.parse(a.createdAt) - Date.parse(b.createdAt) || (a.sequence || 0) - (b.sequence || 0));
    if (!Object.hasOwn(snapshot.messages, id) || previous.length !== next.length || next.some((row, index) => row !== previous[index]))
      publish({ messages: { ...snapshot.messages, [id]: next } });
  }
  function refreshRoot() {
    const generation = ++rootRequest;
    return rootPending = (async () => {
      let result;
      try { result = await api(''); }
      catch (error) {
        if (disposed) return;
        if (generation !== rootRequest) return rootPending;
        throw error;
      }
      if (disposed) return;
      // Startup and workspace restoration both load profiles. A superseded
      // read must await the newer snapshot before callers choose a default page.
      if (generation !== rootRequest) return rootPending;
      publish({ companions: stableRows(snapshot.companions, result.companions || []), settings: stable(snapshot.settings, result.settings || snapshot.settings),
        defaultWorkspaceId: result.defaultWorkspaceId || null,
        userProfile: stable(snapshot.userProfile, result.userProfile || snapshot.userProfile),
        stickers: stable(snapshot.stickers, result.stickers || []), models: stable(snapshot.models, result.models || []), loading: false, error: null });
      return result;
    })();
  }
  async function refreshMessages(id, { before } = {}) {
    const requestKey = id + (before ? ':earlier' : ':latest');
    const generation = (messageRequests.get(requestKey) || 0) + 1;
    messageRequests.set(requestKey, generation);
    const statusEpoch = statusEpochs.get(id) || 0;
    const result = await api('/' + encodeURIComponent(id) + '/messages' + (before ? '?before=' + encodeURIComponent(before) : ''));
    if (disposed || generation !== messageRequests.get(requestKey)) return;
    const alreadyLoaded = snapshot.hasMore[id] !== undefined;
    replaceMessages(id, result.messages || [], { prepend: Boolean(before) });
    // A completion/error event can arrive while this older history read is in flight.
    const keepStatus = before && snapshot.statuses[id] || statusEpoch !== (statusEpochs.get(id) || 0);
    const status = stable(snapshot.statuses[id], keepStatus ? snapshot.statuses[id] : result.status || { phase: 'idle' });
    const hasMore = before || !alreadyLoaded ? result.hasMore === true : snapshot.hasMore[id];
    const last = result.messages?.at(-1);
    const companions = last && !before ? stableRows(snapshot.companions, snapshot.companions.map(item => item.id === id ? { ...item, lastMessage: last } : item)) : snapshot.companions;
    if (status !== snapshot.statuses[id] || hasMore !== snapshot.hasMore[id] || companions !== snapshot.companions || snapshot.error)
      publish({ statuses: { ...snapshot.statuses, [id]: status }, hasMore: { ...snapshot.hasMore, [id]: hasMore }, companions, error: null });
    return result;
  }
  function markRead(id, messageId) {
    if (!messageId || readThrough.get(id) === messageId) return;
    const messages = snapshot.messages[id] || [], index = messages.findIndex(message => message.id === messageId);
    const previousIndex = messages.findIndex(message => message.id === readThrough.get(id));
    if (index < 0 || previousIndex > index || ['pending', 'failed'].includes(messages[index].status)) return;
    readThrough.set(id, messageId);
    const remaining = index < 0 ? null : messages.slice(index + 1).filter(message => message.role === 'assistant').length;
    if (remaining !== null) publish({ companions: snapshot.companions.map(item => item.id === id ? { ...item, unread: Math.min(item.unread || 0, remaining) } : item) });
    api('/' + encodeURIComponent(id) + '/read', { method: 'POST', body: { messageId } }).then(refreshRoot).catch(error => {
      if (readThrough.get(id) === messageId) readThrough.delete(id);
      refreshRoot().then(() => report(error), report);
    });
  }
  async function conversationAction(id, action) {
    await api('/' + encodeURIComponent(id) + '/conversation', { method: 'POST', body: { action } });
    await refreshRoot();
  }
  async function refresh(id = snapshot.companionId) {
    const operations = [refreshRoot()];
    if (id) operations.push(refreshMessages(id));
    return Promise.all(operations);
  }
  function report(error) { publish({ error: error?.message || String(error) }); }
  function scheduleEventRead(key, operation) {
    const current = eventReads.get(key);
    if (current) { current.again = true; return; }
    const entry = { again: false }; eventReads.set(key, entry);
    Promise.resolve().then(async () => {
      do { entry.again = false; if (!disposed) await operation(); } while (entry.again && !disposed);
    }).catch(report).finally(() => eventReads.delete(key));
  }
  async function initialize() {
    publish({ loading: true });
    try {
      const result = await refreshRoot();
      if (!events && target.EventSource && !disposed) {
        events = new target.EventSource('/droy/companions/events');
        const invalidate = event => {
          let data;
          try { data = JSON.parse(event.data); } catch { return; }
          const id = data.companionId;
          if (data.kind === 'status' && id && data.status) {
            statusEpochs.set(id, (statusEpochs.get(id) || 0) + 1);
            if (JSON.stringify(snapshot.statuses[id]) !== JSON.stringify(data.status)) publish({ statuses: { ...snapshot.statuses, [id]: data.status } });
            return;
          }
          if (data.kind === 'read') { scheduleEventRead('root', refreshRoot); return; }
          scheduleEventRead('root', refreshRoot);
          if (data.kind !== 'profile' && id && (snapshot.companionId === id || snapshot.messages[id]))
            scheduleEventRead(id, () => refreshMessages(id));
        };
        events.addEventListener('invalidate', invalidate);
        events.addEventListener('message', invalidate);
      }
      return result;
    } catch (error) { publish({ loading: false }); report(error); throw error; }
  }
  function open(id) {
    if (id && !profile(id)) return;
    publish({ screen: id ? 'chat' : 'list', direction: id ? 'forward' : 'back', companionId: id || null, task: null, error: null });
    if (id) {
      refreshMessages(id).catch(report);
    }
  }
  function back() {
    if (snapshot.screen === 'task' || snapshot.screen === 'profile') publish({ screen: 'chat', direction: 'back', task: null, error: null });
    else open();
  }
  function setMessage(id, message) {
    const rows = [...(snapshot.messages[id] || [])];
    const index = rows.findIndex(row => row.id === message.id || message.clientId && row.clientId === message.clientId);
    if (index < 0) rows.push(message); else rows[index] = message;
    publish({ messages: { ...snapshot.messages, [id]: rows } });
  }
  async function send(id, input = {}) {
    const epoch = historyEpoch.get(id);
    const current = draft(id);
    const quoted = input.replyTo ?? current.replyTo;
    const clientId = input.clientId || target.crypto?.randomUUID?.() || ('message-' + Date.now() + '-' + Math.random().toString(16).slice(2));
    const payload = { text: input.text ?? current.text, attachments: input.attachments ?? current.attachments,
      ...(input.voice ? { voice: input.voice } : {}), ...(input.stickerId ? { stickerId: input.stickerId } : {}),
      ...(quoted ? { replyTo: typeof quoted === 'string' ? quoted : quoted.id } : {}), clientId };
    if (!payload.text?.trim() && !payload.attachments?.length && !payload.voice && !payload.stickerId) return;
    const optimistic = { id: clientId, clientId, role: 'user', type: payload.voice ? 'voice' : payload.stickerId ? 'sticker' : 'text',
      text: payload.text || '', attachments: payload.attachments || [], voice: payload.voice, stickerId: payload.stickerId,
      replyTo: quoted, createdAt: new Date().toISOString(), status: 'pending' };
    setMessage(id, optimistic);
    if (!input.clientId && input.text === undefined && input.attachments === undefined && !input.voice && !input.stickerId)
      setDraft(id, { ...emptyDraft(), retainedRecording: current.retainedRecording });
    let message;
    try {
      const result = await api('/' + encodeURIComponent(id) + '/messages', { method: 'POST', body: payload });
      message = result.message || result.userMessage || (result.id ? result : null);
      if (historyEpoch.get(id) !== epoch) return message;
      if (message) setMessage(id, { ...message, clientId: message.clientId || clientId });
    } catch (error) {
      if (historyEpoch.get(id) !== epoch) return;
      setMessage(id, { ...optimistic, status: 'failed', error: error.message });
      report(error); throw error;
    }
    // A failed refresh cannot undo an acknowledged send or trigger a duplicate retry.
    await refreshMessages(id).catch(report);
    refreshRoot().catch(report);
    return message;
  }
  async function updateProfile(id, patch) {
    const result = await api('/' + encodeURIComponent(id), { method: 'PATCH', body: patch });
    await refreshRoot(); return result;
  }
  async function updateSettings(patch) {
    const result = await api('/settings', { method: 'PATCH', body: patch });
    publish({ settings: result.settings || { ...snapshot.settings, ...patch } }); return result;
  }
  async function updateUserProfile(patch) {
    const result = await api('/user-profile', { method: 'PATCH', body: patch });
    publish({ userProfile: result }); return result;
  }
  async function removeMessage(id, messageId) {
    await api('/' + encodeURIComponent(id) + '/messages/' + encodeURIComponent(messageId), { method: 'DELETE' });
    publish({ messages: { ...snapshot.messages, [id]: (snapshot.messages[id] || []).filter(row => row.id !== messageId) } });
    await refreshRoot();
  }
  function retryTurn(id) {
    if (retryRequests.has(id)) return retryRequests.get(id);
    const operation = api('/' + encodeURIComponent(id) + '/retry', { method: 'POST', body: {} })
      .then(() => refreshMessages(id)).finally(() => retryRequests.delete(id));
    retryRequests.set(id, operation); return operation;
  }
  async function clearChat(id) {
    await api('/' + encodeURIComponent(id) + '/messages', { method: 'DELETE' });
    historyEpoch.set(id, (historyEpoch.get(id) || 0) + 1);
    for (const kind of ['latest', 'earlier']) {
      const key = id + ':' + kind; messageRequests.set(key, (messageRequests.get(key) || 0) + 1);
    }
    scrollPositions.delete(id); readThrough.delete(id);
    setDraft(id, emptyDraft());
    publish({ messages: { ...snapshot.messages, [id]: [] }, statuses: { ...snapshot.statuses, [id]: { phase: 'idle' } },
      hasMore: { ...snapshot.hasMore, [id]: false }, ...(snapshot.companionId === id ? { task: null } : {}), error: null });
    await refreshRoot();
  }
  return {
    getSnapshot: () => snapshot, subscribe(listener) { listeners.add(listener); return () => listeners.delete(listener); },
    getViewSnapshot: () => viewSnapshot, subscribeView(listener) { viewListeners.add(listener); return () => viewListeners.delete(listener); },
    subscribeDraft(id, listener) { if (!draftListeners.has(id)) draftListeners.set(id, new Set()); const set = draftListeners.get(id); set.add(listener); return () => { set.delete(listener); if (!set.size) draftListeners.delete(id); }; },
    initialize, refresh, refreshRoot, refreshMessages, api, profile, draft, setDraft, send, updateProfile, updateSettings, removeMessage,
    retryTurn, clearChat, updateUserProfile, conversationAction, markRead,
    open, back, report, showProfile: () => publish({ screen: 'profile', direction: 'forward', error: null }),
    showTask: task => publish({ screen: 'task', direction: 'forward', task, error: null }),
    cancel: id => api('/' + encodeURIComponent(id) + '/cancel', { method: 'POST', body: {} }).then(() => refreshMessages(id)),
    saveScroll(id, value) { scrollPositions.set(id, value); }, scrollFor: id => scrollPositions.get(id),
    saveListScroll(value) { listScroll = value; }, listScrollFor: () => listScroll,
    dispose() { disposed = true; events?.close(); listeners.clear(); viewListeners.clear(); draftListeners.clear(); messageRequests.clear(); statusEpochs.clear(); },
  };
}

function createCompanionVoiceRecovery({ state, recording }) {
  const operations = new Map();
  const pending = id => state.draft(id).retainedRecording || null;
  const voiceId = voice => voice?.attachmentId || voice?.id;
  const isCurrent = (id, retained) => voiceId(pending(id)?.voice) === voiceId(retained.voice);
  const clear = (id, retained) => { if (isCurrent(id, retained)) state.setDraft(id, { retainedRecording: null }); };
  function retain(id, error) {
    if (!error?.retainedVoice) return false;
    state.setDraft(id, { retainedRecording: { voice: error.retainedVoice,
      asText: typeof error.asText === 'boolean' ? error.asText : pending(id)?.asText === true } });
    return true;
  }
  function run(id, operation) {
    if (operations.has(id)) return operations.get(id);
    const promise = Promise.resolve().then(operation).finally(() => { if (operations.get(id) === promise) operations.delete(id); });
    operations.set(id, promise); return promise;
  }
  function originalMessage(retained) {
    return { text: '', attachments: [], voice: { ...retained.voice, transcriptionState: 'failed' },
      clientId: 'voice:' + voiceId(retained.voice) };
  }
  return {
    pending, retain,
    retry(id) { return run(id, async () => {
      const retained = pending(id); if (!retained) return;
      let result;
      try {
        result = await recording.retry({ companionId: id, voice: retained.voice, asText: retained.asText });
        if (!result?.text?.trim()) throw Object.assign(new Error('COMPANION_TRANSCRIPTION_EMPTY'), { retainedVoice: retained.voice, asText: retained.asText });
      } catch (error) { if (isCurrent(id, retained)) retain(id, error); throw error; }
      if (!isCurrent(id, retained)) return;
      if (retained.asText) {
        const draft = state.draft(id);
        state.setDraft(id, { text: [draft.text, result.text].filter(Boolean).join('\n'), retainedRecording: null });
      } else {
        const voice = { ...retained.voice, ...result.voice };
        if (voice.transcriptionState === 'failed') delete voice.transcriptionState;
        await state.send(id, { text: result.text, attachments: result.attachments || [], voice, clientId: 'voice:' + voiceId(voice) });
        clear(id, retained);
      }
      return result;
    }); },
    sendOriginal(id) { return run(id, async () => {
      const retained = pending(id); if (!retained) return;
      await state.send(id, originalMessage(retained)); clear(id, retained);
    }); },
    discard(id) { return run(id, async () => {
      const retained = pending(id); if (!retained) return;
      await recording?.discard?.({ companionId: id, voice: retained.voice }); clear(id, retained);
    }); },
    retryMessage(id, message) { return run('message:' + id + ':' + message.id, async () => {
      const result = await recording.retryMessage({ companionId: id, messageId: message.id, voice: message.voice });
      await state.refreshMessages(id); return result;
    }); },
  };
}

function createCompanionUi({ React, ctx, target = window, nativeBridge, api, media, recording } = {}) {
  const h = React.createElement, doc = target.document;
  const state = createCompanionClientState({ target, api });
  const voiceRecovery = createCompanionVoiceRecovery({ state, recording });
  const disposers = [];
  let activeAudio = null, initialized, disposed = false, localBack = () => false, captureBack = () => false;
  const locale = () => /^zh\b/i.test(ctx.locale?.getSnapshot?.().active || '') ? 'zh' : 'en';
  const t = (key, values = {}) => {
    const text = DROY_COMPANION_COPY[locale()][key] || DROY_COMPANION_COPY.en[key] || key;
    return text.replace(/\{(\w+)\}/g, (_, name) => String(values[name] ?? ''));
  };
  function useSnapshot() {
    if (ctx.locale) React.useSyncExternalStore(callback => ctx.locale.subscribe(callback), () => ctx.locale.getSnapshot(), () => ctx.locale.getSnapshot());
    return React.useSyncExternalStore(state.subscribeView, state.getViewSnapshot, state.getViewSnapshot);
  }
  function useDraft(id) {
    const subscribe = React.useCallback(listener => state.subscribeDraft(id, listener), [id]);
    const read = React.useCallback(() => state.draft(id), [id]);
    return React.useSyncExternalStore(subscribe, read, read);
  }
  function useModalFocus(open, element, close) {
    const closeRef = React.useRef(close); closeRef.current = close;
    React.useEffect(() => {
      if (!open) return;
      const previous = doc.activeElement;
      element.current?.querySelector('input,textarea,button')?.focus({ preventScroll: true });
      const keydown = event => {
        if (event.key === 'Escape') { event.preventDefault(); closeRef.current(); return; }
        if (event.key !== 'Tab') return;
        const fields = [...(element.current?.querySelectorAll('button:not(:disabled),input:not(:disabled),textarea:not(:disabled),[tabindex="0"]') || [])];
        if (!fields.length) return;
        const first = fields[0], last = fields.at(-1);
        if (event.shiftKey && doc.activeElement === first) { event.preventDefault(); last.focus(); }
        else if (!event.shiftKey && doc.activeElement === last) { event.preventDefault(); first.focus(); }
      };
      doc.addEventListener('keydown', keydown);
      return () => { doc.removeEventListener('keydown', keydown); if (previous?.isConnected) previous.focus({ preventScroll: true }); };
    }, [open]);
  }
  function boot() { return initialized ||= state.initialize().catch(() => { initialized = null; }); }
  const paths = {
    switch: 'M4 7h15l-4-4m4 14H4l4 4M19 7l-4 4M4 17l4-4', pin: 'm8 3 8 0-1 6 4 4H5l4-4-1-6M12 13v8',
    back: 'm15 5-7 7 7 7', down: 'm6 9 6 6 6-6', plus: 'M12 5v14M5 12h14', close: 'm6 6 12 12M18 6 6 18',
    more: 'M4 6h16M4 12h16M4 18h16', mic: 'M8 5a4 4 0 0 1 8 0v7a4 4 0 0 1-8 0ZM5 10v2a7 7 0 0 0 14 0v-2M12 19v4M8 23h8',
    keyboard: 'M3 5h18v14H3ZM6 9h1m3 0h1m3 0h1m3 0h1M6 12h1m3 0h1m3 0h1m3 0h1M7 16h10',
    smile: 'M21 12a9 9 0 1 1-18 0 9 9 0 0 1 18 0ZM8 9v1m8-1v1M8 14q4 5 8 0',
    photo: 'M3 3h18v18H3Zm0 14 5-5 5 5 3-3 5 5M8 7h.01', camera: 'M8 6 10 3h4l2 3h5v14H3V6ZM16 13a4 4 0 1 1-8 0 4 4 0 0 1 8 0Z',
    file: 'M5 3h9l5 5v13H5ZM14 3v6h5', send: 'm4 12 16-8-5 16-3-8-8 0Zm8 0 8-8',
    play: 'm9 5 10 7-10 7Z', pause: 'M8 5v14M16 5v14', text: 'M4 6h16M12 6v14M8 20h8',
    quote: 'M8 7H4v6h5v4M19 7h-4v6h5v4', memory: 'M4 4h7a4 4 0 0 1 4 4v13a4 4 0 0 0-4-4H4ZM15 8a4 4 0 0 1 4-4h1v13h-1a4 4 0 0 0-4 4',
    check: 'm5 12 4 4L19 6', stop: 'M6 6h12v12H6Z', settings: 'M4 7h16M4 17h16M8 4v6M16 14v6',
    user: 'M16 7a4 4 0 1 1-8 0 4 4 0 0 1 8 0ZM4 21v-2a8 8 0 0 1 16 0v2',
  };
  function Icon({ name, size = 24 }) {
    return h('svg', { width: size, height: size, viewBox: '0 0 24 24', fill: 'none', stroke: 'currentColor', strokeWidth: 1.7,
      strokeLinecap: 'round', strokeLinejoin: 'round', 'aria-hidden': true, focusable: false }, h('path', { d: paths[name] || paths.more }));
  }
  function iconButton(name, label, onClick, extra = {}) {
    return h('button', { type: 'button', className: 'droy-im-icon', 'aria-label': t(label), title: t(label), onClick, ...extra }, h(Icon, { name }));
  }
  function Avatar({ companion, small = false }) {
    return h('span', { className: 'droy-im-avatar' + (small ? ' is-small' : ''), 'aria-hidden': true,
      'data-portrait-avatar': companion.templateId === 'maimai' && /\/assets\/maimai\.png(?:[?#]|$)/.test(companion.avatarUrl || '') ? 'true' : undefined },
      companion.avatarUrl ? h('img', { src: companion.avatarUrl, alt: '', draggable: false }) : companion.name?.slice(0, 1));
  }
  const dateFormats = new Map();
  let currentTimeZone = new Intl.DateTimeFormat().resolvedOptions().timeZone;
  function dateLabel(value, date = false) {
    const parsed = new Date(value);
    if (!Number.isFinite(parsed.getTime())) return '';
    const key = locale() + ':' + date + ':' + currentTimeZone;
    if (!dateFormats.has(key)) dateFormats.set(key, new Intl.DateTimeFormat(locale() === 'zh' ? 'zh-CN' : 'en', date
      ? { month: 'numeric', day: 'numeric', timeZone: currentTimeZone } : { hour: '2-digit', minute: '2-digit', hourCycle: 'h23', timeZone: currentTimeZone }));
    return dateFormats.get(key).format(parsed);
  }
  const phaseText = phase => ['thinking', 'typing'].includes(phase) ? 'typing' : null;
  const backgroundUrl = background => background?.type === 'preset' && /^0[1-5]$/.test(background.id)
    ? '/droy/companions/assets/wallpaper-' + background.id + '.webp' : background?.type === 'upload' ? background.url : undefined;
  function ErrorNotice({ error, reload }) {
    const text = typeof error === 'string' && !/^[A-Z][A-Z0-9_:. -]*$/.test(error) ? error : t('requestFailed');
    return error ? h('div', { className: 'droy-im-error', role: 'alert' }, h('span', null, text),
      reload ? h('button', { type: 'button', onClick: reload }, t('retry')) : null) : null;
  }
  function ModeMenu({ from = 'messages', snapshot }) {
    const destination = from === 'messages' ? 'work' : 'messages';
    const unread = snapshot.companions.filter(item => !item.hidden).reduce((total, item) => total + (item.unread || 0), 0);
    const switchMode = () => {
      doc.activeElement?.blur?.();
      if (nativeBridge) nativeBridge.request('hideKeyboard').catch(state.report);
      doc.body?.setAttribute('data-droy-mode-enter', destination);
      if (destination === 'messages') { state.open(); ctx.layout.selectPanel('droy-companions'); }
      else ctx.layout.selectPanel(null);
    };
    return h('div', { className: 'droy-im-mode' },
      h('button', { type: 'button', className: 'droy-im-mode-trigger', 'aria-label': t('switchTo', { mode: t(destination) }),
        onClick: switchMode }, t(from), h(Icon, { name: 'switch', size: 18 }),
        unread > 0 ? h('span', { className: 'droy-im-unread droy-im-mode-unread', 'aria-label': t('unreadCount', { count: unread }) }, unread > 99 ? '99+' : unread) : null));
  }
  function WorkModeSwitch() { const snapshot = useSnapshot(); return h(ModeMenu, { from: 'work', snapshot }); }
  function Messages({ snapshot }) {
    const list = React.useRef(null), pointer = React.useRef(null), suppressClick = React.useRef(false), menuElement = React.useRef(null);
    const actionTouch = React.useRef(null), actionClick = React.useRef(null), actionBusy = React.useRef(false);
    const [revealed, setRevealed] = React.useState(null), [menu, setMenu] = React.useState(null), [pending, setPending] = React.useState(false);
    const rows = companionConversations(snapshot.companions);
    const clearHold = () => { if (pointer.current?.timer) target.clearTimeout(pointer.current.timer); };
    const closeMenu = () => { if (!pending) setMenu(null); };
    useModalFocus(Boolean(menu), menuElement, closeMenu);
    React.useLayoutEffect(() => {
      const node = list.current; if (node) node.scrollTop = state.listScrollFor();
      return () => { if (node) state.saveListScroll(node.scrollTop); };
    }, []);
    React.useEffect(() => {
      localBack = () => { if (menu) { closeMenu(); return true; } if (revealed) { setRevealed(null); return true; } return false; };
      return () => { localBack = () => false; clearHold(); };
    }, [menu, revealed, pending]);
    async function operate(id, action) {
      if (actionBusy.current) return;
      actionBusy.current = true; setPending(true);
      try { await state.conversationAction(id, action); setMenu(null); setRevealed(null); }
      catch (error) { state.report(error); }
      finally { actionBusy.current = false; setPending(false); }
    }
    function actionHandlers(id, action) {
      const key = id + ':' + action;
      const slop = Math.max(4, Number(target.__droyTouchConfig?.touchSlopCssPx) || 8);
      return {
        onPointerDown(event) {
          actionClick.current = null;
          actionTouch.current = event.pointerType === 'touch' && event.isPrimary !== false
            ? { key, pointerId: event.pointerId, x: event.clientX, y: event.clientY, cancelled: false } : null;
        },
        onPointerMove(event) {
          const tap = actionTouch.current;
          if (tap?.key === key && Math.hypot(event.clientX - tap.x, event.clientY - tap.y) > slop) tap.cancelled = true;
        },
        onPointerCancel() { actionTouch.current = null; actionClick.current = key; },
        onPointerUp(event) {
          const tap = actionTouch.current; actionTouch.current = null;
          if (!tap || tap.key !== key || tap.pointerId !== event.pointerId) return;
          actionClick.current = key;
          event.preventDefault();
          const bounds = event.currentTarget.getBoundingClientRect();
          if (tap.cancelled || event.currentTarget.disabled || Math.hypot(event.clientX - tap.x, event.clientY - tap.y) > slop
            || event.clientX < bounds.left || event.clientX > bounds.right || event.clientY < bounds.top || event.clientY > bounds.bottom) return;
          // WebView can omit click after a swipe; a completed tap owns activation.
          operate(id, action);
        },
        onClick(event = {}) {
          if (event.detail !== 0 && actionClick.current === key) { actionClick.current = null; event.preventDefault?.(); return; }
          actionClick.current = null; operate(id, action);
        },
      };
    }
    function start(event, companion) {
      if (event.button !== undefined && event.button !== 0) return;
      clearHold(); suppressClick.current = false;
      if (revealed && revealed !== companion.id) setRevealed(null);
      const current = { x: event.clientX, y: event.clientY, id: companion.id, swiping: false, moved: false };
      current.timer = target.setTimeout(() => { suppressClick.current = true; setRevealed(null); setMenu(companion); }, 550);
      pointer.current = current;
    }
    function move(event) {
      const current = pointer.current; if (!current) return;
      const dx = event.clientX - current.x, dy = event.clientY - current.y;
      if (Math.hypot(dx, dy) > 10) { clearHold(); current.moved = true; }
      if (Math.abs(dx) > 16 && Math.abs(dx) > Math.abs(dy) * 1.3) {
        current.swiping = true; suppressClick.current = true;
        event.currentTarget.setPointerCapture?.(event.pointerId);
        if (dx < -36) setRevealed(current.id); else if (dx > 36) setRevealed(null);
      }
    }
    function end() { clearHold(); pointer.current = null; }
    return h('section', { className: 'droy-im-screen droy-im-list-screen', 'data-droy-im-screen': 'list' },
      h('header', { className: 'droy-im-header' }, h(ModeMenu, { snapshot }),
        iconButton('settings', 'settings', () => target.dispatchEvent(new target.CustomEvent('droy-mobile-open-settings', { detail: { section: 'general' } })))),
      h('div', { className: 'droy-im-conversation-list', ref: list, onScroll: () => { clearHold(); if (list.current) state.saveListScroll(list.current.scrollTop); } },
        snapshot.loading && !snapshot.companions.length ? h('p', { className: 'droy-im-empty', role: 'status' }, t('loading')) : null,
        ...rows.map(companion => {
          const draft = state.draft(companion.id), last = companion.lastMessage, open = revealed === companion.id;
          const preview = draft.text ? draft.text : typeof last === 'string' ? last : last?.text || (last?.type === 'voice' ? t('voiceMessage') : last?.type === 'sticker' ? t('stickers') : '');
          return h('div', { className: 'droy-im-conversation-row' + (open ? ' is-open' : '') + (companion.pinned ? ' is-pinned' : ''), key: companion.id, 'data-conversation-id': companion.id },
            h('div', { className: 'droy-im-conversation-actions', 'aria-hidden': !open },
              h('button', { type: 'button', 'data-conversation-action': 'read', tabIndex: open ? 0 : -1, disabled: pending || !companion.unread,
                ...actionHandlers(companion.id, 'read') }, t('markRead')),
              h('button', { type: 'button', 'data-conversation-action': 'hide', tabIndex: open ? 0 : -1, disabled: pending,
                ...actionHandlers(companion.id, 'hide') }, t('delete'))),
            h('button', { className: 'droy-im-conversation', type: 'button', 'data-droy-companion': companion.id,
              'aria-haspopup': 'dialog',
              onPointerDown: event => start(event, companion), onPointerMove: move, onPointerUp: end,
              onPointerCancel: end, onContextMenu: event => { event.preventDefault(); clearHold(); suppressClick.current = true; setMenu(companion); },
              onKeyDown: event => { if (event.key === 'ContextMenu' || event.shiftKey && event.key === 'F10') { event.preventDefault(); setMenu(companion); } },
              onClick: event => { if (suppressClick.current) { suppressClick.current = false; event.preventDefault(); return; } if (revealed) { setRevealed(null); return; } state.open(companion.id); } },
              h(Avatar, { companion }), h('span', { className: 'droy-im-conversation-copy' },
                h('span', { className: 'droy-im-conversation-heading' }, h('strong', null, companion.name), companion.pinned ? h(Icon, { name: 'pin', size: 14 }) : null,
                  h('time', { dateTime: companion.lastActivityAt || last?.createdAt }, dateLabel(companion.lastActivityAt || last?.createdAt))),
                h('span', { className: 'droy-im-conversation-preview' }, draft.text ? h('span', { className: 'droy-im-draft-label' }, t('draft') + ' · ') : null, preview)),
              companion.unread > 0 ? h('span', { className: 'droy-im-unread', 'aria-label': t('unreadCount', { count: companion.unread }) }, companion.unread > 99 ? '99+' : companion.unread) : null));
        }),
        !snapshot.loading && !rows.length ? h('p', { className: 'droy-im-empty' }, t(snapshot.companions.length ? 'emptyConversations' : 'emptyList')) : null,
        h(ErrorNotice, { error: snapshot.error, reload: () => state.refresh().catch(state.report) })),
      menu ? h('div', { className: 'droy-im-modal-shade', onClick: event => { if (event.target === event.currentTarget) closeMenu(); } },
        h('div', { className: 'droy-im-message-menu', ref: menuElement, role: 'dialog', 'aria-modal': true, 'aria-label': menu.name, 'data-droy-conversation-menu': menu.id },
          h('button', { type: 'button', disabled: pending, 'data-conversation-action': menu.pinned ? 'unpin' : 'pin', onClick: () => operate(menu.id, menu.pinned ? 'unpin' : 'pin') }, t(menu.pinned ? 'unpinConversation' : 'pinConversation')),
          h('button', { type: 'button', disabled: pending || !menu.unread, 'data-conversation-action': 'read', onClick: () => operate(menu.id, 'read') }, t('markRead')),
          h('button', { type: 'button', className: 'is-danger', disabled: pending, 'data-conversation-action': 'hide', onClick: () => operate(menu.id, 'hide') }, t('delete')),
          h('button', { type: 'button', disabled: pending, onClick: closeMenu }, t('cancel')))) : null);
  }
  function VoiceBubble({ message, companionId, retryable = true }) {
    const [playing, setPlaying] = React.useState(false), [expanded, setExpanded] = React.useState(false), [error, setError] = React.useState(false);
    const [retrying, setRetrying] = React.useState(false), [retryError, setRetryError] = React.useState(false);
    const audio = React.useRef(null);
    const voice = message.voice || message.attachments?.find(item => item.mediaType?.startsWith('audio/')) || {};
    const url = voice.url || voice.audioUrl || message.audioUrl;
    const unrecognized = voice.transcriptionState === 'failed';
    React.useEffect(() => () => { if (activeAudio === audio.current) activeAudio = null; audio.current?.pause(); }, []);
    const toggle = async () => {
      if (!audio.current || !url) { setError(true); return; }
      if (playing) audio.current.pause();
      else {
        if (activeAudio && activeAudio !== audio.current) activeAudio.pause();
        activeAudio = audio.current;
        try { await audio.current.play(); setError(false); } catch { setError(true); }
      }
    };
    return h('div', { className: 'droy-im-voice' },
      h('button', { type: 'button', className: 'droy-im-voice-play', onClick: toggle, 'aria-label': t(playing ? 'pauseVoice' : 'playVoice') },
        h(Icon, { name: playing ? 'pause' : 'play', size: 20 }), h('span', { className: 'droy-im-voice-bars', 'aria-hidden': true }, ...[10, 18, 12, 24, 16, 20, 12, 18].map((height, i) => h('i', { key: i, style: { height } }))),
        h('span', null, Math.max(1, Math.round((voice.durationMs || message.durationMs || 1000) / 1000)) + '″')),
      h('audio', { ref: audio, src: url, preload: 'none', onPlay: () => setPlaying(true), onPause: () => setPlaying(false), onEnded: () => setPlaying(false), onError: () => setError(true) }),
      message.text ? h('button', { type: 'button', className: 'droy-im-transcript-toggle', onClick: () => setExpanded(!expanded), 'aria-expanded': expanded }, t(expanded ? 'hideTranscript' : 'showTranscript')) : null,
      expanded ? h('p', { className: 'droy-im-transcript' }, message.text) : null,
      unrecognized && retryable ? h('div', { className: 'droy-im-transcription-state' },
        h('span', { role: 'status' }, t(retrying ? 'transcribing' : retryError ? 'recognitionRetryFailed' : 'unrecognizedVoice')),
        recording?.retryMessage ? h('button', { type: 'button', className: 'droy-im-transcript-toggle', disabled: retrying, onClick: async () => {
          if (retrying) return;
          setRetrying(true); setRetryError(false);
          try { await voiceRecovery.retryMessage(companionId, { ...message, voice }); }
          catch { setRetryError(true); }
          finally { setRetrying(false); }
        } }, t('retryRecognition')) : null) : null,
      error ? h('span', { className: 'droy-im-voice-error', role: 'status' }, t('audioUnavailable')) : null);
  }
  function TaskCard({ task }) {
    const status = { queued: 'taskQueued', running: 'taskRunning', completed: 'taskCompleted', failed: 'taskFailed', cancelled: 'taskCancelled', needs_input: 'taskNeedsInput', cancelling: 'taskCancelling' }[task.state || task.status || task.phase] || 'task';
    const result = typeof task.result === 'string' ? task.result : task.result?.text || task.result?.summary || task.summary || '';
    return h('button', { type: 'button', className: 'droy-im-task-card', onClick: () => state.showTask(task) },
      h('span', { className: 'droy-im-task-kind' }, t('task')), h('strong', null, task.title || task.summary || t('task')),
      h('span', { className: 'droy-im-task-status' }, t(status)), result ? h('span', { className: 'droy-im-task-result' }, result) : null);
  }
  function Message({ message, companion, onMenu, onUserProfile, userProfile, original, sticker, animate = false, grouped = false }) {
    const hold = React.useRef(null), pointer = React.useRef(null);
    const [entering, setEntering] = React.useState(animate);
    React.useEffect(() => () => target.clearTimeout(hold.current), []);
    const openMenu = event => { event?.preventDefault?.(); onMenu(message); };
    const quoted = message.replyTo && (message.role === 'user' || message.quoteReply !== false);
    const time = h('time', { className: 'droy-im-message-time', dateTime: message.createdAt }, dateLabel(message.createdAt));
    return h('article', { className: 'droy-im-message is-' + message.role + (message.status === 'pending' ? ' is-pending' : '') + (entering ? ' is-entering' : '') + (grouped ? ' is-grouped' : ''),
      'data-message-id': message.id, 'data-message-status': message.status,
      onAnimationEnd: () => setEntering(false),
      onContextMenu: openMenu, onPointerDown: event => { pointer.current = { x: event.clientX, y: event.clientY }; hold.current = target.setTimeout(() => openMenu(), 550); },
      onPointerUp: () => target.clearTimeout(hold.current), onPointerCancel: () => target.clearTimeout(hold.current),
      onPointerMove: event => { if (pointer.current && Math.hypot(event.clientX - pointer.current.x, event.clientY - pointer.current.y) > 10) target.clearTimeout(hold.current); } },
      message.role !== 'user' ? h('button', { type: 'button', className: 'droy-im-companion-avatar', 'aria-label': t('profileOf', { name: companion.name }), 'data-droy-im-action': 'companion-profile',
        onPointerDown: event => event.stopPropagation(), onClick: event => { event.stopPropagation(); state.showProfile(); } }, h(Avatar, { companion, small: true })) : null,
      h('div', { className: 'droy-im-message-content' },
        message.type === 'task' && message.task ? h('div', { className: 'droy-im-media-message' }, h(TaskCard, { task: message.task }), time) :
          message.type === 'voice' ? h('div', { className: 'droy-im-bubble' }, quoted ? h('div', { className: 'droy-im-message-quote' }, original?.text || (original === null ? t('quotedUnavailable') : t('attachment'))) : null, h(VoiceBubble, { message, companionId: companion.id }), time) :
            message.type === 'sticker' ? h('div', { className: 'droy-im-media-message is-sticker' },
              quoted ? h('div', { className: 'droy-im-message-quote' }, original?.text || (original === null ? t('quotedUnavailable') : t('attachment'))) : null,
              h('img', { className: 'droy-im-sticker-message', src: message.url || message.stickerUrl || sticker?.url, alt: sticker?.label || t('stickers'), loading: 'lazy', decoding: 'async' }), time) :
              message.text ? h('div', { className: 'droy-im-bubble' },
                quoted ? h('div', { className: 'droy-im-message-quote' }, original?.text || (original === null ? t('quotedUnavailable') : t('attachment'))) : null,
                h('span', { className: 'droy-im-message-text' }, message.text), time) : null,
        ...(message.attachments || []).filter(item => !item.mediaType?.startsWith('audio/')).map((attachment, index) => attachment.mediaType?.startsWith('image/') || attachment.type === 'image'
          ? h('a', { href: attachment.url, target: '_blank', rel: 'noreferrer', className: 'droy-im-picture', key: attachment.id || index, 'aria-label': t('previewImage') },
            h('img', { src: attachment.url, alt: attachment.name || t('image'), loading: 'lazy', decoding: 'async', width: attachment.width, height: attachment.height,
              style: { aspectRatio: attachment.width && attachment.height ? attachment.width + ' / ' + attachment.height : '4 / 3' } }), time)
          : h('a', { className: 'droy-im-file', href: attachment.url, download: attachment.name, key: attachment.id || index }, h(Icon, { name: 'file', size: 22 }), h('span', null, attachment.name || t('attachment')), time)),
        message.status === 'pending' ? h('span', { className: 'droy-im-message-status', role: 'status' }, t('sending')) : null,
        message.status === 'failed' ? h('button', { type: 'button', className: 'droy-im-retry', onClick: () => state.send(companion.id, { ...message, clientId: message.clientId || message.id }).catch(() => {}) }, t('sendAgain')) : null),
      message.role === 'user' ? h('button', { type: 'button', className: 'droy-im-avatar is-small is-user', 'aria-label': t('yourProfile'), 'data-droy-im-action': 'user-profile',
        onPointerDown: event => event.stopPropagation(), onClick: event => { event.stopPropagation(); onUserProfile(); } },
        h('img', { src: userProfile.avatarUrl || DEFAULT_USER_PROFILE.avatarUrl, alt: userProfile.name || t('you'), draggable: false, decoding: 'async' })) : null);
  }
  const MemoMessage = React.memo(Message);
  function MessageRows({ messages, companion, userProfile, stickers, onMenu, onUserProfile, incoming, language }) {
    const originals = new Map(messages.map(message => [message.id, message]));
    const stickerMap = new Map(stickers.map(sticker => [sticker.id, sticker]));
    return messages.map((message, index) => {
      const previous = messages[index - 1];
      const newDay = !previous || new Date(message.createdAt).toDateString() !== new Date(previous.createdAt).toDateString();
      return h(React.Fragment, { key: message.id }, newDay ? h('time', { className: 'droy-im-timestamp', dateTime: message.createdAt }, dateLabel(message.createdAt, true)) : null,
        h(MemoMessage, { message, companion, userProfile, onMenu, onUserProfile, language,
          original: Object.hasOwn(message, 'replyPreview') ? message.replyPreview : (typeof message.replyTo === 'string' ? originals.get(message.replyTo) : message.replyTo),
          sticker: stickerMap.get(message.stickerId), animate: incoming.has(message.id), grouped: !newDay && previous.role === message.role }));
    });
  }
  const MemoMessageRows = React.memo(MessageRows);
  function Composer({ companion, stickers, panel, setPanel }) {
    const draft = useDraft(companion.id), input = React.useRef(null), handle = React.useRef(null), gesture = React.useRef(null), operation = React.useRef(0);
    const quote = draft.replyTo;
    const lastPanel = React.useRef(panel); if (panel) lastPanel.current = panel;
    const panelContent = panel || lastPanel.current;
    const preparingRecording=React.useRef(null);
    const [voiceMode, setVoiceMode] = React.useState(false), [capture, setCapture] = React.useState(null), [busy, setBusy] = React.useState(false);
    const [recoveryAction, setRecoveryAction] = React.useState(null), [recoveryError, setRecoveryError] = React.useState(false);
    React.useLayoutEffect(() => { const node = input.current; if (node) { node.style.height = 'auto'; node.style.height = Math.min(node.scrollHeight, 132) + 'px'; } }, [draft.text]);
    const cancelRecording = React.useCallback(() => {
      operation.current++; gesture.current = null;
      preparingRecording.current?.abort();preparingRecording.current=null;
      const current = handle.current; handle.current = null; setCapture(null);
      if (current) Promise.resolve(current.cancel()).catch(state.report);
    }, []);
    React.useEffect(() => {
      captureBack = capture ? () => { cancelRecording(); return true; } : () => false;
      return () => { captureBack = () => false; };
    }, [capture, cancelRecording]);
    React.useEffect(() => {
      const hide = () => { if (doc.visibilityState === 'hidden') cancelRecording(); };
      const blur = () => cancelRecording();
      doc.addEventListener('visibilitychange', hide); target.addEventListener('blur', blur);
      return () => { cancelRecording(); doc.removeEventListener('visibilitychange', hide); target.removeEventListener('blur', blur); };
    }, [cancelRecording]);
    React.useEffect(() => { if (!capture?.startedAt) return; const timer = target.setInterval(() => setCapture(previous => previous ? { ...previous, now: Date.now() } : null), 250); return () => target.clearInterval(timer); }, [capture?.startedAt]);
    async function beginRecording(event) {
      if (state.draft(companion.id).retainedRecording) return;
      if (!recording || busy) { state.report(new Error(t('recordingUnavailable'))); return; }
      event.preventDefault(); activeAudio?.pause();
      if (typeof event.pointerId === 'number') event.currentTarget.setPointerCapture?.(event.pointerId);
      const current = ++operation.current; gesture.current = { x: event.clientX, y: event.clientY, intent: 'send' };
      setCapture({ preparing: true, intent: 'send' });
      const preparing=new target.AbortController();preparingRecording.current=preparing;
      try {
        const next = await recording.start({ companionId: companion.id, signal:preparing.signal });
        if(preparingRecording.current===preparing)preparingRecording.current=null;
        if (current !== operation.current || !gesture.current) { await next.cancel(); return; }
        handle.current = next; setCapture({ startedAt: Date.now(), now: Date.now(), intent: gesture.current.intent });
      } catch (error) { if(preparingRecording.current===preparing)preparingRecording.current=null;if (current === operation.current) { gesture.current = null; setCapture(null); state.report(error); } }
    }
    function moveRecording(event) {
      if (!gesture.current) return;
      const dx = event.clientX - gesture.current.x, dy = event.clientY - gesture.current.y;
      const intent = dx > 65 ? 'text' : dx < -65 || dy < -85 ? 'cancel' : 'send';
      gesture.current.intent = intent;
      // React can replay this updater after cancellation clears the gesture ref.
      setCapture(previous => previous ? { ...previous, intent } : null);
    }
    async function finishRecording(forcedIntent) {
      const intent = forcedIntent || gesture.current?.intent || 'cancel';
      if (!handle.current || intent === 'cancel') { cancelRecording(); return; }
      gesture.current = null; operation.current++;
      const current = handle.current; handle.current = null; setCapture(null); setBusy(true);
      try {
        const result = await current.finish({ asText: intent === 'text' });
        if (intent === 'text') {
          state.setDraft(companion.id, { text: [state.draft(companion.id).text, result.text].filter(Boolean).join('\n') });
          setVoiceMode(false); target.requestAnimationFrame(() => input.current?.focus());
        } else if (result.voice || result.text || result.attachments?.length)
          await state.send(companion.id, { ...result, attachments: result.attachments || [] });
      } catch (error) {
        if (error.retainedVoice) {
          voiceRecovery.retain(companion.id, { retainedVoice: error.retainedVoice, asText: intent === 'text' });
          setRecoveryError(false);
        } else state.report(error);
      }
      finally { setBusy(false); }
    }
    async function recoverVoice(action) {
      if (busy) return;
      const retained = voiceRecovery.pending(companion.id); if (!retained) return;
      setBusy(true); setRecoveryAction(action); setRecoveryError(false);
      try {
        if (action === 'retry') {
          await voiceRecovery.retry(companion.id);
          if (retained.asText && !voiceRecovery.pending(companion.id)) {
            setVoiceMode(false); target.requestAnimationFrame(() => input.current?.focus());
          }
        } else if (action === 'send') await voiceRecovery.sendOriginal(companion.id);
        else await voiceRecovery.discard(companion.id);
      } catch (error) {
        if (action === 'retry') setRecoveryError(true);
        else state.report(error);
      } finally { setRecoveryAction(null); setBusy(false); }
    }
    async function pick(kind) {
      if (!media?.pick) { state.report(new Error(t('selectUnavailable'))); return; }
      setPanel(null); setBusy(true);
      try { const selected = await media.pick(kind, { companionId: companion.id, remaining: Math.max(0, 10 - state.draft(companion.id).attachments.length) }); if (selected?.length) state.setDraft(companion.id, { attachments: [...state.draft(companion.id).attachments, ...selected] }); }
      catch (error) {
        if (error.retainedUploads?.length) {
          const attachments = state.draft(companion.id).attachments;
          const ids = new Set(attachments.map(item => item.id));
          state.setDraft(companion.id, { attachments: [...attachments, ...error.retainedUploads.filter(item => !ids.has(item.id))] });
        }
        state.report(error);
      } finally { setBusy(false); }
    }
    function togglePanel(next) { input.current?.blur(); setPanel(panel === next ? null : next); }
    const canSend = draft.text.trim() || draft.attachments.length;
    return h('div', { className: 'droy-im-composer-wrap', 'data-droy-im-input-mode': panel || (voiceMode ? 'voice' : 'keyboard') },
      draft.retainedRecording ? h('section', { className: 'droy-im-retained-voice', 'data-droy-retained-voice': true, 'aria-label': t('recordingKept') },
        h('strong', { role: 'status' }, t(recoveryAction === 'retry' ? 'transcribing' : recoveryError ? 'recognitionRetryFailed' : 'recordingKept')),
        h(VoiceBubble, { message: { voice: draft.retainedRecording.voice }, companionId: companion.id, retryable: false }),
        h('p', null, t(draft.retainedRecording.asText ? 'retainedTextHint' : 'retainedVoiceHint')),
        h('div', { className: 'droy-im-retained-actions' },
          h('button', { type: 'button', disabled: busy || !recording?.retry, onClick: () => recoverVoice('retry') }, t('retryRecognition')),
          h('button', { type: 'button', disabled: busy, onClick: () => recoverVoice('send') }, t('sendOriginalVoice')),
          h('button', { type: 'button', disabled: busy, onClick: () => recoverVoice('discard') }, t('cancel')))) : null,
      quote ? h('div', { className: 'droy-im-compose-quote' }, h(Icon, { name: 'quote', size: 16 }), h('span', null, quote.text || t('attachment')),
        iconButton('close', 'dismissQuote', () => state.setDraft(companion.id, { replyTo: null }))) : null,
      draft.attachments.length ? h('div', { className: 'droy-im-draft-attachments' }, ...draft.attachments.map((item, index) =>
        h('div', { className: 'droy-im-draft-file', key: item.id || index }, item.mediaType?.startsWith('image/') ? h('img', { src: item.url, alt: item.name || t('image') }) : h(Icon, { name: 'file', size: 20 }),
          h('span', null, item.name || t('attachment')), h('button', { type: 'button', 'aria-label': t('removeAttachment', { name: item.name || t('attachment') }),
            onClick: () => state.setDraft(companion.id, { attachments: state.draft(companion.id).attachments.filter((_, itemIndex) => itemIndex !== index) }) }, h(Icon, { name: 'close', size: 15 }))))) : null,
      h('div', { className: 'droy-im-composer' },
        iconButton(voiceMode ? 'keyboard' : 'mic', voiceMode ? 'textMode' : 'voiceMode', () => { setPanel(null); setVoiceMode(!voiceMode); if (voiceMode) target.requestAnimationFrame(() => input.current?.focus()); }),
        voiceMode ? h('button', { type: 'button', className: 'droy-im-hold', disabled: busy || Boolean(draft.retainedRecording), onPointerDown: beginRecording, onPointerMove: moveRecording,
          onPointerUp: () => finishRecording(), onPointerCancel: cancelRecording, onKeyDown: event => { if (event.code === 'Space' && !event.repeat) beginRecording(event); }, onKeyUp: event => { if (event.code === 'Space') finishRecording(); } }, busy ? t('transcribing') : t('holdToTalk')) :
          h('textarea', { ref: input, className: 'droy-im-input', rows: 1, value: draft.text, 'aria-label': t('messageInput'), placeholder: t('messageInput'),
            onFocus: () => setPanel(null), onChange: event => state.setDraft(companion.id, { text: event.target.value }),
            onKeyDown: event => { if (event.key === 'Enter' && !event.shiftKey && !event.nativeEvent?.isComposing && !event.isComposing && (event.ctrlKey || event.metaKey)) { event.preventDefault(); state.send(companion.id).catch(() => {}); } } }),
        iconButton('smile', 'stickers', () => togglePanel('stickers'), { 'aria-expanded': panel === 'stickers', 'aria-pressed': panel === 'stickers' }),
        canSend ? h('button', { type: 'button', className: 'droy-im-send', 'aria-label': t('send'), onClick: () => state.send(companion.id).catch(() => {}) }, h(Icon, { name: 'send', size: 20 })) :
          iconButton(panel === 'attachments' ? 'close' : 'plus', 'attachments', () => togglePanel('attachments'), { 'aria-expanded': panel === 'attachments' })),
      h('div', { className: 'droy-im-input-panel' + (panel ? ' is-open' : ''), 'aria-hidden': !panel, inert: !panel ? '' : undefined },
        panelContent === 'stickers' ? h('div', { className: 'droy-im-sticker-grid', role: 'group', 'aria-label': t('stickers') }, ...stickers.map(sticker =>
          h('button', { type: 'button', key: sticker.id, onClick: () => state.send(companion.id, { stickerId: sticker.id, text: '', attachments: [] }).catch(() => {}), 'aria-label': sticker.label || t('stickers') }, h('img', { src: sticker.url, alt: sticker.label || '', draggable: false })))) :
          panelContent === 'attachments' ? h('div', { className: 'droy-im-attachment-grid' }, ...[['album', 'photo'], ['camera', 'camera'], ['file', 'file']].map(([kind, icon]) =>
            h('button', { type: 'button', key: kind, disabled: busy, onClick: () => pick(kind) }, h('span', null, h(Icon, { name: icon, size: 29 })), h('span', null, t(kind))))) : null),
      capture ? h('div', { className: 'droy-im-recording is-' + capture.intent, role: 'dialog', 'aria-modal': true, 'aria-label': t('recording') },
        h('div', { className: 'droy-im-record-card', role: 'status' }, h('strong', null, capture.preparing ? t('recordingPermission') :
          Math.floor((capture.now - capture.startedAt) / 60000).toString().padStart(2, '0') + ':' + (Math.floor((capture.now - capture.startedAt) / 1000) % 60).toString().padStart(2, '0')),
          h('span', { className: 'droy-im-record-wave', 'aria-hidden': true }, ...Array.from({ length: 15 }, (_, index) => h('i', { key: index, style: { '--bar-index': index } })))),
        h('p', { className: 'droy-im-record-hint' }, t(capture.intent === 'cancel' ? 'releaseCancel' : capture.intent === 'text' ? 'releaseText' : 'releaseSend')),
        h('p', { className: 'droy-im-record-guide' }, t('slideHint')),
        h('div', { className: 'droy-im-record-actions' }, iconButton('close', 'cancel', cancelRecording), iconButton('mic', 'stopRecording', () => finishRecording('send')), iconButton('text', 'toText', () => finishRecording('text')))) : null);
  }
  const MemoComposer = React.memo(Composer);
  function Chat({ companion, snapshot }) {
    const list = React.useRef(null), nearBottom = React.useRef(true), previousId = React.useRef(null), prepend = React.useRef(null), seenMessages = React.useRef(null);
    const [panel, setPanel] = React.useState(null), [menu, setMenu] = React.useState(null), [newMessages, setNewMessages] = React.useState(false), [loadingEarlier, setLoadingEarlier] = React.useState(false), [image, setImage] = React.useState(null), [userProfileOpen, setUserProfileOpen] = React.useState(false);
    const messages = snapshot.messages[companion.id] || EMPTY_COMPANION_MESSAGES, status = snapshot.statuses[companion.id] || {};
    const incoming = React.useMemo(() => {
      const added = new Set();
      if (seenMessages.current && !prepend.current) for (const message of messages)
        if (!seenMessages.current.has(message.id) && !seenMessages.current.has(message.clientId)) added.add(message.id);
      if (snapshot.messages[companion.id]) seenMessages.current = new Set(messages.flatMap(message => [message.id, message.clientId].filter(Boolean)));
      return added;
    }, [messages]);
    const userProfileBusy = React.useRef(false);
    const openUserProfile = React.useCallback(() => setUserProfileOpen(true), []);
    const closeUserProfile = React.useCallback(() => { if (!userProfileBusy.current) setUserProfileOpen(false); }, []);
    const setUserProfileBusy = React.useCallback(value => { userProfileBusy.current = value; }, []);
    const readVisible = React.useCallback(() => {
      const node = list.current;
      if (!node || doc.visibilityState === 'hidden' || !node.getClientRects().length) return;
      const bounds = node.getBoundingClientRect();
      const last = [...node.querySelectorAll('[data-message-id]')].filter(element => {
        if (['pending', 'failed'].includes(element.getAttribute('data-message-status'))) return false;
        const rect = element.getBoundingClientRect(); return rect.bottom > bounds.top && rect.bottom <= bounds.bottom + 1;
      }).at(-1);
      if (last) state.markRead(companion.id, last.getAttribute('data-message-id'));
    }, [companion.id, messages]);
    React.useEffect(() => {
      doc.addEventListener('visibilitychange', readVisible);
      return () => doc.removeEventListener('visibilitychange', readVisible);
    }, [readVisible]);
    React.useLayoutEffect(() => {
      const node = list.current; if (!node) return;
      if (previousId.current !== companion.id) { node.scrollTop = state.scrollFor(companion.id) ?? node.scrollHeight; previousId.current = companion.id; setNewMessages(false); }
      else if (prepend.current) { if (!loadingEarlier) { node.scrollTop += node.scrollHeight - prepend.current.height; prepend.current = null; } }
      else if (nearBottom.current || messages.at(-1)?.role === 'user' && messages.at(-1)?.status === 'pending') node.scrollTop = node.scrollHeight;
      else setNewMessages(true);
      readVisible();
    }, [companion.id, messages, loadingEarlier]);
    // Read before DOM removal; detached scroll containers report zero.
    React.useLayoutEffect(() => { const id = companion.id, node = list.current; return () => { if (node) state.saveScroll(id, node.scrollTop); }; }, [companion.id]);
    React.useEffect(() => {
      localBack = () => { if (userProfileOpen) { closeUserProfile(); return true; } if (image) { setImage(null); return true; } if (menu) { setMenu(null); return true; } if (panel) { setPanel(null); return true; } return false; };
      const back = event => { if (localBack()) event.preventDefault?.(); };
      target.addEventListener('droy-companion-back', back); return () => target.removeEventListener('droy-companion-back', back);
    }, [panel, menu, image, userProfileOpen]);
    React.useEffect(() => () => { localBack = () => false; }, []);
    async function earlier() {
      if (loadingEarlier || !messages.length) return;
      setLoadingEarlier(true); prepend.current = { height: list.current?.scrollHeight || 0 };
      try { await state.refreshMessages(companion.id, { before: messages[0].id }); } catch (error) { prepend.current = null; state.report(error); } finally { setLoadingEarlier(false); }
    }
    const toBottom = () => { if (list.current) list.current.scrollTo({ top: list.current.scrollHeight, behavior: target.matchMedia?.('(prefers-reduced-motion: reduce)').matches ? 'auto' : 'smooth' }); nearBottom.current = true; setNewMessages(false); };
    const handleBack = () => { if (menu) setMenu(null); else if (panel) setPanel(null); else { state.saveScroll(companion.id, list.current?.scrollTop || 0); state.back(); } };
    return h('section', { className: 'droy-im-screen droy-im-chat-screen', 'data-droy-im-screen': 'chat', 'data-companion-id': companion.id },
      h('header', { className: 'droy-im-header' }, iconButton('back', 'back', handleBack),
        h('button', { type: 'button', className: 'droy-im-chat-title', 'aria-label': t('profileOf', { name: companion.name }), onClick: state.showProfile },
          h('strong', null, companion.name), h('span', { role: 'status', 'aria-live': 'polite' }, phaseText(status.phase) ? t(phaseText(status.phase)) : '\u00a0')),
        iconButton('more', 'profile', state.showProfile)),
      h('div', { className: 'droy-im-messages' + (backgroundUrl(companion.chatBackground) ? ' has-wallpaper' : ''), ref: list, role: 'log', 'aria-label': companion.name, 'aria-live': 'polite',
        style: backgroundUrl(companion.chatBackground) ? { backgroundImage: 'url(' + JSON.stringify(backgroundUrl(companion.chatBackground)) + ')', backgroundPosition: (companion.chatBackground.position?.x ?? 50) + '% ' + (companion.chatBackground.position?.y ?? 50) + '%' } : undefined,
        onScroll: () => { const node = list.current; nearBottom.current = node.scrollHeight - node.clientHeight - node.scrollTop < 64; state.saveScroll(companion.id, node.scrollTop); if (nearBottom.current) setNewMessages(false); readVisible(); },
        onClick: event => { const anchor = event.target.closest?.('.droy-im-picture'); if (anchor) { event.preventDefault(); setImage(anchor.href); } } },
        snapshot.hasMore[companion.id] ? h('button', { type: 'button', className: 'droy-im-load-earlier', disabled: loadingEarlier, onClick: earlier }, t(loadingEarlier ? 'loading' : 'earlier')) : null,
        !snapshot.messages[companion.id] ? h('p', { className: 'droy-im-empty', role: 'status' }, t('loading')) : null,
        snapshot.messages[companion.id] && !messages.length ? h('div', { className: 'droy-im-chat-empty' }, h(Avatar, { companion }), h('strong', null, t('emptyChat', { name: companion.name })), h('p', null, t('emptyHint'))) : null,
        h(MemoMessageRows, { messages, companion, userProfile: snapshot.userProfile, stickers: snapshot.stickers, onMenu: setMenu, onUserProfile: openUserProfile, incoming, language: locale() + ':' + currentTimeZone }),
        status.error ? h('p', { className: 'droy-im-partial-error', role: 'status' }, t('partialError')) : null),
      newMessages ? h('button', { className: 'droy-im-new-messages', type: 'button', onClick: toBottom }, t('newMessages'), h(Icon, { name: 'down', size: 16 })) : null,
      h(ErrorNotice, { error: snapshot.error || (status.phase === 'error' ? status.error || t('partialError') : null),
        reload: () => (status.phase === 'error' ? state.retryTurn(companion.id) : state.refreshMessages(companion.id)).catch(state.report) }),
      h(MemoComposer, { companion, stickers: snapshot.stickers, panel, setPanel, language: locale() }),
      userProfileOpen ? h(UserProfileDialog, { profile: snapshot.userProfile, onClose: closeUserProfile, onPending: setUserProfileBusy }) : null,
      menu ? h('div', { className: 'droy-im-modal-shade', onClick: () => setMenu(null) }, h('div', { className: 'droy-im-message-menu', role: 'dialog', 'aria-modal': true, 'aria-label': t('menu'), onClick: event => event.stopPropagation() },
        menu.text ? h('p', { className: 'droy-im-menu-preview' }, menu.text) : null,
        menu.text ? h('button', { type: 'button', onClick: async () => { try { await target.navigator.clipboard.writeText(menu.text); setMenu(null); } catch (error) { state.report(error); } } }, t('copy')) : null,
        h('button', { type: 'button', 'data-droy-im-action': 'quote', onClick: () => { state.setDraft(companion.id, { replyTo: { id: menu.id, text: menu.text || t('attachment'), role: menu.role } }); setMenu(null); } }, t('quote')),
        h('button', { type: 'button', className: 'is-danger', onClick: () => { if (target.confirm(t('deleteMessage') + '\n' + t('deleteMessageHint'))) state.removeMessage(companion.id, menu.id).catch(state.report); setMenu(null); } }, t('delete')),
        h('button', { type: 'button', onClick: () => setMenu(null) }, t('cancel')))) : null,
      image ? h('div', { className: 'droy-im-image-viewer', role: 'dialog', 'aria-modal': true, 'aria-label': t('previewImage'), onClick: () => setImage(null) },
        iconButton('close', 'close', () => setImage(null)), h('img', { src: image, alt: t('image') })) : null);
  }
  function dialog(title, content, onClose, ref, extra = '') {
    return h('div', { className: 'droy-im-modal-shade is-centered', onClick: onClose },
      h('section', { ref, className: 'droy-im-dialog ' + extra, role: 'dialog', 'aria-modal': true, 'aria-label': t(title), 'data-droy-im-dialog': title, onClick: event => event.stopPropagation() },
        h('header', { className: 'droy-im-dialog-header' }, h('strong', null, t(title)), iconButton('close', 'close', onClose)),
        h('div', { className: 'droy-im-dialog-body' }, content)));
  }
  function UserProfileDialog({ profile, onClose, onPending }) {
    const [name, setName] = React.useState(profile.name || ''), [avatarUrl, setAvatarUrl] = React.useState(profile.avatarUrl || DEFAULT_USER_PROFILE.avatarUrl);
    const [pending, setPending] = React.useState(false), [error, setError] = React.useState(null);
    const element = React.useRef(null), uploaded = React.useRef(null), nameEdited = React.useRef(false);
    useModalFocus(true, element, onClose);
    const discard = () => { const value = uploaded.current; uploaded.current = null; if (value) return state.api('/user-profile/uploads/' + encodeURIComponent(value.id), { method: 'DELETE' }).catch(state.report); };
    React.useEffect(() => () => { discard(); }, []);
    const pick = async () => {
      if (pending || !media?.pick) return;
      setPending(true); onPending(true); setError(null);
      try { const result = await media.pick('user-avatar'); if (result?.[0]) { await discard(); uploaded.current = result[0]; setAvatarUrl(result[0].url); } }
      catch (failure) {
        if (failure.retainedUploads?.[0]) { await discard(); uploaded.current = failure.retainedUploads[0]; setAvatarUrl(uploaded.current.url); }
        setError(failure.message);
      } finally { setPending(false); onPending(false); }
    };
    return dialog('profile', h('form', { className: 'droy-im-profile-form', onSubmit: async event => {
      event.preventDefault(); if (pending || nameEdited.current && !name.trim()) return;
      setPending(true); onPending(true); setError(null);
      try { await state.updateUserProfile({ avatarUrl, ...(nameEdited.current ? { name: name.trim() } : {}) }); uploaded.current = null; onPending(false); onClose(); }
      catch (failure) { setError(failure.message); } finally { setPending(false); onPending(false); }
    } }, h('div', { className: 'droy-im-profile-identity' },
      h('button', { type: 'button', className: 'droy-im-avatar', 'aria-label': t('changeAvatar'), disabled: pending, onClick: pick }, h('img', { src: avatarUrl, alt: '', draggable: false })),
      h('button', { type: 'button', className: 'droy-im-text-button', 'data-droy-im-action': 'user-avatar', disabled: pending, onClick: pick }, t('changeAvatar'))),
      h('label', { className: 'droy-im-field' }, h('span', null, t('userName')), h('input', { 'data-droy-im-field': 'user-name', value: name, maxLength: 40, placeholder: t('you'), disabled: pending,
        onChange: event => { nameEdited.current = true; setName(event.target.value); } })),
      h(ErrorNotice, { error }), h('div', { className: 'droy-im-dialog-actions' },
        h('button', { type: 'button', className: 'droy-im-secondary', 'data-droy-im-action': 'cancel', disabled: pending, onClick: onClose }, t('cancel')),
        h('button', { type: 'submit', className: 'droy-im-primary', 'data-droy-im-action': 'save', disabled: pending || nameEdited.current && !name.trim() }, t(pending ? 'loading' : 'save')))), onClose, element);
  }
  function Profile({ companion, snapshot }) {
    const [form, setForm] = React.useState(() => ({ name: companion.name, model: companion.model || null,
      settings: { ...companion.settings, quietHours: { enabled: companion.settings?.quietHours !== null, start: '22:00', end: '09:00', ...companion.settings?.quietHours } } }));
    const [pending, setPending] = React.useState(false), [saved, setSaved] = React.useState(false), [modal, setModal] = React.useState(null);
    const [modelSearch, setModelSearch] = React.useState(''), [personality, setPersonality] = React.useState(''), [background, setBackground] = React.useState({ type: 'default' }), [avatarCandidate, setAvatarCandidate] = React.useState(null);
    const nameEdited = React.useRef(false), modalElement = React.useRef(null), modelElement = React.useRef(null), backgroundUpload = React.useRef(null), avatarUpload = React.useRef(null);
    React.useEffect(() => { if (!nameEdited.current) setForm(previous => ({ ...previous, name: companion.name })); }, [companion.name]);
    const discardBackground = () => {
      const item = backgroundUpload.current; backgroundUpload.current = null;
      if (item) return state.api('/' + encodeURIComponent(companion.id) + '/uploads/' + encodeURIComponent(item.id), { method: 'DELETE' }).catch(state.report);
    };
    const discardAvatar = () => {
      const item = avatarUpload.current; avatarUpload.current = null;
      if (item) return state.api('/' + encodeURIComponent(companion.id) + '/uploads/' + encodeURIComponent(item.id), { method: 'DELETE' }).catch(state.report);
    };
    const closeModal = () => { if (pending) return; if (modal === 'background') discardBackground(); if (modal === 'avatar') discardAvatar(); setModal(null); };
    useModalFocus(['personality', 'background', 'avatar'].includes(modal), modalElement, closeModal);
    React.useEffect(() => {
      localBack = () => { if (!modal) return false; closeModal(); return true; };
      return () => { localBack = () => false; };
    }, [modal, pending]);
    React.useEffect(() => {
      if (modal !== 'model') return;
      const outside = event => { if (!modelElement.current?.contains(event.target)) setModal(null); };
      const key = event => { if (event.key === 'Escape') { event.preventDefault(); setModal(null); } };
      doc.addEventListener('pointerdown', outside); doc.addEventListener('keydown', key);
      const scroller = modelElement.current?.closest('.droy-im-profile-scroll');
      if (scroller) scroller.scrollTop += modelElement.current.getBoundingClientRect().top - scroller.getBoundingClientRect().top;
      return () => { doc.removeEventListener('pointerdown', outside); doc.removeEventListener('keydown', key); };
    }, [modal]);
    React.useEffect(() => () => { discardBackground(); discardAvatar(); }, [companion.id]);
    const edit = (key, value) => { if (key === 'name') nameEdited.current = true; setSaved(false); setForm(previous => ({ ...previous, [key]: value })); };
    const setting = (key, value) => { setSaved(false); setForm(previous => ({ ...previous, settings: { ...previous.settings, [key]: value } })); };
    const run = async operation => { if (pending) return; setPending(true); try { await operation(); } catch (error) { state.report(error); } finally { setPending(false); } };
    const modelValue = model => model ? JSON.stringify({ provider: model.provider, model: model.model || model.id }) : '';
    const models = [...snapshot.models];
    if (form.model && !models.some(item => modelValue(item) === modelValue(form.model))) models.unshift({ ...form.model, id: form.model.model, name: form.model.model });
    const selectedModel = models.find(item => modelValue(item) === modelValue(form.model));
    const modelGroups = new Map();
    for (const model of models) {
      if (modelSearch && ![model.name, model.model, model.id, model.provider].filter(Boolean).join(' ').toLowerCase().includes(modelSearch.toLowerCase())) continue;
      if (!modelGroups.has(model.provider)) modelGroups.set(model.provider, []); modelGroups.get(model.provider).push(model);
    }
    function toggle(label, value, change) {
      return h('label', { className: 'droy-im-toggle-row' }, h('span', null, h('strong', null, t(label))),
        h('input', { type: 'checkbox', role: 'switch', 'data-droy-im-setting': label, checked: value === true, disabled: pending, onChange: event => change(event.target.checked) }));
    }
    const presets = [{ type: 'default' }, ...['01', '02', '03', '04', '05'].map(id => ({ type: 'preset', id }))];
    const backgroundKey = value => value.type + ':' + (value.id || '');
    const backgroundStyle = value => backgroundUrl(value) ? { backgroundImage: 'url(' + JSON.stringify(backgroundUrl(value)) + ')', backgroundPosition: (value.position?.x ?? 50) + '% ' + (value.position?.y ?? 50) + '%' } : undefined;
    return h('section', { className: 'droy-im-screen droy-im-profile-screen', 'data-droy-im-screen': 'profile' },
      h('header', { className: 'droy-im-header' }, iconButton('back', 'back', () => { if (modal) closeModal(); else state.back(); }), h('strong', { className: 'droy-im-page-title' }, t('profile')), h('span', { className: 'droy-im-header-spacer' })),
      h('div', { className: 'droy-im-profile-scroll' },
        h('div', { className: 'droy-im-profile-identity' }, h(Avatar, { companion }), h('strong', null, companion.name),
          media?.pick ? h('button', { type: 'button', className: 'droy-im-text-button', disabled: pending, onClick: () => run(async () => {
            let picked;
            try { picked = await media.pick('avatar', { companionId: companion.id }); }
            catch (error) {
              if (error.retainedUploads?.[0]) { avatarUpload.current = error.retainedUploads[0]; setAvatarCandidate(avatarUpload.current); setModal('avatar'); }
              throw error;
            }
            if (picked?.[0]) { avatarUpload.current = picked[0]; setAvatarCandidate(picked[0]); setModal('avatar'); }
          }) }, t('changeAvatar')) : null),
        h('form', { className: 'droy-im-profile-form', onSubmit: event => { event.preventDefault(); run(async () => {
          const patch = { model: form.model, settings: form.settings, ...(nameEdited.current && form.name !== companion.name ? { name: form.name } : {}) };
          await state.updateProfile(companion.id, patch); nameEdited.current = false; setSaved(true);
        }); } },
          h('label', { className: 'droy-im-field' }, h('span', null, t('name')), h('input', { 'data-droy-im-field': 'companion-name', value: form.name, maxLength: 40, required: true, disabled: pending, onChange: event => edit('name', event.target.value) })),
          h('div', { className: 'droy-im-field droy-im-model-select', ref: modelElement }, h('span', { id: 'droy-im-model-label' }, t('model')),
            h('button', { type: 'button', className: 'droy-im-select-trigger', 'data-droy-im-action': 'model-picker', 'aria-labelledby': 'droy-im-model-label', 'aria-haspopup': 'listbox', 'aria-expanded': modal === 'model', disabled: pending,
              onClick: () => { setModelSearch(''); setModal(modal === 'model' ? null : 'model'); } },
              h('span', null, selectedModel?.name || selectedModel?.id || selectedModel?.model || t('chooseModel')), h(Icon, { name: 'down', size: 18 })),
            modal === 'model' ? h('div', { className: 'droy-im-model-popover' },
              h('input', { className: 'droy-im-model-search', value: modelSearch, 'aria-label': t('searchModels'), placeholder: t('searchModels'), onChange: event => setModelSearch(event.target.value), onKeyDown: event => { if (event.key === 'Enter') event.preventDefault(); } }),
              h('div', { className: 'droy-im-model-options', role: 'listbox', 'aria-label': t('model') },
                h('button', { type: 'button', role: 'option', 'aria-selected': !form.model, 'data-droy-im-model': 'default', onClick: () => { edit('model', null); setModal(null); } }, h('span', null, t('chooseModel')), !form.model ? h(Icon, { name: 'check', size: 18 }) : null),
                ...[...modelGroups].map(([provider, items]) => h('div', { key: provider, role: 'group', 'aria-label': provider },
                  h('div', { className: 'droy-im-model-provider' }, provider), ...items.map(model => h('button', { type: 'button', role: 'option', key: modelValue(model), 'aria-selected': modelValue(form.model) === modelValue(model), 'data-droy-im-model': model.provider + ':' + (model.model || model.id),
                    onClick: () => { edit('model', { provider: model.provider, model: model.model || model.id }); setModal(null); } },
                    h('span', null, model.name || model.model || model.id), modelValue(form.model) === modelValue(model) ? h(Icon, { name: 'check', size: 18 }) : null)))),
                !modelGroups.size ? h('p', { className: 'droy-im-muted' }, t('noMatchingModels')) : null)) : null),
          h('button', { type: 'button', className: 'droy-im-config-row droy-im-personality', 'data-droy-im-action': 'personality', disabled: pending, onClick: () => {
            setPersonality(companion.personalityOverride || (typeof companion.personality === 'string' ? companion.personality : companion.personality?.[locale()] || companion.personality?.en || '')); setModal('personality');
          } }, h('span', null, t('personality')), h('span', null, t('configure'), h(Icon, { name: 'down', size: 16 }))),
          h('button', { type: 'button', className: 'droy-im-config-row', 'data-droy-im-action': 'background', disabled: pending, onClick: () => { setBackground(companion.chatBackground || { type: 'default' }); setModal('background'); } },
            h('span', null, t('chatBackground')), h(Icon, { name: 'down', size: 16 })),
          h('div', { className: 'droy-im-profile-group' },
            toggle('proactive', form.settings.proactiveEnabled, value => setting('proactiveEnabled', value)),
            toggle('dnd', form.settings.quietHours.enabled, value => setting('quietHours', { ...form.settings.quietHours, enabled: value })),
            form.settings.quietHours.enabled ? h('fieldset', { className: 'droy-im-dnd', 'aria-label': t('dnd') },
              h('label', null, t('dndStart'), h('input', { type: 'time', 'data-droy-im-setting': 'dndStart', value: form.settings.quietHours.start, disabled: pending, onChange: event => setting('quietHours', { ...form.settings.quietHours, start: event.target.value }) })),
              h('label', null, t('dndEnd'), h('input', { type: 'time', 'data-droy-im-setting': 'dndEnd', value: form.settings.quietHours.end, disabled: pending, onChange: event => setting('quietHours', { ...form.settings.quietHours, end: event.target.value }) }))) : null,
            toggle('civilLanguage', form.settings.allowProfanity === false, value => setting('allowProfanity', !value)),
            toggle('memory', form.settings.memoryEnabled !== false, value => setting('memoryEnabled', value))),
          h('button', { type: 'submit', className: 'droy-im-primary', 'data-droy-im-action': 'profile-save', disabled: pending || !form.name.trim() }, t(pending ? 'loading' : saved ? 'saved' : 'save'))),
        h('section', { className: 'droy-im-history-settings' },
          h('button', { type: 'button', className: 'droy-im-text-button is-danger', disabled: pending, onClick: () => {
            if (target.confirm(t('clearChatConfirm') + '\n' + t('clearChatHint'))) run(async () => {
              const voice = state.draft(companion.id).retainedRecording?.voice; await state.clearChat(companion.id);
              if (voice) await recording?.discard?.({ companionId: companion.id, voice });
            });
          } }, t('clearChat'))), h(ErrorNotice, { error: snapshot.error })),
      modal === 'avatar' && avatarCandidate ? dialog('avatar', h('div', { className: 'droy-im-dialog-form' },
        h('div', { className: 'droy-im-profile-identity' }, h('span', { className: 'droy-im-avatar' }, h('img', { src: avatarCandidate.url, alt: t('avatar') }))),
        h(ErrorNotice, { error: snapshot.error }), h('div', { className: 'droy-im-dialog-actions' },
          h('button', { type: 'button', className: 'droy-im-secondary', 'data-droy-im-action': 'cancel', disabled: pending, onClick: closeModal }, t('cancel')),
          h('button', { type: 'button', className: 'droy-im-primary', 'data-droy-im-action': 'save', disabled: pending, onClick: () => run(async () => {
            await state.updateProfile(companion.id, { avatarUrl: avatarCandidate.url }); avatarUpload.current = null; setModal(null);
          }) }, t(pending ? 'loading' : 'save')))), closeModal, modalElement) : null,
      modal === 'personality' ? dialog('personality', h('form', { className: 'droy-im-dialog-form', onSubmit: event => { event.preventDefault(); run(async () => {
        await state.updateProfile(companion.id, { personalityOverride: personality }); setModal(null);
      }); } }, h('textarea', { className: 'droy-im-personality-editor', value: personality, rows: 8, maxLength: 4000, disabled: pending, 'aria-label': t('personality'), onChange: event => setPersonality(event.target.value) }),
        h(ErrorNotice, { error: snapshot.error }), h('div', { className: 'droy-im-dialog-actions' },
          h('button', { type: 'button', className: 'droy-im-secondary', 'data-droy-im-action': 'cancel', disabled: pending, onClick: closeModal }, t('cancel')),
          h('button', { type: 'submit', className: 'droy-im-primary', 'data-droy-im-action': 'save', disabled: pending }, t(pending ? 'loading' : 'save')))), closeModal, modalElement) : null,
      modal === 'background' ? dialog('chatBackground', h('div', { className: 'droy-im-background-editor' },
        h('div', { className: 'droy-im-background-preview', style: backgroundStyle(background), 'aria-label': t('previewImage') }, h('i'), h('i')),
        h('div', { className: 'droy-im-background-grid', role: 'group', 'aria-label': t('chatBackground') }, ...presets.map(item =>
          h('button', { type: 'button', key: backgroundKey(item), 'data-droy-im-background': item.id || 'default', className: 'droy-im-background-option' + (item.type === 'default' ? ' is-default' : ''), style: item.type === 'preset' ? { backgroundImage: 'url(/droy/companions/assets/wallpaper-' + item.id + '-thumb.webp)' } : undefined,
            'aria-label': item.type === 'default' ? t('defaultBackground') : t('presetBackground', { number: Number(item.id) }), 'aria-pressed': backgroundKey(item) === backgroundKey(background),
            onClick: () => setBackground(item) }, backgroundKey(item) === backgroundKey(background) ? h('span', { className: 'droy-im-background-check' }, h(Icon, { name: 'check', size: 17 })) : null))),
        h('button', { type: 'button', className: 'droy-im-secondary', 'data-droy-im-action': 'upload-background', disabled: pending, onClick: () => run(async () => {
          let picked;
          try { picked = await media?.pick('background', { companionId: companion.id }); }
          catch (error) {
            if (error.retainedUploads?.[0]) { await discardBackground(); backgroundUpload.current = error.retainedUploads[0]; setBackground({ type: 'upload', id: backgroundUpload.current.id, url: backgroundUpload.current.url, position: { x: 50, y: 50 } }); }
            throw error;
          }
          if (picked?.[0]) { await discardBackground(); backgroundUpload.current = picked[0]; setBackground({ type: 'upload', id: picked[0].id, url: picked[0].url, position: { x: 50, y: 50 } }); }
        }) }, t('uploadBackground')),
        background.type === 'upload' ? h('div', { className: 'droy-im-background-position' }, ...['x', 'y'].map(axis => h('label', { key: axis },
          h('span', null, t(axis === 'x' ? 'horizontalPosition' : 'verticalPosition')), h('input', { type: 'range', min: 0, max: 100, value: background.position?.[axis] ?? 50,
            onChange: event => setBackground(previous => ({ ...previous, position: { x: 50, y: 50, ...previous.position, [axis]: Number(event.target.value) } })) })))) : null,
        h(ErrorNotice, { error: snapshot.error }), h('div', { className: 'droy-im-dialog-actions' },
          h('button', { type: 'button', className: 'droy-im-secondary', 'data-droy-im-action': 'cancel', disabled: pending, onClick: closeModal }, t('cancel')),
          h('button', { type: 'button', className: 'droy-im-primary', 'data-droy-im-action': 'apply', disabled: pending, onClick: () => run(async () => {
            const value = background.type === 'upload' ? { type: 'upload', id: background.id, position: background.position || { x: 50, y: 50 } } : background;
            await state.updateProfile(companion.id, { chatBackground: value });
            if (backgroundUpload.current?.id === background.id && background.type === 'upload') backgroundUpload.current = null;
            else await discardBackground(); setModal(null);
          }) }, t(pending ? 'loading' : 'apply')))), closeModal, modalElement, 'droy-im-background-dialog') : null);
  }
  function TaskDetails({ task, companion, snapshot }) {
    const ended = ['completed', 'failed', 'cancelled'].includes(task.state || task.status || task.phase);
    const text = value => typeof value === 'string' ? value : value?.summary || value?.text || '';
    return h('section', { className: 'droy-im-screen droy-im-task-screen', 'data-droy-im-screen': 'task' },
      h('header', { className: 'droy-im-header' }, iconButton('back', 'back', state.back), h('strong', { className: 'droy-im-page-title' }, t('taskDetails')), h('span', { className: 'droy-im-header-spacer' })),
      h('div', { className: 'droy-im-profile-scroll' }, h('h2', null, task.title || t('task')), h('p', { className: 'droy-im-muted' }, companion.name),
        text(task.description) ? h('p', { className: 'droy-im-task-body' }, text(task.description)) : null,
        text(task.result || task.summary) ? h('p', { className: 'droy-im-task-body' }, text(task.result || task.summary)) : null,
        text(task.error) ? h('p', { className: 'droy-im-error' }, text(task.error)) : null,
        !ended ? h('button', { type: 'button', className: 'droy-im-primary', onClick: () => state.api('/' + companion.id + '/cancel', { method: 'POST', body: { taskId: task.id } }).then(() => state.refreshMessages(companion.id)).catch(state.report) }, t('stopTask')) : null,
        h('p', { className: 'droy-im-muted' }, t('taskDetailHint')), h(ErrorNotice, { error: snapshot.error })));
  }
  function Panel() {
    const snapshot = useSnapshot();
    React.useEffect(() => { boot(); }, []);
    const companion = state.profile(snapshot.companionId);
    const task = snapshot.messages[companion?.id]?.find(message => message.task?.id === snapshot.task?.id)?.task || snapshot.task;
    return h('main', { className: 'droy-im', 'data-droy-im': true, 'data-direction': snapshot.direction }, snapshot.screen === 'list' || !companion ? h(Messages, { snapshot }) :
      snapshot.screen === 'profile' ? h(Profile, { key: companion.id, companion, snapshot }) :
        snapshot.screen === 'task' ? h(TaskDetails, { task, companion, snapshot }) : h(Chat, { key: companion.id, companion, snapshot }));
  }
  function DefaultModeSetting() {
    const snapshot = useSnapshot();
    React.useEffect(() => { boot(); }, []);
    return h('label', { className: 'droy-row droy-im-default-mode' }, h('span', { className: 'droy-row-copy' },
      h('span', { className: 'droy-row-title' }, t('defaultMode')), h('small', { className: 'droy-description' }, t('defaultModeHint'))),
      h('select', { value: snapshot.settings.defaultMode || 'messages', onChange: event => state.updateSettings({ defaultMode: event.target.value }).catch(state.report) },
        h('option', { value: 'messages' }, t('messages')), h('option', { value: 'work' }, t('work'))));
  }
  const style = doc.createElement('style'); style.id = 'droy-companion-style';
  style.textContent = typeof DROY_COMPANION_STYLES === 'string' ? DROY_COMPANION_STYLES : '';
  doc.head.appendChild(style); disposers.push(() => style.remove());
  const visibility = () => { if (doc.visibilityState !== 'hidden') { currentTimeZone = new Intl.DateTimeFormat().resolvedOptions().timeZone; state.refresh().catch(state.report); } };
  doc.addEventListener('visibilitychange', visibility); disposers.push(() => doc.removeEventListener('visibilitychange', visibility));
  let profileLocale = locale();
  const refreshProfileLocale = () => {
    Promise.resolve().then(() => target.__DSH_LOCALE__?.whenSynced?.()).then(() => {
      if (!disposed) return state.refreshRoot();
    }).catch(error => { if (!disposed) state.report(error); });
  };
  target.addEventListener('droy-native-locale-change', refreshProfileLocale);
  disposers.push(() => target.removeEventListener('droy-native-locale-change', refreshProfileLocale));
  if (ctx.locale?.subscribe) disposers.push(ctx.locale.subscribe(() => {
    const next = locale(); if (next === profileLocale) return;
    profileLocale = next;
    if (!target.__DSH_LOCALE__) refreshProfileLocale();
  }));
  return { Panel, WorkModeSwitch, DefaultModeSetting, state,
    initialize: boot, open: id => { state.open(id); ctx.layout.selectPanel('droy-companions'); },
    back() { if (captureBack() || localBack()) return true; if (state.getSnapshot().screen === 'list') return false; state.back(); return true; },
    refresh: state.refresh, dispose() { disposed = true; activeAudio?.pause(); state.dispose(); for (const dispose of disposers) dispose(); } };
}
