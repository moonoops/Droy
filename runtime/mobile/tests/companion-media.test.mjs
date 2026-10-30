import test from 'node:test';
import assert from 'node:assert/strict';
import { createRequire, registerHooks } from 'node:module';
import { pathToFileURL } from 'node:url';
import { mkdtemp, rm, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { CompanionStore } from '../companions/store.mjs';
import { openLegacyCompanionStore } from './fixtures/legacy-companion-db.mjs';
import { readSharedAttachment, replyAttachment, stickerCatalog } from '../companions/media.mjs';
import { encodePng } from '../png.mjs';

const installed = createRequire(new URL('../../dsh/package.json', import.meta.url));
const attachmentsUrl = new URL('../attachments.mjs', import.meta.url).href;
const hooks = registerHooks({ resolve(specifier, context, next) {
  if (specifier.startsWith('@deepseek-ai/')) return { url: pathToFileURL(installed.resolve(specifier)).href, shortCircuit: true };
  if (context.parentURL === attachmentsUrl && specifier === './publish.mjs')
    return { url: new URL('./fixtures/host-publish.mjs', import.meta.url).href, shortCircuit: true };
  return next(specifier, context);
} });
const { Context } = await import('@deepseek-ai/cordis');
const { default: MobileAttachments } = await import('../attachments.mjs');
hooks.deregister();

const code = expected => error => error.code === expected;
const png = () => encodePng({ width: 2, height: 2, channels: 4, pixels: Buffer.alloc(16, 200) });
async function fixture(t) {
  const directory = await mkdtemp(join(tmpdir(), 'droy-companion-media-'));
  const ctx = new Context(), store = await openLegacyCompanionStore({ directory: join(directory, 'companions') });
  await ctx.plugin(MobileAttachments, { dshHome: join(directory, 'host') });
  t.after(async () => { await ctx.fiber.dispose(); await store.close(); await rm(directory, { recursive: true, force: true }); });
  let count = 0;
  async function upload({ companionId = 'kaka', data = Buffer.from('Fictional shared content'), name = 'fictional.txt', mediaType = 'text/plain', sent = true, role = 'user', status = 'sent' } = {}) {
    const ref = mediaType.startsWith('image/') ? await ctx.attachments.saveImage({ data, mediaType, name }) : await ctx.attachments.saveFile({ data, name });
    const id = 'upload-' + (++count), value = { id, name: ref.name, mediaType, bytes: ref.bytes, ref,
      url: '/droy/companions/' + companionId + '/attachments/' + id };
    await store.putUpload(companionId, value);
    if (sent) await store.appendMessage(companionId, { id: 'source-' + id, role, status, type: mediaType.startsWith('image/') ? 'image' : 'file', attachments: [value] });
    return value;
  }
  const input = (value, extra = {}) => ({ ctx, store, companionId: 'kaka', attachmentId: value.id, locale: 'en', ...extra });
  return { ctx, store, upload, input };
}

test('shared UTF-8 files return their actual contents and safe full-file-verified prefixes', async t => {
  const f = await fixture(t);
  for (const [name, mediaType, text] of [
    ['fictional.json', 'application/json', '{"city":"Fictional Bay"}'],
    ['fictional.csv', 'text/csv', 'name,count\nFictional,3\n'],
    ['fictional.md', 'application/octet-stream', '# Fictional note\nA shared topic.'],
  ]) {
    const file = await f.upload({ name, mediaType, data: Buffer.from(text) });
    const result = await readSharedAttachment(f.input(file));
    assert.equal(result.kind, 'text');
    assert.equal(result.text, text);
    assert.equal(result.truncated, false);
    assert.equal(result.readBytes, Buffer.byteLength(text));
    assert.equal(result.integrityVerified, true);
    assert.equal(result.sourceMessageId, 'source-' + file.id);
  }
  const text = '共同话题🙂'.repeat(20_000), file = await f.upload({ data: Buffer.from(text) });
  const result = await readSharedAttachment(f.input(file, { maxBytes: 8 }));
  assert.equal(result.text, '共同');
  assert.equal(result.truncated, true);
  assert.equal(result.returnedBytes, 6);
  assert.equal(result.readBytes, Buffer.byteLength(text));
  assert.equal(result.integrityVerified, true);
});

test('truncation still reads the suffix and rejects a full-file digest mismatch', async t => {
  const f = await fixture(t), bytes = Buffer.alloc(180_000, 0x71), file = await f.upload({ data: bytes });
  bytes[bytes.length - 1] = 0x72;
  await writeFile(f.ctx.attachments.fileHostPath(file.ref), bytes);
  await assert.rejects(readSharedAttachment(f.input(file, { maxBytes: 16 })), code('ATTACHMENT_CORRUPT'));
});

test('uploads, avatar-only images, failed sends and another companion do not grant model file access', async t => {
  const f = await fixture(t);
  for (const options of [
    { sent: false },
    { sent: false, mediaType: 'image/png', data: png(), name: 'avatar.png' },
    { role: 'assistant' },
    { status: 'failed' },
    { companionId: 'maimai' },
  ]) {
    const file = await f.upload(options);
    await assert.rejects(readSharedAttachment(f.input(file)), code('COMPANION_ATTACHMENT_SCOPE'));
    await assert.rejects(replyAttachment(f.input(file)), code('COMPANION_ATTACHMENT_SCOPE'));
  }
  await assert.rejects(readSharedAttachment(f.input({id:'../maimai'})), code('COMPANION_INVALID_ID'));
});

test('a sent source remains findable beyond a history page and cannot be read after deletion or forget', async t => {
  const f = await fixture(t);
  for (let i = 0; i < 205; i++) await f.store.appendMessage('kaka', { id: 'history-' + i, role: 'user', text: 'Fictional old message ' + i });
  const file = await f.upload();
  assert.equal((await readSharedAttachment(f.input(file))).kind, 'text');
  await f.store.updateState('kaka', { forgottenMessageIds: ['source-' + file.id] });
  await assert.rejects(readSharedAttachment(f.input(file)), code('COMPANION_ATTACHMENT_SCOPE'));
  await f.store.updateState('kaka', { forgottenMessageIds: [] });
  await f.store.removeMessage('kaka', 'source-' + file.id);
  await assert.rejects(readSharedAttachment(f.input(file)), code('COMPANION_ATTACHMENT_SCOPE'));
  const second = await f.upload();
  const last = await f.store.listMessages('kaka', { limit: 1 });
  await f.store.updateState('kaka', { forgottenThrough: last.nextAfter });
  assert.ok(await f.store.getMessage('kaka', 'source-' + second.id));
  await assert.rejects(readSharedAttachment(f.input(second)), code('COMPANION_ATTACHMENT_SCOPE'));
});

test('actual image refs can be rendered or replied with and content corruption is rejected', async t => {
  const f = await fixture(t), file = await f.upload({ mediaType: 'image/png', data: png(), name: 'fictional-photo.png' });
  const result = await readSharedAttachment(f.input(file));
  assert.equal(result.kind, 'image');
  assert.deepEqual(result.attachment, file.ref);
  assert.equal(result.integrityVerified, true);
  const descriptor = await replyAttachment(f.input(file));
  assert.deepEqual(descriptor, { id: file.id, name: file.name, mediaType: 'image/png', bytes: file.bytes, ref: file.ref,
    url: '/droy/companions/kaka/attachments/' + file.id });
  const textFile = await f.upload();
  await assert.rejects(replyAttachment(f.input(textFile)), code('COMPANION_IMAGE_REQUIRED'));
  await writeFile(f.ctx.attachments.imageHostPath(file.ref), Buffer.alloc(file.bytes));
  await assert.rejects(readSharedAttachment(f.input(file)), code('ATTACHMENT_CORRUPT'));
  await assert.rejects(replyAttachment(f.input(file)), code('ATTACHMENT_CORRUPT'));
});

test('unsupported formats and non-text payloads have explicit paired-language results', async t => {
  const f = await fixture(t);
  const pdf = await f.upload({ name: 'fictional.pdf', mediaType: 'application/pdf', data: Buffer.from('%PDF fictional binary') });
  const unsupportedEn = await readSharedAttachment(f.input(pdf));
  const unsupportedZh = await readSharedAttachment(f.input(pdf, { locale: 'zh' }));
  assert.equal(unsupportedEn.kind, 'unsupported');
  assert.equal(unsupportedEn.reason, 'format');
  assert.equal(unsupportedEn.integrityVerified, false);
  assert.match(unsupportedEn.message, /not supported/);
  assert.match(unsupportedZh.message, /暂不支持/);
  assert.equal('text' in unsupportedEn, false);
  const invalid = await f.upload({ data: Buffer.concat([Buffer.alloc(70_000, 0x61), Buffer.from([0xff])]) });
  const encoding = await readSharedAttachment(f.input(invalid, { maxBytes: 4 }));
  assert.equal(encoding.kind, 'unsupported');
  assert.equal(encoding.reason, 'encoding');
  assert.equal(encoding.integrityVerified, true);
  assert.equal(encoding.readBytes, 70_001);
  const binary = await f.upload({ data: Buffer.from('Fictional\0binary') });
  assert.equal((await readSharedAttachment(f.input(binary))).reason, 'binary');
});

test('cancelled reads never return a partial successful result and close the provider iterator', async t => {
  const f = await fixture(t), file = await f.upload({ data: Buffer.alloc(180_000, 0x71) });
  const controller = new AbortController(), reason = new Error('Fictional cancellation');
  let closed = false;
  const attachments = { async *readFileStream(ref, signal) {
    try {
      for await (const chunk of f.ctx.attachments.readFileStream(ref, signal)) {
        yield chunk;
        controller.abort(reason);
      }
    } finally { closed = true; }
  } };
  await assert.rejects(readSharedAttachment(f.input(file, { ctx: {attachments}, signal: controller.signal, maxBytes: 4 })), error => error === reason);
  assert.equal(closed, true);
  const abort = new AbortController();abort.abort(reason);
  await assert.rejects(readSharedAttachment(f.input(file, { signal: abort.signal })), error => error === reason);
});

test('deleting the source while bytes are read prevents the late result from reaching the model', async t => {
  const f = await fixture(t), file = await f.upload({ data: Buffer.alloc(180_000, 0x71) });
  let removed = false;
  const attachments = { async *readFileStream(ref, signal) {
    for await (const chunk of f.ctx.attachments.readFileStream(ref, signal)) {
      if (!removed) { removed = true; await f.store.removeMessage('kaka', 'source-' + file.id); }
      yield chunk;
    }
  } };
  await assert.rejects(readSharedAttachment(f.input(file, { ctx: {attachments} })), code('COMPANION_ATTACHMENT_SCOPE'));
});

test('excluded shared sources cannot be read or replied with, including exclusion during reading',async t=>{
  const f=await fixture(t),file=await f.upload({data:Buffer.alloc(180_000,0x71)});
  let excluded=false;
  const attachments={async *readFileStream(ref,signal){
    for await(const chunk of f.ctx.attachments.readFileStream(ref,signal)){
      if(!excluded){excluded=true;await f.store.excludeDerivedMessages('kaka',['source-'+file.id]);}
      yield chunk;
    }
  }};
  await assert.rejects(readSharedAttachment(f.input(file,{ctx:{attachments}})),code('COMPANION_ATTACHMENT_SCOPE'));
  await assert.rejects(readSharedAttachment(f.input(file)),code('COMPANION_ATTACHMENT_SCOPE'));
  const image=await f.upload({data:png(),mediaType:'image/png',name:'fictional.png'});
  await f.store.excludeDerivedMessages('kaka',['source-'+image.id]);
  await assert.rejects(replyAttachment(f.input(image)),code('COMPANION_ATTACHMENT_SCOPE'));
  assert.ok(await f.store.getMessage('kaka','source-'+image.id));
});

test('built-in stickers expose factual local metadata in either language without shared mutation', () => {
  const zh = stickerCatalog('zh'), en = stickerCatalog('en');
  assert.deepEqual(zh.map(item => item.id), en.map(item => item.id));
  assert.equal(zh.length, 3);
  for (let i = 0; i < zh.length; i++) {
    assert.ok(zh[i].description && en[i].description);
    assert.ok(zh[i].tags.length && en[i].tags.length);
    assert.equal(zh[i].url, '/droy/companions/assets/' + zh[i].id + '.png');
    assert.notEqual(zh[i].label, en[i].label);
  }
  zh[0].tags.length = 0;
  assert.ok(stickerCatalog('zh')[0].tags.length);
});
