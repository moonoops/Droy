import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { droyWorkspaceKind, patchWorkspace, patchSidebar } from '../../../tools/mobile_ui_patch.mjs';

const client = await readFile(new URL('../client.js', import.meta.url), 'utf8');
const workspace = patchWorkspace(await readFile(new URL('../../dsh/node_modules/@deepseek-ai/dsh-client-ui-workspace/lib/client.js', import.meta.url), 'utf8'));
const sidebar = patchSidebar(await readFile(new URL('../../dsh/node_modules/@deepseek-ai/dsh-client-ui-sidebar/lib/client.js', import.meta.url), 'utf8'));
const jsx = (type, props, ...children) => ({ type, props: { ...props, children } });
const nodes = value => !value || typeof value !== 'object' ? [] : Array.isArray(value) ? value.flatMap(nodes) : [value, ...nodes(value.props?.children)];
function excerpt(source, name) {
  const start = source.indexOf(`function ${name}(`), next = source.indexOf('\n\t\tfunction ', start + 1), boundary = source.indexOf('\n\t\t//#endregion', start);
  return source.slice(start, next >= 0 && next < boundary ? next : boundary);
}

test('workspace kinds use the initialized default identity across languages, renames and same-name user spaces', () => {
  for (const title of ['默认空间','Default space','Renamed','default-workspace']) {
    assert.equal(droyWorkspaceKind({workspaceId:'default-id', title, path:'/data/user/0/io.droyagent.app.dev/files/dsh-home/workspace'}, [], 'default-id'), 'default');
  }
  assert.equal(droyWorkspaceKind({workspaceId:'owned', title:'Work', path:'/other'}, ['owned'], 'default-id'), 'companion');
  assert.equal(droyWorkspaceKind({workspaceId:'custom', title:'默认空间', path:'/projects/default-workspace'}, [], 'default-id'), 'user');
  assert.equal(droyWorkspaceKind({workspaceId:'custom', title:'Default space', path:'/projects/workspace'}, [], 'default-id'), 'user');
  assert.equal(droyWorkspaceKind({workspaceId:'custom', title:'default-workspace', path:'/projects/default-workspace'}), 'user');
  assert.equal(droyWorkspaceKind(undefined), 'ungrouped');
});

test('actual derived space groups keep users then default then ungrouped then companions despite recent activity', () => {
  const rows = [{key:'buddy',workspaceId:'buddy',sessions:[]}, {key:'d',workspaceId:'d',sessions:[]}, {key:'second',workspaceId:'second',sessions:[]}, {key:'first',workspaceId:'first',sessions:[]}, {key:'',sessions:[]}];
  const workspaces = [{workspaceId:'buddy',title:'Buddy'}, {workspaceId:'d',title:'Renamed',path:'/data/home/workspace'}, {workspaceId:'second',title:'Second'}, {workspaceId:'first',title:'First'}];
  const derive = new Function('document','window','droyWorkspaceKind','droyWorkspaceRanks','groupByWorkspace','mainSessionId','owningGroupKey',
    `${excerpt(workspace,'deriveGroups')};return deriveGroups;`)(
      {documentElement:{hasAttribute:()=>true}}, {DroyMobile:{companionWorkspaceIds:()=>['buddy'],defaultWorkspaceId:()=> 'd'}}, droyWorkspaceKind,
      {user:0,default:1,ungrouped:2,companion:3}, ()=>rows, ()=>undefined, ()=>undefined);
  const result = derive({byId:{}},workspaces,{archivedSessionIds:[],pinnedSessionIds:[]},{},{expandedGroups:[]});
  assert.deepEqual(result.map(row=>row.key), ['second','first','d','','buddy']);
  assert.deepEqual(result.map(row=>row.droyWorkspaceKind), ['user','user','default','ungrouped','companion']);
});

test('work header exposes direct mode plus ordered conversation, resource and global settings actions', async () => {
  for (const language of ['zh','en']) {
    const actions=[], events=[];
    const ctx={layout:{panelInfo:{subscribe(){},getSnapshot:()=>({activePanelId:null})},toggleSidebar:()=>actions.push('conversations')},
      locale:{subscribe(){},getSnapshot:()=>({active:language})},sidebarRight:{toggleExpanded:()=>actions.push('resources')}};
    const doc={activeElement:{blur:()=>actions.push('blur')}};
    const target={CustomEvent:class {constructor(type,init){this.type=type;this.detail=init?.detail;}},dispatchEvent:event=>events.push(event)};
    const start=client.indexOf('      function WorkModeSwitch()'),end=client.indexOf("      ctx.slots.inject('shell.overlay'",start);
    const render=new Function('React','ctx','doc','target','bridge','controller','h','companionUi','DROY_COMPANION_COPY','IconNewChatOutlineRegular','IconPanelLeftOutlineRegular','IconSettingsOutlineMedium',
      `${client.slice(start,end)};return WorkModeSwitch;`)(
        {useSyncExternalStore:(_subscribe,snapshot)=>snapshot()},ctx,doc,target,{request:async action=>actions.push(action)},{report:error=>{throw error;}},jsx,
        {WorkModeSwitch:'mode'}, {zh:{conversationsSpaces:'对话与空间',resourcePanel:'资源面板',settings:'设置'},en:{conversationsSpaces:'Conversations and spaces',resourcePanel:'Resources',settings:'Settings'}},'chat-icon','panel-icon','settings-icon');
    const tree=render(),buttons=nodes(tree).filter(node=>node.type==='button');
    assert.equal(tree.props.className,'droy-work-header');
    assert.equal(buttons.length,3);
    assert.deepEqual(buttons.map(button=>Object.keys(button.props).find(key=>key.startsWith('data-droy-'))), ['data-droy-menu','data-droy-resource-toggle','data-droy-global-settings']);
    assert.ok(buttons.every(button=>button.props['aria-label']));
    for(const button of buttons)button.props.onClick();
    await Promise.resolve();
    assert.deepEqual(actions,['blur','hideKeyboard','conversations','blur','hideKeyboard','resources','blur','hideKeyboard']);
    assert.equal(events[0].type,'droy-mobile-open-settings');
    assert.equal(events[0].detail.section,'general');
  }
});

test('mobile conversation browser replaces sidebar chrome with its own back page header', () => {
  assert.doesNotThrow(()=>new Function(sidebar));
  assert.match(sidebar,/mobile \? \(0, react_jsx_runtime\.jsxs\)\("header",/);
  assert.match(sidebar,/"data-droy-drawer-close":true/);
  assert.match(sidebar,/children: renderSlot\("sidebar.settings"/);
  assert.doesNotMatch(sidebar,/children: mobile \? null : renderSlot\("sidebar.settings"/);
  assert.match(sidebar,/title:"对话与空间",back:"返回工作台"/);
  assert.match(sidebar,/title:"Conversations and spaces",back:"Back to workspace"/);
});
