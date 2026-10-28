import test, { after } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { DatabaseSync } from 'node:sqlite';
import { registerHooks } from 'node:module';
import { CompanionStore } from '../companions/store.mjs';
import { defaultProfiles, LEGACY_COMPANION_PERSONAS } from '../companions/profiles.mjs';

const imports = registerHooks({ resolve(specifier, context, next) {
  if (specifier === '@droyagent/memory/store') return { url: new URL('../../memory/store.mjs', import.meta.url).href, shortCircuit: true };
  return next(specifier, context);
} });
after(() => imports.deregister());
const { CompanionMemory } = await import('../companions/memory.mjs');
const personaFields = ['personality', 'behaviorStyle', 'replyStyle'];

async function fixture(t) {
  const directory = await mkdtemp(join(tmpdir(), 'droy-maibot-migration-'));
  let store = await CompanionStore.open({ directory });
  t.after(async () => { await store?.close(); await rm(directory, { recursive: true, force: true }); });
  return {
    directory, get store() { return store; },
    async reopen(edit) {
      await store.close(); store = null;
      if (edit) {
        const db = new DatabaseSync(join(directory, 'companions.sqlite'));
        try { edit(db); } finally { db.close(); }
      }
      store = await CompanionStore.open({ directory });
    },
  };
}
function editProfile(db, id, transform) {
  const profile = JSON.parse(db.prepare('SELECT data FROM profiles WHERE id=?').get(id).data);
  db.prepare('UPDATE profiles SET data=? WHERE id=?').run(JSON.stringify(transform(profile)), id);
}

test('old stock personas upgrade without changing identity, configuration, sessions, learning, history or private memories', async t => {
  const f = await fixture(t);
  const defaults = new Map(defaultProfiles().map(profile => [profile.id, profile]));
  for (const id of defaults.keys()) {
    await f.store.updateProfile(id, {
      name: 'Fictional custom ' + id, avatar: 'https://example.com/' + id + '.png',
      model: { provider: 'fixture', model: 'local-' + id },
      settings: { proactiveEnabled: true, memoryEnabled: false, allowProfanity: true, quietHours: null },
    });
    await f.store.bindSession(id, { sessionId: id + '-chat', workspaceId: id + '-workspace', kind: 'chat' });
    await f.store.appendMessage(id, { id: 'old-message', role: 'user', text: 'Fictional private ' + id });
    await f.store.updateState(id, { summary: 'Fictional shared past ' + id, memoryRevision: 4,
      expressions: [{ situation: 'Fictional greeting', expression: 'Fictional shared expression', sourceIds: ['old-message'] }] });
  }
  const before = new Map(await Promise.all([...defaults.keys()].map(async id => [id, {
    profile: await f.store.getProfile(id), state: await f.store.getState(id), messages: await f.store.listMessages(id),
  }])));
  let memory = await CompanionMemory.open({ directory: f.directory, companionId: 'maimai' });
  const saved = await memory.remember({ text: 'Fictional retained preference', kind: 'preference', sourceRefs: ['old-message'] });
  await memory.close();
  await f.reopen(db => {
    for (const id of defaults.keys()) editProfile(db, id, profile => ({ ...profile, ...structuredClone(LEGACY_COMPANION_PERSONAS[id]) }));
  });
  for (const [id, previous] of before) {
    const expected = { ...previous.profile };
    for (const field of personaFields) expected[field] = defaults.get(id)[field];
    assert.deepEqual(await f.store.getProfile(id), expected);
    assert.deepEqual(await f.store.getState(id), previous.state);
    assert.deepEqual(await f.store.listMessages(id), previous.messages);
  }
  memory = await CompanionMemory.open({ directory: f.directory, companionId: 'maimai' });
  try { assert.deepEqual(await memory.get(saved.recordId), saved.record); } finally { await memory.close(); }
  await f.reopen();
  for (const [id, previous] of before) assert.equal((await f.store.getProfile(id)).updatedAt, previous.profile.updatedAt);
});

test('preset migration compares each translation exactly and preserves partial customizations', async t => {
  const f = await fixture(t), current = defaultProfiles().find(profile => profile.id === 'maimai');
  const old = structuredClone(LEGACY_COMPANION_PERSONAS.maimai);
  const custom = {
    personality: { zh: old.personality.zh + ' ', en: old.personality.en },
    behaviorStyle: { zh: old.behaviorStyle.zh, en: 'Fictional custom behavior' },
    replyStyle: { zh: '虚构的自定义表达方式', en: old.replyStyle.en },
  };
  await f.reopen(db => editProfile(db, 'maimai', profile => ({ ...profile, ...custom })));
  const result = await f.store.getProfile('maimai');
  assert.deepEqual(result.personality, { zh: custom.personality.zh, en: current.personality.en });
  assert.deepEqual(result.behaviorStyle, { zh: current.behaviorStyle.zh, en: custom.behaviorStyle.en });
  assert.deepEqual(result.replyStyle, { zh: custom.replyStyle.zh, en: current.replyStyle.en });
  await f.reopen();
  assert.deepEqual(await f.store.getProfile('maimai'), result);
});

test('fully customized personas and new defaults reopen without profile writes', async t => {
  const f = await fixture(t);
  await f.store.updateProfile('maimai', Object.fromEntries(personaFields.map(field => [field, { zh: '虚构自定义' + field, en: 'Fictional custom ' + field }])));
  const expected = await f.store.getProfile('maimai');
  await f.reopen(db => db.exec("CREATE TRIGGER reject_persona_write BEFORE UPDATE ON profiles BEGIN SELECT RAISE(ABORT,'unexpected profile rewrite'); END"));
  assert.deepEqual(await f.store.getProfile('maimai'), expected);
  assert.deepEqual((await f.store.getProfile('kaka')).personality, defaultProfiles()[0].personality);
});

test('failed preset upgrade rolls back all profile changes and is retried on a later open', async t => {
  const f = await fixture(t);
  await assert.rejects(f.reopen(db => {
    for (const id of ['kaka', 'maimai']) editProfile(db, id, profile => ({ ...profile, ...structuredClone(LEGACY_COMPANION_PERSONAS[id]) }));
    db.exec("CREATE TRIGGER reject_maimai_upgrade BEFORE UPDATE ON profiles WHEN OLD.id='maimai' BEGIN SELECT RAISE(ABORT,'fictional upgrade failure'); END");
  }), /fictional upgrade failure/);
  const db = new DatabaseSync(join(f.directory, 'companions.sqlite'));
  try {
    for (const id of ['kaka', 'maimai']) {
      const profile = JSON.parse(db.prepare('SELECT data FROM profiles WHERE id=?').get(id).data);
      for (const field of personaFields) assert.deepEqual(profile[field], LEGACY_COMPANION_PERSONAS[id][field]);
    }
    db.exec('DROP TRIGGER reject_maimai_upgrade');
  } finally { db.close(); }
  const recovered = await CompanionStore.open({ directory: f.directory });
  try { assert.deepEqual((await recovered.getProfile('maimai')).personality, defaultProfiles()[1].personality); }
  finally { await recovered.close(); }
});
