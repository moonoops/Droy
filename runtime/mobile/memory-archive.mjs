import { randomBytes, createHash } from 'node:crypto';
import { constants } from 'node:fs';
import { mkdir, mkdtemp, lstat, chmod, readdir, rm, open } from 'node:fs/promises';
import { isAbsolute, join } from 'node:path';
import { fields, identity, MemoryError } from '../memory/schema.mjs';

export const MEMORY_ARCHIVE_MAX_BYTES = 16 * 1024 * 1024;
export const MEMORY_ARCHIVE_TTL_MS = 5 * 60 * 1000;
const PREFIX = '.memory-archive-';
const MAX_OPERATIONS = 4;
const fail = code => { throw new MemoryError(code); };
function password(value) {
  if (typeof value !== 'string' || value.length < 12 || Buffer.byteLength(value) > 1024 || value.includes('\0')) fail('MEMORY_INVALID_PASSPHRASE');
}
function safeError(error) {
  return error instanceof MemoryError ? error : new MemoryError('MEMORY_ARCHIVE_IO');
}
function decode(value) {
  if (typeof value !== 'string' || !value.length || value.length % 4
    || value.length > Math.ceil(MEMORY_ARCHIVE_MAX_BYTES / 3) * 4
    || !/^[A-Za-z0-9+/]*={0,2}$/.test(value)) fail('MEMORY_ARCHIVE_INVALID');
  const bytes = Buffer.from(value, 'base64');
  if (bytes.length > MEMORY_ARCHIVE_MAX_BYTES || bytes.toString('base64') !== value) fail('MEMORY_ARCHIVE_INVALID');
  return bytes;
}

/** Authenticated mobile transport only; the host owns activation and store initialization. */
export function createMemoryArchives({ store, directory, authorizeUi }) {
  if (typeof store !== 'function' || typeof authorizeUi !== 'function' || typeof directory !== 'string'
    || !isAbsolute(directory) || directory.includes('\0')) fail('MEMORY_ARCHIVE_CONFIG');
  const entries = new Map(), pending = new Set();
  let initialized, closed = false, closePromise, reservedExports = 0, cleanupFailed = false;
  async function prepare() {
    initialized ??= (async () => {
      await mkdir(directory, { recursive: true, mode: 0o700 });
      const info = await lstat(directory);
      if (!info.isDirectory() || info.isSymbolicLink() || (process.getuid && info.uid !== process.getuid())) fail('MEMORY_ARCHIVE_DIRECTORY');
      await chmod(directory, 0o700);
      // A crashed process cannot serve its old opaque IDs. Reap only expired,
      // adapter-owned temporary directories, without following symlinks.
      for (const item of await readdir(directory, { withFileTypes: true })) {
        if (!item.name.startsWith(PREFIX) || !item.isDirectory() || item.isSymbolicLink()) continue;
        const path = join(directory, item.name), stat = await lstat(path);
        if (stat.isDirectory() && !stat.isSymbolicLink() && stat.mtimeMs <= Date.now() - MEMORY_ARCHIVE_TTL_MS
          && (!process.getuid || stat.uid === process.getuid())) await rm(path, { recursive: true, force: true });
      }
    })();
    await initialized;
  }
  async function temporary() {
    await prepare();
    const folder = await mkdtemp(join(directory, PREFIX));
    await chmod(folder, 0o700);
    return { folder, path: join(folder, 'archive.droy-memory') };
  }
  async function remove(entry) {
    if (entry.cleanup) return entry.cleanup;
    clearTimeout(entry.timer);
    entry.cleanup = (async () => {
      if (entry.stream && !entry.stream.closed) {
        const finished = new Promise(resolve => entry.stream.once('close', resolve));
        entry.stream.destroy(); await finished;
      }
      await rm(entry.folder, { recursive: true, force: true });
      entries.delete(entry.id);
    })();
    try { await entry.cleanup; } catch { cleanupFailed = true; fail('MEMORY_ARCHIVE_IO'); }
  }
  function scheduleRemoval(entry) {
    entry.timer = setTimeout(() => { void remove(entry).catch(() => { cleanupFailed = true; }); },
      Math.max(0, entry.expiresAt - Date.now()));
    entry.timer.unref?.();
  }
  async function run(context, action) {
    // A denied hook may already have completed the HTTP response.
    if (await authorizeUi(context) !== true) return undefined;
    if (closed) fail('MEMORY_ARCHIVE_CLOSED');
    if (cleanupFailed) fail('MEMORY_ARCHIVE_IO');
    if (pending.size >= MAX_OPERATIONS) fail('MEMORY_ARCHIVE_BUSY');
    const work = Promise.resolve().then(action); pending.add(work);
    try { return await work; } catch (error) { throw safeError(error); }
    finally { pending.delete(work); }
  }
  return Object.freeze({
    exportArchive(args, context) {
      return run(context, async () => {
        fields(args, ['recordIds', 'passphrase']); password(args.passphrase);
        if (!Array.isArray(args.recordIds) || !args.recordIds.length || args.recordIds.length > 1000) fail('MEMORY_EXPORT_SCOPE_REQUIRED');
        const recordIds = [...new Set(args.recordIds.map(identity))];
        if (entries.size + reservedExports >= MAX_OPERATIONS) fail('MEMORY_ARCHIVE_BUSY');
        reservedExports++;
        let entry;
        try {
          const backend = await store();
          if (!backend || typeof backend.exportArchive !== 'function') fail('MEMORY_NOT_INITIALIZED');
          entry = await temporary();
          const result = await backend.exportArchive({ path: entry.path, recordIds, passphrase: args.passphrase });
          const file = await open(entry.path, constants.O_RDONLY | constants.O_NOFOLLOW);
          let stat; try { stat = await file.stat(); } finally { await file.close(); }
          if (!stat.isFile() || stat.nlink !== 1 || stat.size < 1 || stat.size > MEMORY_ARCHIVE_MAX_BYTES) fail('MEMORY_EXPORT_TOO_LARGE');
          if (result?.encrypted !== true || result.recordCount !== recordIds.length) fail('MEMORY_ARCHIVE_INVALID');
          entry.id = randomBytes(32).toString('hex'); entry.expiresAt = Date.now() + MEMORY_ARCHIVE_TTL_MS;
          entry.identity = { ino: stat.ino, dev: stat.dev, size: stat.size }; entry.consumed = false;
          entries.set(entry.id, entry); scheduleRemoval(entry);
          return { archiveId: entry.id, downloadUrl: `/droy/memory/archive/download/${entry.id}`,
            expiresAt: entry.expiresAt, encrypted: true, recordCount: result.recordCount };
        } catch (error) {
          if (entry) await remove(entry);
          throw error;
        } finally { reservedExports--; }
      });
    },
    importArchive(args, context) {
      return run(context, async () => {
        fields(args, ['archiveBase64', 'passphrase']); password(args.passphrase);
        const bytes = decode(args.archiveBase64);
        const operationId = 'archive-import:' + createHash('sha256').update(bytes).digest('hex');
        const backend = await store();
        if (!backend || typeof backend.importArchive !== 'function') fail('MEMORY_NOT_INITIALIZED');
        const entry = await temporary();
        try {
          const file = await open(entry.path, constants.O_WRONLY | constants.O_CREAT | constants.O_EXCL | constants.O_NOFOLLOW, 0o600);
          try { await file.writeFile(bytes); await file.sync(); } finally { await file.close(); }
          return await backend.importArchive({ operationId, path: entry.path, passphrase: args.passphrase });
        } finally { await remove(entry); }
      });
    },
    downloadArchive(id, context) {
      return run(context, async () => {
        if (typeof id !== 'string' || !/^[a-f0-9]{64}$/.test(id)) fail('MEMORY_ARCHIVE_NOT_FOUND');
        const entry = entries.get(id);
        if (!entry || entry.consumed) fail('MEMORY_ARCHIVE_NOT_FOUND');
        if (entry.expiresAt <= Date.now()) { await remove(entry); fail('MEMORY_ARCHIVE_EXPIRED'); }
        entry.consumed = true;
        let file;
        try {
          file = await open(entry.path, constants.O_RDONLY | constants.O_NOFOLLOW);
          const stat = await file.stat();
          if (!stat.isFile() || stat.nlink !== 1 || stat.ino !== entry.identity.ino || stat.dev !== entry.identity.dev
            || stat.size !== entry.identity.size) fail('MEMORY_ARCHIVE_INVALID');
          if (entry.expiresAt <= Date.now()) fail('MEMORY_ARCHIVE_EXPIRED');
          entry.stream = file.createReadStream({ autoClose: true }); file = undefined;
          entry.stream.on('error', () => {});
          entry.stream.once('close', () => { void remove(entry).catch(() => { cleanupFailed = true; }); });
          return { stream: entry.stream, contentType: 'application/octet-stream',
            filename: 'droy-memory.droy-memory', contentLength: stat.size };
        } catch (error) {
          await file?.close(); await remove(entry); throw error;
        }
      });
    },
    close() {
      if (closePromise) return closePromise;
      closed = true;
      closePromise = (async () => {
        await Promise.allSettled([...pending]);
        const removed = await Promise.allSettled([...entries.values()].map(remove));
        if (cleanupFailed || removed.some(value => value.status === 'rejected')) fail('MEMORY_ARCHIVE_IO');
      })();
      return closePromise;
    },
  });
}
