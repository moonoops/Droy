import test from 'node:test';
import assert from 'node:assert/strict';
import { DatabaseSync } from 'node:sqlite';
import { mkdtemp, rm, readFile, copyFile, lstat, link, realpath } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { spawnSync } from 'node:child_process';
import { CompanionStore, companionCreationTombstoneDigest } from '../companions/store.mjs';
import { createCompanionStoreMaintenance } from '../companions/maintenance-store.mjs';

const code = value => error => error.code === 'MAINTENANCE_STORE_' + value;
const gone = error => error.code === 'COMPANION_CREATION_DELETED';
const privateText = 'PRIVATE_TARGET_BODY_827c46d8';
const imageRef = value => ({ attachmentId: 'sha256:' + value.repeat(64), bytes: 9, mediaType: 'image/png', width: 2, height: 2 });
const upload = (owner, id, ref = imageRef('b')) => ({ id, name: 'PRIVATE_UPLOAD_NAME_' + id, mediaType: ref.mediaType ?? 'text/plain',
  bytes: ref.bytes, ref, url: `/droy/companions/${owner}/attachments/${id}` });
const asJson = value => JSON.parse(JSON.stringify(value));

async function fixture(t, { version = 3 } = {}) {
  const root = await realpath(await mkdtemp(join(tmpdir(), 'droy-maintenance-store-'))), operationId = 'store-maintenance-op';
  let store = await CompanionStore.open({ directory: root });
  t.after(async () => { await store?.close(); await rm(root, { recursive: true, force: true }); });
  const keepId = (await store.profiles())[0].id;
  await store.updateProfile(keepId, { name: 'Keep this companion', model: { provider: 'saved-provider', model: 'saved-model' } });
  const draft = await store.createDraft({ requestId: 'PRIVATE_DRAFT_REQUEST_89137' });
  await store.putDraftUpload(draft.draftId, upload('creation-drafts/' + draft.draftId, 'private-avatar', imageRef('a')));
  await store.putDraftUpload(draft.draftId, upload('creation-drafts/' + draft.draftId, 'shared-wallpaper', imageRef('b')));
  await store.putDraftUpload(draft.draftId, upload('creation-drafts/' + draft.draftId, 'unused-image', imageRef('c')));
  const input = { requestId: 'PRIVATE_PROFILE_REQUEST_1984', templateId: 'kaka', name: 'PRIVATE_TARGET_NAME_2f38',
    personalityOverride: 'PRIVATE_PERSONALITY_56789', model: null, draftId: draft.draftId, avatarUploadId: 'private-avatar',
    chatBackground: { type: 'upload', id: 'shared-wallpaper' } };
  const targetId = (await store.createProfile(input)).profile.id;
  await store.putUpload(keepId, upload(keepId, 'keep-shared', imageRef('b')));
  await store.putUserUpload(upload('user-profile', 'user-shared', imageRef('b')));
  await store.updateUserProfile({ name: 'Keep global user', avatarUrl: '/droy/companions/user-profile/attachments/user-shared' });
  await store.updateSettings({ defaultMode: 'work', timeZone: 'Asia/Shanghai' });
  await store.bindSession(targetId, { sessionId: 'target-chat', workspaceId: 'target-workspace', kind: 'chat' });
  await store.bindSession(targetId, { sessionId: 'target-work', workspaceId: 'target-workspace', kind: 'work', taskId: 'target-task' });
  await store.bindSession(keepId, { sessionId: 'keep-chat', workspaceId: 'keep-workspace', kind: 'chat' });
  await store.appendMessage(targetId, { id: 'private-message', clientId: 'private-client', role: 'user', text: privateText });
  await store.appendMessage(targetId, { id: 'target-task', role: 'assistant', type: 'task', text: 'PRIVATE_TASK_TITLE',
    task: { id: 'target-task', sessionId: 'target-work', state: 'completed', summary: 'PRIVATE_TASK_RESULT' } });
  await store.appendMessage(keepId, { id: 'keep-message', role: 'user', text: 'Keep this history' });
  await store.updateState(targetId, { summary: 'PRIVATE_LEARNED_SUMMARY', learningCursor: 'seq:0', memoryRevision: 7,
    sessionSources: { 'target-chat': { messageIds: ['private-message'], hasSummary: true } }, invalidSessionIds: ['target-work'] });
  await store.updateState(keepId, { summary: 'Keep learned state' });
  await store.updateConversation(targetId, { action: 'pin' }); await store.markRead(targetId);
  const unrelated = await store.createDraft({ requestId: input.requestId });
  await store.putDraftUpload(unrelated.draftId, upload('creation-drafts/' + unrelated.draftId, 'keep-draft-image', imageRef('d')));
  const kept = { profile: await store.getProfile(keepId), state: await store.getState(keepId), messages: (await store.listMessages(keepId)).messages,
    settings: await store.getSettings(), user: await store.getUserProfile(), userUpload: await store.getUserUpload('user-shared'), draft: await store.getDraft(unrelated.draftId) };
  await store.close(); store = null;
  const path = join(root, 'companions.sqlite');
  if (version === 2) { const db = new DatabaseSync(path); db.exec('DROP TABLE creation_tombstones; DROP TABLE maintenance_operations; PRAGMA user_version=2;'); db.close(); }
  let held = true, epoch = 'store-maintenance-epoch', approved;
  const authority = { async assertHeld(request) {
    assert.equal(request.scope, 'companion-store');
    return { root, operationId, admissionEpoch: epoch, hostStopped: held, admissionsClosed: held,
      ...(approved ? { manifestDigest: approved } : {}) };
  } };
  return { root, path, operationId, targetId, keepId, input, draft, unrelated, kept, authority,
    approve(value) { approved = value; }, setHeld(value) { held = value; }, setEpoch(value) { epoch = value; },
    create(extra = {}) { return createCompanionStoreMaintenance({ root, operationId, companionId: targetId, authority, ...extra }); },
    async reopen() { store = await CompanionStore.open({ directory: root }); return store; },
    db(readOnly = true) { return new DatabaseSync(path, { readOnly }); } };
}

async function approved(f, extra) { const operation = await f.create(extra), plan = await operation.prepare(); f.approve(plan.manifestDigest); return { operation, plan }; }

test('read-only prepare proves actual bindings, full creation refs and shared primary references without changing SQLite bytes', async t => {
  const f = await fixture(t), bytes = await readFile(f.path), operation = await f.create(), plan = await operation.prepare();
  assert.deepEqual(await readFile(f.path), bytes); assert.equal(plan.phase, 'prepared'); assert.equal(plan.scope, 'companion-store');
  assert.deepEqual(plan.manifest.sessions.map(row => row.sessionId), ['target-chat', 'target-work']);
  assert.deepEqual(plan.manifest.taskClaims, [{ taskId: 'target-task', sessionId: 'target-work', bound: true }]);
  assert.deepEqual(plan.manifest.creation.receipts, [{ requestId: f.input.requestId }]);
  assert.equal(plan.manifest.creation.drafts[0].draftId, f.draft.draftId);
  assert.equal(plan.manifest.uploads.length, 3);
  assert.ok(plan.manifest.blobs.find(row => row.ref.attachmentId === imageRef('b').attachmentId).retainedInStore.some(source => source.includes('user-profile')));
  assert.equal((await operation.status()).phase, 'prepared');
});

test('Store commit requires durable full-manifest authority in addition to exclusive stopped/admissions proof', async t => {
  const f = await fixture(t), operation = await f.create(), plan = await operation.prepare(), bytes = await readFile(f.path);
  await assert.rejects(operation.commit(), code('MANIFEST_NOT_HELD')); assert.deepEqual(await readFile(f.path), bytes);
  f.approve(plan.manifestDigest); f.setHeld(false); await assert.rejects(operation.commit(), code('AUTHORITY_LOST'));
  f.setHeld(true); f.setEpoch('other-epoch'); await assert.rejects(operation.commit(), code('AUTHORITY_CHANGED'));
  await assert.rejects(f.create({ authority: { hostStopped: true, admissionsClosed: true } }), code('AUTHORITY_REQUIRED'));
});

test('atomic deletion removes only target data, closes all old creation retries, preserves every other owner and compacts current SQLite', async t => {
  const f = await fixture(t), { operation, plan } = await approved(f);
  const receipt = await operation.commit(); assert.equal(receipt.phase, 'complete'); assert.equal(receipt.hygiene.complete, true);
  let wal; try { wal = await lstat(f.path + '-wal'); } catch (error) { if (error.code !== 'ENOENT') throw error; }
  assert.ok(!wal || wal.size === 0, 'Current WAL must be absent or truly truncated');
  assert.equal(receipt.scope, 'companion-store-only'); assert.equal(receipt.companionDeletion, 'incomplete');
  assert.equal((await operation.recover()).phase, 'complete'); assert.equal((await operation.status()).phase, 'complete');
  const db = f.db();
  try {
    assert.deepEqual(db.prepare('PRAGMA foreign_key_check').all(), []); assert.equal(db.prepare('PRAGMA freelist_count').get().freelist_count, 0);
    assert.equal(db.prepare('PRAGMA user_version').get().user_version, 3);
    for (const table of ['messages', 'sessions', 'read_positions', 'companion_state', 'conversation_state', 'message_tombstones', 'companion_uploads', 'name_claims', 'creation_receipts'])
      assert.equal(db.prepare(`SELECT COUNT(*) count FROM ${table} WHERE companion_id=?`).get(f.targetId).count, 0, table);
    assert.equal(db.prepare('SELECT COUNT(*) count FROM creation_drafts WHERE id=?').get(f.draft.draftId).count, 0);
    assert.equal(db.prepare('SELECT COUNT(*) count FROM draft_uploads WHERE draft_id=?').get(f.draft.draftId).count, 0);
    const tombstones = asJson(db.prepare('SELECT * FROM creation_tombstones ORDER BY kind').all());
    assert.deepEqual(tombstones.map(row => row.kind), ['draft-id', 'draft-request', 'profile-request']);
    assert.ok(tombstones.every(row => /^[a-f0-9]{64}$/.test(row.digest)));
    const journal = JSON.stringify(db.prepare('SELECT * FROM maintenance_operations').all());
    assert.equal(journal.includes('PRIVATE_'), false); assert.equal(journal.includes('/droy/'), false);
    assert.equal(db.prepare('SELECT retained_digest FROM maintenance_operations').get().retained_digest, plan.retainedDigest);
  } finally { db.close(); }
  const store = await f.reopen();
  await assert.rejects(store.getProfile(f.targetId), error => error.code === 'COMPANION_NOT_FOUND');
  for (const input of [f.input, { ...f.input, name: 'A changed retry' }]) await assert.rejects(store.createProfile(input), gone);
  await assert.rejects(store.getCreation(f.input.requestId), gone);
  await assert.rejects(store.createDraft({ requestId: 'PRIVATE_DRAFT_REQUEST_89137' }), gone);
  await assert.rejects(store.getDraft(f.draft.draftId), gone); await assert.rejects(store.cancelDraft(f.draft.draftId), gone);
  assert.equal((await store.createDraft({ requestId: f.input.requestId })).draftId, f.unrelated.draftId, 'Request domains remain independent');
  assert.deepEqual(await store.getProfile(f.keepId), f.kept.profile); assert.deepEqual(await store.getState(f.keepId), f.kept.state);
  assert.deepEqual((await store.listMessages(f.keepId)).messages, f.kept.messages); assert.deepEqual(await store.getSettings(), f.kept.settings);
  assert.deepEqual(await store.getUserProfile(), f.kept.user); assert.deepEqual(await store.getUserUpload('user-shared'), f.kept.userUpload);
  assert.deepEqual(await store.getDraft(f.unrelated.draftId), f.kept.draft);
  const bytes = await readFile(f.path); for (const value of [privateText, 'PRIVATE_PERSONALITY_56789', 'PRIVATE_LEARNED_SUMMARY', 'PRIVATE_TARGET_NAME_2f38']) assert.equal(bytes.includes(Buffer.from(value)), false);
});

test('known schema2 upgrades inside the deletion transaction and no existing empty catalog is reseeded', async t => {
  const f = await fixture(t, { version: 2 }), { operation } = await approved(f); await operation.commit();
  const store = await f.reopen(); assert.deepEqual((await store.profiles()).map(row => row.id), [f.keepId]);
  assert.equal((await store.getProfile(f.keepId)).model.model, 'saved-model');
  await store.close(); let heldManifest;
  const last = await createCompanionStoreMaintenance({ root: f.root, operationId: 'last-companion', companionId: f.keepId,
    authority: { async assertHeld() { return { root: f.root, operationId: 'last-companion', admissionEpoch: 'last-epoch',
      hostStopped: true, admissionsClosed: true, manifestDigest: heldManifest }; } } });
  heldManifest = (await last.prepare()).manifestDigest; await last.commit();
  const empty = await f.reopen(); assert.deepEqual(await empty.profiles(), []);
});

test('a real remaining SQLite reader prevents a successful WAL hygiene receipt until its read transaction closes', async t => {
  const f = await fixture(t), { operation } = await approved(f), reader = f.db();
  reader.exec('BEGIN'); reader.prepare('SELECT data FROM profiles').all();
  try {
    await assert.rejects(operation.commit(), code('CHECKPOINT_BUSY'));
    const status = await operation.status(); assert.equal(status.phase, 'deleted'); assert.equal(status.hygiene.complete, false);
  } finally { reader.exec('ROLLBACK'); reader.close(); }
  assert.equal((await operation.recover()).phase, 'complete');
});

test('unknown tables, triggers, schema versions and broken foreign keys fail without modifying source rows', async t => {
  for (const statement of ['CREATE TABLE future_private(id TEXT)', 'CREATE TRIGGER future_delete BEFORE DELETE ON profiles BEGIN SELECT 1; END',
    'PRAGMA user_version=99', "PRAGMA foreign_keys=OFF; INSERT INTO read_positions VALUES('unowned-profile',1)"]) await t.test(statement.split(' ').slice(0, 3).join(' '), async child => {
    const f = await fixture(child), db = f.db(false); db.exec(statement); db.close();
    const bytes = await readFile(f.path), operation = await f.create();
    await assert.rejects(operation.prepare(), error => ['MAINTENANCE_STORE_SCHEMA_UNSUPPORTED', 'MAINTENANCE_STORE_INTEGRITY_FAILED'].includes(error.code));
    assert.deepEqual(await readFile(f.path), bytes);
  });
});

test('unknown attachment refs and cross-owner dangling product URLs are refused instead of guessed', async t => {
  for (const corrupt of ['ref', 'avatar']) await t.test(corrupt, async child => {
    const f = await fixture(child), db = f.db(false);
    if (corrupt === 'ref') db.prepare("UPDATE companion_uploads SET data=json_set(data,'$.ref.privatePath','/private/unknown') WHERE companion_id=?").run(f.targetId);
    else db.prepare("UPDATE profiles SET data=json_set(data,'$.avatar',?) WHERE id=?").run(`/droy/companions/${f.targetId}/attachments/private-avatar`, f.keepId);
    db.close(); const bytes = await readFile(f.path), operation = await f.create();
    await assert.rejects(operation.prepare(), error => ['MAINTENANCE_STORE_REFERENCE_INVALID', 'MAINTENANCE_STORE_REFERENCE_CONFLICT'].includes(error.code));
    assert.deepEqual(await readFile(f.path), bytes);
  });
});

test('changed retained data or authority loss before COMMIT rolls back target deletion and all creation tombstones', async t => {
  const f = await fixture(t), { operation } = await approved(f, { onCheckpoint(phase) { if (phase === 'rows-deleted') f.setHeld(false); } });
  await assert.rejects(operation.commit(), code('AUTHORITY_LOST')); f.setHeld(true);
  const db = f.db();
  try { assert.ok(db.prepare('SELECT 1 FROM profiles WHERE id=?').get(f.targetId)); assert.equal(db.prepare('SELECT COUNT(*) count FROM creation_tombstones').get().count, 0); }
  finally { db.close(); }
  const next = await f.create(), plan = await next.prepare(); f.approve(plan.manifestDigest);
  const writer = f.db(false); writer.prepare("UPDATE profiles SET data=json_set(data,'$.personalityOverride','Retained change') WHERE id=?").run(f.keepId); writer.close();
  await assert.rejects(next.commit(), code('SOURCE_CHANGED'));
});

test('prepare also binds full target row bytes without copying private message or persona content into its manifest', async t => {
  const f = await fixture(t), { operation, plan } = await approved(f);
  assert.match(plan.manifest.targetDigest, /^[a-f0-9]{64}$/);
  assert.equal(JSON.stringify(plan.manifest).includes(privateText), false);
  assert.equal(JSON.stringify(plan.manifest).includes('PRIVATE_PERSONALITY_56789'), false);
  const writer = f.db(false); writer.prepare("UPDATE profiles SET data=json_set(data,'$.personalityOverride','Changed after preparation') WHERE id=?").run(f.targetId); writer.close();
  await assert.rejects(operation.commit(), code('SOURCE_CHANGED'));
  const db = f.db(); try { assert.equal(JSON.parse(db.prepare('SELECT data FROM profiles WHERE id=?').get(f.targetId).data).personalityOverride, 'Changed after preparation'); } finally { db.close(); }
});

test('a crash after the SQL COMMIT is recovered from opaque operation state without repeating creation or losing others', async t => {
  const f = await fixture(t), { operation } = await approved(f, { onCheckpoint(phase) { if (phase === 'deleted') throw Object.assign(new Error('synthetic interruption'), { code: 'INTERRUPTED' }); } });
  await assert.rejects(operation.commit(), error => error.code === 'INTERRUPTED');
  assert.equal((await operation.status()).phase, 'deleted');
  const restarted = await f.create(); assert.equal((await restarted.recover()).phase, 'complete');
  const store = await f.reopen(); assert.deepEqual(await store.getProfile(f.keepId), f.kept.profile); await assert.rejects(store.createProfile(f.input), gone);
});

test('real process interruption before commit and after durable delete/vacuum preserves transactional recovery', async t => {
  for (const phase of ['rows-deleted', 'deleted', 'vacuumed']) await t.test(phase, async child => {
    const f = await fixture(child), { plan } = await approved(f);
    const source = `import {createCompanionStoreMaintenance} from ${JSON.stringify(new URL('../companions/maintenance-store.mjs', import.meta.url).href)};
      const [root,operationId,companionId,manifestDigest,phase]=process.argv.slice(1);
      const authority={async assertHeld(){return {root,operationId,admissionEpoch:'store-maintenance-epoch',hostStopped:true,admissionsClosed:true,manifestDigest};}};
      const job=await createCompanionStoreMaintenance({root,operationId,companionId,authority,onCheckpoint(value){if(value===phase)process.exit(72);}});
      await job.prepare();await job.commit();`;
    const result = spawnSync(process.execPath, ['--input-type=module', '-e', source, f.root, f.operationId, f.targetId, plan.manifestDigest, phase], { timeout: 15000, encoding: 'utf8' });
    assert.equal(result.status, 72, result.stderr);
    const db = f.db(); try { assert.equal(!!db.prepare('SELECT 1 FROM profiles WHERE id=?').get(f.targetId), phase === 'rows-deleted'); } finally { db.close(); }
    const recovered = await f.create(); assert.equal((await recovered.recover()).phase, 'complete');
  });
});

test('maintenance never follows a hardlinked SQLite file or claims to erase separately saved backups', async t => {
  const f = await fixture(t), backup = f.path + '.separate-backup'; await copyFile(f.path, backup);
  const { operation } = await approved(f); assert.equal((await operation.commit()).hygiene.externalBackupsErased, false);
  assert.ok((await readFile(backup)).includes(Buffer.from(privateText)));
  const g = await fixture(t), alias = g.path + '.hardlink'; await link(g.path, alias);
  await assert.rejects(g.create(), code('LINKED_PATH')); assert.equal((await lstat(g.path)).nlink, 2);
});

test('recovery refuses lost creation tombstones and a different operation epoch instead of claiming completed deletion', async t => {
  const f = await fixture(t), { operation } = await approved(f); await operation.commit();
  f.setEpoch('different-maintenance-epoch'); const changed = await f.create(); await assert.rejects(changed.prepare(), code('OPERATION_CONFLICT'));
  f.setEpoch('store-maintenance-epoch');
  const db = f.db(false); db.exec("DELETE FROM creation_tombstones WHERE kind='profile-request'"); db.close();
  const bytes = await readFile(f.path), broken = await f.create(); await assert.rejects(broken.recover(), code('JOURNAL_INVALID'));
  assert.deepEqual(await readFile(f.path), bytes);
});

test('tombstone domains cannot collide and contain only irreversible request digests', () => {
  const values = ['profile-request', 'draft-request', 'draft-id'].map(kind => companionCreationTombstoneDigest(kind, 'PRIVATE_NAME_REQUEST'));
  assert.equal(new Set(values).size, 3); assert.ok(values.every(value => /^[a-f0-9]{64}$/.test(value) && !value.includes('PRIVATE')));
});
