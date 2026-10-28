window.__ModuleLoader__.load({
  id: '@droyagent/mobile',
  factory: require => {
    const React = require('react');
    const { Button, Switch, Input, Modal, closeTopModal, modalSelector, IconNewChatOutlineRegular, IconPanelLeftOutlineRegular, IconSettingsOutlineMedium } = require('@deepseek-ai/dsh-client-ui-primitives');
    const h = React.createElement;

    /* DROY_UI_COPY_MODULE */
    /* DROY_TOUCH_MODULE */
    /* DROY_TOKEN_USAGE_MODULE */
    /* DROY_SETUP_MODULE */
    /* DROY_COMPANION_MODULE */

    function createNativeBridge(target, timeoutMs = 10000) {
      const pending = new Map();
      const native = target.DroyNative;
      const previous = native?.onmessage;
      let sequence = 0;
      let disposed = false;
      const onmessage = event => {
        let reply;
        try { reply = JSON.parse(typeof event === 'string' ? event : event.data); }
        catch { return; }
        const entry = pending.get(reply?.id);
        if (!entry) return;
        pending.delete(reply.id);
        target.clearTimeout(entry.timer);
        if (reply.ok === true) entry.resolve(reply.result);
        else entry.reject(new Error(typeof reply.error === 'string' ? reply.error : droyText("操作未完成，请重试")));
      };
      if (native) native.onmessage = onmessage;
      return {
        request(action, args = {}, requestTimeoutMs = timeoutMs) {
          if (disposed) return Promise.reject(new Error(droyText("页面已关闭")));
          if (!native || typeof native.postMessage !== 'function')
            return Promise.reject(new Error(droyText("未连接手机设置，请重新加载对话页面")));
          const id = `droy-ui-${++sequence}`;
          return new Promise((resolve, reject) => {
            const timer = target.setTimeout(() => {
              pending.delete(id);
              reject(new Error(droyText("操作超时，请重试")));
            }, requestTimeoutMs);
            pending.set(id, { resolve, reject, timer });
            try { native.postMessage(JSON.stringify({ id, action, args })); }
            catch (error) {
              target.clearTimeout(timer);
              pending.delete(id);
              reject(error);
            }
          });
        },
        dispose() {
          disposed = true;
          if (native?.onmessage === onmessage) native.onmessage = previous;
          for (const entry of pending.values()) {
            target.clearTimeout(entry.timer);
            entry.reject(new Error(droyText("页面已关闭")));
          }
          pending.clear();
        }
      };
    }

    function createCompanionWorkbench({ ctx, state, reportError, canInitialize = () => true }) {
      const { Toast } = require('@deepseek-ai/dsh-client-ui-primitives');
      const navigation = ctx.uiWorkspace, blocked = new Map(), restorers = [];
      const noticeListeners = new Set();
      let refusal = null, refusalSequence = 0;
      const refusalSnapshot = () => refusal;
      const subscribeRefusal = listener => { noticeListeners.add(listener); return () => noticeListeners.delete(listener); };
      let disposed = false, startupRequested = false, startupPending = false;
      const owners = () => new Map(state.getSnapshot().companions.filter(row => row.workspaceId).map(row => [row.workspaceId, row]));
      const workspaceFor = sessionId => ctx.workspaces.list.getSnapshot().items.find(row => row.sessionIds.includes(sessionId));
      const ownerFor = sessionId => owners().get(workspaceFor(sessionId)?.workspaceId);
      const currentSession = () => Object.values(ctx.sessions.list.getSnapshot().byId).find(row => row.retainedBy?.mainView > 0)?.id;
      function reason(owner) {
        const language = /^zh\b/i.test(ctx.locale?.getSnapshot?.().active || '') ? 'zh' : 'en';
        return DROY_COMPANION_COPY[language].workspaceReadOnly.replace('{name}', () => owner.name);
      }
      function refused(owner) {
        refusal = { owner, seq: ++refusalSequence };
        for (const listener of noticeListeners) listener();
        return new Error(reason(owner));
      }
      function wrap(name, intercept) {
        const original = navigation[name];
        const replacement = function (...args) { return intercept(original.bind(navigation), ...args); };
        navigation[name] = replacement;
        restorers.push(() => { if (navigation[name] === replacement) navigation[name] = original; });
      }
      // DSH also restores a selection before the first user action. Buddy
      // spaces remain in the sidebar, but are never automatic work targets.
      wrap('restoreSelection', async (original, workspaces, sessions) => {
        if (!state.getSnapshot().companions.length) await state.refreshRoot();
        if (disposed) return;
        const managed = owners(), excluded = new Set(workspaces.items.filter(row => managed.has(row.workspaceId)).flatMap(row => row.sessionIds));
        return original({ ...workspaces, items: workspaces.items.filter(row => !managed.has(row.workspaceId)) },
          { ...sessions, ids: sessions.ids.filter(id => !excluded.has(id)),
            byId: Object.fromEntries(Object.entries(sessions.byId).filter(([id]) => !excluded.has(id))) });
      });
      wrap('startSession', (original, workspaceId, ...args) => {
        const owner = owners().get(workspaceId ?? workspaceFor(currentSession())?.workspaceId);
        if (owner) { refused(owner); return; }
        return original(workspaceId, ...args);
      });
      for (const name of ['connectWorkspace', 'openWorkspace']) wrap(name, (original, workspaceId, ...args) => {
        const owner = owners().get(workspaceId);
        if (owner) return Promise.reject(refused(owner));
        return original(workspaceId, ...args);
      });
      wrap('forkSession', (original, sessionId, ...args) => {
        const owner = ownerFor(sessionId);
        return owner ? Promise.reject(refused(owner)) : original(sessionId, ...args);
      });
      function updateBlocks() {
        if (disposed) return;
        const next = new Map(), managed = owners();
        for (const workspace of ctx.workspaces.list.getSnapshot().items) {
          const owner = managed.get(workspace.workspaceId); if (!owner) continue;
          for (const sessionId of workspace.sessionIds) {
            const text = reason(owner), previous = blocked.get(sessionId);
            const block = previous?.reason === text ? previous : { reason: text };
            next.set(sessionId, block);
            if (block !== previous) ctx.conversation.blocks.set(sessionId, block);
          }
        }
        for (const [sessionId, block] of blocked) if (!next.has(sessionId) && ctx.conversation.blocks.storeFor(sessionId).getSnapshot() === block)
          ctx.conversation.blocks.set(sessionId, undefined);
        blocked.clear(); for (const [id, block] of next) blocked.set(id, block);
      }
      async function selectDefault() {
        if (disposed || !startupRequested || startupPending || !canInitialize()) return;
        const workspaces = ctx.workspaces.list.getSnapshot(), sessions = ctx.sessions.list.getSnapshot();
        if (workspaces.phase !== 'ready' || sessions.phase !== 'ready') return;
        const selected = currentSession();
        if (selected && !ownerFor(selected)) return;
        startupPending = true;
        try {
          const workspace = workspaces.items.find(row => !owners().has(row.workspaceId)) || await ctx.workspaces.initializeDefault();
          if (!workspace || disposed || !canInitialize()) return;
          const sessionId = await navigation.connectWorkspace(workspace.workspaceId);
          if (disposed || !canInitialize()) return;
          const selectedNow = currentSession();
          if (selectedNow && !ownerFor(selectedNow)) return;
          const panel = ctx.layout.panelInfo.getSnapshot().activePanelId;
          navigation.openSession(sessionId);
          if (panel !== null) ctx.layout.selectPanel(panel);
        } catch (error) { if (!disposed) reportError(error); }
        finally { startupPending = false; }
      }
      const changed = () => { updateBlocks(); selectDefault(); };
      const subscribeCompanions = state.subscribeView || state.subscribe;
      const companionSnapshot = () => state.getSnapshot().companions;
      let companionRows = companionSnapshot();
      const companionsChanged = () => {
        const next = companionSnapshot();
        if (next === companionRows) return;
        companionRows = next; changed();
      };
      const subscriptions = [subscribeCompanions(companionsChanged), ctx.workspaces.list.subscribe(changed), ctx.sessions.list.subscribe(changed)];
      if (ctx.locale?.subscribe) subscriptions.push(ctx.locale.subscribe(updateBlocks));
      const registration = ctx.slots.inject('conversation.composer', () => ctx.slots.register({ name: 'conversation.composer', priority: -10,
        select: ({ sessionId }) => ownerFor(sessionId) || null }, function CompanionReadOnlyComposer({ matched }) {
        if (ctx.locale) React.useSyncExternalStore(callback => ctx.locale.subscribe(callback), () => ctx.locale.getSnapshot(), () => ctx.locale.getSnapshot());
        return h('p', { className: 'droy-workspace-readonly', role: 'note', style: { margin: '12px 16px', color: 'var(--dsw-alias-label-secondary)', fontSize: '14px', lineHeight: 1.6 } }, reason(matched));
      }));
      const noticeRegistration = ctx.slots.inject('shell.overlay', () => ctx.slots.register({ name: 'shell.overlay', id: 'droy-workspace-readonly-notice' },
        function CompanionReadOnlyNotice() {
          const notice = React.useSyncExternalStore(subscribeRefusal, refusalSnapshot, refusalSnapshot);
          React.useSyncExternalStore(subscribeCompanions, companionSnapshot, companionSnapshot);
          if (ctx.locale) React.useSyncExternalStore(callback => ctx.locale.subscribe(callback), () => ctx.locale.getSnapshot(), () => ctx.locale.getSnapshot());
          if (!notice) return null;
          const owner = state.getSnapshot().companions.find(row => row.id === notice.owner.id) || notice.owner;
          return h(Toast, { key: notice.seq, text: reason(owner), holdMs: 8000, onDone: () => {
            if (refusal?.seq !== notice.seq) return;
            refusal = null; for (const listener of noticeListeners) listener();
          } });
        }));
      updateBlocks();
      return { ownerFor, initializeDefault() { startupRequested = true; return selectDefault(); },
        dispose() {
          disposed = true; for (const stop of subscriptions) stop?.();
          if (typeof registration === 'function') registration();
          if (typeof noticeRegistration === 'function') noticeRegistration();
          refusal = null; noticeListeners.clear();
          for (const restore of restorers.reverse()) restore();
          for (const [id, block] of blocked) if (ctx.conversation.blocks.storeFor(id).getSnapshot() === block) ctx.conversation.blocks.set(id, undefined);
          blocked.clear();
        } };
    }

    function createBrowserNavigation(ctx, bridge, reportError, view = window) {
      const pages = new Map(), pending = new Set();
      let disposed = false, preparing;
      const unavailable = () => new Error(droyText("页面未能就绪，请重试"));
      function assertAlive(deadline) {
        if (disposed) throw new Error(droyText("页面已关闭"));
        if (Date.now() >= deadline) throw unavailable();
      }
      function bounded(operation, deadline) {
        return new Promise((resolve, reject) => {
          let settled = false;
          const finish = (error, value) => {
            if (settled) return;
            settled = true; view.clearTimeout(timer); pending.delete(cancel);
            if (error) reject(error); else resolve(value);
          };
          const cancel = () => finish(new Error(droyText("页面已关闭")));
          const timer = view.setTimeout(() => finish(unavailable()), Math.max(0, deadline - Date.now()));
          pending.add(cancel);
          Promise.resolve(operation).then(value => finish(undefined, value), error => finish(error));
          if (disposed) cancel();
        });
      }
      function waitFor(read, deadline) {
        let frame;
        const operation = new Promise((resolve, reject) => {
          const check = () => {
            try {
              assertAlive(deadline);
              const value = read();
              if (value) resolve(value); else frame = view.requestAnimationFrame(check);
            } catch (error) { reject(error); }
          };
          check();
        });
        return bounded(operation, deadline).finally(() => { if (frame !== undefined) view.cancelAnimationFrame(frame); });
      }
      function selectedSession() {
        const rows = Object.values(ctx.sessions.list.getSnapshot().byId)
          .filter(row => (row.retainedBy.mainView || 0) > 0);
        if (rows.length > 1) throw new Error(droyText("当前对话不明确，请先选择一个对话"));
        return rows[0]?.id;
      }
      function recentWorkspace(workspaces, sessions) {
        let selected, selectedTime = -Infinity;
        for (const workspace of workspaces) {
          let latest = -Infinity;
          for (const id of workspace.sessionIds) {
            const session = sessions[id];
            if (session) latest = Math.max(latest, session.updatedAt);
          }
          if (latest === -Infinity) latest = Date.parse(workspace.createdAt);
          if (!selected || latest > selectedTime) { selected = workspace.workspaceId; selectedTime = latest; }
        }
        return selected;
      }
      function ensureSurface() {
        if (disposed) return Promise.reject(new Error(droyText("页面已关闭")));
        const ready = ctx.sidebarRight.commandTarget(null);
        if (ready) return Promise.resolve(ready.sessionId);
        if (preparing) return preparing;
        const deadline = Date.now() + 10000;
        const navigation = ctx.layout.beginNavigation();
        const assertPreparing = () => {
          assertAlive(deadline);
          if (navigation.aborted) throw new Error(droyText("页面已切换，浏览器未打开"));
        };
        preparing = (async () => {
          await waitFor(() => {
            assertPreparing();
            return ctx.sessions.list.getSnapshot().phase === 'ready' && ctx.workspaces.list.getSnapshot().phase === 'ready';
          }, deadline);
          assertPreparing();
          let sessionId = selectedSession();
          if (!sessionId) {
            const workspaces = ctx.workspaces.list.getSnapshot(), sessions = ctx.sessions.list.getSnapshot();
            let workspaceId = recentWorkspace(workspaces.items, sessions.byId);
            if (!workspaceId && workspaces.items.length === 0 && sessions.ids.length === 0) {
              const workspace = await bounded(ctx.workspaces.initializeDefault(navigation), deadline);
              assertPreparing();
              workspaceId = workspace?.workspaceId;
            }
            if (!workspaceId) throw new Error(droyText("没有可用空间，请先选择空间再打开浏览器"));
            assertPreparing();
            sessionId = await bounded(ctx.uiWorkspace.connectWorkspace(workspaceId), deadline);
            assertPreparing();
            const selectedNow = selectedSession();
            if (selectedNow && selectedNow !== sessionId) throw new Error(droyText("页面已切换，浏览器未打开"));
          }
          assertPreparing();
          // Selecting a blank session creates no message or model turn. Its store is adopted after React renders.
          ctx.uiWorkspace.openSession(sessionId);
          assertAlive(deadline);
          // openSession itself aborts the previous navigation when it reveals Conversation.
          if (selectedSession() !== sessionId) throw new Error(droyText("对话已切换，浏览器未打开"));
          const surfaceNavigation = ctx.layout.beginNavigation();
          await waitFor(() => {
            if (surfaceNavigation.aborted) throw new Error(droyText("页面已切换，浏览器未打开"));
            const target = ctx.sidebarRight.commandTarget(null);
            if (target && target.sessionId !== sessionId) throw new Error(droyText("对话已切换，浏览器未打开"));
            return target?.sessionId === sessionId;
          }, deadline);
          assertAlive(deadline);
          if (surfaceNavigation.aborted) throw new Error(droyText("页面已切换，浏览器未打开"));
          return sessionId;
        })().finally(() => { preparing = undefined; });
        return preparing;
      }
      async function openPage(params, nativeSessionId) {
        const chatSessionId = await ensureSurface();
        if (disposed) throw new Error(droyText("页面已关闭"));
        if (ctx.sidebarRight.commandTarget(null)?.sessionId !== chatSessionId) throw new Error(droyText("对话已切换，浏览器未打开"));
        const existing = nativeSessionId && pages.get(nativeSessionId);
        const before = ctx.sidebarRight.openTabs.getSnapshot().filter(tab => tab.sessionId === chatSessionId);
        const reused = existing && before.some(tab => tab.tabId === existing);
        if (reused) {
          ctx.sidebarRight.focus(existing);
          if (!ctx.sidebarRight.isExpanded()) ctx.sidebarRight.toggleExpanded();
        } else ctx.sidebarRight.openTab('browser', { params });
        const tab = ctx.sidebarRight.active();
        if (!tab || tab.kind !== 'browser' || (reused ? tab.id !== existing : before.some(previous => previous.tabId === tab.id)))
          throw new Error(droyText("浏览器标签未能打开，请重试"));
        if (nativeSessionId) pages.set(nativeSessionId, tab.id);
        return { status: 'opened', tabId: tab.id, chatSessionId, ...(nativeSessionId ? { sessionId: nativeSessionId } : {}) };
      }
      return {
        browserPage(args) {
          if (args.operation === 'attach' && args.uiTabId) pages.set(args.tabId, args.uiTabId);
          if (args.operation === 'close') pages.delete(args.tabId);
          return bridge.request('browserPage', args);
        },
        openExternalBrowser(url) { return bridge.request('openExternalBrowser', { url }); },
        openBrowser(url) { return openPage(url === undefined ? {} : { url }); },
        revealBrowser({ sessionId, url } = {}) {
          if (typeof sessionId !== 'string' || !sessionId) return Promise.reject(new Error(droyText("浏览器会话不存在")));
          return openPage({ nativeSessionId: sessionId, ...(url === undefined ? {} : { url }) }, sessionId);
        },
        async revealPetTask({ taskId, requestId } = {}) {
          if (typeof taskId !== 'string' || !taskId.trim()) throw new Error(droyText("没有可打开的任务对话"));
          const deadline = Date.now() + 10000;
          const requirePending = async () => {
            if (requestId == null) return;
            if (typeof requestId !== 'string' || !requestId
                || !(await bridge.request('petQuestionActive', { taskId, requestId })).active)
              throw new Error(droyText("这个问题已处理"));
          };
          let navigation = ctx.layout.beginNavigation();
          const isCurrent = () => {
            if (navigation.aborted) throw new Error(droyText("页面已切换，任务对话未打开"));
          };
          await waitFor(() => {
            isCurrent();
            return ctx.sessions.list.getSnapshot().phase === 'ready' && ctx.workspaces.list.getSnapshot().phase === 'ready';
          }, deadline);
          isCurrent();
          if (!ctx.sessions.list.getSnapshot().byId[taskId]) throw new Error(droyText("任务对话已不存在"));
          if (ctx.workspaces.list.getSnapshot().archivedSessionIds.includes(taskId)) throw new Error(droyText("任务对话已归档，请从历史记录查看"));
          await requirePending();
          isCurrent();
          ctx.uiWorkspace.openSession(taskId);
          navigation = ctx.layout.beginNavigation();
          await waitFor(() => {
            isCurrent();
            return ctx.sidebarRight.commandTarget(null)?.sessionId === taskId;
          }, deadline);
          isCurrent();
          await requirePending();
          isCurrent();
          if (ctx.sidebarRight.isExpanded()) ctx.sidebarRight.toggleExpanded();
          return { status: 'opened', taskId };
        },
        reportBrowserError(error) { reportError(error); },
        dispose() { disposed = true; for (const cancel of [...pending]) cancel(); pages.clear(); }
      };
    }

    const LOGO_BACKGROUNDS = { light: '#F2EEE7', dark: '#D8D3CB' };
    const logoPaletteCache = new Map();

    function logoBackgroundsFromPixels(pixels, width, height) {
      const opaque = (x, y) => x >= 0 && y >= 0 && x < width && y < height && pixels[(y * width + x) * 4 + 3] >= 128;
      let luminance = 0, count = 0;
      const linear = value => value <= .04045 ? value / 12.92 : ((value + .055) / 1.055) ** 2.4;
      // Sample the silhouette: a white face with a dark outline still needs a light backing.
      for (let y = 0; y < height; y++) for (let x = 0; x < width; x++) {
        if (!opaque(x, y) || opaque(x - 1, y) && opaque(x + 1, y) && opaque(x, y - 1) && opaque(x, y + 1)) continue;
        const offset = (y * width + x) * 4;
        luminance += .2126 * linear(pixels[offset] / 255) + .7152 * linear(pixels[offset + 1] / 255) + .0722 * linear(pixels[offset + 2] / 255);
        count++;
      }
      const darkBacking = { light: '#4A4650', dark: '#37333D' };
      const foreground = count ? luminance / count : 0;
      const score = palette => Math.min(...Object.values(palette).map(color => {
        const channels = color.slice(1).match(/../g).map(value => linear(parseInt(value, 16) / 255));
        const background = channels[0] * .2126 + channels[1] * .7152 + channels[2] * .0722;
        return (Math.max(foreground, background) + .05) / (Math.min(foreground, background) + .05);
      }));
      return score(darkBacking) > score(LOGO_BACKGROUNDS) ? darkBacking : { ...LOGO_BACKGROUNDS };
    }

    function localizedBrandTemplate(template, source) {
      return !template || template === source || template === DROY_UI_COPY.en[source] ? droyText(source) : template;
    }

    function brandingSnapshot(state) {
      const brand = state?.branding || {};
      const name = brand.name || state?.assistantName || 'Droy';
      const expand = (template, fallback) => (typeof template === 'string' && template.trim() ? template : fallback).replaceAll('{name}', () => name);
      const petId = 'default';
      return {
        name, sidebarLabel: brand.sidebarLabel || 'Droy',
        heroBadge: typeof brand.heroBadge === 'string' ? brand.heroBadge : '',
        welcome: expand(localizedBrandTemplate(brand.welcomeTemplate, '与{name}开始对话'), droyText('与{name}开始对话')),
        composer: expand(localizedBrandTemplate(brand.composerTemplate, '请描述你想让{name}执行的任务，或者你的对话'), droyText('请描述你想让{name}执行的任务，或者你的对话')),
        logoUrl: brand.logoUrl || '/droy/branding/installed-logo',
        logoPetId: petId, logoBackgrounds: brand.logoBackgrounds || null,
        dshVersion: brand.dshVersion || '',
      };
    }

    function installBranding(target, controller) {
      const previous = target.DroyBranding;
      let snapshot = brandingSnapshot(controller.getSnapshot().state);
      const store = { getSnapshot: () => snapshot };
      target.DroyBranding = store;
      const updateIcon = () => {
        for (const icon of target.document?.querySelectorAll('link[rel~="icon"]') || []) icon.href = snapshot.logoUrl;
      };
      const publish = () => {
        const next = brandingSnapshot(controller.getSnapshot().state);
        if (JSON.stringify(next) === JSON.stringify(snapshot)) return;
        snapshot = next;
        updateIcon();
        target.dispatchEvent(new target.CustomEvent('droy-branding-change'));
      };
      const unsubscribe = controller.subscribe(publish);
      const unsubscribeLocale = droyLocale?.subscribe(publish);
      updateIcon();
      target.dispatchEvent(new target.CustomEvent('droy-branding-change'));
      return () => { unsubscribe(); unsubscribeLocale?.(); if (target.DroyBranding === store) target.DroyBranding = previous; };
    }

    function createController(bridge) {
      let snapshot = { state: null, loading: false, pending: null, pendingKeys: {}, catalogLoading: {}, error: null, errorKey: null };
      const listeners = new Set(), views = new Map(), sections = new Map(), sectionRequests = new Map(), mutations = new Map();
      let refreshGeneration = 0, refreshing = null, lastRefresh = 0, disposed = false, mutationQueue = Promise.resolve();
      const publish = update => {
        if (disposed || Object.entries(update).every(([key, value]) => snapshot[key] === value)) return;
        snapshot = { ...snapshot, ...update };
        for (const listener of listeners) listener();
      };
      const validState = value => value && Array.isArray(value.groups) && Array.isArray(value.fileGrants);
      function accept(incoming, partial = false) {
        if (disposed) return;
        const previous = snapshot.state || {}, next = partial ? { ...previous } : {};
        for (const [key, supplied] of Object.entries(incoming)) {
          let value = supplied;
          if (['notificationManagement', 'proactiveNotifications'].includes(key) && supplied && !Object.hasOwn(supplied, 'apps') && previous[key]?.apps) {
            value = { ...supplied, apps: previous[key].apps };
          }
          if (key === 'notificationManagement' && value?.apps && value.exceptions) {
            const modes = new Map(value.exceptions.map(item => [item.packageName, item.mode]));
            value = { ...value, apps: value.apps.map(item => {
              const mode = modes.get(item.packageName) || 'managed';
              return mode === item.mode ? item : { ...item, mode, managed: mode !== 'allow' };
            }) };
          }
          next[key] = previous[key] === value || JSON.stringify(previous[key]) === JSON.stringify(value) ? previous[key] : value;
        }
        const unchanged = snapshot.state && Object.keys(previous).length === Object.keys(next).length
          && Object.keys(next).every(key => next[key] === previous[key]);
        publish({ state: unchanged ? snapshot.state : next, loading: false });
      }
      function pending() {
        const pendingKeys = Object.fromEntries([...mutations].map(([key, item]) => [key, item.action]));
        publish({ pendingKeys, pending: Object.values(pendingKeys)[0] || null });
      }
      const controller = {
        getSnapshot: () => snapshot,
        subscribe(listener) { listeners.add(listener); return () => listeners.delete(listener); },
        view(fields, actions = []) {
          const key = JSON.stringify([fields, actions]);
          if (views.has(key)) return views.get(key);
          let selected;
          const relevant = value => actions.some(action => value === action || value?.startsWith(action + ':'));
          const getSnapshot = () => {
            const state = snapshot.state ? Object.fromEntries(fields.map(field => [field, snapshot.state[field]])) : null;
            const next = { state, loading: snapshot.loading,
              catalogLoading: fields.some(field => snapshot.catalogLoading[field]),
              pending: Object.keys(snapshot.pendingKeys).some(relevant) ? snapshot.pending : null,
              error: !snapshot.errorKey || relevant(snapshot.errorKey) ? snapshot.error : null };
            if (!selected || selected.loading !== next.loading || selected.catalogLoading !== next.catalogLoading || selected.pending !== next.pending || selected.error !== next.error
              || Boolean(selected.state) !== Boolean(state) || state && fields.some(field => selected.state[field] !== state[field])) selected = next;
            return selected;
          };
          const view = { getSnapshot, subscribe(listener) {
            let previous = getSnapshot();
            const changed = () => { const next = getSnapshot(); if (next !== previous) { previous = next; listener(); } };
            listeners.add(changed); return () => listeners.delete(changed);
          } };
          views.set(key, view); return view;
        },
        refresh({ force = false, maxAgeMs = 0, section } = {}) {
          if (section) return controller.refreshSection(section);
          if (!force && refreshing) return refreshing;
          if (!force && snapshot.state && !snapshot.error && Date.now() - lastRefresh < maxAgeMs) return Promise.resolve(snapshot.state);
          const generation = ++refreshGeneration;
          publish({ loading: !snapshot.state, error: null, errorKey: null });
          const operation = (async () => { try {
            const state = await bridge.request('getState');
            if (!validState(state)) throw new Error(droyText("手机设置数据不完整，请重试"));
            if (generation === refreshGeneration) { lastRefresh = Date.now(); accept(state); }
            return state;
          } catch (error) {
            if (generation === refreshGeneration) publish({ loading: false, error: error.message, errorKey: null });
            throw error;
          } })();
          const settled = operation.finally(() => { if (refreshing === settled) refreshing = null; });
          refreshing = settled; return settled;
        },
        refreshSection(section) {
          if (sectionRequests.has(section)) return sectionRequests.get(section);
          const generation = refreshGeneration;
          if (section !== 'audio') publish({ catalogLoading: { ...snapshot.catalogLoading, [section]: true } });
          const operation = Promise.resolve().then(() => bridge.request('getStateSection', { section })).then(result => {
            const required = section === 'audio' ? ['audioCapture', 'audioTranscription'] : [section];
            if (!result || required.some(key => !result[key] || typeof result[key] !== 'object')
                || section !== 'audio' && !Array.isArray(result[section].apps))
              throw new Error(droyText("手机设置数据不完整，请重试"));
            if (generation === refreshGeneration) accept(result, true);
            else if (section !== 'audio' && snapshot.state?.[section])
              accept({ [section]: { ...snapshot.state[section], apps: result[section].apps } }, true);
            return result;
          }).finally(() => {
            if (sectionRequests.get(section) === operation) sectionRequests.delete(section);
            if (section !== 'audio') publish({ catalogLoading: { ...snapshot.catalogLoading, [section]: false } });
          });
          sectionRequests.set(section, operation); return operation;
        },
        observeSection(section) {
          sections.set(section, (sections.get(section) || 0) + 1);
          return () => { const count = sections.get(section) - 1; if (count) sections.set(section, count); else sections.delete(section); };
        },
        run(action, args = {}, refresh = true) {
          const key = action + (action === 'setPreference' ? ':' + args.key : '');
          if (mutations.has(key)) return mutations.get(key).promise;
          publish({ error: null, errorKey: null });
          const operation = mutationQueue.then(async () => {
            if (disposed) return;
            try {
              const result = await bridge.request(action, args);
              if (refresh) {
                if (validState(result)) { ++refreshGeneration; lastRefresh = Date.now(); accept(result); }
                else await controller.refresh({ force: true });
                for (const section of sections.keys())
                  if (section === 'audio' && action.startsWith('audio') || action === section || action === 'setPreference')
                    await controller.refreshSection(section);
              }
              return result;
            } catch (error) { publish({ error: error.message, errorKey: key }); throw error; }
          });
          mutationQueue = operation.catch(() => {});
          const settled = operation.finally(() => { mutations.delete(key); pending(); });
          mutations.set(key, { action, promise: settled }); pending(); return settled;
        },
        report(error) { publish({ error: error.message || String(error), errorKey: null }); },
        dispose() { disposed = true; ++refreshGeneration; listeners.clear(); views.clear(); sections.clear(); }
      };
      return controller;
    }

    function permissionRows(state) {
      const caps = state.capabilities || {};
      const system = { ...caps.systemPermissions, ...state.systemPermissions };
      const notifications = system.notifications || {};
      const diagnostics = caps.diagnostics?.privacy_usage;
      const diagnosticStatus = diagnostics?.available === true ? droyText("已配置")
        : diagnostics?.reason === 'usage_permission_required' ? droyText("请开启使用情况访问")
        : diagnostics?.available === false ? droyText("待配置，请通过电脑端权限配置工具授权")
        : droyText("状态暂不可用");
      const yesNo = value => value === true ? droyText("已授权") : value === false ? droyText("待授权") : droyText("状态暂不可用");
      const hardware = system.hardware || {};
      const supported = (kind, status) => hardware[kind] === false ? droyText("设备不支持") : status;
      const enabled = value => value === true ? droyText("已开启") : value === false ? droyText("未开启") : droyText("状态暂不可用");
      const locationStatus = system.locationPrecise === true ? droyText("精确位置已授权")
        : system.locationApproximate === true ? droyText("大致位置已授权") : yesNo(system.locationApproximate);
      const bluetoothStatus = system.bluetoothScan === true && system.bluetoothConnect === true ? droyText("已授权")
        : system.bluetoothScan === false || system.bluetoothConnect === false ? droyText("待授权") : droyText("状态暂不可用");
      const wifiScanStatus = system.locationPrecise === false ? droyText("需要精确位置权限")
        : system.locationEnabled === false ? droyText("请开启系统定位") : yesNo(system.wifiScan);
      const recovery = caps.accessibilityRecovery || {};
      let accessibilityStatus = droyText("待连接");
      if (caps.accessibilityConnected) accessibilityStatus = droyText("已连接");
      else if (recovery.status === 'restoring') accessibilityStatus = droyText("自动恢复中");
      else if (recovery.enabled === true) accessibilityStatus = droyText("已开启，服务待连接");
      else if (recovery.status === 'revoked') accessibilityStatus = droyText("系统中已关闭");
      else if (recovery.authorized === true && recovery.ready === false) accessibilityStatus = droyText("已授权，自动恢复待配置");
      else if (recovery.status === 'error') accessibilityStatus = droyText("自动恢复失败");
      else if (recovery.authorized === false || recovery.status === 'not_authorized') accessibilityStatus = droyText("未开启");
      else if (recovery.status === 'pending_restore') accessibilityStatus = droyText("待自动恢复");
      return [
        ['accessibility', droyText("无障碍服务"), accessibilityStatus, droyText("打开系统无障碍设置")],
        ['shizuku', droyText("Shizuku（可选）"), caps.shizuku?.authorized ? droyText("已授权") : caps.shizuku?.running ? droyText("待授权") : droyText("未启动"), droyText("授权 Shizuku")],
        ['notifications', droyText("运行通知"), yesNo(state.notificationPermissionGranted ?? system.runtimeNotifications), droyText("设置运行通知权限")],
        ['calendar', droyText("日历读取"), yesNo(system.calendar), droyText("设置日历读取权限")],
        ['contacts', droyText("联系人读取"), yesNo(system.contacts), droyText("设置联系人读取权限")],
        ['microphone', droyText("麦克风"), yesNo(system.microphone), droyText("设置麦克风权限")],
        ['camera', droyText("相机"), yesNo(system.camera), droyText("设置相机权限")],
        ['petOverlay', droyText("悬浮窗"), yesNo(system.overlay), droyText("设置悬浮窗权限")],
        ['location', droyText("位置访问"), locationStatus, droyText("设置位置权限")],
        ['locationSettings', droyText("系统定位"), enabled(system.locationEnabled), droyText("打开定位设置")],
        ['bluetooth', droyText("附近蓝牙设备"), supported('bluetooth', bluetoothStatus), hardware.bluetooth === false ? null : droyText("设置蓝牙权限")],
        ['bluetoothSettings', droyText("蓝牙开关"), supported('bluetooth', enabled(system.bluetoothEnabled)), hardware.bluetooth === false ? null : droyText("打开蓝牙设置")],
        ['wifiScan', droyText("Wi-Fi 扫描"), supported('wifi', wifiScanStatus), hardware.wifi === false ? null : droyText("设置扫描位置权限")],
        ['nearbyWifi', droyText("附近 Wi-Fi 设备"), supported('wifi', yesNo(system.nearbyWifi)), hardware.wifi === false ? null : droyText("设置附近 Wi-Fi 权限")],
        ['wifiSettings', droyText("Wi-Fi 开关"), supported('wifi', enabled(system.wifiEnabled)), hardware.wifi === false ? null : droyText("打开 Wi-Fi 设置")],
        ['nfcSettings', droyText("NFC 开关"), supported('nfc', enabled(system.nfcEnabled)), hardware.nfc === false ? null : droyText("打开 NFC 设置")],
        ['phoneState', droyText("蜂窝网络信息"), supported('telephony', yesNo(system.phoneState)), hardware.telephony === false ? null : droyText("设置电话状态权限")],
        ['activityRecognition', droyText("计步与活动识别"), supported('stepCounter', yesNo(system.activityRecognition)), hardware.stepCounter === false ? null : droyText("设置活动识别权限")],
        ['bodySensors', droyText("心率传感器"), supported('heartRate', yesNo(system.bodySensors)), hardware.heartRate === false ? null : droyText("设置心率权限")],
        ['notificationAccess', droyText("通知访问"), notifications.permissionGranted === true
          ? notifications.connected ? droyText("已授权，服务已连接") : droyText("已授权，服务待连接")
          : notifications.permissionGranted === false ? droyText("待授权") : droyText("状态暂不可用"), droyText("打开通知访问设置")],
        ['usage', droyText("使用情况访问"), yesNo(system.usage), droyText("打开使用情况访问设置")],
        ['diagnostics', droyText("系统诊断读取"), diagnosticStatus, null],
        ['brightness', droyText("修改系统亮度"), yesNo(system.brightness), droyText("设置系统亮度修改权限")]
      ];
    }

    function createMicrophoneGuard(target, bridge) {
      const devices = target.navigator?.mediaDevices;
      if (!devices || typeof devices.getUserMedia !== 'function') return { stop() {}, dispose() {} };
      const original = devices.getUserMedia;
      const streams = new Set();
      let closed = false;
      let generation = 0;
      let requesting = false;
      const allowed = async () => {
        const state = await bridge.request('getState');
        if (closed || state.runtimeReady === false || state.toolsPaused === true || state.capabilities?.product?.stopped === true
            || state.groups?.some(group => group.preference === 'allowAudioMicrophone' && group.enabled === false))
          throw new Error(droyText("麦克风采集已关闭或手机操作已暂停"));
      };
      const release = () => bridge.request('audioDictation',{operation:'release'}).catch(error => {
        if (!closed) target.console?.warn?.('Droy microphone release failed', error.message);
      });
      const forget = stream => { if (streams.delete(stream) && streams.size === 0) release(); };
      const stopStream = (stream, interrupted = false) => {
        const tracked = streams.has(stream);
        for (const track of stream.getTracks()) {
          track.stop();
          // stop() does not emit ended. DSH's interruption handler disposes the
          // MediaRecorder and discards its chunks when product access is revoked.
          if (interrupted && target.Event) track.dispatchEvent?.(new target.Event('ended'));
        }
        forget(stream);
        if (!tracked) release();
      };
      const guarded = async function(constraints) {
        if (!constraints?.audio) return original.call(devices, constraints);
        if (requesting || streams.size) throw new Error(droyText("输入框正在使用麦克风"));
        requesting = true;
        const started = generation;
        let stream, claimed = false;
        try {
          await allowed();
          await bridge.request('audioDictation',{operation:'claim'}); claimed = true;
          stream = await original.call(devices, constraints);
          await allowed();
          if (started !== generation) throw new Error(droyText("本次麦克风采集已取消"));
          streams.add(stream);
          for (const track of stream.getAudioTracks()) {
            const stopTrack = track.stop.bind(track);
            track.stop = () => { stopTrack(); if (stream.getAudioTracks().every(item=>item.readyState === 'ended')) forget(stream); };
            track.addEventListener?.('ended', () => forget(stream), {once:true});
          }
          return stream;
        } catch (error) {
          if (stream) stopStream(stream, true); else if (claimed) release();
          throw error;
        } finally { requesting = false; }
      };
      devices.getUserMedia = guarded;
      const stop = () => { ++generation; for (const stream of [...streams]) stopStream(stream, true); };
      return { stop, dispose() { closed = true; stop(); if (devices.getUserMedia === guarded) devices.getUserMedia = original; } };
    }

    function createBackHandler(ctx, doc, closeModal, touch) {
      return () => {
        touch?.cancelGestures({ closeRows: false });
        const choice = doc.querySelector('.droy-voice-choice[open]');
        if (choice) { choice.open = false; choice.querySelector('summary')?.focus(); return true; }
        // The mobile drawer uses dialog semantics but is owned by layout, not DSH's modal stack.
        const layers = Array.from(doc.querySelectorAll(modalSelector)).filter(element => !element.hasAttribute('data-droy-sidebar'));
        if (layers.length && touch?.closeMenu(layers.at(-1))) return true;
        if (layers.length && layers.at(-1).getAttribute?.('role') !== 'menu') {
          closeModal(doc);
          return true;
        }
        if (!layers.length && touch?.closeMenu()) return true;
        if (layers.length) {
          // Other DSH menus own their Escape handler rather than the modal stack.
          const KeyboardEvent = doc.defaultView?.KeyboardEvent;
          if (KeyboardEvent && doc.dispatchEvent) doc.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true, cancelable: true }));
          else closeModal(doc);
          return true;
        }
        if (touch?.closeExpandedRow()) return true;
        if (doc.querySelector('[data-droy-drawer-open="true"]')) {
          ctx.layout.toggleSidebar();
          return true;
        }
        if (ctx.sidebarRight?.isExpanded()) {
          ctx.sidebarRight.toggleExpanded();
          return true;
        }
        if (ctx.layout.panelInfo.getSnapshot().activePanelId !== null) {
          ctx.layout.selectPanel(null);
          return true;
        }
        return false;
      };
    }

    function createSettingsOpenHandler(ctx, doc) {
      return () => {
        if (doc.querySelector('[data-droy-drawer-open="true"]')) ctx.layout.toggleSidebar();
        if (ctx.sidebarRight?.isExpanded()) ctx.sidebarRight.toggleExpanded();
        // Settings is a destination above chat, so returning cannot reveal a
        // stale workspace panel or the drawer which launched it.
        if (![null,'droy-companions'].includes(ctx.layout.panelInfo.getSnapshot().activePanelId)) ctx.layout.selectPanel(null);
      };
    }

    function observePanelNavigation(ctx, doc) {
      let previous = ctx.layout.panelInfo.getSnapshot().activePanelId;
      return ctx.layout.panelInfo.subscribe(() => {
        const selected = ctx.layout.panelInfo.getSnapshot().activePanelId;
        if (selected === previous) return;
        previous = selected;
        // A global panel replaces chat behind the modal drawer. Release that
        // drawer only for a new destination; initial/restored state is inert.
        if (selected !== null && doc.querySelector('[data-droy-drawer-open="true"]')) ctx.layout.toggleSidebar();
      });
    }

    function observePetContext(ctx, doc, controller) {
      let disposed = false, wanted, sent, sending = false;
      const flush = () => {
        if (disposed || sending || controller.getSnapshot().pending || !wanted || wanted.key === sent) return;
        const next = wanted;
        sent = next.key;
        sending = true;
        controller.run('petContext', next.value, false).catch(() => {}).finally(() => {
          sending = false;
          flush();
        });
      };
      const update = () => {
        const taskId = ctx.sidebarRight.mounted.getSnapshot() || '';
        const value = { taskId, chatVisible: !!taskId && doc.visibilityState !== 'hidden' && !ctx.sidebarRight.isExpanded() };
        wanted = { key: JSON.stringify(value), value };
        flush();
      };
      const unsubscribers = [ctx.sidebarRight.mounted.subscribe(update), ctx.layout.panelInfo.subscribe(update), controller.subscribe(flush)];
      doc.addEventListener('visibilitychange', update);
      update();
      return () => {
        disposed = true;
        for (const unsubscribe of unsubscribers) unsubscribe();
        doc.removeEventListener('visibilitychange', update);
      };
    }

    async function setThemeDurably(ctx, target, preference, timeoutMs = 10000) {
      const form = ctx.configForms.get('ui-theme');
      ctx.theme.setTheme(preference);
      const accepted = () => form.getSnapshot().value?.preference === preference
        && ctx.theme.getTheme().preference === preference;
      if (accepted()) return;
      await new Promise((resolve, reject) => {
        let unsubscribe = () => {};
        const timer = target.setTimeout(() => {
          unsubscribe();
          reject(new Error(droyText("主题设置尚未保存，请重试")));
        }, timeoutMs);
        const check = () => {
          if (form.getSnapshot().status === 'unavailable') {
            target.clearTimeout(timer);
            unsubscribe();
            reject(new Error(droyText("主题设置暂不可用，请重试")));
          } else if (accepted()) {
            target.clearTimeout(timer);
            unsubscribe();
            resolve();
          }
        };
        unsubscribe = form.subscribe(check);
        check();
      });
    }

    function readThemeBackground(target) {
      // DSH installs resolved palette tokens on body, below the document root.
      const value = target.getComputedStyle(target.document.body).getPropertyValue('--dsw-alias-bg-base').trim();
      if (/^#[\da-f]{6}$/i.test(value)) return value.toUpperCase();
      if (/^#[\da-f]{3}$/i.test(value)) return '#' + [...value.slice(1)].map(char => char + char).join('').toUpperCase();
      const rgb = value.match(/^rgb\(\s*(\d+)\s*[, ]\s*(\d+)\s*[, ]\s*(\d+)\s*\)$/i);
      if (rgb && rgb.slice(1).every(channel => Number(channel) <= 255))
        return '#' + rgb.slice(1).map(channel => Number(channel).toString(16).padStart(2, '0')).join('').toUpperCase();
      throw new Error(droyText("页面主题颜色尚未就绪，请重新加载"));
    }

    function observeUiReady(target, onReady, onError) {
      const doc = target.document;
      let disposed = false;
      let scheduled = false;
      let completing = false;
      let observer;
      let firstFrame;
      let secondFrame;
      const isReady = () => {
        const frame = doc.querySelector('[data-droy-frame]');
        return doc.documentElement.hasAttribute('data-droy-mobile')
          && doc.documentElement.dataset.droyInsetsReady === 'true'
          && doc.getElementById('droy-mobile-layout')
          && target.getComputedStyle(doc.documentElement).getPropertyValue('--droy-mobile-layout-ready').trim() === '1'
          && frame && !frame.hasAttribute('data-droy-drawer-open')
          && (doc.querySelector('[data-droy-im-screen]')
            || doc.querySelector('[data-droy-menu]') && doc.querySelector('[data-composer-input]'));
      };
      const check = () => {
        if (disposed || scheduled || completing || !isReady()) return;
        scheduled = true;
        firstFrame = target.requestAnimationFrame(() => {
          secondFrame = target.requestAnimationFrame(() => {
            scheduled = false;
            if (disposed || !isReady()) return;
            completing = true;
            observer?.disconnect();
            Promise.resolve().then(() => { if (!disposed) return onReady(); }).then(() => {
              if (!disposed) doc.documentElement.dataset.droyUiReady = 'true';
            }).catch(error => { if (!disposed) onError(error); });
          });
        });
      };
      observer = new target.MutationObserver(check);
      observer.observe(doc.documentElement, { childList: true, subtree: true, attributes: true,
        attributeFilter: ['data-droy-mobile', 'data-droy-drawer-open', 'data-droy-insets-ready'] });
      check();
      return () => {
        disposed = true;
        observer.disconnect();
        target.cancelAnimationFrame(firstFrame);
        target.cancelAnimationFrame(secondFrame);
      };
    }

    function petAssetUrl(id, file) {
      if (!/^[a-zA-Z0-9-]{1,80}$/.test(id) || !/^[a-zA-Z0-9_-]+\.(png|json)$/.test(file))
        throw new Error(droyText("形象素材地址无效"));
      return `/droy/pets/asset?id=${encodeURIComponent(id)}&file=${encodeURIComponent(file)}`;
    }

    function createNotificationPluginApi(target) {
      return async (enabled, signal) => {
        const response = await target.fetch('/droy/notify/plugin', {
          method: enabled === undefined ? 'GET' : 'POST', credentials: 'same-origin', cache: 'no-store', signal,
          headers: enabled === undefined ? undefined : { 'Content-Type': 'application/json' },
          body: enabled === undefined ? undefined : JSON.stringify({ enabled })
        });
        let data;
        try { data = await response.json(); }
        catch { throw new Error(droyText("通知设置暂不可用，请重试")); }
        if (!response.ok) throw new Error(typeof data.error === 'string' ? data.error : data.error?.message || droyText("通知设置未保存"));
        if (typeof data.enabled !== 'boolean') throw new Error(droyText("通知插件状态不完整，请重试"));
        return data;
      };
    }

    function createPetApi(target) {
      return async (path, body, signal) => {
        if (!['', '/create', '/generate', '/retry'].includes(path)) throw new Error(droyText("形象操作无效"));
        const response = await target.fetch(`/droy/pets${path}`, {
          method: body ? 'POST' : 'GET', credentials: 'same-origin', cache: 'no-store', signal,
          headers: body ? { 'Content-Type': 'application/json' } : undefined,
          body: body ? JSON.stringify(body) : undefined
        });
        let data;
        try { data = await response.json(); }
        catch { throw new Error(droyText("形象服务返回了无效内容，请重新打开设置")); }
        if (!response.ok) throw new Error(typeof data.error === 'string' ? data.error
          : typeof data.error?.message === 'string' ? data.error.message : droyText("形象操作未完成，请重试"));
        return data;
      };
    }

    function petPreviewFrame(manifest, requested, side, elapsed, totalDuration) {
      let name = requested;
      let state = manifest.states?.[name];
      if (!state) throw new Error(droyText("形象缺少这个动作"));
      const directional = side === 'left' ? state.rightState : state.leftState;
      if (directional) { name = directional; state = manifest.states[directional]; }
      const atlas = manifest.atlases[state.atlas];
      if (!atlas || !state.frames?.length || state.frames.length !== state.durationsMs?.length)
        throw new Error(droyText("形象动作数据不完整"));
      // Entry must include the whole blink before the body appears; idle timing remains unchanged.
      const timing = totalDuration === undefined ? state.durationsMs : state.frames.map((_, index) =>
        index === state.frames.length - 1 ? totalDuration - Math.floor(totalDuration / state.frames.length) * index
          : Math.floor(totalDuration / state.frames.length));
      const duration = timing.reduce((total, value) => total + value, 0);
      let index = 0, end = timing[0];
      while (index < state.frames.length - 1 && elapsed >= end) end += timing[++index];
      const frames = state.frames.map(frame => atlas.frames[frame].sourceRect);
      const source = frames[index];
      const facing = side === 'left' ? 'right' : 'left';
      return { name, file: atlas.file, source, duration,
        maxWidth: Math.max(...frames.map(rect => rect[2])), maxHeight: Math.max(...frames.map(rect => rect[3])),
        mirror: state.mirrorSafe === true && ['left', 'right'].includes(state.facing) && state.facing !== facing };
    }

    function createPetPreview(canvas, manifest, images, target) {
      const context = canvas.getContext('2d');
      if (!context) throw new Error(droyText("这个设备无法预览形象动画"));
      let side = 'right', active = 'idle', started = 0, frame, disposed = false;
      const last = new Map();
      const reduced = () => target.matchMedia?.('(prefers-reduced-motion: reduce)').matches === true;
      const draw = now => {
        if (disposed) return;
        const elapsed = reduced() ? Number.MAX_SAFE_INTEGER : Math.max(0, now - started);
        const entering = active.startsWith('enter'), exiting = active.startsWith('exit');
        const facingSide = exiting ? side === 'left' ? 'right' : 'left' : side;
        const peekIntro = active === 'enterPeek' && manifest.states[side === 'left' ? 'peekLeft' : 'peekRight'];
        const bodyPose = petPreviewFrame(manifest, active, facingSide, Math.max(0, elapsed - (peekIntro ? 500 : 0)));
        const pose = peekIntro && elapsed < 500 ? petPreviewFrame(manifest, side === 'left' ? 'peekLeft' : 'peekRight', side, elapsed, 500) : bodyPose;
        const size = canvas.getBoundingClientRect(), density = target.devicePixelRatio || 1;
        const width = Math.max(1, size.width), height = Math.max(1, size.height);
        if (canvas.width !== Math.round(width * density) || canvas.height !== Math.round(height * density)) {
          canvas.width = Math.round(width * density); canvas.height = Math.round(height * density);
        }
        context.setTransform(density, 0, 0, density, 0, 0);
        context.clearRect(0, 0, width, height);
        const progress = Math.min(1, elapsed / Math.max(1, bodyPose.duration + (peekIntro ? 500 : 0)));
        if (!(exiting && progress === 1)) {
          const displaySize = peekIntro && elapsed < 500 ? 64 : 90;
          const scale = Math.min(displaySize / pose.maxWidth, displaySize / pose.maxHeight);
          const [sx, sy, sw, sh] = pose.source;
          const edge = side === 'left' ? -70 : width + 70;
          const resting = side === 'left' ? width * .32 : width * .68;
          const t = 1 - Math.pow(1 - progress, 2);
          const peekX = side === 'left' ? 32 : width - 32;
          const x = peekIntro ? elapsed < 500 ? edge + (peekX - edge) * Math.min(1, elapsed / 150)
            : peekX + (resting - peekX) * Math.min(1, (elapsed - 500) / bodyPose.duration)
            : entering ? edge + (resting - edge) * t : exiting ? resting + (edge - resting) * t : resting;
          const y = height - 24 - (active === 'enterJump' ? Math.sin(progress * Math.PI) * 34 : 0);
          context.save(); context.translate(x, y);
          if (pose.mirror) context.scale(-1, 1);
          context.shadowColor = 'rgba(180,180,180,.7)'; context.shadowBlur = 1;
          context.drawImage(images[pose.file], sx, sy, sw, sh, -sw * scale / 2, -sh * scale, sw * scale, sh * scale);
          context.restore();
        }
        if (progress < 1) frame = target.requestAnimationFrame(draw);
        else if (!exiting && active !== 'idle') { active = 'idle'; started = now; frame = target.requestAnimationFrame(draw); }
      };
      const replay = name => {
        if (disposed) return;
        target.cancelAnimationFrame(frame); active = name; started = target.performance.now();
        frame = target.requestAnimationFrame(draw);
      };
      return {
        setSide(value) { side = value === 'left' ? 'left' : 'right'; replay('idle'); },
        play(kind) {
          const choices = (manifest.variants?.[kind] || [kind]).filter(name => manifest.states[name]);
          if (!choices.length) throw new Error(droyText("形象缺少这个动作"));
          const alternatives = choices.filter(name => name !== last.get(kind));
          const pool = alternatives.length ? alternatives : choices;
          const weight = name => {
            const key = name.toLowerCase();
            switch (manifest.persona?.temperament || 'curious') {
              case 'playful': return /jump|wave|tail/.test(key) ? 4 : 1;
              case 'shy': return /peek|shy|hide/.test(key) ? 4 : 1;
              case 'calm': return /walk|stretch/.test(key) || key === 'idle' ? 4 : 1;
              default: return /peek|tilt/.test(key) ? 3 : 1;
            }
          };
          let choice = Math.random() * pool.reduce((sum, name) => sum + weight(name), 0);
          const selected = pool.find(name => (choice -= weight(name)) < 0) || pool[pool.length - 1];
          last.set(kind, selected); replay(selected);
        },
        resize() { if (!disposed) { target.cancelAnimationFrame(frame); frame = target.requestAnimationFrame(draw); } },
        dispose() { disposed = true; target.cancelAnimationFrame(frame); }
      };
    }

    const styles = `
.droy-mode-picker{max-width:calc(100vw - 24px)}
[data-droy-mobile] [data-composer-dock]{box-sizing:border-box;display:grid;width:100%;max-width:var(--dsh-composer-card-max-width);grid-template-columns:repeat(3,minmax(0,1fr));gap:4px 8px}
[data-composer-dock]>*{grid-column:1 / -1;min-width:0}
[data-composer-dock]>[data-composer-stat=activity]{grid-column:1;grid-row:1;justify-self:start}
[data-composer-dock]>[data-composer-stat=usage]{grid-column:2;grid-row:1;justify-self:center}
[data-composer-dock]>[data-droy-context-meter]{grid-column:3;grid-row:1;justify-self:end}
@media(hover:none){[data-composer-dock] button[aria-expanded=false]{background:transparent}}
[data-composer-dock]:has([data-droy-token-usage]) [data-droy-voice-dock]{order:2}
[data-composer-seat]:has([data-droy-token-usage]){box-sizing:border-box;min-height:0;max-height:100%;overflow-y:auto}
.droy-brand-mark{display:block;object-fit:contain;flex:none;border-radius:25%;background-color:var(--droy-logo-light,#F2EEE7)}.droy-brand-identity{display:flex;align-items:center;min-width:0;max-width:100%;width:100%;height:auto;letter-spacing:normal}.droy-brand-name{font-size:20px;line-height:26px;font-weight:600;overflow-wrap:anywhere;min-width:0}.droy-brand-badge{font-size:12px;line-height:18px;color:var(--dsw-alias-label-secondary);overflow-wrap:anywhere}.droy-brand-welcome{display:block;max-width:100%;overflow-wrap:anywhere;text-wrap:balance}.droy-brand-settings{display:block}.droy-brand-settings details{min-width:0}.droy-brand-settings summary{display:flex;align-items:center;min-height:48px;cursor:pointer}.droy-brand-settings summary:before{content:'›';margin-right:8px}.droy-brand-settings details[open]>summary:before{transform:rotate(90deg)}.droy-brand-settings summary:focus-visible{outline:2px solid var(--dsw-alias-state-business-primary);outline-offset:2px}.droy-brand-settings-body{display:flex;flex-direction:column;gap:16px}.droy-brand-field{min-width:0}.droy-pet-brand-preview{width:72px;height:72px;align-self:center}
body[data-ds-dark-theme] .droy-brand-mark{background-color:var(--droy-logo-dark,#D8D3CB)}
.droy-brand-previews{display:flex;flex-wrap:wrap;gap:12px;min-width:0}.droy-brand-previews figure{display:flex;flex:1 1 100px;min-width:0;align-items:center;flex-direction:column;gap:12px;margin:0;padding:16px 8px;border-radius:12px;font-size:12px;line-height:18px}.droy-brand-previews [data-droy-brand-preview=light]{background:#F7F7F8;color:#262626}.droy-brand-previews [data-droy-brand-preview=dark]{background:#1B1B1C;color:#EFEFEF}
body [data-droy-brand-preview=light] .droy-brand-mark{background-color:var(--droy-logo-light,#F2EEE7)}
body [data-droy-brand-preview=dark] .droy-brand-mark{background-color:var(--droy-logo-dark,#D8D3CB)}
.droy-logo-background-settings{display:flex;flex-direction:column;gap:12px;min-width:0}.droy-logo-color-field{display:flex;flex-direction:column;gap:4px;min-width:0}.droy-logo-color-field .droy-input{min-height:48px}.droy-logo-background-settings .droy-actions{flex-wrap:wrap}
[data-droy-drawer-header]{height:auto!important;min-height:64px;align-items:center!important;overflow:visible!important}[data-droy-drawer-header]>:has([data-droy-brand-container]){height:auto!important;min-width:0;flex:1;overflow:visible!important}[data-droy-brand-container]{height:auto!important;min-width:0;flex:1;align-items:center!important;overflow:visible!important}[data-droy-brand-name-slot]{height:auto!important;flex:1;min-width:0;overflow:visible!important}[data-droy-brand-container]>:has(>.droy-brand-mark){height:auto!important;flex:none;width:32px}[data-droy-drawer-header] .droy-brand-mark{width:32px;height:32px}

.droy-notify-apps,.droy-notify-target{display:flex;flex-direction:column;gap:12px;min-width:0}.droy-notify-target{margin-top:12px;align-items:stretch}.droy-notify-target .droy-button{align-self:flex-start;min-height:48px;white-space:normal}.droy-notify-apps .droy-input,.droy-notify-target .droy-input{flex:none;min-height:50px;height:50px}.droy-notify-apps .droy-input>input,.droy-notify-target .droy-input>input{box-sizing:border-box;min-height:48px;height:48px;padding:0}
.droy-row{display:flex;align-items:center;justify-content:space-between;gap:24px;padding:16px 0;border-bottom:.5px solid var(--dsw-alias-border-l2);min-width:0;color:var(--dsw-alias-label-primary)}
.droy-row-copy{display:flex;flex:1;min-width:0;flex-direction:column;gap:4px}.droy-row-title{font-size:14px;line-height:20px}.droy-description{margin:0;color:var(--dsw-alias-label-secondary);font-size:12px;line-height:18px;overflow-wrap:anywhere}
.droy-screen-display .droy-button{min-height:48px;height:auto;white-space:normal;line-height:20px}
.droy-input{box-sizing:border-box;flex:1;min-width:0;width:100%}.droy-input::placeholder,.droy-input input::placeholder,.droy-pet-field textarea::placeholder{color:var(--dsw-alias-label-secondary);opacity:1}.droy-switch-wrap{display:inline-flex;flex:none;align-items:center;justify-content:center;min-width:var(--droy-touch-target,48px);min-height:var(--droy-touch-target,48px);cursor:pointer}
.droy-name-editor{display:flex;gap:8px;align-items:center;max-width:100%;width:100%;margin-top:4px}.droy-button{flex:none;max-width:100%}.droy-advanced summary:focus-visible{outline:2px solid var(--dsw-focus-ring-color,var(--dsw-alias-state-business-primary));outline-offset:3px}
.droy-pet-settings{flex-direction:column;align-items:stretch;gap:12px}.droy-pet-header{display:flex;align-items:center;justify-content:space-between;gap:16px;min-width:0}.droy-pet-name-editor{flex-wrap:wrap;align-items:stretch}.droy-pet-name-editor .droy-input{flex:1 1 140px}.droy-pet-settings .droy-actions{margin-top:0}.droy-pet-settings .droy-status{overflow-wrap:anywhere}
.droy-pet-choices{display:flex;flex-wrap:wrap;gap:6px;margin-top:4px}.droy-pet-choices .droy-button{flex:1 1 70px;min-height:48px;height:auto;white-space:normal;line-height:20px;padding:8px}.droy-pet-choices [aria-pressed=true]{border-color:var(--dsw-alias-label-primary);background:var(--dsw-alias-bg-l2,var(--dsw-alias-bg-base));font-weight:600}
.droy-touch-switch[role=switch][aria-checked]{width:48px;height:48px;padding:0;background:transparent;border-radius:8px}.droy-touch-switch[role=switch]:before{content:'';position:absolute;left:6px;top:14px;width:36px;height:20px;border-radius:999px;background:var(--dsw-alias-border-l3);pointer-events:none}.droy-touch-switch[role=switch][aria-checked=true]:before{background:var(--dsw-alias-brand-primary)}.droy-touch-switch[role=switch]>span{position:absolute;left:8px;top:16px}
.droy-notification-exception-link{display:flex;align-items:center;justify-content:space-between;gap:16px;width:100%;min-height:48px;text-align:left;white-space:normal}.droy-notification-exceptions[role=dialog]{position:fixed;inset:0;box-sizing:border-box;width:100vw;max-width:none;height:100dvh;max-height:none;margin:0;border-radius:0;padding:calc(var(--droy-safe-top,0px) + 8px) calc(var(--droy-safe-right,0px) + 16px) max(var(--droy-safe-bottom,0px),var(--droy-ime-bottom,0px)) calc(var(--droy-safe-left,0px) + 16px);overflow:hidden}.droy-notification-exceptions button[aria-label]{min-width:48px;min-height:48px}.droy-notification-exceptions-content{flex:1;min-height:0;overflow:auto;overscroll-behavior:contain}.droy-notification-exceptions-content>div:first-child{flex:none;padding:0 0 8px}.droy-notification-exceptions-content>div:last-child{display:flex;flex:none;min-height:0;margin:0;padding:0}.droy-notification-exceptions-body{display:flex;flex:none;flex-direction:column;gap:16px;min-width:0;min-height:0}.droy-notification-exceptions-body .droy-input{flex:none;min-height:50px;height:50px}.droy-notification-exceptions-body .droy-input>input{box-sizing:border-box;min-height:48px;height:48px;padding:0}.droy-notification-exception{padding:16px 0;border-bottom:.5px solid var(--dsw-alias-border-l2);min-width:0}.droy-notification-exception .droy-row-title{display:block;overflow-wrap:anywhere}.droy-notification-modes{display:flex;gap:8px;flex-wrap:wrap;margin-top:12px}.droy-notification-modes .droy-button{flex:1 1 80px;min-height:48px;height:auto;padding:8px;white-space:normal;line-height:20px}.droy-notification-modes [aria-pressed=true]{border-color:var(--dsw-alias-label-primary);background:var(--dsw-alias-bg-l2,var(--dsw-alias-bg-base));font-weight:600}
.droy-notification-settings{flex-direction:column;align-items:stretch;gap:12px}.droy-notification-header{display:flex;align-items:center;justify-content:space-between;gap:16px;min-width:0}.droy-notification-details{min-width:0}.droy-notification-details>summary{display:flex;align-items:center;gap:8px;min-height:48px;cursor:pointer;font-size:14px}.droy-notification-details>summary:before{content:'›';font-size:20px;transform:rotate(0deg)}.droy-notification-details[open]>summary:before{transform:rotate(90deg)}.droy-notification-details>summary:focus-visible{outline:2px solid var(--dsw-focus-ring-color,var(--dsw-alias-state-business-primary));outline-offset:2px}.droy-notification-body{display:flex;flex-direction:column;gap:12px;padding-top:4px;min-width:0}.droy-notification-body .droy-button,.droy-notification-settings>.droy-actions .droy-button{min-height:48px;height:auto;white-space:normal;overflow-wrap:anywhere;line-height:20px;padding:8px 12px}.droy-notification-body .droy-actions{margin-top:0}.droy-notification-list{max-height:320px;overflow-y:auto;overscroll-behavior:contain;min-width:0}.droy-notification-rule,.droy-notification-entry{display:flex;flex-direction:column;gap:6px;padding:12px 0;border-bottom:.5px solid var(--dsw-alias-border-l2);min-width:0;overflow-wrap:anywhere}.droy-notification-rule .droy-actions{justify-content:flex-end}.droy-notification-group-title{margin:4px 0;font-size:14px;font-weight:500}.droy-notification-empty{margin:0;padding:8px 0;color:var(--dsw-alias-label-secondary);font-size:13px;line-height:20px}
.droy-pet-studio{display:flex;flex-direction:column;gap:12px;min-width:0;width:100%}.droy-pet-studio-body{display:flex;flex-direction:column;gap:14px;padding-top:12px;border-top:1px solid var(--dsw-alias-border-l2)}.droy-pet-form{display:flex;flex-direction:column;gap:12px}.droy-pet-field{display:flex;flex-direction:column;gap:5px;min-width:0}.droy-pet-field textarea{box-sizing:border-box;resize:vertical;min-height:80px;width:100%;max-width:100%;border:1px solid var(--dsw-alias-border-l2);border-radius:8px;padding:10px;background:var(--dsw-alias-bg-base);color:var(--dsw-alias-label-primary);font:inherit;font-size:14px;line-height:22px}.droy-pet-field textarea:focus-visible,.droy-pet-stage:focus-visible{outline:2px solid var(--dsw-alias-state-business-primary,var(--dsw-alias-label-primary));outline-offset:2px}.droy-pet-studio .droy-button{min-height:48px;height:auto;white-space:normal;overflow-wrap:anywhere;line-height:20px;padding:8px 12px}.droy-pet-card{display:flex;flex-direction:column;gap:10px;border:1px solid var(--dsw-alias-border-l2);border-radius:10px;padding:12px;min-width:0}.droy-pet-card .droy-row-title{overflow-wrap:anywhere}.droy-pet-reference{width:144px;max-width:100%;height:144px;object-fit:contain;align-self:center;background:var(--dsw-alias-bg-l2,var(--dsw-alias-bg-base));border-radius:8px}.droy-pet-voice{margin:0;padding-left:20px;font-size:13px;line-height:22px;overflow-wrap:anywhere}.droy-pet-stage{display:block;box-sizing:border-box;width:100%;height:164px;padding:0;overflow:hidden;border:1px solid var(--dsw-alias-border-l2);border-radius:10px;background:var(--dsw-alias-bg-l2,var(--dsw-alias-bg-base));touch-action:pan-y;position:relative}.droy-pet-stage canvas{display:block;width:100%;height:100%}.droy-pet-preview{display:flex;flex-direction:column;gap:8px}.droy-pet-preview .droy-actions{gap:6px}.droy-pet-preview .droy-button{flex:1 1 62px;padding:8px}.droy-pet-current{font-size:12px;color:var(--dsw-alias-label-secondary)}
.droy-permissions{color:var(--dsw-alias-label-primary);font-size:14px;line-height:22px}.droy-section-title{font-size:16px;font-weight:500;margin:24px 0 4px}.droy-permissions>.droy-section-title:first-child{margin-top:0}.droy-actions{display:flex;flex-wrap:wrap;gap:8px;margin-top:12px}.droy-state{padding:12px 0;font-size:13px;line-height:20px}.droy-error{color:var(--dsw-alias-state-danger-primary,var(--dsw-alias-label-primary));font-size:13px;line-height:20px;padding:10px 0}.droy-status{font-size:12px;color:var(--dsw-alias-label-tertiary)}.droy-system-row{align-items:center;gap:16px}.droy-system-row .droy-row-copy{gap:4px}.droy-system-row .droy-button{flex:0 1 45%;font-size:12px}.droy-system-row .droy-button{min-height:var(--droy-touch-target,48px);height:auto;white-space:normal;line-height:18px;padding:8px 10px}.droy-file-row{align-items:flex-start}.droy-advanced{margin-top:24px;border-top:1px solid var(--dsw-alias-border-l2);padding-top:16px}.droy-advanced summary{cursor:pointer;min-height:32px}.droy-inline-error{margin-top:8px}
.droy-permission-presets .droy-button,.droy-permission-confirm .droy-button{flex:1 1 150px;min-height:48px;height:auto;white-space:normal;line-height:22px;overflow-wrap:anywhere;padding:10px 12px}.droy-permission-confirm[role=dialog]{box-sizing:border-box;width:calc(100vw - 32px);max-width:420px;max-height:calc(100dvh - 32px);overflow:auto}.droy-permission-confirm-content{min-width:0;overflow-wrap:anywhere}.droy-permission-confirm .droy-description{line-height:1.6}.droy-permission-confirm .droy-actions{padding-bottom:4px}
.droy-audio-settings{flex-direction:column;align-items:stretch;gap:12px}.droy-audio-settings details{min-width:0}.droy-audio-settings summary{display:flex;align-items:center;min-height:48px;cursor:pointer;font-weight:500}.droy-audio-body{display:flex;flex-direction:column;gap:12px;min-width:0;padding-top:8px}.droy-audio-body label{display:flex;flex-direction:column;gap:6px;min-width:0;font-size:14px;line-height:22px}.droy-audio-body select,.droy-audio-body input{box-sizing:border-box;width:100%;max-width:100%;min-height:48px;padding:10px;border:1px solid var(--dsw-alias-border-l2);border-radius:8px;background:var(--dsw-alias-bg-base);color:var(--dsw-alias-label-primary);font:inherit}.droy-audio-body .droy-audio-check{display:flex;flex-direction:row;align-items:center;gap:10px;min-height:48px}.droy-audio-check input{width:22px;min-height:22px;flex:none}.droy-audio-body .droy-button{min-height:48px;height:auto;white-space:normal;line-height:20px;padding:8px 12px}.droy-audio-card{display:flex;flex-direction:column;gap:6px;min-width:0;border:1px solid var(--dsw-alias-border-l2);border-radius:8px;padding:12px;overflow-wrap:anywhere}.droy-audio-text{white-space:pre-wrap;overflow-wrap:anywhere;font-size:14px;line-height:22px;margin:0}.droy-audio-card .droy-actions{margin-top:4px}.droy-audio-body .droy-actions{margin-top:0}.droy-audio-body progress{width:100%;max-width:100%}
`;

    function apply(ctx) {
      droyLocale = ctx.locale;
      const target = window;
      const doc = target.document;
      const bridge = createNativeBridge(target);
      const microphone = createMicrophoneGuard(target, bridge);
      const controller = createController(bridge);
      const stopBranding = installBranding(target, controller);
      const stopLocaleCopy = ctx.locale?.register?.('droy.mobile', DROY_UI_COPY);
      const previousTouch = target.DroyTouch;
      const touch = createTouchController(target, {
        closeDrawer: () => ctx.layout.toggleSidebar(),
        hasModal: () => Array.from(doc.querySelectorAll(modalSelector))
          .some(element => !element.hasAttribute('data-droy-sidebar'))
      });
      target.DroyTouch = touch;
      target.dispatchEvent(new target.CustomEvent('droy-touch-ready'));
      let disposed = false;
      let nativeLocale = ctx.locale?.getSnapshot().active;
      const refreshLocaleState = () => {
        if (disposed) return;
        Promise.resolve().then(() => target.__DSH_LOCALE__?.whenSynced?.()).then(() => {
          // Each accepted change gets a read. The controller's generation check
          // prevents a slower response from an earlier language replacing it.
          if (!disposed) return controller.refresh({ force: true });
        }).catch(error => controller.report(error));
      };
      // A native acknowledgement also arrives after retrying the same language.
      // The Host locale need not change again for native labels to recover.
      target.addEventListener('droy-native-locale-change', refreshLocaleState);
      const stopLocaleState = ctx.locale?.subscribe(() => {
        const next = ctx.locale.getSnapshot().active;
        if (next === nativeLocale) return;
        nativeLocale = next;
        if (!target.__DSH_LOCALE__) refreshLocaleState();
      });
      let pendingNotificationManagement = false;
      const themeFrames = new Map();
      const useState = (fields, actions) => {
        const store = fields ? controller.view(fields, actions) : controller;
        return React.useSyncExternalStore(store.subscribe, store.getSnapshot, store.getSnapshot);
      };
      const watchSection = (section, active = true) => React.useEffect(() => {
        if (!active) return;
        const stop = controller.observeSection(section);
        controller.refreshSection(section).catch(error => controller.report(error));
        return stop;
      }, [section, active]);
      const submit = (action, args, refresh = true) => controller.run(action, args, refresh).catch(() => {});
      let syncedTimeZone = '';
      const syncTimeZone = async state => {
        if (!state.timeZone || state.timeZone === syncedTimeZone) return;
        const response = await target.fetch('/droy/companions/settings', { method: 'PATCH', credentials: 'same-origin',
          headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ timeZone: state.timeZone }) });
        if (!response.ok) throw new Error(droyText("手机设置数据不完整，请重试"));
        syncedTimeZone = state.timeZone;
      };
      const refresh = options => controller.refresh(options).then(async state => {
        if (!options?.section) { await initializeTheme(state); await syncTimeZone(state); }
        return state;
      }).catch(error => controller.report(error));
      const busy = snapshot => snapshot.pending !== null || !snapshot.state;
      const actionButton = (props, label) => h(Button, { variant: 'outline', size: 'md', className: 'droy-button', ...props }, label);
      const button = (label, action, args, snapshot, refreshAfter = true, extra = {}) => actionButton({
        'data-droy-action': action,
        disabled: busy(snapshot), onClick: () => submit(action, args, refreshAfter), ...extra
      }, label);
      function Feedback({ snapshot }) {
        useDroyLocale();
        return h(React.Fragment, null,
          snapshot.loading && !snapshot.state ? h('div', { className: 'droy-state', role: 'status' }, droyText("正在读取手机设置…")) : null,
          snapshot.error ? h('div', { className: 'droy-error', role: 'alert' }, droyText(snapshot.error),
            h('div', { className: 'droy-actions' }, actionButton({ 'data-droy-action': 'refresh', onClick: () => refresh() }, droyText("重试")))) : null);
      }
      function AssistantName() {
        useDroyLocale();
        const snapshot = useState(['assistantName'], ['setPreference:assistantName']);
        const saved = snapshot.state?.assistantName || 'Droy';
        const [draft, setDraft] = React.useState(saved);
        React.useEffect(() => setDraft(saved), [saved]);
        const valid = draft.trim().length > 0 && draft.trim().length <= 40;
        return h('div', { className: 'droy-row', 'data-droy-general': 'assistantName' }, h('div', { className: 'droy-row-copy' },
          h('label', { className: 'droy-row-title', htmlFor: 'droy-assistant-name' }, droyText("页面显示名称")),
          h('p', { className: 'droy-description' }, droyText("用于页面标题、侧栏、欢迎语和输入提示。桌面 App 名称在安装包中配置。")),
          h('div', { className: 'droy-name-editor' }, h(Input, {
            id: 'droy-assistant-name', className: 'droy-input droy-assistant-name-input', 'aria-label': droyText("页面显示名称"),
            value: draft, maxLength: 40, disabled: busy(snapshot), onChange: event => setDraft(event.target.value)
          }), actionButton({ className: 'droy-button droy-assistant-name-save',
            disabled: busy(snapshot) || !valid || draft.trim() === saved,
            onClick: () => submit('setPreference', { key: 'assistantName', value: draft.trim() }) }, droyText("保存名称"))),
          h(Feedback, { snapshot })));
      }
      function BrandImage({ src, alt, size = 80, backgrounds, onPalette }) {
        useDroyLocale();
        const [loaded, setLoaded] = React.useState(null);
        const automatic = loaded?.src === src ? loaded.palette : logoPaletteCache.get(src) || LOGO_BACKGROUNDS;
        const palette = backgrounds || automatic;
        return h('img', { className: 'droy-brand-mark', 'data-droy-brand-mark': true,
          src, alt, width: size, height: size,
          style: { '--droy-logo-light': palette.light, '--droy-logo-dark': palette.dark },
          onLoad: event => {
            const image = event.currentTarget;
            let palette = logoPaletteCache.get(src);
            if (!palette) {
              const canvas = doc.createElement('canvas'); canvas.width = 96; canvas.height = 96;
              const context = canvas.getContext('2d', { willReadFrequently: true });
              context.drawImage(image, 0, 0, 96, 96);
              palette = logoBackgroundsFromPixels(context.getImageData(0, 0, 96, 96).data, 96, 96);
            }
            if (logoPaletteCache.size >= 64) logoPaletteCache.delete(logoPaletteCache.keys().next().value);
            logoPaletteCache.set(src, palette); setLoaded({ src, palette }); onPalette?.(palette);
          } });
      }
      function BrandPreviews({ src, name, backgrounds, onPalette }) {
        useDroyLocale();
        return h('div', { className: 'droy-brand-previews', 'aria-label': droyText("标志深浅模式预览") },
          ...['light', 'dark'].map(mode => h('figure', { key: mode, 'data-droy-brand-preview': mode },
            h(BrandImage, { src, alt: droyText("{0}的{1}模式标志", [name, mode === 'light' ? droyText("浅色") : droyText("深色")]), size: 72, backgrounds, onPalette }),
            h('figcaption', null, mode === 'light' ? droyText("浅色模式") : droyText("深色模式")))));
      }
      function BrandMark({ size = 80 }) {
        useDroyLocale();
        const snapshot = useState();
        const brand = brandingSnapshot(snapshot.state);
        return h(BrandImage, { src: brand.logoUrl, alt: droyText("{0}标志", [brand.name]), size, backgrounds: brand.logoBackgrounds });
      }
      function BrandName() {
        useDroyLocale();
        const snapshot = useState();
        const brand = brandingSnapshot(snapshot.state);
        return h('span', { className: 'droy-brand-identity', 'data-droy-brand-identity': true },
          h('span', { className: 'droy-brand-name', 'data-droy-brand-name': true }, brand.name));
      }
      function BrandWelcome() {
        useDroyLocale();
        const snapshot = useState();
        return h('span', { className: 'droy-brand-welcome', 'data-droy-brand-welcome': true }, brandingSnapshot(snapshot.state).welcome);
      }
      function BrandBadge() {
        useDroyLocale();
        const snapshot = useState();
        const badge = brandingSnapshot(snapshot.state).heroBadge;
        return badge ? h('span', { className: 'droy-brand-badge', 'data-droy-brand-badge': true }, badge) : null;
      }
      function BrandField({ entry, snapshot }) {
        useDroyLocale();
        const [key, label, property, fallback, maxLength] = entry;
        const configured = snapshot.state?.branding?.[property];
        const saved = localizedBrandTemplate(configured, DROY_UI_EN_SOURCES.get(fallback) ?? fallback);
        const [value, setValue] = React.useState(saved);
        const previousSaved = React.useRef(saved);
        React.useEffect(() => {
          const previous = previousSaved.current;
          setValue(current => current === previous ? saved : current);
          previousSaved.current = saved;
        }, [saved]);
        return h('div', { className: 'droy-brand-field' },
          h('label', { className: 'droy-row-title', htmlFor: `droy-${key}` }, label),
          h('div', { className: 'droy-name-editor' },
            h(Input, { id: `droy-${key}`, className: 'droy-input', value, maxLength, disabled: busy(snapshot), onChange: event => setValue(event.target.value) }),
            actionButton({ 'data-droy-brand-save': key, disabled: busy(snapshot) || key !== 'brandHeroBadge' && !value.trim() || value.trim() === saved,
              onClick: () => submit('setPreference', { key, value: value.trim() }) }, droyText("保存"))));
      }
      function BrandingSettings() {
        useDroyLocale();
        const snapshot = useState(['branding', 'assistantName', 'petId'], ['setPreference', 'setBrandLogoBackgrounds']);
        const fields = [
          ['brandHeroBadge', droyText("欢迎页状态标签（留空隐藏）"), 'heroBadge', '', 40],
          ['brandWelcomeTemplate', droyText("欢迎语"), 'welcomeTemplate', droyText("与{name}开始对话"), 240],
          ['brandComposerTemplate', droyText("输入提示"), 'composerTemplate', droyText("请描述你想让{name}执行的任务，或者你的对话"), 240],
        ];
        return h('div', { className: 'droy-row droy-brand-settings', 'data-droy-brand-settings': true },
          h('details', null, h('summary', { className: 'droy-row-title' }, droyText("界面品牌与文案")),
            h('div', { className: 'droy-brand-settings-body' },
              h('p', { className: 'droy-description' }, droyText("产品标志与宠物形象相互独立。{name} 会自动替换为页面显示名称。")),
              h(BrandBackgroundSettings, { key: brandingSnapshot(snapshot.state).logoPetId, snapshot }),
              ...fields.map(entry => h(BrandField, { key: entry[0], entry, snapshot })), h(Feedback, { snapshot }))));
      }

      function BrandBackgroundSettings({ snapshot }) {
        useDroyLocale();
        const brand = brandingSnapshot(snapshot.state);
        const saved = brand.logoBackgrounds;
        const [draft, setDraft] = React.useState(null);
        const [automatic, setAutomatic] = React.useState(null);
        React.useEffect(() => setDraft(null), [saved?.light, saved?.dark, brand.logoUrl]);
        const palette = draft || saved || (automatic?.src === brand.logoUrl ? automatic.palette : logoPaletteCache.get(brand.logoUrl)) || LOGO_BACKGROUNDS;
        const valid = ['light', 'dark'].every(mode => /^#[0-9a-f]{6}$/i.test(palette[mode]));
        return h('div', { className: 'droy-logo-background-settings' },
          h('span', { className: 'droy-row-title' }, droyText("标志底板")),
          h('p', { className: 'droy-description' }, droyText("两种模式使用相同形状和角色，仅切换底色。默认按角色轮廓自动配色，也可分别设置。设置仅用于当前形象的 App 内标志。")),
          h(BrandPreviews, { src: brand.logoUrl, name: brand.name, backgrounds: valid ? draft || saved : saved,
            onPalette: palette => setAutomatic({ src: brand.logoUrl, palette }) }),
          ...['light', 'dark'].map(mode => h('div', { className: 'droy-logo-color-field', key: mode },
            h('label', { className: 'droy-row-title', htmlFor: `droy-logo-background-${mode}` }, droyText("{0}模式底色", [mode === 'light' ? droyText("浅色") : droyText("深色")])),
            h(Input, { id: `droy-logo-background-${mode}`, className: 'droy-input', value: palette[mode], maxLength: 7,
              spellCheck: false, autoCapitalize: 'characters', disabled: busy(snapshot),
              onChange: event => setDraft({ ...palette, [mode]: event.target.value }) }))),
          h('p', { className: 'droy-description' }, droyText("使用 #RRGGBB 格式，例如 #F2EEE7。已有图片内部的背景会保留。")),
          h('div', { className: 'droy-actions' },
            actionButton({ 'data-droy-logo-background-save': true, disabled: busy(snapshot) || !draft || !valid,
              onClick: () => submit('setBrandLogoBackgrounds', { petId: brand.logoPetId, light: palette.light, dark: palette.dark }) }, droyText("保存底色")),
            actionButton({ 'data-droy-logo-background-reset': true, disabled: busy(snapshot) || !saved && !draft,
              onClick: () => saved ? submit('setBrandLogoBackgrounds', { petId: brand.logoPetId, reset: true }) : setDraft(null) }, droyText("恢复自动配色"))));
      }
      function EncryptedDns() {
        useDroyLocale();
        const snapshot = useState(['browserEncryptedDns'], ['setPreference:browserEncryptedDns']);
        return h('div', { className: 'droy-row', 'data-droy-general': 'browserEncryptedDns' },
          h('span', { className: 'droy-row-copy' }, h('span', { className: 'droy-row-title' }, droyText("浏览器加密 DNS")),
            h('span', { className: 'droy-description' }, droyText("内置浏览器使用 Cloudflare 解析域名。此项仅控制浏览器域名解析。"))),
          h(Switch, { label: droyText("浏览器加密 DNS"),
            checked: snapshot.state?.browserEncryptedDns === true, disabled: busy(snapshot),
            onChange: value => submit('setPreference', { key: 'browserEncryptedDns', value }) }));
      }
      function AutomationScreenDisplay() {
        useDroyLocale();
        const snapshot = useState(['automationScreenDisplay','automationScreenDisplayReady','automationScreenDisplayError'], ['setPreference:automationScreenDisplay','requestPermission']);
        const enabled = snapshot.state?.automationScreenDisplay !== false;
        const needsPermission = snapshot.state && enabled && snapshot.state.automationScreenDisplayReady !== true;
        return h('div', { className: 'droy-row droy-screen-display', 'data-droy-general': 'automationScreenDisplay' },
          h('div', { className: 'droy-row-copy' },
            h('span', { className: 'droy-row-title' }, droyText("自动化操作屏幕显示")),
            h('p', { className: 'droy-description' }, droyText("当Droy操作手机时，屏幕边缘会发光提醒")),
            enabled && snapshot.state?.automationScreenDisplayError
              ? h('div', { className: 'droy-error', role: 'alert' }, droyText(snapshot.state.automationScreenDisplayError)) : null,
            needsPermission ? h('div', { 'data-droy-screen-display-permission': true },
              h('p', { className: 'droy-description', role: 'status' }, droyText("允许在其他应用上显示后，即可显示屏幕光效。")),
              h('div', { className: 'droy-actions' },
                button(droyText("开启显示权限"), 'requestPermission', { name: 'petOverlay' }, snapshot, false,
                  { 'data-droy-permission': 'automationScreenDisplay' }))) : null,
            h(Feedback, { snapshot })),
          h(Switch, { label: droyText("自动化操作屏幕显示"), className: 'droy-touch-switch',
            checked: enabled, disabled: busy(snapshot),
            onChange: value => submit('setPreference', { key: 'automationScreenDisplay', value }) }));
      }
      function ReturnOnCompletion() {
        useDroyLocale();
        const snapshot = useState(['returnToDroyOnCompletion'], ['setPreference:returnToDroyOnCompletion']);
        return h('div', { className: 'droy-row', 'data-droy-general': 'returnToDroyOnCompletion' },
          h('div', { className: 'droy-row-copy' },
            h('span', { className: 'droy-row-title' }, droyText("任务完成后总是返回DroyAPP")),
            h('p', { className: 'droy-description' }, droyText("本轮任务结束后，自动打开对应对话查看结果。")),
            h(Feedback, { snapshot })),
          h(Switch, { label: droyText("任务完成后总是返回DroyAPP"),
            checked: snapshot.state?.returnToDroyOnCompletion === true, disabled: busy(snapshot),
            onChange: value => submit('setPreference', { key: 'returnToDroyOnCompletion', value }) }));
      }
      function NotificationAppTarget({ app, selected, allowedApps, snapshot }) {
        useDroyLocale();
        const [draft, setDraft] = React.useState(selected?.target || '');
        React.useEffect(() => setDraft(selected?.target || ''), [selected?.target]);
        const save = entries => submit('proactiveNotifications', { allowedApps: entries });
        const records = allowedApps.map(entry => ({ packageName: entry.packageName, target: entry.target || '' }));
        return h('div', { className: 'droy-notification-exception', 'data-droy-notify-app': app.packageName },
          h('div', { className: 'droy-notification-header' },
            h('span', { className: 'droy-row-title' }, app.label || app.packageName),
            h(Switch, { label: droyText("允许通过{0}主动通知", [app.label || app.packageName]), className: 'droy-touch-switch',
              checked: !!selected, disabled: busy(snapshot), onChange: value => save(value
                ? [...records, { packageName: app.packageName, target: '' }]
                : records.filter(entry => entry.packageName !== app.packageName)) })),
          selected ? h('div', { className: 'droy-notify-target' },
            h('label', { className: 'droy-description', htmlFor: `droy-notify-target-${app.packageName}` }, droyText("默认接收对象（可选）")),
            h(Input, { id: `droy-notify-target-${app.packageName}`, className: 'droy-input', value: draft,
              maxLength: 120, disabled: busy(snapshot), 'aria-label': droyText("{0}默认接收对象", [app.label || app.packageName]),
              placeholder: droyText("例如：文件传输助手；也可在任务中指定"), onChange: event => setDraft(event.target.value) }),
            actionButton({ disabled: busy(snapshot) || draft.trim() === (selected.target || ''),
              'data-droy-save-notify-target': app.packageName,
              onClick: () => save(records.map(entry => entry.packageName === app.packageName ? { ...entry, target: draft.trim() } : entry)) }, droyText("保存接收对象"))) : null);
      }
      function ProactiveNotificationSettings() {
        useDroyLocale();
        const snapshot = useState(['proactiveNotifications','notificationPermissionGranted'], ['proactiveNotifications','requestPermission']);
        const settings = snapshot.state?.proactiveNotifications || {};
        const [plugin, setPlugin] = React.useState(null);
        const [pending, setPending] = React.useState(false);
        const [error, setError] = React.useState('');
        const [search, setSearch] = React.useState('');
        const [opened, setOpened] = React.useState(false);
        const request = createNotificationPluginApi(target);
        watchSection('proactiveNotifications', opened && settings.appsEnabled === true);
        React.useEffect(() => {
          const panel = doc.querySelector('[data-droy-general=proactiveNotifications]')?.closest('[data-droy-settings-panel]');
          if (!panel) return;
          const reveal = () => {
            const field = panel.querySelector('[data-droy-general=proactiveNotifications] input:focus');
            if (field && target.innerWidth > target.innerHeight) field.scrollIntoView({ block: 'nearest', inline: 'nearest' });
          };
          const observer = new target.ResizeObserver(reveal);
          observer.observe(panel);
          panel.addEventListener('focusin', reveal);
          return () => { observer.disconnect(); panel.removeEventListener('focusin', reveal); };
        }, []);
        React.useEffect(() => {
          let current = true;
          request().then(value => { if (current) setPlugin(value); }).catch(failure => { if (current) setError(failure.message); });
          return () => { current = false; };
        }, []);
        const updatePlugin = async enabled => {
          setPending(true); setError('');
          try { setPlugin(await request(enabled)); await refresh({ force: true }); }
          catch (failure) { setError(failure.message); }
          finally { setPending(false); }
        };
        const allowedApps = settings.allowedApps || [];
        const apps = settings.apps || [];
        const needle = search.trim().toLocaleLowerCase();
        const visible = needle ? apps.filter(app => `${app.label} ${app.packageName}`.toLocaleLowerCase().includes(needle))
          : allowedApps.map(entry => apps.find(app => app.packageName === entry.packageName) || entry);
        const configure = values => submit('proactiveNotifications', values);
        return h('div', { className: 'droy-row droy-notification-settings', 'data-droy-general': 'proactiveNotifications' },
          h('div', { className: 'droy-notification-header' },
            h('span', { className: 'droy-row-title' }, droyText("Droy 主动通知")),
            h(Switch, { label: droyText("Droy 主动通知"), className: 'droy-touch-switch', checked: plugin?.enabled === true,
              disabled: pending || !plugin || busy(snapshot), onChange: updatePlugin })),
          h('p', { className: 'droy-description' }, droyText("Droy 按任务需要提醒，平时少打扰；简单事项只选一种方式。")),
          !plugin && !error ? h('p', { className: 'droy-description', role: 'status' }, droyText("正在读取通知设置…")) : null,
          error ? h('div', { className: 'droy-error', role: 'alert' }, droyText(error),
            h('div', { className: 'droy-actions' }, actionButton({ disabled: pending, onClick: () => updatePlugin(undefined) }, droyText("重新读取")))) : null,
          h(Feedback, { snapshot }),
          plugin?.enabled ? h('details', { className: 'droy-notification-details', open: opened,
            'data-droy-proactive-details': true, onToggle: event => { setOpened(event.currentTarget.open); if (event.currentTarget.open) refresh(); } },
            h('summary', null, droyText("通知方式")),
            h('div', { className: 'droy-notification-body' },
              h('div', { className: 'droy-notification-header' },
                h('div', { className: 'droy-row-copy' }, h('span', { className: 'droy-row-title' }, droyText("本机通知")),
                  h('p', { className: 'droy-description' }, droyText("选择系统通知或宠物提示，遵循已有声音与形象显示设置。"))),
                h(Switch, { label: droyText("本机通知"), className: 'droy-touch-switch', checked: settings.localEnabled !== false,
                  disabled: busy(snapshot), onChange: value => configure({ localEnabled: value }) })),
              snapshot.state?.notificationPermissionGranted === false && settings.localEnabled !== false
                ? h('div', { className: 'droy-actions' }, button(droyText("开启系统通知权限"), 'requestPermission', { name: 'notifications' }, snapshot, false)) : null,
              h('div', { className: 'droy-notification-header' },
                h('div', { className: 'droy-row-copy' }, h('span', { className: 'droy-row-title' }, droyText("通过其他 App 主动通知")),
                  h('p', { className: 'droy-description' }, droyText("允许 Droy 操作你选择的 App，向指定对象发送提醒。"))),
                h(Switch, { label: droyText("通过其他 App 主动通知"), className: 'droy-touch-switch', checked: settings.appsEnabled === true,
                  disabled: busy(snapshot), onChange: value => configure({ appsEnabled: value }) })),
              settings.appsEnabled ? h('div', { className: 'droy-notify-apps' },
                h(Input, { className: 'droy-input', value: search, 'aria-label': droyText("搜索通知应用"), placeholder: droyText("搜索应用以添加"),
                  onChange: event => setSearch(event.target.value) }),
                snapshot.catalogLoading && !apps.length ? h('p', { className: 'droy-description', role: 'status' }, droyText("正在读取通知设置…")) : null,
                !snapshot.catalogLoading && !needle && !allowedApps.length ? h('p', { className: 'droy-description' }, droyText("尚未选择应用。搜索后开启要使用的 App。")) : null,
                !snapshot.catalogLoading && needle && !visible.length ? h('p', { className: 'droy-description' }, droyText("没有找到匹配的应用。")) : null,
                ...visible.map(app => h(NotificationAppTarget, { key: app.packageName, app, allowedApps, snapshot,
                  selected: allowedApps.find(entry => entry.packageName === app.packageName) }))) : null)) : null);
      }
      function BackgroundRuntime() {
        useDroyLocale();
        const snapshot = useState(['backgroundRuntimeEnabled'], ['setPreference:backgroundRuntimeEnabled']);
        return h('div', { className: 'droy-row', 'data-droy-general': 'backgroundRuntimeEnabled' },
          h('div', { className: 'droy-row-copy' },
            h('span', { className: 'droy-row-title' }, droyText("持续后台运行")),
            h('p', { className: 'droy-description' }, droyText("关闭界面后继续运行。")),
            h(Feedback, { snapshot })),
          h(Switch, { label: droyText("持续后台运行"), className: 'droy-touch-switch', checked: snapshot.state?.backgroundRuntimeEnabled === true,
            disabled: busy(snapshot), onChange: value => submit('setPreference', { key: 'backgroundRuntimeEnabled', value }) }));
      }
      function NotificationExceptions({ onClose }) {
        useDroyLocale();
        watchSection('notificationManagement');
        const snapshot = useState(['notificationManagement'], ['notificationManagement']);
        const management = snapshot.state?.notificationManagement || {};
        const apps = management.apps || [];
        const [search, setSearch] = React.useState('');
        const bodyRef = React.useRef(null);
        React.useEffect(() => {
          const body = bodyRef.current;
          const content = body.closest('.droy-notification-exceptions-content');
          const observer = new target.ResizeObserver(() => {
            const input = body.querySelector('input:focus');
            if (!input) return;
            const viewport = content.getBoundingClientRect(), focused = input.getBoundingClientRect();
            if (focused.bottom > viewport.bottom) content.scrollTop += focused.bottom - viewport.bottom;
            else if (focused.top < viewport.top) content.scrollTop += focused.top - viewport.top;
          });
          observer.observe(content);
          return () => observer.disconnect();
        }, []);
        const query = search.trim().toLowerCase();
        const matches = apps.filter(app => query
          ? `${app.label} ${app.packageName}`.toLowerCase().includes(query)
          : app.mode === 'allow' || app.mode === 'block');
        return h(Modal, { open: true, title: droyText("例外应用"), closeLabel: droyText("返回通知管理"), onClose,
          className: 'droy-notification-exceptions', contentClassName: 'droy-notification-exceptions-content' },
          h('div', { ref: bodyRef, className: 'droy-notification-exceptions-body', 'data-droy-notification-exceptions': true },
            h(Input, { className: 'droy-input', 'aria-label': droyText("搜索应用"), placeholder: droyText("搜索应用"), value: search,
              onChange: event => setSearch(event.target.value), maxLength: 100 }),
            h(Feedback, { snapshot }),
            snapshot.catalogLoading && !apps.length ? h('p', { className: 'droy-description', role: 'status' }, droyText("正在读取通知设置…")) : null,
            !snapshot.catalogLoading && matches.length === 0 ? h('p', { className: 'droy-notification-empty' }, query ? droyText("没有匹配的应用") : droyText("暂无例外应用")) : null,
            ...matches.map(app => h('div', { className: 'droy-notification-exception', key: app.packageName,
              'data-droy-notification-app': app.packageName },
              h('span', { className: 'droy-row-title' }, app.label),
              h('div', { className: 'droy-notification-modes', role: 'group', 'aria-label': droyText("{0}通知方式", [app.label]) },
                ...[['managed', droyText("智能管理")], ['allow', droyText("放行")], ['block', droyText("不提醒")]].map(([mode, label]) =>
                  button(label, 'notificationManagement', { operation: 'setAppMode', packageName: app.packageName, mode }, snapshot,
                    true, { 'aria-pressed': (app.mode || 'managed') === mode, 'aria-label': `${app.label}：${label}`,
                      'data-droy-notification-mode': mode })))))));
      }
      function NotificationSettings() {
        useDroyLocale();
        const snapshot = useState(['notificationManagement','notificationManagementEnabled','capabilities','notificationPermissionGranted'], ['notificationManagement','setPreference:notificationManagementEnabled','requestPermission']);
        const state = snapshot.state;
        const management = state?.notificationManagement || {};
        const enabled = state?.notificationManagementEnabled ?? management.enabled ?? true;
        const apps = management.apps || [], rules = management.rules || [], archive = management.archive || [];
        const exceptions = management.exceptions || apps.filter(app => app.mode === 'allow' || app.mode === 'block');
        const [exceptionsOpened, setExceptionsOpened] = React.useState(false);
        const [opened, setOpened] = React.useState(pendingNotificationManagement);
        const [archiveOpened, setArchiveOpened] = React.useState(pendingNotificationManagement);
        React.useEffect(() => {
          let frame;
          const reveal = () => {
            pendingNotificationManagement = false;
            setOpened(true); setArchiveOpened(true); setExceptionsOpened(false);
            frame = target.requestAnimationFrame(() => {
              const archive = doc.querySelector('[data-droy-notification-archive]');
              const options = archive?.closest('[data-droy-settings-options]');
              if (options) options.scrollTop += archive.getBoundingClientRect().top - options.getBoundingClientRect().top;
            });
          };
          if (pendingNotificationManagement) reveal();
          target.addEventListener('droy-notification-management-open', reveal);
          return () => {
            target.removeEventListener('droy-notification-management-open', reveal);
            if (frame !== undefined) target.cancelAnimationFrame(frame);
          };
        }, []);
        const access = state?.capabilities?.systemPermissions?.notifications;
        const accessStatus = management.access;
        const actionError = accessStatus?.actionRequired ? accessStatus.message
          : management.error || (management.autoSilence?.actionRequired ? management.autoSilence.message : '');
        return h('div', { className: 'droy-row droy-notification-settings', 'data-droy-general': 'notificationManagementEnabled' },
          h('div', { className: 'droy-notification-header' },
            h('span', { className: 'droy-row-title' }, droyText("通知管理")),
            h(Switch, { label: droyText("通知管理"), className: 'droy-touch-switch', checked: enabled, disabled: busy(snapshot),
              onChange: value => submit('setPreference', { key: 'notificationManagementEnabled', value }) })),
          h(Feedback, { snapshot }),
          actionError ? h('div', { className: 'droy-error', role: 'alert', 'data-droy-notification-error': true }, droyText(actionError)) : null,
          enabled && accessStatus?.state === 'connecting' ? h('p', { className: 'droy-description', role: 'status',
            'data-droy-notification-preparing': true }, droyText(accessStatus.message)) : null,
          enabled && (access?.permissionGranted === false || accessStatus?.action === 'notificationAccess') ? h('div', { className: 'droy-actions' },
            button(droyText(accessStatus?.state === 'listener_unavailable' ? "检查通知读取权限" : "开启通知访问"), 'requestPermission', { name: 'notificationAccess' }, snapshot, false)) : null,
          enabled && (state?.notificationPermissionGranted === false || accessStatus?.action === 'notifications') ? h('div', { className: 'droy-actions' },
            button(droyText("开启提醒权限"), 'requestPermission', { name: 'notifications' }, snapshot, false)) : null,
          h('details', { className: 'droy-notification-details', 'data-droy-notification-details': true,
            open: opened, onToggle: event => { setOpened(event.currentTarget.open); if (event.currentTarget.open) refresh(); } },
            h('summary', null, droyText("通知设置")),
            h('div', { className: 'droy-notification-body' },
              h('div', { className: 'droy-notification-header' },
                h('div', { className: 'droy-row-copy' }, h('span', { className: 'droy-row-title' }, droyText("智能分析")),
                  h('p', { className: 'droy-description' }, droyText("使用当前模型识别广告并清理，保留有用的原通知；自定义规则优先。"))),
                h(Switch, { label: droyText("智能分析"), className: 'droy-touch-switch', checked: management.cloudAnalysisEnabled !== false, disabled: busy(snapshot),
                  onChange: value => submit('notificationManagement', { operation: 'configure', cloudAnalysisEnabled: value }) })),
              h('div', { className: 'droy-row-copy' },
                h('span', { className: 'droy-row-title', id: 'droy-notification-reminder-label' }, droyText("重要通知提醒")),
                h('p', { className: 'droy-description' }, droyText("重要通知保留超过所选时长后提醒一次。亮屏解锁时优先用宠物气泡，否则发送系统通知。")),
                h('div', { className: 'droy-notification-modes', role: 'group', 'aria-labelledby': 'droy-notification-reminder-label' },
                  ...[5, 10].map(minutes => actionButton({ key: minutes, className: 'droy-button',
                    'data-droy-notification-reminder': minutes, 'aria-pressed': (management.reminderMinutes || 5) === minutes,
                    disabled: busy(snapshot), onClick: () => submit('notificationManagement', { operation: 'configure', reminderMinutes: minutes }) },
                  droyText("{0} 分钟", [minutes]))))),
              actionButton({ className: 'droy-button droy-notification-exception-link',
                'data-droy-action': 'openNotificationExceptions', disabled: busy(snapshot),
                onClick: () => { setExceptionsOpened(true); } },
                h('span', null, droyText("例外应用")), h('span', { className: 'droy-status' }, droyText("{0} 个 ›", [exceptions.length]))),
              enabled ? h('p', { className: 'droy-description', 'data-droy-cleared-ads': true },
                droyText("最近24小时已清理 {0} 条广告", [management.clearedAdsCount || 0])) : null,
              h('h3', { className: 'droy-notification-group-title' }, droyText("通知规则")),
              h('p', { className: 'droy-description' }, droyText("在对话中告诉 Droy 如何处理通知。")),
              rules.length === 0 ? h('p', { className: 'droy-notification-empty' }, droyText("暂无规则")) : null,
              ...rules.map(rule => h('div', { className: 'droy-notification-rule', key: rule.id },
                h('span', { className: 'droy-row-title' }, rule.instruction),
                h('div', { className: 'droy-actions' }, button(droyText("删除规则"), 'notificationManagement', { operation: 'deleteRule', id: rule.id }, snapshot,
                  true, { 'aria-label': droyText("删除规则：{0}", [rule.instruction]), 'data-droy-notification-rule-delete': rule.id })))),
              h('details', { className: 'droy-notification-details', 'data-droy-notification-archive': true,
                open: archiveOpened, onToggle: event => setArchiveOpened(event.currentTarget.open) },
                h('summary', null, droyText("通知收纳{0}", [archive.length ? ` · ${archive.length}` : ''])),
                h('div', { className: 'droy-notification-list' },
                  archive.length === 0 ? h('p', { className: 'droy-notification-empty' }, droyText("暂无收纳通知")) : null,
                  ...archive.map((entry, index) => h('div', { className: 'droy-notification-entry', key: entry.id || entry.key || index },
                    h('span', { className: 'droy-status' }, entry.appName || apps.find(app => app.packageName === entry.packageName)?.label || entry.packageName),
                    h('span', { className: 'droy-row-title' }, entry.title),
                    entry.text ? h('p', { className: 'droy-description' }, entry.text) : null)))))),
          exceptionsOpened ? h(NotificationExceptions, { onClose: () => setExceptionsOpened(false) }) : null);
      }
      function PetSettings() {
        useDroyLocale();
        const snapshot = useState(['petEnabled','petName','petExternalDisplay','petInternalDisplay','petId','petLoadError','petPermissionGranted','branding'], ['setPreference','selectPet','previewPet','requestPermission']);
        const saved = snapshot.state?.petName || 'Droy';
        const granted = snapshot.state?.petPermissionGranted === true;
        const [draft, setDraft] = React.useState(saved);
        React.useEffect(() => setDraft(saved), [saved]);
        const valid = draft.trim().length > 0 && draft.trim().length <= 40;
        const display = (key, label, choices, fallback) => h('div', { className: 'droy-row-copy' },
          h('span', { className: 'droy-row-title', id: `droy-${key}-label` }, label),
          h('div', { className: 'droy-pet-choices', role: 'group', 'aria-labelledby': `droy-${key}-label` },
            ...choices.map(([value, text]) => actionButton({ key: value,
              'data-droy-pet-mode': key, 'data-value': value,
              'aria-pressed': (snapshot.state?.[key] || fallback) === value, disabled: busy(snapshot),
              onClick: () => submit('setPreference', { key, value }) }, text))));
        return h('div', { className: 'droy-row droy-pet-settings', 'data-droy-general': 'pet' },
          h('div', { className: 'droy-pet-header' }, h('div', { className: 'droy-row-copy' },
            h('span', { className: 'droy-row-title' }, droyText("宠物")),
            h('p', { className: 'droy-description' }, droyText("设置宠物名字、动态形象和显示方式。这些设置不会修改消息里的咔咔或麦麦。")))),
          display('petExternalDisplay', droyText("在其他应用和桌面显示"), [['always', droyText("始终显示")], ['adaptive', droyText("自适应")], ['off', droyText("关闭")]],
            snapshot.state?.petEnabled === false ? 'off' : 'always'),
          display('petInternalDisplay', droyText("在 Droy 内显示"), [['off', droyText("关闭")], ['adaptive', droyText("自适应")], ['always', droyText("始终显示")]], 'off'),
          h('p', { className: 'droy-description' }, droyText("自适应：有进行中的任务或待告知的信息时出现，其他时间完全隐藏。")),
          h('div', { className: 'droy-row-copy' },
            h('label', { className: 'droy-row-title', htmlFor: 'droy-pet-name' }, droyText("宠物名字")),
            h('div', { className: 'droy-name-editor droy-pet-name-editor' },
              h(Input, { id: 'droy-pet-name', className: 'droy-input', 'aria-label': droyText("宠物名字"),
                value: draft, maxLength: 40, disabled: busy(snapshot),
                onChange: event => setDraft(event.target.value) }),
              actionButton({ 'data-droy-action': 'savePetName',
                disabled: busy(snapshot) || !valid || draft.trim() === saved,
                onClick: event => { event.currentTarget.focus(); return submit('setPreference', { key: 'petName', value: draft.trim() }); } }, droyText("保存姓名")))),
          snapshot.state ? h('div', { className: 'droy-status', 'data-droy-pet-permission': true, role: 'status' },
            granted ? droyText("已允许在其他应用上显示。") : droyText("在其他应用和桌面显示需要悬浮窗权限；Droy 内显示和预览无需此权限。")) : null,
          snapshot.state?.petLoadError ? h('div', { className: 'droy-error', role: 'alert' }, droyText(snapshot.state.petLoadError)) : null,
          h('div', { className: 'droy-actions' },
            !granted ? button(droyText("开启显示权限"), 'requestPermission', { name: 'petOverlay' }, snapshot, false,
              { 'data-droy-permission': 'petOverlay' }) : null,
            button(droyText("和宠物打个招呼"), 'previewPet', {}, snapshot, false)),
          h(Feedback, { snapshot }),
          h(PetStudio, { snapshot }));
      }
      function PetAnimationPreview({ pet }) {
        useDroyLocale();
        const canvas = React.useRef(null), player = React.useRef(null), press = React.useRef(null);
        const [side, setSide] = React.useState('right');
        const [error, setError] = React.useState('');
        const [loading, setLoading] = React.useState(true);
        React.useEffect(() => {
          const abort = new target.AbortController(); let alive = true, resizeObserver;
          const images = [];
          setLoading(true); setError('');
          (async () => {
            const response = await target.fetch(petAssetUrl(pet.id, 'pet.json'), { credentials: 'same-origin', cache: 'no-store', signal: abort.signal });
            if (!response.ok) throw new Error(droyText("形象动作暂时无法读取，请重试"));
            const manifest = await response.json();
            const loaded = {};
            await Promise.all(Object.values(manifest.atlases).map(atlas => new Promise((resolve, reject) => {
              const image = new target.Image(); images.push(image);
              image.onload = () => { loaded[atlas.file] = image; resolve(); };
              image.onerror = () => reject(new Error(droyText("形象图片无法读取，请重试")));
              image.src = petAssetUrl(pet.id, atlas.file);
            })));
            if (!alive) return;
            player.current = createPetPreview(canvas.current, manifest, loaded, target);
            player.current.setSide(side);
            if (target.ResizeObserver) { resizeObserver = new target.ResizeObserver(() => player.current?.resize()); resizeObserver.observe(canvas.current); }
            setLoading(false);
          })().catch(failure => { if (alive) { setLoading(false); setError(failure.message); } });
          return () => {
            alive = false; abort.abort(); target.clearTimeout(press.current?.timer);
            for (const image of images) { image.onload = null; image.onerror = null; }
            resizeObserver?.disconnect(); player.current?.dispose(); player.current = null;
          };
        }, [pet.id, pet.manifestUrl]);
        const play = kind => {
          try { player.current?.play(kind); } catch (failure) { setError(failure.message); }
        };
        const cancelPress = () => { target.clearTimeout(press.current?.timer); press.current = null; };
        return h('div', { className: 'droy-pet-preview', 'data-droy-pet-preview': pet.id },
          h('button', { type: 'button', className: 'droy-pet-stage', 'aria-label': droyText("宠物动态预览，点击回应，长按互动"), disabled: loading || !!error,
            onContextMenu: event => event.preventDefault(),
            onPointerDown: event => {
              if (event.button !== 0) return;
              cancelPress();
              const current = { x: event.clientX, y: event.clientY, long: false };
              current.timer = target.setTimeout(() => { if (press.current === current) { current.long = true; play('pet'); } }, 500);
              press.current = current;
            },
            onPointerMove: event => { const current = press.current; if (current && Math.hypot(event.clientX - current.x, event.clientY - current.y) > 12) cancelPress(); },
            onPointerLeave: cancelPress, onPointerCancel: cancelPress,
            onPointerUp: () => { const current = press.current; cancelPress(); if (current && !current.long) play('tap'); },
            onClick: event => { if (event.detail === 0) play('tap'); }
          }, h('canvas', { ref: canvas, 'aria-hidden': true })),
          loading ? h('p', { className: 'droy-description', role: 'status' }, droyText("正在加载动作预览…")) : null,
          error ? h('p', { className: 'droy-error', role: 'alert' }, droyText(error)) : null,
          h('div', { className: 'droy-pet-choices', role: 'group', 'aria-label': droyText("预览所在位置") },
            ...[['left', droyText("屏幕左侧")], ['right', droyText("屏幕右侧")]].map(([value, label]) => actionButton({ key: value,
              'aria-pressed': side === value, 'data-droy-pet-side': value, disabled: loading || !!error,
              onClick: () => { setSide(value); player.current?.setSide(value); } }, label))),
          h('div', { className: 'droy-actions' },
            ...[['enter', droyText("入场")], ['tap', droyText("点击")], ['pet', droyText("互动")], ['idle', droyText("待机")], ['working', droyText("工作")], ['exit', droyText("退场")]].map(([kind, label]) =>
              actionButton({ key: kind, disabled: loading || !!error, 'data-droy-preview-action': kind, onClick: () => play(kind) }, label))),
          h('p', { className: 'droy-description' }, droyText("连续试玩可查看不同动作。点击形象回应，长按体验互动。")));
      }

      function PetStudio({ snapshot }) {
        useDroyLocale();
        const [open, setOpen] = React.useState(false);
        return h('div', { className: 'droy-pet-studio' },
          actionButton({ 'data-droy-action': 'openPetStudio', 'aria-expanded': open,
            onClick: () => setOpen(value => !value) }, open ? droyText("收起宠物形象设置") : droyText("定制宠物形象")),
          open ? h(PetStudioBody, { snapshot }) : null);
      }

      function PetStudioBody({ snapshot }) {
        useDroyLocale();
        const [catalog, setCatalog] = React.useState(null), [error, setError] = React.useState('');
        const [pending, setPending] = React.useState(false), [editing, setEditing] = React.useState(null);
        const [form, setForm] = React.useState({ name: '', description: '', personality: '' });
        const [previewId, setPreviewId] = React.useState(null);
        const alive = React.useRef(true), catalogVersion = React.useRef(0);
        const api = createPetApi(target);
        const reload = async signal => {
          const version = ++catalogVersion.current;
          const data = await api('', undefined, signal);
          if (alive.current && version === catalogVersion.current) setCatalog(data);
          return data;
        };
        React.useEffect(() => {
          alive.current = true; const abort = new target.AbortController();
          const load = () => reload(abort.signal).then(data => {
            if (!alive.current) return;
            setError('');
          }).catch(failure => { if (alive.current) setError(failure.message); });
          load();
          return () => { alive.current = false; abort.abort(); };
        }, []);
        React.useEffect(() => {
          if (!catalog?.pets?.some(pet => pet.status?.startsWith('generating_'))) return;
          const abort = new target.AbortController();
          const timer = target.setTimeout(() => reload(abort.signal).catch(failure => {
            if (alive.current && failure.name !== 'AbortError') setError(failure.message);
          }), 1800);
          return () => { abort.abort(); target.clearTimeout(timer); };
        }, [catalog]);
        const run = async (path, body) => {
          if (pending) return;
          setPending(true); setError('');
          try {
            await api(path, body);
            if (alive.current) { await reload(); setEditing(null); }
          } catch (failure) { if (alive.current) setError(failure.message); }
          finally { if (alive.current) setPending(false); }
        };
        const update = (key, value) => setForm(previous => ({ ...previous, [key]: value }));
        const disabled = pending || busy(snapshot);
        const generationBusy = pending || catalog?.pets?.some(pet => pet.status?.startsWith('generating_')) === true;
        const valid = form.name.trim().length > 0 && form.name.trim().length <= 40
          && form.description.trim().length > 0 && form.description.length <= 1000 && form.personality.length <= 1000;
        const edit = pet => {
          setEditing(pet.status === 'ready' ? null : pet.id);
          setForm({ name: pet.name || '', description: pet.description || '', personality: pet.personality || '' });
          const input = doc.getElementById('droy-pet-create-name');
          input?.scrollIntoView({ block: 'center', behavior: 'smooth' }); input?.focus({ preventScroll: true });
        };
        const submitForm = () => run(editing ? '/retry' : '/create', {
          ...(editing ? { id: editing } : {}), name: form.name.trim(), description: form.description.trim(), personality: form.personality.trim()
        });
        const generating = pet => pet.status?.startsWith('generating_') === true;
        return h('div', { className: 'droy-pet-studio-body', 'data-droy-pet-studio': true },
          h('div', { className: 'droy-pet-card' },
            h('span', { className: 'droy-row-title' }, droyText("默认黑猫")),
            h('p', { className: 'droy-description' }, droyText("黑猫宠物，点击回应、长按互动，可选择悬浮显示。")),
            (snapshot.state?.petId || 'default') === 'default' ? h('span', { className: 'droy-pet-current' }, droyText("正在使用")) :
              button(droyText("使用默认黑猫"), 'selectPet', { id: 'default' }, snapshot)),
          h('div', { className: 'droy-pet-form' },
            h('span', { className: 'droy-row-title' }, editing ? droyText("调整形象方案") : droyText("设计宠物形象")),
            h('p', { className: 'droy-description' }, droyText("为宠物更换动态形象、界面标志和互动风格，不会修改聊天伙伴的资料、记忆或聊天。完整素材包含参考图、品牌标志及六组动作。")),
            h('label', { className: 'droy-pet-field' }, h('span', { className: 'droy-row-title' }, droyText("形象方案名称")),
              h(Input, { id: 'droy-pet-create-name', className: 'droy-input', value: form.name, maxLength: 40,
                disabled: pending, onChange: event => update('name', event.target.value), placeholder: droyText("例如：眼镜小狐狸") })),
            h('label', { className: 'droy-pet-field' }, h('span', { className: 'droy-row-title' }, droyText("形象描述")),
              h('textarea', { id: 'droy-pet-description', value: form.description, maxLength: 1000, rows: 3,
                disabled: pending, onChange: event => update('description', event.target.value), placeholder: droyText("例如：戴圆眼镜的灰色小狐狸，柔和的水彩画风") })),
            h('label', { className: 'droy-pet-field' }, h('span', { className: 'droy-row-title' }, droyText("宠物性格与互动短句")),
              h('textarea', { id: 'droy-pet-personality', value: form.personality, maxLength: 1000, rows: 3,
                disabled: pending, onChange: event => update('personality', event.target.value), placeholder: droyText("例如：有点傲娇但很关心我，话少，开心时会偷偷得意") })),
            h('p', { className: 'droy-description' }, droyText("这里只影响宠物动作和互动短句，不会改变聊天伙伴的人设。")),
            catalog?.available === false ? h('p', { className: 'droy-description', role: 'status' }, droyText(catalog.configurationMessage)) : null,
            catalog?.provider ? h('p', { className: 'droy-description' }, droyText("宠物性格与外观描述会发送给当前文本模型以设计形象；外观描述、性格与参考图会发送至 {0} 图片服务。按各服务计费。", [catalog.provider])) : null,
            catalog?.textModel ? h('p', { className: 'droy-description' }, droyText("宠物设计：{0} / {1}", [catalog.textProvider || '', catalog.textModel])) : null,
            catalog?.model ? h('p', { className: 'droy-description' }, droyText("图片生成：{0}", [catalog.model])) : null,
            editing ? h('p', { className: 'droy-description' }, droyText("重新生成会替换这份方案的参考图和动作；当前宠物的形象保持不变。")) : null,
            h('div', { className: 'droy-actions' },
              actionButton({ 'data-droy-action': 'createPet', disabled: disabled || generationBusy || !catalog || !valid, onClick: submitForm },
                pending ? droyText("正在保存…") : generationBusy ? droyText("正在制作形象…") : catalog?.available ? editing ? droyText("重新生成形象") : droyText("生成宠物形象预览") : droyText("保存描述")),
              editing ? actionButton({ disabled: pending, onClick: () => { setEditing(null); setForm({ name: '', description: '', personality: '' }); } }, droyText("取消调整")) : null)),
          !catalog && !error ? h('p', { className: 'droy-description', role: 'status' }, droyText("正在读取形象方案和生成服务…")) : null,
          error ? h('div', { className: 'droy-error', role: 'alert' }, droyText(error),
            h('div', { className: 'droy-actions' }, actionButton({ disabled: pending, onClick: () => reload().then(() => setError('')).catch(failure => setError(failure.message)) }, droyText("重新读取")))) : null,
          ...(catalog?.pets || []).map(pet => h('div', { className: 'droy-pet-card', key: pet.id, 'data-droy-pet-candidate': pet.id },
            h('span', { className: 'droy-row-title' }, pet.name),
            h('p', { className: 'droy-description' }, pet.description),
            pet.referenceUrl ? h('img', { className: 'droy-pet-reference', src: petAssetUrl(pet.id, 'reference.png') + `&v=${pet.updatedAt || 0}`, alt: droyText("{0}的候选形象", [pet.name]) }) : null,
            pet.logoUrl ? h(BrandPreviews, { src: pet.logoUrl + `&v=${pet.updatedAt || 0}`, name: pet.name,
              backgrounds: snapshot.state?.branding?.logoBackgroundsByPet?.[pet.id] || null }) : null,
            pet.referenceUrl && pet.referenceCurrent === false ? h('p', { className: 'droy-description' }, droyText("这是上次生成的形象，当前描述尚未生成成功。")) : null,
            pet.persona?.description ? h('p', { className: 'droy-description' }, pet.persona.description) : null,
            pet.personaGenerated === true && pet.persona?.voice ? h('ul', { className: 'droy-pet-voice', 'aria-label': droyText("宠物互动短句预览") },
              ...[...(pet.persona.voice.working || []).slice(0, 2), ...(pet.persona.voice.idle || []).slice(0, 1)].map((line, index) =>
                h('li', { key: index }, line.replaceAll('{name}', pet.name)))) : null,
            h('p', { className: 'droy-description', role: 'status' }, droyText(pet.stage) || ({ draft: droyText("描述已保存"), ready: droyText("动作已生成，可试玩并使用"), reference_ready: droyText("形象已生成，可继续制作动作") }[pet.status] || droyText("正在准备"))),
            pet.error ? h('p', { className: 'droy-error', role: 'alert' }, droyText(pet.error)) : null,
            pet.status === 'ready' && previewId === pet.id ? h(PetAnimationPreview, { pet }) : null,
            h('div', { className: 'droy-actions' },
              pet.status === 'ready' ? actionButton({ disabled, 'data-droy-action': 'previewPetCandidate', onClick: () => setPreviewId(previewId === pet.id ? null : pet.id) }, previewId === pet.id ? droyText("收起试玩") : droyText("试玩动作")) : null,
              pet.status === 'ready' && snapshot.state?.petId !== pet.id ? button(droyText("应用宠物形象"), 'selectPet', { id: pet.id }, snapshot, true, { disabled }) : null,
              pet.status === 'ready' && snapshot.state?.petId === pet.id ? h('span', { className: 'droy-pet-current' }, droyText("正在使用")) : null,
              !generating(pet) ? actionButton({ disabled, onClick: () => edit(pet) }, pet.status === 'ready' ? droyText("复制描述新建") : droyText("调整描述")) : null,
              !generating(pet) && pet.status !== 'ready' && catalog.available ? actionButton({ disabled: disabled || generationBusy, onClick: () => run(pet.referenceUrl && pet.referenceCurrent !== false ? '/generate' : '/retry', { id: pet.id }) },
                pet.referenceUrl && pet.referenceCurrent !== false ? pet.status === 'error' ? droyText("继续生成缺失动作") : droyText("生成完整动作") : droyText("生成形象")) : null))));
      }

      function Diagnostics() {
        useDroyLocale();
        const snapshot = useState(['runtimeReady'], ['openDiagnostics']);
        return h('div', { className: 'droy-row' }, h('div', { className: 'droy-row-copy' },
          h('span', { className: 'droy-row-title' }, 'Droy'),
          h('span', { className: 'droy-description' }, droyText("版本、运行诊断、数据管理与停止任务和引擎。"))),
          button(droyText("关于与诊断"), 'openDiagnostics', {}, snapshot, false));
      }
      function AudioSettings() {
        useDroyLocale();
        const snapshot = useState(['audioCapture','audioTranscription','toolsPaused','capabilities','groups','runtimeReady'], ['audioCapture','audioTranscription','audioAuthorize','audioPlay','audioExport','audioImport']);
        const state = snapshot.state;
        const [expanded, setExpanded] = React.useState(false);
        const [source, setSource] = React.useState('microphone');
        const [packageName, setPackageName] = React.useState('');
        const [seconds, setSeconds] = React.useState('0');
        const [saveFile, setSaveFile] = React.useState(true);
        const [pageCaptureId, setPageCaptureId] = React.useState(null);
        React.useEffect(() => {
          if (!expanded) return;
          let stopped = false, timer;
          const stop = controller.observeSection('audio');
          const poll = async () => {
            if (stopped) return;
            const panel = doc.querySelector('[data-droy-audio-details]');
            if (doc.visibilityState !== 'hidden' && panel?.getClientRects().length)
              await controller.refreshSection('audio').catch(error => controller.report(error));
            if (!stopped) timer = target.setTimeout(poll, 1000);
          };
          poll();
          return () => { stopped = true; target.clearTimeout(timer); stop(); };
        }, [expanded]);
        const capture = state?.audioCapture || {};
        const active = capture.active;
        const currentCapture = active || (capture.recent || []).find(item => item.captureId === pageCaptureId);
        const recognizer = state?.audioTranscription || {};
        const job = recognizer.job;
        const paused = state?.toolsPaused ?? state?.capabilities?.product?.stopped ?? true;
        const enabled = key => state?.groups?.find(group => group.preference === key)?.enabled !== false;
        const canTranscribe = !paused && enabled('allowAudioTranscription') && recognizer.ready === true;
        const duration = Number(seconds);
        const valid = /^\d+$/.test(seconds) && Number.isSafeInteger(duration) && duration >= 0 && duration <= 86400
          && (source !== 'app' || packageName.trim().length > 0);
        const statusText = value => ({awaiting_user:droyText("等待系统授权"),starting:droyText("正在启动"),recording:droyText("正在采集"),finalizing:droyText("正在生成文件"),
          completed:droyText("已结束"),interrupted:droyText("已中断"),failed:droyText("失败"),cancelled:droyText("已取消"),queued:droyText("等待转写"),cancelling:droyText("正在停止"),running:droyText("正在转写"),transcribing:droyText("正在转写"),
          preparing:droyText("正在初始化"),not_prepared:droyText("等待初始化"),ready:droyText("已就绪")})[value] || value || droyText("状态暂不可用");
        const captureCard = (item, isActive) => {
          const id = item.captureId;
          return h('div', {className:'droy-audio-card',key:id,'data-droy-audio-capture':id,'data-droy-audio-current-result':!isActive || undefined},
            h('strong',null,item.source === 'microphone' ? droyText("麦克风") : item.scope === 'system' ? droyText("系统可采集音频") : droyText("应用音频 · {0}", [item.packageName || ''])),
            h('span',{className:'droy-status'},droyText("{0} · {1} 秒", [statusText(item.state), Math.floor((item.durationMs || 0) / 1000)])),
            item.message ? h('p',{className:'droy-description'},droyText(item.message)) : null,
            item.signal === 'system_silenced' ? h('p',{className:'droy-error'},droyText("麦克风被系统静音，当前未收到有效声音。")) : null,
            h('div',{className:'droy-actions'},
              item.state === 'awaiting_user' ? button(droyText("完成系统授权"),'audioAuthorize',{captureId:id},snapshot,false) : null,
              isActive ? button(droyText("停止采集"),'audioCapture',{operation:'stop',captureId:id},snapshot,true,{'data-droy-audio-stop':true}) : null,
              isActive && item.state === 'recording' && (!job || !['queued','running','transcribing','starting','cancelling'].includes(job.state))
                ? button(droyText("实时转写"),'audioTranscription',{operation:'transcribe',captureId:id},snapshot,true,{disabled:busy(snapshot)||!canTranscribe}) : null,
              item.fileReady ? button(droyText("播放"),'audioPlay',{captureId:id},snapshot,false) : null,
              item.fileReady ? button(droyText("导出录音"),'audioExport',{captureId:id},snapshot,false) : null,
              item.fileReady ? button(droyText("转成文字"),'audioTranscription',{operation:'transcribe',captureId:id},snapshot,true,
                {disabled:busy(snapshot)||!canTranscribe}) : null,
              !isActive ? button(droyText("删除录音"),'audioCapture',{operation:'delete',captureId:id},snapshot,true,
                {'data-droy-audio-delete':true,onClick:()=>{ if (target.confirm(droyText("仅删除录音库中的文件，聊天附件与转写保留。确定删除？"))) submit('audioCapture',{operation:'delete',captureId:id}); }}) : null));
        };
        return h('div',{className:'droy-row droy-audio-settings','data-droy-general':'audio'},
          h('div',{className:'droy-row-copy'},h('span',{className:'droy-row-title'},droyText("音频采集与转写")),
            h('span',{className:'droy-description'},droyText("录下麦克风或应用声音，在手机上转成文字。"))),
          h('details',{open:expanded,onToggle:event=>setExpanded(event.currentTarget.open),'data-droy-audio-details':true},
            h('summary',null,droyText("录音、转写与识别器")),
            h('div',{className:'droy-audio-body'},
              h(Feedback,{snapshot}),
              paused ? h('p',{className:'droy-description'},droyText("Android Use 已暂停；恢复后可开始采集和转写。已进行的采集仍可停止。")) : null,
              h('label',null,droyText("声音来源"),h('select',{'aria-label':droyText("声音来源"),value:source,onChange:event=>setSource(event.target.value)},
                h('option',{value:'microphone'},droyText("麦克风")),h('option',{value:'app'},droyText("指定应用")),h('option',{value:'system'},droyText("系统可采集音频")))),
              source === 'app' ? h('label',null,droyText("目标应用包名"),h('input',{'aria-label':droyText("目标应用包名"),value:packageName,
                placeholder:droyText("例如 com.example.player"),autoCapitalize:'none',spellCheck:false,onChange:event=>setPackageName(event.target.value)})) : null,
              source !== 'microphone' ? h('p',{className:'droy-description'},droyText("仅采集 Android 和目标应用允许捕获的播放音频。")) : null,
              h('label',null,droyText("采集时长（秒，0 表示手动停止）"),h('input',{'aria-label':droyText("采集时长"),inputMode:'numeric',value:seconds,
                onChange:event=>setSeconds(event.target.value)})),
              h('label',{className:'droy-audio-check'},h('input',{type:'checkbox',checked:saveFile,onChange:event=>setSaveFile(event.target.checked)}),droyText("保存录音文件")),
              h('div',{className:'droy-actions'},actionButton(
                {'data-droy-action':'audioCapture','data-droy-audio-start':true,disabled:busy(snapshot)||paused||!state?.runtimeReady||!!active||!valid||!enabled(source === 'microphone'?'allowAudioMicrophone':'allowAudioPlayback'),
                  onClick:async()=>{
                    const result = await submit('audioCapture',{operation:'start',source:source === 'microphone' ? 'microphone' : 'playback',
                      scope:source === 'system' ? 'system' : 'app',packageName:source === 'app' ? packageName.trim() : '',durationMs:duration * 1000,saveFile});
                    if (result?.captureId) setPageCaptureId(result.captureId);
                  }},droyText("开始采集"))),
              currentCapture ? captureCard(currentCapture,!!active) : h('p',{className:'droy-description'},droyText("当前没有正在进行的采集。")),
              h('h3',{className:'droy-row-title'},droyText("本地语音识别")),
              h('p',{className:'droy-description'},recognizer.ready ? droyText("识别器已就绪，录音在手机上处理。") : droyText("识别模型随应用安装，首次启动自动初始化，无需下载。")),
              h('span',{className:'droy-status','data-droy-audio-model-state':true},statusText(recognizer.state || recognizer.status || 'not_prepared')),
              recognizer.state === 'preparing' ? h('progress',{max:recognizer.totalBytes || 1,value:recognizer.downloadedBytes || 0,'aria-label':droyText("识别模型初始化进度")}) : null,
              recognizer.error ? h('p',{className:'droy-error'},droyText(recognizer.error)) : null,
              h('div',{className:'droy-actions'},button(droyText("选择音频转文字"),'audioImport',{},snapshot,false,{disabled:busy(snapshot)||!canTranscribe})),
              job ? h('div',{className:'droy-audio-card','data-droy-audio-transcript':job.transcriptId},
                h('strong',null,droyText("转写结果")),h('span',{className:'droy-status'},statusText(job.state || job.status)),
                job.text ? h('p',{className:'droy-audio-text'},job.text) : h('p',{className:'droy-description'},droyText("尚无已识别文字。")),
                job.error ? h('p',{className:'droy-error'},droyText(job.error)) : null,
                ['queued','starting','running','transcribing'].includes(job.state) ? h('div',{className:'droy-actions'},
                  button(droyText("停止转写"),'audioTranscription',{operation:'stop_transcription',transcriptId:job.transcriptId},snapshot)) : null) : null,
              h('p',{className:'droy-description','data-droy-audio-privacy-notice':true},droyText("语音对话和唤醒不保留录音；手动采集可选择保存录音文件。")))));
      }
      function PermissionPresetConfirmation({ mode, snapshot, onClose, onConfirm }) {
        const title = { all: droyText("一键开启所有权限"), dialogue: droyText("关闭所有权限"), assistant: droyText("仅开启最小化权限") }[mode];
        const explanation = {
          all: droyText("将开启全部手机能力，包括屏幕、联系人、位置、麦克风和 Root 等。读取的信息可能发送给当前模型。系统授权将连续请求，请逐项确认；无障碍等权限需在系统设置中开启，返回后自动继续。"),
          dialogue: droyText("将立即关闭全部手机能力并停止相关操作，对话仍可使用。Android 已授予的系统权限及选定文件授权会保留，但已关闭的手机能力不再使用这些授权；如需撤销，可到系统设置或下方文件列表操作。"),
          assistant: droyText("仅开启页面观察、手机操作和应用查找，并申请无障碍授权。其他手机能力全部关闭，包括 Root、联系人、位置和麦克风；已有 Android 系统授权会保留。")
        }[mode];
        return h(Modal, { open: true, title, closeLabel: droyText("取消"), onClose,
          className: 'droy-permission-confirm', contentClassName: 'droy-permission-confirm-content' },
          h('div', { 'data-droy-permission-confirmation': mode },
            h(Feedback, { snapshot }),
            h('p', { className: 'droy-description' }, explanation),
            mode === 'all' ? h('p', { className: 'droy-description' }, droyText("文件和目录需另行选择；屏幕或音频捕获按使用时授权。Shizuku、Root 和电脑专用授权取决于设备环境，不能自动授予。拒绝或跳过的项目可稍后补配。")) : null,
            h('div', { className: 'droy-actions' },
              actionButton({ 'data-droy-permission-cancel': true, disabled: busy(snapshot), onClick: onClose }, droyText("取消")),
              actionButton({ 'data-droy-permission-confirm': true, disabled: busy(snapshot), onClick: onConfirm }, droyText("确认并应用")))));
      }
      function Permissions() {
        useDroyLocale();
        const snapshot = useState();
        const state = snapshot.state;
        const [preset, setPreset] = React.useState(null);
        React.useEffect(() => { refresh({ maxAgeMs: 5000 }); }, []);
        if (!state) return h('div', { className: 'droy-permissions', 'data-droy-permissions': true }, h(Feedback, { snapshot }));
        const caps = state.capabilities || {};
        const queue = state.permissionSetup || {};
        const grouped = new Map();
        for (const group of state.groups) {
          const section = group.section || droyText("手机能力");
          if (!grouped.has(section)) grouped.set(section, []);
          grouped.get(section).push(group);
        }
        const rootStatus = {
          disabled: droyText("产品未授权"), last_command_succeeded: droyText("最近一次命令成功；后续动作仍会重新验证"),
          unavailable: droyText("系统 su 不可用"), denied_or_failed: droyText("最近一次命令被拒绝或失败"), not_verified: droyText("系统授权尚未验证")
        }[caps.rootAuthorization] || droyText("系统授权尚未验证");
        return h('div', { className: 'droy-permissions', 'data-droy-permissions': true },
          h('h2', { className: 'droy-section-title' }, droyText("权限快速配置")),
          h('p', { className: 'droy-description' }, droyText("修改立即生效并自动保存。读取的信息可能发送给当前模型；手机能力还需对应的系统授权。")),
          h(Feedback, { snapshot }),
          h('div', { className: 'droy-actions droy-permission-presets' },
            ...[['all', droyText("一键开启所有权限")], ['dialogue', droyText("关闭所有权限")], ['assistant', droyText("仅开启最小化权限")]].map(([mode, label]) =>
              actionButton({ key: mode, 'data-droy-permission-preset': mode, disabled: busy(snapshot) || queue.active && mode !== 'dialogue', onClick: () => setPreset(mode) }, label))),
          preset ? h(PermissionPresetConfirmation, { mode: preset, snapshot, onClose: () => setPreset(null),
            onConfirm: async () => {
              try { await controller.run('configurePermissions', { mode: preset }); setPreset(null); }
              catch { /* Keep the confirmation available after a failed save. */ }
            } }) : null,
          queue.active ? h('div', { className: 'droy-state', 'data-droy-permission-queue': true, role: 'status' },
            h('p', null, droyText("正在依次请求系统授权。完成当前弹窗或设置后，返回 Droy 将自动继续。")),
            button(droyText("停止后续授权"), 'cancelPermissionSetup', {}, snapshot)) : null,
          !queue.active && queue.total > 0 && queue.completed >= queue.total ? h('p', { className: 'droy-state', role: 'status' },
            droyText("授权流程已结束，实际授权状态见下方。未授权的项目可单独配置。")) : null,
          queue.error ? h('p', { className: 'droy-error', role: 'alert' }, droyText(queue.error)) : null,
          ...Array.from(grouped, ([section, groups]) => h('section', { key: section, className: 'droy-capability-section' },
            h('h3', { className: 'droy-section-title' }, section),
            ...groups.map(group => h('div', { className: 'droy-row', key: group.preference },
              h('span', { className: 'droy-row-copy' }, h('span', { className: 'droy-row-title' }, group.label),
                h('span', { className: 'droy-description' }, group.description)),
              h('label', { className: 'droy-switch-wrap', 'data-droy-preference': group.preference },
                h(Switch, { label: group.label, checked: group.enabled === true, disabled: busy(snapshot) || queue.active && !group.enabled,
                  onChange: value => submit('setPreference', { key: group.preference, value }) })))))),
          h('h3', { className: 'droy-section-title' }, droyText("系统授权")),
          ...permissionRows(state).map(([name, label, status, actionLabel]) => h('div', { className: 'droy-row droy-system-row', key: name },
            h('div', { className: 'droy-row-copy' }, h('span', { className: 'droy-row-title' }, label),
              h('span', { className: 'droy-status', 'data-droy-permission-status': name }, status)),
            actionLabel ? button(actionLabel, 'requestPermission', { name }, snapshot, true, { 'data-droy-permission': name, disabled: busy(snapshot) || queue.active }) : null)),
          h('p', { className: 'droy-description' }, `Root：${rootStatus}`),
          h('h3', { className: 'droy-section-title' }, droyText("选定文件与目录")),
          h('p', { className: 'droy-description' }, droyText("助理只访问你通过系统选择器授权的来源。撤销后将无法继续访问。")),
          h('div', { className: 'droy-actions' },
            button(droyText("选择只读文字文件"), 'requestPermission', { name: 'file' }, snapshot, true, { 'data-droy-permission': 'file', disabled: busy(snapshot) || queue.active }),
            button(droyText("选择可读取及导出的目录"), 'requestPermission', { name: 'directory' }, snapshot, true, { 'data-droy-permission': 'directory', disabled: busy(snapshot) || queue.active })),
          state.fileGrants.length === 0 ? h('p', { className: 'droy-state' }, droyText("尚未选择文件或目录。")) : null,
          ...state.fileGrants.map(grant => h('div', { className: 'droy-row droy-file-row', key: grant.grantId || grant.id },
            h('div', { className: 'droy-row-copy' }, h('span', { className: 'droy-row-title' }, grant.name),
              h('span', { className: 'droy-description' }, `${grant.kind === 'directory' ? droyText("目录") : droyText("文件")} · ${grant.writable ? droyText("可导出") : droyText("只读")}${grant.available === false ? droyText(" · 当前不可访问") : ''}`)),
            button(droyText("撤销授权"), 'revokeFile', { id: grant.grantId || grant.id }, snapshot, true, { 'aria-label': droyText("撤销 {0} 的授权", [grant.name]) }))),
          h('details', { className: 'droy-advanced' }, h('summary', null, droyText("高级")),
            h('div', { className: 'droy-actions' }, actionButton({ 'data-droy-action': 'openBrowser', disabled: busy(snapshot), onClick: () => {
              closeTopModal(doc);
              browserNavigation.openBrowser().catch(error => controller.report(error));
            } }, droyText("打开浏览器")),
              button(droyText("查看设置恢复记录"), 'openSettingRestores', {}, snapshot, false))));
      }

      const style = doc.createElement('style');
      style.id = 'droy-mobile-client-style';
      style.textContent = styles;
      installTokenUsageClient(ctx, React);
      doc.head.appendChild(style);
      for (const [name, component] of [
        ['sidebar.brand.mark', BrandMark], ['sidebar.brand.name', BrandName],
        ['conversation.hero.brand.mark', BrandMark], ['droy.brand.hero-title', BrandWelcome],
        ['droy.brand.hero-badge', BrandBadge],
      ]) ctx.slots.inject(name, () => ctx.slots.register({ name, id: `droy-${name}` }, component));
      ctx.slots.inject('settings.general.item', () => ctx.slots.register({ name: 'settings.general.item', id: 'droy-assistant-name', order: 80 }, AssistantName));
      ctx.slots.inject('settings.general.item', () => ctx.slots.register({ name: 'settings.general.item', id: 'droy-branding', order: 80.5 }, BrandingSettings));
      ctx.slots.inject('settings.general.item', () => ctx.slots.register({ name: 'settings.general.item', id: 'droy-browser-dns', order: 81 }, EncryptedDns));
      ctx.slots.inject('settings.general.item', () => ctx.slots.register({ name: 'settings.general.item', id: 'droy-pet', order: 82 }, PetSettings));
      ctx.slots.inject('settings.general.item', () => ctx.slots.register({ name: 'settings.general.item', id: 'droy-automation-screen-display', order: 83 }, AutomationScreenDisplay));
      ctx.slots.inject('settings.general.item', () => ctx.slots.register({ name: 'settings.general.item', id: 'droy-return-on-completion', order: 84 }, ReturnOnCompletion));
      ctx.slots.inject('settings.general.item', () => ctx.slots.register({ name: 'settings.general.item', id: 'droy-background-runtime', order: 85 }, BackgroundRuntime));
      ctx.slots.inject('settings.general.item', () => ctx.slots.register({ name: 'settings.general.item', id: 'droy-notifications', order: 86 }, NotificationSettings));
      ctx.slots.inject('settings.general.item', () => ctx.slots.register({ name: 'settings.general.item', id: 'droy-proactive-notifications', order: 87 }, ProactiveNotificationSettings));
      ctx.slots.inject('droy.voice.audio', () => ctx.slots.register({ name: 'droy.voice.audio', id: 'droy-audio' }, AudioSettings));
      ctx.slots.inject('settings.general.item', () => ctx.slots.register({ name: 'settings.general.item', id: 'droy-diagnostics', order: 100 }, Diagnostics));
      ctx.slots.inject('settings.section', () => ctx.slots.register({ name: 'settings.section', id: 'droy-permissions', order: 15, label: () => droyText("权限设置") }, Permissions));
      const setup = installSetupClient({ ctx, React, h, Input, target, doc, bridge, controller, useState,
        actionButton, Permissions, BrandMark, PetStudio });

      let syncedTheme = '';
      const syncTheme = () => new Promise((resolve, reject) => {
        if (disposed) { reject(new Error(droyText("页面已关闭"))); return; }
        const frame = target.requestAnimationFrame(() => {
          themeFrames.delete(frame);
          try {
            const snapshot = ctx.theme.getTheme();
            const args = { preference: snapshot.preference, colorScheme: snapshot.active.colorScheme,
              backgroundColor: readThemeBackground(target) };
            const key = JSON.stringify(args);
            if (key === syncedTheme) { resolve(); return; }
            bridge.request('themeSync', args).then(() => { syncedTheme = key; resolve(); }, reject);
          } catch (error) { reject(error); }
        });
        themeFrames.set(frame, reject);
      });
      let themeInitialized = false;
      let themeOperation = null;
      let initializingTheme = false;
      let resettingTheme = false;
      function initializeTheme(state) {
        if (themeOperation) return themeOperation;
        if (themeInitialized && state.themeReset !== 'system') return Promise.resolve();
        initializingTheme = true;
        themeOperation = (async () => {
          const desired = state.themeReset === 'system' ? 'system'
            : !themeInitialized && ['light', 'dark'].includes(state.legacyTheme) ? state.legacyTheme : null;
          if (desired) await setThemeDurably(ctx, target, desired);
          await syncTheme();
          themeInitialized = true;
        })().finally(() => { themeOperation = null; initializingTheme = false; });
        return themeOperation;
      }
      const disposeTheme = ctx.on('theme/change', () => {
        if (themeInitialized && !initializingTheme && !resettingTheme) syncTheme().catch(error => controller.report(error));
      });
      const initial = refresh();
      const oldMobile = target.DroyMobile;
      const browserNavigation = createBrowserNavigation(ctx, bridge, error => controller.report(error), target);
      const companionUi = createCompanionUi({React,ctx,target,nativeBridge:bridge,
        ...createCompanionInputAdapters({ctx,target,bridge,loadSpeech:()=>require('@deepseek-ai/dsh-experimental-client-ui-voice-input').createDroyVoiceInput(ctx)})});
      ctx.slots.inject('main',()=>ctx.slots.register({name:'main',key:'droy-companions'},companionUi.Panel));
      ctx.slots.inject('settings.general.item',()=>ctx.slots.register({name:'settings.general.item',id:'droy-default-mode',order:1},companionUi.DefaultModeSetting));
      function WorkModeSwitch() {
        const panel=React.useSyncExternalStore(ctx.layout.panelInfo.subscribe,ctx.layout.panelInfo.getSnapshot,ctx.layout.panelInfo.getSnapshot);
        const locale=React.useSyncExternalStore(callback=>ctx.locale.subscribe(callback),()=>ctx.locale.getSnapshot(),()=>ctx.locale.getSnapshot());
        if(panel.activePanelId!==null)return null;
        const copy=DROY_COMPANION_COPY[/^zh\b/i.test(locale.active||'')?'zh':'en'];
        const navigate=operation=>{
          doc.activeElement?.blur?.();
          bridge.request('hideKeyboard').catch(error=>controller.report(error));
          operation();
        };
        const button=(marker,label,icon,onClick,extra={})=>h('button',{type:'button',className:'droy-work-header-button',[marker]:true,
          'aria-label':label,title:label,onClick:()=>navigate(onClick),...extra},h(icon,{size:24,'aria-hidden':true}));
        return h('header',{className:'droy-work-header'},h('div',{className:'droy-work-mode-switch'},h(companionUi.WorkModeSwitch)),
          h('div',{className:'droy-work-header-actions'},
            button('data-droy-menu',copy.conversationsSpaces,IconNewChatOutlineRegular,()=>ctx.layout.toggleSidebar()),
            button('data-droy-resource-toggle',copy.resourcePanel,IconPanelLeftOutlineRegular,()=>ctx.sidebarRight.toggleExpanded(),{className:'droy-work-header-button droy-work-resource-button'}),
            button('data-droy-global-settings',copy.settings,IconSettingsOutlineMedium,()=>target.dispatchEvent(new target.CustomEvent('droy-mobile-open-settings',{detail:{section:'general'}})))));
      }
      ctx.slots.inject('shell.overlay',()=>ctx.slots.register({name:'shell.overlay',id:'droy-work-mode-switch',order:1},WorkModeSwitch));
      const updateCompanionPanel=()=>{
        const selected=ctx.layout.panelInfo.getSnapshot().activePanelId;
        doc.body.toggleAttribute('data-droy-im-active',selected==='droy-companions');
        doc.body.toggleAttribute('data-droy-work-active',selected===null);
      };
      const stopCompanionPanel=ctx.layout.panelInfo.subscribe(updateCompanionPanel);updateCompanionPanel();
      let companionWorkspaceKey='';
      const updateWorkspaceOrder=()=>{
        const snapshot=companionUi.state.getSnapshot();
        const key=JSON.stringify([snapshot.defaultWorkspaceId??null,...snapshot.companions.map(row=>row.workspaceId).filter(Boolean)]);
        if(key===companionWorkspaceKey)return;
        companionWorkspaceKey=key;
        target.dispatchEvent(new target.CustomEvent('droy-workspace-order-change'));
      };
      const stopWorkspaceOrder=companionUi.state.subscribeView(updateWorkspaceOrder);
      let initialInteraction=false;
      const markInitialInteraction=()=>{initialInteraction=true;};
      target.addEventListener('pointerdown',markInitialInteraction,{once:true});
      target.addEventListener('keydown',markInitialInteraction,{once:true});
      const companionWorkbench = createCompanionWorkbench({ctx,state:companionUi.state,reportError:error=>controller.report(error),canInitialize:()=>!disposed&&!initialInteraction});
      companionUi.initialize().then(async()=>{
        await companionWorkbench.initializeDefault();
        if(!disposed&&!initialInteraction) {
          if(companionUi.state.getSnapshot().settings.defaultMode==='messages')companionUi.open();
          else ctx.layout.selectPanel(null);
        }
      }).catch(error=>controller.report(error));
      const mobile = {
        ...browserNavigation,
        companionWorkspaceIds() { return companionUi.state.getSnapshot().companions.map(row=>row.workspaceId).filter(Boolean); },
        defaultWorkspaceId() { return companionUi.state.getSnapshot().defaultWorkspaceId ?? null; },
        async openCompanion(id) {
          if(!['kaka','maimai'].includes(id))throw new Error('COMPANION_NOT_FOUND');
          initialInteraction=true;await companionUi.initialize();
          closeTopModal(doc);
          if(doc.querySelector('[data-droy-drawer-open="true"]'))ctx.layout.toggleSidebar();
          companionUi.open(id);return {companionId:id};
        },
        mediaInput(args) { return bridge.request('mediaInput', args); },
        saveMemoryArchive(id) { return bridge.request('memoryArchiveSave', { archiveId: id }); },
        voiceRequest(action, args = {}) {
          return bridge.request(action, args, action === 'voiceAuthenticate' ? 120000 : 10000);
        },
        async revealPetTask(args) {
          try {
            const result = await browserNavigation.revealPetTask(args);
            touch.cancelGestures({ closeRows: true });
            closeTopModal(doc);
            if (doc.querySelector('[data-droy-drawer-open="true"]')) ctx.layout.toggleSidebar();
            return result;
          } catch (error) { controller.report(error); throw error; }
        },
        handleBack() {
          const topLayer=Array.from(doc.querySelectorAll(modalSelector)).at(-1);
          if(topLayer?.closest('[data-droy-im]'))return companionUi.back();
          if (doc.querySelector(modalSelector)) return createBackHandler(ctx, doc, closeTopModal, touch)();
          if (!doc.querySelector(modalSelector) && setup.handleBack()) return true;
          if(ctx.layout.panelInfo.getSnapshot().activePanelId==='droy-companions') return companionUi.back();
          return createBackHandler(ctx, doc, closeTopModal, touch)();
        },
        refresh,
        stopMicrophone: microphone.stop,
        openPermissions() { target.dispatchEvent(new target.CustomEvent('droy-mobile-open-settings', { detail: { section: 'droy-permissions' } })); },
        openNotificationManagement() {
          pendingNotificationManagement = true;
          target.dispatchEvent(new target.CustomEvent('droy-mobile-open-settings', { detail: { section: 'general' } }));
          target.dispatchEvent(new target.CustomEvent('droy-notification-management-open'));
        },
        async resetPersonalization() {
          await initial;
          resettingTheme = true;
          try {
            await setThemeDurably(ctx, target, 'system');
            await syncTheme();
            themeInitialized = true;
            await controller.refresh();
          } catch (error) {
            themeInitialized = false;
            controller.report(error);
          } finally { resettingTheme = false; }
        }
      };
      target.DroyMobile = mobile;
      target.dispatchEvent(new target.CustomEvent('droy-mobile-ready'));
      const stopPanelNavigation = observePanelNavigation(ctx, doc);
      const stopPetContext = observePetContext(ctx, doc, controller);
      const onSettingsOpen = createSettingsOpenHandler(ctx, doc);
      target.addEventListener('droy-mobile-settings-open', onSettingsOpen);
      const stopReadyObserver = observeUiReady(target, async () => {
        await initial;
        if (disposed) return;
        if (!themeInitialized) throw new Error(droyText("页面初始化未完成，请重新加载"));
        await syncTheme();
        if (!disposed) await bridge.request('uiReady');
      }, error => controller.report(error));
      const onFocus = () => refresh({ maxAgeMs: 100 });
      const onVisibility = () => { if (doc.visibilityState === 'visible') refresh({ maxAgeMs: 100 }); };
      target.addEventListener('focus', onFocus);
      doc.addEventListener('visibilitychange', onVisibility);
      ctx.effect(() => () => {
        disposed = true;
        companionWorkbench.dispose();stopWorkspaceOrder();companionUi.dispose();stopCompanionPanel();doc.body.removeAttribute('data-droy-im-active');doc.body.removeAttribute('data-droy-work-active');
        target.removeEventListener('pointerdown',markInitialInteraction);
        target.removeEventListener('keydown',markInitialInteraction);
        setup.dispose();
        browserNavigation.dispose();
        stopReadyObserver();
        stopPanelNavigation();
        stopPetContext();
        if (typeof disposeTheme === 'function') disposeTheme();
        for (const [frame, reject] of themeFrames) {
          target.cancelAnimationFrame(frame);
          reject(new Error(droyText("页面已关闭")));
        }
        themeFrames.clear();
        if (target.DroyMobile === mobile) target.DroyMobile = oldMobile;
        if (target.DroyTouch === touch) target.DroyTouch = previousTouch;
        touch.dispose();
        target.removeEventListener('focus', onFocus);
        target.removeEventListener('droy-mobile-settings-open', onSettingsOpen);
        doc.removeEventListener('visibilitychange', onVisibility);
        style.remove();
        microphone.dispose();
        target.removeEventListener('droy-native-locale-change', refreshLocaleState);
        stopLocaleState?.();
        stopLocaleCopy?.();
        stopBranding();
        controller.dispose();
        bridge.dispose();
      }, 'droy-mobile: native settings bridge');
    }
    return { apply, inject: ['locale', 'remote', 'slots', 'theme', 'layout', 'configForms', 'sidebarRight', 'sessions', 'workspaces', 'uiWorkspace', 'conversation'], createNativeBridge, createCompanionWorkbench, createBrowserNavigation, createController, brandingSnapshot, logoBackgroundsFromPixels, installBranding, createTouchController, createMicrophoneGuard, permissionRows, createBackHandler, createSettingsOpenHandler, observePanelNavigation, observePetContext, createNotificationPluginApi, createPetApi, petAssetUrl, petPreviewFrame, createPetPreview, setThemeDurably, readThemeBackground, observeUiReady };
  }
});
