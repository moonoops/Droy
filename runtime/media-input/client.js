window.__ModuleLoader__.load({
  id: '@droyagent/media-input',
  factory: require => {
    const React = require('react');
    const { Button, Modal } = require('@deepseek-ai/dsh-client-ui-primitives');
    const h = React.createElement;
    let locale;
    const messages = {
      "关闭": "Close",
      "相册": "Photos",
      "从相册选择图片上传": "Choose photos to upload",
      "拍照": "Camera",
      "使用相机拍照上传": "Take a photo to upload",
      "待导入内容": "Pending imports",
      "页面已关闭": "Page closed",
      "无法读取附件：{v0}": "Could not read attachment: {v0}",
      "附件内容不完整：{v0}": "Attachment content is incomplete: {v0}",
      "草稿已变化，分享文字尚未加入，请检查草稿": "The draft has changed. Shared text was not added; check the draft.",
      "文字导入曾中断，请检查草稿；确认后可保留当前文字": "Text import was interrupted. Check the draft, then choose whether to keep the current text.",
      "恢复了上次未确认的附件，请核对发送记录后再发送": "Unconfirmed attachments were recovered. Check sent messages before sending again.",
      "每条消息最多添加 10 张图片，请先移除图片": "Each message supports up to 10 images. Remove an image first.",
      "导入内容已失效": "The import is no longer available",
      "没有待核对的分享文字": "No shared text needs review",
      "请先在草稿中移除附件，再放弃导入": "Remove attachments from the draft before discarding this import",
      "手机附件服务尚未就绪": "Phone attachment service is not ready",
      "，{v0} 张图片": ", {v0} images",
      "{v0} 个附件{v1}": "{v0} attachments{v1}",
      "加入对话": "Add to conversation",
      "新对话草稿": "New conversation draft",
      "照片尚未保存到相册": "The photo has not been saved to Photos",
      "重试保存相册": "Retry saving to Photos",
      "保留当前文字": "Keep current text",
      "导入内容": "Import content",
      "稍后处理": "Handle later",
      "放弃导入": "Discard import",
      "正在导入…": "Importing…",
      "重试加入草稿": "Retry adding to draft",
      "加入草稿": "Add to draft",
      "返回草稿": "Return to draft",
      "查看导入内容": "View imports",
      "图片服务尚未就绪，请稍后重试": "Image service is not ready. Try again shortly.",
      "对话正在提交，请稍后添加附件": "The conversation is being submitted. Add attachments shortly.",
      "请先选择工作区，再导入分享内容": "Select a space before importing shared content",
      "未命名对话": "Untitled conversation",
      "图片格式不支持，请转换后重试": "Unsupported image format. Convert the image and try again.",
      "每条消息最多添加 {v0} 张图片": "Each message supports up to {v0} images",
      "图片超过附件大小上限": "The image exceeds the attachment size limit",
      "图片总大小超过附件上限": "The total image size exceeds the attachment limit",
      "对话正在提交，附件尚未加入草稿": "The conversation is being submitted. Attachments were not added to the draft.",
      "草稿已变化，分享文字尚未加入，请重试": "The draft has changed. Shared text was not added; try again."
};
    const namespace = 'droy.media-input';
    const interpolate = (text, params) => text.replace(/\{(\w+)\}/g, (match, name) => params && name in params ? String(params[name]) : match);
    const retainedMessages = Object.entries(messages).flatMap(([source, english]) => [source, english].map(template => {
      const names = [];
      const pattern = template.split(/(\{\w+\})/g).map(part => {
        if (/^\{\w+\}$/.test(part)) { names.push(part.slice(1, -1)); return '([\\s\\S]*?)'; }
        return part.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
      }).join('');
      return { source, english, names, pattern: new RegExp(`^${pattern}$`) };
    }));
    function tr(source, params) {
      if (typeof source !== 'string') return source;
      const english = locale?.getSnapshot().active === 'en';
      if (Object.hasOwn(messages, source)) return interpolate(english ? messages[source] : source, params);
      // Only product status/error surfaces use this path; user records and drafts are never translated.
      for (const message of retainedMessages) {
        const match = message.pattern.exec(source);
        if (match) return interpolate(english ? message.english : message.source,
          Object.fromEntries(message.names.map((name, index) => [name, match[index + 1]])));
      }
      return source;
    }
    function installLocale(ctx) {
      locale = ctx.locale;
      if (!locale) return;
      const dispose = locale.register(namespace, { zh: Object.fromEntries(Object.keys(messages).map(key => [key, key])), en: messages });
      ctx.effect(() => dispose);
    }
    function useLocale() {
      if (locale) React.useSyncExternalStore(listener => locale.subscribe(listener), () => locale.getSnapshot(), () => locale.getSnapshot());
    }


    /* DROY_MEDIA_ADAPTER */

    function AlbumIcon({ size = 24, className } = {}) {
      return h('svg', { width: size, height: size, className, viewBox: '0 0 24 24', fill: 'none', stroke: 'currentColor',
        strokeWidth: 1.7, strokeLinecap: 'round', strokeLinejoin: 'round', 'aria-hidden': true, focusable: false },
        h('rect', { x: 3, y: 3, width: 18, height: 18, rx: 3 }),
        h('circle', { cx: 8, cy: 8, r: 1.5 }), h('path', { d: 'm3 16 5-5 5 5 3-3 5 5' }));
    }

    function CameraIcon({ size = 24, className } = {}) {
      return h('svg', { width: size, height: size, className, viewBox: '0 0 24 24', fill: 'none', stroke: 'currentColor',
        strokeWidth: 1.7, strokeLinecap: 'round', strokeLinejoin: 'round', 'aria-hidden': true, focusable: false },
        h('path', { d: 'M8 6 9.5 3.5h5L16 6h3a2 2 0 0 1 2 2v10a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2Z' }),
        h('circle', { cx: 12, cy: 12.5, r: 3.5 }));
    }

    function createMediaCommands(adapter, controller, target) {
      return [
        ...[
          ['droy-album', tr("相册"), tr("从相册选择图片上传"), 'album', AlbumIcon],
          ['droy-camera', tr("拍照"), tr("使用相机拍照上传"), 'camera', CameraIcon],
        ].map(([name, label, description, source, icon]) => ({ name, label: () => tr(label), description: () => tr(description), icon,
          available: session => Boolean(target.DroyMobile?.mediaInput) && adapter.available(session.sessionId),
          ui: { kind: 'action', run: session => { void controller.pick(source, session.sessionId); } },
        })),
        { name: 'droy-imports', label: () => tr("待导入内容"), available: () => controller.pendingRows().length > 0,
          ui: { kind: 'action', run: () => controller.open() } },
      ];
    }

    function createImportController(adapter, native, environment) {
      const listeners = new Set(), adopted = new Map(), adopting = new Map(), failures = new Map(), dismissed = new Set(), currentPicks = new Set(), recoveryNotices = new Set();
      let snapshot = { imports: [], dialogId: null, pending: false, error: null, notice: null, textNeedsReview: null };
      let disposed = false, refreshFlight, refreshAgain = false, cleanupFlight, cleanupAgain = false;
      const publish = patch => {
        if (disposed) return;
        snapshot = { ...snapshot, ...patch };
        for (const listener of listeners) listener();
      };
      const request = args => {
        if (disposed) return Promise.reject(new Error(tr("页面已关闭")));
        return native(args);
      };
      const rowOf = id => snapshot.imports.find(row => row.id === id);
      const pendingRows = () => snapshot.imports.filter(row => !row.targetSessionId || row.error || row.albumError || row.albumSaved === false || failures.has(row.id));
      async function loadFiles(row) {
        const files = [];
        for (const item of row.items || []) {
          if (disposed) throw new Error(tr("页面已关闭"));
          const response = await environment.fetch(`/droy/media-input/${encodeURIComponent(row.id)}/${encodeURIComponent(item.id)}`, {
            credentials: 'same-origin', cache: 'no-store', redirect: 'error',
          });
          if (!response.ok) throw new Error(tr("无法读取附件：{v0}", {v0: item.name}));
          const blob = await response.blob();
          if (blob.size !== item.bytes) throw new Error(tr("附件内容不完整：{v0}", {v0: item.name}));
          files.push(new environment.File([blob], item.name, { type: item.mediaType || 'application/octet-stream' }));
        }
        return files;
      }
      async function ensureText(entry, row) {
        if (!row.text || row.textApplied) return;
        if (!entry.textInserted) {
          const current = entry.handle.input.state.getSnapshot().draft;
          if (row.textPrepared) {
            const currentHash = await environment.hashText(current);
            if (currentHash === row.textBeforeHash) {
              if (entry.handle.input.state.getSnapshot().draft !== current)
                throw new Error(tr("草稿已变化，分享文字尚未加入，请检查草稿"));
              adapter.appendText(entry.handle, row.text);
            } else if (!current.endsWith(row.text)) {
              publish({ textNeedsReview: row.id });
              throw new Error(tr("文字导入曾中断，请检查草稿；确认后可保留当前文字"));
            }
          } else {
            const beforeHash = await environment.hashText(current);
            await request({ operation: 'bind', id: row.id, targetSessionId: entry.handle.binding.sessionId,
              textPrepared: true, textBeforeHash: beforeHash });
            row.textPrepared = true; row.textBeforeHash = beforeHash;
            if (entry.handle.input.state.getSnapshot().draft !== current) {
              publish({ textNeedsReview: row.id });
              throw new Error(tr("草稿已变化，分享文字尚未加入，请检查草稿"));
            }
            adapter.appendText(entry.handle, row.text);
          }
          entry.textInserted = true;
        }
        await request({ operation: 'bind', id: row.id, targetSessionId: entry.handle.binding.sessionId, textApplied: true });
        entry.row.textApplied = true;
        if (snapshot.textNeedsReview === row.id) publish({ textNeedsReview: null });
      }
      async function attach(row, sessionId, open = true) {
        if (adopting.has(row.id)) return adopting.get(row.id);
        const operation = doAttach(row, sessionId, open).then(result => {
          const previousError = failures.get(row.id);
          failures.delete(row.id);
          if (previousError && snapshot.error === previousError) {
            const hideRecoveredDialog = snapshot.dialogId === row.id && row.targetSessionId && !row.error && !row.albumError && row.albumSaved !== false;
            publish({ error: null, ...(hideRecoveredDialog ? { dialogId: null } : {}) });
          }
          return result;
        }, error => { failures.set(row.id, error.message); throw error; }).finally(() => {
          adopting.delete(row.id);
          void Promise.resolve().then(cleanup).catch(error => publish({ error: error.message }));
        });
        adopting.set(row.id, operation);
        return operation;
      }
      async function doAttach(row, sessionId, open) {
        const existing = adopted.get(row.id);
        if (existing) return ensureText(existing, row);
        const handle = await adapter.acquire(sessionId);
        let entry;
        try {
          const files = await loadFiles(row);
          adapter.validate(handle, files);
          await request({ operation: 'bind', id: row.id, targetSessionId: sessionId });
          if (disposed) throw new Error(tr("页面已关闭"));
          if (open) adapter.open(sessionId);
          const drafts = adapter.attach(handle, files);
          entry = { row: { ...row, targetSessionId: sessionId }, handle, drafts: new Map((row.items || []).map((item, i) => [item.id, drafts[i].id])), stop: null };
          adopted.set(row.id, entry);
          entry.stop = adapter.subscribe(handle, () => {
            // DSH releases removed draft objects after synchronously publishing
            // the input state. Observe the complete operation in a microtask.
            void Promise.resolve().then(cleanup).catch(error => publish({ error: error.message }));
          });
          // Acknowledgement follows the persisted scoped edit; recovery checks
          // the existing suffix before applying an interrupted acknowledgement.
          await ensureText(entry, row);
        } catch (error) {
          if (!entry) handle.release();
          // If text acknowledgement failed, retain the already-attached bytes;
          // refresh must never manufacture a second set of browser attachments.
          throw error;
        }
      }
      async function cleanup() {
        if (disposed) return;
        if (cleanupFlight) { cleanupAgain = true; return cleanupFlight; }
        cleanupFlight = (async () => {
          do {
            cleanupAgain = false;
            for (const [id, entry] of adopted) {
            // A text-only import has no attachment ids to keep it alive while
            // its prepare/hash/ack is pending. It remains owned by adoption.
            if (adopting.has(id) || (entry.row.text && !entry.row.textApplied)) continue;
            const state = entry.handle.input.state.getSnapshot();
            if (['adjudicating', 'submitting'].includes(state.phase)) continue;
            const removed = [...entry.drafts].filter(([, draftId]) => !state.attachmentIds.includes(draftId) && !adapter.draftPresent(draftId));
            if (removed.length) {
              await request({ operation: 'removeItems', id, itemIds: removed.map(([itemId]) => itemId) });
              for (const [itemId] of removed) entry.drafts.delete(itemId);
            }
            const session = entry.handle.binding.session.getSnapshot();
            const textFinished = !entry.row.text || (state.draft === '' && !session.pendingSubmissions.length && !session.promptError);
            if (entry.drafts.size || !textFinished) continue;
            const currentRow = rowOf(id) || entry.row;
            if (currentRow.albumError || currentRow.albumSaved === false) continue;
            await request({ operation: 'complete', id });
            entry.stop?.(); entry.handle.release(); adopted.delete(id);
            publish({ imports: snapshot.imports.filter(row => row.id !== id) });
            }
          } while (cleanupAgain && !disposed);
        })().finally(() => { cleanupFlight = null; });
        return cleanupFlight;
      }
      async function refresh() {
        if (disposed) return;
        if (refreshFlight) { refreshAgain = true; return refreshFlight; }
        refreshFlight = (async () => {
          do {
            refreshAgain = false;
            const result = await request({ operation: 'list' });
            if (disposed) return;
            const imports = Array.isArray(result.imports) ? result.imports : [];
            publish({ imports });
            for (const row of imports) {
              const existing = adopted.get(row.id);
              if (!existing && row.source === 'camera' && row.albumSaved === true && !row.error && !(row.items || []).length && !row.text) {
                await request({ operation: 'complete', id: row.id });
                publish({ imports: snapshot.imports.filter(value => value.id !== row.id) });
                continue;
              }
              if (existing) {
                existing.row = row;
                if (row.text && !row.textApplied) {
                  try { await attach(row, row.targetSessionId, false); }
                  catch (error) { publish({ error: error.message, dialogId: row.id }); }
                }
              }
              if (row.targetSessionId && !row.error && !existing && ((row.items || []).length || row.text)) {
                if (!currentPicks.has(`${row.source}:${row.targetSessionId}`) && !recoveryNotices.has(row.id)) {
                  recoveryNotices.add(row.id);
                  publish({ notice: tr("恢复了上次未确认的附件，请核对发送记录后再发送") });
                }
                try { await attach(row, row.targetSessionId, row.source !== 'share'); }
                catch (error) { publish({ error: error.message, dialogId: row.id }); }
              }
            }
            const first = pendingRows().find(row => !dismissed.has(row.id));
            if (!snapshot.dialogId && first) publish({ dialogId: first.id });
            await cleanup();
          } while (refreshAgain && !disposed);
        })().catch(error => { publish({ error: error.message }); })
          .finally(() => { refreshFlight = null; });
        return refreshFlight;
      }
      async function run(operation) {
        if (snapshot.pending) return;
        publish({ pending: true, error: null });
        try { return await operation(); }
        catch (error) { publish({ error: error.message }); }
        finally { publish({ pending: false }); }
      }
      return {
        getSnapshot: () => snapshot,
        subscribe(listener) { listeners.add(listener); return () => listeners.delete(listener); },
        pendingRows,
        errorFor: id => failures.get(id),
        hasAdopted: id => adopted.has(id),
        canDiscard: id => !adopted.has(id) || adopted.get(id).drafts.size === 0,
        clearError() { publish({ error: null, notice: null }); },
        refresh,
        pick(source, sessionId) { return run(async () => {
          const handle = await adapter.acquire(sessionId);
          try {
            const remaining = adapter.remaining(handle);
            if (remaining < 1) throw new Error(tr("每条消息最多添加 10 张图片，请先移除图片"));
            const key = `${source}:${sessionId}`, alreadyPicked = currentPicks.has(key);
            // A picker result can notify the page before the launch reply is
            // delivered. Mark this page's ownership before crossing the bridge.
            currentPicks.add(key);
            try { await request({ operation: 'pick', source, remaining, targetSessionId: sessionId }); }
            catch (error) { if (!alreadyPicked) currentPicks.delete(key); throw error; }
          } finally { handle.release(); }
        }); },
        open(id) { const row = id ? rowOf(id) : pendingRows()[0]; if (row) publish({ dialogId: row.id, error: failures.get(row.id) || null }); },
        close() { if (snapshot.dialogId) dismissed.add(snapshot.dialogId); publish({ dialogId: null }); },
        adopt(id, target = 'new') { return run(async () => {
          const row = rowOf(id);
          if (!row || row.error) throw new Error(row?.error || tr("导入内容已失效"));
          const sessionId = row.targetSessionId || (target === 'new' ? await adapter.createSession() : target);
          await attach(row, sessionId);
          dismissed.add(id);
          publish({ dialogId: null });
          await refresh();
        }); },
        keepCurrentText(id) { return run(async () => {
          const entry = adopted.get(id);
          if (!entry || snapshot.textNeedsReview !== id) throw new Error(tr("没有待核对的分享文字"));
          await request({ operation: 'bind', id, targetSessionId: entry.handle.binding.sessionId, textApplied: true });
          entry.row.textApplied = true;
          failures.delete(id);
          publish({ textNeedsReview: null, error: null });
          await refresh();
        }); },
        discard(id) { return run(async () => {
          const entry = adopted.get(id);
          if (entry?.drafts.size) throw new Error(tr("请先在草稿中移除附件，再放弃导入"));
          await request({ operation: 'complete', id });
          if (entry) { entry.stop?.(); entry.handle.release(); adopted.delete(id); }
          failures.delete(id);
          dismissed.delete(id);
          publish({ dialogId: null, imports: snapshot.imports.filter(row => row.id !== id) });
        }); },
        retryAlbum(id) { return run(async () => { await request({ operation: 'retryAlbum', id }); }); },
        dispose() {
          disposed = true;
          for (const entry of adopted.values()) { entry.stop?.(); entry.handle.release(); }
          adopted.clear(); listeners.clear();
        },
      };
    }

    function observeMediaCatalogs(ctx, refresh) {
      const key = () => {
        const sessions = ctx.sessions.list.getSnapshot(), workspaces = ctx.workspaces.list.getSnapshot();
        // Retain counters and title updates are consequences of adoption and
        // must not feed another refresh. Only catalog identity/readiness matters.
        return JSON.stringify([
          sessions.phase, [...sessions.ids].sort(), workspaces.phase,
          workspaces.items.map(row => row.workspaceId).sort(), [...workspaces.archivedSessionIds].sort(),
        ]);
      };
      let previous = key();
      const changed = () => { const next = key(); if (next !== previous) { previous = next; refresh(); } };
      const stops = [ctx.sessions.list.subscribe(changed), ctx.workspaces.list.subscribe(changed)];
      return () => { for (const stop of stops) stop(); };
    }

    function apply(ctx) {
      installLocale(ctx);
      const target = window, adapter = createDshMediaAdapter(ctx, tr);
      const native = args => {
        if (typeof target.DroyMobile?.mediaInput !== 'function') return Promise.reject(new Error(tr("手机附件服务尚未就绪")));
        return target.DroyMobile.mediaInput(args);
      };
      const controller = createImportController(adapter, native, { fetch: target.fetch.bind(target), File: target.File,
        async hashText(text) {
          const bytes = new target.TextEncoder().encode(text);
          const digest = await target.crypto.subtle.digest('SHA-256', bytes);
          return Array.from(new Uint8Array(digest), value => value.toString(16).padStart(2, '0')).join('');
        },
      });
      const useStore = () => React.useSyncExternalStore(controller.subscribe, controller.getSnapshot, controller.getSnapshot);
      function ImportDialog() {
        useLocale();
        const state = useStore();
        const row = state.imports.find(value => value.id === state.dialogId);
        const [destination, setDestination] = React.useState('new');
        React.useEffect(() => { setDestination('new'); }, [row?.id]);
        if (!row) return null;
        const images = (row.items || []).filter(item => item.mediaType?.startsWith('image/')).length;
        const bound = Boolean(row.targetSessionId);
        const error = state.error || controller.errorFor(row.id) || row.error;
        const children = [
          h('div', { key: 'summary', 'data-droy-media-summary': true }, tr("{v0} 个附件{v1}", {v0: row.items?.length || 0, v1: images ? tr("，{v0} 张图片", {v0: images}) : ''})),
          h('ul', { key: 'items', className: 'droy-media-items' }, (row.items || []).map(item => h('li', { key: item.id, 'data-droy-media-item': item.id },
            item.mediaType?.startsWith('image/') ? h('img', { src: `/droy/media-input/${row.id}/${item.id}`, alt: '', loading: 'lazy' }) : h('span', { 'aria-hidden': true }, '📄'),
            h('span', null, item.name)))),
          row.text ? h('p', { key: 'text', className: 'droy-media-text', 'data-droy-media-text': true }, row.text) : null,
          !bound && !row.error ? h('label', { key: 'target', className: 'droy-media-target' }, tr("加入对话"),
            h('select', { 'data-droy-media-target': true, value: destination, onChange: event => setDestination(event.target.value), disabled: state.pending },
              h('option', { value: 'new' }, tr("新对话草稿")), adapter.sessions().map(session => h('option', { key: session.id, value: session.id }, session.title)))) : null,
          row.albumError || row.albumSaved === false ? h('div', { key: 'album', role: 'status', 'data-droy-media-album-error': true },
            h('p', null, tr(row.albumError) || tr("照片尚未保存到相册")),
            h(Button, { disabled: state.pending, 'data-droy-media-album-retry': true, onClick: () => controller.retryAlbum(row.id) }, tr("重试保存相册"))) : null,
          error ? h('p', { key: 'error', role: 'alert', 'data-droy-media-error': true }, tr(error)) : null,
          state.textNeedsReview === row.id ? h(Button, { key: 'keep-text', disabled: state.pending, 'data-droy-media-keep-text': true,
            onClick: () => controller.keepCurrentText(row.id) }, tr("保留当前文字")) : null,
        ];
        return h(Modal, { open: true, title: tr("导入内容"), closeLabel: tr("稍后处理"), className: 'droy-media-dialog',
          onClose: () => controller.close(), footer: h('div', { className: 'droy-media-actions' },
            h(Button, { variant: 'outline', 'data-droy-media-cancel': true, onClick: () => controller.close() }, tr("稍后处理")),
            h(Button, { variant: 'outline', disabled: state.pending || !controller.canDiscard(row.id), 'data-droy-media-discard': true, onClick: () => controller.discard(row.id) }, tr("放弃导入")),
            !row.error && (!bound || !controller.hasAdopted(row.id) || Boolean(state.error)) ? h(Button, { disabled: state.pending, 'data-droy-media-import': true, onClick: () => controller.adopt(row.id, destination) }, state.pending ? tr("正在导入…") : bound ? tr("重试加入草稿") : tr("加入草稿")) : null,
            bound ? h(Button, { onClick: () => { adapter.open(row.targetSessionId); controller.close(); }, 'data-droy-media-open-draft': true }, tr("返回草稿")) : null),
        }, h('div', { 'data-droy-media-dialog': row.id }, children));
      }
      function Notices() {
        useLocale();
        const state = useStore();
        if ((!state.error && !state.notice) || state.dialogId) return null;
        return h('div', { role: state.error ? 'alert' : 'status', className: 'droy-media-notice', 'data-droy-media-notice': true },
          h('span', { className: 'droy-media-notice-text' }, tr(state.error || state.notice)),
          controller.pendingRows().length ? h(Button, { variant: 'outline', onClick: () => controller.open() }, tr("查看导入内容")) : null,
          h(Button, { variant: 'outline', 'data-droy-media-notice-close': true, onClick: () => controller.clearError() }, tr("关闭")));
      }
      const style = target.document.createElement('style');
      style.textContent = `.droy-media-dialog{max-width:min(560px,calc(100vw - 24px));max-height:100%;overflow:auto}.droy-media-items{list-style:none;padding:0;margin:12px 0;max-height:32dvh;overflow:auto}.droy-media-items li{display:flex;gap:12px;align-items:center;padding:8px 0;overflow-wrap:anywhere}.droy-media-items img{width:52px;height:52px;object-fit:cover;flex-shrink:0;border-radius:8px}.droy-media-items li>span:last-child{min-width:0}.droy-media-text{white-space:pre-wrap;overflow-wrap:anywhere;max-height:20dvh;overflow:auto}.droy-media-target{display:flex;flex-direction:column;gap:8px}.droy-media-target select{width:100%;min-height:48px;color:inherit;background:var(--dsw-alias-bg-layer-2,#fff);border:1px solid currentColor;border-radius:8px;padding:8px;font:inherit}.droy-media-actions{display:flex;flex-wrap:wrap;gap:8px;justify-content:flex-end;padding:12px}.droy-media-actions button{min-height:48px;white-space:normal}.droy-media-notice{position:fixed;top:calc(64px + var(--droy-safe-top,0px));left:max(12px,var(--droy-safe-left,0px));right:max(12px,var(--droy-safe-right,0px));max-height:30dvh;overflow:auto;display:flex;align-items:center;flex-wrap:wrap;gap:8px;z-index:100;background:var(--dsw-alias-bg-layer-2,#fff);color:var(--dsw-alias-label-primary,#222);border:1px solid currentColor;padding:12px;border-radius:12px;overflow-wrap:anywhere}`;
      style.textContent += '.droy-media-notice-text{flex:1 1 180px;min-width:0}.droy-media-notice button{min-width:48px;min-height:48px;flex-shrink:0;white-space:normal}';
      target.document.head.appendChild(style);
      ctx.slots.inject('shell.overlay', () => ctx.slots.register({ name: 'shell.overlay', id: 'droy-media-input' }, () => h(React.Fragment, null, h(ImportDialog), h(Notices))));
      const disposers = createMediaCommands(adapter, controller, target).map(command => ctx.commandUi.register(command));
      ctx.on('droy/command-menu-rows', orderMediaCommands);
      const refresh = () => { if (target.DroyMobile?.mediaInput) void controller.refresh(); };
      const onVisibility = () => { if (target.document.visibilityState === 'visible') refresh(); };
      for (const name of ['droy-mobile-ready', 'droy-media-input-change', 'focus']) target.addEventListener(name, refresh);
      target.document.addEventListener('visibilitychange', onVisibility);
      const previous = target.DroyMediaInput;
      target.DroyMediaInput = controller;
      const stopCatalogs = observeMediaCatalogs(ctx, refresh);
      refresh();
      ctx.effect(() => () => {
        stopCatalogs();
        for (const dispose of disposers) dispose();
        for (const name of ['droy-mobile-ready', 'droy-media-input-change', 'focus']) target.removeEventListener(name, refresh);
        target.document.removeEventListener('visibilitychange', onVisibility);
        if (target.DroyMediaInput === controller) target.DroyMediaInput = previous;
        style.remove(); controller.dispose();
      });
    }
    return { apply, inject: ['locale', 'slots', 'commandUi', 'conversation', 'sessions', 'uiWorkspace', 'workspaces'], createImportController, createDshMediaAdapter, observeMediaCatalogs, createMediaCommands, orderMediaCommands };
  },
});
