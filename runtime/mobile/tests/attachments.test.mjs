import test from 'node:test';
import assert from 'node:assert/strict';
import { createRequire, registerHooks, syncBuiltinESMExports } from 'node:module';
import { pathToFileURL } from 'node:url';
import fsPromises, { mkdir, mkdtemp, readFile, readdir, rm, writeFile } from 'node:fs/promises';
import { createHash } from 'node:crypto';
import { tmpdir } from 'node:os';
import { dirname, join } from 'node:path';
import { encodePng, decodePng, crc32 } from '../png.mjs';
import { deflateSync } from 'node:zlib';

const installed = createRequire(new URL('../../dsh/package.json', import.meta.url));
const attachmentsUrl = new URL('../attachments.mjs', import.meta.url).href;
const hooks = registerHooks({ resolve(specifier, context, next) {
  if (specifier.startsWith('@deepseek-ai/')) return { url: pathToFileURL(installed.resolve(specifier)).href, shortCircuit: true };
  if (context.parentURL === attachmentsUrl && specifier === './publish.mjs') {
    return { url: new URL('./fixtures/host-publish.mjs', import.meta.url).href, shortCircuit: true };
  }
  return next(specifier, context);
} });
const { Context } = await import('@deepseek-ai/cordis');
const { default: MobileAttachments } = await import('../attachments.mjs');
const { normalizeAndroidImage } = await import('../attachment-codec.mjs');
hooks.deregister();

async function withStore(run, config) {
  const home = await mkdtemp(join(tmpdir(), 'droy-attachments-'));
  const originalHome = process.env.DSH_HOME;
  process.env.DSH_HOME = home;
  const ctx = new Context();
  try {
    await ctx.plugin(MobileAttachments, config);
    await run(ctx.attachments);
  } finally {
    await ctx.fiber.dispose();
    if (originalHome === undefined) delete process.env.DSH_HOME;
    else process.env.DSH_HOME = originalHome;
    await rm(home, { recursive: true, force: true });
  }
}
const screenshot = () => ({
  data: encodePng({ width: 4, height: 3, channels: 4, pixels: Buffer.from(Array.from({ length: 48 }, (_, i) => i * 3)) }),
  mediaType: 'image/png',
});

test('concurrent identical screenshots reuse a verified durable attachment', async () => withStore(async store => {
  const refs = await Promise.all(Array.from({ length: 4 }, () => store.saveImage(screenshot())));
  for (const ref of refs) assert.deepEqual(ref, refs[0]);
  assert.deepEqual((await store.readImage(refs[0])).data, screenshot().data);
  assert.deepEqual(await readdir(dirname(store.imageHostPath(refs[0]))), [refs[0].attachmentId.slice(7)]);
  assert.deepEqual(await readdir(join(store.root, 'tmp')), []);
}));

test('a corrupt existing screenshot never produces a successful attachment reference', async () => withStore(async store => {
  const ref = await store.saveImage(screenshot());
  const path = store.imageHostPath(ref);
  const corrupt = Buffer.alloc(ref.bytes, 0);
  await writeFile(path, corrupt);
  await assert.rejects(store.saveImage(screenshot()), /截图完整性校验失败/);
  assert.deepEqual(await readFile(path), corrupt, 'failed publication must not overwrite existing evidence');
  assert.deepEqual(await readdir(dirname(path)), [ref.attachmentId.slice(7)]);
  assert.deepEqual(await readdir(join(store.root, 'tmp')), []);
}));

test('failed screenshot writes remove their private temporary files', async t => withStore(async store => {
  const originalOpen = fsPromises.open;
  const full = Object.assign(new Error('simulated full storage'), { code: 'ENOSPC' });
  const mockedOpen = t.mock.method(fsPromises, 'open', async (...args) => {
    const handle = await originalOpen(...args);
    if (String(args[0]).startsWith(`${store.root}/`) && String(args[0]).endsWith('.tmp')) {
      handle.writeFile = async () => { throw full; };
    }
    return handle;
  });
  syncBuiltinESMExports();
  try {
    await assert.rejects(store.saveImage(screenshot()), error => error === full);
    assert.deepEqual(await readdir(join(store.root, 'tmp')), []);
  } finally {
    mockedOpen.mock.restore();
    syncBuiltinESMExports();
  }
}));

async function collect(stream) {
  const chunks = [];
  for await (const chunk of stream) chunks.push(chunk);
  return Buffer.concat(chunks);
}

test('legacy PNG references remain readable after switching to the upstream storage layout', async () => withStore(async store => {
  const data = screenshot().data;
  const ref = { attachmentId: createHash('sha256').update(data).digest('hex'), bytes: data.length, mediaType: 'image/png', width: 4, height: 3, name: 'android-screen.png' };
  await mkdir(store.legacyRoot, { recursive: true });
  await writeFile(store.imageHostPath(ref), data);
  assert.deepEqual((await store.readImage(ref)).data, data);
  const projected = await store.readImageRequest(ref, { width: 2, height: 2, maxBytes: 1000 });
  assert.equal(projected.width, 2);
  assert.equal(projected.height, 1);
}));

test('attachment limits follow the upstream provider and preserve image names', async () => withStore(async store => {
  assert.deepEqual(store.imageLimits.mediaTypes, ['image/png', 'image/jpeg', 'image/webp', 'image/gif']);
  assert.equal(store.imageLimits.maxImagesPerMessage, 20);
  assert.equal(store.imageLimits.maxMessageImageBytes, 200 * 1024 * 1024);
  const ref = await store.saveImage({ ...screenshot(), name: 'C:\\private\\fictional-photo.png' });
  assert.equal(ref.name, 'fictional-photo.png');
  assert.match(ref.attachmentId, /^sha256:[a-f0-9]{64}$/);
}));

test('JPEG WebP GIF and non-basic PNG share the Android normalization contract', async t => withStore(async store => {
  const invoked = [];
  t.mock.method(store, 'normalizeImage', async input => {
    invoked.push(input.mediaType);
    return { data: screenshot().data, mediaType: 'image/png', bytes: screenshot().data.length, width: 4, height: 3, originalWidth: 8, originalHeight: 6 };
  });
  for (const mediaType of ['image/jpeg', 'image/webp', 'image/gif', 'image/png']) {
    const ref = await store.saveImage({ data: Buffer.from('native codec fixture'), mediaType, name: 'source-image' });
    assert.equal(ref.mediaType, 'image/png');
    assert.equal(ref.name, 'source-image');
    assert.deepEqual(ref.originalDimensions, { width: 8, height: 6 });
    assert.deepEqual((await store.readImage(ref)).data, screenshot().data);
  }
  assert.deepEqual(invoked, ['image/jpeg', 'image/webp', 'image/gif', 'image/png']);
}));

test('a failed image batch publishes none of its otherwise valid members', async () => withStore(async store => {
  await assert.rejects(store.saveImages([screenshot(), { data: Buffer.from('invalid'), mediaType: 'image/svg+xml' }]), error => error.code === 'UNSUPPORTED_IMAGE_TYPE');
  await assert.rejects(readdir(store.root), error => error.code === 'ENOENT');
}));

test('ordinary and empty files are stored verbatim with safe usable filenames', async () => withStore(async store => {
  for (const data of [Buffer.from([0, 1, 2, 255, 10]), Buffer.alloc(0)]) {
    const ref = await store.saveFile({ data, name: '../../private/fictional-report.txt' });
    assert.equal(ref.name, 'fictional-report.txt');
    assert.equal(ref.bytes, data.length);
    assert.equal(ref.attachmentId, `sha256:${createHash('sha256').update(data).digest('hex')}`);
    assert.deepEqual(await collect(store.readFileStream(ref)), data);
    assert.ok(store.fileHostPath(ref).endsWith('/fictional-report.txt'));
  }
  assert.deepEqual(await readdir(join(store.root, 'tmp')), []);
}));

test('file upload streams consume and verify bounded chunks', async () => withStore(async store => {
  const data = Buffer.alloc(300_000, 0x71);
  async function* chunks() {
    for (let offset = 0; offset < data.length; offset += 1024) yield data.subarray(offset, offset + 1024);
  }
  const ref = await store.saveFileStream({ data: chunks(), name: 'stream.bin' });
  assert.deepEqual(await collect(store.readFileStream(ref)), data);
  const refs = await Promise.all([store.saveFile({ data, name: 'stream.bin' }), store.saveFile({ data, name: 'stream.bin' })]);
  assert.deepEqual(refs, [ref, ref]);
  await writeFile(store.fileHostPath(ref), Buffer.alloc(data.length));
  await assert.rejects(collect(store.readFileStream(ref)), error => error.code === 'ATTACHMENT_CORRUPT');
  await assert.rejects(store.saveFile({ data, name: 'stream.bin' }), error => error.code === 'ATTACHMENT_CORRUPT');
  assert.deepEqual(await readFile(store.fileHostPath(ref)), Buffer.alloc(data.length), 'corrupt evidence is never overwritten');
}));

test('file reference paths reject traversal and invalid byte metadata', async () => withStore(async store => {
  const ref = await store.saveFile({ data: Buffer.from('fixture'), name: 'fixture.txt' });
  for (const name of ['../../escape', '..', '.', 'a/b', 'C:\\path\\file', '']) {
    assert.throws(() => store.fileHostPath({ ...ref, name }), error => error.code === 'INVALID_ATTACHMENT_REF');
  }
  assert.throws(() => store.fileHostPath({ ...ref, bytes: -1 }), error => error.code === 'INVALID_ATTACHMENT_REF');
  assert.throws(() => store.imageHostPath({ ...ref, attachmentId: '../../escape' }), error => error.code === 'INVALID_ATTACHMENT_REF');
}));

test('cancellation removes incomplete streamed files', async () => withStore(async store => {
  const controller = new AbortController();
  const reason = new Error('fixture cancelled');
  async function* chunks() {
    yield Buffer.alloc(100, 1);
    controller.abort(reason);
    yield Buffer.alloc(100, 2);
  }
  await assert.rejects(store.saveFileStream({ data: chunks(), signal: controller.signal, name: 'cancel.bin' }), error => error === reason);
  assert.deepEqual(await readdir(store.root), ['tmp']);
  assert.deepEqual(await readdir(join(store.root, 'tmp')), []);
}));

test('request image identities cover every projection input and retain alpha', async () => withStore(async store => {
  const ref = await store.saveImage(screenshot());
  const original = await store.readImageRequest(ref, { width: 4, height: 3, maxBytes: 1000 });
  const smaller = await store.readImageRequest(ref, { width: 2, height: 2, maxBytes: 1000 });
  const differentCap = await store.readImageRequest(ref, { width: 4, height: 3, maxBytes: 1001 });
  assert.notEqual(original.variantId, smaller.variantId);
  assert.notEqual(original.variantId, differentCap.variantId);
  assert.equal(smaller.hasAlpha, true);
  assert.equal(smaller.space, 'srgb');
  assert.ok(smaller.width <= 2 && smaller.height <= 2 && smaller.bytes <= 1000);
  await assert.rejects(store.readImageRequest(ref, { width: 0, height: 2, maxBytes: 1000 }), error => error.code === 'INVALID_ATTACHMENT_REF');
}));

function uncompressedPng() {
  const data = screenshot().data, header = data.subarray(8, 33);
  const pixels = decodePng(data).pixels, rows = Buffer.alloc(3 * (1 + 4 * 4));
  for (let y = 0; y < 3; y++) pixels.copy(rows, y * 17 + 1, y * 16, (y + 1) * 16);
  const compressed = deflateSync(rows, { level: 0 }), idat = Buffer.alloc(compressed.length + 12);
  idat.writeUInt32BE(compressed.length); idat.write('IDAT', 4); compressed.copy(idat, 8);
  idat.writeUInt32BE(crc32(idat.subarray(4, -4)), idat.length - 4);
  return Buffer.concat([data.subarray(0, 8), header, idat, data.subarray(-12)]);
}

test('validated PNG bytes are reused for storage and unchanged request projections', async t => withStore(async store => {
  const data = uncompressedPng();
  assert.notDeepEqual(data, screenshot().data, 'fixture uses a different valid PNG compression');
  t.mock.method(store, 'normalizeImage', async () => { throw new Error('basic PNG must not need the Android codec'); });
  const ref = await store.saveImage({ data, mediaType: 'image/png' });
  assert.deepEqual((await store.readImage(ref)).data, data);
  const projected = await store.readImageRequest(ref, { width: 4, height: 3, maxBytes: 1000 });
  assert.deepEqual(projected.data, data);
  assert.equal(projected.hasAlpha, true);
  assert.deepEqual(decodePng(projected.data).pixels, decodePng(screenshot().data).pixels);
}));

test('native-normalized PNG bytes are reused only within the image policy', async t => withStore(async store => {
  const data = uncompressedPng();
  t.mock.method(store, 'normalizeImage', async () => ({ data, mediaType: 'image/png', bytes: data.length,
    width: 4, height: 3, originalWidth: 8, originalHeight: 6 }));
  const ref = await store.saveImage({ data: Buffer.from('fictional JPEG'), mediaType: 'image/jpeg' });
  assert.deepEqual((await store.readImage(ref)).data, data);
  assert.deepEqual(ref.originalDimensions, { width: 8, height: 6 });
}));

test('PNG passthrough still obeys source pixel limits and normalized dimensions', async () => {
  await withStore(async store => {
    await assert.rejects(store.saveImage(screenshot()), error => error.code === 'IMAGE_TOO_MANY_PIXELS');
  }, { maxImagePixels: 6 });
  await withStore(async store => {
    const ref = await store.saveImage(screenshot());
    assert.ok(ref.width <= 2 && ref.height <= 2);
    assert.deepEqual(ref.originalDimensions, { width: 4, height: 3 });
    assert.equal(decodePng((await store.readImage(ref)).data).channels, 4);
  }, { normalizedImageMaxDimension: 2 });
});

test('PNG byte limits resize valid inputs and reject an impossible request budget', async () => withStore(async store => {
  const data = uncompressedPng(), ref = await store.saveImage({ data, mediaType: 'image/png' });
  const projected = await store.readImageRequest(ref, { width: 4, height: 3, maxBytes: 90 });
  assert.ok(projected.bytes <= 90);
  assert.ok(projected.width < 4 || projected.height < 3);
  assert.equal(decodePng(projected.data).channels, 4);
  await assert.rejects(store.readImageRequest(ref, { width: 4, height: 3, maxBytes: 1 }), error => error.code === 'IMAGE_TOO_LARGE');
}));

test('corrupt PNG bytes cannot take the validated passthrough path', async t => withStore(async store => {
  const invalid = Buffer.from(uncompressedPng()); invalid[40] ^= 1;
  const failure = Object.assign(new Error('invalid image fixture'), { code: 'INVALID_IMAGE' });
  t.mock.method(store, 'normalizeImage', async () => { throw failure; });
  await assert.rejects(store.saveImage({ data: invalid, mediaType: 'image/png' }), error => error === failure);
  await assert.rejects(readdir(store.root), error => error.code === 'ENOENT');
}));

test('image request cancellation is preserved before reading an object', async () => withStore(async store => {
  const ref = await store.saveImage(screenshot()), controller = new AbortController();
  const reason = new Error('image request cancelled'); controller.abort(reason);
  await assert.rejects(store.readImageRequest(ref, { width: 4, height: 3, maxBytes: 1000 }, controller.signal), error => error === reason);
}));

test('native codec stages private files and cleans them after successful normalization', async () => withStore(async store => {
  const input = { data: Buffer.from('encoded JPEG fixture'), mediaType: 'image/jpeg' };
  const png = screenshot().data;
  const output = await normalizeAndroidImage(input, store.imageLimits, store.normalizationPolicy, {
    home: store.home, port: '12345', token: 'a'.repeat(40),
    async fetchImpl(url, options) {
      assert.equal(url, 'http://127.0.0.1:12345/attachments/normalize');
      assert.equal(options.headers.Authorization, `Bearer ${'a'.repeat(40)}`);
      const request = JSON.parse(options.body);
      assert.equal(request.mediaType, 'image/jpeg');
      assert.match(request.id, /^[a-f0-9-]{36}$/);
      const root = join(store.home, 'attachments', 'native');
      assert.deepEqual(await readFile(join(root, `${request.id}.source`)), input.data);
      assert.deepEqual(request.target, store.normalizationPolicy);
      await writeFile(join(root, `${request.id}.png`), png);
      return { ok: true, json: async () => ({ ok: true, result: { mediaType: 'image/png', bytes: png.length, width: 4, height: 3, originalWidth: 4, originalHeight: 3 } }) };
    },
  });
  assert.deepEqual(output.data, png);
  assert.deepEqual(await readdir(join(store.home, 'attachments', 'native')), []);
}));

test('native codec rejection returns an admission error and cleans staged source', async () => withStore(async store => {
  await assert.rejects(normalizeAndroidImage({ data: Buffer.from('bad'), mediaType: 'image/gif' }, store.imageLimits, store.normalizationPolicy, {
    home: store.home, port: '12345', token: 'a'.repeat(40),
    async fetchImpl() { return { ok: true, json: async () => ({ ok: false, error: { code: 'REJECTED', message: 'Invalid GIF image' } }) }; },
  }), error => error.code === 'INVALID_IMAGE');
  assert.deepEqual(await readdir(join(store.home, 'attachments', 'native')), []);
}));

test('missing attachment objects use the upstream recoverable error code', async () => withStore(async store => {
  const image = await store.saveImage(screenshot());
  const file = await store.saveFile({ data: Buffer.from('fixture'), name: 'fixture.txt' });
  await rm(store.imageHostPath(image));
  await rm(store.fileHostPath(file));
  await assert.rejects(store.readImage(image), error => error.code === 'ATTACHMENT_NOT_FOUND');
  await assert.rejects(collect(store.readFileStream(file)), error => error.code === 'ATTACHMENT_NOT_FOUND');
}));
