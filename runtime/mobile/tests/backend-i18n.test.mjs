import test from 'node:test';
import assert from 'node:assert/strict';
import { backendMessages, translateBackendText, localizePetResponse, localizeSetupResponse } from '../backend-i18n.mjs';
import { createPetPersona, createDshPetPersonaGenerator } from '../pet-persona.mjs';
import { createBridge } from '../bridge.mjs';
import { createAudioService, speechPreparation } from '../audio.mjs';
import { requireMobileSession } from '../auth.mjs';

test('backend-owned text has both languages and preserves placeholders and unknown content', () => {
  for (const [zh, en] of Object.entries(backendMessages)) {
    assert.ok(zh.trim() && en.trim());
    assert.equal(translateBackendText(zh, 'zh-CN'), zh);
    assert.equal(translateBackendText(zh, 'en-US'), en);
    assert.doesNotMatch(en, /[\p{Script=Han}]/u);
  }
  assert.equal(translateBackendText('正在生成待机与轻触（2/6）', 'en'), 'Generating Idle and tap (2/6)');
  assert.match(translateBackendText('招呼与抚摸的第3行存在空帧或越界，请重做这一组', 'en'), /Greeting and petting, row 3: empty or out-of-bounds frames/);
  assert.equal(translateBackendText('外观描述请填写1–1000个字符', 'en'), 'Appearance description must contain 1–1000 characters.');
  assert.equal(translateBackendText('fictional user content 中文', 'en'), 'fictional user content 中文');
});

test('pet projections translate stages and errors while preserving user-owned and generated fields', () => {
  const original = { configurationMessage: '使用 OpenAI 图片服务，按服务计费。', pets: [{
    name: '正在生成形象', description: '默认空间', personality: '描述已保存',
    persona: { voice: { working: ['{name}的用户自定义短句。'] } },
    stage: '正在生成形象', error: '图片尺寸无效', groups: [{ id: 'idle-touch', label: '待机与轻触' }],
  }] };
  const view = localizePetResponse(original, 'en');
  assert.equal(view.pets[0].stage, 'Generating character');
  assert.equal(view.pets[0].error, 'Invalid image dimensions.');
  assert.equal(view.pets[0].name, original.pets[0].name);
  assert.equal(view.pets[0].description, original.pets[0].description);
  assert.equal(view.pets[0].personality, original.pets[0].personality);
  assert.equal(view.pets[0].persona, original.pets[0].persona);
  assert.equal(original.pets[0].stage, '正在生成形象');
  assert.equal(localizePetResponse(original, 'zh').pets[0].stage, '正在生成形象');
});

test('setup receipt presentation changes language without changing its stored verification or model identity', () => {
  const receipt = { selection: { provider: 'fictional-provider', model: '模型对话和工具调用验证通过。' },
    modelVerification: { passed: true, toolsPassed: false, detail: '模型对话可用，工具调用尚未通过。' },
    test: { message: '模型对话验证通过；工具调用尚未通过，可先使用对话。' } };
  const localized = localizeSetupResponse(receipt, 'en');
  assert.match(localized.modelVerification.detail, /Model chat is available/);
  assert.match(localized.test.message, /you can use chat first/);
  assert.equal(localized.selection, receipt.selection);
  assert.equal(localized.modelVerification.passed, true);
  assert.equal(receipt.modelVerification.detail, '模型对话可用，工具调用尚未通过。');
});

test('local persona voices have English equivalents without translating custom descriptions', () => {
  for (const description of ['', '安静稳重', '活泼热情', '害羞', '温柔', '傲娇', '话少']) {
    const zh = createPetPersona(description, 'zh'), en = createPetPersona(description, 'en');
    assert.equal(en.temperament, zh.temperament);
    assert.deepEqual(en.traits, zh.traits);
    if (description) assert.equal(en.description, description);
    for (const line of Object.values(en.voice).flat()) {
      assert.ok(line.includes('{name}'));
      assert.doesNotMatch(line, /[\p{Script=Han}]/u);
      assert.ok(line.length <= 120);
    }
  }
});

test('persona generation reads live settings each call and keeps an explicit language on generated voice data', async () => {
  let language = 'en';
  const requests = [];
  const value = createPetPersona('calm', 'en');
  const llm = { async *stream(request) {
    requests.push(request);
    yield { type: 'text-delta', text: JSON.stringify(value) };
    yield { type: 'finish', reason: { kind: 'stop' } };
  } };
  const settings = { describe: () => [{ ns: 'locale', value: { preference: language } }] };
  const ctx = { get(name) { return name === 'llm' ? llm : name === 'settings' ? settings : name === 'agentDefaultModel'
    ? { currentSelection: () => ({ provider: 'fixture', model: 'scripted' }) } : undefined; }, llm };
  const generator = createDshPetPersonaGenerator(ctx);
  assert.equal((await generator.generate({ description: '自定义说明', appearance: '虚构角色' })).language, 'en');
  assert.match(requests[0].system, /voice phrases in English/);
  language = 'zh';
  assert.equal((await generator.generate({ description: '自定义说明', appearance: '虚构角色' })).language, 'zh');
  assert.match(requests[1].system, /互动短句使用中文/);
  assert.match(requests[0].messages[0].content[0].text, /自定义说明/);
});

test('bridge-owned errors follow the live language and native error text remains unchanged', async () => {
  let locale = 'en';
  let envelope = { ok: false, error: {} };
  const bridge = createBridge({ port: 12345, token: 'fictional-localization-token-1234567890', getLocale: () => locale,
    fetchImpl: async () => ({ ok: false, json: async () => envelope }) });
  const exec = { agent: { id: 'fixture' }, callId: 'test', signal: new AbortController().signal };
  await assert.rejects(bridge.call('observe', {}, exec), /The native bridge request failed/);
  locale = 'zh';
  await assert.rejects(bridge.call('observe', {}, exec), /原生桥请求失败/);
  locale = 'en'; envelope = { ok: false, error: { code: 'FIXTURE', message: '保留原生具体错误' } };
  await assert.rejects(bridge.call('observe', {}, exec), /保留原生具体错误/);
  await assert.rejects(bridge.call('observe', {}, { ...exec, agent: undefined }), /missing an engine identity/);
});

test('audio-owned errors and provider label read the current language without translating transcripts', async () => {
  let locale = 'en';
  const audio = createAudioService({ bridge: {}, attachments: {}, getLocale: () => locale });
  assert.equal(audio.provider.info.name, 'SenseVoice local recognition (Android)');
  const exec = { agent: { id: 'fixture' }, callId: 'test', signal: new AbortController().signal };
  await assert.rejects(audio.execute('unknown', {}, exec), /Unknown audio tool/);
  locale = 'zh';
  assert.equal(audio.provider.info.name, 'SenseVoice 本地识别（Android）');
  await assert.rejects(audio.execute('unknown', {}, exec), /未知音频工具/);
  const nativeMessage = '来自原生识别器的具体错误';
  assert.ok(speechPreparation({ state: 'failed', error: nativeMessage }, 'en').message.includes(nativeMessage));
  await audio.dispose();
});

test('unauthenticated pages use only the installation locale while preserving rejection', () => {
  const before = process.env.DROY_LOCALE;
  try {
    for (const locale of ['zh', 'en']) {
      process.env.DROY_LOCALE = locale;
      let status, body;
      const accepted = requireMobileSession({ headers: { host: 'untrusted.example' } }, {
        writeHead(value) { status = value; }, end(value) { body = value; },
      });
      assert.equal(accepted, false);
      assert.equal(status, 401);
      assert.match(body, locale === 'zh' ? /会话未授权/ : /session unauthorized/);
    }
  } finally {
    if (before === undefined) delete process.env.DROY_LOCALE; else process.env.DROY_LOCALE = before;
  }
});
