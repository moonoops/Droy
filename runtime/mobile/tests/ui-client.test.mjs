import test from 'node:test';
import assert from 'node:assert/strict';
import vm from 'node:vm';
import { composeMobileClient } from '../../../tools/mobile_client_bundle.mjs';
import { readFile } from 'node:fs/promises';

const touchSource = await readFile(new URL('../touch.js', import.meta.url), 'utf8');
const usageSource = await readFile(new URL('../token-usage-client.js', import.meta.url), 'utf8');
const source = composeMobileClient(await readFile(new URL('../client.js', import.meta.url), 'utf8'), touchSource, usageSource);
const modalSelector = '[role="dialog"][aria-modal="true"], [role="menu"]';
function load(window = {}, overrides = {}) {
  let client;
  window.__ModuleLoader__ = { load({ id, factory }) {
    assert.equal(id, '@droyagent/mobile');
    client = factory(name => {
      if (name === 'react') return overrides.React || {};
      if (name === '@deepseek-ai/dsh-client-ui-primitives') return {
        Button: 'dsh-button', Switch: 'dsh-switch', Input: 'dsh-input', Toast: 'dsh-toast',
        closeTopModal() {}, modalSelector, ...overrides.primitives
      };
      throw new Error(`Unexpected dependency: ${name}`);
    });
  } };
  vm.runInNewContext(source, { window });
  return client;
}

const state = () => ({
  assistantName: 'Droy', browserEncryptedDns: true, runtimeReady: true,
  groups: [{ preference: 'allowContacts', label: '读取联系人', description: '查询匹配联系人', section: '个人数据', enabled: true }],
  fileGrants: [{ grantId: 'fictional-file', name: 'example.txt', kind: 'document', writable: false, available: true }],
  capabilities: { accessibilityConnected: true, rootAuthorization: 'not_verified', product: { stopped: false },
    systemPermissions: { calendar: false, contacts: false, usage: true, notifications: { permissionGranted: true, connected: false } } },
  systemPermissions: { brightness: true }, notificationPermissionGranted: true
});
const flush = async () => { for (let index = 0; index < 3; index++) await new Promise(resolve => setImmediate(resolve)); };

test('logo backing colors follow the opaque silhouette and ignore transparent RGB and interior highlights', () => {
  const client = load();
  const image = (edge, center = edge) => {
    const pixels = new Uint8ClampedArray(5 * 5 * 4).fill(255);
    for (let y = 0; y < 5; y++) for (let x = 0; x < 5; x++) {
      const offset = (y * 5 + x) * 4;
      pixels[offset + 3] = x && y && x < 4 && y < 4 ? 255 : 0;
      const value = x === 2 && y === 2 ? center : edge;
      pixels[offset] = pixels[offset + 1] = pixels[offset + 2] = value;
    }
    return client.logoBackgroundsFromPixels(pixels, 5, 5);
  };
  assert.equal(image(0).light, '#F2EEE7');
  assert.equal(image(0).dark, '#D8D3CB');
  assert.equal(image(255).light, '#4A4650');
  assert.equal(image(255).dark, '#37333D');
  assert.equal(image(153).dark, '#37333D', 'Mid-gray artwork needs the higher-contrast backing too');
  assert.equal(image(0, 255).light, '#F2EEE7', 'A bright face must not obscure a dark outline');
  const transparent = new Uint8ClampedArray(16).fill(255);
  for (let i = 3; i < transparent.length; i += 4) transparent[i] = 0;
  assert.equal(client.logoBackgroundsFromPixels(transparent, 2, 2).light, '#F2EEE7');
});

test('current logo colors apply to shared brand slots and both previews use the same foreground', async () => {
  const saved = state();
  saved.branding = { name: 'Droy', logoBackgrounds: { light: '#AAAABB', dark: '#BBBBAA' } };
  const fixture = applyFixture(saved);
  await flush();
  const mark = fixture.registrations.find(item => item.options.name === 'conversation.hero.brand.mark').component({ size: 80 });
  const image = mark.type(mark.props);
  assert.equal(image.props.style['--droy-logo-light'], '#AAAABB');
  assert.equal(image.props.style['--droy-logo-dark'], '#BBBBAA');
  const settings = fixture.registrations.find(item => item.options.id === 'droy-branding').component();
  const editor = nodes(settings).find(node => node.type?.name === 'BrandBackgroundSettings');
  const editorTree = editor.type(editor.props);
  const previews = nodes(editorTree).find(node => node.type?.name === 'BrandPreviews');
  const rendered = previews.type(previews.props);
  assert.deepEqual(nodes(rendered).filter(node => node.props['data-droy-brand-preview']).map(node => node.props['data-droy-brand-preview']), ['light', 'dark']);
  const images = nodes(rendered).filter(node => node.type?.name === 'BrandImage');
  assert.equal(images.length, 2);
  assert.equal(images[0].props.src, images[1].props.src);
  assert.equal(images[0].props.size, images[1].props.size);
  fixture.dispose();
});

test('native bridge correlates out-of-order replies, rejects errors, ignores unmatched replies', async () => {
  const sent = [];
  const window = { setTimeout, clearTimeout, DroyNative: { postMessage: message => sent.push(JSON.parse(message)) } };
  const bridge = load(window).createNativeBridge(window);
  const first = bridge.request('getState');
  const second = bridge.request('setPreference', { key: 'allowContacts', value: false });
  window.DroyNative.onmessage({ data: JSON.stringify({ id: 'unknown', ok: true, result: null }) });
  window.DroyNative.onmessage({ data: '{invalid' });
  window.DroyNative.onmessage({ data: JSON.stringify({ id: sent[1].id, ok: false, error: '设置未保存' }) });
  await assert.rejects(second, /设置未保存/);
  window.DroyNative.onmessage({ data: JSON.stringify({ id: sent[0].id, ok: true, result: { assistantName: 'Example' } }) });
  assert.equal((await first).assistantName, 'Example');
  assert.equal(sent[1].args.value, false);
  bridge.dispose();
});

test('bridge timeouts and disposal reject instead of reporting success', async () => {
  const original = () => {};
  const window = { setTimeout, clearTimeout, DroyNative: { onmessage: original, postMessage() {} } };
  const bridge = load(window).createNativeBridge(window, 5);
  await assert.rejects(bridge.request('pauseOperations'), /超时/);
  const pending = bridge.request('getState');
  bridge.dispose();
  await assert.rejects(pending, /页面已关闭/);
  await assert.rejects(bridge.request('getState'), /页面已关闭/);
  assert.equal(window.DroyNative.onmessage, original);
  const unavailable = load().createNativeBridge({ setTimeout, clearTimeout });
  await assert.rejects(unavailable.request('getState'), /未连接手机设置/);
});

test('settings mutations refresh confirmed state and expose failed writes', async () => {
  const saved = state();
  const calls = [];
  let fail = false;
  const controller = load().createController({ async request(action, args) {
    calls.push([action, args]);
    if (action === 'getState') return structuredClone(saved);
    if (fail) throw new Error('权限没有保存');
    if (action === 'setPreference') saved.groups[0].enabled = args.value;
    return {};
  } });
  let notifications = 0;
  const unsubscribe = controller.subscribe(() => notifications++);
  await controller.refresh();
  await controller.run('setPreference', { key: 'allowContacts', value: false });
  assert.equal(controller.getSnapshot().state.groups[0].enabled, false);
  assert.deepEqual(calls.map(call => call[0]), ['getState', 'setPreference', 'getState']);
  fail = true;
  await assert.rejects(controller.run('setPreference', { key: 'allowContacts', value: true }), /权限没有保存/);
  assert.equal(controller.getSnapshot().state.groups[0].enabled, false);
  assert.equal(controller.getSnapshot().pending, null);
  assert.match(controller.getSnapshot().error, /权限没有保存/);
  assert.ok(notifications > 0);
  unsubscribe();
  controller.dispose();
});

test('late older refresh cannot override a newer permission snapshot', async () => {
  const resolvers = [];
  const controller = load().createController({ request: () => new Promise(resolve => resolvers.push(resolve)) });
  const older = controller.refresh();
  const newer = controller.refresh({ force: true });
  resolvers[1]({ ...state(), assistantName: 'Current' });
  await newer;
  resolvers[0]({ ...state(), assistantName: 'Old' });
  await older;
  assert.equal(controller.getSnapshot().state.assistantName, 'Current');
  controller.dispose();
});

test('settings refreshes coalesce and cached unchanged grants do not rerender the settings tree', async () => {
  const waiting = [];
  const controller = load().createController({ request: () => new Promise(resolve => waiting.push(resolve)) });
  let notifications = 0;
  controller.subscribe(() => notifications++);
  const first = controller.refresh();
  assert.equal(controller.refresh(), first);
  assert.equal(waiting.length, 1);
  waiting.shift()(state());
  await first;
  const initialNotifications = notifications;
  await controller.refresh({ maxAgeMs: 5000 });
  assert.equal(waiting.length, 0);
  assert.equal(notifications, initialNotifications);
  const background = controller.refresh();
  assert.equal(controller.getSnapshot().loading, false);
  waiting.shift()(state());
  await background;
  assert.equal(notifications, initialNotifications);
  const permissionChange = controller.refresh();
  waiting.shift()({ ...state(), systemPermissions: { contacts: true } });
  await permissionChange;
  assert.equal(controller.getSnapshot().state.systemPermissions.contacts, true);
  assert.equal(notifications, initialNotifications + 1);
  controller.dispose();
});

test('a failed background refresh keeps accepted settings and offers a recoverable error', async () => {
  let fail = false;
  const controller = load().createController({ request() {
    if (fail) throw new Error('系统授权状态读取失败');
    return state();
  } });
  await controller.refresh();
  fail = true;
  await assert.rejects(controller.refresh(), /系统授权状态读取失败/);
  assert.equal(controller.getSnapshot().state.groups[0].enabled, true);
  assert.match(controller.getSnapshot().error, /系统授权状态读取失败/);
  fail = false;
  await controller.refresh();
  assert.equal(controller.getSnapshot().error, null);
  controller.dispose();
});

test('permission status comes from actual grants even when product capability is enabled', () => {
  const rows = load().permissionRows(state());
  assert.equal(rows.find(row => row[0] === 'contacts')[2], '待授权');
  assert.equal(rows.find(row => row[0] === 'brightness')[2], '已授权');
  assert.equal(rows.find(row => row[0] === 'notificationAccess')[2], '已授权，服务待连接');
  const unknown = load().permissionRows({ capabilities: {} });
  assert.equal(unknown.find(row => row[0] === 'calendar')[2], '状态暂不可用');
  assert.equal(unknown.find(row => row[0] === 'microphone')[2], '状态暂不可用');
  assert.equal(load().permissionRows({...state(),systemPermissions:{microphone:true}}).find(row=>row[0] === 'microphone')[2], '已授权');
});

test('hardware permissions distinguish approximate location, scan prerequisites and unsupported hardware', () => {
  const saved = {...state(), systemPermissions: {
    hardware: {wifi:true,bluetooth:true,nfc:false,telephony:true,stepCounter:false},
    locationApproximate:true,locationPrecise:false,locationEnabled:true,
    bluetoothScan:true,bluetoothConnect:false,nearbyWifi:true,wifiScan:false,phoneState:false,
  }};
  const rows = load().permissionRows(saved);
  assert.equal(rows.find(row => row[0] === 'location')[2], '大致位置已授权');
  assert.equal(rows.find(row => row[0] === 'wifiScan')[2], '需要精确位置权限');
  assert.equal(rows.find(row => row[0] === 'nearbyWifi')[2], '已授权');
  assert.equal(rows.find(row => row[0] === 'bluetooth')[2], '待授权');
  assert.equal(rows.find(row => row[0] === 'nfcSettings')[2], '设备不支持');
  assert.equal(rows.find(row => row[0] === 'nfcSettings')[3], null);
  assert.equal(rows.find(row => row[0] === 'activityRecognition')[3], null);
});

test('dictation claims native input before capture and releases on normal track stop', async () => {
  const calls = [], listeners = new Map();
  const track = {readyState:'live',stop(){ this.readyState='ended'; },addEventListener(name,listener){listeners.set(name,listener);} };
  const stream = {getTracks:()=>[track],getAudioTracks:()=>[track]};
  const original = async () => { calls.push('capture'); return stream; };
  const target = {navigator:{mediaDevices:{getUserMedia:original}}};
  const guard = load().createMicrophoneGuard(target,{async request(action,args){
    calls.push(action === 'audioDictation' ? args.operation : action);
    return state();
  }});
  assert.equal(await target.navigator.mediaDevices.getUserMedia({audio:true}),stream);
  assert.deepEqual(calls.slice(0,3),['getState','claim','capture']);
  await assert.rejects(target.navigator.mediaDevices.getUserMedia({audio:true}),/正在使用/);
  track.stop(); await flush();
  assert.equal(calls.filter(call=>call === 'release').length,1);
  guard.dispose();
  assert.equal(target.navigator.mediaDevices.getUserMedia,original);
});

test('revoking active dictation stops audio and emits the interruption that discards the recording', async () => {
  let interrupted=0, releases=0, grants=true;
  const listeners = new Map();
  const track = {readyState:'live',stop(){this.readyState='ended';},addEventListener(name,listener){listeners.set(name,listener);},
    dispatchEvent(event){ if(event.type === 'ended') interrupted++; listeners.get(event.type)?.(event); }};
  const stream = {getTracks:()=>[track],getAudioTracks:()=>[track]};
  const target = {Event:class {constructor(type){this.type=type;}},navigator:{mediaDevices:{getUserMedia:async()=>stream}}};
  const guard=load().createMicrophoneGuard(target,{async request(action,args){
    if(action === 'audioDictation' && args.operation === 'release') releases++;
    return {...state(),toolsPaused:!grants};
  }});
  await target.navigator.mediaDevices.getUserMedia({audio:true});
  grants=false; guard.stop(); await flush();
  assert.equal(track.readyState,'ended'); assert.equal(interrupted,1); assert.equal(releases,1);
  await assert.rejects(target.navigator.mediaDevices.getUserMedia({audio:true}),/已关闭|已暂停/);
  guard.dispose();
});

test('late microphone permission after cancellation is released instead of starting dictation', async () => {
  let finishGrant, releases=0, stopped=0;
  const track={stop(){stopped++;},addEventListener(){}};
  const stream={getTracks:()=>[track],getAudioTracks:()=>[track]};
  const target={navigator:{mediaDevices:{getUserMedia:()=>new Promise(resolve=>finishGrant=resolve)}}};
  const guard=load().createMicrophoneGuard(target,{async request(action,args){
    if(action === 'audioDictation' && args.operation === 'release') releases++;
    return state();
  }});
  const pending=target.navigator.mediaDevices.getUserMedia({audio:true});
  await flush(); guard.stop(); finishGrant(stream);
  await assert.rejects(pending,/已取消/); assert.equal(stopped,1); assert.equal(releases,1);
  guard.dispose();
});

test('an explicitly stopped engine cannot start a cached WebView microphone grant', async () => {
  let opened=0,claimed=0;
  const target={navigator:{mediaDevices:{getUserMedia:async()=>{opened++;throw new Error('must not open');}}}};
  const guard=load().createMicrophoneGuard(target,{async request(action){
    if(action === 'audioDictation') claimed++;
    return {...state(),runtimeReady:false};
  }});
  await assert.rejects(target.navigator.mediaDevices.getUserMedia({audio:true}),/已关闭|已暂停/);
  assert.equal(opened,0);assert.equal(claimed,0);guard.dispose();
});

test('optional Shizuku status distinguishes running and authorization', () => {
  const row = shizuku => load().permissionRows({capabilities:{shizuku}}).find(item => item[0] === 'shizuku');
  assert.equal(row({running:false,authorized:false})[2], '未启动');
  assert.equal(row({running:true,authorized:false})[2], '待授权');
  assert.equal(row({running:true,authorized:true})[2], '已授权');
});

test('diagnostic system access distinguishes PC setup from usage access without a false settings button', () => {
  const row = diagnostics => load().permissionRows({capabilities:{diagnostics:{privacy_usage:diagnostics}}})
    .find(item => item[0] === 'diagnostics');
  assert.equal(row({available:true})[2], '已配置');
  assert.equal(row({available:false,reason:'usage_permission_required'})[2], '请开启使用情况访问');
  assert.match(row({available:false,reason:'usage_stats_permission_required'})[2], /电脑端权限配置/);
  assert.equal(row({available:false,reason:'dump_permission_required'})[3], null);
});

test('accessibility distinguishes system authorization, recovery and live connection', () => {
  const client = load();
  const cases = [
    [{ enabled: false, authorized: false, ready: true, status: 'not_authorized' }, '未开启'],
    [{ enabled: false, authorized: false, ready: true, status: 'revoked' }, '系统中已关闭'],
    [{ enabled: false, authorized: true, ready: true, status: 'restoring' }, '自动恢复中'],
    [{ enabled: true, authorized: true, ready: true, status: 'restoring' }, '自动恢复中'],
    [{ enabled: true, authorized: true, ready: true, status: 'disconnected' }, '已开启，服务待连接'],
    [{ enabled: true, authorized: true, ready: false, status: 'setup_required' }, '已开启，服务待连接'],
    [{ enabled: false, authorized: true, ready: false, status: 'setup_required' }, '已授权，自动恢复待配置'],
    [{ enabled: false, authorized: true, ready: true, status: 'pending_restore' }, '待自动恢复'],
    [{ enabled: false, authorized: true, ready: true, status: 'error' }, '自动恢复失败'],
  ];
  for (const [accessibilityRecovery, expected] of cases) {
    const row = client.permissionRows({ capabilities: { accessibilityConnected: false, accessibilityRecovery } })
      .find(row => row[0] === 'accessibility');
    assert.equal(row[2], expected, JSON.stringify(accessibilityRecovery));
    assert.equal(row[3], '打开系统无障碍设置');
  }
});

test('live accessibility connection wins over an earlier recovery status and legacy state stays usable', () => {
  const client = load();
  for (const status of ['revoked', 'restoring', 'error', 'setup_required']) {
    const row = client.permissionRows({ capabilities: { accessibilityConnected: true,
      accessibilityRecovery: { enabled: false, authorized: false, ready: false, status } } })
      .find(row => row[0] === 'accessibility');
    assert.equal(row[2], '已连接');
  }
  for (const capabilities of [{}, { accessibilityConnected: false }, { accessibilityRecovery: {} }]) {
    assert.equal(client.permissionRows({ capabilities }).find(row => row[0] === 'accessibility')[2], '待连接');
  }
});

test('back closes one layer at a time with the upstream void modal close API', () => {
  const calls = [];
  let modal = true;
  let drawer = true;
  let panel = 'files';
  const drawerNode = { hasAttribute: attribute => attribute === 'data-droy-sidebar' };
  const modalNode = { hasAttribute: () => false };
  const doc = { querySelector: selector => selector === '.droy-voice-choice[open]' ? null : drawer ? drawerNode : null,
    querySelectorAll: selector => {
      assert.equal(selector, modalSelector);
      return [...drawer ? [drawerNode] : [], ...modal ? [modalNode] : []];
    } };
  const ctx = { layout: { toggleSidebar() { calls.push('drawer'); drawer = false; },
    panelInfo: { getSnapshot: () => ({ activePanelId: panel }) },
    selectPanel(id) { calls.push('conversation'); panel = id; } } };
  const back = load().createBackHandler(ctx, doc, () => { calls.push('modal'); modal = false; });
  assert.equal(back(), true);
  assert.deepEqual(calls, ['modal']);
  assert.equal(back(), true);
  assert.deepEqual(calls, ['modal', 'drawer']);
  assert.equal(back(), true);
  assert.deepEqual(calls, ['modal', 'drawer', 'conversation']);
  assert.equal(back(), false);
});

test('entering settings closes its launching drawer and returns the underlying destination to chat', () => {
  let drawer = true;
  let panel = 'files';
  const calls = [];
  const ctx = { layout: {
    toggleSidebar() { drawer = false; calls.push('drawer'); },
    panelInfo: { getSnapshot: () => ({ activePanelId: panel }) },
    selectPanel(id) { panel = id; calls.push('chat'); }
  } };
  const open = load().createSettingsOpenHandler(ctx, { querySelector: () => drawer });
  open();
  assert.deepEqual(calls, ['drawer', 'chat']);
  open();
  assert.deepEqual(calls, ['drawer', 'chat']);
});

test('new global panel selection releases the drawer without reacting to restored or unchanged state', () => {
  let drawer = true, panel = 'restored-panel', closes = 0;
  const subscribers = new Set();
  const ctx = { layout: {
    panelInfo: { getSnapshot: () => ({ activePanelId: panel }),
      subscribe(listener) { subscribers.add(listener); return () => subscribers.delete(listener); } },
    toggleSidebar() { drawer = !drawer; closes++; for (const listener of subscribers) listener(); }
  } };
  const stop = load().observePanelNavigation(ctx, { querySelector: () => drawer });
  const publish = value => { panel = value; for (const listener of subscribers) listener(); };
  assert.equal(drawer, true, 'Initial restored state must not close a drawer');
  publish('restored-panel');
  assert.equal(drawer, true, 'Same-value notifications must not close a drawer');
  publish(null);
  assert.equal(drawer, true, 'Returning to chat retains its own navigation behavior');
  publish('plugins');
  assert.equal(drawer, false, 'A selected plugin panel must be usable, not covered by an inert drawer');
  assert.equal(closes, 1, 'A synchronous store notification must not re-open the drawer');
  publish('automations');
  assert.equal(closes, 1, 'Navigation with a closed drawer must not toggle it open');
  drawer = true;
  publish('automations');
  assert.equal(drawer, true);
  publish('plugins');
  assert.equal(drawer, false);
  stop();
  assert.equal(subscribers.size, 0);
  drawer = true;
  publish('automations');
  assert.equal(drawer, true);
});

test('opening settings from a resource panel collapses its public owner before returning to chat', () => {
  let expanded = true;
  const calls = [];
  const ctx = { sidebarRight: { isExpanded: () => expanded, toggleExpanded() { expanded = false; calls.push('resource-panel'); } },
    layout: { panelInfo: { getSnapshot: () => ({ activePanelId: null }) }, selectPanel() { calls.push('main-panel'); } } };
  const open = load().createSettingsOpenHandler(ctx, { querySelector: () => null });
  open();
  assert.equal(expanded, false);
  assert.deepEqual(calls, ['resource-panel']);
  open();
  assert.deepEqual(calls, ['resource-panel'], 'Repeated settings requests must not reopen the resource panel');
});

test('back dismisses the controlled session menu, then the revealed row, before the drawer', () => {
  const calls = [];
  let menu = true, row = true;
  const doc = { querySelectorAll: () => menu ? [{ hasAttribute: () => false, getAttribute: () => 'menu' }] : [], querySelector: selector => selector !== '.droy-voice-choice[open]' };
  const touch = { cancelGestures() {}, closeMenu() { if (!menu) return false; menu = false; calls.push('menu'); return true; },
    closeExpandedRow() { if (!row) return false; row = false; calls.push('row'); return true; } };
  const ctx = { layout: { toggleSidebar() { calls.push('drawer'); } } };
  const back = load().createBackHandler(ctx, doc, () => calls.push('modal'), touch);
  assert.equal(back(), true); assert.equal(back(), true); assert.equal(back(), true);
  assert.deepEqual(calls, ['menu', 'row', 'drawer']);
});

test('a DSH dropdown above a settings dialog receives Escape before closing the dialog', () => {
  const calls = [];
  const doc = { querySelector: () => null, querySelectorAll: () => ['dialog', 'menu'].map(role => ({ hasAttribute: () => false, getAttribute: () => role })),
    defaultView: { KeyboardEvent: class { constructor(type, props) { this.type = type; Object.assign(this, props); } } },
    dispatchEvent(event) { calls.push(event.key); } };
  assert.equal(load().createBackHandler({}, doc, () => calls.push('dialog'))(), true);
  assert.deepEqual(calls, ['Escape']);
});

test('resource-panel Back collapses the public owner and preserves the current conversation', () => {
  const calls = [];
  let expanded = true;
  const ctx = { sidebarRight: { isExpanded: () => expanded, toggleExpanded() { expanded = false; calls.push('collapse'); } },
    layout: { panelInfo: { getSnapshot: () => ({ activePanelId: null }) }, selectPanel() { calls.push('main-panel'); } } };
  const doc = { querySelectorAll: () => [], querySelector: () => null };
  const back = load().createBackHandler(ctx, doc, () => calls.push('modal'));
  assert.equal(back(), true);
  assert.equal(expanded, false);
  assert.deepEqual(calls, ['collapse']);
  assert.equal(back(), false);
});

test('Back passes the actual top popup to the controlled bridge before dismissing a background popup', () => {
  const calls = [];
  const top = { hasAttribute: () => false, getAttribute: () => 'menu' };
  const doc = { querySelector: () => null, querySelectorAll: () => [top], defaultView: { KeyboardEvent: class { constructor(type, props) { Object.assign(this, { type }, props); } } },
    dispatchEvent: event => calls.push(event.key) };
  const touch = { cancelGestures() {}, closeMenu(element) { assert.equal(element, top); return false; } };
  assert.equal(load().createBackHandler({}, doc, () => calls.push('modal'), touch)(), true);
  assert.deepEqual(calls, ['Escape']);
});

test('Back closes the controlled image preview before the registered settings modal behind it', () => {
  const calls = [];
  let preview = true, settings = true;
  const dialog = () => ({ hasAttribute: () => false, getAttribute: () => 'dialog' });
  const image = dialog(), modal = dialog();
  const doc = { querySelectorAll: () => [...settings ? [modal] : [], ...preview ? [image] : []], querySelector: () => null };
  const touch = { cancelGestures() {}, closeMenu(top) {
    if (top !== image || !preview) return false;
    preview = false; calls.push('image-preview'); return true;
  } };
  const back = load().createBackHandler({}, doc, () => { settings = false; calls.push('settings'); }, touch);
  assert.equal(back(), true);
  assert.equal(settings, true, 'Preview Back must not close the modal underneath');
  assert.equal(back(), true);
  assert.deepEqual(calls, ['image-preview', 'settings']);
});

function applyFixture(initial = state(), { deferTheme = false, ready = false, reactUseState, onNativeRequest, onNativeReply, reactHooks = {}, windowExtra = {}, locale, initialPanel = null } = {}) {
  const registrations = [];
  const disposers = [];
  const events = new Map();
  const requests = [];
  const listeners = new Map();
  const addListener = (name, listener) => {
    if (!listeners.has(name)) {
      const callbacks = new Set(), dispatch = event => { for (const callback of [...callbacks]) callback(event); };
      dispatch.callbacks = callbacks; listeners.set(name, dispatch);
    }
    listeners.get(name).callbacks.add(listener);
  };
  const removeListener = (name, listener) => {
    const dispatch = listeners.get(name); if (!dispatch) return;
    dispatch.callbacks.delete(listener); if (!dispatch.callbacks.size) listeners.delete(name);
  };
  const document = {
    body: { toggleAttribute() {}, removeAttribute() {} }, documentElement: { dataset: { droyInsetsReady: 'true' }, hasAttribute: () => true }, getElementById: () => ready ? {} : null,
    head: { appendChild() {} },
    createElement: () => ({ remove() {} }), querySelector: () => ready ? { hasAttribute: () => false } : null, querySelectorAll: () => [],
    addEventListener: addListener, removeEventListener: removeListener
  };
  const window = { document, setTimeout, clearTimeout,
    fetch: async () => ({ok:true,json:async()=>({companions:[],settings:{defaultMode:'work'},stickers:[],models:[]})}),
    requestAnimationFrame: callback => setImmediate(callback), cancelAnimationFrame: clearImmediate,
    MutationObserver: class { observe() {} disconnect() {} },
    getComputedStyle: () => ({ getPropertyValue: key => key === '--droy-mobile-layout-ready' ? ready ? '1' : ''
      : theme.active.colorScheme === 'dark' ? '#1b1b1c' : '#fff' }),
    CustomEvent: class { constructor(type, options = {}) { this.type = type; this.detail = options.detail; } },
    addEventListener: addListener, removeEventListener: removeListener,
    dispatchEvent: event => { events.set(event.type, event); listeners.get(event.type)?.(event); },
    DroyNative: { postMessage(message) {
      const request = JSON.parse(message);
      requests.push(request);
      queueMicrotask(() => {
        let error, result;
        try { result = onNativeRequest?.(request, initial); } catch (failure) { error = failure.message; }
        if (request.action === 'themeSync') { delete initial.legacyTheme; delete initial.themeReset; }
        const response = JSON.stringify({ id: request.id, ok: !error, error,
          result: request.action === 'getState' ? initial : result ?? {} });
        const deliver = () => window.DroyNative.onmessage?.({ data: response });
        if (onNativeReply?.(request, deliver) !== true) deliver();
      });
    } }
  };
  Object.assign(window, windowExtra);
  const theme = { preference: 'light', active: { colorScheme: 'light' } };
  const durableTheme = { status: 'ready', value: { preference: 'light' } };
  const themeSubscribers = new Set();
  const acceptTheme = () => {
    durableTheme.value.preference = theme.preference;
    for (const subscriber of themeSubscribers) subscriber();
  };
  const themeWrites = [];
  const ctxEvents = new Map();
  const panelSubscribers = new Set();
  let panelId = initialPanel;
  const ctx = {
    locale,
    slots: { inject(_slot, register) { register(); }, register(options, component) { registrations.push({ options, component }); } },
    theme: { getTheme: () => theme, setTheme(value) {
      themeWrites.push(value); theme.preference = value; theme.active.colorScheme = value === 'dark' ? 'dark' : 'light';
      ctxEvents.get('theme/change')?.(theme);
      if (!deferTheme) acceptTheme();
    } },
    configForms: { get: namespace => {
      assert.equal(namespace, 'ui-theme');
      return { getSnapshot: () => durableTheme, subscribe(listener) { themeSubscribers.add(listener); return () => themeSubscribers.delete(listener); } };
    } },
    layout: { panelInfo: { getSnapshot: () => ({ activePanelId: panelId }),
      subscribe(listener) { panelSubscribers.add(listener); return () => panelSubscribers.delete(listener); } },
      selectPanel(id) { panelId=id;for(const listener of panelSubscribers)listener(); } },
    sidebarRight: { mounted: { getSnapshot: () => undefined, subscribe: () => () => {} }, isExpanded: () => false },
    sessions: { list: { getSnapshot: () => ({phase:'ready',ids:[],byId:{}}), subscribe: () => () => {} } },
    workspaces: { list: { getSnapshot: () => ({phase:'ready',items:[]}), subscribe: () => () => {} }, initializeDefault: async () => undefined },
    uiWorkspace: {startSession() {},connectWorkspace:async()=>undefined,openWorkspace:async()=>undefined,forkSession:async()=>undefined,openSession() {},restoreSelection:async()=>undefined},
    conversation: {blocks:{set() {},storeFor:()=>({getSnapshot:()=>undefined})}},
    on: (event, listener) => { ctxEvents.set(event, listener); return () => ctxEvents.delete(event); },
    effect: callback => disposers.push(callback())
  };
  const React = { createElement: (type, props, ...children) => ({ type, props: props || {}, children }),
    memo: component => component, useMemo: callback => callback(), Fragment: Symbol('fragment'), useId: () => 'droy-model-fixture', useEffect() {}, useState: reactUseState || (value => [value, () => {}]),
    useSyncExternalStore: (_subscribe, get) => get(), ...reactHooks };
  load(window, { React }).apply(ctx);
  return { registrations, requests, themeWrites, window, ctx, listeners, panelSubscribers, acceptTheme,
    refuseTheme() { durableTheme.status = 'unavailable'; for (const subscriber of themeSubscribers) subscriber(); },
    dispose: () => disposers.forEach(dispose => dispose?.()) };
}

test('the saved Work startup preference closes a restored companion panel', async () => {
  const f=applyFixture(state(),{initialPanel:'droy-companions'});
  await flush();await flush();
  assert.equal(f.ctx.layout.panelInfo.getSnapshot().activePanelId,null);
  f.dispose();
});

for(const supersededFailure of [false,true])test('concurrent workspace restoration follows the latest preference when the older read '+(supersededFailure?'fails':'succeeds'), async () => {
  const reads=[];
  const f=applyFixture(state(),{windowExtra:{fetch:async()=>new Promise((resolve,reject)=>reads.push({resolve,reject}))}});
  const restoring=f.ctx.uiWorkspace.restoreSelection(f.ctx.workspaces.list.getSnapshot(),f.ctx.sessions.list.getSnapshot());
  assert.equal(reads.length,2);
  const response=()=>({ok:true,json:async()=>({companions:[],settings:{defaultMode:'work'},stickers:[],models:[]})});
  if(supersededFailure)reads[0].reject(new Error('Fictional superseded request failure'));
  else reads[0].resolve(response());
  await flush();
  reads[1].resolve(response());await restoring;await flush();
  assert.equal(f.ctx.layout.panelInfo.getSnapshot().activePanelId,null);
  assert.equal(f.requests.some(request=>request.action==='notifyError'),false);
  f.dispose();
});

test('a late startup preference does not replace a destination the user already selected', async () => {
  let release;
  const response=new Promise(resolve=>{release=resolve;});
  const f=applyFixture(state(),{initialPanel:'droy-companions',windowExtra:{fetch:async()=>response}});
  f.ctx.layout.selectPanel('skills');f.listeners.get('pointerdown')?.({target:{}});
  release({ok:true,json:async()=>({companions:[],settings:{defaultMode:'work'},stickers:[],models:[]})});
  await flush();await flush();
  assert.equal(f.ctx.layout.panelInfo.getSnapshot().activePanelId,'skills');
  f.dispose();
});

function nodes(tree) {
  if (!tree || typeof tree !== 'object') return [];
  return [tree, ...(tree.children || []).flatMap(nodes)];
}

test('only incomplete installations render the phone setup flow', async () => {
  for (const required of [false, true]) {
    const saved = { ...state(), setup: { required, step: 1, mode: 'dialogue', modeSelected: false,
      identity: { companionName: 'Droy', userAddress: '', personality: '' } } };
    const fixture = applyFixture(saved, { reactHooks: { useRef: current => ({ current }) } });
    await flush();
    const tree = fixture.registrations.find(row => row.options.id === 'droy-setup').component();
    assert.equal(tree?.props['data-droy-setup'] === true, required);
    assert.equal(fixture.requests.some(row => row.action === 'setupUpdate'), false,
      'Rendering a recommendation must not grant capabilities');
    fixture.dispose();
  }
});

test('completed setup releases the shared model portal target', async () => {
  const effects = [], notifications = [];
  const fixture = applyFixture({ ...state(), setup: { required: false, step: 6 } }, { reactHooks: {
    useRef: current => ({ current }), useEffect: effect => effects.push(effect),
  } });
  fixture.window.addEventListener('droy-setup-step', () => notifications.push('released'));
  fixture.registrations.find(row => row.options.id === 'droy-setup').component();
  const cleanup = effects.splice(0).map(effect => effect());
  assert.deepEqual(notifications, ['released']);
  for (const dispose of cleanup) dispose?.();
  fixture.dispose();
});

test('the recommended phone profile is applied only when the welcome choice is confirmed', async () => {
  const saved = { ...state(), setup: { required: true, step: 1, mode: 'dialogue', modeSelected: false } };
  const fixture = applyFixture(saved, { reactHooks: { useRef: current => ({ current }) } });
  await flush();
  const tree = fixture.registrations.find(row => row.options.id === 'droy-setup').component();
  const choices = nodes(tree).filter(row => row.props['data-droy-setup-mode']);
  assert.equal(choices.find(row => row.props.value === 'assistant').props.checked, true);
  assert.deepEqual(choices.map(row => row.props.value), ['assistant', 'dialogue', 'all']);
  assert.equal(choices.find(row => row.props.value === 'all').props.checked, false);
  assert.equal(fixture.requests.some(row => row.action === 'setupUpdate'), false);
  await nodes(tree).find(row => row.props['data-droy-setup-next']).props.onClick();
  await flush();
  assert.deepEqual(fixture.requests.find(row => row.action === 'setupUpdate').args, { mode: 'assistant', step: 2 });
  fixture.dispose();
});

test('model setup requires verified conversation while the final page still uses a separate completion action', async () => {
  for (const passed of [false, true]) {
    const saved = { ...state(), setup: { required: true, step: 2, mode: 'dialogue', modeSelected: true,
      modelVerification: { passed }, identity: {} } };
    const fixture = applyFixture(saved, { reactHooks: { useRef: current => ({ current }) } });
    await flush();
    const tree = fixture.registrations.find(row => row.options.id === 'droy-setup').component();
    assert.equal(nodes(tree).find(row => row.props['data-droy-setup-next']).props.disabled, !passed);
    assert.equal(nodes(tree).some(row => row.props['data-droy-setup-model-settings']), true);
    assert.equal(fixture.requests.some(row => row.action === 'setupUpdate'), false);
    fixture.dispose();
  }
});

test('shared model footer clears unavailable defaults, shows providers and refreshes saved selections', async () => {
  const effects = [], states = [], refs = [], subscriptions = new Map();
  let stateIndex = 0, refIndex = 0;
  const models = [{ provider: 'configured', model: 'text', name: 'Text', providerName: 'Configured provider' }];
  let response = { ok: true, models, selection: { provider: 'old-route', model: 'text' }, selectionAvailable: false };
  const fixture = applyFixture(state(), { windowExtra: { AbortController,
    fetch: async () => ({ ok: true, json: async () => response }) }, reactHooks: {
    useRef(value) { return refs[refIndex++] ?? (refs[refIndex - 1] = { current: value }); },
    useState(value) { const index = stateIndex++; if (!(index in states)) states[index] = value;
      return [states[index], next => { states[index] = typeof next === 'function' ? next(states[index]) : next; }]; },
    useEffect(effect) { effects.push(effect); },
  } });
  fixture.ctx.remote = { $on(event, callback) { subscriptions.set(event, callback); return () => subscriptions.delete(event); } };
  const footer = fixture.registrations.find(row => row.options.id === 'droy-default-model');
  assert.equal(footer.options.name, 'settings.models.footer');
  const render = () => { stateIndex = 0; refIndex = 0; return nodes(footer.component()); };
  render();
  const cleanups = effects.splice(0).map(effect => effect());
  await flush();
  let tree = render();
  assert.equal(tree.find(node => node.props['data-droy-setup-model']).props.value, '');
  assert.equal(tree.find(node => node.props['data-droy-setup-model-test']).props.disabled, true);
  assert.ok(tree.some(node => node.type === 'option' && node.children[0] === 'Configured provider · Text'));
  response = { ...response, selection: { provider: 'configured', model: 'text' }, selectionAvailable: true };
  subscriptions.get('settings/document-updated')(); await flush();
  tree = render();
  assert.equal(tree.find(node => node.props['data-droy-setup-model']).props.value, JSON.stringify(['configured', 'text']));
  assert.equal(tree.find(node => node.props['data-droy-setup-model-test']).props.disabled, false);
  for (const cleanup of cleanups) cleanup?.();
  fixture.dispose();
});

test('a model test result invalidates earlier shared-footer status requests', async () => {
  const effects = [], states = [], refs = [], responses = [], subscriptions = new Map();
  let stateIndex = 0, refIndex = 0, hold = false;
  const status = { ok: true, models: [{ provider: 'configured', model: 'text' }],
    selection: { provider: 'configured', model: 'text' }, modelVerification: { passed: false } };
  const fixture = applyFixture(state(), { windowExtra: { AbortController,
    fetch: () => hold ? new Promise(resolve => responses.push(value => resolve({ ok: true, json: async () => value })))
      : Promise.resolve({ ok: true, json: async () => status }) }, reactHooks: {
    useRef(value) { return refs[refIndex++] ?? (refs[refIndex - 1] = { current: value }); },
    useState(value) { const index = stateIndex++; if (!(index in states)) states[index] = value;
      return [states[index], next => { states[index] = typeof next === 'function' ? next(states[index]) : next; }]; },
    useEffect(effect) { effects.push(effect); },
  } });
  fixture.ctx.remote = { $on(event, callback) { subscriptions.set(event, callback); return () => subscriptions.delete(event); } };
  const component = fixture.registrations.find(row => row.options.id === 'droy-default-model').component;
  const render = () => { stateIndex = 0; refIndex = 0; return nodes(component()); };
  render(); const cleanups = effects.splice(0).map(effect => effect()); await flush();
  hold = true; subscriptions.get('settings/document-updated')();
  const testing = render().find(node => node.props['data-droy-setup-model-test']).props.onClick();
  assert.equal(responses.length, 2);
  responses[1]({ ok: true, modelVerification: { passed: true, toolsPassed: true } });
  await testing; await flush();
  assert.ok(render().some(node => node.props.role === 'status'));
  responses[0](status); await flush();
  assert.ok(render().some(node => node.props.role === 'status'));
  for (const cleanup of cleanups) cleanup?.(); fixture.dispose();
});

test('older setup status cannot overwrite a model test that has already passed', async () => {
  const effects = [], states = [], refs = [], responses = [];
  let stateIndex = 0, refIndex = 0;
  const saved = { ...state(), setup: { required: true, step: 2, mode: 'dialogue', modeSelected: true,
    modelVerification: { passed: false }, identity: {} } };
  const fixture = applyFixture(saved, { windowExtra: { AbortController,
    fetch: path => path === '/droy/companions'
      ? Promise.resolve({ok:true,json:async()=>({companions:[],settings:{defaultMode:'work'},stickers:[],models:[]})})
      : new Promise(resolve => responses.push(value => resolve({ ok: true, json: async () => value }))) }, reactHooks: {
    useRef(value) { return refs[refIndex++] ?? (refs[refIndex - 1] = { current: value }); },
    useState(value) { const index = stateIndex++; if (!(index in states)) states[index] = value;
      return [states[index], next => { states[index] = typeof next === 'function' ? next(states[index]) : next; }]; },
    useEffect(effect) { effects.push(effect); },
  } });
  await flush();
  const component = fixture.registrations.find(row => row.options.id === 'droy-setup').component;
  const render = () => { stateIndex = 0; refIndex = 0; return nodes(component()); };
  render(); const cleanups = effects.splice(0).map(effect => effect());
  assert.equal(responses.length, 1);
  saved.setup.modelVerification.passed = true;
  fixture.window.dispatchEvent(new fixture.window.CustomEvent('droy-model-updated'));
  assert.equal(responses.length, 2);
  responses[1]({ ok: true, state: { ...saved.setup, modelVerification: { passed: true } } }); await flush();
  assert.equal(render().find(node => node.props['data-droy-setup-next']).props.disabled, false);
  responses[0]({ ok: true, state: { ...saved.setup, modelVerification: { passed: false } } }); await flush();
  assert.equal(render().find(node => node.props['data-droy-setup-next']).props.disabled, false);
  for (const cleanup of cleanups) cleanup?.(); fixture.dispose();
});

test('audio settings keep active work stoppable without listing recording history when tools are paused', async () => {
  const saved={...state(),runtimeReady:false,toolsPaused:true,audioCapture:{active:{captureId:'live',state:'recording',source:'microphone',durationMs:2500},
    recent:[{captureId:'saved',state:'completed',source:'playback',scope:'app',packageName:'com.example.player',fileReady:true,durationMs:4000}]},
    audioTranscription:{ready:true,state:'ready',job:{transcriptId:'job',state:'queued',text:''}}};
  const fixture=applyFixture(saved); await flush();
  const component=fixture.registrations.find(item=>item.options.id === 'droy-audio').component;
  const tree=nodes(component());
  const start=tree.find(node=>node.props['data-droy-action'] === 'audioCapture' && node.children[0] === '开始采集');
  const stop=tree.find(node=>node.props['data-droy-audio-stop']);
  assert.equal(start.props.disabled,true); assert.equal(stop.props.disabled,false);
  assert.equal(tree.some(node=>node.props['data-droy-audio-capture'] === 'saved'),false);
  assert.equal(tree.some(node=>['audioPlay','audioExport'].includes(node.props['data-droy-action'])),false);
  assert.equal(tree.some(node=>node.children[0] === '最近录音'),false);
  assert.ok(tree.find(node=>node.children[0] === '停止转写'),'Queued jobs must still be cancellable');
  await stop.props.onClick(); await flush();
  assert.deepEqual(fixture.requests.find(request=>request.action === 'audioCapture').args,{operation:'stop',captureId:'live'});
  fixture.dispose();
});

test('audio recording and transcription controls send separate capture and job requests', async () => {
  const saved={...state(),toolsPaused:false,audioCapture:{active:null,recent:[{captureId:'saved',state:'completed',source:'microphone',fileReady:true}]},
    audioTranscription:{ready:false,state:'not_prepared'}};
  const fixture=applyFixture(saved); await flush();
  const component=fixture.registrations.find(item=>item.options.id === 'droy-audio').component;
  let tree=nodes(component());
  const start=tree.find(node=>node.children[0] === '开始采集');
  assert.equal(start.props.disabled,false,'Recording must not depend on a prepared recognizer');
  await start.props.onClick(); await flush();
  assert.deepEqual(fixture.requests.find(request=>request.action === 'audioCapture').args,
    {operation:'start',source:'microphone',scope:'app',packageName:'',durationMs:0,saveFile:true});
  saved.audioTranscription={ready:true,state:'ready'};
  saved.audioCapture.active={captureId:'live',state:'recording',source:'microphone',durationMs:2500};
  await fixture.window.DroyMobile.refresh();
  tree=nodes(component());
  assert.equal(tree.some(node=>node.props['data-droy-audio-capture'] === 'saved'),false);
  const transcribe=tree.find(node=>node.children[0] === '实时转写');
  assert.equal(transcribe.props.disabled,false);
  await transcribe.props.onClick(); await flush();
  assert.deepEqual(fixture.requests.find(request=>request.action === 'audioTranscription').args,{operation:'transcribe',captureId:'live'});
  fixture.dispose();
});

test('manual capture keeps only this page’s completed receipt available for playback, export, transcription and deletion', async () => {
  const saved={...state(),toolsPaused:false,audioCapture:{active:null,recent:[{captureId:'old',state:'completed',source:'microphone',fileReady:true}]},
    audioTranscription:{ready:true,state:'ready'}};
  const values=[]; let hook=0, sequence=0;
  const fixture=applyFixture(saved,{
    reactUseState(initial) {
      const index=hook++;
      if (!(index in values)) values[index]=initial;
      return [values[index],value=>{values[index]=value;}];
    },
    windowExtra:{confirm:()=>true},
    onNativeRequest({action,args}) {
      if (action !== 'audioCapture') return;
      if (args.operation === 'start') return saved.audioCapture.active={captureId:`page-${++sequence}`,state:'recording',source:'microphone',fileReady:false};
      if (args.operation === 'stop') {
        const completed={...saved.audioCapture.active,state:'completed',fileReady:true,durationMs:3000};
        saved.audioCapture.active=null; saved.audioCapture.recent.unshift(completed); return completed;
      }
      if (args.operation === 'delete') saved.audioCapture.recent=saved.audioCapture.recent.filter(item=>item.captureId !== args.captureId);
    }
  });
  await flush();
  const component=fixture.registrations.find(item=>item.options.id === 'droy-audio').component;
  const render=()=>{hook=0;return nodes(component());};
  let tree=render();
  assert.equal(tree.some(node=>node.props['data-droy-audio-capture']),false);
  await tree.find(node=>node.props['data-droy-audio-start']).props.onClick(); await flush();
  tree=render();
  assert.equal(tree.find(node=>node.props['data-droy-audio-capture']).props['data-droy-audio-capture'],'page-1');
  await tree.find(node=>node.props['data-droy-audio-stop']).props.onClick(); await flush();
  tree=render();
  const card=tree.find(node=>node.props['data-droy-audio-current-result']);
  assert.equal(card.props['data-droy-audio-capture'],'page-1');
  assert.equal(tree.some(node=>node.props['data-droy-audio-capture'] === 'old'),false);
  for (const [label,action] of [['播放','audioPlay'],['导出录音','audioExport'],['转成文字','audioTranscription']]) {
    await tree.find(node=>node.children[0] === label).props.onClick(); await flush();
    assert.equal(fixture.requests.filter(request=>request.action === action).at(-1).args.captureId,'page-1');
  }
  await tree.find(node=>node.props['data-droy-audio-delete']).props.onClick(); await flush();
  assert.equal(render().some(node=>node.props['data-droy-audio-current-result']),false);
  assert.equal(saved.audioCapture.recent[0].captureId,'old');
  await render().find(node=>node.props['data-droy-audio-start']).props.onClick(); await flush();
  await render().find(node=>node.props['data-droy-audio-stop']).props.onClick(); await flush();
  assert.equal(render().find(node=>node.props['data-droy-audio-current-result']).props['data-droy-audio-capture'],'page-2');
  values.length=0; // A newly mounted settings page does not retain the previous page's receipt.
  assert.equal(render().some(node=>node.props['data-droy-audio-capture']),false);
  fixture.dispose();
});

test('shared audio panel observes automatic offline initialization without download or cancellation actions', async () => {
  const saved={...state(),toolsPaused:false,audioCapture:{active:null,recent:[]},
    audioTranscription:{ready:false,state:'not_prepared'}};
  const fixture=applyFixture(saved); await flush();
  const component=fixture.registrations.find(item=>item.options.id === 'droy-audio').component;
  for (const status of ['not_prepared','preparing','ready']) {
    saved.audioTranscription={ready:status === 'ready',state:status,totalBytes:100,downloadedBytes:50};
    await fixture.window.DroyMobile.refresh();
    const tree=nodes(component());
    assert.equal(tree.some(node=>node.children[0] === '下载并准备模型' || node.children[0] === '取消准备'),false);
    assert.equal(tree.find(node=>node.props['data-droy-action'] === 'audioImport').props.disabled,status !== 'ready');
    assert.equal(tree.find(node=>node.children[0] === '开始采集').props.disabled,false);
    if (status === 'preparing') {
      const progress=tree.find(node=>node.type === 'progress');
      assert.equal(progress.props['aria-label'],'识别模型初始化进度');
      assert.equal(progress.props.value,50);
    }
  }
  assert.equal(fixture.requests.some(request=>request.action === 'audioTranscription'),false);
  fixture.dispose();
});

test('plugin contributes to existing settings, migrates old theme once, then follows DSH changes', async () => {
  const fixture = applyFixture({ ...state(), legacyTheme: 'dark' });
  await flush();
  assert.deepEqual(fixture.themeWrites, ['dark']);
  assert.deepEqual(fixture.requests.filter(request => request.action === 'themeSync').map(request => request.args), [
    { preference: 'dark', colorScheme: 'dark', backgroundColor: '#1B1B1C' }
  ]);
  fixture.ctx.theme.setTheme('system');
  await flush();
  assert.equal(fixture.requests.filter(request => request.action === 'themeSync').at(-1).args.preference, 'system');
  await fixture.window.DroyMobile.refresh();
  assert.deepEqual(fixture.themeWrites, ['dark', 'system']);
  fixture.window.DroyMobile.openPermissions();
  assert.equal(fixture.window.DroyMobile.handleBack(), false);
  const sections = fixture.registrations.filter(item => item.options.name === 'settings.section');
  assert.equal(sections.length, 1);
  assert.equal(sections[0].options.id, 'droy-permissions');
  assert.equal(sections[0].options.label(), '权限设置');
  const permissionTree = nodes(sections[0].component());
  const toggleRow = permissionTree.find(node => node.props['data-droy-preference'] === 'allowContacts');
  const toggle = nodes(toggleRow).find(node => node.type === 'dsh-switch');
  assert.equal(toggle.props.checked, true);
  assert.equal(toggle.props.label, '读取联系人');
  const contactState = permissionTree.find(node => node.props['data-droy-permission-status'] === 'contacts');
  assert.equal(contactState.children[0], '待授权');
  assert.ok(permissionTree.find(node => node.props['data-droy-action'] === 'revokeFile'));
  assert.ok(!permissionTree.find(node => String(node.children).includes('练习')));
  fixture.dispose();
});

test('settings-open listener follows the shell event and is removed on plugin disposal', async () => {
  const fixture = applyFixture();
  await flush();
  let panel = 'workspace';
  let drawer = true;
  const calls = [];
  fixture.window.document.querySelector = () => drawer;
  fixture.ctx.layout.toggleSidebar = () => { drawer = false; calls.push('drawer'); };
  fixture.ctx.layout.panelInfo.getSnapshot = () => ({ activePanelId: panel });
  fixture.ctx.layout.selectPanel = id => { panel = id; calls.push('chat'); };
  fixture.window.dispatchEvent(new fixture.window.CustomEvent('droy-mobile-settings-open'));
  assert.deepEqual(calls, ['drawer', 'chat']);
  fixture.dispose();
  assert.equal(fixture.listeners.has('droy-mobile-settings-open'), false);
  assert.equal(fixture.panelSubscribers.size, 0, 'Panel navigation must unsubscribe on disposal');
});

test('general and permission controls reuse the upstream primitives and preserve independent preferences', async () => {
  const nativeState = state();
  nativeState.groups.push({ preference: 'allowCalendar', label: '读取日历', section: '个人数据', enabled: false });
  const fixture = applyFixture(nativeState);
  await flush();
  const general = fixture.registrations.filter(item => item.options.name === 'settings.general.item').flatMap(item => nodes(item.component()));
  assert.equal(general.find(node => node.props.id === 'droy-assistant-name').type, 'dsh-input');
  assert.equal(general.find(node => node.props['data-droy-action'] === 'openDiagnostics').type, 'dsh-button');
  assert.equal(general.find(node => node.type === 'dsh-switch').props.label, '浏览器加密 DNS');
  const permissions = nodes(fixture.registrations.find(item => item.options.id === 'droy-permissions').component());
  const contact = nodes(permissions.find(node => node.props['data-droy-preference'] === 'allowContacts')).find(node => node.type === 'dsh-switch');
  const calendar = nodes(permissions.find(node => node.props['data-droy-preference'] === 'allowCalendar')).find(node => node.type === 'dsh-switch');
  assert.equal(contact.props.checked, true);
  assert.equal(calendar.props.checked, false);
  const action = permissions.find(node => node.props['data-droy-permission'] === 'contacts');
  const diagnostic = general.find(node => node.props['data-droy-action'] === 'openDiagnostics');
  assert.equal(action.type, diagnostic.type);
  assert.equal(action.props.variant, diagnostic.props.variant);
  assert.equal(action.props.size, diagnostic.props.size);
  contact.props.onChange(false);
  await flush();
  assert.deepEqual(fixture.requests.find(request => request.action === 'setPreference').args, { key: 'allowContacts', value: false });
  assert.equal(fixture.requests.some(request => request.args.key === 'allowCalendar'), false);
  fixture.dispose();
});

for (const active of ['zh', 'en']) test(`permission presets require a cancellable explanation before saving (${active})`, async () => {
  const hooks = hookFixture();
  const saved = state();
  const fixture = applyFixture(saved, { reactHooks: hooks.hooks,
    locale: { getSnapshot: () => ({ active }), subscribe: () => () => {} },
    onNativeRequest({ action, args }) {
      if (action === 'configurePermissions') saved.selectedPreset = args.mode;
    } });
  await flush();
  const component = fixture.registrations.find(item => item.options.id === 'droy-permissions').component;
  const render = () => nodes(hooks.render(component));
  for (const mode of ['all', 'dialogue', 'assistant']) {
    let tree = render();
    assert.equal(tree.some(node => ['resumeOperations', 'pauseOperations'].includes(node.props['data-droy-action'])), false);
    assert.equal(tree.some(node => node.children.includes(active === 'zh' ? '手机权限' : 'Phone permissions')), false);
    const count = fixture.requests.filter(row => row.action === 'configurePermissions').length;
    tree.find(node => node.props['data-droy-permission-preset'] === mode).props.onClick();
    tree = render();
    const confirmation = tree.find(node => node.type?.name === 'PermissionPresetConfirmation');
    assert.ok(confirmation);
    let dialog = nodes(confirmation.type(confirmation.props));
    assert.equal(dialog.find(node => node.props['data-droy-permission-confirmation']).props['data-droy-permission-confirmation'], mode);
    assert.ok(dialog.some(node => node.children.some(child => typeof child === 'string' && (active === 'zh' ? /系统|Android/.test(child) : /Android/.test(child)))));
    assert.equal(fixture.requests.filter(row => row.action === 'configurePermissions').length, count);
    dialog.find(node => node.props['data-droy-permission-cancel']).props.onClick();
    assert.equal(render().some(node => node.type?.name === 'PermissionPresetConfirmation'), false);
    assert.equal(fixture.requests.filter(row => row.action === 'configurePermissions').length, count);
    render().find(node => node.props['data-droy-permission-preset'] === mode).props.onClick();
    const confirmed = render().find(node => node.type?.name === 'PermissionPresetConfirmation');
    dialog = nodes(confirmed.type(confirmed.props));
    await dialog.find(node => node.props['data-droy-permission-confirm']).props.onClick();
    await flush();
    assert.equal(saved.selectedPreset, mode);
    assert.deepEqual(fixture.requests.filter(row => row.action === 'configurePermissions').at(-1).args, { mode });
    assert.equal(render().some(node => node.type?.name === 'PermissionPresetConfirmation'), false);
  }
  hooks.dispose(); fixture.dispose();
});

test('failed permission preset save preserves the explanation and does not start a second authorization request', async () => {
  const hooks = hookFixture();
  const fixture = applyFixture(state(), { reactHooks: hooks.hooks, onNativeRequest({ action }) {
    if (action === 'configurePermissions') throw new Error('Permission configuration could not be saved');
  } });
  await flush();
  const component = fixture.registrations.find(item => item.options.id === 'droy-permissions').component;
  const render = () => nodes(hooks.render(component));
  render().find(node => node.props['data-droy-permission-preset'] === 'all').props.onClick();
  let confirmation = render().find(node => node.type?.name === 'PermissionPresetConfirmation');
  await nodes(confirmation.type(confirmation.props)).find(node => node.props['data-droy-permission-confirm']).props.onClick();
  await flush();
  confirmation = render().find(node => node.type?.name === 'PermissionPresetConfirmation');
  assert.ok(confirmation);
  assert.equal(confirmation.props.snapshot.error, 'Permission configuration could not be saved');
  assert.equal(fixture.requests.filter(row => row.action === 'configurePermissions').length, 1);
  assert.equal(fixture.requests.some(row => row.action === 'requestPermission'), false);
  hooks.dispose(); fixture.dispose();
});

test('permission queue can be stopped and onboarding uses the same confirmed presets', async () => {
  const saved = { ...state(), permissionSetup: { active: true, current: 'contacts', pending: ['usage'] },
    setup: { required: true, step: 5, mode: 'all', capabilities: {} } };
  const fixture = applyFixture(saved, { reactHooks: { useRef: current => ({ current }) } });
  await flush();
  const component = fixture.registrations.find(item => item.options.id === 'droy-permissions').component;
  const tree = nodes(component());
  assert.ok(tree.find(node => node.props['data-droy-permission-queue']));
  await tree.find(node => node.props['data-droy-action'] === 'cancelPermissionSetup').props.onClick();
  await flush();
  assert.equal(fixture.requests.filter(row => row.action === 'cancelPermissionSetup').length, 1);
  const setup = nodes(fixture.registrations.find(item => item.options.id === 'droy-setup').component());
  assert.ok(setup.find(node => node.type === component));
  assert.equal(setup.some(node => node.props['data-droy-setup-dialogue']), false);
  fixture.dispose();
});

test('screen display defaults on independently of the pet and preserves its explicit saved preference', async () => {
  const nativeState = { ...state(), petEnabled: false, petExternalDisplay: 'off', petInternalDisplay: 'off',
    automationScreenDisplayReady: true };
  const fixture = applyFixture(nativeState, { onNativeRequest(request, saved) {
    if (request.action === 'setPreference') saved[request.args.key] = request.args.value;
  } });
  await flush();
  const registration = fixture.registrations.find(item => item.options.id === 'droy-automation-screen-display');
  assert.ok(registration.options.order > fixture.registrations.find(item => item.options.id === 'droy-pet').options.order);
  assert.ok(registration.options.order < fixture.registrations.find(item => item.options.id === 'droy-return-on-completion').options.order);
  const render = () => nodes(registration.component());
  const toggle = () => render().find(node => node.type === 'dsh-switch');
  assert.equal(toggle().props.label, '自动化操作屏幕显示');
  assert.equal(toggle().props.className, 'droy-touch-switch');
  assert.equal(toggle().props.checked, true);
  assert.equal(toggle().props.disabled, false);
  assert.ok(render().some(node => node.children.includes('当Droy操作手机时，屏幕边缘会发光提醒')));
  assert.equal(render().some(node => node.props['data-droy-screen-display-permission']), false);
  toggle().props.onChange(false);
  await flush();
  assert.deepEqual(fixture.requests.find(request => request.action === 'setPreference').args,
    { key: 'automationScreenDisplay', value: false });
  assert.equal(toggle().props.checked, false);
  await fixture.window.DroyMobile.refresh();
  assert.equal(toggle().props.checked, false);
  assert.equal(nativeState.petEnabled, false);
  assert.equal(nativeState.petExternalDisplay, 'off');
  assert.equal(nativeState.petInternalDisplay, 'off');
  assert.equal(fixture.requests.some(request => request.action === 'requestPermission'), false);
  toggle().props.onChange(true);
  await flush();
  assert.equal(toggle().props.checked, true);
  fixture.dispose();
});

test('screen display distinguishes its preference from display readiness and requests permission only on demand', async () => {
  const nativeState = { ...state(), automationScreenDisplay: false, automationScreenDisplayReady: false };
  const fixture = applyFixture(nativeState, { onNativeRequest(request, saved) {
    if (request.action === 'setPreference') saved[request.args.key] = request.args.value;
  } });
  await flush();
  const component = fixture.registrations.find(item => item.options.id === 'droy-automation-screen-display').component;
  const render = () => nodes(component());
  assert.equal(render().find(node => node.type === 'dsh-switch').props.checked, false);
  assert.equal(render().some(node => node.props['data-droy-screen-display-permission']), false);
  render().find(node => node.type === 'dsh-switch').props.onChange(true);
  await flush();
  assert.equal(fixture.requests.some(request => request.action === 'requestPermission'), false);
  assert.ok(render().some(node => node.props.role === 'status'
    && node.children.includes('允许在其他应用上显示后，即可显示屏幕光效。')));
  const permission = render().find(node => node.props['data-droy-permission'] === 'automationScreenDisplay');
  assert.equal(permission.children[0], '开启显示权限');
  permission.props.onClick();
  await flush();
  assert.deepEqual(fixture.requests.find(request => request.action === 'requestPermission').args, { name: 'petOverlay' });
  assert.ok(render().some(node => node.props['data-droy-screen-display-permission']),
    'Opening the system permission screen does not establish that access was granted');
  nativeState.automationScreenDisplayReady = true;
  await fixture.window.DroyMobile.refresh();
  assert.equal(render().some(node => node.props['data-droy-screen-display-permission']), false);
  assert.equal(render().find(node => node.type === 'dsh-switch').props.checked, true);
  fixture.dispose();
});

test('screen display surfaces a native overlay failure only while enabled and clears it after recovery', async () => {
  const nativeState = { ...state(), automationScreenDisplay: true, automationScreenDisplayReady: true,
    automationScreenDisplayError: '' };
  const fixture = applyFixture(nativeState, { onNativeRequest(request, saved) {
    if (request.action === 'setPreference') saved[request.args.key] = request.args.value;
  } });
  await flush();
  const component = fixture.registrations.find(item => item.options.id === 'droy-automation-screen-display').component;
  const render = () => nodes(component());
  const alert = () => render().find(node => node.props.role === 'alert');
  assert.equal(alert(), undefined);
  nativeState.automationScreenDisplayError = '屏幕光效暂时无法显示，请重新开启';
  await fixture.window.DroyMobile.refresh();
  assert.equal(alert().props.className, 'droy-error');
  assert.equal(alert().children[0], nativeState.automationScreenDisplayError);
  render().find(node => node.type === 'dsh-switch').props.onChange(false);
  await flush();
  assert.equal(alert(), undefined);
  render().find(node => node.type === 'dsh-switch').props.onChange(true);
  await flush();
  assert.equal(alert().children[0], nativeState.automationScreenDisplayError);
  nativeState.automationScreenDisplayError = '';
  await fixture.window.DroyMobile.refresh();
  assert.equal(alert(), undefined);
  fixture.dispose();
});

test('a refused screen display preference keeps the confirmed state and surfaces the native failure', async () => {
  const nativeState = { ...state(), automationScreenDisplay: true, automationScreenDisplayReady: true };
  const fixture = applyFixture(nativeState, { onNativeRequest(request) {
    if (request.action === 'setPreference') throw new Error('屏幕显示设置未保存，请重试');
  } });
  await flush();
  const component = fixture.registrations.find(item => item.options.id === 'droy-automation-screen-display').component;
  nodes(component()).find(node => node.type === 'dsh-switch').props.onChange(false);
  await flush();
  const tree = nodes(component());
  assert.equal(tree.find(node => node.type === 'dsh-switch').props.checked, true);
  assert.match(tree.find(node => typeof node.type === 'function').props.snapshot.error, /屏幕显示设置未保存/);
  fixture.dispose();
});

test('return after completion defaults off independently of the pet and preserves an explicit choice', async () => {
  const nativeState = { ...state(), petEnabled: false, petPermissionGranted: false };
  const fixture = applyFixture(nativeState, { onNativeRequest(request, saved) {
    if (request.action === 'setPreference') saved[request.args.key] = request.args.value;
  } });
  await flush();
  const component = fixture.registrations.find(item => item.options.id === 'droy-return-on-completion').component;
  const toggle = () => nodes(component()).find(node => node.type === 'dsh-switch');
  assert.equal(toggle().props.label, '任务完成后总是返回DroyAPP');
  assert.equal(toggle().props.checked, false);
  assert.equal(toggle().props.disabled, false);
  toggle().props.onChange(true);
  await flush();
  assert.deepEqual(fixture.requests.find(request => request.action === 'setPreference').args,
    { key: 'returnToDroyOnCompletion', value: true });
  assert.equal(toggle().props.checked, true);
  await fixture.window.DroyMobile.refresh();
  assert.equal(toggle().props.checked, true);
  toggle().props.onChange(false);
  await flush();
  assert.equal(nativeState.returnToDroyOnCompletion, false);
  assert.equal(nativeState.petEnabled, false);
  assert.equal(fixture.requests.some(request => request.action === 'requestPermission'), false);
  fixture.dispose();
});

test('a refused completion-return preference keeps the saved choice and exposes the failure', async () => {
  const nativeState = { ...state(), returnToDroyOnCompletion: true };
  const fixture = applyFixture(nativeState, { onNativeRequest(request) {
    if (request.action === 'setPreference') throw new Error('设置未保存，请重试');
  } });
  await flush();
  const component = fixture.registrations.find(item => item.options.id === 'droy-return-on-completion').component;
  nodes(component()).find(node => node.type === 'dsh-switch').props.onChange(false);
  await flush();
  const tree = nodes(component());
  assert.equal(tree.find(node => node.type === 'dsh-switch').props.checked, true);
  assert.match(tree.find(node => typeof node.type === 'function').props.snapshot.error, /设置未保存/);
  fixture.dispose();
});

test('pet display modes are independent, preserve grants, and allow in-app preview without an overlay grant', async () => {
  const nativeState = { ...state(), petExternalDisplay: 'always', petInternalDisplay: 'off', petName: 'Droy', petPermissionGranted: false };
  const fixture = applyFixture(nativeState, { onNativeRequest(request, saved) {
    if (request.action === 'setPreference') saved[request.args.key] = request.args.value;
  } });
  await flush();
  const component = fixture.registrations.find(item => item.options.id === 'droy-pet').component;
  const render = () => nodes(component());
  const mode = (key, value) => render().find(node => node.props['data-droy-pet-mode'] === key && node.props['data-value'] === value);
  assert.equal(render().some(node => node.type === 'dsh-switch'), false);
  assert.equal(mode('petExternalDisplay', 'always').props['aria-pressed'], true);
  assert.equal(mode('petInternalDisplay', 'off').props['aria-pressed'], true);
  assert.equal(render().find(node => node.props['data-droy-action'] === 'previewPet').props.disabled, false);
  render().find(node => node.props['data-droy-action'] === 'previewPet').props.onClick();
  await flush();
  assert.ok(fixture.requests.some(request => request.action === 'previewPet'));
  assert.equal(fixture.requests.some(request => request.action === 'requestPermission'), false);
  mode('petExternalDisplay', 'adaptive').props.onClick();
  await flush();
  assert.equal(mode('petExternalDisplay', 'adaptive').props['aria-pressed'], true);
  assert.equal(nativeState.petInternalDisplay, 'off');
  mode('petInternalDisplay', 'always').props.onClick();
  await flush();
  assert.equal(nativeState.petExternalDisplay, 'adaptive');
  assert.equal(nativeState.petInternalDisplay, 'always');
  render().find(node => node.props['data-droy-permission'] === 'petOverlay').props.onClick();
  await flush();
  assert.deepEqual(fixture.requests.find(request => request.action === 'requestPermission').args, { name: 'petOverlay' });
  nativeState.petPermissionGranted = true;
  await fixture.window.DroyMobile.refresh();
  assert.equal(render().some(node => node.props['data-droy-permission'] === 'petOverlay'), false);
  assert.equal(fixture.requests.some(request => request.action === 'pauseOperations'), false);
  fixture.dispose();
});

test('pet name stays editable while the appearance is off and saves independently of the page title', async () => {
  const nativeState = { ...state(), petEnabled: false, petName: 'Droy', petPermissionGranted: true };
  let draft;
  const fixture = applyFixture(nativeState, {
    reactUseState: initial => [draft ?? initial, value => { draft = value; }],
    onNativeRequest(request, saved) { if (request.action === 'setPreference') saved[request.args.key] = request.args.value; }
  });
  await flush();
  const component = fixture.registrations.find(item => item.options.id === 'droy-pet').component;
  const render = () => nodes(component());
  let tree = render();
  const input = tree.find(node => node.props.id === 'droy-pet-name');
  assert.equal(input.props.disabled, false);
  assert.equal(input.props.maxLength, 40);
  assert.equal(tree.find(node => node.props['data-droy-action'] === 'previewPet').props.disabled, false);
  for (const invalid of ['   ', '猫'.repeat(41)]) {
    input.props.onChange({ target: { value: invalid } });
    assert.equal(render().find(node => node.props['data-droy-action'] === 'savePetName').props.disabled, true);
  }
  input.props.onChange({ target: { value: '  墨墨  ' } });
  tree = render();
  const save = tree.find(node => node.props['data-droy-action'] === 'savePetName');
  assert.equal(save.props.disabled, false);
  let editingEnded = false;
  save.props.onClick({ currentTarget: { focus() { editingEnded = true; } } });
  assert.equal(editingEnded, true);
  await flush();
  assert.deepEqual(fixture.requests.find(request => request.action === 'setPreference').args, { key: 'petName', value: '墨墨' });
  assert.equal(nativeState.petName, '墨墨');
  assert.equal(nativeState.assistantName, 'Droy');
  assert.equal(nativeState.petEnabled, false);
  fixture.dispose();
});

test('a refused pet display setting keeps the confirmed mode and shows the native error', async () => {
  const nativeState = { ...state(), petExternalDisplay: 'always', petInternalDisplay: 'off', petName: 'Droy', petPermissionGranted: true };
  const fixture = applyFixture(nativeState, { onNativeRequest(request) {
    if (request.action === 'setPreference') throw new Error('伙伴设置未保存，请重试');
  } });
  await flush();
  const component = fixture.registrations.find(item => item.options.id === 'droy-pet').component;
  nodes(component()).find(node => node.props['data-droy-pet-mode'] === 'petExternalDisplay' && node.props['data-value'] === 'off').props.onClick();
  await flush();
  const tree = nodes(component());
  assert.equal(tree.find(node => node.props['data-droy-pet-mode'] === 'petExternalDisplay' && node.props['data-value'] === 'always').props['aria-pressed'], true);
  assert.equal(nativeState.petInternalDisplay, 'off');
  const feedback = tree.find(node => typeof node.type === 'function' && node.props.snapshot);
  assert.equal(feedback.props.snapshot.pending, null);
  assert.match(feedback.props.snapshot.error, /伙伴设置未保存/);
  fixture.dispose();
});

test('pet context follows public session and panel state, coalesces changes, and never refreshes settings', async () => {
  const client = load();
  const callbacks = { mounted: new Set(), panel: new Set(), visibility: new Set() };
  let taskId = 'chat-a', expanded = false, finishWrite;
  const calls = [];
  const controller = client.createController({ request(action, args) {
    calls.push({ action, args });
    if (action === 'setPreference') return new Promise(resolve => { finishWrite = resolve; });
    return Promise.resolve({});
  } });
  const source = name => ({ subscribe(callback) { callbacks[name].add(callback); return () => callbacks[name].delete(callback); } });
  const ctx = { sidebarRight: { mounted: { ...source('mounted'), getSnapshot: () => taskId }, isExpanded: () => expanded },
    layout: { panelInfo: source('panel') } };
  const doc = { visibilityState: 'visible',
    addEventListener(_name, callback) { callbacks.visibility.add(callback); },
    removeEventListener(_name, callback) { callbacks.visibility.delete(callback); } };
  const notify = name => { for (const callback of callbacks[name]) callback(); };
  const stop = client.observePetContext(ctx, doc, controller);
  await flush();
  assert.deepEqual(JSON.parse(JSON.stringify(calls[0])), { action: 'petContext', args: { taskId: 'chat-a', chatVisible: true } });
  notify('panel'); notify('mounted'); await flush();
  assert.equal(calls.length, 1, 'unchanged view state must not send another native request');
  expanded = true; notify('panel'); await flush();
  assert.equal(calls.at(-1).args.chatVisible, false, 'a full-window resource/browser panel covers the chat');
  expanded = false; notify('panel'); await flush();
  assert.equal(calls.at(-1).args.chatVisible, true);
  const writing = controller.run('setPreference', { key: 'petName', value: '墨墨' }, false);
  await Promise.resolve();
  taskId = 'chat-b'; notify('mounted');
  taskId = 'chat-c'; notify('mounted');
  finishWrite({}); await writing; await flush();
  assert.equal(calls.at(-1).args.taskId, 'chat-c');
  assert.equal(calls.some(call => call.args.taskId === 'chat-b'), false, 'busy bridge only needs the latest context');
  doc.visibilityState = 'hidden'; notify('visibility'); await flush();
  assert.equal(calls.at(-1).args.chatVisible, false);
  doc.visibilityState = 'visible'; notify('visibility'); await flush();
  assert.equal(calls.at(-1).args.chatVisible, true);
  taskId = undefined; notify('mounted'); await flush();
  assert.deepEqual(JSON.parse(JSON.stringify(calls.at(-1).args)), { taskId: '', chatVisible: false });
  assert.equal(calls.some(call => call.action === 'getState'), false);
  stop();
  assert.ok(Object.values(callbacks).every(set => set.size === 0));
  controller.dispose();
});

test('pending reset overrides old theme and explicit personalization reset uses DSH system preference', async () => {
  const nativeState = { ...state(), legacyTheme: 'dark', themeReset: 'system' };
  const fixture = applyFixture(nativeState);
  await flush();
  assert.deepEqual(fixture.themeWrites, ['system']);
  nativeState.assistantName = 'Reset name';
  nativeState.browserEncryptedDns = false;
  await fixture.window.DroyMobile.resetPersonalization();
  assert.equal(fixture.requests.filter(request => request.action === 'themeSync').at(-1).args.preference, 'system');
  const name = fixture.registrations.find(item => item.options.id === 'droy-assistant-name');
  const nameInput = nodes(name.component()).find(node => node.props.id === 'droy-assistant-name');
  assert.equal(nameInput.props.value, 'Reset name');
  const dns = fixture.registrations.find(item => item.options.id === 'droy-browser-dns');
  assert.equal(nodes(dns.component()).find(node => node.type === 'dsh-switch').props.checked, false);
  fixture.dispose();
});

test('legacy migration waits for durable Host acceptance before clearing native migration markers', async () => {
  const nativeState = { ...state(), legacyTheme: 'dark' };
  const fixture = applyFixture(nativeState, { deferTheme: true });
  await flush();
  assert.deepEqual(fixture.themeWrites, ['dark']);
  assert.equal(fixture.requests.filter(request => request.action === 'themeSync').length, 0);
  assert.equal(nativeState.legacyTheme, 'dark');
  fixture.acceptTheme();
  await flush();
  assert.equal(fixture.requests.filter(request => request.action === 'themeSync').length, 1);
  assert.equal(nativeState.legacyTheme, undefined);
  fixture.dispose();
});

test('refused theme migration leaves native markers intact and displays a recoverable error', async () => {
  const nativeState = { ...state(), themeReset: 'system' };
  const fixture = applyFixture(nativeState, { deferTheme: true });
  await flush();
  fixture.refuseTheme();
  await flush();
  assert.equal(fixture.requests.filter(request => request.action === 'themeSync').length, 0);
  assert.equal(nativeState.themeReset, 'system');
  const name = fixture.registrations.find(item => item.options.id === 'droy-assistant-name');
  const feedback = nodes(name.component()).find(node => typeof node.type === 'function');
  assert.match(feedback.props.snapshot.error, /主题设置暂不可用/);
  fixture.dispose();
});

test('theme write timeout does not acknowledge an unconfirmed preference', async () => {
  const ctx = { configForms: { get: () => ({ getSnapshot: () => ({ status: 'ready', value: { preference: 'light' } }), subscribe: () => () => {} }) },
    theme: { setTheme() {}, getTheme: () => ({ preference: 'dark' }) } };
  await assert.rejects(load().setThemeDurably(ctx, { setTimeout, clearTimeout }, 'dark', 5), /尚未保存/);
});

test('native receives the actual DSH body palette before the single UI-ready acknowledgement', async () => {
  const fixture = applyFixture(state(), { ready: true });
  await flush();
  await flush();
  const actions = fixture.requests.map(request => request.action);
  assert.equal(actions.filter(action => action === 'uiReady').length, 1);
  assert.ok(actions.indexOf('themeSync') < actions.indexOf('uiReady'));
  assert.equal(fixture.requests.find(request => request.action === 'themeSync').args.backgroundColor, '#FFFFFF');
  assert.equal(fixture.window.document.documentElement.dataset.droyUiReady, 'true');
  fixture.dispose();
});

test('theme palette conversion uses body tokens and rejects unresolved or transparent colors', () => {
  const client = load();
  const body = {};
  let value = '#1b1b1c';
  const target = { document: { body }, getComputedStyle(element) {
    assert.equal(element, body);
    return { getPropertyValue: property => { assert.equal(property, '--dsw-alias-bg-base'); return value; } };
  } };
  assert.equal(client.readThemeBackground(target), '#1B1B1C');
  value = 'rgb(27, 27, 28)';
  assert.equal(client.readThemeBackground(target), '#1B1B1C');
  value = '#fff';
  assert.equal(client.readThemeBackground(target), '#FFFFFF');
  for (const invalid of ['', 'transparent', 'var(--missing)', 'rgba(0,0,0,.5)', 'rgb(256, 0, 0)']) {
    value = invalid;
    assert.throws(() => client.readThemeBackground(target), /颜色尚未就绪/);
  }
});

function readinessFixture() {
  const frames = new Map();
  const state = { mobile: true, style: false, css: false, frame: true, drawer: false, menu: true, composer: true };
  const root = { dataset: { droyInsetsReady: 'true' }, hasAttribute: () => state.mobile };
  let check;
  let sequence = 0;
  let connected = false;
  const window = {
    document: { documentElement: root, getElementById: () => state.style ? {} : null,
      querySelector: selector => selector === '[data-droy-frame]' ? state.frame ? { hasAttribute: () => state.drawer } : null
        : selector === '[data-droy-im-screen]' ? state.im : selector === '[data-droy-menu]' ? state.menu : selector === '[data-composer-input]' ? state.composer : null },
    getComputedStyle: () => ({ getPropertyValue: () => state.css ? '1' : '' }),
    requestAnimationFrame(callback) { const id = ++sequence; frames.set(id, callback); return id; },
    cancelAnimationFrame: id => frames.delete(id),
    MutationObserver: class { constructor(callback) { check = callback; } observe() { connected = true; } disconnect() { connected = false; } }
  };
  return { window, state, root, get connected() { return connected; }, changed: () => check(),
    paint() { const callbacks = [...frames.values()]; frames.clear(); callbacks.forEach(callback => callback()); },
    get pendingFrames() { return frames.size; } };
}

test('IM starts through the native loading screen without a hidden work composer', async () => {
  const fixture=readinessFixture();let ready=0;
  Object.assign(fixture.state,{style:true,css:true,menu:false,composer:false,im:{}});
  const dispose=load().observeUiReady(fixture.window,()=>{ready++;},error=>{throw error;});
  fixture.paint();fixture.paint();await flush();
  assert.equal(ready,1);assert.equal(fixture.root.dataset.droyUiReady,'true');dispose();
});

test('opening settings over IM preserves the mode underneath', () => {
  const calls=[];const ctx={layout:{panelInfo:{getSnapshot:()=>({activePanelId:'droy-companions'})},selectPanel:id=>calls.push(id)},sidebarRight:{isExpanded:()=>false}};
  load().createSettingsOpenHandler(ctx,{querySelector:()=>null})();assert.deepEqual(calls,[]);
});

test('UI-ready waits for insets, applied mobile styles, closed drawer, mounted composer and two stable frames', async () => {
  const fixture = readinessFixture();
  delete fixture.root.dataset.droyInsetsReady;
  let ready = 0;
  const errors = [];
  const dispose = load().observeUiReady(fixture.window, () => { ready++; }, error => errors.push(error));
  assert.equal(fixture.pendingFrames, 0);
  fixture.state.style = true;
  fixture.changed();
  assert.equal(fixture.pendingFrames, 0);
  fixture.state.css = true;
  fixture.changed();
  assert.equal(fixture.pendingFrames, 0);
  fixture.root.dataset.droyInsetsReady = 'true';
  fixture.state.drawer = true;
  fixture.changed();
  assert.equal(fixture.pendingFrames, 0);
  fixture.state.drawer = false;
  fixture.state.composer = false;
  fixture.changed();
  assert.equal(fixture.pendingFrames, 0);
  fixture.state.composer = true;
  fixture.changed();
  fixture.paint();
  await flush();
  assert.equal(ready, 0);
  fixture.paint();
  await flush();
  assert.equal(ready, 1);
  assert.equal(fixture.root.dataset.droyUiReady, 'true');
  assert.equal(fixture.connected, false);
  fixture.changed();
  fixture.paint();
  fixture.paint();
  await flush();
  assert.equal(ready, 1);
  assert.deepEqual(errors, []);
  dispose();
});

test('UI-ready failure stays unacknowledged and disposal cancels frames and observer', async () => {
  const fixture = readinessFixture();
  fixture.state.style = fixture.state.css = true;
  const errors = [];
  const dispose = load().observeUiReady(fixture.window, async () => { throw new Error('原生界面未确认'); }, error => errors.push(error));
  fixture.paint();
  fixture.paint();
  await flush();
  assert.equal(fixture.root.dataset.droyUiReady, undefined);
  assert.match(errors[0].message, /原生界面未确认/);
  dispose();
  let called = false;
  const second = readinessFixture();
  second.state.style = second.state.css = true;
  const cancel = load().observeUiReady(second.window, () => { called = true; }, error => errors.push(error));
  second.paint();
  cancel();
  second.paint();
  await flush();
  assert.equal(called, false);
  assert.equal(second.pendingFrames, 0);
  assert.equal(second.connected, false);
});

test('pet generation requests stay same-origin, preserve drafts and surface server failures', async () => {
  const calls = [];
  let response = { ok: true, json: async () => ({ available: false, pets: [] }) };
  const api = load().createPetApi({ fetch: async (url, options) => { calls.push({ url, options }); return response; } });
  assert.equal((await api('')).available, false);
  assert.equal(calls[0].url, '/droy/pets');
  assert.equal(calls[0].options.credentials, 'same-origin');
  response = { ok: true, json: async () => ({ pet: { id: 'fictional-pet', status: 'draft' } }) };
  const draft = { name: '墨墨', description: '黑猫', personality: '好奇且安静' };
  assert.equal((await api('/create', draft)).pet.status, 'draft');
  assert.deepEqual(JSON.parse(calls[1].options.body), draft);
  assert.equal(calls[1].options.method, 'POST');
  response = { ok: false, json: async () => ({ error: '动作生成未完成，已保留候选' }) };
  await assert.rejects(api('/generate', { id: 'fictional-pet' }), /已保留候选/);
  response = { ok: false, json: async () => { throw new Error('invalid JSON'); } };
  await assert.rejects(api('/retry', { id: 'fictional-pet' }), /无效内容/);
  await assert.rejects(api('https://example.test/steal'), /操作无效/);
});

test('pet preview respects authored directional frames and mirrors only explicitly safe artwork', () => {
  const client = load();
  assert.equal(client.petAssetUrl('fictional-pet', 'pet.json'), '/droy/pets/asset?id=fictional-pet&file=pet.json');
  assert.throws(() => client.petAssetUrl('../secret', 'pet.json'), /地址无效/);
  assert.throws(() => client.petAssetUrl('safe', '../secret.png'), /地址无效/);
  const frames = [0, 1, 2].map(index => ({ sourceRect: [index * 40, 0, 40, 50] }));
  const action = { atlas: 'body', frames: [0, 1], durationsMs: [100, 200], facing: 'left', mirrorSafe: true };
  const manifest = { atlases: { body: { file: 'body.png', frames } }, states: { idle: action } };
  const left = client.petPreviewFrame(manifest, 'idle', 'left', 110);
  assert.equal(left.mirror, true);
  assert.equal(left.source[0], 40);
  assert.equal(left.duration, 300);
  assert.equal(client.petPreviewFrame(manifest, 'idle', 'right', 110).mirror, false);
  action.mirrorSafe = false;
  assert.equal(client.petPreviewFrame(manifest, 'idle', 'left', 110).mirror, false);
  action.rightState = 'idleRight';
  manifest.states.idleRight = { ...action, frames: [2], durationsMs: [100], facing: 'right' };
  const authored = client.petPreviewFrame(manifest, 'idle', 'left', 110);
  assert.equal(authored.name, 'idleRight');
  assert.equal(authored.source[0], 80);
  assert.equal(authored.mirror, false);
});

test('pet sprite preview animates real frames, holds an exited pet hidden, and disposes its frame loop', () => {
  const client = load();
  const frames = [0, 1].map(index => ({ sourceRect: [index * 40, 0, 40, 50] }));
  const action = { atlas: 'body', frames: [0, 1], durationsMs: [100, 100], facing: 'left', mirrorSafe: true };
  const manifest = { atlases: { body: { file: 'body.png', frames } }, states: { idle: action, tap: action, tapTilt: action, exitWalk: action },
    variants: { tap: ['tap', 'tapTilt'], exit: ['exitWalk'] } };
  const callbacks = new Map(), draws = [], transforms = []; let next = 0, now = 0;
  const target = { devicePixelRatio: 1, performance: { now: () => now }, matchMedia: () => ({ matches: false }),
    requestAnimationFrame(callback) { callbacks.set(++next, callback); return next; }, cancelAnimationFrame(id) { callbacks.delete(id); } };
  const context = { setTransform() {}, clearRect() {}, save() {}, restore() {},
    translate: (...args) => transforms.push(args), scale() {}, drawImage: (...args) => draws.push(args) };
  const canvas = { width: 0, height: 0, getContext: () => context, getBoundingClientRect: () => ({ width: 260, height: 164 }) };
  const image = {};
  const player = client.createPetPreview(canvas, manifest, { 'body.png': image }, target);
  const step = elapsed => { now = elapsed; const [id, callback] = callbacks.entries().next().value; callbacks.delete(id); callback(elapsed); };
  player.setSide('left'); step(0); step(150);
  assert.equal(draws[0][0], image);
  assert.equal(draws[0][1], 0);
  assert.equal(draws[1][1], 40, 'Frame progression must draw a different sprite region');
  player.play('exit'); step(150); const before = draws.length; step(400);
  assert.equal(draws.length, before, 'The completed exit must not redraw the pet at its resting position');
  assert.equal(callbacks.size, 0);
  player.play('tap'); assert.equal(callbacks.size, 1);
  player.dispose(); assert.equal(callbacks.size, 0);
});

test('pet preview exposes manifest signature actions and keeps pixel art unsmoothed', () => {
  const client = load(), callbacks = new Map(), draws = []; let next = 0;
  const action = { atlas: 'body', frames: [0, 1], durationsMs: [100, 100], facing: 'front', mirrorSafe: false };
  const manifest = { rendering: { style: 'pixel' }, atlases: { body: { file: 'body.png', frames: [
    { sourceRect: [0, 0, 40, 50] }, { sourceRect: [40, 0, 40, 50] }
  ] } }, states: Object.fromEntries(['idle', 'neckStretch', 'spitForward', 'spitScreen', 'completed', 'stopped', '../private']
    .map(id => [id, { ...action, label: id, labelLocales: { zh: '动作', en: 'Action' } }])) };
  assert.deepEqual(Array.from(client.petPreviewActions(manifest), item => item.id), ['idle', 'neckStretch', 'spitForward', 'spitScreen']);
  const context = { imageSmoothingEnabled: true, shadowBlur: 3, setTransform() {}, clearRect() {}, save() {}, restore() {},
    translate() {}, scale() {}, drawImage(...args) { draws.push(args); } };
  const target = { performance: { now: () => 0 }, requestAnimationFrame(callback) { callbacks.set(++next, callback); return next; },
    cancelAnimationFrame(id) { callbacks.delete(id); }, matchMedia: () => ({ matches: false }) };
  const canvas = { getContext: () => context, getBoundingClientRect: () => ({ width: 260, height: 164 }) };
  const player = client.createPetPreview(canvas, manifest, { 'body.png': {} }, target);
  player.play('spitScreen');
  const [id, draw] = callbacks.entries().next().value; callbacks.delete(id); draw(150);
  assert.equal(draws.at(-1)[1], 40, 'The chosen signature must play its actual second frame');
  assert.equal(context.imageSmoothingEnabled, false);
  assert.equal(context.shadowBlur, 0);
  player.dispose();
});

function hookFixture() {
  const components = new Map(); let active, cursor;
  const slot = () => { const index = cursor++; return [index, active.slots[index]]; };
  const hooks = {
    useState(initial) { const owner = active; const [index, existing] = slot();
      if (!existing) owner.slots[index] = { value: typeof initial === 'function' ? initial() : initial };
      return [owner.slots[index].value, value => { owner.slots[index].value = typeof value === 'function' ? value(owner.slots[index].value) : value; }]; },
    useRef(value) { const [index, existing] = slot(); return existing || (active.slots[index] = { current: value }); },
    useEffect(callback, deps) { const owner = active; const [index, existing] = slot();
      if (!existing || deps.some((value, i) => value !== existing.deps[i])) {
        owner.effects.push(() => { existing?.cleanup?.(); owner.slots[index] = { deps, cleanup: callback() }; });
      }
    }
  };
  return { hooks, render(Component, props = {}) {
    if (!components.has(Component)) components.set(Component, { slots: [], effects: [] });
    active = components.get(Component); cursor = 0;
    const result = Component(props); const effects = active.effects.splice(0);
    effects.forEach(effect => effect()); return result;
  }, reset(Component) { for (const slot of components.get(Component)?.slots || []) slot?.cleanup?.(); components.delete(Component); },
  dispose() { for (const owner of components.values()) for (const slot of owner.slots) slot?.cleanup?.(); } };
}

test('pet creation saves editable personality drafts without a configured provider and uses ready pets only on explicit selection', async () => {
  const hooks = hookFixture(); const calls = [];
  const catalog = { available: false, configurationMessage: '请在模型设置配置图片服务', provider: 'OpenAI', pets: [] };
  const nativeState = { ...state(), petId: 'default', petName: 'Droy', petExternalDisplay: 'off', petInternalDisplay: 'off' };
  const fixture = applyFixture(nativeState, { reactHooks: hooks.hooks,
    windowExtra: { AbortController, fetch: async (url, options) => {
      calls.push({ url, body: options.body ? JSON.parse(options.body) : null });
      if (url.endsWith('/create')) catalog.pets.push({ id: 'draft-1', ...JSON.parse(options.body), status: 'draft', stage: '描述已保存' });
      return { ok: true, json: async () => url.endsWith('/create') ? { pet: catalog.pets[0] } : structuredClone(catalog) };
    } }, onNativeRequest(request, saved) { if (request.action === 'selectPet') saved.petId = request.args.id; } });
  await flush();
  const settings = hooks.render(fixture.registrations.find(item => item.options.id === 'droy-pet').component);
  const studio = nodes(settings).find(node => node.type?.name === 'PetStudio');
  nodes(hooks.render(studio.type, studio.props)).find(node => node.props['data-droy-action'] === 'openPetStudio').props.onClick();
  const body = nodes(hooks.render(studio.type, studio.props)).find(node => node.type?.name === 'PetStudioBody');
  const render = () => nodes(hooks.render(body.type, body.props));
  render(); await flush();
  let tree = render();
  assert.equal(tree.find(node => node.props['data-droy-action'] === 'createPet').props.disabled, true);
  for (const [id, value] of [['droy-pet-create-name', '小灰'], ['droy-pet-description', '戴眼镜的灰狐狸'], ['droy-pet-personality', '安静认真，话少']])
    tree.find(node => node.props.id === id).props.onChange({ target: { value } });
  tree = render();
  const create = tree.find(node => node.props['data-droy-action'] === 'createPet');
  assert.equal(create.children[0], '保存描述'); assert.equal(create.props.disabled, false);
  await create.props.onClick(); await flush(); tree = render();
  assert.deepEqual(calls.find(call => call.url.endsWith('/create')).body, { name: '小灰', description: '戴眼镜的灰狐狸', personality: '安静认真，话少' });
  assert.equal(fixture.requests.some(request => request.action === 'selectPet'), false, 'Creating a draft must not replace the selected pet');
  assert.equal(tree.some(node => node.props['data-droy-action'] === 'selectPet'), false, 'A draft must never expose Use');
  catalog.available = true; catalog.pets[0].status = 'generating_persona'; catalog.pets[0].stage = '正在设计角色性格';
  catalog.pets[0].personaGenerated = false;
  catalog.pets[0].persona = { voice: { working: ['{name}的旧模板'], idle: ['尚未生成'] } };
  hooks.reset(body.type); render(); await flush(); tree = render();
  assert.equal(tree.find(node => node.props['data-droy-action'] === 'createPet').props.disabled, true);
  assert.equal(tree.some(node => node.props['aria-label'] === '说话风格预览'), false, 'Local presets must not masquerade as generated personality');
  assert.ok(tree.some(node => node.props.role === 'status' && node.children.includes('正在设计角色性格')));
  catalog.pets[0].status = 'ready'; catalog.pets[0].manifestUrl = '/droy/pets/asset?id=draft-1&file=pet.json';
  catalog.pets[0].personaGenerated = true;
  catalog.pets[0].persona.voice = { working: ['{name}再核实一下'], idle: ['安静陪着你'] };
  // Remount reflects the persisted candidate without issuing another generation.
  hooks.reset(body.type);
  render(); await flush(); tree = render();
  assert.ok(tree.some(node => node.props['aria-label'] === '宠物互动短句预览'));
  assert.ok(tree.some(node => node.type === 'li' && node.children.includes('小灰再核实一下')));
  const use = tree.find(node => node.props['data-droy-action'] === 'selectPet');
  assert.equal(use.children[0], '应用宠物形象');
  assert.equal(fixture.requests.some(request => request.action === 'selectPet'), false);
  await use.props.onClick(); await flush();
  assert.equal(nativeState.petId, 'draft-1');
  assert.equal(nativeState.petName, 'Droy', 'Applying an appearance must preserve the pet name');
  assert.equal(calls.filter(call => call.url.endsWith('/create')).length, 1);
  hooks.dispose(); fixture.dispose();
});

test('three built-in pets remain selectable without image generation and preview does not select them', async () => {
  const hooks = hookFixture(), calls = [];
  const ids = ['default', '8dd37d0d-b6ed-47c2-9952-8f3be20fa6d0', 'cb5c4126-8c3b-41a5-a1bd-3d3b95cdeee2'];
  const names = [['黑猫', 'Black cat'], ['土拨鼠', 'Marmot'], ['羊驼', 'Alpaca']];
  const catalog = { available: false, imageAvailable: false, pets: [], builtIns: ids.map((id, index) => ({
    id, name: names[index][0], nameLocales: { zh: names[index][0], en: names[index][1] },
    description: '像素伙伴', descriptionLocales: { zh: '像素伙伴', en: 'Pixel companion' },
    referenceUrl: `/droy/pets/asset?id=${id}&file=reference.png`, manifestUrl: `/droy/pets/asset?id=${id}&file=pet.json`
  })) };
  const nativeState = { ...state(), petId: 'retained-custom', petName: 'Droy', petExternalDisplay: 'off', petInternalDisplay: 'off' };
  let failSelect = false;
  const fixture = applyFixture(nativeState, { reactHooks: hooks.hooks,
    windowExtra: { AbortController, fetch: async (url, options) => {
      calls.push({ url, method: options.method }); return { ok: true, json: async () => structuredClone(catalog) };
    } }, onNativeRequest(request, saved) {
      if (request.action === 'selectPet') {
        if (failSelect) throw new Error('Selection not saved');
        saved.petId = request.args.id;
      }
    } });
  await flush();
  const settings = hooks.render(fixture.registrations.find(item => item.options.id === 'droy-pet').component);
  const studio = nodes(settings).find(node => node.type?.name === 'PetStudio');
  nodes(hooks.render(studio.type, studio.props)).find(node => node.props['data-droy-action'] === 'openPetStudio').props.onClick();
  const body = nodes(hooks.render(studio.type, studio.props)).find(node => node.type?.name === 'PetStudioBody');
  const render = () => nodes(hooks.render(body.type, body.props));
  render(); await flush(); let tree = render();
  const cards = tree.filter(node => node.props['data-droy-pet-builtin']);
  assert.deepEqual(cards.map(card => card.props['data-droy-pet-builtin']), ids);
  assert.deepEqual(cards.map(card => nodes(card).find(node => node.props.className === 'droy-row-title').children[0]), names.map(pair => pair[0]));
  assert.equal(nativeState.petId, 'retained-custom', 'Reading built-ins must preserve an existing custom selection');
  const marmot = cards[1];
  nodes(marmot).find(node => node.props['data-droy-action'] === 'previewPetBuiltin').props.onClick();
  tree = render();
  assert.equal(tree.find(node => node.type?.name === 'PetAnimationPreview').props.pet.id, ids[1]);
  assert.equal(fixture.requests.some(request => request.action === 'selectPet'), false);
  assert.ok(calls.filter(call => call.url.startsWith('/droy/pets')).every(call => (call.method || 'GET') === 'GET'),
    'Preview/list must not start paid generation');
  let use = nodes(tree.find(node => node.props['data-droy-pet-builtin'] === ids[1])).find(node => node.props['data-droy-action'] === 'selectPet');
  await use.props.onClick(); await flush(); tree = render();
  assert.equal(nativeState.petId, ids[1]);
  assert.equal(nativeState.petName, 'Droy');
  failSelect = true;
  use = nodes(tree.find(node => node.props['data-droy-pet-builtin'] === ids[2])).find(node => node.props['data-droy-action'] === 'selectPet');
  await use.props.onClick(); await flush();
  assert.equal(nativeState.petId, ids[1], 'A rejected selection must not replace the current built-in');
  hooks.dispose(); fixture.dispose();
});

test('peek entry visibly closes and reopens its eyes within 500ms before the walking body starts', () => {
  const client = load(), frames = [0, 1, 2, 3].map(index => ({ sourceRect: [index * 40, 0, 40, 50] }));
  const body = { atlas: 'body', frames: [0, 1], durationsMs: [100, 100], facing: 'left', mirrorSafe: true };
  const peek = { atlas: 'body', frames: [2, 3, 2], durationsMs: [1200, 120, 1200], facing: 'left', mirrorSafe: true };
  const manifest = { atlases: { body: { file: 'body.png', frames } }, states: { idle: body, enterPeek: body, peekRight: peek, peekLeft: peek }, variants: { enter: ['enterPeek'] } };
  assert.equal(client.petPreviewFrame(manifest, 'peekRight', 'right', 170).source[0], 80, 'Idle peek must retain its original slow rhythm');
  const callbacks = new Map(), draws = [], positions = []; let next = 0, now = 0;
  const target = { devicePixelRatio: 1, performance: { now: () => now }, matchMedia: () => ({ matches: false }),
    requestAnimationFrame(callback) { callbacks.set(++next, callback); return next; }, cancelAnimationFrame(id) { callbacks.delete(id); } };
  const context = { setTransform() {}, clearRect() {}, save() {}, restore() {}, scale() {},
    translate: (...args) => positions.push(args), drawImage: (...args) => draws.push(args) };
  const canvas = { width: 0, height: 0, getContext: () => context, getBoundingClientRect: () => ({ width: 260, height: 164 }) };
  const player = client.createPetPreview(canvas, manifest, { 'body.png': {} }, target);
  const step = elapsed => { now = elapsed; const [id, callback] = callbacks.entries().next().value; callbacks.delete(id); callback(elapsed); };
  player.play('enter'); step(0); step(170); step(340); step(510);
  assert.deepEqual(draws.map(args => args[1]), [80, 120, 80, 0], 'Entry must draw open, closed, open, then body regions');
  const closedEye = draws[1], closedX = positions[1][0];
  assert.ok(closedX + closedEye[5] >= 0 && closedX + closedEye[5] + closedEye[7] <= 260, 'Blink must already be inside the visible canvas');
  player.dispose();
});

test('notification settings use the profile plugin state and report failed changes', async () => {
  const calls = [];
  let enabled = true;
  let rejected = false;
  const api = load().createNotificationPluginApi({ async fetch(path, options) {
    calls.push({ path, options });
    if (options.method === 'POST') {
      if (rejected) return { ok: false, async json() { return { error: '插件设置未保存' }; } };
      enabled = JSON.parse(options.body).enabled;
    }
    return { ok: true, async json() { return { enabled, active: enabled }; } };
  } });
  assert.equal((await api()).enabled, true);
  assert.equal((await api(false)).enabled, false);
  assert.equal(calls[0].path, '/droy/notify/plugin');
  assert.equal(calls[0].options.credentials, 'same-origin');
  assert.equal(calls[0].options.method, 'GET');
  assert.equal(calls[1].options.body, '{"enabled":false}');
  rejected = true;
  await assert.rejects(api(true), /插件设置未保存/);
  assert.equal(enabled, false);
});

test('notification plugin API rejects incomplete state instead of displaying a false enabled result', async () => {
  for (const value of [{}, { enabled: 'yes' }]) {
    const api = load().createNotificationPluginApi({ async fetch() { return { ok: true, async json() { return value; } }; } });
    await assert.rejects(api(), /状态不完整/);
  }
});

test('proactive notification settings retain a separate host entry when the plugin is disabled', async () => {
  const fixture = applyFixture({ ...state(), proactiveNotifications: { localEnabled: true, appsEnabled: false } });
  await flush();
  const component = fixture.registrations.find(item => item.options.id === 'droy-proactive-notifications').component;
  const tree = nodes(component());
  assert.ok(tree.find(node => node.props['data-droy-general'] === 'proactiveNotifications'));
  const toggle = tree.find(node => node.type === 'dsh-switch');
  assert.equal(toggle.props.label, 'Droy 主动通知');
  assert.equal(toggle.props.disabled, true, 'Wait for the profile owner rather than inventing an enabled state');
  assert.ok(fixture.registrations.find(item => item.options.id === 'droy-notifications'), 'Received notification management stays separate');
  fixture.dispose();
});

test('brand templates insert the current page name literally and keep the product logo independent of pets', () => {
  const client = load();
  const defaults = client.brandingSnapshot(null);
  assert.equal(defaults.welcome, '与Droy开始对话');
  assert.equal(defaults.composer, '请描述你想让Droy执行的任务，或者你的对话');
  const state = { assistantName: '墨墨', petId: 'default', branding: {
    welcomeTemplate: '{name} · {name}', composerTemplate: '请告诉{name}', sidebarLabel: '伙伴',
    dshVersion: '0.2.1-rc.2', logoUrl: '/droy/branding/installed-logo',
  } };
  assert.equal(client.brandingSnapshot(state).welcome, '墨墨 · 墨墨');
  assert.equal(client.brandingSnapshot(state).logoUrl, '/droy/branding/installed-logo');
  state.petId = 'a00b0000-0000-4000-8000-000000000001';
  assert.equal(client.brandingSnapshot(state).logoUrl, '/droy/branding/installed-logo');
  assert.equal(client.brandingSnapshot(state).logoPetId,'default');
  state.assistantName = '<img onerror=alert(1)>';
  assert.equal(client.brandingSnapshot(state).composer, '请告诉<img onerror=alert(1)>');
  for (const name of ['$&', '$$', "$`", "$'"])
    assert.equal(client.brandingSnapshot({ assistantName: name }).welcome, `与${name}开始对话`);
});

test('brand state publishes only confirmed changes and updates the shared slots and favicon without reload', async () => {
  const saved = state();
  saved.branding = { name: 'Droy', sidebarLabel: 'Legacy label', dshVersion: '0.2.1-rc.2' };
  const fixture = applyFixture(saved);
  await flush();
  const brand = () => fixture.window.DroyBranding.getSnapshot();
  assert.equal(brand().name, 'Droy');
  const old = brand();
  await fixture.window.DroyMobile.refresh();
  assert.equal(brand(), old, 'Equivalent confirmed state must keep a stable React snapshot');
  saved.assistantName = '墨墨'; saved.branding.name = '墨墨';
  saved.branding.welcomeTemplate = '你好，{name}'; saved.branding.composerTemplate = '{name}，请帮我';
  await fixture.window.DroyMobile.refresh();
  assert.equal(brand().welcome, '你好，墨墨');
  assert.equal(brand().composer, '墨墨，请帮我');
  const title = fixture.registrations.find(item => item.options.name === 'droy.brand.hero-title').component();
  assert.equal(title.children[0], '你好，墨墨');
  const sidebar = fixture.registrations.find(item => item.options.name === 'sidebar.brand.name').component();
  assert.deepEqual(nodes(sidebar).filter(node => node.props['data-droy-brand-name']).map(node => node.children[0]), ['墨墨']);
  assert.equal(nodes(sidebar).some(node => node.props['data-droy-brand-label'] || node.props['data-droy-brand-credit']), false);
  const mark = fixture.registrations.find(item => item.options.name === 'conversation.hero.brand.mark').component({ size: 80 });
  assert.equal(mark.props.src, brand().logoUrl);
  assert.equal(mark.props.alt, '墨墨标志');
  assert.ok(fixture.registrations.some(item => item.options.id === 'droy-branding'));
  const badge = fixture.registrations.find(item => item.options.name === 'droy.brand.hero-badge');
  assert.equal(badge.component(), null);
  saved.branding.heroBadge = '我的伙伴';
  await fixture.window.DroyMobile.refresh();
  assert.equal(badge.component().children[0], '我的伙伴');
  fixture.dispose();
  assert.equal(fixture.window.DroyBranding, undefined);
});


test('mobile settings and branding follow the shared DSH locale without overwriting custom text', async () => {
  let snapshot = { active: 'zh', revision: 0 };
  const listeners = new Set();
  const locale = { getSnapshot: () => snapshot, subscribe(listener) { listeners.add(listener); return () => listeners.delete(listener); } };
  const saved = state();
  saved.branding = { welcomeTemplate: '与{name}开始对话', composerTemplate: '请描述你想让{name}执行的任务，或者你的对话' };
  const fixture = applyFixture(saved, { locale });
  await flush();
  const settings = fixture.registrations.find(item => item.options.id === 'droy-assistant-name').component;
  const text = tree => nodes(tree).flatMap(node => node.children.filter(child => typeof child === 'string')).join(' ');
  assert.match(text(settings()), /页面显示名称/);
  assert.equal(fixture.window.DroyBranding.getSnapshot().welcome, '与Droy开始对话');
  snapshot = { active: 'en', revision: 1 };
  for (const listener of listeners) listener();
  assert.match(text(settings()), /Display name/);
  assert.equal(fixture.window.DroyBranding.getSnapshot().welcome, 'Start a conversation with Droy');
  assert.match(fixture.window.DroyBranding.getSnapshot().composer, /Describe a task for Droy/);
  saved.branding.welcomeTemplate = 'My own welcome for {name}';
  await fixture.window.DroyMobile.refresh();
  snapshot = { active: 'zh', revision: 2 };
  for (const listener of listeners) listener();
  assert.equal(fixture.window.DroyBranding.getSnapshot().welcome, 'My own welcome for Droy');
  fixture.dispose();
  assert.equal(listeners.size, 0);
});

test('mobile UI copy has matching Chinese and English placeholders and no blank translations', async () => {
  const copy = await readFile(new URL('../ui-copy.js', import.meta.url), 'utf8');
  const dictionaries = vm.runInNewContext(copy + '\nDROY_UI_COPY');
  assert.deepEqual(Object.keys(dictionaries.zh), Object.keys(dictionaries.en));
  for (const key of Object.keys(dictionaries.zh)) {
    assert.ok(dictionaries.en[key].trim(), key);
    const placeholders = value => [...value.matchAll(/\{(\w+)\}/g)].map(match => match[1]).sort();
    assert.deepEqual(placeholders(dictionaries.zh[key]), placeholders(dictionaries.en[key]), key);
  }
  assert.ok(Object.keys(dictionaries.zh).length >= 419);
});


test('locale changes refresh native labels only after native language synchronization completes', async () => {
  let snapshot = { active: 'zh', revision: 0 }, resolveSync;
  const listeners = new Set();
  const locale = { getSnapshot: () => snapshot, subscribe(listener) { listeners.add(listener); return () => listeners.delete(listener); } };
  const synced = new Promise(resolve => { resolveSync = resolve; });
  const fixture = applyFixture(state(), { locale, windowExtra: { __DSH_LOCALE__: { whenSynced: () => synced } } });
  await flush();
  const initialReads = fixture.requests.filter(item => item.action === 'getState').length;
  snapshot = { active: 'en', revision: 1 };
  for (const listener of listeners) listener();
  await flush();
  assert.equal(fixture.requests.filter(item => item.action === 'getState').length, initialReads);
  resolveSync();
  fixture.window.dispatchEvent(new fixture.window.CustomEvent('droy-native-locale-change'));
  await flush();
  assert.equal(fixture.requests.filter(item => item.action === 'getState').length, initialReads + 1);
  snapshot = { active: 'en', revision: 2 };
  for (const listener of listeners) listener();
  await flush();
  assert.equal(fixture.requests.filter(item => item.action === 'getState').length, initialReads + 1, 'dictionary revisions must not refresh native state');
  fixture.dispose();
});


test('retrying native synchronization for the same saved language refreshes labels after success', async () => {
  let snapshot = { active: 'zh', revision: 0 }, nativeLanguage = 'zh';
  const listeners = new Set();
  const locale = { getSnapshot: () => snapshot, subscribe(listener) { listeners.add(listener); return () => listeners.delete(listener); } };
  const saved = state();
  let pending = Promise.resolve();
  const fixture = applyFixture(saved, { locale,
    windowExtra: { __DSH_LOCALE__: { whenSynced: () => pending } },
    onNativeRequest(request, state) {
      if (request.action === 'getState') state.groups[0].label = nativeLanguage === 'en' ? 'Read contacts' : '读取联系人';
    },
  });
  await flush();
  const permissionRows = () => nodes(fixture.registrations.find(item => item.options.id === 'droy-permissions').component());
  assert.ok(permissionRows().some(node => node.children.includes('读取联系人')));
  const initialReads = fixture.requests.filter(item => item.action === 'getState').length;
  snapshot = { active: 'en', revision: 1 };
  pending = Promise.reject(new Error('Fictional native synchronization failure'));
  pending.catch(() => {});
  for (const listener of listeners) listener();
  await flush();
  assert.equal(fixture.requests.filter(item => item.action === 'getState').length, initialReads);
  assert.ok(permissionRows().some(node => node.children.includes('读取联系人')));
  nativeLanguage = 'en';
  pending = Promise.resolve();
  fixture.window.dispatchEvent(new fixture.window.CustomEvent('droy-native-locale-change'));
  await flush();
  assert.equal(locale.getSnapshot().active, 'en', 'a same-language retry has no second Host locale change');
  assert.equal(fixture.requests.filter(item => item.action === 'getState').length, initialReads + 1);
  assert.ok(permissionRows().some(node => node.children.includes('Read contacts')));
  fixture.dispose();
  assert.equal(fixture.listeners.has('droy-native-locale-change'), false);
});


test('a later native language acknowledgement refreshes labels while an earlier state read is in flight', async () => {
  let snapshot = { active: 'zh', revision: 0 }, nativeLanguage = 'zh', holdNextRead = false, deliverEarlier;
  const listeners = new Set();
  const locale = { getSnapshot: () => snapshot, subscribe(listener) { listeners.add(listener); return () => listeners.delete(listener); } };
  const fixture = applyFixture(state(), { locale,
    windowExtra: { __DSH_LOCALE__: { whenSynced: () => Promise.resolve() } },
    onNativeRequest(request, state) {
      if (request.action === 'getState') state.groups[0].label = nativeLanguage === 'en' ? 'Read contacts' : '读取联系人';
    },
    onNativeReply(request, deliver) {
      if (request.action !== 'getState' || !holdNextRead) return false;
      holdNextRead = false;
      deliverEarlier = deliver;
      return true;
    },
  });
  await flush();
  const initialReads = fixture.requests.filter(item => item.action === 'getState').length;
  const change = language => {
    nativeLanguage = language;
    snapshot = { active: language, revision: snapshot.revision + 1 };
    for (const listener of listeners) listener();
    fixture.window.dispatchEvent(new fixture.window.CustomEvent('droy-native-locale-change'));
  };
  holdNextRead = true;
  change('en');
  await flush();
  assert.equal(typeof deliverEarlier, 'function');
  change('zh');
  await flush();
  assert.equal(fixture.requests.filter(item => item.action === 'getState').length, initialReads + 2);
  const labels = () => nodes(fixture.registrations.find(item => item.options.id === 'droy-permissions').component())
    .flatMap(node => node.children.filter(child => typeof child === 'string'));
  assert.ok(labels().includes('读取联系人'));
  deliverEarlier();
  await flush();
  assert.ok(labels().includes('读取联系人'), 'the late English response must not replace the newer Chinese labels');
  assert.equal(labels().includes('Read contacts'), false);
  fixture.dispose();
});

test('the sixth setup page renders a saved user address without accessing locale outside its Cordis dependency scope', async () => {
  const { createRequire } = await import('node:module');
  const { pathToFileURL } = await import('node:url');
  const installed = createRequire(new URL('../../dsh/package.json', import.meta.url));
  const { Context } = await import(pathToFileURL(installed.resolve('@deepseek-ai/cordis')).href);

  for (const active of ['zh', 'en']) {
    const locale = { getSnapshot: () => ({ active }), subscribe: () => () => {} };
    const root = new Context();
    let strictScope;
    await root.plugin({ name: 'setup-locale-owner-fixture', apply(scope) { scope.provide('locale', locale); } });
    await root.plugin({ name: 'setup-without-locale-dependency-fixture', apply(scope) { strictScope = scope; } });
    assert.throws(() => strictScope.locale, /cannot get property "locale" without inject/,
      'The regression fixture must enforce the real Cordis service boundary');

    const saved = { ...state(), setup: { required: true, completed: false, step: 6, mode: 'dialogue',
      modeSelected: true, identity: { companionName: 'Momo', userAddress: 'Friend', personality: 'Calm.' },
      modelVerification: { passed: true }, checks: [] } };
    const fixture = applyFixture(saved, { locale, reactHooks: { useRef: current => ({ current }) } });
    try {
      await flush();
      // apply() has already captured its authorized locale service. Rendering
      // the welcome page must not perform a fresh read through another scope.
      Object.defineProperty(fixture.ctx, 'locale', { configurable: true, get: () => strictScope.locale });
      const component = fixture.registrations.find(row => row.options.id === 'droy-setup').component;
      let tree;
      assert.doesNotThrow(() => { tree = component(); }, `${active}: the sixth page must remain mounted`);
      assert.equal(tree.props['data-droy-setup-step'], 6);
      const heading = nodes(tree).find(row => row.props['data-droy-setup-title']);
      const title = heading.children.filter(child => typeof child === 'string').join('');
      assert.ok(title.includes('Friend'), 'A nonempty saved address must be rendered');
      assert.ok(title.includes('Droy'), 'The welcome heading must name the product');
      assert.equal(title.includes('Momo'), false, 'The pet name must not become the product or chat companion name');
      assert.ok(title.startsWith(active === 'en' ? 'Friend, ' : 'Friend，'), 'The captured locale must format the address');
      assert.ok(nodes(tree).some(row => row.props['data-droy-setup-finish']), 'Completion still requires its own action');
      assert.equal(nodes(tree).some(row => row.props['data-droy-setup-next']), false);
      assert.equal(fixture.requests.some(row => row.action === 'setupUpdate'), false, 'Rendering must not advance or complete setup');
    } finally {
      Object.defineProperty(fixture.ctx, 'locale', { configurable: true, value: locale });
      fixture.dispose();
      await root.fiber.dispose();
    }
  }
});


test('Back folds the inline speech choice and focuses its trigger before closing settings', () => {
  const calls = [], choice = { open: true, querySelector: () => ({ focus() { calls.push('focus-choice'); } }) };
  const doc = { querySelector: selector => selector === '.droy-voice-choice[open]' && choice.open ? choice : null,
    querySelectorAll: () => [{ hasAttribute: () => false, getAttribute: () => 'dialog' }] };
  const back = load().createBackHandler({}, doc, () => calls.push('close-settings'));
  assert.equal(back(), true); assert.equal(choice.open, false); assert.deepEqual(calls, ['focus-choice']);
  assert.equal(back(), true); assert.deepEqual(calls, ['focus-choice', 'close-settings']);
});

function companionWorkbenchFixture({ initialSession = 'kaka-task', workspacePhase = 'ready', panel = null } = {}) {
  const observable = initial => {
    let value = initial; const listeners = new Set();
    return { getSnapshot: () => value, subscribe(listener) { listeners.add(listener); return () => listeners.delete(listener); },
      set(next) { value = next; for (const listener of [...listeners]) listener(); } };
  };
  const companions = observable({ companions: [{ id: 'kaka', name: 'Kaka', workspaceId: 'owned' }] });
  const workspaces = observable({ phase: workspacePhase, items: [
    { workspaceId: 'owned', sessionIds: ['kaka-task'] }, { workspaceId: 'default', sessionIds: ['ordinary'] }] });
  const sessions = observable({ phase: 'ready', ids: ['kaka-task', 'ordinary'], byId: Object.fromEntries(['kaka-task', 'ordinary'].map(id => [id, { id, retainedBy: { mainView: id === initialSession ? 1 : 0 } }])) });
  const locale = observable({ active: 'en' }), blockStores = new Map(), registrations = [], calls = [], errors = [];
  const blocks = { storeFor(id) { if (!blockStores.has(id)) blockStores.set(id, observable(undefined)); return blockStores.get(id); },
    set(id, block) { blocks.storeFor(id).set(block); } };
  const navigation = Object.fromEntries(['startSession', 'connectWorkspace', 'openWorkspace', 'forkSession'].map(name => [name, (...args) => {
    calls.push([name, ...args]); return name === 'startSession' ? undefined : Promise.resolve(name === 'connectWorkspace' ? 'ordinary' : undefined);
  }]));
  navigation.openSession = id => { calls.push(['openSession', id]); sessions.set({ ...sessions.getSnapshot(), byId: Object.fromEntries(Object.values(sessions.getSnapshot().byId).map(row => [row.id, { ...row, retainedBy: { mainView: row.id === id ? 1 : 0 } }])) }); panel = null; };
  navigation.restoreSelection = async (...args) => { calls.push(['restoreSelection', ...args]); };
  const ctx = { locale, uiWorkspace: navigation, sessions: { list: sessions }, workspaces: { list: workspaces }, conversation: { blocks },
    layout: { panelInfo: { getSnapshot: () => ({ activePanelId: panel }) }, selectPanel(id) { panel = id; } },
    slots: { inject(_name, register) { return register(); }, register(options, component) { registrations.push({ options, component }); return () => {}; } } };
  let allowed = true;
  const client = load({}, { React: { createElement: (type, props, ...children) => ({ type, props, children }), useSyncExternalStore: (_subscribe, read) => read() } });
  const policy = client.createCompanionWorkbench({ ctx, state: companions, reportError: error => errors.push(error.message), canInitialize: () => allowed });
  return { policy, ctx, calls, errors, sessions, workspaces, companions, locale, blocks, registrations,
    stopStartup() { allowed = false; }, panel: () => panel };
}

test('companion workspaces replace the composer and reject manual creation and forks without reaching work RPCs', async () => {
  const f = companionWorkbenchFixture();
  assert.match(f.blocks.storeFor('kaka-task').getSnapshot().reason, /Kaka/);
  assert.equal(f.blocks.storeFor('ordinary').getSnapshot(), undefined);
  f.ctx.uiWorkspace.startSession('owned'); f.ctx.uiWorkspace.startSession();
  await assert.rejects(f.ctx.uiWorkspace.connectWorkspace('owned'), /Messages/);
  await assert.rejects(f.ctx.uiWorkspace.openWorkspace('owned'), /Messages/);
  await assert.rejects(f.ctx.uiWorkspace.forkSession('kaka-task'), /Messages/);
  assert.deepEqual(f.calls, []);
  const entry = f.registrations.find(row => row.options.name === 'conversation.composer');
  assert.equal(entry.options.select({ sessionId: 'ordinary' }), null);
  const matched = entry.options.select({ sessionId: 'kaka-task' });
  assert.equal(matched.id, 'kaka');
  assert.match(entry.component({ matched }).children.join(), /Go to Messages/);
  f.ctx.uiWorkspace.startSession('default');
  await f.ctx.uiWorkspace.forkSession('ordinary');
  f.ctx.uiWorkspace.openSession('kaka-task');
  assert.deepEqual(f.calls.map(row => row[0]), ['startSession', 'forkSession', 'openSession']);
  f.locale.set({ active: 'zh-CN' });
  assert.match(f.blocks.storeFor('kaka-task').getSnapshot().reason, /返回消息列表/);
  f.policy.dispose();
  assert.equal(f.blocks.storeFor('kaka-task').getSnapshot(), undefined);
  await f.ctx.uiWorkspace.connectWorkspace('owned');
  assert.equal(f.calls.at(-1)[0], 'connectWorkspace');
});

test('startup selects an ordinary workspace after companion spaces prepend and preserves the IM panel', async () => {
  const f = companionWorkbenchFixture({ panel: 'droy-companions' });
  await f.policy.initializeDefault();
  assert.deepEqual(f.calls, [['connectWorkspace', 'default'], ['openSession', 'ordinary']]);
  assert.equal(f.panel(), 'droy-companions');
  f.policy.dispose();
});

test('companion draft and status updates skip workspace scans while ownership updates remain live', () => {
  const f = companionWorkbenchFixture();
  const readWorkspaces = f.workspaces.getSnapshot;
  let scans = 0;
  f.workspaces.getSnapshot = () => { scans++; return readWorkspaces(); };
  const before = f.companions.getSnapshot();
  f.companions.set({ ...before, draft: 'hello' });
  f.companions.set({ ...before, status: 'typing' });
  assert.equal(scans, 0);
  f.companions.set({ ...before, companions: before.companions.map(row => ({ ...row, name: 'Renamed' })) });
  assert.equal(scans, 1);
  assert.match(f.blocks.storeFor('kaka-task').getSnapshot().reason, /Renamed/);
  f.workspaces.set({ ...readWorkspaces(), items: readWorkspaces().items.map(row => row.workspaceId === 'owned'
    ? { ...row, sessionIds: ['kaka-task', 'new-task'] } : row) });
  assert.match(f.blocks.storeFor('new-task').getSnapshot().reason, /Renamed/);
  f.policy.dispose();
});

test('automatic work restoration excludes companion spaces and saved companion sessions without showing a refusal', async () => {
  const f=companionWorkbenchFixture();
  await f.ctx.uiWorkspace.restoreSelection(f.workspaces.getSnapshot(),f.sessions.getSnapshot());
  const [name,workspaces,sessions]=f.calls[0];
  assert.equal(name,'restoreSelection');assert.deepEqual(workspaces.items.map(row=>row.workspaceId),['default']);
  assert.deepEqual(sessions.ids,['ordinary']);assert.deepEqual(Object.keys(sessions.byId),['ordinary']);
  const overlay=f.registrations.find(row=>row.options.id==='droy-workspace-readonly-notice');
  assert.equal(overlay.component(),null);assert.deepEqual(f.errors,[]);
  f.policy.dispose();
});

test('automatic restoration waits for the first companion ownership snapshot', async () => {
  const f=companionWorkbenchFixture(),ready=f.companions.getSnapshot();
  f.companions.set({companions:[]});
  let release;f.companions.refreshRoot=()=>new Promise(resolve=>{release=()=>{f.companions.set(ready);resolve();};});
  const restore=f.ctx.uiWorkspace.restoreSelection(f.workspaces.getSnapshot(),f.sessions.getSnapshot());
  assert.deepEqual(f.calls,[]);release();await restore;
  assert.deepEqual(f.calls[0][1].items.map(row=>row.workspaceId),['default']);
  f.policy.dispose();
});

test('startup waits for workspace readiness and does not steal a user-selected workspace', async () => {
  const f = companionWorkbenchFixture({ workspacePhase: 'following' });
  await f.policy.initializeDefault(); assert.deepEqual(f.calls, []);
  f.workspaces.set({ ...f.workspaces.getSnapshot(), phase: 'ready' }); await flush();
  assert.equal(f.calls[0][1], 'default'); f.policy.dispose();
  for (const options of [{ initialSession: 'ordinary' }, {}]) {
    const second = companionWorkbenchFixture(options);
    if (!options.initialSession) second.stopStartup();
    await second.policy.initializeDefault(); assert.deepEqual(second.calls, []); second.policy.dispose();
  }
});

test('setup introduces the initial companion and four presets and saves only the optional welcome address in both languages', async () => {
  for (const active of ['zh', 'en']) {
    const hooks = hookFixture(), locale = { getSnapshot: () => ({ active }), subscribe: () => () => {} };
    const saved = { ...state(), setup: { required: true, step: 4, mode: 'dialogue', modeSelected: true,
      identity: { companionName: 'Old pet', personality: 'Old global personality', userAddress: 'Friend' } } };
    const fixture = applyFixture(saved, { locale, reactHooks: hooks.hooks,
      onNativeRequest(request, state) { if (request.action === 'setupUpdate') Object.assign(state.setup.identity, request.args); } });
    await flush();
    const Setup = fixture.registrations.find(row => row.options.id === 'droy-setup').component;
    let tree = nodes(hooks.render(Setup));
    const strings = tree.flatMap(node => node.children.filter(value => typeof value === 'string'));
    assert.ok(strings.some(value => value.includes(active === 'en' ? 'Your first installation includes Kaka' : '首次安装自带咔咔')));
    for (const name of active === 'en' ? ['Kaka', 'Maimai', 'Dafeiyu', 'Lady Claude'] : ['咔咔', '麦麦', '大肥鱼', 'Claude 大小姐'])
      assert.ok(strings.some(value => value.includes(name)), `Setup must introduce the ${name} preset in ${active}`);
    assert.ok(strings.some(value => value.includes(active === 'en' ? 'first chat' : '首次聊天')));
    assert.ok(strings.some(value => value.includes(active === 'en' ? 'without Root' : '无需 Root')));
    const optional = tree.find(node => node.type === 'details'); assert.notEqual(optional.props.open, true);
    assert.ok(nodes(optional).some(node => node.type?.name === 'PetStudio'));
    const Identity = tree.find(node => node.type?.name === 'Identity');
    let fields = nodes(hooks.render(Identity.type, Identity.props));
    assert.equal(fields.some(node => ['droy-setup-name', 'droy-setup-personality'].includes(node.props.id)), false);
    fields.find(node => node.props.id === 'droy-setup-address').props.onChange({ target: { value: 'New friend' } });
    hooks.render(Identity.type, Identity.props);
    tree = nodes(hooks.render(Setup)); tree.find(node => node.props['data-droy-setup-next']).props.onClick(); await flush();
    const update = fixture.requests.find(request => request.action === 'setupUpdate');
    assert.deepEqual(update.args, { userAddress: 'New friend', step: 5 });
    assert.equal(saved.setup.identity.companionName, 'Old pet'); assert.equal(saved.setup.identity.personality, 'Old global personality');
    fixture.dispose(); hooks.dispose();
  }
});

test('legacy appearance settings and studio are explicitly pets and never configure chat companion identity', async () => {
  for (const active of ['zh', 'en']) {
    const hooks = hookFixture(), requests = [], locale = { getSnapshot: () => ({ active }), subscribe: () => () => {} };
    const fixture = applyFixture({ ...state(), petName: 'Old pet', setup: { required: false, identity: { userAddress: 'Friend' } } }, { locale, reactHooks: hooks.hooks,
      windowExtra: { AbortController, fetch: async (url, options = {}) => {
        requests.push({ url, ...options }); return { ok: true, json: async () => url === '/droy/pets' ? { available: false, pets: [] } : { companions: [], settings: { defaultMode: 'work' } } };
      } } });
    await flush();
    let tree = nodes(hooks.render(fixture.registrations.find(row => row.options.id === 'droy-pet').component));
    const name = tree.find(node => node.props.id === 'droy-pet-name');
    assert.equal(name.props['aria-label'], active === 'en' ? 'Pet name' : '宠物名字');
    assert.ok(tree.some(node => node.children.includes(active === 'en' ? 'Pet' : '宠物')));
    const Studio = tree.find(node => node.type?.name === 'PetStudio');
    tree = nodes(hooks.render(Studio.type, Studio.props));
    tree.find(node => node.props['data-droy-action'] === 'openPetStudio').props.onClick();
    const Body = nodes(hooks.render(Studio.type, Studio.props)).find(node => node.type?.name === 'PetStudioBody');
    hooks.render(Body.type, Body.props); await flush(); tree = nodes(hooks.render(Body.type, Body.props));
    assert.ok(tree.some(node => node.children.some(value => typeof value === 'string' && value.includes(active === 'en' ? 'do not change chat companions' : '不会改变聊天伙伴'))));
    assert.equal(requests.some(request => request.method === 'POST'), false, 'Opening optional pet settings must not generate a paid design');
    assert.equal(fixture.requests.some(request => request.action === 'setupUpdate' || request.action === 'selectPet'), false);
    assert.ok(fixture.registrations.some(row => row.options.id === 'droy-welcome-address'));
    fixture.dispose(); hooks.dispose();
  }
});

test('blocked companion workspace picks show a public shell Toast in both languages without changing the current workspace', async () => {
  const f = companionWorkbenchFixture({ initialSession: 'ordinary' });
  const entry = f.registrations.find(row => row.options.id === 'droy-workspace-readonly-notice');
  assert.equal(entry.options.name, 'shell.overlay'); assert.equal(entry.component(), null);
  await assert.rejects(f.ctx.uiWorkspace.connectWorkspace('owned'), /Go to Messages/);
  const first = entry.component();
  assert.equal(first.type, 'dsh-toast'); assert.match(first.props.text, /Kaka/);
  assert.match(first.props.text, /open your companion’s chat/); assert.equal(first.props.holdMs, 8000);
  f.locale.set({ active: 'zh' });
  assert.match(entry.component().props.text, /返回消息列表，进入聊天后告诉伙伴要做什么/);
  f.ctx.uiWorkspace.startSession('owned');
  const second = entry.component(); assert.notEqual(second.props.key, first.props.key);
  first.props.onDone(); assert.ok(entry.component(), 'A previous toast expiry must not dismiss a newer refusal');
  assert.deepEqual(f.calls, []); assert.equal(f.sessions.getSnapshot().byId.ordinary.retainedBy.mainView, 1);
  second.props.onDone(); assert.equal(entry.component(), null);
  assert.deepEqual(f.errors, [], 'An expected read-only refusal must not be hidden in Settings feedback'); f.policy.dispose();
});

test('confirmed mutation state is adopted once and unrelated setting subscriptions stay idle', async () => {
  const saved = { ...state(), petName: 'Pet', returnToDroyOnCompletion: false }, calls = [];
  const controller = load().createController({ async request(action, args) {
    calls.push(action);
    if (action === 'getState') return structuredClone(saved);
    saved[args.key] = args.value;
    return structuredClone(saved);
  } });
  await controller.refresh();
  const name = controller.view(['petName'], ['setPreference:petName']);
  const navigation = controller.view(['returnToDroyOnCompletion'], ['setPreference:returnToDroyOnCompletion']);
  let nameUpdates = 0, otherUpdates = 0;
  const before = navigation.getSnapshot();
  const stopName = name.subscribe(() => nameUpdates++), stopNavigation = navigation.subscribe(() => otherUpdates++);
  await controller.run('setPreference', { key: 'petName', value: 'New pet name' });
  assert.deepEqual(calls, ['getState', 'setPreference']);
  assert.equal(name.getSnapshot().state.petName, 'New pet name');
  assert.ok(nameUpdates >= 2);
  assert.equal(otherUpdates, 0);
  assert.equal(navigation.getSnapshot(), before);
  stopName(); stopNavigation(); controller.dispose();
});

test('independent setting writes queue without losing either choice and same-control double taps coalesce', async () => {
  const saved = state(), writes = [], releases = [];
  const controller = load().createController({ request(action, args) {
    if (action === 'getState') return Promise.resolve(structuredClone(saved));
    writes.push(args);
    return new Promise(resolve => releases.push(() => { saved[args.key] = args.value; resolve(structuredClone(saved)); }));
  } });
  await controller.refresh();
  const first = controller.run('setPreference', { key: 'petName', value: 'First' });
  assert.equal(controller.run('setPreference', { key: 'petName', value: 'First' }), first);
  const second = controller.run('setPreference', { key: 'returnToDroyOnCompletion', value: true });
  await Promise.resolve(); assert.equal(writes.length, 1);
  releases.shift()(); await first; await Promise.resolve();
  assert.equal(writes.length, 2); releases.shift()(); await second;
  assert.equal(controller.getSnapshot().state.petName, 'First');
  assert.equal(controller.getSnapshot().state.returnToDroyOnCompletion, true);
  assert.equal(controller.getSnapshot().pending, null);
  controller.dispose();
});

test('scoped audio reads coalesce and app inventories survive lightweight state responses', async () => {
  const saved = { ...state(), notificationManagement: { exceptions: [], rules: [] } }, calls = [];
  let finish;
  const controller = load().createController({ request(action, args) {
    calls.push([action, args]);
    if (action === 'getState') return Promise.resolve(structuredClone(saved));
    if (args.section === 'notificationManagement') return Promise.resolve({ notificationManagement: {
      ...saved.notificationManagement, apps: [{ packageName: 'fictional.app', label: 'App', mode: 'managed' }] } });
    return new Promise(resolve => { finish = resolve; });
  } });
  await controller.refresh(); await controller.refreshSection('notificationManagement');
  await controller.refresh();
  assert.equal(controller.getSnapshot().state.notificationManagement.apps[0].label, 'App');
  const first = controller.refreshSection('audio');
  assert.equal(controller.refreshSection('audio'), first);
  await Promise.resolve();
  finish({ audioCapture: { active: null }, audioTranscription: { ready: true } }); await first;
  assert.equal(calls.filter(([action, args]) => action === 'getStateSection' && args.section === 'audio').length, 1);
  assert.equal(controller.getSnapshot().state.audioTranscription.ready, true);
  controller.dispose();
});

test('a failed scoped read preserves confirmed state and a refused write never appears saved', async () => {
  const saved = { ...state(), petName: 'Original' };
  const controller = load().createController({ request(action) {
    if (action === 'getState') return Promise.resolve(structuredClone(saved));
    if (action === 'getStateSection') return Promise.reject(new Error('Scoped read failed'));
    return Promise.reject(new Error('Durable write failed'));
  } });
  await controller.refresh();
  await assert.rejects(controller.refreshSection('audio'), /Scoped read failed/);
  await assert.rejects(controller.run('setPreference', { key: 'petName', value: 'Unsaved' }), /Durable write failed/);
  assert.equal(controller.getSnapshot().state.petName, 'Original');
  assert.equal(controller.getSnapshot().pending, null);
  controller.dispose();
});

test('a late app catalog cannot roll back a newer confirmed exception mode', async () => {
  const saved = { ...state(), notificationManagement: { exceptions: [], rules: [] } };
  let finish;
  const controller = load().createController({ request(action) {
    if (action === 'getState') return Promise.resolve(structuredClone(saved));
    return new Promise(resolve => { finish = resolve; });
  } });
  await controller.refresh();
  const catalog = controller.refreshSection('notificationManagement'); await Promise.resolve();
  saved.notificationManagement.exceptions = [{ packageName: 'fictional.app', mode: 'block' }];
  await controller.refresh();
  finish({ notificationManagement: { exceptions: [], apps: [{ packageName: 'fictional.app', label: 'App', mode: 'managed' }] } });
  await catalog;
  assert.equal(controller.getSnapshot().state.notificationManagement.exceptions[0].mode, 'block');
  assert.equal(controller.getSnapshot().state.notificationManagement.apps[0].mode, 'block');
  controller.dispose();
});

test('lightweight notification refresh preserves only explicit app selections and defaults missing modes to allow', async () => {
  const saved = { ...state(), notificationManagement: { appModes: { 'fictional.selected': 'managed', 'fictional.allowed': 'allow' }, exceptions: [] } };
  const apps = [
    { packageName: 'fictional.selected', label: 'Selected', mode: 'managed', managed: true },
    { packageName: 'fictional.allowed', label: 'Allowed', mode: 'allow', managed: false },
    { packageName: 'fictional.new', label: 'New app', mode: 'allow', managed: false },
  ];
  const controller = load().createController({ request(action) {
    if (action === 'getState') return Promise.resolve(structuredClone(saved));
    return Promise.resolve({ notificationManagement: { ...structuredClone(saved.notificationManagement), apps } });
  } });
  try {
    await controller.refresh(); await controller.refreshSection('notificationManagement');
    await controller.refresh();
    const selected = () => Array.from(controller.getSnapshot().state.notificationManagement.apps,
      app => ({ packageName: app.packageName, mode: app.mode, managed: app.managed }));
    assert.deepEqual(selected(), apps.map(({ packageName, mode, managed }) => ({ packageName, mode, managed })));
    saved.notificationManagement.appModes = {};
    await controller.refresh();
    assert.ok(selected().every(app => app.mode === 'allow' && app.managed === false));
    assert.equal(controller.getSnapshot().state.notificationManagement.apps[0].label, 'Selected');
  } finally { controller.dispose(); }
});

test('complete native notification app catalogs retain their authoritative modes', async () => {
  const saved = { ...state(), notificationManagement: { appModes: { 'fictional.app': 'managed' }, exceptions: [],
    apps: [{ packageName: 'fictional.app', label: 'Native app', mode: 'allow', managed: false }] } };
  const controller = load().createController({ request: async () => structuredClone(saved) });
  try {
    await controller.refresh();
    const app = controller.getSnapshot().state.notificationManagement.apps[0];
    assert.equal(app.mode, 'allow'); assert.equal(app.managed, false);
  } finally { controller.dispose(); }
});

test('a late notification catalog uses the latest explicit app modes without reviving deselected apps', async () => {
  const saved = { ...state(), notificationManagement: { appModes: { 'fictional.app': 'managed' }, exceptions: [] } };
  let finish;
  const controller = load().createController({ request(action) {
    if (action === 'getState') return Promise.resolve(structuredClone(saved));
    return new Promise(resolve => { finish = resolve; });
  } });
  try {
    await controller.refresh();
    const catalog = controller.refreshSection('notificationManagement'); await Promise.resolve();
    saved.notificationManagement.appModes = {};
    await controller.refresh();
    finish({ notificationManagement: { appModes: { 'fictional.app': 'managed' }, exceptions: [],
      apps: [{ packageName: 'fictional.app', label: 'Late app label', mode: 'managed', managed: true }] } });
    await catalog;
    const current = controller.getSnapshot().state.notificationManagement;
    assert.equal(current.apps[0].label, 'Late app label');
    assert.equal(current.apps[0].mode, 'allow'); assert.equal(current.apps[0].managed, false);
    assert.equal(Object.keys(current.appModes).length, 0);
  } finally { controller.dispose(); }
});

test('native timezone is synchronized on startup and only after a changed device timezone', async () => {
  const saved = { ...state(), timeZone: 'Asia/Shanghai' }, writes = [];
  const fixture = applyFixture(saved, { windowExtra: { fetch: async (url, options) => {
    if (url === '/droy/companions/settings' && options?.method === 'PATCH') writes.push(JSON.parse(options.body));
    return { ok: true, json: async () => ({ companions: [], settings: { defaultMode: 'work' }, stickers: [], models: [] }) };
  } } });
  await flush(); await flush();
  assert.deepEqual(writes, [{ timeZone: 'Asia/Shanghai' }]);
  await fixture.window.DroyMobile.refresh();
  assert.equal(writes.length, 1);
  saved.timeZone = 'America/New_York';
  await fixture.window.DroyMobile.refresh();
  assert.deepEqual(writes.at(-1), { timeZone: 'America/New_York' });
  fixture.dispose();
});
