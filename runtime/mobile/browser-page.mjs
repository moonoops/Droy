// Embedded in DSH's browser plugin by the version-checked Android patch.
export function createNativeBrowserPage(options, { createStore, document, browserPage, window: view = document.defaultView }) {
  const message = (zh, en) => /^zh(?:-|$)/i.test(document.documentElement?.lang ?? '') ? zh : en;
  const sessionId = options.nativeSessionId || options.tabId;
  if (typeof sessionId !== 'string' || !sessionId) throw new Error('Android browser session identity is missing');
  let host, disposed = false, nativeAttached = false, geometryKey = '', checkpointKey = '';
  let revision = -1, queue = Promise.resolve(), pollTimer, layoutFrame, resizeObserver, mutationObserver;
  let mountGeneration = 0, disposal;
  const store = createStore({ target: undefined, address: 'empty', loading: false,
    canGoBack: false, canGoForward: false, error: undefined, sandboxEnabled: undefined });

  function publish(update) {
    if (disposed) return;
    const current = store.getSnapshot(), next = { ...current, ...update };
    if (JSON.stringify(current) !== JSON.stringify(next)) store.set(next);
  }

  function applyFrame(result) {
    if (disposed) return;
    const state = result?.frame ?? result;
    if (!state || state.sessionId !== sessionId || typeof state.sessionOpen !== 'boolean')
      throw new Error(message('浏览器页面状态不完整，请重新加载', 'The browser page state is incomplete. Reload the page.'));
    // Requests are serialized; a restarted native process may legitimately reset its revision.
    revision = Number.isFinite(state.revision) ? state.revision : revision + 1;
    const target = state.sessionOpen && state.url && state.url !== 'about:blank'
      ? { url: state.url, title: state.title || undefined } : undefined;
    const error = typeof state.error === 'string' ? { code: 'android-browser', description: state.error }
      : state.error || undefined;
    publish({ target, address: target ? 'known' : 'empty', loading: state.loading === true,
      canGoBack: state.canGoBack === true, canGoForward: state.canGoForward === true, error });
    // Native history owns redirects and in-page navigation. Persist only an observed address for restore.
    const nextCheckpointKey = JSON.stringify(target);
    if (target && nextCheckpointKey !== checkpointKey) {
      checkpointKey = nextCheckpointKey;
      options.persist({ entries: [target], index: 0, request: { target, revision },
        navigation: { status: 'known', revision }, failure: undefined });
    }
    syncViewport();
  }

  function lifecycleRequest(operation) {
    // Hiding and closing must reach MainActivity before a slow DNS/navigation reply.
    // Their acknowledgements cannot update page state or reattach an old surface.
    try { return Promise.resolve(browserPage({ operation, tabId: sessionId, uiTabId: options.tabId })); }
    catch (error) { return Promise.reject(error); }
  }

  function reportFailure(error) {
    publish({ loading: false, error: { code: 'android-browser', description: error?.message || message('无法读取浏览器页面，请重试', 'Cannot read the browser page. Try again.') } });
  }

  function request(operation, args = {}) {
    if (operation === 'detach') return lifecycleRequest(operation).catch(reportFailure);
    const generation = mountGeneration;
    const task = queue.then(async () => {
      if (disposed) return;
      if (operation === 'attach') {
        if (generation !== mountGeneration || !nativeAttached || geometryKey !== JSON.stringify(args.viewport)) return;
        const currentViewport = viewport();
        if (!currentViewport || JSON.stringify(currentViewport) !== JSON.stringify(args.viewport)) { syncViewport(); return; }
      }
      applyFrame(await browserPage({ operation, tabId: sessionId, uiTabId: options.tabId, ...args }));
    });
    queue = task.catch(error => {
      if (operation === 'attach') { nativeAttached = false; geometryKey = ''; }
      reportFailure(error);
    });
    return queue;
  }

  function surfaceVisible() {
    if (!host || document.visibilityState === 'hidden' || host.isConnected === false) return false;
    if (host.getClientRects && host.getClientRects().length === 0) return false;
    if (host.closest?.('[hidden], [inert], [aria-hidden="true"]')) return false;
    if (view.getComputedStyle?.(host).visibility === 'hidden') return false;
    const overlays = document.querySelectorAll?.('[role="dialog"][aria-modal="true"], [role="menu"], [data-droy-drawer-open="true"]') || [];
    return !Array.from(overlays).some(node => !node.hasAttribute('data-droy-sidebar'));
  }

  function viewport() {
    if (!store.getSnapshot().target || !surfaceVisible()) return undefined;
    const rect = host.getBoundingClientRect();
    const viewportWidth = view.innerWidth, viewportHeight = view.innerHeight, pixelRatio = view.devicePixelRatio;
    if (![rect.left, rect.top, rect.width, rect.height, viewportWidth, viewportHeight, pixelRatio].every(Number.isFinite)
      || rect.width <= 0 || rect.height <= 0 || viewportWidth <= 0 || viewportHeight <= 0 || pixelRatio <= 0)
      return undefined;
    // CSS viewport rounding may differ by a fraction of a physical pixel.
    // Larger slide-transition offsets must settle; clipping would attach the page at the wrong coordinates.
    const epsilon = 1 / pixelRatio;
    if (rect.left < -epsilon || rect.top < -epsilon || rect.left + rect.width > viewportWidth + epsilon
      || rect.top + rect.height > viewportHeight + epsilon)
      return undefined;
    return { left: rect.left, top: rect.top, width: rect.width, height: rect.height, viewportWidth };
  }

  function syncViewport() {
    if (disposed) return;
    const rect = viewport();
    const nextKey = rect ? JSON.stringify(rect) : '';
    if (!rect) {
      geometryKey = '';
      if (nativeAttached) { nativeAttached = false; request('detach'); }
    } else if (!nativeAttached || geometryKey !== nextKey) {
      nativeAttached = true; geometryKey = nextKey;
      request('attach', { viewport: rect });
    }
  }

  function scheduleLayout() {
    if (disposed || !host || layoutFrame !== undefined) return;
    layoutFrame = view.requestAnimationFrame(() => { layoutFrame = undefined; syncViewport(); });
  }

  function schedulePoll(generation) {
    if (disposed || !host || generation !== mountGeneration) return;
    pollTimer = view.setTimeout(async () => {
      pollTimer = undefined;
      if (disposed || !host || generation !== mountGeneration) return;
      // Transform animations need not emit resize events; polling also observes their final position.
      syncViewport();
      if (surfaceVisible()) await request('state');
      schedulePoll(generation);
    }, 500);
  }

  function unmount() {
    ++mountGeneration;
    resizeObserver?.disconnect(); resizeObserver = undefined;
    mutationObserver?.disconnect(); mutationObserver = undefined;
    view.removeEventListener('resize', scheduleLayout);
    view.removeEventListener('scroll', scheduleLayout, true);
    view.visualViewport?.removeEventListener('resize', scheduleLayout);
    view.visualViewport?.removeEventListener('scroll', scheduleLayout);
    document.removeEventListener('visibilitychange', scheduleLayout);
    if (pollTimer !== undefined) view.clearTimeout(pollTimer);
    if (layoutFrame !== undefined) view.cancelAnimationFrame(layoutFrame);
    pollTimer = layoutFrame = undefined;
    host = undefined; geometryKey = '';
    if (nativeAttached) { nativeAttached = false; request('detach'); }
  }

  const frame = {
    getSnapshot: () => store.getSnapshot(),
    subscribe: listener => store.subscribe(listener),
    loadUrl(target) {
      if (disposed) return;
      publish({ target, address: 'requested', loading: true, error: undefined });
      request('navigate', { url: target.url });
    },
    goBack() { if (!disposed && store.getSnapshot().canGoBack) request('back'); },
    goForward() { if (!disposed && store.getSnapshot().canGoForward) request('forward'); },
    reload() { if (!disposed && store.getSnapshot().target) request('reload'); },
    dispose() {
      if (disposed) return disposal;
      unmount(); disposed = true;
      // Close immediately; queued page commands become inert and late replies are ignored.
      disposal = lifecycleRequest('close');
      return disposal;
    }
  };
  const presentation = {
    mount(viewportId) {
      if (disposed) return () => {};
      unmount();
      const next = document.getElementById(viewportId);
      if (!next) throw new Error('Android browser viewport is not mounted');
      host = next;
      const generation = ++mountGeneration;
      if (view.ResizeObserver) { resizeObserver = new view.ResizeObserver(scheduleLayout); resizeObserver.observe(next); }
      if (view.MutationObserver && document.documentElement) {
        mutationObserver = new view.MutationObserver(records => {
          const overlays = '[role="dialog"], [role="menu"], [data-droy-sidebar], [data-droy-backdrop]';
          const relevant = records.some(record => record.type === 'attributes'
            ? record.target === host || record.target.contains?.(host) || record.target.matches?.(overlays)
            : [...record.addedNodes, ...record.removedNodes].some(node => node.nodeType === 1
              && (node === host || node.contains?.(host) || node.matches?.(overlays) || node.querySelector?.(overlays))));
          if (relevant) scheduleLayout();
        });
        mutationObserver.observe(document.documentElement, { attributes: true, childList: true, subtree: true,
          attributeFilter: ['style', 'class', 'hidden', 'inert', 'aria-hidden', 'aria-modal', 'data-droy-drawer-open'] });
      }
      view.addEventListener('resize', scheduleLayout);
      view.addEventListener('scroll', scheduleLayout, true);
      view.visualViewport?.addEventListener('resize', scheduleLayout);
      view.visualViewport?.addEventListener('scroll', scheduleLayout);
      document.addEventListener('visibilitychange', scheduleLayout);
      // An idle/saved DSH tab keeps its own start or restore UI until a native page exists.
      request('state'); schedulePoll(generation);
      return () => { if (generation === mountGeneration) unmount(); };
    },
    dispose() { return frame.dispose(); }
  };
  return { frame, presentation };
}
