import test, { after } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { createRequire, registerHooks } from 'node:module';
import { pathToFileURL } from 'node:url';
import { createServer } from 'node:http';
import { createHash } from 'node:crypto';
import { setTimeout as delay } from 'node:timers/promises';
import { openLegacyCompanionStore } from './fixtures/legacy-companion-db.mjs';
import { StickerPackLibrary } from '../companions/sticker-packs.mjs';
import { OnlineStickerService, ONLINE_STICKER_CATALOG_URL, ONLINE_STICKER_INDEX_URL } from '../companions/online-stickers.mjs';
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
const gif = () => Buffer.from('47494638396101000100800000000000ffffff21ff0b4e45545343415045322e300301000000'
  + '21f904000a0000002c0000000001000100000202440100'
  + '21f904000a0000002c00000000010001000002024c01003b', 'hex');
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
  const ctx = { get(name) { return this[name]; }, logger: { warn() {} }, agents: { get() {} }, workspaceRegistry: { get() {} },
    agentDefaultModel: { currentSelection: () => ({ provider: 'fictional', model: 'fixture' }) },
    sessionController: { modelCatalog: async () => ({ groups: [] }) },
    settings: { describe: () => [{ ns: 'locale', value: { preference: locale } }] } };
  const runtime = new CompanionRuntime({ ctx, store, directory, stickerLibrary: library,
    options: { getClock: async () => ({ now: Date.parse('2026-10-30T04:00:00Z'), timeZone: 'Asia/Shanghai' }) } });
  const previousToken = process.env.DROY_WEB_TOKEN; process.env.DROY_WEB_TOKEN = token;
  const handler = createCompanionHandler({ ctx, runtime, store });
  const handled = [];
  const server = createServer((req, res) => {
    const done = handler(req, res).catch(error => { res.writeHead(500); res.end(String(error)); });
    handled.push({ req, res, done });
  });
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
  return { directory, store, runtime, library, owner, api, request, url, handled };
}

function publicStickerFixture(f, { checksum = true, status = 200, imageData = png(), extension = 'png' } = {}) {
  const packId = 'fictional-waves', archiveUrl = 'https://stickers.example.org/waves.zip', imageUrl = 'https://stickers.example.org/wave.' + extension;
  const archive = imageZip(['daily/Fictional-wave.png', 'happy/Fictional-cheer.png']);
  const pack = { id: packId, name: 'Fictional waves', description: 'Friendly fictional greetings', count: 2, bytes: archive.length,
    version: '1.0.0', license: 'CC0', archiveUrl, sha256: checksum ? createHash('sha256').update(archive).digest('hex') : '0'.repeat(64) };
  const catalog = { schemaVersion: 1, packs: [pack] };
  const index = { schemaVersion: 1, packs: { [packId]: pack }, stickers: [{ id: packId + '/daily/wave', pack: packId,
    name: 'Fictional-wave', description: 'A fictional friend waves hello', tag: 'daily', keywords: ['wave', 'hello'],
    caption: 'A fictional friend waves hello', path: 'memes/daily/wave.' + extension, imageUrl }] };
  const calls = [];
  f.runtime.onlineStickers = new OnlineStickerService({ library: f.library,
    allowedHosts: ['raw.githubusercontent.com', 'stickers.example.org'],
    resolve: async () => [{ address: '93.184.216.34', family: 4 }],
    fetchImpl: async (url, options) => {
      calls.push({ url, signal: options.signal });
      assert.equal(options.headers.cookie, undefined);
      assert.equal(options.headers.authorization, undefined);
      if (status !== 200) return new Response('Fictional source failure', { status });
      if (url === ONLINE_STICKER_CATALOG_URL) return Response.json(catalog);
      if (url === ONLINE_STICKER_INDEX_URL) return Response.json(index);
      if (url === archiveUrl) return new Response(archive);
      if (url === imageUrl) return new Response(imageData);
      assert.fail('Unregistered public request: ' + url);
    },
  });
  return { calls, packId, archiveUrl, imageUrl };
}

async function eventually(read, description) {
  const deadline = Date.now() + 2000;
  while (Date.now() < deadline) { if (await read()) return; await delay(5); }
  assert.fail('Timed out: ' + description);
}

async function downloadedImage(f) {
  return f.library.importOnlineImage({ bytes: png(), label: 'Fictional-wave', description: 'A fictional friend waves hello',
    tag: 'daily', keywords: 'wave hello', source: 'https://stickers.example.org/wave.png', license: 'CC0' });
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

test('online image search downloads one reusable local sticker, enables only its owner and sends that exact ID', async t => {
  const f = await fixture(t), remote = publicStickerFixture(f), other = await f.store.getProfile('maimai');
  const search = await f.api('/sticker-search?query=wave&tag=daily&format=static');
  assert.equal(search.status, 200, JSON.stringify(search.value));
  assert.equal(search.value.items.length, 1);
  const candidate = search.value.items[0];
  assert.equal(candidate.format, 'static');
  assert.equal(candidate.license, 'CC0'); assert.match(candidate.description, /waves hello/);
  assert.equal(f.library.packs().length, 1, 'Search does not install images');

  const result = await f.api('/kaka/sticker-search', 'POST', { stickerId: candidate.id });
  assert.equal(result.status, 201, JSON.stringify(result.value));
  assert.equal(result.value.downloaded, true);
  const { pack, sticker } = result.value;
  assert.equal(pack.count, 1);
  assert.equal(sticker.source, remote.imageUrl);
  assert.ok(f.runtime.stickers(await f.store.getProfile('kaka'), { query: 'wave' }).some(row => row.id === sticker.id));
  assert.deepEqual((await f.store.getProfile('maimai')).settings.stickerPackIds, other.settings.stickerPackIds);
  assert.ok(!f.runtime.stickers(other, { all: true }).some(row => row.id === sticker.id));

  f.runtime.generate = async () => assert.fail('Sending an exact downloaded ID must not choose a different sticker');
  const sent = await f.runtime.sendEmoji(f.owner, { stickerId: sticker.id });
  assert.equal(sent.delivered, true); assert.equal(sent.stickerId, sticker.id);
  assert.equal((await f.store.listMessages('kaka')).messages[0].stickerId, sticker.id);
  const repeated = await f.api('/kaka/sticker-search', 'POST', { stickerId: candidate.id });
  assert.equal(repeated.status, 201); assert.equal(repeated.value.sticker.id, sticker.id);
  assert.equal(repeated.value.pack.count, 1); assert.equal(f.library.packs().length, 2);

  await f.api('/kaka', 'PATCH', { settings: { stickerPackIds: [] } });
  await assert.rejects(f.runtime.sendEmoji(f.owner, { stickerId: sticker.id }), error => error.code === 'COMPANION_INVALID_STICKER');
  const historical = await f.request('/sticker-media/' + sticker.id);
  assert.equal(historical.status, 200); assert.deepEqual(Buffer.from(await historical.arrayBuffer()), png());
});

test('online GIFs retain original animation bytes, owner scope and historical meaning after disabling the pack', async t => {
  const f = await fixture(t), image = gif(), remote = publicStickerFixture(f, { imageData: image, extension: 'gif' });
  const other = await f.store.getProfile('maimai');
  const search = await f.api('/sticker-search?query=wave&tag=daily&format=gif');
  assert.equal(search.status, 200, JSON.stringify(search.value));
  assert.equal(search.value.items.length, 1, 'An animated GIF in the public index remains discoverable');
  assert.equal(search.value.items[0].format, 'gif');
  const staticOnly = await f.api('/sticker-search?query=wave&tag=daily&format=static');
  assert.equal(staticOnly.status, 200); assert.deepEqual(staticOnly.value.items, [], 'A GIF is excluded from static-only search');
  const downloaded = await f.api('/kaka/sticker-search', 'POST', { stickerId: search.value.items[0].id });
  assert.equal(downloaded.status, 201, JSON.stringify(downloaded.value));
  const { pack, sticker } = downloaded.value;
  assert.equal(sticker.source, remote.imageUrl);
  assert.ok((await f.store.getProfile('kaka')).settings.stickerPackIds.includes(pack.id));
  assert.deepEqual((await f.store.getProfile('maimai')).settings.stickerPackIds, other.settings.stickerPackIds);
  assert.ok(!f.runtime.stickers(other, { all: true }).some(row => row.id === sticker.id));
  const media = await f.request('/sticker-media/' + sticker.id);
  assert.equal(media.status, 200); assert.equal(media.headers.get('content-type'), 'image/gif');
  assert.deepEqual(Buffer.from(await media.arrayBuffer()), image, 'Downloading does not flatten the GIF to a frame');

  f.runtime.generate = async () => assert.fail('The chosen local GIF ID must be sent directly');
  const sent = await f.runtime.sendEmoji(f.owner, { stickerId: sticker.id });
  assert.equal(sent.delivered, true); assert.equal(sent.stickerId, sticker.id);
  const message = await f.store.getMessage('kaka', sent.messageIds[0]);
  await f.api('/kaka', 'PATCH', { settings: { stickerPackIds: [] } });
  assert.deepEqual(f.runtime.stickers(await f.store.getProfile('kaka')), []);
  assert.equal(stickerContext(message, 'en', f.runtime.catalog()).sticker.description, 'A fictional friend waves hello');
  const historical = await f.request('/sticker-media/' + sticker.id);
  assert.equal(historical.headers.get('content-type'), 'image/gif');
  assert.deepEqual(Buffer.from(await historical.arrayBuffer()), image);
});

test('exact sticker sends honor caller cancellation and runtime closure without creating a reply or message', async t => {
  for (const reason of ['cancelled', 'closed']) await t.test(reason, async child => {
    const f = await fixture(child), abort = new AbortController();
    const before = (await f.store.getState('kaka')).replyDraft;
    f.runtime.generate = async () => assert.fail('An exact sticker does not call the selector');
    if (reason === 'cancelled') abort.abort();
    else await f.runtime.close();
    await assert.rejects(f.runtime.sendEmoji(f.owner, { stickerId: 'test-sticker-ok' }, abort.signal), error => error.name === 'AbortError');
    assert.equal((await f.store.listMessages('kaka')).messages.length, 0);
    assert.equal((await f.store.getState('kaka')).replyDraft, before);
  });
});

test('market download verifies and installs one pack while duplicate installs preserve the library and other companions', async t => {
  const f = await fixture(t), remote = publicStickerFixture(f), other = await f.store.getProfile('maimai');
  const market = await f.api('/sticker-market?query=waves');
  assert.equal(market.status, 200); assert.equal(market.value.packs[0].id, remote.packId);
  const first = await f.api('/kaka/sticker-market', 'POST', { packId: remote.packId });
  assert.equal(first.status, 201, JSON.stringify(first.value));
  assert.equal(first.value.installed, 2); assert.equal(first.value.pack.count, 2); assert.equal(first.value.skippedAnimated, 0);
  assert.equal(first.value.pack.originId, remote.packId);
  assert.ok((await f.store.getProfile('kaka')).settings.stickerPackIds.includes(first.value.pack.id));
  assert.deepEqual((await f.store.getProfile('maimai')).settings, other.settings);
  const second = await f.api('/kaka/sticker-market', 'POST', { packId: remote.packId });
  assert.equal(second.status, 201); assert.equal(second.value.pack.id, first.value.pack.id);
  assert.equal(f.library.packs().length, 2);
  assert.equal(remote.calls.filter(call => call.url === remote.archiveUrl).length, 1);
});

test('online routes authenticate before reading sources and reject invalid queries or arbitrary download URLs', async t => {
  const f = await fixture(t), remote = publicStickerFixture(f);
  assert.equal((await fetch(f.url + '/sticker-market')).status, 401);
  assert.equal((await fetch(f.url + '/kaka/sticker-search', { method: 'POST' })).status, 401);
  for (const path of ['/sticker-search', '/sticker-search?query=%20', '/sticker-search?query=wave&url=https://example.org',
    '/sticker-search?query=' + 'x'.repeat(201), '/sticker-search?query=wave&format=video',
    '/sticker-market?query=wave&format=gif']) assert.equal((await f.api(path)).status, 400);
  for (const value of [{}, { url: 'https://example.org/picture.png' }, { stickerId: 'a', packId: 'b' }, { stickerId: '' }, null])
    assert.equal((await f.api('/kaka/sticker-search', 'POST', value)).status, 400);
  assert.equal((await f.api('/kaka/sticker-search', 'POST', { stickerId: 'https://example.org/picture.png' })).status, 404);
  assert.equal((await f.api('/kaka/sticker-market', 'POST', { packId: 'https://example.org/pack.zip' })).status, 404);
  assert.equal(remote.calls.length, 0, 'Rejected inputs must not cause remote requests');
  assert.equal(f.library.packs().length, 1);
});

test('network and checksum failures have paired language messages and leave existing packs unchanged', async t => {
  for (const locale of ['zh', 'en']) await t.test(locale, async child => {
    const f = await fixture(child, { locale });
    publicStickerFixture(f, { status: 503 });
    const network = await f.api('/sticker-market');
    assert.equal(network.status, 503); assert.equal(network.value.error, 'COMPANION_STICKER_ONLINE_NETWORK');
    assert.match(network.value.message, locale === 'zh' ? /网络/ : /connection/);

    const remote = publicStickerFixture(f, { checksum: false });
    const checksum = await f.api('/kaka/sticker-market', 'POST', { packId: remote.packId });
    assert.equal(checksum.status, 400); assert.equal(checksum.value.error, 'COMPANION_STICKER_ONLINE_CHECKSUM');
    assert.match(checksum.value.message, locale === 'zh' ? /校验/ : /verification/);
    assert.equal(f.library.packs().length, 1);
    assert.deepEqual((await f.store.getProfile('kaka')).settings.stickerPackIds ?? ['droy-default'], ['droy-default']);
  });
});

test('HTTP disconnect cancels the ongoing download and cannot enable a late result', async t => {
  const f = await fixture(t), saved = await downloadedImage(f), entered = Promise.withResolvers(), release = Promise.withResolvers();
  t.after(() => release.resolve());
  f.runtime.onlineStickers = { async download(_id, { signal }) { entered.resolve(signal); await release.promise; return saved; } };
  const abort = new AbortController();
  const response = fetch(f.url + '/kaka/sticker-search', { method: 'POST', signal: abort.signal,
    headers: { cookie: 'droy_session=' + token, 'content-type': 'application/json' }, body: JSON.stringify({ stickerId: 'fictional-id' }) })
    .then(value => ({ value }), error => ({ error }));
  const signal = await entered.promise;
  abort.abort();
  assert.equal((await response).error?.name, 'AbortError');
  await eventually(() => signal.aborted, 'the server observes the disconnected request');
  release.resolve(); await f.runtime.maintenance.drainMemorySpace('kaka');
  assert.deepEqual((await f.store.getProfile('kaka')).settings.stickerPackIds ?? ['droy-default'], ['droy-default']);
});

test('a POST disconnected during its initial profile read cannot start or enable a later download', async t => {
  const f = await fixture(t), saved = await downloadedImage(f), entered = Promise.withResolvers(), release = Promise.withResolvers();
  const original = f.store.getProfile.bind(f.store); let reads = 0, downloads = 0;
  const before = (await original('kaka')).settings.stickerPackIds;
  t.after(() => { release.resolve(); f.store.getProfile = original; });
  f.store.getProfile = async id => {
    const profile = await original(id);
    if (id === 'kaka' && ++reads === 1) { entered.resolve(); await release.promise; }
    return profile;
  };
  f.runtime.onlineStickers = { async download() { downloads++; return saved; } };
  const download = f.runtime.downloadOnlineSticker.bind(f.runtime); let receivedSignal;
  f.runtime.downloadOnlineSticker = (id, input, options) => { receivedSignal = options.signal; return download(id, input, options); };
  const abort = new AbortController();
  const response = fetch(f.url + '/kaka/sticker-search', { method: 'POST', signal: abort.signal,
    headers: { cookie: 'droy_session=' + token, 'content-type': 'application/json' }, body: JSON.stringify({ stickerId: 'fictional-id' }) })
    .then(value => ({ value }), error => ({ error }));
  await entered.promise;
  const request = f.handled[0].req, buffered = [];
  for await (const chunk of request) buffered.push(chunk);
  // A buffered body reaches cancellation admission; a destroyed unread stream
  // would fail earlier and would not exercise the late-listener boundary.
  request[Symbol.asyncIterator] = async function* () { yield Buffer.concat(buffered); };
  abort.abort();
  assert.equal((await response).error?.name, 'AbortError');
  await eventually(() => f.handled[0].res.destroyed, 'the response closes while the initial profile read is pending');
  release.resolve(); await f.handled[0].done;
  assert.equal(receivedSignal?.aborted, true, 'The download boundary observes the already disconnected response');
  assert.equal(downloads, 0);
  assert.deepEqual((await original('kaka')).settings.stickerPackIds, before);
});

test('cancellation, host close and a superseding user message cannot enable a completed download', async t => {
  for (const reason of ['cancel', 'close', 'message']) await t.test(reason, async child => {
    const f = await fixture(child), saved = await downloadedImage(f), entered = Promise.withResolvers(), release = Promise.withResolvers();
    child.after(() => release.resolve());
    f.runtime.onlineStickers = { async download(_id, { signal }) { entered.resolve(signal); await release.promise; return saved; } };
    const abort = new AbortController();
    const pending = f.runtime.downloadOnlineSticker('kaka', { stickerId: 'fictional-id' }, { signal: abort.signal, owner: f.owner })
      .then(value => ({ value }), error => ({ error }));
    const signal = await entered.promise;
    if (reason === 'cancel') abort.abort();
    else if (reason === 'close') await f.runtime.close();
    else {
      f.runtime.options.debounceMs = 60000;
      await f.runtime.submit('kaka', { clientId: 'fictional-new-message', text: 'Please stop looking for that sticker.' });
    }
    if (reason !== 'message') assert.equal(signal.aborted, true);
    release.resolve();
    const result = await pending;
    assert.equal(result.error?.[reason === 'message' ? 'code' : 'name'], reason === 'message' ? 'COMPANION_REPLY_SUPERSEDED' : 'AbortError');
    assert.deepEqual((await f.store.getProfile('kaka')).settings.stickerPackIds ?? ['droy-default'], ['droy-default']);
  });
});

test('maintenance drain waits for a companion download through library publication and profile enablement', async t => {
  const f = await fixture(t), saved = await downloadedImage(f), entered = Promise.withResolvers(), release = Promise.withResolvers();
  t.after(() => release.resolve());
  f.runtime.onlineStickers = { async download() { entered.resolve(); await release.promise; return saved; } };
  const downloading = f.runtime.downloadOnlineSticker('kaka', { stickerId: 'fictional-id' });
  await entered.promise;
  let drained = false;
  const drain = f.runtime.maintenance.drainMemorySpace('kaka').then(() => { drained = true; });
  await delay(0); assert.equal(drained, false);
  release.resolve(); await downloading; await drain;
  assert.ok((await f.store.getProfile('kaka')).settings.stickerPackIds.includes(saved.pack.id));
});

test('a user message arriving during the final profile read cannot enable the downloaded pack', async t => {
  const f = await fixture(t), saved = await downloadedImage(f), entered = Promise.withResolvers(), release = Promise.withResolvers();
  const original = f.store.getProfile.bind(f.store); let reads = 0;
  t.after(() => { release.resolve(); f.store.getProfile = original; });
  f.runtime.onlineStickers = { async download() { return saved; } };
  f.store.getProfile = async id => {
    const profile = await original(id);
    if (id === 'kaka' && ++reads === 2) { entered.resolve(); await release.promise; }
    return profile;
  };
  const pending = f.runtime.downloadOnlineSticker('kaka', { stickerId: 'fictional-id' }, { owner: f.owner })
    .then(value => ({ value }), error => ({ error }));
  await entered.promise;
  f.runtime.state('kaka').revision++;
  release.resolve();
  assert.equal((await pending).error?.code, 'COMPANION_REPLY_SUPERSEDED');
  assert.deepEqual((await original('kaka')).settings.stickerPackIds ?? ['droy-default'], ['droy-default']);
});

test('agent download budget is bounded per conversation revision while manual downloads remain available', async t => {
  const f = await fixture(t), saved = await downloadedImage(f); let downloads = 0;
  f.runtime.onlineStickers = { async download() { downloads++; return saved; } };
  for (let i = 0; i < 3; i++) assert.equal((await f.runtime.downloadOnlineSticker('kaka', { stickerId: 'fictional-id' }, { owner: f.owner })).downloaded, true);
  const limited = await f.runtime.downloadOnlineSticker('kaka', { stickerId: 'fictional-id' }, { owner: f.owner });
  assert.equal(limited.downloaded, false); assert.equal(limited.reason, 'turn_download_limit'); assert.equal(downloads, 3);
  assert.equal((await f.api('/kaka/sticker-search', 'POST', { stickerId: 'fictional-id' })).status, 201);
  f.runtime.options.debounceMs = 60000;
  await f.runtime.submit('kaka', { clientId: 'fictional-next-turn', text: 'Find a different expression now.' });
  assert.equal((await f.runtime.downloadOnlineSticker('kaka', { stickerId: 'fictional-id' }, { owner: f.owner })).downloaded, true);
  assert.equal(downloads, 5);
  await assert.rejects(f.runtime.downloadOnlineSticker('kaka', { stickerId: 'fictional-id' }, { owner: { ...f.owner, kind: 'work' } }),
    error => error.code === 'COMPANION_TOOL_NOT_ALLOWED');
});
