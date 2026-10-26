import test from 'node:test';
import assert from 'node:assert/strict';
import vm from 'node:vm';
import { readFile } from 'node:fs/promises';

async function fixture(name) {
  let source = await readFile(new URL(`../../${name}/client.js`, import.meta.url), 'utf8');
  for (const [marker, file] of name === 'voice' ? [['DROY_VOICE_ADAPTER', 'dsh-adapter.js'], ['DROY_VOICE_CONTROLLER', 'client-controller.js']]
    : name === 'media-input' ? [['DROY_MEDIA_ADAPTER', 'dsh-adapter.js']] : [])
    source = source.replace(`/* ${marker} */`, await readFile(new URL(`../../${name}/${file}`, import.meta.url), 'utf8'));
  // Inspect the private translation seam without expanding the plugin's public API.
  source = source.replace('return { apply, inject:', 'return { localization: { tr, installLocale }, apply, inject:');
  let plugin, active = 'en';
  const dictionaries = new Map();
  const window = { __ModuleLoader__: { load({ factory }) { plugin = factory(() => ({})); } } };
  vm.runInNewContext(source, { window });
  plugin.localization.installLocale({ locale: {
    getSnapshot: () => ({ active }),
    register(namespace, entries) { dictionaries.set(namespace, entries); return () => dictionaries.delete(namespace); },
  }, effect() {} });
  return { ...plugin.localization, dictionaries, setLocale: value => { active = value; } };
}

for (const name of ['memory', 'voice', 'media-input']) {
  test(`${name} uses complete Chinese/English dictionaries and switches retained product messages`, async () => {
    const f = await fixture(name);
    const dictionary = f.dictionaries.get(`droy.${name}`);
    assert.deepEqual(Array.from(Object.keys(dictionary.zh)), Array.from(Object.keys(dictionary.en)));
    for (const [key, text] of Object.entries(dictionary.en)) {
      assert.ok(text.length > 0);
      assert.deepEqual((key.match(/\{\w+\}/g) || []).sort(), (text.match(/\{\w+\}/g) || []).sort(), key);
    }
    const source = name === 'memory' ? '已保存记忆。' : name === 'voice' ? '语音设置' : '导入内容';
    const translated = f.tr(source);
    assert.notEqual(translated, source);
    f.setLocale('zh');
    assert.equal(f.tr(translated), source);
    assert.equal(f.tr(source), source);
    f.setLocale('en');
    const personalText = '这是一段用户保存的中文内容，保持原文。';
    assert.equal(f.tr(personalText), personalText);
  });
}

test('translation interpolates user-provided filenames without translating their contents', async () => {
  const f = await fixture('media-input');
  const error = f.tr('无法读取附件：{v0}', { v0: '关闭' });
  assert.equal(error, 'Could not read attachment: 关闭');
  f.setLocale('zh');
  assert.equal(f.tr(error), '无法读取附件：关闭');
});
