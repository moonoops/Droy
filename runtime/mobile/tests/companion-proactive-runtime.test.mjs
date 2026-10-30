import test,{after} from 'node:test';
import assert from 'node:assert/strict';
import {createRequire,registerHooks} from 'node:module';
import {pathToFileURL} from 'node:url';
import {buildProactiveContext} from '../companions/proactive-policy.mjs';
import {prepareReply} from '../companions/delivery.mjs';

const installed=createRequire(new URL('../../dsh/package.json',import.meta.url));
const imports=registerHooks({resolve(specifier,context,next) {
  if(specifier==='@droyagent/memory/memos')return {url:new URL('../../memory/memos/store.mjs',import.meta.url).href,shortCircuit:true};
  if(specifier==='@droyagent/memory/store')return {url:new URL('../../memory/store.mjs',import.meta.url).href,shortCircuit:true};
  if(specifier.startsWith('@deepseek-ai/'))return {url:pathToFileURL(installed.resolve(specifier)).href,shortCircuit:true};
  return next(specifier,context);
}});
after(()=>imports.deregister());
const {CompanionRuntime}=await import('../companions/runtime.mjs');
const now=Date.parse('2026-10-30T10:00:00Z'),id='fictional-companion';
const decision=overrides=>({action:'contact',kind:'check_in',reason:'A grounded hiking follow-up',sourceIds:['fictional-user'],nextCheckMinutes:150,...overrides});
function fixture() {
  const runtime=Object.create(CompanionRuntime.prototype),state={phase:'idle',revision:0,pending:[]};
  const profile={id,name:'Fictional companion',personality:'Calm',model:{provider:'fixture',model:'fictional'},
    settings:{proactiveEnabled:true,memoryEnabled:true,quietHours:{enabled:true,start:'22:00',end:'09:00'}}};
  let saved={lastUserAt:new Date(now-60*60_000).toISOString(),memoryRevision:0};
  const records=new Map([['fictional-user',{id:'fictional-user',role:'user',type:'text',text:'Fictional hiking conversation',createdAt:saved.lastUserAt}]]);
  const calls={models:[],agents:[],scheduled:[],warnings:[],emits:[]};
  Object.assign(runtime,{closed:false,lifetime:new AbortController(),options:{proactiveIntervalMs:1800000},
    states:new Map([[id,state]]),turnMemories:new Map(),state:()=>state,beginChat:()=>()=>{},locale:()=> 'en',copy:()=>({proactive:'Consider a grounded follow-up.'}),
    emit:(...args)=>calls.emits.push(args),ctx:{logger:{warn:value=>calls.warnings.push(value)}},
    clock:async()=>({currentTime:new Date(now).toISOString(),timeZone:'UTC'}),profileForUse:async()=>structuredClone(profile),
    maintenance:{proactive:(companionId,deadline,callback)=>{calls.scheduled.push({companionId,deadline,callback});}},
    store:{getProfile:async()=>structuredClone(profile),getState:async()=>structuredClone(saved),
      updateState:async(_id,patch)=>{saved={...saved,...(typeof patch==='function'?patch(structuredClone(saved)):patch)};return structuredClone(saved);},
      getMessage:async(_id,messageId)=>records.get(messageId),getMessageCursor:async()=> 'seq:1',
      appendMessage:async(_id,message)=>{records.set(message.id,structuredClone(message));return structuredClone(message);},
      completeInputs:async()=>{saved.replyDraft=null;}},
    proactiveContext:async(_id,_profile,_saved,clock)=>({context:buildProactiveContext({profile,messages:[...records.values()],state:saved,
      now:Date.parse(clock.currentTime),timeZone:clock.timeZone}),memories:[]}),
    generate:async(...args)=>{calls.models.push(args);return JSON.stringify(decision());},
    agent:async()=>({id:'fictional-session',status:'idle',followup:value=>calls.agents.push(value),steer:()=>assert.fail('Must not steer an unrelated running turn')})});
  return {runtime,state,profile,records,calls,get saved(){return structuredClone(saved);},setSaved:value=>{saved={...saved,...value};}};
}

test('disabled or quiet-hours proactive consideration makes no model call and sends nothing',async()=>{
  for(const mode of ['disabled','quiet']) {
    const f=fixture();
    if(mode==='disabled')f.profile.settings.proactiveEnabled=false;
    else f.runtime.clock=async()=>({currentTime:'2026-10-30T23:00:00Z',timeZone:'UTC'});
    const earliest=mode==='disabled'?Date.now():Date.parse((await f.runtime.clock()).currentTime);
    assert.ok(Date.parse(await f.runtime.considerProactive(id))>earliest);
    assert.equal(f.calls.models.length,0);assert.equal(f.calls.agents.length,0);
    assert.equal(f.saved.proactiveLastContactAt,undefined);
  }
});

test('model wait persists the decision and dynamically selected next check without counting a contact',async()=>{
  const f=fixture();f.runtime.generate=async()=>JSON.stringify(decision({action:'wait',sourceIds:[],nextCheckMinutes:390}));
  const next=await f.runtime.considerProactive(id);
  assert.equal(next,new Date(now+390*60_000).toISOString());
  assert.equal(f.saved.proactiveDecision.action,'wait');assert.equal(f.calls.agents.length,0);
  assert.equal(f.saved.proactiveUnansweredCount,undefined);assert.equal(f.state.proactiveAbort,null);
  assert.equal(f.runtime.proactiveConsidering,null);
});

test('proactive model context excludes memory derived from forgotten sources and other companion content',async()=>{
  const f=fixture();delete f.runtime.proactiveContext;
  f.setSaved({forgottenMessageIds:['forgotten-user']});
  const ownState=f.runtime.store.getState;
  f.runtime.store.getState=async companionId=>companionId===id?ownState(companionId):{
    proactiveLastContactAt:'2026-10-30T09:30:00Z',summary:'PRIVATE OTHER COMPANION CONTENT'};
  f.runtime.store.profiles=async()=>[f.profile,{id:'other-companion'}];
  f.runtime.store.listMessages=async()=>({messages:[...f.records.values()],hasMore:false});
  f.runtime.learner={activeEvents:async()=>[],hasExcludedSources:async(_id,sources)=>sources.includes('im:'+id+':forgotten-user')};
  f.runtime.memory=async()=>({query:async args=>{
    assert.deepEqual(args.states,['active']);
    return {items:[
      {recordId:'visible-memory',companionId:id,state:'active',payload:{text:'Fictional hiking preference'},sourceRefs:['im:'+id+':fictional-user']},
      {recordId:'excluded-memory',companionId:id,state:'active',payload:{text:'FORGOTTEN CONTENT'},sourceRefs:['im:'+id+':forgotten-user']},
      {recordId:'inferred-memory',companionId:id,state:'candidate',payload:{text:'UNCONFIRMED CONTENT'},sourceRefs:['im:'+id+':fictional-user']},
      {recordId:'expired-memory',companionId:id,state:'active',validUntil:new Date(now-1).toISOString(),payload:{text:'EXPIRED CONTENT'},sourceRefs:['im:'+id+':fictional-user']},
      {recordId:'future-memory',companionId:id,state:'active',validFrom:new Date(now+60_000).toISOString(),payload:{text:'FUTURE CONTENT'},sourceRefs:['im:'+id+':fictional-user']},
    ]};
  }});
  const result=await f.runtime.proactiveContext(id,f.profile,f.saved,await f.runtime.clock());
  assert.deepEqual(result.memories.map(item=>item.recordId),['visible-memory']);
  assert.equal(JSON.stringify(result.context).includes('FORGOTTEN CONTENT'),false);
  assert.equal(JSON.stringify(result.context).includes('PRIVATE OTHER COMPANION CONTENT'),false);
  for(const text of ['UNCONFIRMED CONTENT','EXPIRED CONTENT','FUTURE CONTENT'])assert.equal(JSON.stringify(result.context).includes(text),false);
  assert.equal(result.context.contact.recentOtherContactAt,'2026-10-30T09:30:00Z');
});

test('proactive retrieval rejects a foreign memory record before constructing model context',async()=>{
  const f=fixture();delete f.runtime.proactiveContext;
  f.runtime.store.profiles=async()=>[f.profile];
  f.runtime.store.listMessages=async()=>({messages:[...f.records.values()],hasMore:false});
  f.runtime.learner={activeEvents:async()=>[],hasExcludedSources:async()=>false};
  f.runtime.memory=async()=>({query:async()=>({items:[{recordId:'foreign-record',companionId:'other-companion',state:'active',payload:{text:'PRIVATE FOREIGN CONTENT'},sourceRefs:[]}]})});
  await assert.rejects(f.runtime.proactiveContext(id,f.profile,f.saved,await f.runtime.clock()),{code:'COMPANION_SOURCE_SCOPE'});
  assert.equal(f.calls.models.length,0);
});

test('grounded contact wakes its own session and clears the active intent only after visible delivery',async()=>{
  const f=fixture();await f.runtime.considerProactive(id);
  assert.equal(f.calls.agents.length,1);assert.equal(f.saved.proactiveUnansweredCount,undefined);
  const input=JSON.parse(f.calls.agents[0].content[0].text);
  assert.equal(input.internalIntent.evidence[0].id,'fictional-user');
  const draft=await prepareReply({store:f.runtime.store,companionId:id,sourceMessageIds:[],proactive:f.state.proactiveIntent,
    parts:[{type:'text',text:'How did the fictional hike go?'}]});
  draft.createdAt=new Date(now).toISOString();f.setSaved({replyDraft:draft});
  const result=await f.runtime.publish(id,draft,new AbortController().signal);
  assert.equal(result.newMessageIds.length,1);assert.equal(f.state.proactiveIntent,null);
  assert.deepEqual(draft.proactive.sourceIds,['fictional-user']);
  f.setSaved({replyDraft:draft});const replay=await f.runtime.publish(id,draft,new AbortController().signal);
  assert.equal(replay.newMessageIds.length,0);
});

test('proactive opt-out or memory change while deciding prevents a stale contact',async()=>{
  for(const mode of ['disabled','memory-changed']) {
    const f=fixture();f.runtime.generate=async()=>{
      if(mode==='disabled')f.profile.settings.proactiveEnabled=false;
      else f.setSaved({memoryRevision:1});
      return JSON.stringify(decision());
    };
    await f.runtime.considerProactive(id);
    assert.equal(f.calls.agents.length,0);assert.equal(f.saved.proactiveUnansweredCount,undefined);
  }
});

test('an input or memory revision during decision persistence cannot revive the old contact intent',async()=>{
  for(const change of ['input','memory']) {
    const f=fixture(),update=f.runtime.store.updateState;
    f.runtime.store.updateState=async(companionId,patch)=>{
      const result=await update(companionId,patch);
      if(patch?.proactiveDecision) {
        if(change==='input')f.state.revision++;
        else f.setSaved({memoryRevision:1});
      }
      return result;
    };
    await f.runtime.considerProactive(id);
    assert.equal(f.calls.agents.length,0,change+' changed after decision verification');
    assert.equal(f.state.proactiveIntent,undefined);
  }
});

test('another companion with an in-flight proactive intent postpones new contact without model calls',async()=>{
  const f=fixture();f.runtime.states.set('other-companion',{proactiveIntent:{sourceIds:['not-shared']}});
  await f.runtime.considerProactive(id);
  assert.equal(f.calls.models.length,0);assert.equal(f.calls.agents.length,0);
});

test('editing the persona while its agent is opening invalidates the old proactive decision',async()=>{
  const f=fixture(),open=f.runtime.agent;f.profile.personaRevision=1;
  f.runtime.agent=async()=>{const agent=await open();f.profile.personaRevision=2;return agent;};
  await f.runtime.considerProactive(id);
  assert.equal(f.calls.agents.length,0);
  assert.equal(f.state.proactiveIntent,undefined);
});

test('a background model failure rearms the next opportunity without poisoning chat state',async()=>{
  const f=fixture();f.runtime.generate=async()=>{throw Object.assign(new Error('Fictional outage'),{code:'FICTIONAL_OUTAGE'});};
  f.runtime.scheduleProactive(id,new Date(now).toISOString());
  await f.calls.scheduled[0].callback();
  assert.equal(f.calls.scheduled.length,2);assert.equal(f.state.phase,'idle');
  assert.equal(f.saved.proactiveFailures,1);assert.equal(f.saved.proactiveError,'FICTIONAL_OUTAGE');
  assert.ok(Date.parse(f.saved.proactiveNextAt)>Date.now());assert.equal(f.saved.proactiveUnansweredCount,undefined);
});

test('new-input cancellation rearms normally without recording a model failure',async()=>{
  const f=fixture();f.runtime.generate=async(_profile,_system,_input,{signal})=>{
    f.state.revision++;f.state.proactiveAbort.abort('new_message');signal.throwIfAborted();
  };
  f.runtime.scheduleProactive(id,new Date(now).toISOString());
  await f.calls.scheduled[0].callback();
  assert.equal(f.calls.scheduled.length,2);assert.equal(f.calls.agents.length,0);
  assert.equal(f.saved.proactiveFailures??0,0);assert.equal(f.calls.warnings.length,0);
});

test('runtime shutdown during a decision does not schedule or deliver new work',async()=>{
  const f=fixture();f.runtime.generate=async(_profile,_system,_input,{signal})=>{
    f.runtime.closed=true;f.runtime.lifetime.abort();signal.throwIfAborted();
  };
  f.runtime.scheduleProactive(id,new Date(now).toISOString());await f.calls.scheduled[0].callback();
  assert.equal(f.calls.scheduled.length,1);assert.equal(f.calls.agents.length,0);
  assert.equal(f.saved.proactiveUnansweredCount,undefined);
});
