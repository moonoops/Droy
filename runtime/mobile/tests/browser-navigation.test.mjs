import test from 'node:test';
import assert from 'node:assert/strict';
import vm from 'node:vm';
import { composeMobileClient } from '../../../tools/mobile_client_bundle.mjs';
import { readFile } from 'node:fs/promises';

const source = composeMobileClient(await readFile(new URL('../client.js', import.meta.url), 'utf8'),
  await readFile(new URL('../touch.js', import.meta.url), 'utf8'));
const settle = async () => { for (let i = 0; i < 24; i++) await Promise.resolve(); };
function fixture({ questionActive = () => true, ready = true, selected = 'chat-a', adopt = true, connect, initialize, refuseOpen = false, phase = 'ready', archivedSessionIds = [],
  workspaces = [{ workspaceId: 'workspace-a', path: '/fictional', sessionIds: ['chat-a'], createdAt: '2026-01-01' }],
  summaries = [{ id: 'chat-a', updatedAt: 100, retainedBy: {} }] } = {}) {
  let client;
  const view = { __ModuleLoader__: { load({ factory }) { client = factory(() => ({})); } } };
  vm.runInNewContext(source, { window: view });
  const calls = [], tabs = [], timers = new Map(), frames = new Map();
  let sequence = 0, tabSequence = 0, expanded = false, mounted = selected, adopted = ready;
  let active = { id: 'guide-a', kind: 'guide' }, navigation = new AbortController();
  view.setTimeout = fn => { const id = ++sequence; timers.set(id, fn); return id; };
  view.clearTimeout = id => timers.delete(id);
  view.requestAnimationFrame = fn => { const id = ++sequence; frames.set(id, fn); return id; };
  view.cancelAnimationFrame = id => frames.delete(id);
  const sessionState = { phase, ids: summaries.map(row => row.id), byId: Object.fromEntries(summaries.map(row => [row.id,
    { ...row, retainedBy: { ...row.retainedBy, ...(row.id === selected ? { mainView: 1 } : {}) } }])) };
  const workspaceState = { phase, items: [...workspaces], archivedSessionIds };
  const sidebarRight = {
    mounted: { getSnapshot: () => mounted }, commandTarget: () => adopted && mounted ? { sessionId: mounted } : undefined,
    openTabs: { getSnapshot: () => tabs }, active: () => active,
    openTab(kind, options) {
      calls.push({ kind, options });
      if (!refuseOpen) {
        active = { kind, id: `ui-${++tabSequence}` };
        tabs.push({ tabId: active.id, kind, sessionId: mounted }); expanded = true;
      }
    },
    focus(tabId) { calls.push({ focus: tabId }); const row = tabs.find(tab => tab.tabId === tabId); if (row) active = { id: row.tabId, kind: row.kind }; },
    isExpanded: () => expanded, toggleExpanded() { expanded = !expanded; }
  };
  const ctx = {
    sidebarRight, sessions: { list: { getSnapshot: () => sessionState } },
    workspaces: { list: { getSnapshot: () => workspaceState }, async initializeDefault(signal) {
      calls.push({ initialize: true });
      const result = initialize ? await initialize(signal) : undefined;
      if (result) workspaceState.items.push(result);
      return result;
    } },
    layout: { beginNavigation() { navigation.abort(); navigation = new AbortController(); return navigation.signal; } },
    uiWorkspace: {
      async connectWorkspace(workspaceId) { calls.push({ connect: workspaceId }); return connect ? connect(workspaceId) : 'chat-created'; },
      openSession(sessionId) {
        navigation.abort();
        calls.push({ select: sessionId }); mounted = sessionId; adopted = adopt;
        for (const row of Object.values(sessionState.byId)) row.retainedBy = {};
        sessionState.byId[sessionId] = { id: sessionId, updatedAt: 200, retainedBy: { mainView: 1 } };
      }
    }
  };
  const bridge = { request(action, args) { calls.push({ action, args }); return Promise.resolve(action === 'petQuestionActive' ? { active: questionActive() } : { status: 'ok' }); } };
  return { navigation: client.createBrowserNavigation(ctx, bridge, error => calls.push({ error }), view), calls, tabs, frames, timers,
    expanded: () => expanded, setExpanded(value) { expanded = value; },
    async draw() { adopted = true; for (const [id, fn] of [...frames]) { frames.delete(id); fn(); } await settle(); },
    async expire() { for (const [id, fn] of [...timers]) { timers.delete(id); fn(); } await settle(); },
    supersede() { ctx.layout.beginNavigation(); },
    setPhases(value) { sessionState.phase = workspaceState.phase = value; },
    selectAnother(sessionId) {
      mounted = sessionId; adopted = true;
      for (const row of Object.values(sessionState.byId)) row.retainedBy = {};
      sessionState.byId[sessionId] = { id: sessionId, updatedAt: 300, retainedBy: { mainView: 1 } };
    }
  };
}

test('browser entry opens a verified DSH page and external action uses the native system browser', async () => {
  const { navigation, calls } = fixture();
  const result = await navigation.openBrowser('https://example.test');
  assert.equal(result.status, 'opened');
  assert.equal(result.chatSessionId, 'chat-a');
  assert.equal(calls[0].kind, 'browser');
  assert.equal(calls[0].options.params.url, 'https://example.test');
  assert.equal(calls.some(call => call.connect || call.select), false, 'an adopted current session needs no new conversation');
  await navigation.openExternalBrowser('https://example.test');
  assert.equal(calls[1].action, 'openExternalBrowser');
  assert.equal(calls[1].args.url, 'https://example.test');
});

test('agent opening reveals the same native session without duplicate navigation or tabs', async () => {
  const page = fixture();
  const first = await page.navigation.revealBrowser({ sessionId: 'native-a' });
  assert.equal(page.calls[0].options.params.nativeSessionId, 'native-a');
  assert.equal(page.calls[0].options.params.url, undefined, 'already opened native sessions must not navigate twice');
  page.setExpanded(false);
  const repeated = await page.navigation.revealBrowser({ sessionId: 'native-a' });
  assert.equal(repeated.tabId, first.tabId);
  assert.equal(page.calls.at(-1).focus, first.tabId);
  assert.equal(page.expanded(), true);
  assert.equal(page.calls.filter(call => call.kind === 'browser').length, 1);
  await page.navigation.browserPage({ operation: 'close', tabId: 'native-a', uiTabId: first.tabId });
  await page.navigation.revealBrowser({ sessionId: 'native-a' });
  assert.equal(page.calls.at(-1).kind, 'browser');
});

test('a blank chat connects through the public workspace API and waits for adoption before opening', async () => {
  const page = fixture({ ready: false, selected: null, adopt: false });
  const result = page.navigation.revealBrowser({ sessionId: 'native-a' }); await settle();
  assert.equal(page.calls[0].connect, 'workspace-a');
  assert.equal(page.calls[1].select, 'chat-created');
  assert.equal(page.calls.some(call => call.kind === 'browser'), false, 'selected/mounted alone does not mean its store was adopted');
  await page.draw();
  assert.equal((await result).chatSessionId, 'chat-created');
  assert.equal(page.calls.at(-1).options.params.nativeSessionId, 'native-a');
});

test('an existing main-view reference is reused while its sidebar surface mounts', async () => {
  const page = fixture({ ready: false, adopt: false });
  const result = page.navigation.openBrowser(); await settle();
  assert.equal(page.calls[0].select, 'chat-a');
  assert.equal(page.calls.some(call => call.connect), false);
  assert.equal(page.calls.some(call => call.kind === 'browser'), false);
  await page.draw();
  assert.equal((await result).chatSessionId, 'chat-a');
});

test('workspace selection follows DSH recency and first-use default initialization respects refusal', async () => {
  const recent = fixture({ ready: false, selected: null,
    workspaces: [
      { workspaceId: 'older', sessionIds: ['a'], createdAt: '2026-01-01' },
      { workspaceId: 'recent', sessionIds: ['b'], createdAt: '2026-01-01' }
    ], summaries: [{ id: 'a', updatedAt: 10 }, { id: 'b', updatedAt: 20 }] });
  await recent.navigation.openBrowser();
  assert.equal(recent.calls[0].connect, 'recent');
  const blank = fixture({ ready: false, selected: null, workspaces: [], summaries: [],
    initialize: async () => ({ workspaceId: 'host-default', sessionIds: [], createdAt: '2026-01-01' }) });
  await blank.navigation.openBrowser();
  assert.equal(blank.calls[0].initialize, true);
  assert.equal(blank.calls[1].connect, 'host-default');
  const refused = fixture({ ready: false, selected: null, workspaces: [], summaries: [] });
  await assert.rejects(refused.navigation.openBrowser(), /没有可用空间/);
  assert.equal(refused.calls.some(call => call.connect || call.select || call.kind === 'browser'), false);
});

test('creation failures, adoption timeouts and a no-op open never claim a browser was opened', async () => {
  const failed = fixture({ ready: false, selected: null, connect: async () => { throw new Error('Creation refused'); } });
  await assert.rejects(failed.navigation.openBrowser(), /Creation refused/);
  assert.equal(failed.calls.some(call => call.select || call.kind === 'browser'), false);
  const stalled = fixture({ ready: false, adopt: false });
  const pending = stalled.navigation.openBrowser(); await settle();
  const rejection = assert.rejects(pending, /未能就绪/);
  await stalled.expire(); await rejection;
  assert.equal(stalled.calls.some(call => call.kind === 'browser'), false);
  assert.equal(stalled.frames.size, 0);
  const noOp = fixture({ refuseOpen: true });
  await assert.rejects(noOp.navigation.openBrowser(), /标签未能打开/);
});

test('navigation while catalogs load cancels the original browser request before any session is selected', async () => {
  const page = fixture({ ready: false, selected: null, phase: 'loading' });
  const result = page.navigation.openBrowser(); await settle();
  const rejection = assert.rejects(result, /页面已切换/);
  page.supersede();
  page.setPhases('ready');
  await page.draw(); await rejection;
  assert.equal(page.calls.some(call => call.initialize || call.connect || call.select || call.kind === 'browser'), false);
  assert.equal(page.frames.size, 0);
});

test('dispose or timeout during connection prevents a late result from selecting or opening a chat', async () => {
  for (const stop of ['dispose', 'timeout', 'supersede', 'select']) {
    let finish;
    const page = fixture({ ready: false, selected: null, connect: () => new Promise(resolve => { finish = resolve; }) });
    const result = page.navigation.openBrowser(); await settle();
    const rejection = assert.rejects(result, stop === 'dispose' ? /页面已关闭/ : stop === 'timeout' ? /未能就绪/ : /页面已切换/);
    if (stop === 'dispose') page.navigation.dispose();
    if (stop === 'timeout') await page.expire();
    if (stop === 'supersede') page.supersede();
    if (stop === 'select') page.selectAnother('user-selected-chat');
    finish('late-chat'); await rejection; await settle();
    assert.equal(page.calls.some(call => call.select || call.kind === 'browser'), false, stop);
  }
});

test('pet task entry opens the original session and collapses its browser without creating a session or turn', async () => {
  const page = fixture({ summaries: [{ id: 'chat-a', updatedAt: 100 }, { id: 'original-task', updatedAt: 90 }] });
  page.setExpanded(true);
  const result = await page.navigation.revealPetTask({ taskId: 'original-task' });
  assert.equal(result.status, 'opened');
  assert.equal(result.taskId, 'original-task');
  assert.deepEqual(page.calls, [{ select: 'original-task' }]);
  assert.equal(page.expanded(), false);
});

test('pet task entry rejects missing and archived targets without selecting another conversation', async () => {
  const page = fixture({ archivedSessionIds: ['chat-a'] });
  for (const taskId of [undefined, '', '   ', 123])
    await assert.rejects(page.navigation.revealPetTask({ taskId }), /没有可打开/);
  await assert.rejects(page.navigation.revealPetTask({ taskId: 'missing-task' }), /已不存在/);
  await assert.rejects(page.navigation.revealPetTask({ taskId: 'chat-a' }), /已归档/);
  assert.deepEqual(page.calls, []);
});

test('pet task waits for startup catalog and session adoption but yields to later user navigation', async () => {
  const page = fixture({ ready: false, adopt: false, phase: 'loading' });
  const result = page.navigation.revealPetTask({ taskId: 'chat-a' }); await settle();
  assert.deepEqual(page.calls, []);
  page.setPhases('ready'); await page.draw();
  assert.deepEqual(page.calls, [{ select: 'chat-a' }]);
  await page.draw();
  assert.equal((await result).taskId, 'chat-a');
  const superseded = fixture({ ready: false, adopt: false, phase: 'loading' });
  const pending = superseded.navigation.revealPetTask({ taskId: 'chat-a' }); await settle();
  const rejected = assert.rejects(pending, /页面已切换/);
  superseded.supersede(); superseded.setPhases('ready'); await superseded.draw(); await rejected;
  assert.deepEqual(superseded.calls, []);
});


test('question return checks the live request after catalog wait and verifies it again after adoption', async () => {
  let active = true;
  const page = fixture({ phase: 'loading', questionActive: () => active });
  const navigation = page.navigation.revealPetTask({ taskId: 'chat-a', requestId: 'question-a' });
  const rejection = assert.rejects(navigation, /已处理/);
  await settle(); active = false; page.setPhases('ready'); await page.draw(); await rejection;
  assert.equal(page.calls.some(call => call.select), false, 'late catalog readiness must not reopen an answered question');

  let checks = 0;
  const arrived = fixture({ questionActive: () => ++checks === 1 });
  await assert.rejects(arrived.navigation.revealPetTask({ taskId: 'chat-a', requestId: 'question-b' }), /已处理/);
  assert.equal(checks, 2, 'cancellation during navigation must not receive a successful arrival receipt');
  const live = fixture();
  const result = await live.navigation.revealPetTask({ taskId: 'chat-a', requestId: 'question-c' });
  assert.equal(result.status, 'opened');
  assert.equal(live.calls.filter(call => call.action === 'petQuestionActive').length, 2);
});

test('a newer question in the same task supersedes older catalog-wait navigation', async () => {
  const page = fixture({ phase: 'loading' });
  const older = page.navigation.revealPetTask({ taskId: 'chat-a', requestId: 'older-question' });
  const rejected = assert.rejects(older, /页面已切换/);
  await settle();
  const newer = page.navigation.revealPetTask({ taskId: 'chat-a', requestId: 'newer-question' });
  page.setPhases('ready'); await page.draw();
  await rejected;
  assert.equal((await newer).status, 'opened');
  assert.equal(page.calls.filter(call => call.select).length, 1);
  assert.ok(page.calls.filter(call => call.action === 'petQuestionActive').every(call => call.args.requestId === 'newer-question'));
});
