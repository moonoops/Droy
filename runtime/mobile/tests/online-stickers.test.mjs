import test from 'node:test';
import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { mkdtemp, rm, readFile, writeFile, rename } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { OnlineStickerService, ONLINE_STICKER_CATALOG_URL, ONLINE_STICKER_INDEX_URL } from '../companions/online-stickers.mjs';
import { StickerPackLibrary, ONLINE_STICKER_PACK_ID, MARKET_STICKER_IMPORT_CAPABILITY } from '../companions/sticker-packs.mjs';
import { crc32, encodePng } from '../png.mjs';

const png = color => encodePng({ width: 2, height: 2, channels: 4, pixels: Buffer.alloc(16, color ?? 180) });
const gif = () => Buffer.from('47494638396101000100800000000000ffffff21f904000a0000002c000000000100010000020244010021f904000a0000002c00000000010001000002024c01003b', 'hex');
const animatedWebp = () => { const bytes = Buffer.alloc(30); bytes.write('RIFF'); bytes.writeUInt32LE(22, 4); bytes.write('WEBPVP8X', 8); bytes.writeUInt32LE(10, 16); bytes[20] = 2; return bytes; };
const sha = bytes => createHash('sha256').update(bytes).digest('hex');
const publicResolve = async () => [{ address: '93.184.215.14', family: 4 }];
const code = suffix => error => error.code === 'COMPANION_STICKER_ONLINE_' + suffix;
function zip(rows) {
  const body = [], central = []; let offset = 0;
  for (const row of rows) {
    const name = Buffer.from(row.name), data = row.data, checksum = crc32(data);
    const local = Buffer.alloc(30); local.writeUInt32LE(0x04034b50); local.writeUInt16LE(20, 4); local.writeUInt16LE(0x800, 6);
    local.writeUInt32LE(checksum, 14); local.writeUInt32LE(data.length, 18); local.writeUInt32LE(data.length, 22); local.writeUInt16LE(name.length, 26);
    const entry = Buffer.alloc(46); entry.writeUInt32LE(0x02014b50); entry.writeUInt16LE(20, 4); entry.writeUInt16LE(20, 6); entry.writeUInt16LE(0x800, 8);
    entry.writeUInt32LE(checksum, 16); entry.writeUInt32LE(data.length, 20); entry.writeUInt32LE(data.length, 24); entry.writeUInt16LE(name.length, 28); entry.writeUInt32LE(offset, 42);
    body.push(local, name, data); central.push(entry, name); offset += local.length + name.length + data.length;
  }
  const directory = Buffer.concat(central), end = Buffer.alloc(22); end.writeUInt32LE(0x06054b50); end.writeUInt16LE(rows.length, 8); end.writeUInt16LE(rows.length, 10);
  end.writeUInt32LE(directory.length, 12); end.writeUInt32LE(offset, 16);
  return Buffer.concat([...body, directory, end]);
}
async function fixture(t) {
  const directory = await mkdtemp(join(tmpdir(), 'droy-online-stickers-'));
  t.after(() => rm(directory, { recursive: true, force: true }));
  const library = await new StickerPackLibrary(directory).open();
  const archiveUrl = 'https://images.example.org/fictional.zip', imageUrl = 'https://images.example.org/heart.png';
  const archive = zip([{ name: 'happy/heart.png', data: png() }, { name: 'sleep/sleep.png', data: png(50) },
    { name: 'happy/animated.gif', data: gif() }, { name: 'happy/unsupported.webp', data: animatedWebp() }]);
  const catalog = { schemaVersion: 1, packs: [{ id: 'fictional-1', name: '虚构图包', description: '虚构日常表情', version: '1',
    count: 4, bytes: archive.length, license: 'CC0-1.0', archiveUrl, sha256: sha(archive), homepage: 'https://images.example.org/' }] };
  const index = { schemaVersion: 1, packs: { 'fictional-1': { name: '虚构图包', license: 'CC0-1.0' } }, stickers: [
    { id: 'fictional-1/happy/heart.png', pack: 'fictional-1', path: 'happy/heart.png', name: '给你比心', tag: 'happy',
      description: '虚构小猫抱着爱心，表示关心与安慰。', keywords: ['比心', '安慰', '关心'], useWhen: ['安慰朋友时'], imageUrl },
    { id: 'fictional-1/sleep/sleep.png', pack: 'fictional-1', path: 'sleep/sleep.png', name: '晚安', tag: 'sleep', description: '虚构小猫睡觉', keywords: ['休息', '晚安'] },
    { id: 'fictional-1/happy/animated.gif', pack: 'fictional-1', path: 'happy/animated.gif', name: '摇摆动态图', tag: 'happy', keywords: ['摇摆', '动画'], imageUrl: 'https://images.example.org/animation.gif' },
  ] };
  const routes = new Map([[ONLINE_STICKER_CATALOG_URL, () => Response.json(catalog)], [ONLINE_STICKER_INDEX_URL, () => Response.json(index)],
    [archiveUrl, () => new Response(archive)], [imageUrl, () => new Response(png())], ['https://images.example.org/animation.gif', () => new Response(gif())]]), calls = [];
  const fetchImpl = async (url, options) => { calls.push({ url, options }); return routes.get(url)?.() ?? new Response('missing', { status: 404 }); };
  const service = new OnlineStickerService({ library, fetchImpl, resolve: publicResolve,
    allowedHosts: ['raw.githubusercontent.com', 'images.example.org'] });
  return { service, library, directory, archive, archiveUrl, imageUrl, catalog, index, routes, calls };
}

test('online search matches individual descriptions and use cases, includes GIF, and does not upload the query', async t => {
  const f = await fixture(t);
  const result = await f.service.search({ query: '安慰朋友', tag: 'happy' });
  assert.equal(result.items.length, 1); assert.equal(result.items[0].label, '给你比心');
  assert.match(result.items[0].id, /^online-[a-f0-9]{40}$/u); assert.equal(result.items[0].license, 'CC0-1.0');
  assert.deepEqual(f.calls.map(call => call.url).sort(), [ONLINE_STICKER_CATALOG_URL, ONLINE_STICKER_INDEX_URL].sort());
  assert.equal(f.calls[0].options.body, undefined); assert.equal(f.calls[0].options.headers.authorization, undefined);
  assert.equal((await f.service.search({ query: '找不到的表情' })).items.length, 0);
  assert.equal(f.calls.length, 2, 'a bounded metadata cache avoids repeated identical index and catalog downloads');
  assert.equal((await f.service.search({ query: '晚安', tag: 'daily' })).items[0].label, '晚安');
  assert.equal((await f.service.search({ query: '摇摆', tag: 'happy' })).items[0].label, '摇摆动态图');
  const gifs = await f.service.search({ query: '摇摆', format: 'gif' });
  assert.equal(gifs.items[0].format, 'gif');
  assert.equal(gifs.items[0].downloadable, true);
  assert.deepEqual((await f.service.search({ query: '摇摆', format: 'static' })).items, []);
  assert.equal((await f.service.search({ query: '安慰', format: 'static' })).items[0].format, 'static');
  await assert.rejects(f.service.search({ query: '摇摆', format: 'video' }), code('INVALID'));
  await assert.rejects(f.service.search({ query: '' }), code('INVALID'));
  await assert.rejects(f.service.search({ query: 'a'.repeat(201) }), code('INVALID'));
});

test('GIF search excludes flattened previews whose source archive is unavailable or known too large', async t => {
  const f = await fixture(t);
  f.index.stickers.find(item => item.name === '摇摆动态图').imageUrl = 'https://images.example.org/flattened-preview.jpg';
  let result = await f.service.search({ query: '摇摆', format: 'gif' });
  assert.equal(result.items.length, 1); assert.equal(result.items[0].downloadBytes, f.archive.length);
  f.catalog.packs[0].bytes = 20 * 1024 * 1024 + 1;
  f.service.cache.delete(ONLINE_STICKER_CATALOG_URL);
  result = await f.service.search({ query: '摇摆', format: 'gif' });
  assert.deepEqual(result.items, []);
  f.catalog.packs[0].bytes = f.archive.length; delete f.catalog.packs[0].archiveUrl;
  f.service.cache.delete(ONLINE_STICKER_CATALOG_URL);
  assert.deepEqual((await f.service.search({ query: '摇摆', format: 'gif' })).items, []);
});

test('single images enter one shared collection with trusted meaning, deduplicate, and survive restart', async t => {
  const f = await fixture(t), item = (await f.service.search({ query: '比心' })).items[0];
  const first = await f.service.download(item.id, { locale: 'zh' });
  assert.equal(first.pack.id, ONLINE_STICKER_PACK_ID); assert.equal(first.pack.name, '联网表情');
  assert.equal(first.sticker.description, item.description); assert.equal(first.sticker.license, 'CC0-1.0');
  const duplicate = await f.service.download(item.id, { locale: 'en' });
  assert.equal(duplicate.sticker.id, first.sticker.id); assert.equal(duplicate.pack.count, 1); assert.equal(duplicate.pack.name, 'Online stickers');
  const sleep = (await f.service.search({ query: '晚安' })).items[0];
  const second = await f.service.download(sleep.id);
  assert.equal(second.pack.id, first.pack.id); assert.equal(second.pack.count, 2);
  assert.deepEqual((await f.library.read(first.sticker.id)).data, png());
  const restarted = await new StickerPackLibrary(f.directory).open();
  assert.equal(restarted.packs('en').find(pack => pack.id === first.pack.id).count, 2);
  assert.equal(restarted.catalog().find(sticker => sticker.id === first.sticker.id).description, item.description);
  assert.deepEqual((await restarted.read(first.sticker.id)).data, png());
});

test('market installation verifies the archive, includes GIF, skips unsupported animation and preserves provenance', async t => {
  const f = await fixture(t), market = await f.service.market({ query: '虚构' });
  assert.equal(market.packs[0].id, 'fictional-1'); assert.equal(market.packs[0].count, 4);
  const installed = await f.service.install('fictional-1');
  assert.equal(installed.installed, 3); assert.equal(installed.skippedAnimated, 1);
  assert.equal(installed.pack.originId, 'fictional-1'); assert.equal(installed.pack.originVersion, '1');
  assert.equal(installed.pack.importCapability, MARKET_STICKER_IMPORT_CAPABILITY);
  assert.equal(installed.pack.license, 'CC0-1.0');
  const before = f.calls.length, again = await f.service.install('fictional-1');
  assert.equal(again.pack.id, installed.pack.id); assert.equal(f.calls.length, before);
  const savedGif = f.library.catalog().find(item => item.packId === installed.pack.id && item.label === 'animated');
  assert.deepEqual(await f.library.read(savedGif.id), { data: gif(), mediaType: 'image/gif' });
  const reopened = await new StickerPackLibrary(f.directory).open();
  assert.deepEqual(await reopened.read(savedGif.id), { data: gif(), mediaType: 'image/gif' });
  await assert.rejects(f.library.importZip(f.archive), error => error.code === 'COMPANION_STICKER_PACK_UNSUPPORTED');
});

test('same-version static market packs are replaced by a GIF-capable install without deleting old history media', async t => {
  const f = await fixture(t);
  const seed = await f.library.importZip(zip([{ name: 'happy/old-static.png', data: png() }]), {
    name: 'Previous static import.zip', provenance: { originId: 'fictional-1', originVersion: '1' },
  });
  // Recreate the preceding importer's identity for this exact archive, with its GIF omitted.
  const old = { ...seed, id: 'pack-' + sha(Buffer.concat([Buffer.from('static\0'), f.archive])).slice(0, 40) };
  const previous = JSON.parse(await readFile(join(f.directory, seed.id, 'metadata.json'), 'utf8'));
  await rename(join(f.directory, seed.id), join(f.directory, old.id));
  await writeFile(join(f.directory, old.id, 'metadata.json'), JSON.stringify({ ...previous, id: old.id, skippedAnimated: 2 }));
  f.library = await new StickerPackLibrary(f.directory).open(); f.service.library = f.library;
  assert.equal(old.importCapability, undefined);
  const oldSticker = f.library.catalog().find(item => item.packId === old.id);
  const current = await f.service.install('fictional-1');
  assert.notEqual(current.pack.id, old.id); assert.equal(current.pack.importCapability, MARKET_STICKER_IMPORT_CAPABILITY);
  assert.equal(current.installed, 3);
  const gifSticker = f.library.catalog().find(item => item.packId === current.pack.id && item.label === 'animated');
  assert.deepEqual(await f.library.read(gifSticker.id), { data: gif(), mediaType: 'image/gif' });
  assert.deepEqual(await f.library.read(oldSticker.id), { data: png(), mediaType: 'image/png' });
  const reopened = await new StickerPackLibrary(f.directory).open();
  assert.equal(reopened.packs().find(pack => pack.id === current.pack.id).importCapability, MARKET_STICKER_IMPORT_CAPABILITY);
  assert.equal(reopened.packs().find(pack => pack.id === old.id).importCapability, undefined);
  assert.deepEqual(await reopened.read(oldSticker.id), { data: png(), mediaType: 'image/png' });
  const restartedService = new OnlineStickerService({ library: reopened, fetchImpl: f.service.fetchImpl, resolve: publicResolve,
    allowedHosts: ['raw.githubusercontent.com', 'images.example.org'] });
  assert.equal((await restartedService.install('fictional-1')).pack.id, current.pack.id);
});

test('market manifests download their listed images with captions and reject a partial failed pack', async t => {
  const f = await fixture(t), manifestUrl = 'https://images.example.org/manifest.json';
  f.catalog.packs.push({ id: 'manifest-1', name: '虚构清单图包', version: '2', license: 'CC0-1.0', manifestUrl });
  f.routes.set(manifestUrl, () => Response.json({ id: 'manifest-1', name: '虚构清单图包', version: '2', memes: [
    { url: f.imageUrl, name: '比心', caption: '虚构猫咪抱着爱心', tag: 'happy', keywords: '关心 比心' },
    { url: 'https://images.example.org/animation.gif', name: '动态图', tag: 'happy' },
    { url: 'https://images.example.org/unsupported.webp', name: '其它不支持动图', tag: 'happy' },
  ] }));
  f.routes.set('https://images.example.org/unsupported.webp', () => new Response(animatedWebp()));
  const result = await f.service.install('manifest-1');
  assert.equal(result.installed, 2); assert.equal(result.skippedAnimated, 1);
  assert.equal(result.pack.originId, 'manifest-1');
  assert.equal(result.pack.importCapability, MARKET_STICKER_IMPORT_CAPABILITY);
  const sticker = f.library.catalog().find(row => row.packId === result.pack.id);
  assert.equal(sticker.description, '虚构猫咪抱着爱心'); assert.ok(sticker.tags.includes('关心'));
  const restarted = await new StickerPackLibrary(f.directory).open();
  assert.equal(restarted.packs().find(pack => pack.id === result.pack.id).count, 2);
  assert.deepEqual((await restarted.read(sticker.id)).data, png());
  const savedGif = restarted.catalog().find(item => item.packId === result.pack.id && item.label === '动态图');
  assert.deepEqual(await restarted.read(savedGif.id), { data: gif(), mediaType: 'image/gif' });
  f.catalog.packs.push({ id: 'broken-1', name: '失败清单', manifestUrl: 'https://images.example.org/broken.json' });
  f.service.cache.delete(ONLINE_STICKER_CATALOG_URL);
  f.routes.set('https://images.example.org/broken.json', () => Response.json({ memes: [
    { url: f.imageUrl, name: '比心' }, { url: 'https://images.example.org/missing.png', name: '缺失' },
  ] }));
  await assert.rejects(f.service.install('broken-1'), code('NETWORK'));
  assert.equal(f.library.packs().length, 2);
});

test('online GIF download and archive extraction preserve animation bytes in the shared collection across restart', async t => {
  const f = await fixture(t), item = (await f.service.search({ query: '摇摆' })).items[0];
  const first = await f.service.download(item.id);
  assert.deepEqual(await f.library.read(first.sticker.id), { data: gif(), mediaType: 'image/gif' });
  const again = await f.service.download(item.id);
  assert.equal(again.sticker.id, first.sticker.id); assert.equal(again.pack.count, 1);
  f.index.stickers.find(row => row.name === '摇摆动态图').imageUrl = 'https://images.example.org/flattened-preview.jpg';
  f.routes.set('https://images.example.org/flattened-preview.jpg', () => { assert.fail('A JPG preview must not replace the original GIF'); });
  f.service.cache.delete(ONLINE_STICKER_INDEX_URL);
  const archived = await f.service.download(item.id);
  assert.equal(archived.sticker.id, first.sticker.id);
  assert.ok(f.calls.some(call => call.url === f.archiveUrl));
  const reopened = await new StickerPackLibrary(f.directory).open();
  assert.deepEqual(await reopened.read(first.sticker.id), { data: gif(), mediaType: 'image/gif' });
});

test('an indexed GIF cannot silently become a static image when a direct URL serves the wrong format', async t => {
  const f = await fixture(t), item = (await f.service.search({ query: '摇摆', format: 'gif' })).items[0];
  f.routes.set('https://images.example.org/animation.gif', () => new Response(png()));
  await assert.rejects(f.service.download(item.id), code('INVALID'));
  assert.equal(f.library.packs().length, 1);
});

test('checksum failure, invalid bytes and unknown IDs never publish a pack', async t => {
  const f = await fixture(t);
  f.routes.set(f.archiveUrl, () => new Response(Buffer.from('different archive')));
  await assert.rejects(f.service.install('fictional-1'), code('CHECKSUM'));
  await assert.rejects(f.service.download('online-' + 'f'.repeat(40)), code('NOT_FOUND'));
  const item = (await f.service.search({ query: '比心' })).items[0];
  f.routes.set(f.imageUrl, () => new Response('<html>not an image</html>'));
  await assert.rejects(f.service.download(item.id), error => error.code === 'COMPANION_STICKER_PACK_INVALID');
  assert.equal(f.library.packs().length, 1);
});

test('downloads reject private addresses, unsafe redirects, credentials and response growth', async t => {
  const f = await fixture(t);
  for (const url of ['http://images.example.org/x', 'https://127.0.0.1/x', 'https://[::1]/x', 'https://[::ffff:127.0.0.1]/x',
    'https://localhost/x', 'https://user:password@images.example.org/x', 'https://images.example.org:444/x'])
    await assert.rejects(f.service.bytes(url, 100), code('INVALID'));
  const url = 'https://images.example.org/redirect';
  f.routes.set(url, () => new Response(null, { status: 302, headers: { location: 'https://192.168.0.1/private' } }));
  await assert.rejects(f.service.bytes(url, 100), code('INVALID'));
  f.routes.set(url, () => new Response(null, { status: 302, headers: { location: 'https://another.example.org/private' } }));
  await assert.rejects(f.service.bytes(url, 100), code('INVALID'));
  f.service.resolve = async () => [{ address: '93.184.215.14', family: 4 }, { address: '10.0.0.1', family: 4 }];
  await assert.rejects(f.service.bytes(f.imageUrl, 100), code('INVALID'));
  f.service.resolve = publicResolve;
  f.routes.set(url, () => new Response(new Uint8Array(101)));
  await assert.rejects(f.service.bytes(url, 100), error => error.code === 'COMPANION_STICKER_PACK_TOO_LARGE');
  f.routes.set(url, () => new Response(new Uint8Array(1), { headers: { 'content-length': '101' } }));
  await assert.rejects(f.service.bytes(url, 100), error => error.code === 'COMPANION_STICKER_PACK_TOO_LARGE');
});

test('cancellation interrupts hostname lookup and streaming before any storage mutation; network errors remain failures', async t => {
  const f = await fixture(t), abort = new AbortController();
  f.service.resolve = () => new Promise(() => {});
  const pending = f.service.market({ signal: abort.signal }); abort.abort();
  await assert.rejects(pending, { name: 'AbortError' });
  f.service.resolve = publicResolve;
  const controller = new AbortController();
  f.routes.set(f.imageUrl, () => new Response(new ReadableStream({ start() { queueMicrotask(() => controller.abort()); } })));
  await assert.rejects(f.service.bytes(f.imageUrl, 100, controller.signal), { name: 'AbortError' });
  f.routes.set(f.imageUrl, () => new Response('unavailable', { status: 503 }));
  await assert.rejects(f.service.bytes(f.imageUrl, 100), code('NETWORK'));
  assert.equal(f.library.packs().length, 1);
});
