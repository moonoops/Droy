import { randomUUID } from 'node:crypto';
import { assertCompanionId } from './profiles.mjs';

const publishing=new WeakMap();
const fail=code=>{throw Object.assign(new Error(code),{code});};
const checkSignal=signal=>signal?.throwIfAborted();
const identifier=value=>{
  if(typeof value!=='string'||!/^[A-Za-z0-9_.:-]{1,160}$/u.test(value))fail('COMPANION_INVALID_ID');
  return value;
};
function sequence(cursor) {
  if(typeof cursor!=='string'||!/^seq:\d+$/u.test(cursor)||!Number.isSafeInteger(Number(cursor.slice(4))))fail('COMPANION_INVALID_INPUT');
  return Number(cursor.slice(4));
}
function currentDraft(state,draft) {
  if(state.replyDraft?.id!==draft.id||state.replyDraft.companionId!==draft.companionId
    ||(state.memoryRevision??0)!==draft.memoryRevision||(state.forgottenThrough??'seq:0')!==draft.baseline)fail('COMPANION_REPLY_STALE');
  return state.replyDraft;
}
async function sourceCheck(store,companionId,state,sourceMessageIds,signal) {
  const baseline=sequence(state.forgottenThrough??'seq:0'),forgotten=new Set(state.forgottenMessageIds??[]);
  for(const source of sourceMessageIds) {
    checkSignal(signal);
    const [message,cursor]=await Promise.all([store.getMessage(companionId,source),store.getMessageCursor(companionId,source)]);
    if(!message||message.contextExcluded||forgotten.has(source)||!cursor||sequence(cursor)<=baseline)fail('COMPANION_REPLY_STALE');
  }
  checkSignal(signal);
}
async function checkedDraft({store,companionId,draft,signal}) {
  checkSignal(signal);
  const state=await store.getState(companionId),stored=currentDraft(state,draft);
  await sourceCheck(store,companionId,state,stored.sourceMessageIds,signal);
  return stored;
}

/** Persist message identities before publishing any bubbles. Recovery uses the
 * same identities even if the previous process stopped immediately after append. */
export async function prepareReply({store,companionId,sourceMessageIds,parts,proactive}) {
  assertCompanionId(companionId);
  if(!Array.isArray(sourceMessageIds)||!Array.isArray(parts)||!parts.length)fail('COMPANION_INVALID_INPUT');
  const sources=[...new Set(sourceMessageIds.map(identifier))],state=await store.getState(companionId);
  await sourceCheck(store,companionId,state,sources);
  const contact=proactive?{sourceIds:[...new Set((proactive.sourceIds??[]).map(identifier))]}:null;
  const createdAt=new Date().toISOString(),draft={id:randomUUID(),companionId,sourceMessageIds:sources,
    ...(contact?{proactive:contact}:{}),memoryRevision:state.memoryRevision??0,baseline:state.forgottenThrough??'seq:0',createdAt,
    parts:parts.map(part=>{
      const message=typeof part==='string'?{type:'text',text:part}:structuredClone(part);
      if(!message||typeof message!=='object'||Array.isArray(message))fail('COMPANION_INVALID_INPUT');
      return {...message,id:randomUUID(),role:'assistant',status:'sent',createdAt,
        contextSourceIds:[...new Set([...sources,...(message.contextSourceIds??[])].map(identifier))]};
    })};
  for(let index=0;index<draft.parts.length;index++) {
    const part=draft.parts[index];
    if(part.quotePrevious&&index>0)part.replyTo=draft.parts[index-1].id;
    // Older saved correction drafts retain their source link, but a source link
    // alone is no longer an instruction to show a quote in the conversation.
    if(part.quoteReply === undefined)part.quoteReply=false;
    delete part.quotePrevious;
  }
  await store.updateState(companionId,current=>{
    if((current.memoryRevision??0)!==draft.memoryRevision||(current.forgottenThrough??'seq:0')!==draft.baseline)fail('COMPANION_REPLY_STALE');
    return {replyDraft:draft};
  });
  return draft;
}

async function publish(input) {
  const {store,companionId,draft,signal,beforeSend,onSent}=input;
  assertCompanionId(companionId);identifier(draft?.id);
  if(draft.companionId!==companionId)fail('COMPANION_REPLY_STALE');
  const stored=await checkedDraft(input),messageIds=[],newMessageIds=[];
  for(let index=0;index<stored.parts.length;index++) {
    const message=stored.parts[index];
    await checkedDraft(input);
    let saved=await store.getMessage(companionId,message.id);
    if(!saved) {
      await beforeSend?.({message:structuredClone(message),index,draft:structuredClone(stored)});
      await checkedDraft(input);
      saved=await store.appendMessage(companionId,{...message,createdAt:new Date().toISOString()});
      newMessageIds.push(saved.id);
      checkSignal(signal);
      await onSent?.(saved,{index,draft:structuredClone(stored)});
    }
    messageIds.push(saved.id);
  }
  await checkedDraft(input);
  checkSignal(signal);
  await store.completeInputs(companionId,stored.sourceMessageIds,{draftId:stored.id,memoryRevision:stored.memoryRevision});
  return {messageIds,newMessageIds};
}

export function publishReply(input) {
  let active=publishing.get(input.store);
  if(!active){active=new Map();publishing.set(input.store,active);}
  const previous=active.get(input.companionId)??Promise.resolve();
  const pending=previous.catch(()=>{}).then(()=>publish(input));
  active.set(input.companionId,pending);
  pending.finally(()=>{if(active.get(input.companionId)===pending)active.delete(input.companionId);}).catch(()=>{});
  return pending;
}
