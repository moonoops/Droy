import { createHash } from 'node:crypto';
import { lookup } from 'node:dns/promises';
import { request } from 'node:https';
import { BlockList, isIP } from 'node:net';
import { Readable } from 'node:stream';
import { STICKER_PACK_LIMITS, MARKET_STICKER_IMPORT_CAPABILITY, stickerArchiveImage } from './sticker-packs.mjs';
import { moodTags, searchRows } from './vendor/dsh-meme/search.mjs';

const base = 'https://raw.githubusercontent.com/yyh-001/dsh-meme-packs/main/';
export const ONLINE_STICKER_CATALOG_URL = base + 'catalog.json';
export const ONLINE_STICKER_INDEX_URL = base + 'stickers.json';
const source = 'dsh-meme-packs';
const providerHosts = ['raw.githubusercontent.com', 'github.com', 'release-assets.githubusercontent.com', 'objects.githubusercontent.com', 'img.aigengtu.com'];
const fail = suffix => { throw Object.assign(new Error('COMPANION_STICKER_ONLINE_' + suffix), { code: 'COMPANION_STICKER_ONLINE_' + suffix }); };
const hash = value => createHash('sha256').update(value).digest('hex');
const object = value => value !== null && typeof value === 'object' && !Array.isArray(value);
const clipped = (value, limit = 200) => typeof value === 'string' ? [...value.replace(/[\u0000-\u001f\u007f]/gu, ' ').trim()].slice(0, limit).join('') : '';
const strings = value => Array.isArray(value) ? value.filter(item => typeof item === 'string') : [];
const idPattern = /^[a-zA-Z0-9][a-zA-Z0-9_.-]{0,199}$/u;
const denied4 = new BlockList(), denied6 = new BlockList();
for (const [ip, bits] of [['0.0.0.0', 8], ['10.0.0.0', 8], ['100.64.0.0', 10], ['127.0.0.0', 8],
  ['169.254.0.0', 16], ['172.16.0.0', 12], ['192.0.0.0', 24], ['192.0.2.0', 24], ['192.168.0.0', 16],
  ['198.18.0.0', 15], ['198.51.100.0', 24], ['203.0.113.0', 24], ['224.0.0.0', 4], ['240.0.0.0', 4]]) denied4.addSubnet(ip, bits, 'ipv4');
for (const [ip, bits] of [['::', 128], ['::1', 128], ['::ffff:0:0', 96], ['64:ff9b::', 96], ['64:ff9b:1::', 48],
  ['100::', 64], ['2001:db8::', 32], ['2001::', 32], ['2002::', 16], ['fc00::', 7], ['fe80::', 10], ['ff00::', 8]]) denied6.addSubnet(ip, bits, 'ipv6');
const publicIp = ip => isIP(ip) === 4 ? !denied4.check(ip, 'ipv4') : isIP(ip) === 6 && !denied6.check(ip, 'ipv6');

function publicUrl(value) {
  let url;
  try { url = new URL(value); } catch { fail('INVALID'); }
  const hostname = url.hostname.replace(/^\[|\]$/gu, '').replace(/\.$/u, '').toLowerCase();
  if (url.protocol !== 'https:' || url.username || url.password || url.hash || url.port && url.port !== '443'
    || !hostname || /(?:^|\.)(?:localhost|local|internal|lan|home|test|invalid)$/u.test(hostname)
    || isIP(hostname) && !publicIp(hostname)) fail('INVALID');
  return url;
}
function archivePath(value) {
  if (typeof value !== 'string' || !value || value.length > 1024 || value.startsWith('/') || /[\\:\u0000-\u001f\u007f]/u.test(value)
    || value.split('/').some(part => !part || part === '.' || part === '..')) fail('INVALID');
  return value;
}
function inputQuery(query, allowEmpty = false) {
  if (typeof query !== 'string' || [...query].length > 200 || !query.trim() && !allowEmpty) fail('INVALID');
  return query.trim();
}
function needsArchive(item) {
  return !item.imageUrl || item.format === 'gif' && !/\.gif$/iu.test(publicUrl(item.imageUrl).pathname);
}
function aborted(promise, signal) {
  signal.throwIfAborted();
  return new Promise((resolve, reject) => {
    const abort = () => reject(signal.reason);
    signal.addEventListener('abort', abort, { once: true });
    promise.then(resolve, reject).finally(() => signal.removeEventListener('abort', abort));
  });
}
async function pinnedFetch(url, options, addresses) {
  return new Promise((resolve, reject) => {
    const outgoing = request(url, { ...options, agent: false, lookup(_hostname, settings, done) {
      const rows = settings.family ? addresses.filter(row => row.family === settings.family) : addresses;
      if (!rows.length) return done(Object.assign(new Error('No validated address'), { code: 'ENOTFOUND' }));
      if (settings.all) done(null, rows);
      else done(null, rows[0].address, rows[0].family);
    } }, incoming => {
      const headers = new Headers();
      for (const [key, value] of Object.entries(incoming.headers)) if (value !== undefined) headers.set(key, Array.isArray(value) ? value.join(', ') : value);
      resolve({ status: incoming.statusCode, headers, body: Readable.toWeb(incoming) });
    });
    outgoing.once('error', reject); outgoing.end();
  });
}

/** Public catalog lookup and bounded downloads; conversation text never leaves the device. */
export class OnlineStickerService {
  constructor({ library, fetchImpl, resolve = lookup, timeoutMs = 60000, allowedHosts = providerHosts } = {}) {
    this.library = library; this.fetchImpl = fetchImpl; this.resolve = resolve; this.timeoutMs = timeoutMs;
    this.cache = new Map(); this.allowedHosts = new Set(allowedHosts);
  }
  async bytes(value, maximum, signal) {
    const timeout = AbortSignal.timeout(this.timeoutMs), combined = signal ? AbortSignal.any([signal, timeout]) : timeout;
    let url = publicUrl(value);
    try {
      for (let hop = 0; hop <= 4; hop++) {
        combined.throwIfAborted();
        if (!this.allowedHosts.has(url.hostname.toLowerCase())) fail('INVALID');
        const options = { method: 'GET', redirect: 'manual', signal: combined,
          headers: { 'user-agent': 'DroyAgent/online-stickers', accept: '*/*', 'accept-encoding': 'identity' } };
        let response;
        const route = this.fetchImpl ? { proxied: false } : (await import('@deepseek-ai/dsh-http-proxy')).proxyRouteFor(url);
        if (route.proxied) {
          // Preserve DSH's configured proxy; the proxy owns DNS for this route, as in dsh-web-fetch-http.
          const { fetch } = await import('undici');
          response = await fetch(url, { ...options, dispatcher: route.dispatcher });
        } else {
          const hostname = url.hostname.replace(/^\[|\]$/gu, '');
          const addresses = isIP(hostname) ? [{ address: hostname, family: isIP(hostname) }]
            : await aborted(Promise.resolve(this.resolve(hostname, { all: true, verbatim: true })), combined);
          if (!addresses.length || addresses.some(row => !publicIp(row.address))) fail('INVALID');
          response = this.fetchImpl ? await this.fetchImpl(url.toString(), options) : await pinnedFetch(url, options, addresses);
        }
        if ([301, 302, 303, 307, 308].includes(response.status)) {
          const location = response.headers.get('location'); await response.body?.cancel();
          if (!location || hop === 4) fail('INVALID');
          url = publicUrl(new URL(location, url).toString()); continue;
        }
        if (response.status < 200 || response.status >= 300) { await response.body?.cancel(); fail('NETWORK'); }
        if (Number(response.headers.get('content-length')) > maximum) {
          await response.body?.cancel(); throw Object.assign(new Error('COMPANION_STICKER_PACK_TOO_LARGE'), { code: 'COMPANION_STICKER_PACK_TOO_LARGE' });
        }
        const reader = response.body?.getReader();
        if (!reader) fail('NETWORK');
        const chunks = []; let count = 0;
        try {
          while (true) {
            const result = await aborted(reader.read(), combined);
            if (result.done) break;
            count += result.value.length;
            if (count > maximum) throw Object.assign(new Error('COMPANION_STICKER_PACK_TOO_LARGE'), { code: 'COMPANION_STICKER_PACK_TOO_LARGE' });
            chunks.push(Buffer.from(result.value));
          }
        } finally { await reader.cancel(); reader.releaseLock(); }
        combined.throwIfAborted(); return Buffer.concat(chunks, count);
      }
      fail('INVALID');
    } catch (error) {
      if (combined.aborted) throw combined.reason;
      if (error.code?.startsWith('COMPANION_STICKER_')) throw error;
      fail('NETWORK');
    }
  }
  async json(url, maximum, signal) {
    const entry = this.cache.get(url);
    signal?.throwIfAborted();
    if (entry && Date.now() - entry.time < 300000) return entry.value;
    let value;
    try { value = JSON.parse((await this.bytes(url, maximum, signal)).toString('utf8')); }
    catch (error) { if (error instanceof SyntaxError) fail('INVALID'); throw error; }
    if (!object(value)) fail('INVALID');
    this.cache.set(url, { value, time: Date.now() }); return value;
  }
  async catalog(signal) {
    const data = await this.json(ONLINE_STICKER_CATALOG_URL, 2 * 1024 * 1024, signal);
    if (data.schemaVersion !== 1 || !Array.isArray(data.packs) || data.packs.length > 1000) fail('INVALID');
    for (const pack of data.packs) if (!object(pack) || !idPattern.test(pack.id) || typeof pack.name !== 'string') fail('INVALID');
    return data.packs;
  }
  async market({ query = '', signal } = {}) {
    query = inputQuery(query, true);
    const packs = await this.catalog(signal);
    const rows = packs.map(pack => ({ ...pack, file_name: pack.name, caption: pack.description, tag: '', keywords: strings(pack.keywords).join(' ') }));
    const selected = query ? searchRows(rows, query, 50) : rows;
    return { source, packs: selected.map(pack => ({ id: pack.id, name: clipped(pack.name, 80), description: clipped(pack.description, 500),
      count: Number.isSafeInteger(pack.count) && pack.count > 0 ? pack.count : 0,
      bytes: Number.isSafeInteger(pack.bytes) && pack.bytes > 0 ? pack.bytes : 0, license: clipped(pack.license), version: clipped(pack.version, 100),
      source: clipped(pack.homepage || 'https://github.com/yyh-001/dsh-meme-packs', 1000) })) };
  }
  async index(signal) {
    const data = await this.json(ONLINE_STICKER_INDEX_URL, 4 * 1024 * 1024, signal);
    if (data.schemaVersion !== 1 || !Array.isArray(data.stickers) || data.stickers.length > 20000 || !object(data.packs)) fail('INVALID');
    return data.stickers.filter(row => {
      if (!object(row) || typeof row.id !== 'string' || !idPattern.test(row.pack) || !object(data.packs[row.pack])) fail('INVALID');
      archivePath(row.path);
      return /\.(?:png|jpe?g|webp|gif)$/iu.test(row.path);
    }).map(row => {
      const pack = data.packs[row.pack];
      return { id: 'online-' + hash(source + '\0' + row.id).slice(0, 40), originalId: row.id, path: row.path,
        format: /\.gif$/iu.test(row.path) ? 'gif' : 'static',
        label: clipped(row.name || row.file || row.caption), description: clipped(row.description || row.caption || row.name),
        tags: [clipped(row.tag, 80), ...strings(row.keywords).map(value => clipped(value, 80))].filter(Boolean).slice(0, 20),
        packId: row.pack, packName: clipped(pack.name, 80), license: clipped(pack.license),
        source: clipped(row.imageUrl || 'https://github.com/yyh-001/dsh-meme-packs', 1000), imageUrl: row.imageUrl,
        search: clipped([row.search, row.text, row.description, row.caption, ...strings(row.keywords), ...strings(row.useWhen)].filter(Boolean).join(' '), 4000) };
    });
  }
  async search({ query, tag = '', format = 'any', limit = 12, signal } = {}) {
    query = inputQuery(query);
    if (typeof tag !== 'string' || tag.length > 80 || !['any', 'gif', 'static'].includes(format)
      || !Number.isSafeInteger(limit) || limit < 1 || limit > 30) fail('INVALID');
    const [index, catalog] = await Promise.all([this.index(signal), this.catalog(signal)]);
    const packs = new Map(catalog.map(pack => [pack.id, pack]));
    const tags = moodTags[tag] ?? (tag ? [tag] : []), rows = index
      .filter(row => format === 'any' || row.format === format)
      .filter(row => {
        if (!needsArchive(row)) return true;
        const pack = packs.get(row.packId);
        return typeof pack?.archiveUrl === 'string' && /^[a-f0-9]{64}$/u.test(pack.sha256) && !(pack.bytes > STICKER_PACK_LIMITS.archiveBytes);
      })
      .filter(row => !tags.length || row.tags.some(value => tags.includes(value)))
      .map(row => ({ ...row, downloadable: true, downloadBytes: needsArchive(row) ? packs.get(row.packId)?.bytes || 0 : 0,
        caption: row.description, keywords: row.search, file_name: row.label, tag: row.tags.join(' ') }));
    return { source, items: searchRows(rows, query, limit).map(({ originalId, path, imageUrl, search, caption, keywords, file_name, tag: _tag, ...row }) => row) };
  }
  async pack(id, signal) {
    if (typeof id !== 'string' || !idPattern.test(id)) fail('NOT_FOUND');
    const row = (await this.catalog(signal)).find(pack => pack.id === id);
    if (!row) fail('NOT_FOUND');
    let resolved = row;
    if (!row.archiveUrl && row.manifestUrl) resolved = { ...row, ...await this.json(publicUrl(row.manifestUrl).toString(), 256 * 1024, signal), id: row.id };
    if (!resolved.archiveUrl && Array.isArray(resolved.memes)) {
      if (!resolved.memes.length) fail('INVALID');
      if (resolved.memes.length > STICKER_PACK_LIMITS.images) throw Object.assign(new Error('COMPANION_STICKER_PACK_TOO_LARGE'), { code: 'COMPANION_STICKER_PACK_TOO_LARGE' });
      for (const row of resolved.memes) if (!object(row) || typeof row.url !== 'string') fail('INVALID');
      return resolved;
    }
    if (typeof resolved.archiveUrl !== 'string' || !/^[a-f0-9]{64}$/u.test(resolved.sha256)) fail('INVALID');
    publicUrl(resolved.archiveUrl);
    if (resolved.bytes > STICKER_PACK_LIMITS.archiveBytes) throw Object.assign(new Error('COMPANION_STICKER_PACK_TOO_LARGE'), { code: 'COMPANION_STICKER_PACK_TOO_LARGE' });
    return resolved;
  }
  async archive(pack, signal) {
    const bytes = await this.bytes(pack.archiveUrl, STICKER_PACK_LIMITS.archiveBytes, signal);
    if (hash(bytes) !== pack.sha256) fail('CHECKSUM');
    return bytes;
  }
  async install(packId, { signal, locale = 'zh' } = {}) {
    const remote = await this.pack(packId, signal);
    const installed = this.library.packs(locale).find(pack => pack.originId === remote.id && pack.originVersion === clipped(remote.version, 100)
      && pack.importCapability === MARKET_STICKER_IMPORT_CAPABILITY);
    signal?.throwIfAborted();
    if (installed) return { pack: installed, installed: installed.count, skippedAnimated: installed.skippedAnimated ?? 0 };
    const provenance = { source: clipped(remote.homepage || remote.manifestUrl || remote.archiveUrl, 1000), license: clipped(remote.license),
      originId: remote.id, originVersion: clipped(remote.version, 100) };
    let pack;
    if (remote.archiveUrl) {
      const bytes = await this.archive(remote, signal);
      pack = await this.library.importZip(bytes, { name: clipped(remote.name, 80) + '.zip', signal, skipUnsupported: true, provenance });
    } else {
      const cancel = new AbortController(), timeout = AbortSignal.timeout(180000);
      const combined = AbortSignal.any([cancel.signal, timeout, ...(signal ? [signal] : [])]);
      const images = []; let size = 0;
      try {
        // Four concurrent image requests keep a manifest pack practical on mobile without an unbounded fan-out.
        for (let start = 0; start < remote.memes.length; start += 4) {
          const batch = await Promise.all(remote.memes.slice(start, start + 4).map(async row => {
            const bytes = await this.bytes(row.url, STICKER_PACK_LIMITS.imageBytes, combined);
            size += bytes.length;
            if (size > STICKER_PACK_LIMITS.expandedBytes) throw Object.assign(new Error('COMPANION_STICKER_PACK_TOO_LARGE'), { code: 'COMPANION_STICKER_PACK_TOO_LARGE' });
            if (row.sha256 && hash(bytes) !== row.sha256) fail('CHECKSUM');
            return { bytes, label: clipped(row.name || row.caption || row.file), description: clipped(row.description || row.caption || row.name),
              tag: clipped(row.tag, 80), keywords: clipped(Array.isArray(row.keywords) ? row.keywords.join(' ') : row.keywords, 1000),
              source: clipped(row.url, 1000), license: provenance.license };
          }));
          images.push(...batch);
        }
        pack = await this.library.importRemotePack(images, { name: clipped(remote.name, 80), signal: combined, provenance });
      } catch (error) { cancel.abort(error); throw error; }
    }
    return { pack, installed: pack.count, skippedAnimated: pack.skippedAnimated ?? 0 };
  }
  async download(stickerId, { signal, locale = 'zh' } = {}) {
    if (typeof stickerId !== 'string' || !/^online-[a-f0-9]{40}$/u.test(stickerId)) fail('NOT_FOUND');
    const item = (await this.index(signal)).find(row => row.id === stickerId);
    if (!item) fail('NOT_FOUND');
    // The public index's GIF imageUrl values currently point at flattened JPG previews.
    // Read the verified archive's original path whenever the direct URL is not a GIF.
    const direct = !needsArchive(item);
    let bytes, origin = item.source;
    if (direct) bytes = await this.bytes(item.imageUrl, STICKER_PACK_LIMITS.imageBytes, signal);
    else {
      const remote = await this.pack(item.packId, signal);
      if (!remote.archiveUrl) fail('INVALID');
      bytes = stickerArchiveImage(await this.archive(remote, signal), item.path);
      origin = remote.archiveUrl;
    }
    if (item.format === 'gif' && !['GIF87a', 'GIF89a'].includes(bytes.toString('ascii', 0, 6))) fail('INVALID');
    return this.library.importOnlineImage({ bytes, label: item.label, description: item.description, tag: item.tags[0] ?? '',
      keywords: item.tags.slice(1).join(' '), source: clipped(origin, 1000), license: item.license }, { signal, locale });
  }
}
