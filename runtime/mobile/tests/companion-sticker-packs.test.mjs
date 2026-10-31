import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, mkdir, rm, readFile, readdir, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { DatabaseSync } from 'node:sqlite';
import { deflateRawSync } from 'node:zlib';
import { StickerPackLibrary, STICKER_PACK_LIMITS, BUILTIN_STICKER_PACK_ID, ONLINE_STICKER_PACK_ID } from '../companions/sticker-packs.mjs';
import { stickerCatalog } from '../companions/media.mjs';
import { crc32, encodePng } from '../png.mjs';

const png = () => encodePng({ width: 2, height: 2, channels: 4, pixels: Buffer.alloc(16, 200) });
const gif = () => Buffer.from('47494638396101000100800000000000ffffff21f904000a0000002c000000000100010000020244010021f904000a0000002c00000000010001000002024c01003b', 'hex');
const errorCode = expected => error => error.code === 'COMPANION_STICKER_PACK_' + expected;
function zip(rows, { deflate = false } = {}) {
  const body = [], central = []; let offset = 0;
  for (const row of rows) {
    const name = Buffer.from(row.name), bytes = row.data ?? Buffer.alloc(0), compressed = deflate ? deflateRawSync(bytes) : bytes;
    const method = deflate ? 8 : 0, checksum = crc32(bytes), expanded = row.expanded ?? bytes.length;
    const local = Buffer.alloc(30); local.writeUInt32LE(0x04034b50); local.writeUInt16LE(20, 4); local.writeUInt16LE(0x800, 6);
    local.writeUInt16LE(method, 8); local.writeUInt32LE(checksum, 14); local.writeUInt32LE(compressed.length, 18);
    local.writeUInt32LE(expanded, 22); local.writeUInt16LE(name.length, 26);
    const header = Buffer.alloc(46); header.writeUInt32LE(0x02014b50); header.writeUInt16LE(0x314, 4); header.writeUInt16LE(20, 6);
    header.writeUInt16LE(0x800, 8); header.writeUInt16LE(method, 10); header.writeUInt32LE(checksum, 16);
    header.writeUInt32LE(compressed.length, 20); header.writeUInt32LE(expanded, 24); header.writeUInt16LE(name.length, 28);
    header.writeUInt32LE(row.external ?? 0, 38); header.writeUInt32LE(offset, 42);
    body.push(local, name, compressed); central.push(header, name); offset += local.length + name.length + compressed.length;
  }
  const centralBytes = Buffer.concat(central), end = Buffer.alloc(22); end.writeUInt32LE(0x06054b50);
  end.writeUInt16LE(rows.length, 8); end.writeUInt16LE(rows.length, 10); end.writeUInt32LE(centralBytes.length, 12); end.writeUInt32LE(offset, 16);
  return Buffer.concat([...body, centralBytes, end]);
}
async function fixture(t) {
  const directory = await mkdtemp(join(tmpdir(), 'droy-stickers-'));
  t.after(() => rm(directory, { recursive: true, force: true }));
  const library = await new StickerPackLibrary(join(directory, 'packs'), { builtinCatalog: stickerCatalog }).open();
  return { directory, library };
}
async function indexBytes(directory, rows, { view = false } = {}) {
  const path = join(directory, 'index-' + Math.random() + '.db'), db = new DatabaseSync(path);
  db.exec('CREATE TABLE memes(path TEXT, tag TEXT, file_name TEXT, caption TEXT, keywords TEXT)');
  const insert = db.prepare('INSERT INTO memes VALUES(?,?,?,?,?)');
  for (const row of rows) insert.run(row.path, row.tag ?? 'happy', row.file_name ?? 'fictional.png', row.caption ?? 'Fictional happy cat', row.keywords ?? 'cat happy');
  if (view) db.exec('ALTER TABLE memes RENAME TO source; CREATE VIEW memes AS SELECT * FROM source');
  db.close(); return readFile(path);
}

test('built-in IDs remain unchanged and language follows current catalog locale', async t => {
  const { library } = await fixture(t);
  assert.equal(library.packs('en')[0].id, BUILTIN_STICKER_PACK_ID);
  assert.equal(library.packs()[0].count, 9);
  assert.deepEqual(library.catalog('en').map(row => row.id), stickerCatalog('en').map(row => row.id));
  assert.equal(library.catalog('en')[0].label, 'Understood');
  assert.equal(library.catalog('zh')[0].label, '收到');
  assert.deepEqual(library.catalog('zh', { packIds: [] }), []);
  assert.equal(await library.read('test-sticker-ok'), null);
  for (const tag of ['happy', 'sad', 'confused', 'daily']) {
    const ids = locale => library.search(locale, { packIds: [BUILTIN_STICKER_PACK_ID], tag, limit: 30 }).map(item => item.id).sort();
    assert.ok(ids('zh').length > 0); assert.deepEqual(ids('zh'), ids('en'));
  }
});

test('imports dsh-meme export ZIP, preserving metadata with stable opaque IDs', async t => {
  const { directory, library } = await fixture(t), bytes = png();
  const index = await indexBytes(directory, [{ path: 'memes/happy/cat.png', tag: 'happy', caption: '小猫比心', keywords: '猫 比心 开心' }]);
  const archive = zip([{ name: 'manifest.json', data: Buffer.from(JSON.stringify({ id: 'cat', name: 'Fictional cats', license: 'CC0', source: 'https://example.invalid/cats' })) },
    { name: 'index.db', data: index }, { name: 'memes/happy/cat.png', data: bytes }], { deflate: true });
  const pack = await library.importZip(archive, { name: 'fallback.zip' });
  assert.match(pack.id, /^pack-[a-f0-9]{40}$/u); assert.equal(pack.name, 'Fictional cats');
  const [item] = library.catalog('en', { packIds: [pack.id] });
  assert.equal(item.label, '小猫比心'); assert.equal(item.packId, pack.id);
  assert.equal(item.url, '/droy/companions/sticker-media/' + item.id);
  assert.ok(item.tags.includes('比心'));
  assert.deepEqual(await library.read(item.id), { data: bytes, mediaType: 'image/png' });
  const duplicate = await library.importZip(archive);
  assert.equal(duplicate.id, pack.id); assert.equal(library.packs().length, 2);
  assert.equal(duplicate.license, 'CC0');
  const restarted = await new StickerPackLibrary(join(directory, 'packs'), { builtinCatalog: stickerCatalog }).open();
  assert.deepEqual(restarted.catalog(), library.catalog());
  assert.equal(restarted.packs().find(row => row.id === pack.id).source, 'https://example.invalid/cats');
  assert.deepEqual(await restarted.read(item.id), { data: bytes, mediaType: 'image/png' });
});

test('plain-image ZIP gets file-name meanings and keyword/emotion matching within selected packs', async t => {
  const { library } = await fixture(t);
  const first = await library.importZip(zip([{ name: 'happy/猫比心.png', data: png() }, { name: 'sad/伤心.png', data: png() }]), { name: 'Fictional reactions.zip' });
  const second = await library.importZip(zip([{ name: 'work/加班.png', data: png() }]), { name: 'Work.zip' });
  assert.equal(first.name, 'Fictional reactions');
  assert.equal(library.search('zh', { packIds: [first.id], query: '小猫比心' })[0].label, '猫比心');
  assert.equal(library.search('en', { packIds: [second.id], tag: 'daily' })[0].label, '加班');
  assert.equal(library.search('zh', { packIds: [first.id], query: '加班' }).length, 0);
  assert.equal(library.search('zh', { packIds: [first.id], tag: 'sad' })[0].label, '伤心');
  assert.equal(library.search('en', { packIds: [], query: 'cat' }).length, 0);
  assert.equal(library.catalog('zh').length, 12);
});

test('long valid archive filenames derive bounded Unicode display names without rejecting the import', async t => {
  const { library } = await fixture(t);
  for (const name of ['Fictional reactions '.repeat(7), '虚构表情🙂'.repeat(25)]) {
    const archive = zip([{ name: name.startsWith('F') ? 'cat.png' : 'reaction.png', data: png() }]);
    const pack = await library.importZip(archive, { name: name + '.zip' });
    assert.equal(pack.name, [...name].slice(0, 80).join(''));
    assert.equal([...pack.name].length, 80); assert.ok(pack.name.isWellFormed());
  }
  const explicit = zip([{ name: 'cat.png', data: png() }, { name: 'manifest.json', data: Buffer.from(JSON.stringify({ name: 'a'.repeat(81) })) }]);
  await assert.rejects(library.importZip(explicit, { name: 'valid.zip' }), errorCode('INVALID'));
});

test('opening the library removes only its interrupted UUID import directories', async t => {
  const { library, directory } = await fixture(t), root = join(directory, 'packs');
  const interrupted = '.import-12345678-1234-4123-8123-123456789abc';
  await mkdir(join(root, interrupted)); await writeFile(join(root, interrupted, 'partial.png'), png());
  await mkdir(join(root, '.import-user-data')); await writeFile(join(root, '.import-user-data', 'keep.txt'), 'keep');
  await library.open();
  assert.deepEqual(await readdir(root), ['.import-user-data']);
  assert.equal(await readFile(join(root, '.import-user-data', 'keep.txt'), 'utf8'), 'keep');
});

test('rejects archive traversal, absolute paths, duplicate names, symlinks and truncation', async t => {
  const { library } = await fixture(t);
  for (const name of ['../outside.png', '/outside.png', 'C:/outside.png', 'happy\\outside.png', 'a/./b.png', 'a//b.png'])
    await assert.rejects(library.importZip(zip([{ name, data: png() }])), errorCode('INVALID'));
  await assert.rejects(library.importZip(zip([{ name: 'same.png', data: png() }, { name: 'same.png', data: png() }])), errorCode('INVALID'));
  await assert.rejects(library.importZip(zip([{ name: 'linked.png', data: png(), external: (0xa000 << 16) >>> 0 }])), errorCode('INVALID'));
  const valid = zip([{ name: 'happy/cat.png', data: png() }]);
  await assert.rejects(library.importZip(valid.subarray(0, valid.length - 1)), errorCode('INVALID'));
  assert.equal(library.packs().length, 1);
});

test('rejects CRC mismatch, encrypted flags, forged local name and multi-disk archives', async t => {
  const { library } = await fixture(t), valid = zip([{ name: 'happy/cat.png', data: png() }]);
  const corrupt = Buffer.from(valid); corrupt[45] ^= 1;
  await assert.rejects(library.importZip(corrupt), errorCode('INVALID'));
  const encrypted = Buffer.from(valid); encrypted.writeUInt16LE(0x801, 6);
  await assert.rejects(library.importZip(encrypted), errorCode('INVALID'));
  const renamed = Buffer.from(valid); renamed[30] ^= 1;
  await assert.rejects(library.importZip(renamed), errorCode('INVALID'));
  const multidisk = Buffer.from(valid); multidisk.writeUInt16LE(1, valid.length - 18);
  await assert.rejects(library.importZip(multidisk), errorCode('INVALID'));
});

test('enforces compressed, expanded, per-image and count limits before publication', async t => {
  const { library, directory } = await fixture(t);
  await assert.rejects(library.importZip(Buffer.alloc(STICKER_PACK_LIMITS.archiveBytes + 1)), errorCode('TOO_LARGE'));
  await assert.rejects(library.importZip(zip([{ name: 'large.png', data: png(), expanded: STICKER_PACK_LIMITS.expandedBytes + 1 }])), errorCode('TOO_LARGE'));
  await assert.rejects(library.importZip(zip([{ name: 'large.png', data: Buffer.alloc(STICKER_PACK_LIMITS.imageBytes + 1) }], { deflate: true })), errorCode('TOO_LARGE'));
  await assert.rejects(library.importZip(zip(Array.from({ length: 501 }, (_, i) => ({ name: i + '.png', data: png() })))), errorCode('TOO_LARGE'));
  const forged = zip([{ name: 'bomb.png', data: Buffer.alloc(100_000), expanded: 20 }], { deflate: true });
  await assert.rejects(library.importZip(forged), errorCode('TOO_LARGE'));
  assert.deepEqual(await readdir(join(directory, 'packs')), []);
});

test('rejects damaged GIF, APNG, animated WebP and bogus image bytes', async t => {
  const { library } = await fixture(t);
  await assert.rejects(library.importZip(zip([{ name: 'cat.gif', data: Buffer.from('GIF89a') }])), errorCode('INVALID'));
  const bytes = png(), animation = Buffer.alloc(20); animation.writeUInt32BE(8); animation.write('acTL', 4);
  animation.writeUInt32BE(crc32(animation.subarray(4, 16)), 16);
  const apng = Buffer.concat([bytes.subarray(0, 33), animation, bytes.subarray(33)]);
  await assert.rejects(library.importZip(zip([{ name: 'cat.png', data: apng }])), errorCode('UNSUPPORTED'));
  const webp = Buffer.alloc(30); webp.write('RIFF'); webp.writeUInt32LE(22, 4); webp.write('WEBPVP8X', 8); webp.writeUInt32LE(10, 16); webp[20] = 2;
  await assert.rejects(library.importZip(zip([{ name: 'cat.webp', data: webp }])), errorCode('UNSUPPORTED'));
  await assert.rejects(library.importZip(zip([{ name: 'cat.png', data: Buffer.from('<script>not an image</script>') }])), errorCode('INVALID'));
});

test('market imports preserve GIF and skip APNG or animated WebP, while malformed images still fail', async t => {
  const { library } = await fixture(t), bytes = png();
  const animation = Buffer.alloc(20); animation.writeUInt32BE(8); animation.write('acTL', 4);
  animation.writeUInt32BE(crc32(animation.subarray(4, 16)), 16);
  const apng = Buffer.concat([bytes.subarray(0, 33), animation, bytes.subarray(33)]);
  const webp = Buffer.alloc(30); webp.write('RIFF'); webp.writeUInt32LE(22, 4); webp.write('WEBPVP8X', 8); webp.writeUInt32LE(10, 16); webp[20] = 2;
  const rows = [{ name: 'static.png', data: bytes }, { name: 'animated.gif', data: gif() },
    { name: 'animated.png', data: apng }, { name: 'animated.webp', data: webp }];
  const pack = await library.importZip(zip(rows), { skipUnsupported: true, provenance: { originId: 'fictional-market', originVersion: '1' } });
  assert.equal(pack.count, 2); assert.equal(pack.skippedAnimated, 2); assert.equal(pack.originId, 'fictional-market');
  await assert.rejects(library.importZip(zip(rows)), errorCode('UNSUPPORTED'));
  await assert.rejects(library.importZip(zip(rows.slice(2)), { skipUnsupported: true }), errorCode('UNSUPPORTED'));
  await assert.rejects(library.importZip(zip([...rows, { name: 'broken.png', data: Buffer.from('not an image') }]), { skipUnsupported: true }), errorCode('INVALID'));
  assert.equal(library.packs().length, 2);
});

test('manual ZIP and online collection keep multi-frame GIF bytes and media types after restart', async t => {
  const { library, directory } = await fixture(t), bytes = gif();
  const pack = await library.importZip(zip([{ name: 'happy/虚构摇摆.gif', data: bytes }]));
  const [sticker] = library.catalog('zh', { packIds: [pack.id] });
  assert.deepEqual(await library.read(sticker.id), { data: bytes, mediaType: 'image/gif' });
  const metadata = JSON.parse(await readFile(join(directory, 'packs', pack.id, 'metadata.json'), 'utf8'));
  assert.equal(metadata.items[0].frameCount, 2); assert.equal(metadata.items[0].animated, true); assert.equal(metadata.items[0].extension, 'gif');
  const online = await library.importOnlineImage({ bytes, label: '摇摆', description: '虚构黑白方块交替', tag: 'happy' });
  const reopened = await new StickerPackLibrary(join(directory, 'packs'), { builtinCatalog: stickerCatalog }).open();
  assert.deepEqual(await reopened.read(sticker.id), { data: bytes, mediaType: 'image/gif' });
  assert.deepEqual(await reopened.read(online.sticker.id), { data: bytes, mediaType: 'image/gif' });
  const still = Buffer.from('47494638376101000100800000000000ffffff2c00000000010001000002024401003b', 'hex');
  const gif87 = await library.importZip(zip([{ name: 'single.gif', data: still }]));
  assert.deepEqual((await library.read(library.catalog('en', { packIds: [gif87.id] })[0].id)).data, still);
});

test('GIF rejects truncated color tables, frames, extensions, image sub-blocks and canvas or frame excess', async t => {
  const { library } = await fixture(t), bytes = gif();
  const invalid = [bytes.subarray(0, 12), bytes.subarray(0, 17), bytes.subarray(0, -1), Buffer.concat([bytes, Buffer.from([0])])];
  const noFrame = Buffer.concat([bytes.subarray(0, 19), Buffer.from([0x3b])]); invalid.push(noFrame);
  const badFrame = Buffer.from(bytes); badFrame.writeUInt16LE(2, 32); invalid.push(badFrame);
  const badControl = Buffer.from(bytes); badControl[21] = 3; invalid.push(badControl);
  const reservedControl = Buffer.from(bytes); reservedControl[22] = 0xe0; invalid.push(reservedControl);
  const badCodeSize = Buffer.from(bytes); badCodeSize[37] = 1; invalid.push(badCodeSize);
  const badSubBlock = Buffer.from(bytes); badSubBlock[38] = 255; invalid.push(badSubBlock);
  const noColors = Buffer.from(bytes); noColors[10] = 0; invalid.push(noColors);
  for (const data of invalid) await assert.rejects(library.importZip(zip([{ name: 'broken.gif', data }])), errorCode('INVALID'));
  const tooWide = Buffer.from(bytes); tooWide.writeUInt16LE(STICKER_PACK_LIMITS.dimension + 1, 6);
  await assert.rejects(library.importZip(zip([{ name: 'wide.gif', data: tooWide }])), errorCode('TOO_LARGE'));
  const frame = bytes.subarray(19, 42);
  const tooManyFrames = Buffer.concat([bytes.subarray(0, 19), ...Array(STICKER_PACK_LIMITS.gifFrames + 1).fill(frame), Buffer.from([0x3b])]);
  await assert.rejects(library.importZip(zip([{ name: 'too-many.gif', data: tooManyFrames }])), errorCode('TOO_LARGE'));
  const largeCanvas = Buffer.from(bytes.subarray(0, 19)); largeCanvas.writeUInt16LE(2048, 6); largeCanvas.writeUInt16LE(2048, 8);
  const excessiveCanvasWork = Buffer.concat([largeCanvas, ...Array(40).fill(frame), Buffer.from([0x3b])]);
  await assert.rejects(library.importZip(zip([{ name: 'expensive.gif', data: excessiveCanvasWork }])), errorCode('TOO_LARGE'));
  assert.equal(library.packs().length, 1);
});

test('GIF application and comment extensions preserve loop bytes without flattening frames', async t => {
  const { library } = await fixture(t), bytes = gif();
  const loop = Buffer.from('21ff0b4e45545343415045322e300301000000', 'hex');
  const comment = Buffer.from([0x21, 0xfe, 3, 0x61, 0x62, 0x63, 0]);
  const looping = Buffer.concat([bytes.subarray(0, 19), loop, comment, bytes.subarray(19)]);
  looping[11] = 255; // Unused background sentinel used by real market GIFs.
  const pack = await library.importZip(zip([{ name: 'loop.gif', data: looping }]));
  const sticker = library.catalog('zh', { packIds: [pack.id] })[0];
  assert.deepEqual(await library.read(sticker.id), { data: looping, mediaType: 'image/gif' });
  const truncated = Buffer.concat([bytes.subarray(0, 19), loop.subarray(0, -1)]);
  await assert.rejects(library.importZip(zip([{ name: 'broken-loop.gif', data: truncated }])), errorCode('INVALID'));
});

test('validates image dimensions and image magic rather than trusting extension', async t => {
  const { library } = await fixture(t), bytes = png();
  bytes.writeUInt32BE(STICKER_PACK_LIMITS.dimension + 1, 16); bytes.writeUInt32BE(crc32(bytes.subarray(12, 29)), 29);
  await assert.rejects(library.importZip(zip([{ name: 'too-big.png', data: bytes }])), errorCode('TOO_LARGE'));
  const webp = await readFile(new URL('../companions/assets/wallpaper-01-thumb.webp', import.meta.url));
  const pack = await library.importZip(zip([{ name: 'static.webp', data: webp }]));
  assert.equal((await library.read(library.catalog('en', { packIds: [pack.id] })[0].id)).mediaType, 'image/webp');
});

test('does not accept SQLite views, paths outside the archive or duplicate indexed images', async t => {
  const { library, directory } = await fixture(t);
  for (const [rows, opts] of [
    [[{ path: '../private.png' }], {}], [[{ path: '/private.png' }], {}], [[{ path: 'missing.png' }], {}],
    [[{ path: 'cat.png' }, { path: 'cat.png' }], {}], [[{ path: 'cat.png' }], { view: true }],
  ]) {
    const index = await indexBytes(directory, rows, opts);
    await assert.rejects(library.importZip(zip([{ name: 'index.db', data: index }, { name: 'cat.png', data: png() }])), errorCode('INVALID'));
  }
  assert.equal(library.packs().length, 1);
});

test('simultaneous imports are idempotent and cancellation leaves no visible partial pack', async t => {
  const { library, directory } = await fixture(t), bytes = zip([{ name: 'cat.png', data: png() }]);
  const packs = await Promise.all([library.importZip(bytes), library.importZip(bytes)]);
  assert.equal(packs[0].id, packs[1].id); assert.equal(library.packs().length, 2);
  const abort = new AbortController(); abort.abort();
  await assert.rejects(library.importZip(bytes, { signal: abort.signal }), { name: 'AbortError' });
  const during = new AbortController();
  const pending = library.importZip(zip([{ name: 'cancelled.png', data: png() }]), { signal: during.signal });
  queueMicrotask(() => during.abort());
  await assert.rejects(pending, { name: 'AbortError' });
  assert.equal(library.packs().length, 2);
  assert.equal((await readdir(join(directory, 'packs'))).filter(name => name.startsWith('.import-')).length, 0);
});

test('stored media corruption is surfaced and arbitrary IDs never become file paths', async t => {
  const { library, directory } = await fixture(t), pack = await library.importZip(zip([{ name: 'cat.png', data: png() }]));
  const [item] = library.catalog('zh', { packIds: [pack.id] });
  await writeFile(join(directory, 'packs', pack.id, item.id + '.png'), Buffer.from('corrupt'));
  await assert.rejects(library.read(item.id), errorCode('INVALID'));
  assert.equal(await library.read('../../private'), null);
  assert.equal(await library.read('meme-' + '0'.repeat(40)), null);
});

const largeMetadata = { label: '虚'.repeat(200), description: '构'.repeat(200), tag: '类'.repeat(80),
  keywords: '词'.repeat(1000), source: 'https://example.invalid/' + '源'.repeat(950), license: '许'.repeat(200) };

test('remote pack metadata is bounded before publication and the previous library still opens', async t => {
  const { library, directory } = await fixture(t);
  const previous = await library.importZip(zip([{ name: 'existing.png', data: png() }]));
  const [oldSticker] = library.catalog('en', { packIds: [previous.id] });
  const images = Array.from({ length: 500 }, () => ({ bytes: png(), ...largeMetadata }));
  await assert.rejects(library.importRemotePack(images, { name: 'Fictional metadata overflow' }), errorCode('TOO_LARGE'));
  const reopened = await new StickerPackLibrary(join(directory, 'packs'), { builtinCatalog: stickerCatalog }).open();
  assert.deepEqual(reopened.catalog(), library.catalog()); assert.equal(reopened.packs().length, 2);
  assert.deepEqual((await reopened.read(oldSticker.id)).data, png());
});

test('online collection rejects an oversized metadata append without breaking the existing collection on restart', async t => {
  const { library, directory } = await fixture(t);
  let accepted = 0, lastSticker, rejected = false;
  for (let index = 0; index < STICKER_PACK_LIMITS.images; index++) {
    const pixels = Buffer.alloc(16, 100); pixels.writeUInt32LE(index);
    const bytes = encodePng({ width: 2, height: 2, channels: 4, pixels });
    try {
      const result = await library.importOnlineImage({ bytes, ...largeMetadata });
      accepted++; lastSticker = result.sticker;
    } catch (error) {
      assert.equal(error.code, 'COMPANION_STICKER_PACK_TOO_LARGE'); rejected = true; break;
    }
  }
  assert.equal(rejected, true); assert.ok(accepted > 0 && accepted < STICKER_PACK_LIMITS.images);
  const raw = await readFile(join(directory, 'packs', ONLINE_STICKER_PACK_ID, 'metadata.json'));
  assert.ok(raw.length <= 2 * 1024 * 1024);
  const reopened = await new StickerPackLibrary(join(directory, 'packs'), { builtinCatalog: stickerCatalog }).open();
  assert.equal(reopened.packs().find(pack => pack.id === ONLINE_STICKER_PACK_ID).count, accepted);
  assert.deepEqual(reopened.catalog(), library.catalog()); assert.ok((await reopened.read(lastSticker.id)).data.length > 0);
});
