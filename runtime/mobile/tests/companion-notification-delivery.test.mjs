import test,{after} from 'node:test';
import assert from 'node:assert/strict';
import {mkdtemp,rm} from 'node:fs/promises';
import {tmpdir} from 'node:os';
import {join} from 'node:path';
import {createRequire,registerHooks} from 'node:module';
import {pathToFileURL} from 'node:url';
import {createServer} from 'node:http';
import {CompanionStore} from '../companions/store.mjs';
import {openLegacyCompanionStore} from './fixtures/legacy-companion-db.mjs';
import {prepareReply} from '../companions/delivery.mjs';

const installed=createRequire(new URL('../../dsh/package.json',import.meta.url));
const hooks=registerHooks({resolve(specifier,context,next){
  if(specifier==='@droyagent/memory/memos')return {url:new URL('../../memory/memos/store.mjs',import.meta.url).href,shortCircuit:true};
  if(specifier==='@droyagent/memory/store')return {url:new URL('../../memory/store.mjs',import.meta.url).href,shortCircuit:true};
  if(specifier.startsWith('@deepseek-ai/'))return {url:pathToFileURL(installed.resolve(specifier)).href,shortCircuit:true};
  return next(specifier,context);
}});
after(()=>hooks.deregister());
const [{CompanionRuntime},{createCompanionHandler,companionNotificationRequest}]=await Promise.all([
  import('../companions/runtime.mjs'),import('../companions/host.mjs'),
]);

async function fixture(t,{locale='en',quiet=false}={}) {
  const directory=await mkdtemp(join(tmpdir(),'droy-notification-delivery-'));
  let store=await openLegacyCompanionStore({directory}),respond=()=>({status:'published'});
  const notices=[],warnings=[],events=[],state={phase:'idle',revision:0,pending:[],admitted:new Set(),chatCancelRevision:0};
  const runtime=Object.create(CompanionRuntime.prototype);
  Object.assign(runtime,{store,directory,closed:false,lifetime:new AbortController(),background:new Set(),
    options:{},notificationDeliveries:new Map(),instanceStarts:new Map(),contextSources:new Map(),
    state:()=>state,beginChat:()=>()=>{},locale:()=>locale,emit:(...args)=>events.push(args),
    ctx:{logger:{warn:value=>warnings.push(value)}},
    clock:async()=>({currentTime:quiet?'2026-10-30T23:30:00+08:00':'2026-10-30T12:30:00+08:00',timeZone:'Asia/Shanghai'}),
    scheduleWait:()=>{},scheduleProactive:()=>{},
    notify:async(value,signal)=>{
      signal?.throwIfAborted();
      const request=value.operation==='companion'?companionNotificationRequest(value,await store.getProfile(value.companionId),await runtime.clock()):value;
      notices.push(structuredClone(request));
      return respond(request,signal);
    },
  });
  t.after(async()=>{runtime.lifetime.abort();await Promise.allSettled([...runtime.background]);await store.close();await rm(directory,{recursive:true,force:true});});
  return {runtime,notices,warnings,events,state,get store(){return store;},set respond(value){respond=value;},
    async reopen(){await store.close();store=await CompanionStore.open({directory});runtime.store=store;},
    async draft(parts,extra={}){return prepareReply({store,companionId:'kaka',sourceMessageIds:[],parts,...extra});},
  };
}

test('one text reply retains its full preview and mixed media share one alert with ordered committed identities',async t=>{
  const f=await fixture(t),sticker=f.runtime.catalog()[0].id;
  const text='A fictional reply longer than the old preview limit. '.repeat(6);
  const draft=await f.draft([{type:'text',text},{type:'sticker',text:'',stickerId:sticker},
    {type:'image',text:'',attachments:[{id:'fictional-image',mediaType:'image/png'}]}]);
  const delivered=await f.runtime.publish('kaka',draft);
  assert.equal(delivered.messageIds.length,3);
  assert.deepEqual(f.notices.map(value=>value.alert),[true,false,false]);
  assert.deepEqual(f.notices.map(value=>value.sequence),[1,2,3]);
  assert.deepEqual(f.notices.map(value=>value.messageId),delivered.messageIds);
  assert.deepEqual(f.notices.map(value=>value.text),[text,'[Sticker]','[Image]']);
  assert.ok(f.notices.every(value=>value.operation==='companion'&&value.sentAt>0&&value.quiet===false&&value.unreadCount===3&&value.avatar.startsWith('/droy/companions/')));
  assert.equal((await f.store.getState('kaka')).pendingNotificationIds,undefined);
});

test('standalone emoji and media previews have paired localized summaries without generating extra chat text',async t=>{
  for(const locale of ['zh','en'])await t.test(locale,async child=>{
    const f=await fixture(child,{locale}),sticker=f.runtime.catalog()[0];
    f.runtime.profileForUse=id=>f.store.getProfile(id);f.runtime.context=async()=>({messages:[]});
    f.runtime.generate=async()=>'{"emoji_index":1}';
    const sent=await f.runtime.sendEmoji({kind:'chat',companionId:'kaka',sessionId:'fixture-chat'},{});
    assert.equal(sent.delivered,true);assert.equal(sent.stickerId,sticker.id);
    assert.equal((await f.store.listMessages('kaka')).messages[0].text,'');
    for(const type of ['voice','file','image'])await f.runtime.publish('kaka',await f.draft([{type,text:''}]));
    assert.deepEqual(f.notices.map(value=>value.text),locale==='zh'?['[表情]','[语音]','[文件]','[图片]']:['[Sticker]','[Voice message]','[File]','[Image]']);
    assert.ok(f.notices.every(value=>value.alert===true));
  });
});

test('quiet hours preserve already committed messages as silent notices while proactive publication stays blocked',async t=>{
  const f=await fixture(t,{quiet:true});
  await f.runtime.publish('kaka',await f.draft(['A fictional user-requested reply.']));
  assert.equal(f.notices.length,1);assert.equal(f.notices[0].quiet,true);
  await f.store.updateProfile('kaka',{settings:{proactiveEnabled:true}});
  const suppressed=await f.runtime.publish('kaka',await f.draft(['An unsent fictional proactive reply.'],{proactive:{sourceIds:[]}}));
  assert.equal(suppressed.suppressed,true);
  assert.equal(f.notices.length,1);assert.equal((await f.store.listMessages('kaka')).messages.length,1);
});

test('a committed message survives bridge failure and startup restores the same pending notice silently',async t=>{
  const f=await fixture(t),draft=await f.draft(['A fictional durable reply.']);
  f.respond=()=>{throw Object.assign(new Error('Fictional bridge outage'),{code:'FICTIONAL_BRIDGE_OFFLINE'});};
  const result=await f.runtime.publish('kaka',draft);
  assert.equal(result.notification.status,'failed');assert.equal((await f.store.getState('kaka')).replyDraft,null);
  assert.deepEqual((await f.store.getState('kaka')).pendingNotificationIds,result.messageIds);
  assert.deepEqual(f.warnings,['COMPANION_NOTICE_FAILED: FICTIONAL_BRIDGE_OFFLINE']);
  await f.reopen();f.respond=()=>({status:'published'});
  await f.runtime.startInstance('kaka');
  assert.equal(f.notices.length,2);assert.equal(f.notices[1].messageId,f.notices[0].messageId);
  assert.equal(f.notices[1].alert,false);assert.equal(f.notices[1].sequence,1);
  assert.equal((await f.store.listMessages('kaka')).messages.length,1);
  assert.deepEqual(await f.store.pendingNotifications('kaka'),[]);
});

test('restart after append before publisher observation recovers notification even when no new part is appended',async t=>{
  const f=await fixture(t),draft=await f.draft([{type:'image',text:''}]),append=f.store.appendMessage.bind(f.store);
  f.store.appendMessage=async(...args)=>{await append(...args);throw new Error('Fictional crash after commit');};
  await assert.rejects(f.runtime.publish('kaka',draft),/Fictional crash/);
  await f.reopen();
  await f.runtime.recoverReply('kaka');
  assert.equal(f.notices.length,1);assert.equal(f.notices[0].messageId,draft.parts[0].id);
  assert.equal(f.notices[0].text,'[Image]');assert.equal(f.notices[0].alert,false);
  assert.equal((await f.store.listMessages('kaka')).messages.length,1);
  assert.equal((await f.store.getState('kaka')).replyDraft,null);
});

test('disabled delivery is acknowledged and never replayed as a new alert after re-enabling',async t=>{
  const f=await fixture(t);f.respond=()=>({status:'disabled',reason:'messages_disabled'});
  await f.runtime.publish('kaka',await f.draft(['A fictional disabled notice.']));
  assert.deepEqual(await f.store.pendingNotifications('kaka'),[]);
  f.respond=()=>({status:'published'});await f.runtime.deliverNotifications('kaka');
  assert.equal(f.notices.length,1);assert.equal((await f.store.profiles())[0].unreadCount,1);
});

test('authenticated read and conversation actions send authoritative cutoffs without consuming a later unread reply',async t=>{
  const f=await fixture(t),first=await f.runtime.publish('kaka',await f.draft(['First fictional reply.']));
  await f.runtime.publish('kaka',await f.draft(['Later fictional reply.']));
  const previousToken=process.env.DROY_WEB_TOKEN,token='fictional-notification-session-token-123456789';
  process.env.DROY_WEB_TOKEN=token;
  t.after(()=>{if(previousToken===undefined)delete process.env.DROY_WEB_TOKEN;else process.env.DROY_WEB_TOKEN=previousToken;});
  const handler=createCompanionHandler({ctx:f.runtime.ctx,runtime:f.runtime,store:f.store});
  const server=createServer((req,res)=>handler(req,res));
  await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));
  t.after(async()=>{server.closeAllConnections();await new Promise(resolve=>server.close(resolve));});
  const post=(path,body)=>fetch('http://127.0.0.1:'+server.address().port+'/droy/companions/kaka/'+path,{method:'POST',headers:{cookie:'droy_session='+token,'content-type':'application/json'},body:JSON.stringify(body)});
  assert.equal((await post('read',{messageId:first.messageIds[0]})).status,200);
  assert.deepEqual(f.notices.at(-1),{operation:'companionRead',companionId:'kaka',throughSequence:1,unreadCount:1});
  assert.equal((await f.store.profiles())[0].unreadCount,1);
  const before=f.notices.length;
  assert.equal((await post('conversation',{action:'pin'})).status,200);assert.equal(f.notices.length,before);
  assert.equal((await post('conversation',{action:'read'})).status,200);
  assert.deepEqual(f.notices.at(-1),{operation:'companionRead',companionId:'kaka',throughSequence:2,unreadCount:0});
  await f.runtime.publish('kaka',await f.draft(['A newer fictional reply.']));
  assert.equal((await post('conversation',{action:'hide'})).status,200);
  assert.equal(f.notices.at(-1).throughSequence,3);assert.equal(f.notices.at(-1).unreadCount,0);
});

test('read failure preserves committed read position and emits a bounded diagnostic without private text',async t=>{
  const f=await fixture(t),first=await f.runtime.publish('kaka',await f.draft(['Private fictional preview.']));
  f.respond=()=>{throw Object.assign(new Error('Do not log Private fictional preview.'),{code:'FICTIONAL_READ_BRIDGE_OFFLINE'});};
  const result=await f.runtime.markRead('kaka',first.messageIds[0]);
  assert.equal(result.read,true);assert.equal((await f.store.profiles())[0].unreadCount,0);
  assert.deepEqual(f.warnings,['COMPANION_NOTICE_READ_FAILED: FICTIONAL_READ_BRIDGE_OFFLINE']);
  f.respond=()=>({status:'published'});
  await f.runtime.publish('kaka',await f.draft(['Only this later reply is unread.']));
  assert.equal(f.notices.at(-1).throughSequence,1);assert.equal(f.notices.at(-1).unreadCount,1);
  f.runtime.closed=true;
  await assert.rejects(f.runtime.markRead('kaka',first.messageIds[0]),{code:'COMPANION_CLOSED'});
});

test('deleting messages and clearing history retire pending notices while retaining a monotonic read cutoff',async t=>{
  const f=await fixture(t);f.runtime.invalidate=async()=>{};f.runtime.cancel=async()=>{};
  f.respond=value=>value.operation==='companion'?{status:'failed'}:{ok:true};
  const first=await f.runtime.publish('kaka',await f.draft(['A fictional reply to delete.']));
  await f.runtime.removeMessage('kaka',first.messageIds[0]);
  assert.deepEqual(await f.store.pendingNotifications('kaka'),[]);
  assert.deepEqual(f.notices.at(-1),{operation:'companionRemove',companionId:'kaka',messageId:first.messageIds[0],unreadCount:0});
  await f.runtime.publish('kaka',await f.draft(['A fictional reply to clear.']));
  await f.runtime.clearHistory('kaka');
  assert.deepEqual(await f.store.pendingNotifications('kaka'),[]);
  assert.deepEqual(f.notices.at(-1),{operation:'companionRead',companionId:'kaka',throughSequence:2,unreadCount:0});
  await f.reopen();assert.deepEqual(await f.store.pendingNotifications('kaka'),[]);
  await f.store.appendMessage('kaka',{id:'later-row',role:'assistant',text:'A later fictional reply.'},{notification:true});
  assert.equal((await f.store.pendingNotifications('kaka'))[0].sequence,3);
});

test('failed notification removal remains durable, is visible to the caller and precedes later message publication',async t=>{
  const f=await fixture(t);f.runtime.invalidate=async()=>{};
  const first=await f.runtime.publish('kaka',await f.draft(['Fictional content to remove.']));
  f.respond=value=>{if(value.operation==='companionRemove')throw Object.assign(new Error('Fictional cleanup failure'),{code:'FICTIONAL_NOTICE_REMOVE_OFFLINE'});return {status:'published'};};
  await assert.rejects(f.runtime.removeMessage('kaka',first.messageIds[0]),{code:'COMPANION_NOTIFICATION_REMOVE_FAILED'});
  assert.equal(await f.store.getMessage('kaka',first.messageIds[0]),null);
  assert.deepEqual(await f.store.pendingNotificationRemovals('kaka'),first.messageIds);
  assert.match(f.warnings.at(-1),/^COMPANION_NOTICE_REMOVE_FAILED: FICTIONAL_NOTICE_REMOVE_OFFLINE$/);
  await f.reopen();f.respond=()=>({status:'published'});
  const before=f.notices.length;
  await f.runtime.publish('kaka',await f.draft(['A new fictional unread reply.']));
  assert.deepEqual(f.notices.slice(before).map(value=>value.operation),['companionRemove','companion']);
  assert.deepEqual(await f.store.pendingNotificationRemovals('kaka'),[]);
  assert.equal(f.notices.at(-1).unreadCount,1);
});

test('retrying an already deleted message retries its outstanding notification cleanup',async t=>{
  const f=await fixture(t);f.runtime.invalidate=async()=>{};
  const first=await f.runtime.publish('kaka',await f.draft(['A fictional message to delete.']));
  f.respond=()=>{throw Object.assign(new Error('Fictional offline bridge'),{code:'FICTIONAL_NOTICE_OFFLINE'});};
  await assert.rejects(f.runtime.removeMessage('kaka',first.messageIds[0]),{code:'COMPANION_NOTIFICATION_REMOVE_FAILED'});
  f.respond=()=>({ok:true});
  assert.equal((await f.runtime.removeMessage('kaka',first.messageIds[0])).removed,true);
  assert.deepEqual(await f.store.pendingNotificationRemovals('kaka'),[]);
});

test('full notification cleanup backlog refuses another deletion without dropping prior cleanup identities',async t=>{
  const f=await fixture(t);
  for(let index=0;index<201;index++)await f.store.appendMessage('kaka',{id:'delete-backlog-'+index,role:'assistant',text:'Fictional cleanup row.'});
  for(let index=0;index<200;index++)await f.store.removeMessage('kaka','delete-backlog-'+index);
  const pending=await f.store.pendingNotificationRemovals('kaka');
  assert.equal(pending.length,200);
  await assert.rejects(f.store.removeMessage('kaka','delete-backlog-200'),{code:'COMPANION_NOTIFICATION_REMOVALS_PENDING'});
  assert.ok(await f.store.getMessage('kaka','delete-backlog-200'));
  assert.deepEqual(await f.store.pendingNotificationRemovals('kaka'),pending);
});

test('notification identities do not consume the existing chat-context metadata size budget',async t=>{
  const f=await fixture(t),summary='x'.repeat(262144-14);
  await f.store.updateState('kaka',{summary});
  const first=await f.store.appendMessage('kaka',{id:'full-context-notice',role:'assistant',text:'A fictional message after a full context.'},{notification:true});
  assert.equal((await f.store.pendingNotifications('kaka'))[0].message.id,first.id);
  await f.store.updateState('kaka',current=>({...current,summary}));
  assert.equal((await f.store.getState('kaka')).summary,summary);
});

test('pending notification history is bounded without trimming chat messages or authoritative unread count',async t=>{
  const f=await fixture(t);
  for(let index=0;index<201;index++)await f.store.appendMessage('kaka',{id:'notice-backlog-'+index,role:'assistant',text:'Fictional pending notice.'},{notification:true});
  const pending=await f.store.pendingNotifications('kaka');
  assert.equal(pending.length,200);assert.equal(pending[0].message.id,'notice-backlog-1');
  assert.equal((await f.store.profiles())[0].unreadCount,201);
  assert.equal((await f.store.listMessages('kaka',{limit:200})).hasMore,true);
});
