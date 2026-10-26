// All DSH-specific service calls live here. This file is inlined into the client
// factory at packaging time and checked against the bound DSH public contracts.
function orderMediaCommands(rows) {
  const names = ['droy-album', 'droy-camera'];
  const rest = rows.filter(row => !names.includes(row.name));
  const section = rest[0]?.section;
  return [...names.flatMap(name => {
    const row = rows.find(value => value.name === name);
    return row ? [{ ...row, ...(section === undefined ? {} : { section }) }] : [];
  }), ...rest];
}

function createDshMediaAdapter(ctx, translate = value => value) {
  const imageTypes = new Set(['image/png', 'image/jpeg', 'image/webp', 'image/gif']);
  function inputFor(binding) { return ctx.conversation.input.for(binding.ctx); }
  function limitsFor(binding) {
    const limits = binding.session.projections.faceOf('imageLimits').getSnapshot();
    if (!limits) throw new Error('图片服务尚未就绪，请稍后重试');
    return { ...limits, maxImagesPerMessage: Math.min(10, limits.maxImagesPerMessage) };
  }
  function assertAvailable(handle) {
    const state = handle.input.state.getSnapshot();
    const block = ctx.conversation.blocks.storeFor(handle.binding.sessionId).getSnapshot();
    if (block || ['adjudicating', 'submitting'].includes(state.phase))
      throw new Error(block?.reason || '对话正在提交，请稍后添加附件');
    return state;
  }
  function images(handle) {
    return ctx.conversation.resolveDraftAttachments(handle.input.state.getSnapshot().attachmentIds)
      .filter(attachment => attachment.kind === 'image');
  }
  return {
    async acquire(sessionId) {
      const reference = ctx.sessions.retain(sessionId, { source: 'mediaInput' });
      try {
        const binding = await reference.ready;
        return { binding, input: inputFor(binding), release: () => reference.release() };
      } catch (error) { reference.release(); throw error; }
    },
    selectedSession() {
      return Object.values(ctx.sessions.list.getSnapshot().byId)
        .find(row => (row.retainedBy.mainView || 0) > 0)?.id;
    },
    async createSession() {
      const selected = this.selectedSession();
      let workspaces = ctx.workspaces.list.getSnapshot().items;
      if (!workspaces.length) {
        const created = await ctx.workspaces.initializeDefault();
        if (created) workspaces = [created];
      }
      const workspace = workspaces.find(row => row.sessionIds.includes(selected)) || workspaces[0];
      if (!workspace) throw new Error('请先选择工作区，再导入分享内容');
      return ctx.sessions.create({ workspaceId: workspace.workspaceId });
    },
    sessions() {
      const source = ctx.sessions.list.getSnapshot();
      const archived = new Set(ctx.workspaces.list.getSnapshot().archivedSessionIds);
      return source.ids.map(id => source.byId[id]).filter(row => !row.parentId && !archived.has(row.id))
        .map(row => ({ id: row.id, title: row.displayTitle || row.title || translate('未命名对话') }));
    },
    open(sessionId) { ctx.uiWorkspace.openSession(sessionId); },
    available(sessionId) {
      const binding = ctx.sessions.binding(sessionId);
      if (!binding) return false;
      try { assertAvailable({ binding, input: inputFor(binding) }); return true; }
      catch { return false; }
    },
    remaining(handle) {
      assertAvailable(handle);
      return Math.max(0, limitsFor(handle.binding).maxImagesPerMessage - images(handle).length);
    },
    validate(handle, files) {
      assertAvailable(handle);
      const limits = limitsFor(handle.binding), existing = images(handle);
      const incoming = files.filter(file => file.type.startsWith('image/'));
      if (incoming.some(file => !imageTypes.has(file.type))) throw new Error('图片格式不支持，请转换后重试');
      if (existing.length + incoming.length > limits.maxImagesPerMessage) throw new Error(`每条消息最多添加 ${limits.maxImagesPerMessage} 张图片`);
      if (incoming.some(file => file.size > limits.maxImageBytes)) throw new Error('图片超过附件大小上限');
      const total = [...existing.map(value => value.file), ...incoming].reduce((sum, file) => sum + file.size, 0);
      if (total > limits.maxMessageImageBytes) throw new Error('图片总大小超过附件上限');
    },
    attach(handle, files) {
      this.validate(handle, files);
      const drafts = ctx.conversation.createDrafts(handle.binding.sessionId, files);
      if (!handle.input.addAttachments(drafts.map(draft => draft.id))) {
        for (const draft of drafts) ctx.conversation.releaseDraftAttachment(draft.id);
        throw new Error('对话正在提交，附件尚未加入草稿');
      }
      return drafts;
    },
    appendText(handle, text) {
      if (!text) return;
      const current = assertAvailable(handle);
      const suffix = `${current.draft ? '\n' : ''}${text}`;
      const context = handle.binding.ctx;
      if (context.bail(context, 'slash/input-insert-text', {
        text: suffix, span: { start: current.draft.length, end: current.draft.length, draftRev: current.draftRev },
      }) !== true) throw new Error('草稿已变化，分享文字尚未加入，请重试');
    },
    draftPresent(id) { return ctx.conversation.resolveDraftAttachments([id]).length > 0; },
    subscribe(handle, listener) {
      const stops = [handle.input.state.subscribe(listener), handle.binding.session.subscribe(listener)];
      return () => { for (const stop of stops) stop(); };
    },
    notify(handle, text) { handle.input.notify('error', text); },
  };
}
