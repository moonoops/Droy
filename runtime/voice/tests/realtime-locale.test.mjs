import test from 'node:test';
import assert from 'node:assert/strict';
import { realtimePrompts } from '../realtime.mjs';

test('realtime prompts and task declarations use the selected language without changing the tool protocol', () => {
  const zh = realtimePrompts('zh-CN'), en = realtimePrompts('en-US');
  assert.match(zh.taskInstruction, /默认用简洁自然的中文/);
  assert.match(zh.taskInstruction, /历史、摘要或工具结果/);
  assert.match(en.taskInstruction, /Speak concise, natural English/);
  assert.match(en.taskInstruction, /user explicitly requests another language or a translation/);
  assert.match(en.miniInstruction, /has no phone tools/);
  assert.doesNotMatch(JSON.stringify(en), /\p{Script=Han}/u);
  assert.equal(zh.declaration.name, en.declaration.name);
  assert.deepEqual(zh.declaration.parameters.required, en.declaration.parameters.required);
  assert.deepEqual(Object.keys(zh.declaration.parameters.properties), Object.keys(en.declaration.parameters.properties));
  assert.deepEqual(realtimePrompts('fr-FR'), en);
});
