import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { mkdtemp, rm } from 'node:fs/promises';
import { join } from 'node:path';
import { tmpdir } from 'node:os';
import { CompanionStore } from '../companions/store.mjs';
import { COMPANION_TEMPLATE_IDS, COMPANION_PERSONAS, companionTemplates, profileFromTemplate,
  localizedProfile, resolvePersona } from '../companions/profiles.mjs';
import { buildPlannerPrompt, buildReplyerRequest } from '../companions/maibot-prompts.mjs';
import { buildProactiveContext } from '../companions/proactive-policy.mjs';
import { roleExpressionExamples } from '../companions/maibot-expression.mjs';

const expected = {
  kaka: { zh: '咔咔', en: 'Kaka', avatar: 'kaka-v2.png' },
  maimai: { zh: '麦麦', en: 'Maimai', avatar: 'maimai-v2.png' },
  dafeiyu: { zh: '大肥鱼', en: 'Dafeiyu', avatar: 'dafeiyu.png' },
  claude: { zh: 'Claude 大小姐', en: 'Lady Claude', avatar: 'claude.png' },
};
const fields = ['personality', 'behaviorStyle', 'replyStyle'];
const locales = ['zh', 'en'];
const now = Date.parse('2026-10-30T12:00:00Z');
const context = () => ({ currentTime: new Date(now).toISOString(), timeZone: 'Asia/Shanghai', messages: [
  { id: 'fictional-user-message', role: 'user', type: 'text', text: 'Fictional question about a lunch plan.', createdAt: new Date(now - 60_000).toISOString() },
] });

async function fixture(t) {
  const directory = await mkdtemp(join(tmpdir(), 'droy-companion-presets-'));
  let store = await CompanionStore.open({ directory });
  t.after(async () => { await store.close(); await rm(directory, { recursive: true, force: true }); });
  return {
    get store() { return store; },
    async reopen() { await store.close(); store = await CompanionStore.open({ directory }); },
  };
}

test('the four preset choices keep their own bilingual identity, styles, names and selected avatar assets', () => {
  assert.deepEqual(COMPANION_TEMPLATE_IDS, Object.keys(expected));
  const templates = companionTemplates();
  assert.deepEqual(templates.map(profile => profile.templateId), Object.keys(expected));
  assert.equal(new Set(templates.map(profile => profile.avatar)).size, 4);
  for (const profile of templates) {
    const item = expected[profile.templateId];
    assert.equal(profile.avatar, '/droy/companions/assets/' + item.avatar);
    const bytes = readFileSync(new URL('../companions/assets/' + item.avatar, import.meta.url));
    assert.equal(bytes.subarray(0, 8).toString('hex'), '89504e470d0a1a0a');
    for (const locale of locales) {
      assert.equal(localizedProfile(profile, locale).name, item[locale]);
      for (const field of fields) assert.ok(profile[field][locale].trim(), profile.templateId + ' ' + field + ' ' + locale);
    }
  }
  assert.match(COMPANION_PERSONAS.kaka.personality.zh, /男|男性/);
  assert.match(COMPANION_PERSONAS.kaka.personality.en, /\bman\b|\bmale\b/i);
  assert.doesNotMatch(JSON.stringify(COMPANION_PERSONAS.kaka), /黑猫|black.cat|猫咪|女大学生|female university student|本鲸|Lady Claude/i);
});

test('a fresh installation creates exactly one Kaka instance and reopening does not add the other presets', async t => {
  const f = await fixture(t), initial = await f.store.profiles();
  assert.equal(initial.length, 1);
  assert.equal(initial[0].templateId, 'kaka');
  assert.match(initial[0].id, /^companion-[0-9a-f-]{36}$/);
  assert.equal(initial[0].name, null);
  for (const locale of locales) assert.equal(localizedProfile(initial[0], locale).name, expected.kaka[locale]);
  for (let iteration = 0; iteration < 2; iteration++) {
    await f.reopen();
    assert.deepEqual((await f.store.profiles()).map(profile => profile.id), [initial[0].id]);
  }
});

test('all four presets can be added with localized unique names while creation receipts remain idempotent', async t => {
  const f = await fixture(t), created = [];
  for (const [templateId, names] of Object.entries(expected)) {
    assert.equal(await f.store.suggestName(templateId, 'zh'), names.zh + ' 1');
    assert.equal(await f.store.suggestName(templateId, 'en'), names.en + ' 1');
    const input = { requestId: 'fictional-create-' + templateId, templateId, name: await f.store.suggestName(templateId, 'zh') };
    const result = await f.store.createProfile(input);
    created.push(result.profile);
    assert.equal(result.replayed, false);
    assert.equal(result.profile.templateId, templateId);
    assert.equal(result.profile.name, input.name);
    assert.equal(result.profile.avatar, '/droy/companions/assets/' + names.avatar);
    assert.equal(await f.store.suggestName(templateId, 'zh'), names.zh + ' 2');
    for (const field of fields) assert.deepEqual(result.profile[field], COMPANION_PERSONAS[templateId][field]);
    for (const locale of locales) assert.equal(localizedProfile(result.profile, locale).name, input.name, 'user names retain their original language');
    const replay = await f.store.createProfile(input);
    assert.equal(replay.replayed, true);
    assert.equal(replay.profile.id, result.profile.id);
  }
  assert.equal(new Set(created.map(profile => profile.id)).size, 4);
  const ids = (await f.store.profiles()).map(profile => profile.id);
  assert.equal(ids.length, 5);
  await f.reopen();
  assert.deepEqual((await f.store.profiles()).map(profile => profile.id), ids);
});

test('editing or mutating an instance cannot rewrite another companion or its reusable template', async t => {
  const f = await fixture(t), templatesBefore = structuredClone(COMPANION_PERSONAS), instances = [];
  for (const templateId of COMPANION_TEMPLATE_IDS) {
    const result = await f.store.createProfile({ requestId: 'fictional-isolation-' + templateId, templateId, name: 'Fictional ' + templateId });
    instances.push(result.profile);
  }
  const target = instances.find(profile => profile.templateId === 'dafeiyu');
  target.personality.zh = 'Fictional mutation of a returned copy';
  target.settings.quietHours.enabled = false;
  assert.deepEqual((await f.store.getProfile(target.id)).personality, templatesBefore.dafeiyu.personality);
  assert.equal((await f.store.getProfile(target.id)).settings.quietHours.enabled, true);
  const others = await Promise.all(instances.filter(profile => profile.id !== target.id).map(profile => f.store.getProfile(profile.id)));
  await f.store.updateProfile(target.id, { personalityOverride: 'Fictional patient cartographer.', settings: { proactiveEnabled: false } });
  for (const other of others) assert.deepEqual(await f.store.getProfile(other.id), other);
  assert.deepEqual(COMPANION_PERSONAS, templatesBefore);
  const first = profileFromTemplate('kaka'), second = profileFromTemplate('kaka');
  first.personality.en = 'Fictional factory-copy mutation';
  first.settings.quietHours.start = '20:00';
  assert.equal(second.personality.en, templatesBefore.kaka.personality.en);
  assert.equal(second.settings.quietHours.start, '22:00');
  assert.deepEqual(COMPANION_PERSONAS, templatesBefore);
});

test('both languages route each preset to its own planner, replyer and proactive persona without foreign identities', () => {
  for (const templateId of COMPANION_TEMPLATE_IDS) for (const locale of locales) {
    const profile = localizedProfile(profileFromTemplate(templateId), locale), own = COMPANION_PERSONAS[templateId], current = context();
    const planner = buildPlannerPrompt({ profile, locale });
    const replyer = buildReplyerRequest({ profile, locale, context: current }).system;
    assert.ok(planner.includes(expected[templateId][locale]));
    assert.ok(replyer.includes(expected[templateId][locale]));
    assert.ok(planner.includes(own.personality[locale]));
    assert.ok(planner.includes(own.behaviorStyle[locale]));
    assert.ok(replyer.includes(own.personality[locale]));
    assert.ok(replyer.includes(own.replyStyle[locale]));
    for (const [foreignId, foreign] of Object.entries(expected).filter(([id]) => id !== templateId)) {
      for (const foreignName of [foreign.zh, foreign.en]) {
        assert.ok(!planner.includes(foreignName), templateId + ' planner contains ' + foreignId + ' identity');
        assert.ok(!replyer.includes(foreignName), templateId + ' replyer contains ' + foreignId + ' identity');
      }
    }
    assert.doesNotMatch(planner + replyer, /\{(?:identity|personality|behavior_style|reply_style|bot_name)\}/);
    const proactive = buildProactiveContext({ profile: localizedProfile(profile, locale), messages: current.messages, now, timeZone: current.timeZone });
    assert.equal(proactive.persona.name, expected[templateId][locale]);
    for (const field of fields) assert.equal(proactive.persona[field], own[field][locale]);
    assert.deepEqual(resolvePersona(profile, locale), Object.fromEntries(fields.map(field => [field, own[field][locale]])));
  }
});

test('custom identities replace all implicit template traits and preset expression examples in either language', () => {
  const customIdentity = 'A fictional reserved adult cartographer, patient and precise.';
  for (const templateId of COMPANION_TEMPLATE_IDS) for (const locale of locales) {
    const original = profileFromTemplate(templateId), snapshot = structuredClone(original);
    const custom = localizedProfile({ ...original, personalityOverride: customIdentity }, locale);
    const planner = buildPlannerPrompt({ profile: custom, locale });
    const replyer = buildReplyerRequest({ profile: custom, locale, context: context() }).system;
    for (const prompt of [planner, replyer]) {
      assert.ok(prompt.includes(customIdentity));
      assert.ok(!prompt.includes(original.personality[locale]));
      assert.doesNotMatch(prompt, /女大学生|female university student|鲸鱼娘|whale girl|黑猫|black.cat|本鲸|大小姐.*气质/i);
    }
    assert.ok(!planner.includes(original.behaviorStyle[locale]));
    assert.ok(!replyer.includes(original.replyStyle[locale]));
    assert.deepEqual(roleExpressionExamples(custom, locale), []);
    const edited = { ...custom, behaviorStyleOverride: 'Ask only for missing map coordinates.', replyStyleOverride: 'Use calm concise sentences without catchphrases.' };
    assert.match(buildPlannerPrompt({ profile: edited, locale }), /Ask only for missing map coordinates\./);
    assert.match(buildReplyerRequest({ profile: edited, locale, context: context() }).system, /Use calm concise sentences without catchphrases\./);
    assert.deepEqual(original, snapshot);
  }
});
