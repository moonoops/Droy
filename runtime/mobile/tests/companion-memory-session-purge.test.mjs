import test, { after } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, mkdir, readFile, writeFile, copyFile, symlink, rm, realpath, lstat } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { basename, dirname, join } from 'node:path';
import { createRequire, registerHooks } from 'node:module';
import { pathToFileURL } from 'node:url';
import { spawn } from 'node:child_process';
import { createInterface } from 'node:readline';
import { patchNativeCompanionMaintenance } from '../../../tools/native_companion_maintenance_patch.mjs';
import { CompanionMemorySpaces } from '../companions/memory-spaces.mjs';
const installed = createRequire(new URL('../../dsh/package.json', import.meta.url));
const providerPath = installed.resolve('@deepseek-ai/dsh-session-persistence-jsonl');
const patched = await realpath(await mkdtemp(join(tmpdir(), 'droy-memory-purge-sdk-')));
await writeFile(join(patched, 'index.mjs'), patchNativeCompanionMaintenance(await readFile(providerPath, 'utf8')));
await copyFile(join(dirname(providerPath), 'worker.cjs'), join(patched, 'worker.cjs'));
await symlink(new URL('../../dsh/node_modules', import.meta.url), join(patched, 'node_modules'), 'dir');
const hooks = registerHooks({ resolve(s, c, n) { if (s.startsWith('@deepseek-ai/')) return { url: pathToFileURL(installed.resolve(s)).href, shortCircuit: true }; return n(s, c); } });
const [{ runOfflineCompanionMemorySessionPurge: run, purgeCompanionMemorySessions: online }, { createOfflineJsonlProvider }, Jsonl, { createUserMessage }, { CompanionStore }] = await Promise.all([
  import('../companions/memory-session-purge.mjs'), import('../companions/maintenance-sessions.mjs'), import(pathToFileURL(join(patched, 'index.mjs')).href),
  import('@deepseek-ai/dsh-llm'), import('../companions/store.mjs')]);
hooks.deregister(); after(() => rm(patched, { recursive: true, force: true }));
const provider = createOfflineJsonlProvider(Jsonl);
const event = text => ({ type: 'user/message', seq: 0, time: 1, surfaceOp: 'append', data: createUserMessage({ content: [{ type: 'text', text }], source: { kind: 'user' } }) });
async function session(root, header, text) {
  const opened = await provider({ root, compression: 'none' });
  try { const writer = await opened.store.create(header); try { await writer.append([event(text)]); await writer.flush(); } finally { await writer.close(); } } finally { await opened.close(); }
}
async function inspect(root) {
  const opened = await provider({ root, compression: 'none' });
  try { const audit = await opened.audit(); const values = [];
    for (const row of audit.entries) { const reader = await opened.store.open(row.id, 'read'); try { values.push({ header: reader.header, ...await reader.read() }); } finally { await reader.close(); } }
    return values;
  } finally { await opened.close(); }
}
async function fixture(t) {
  const home = await realpath(await mkdtemp(join(tmpdir(), 'droy-memory-purge-home-'))); t.after(() => rm(home, { recursive: true, force: true }));
  const root = join(home, 'droy-companions'), store = await CompanionStore.open({ directory: root });
  const keep = (await store.profiles())[0].id, target = (await store.createProfile({ requestId: 'memory-purge-target', templateId: 'kaka', name: 'Synthetic target', model: null })).profile.id;
  for (const id of [target, keep]) await mkdir(join(root, id, 'work'), { recursive: true });
  await store.bindSession(target, { sessionId: 'target-chat', workspaceId: 'target-workspace', kind: 'chat' });
  await store.bindSession(target, { sessionId: 'target-work', workspaceId: 'target-workspace', kind: 'work', taskId: 'target-task' });
  await store.bindSession(keep, { sessionId: 'keep-work', workspaceId: 'keep-workspace', kind: 'work', taskId: 'keep-task' });
  await store.appendMessage(target, { id: 'visible-source', role: 'user', type: 'text', text: 'SYNTHETIC_VISIBLE_CHAT' });
  await store.appendMessage(target, { id: 'target-task', role: 'assistant', type: 'task', text: 'SYNTHETIC_VISIBLE_TASK', task: { id: 'target-task', sessionId: 'target-work', state: 'running', summary: 'SYNTHETIC_DERIVED_SUMMARY' } });
  const profile = await store.getProfile(target), visible = (await store.listMessages(target)).messages;
  await store.close();
  const spaces = await CompanionMemorySpaces.open({ directory: root });
  const space = await spaces.ensureActiveSpace(target, { profile });
  await writeFile(join(space.directory, '..', 'synthetic-memory.txt'), 'SYNTHETIC_RECALLED_MEMORY');
  const operationId = 'memory-management:synthetic-op', admissionEpoch = 'memory-purge-epoch';
  await spaces.beginPurge(space.memorySpaceId, { expectedRevision: space.revision, operationId, sourceCaptureBoundary: 'seq:2' });
  const sessionsRoot = join(home, 'sessions'), header = (id, owner, extra = {}) => ({ version: 4, id, cwd: join(root, owner, 'work'), createdAt: 1, isSeeded: false, delegationDepth: 0, ...extra });
  await session(sessionsRoot, header('target-chat', target), 'SYNTHETIC_RECALLED_MEMORY');
  await session(sessionsRoot, header('target-work', target), 'SYNTHETIC_TOOL_MEMORY');
  await session(sessionsRoot, header('target-child', target, { origin: 'subagent', parentSession: 'target-work', delegationDepth: 1 }), 'SYNTHETIC_CHILD_MEMORY');
  await session(sessionsRoot, header('keep-work', keep), 'SYNTHETIC_KEEP_MEMORY');
  const workspace = { unit: { name: 'workspace', version: 2 }, global: { initialized: true, workspaceIds: ['target-workspace', 'keep-workspace'], pinnedSessionIds: ['target-chat', 'keep-work'], archivedSessionIds: ['target-work'] }, tables: { workspaces: {
    'target-workspace': { path: join(root, target, 'work'), title: 'Synthetic target', sessionIds: ['target-chat', 'target-work', 'target-child'], createdAt: '2030', updatedAt: '2030' },
    'keep-workspace': { path: join(root, keep, 'work'), title: 'Synthetic keep', sessionIds: ['keep-work'], createdAt: '2030', updatedAt: '2030' },
  } } };
  await mkdir(join(home, 'storages')); await writeFile(join(home, 'storages', 'workspace.json'), JSON.stringify(workspace));
  let held = true;
  const authority = { async assertHeld(request) { return { ...request, hostStopped: held, admissionsClosed: held }; } };
  const config = { home, companionId: target, memorySpaceId: space.memorySpaceId, operationId, admissionEpoch, authority, provider, compression: 'none' };
  return { home, root, target, keep, spaces, space, visible, sessionsRoot, header, workspace, config, lose() { held = false; } };
}
test('offline purge removes owned engine memory while preserving visible chats, profile and unrelated sessions', async t => {
  const f = await fixture(t), before = await inspect(f.sessionsRoot), keep = before.find(row => row.header.id === 'keep-work');
  const receipt = await run(f.config); assert.equal(receipt.completed, true); assert.deepEqual(receipt.removedSessionIds, ['target-chat', 'target-child', 'target-work']);
  assert.deepEqual(await inspect(f.sessionsRoot), [keep]);
  const store = await CompanionStore.open({ directory: f.root });
  try {
    assert.equal((await store.getProfile(f.target)).chatSessionId, null); assert.deepEqual(await store.listSessions(f.target), []);
    const messages = (await store.listMessages(f.target)).messages; assert.deepEqual(messages.map(row => row.text), f.visible.map(row => row.text));
    assert.equal(messages.find(row => row.id === 'target-task').task.state, 'cancelled');
    assert.equal((await store.getState(f.target)).forgottenThrough, 'seq:2'); assert.equal((await store.listSessions(f.keep)).length, 1);
  } finally { await store.close(); }
  assert.equal((await f.spaces.get(f.space.memorySpaceId)).state, 'purged');
  const replacement = await f.spaces.current(f.target); assert.equal(replacement.state, 'active'); assert.notEqual(replacement.memorySpaceId, f.space.memorySpaceId); assert.equal(replacement.sourceCaptureBoundary, 'seq:2');
  await assert.rejects(lstat(join(f.space.directory, '..', 'synthetic-memory.txt')), { code: 'ENOENT' });
  const workspace = JSON.parse(await readFile(join(f.home, 'storages', 'workspace.json'), 'utf8'));
  assert.deepEqual(workspace.tables.workspaces['keep-workspace'], f.workspace.tables.workspaces['keep-workspace']); assert.deepEqual(workspace.tables.workspaces['target-workspace'].sessionIds, []);
  assert.deepEqual(workspace.global.pinnedSessionIds, ['keep-work']); assert.deepEqual(await run(f.config), receipt);
});
test('each destructive checkpoint resumes under the same authorization without losing other sessions', async t => {
  for (const stop of ['planned', 'sessions:source-renamed', 'sessions', 'bindings', 'complete']) await t.test(stop, async child => {
    const f = await fixture(child); let interrupted = false;
    await assert.rejects(run({ ...f.config, onCheckpoint(phase) { if (phase === stop && !interrupted) { interrupted = true; throw Error('synthetic-interruption'); } } }), /synthetic-interruption/);
    assert.equal(interrupted, true); assert.equal((await run(f.config)).completed, true); assert.deepEqual((await inspect(f.sessionsRoot)).map(row => row.header.id), ['keep-work']);
  });
});
test('lost native authority or missing persisted user grant cannot erase sessions', async t => {
  const f = await fixture(t), before = await inspect(f.sessionsRoot); f.lose(); await assert.rejects(run(f.config), { code: 'COMPANION_MEMORY_SESSION_AUTHORITY_LOST' }); assert.deepEqual(await inspect(f.sessionsRoot), before);
  const g = await fixture(t); await assert.rejects(run({ ...g.config, operationId: 'forged-op' }), { code: 'COMPANION_MEMORY_SESSION_GRANT_REQUIRED' }); assert.equal((await inspect(g.sessionsRoot)).length, 4);
});
test('foreign ownership and inherited sessions outside companion scope fail closed', async t => {
  const f = await fixture(t); await session(f.sessionsRoot, f.header('foreign-child', f.keep, { parentSession: 'target-work', origin: 'subagent', delegationDepth: 1 }), 'SYNTHETIC_FOREIGN');
  await assert.rejects(run(f.config), { code: 'COMPANION_MEMORY_SESSION_RELATED_SCOPE_CONFLICT' }); assert.equal((await inspect(f.sessionsRoot)).length, 5);
  const g = await fixture(t); await session(g.sessionsRoot, g.header('unclaimed-private', g.target), 'SYNTHETIC_UNKNOWN');
  await assert.rejects(run(g.config), { code: 'COMPANION_MEMORY_SESSION_SCOPE_UNPROVEN' }); assert.equal((await inspect(g.sessionsRoot)).length, 5);
});
test('online flow drains actual owned agents and demands offline cleanup without changing persistence', async t => {
  const f = await fixture(t), store = await CompanionStore.open({ directory: f.root }), before = await inspect(f.sessionsRoot), calls = [];
  const ctx = { sessionPersistence: { list: async () => before }, agents: { get(sessionId) { return { cancel() { calls.push('cancel:' + sessionId); }, async whenIdle() { calls.push('idle:' + sessionId); } }; } } };
  try { await assert.rejects(online({ ctx, store, directory: f.root, companionId: f.target }), { code: 'COMPANION_MEMORY_SESSION_PURGE_REQUIRES_OFFLINE' }); }
  finally { await store.close(); }
  assert.equal(calls.length, 6); assert.equal(calls.some(row => row.endsWith('keep-work')), false); assert.deepEqual(await inspect(f.sessionsRoot), before);
});
test('empty live list still requires a strict offline historical audit', async t => {
  const f = await fixture(t), store = await CompanionStore.open({ directory: f.root });
  try { await assert.rejects(online({ ctx: { sessionPersistence: { list: async () => [] } }, store, directory: f.root, companionId: f.target }), { code: 'COMPANION_MEMORY_SESSION_PURGE_REQUIRES_OFFLINE' }); }
  finally { await store.close(); }
});
test('native home alias supports Android canonical paths and cannot be changed during recovery', async t => {
  const f = await fixture(t), carrier = await mkdtemp(join(tmpdir(), 'droy-memory-purge-alias-')); t.after(() => rm(carrier, { recursive: true, force: true }));
  await symlink(dirname(f.home), join(carrier, 'parent'), 'dir');
  const aliasRoot = join(carrier, 'parent', basename(f.home)), rows = await inspect(f.sessionsRoot);
  await rm(f.sessionsRoot, { recursive: true });
  for (const row of rows) await session(f.sessionsRoot, { ...row.header, cwd: aliasRoot + row.header.cwd.slice(f.home.length) }, row.events[0].data.content[0].text);
  await assert.rejects(run(f.config), { code: 'COMPANION_MEMORY_SESSION_SCOPE_UNPROVEN' });
  const config = { ...f.config, trustedRootAlias: { canonicalRoot: f.home, aliasRoot } };
  await assert.rejects(run({ ...config, onCheckpoint(phase) { if (phase === 'planned') throw Error('synthetic-pause'); } }), /synthetic-pause/);
  await assert.rejects(run({ ...config, admissionEpoch: 'different-epoch' }), { code: 'COMPANION_MEMORY_SESSION_JOURNAL_INVALID' });
  await assert.rejects(run(f.config), { code: 'COMPANION_MEMORY_SESSION_JOURNAL_INVALID' });
  assert.equal((await run(config)).completed, true); assert.equal((await inspect(f.sessionsRoot))[0].header.cwd, aliasRoot + rows.find(row => row.header.id === 'keep-work').header.cwd.slice(f.home.length));
});
test('retained memory without a live companion profile purges without recreating a profile or space', async t => {
  const home = await realpath(await mkdtemp(join(tmpdir(), 'droy-retained-purge-'))); t.after(() => rm(home, { recursive: true, force: true }));
  const root = join(home, 'droy-companions'), store = await CompanionStore.open({ directory: root }), profiles = await store.profiles(); await store.close();
  const companionId = 'archived-companion', profile = { ...profiles[0], id: companionId, name: 'Synthetic archived' };
  const spaces = await CompanionMemorySpaces.open({ directory: root }); await spaces.ensureActiveSpace(companionId, { profile });
  const retained = await spaces.retain(companionId, { profile, operationId: 'synthetic-retain' }), operationId = 'memory-management:retained';
  await spaces.beginPurge(retained.memorySpaceId, { expectedRevision: retained.revision, operationId });
  const keepRoot = join(root, profiles[0].id, 'work'); await mkdir(keepRoot, { recursive: true });
  await session(join(home, 'sessions'), { version: 4, id: 'retained-keep', cwd: keepRoot, createdAt: 1, isSeeded: false, delegationDepth: 0 }, 'SYNTHETIC_KEEP');
  const result = await run({ home, companionId, memorySpaceId: retained.memorySpaceId, operationId, admissionEpoch: 'retained-epoch', compression: 'none', provider,
    authority: { async assertHeld(request) { return { ...request, hostStopped: true, admissionsClosed: true }; } } });
  assert.equal(result.completed, true); assert.equal(result.replacementSpaceId, null); assert.deepEqual(result.removedSessionIds, []);
  assert.equal((await spaces.get(retained.memorySpaceId)).state, 'purged');
  const after = await CompanionStore.open({ directory: root }); try { assert.deepEqual(await after.profiles(), profiles); } finally { await after.close(); }
});
test('native runner challenges the live authority channel and returns a complete scoped receipt', async t => {
  const f = await fixture(t), loader = join(f.home, 'synthetic-loader.mjs');
  await writeFile(loader, `import {createRequire,registerHooks} from 'node:module';import {pathToFileURL} from 'node:url';
    const installed=createRequire(${JSON.stringify(new URL('../../dsh/package.json', import.meta.url).href)});
    registerHooks({resolve(s,c,n){if(s==='@deepseek-ai/dsh-session-persistence-jsonl')return {url:${JSON.stringify(pathToFileURL(join(patched, 'index.mjs')).href)},shortCircuit:true};if(s.startsWith('@deepseek-ai/'))return {url:pathToFileURL(installed.resolve(s)).href,shortCircuit:true};return n(s,c);}});`);
  const child = spawn(process.execPath, ['--import', loader, new URL('../companions/memory-session-purge-native-runner.mjs', import.meta.url).pathname], { stdio: ['pipe', 'pipe', 'pipe'] });
  t.after(() => { if (child.exitCode === null) child.kill(); });
  const messages = [], input = createInterface({ input: child.stdout }); let errors = '';
  child.stderr.on('data', data => { errors += data; });
  const finished = new Promise((resolve, reject) => { child.on('error', reject); child.on('exit', code => resolve(code)); });
  for await (const line of input) {
    const value = JSON.parse(line); messages.push(value);
    if (value.kind === 'started') { const { authority, provider, ...args } = f.config; child.stdin.write(JSON.stringify({ action: 'commit', ...args }) + '\n'); }
    if (value.kind === 'authority') {
      assert.equal(value.request.scope, 'companion-memory-purge'); assert.equal(value.request.root, f.home);
      for (const key of ['operationId','memorySpaceId','companionId','admissionEpoch']) assert.equal(value.request[key], f.config[key]);
      child.stdin.write(JSON.stringify({ kind: 'authority', nonce: value.nonce, held: true }) + '\n');
    }
  }
  assert.equal(await finished, 0, errors); const receipt = messages.find(row => row.kind === 'result')?.receipt;
  assert.equal(receipt?.completed, true); assert.equal(receipt.memorySpaceId, f.space.memorySpaceId);
  assert.equal(JSON.stringify(messages).includes('SYNTHETIC_RECALLED_MEMORY'), false); assert.equal(messages.filter(row => row.kind === 'authority').length > 10, true);
});
