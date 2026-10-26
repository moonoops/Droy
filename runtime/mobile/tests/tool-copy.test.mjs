import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { createRequire, registerHooks } from 'node:module';
import { pathToFileURL } from 'node:url';
import { toolDefinitions } from '../catalog.mjs';
import { petProgressTool } from '../pet-text.mjs';
import { voiceToolDefinition } from '../../voice/voice-tools.mjs';
import { localizeToolDescriptions } from '../tool-copy.mjs';

const installed = createRequire(new URL('../../dsh/package.json', import.meta.url));
const hooks = registerHooks({resolve(specifier, context, next) {
  if (specifier.startsWith('@deepseek-ai/')) return {url: pathToFileURL(installed.resolve(specifier)).href, shortCircuit: true};
  return next(specifier, context);
}});
const {notificationRulesTool} = await import('../notification-management.mjs');
const {channelsTool, notifyTool} = await import('../notifications.mjs');
hooks.deregister();
const owned = [...toolDefinitions, petProgressTool, voiceToolDefinition, notificationRulesTool, channelsTool, notifyTool];
function descriptions(value, result = []) {
  if (Array.isArray(value)) for (const item of value) descriptions(item, result);
  else if (value && typeof value === 'object') for (const [key, item] of Object.entries(value)) {
    if (key === 'description' && typeof item === 'string') result.push(item);
    else descriptions(item, result);
  }
  return result;
}
function withoutDescriptions(value) {
  if (Array.isArray(value)) return value.map(withoutDescriptions);
  if (value && typeof value === 'object') return Object.fromEntries(Object.entries(value).filter(([key, item]) => !(key === 'description' && typeof item === 'string')).map(([key, item]) => [key, withoutDescriptions(item)]));
  return value;
}

test('all owned tool and parameter descriptions have English, preserving every machine contract', () => {
  const before = JSON.stringify(owned);
  const english = localizeToolDescriptions(owned, 'en');
  for (const text of descriptions(english)) assert.ok(!/\p{Script=Han}/u.test(text), `English tool prose missing: ${text}`);
  assert.deepEqual(withoutDescriptions(english), withoutDescriptions(owned));
  assert.equal(JSON.stringify(owned), before, 'shared tool definitions must stay untouched');
  assert.equal(localizeToolDescriptions(owned, 'zh'), owned);
});

test('English skill descriptions are supplied while external prose and user values stay literal', () => {
  const source = readFileSync(new URL('../skills.mjs', import.meta.url), 'utf8');
  const skillText = [...source.matchAll(/description:\s*'([^']+)'/g)].map(match => ({description: match[1]}));
  for (const item of localizeToolDescriptions(skillText, 'en')) assert.ok(!/\p{Script=Han}/u.test(item.description));
  const external = {name: 'external', description: '第三方原文', parameters: {value: {enum: ['中文业务值'], default: '用户原文'}}};
  assert.deepEqual(localizeToolDescriptions([external], 'en'), [external]);
});
