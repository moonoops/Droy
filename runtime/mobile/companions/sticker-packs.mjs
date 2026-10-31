import { createHash, randomUUID } from 'node:crypto';
import { mkdir, readdir, readFile, writeFile, rename, rm, lstat, open } from 'node:fs/promises';
import { join, basename, extname } from 'node:path';
import { DatabaseSync } from 'node:sqlite';
import { inflateRawSync } from 'node:zlib';
import { crc32 } from '../png.mjs';
import { moodTags, sampleRows, searchRows } from './vendor/dsh-meme/search.mjs';

export const STICKER_PACK_LIMITS = Object.freeze({ archiveBytes: 20 * 1024 * 1024, expandedBytes: 64 * 1024 * 1024,
  imageBytes: 8 * 1024 * 1024, images: 500, pixels: 16_000_000, dimension: 8192,
  gifFrames: 500, gifFramePixels: 128_000_000 });
export const BUILTIN_STICKER_PACK_ID = 'droy-default';
// Same-version market archives must not reuse earlier imports that discarded GIFs.
export const MARKET_STICKER_IMPORT_CAPABILITY = 'gif-v1';
export const ONLINE_STICKER_PACK_ID = 'pack-' + createHash('sha256').update('droy-online-stickers-v1').digest('hex').slice(0, 40);
const invalid = () => { throw Object.assign(new Error('COMPANION_STICKER_PACK_INVALID'), { code: 'COMPANION_STICKER_PACK_INVALID' }); };
const tooLarge = () => { throw Object.assign(new Error('COMPANION_STICKER_PACK_TOO_LARGE'), { code: 'COMPANION_STICKER_PACK_TOO_LARGE' }); };
const unsupported = () => { throw Object.assign(new Error('COMPANION_STICKER_PACK_UNSUPPORTED'), { code: 'COMPANION_STICKER_PACK_UNSUPPORTED' }); };
const digest = bytes => createHash('sha256').update(bytes).digest('hex');
const metadataLimit = 2 * 1024 * 1024;
function metadataBytes(pack) {
  const bytes = Buffer.from(JSON.stringify(pack));
  if (bytes.length > metadataLimit) tooLarge();
  return bytes;
}
const packPattern = /^pack-[a-f0-9]{40}$/u, stickerPattern = /^meme-[a-f0-9]{40}$/u;
const stagingPattern = /^\.import-[a-f0-9]{8}-[a-f0-9]{4}-4[a-f0-9]{3}-[89ab][a-f0-9]{3}-[a-f0-9]{12}$/u;
const builtinMoods = Object.freeze({ 'test-sticker-ok': 'happy', 'test-sticker-happy': 'happy',
  'test-sticker-curious': 'confused', 'sticker-support': 'happy', 'sticker-rest': 'daily',
  'sticker-surprised': 'confused', 'sticker-sad': 'sad', 'sticker-wave': 'daily', 'sticker-thinking': 'confused' });
const imageExtension = /\.(?:png|jpe?g|webp|gif|apng)$/iu;
const plain = value => value !== null && typeof value === 'object' && !Array.isArray(value);
function text(value, limit, fallback = '') {
  if (value == null || value === '') return fallback;
  if (typeof value !== 'string' || !value.isWellFormed() || [...value].length > limit || /[\u0000-\u0008\u000b\u000c\u000e-\u001f\u007f]/u.test(value)) invalid();
  return value.trim();
}
function safePath(value, directory = false) {
  if (typeof value !== 'string' || !value || Buffer.byteLength(value) > 1024 || value.includes('\\') || value.startsWith('/')
    || /[\u0000-\u001f\u007f:]/u.test(value) || !value.isWellFormed()) invalid();
  const path = directory && value.endsWith('/') ? value.slice(0, -1) : value;
  if (path.split('/').some(part => !part || part === '.' || part === '..')) invalid();
  return path;
}
function parseZip(data) {
  if (!Buffer.isBuffer(data)) data = Buffer.from(data ?? []);
  if (data.length > STICKER_PACK_LIMITS.archiveBytes) tooLarge();
  if (data.length < 22) invalid();
  let end = -1;
  for (let p = data.length - 22; p >= Math.max(0, data.length - 65557); p--) {
    if (data.readUInt32LE(p) === 0x06054b50 && p + 22 + data.readUInt16LE(p + 20) === data.length) { end = p; break; }
  }
  if (end < 0 || data.readUInt16LE(end + 4) || data.readUInt16LE(end + 6)) invalid();
  const count = data.readUInt16LE(end + 10), size = data.readUInt32LE(end + 12), start = data.readUInt32LE(end + 16);
  if (count !== data.readUInt16LE(end + 8) || count === 65535 || start + size !== end || count > 1100) invalid();
  const entries = new Map(), occupied = [], decoder = new TextDecoder('utf-8', { fatal: true });
  let p = start, total = 0;
  for (let i = 0; i < count; i++) {
    if (p + 46 > end || data.readUInt32LE(p) !== 0x02014b50) invalid();
    const flags = data.readUInt16LE(p + 8), method = data.readUInt16LE(p + 10), checksum = data.readUInt32LE(p + 16);
    const compressed = data.readUInt32LE(p + 20), expanded = data.readUInt32LE(p + 24), nameLength = data.readUInt16LE(p + 28);
    const next = p + 46 + nameLength + data.readUInt16LE(p + 30) + data.readUInt16LE(p + 32);
    const external = data.readUInt32LE(p + 38), local = data.readUInt32LE(p + 42);
    if (next > end || data.readUInt16LE(p + 34) || (flags & ~0x808) || ![0, 8].includes(method)
      || compressed === 0xffffffff || expanded === 0xffffffff || (external >>> 16 & 0xf000) === 0xa000) invalid();
    let name;
    try { name = decoder.decode(data.subarray(p + 46, p + 46 + nameLength)); } catch { invalid(); }
    const directory = name.endsWith('/');
    safePath(name, directory);
    if (entries.has(name) || directory && expanded !== 0) invalid();
    total += expanded;
    if (expanded > STICKER_PACK_LIMITS.expandedBytes || total > STICKER_PACK_LIMITS.expandedBytes) tooLarge();
    if (local + 30 > start || data.readUInt32LE(local) !== 0x04034b50
      || data.readUInt16LE(local + 6) !== flags || data.readUInt16LE(local + 8) !== method) invalid();
    const localNameLength = data.readUInt16LE(local + 26), bodyStart = local + 30 + localNameLength + data.readUInt16LE(local + 28);
    if (bodyStart + compressed > start || !data.subarray(local + 30, local + 30 + localNameLength).equals(data.subarray(p + 46, p + 46 + nameLength))) invalid();
    if (!(flags & 8) && (data.readUInt32LE(local + 14) !== checksum || data.readUInt32LE(local + 18) !== compressed || data.readUInt32LE(local + 22) !== expanded)) invalid();
    const segment = [local, bodyStart + compressed];
    if (occupied.some(([a, b]) => segment[0] < b && segment[1] > a)) invalid();
    occupied.push(segment);
    let bytes;
    try { bytes = method === 0 ? data.subarray(bodyStart, bodyStart + compressed)
      : inflateRawSync(data.subarray(bodyStart, bodyStart + compressed), { maxOutputLength: Math.max(1, expanded) }); }
    catch (error) { if (error.code === 'ERR_BUFFER_TOO_LARGE') tooLarge(); invalid(); }
    if (bytes.length !== expanded || crc32(bytes) !== checksum) invalid();
    entries.set(name, { bytes, directory }); p = next;
  }
  if (p !== end) invalid();
  return entries;
}
function dimensions(width, height) {
  if (!width || !height) invalid();
  if (width > STICKER_PACK_LIMITS.dimension || height > STICKER_PACK_LIMITS.dimension || width * height > STICKER_PACK_LIMITS.pixels) tooLarge();
}
function pngInfo(data) {
  let p = 8, width, height, ended = false, hasData = false;
  while (p < data.length) {
    if (p + 12 > data.length) invalid();
    const size = data.readUInt32BE(p), end = p + size + 12, type = data.toString('ascii', p + 4, p + 8);
    if (end > data.length || crc32(data.subarray(p + 4, end - 4)) !== data.readUInt32BE(end - 4)) invalid();
    if (type === 'acTL' || type === 'fcTL' || type === 'fdAT') unsupported();
    if (width === undefined && type !== 'IHDR') invalid();
    if (type === 'IHDR') {
      if (width !== undefined || size !== 13) invalid();
      width = data.readUInt32BE(p + 8); height = data.readUInt32BE(p + 12); dimensions(width, height);
    } else if (type === 'IDAT') hasData = true;
    else if (type === 'IEND') { if (size || end !== data.length) invalid(); ended = true; break; }
    p = end;
  }
  if (!ended || !hasData) invalid();
  return { mediaType: 'image/png', extension: 'png' };
}
function jpegInfo(data) {
  let p = 2, found = false;
  while (p + 4 <= data.length) {
    if (data[p++] !== 0xff) invalid();
    while (p < data.length && data[p] === 0xff) p++;
    if (p + 3 > data.length) invalid();
    const marker = data[p++];
    if (marker === 0xda) break;
    if ([0xd8, 0xd9, 0x00].includes(marker)) invalid();
    const length = data.readUInt16BE(p);
    if (length < 2 || p + length > data.length) invalid();
    if ([0xc0, 0xc1, 0xc2, 0xc3, 0xc5, 0xc6, 0xc7, 0xc9, 0xca, 0xcb, 0xcd, 0xce, 0xcf].includes(marker)) {
      if (length < 8) invalid();
      dimensions(data.readUInt16BE(p + 5), data.readUInt16BE(p + 3)); found = true;
    }
    p += length;
  }
  if (!found || data.length < 4 || data.readUInt16BE(data.length - 2) !== 0xffd9) invalid();
  return { mediaType: 'image/jpeg', extension: 'jpg' };
}
function webpInfo(data) {
  if (data.readUInt32LE(4) + 8 !== data.length) invalid();
  let p = 12, width, height, image = false;
  while (p < data.length) {
    if (p + 8 > data.length) invalid();
    const type = data.toString('ascii', p, p + 4), length = data.readUInt32LE(p + 4), body = p + 8;
    if (body + length > data.length) invalid();
    if (type === 'ANIM' || type === 'ANMF') unsupported();
    if (type === 'VP8X') {
      if (length !== 10) invalid();
      if (data[body] & 2) unsupported();
      width = 1 + data.readUIntLE(body + 4, 3); height = 1 + data.readUIntLE(body + 7, 3); dimensions(width, height);
    } else if (type === 'VP8 ') {
      if (length < 10 || !data.subarray(body + 3, body + 6).equals(Buffer.from([0x9d, 1, 0x2a]))) invalid();
      const w = data.readUInt16LE(body + 6) & 0x3fff, h = data.readUInt16LE(body + 8) & 0x3fff;
      dimensions(w, h); if (width !== undefined && (w > width || h > height)) invalid(); image = true;
    } else if (type === 'VP8L') {
      if (length < 5 || data[body] !== 0x2f) invalid();
      const bits = data.readUInt32LE(body + 1), w = (bits & 0x3fff) + 1, h = ((bits >>> 14) & 0x3fff) + 1;
      dimensions(w, h); if (width !== undefined && (w > width || h > height)) invalid(); image = true;
    }
    p = body + length + (length & 1);
  }
  if (p !== data.length || !image) invalid();
  return { mediaType: 'image/webp', extension: 'webp' };
}
function gifInfo(data) {
  if (data.length < 14 || !['GIF87a', 'GIF89a'].includes(data.toString('ascii', 0, 6))) invalid();
  const width = data.readUInt16LE(6), height = data.readUInt16LE(8);
  dimensions(width, height);
  const globalColors = data[10] & 0x80 ? 1 << ((data[10] & 7) + 1) : 0;
  let p = 13 + globalColors * 3, frameCount = 0, control = null;
  // Some market encoders use an unused 0xff background index with a smaller palette.
  // Check color-table byte bounds here; background rendering belongs to the decoder.
  if (p >= data.length) invalid();
  const subBlocks = (required = false) => {
    let bytes = 0;
    while (p < data.length) {
      const size = data[p++];
      if (!size) { if (required && !bytes) invalid(); return; }
      if (p + size > data.length) invalid();
      bytes += size; p += size;
    }
    invalid();
  };
  while (p < data.length) {
    const block = data[p++];
    if (block === 0x3b) {
      if (!frameCount || p !== data.length || control) invalid();
      return { mediaType: 'image/gif', extension: 'gif', animated: frameCount > 1, frameCount };
    }
    if (block === 0x21) {
      if (p >= data.length) invalid();
      const label = data[p++];
      if (label === 0xf9) {
        if (p + 6 > data.length || data[p] !== 4 || data[p + 5] !== 0 || data[p + 1] & 0xe0
          || (data[p + 1] >> 2 & 7) > 3 || control) invalid();
        control = { transparent: !!(data[p + 1] & 1), index: data[p + 4] }; p += 6;
      } else if (label === 0xff) {
        if (p + 12 > data.length || data[p] !== 11) invalid();
        p += 12; subBlocks();
      } else if (label === 0xfe) subBlocks();
      else if (label === 0x01) {
        if (p + 13 > data.length || data[p] !== 12 || !globalColors || !data[p + 9] || !data[p + 10]
          || data[p + 11] >= globalColors || data[p + 12] >= globalColors
          || data.readUInt16LE(p + 1) + data.readUInt16LE(p + 5) > width
          || data.readUInt16LE(p + 3) + data.readUInt16LE(p + 7) > height) invalid();
        if (control?.transparent && control.index >= globalColors) invalid();
        p += 13; subBlocks(); control = null;
      } else invalid();
      continue;
    }
    if (block !== 0x2c || p + 9 > data.length) invalid();
    const left = data.readUInt16LE(p), top = data.readUInt16LE(p + 2), frameWidth = data.readUInt16LE(p + 4), frameHeight = data.readUInt16LE(p + 6), packed = data[p + 8];
    dimensions(frameWidth, frameHeight);
    if (left + frameWidth > width || top + frameHeight > height || packed & 0x18) invalid();
    const localColors = packed & 0x80 ? 1 << ((packed & 7) + 1) : 0;
    const colors = localColors || globalColors;
    p += 9 + localColors * 3;
    if (!colors || p >= data.length || data[p] < 2 || data[p] > 8 || control?.transparent && control.index >= colors) invalid();
    p++;
    subBlocks(true); control = null;
    // Limit repeated canvas work as well as compressed bytes; many tiny frames can otherwise be expensive.
    frameCount++;
    if (frameCount > STICKER_PACK_LIMITS.gifFrames || width * height * frameCount > STICKER_PACK_LIMITS.gifFramePixels) tooLarge();
  }
  invalid();
}
function imageInfo(data) {
  if (!data.length) invalid();
  if (data.length > STICKER_PACK_LIMITS.imageBytes) tooLarge();
  if (data.length >= 8 && data.subarray(0, 8).equals(Buffer.from([137,80,78,71,13,10,26,10]))) return pngInfo(data);
  if (data.length >= 4 && data[0] === 0xff && data[1] === 0xd8) return jpegInfo(data);
  if (data.length >= 12 && data.toString('ascii', 0, 4) === 'RIFF' && data.toString('ascii', 8, 12) === 'WEBP') return webpInfo(data);
  if (data.toString('ascii', 0, 3) === 'GIF') return gifInfo(data);
  invalid();
}
/** Validate the entire archive before exposing a single indexed image. */
export function stickerArchiveImage(bytes, path) {
  const entry = parseZip(bytes).get(safePath(path));
  if (!entry || entry.directory) invalid();
  imageInfo(entry.bytes);
  return Buffer.from(entry.bytes);
}
async function readIndex(bytes, staging, entries) {
  const path = join(staging, 'source-index.db');
  await writeFile(path, bytes, { mode: 0o600, flag: 'wx' });
  let db;
  try {
    db = new DatabaseSync(path, { readOnly: true, allowExtension: false });
    db.exec('PRAGMA trusted_schema=OFF; PRAGMA query_only=ON;');
    if (db.prepare("SELECT type FROM sqlite_master WHERE name='memes'").get()?.type !== 'table') invalid();
    const rows = db.prepare("SELECT path, tag, file_name, caption, COALESCE(keywords, '') AS keywords FROM memes LIMIT 501").all();
    if (!rows.length) invalid();
    if (rows.length > STICKER_PACK_LIMITS.images) tooLarge();
    const seen = new Set();
    return rows.map(row => {
      const source = safePath(row.path);
      if (seen.has(source) || !entries.has(source) || !imageExtension.test(source)) invalid();
      seen.add(source);
      return { source, tag: text(row.tag, 80), label: text(row.caption, 200, basename(source, extname(source))),
        description: text(row.caption, 200, basename(source, extname(source))), keywords: text(row.keywords, 1000), file_name: text(row.file_name, 255, basename(source)) };
    });
  } catch (error) {
    if (error.code?.startsWith('COMPANION_')) throw error;
    invalid();
  } finally { db?.close(); await rm(path, { force: true }); }
}
async function durableWrite(path, data) {
  const fd = await open(path, 'wx', 0o600);
  try { await fd.writeFile(data); await fd.sync(); } finally { await fd.close(); }
}

export class StickerPackLibrary {
  constructor(directory, { builtinCatalog = () => [] } = {}) {
    this.directory = directory; this.builtinCatalog = builtinCatalog; this.imported = new Map(); this.queue = Promise.resolve();
  }
  async open() {
    await mkdir(this.directory, { recursive: true, mode: 0o700 });
    const names = await readdir(this.directory);
    for (const name of names.filter(name => stagingPattern.test(name))) {
      const path = join(this.directory, name), info = await lstat(path);
      if (info.isDirectory() && !info.isSymbolicLink()) await rm(path, { recursive: true, force: true });
    }
    for (const name of names.filter(name => packPattern.test(name)).sort()) {
      const folder = join(this.directory, name), folderInfo = await lstat(folder);
      if (!folderInfo.isDirectory() || folderInfo.isSymbolicLink()) invalid();
      const raw = await readFile(join(folder, 'metadata.json'));
      if (raw.length > metadataLimit) tooLarge();
      let pack;
      try { pack = JSON.parse(raw); } catch { invalid(); }
      if (!plain(pack) || pack.schemaVersion !== 1 || pack.id !== name || !Array.isArray(pack.items)
        || !pack.items.length || pack.items.length > STICKER_PACK_LIMITS.images) invalid();
      text(pack.name, 80); text(pack.license, 200); text(pack.source, 1000); text(pack.description, 500);
      text(pack.originId, 200); text(pack.originVersion, 100); text(pack.importCapability, 80); const seen = new Set();
      for (const item of pack.items) {
        if (!stickerPattern.test(item.id) || seen.has(item.id) || !/^[a-f0-9]{64}$/u.test(item.digest)
          || !['image/png', 'image/jpeg', 'image/webp', 'image/gif'].includes(item.mediaType)
          || !['png', 'jpg', 'webp', 'gif'].includes(item.extension)) invalid();
        seen.add(item.id); text(item.label, 200); text(item.description, 200); text(item.tag, 80); text(item.keywords, 1000);
        text(item.source, 1000); text(item.license, 200);
        const info = await lstat(join(folder, item.id + '.' + item.extension));
        if (!info.isFile() || info.isSymbolicLink() || info.size !== item.bytes || info.size > STICKER_PACK_LIMITS.imageBytes) invalid();
      }
      this.imported.set(name, pack);
    }
    return this;
  }
  packs(locale = 'zh') {
    return [{ id: BUILTIN_STICKER_PACK_ID, name: locale === 'en' ? 'Droy stickers' : 'Droy 表情', count: this.builtinCatalog(locale).length, builtin: true },
      ...[...this.imported.values()].map(pack => ({ id: pack.id,
        name: pack.kind === 'online' ? (locale === 'en' ? 'Online stickers' : '联网表情') : pack.name,
        count: pack.items.length, builtin: false, license: pack.license ?? '', source: pack.source ?? '', description: pack.description ?? '',
        ...(pack.originId ? { originId: pack.originId, originVersion: pack.originVersion ?? '' } : {}),
        ...(pack.importCapability ? { importCapability: pack.importCapability } : {}),
        ...(pack.skippedAnimated ? { skippedAnimated: pack.skippedAnimated } : {}) }))];
  }
  catalog(locale = 'zh', { packIds } = {}) {
    const enabled = packIds === undefined ? null : new Set(packIds);
    const result = enabled && !enabled.has(BUILTIN_STICKER_PACK_ID) ? [] : this.builtinCatalog(locale).map(item => ({ ...item,
      tags: [builtinMoods[item.id], ...(item.tags ?? [])].filter(Boolean), packId: BUILTIN_STICKER_PACK_ID }));
    for (const pack of this.imported.values()) {
      if (enabled && !enabled.has(pack.id)) continue;
      result.push(...pack.items.map(item => ({ id: item.id, url: '/droy/companions/sticker-media/' + item.id, label: item.label,
        description: item.description, tags: [item.tag, ...item.keywords.split(/[\s,，、;；/|]+/u)].filter(Boolean), packId: pack.id,
        ...(item.source ? { source: item.source } : {}), ...(item.license ? { license: item.license } : {}) })));
    }
    return structuredClone(result);
  }
  search(locale = 'zh', { packIds, query = '', tag = '', limit = 8 } = {}) {
    const n = Math.max(1, Math.min(Number(limit) || 8, 30)), t = String(tag).trim().toLowerCase();
    const tags = t ? moodTags[t] ?? [t] : null;
    let rows = this.catalog(locale, { packIds }).map(item => ({ ...item, tag: item.tags?.[0] ?? '', caption: item.description,
      keywords: item.tags?.join(' ') ?? '', file_name: item.label }));
    if (tags) rows = rows.filter(row => row.tags?.some(tag => tags.includes(tag.toLowerCase())));
    const found = String(query).trim() ? searchRows(rows, query, n) : sampleRows(rows, n);
    return found.map(({ tag, caption, keywords, file_name, ...item }) => item);
  }
  async importZip(input, { name, signal, skipUnsupported = false, provenance = {} } = {}) {
    signal?.throwIfAborted();
    const bytes = Buffer.from(input ?? []);
    const work = this.queue.then(() => this.install(bytes, name, signal, { skipUnsupported, provenance }));
    this.queue = work.catch(() => {}); return work;
  }
  async install(bytes, suppliedName, signal, { skipUnsupported = false, provenance = {} } = {}) {
    signal?.throwIfAborted();
    const entries = parseZip(bytes), id = 'pack-' + digest(skipUnsupported
      ? Buffer.concat([Buffer.from(MARKET_STICKER_IMPORT_CAPABILITY + '\0'), bytes]) : bytes).slice(0, 40);
    if (this.imported.has(id)) return this.packs().find(pack => pack.id === id);
    const images = [...entries].filter(([path, entry]) => !entry.directory && imageExtension.test(path));
    if (!images.length) invalid();
    if (images.length > STICKER_PACK_LIMITS.images) tooLarge();
    const infos = new Map();
    let skippedAnimated = 0;
    for (const [path, entry] of images) {
      try {
        if (/\.apng$/iu.test(path)) unsupported();
        infos.set(path, imageInfo(entry.bytes));
      } catch (error) {
        if (!skipUnsupported || error.code !== 'COMPANION_STICKER_PACK_UNSUPPORTED') throw error;
        skippedAnimated++;
      }
    }
    if (!infos.size) unsupported();
    let manifest = {};
    if (entries.has('manifest.json')) {
      if (entries.get('manifest.json').bytes.length > 65536) tooLarge();
      try { manifest = JSON.parse(entries.get('manifest.json').bytes.toString('utf8')); } catch { invalid(); }
      if (!plain(manifest)) invalid();
    }
    const filename = suppliedName == null ? '' : text(suppliedName, 1024);
    if (Buffer.byteLength(filename) > 1024) invalid();
    const fallbackName = [...filename.replace(/\.zip$/iu, '')].slice(0, 80).join('');
    const name = text(manifest.name, 80, fallbackName || id);
    const staging = join(this.directory, '.import-' + randomUUID());
    await mkdir(staging, { mode: 0o700 });
    try {
      const rows = entries.has('index.db') ? await readIndex(entries.get('index.db').bytes, staging, entries)
        : images.map(([source]) => ({ source, label: basename(source, extname(source)), description: basename(source, extname(source)),
          tag: source.includes('/') ? source.split('/').at(-2) : 'daily', keywords: '', file_name: basename(source) }));
      const items = [];
      for (const row of rows) {
        signal?.throwIfAborted();
        if (!infos.has(row.source)) continue;
        const data = entries.get(row.source).bytes, info = infos.get(row.source), stickerId = 'meme-' + digest(id + '\0' + row.source).slice(0, 40);
        const item = { id: stickerId, label: text(row.label, 200), description: text(row.description, 200), tag: text(row.tag, 80),
          keywords: text(row.keywords, 1000), ...info, bytes: data.length, digest: digest(data) };
        await durableWrite(join(staging, item.id + '.' + info.extension), data); items.push(item);
      }
      if (!items.length) unsupported();
      const pack = { schemaVersion: 1, id, name, items, license: text(manifest.license, 200, text(provenance.license, 200)),
        source: text(manifest.source, 1000, text(provenance.source, 1000)), description: text(manifest.description, 500),
        ...(provenance.originId ? { originId: text(provenance.originId, 200), originVersion: text(provenance.originVersion, 100) } : {}),
        ...(skipUnsupported ? { importCapability: MARKET_STICKER_IMPORT_CAPABILITY } : {}),
        ...(skippedAnimated ? { skippedAnimated } : {}) };
      await durableWrite(join(staging, 'metadata.json'), metadataBytes(pack));
      signal?.throwIfAborted();
      await rename(staging, join(this.directory, id));
      const fd = await open(this.directory, 'r'); try { await fd.sync(); } finally { await fd.close(); }
      this.imported.set(id, pack);
      return this.packs().find(pack => pack.id === id);
    } finally { await rm(staging, { recursive: true, force: true }); }
  }
  async importOnlineImage(input, { signal, locale = 'zh' } = {}) {
    signal?.throwIfAborted();
    const bytes = Buffer.from(input.bytes ?? []), info = imageInfo(bytes), hash = digest(bytes);
    const metadata = { label: text(input.label, 200), description: text(input.description, 200), tag: text(input.tag, 80),
      keywords: text(input.keywords, 1000), source: text(input.source, 1000), license: text(input.license, 200) };
    const work = this.queue.then(async () => {
      signal?.throwIfAborted();
      const previous = this.imported.get(ONLINE_STICKER_PACK_ID), duplicate = previous?.items.find(item => item.digest === hash);
      if (duplicate) return { pack: this.packs(locale).find(pack => pack.id === ONLINE_STICKER_PACK_ID),
        sticker: this.catalog(locale, { packIds: [ONLINE_STICKER_PACK_ID] }).find(item => item.id === duplicate.id) };
      const items = previous?.items ?? [];
      if (items.length >= STICKER_PACK_LIMITS.images || items.reduce((sum, item) => sum + item.bytes, bytes.length) > STICKER_PACK_LIMITS.expandedBytes) tooLarge();
      const id = 'meme-' + digest(ONLINE_STICKER_PACK_ID + '\0' + hash).slice(0, 40);
      const item = { id, ...metadata, ...info, bytes: bytes.length, digest: hash };
      const pack = { schemaVersion: 1, id: ONLINE_STICKER_PACK_ID, kind: 'online', name: 'Online stickers',
        license: '', source: '', description: '', items: [...items, item] };
      const folder = join(this.directory, pack.id), staging = join(this.directory, '.import-' + randomUUID());
      await mkdir(staging, { mode: 0o700 });
      let imagePublished = false, committed = false;
      try {
        await durableWrite(join(staging, id + '.' + info.extension), bytes);
        await durableWrite(join(staging, 'metadata.json'), metadataBytes(pack));
        signal?.throwIfAborted();
        if (!previous) await rename(staging, folder);
        else {
          await rename(join(staging, id + '.' + info.extension), join(folder, id + '.' + info.extension));
          imagePublished = true;
          signal?.throwIfAborted();
          await rename(join(staging, 'metadata.json'), join(folder, 'metadata.json'));
        }
        committed = true;
        this.imported.set(pack.id, pack);
        const fd = await open(folder, 'r'); try { await fd.sync(); } finally { await fd.close(); }
        return { pack: this.packs(locale).find(row => row.id === pack.id),
          sticker: this.catalog(locale, { packIds: [pack.id] }).find(row => row.id === id) };
      } finally {
        if (imagePublished && !committed) await rm(join(folder, id + '.' + info.extension), { force: true });
        if (!previous && !committed) await rm(folder, { recursive: true, force: true });
        await rm(staging, { recursive: true, force: true });
      }
    });
    this.queue = work.catch(() => {});
    return work;
  }
  async importRemotePack(images, { name, signal, provenance = {} } = {}) {
    signal?.throwIfAborted();
    if (!Array.isArray(images) || !images.length) invalid();
    if (images.length > STICKER_PACK_LIMITS.images) tooLarge();
    let size = 0, skippedAnimated = 0;
    const rows = [];
    for (const input of images) {
      const bytes = Buffer.from(input.bytes ?? []);
      size += bytes.length;
      if (size > STICKER_PACK_LIMITS.expandedBytes) tooLarge();
      let info;
      try { info = imageInfo(bytes); }
      catch (error) { if (error.code !== 'COMPANION_STICKER_PACK_UNSUPPORTED') throw error; skippedAnimated++; continue; }
      rows.push({ data: bytes, digest: digest(bytes), info, label: text(input.label, 200), description: text(input.description, 200),
        tag: text(input.tag, 80), keywords: text(input.keywords, 1000), source: text(input.source, 1000), license: text(input.license, 200) });
    }
    if (!rows.length) unsupported();
    const details = { name: text(name, 80), license: text(provenance.license, 200), source: text(provenance.source, 1000),
      originId: text(provenance.originId, 200), originVersion: text(provenance.originVersion, 100),
      importCapability: MARKET_STICKER_IMPORT_CAPABILITY, skippedAnimated };
    const id = 'pack-' + digest(JSON.stringify({ ...details, images: rows.map(({ data, info, ...row }) => row) })).slice(0, 40);
    const work = this.queue.then(async () => {
      signal?.throwIfAborted();
      if (this.imported.has(id)) return this.packs().find(pack => pack.id === id);
      const staging = join(this.directory, '.import-' + randomUUID());
      await mkdir(staging, { mode: 0o700 });
      try {
        const items = [];
        for (const [index, row] of rows.entries()) {
          signal?.throwIfAborted();
          const { data, info, ...metadata } = row;
          const stickerId = 'meme-' + digest(id + '\0' + index).slice(0, 40);
          await durableWrite(join(staging, stickerId + '.' + info.extension), data);
          items.push({ id: stickerId, ...metadata, ...info, bytes: data.length });
        }
        const pack = { schemaVersion: 1, id, ...details, description: '', items };
        await durableWrite(join(staging, 'metadata.json'), metadataBytes(pack));
        signal?.throwIfAborted();
        await rename(staging, join(this.directory, id));
        this.imported.set(id, pack);
        const fd = await open(this.directory, 'r'); try { await fd.sync(); } finally { await fd.close(); }
        return this.packs().find(pack => pack.id === id);
      } finally { await rm(staging, { recursive: true, force: true }); }
    });
    this.queue = work.catch(() => {}); return work;
  }
  async read(stickerId) {
    if (typeof stickerId !== 'string' || !stickerPattern.test(stickerId)) return null;
    for (const pack of this.imported.values()) {
      const item = pack.items.find(row => row.id === stickerId);
      if (!item) continue;
      const path = join(this.directory, pack.id, item.id + '.' + item.extension), info = await lstat(path);
      if (!info.isFile() || info.isSymbolicLink()) invalid();
      const data = await readFile(path);
      if (data.length !== item.bytes || digest(data) !== item.digest) invalid();
      return { data, mediaType: item.mediaType };
    }
    return null;
  }
}
