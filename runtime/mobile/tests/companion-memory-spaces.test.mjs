import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, mkdir, writeFile, readFile, realpath, rm, symlink, rename, lstat } from 'node:fs/promises';
import { join } from 'node:path';
import { tmpdir } from 'node:os';
import { DatabaseSync } from 'node:sqlite';
import { CompanionMemorySpaces } from '../companions/memory-spaces.mjs';

async function fixture(t) {
  const home = await realpath(await mkdtemp(join(tmpdir(), 'droy-memory-spaces-'))), directory = join(home, 'companions');
  await mkdir(directory); t.after(() => rm(home, { recursive: true, force: true }));
  const spaces = await CompanionMemorySpaces.open({ directory });
  return { home, directory, spaces, profile: { id: 'alpha', name: 'Synthetic companion', workspaceId: 'old-space', personaRevision: 3,
    personalityOverride: 'Synthetic original personality', model: { provider: 'synthetic', model: 'unused' }, settings: {} } };
}
test('legacy memory and identity move into one stable hidden space and survive companion removal', async t => {
  const f = await fixture(t), legacy = join(f.directory, 'alpha');
  await mkdir(join(legacy, 'memory'), { recursive: true });
  await writeFile(join(legacy, 'memory', 'memory.sqlite'), 'synthetic preserved bytes');
  await writeFile(join(legacy, 'memory.identity.json'), 'synthetic identity');
  const first = await f.spaces.ensureActiveSpace('alpha', { profile: f.profile });
  assert.equal(await readFile(join(first.directory, 'memory.sqlite'), 'utf8'), 'synthetic preserved bytes');
  assert.equal(await readFile(join(first.directory, '..', 'memory.identity.json'), 'utf8'), 'synthetic identity');
  await assert.rejects(lstat(join(legacy, 'memory')), { code: 'ENOENT' });
  const second = await (await CompanionMemorySpaces.open({ directory: f.directory })).ensureActiveSpace('alpha');
  assert.equal(second.memorySpaceId, first.memorySpaceId);
  const retained = await f.spaces.retain('alpha', { profile: f.profile, evidence: [{ sourceRef: 'im:alpha:one', text: 'Synthetic evidence' }], operationId: 'remove-alpha' });
  await rm(legacy, { recursive: true });
  assert.equal(retained.state, 'retained');
  assert.equal(await readFile(join(first.directory, 'memory.sqlite'), 'utf8'), 'synthetic preserved bytes');
  assert.equal(JSON.parse(await readFile(join(first.directory, '..', 'persona.json'))).model, undefined);
  assert.equal(JSON.parse(await readFile(join(first.directory, '..', 'evidence.json'))).sources[0].text, 'Synthetic evidence');
  await assert.rejects(f.spaces.assertActive(first), { code: 'COMPANION_MEMORY_SPACE_STALE' });
  await assert.rejects(f.spaces.ensureActiveSpace('alpha'), { code: 'COMPANION_MEMORY_SPACE_RETAINED' });
  assert.deepEqual(await f.spaces.retain('alpha', { operationId: 'remove-alpha' }), retained);
  const other = await f.spaces.ensureActiveSpace('beta', { profile: { ...f.profile, id: 'beta' } });
  assert.notEqual(other.memorySpaceId, first.memorySpaceId);
  const listed = await f.spaces.list({ state: 'retained' });
  assert.equal(listed.length, 1); assert.equal('directory' in listed[0], false); assert.equal('personalityOverride' in listed[0], false);
});
test('archive waits for admitted work, then fences old bindings and restores only explicitly', async t => {
  const f = await fixture(t), binding = await f.spaces.ensureActiveSpace('alpha', { profile: f.profile });
  let release; const blocked = new Promise(resolve => { release = resolve; });
  let entered; const started = new Promise(resolve => { entered = resolve; });
  const write = f.spaces.withActive(binding, async () => { entered(); await blocked; return 'committed'; });
  await started;
  const archive = f.spaces.retain('alpha', { operationId: 'archive' });
  release(); assert.equal(await write, 'committed'); await archive;
  await assert.rejects(f.spaces.withActive(binding, () => assert.fail('must not run')), { code: 'COMPANION_MEMORY_SPACE_STALE' });
  const retained = await f.spaces.get(binding.memorySpaceId);
  assert.equal((await f.spaces.persona(binding.memorySpaceId)).personalityOverride, 'Synthetic original personality');
  await assert.rejects(f.spaces.restore(binding.memorySpaceId, { workspaceId: 'unauthorized' }), { code: 'COMPANION_MEMORY_OPERATION_CONFLICT' });
  await f.spaces.beginRestore(binding.memorySpaceId, { expectedRevision: retained.revision, operationId: 'restore' });
  const restored = await f.spaces.restore(binding.memorySpaceId, { workspaceId: 'new-workspace', operationId: 'restore' });
  assert.deepEqual(await f.spaces.restore(binding.memorySpaceId, { workspaceId: 'new-workspace', operationId: 'restore' }), restored);
  assert.equal(restored.memorySpaceId, binding.memorySpaceId); assert.equal(restored.originalWorkspaceId, 'old-space');
  assert.equal(restored.workspaceId, 'new-workspace'); assert.ok(restored.epoch > binding.epoch);
  await assert.rejects(f.spaces.assertActive(binding), { code: 'COMPANION_MEMORY_SPACE_STALE' });
  assert.equal((await f.spaces.assertActive(restored)).state, 'active');
});
test('whole-space purge leaves terminal identity and starts a new active generation only when requested', async t => {
  const f = await fixture(t), old = await f.spaces.ensureActiveSpace('alpha', { profile: f.profile });
  await mkdir(old.directory); await writeFile(join(old.directory, 'private.txt'), 'Synthetic private memory');
  await assert.rejects(f.spaces.beginPurge(old.memorySpaceId, { expectedRevision: 99, operationId: 'purge' }), { code: 'COMPANION_MEMORY_REVISION_CONFLICT' });
  const pending = await f.spaces.beginPurge(old.memorySpaceId, { expectedRevision: old.revision, operationId: 'purge', sourceCaptureBoundary: 'message-cutoff' });
  assert.equal(pending.state, 'purging'); await assert.rejects(f.spaces.assertActive(old), { code: 'COMPANION_MEMORY_SPACE_STALE' });
  const reopened = await CompanionMemorySpaces.open({ directory: f.directory });
  const result = await reopened.finishPurge(old.memorySpaceId, { operationId: 'purge', reactivate: true });
  assert.notEqual(result.replacement.memorySpaceId, old.memorySpaceId); assert.equal(result.replacement.sourceCaptureBoundary, 'message-cutoff');
  assert.equal((await reopened.get(old.memorySpaceId)).state, 'purged');
  await assert.rejects(lstat(join(old.directory, '..')), { code: 'ENOENT' });
  assert.deepEqual(await reopened.finishPurge(old.memorySpaceId, { operationId: 'purge', reactivate: true, sourceCaptureBoundary: 'message-cutoff' }), result);
  assert.equal((await reopened.ensureActiveSpace('alpha')).memorySpaceId, result.replacement.memorySpaceId);
  assert.equal((await reopened.persona(result.replacement.memorySpaceId)).personalityOverride, 'Synthetic original personality');
});
test('purging retained memory never implicitly creates a replacement', async t => {
  const f = await fixture(t); await f.spaces.ensureActiveSpace('alpha', { profile: f.profile });
  const retained = await f.spaces.retain('alpha', { operationId: 'archive' });
  await f.spaces.beginPurge(retained.memorySpaceId, { expectedRevision: retained.revision, operationId: 'purge' });
  await assert.rejects(f.spaces.finishPurge(retained.memorySpaceId, { operationId: 'purge', reactivate: true }), { code: 'COMPANION_MEMORY_SPACE_NOT_ACTIVE' });
  const result = await f.spaces.finishPurge(retained.memorySpaceId, { operationId: 'purge' });
  assert.equal(result.replacement, null); await assert.rejects(f.spaces.ensureActiveSpace('alpha'), { code: 'COMPANION_MEMORY_SPACE_PURGED' });
});
test('pending restore and purge cannot both own a retained identity', async t => {
  const f = await fixture(t); await f.spaces.ensureActiveSpace('alpha', { profile: f.profile });
  const retained = await f.spaces.retain('alpha', { operationId: 'archive' });
  const restoring = await f.spaces.beginRestore(retained.memorySpaceId, { expectedRevision: retained.revision, operationId: 'restore' });
  await assert.rejects(f.spaces.beginPurge(retained.memorySpaceId, { expectedRevision: restoring.revision, operationId: 'purge' }), { code: 'COMPANION_MEMORY_OPERATION_CONFLICT' });
  const active = await f.spaces.restore(retained.memorySpaceId, { operationId: 'restore', workspaceId: 'restored-workspace' });
  const purging = await f.spaces.beginPurge(active.memorySpaceId, { expectedRevision: active.revision, operationId: 'purge' });
  await assert.rejects(f.spaces.beginRestore(active.memorySpaceId, { expectedRevision: purging.revision, operationId: 'new-restore' }), { code: 'COMPANION_MEMORY_OPERATION_CONFLICT' });
});
test('interrupted two-file migration resumes without discarding either identity or data', async t => {
  const f = await fixture(t), binding = await f.spaces.ensureActiveSpace('alpha');
  await mkdir(binding.directory); await writeFile(join(binding.directory, 'data'), 'synthetic');
  await mkdir(join(f.directory, 'alpha')); await writeFile(join(f.directory, 'alpha', 'memory.identity.json'), 'identity');
  const db = new DatabaseSync(join(f.spaces.vault, 'spaces.sqlite'));
  const row = JSON.parse(db.prepare('SELECT data FROM spaces WHERE id=?').get(binding.memorySpaceId).data); row.migration = 'pending';
  db.prepare('UPDATE spaces SET data=? WHERE id=?').run(JSON.stringify(row), binding.memorySpaceId); db.close();
  const recovered = await (await CompanionMemorySpaces.open({ directory: f.directory })).ensureActiveSpace('alpha');
  assert.equal(recovered.memorySpaceId, binding.memorySpaceId);
  assert.equal(await readFile(join(recovered.directory, 'data'), 'utf8'), 'synthetic');
  assert.equal(await readFile(join(recovered.directory, '..', 'memory.identity.json'), 'utf8'), 'identity');
});
test('memory migration and purge reject links instead of reading or removing outside files', async t => {
  const f = await fixture(t), outside = join(f.home, 'outside'); await mkdir(outside); await writeFile(join(outside, 'keep'), 'keep');
  await mkdir(join(f.directory, 'alpha')); await symlink(outside, join(f.directory, 'alpha', 'memory'));
  await assert.rejects(f.spaces.ensureActiveSpace('alpha'), { code: 'COMPANION_MEMORY_LINKED_PATH' });
  const g = await f.spaces.ensureActiveSpace('beta'); await symlink(outside, join(g.directory, '..', 'external'));
  await f.spaces.beginPurge(g.memorySpaceId, { expectedRevision: g.revision, operationId: 'purge' });
  await assert.rejects(f.spaces.finishPurge(g.memorySpaceId, { operationId: 'purge' }), { code: 'COMPANION_MEMORY_LINKED_PATH' });
  assert.equal(await readFile(join(outside, 'keep'), 'utf8'), 'keep');
});
