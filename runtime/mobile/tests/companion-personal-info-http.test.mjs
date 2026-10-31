import test, { after } from 'node:test';
import assert from 'node:assert/strict';
import { createRequire, registerHooks } from 'node:module';
import { pathToFileURL } from 'node:url';
import { mkdtemp, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { createServer } from 'node:http';
import { CompanionStore } from '../companions/store.mjs';
import { openLegacyCompanionStore } from './fixtures/legacy-companion-db.mjs';
import { dialoguePrompts } from '../companions/dialogue-prompts.mjs';
import { encodePng } from '../png.mjs';

const installed = createRequire(new URL('../../dsh/package.json', import.meta.url));
const hooks = registerHooks({ resolve(specifier, context, next) {
  if (context.parentURL === new URL('../attachments.mjs', import.meta.url).href && specifier === './publish.mjs')
    return { url: new URL('./fixtures/host-publish.mjs', import.meta.url).href, shortCircuit: true };
  if (specifier === '@droyagent/memory/memos') return { url: new URL('../../memory/memos/store.mjs', import.meta.url).href, shortCircuit: true };
  if (specifier === '@droyagent/memory/store') return { url: new URL('../../memory/store.mjs', import.meta.url).href, shortCircuit: true };
  if (specifier.startsWith('@deepseek-ai/')) return { url: pathToFileURL(installed.resolve(specifier)).href, shortCircuit: true };
  return next(specifier, context);
} });
after(() => hooks.deregister());
const [{ createCompanionHandler }, { Context }, { default: MobileAttachments }] = await Promise.all([
  import('../companions/host.mjs'), import('@deepseek-ai/cordis'), import('../attachments.mjs'),
]);
const token = 'fictional-personal-information-http-test-token';
const image = () => ({ name: 'fictional.png', mediaType: 'image/png', base64: encodePng({ width: 2, height: 2, channels: 4, pixels: Buffer.alloc(16, 180) }).toString('base64') });

async function fixture(t) {
  const directory = await mkdtemp(join(tmpdir(), 'droy-personal-info-http-'));
  const store = await openLegacyCompanionStore({ directory: join(directory, 'companions') }), context = new Context();
  await context.plugin(MobileAttachments, { dshHome: join(directory, 'host') });
  let locale = 'en';
  const events = [], notificationReads=[], ctx = { droyInitialization:{defaultWorkspaceId:'fictional-default-workspace-id'}, attachments: context.attachments, logger: { warn() {} },
    sessionController: { modelCatalog: async () => ({ groups: [] }) }, workspaceRegistry: { get() {} },
    settings: { describe: () => [{ ns: 'locale', value: { preference: locale } }] } };
  const runtime = { emit: (...args) => events.push(args), state: () => ({ phase: 'idle' }),
    syncReadNotifications:async companionId=>{notificationReads.push({companionId,...await store.notificationReadState(companionId)});},
    status: () => ({ phase: 'idle', chatExecution: { quiescent: true, cancelRequested: false } }), copy: () => ({ generationFailed: 'Fictional failure' }) };
  const previousToken = process.env.DROY_WEB_TOKEN; process.env.DROY_WEB_TOKEN = token;
  const handler = createCompanionHandler({ ctx, runtime, store });
  const server = createServer((req, res) => handler(req, res).catch(error => { res.writeHead(500); res.end(String(error)); }));
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  t.after(async () => {
    server.closeAllConnections(); await new Promise(resolve => server.close(resolve));
    if (previousToken === undefined) delete process.env.DROY_WEB_TOKEN; else process.env.DROY_WEB_TOKEN = previousToken;
    await context.fiber.dispose(); await store.close(); await rm(directory, { recursive: true, force: true });
  });
  const url = 'http://127.0.0.1:' + server.address().port + '/droy/companions';
  const request = (path = '', method = 'GET', value) => fetch(url + path, { method,
    headers: { cookie: 'droy_session=' + token, 'content-type': 'application/json' },
    ...(value === undefined ? {} : { body: JSON.stringify(value) }) });
  const api = async (...args) => { const response = await request(...args); return { status: response.status, value: await response.json() }; };
  return { store, request, api, events, notificationReads, url, runtime, ctx, setLocale: value => { locale = value; } };
}

test('effective companion models preserve whether they follow the global default', async t => {
  const f = await fixture(t);
  f.ctx.agentDefaultModel = { currentSelection: () => ({ provider: 'fixture', model: 'global' }) };
  const inherited = (await f.api()).value.companions.find(item => item.id === 'kaka');
  assert.equal(inherited.modelInherited, true);
  assert.deepEqual(inherited.model, { provider: 'fixture', model: 'global' });
  await f.store.updateProfile('kaka', { model: { provider: 'fixture', model: 'explicit' } });
  const explicit = (await f.api()).value.companions.find(item => item.id === 'kaka');
  assert.equal(explicit.modelInherited, false);
  assert.deepEqual(explicit.model, { provider: 'fixture', model: 'explicit' });
});

test('user avatars have authenticated product ownership and cannot be sent as companion attachments', async t => {
  const f = await fixture(t);
  assert.equal((await fetch(f.url + '/user-profile')).status, 401);
  const initial = await f.api();
  assert.deepEqual(initial.value.userProfile, { name: '', bio: '', avatarUrl: '/droy/companions/assets/user-default.png' });
  const uploaded = await f.api('/user-profile/uploads', 'POST', image());
  assert.equal(uploaded.status, 201);
  assert.match(uploaded.value.url, /^\/droy\/companions\/user-profile\/attachments\//);
  const saved = await f.api('/user-profile', 'PATCH', { name: 'Fictional User', avatarUrl: uploaded.value.url });
  assert.equal(saved.status, 200);
  assert.deepEqual((await f.api()).value.userProfile, saved.value);
  const bytes = await f.request('/user-profile/attachments/' + uploaded.value.id);
  assert.equal(bytes.status, 200); assert.equal(bytes.headers.get('content-type'), 'image/png');
  assert.ok((await bytes.arrayBuffer()).byteLength > 0);
  assert.equal((await f.api('/user-profile/uploads/' + uploaded.value.id, 'DELETE')).value.error, 'COMPANION_ATTACHMENT_IN_USE');
  assert.equal((await f.api('/kaka/messages', 'POST', { text: 'Fictional', attachments: [{ id: uploaded.value.id }] })).value.error, 'COMPANION_ATTACHMENT_SCOPE');
  assert.equal((await f.api('/kaka/attachments/' + uploaded.value.id)).status, 404);
  const companionUpload = await f.api('/kaka/uploads', 'POST', image());
  assert.equal((await f.api('/user-profile', 'PATCH', { avatarUrl: companionUpload.value.url })).value.error, 'COMPANION_ATTACHMENT_SCOPE');
  assert.equal((await f.api('/user-profile/uploads', 'POST', { name: 'fictional.txt', mediaType: 'text/plain', base64: Buffer.from('Fictional').toString('base64') })).value.error, 'COMPANION_IMAGE_REQUIRED');
  await f.store.clearMessages('kaka');
  assert.deepEqual((await f.api('/user-profile')).value, saved.value);
  assert.deepEqual(f.events, [[null, 'profile']]);
});

test('profile HTTP saves custom personality, image crop and enabled quiet hours with companion boundaries', async t => {
  const f = await fixture(t), upload = (await f.api('/kaka/uploads', 'POST', image())).value;
  const before = (await f.api()).value.companions.find(profile => profile.id === 'kaka');
  const value = { personalityOverride: 'Fictional custom 性格', chatBackground: { type: 'upload', id: upload.id, position: { x: 15, y: 70 } },
    settings: { quietHours: { enabled: false, start: '22:00', end: '09:00' }, allowProfanity: true } };
  const updated = await f.api('/kaka', 'PATCH', value);
  assert.equal(updated.status, 200);
  assert.equal(updated.value.personality, value.personalityOverride);
  assert.deepEqual(updated.value.chatBackground, { ...value.chatBackground, url: upload.url });
  assert.deepEqual(updated.value.settings.quietHours, value.settings.quietHours);
  assert.equal((await f.api('/kaka/uploads/' + upload.id, 'DELETE')).value.error, 'COMPANION_ATTACHMENT_IN_USE');
  assert.equal((await f.api('/maimai', 'PATCH', { chatBackground: { type: 'upload', id: upload.id } })).value.error, 'COMPANION_ATTACHMENT_SCOPE');
  assert.deepEqual((await f.store.getProfile('maimai')).chatBackground, { type: 'default' });
  await f.api('/kaka', 'PATCH', { personalityOverride: '', chatBackground: { type: 'default' } });
  assert.equal((await f.api()).value.companions.find(profile => profile.id === 'kaka').personality, before.personality);
  assert.equal((await f.api('/kaka/uploads/' + upload.id, 'DELETE')).value.removed, true);
  assert.ok(f.events.every(event => event[0] === 'kaka' && event[1] === 'profile'));
});

test('quoted messages return bounded current previews outside the loaded page and redact withdrawn sources', async t => {
  const f = await fixture(t);
  await f.store.appendMessage('kaka', { id: 'original', role: 'user', text: 'Fictional old question' });
  for (let i = 0; i < 55; i++) await f.store.appendMessage('kaka', { id: 'later-' + i, role: 'user', text: 'Fictional later message' });
  await f.store.appendMessage('kaka', { id: 'answer', role: 'assistant', text: 'Fictional answer', replyTo: 'original', quoteReply: true });
  await f.store.appendMessage('kaka', { id: 'direct', role: 'assistant', text: 'Fictional direct answer', replyTo: 'original', quoteReply: false });
  const response = (await f.api('/kaka/messages')).value;
  assert.ok(!response.messages.some(message => message.id === 'original'));
  assert.deepEqual(response.messages.find(message => message.id === 'answer').replyPreview, { id: 'original', text: 'Fictional old question', type: 'text' });
  assert.equal(Object.hasOwn(response.messages.find(message => message.id === 'direct'), 'replyPreview'), false);
  await f.store.removeMessage('kaka', 'original');
  assert.equal((await f.api('/kaka/messages')).value.messages.find(message => message.id === 'answer').replyPreview, null);
});

test('chat HTTP snapshots hide internal work and reject old task references without removing internal records', async t => {
  const f = await fixture(t);
  await f.store.appendMessage('kaka', { id: 'work-hidden', role: 'assistant', type: 'task', text: 'Fictional hidden work', task: { state: 'completed' } });
  await f.store.appendMessage('kaka', { id: 'result-visible', role: 'assistant', text: 'Fictional reply', replyTo: 'work-hidden', quoteReply: false });
  const page = await f.api('/kaka/messages');
  assert.equal(page.status, 200);
  assert.deepEqual(page.value.messages.map(row => row.id), ['result-visible']);
  assert.equal(Object.hasOwn(page.value.messages[0], 'replyTo'), false);
  assert.equal(Object.hasOwn(page.value.messages[0], 'replyPreview'), false);
  let submitted = false;
  f.runtime.submit = async () => { submitted = true; return {}; };
  for (const replyTo of ['work-hidden', { id: 'work-hidden' }]) {
    const response = await f.api('/kaka/messages', 'POST', { text: 'Fictional user text', replyTo });
    assert.equal(response.status, 404);
    assert.equal(response.value.error, 'COMPANION_MESSAGE_NOT_FOUND');
  }
  assert.equal(submitted, false);
  for (const [path, method, body] of [
    ['/kaka/messages/work-hidden', 'DELETE'], ['/kaka/messages/work-hidden/transcript', 'PATCH', { text: 'Fictional' }],
    ['/kaka/read', 'POST', { messageId: 'work-hidden' }],
  ]) assert.equal((await f.api(path, method, body)).status, 404);
  assert.equal((await f.store.getMessage('kaka', 'work-hidden')).task.state, 'completed');
  assert.equal((await f.store.getMessage('kaka', 'result-visible')).replyTo, 'work-hidden');
});

test('private activity observation requires authentication and precedes companion ID lookup', async t => {
  const f = await fixture(t);
  let busy = true;
  f.runtime.activity = () => ({ busy });
  assert.equal((await fetch(f.url + '/activity')).status, 401);
  assert.deepEqual(await f.api('/activity'), { status: 200, value: { busy: true } });
  busy = false;
  assert.deepEqual(await f.api('/activity'), { status: 200, value: { busy: false } });
});

test('wallpaper assets and thumbnails serve WebP and unknown files are not exposed', async t => {
  const f = await fixture(t);
  const wallpapers = ['01', '02', '03', '04', '05', '06', '07', '08']
    .flatMap(id => ['wallpaper-' + id + '.webp', 'wallpaper-' + id + '-thumb.webp']);
  for (const name of [...wallpapers, 'user-default.png']) {
    const response = await f.request('/assets/' + name);
    assert.equal(response.status, 200);
    assert.equal(response.headers.get('content-type'), name.endsWith('.webp') ? 'image/webp' : 'image/png');
    assert.ok((await response.arrayBuffer()).byteLength > 0);
  }
  for (const name of ['wallpaper-00.webp', 'wallpaper-09.webp', 'wallpaper-09-thumb.webp', 'host.mjs'])
    assert.equal((await f.api('/assets/' + name)).status, 404);
  for (const name of ['wallpaper-01.webp', 'wallpaper-08-thumb.webp']) {
    const response = await f.request('/assets/' + name + '?v=20261030');
    assert.equal(response.status, 200);
    assert.equal(response.headers.get('content-type'), 'image/webp');
    assert.ok((await response.arrayBuffer()).byteLength > 0);
  }
  assert.equal((await fetch(f.url + '/assets/wallpaper-08.webp')).status, 401);
});

test('profile and image failures use the current operation language and retain the saved profile', async t => {
  const f = await fixture(t), original = (await f.api('/user-profile')).value;
  assert.deepEqual(Object.keys(dialoguePrompts.zh).sort(), Object.keys(dialoguePrompts.en).sort());
  for (const locale of ['zh', 'en']) {
    f.setLocale(locale);
    const copy = dialoguePrompts[locale];
    const invalidProfile = await f.api('/user-profile', 'PATCH', { name: '' });
    assert.equal(invalidProfile.status, 400);
    assert.equal(invalidProfile.value.message, copy.operationFailed);
    assert.notEqual(invalidProfile.value.message, copy.generationFailed);
    const invalidBackground = await f.api('/kaka', 'PATCH', { chatBackground: { type: 'preset', id: '09' } });
    assert.equal(invalidBackground.status, 400);
    assert.equal(invalidBackground.value.message, copy.operationFailed);
    const invalidImage = await f.api('/user-profile/uploads', 'POST', {
      mediaType: 'image/svg+xml', name: 'fictional.svg', base64: Buffer.from('<svg/>').toString('base64'),
    });
    assert.equal(invalidImage.status, 400);
    assert.equal(invalidImage.value.message, copy.invalidImage);
    assert.deepEqual((await f.api('/user-profile')).value, original);
    f.runtime.submit = async () => { throw Object.assign(new Error('Fictional missing model'), { code: 'COMPANION_MODEL_REQUIRED' }); };
    assert.equal((await f.api('/kaka/messages', 'POST', { text: 'Fictional message' })).value.message, copy.missingModel);
    f.runtime.submit = async () => { throw Object.assign(new Error('Fictional failed reply'), { code: 'COMPANION_GENERATION_FAILED' }); };
    assert.equal((await f.api('/kaka/messages', 'POST', { text: 'Fictional message' })).value.message, copy.generationFailed);
  }
});

test('conversation actions require authentication, validate exact input and keep hidden history accessible', async t => {
  const f = await fixture(t);
  await f.store.appendMessage('kaka', { id: 'old', role: 'assistant', text: 'Fictional old message', createdAt: '2026-10-28T01:00:00.000Z' });
  const unauthorized = await fetch(f.url + '/kaka/conversation', { method: 'POST', headers: { 'content-type': 'application/json' }, body: JSON.stringify({ action: 'hide' }) });
  assert.equal(unauthorized.status, 401);
  assert.deepEqual((await f.api('/kaka/conversation', 'POST', { action: 'pin' })).value, { pinned: true, hidden: false, unreadCount: 1 });
  const pinned = (await f.api()).value.companions.find(item => item.id === 'kaka');
  assert.equal(pinned.pinned, true); assert.equal(pinned.hidden, false);
  assert.equal(pinned.lastActivityAt, '2026-10-28T01:00:00.000Z');
  assert.equal(pinned.unread, 1);
  assert.deepEqual((await f.api('/kaka/conversation', 'POST', { action: 'hide' })).value, { pinned: false, hidden: true, unreadCount: 0 });
  const hidden = (await f.api()).value.companions.find(item => item.id === 'kaka');
  assert.equal(hidden.hidden, true); assert.equal(hidden.unread, 0);
  assert.equal((await f.api('/kaka/messages')).value.messages[0].id, 'old');
  for (const input of [{}, { action: 'delete' }, { action: 'hide', messageId: 'old' }]) {
    const result = await f.api('/kaka/conversation', 'POST', input);
    assert.equal(result.status, 400); assert.equal(result.value.error, 'COMPANION_INVALID_INPUT');
  }
  assert.equal((await f.api('/missing/conversation', 'POST', { action: 'hide' })).status, 404);
  assert.equal((await f.api('/kaka/conversation/extra', 'POST', { action: 'hide' })).status, 405);
  await f.store.appendMessage('kaka', { id: 'new', role: 'assistant', text: 'Fictional new message' });
  const returned = (await f.api()).value.companions.find(item => item.id === 'kaka');
  assert.equal(returned.hidden, false); assert.equal(returned.pinned, false); assert.equal(returned.unread, 1);
  assert.deepEqual((await f.api('/kaka/conversation', 'POST', { action: 'read' })).value, { pinned: false, hidden: false, unreadCount: 0 });
  assert.deepEqual(f.events, [['kaka', 'profile'], ['kaka', 'profile'], ['kaka', 'profile']]);
  assert.deepEqual(f.notificationReads,[{companionId:'kaka',throughSequence:1,unreadCount:0},{companionId:'kaka',throughSequence:2,unreadCount:0}]);
});

test('root HTTP exposes the initialized default identity independently of locale and displayed names', async t => {
  const f = await fixture(t);
  for (const locale of ['zh','en']) {
    f.setLocale(locale);
    const root = await f.api();
    assert.equal(root.status, 200);
    assert.equal(root.value.defaultWorkspaceId, 'fictional-default-workspace-id');
  }
  delete f.ctx.droyInitialization;
  assert.equal((await f.api()).value.defaultWorkspaceId, null);
});
