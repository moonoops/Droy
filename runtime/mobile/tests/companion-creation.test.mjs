import test, { after } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, rm } from 'node:fs/promises';
import { join } from 'node:path';
import { tmpdir } from 'node:os';
import { DatabaseSync } from 'node:sqlite';
import { createServer } from 'node:http';
import { once } from 'node:events';
import { spawn } from 'node:child_process';
import { createInterface } from 'node:readline';
import { createRequire, registerHooks } from 'node:module';
import { pathToFileURL } from 'node:url';
import { CompanionStore } from '../companions/store.mjs';
import { defaultProfiles, localizedProfile } from '../companions/profiles.mjs';
import { seedLegacyCompanionDb } from './fixtures/legacy-companion-db.mjs';
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
const [{ createCompanionHandler }, { Context }, { default: MobileAttachments }] = await Promise.all([
  import('../companions/host.mjs'), import('@deepseek-ai/cordis'), import('../attachments.mjs'),
]);
hooks.deregister();
const token = 'fictional-companion-creation-token-123456789';
const previousToken = process.env.DROY_WEB_TOKEN; process.env.DROY_WEB_TOKEN = token;
after(() => { if (previousToken === undefined) delete process.env.DROY_WEB_TOKEN; else process.env.DROY_WEB_TOKEN = previousToken; });
const code = expected => error => error.code === expected;
const create = (requestId, name, extra = {}) => ({ requestId, name, templateId: 'kaka', ...extra });
const image = () => ({ name: 'fictional-avatar.png', mediaType: 'image/png',
  base64: encodePng({ width: 2, height: 2, channels: 4, pixels: Buffer.alloc(16, 180) }).toString('base64') });

async function fixture(t, { legacy, locale = 'en', beforeOpen } = {}) {
  const directory = await mkdtemp(join(tmpdir(), 'droy-companion-creation-'));
  if (legacy !== undefined) await seedLegacyCompanionDb(directory, { profiles: legacy });
  if (beforeOpen) { const db = new DatabaseSync(join(directory, 'companions.sqlite')); try { beforeOpen(db); } finally { db.close(); } }
  let store = await CompanionStore.open({ directory });
  const context = new Context(); await context.plugin(MobileAttachments, { dshHome: join(directory, 'attachment-host') });
  const calls = [], ctx = { attachments: context.attachments, droyInitialization: { defaultWorkspaceId: 'fixture-default' }, logger: { warn() {} },
    settings: { describe: () => [{ ns: 'locale', value: { preference: locale } }] },
    sessionController: { modelCatalog: async () => ({ groups: [] }) }, workspaceRegistry: { get() {} },
    agentDefaultModel: { currentSelection: () => ({ provider: 'fixture-global', model: 'global-model' }) },
    llm: { async resolveCallConfig(model) { calls.push({ kind: 'model', model }); return model; } } };
  const runtime = { async startInstance(id) { calls.push({ kind: 'start', id }); }, emit(...args) { calls.push({ kind: 'event', args }); },
    status: () => ({ phase: 'idle', chatExecution: { quiescent: true, cancelRequested: false } }) };
  let server;
  t.after(async () => {
    server?.closeAllConnections(); if (server) await new Promise(resolve => server.close(resolve));
    await context.fiber.dispose(); await store?.close(); await rm(directory, { recursive: true, force: true });
  });
  return { directory, get store() { return store; }, ctx, runtime, calls,
    async reopen(edit) {
      await store.close(); store = null;
      if (edit) { const db = new DatabaseSync(join(directory, 'companions.sqlite')); try { edit(db); } finally { db.close(); } }
      store = await CompanionStore.open({ directory });
    },
    async serve() {
      const handler = createCompanionHandler({ ctx, runtime, store });
      server = createServer((req, res) => handler(req, res)); server.listen(0, '127.0.0.1'); await once(server, 'listening');
      const url = `http://127.0.0.1:${server.address().port}/droy/companions`;
      const request = (path = '', method = 'GET', value, extra = {}) => fetch(url + path, { method,
        headers: { Cookie: 'droy_session=' + token, 'Content-Type': 'application/json' },
        ...(value === undefined ? {} : { body: JSON.stringify(value) }), ...extra });
      return { request, async api(...args) { const response = await request(...args); return { status: response.status, value: await response.json() }; } };
    },
  };
}

test('only a new schema-0 database seeds one Kaka instance, with a stable identity separate from its template', async t => {
  const f = await fixture(t), profiles = await f.store.profiles();
  assert.equal(profiles.length, 1); assert.equal(profiles[0].templateId, 'kaka');
  assert.match(profiles[0].id, /^companion-[0-9a-f-]{36}$/); assert.equal(profiles[0].name, null); assert.equal(profiles[0].model, null);
  assert.equal(localizedProfile(profiles[0], 'en').name, 'Kaka'); assert.equal(localizedProfile(profiles[0], 'zh').name, '咔咔');
  await f.reopen(); assert.deepEqual((await f.store.profiles()).map(row => row.id), [profiles[0].id]);
});

test('schema-1 migration preserves actual rows, missing identities and an empty existing catalog without reseeding', async t => {
  for (const legacy of [defaultProfiles(), [defaultProfiles()[1]], []]) await t.test('rows=' + legacy.length, async child => {
    const f = await fixture(child, { legacy }), expected = legacy.map(row => row.id);
    assert.deepEqual((await f.store.profiles()).map(row => row.id), expected);
    await f.reopen(); assert.deepEqual((await f.store.profiles()).map(row => row.id), expected);
  });
});

test('real schema-1 data keeps stable owners, read boundaries, history, model sources and learning state through migration', async t => {
  const legacy = defaultProfiles().map((profile, index) => ({ ...profile, name: index ? 'Fictional existing Maimai' : null,
    nameCustomized: index === 1, model: index ? { provider: 'legacy-provider', model: 'legacy-explicit' } : null,
    workspaceId: profile.id + '-saved-workspace', chatSessionId: profile.id + '-saved-chat',
    avatar: 'https://fixture.invalid/retained-' + profile.id + '.png' }));
  const tables = ['messages', 'sessions', 'settings', 'read_positions', 'companion_state', 'conversation_state', 'companion_uploads', 'user_profile', 'user_uploads'];
  let before;
  const f = await fixture(t, { legacy, beforeOpen(db) {
    const message = (id, role, text) => ({ id, role, type: 'text', text, status: 'received', createdAt: '2026-10-27T10:00:00.000Z' });
    for (const [seq, companionId, value] of [
      [50, 'kaka', message('read-old', 'assistant', 'Fictional already-read history')],
      [51, 'kaka', message('unread-old', 'assistant', 'Fictional unread history')],
      [52, 'maimai', message('other-old', 'user', 'Fictional other companion history')],
    ]) db.prepare('INSERT INTO messages VALUES(?,?,?,?,?,?)').run(seq, companionId, value.id, null, 'legacy-fixture-fingerprint-' + seq, JSON.stringify(value));
    for (const profile of legacy) {
      db.prepare('INSERT INTO sessions VALUES(?,?,?,?,NULL)').run(profile.chatSessionId, profile.id, 'chat', profile.workspaceId);
      db.prepare('INSERT INTO companion_state VALUES(?,?)').run(profile.id, JSON.stringify({ summary: 'Fictional saved summary ' + profile.id,
        memoryRevision: 4, expressions: [{ situation: 'Fictional situation', expression: 'Fictional phrase', sourceIds: ['read-old'] }] }));
    }
    db.prepare('UPDATE read_positions SET seq=50 WHERE companion_id=?').run('kaka');
    db.prepare('INSERT INTO conversation_state VALUES(?,?,?)').run('kaka', 1, 0);
    db.prepare('INSERT INTO conversation_state VALUES(?,?,?)').run('maimai', 0, 1);
    db.prepare('UPDATE settings SET data=?').run(JSON.stringify({ defaultMode: 'workbench', timeZone: 'Asia/Shanghai' }));
    db.prepare('UPDATE user_profile SET data=?').run(JSON.stringify({ name: 'Fictional retained user', avatarUrl: '/droy/companions/assets/user-default.png' }));
    db.prepare('INSERT INTO companion_uploads(companion_id,upload_id,data) VALUES(?,?,?)').run('maimai', 'legacy-media', JSON.stringify({ id: 'legacy-media', ref: { fixture: true } }));
    db.prepare('INSERT INTO user_uploads VALUES(?,?)').run('legacy-user-media', JSON.stringify({ id: 'legacy-user-media', ref: { fixture: true } }));
    before = Object.fromEntries(tables.map(table => [table, db.prepare(`SELECT * FROM ${table} ORDER BY rowid`).all()]));
  } });
  for (const profile of legacy) assert.deepEqual(await f.store.getProfile(profile.id), profile);
  const list = await f.store.profiles();
  assert.equal(list[0].unreadCount, 1); assert.equal(list[0].pinned, true); assert.equal(list[1].hidden, true);
  assert.equal((await f.store.sessionOwner('kaka-saved-chat')).companionId, 'kaka');
  assert.equal((await f.store.getProfile('kaka')).model, null);
  assert.deepEqual((await f.store.getProfile('maimai')).model, { provider: 'legacy-provider', model: 'legacy-explicit' });
  await f.reopen(db => {
    assert.equal(db.prepare('PRAGMA user_version').get().user_version, 3);
    for (const table of tables) assert.deepEqual(db.prepare(`SELECT * FROM ${table} ORDER BY rowid`).all(), before[table], table);
  });
  assert.deepEqual((await f.store.profiles()).map(profile => profile.id), ['kaka', 'maimai']);
});

test('legacy null names reserve both language aliases and old conflicting names remain intact', async t => {
  const profiles = defaultProfiles().map(profile => ({ ...profile, name: null }));
  const f = await fixture(t, { legacy: profiles });
  for (const [index, name] of ['Kaka', 'ＫＡＫＡ', '咔咔', 'Maimai', '麦麦'].entries())
    await assert.rejects(f.store.createProfile(create('reserved-' + index, name)), code('COMPANION_NAME_CONFLICT'));
  const duplicate = defaultProfiles().map((profile, index) => ({ ...profile, name: index ? 'alice' : 'Ａlice', nameCustomized: true }));
  const old = await fixture(t, { legacy: duplicate });
  assert.deepEqual((await old.store.profiles()).map(row => row.name), ['Ａlice', 'alice']);
  await old.store.updateProfile('kaka', { personalityOverride: 'Fictional edited personality' });
  await old.store.updateProfile('kaka', { name: 'Alice' });
  await assert.rejects(old.store.createProfile(create('duplicate-name', ' ALICE ')), code('COMPANION_NAME_CONFLICT'));
  assert.equal((await old.store.getProfile('maimai')).name, 'alice');
});

test('creation and rename use the same normalization, suggest +1 names and preserve existing explicit model references', async t => {
  const f = await fixture(t), suggestion = await f.store.suggestName('kaka', 'en'); assert.equal(suggestion, 'Kaka 1');
  const first = await f.store.createProfile(create('first', suggestion));
  assert.equal(await f.store.suggestName('kaka', 'en'), 'Kaka 2');
  await assert.rejects(f.store.createProfile(create('second', 'ＫＡＫＡ   1')), code('COMPANION_NAME_CONFLICT'));
  const second = await f.store.createProfile(create('second', 'A different name', { model: { provider: 'saved-provider', model: 'saved-model' } }));
  assert.notEqual(first.profile.id, second.profile.id); assert.equal(first.profile.templateId, second.profile.templateId);
  await assert.rejects(f.store.updateProfile(second.profile.id, { name: 'kaka  1' }), code('COMPANION_NAME_CONFLICT'));
  await f.store.bindSession(first.profile.id, { sessionId: 'owned-chat', workspaceId: 'owned-workspace', kind: 'chat' });
  assert.equal((await f.store.getProfile(first.profile.id)).name, suggestion);
  await f.reopen(); assert.equal((await f.store.getProfile(first.profile.id)).model, null);
  assert.deepEqual((await f.store.getProfile(second.profile.id)).model, { provider: 'saved-provider', model: 'saved-model' });
});

test('requestId retries across reopen return the same instance and refuse changed creation content', async t => {
  const f = await fixture(t), input = create('lost-receipt', 'Replayable', { personalityOverride: 'Fictional editable personality', model: null });
  const first = await f.store.createProfile(input); await f.reopen();
  const retry = await f.store.createProfile(input); assert.equal(retry.profile.id, first.profile.id); assert.equal(retry.replayed, true);
  await f.store.updateProfile(first.profile.id, { name: 'A later chosen name' });
  assert.equal((await f.store.createProfile(input)).profile.name, 'A later chosen name');
  await assert.rejects(f.store.createProfile({ ...input, name: 'Different request body' }), code('COMPANION_CREATION_CONFLICT'));
  assert.equal((await f.store.profiles()).length, 2);
});

test('creation commits interaction settings and backgrounds with the same defaults and validators as editing', async t => {
  const f = await fixture(t), { api } = await f.serve();
  const settings = { proactiveEnabled: true, memoryEnabled: false, allowProfanity: false,
    quietHours: { enabled: false, start: '21:30', end: '08:15' } };
  const input = create('interaction-create', 'Configured at creation', { settings, chatBackground: { type: 'preset', id: '03' } });
  const created = await api('', 'POST', input); assert.equal(created.status, 201);
  assert.deepEqual(created.value.profile.settings, settings);
  assert.deepEqual(created.value.profile.chatBackground, { type: 'preset', id: '03', url: '/droy/companions/assets/wallpaper-03.webp' });
  assert.deepEqual((await f.store.getProfile(created.value.profile.id)).settings, settings);
  assert.equal((await api('', 'POST', input)).value.profile.id, created.value.profile.id);
  const conflict = await api('', 'POST', { ...input, settings: { ...settings, allowProfanity: true } });
  assert.equal(conflict.status, 409); assert.equal(conflict.value.error, 'COMPANION_CREATION_CONFLICT');
  const partial = await f.store.createProfile(create('partial-interaction-create', 'Partial settings', { settings: { memoryEnabled: false, quietHours: null } }));
  assert.deepEqual(partial.profile.settings, { proactiveEnabled: true, memoryEnabled: false, allowProfanity: true,
    quietHours: { enabled: false, start: '22:00', end: '09:00' } });
  const invalid = [
    { settings: { memoryEnabled: 'false' } }, { settings: { unrelated: true } },
    { settings: { quietHours: { start: '25:00', end: '08:00' } } },
    { chatBackground: { type: 'preset', id: '99' } }, { chatBackground: { type: 'default', position: { x: 0, y: 0 } } },
    { personalityOverride: null },
  ];
  for (const [index, patch] of invalid.entries()) {
    await assert.rejects(f.store.createProfile(create('invalid-interaction-' + index, 'Rejected interaction ' + index, patch)), code('COMPANION_INVALID_INPUT'));
    await assert.rejects(f.store.updateProfile(created.value.profile.id, patch), code('COMPANION_INVALID_INPUT'));
  }
  assert.equal((await f.store.profiles()).length, 3);
});

test('a draft background and avatar transfer atomically, including one shared image and stable retry content', async t => {
  for (const shared of [true, false]) await t.test(shared ? 'shared image' : 'distinct images', async child => {
    const f = await fixture(child), { api } = await f.serve(), draft = await f.store.createDraft({ requestId: 'background-draft' });
    const avatar = (await api('/creation-drafts/' + draft.draftId + '/uploads', 'POST', image())).value;
    const background = shared ? avatar : (await api('/creation-drafts/' + draft.draftId + '/uploads', 'POST', image())).value;
    const input = create('background-create', 'Background owner', { draftId: draft.draftId, avatarUploadId: avatar.id,
      chatBackground: { type: 'upload', id: background.id, position: { x: 30, y: 80 } }, settings: { memoryEnabled: false } });
    const created = await api('', 'POST', input); assert.equal(created.status, 201);
    const profile = created.value.profile;
    assert.equal(profile.avatarUrl, `/droy/companions/${profile.id}/attachments/${avatar.id}`);
    assert.deepEqual(profile.chatBackground, { type: 'upload', id: background.id,
      url: `/droy/companions/${profile.id}/attachments/${background.id}`, position: { x: 30, y: 80 } });
    assert.equal((await f.store.listUploads(profile.id)).uploads.length, shared ? 1 : 2);
    const replay = await api('', 'POST', { ...input, chatBackground: { ...input.chatBackground, url: background.url } });
    assert.equal(replay.status, 200); assert.equal(replay.value.profile.id, profile.id);
    const db = new DatabaseSync(join(f.directory, 'companions.sqlite'));
    try { assert.equal(db.prepare('SELECT COUNT(*) count FROM draft_uploads WHERE draft_id=?').get(draft.draftId).count, 0); }
    finally { db.close(); }
  });
});

async function actor(t, directory, operation) {
  const script = `import {CompanionStore} from ${JSON.stringify(new URL('../companions/store.mjs', import.meta.url).href)};
    const store=await CompanionStore.open({directory:process.argv[1]});console.log('ready');
    for await(const _ of process.stdin)break;
    try{const input=JSON.parse(process.argv[2]);const value=input.kind==='rename'?await store.updateProfile(input.id,{name:input.name})
      :input.kind==='cancel'?await store.cancelDraft(input.id):await store.createProfile(input.request);
    console.log(JSON.stringify({ok:true,id:value.profile?.id??value.id,result:value}));}catch(error){console.log(JSON.stringify({ok:false,code:error.code}));}finally{await store.close();}`;
  const child = spawn(process.execPath, ['--input-type=module', '-e', script, directory, JSON.stringify(operation)], { stdio: ['pipe', 'pipe', 'pipe'] });
  t.after(() => child.kill()); child.stderr.resume();
  const ready = Promise.withResolvers(), result = Promise.withResolvers();
  const lines = createInterface({ input: child.stdout });
  lines.on('line', line => line === 'ready' ? ready.resolve() : result.resolve(JSON.parse(line)));
  child.on('error', error => { ready.reject(error); result.reject(error); });
  child.on('exit', status => { if (status) { const error = new Error('Creation actor exited: ' + status); ready.reject(error); result.reject(error); } });
  await ready.promise; return { go() { child.stdin.end('go'); }, result: result.promise };
}
test('real SQLite writer races allow one normalized name and make one requestId idempotent across processes', { timeout: 10000 }, async t => {
  const f = await fixture(t);
  let a = await actor(t, f.directory, { kind: 'create', request: create('race-a', 'Concurrent name') });
  let b = await actor(t, f.directory, { kind: 'create', request: create('race-b', 'ＣＯＮＣＵＲＲＥＮＴ  name') });
  a.go(); b.go(); let results = await Promise.all([a.result, b.result]);
  assert.equal(results.filter(row => row.ok).length, 1); assert.equal(results.find(row => !row.ok).code, 'COMPANION_NAME_CONFLICT');
  a = await actor(t, f.directory, { kind: 'create', request: create('same-id', 'Same request') });
  b = await actor(t, f.directory, { kind: 'create', request: create('same-id', 'Same request') });
  a.go(); b.go(); results = await Promise.all([a.result, b.result]);
  assert.ok(results.every(row => row.ok)); assert.equal(results[0].id, results[1].id);
  const first = await f.store.createProfile(create('rename-a', 'Before rename A')), second = await f.store.createProfile(create('rename-b', 'Before rename B'));
  a = await actor(t, f.directory, { kind: 'rename', id: first.profile.id, name: 'One rename winner' });
  b = await actor(t, f.directory, { kind: 'rename', id: second.profile.id, name: 'ＯＮＥ  RENAME winner' });
  a.go(); b.go(); results = await Promise.all([a.result, b.result]);
  assert.equal(results.filter(row => row.ok).length, 1); assert.equal(results.find(row => !row.ok).code, 'COMPANION_NAME_CONFLICT');
});

test('draft images belong to no persona until atomic creation transfers only the selected avatar', async t => {
  const f = await fixture(t), { api, request } = await f.serve(), before = await f.store.profiles();
  const first = await api('/creation-drafts', 'POST', { requestId: 'draft-one' }), draftId = first.value.draftId;
  assert.equal((await api('/creation-drafts', 'POST', { requestId: 'draft-one' })).value.draftId, draftId);
  const uploaded = await api('/creation-drafts/' + draftId + '/uploads', 'POST', image()); assert.equal(uploaded.status, 201);
  assert.equal((await request('/creation-drafts/' + draftId + '/attachments/' + uploaded.value.id)).status, 200);
  assert.deepEqual((await f.store.profiles()).map(row => row.id), before.map(row => row.id));
  const input = create('avatar-create', 'Avatar owner', { draftId, avatarUploadId: uploaded.value.id, personalityOverride: 'Fictional edited persona', model: null });
  const created = await api('', 'POST', input); assert.equal(created.status, 201);
  const profile = created.value.profile; assert.equal(profile.templateId, 'kaka'); assert.equal(profile.modelInherited, true);
  assert.equal((await f.store.getProfile(profile.id)).model, null); assert.equal(profile.personality, input.personalityOverride);
  assert.equal(profile.avatarUrl, `/droy/companions/${profile.id}/attachments/${uploaded.value.id}`);
  assert.equal((await f.store.getUpload(profile.id, uploaded.value.id)).url, profile.avatarUrl);
  assert.equal(await f.store.getUpload(before[0].id, uploaded.value.id), null);
  assert.equal((await request('/' + profile.id + '/attachments/' + uploaded.value.id)).status, 200);
  assert.equal((await api('/creation-drafts/' + draftId)).value.companionId, profile.id);
  assert.equal((await request('/creation-drafts/' + draftId + '/attachments/' + uploaded.value.id)).status, 409);
  assert.equal((await api('', 'POST', input)).value.profile.id, profile.id);
  assert.equal((await f.store.profiles()).length, 2); assert.equal(profile.workspaceId, null);
});

test('cancel and commit are ordered by the same draft transaction and never delete a created instance or shared media', async t => {
  const f = await fixture(t), { api } = await f.serve(), user = await f.store.getUserProfile();
  const draft = await f.store.createDraft({ requestId: 'cancelled-draft' });
  const uploaded = await api('/creation-drafts/' + draft.draftId + '/uploads', 'POST', image());
  const before = await f.store.profiles();
  assert.deepEqual(await f.store.cancelDraft(draft.draftId), { cancelled: true, state: 'cancelled' });
  await assert.rejects(f.store.createProfile(create('cancelled-create', 'Never created', { draftId: draft.draftId, avatarUploadId: uploaded.value.id })), code('COMPANION_DRAFT_CLOSED'));
  assert.deepEqual((await f.store.profiles()).map(row => row.id), before.map(row => row.id));
  assert.deepEqual(await f.store.getUserProfile(), user);
  await f.ctx.attachments.readImage(uploaded.value.ref);
  const next = await f.store.createDraft({ requestId: 'committed-draft' });
  const created = await f.store.createProfile(create('committed-create', 'Created once', { draftId: next.draftId }));
  assert.deepEqual(await f.store.cancelDraft(next.draftId), { cancelled: false, state: 'committed', companionId: created.profile.id });
  assert.ok(await f.store.getProfile(created.profile.id));
});

test('competing SQLite cancellation and commit return the winning draft state without creating a partial persona', { timeout: 10000 }, async t => {
  const f = await fixture(t), draft = await f.store.createDraft({ requestId: 'raced-draft' });
  const input = create('raced-draft-create', 'Fictional raced draft', { draftId: draft.draftId, settings: { memoryEnabled: false } });
  const cancel = await actor(t, f.directory, { kind: 'cancel', id: draft.draftId });
  const commit = await actor(t, f.directory, { kind: 'create', request: input });
  cancel.go(); commit.go();
  const [cancelled, created] = await Promise.all([cancel.result, commit.result]);
  assert.equal(cancelled.ok, true);
  const saved = await f.store.getDraft(draft.draftId);
  if (created.ok) {
    assert.equal(saved.state, 'committed'); assert.equal(saved.companionId, created.id);
    assert.deepEqual(cancelled.result, { cancelled: false, state: 'committed', companionId: created.id });
    assert.equal((await f.store.profiles()).length, 2);
    assert.equal((await f.store.createProfile(input)).profile.id, created.id);
  } else {
    assert.equal(created.code, 'COMPANION_DRAFT_CLOSED'); assert.equal(saved.state, 'cancelled');
    assert.deepEqual(cancelled.result, { cancelled: true, state: 'cancelled' });
    assert.equal((await f.store.profiles()).length, 1); assert.equal(await f.store.getCreation(input.requestId), null);
  }
});

test('a failed final receipt write rolls back persona, name claim and avatar transfer together', async t => {
  const f = await fixture(t), { api } = await f.serve(), draft = await f.store.createDraft({ requestId: 'rollback-draft' });
  const upload = (await api('/creation-drafts/' + draft.draftId + '/uploads', 'POST', image())).value;
  const db = new DatabaseSync(join(f.directory, 'companions.sqlite'));
  db.exec("CREATE TRIGGER reject_creation BEFORE INSERT ON creation_receipts BEGIN SELECT RAISE(ABORT,'fictional receipt failure'); END");
  const input = create('rollback-create', 'Retryable name', { draftId: draft.draftId, avatarUploadId: upload.id,
    chatBackground: { type: 'upload', id: upload.id }, settings: { proactiveEnabled: true, memoryEnabled: false } });
  await assert.rejects(f.store.createProfile(input), /fictional receipt failure/);
  assert.equal((await f.store.profiles()).length, 1); assert.equal((await f.store.getDraft(draft.draftId)).state, 'open');
  assert.equal((await f.store.getDraftUpload(draft.draftId, upload.id)).id, upload.id);
  assert.equal(db.prepare('SELECT COUNT(*) count FROM companion_uploads').get().count, 0);
  db.exec('DROP TRIGGER reject_creation'); db.close();
  const created = await f.store.createProfile(input); assert.equal(created.profile.name, 'Retryable name');
  assert.equal(created.profile.chatBackground.url, created.profile.avatar);
  assert.equal(created.profile.settings.proactiveEnabled, true); assert.equal(created.profile.settings.memoryEnabled, false);
});

test('avatar changes cannot borrow another companion, another draft, or the user-profile upload owner', async t => {
  const f = await fixture(t), { api } = await f.serve();
  const draftA = await f.store.createDraft({ requestId: 'scope-a' }), draftB = await f.store.createDraft({ requestId: 'scope-b' });
  const upload = (await api('/creation-drafts/' + draftB.draftId + '/uploads', 'POST', image())).value;
  await assert.rejects(f.store.createProfile(create('cross-draft', 'Wrong draft', { draftId: draftA.draftId, avatarUploadId: upload.id })), code('COMPANION_ATTACHMENT_SCOPE'));
  const userUpload = (await api('/user-profile/uploads', 'POST', image())).value;
  await assert.rejects(f.store.createProfile(create('cross-user', 'Wrong user', { draftId: draftA.draftId, avatarUploadId: userUpload.id })), code('COMPANION_ATTACHMENT_SCOPE'));
  for (const [index, extra] of [
    { chatBackground: { type: 'upload', id: upload.id } },
    { draftId: draftA.draftId, chatBackground: { type: 'upload', id: upload.id } },
    { draftId: draftA.draftId, chatBackground: { type: 'upload', id: userUpload.id } },
    { draftId: draftB.draftId, chatBackground: { type: 'upload', id: upload.id, url: userUpload.url } },
  ].entries()) await assert.rejects(f.store.createProfile(create('cross-background-' + index, 'Wrong background ' + index, extra)), code('COMPANION_ATTACHMENT_SCOPE'));
  const created = await f.store.createProfile(create('right-avatar', 'Right owner', { draftId: draftB.draftId, avatarUploadId: upload.id }));
  const other = (await f.store.profiles())[0];
  for (const patch of [{ avatar: created.profile.avatar }, { avatarUrl: created.profile.avatar }, { avatar: userUpload.url }])
    assert.equal((await api('/' + other.id, 'PATCH', patch)).value.error, 'COMPANION_ATTACHMENT_SCOPE');
  await assert.rejects(f.store.updateProfile(other.id, { avatar: 'https://fixture.invalid/new-avatar.png' }), code('COMPANION_ATTACHMENT_SCOPE'));
  assert.equal((await f.store.getProfile(other.id)).avatar, '/droy/companions/assets/kaka.png');
});

test('legacy avatar values survive unrelated edits while both PATCH spellings enforce new ownership', async t => {
  const f = await fixture(t, { legacy: defaultProfiles().map(row => ({ ...row, avatar: 'https://fixture.invalid/legacy-' + row.id + '.png' })) });
  await f.store.updateProfile('kaka', { name: 'Preserved legacy avatar' });
  assert.equal((await f.store.getProfile('kaka')).avatar, 'https://fixture.invalid/legacy-kaka.png');
  await f.store.updateProfile('kaka', { avatar: 'https://fixture.invalid/legacy-kaka.png' });
  await f.reopen(); assert.equal((await f.store.getProfile('kaka')).avatar, 'https://fixture.invalid/legacy-kaka.png');
});

test('templates and creation errors use current paired language copy and successful retries skip expired model discovery', async t => {
  for (const locale of ['zh', 'en']) await t.test(locale, async child => {
    const f = await fixture(child, { locale }), { api, request } = await f.serve();
    assert.equal((await request('', 'POST', {}, { headers: {} })).status, 401);
    const templates = await api('/templates'); assert.equal(templates.status, 200);
    assert.deepEqual(templates.value.templates.map(row => row.templateId), ['kaka', 'maimai']);
    assert.equal(templates.value.templates[0].suggestedName, locale === 'zh' ? '咔咔 1' : 'Kaka 1');
    assert.equal('id' in templates.value.templates[0], false);
    const input = create('model-retry', 'Saved model', { model: { provider: 'saved-provider', model: 'saved-model' } });
    const created = await api('', 'POST', input); assert.equal(created.value.profile.modelInherited, false);
    f.ctx.llm.resolveCallConfig = async () => { throw new Error('fictional-private-credential'); };
    const replay = await api('', 'POST', input); assert.equal(replay.status, 200); assert.equal(replay.value.profile.id, created.value.profile.id);
    const conflict = await api('', 'POST', create('name-clash', 'saved model'));
    assert.equal(conflict.status, 409); assert.equal(conflict.value.error, 'COMPANION_NAME_CONFLICT');
    assert.match(conflict.value.message, locale === 'zh' ? /名称/ : /name/);
  });
});

test('a committed creation survives a lost startup response and safely recovers through the original requestId', async t => {
  const f = await fixture(t), { api } = await f.serve(), originalStart = f.runtime.startInstance;
  f.runtime.startInstance = async () => { throw new Error('/fictional/private/path/with-sensitive-provider-details'); };
  const input = create('committed-response-retry', 'Fictional recovered creation', { settings: { memoryEnabled: false } });
  const failed = await api('', 'POST', input);
  assert.equal(failed.status, 500); assert.equal(failed.value.error, 'COMPANION_OPERATION_FAILED');
  assert.ok(!JSON.stringify(failed.value).includes('/fictional/private/path'));
  const receipt = await f.store.getCreation(input.requestId);
  assert.equal(receipt.profile.name, input.name); assert.equal((await f.store.profiles()).length, 2);
  f.runtime.startInstance = originalStart;
  const recovered = await api('', 'POST', input);
  assert.equal(recovered.status, 200); assert.equal(recovered.value.replayed, true);
  assert.equal(recovered.value.profile.id, receipt.profile.id);
  assert.equal(recovered.value.profile.settings.memoryEnabled, false);
  assert.equal((await f.store.profiles()).length, 2);
});

test('new role creation exposes and persists enabled proactive default while legacy choices remain unchanged', async t => {
  const f = await fixture(t, { legacy: defaultProfiles() }), { api } = await f.serve();
  assert.equal((await f.store.getProfile('kaka')).settings.proactiveEnabled, false);
  const catalog = await api('/templates');
  assert.ok(catalog.value.templates.every(template => template.settings.proactiveEnabled));
  assert.ok(catalog.value.templates.every(template => template.customPersonaDefaults.behaviorStyle));
  const input = create('complete-role', 'Fictional custom role', { templateId: 'maimai',
    personalityOverride: 'Fictional calm male programmer.', behaviorStyleOverride: 'Listen first.', replyStyleOverride: 'Be concise.' });
  const result = await api('', 'POST', input);
  assert.equal(result.status, 201);
  assert.equal(result.value.profile.settings.proactiveEnabled, true);
  const patched = await api('/' + result.value.profile.id, 'PATCH', { behaviorStyleOverride: 'Ask less.', replyStyleOverride: 'Use dry humor.' });
  assert.equal(patched.status, 200);
  assert.equal(patched.value.behaviorStyle, 'Ask less.'); assert.equal(patched.value.replyStyle, 'Use dry humor.');
  assert.equal((await f.store.getProfile('kaka')).settings.proactiveEnabled, false);
  await assert.rejects(f.store.createProfile(create('invalid-role', 'Invalid fixture', { behaviorStyleOverride: null })), code('COMPANION_INVALID_INPUT'));
});

test('creation receipts from before proactive default change replay without changing existing settings', async t => {
  const f = await fixture(t);
  const input = create('old-default-receipt', 'Fictional original');
  const original = await f.store.createProfile({ ...input, settings: { proactiveEnabled: false } });
  await f.reopen(db => {
    const profile = JSON.parse(db.prepare('SELECT data FROM profiles WHERE id=?').get(original.profile.id).data);
    delete profile.templateVersion;
    db.prepare('UPDATE profiles SET data=? WHERE id=?').run(JSON.stringify(profile), profile.id);
  });
  const replayed = await f.store.createProfile(input);
  assert.equal(replayed.replayed, true); assert.equal(replayed.profile.id, original.profile.id);
  assert.equal(replayed.profile.settings.proactiveEnabled, false);
  await assert.rejects(f.store.createProfile({ ...input, name: 'Changed fixture' }), code('COMPANION_CREATION_CONFLICT'));
});
