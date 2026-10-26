import test from 'node:test';
import assert from 'node:assert/strict';
import vm from 'node:vm';
import { composeMobileClient } from '../../../tools/mobile_client_bundle.mjs';
import { readFile } from 'node:fs/promises';
import { patchLayout, patchSettings, patchWorkspace, patchConversation, patchSidebar, patchSidebarRight, patchModelSelection, patchInputTrigger, droyTriggerMenuPosition } from '../../../tools/mobile_ui_patch.mjs';

const packageRoot = new URL('../../dsh/node_modules/@deepseek-ai/', import.meta.url);
const layout = await readFile(new URL('dsh-client-ui-layout/lib/client.js', packageRoot), 'utf8');
const workspace = await readFile(new URL('dsh-client-ui-workspace/lib/client.js', packageRoot), 'utf8');
const conversation = await readFile(new URL('dsh-client-ui-conversation/lib/client.js', packageRoot), 'utf8');
const settings = await readFile(new URL('dsh-client-ui-settings-general/lib/client.js', packageRoot), 'utf8');

function renderFrame({mobile = true, open = false, width = 393} = {}) {
  const patched = patchLayout(layout);
  const functionCode = patched.slice(patched.indexOf('function computeColumns('), patched.indexOf('//#region lib/types/client/stores.js'));
  const jsx = (type, props) => ({type, props});
  const react = {useRef: value => ({current:value}), useState: value => [value, () => {}], useMemo: callback => callback(), useCallback: callback => callback, useEffect() {}, useLayoutEffect() {}};
  const document = {documentElement:{hasAttribute:name => mobile && name === 'data-droy-mobile',dataset:{}},querySelector:()=>null};
  // Keep the upstream stylesheet declaration in the evaluated excerpt inert.
  const factory = new Function('react','react_jsx_runtime','document','window', `const SIDEBAR_AUTO_COLLAPSE=1024,RIGHTBAR_MAX_RATIO=.7;function clampWidth(px,min,max){return Math.min(max,Math.max(min,Math.round(px)))};${functionCode};return AppFrame;`);
  document.querySelector = selector => selector.startsWith('style[') ? {} : null;
  const AppFrame = factory(react, {jsx,jsxs:jsx,Fragment:'fragment'}, document, {});
  let toggles = 0;
  const frame = AppFrame({
    useStore: selector => selector({layoutInfo:{viewportWidth:width,narrowExpanded:open,sidebar:280,rightbar:null,rightbarShown:false,rightbarTrack:false}}),
    useSessions:()=>{},usePanelInfo:()=>{},actions:{toggleSidebar(){toggles++;}},
    renderSlot:(name,props)=>({slot:name,props}),t:value=>value,
  });
  return {frame,get toggles(){return toggles;}};
}

function nodes(tree) {
  if (Array.isArray(tree)) return tree.flatMap(nodes);
  if (!tree || typeof tree !== 'object') return [];
  const children = tree.props?.children;
  return [tree,...(Array.isArray(children)?children:[children]).flatMap(nodes)];
}

function renderSettings({ mobile = true } = {}) {
  const patched = patchSettings(settings);
  const start = patched.indexOf('function SettingsPanel(');
  const end = patched.indexOf('\n\t\t/**\n\t\t* Render the settings trigger', start);
  const jsx = (type, props) => ({ type, props });
  const effects = [];
  const modalLayers = [];
  const events = [];
  const slots = [];
  const selected = [];
  const frames = [];
  const focusCalls = [];
  let menuVisible = true;
  let menuInert = false;
  let closes = 0;
  const drawerGear = {};
  const drawer = { contains: node => node === drawerGear };
  const menu = { getClientRects: () => menuVisible ? [{}] : [], closest: () => menuInert ? {} : null,
    focus: options => { focusCalls.push(options); document.activeElement = menu; } };
  const document = { documentElement: { hasAttribute: name => mobile && name === 'data-droy-mobile' }, body: {},
    querySelector: selector => selector === '[data-droy-sidebar][inert]' ? drawer : selector === '[data-droy-menu]' ? menu : null };
  document.activeElement = document.body;
  const window = { CustomEvent: class { constructor(type) { this.type = type; } }, dispatchEvent: event => events.push(event.type),
    requestAnimationFrame: callback => frames.push(callback) };
  const makePanel = new Function('react', 'react_jsx_runtime', 'react_dom', '_deepseek_ai_dsh_client_ui_primitives', 'SettingsRoot_module_css_default', 'navIcon', 'clsx', 'document', 'window',
    `${patched.slice(start, end)};return SettingsPanel;`);
  const Panel = makePanel({ useId: () => 'settings-title', useRef: value => ({ current: value }), useLayoutEffect: effect => effects.push(effect) },
    { jsx, jsxs: jsx }, { createPortal: (tree, body) => { assert.equal(body, document.body); return tree; } },
    { useModalLayer: (...args) => modalLayers.push(args), IconCloseOutlineRegular: 'close-icon', IconChevronLeftOutlineRegular: 'dsh-back-icon' },
    new Proxy({}, { get: (_target, key) => String(key) }), id => jsx('nav-icon', { id }), (...classes) => classes.filter(Boolean).join(' '), document, window);
  const tree = Panel({ rows: [{ id: 'general', label: '通用设置' }, { id: 'models', label: '模型' }, { id: 'droy-permissions', label: '权限设置' }],
    activeId: 'models', renderSlot: (name, props, options) => { const slot = { slot: name, props, options }; slots.push(slot); return slot; },
    onSelect: id => selected.push(id), onClose: () => { closes++; } });
  const cleanups = effects.map(effect => effect());
  return { tree, events, slots, selected, modalLayers, focusCalls, document, window, drawerGear, menu,
    close: () => cleanups.forEach(cleanup => cleanup?.()), paint: () => frames.splice(0).forEach(callback => callback()),
    hideMenu: () => { menuVisible = false; }, makeMenuInert: () => { menuInert = true; }, get closes() { return closes; } };
}

test('Droy owns phone onboarding while desktop onboarding and explicit phone settings remain available', () => {
  const patched = patchSettings(settings);
  const start = patched.indexOf('function SettingsRoot(');
  const code = patched.slice(start, patched.indexOf('//#endregion', start));
  for (const [mobile, activeId] of [[true, 'general'], [true, undefined], [false, 'general']]) {
    const effects = [], refs = [];
    let refIndex = 0, stateIndex = 0, steps = [], closes = 0;
    const state = { open: true, activeId };
    const jsx = (type, props) => ({ type, props });
    const react = {
      useRef(value) { return refs[refIndex++] ?? (refs[refIndex - 1] = { current: value }); },
      useState(value) { const values = [undefined, new Set(), false, false]; return [values[stateIndex++], () => {}]; },
      useEffect(effect) { effects.push(effect); }, useLayoutEffect(effect) { effects.push(effect); },
      useCallback: callback => callback,
    };
    const document = { documentElement: { hasAttribute: name => mobile && name === 'data-droy-mobile' } };
    const Root = new Function('react', 'react_jsx_runtime', '_deepseek_ai_dsh_client_ui_primitives', 'SettingsRoot_module_css_default',
      'clsx', 'SettingsPanel', 'DesktopUpdateIndicator', 'document', 'window', `${code};return SettingsRoot;`)(react,
      { jsx, jsxs: jsx, Fragment: 'fragment' }, { Tooltip: 'tooltip', ConnectionIndicator: 'connection' },
      new Proxy({}, { get: (_target, key) => String(key) }), (...classes) => classes.filter(Boolean).join(' '),
      'settings-panel', 'desktop-update', document, {});
    const render = () => {
      refIndex = 0; stateIndex = 0;
      const tree = Root({ wide: true, reconnect() {}, useConnectionState: () => 'connected', useSections: () => [],
        useOnboardingSteps: () => steps, useSessions: select => select({ phase: 'ready', byId: {} }),
        renderSlot: (name, props) => ({ type: name, props }), t: value => value, useDesktopUpdate: () => ({}),
        openDesktopUpdate() {}, useStore: select => select(state),
        actions: { close() { state.open = false; closes++; }, openSection() {}, open() {}, select() {} },
        useShortcuts: select => select([]) });
      for (const effect of effects.splice(0)) effect();
      return tree;
    };
    render();
    steps = [{ id: 'account-welcome' }];
    const tree = render();
    assert.equal(closes, mobile ? 0 : 1);
    assert.equal(nodes(tree).some(node => node.type === 'settings.onboarding'), !mobile);
    if (mobile && activeId !== undefined) {
      state.open = false; state.activeId = undefined;
      assert.equal(nodes(render()).some(node => node.type === 'settings.onboarding'), false,
        'Leaving settings must return to the Droy setup flow without another automatic wizard');
    }
  }
});

test('phone settings omits the unsupported Host configuration document action', () => {
  const patched = patchSettings(settings);
  const start = patched.indexOf('const documentController =');
  const end = patched.indexOf('ctx.effect(', start);
  const actionStart = patched.indexOf('if (documentInjected !== void 0)');
  const actionEnd = patched.indexOf('ctx.slots.inject("settings.close"', actionStart);
  const createAction = new Function('ctx', 'document', 'window', 'SettingsDocumentStore', 'SettingsDocumentAction', 'NS',
    `${patched.slice(start, end)};${patched.slice(actionStart, actionEnd)};return documentController;`);
  for (const [mobileAttribute, nativeBridge, loopback, expected] of [
    [true, false, true, false], [false, true, true, false], [true, true, true, false],
    [false, false, true, true], [false, false, false, false],
  ]) {
    const registered = [];
    let describes = 0, controllers = 0;
    const ctx = { remote: { $host: { isLoopback: loopback } },
      configForms: { describe() { describes++; return {}; } },
      slots: { inject(_slot, callback) { callback(); }, register(action) { registered.push(action); } } };
    const controller = createAction(ctx,
      { documentElement: { hasAttribute: name => mobileAttribute && name === 'data-droy-mobile' } },
      nativeBridge ? { DroyNative: {} } : {},
      class { constructor() { controllers++; this.store = {}; } }, 'document-action', 'settings-general');
    assert.equal(controller !== undefined, expected);
    assert.equal(describes, Number(expected));
    assert.equal(controllers, Number(expected));
    assert.deepEqual(registered.map(action => action.id), expected ? ['open-document'] : []);
  }
});

test('phone settings exposes one full-window shell while retaining DSH modal and section contracts', () => {
  const rendered = renderSettings();
  const all = nodes(rendered.tree);
  const panel = all.find(node => node.props?.['data-droy-settings-panel']);
  assert.equal(rendered.tree.props['data-droy-settings'], true);
  assert.equal(panel.props.role, 'dialog');
  assert.equal(panel.props['aria-modal'], 'true');
  assert.equal(panel.props['data-shortcut-modal'], 'settings');
  assert.equal(rendered.modalLayers.length, 1);
  assert.equal(rendered.modalLayers[0][1], true);
  assert.deepEqual(rendered.events, ['droy-mobile-settings-open']);
  const header = all.find(node => node.props?.['data-droy-settings-header']);
  assert.ok(header);
  assert.ok(all.find(node => node.props?.id === panel.props['aria-labelledby']));
  const back = all.find(node => node.props?.['data-droy-settings-back']);
  assert.equal(back.props.children.type, 'dsh-back-icon');
  assert.equal(back.props.children.props.size, 24);
  assert.equal(back.props.children.props['aria-hidden'], true);
  back.props.onClick();
  assert.equal(rendered.closes, 1);
  const tabs = nodes(all.find(node => node.props?.['data-droy-settings-tabs']));
  assert.deepEqual(tabs.filter(node => node.props?.['data-droy-settings-section']).map(node => node.props['data-droy-settings-section']), ['general', 'models', 'droy-permissions']);
  tabs.find(node => node.props?.['data-droy-settings-section'] === 'droy-permissions').props.onClick();
  assert.deepEqual(rendered.selected, ['droy-permissions']);
  const options = all.find(node => node.props?.['data-droy-settings-options']);
  assert.equal(options.props.children.slot, 'settings.section');
  assert.equal(options.props.children.options.only, 'models');
  options.props.children.props.close();
  assert.equal(rendered.closes, 2);
  assert.ok(rendered.slots.some(slot => slot.slot === 'settings.action'));
});

test('desktop settings retains its original dialog title and close button', () => {
  const rendered = renderSettings({ mobile: false });
  const all = nodes(rendered.tree);
  assert.equal(rendered.tree.props['data-droy-settings'], undefined);
  assert.equal(all.some(node => node.props?.['data-droy-settings-back']), false);
  const panel = all.find(node => node.props?.['data-shortcut-modal'] === 'settings');
  assert.equal(panel.props['aria-labelledby'], 'settings-title');
  assert.ok(all.find(node => node.props?.id === 'settings-title'));
  assert.equal(rendered.events.length, 0);
  all.find(node => node.props?.className === 'close').props.onClick();
  assert.equal(rendered.closes, 1);
});

test('closing phone settings restores visible menu focus after the upstream launcher cleanup', () => {
  for (const destination of ['body', 'inert-drawer']) {
    const rendered = renderSettings();
    rendered.document.activeElement = {};
    rendered.close();
    assert.equal(rendered.focusCalls.length, 0);
    // DSH modal cleanup runs after our cleanup, attempting to focus its launcher.
    rendered.document.activeElement = destination === 'body' ? rendered.document.body : rendered.drawerGear;
    rendered.paint();
    assert.deepEqual(rendered.focusCalls, [{ preventScroll: true }]);
    assert.equal(rendered.document.activeElement, rendered.menu);
  }
});

test('settings focus restoration leaves other modal focus and hidden or inert menus alone', () => {
  const focusedModal = renderSettings();
  const field = {};
  focusedModal.close();
  focusedModal.document.activeElement = field;
  focusedModal.paint();
  assert.equal(focusedModal.focusCalls.length, 0);
  assert.equal(focusedModal.document.activeElement, field);
  for (const setup of [rendered => rendered.hideMenu(), rendered => rendered.makeMenuInert()]) {
    const rendered = renderSettings();
    setup(rendered);
    rendered.close();
    rendered.paint();
    assert.equal(rendered.focusCalls.length, 0);
  }
  const desktop = renderSettings({ mobile: false });
  desktop.close();
  desktop.paint();
  assert.equal(desktop.focusCalls.length, 0);
});

test('mobile starts with zero sidebar track and a working top-left menu', () => {
  const result = renderFrame();
  assert.match(result.frame.props.style.gridTemplateColumns,/^0px /);
  assert.equal(result.frame.props['data-droy-drawer-open'],undefined);
  const drawer = nodes(result.frame).find(node => node.props?.['data-droy-sidebar']);
  assert.equal(drawer.props.inert,'');
  assert.equal(drawer.props['aria-hidden'],true);
  const menu = nodes(result.frame).find(node => node.props?.['data-droy-menu']);
  assert.equal(menu.props['aria-label'],'打开菜单');
  menu.props.onClick();
  assert.equal(result.toggles,1);
});

test('mobile drawer covers a full-width conversation and has an independent close backdrop', () => {
  const result = renderFrame({open:true,width:320});
  assert.match(result.frame.props.style.gridTemplateColumns,/^0px /);
  assert.equal(result.frame.props.style['--droy-drawer-width'],'272px');
  const all = nodes(result.frame);
  const drawer = all.find(node => node.props?.['data-droy-sidebar']);
  assert.equal(drawer.props.role,'dialog');
  assert.equal(drawer.props['aria-modal'],'true');
  assert.equal(drawer.props.inert,undefined);
  assert.equal(drawer.props['aria-hidden'],undefined);
  assert.equal(drawer.props.children.props.width,272);
  const center = all.find(node => node.type?.name === 'CenterColumn');
  assert.equal(center.props.inert,true);
  all.find(node => node.props?.['data-droy-backdrop']).props.onClick();
  assert.equal(result.toggles,1);
  assert.equal(all.some(node => node.props?.side === 'sidebar'),false);
});

test('mobile drawer retains the same wide sidebar and conversation geometry across toggles', () => {
  const closed = renderFrame({open:false});
  const opened = renderFrame({open:true});
  const sidebar = result => nodes(result.frame).find(node => node.props?.['data-droy-sidebar']).props.children;
  assert.equal(sidebar(closed).props.collapsed,false);
  assert.deepEqual(sidebar(closed),sidebar(opened));
  assert.equal(closed.frame.props.style.gridTemplateColumns,opened.frame.props.style.gridTemplateColumns);
  assert.ok(nodes(closed.frame).find(node => node.props?.['data-droy-backdrop']));
});

test('desktop shell keeps its upstream rail and geometry', () => {
  const {frame} = renderFrame({mobile:false,width:800});
  assert.match(frame.props.style.gridTemplateColumns,/^56px /);
  assert.equal(nodes(frame).some(node=>node.props?.['data-droy-menu']),false);
});

test('mobile on a wide screen uses the same drawer toggle state as its renderer', () => {
  const patched = patchLayout(layout);
  const start = patched.indexOf('function createLayoutStore()');
  const end = patched.indexOf('//#endregion',start);
  const document = {documentElement:{hasAttribute:name=>name==='data-droy-mobile'}};
  const makeStore = new Function('_deepseek_ai_dsh_client_store','window','document',
    `function clampWidth(px,min,max){return Math.min(max,Math.max(min,Math.round(px)))};${patched.slice(start,end)};return createLayoutStore();`);
  const spec = makeStore({defineStore:spec=>spec},{innerWidth:1280},document);
  const state = spec.init();
  spec.actions.toggleSidebar(state);
  assert.equal(state.layoutInfo.narrowExpanded,true);
  assert.equal(renderFrame({open:state.layoutInfo.narrowExpanded,width:1280}).frame.props['data-droy-drawer-open'],true);
  spec.actions.toggleSidebar(state);
  assert.equal(state.layoutInfo.narrowExpanded,false);
});

test('upstream input drift fails visibly instead of applying a partial adaptation', async () => {
  assert.throws(()=>patchLayout(layout.replace('const narrow = viewport < SIDEBAR_AUTO_COLLAPSE;','const narrow = false;')),/patch anchor/);
  for (const [name,patch] of [['dsh-client-ui-settings-general',patchSettings],['dsh-client-ui-workspace',patchWorkspace],['dsh-client-ui-conversation',patchConversation],['dsh-client-ui-sidebar',patchSidebar],['dsh-client-ui-sidebar-right',patchSidebarRight],['dsh-client-ui-model-selection',patchModelSelection]]) {
    const source = await readFile(new URL(`${name}/lib/client.js`,packageRoot),'utf8');
    const output = patch(source);
    assert.notEqual(output,source);
    assert.doesNotThrow(()=>new Function(output));
    assert.throws(()=>patch(output),/patch anchor/);
  }
});


function functionExcerpt(source, name) {
  const start = source.indexOf(`function ${name}(`);
  assert.ok(start >= 0);
  const end = source.indexOf('\n\t\t}', start) + '\n\t\t}'.length;
  assert.ok(end > start);
  return source.slice(start, end);
}

function renderSessionRow({touch = true, input = touch ? 'touch' : 'mouse', mobile = true, blank = false, archived = false, menuOpen = false} = {}) {
  const patched = patchWorkspace(workspace);
  const effects = [], bindings = [], resets = [], slots = [], stateChanges = [], listeners = new Map();
  const jsx = (type, props) => ({type, props});
  const icons = new Proxy({}, {get: (_target, key) => key});
  const fakeRow = {id:'session-fixture'};
  const document = {documentElement:{hasAttribute:()=>mobile}};
  const window = {DroyTouch:{isTouchInteraction:()=>input === 'touch', bindSessionRow:(row, options) => {
    bindings.push({row,options}); return () => bindings.push('disposed');
  }, resetSessionRow: row => resets.push(row)}, addEventListener:(type, listener)=>listeners.set(type,listener),
    removeEventListener:(type, listener)=>{if (listeners.get(type) === listener) listeners.delete(type);}};
  const react = {useId:()=>':session-fixture:',useState:()=>[menuOpen, value=>stateChanges.push(value)],useMemo:fn=>fn(),useRef:value=>({current:value}),useEffect:fn=>effects.push(fn)};
  const make = new Function('react','react_jsx_runtime','_deepseek_ai_dsh_client_ui_primitives','Rows_module_css_default','clsx','useTitleMarquee','displayTitle','sessionStatuses','timeLabel','SessionStatusDots','PinnedIndicator','SessionHoverContent','document','window',
    `${functionExcerpt(patched,'isDroyTouchInteraction')}\n${functionExcerpt(patched,'SessionNodeItem')};return SessionNodeItem;`);
  const Component = make(react,{jsx,jsxs:jsx},icons,new Proxy({},{get:(_t,key)=>key}),(...x)=>x.filter(Boolean).join(' '),()=>({enter(){},leave(){}}),n=>n.title,()=>[{state:'idle'}],()=>'', 'status','pinned','hover',document,window);
  const tree = Component({node:{id:'session-fixture',title:'Fixture',blank,archived},currentId:'another',now:0,onOpen:id=>stateChanges.push(['opened',id]),onRenameRequest:()=>stateChanges.push('rename'),
    renderSlot:(name,props,options)=>{const slot={name,props,options};slots.push(slot);return slot;},drag:{start(){},end(){},active:false},t:key=>key});
  const row = tree.props.anchor;
  row.props.ref.current = fakeRow;
  const cleanups = effects.map(effect=>effect());
  return {tree,row,bindings,resets,slots,stateChanges,listeners,window,getDocument:()=>document,close:()=>cleanups.forEach(fn=>fn?.())};
}

test('touch rows keep DSH menu contributions and expose only the original archive slot to swipe', () => {
  const rendered = renderSessionRow();
  const all = nodes(rendered.tree.props.anchor);
  assert.equal(rendered.row.props['data-droy-session-row'],'session-fixture');
  assert.equal(rendered.row.props.draggable,false);
  assert.equal(rendered.row.props.onPointerEnter,undefined);
  assert.equal(rendered.tree.props.disabled,true);
  assert.equal(all.find(node=>node.props?.className==='title').props.onDoubleClick,undefined);
  const menu = all.find(node=>node.type==='Menu');
  const trigger = menu.props.anchor;
  assert.equal(trigger.props['data-droy-session-menu'],true);
  trigger.props.onClick();
  assert.equal(rendered.stateChanges[0],true);
  assert.equal(menu.props.closeOnPointerLeave,false);
  assert.ok(rendered.slots.some(slot=>slot.name==='sidebar.workspaces.session.menu.item' && !slot.options?.only));
  const action = all.find(node=>node.props?.['data-droy-swipe-action']);
  assert.equal(action.props.inert,'');
  assert.equal(action.props.children.name,'sidebar.workspaces.session.row.action');
  assert.equal(action.props.children.options.only,'archive');
  assert.equal(rendered.bindings.length,1);
  assert.equal(rendered.bindings[0].options.sessionId,'session-fixture');
  rendered.bindings[0].options.openMenu();
  assert.equal(rendered.stateChanges.at(-1),true);
  rendered.bindings[0].options.closeMenu();
  assert.equal(rendered.stateChanges.at(-1),false);
  rendered.listeners.get('droy-touch-ready')();
  assert.equal(rendered.bindings[1],'disposed');
  rendered.close();
  assert.equal(rendered.listeners.size,0);
});

test('blank rows have neither long-press binding nor swipe/menu; desktop preserves original pointer actions', () => {
  const blank = renderSessionRow({blank:true});
  assert.equal(blank.bindings.length,0);
  assert.equal(nodes(blank.row).some(node=>node.props?.['data-droy-session-menu'] || node.props?.['data-droy-swipe-action']),false);
  for (const options of [{touch:false,mobile:false},{touch:true,mobile:false}]) {
    const desktop = renderSessionRow(options);
    assert.equal(desktop.row.props.draggable,true);
    assert.equal(typeof desktop.row.props.onPointerEnter,'function');
    assert.equal(desktop.tree.props.disabled,false);
    assert.equal(typeof nodes(desktop.row).find(node=>node.props?.className==='title').props.onDoubleClick,'function');
    assert.equal(nodes(desktop.row).find(node=>node.type==='Menu').props.closeOnPointerLeave,true);
    assert.equal(nodes(desktop.row).find(node=>node.props?.['data-droy-session-surface']).props.style.display,'contents');
    assert.equal(nodes(desktop.row).some(node=>node.props?.['data-droy-swipe-action']),false);
  }
  assert.equal(renderSessionRow({menuOpen:true}).resets.length,1);
});

test('phone row structure survives keyboard and mouse state before the next touch', () => {
  for (const input of ['touch','keyboard','mouse']) {
    const rendered=renderSessionRow({mobile:true,input});
    const all=nodes(rendered.row);
    const surface=all.find(node=>node.props?.['data-droy-session-surface']);
    assert.equal(surface.props.style,undefined,`${input}: mobile surface must keep its own layout box`);
    assert.ok(all.find(node=>node.props?.['data-droy-swipe-action']),`${input}: archive layer must remain mounted`);
    assert.equal(rendered.row.props.draggable,false);
    assert.equal(rendered.row.props.onPointerEnter,undefined);
    assert.equal(rendered.tree.props.disabled,true);
    assert.equal(all.find(node=>node.props?.className==='title').props.onDoubleClick,undefined);
    assert.equal(all.find(node=>node.type==='Menu').props.closeOnPointerLeave,false);
    rendered.close();
  }
});

function workspaceServiceFixture() {
  const source = patchWorkspace(workspace);
  const clear = source.slice(source.indexOf('clearMain(closeDrawer = true)'),source.indexOf('\n\t\t\treplaceMain(',source.indexOf('clearMain(closeDrawer = true)')));
  const archive = source.slice(source.indexOf('async archiveSession(sessionId,'),source.indexOf('\n\t\t\tasync pinSession(',source.indexOf('async archiveSession(sessionId,')));
  const observed = source.slice(source.indexOf('clearArchivedCurrent() {'),source.indexOf('\n\t\t\tclearMain(',source.indexOf('clearArchivedCurrent() {')));
  const events=[],calls=[];
  let resolve, reject;
  const pending = new Promise((yes,no)=>{resolve=yes;reject=no;});
  const service = new Function('document','droySessionOperation',`return new class {${clear}\n${archive}\n${observed}};`)({querySelector:()=>({})},(_id,_action,fn)=>fn());
  Object.assign(service,{mainReference:{sessionId:'current',release:()=>events.push('released')},selection:{set:value=>events.push(value)},ctx:{layout:{toggleSidebar:()=>events.push('toggle'),selectPanel:()=>events.push('panel')}},
    workspaces:{archiveSession:(...args)=>{calls.push(args);return pending;},unarchiveSession:()=>pending,list:{getSnapshot:()=>({archivedSessionIds:['current']})}}});
  return {service,events,calls,resolve,reject};
}

test('archive completion and early archived-set projection both clear current conversation without closing drawer', async () => {
  for(const observedFirst of [false,true]) {
    const f=workspaceServiceFixture();
    const action=f.service.archiveSession('current');
    if(observedFirst) assert.equal(f.service.clearArchivedCurrent(),true);
    f.resolve();await action;
    assert.equal(f.service.mainReference,undefined);
    assert.equal(f.events.filter(value=>value==='released').length,1);
    assert.equal(f.events.includes('toggle'),false);
    assert.deepEqual(f.calls,[['current',{}]]);
  }
  const failed=workspaceServiceFixture();
  const action=failed.service.archiveSession('current');failed.reject(new Error('fixture refusal'));
  await assert.rejects(action,/fixture refusal/);
  assert.equal(failed.service.mainReference.sessionId,'current');
  assert.deepEqual(failed.events,[]);
  const user=workspaceServiceFixture();user.service.clearMain();
  assert.equal(user.events.filter(value=>value==='toggle').length,1);
});

test('pending archive operations share one host request and release their guard after rejection or completion', async () => {
  const source=patchWorkspace(workspace);
  const start=source.indexOf('const droySessionOperations = new Map();');
  const end=source.indexOf('\n\t\tfunction droySessionAction(',start);
  const operation=new Function(`${source.slice(start,end)};return droySessionOperation;`)();
  let calls=0, reject;
  const pending=new Promise((_yes,no)=>{reject=no;});
  const first=operation('same','archive',()=>{calls++;return pending;});
  const second=operation('same','archive',()=>{calls++;});
  assert.equal(first,second);
  await Promise.resolve();assert.equal(calls,1);
  reject(new Error('refusal'));await assert.rejects(first,/refusal/);
  await operation('same','archive',()=>{calls++;});
  assert.equal(calls,2);
});

function composerHandlers({touch=true,mobile=true}={}) {
  const source=patchConversation(conversation);
  const start=source.indexOf('const keepFocus = (e) => {');
  const end=source.indexOf('\n\t\t\tconst onWorkspaceKeyDown',start);
  const mark=source.indexOf('"data-droy-composer-add"');
  const mouse=source.indexOf('onMouseDown: (event) => {',mark);
  const mouseEnd=source.indexOf('\n\t\t\t\t\t\t\t\t\t\t\t\tonClick:',mouse);
  const mouseCode=source.slice(mouse+'onMouseDown: '.length,mouseEnd).replace(/,$/,'');
  const autoStart=source.indexOf('if (locked || editor === null || isDroyTouchInteraction()) return;');
  const autoEnd=source.indexOf('\n\t\t\t}, [',autoStart);
  const caret={anchor:2,focus:2};const calls=[];
  const editor={getRootElement:()=>({focus:()=>calls.push('root-focus')})};
  const make=new Function('document','window','editor','keyboard','toggleCommandMenu','focusDraftEditor','revealSelection','keepDraftFocus',
    `${functionExcerpt(source,'isDroyTouchInteraction')}\n${source.slice(start,end)};return {mouseDown:${mouseCode},click:onToggleCommandMenu,passiveFocus:()=>{const locked=false;${source.slice(autoStart,autoEnd)}}};`);
  const handlers=make({documentElement:{hasAttribute:()=>mobile}},{DroyTouch:{isTouchInteraction:()=>touch}},editor,{caretSpan:()=>caret},span=>calls.push(['menu',span]),()=>calls.push('draft-focus'),()=>{},()=>calls.push('keep-focus'));
  return {handlers,calls,caret};
}

test('touch plus preserves caret and menu action without either proactive editor focus path', () => {
  const f=composerHandlers();let prevented=0;
  f.handlers.mouseDown({preventDefault:()=>prevented++});f.handlers.click();f.handlers.passiveFocus();
  assert.equal(prevented,1);
  assert.deepEqual(f.calls,[['menu',f.caret]]);
  for(const options of [{touch:false},{mobile:false,touch:true}]) {
    const desktop=composerHandlers(options);
    desktop.handlers.mouseDown({preventDefault(){}});desktop.handlers.click();desktop.handlers.passiveFocus();
    assert.deepEqual(desktop.calls,['keep-focus','draft-focus',['menu',desktop.caret],'draft-focus']);
  }
});

test('client composition injects one controller into the existing ModuleLoader factory and rejects drift', async () => {
  const client=await readFile(new URL('../client.js',import.meta.url),'utf8');
  const touch=await readFile(new URL('../touch.js',import.meta.url),'utf8');
  const output=composeMobileClient(client,touch);
  assert.equal(output.includes('/* DROY_TOUCH_MODULE */'),false);
  assert.equal(output.includes('/* DROY_TOKEN_USAGE_MODULE */'),false);
  let loads=0;
  vm.runInNewContext(output,{window:{__ModuleLoader__:{load:()=>loads++}}});
  assert.equal(loads,1);
  assert.throws(()=>composeMobileClient('no marker',touch),/exactly once/);
  assert.throws(()=>composeMobileClient(client+'/* DROY_TOUCH_MODULE */',touch),/exactly once/);
  assert.throws(()=>composeMobileClient(client,''),/declaration missing/);
});


test('phone settings cleanup routes editable focus through touch policy without changing other modal controls', () => {
  const rendered=renderSettings();const calls=[];
  rendered.window.DroyTouch={isTouchInteraction:()=>true,restoreFocus:(previous,fallback)=>calls.push([previous,fallback])};
  const editor={matches:()=>true};
  rendered.close();rendered.document.activeElement=editor;rendered.paint();
  assert.deepEqual(calls,[[editor,rendered.menu]]);
});

test('composer Back binding dismisses the public trigger controller and follows current launcher state', () => {
  const source=patchConversation(conversation);
  const start=source.indexOf('const commandMenuOpenRef = (0, react.useRef)(commandMenuOpen);');
  const end=source.indexOf('\n\t\t\tconst [activity,',start);
  const effects=[],registrations=[],listeners=new Map();let dismissed=0,disposed=0;
  const react={useRef:value=>({current:value}),useEffect:effect=>effects.push(effect)};
  const window={DroyTouch:{bindMenu:(id,binding)=>{registrations.push({id,binding});return ()=>disposed++;}},
    addEventListener:(type,callback)=>listeners.set(type,callback),removeEventListener:type=>listeners.delete(type)};
  const reference=new Function('react','window','keyboard','dismissCommandMenu','commandMenuOpen',`${source.slice(start,end)};return commandMenuOpenRef;`)(react,window,{},()=>dismissed++,true);
  const cleanup=effects[0]();
  assert.equal(registrations[0].id,'composer-add');
  assert.equal(registrations[0].binding.isOpen(),true);
  registrations[0].binding.close();assert.equal(dismissed,1);
  reference.current=false;
  registrations[0].binding.close();assert.equal(dismissed,1);
  listeners.get('droy-touch-ready')();assert.equal(disposed,1);
  cleanup();assert.equal(disposed,2);assert.equal(listeners.size,0);
  // Execute the actual narrow injection against a public DSH controller stand-in.
  const injected=source.slice(source.indexOf('dismissCommandMenu: inputTriggers'),source.indexOf('\n',source.indexOf('dismissCommandMenu: inputTriggers'))).replace(/,$/,'');
  const dismiss=new Function('inputTriggers',`return ({${injected}}).dismissCommandMenu;`)({dismiss:()=>dismissed++});
  dismiss();assert.equal(dismissed,2);
});

test('original archive callbacks report completion, confirmation and failure without replacing DSH notices', async () => {
  const source=patchWorkspace(workspace);
  const start=source.indexOf('const archiveInjected = () => ({');
  const end=source.indexOf('\n\t\t\tinstallWorkspaceShortcuts',start);
  for(const outcome of ['success','active','failed']) {
    const phases=[],notices=[],requests=[];
    const behavior=async()=>{if(outcome!=='success') throw Object.assign(new Error('fictional private failure'),{active:outcome==='active'});};
    const make=new Function('uiWorkspace','droySessionAction','notify','activeSessionRefusal','sessions','archiveRequest','unarchiveSession','archivedSet','console',`${source.slice(start,end)};return archiveInjected();`);
    const api=make({archiveSession:behavior},(id,action,phase)=>phases.push({id,action,phase}),notice=>notices.push(notice),error=>error.active?['fixture activity']:undefined,
      {list:{getSnapshot:()=>({byId:{fixture:{displayTitle:'Fixture'}}})}},{set:value=>requests.push(value)},()=>{}, {},{warn(){}});
    api.archiveSession('fixture');await new Promise(setImmediate);
    assert.deepEqual(phases.map(item=>item.phase),['pending',outcome==='success'?'succeeded':outcome==='active'?'needsConfirmation':'failed']);
    assert.ok(phases.every(item=>item.id==='fixture' && item.action==='archive'));
    if(outcome==='success') assert.deepEqual(notices,[{kind:'archived',sessionId:'fixture'}]);
    if(outcome==='active') assert.deepEqual(requests,[{sessionId:'fixture',displayTitle:'Fixture',activity:['fixture activity']}]);
    if(outcome==='failed') {assert.deepEqual(notices,[]);assert.deepEqual(requests,[]);}
  }
});

test('stop-and-archive retains confirmation cancellation and rethrows Host failure while reporting safe status', async () => {
  const source=patchWorkspace(workspace);
  const start=source.indexOf('const archiveConfirmInjected = () => ({');
  const end=source.indexOf('\n\t\t\tconst forkInjected',start);
  for(const rejected of [false,true]) {
    const phases=[],notices=[],operations=[];let pending={sessionId:'fixture'};
    const make=new Function('uiWorkspace','droySessionAction','notify','archiveRequest',`${source.slice(start,end)};return archiveConfirmInjected();`);
    const api=make({archiveSession:async(id,options)=>{operations.push([id,options]);if(rejected)throw new Error('host refused');}},(_id,_action,phase)=>phases.push(phase),notice=>notices.push(notice),
      {getSnapshot:()=>pending,set:value=>pending=value});
    api.settleSessionArchive('cancelled');assert.equal(pending,null);assert.deepEqual(phases,['cancelled']);assert.deepEqual(operations,[]);
    phases.length=0;
    if(rejected) await assert.rejects(api.stopAndArchiveSession('fixture'),/host refused/);
    else await api.stopAndArchiveSession('fixture');
    assert.deepEqual(operations,[['fixture',{stopActivity:true}]]);
    assert.deepEqual(phases,['pending',rejected?'failed':'succeeded']);
    assert.equal(notices.length,rejected?0:1);
  }
});

test('original pin and archive row buttons expose their own DOM identity through slot wrappers', () => {
  const source=patchWorkspace(workspace);
  const jsx=(type,props)=>({type,props});
  const primitives=new Proxy({},{get:(_target,key)=>key});
  const classes={iconButton:'dsh-icon-button'};
  const operations=[];
  const pin=new Function('react_jsx_runtime','_deepseek_ai_dsh_client_ui_primitives','Rows_module_css_default','usePinState',`${functionExcerpt(source,'PinSessionRowButton')};return PinSessionRowButton;`)(
    {jsx},primitives,classes,()=>({pinned:false,archived:false}));
  const pinButton=pin({sessionId:'fixture',pinSession:id=>operations.push(['pin',id]),unpinSession:()=>{},t:key=>key}).props.children;
  assert.equal(pinButton.type,'button');
  assert.equal(pinButton.props['data-droy-session-pin'],'fixture');
  pinButton.props.onClick();
  const archive=new Function('react_jsx_runtime','_deepseek_ai_dsh_client_ui_primitives','Rows_module_css_default',`${functionExcerpt(source,'ArchiveSessionRowButton')};return ArchiveSessionRowButton;`)(
    {jsx},primitives,classes);
  for(const archived of [false,true]) {
    const button=archive({sessionId:'fixture',useArchived:select=>select(new Set(archived?['fixture']:[])),archiveSession:id=>operations.push(['archive',id]),unarchiveSession:id=>operations.push(['unarchive',id]),t:key=>key}).props.children;
    assert.equal(button.type,'button');
    assert.equal(button.props['data-droy-session-archive'],'fixture');
    assert.equal(button.props['data-droy-session-action'],archived?'unarchive':'archive');
    button.props.onClick();
  }
  assert.deepEqual(operations,[['pin','fixture'],['archive','fixture'],['unarchive','fixture']]);
});

test('workspace header keeps mobile gesture and hover rules after keyboard or mouse events', () => {
  const source=patchWorkspace(workspace);
  const jsx=(type,props)=>({type,props});
  const make=new Function('react','react_jsx_runtime','_deepseek_ai_dsh_client_ui_primitives','Rows_module_css_default','clsx','WorkspaceHoverContent','document','window',
    `${functionExcerpt(source,'ProjectRowItem')};return ProjectRowItem;`);
  for(const mobile of [true,false]) for(const input of ['touch','keyboard','mouse']) {
    const Component=make({useState:()=>[false,()=>{}]},{jsx,jsxs:jsx},new Proxy({},{get:(_target,key)=>key}),new Proxy({},{get:(_target,key)=>key}),(...classes)=>classes.filter(Boolean).join(' '),'hover',
      {documentElement:{hasAttribute:()=>mobile}},{DroyTouch:{isTouchInteraction:()=>input==='touch'}});
    const tree=Component({group:{key:'group',workspaceId:'workspace',label:'Fixture workspace',createdAt:123,expanded:true},onToggle(){},onCreate(){},actions:{rename(){},delete(){}},drag:{start(){},end(){}},t:key=>key});
    assert.equal(tree.props.disabled,mobile);
    assert.equal(tree.props.anchor.props.draggable,!mobile);
    assert.equal(typeof tree.props.anchor.props.onDragStart,mobile?'undefined':'function');
    const menu=nodes(tree.props.anchor).find(node=>node.type==='Menu');
    assert.equal(menu.props.closeOnPointerLeave,!mobile);
  }
});

test('workspace chip second touch closes the controlled picker before another outside-pointer handler can reopen it', () => {
  const source=patchConversation(conversation);
  const jsx=(type,props)=>({type,props});
  let open=true,stopped=false;
  const Chip=new Function('react_jsx_runtime','HeroShell_module_css_default','_deepseek_ai_dsh_client_ui_primitives','document','window',`${functionExcerpt(source,'WorkspaceChip')};return WorkspaceChip;`)(
    {jsx,jsxs:jsx},{workspace:'workspace'},new Proxy({},{get:(_target,key)=>key}),{documentElement:{hasAttribute:()=>true}},{});
  const button=Chip({buttonRef:{current:null},label:'Fixture',menuOpen:open,onClick:()=>{open=!open;},t:key=>key});
  assert.equal(button.props['data-droy-workspace-trigger'],true);
  assert.equal(button.props['aria-expanded'],true);
  button.props.onPointerDown({stopPropagation:()=>{stopped=true;}});
  if(!stopped)open=false; // DSH's existing document pointerdown outside handler.
  button.props.onClick();
  assert.equal(open,false);
});

test('picking the existing workspace only closes; a different workspace keeps the original async navigation', async () => {
  const source=patchConversation(conversation);
  const start=source.indexOf('onPick: (workspaceId) => {',source.indexOf('const heroWorkspaceRow ='));
  const end=source.indexOf('\n\t\t\t\t\t\tonClose:',start);
  const callback=source.slice(start+'onPick: '.length,end).replace(/,$/,'');
  for(const chosen of ['current','different']) {
    const calls=[];const state={open:true,pending:undefined};
    const pick=new Function('pendingWorkspaceId','sessionWorkspace','setPickerOpen','setPendingWorkspaceId','selectWorkspace',`return (${callback});`)(
      undefined,{workspaceId:'current'},value=>state.open=value,value=>state.pending=typeof value==='function'?value(state.pending):value,id=>{calls.push(id);return Promise.resolve();});
    pick(chosen);await Promise.resolve();
    assert.equal(state.open,false);
    assert.deepEqual(calls,chosen==='current'?[]:['different']);
    assert.equal(state.pending,chosen==='current'?undefined:'different');
  }
});

test('only blank sessions carrying original DSH drafts become extra discoverable rows', () => {
  const source=patchWorkspace(workspace);
  const visible=new Function(`${functionExcerpt(source,'sessionVisible')};return sessionVisible;`)();
  const archived=new Set();
  assert.equal(visible({id:'empty',blank:true},'selected',archived,'default'),false);
  assert.equal(visible({id:'selected',blank:true},'selected',archived,'default'),true);
  assert.equal(visible({id:'text-or-image',blank:true,droyDraft:true},'selected',archived,'default'),true);
  assert.equal(visible({id:'history',blank:false,droyDraft:true},'selected',archived,'default'),true);
  archived.add('text-or-image');
  assert.equal(visible({id:'text-or-image',blank:true,droyDraft:true},'selected',archived,'default'),false);
  const display=new Function(`${functionExcerpt(source,'displayTitle')};return displayTitle;`)();
  assert.equal(display({blank:true,droyDraft:true},key=>({"session.draft":"未发送草稿"})[key]),'未发送草稿');
});

test('session close animation commits after completion and stale close cannot dismiss a reopened menu', () => {
  const rendered=renderSessionRow({menuOpen:true});const completions=[];const element={};
  rendered.window.DroyTouch.closeSessionMenuAnimated=(_element,done)=>completions.push(done);
  rendered.window.DroyTouch.animateSessionMenu=()=>{};
  // The real element is obtained through the stable class, never a session title.
  const menu=nodes(rendered.row).find(node=>node.type==='Menu');
  assert.match(menu.props.listClassName,/^droy-session-menu droy-session-menu-/);
  // renderSessionRow's document source is returned below for this DOM lookup.
  rendered.getDocument().querySelector=()=>element;
  menu.props.onClose();
  assert.deepEqual(rendered.stateChanges,[]);
  rendered.bindings[0].options.openMenu();
  completions.shift()();
  assert.deepEqual(rendered.stateChanges,[true]);
  menu.props.onClose();completions.shift()();
  assert.deepEqual(rendered.stateChanges,[true,false]);
});

test('actual right panel root and content carry safe-area hooks without changing desktop width or controls', async () => {
  const source=patchSidebarRight(await readFile(new URL('dsh-client-ui-sidebar-right/lib/client.js',packageRoot),'utf8'));
  const jsx=(type,props)=>({type,props});
  for(const mobile of [true,false]) {
    const Panel=new Function('react_jsx_runtime','SidebarRight_module_css_default','_deepseek_ai_dsh_client_ui_dockkit','document','window','PanelChrome','bodiesFor','titlesFor','intentsFor','dockLabels',
      `${functionExcerpt(source,'SidebarPanel')};return SidebarPanel;`)({jsx},{panel:'original-panel',panelBody:'original-body'},
      {DockLayout:'DSH-Dock',canSplit:()=>true,dockPaneIds:()=>[]},{documentElement:{hasAttribute:()=>mobile}},{},'chrome',()=>{},()=>{},()=>{},()=>{});
    const tree=Panel({sessionId:'fixture',surface:{layout:{expanded:true,floats:[]}},actions:{},t:key=>key,renderSlot:()=>{},width:320,fullscreen:false,useTabTypes:select=>select([]),shortcuts:[]});
    assert.equal(tree.props['data-droy-mobile-panel'],true);
    assert.equal(tree.props.style.width,mobile?'100%':320);
    assert.equal(tree.props.children.props['data-droy-mobile-panel-body'],true);
    assert.equal(tree.props.children.props.children.type,'DSH-Dock');
  }
  const Chrome=new Function('react_jsx_runtime','_deepseek_ai_dsh_client_ui_primitives','SidebarRight_module_css_default','FullscreenGlyph','ExitFullscreenGlyph',`${functionExcerpt(source,'PanelChrome')};return PanelChrome;`)(
    {jsx,jsxs:jsx},new Proxy({},{get:(_target,key)=>key}),{},'full','exit');
  let toggles=0;
  const chrome=Chrome({sessionId:'fixture',fullscreen:false,actions:{toggleExpanded:()=>toggles++},t:key=>key,shortcuts:[],toggleFullscreen(){}});
  assert.equal(chrome.props['data-droy-panel-chrome'],true);
  const collapse=nodes(chrome).find(node=>node.props?.['data-sidebar-right-toggle']);
  collapse.props.onClick();assert.equal(toggles,1);
});


test('repeated close requests keep one valid animation completion', () => {
  const rendered=renderSessionRow({menuOpen:true});const completions=[];
  rendered.getDocument().querySelector=()=>({});
  rendered.window.DroyTouch.closeSessionMenuAnimated=(_element,done)=>{ completions[0]=done; };
  const menu=nodes(rendered.row).find(node=>node.type==='Menu');
  menu.props.onClose();menu.props.onClose();
  assert.equal(completions.length,1);
  assert.deepEqual(rendered.stateChanges,[]);
  completions[0]();
  assert.deepEqual(rendered.stateChanges,[false]);
});


test('hardware keyboard menu dismissal remains immediate to preserve upstream focus restoration', () => {
  const rendered=renderSessionRow({menuOpen:true,input:'keyboard'});let animations=0;
  rendered.getDocument().querySelector=()=>({});
  rendered.window.DroyTouch.closeSessionMenuAnimated=()=>animations++;
  nodes(rendered.row).find(node=>node.type==='Menu').props.onClose();
  assert.equal(animations,0);
  assert.deepEqual(rendered.stateChanges,[false]);
});


test('mobile landscape right panel owner uses full-window mode without reserving a desktop push track', async () => {
  const source=patchSidebarRight(await readFile(new URL('dsh-client-ui-sidebar-right/lib/client.js',packageRoot),'utf8'));
  const start=source.indexOf('const autoFullscreen =');
  const end=source.indexOf('\n\t\t\t(0, react.useEffect)',start);
  const code=source.slice(start,end);
  for(const mobile of [true,false]) {
    const result=new Function('document','window','react','viewportWidth','surface','shown','measureRoom',`${code};return {autoFullscreen,fullscreen,track};`)(
      {documentElement:{hasAttribute:()=>mobile}},{},{useRef:value=>({current:value}),useCallback:fn=>fn},900,{layout:{mode:'push'}},true,()=>{});
    assert.deepEqual(result,{autoFullscreen:mobile,fullscreen:mobile,track:!mobile});
  }
});

test('model picker registers its original controlled close and real menu element with touch lifecycle', async () => {
  const source=patchModelSelection(await readFile(new URL('dsh-client-ui-model-selection/lib/client.js',packageRoot),'utf8'));
  const start=source.indexOf('const droyOpenRef =');
  const end=source.indexOf('\n\t\t\tconst [pane,',start);
  const effects=[],registered=[],listeners=new Map(),closes=[],opened=[];let disposed=0;
  const menu={id:'actual-model-menu'};
  const reference=new Function('react','window','open','close','menuRef',`${source.slice(start,end)};return droyOpenRef;`)(
    {useRef:value=>({current:value}),useEffect:effect=>effects.push(effect)},
    {DroyTouch:{bindMenu:(id,options)=>{registered.push({id,options});return()=>disposed++;},menuOpened:id=>opened.push(id)},
      addEventListener:(type,fn)=>listeners.set(type,fn),removeEventListener:type=>listeners.delete(type)},true,restore=>closes.push(restore),{current:menu});
  const cleanup=effects.map(effect=>effect());
  assert.equal(registered[0].id,'model-picker');
  assert.equal(registered[0].options.isOpen(),true);
  assert.equal(registered[0].options.getElement(),menu);
  registered[0].options.close();assert.deepEqual(closes,[false]);
  assert.deepEqual(opened,['model-picker']);
  reference.current=false;assert.equal(registered[0].options.isOpen(),false);
  listeners.get('droy-touch-ready')();assert.equal(disposed,1);
  cleanup.forEach(fn=>fn?.());assert.equal(disposed,2);assert.equal(listeners.size,0);
});

test('scrolling composer command menu uses the actual available chat viewport', () => {
  const style = droyTriggerMenuPosition({ left: 24, top: 128.75, width: 265.05 },
    { left: 0, top: 120.75, right: 318, bottom: 403.05, width: 318, height: 282.3 });
  assert.equal(style.position, 'fixed');
  assert.equal(style.top, 124.75);
  assert.equal(style.left, 24);
  assert.ok(style.maxHeight > 270, 'large-font keyboard menu must not collapse to the old 8px cap');
  assert.ok(style.top + style.maxHeight <= 403.05);
  const shifted = droyTriggerMenuPosition({ left: -20, top: 100, width: 500 },
    { left: 12, top: 90, right: 312, bottom: 290, width: 300, height: 200 });
  assert.equal(shifted.left, 16); assert.equal(shifted.width, 292);
  assert.ok(shifted.top >= 90 && shifted.top + shifted.maxHeight <= 290);
});

function triggerMenuFixture(source, { mobile = true, usage = true, overflowing = true } = {}) {
  const patched = patchInputTrigger(source);
  const code = patched.slice(patched.indexOf('function MenuView('), patched.indexOf('\n\t\t//#endregion', patched.indexOf('function MenuView(')));
  const jsx = (type, props) => ({ type, props });
  class Node {}
  const anchor = new Node(), card = new Node(), seat = new Node(), scroll = new Node(), menuNode = new Node();
  const composerButton = new Node(), menuItem = new Node(), outside = new Node();
  card.contains = target => target === composerButton;
  card.closest = selector => selector === '[data-composer-seat]' ? seat : null;
  card.getBoundingClientRect = () => ({ left: 24, top: 128.75, width: 265.05 });
  anchor.closest = selector => selector === '[data-composer-card]' ? card : null;
  seat.querySelector = () => usage ? {} : null;
  seat.scrollHeight = overflowing ? 374 : 282; seat.clientHeight = 282;
  seat.closest = () => scroll;
  let bottom = 403.05;
  scroll.getBoundingClientRect = () => ({ left: 0, top: 120.75, right: 318, bottom, width: 318, height: bottom - 120.75 });
  menuNode.contains = target => target === menuItem;
  const view = { scrollTop: 0, clientHeight: 200, scrollHeight: 700 };
  const listeners = new Map(), windowListeners = new Map(), observers = [];
  const document = { body: {}, documentElement: { hasAttribute: () => mobile }, getElementById: () => null,
    addEventListener: (name, listener) => listeners.set(name, listener),
    removeEventListener: (name, listener) => { if (listeners.get(name) === listener) listeners.delete(name); } };
  const window = { innerWidth: 320, innerHeight: 686,
    addEventListener: (name, listener) => windowListeners.set(name, listener),
    removeEventListener: (name, listener) => { if (windowListeners.get(name) === listener) windowListeners.delete(name); },
    visualViewport: { offsetLeft: 0, offsetTop: 0, width: 320, height: 686,
      addEventListener() {}, removeEventListener() {} } };
  class ResizeObserver {
    constructor(callback) { this.callback = callback; observers.push(this); }
    observe() {} disconnect() { this.disconnected = true; }
  }
  const refs = [{ current: menuNode }, { current: view }, { current: anchor }], state = [];
  let refIndex = 0, stateIndex = 0, effects = [], disposers = [], dismissals = 0, picks = [];
  const react = { Fragment: 'fragment', useSyncExternalStore: (_subscribe, get) => get(),
    useRef: () => refs[refIndex++],
    useState(value) { const index = stateIndex++; if (!(index in state)) state[index] = value; return [state[index], next => { state[index] = typeof next === 'function' ? next(state[index]) : next; }]; },
    useLayoutEffect: effect => effects.push(effect), useEffect: effect => effects.push(effect), useCallback: fn => fn };
  const portalCalls = [];
  const Menu = new Function('react', 'react_jsx_runtime', 'react_dom', '_deepseek_ai_dsh_client_ui_primitives', 'MenuView_module_css_default', 'clsx', 'optionId', 'droyTriggerMenuPosition', 'document', 'window', 'ResizeObserver', 'Node', `const MAX_HEIGHT=400,TOP_MARGIN=84;${code};return MenuView;`)(
    react, { jsx, jsxs: jsx }, { createPortal(tree, host) { portalCalls.push({ tree, host }); return jsx('portal', { children: tree }); } },
    { MenuSurface: 'surface', useAnchoredMaxHeight: () => 8 }, new Proxy({}, { get: (_target, key) => String(key) }),
    (...classes) => classes.filter(Boolean).join(' '), (source, index) => `option-${source}-${index}`, droyTriggerMenuPosition,
    document, window, ResizeObserver, Node);
  const menuState = { open: true, highlight: null, groups: [{ source: 'command', status: 'ready', items: [{ name: 'file', label: '文件' }] }] };
  const props = { menu: { subscribe() {}, getSnapshot: () => menuState }, headers: { subscribe() {}, getSnapshot: () => new Map() },
    onPick: (...args) => picks.push(args), onCrumb() {}, onHover() {}, onDismiss() { dismissals++; }, t: value => value };
  const render = () => { for (const dispose of disposers) dispose?.(); disposers = []; effects = []; refIndex = 0; stateIndex = 0; const tree = Menu(props); disposers = effects.map(effect => effect()); return tree; };
  return { render, portalCalls, document, windowListeners, observers, composerButton, menuItem, outside, picks,
    resize(value) { bottom = value; observers.at(-1)?.callback(); },
    pointerDown(target) { listeners.get('pointerdown')?.({ target }); },
    dismissMenu() { menuState.open = false; },
    close() { for (const dispose of disposers) dispose?.(); }, get dismissals() { return dismissals; } };
}

test('only an overflowing mobile composer with totals portals the existing menu and preserves inside/outside picks', async () => {
  const source = await readFile(new URL('dsh-client-ui-input-trigger/lib/client.js', packageRoot), 'utf8');
  for (const options of [{ mobile: false }, { usage: false }, { overflowing: false }, {}]) {
    const f = triggerMenuFixture(source, options);
    f.render(); const tree = f.render();
    const surface = nodes(tree).find(node => node.type === 'surface');
    const portalled = options.mobile !== false && options.usage !== false && options.overflowing !== false;
    assert.equal(f.portalCalls.length > 0, portalled);
    assert.equal(surface.props['data-droy-trigger-portal'], portalled || undefined);
    assert.equal(surface.props.style.position === 'fixed', portalled);
    if (portalled) {
      assert.equal(f.portalCalls.at(-1).host, f.document.body);
      assert.equal(surface.props.ref.current.contains(f.menuItem), true);
      // The original composer remains an inside target after the menu's DOM parent changes.
      f.pointerDown(f.composerButton); f.pointerDown(f.menuItem);
      assert.equal(f.dismissals, 0);
      f.pointerDown(f.outside); assert.equal(f.dismissals, 1);
      const item = nodes(surface).find(node => node.props?.role === 'option');
      let prevented = false; item.props.onMouseDown({ preventDefault() { prevented = true; } });
      assert.equal(prevented, true); assert.deepEqual(f.picks, [['command', 0]]);
      f.resize(480); const resized = nodes(f.render()).find(node => node.type === 'surface');
      assert.ok(resized.props.style.maxHeight > surface.props.style.maxHeight);
      f.dismissMenu(); assert.equal(f.render(), null);
    }
    f.close(); assert.equal(f.windowListeners.size, 0);
    assert.ok(f.observers.every(observer => observer.disconnected));
  }
  assert.throws(() => patchInputTrigger(patchInputTrigger(source)), /already adapted/);
});


test('empty composer docking is limited to the hero phase with the Android keyboard visible', async () => {
  const css = await readFile(new URL('../../../app/src/main/assets/mobile.css', import.meta.url), 'utf8');
  const patched = patchConversation(conversation);
  assert.ok(patched.includes('"data-droy-composer-hero": hero || void 0'));
  const rules = [...css.matchAll(/([^{}]+)\{([^{}]*)\}/g)];
  const docks = rules.filter(([, selector]) => selector.includes('data-droy-ime-visible="true"') &&
    /data-content-phase="hero"|data-droy-composer-hero/.test(selector));
  assert.equal(docks.length, 3);
  assert.ok(docks.every(([, selector]) => selector.includes('html[data-droy-mobile][data-droy-ime-visible="true"]')));
  assert.match(docks.find(([, selector]) => selector.includes('> [data-conversation-scroll]') && !selector.includes('data-composer-seat'))[2], /justify-content: flex-start/);
  assert.match(docks.find(([, selector]) => selector.includes('data-composer-seat'))[2], /margin-top: auto/);
  assert.match(docks.find(([, selector]) => selector.includes('data-droy-composer-hero'))[2], /padding-bottom: 8px/);
  assert.ok(docks.every(([, , body]) => !/height:|bottom:.*ime/.test(body)), 'IME avoidance remains owned by the existing center inset');
});


test('hero resize reveals the focused input in an overflowing keyboard viewport without moving active chats', () => {
  const patched = patchConversation(conversation);
  const start = patched.indexOf('seatObserver.current = new ResizeObserver(() => {') + 'seatObserver.current = new ResizeObserver(() => {'.length;
  const end = patched.indexOf('\n\t\t\t\t});', start);
  const resize = new Function('document', 'seat', 'scroller', patched.slice(start, end));
  for (const mobile of [false,true]) for (const ime of [false,true]) for (const hero of [false,true]) for (const focused of [false,true]) {
    const input = {}, properties = {};
    const scroller = {clientHeight:220,scrollHeight:400,scrollTop:0,style:{setProperty:(key,value)=>properties[key]=value}};
    const seat = {offsetHeight:400,querySelector:()=>hero ? {} : null,contains:node=>node===input};
    const document = {documentElement:{hasAttribute:()=>mobile,getAttribute:()=>ime ? 'true' : 'false'},
      activeElement: focused ? {closest:()=>input} : {}};
    resize(document,seat,scroller);
    assert.equal(scroller.scrollTop, mobile&&ime&&hero&&focused ? 400 : 0);
    assert.equal(properties['--dsh-conversation-viewport-height'], '220px');
  }
});
