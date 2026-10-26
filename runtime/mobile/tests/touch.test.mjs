import test from 'node:test';
import assert from 'node:assert/strict';
import vm from 'node:vm';
import { readFile } from 'node:fs/promises';

const source = await readFile(new URL('../touch.js', import.meta.url), 'utf8');
const mobileStyles = await readFile(new URL('../../../app/src/main/assets/mobile.css', import.meta.url), 'utf8');
const create = vm.runInNewContext(`${source};createTouchController`);

function fixture() {
  let timestamp = 0, sequence = 0, drawer = true, modal = false, observer;
  const timers = new Map(), events = new Map(), nativeEvents = new Map();
  const calls = [];
  function element(attributes = {}, parent = null, width = 320) {
    const attrs = new Map(Object.entries(attributes));
    const styles = new Map();
    const handlers = new Map(), handlerOptions = new Map();
    const node = { parent, isConnected: true, inert: false, attrs, styles, handlers, handlerOptions,
      addEventListener(type, handler, options) { handlers.set(type, handler); handlerOptions.set(type, options); },
      removeEventListener(type) { handlers.delete(type); handlerOptions.delete(type); },
      style: { setProperty: (name, value) => styles.set(name, value), removeProperty: name => styles.delete(name) },
      setAttribute: (name, value) => attrs.set(name, value), removeAttribute: name => attrs.delete(name), hasAttribute: name => attrs.has(name),
      getAttribute: name => attrs.get(name) ?? null,
      contains(child) { for (let current = child; current; current = current.parent) if (current === node) return true; return false; },
      getClientRects: () => [{}], getBoundingClientRect: () => ({ width }),
      closest(selector) {
        const matches = selector.split(',').some(part => {
          const exact = part.trim().match(/^\[([^=\]]+)(?:="([^"]*)")?\]$/);
          return exact ? attrs.has(exact[1]) && (exact[2] === undefined || attrs.get(exact[1]) === exact[2])
            : part.trim() === node.tag;
        });
        return matches ? node : parent?.closest(selector) || null;
      },
      querySelector: () => null,
      focus() { doc.activeElement = node; calls.push('focus:' + (node.name || 'node')); },
      blur() { doc.activeElement = doc.body; calls.push('blur:' + (node.name || 'node')); },
      setPointerCapture(id) { node.captured = id; }, hasPointerCapture: id => node.captured === id,
      releasePointerCapture() { node.captured = null; }, remove() { node.isConnected = false; }
    };
    return node;
  }
  const root = element(), sidebar = element({ 'data-droy-sidebar': '' }), backdrop = element({ 'data-droy-backdrop': '' });
  const body = element();
  body.appendChild = node => { body.lastChild = node; };
  const doc = { body, documentElement: root, visibilityState: 'visible',
    querySelector: selector => selector === '[data-droy-frame]' ? root : selector === '[data-droy-sidebar]' ? sidebar
      : selector === '[data-droy-backdrop]' ? backdrop : null,
    querySelectorAll: () => [],
    createElement: () => element(),
    addEventListener(type, handler) { events.set(type, handler); }, removeEventListener(type) { events.delete(type); }
  };
  const target = { document: doc, performance: { now: () => timestamp },
    setTimeout(callback, ms) { const id = ++sequence; timers.set(id, { callback, at: timestamp + ms }); return id; },
    clearTimeout: id => timers.delete(id),
    addEventListener: (type, handler) => nativeEvents.set(type, handler), removeEventListener: type => nativeEvents.delete(type),
    MutationObserver: class { constructor(callback) { observer = callback; } observe() {} disconnect() { observer = null; } }
  };
  const controller = create(target, { isDrawerOpen: () => drawer, hasModal: () => modal, closeDrawer() { drawer = false; calls.push('drawer'); } });
  const event = (type, node, x = 150, y = 100, extra = {}) => {
    const value = { pointerType: 'touch', pointerId: 1, isPrimary: true, target: node, clientX: x, clientY: y, detail: 1,
      preventDefault() { value.prevented = true; }, stopImmediatePropagation() { value.stopped = true; }, ...extra };
    events.get(type)?.(value);
    return value;
  };
  const row = (id = 'one', extra = {}) => {
    const node = element({ 'data-droy-session-row': id }, sidebar);
    const surface = element({ 'data-droy-session-surface': '' }, node);
    const action = element({ 'data-droy-swipe-action': '' }, node, 72);
    node.querySelector = selector => selector === '[data-droy-swipe-action]' ? action : null;
    let menuOpen = false;
    const unregister = controller.bindSessionRow(node, { sessionId: id, openMenu() { menuOpen = true; calls.push('menu:' + id); },
      closeMenu() { menuOpen = false; }, isMenuOpen: () => menuOpen, ...extra });
    return { node, surface, action, unregister, get menuOpen() { return menuOpen; } };
  };
  return { target, doc, controller, calls, event, row, sidebar, backdrop, root, timers, events, nativeEvents, element,
    set modal(value) { modal = value; }, set drawer(value) { drawer = value; },
    advance(ms) { timestamp += ms; const due = [...timers].filter(([, timer]) => timer.at <= timestamp); for (const [id, timer] of due) { timers.delete(id); timer.callback(); } },
    changed() { observer?.(); }
  };
}

function swipe(f, node, start = 170, end = 80) {
  f.event('pointerdown', node, start);
  f.advance(120);
  f.event('pointermove', node, end);
  f.advance(120);
  f.event('pointerup', node, end);
}

test('selection suppression stays inside mobile sidebar chrome and preserves nested editing', () => {
  const rules = [...mobileStyles.replace(/\/\*[\s\S]*?\*\//g, '').matchAll(/([^{}]+)\{([^{}]*)\}/g)]
    .map(([, selector, declarations]) => ({ selector, declarations }));
  const suppress = rules.filter(rule => /(?:^|;)\s*(?:-webkit-)?user-select\s*:\s*none\b/.test(rule.declarations));
  assert.ok(suppress.length > 0, 'Touch rows and drawer headers must explicitly suppress native word selection');
  for (const rule of suppress) {
    for (const selector of rule.selector.trim().split(',')) {
      assert.match(selector.trim(), /^html\[data-droy-mobile\] \[data-droy-(?:session-row|drawer-header)\]$/,
        'Conversation text must not inherit a global no-selection rule');
    }
    assert.match(rule.declarations, /-webkit-user-select\s*:\s*none/);
    assert.match(rule.declarations, /(?:^|;)\s*user-select\s*:\s*none/);
  }
  for (const marker of ['data-droy-session-row', 'data-droy-drawer-header']) {
    assert.ok(suppress.some(rule => rule.selector.includes(`[${marker}]`)));
    const editing = rules.find(rule => rule.selector.includes(`[${marker}] :is(input, textarea, [contenteditable]:not([contenteditable="false"]))`));
    assert.ok(editing, `${marker} must preserve native editing inside its fields`);
    assert.match(editing.declarations, /-webkit-user-select\s*:\s*text/);
    assert.match(editing.declarations, /(?:^|;)\s*user-select\s*:\s*text/);
  }
});

test('tap is untouched, long press opens one original menu and consumes release plus click', () => {
  const f = fixture(), row = f.row();
  f.event('pointerdown', row.surface); f.event('pointerup', row.surface);
  assert.equal(f.event('click', row.surface).prevented, undefined);
  f.event('pointerdown', row.surface); f.advance(500);
  assert.deepEqual(f.calls, ['menu:one']);
  const menuItem = f.element({ role: 'menuitem' });
  assert.equal(f.event('pointerup', menuItem).prevented, true);
  assert.equal(f.event('click', menuItem).prevented, true);
  assert.equal(row.node.hasAttribute('data-droy-row-expanded'), false);
  // The next real touch deliberately selecting a menu action remains usable.
  f.event('pointerdown', menuItem); f.event('pointerup', menuItem);
  assert.equal(f.event('click', menuItem).prevented, undefined);
  f.controller.dispose();
});

test('long press reads the current system duration and movement slop on every gesture', () => {
  const f = fixture(), row = f.row();
  f.target.__droyTouchConfig = { longPressMs: 900, touchSlopCssPx: 12 };
  f.event('pointerdown', row.surface); f.advance(500);
  assert.equal(row.menuOpen, false);
  f.event('pointermove', row.surface, 156, 104); f.advance(400);
  assert.equal(row.menuOpen, true);
  f.event('pointerup', row.surface);
  f.target.__droyTouchConfig.longPressMs = 300;
  f.event('pointerdown', row.surface); f.advance(300);
  assert.deepEqual(f.calls, ['menu:one', 'menu:one']);
  f.controller.dispose();
});

test('vertical scrolling cancels long press without preventing native scroll', () => {
  const f = fixture(), row = f.row();
  f.event('pointerdown', row.surface);
  const move = f.event('pointermove', row.surface, 154, 135);
  f.advance(1000);
  assert.equal(move.prevented, undefined);
  assert.equal(row.menuOpen, false);
  assert.equal(row.node.hasAttribute('data-droy-row-expanded'), false);
  f.controller.dispose();
});

test('native touchmove cancellation is limited to a locked horizontal sidebar gesture', () => {
  const f = fixture(), row = f.row();
  const move = node => {
    const event = { preventDefault() { event.prevented = true; }, stopPropagation() { event.stopped = true; } };
    node.handlers.get('touchmove')?.(event);
    return event;
  };
  assert.equal(f.events.has('touchmove'), false, 'Conversation scrolling must not acquire a document-wide nonpassive listener');
  assert.equal(f.sidebar.handlerOptions.get('touchmove').passive, false);
  assert.equal(move(f.doc.body).prevented, undefined);
  assert.equal(move(f.sidebar).prevented, undefined);
  f.event('pointerdown', row.surface);
  f.event('pointermove', row.surface, 148, 101);
  assert.equal(move(f.sidebar).prevented, undefined, 'Movement below slop must remain native');
  f.event('pointermove', row.surface, 80, 101);
  const horizontal = move(f.sidebar);
  assert.equal(horizontal.prevented, true);
  assert.equal(horizontal.stopped, undefined, 'Native touchmove must keep propagating');
  f.event('pointerup', row.surface, 80, 101);
  assert.equal(move(f.sidebar).prevented, undefined, 'Releasing a gesture must restore native touch handling');
  f.event('pointerdown', row.surface);
  f.event('pointermove', row.surface, 153, 150);
  assert.equal(move(f.sidebar).prevented, undefined, 'Vertical scrolling must not be cancelled');
  f.event('pointerup', row.surface, 153, 150);
  f.event('pointerdown', f.backdrop, 260);
  f.event('pointermove', f.backdrop, 130);
  assert.equal(move(f.backdrop).prevented, true, 'Backdrop drag uses the same narrow native touch policy');
  f.controller.dispose();
  assert.equal(f.sidebar.handlers.has('touchmove'), false);
  assert.equal(f.backdrop.handlers.has('touchmove'), false);
});

test('native touchmove listeners follow drawer mount and removal without observing the conversation', () => {
  const f = fixture();
  f.sidebar.isConnected = false;
  f.changed();
  assert.equal(f.sidebar.handlers.has('touchmove'), false);
  f.sidebar.isConnected = true;
  f.changed();
  assert.equal(f.sidebar.handlers.has('touchmove'), true);
  assert.equal(f.doc.body.handlers.has('touchmove'), false);
  f.controller.dispose();
});

test('left and right system edge cancellation preserves the revealed row for a single Back', () => {
  for (const side of ['left', 'right']) for (const useClientWidth of [false, true]) {
    const f = fixture(), row = f.row();
    if (useClientWidth) f.root.clientWidth = 400;
    else f.target.innerWidth = 400;
    f.target.__droyTouchConfig = { systemGestureLeftCssPx: 24, systemGestureRightCssPx: 24 };
    swipe(f, row.surface);
    const target = side === 'left' ? f.sidebar : f.backdrop;
    const x = side === 'left' ? 1 : 399;
    const down = f.event('pointerdown', target, x);
    assert.equal(down.prevented, undefined);
    assert.equal(down.stopped, undefined);
    assert.equal(row.action.inert, false, 'System Back must not close a layer on pointerdown');
    const move = f.event('pointermove', target, side === 'left' ? 80 : 320);
    assert.equal(move.prevented, undefined);
    assert.equal(move.stopped, undefined);
    f.event('pointercancel', target, x);
    f.advance(900);
    assert.equal(row.action.inert, false, 'System pointer cancellation must preserve the revealed action');
    assert.equal(f.controller.closeExpandedRow(), true, 'The later Back callback must still have exactly one row layer to close');
    assert.deepEqual(f.calls, [], 'The drawer must remain for a later Back');
    swipe(f, row.surface);
    assert.equal(row.action.inert, false, 'Interior gestures must keep working after a cancelled system sequence');
    f.controller.dispose();
  }
});

test('plain edge taps keep original clicks and absent system insets preserve existing gestures', () => {
  const f = fixture(), row = f.row();
  f.target.innerWidth = 400;
  f.target.__droyTouchConfig = { systemGestureLeftCssPx: 24, systemGestureRightCssPx: 24 };
  swipe(f, row.surface);
  const button = f.element({}, f.sidebar); button.tag = 'button';
  f.event('pointerdown', button, 2);
  const up = f.event('pointerup', button, 2);
  assert.equal(up.prevented, undefined);
  assert.equal(up.stopped, undefined);
  assert.equal(f.event('click', button, 2).prevented, undefined);
  assert.equal(row.action.inert, false);
  delete f.target.__droyTouchConfig;
  swipe(f, f.backdrop, 399, 200);
  assert.deepEqual(f.calls, ['drawer'], 'Missing native system gesture insets must default to zero');
  f.controller.dispose();
});

test('horizontal swiping reveals only the real action and never invokes archive or drawer', () => {
  const f = fixture(), row = f.row();
  assert.equal(row.action.inert, true);
  swipe(f, row.surface);
  assert.equal(row.node.attrs.get('data-droy-row-expanded'), 'true');
  assert.equal(row.action.inert, false);
  assert.deepEqual(f.calls, []);
  assert.equal(f.event('click', row.surface).prevented, true);
  swipe(f, row.surface, 90, 175);
  assert.equal(row.node.hasAttribute('data-droy-row-expanded'), false);
  assert.equal(row.action.inert, true);
  f.controller.dispose();
});

test('one row is expanded, first tap closes it, and tapping the exposed action stays usable', () => {
  const f = fixture(), one = f.row('one'), two = f.row('two');
  swipe(f, one.surface); swipe(f, two.surface);
  assert.equal(one.action.inert, true); assert.equal(two.action.inert, false);
  const button = f.element({}, two.action); button.tag = 'button';
  f.event('pointerdown', button); f.event('pointerup', button);
  assert.equal(f.event('click', button).prevented, undefined);
  f.event('pointerdown', two.surface);
  assert.equal(f.event('pointerup', two.surface).prevented, true);
  assert.equal(two.action.inert, true);
  assert.equal(f.event('click', two.surface).prevented, true);
  f.controller.dispose();
});

test('blank and archived rows never expose an archive shortcut', () => {
  for (const props of [{ blank: true }, { archived: true }]) {
    const f = fixture(), row = f.row('special', props);
    swipe(f, row.surface);
    assert.equal(row.action.inert, true);
    assert.deepEqual(f.calls, []);
    f.controller.dispose();
  }
  const f = fixture();
  const unregistered = f.element({ 'data-droy-session-row': 'blank' }, f.sidebar);
  swipe(f, unregistered);
  assert.deepEqual(f.calls, []);
  f.controller.dispose();
});

test('a fast short twitch snaps back instead of closing drawer or exposing a row', () => {
  const f = fixture(), row = f.row();
  for (const target of [f.sidebar, row.surface]) {
    f.event('pointerdown', target, 160);
    f.advance(1); f.event('pointermove', target, 148);
    f.advance(1); f.event('pointerup', target, 148);
  }
  assert.deepEqual(f.calls, []);
  assert.equal(row.action.inert, true);
  f.controller.dispose();
});

test('drawer blank and its button backdrop track a left drag, short drag snaps back', () => {
  const f = fixture();
  f.backdrop.tag = 'button';
  f.event('pointerdown', f.sidebar, 210); f.advance(120);
  f.event('pointermove', f.sidebar, 190);
  assert.equal(f.sidebar.styles.get('transform'), 'translateX(-20px)');
  f.advance(120); f.event('pointerup', f.sidebar, 190);
  assert.deepEqual(f.calls, []);
  assert.equal(f.sidebar.styles.has('transform'), false);
  swipe(f, f.backdrop, 260, 100);
  assert.deepEqual(f.calls, ['drawer']);
  assert.equal(f.event('click', f.backdrop).prevented, true);
  f.controller.dispose();
});

test('header buttons keep ordinary taps but intentional horizontal drags close the drawer', () => {
  const f = fixture();
  const header = f.element({ 'data-droy-drawer-header': '' }, f.sidebar);
  const button = f.element({}, header); button.tag = 'button';
  f.event('pointerdown', button);
  assert.equal(f.event('pointerup', button).prevented, undefined);
  assert.equal(f.event('click', button).prevented, undefined);
  assert.deepEqual(f.calls, []);
  swipe(f, button, 260, 100);
  assert.deepEqual(f.calls, ['drawer']);
  assert.equal(f.event('click', button).prevented, true);
  f.controller.dispose();
});

test('editable header fields and buttons outside the header retain their own gestures', () => {
  for (const tag of ['input', 'textarea', 'select']) {
    const f = fixture();
    const header = f.element({ 'data-droy-drawer-header': '' }, f.sidebar);
    const field = f.element({}, header); field.tag = tag;
    swipe(f, field, 260, 100);
    assert.deepEqual(f.calls, []);
    f.controller.dispose();
  }
  const f = fixture(), button = f.element({}, f.sidebar); button.tag = 'button';
  swipe(f, button, 260, 100);
  assert.deepEqual(f.calls, []);
  f.controller.dispose();
});

test('menus, mouse, editable controls and system edge outside drawer do not start gestures', () => {
  const f = fixture(), row = f.row();
  f.modal = true; swipe(f, row.surface); assert.equal(row.action.inert, true);
  f.modal = false;
  f.event('pointerdown', row.surface, 160, 100, { pointerType: 'mouse' }); f.advance(900);
  assert.equal(row.menuOpen, false);
  const input = f.element({}, row.node); input.tag = 'input';
  swipe(f, input); assert.equal(row.action.inert, true);
  swipe(f, f.doc.body, 0, 100); assert.deepEqual(f.calls, []);
  f.controller.dispose();
});

test('multitouch, pointer cancellation, scroll and page/workspace exit clear gestures', () => {
  for (const cancel of [
    f => f.event('pointerdown', f.sidebar, 100, 100, { pointerId: 2, isPrimary: false }),
    (f, row) => f.event('pointercancel', row.surface),
    (f, row) => f.event('scroll', row.node),
    f => { f.doc.visibilityState = 'hidden'; f.events.get('visibilitychange')(); },
    f => f.nativeEvents.get('pagehide')(),
    f => f.nativeEvents.get('droy-mobile-workspace-change')()
  ]) {
    const f = fixture(), row = f.row();
    f.event('pointerdown', row.surface);
    cancel(f, row); f.advance(900);
    assert.equal(row.menuOpen, false);
    assert.equal(row.action.inert, true);
    f.controller.dispose();
  }
});

test('transferring implicit child capture to a row or drawer does not cancel its swipe', () => {
  for (const kind of ['row', 'drawer']) {
    const f = fixture(), row = f.row();
    const child = kind === 'row' ? row.surface : f.element({ 'data-droy-drawer-header': '' }, f.sidebar);
    const owner = kind === 'row' ? row.node : f.sidebar;
    owner.setPointerCapture = id => {
      owner.captured = id;
      f.event('lostpointercapture', child, 0, 0, { pointerId: id });
    };
    swipe(f, child, 270, 100);
    if (kind === 'row') {
      assert.equal(row.node.attrs.get('data-droy-row-expanded'), 'true');
      assert.equal(row.action.inert, false);
      assert.deepEqual(f.calls, []);
    } else assert.deepEqual(f.calls, ['drawer']);
    f.controller.dispose();
  }
});

test('losing the explicit capture owner cancels and resets a partially dragged row', () => {
  const f = fixture(), row = f.row();
  f.event('pointerdown', row.surface, 170);
  f.advance(100); f.event('pointermove', row.surface, 120);
  assert.equal(row.node.attrs.get('data-droy-row-dragging'), 'true');
  row.node.captured = null;
  f.event('lostpointercapture', row.node);
  assert.equal(row.node.hasAttribute('data-droy-row-dragging'), false);
  assert.equal(row.action.inert, true);
  f.event('pointerup', row.surface, 60);
  assert.equal(row.action.inert, true);
  f.controller.dispose();
});

test('irrelevant DOM mutation preserves active gesture; row removal and drawer close cancel it', () => {
  const f = fixture(), row = f.row();
  f.event('pointerdown', row.surface); f.advance(300); f.changed(); f.advance(200);
  assert.equal(row.menuOpen, true);
  f.event('pointerup', row.surface);
  f.event('pointerdown', row.surface); row.node.isConnected = false; f.changed(); f.advance(500);
  assert.deepEqual(f.calls, ['menu:one']);
  const two = f.row('two'); f.event('pointerdown', two.surface); f.drawer = false; f.changed(); f.advance(500);
  assert.equal(two.menuOpen, false);
  f.controller.dispose();
});

test('pending action blocks duplicate gesture, confirmed phase resets row, failed recovery is visible', () => {
  const f = fixture(), row = f.row();
  const action = phase => f.nativeEvents.get('droy-mobile-session-action')({ detail: { sessionId: 'one', action: 'unarchive', phase } });
  swipe(f, row.surface); action('pending');
  f.event('pointerdown', row.surface); f.advance(700);
  assert.equal(row.menuOpen, false);
  assert.equal(row.node.attrs.get('aria-busy'), 'true');
  action('failed');
  assert.equal(row.node.hasAttribute('aria-busy'), false);
  assert.match(f.doc.body.lastChild.textContent, /恢复未完成/);
  action('succeeded'); assert.equal(row.action.inert, true);
  f.controller.dispose();
  assert.equal(f.events.size, 0); assert.equal(f.nativeEvents.size, 0);
});

test('pending rows block pointer and keyboard activation; mouse context menu remains native', () => {
  const f = fixture(), row = f.row();
  f.nativeEvents.get('droy-mobile-session-action')({ detail: { sessionId: 'one', action: 'archive', phase: 'pending' } });
  const button = f.element({}, row.action); button.tag = 'button';
  assert.equal(f.event('click', button).prevented, true);
  assert.equal(f.event('keydown', button, 0, 0, { key: 'Enter' }).prevented, true);
  f.event('pointerdown', row.surface, 0, 0, { pointerType: 'mouse' });
  assert.equal(f.event('contextmenu', row.surface).prevented, undefined);
  f.controller.dispose();
});

test('back uses controlled menu dismiss callbacks without manufacturing a pointer action', () => {
  const f = fixture(), row = f.row();
  f.event('pointerdown', row.surface); f.advance(500); f.event('pointerup', row.surface);
  assert.equal(f.controller.closeMenu(), true);
  assert.equal(row.menuOpen, false);
  let inputMenu = true;
  const dispose = f.controller.bindMenu('composer', { isOpen: () => inputMenu, close() { inputMenu = false; } });
  assert.equal(f.controller.closeMenu(), true);
  assert.equal(inputMenu, false);
  assert.equal(f.controller.closeMenu(), false);
  inputMenu = true; dispose();
  assert.equal(f.controller.closeMenu(), false);
  f.controller.dispose();
});

function menuAnimations(f, element) {
  const animations = [];
  f.target.getComputedStyle = () => ({ opacity: '1' });
  element.animate = (frames, options) => {
    const listeners = new Map();
    const animation = { frames, options, listeners, cancelled: false,
      addEventListener: (type, callback) => listeners.set(type, callback),
      removeEventListener: type => listeners.delete(type), cancel() { animation.cancelled = true; },
      finish() { listeners.get('finish')?.(); } };
    animations.push(animation);
    return animation;
  };
  return animations;
}

test('session menu uses a real 150ms exit before unmount and repeated Back cannot close another layer', () => {
  const f = fixture(), menu = f.element();
  const animations = menuAnimations(f, menu);
  let open = true, closed = 0;
  f.controller.bindMenu('session:one', { isOpen: () => open, getElement: () => menu,
    close: () => f.controller.closeSessionMenuAnimated(menu, () => { open = false; closed++; }) });
  f.controller.animateSessionMenu(menu, true);
  assert.equal(animations[0].options.duration, 150);
  assert.equal(menu.attrs.get('data-droy-menu-phase'), 'opening');
  animations[0].finish();
  assert.equal(menu.hasAttribute('data-droy-menu-phase'), false);
  assert.equal(f.controller.closeMenu(menu), true);
  assert.equal(menu.inert, true);
  assert.equal(menu.attrs.get('aria-hidden'), 'true');
  assert.equal(menu.attrs.get('data-droy-menu-phase'), 'closing');
  assert.equal(closed, 0, 'The original Menu must remain mounted until the actual animation finish');
  assert.equal(f.controller.closeMenu(menu), true);
  assert.equal(animations.length, 2, 'A second Back must reuse the pending exit');
  animations[1].finish(); animations[1].finish();
  assert.equal(closed, 1);
  f.controller.dispose();
});

test('reopening a session menu cancels the old exit and its stale completion cannot close the new menu', () => {
  const f = fixture(), menu = f.element();
  menu.setAttribute('aria-hidden', 'false');
  const animations = menuAnimations(f, menu);
  let closed = 0;
  f.controller.closeSessionMenuAnimated(menu, () => closed++);
  const oldFinish = animations[0].listeners.get('finish');
  f.controller.animateSessionMenu(menu, true);
  assert.equal(animations[0].cancelled, true);
  assert.equal(menu.inert, false);
  assert.equal(menu.attrs.get('aria-hidden'), 'false');
  oldFinish();
  assert.equal(closed, 0);
  animations[1].finish();
  assert.equal(menu.hasAttribute('data-droy-menu-phase'), false);
  f.controller.closeSessionMenuAnimated(menu, () => closed++);
  f.controller.dispose();
  animations[2].finish();
  assert.equal(closed, 0, 'Disposed animation callbacks must not commit stale React state');
  assert.equal(menu.inert, false);
});

test('repeated close keeps the latest adapter request token and commits after the single exit', () => {
  const f = fixture(), menu = f.element();
  const animations = menuAnimations(f, menu);
  let open = true, request = 0, commits = 0;
  const requestClose = () => {
    const current = ++request;
    f.controller.closeSessionMenuAnimated(menu, () => {
      if (current !== request) return;
      open = false; commits++;
    });
  };
  requestClose(); requestClose();
  assert.equal(animations.length, 1);
  assert.equal(open, true);
  animations[0].finish();
  assert.equal(open, false, 'The only animation finish must commit the current close request');
  assert.equal(commits, 1);
  // Another Back may arrive between animation finish and React's unmount.
  requestClose();
  assert.equal(commits, 2, 'An already-finished presence must not strand a newer close request');
  f.controller.dispose();
});

test('reduced motion closes immediately without creating a presence animation', () => {
  const f = fixture(), menu = f.element();
  const animations = menuAnimations(f, menu);
  f.target.matchMedia = query => ({ matches: query === '(prefers-reduced-motion: reduce)' });
  let closed = 0;
  f.controller.animateSessionMenu(menu, true);
  f.controller.closeSessionMenuAnimated(menu, () => closed++);
  assert.equal(animations.length, 0);
  assert.equal(closed, 1);
  assert.equal(menu.inert, false);
  f.controller.dispose();
});

test('opening a popup closes the previous one and Back matches the actual top element', () => {
  const f = fixture(), workspace = f.element(), add = f.element(), unknown = f.element();
  let workspaceOpen = true, addOpen = false;
  f.controller.bindMenu('workspace', { isOpen: () => workspaceOpen, getElement: () => workspace,
    close() { workspaceOpen = false; f.calls.push('workspace'); } });
  f.controller.bindMenu('add', { isOpen: () => addOpen, getElement: () => add,
    close() { addOpen = false; f.calls.push('add'); } });
  f.controller.menuOpened('workspace');
  addOpen = true;
  f.controller.menuOpened('add');
  assert.equal(workspaceOpen, false);
  assert.equal(f.controller.closeMenu(unknown), false, 'An unrelated top menu must not dismiss a background registered popup');
  assert.equal(addOpen, true);
  assert.equal(f.controller.closeMenu(add), true);
  assert.deepEqual(f.calls, ['workspace', 'add']);
  f.controller.dispose();
});

test('input mode scopes touch feedback and preserves keyboard focus policy and synthetic Escape', () => {
  const f = fixture();
  assert.equal(f.root.attrs.get('data-droy-input'), 'touch');
  f.event('keydown', f.doc.body, 0, 0, { key: 'Escape', isTrusted: false });
  assert.equal(f.root.attrs.get('data-droy-input'), 'touch');
  f.event('keydown', f.doc.body, 0, 0, { key: 'Tab', isTrusted: true });
  assert.equal(f.root.attrs.get('data-droy-input'), 'keyboard');
  f.event('pointerdown', f.doc.body);
  assert.equal(f.root.attrs.get('data-droy-input'), 'touch');
  f.event('pointerup', f.doc.body);
  f.event('pointerdown', f.doc.body, 0, 0, { pointerType: 'mouse' });
  assert.equal(f.root.attrs.get('data-droy-input'), 'mouse');
  f.controller.dispose();
  assert.equal(f.root.hasAttribute('data-droy-input'), false);
});

test('touch dismissal avoids reopening keyboard while keyboard navigation restores editor focus', () => {
  const f = fixture();
  const input = f.element(); input.tag = 'textarea'; input.name = 'editor';
  const fallback = f.element(); fallback.name = 'menu';
  f.doc.activeElement = input;
  f.controller.restoreFocus(input, fallback);
  assert.deepEqual(f.calls, ['blur:editor', 'focus:menu']);
  f.events.get('keydown')({ key: 'Tab', target: fallback });
  f.controller.restoreFocus(input, fallback);
  assert.deepEqual(f.calls, ['blur:editor', 'focus:menu', 'focus:editor']);
  assert.equal(f.controller.isTouchInteraction(), false);
  f.controller.dispose();
});

test('drawer cleanup preserves focus already claimed by a new DSH modal', () => {
  const f = fixture();
  const modal = f.element({ role: 'dialog', 'aria-modal': 'true' });
  const input = f.element({}, modal); input.tag = 'input'; input.name = 'settings-editor';
  const originalClosest = input.closest;
  input.closest = selector => selector === '[role="dialog"][aria-modal="true"], [role="menu"]'
    ? modal : originalClosest(selector);
  const previous = f.element(); previous.name = 'right-panel-trigger';
  const fallback = f.element(); fallback.name = 'main-menu';
  f.doc.activeElement = input;
  f.controller.restoreFocus(previous, fallback);
  assert.equal(f.doc.activeElement, input);
  assert.deepEqual(f.calls, [], 'A late drawer cleanup must not steal or blur modal focus');
  modal.setAttribute('data-droy-sidebar', '');
  f.controller.restoreFocus(previous, fallback);
  assert.equal(f.doc.activeElement, previous, 'The drawer itself does not block its normal focus restoration');
  f.controller.dispose();
});

test('closing a controlled preview restores focus without stealing from a different modal', () => {
  const f = fixture();
  const preview = f.element({ role: 'dialog', 'aria-modal': 'true' });
  const close = f.element({}, preview); close.name = 'preview-close';
  const originalClosest = close.closest;
  close.closest = selector => selector === '[role="dialog"][aria-modal="true"], [role="menu"]'
    ? preview : originalClosest(selector);
  const previous = f.element(); previous.name = 'attachment-preview';
  const fallback = f.element(); fallback.name = 'main-menu';
  f.doc.activeElement = close;
  f.controller.restoreFocus(previous, fallback, preview);
  assert.equal(f.doc.activeElement, previous, 'The closing owner may release its own focus while still connected');
  const anotherModal = f.element({ role: 'dialog', 'aria-modal': 'true' });
  const nextClose = f.element({}, anotherModal); nextClose.name = 'new-modal-close';
  nextClose.closest = selector => selector === '[role="dialog"][aria-modal="true"], [role="menu"]'
    ? anotherModal : null;
  f.doc.activeElement = nextClose;
  f.controller.restoreFocus(previous, fallback, preview);
  assert.equal(f.doc.activeElement, nextClose, 'The old owner must not reclaim focus from a newly opened dialog');
  assert.deepEqual(f.calls, ['focus:attachment-preview']);
  f.controller.dispose();
});
