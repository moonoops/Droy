import test from 'node:test';
import assert from 'node:assert/strict';
import {buildProactiveContext,parseProactiveDecision,proactiveNextCheckAt,proactiveDecisionPrompt,proactivePrompts,PROACTIVE_CONTEXT_MAX_BYTES} from '../companions/proactive-policy.mjs';

const now=Date.parse('2026-10-30T10:00:00Z'),profile={id:'fictional-companion',name:'Fictional companion',personality:'Calm and curious'};
const message=(id,at,text='Fictional shared interest in hiking')=>({id,role:'user',type:'text',text,createdAt:new Date(at).toISOString()});
const build=options=>buildProactiveContext({profile,now,timeZone:'Asia/Shanghai',...options});
const decision=overrides=>({action:'contact',kind:'check_in',reason:'Continue the shared hiking topic',sourceIds:['user-one'],nextCheckMinutes:45,...overrides});

test('observed local-day activity and contact cadence adapt to frequent conversation without daily quotas',()=>{
  const sparse=build({messages:[message('user-one',now-60*60_000)]});
  const frequent=build({messages:Array.from({length:36},(_,index)=>message('m-'+index,now-(index+1)*10*60_000))});
  assert.equal(frequent.activity.observedUserMessagesToday,36);
  assert.ok(frequent.suggestedNextCheckMinutes<sparse.suggestedNextCheckMinutes);
  const midnight=build({messages:[message('before',Date.parse('2026-10-29T15:59:00Z')),message('after',Date.parse('2026-10-29T16:01:00Z'))]});
  assert.equal(midnight.activity.observedUserMessagesToday,1);
  assert.equal(midnight.activity.observedActiveDays,2);
  assert.equal('dailyQuota' in frequent,false);
});

test('unanswered contact lengthens reconsideration; a new user message restores responsive cadence',()=>{
  const messages=[message('user-one',now-60*60_000)],lastContactAt=new Date(now-30*60_000).toISOString();
  const before=build({messages});
  const ignored=build({messages,state:{proactiveLastContactAt:lastContactAt,proactiveUnansweredCount:2}});
  assert.equal(ignored.contact.unansweredCount,2);
  assert.ok(ignored.suggestedNextCheckMinutes>before.suggestedNextCheckMinutes);
  assert.equal(parseProactiveDecision(decision({nextCheckMinutes:240}),ignored).action,'contact');
  const answered=build({messages:[...messages,message('answer',now-10*60_000)],state:{proactiveLastContactAt:lastContactAt,proactiveUnansweredCount:2}});
  assert.equal(answered.contact.unansweredCount,0);
  assert.ok(answered.suggestedNextCheckMinutes<ignored.suggestedNextCheckMinutes);
});

test('contact requires current own evidence; failed, forgotten and foreign material cannot supply it',()=>{
  const context=build({messages:[message('user-one',now-60*60_000),{...message('forgotten',now-50*60_000),contextExcluded:true},
    {...message('failed',now-40*60_000),status:'failed'}],memories:[{recordId:'own-memory',companionId:profile.id,payload:{text:'Fictional verified preference'},state:'active'}]});
  assert.deepEqual(context.evidence.map(item=>item.id),['user-one','memory:own-memory']);
  for(const sourceIds of [[],['forgotten'],['failed'],['invented'],['im:other:secret']])
    assert.throws(()=>parseProactiveDecision(decision({sourceIds}),context),{code:'COMPANION_INVALID_PROACTIVE_DECISION'});
  assert.equal(parseProactiveDecision(decision({sourceIds:['memory:own-memory']}),context).action,'contact');
  assert.throws(()=>build({memories:[{recordId:'foreign',companionId:'other',payload:{text:'Do not leak'}}]}),{code:'COMPANION_SOURCE_SCOPE'});
});

test('event decisions require grounded due candidates and exclude ended or expired events',()=>{
  const messages=[message('user-one',now-2*60*60_000)],base={text:'Fictional interview follow-up',sourceIds:['user-one'],status:'pending'};
  const context=build({messages,events:[{...base,id:'due',dueAt:new Date(now-60_000).toISOString()},
    {...base,id:'future',dueAt:new Date(now+15*60_000).toISOString()},
    {...base,id:'cancelled',status:'cancelled'},{...base,id:'completed',status:'completed'},
    {...base,id:'expired',expiresAt:new Date(now-60_000).toISOString()},
    {...base,id:'unsupported',sourceIds:['missing']} ]});
  assert.deepEqual(context.candidates.map(item=>item.id),['due','future']);
  assert.equal(parseProactiveDecision(decision({kind:'event',candidateId:'due'}),context).candidateId,'due');
  for(const candidateId of ['future','cancelled','completed','expired','unsupported','invented'])
    assert.throws(()=>parseProactiveDecision(decision({kind:'event',candidateId}),context),{code:'COMPANION_INVALID_PROACTIVE_DECISION'});
  assert.equal(proactiveNextCheckAt(context,decision({nextCheckMinutes:120})),new Date(now+15*60_000).toISOString());
});

test('an old supplied source for an active event survives the recent conversation window',()=>{
  const messages=Array.from({length:100},(_,index)=>message('m-'+index,now-(101-index)*60_000));
  const context=build({messages,events:[{id:'old-event',text:'A sourced future agreement',sourceIds:['m-0'],status:'pending'}]});
  assert.equal(context.candidates[0].id,'old-event');
  assert.ok(context.evidence.some(item=>item.id==='m-0'));
  assert.equal(context.recentConversation.length,24);
});

test('malformed model output is surfaced and next checks cannot create hot loops or unbounded timers',()=>{
  const context=build({messages:[message('user-one',now-60_000)]});
  for(const raw of ['not json',null,[],decision({action:'send_forever'}),decision({nextCheckMinutes:0}),decision({nextCheckMinutes:Infinity}),decision({reason:''})])
    assert.throws(()=>parseProactiveDecision(raw,context),{code:'COMPANION_INVALID_PROACTIVE_DECISION'});
  assert.equal(parseProactiveDecision(JSON.stringify(decision({nextCheckMinutes:0.01})),context).nextCheckMinutes,5);
  assert.equal(parseProactiveDecision(decision({nextCheckMinutes:99999}),context).nextCheckMinutes,1440);
  const quiet=parseProactiveDecision(decision({action:'wait',sourceIds:[],reason:'No natural reason to interrupt',nextCheckMinutes:120}),context);
  assert.equal(quiet.action,'wait');
  assert.equal(proactiveNextCheckAt(context,quiet),new Date(now+120*60_000).toISOString());
});

test('other companion coordination contains only time and bilingual protocols use the same machine fields',()=>{
  const at=new Date(now-60_000).toISOString(),context=build({recentOtherContactAt:at});
  assert.equal(context.contact.recentOtherContactAt,at);
  assert.deepEqual(Object.keys(proactivePrompts).sort(),['en','zh']);
  for(const locale of ['zh','en'])for(const key of ['action','kind','reason','sourceIds','candidateId','nextCheckMinutes'])
    assert.ok(proactiveDecisionPrompt(locale).includes('"'+key+'"'));
  assert.equal(context.evidence.length,0);
  assert.equal(parseProactiveDecision(decision({action:'wait',sourceIds:[]}),context).action,'wait');
});

test('long bilingual messages are bounded snippets while activity and candidate source identities stay intact',()=>{
  const long='虚构长消息😀'.repeat(2000),messages=Array.from({length:1000},(_,index)=>message('long-'+index,now-(1000-index)*60_000,long));
  const memories=Array.from({length:80},(_,index)=>({recordId:'record-'+index,companionId:profile.id,kind:'preference',payload:{text:long}}));
  const context=build({profile:{...profile,personality:{zh:long,en:'Fictional long persona. '.repeat(2000)},behaviorStyle:long,replyStyle:long},
    messages,memories,events:[{id:'grounded-event',status:'pending',text:long,sourceIds:['long-0','memory:record-79'],dueAt:null}]});
  assert.ok(Buffer.byteLength(JSON.stringify(context))<=PROACTIVE_CONTEXT_MAX_BYTES);
  assert.equal(context.activity.observedUserMessagesSevenDays,1000);
  assert.equal(context.truncated,true);
  assert.deepEqual(context.candidates[0].sourceIds,['long-0','memory:record-79']);
  assert.equal(context.candidates[0].dueAt,null);
  assert.equal(context.candidates[0].truncated,true);
  const known=new Set(context.evidence.map(item=>item.id));
  assert.ok(known.has('long-0'));assert.ok(known.has('memory:record-79'));
  assert.ok(context.evidence.some(item=>item.kind==='preference'));
  for(const item of context.evidence) {
    assert.equal(item.text.isWellFormed(),true);
    assert.ok(Buffer.byteLength(item.text)<=512);
    assert.equal(item.truncated,true);
  }
  for(const item of context.recentConversation)if(item.evidenceId) {
    assert.ok(known.has(item.evidenceId));assert.equal(item.text,undefined);
  }
  assert.equal(parseProactiveDecision(decision({kind:'event',candidateId:'grounded-event',sourceIds:['long-0','memory:record-79']}),context).candidateId,'grounded-event');
});

test('serialized budget accounts for JSON escaping and never leaves partially admitted candidate sources',()=>{
  const long='\u0001"\\'.repeat(10000),messages=Array.from({length:120},(_,index)=>message('escaped-'+index,now-(index+1)*60_000,long));
  const events=Array.from({length:30},(_,index)=>({id:'event-'+index,text:long,status:'pending',sourceIds:['escaped-'+index,'escaped-'+(index+30)]}));
  const context=build({messages,events});
  assert.ok(Buffer.byteLength(JSON.stringify(context))<=PROACTIVE_CONTEXT_MAX_BYTES);
  assert.equal(context.truncated,true);
  assert.equal(context.activity.observedUserMessagesSevenDays,120);
  assert.ok(context.candidates.length>0);assert.ok(context.candidates.length<events.length);
  const known=new Set(context.evidence.map(item=>item.id));
  for(const candidate of context.candidates)assert.ok(candidate.sourceIds.every(id=>known.has(id)));
});

test('deduplicating short text with long message IDs cannot grow a full context beyond its budget',()=>{
  const messages=Array.from({length:100},(_,index)=>message('long-id-'+String(index).padStart(3,'0')+'x'.repeat(140),now-(index+1)*60_000,'好'));
  const memories=Array.from({length:80},(_,index)=>({recordId:'record-'+index,companionId:profile.id,kind:'preference',payload:{text:'long fictional memory '.repeat(1000)}}));
  const context=build({messages,memories});
  assert.ok(Buffer.byteLength(JSON.stringify(context))<=PROACTIVE_CONTEXT_MAX_BYTES);
  assert.equal(context.activity.observedUserMessagesSevenDays,100);
});
