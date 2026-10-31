import test, { after } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { registerHooks } from 'node:module';
import { CompanionStore } from '../companions/store.mjs';
import { openLegacyCompanionStore } from './fixtures/legacy-companion-db.mjs';
import { CompanionLearning, learningProtocols, rankMemoryCandidates } from '../companions/learning.mjs';
import { dialoguePrompts } from '../companions/dialogue-prompts.mjs';
import { withMemoryEvidence } from './fixtures/memory-evidence.mjs';

const imports = registerHooks({ resolve(specifier, context, next) {
  if(specifier==='@droyagent/memory/store')return {url:new URL('../../memory/store.mjs',import.meta.url).href,shortCircuit:true};
  if(specifier==='@droyagent/memory/memos')return {url:new URL('../../memory/memos/store.mjs',import.meta.url).href,shortCircuit:true};
  return next(specifier,context);
} });
after(()=>imports.deregister());
const { CompanionMemory } = await import('../companions/memory.mjs');
const code = expected => error => error.code===expected;
const emptyLearning = () => ({impressions:[],expressions:[],jargon:[],memories:[]});
const recallWithoutEvidence = record => {
  const {evidence,...value}=record;
  assert.deepEqual(evidence,[]);
  return {...value,evidenceCount:0,evidenceExcerpts:[],evidenceTruncated:false};
};

async function fixture(t,{respond,options={},locale='en'}={}) {
  const directory=await mkdtemp(join(tmpdir(),'droy-companion-learning-'));
  const store=await openLegacyCompanionStore({directory}),memories=new Map(),calls=[];
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
      const answer=await (respond ? respond(call) : input.suppliedMemories ? JSON.stringify(emptyLearning()) : 'Fictional unfinished topic retained.');
      if(input.summary !== undefined) {
        const sourceIds=[...new Set([...input.messages.map(message=>message.id),...Object.values(input.summaryState ?? {}).flatMap(entries=>entries.flatMap(entry=>entry.sourceIds))])];
        return JSON.stringify({topics:[],background:[{text:answer,sourceIds}],progress:[],pending:[],agreements:[]});
      }
      return input.suppliedMemories?withMemoryEvidence(answer,input.messages):answer;
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
  assert.deepEqual((await(await f.memoryFor('kaka')).query()).items[0].sourceRefs,['im:kaka:first','im:kaka:second']);
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
    assert.deepEqual(selected.items,[recallWithoutEvidence(kakaRecord)]);
    assert.equal(selected.items[0].revisionId,kakaRecord.revisionId);
    const other=await f.learning.recall('maimai',[{role:'user',text:'Could you remind me of my name?'}]);
    assert.deepEqual(other.items,[recallWithoutEvidence(maimaiRecord)]);
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
  assert.deepEqual(result.items,[recallWithoutEvidence(oldRecord)]);
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

test('explicit recall expands synonyms across older records and applies the requested time window',async t=>{
  let old;
  const f=await fixture(t,{locale:'zh',options:{semanticRecall:true,memoryCandidates:3},respond:({input,system,learningStage})=>{
    if(learningStage==='memory_query') {
      assert.equal(system,learningProtocols.zh.query);
      assert.equal(input.query,'我以前说周末喜欢爬山吗？');
      return JSON.stringify({terms:['登山','徒步'],kinds:['preference']});
    }
    assert.equal(system,learningProtocols.zh.recall);
    assert.ok(input.candidates.some(item=>item.recordId===old.recordId));
    assert.ok(!input.candidates.some(item=>item.text.includes('outside requested range')));
    return JSON.stringify({recordIds:[old.recordId]});
  }});
  const memory=await f.memoryFor('kaka');
  old=(await memory.remember({kind:'preference',text:'虚构用户小林喜欢在周末登山。',occurredAt:'2025-01-01T00:00:00Z'})).record;
  for(let i=0;i<8;i++)await memory.remember({text:'Fictional unrelated recent record '+i,occurredAt:new Date(Date.UTC(2026,0,i+1)).toISOString()});
  await memory.remember({kind:'preference',text:'Fictional 登山 outside requested range',occurredAt:'2026-10-01T00:00:00Z'});
  const result=await f.learning.recall('kaka',[],{query:'我以前说周末喜欢爬山吗？',from:'2024-01-01T00:00:00Z',to:'2026-02-01T00:00:00Z'});
  assert.deepEqual(result.items,[recallWithoutEvidence(old)]);
  assert.equal(f.calls.length,2,'one query plan and one semantic selection');
});

test('invalid query plans cannot broaden recall to unsupported kinds or invented records',async t=>{
  const f=await fixture(t,{options:{semanticRecall:true},respond:()=>JSON.stringify({terms:['coffee'],kinds:['other-companion']})});
  await(await f.memoryFor('kaka')).remember({text:'A fictional coffee preference'});
  await assert.rejects(f.learning.recall('kaka',[],{query:'What drink do I like?'}),code('COMPANION_INVALID_RECALL'));
  assert.equal(f.calls.length,1);
});

test('fact progress survives language failure and later facts continue independently before language recovers',async t=>{
  const f=await fixture(t,{respond:({input})=>JSON.stringify({...emptyLearning(),memories:input.messages.filter(message=>message.role==='user').map(message=>({
    kind:'fact',text:message.text,sourceIds:[message.id]}))})});
  const generate=f.learning.generate;
  let broken=true;
  f.learning.generate=(profile,system,input,options)=>{
    if(options.learningStage==='learn_style'&&broken)throw Object.assign(new Error('Fictional style service failure'),{code:'COMPANION_INVALID_LEARNING'});
    return generate(profile,system,input,options);
  };
  await f.append('fact-one','Fictional first remembered fact');
  await assert.rejects(f.learning.maintain('kaka'),code('COMPANION_INVALID_LEARNING'));
  let state=await f.store.getState('kaka');
  assert.equal(state.factsThroughMessageId,'fact-one');
  assert.equal(state.learningCursor,undefined);
  assert.equal(state.languageLearningStatus.status,'failed');
  await f.append('fact-two','Fictional second remembered fact');
  await assert.rejects(f.learning.maintain('kaka'),code('COMPANION_INVALID_LEARNING'));
  state=await f.store.getState('kaka');
  assert.equal(state.factsThroughMessageId,'fact-two');
  assert.equal((await(await f.memoryFor('kaka')).query()).total,2);
  broken=false;
  await f.learning.maintain('kaka');
  state=await f.store.getState('kaka');
  assert.equal(state.learnedThroughMessageId,'fact-two');
  assert.equal(state.languageLearningStatus.status,'ready');
  assert.equal((await(await f.memoryFor('kaka')).query()).total,2,'language recovery does not duplicate facts');
});

test('structured summaries preserve grounded quotes and reject invented source references',async t=>{
  for(const invalid of [false,true])await t.test(String(invalid),async child=>{
    const f=await fixture(child);
    for(let i=0;i<6;i++)await f.append('structured-'+i,'Fictional exact source '+i);
    f.learning.generate=async(_profile,system,input)=>{
      assert.ok(system.includes(learningProtocols.en.summary));
      return JSON.stringify({topics:[{text:'Fictional open conversation',sourceIds:[invalid?'invented':'structured-0'],quote:'Fictional exact source 0'}],
        background:[],progress:[],pending:[{text:'Fictional unfinished discussion',sourceIds:['structured-1']}],agreements:[]});
    };
    if(invalid) {
      await assert.rejects(f.learning.maintain('kaka',{learn:false}),code('COMPANION_LEARNING_SOURCE_REQUIRED'));
      assert.equal((await f.store.getState('kaka')).summaryCursor,undefined);
    } else {
      await f.learning.maintain('kaka',{learn:false});
      const state=await f.store.getState('kaka');
      assert.equal(state.summaryState.topics[0].quote,'Fictional exact source 0');
      assert.deepEqual(state.summaryState.pending[0].sourceIds,['structured-1']);
      assert.match(state.summary,/Fictional exact source 0/);
      assert.equal(state.summaryRevision,0);
    }
  });
});

test('grounded events support completion and disappear from proactive context when disabled or excluded',async t=>{
  let complete=false;
  const f=await fixture(t,{respond:({input})=>JSON.stringify({...emptyLearning(),events:[{
    action:complete?'replace':'add',...(complete?{targetId:input.existing.events[0].id}:{}),
    kind:'event',text:'Fictional interview',status:complete?'completed':'pending',dueAt:'2026-11-01T09:00:00+08:00',
    sourceIds:[input.messages.filter(message=>message.role==='user').at(-1).id],
  }]})});
  await f.append('event-source','Fictional interview on November 1 at 09:00 +08:00');
  await f.learning.maintain('kaka');
  let events=await f.learning.activeEvents('kaka');
  assert.equal(events.length,1);
  assert.equal(events[0].dueAt,'2026-11-01T01:00:00.000Z');
  const eventRecordId=events[0].memoryRecordId,memory=await f.memoryFor('kaka');
  assert.ok(eventRecordId);
  assert.equal((await memory.get(eventRecordId)).kind,'event');
  assert.equal((await memory.query({text:'Fictional interview'})).total,1,'events are discoverable by recall and forget');
  await f.store.updateProfile('kaka',{settings:{memoryEnabled:false}});
  assert.deepEqual(await f.learning.activeEvents('kaka'),[]);
  await f.store.updateProfile('kaka',{settings:{memoryEnabled:true}});
  complete=true;
  await f.append('event-complete','Fictional interview is completed');
  await f.learning.maintain('kaka');
  assert.deepEqual(await f.learning.activeEvents('kaka'),[]);
  assert.equal((await f.learning.activeEvents('kaka',{includeClosed:true}))[0].status,'completed');
  const completed=await memory.get(eventRecordId);
  assert.match(completed.payload.text,/completed/);
  assert.deepEqual(completed.sourceRefs,['im:kaka:event-complete','im:kaka:event-source']);
  assert.equal((await memory.query({text:'Fictional interview'})).total,1,'completion revises the event instead of leaving an active duplicate');
  await f.store.excludeDerivedMessages('kaka',['event-complete']);
  assert.deepEqual(await f.learning.activeEvents('kaka',{includeClosed:true}),[]);
});

test('a single meaningful user message saves facts and events before the language batch is full',async t=>{
  const f=await fixture(t,{options:{learningUserMessages:6},respond:({input})=>JSON.stringify({...emptyLearning(),
    memories:[{kind:'fact',text:'Fictional appointment tomorrow',sourceIds:['single-event']}],
    events:[{kind:'event',text:'Fictional appointment tomorrow',status:'pending',sourceIds:['single-event']}],
  })});
  await f.append('single-event','I have a fictional appointment tomorrow');
  const result=await f.learning.maintain('kaka');
  assert.equal(result.factsThrough,'single-event');
  assert.equal((await f.learning.activeEvents('kaka')).length,1);
  assert.equal((await f.store.getState('kaka')).learningCursor,undefined);
  assert.equal(f.calls.filter(call=>call.learningStage).length,0,'language learning still waits for its own batch');
});

test('event revision reconciles a committed memory after the state write fails without a receipt conflict',async t=>{
  let completed=false;
  const f=await fixture(t,{respond:({input})=>JSON.stringify({...emptyLearning(),events:[{
    action:completed?'replace':'add',...(completed?{targetId:input.existing.events[0].id}:{}),
    kind:'event',text:'Fictional interview',status:completed?'completed':'pending',
    sourceIds:[completed?'completed-source':'initial-source'],
  }]})});
  await f.append('initial-source','Fictional interview is pending');
  await f.learning.maintain('kaka');
  const event=(await f.learning.activeEvents('kaka'))[0],memory=await f.memoryFor('kaka');
  completed=true;
  await f.append('completed-source','The fictional interview is complete');
  const update=f.store.updateState.bind(f.store);
  let failOnce=true;
  f.store.updateState=async(id,patch)=>{
    if(failOnce&&patch.events?.some(entry=>entry.status==='completed')) {
      failOnce=false;
      throw new Error('Fictional interrupted state persistence');
    }
    return update(id,patch);
  };
  await assert.rejects(f.learning.maintain('kaka'),/Fictional interrupted state persistence/);
  const saved=await memory.get(event.memoryRecordId);
  assert.match(saved.payload.text,/completed/);
  assert.equal((await f.learning.activeEvents('kaka')).length,1,'state still reflects the interrupted pre-commit state');
  await f.learning.maintain('kaka');
  assert.deepEqual(await f.learning.activeEvents('kaka'),[]);
  assert.equal((await memory.get(event.memoryRecordId)).revisionId,saved.revisionId,'already committed event revision is not repeated');
  assert.equal((await memory.query({kinds:['event']})).total,1);
});

test('invalid language proposals in maintenance output do not discard valid sourced facts',async t=>{
  const f=await fixture(t,{respond:()=>JSON.stringify({...emptyLearning(),
    expressions:[{situation:'Fictional expression',expression:'Fictional unsupported phrase',sourceIds:['invented']}],
    memories:[{kind:'fact',text:'Fictional confirmed preference',sourceIds:['valid-source']}],
  })});
  await f.append('valid-source','A fictional confirmed preference');
  await assert.rejects(f.learning.maintain('kaka'),code('COMPANION_LEARNING_SOURCE_REQUIRED'));
  assert.equal((await(await f.memoryFor('kaka')).query()).total,1);
  const state=await f.store.getState('kaka');
  assert.equal(state.factsThroughMessageId,'valid-source');
  assert.equal(state.learningCursor,undefined);
  assert.equal(state.languageLearningStatus.status,'failed');
});

test('source exclusion clears the persisted structured summary as well as its readable text',async t=>{
  const f=await fixture(t);
  for(let i=0;i<6;i++)await f.append('private-summary-'+i,'Fictional private source '+i);
  await f.learning.maintain('kaka',{learn:false});
  assert.ok((await f.store.getState('kaka')).summaryState.background.length);
  await f.store.excludeDerivedMessages('kaka',['private-summary-0']);
  const state=await f.store.getState('kaka');
  assert.equal(state.summary,'');
  assert.equal(state.summaryState,null);
  assert.equal(state.summaryThrough,null);
});

test('automatic memory requires exact user evidence and rejects questions, quoted claims and fabricated excerpts',async t=>{
  for(const invalid of ['missing','fabricated','question','quotation','hypothetical'])await t.test(invalid,async child=>{
    const source=invalid==='question'?'Should I move to a fictional city?':'I prefer fictional tea.';
    const f=await fixture(child,{respond:()=>JSON.stringify({...emptyLearning(),memories:[{
      kind:'preference',text:'The fictional user prefers tea',sourceIds:['source'],
      claimType:['quotation','hypothetical'].includes(invalid)?invalid:'statement',
      evidence:invalid==='missing'?[]:[{sourceId:'source',quote:invalid==='fabricated'?'I prefer fictional coffee.':source}],
    }]})});
    await f.append('source',source);
    await assert.rejects(f.learning.maintain('kaka'),code('COMPANION_LEARNING_EVIDENCE_REQUIRED'));
    assert.equal((await(await f.memoryFor('kaka')).query()).total,0);
    assert.equal((await f.store.getState('kaka')).factLearningCursor,undefined);
  });
});

test('a guessed correction cannot replace a stored fact even with a valid current source',async t=>{
  const f=await fixture(t),memory=await f.memoryFor('kaka');
  const saved=await memory.remember({text:'The fictional user lives in city A',sourceRefs:['old']});
  await f.append('new','I am considering a fictional trip to city B.');
  f.learning.generate=async()=>JSON.stringify({...emptyLearning(),memories:[{action:'revise',recordId:saved.recordId,
    expectedRevisionId:saved.revisionId,text:'The fictional user lives in city B',sourceIds:['new'],claimType:'inference',
    revisionReason:'explicit_update',evidence:[{sourceId:'new',quote:'I am considering a fictional trip to city B.'}]}]});
  await assert.rejects(f.learning.maintain('kaka'),code('COMPANION_LEARNING_EVIDENCE_REQUIRED'));
  assert.equal((await memory.get(saved.recordId)).revisionId,saved.revisionId);
});

test('inferred candidates are retained separately and never supplied as active recall facts',async t=>{
  const f=await fixture(t,{respond:()=>JSON.stringify({...emptyLearning(),memories:[{kind:'preference',text:'Fictional user may prefer quiet cafes',
    sourceIds:['source'],claimType:'inference'}]})});
  await f.append('source','This fictional cafe is quiet.');
  await f.learning.maintain('kaka');
  const memory=await f.memoryFor('kaka'),candidate=(await memory.query({states:['candidate']})).items[0];
  assert.equal(candidate.state,'candidate');
  assert.equal(candidate.assertion,'model_inference');
  assert.deepEqual((await f.learning.recall('kaka',[{role:'user',text:'quiet cafes'}])).items,[]);
});

test('validated quotes persist the canonical user source rather than a model-generated paraphrase',async t=>{
  const source='  My fictional favorite drink is tea. I enjoy it in the morning.  ';
  const f=await fixture(t,{respond:()=>JSON.stringify({...emptyLearning(),memories:[{kind:'preference',text:'Fictional user prefers tea',
    sourceIds:['source'],claimType:'statement',evidence:[{sourceId:'source',quote:'My fictional favorite drink is tea.'}]}]})});
  await f.append('source',source);
  const actual=await f.store.getMessage('kaka','source'),memory=await f.memoryFor('kaka'),remember=memory.remember.bind(memory);
  let input;
  memory.remember=async value=>{input=value;return remember(value);};
  await f.learning.maintain('kaka');
  assert.deepEqual(input.evidence,[{sourceRef:'im:kaka:source',text:actual.text,occurredAt:new Date(actual.createdAt).toISOString(),role:'user'}]);
  assert.equal(input.assertion,'model_inference','a grounded paraphrase is still automatic interpretation');
});

test('events require direct statement evidence before they can affect proactive context',async t=>{
  const f=await fixture(t,{respond:()=>JSON.stringify({...emptyLearning(),events:[{kind:'agreement',text:'Fictional appointment',
    status:'pending',sourceIds:['source'],claimType:'statement',evidence:[{sourceId:'source',quote:'Invented agreement'}]}]})});
  await f.append('source','An unrelated fictional greeting.');
  await assert.rejects(f.learning.maintain('kaka'),code('COMPANION_LEARNING_EVIDENCE_REQUIRED'));
  assert.deepEqual(await f.learning.activeEvents('kaka'),[]);
});

test('a canonical source larger than a memory summary is retained without silent truncation',async t=>{
  const source='My fictional favorite drink is tea.\n'+'Fictional contextual detail. '.repeat(700);
  assert.ok(Buffer.byteLength(source)>16384);
  const f=await fixture(t,{respond:()=>JSON.stringify({...emptyLearning(),memories:[{kind:'preference',text:'Fictional user prefers tea',
    sourceIds:['long-source'],claimType:'statement',evidence:[{sourceId:'long-source',quote:'My fictional favorite drink is tea.'}]}]})});
  await f.append('long-source',source);
  await f.learning.maintain('kaka');
  const memory=await f.memoryFor('kaka'),record=(await memory.query()).items[0];
  assert.equal((await memory.get(record.recordId)).evidence[0].text,source);
});

test('a semantic selection cannot return a record revised while the model was choosing it',async t=>{
  let memory,record;
  const f=await fixture(t,{options:{semanticRecall:true},respond:async({input})=>{
    assert.ok(input.candidates.some(item=>item.recordId===record.recordId));
    await memory.revise({recordId:record.recordId,expectedRevisionId:record.revisionId,text:'Fictional corrected preference'});
    return JSON.stringify({recordIds:[record.recordId]});
  }});
  memory=await f.memoryFor('kaka');record=(await memory.remember({text:'Fictional tea preference'})).record;
  const result=await f.learning.recall('kaka',[{role:'user',text:'tea preference'}]);
  assert.deepEqual(result.items,[]);
  assert.equal(result.total,0);
});

test('scoped engine search supplies semantic candidates while inactive results remain excluded',async t=>{
  const f=await fixture(t,{options:{memoryCandidates:1}}),memory=await f.memoryFor('kaka');
  const old=(await memory.remember({text:'Fictional user enjoys tea',occurredAt:'2020-01-01T00:00:00Z'})).record;
  await memory.remember({text:'Fictional unrelated newest note'});
  const pending=(await memory.remember({text:'Fictional inferred beverage',state:'candidate'})).record;
  let searched;
  memory.search=async args=>{searched=args;return {items:[old,pending],total:2};};
  const result=await f.learning.recall('kaka',[],{query:'My preferred beverage'});
  assert.equal(searched.query,'My preferred beverage');
  assert.deepEqual(searched.states,['active']);
  assert.deepEqual(result.items.map(item=>item.recordId),[old.recordId]);
});

test('recent and text query results cannot reintroduce facts outside their validity window',async t=>{
  const f=await fixture(t),memory=await f.memoryFor('kaka'),now=Date.now();
  const expired=await memory.remember({text:'Fictional coffee expired preference'});
  await memory.revise({recordId:expired.recordId,expectedRevisionId:expired.revisionId,patch:{validUntil:new Date(now-60_000).toISOString()}});
  const future=await memory.remember({text:'Fictional coffee future preference'});
  await memory.revise({recordId:future.recordId,expectedRevisionId:future.revisionId,patch:{validFrom:new Date(now+60_000).toISOString()}});
  const active=await memory.remember({text:'Fictional coffee active preference'});
  const result=await f.learning.recall('kaka',[{role:'user',text:'coffee preference'}]);
  assert.deepEqual(result.items.map(record=>record.recordId),[active.recordId]);
});

test('recall rechecks validity at selection completion even without a revision change',async t=>{
  let memory,record,expire=false;
  const f=await fixture(t,{options:{semanticRecall:true},respond:({input})=>{
    assert.ok(input.candidates.some(candidate=>candidate.recordId===record.recordId));
    expire=true;return JSON.stringify({recordIds:[record.recordId]});
  }});
  memory=await f.memoryFor('kaka');record=(await memory.remember({text:'Fictional coffee preference'})).record;
  const get=memory.get.bind(memory);
  memory.get=async id=>{const current=await get(id);return expire?{...current,validUntil:new Date(Date.now()-1).toISOString()}:current;};
  assert.deepEqual((await f.learning.recall('kaka',[{role:'user',text:'coffee'}])).items,[]);
});


test('recall bounds canonical proof in selector input and final records without changing stored sources',async t=>{
  for(const locale of ['zh','en'])for(const semanticRecall of [true,false])await t.test(locale+' '+semanticRecall,async child=>{
    const budget=1800,source='My fictional favorite drink is tea.\n'+'Fictional contextual detail. '.repeat(6000);
    assert.ok(Buffer.byteLength(source)>150000);
    const f=await fixture(child,{locale,options:{semanticRecall,memoryBytes:budget},respond:({system,input})=>{
      assert.equal(system,learningProtocols[locale].recall);
      assert.match(system,locale==='zh'?/关于其他人的描述不能当作用户自身事实/:/descriptions of other people are not facts about the user/);
      assert.ok(Buffer.byteLength(JSON.stringify(input.candidates))<=budget);
      for(const candidate of input.candidates) {
        assert.equal(candidate.assertion,'model_inference');
        assert.equal(candidate.evidenceCount,1);assert.equal(candidate.evidenceTruncated,true);
        assert.equal(candidate.evidence,undefined);
        assert.equal(candidate.evidenceExcerpts[0].truncated,true);
        assert.ok(Buffer.byteLength(candidate.evidenceExcerpts[0].text)<=512);
        assert.ok(source.startsWith(candidate.evidenceExcerpts[0].text));
      }
      return JSON.stringify({recordIds:input.candidates.map(candidate=>candidate.recordId)});
    }});
    const memory=await f.memoryFor('kaka'),stored=[];
    for(let index=0;index<2;index++) {
      const sourceRef=memory.sourceRef('long-proof-'+index);
      stored.push((await memory.remember({text:'Fictional tea preference '+index,assertion:'model_inference',sourceRefs:[sourceRef],
        evidence:[{sourceRef,text:source,role:'user',occurredAt:'2026-10-01T00:00:00Z'}]})).record);
    }
    const result=await f.learning.recall('kaka',[{role:'user',text:'Fictional tea preference'}]);
    assert.ok(result.items.length>0);assert.ok(result.items.length<stored.length,'The aggregate budget includes source excerpts and record metadata');
    assert.ok(Buffer.byteLength(JSON.stringify(result.items))<=budget);
    for(const record of result.items) {
      assert.equal(Object.hasOwn(record,'evidence'),false);
      assert.equal(record.evidenceCount,1);assert.equal(record.evidenceTruncated,true);
      assert.equal(record.assertion,'model_inference');
      assert.equal(record.revisionId,stored.find(item=>item.recordId===record.recordId).revisionId);
      assert.ok(Buffer.byteLength(record.evidenceExcerpts[0].text)<=512);
    }
    for(const record of stored)assert.equal((await memory.get(record.recordId)).evidence[0].text,source);
    assert.equal(f.calls.length,semanticRecall?1:0,'Bounded proof uses the existing selector call');
  });
});
