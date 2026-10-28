import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import vm from 'node:vm';

const activity = await readFile(new URL('../../../app/src/main/java/io/droyagent/app/MainActivity.java', import.meta.url), 'utf8');
const css = await readFile(new URL('../../../app/src/main/assets/mobile.css', import.meta.url), 'utf8');
const start = activity.indexOf('window.__droyApplyWindowInsets = (pixels) => {');
const end = activity.indexOf('\n                const update =', start);
assert.ok(start >= 0 && end > start, 'The Android inset bridge must remain available to the mobile shell');

function insets({ width = 1080, cssWidth = 360, density = 3, devicePixelRatio = 3, ime = 900 } = {}) {
  const properties = new Map(), attributes = new Map();
  const root = { clientWidth: cssWidth, style: { setProperty: (key, value) => properties.set(key, value) },
    setAttribute: (key, value) => attributes.set(key, value) };
  const window = { innerWidth: cssWidth, devicePixelRatio };
  vm.runInNewContext(activity.slice(start, end), { window, document: { documentElement: root } });
  window.__droyApplyWindowInsets({ width, density, top: 72, right: 0, bottom: 72, left: 0, ime, ready: true,
    longPressMs: 500, touchSlop: 24, systemGestureLeft: 54, systemGestureRight: 54 });
  return { properties, attributes, window };
}

test('Android insets and common chrome share the measured CSS pixel scale, including viewport changes', () => {
  for (const cssWidth of [320, 360, 412]) {
    const { properties, window } = insets({ cssWidth });
    const ratio = cssWidth / 1080;
    assert.equal(properties.get('--droy-safe-top'), (72 * ratio) + 'px');
    assert.equal(properties.get('--droy-ime-bottom'), (900 * ratio) + 'px');
    assert.equal(properties.get('--droy-touch-target'), (48 * 3 * ratio) + 'px');
    assert.equal(properties.get('--droy-header-height'), (56 * 3 * ratio) + 'px');
    assert.equal(properties.get('--droy-header-font-size'), (18 * 3 * ratio) + 'px');
    assert.equal(window.__droyTouchConfig.dpToCss, 3 * ratio);
  }
});

test('keyboard closing removes only the IME inset while retaining the system navigation inset', () => {
  const { properties, attributes, window } = insets();
  assert.equal(attributes.get('data-droy-ime-visible'), 'true');
  window.__droyApplyWindowInsets({ ...window.__droyWindowInsets, ime: 0 });
  assert.equal(properties.get('--droy-ime-bottom'), '0px');
  assert.equal(attributes.get('data-droy-ime-visible'), 'false');
  assert.equal(properties.get('--droy-safe-bottom'), '24px');
  assert.match(css, /--droy-content-bottom:\s*max\(var\(--droy-safe-bottom, 0px\), var\(--droy-ime-bottom, 0px\)\)/);
  assert.match(activity, /SOFT_INPUT_ADJUST_NOTHING/);
});

test('selection scrolling is confined to real content scrollers and does not make the application shell scrollable', () => {
  const rules = [...css.matchAll(/([^{}]+)\{([^{}]*)\}/g)];
  for (const marker of ['#root', '[data-droy-frame]', '[data-droy-center]', '.wSkVaW_root', '[data-conversation-content]']) {
    assert.ok(rules.some(([, selector, declarations]) => selector.includes(marker) && /overflow:\s*clip\s*;/.test(declarations)),
      marker + ' must reject programmatic ancestor scrolling rather than merely hide its scrollbar');
  }
  assert.ok(rules.some(([, selector, declarations]) => selector.includes('[data-conversation-scroll]') && /overscroll-behavior:\s*contain/.test(declarations)));
  assert.ok(rules.some(([, selector, declarations]) => selector.includes('body[data-droy-work-active] [data-droy-center]') &&
    /padding-top:\s*calc\(var\(--droy-safe-top, 0px\) \+ var\(--droy-header-height\)\)/.test(declarations)));
});
