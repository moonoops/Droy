import test from 'node:test';
import assert from 'node:assert/strict';
import { createServer } from 'node:http';
import { once } from 'node:events';
import { createRequire, registerHooks } from 'node:module';
import { pathToFileURL } from 'node:url';
import { randomUUID } from 'node:crypto';
import { mkdtemp, mkdir, readFile, readdir, realpath, rm, symlink, writeFile } from 'node:fs/promises';
import { join } from 'node:path';
import { tmpdir } from 'node:os';
const installed = createRequire(new URL('../../dsh/package.json', import.meta.url));
const hook = registerHooks({ resolve(specifier, context, next) {
  if (specifier.startsWith('@deepseek-ai/')) return { url: pathToFileURL(installed.resolve(specifier)).href, shortCircuit: true };
  return next(specifier, context);
} });
const { createNativeWorkspaces } = await import('../native-workspaces.mjs');
hook.deregister();
const loadSdk = name => import(pathToFileURL(installed.resolve(name)).href);
const fail = code => Object.assign(new Error('private-provider-secret'), { code });
async function fixture(t, options = {}) {
  const home = await mkdtemp(join(tmpdir(), 'droy-workspaces-test-'));
  await mkdir(join(home, 'ordinary')); await mkdir(join(home, 'companion'));
  await mkdir(join(home, 'unregistered')); await writeFile(join(home, 'ordinary', 'file.txt'), 'fictional file');
  await writeFile(join(home, 'history.jsonl'), 'fictional preserved transcript\n');
  const calls = [], history = new Map([['work-a', 'fictional transcript'], ['child-a', 'fictional transcript']]);
  const now = '2030-01-01T00:00:00Z';
  let spaces = [
    { workspaceId: 'space-a', path: join(home, 'ordinary'), title: 'Renamed default', sessionIds: ['work-a', 'child-a'], createdAt: now, updatedAt: now },
    { workspaceId: 'companion-space', path: join(home, 'companion'), title: 'Companion', sessionIds: ['companion-a'], createdAt: now, updatedAt: now },
  ];
  let archived = [], pinned = [], forkCount = 0;
  const rows = new Map(['work-a', 'companion-a', 'child-a'].map(sessionId => [sessionId, {
    sessionId, agentAvailable: false, formatStatus: 'current', updatedAt: 1, running: false, blank: false,
    ...(sessionId === 'child-a' ? { origin: 'subagent', parentSessionId: 'work-a' } : {}),
    projections: { kind: 'cached', asOfSeq: 1, values: { title: 'Work (3)' } },
  }]));
  const ctx = {
    settings: { describe: () => [{ ns: 'locale', value: { preference: options.locale ?? 'en' } }] },
    droyInitialization: { defaultWorkspaceId: 'space-a' },
    droyCompanions: { ownsSession: id => id === 'companion-a', store: { profiles: async () => [{ workspaceId: 'companion-space' }] } },
    sessionPersistence: { stat: async id => rows.has(id) ? { formatStatus: rows.get(id).formatStatus,
      header: { id, cwd: spaces[0]?.path, ...(id === 'child-a' ? { delegationDepth: 1 } : {}) } } : undefined },
    sessions: { get: id => rows.has(id) ? { id } : undefined, flush: async () => true },
    directoryPicker: { capability: () => ({ kind: 'browse' }) },
    directoryPickerController: {
      list: async path => ({ path: path ?? home, home, crumbs: [{ name: '/', path: home, hidden: false }],
        entries: [{ name: 'ordinary', path: join(home, 'ordinary'), hidden: false }], truncated: false }),
      createDirectory: async (path, name) => { calls.push(['directory.create', path, name]); const result = join(path, name); await mkdir(result); return result; },
    },
    sessionController: {
      list: async () => ({ items: [...rows.values()] }),
      fork: async args => {
        calls.push(['fork', args]); forkCount++;
        if (rows.get(args.sessionId).formatStatus === 'migration-required' && !args.allowMigration) throw fail('session/migration-required');
        if (options.forkAction) return options.forkAction(args);
        const sessionId = 'fork-' + forkCount; rows.set(sessionId, { ...rows.get(args.sessionId), sessionId, parentSessionId: args.sessionId });
        history.set(sessionId, history.get(args.sessionId)); return { sessionId };
      },
      rename: async args => {
        calls.push(['session.rename', args]);
        if (options.renameFailure) throw fail('session/title-invalid');
        rows.get(args.sessionId).projections.values.title = args.title.trim(); return { title: args.title.trim(), seq: 7 };
      },
    },
    workspaceController: {
      async *follow() { yield { type: 'baseline', value: { items: structuredClone(spaces), archivedSessionIds: [...archived], pinnedSessionIds: [...pinned] } }; },
      create: async args => {
        calls.push(['create', args]);
        const existing = spaces.find(row => row.path === args.path);
        if (existing) return { workspace: existing, created: false };
        const row = { workspaceId: 'new-space', path: args.path, title: 'unregistered', sessionIds: [], createdAt: now, updatedAt: now };
        spaces.unshift(row); return { workspace: row, created: true };
      },
      rename: async args => {
        calls.push(['workspace.rename', args]); const row = spaces.find(row => row.workspaceId === args.workspaceId);
        if (spaces.some(row => row.workspaceId !== args.workspaceId && row.title === args.title.trim())) throw fail('workspace/name-conflict');
        row.title = args.title.trim(); return { workspace: row };
      },
      delete: async args => { calls.push(['delete', args]); spaces = spaces.filter(row => row.workspaceId !== args.workspaceId); return { deleted: true }; },
      insertBefore: async args => { calls.push(['workspace.move', args]); return { workspaceIds: spaces.map(row => row.workspaceId) }; },
      insertSessionBefore: async args => { calls.push(['session.move', args]); return { workspace: spaces.find(row => row.workspaceId === args.workspaceId) }; },
      pinSession: async args => { calls.push(['pin', args]); pinned = [args.sessionId, ...pinned.filter(id => id !== args.sessionId)]; return { pinnedSessionIds: pinned }; },
      unpinSession: async args => { pinned = pinned.filter(id => id !== args.sessionId); return { pinnedSessionIds: pinned }; },
      archiveSession: async args => {
        calls.push(['archive', args]); if (options.active && !args.stopActivity) throw fail('workspace/session-active');
        archived = [...new Set([...archived, args.sessionId])]; return { archivedSessionIds: archived };
      },
      unarchiveSession: async args => { archived = archived.filter(id => id !== args.sessionId); return { archivedSessionIds: archived }; },
    },
  };
  let adapter = createNativeWorkspaces(ctx, { operationDirectory: join(home, 'receipts'), workspaceDirectory: join(home, 'user-spaces') });
  const controller = new AbortController();
  const server = createServer((req, res) => adapter.handle(req, res, controller.signal));
  server.listen(0, '127.0.0.1'); await once(server, 'listening');
  t.after(async () => { await adapter.dispose(); server.closeAllConnections(); server.close(); await rm(home, { recursive: true, force: true }); });
  return { ctx, home, calls, rows, history,
    restart: async () => { await adapter.dispose(); adapter = createNativeWorkspaces(ctx, { operationDirectory: join(home, 'receipts'), workspaceDirectory: join(home, 'user-spaces') }); },
    async request(path = '', body) {
      const response = await fetch(`http://127.0.0.1:${server.address().port}${'/droy/native/workspaces'}${path}`,
        body === undefined ? {} : { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) });
      const value = await response.json(); assert.ok(!JSON.stringify(value).includes('private-provider-secret'));
      return { status: response.status, value, result: value.result };
    },
  };
}
const op = (kind, args, requestId = randomUUID()) => ({ requestId, kind, args });
test('catalog preserves real global order, pins, stable default ID and cold session projections', async t => {
  const f = await fixture(t);
  await f.request('/mutate', op('session.pin', { sessionId: 'work-a' }));
  const { result } = await f.request();
  assert.deepEqual(result.items.map(row => row.workspaceId), ['space-a', 'companion-space']);
  assert.equal(result.defaultWorkspaceId, 'space-a'); assert.equal(result.workspaces[0].title, 'Renamed default');
  assert.deepEqual(result.pinnedSessionIds, ['work-a']); assert.equal(result.workspaces[1].readOnly, true);
  assert.equal(result.sessions.find(row => row.id === 'child-a').readOnly, true); assert.equal(result.directoryBrowsingAvailable, true);
});
test('managed sessions/workspaces, subagents and symlinked managed directories reject server-side', async t => {
  const f = await fixture(t); await symlink(join(f.home, 'companion'), join(f.home, 'alias'));
  for (const request of [op('session.rename', { sessionId: 'companion-a', title: 'X' }), op('session.pin', { sessionId: 'child-a' }),
    op('workspace.remove', { workspaceId: 'companion-space' }), op('workspace.move', { workspaceId: 'space-a', beforeWorkspaceId: 'companion-space' }),
    op('workspace.register', { path: join(f.home, 'alias') }), op('directory.create', { path: join(f.home, 'alias'), name: 'new' })])
    assert.equal((await f.request('/mutate', request)).status, 409);
  assert.deepEqual(f.calls, []);
});
test('archive/restore and workspace removal retain logs/files and expose former sessions as ungrouped', async t => {
  const f = await fixture(t);
  assert.equal((await f.request('/mutate', op('session.archive', { sessionId: 'work-a' }))).result.state, 'complete');
  assert.deepEqual((await f.request()).result.archivedSessionIds, ['work-a']);
  await f.request('/mutate', op('session.restore', { sessionId: 'work-a' }));
  await f.request('/mutate', op('workspace.remove', { workspaceId: 'space-a' }));
  const catalog = (await f.request()).result;
  assert.equal(catalog.sessions.find(row => row.id === 'work-a').workspaceId, null);
  assert.equal(f.history.get('work-a'), 'fictional transcript');
  assert.equal(await readFile(join(f.home, 'ordinary', 'file.txt'), 'utf8'), 'fictional file');
  assert.equal(await readFile(join(f.home, 'history.jsonl'), 'utf8'), 'fictional preserved transcript\n');
  assert.deepEqual(catalog.archivedSessionIds, []);
});
test('active archive requires an explicit distinct stop request and does not claim stop completion', async t => {
  const f = await fixture(t, { active: true });
  const first = op('session.archive', { sessionId: 'work-a', stopActivity: false });
  assert.equal((await f.request('/mutate', first)).result.errorCode, 'workspace/session-active');
  assert.deepEqual((await f.request()).result.archivedSessionIds, []);
  assert.equal((await f.request('/mutate', op('session.archive', { sessionId: 'work-a', stopActivity: true }))).result.state, 'complete');
  assert.equal(f.calls.filter(row => row[0] === 'archive').length, 2);
});
test('fork migration preflight never silently permits migration; successful fork follows actual title increment', async t => {
  const f = await fixture(t); f.rows.get('work-a').formatStatus = 'migration-required';
  assert.equal((await f.request('/mutate', op('session.fork', { sessionId: 'work-a', allowMigration: false }))).result.errorCode, 'session/migration-required');
  assert.equal(f.calls.filter(row => row[0] === 'session.rename').length, 0);
  const accepted = await f.request('/mutate', op('session.fork', { sessionId: 'work-a', allowMigration: true }));
  assert.equal(accepted.result.title, 'Work (4)'); assert.ok(accepted.result.resultSessionId);
  assert.equal(f.history.get(accepted.result.resultSessionId), 'fictional transcript');
  // A fork lineage is not a subagent origin and remains editable.
  assert.equal((await f.request()).result.sessions.find(row => row.id === accepted.result.resultSessionId).readOnly, false);
});
test('known child receipt survives title failure/restart and repeated requests never fork again', async t => {
  const f = await fixture(t, { renameFailure: true }), request = op('session.fork', { sessionId: 'work-a', allowMigration: false });
  const first = (await f.request('/mutate', request)).result;
  assert.equal(first.state, 'complete'); assert.equal(first.renameUnconfirmed, true); assert.ok(first.resultSessionId);
  await f.restart(); assert.deepEqual((await f.request('/operations/status', request)).result, first);
  assert.deepEqual((await f.request('/mutate', request)).result, first);
  assert.equal(f.calls.filter(row => row[0] === 'fork').length, 1);
  assert.equal((await f.request('/mutate', { ...request, args: { sessionId: 'work-a', allowMigration: true } })).status, 409);
});
test('unknown result remains uncertain on restart and is not replayed; receipt stores no provider text or request body', async t => {
  const f = await fixture(t, { forkAction: async () => { throw fail('ECONNRESET'); } });
  const request = op('session.fork', { sessionId: 'work-a', allowMigration: false });
  assert.equal((await f.request('/mutate', request)).result.state, 'unknown'); await f.restart();
  assert.equal((await f.request('/mutate', request)).result.state, 'unknown');
  assert.equal(f.calls.filter(row => row[0] === 'fork').length, 1);
  const stored = await readFile(join(f.home, 'receipts', request.requestId + '.json'), 'utf8');
  assert.ok(!stored.includes('private-provider-secret')); assert.ok(!stored.includes('allowMigration'));
  assert.equal((await f.request('/operations/status', op('session.pin', { sessionId: 'work-a' }))).result.state, 'missing');
});
test('a failed durability checkpoint retains the known child ID as uncertain and cannot create another child', async t => {
  const f = await fixture(t); f.ctx.sessions.flush = async () => { throw fail('EIO'); };
  const request = op('session.fork', { sessionId: 'work-a', allowMigration: false });
  const receipt = (await f.request('/mutate', request)).result;
  assert.equal(receipt.state, 'unknown'); assert.ok(receipt.resultSessionId);
  assert.equal(f.calls.filter(row => row[0] === 'session.rename').length, 0);
  await f.restart(); assert.deepEqual((await f.request('/mutate', request)).result, receipt);
  assert.equal(f.calls.filter(row => row[0] === 'fork').length, 1);
});
test('directory browser uses host paths; directory creation and registration keep immutable receipts', async t => {
  const f = await fixture(t);
  const directory = (await f.request('/directory/list', {})).result;
  assert.equal(directory.path, f.home);
  const create = op('directory.create', { path: f.home, name: 'created' });
  assert.equal((await f.request('/mutate', create)).result.path, join(f.home, 'created'));
  await f.request('/mutate', create);
  assert.equal(f.calls.filter(row => row[0] === 'directory.create').length, 1);
  const registration = (await f.request('/mutate', op('workspace.register', { path: join(f.home, 'created') }))).result;
  assert.equal(registration.workspaceId, 'new-space');
  assert.equal((await f.request('/directory/list', { path: 'content://fake-tree' })).status, 400);
  assert.equal((await f.request('/mutate', op('directory.create', { path: f.home, name: '../escape' }))).status, 400);
});
test('independent receipt records have no lifetime count cap and persisted pending records never resume execution', async t => {
  const f = await fixture(t);
  const first = op('session.pin', { sessionId: 'work-a' }); await f.request('/mutate', first);
  assert.equal((await f.request('/mutate', op('session.unpin', { sessionId: 'work-a' }))).result.state, 'complete');
  const file = join(f.home, 'receipts', first.requestId + '.json'), stored = JSON.parse(await readFile(file, 'utf8'));
  stored.state = 'pending'; await writeFile(file, JSON.stringify(stored)); await f.restart();
  assert.equal((await f.request('/mutate', first)).result.state, 'unknown');
  assert.equal(f.calls.length, 1); assert.equal((await readdir(join(f.home, 'receipts'))).length, 2);
});
test('invalid shape and unsupported mutation fields fail before SDK calls', async t => {
  const f = await fixture(t);
  for (const request of [op('session.fork', { sessionId: 'work-a' }), op('session.fork', { sessionId: 'work-a', allowMigration: false, atSeq: 1 }),
    op('workspace.rename', { workspaceId: 'space-a', title: '' }), { ...op('session.pin', { sessionId: 'work-a' }), requestId: '../../escape' }])
    assert.equal((await f.request('/mutate', request)).status, 400);
  assert.deepEqual(f.calls, []);
});

test('real SDK forks the full completed prefix, attaches the child to the source workspace and retains persisted logs after archive/removal', async t => {
  const [{ Context }, { default: Storage }, JsonStorage, Domains, { WorkspaceRegistry }, { WorkspaceController },
    { SessionController }, { default: Sessions }, Jsonl, { default: Projections }, { SessionQueryEngine }, { default: Titles }, llm] = await Promise.all([
    loadSdk('@deepseek-ai/cordis'), loadSdk('@deepseek-ai/dsh-storage'), loadSdk('@deepseek-ai/dsh-storage-json'),
    loadSdk('@deepseek-ai/dsh-storage-domain'), loadSdk('@deepseek-ai/dsh-workspace'), loadSdk('@deepseek-ai/dsh-api-workspace-controller'),
    loadSdk('@deepseek-ai/dsh-api-session-controller'), loadSdk('@deepseek-ai/dsh-session'), loadSdk('@deepseek-ai/dsh-session-persistence-jsonl'),
    loadSdk('@deepseek-ai/dsh-session-projection'), loadSdk('@deepseek-ai/dsh-session-query'), loadSdk('@deepseek-ai/dsh-session-title'), loadSdk('@deepseek-ai/dsh-llm'),
  ]);
  const home = await mkdtemp(join(tmpdir(), 'droy-workspaces-sdk-')), ctx = new Context(), writers = new Map(), agents = new Map();
  let server, adapter;
  t.after(async () => {
    await adapter?.dispose(); server?.closeAllConnections(); server?.close();
    for (const writer of writers.values()) await writer.close();
    await ctx.fiber.dispose(); await rm(home, { recursive: true, force: true });
  });
  ctx.provide('remoteAccess', { allows: () => true });
  ctx.provide('typert', { resources: { register() {} }, lookups: { configure() {} }, contexts: { configureHost() {} } });
  ctx.provide('fileUploads', { registerAgentResolver: () => () => {} });
  ctx.provide('agentDefaultModel', { currentSelection: () => ({ provider: 'fictional', model: 'fixture' }) });
  ctx.provide('droyCompanions', { ownsSession: () => false, store: { profiles: async () => [] } });
  await ctx.plugin(Sessions); await ctx.plugin(Projections); await ctx.plugin(Jsonl, { root: join(home, 'sessions'), compression: 'none' });
  await ctx.plugin(SessionQueryEngine); await ctx.plugin(Titles, { fallbackMaxWords: 8, fallbackMaxBytes: 120, maxTitleBytes: 256 });
  await ctx.plugin(Storage); await ctx.plugin(JsonStorage, { root: join(home, 'workspace-state') }); await ctx.plugin(Domains, { backend: 'json' });
  await ctx.plugin(WorkspaceRegistry); await ctx.plugin(WorkspaceController);
  async function persist(session) {
    const writer = await ctx.sessionPersistence.create(session.header, { inheritedEventCount: session.inheritedEventCount });
    const initial = session.snapshotEvents(); await writer.append(initial); writers.set(session.id, writer);
    await writer.flush();
  }
  // The executor is inert: actual public controllers, query, SessionStore,
  // projections and JSONL persistence own fork selection/copying and storage.
  ctx.provide('agents', { get: id => agents.get(id), async create(request) {
    const session = ctx.sessions.create(request.sessionId, { seed: request.seed, inheritedEventCount: request.inheritedEventCount, meta: request.meta });
    const agent = { id: session.id, session, ctx, status: 'idle', options: { ...request.agentOptions } }; agents.set(agent.id, agent);
    await request.setup?.(ctx, agent); await persist(session); return { agent };
  } });
  new SessionController(ctx, { nativeOpen: false });
  await mkdir(join(home, 'work')); await writeFile(join(home, 'work', 'retained.txt'), 'fictional workspace content');
  const { workspace } = await ctx.workspaceController.create({ path: join(home, 'work') });
  ctx.provide('droyInitialization', { defaultWorkspaceId: workspace.workspaceId });
  const source = ctx.sessions.prepare('real-source', { meta: { cwd: workspace.path } });
  const detachSource = ctx.sessions.enter(source); ctx.sessions.announce(source);
  await ctx.workspaceRegistry.get(workspace.workspaceId).attachSession(source.id);
  ctx.sessionTitle.rename(source, 'Source');
  for (let turn = 1; turn <= 60; turn++) {
    source.append('turn/start', { turn });
    source.append('user/message', llm.createUserMessage({ content: [{ type: 'text', text: 'Fictional history ' + turn }], source: { kind: 'user' } }), { surfaceOp: 'append' });
    source.append('turn/end', { turn, reason: { kind: 'completed' } });
  }
  const completed = source.snapshotEvents();
  source.append('turn/start', { turn: 61 });
  source.append('user/message', llm.createUserMessage({ content: [{ type: 'text', text: 'Unfinished current turn' }], source: { kind: 'user' } }), { surfaceOp: 'append' });
  await persist(source);
  adapter = createNativeWorkspaces(ctx, { operationDirectory: join(home, 'receipts'), workspaceDirectory: join(home, 'user-spaces') });
  const signal = new AbortController().signal;
  server = createServer((req, res) => adapter.handle(req, res, signal)); server.listen(0, '127.0.0.1'); await once(server, 'listening');
  const request = async (suffix = '', payload) => {
    const response = await fetch(`http://127.0.0.1:${server.address().port}${'/droy/native/workspaces'}${suffix}`, payload === undefined ? {} :
      { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(payload) });
    const envelope = await response.json(); assert.equal(response.status, 200, JSON.stringify(envelope)); return envelope.result;
  };
  await request('/mutate', op('session.pin', { sessionId: source.id }));
  const forkRequest = op('session.fork', { sessionId: source.id, allowMigration: false });
  const receipt = await request('/mutate', forkRequest), child = ctx.sessions.get(receipt.resultSessionId);
  assert.equal(receipt.state, 'complete'); assert.equal(receipt.title, 'Source (1)'); assert.equal(receipt.renameUnconfirmed, false);
  assert.deepEqual(child.snapshotEvents().slice(0, completed.length), completed);
  assert.equal(child.inheritedEventCount, completed.length);
  assert.equal(child.snapshotEvents().filter(event => event.type === 'user/message').length, 60);
  assert.ok(!JSON.stringify(child.snapshotEvents()).includes('Unfinished current turn'));
  assert.equal(child.header.parentSession, source.id);
  let catalog = await request();
  assert.equal(catalog.sessions.find(row => row.id === child.id).workspaceId, workspace.workspaceId);
  assert.equal(catalog.sessions.find(row => row.id === child.id).readOnly, false);
  assert.deepEqual(catalog.pinnedSessionIds, [source.id]); assert.deepEqual(catalog.archivedSessionIds, []);
  await ctx.sessions.flush(child);
  const sourceBefore = await ctx.sessionPersistence.open(source.id, 'read'), childBefore = await ctx.sessionPersistence.open(child.id, 'read');
  const sourceLog = await sourceBefore.read(), childLog = await childBefore.read(); await sourceBefore.close(); await childBefore.close();
  await request('/mutate', op('session.archive', { sessionId: source.id }));
  catalog = await request(); assert.deepEqual(catalog.archivedSessionIds, [source.id]); assert.ok(!catalog.archivedSessionIds.includes(child.id));
  assert.ok(!catalog.pinnedSessionIds.includes(child.id));
  await request('/mutate', op('workspace.remove', { workspaceId: workspace.workspaceId }));
  detachSource(); catalog = await request();
  assert.equal(catalog.items.length, 0); assert.equal(catalog.sessions.find(row => row.id === child.id).workspaceId, null);
  assert.ok(catalog.sessions.some(row => row.id === source.id));
  for (const [id, log] of [[source.id, sourceLog], [child.id, childLog]]) {
    const handle = await ctx.sessionPersistence.open(id, 'read'); assert.deepEqual((await handle.read()).events, log.events); await handle.close();
  }
  assert.equal(await readFile(join(home, 'work', 'retained.txt'), 'utf8'), 'fictional workspace content');
  assert.deepEqual(await request('/mutate', forkRequest), receipt);
  assert.equal(ctx.sessions.list().filter(row => row.header.parentSession === source.id).length, 1);
});

test('named spaces create a dedicated directory once and retain the registered workspace on replay', async t => {
  const f = await fixture(t);
  const request = op('workspace.create', { name: 'Research' });
  const first = await f.request('/mutate', request);
  assert.equal(first.result.state, 'complete');
  assert.equal(first.result.path, await realpath(join(f.home, 'user-spaces', 'Research')));
  assert.equal(first.result.workspaceId, 'new-space');
  assert.equal(f.calls.filter(row => row[0] === 'create').length, 1);
  await f.restart();
  const replay = await f.request('/mutate', request);
  assert.deepEqual(replay.result, first.result);
  assert.equal(f.calls.filter(row => row[0] === 'create').length, 1);
  const exists = await f.request('/mutate', op('workspace.create', { name: 'Research' }));
  assert.equal(exists.result.state, 'failed');
  assert.equal(exists.result.errorCode, 'directory-picker/exists');
});

test('named spaces reject traversal, blank names, and existing workspace titles before writes', async t => {
  const f = await fixture(t);
  for (const name of ['', ' ', '..', '.. ', '.', '../secret', '/secret', 'a/b', 'a\\b']) {
    const result = await f.request('/mutate', op('workspace.create', { name }));
    assert.equal(result.status, 400, name);
  }
  const duplicate = await f.request('/mutate', op('workspace.create', { name: 'Renamed default' }));
  assert.equal(duplicate.status, 200);
  assert.equal(duplicate.result.state, 'failed');
  assert.equal(duplicate.result.errorCode, 'workspace/name-conflict');
  assert.deepEqual(f.calls, []);
});
