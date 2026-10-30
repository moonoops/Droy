import { toolDefinitions } from '../catalog.mjs';
import assert from 'node:assert/strict';
import { mkdtemp, rm, readFile, realpath } from 'node:fs/promises';
import { join } from 'node:path';
import { tmpdir } from 'node:os';
import { fileURLToPath } from 'node:url';
import { createServer } from 'node:http';
import { spawnSync } from 'node:child_process';
import { persona, defaultCompanionPersona, mobilePersonas, companionPersonas } from '../profile.mjs';
import { responseLanguagePrompts } from '../prompt.mjs';
import { companionState } from './fixtures/companion-state.mjs';
import { COMPANION_CONTEXT } from '../companion.mjs';
const { TOOL_GROUPS } = await import(new URL('../../generated/dsh/mobile/tool-groups.mjs', import.meta.url));
const runtime = fileURLToPath(new URL('../../generated/dsh/',import.meta.url));
const expectedPresets=['cordis','standard'];
const expectedDsh=JSON.parse(await readFile(new URL('../../dsh/package.json',import.meta.url),'utf8')).dependencies['@deepseek-ai/dsh'];
const expectedMobile=JSON.parse(await readFile(new URL('../package.json',import.meta.url),'utf8')).version;
const home=await mkdtemp(join(tmpdir(),'droy-profile-'));
const token='fictional-smoke-test-token-1234567890';
const reservation=createServer();await new Promise(r=>reservation.listen(0,'127.0.0.1',r));const port=reservation.address().port;await new Promise(r=>reservation.close(r));
const nativeCalls=[];
const nativeState={pluginActive:false,messagesEnabled:true,tasksEnabled:true,channels:[{id:'system',available:true},{id:'pet',available:false}]};
const nativeBridge=createServer(async(req,res)=>{
 assert.equal(req.headers.authorization,`Bearer ${token}`);
 let body='';for await(const chunk of req)body+=chunk;
 const args=JSON.parse(body);nativeCalls.push({path:req.url,...args});
 if(req.url==='/notify'&&args.operation==='activate')nativeState.pluginActive=args.enabled;
 const memoryState={settings:{enabled:false,recallEnabled:true,automaticExtraction:false,privacyEverActivated:false},
   root:{authorized:false,checkedAt:0},execution:{ready:false,reason:'MEMORY_DISABLED'}};
 const historyState={config:{activityEnabled:false,locationEnabled:false},running:false};
 res.writeHead(200,{'content-type':'application/json'});res.end(JSON.stringify({ok:true,result:req.url==='/memory'?memoryState:req.url==='/history'?historyState:req.url==='/clock'?{now:Date.now(),timeZone:'Asia/Shanghai'}:req.url==='/companion'?companionState:nativeState}));
});
await new Promise(resolve=>nativeBridge.listen(0,'127.0.0.1',resolve));
Object.assign(process.env,{DSH_HOME:home,DROY_RUNTIME_DIR:runtime,DROY_LOCALE:'zh',DROY_WEB_TOKEN:token,DROY_BRIDGE_TOKEN:token,DROY_BRIDGE_PORT:String(nativeBridge.address().port),DROY_WEB_PORT:String(port),DSH_TELEMETRY_DISABLED:'1'});
await import(new URL('../../generated/dsh/mobile/bootstrap.mjs',import.meta.url));
const {runProfile}=await import(new URL('../../generated/dsh/node_modules/@deepseek-ai/dsh/lib/profile-boot.js',import.meta.url));
const {createLaunchEnvironmentSnapshot}=await import(new URL('../../generated/dsh/node_modules/@deepseek-ai/dsh-launch-environment/lib/index.js',import.meta.url));
const { mobilePackageManager }=await import(new URL('../../generated/dsh/mobile/package-manager.mjs',import.meta.url));
const {ctx,shutdown}=await runProfile({profile:'mobile',patchFiles:[],args:['--no-open'],packageManager:mobilePackageManager(),environment:createLaunchEnvironmentSnapshot([{source:'process',values:process.env}])});
try {
 const tools=ctx.tools.schemas().map(x=>x.name).sort();
 for(const tool of toolDefinitions)assert.ok(tools.includes(tool.name),tool.name);
 for(const name of ['droy_notification_channels','droy_notify'])assert.ok(tools.includes(name),`${name} must be registered by the independent notification plugin`);
 assert.ok(tools.includes('droy_voice'),'voice tools must activate against the bound DSH schema compiler');
 assert.ok(ctx.get('droyVoice'),'voice service must be active, not merely present in the client roster');
 assert.ok(ctx.get('droyPrivacy'),'mandatory privacy service must be mounted');
 assert.equal(ctx.get('droyExecution')?.installed(),true,'both replacement providers and tool middleware must be mounted');
 assert.ok(ctx.get('droyMemory'),'memory plugin must mount while its collection switch remains disabled');
 assert.deepEqual(await ctx.get('droyPrivacy').status(),{active:false,ready:false,reason:'MEMORY_DISABLED'});
 const privacyEntry=[...ctx.loader.entries()].find(entry=>entry.options.id==='droy-privacy');
 const executionEntry=[...ctx.loader.entries()].find(entry=>entry.options.id==='droy-execution');
 assert.ok(privacyEntry&&executionEntry);
 await assert.rejects(privacyEntry.update({disabled:true}),/DROY_REQUIRED_PROTECTION_PLUGIN/);
 assert.throws(()=>executionEntry.parent.remove(executionEntry.options.id),/DROY_REQUIRED_PROTECTION_PLUGIN/);
 const protectedRow=(await ctx.pluginManager.listPlugins()).find(row=>row.moduleName==='@droyagent/privacy');
 assert.ok(protectedRow,'privacy entry must be visible to the actual plugin manager');
 const profilePatch=join(home,'profiles/mobile/cordis.patch.yml');
 const originalPatch=await readFile(profilePatch,'utf8');
 const refused=await ctx.pluginManager.setPluginEnabled(protectedRow.entryId,false);
 assert.match(JSON.stringify(refused),/DROY_REQUIRED_PROTECTION_PLUGIN/);
 assert.equal(await readFile(profilePatch,'utf8'),originalPatch,'refused enablement must not change profile');
 assert.ok(ctx.get('droyPrivacy'),'plugin-manager refusal preserves the mounted guard');
 assert.equal((await ctx.get('droyNotifications').channels()).pluginActive,true);
 const presets=await ctx.agentPresets.list();
 assert.deepEqual(presets.map(x=>x.id).sort(),expectedPresets);
 for(const preset of presets)assert.equal(preset.broken,undefined,`${preset.id}: ${preset.broken}`);
 assert.equal(ctx.agentPresets.defaultId,'standard');
 for(const service of ['pluginManager','ptcRuntime','schedule','skills','permissionPresets','sessionController','terminalController','speechToText'])assert.ok(ctx.get(service),`${service} must be active`);
 assert.equal(ctx.get('productAnalytics'),undefined,'upstream desktop analytics must stay inactive in the mobile profile');
 assert.equal(ctx.get('productTelemetry'),undefined,'the desktop analytics sender must stay inactive in the mobile profile');
 assert.ok(ctx.speechToText.listProviders().some(provider=>provider.id==='droy-sensevoice-local'),'Android native speech provider must be registered in the actual profile');
 assert.ok([...ctx.registry.values()].some(runtime=>[...runtime.fibers].some(fiber=>fiber.entry?.options?.name==='@deepseek-ai/dsh-experimental-client-ui-voice-input'&&!fiber.entry.disabled)),'DSH voice plugin must be enabled in the actual profile');
 assert.ok(ctx.get('sessionController'),'chat controller must be active');
 assert.ok(ctx.get('droyInitialization')?.ready);assert.ok(ctx.get('droyLifecycle'));
 const companionDeadline=Date.now()+5000;while(!ctx.get('droyCompanions')&&Date.now()<companionDeadline)await new Promise(resolve=>setTimeout(resolve,10));
 assert.ok(ctx.get('droyCompanions'),'non-Root companion service must activate');
 const defaultPath=await realpath(join(home,'workspace'));
 const initial=ctx.workspaceRegistry.list().filter(workspace=>workspace.path===defaultPath);assert.equal(initial.length,1);
 assert.equal(ctx.workspaceRegistry.list().length,1,'fresh companions do not create workspaces before use');
 const initialCompanions=await ctx.get('droyCompanions').store.profiles();
 assert.equal(initialCompanions.length,1,'fresh installations contain one default companion');
 assert.equal(initialCompanions[0].templateId,'kaka');
 assert.match(initialCompanions[0].id,/^companion-[0-9a-f-]{36}$/);
 assert.equal(initialCompanions[0].workspaceId,null);
 assert.equal(initialCompanions[0].chatSessionId,null);
 assert.equal(initial[0].title,'默认空间');
 assert.equal(ctx.get('droyInitialization').defaultWorkspaceId,initial[0].id,'mobile initialization exposes the durable default identity');
 const same=await ctx.workspaceRegistry.initializeDefault(async()=>{throw new Error('Must reuse existing default');});assert.equal(same.id,initial[0].id);
 for(const agentPreset of expectedPresets){
  const created=await ctx.sessionController.create({workspaceId:initial[0].id,agentPreset});
  const agent=ctx.agents.get(created.sessionId);assert.ok(agent);
  assert.ok(ctx.get('droyPluginPolicy'),'phone tools require the prohibition policy');
  assert.equal(ctx.agentPresets.serviceFor(agent,'toolResultPruner'),undefined,`${agentPreset}: head/tail pruning must never activate`);
  const compaction=ctx.agentPresets.serviceFor(agent,'compaction');
  if(compaction){
    assert.equal(compaction.constructor.name,'BasicCompactionEngine',`${agentPreset}: native DSH engine`);
    assert.equal(compaction.config.auto,true);
    assert.equal(compaction.config.thresholdRatio,0.8);
    assert.equal(compaction.config.retainRatio,0.16);
    assert.equal(compaction.config.retainTokens,undefined);
    assert.equal(compaction.config.headroomTokens,65536);
    assert.equal(compaction.config.maxTokens,65536);
    assert.equal(compaction.config.compactionRetries,1);
    assert.equal(compaction.config.maxOverflowRetries,1);
    assert.deepEqual(compaction.config.modelPolicies,[],`${agentPreset}: native defaults without Droy route policies`);
    console.log(JSON.stringify({check:'native-compaction-defaults',agentPreset,config:compaction.config}));
  }
  const assembled=await ctx.systemPrompt.assemble({scope:agent,agent});
  const prompt=assembled.sections.map(section=>section.text).join('\n\n');
  assert.equal(prompt.split(persona).length-1,1,`${agentPreset}: final packaged assembly must include Android defaults once`);
  assert.equal(prompt.split(defaultCompanionPersona).length-1,1,`${agentPreset}: default companion personality must be included once`);
  assert.ok(!assembled.contexts.find(context=>context.name===COMPANION_CONTEXT)?.text,`${agentPreset}: default work must remain neutral without a companion identity`);
  assert.ok(!prompt.includes('You are a coding agent'),`${agentPreset}: stale default coding identity`);
  const baseTools=assembled.tools.map(tool=>tool.name);
  if(agentPreset==='standard'){
    assert.ok(!assembled.sections.some(section=>['tool:bash','tool:goal','team:policy','app:web-surface'].includes(section.name)));
    const schemaChars=JSON.stringify(assembled.tools).length;
    const contextChars=assembled.contexts.reduce((total,entry)=>total+(entry.text?.length??0),0);
    console.log(JSON.stringify({check:'mobile-first-request-shape',tools:baseTools.length,schemaChars,systemChars:prompt.length,contextChars,
      estimatedTokens:Math.ceil((schemaChars+prompt.length+contextChars)/4),estimator:'DSH chars/4 heuristic; not provider usage'}));
  }
  assert.ok(baseTools.includes('droy_load_tools'),`${agentPreset}: expansion is reachable`);
  assert.ok(!baseTools.includes('android_memory_write'),`${agentPreset}: heavy schema starts unloaded`);
  for(const group of Object.keys(TOOL_GROUPS))await ctx.tools.get('droy_load_tools',agent).execute({group},{agent});
  const expanded=await ctx.systemPrompt.assemble({agent,scope:agent});
  assert.ok(expanded.sections.some(section=>section.name==='tool:bash'));
  assert.ok(expanded.sections.some(section=>section.name==='tool:goal'));
  for (const language of ['en', 'zh']) {
    await ctx.settings.mutate('locale', [{op:'set', path:['preference'], value:language}]);
    const localized = await ctx.systemPrompt.assemble({agent, scope:agent});
    const text = localized.sections.map(section => section.text).join('\n\n');
    assert.ok(text.includes(mobilePersonas[language]), `${agentPreset}: current product template ${language}`);
    assert.ok(text.includes(companionPersonas[language]), `${agentPreset}: current companion template ${language}`);
    assert.ok(text.includes(responseLanguagePrompts[language]), `${agentPreset}: current response language ${language}`);
    assert.ok(!text.includes(mobilePersonas[language === 'en' ? 'zh' : 'en']), 'no stale built-in product template');
    const manual = localized.contexts.find(context => context.name === 'droy:loaded-tool-instructions')?.text;
    assert.ok(manual?.includes(language === 'en' ? 'Choose the sound source' : '涉及声音时按任务选择音源'), 'already loaded manuals follow the current setting');
    if (language === 'en') {
      const checkDescriptions = value => {
        if (Array.isArray(value)) value.forEach(checkDescriptions);
        else if (value && typeof value === 'object') for (const [key, item] of Object.entries(value)) {
          if (key === 'description' && typeof item === 'string') assert.ok(!/\p{Script=Han}/u.test(item), `English owned tool description missing: ${item}`);
          else checkDescriptions(item);
        }
      };
      checkDescriptions(localized.tools.filter(tool => toolDefinitions.some(owned => owned.name === tool.name)
        || ['droy_load_tools','droy_pet_progress','droy_voice','droy_notification_rules','droy_notification_channels','droy_notify','droy_skill_save'].includes(tool.name)));
    }
  }
  const effectiveTools=expanded.tools.map(tool=>tool.name);
  for(const name of ['bash','read','write','edit','skill','schedule_create','schedule_list','schedule_update','schedule_delete','web_search','web_fetch'])assert.ok(effectiveTools.includes(name),`${agentPreset}: ${name}`);
  for(const tool of toolDefinitions)assert.ok(effectiveTools.includes(tool.name),`${agentPreset}: ${tool.name}`);
  const commands=ctx.commands.list(agent).map(command=>command.name);
  for(const name of ['goal','plan','compact','permission','feedback','export'])assert.ok(commands.includes(name),`${agentPreset}: /${name}`);
  if(agentPreset==='standard'){
    const restore=agent.ctx.tools.presentAs('ptc');
    try { assert.deepEqual((await ctx.systemPrompt.assemble({scope:agent,agent})).tools.map(tool=>tool.name),['run_code'],'PTC retains its existing invocation transport'); }
    finally { restore(); }
  }
 }
 const locale=ctx.settings.describe().find(item=>item.ns==='locale');assert.ok(locale,'locale settings must exist');
 assert.match(JSON.stringify(locale),/"preference":"zh"/);
 for(const path of ['/','/api','/droy/preferences','/droy/ready','/droy/shutdown','/droy/notify/plugin','/assets/unknown.js'])assert.equal((await fetch(`http://127.0.0.1:${port}${path}`)).status,401);
 const cookie=`droy_session=${token}`;
 const nativeDeadline=Date.now()+5000;while(!ctx.get('droyNativeApi')&&Date.now()<nativeDeadline)await new Promise(resolve=>setTimeout(resolve,10));
 assert.ok(ctx.get('droyNativeApi'),'native workbench API must mount against the actual Host services');
 for(const path of ['/droy/native/models','/droy/native/workspaces','/droy/native/session/list'])
  assert.equal((await fetch(`http://127.0.0.1:${port}${path}`)).status,401,'native routes retain the Host authentication guard');
 const nativeModels=await fetch(`http://127.0.0.1:${port}/droy/native/models`,{headers:{cookie}});
 assert.equal(nativeModels.status,200);const nativeCatalog=await nativeModels.json();assert.equal(nativeCatalog.ok,true);
 assert.ok(Array.isArray(nativeCatalog.result.groups));assert.ok(Array.isArray(nativeCatalog.result.routableProviders));assert.ok(Array.isArray(nativeCatalog.result.failures));
 assert.equal(typeof nativeCatalog.result.default.provider,'string');assert.equal(typeof nativeCatalog.result.default.model,'string');
 const nativeWorkspaces=await fetch(`http://127.0.0.1:${port}/droy/native/workspaces`,{headers:{cookie}});
 assert.equal(nativeWorkspaces.status,200);const nativeWorkspaceState=await nativeWorkspaces.json();assert.equal(nativeWorkspaceState.ok,true);
 assert.equal(nativeWorkspaceState.result.defaultWorkspaceId,initial[0].id);
 assert.ok(nativeWorkspaceState.result.items.some(item=>item.workspaceId===initial[0].id&&item.path===defaultPath));
 assert.ok(Array.isArray(nativeWorkspaceState.result.archivedSessionIds));assert.ok(Array.isArray(nativeWorkspaceState.result.pinnedSessionIds));
 const nativeSessions=await fetch(`http://127.0.0.1:${port}/droy/native/session/list`,{method:'POST',headers:{cookie,'content-type':'application/json'},body:'{}'});
 assert.equal(nativeSessions.status,200);const nativeSessionState=await nativeSessions.json();assert.equal(nativeSessionState.ok,true);assert.ok(Array.isArray(nativeSessionState.result.items));
 for(const session of nativeSessionState.result.items){assert.equal(typeof session.sessionId,'string');assert.equal(typeof session.running,'boolean');assert.equal(typeof session.agentAvailable,'boolean');}
 const companionRoot=await fetch(`http://127.0.0.1:${port}/droy/companions`,{headers:{cookie}});assert.equal(companionRoot.status,200);assert.equal((await companionRoot.json()).defaultWorkspaceId,initial[0].id);
 const ready=await fetch(`http://127.0.0.1:${port}/droy/ready`,{headers:{cookie}});assert.equal(ready.status,200);const readiness=await ready.json();assert.equal(readiness.ready,true);assert.equal(readiness.dshVersion,expectedDsh);assert.equal(readiness.mobileVersion,expectedMobile);
 const login=await fetch(`http://127.0.0.1:${port}/?token=${token}`,{headers:{cookie},redirect:'manual'});
 assert.equal(login.status,303);assert.ok(login.headers.get('set-cookie').includes('HttpOnly'));
 const authenticated={cookie:`${cookie}; ${login.headers.get('set-cookie').split(';')[0]}`};
 const notificationControl=async(enabled)=>{
  const response=await fetch(`http://127.0.0.1:${port}/droy/notify/plugin`,{headers:{...authenticated,'content-type':'application/json'},
   ...(enabled===undefined?{}:{method:'POST',body:JSON.stringify({enabled})})});
  assert.equal(response.status,200);return response.json();
 };
 nativeState.tasksEnabled=false;
 const disabled=await notificationControl(false);assert.equal(disabled.application,'applied');assert.equal(disabled.enabled,false);
 assert.equal(ctx.get('droyNotifications'),undefined);assert.ok(!ctx.tools.schemas().some(tool=>tool.name==='droy_notify'));
 assert.ok(ctx.tools.schemas().some(tool=>tool.name==='android_observe'),'disabling notification plugin retains Android Use');
 assert.equal((await notificationControl()).enabled,false,'host enablement route remains reachable while notification plugin is unloaded');
 const disabledPatch=await readFile(join(home,'profiles/mobile/cordis.patch.yml'),'utf8');
 const disabledBoot=spawnSync(process.execPath,[join(runtime,'mobile/bootstrap.mjs')],{env:process.env});assert.equal(disabledBoot.status,0);
 assert.equal(await readFile(join(home,'profiles/mobile/cordis.patch.yml'),'utf8'),disabledPatch,'bootstrap preserves disabled plugin choice');
 const enabled=await notificationControl(true);assert.equal(enabled.application,'applied');assert.equal(enabled.enabled,true);
 assert.equal((await ctx.get('droyNotifications').channels()).tasksEnabled,false,'plugin reload preserves Android-owned task notification preferences');
 assert.ok(nativeCalls.some(call=>call.operation==='activate'&&call.enabled===false));
 const page=await fetch(`http://127.0.0.1:${port}/`,{headers:authenticated});assert.equal(page.status,200);
 const html=await page.text();assert.ok(html.includes('dsh-client-ui-chat'));assert.ok(html.includes('dsh-client-ui-attachment'),'reviewed attachment presentation must be in the client roster');assert.ok(html.includes('dsh-client-ui-workspace'));assert.ok(html.includes('dsh-client-ui-sidebar-right'));for(const name of ['plugin-manager','schedule','sidebar-terminal','sidebar-browser','skill','goal','plan','permission-presets','reference','approval'])assert.ok(html.includes(`dsh-client-ui-${name}`),`${name} must be in the client roster`);
 assert.ok(html.includes('@droyagent/media-input'),'independent media plugin must be in the actual client roster');
 assert.ok(html.includes('@droyagent/voice'),'independent voice plugin must be in the actual client roster');
 assert.equal((await fetch(`http://127.0.0.1:${port}/droy/media-input/00000000-0000-0000-0000-000000000001/00000000-0000-0000-0000-000000000002`,{headers:authenticated})).status,404,'media host route must reject missing native objects');
 const theme=await fetch(`http://127.0.0.1:${port}/droy/preferences`,{method:'POST',headers:{...authenticated,'content-type':'application/json'},body:JSON.stringify({theme:'dark'})});assert.equal(theme.status,200);
 assert.ok((await readFile(join(home,'profiles/mobile/cordis.patch.yml'),'utf8')).includes('dark'));
 await ctx.settings.mutate('locale',[{op:'set',path:['preference'],value:'en'}]);
 const userPatch=await readFile(join(home,'profiles/mobile/cordis.patch.yml'),'utf8');
 const bootstrap=spawnSync(process.execPath,[join(runtime,'mobile/bootstrap.mjs')],{env:process.env});assert.equal(bootstrap.status,0);
 assert.equal(await readFile(join(home,'profiles/mobile/cordis.patch.yml'),'utf8'),userPatch,'bootstrap must preserve user settings byte for byte');
 const badOrigin=await fetch(`http://127.0.0.1:${port}/`,{headers:{...authenticated,origin:'https://example.invalid'}});assert.equal(badOrigin.status,401);
 await ctx.workspaceRegistry.delete(initial[0].id);
 assert.equal(await ctx.workspaceRegistry.initializeDefault(async()=>{throw new Error('Deleted default must not recreate');}),undefined);
 console.log('PASS: standard and creator modes with Android prompt assembly, default space, plugin/automation/skill/terminal services, full client roster, HTTP guard and persisted settings');
} finally {await shutdown.shutdown(0);nativeBridge.closeAllConnections();await new Promise(resolve=>nativeBridge.close(resolve));await rm(home,{recursive:true,force:true});}
