// Real packaged DSH, local scripted inference and a no-Root native fixture.
import test from 'node:test';
import assert from 'node:assert/strict';
import {mkdtemp,rm,mkdir,writeFile} from 'node:fs/promises';
import {join} from 'node:path';
import {tmpdir} from 'node:os';
import {createServer} from 'node:http';
import {registerHooks} from 'node:module';
import {setTimeout as delay} from 'node:timers/promises';
import {fileURLToPath} from 'node:url';
import {dialoguePrompts} from '../companions/dialogue-prompts.mjs';
import {buildPlannerPrompt} from '../companions/maibot-prompts.mjs';
import {responseLanguagePrompts} from '../prompt.mjs';
import {seedLegacyCompanionDb} from './fixtures/legacy-companion-db.mjs';

async function eventually(read,label) {
  const deadline=Date.now()+10000;
  while(Date.now()<deadline){const value=await read();if(value)return value;await delay(20);}
  assert.fail('Timed out: '+label);
}
const token='fictional-companion-integration-token-123456789';
const textChunks=text=>[
  {type:'block-start',index:0,blockType:'text'},
  {type:'text-delta',index:0,text},
  {type:'block-end',index:0,block:{type:'text',text}},
  {type:'finish',reason:{kind:'stop'}},
];
test('restricted companions remain usable with persisted legacy protection and no Root', {timeout:60000},async t=>{
  const home=await mkdtemp(join(tmpdir(),'droy-companions-integration-'));
  await seedLegacyCompanionDb(join(home,'droy-companions'));
  const reservation=createServer();await new Promise(r=>reservation.listen(0,'127.0.0.1',r));
  const port=reservation.address().port;await new Promise(r=>reservation.close(r));
  const native=createServer(async(req,res)=>{
    assert.equal(req.headers.authorization,'Bearer '+token);
    let body=''; for await(const chunk of req)body+=chunk;
    const operation=body?JSON.parse(body).operation:undefined;
    if(req.url==='/memory'&&operation==='activation.prepare'){
      res.writeHead(409,{'content-type':'application/json'});
      res.end(JSON.stringify({ok:false,error:{code:'MEMORY_ROOT_REQUIRED',message:'No Root in this fixture'}}));return;
    }
    const result=req.url==='/memory'?{settings:{enabled:false,recallEnabled:true,automaticExtraction:false,privacyEverActivated:true},root:{authorized:false},execution:{ready:false,reason:'MEMORY_DISABLED'}}
      :req.url==='/history'?{config:{activityEnabled:false,locationEnabled:false},running:false}
      :req.url==='/notify'?{pluginActive:true,localEnabled:true,appsEnabled:false,channels:[]}
      :req.url==='/clock'?{now:Date.now(),timeZone:'Asia/Shanghai'}
      :req.url==='/companion'?{name:'Legacy fixture',displayName:'Legacy',appearanceId:'legacy',personality:''}:{};
    res.writeHead(200,{'content-type':'application/json'});res.end(JSON.stringify({ok:true,result}));
  });
  await new Promise(r=>native.listen(0,'127.0.0.1',r));
  const runtime=fileURLToPath(new URL('../../generated/dsh/',import.meta.url));
  const values={DSH_HOME:home,DROY_RUNTIME_DIR:runtime,DROY_LOCALE:'zh',DROY_WEB_TOKEN:token,DROY_BRIDGE_TOKEN:token,
    DROY_BRIDGE_PORT:String(native.address().port),DROY_WEB_PORT:String(port),DSH_TELEMETRY_DISABLED:'1',DROY_MEMORY_PROTECTION_REQUIRED:'1'};
  const previous=Object.fromEntries(Object.keys(values).map(key=>[key,process.env[key]]));Object.assign(process.env,values);
  const hooks=registerHooks({resolve(specifier,context,next){
    if(specifier==='@deepseek-ai/node-addon-system/flock')return {url:new URL('../../dsh/node_modules/@deepseek-ai/node-addon-system/lib/flock.js',import.meta.url).href,shortCircuit:true};
    if(specifier==='@droyagent/mobile/publish')return {url:new URL('./fixtures/host-publish.mjs',import.meta.url).href,shortCircuit:true};
    return next(specifier,context);
  }});
  let running;
  t.after(async()=>{
    try{await running?.shutdown.shutdown(0);}
    finally{
      hooks.deregister();native.closeAllConnections();await new Promise(r=>native.close(r));
      for(const [key,value]of Object.entries(previous)){if(value===undefined)delete process.env[key];else process.env[key]=value;}
      await rm(home,{recursive:true,force:true});
    }
  });
  await import(new URL('../../generated/dsh/mobile/bootstrap.mjs',import.meta.url));
  const {runProfile}=await import(new URL('../../generated/dsh/node_modules/@deepseek-ai/dsh/lib/profile-boot.js',import.meta.url));
  const {createLaunchEnvironmentSnapshot}=await import(new URL('../../generated/dsh/node_modules/@deepseek-ai/dsh-launch-environment/lib/index.js',import.meta.url));
  const {mobilePackageManager}=await import(new URL('../../generated/dsh/mobile/package-manager.mjs',import.meta.url));
  const pm=mobilePackageManager();pm.env={...pm.env,npm_config_offline:'true',CI:'true'};
  running=await runProfile({profile:'mobile',patchFiles:[],args:['--no-open'],packageManager:pm,
    environment:createLaunchEnvironmentSnapshot([{source:'process',values:process.env}])});
  const ctx=running.ctx;
  let companions;
  try { companions=await eventually(()=>ctx.get('droyCompanions'),'companion service activation'); }
  catch(error) {
    error.message+=' '+JSON.stringify({services:Object.fromEntries(['sessionController','workspaceRegistry','agentDefaultModel','sessionPersistence','attachments','llm','droyInitialization','droyPrivacy'].map(name=>[name,!!ctx.get(name)])),
      pending:ctx.loader.getTasks().length,errors:[...ctx.registry.values()].flatMap(runtime=>[...runtime.fibers]).filter(fiber=>fiber._error).map(fiber=>({name:fiber.name,error:String(fiber._error),stack:fiber._error?.stack})),entries:[...ctx.loader.entries()].filter(entry=>entry.options.name.startsWith('@droyagent')).map(entry=>({name:entry.options.name,state:entry.fiber?.state,disabled:entry.disabled}))});throw error;
  }
  const readiness=await fetch('http://127.0.0.1:'+port+'/droy/ready',{headers:{cookie:'droy_session='+token}});
  assert.equal(readiness.status,200,await readiness.text());
  const {LlmAdapter}=await import(new URL('../../generated/dsh/node_modules/@deepseek-ai/dsh-llm/lib/index.js',import.meta.url));
  const privacy=ctx.get('droyPrivacy');
  assert.equal((await privacy.status()).active,true);
  assert.equal((await privacy.status()).ready,false);
  const calls=[],errors=[],steps=new Map();ctx.on('agent/error',event=>errors.push(event.error.stack??String(event.error)));
  class LocalModel extends LlmAdapter {
    async resolveModel(provider,id){return {provider,id,name:'Restricted companion fixture',inputModalities:['text'],context:{contextWindow:65536},defaultMaxTokens:2048};}
    async listModels(provider){return [await this.resolveModel(provider,'kaka'),await this.resolveModel(provider,'maimai')];}
    async *stream(options){
      calls.push(options);
      if(options.tools?.some(tool=>tool.name==='companion_reply')){
        assert.ok(!options.tools.some(tool=>['bash','run_code','android_tap','read','droy_load_tools'].includes(tool.name)));
        const step=steps.get(options.sessionId)??0;steps.set(options.sessionId,step+1);
        assert.ok(step<8,'Restricted fixture exceeded its planned actions: '+JSON.stringify(options.messages.filter(message=>message.role==='tool').slice(-2)));
        const owner=companions.owners.get(options.sessionId);
        const name=owner.kind==='work'||step===2?'companion_reply':step===0?'companion_remember':step===1?'companion_recall':'companion_quiet';
        const args=JSON.stringify(name==='companion_remember'?{text:'Fictional '+options.model+' fact',sourceIds:['source-'+options.model]}:
          name==='companion_recall'?{query:'Fictional'}:name==='companion_reply'?{msg_id:owner.kind==='work'?'':'source-'+options.model,reply_reference:'Respond naturally using the actual result.'}:{});
        const id='call-'+calls.length;
        yield {type:'block-start',index:0,blockType:'tool-call'};
        yield {type:'tool-call-delta',index:0,id,name,argumentsDelta:args};
        yield {type:'block-end',index:0,block:{type:'tool-call',id,name,arguments:args}};
        yield {type:'finish',reason:{kind:'tool-calls'}};
      }else {
        let input;try{input=JSON.parse(options.messages.filter(item=>item.role==='user').at(-1)?.content.filter(block=>block.type==='text').map(block=>block.text).join(''));}catch{}
        yield* textChunks(input?.candidates?JSON.stringify({recordIds:input.candidates.slice(0,input.limit).map(record=>record.recordId)}):options.model+' scoped reply.');
      }
    }
  }
  ctx.llm.registerAdapter(['legacy-privacy-fixture'],new LocalModel());
  companions.options.maibot={typo:{enabled:false},random:()=>0.99};
  for(const id of ['kaka','maimai']){
    await companions.store.updateProfile(id,{model:{provider:'legacy-privacy-fixture',model:id}});
    await companions.submit(id,{clientId:'source-'+id,text:'Remember my fictional '+id+' fact.'});
    await eventually(async()=>{
      const state=companions.state(id);
      assert.ok(!state.error,JSON.stringify({state:{phase:state.phase,error:state.error},errors,calls:calls.map(call=>({sessionId:call.sessionId,tools:call.tools?.map(tool=>tool.name)}))}));
      assert.deepEqual(errors,[]);
      return (await companions.store.listMessages(id)).messages.some(row=>row.role==='assistant');
    },'restricted '+id+' reply');
    const records=(await (await companions.memory(id)).query()).items;
    assert.equal(records.length,1);assert.match(JSON.stringify(records),new RegExp('Fictional '+id+' fact'));
    assert.ok(!JSON.stringify(records).includes('Fictional '+(id==='kaka'?'maimai':'kaka')+' fact'));
    const agent=await companions.agent(id);
    const descriptions={companion_reply:'replyDescription',companion_wait:'waitDescription',companion_quiet:'stopDescription',
      companion_work:'workDescription',companion_recall:'recallDescription',companion_remember:'rememberDescription',companion_forget:'forgetDescription',
      companion_attachment:'attachmentDescription',web_search:'searchDescription',web_fetch:'fetchDescription'};
    for(const language of ['zh','en','zh']) {
      await ctx.settings.mutate('locale',[{op:'set',path:['preference'],value:language}]);
      const assembly=await ctx.systemPrompt.assemble({agent,scope:agent});
      assert.deepEqual(assembly.sections.map(row=>row.name),['droy:companion-planner','droy:response-language']);
      assert.deepEqual(assembly.contexts.map(row=>row.name),['droy:shared-conversation']);
      assert.equal(assembly.sections[0].text,buildPlannerPrompt({profile:await companions.store.getProfile(id),locale:language,
        options:companions.options.maibot,availableTools:assembly.tools.map(tool=>tool.name)}));
      assert.equal(assembly.sections[1].text,responseLanguagePrompts[language]);
      for(const [name,key] of Object.entries(descriptions))
        assert.equal(assembly.tools.find(tool=>tool.name===name)?.description,dialoguePrompts[language][key],id+' '+language+' '+name);
      assert.ok(!JSON.stringify(assembly.contexts).includes('Fictional '+(id==='kaka'?'maimai':'kaka')+' fact'));
    }
    assert.equal(companions.isRestrictedAgent(agent),true);
    assert.equal(companions.isRestrictedSession(agent.id),true);
    const denied=await agent.ctx.tools.execute({agent,name:'bash',arguments:{command:'echo forbidden'},callId:'forbidden-'+id,signal:new AbortController().signal});
    assert.equal(denied.isError,true);
    assert.match(JSON.stringify(denied),/UNKNOWN_TOOL|COMPANION_TOOL_NOT_ALLOWED/);
  }
  assert.deepEqual(errors,[]);
  // The background work scope must receive the same restriction and bypass.
  const task=await companions.startWork('kaka',{title:'Public information summary',goal:'Summarize the information already provided.'});
  try { await eventually(async()=>{
    const value=(await companions.store.getMessage('kaka',task.taskId))?.task;
    assert.notEqual(value?.state,'failed',JSON.stringify({task:value,errors,calls:calls.filter(call=>call.sessionId===task.sessionId).map(call=>({tools:call.tools?.map(tool=>tool.name),messages:call.messages}))}));
    return value?.state==='completed';
  },'restricted companion work'); }catch(error){error.message+=' '+JSON.stringify({state:companions.state('kaka').error,task:await companions.store.getMessage('kaka',task.taskId),errors,
    calls:calls.filter(call=>call.sessionId===task.sessionId).slice(-3).map(call=>({tools:call.tools?.map(tool=>tool.name),messages:call.messages.filter(message=>message.role==='tool').slice(-2)}))});throw error;}
  assert.equal(companions.isRestrictedSession(task.sessionId),true);
  assert.ok(calls.some(call=>call.sessionId===task.sessionId));
  assert.deepEqual(errors,[]);
  // Global legacy state remains active/unready. An unowned work dispatch still
  // fails before reaching the adapter, even with the same provider and model.
  const before=calls.length,chunks=[];
  for await(const chunk of ctx.llm.stream({provider:'legacy-privacy-fixture',model:'kaka',sessionId:'legacy-protected-work',
    messages:[{role:'user',content:[{type:'text',text:'Legacy work must remain protected.'}]}]}))chunks.push(chunk);
  assert.equal(chunks.at(-1).reason.kind,'error');
  assert.match(JSON.stringify(chunks.at(-1)),/PRIVACY_NOT_INITIALIZED/);
  assert.equal(calls.length,before);
  assert.equal((await privacy.status()).active,true);assert.equal((await privacy.status()).ready,false);
  const profile=await companions.store.getProfile('maimai');
  await companions.resetChat('maimai');
  assert.equal(companions.ownsSession(profile.chatSessionId),true);
  assert.equal(companions.isRestrictedSession(profile.chatSessionId),false);
  if(process.env.DROY_COMPANION_TEST_EVIDENCE){
    const directory=process.env.DROY_COMPANION_TEST_EVIDENCE;await mkdir(directory,{recursive:true});
    await writeFile(join(directory,'packaged-legacy-privacy-receipts.json'),JSON.stringify({generatedAt:new Date().toISOString(),
      kind:'scripted-model-real-dsh-loop-legacy-protection',errors,
      calls:calls.map(({provider,model,sessionId,system,messages,tools})=>({provider,model,sessionId,system,messages,tools})),
      work:await companions.store.getMessage('kaka',task.taskId)},null,2));
  }
});
