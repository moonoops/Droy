import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, mkdir, rm, readFile, readdir, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { DatabaseSync } from 'node:sqlite';
import { deflateRawSync } from 'node:zlib';
import { StickerPackLibrary, STICKER_PACK_LIMITS, BUILTIN_STICKER_PACK_ID } from '../companions/sticker-packs.mjs';
import { stickerCatalog } from '../companions/media.mjs';
import { crc32, encodePng } from '../png.mjs';

const png = () => encodePng({ width: 2, height: 2, channels: 4, pixels: Buffer.alloc(16, 200) });
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

test('rejects GIF, APNG, animated WebP and bogus image bytes', async t => {
  const { library } = await fixture(t);
  await assert.rejects(library.importZip(zip([{ name: 'cat.gif', data: Buffer.from('GIF89a') }])), errorCode('UNSUPPORTED'));
  const bytes = png(), animation = Buffer.alloc(20); animation.writeUInt32BE(8); animation.write('acTL', 4);
  animation.writeUInt32BE(crc32(animation.subarray(4, 16)), 16);
  const apng = Buffer.concat([bytes.subarray(0, 33), animation, bytes.subarray(33)]);
  await assert.rejects(library.importZip(zip([{ name: 'cat.png', data: apng }])), errorCode('UNSUPPORTED'));
  const webp = Buffer.alloc(30); webp.write('RIFF'); webp.writeUInt32LE(22, 4); webp.write('WEBPVP8X', 8); webp.writeUInt32LE(10, 16); webp[20] = 2;
  await assert.rejects(library.importZip(zip([{ name: 'cat.webp', data: webp }])), errorCode('UNSUPPORTED'));
  await assert.rejects(library.importZip(zip([{ name: 'cat.png', data: Buffer.from('<script>not an image</script>') }])), errorCode('INVALID'));
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
