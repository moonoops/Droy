function createDshVoiceAdapter(ctx) {
  const textOf = message => (message?.content || []).filter(block => block.type === 'text').map(block => block.text || '').join('');
  const composers = new Map(), waiting = new Map();
  return {
    attachInput(sessionId, actions) {
      composers.set(sessionId, actions);
      for (const ready of waiting.get(sessionId) || []) ready(actions);
      waiting.delete(sessionId);
      return () => { if (composers.get(sessionId) === actions) composers.delete(sessionId); };
    },
    async composer(handle, signal) {
      const id = handle.binding.sessionId;
      let actions = composers.get(id);
      if (!actions) actions = await new Promise((resolve, reject) => {
        const ready = value => { signal?.removeEventListener('abort', abort); resolve(value); };
        const abort = () => { waiting.get(id)?.delete(ready); reject(signal.reason || new Error('语音输入已取消')); };
        if (signal?.aborted) return abort();
        if (!waiting.has(id)) waiting.set(id, new Set());
        waiting.get(id).add(ready);
        signal?.addEventListener('abort', abort, { once: true });
      });
      return { actions, input: ctx.conversation.input.for(handle.binding.ctx) };
    },
    captureDraft(composer) {
      const state = composer.input.state.getSnapshot();
      return { composer, span: composer.actions.captureInsertion(), text: '',
        owned: !state.draft && !state.attachmentIds.length && !state.occurrences.length && state.phase === 'plain' };
    },
    previewDraft(draft, text) {
      const { actions, input } = draft.composer;
      const state = input.state.getSnapshot();
      if (!draft.owned || state.draft !== draft.text || state.attachmentIds.length || state.occurrences.length) return false;
      if (!actions.insertText(text, { ...draft.span, start: 0, end: draft.text.length })) return false;
      draft.text = text;
      draft.span = actions.captureInsertion();
      actions.persistDraft();
      return true;
    },
    retainDraft(draft, text) {
      if (draft.owned && draft.text === text) return true;
      if (draft.text) return false;
      const inserted = draft.composer.actions.insertText(text, draft.span);
      if (inserted) draft.composer.actions.persistDraft();
      return inserted;
    },
    clearDraft(draft) { return this.previewDraft(draft, ''); },
    insertPending(composer, text) {
      const ok = composer.actions.insertText(text, composer.actions.captureInsertion());
      if (ok) composer.actions.persistDraft();
      return ok;
    },
    observeSelection(listener) { return ctx.sessions.list.subscribe(() => listener(this.selectedSession())); },
    selectedSession() {
      return Object.values(ctx.sessions.list.getSnapshot().byId).find(row => (row.retainedBy.mainView || 0) > 0)?.id;
    },
    async createSession(signal, beforeOpen) {
      let rows = ctx.workspaces.list.getSnapshot().items;
      if (!rows.length) {
        const created = await ctx.workspaces.initializeDefault();
        if (created) rows = [created];
      }
      const selected = this.selectedSession();
      const workspace = rows.find(row => row.sessionIds.includes(selected)) || rows[0];
      if (!workspace) throw new Error('请先选择工作区');
      if (signal?.aborted) throw signal.reason;
      await beforeOpen?.();
      const id = await ctx.sessions.create({ workspaceId: workspace.workspaceId });
      if (signal?.aborted) throw signal.reason;
      await beforeOpen?.();
      ctx.uiWorkspace.openSession(id);
      return id;
    },
    async acquire(sessionId) {
      const reference = ctx.sessions.retain(sessionId, { source: 'droyVoice' });
      try { return { binding: await reference.ready, release: () => reference.release() }; }
      catch (error) { reference.release(); throw error; }
    },
    async submit(handle, text, admitted) {
      const session = handle.binding.session;
      const submission = session.beginSubmission({ mode: 'queue', text, attachments: [] });
      admitted?.(submission.requestId);
      let result;
      try { result = await session.prompt([{ type: 'text', text }], 'queue', undefined, submission.requestId); }
      catch (error) { submission.abandon(); throw error; }
      if (!result.ok) { submission.abandon(); throw new Error(result.error?.message || '语音消息未能提交'); }
      return submission.requestId;
    },
    async cancel(handle) {
      const result = await handle.binding.session.cancel();
      if (!result.ok) throw new Error(result.error?.message || '任务取消未完成');
      return result.value;
    },
    observe(handle, listener) {
      const source = handle.binding.eventSource;
      let window = source.getSnapshot();
      let cursor = Math.max(-1, ...window.entries.filter(row => row.type === 'event').map(row => row.event.seq));
      let transientCursor = Math.max(-1, ...window.entries.filter(row => row.type === 'transient').map(row => row.event.seq));
      return source.subscribe(() => {
        window = source.getSnapshot();
        const change = window.change;
        if (change.kind === 'settle-assistant') {
          if (change.entry && change.entry.event.seq > cursor) {
            cursor = change.entry.event.seq;
            listener(change.entry.event, { attemptId: change.attemptId });
          }
          return;
        }
        const entries = change.kind === 'append' ? change.entries : window.entries;
        for (const row of entries) {
          const event = row.event;
          if (row.type === 'transient') {
            if (event.seq <= transientCursor) continue;
            transientCursor = event.seq;
            listener(event, { attemptId: event.data.attemptId });
          } else if (event.seq > cursor) {
            cursor = event.seq;
            listener(event, {});
          }
        }
      });
    },
    context(handle) {
      return handle.binding.eventSource.getSnapshot().entries.filter(row => row.type === 'event')
        .map(row => row.event).filter(event => event.type === 'user/message' || event.type === 'assistant/message')
        .slice(-10).map(event => ({ role: event.type === 'user/message' ? 'user' : 'assistant',
          text: textOf(event.type === 'user/message' ? event.data : event.data.message) })).filter(row => row.text)
        .map(row => `${row.role === 'user' ? '用户' : 'Droy'}：${row.text}`).join('\n').slice(-5000);
    },
    textOf,
  };
}
