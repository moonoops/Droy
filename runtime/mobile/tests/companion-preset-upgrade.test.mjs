import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, mkdir, readFile, realpath, rm, writeFile } from 'node:fs/promises';
import { join } from 'node:path';
import { tmpdir } from 'node:os';
import { DatabaseSync } from 'node:sqlite';
import { CompanionStore } from '../companions/store.mjs';
import { CompanionMemorySpaces } from '../companions/memory-spaces.mjs';
import { COMPANION_PERSONAS, COMPANION_TEMPLATE_VERSION, LEGACY_COMPANION_PERSONAS,
  profileFromTemplate, profileNameKeys, resolvePersona, upgradeCompanionPreset } from '../companions/profiles.mjs';
import { selectExpressions } from '../companions/maibot-expression.mjs';

const fields = ['personality', 'behaviorStyle', 'replyStyle'];
const locales = ['zh', 'en'];
const blackCatIdentity = {
  zh: '你是咔咔，一个以黑猫为形象、机灵、温和、略俏皮的伙伴。亲近但不过分热情，有自己的想法，认真倾听，也记得双方一起聊过的话题。',
  en: 'You are Kaka, a quick-minded, warm, slightly playful companion with a black-cat appearance. Be close without overwhelming enthusiasm, have views of your own, listen carefully, and remember the topics you have shared.',
};
function oldKaka(base = profileFromTemplate('kaka', { id: 'fictional-existing-kaka', now: '2026-10-01T00:00:00.000Z' })) {
  return { ...structuredClone(base), ...structuredClone(LEGACY_COMPANION_PERSONAS.kaka), personality: structuredClone(blackCatIdentity),
    avatar: '/droy/companions/assets/kaka.png', templateVersion: 1, personaRevision: 7 };
}
const otherProfileFields = profile => Object.fromEntries(Object.entries(profile)
  .filter(([key]) => ![...fields, 'avatar', 'templateVersion', 'personaRevision'].includes(key)));

async function fixture(t) {
  const home = await realpath(await mkdtemp(join(tmpdir(), 'droy-preset-upgrade-'))), directory = join(home, 'companions');
  let store = await CompanionStore.open({ directory });
  t.after(async () => { await store?.close(); await rm(home, { recursive: true, force: true }); });
  return {
    directory,
    get store() { return store; },
    async reopen(edit) {
      await store.close(); store = null;
      let result;
      if (edit) {
        const db = new DatabaseSync(join(directory, 'companions.sqlite'));
        try { result = edit(db); } finally { db.close(); }
      }
      store = await CompanionStore.open({ directory });
      return result;
    },
  };
}

function nonProfileRows(db) {
  return Object.fromEntries(['messages', 'sessions', 'settings', 'read_positions', 'companion_state', 'conversation_state',
    'name_claims', 'creation_receipts', 'creation_drafts', 'companion_uploads', 'user_profile'].map(table =>
    [table, JSON.parse(JSON.stringify(db.prepare('SELECT * FROM ' + table + ' ORDER BY rowid').all()))]));
}

test('both shipped Kaka identities upgrade exact defaults without mutating the saved snapshot', () => {
  for (const personality of [blackCatIdentity, LEGACY_COMPANION_PERSONAS.kaka.personality]) {
    const saved = { ...oldKaka(), personality: structuredClone(personality) }, snapshot = structuredClone(saved);
    const upgraded = upgradeCompanionPreset(saved);
    assert.deepEqual(saved, snapshot);
    for (const field of fields) assert.deepEqual(upgraded[field], COMPANION_PERSONAS.kaka[field]);
    assert.match(upgraded.personality.zh, /男性上班族/);
    assert.doesNotMatch(JSON.stringify(upgraded.personality), /黑猫|black.cat/i);
    assert.equal(upgraded.avatar, '/droy/companions/assets/kaka-v2.png');
    assert.equal(upgraded.templateVersion, COMPANION_TEMPLATE_VERSION);
    assert.equal(upgraded.personaRevision, saved.personaRevision + 1);
    assert.deepEqual(otherProfileFields(upgraded), otherProfileFields(saved));
    assert.deepEqual(upgradeCompanionPreset(upgraded), upgraded);
  }
});

test('only exact old defaults change, independently for each field and language', () => {
  const saved = oldKaka();
  saved.personality.zh = '虚构的用户中文人格，保留原文。';
  saved.behaviorStyle.en = 'Fictional user-authored English behavior.';
  saved.replyStyle.zh += ' ';
  const upgraded = upgradeCompanionPreset(saved);
  for (const [field, locale] of [['personality', 'zh'], ['behaviorStyle', 'en'], ['replyStyle', 'zh']])
    assert.equal(upgraded[field][locale], saved[field][locale]);
  for (const [field, locale] of [['personality', 'en'], ['behaviorStyle', 'zh'], ['replyStyle', 'en']])
    assert.equal(upgraded[field][locale], COMPANION_PERSONAS.kaka[field][locale]);
  assert.equal(upgraded.personaRevision, saved.personaRevision + 1);
});

test('custom identity or explicit styles keep their effective persona and revision when only hidden defaults change', () => {
  const identity = 'Fictional user-authored adult cartographer.';
  for (const overrides of [
    { personalityOverride: identity },
    { personalityOverride: identity, behaviorStyleOverride: 'Wait for the map coordinates.', replyStyleOverride: 'Use precise map terminology.' },
  ]) {
    const saved = { ...oldKaka(), ...overrides, avatar: 'https://fixture.invalid/custom-kaka-avatar.png' };
    const upgraded = upgradeCompanionPreset(saved);
    for (const [key, value] of Object.entries(overrides)) assert.equal(upgraded[key], value);
    for (const field of fields) assert.deepEqual(upgraded[field], COMPANION_PERSONAS.kaka[field]);
    for (const locale of locales) assert.deepEqual(resolvePersona(upgraded, locale), resolvePersona(saved, locale));
    assert.equal(upgraded.avatar, saved.avatar);
    assert.equal(upgraded.personaRevision, saved.personaRevision);
    assert.equal(upgraded.templateVersion, COMPANION_TEMPLATE_VERSION);
    assert.deepEqual(otherProfileFields(upgraded), otherProfileFields(saved));
  }
});

test('avatar-only upgrades and current or unrecognized templates cannot invalidate an unchanged effective persona', () => {
  const avatarOnly = { ...oldKaka(), ...structuredClone(COMPANION_PERSONAS.kaka) };
  const updatedAvatar = upgradeCompanionPreset(avatarOnly);
  assert.equal(updatedAvatar.avatar, '/droy/companions/assets/kaka-v2.png');
  assert.equal(updatedAvatar.personaRevision, avatarOnly.personaRevision);
  const userSelectedOldText = { ...oldKaka(), templateVersion: COMPANION_TEMPLATE_VERSION };
  assert.deepEqual(upgradeCompanionPreset(userSelectedOldText), userSelectedOldText);
  const unrecognized = { ...oldKaka(), templateId: 'fictional-private-template' };
  assert.deepEqual(upgradeCompanionPreset(unrecognized), unrecognized);
});

test('schema-3 startup upgrades one existing Kaka while keeping IDs, private data and memory-space ownership intact', async t => {
  const f = await fixture(t), initial = (await f.store.profiles())[0], id = initial.id;
  await f.store.updateProfile(id, { name: 'Fictional retained coworker', model: { provider: 'fictional-provider', model: 'fictional-model', reasoningEffort: 'low' },
    settings: { proactiveEnabled: false, memoryEnabled: true, allowProfanity: false, quietHours: { enabled: true, start: '23:30', end: '08:15' }, stickerPackIds: ['fictional-owned-pack'] } });
  await f.store.bindSession(id, { sessionId: 'fictional-retained-chat', workspaceId: 'fictional-retained-workspace', kind: 'chat' });
  await f.store.bindSession(id, { sessionId: 'fictional-retained-work', workspaceId: 'fictional-retained-workspace', kind: 'work', taskId: 'fictional-retained-task' });
  await f.store.appendMessage(id, { id: 'fictional-retained-message', clientId: 'fictional-retained-client', role: 'user', type: 'text', text: 'Fictional retained shared memory source.' });
  await f.store.markRead(id, 'fictional-retained-message');
  await f.store.updateConversation(id, { action: 'pin' });
  await f.store.updateSettings({ defaultMode: 'work' });
  await f.store.updateUserProfile({ name: 'Fictional user', bio: 'Fictional retained profile.' });
  const expressions = Array.from({ length: 10 }, (_, index) => ({ id: 'fictional-prior-expression-' + index, personaRevision: 7,
    situation: 'Fictional prior cat conversation', expression: 'Fictional prior cat catchphrase ' + index, sourceIds: ['fictional-retained-message'] }));
  const retainedState = { summary: 'Fictional retained summary.', memoryRevision: 5, expressions,
    events: [{ id: 'fictional-retained-event', sourceIds: ['fictional-retained-message'], status: 'pending' }],
    sessionSources: { 'fictional-retained-chat': { messageIds: ['fictional-retained-message'] } } };
  await f.store.updateState(id, retainedState);
  const sibling = (await f.store.createProfile({ requestId: 'fictional-sibling-create', templateId: 'maimai', name: 'Fictional retained sibling' })).profile;
  await f.store.updateState(sibling.id, { summary: 'Fictional sibling-only state.' });
  const siblingBefore = await f.store.getProfile(sibling.id), idsBefore = (await f.store.profiles()).map(profile => profile.id);
  const saved = oldKaka(await f.store.getProfile(id));
  const spaces = await CompanionMemorySpaces.open({ directory: f.directory });
  const binding = await spaces.ensureActiveSpace(id, { profile: saved });
  const siblingBinding = await spaces.ensureActiveSpace(sibling.id, { profile: siblingBefore });
  await mkdir(binding.directory, { recursive: true });
  await writeFile(join(binding.directory, 'fictional-memory.bin'), 'Fictional retained memory bytes.');
  const before = await f.reopen(db => {
    assert.equal(db.prepare('PRAGMA user_version').get().user_version, 3);
    db.prepare('UPDATE profiles SET data=? WHERE id=?').run(JSON.stringify(saved), id);
    return nonProfileRows(db);
  });
  const upgraded = await f.store.getProfile(id);
  for (const field of fields) assert.deepEqual(upgraded[field], COMPANION_PERSONAS.kaka[field]);
  assert.equal(upgraded.avatar, '/droy/companions/assets/kaka-v2.png');
  assert.equal(upgraded.personaRevision, 8);
  assert.equal(upgraded.templateVersion, COMPANION_TEMPLATE_VERSION);
  assert.deepEqual(otherProfileFields(upgraded), otherProfileFields(saved));
  assert.deepEqual(await f.store.getProfile(sibling.id), siblingBefore);
  assert.deepEqual((await f.store.profiles()).map(profile => profile.id), idsBefore);
  assert.deepEqual(await f.store.getState(id), retainedState);
  assert.equal((await f.store.getMessage(id, 'fictional-retained-message')).text, 'Fictional retained shared memory source.');
  assert.equal((await f.store.sessionOwner('fictional-retained-chat')).companionId, id);
  assert.equal((await f.store.sessionOwner('fictional-retained-work')).companionId, id);
  assert.equal((await spaces.current(id)).memorySpaceId, binding.memorySpaceId);
  assert.equal((await spaces.current(sibling.id)).memorySpaceId, siblingBinding.memorySpaceId);
  const oldSelection = await selectExpressions({ profile: saved, context: { expressions }, random: () => 0 });
  assert.equal(oldSelection.selectedIds.length, 5);
  const newSelection = await selectExpressions({ profile: upgraded, context: { expressions }, random: () => 0 });
  assert.deepEqual(newSelection.selectedIds, []);
  assert.doesNotMatch(newSelection.prompt, /Fictional prior cat catchphrase/);
  const resumedBinding = await spaces.ensureActiveSpace(id, { profile: upgraded });
  assert.equal(resumedBinding.memorySpaceId, binding.memorySpaceId);
  assert.equal(resumedBinding.companionId, id);
  assert.equal(resumedBinding.workspaceId, binding.workspaceId);
  assert.equal(resumedBinding.epoch, binding.epoch);
  assert.equal(await readFile(join(resumedBinding.directory, 'fictional-memory.bin'), 'utf8'), 'Fictional retained memory bytes.');
  assert.deepEqual((await spaces.persona(binding.memorySpaceId)).personality, upgraded.personality);
  const reopenedRows = await f.reopen(db => {
    assert.equal(db.prepare('PRAGMA user_version').get().user_version, 3);
    return nonProfileRows(db);
  });
  assert.deepEqual(reopenedRows, before);
  assert.deepEqual(await f.store.getProfile(id), upgraded);
  assert.deepEqual(await f.store.getProfile(sibling.id), siblingBefore);
  assert.deepEqual((await f.store.profiles()).map(profile => profile.id), idsBefore);
});

test('schema-3 startup keeps custom fields, overrides, avatars and routing while upgrading hidden old defaults', async t => {
  const f = await fixture(t), initial = (await f.store.profiles())[0], saved = oldKaka(initial);
  saved.name = 'Fictional preserved custom coworker'; saved.nameCustomized = true;
  saved.personality.zh = '虚构的用户中文人格，必须保留。';
  saved.behaviorStyle.en = 'Fictional retained custom behavior.';
  saved.personalityOverride = 'Fictional user-authored architect.';
  saved.behaviorStyleOverride = 'Fictional carefully chosen behavior override.';
  saved.replyStyleOverride = 'Fictional carefully chosen reply override.';
  saved.avatar = 'https://fixture.invalid/retained-user-avatar.png';
  saved.model = { provider: 'fictional-retained-provider', model: 'fictional-retained-model', reasoningEffort: 'low' };
  saved.settings = { proactiveEnabled: false, memoryEnabled: false, allowProfanity: false,
    quietHours: { enabled: false, start: '21:45', end: '07:30' }, stickerPackIds: ['fictional-retained-pack'] };
  await f.reopen(db => {
    db.prepare('UPDATE profiles SET data=? WHERE id=?').run(JSON.stringify(saved), saved.id);
    db.prepare('DELETE FROM name_claims WHERE companion_id=?').run(saved.id);
    for (const key of profileNameKeys(saved)) db.prepare('INSERT INTO name_claims VALUES(?,?)').run(key, saved.id);
  });
  const upgraded = await f.store.getProfile(saved.id);
  assert.equal(upgraded.personality.zh, saved.personality.zh);
  assert.equal(upgraded.personality.en, COMPANION_PERSONAS.kaka.personality.en);
  assert.equal(upgraded.behaviorStyle.zh, COMPANION_PERSONAS.kaka.behaviorStyle.zh);
  assert.equal(upgraded.behaviorStyle.en, saved.behaviorStyle.en);
  assert.deepEqual(upgraded.replyStyle, COMPANION_PERSONAS.kaka.replyStyle);
  assert.equal(upgraded.avatar, saved.avatar);
  assert.equal(upgraded.personaRevision, saved.personaRevision);
  assert.equal(upgraded.templateVersion, COMPANION_TEMPLATE_VERSION);
  assert.deepEqual(otherProfileFields(upgraded), otherProfileFields(saved));
  for (const locale of locales) assert.deepEqual(resolvePersona(upgraded, locale), resolvePersona(saved, locale));
  await f.reopen();
  assert.deepEqual(await f.store.getProfile(saved.id), upgraded);
  assert.deepEqual((await f.store.profiles()).map(profile => profile.id), [saved.id]);
});

test('an existing empty schema-3 installation stays empty through startup and repeated reopen', async t => {
  const f = await fixture(t);
  await f.reopen(db => {
    assert.equal(db.prepare('PRAGMA user_version').get().user_version, 3);
    db.exec('PRAGMA foreign_keys=ON; BEGIN IMMEDIATE; DELETE FROM name_claims; DELETE FROM read_positions; DELETE FROM profiles; COMMIT;');
  });
  assert.deepEqual(await f.store.profiles(), []);
  await f.reopen();
  assert.deepEqual(await f.store.profiles(), []);
});
