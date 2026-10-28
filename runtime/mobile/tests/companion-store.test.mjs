import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, rm, readFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { DatabaseSync } from 'node:sqlite';
import { CompanionStore } from '../companions/store.mjs';
import { defaultProfiles, localizedProfile } from '../companions/profiles.mjs';
import { prepareReply } from '../companions/delivery.mjs';

const code = expected => error => error.code === expected;
async function fixture(t) {
  const directory = await mkdtemp(join(tmpdir(), 'droy-companion-store-'));
  let store = await CompanionStore.open({ directory });
  t.after(async () => { await store?.close(); await rm(directory, { recursive: true, force: true }); });
  return { directory, get store() { return store; }, async reopen() { await store.close(); store = await CompanionStore.open({ directory }); } };
}
const message = (id, text = 'Fictional message', extra = {}) => ({ id, role: 'user', type: 'text', text, ...extra });

test('two profiles have paired persona fields and independent durable configuration', async t => {
  const f = await fixture(t);
  const profiles = await f.store.profiles();
  assert.deepEqual(profiles.map(profile => profile.id), ['kaka', 'maimai']);
  for (const profile of profiles) {
    assert.equal(profile.model, null);
    for (const field of ['personality', 'behaviorStyle', 'replyStyle']) {
      assert.deepEqual(Object.keys(profile[field]).sort(), ['en', 'zh']);
      assert.ok(profile[field].en.trim() && profile[field].zh.trim());
    }
  }
  const original = await f.store.getProfile('kaka');
  await f.store.updateProfile('kaka', { name: 'Fictional renamed friend', model: { provider: 'fixture', model: 'local' }, settings: { proactiveEnabled: true, quietHours: null } });
  await f.store.updateSettings({ defaultMode: 'work' });
  await f.store.updateState('kaka', { summary: 'Shared fictional topic', expressions: ['A fictional phrase'] });
  await f.reopen();
  const updated = await f.store.getProfile('kaka');
  assert.equal(updated.id, 'kaka');
  assert.equal(updated.createdAt, original.createdAt);
  assert.equal(updated.name, 'Fictional renamed friend');
  assert.deepEqual(updated.model, { provider: 'fixture', model: 'local' });
  assert.equal(updated.settings.proactiveEnabled, true);
  assert.equal(updated.settings.memoryEnabled, true);
  assert.deepEqual(updated.settings.quietHours, { enabled: false, start: '22:00', end: '09:00' });
  assert.equal((await f.store.getProfile('maimai')).model, null);
  assert.equal((await f.store.getProfile('maimai')).settings.proactiveEnabled, false);
  assert.deepEqual(await f.store.getSettings(), { defaultMode: 'work' });
  assert.deepEqual(await f.store.getState('kaka'), { summary: 'Shared fictional topic', expressions: ['A fictional phrase'] });
  assert.deepEqual(await f.store.getState('maimai'), {});
  assert.equal(localizedProfile(updated, 'en').name, updated.name);
  assert.equal(localizedProfile(defaultProfiles()[0], 'en').name, 'Kaka');
  assert.equal(localizedProfile(defaultProfiles()[1], 'zh').name, '麦麦');
  await f.store.updateProfile('maimai', { name: '麦麦' });
  assert.equal(localizedProfile(await f.store.getProfile('maimai'), 'en').name, '麦麦');
});

test('invalid profile and settings input cannot mutate identities or another model', async t => {
  const { store } = await fixture(t);
  for (const patch of [{ id: 'maimai' }, { name: '  ' }, { name: 'x'.repeat(81) }, { avatar: 'javascript:alert(1)' },
    { model: { provider: 'fixture', model: '' } }, { personality: { zh: '只有中文' } },
    { settings: { quietHours: { start: '25:00', end: '08:00' } } }]) {
    await assert.rejects(store.updateProfile('kaka', patch), code('COMPANION_INVALID_INPUT'));
  }
  await assert.rejects(store.updateSettings({ defaultMode: 'other' }), code('COMPANION_INVALID_INPUT'));
  await assert.rejects(store.getProfile('../maimai'), code('COMPANION_NOT_FOUND'));
  assert.equal((await store.getProfile('kaka')).name, '咔咔');
});

test('personal information and disabled quiet hours survive restart without changing paired presets', async t => {
  const f = await fixture(t), original = await f.store.getProfile('kaka');
  assert.equal(original.settings.allowProfanity, true);
  assert.deepEqual(original.settings.quietHours, { enabled: true, start: '22:00', end: '09:00' });
  const personalityOverride = '  Fictional user-authored 性格\nKeep the original language.  ';
  await f.store.updateProfile('kaka', { personalityOverride, settings: { quietHours: { enabled: false, start: '23:15', end: '07:45' } } });
  await f.reopen();
  const saved = await f.store.getProfile('kaka');
  assert.deepEqual(saved.personality, original.personality);
  assert.equal(saved.personalityOverride, personalityOverride);
  assert.equal(localizedProfile(saved, 'zh').personality, personalityOverride);
  assert.equal(localizedProfile(saved, 'en').personality, personalityOverride);
  assert.deepEqual(saved.settings.quietHours, { enabled: false, start: '23:15', end: '07:45' });
  await f.store.updateProfile('kaka', { settings: { quietHours: null } });
  assert.deepEqual((await f.store.getProfile('kaka')).settings.quietHours, saved.settings.quietHours);
  await f.store.updateProfile('kaka', { personalityOverride: '' });
  assert.equal(localizedProfile(await f.store.getProfile('kaka'), 'en').personality, original.personality.en);
  for (const patch of [{ personalityOverride: 42 }, { personalityOverride: 'x'.repeat(16385) },
    { settings: { quietHours: { enabled: 'true', start: '22:00', end: '09:00' } } }])
    await assert.rejects(f.store.updateProfile('kaka', patch), code('COMPANION_INVALID_INPUT'));
});

test('legacy quiet-hour objects retain their chosen time and null remains disabled after migration', async t => {
  const f = await fixture(t);
  await f.store.close();
  const db = new DatabaseSync(join(f.directory, 'companions.sqlite'));
  for (const [id, quietHours, allowProfanity] of [['kaka', { start: '21:30', end: '06:45' }, false], ['maimai', null, true]]) {
    const row = JSON.parse(db.prepare('SELECT data FROM profiles WHERE id=?').get(id).data);
    delete row.personalityOverride; delete row.chatBackground;
    row.settings = { ...row.settings, quietHours, allowProfanity };
    db.prepare('UPDATE profiles SET data=? WHERE id=?').run(JSON.stringify(row), id);
  }
  db.close();
  await f.reopen();
  const kaka = await f.store.getProfile('kaka'), maimai = await f.store.getProfile('maimai');
  assert.deepEqual(kaka.settings.quietHours, { enabled: true, start: '21:30', end: '06:45' });
  assert.equal(kaka.settings.allowProfanity, false);
  assert.deepEqual(maimai.settings.quietHours, { enabled: false, start: '22:00', end: '09:00' });
  assert.equal(maimai.settings.allowProfanity, true);
  assert.equal(kaka.personalityOverride, '');
  assert.deepEqual(maimai.chatBackground, { type: 'default' });
  await f.reopen();
  assert.deepEqual(await f.store.getProfile('kaka'), kaka);
});

const imageUpload = (id, owner = 'kaka') => ({ id, mediaType: 'image/png', name: 'fictional.png', bytes: 4,
  url: '/droy/companions/' + owner + '/attachments/' + id, ref: { id: 'fixture-' + id } });

test('the global user avatar is independent of companion uploads, profile changes and history deletion', async t => {
  const f = await fixture(t);
  assert.deepEqual(await f.store.getUserProfile(), { name: '', avatarUrl: '/droy/companions/assets/user-default.png' });
  const avatar = imageUpload('shared-avatar', 'user-profile'), local = imageUpload('local-avatar');
  await f.store.putUserUpload(avatar); await f.store.putUpload('kaka', local);
  await assert.rejects(f.store.updateUserProfile({ avatarUrl: local.url }), code('COMPANION_ATTACHMENT_SCOPE'));
  await f.store.updateUserProfile({ name: ' Fictional User ', avatarUrl: avatar.url });
  await assert.rejects(f.store.removeUserUpload(avatar.id), code('COMPANION_ATTACHMENT_IN_USE'));
  assert.equal(await f.store.getUpload('kaka', avatar.id), null);
  assert.equal(await f.store.getUserUpload(local.id), null);
  await f.store.appendMessage('kaka', message('for-clearing'));
  await f.store.clearMessages('kaka');
  await f.store.updateProfile('maimai', { name: 'Fictional Friend' });
  await f.reopen();
  assert.deepEqual(await f.store.getUserProfile(), { name: 'Fictional User', avatarUrl: avatar.url });
  assert.deepEqual(await f.store.getUserUpload(avatar.id), avatar);
  for (const patch of [{ name: '  ' }, { name: 'x'.repeat(81) }, { name: 'bad\nname' }, { companionId: 'kaka' }, { avatarUrl: 'https://example.com/avatar.png' }])
    await assert.rejects(f.store.updateUserProfile(patch));
  await f.store.updateUserProfile({ avatarUrl: '/droy/companions/assets/user-default.png' });
  assert.deepEqual(await f.store.removeUserUpload(avatar.id), { removed: true });
});

test('backgrounds validate image ownership, retain crop and protect in-use uploads', async t => {
  const f = await fixture(t), image = imageUpload('wallpaper'), other = imageUpload('other', 'maimai');
  await f.store.putUpload('kaka', image); await f.store.putUpload('maimai', other);
  await f.store.putUpload('kaka', { ...imageUpload('text-file'), mediaType: 'text/plain' });
  for (const value of [{ type: 'preset', id: '06' }, { type: 'default', url: image.url },
    { type: 'upload', id: other.id }, { type: 'upload', id: 'text-file' },
    { type: 'upload', id: image.id, url: other.url }, { type: 'upload', id: image.id, position: { x: -1, y: 50 } }])
    await assert.rejects(f.store.updateProfile('kaka', { chatBackground: value }));
  await f.store.updateProfile('kaka', { chatBackground: { type: 'upload', id: image.id, position: { x: 25, y: 80 } } });
  await assert.rejects(f.store.removeUpload('kaka', image.id), code('COMPANION_ATTACHMENT_IN_USE'));
  await f.store.clearMessages('kaka'); await f.reopen();
  assert.deepEqual((await f.store.getProfile('kaka')).chatBackground, { type: 'upload', id: image.id, url: image.url, position: { x: 25, y: 80 } });
  assert.deepEqual((await f.store.getProfile('maimai')).chatBackground, { type: 'default' });
  await f.store.updateProfile('kaka', { chatBackground: { type: 'preset', id: '03' } });
  assert.deepEqual((await f.store.getProfile('kaka')).chatBackground, { type: 'preset', id: '03', url: '/droy/companions/assets/wallpaper-03.webp' });
  assert.deepEqual(await f.store.removeUpload('kaka', image.id), { removed: true });
});

test('explicit quote intent is durable and previews omit excluded, deleted and foreign targets', async t => {
  const f = await fixture(t);
  await f.store.appendMessage('kaka', message('older', 'Fictional older message '.repeat(30)));
  await f.store.appendMessage('maimai', message('foreign', 'Fictional foreign body'));
  await f.store.appendMessage('kaka', message('plain', 'Fictional direct answer', { role: 'assistant', replyTo: 'older', quoteReply: false }));
  await f.store.appendMessage('kaka', message('quoted', 'Fictional referenced answer', { role: 'assistant', replyTo: 'older', quoteReply: true }));
  await f.reopen();
  assert.equal((await f.store.getMessage('kaka', 'plain')).quoteReply, false);
  assert.equal((await f.store.getMessage('kaka', 'quoted')).quoteReply, true);
  const previews = await f.store.replyPreviews('kaka', ['older', 'foreign', 'missing']);
  assert.deepEqual(Object.keys(previews), ['older']);
  assert.equal(previews.older.text.length, 180);
  await assert.rejects(f.store.appendMessage('kaka', message('bad-quote', 'Fictional', { quoteReply: true })), code('COMPANION_INVALID_INPUT'));
  await f.store.excludeDerivedMessages('kaka', ['older']);
  assert.deepEqual(await f.store.replyPreviews('kaka', ['older']), {});
  await assert.rejects(f.store.appendMessage('kaka', message('excluded-quote', 'Fictional', { replyTo: 'older', quoteReply: true })), code('COMPANION_SOURCE_EXCLUDED'));
  await f.store.removeMessage('kaka', 'older');
  assert.deepEqual(await f.store.replyPreviews('kaka', ['older']), {});
});

test('a synchronized device timezone is validated and durable without changing other settings', async t => {
  const f = await fixture(t);
  await f.store.updateSettings({ defaultMode: 'work', timeZone: 'Asia/Shanghai' });
  await f.reopen();
  assert.deepEqual(await f.store.getSettings(), { defaultMode: 'work', timeZone: 'Asia/Shanghai' });
  for (const timeZone of ['invalid-zone', '+24:00', 'GMT+25:00', '', null, 'Asia/Shanghai\n'])
    await assert.rejects(f.store.updateSettings({ timeZone }), code('COMPANION_INVALID_INPUT'));
  assert.equal((await f.store.getSettings()).timeZone, 'Asia/Shanghai');
  await f.store.updateSettings({ timeZone: 'GMT+08:00' });
  await f.reopen();
  assert.deepEqual(await f.store.getSettings(), { defaultMode: 'work', timeZone: '+08:00' });
  assert.equal((await f.store.updateSettings({ timeZone: '-05:30' })).timeZone, '-05:30');
});

test('client message identity survives retry, update and restart without duplicates', async t => {
  const f = await fixture(t);
  const input = message('client-one', 'Fictional original text', { clientId: 'client-one' });
  const first = await f.store.appendMessage('kaka', input);
  assert.deepEqual(await f.store.appendMessage('kaka', input), first);
  await f.store.updateMessage('kaka', first.id, { status: 'failed', error: 'fixture_failure' });
  await f.reopen();
  const duplicate = await f.store.appendMessage('kaka', input);
  assert.equal(duplicate.status, 'failed');
  assert.equal(duplicate.createdAt, first.createdAt);
  assert.equal((await f.store.listMessages('kaka')).messages.length, 1);
  await assert.rejects(f.store.appendMessage('kaka', { ...input, text: 'Conflicting fictional text' }), code('COMPANION_MESSAGE_CONFLICT'));
  await f.store.appendMessage('maimai', input);
  assert.equal((await f.store.listMessages('maimai')).messages.length, 1);
  assert.equal(await f.store.getMessage('maimai', 'not-present'), null);
});

test('history pagination keeps order when new messages arrive and an anchor is deleted', async t => {
  const { store } = await fixture(t);
  for (let i = 0; i < 12; i++) await store.appendMessage('kaka', message('item-' + i, 'Fictional item ' + i, { createdAt: '2026-10-01T00:00:00Z' }));
  const first = await store.listMessages('kaka', { limit: 4 });
  assert.deepEqual(first.messages.map(item => item.id), ['item-8', 'item-9', 'item-10', 'item-11']);
  await store.appendMessage('kaka', message('new', 'Fictional new item'));
  await store.removeMessage('kaka', 'item-8');
  const second = await store.listMessages('kaka', { limit: 4, before: first.nextBefore });
  assert.deepEqual(second.messages.map(item => item.id), ['item-4', 'item-5', 'item-6', 'item-7']);
  const third = await store.listMessages('kaka', { limit: 4, before: second.nextBefore });
  assert.deepEqual(third.messages.map(item => item.id), ['item-0', 'item-1', 'item-2', 'item-3']);
  assert.equal(third.hasMore, false);
  await assert.rejects(store.listMessages('maimai', { before: 'item-4' }), code('COMPANION_MESSAGE_NOT_FOUND'));
});

test('session binding is durable and cannot be reassigned to another companion or kind', async t => {
  const f = await fixture(t), binding = { sessionId: 'chat-one', workspaceId: 'space-one', kind: 'chat' };
  const saved = await f.store.bindSession('kaka', binding);
  assert.deepEqual(await f.store.bindSession('kaka', binding), saved);
  await f.store.bindSession('kaka', { sessionId: 'work-one', workspaceId: 'space-one', kind: 'work', taskId: 'task-one' });
  await assert.rejects(f.store.bindSession('maimai', binding), code('COMPANION_SESSION_CONFLICT'));
  await assert.rejects(f.store.bindSession('kaka', { ...binding, kind: 'work' }), code('COMPANION_SESSION_CONFLICT'));
  await f.reopen();
  assert.deepEqual(await f.store.sessionOwner('chat-one'), saved);
  assert.equal((await f.store.getProfile('kaka')).chatSessionId, 'chat-one');
  assert.equal((await f.store.listSessions('kaka', { kind: 'work' }))[0].taskId, 'task-one');
  assert.deepEqual(await f.store.listSessions('maimai'), []);
  assert.equal(await f.store.sessionOwner('user-workspace-session'), null);
});

test('voice metadata, references and unread positions remain scoped after deletion', async t => {
  const f = await fixture(t);
  await f.store.appendMessage('kaka', message('user-one'));
  await f.store.appendMessage('kaka', { id: 'voice-one', role: 'user', type: 'voice', voice: { attachmentId: 'fixture-audio', durationMs: 2100 }, transcript: 'Fictional voice transcript' });
  await f.store.appendMessage('kaka', message('assistant-one', 'Fictional answer', { role: 'assistant', replyTo: 'user-one' }));
  assert.equal((await f.store.profiles())[0].unreadCount, 1);
  await f.store.markRead('kaka');
  await f.store.appendMessage('maimai', message('assistant-two', 'Other fictional answer', { role: 'assistant' }));
  assert.equal((await f.store.profiles())[0].unreadCount, 0);
  assert.equal((await f.store.profiles())[1].unreadCount, 1);
  await assert.rejects(f.store.appendMessage('maimai', message('wrong-reply', 'Fictional', { replyTo: 'user-one' })), code('COMPANION_MESSAGE_NOT_FOUND'));
  await f.store.removeMessage('kaka', 'voice-one');
  await f.reopen();
  assert.equal(await f.store.getMessage('kaka', 'voice-one'), null);
  assert.deepEqual((await f.store.listMessages('kaka')).messages.map(item => item.id), ['user-one', 'assistant-one']);
  const dbBytes = await readFile(join(f.directory, 'companions.sqlite'));
  assert.equal(dbBytes.includes(Buffer.from('Fictional voice transcript')), false);
});

test('concurrent partial state and message updates retain both changes', async t => {
  const { store } = await fixture(t);
  await Promise.all([
    store.updateState('kaka', { summary: 'Fictional background summary' }),
    store.updateState('kaka', { waitingUntil: '2030-01-01T00:00:00Z' }),
  ]);
  assert.deepEqual(await store.getState('kaka'), { summary: 'Fictional background summary', waitingUntil: '2030-01-01T00:00:00Z' });
  await store.appendMessage('kaka', message('updating', 'Fictional original'));
  await Promise.all([store.updateMessage('kaka', 'updating', { text: 'Fictional final' }), store.updateMessage('kaka', 'updating', { status: 'failed' })]);
  assert.equal((await store.getMessage('kaka', 'updating')).text, 'Fictional final');
  assert.equal((await store.getMessage('kaka', 'updating')).status, 'failed');
});

test('late retries cannot resurrect deleted or cleared messages', async t => {
  const f = await fixture(t), original = message('deleted', 'Fictional deleted body', { clientId: 'deleted-client' });
  await f.store.appendMessage('kaka', original);
  await f.store.removeMessage('kaka', original.id);
  await f.reopen();
  await assert.rejects(f.store.appendMessage('kaka', original), code('COMPANION_MESSAGE_DELETED'));
  await assert.rejects(f.store.appendMessage('kaka', { ...original, id: 'new-id' }), code('COMPANION_MESSAGE_DELETED'));
  const other = message('clear-one');
  await f.store.appendMessage('kaka', other);
  await f.store.clearMessages('kaka');
  await assert.rejects(f.store.appendMessage('kaka', other), code('COMPANION_MESSAGE_DELETED'));
  assert.deepEqual((await f.store.listMessages('kaka')).messages, []);
  await f.store.appendMessage('maimai', original);
  assert.equal((await f.store.listMessages('maimai')).messages.length, 1);
});

test('forward cursors process each source once even when the checkpoint message is removed', async t => {
  const { store } = await fixture(t);
  for (let i = 0; i < 7; i++) {
    await store.appendMessage('kaka', message('forward-' + i, 'Fictional source ' + i, { createdAt: '2026-10-01T00:00:00Z' }));
    await store.appendMessage('maimai', message('other-' + i));
  }
  const first = await store.listMessages('kaka', { after: 'seq:0', limit: 3 });
  assert.deepEqual(first.messages.map(item => item.id), ['forward-0', 'forward-1', 'forward-2']);
  await store.removeMessage('kaka', 'forward-2');
  const second = await store.listMessages('kaka', { after: first.nextAfter, limit: 3 });
  assert.deepEqual(second.messages.map(item => item.id), ['forward-3', 'forward-4', 'forward-5']);
  const third = await store.listMessages('kaka', { after: second.nextAfter, limit: 3 });
  assert.deepEqual(third.messages.map(item => item.id), ['forward-6']);
  assert.equal(third.hasMore, false);
  assert.deepEqual((await store.listMessages('kaka', { after: third.nextAfter })).messages, []);
  await assert.rejects(store.listMessages('kaka', { after: first.nextAfter, before: 'forward-6' }), code('COMPANION_INVALID_INPUT'));
});

test('transactional state callbacks preserve concurrent source merges and reject asynchronous mutations', async t => {
  const { store } = await fixture(t);
  await store.updateState('kaka', { summary: 'Fictional retained context' });
  await Promise.all(['one','two','three'].map(id => store.updateState('kaka', state => ({
    sessionSources: { ...state.sessionSources, [id]: ['Fictional '+id] },
  }))));
  const saved = await store.getState('kaka');
  assert.deepEqual(Object.keys(saved.sessionSources).sort(), ['one','three','two']);
  assert.equal(saved.summary, 'Fictional retained context');
  await assert.rejects(store.updateState('kaka', async () => ({ summary: 'Not committed' })), code('COMPANION_INVALID_INPUT'));
  assert.deepEqual(await store.getState('kaka'), saved);
});

const upload = id => ({ id, name: 'Fictional '+id+'.txt', mediaType: 'text/plain', bytes: 8,
  ref: { attachmentId: 'sha256:'+'a'.repeat(64), name: 'Fictional '+id+'.txt', bytes: 8 },
  url: '/droy/companions/kaka/attachments/'+id });

test('upload rows support independent concurrent writes, stable pages and scoped deletion after restart', async t => {
  const f = await fixture(t);
  const inputs = Array.from({length: 700}, (_,i) => ({...upload('upload-'+i), description: 'Fictional metadata '.repeat(32)}));
  await Promise.all(inputs.map(input => f.store.putUpload('kaka',input)));
  assert.deepEqual(await f.store.putUpload('kaka',inputs[0]),inputs[0]);
  await assert.rejects(f.store.putUpload('kaka',{...inputs[0],name:'Conflicting.txt'}),code('COMPANION_ATTACHMENT_CONFLICT'));
  assert.equal(await f.store.getUpload('maimai',inputs[0].id),null);
  await f.reopen();
  assert.deepEqual(await f.store.getUpload('kaka',inputs[0].id),inputs[0]);
  assert.deepEqual(await f.store.getState('kaka'),{});
  const first = await f.store.listUploads('kaka',{limit:200});
  assert.equal(first.uploads.length,200);
  assert.equal(first.uploads[0].id,'upload-699');
  await f.store.removeUpload('kaka',first.uploads.at(-1).id);
  await f.store.putUpload('kaka',upload('new-upload'));
  const second = await f.store.listUploads('kaka',{limit:200,before:first.nextBefore});
  assert.equal(second.uploads[0].id,'upload-499');
  assert.equal(second.uploads.length,200);
  assert.equal((await f.store.removeUpload('maimai',inputs[0].id)).removed,false);
  assert.equal((await f.store.removeUpload('kaka',inputs[0].id)).removed,true);
  assert.equal(await f.store.getUpload('kaka',inputs[0].id),null);
  await assert.rejects(f.store.updateState('kaka',{uploads:{}}),code('COMPANION_UPLOAD_API_REQUIRED'));
});

test('legacy upload JSON migrates once, preserving state and existing upload identity', async t => {
  const f = await fixture(t), first = upload('legacy-one'), second = upload('legacy-two');
  await f.store.putUpload('kaka',first);
  await f.store.close();
  const db = new DatabaseSync(join(f.directory,'companions.sqlite'));
  db.prepare('INSERT INTO companion_state VALUES(?,?)').run('kaka',JSON.stringify({summary:'Fictional retained summary',uploads:{[first.id]:first,[second.id]:second}}));
  db.close();
  await f.reopen();
  assert.deepEqual(await f.store.getState('kaka'),{summary:'Fictional retained summary'});
  assert.deepEqual(await f.store.getUpload('kaka',second.id),second);
  assert.equal((await f.store.listUploads('kaka')).uploads.length,2);
  await f.reopen();
  assert.equal((await f.store.listUploads('kaka')).uploads.length,2);
  await assert.rejects(f.store.updateState('kaka',()=>({uploads:{[first.id]:first}})),code('COMPANION_UPLOAD_API_REQUIRED'));
});

test('failed legacy migration leaves the original state and no partial upload rows', async t => {
  const f = await fixture(t), first = upload('valid-first');
  await f.store.close();
  const legacy={summary:'Fictional untouched summary',uploads:{[first.id]:first,invalid:{...upload('invalid'),bytes:-1}}};
  let db = new DatabaseSync(join(f.directory,'companions.sqlite'));
  db.prepare('INSERT INTO companion_state VALUES(?,?)').run('kaka',JSON.stringify(legacy));db.close();
  await assert.rejects(CompanionStore.open({directory:f.directory}),code('COMPANION_INVALID_INPUT'));
  db = new DatabaseSync(join(f.directory,'companions.sqlite'));
  assert.deepEqual(JSON.parse(db.prepare('SELECT data FROM companion_state WHERE companion_id=?').get('kaka').data),legacy);
  assert.equal(db.prepare('SELECT COUNT(*) count FROM companion_uploads').get().count,0);
  db.prepare('UPDATE companion_state SET data=? WHERE companion_id=?').run('{}','kaka');db.close();
  await f.reopen();
});

test('message context provenance is validated and can be updated internally',async t=>{
  const {store}=await fixture(t);
  await store.appendMessage('kaka',message('source'));
  const saved=await store.appendMessage('kaka',message('reply','Fictional answer',{
    role:'assistant',contextSourceIds:['source','source'],contextRange:{after:'seq:0',through:'seq:1'},contextExcluded:false,
  }));
  assert.deepEqual(saved.contextSourceIds,['source']);
  for(const patch of [{contextSourceIds:null},{contextSourceIds:['../other']},{contextRange:{after:'seq:2',through:'seq:1'}},
    {contextRange:{after:'seq:0',through:'message-id'}},{contextRange:{after:'seq:0',through:'seq:9007199254740992'}},
    {contextRange:{after:'seq:0',through:'seq:1',extra:1}},{contextExcluded:'true'}]) {
    await assert.rejects(store.updateMessage('kaka','reply',patch),error=>['COMPANION_INVALID_INPUT','COMPANION_INVALID_ID'].includes(error.code));
  }
  const updated=await store.updateMessage('kaka','reply',{contextSourceIds:[],contextRange:{after:'seq:1',through:'seq:1'},contextExcluded:true});
  assert.deepEqual(updated.contextSourceIds,[]);
  assert.equal(updated.contextExcluded,true);
});

test('forget exclusion follows assistant restatements and summary ranges while retaining visible independent history',async t=>{
  const f=await fixture(t);
  await f.store.appendMessage('kaka',message('identity','I am Fictional X'));
  const identityCursor=(await f.store.listMessages('kaka',{limit:1})).nextAfter;
  await f.store.appendMessage('maimai',message('identity','I am another fictional person'));
  await f.store.appendMessage('kaka',message('echo','I remember you are Fictional X',{role:'assistant',contextSourceIds:['identity']}));
  await f.store.appendMessage('kaka',message('echo-again','As we discussed, you are Fictional X',{role:'assistant',contextSourceIds:['echo']}));
  await f.store.appendMessage('kaka',message('from-summary','Fictional X was in the previous summary',{
    role:'assistant',contextRange:{after:'seq:0',through:identityCursor},
  }));
  await f.store.appendMessage('kaka',message('range-boundary','Independent boundary reply',{
    role:'assistant',contextRange:{after:identityCursor,through:identityCursor},
  }));
  await f.store.appendMessage('kaka',message('independent','Unrelated fictional topic'));
  await f.store.appendMessage('kaka',message('independent-reply','Unrelated fictional answer',{role:'assistant',contextSourceIds:['independent']}));
  await f.store.appendMessage('maimai',message('echo','Independent other companion',{role:'assistant',contextSourceIds:['identity']}));
  await f.store.updateState('kaka',{memoryRevision:4,summary:'Private fictional identity summary',summaryThrough:'identity',summaryCursor:identityCursor});
  const result=await f.store.excludeDerivedMessages('kaka',['identity']);
  assert.deepEqual(result.excludedIds,['identity','echo','echo-again','from-summary']);
  assert.equal(result.newlyExcluded,true);
  const state=await f.store.getState('kaka');
  assert.equal(state.memoryRevision,5);
  assert.equal(state.summary,'');
  assert.equal(state.summaryThrough,null);
  assert.equal(state.summaryCursor,'seq:0');
  assert.equal(state.forgottenMessageIds,undefined,'derived IDs are flags on rows, not a growing state array');
  const history=(await f.store.listMessages('kaka')).messages;
  assert.equal(history.length,7);
  assert.equal(history.find(item=>item.id==='identity').text,'I am Fictional X');
  assert.equal(history.find(item=>item.id==='independent-reply').contextExcluded,undefined);
  assert.equal(history.find(item=>item.id==='range-boundary').contextExcluded,undefined);
  assert.equal((await f.store.getMessage('maimai','echo')).contextExcluded,undefined);
  assert.deepEqual(await f.store.getState('maimai'),{});
  assert.deepEqual(await f.store.excludeDerivedMessages('kaka',['identity']),{...result,newlyExcluded:false});
  assert.equal((await f.store.getState('kaka')).memoryRevision,5);
  await f.reopen();
  assert.equal((await f.store.getMessage('kaka','echo-again')).contextExcluded,true);
  assert.equal((await f.store.listMessages('kaka')).messages.length,7);
});

test('late task provenance also propagates when an older row references a newer derived message',async t=>{
  const {store}=await fixture(t);
  await store.appendMessage('kaka',message('source'));
  await store.appendMessage('kaka',message('old-task','Fictional task',{role:'assistant',type:'task'}));
  await store.appendMessage('kaka',message('later-echo','Fictional repeated source',{role:'assistant',contextSourceIds:['source']}));
  await store.updateMessage('kaka','old-task',{contextSourceIds:['later-echo']});
  assert.deepEqual((await store.excludeDerivedMessages('kaka',['source'])).excludedIds,['source','old-task','later-echo']);
});

test('rebuilt summary ranges spanning an old forgotten row are not excluded when an unrelated source is forgotten',async t=>{
  const {store}=await fixture(t);
  await store.appendMessage('kaka',message('A','Fictional old fact A'));
  await store.appendMessage('kaka',message('B','Fictional still valid fact B'));
  const through=await store.getMessageCursor('kaka','B');
  await store.excludeDerivedMessages('kaka',['A']);
  await store.appendMessage('kaka',message('rebuilt-summary-reply','Fictional B from the rebuilt summary',{
    role:'assistant',contextRange:{after:'seq:0',through},contextSourceIds:['B'],
  }));
  await store.appendMessage('kaka',message('C','Fictional unrelated fact C'));
  await store.excludeDerivedMessages('kaka',['C']);
  assert.equal((await store.getMessage('kaka','rebuilt-summary-reply')).contextExcluded,undefined);
  await store.excludeDerivedMessages('kaka',['B']);
  assert.equal((await store.getMessage('kaka','rebuilt-summary-reply')).contextExcluded,true);
});

test('late explicit assistant references to excluded sources are rejected while existing idempotent replies survive',async t=>{
  const {store}=await fixture(t);
  await store.appendMessage('kaka',message('source'));
  const original=message('original','Fictional original reply',{role:'assistant',contextSourceIds:['source']});
  await store.appendMessage('kaka',original);
  await store.appendMessage('kaka',message('task','Fictional task',{role:'assistant',type:'task',contextSourceIds:['source'],task:{state:'running'}}));
  await store.excludeDerivedMessages('kaka',['source']);
  assert.equal((await store.appendMessage('kaka',original)).contextExcluded,true);
  await assert.rejects(store.appendMessage('kaka',message('late','Fictional late output',{role:'assistant',contextSourceIds:['source']})),code('COMPANION_SOURCE_EXCLUDED'));
  await assert.rejects(store.updateMessage('kaka','task',{task:{state:'completed',summary:'Fictional late result'},contextSourceIds:['source']}),code('COMPANION_SOURCE_EXCLUDED'));
  assert.equal(await store.getMessage('kaka','late'),null);
  assert.equal((await store.getMessage('kaka','task')).task.state,'running');
  await store.updateMessage('kaka','task',{task:{state:'cancelled',summary:''}});
  assert.equal((await store.getMessage('kaka','task')).task.state,'cancelled','a cancellation without new source metadata remains possible');
  await store.appendMessage('maimai',message('source'));
  await store.appendMessage('maimai',message('other','Fictional independent reply',{role:'assistant',contextSourceIds:['source']}));
  assert.equal((await store.getMessage('maimai','other')).contextExcluded,undefined);
});

test('received user messages survive restart and retry even when enqueue state was never written',async t=>{
  const f=await fixture(t),original=message('received-one','Fictional accepted input',{status:'received'});
  await f.store.appendMessage('kaka',original);
  await f.store.appendMessage('kaka',message('received-two','Fictional later input',{status:'received',createdAt:'2020-01-01T00:00:00Z'}));
  await f.store.appendMessage('kaka',message('completed','Fictional completed input',{status:'sent'}));
  await f.store.appendMessage('kaka',message('assistant','Fictional assistant bubble',{role:'assistant',status:'received'}));
  await f.store.appendMessage('kaka',message('excluded','Fictional forgotten input',{status:'received',contextExcluded:true}));
  await f.store.appendMessage('maimai',message('received-one','Fictional other input',{status:'received'}));
  assert.deepEqual(await f.store.getState('kaka'),{});
  await f.reopen();
  assert.equal((await f.store.appendMessage('kaka',original)).status,'received');
  assert.deepEqual((await f.store.pendingMessages('kaka')).map(item=>item.id),['received-one','received-two']);
  assert.deepEqual((await f.store.pendingMessages('maimai')).map(item=>item.id),['received-one']);
  await f.store.updateState('kaka',{pendingMessageIds:['received-one','received-two'],summary:'Fictional retained summary'});
  assert.deepEqual(await f.store.completeInputs('kaka',['received-one','received-one']),{completedIds:['received-one']});
  assert.equal((await f.store.appendMessage('kaka',original)).status,'sent','an idempotent retry returns the authoritative completed status');
  await f.reopen();
  assert.deepEqual((await f.store.pendingMessages('kaka')).map(item=>item.id),['received-two']);
  assert.deepEqual((await f.store.getState('kaka')).pendingMessageIds,['received-two']);
  assert.equal((await f.store.getState('kaka')).summary,'Fictional retained summary');
  assert.deepEqual(await f.store.completeInputs('kaka',['received-one']),{completedIds:[]});
  assert.equal((await f.store.getMessage('maimai','received-one')).status,'received');
});

test('draft completion validates identity and revision before changing receipts, then atomically preserves unrelated pending state',async t=>{
  const {store}=await fixture(t);
  for(const id of ['one','two'])await store.appendMessage('kaka',message(id,'Fictional input '+id,{status:'received'}));
  await store.updateState('kaka',{memoryRevision:3,pendingMessageIds:['one','two'],waitingUntil:'2030-01-01T00:00:00Z'});
  const draft=await prepareReply({store,companionId:'kaka',sourceMessageIds:['one'],parts:['Fictional answer']});
  const unchanged=async()=>{
    assert.equal((await store.getMessage('kaka','one')).status,'received');
    assert.deepEqual((await store.getState('kaka')).pendingMessageIds,['one','two']);
  };
  await assert.rejects(store.completeInputs('kaka',['one'],{draftId:'other-draft',memoryRevision:3}),code('COMPANION_REPLY_STALE'));await unchanged();
  await assert.rejects(store.completeInputs('kaka',['one'],{draftId:draft.id,memoryRevision:2}),code('COMPANION_REPLY_STALE'));await unchanged();
  await store.updateState('kaka',{memoryRevision:4});
  await assert.rejects(store.completeInputs('kaka',['one'],{draftId:draft.id}),code('COMPANION_REPLY_STALE'));await unchanged();
  await store.updateState('kaka',{memoryRevision:3,forgottenThrough:await store.getMessageCursor('kaka','one')});
  await assert.rejects(store.completeInputs('kaka',['one'],{draftId:draft.id,memoryRevision:3}),code('COMPANION_REPLY_STALE'));await unchanged();
  await store.updateState('kaka',{forgottenThrough:'seq:0'});
  await assert.rejects(store.completeInputs('kaka',['one'],{memoryRevision:2}),code('COMPANION_REPLY_STALE'));await unchanged();
  assert.deepEqual(await store.completeInputs('kaka',['one'],{draftId:draft.id,memoryRevision:3}),{completedIds:['one']});
  const state=await store.getState('kaka');
  assert.equal(state.replyDraft,null);
  assert.deepEqual(state.pendingMessageIds,['two']);
  assert.equal(state.waitingUntil,'2030-01-01T00:00:00Z');
  assert.equal(state.memoryRevision,3);
  assert.deepEqual((await store.pendingMessages('kaka')).map(item=>item.id),['two']);
});

test('failure writing completion state rolls back received status changes in the same SQLite transaction',async t=>{
  const f=await fixture(t);
  await f.store.appendMessage('kaka',message('one','Fictional unfinished input',{status:'received'}));
  await f.store.updateState('kaka',{pendingMessageIds:['one'],summary:'Fictional untouched context'});
  const db=new DatabaseSync(join(f.directory,'companions.sqlite'));
  db.exec("CREATE TRIGGER reject_completion BEFORE UPDATE ON companion_state BEGIN SELECT RAISE(ABORT,'fictional state write failure'); END");
  try {
    await assert.rejects(f.store.completeInputs('kaka',['one']),/fictional state write failure/);
    assert.equal((await f.store.getMessage('kaka','one')).status,'received');
    assert.deepEqual(await f.store.getState('kaka'),{pendingMessageIds:['one'],summary:'Fictional untouched context'});
  }finally {db.exec('DROP TRIGGER reject_completion');db.close();}
  await f.reopen();
  assert.deepEqual((await f.store.pendingMessages('kaka')).map(item=>item.id),['one']);
  await f.store.completeInputs('kaka',['one']);
  assert.deepEqual(await f.store.pendingMessages('kaka'),[]);
});

test('terminal task result receipts share durable ordered recovery and atomic completion with user inputs',async t=>{
  const f=await fixture(t);
  await f.store.appendMessage('kaka',message('user-input','Fictional user input',{status:'received'}));
  for(const state of ['completed','failed','cancelled','running'])await f.store.appendMessage('kaka',{
    id:'task-'+state,role:'assistant',type:'task',text:'Fictional task '+state,status:'received',task:{state,summary:'Fictional task result'},
  });
  await f.store.appendMessage('kaka',{id:'running-sent',role:'assistant',type:'task',status:'sent',task:{state:'running'}});
  await f.store.appendMessage('kaka',{id:'completed-sent',role:'assistant',type:'task',status:'sent',task:{state:'completed'}});
  await f.store.appendMessage('kaka',message('ordinary-assistant','Fictional ordinary reply',{role:'assistant',status:'received'}));
  await f.store.appendMessage('kaka',{id:'excluded-result',role:'assistant',type:'task',status:'received',contextExcluded:true,task:{state:'completed'}});
  await f.store.appendMessage('maimai',{id:'other-result',role:'assistant',type:'task',status:'received',task:{state:'completed'}});
  await f.store.updateState('kaka',{pendingMessageIds:['user-input','task-completed','task-failed','task-cancelled'],summary:'Fictional retained context'});
  await f.reopen();
  assert.deepEqual((await f.store.pendingMessages('kaka')).map(item=>item.id),['user-input','task-completed','task-failed','task-cancelled']);
  const draft=await prepareReply({store:f.store,companionId:'kaka',sourceMessageIds:['task-completed'],parts:['Fictional task follow-up']});
  assert.deepEqual(await f.store.completeInputs('kaka',['task-completed'],{draftId:draft.id,memoryRevision:draft.memoryRevision}),{completedIds:['task-completed']});
  assert.equal((await f.store.getMessage('kaka','task-completed')).status,'sent');
  assert.deepEqual((await f.store.getState('kaka')).pendingMessageIds,['user-input','task-failed','task-cancelled']);
  assert.equal((await f.store.getState('kaka')).replyDraft,null);
  assert.equal((await f.store.getState('kaka')).summary,'Fictional retained context');
  assert.deepEqual(await f.store.completeInputs('kaka',['task-running','ordinary-assistant','excluded-result']),{completedIds:[]});
  assert.equal((await f.store.getMessage('kaka','task-running')).status,'received');
  await f.reopen();
  assert.deepEqual((await f.store.pendingMessages('kaka')).map(item=>item.id),['user-input','task-failed','task-cancelled']);
  assert.deepEqual((await f.store.pendingMessages('maimai')).map(item=>item.id),['other-result']);
  await f.store.completeInputs('kaka',['task-failed','task-cancelled']);
  assert.deepEqual((await f.store.pendingMessages('kaka')).map(item=>item.id),['user-input']);
});

test('manual memory base and revision tokens reject withdrawn sources without blocking newer revisions or other companions',async t=>{
  const f=await fixture(t),base='memory:fictional-record',old=base+':revision-one',current=base+':revision-two';
  const original=message('original-memory-reply','Fictional reply from a manual memory',{role:'assistant',contextSourceIds:[base,old]});
  await f.store.appendMessage('kaka',original);
  await f.store.appendMessage('kaka',message('task','Fictional running task',{role:'assistant',type:'task',task:{state:'running'}}));
  await f.store.excludeDerivedMessages('kaka',[old]);
  await f.store.updateState('kaka',{forgottenMessageIds:[old]});
  await f.reopen();
  await assert.rejects(f.store.appendMessage('kaka',message('late-old','Fictional late old revision',{role:'assistant',contextSourceIds:[base,old]})),code('COMPANION_SOURCE_EXCLUDED'));
  await assert.rejects(f.store.updateMessage('kaka','task',{contextSourceIds:[base,old],task:{state:'completed',summary:'Fictional revoked result'}}),code('COMPANION_SOURCE_EXCLUDED'));
  assert.equal((await f.store.getMessage('kaka','task')).task.state,'running');
  assert.equal((await f.store.appendMessage('kaka',original)).contextExcluded,true,'an existing idempotent message is returned before source validation');
  await f.store.appendMessage('kaka',message('current-memory-reply','Fictional corrected revision',{role:'assistant',contextSourceIds:[base,current]}));
  await f.store.updateState('kaka',{forgottenMessageIds:[old,base]});
  await assert.rejects(f.store.appendMessage('kaka',message('late-forgotten','Fictional revoked memory',{role:'assistant',contextSourceIds:[base,current]})),code('COMPANION_SOURCE_EXCLUDED'));
  await f.store.appendMessage('maimai',message('independent-memory-reply','Fictional independent memory',{role:'assistant',contextSourceIds:[base,old]}));
  assert.ok(await f.store.getMessage('maimai','independent-memory-reply'));
});

test('an excluded task cannot receive new explicit provenance even when it drops withdrawn sources, while cancellation remains possible',async t=>{
  const {store}=await fixture(t);
  await store.appendMessage('kaka',message('task','Fictional obsolete task',{role:'assistant',type:'task',task:{state:'running',summary:'Fictional old summary'},contextExcluded:true}));
  for(const contextSourceIds of [[],['unrelated-new-source']])await assert.rejects(store.updateMessage('kaka','task',{
    contextSourceIds,contextExcluded:false,task:{state:'completed',summary:'Fictional late result'},
  }),code('COMPANION_SOURCE_EXCLUDED'));
  assert.equal((await store.getMessage('kaka','task')).task.state,'running');
  assert.equal((await store.getMessage('kaka','task')).contextExcluded,true);
  await store.updateMessage('kaka','task',{task:{state:'cancelled',summary:''},status:'sent'});
  const cancelled=await store.getMessage('kaka','task');
  assert.deepEqual(cancelled.task,{state:'cancelled',summary:''});
  assert.equal(cancelled.contextExcluded,true);
});
