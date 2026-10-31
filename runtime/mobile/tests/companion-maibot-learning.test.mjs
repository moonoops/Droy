import test, { after } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { registerHooks } from 'node:module';
import { CompanionStore } from '../companions/store.mjs';
import { openLegacyCompanionStore } from './fixtures/legacy-companion-db.mjs';
import { CompanionLearning, maiBotLearningCopy } from '../companions/learning.mjs';
import { renderMaiBotPrompt } from '../companions/maibot-prompts.mjs';
import { withMemoryEvidence } from './fixtures/memory-evidence.mjs';

const imports=registerHooks({resolve(specifier,context,next){
  if(specifier==='@droyagent/memory/store')return {url:new URL('../../memory/store.mjs',import.meta.url).href,shortCircuit:true};
  if(specifier==='@droyagent/memory/memos')return {url:new URL('../../memory/memos/store.mjs',import.meta.url).href,shortCircuit:true};
  return next(specifier,context);
}});
after(()=>imports.deregister());
const {CompanionMemory}=await import('../companions/memory.mjs');
const empty=()=>({impressions:[],expressions:[],jargon:[],memories:[]});

async function fixture(t,{respond,locale='en'}={}) {
  const directory=await mkdtemp(join(tmpdir(),'droy-maibot-learning-'));
  let store=await openLegacyCompanionStore({directory});
  for(const id of ['kaka','maimai'])await store.updateProfile(id,{model:{provider:'fictional',model:'fixture-'+id}});
  const calls=[],memories=new Map(),language={value:locale};
  const memoryFor=async id=>{
    if(!memories.has(id))memories.set(id,await CompanionMemory.open({directory,companionId:id}));
    return memories.get(id);
  };
  const generate=async(profile,system,input,options)=>{
    const call={profile,system,input,options,stage:options.learningStage};calls.push(call);
    const supplied=await respond?.(call);
    if(supplied!==undefined)return input.suppliedMemories?withMemoryEvidence(supplied,input.messages):typeof supplied==='string'?supplied:JSON.stringify(supplied);
    if(call.stage==='learn_style'||call.stage==='learn_jargon')return '[]';
    if(call.stage==='expression_evaluation')return JSON.stringify({suitable:true,reason:'Fictional reusable wording'});
    if(call.stage==='jargon_inference_with_context')return JSON.stringify({meaning:'A fictional private cafe',no_info:false});
    if(call.stage==='jargon_inference_content_only')return JSON.stringify({meaning:'A fictional generic dictionary meaning'});
    if(call.stage==='jargon_compare_inference')return JSON.stringify({is_similar:false,reason:'The private meaning differs'});
    return JSON.stringify(empty());
  };
  const build=()=>new CompanionLearning({store,memoryFor,generate,getLocale:()=>language.value,
    options:{learningUserMessages:1,semanticRecall:false}});
  let learner=build();
  t.after(async()=>{
    await Promise.allSettled([...learner.running.values()]);
    await Promise.all([...memories.values()].map(memory=>memory.close()));
    await store.close();await rm(directory,{recursive:true,force:true});
  });
  return {calls,language,memoryFor,get store(){return store;},get learner(){return learner;},
    append:(id,text,extra={})=>store.appendMessage('kaka',{id,role:'user',text,...extra}),
    async reopen(){await store.close();store=await CompanionStore.open({directory});learner=build();},
    async learn(){return learner.maintain('kaka',{compact:false});},
  };
}
const stages=f=>f.calls.filter(call=>call.stage).map(call=>call.stage);
const jargonExtractor=({stage,input})=>stage==='learn_jargon'?input.messages.flatMap((message,index)=>
  message.role==='user'&&message.text.includes('moon dock')?[{content:'moon dock',id:String(index+1)}]:[]):undefined;

test('both languages use original style extraction and separate review before persisting sourced reusable expressions',async t=>{
  assert.deepEqual(Object.keys(maiBotLearningCopy.zh),Object.keys(maiBotLearningCopy.en));
  for(const locale of ['zh','en'])await t.test(locale,async child=>{
    const f=await fixture(child,{locale,respond:({stage,input})=>{
      if(stage==='learn_style')return [{situation:'Fictional surprise',style:'Well, would you look at that',id:'1'},
        {situation:'Fictional greeting',style:'Fictional specific person name',id:'1'}];
      if(stage==='expression_evaluation')return {suitable:input.style!=='Fictional specific person name',reason:'Fictional review'};
    }});
    await f.append('source','Well, would you look at that');
    await f.append('self','Fictional assistant phrase',{role:'assistant'});
    await f.learn();
    const state=await f.store.getState('kaka');
    assert.equal(state.expressions.length,1);
    assert.deepEqual(state.expressions[0].sourceIds,['source']);
    assert.equal(state.expressions[0].count,1);
    assert.deepEqual(stages(f),['learn_style','expression_evaluation','expression_evaluation','learn_jargon']);
    const extraction=f.calls.find(call=>call.stage==='learn_style');
    assert.ok(extraction.system.includes(renderMaiBotPrompt('learn_style',locale,{chat_str:''}).trim()));
    assert.ok(extraction.system.includes('role="SELF"'));
    assert.ok(f.calls.filter(call=>call.stage).every(call=>call.system.endsWith(maiBotLearningCopy[locale].boundary)));
    assert.deepEqual(await f.store.getState('maimai'),{});
    const count=f.calls.length;await f.reopen();await f.learn();assert.equal(f.calls.length,count);
  });
});

test('jargon accumulates persisted unique evidence and runs the original three inference stages at the first threshold',async t=>{
  const f=await fixture(t,{respond:jargonExtractor});
  for(let i=1;i<=3;i++){await f.append('source-'+i,'Our moon dock is the fictional cafe '+i);await f.learn();}
  let state=await f.store.getState('kaka');
  assert.equal(state.jargon.length,0);
  assert.equal(state.jargonCandidates[0].sourceIds.length,3);
  assert.equal(stages(f).some(stage=>stage.startsWith('jargon_inference')),false);
  await f.reopen();await f.learn();
  assert.equal((await f.store.getState('kaka')).jargonCandidates[0].sourceIds.length,3);
  await f.append('source-4','See you at the moon dock, our fictional cafe');await f.learn();
  state=await f.store.getState('kaka');
  assert.equal(state.jargon[0].meaning,'A fictional private cafe');
  assert.deepEqual(state.jargon[0].sourceIds,['source-1','source-2','source-3','source-4']);
  assert.equal(state.jargonCandidates[0].lastInferenceCount,4);
  assert.deepEqual(stages(f).slice(-3),['jargon_inference_with_context','jargon_inference_content_only','jargon_compare_inference']);
  const inference=f.calls.find(call=>call.stage==='jargon_inference_with_context');
  assert.equal(inference.input.messages.length,4);
  assert.ok(inference.input.messages.every(message=>message.role==='user'));
});

test('insufficient jargon context is deferred until new evidence reaches the next threshold and survives restart',async t=>{
  const f=await fixture(t,{respond:call=>call.stage==='jargon_inference_with_context'&&call.input.messages.length<8
    ?{meaning:'',no_info:true}:jargonExtractor(call)});
  for(let i=1;i<=4;i++)await f.append('source-'+i,'Fictional moon dock mention '+i);
  await f.learn();await f.reopen();
  assert.equal((await f.store.getState('kaka')).jargon.length,0);
  for(let i=5;i<=7;i++){await f.append('source-'+i,'Fictional moon dock mention '+i);await f.learn();}
  assert.equal(f.calls.filter(call=>call.stage==='jargon_inference_with_context').length,1);
  assert.equal(f.calls.filter(call=>call.stage==='jargon_inference_content_only').length,0);
  await f.append('source-8','Fictional moon dock means our cafe');await f.learn();
  assert.equal(f.calls.filter(call=>call.stage==='jargon_inference_with_context').length,2);
  assert.equal((await f.store.getState('kaka')).jargon[0].meaning,'A fictional private cafe');
});

test('ordinary terms with matching contextual and general meanings do not become private jargon',async t=>{
  const f=await fixture(t,{respond:call=>call.stage==='jargon_compare_inference'?{is_similar:true,reason:'Same meaning'}:jargonExtractor(call)});
  for(let i=1;i<=4;i++)await f.append('source-'+i,'Fictional moon dock mention '+i);
  await f.learn();
  assert.equal((await f.store.getState('kaka')).jargon.length,0);
  assert.equal((await f.store.getState('kaka')).jargonCandidates[0].lastInferenceCount,4);
});

test('forgotten candidate evidence cannot cross the inference threshold or be sent in later learning',async t=>{
  const f=await fixture(t,{respond:jargonExtractor});
  for(let i=1;i<=3;i++)await f.append('source-'+i,'Fictional moon dock '+i);
  await f.learn();
  await f.store.excludeDerivedMessages('kaka',['source-1']);
  await f.append('source-4','Fictional new moon dock');await f.learn();
  const state=await f.store.getState('kaka');
  assert.deepEqual(state.jargonCandidates[0].sourceIds,['source-4']);
  assert.equal(stages(f).some(stage=>stage.startsWith('jargon_inference')),false);
  assert.ok(!f.calls.at(-1).input.messages.some(message=>message.id==='source-1'));
});

test('an explicit meaning correction removes pending old inference and keeps the corrected source',async t=>{
  const f=await fixture(t,{respond:call=>{
    if(!call.stage)return {...empty(),jargon:[{action:'replace',targetId:'known-term',term:'moon dock',meaning:'Fictional corrected library',sourceIds:['correction']}]};
    return jargonExtractor(call);
  }});
  for(let i=1;i<=3;i++)await f.append('old-'+i,'Fictional moon dock cafe '+i);
  await f.store.updateState('kaka',{learningCursor:await f.store.getMessageCursor('kaka','old-3'),
    jargon:[{id:'known-term',term:'moon dock',meaning:'Fictional old cafe',sourceIds:['old-1']}],
    jargonCandidates:[{term:'moon dock',sourceIds:['old-1','old-2','old-3'],lastInferenceCount:0}]});
  await f.append('correction','Actually our moon dock means the fictional library');await f.learn();
  const state=await f.store.getState('kaka');
  assert.equal(state.jargon[0].meaning,'Fictional corrected library');
  assert.deepEqual(state.jargon[0].sourceIds,['correction']);
  assert.deepEqual(state.jargonCandidates,[]);
  assert.equal(stages(f).some(stage=>stage.startsWith('jargon_inference')),false);
});

test('a withdrawn expression cannot be relearned from the same correction message',async t=>{
  const f=await fixture(t,{respond:({stage})=>{
    if(!stage)return {...empty(),expressions:[{action:'remove',targetId:'old-style',sourceIds:['correction']}]};
    if(stage==='learn_style')return [{situation:'A different fictional situation',style:'Old fictional expression',id:'1'}];
  }});
  await f.store.updateState('kaka',{expressions:[{id:'old-style',situation:'Fictional greeting',expression:'Old fictional expression',sourceIds:['earlier']}]});
  await f.append('correction','Please stop using Old fictional expression');await f.learn();
  assert.deepEqual((await f.store.getState('kaka')).expressions,[]);
  assert.equal(f.calls.some(call=>call.stage==='expression_evaluation'),false);
});

test('source invalidation during expression review blocks late language writes and excludes previously committed facts from recall',async t=>{
  let release,started;
  const gate=new Promise(resolve=>{release=resolve;}),began=new Promise(resolve=>{started=resolve;});
  const f=await fixture(t,{respond:async({stage})=>{
    if(stage==='learn_style')return [{situation:'Fictional greeting',style:'Fictional phrase',id:'1'}];
    if(stage==='expression_evaluation'){started();await gate;return {suitable:true};}
    if(!stage)return {...empty(),memories:[{kind:'fact',text:'Fictional private fact',sourceIds:['source']}]};
  }});
  await f.append('source','Fictional phrase and private fact');
  const pending=f.learn();await began;
  await f.store.excludeDerivedMessages('kaka',['source']);release();
  assert.equal((await pending).discarded,true);
  const state=await f.store.getState('kaka');
  assert.equal(state.expressions,undefined);assert.equal(state.learningCursor,undefined);
  assert.equal((await(await f.memoryFor('kaka')).query()).total,1,'the fact committed independently before language review began');
  assert.deepEqual((await f.learner.recall('kaka',[{role:'user',text:'Fictional private fact'}])).items,[],'excluded sources cannot return to chat');
});

test('extracted sources cannot name SELF and nonliteral jargon cannot be learned from an invented term',async t=>{
  const bad=await fixture(t,{respond:({stage})=>stage==='learn_style'?[{situation:'Fictional scene',style:'Fictional expression',id:'2'}]:undefined});
  await bad.append('user','Fictional actual user text');await bad.append('self','Fictional assistant expression',{role:'assistant'});
  await assert.rejects(bad.learn(),error=>error.code==='COMPANION_LEARNING_SOURCE_REQUIRED');
  assert.equal((await bad.store.getState('kaka')).learningCursor,undefined);
  const f=await fixture(t,{respond:({stage})=>stage==='learn_jargon'?[{content:'invented phrase',id:'1'}]:undefined});
  await f.append('source','Fictional actual user text');await f.learn();
  assert.deepEqual((await f.store.getState('kaka')).jargonCandidates,[]);
  assert.deepEqual((await f.store.getState('kaka')).jargon,[]);
});

test('real DeepSeek style output using durable message IDs completes review, jargon extraction and persistence for both companions',async t=>{
  // Synthetic evaluation responses from the two companion runs. Preserve the
  // returned IDs so this covers the production mismatch with numbered rows.
  const responses={
    kaka:[
      {situation:'用夸张比喻表达对巧合的怀疑',style:'使用 怀疑××在针对我',id:'eval-kaka-banter'},
      {situation:'说完玩笑话后自我澄清',style:'使用 开玩笑的，别给我贴标签',id:'eval-kaka-joke-feedback'},
    ],
    maimai:[
      {situation:'吐槽倒霉事并怀疑被针对',style:'用夸张自嘲的口吻抱怨',id:'eval-maimai-banter'},
      {situation:'开玩笑后担心被误解或表扬',style:'先抛梗再补一句划清态度',id:'eval-maimai-joke-feedback'},
    ],
  };
  for(const id of ['kaka','maimai'])await t.test(id,async child=>{
    const f=await fixture(child,{locale:'zh',respond:({stage})=>stage==='learn_style'?responses[id]:undefined});
    await f.store.appendMessage(id,{id:'eval-'+id+'-banter',role:'user',text:'虚构场景：我怀疑这场雨在针对我。'});
    await f.store.appendMessage(id,{id:'eval-'+id+'-joke-feedback',role:'user',text:'开玩笑的，别给我贴标签。'});
    const result=await f.learner.maintain(id,{compact:false});
    assert.equal(result.learned,true);
    assert.deepEqual(stages(f),['learn_style','expression_evaluation','expression_evaluation','learn_jargon']);
    const state=await f.store.getState(id);
    assert.equal(state.learningCursor,await f.store.getMessageCursor(id,'eval-'+id+'-joke-feedback'));
    assert.deepEqual(state.expressions.map(entry=>entry.sourceIds),responses[id].map(entry=>[entry.id]));
    assert.deepEqual(await f.store.getState(id==='kaka'?'maimai':'kaka'),{});
    await f.reopen();
    assert.deepEqual((await f.store.getState(id)).expressions,state.expressions);
  });
});

test('jargon extraction accepts durable current-user IDs while retaining original numbered IDs',async t=>{
  const f=await fixture(t,{respond:({stage})=>stage==='learn_jargon'
    ?[{content:'moon dock',id:'real-source'},{content:'moon dock',id:'2'},{content:'moon dock',id:3}]:undefined});
  for(const id of ['real-source','second-source','third-source'])await f.append(id,'Fictional moon dock mention');
  await f.learn();
  assert.deepEqual((await f.store.getState('kaka')).jargonCandidates[0].sourceIds,['real-source','second-source','third-source']);
});

test('durable learning references still reject assistant, foreign, absent and ambiguous numeric identities',async t=>{
  for(const reference of ['self-source','foreign-source','missing-source','2'])await t.test(reference,async child=>{
    const f=await fixture(child,{respond:({stage})=>stage==='learn_style'
      ?[{situation:'Fictional situation',style:'Fictional phrase',id:reference}]:undefined});
    await f.append('2','Fictional first user source');
    await f.append('second-user','Fictional second user source');
    await f.append('self-source','Fictional assistant phrase',{role:'assistant'});
    await f.store.appendMessage('maimai',{id:'foreign-source',role:'user',text:'Fictional other companion source'});
    await assert.rejects(f.learn(),error=>error.code==='COMPANION_LEARNING_SOURCE_REQUIRED');
    assert.equal((await f.store.getState('kaka')).learningCursor,undefined);
    assert.equal(f.calls.some(call=>call.stage==='expression_evaluation'),false);
  });
});
