import test, { after } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { registerHooks } from 'node:module';
import { CompanionStore } from '../companions/store.mjs';
import { CompanionLearning, learningProtocols, rankMemoryCandidates } from '../companions/learning.mjs';
import { dialoguePrompts } from '../companions/dialogue-prompts.mjs';

const imports = registerHooks({ resolve(specifier, context, next) {
  if(specifier==='@droyagent/memory/store')return {url:new URL('../../memory/store.mjs',import.meta.url).href,shortCircuit:true};
  return next(specifier,context);
} });
after(()=>imports.deregister());
const { CompanionMemory } = await import('../companions/memory.mjs');
const code = expected => error => error.code===expected;
const emptyLearning = () => ({impressions:[],expressions:[],jargon:[],memories:[]});

async function fixture(t,{respond,options={},locale='en'}={}) {
  const directory=await mkdtemp(join(tmpdir(),'droy-companion-learning-'));
  const store=await CompanionStore.open({directory}),memories=new Map(),calls=[];
  await store.updateProfile('kaka',{model:{provider:'fixture-a',model:'local-a'}});
  await store.updateProfile('maimai',{model:{provider:'fixture-b',model:'local-b'}});
  let memoryAccesses=0;
  const memoryFor=async id=>{
    memoryAccesses++;
    if(!memories.has(id))memories.set(id,await CompanionMemory.open({directory,companionId:id}));
    return memories.get(id);
  };
  const learning=new CompanionLearning({store,memoryFor,getLocale:()=>locale,
    options:{learningUserMessages:1,compactAfter:6,retainMessages:2,batchMessages:8,maxBatches:2,memoryCandidates:12,memoryBytes:24000,semanticRecall:false,...options},
    generate:async(profile,system,input,{signal,learningStage})=>{
      const call={profile,system,input,signal,learningStage};calls.push(call);
      if(learningStage==='learn_style'||learningStage==='learn_jargon')return '[]';
      if(learningStage==='expression_evaluation')return JSON.stringify({suitable:true,reason:'Fictional suitable expression'});
      return respond ? respond(call) : input.suppliedMemories ? JSON.stringify(emptyLearning()) : 'Fictional unfinished topic retained.';
    }});
  t.after(async()=>{
    await Promise.allSettled([...learning.running.values()]);
    await Promise.all([...memories.values()].map(memory=>memory.close()));
    await store.close();await rm(directory,{recursive:true,force:true});
  });
  const append=(id,text,extra={})=>store.appendMessage('kaka',{id,role:'user',type:'text',text,createdAt:'2026-10-01T00:00:00Z',...extra});
  return {directory,store,learning,memoryFor,calls,append,get memoryAccesses(){return memoryAccesses;}};
}

test('incremental learning persists sourced language and skips processed messages even with identical timestamps',async t=>{
  const f=await fixture(t,{respond:({input})=>{
    assert.ok(input.suppliedMemories);
    const sourceIds=[input.messages.find(message=>message.role==='user').id];
    return JSON.stringify({impressions:[{text:'Fictional user enjoys quiet conversations',sourceIds}],
      expressions:[{situation:'Fictional greeting',expression:'A shared fictional phrase',sourceIds}],
      jargon:[{term:'Fictional spot',meaning:'An agreed fictional cafe',sourceIds}],
      memories:[{kind:'preference',text:'Fictional user likes quiet cafes',sourceIds}]});
  }});
  await f.append('first','Fictional greeting and cafe preference');
  const first=await f.learning.maintain('kaka');
  assert.equal(first.learned,true);
  const saved=await f.store.getState('kaka');
  assert.deepEqual(saved.impressions[0].sourceIds,['first']);
  assert.ok(saved.expressions[0].id);
  assert.equal((await(await f.memoryFor('kaka')).query()).items[0].payload.text,'Fictional user likes quiet cafes');
  await f.learning.maintain('kaka');
  assert.equal(f.calls.length,4,'maintenance, style extraction, expression review and jargon extraction run once');
  await f.append('second','Another fictional greeting');
  await f.learning.maintain('kaka');
  assert.equal(f.calls.length,8);
  assert.equal((await f.store.getState('kaka')).learnedThroughMessageId,'second');
  assert.equal((await f.store.getState('kaka')).expressions.length,1);
  assert.deepEqual((await f.store.getState('kaka')).expressions[0].sourceIds,['first','second']);
  assert.equal((await(await f.memoryFor('kaka')).query()).total,1);
  assert.deepEqual((await f.store.getState('maimai')).expressions,undefined);
});

test('rolling summary starts before a recent-history page drops early context and catches up in bounded passes',async t=>{
  const f=await fixture(t,{respond:({input})=>input.suppliedMemories ? JSON.stringify(emptyLearning())
    : [input.summary,...input.messages.map(message=>message.text)].filter(Boolean).join(' | ')});
  for(let index=0;index<30;index++)await f.append('history-'+index,index===0?'Fictional unfinished first promise':'Fictional topic '+index);
  let result=await f.learning.maintain('kaka');
  assert.equal(result.hasMore,true);
  assert.equal(result.compacted,true);
  assert.ok(f.calls.some(call=>!call.learningStage&&!call.input.suppliedMemories&&call.input.messages.some(message=>message.id==='history-0')));
  for(let round=0;result.hasMore&&round<8;round++)result=await f.learning.maintain('kaka');
  assert.equal(result.hasMore,false);
  const state=await f.store.getState('kaka');
  assert.match(state.summary,/Fictional unfinished first promise/);
  assert.equal(state.learnedThroughMessageId,'history-29');
  const pending=await f.store.listMessages('kaka',{after:state.summaryCursor});
  assert.ok(pending.messages.length<f.learning.options.compactAfter);
  assert.equal((await f.store.listMessages('kaka',{limit:8})).messages.some(message=>message.id==='history-0'),false);
});

test('the model can replace and withdraw evidenced learning and correct a supplied memory',async t=>{
  let target;
  const f=await fixture(t,{respond:({input})=>JSON.stringify({
    impressions:[{action:'replace',targetId:'impression-old',text:'Fictional user now prefers tea',sourceIds:['correction']}],
    expressions:[{action:'remove',targetId:'expression-old',sourceIds:['correction']}],
    jargon:[{action:'replace',targetId:'jargon-old',term:'usual spot',meaning:'The fictional tea shop',sourceIds:['correction']}],
    memories:[{action:'revise',recordId:target.recordId,expectedRevisionId:target.revisionId,text:'Fictional user now prefers tea',sourceIds:['correction']}],
  })});
  await f.append('original','Fictional user previously preferred coffee');
  const memory=await f.memoryFor('kaka');
  target=await memory.remember({kind:'preference',text:'Fictional user prefers coffee',sourceRefs:['original']});
  const firstPage=await f.store.listMessages('kaka',{after:'seq:0'});
  await f.store.updateState('kaka',{learningCursor:firstPage.nextAfter,
    impressions:[{id:'impression-old',text:'Fictional coffee preference',sourceIds:['original']}],
    expressions:[{id:'expression-old',situation:'Fictional greeting',expression:'Old fictional phrase',sourceIds:['original']}],
    jargon:[{id:'jargon-old',term:'usual spot',meaning:'The fictional coffee shop',sourceIds:['original']}],
  });
  await f.append('correction','Fictional correction: tea now; no longer use that greeting');
  await f.learning.maintain('kaka');
  const state=await f.store.getState('kaka');
  assert.equal(state.impressions[0].id,'impression-old');
  assert.equal(state.impressions[0].text,'Fictional user now prefers tea');
  assert.deepEqual(state.impressions[0].sourceIds,['correction']);
  assert.deepEqual(state.expressions,[]);
  assert.equal(state.jargon[0].meaning,'The fictional tea shop');
  const corrected=await memory.get(target.recordId);
  assert.notEqual(corrected.revisionId,target.revisionId);
  assert.equal(corrected.payload.text,'Fictional user now prefers tea');
  assert.deepEqual(corrected.sourceRefs,['im:kaka:correction']);
  assert.equal((await memory.query()).total,1);
});

test('unknown learning targets or memory identities fail before committing any generated changes',async t=>{
  for(const bad of ['entry','memory'])await t.test(bad,async child=>{
    const f=await fixture(child,{respond:()=>JSON.stringify({
      impressions:bad==='entry'?[{action:'remove',targetId:'not-supplied',sourceIds:['source']}]:[{text:'Fictional uncommitted impression',sourceIds:['source']}],
      expressions:[],jargon:[],memories:bad==='memory'?[{action:'revise',recordId:'unknown-record',expectedRevisionId:'unknown-revision',text:'Unknown fictional memory',sourceIds:['source']}]:[],
    })});
    await f.append('source','Fictional actual user evidence');
    await assert.rejects(f.learning.maintain('kaka'),code('COMPANION_LEARNING_TARGET_REQUIRED'));
    assert.deepEqual(await f.store.getState('kaka'),{});
    assert.equal((await(await f.memoryFor('kaka')).query()).total,0);
  });
});

test('generated learning cannot cite the companion or another companion user as evidence',async t=>{
  for(const invalidId of ['assistant-source','other-companion-source','made-up-source'])await t.test(invalidId,async child=>{
    const f=await fixture(child,{respond:()=>JSON.stringify({...emptyLearning(),impressions:[{text:'Fictional invalid inference',sourceIds:[invalidId]}]})});
    await f.append('real-user','Fictional real user source');
    await f.append('assistant-source','Fictional assistant phrase',{role:'assistant'});
    await f.store.appendMessage('maimai',{id:'other-companion-source',role:'user',text:'Fictional other conversation'});
    await assert.rejects(f.learning.maintain('kaka'),code('COMPANION_LEARNING_SOURCE_REQUIRED'));
    assert.equal((await f.store.getState('kaka')).learningCursor,undefined);
  });
});

test('cancelled generation never writes learning or advances its cursor',async t=>{
  let release,started;
  const gate=new Promise(resolve=>{release=resolve;}),began=new Promise(resolve=>{started=resolve;});
  const f=await fixture(t,{respond:async()=>{started();await gate;return JSON.stringify({...emptyLearning(),impressions:[{text:'Fictional late inference',sourceIds:['source']}]});}});
  await f.append('source','Fictional cancellable learning source');
  const abort=new AbortController(),pending=f.learning.maintain('kaka',{signal:abort.signal});
  await began;abort.abort();release();
  await assert.rejects(pending,{name:'AbortError'});
  assert.deepEqual(await f.store.getState('kaka'),{});
});

test('persistent invalidation or disabling memory discards a stale generated result',async t=>{
  for(const change of ['forget','disable'])await t.test(change,async child=>{
    let release,started;
    const gate=new Promise(resolve=>{release=resolve;}),began=new Promise(resolve=>{started=resolve;});
    const f=await fixture(child,{respond:async()=>{started();await gate;return JSON.stringify({...emptyLearning(),memories:[{kind:'fact',text:'Fictional stale fact',sourceIds:['source']}]});}});
    await f.append('source','Fictional source to invalidate');
    const pending=f.learning.maintain('kaka');await began;
    await f.store.updateState('kaka',{memoryRevision:1,...(change==='forget'?{forgottenMessageIds:['source']}: {})});
    if(change==='disable')await f.store.updateProfile('kaka',{settings:{memoryEnabled:false}});
    release();assert.equal((await pending).discarded,true);
    assert.equal((await(await f.memoryFor('kaka')).query()).total,0);
    assert.equal((await f.store.getState('kaka')).learningCursor,undefined);
  });
});

test('queued maintenance checks fresh cursors instead of learning the same sources twice',async t=>{
  let release,started;
  const gate=new Promise(resolve=>{release=resolve;}),began=new Promise(resolve=>{started=resolve;});
  const f=await fixture(t,{respond:async()=>{started();await gate;return JSON.stringify(emptyLearning());}});
  await f.append('source','Fictional shared source');
  const first=f.learning.maintain('kaka'),second=f.learning.maintain('kaka');
  await began;release();await Promise.all([first,second]);
  assert.equal(f.calls.length,3,'queued maintenance does not repeat either upstream extraction stage');
});

test('natural recall finds an older matching record and respects private jargon and forgotten sources',async t=>{
  const f=await fixture(t,{options:{memoryCandidates:3,recallTerms:4}}),memory=await f.memoryFor('kaka');
  const remembered=await memory.remember({text:'Fictional Riverside is the user’s preferred cafe',sourceRefs:['old-source'],occurredAt:'2025-01-01T00:00:00Z'});
  for(let index=0;index<6;index++)await memory.remember({text:'Unrelated fictional recent item '+index,occurredAt:new Date(Date.UTC(2026,1,index+1)).toISOString()});
  await f.store.updateState('kaka',{jargon:[{id:'usual',term:'usual spot',meaning:'Riverside cafe',sourceIds:['old-source']}]});
  const found=await f.learning.recall('kaka',[{role:'user',text:'How about the usual spot?'}],{limit:2});
  assert.equal(found.items[0].recordId,remembered.recordId);
  assert.deepEqual((await f.learning.recall('kaka',[{role:'user',text:'Astronomy telescopes'}])).items,[]);
  await f.store.updateState('kaka',{forgottenMessageIds:['old-source']});
  assert.equal((await f.learning.recall('kaka',[{role:'user',text:'Riverside cafe'}])).items.some(record=>record.recordId===remembered.recordId),false);
  const accesses=f.memoryAccesses;
  await f.store.updateProfile('kaka',{settings:{memoryEnabled:false}});
  assert.deepEqual(await f.learning.recall('kaka',[]),{items:[],total:0,enabled:false});
  assert.equal(f.memoryAccesses,accesses);
});

test('paired maintenance prompts follow the selected language and include previous summary distinctions',async t=>{
  assert.deepEqual(Object.keys(learningProtocols.zh).sort(),Object.keys(learningProtocols.en).sort());
  for(const locale of ['zh','en'])await t.test(locale,async child=>{
    const f=await fixture(child,{locale});
    for(let index=0;index<6;index++)await f.append('language-'+index,'Fictional agreement '+index);
    await f.learning.maintain('kaka');
    assert.ok(f.calls.some(call=>call.system.includes(dialoguePrompts[locale].learning)&&call.system.includes(learningProtocols[locale].learning)));
    assert.ok(f.calls.some(call=>call.system.includes(dialoguePrompts[locale].summary)&&call.system.includes(learningProtocols[locale].summary)));
    assert.ok(f.calls.every(call=>call.profile.id==='kaka'&&call.profile.model.provider==='fixture-a'));
  });
});

test('context preparation continuously compacts a long prefix without dropping the middle history',async t=>{
  const f=await fixture(t,{respond:({input})=>{
    assert.equal(input.suppliedMemories,undefined);
    return [input.summary,...input.messages.map(message=>message.text)].filter(Boolean).join(' | ');
  }});
  for(let index=0;index<30;index++)await f.append('context-'+index,'Shared fictional context '+index);
  const context=await f.learning.contextMessages('kaka');
  const combined=context.state.summary+' | '+context.messages.map(message=>message.text).join(' | ');
  for(let index=0;index<30;index++)assert.ok(combined.includes('Shared fictional context '+index));
  const covered=new Set([...f.calls.flatMap(call=>call.input.messages.map(message=>message.id)),...context.messages.map(message=>message.id)]);
  assert.deepEqual([...covered].sort(),Array.from({length:30},(_,index)=>'context-'+index).sort());
  assert.ok(context.messages.length<f.learning.options.compactAfter);
  const count=f.calls.length;
  await f.learning.contextMessages('kaka');
  assert.equal(f.calls.length,count);
  assert.equal((await f.store.getState('kaka')).learningCursor,undefined);
});

test('reset baseline excludes old chat from learning and context while the visible history remains',async t=>{
  const f=await fixture(t,{respond:({input})=>input.suppliedMemories?JSON.stringify(emptyLearning())
    :[input.summary,...input.messages.map(message=>message.text)].filter(Boolean).join(' | ')});
  for(let index=0;index<12;index++)await f.append('prior-'+index,'Private fictional prior topic '+index);
  const baseline=(await f.store.listMessages('kaka')).nextAfter;
  await f.store.updateState('kaka',{memoryRevision:1,forgottenThrough:baseline,learningCursor:'seq:0',summaryCursor:'seq:0',
    summaryThrough:null,summary:'Private fictional prior summary',summaryRevision:0});
  for(let index=0;index<8;index++)await f.append('after-'+index,'New fictional topic '+index);
  await f.learning.maintain('kaka');
  const context=await f.learning.contextMessages('kaka');
  assert.ok(f.calls.every(call=>call.input.messages.every(message=>message.id.startsWith('after-'))));
  assert.ok(!context.state.summary.includes('prior'));
  assert.ok(context.messages.every(message=>message.id.startsWith('after-')));
  assert.equal((await f.store.listMessages('kaka',{after:'seq:0'})).messages.length,20);
});

test('memory-off conversation compaction is transient and reset invalidates its cached context',async t=>{
  const f=await fixture(t,{respond:({input})=>[input.summary,...input.messages.map(message=>message.text)].filter(Boolean).join(' | ')});
  await f.store.updateProfile('kaka',{settings:{memoryEnabled:false}});
  for(let index=0;index<14;index++)await f.append('temporary-'+index,'Temporary fictional topic '+index);
  const context=await f.learning.contextMessages('kaka');
  assert.match(context.state.summary,/Temporary fictional topic 0/);
  assert.deepEqual(context.state.impressions,[]);
  assert.equal((await f.store.getState('kaka')).summary,undefined);
  assert.equal((await f.store.getState('kaka')).learningCursor,undefined);
  assert.equal(f.memoryAccesses,0);
  const count=f.calls.length;
  await f.learning.contextMessages('kaka');
  assert.equal(f.calls.length,count);
  const baseline=(await f.store.listMessages('kaka')).nextAfter;
  await f.store.updateState('kaka',{memoryRevision:1,forgottenThrough:baseline});
  await f.append('new','Fictional new context after reset');
  const reset=await f.learning.contextMessages('kaka');
  assert.equal(reset.state.summary,'');
  assert.deepEqual(reset.messages.map(message=>message.id),['new']);
});

test('summary generation also discards results invalidated while the model is running',async t=>{
  let release,started;
  const gate=new Promise(resolve=>{release=resolve;}),began=new Promise(resolve=>{started=resolve;});
  const f=await fixture(t,{respond:async({input})=>{
    if(input.suppliedMemories)return JSON.stringify(emptyLearning());
    started();await gate;return 'Private fictional stale summary';
  }});
  for(let index=0;index<6;index++)await f.append('summary-'+index,'Fictional summary source '+index);
  const pending=f.learning.maintain('kaka');await began;
  await f.store.updateState('kaka',{memoryRevision:1,summary:'',summaryThrough:null});
  release();assert.equal((await pending).discarded,true);
  const saved=await f.store.getState('kaka');
  assert.equal(saved.summary,'');
  assert.equal(saved.summaryCursor,undefined);
});

test('correction can find an older related memory beyond the recent candidate window',async t=>{
  let target;
  const f=await fixture(t,{options:{memoryCandidates:3},respond:({input})=>{
    const supplied=input.suppliedMemories.find(record=>record.recordId===target.recordId);
    assert.ok(supplied);
    return JSON.stringify({...emptyLearning(),memories:[{action:'revise',recordId:supplied.recordId,expectedRevisionId:supplied.revisionId,
      text:'Fictional Riverside is no longer the preferred cafe',sourceIds:['correction']}]});
  }});
  const memory=await f.memoryFor('kaka');
  target=await memory.remember({kind:'preference',text:'Fictional Riverside is the preferred cafe',occurredAt:'2025-01-01T00:00:00Z'});
  for(let index=0;index<8;index++)await memory.remember({text:'Unrelated fictional recent memory '+index,occurredAt:new Date(Date.UTC(2026,1,index+1)).toISOString()});
  await f.append('correction','Riverside is no longer my preferred cafe');
  await f.learning.maintain('kaka');
  assert.equal((await memory.get(target.recordId)).payload.text,'Fictional Riverside is no longer the preferred cafe');
});

test('forgotten user facts and assistant restatements stay visible but leave future raw context and learning',async t=>{
  const f=await fixture(t,{respond:({input})=>{
    assert.deepEqual(input.messages.map(message=>message.id),['safe-topic']);
    return JSON.stringify(emptyLearning());
  }});
  await f.append('identity','I am Fictional X');
  await f.append('identity-echo','I remember you are Fictional X',{role:'assistant',contextSourceIds:['identity']});
  await f.append('identity-repeated','You mentioned Fictional X before',{role:'assistant',contextSourceIds:['identity-echo']});
  await f.append('safe-topic','Fictional unrelated new topic');
  await f.store.excludeDerivedMessages('kaka',['identity']);
  const context=await f.learning.contextMessages('kaka');
  assert.deepEqual(context.messages.map(message=>message.id),['safe-topic']);
  await f.learning.maintain('kaka');
  assert.equal(f.calls.length,3);
  assert.equal((await f.store.listMessages('kaka')).messages.length,4);
});

test('atomic source exclusion discards late learning and prevents recall from surviving sourced records',async t=>{
  let release,started;
  const gate=new Promise(resolve=>{release=resolve;}),began=new Promise(resolve=>{started=resolve;});
  const f=await fixture(t,{respond:async()=>{
    started();await gate;
    return JSON.stringify({...emptyLearning(),memories:[{kind:'fact',text:'Fictional X is the user',sourceIds:['identity']}]});
  }});
  await f.append('identity','I am Fictional X');
  await f.append('echo','Fictional X is your name',{role:'assistant',contextSourceIds:['identity']});
  const memory=await f.memoryFor('kaka');
  await memory.remember({kind:'fact',text:'Fictional X prefers quiet evenings',sourceRefs:['identity']});
  const pending=f.learning.maintain('kaka');await began;
  await f.store.excludeDerivedMessages('kaka',['identity']);
  release();
  assert.equal((await pending).discarded,true);
  assert.equal((await memory.query()).total,1,'the late generated record was not committed');
  const recalled=await f.learning.recall('kaka',[{role:'user',text:'Tell me about Fictional X'}]);
  assert.equal(recalled.items.length,0,'a separate record with the excluded source must not reintroduce the fact');
  assert.equal((await f.store.getState('kaka')).learningCursor,undefined);
});

test('atomic exclusion invalidates a running summary and rebuilt context omits summarized derived facts',async t=>{
  let release,started,late=true;
  const gate=new Promise(resolve=>{release=resolve;}),began=new Promise(resolve=>{started=resolve;});
  const f=await fixture(t,{respond:async({input})=>{
    if(input.suppliedMemories)return JSON.stringify(emptyLearning());
    if(late){started();await gate;return 'Private fictional stale identity summary';}
    return [input.summary,...input.messages.map(message=>message.text)].filter(Boolean).join(' | ');
  }});
  await f.append('identity','I am Fictional X');
  const through=(await f.store.listMessages('kaka')).nextAfter;
  await f.append('summary-echo','I remember Fictional X from a summary',{role:'assistant',contextRange:{after:'seq:0',through}});
  for(let i=0;i<6;i++)await f.append('safe-'+i,'Fictional unrelated topic '+i);
  const pending=f.learning.maintain('kaka',{learn:false});await began;
  await f.store.excludeDerivedMessages('kaka',['identity']);late=false;release();
  assert.equal((await pending).discarded,true);
  const context=await f.learning.contextMessages('kaka');
  const text=context.state.summary+' '+context.messages.map(message=>message.text).join(' ');
  assert.ok(!text.includes('Fictional X'));
  assert.ok(!text.includes('stale identity'));
  for(let i=0;i<6;i++)assert.ok(text.includes('Fictional unrelated topic '+i));
});

test('semantic recall bridges different wording, preserves revisions and uses the current companion model in either language',async t=>{
  for(const locale of ['zh','en'])await t.test(locale,async child=>{
    const f=await fixture(child,{locale,options:{semanticRecall:true},respond:({profile,system,input})=>{
      assert.equal(system,learningProtocols[locale].recall);
      assert.equal(profile.model.provider,profile.id==='kaka'?'fixture-a':'fixture-b');
      assert.ok(input.candidates.every(candidate=>candidate.revisionId));
      assert.ok(!input.candidates.some(candidate=>candidate.text.includes(profile.id==='kaka'?'Fictional Ada':'小王')));
      const selected=input.candidates.find(candidate=>candidate.text.includes(profile.id==='kaka'?'小王':'Fictional Ada'));
      return JSON.stringify({recordIds:[selected.recordId],revisionId:'model-must-not-override-original'});
    }});
    assert.equal(new CompanionLearning({store:f.store,memoryFor:f.memoryFor,generate:()=>{}}).options.semanticRecall,true);
    const kaka=await f.memoryFor('kaka'),maimai=await f.memoryFor('maimai');
    const kakaRecord=(await kaka.remember({kind:'fact',text:'用户叫小王'})).record;
    const maimaiRecord=(await maimai.remember({kind:'fact',text:'This user is called Fictional Ada'})).record;
    await kaka.remember({kind:'fact',text:'今天某个虚构城市下雨'});
    assert.deepEqual(rankMemoryCandidates([kakaRecord],[{role:'user',text:'我叫什么'}]),[]);
    const selected=await f.learning.recall('kaka',[{id:'name-question',role:'user',text:'我叫什么'}]);
    assert.deepEqual(selected.items,[kakaRecord]);
    assert.equal(selected.items[0].revisionId,kakaRecord.revisionId);
    const other=await f.learning.recall('maimai',[{role:'user',text:'Could you remind me of my name?'}]);
    assert.deepEqual(other.items,[maimaiRecord]);
    assert.equal(f.calls.length,2);
  });
});

test('semantic recall returns an empty result without padding and skips model calls for empty or non-user context',async t=>{
  const f=await fixture(t,{options:{semanticRecall:true},respond:()=>JSON.stringify({recordIds:[]})});
  assert.deepEqual((await f.learning.recall('kaka',[{role:'user',text:'Fictional astronomy question'}])).items,[]);
  assert.equal(f.calls.length,0);
  await(await f.memoryFor('kaka')).remember({text:'The fictional user enjoys coffee'});
  assert.deepEqual((await f.learning.recall('kaka',[])).items,[]);
  assert.deepEqual((await f.learning.recall('kaka',[{role:'assistant',text:'Fictional assistant thought'}])).items,[]);
  assert.equal(f.calls.length,0);
  const unrelated=await f.learning.recall('kaka',[{role:'user',text:'Fictional astronomy question'}]);
  assert.deepEqual(unrelated.items,[]);
  assert.equal(unrelated.total,0);
  assert.equal(unrelated.candidates,1);
  assert.equal(f.calls.length,1);
});

test('semantic selection sees a matching older query result beyond the recent window without other companion records',async t=>{
  let oldRecord;
  const f=await fixture(t,{options:{semanticRecall:true,memoryCandidates:3},respond:({input})=>{
    assert.ok(input.candidates.some(candidate=>candidate.recordId===oldRecord.recordId));
    assert.ok(input.candidates.length<=3);
    return JSON.stringify([oldRecord.recordId]);
  }});
  const memory=await f.memoryFor('kaka');
  oldRecord=(await memory.remember({text:'Riverside is the fictional preferred cafe',occurredAt:'2025-01-01T00:00:00Z'})).record;
  for(let i=0;i<8;i++)await memory.remember({text:'Unrelated fictional astronomy record '+i,occurredAt:new Date(Date.UTC(2026,1,i+1)).toISOString()});
  const result=await f.learning.recall('kaka',[{role:'user',text:'Shall we visit Riverside?'}]);
  assert.deepEqual(result.items,[oldRecord]);
});

test('invalid semantic output and model errors are explicit failures rather than recent-memory fallback',async t=>{
  for(const response of ['not-json',JSON.stringify({recordIds:['not-a-supplied-id']}),JSON.stringify({recordIds:[{recordId:'invented'}]}),'model-error'])await t.test(response,async child=>{
    const f=await fixture(child,{options:{semanticRecall:true},respond:()=>{
      if(response==='model-error')throw new Error('Fictional provider failure');
      return response;
    }});
    await(await f.memoryFor('kaka')).remember({text:'A fictional coffee preference'});
    await assert.rejects(f.learning.recall('kaka',[{role:'user',text:'What do I enjoy?'}]),
      code(response==='model-error'?'COMPANION_RECALL_FAILED':'COMPANION_INVALID_RECALL'));
    assert.equal(f.calls.length,1);
  });
});

test('semantic recall is cancelled or discarded when its sources are invalidated during generation',async t=>{
  for(const change of ['cancel','exclude'])await t.test(change,async child=>{
    let release,started,record;
    const gate=new Promise(resolve=>{release=resolve;}),began=new Promise(resolve=>{started=resolve;});
    const f=await fixture(child,{options:{semanticRecall:true},respond:async()=>{started();await gate;return JSON.stringify({recordIds:[record.recordId]});}});
    await f.append('identity','I am Fictional X');
    record=(await(await f.memoryFor('kaka')).remember({text:'The user is Fictional X',sourceRefs:['identity']})).record;
    const controller=new AbortController(),pending=f.learning.recall('kaka',[{role:'user',text:'Who am I?'}],{signal:controller.signal});
    await began;
    if(change==='cancel')controller.abort();else await f.store.excludeDerivedMessages('kaka',['identity']);
    release();
    await assert.rejects(pending,change==='cancel'?{name:'AbortError'}:code('COMPANION_LEARNING_STALE'));
    if(change==='exclude') {
      assert.deepEqual((await f.learning.recall('kaka',[{role:'user',text:'Who am I?'}])).items,[]);
      assert.equal(f.calls.length,1,'excluded candidate cannot be passed to another semantic call');
    }
  });
});
