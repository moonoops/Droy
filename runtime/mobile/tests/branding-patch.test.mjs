import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { createHash } from 'node:crypto';
import { spawnSync } from 'node:child_process';
import { patchConversationBranding, patchSidebarBranding, patchModelsBranding, patchAccountBranding, patchProductCopy, patchEntryCustomizationCopy, patchPresetCopy, patchShortcutBranding, patchFrontendBranding, patchWebManifestBranding } from '../../../tools/mobile_brand_patch.mjs';
import { patchConversation, patchSidebar, patchLayout } from '../../../tools/mobile_ui_patch.mjs';
import { readUpstreamProfile, mobileProfilePatches, applyEntryPatches } from '../../../tools/mobile_profile.mjs';

const root = new URL('../../dsh/node_modules/', import.meta.url);
const upstream = async name => readFile(new URL(name, root), 'utf8');
const files = [
  ['@deepseek-ai/dsh-client-ui-conversation/lib/client.js', source => patchConversationBranding(patchConversation(source))],
  ['@deepseek-ai/dsh-client-ui-sidebar/lib/client.js', source => patchSidebarBranding(patchSidebar(source))],
  ['@deepseek-ai/dsh-client-ui-settings-models/lib/client.js', patchModelsBranding],
  ['@deepseek-ai/dsh-client-ui-settings-account/lib/client.js', patchAccountBranding],
  ['@deepseek-ai/dsh-client-ui-plugin-manager/lib/client.js', source => patchProductCopy(source, 2)],
  ['@deepseek-ai/dsh-client-ui-sidebar-documentpreview/lib/client.js', source => patchProductCopy(source, 2)],
  ['@deepseek-ai/dsh-client-ui-shortcuts/lib/client.js', patchShortcutBranding],
  ['@deepseek-ai/dsh-web-frontend/dist/index.html', patchFrontendBranding],
  ['@deepseek-ai/dsh-web-frontend/dist/manifest.webmanifest', patchWebManifestBranding],
  ['@deepseek-ai/dsh-client-ui-agent-preset/lib/client.js', patchPresetCopy],
  ['@deepseek-ai/dsh-client-ui-entry-customization/lib/client.js', patchEntryCustomizationCopy],
];

test('brand patches accept only reviewed upstream inputs and produce valid JavaScript', async () => {
  const lock = JSON.parse(await readFile(new URL('../upstream-patches.lock.json', import.meta.url)));
  for (const [file, patch] of files) {
    const source = await upstream(file);
    assert.equal(createHash('sha256').update(source).digest('hex'), lock.sha256[file], file);
    const result = patch(source);
    assert.notEqual(result, source, file);
    assert.throws(() => patch(''), undefined, file);
    if (file.endsWith('.js')) {
      const check = spawnSync(process.execPath, ['--check', '--input-type=module'], {input:result, encoding:'utf8'});
      assert.equal(check.status, 0, `${file}: ${check.stderr}`);
    }
  }
});

test('hero renders configurable plain text and has no preview badge or whale fallback', async () => {
  const source = patchConversationBranding(await upstream(files[0][0]));
  const start = source.indexOf('function HeroShell(');
  const code = source.slice(start, source.indexOf('\n\t\t//#endregion', start));
  const jsx = (type, props) => ({type, props});
  const Hero = new Function('react', 'react_jsx_runtime', 'HeroShell_module_css_default', 'useDroyBranding',
    `${code};return HeroShell;`)({useState:value=>[value,()=>{}]}, {jsx,jsxs:jsx}, {},
    ()=>({welcome:'与测试伙伴开始对话<script>'}));
  const slots = [];
  Hero({t:key=>key,renderSlot:(name,props,options)=>{slots.push({name,options});return null;}});
  assert.equal(slots.find(slot=>slot.name==='droy.brand.hero-title').options.fallback, '与测试伙伴开始对话<script>');
  assert.equal(slots.find(slot=>slot.name==='droy.brand.hero-badge').options.fallback, null);
  assert.equal(slots.find(slot=>slot.name==='conversation.hero.brand.mark').options.fallback, null);
  assert.ok(source.includes('"droy.brand.hero-title": {kind:"single",scope:"root"}'));
});

test('sidebar wrapper markers leave product identity markers unique and accessible', async () => {
  const source = patchSidebarBranding(patchSidebar(await upstream(files[1][0])));
  assert.ok(source.includes('"data-droy-brand-container": true,'));
  assert.ok(source.includes('"data-droy-brand-name-slot": true,'));
  assert.ok(!source.includes('"data-droy-brand-identity":'));
  assert.ok(!source.includes('"data-droy-brand-name":'));
  assert.ok(!source.includes('"data-droy-brand-container": true,\n\t\t\t\t\t\t\t\t"aria-hidden"'));
});

test('normal and hero placeholders follow branding while operational states retain their text', async () => {
  const source = patchConversationBranding(await upstream(files[0][0]));
  assert.match(source, /hero \? \{ placeholder: droyBranding\.composer \}/);
  const expression = source.match(/const placeholderText = (.*);/)[1];
  const placeholder = new Function('placeholder','parentOffline','disabled','canSteerQueue','planActive','t','droyBranding',`return ${expression};`);
  const value = (explicit,...flags) => placeholder(explicit,...flags,key=>key,{composer:'请描述你想让小黑执行的任务，或者你的对话'});
  assert.equal(value(undefined,false,false,false,false), '请描述你想让小黑执行的任务，或者你的对话');
  assert.equal(value(undefined,true,false,false,false), 'placeholder.parentOffline');
  assert.equal(value(undefined,false,true,false,false), 'placeholder.unavailable');
  assert.equal(value(undefined,false,false,true,false), 'placeholder.steerQueue');
  assert.equal(value(undefined,false,false,false,true), 'placeholder.plan');
  assert.equal(value('等待工作区',false,false,false,false), '等待工作区');
});

test('brand hooks refresh already mounted components and remove their listener on cleanup', async () => {
  const source = patchConversationBranding(await upstream(files[0][0]));
  const start = source.indexOf('const droyDefaultBranding');
  const end = source.indexOf('\n\t\t}', source.indexOf('function useDroyBranding()', start)) + '\n\t\t}'.length;
  const listeners = new Map(), effects = [], updates = [];
  let snapshot;
  const window = {DroyBranding:{getSnapshot:()=>snapshot},addEventListener:(type,fn)=>listeners.set(type,fn),removeEventListener:(type,fn)=>{assert.equal(listeners.get(type),fn);listeners.delete(type);}};
  const react = {useState:read=>[read(),value=>updates.push(value)],useEffect:effect=>effects.push(effect)};
  const hook = new Function('react','window',`${source.slice(start,end)};return useDroyBranding;`)(react,window);
  assert.equal(hook().name, 'Droy');
  const cleanup = effects[0]();
  snapshot = {name:'小黑',welcome:'与小黑开始对话',composer:'自定义提示'};
  listeners.get('droy-branding-change')();
  assert.equal(updates.at(-1),snapshot);
  cleanup(); assert.equal(listeners.size,0);
});

test('sidebar settings button opens settings directly in both languages and account states', async () => {
  const source = patchAccountBranding(await upstream(files[3][0]));
  const start = source.indexOf('function AccountMenu(');
  const code = source.slice(start, source.indexOf('\n\t\t//#endregion', start));
  const jsx = (type, props) => ({ type, props });
  const Menu = new Function('react', 'react_jsx_runtime', '_deepseek_ai_dsh_client_ui_primitives', 'AccountMenu_module_css_default',
    `${code};return AccountMenu;`)({useRef: value => ({current:value}), useState: value => [value, () => {}], useEffect() {}},
    {jsx, jsxs:jsx}, {IconSettingsOutlineMedium:'settings-icon'}, {});
  for (const signedIn of [false,true]) for (const label of ['设置','Settings']) {
    let opened = 0;
    const tree = Menu({wide:true, openSettings:() => opened++, t:key => key === 'settings' ? label : key,
      useAccount: () => ({view:{status:signedIn ? 'credential-stored' : 'signed-out'}}), useTheme: () => 'light'});
    const button = tree.props.children.find(node => node?.props?.['data-droy-settings-launcher']);
    assert.equal(button.type, 'button');
    assert.equal(button.props['aria-label'], label);
    assert.equal(button.props['aria-haspopup'], undefined);
    assert.equal(button.props.children[0].type, 'settings-icon');
    assert.equal(button.props.children[1].props.children, label);
    assert.equal(tree.props.children.some(node => node?.type === 'menu'), false);
    button.props.onClick();
    assert.equal(opened, 1);
  }
  assert.ok(!code.includes('_deepseek_ai_dsh_client_ui_primitives.Menu,'));
  assert.ok(!source.includes('ctx.slots.inject("settings.models.sign-in"'));
  assert.ok(source.includes('false && (0, react_jsx_runtime.jsx)(SignInDialog'));
  const models = patchModelsBranding(await upstream(files[2][0]));
  assert.ok(models.includes('}, { fallback: editor })'));
  assert.ok(models.includes('apiKey ? editor : renderSlot("settings.models.sign-in"'));
});

test('Droy replaces the official brand plugin without disabling provider or model features', async () => {
  const rows = await readUpstreamProfile(root.pathname);
  const patches = mobileProfilePatches(rows);
  const result = applyEntryPatches(rows,patches,message=>{throw new Error(message);});
  assert.equal(result.find(row=>row.id==='ui-brand-official').disabled,true);
  assert.notEqual(result.find(row=>row.id==='ui-settings-account').disabled,true);
  for (const row of rows.filter(row=>!row.disabled && !['attachment-local','ui-brand-official','subprocess','fs-sandbox'].includes(row.id) && row.name!=='@deepseek-ai/dsh-agent-preset'))
    assert.notEqual(result.find(candidate=>candidate.id===row.id)?.disabled,true,row.id);
  assert.equal(result.find(row=>row.id==='droy-execution')?.name,'@droyagent/execution');
});

test('generated space and mode surfaces include the mobile product wording', async () => {
  const generated = new URL('../../generated/dsh/node_modules/@deepseek-ai/', import.meta.url);
  for (const name of ['dsh-client-ui-settings-general', 'dsh-client-ui-workspace', 'dsh-client-ui-conversation', 'dsh-client-locale', 'dsh-client-ui-directory-picker-browse', 'dsh-client-ui-reference', 'dsh-client-ui-agent-preset']) {
    const source = await readFile(new URL(`${name}/lib/client.js`, generated), 'utf8');
    assert.ok(!source.includes('工作区'), `${name} still exposes developer workspace wording`);
  }
  const modes = await readFile(new URL('dsh-client-ui-agent-preset/lib/client.js', generated), 'utf8');
  assert.ok(modes.includes('日常对话、手机操作'));
  assert.ok(modes.includes('创建模式与扩展能力'));
});

test('initial document branding and additional product copy omit the old product identity', async () => {
  const html = patchFrontendBranding(await upstream(files[7][0]));
  assert.ok(html.includes('<title>Droy</title>'));
  assert.ok(html.includes('/droy/branding/logo?petId=default'));
  assert.ok(!html.includes('favicon.svg'));
  const manifest = JSON.parse(patchWebManifestBranding(await upstream(files[8][0])));
  assert.equal(manifest.name,'Droy'); assert.equal(manifest.short_name,'Droy');
  const layout = patchLayout(await upstream('@deepseek-ai/dsh-client-ui-layout/lib/client.js'));
  assert.ok(!layout.includes('const productTitle = "DeepSeek Harness"'));
  assert.ok(layout.includes('window.DroyBranding?.getSnapshot()?.name || "Droy"'));
  const models = patchModelsBranding(await upstream(files[2][0]));
  assert.ok(models.includes('title: t("welcomeTitle").replace("Droy", () => droyBranding.name)'));
  assert.ok(!models.includes('DeepSeek Harness'));
  // Provider identifiers remain valid configuration, rather than brand copy.
  assert.ok(models.includes('deepseek-official'));
});
