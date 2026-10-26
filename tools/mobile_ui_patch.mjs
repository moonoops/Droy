import { createDroyDraftRetention } from './mobile_draft_bridge.mjs';

// Only the DSH shell seams absent from its public plugin API live here.
// Every anchor is checked against the locked upstream input before packaging.
function replaceOnce(source, from, to) {
  if (source.split(from).length !== 2) throw new Error(`Mobile UI patch anchor: ${from.slice(0, 90)}`);
  return source.replace(from, to);
}

export function patchComposerStats(source) {
  source = replaceOnce(source,
    'return t("message.tokensPerSecond", { tps: formatTokensPerSecond(stats.decodeTokens / (stats.decodeMs / 1e3)) });',
    'return `${formatTokensPerSecond(stats.decodeTokens / (stats.decodeMs / 1e3))} tps`;');
  source = replaceOnce(source, 'if (stats.steps === 0) return null;', 'if (speed === null) return null;');
  source = replaceOnce(source, 'label: joined$1(counts, speed)', 'label: speed');
  const total = 'const total = billedInputTokens(usage) + usage.outputTokens;\n\t\t\tconst totalText = t("message.turnUsage.count", { count: formatTokens(total, t) });';
  source = replaceOnce(source, total, '');
  source = replaceOnce(source,
    'if (mode === "compact") return cacheHitText === null ? null : (0, react_jsx_runtime.jsx)(PlainPill, {',
    `${total}\n\t\t\tif (mode === "compact") return (0, react_jsx_runtime.jsx)(PlainPill, {`);
  source = replaceOnce(source, 'label: cacheHitText', 'label: totalText');
  return replaceOnce(source, 'label: joined$1(totalText, cacheHitText)', 'label: totalText');
}

export function patchLayout(source) {
  if (source.includes('data-droy-frame')) throw new Error('Mobile UI patch anchor: layout already adapted');
  source = replaceOnce(source,
    'const narrow = viewport < SIDEBAR_AUTO_COLLAPSE;',
    `const mobile = document.documentElement.hasAttribute("data-droy-mobile") || typeof window.DroyNative !== "undefined";
\t\t\tconst narrow = mobile || viewport < SIDEBAR_AUTO_COLLAPSE;`);
  source = replaceOnce(source,
    'const collapsedWidth = darwin || document.documentElement.hasAttribute("data-windows-titlebar") ? 0 : 56;',
    'const collapsedWidth = mobile || darwin || document.documentElement.hasAttribute("data-windows-titlebar") ? 0 : 56;');
  source = replaceOnce(source,
    'const normal = computeColumns(viewport, !layoutInfo.rightbarShown && narrow ? 0 : sidebarPreference, rightbarPreference, collapsedWidth);',
    'const normal = computeColumns(viewport, mobile || !layoutInfo.rightbarShown && narrow ? 0 : sidebarPreference, rightbarPreference, collapsedWidth);');
  source = replaceOnce(source,
    'const productTitle = "DeepSeek Harness";',
    `const drawerRef = (0, react.useRef)(null);
\t\t\t(0, react.useEffect)(() => {
\t\t\t\tif (!mobile || sidebarCollapsed) return;
\t\t\t\tconst previous = document.activeElement;
\t\t\t\tconst drawer = drawerRef.current;
\t\t\t\tdrawer?.querySelector("button, input, [tabindex='0']")?.focus();
\t\t\t\treturn () => {
					const fallback = document.querySelector('[data-droy-menu]');
					if (window.DroyTouch) window.DroyTouch.restoreFocus(previous, fallback);
					else if (previous?.isConnected && !previous.matches?.('input, textarea, [contenteditable="true"]')) previous.focus({preventScroll:true});
				};
\t\t\t}, [mobile, sidebarCollapsed]);
\t\t\tconst drawerKeyDown = (event) => {
\t\t\t\tif (!mobile || sidebarCollapsed || document.querySelector('[data-shortcut-modal]')) return;
\t\t\t\tif (event.key === "Escape") { event.preventDefault(); event.stopPropagation(); actions.toggleSidebar(); return; }
\t\t\t\tif (event.key !== "Tab") return;
\t\t\t\tconst controls = [...drawerRef.current.querySelectorAll('button:not([disabled]), input:not([disabled]), [tabindex="0"]')].filter(node => node.getClientRects().length);
\t\t\t\tconst first = controls[0], last = controls[controls.length - 1];
\t\t\t\tif (!first) { event.preventDefault(); drawerRef.current.focus(); }
\t\t\t\telse if (event.shiftKey && document.activeElement === first) { event.preventDefault(); last.focus(); }
\t\t\t\telse if (!event.shiftKey && document.activeElement === last) { event.preventDefault(); first.focus(); }
\t\t\t};
\t\t\tconst [productTitle, setDroyProductTitle] = (0, react.useState)(window.DroyBranding?.getSnapshot()?.name || "Droy");
\t\t\t(0, react.useEffect)(() => {
\t\t\t\tconst update = () => setDroyProductTitle(window.DroyBranding?.getSnapshot()?.name || "Droy");
\t\t\t\twindow.addEventListener?.("droy-branding-change", update);
\t\t\t\tupdate();
\t\t\t\treturn () => window.removeEventListener?.("droy-branding-change", update);
\t\t\t}, []);`);
  // A mobile drawer keeps the wide sidebar mounted while hidden. Toggling the
  // desktop collapsed prop also swaps its rail/tree and starts settling timers.
  source = replaceOnce(source,
    `const sidebar = (0, react.useMemo)(() => renderSlot("sidebar", {
\t\t\t\tcollapsed: sidebarCollapsed,
\t\t\t\twidth: cols.sidebar
\t\t\t}), [
\t\t\t\trenderSlot,
\t\t\t\tsidebarCollapsed,
\t\t\t\tcols.sidebar
\t\t\t]);`,
    `const renderedSidebarCollapsed = mobile ? false : sidebarCollapsed;
\t\t\tconst sidebarWidth = mobile ? Math.min(320, Math.max(0, viewport - 48)) : cols.sidebar;
\t\t\tconst sidebar = (0, react.useMemo)(() => renderSlot("sidebar", {
\t\t\t\tcollapsed: renderedSidebarCollapsed,
\t\t\t\twidth: sidebarWidth
\t\t\t}), [renderSlot, renderedSidebarCollapsed, sidebarWidth]);`);
  source = replaceOnce(source,
    'const leadingMounted = darwin && sidebarCollapsed;',
    'const leadingMounted = (mobile || darwin) && sidebarCollapsed;');
  source = replaceOnce(source,
    'if (d.layoutInfo.viewportWidth < 1024) d.layoutInfo.narrowExpanded = !d.layoutInfo.narrowExpanded;',
    'if (document.documentElement.hasAttribute("data-droy-mobile") || typeof window.DroyNative !== "undefined" || d.layoutInfo.viewportWidth < 1024) d.layoutInfo.narrowExpanded = !d.layoutInfo.narrowExpanded;');
  source = replaceOnce(source,
    'gridTemplateColumns: `${cols.sidebar}px minmax(${cols.rightbar === 0 ? 0 : 400}px, 1fr) minmax(0px, ${rightbarMax}px)`',
    'gridTemplateColumns: `${mobile ? 0 : cols.sidebar}px minmax(${cols.rightbar === 0 ? 0 : 400}px, 1fr) minmax(0px, ${rightbarMax}px)`,\n\t\t\t\t\t...(mobile ? {"--dsh-frame-leading-clearance":"56px", "--droy-drawer-width":`${Math.min(320, Math.max(0, viewport - 48))}px`} : {})');
  source = replaceOnce(source,
    '"data-sidebar-collapsed": sidebarCollapsed || void 0,',
    '"data-droy-frame": mobile || void 0,\n\t\t\t\t"data-droy-drawer-open": mobile && !sidebarCollapsed || void 0,\n\t\t\t\t"data-sidebar-collapsed": sidebarCollapsed || void 0,');
  source = replaceOnce(source,
    'className: AppFrame_module_css_default.sidebarCol,\n\t\t\t\t\t\tchildren: sidebar',
    `className: AppFrame_module_css_default.sidebarCol,
\t\t\t\t\t\t"data-droy-sidebar": mobile || void 0,
\t\t\t\t\t\tref: drawerRef,
\t\t\t\t\t\trole: mobile && !sidebarCollapsed ? "dialog" : void 0,
\t\t\t\t\t\t"aria-modal": mobile && !sidebarCollapsed ? "true" : void 0,
\t\t\t\t\t\t"aria-label": mobile ? ((document.documentElement.lang || "zh").startsWith("zh") ? "会话菜单" : "Conversation menu") : void 0,
\t\t\t\t\t\tinert: mobile && sidebarCollapsed ? "" : void 0,
\t\t\t\t\t\t"aria-hidden": mobile && sidebarCollapsed || void 0,
\t\t\t\t\t\ttabIndex: mobile ? -1 : void 0,
\t\t\t\t\t\tonKeyDown: drawerKeyDown,
\t\t\t\t\t\tchildren: sidebar`);
  source = replaceOnce(source,
    'className: AppFrame_module_css_default.centerCol,',
    'className: AppFrame_module_css_default.centerCol,\n\t\t\t\t"data-droy-center": true,\n\t\t\t\tinert: props.inert ? "" : void 0,\n\t\t\t\t"aria-hidden": props.inert || void 0,');
  source = replaceOnce(source,
    '(CenterColumn, { children: main })',
    '(CenterColumn, { children: main, inert: mobile && !sidebarCollapsed })');
  source = replaceOnce(source,
    'children: leading\n',
    `children: mobile ? (0, react_jsx_runtime.jsx)("button", {
\t\t\t\t\t\t\ttype: "button", className: "droy-menu-button", "data-droy-menu": true,
\t\t\t\t\t\t\t"aria-label": (document.documentElement.lang || "zh").startsWith("zh") ? "打开菜单" : "Open menu", "aria-expanded": false, onClick: () => actions.toggleSidebar(),
\t\t\t\t\t\t\tchildren: (0, react_jsx_runtime.jsx)("svg", {width:24,height:24,viewBox:"0 0 24 24",fill:"none","aria-hidden":true,
\t\t\t\t\t\t\t\tchildren:(0,react_jsx_runtime.jsx)("path",{d:"M4 8h16M4 16h11",stroke:"currentColor",strokeWidth:1.8,strokeLinecap:"round"})})
\t\t\t\t\t\t}) : leading
`);
  source = replaceOnce(source,
    '!sidebarCollapsed && (0, react_jsx_runtime.jsx)(DragHandle, {',
    `mobile && (0, react_jsx_runtime.jsx)("button", {
\t\t\t\t\t\t"data-droy-backdrop": true, className:"droy-drawer-backdrop", tabIndex:-1, "aria-hidden":true,
\t\t\t\t\t\tonClick:() => actions.toggleSidebar()
\t\t\t\t\t}),
\t\t\t\t\t!mobile && !sidebarCollapsed && (0, react_jsx_runtime.jsx)(DragHandle, {`);
  return source;
}

export function patchSettings(source) {
  if (source.includes('openDroySettings')) throw new Error('Mobile UI patch anchor: settings already adapted');
  // The upstream action opens the Host YAML in a desktop text editor. Android
  // has no native opener for that private file, even when its Host is loopback.
  source = replaceOnce(source,
    'const documentController = ctx.remote.$host.isLoopback ? new SettingsDocumentStore(ctx, ctx.configForms.describe()) : void 0;',
    'const documentController = ctx.remote.$host.isLoopback && !document.documentElement.hasAttribute("data-droy-mobile") && typeof window.DroyNative === "undefined" ? new SettingsDocumentStore(ctx, ctx.configForms.describe()) : void 0;');
  source = replaceFunction(source, 'SettingsRoot', text => {
    text = replaceOnce(text, 'const onboardingStep = requestedOnboarding !== void 0 ?',
      'const onboardingStep = (document.documentElement.hasAttribute("data-droy-mobile") || typeof window.DroyNative !== "undefined") && requestedOnboarding === void 0 ? void 0 : requestedOnboarding !== void 0 ?');
    text = replaceOnce(text, 'const { close, openSection } = actions;',
      `const { close, openSection } = actions;
\t\t\t// A notification destination takes precedence over delayed automatic onboarding.
\t\t\tconst explicitMobileSection = open && activeId !== void 0 && (document.documentElement.hasAttribute("data-droy-mobile") || typeof window.DroyNative !== "undefined");`);
    text = replaceOnce(text, 'if (appeared && open) close();', 'if (appeared && open && !explicitMobileSection) close();');
    text = replaceOnce(text, 'onboardingStep,\n\t\t\t\topen,\n\t\t\t\tclose',
      'onboardingStep,\n\t\t\t\topen,\n\t\t\t\texplicitMobileSection,\n\t\t\t\tclose');
    return replaceOnce(text, 'onboardingStep !== void 0 && renderSlot("settings.onboarding", {',
      '!explicitMobileSection && onboardingStep !== void 0 && renderSlot("settings.onboarding", {');
  });
  // Keep DSH's portal, modal focus stack and settings slots. Only the phone shell
  // changes from a floating dialog to a full-window page.
  source = replaceOnce(source,
    'function SettingsPanel({ rows, renderSlot, activeId, onSelect, onClose }) {',
    `function SettingsPanel({ rows, renderSlot, activeId, onSelect, onClose }) {
\t\t\tconst mobile = document.documentElement.hasAttribute("data-droy-mobile") || typeof window.DroyNative !== "undefined";
\t\t\t(0, react.useLayoutEffect)(() => {
\t\t\t\tif (!mobile) return;
\t\t\t\twindow.dispatchEvent(new window.CustomEvent("droy-mobile-settings-open"));
\t\t\t\treturn () => {
\t\t\t\t\t// DSH first restores its launcher; the phone launcher is now in an inert drawer.
\t\t\t\t\twindow.requestAnimationFrame(() => {
\t\t\t\t\t\tconst active = document.activeElement;
\t\t\t\t\t\tconst drawer = document.querySelector('[data-droy-sidebar][inert]');
\t\t\t\t\t\tconst menu = document.querySelector('[data-droy-menu]');
\t\t\t\t\t\tif (active?.matches?.('input, textarea, [contenteditable="true"]') && window.DroyTouch?.isTouchInteraction()) {
\t\t\t\t\t\t\twindow.DroyTouch.restoreFocus(active, menu);
\t\t\t\t\t\t\treturn;
\t\t\t\t\t\t}
\t\t\t\t\t\tif (active !== document.body && !drawer?.contains(active)) return;
\t\t\t\t\t\tif (menu?.getClientRects().length && !menu.closest('[inert]')) menu.focus({preventScroll:true});
\t\t\t\t\t});
\t\t\t\t};
\t\t\t}, [mobile]);`);
  for (const [name, marker] of [
    ['overlay', 'settings'], ['mask', 'settings-mask'], ['panel', 'settings-panel'],
    ['nav', 'settings-nav'], ['navList', 'settings-tabs'], ['content', 'settings-content'],
    ['header', 'settings-actions'], ['options', 'settings-options'], ['close', 'settings-close']
  ]) {
    source = replaceOnce(source,
      `className: SettingsRoot_module_css_default.${name},`,
      `className: SettingsRoot_module_css_default.${name},\n\t\t\t\t\t"data-droy-${marker}": mobile || void 0,`);
  }
  source = replaceOnce(source,
    `className: SettingsRoot_module_css_default.navTitle,
\t\t\t\t\t\t\tid: titleId,
\t\t\t\t\t\t\ttabIndex: -1,
\t\t\t\t\t\t\t"data-modal-autofocus": active === void 0 ? "" : void 0,
\t\t\t\t\t\t\tchildren: renderSlot("settings.header", {})`,
    `className: SettingsRoot_module_css_default.navTitle,
\t\t\t\t\t\t\t"data-droy-settings-header": mobile || void 0,
\t\t\t\t\t\t\tid: mobile ? void 0 : titleId,
\t\t\t\t\t\t\ttabIndex: -1,
\t\t\t\t\t\t\t"data-modal-autofocus": active === void 0 ? "" : void 0,
\t\t\t\t\t\t\tchildren: mobile ? [(0, react_jsx_runtime.jsx)("button", {
\t\t\t\t\t\t\t\ttype: "button", "data-droy-settings-back": true,
\t\t\t\t\t\t\t\t"aria-label": (document.documentElement.lang || "zh").startsWith("zh") ? "返回对话" : "Back to conversation", onClick: onClose,
\t\t\t\t\t\t\t\tchildren: (0, react_jsx_runtime.jsx)(_deepseek_ai_dsh_client_ui_primitives.IconChevronLeftOutlineRegular, {size:24,"aria-hidden":true})
\t\t\t\t\t\t\t}), (0, react_jsx_runtime.jsx)("span", {id:titleId,children:renderSlot("settings.header", {})})] : renderSlot("settings.header", {})`);
  source = replaceOnce(source,
    '"aria-current": row.id === active ? "true" : void 0,',
    '"data-droy-settings-section": row.id,\n\t\t\t\t\t\t\t\t"aria-current": row.id === active ? "true" : void 0,');
  source = replaceFunction(source, 'SettingsPanel', text => replaceOnce(text,
    'const panel = (0, react.useRef)(null);',
    `const panel = (0, react.useRef)(null);
\t\t\t(0, react.useLayoutEffect)(() => {
\t\t\t\tif (!mobile) return;
\t\t\t\tconst tabs = panel.current?.querySelector('[data-droy-settings-tabs]');
\t\t\t\tconst selected = tabs?.querySelector('[aria-current="true"]');
\t\t\t\tif (!selected) return;
\t\t\t\tconst outer = tabs.getBoundingClientRect(), item = selected.getBoundingClientRect();
\t\t\t\t// Scroll only the tab strip; scrollIntoView also shifts the modal body.
\t\t\t\tif (item.left < outer.left) tabs.scrollLeft += item.left - outer.left;
\t\t\t\telse if (item.right > outer.right) tabs.scrollLeft += item.right - outer.right;
\t\t\t}, [mobile, active, rows]);`));
  source = replaceOnce(source,
    'const shellInstance = shellHandle.create();',
    `const shellInstance = shellHandle.create();
\t\t\t\tconst openDroySettings = (event) => {
\t\t\t\t\tconst section = event.detail?.section;
\t\t\t\t\tif (typeof section !== "string" || !ctx.slots.entries("settings.section").some(entry => entry.options.id === section)) return;
\t\t\t\t\tshellInstance.actions.openSection(section);
\t\t\t\t};
\t\t\t\twindow.addEventListener("droy-mobile-open-settings", openDroySettings);`);
  source = replaceOnce(source,
    '\t\t\t\t\tdisposeCommand();\n\t\t\t\t\tdisposeSlot();',
    '\t\t\t\t\twindow.removeEventListener("droy-mobile-open-settings", openDroySettings);\n\t\t\t\t\tdisposeCommand();\n\t\t\t\t\tdisposeSlot();');
  return source;
}

// These bridges keep DSH actions, menu contributions and draft ownership intact.
// The gesture recognizer itself is a separately maintained mobile source module.
const touchPredicate = `function isDroyTouchInteraction() {
			const mobile = document.documentElement.hasAttribute("data-droy-mobile") || typeof window.DroyNative !== "undefined";
			if (!mobile) return false;
			if (window.DroyTouch) return window.DroyTouch.isTouchInteraction();
			return (window.navigator?.maxTouchPoints ?? 0) > 0 || window.matchMedia?.("(pointer: coarse)").matches === true;
		}`;

function replaceFunction(source, name, update) {
  const start = source.indexOf(`function ${name}(`);
  const boundary = source.indexOf('\n\t\t//#endregion', start);
  const next = source.indexOf('\n\t\tfunction ', start + 1);
  const end = next >= 0 && next < boundary ? next : boundary;
  if (start < 0 || end < 0) throw new Error(`Mobile UI patch anchor: function ${name}`);
  const original = source.slice(start, end);
  const updated = update(original);
  if (original === updated) throw new Error(`Mobile UI patch anchor: function ${name} unchanged`);
  return source.slice(0, start) + updated + source.slice(end);
}

export function patchWorkspace(source) {
  if (source.includes('data-droy-session-row')) throw new Error('Mobile UI patch anchor: workspace already adapted');
  source = replaceOnce(source, 'let react = require("react");', `let react = require("react");
		${touchPredicate}
		function useDroyDraftList(list) {
			const [revision, setRevision] = (0, react.useState)(0);
			(0, react.useEffect)(() => {
				const update = () => setRevision(value => value + 1);
				window.addEventListener("droy-mobile-drafts-change", update);
				return () => window.removeEventListener("droy-mobile-drafts-change", update);
			}, []);
			return (0, react.useMemo)(() => {
				if (!document.documentElement.hasAttribute("data-droy-mobile") && typeof window.DroyNative === "undefined") return list;
				const byId = Object.fromEntries(Object.entries(list.byId).map(([id, summary]) => [id,
					summary?.blank && window.DroyDrafts?.has(id) ? {...summary, droyDraft:true} : summary]));
				return {...list, byId};
			}, [list, revision]);
		}
		const droySessionOperations = new Map();
		function droySessionOperation(sessionId, action, operation) {
			const key = action + ":" + sessionId;
			if (droySessionOperations.has(key)) return droySessionOperations.get(key);
			const pending = Promise.resolve().then(operation).finally(() => droySessionOperations.delete(key));
			droySessionOperations.set(key, pending);
			return pending;
		}
		function droySessionAction(sessionId, action, phase) {
			if (!document.documentElement.hasAttribute("data-droy-mobile") && typeof window.DroyNative === "undefined") return;
			const message = phase === "failed" ? ((document.documentElement.lang || "").startsWith("zh") ? "操作失败，请重试" : "Could not complete this action. Try again.") : "";
			window.dispatchEvent(new window.CustomEvent("droy-mobile-session-action", {detail:{sessionId,action,phase,message}}));
		}`);
  source = replaceOnce(source, 'await this.workspaces.archiveSession(sessionId, options);', 'await droySessionOperation(sessionId, "archive", () => this.workspaces.archiveSession(sessionId, options));');
  source = replaceOnce(source, 'await this.workspaces.unarchiveSession(sessionId);', 'await droySessionOperation(sessionId, "unarchive", () => this.workspaces.unarchiveSession(sessionId));');
  const closeDrawer = `if (document.querySelector('[data-droy-drawer-open="true"]')) this.ctx.layout.toggleSidebar();`;
  source = replaceOnce(source,
    'clearMain() {\n\t\t\t\tconst previous = this.mainReference;',
    `clearMain(closeDrawer = true) {
				if (closeDrawer) { ${closeDrawer} }
				const previous = this.mainReference;`);
  source = replaceOnce(source,
    'if (this.mainReference?.sessionId === sessionId) this.clearMain();',
    'if (this.mainReference?.sessionId === sessionId) this.clearMain(false);');
  source = replaceOnce(source,
    'this.clearMain();\n\t\t\t\treturn true;',
    'this.clearMain(false);\n\t\t\t\treturn true;');
  source = replaceOnce(source,
    'if (panel === "reveal") this.ctx.layout.selectPanel(null);',
    `if (panel === "reveal") { this.ctx.layout.selectPanel(null); ${closeDrawer} }`);

  source = replaceOnce(source, 'if (session.blank && session.id !== current) return false;', 'if (session.blank && session.id !== current && !session.droyDraft) return false;');
  source = replaceOnce(source, 'blank: s.blank,', 'blank: s.blank,\n\t\t\t\tdroyDraft: s.droyDraft === true,');
  source = replaceOnce(source, 'return node.blank ? t("session.new") : node.title || t("session.untitled");', 'return node.droyDraft ? t("session.draft") : node.blank ? t("session.new") : node.title || t("session.untitled");');
  source = replaceOnce(source, '"session.new": "新会话",', '"session.new": "新会话",\n\t\t\t"session.draft": "未发送草稿",');
  source = replaceOnce(source, '"session.new": "New Session",', '"session.new": "New Session",\n\t\t\t"session.draft": "Unsent draft",');
  source = replaceFunction(source, 'WorkspaceBrowser', text => replaceOnce(text, 'const list = useSessions((state) => state);', 'const list = useDroyDraftList(useSessions((state) => state));'));
  source = replaceFunction(source, 'SessionNodeItem', text => {
    // A keyboard/back event changes the last input, not the mounted phone layout.
    text = replaceOnce(text, 'const row = node;', 'const row = node;\n\t\t\tconst touch = document.documentElement.hasAttribute("data-droy-mobile") || typeof window.DroyNative !== "undefined";');
    text = replaceOnce(text, 'const [menuOpen, setMenuOpen] = (0, react.useState)(false);', `const [menuOpen, rawSetMenuOpen] = (0, react.useState)(false);
			const menuListId = "droy-session-menu-" + (0, react.useId)().replace(/[^a-zA-Z0-9_-]/g, "_");
			const menuElement = () => document.querySelector("." + menuListId);
			const menuRequest = (0, react.useRef)(0);
			const setMenuOpen = value => {
				const open = typeof value === "function" ? value(menuOpenRef.current) : value;
				const request = ++menuRequest.current;
				const commit = () => { if (menuRequest.current === request) rawSetMenuOpen(open); };
				if (!open && touch && isDroyTouchInteraction() && window.DroyTouch?.closeSessionMenuAnimated) window.DroyTouch.closeSessionMenuAnimated(menuElement(), commit);
				else { if (open && menuOpenRef.current) window.DroyTouch?.animateSessionMenu?.(menuElement(), true); commit(); }
			};
			(0, react.useEffect)(() => () => { menuRequest.current++; }, []);`);
    text = replaceOnce(text, 'const draggable = drag !== void 0 && !row.blank && !row.archived;', 'const draggable = !touch && drag !== void 0 && !row.blank && !row.archived;');
    text = replaceOnce(text, 'const titleRef = (0, react.useRef)(null);', `const titleRef = (0, react.useRef)(null);
			const menuOpenRef = (0, react.useRef)(menuOpen);
			menuOpenRef.current = menuOpen;
			(0, react.useEffect)(() => {
				if (row.blank) return;
				let dispose;
				const bind = () => {
					dispose?.();
					dispose = window.DroyTouch?.bindSessionRow(rowRef.current, {
						sessionId: node.id, archived: row.archived, blank: row.blank,
						openMenu: () => setMenuOpen(true), closeMenu: () => setMenuOpen(false),
						isMenuOpen: () => menuOpenRef.current, menuElement
					});
				};
				bind();
				window.addEventListener("droy-touch-ready", bind);
				return () => { dispose?.(); window.removeEventListener("droy-touch-ready", bind); };
			}, [node.id, row.archived, row.blank]);
			(0, react.useEffect)(() => {
				if (menuOpen) {
					window.DroyTouch?.resetSessionRow?.(rowRef.current);
					window.DroyTouch?.menuOpened?.("session:" + node.id);
					window.DroyTouch?.animateSessionMenu?.(menuElement(), true);
				}
			}, [menuOpen]);`);
    text = replaceOnce(text, '"data-row-key": `session:${node.id}`,', `"data-row-key": \`session:\${node.id}\`,
					"data-droy-session-row": node.id,
					"data-droy-session-blank": row.blank || void 0,
					"data-droy-session-draft": row.droyDraft || void 0,
					"data-droy-session-menu-open": menuOpen || void 0,`);
    text = replaceOnce(text, 'onPointerEnter: marquee.enter,', 'onPointerEnter: touch ? void 0 : marquee.enter,');
    text = replaceOnce(text, 'onPointerLeave: marquee.leave,', 'onPointerLeave: touch ? void 0 : marquee.leave,');
    text = replaceOnce(text, 'onDragOver: drag === void 0 ? void 0 : (e) => {', 'onDragOver: touch || drag === void 0 ? void 0 : (e) => {');
    text = replaceOnce(text, 'onDrop: drag === void 0 ? void 0 : (e) => {', 'onDrop: touch || drag === void 0 ? void 0 : (e) => {');
    text = replaceOnce(text, 'onDoubleClick: row.blank ? void 0 : (e) => {', 'onDoubleClick: touch || row.blank ? void 0 : (e) => {');
    text = replaceOnce(text, 'closeOnPointerLeave: true,', 'closeOnPointerLeave: !touch,\n\t\t\t\t\t\t\t\tlistClassName: "droy-session-menu " + menuListId,');
    text = replaceOnce(text, '"aria-label": t("actions.session.aria", { name: title }),', '"data-droy-session-menu": true,\n\t\t\t\t\t\t\t\t\t"aria-label": t("actions.session.aria", { name: title }),');
    text = replaceOnce(text, 'className: Rows_module_css_default.rowActions,', 'className: Rows_module_css_default.rowActions,\n\t\t\t\t\t\t\t"data-droy-row-actions": true,');
    text = replaceOnce(text, 'children: [\n\t\t\t\t\t\t(0, react_jsx_runtime.jsx)("span", {', `children: [(0, react_jsx_runtime.jsxs)("div", {
						"data-droy-session-surface": true,
						style: touch ? void 0 : {display:"contents"},
						children: [
						(0, react_jsx_runtime.jsx)("span", {`);
    text = replaceOnce(text, `					]
				}),
				content:`, `					]}), touch && !row.blank && (0, react_jsx_runtime.jsx)("div", {
						"data-droy-swipe-action": true, inert: "", "aria-hidden": true,
						onClick: (event) => event.stopPropagation(),
						children: renderSlot("sidebar.workspaces.session.row.action", {
							sessionId: node.id, displayTitle: row.title
						}, {only:"archive"})
					})]
				}),
				content:`);
    text = replaceOnce(text, 'disabled: menuOpen || drag?.active === true,', 'disabled: touch || menuOpen || drag?.active === true,');
    return text;
  });
  source = replaceFunction(source, 'ProjectRowItem', text => {
    text = replaceOnce(text, 'const row = group;', 'const row = group;\n\t\t\tconst touch = document.documentElement.hasAttribute("data-droy-mobile") || typeof window.DroyNative !== "undefined";');
    text = replaceOnce(text, 'draggable: drag !== void 0,', 'draggable: !touch && drag !== void 0,');
    text = replaceOnce(text, 'onDragStart: drag === void 0 ? void 0 : (e) => {', 'onDragStart: touch || drag === void 0 ? void 0 : (e) => {');
    text = replaceOnce(text, 'onDragEnd: drag?.end,', 'onDragEnd: touch ? void 0 : drag?.end,');
    text = replaceOnce(text, 'closeOnPointerLeave: true,', 'closeOnPointerLeave: !touch,');
    text = replaceOnce(text, 'disabled: menuOpen,', 'disabled: touch || menuOpen,');
    return text;
  });
  source = replaceFunction(source, 'WorkspacePickFlow', text => replaceOnce(text, 'anchor: null,', 'anchor: null,\n\t\t\t\t\t\tlistClassName: "droy-workspace-picker",'));
  source = replaceOnce(source, 'openWorkspace(workspaceId, beforeOpen, options = {}) {', `openWorkspace(workspaceId, beforeOpen, options = {}) {
				if (document.documentElement.hasAttribute("data-droy-mobile") || typeof window.DroyNative !== "undefined") window.dispatchEvent(new window.CustomEvent("droy-mobile-workspace-change"));`);
  source = replaceFunction(source, 'SearchResultItem', text => {
    text = replaceOnce(text, 'className: Rows_module_css_default.rowActions,', 'className: Rows_module_css_default.rowActions,\n\t\t\t\t\t\t\t"data-droy-search-actions": true,');
    return replaceOnce(text, '"aria-label": t("menu.unarchiveSession"),', '"data-droy-session-restore": result.id,\n\t\t\t\t\t\t\t\t\t"aria-label": t("menu.unarchiveSession"),');
  });
  source = replaceOnce(source, '"aria-label": t("viewOptions.label"),', '"data-droy-view-options": true,\n\t\t\t\t\t"aria-label": t("viewOptions.label"),');
  for (const indentation of ["\t\t\t\t\t\t\t\t\t\t\t\t", "\t\t\t\t\t\t\t\t"]) {
    source = replaceOnce(source, `\n${indentation}"aria-label": t("search.sessions.aria"),`, `\n${indentation}"data-droy-session-search": true,\n${indentation}"aria-label": t("search.sessions.aria"),`);
  }
  source = replaceOnce(source, 'className: WorkspaceBrowser_module_css_default.sectionHeader,', 'className: WorkspaceBrowser_module_css_default.sectionHeader,\n\t\t\t\t\t\t"data-droy-drawer-header": true,');
  source = replaceOnce(source, '"aria-label": t(archived ? "menu.unarchiveSession" : "menu.archiveSession"),', '"data-droy-session-archive": sessionId,\n\t\t\t\t\t"data-droy-session-action": archived ? "unarchive" : "archive",\n\t\t\t\t\t"aria-label": t(archived ? "menu.unarchiveSession" : "menu.archiveSession"),');
  source = replaceOnce(source, '"aria-label": t(pinned ? "menu.unpinSession" : "menu.pinSession"),', '"data-droy-session-pin": sessionId,\n\t\t\t\t\t"aria-label": t(pinned ? "menu.unpinSession" : "menu.pinSession"),');

  // Expose only action status. DSH still performs the operation and owns its toast,
  // confirmation, stop-and-archive contract and archived-set projection.
  source = replaceOnce(source, `const unarchiveSession = (sessionId) => {
				uiWorkspace.unarchiveSession(sessionId).catch((reason) => {
					console.warn("session unarchive rejected:", reason);
				});
			};`, `const unarchiveSession = (sessionId) => {
				droySessionAction(sessionId, "unarchive", "pending");
				uiWorkspace.unarchiveSession(sessionId).then(() => {
					droySessionAction(sessionId, "unarchive", "succeeded");
				}).catch((reason) => {
					droySessionAction(sessionId, "unarchive", "failed");
					console.warn("session unarchive rejected:", reason);
				});
			};`);
  source = replaceOnce(source, `archiveSession: (sessionId) => {
					uiWorkspace.archiveSession(sessionId).then(() => {`, `archiveSession: (sessionId) => {
					droySessionAction(sessionId, "archive", "pending");
					uiWorkspace.archiveSession(sessionId).then(() => {
						droySessionAction(sessionId, "archive", "succeeded");`);
  source = replaceOnce(source, 'console.warn("session archive rejected:", reason);', 'droySessionAction(sessionId, "archive", "failed");\n\t\t\t\t\t\t\tconsole.warn("session archive rejected:", reason);');
  source = replaceOnce(source, 'const displayTitle = sessions.list.getSnapshot().byId[sessionId]?.displayTitle ?? sessionId;', 'droySessionAction(sessionId, "archive", "needsConfirmation");\n\t\t\t\t\t\tconst displayTitle = sessions.list.getSnapshot().byId[sessionId]?.displayTitle ?? sessionId;');
  source = replaceFunction(source, 'ArchiveConfirmForm', text => {
    text = replaceOnce(text, 'if (archiving) return;\n\t\t\t\tonSettle();', 'if (archiving) return;\n\t\t\t\tonSettle("cancelled");');
    return replaceOnce(text, 'setArchiving(false);\n\t\t\t\t\tonSettle();', 'setArchiving(false);\n\t\t\t\t\tonSettle("success");');
  });
  source = replaceOnce(source, `settleSessionArchive: () => {
					archiveRequest.set(null);
				},`, `settleSessionArchive: (phase) => {
					const pending = archiveRequest.getSnapshot();
					if (phase === "cancelled" && pending !== null) droySessionAction(pending.sessionId, "archive", "cancelled");
					archiveRequest.set(null);
				},`);
  source = replaceOnce(source, `await uiWorkspace.archiveSession(sessionId, { stopActivity: true });`, `droySessionAction(sessionId, "archive", "pending");
					try {
						await uiWorkspace.archiveSession(sessionId, { stopActivity: true });
					} catch (error) {
						droySessionAction(sessionId, "archive", "failed");
						throw error;
					}
					droySessionAction(sessionId, "archive", "succeeded");`);
  source = replaceOnce(source, `unarchiveSession: async (sessionId) => {
					await uiWorkspace.unarchiveSession(sessionId);
				},`, `unarchiveSession: async (sessionId) => {
					droySessionAction(sessionId, "unarchive", "pending");
					try {
						await uiWorkspace.unarchiveSession(sessionId);
					} catch (error) {
						droySessionAction(sessionId, "unarchive", "failed");
						throw error;
					}
					droySessionAction(sessionId, "unarchive", "succeeded");
				},`);
  return source;
}

export function patchConversation(source) {
  if (source.includes('data-droy-composer-add')) throw new Error('Mobile UI patch anchor: conversation already adapted');
  source = replaceOnce(source, 'className: ContextMeter_module_css_default.root,',
    'className: ContextMeter_module_css_default.root,\n\t\t\t\t"data-droy-context-meter": true,');
  source = replaceOnce(source, 'className: clsx(ConversationRoot_module_css_default.composerStack, hero && ConversationRoot_module_css_default.composerHero),',
    'className: clsx(ConversationRoot_module_css_default.composerStack, hero && ConversationRoot_module_css_default.composerHero),\n\t\t\t\t"data-droy-composer-hero": hero || void 0,');
  // Large fonts and landscape can make the whole hero taller than the IME's
  // remaining viewport. Reuse its resize observer to reveal the focused input.
  source = replaceOnce(source, 'scroller.style.setProperty("--dsh-conversation-viewport-height", `${scroller.clientHeight}px`);',
    `scroller.style.setProperty("--dsh-conversation-viewport-height", \`\${scroller.clientHeight}px\`);
\t\t\t\t\tif (document.documentElement.hasAttribute("data-droy-mobile")
\t\t\t\t\t\t&& document.documentElement.getAttribute("data-droy-ime-visible") === "true"
\t\t\t\t\t\t&& seat.querySelector("[data-droy-composer-hero]")
\t\t\t\t\t\t&& seat.contains(document.activeElement?.closest?.("[data-composer-input]")))
\t\t\t\t\t\tscroller.scrollTop = scroller.scrollHeight;`);
  // Preserve Session-scoped input contracts; the empty home gets the same seats.
  source = replaceOnce(source, 'zone !== void 0 && renderSlot("conversation.input.dock", zone),\n\t\t\t\t\tinputBar',
    'zone !== void 0 ? renderSlot("conversation.input.dock", zone) : renderSlot("droy.voice.hero", {}),\n\t\t\t\t\tinputBar');
  source = replaceOnce(source, '"conversation.input.dock": {\n\t\t\t\t\t\tkind: "list",\n\t\t\t\t\t\tscope: "session"\n\t\t\t\t\t},',
    '"droy.voice.hero": {kind:"list",scope:"root"},\n\t\t\t\t\t"conversation.input.dock": {\n\t\t\t\t\t\tkind: "list",\n\t\t\t\t\t\tscope: "session"\n\t\t\t\t\t},');
  source = replaceOnce(source, 'input === void 0 || sessionId === void 0 ? null : (0, react_jsx_runtime.jsx)("div", {\n\t\t\t\t\t\t\t\t\t\t\tclassName: activity ? InputBar_module_css_default.activityExpanded : InputBar_module_css_default.activity,',
    '(0, react_jsx_runtime.jsx)("div", {\n\t\t\t\t\t\t\t\t\t\t\tclassName: activity ? InputBar_module_css_default.activityExpanded : InputBar_module_css_default.activity,');
  source = replaceOnce(source, '"conversation.input.activity": {\n\t\t\t\t\t\tkind: "single",',
    '"droy.voice.activity": {kind:"single",scope:"root"},\n\t\t\t\t\t"conversation.input.activity": {\n\t\t\t\t\t\tkind: "single",');
  source = replaceOnce(source, 'children: renderSlot("conversation.input.activity", {',
    'children: input === void 0 || sessionId === void 0 ? renderSlot("droy.voice.activity", {}) : renderSlot("conversation.input.activity", {');
  source = replaceOnce(source, 'let react = require("react");', `let react = require("react");\n\t\t${touchPredicate}`);
  source = replaceOnce(source, 'let react = require("react");', `let react = require("react");\n\t\t${createDroyDraftRetention.toString()}`);
  source = replaceOnce(source, 'const inputHub = new InputHub(ctx, t);', `const inputHub = new InputHub(ctx, t);
			const droyDrafts = createDroyDraftRetention(window, sessions, readConversationDraft);
			inputHub.droyDrafts = droyDrafts;
			ctx.effect(() => {
				window.DroyDrafts = droyDrafts;
				window.dispatchEvent(new window.CustomEvent("droy-mobile-drafts-change"));
				return () => { droyDrafts.dispose(); if (window.DroyDrafts === droyDrafts) delete window.DroyDrafts; };
			}, "droy: unsent draft lifetime");
			ctx.inject(["workspaces"], scope => scope.effect(() => {
				const update = () => droyDrafts.setArchived(scope.workspaces.list.getSnapshot().archivedSessionIds);
				update(); return scope.workspaces.list.subscribe(update);
			}, "droy: archived draft visibility"));`);
  source = replaceOnce(source, 'shell.setDraft(readConversationDraft(binding.sessionId));', `shell.setDraft(readConversationDraft(binding.sessionId));
				actx.effect(() => this.droyDrafts?.track(binding, shell), "droy: retained unsent input");`);
  source = replaceOnce(source, 'retryFileUpload, toggleCommandMenu, stop, t, renderSlot', 'retryFileUpload, toggleCommandMenu, dismissCommandMenu, stop, t, renderSlot');
  source = replaceOnce(source, 'toggleCommandMenu: inputTriggers === void 0 ? void 0 : (selection) => {', 'dismissCommandMenu: inputTriggers === void 0 ? void 0 : () => inputTriggers.dismiss(),\n\t\t\t\t\t\ttoggleCommandMenu: inputTriggers === void 0 ? void 0 : (selection) => {');
  source = replaceOnce(source, 'const commandMenuOpen = useMenuLauncher((source) => source === "command");', `const commandMenuOpen = useMenuLauncher((source) => source === "command");
			const commandMenuOpenRef = (0, react.useRef)(commandMenuOpen);
			commandMenuOpenRef.current = commandMenuOpen;
			(0, react.useEffect)(() => {
				if (keyboard === void 0 || dismissCommandMenu === void 0) return;
				let dispose;
				const bind = () => {
					dispose?.();
					dispose = window.DroyTouch?.bindMenu("composer-add", {
						isOpen: () => commandMenuOpenRef.current,
						close: () => { if (commandMenuOpenRef.current) dismissCommandMenu(); },
						getElement: () => document.querySelector('[data-trigger-menu]')
					});
				};
				bind();
				window.addEventListener("droy-touch-ready", bind);
				return () => { dispose?.(); window.removeEventListener("droy-touch-ready", bind); };
			}, [keyboard, dismissCommandMenu]);`);
  source = replaceOnce(source, 'const [activity, setActivity] = (0, react.useState)(false);', `
			(0, react.useEffect)(() => { if (commandMenuOpen) window.DroyTouch?.menuOpened?.("composer-add"); }, [commandMenuOpen]);
			const [activity, setActivity] = (0, react.useState)(false);`);
  source = replaceFunction(source, 'WorkspaceChip', text => replaceOnce(text, '"aria-label": t("hero.chooseWorkspace"),', `"data-droy-workspace-trigger": true,
					"data-droy-workspace-chip": true,
					onPointerDown: event => {
						if (document.documentElement.hasAttribute("data-droy-mobile") || typeof window.DroyNative !== "undefined") event.stopPropagation();
					},
					"aria-label": t("hero.chooseWorkspace"),`));
  source = replaceOnce(source, 'const [pendingWorkspaceId, setPendingWorkspaceId] = (0, react.useState)();', `const [pendingWorkspaceId, setPendingWorkspaceId] = (0, react.useState)();
			const pickerOpenRef = (0, react.useRef)(pickerOpen);
			pickerOpenRef.current = pickerOpen;
			(0, react.useEffect)(() => {
				let dispose;
				const bind = () => { dispose?.(); dispose = window.DroyTouch?.bindMenu("workspace-picker", {
					isOpen: () => pickerOpenRef.current, close: () => setPickerOpen(false),
					getElement: () => document.querySelector(".droy-workspace-picker")
				}); };
				bind(); window.addEventListener("droy-touch-ready", bind);
				return () => { dispose?.(); window.removeEventListener("droy-touch-ready", bind); };
			}, []);
			(0, react.useEffect)(() => { if (pickerOpen) window.DroyTouch?.menuOpened?.("workspace-picker"); }, [pickerOpen]);`);
  source = replaceOnce(source, 'setPickerOpen(false);\n\t\t\t\t\t\t\tsetPendingWorkspaceId(workspaceId);', 'setPickerOpen(false);\n\t\t\t\t\t\t\tif (workspaceId === (pendingWorkspaceId ?? sessionWorkspace?.workspaceId)) return;\n\t\t\t\t\t\t\tsetPendingWorkspaceId(workspaceId);');
  source = replaceOnce(source, 'if (locked || editor === null) return;\n\t\t\t\tfocusDraftEditor(editor, revealSelection);',
    'if (locked || editor === null || isDroyTouchInteraction()) return;\n\t\t\t\tfocusDraftEditor(editor, revealSelection);');
  source = replaceOnce(source, `if (editor !== null) focusDraftEditor(editor, revealSelection);
				toggleCommandMenu?.(keyboard.caretSpan());`, `if (editor !== null && !isDroyTouchInteraction()) focusDraftEditor(editor, revealSelection);
				toggleCommandMenu?.(keyboard.caretSpan());`);
  source = replaceOnce(source, 'className: InputBar_module_css_default.add,', 'className: InputBar_module_css_default.add,\n\t\t\t\t\t\t\t\t\t\t\t\t"data-droy-composer-add": true,');
  return replaceOnce(source, `onMouseDown: keepFocus,
												onClick: onToggleCommandMenu,`, `onMouseDown: (event) => {
													if (isDroyTouchInteraction()) event.preventDefault();
													else keepFocus(event);
												},
												onClick: onToggleCommandMenu,`);
}

// A scrollable composer can be taller than the chat viewport with large fonts and the IME.
export function droyTriggerMenuPosition(card, viewport) {
  const gap = 4;
  const width = Math.min(card.width, Math.max(0, viewport.width - gap * 2));
  const maxHeight = Math.min(400, Math.max(0, viewport.height - gap * 2));
  return {
    position: 'fixed',
    left: Math.max(viewport.left + gap, Math.min(card.left, viewport.right - gap - width)),
    top: Math.max(viewport.top + gap, Math.min(card.top - maxHeight - gap, viewport.bottom - gap - maxHeight)),
    width, maxHeight, right: 'auto', bottom: 'auto',
  };
}

export function patchInputTrigger(source) {
  if (source.includes('data-droy-trigger-anchor')) throw new Error('Mobile UI patch anchor: input trigger already adapted');
  source = replaceOnce(source, 'let react = require("react");', `let react = require("react");
\t\tlet react_dom = require("react-dom");
\t\t${droyTriggerMenuPosition.toString()}`);
  return replaceFunction(source, 'MenuView', text => {
    text = replaceOnce(text, 'const viewportRef = (0, react.useRef)(null);', `const viewportRef = (0, react.useRef)(null);
\t\t\tconst droyAnchorRef = (0, react.useRef)(null);
\t\t\tconst [droyPosition, setDroyPosition] = (0, react.useState)(null);
\t\t\t(0, react.useLayoutEffect)(() => {
\t\t\t\tif (!state.open) { setDroyPosition(null); return; }
\t\t\t\tconst update = () => {
\t\t\t\t\tconst card = droyAnchorRef.current?.closest("[data-composer-card]");
\t\t\t\t\tconst seat = card?.closest("[data-composer-seat]");
\t\t\t\t\tconst mobile = document.documentElement.hasAttribute("data-droy-mobile") || typeof window.DroyNative !== "undefined";
\t\t\t\t\tlet position = null;
\t\t\t\t\tif (mobile && seat?.querySelector("[data-droy-token-usage]") && seat.scrollHeight > seat.clientHeight + 1) {
\t\t\t\t\t\tconst scroll = seat.closest("[data-conversation-scroll]");
\t\t\t\t\t\tif (scroll) {
\t\t\t\t\t\t\tconst bounds = scroll.getBoundingClientRect(), visual = window.visualViewport;
\t\t\t\t\t\t\tconst left = Math.max(bounds.left, visual?.offsetLeft ?? 0), top = Math.max(bounds.top, visual?.offsetTop ?? 0);
\t\t\t\t\t\t\tconst right = Math.min(bounds.right, (visual?.offsetLeft ?? 0) + (visual?.width ?? window.innerWidth));
\t\t\t\t\t\t\tconst bottom = Math.min(bounds.bottom, (visual?.offsetTop ?? 0) + (visual?.height ?? window.innerHeight));
\t\t\t\t\t\t\tposition = droyTriggerMenuPosition(card.getBoundingClientRect(), {left, top, right, bottom, width: right - left, height: bottom - top});
\t\t\t\t\t\t}
\t\t\t\t\t}
\t\t\t\t\tsetDroyPosition(previous => position === null ? previous === null ? previous : null
\t\t\t\t\t\t: previous !== null && Object.keys(position).every(key => previous[key] === position[key]) ? previous : position);
\t\t\t\t};
\t\t\t\tupdate();
\t\t\t\tconst card = droyAnchorRef.current?.closest("[data-composer-card]"), seat = card?.closest("[data-composer-seat]");
\t\t\t\tif (!(document.documentElement.hasAttribute("data-droy-mobile") || typeof window.DroyNative !== "undefined") || !seat) return;
\t\t\t\tconst observer = new ResizeObserver(update);
\t\t\t\tif (card) observer.observe(card);
\t\t\t\tif (seat) observer.observe(seat);
\t\t\t\tconst scroll = seat?.closest("[data-conversation-scroll]");
\t\t\t\tif (scroll) observer.observe(scroll);
\t\t\t\twindow.addEventListener("resize", update);
\t\t\t\twindow.addEventListener("scroll", update, true);
\t\t\t\twindow.visualViewport?.addEventListener("resize", update);
\t\t\t\treturn () => { observer.disconnect(); window.removeEventListener("resize", update); window.removeEventListener("scroll", update, true); window.visualViewport?.removeEventListener("resize", update); };
\t\t\t}, [state.open]);`);
    // The menu's DOM parent changes in portal mode; its original composer remains an inside click.
    text = replaceOnce(text, '(listRef.current?.closest("[data-composer-card]"))?.contains(ev.target)',
      '(droyAnchorRef.current?.closest("[data-composer-card]"))?.contains(ev.target)');
    text = replaceOnce(text, 'return (0, react_jsx_runtime.jsxs)(_deepseek_ai_dsh_client_ui_primitives.MenuSurface, {',
      'const droyMenu = (0, react_jsx_runtime.jsxs)(_deepseek_ai_dsh_client_ui_primitives.MenuSurface, {');
    text = replaceOnce(text, 'style: { maxHeight },', `style: droyPosition ?? { maxHeight: document.documentElement.hasAttribute("data-droy-mobile") || typeof window.DroyNative !== "undefined"
\t\t\t\t\t? \`max(0px, calc(\${maxHeight}px - var(--droy-safe-top, 0px)))\` : maxHeight },
\t\t\t\t"data-droy-trigger-portal": droyPosition !== null || void 0,`);
    return replaceOnce(text, '\n\t\t\t});\n\t\t}', `
\t\t\t});
\t\t\treturn (0, react_jsx_runtime.jsxs)(react.Fragment, {children: [
\t\t\t\t(0, react_jsx_runtime.jsx)("span", {ref: droyAnchorRef, "data-droy-trigger-anchor": true, style: {display:"none"}}),
\t\t\t\tdroyPosition === null ? droyMenu : react_dom.createPortal(droyMenu, document.body)
\t\t\t]});
\t\t}`);
  });
}

export function patchSidebar(source) {
  if (source.includes('data-droy-drawer-close')) throw new Error('Mobile UI patch anchor: sidebar already adapted');
  source = replaceOnce(source, 'className: clsx(SidebarRoot_module_css_default.iconButton, SidebarRoot_module_css_default.toggle),',
    'className: clsx(SidebarRoot_module_css_default.iconButton, SidebarRoot_module_css_default.toggle),\n\t\t\t\t\t"data-droy-drawer-close": true,');
  return replaceOnce(source, 'className: SidebarRoot_module_css_default.logoRow,',
    'className: SidebarRoot_module_css_default.logoRow,\n\t\t\t\t\t\t"data-droy-drawer-header": true,');
}

export function patchSidebarRight(source) {
  if (source.includes('data-droy-mobile-panel')) throw new Error('Mobile UI patch anchor: right panel already adapted');
  source = replaceOnce(source, 'const autoFullscreen = viewportWidth < 768;', 'const autoFullscreen = document.documentElement.hasAttribute("data-droy-mobile") || typeof window.DroyNative !== "undefined" || viewportWidth < 768;');
  source = replaceFunction(source, 'SidebarPanel', text => {
    text = replaceOnce(text, 'className: SidebarRight_module_css_default.panel,', 'className: SidebarRight_module_css_default.panel,\n\t\t\t\t"data-droy-mobile-panel": true,');
    text = replaceOnce(text, 'width: fullscreen ? "100vw" : width,', 'width: document.documentElement.hasAttribute("data-droy-mobile") || typeof window.DroyNative !== "undefined" ? "100%" : fullscreen ? "100vw" : width,');
    return replaceOnce(text, 'className: SidebarRight_module_css_default.panelBody,', 'className: SidebarRight_module_css_default.panelBody,\n\t\t\t\t\t"data-droy-mobile-panel-body": true,');
  });
  return replaceFunction(source, 'PanelChrome', text => replaceOnce(text, '(react_jsx_runtime.Fragment, { children:', '("span", { "data-droy-panel-chrome": true, style:{display:"inline-flex",alignItems:"center"}, children:'));
}

export function patchModelSelection(source) {
  if (source.includes('data-droy-model-trigger')) throw new Error('Mobile UI patch anchor: model picker already adapted');
  return replaceFunction(source, 'ModelSelect', text => {
    text = replaceOnce(text, 'const [open, setOpen] = (0, react.useState)(false);', `const [open, setOpen] = (0, react.useState)(false);
			const droyOpenRef = (0, react.useRef)(open);
			droyOpenRef.current = open;
			(0, react.useEffect)(() => {
				let dispose;
				const bind = () => { dispose?.(); dispose = window.DroyTouch?.bindMenu("model-picker", {
					isOpen: () => droyOpenRef.current, close: () => close(false), getElement: () => menuRef.current
				}); };
				bind(); window.addEventListener("droy-touch-ready", bind);
				return () => { dispose?.(); window.removeEventListener("droy-touch-ready", bind); };
			}, []);
			(0, react.useEffect)(() => { if (open) window.DroyTouch?.menuOpened?.("model-picker"); }, [open]);`);
    text = replaceOnce(text, 'className: ModelSelect_module_css_default.trigger,', 'className: ModelSelect_module_css_default.trigger,\n\t\t\t\t\t\t"data-droy-model-trigger": true,');
    return replaceOnce(text, 'className: ModelSelect_module_css_default.menu,', 'className: ModelSelect_module_css_default.menu,\n\t\t\t\t\t\t"data-droy-model-menu": true,');
  });
}

export function patchImageLightbox(source) {
  if (source.includes('data-droy-attachment-lightbox')) throw new Error('Mobile UI patch anchor: image lightbox already adapted');
  const start = source.indexOf('function aw({src:e,alt:t,labels:r,onClose:i}){');
  const end = source.indexOf('function qF(', start);
  if (start < 0 || end < 0) throw new Error('Mobile UI patch anchor: shared image lightbox');
  const original = source.slice(start, end);
  let updated = replaceOnce(original, 'const n=C.useRef(null),s=C.useRef(null);return', `const n=C.useRef(null),s=C.useRef(null),o=C.useRef(null),a=document.documentElement.hasAttribute("data-droy-mobile")||typeof window.DroyNative!=="undefined",f=C.useRef(i),d=C.useId();f.current=i;C.useEffect(()=>{if(!a)return;let dispose;const id="attachment-lightbox:"+d;const bind=()=>{dispose?.();dispose=window.DroyTouch?.bindMenu(id,{isOpen:()=>o.current?.isConnected===true,close:()=>f.current(),getElement:()=>o.current});window.DroyTouch?.menuOpened?.(id)};bind();window.addEventListener("droy-touch-ready",bind);return()=>{dispose?.();window.removeEventListener("droy-touch-ready",bind)}},[a,d]);return`);
  updated = replaceOnce(updated, 'C.useEffect(()=>{s.current=', 'C.useEffect(()=>{const closingOwner=o.current;s.current=');
  updated = replaceOnce(updated, 's.current?.focus()', 'a&&window.DroyTouch?window.DroyTouch.restoreFocus(s.current,document.querySelector("[data-droy-menu]"),closingOwner):s.current?.focus()');
  updated = replaceOnce(updated, 'className:Cs.backdrop,role:', 'ref:o,"data-droy-attachment-lightbox":a||void 0,className:Cs.backdrop,role:');
  updated = replaceOnce(updated, 'className:Cs.stage,children:', '"data-droy-attachment-stage":a||void 0,className:Cs.stage,children:');
  updated = replaceOnce(updated, 'className:Cs.image,src:', '"data-droy-attachment-image":a||void 0,className:Cs.image,src:');
  updated = replaceOnce(updated, 'ref:n,type:"button",className:Cs.close,', 'ref:n,"data-droy-attachment-close":a||void 0,type:"button",className:Cs.close,');
  return source.slice(0, start) + updated + source.slice(end);
}

export function patchThemeSettings(source) {
  if (source.includes('data-droy-font-row')) throw new Error('Mobile UI patch anchor: theme settings already adapted');
  source = replaceFunction(source, 'AppearanceRow', text => {
    text = replaceOnce(text, 'className: AppearanceRow_module_css_default.group,', 'className: AppearanceRow_module_css_default.group,\n\t\t\t\t"data-droy-theme-group": true,');
    text = replaceOnce(text, 'className: AppearanceRow_module_css_default.cubeRow,', 'className: AppearanceRow_module_css_default.cubeRow,\n\t\t\t\t\t"data-droy-theme-choices": true,');
    return replaceOnce(text, '"aria-pressed": preference === id,', '"data-droy-theme-choice": id,\n\t\t\t\t\t\t"aria-pressed": preference === id,');
  });
  source = replaceFunction(source, 'FontFamilyRow', text => {
    text = replaceOnce(text, 'className: FontSizeRow_module_css_default.row,', 'className: FontSizeRow_module_css_default.row,\n\t\t\t\t"data-droy-font-row": "family",\n\t\t\t\t"data-droy-font-role": role,');
    text = replaceOnce(text, 'className: FontSizeRow_module_css_default.rowText,', 'className: FontSizeRow_module_css_default.rowText,\n\t\t\t\t\t"data-droy-font-copy": true,');
    text = replaceOnce(text, 'className: FontSizeRow_module_css_default.title,', 'className: FontSizeRow_module_css_default.title,\n\t\t\t\t\t\t"data-droy-font-title": true,');
    text = replaceOnce(text, 'className: FontSizeRow_module_css_default.desc,', 'className: FontSizeRow_module_css_default.desc,\n\t\t\t\t\t\t"data-droy-font-description": true,');
    return replaceOnce(text, 'className: FontFamilyRow_module_css_default.field,', 'className: FontFamilyRow_module_css_default.field,\n\t\t\t\t\t"data-droy-font-field": true,');
  });
  source = replaceFunction(source, 'FontSizeRow', text => {
    text = replaceOnce(text, 'className: FontSizeRow_module_css_default.row,', 'className: FontSizeRow_module_css_default.row,\n\t\t\t\t"data-droy-font-row": "size",\n\t\t\t\t"data-droy-font-role": role,');
    for (const [css, marker] of [['rowText','copy'],['title','title'],['desc','description'],['control','control'],['stepper','stepper'],['value','value'],['arrows','arrows'],['unit','unit'],['expander','expander']]) {
      text = replaceOnce(text, `className: FontSizeRow_module_css_default.${css},`, `className: FontSizeRow_module_css_default.${css},\n\t\t\t\t\t"data-droy-font-${marker}": true,`);
    }
    for (const direction of ['increase','decrease']) {
      text = replaceOnce(text, `"aria-label": t(\`fontSize.\${role}.${direction}\`),`, `"data-droy-font-step": "${direction}",\n\t\t\t\t\t\t\t\t"aria-label": t(\`fontSize.\${role}.${direction}\`),`);
    }
    return text;
  });
  return replaceFunction(source, 'FontSettingsGroup', text => {
    text = replaceOnce(text, 'className: FontSettingsGroup_module_css_default.group,', 'className: FontSettingsGroup_module_css_default.group,\n\t\t\t\t"data-droy-font-group": true,');
    return replaceOnce(text, 'id: regionId,', 'id: regionId,\n\t\t\t\t\t"data-droy-font-advanced": true,');
  });
}
