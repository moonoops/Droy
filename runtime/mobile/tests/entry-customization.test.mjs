import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { runInNewContext } from 'node:vm';
import { patchEntryCustomizationCopy } from '../../../tools/mobile_brand_patch.mjs';

const source = await readFile(new URL('../../dsh/node_modules/@deepseek-ai/dsh-client-ui-entry-customization/lib/client.js', import.meta.url), 'utf8');

test('all More menus create unsent localized creator drafts with append semantics', () => {
  let plugin, dictionaries, language = 'en', browsed = 0;
  const entries = [], drafts = [];
  runInNewContext(patchEntryCustomizationCopy(source), {
    window: { __ModuleLoader__: { load: definition => { plugin = definition.factory(() => ({})); } } },
  });
  plugin.apply({
    effect: effect => effect(),
    get: () => undefined,
    locale: {
      register: (_namespace, values) => { dictionaries = values; },
      bind: () => key => dictionaries[language][key],
    },
    slots: { inject: (_slot, register) => register(), register: entry => entries.push(entry) },
    pluginNavigation: { openList: () => browsed++ },
    uiWorkspace: { startSession: (workspace, options) => drafts.push({ workspace, ...options }) },
  });
  assert.equal(entries.length, 3);
  assert.deepEqual(Object.keys(dictionaries.en).sort(), Object.keys(dictionaries.zh).sort());
  for (language of ['en', 'zh']) {
    for (const entry of entries) {
      const actions = entry.inject();
      actions.browsePlugins();
      actions.createPlugin();
      const draft = drafts.at(-1);
      assert.equal(draft.workspace, undefined);
      assert.equal(draft.agentPreset, 'cordis');
      assert.equal(draft.whenDraftExists, 'append');
      assert.equal(draft.clearPreviousDraft, undefined);
      assert.match(draft.prompt, /Droy/);
      assert.equal(/\p{Script=Han}/u.test(draft.prompt), language === 'zh');
      assert.equal(draft.prompt, dictionaries[language][`createPrompt.${actions.region}`]);
    }
  }
  assert.equal(browsed, 6);
  assert.equal(new Set(drafts.map(draft => draft.prompt)).size, 6);
});
