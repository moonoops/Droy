import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, rm, readdir, stat, writeFile, truncate, mkdir, symlink, utimes } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { once } from 'node:events';
import { MemoryStore } from '../../memory/store.mjs';
import { createMemoryArchives, MEMORY_ARCHIVE_MAX_BYTES, MEMORY_ARCHIVE_TTL_MS } from '../memory-archive.mjs';

const passphrase = 'fictional-archive-test-password';
const code = value => error => error.code === value && error.message === value;
const scope = { recordIds: ['fictional-record'], passphrase };
async function fixture(t, backend, authorizeUi = () => true) {
  const root = await mkdtemp(join(tmpdir(), 'droy-archive-test-')), directory = join(root, 'private-archives');
  let storeCalls = 0;
  const archives = createMemoryArchives({ directory, store: async () => { storeCalls++; return backend; }, authorizeUi });
  t.after(async () => { await archives.close(); await rm(root, { force: true, recursive: true }); });
  return { root, directory, archives, get storeCalls() { return storeCalls; } };
}
const stub = (bytes = Buffer.from('synthetic encrypted fixture')) => ({
  async exportArchive({ path, recordIds }) { await writeFile(path, bytes, { mode: 0o600, flag: 'wx' }); return { path, encrypted: true, recordCount: recordIds.length }; },
});
async function empty(directory) {
  const deadline = performance.now() + 2000;
  while (performance.now() < deadline) {
    if ((await readdir(directory)).length === 0) return;
    await new Promise(resolve => setImmediate(resolve));
  }
  assert.deepEqual(await readdir(directory), []);
}

test('denied UI does not open the store or create files, and cannot consume a download', async t => {
  let authorized = false;
  const f = await fixture(t, stub(), context => context === 'actual-request' && authorized);
  assert.equal(await f.archives.exportArchive(scope, 'actual-request'), undefined);
  assert.equal(await f.archives.importArchive({ archiveBase64: 'YQ==', passphrase }, 'actual-request'), undefined);
  assert.equal(f.storeCalls, 0); await assert.rejects(stat(f.directory), { code: 'ENOENT' });
  authorized = true; const exported = await f.archives.exportArchive(scope, 'actual-request');
  authorized = false; assert.equal(await f.archives.downloadArchive(exported.archiveId, 'actual-request'), undefined);
  authorized = true; const download = await f.archives.downloadArchive(exported.archiveId, 'actual-request');
  assert.ok(download.stream); download.stream.resume();
});

test('selected core export is encrypted, opaque, private and single-use; import is durable and empty-space only', async t => {
  const root = await mkdtemp(join(tmpdir(), 'droy-archive-core-'));
  const source = await MemoryStore.open({ directory: join(root, 'source'), initialize: true });
  const destination = await MemoryStore.open({ directory: join(root, 'destination'), initialize: true });
  t.after(async () => { await source.close(); await destination.close(); await rm(root, { force: true, recursive: true }); });
  const selected = await source.record({ operationId: 'one', record: { kind: 'fact', payload: { text: 'fictional selected secret' } } });
  await source.record({ operationId: 'two', record: { kind: 'fact', payload: { text: 'fictional unselected secret' } } });
  const f = await fixture(t, source), into = await fixture(t, destination);
  const exported = await f.archives.exportArchive({ recordIds: [selected.recordId], passphrase });
  assert.match(exported.downloadUrl, /^\/droy\/memory\/archive\/download\/[a-f0-9]{64}$/);
  assert.equal(JSON.stringify(exported).includes(f.root), false); assert.equal(JSON.stringify(exported).includes(passphrase), false);
  assert.equal((await stat(f.directory)).mode & 0o077, 0);
  const folder = (await readdir(f.directory))[0];
  assert.equal((await stat(join(f.directory, folder, 'archive.droy-memory'))).mode & 0o077, 0);
  const [a, b] = await Promise.allSettled([f.archives.downloadArchive(exported.archiveId), f.archives.downloadArchive(exported.archiveId)]);
  assert.equal(a.status, 'fulfilled'); assert.equal(b.status, 'rejected'); assert.equal(b.reason.code, 'MEMORY_ARCHIVE_NOT_FOUND');
  const chunks = []; for await (const chunk of a.value.stream) chunks.push(chunk);
  const bytes = Buffer.concat(chunks); assert.equal(bytes.length, a.value.contentLength);
  assert.equal(bytes.includes(Buffer.from('fictional selected secret')), false); await empty(f.directory);
  const input = { archiveBase64: bytes.toString('base64'), passphrase };
  await assert.rejects(into.archives.importArchive({ ...input, passphrase: 'fictional-wrong-password' }), code('MEMORY_ARCHIVE_AUTHENTICATION'));
  const imported = await into.archives.importArchive(input);
  assert.equal(imported.imported.length, 1); assert.equal((await destination.query()).items[0].payload.text, 'fictional selected secret');
  assert.equal((await into.archives.importArchive(input)).duplicate, true);
  await assert.rejects(f.archives.importArchive(input), code('MEMORY_IMPORT_REQUIRES_EMPTY_SPACE'));
  await empty(into.directory); await empty(f.directory);
});

test('expiry deletes unused downloads and active streams and refuses subsequent access', async t => {
  t.mock.timers.enable({ apis: ['setTimeout', 'Date'], now: Date.now() });
  const f = await fixture(t, stub());
  const one = await f.archives.exportArchive(scope), two = await f.archives.exportArchive(scope);
  const download = await f.archives.downloadArchive(two.archiveId);
  t.mock.timers.tick(MEMORY_ARCHIVE_TTL_MS + 1);
  await empty(f.directory);
  assert.equal(download.stream.destroyed, true);
  await assert.rejects(f.archives.downloadArchive(one.archiveId), code('MEMORY_ARCHIVE_NOT_FOUND'));
});

test('strict 16 MiB ciphertext boundary and canonical base64 are validated without regex stack overflow', async t => {
  let calls = 0;
  const f = await fixture(t, { async importArchive({ operationId, path }) { calls++; assert.match(operationId, /^archive-import:[a-f0-9]{64}$/); assert.equal((await stat(path)).size, MEMORY_ARCHIVE_MAX_BYTES); return { imported: [] }; } });
  for (const value of ['YQ', 'Y Q==', 'YR==', '====', '', 'AA-_']) {
    await assert.rejects(f.archives.importArchive({ archiveBase64: value, passphrase }), code('MEMORY_ARCHIVE_INVALID'));
  }
  const tooLarge = Buffer.alloc(MEMORY_ARCHIVE_MAX_BYTES + 1).toString('base64');
  await assert.rejects(f.archives.importArchive({ archiveBase64: tooLarge, passphrase }), code('MEMORY_ARCHIVE_INVALID'));
  assert.equal(calls, 0); assert.equal(f.storeCalls, 0);
  await f.archives.importArchive({ archiveBase64: Buffer.alloc(MEMORY_ARCHIVE_MAX_BYTES).toString('base64'), passphrase });
  assert.equal(calls, 1); await empty(f.directory);
});

test('oversized core exports and failed imports clean partial files and redact filesystem errors', async t => {
  const f = await fixture(t, {
    async exportArchive({ path }) { await writeFile(path, 'x'); await truncate(path, MEMORY_ARCHIVE_MAX_BYTES + 1); return { encrypted: true, recordCount: 1 }; },
    async importArchive() { throw new Error('/private/synthetic-path ' + passphrase); },
  });
  await assert.rejects(f.archives.exportArchive(scope), code('MEMORY_EXPORT_TOO_LARGE')); await empty(f.directory);
  await assert.rejects(f.archives.importArchive({ archiveBase64: 'YQ==', passphrase }), code('MEMORY_ARCHIVE_IO')); await empty(f.directory);
});

test('opaque download validation and exclusive private directory reject path and symlink substitution', async t => {
  const f = await fixture(t, stub());
  for (const id of ['../private', '/tmp/file', 'a'.repeat(63), 'A'.repeat(64)]) await assert.rejects(f.archives.downloadArchive(id), code('MEMORY_ARCHIVE_NOT_FOUND'));
  await mkdir(join(f.root, 'elsewhere')); await symlink(join(f.root, 'elsewhere'), f.directory);
  await assert.rejects(f.archives.exportArchive(scope), code('MEMORY_ARCHIVE_DIRECTORY'));
  assert.deepEqual(await readdir(join(f.root, 'elsewhere')), []);
});

test('uninitialized stores stay uninitialized and close cleans all outstanding exports', async t => {
  const absent = await fixture(t, undefined);
  await assert.rejects(absent.archives.exportArchive(scope), code('MEMORY_NOT_INITIALIZED'));
  await assert.rejects(stat(absent.directory), { code: 'ENOENT' });
  const f = await fixture(t, stub());
  for (let i = 0; i < 4; i++) await f.archives.exportArchive(scope);
  await assert.rejects(f.archives.exportArchive(scope), code('MEMORY_ARCHIVE_BUSY'));
  await f.archives.close(); await empty(f.directory);
  await assert.rejects(f.archives.exportArchive(scope), code('MEMORY_ARCHIVE_CLOSED'));
});

test('close waits for cleanup already started by an abandoned download', async t => {
  const f = await fixture(t, stub());
  const exported = await f.archives.exportArchive(scope), download = await f.archives.downloadArchive(exported.archiveId);
  const closed = once(download.stream, 'close'); download.stream.destroy(); await closed;
  await f.archives.close(); assert.deepEqual(await readdir(f.directory), []);
});

test('restart sweeps expired owned temporary folders without following unrelated symlinks', async t => {
  const f = await fixture(t, stub()); await mkdir(f.directory);
  const stale = join(f.directory, '.memory-archive-stale'); await mkdir(stale); await writeFile(join(stale, 'archive.droy-memory'), 'ciphertext');
  const old = new Date(Date.now() - MEMORY_ARCHIVE_TTL_MS - 1000); await utimes(stale, old, old);
  const outside = join(f.root, 'retained'); await mkdir(outside); await writeFile(join(outside, 'keep'), 'fictional');
  await symlink(outside, join(f.directory, '.memory-archive-link'));
  await f.archives.exportArchive(scope);
  await assert.rejects(stat(stale), { code: 'ENOENT' }); assert.deepEqual(await readdir(outside), ['keep']);
});
