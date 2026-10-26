import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { createRequire, registerHooks } from 'node:module';
import { pathToFileURL } from 'node:url';
import { patchSessionTitleProvider } from '../../../tools/mobile_prompt_patch.mjs';
const installed = createRequire(new URL('../../dsh/package.json', import.meta.url));
const hooks = registerHooks({resolve(specifier, context, next) {
  if (specifier === '@droyagent/mobile/prompt') return {url: new URL('../prompt.mjs', import.meta.url).href, shortCircuit: true};
  if (specifier.startsWith('@deepseek-ai/')) return {url: pathToFileURL(installed.resolve(specifier)).href, shortCircuit: true};
  return next(specifier, context);
}});
const providers = [];
for (const name of ['first-prompt', 'all-prompts']) {
  const source = await readFile(new URL(`../../dsh/node_modules/@deepseek-ai/dsh-session-title-${name}-llm/lib/index.js`, import.meta.url), 'utf8');
  const provider = await import('data:text/javascript;base64,' + Buffer.from(patchSessionTitleProvider(source)).toString('base64'));
  providers.push({name, provider, source});
}
hooks.deregister();

for (const {name, provider, source} of providers) test(`real DSH ${name} title requests follow live settings rather than message language`, async () => {
  let language = 'zh', registered;
  const requests = [], events = [];
  const settings = {describe: () => [{ns: 'locale', value: {preference: language}}]};
  const ctx = {
    get: service => service === 'settings' ? settings : undefined,
    sessionTitle: {register: value => {registered = value;}},
    llm: {async prepareCall(config) {return {config, async *stream(request) {
      requests.push(request);
      yield {type: 'block-start', index: 0, blockType: 'text'};
      yield {type: 'text-delta', index: 0, text: language === 'zh' ? '虚构旅行安排' : 'Fictional travel plans'};
      yield {type: 'block-end', index: 0, block: {type: 'text', text: language === 'zh' ? '虚构旅行安排' : 'Fictional travel plans'}};
      yield {type: 'finish', reason: {kind: 'stop'}};
    }};}},
  };
  provider.apply(ctx, {targetWords: 6, targetCjkCharacters: 12, maxInputBytes: 16384, maxOutputTokens: 128, timeoutMs: 5000});
  const request = {messages: [{seq: 1, text: '请安排一次虚构旅行。'}],
    currentTitle: {source: {kind: 'provider'}, title: '虚构旅行安排'},
    route: {provider: 'fixture', model: 'fixture'}, session: {id: 'fixture', append: (...args) => events.push(args)}, signal: new AbortController().signal};
  for (const next of ['zh', 'en', 'zh']) {
    language = next;
    const result = await registered.generate(request);
    assert.equal(result.title, language === 'zh' ? '虚构旅行安排' : 'Fictional travel plans');
    const sent = requests.at(-1);
    assert.ok(sent.messages[0].content[0].text.includes('请安排一次虚构旅行。'), 'source data stays literal');
    assert.ok(sent.system.includes(language === 'zh' ? '标题使用简体中文' : 'Write the title in English'));
    assert.ok(!sent.system.includes('Use the language of the messages.'));
    assert.ok(!sent.system.includes('AI coding-assistant'));
    if (name === 'all-prompts') assert.ok(sent.system.includes('already in the required language'), 'old-title stability cannot force the wrong language');
  }
  assert.equal(requests.length, 3, 'one title call per requested generation, without retries');
  assert.equal(events.length, 3, 'actual auxiliary request recording remains intact');
  assert.throws(() => patchSessionTitleProvider(source.replace('Use the language of the messages.', 'Unexpected upstream change.')), /assembly changed/);
});
