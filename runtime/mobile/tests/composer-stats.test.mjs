import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { patchComposerStats } from '../../../tools/mobile_ui_patch.mjs';

const source = await readFile(new URL('../../dsh/node_modules/@deepseek-ai/dsh-client-ui-chat/lib/client.js', import.meta.url), 'utf8');
const patched = patchComposerStats(source);
function section(text, start, end) {
  const offset = text.indexOf(start);
  assert.ok(offset >= 0, start);
  const limit = text.indexOf(end, offset);
  assert.ok(limit > offset, end);
  return text.slice(offset, limit);
}
const dictionaries = new Function(`${section(source, 'const zh = {', '\n\t\t//#endregion')};return {zh,en};`)();
const jsx = (type, props) => ({ type, props });
const primitives = new Proxy({}, { get: (_, name) => name });
const components = new Function('react', 'react_jsx_runtime', '_deepseek_ai_dsh_client_ui_primitives',
  `const stat_dialog_module_css_default = {};
   ${section(patched, 'function formatTokensPerSecond(', '\n\t\t/**')}
   ${section(patched, 'function formatTokens(', '\n\t\t//#endregion')}
   ${section(patched, 'function deriveStats(', '\n\t\t//#endregion')}
   return {ActivityPill,UsagePill};`)(
  { memo: component => component, useMemo: compute => compute() }, { jsx, jsxs: jsx, Fragment: 'fragment' }, primitives);

for (const locale of ['zh', 'en']) for (const mode of ['full', 'compact']) {
  test(`${locale}/${mode}: composer shows only speed and token total while keeping dialog details`, () => {
    const t = (key, params = {}) => dictionaries[locale][key].replace(/\{(\w+)\}/g, (_, name) => params[name]);
    const stats = { turns: 12, steps: 34, decodeMs: 2000, decodeTokens: 100, llmMs: 3000, toolMs: 1000, ttftMs: 500, ttftSteps: 1 };
    const usage = { uncachedInputTokens: 200, cacheReadTokens: 600, cacheWriteTokens: 0, outputTokens: 100 };
    const props = { useChat: selector => selector({ legacy: { nodes: [] } }),
      useProjection: key => ({ sessionStats: stats, tokenUsage: usage })[key], usePerformanceUsage: read => read(mode), t };
    const activity = components.ActivityPill(props);
    const tokens = components.UsagePill(props);
    assert.equal(activity.props.label, '50 tps');
    assert.equal(tokens.props.label, '900 tok');
    if (mode === 'full') {
      assert.match(activity.props.ariaLabel, /12.*34.*50 tps/);
      assert.match(tokens.props.ariaLabel, /75%/);
      assert.ok(activity.props.children);
      assert.ok(tokens.props.children);
    }
    stats.decodeMs = 0;
    assert.equal(components.ActivityPill(props), null);
    usage.uncachedInputTokens = usage.cacheReadTokens = usage.outputTokens = 0;
    assert.equal(components.UsagePill(props), null);
  });
}

test('patch leaves context indicator and unrelated chat code unchanged and rejects duplicate application', () => {
  const before = source.slice(0, source.indexOf('function deriveStats('));
  const after = patched.slice(0, patched.indexOf('function deriveStats('));
  assert.equal(after.replace('return `${formatTokensPerSecond(stats.decodeTokens / (stats.decodeMs / 1e3))} tps`;',
    'return t("message.tokensPerSecond", { tps: formatTokensPerSecond(stats.decodeTokens / (stats.decodeMs / 1e3)) });'), before);
  assert.equal(patched.slice(patched.indexOf('//#region lib/types/client/conversation-nodes/common.js')),
    source.slice(source.indexOf('//#region lib/types/client/conversation-nodes/common.js')));
  assert.throws(() => patchComposerStats(patched), /anchor/);
});
