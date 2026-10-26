// Brand seams not exposed by the pinned DSH client slots.
function replaceOnce(source, from, to) {
  if (source.split(from).length !== 2) throw new Error(`Droy brand patch anchor: ${from.slice(0, 90)}`);
  return source.replace(from, to);
}

const brandHook = `
		const droyDefaultBranding = () => (window.document?.documentElement?.lang || "zh").startsWith("zh") ? {name:"Droy",welcome:"与Droy开始对话",composer:"请描述你想让Droy执行的任务，或者你的对话"} : {name:"Droy",welcome:"Start a conversation with Droy",composer:"Describe a task for Droy, or start a conversation"};
		function useDroyBranding() {
			const read = () => window.DroyBranding?.getSnapshot() ?? droyDefaultBranding();
			const [branding, setBranding] = (0, react.useState)(read);
			(0, react.useEffect)(() => {
				const update = () => setBranding(read());
				window.addEventListener("droy-branding-change", update);
				update();
				return () => window.removeEventListener("droy-branding-change", update);
			}, []);
			return branding;
		}
`;

export function patchConversationBranding(source) {
  source = replaceOnce(source, 'let react = require("react");', 'let react = require("react");' + brandHook);
  source = replaceOnce(source, 'function HeroShell({ t, renderSlot, children }) {',
    'function HeroShell({ t, renderSlot, children }) {\n\t\t\tconst droyBranding = useDroyBranding();');
  source = replaceOnce(source, 'className: HeroShell_module_css_default.headline,',
    'className: HeroShell_module_css_default.headline,\n\t\t\t\t\t\t"data-droy-hero-headline": true,');
  source = replaceOnce(source, 'className: HeroShell_module_css_default.titleGroup,',
    'className: HeroShell_module_css_default.titleGroup,\n\t\t\t\t\t\t\t"data-droy-hero-title": true,');
  source = replaceOnce(source, '{ fallback: (0, react_jsx_runtime.jsx)(HeroFish, { hovering }) }', '{ fallback: null }');
  source = replaceOnce(source, 'children: [(0, react_jsx_runtime.jsx)("span", { children: t("hero.headline") }), (0, react_jsx_runtime.jsx)("span", {\n\t\t\t\t\t\t\t\tclassName: HeroShell_module_css_default.previewBadge,\n\t\t\t\t\t\t\t\tchildren: t("hero.preview")\n\t\t\t\t\t\t\t})]',
    'children: [renderSlot("droy.brand.hero-title", {}, {fallback: droyBranding.welcome}), renderSlot("droy.brand.hero-badge", {}, {fallback: null})]');
  source = replaceOnce(source, 'const { sessionId, phase, hero, useSession, useSessions, useSessionStatus, useWorkspaces, useInput, useComposerBlock, renderSlot, renderSlotChain, selectWorkspace, t, useFactorySlot } = props;',
    'const { sessionId, phase, hero, useSession, useSessions, useSessionStatus, useWorkspaces, useInput, useComposerBlock, renderSlot, renderSlotChain, selectWorkspace, t, useFactorySlot } = props;\n\t\t\tconst droyBranding = useDroyBranding();');
  source = replaceOnce(source, '} : hero ? { placeholder: t("placeholder.hero") } : {}', '} : hero ? { placeholder: droyBranding.composer } : {}');
  source = replaceOnce(source, 'const placeholderText = placeholder ?? (parentOffline ? t("placeholder.parentOffline") : disabled ? t("placeholder.unavailable") : canSteerQueue ? t("placeholder.steerQueue") : planActive ? t("placeholder.plan") : t("placeholder.default"));',
    'const droyBranding = useDroyBranding();\n\t\t\tconst placeholderText = placeholder ?? (parentOffline ? t("placeholder.parentOffline") : disabled ? t("placeholder.unavailable") : canSteerQueue ? t("placeholder.steerQueue") : planActive ? t("placeholder.plan") : droyBranding.composer);');
  return replaceOnce(source, '"conversation.hero.brand.mark": {\n\t\t\t\t\t\tkind: "single",\n\t\t\t\t\t\tscope: "root"\n\t\t\t\t\t},',
    '"droy.brand.hero-title": {kind:"single",scope:"root"},\n\t\t\t\t\t"droy.brand.hero-badge": {kind:"single",scope:"root"},\n\t\t\t\t\t"conversation.hero.brand.mark": {\n\t\t\t\t\t\tkind: "single",\n\t\t\t\t\t\tscope: "root"\n\t\t\t\t\t},');
}

export function patchSidebarBranding(source) {
  for (const [key, marker] of [['brandIdentity', 'container'], ['brandName', 'name-slot']]) {
    source = replaceOnce(source, `className: SidebarRoot_module_css_default.${key},`,
      `className: SidebarRoot_module_css_default.${key},\n\t\t\t\t\t\t\t\t"data-droy-brand-${marker}": true,`);
  }
  const whale = '{ fallback: (0, react_jsx_runtime.jsx)(_deepseek_ai_dsh_client_ui_primitives.FishLogo, { size: 24 }) }';
  if (source.split(whale).length !== 3) throw new Error('Droy sidebar mark fallback anchor');
  source = source.replaceAll(whale, '{ fallback: null }');
  source = replaceOnce(source, '"data-droy-brand-container": true,\n\t\t\t\t\t\t\t\t"aria-hidden": "true",', '"data-droy-brand-container": true,');
  return source;
}

export function patchModelsBranding(source) {
  source = replaceOnce(source, 'let react = require("react");', 'let react = require("react");' + brandHook);
  source = replaceOnce(source, 'const { complete, controller, useWelcome, t } = props;',
    'const { complete, controller, useWelcome, t } = props;\n\t\t\tconst droyBranding = useDroyBranding();');
  source = replaceOnce(source, 'title: t("welcomeTitle"),', 'title: t("welcomeTitle").replace("Droy", () => droyBranding.name),');
  const replacements = [
    ['welcomeTitle', ['Welcome to Droy', '欢迎使用 Droy']],
    ['welcomeBody', ['Chat with your configured model on this phone. Manage Android permissions in Settings → Permissions. Information you allow the assistant to read is sent to the selected model.', '在手机上连接你配置的模型进行对话。读取的信息会发送给所选模型。可在设置的「权限设置」中管理手机能力和系统授权。']],
  ];
  for (const [key, values] of replacements) {
    let count = 0;
    source = source.replace(new RegExp(`${key}: "(?:[^"\\\\]|\\\\.)*"`, 'g'), () => `${key}: ${JSON.stringify(values[count++])}`);
    if (count !== 2) throw new Error(`Droy welcome copy anchor: ${key}`);
  }
  return source;
}

export function patchAccountBranding(source) {
  const menuStart = source.indexOf('\t\t\t\t\t(0, react_jsx_runtime.jsx)(_deepseek_ai_dsh_client_ui_primitives.Menu, {', source.indexOf('function AccountMenu('));
  const menuEnd = source.indexOf('\n\t\t\t\t\taccount.loginVisible', menuStart);
  if (menuStart < 0 || menuEnd < 0) throw new Error('Droy settings launcher anchor');
  source = source.slice(0, menuStart) + `\t\t\t\t\t(0, react_jsx_runtime.jsxs)("button", {
\t\t\t\t\t\tref: trigger, type: "button", className: AccountMenu_module_css_default.trigger,
\t\t\t\t\t\t"data-droy-settings-launcher": true, "data-collapsed": !wide,
\t\t\t\t\t\t"aria-label": t("settings"), onClick: () => openSettings(),
\t\t\t\t\t\tchildren: [(0, react_jsx_runtime.jsx)(_deepseek_ai_dsh_client_ui_primitives.IconSettingsOutlineMedium, {size: 20, "aria-hidden": true}),
\t\t\t\t\t\t\twide && (0, react_jsx_runtime.jsx)("span", {className: AccountMenu_module_css_default.label, children: t("settings")})]
\t\t\t\t\t}),` + source.slice(menuEnd);
  // Account sign-in belongs to DSH's hosted account service. The model dialog
  // already provides a local API-key editor when this optional seat is absent.
  source = replaceOnce(source, '\n\t\t\tctx.slots.inject("settings.models.sign-in", () => ctx.slots.register({\n\t\t\t\tname: "settings.models.sign-in",\n\t\t\t\tlocale: "settings.account",\n\t\t\t\tinject: () => operations\n\t\t\t}, AccountOnboarding));', '');
  source = replaceOnce(source, 'account.loginVisible && !account.onboarding && (0, react_jsx_runtime.jsx)(SignInDialog, {', 'false && (0, react_jsx_runtime.jsx)(SignInDialog, {');
  return source;
}

export function patchProductCopy(source, count) {
  if (source.split('DeepSeek Harness').length !== count + 1) throw new Error('Droy product copy anchor');
  return source.replaceAll('DeepSeek Harness', 'Droy');
}

export function patchSpaceCopy(source) {
  return source.replaceAll('工作区', '空间');
}

export function patchPresetCopy(source) {
  source = patchSpaceCopy(source);
  source = replaceOnce(source,
    'className: AgentPresetSeat_module_css_default.menuAnchor,',
    'className: AgentPresetSeat_module_css_default.menuAnchor,\n\t\t\t\tlistClassName: "droy-mode-picker",');
  source = replaceOnce(source,
    '处理代码、文件和资料，适合大多数任务。Agent 会按需使用检索、编辑和终端等工具。',
    '处理日常对话、手机操作和资料整理，按需使用工具完成任务。');
  source = replaceOnce(source,
    '用对话定制 DSH：让 Agent 编写插件，添加新功能或界面；也能组合工具和提示词，创建自己的模式。',
    '创建模式与扩展能力：通过对话创建自己的模式、插件或界面。');
  source = replaceOnce(source,
    '日常编程、文件处理和资料整理可以从这里开始。标准模式也能编写脚本、批量处理文件；PTC 改变的是工具调用方式，批量任务并不必须使用 PTC。',
    '日常对话、手机操作、文件处理和资料整理都可以从这里开始。说明目标后，Droy 会按需使用工具完成任务。');
  source = replaceOnce(source,
    'Start here for everyday coding, file work, and research. Standard mode can also write scripts and process files in batches. PTC changes how tool calls are organized; it is not required for batch tasks.',
    'Start here for everyday conversations, phone tasks, file work, and research. Describe your goal and Droy will use the tools it needs.');
  source = replaceOnce(source,
    'Work with code, files, and information. Suitable for most tasks, with search, editing, terminal commands, and other tools available as needed.',
    'Handle everyday conversations, phone tasks, files, and information, using tools as needed.');
  source = replaceOnce(source,
    'Customize DSH through conversation. Let the agent write plugins that add features or UI, or combine tools and prompts to create your own mode.',
    'Create modes and add capabilities: use conversation to create your own modes, plugins, or UI.');
  return source.replaceAll('扩展 DSH 的能力', '扩展 Droy 的能力').replaceAll('DSH 插件', 'Droy 插件');
}

export function patchShortcutBranding(source) {
  if (source.split('Harness').length !== 5) throw new Error('Droy shortcut copy anchor');
  return source.replaceAll('Harness', 'Droy');
}

export function patchFrontendBranding(source) {
  source = replaceOnce(source, '    <link rel="icon" type="image/svg+xml" href="./favicon-dark.svg" media="(prefers-color-scheme: dark)" />\n    <link rel="icon" type="image/svg+xml" href="./favicon.svg" media="(prefers-color-scheme: light)" />',
    '    <link rel="icon" type="image/png" href="/droy/branding/logo?petId=default" data-droy-favicon />');
  return replaceOnce(source, '<title>DeepSeek Harness</title>', '<title>Droy</title>');
}

export function patchWebManifestBranding(source) {
  const manifest = JSON.parse(source);
  if (manifest.name !== 'DeepSeek Harness' || manifest.short_name !== 'DSH' || manifest.icons?.[0]?.src !== 'favicon.svg') throw new Error('Droy manifest brand anchor');
  manifest.name = 'Droy'; manifest.short_name = 'Droy';
  manifest.icons = [{src:'/droy/branding/logo?petId=default',sizes:'any',type:'image/png',purpose:'any'}];
  return JSON.stringify(manifest, null, 2) + '\n';
}
