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
const toolChunks=(id,name,input={})=>{
  const args=JSON.stringify(input);
  return [{type:'block-start',index:0,blockType:'tool-call'},
    {type:'tool-call-delta',index:0,id,name,argumentsDelta:args},
    {type:'block-end',index:0,block:{type:'tool-call',id,name,arguments:args}},
    {type:'finish',reason:{kind:'tool-calls'}}];
};
const contentText=message=>(message.content??[]).filter(block=>block.type==='text').map(block=>block.text).join('\n');
test('packaged companions run isolated Planner and Replyer conversations without Root', {timeout:60000},async t=>{
  const home=await mkdtemp(join(tmpdir(),'droy-companions-integration-'));
  const reservation=createServer();await new Promise(r=>reservation.listen(0,'127.0.0.1',r));
  const port=reservation.address().port;await new Promise(r=>reservation.close(r));
  const native=createServer(async(req,res)=>{
    assert.equal(req.headers.authorization,'Bearer '+token);
    for await(const _ of req){}
    const result=req.url==='/memory'?{settings:{enabled:false,recallEnabled:true,automaticExtraction:false,privacyEverActivated:false},root:{authorized:false},execution:{ready:false,reason:'MEMORY_DISABLED'}}
      :req.url==='/history'?{config:{activityEnabled:false,locationEnabled:false},running:false}
      :req.url==='/notify'?{pluginActive:true,localEnabled:true,appsEnabled:false,channels:[]}
      :req.url==='/clock'?{now:Date.now(),timeZone:'Asia/Shanghai'}
      :req.url==='/companion'?{name:'Legacy fixture',displayName:'Legacy',appearanceId:'legacy',personality:''}:{};
    res.writeHead(200,{'content-type':'application/json'});res.end(JSON.stringify({ok:true,result}));
  });
  await new Promise(r=>native.listen(0,'127.0.0.1',r));
  const runtime=fileURLToPath(new URL('../../generated/dsh/',import.meta.url));
  const values={DSH_HOME:home,DROY_RUNTIME_DIR:runtime,DROY_LOCALE:'zh',DROY_WEB_TOKEN:token,DROY_BRIDGE_TOKEN:token,
    DROY_BRIDGE_PORT:String(native.address().port),DROY_WEB_PORT:String(port),DSH_TELEMETRY_DISABLED:'1'};
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
  const companions=await eventually(()=>ctx.get('droyCompanions'),'companion service activation');
  const readiness=await fetch('http://127.0.0.1:'+port+'/droy/ready',{headers:{cookie:'droy_session='+token}});
  assert.equal(readiness.status,200,await readiness.text());
  const {LlmAdapter}=await import(new URL('../../generated/dsh/node_modules/@deepseek-ai/dsh-llm/lib/index.js',import.meta.url));
  const calls=[],errors=[],scripts=new Map(),actions=[];ctx.on('agent/error',event=>errors.push(String(event.error)));
  class LocalModel extends LlmAdapter {
    async resolveModel(provider,id){return {provider,id,name:'Local companion fixture',inputModalities:['text','image'],context:{contextWindow:65536},defaultMaxTokens:2048};}
    async listModels(provider){return [await this.resolveModel(provider,'kaka-model'),await this.resolveModel(provider,'maimai-model')];}
    async *stream(options){
      calls.push(options);
      if(options.tools?.some(tool=>tool.name==='companion_reply')){
        assert.ok(!options.tools.some(tool=>['bash','android_tap','read','droy_load_tools'].includes(tool.name)));
        for(const name of ['web_search','web_fetch'])assert.ok(options.tools.some(tool=>tool.name===name),'Companion must expose '+name);
        const schema=options.tools.find(tool=>tool.name==='companion_reply').parameters;
        assert.ok(schema.required.includes('reply_reference'));assert.ok(schema.required.includes('msg_id'));
        assert.equal(schema.properties.quoteReply.type,'boolean');
        assert.equal(schema.properties.intent,undefined);
        const owner=companions.owners.get(options.sessionId),script=scripts.get(options.sessionId);
        let step=script?.shift();
        if(!step)step=owner.kind==='work'?{name:'companion_reply',args:{msg_id:'',reply_reference:'FIXTURE_WORK_RESULT'}}:{name:'companion_quiet'};
        if(owner.kind==='work')for(const name of ['companion_wait','companion_quiet','companion_work','companion_emoji'])assert.ok(!options.tools.some(tool=>tool.name===name));
        actions.push({sessionId:options.sessionId,name:step.name,at:Date.now(),options});
        if(step.check)step.check(options);
        if(step.name==='silence')yield* textChunks('Nothing further to do.');
        else yield* toolChunks('call-'+calls.length,step.name,step.args);
      }else {
        const contents=options.messages.map(contentText),reference=contents.find(text=>text.startsWith('【回复信息参考】')||text.startsWith('[Reply Reference]'));
        if(reference){
          assert.ok(!reference.startsWith('{"intent"'));
          const body=reference.includes('FIXTURE_WORK_RESULT')?'Fictional work completed with its verified result.':
            reference.includes('FIXTURE_AFTER_WAIT')?'I finished waiting.':reference.includes('FIXTURE_NEW_MESSAGE')?'I saw your new message.':options.model+' 收到。\n\n我们继续聊。';
          yield* textChunks(body);
        }else if(/emoji_index/u.test(options.system??''))yield* textChunks('{"emoji_index":1,"reason":"Fictional acknowledgement"}');
        else {
          let input;try{input=JSON.parse(contents.at(-1));}catch{}
          yield* textChunks(input?.candidates?JSON.stringify({recordIds:input.candidates.slice(0,input.limit).map(record=>record.recordId)}):'Fictional conversation title');
        }
      }
    }
  }
  ctx.llm.registerAdapter(['companion-fixture'],new LocalModel());
  const defaultModel={provider:'companion-fixture',model:'work-model'};
  await ctx.agentDefaultModel.saveSelection(defaultModel);
  await companions.store.updateProfile('kaka',{model:{provider:'companion-fixture',model:'kaka-model'}});
  await companions.store.updateProfile('maimai',{model:{provider:'companion-fixture',model:'maimai-model'}});
  Object.assign(companions.options,{debounceMs:5,typingMinMs:0,typingMaxMs:0,learningMessages:100});
  companions.options.maibot={typo:{enabled:false},random:()=>0.99};
  companions.learner.options.learningUserMessages=100;
  const kakaAgent=await companions.agent('kaka'),maimaiAgent=await companions.agent('maimai');
  scripts.set(kakaAgent.id,[
    {name:'companion_reply',args:{msg_id:'greeting-kaka',reply_reference:'FIXTURE_GREETING',reply_style:'正常回复'}},
    {name:'companion_stickers',check:options=>assert.match(JSON.stringify(options.messages),/kaka-model 收到/u)},
    {name:'companion_emoji',args:{msg_id:'greeting-kaka'}},
    {name:'companion_wait',args:{seconds:0.03}},
    {name:'companion_reply',args:{msg_id:'greeting-kaka',reply_reference:'FIXTURE_AFTER_WAIT',quoteReply:true}},
    {name:'companion_quiet'},
  ]);
  scripts.set(maimaiAgent.id,[{name:'companion_reply',args:{msg_id:'greeting-maimai',reply_reference:'FIXTURE_GREETING'}},{name:'companion_quiet'}]);
  const login=await fetch('http://127.0.0.1:'+port+'/?token='+token,{headers:{cookie:'droy_session='+token},redirect:'manual'});
  const cookie='droy_session='+token+'; '+login.headers.get('set-cookie').split(';')[0];
  const api=async(path='',method='GET',body)=>{
    const response=await fetch('http://127.0.0.1:'+port+'/droy/companions'+path,{
      method,headers:{cookie,...(body?{'content-type':'application/json'}:{})},
      ...(body?{body:JSON.stringify(body)}:{})});
    const raw=await response.text();assert.ok(raw,'Empty HTTP response '+response.status+' for '+method+' '+path);
    return {status:response.status,value:JSON.parse(raw)};
  };
  const listed=await api();assert.equal(listed.status,200);assert.equal(listed.value.companions.length,2);
  assert.deepEqual(ctx.agentDefaultModel.currentSelection(),defaultModel);
  assert.equal((await api('/kaka/messages','POST',{clientId:'greeting-kaka',text:'你好'})).status,202);
  await eventually(()=>scripts.get(kakaAgent.id).length===0&&kakaAgent.status!=='running','Kaka Planner reply, emoji, wait, reply and stop');
  const kakaReplies=(await companions.store.listMessages('kaka')).messages.filter(item=>item.role==='assistant');
  const toolFailures=actions.flatMap(action=>action.options.messages.filter(message=>message.role==='tool'&&message.isError));
  assert.deepEqual(toolFailures,[],'Every action in the real DSH loop must succeed');
  assert.ok(kakaReplies.some(item=>item.type==='sticker'&&item.stickerId==='test-sticker-ok'));
  const greeting=kakaReplies.filter(item=>item.type==='text'&&item.text.includes('kaka-model'));
  assert.equal(greeting.length,1,'A paragraph break does not create a second bubble');
  assert.equal(greeting[0].text,'kaka-model 收到。\n\n我们继续聊。');
  assert.equal(greeting[0].quoteReply,false);
  assert.equal(kakaReplies.find(item=>item.type==='sticker').quoteReply,false);
  assert.equal(kakaReplies.find(item=>item.text==='I finished waiting.').quoteReply,true);
  assert.ok(kakaReplies.some(item=>item.text==='I finished waiting.'),JSON.stringify(kakaReplies.map(item=>({type:item.type,text:item.text}))));
  assert.deepEqual(actions.filter(action=>action.sessionId===kakaAgent.id).map(action=>action.name),
    ['companion_reply','companion_stickers','companion_emoji','companion_wait','companion_reply','companion_quiet']);
  assert.equal((await companions.store.getState('kaka')).waitingUntil,null);
  assert.deepEqual(errors,[]);
  assert.ok(calls.some(call=>call.model==='kaka-model'&&call.messages.some(message=>contentText(message).startsWith('【回复信息参考】'))));
  assert.equal((await api('/maimai/messages','POST',{clientId:'greeting-maimai',text:'你好，麦麦'})).status,202);
  await eventually(()=>scripts.get(maimaiAgent.id).length===0&&maimaiAgent.status!=='running','Maimai reply');
  assert.ok((await companions.store.listMessages('kaka')).messages.every(item=>!item.text.includes('maimai-model')));
  assert.ok((await companions.store.listMessages('maimai')).messages.every(item=>!item.text.includes('kaka-model')));
  const memory=await companions.memory('kaka');await memory.remember({text:'Fictional user likes tea',sourceRefs:['greeting-kaka']});
  assert.equal((await (await companions.memory('maimai')).query()).items.length,0);
  assert.deepEqual(ctx.agentDefaultModel.currentSelection(),defaultModel);
  const profile=await companions.store.getProfile('kaka');
  await assert.rejects(ctx.sessionController.create({workspaceId:profile.workspaceId}),/COMPANION_WORKSPACE_MANAGED/);
  await assert.rejects(ctx.sessionController.prompt({sessionId:profile.chatSessionId,content:[{type:'text',text:'Manual bypass'}]},new AbortController().signal),/COMPANION_SESSION_MANAGED/);
  const visibleBefore=(await companions.store.listMessages('kaka')).messages.filter(row=>row.role==='assistant').length;
  scripts.set(kakaAgent.id,[{name:'silence'}]);
  await companions.submit('kaka',{clientId:'intentional-silence',text:'No answer needed.'});
  await eventually(async()=>scripts.get(kakaAgent.id).length===0&&!(await companions.store.getState('kaka')).pendingMessageIds.length,'intentional silence consumes input');
  assert.equal((await companions.store.listMessages('kaka')).messages.filter(row=>row.role==='assistant').length,visibleBefore);
  assert.equal(companions.state('kaka').error,undefined);

  scripts.set(kakaAgent.id,[{name:'companion_wait',args:{seconds:0.2}},
    {name:'companion_reply',args:{msg_id:'wait-new-message',reply_reference:'FIXTURE_NEW_MESSAGE'},check:options=>assert.match(JSON.stringify(options.messages),/An updated fictional message/u)},
    {name:'companion_quiet'}]);
  await companions.submit('kaka',{clientId:'wait-original-message',text:'Wait briefly before continuing.'});
  await eventually(()=>companions.state('kaka').phase==='waiting','same-turn wait starts');
  const waitingCalls=actions.filter(action=>action.sessionId===kakaAgent.id).length;
  await companions.submit('kaka',{clientId:'wait-new-message',text:'An updated fictional message'});
  await delay(40);
  assert.equal(actions.filter(action=>action.sessionId===kakaAgent.id).length,waitingCalls,'Default MaiBot wait does not create a second concurrent Planner turn');
  await eventually(async()=>scripts.get(kakaAgent.id).length===0&&!(await companions.store.getState('kaka')).pendingMessageIds.length,'wait resumes with new message once');
  assert.equal((await companions.store.listMessages('kaka')).messages.filter(row=>row.text==='I saw your new message.').length,1);
  assert.equal((await companions.store.getState('kaka')).waitingUntil,null);

  scripts.set(kakaAgent.id,[{name:'companion_wait',args:{seconds:30}}]);
  await companions.submit('kaka',{clientId:'cancel-wait',text:'Start a wait for cancellation testing.'});
  await eventually(()=>companions.state('kaka').phase==='waiting','cancellable wait starts');
  const cancelledCalls=actions.filter(action=>action.sessionId===kakaAgent.id).length;
  await companions.cancel('kaka');
  await eventually(()=>kakaAgent.status!=='running','wait cancellation reaches real DSH loop');
  await delay(40);
  assert.equal(actions.filter(action=>action.sessionId===kakaAgent.id).length,cancelledCalls);
  assert.equal((await companions.store.getState('kaka')).waitingUntil,null);
  assert.equal(companions.state('kaka').error,undefined);

  const task=await companions.startWork('kaka',{title:'Fictional delegated result',goal:'Return a fictional result for contract validation.'});
  scripts.set(kakaAgent.id,[{name:'companion_reply',args:{msg_id:task.taskId,reply_reference:'FIXTURE_TASK_CARD: Report the completed result.'}},
    {name:'companion_quiet'}]);
  await eventually(async()=>(await companions.store.getMessage('kaka',task.taskId)).task.state==='completed','work reply concludes the delegated task');
  assert.equal((await companions.store.getMessage('kaka',task.taskId)).task.summary,'Fictional work completed with its verified result.');
  assert.equal(actions.filter(action=>action.sessionId===task.sessionId).length,1);
  await eventually(()=>scripts.get(kakaAgent.id).length===0&&kakaAgent.status!=='running'&&!companions.state('kaka').pending.length,'work result chat followup');
  assert.ok((await companions.store.listMessages('kaka')).messages.some(message=>message.role==='assistant'&&message.type==='text'&&message.replyTo===task.taskId),
    'The completed task card is a valid reply target in the real Planner/Replyer loop');
  const root=await ctx.sessionController.create({workspaceId:ctx.workspaceRegistry.list().find(row=>![profile.workspaceId,(listed.value.companions.find(p=>p.id==='maimai')).workspaceId].includes(row.id)).id});
  const assembly=await ctx.systemPrompt.assemble({agent:ctx.agents.get(root.sessionId),scope:ctx.agents.get(root.sessionId)});
  assert.ok(!assembly.contexts.find(item=>item.name==='droy:current-companion')?.text);
  assert.ok(assembly.sections.some(section=>section.text.includes('中立工作助手')));
  assert.deepEqual(errors,[]);
  if(process.env.DROY_COMPANION_TEST_EVIDENCE){
    const directory=process.env.DROY_COMPANION_TEST_EVIDENCE;await mkdir(directory,{recursive:true});
    await writeFile(join(directory,'packaged-integration-receipts.json'),JSON.stringify({generatedAt:new Date().toISOString(),
      kind:'scripted-model-real-dsh-loop',actions:actions.map(({sessionId,name,at})=>({sessionId,name,at})),errors,
      calls:calls.map(({provider,model,sessionId,system,messages,tools})=>({provider,model,sessionId,system,messages,tools})),
      kaka:await companions.store.listMessages('kaka'),maimai:await companions.store.listMessages('maimai')},null,2));
  }
});
