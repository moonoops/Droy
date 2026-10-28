import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp,rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { CompanionStore } from '../companions/store.mjs';
import { prepareReply,publishReply } from '../companions/delivery.mjs';

const code=expected=>error=>error.code===expected;
async function fixture(t) {
  const directory=await mkdtemp(join(tmpdir(),'droy-companion-delivery-'));
  let store=await CompanionStore.open({directory});
  t.after(async()=>{await store.close();await rm(directory,{recursive:true,force:true});});
  await store.appendMessage('kaka',{id:'source',role:'user',text:'Fictional user question',status:'received'});
  await store.updateState('kaka',{pendingMessageIds:['source'],summary:'Fictional retained context'});
  return {get store(){return store;},async reopen(){await store.close();store=await CompanionStore.open({directory});},
    prepare:(parts=['Fictional first reply','Fictional second reply'])=>prepareReply({store,companionId:'kaka',sourceMessageIds:['source'],parts}),
    publish:(draft,options={})=>publishReply({store,companionId:'kaka',draft,...options})};
}

test('drafts persist fixed message identities, source metadata and only consume their own pending users',async t=>{
  const f=await fixture(t);
  await f.store.appendMessage('kaka',{id:'later-source',role:'user',text:'Fictional newer request',status:'received'});
  await f.store.updateState('kaka',{pendingMessageIds:['source','later-source']});
  const draft=await f.prepare([{type:'text',text:'Fictional answer',replyTo:'source',contextSourceIds:['source'],contextRange:{after:'seq:0',through:'seq:1'}},
    {type:'sticker',text:'',stickerId:'test-sticker-ok'},
    {type:'image',text:'',attachments:[{id:'fictional-image',mediaType:'image/png'}]}]);
  assert.deepEqual(await f.store.getState('kaka'),{pendingMessageIds:['source','later-source'],summary:'Fictional retained context',replyDraft:draft});
  assert.equal(new Set(draft.parts.map(part=>part.id)).size,3);
  assert.ok(draft.parts.every(part=>part.role==='assistant'&&part.status==='sent'&&part.createdAt===draft.createdAt));
  const sent=[],paced=[];
  const result=await f.publish(draft,{beforeSend:({index})=>paced.push(index),onSent:message=>sent.push(message.id)});
  assert.deepEqual(result,{messageIds:draft.parts.map(part=>part.id),newMessageIds:draft.parts.map(part=>part.id)});
  assert.deepEqual(sent,result.newMessageIds);
  assert.deepEqual(paced,[0,1,2]);
  const state=await f.store.getState('kaka');
  assert.equal(state.replyDraft,null);
  assert.deepEqual(state.pendingMessageIds,['later-source']);
  assert.equal((await f.store.getMessage('kaka','source')).status,'sent');
  assert.deepEqual((await f.store.pendingMessages('kaka')).map(message=>message.id),['later-source']);
  assert.equal(state.summary,'Fictional retained context');
  assert.deepEqual((await f.store.getMessage('kaka',result.messageIds[0])).contextSourceIds,['source']);
});

test('restart after a partial delivery only publishes missing parts and calls pacing/hooks for those parts',async t=>{
  const f=await fixture(t),draft=await f.prepare(),crash=new Error('Fictional interruption');
  await assert.rejects(f.publish(draft,{onSent:()=>{throw crash;}}),error=>error===crash);
  assert.equal((await f.store.listMessages('kaka')).messages.filter(message=>message.role==='assistant').length,1);
  await f.reopen();
  const restored=(await f.store.getState('kaka')).replyDraft,sent=[],paced=[];
  assert.deepEqual(restored,draft);
  const result=await f.publish(restored,{beforeSend:({index})=>paced.push(index),onSent:message=>sent.push(message.id)});
  assert.deepEqual(result.messageIds,draft.parts.map(part=>part.id));
  assert.deepEqual(result.newMessageIds,[draft.parts[1].id]);
  assert.deepEqual(sent,result.newMessageIds);
  assert.deepEqual(paced,[1]);
  assert.equal((await f.store.listMessages('kaka')).messages.filter(message=>message.role==='assistant').length,2);
});

test('typo corrections keep their actual preceding bubble reference after restart',async t=>{
  const f=await fixture(t),draft=await f.prepare([
    {type:'text',text:'Fictional typo',replyTo:'source'},
    {type:'text',text:'Fictional correction',replyTo:'source',quotePrevious:true},
  ]);
  assert.equal(draft.parts[1].replyTo,draft.parts[0].id);
  assert.equal(draft.parts[1].quoteReply,false,'A correction source link does not automatically request a visible quote');
  assert.equal(Object.hasOwn(draft.parts[1],'quotePrevious'),false);
  await assert.rejects(f.publish(draft,{onSent:()=>{throw new Error('Fictional crash');}}));
  await f.reopen();
  const resumed=await f.publish((await f.store.getState('kaka')).replyDraft);
  assert.deepEqual(resumed.newMessageIds,[draft.parts[1].id]);
  assert.equal((await f.store.getMessage('kaka',draft.parts[1].id)).replyTo,draft.parts[0].id);
  assert.equal((await f.store.getMessage('kaka',draft.parts[0].id)).replyTo,'source');
});

test('a crash immediately after append but before any progress callback cannot duplicate its bubble',async t=>{
  const f=await fixture(t),draft=await f.prepare(),append=f.store.appendMessage.bind(f.store),crash=new Error('Fictional post-append crash');
  let interrupted=false;
  f.store.appendMessage=async(...args)=>{
    const saved=await append(...args);
    if(!interrupted){interrupted=true;throw crash;}
    return saved;
  };
  await assert.rejects(f.publish(draft),error=>error===crash);
  await f.reopen();
  const result=await f.publish((await f.store.getState('kaka')).replyDraft);
  assert.deepEqual(result.newMessageIds,[draft.parts[1].id]);
  assert.equal((await f.store.listMessages('kaka')).messages.filter(message=>message.role==='assistant').length,2);
});

test('restart after all parts were appended but before finalization clears pending without republishing',async t=>{
  const f=await fixture(t),draft=await f.prepare(),crash=new Error('Fictional pre-finalize crash');
  f.store.completeInputs=async()=>{throw crash;};
  await assert.rejects(f.publish(draft),error=>error===crash);
  assert.equal((await f.store.listMessages('kaka')).messages.filter(message=>message.role==='assistant').length,2);
  await f.reopen();
  const result=await f.publish((await f.store.getState('kaka')).replyDraft,{
    beforeSend:()=>assert.fail('already published bubbles must not be paced'),
    onSent:()=>assert.fail('already published bubbles must not notify again'),
  });
  assert.deepEqual(result.messageIds,draft.parts.map(part=>part.id));
  assert.deepEqual(result.newMessageIds,[]);
  assert.equal((await f.store.getState('kaka')).replyDraft,null);
  assert.deepEqual((await f.store.getState('kaka')).pendingMessageIds,[]);
  assert.deepEqual(await f.store.pendingMessages('kaka'),[]);
  await f.reopen();
  assert.deepEqual(await f.store.pendingMessages('kaka'),[]);
});

test('cancellation between bubbles preserves the draft and can resume without duplicates',async t=>{
  const f=await fixture(t),draft=await f.prepare(),controller=new AbortController();
  await assert.rejects(f.publish(draft,{signal:controller.signal,onSent:()=>controller.abort()}),{name:'AbortError'});
  assert.equal((await f.store.getState('kaka')).replyDraft.id,draft.id);
  const result=await f.publish(draft);
  assert.deepEqual(result.newMessageIds,[draft.parts[1].id]);
  assert.equal((await f.store.listMessages('kaka')).messages.filter(message=>message.role==='assistant').length,2);
});

test('forgotten, deleted, reset or explicitly excluded sources cannot publish a saved draft',async t=>{
  for(const change of ['forget','delete','baseline','legacy-forget'])await t.test(change,async child=>{
    const f=await fixture(child),draft=await f.prepare();
    if(change==='forget')await f.store.excludeDerivedMessages('kaka',['source']);
    if(change==='delete')await f.store.removeMessage('kaka','source');
    if(change==='baseline')await f.store.updateState('kaka',{forgottenThrough:await f.store.getMessageCursor('kaka','source')});
    if(change==='legacy-forget')await f.store.updateState('kaka',{forgottenMessageIds:['source']});
    await assert.rejects(f.publish(draft),code('COMPANION_REPLY_STALE'));
    assert.equal((await f.store.listMessages('kaka')).messages.filter(message=>message.role==='assistant').length,0);
  });
});

test('superseding or clearing a draft, including during typing delay, prevents old sends',async t=>{
  const f=await fixture(t),old=await f.prepare(),newer=await f.prepare(['Fictional replacement']);
  await assert.rejects(f.publish(old),code('COMPANION_REPLY_STALE'));
  await assert.rejects(f.publish(newer,{beforeSend:()=>f.store.updateState('kaka',{replyDraft:null})}),code('COMPANION_REPLY_STALE'));
  assert.equal((await f.store.listMessages('kaka')).messages.filter(message=>message.role==='assistant').length,0);
});

test('source cursor lookup and drafts remain scoped to one companion',async t=>{
  const f=await fixture(t);
  assert.equal(await f.store.getMessageCursor('kaka','source'),'seq:1');
  assert.equal(await f.store.getMessageCursor('maimai','source'),null);
  await assert.rejects(prepareReply({store:f.store,companionId:'maimai',sourceMessageIds:['source'],parts:['Fictional forbidden reply']}),code('COMPANION_REPLY_STALE'));
  const draft=await f.prepare();
  await assert.rejects(publishReply({store:f.store,companionId:'maimai',draft}),code('COMPANION_REPLY_STALE'));
  await f.store.removeMessage('kaka','source');
  assert.equal(await f.store.getMessageCursor('kaka','source'),null);
});

test('overlapping publishers cannot repeat new-message callbacks for the same draft',async t=>{
  const f=await fixture(t),draft=await f.prepare(),sent=[];
  const results=await Promise.allSettled([f.publish(draft,{onSent:message=>sent.push(message.id)}),f.publish(draft,{onSent:message=>sent.push(message.id)})]);
  assert.equal(results[0].status,'fulfilled');
  assert.equal(results[1].status,'rejected');
  assert.equal(results[1].reason.code,'COMPANION_REPLY_STALE');
  assert.deepEqual(sent,draft.parts.map(part=>part.id));
});
