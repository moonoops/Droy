import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import vm from 'node:vm';
import { createHash } from 'node:crypto';

const adapterSource = await readFile(new URL('../dsh-adapter.js', import.meta.url), 'utf8');
const clientSource = (await readFile(new URL('../client.js', import.meta.url), 'utf8')).replace('/* DROY_MEDIA_ADAPTER */', adapterSource);
function load() {
  let exports;
  const window = { __ModuleLoader__: { load({ factory }) { exports = factory(() => ({})); } } };
  vm.runInNewContext(clientSource, { window });
  return exports;
}
const { createImportController, createDshMediaAdapter, observeMediaCatalogs } = load();
const settle = async () => { for (let i = 0; i < 6; i++) await new Promise(resolve => setImmediate(resolve)); };
const row = (overrides = {}) => ({ id: 'batch', source: 'share', text: 'shared text', textApplied: false, items: [{ id: 'item', name: 'photo.png', mediaType: 'image/png', bytes: 3 }], ...overrides });

function fixture(initial = [row()]) {
  let imports = structuredClone(initial), draftSequence = 0, createCount = 0, attachments = 0;
  const calls = [], handles = new Map(), liveDrafts = new Set(), opens = [], listeners = new Map();
  const handle = id => {
    if (!handles.has(id)) {
      const state = { draft: id === 'existing' ? 'existing draft' : '', attachmentIds: [], phase: 'plain', draftRev: 0 };
      const session = { pendingSubmissions: [], promptError: null };
      handles.set(id, { binding: { sessionId: id, session: { getSnapshot: () => session } }, input: { state: { getSnapshot: () => state } }, release() {}, state, session });
    }
    return handles.get(id);
  };
  const adapter = {
    acquire: async id => handle(id),
    createSession: async () => `new-${++createCount}`,
    open: id => opens.push(id),
    validate(_handle, files) { if (files.length > 10) throw new Error('too many'); },
    remaining: current => 10 - current.state.attachmentIds.length,
    attach(current, files) {
      attachments += files.length;
      return files.map(file => {
        const draft = { id: `draft-${++draftSequence}`, file };
        liveDrafts.add(draft.id); current.state.attachmentIds.push(draft.id);
        return draft;
      });
    },
    appendText(current, text) { current.state.draft += (current.state.draft ? '\n' : '') + text; },
    draftPresent: id => liveDrafts.has(id),
    subscribe(current, listener) { listeners.set(current.binding.sessionId, listener); return () => listeners.delete(current.binding.sessionId); },
  };
  const native = async args => {
    calls.push(structuredClone(args));
    const batch = imports.find(value => value.id === args.id);
    if (args.operation === 'list') return { imports: structuredClone(imports) };
    if (args.operation === 'bind') { batch.targetSessionId = args.targetSessionId; if (args.textApplied) batch.textApplied = true; if (args.textPrepared) { batch.textPrepared = true; batch.textBeforeHash = args.textBeforeHash; } }
    if (args.operation === 'removeItems') batch.items = batch.items.filter(item => !args.itemIds.includes(item.id));
    if (args.operation === 'complete') imports = imports.filter(value => value.id !== args.id);
    return {};
  };
  const environment = { fetch: async () => new Response(new Blob(['abc'])), File, hashText: async text => createHash('sha256').update(text).digest('hex') };
  const controller = createImportController(adapter, native, environment);
  return { controller, adapter, native, environment, calls, handles, handle, liveDrafts, opens, listeners,
    get imports() { return imports; }, get attachments() { return attachments; }, get createCount() { return createCount; } };
}

test('share preview defaults to a new draft and closing preserves it for reopening', async () => {
  const f = fixture();
  await f.controller.refresh();
  assert.equal(f.controller.getSnapshot().dialogId, 'batch');
  assert.equal(f.createCount, 0);
  f.controller.close();
  await f.controller.refresh();
  assert.equal(f.controller.getSnapshot().dialogId, null);
  assert.equal(f.imports.length, 1);
  f.controller.open();
  assert.equal(f.controller.getSnapshot().dialogId, 'batch');
  await f.controller.adopt('batch');
  assert.equal(f.createCount, 1);
  assert.equal(f.handle('new-1').state.draft, 'shared text');
  assert.equal(f.opens.at(-1), 'new-1');
  assert.equal(f.attachments, 1);
  assert.equal(f.calls.some(call => call.operation === 'complete'), false);
  f.controller.dispose();
});

test('existing draft is appended and repeated native notifications never duplicate bytes or text', async () => {
  const f = fixture();
  await f.controller.refresh();
  await f.controller.adopt('batch', 'existing');
  await Promise.all([f.controller.refresh(), f.controller.refresh(), f.controller.refresh()]);
  assert.equal(f.handle('existing').state.draft, 'existing draft\nshared text');
  assert.equal(f.attachments, 1);
  assert.equal(f.createCount, 0);
  assert.equal(f.imports[0].textApplied, true);
  f.controller.dispose();
});

test('page reload restores attachments into their saved session without reappending edited text', async () => {
  const f = fixture([row({ targetSessionId: 'existing', textApplied: true })]);
  f.handle('existing').state.draft = 'user edited the imported text';
  await f.controller.refresh();
  assert.equal(f.handle('existing').state.draft, 'user edited the imported text');
  assert.equal(f.handle('existing').state.attachmentIds.length, 1);
  assert.equal(f.createCount, 0);
  assert.equal(f.opens.length, 0, 'background recovery should not steal the selected session');
  assert.match(f.controller.getSnapshot().notice, /核对发送记录/);
  f.controller.dispose();
});

test('a prepared text edit interrupted before insertion resumes from the matching draft fingerprint', async () => {
  const f = fixture([row({ targetSessionId: 'existing', textPrepared: true,
    textBeforeHash: createHash('sha256').update('existing draft').digest('hex') })]);
  await f.controller.refresh();
  assert.equal(f.handle('existing').state.draft, 'existing draft\nshared text');
  assert.equal(f.imports[0].textApplied, true);
  f.controller.dispose();
});

test('a prepared text edit with a changed draft requires review and never appends automatically', async () => {
  const f = fixture([row({ targetSessionId: 'existing', textPrepared: true,
    textBeforeHash: createHash('sha256').update('before crash').digest('hex') })]);
  f.handle('existing').state.draft = 'user changed the draft after interruption';
  await f.controller.refresh();
  assert.equal(f.handle('existing').state.draft, 'user changed the draft after interruption');
  assert.equal(f.imports[0].textApplied, false);
  assert.equal(f.controller.getSnapshot().textNeedsReview, 'batch');
  assert.match(f.controller.getSnapshot().error, /中断/);
  await f.controller.keepCurrentText('batch');
  assert.equal(f.imports[0].textApplied, true);
  assert.equal(f.controller.getSnapshot().textNeedsReview, null);
  assert.equal(f.controller.errorFor('batch'), undefined);
  assert.equal(f.controller.pendingRows().length, 0);
  assert.equal(f.handle('existing').state.draft, 'user changed the draft after interruption');
  f.controller.dispose();
});

test('recovery acknowledges a prepared text suffix without a second insertion', async () => {
  const f = fixture([row({ targetSessionId: 'existing', textPrepared: true,
    textBeforeHash: createHash('sha256').update('existing draft').digest('hex') })]);
  f.handle('existing').state.draft = 'existing draft\nshared text';
  await f.controller.refresh();
  assert.equal(f.handle('existing').state.draft, 'existing draft\nshared text');
  assert.equal(f.imports[0].textApplied, true);
  f.controller.dispose();
});

test('a result from a picker opened on this page does not show a recovery warning', async () => {
  const f = fixture([]);
  await f.controller.pick('album', 'existing');
  f.imports.push(row({ source: 'album', targetSessionId: 'existing', text: undefined }));
  await f.controller.refresh();
  assert.equal(f.controller.getSnapshot().notice, null);
  f.controller.dispose();
});

test('an immediate picker result before the launch response is treated as a new selection', async () => {
  const f = fixture([]);
  let controller;
  controller = createImportController(f.adapter, async args => {
    if (args.operation === 'pick') {
      f.imports.push(row({ source: 'album', targetSessionId: 'existing', text: undefined }));
      await controller.refresh();
    }
    return f.native(args);
  }, f.environment);
  await controller.pick('album', 'existing');
  assert.equal(f.attachments, 1);
  assert.equal(controller.getSnapshot().notice, null);
  controller.dispose(); f.controller.dispose();
});

test('a failed picker launch rolls back page ownership for later genuine recovery', async () => {
  const f = fixture([]);
  const controller = createImportController(f.adapter, async args => {
    if (args.operation === 'pick') throw new Error('launch failed');
    return f.native(args);
  }, f.environment);
  await controller.pick('album', 'existing');
  f.imports.push(row({ source: 'album', targetSessionId: 'existing', text: undefined }));
  await controller.refresh();
  assert.match(controller.getSnapshot().notice, /核对发送记录/);
  controller.dispose(); f.controller.dispose();
});

test('optimistic submit clear retains imported originals until DSH releases successful draft objects', async () => {
  const f = fixture([row({ text: undefined, source: 'album', targetSessionId: 'existing' })]);
  await f.controller.refresh();
  const current = f.handle('existing'), draftId = current.state.attachmentIds[0];
  current.state.attachmentIds = [];
  f.listeners.get('existing')();
  await settle();
  assert.equal(f.calls.some(call => call.operation === 'complete'), false);
  assert.equal(f.imports[0].items.length, 1);
  f.liveDrafts.delete(draftId);
  f.listeners.get('existing')();
  await settle();
  assert.equal(f.imports.length, 0);
  assert.equal(f.calls.some(call => call.operation === 'removeItems'), true);
  f.controller.dispose();
});

test('failed album save retains the original after successful message attachment consumption', async () => {
  const f = fixture([row({ text: undefined, source: 'camera', targetSessionId: 'existing', albumSaved: false, albumError: '未保存到相册' })]);
  await f.controller.refresh();
  const current = f.handle('existing');
  for (const id of current.state.attachmentIds) f.liveDrafts.delete(id);
  current.state.attachmentIds = [];
  f.listeners.get('existing')();
  await settle();
  assert.equal(f.imports.length, 1);
  assert.equal(f.imports[0].items.length, 0);
  assert.equal(f.controller.canDiscard('batch'), true);
  await f.controller.retryAlbum('batch');
  assert.equal(f.calls.at(-1).operation, 'retryAlbum');
  await f.controller.discard('batch');
  assert.equal(f.imports.length, 0);
  f.controller.dispose();
});

test('remaining image budget is passed to native picker and a full draft does not open it', async () => {
  const f = fixture([]), current = f.handle('existing');
  current.state.attachmentIds = ['one', 'two', 'three'];
  await f.controller.pick('album', 'existing');
  assert.equal(f.calls.at(-1).remaining, 7);
  current.state.attachmentIds = Array(10).fill('full');
  await f.controller.pick('camera', 'existing');
  assert.equal(f.calls.filter(call => call.operation === 'pick').length, 1);
  assert.match(f.controller.getSnapshot().error, /10/);
  f.controller.dispose();
});

test('explicit discard removes unadopted contents while disposal alone preserves them', async () => {
  const f = fixture(); await f.controller.refresh(); f.controller.dispose();
  assert.equal(f.imports.length, 1);
  const other = fixture(); await other.controller.refresh(); await other.controller.discard('batch');
  assert.equal(other.imports.length, 0); other.controller.dispose();
});

test('synchronous input removal observes the subsequent DSH object release before native cleanup', async () => {
  const f = fixture([row({ text: undefined, targetSessionId: 'existing' })]);
  await f.controller.refresh();
  const current = f.handle('existing'), id = current.state.attachmentIds[0];
  current.state.attachmentIds = [];
  f.listeners.get('existing')();
  f.liveDrafts.delete(id);
  await settle();
  assert.equal(f.imports.length, 0);
  f.controller.dispose();
});

test('refresh racing a native bind joins the in-flight adoption', async () => {
  const f = fixture();
  let controller, interrupted;
  controller = createImportController(f.adapter, async args => {
    const result = await f.native(args);
    if (args.operation === 'bind' && !args.textApplied && !interrupted) {
      interrupted = controller.refresh();
      await new Promise(resolve => setImmediate(resolve));
    }
    return result;
  }, f.environment);
  await controller.refresh();
  await controller.adopt('batch', 'existing');
  await interrupted;
  assert.equal(f.attachments, 1);
  assert.equal(f.handle('existing').state.draft, 'existing draft\nshared text');
  controller.dispose(); f.controller.dispose();
});

test('a second removal during native cleanup is drained before the cleanup flight ends', async () => {
  const f = fixture([row({ text: undefined, targetSessionId: 'existing', items: [
    { id: 'first', name: 'one.png', mediaType: 'image/png', bytes: 3 },
    { id: 'second', name: 'two.png', mediaType: 'image/png', bytes: 3 },
  ] })]);
  let releaseNative, started;
  const waiting = new Promise(resolve => { started = resolve; });
  let delay = true;
  const controller = createImportController(f.adapter, async args => {
    if (args.operation === 'removeItems' && delay) {
      delay = false; started();
      await new Promise(resolve => { releaseNative = resolve; });
    }
    return f.native(args);
  }, f.environment);
  await controller.refresh();
  const current = f.handle('existing'), [first, second] = current.state.attachmentIds;
  current.state.attachmentIds = [second];
  f.listeners.get('existing')(); f.liveDrafts.delete(first);
  await waiting;
  current.state.attachmentIds = [];
  f.listeners.get('existing')(); f.liveDrafts.delete(second);
  await settle();
  releaseNative();
  await settle();
  assert.equal(f.imports.length, 0);
  assert.equal(f.calls.filter(call => call.operation === 'removeItems').length, 2);
  controller.dispose(); f.controller.dispose();
});

test('a bound fatal native error remains explicitly discardable', async () => {
  const f = fixture([row({ targetSessionId: 'existing', items: [], error: '无法读取文件' })]);
  await f.controller.refresh();
  assert.equal(f.controller.canDiscard('batch'), true);
  await f.controller.discard('batch');
  assert.equal(f.imports.length, 0);
  f.controller.dispose();
});

test('a successful album retry cleans an empty camera import after page restoration', async () => {
  const f = fixture([row({ source: 'camera', targetSessionId: 'existing', items: [], text: undefined, albumSaved: true })]);
  await f.controller.refresh();
  assert.equal(f.imports.length, 0);
  assert.equal(f.attachments, 0);
  f.controller.dispose();
});

test('DSH adapter uses scoped CAS insertion and preserves structured reference occurrences', async () => {
  const state = { draft: '@existing-file', draftRev: 17, attachmentIds: [], phase: 'plain', occurrences: [{ source: 'reference' }] };
  const writes = [], fakeContext = { bail(subject, event, request) { writes.push({ subject, event, request }); return true; } };
  const limits = { maxImagesPerMessage: 20, maxImageBytes: 20, maxMessageImageBytes: 100 };
  const binding = { sessionId: 'existing', ctx: fakeContext, session: { projections: { faceOf: () => ({ getSnapshot: () => limits }) } } };
  const adapter = createDshMediaAdapter({ conversation: { blocks: { storeFor: () => ({ getSnapshot: () => undefined }) }, resolveDraftAttachments: () => [] } });
  const handle = { binding, input: { state: { getSnapshot: () => state } } };
  adapter.appendText(handle, 'shared');
  assert.equal(writes[0].subject, fakeContext);
  assert.equal(writes[0].event, 'slash/input-insert-text');
  assert.equal(writes[0].request.span.start, state.draft.length);
  assert.equal(writes[0].request.span.draftRev, 17);
  assert.equal(writes[0].request.text, '\nshared');
  assert.equal(state.occurrences.length, 1);
  assert.equal(adapter.remaining(handle), 10);
  assert.throws(() => adapter.validate(handle, [new File(['x'], 'image.heic', { type: 'image/heic' })]), /格式/);
  assert.throws(() => adapter.validate(handle, Array(11).fill(new File(['x'], 'image.png', { type: 'image/png' }))), /10/);
});

test('native acknowledgement interruption retries one in-flight adoption instead of duplicating drafts', async () => {
  const f = fixture(), native = f.native;
  let failed = false;
  const controller = createImportController(f.adapter, async args => {
    if (args.operation === 'bind' && args.textApplied && !failed) { failed = true; throw new Error('interrupted'); }
    return native(args);
  }, f.environment);
  await controller.refresh();
  await controller.adopt('batch', 'existing');
  assert.match(controller.getSnapshot().error, /interrupted/);
  f.handle('existing').state.draft = 'user edited the imported text';
  await controller.refresh();
  assert.equal(f.attachments, 1);
  assert.equal(f.handle('existing').state.draft, 'user edited the imported text');
  assert.equal(f.imports[0].textApplied, true);
  controller.dispose(); f.controller.dispose();
});

for (const pauseAt of ['hash', 'prepare']) test(`text-only import survives draft clearing during ${pauseAt}`, async () => {
  const f = fixture([row({ items: [] })]);
  let proceed, started;
  const waiting = new Promise(resolve => { started = resolve; });
  const pause = async () => { started(); await new Promise(resolve => { proceed = resolve; }); };
  const environment = { ...f.environment, async hashText(text) {
    if (pauseAt === 'hash') await pause();
    return f.environment.hashText(text);
  } };
  const controller = createImportController(f.adapter, async args => {
    if (pauseAt === 'prepare' && args.operation === 'bind' && args.textPrepared) await pause();
    return f.native(args);
  }, environment);
  await controller.refresh();
  const adoption = controller.adopt('batch', 'existing');
  await waiting;
  f.handle('existing').state.draft = '';
  f.listeners.get('existing')();
  await settle();
  assert.equal(f.calls.some(call => call.operation === 'complete'), false);
  assert.equal(f.imports.length, 1);
  proceed();
  await adoption;
  await settle();
  assert.equal(f.imports.length, 1, 'unacknowledged text must remain recoverable after adoption fails');
  assert.equal(f.calls.some(call => call.operation === 'complete'), false);
  assert.equal(f.imports[0].textApplied, false);
  assert.equal(f.handle('existing').state.draft, '');
  controller.dispose(); f.controller.dispose();
});

test('a missing saved target keeps import bytes and exposes reopening and explicit discard', async () => {
  const f = fixture([row({ targetSessionId: 'deleted-session' })]);
  const adapter = { ...f.adapter, async acquire() { throw new Error('目标对话已删除'); } };
  const controller = createImportController(adapter, f.native, f.environment);
  await controller.refresh();
  assert.equal(f.imports.length, 1);
  assert.equal(f.calls.some(call => ['removeItems', 'complete'].includes(call.operation)), false);
  assert.equal(controller.pendingRows().length, 1);
  controller.close(); controller.clearError();
  controller.open();
  assert.equal(controller.getSnapshot().dialogId, 'batch');
  assert.match(controller.getSnapshot().error, /已删除/);
  assert.equal(controller.canDiscard('batch'), true);
  assert.equal(f.createCount, 0);
  await controller.discard('batch');
  assert.equal(f.imports.length, 0);
  controller.dispose(); f.controller.dispose();
});

test('catalog hydration restores a saved target once it appears and releases subscriptions', async () => {
  const f = fixture([row({ targetSessionId: 'existing', textApplied: true })]);
  const sessionListeners = new Set(), workspaceListeners = new Set();
  let sessionSnapshot = { phase: 'pending', ids: [], byId: {} };
  let workspaceSnapshot = { phase: 'pending', items: [], archivedSessionIds: [] };
  const ctx = {
    sessions: { list: { getSnapshot: () => sessionSnapshot, subscribe(listener) { sessionListeners.add(listener); return () => sessionListeners.delete(listener); } } },
    workspaces: { list: { getSnapshot: () => workspaceSnapshot, subscribe(listener) { workspaceListeners.add(listener); return () => workspaceListeners.delete(listener); } } },
  };
  const adapter = { ...f.adapter, async acquire(id) {
    if (!sessionSnapshot.ids.includes(id)) throw new Error(`sessions.retain: unknown session ${id}`);
    return f.adapter.acquire(id);
  } };
  const controller = createImportController(adapter, f.native, f.environment);
  let refreshes = 0, lastRefresh;
  const stop = observeMediaCatalogs(ctx, () => { refreshes++; lastRefresh = controller.refresh(); });
  await controller.refresh();
  assert.match(controller.getSnapshot().error, /unknown session/);
  assert.equal(f.attachments, 0);
  sessionSnapshot = { phase: 'ready', ids: ['existing'], byId: { existing: { retainedBy: {} } } };
  for (const listener of sessionListeners) listener();
  await lastRefresh;
  assert.equal(f.attachments, 1);
  assert.equal(f.handle('existing').state.attachmentIds.length, 1);
  assert.equal(controller.getSnapshot().error, null);
  assert.equal(controller.getSnapshot().dialogId, null);
  assert.equal(controller.pendingRows().length, 0);
  sessionSnapshot.byId.existing.retainedBy.mediaInput = 1;
  for (const listener of sessionListeners) listener();
  assert.equal(refreshes, 1, 'adoption retain feedback must not trigger a catalog refresh');
  workspaceSnapshot = { phase: 'ready', items: [{ workspaceId: 'workspace' }], archivedSessionIds: [] };
  for (const listener of workspaceListeners) listener();
  await lastRefresh;
  assert.equal(f.attachments, 1, 'workspace hydration must not duplicate restored bytes');
  stop();
  assert.equal(sessionListeners.size, 0);
  assert.equal(workspaceListeners.size, 0);
  controller.dispose(); f.controller.dispose();
});
