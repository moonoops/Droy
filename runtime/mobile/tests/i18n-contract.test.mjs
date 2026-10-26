import test from 'node:test';
import assert from 'node:assert/strict';
import vm from 'node:vm';
import { readFile, readdir } from 'node:fs/promises';
import { backendMessages } from '../backend-i18n.mjs';

const placeholders = text => [...text.matchAll(/\{(\w+)\}/g)].map(match => match[1]).sort();
function checkPairs(chinese, english, label) {
  assert.deepEqual(Object.keys(chinese).sort(), Object.keys(english).sort(), `${label}: every key needs Chinese and English`);
  for (const key of Object.keys(chinese)) {
    assert.equal(typeof chinese[key], 'string', `${label}: ${key}`);
    assert.equal(typeof english[key], 'string', `${label}: ${key}`);
    assert.ok(chinese[key].trim() && english[key].trim(), `${label}: empty translation: ${key}`);
    assert.deepEqual(placeholders(chinese[key]), placeholders(english[key]), `${label}: placeholder mismatch: ${key}`);
  }
}

test('mobile, setup and token copy require matching bilingual keys and placeholders', async () => {
  const source = await readFile(new URL('../ui-copy.js', import.meta.url), 'utf8');
  const dictionaries = vm.runInNewContext(`${source}\nDROY_UI_COPY`);
  checkPairs(dictionaries.zh, dictionaries.en, 'mobile');
});

for (const name of ['memory', 'voice', 'media-input']) test(`${name} copy requires English for every Chinese key and matching placeholders`, async () => {
  const source = await readFile(new URL(`../../${name}/client.js`, import.meta.url), 'utf8');
  const start = source.indexOf('const messages = ');
  assert.ok(start >= 0, `${name}: missing product dictionary`);
  const end = source.indexOf('\n};', start);
  assert.ok(end > start, `${name}: dictionary boundary changed`);
  const english = vm.runInNewContext(`${source.slice(start, end + '\n};'.length)}\nmessages`);
  checkPairs(Object.fromEntries(Object.keys(english).map(key => [key, key])), english, name);
});

test('backend product copy keeps interpolation placeholders in both languages', () => {
  checkPairs(Object.fromEntries(Object.keys(backendMessages).map(key => [key, key])), backendMessages, 'backend');
});

test('every bundled product prompt has both language versions', async () => {
  for (const path of ['', 'extensions/']) {
    const directory = new URL(`../prompts/${path}`, import.meta.url);
    const files = (await readdir(directory)).filter(file => file.endsWith('.md'));
    for (const file of files) {
      const counterpart = file.endsWith('.en.md') ? file.replace('.en.md', '.md') : file.replace('.md', '.en.md');
      assert.ok(files.includes(counterpart), `Missing bilingual prompt: ${path}${counterpart}`);
      assert.ok((await readFile(new URL(file, directory), 'utf8')).trim(), `Empty prompt: ${path}${file}`);
    }
  }
});
