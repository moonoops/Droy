import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { Script } from 'node:vm';
import { createNativeBrowserPage } from '../browser-page.mjs';
import { patchSidebarBrowser } from '../../../tools/mobile_browser_patch.mjs';

const settle = async () => { for (let i = 0; i < 24; i++) await Promise.resolve(); };

function fixture({ initial, tabId = 'tab-a', nativeSessionId, browserPage, locale = 'zh' } = {}) {
  const calls = [], persisted = [], timers = new Map(), layouts = new Map(), listeners = new Map();
  let sequence = 0, nativeRevision = 0;
  let rect = { left: 10, top: 80, width: 390, height: 550 }, overlays = [];
  let native = { sessionId: nativeSessionId || tabId, sessionOpen: true, url: '', title: '', loading: false,
    canGoBack: false, canGoForward: false };
  function eventTarget() {
    return {
      addEventListener(type, listener) { listeners.set(`${type}-${listeners.size}`, { type, listener }); },
      removeEventListener(type, listener) {
        for (const [id, entry] of listeners) if (entry.type === type && entry.listener === listener) listeners.delete(id);
      }
    };
  }
  const host = { isConnected: true, getBoundingClientRect: () => rect, getClientRects: () => rect.width ? [rect] : [] };
  const view = {
    ...eventTarget(), innerWidth: 640, innerHeight: 900, devicePixelRatio: 2.75,
    setTimeout(fn) { const id = ++sequence; timers.set(id, fn); return id; },
    clearTimeout(id) { timers.delete(id); },
    requestAnimationFrame(fn) { const id = ++sequence; layouts.set(id, fn); return id; },
    cancelAnimationFrame(id) { layouts.delete(id); },
    ResizeObserver: class { constructor(callback) { this.callback = callback; } observe() {} disconnect() {} },
    MutationObserver: class { constructor(callback) { this.callback = callback; } observe() {} disconnect() {} }
  };
  const document = { ...eventTarget(), defaultView: view, documentElement: { lang: locale }, visibilityState: 'visible',
    getElementById: id => id === 'viewport' ? host : null, querySelectorAll: () => overlays,
    createElement() { throw new Error('The native page must not add browser chrome or an iframe'); } };
  const createStore = value => {
    const subscribers = new Set();
    return { getSnapshot: () => value, set(next) { value = next; for (const notify of subscribers) notify(); },
      subscribe(notify) { subscribers.add(notify); return () => subscribers.delete(notify); } };
  };
  const page = createNativeBrowserPage({ initial, tabId, nativeSessionId, persist: value => persisted.push(value) }, {
    createStore, document, window: view,
    async browserPage(args) {
      calls.push(args);
      if (browserPage) return browserPage(args);
      if (args.operation === 'navigate') native = { ...native, url: args.url, loading: true };
      if (args.operation === 'close') native = { ...native, sessionOpen: false };
      return { ...native, revision: ++nativeRevision };
    }
  });
  return { ...page, calls, persisted, timers, listeners, document,
    setNative(update) { native = { ...native, ...update }; },
    setRect(update) { rect = { ...rect, ...update }; },
    setWindow(update) { Object.assign(view, update); },
    setOverlays(value) { overlays = value; },
    async poll() { const [id, fn] = timers.entries().next().value || []; if (fn) { timers.delete(id); await fn(); await settle(); } },
    async layout() {
      for (const { type, listener } of [...listeners.values()]) if (type === 'resize') listener();
      for (const [id, fn] of [...layouts]) { layouts.delete(id); fn(); }
      await settle();
    }
  };
}

test('DSH mounts only native content and displays actual loading, redirects, title and history', async () => {
  const page = fixture();
  const hide = page.presentation.mount('viewport');
  await settle();
  assert.deepEqual(page.calls[0], { operation: 'state', tabId: 'tab-a', uiTabId: 'tab-a' });
  assert.equal(page.calls.some(call => call.operation === 'attach'), false, 'empty native pages must not cover DSH start or restore controls');
  assert.equal(page.frame.getSnapshot().target, undefined);
  page.frame.loadUrl({ url: 'https://example.test/requested' });
  await settle();
  assert.equal(page.frame.getSnapshot().loading, true, 'dispatch acknowledgement is not a load-complete signal');
  page.setNative({ url: 'https://example.test/redirected', title: 'Actual title', loading: false, canGoBack: true });
  await page.poll();
  assert.deepEqual(page.frame.getSnapshot().target, { url: 'https://example.test/redirected', title: 'Actual title' });
  assert.equal(page.frame.getSnapshot().address, 'known');
  assert.equal(page.frame.getSnapshot().canGoBack, true);
  assert.equal(page.frame.getSnapshot().loading, false);
  assert.deepEqual(page.persisted.at(-1).entries, [{ url: 'https://example.test/redirected', title: 'Actual title' }]);
  page.frame.goBack(); await settle();
  assert.equal(page.calls.at(-1).operation, 'back');
  hide(); await settle();
  assert.equal(page.calls.at(-1).operation, 'detach');
  assert.equal(page.timers.size, 0, 'hidden pages must stop polling');
  page.presentation.mount('viewport'); await settle();
  assert.equal(page.calls.filter(call => call.operation === 'navigate').length, 1, 'reattach preserves native history');
  await page.frame.dispose();
  assert.equal(page.calls.at(-1).operation, 'close');
});

test('saved addresses stay idle, native errors surface, and tabs keep independent session identities', async () => {
  const a = fixture({ initial: { entries: [{ url: 'https://example.test/saved' }], index: 0 } });
  const b = fixture({ tabId: 'tab-b', nativeSessionId: 'agent-session-b' });
  a.presentation.mount('viewport'); b.presentation.mount('viewport'); await settle();
  assert.equal(a.calls.some(call => call.operation === 'navigate'), false, 'restore requires an explicit DSH action');
  b.frame.loadUrl({ url: 'https://example.test/b' }); await settle();
  assert.ok(b.calls.every(call => call.tabId === 'agent-session-b' && call.uiTabId === 'tab-b'));
  assert.ok(a.calls.every(call => call.tabId === 'tab-a'));
  b.setNative({ error: { code: 'net-error', description: 'Connection failed' }, loading: false });
  await b.poll();
  assert.equal(b.frame.getSnapshot().error.description, 'Connection failed');
  assert.equal(a.frame.getSnapshot().error, undefined);
  await a.frame.dispose(); await b.frame.dispose();
});

test('resize, scroll and overlays update or detach the native viewport without navigation', async () => {
  const page = fixture();
  page.setNative({ url: 'https://example.test/visible' });
  page.presentation.mount('viewport'); await settle();
  page.setRect({ top: 110, width: 500 }); await page.layout();
  assert.equal(page.calls.at(-1).operation, 'attach');
  assert.deepEqual(page.calls.at(-1).viewport, { left: 10, top: 110, width: 500, height: 550, viewportWidth: 640 });
  page.setOverlays([{ hasAttribute: () => false }]); await page.layout();
  assert.equal(page.calls.at(-1).operation, 'detach');
  page.setOverlays([]); await page.layout();
  assert.equal(page.calls.at(-1).operation, 'attach');
  page.document.visibilityState = 'hidden'; await page.layout();
  assert.equal(page.calls.at(-1).operation, 'detach');
  assert.equal(page.calls.some(call => call.operation === 'navigate'), false);
  await page.frame.dispose();
  assert.equal(page.listeners.size, 0);
});

test('transitional out-of-viewport geometry detaches and polling recovers without a resize event', async () => {
  const page = fixture();
  page.setNative({ url: 'https://example.test/visible' });
  page.presentation.mount('viewport'); await settle();
  assert.equal(page.calls.at(-1).viewport.viewportWidth, 640);
  const attachments = page.calls.filter(call => call.operation === 'attach').length;
  page.setRect({ left: 400 }); await page.layout();
  assert.equal(page.calls.at(-1).operation, 'detach');
  assert.equal(page.calls.filter(call => call.operation === 'attach').length, attachments);
  page.setRect({ left: 10 });
  await page.poll();
  assert.equal(page.calls.filter(call => call.operation === 'attach').length, attachments + 1,
    'the final transform position must recover even without resize or mutation events');
  assert.deepEqual(page.calls.findLast(call => call.operation === 'attach').viewport,
    { left: 10, top: 80, width: 390, height: 550, viewportWidth: 640 });
  page.setWindow({ innerWidth: 0 }); await page.layout();
  assert.equal(page.calls.at(-1).operation, 'detach');
  page.setWindow({ innerWidth: NaN }); await page.layout();
  assert.equal(page.calls.at(-1).operation, 'detach');
  page.setWindow({ innerWidth: 640 }); await page.poll();
  assert.equal(page.calls.filter(call => call.operation === 'attach').length, attachments + 2);
  await page.frame.dispose();
});

test('viewport rounding permits half a physical pixel but rejects offsets beyond one physical pixel', async () => {
  const page = fixture();
  page.setWindow({ devicePixelRatio: 2.5 });
  page.setRect({ left: 0, width: 640 + 0.5 / 2.5 });
  page.setNative({ url: 'https://example.test/visible' });
  page.presentation.mount('viewport'); await settle();
  assert.equal(page.calls.at(-1).operation, 'attach');
  assert.equal(page.calls.at(-1).viewport.width, 640.2, 'fractional geometry must not be clamped');
  const attachments = page.calls.filter(call => call.operation === 'attach').length;
  page.setRect({ width: 640 + 1.1 / 2.5 }); await page.layout();
  assert.equal(page.calls.at(-1).operation, 'detach');
  assert.equal(page.calls.filter(call => call.operation === 'attach').length, attachments);
  page.setRect({ width: 640 });
  for (const devicePixelRatio of [0, NaN]) {
    page.setWindow({ devicePixelRatio }); await page.layout();
    assert.equal(page.calls.filter(call => call.operation === 'attach').length, attachments);
  }
  page.setWindow({ devicePixelRatio: 2.5 }); await page.poll();
  assert.equal(page.calls.filter(call => call.operation === 'attach').length, attachments + 1);
  await page.frame.dispose();
});

test('overlays and unmount detach immediately while navigation is pending and queued attachments stay hidden', async () => {
  for (const hideWith of ['overlay', 'unmount']) {
    let finish;
    const native = { sessionId: 'tab-a', sessionOpen: true, url: 'https://example.test/old', revision: 1 };
    const page = fixture({ browserPage: args => args.operation === 'navigate'
      ? new Promise(resolve => { finish = resolve; }) : Promise.resolve(native) });
    const hide = page.presentation.mount('viewport'); await settle();
    assert.equal(page.calls.at(-1).operation, 'attach');
    page.frame.loadUrl({ url: 'https://example.test/slow-dns' }); await settle();
    assert.equal(page.calls.at(-1).operation, 'navigate');
    const attachments = page.calls.filter(call => call.operation === 'attach').length;
    page.setRect({ top: 110 }); await page.layout();
    assert.equal(page.calls.at(-1).operation, 'navigate', 'placement is queued behind the pending navigation');
    if (hideWith === 'overlay') {
      page.setOverlays([{ hasAttribute: () => false }]); await page.layout();
    } else hide();
    assert.equal(page.calls.at(-1).operation, 'detach', `${hideWith} cannot wait for navigation/DNS`);
    // A queued placement from before hiding must not recreate the native surface.
    finish({ ...native, url: 'https://example.test/slow-dns', revision: 2 }); await settle();
    assert.equal(page.calls.filter(call => call.operation === 'attach').length, attachments);
    await page.frame.dispose();
  }
});

test('closing releases native contents immediately without waiting for pending navigation', async () => {
  let finish;
  const native = { sessionId: 'tab-a', sessionOpen: true, url: 'https://example.test/old', revision: 1 };
  const page = fixture({ browserPage: args => args.operation === 'navigate'
    ? new Promise(resolve => { finish = resolve; }) : Promise.resolve(native) });
  page.presentation.mount('viewport'); await settle();
  page.frame.loadUrl({ url: 'https://example.test/slow-dns' }); await settle();
  const before = page.frame.getSnapshot();
  const disposal = page.frame.dispose();
  assert.equal(page.calls.at(-1).operation, 'close');
  await disposal;
  finish({ ...native, url: 'https://example.test/late', revision: 2 }); await settle();
  assert.equal(page.frame.getSnapshot(), before);
  assert.equal(page.calls.at(-1).operation, 'close');
});

test('unexpected state fails visibly and disposed pages ignore late replies while releasing native resources', async () => {
  const invalid = fixture({ browserPage: async () => ({ sessionId: 'another-tab', sessionOpen: true }) });
  invalid.presentation.mount('viewport'); await settle();
  assert.match(invalid.frame.getSnapshot().error.description, /状态不完整/);
  await invalid.frame.dispose();
  let finish;
  const pending = fixture({ browserPage: args => args.operation === 'navigate'
    ? new Promise(resolve => { finish = resolve; })
    : Promise.resolve({ sessionId: 'tab-a', sessionOpen: args.operation !== 'close', revision: 3 }) });
  pending.frame.loadUrl({ url: 'https://example.test' }); await settle();
  const before = pending.frame.getSnapshot();
  const disposal = pending.frame.dispose();
  finish({ sessionId: 'tab-a', sessionOpen: true, url: 'https://example.test/late', revision: 4 });
  await disposal;
  assert.equal(pending.frame.getSnapshot(), before);
  assert.equal(pending.calls.at(-1).operation, 'close');
});

test('browser patch retains DSH controls, bridges external opening, and rejects changed upstream contracts', async () => {
  const source = await readFile(new URL('../../dsh/node_modules/@deepseek-ai/dsh-client-ui-sidebar-browser/lib/client.js', import.meta.url), 'utf8');
  const patched = patchSidebarBrowser(source);
  new Script(patched);
  assert.ok(patched.includes('function BrowserBody(props)'));
  assert.ok(patched.includes('const presentation = new IframePresentation'));
  assert.ok(patched.includes('nativeSessionId: tab.navigation.params?.nativeSessionId'));
  assert.ok(patched.includes('window.DroyMobile?.openExternalBrowser(externalUrl)'));
  assert.ok(patched.includes('return window.DroyMobile.browserPage(args)'));
  assert.equal(patched.includes('在 Droy 浏览器中继续'), false);
  assert.throws(() => patchSidebarBrowser(patched), /anchor/);
  for (const anchor of ['function createIframePage(options)', 'initialUrl: initialUrl.current,', 'window.open(externalUrl, "_blank", "noopener,noreferrer");'])
    assert.throws(() => patchSidebarBrowser(source.replace(anchor, 'changedAnchor')), /anchor/);
});

test('a native browser covered by settings stops state polling and resumes after settings close', async () => {
  const page = fixture(); page.presentation.mount('viewport'); await settle();
  page.frame.loadUrl({ url: 'https://example.test/visible' }); await settle();
  page.setOverlays([{ hasAttribute: () => false }]);
  await page.layout();
  const reads = page.calls.filter(item => item.operation === 'state').length;
  await page.poll(); await page.poll();
  assert.equal(page.calls.filter(item => item.operation === 'state').length, reads);
  assert.equal(page.calls.at(-1).operation, 'detach');
  page.setOverlays([]); await page.layout(); await page.poll();
  assert.equal(page.calls.filter(item => item.operation === 'state').length, reads + 1);
  await page.frame.dispose();
});
