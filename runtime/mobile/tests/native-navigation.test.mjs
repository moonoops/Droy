import test from 'node:test';
import assert from 'node:assert/strict';
import { createRequire, registerHooks } from 'node:module';
import { pathToFileURL } from 'node:url';
import { mkdtemp, mkdir, readFile, readdir, realpath, rm, symlink } from 'node:fs/promises';
import { join } from 'node:path';
import { tmpdir } from 'node:os';
import { CompanionStore } from '../companions/store.mjs';

const installed = createRequire(new URL('../../dsh/package.json', import.meta.url));
const hook = registerHooks({ resolve(specifier, context, next) {
  if (specifier.startsWith('@deepseek-ai/')) return { url: pathToFileURL(installed.resolve(specifier)).href, shortCircuit: true };
  return next(specifier, context);
} });
const { createNativeNavigationService, NativeNavigationError } = await import('../native-navigation.mjs');
hook.deregister();
const loadSdk = name => import(pathToFileURL(installed.resolve(name)).href);
const privateFailure = code => Object.assign(new Error('fictional-private-path-and-provider-key'), { code });
const code = expected => error => error instanceof NativeNavigationError && error.code === 'NATIVE_NAVIGATION_' + expected;
const engine = (sessionId = 'ordinary', extra = {}) => ({ kind: 'engine-session', sessionId, ...extra });
const companion = (sourceSessionId = 'chat', extra = {}) => ({ kind: 'companion-chat', companionId: 'companion-one',
  chatSessionId: 'chat', sourceSessionId, ...extra });

function fixture() {
  const calls = [], headers = new Map(), summaries = new Map(), owners = new Map(), messages = new Map(), profiles = new Map();
  let archived = [], workspaceCloses = 0;
  const spaces = [{ workspaceId: 'space-work', path: '/fixture/work', title: 'Work', sessionIds: [], createdAt: '2030-01-01T00:00:00Z', updatedAt: '2030-01-01T00:00:00Z' },
    { workspaceId: 'space-companion', path: '/fixture/companion', title: 'Companion', sessionIds: [], createdAt: '2030-01-01T00:00:00Z', updatedAt: '2030-01-01T00:00:00Z' }];
  const forbidden = name => () => { throw new Error('unexpected side effect: ' + name); };
  const ctx = {
    settings: { describe: () => [{ ns: 'locale', value: { preference: 'en' } }] },
    sessions: { get: id => { calls.push(['live', id]); return undefined; }, prepare: forbidden('prepare'), create: forbidden('session-create'), enter: forbidden('enter') },
    sessionPersistence: {
      async stat(id, options) { calls.push(['stat', id, options]); return headers.has(id) ? { header: structuredClone(headers.get(id)), formatStatus: 'current' } : undefined; },
      create: forbidden('persist-create'), open: forbidden('persist-open'),
    },
    sessionController: {
      async list(request, signal) { calls.push(['list', request, signal]); return { items: structuredClone([...summaries.values()]) }; },
      async page(request, signal) { calls.push(['page', request, signal]); return { records: [], hasMore: false }; },
      follow: forbidden('session-follow'), resolveAgent: forbidden('resolve-agent'), create: forbidden('controller-create'),
      prompt: forbidden('prompt'), cancel: forbidden('cancel'),
    },
    workspaceController: {
      async *follow(signal) {
        calls.push(['workspaces', signal]);
        try { yield { type: 'baseline', value: { items: structuredClone(spaces), archivedSessionIds: [...archived], pinnedSessionIds: [] } }; }
        finally { workspaceCloses++; }
      },
      create: forbidden('workspace-create'), initializeDefault: forbidden('initialize-default'),
    },
    droyCompanions: {
      directory: '/fixture/companions',
      ownsSession: id => owners.has(id),
      store: {
        async profiles() { calls.push(['profiles']); return structuredClone([...profiles.values()]); },
        async getProfile(id) { calls.push(['profile', id]); if (!profiles.has(id)) throw privateFailure('COMPANION_NOT_FOUND'); return structuredClone(profiles.get(id)); },
        async sessionOwner(id) { calls.push(['owner', id]); return structuredClone(owners.get(id) ?? null); },
        async getMessage(id, taskId) { calls.push(['message', id, taskId]); return structuredClone(messages.get(id + ':' + taskId) ?? null); },
        bindSession: forbidden('bind-session'), updateProfile: forbidden('update-profile'),
      },
      startInstance: forbidden('start-instance'), agent: forbidden('companion-agent'),
    },
    droyNativeDecisions: { answer: forbidden('decision-answer'), snapshot: forbidden('decision-snapshot') },
  };
  function add(id, meta = {}, workspaceId = 'space-work') {
    const space = spaces.find(row => row.workspaceId === workspaceId);
    const header = { version: 4, id, createdAt: 1, isSeeded: false, cwd: space?.path ?? '/fixture/ungrouped', ...meta };
    headers.set(id, header);
    summaries.set(id, { sessionId: id, agentAvailable: false, updatedAt: 1, running: false, blank: true,
      cwd: header.cwd, ...(header.origin === undefined ? {} : { origin: header.origin }),
      ...(header.parentSession === undefined ? {} : { parentSessionId: header.parentSession }) });
    space?.sessionIds.push(id); return header;
  }
  function bind(id, kind, taskId = null, companionId = 'companion-one') {
    add(id, kind === 'chat' ? { cwd: '/fixture/companions/' + companionId + '/chat' } : {},
      kind === 'chat' ? null : 'space-companion');
    const saved = profiles.get(companionId) ?? { id: companionId, workspaceId: 'space-companion', chatSessionId: null };
    if (kind === 'chat') saved.chatSessionId = id;
    profiles.set(companionId, saved);
    owners.set(id, { companionId, sessionId: id, workspaceId: 'space-companion', kind, taskId });
    if (kind === 'work') messages.set(companionId + ':' + taskId, { id: taskId, role: 'assistant', type: 'task', task: { id: taskId, sessionId: id, state: 'completed' } });
  }
  add('ordinary'); add('fork', { parentSession: 'ordinary', isSeeded: true });
  add('child', { parentSession: 'ordinary', origin: 'subagent', delegationDepth: 1 });
  bind('chat', 'chat'); bind('work', 'work', 'task-one');
  return { ctx, calls, headers, summaries, spaces, profiles, owners, messages, add, bind,
    service: createNativeNavigationService(ctx), archive: (...ids) => { archived = ids; }, get workspaceCloses() { return workspaceCloses; } };
}

test('ordinary, archived, ungrouped and ordinary fork targets carry explicit addresses without activation', async () => {
  const f = fixture();
  assert.deepEqual(await f.service.resolve(engine()), { kind: 'engine-session', session: {
    id: 'ordinary', workspaceId: 'space-work', owner: 'workbench', readOnly: false, archived: false, address: { kind: 'session', sessionId: 'ordinary' },
  } });
  f.archive('ordinary');
  assert.equal((await f.service.resolve(engine())).session.readOnly, true);
  assert.equal((await f.service.resolve(engine())).session.archived, true);
  const fork = await f.service.resolve(engine('fork'));
  assert.equal(fork.session.owner, 'workbench'); assert.equal(fork.session.readOnly, false);
  assert.deepEqual(fork.session.address, { kind: 'session', sessionId: 'fork' });
  f.add('ungrouped', {}, null);
  assert.equal((await f.service.resolve(engine('ungrouped'))).session.workspaceId, null);
  assert.equal(f.calls.filter(row => row[0] === 'page').length, 0);
  assert.equal(f.workspaceCloses, 5);
});

test('subagent proof uses the public non-activating page validator and returns unknown-mode typed address', async () => {
  const f = fixture(), abort = new AbortController();
  const resolved = await f.service.resolve(engine('child'), { signal: abort.signal });
  assert.equal(resolved.session.owner, 'subagent'); assert.equal(resolved.session.readOnly, true);
  const address = { kind: 'subagent', parentSessionId: 'ordinary', childSessionId: 'child', mode: 'unknown' };
  assert.deepEqual(resolved.session.address, address);
  assert.deepEqual(f.calls.find(row => row[0] === 'page'), ['page', { address, throughSeq: -1, maxMessages: 1 }, abort.signal]);
});

test('missing or unproven child facts never fall back to an ordinary writable session', async () => {
  for (const mutate of [
    f => { delete f.headers.get('child').parentSession; delete f.summaries.get('child').parentSessionId; },
    f => { f.headers.get('child').parentSession = 'child'; f.summaries.get('child').parentSessionId = 'child'; },
    f => { f.headers.delete('ordinary'); f.summaries.delete('ordinary'); },
    f => { f.ctx.sessionController.page = async () => { throw privateFailure('subagent/catalog-diagnostic'); }; },
    f => { delete f.headers.get('child').origin; delete f.summaries.get('child').origin; },
  ]) {
    const f = fixture(); mutate(f);
    await assert.rejects(f.service.resolve(engine('child')), error => error instanceof NativeNavigationError);
    assert.equal(f.workspaceCloses, 1);
  }
});

test('companion chat can remain truly empty, and resolving an existing chat never starts an instance', async () => {
  const f = fixture();
  f.profiles.set('empty', { id: 'empty', workspaceId: null, chatSessionId: null });
  assert.deepEqual(await f.service.resolve({ kind: 'companion-chat', companionId: 'empty' }),
    { kind: 'companion-chat', companionId: 'empty', chatSessionId: null });
  assert.deepEqual(f.calls, [['profile', 'empty']]); f.calls.length = 0;
  assert.deepEqual(await f.service.resolve({ kind: 'companion-chat', companionId: 'companion-one' }),
    companion());
  assert.equal(f.workspaceCloses, 1);
  await assert.rejects(f.service.resolve({ kind: 'companion-chat', companionId: 'missing' }), code('NOT_FOUND'));
});

test('private companion chat opens without work workspace membership and retains ownership proof', async () => {
  const f = fixture();
  assert.ok(f.spaces.every(space => !space.sessionIds.includes('chat')));
  assert.deepEqual(await f.service.resolve(engine('chat')),
    companion());
  for (const mutate of [
    f => { f.headers.get('chat').cwd = '/fixture/companions/another/chat'; f.summaries.get('chat').cwd = f.headers.get('chat').cwd; },
    f => { f.headers.get('chat').cwd = '/fixture/companions/companion-one/work'; f.summaries.get('chat').cwd = f.headers.get('chat').cwd; },
    f => { f.spaces[0].sessionIds.push('chat'); },
    f => { f.profiles.get('companion-one').workspaceId = 'space-work'; },
    f => { f.owners.get('chat').taskId = 'unexpected-task'; },
  ]) {
    const invalid = fixture(); mutate(invalid);
    await assert.rejects(invalid.service.resolve({ kind: 'companion-chat', companionId: 'companion-one' }), code('TARGET_MISMATCH'));
  }
  delete f.ctx.droyCompanions.directory;
  await assert.rejects(f.service.resolve(engine('chat')), code('NOT_READY'));
});

test('actual companion tasks are resolved from ownership and the task message, including engine-only inputs', async () => {
  const f = fixture();
  const target = { kind: 'companion-task', companionId: 'companion-one', taskId: 'task-one', sessionId: 'work' };
  const resolved = await f.service.resolve(target);
  assert.deepEqual(resolved, companion('work', { sourceTaskId: 'task-one' }));
  assert.deepEqual(await f.service.resolve(engine('work')), resolved);
  for (const input of [{ ...target, companionId: 'another' }, { ...target, taskId: 'another' }, { ...target, sessionId: 'ordinary' }, { ...target, sessionId: 'chat' }])
    await assert.rejects(f.service.resolve(input), code('TARGET_MISMATCH'));
  f.messages.get('companion-one:task-one').task.sessionId = 'another';
  await assert.rejects(f.service.resolve(target), code('TARGET_MISMATCH'));
});

test('pet request correlators stay on exact verified targets and never become native decisions', async () => {
  const f = fixture(), petRequestId = 'pet-request-one';
  for (const sessionId of ['ordinary', 'child']) {
    const resolved = await f.service.resolve(engine(sessionId, { petRequestId }));
    assert.equal(resolved.petRequestId, petRequestId); assert.equal(resolved.session.id, sessionId);
    assert.equal(Object.hasOwn(resolved, 'decisionId'), false);
  }
  const chat = await f.service.resolve(engine('chat', { petRequestId }));
  assert.deepEqual(chat, companion('chat', { petRequestId }));
  assert.deepEqual(await f.service.resolve(engine('work', { petRequestId })), companion('work', { sourceTaskId: 'task-one', petRequestId }));
});

test('archived and superseded companion links return to the companion without selecting private history in Tasks', async () => {
  const f = fixture(); f.bind('old-chat', 'chat');
  f.profiles.get('companion-one').chatSessionId = 'chat';
  const old = await f.service.resolve(engine('old-chat'));
  assert.deepEqual(old, companion('old-chat'));
  f.archive('chat');
  const archived = await f.service.resolve({ kind: 'companion-chat', companionId: 'companion-one' });
  assert.deepEqual(archived, companion());
});

test('managed workspace membership alone cannot create an ordinary writable entry', async () => {
  const f = fixture(); f.add('unbound-in-managed-space', {}, 'space-companion');
  const resolved = await f.service.resolve(engine('unbound-in-managed-space'));
  assert.equal(resolved.session.owner, 'workbench'); assert.equal(resolved.session.readOnly, true);
  f.ctx.droyCompanions.ownsSession = id => id === 'ordinary';
  await assert.rejects(f.service.resolve(engine()), code('NOT_READY'));
});

test('owner/profile/workspace disagreements, absent task messages and unavailable store seams fail closed', async () => {
  for (const mutate of [
    f => { f.owners.get('work').sessionId = 'another'; },
    f => { f.profiles.get('companion-one').workspaceId = 'space-work'; },
    f => { f.spaces[1].sessionIds = []; },
    f => { f.owners.get('work').kind = 'unrecognized'; },
    f => { delete f.ctx.droyCompanions.store.sessionOwner; },
    f => { delete f.ctx.droyCompanions.store.getProfile; },
    f => { f.ctx.droyCompanions.store.profiles = async () => undefined; },
    f => { f.messages.delete('companion-one:task-one'); },
  ]) {
    const f = fixture(); mutate(f);
    await assert.rejects(f.service.resolve(engine('work')), error => error instanceof NativeNavigationError);
  }
});

test('unknown targets, denied list entries, migration and conflicting live headers have separate safe errors', async () => {
  const f = fixture();
  await assert.rejects(f.service.resolve(engine('missing')), code('NOT_FOUND'));
  f.summaries.delete('ordinary'); await assert.rejects(f.service.resolve(engine()), code('ACCESS_DENIED'));
  f.summaries.set('ordinary', { sessionId: 'ordinary', agentAvailable: false, updatedAt: 1, running: false, blank: true, formatStatus: 'migration-required' });
  await assert.rejects(f.service.resolve(engine()), code('MIGRATION_REQUIRED'));
  delete f.summaries.get('ordinary').formatStatus;
  f.ctx.sessions.get = id => id === 'ordinary' ? { header: { ...f.headers.get(id), cwd: '/fixture/conflicting' } } : undefined;
  await assert.rejects(f.service.resolve(engine()), code('TARGET_MISMATCH'));
});

test('malformed and ambiguous inputs fail before any provider read', async () => {
  const f = fixture();
  for (const input of [null, [], {}, engine('../escape'), engine('x'.repeat(257)), engine('ordinary', { decisionId: 'pet' }),
    engine('ordinary', { companionId: 'companion-one' }), engine('ordinary', { petRequestId: null }),
    { kind: 'companion-chat', companionId: 'x'.repeat(161) }, { kind: 'companion-task', sessionId: 'work', companionId: 'companion-one' },
    { kind: 'constructor' }, { kind: 'toString' }, { kind: 'browser-tab', browserSessionId: 'browser' }])
    await assert.rejects(f.service.resolve(input), code('BAD_REQUEST'));
  await assert.rejects(f.service.resolve(engine(), { signal: {} }), code('BAD_REQUEST'));
  assert.deepEqual(f.calls, []);
});

test('invalid catalogs are not replaced with fabricated baselines, and baseline subscriptions always close', async () => {
  for (const mutate of [
    f => { f.spaces[1].sessionIds.push('ordinary'); },
    f => { f.ctx.sessionController.list = async () => ({ items: [] , unexpected: true }); f.ctx.sessionPersistence.stat = async () => { throw privateFailure('disk-read-failed'); }; },
    f => { f.ctx.sessionController.list = async () => ({ items: [{ sessionId: 'ordinary' }] }); },
    f => { f.ctx.workspaceController.follow = async function* () { try { yield { type: 'unknown' }; } finally { f.calls.push(['closed-invalid']); } }; },
  ]) {
    const f = fixture(); mutate(f);
    await assert.rejects(f.service.resolve(engine()), error => error instanceof NativeNavigationError);
    assert.ok(f.workspaceCloses === 1 || f.calls.some(row => row[0] === 'closed-invalid'));
  }
});

test('pre-cancelled and mid-read cancelled resolutions do not activate anything or expose cancellation details', async () => {
  const f = fixture(), controller = new AbortController(); controller.abort(privateFailure('private-cancel'));
  await assert.rejects(f.service.resolve(engine(), { signal: controller.signal }), code('CANCELLED'));
  assert.deepEqual(f.calls, []);
  const mid = new AbortController();
  f.ctx.sessionPersistence.stat = async () => { mid.abort(privateFailure('private-cancel')); throw mid.signal.reason; };
  await assert.rejects(f.service.resolve(engine(), { signal: mid.signal }), error => code('CANCELLED')(error) && !error.message.includes('private'));
  assert.equal(f.workspaceCloses, 1);
});

test('a cancellation at the last companion read and a concurrent chat replacement cannot return a stale ready target', async () => {
  const f = fixture(), abort = new AbortController(), original = f.ctx.droyCompanions.store.getProfile;
  let reads = 0;
  f.ctx.droyCompanions.store.getProfile = async id => {
    const saved = await original(id); if (++reads === 2) abort.abort(privateFailure('late-cancel')); return saved;
  };
  await assert.rejects(f.service.resolve({ kind: 'companion-chat', companionId: 'companion-one' }, { signal: abort.signal }), code('CANCELLED'));
  reads = 0;
  f.ctx.droyCompanions.store.getProfile = async id => {
    const saved = await original(id); if (++reads === 2) saved.chatSessionId = null; return saved;
  };
  await assert.rejects(f.service.resolve({ kind: 'companion-chat', companionId: 'companion-one' }), code('TARGET_MISMATCH'));
});

test('public provider refusal types retain safe missing, denied, unsupported and waiting distinctions', async () => {
  const f = fixture();
  for (const [failure, expected] of [
    [privateFailure('subagent/unauthorized'), 'ACCESS_DENIED'],
    [privateFailure('subagent/not-found'), 'NOT_FOUND'],
    [privateFailure('subagent/catalog-diagnostic'), 'SUBAGENT_UNPROVEN'],
    [privateFailure('session/migration-required'), 'MIGRATION_REQUIRED'],
    [Object.assign(privateFailure(), { name: 'SessionFormatUnsupportedError' }), 'FORMAT_UNSUPPORTED'],
    [privateFailure('COMPANION_STORE_CLOSED'), 'NOT_READY'],
    [privateFailure('SESSION_QUERY_ABORTED'), 'CANCELLED'],
  ]) {
    f.ctx.sessionController.page = async () => { throw failure; };
    await assert.rejects(f.service.resolve(engine('child')), error => code(expected)(error) && !error.message.includes('private'));
  }
});

test('provider failures produce paired safe product text and no original error or path', async () => {
  const f = fixture(); f.ctx.sessionPersistence.stat = async () => { throw privateFailure('unknown-private-code'); };
  for (const [locale, expected] of [['en', 'This content could not be opened. Try again.'], ['zh', '暂时无法打开，请重试。']]) {
    f.ctx.settings.describe = () => [{ ns: 'locale', value: { preference: locale } }];
    await assert.rejects(f.service.resolve(engine()), error => {
      assert.equal(error.code, 'NATIVE_NAVIGATION_FAILED'); assert.equal(error.status, 500); assert.equal(error.message, expected);
      assert.equal(error.cause, undefined); assert.ok(!JSON.stringify(error).includes('private')); return true;
    });
  }
});

async function realFixture(t) {
  const [{ Context }, { default: Storage }, JsonStorage, Domains, { WorkspaceRegistry }, { WorkspaceController },
    { SessionController }, { default: Sessions }, Jsonl, { default: Projections }, { SessionQueryEngine }, { default: Titles }, { SubagentRuntime }] = await Promise.all([
    loadSdk('@deepseek-ai/cordis'), loadSdk('@deepseek-ai/dsh-storage'), loadSdk('@deepseek-ai/dsh-storage-json'),
    loadSdk('@deepseek-ai/dsh-storage-domain'), loadSdk('@deepseek-ai/dsh-workspace'), loadSdk('@deepseek-ai/dsh-api-workspace-controller'),
    loadSdk('@deepseek-ai/dsh-api-session-controller'), loadSdk('@deepseek-ai/dsh-session'), loadSdk('@deepseek-ai/dsh-session-persistence-jsonl'),
    loadSdk('@deepseek-ai/dsh-session-projection'), loadSdk('@deepseek-ai/dsh-session-query'), loadSdk('@deepseek-ai/dsh-session-title'), loadSdk('@deepseek-ai/dsh-subagent'),
  ]);
  const home = await mkdtemp(join(tmpdir(), 'droy-navigation-sdk-')), ctx = new Context();
  const store = await CompanionStore.open({ directory: join(home, 'companions') });
  t.after(async () => { await ctx.fiber.dispose(); await store.close(); await rm(home, { recursive: true, force: true }); });
  const unexpected = () => { throw new Error('resolver attempted agent creation or resume'); };
  ctx.provide('remoteAccess', { allows: () => true });
  ctx.provide('typert', { resources: { register() {} }, lookups: { configure() {} }, contexts: { configureHost() {} } });
  ctx.provide('fileUploads', { registerAgentResolver: () => () => {} });
  ctx.provide('agentDefaultModel', { currentSelection: () => ({ provider: 'fictional', model: 'fixture' }) });
  ctx.provide('droyCompanions', { store, directory: join(home, 'companions'), ownsSession: () => false });
  ctx.provide('workingDirectory', {});
  await ctx.plugin(Sessions); await ctx.plugin(Projections); await ctx.plugin(Jsonl, { root: join(home, 'sessions'), compression: 'none' });
  await ctx.plugin(SessionQueryEngine); await ctx.plugin(Titles, { fallbackMaxWords: 8, fallbackMaxBytes: 120, maxTitleBytes: 256 });
  await ctx.plugin(SubagentRuntime);
  await ctx.plugin(Storage); await ctx.plugin(JsonStorage, { root: join(home, 'workspace-state') }); await ctx.plugin(Domains, { backend: 'json' });
  await ctx.plugin(WorkspaceRegistry); await ctx.plugin(WorkspaceController);
  // No Agent provider is mounted: public cold listing/page and CompanionStore
  // must suffice, so promotion would fail rather than secretly start a model.
  ctx.provide('agents', { get: () => undefined, list: () => [], create: unexpected, resume: unexpected });
  new SessionController(ctx, { nativeOpen: false });
  await mkdir(join(home, 'work')); const { workspace } = await ctx.workspaceController.create({ path: join(home, 'work') });
  async function save(id, { meta = {}, seed, inheritedEventCount, descriptor, attach = true } = {}) {
    const session = ctx.sessions.prepare(id, { meta: { cwd: workspace.path, ...(inheritedEventCount ? { isSeeded: true } : {}), ...meta }, ...(seed === undefined ? {} : { seed }),
      ...(inheritedEventCount === undefined ? {} : { inheritedEventCount }) });
    if (descriptor) session.append('subagent/descriptor', descriptor);
    const handle = await ctx.sessionPersistence.create(session.header, { inheritedEventCount: session.inheritedEventCount });
    try { await handle.append(session.snapshotEvents()); await handle.flush(); } finally { await handle.close(); }
    if (attach) await ctx.workspaceRegistry.get(workspace.workspaceId).attachSession(id);
    return session;
  }
  const adapter = createNativeNavigationService(ctx);
  return { home, ctx, store, workspace, save, adapter };
}

test('real SDK cold child proof rejects absent/inherited descriptors, preserves ordinary forks and never publishes sessions', async t => {
  const f = await realFixture(t);
  await f.save('parent');
  const healthy = await f.save('real-child', { meta: { origin: 'subagent', parentSession: 'parent', delegationDepth: 1 },
    descriptor: { version: 3, mode: 'one-shot', provider: 'fixture', label: 'Fixture child' } });
  await f.save('real-fork', { meta: { parentSession: 'real-child' }, seed: healthy.snapshotEvents(), inheritedEventCount: healthy.snapshotEvents().length });
  await f.save('no-descriptor', { meta: { origin: 'subagent', parentSession: 'parent', delegationDepth: 1 } });
  await f.save('inherited-descriptor', { meta: { origin: 'subagent', parentSession: 'real-child', delegationDepth: 2 },
    seed: healthy.snapshotEvents(), inheritedEventCount: healthy.snapshotEvents().length });
  const liveBefore = f.ctx.sessions.list().map(row => row.id), directoryBefore = await tree(f.home);
  const child = await f.adapter.resolve(engine('real-child'));
  assert.deepEqual(child.session.address, { kind: 'subagent', parentSessionId: 'parent', childSessionId: 'real-child', mode: 'unknown' });
  assert.equal(child.session.readOnly, true);
  const fork = await f.adapter.resolve(engine('real-fork')); assert.equal(fork.session.owner, 'workbench'); assert.equal(fork.session.readOnly, false);
  for (const id of ['no-descriptor', 'inherited-descriptor']) await assert.rejects(f.adapter.resolve(engine(id)), code('SUBAGENT_UNPROVEN'));
  await assert.rejects(f.adapter.resolve(engine('never-existed')), code('NOT_FOUND'));
  assert.deepEqual(f.ctx.sessions.list().map(row => row.id), liveBefore);
  assert.deepEqual(await tree(f.home), directoryBefore);
});

test('default zero depth is compatible in either source while a different depth is rejected', async () => {
  for (const [liveDepth, storedDepth] of [[undefined, 0], [0, undefined], [1, 0], [0, 1]]) {
    const f = fixture(), original = f.ctx.sessionPersistence.stat;
    f.ctx.sessions.get = id => ({ header: { ...f.headers.get(id), delegationDepth: liveDepth } });
    f.ctx.sessionPersistence.stat = async (...args) => {
      const value = await original(...args); value.header.delegationDepth = storedDepth; return value;
    };
    if ((liveDepth ?? 0) === (storedDepth ?? 0)) assert.equal((await f.service.resolve(engine())).session.id, 'ordinary');
    else await assert.rejects(f.service.resolve(engine()), code('TARGET_MISMATCH'));
  }
});

test('real private chat accepts a canonical base alias and rejects a symlink escaping its companion directory', async t => {
  const f = await realFixture(t), profile = (await f.store.profiles())[0];
  const directory = join(f.home, 'companions'), chat = join(directory, profile.id, 'chat'), alias = join(f.home, 'companion-alias');
  await mkdir(chat, { recursive: true });
  await symlink(directory, alias, 'dir');
  f.ctx.droyCompanions.directory = alias;
  await f.save('private-chat-alias', { meta: { cwd: await realpath(chat) }, attach: false });
  await f.store.bindSession(profile.id, { sessionId: 'private-chat-alias', kind: 'chat', workspaceId: f.workspace.workspaceId });
  assert.deepEqual(await f.adapter.resolve({ kind: 'companion-chat', companionId: profile.id }),
    { kind: 'companion-chat', companionId: profile.id, chatSessionId: 'private-chat-alias', sourceSessionId: 'private-chat-alias' });
  const outside = join(f.home, 'another-chat');
  await mkdir(outside); await rm(chat, { recursive: true }); await symlink(outside, chat, 'dir');
  await assert.rejects(f.adapter.resolve({ kind: 'companion-chat', companionId: profile.id }), code('TARGET_MISMATCH'));
});

test('a real live chat and its normalized JSONL header identify the same ordinary session', async t => {
  const f = await realFixture(t), profile = (await f.store.profiles())[0];
  const directory = join(f.home, 'companions', profile.id, 'chat');
  await mkdir(directory, { recursive: true });
  const live = await f.save('live-companion-chat', { meta: { cwd: directory }, attach: false });
  await f.store.bindSession(profile.id, { sessionId: live.id, kind: 'chat', workspaceId: f.workspace.workspaceId });
  assert.equal(live.header.delegationDepth, undefined);
  assert.equal((await f.ctx.sessionPersistence.stat(live.id)).header.delegationDepth, 0);
  const detach = f.ctx.sessions.enter(live);
  try {
    assert.deepEqual(await f.adapter.resolve({ kind: 'companion-chat', companionId: profile.id }),
      { kind: 'companion-chat', companionId: profile.id, chatSessionId: live.id, sourceSessionId: live.id });
    assert.deepEqual(await f.adapter.resolve(engine(live.id)),
      { kind: 'companion-chat', companionId: profile.id, chatSessionId: live.id, sourceSessionId: live.id });
    assert.equal(f.ctx.sessions.get(live.id), live);
  } finally { detach(); }
});

test('real SQLite companion bindings and task messages resolve without changing profiles, messages or session persistence', async t => {
  const f = await realFixture(t), profile = (await f.store.profiles())[0];
  const chatDirectory = join(f.home, 'companions', profile.id, 'chat');
  await mkdir(chatDirectory, { recursive: true });
  await f.save('companion-chat', { meta: { cwd: chatDirectory }, attach: false }); await f.save('companion-work');
  await f.store.bindSession(profile.id, { sessionId: 'companion-chat', kind: 'chat', workspaceId: f.workspace.workspaceId });
  await f.store.bindSession(profile.id, { sessionId: 'companion-work', kind: 'work', workspaceId: f.workspace.workspaceId, taskId: 'real-task' });
  await f.store.appendMessage(profile.id, { id: 'real-task', role: 'assistant', type: 'task', text: 'Synthetic task',
    task: { id: 'real-task', sessionId: 'companion-work', state: 'completed', title: 'Synthetic task' } });
  const before = { profiles: await f.store.profiles(), messages: await f.store.listMessages(profile.id), sessions: await f.store.listSessions(profile.id) };
  const persistedBefore = await tree(join(f.home, 'sessions'));
  const chat = await f.adapter.resolve({ kind: 'companion-chat', companionId: profile.id });
  assert.deepEqual(chat, { kind: 'companion-chat', companionId: profile.id, chatSessionId: 'companion-chat', sourceSessionId: 'companion-chat' });
  const task = await f.adapter.resolve({ kind: 'companion-task', companionId: profile.id, taskId: 'real-task', sessionId: 'companion-work' });
  assert.deepEqual(task, { ...chat, sourceSessionId: 'companion-work', sourceTaskId: 'real-task' });
  await f.ctx.workspaceController.archiveSession({ sessionId: 'companion-work' });
  assert.deepEqual(await f.adapter.resolve(engine('companion-work')), task);
  assert.deepEqual({ profiles: await f.store.profiles(), messages: await f.store.listMessages(profile.id), sessions: await f.store.listSessions(profile.id) }, before);
  assert.deepEqual(await tree(join(f.home, 'sessions')), persistedBefore);
  assert.deepEqual(f.ctx.sessions.list(), []);
});

async function tree(root, prefix = '') {
  const result = [];
  for (const item of (await readdir(join(root, prefix), { withFileTypes: true })).sort((a, b) => a.name.localeCompare(b.name))) {
    const name = join(prefix, item.name);
    if (item.isDirectory()) result.push(...await tree(root, name));
    else if (!name.includes('companions.sqlite')) result.push([name, (await readFile(join(root, name))).toString('base64')]);
  }
  return result;
}
