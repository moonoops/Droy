import { constants } from 'node:fs';
import { lstat, open, realpath } from 'node:fs/promises';
import { join } from 'node:path';
import { pipeline } from 'node:stream/promises';

export const MEDIA_ROUTE = '/droy/media-input';
const uuid = /^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$/;

class MediaReadError extends Error {
  constructor(status, message) { super(message); this.status = status; }
}

async function directory(path) {
  const info = await lstat(path);
  if (!info.isDirectory() || info.isSymbolicLink())
    throw new MediaReadError(404, '附件不存在');
  return realpath(path);
}

async function file(path) {
  const handle = await open(path, constants.O_RDONLY | constants.O_NOFOLLOW);
  try {
    const stat = await handle.stat();
    if (!stat.isFile()) throw new MediaReadError(404, '附件不存在');
    return { handle, stat };
  } catch (error) { await handle.close(); throw error; }
}

/** Only native-published manifest members can be read; a URL never names a host path. */
export async function openMediaItem(root, importId, itemId) {
  if (!uuid.test(importId) || !uuid.test(itemId)) throw new MediaReadError(404, '附件不存在');
  const canonicalRoot = await directory(root);
  const folder = join(root, importId);
  if (await directory(folder) !== join(canonicalRoot, importId)) throw new MediaReadError(404, '附件不存在');
  const manifestFile = await file(join(folder, 'manifest.json'));
  let manifest;
  try {
    if (manifestFile.stat.size > 512 * 1024) throw new MediaReadError(422, '附件记录无效');
    manifest = JSON.parse(await manifestFile.handle.readFile('utf8'));
  } finally { await manifestFile.handle.close(); }
  if (manifest.id !== importId || !Array.isArray(manifest.items)) throw new MediaReadError(422, '附件记录无效');
  const item = manifest.items.find(value => value.id === itemId);
  if (!item || !Number.isSafeInteger(item.bytes) || item.bytes < 0 || item.bytes > 200 * 1024 * 1024)
    throw new MediaReadError(404, '附件不存在');
  const source = await file(join(folder, `${itemId}.blob`));
  if (source.stat.size !== item.bytes) {
    await source.handle.close();
    throw new MediaReadError(422, '附件内容不完整');
  }
  return { ...source, item };
}

export function createMediaHandler({ root, authorize }) {
  return async (req, res) => {
    if (!authorize(req, res)) return;
    if (req.method !== 'GET' && req.method !== 'HEAD') { res.writeHead(405, { Allow: 'GET, HEAD' }); res.end(); return; }
    let source;
    try {
      const pathname = new URL(req.url, 'http://127.0.0.1').pathname;
      const match = new RegExp(`^${MEDIA_ROUTE}/([a-f0-9-]+)/([a-f0-9-]+)$`).exec(pathname);
      if (!match || !root) throw new MediaReadError(404, '附件不存在');
      source = await openMediaItem(root, match[1], match[2]);
      res.writeHead(200, {
        'Content-Type': 'application/octet-stream',
        'Content-Length': String(source.stat.size),
        'Cache-Control': 'no-store',
        'X-Content-Type-Options': 'nosniff',
        'Content-Security-Policy': "default-src 'none'; sandbox",
      });
      if (req.method === 'HEAD') res.end();
      else await pipeline(source.handle.createReadStream({ autoClose: false }), res);
    } catch (error) {
      if (res.headersSent) res.destroy();
      else {
        const missing = ['ENOENT', 'ENOTDIR', 'ELOOP'].includes(error.code);
        res.writeHead(error.status ?? (missing ? 404 : 500), { 'Content-Type': 'text/plain; charset=utf-8', 'Cache-Control': 'no-store' });
        res.end(error instanceof MediaReadError ? error.message : missing ? '附件不存在' : '附件读取失败');
      }
    } finally { await source?.handle.close(); }
  };
}
