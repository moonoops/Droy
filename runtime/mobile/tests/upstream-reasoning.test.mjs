import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';

// Exercise the assembled upstream component so Android patches cannot silently
// discard the new display/raw-text contract during an engine upgrade.
const source = await readFile(new URL('../../generated/dsh/node_modules/@deepseek-ai/dsh-client-ui-chat/lib/client.js', import.meta.url), 'utf8');
function region(marker) {
  const start = source.indexOf(marker);
  assert.ok(start >= 0, marker);
  return source.slice(start, source.indexOf('//#endregion', start));
}
const jsx = (type, props) => ({ type, props });
const react = { memo: fn => fn, useMemo: fn => fn() };
const primitives = { IconThinkOutlineRegular: 'think', TextShimmer: 'shimmer', DisclosureRow: 'disclosure' };
const { reasoningText, ReasoningRow } = new Function('react', 'react_jsx_runtime', '_deepseek_ai_dsh_client_ui_primitives',
  'ReasoningRow_module_css_default', 'accessibility_module_css_default',
  `${region('function reasoningText(')}\n${region('const THINK_ICON =')}\nreturn {reasoningText,ReasoningRow};`
)(react, { jsx, jsxs: jsx, Fragment: 'fragment' }, primitives, {}, {});

test('packaged reasoning strips only a leading signature, including streamed prefixes', () => {
  const opening = '<signature>';
  for (let end = 1; end <= opening.length; end++) assert.equal(reasoningText(opening.slice(0, end)), '');
  for (const value of ['', '<signature>fixture', '<signature>fixture</signature>']) assert.equal(reasoningText(value), '');
  for (const text of ['Planning next step', '检查下一步', '**Plan**\n\nContinue']) {
    assert.equal(reasoningText(`<signature>fixture</signature>\r\n\n${text}`), text);
    assert.equal(reasoningText(text), text);
    assert.equal(reasoningText(`${text} <signature>literal</signature>`), `${text} <signature>literal</signature>`);
  }
  assert.equal(reasoningText('<signature>x</signature>\rtext'), '\rtext');
  assert.equal(reasoningText('<signpost>literal'), '<signpost>literal');
});

test('packaged row hides signature-only content and preserves rawText in the expanded slot', () => {
  let expanded = false;
  const slots = [];
  const render = (text, running = true) => ReasoningRow({ text, running,
    usePresentation: selector => selector({ settledReasoningPreview: true }),
    useDisclosure: () => ({ expanded, toggle: () => { expanded = !expanded; } }),
    renderSlot: (name, props) => { slots.push({ name, props }); return { type: 'body', props }; }, t: key => key });
  for (const text of ['<sig', '<signature>fixture', '<signature>x</signature>\n  ']) assert.equal(render(text), null);
  const rawText = '<signature>fixture</signature>\r\n**Visible plan**\n\nNext step';
  let row = render(rawText);
  assert.equal(row.props['data-state'], 'running');
  assert.ok(!JSON.stringify(row).includes('fixture'));
  assert.equal(slots.length, 0);
  row.props.children[1].props.onToggle();
  row = render(rawText, false);
  assert.equal(row.props['data-expanded'], true);
  assert.equal(row.props['data-state'], 'ok');
  assert.deepEqual(slots.at(-1), { name: 'conversation.chat.reasoning.body', props: {
    rawText, text: '**Visible plan**\n\nNext step', running: false } });
  row.props.children[1].props.onToggle();
  assert.equal(render(rawText, false).props['data-expanded'], undefined);
});

test('translation wrapper uses display text for requests and original-view rendering', async () => {
  const translationSource = await readFile(new URL('../../generated/dsh/node_modules/@deepseek-ai/dsh-experimental-client-ui-cot-translation/lib/client.js', import.meta.url), 'utf8');
  const start = translationSource.indexOf('function TranslationBody(props)');
  assert.ok(start >= 0);
  const component = translationSource.slice(start, translationSource.indexOf('//#endregion', start));
  const updates = [], effects = [], slots = [], state = [];
  let cursor = 0;
  const hooks = { useMemo: fn => fn(), useRef: () => ({ current: undefined }), useEffect: fn => effects.push(fn),
    useState: initial => { const index = cursor++; if (!(index in state)) state[index] = initial;
      return [state[index], value => { state[index] = value; }]; } };
  class TranslationFixture {
    constructor() {}
    update(text, running) { updates.push({ text, running }); }
    dispose() {}
  }
  const Body = new Function('react', 'react_jsx_runtime', 'ReasoningTranslation', 'Translation_module_css_default',
    '_deepseek_ai_dsh_client_ui_primitives', `${component}; return TranslationBody;`)(
    hooks, { jsx, jsxs: jsx, Fragment: 'fragment' }, TranslationFixture, {}, { Button: 'button' });
  const rawText = '<signature>fixture</signature>\nVisible thought';
  const render = () => { cursor = 0; effects.length = 0; return Body({ rawText, text: reasoningText(rawText), running: false,
    usePreferences: select => select({ targetLanguage: 'auto', provider: 'fixture' }),
    useTranslationLocale: select => select({ active: 'zh' }), useTranslationLimit: select => select(1000),
    translate: () => { throw new Error('Network translation is outside this component contract test'); }, t: key => key,
    renderFactorySlot: (name, props) => { slots.push({ name, props }); return { type: 'content', props }; } }); };
  let tree = render();
  const cleanup = effects.map(fn => fn()).filter(Boolean);
  assert.deepEqual(updates, [{ text: 'Visible thought', running: false }]);
  tree.props.children[0].props.children[0].props.onClick();
  tree = render();
  assert.equal(tree.props['data-translation-view'], 'original');
  assert.equal(slots.at(-1).props.text, 'Visible thought');
  assert.ok(!JSON.stringify(tree).includes('fixture'));
  cleanup.forEach(fn => fn());
});
