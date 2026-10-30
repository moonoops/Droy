import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';

const source = await readFile(new URL('../client.js', import.meta.url), 'utf8');
const copy = await readFile(new URL('../ui-copy.js', import.meta.url), 'utf8');
const components = source.slice(source.indexOf('      function BackgroundRuntime()'), source.indexOf('      function PetSettings()'));
const nodes = tree => !tree || typeof tree !== 'object' ? [] : [tree, ...(tree.children || []).flatMap(nodes)];
const plain = value => JSON.parse(JSON.stringify(value));

function fixture(state = {}, { reveal = false, locale = 'zh', confirm = true } = {}) {
  const writes = [], refreshed = [], effects = [], observers = [], listeners = new Map(), slots = new Map(), frames = new Map();
  let cursor = 0, rendering = '';
  const options = { scrollTop: 0, getBoundingClientRect: () => ({ top: 100 }) };
  const archive = { closest: selector => selector === '[data-droy-settings-options]' ? options : null,
    getBoundingClientRect: () => ({ top: 900 }), scrollIntoView() { assert.fail('must not scroll hidden ancestors'); } };
  const doc = { querySelector: selector => selector === '[data-droy-notification-archive]' ? archive : null };
  const snapshot = { state, pending: null, error: null, loading: false };
  const h = (type, props, ...children) => ({ type, props: props || {}, children });
  const React = {
    useState(initial) {
      const key = `${rendering}:${cursor++}`;
      if (!slots.has(key)) slots.set(key, initial);
      return [slots.get(key), value => slots.set(key, value)];
    },
    useRef(initial) {
      const key = `${rendering}:${cursor++}`;
      if (!slots.has(key)) slots.set(key, { current: initial });
      return slots.get(key);
    },
    useEffect(callback) { effects.push(callback); },
    useSyncExternalStore(_subscribe, getSnapshot) { return getSnapshot(); }
  };
  const submit = (action, args) => { writes.push({ action, args: plain(args) }); return Promise.resolve({}); };
  const actionButton = (props, ...children) => h('dsh-button', props, ...children);
  const button = (label, action, args, _snapshot, _refresh, extra) => actionButton({ ...extra,
    disabled: snapshot.pending !== null, onClick: () => submit(action, args) }, label);
  const target = { confirm: () => confirm, addEventListener: (name, callback) => listeners.set(name, callback),
    removeEventListener: name => listeners.delete(name),
    requestAnimationFrame(callback) { const id = frames.size + 1; frames.set(id, callback); return id; },
    cancelAnimationFrame: id => frames.delete(id),
    ResizeObserver: class {
      constructor(callback) { this.resize = callback; observers.push(this); }
      observe(element) { this.element = element; }
      disconnect() { this.disconnected = true; }
    } };
  const make = new Function('React', 'h', 'useState', 'busy', 'submit', 'Feedback', 'Switch', 'Input', 'Modal', 'actionButton', 'button', 'refresh', 'target', 'doc', 'initialReveal', 'language', 'watchSection',
    `let pendingNotificationManagement = initialReveal;${copy}\ndroyLocale = { subscribe() {}, getSnapshot: () => ({active: language}) };\n${components};return {BackgroundRuntime,NotificationSettings,NotificationExceptions};`);
  const rendered = make(React, h, () => snapshot, value => value.pending !== null || !value.state, submit, 'feedback', 'dsh-switch', 'dsh-input', 'dsh-modal', actionButton, button,
    () => refreshed.push(true), target, doc, reveal, locale, () => {});
  const render = (name, props) => { cursor = 0; rendering = name; return rendered[name](props); };
  return { writes, refreshed, snapshot, effects, observers, listeners, options,
    paint() { const next = [...frames.values()]; frames.clear(); next.forEach(callback => callback()); },
    background: () => render('BackgroundRuntime'), notifications: () => render('NotificationSettings'),
    exceptions: onClose => render('NotificationExceptions', { onClose }) };
}

const managedState = () => ({ backgroundRuntimeEnabled: false, notificationManagementEnabled: true,
  notificationPermissionGranted: true, capabilities: { systemPermissions: { notifications: { permissionGranted: true } } },
  notificationManagement: { enabled: true, silentTakeoverEnabled: false, ruleText: '只有送达才提醒，其他保留原通知',
    apps: [
      { packageName: 'fictional.shop', label: '测试购物', mode: 'managed', managed: true, silent: true },
      { packageName: 'fictional.delivery', label: '测试配送', mode: 'allow', managed: false, silent: false },
      { packageName: 'fictional.promo', label: '测试促销', mode: 'allow', managed: false, silent: false }
    ],
    archive: [{ id: 'notice-a', packageName: 'fictional.shop', appName: '测试购物', title: '优惠活动', text: '虚构广告内容' }] } });

const switchNamed = (tree, name) => nodes(tree).find(node => node.type === 'dsh-switch' && node.props.label === name);
const texts = tree => nodes(tree).flatMap(node => node.children).filter(value => typeof value === 'string');

test('notification organization and silent takeover default off while background remains independent', () => {
  const ui = fixture({});
  const background = switchNamed(ui.background(), '持续后台运行');
  const management = switchNamed(ui.notifications(), '通知管理');
  const takeover = switchNamed(ui.notifications(), '静默接管');
  assert.equal(background.props.checked, false);
  assert.equal(management.props.checked, false);
  assert.equal(takeover.props.checked, false);
  management.props.onChange(false);
  background.props.onChange(true);
  assert.deepEqual(ui.writes, [
    { action: 'notificationManagement', args: { operation: 'configure', enabled: false } },
    { action: 'setPreference', args: { key: 'backgroundRuntimeEnabled', value: true } }
  ]);
});

test('explicit saved off choice controls the unified switch and silent takeover changes only its own setting', () => {
  const state = managedState();
  state.notificationManagementEnabled = false;
  state.notificationManagement.enabled = true;
  const ui = fixture(state);
  assert.equal(switchNamed(ui.notifications(), '通知管理').props.checked, false);
  const takeover = switchNamed(ui.notifications(), '静默接管');
  assert.equal(takeover.props.checked, false);
  takeover.props.onChange(true);
  assert.deepEqual(ui.writes, [{ action: 'notificationManagement', args: { operation: 'configure', silentTakeoverEnabled: true } }]);
  assert.equal(switchNamed(ui.notifications(), '智能分析'), undefined);
});

test('declining content disclosure or silent takeover writes no configuration', () => {
  const state = managedState(); state.notificationManagementEnabled = false;
  const ui = fixture(state, { confirm: false });
  switchNamed(ui.notifications(), '通知管理').props.onChange(true);
  switchNamed(ui.notifications(), '静默接管').props.onChange(true);
  assert.deepEqual(ui.writes, []);
});

test('migrated rules must be reviewed without automatically enabling organization', () => {
  const state = managedState(); state.notificationManagementEnabled = false;
  state.notificationManagement.requiresReview = true;
  const ui = fixture(state);
  switchNamed(ui.notifications(), '通知管理').props.onChange(true);
  const tree = ui.notifications();
  assert.equal(nodes(tree).find(node => node.props['data-droy-notification-details']).props.open, true);
  assert.ok(texts(tree).includes('请先检查并保存迁移的整理规则，再开启整理。'));
  assert.equal(nodes(tree).find(node => node.props['data-droy-notification-rule-save']).props.disabled, false);
  assert.deepEqual(ui.writes, []);
});

test('main settings display an app selection entry without duplicating the installed-app list', () => {
  const ui = fixture(managedState()), tree = ui.notifications();
  assert.equal(nodes(tree).some(node => node.props['data-droy-notification-app']), false);
  assert.equal(nodes(tree).some(node => node.type === 'dsh-input'), false);
  const mainCopy = texts(tree);
  assert.equal(mainCopy.some(text => ['测试配送', '测试促销'].includes(text)), false);
  const open = nodes(tree).find(node => node.props['data-droy-action'] === 'openNotificationExceptions');
  assert.ok(open);
  assert.ok(mainCopy.includes('已选择 1 个 ›'));
  assert.equal(nodes(tree).some(node => typeof node.type === 'function' && node.type.name === 'NotificationExceptions'), false);
  open.props.onClick();
  const secondary = nodes(ui.notifications()).find(node => typeof node.type === 'function' && node.type.name === 'NotificationExceptions');
  assert.ok(secondary);
  secondary.props.onClose();
  assert.equal(nodes(ui.notifications()).some(node => typeof node.type === 'function' && node.type.name === 'NotificationExceptions'), false);
});

test('app manager lists every eligible app and uses the upstream modal close path', () => {
  const ui = fixture(managedState()); let closed = false;
  const tree = ui.exceptions(() => { closed = true; });
  assert.equal(tree.type, 'dsh-modal');
  assert.equal(tree.props.title, '通知应用管理');
  assert.equal(tree.props.closeLabel, '返回通知管理');
  assert.equal(tree.props.contentClassName, 'droy-notification-exceptions-content');
  const rows = nodes(tree).filter(node => node.props['data-droy-notification-app']);
  assert.deepEqual(rows.map(node => node.props['data-droy-notification-app']), ['fictional.shop', 'fictional.delivery', 'fictional.promo']);
  assert.equal(texts(tree).includes('测试购物'), true);
  tree.props.onClose(); assert.equal(closed, true);
});

test('search changes only managed and allow modes without mutating the saved app selection', () => {
  const state = managedState(), before = structuredClone(state.notificationManagement.apps), ui = fixture(state);
  let tree = ui.exceptions(() => {});
  nodes(tree).find(node => node.type === 'dsh-input').props.onChange({ target: { value: '购物' } });
  tree = ui.exceptions(() => {});
  const rows = nodes(tree).filter(node => node.props['data-droy-notification-app']);
  assert.deepEqual(rows.map(node => node.props['data-droy-notification-app']), ['fictional.shop']);
  assert.equal(nodes(rows[0]).some(node => node.props['data-droy-notification-mode'] === 'block'), false);
  for (const mode of ['allow', 'managed']) {
    const choice = nodes(rows[0]).find(node => node.props['data-droy-notification-mode'] === mode);
    choice.props.onClick();
    assert.deepEqual(ui.writes.pop(), { action: 'notificationManagement', args: { operation: 'setAppMode', packageName: 'fictional.shop', mode } });
  }
  assert.deepEqual(state.notificationManagement.apps, before);
});

test('batch selection uses the complete installed list without changing the master switch or rules', () => {
  const state = managedState(), ui = fixture(state), tree = ui.exceptions(() => {});
  const before = structuredClone(state.notificationManagement);
  for (const selected of [true, false]) {
    nodes(tree).find(node => node.props['data-droy-notification-select-all'] === selected).props.onClick();
    assert.deepEqual(ui.writes.pop(), { action: 'notificationManagement', args: {
      operation: 'setApps', enabled: selected, packageNames: before.apps.map(app => app.packageName),
    } });
  }
  assert.deepEqual(state.notificationManagement, before);
});

test('empty installed-app and search states remain honest', () => {
  const state = managedState(); state.notificationManagement.apps = [];
  const ui = fixture(state);
  assert.ok(texts(ui.exceptions(() => {})).includes('暂无可整理的应用'));
  nodes(ui.exceptions(() => {})).find(node => node.type === 'dsh-input').props.onChange({ target: { value: 'not-installed' } });
  assert.ok(texts(ui.exceptions(() => {})).includes('没有匹配的应用'));
});

test('new switches keep expanded touch targets and secondary choices have 48px targets', () => {
  const ui = fixture(managedState());
  const switches = [...nodes(ui.background()), ...nodes(ui.notifications())].filter(node => node.type === 'dsh-switch');
  assert.equal(switches.length, 3);
  assert.ok(switches.every(node => node.props.className === 'droy-touch-switch'));
  assert.match(source, /\.droy-touch-switch\[role=switch\]\[aria-checked\]\{width:48px;height:48px;/);
  assert.match(source, /\.droy-notification-modes \.droy-button\{[^}]*min-height:48px;/);
  assert.match(source, /\.droy-notification-exceptions-body \.droy-input>input\{[^}]*min-height:48px;height:48px;/);
});

test('short keyboard viewport scrolls the focused search within the whole secondary page', () => {
  const ui = fixture(managedState()), tree = ui.exceptions(() => {});
  let focused = true;
  const content = { scrollTop: 0, getBoundingClientRect: () => ({ top: 32, bottom: 107 }) };
  const input = { getBoundingClientRect: () => ({ top: 89 - content.scrollTop, bottom: 137 - content.scrollTop }) };
  const body = { closest: selector => selector === '.droy-notification-exceptions-content' ? content : null,
    querySelector: selector => selector === 'input:focus' && focused ? input : null };
  nodes(tree).find(node => node.props['data-droy-notification-exceptions']).props.ref.current = body;
  const dispose = ui.effects[0]();
  assert.equal(ui.observers.length, 1);
  const observer = ui.observers[0];
  assert.equal(observer.element, content);
  observer.resize();
  assert.equal(content.scrollTop, 30);
  assert.ok(input.getBoundingClientRect().top >= 32);
  assert.ok(input.getBoundingClientRect().bottom <= 107);
  observer.resize(); assert.equal(content.scrollTop, 30);
  focused = false; content.scrollTop = 100;
  observer.resize(); assert.equal(content.scrollTop, 100);
  dispose(); assert.equal(observer.disconnected, true);
  assert.match(source, /\.droy-notification-exceptions-content\{[^}]*overflow:auto;/);
  assert.match(source, /\.droy-notification-exceptions-content>div:last-child\{[^}]*flex:none;/);
});

test('rules edit and save the complete canonical text while preserving saved state until acknowledgement', async () => {
  const state = managedState(), before = state.notificationManagement.ruleText, ui = fixture(state);
  let tree = ui.notifications();
  assert.equal(nodes(tree).find(node => node.props['data-droy-notification-rule-text']).props.value, before);
  nodes(tree).find(node => node.props['data-droy-notification-rule-text']).props.onChange({ target: { value: before + '；保留验证码' } });
  tree = ui.notifications();
  await nodes(tree).find(node => node.props['data-droy-notification-rule-save']).props.onClick();
  assert.deepEqual(ui.writes, [{ action: 'notificationManagement', args: { operation: 'configure', ruleText: before + '；保留验证码' } }]);
  assert.equal(state.notificationManagement.ruleText, before);
  await nodes(ui.notifications()).find(node => node.props['data-droy-notification-rule-reset']).props.onClick();
  assert.deepEqual(ui.writes[1], { action: 'notificationManagement', args: { operation: 'configure', resetRule: true } });
  assert.equal(nodes(tree).some(node => node.props['data-droy-notification-rule-delete']), false);
  assert.equal(texts(tree).some(value => /已知晓|稍后提醒|确认已读|置信度/.test(value)), false);
});

test('summary reveals and scrolls the archive only within its settings pane', () => {
  const ui = fixture(managedState(), { reveal: true });
  let tree = ui.notifications();
  assert.equal(nodes(tree).find(node => node.props['data-droy-notification-details']).props.open, true);
  assert.equal(nodes(tree).find(node => node.props['data-droy-notification-archive']).props.open, true);
  const disposers = ui.effects.splice(0).map(effect => effect());
  ui.paint(); assert.equal(ui.options.scrollTop, 800);
  const open = nodes(tree).find(node => node.props['data-droy-action'] === 'openNotificationExceptions');
  open.props.onClick();
  assert.ok(nodes(ui.notifications()).some(node => typeof node.type === 'function' && node.type.name === 'NotificationExceptions'));
  ui.listeners.get('droy-notification-management-open')();
  tree = ui.notifications();
  assert.equal(nodes(tree).some(node => typeof node.type === 'function' && node.type.name === 'NotificationExceptions'), false);
  disposers.forEach(dispose => dispose?.()); assert.equal(ui.listeners.size, 0);
});

test('permission actions and concise actionable errors stay in the main notification panel', () => {
  const state = managedState(); state.capabilities.systemPermissions.notifications.permissionGranted = false;
  state.notificationPermissionGranted = false;
  state.notificationManagement.autoSilence = { actionRequired: true, message: '请开启 Root 权限' };
  const ui = fixture(state); const tree = ui.notifications();
  const error = nodes(tree).find(node => node.props['data-droy-notification-error']);
  assert.deepEqual(error.children, ['请开启 Root 权限']);
  nodes(tree).find(node => node.children.includes('开启通知访问')).props.onClick();
  nodes(tree).find(node => node.children.includes('开启提醒权限')).props.onClick();
  assert.deepEqual(ui.writes, [
    { action: 'requestPermission', args: { name: 'notificationAccess' } },
    { action: 'requestPermission', args: { name: 'notifications' } }
  ]);
  state.notificationManagement.autoSilence = { status: 'syncing', actionRequired: false, message: '正在设置' };
  assert.equal(nodes(ui.notifications()).some(node => node.props['data-droy-notification-error']), false);
});


test('important reminder defaults to five minutes and updates only its configured delay', () => {
  const state = managedState(), ui = fixture(state);
  let choices = nodes(ui.notifications()).filter(node => node.props['data-droy-notification-reminder']);
  assert.deepEqual(choices.map(node => node.props['aria-pressed']), [true, false]);
  choices[1].props.onClick();
  assert.deepEqual(ui.writes, [{ action: 'notificationManagement', args: { operation: 'configure', reminderMinutes: 10 } }]);
  state.notificationManagement.reminderMinutes = 10;
  choices = nodes(ui.notifications()).filter(node => node.props['data-droy-notification-reminder']);
  assert.deepEqual(choices.map(node => node.props['aria-pressed']), [false, true]);
  assert.deepEqual(choices.map(node => node.children[0]), ['5 分钟', '10 分钟']);
  ui.snapshot.pending = 'notificationManagement';
  assert.ok(nodes(ui.notifications()).filter(node => node.props['data-droy-notification-reminder']).every(node => node.props.disabled));
});

test('reminder descriptions and choices switch languages without changing saved settings', () => {
  const state = managedState(); state.notificationManagement.reminderMinutes = 10;
  const ui = fixture(state, { locale: 'en' }), tree = ui.notifications();
  assert.ok(texts(tree).includes('Important notification reminders'));
  assert.ok(texts(tree).includes('Keep original app alerts and use the current model to clear ads by default. Basic organization does not need Root.'));
  assert.deepEqual(nodes(tree).filter(node => node.props['data-droy-notification-reminder']).map(node => node.children[0]), ['5 minutes', '10 minutes']);
  assert.equal(state.notificationManagement.reminderMinutes, 10);
  assert.deepEqual(ui.writes, []);
});

test('listener startup is a preparation status rather than a permission error', () => {
  const state = managedState();
  state.notificationManagement.access = { state: 'connecting', message: '正在准备通知整理，稍等一下。', actionRequired: false, action: '' };
  const ui = fixture(state), tree = ui.notifications();
  const preparing = nodes(tree).find(node => node.props['data-droy-notification-preparing']);
  assert.equal(preparing.props.role, 'status');
  assert.ok(texts(preparing).includes('正在准备通知整理，稍等一下。'));
  assert.equal(nodes(tree).some(node => node.props['data-droy-notification-error']), false);
  assert.equal(texts(tree).includes('检查通知读取权限'), false);
});

test('actual listener failure and posting permission have distinct setting actions', () => {
  for (const [stateName, action, label] of [['listener_unavailable', 'notificationAccess', '检查通知读取权限'],
    ['post_permission_required', 'notifications', '开启提醒权限']]) {
    const state = managedState();
    state.notificationManagement.access = { state: stateName, message: '需要处理这一项设置', actionRequired: true, action };
    const ui = fixture(state), tree = ui.notifications();
    assert.ok(texts(nodes(tree).find(node => node.props['data-droy-notification-error'])).includes('需要处理这一项设置'));
    const setting = nodes(tree).find(node => node.type === 'dsh-button' && texts(node).includes(label));
    assert.ok(setting); setting.props.onClick();
    assert.deepEqual(ui.writes, [{ action: 'requestPermission', args: { name: action } }]);
  }
});

test('confirmed advertisement cleanup statistics live in settings in both languages', () => {
  for (const [locale, expected] of [['zh', '最近24小时已清理 8 条广告'], ['en', 'Cleared 8 ads in the last 24 hours']]) {
    const state = managedState(); state.notificationManagement.clearedAdsCount = 8;
    state.notificationManagement.access = { state: 'ready', message: '', actionRequired: false, action: '' };
    const tree = fixture(state, { locale }).notifications();
    const count = nodes(tree).find(node => node.props['data-droy-cleared-ads']);
    assert.ok(texts(count).includes(expected));
    assert.equal(nodes(tree).some(node => node.props['data-droy-notification-error']), false);
  }
});

test('actual processing state and last result remain visible independently of the enabled switch', () => {
  const state = managedState();
  state.notificationManagementEnabled = false;
  state.notificationManagement.processing = { state: 'restoration_required', action: 'restore', message: '原应用设置仍待恢复' };
  state.notificationManagement.lastResult = { atMs: 100, success: false, message: '虚构模型超时，原通知已保留' };
  const ui = fixture(state), tree = ui.notifications();
  assert.equal(switchNamed(tree, '通知管理').props.checked, false);
  assert.ok(texts(nodes(tree).find(node => node.props['data-droy-notification-error'])).includes('原应用设置仍待恢复'));
  assert.ok(texts(nodes(tree).find(node => node.props['data-droy-notification-last-result'])).includes('最近处理：虚构模型超时，原通知已保留'));
  nodes(tree).find(node => node.props['data-droy-notification-recovery'] === 'restore').props.onClick();
  assert.deepEqual(ui.writes, [{ action: 'notificationManagement', args: { operation: 'configure', silentTakeoverEnabled: false } }]);
});

test('processing recovery can open app selection, resume, or recheck the configured model', () => {
  for (const action of ['apps', 'resume', 'model', 'permissions']) {
    const state = managedState(); state.notificationManagement.processing = { state: 'setup_required', action, message: '需要完成设置' };
    const ui = fixture(state), tree = ui.notifications();
    assert.ok(texts(nodes(tree).find(node => node.props['data-droy-notification-processing'])).includes('需要完成设置'));
    nodes(tree).find(node => node.props['data-droy-notification-recovery'] === action).props.onClick();
    if (action === 'apps') assert.ok(nodes(ui.notifications()).some(node => typeof node.type === 'function' && node.type.name === 'NotificationExceptions'));
    else if (action === 'resume') assert.deepEqual(ui.writes, [{ action: 'resumeOperations', args: {} }]);
    else assert.deepEqual(ui.refreshed, [true]);
  }
});
