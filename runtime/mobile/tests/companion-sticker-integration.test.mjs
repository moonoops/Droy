import test, { after } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { createRequire, registerHooks } from 'node:module';
import { pathToFileURL } from 'node:url';
import { createServer } from 'node:http';
import { openLegacyCompanionStore } from './fixtures/legacy-companion-db.mjs';
import { StickerPackLibrary } from '../companions/sticker-packs.mjs';
import { stickerCatalog, stickerContext } from '../companions/media.mjs';
import { prepareReply } from '../companions/delivery.mjs';
import { buildReplyerRequest } from '../companions/maibot-prompts.mjs';
import { crc32, encodePng } from '../png.mjs';

const installed = createRequire(new URL('../../dsh/package.json', import.meta.url));
const hooks = registerHooks({ resolve(specifier, context, next) {
  if (specifier === '@droyagent/memory/memos') return { url: new URL('../../memory/memos/store.mjs', import.meta.url).href, shortCircuit: true };
  if (specifier === '@droyagent/memory/store') return { url: new URL('../../memory/store.mjs', import.meta.url).href, shortCircuit: true };
  if (specifier.startsWith('@deepseek-ai/')) return { url: pathToFileURL(installed.resolve(specifier)).href, shortCircuit: true };
  return next(specifier, context);
} });
after(() => hooks.deregister());
const [{ CompanionRuntime }, { createCompanionHandler }] = await Promise.all([
  import('../companions/runtime.mjs'), import('../companions/host.mjs'),
]);
const token = 'fictional-sticker-pack-integration-token';
const png = () => encodePng({ width: 2, height: 2, channels: 4, pixels: Buffer.alloc(16, 190) });
function imageZip(names) {
  const files = [], central = []; let offset = 0;
  for (const path of names) {
    const name = Buffer.from(path), data = png(), checksum = crc32(data);
    const local = Buffer.alloc(30); local.writeUInt32LE(0x04034b50); local.writeUInt16LE(20, 4); local.writeUInt16LE(0x800, 6);
    local.writeUInt32LE(checksum, 14); local.writeUInt32LE(data.length, 18); local.writeUInt32LE(data.length, 22); local.writeUInt16LE(name.length, 26);
    const header = Buffer.alloc(46); header.writeUInt32LE(0x02014b50); header.writeUInt16LE(0x314, 4); header.writeUInt16LE(20, 6);
    header.writeUInt16LE(0x800, 8); header.writeUInt32LE(checksum, 16); header.writeUInt32LE(data.length, 20);
    header.writeUInt32LE(data.length, 24); header.writeUInt16LE(name.length, 28); header.writeUInt32LE(offset, 42);
    files.push(local, name, data); central.push(header, name); offset += local.length + name.length + data.length;
  }
  const directory = Buffer.concat(central), end = Buffer.alloc(22); end.writeUInt32LE(0x06054b50);
  end.writeUInt16LE(names.length, 8); end.writeUInt16LE(names.length, 10); end.writeUInt32LE(directory.length, 12); end.writeUInt32LE(offset, 16);
  return Buffer.concat([...files, directory, end]);
}

async function fixture(t, { locale = 'en' } = {}) {
  const directory = await mkdtemp(join(tmpdir(), 'droy-sticker-http-'));
  const store = await openLegacyCompanionStore({ directory });
  await store.updateProfile('kaka', { model: { provider: 'fictional', model: 'fixture' } });
  const library = await new StickerPackLibrary(join(directory, 'sticker-packs'), { builtinCatalog: stickerCatalog }).open();
  const ctx = { logger: { warn() {} }, agents: { get() {} }, workspaceRegistry: { get() {} },
    agentDefaultModel: { currentSelection: () => ({ provider: 'fictional', model: 'fixture' }) },
    sessionController: { modelCatalog: async () => ({ groups: [] }) },
    settings: { describe: () => [{ ns: 'locale', value: { preference: locale } }] } };
  const runtime = new CompanionRuntime({ ctx, store, directory, stickerLibrary: library,
    options: { getClock: async () => ({ now: Date.parse('2026-10-30T04:00:00Z'), timeZone: 'Asia/Shanghai' }) } });
  const previousToken = process.env.DROY_WEB_TOKEN; process.env.DROY_WEB_TOKEN = token;
  const handler = createCompanionHandler({ ctx, runtime, store });
  const server = createServer((req, res) => handler(req, res).catch(error => { res.writeHead(500); res.end(String(error)); }));
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  t.after(async () => {
    server.closeAllConnections(); await new Promise(resolve => server.close(resolve));
    await runtime.close(); await store.close(); await rm(directory, { recursive: true, force: true });
    if (previousToken === undefined) delete process.env.DROY_WEB_TOKEN; else process.env.DROY_WEB_TOKEN = previousToken;
  });
  const url = 'http://127.0.0.1:' + server.address().port + '/droy/companions';
  const request = (path = '', method = 'GET', value) => fetch(url + path, { method,
    headers: { cookie: 'droy_session=' + token, 'content-type': 'application/json' },
    ...(value === undefined ? {} : { body: JSON.stringify(value) }) });
  const api = async (...args) => { const response = await request(...args); return { status: response.status, value: await response.json() }; };
  const owner = { kind: 'chat', companionId: 'kaka', sessionId: 'fictional-session' };
  return { directory, store, runtime, library, owner, api, request, url };
}

test('authenticated ZIP import shares storage but enables only the importing companion; disabling retains history', async t => {
  const f = await fixture(t), archive = imageZip(['happy/fictional-cat.png']);
  assert.equal((await fetch(f.url + '/kaka/sticker-packs', { method: 'POST' })).status, 401);
  const imported = await f.api('/kaka/sticker-packs', 'POST', { name: 'Fictional cats.zip', base64: archive.toString('base64') });
  assert.equal(imported.status, 201, JSON.stringify(imported.value));
  const pack = imported.value.pack, root = (await f.api()).value;
  assert.equal(root.stickerPacks.filter(item => item.id === pack.id).length, 1);
  const sticker = root.stickers.find(item => item.packId === pack.id);
  assert.ok(sticker); assert.doesNotMatch(JSON.stringify(sticker), new RegExp(f.directory));
  assert.ok(f.runtime.stickers(await f.store.getProfile('kaka'), { all: true }).some(item => item.id === sticker.id));
  assert.ok(!f.runtime.stickers(await f.store.getProfile('maimai'), { all: true }).some(item => item.id === sticker.id));
  assert.equal((await fetch('http://127.0.0.1:' + new URL(f.url).port + sticker.url)).status, 401);
  const media = await f.request('/sticker-media/' + sticker.id);
  assert.equal(media.status, 200); assert.equal(media.headers.get('content-type'), 'image/png');
  assert.deepEqual(Buffer.from(await media.arrayBuffer()), png());
  await f.store.appendMessage('kaka', { role: 'assistant', type: 'sticker', stickerId: sticker.id });
  assert.equal((await f.api('/kaka', 'PATCH', { settings: { stickerPackIds: [] } })).status, 200);
  assert.deepEqual(f.runtime.stickers(await f.store.getProfile('kaka')), []);
  assert.ok((await f.api()).value.stickers.some(item => item.id === sticker.id));
  assert.equal((await f.request('/sticker-media/' + sticker.id)).status, 200);
  const restarted = await new StickerPackLibrary(join(f.directory, 'sticker-packs'), { builtinCatalog: stickerCatalog }).open();
  assert.ok(restarted.catalog().some(item => item.id === sticker.id));
  const again = await f.api('/kaka/sticker-packs', 'POST', { name: 'Another name.zip', base64: archive.toString('base64') });
  assert.equal(again.value.pack.id, pack.id); assert.equal((await f.api()).value.stickerPacks.length, 2);
});

test('pack selections preserve other settings and reject unknown IDs, duplicate IDs and malformed imports in both languages', async t => {
  for (const locale of ['en', 'zh']) {
    const f = await fixture(t, { locale }), before = await f.store.getProfile('kaka');
    assert.equal((await f.api('/kaka', 'PATCH', { settings: { stickerPackIds: [] } })).status, 200);
    const after = await f.store.getProfile('kaka');
    assert.equal(after.settings.memoryEnabled, before.settings.memoryEnabled);
    assert.deepEqual(after.settings.quietHours, before.settings.quietHours);
    for (const ids of [['unknown'], ['droy-default', 'droy-default'], null, 'droy-default'])
      assert.equal((await f.api('/kaka', 'PATCH', { settings: { stickerPackIds: ids } })).status, 400);
    for (const base64 of ['%%%%', 'AAAA=AAA', '', 'YQ==\n', 'YQ==']) {
      const response = await f.api('/kaka/sticker-packs', 'POST', { name: 'bad.zip', base64 });
      assert.equal(response.status, 400); assert.equal(response.value.error, 'COMPANION_STICKER_PACK_INVALID');
      assert.match(response.value.message, locale === 'zh' ? /图包/ : /pack/);
    }
    assert.equal((await f.api('/sticker-media/not-known')).status, 404);
    assert.equal((await f.api('/sticker-media/%2e%2e%2fcompanions.sqlite')).status, 404);
  }
});

test('large libraries return bounded search candidates and emoji selection uses local positions', async t => {
  const f = await fixture(t), pack = await f.runtime.importStickerPack('kaka', imageZip(Array.from({ length: 16 }, (_, i) => 'happy/fictional-' + i + '.png')), { name: 'Fictional many.zip' });
  const profile = await f.store.getProfile('kaka'), selected = f.library.catalog('en', { packIds: [pack.id] }).find(item => item.label === 'fictional-15');
  const candidates = f.runtime.stickers(profile, { query: 'fictional-15' });
  assert.equal(candidates.length, 1); assert.ok(candidates[0].emoji_id > 12);
  const actualContext = await f.runtime.context('kaka', f.owner);
  assert.equal(actualContext.availableStickers.length, 12);
  let generated = false;
  f.runtime.generate = async (_profile, _system, input) => {
    generated = true; assert.equal(input.items.length, 1); assert.equal(input.items[0].emoji_index, 1);
    return '{"emoji_index":1}';
  };
  const response = await f.runtime.sendEmoji(f.owner, { query: 'fictional-15' });
  assert.equal(generated, true); assert.equal(response.delivered, true); assert.equal(response.stickerId, selected.id);
  assert.equal((await f.store.listMessages('kaka')).messages[0].stickerId, selected.id);
});

test('disabling a pack during selection omits the sticker, while text and old catalog meanings remain usable', async t => {
  const f = await fixture(t), pack = await f.runtime.importStickerPack('kaka', imageZip(['happy/fictional-heart.png']), { name: 'Fictional hearts.zip' });
  const [sticker] = f.library.catalog('en', { packIds: [pack.id] });
  await f.store.appendMessage('kaka', { id: 'shared-sticker', role: 'user', type: 'sticker', stickerId: sticker.id });
  const context = await f.runtime.context('kaka', f.owner);
  assert.equal(context.messages[0].sticker.description, sticker.description);
  assert.equal(stickerContext({ stickerId: sticker.id, sticker: { description: 'Untrusted' } }, 'en', f.runtime.catalog()).sticker.description, sticker.description);
  const request = buildReplyerRequest({ profile: await f.store.getProfile('kaka'), locale: 'en', context,
    options: { stickerCatalog: f.runtime.catalog() } });
  assert.match(JSON.stringify(request), /fictional-heart/); assert.doesNotMatch(JSON.stringify(request), /Meaning unknown/);
  f.runtime.generate = async () => { await f.store.updateProfile('kaka', { settings: { stickerPackIds: [] } }); return '{"emoji_index":1}'; };
  const response = await f.runtime.sendEmoji(f.owner, { query: 'fictional-heart' });
  assert.equal(response.delivered, false); assert.equal(response.reason, 'sticker_no_longer_available');
  assert.equal((await f.store.listMessages('kaka')).messages.length, 1);
  f.runtime.generate = async () => assert.fail('Disabled stickers must not call the selector');
  assert.equal((await f.runtime.sendEmoji(f.owner, {})).reason, 'no_available_stickers');
  assert.ok(f.runtime.catalog().some(item => item.id === sticker.id));
});

test('a saved reply omits disabled sticker parts and completes recovery without losing text or duplicating sent parts', async t => {
  const f = await fixture(t), pack = await f.runtime.importStickerPack('kaka', imageZip(['happy/fictional-wave.png']), { name: 'Fictional waves.zip' });
  const [sticker] = f.library.catalog('en', { packIds: [pack.id] });
  await f.store.appendMessage('kaka', { id: 'fictional-question', role: 'user', text: 'A fictional question.' });
  const draft = await prepareReply({ store: f.store, companionId: 'kaka', sourceMessageIds: ['fictional-question'],
    parts: [{ type: 'text', text: 'A complete fictional answer.' }, { type: 'sticker', text: '', stickerId: sticker.id }] });
  await f.store.appendMessage('kaka', draft.parts[0]);
  await f.store.updateProfile('kaka', { settings: { stickerPackIds: [] } });
  const result = await f.runtime.publish('kaka', draft);
  assert.deepEqual(result.messageIds, [draft.parts[0].id]); assert.deepEqual(result.newMessageIds, []);
  assert.equal((await f.store.getState('kaka')).replyDraft, null);
  assert.equal((await f.store.listMessages('kaka')).messages.filter(item => item.role === 'assistant').length, 1);
  assert.equal(await f.store.getMessage('kaka', draft.parts[1].id), null);
  const onlySticker = await prepareReply({ store: f.store, companionId: 'kaka', sourceMessageIds: [], parts: [{ type: 'sticker', text: '', stickerId: sticker.id }] });
  const omitted = await f.runtime.publish('kaka', onlySticker);
  assert.equal(omitted.suppressed, true); assert.deepEqual(omitted.messageIds, []);
  assert.equal((await f.store.getState('kaka')).replyDraft, null);
});

test('accepted user sticker replay remains idempotent after disabling its pack and conflicting reuse still fails', async t => {
  const f = await fixture(t), pack = await f.runtime.importStickerPack('kaka', imageZip(['happy/fictional-ok.png']), { name: 'Fictional ok.zip' });
  const [sticker] = f.library.catalog('en', { packIds: [pack.id] });
  f.runtime.enqueue = async () => {};
  const input = { clientId: 'fictional-outbox-id', stickerId: sticker.id }, first = await f.runtime.submit('kaka', input);
  await f.store.updateProfile('kaka', { settings: { stickerPackIds: [] } });
  assert.equal((await f.runtime.submit('kaka', input)).id, first.id);
  await assert.rejects(f.runtime.submit('kaka', { ...input, text: 'Changed' }), error => error.code === 'COMPANION_MESSAGE_CONFLICT');
  await assert.rejects(f.runtime.submit('kaka', { ...input, clientId: 'new-id' }), error => error.code === 'COMPANION_INVALID_STICKER');
});

test('a pack disabled while generating a text reply omits only its sticker', async t => {
  const f = await fixture(t), pack = await f.runtime.importStickerPack('kaka', imageZip(['happy/fictional-reply.png']), { name: 'Fictional reply.zip' });
  const [sticker] = f.library.catalog('en', { packIds: [pack.id] });
  f.runtime.ctx.llm = { prepareCall: async () => ({ inputModalities: ['text'] }) };
  f.runtime.generate = async () => { await f.store.updateProfile('kaka', { settings: { stickerPackIds: [] } }); return 'A complete fictional reply.'; };
  const reply = await f.runtime.reply(f.owner, { stickerId: sticker.id });
  assert.equal(reply.delivered, true); assert.deepEqual(reply.parts.map(part => part.type), ['text']);
  assert.equal((await f.store.listMessages('kaka')).messages[0].text, 'A complete fictional reply.');
  assert.equal((await f.store.getState('kaka')).replyDraft, null);
});

test('skipping a middle sticker preserves text/image message IDs in the reply result', async t => {
  const f = await fixture(t), pack = await f.runtime.importStickerPack('kaka', imageZip(['happy/fictional-reply.png']), { name: 'Fictional reply.zip' });
  const [sticker] = f.library.catalog('en', { packIds: [pack.id] });
  const upload = { id: 'fictional-image', name: 'fictional.png', mediaType: 'image/png', bytes: 1,
    ref: { attachmentId: 'sha256:' + 'a'.repeat(64), name: 'fictional.png', mediaType: 'image/png', bytes: 1 },
    url: '/droy/companions/kaka/attachments/fictional-image' };
  await f.store.putUpload('kaka', upload);
  await f.store.appendMessage('kaka', { id: 'shared-image', role: 'user', type: 'image', attachments: [upload] });
  f.runtime.ctx.attachments = { readImage: async () => ({ data: png() }) };
  f.runtime.ctx.llm = { prepareCall: async () => ({ inputModalities: ['text'] }) };
  f.runtime.generate = async () => 'A complete fictional reply.';
  const publish = f.runtime.publish.bind(f.runtime);
  f.runtime.publish = async (...args) => {
    await f.store.updateProfile('kaka', { settings: { stickerPackIds: [] } });
    return publish(...args);
  };
  const reply = await f.runtime.reply(f.owner, { stickerId: sticker.id, imageIds: [upload.id] });
  assert.equal(reply.delivered, true); assert.deepEqual(reply.parts.map(part => part.type), ['text', 'image']);
  assert.deepEqual(reply.parts.map(part => part.messageId), reply.messageIds);
  for (const part of reply.parts) assert.equal((await f.store.getMessage('kaka', part.messageId)).type, part.type);
  assert.equal((await f.store.getState('kaka')).replyDraft, null);
});
