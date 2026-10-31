import { companionReplyText } from './reply-text.mjs';
import { inputBatchDelay } from './input-timing.mjs';
import { buildProactiveContext,proactiveDecisionPrompt,parseProactiveDecision,proactiveNextCheckAt } from './proactive-policy.mjs';
import { randomUUID } from 'node:crypto';
import { mkdir } from 'node:fs/promises';
import { join } from 'node:path';
import { setTimeout as pause } from 'node:timers/promises';
import { defineTool } from '@deepseek-ai/dsh-tools';
import { createUserMessage,createAssistantMessage } from '@deepseek-ai/dsh-llm';
import { applyWebSearchTool,applyWebFetchTool } from '@deepseek-ai/dsh-tool-web';
import { CompanionMemory } from './memory.mjs';
import { CompanionMemorySpaces } from './memory-spaces.mjs';
import { consumeCompanionMemoryAuthorization } from './memory-management.mjs';
import { CompanionLearning } from './learning.mjs';
import { installCompanionMaintenance } from './maintenance-runtime.mjs';
import { OnlineStickerService } from './online-stickers.mjs';
import { stickerCatalog,stickerContext,readSharedAttachment,replyAttachment } from './media.mjs';
import { prepareReply,publishReply } from './delivery.mjs';
import { localizedProfile, profileName } from './profiles.mjs';
import { dialoguePrompts } from './dialogue-prompts.mjs';
import { buildPlannerPrompt,buildReplyerRequest,buildWaitReminder,renderMaiBotPrompt,replyToolDescriptions } from './maibot-prompts.mjs';
import { selectExpressions } from './maibot-expression.mjs';
import { isQuietTime, localTimeContext, withoutSources, sharedContext } from './dialogue.mjs';
import { resolveMobileLocale } from '../locale.mjs';
import { translateBackendText } from '../backend-i18n.mjs';
import { assertUserCreate } from '../task-ownership.mjs';
import { MOBILE_PROMPT_SECTION, COMPANION_PROMPT_SECTION } from '../prompt.mjs';

export const COMPANION_TOOLS = new Set(['web_search','web_fetch','companion_reply','companion_wait',
  'companion_quiet','companion_work','companion_recall','companion_remember','companion_forget','companion_attachment',
  'companion_stickers','companion_emoji','companion_sticker_search','companion_sticker_download']);
const CHAT_ONLY_TOOLS=new Set(['companion_wait','companion_quiet','companion_work','companion_stickers','companion_emoji',
  'companion_sticker_search','companion_sticker_download']);
const executionObservers=['cancel','markRead','syncReadNotifications','deliverNotifications'];
function replyParameters(parameters,locale) {
  const descriptions=replyToolDescriptions(locale),result=structuredClone(parameters);
  for(const [key,schema] of Object.entries(result))if(descriptions[key])schema.description=descriptions[key];
  for(const [key,schema] of Object.entries(result.expression_intent?.properties??{}))schema.description=descriptions[key];
  if(result.attach_pic) {
    result.attach_pic.items.properties.msg_id.description=descriptions.picture_msg_id;
    result.attach_pic.items.properties.index.description=descriptions.picture_index;
  }
  return result;
}
const fail = code => { throw Object.assign(new Error(code), {code}); };
const textBlocks = value => (value ?? []).filter(block => block.type === 'text').map(block => block.text).join('\n');
const message = text => createUserMessage({content:[{type:'text',text}],source:{kind:'user'}});
const jsonOutput = {schema:{type:'json'},render:(_args,value)=>[{type:'text',text:JSON.stringify(value)}]};
const isAborted=reason=>['aborted','cancelled'].includes(reason?.kind);
const memorySourceIds=(id,records)=>records.flatMap(record=>[
  'memory:'+record.recordId,'memory:'+record.recordId+':'+record.revisionId,
  ...(record.sourceRefs??[]).filter(source=>source.startsWith('im:'+id+':')).map(source=>source.slice(('im:'+id+':').length)),
]);

export class CompanionRuntime {
  constructor({ctx,store,directory,stickerLibrary,onlineStickers,options = {},notify = () => {}}) {
    this.ctx = ctx; this.store = store; this.directory = directory; this.notify = notify; this.stickerLibrary=stickerLibrary;
    this.onlineStickers=onlineStickers??(stickerLibrary?new OnlineStickerService({library:stickerLibrary}):undefined);
    this.options = {historyMessages:80,learningMessages:6,
      proactiveIntervalMs:1800000,proactiveIdleMs:900000,...options};
    this.owners = new Map(); this.live = new Map(); this.memories = new Map(); this.states = new Map();
    this.listeners = new Set(); this.activityListeners = new Set(); this.handles = new Map(); this.routes = new WeakMap(); this.closed = false;
    this.lifetime=new AbortController();this.background=new Set();this.decorated=new WeakSet();
    this.restrictedAgents=new Map();
    this.memoryManagement=new Set();
    this.notificationDeliveries=new Map();
    this.workspaceStarts=new Map();this.instanceStarts=new Map();
    this.contextSources=new Map();this.learningSignals=new Map();this.turnMemories=new Map();this.turnProfiles=new Map();this.plannerRevisions=new WeakMap();
    this.learner=new CompanionLearning({store,memoryFor:id=>this.memory(id),generate:(...args)=>this.generate(...args),
      getProfile:id=>this.profileForUse(id,{requireModel:false}),
      getLocale:()=>this.locale(),getStickers:()=>this.catalog(),options:{learningUserMessages:this.options.learningMessages,factLearningUserMessages:this.options.factLearningMessages??1}});
    this.maintenance=installCompanionMaintenance(this);
  }
  locale() { return resolveMobileLocale(this.ctx); }
  copy() { return dialoguePrompts[this.locale()]; }
  emit(companionId,kind='message') {
    const event={companionId,kind,...(companionId?{status:this.status(companionId)}:{})};
    if(companionId)this.state(companionId).publishedExecution=JSON.stringify(event.status.execution);
    for (const listener of this.listeners) listener(event);
  }
  executionChanged(id) {
    const state=id?this.states.get(id):undefined;
    if(!state||this.closed||state.publishedExecution===undefined)return;
    if(state.publishedExecution!==JSON.stringify(this.execution(id)))this.emit(id,'status');
  }
  status(id) {
    const state=this.state(id);
    return {phase:state.phase,...(state.error?{error:this.copy().generationFailed}:{}),chatExecution:this.chatExecution(id),execution:this.execution(id)};
  }
  execution(id) {
    const state=this.state(id);
    return {quiescent:this.chatExecution(id).quiescent&&!state.stopPending&&!state.stopUnconfirmed
      &&!state.workTasks.size&&!state.workIdleObservers.size&&!this.maintenance.active(id,executionObservers).length,
    cancelRequested:state.chatCancelRequested};
  }
  activity() {
    return {busy:!this.closed&&(this.maintenance?.active().length>0||[...this.states.values()].some(state=>
      state.pending.length||state.workTasks.size||state.chatWaitScheduled||state.chatRecoveryScheduled||state.stopPending||state.stopUnconfirmed)
      ||[...this.live.values()].some(agent=>agent.status==='running'))};
  }
  activityChanged() { for(const listener of this.activityListeners)listener(this.activity()); }
  subscribeActivity(listener) { this.activityListeners.add(listener);return ()=>this.activityListeners.delete(listener); }
  observeWorkIdle(id,agent) {
    const state=this.state(id);
    if(state.workIdleObservers.has(agent))return;
    if(typeof agent.whenIdle!=='function'){state.stopUnconfirmed=true;this.activityChanged();return;}
    const finish=this.maintenance.begin('work-idle',id);
    const pending=Promise.resolve().then(async()=>{
      try {await agent.whenIdle();}
      catch(error) {state.stopUnconfirmed=true;this.ctx.logger.warn('COMPANION_STOP_UNCONFIRMED: '+(error.code||error.name));}
      finally {state.workIdleObservers.delete(agent);finish();if(!this.closed)this.emit(id,'status');}
    });
    state.workIdleObservers.set(agent,pending);this.background.add(pending);
    pending.finally(()=>this.background.delete(pending));
  }
  chatExecution(id) {
    const state=this.state(id),agents=new Set([this.live.get(id)].filter(Boolean));
    for(const owner of this.owners.values())if(owner.companionId===id&&owner.kind==='chat') {
      const agent=this.ctx.agents.get(owner.sessionId);if(agent)agents.add(agent);
    }
    // This confirms only the companion chat, not its independent work tasks or
    // background memory learning. An aborted signal alone is never settlement.
    return {quiescent:!state.chatOperations.size&&!state.pending.length&&!state.chatWaitScheduled&&!state.chatRecoveryScheduled
      &&!state.chatStopUnconfirmed&&[...agents].every(agent=>agent.status==='idle'&&state.chatObservedIdle.has(agent)),
    cancelRequested:state.chatCancelRequested};
  }
  beginChat(id) {
    const state=this.state(id),operation={};state.chatOperations.add(operation);
    const settled=this.maintenance.begin('chat',id);
    return ()=>{
      state.chatOperations.delete(operation);
      settled();
      if(!this.closed&&this.chatExecution(id).quiescent)this.emit(id,'status');
    };
  }
  observeChatIdle(id,agent) {
    const state=this.state(id);
    if(state.chatIdleObservers.has(agent))return;
    state.chatObservedIdle.delete(agent);
    // Public status also reports idle during maintenance. Only whenIdle proves
    // the current driver and its replacement/maintenance activity have drained.
    if(typeof agent.whenIdle!=='function'){state.chatStopUnconfirmed=true;return;}
    const finish=this.beginChat(id);
    const pending=Promise.resolve().then(async()=>{
      try {await agent.whenIdle();state.chatObservedIdle.add(agent);}
      catch(error) {
        state.chatStopUnconfirmed=true;
        this.ctx.logger.warn('COMPANION_STOP_UNCONFIRMED: '+(error.code||error.name));
      } finally {state.chatIdleObservers.delete(agent);finish();}
    });
    state.chatIdleObservers.set(agent,pending);this.background.add(pending);
    pending.finally(()=>this.background.delete(pending));
  }
  async clock(signal=this.lifetime.signal) {
    const current=this.options.getClock?await this.options.getClock(signal):{now:Date.now(),timeZone:(await this.store.getSettings()).timeZone};
    return localTimeContext(new Date(current.now),current.timeZone??Intl.DateTimeFormat().resolvedOptions().timeZone);
  }
  subscribe(listener) { this.listeners.add(listener); return () => this.listeners.delete(listener); }
  state(id) {
    if (!this.states.has(id)) this.states.set(id,{phase:'idle',revision:0,pending:[],admitted:new Set(),delivered:0,
      chatOperations:new Set(),chatObservedIdle:new WeakSet(),chatIdleObservers:new Map(),chatCancelRequested:false,chatCancelRevision:0,
      workTasks:new Map(),workIdleObservers:new Map(),workReplyAborts:new Map(),stopPending:false,stopUnconfirmed:false});
    return this.states.get(id);
  }
  ownsSession(id) { return this.owners.has(String(id)); }
  isRestrictedAgent(agent) { return !!agent && this.ownsSession(agent.id) && this.decorated.has(agent); }
  isRestrictedSession(id) { return this.isRestrictedAgent(this.restrictedAgents.get(String(id))); }
  async initialize() {
    this.memorySpaces=await CompanionMemorySpaces.open({directory:this.directory});
    await this.recoverMemoryManagement();
    const profiles=await this.store.profiles(),managed=new Set(profiles.map(profile=>profile.workspaceId).filter(Boolean));
    if(!this.ctx.workspaceRegistry.list().some(workspace=>!managed.has(workspace.id))) {
      const path=this.ctx.workingDirectory.defaultDirectory,title=translateBackendText('默认空间',this.locale());
      await mkdir(path,{recursive:true});
      let created=false;
      const workspace=await this.ctx.workspaceRegistry.initializeDefault(()=>{created=true;return path;});
      if(!workspace||managed.has(workspace.id))await this.ctx.workspaceRegistry.create(path,title);
      else if(created)await workspace.setTitle(title);
    }
    for (const profile of profiles) {
      if(profile.workspaceId||!profile.id.startsWith('companion-'))await this.prepareWorkspace(profile.id);
      for (const owner of await this.store.listSessions(profile.id)) this.owners.set(owner.sessionId,owner);
    }
    this.installOwnership();
    this.ctx.on('agent/created',async ({agent}) => {
      return this.maintenance.run('agent-created',async()=>{
        const owner = this.owners.get(agent.id) ?? await this.store.sessionOwner(agent.id);
        if (owner) {
          this.owners.set(agent.id,owner);await this.decorate(agent,owner);
          if(owner.kind==='chat')this.observeChatIdle(owner.companionId,agent);
        }
      });
    });
    this.ctx.on('agent/status',({agent,status}) => {
      if (this.ownsSession(agent.id)) {
        this.maintenance.changed();
        const owner = this.owners.get(agent.id);
        if (owner.kind === 'chat') {
          if(status==='running'){this.state(owner.companionId).phase='thinking';this.observeChatIdle(owner.companionId,agent);}
          this.emit(owner.companionId,'status');
        } else if(status==='running')this.observeWorkIdle(owner.companionId,agent);
        this.activityChanged();
      }
    });
    this.ctx.on('session/event',(session,event) => {
      if (event.type !== 'turn/end' || !this.ownsSession(session.id)) return;
      this.finishTurn(this.owners.get(session.id),event).catch(error=>this.report(this.owners.get(session.id).companionId,error));
    });
    return this;
  }
  async start() {
    if(this.started||this.closed)return;this.started=true;
    for(const profile of await this.store.profiles())if(!this.memoryManagement.has(profile.id))await this.startInstance(profile.id);
  }
  prepareWorkspace(id) {
    if(this.workspaceStarts.has(id))return this.workspaceStarts.get(id);
    const opening=(async()=>{
      const profile=await this.store.getProfile(id),path=join(this.directory,id,'work');
      let workspace=profile.workspaceId?this.ctx.workspaceRegistry.get(profile.workspaceId):null;
      if(profile.workspaceId&&!workspace)fail('COMPANION_WORKSPACE_NOT_FOUND');
      await mkdir(path,{recursive:true});await mkdir(join(this.directory,id,'chat'),{recursive:true});
      if(!workspace) {
        workspace=await this.ctx.workspaceRegistry.create(path,profileName(profile,this.locale()));
        await this.store.updateProfile(id,{workspaceId:workspace.id});
      }
      return workspace.id;
    })();
    this.workspaceStarts.set(id,opening);
    opening.catch(()=>this.workspaceStarts.delete(id));
    return opening;
  }
  startInstance(id) {
    if(this.closed)return Promise.resolve();
    if(this.instanceStarts.has(id))return this.instanceStarts.get(id);
    const state=this.state(id),cancelRevision=state.chatCancelRevision;
    const starting=(async()=>{
      await this.store.getProfile(id);
      const saved=await this.store.getState(id);
      if(state.chatCancelRevision!==cancelRevision||state.stopPending)return;
      const recoveringWait=saved.waitingUntil&&saved.pendingIntent?.kind==='wait'&&!saved.replyDraft;
      if((saved.chatNeedsReset&&!recoveringWait)||saved.replyDraft)await this.store.updateProfile(id,{chatSessionId:null});
      state.pending=await this.activePending(id,[...(saved.pendingMessageIds??[]),...(await this.store.pendingMessages(id)).map(item=>item.id)]);
      if(state.chatCancelRevision!==cancelRevision||state.stopPending){state.pending=[];return;}
      for(const message of state.pending)state.admitted.add(message.id);
      await this.store.updateState(id,{pendingMessageIds:state.pending.map(item=>item.id)});
      if(state.chatCancelRevision!==cancelRevision||state.stopPending)return;
      const covered=new Set(saved.replyDraft?.sourceMessageIds??[]);
      if(recoveringWait) {
        state.phase='waiting';this.scheduleWait(id,saved);
      } else if((saved.replyDraft?.sourceMessageIds?.length||saved.replyDraft?.proactive)&&state.pending.every(item=>covered.has(item.id))) {
        state.chatRecoveryScheduled=true;
        state.timer=this.maintenance.recovery('chat:'+id,Date.now()+(this.options.debounceMs??0),()=>{state.chatRecoveryScheduled=false;return this.recoverReply(id).catch(error=>this.report(id,error));});
      } else {
        if(saved.replyDraft)await this.store.updateState(id,{replyDraft:null});
        if(state.pending.length)state.timer=this.maintenance.recovery('chat:'+id,Date.now()+(this.options.debounceMs??0),()=>this.flush(id).catch(error=>this.report(id,error)));
        else this.scheduleWait(id,saved);
      }
      this.scheduleProactive(id,saved.proactiveNextAt);
      state.workRecoveryTimers??=new Set();
      for(const owner of await this.store.listSessions(id,{kind:'work'})) {
        const taskMessage=await this.store.getMessage(id,owner.taskId);
        if(state.chatCancelRevision!==cancelRevision||state.stopPending)break;
        if(!taskMessage?.contextExcluded&&['running','queued','cancelling'].includes(taskMessage?.task?.state)) {
          state.workTasks.set(owner.taskId,owner.sessionId);
          const timer=this.maintenance.recovery('work:'+owner.sessionId,Date.now()+(this.options.debounceMs??0),()=>{
            state.workRecoveryTimers.delete(timer);
            const operation=this.resumeWork(owner);this.background.add(operation);
            operation.catch(error=>this.report(id,error)).finally(()=>this.background.delete(operation));
            return operation;
          },{unref:true});if(timer)state.workRecoveryTimers.add(timer);
        }
      }
      const read=await this.store.notificationReadState(id);
      if(read.throughSequence>0)await this.syncReadNotifications(id,read);
      await this.deliverNotifications(id);
    })();
    this.instanceStarts.set(id,starting);
    starting.catch(()=>this.instanceStarts.delete(id));
    return starting;
  }
  installOwnership() {
    const controller = this.ctx.sessionController;
    const originals = {};
    const workingDirectory=this.ctx.get('workingDirectory'),ensure=workingDirectory?.ensure;
    if(ensure) {
      const runtime=this;
      workingDirectory.ensure=function(agent,signal) {
        if(!runtime.isRestrictedAgent(agent))return ensure.call(this,agent,signal);
        signal?.throwIfAborted();
        // The product already created this private directory. Reading its
        // session metadata must not start the Root filesystem executor.
        return Promise.resolve(this.get(agent.session));
      };
    }
    for (const name of ['create','prompt','fork','selectModel','rename','cancel','updateQueue']) {
      originals[name] = controller[name];
      if (typeof originals[name] !== 'function') continue;
      const runtime = this;
      const wrapped = async function(request,...args) {
        if(runtime.ctx.get('droyLifecycle')?.stopping)fail('DROY_STOPPING');
        if (name === 'create') {
          await assertUserCreate(runtime.ctx,request,runtime);
        } else if (runtime.ownsSession(request.sessionId)) fail('COMPANION_SESSION_MANAGED');
        if(runtime.ctx.get('droyLifecycle')?.stopping)fail('DROY_STOPPING');
        return originals[name].call(this,request,...args);
      };
      controller[name] = wrapped;
    }
    this.restoreController = () => {
      for (const [name,original] of Object.entries(originals)) if (original) controller[name] = original;
      if(ensure)workingDirectory.ensure=ensure;
    };
  }
  async memory(id) {
    if(this.closed)fail('COMPANION_CLOSED');
    if(this.memoryManagement.has(id))fail('COMPANION_MEMORY_MANAGEMENT_IN_PROGRESS');
    const profile=await this.store.getProfile(id);
    if(this.memoryManagement.has(id))fail('COMPANION_MEMORY_MANAGEMENT_IN_PROGRESS');
    if(this.closed)fail('COMPANION_CLOSED');
    if (!this.memories.has(id)) {
      const opening = CompanionMemory.open({directory:this.directory,companionId:id,profile,
        spaces:this.memorySpaces,embed:this.options.embed,validateSources:async(sources,binding)=>{
          const saved=await this.store.getState(id),boundary=Math.max(Number((binding.sourceCaptureBoundary??'seq:0').slice(4)),Number((saved.forgottenThrough??'seq:0').slice(4)));
          for(const source of sources) {
            if(!source.startsWith('im:'+id+':'))continue;
            const messageId=source.slice(('im:'+id+':').length),cursor=await this.store.getMessageCursor(id,messageId);
            if(!cursor||Number(cursor.slice(4))<=boundary||(saved.forgottenMessageIds??[]).includes(messageId))fail('MEMORY_SOURCE_FORGOTTEN');
          }
        }});
      this.memories.set(id,opening);
      opening.catch(()=>this.memories.delete(id));
    }
    return this.memories.get(id);
  }
  async identityForAgent(agent) {
    const owner = this.owners.get(agent.id);
    if (!owner) return null;
    const profile=localizedProfile(await this.store.getProfile(owner.companionId),this.locale());
    return {...profile,displayName:profile.name,appearanceId:profile.templateId??profile.id};
  }
  async profileForUse(id,{requireModel=true}={}) {
    const saved=await this.store.getProfile(id),profile={...saved,name:profileName(saved,this.locale())};
    const model=profile.model??this.ctx.agentDefaultModel?.currentSelection();
    if(!model&&!requireModel)return profile;
    if(!model?.provider?.trim()||!model?.model?.trim())fail('COMPANION_MODEL_REQUIRED');
    return {...profile,model:structuredClone(model)};
  }
  async context(id,owner,signal=this.lifetime.signal) {
    const profile = await this.store.getProfile(id),page=await this.learner.contextMessages(id,{signal});
    const state=page.state;
    const excluded = new Set(state.forgottenMessageIds ?? []);
    const messages = page.messages.filter(item=>!excluded.has(item.id) && item.status !== 'failed');
    const memoryKey=owner?.sessionId??id,revision=this.state(id).revision+':'+(state.memoryRevision??0);
    let recalled=this.turnMemories.get(memoryKey);
    if(recalled?.revision!==revision) {
      recalled={revision,items:[]};this.turnMemories.set(memoryKey,recalled);
      // Upstream heuristic recall is opt-in. Explicit query_memory results
      // remain shared by Planner and Replyer throughout this logical turn.
      if(profile.settings.memoryEnabled&&this.options.maibot?.heuristicMemory)
        recalled.items=(await this.learner.recall(id,messages,{signal})).items;
    }
    const memories={items:profile.settings.memoryEnabled?recalled.items:[]};
    const [clock,userProfile]=await Promise.all([this.clock(signal),this.store.getUserProfile()]);
    const result={...sharedContext(state,memories,messages,this.locale(),this.catalog()),...clock,
      userProfile:{name:userProfile.name,bio:userProfile.bio??''},availableStickers:this.stickers(profile).slice(0,12)};
    if(owner) {
      const sourceContext={contextSourceIds:[...new Set([...messages.map(item=>item.id),...(this.state(id).proactiveIntent?.sourceIds??[]),
        ...(this.contextSources.get(owner.sessionId)?.contextSourceIds??[]).filter(source=>source.startsWith('memory:')),
        ...memorySourceIds(id,memories.items),
        ...['impressions','expressions','jargon'].flatMap(key=>(result[key]??[]).flatMap(item=>item.sourceIds??[]))])],
        ...(state.summary&&state.summaryCursor?{contextRange:{after:state.forgottenThrough??'seq:0',through:state.summaryCursor}}:{})};
      // A planner recall can inform intent even when the replyer selects
      // different records; retain those memory identities, not all old text.
      this.contextSources.set(owner.sessionId,sourceContext);
      await this.store.updateState(id,current=>({sessionSources:{...current.sessionSources,[owner.sessionId]:{
        messageIds:[...new Set([...(current.sessionSources?.[owner.sessionId]?.messageIds??[]),...sourceContext.contextSourceIds])],
        hasSummary:!!result.summary||!!current.sessionSources?.[owner.sessionId]?.hasSummary,
      }}}));
    }
    return result;
  }
  async generate(profile,system,input,{signal,onDelta,messages,preparedCall,sessionId=profile.chatSessionId ?? 'companion:'+profile.id} = {}) {
    signal=signal?AbortSignal.any([signal,this.lifetime.signal]):this.lifetime.signal;
    signal.throwIfAborted();
    const model=preparedCall?.config??(await this.profileForUse(profile.id)).model;
    signal.throwIfAborted();
    // Resolve once per model request. Later global/profile edits apply to the
    // next request, while this prepared stream keeps its selected route.
    const prepared = preparedCall ?? await this.ctx.llm.prepareCall(model,signal);
    const history=messages?.map(item=>item.role==='assistant'
      ?createAssistantMessage({content:item.content,source:{provider:model.provider,model:model.model}})
      :createUserMessage({content:item.content,source:{kind:'user'}}));
    let text = '', completed = false;
    for await (const chunk of prepared.stream({...prepared.config,system,
      messages:history??[message(typeof input==='string'?input:JSON.stringify(input))],
      sessionId,signal})) {
      signal?.throwIfAborted();
      if (chunk.type === 'text-delta') { text += chunk.text; await onDelta?.(chunk.text); }
      if (chunk.type === 'finish') {
        if (chunk.reason?.kind !== 'stop') fail(chunk.reason?.kind === 'aborted' ? 'COMPANION_CANCELLED' : 'COMPANION_MODEL_FAILED');
        completed = true;
      }
    }
    if (!completed || !text.trim()) fail('COMPANION_MODEL_FAILED');
    return text;
  }
  async agent(id,{kind='chat',sessionId,taskId} = {}) {
    const finish=kind==='chat'?this.beginChat(id):()=>{};
    try {
    if(this.closed)fail('COMPANION_CLOSED');
    let profile = await this.profileForUse(id);
    if (!profile.model) fail('COMPANION_MODEL_REQUIRED');
    const key = kind === 'chat' ? id : sessionId ?? randomUUID();
    if (this.live.has(key)) {
      const agent = this.live.get(key);
      if(kind==='chat')this.observeChatIdle(id,agent);
      return agent;
    }
    if (this.handles.has(key)) return (await this.handles.get(key)).agent;
    const opening = (async () => {
      await this.prepareWorkspace(id);profile=await this.profileForUse(id);
      const existing = kind === 'chat' ? profile.chatSessionId : this.owners.has(sessionId) ? sessionId : null;
      const owner = {companionId:id,kind,taskId:taskId ?? null,workspaceId:profile.workspaceId};
      const setup = async (_scope,agent) => {
        this.owners.set(agent.id,{...owner,sessionId:agent.id});
        await this.decorate(agent,{...owner,sessionId:agent.id});
      };
      const handle = existing
        ? await this.ctx.agents.resume({resumeSessionId:existing,agentOptions:profile.model,setup})
        : await this.ctx.agents.create({sessionId:sessionId ?? randomUUID(),agentOptions:profile.model,
          meta:{cwd:join(this.directory,id,kind === 'chat' ? 'chat' : 'work'),agentPreset:'standard'},setup});
      await this.store.bindSession(id,{sessionId:handle.agent.id,kind,workspaceId:profile.workspaceId,taskId:taskId ?? null});
      if (kind === 'work') await this.ctx.workspaceRegistry.get(profile.workspaceId).attachSession(handle.agent.id);
      this.live.set(key,handle.agent);
      if(kind==='chat')this.observeChatIdle(id,handle.agent);
      else this.observeWorkIdle(id,handle.agent);
      return handle;
    })();
    this.handles.set(key,opening);
    try { return (await opening).agent; } catch (error) { this.handles.delete(key); throw error; }
    } finally {finish();}
  }
  async decorate(agent,owner) {
    if (this.decorated.has(agent)) return;
    agent.ctx.tools.restrict({allow:agent.ctx.tools.schemas().filter(tool=>['web_search','web_fetch'].includes(tool.name)).map(tool=>tool.name)});
    agent.ctx.tools.guard(exec => COMPANION_TOOLS.has(exec.name)&&!(owner.kind==='work'&&CHAT_ONLY_TOOLS.has(exec.name))
      ? undefined : 'COMPANION_TOOL_NOT_ALLOWED');
    if(agent.ctx.get('web')) {
      if(!agent.ctx.tools.get('web_search'))applyWebSearchTool(agent.ctx,8,4,60000,true);
      if(!agent.ctx.tools.get('web_fetch'))applyWebFetchTool(agent.ctx,30000,200000);
    }
    agent.ctx.on('system-prompt/assemble',async (_assembly,context,next) => {
      const inputRevision=this.state(owner.companionId).revision;
      const assembly = await next();
      const profile = await this.profileForUse(owner.companionId), locale = this.locale();
      this.turnProfiles.set(owner.sessionId,structuredClone(profile));this.plannerRevisions.set(agent,inputRevision);
      this.routes.set(agent,profile.model);
      assembly.variables = {...assembly.variables,provider:profile.model.provider,model:profile.model.model};
      assembly.sections = assembly.sections.filter(section=>![MOBILE_PROMPT_SECTION,COMPANION_PROMPT_SECTION].includes(section.name));
      const availableTools=[...COMPANION_TOOLS].filter(name=>owner.kind!=='work'||!CHAT_ONLY_TOOLS.has(name));
      assembly.sections.push({name:'droy:companion-planner',text:buildPlannerPrompt({profile,locale,kind:owner.kind,
        options:this.options.maibot,availableTools}),interpolate:false});
      assembly.contexts = assembly.contexts.filter(item=>item.name !== 'droy:current-companion');
      assembly.contexts.push({name:'droy:shared-conversation',text:JSON.stringify(await this.context(owner.companionId,owner,context.signal)),interpolate:false});
      const descriptions={companion_reply:'replyDescription',companion_wait:'waitDescription',companion_quiet:'stopDescription',
        companion_work:'workDescription',companion_recall:'recallDescription',companion_remember:'rememberDescription',companion_forget:'forgetDescription',
        companion_attachment:'attachmentDescription',companion_stickers:'stickersDescription',companion_emoji:'emojiDescription',
        companion_sticker_search:'stickerSearchDescription',companion_sticker_download:'stickerDownloadDescription',
        web_search:'searchDescription',web_fetch:'fetchDescription'};
      assembly.tools = assembly.tools.filter(tool=>availableTools.includes(tool.name)).map(tool=>({...tool,
        ...(descriptions[tool.name]?{description:dialoguePrompts[locale][descriptions[tool.name]]}:{}),
        ...(['companion_reply','companion_emoji'].includes(tool.name)?{parameters:replyParameters(tool.parameters,locale)}:{})}));
      return assembly;
    },{prepend:true});
    agent.ctx.on('agent/request',async (_payload,next) => {
      const resolved = await next(),route = this.routes.get(agent);
      const {reasoningEffort:_previous,...request} = resolved;
      return {...request,...route};
    },{prepend:true});
    const register = (name,descriptionKey,parameters,execute) => agent.ctx.tools.register(defineTool({
      name,description:this.copy()[descriptionKey],parameters,output:jsonOutput,
      execute:async (args,exec) => this.maintenance.run('tool:'+name,()=>{
        exec.signal?.throwIfAborted();
        const state=this.state(owner.companionId);
        if(state.stopPending||state.chatCancelRequested)fail('COMPANION_CANCELLED');
        if(owner.kind==='chat'&&['companion_reply','companion_emoji','companion_work','companion_sticker_download'].includes(name)) {
          if(state.pending.length||state.dispatching) {
            exec.concludeTurn?.();return {superseded:true,reason:'new_messages_pending'};
          }
          const plannedRevision=this.plannerRevisions.get(agent);
          if(plannedRevision!==undefined&&plannedRevision!==state.revision)
            return {superseded:true,reason:'new_messages_queued'};
        }
        return execute(args,exec);
      },owner.companionId),
    }));
    register('companion_reply','replyDescription',{
      msg_id:{type:'string',required:true},reply_reference:{type:'string',required:true},
      quoteReply:{type:'boolean'},
      reply_style:{type:'string',enum:['简短表达','正常回复','长回复']},
      expression_intent:{type:'object',additionalProperties:false,properties:{focus:{type:'string'},reply_act:{type:'string'},
        scene:{type:'string'},tone:{type:'string'},prefer:{type:'array',items:{type:'string'}},avoid:{type:'array',items:{type:'string'}}}},
      attach_pic:{type:'array',items:{type:'object',additionalProperties:false,properties:{msg_id:{type:'string',required:true},index:{type:'integer'}}}},
      ...(this.options.maibot?.useNewSendLogic?{attach_emoji:{type:'integer'}}:{}),
    },async(args,exec)=>{
      const proactive=!!this.state(owner.companionId).proactiveIntent;
      const result = await this.reply(owner,args,exec.signal);
      if(owner.kind==='work'||proactive)exec.concludeTurn();
      return result;
    });
    register('companion_wait','waitDescription',{seconds:{type:'number',required:true}},(args,exec)=>this.wait(owner,args,exec.signal));
    register('companion_quiet','stopDescription',{},async(_args,exec)=>{
      this.state(owner.companionId).phase='idle';this.state(owner.companionId).turnAction='quiet';this.emit(owner.companionId,'status'); exec.concludeTurn(); return {quiet:true};
    });
    register('companion_recall','recallDescription',{query:{type:'string',required:true}},async(args,exec)=>{
      const profile = await this.store.getProfile(owner.companionId);
      if (!profile.settings.memoryEnabled) return {enabled:false,items:[]};
      const before=await this.store.getState(owner.companionId),turnRevision=this.state(owner.companionId).revision;
      const page=await this.learner.contextMessages(owner.companionId,{signal:exec.signal});
      let result;
      try {result=await this.learner.recall(owner.companionId,page.messages,{query:args.query,signal:exec.signal});}
      catch(error) {if(error.code==='COMPANION_LEARNING_STALE')fail('COMPANION_REPLY_SUPERSEDED');throw error;}
      exec.signal?.throwIfAborted();
      const saved=await this.store.getState(owner.companionId);
      if((saved.memoryRevision??0)!==(before.memoryRevision??0)||this.state(owner.companionId).revision!==turnRevision)fail('COMPANION_REPLY_SUPERSEDED');
      const revision=turnRevision+':'+(saved.memoryRevision??0);
      const previousRecall=this.turnMemories.get(owner.sessionId),items=previousRecall?.revision===revision?previousRecall.items:[];
      const sources=memorySourceIds(owner.companionId,result.items),previous=this.contextSources.get(owner.sessionId)??{};
      await this.store.updateState(owner.companionId,current=>{
        exec.signal?.throwIfAborted();
        if((current.memoryRevision??0)!==(before.memoryRevision??0)||this.state(owner.companionId).revision!==turnRevision)fail('COMPANION_REPLY_SUPERSEDED');
        return {sessionSources:{...current.sessionSources,[owner.sessionId]:{
        ...current.sessionSources?.[owner.sessionId],messageIds:[...new Set([...(current.sessionSources?.[owner.sessionId]?.messageIds??[]),
          ...sources])],
      }}};});
      exec.signal?.throwIfAborted();
      if(this.state(owner.companionId).revision!==turnRevision)fail('COMPANION_REPLY_SUPERSEDED');
      this.turnMemories.set(owner.sessionId,{revision,items:[...new Map([...items,...result.items].map(item=>[item.recordId,item])).values()]});
      this.contextSources.set(owner.sessionId,{...previous,contextSourceIds:[...new Set([...(previous.contextSourceIds??[]),...sources])]});
      return result;
    });
    register('companion_remember','rememberDescription',{text:{type:'string',required:true},sourceIds:{type:'array',items:{type:'string'},required:true},kind:{type:'string',enum:['fact','preference','decision','task_summary']}},async args=>{
      const profile = await this.store.getProfile(owner.companionId);
      if (!profile.settings.memoryEnabled) return {enabled:false};
      if(!args.sourceIds.length)fail('COMPANION_LEARNING_SOURCE_REQUIRED');
      for (const source of args.sourceIds) {
        const message=await this.store.getMessage(owner.companionId,source);
        if(message?.role!=='user'||message.contextExcluded)fail('COMPANION_LEARNING_SOURCE_REQUIRED');
      }
      const memory=await this.memory(owner.companionId),evidence=[];
      for(const source of args.sourceIds) {
        const item=await this.store.getMessage(owner.companionId,source);
        const text=item.text||item.transcript;if(!text)fail('COMPANION_LEARNING_SOURCE_REQUIRED');
        evidence.push({sourceRef:memory.sourceRef(source),text,occurredAt:item.createdAt,role:'user'});
      }
      return memory.remember({text:args.text,kind:args.kind,sourceRefs:args.sourceIds,evidence,assertion:'model_inference'});
    });
    register('companion_forget','forgetDescription',{recordId:{type:'string',required:true},expectedRevisionId:{type:'string',required:true},requestMessageId:{type:'string',required:true}},async(args,exec)=>{
      const request=await this.store.getMessage(owner.companionId,args.requestMessageId);
      if(request?.role!=='user'||request.contextExcluded)fail('COMPANION_LEARNING_SOURCE_REQUIRED');
      const result=await this.forget(owner.companionId,args,{reset:owner.kind==='work',requestMessageId:args.requestMessageId});
      if(owner.kind==='chat')this.state(owner.companionId).resetAfterTurn=true;
      await this.store.appendMessage(owner.companionId,{role:'assistant',type:'text',text:this.copy().forgetReceipt,status:'sent'});
      this.emit(owner.companionId);
      this.state(owner.companionId).turnAction='forget';exec.concludeTurn();return result;
    });
    register('companion_work','workDescription',{title:{type:'string',required:true},goal:{type:'string',required:true},taskId:{type:'string'}},args=>{
      if (owner.kind === 'work') fail('COMPANION_NESTED_WORK');
      return this.startWork(owner.companionId,args);
    });
    register('companion_stickers','stickersDescription',{query:{type:'string'},tag:{type:'string'}},async args=>({items:this.stickers(await this.store.getProfile(owner.companionId),args)}));
    register('companion_sticker_search','stickerSearchDescription',{
      query:{type:'string',required:true},tag:{type:'string'},kind:{type:'string',enum:['stickers','packs']},
      format:{type:'string',enum:['any','gif','static']},
    },(args,exec)=>this.searchOnlineStickers(owner.companionId,{...args,signal:exec.signal}));
    register('companion_sticker_download','stickerDownloadDescription',{
      stickerId:{type:'string'},packId:{type:'string'},
    },(args,exec)=>this.downloadOnlineSticker(owner.companionId,args,{signal:exec.signal,owner}));
    register('companion_emoji','emojiDescription',{msg_id:{type:'string'},quoteReply:{type:'boolean'},query:{type:'string'},tag:{type:'string'},stickerId:{type:'string'}},async(args,exec)=>{
      const proactive=!!this.state(owner.companionId).proactiveIntent;
      const result=await this.sendEmoji(owner,args,exec.signal);if(proactive)exec.concludeTurn();return result;
    });
    agent.ctx.tools.register(defineTool({name:'companion_attachment',description:this.copy().attachmentDescription,
      parameters:{attachmentId:{type:'string',required:true}},
      output:{schema:{type:'json'},render:(_args,value)=>[
        {type:'text',text:JSON.stringify({...value,attachment:undefined})},
        ...(value.kind==='image'?[{type:'image',attachment:value.attachment}]:[]),
      ]},execute:(args,exec)=>readSharedAttachment({ctx:this.ctx,store:this.store,companionId:owner.companionId,
        attachmentId:args.attachmentId,signal:exec.signal,locale:this.locale()})}));
    this.decorated.add(agent);this.restrictedAgents.set(agent.id,agent);
    agent.ctx.effect(()=>()=>{this.restrictedAgents.delete(agent.id);this.contextSources.delete(agent.id);this.turnMemories.delete(agent.id);this.turnProfiles.delete(agent.id);this.decorated.delete(agent);});
  }
  async submit(id,input) {
    const finish=this.beginChat(id);
    try {
    if (this.closed) fail('COMPANION_CLOSED');
    const admission=this.state(id),cancelRevision=admission.chatCancelRevision;
    if(admission.stopPending)fail('COMPANION_CANCELLED');
    const profile = await this.profileForUse(id);
    if (!profile.model) fail('COMPANION_MODEL_REQUIRED');
    const {clientId,text='',attachments=[],voice,replyTo,stickerId} = input;
    if (!text.trim() && !attachments.length && !voice && !stickerId) fail('COMPANION_EMPTY_MESSAGE');
    const state=this.state(id),existing=clientId ? await this.store.getMessage(id,clientId):null;
    if(stickerId&&!existing&&!this.stickers(profile,{all:true}).some(item=>item.id===stickerId))fail('COMPANION_INVALID_STICKER');
    if(this.memoryManagement.has(id))fail('COMPANION_MEMORY_MANAGEMENT_IN_PROGRESS');
    const saved=await this.store.appendMessage(id,{...(clientId?{id:clientId,clientId}:{}),role:'user',
      type:voice?'voice':stickerId?'sticker':attachments[0]?.mediaType?.startsWith('image/')?'image':attachments.length?'file':'text',
      text,attachments,...(voice?{voice}:{}),...(replyTo?{replyTo,quoteReply:true}:{}),...(stickerId?{stickerId}:{}),
      status:'received'});
    if(cancelRevision!==state.chatCancelRevision) {
      await this.store.completeInputs(id,[saved.id]);
      return this.store.getMessage(id,saved.id);
    }
    if(existing?.status==='sent'||state.admitted.has(saved.id))return saved;
    state.admitted.add(saved.id);
    try{await this.enqueue(id,saved);}catch(error){state.admitted.delete(saved.id);throw error;}
    return saved;
    } finally {finish();}
  }
  async enqueue(id,saved) {
    const finish=this.beginChat(id);
    try {
    const state=this.state(id);
    if(saved.type==='task'&&(saved.task?.cancelRequested||state.chatCancelRequested||state.stopPending))return;
    if(state.stopPending)fail('COMPANION_CANCELLED');
    delete state.error;
    state.chatCancelRequested=false;state.chatWaitScheduled=false;state.chatRecoveryScheduled=false;
    state.revision++; state.pending.push(saved); state.replyAbort?.abort();
    clearTimeout(state.timer); clearTimeout(state.waitTimer);
    this.maintenance.cancelRecovery('chat:'+id);this.maintenance.cancelRecovery('wait:'+id);
    state.waitAbort?.abort('new_message');
    state.proactiveIntent=null;state.proactiveAbort?.abort('new_message');
    await this.store.updateState(id,current=>({...(state.waitAbort?{}:{waitingUntil:null,pendingIntent:null}),replyDraft:null,
      ...(saved.role==='user'?{lastUserAt:saved.createdAt??new Date().toISOString(),proactiveUnansweredCount:0}:{}),
      pendingMessageIds:[...new Set([...(current.pendingMessageIds??[]),saved.id])]}));
    state.phase='thinking'; this.emit(id);
    const now=Date.now();state.batchStartedAt??=now;
    const delay=inputBatchDelay({now,firstAt:state.batchStartedAt,count:state.pending.length,lastReplyAt:state.lastReplyAt,
      imageOnly:saved.type==='image'&&!saved.text?.trim(),overrideMs:this.options.debounceMs});
    state.timer=setTimeout(()=>this.flush(id).catch(error=>this.report(id,error)),delay);
    } finally {finish();}
  }
  async transcribeVoice(id,messageId,text) {
    const finish=this.beginChat(id);
    try {
    if(typeof text!=='string'||!text.trim()||text.length>32768)fail('COMPANION_INVALID_INPUT');
    const item=await this.store.getMessage(id,messageId);
    if(!item?.voice)fail('COMPANION_NOT_FOUND');
    if(item.voice.transcriptionState==='ready')return item;
    const saved=await this.store.updateMessage(id,messageId,{text:text.trim(),status:'received',voice:{...item.voice,transcript:text.trim(),transcriptionState:'ready'}});
    await this.enqueue(id,saved);return saved;
    } finally {finish();}
  }
  async retry(id) {
    const finish=this.beginChat(id);
    try {
    if(this.state(id).stopPending)fail('COMPANION_CANCELLED');
    this.state(id).chatCancelRequested=false;
    this.state(id).phase='thinking';delete this.state(id).error;this.emit(id,'status');
    const state=await this.store.getState(id);
    if(state.replyDraft)return await this.recoverReply(id);
    this.state(id).pending=await this.activePending(id,[...(state.pendingMessageIds??[]),...(await this.store.pendingMessages(id)).map(item=>item.id)]);
    return await this.flush(id);
    } finally {finish();}
  }
  async activePending(id,messageIds) {
    const state=await this.store.getState(id),baseline=Number((state.forgottenThrough??'seq:0').slice(4)),active=[];
    for(const messageId of [...new Set(messageIds)]) {
      const message=await this.store.getMessage(id,messageId),cursor=message?await this.store.getMessageCursor(id,messageId):null;
      if(message?.status==='received'&&!message.contextExcluded
        &&!(message.type==='task'&&(message.task?.cancelRequested||['cancelling','cancelled'].includes(message.task?.state)))
        &&!state.forgottenMessageIds?.includes(messageId)&&cursor&&Number(cursor.slice(4))>baseline)active.push(message);
    }
    return active;
  }
  async flush(id) {
    const finish=this.beginChat(id),cancelRevision=this.state(id).chatCancelRevision;
    this.state(id).dispatching=(this.state(id).dispatching??0)+1;
    try {
    this.state(id).batchStartedAt=null;clearTimeout(this.state(id).timer);
    const state=this.state(id),pending=await this.activePending(id,state.pending.splice(0).map(item=>item.id));
    if(cancelRevision!==state.chatCancelRevision)return;
    if (!pending.length || this.closed) {if(!this.closed){state.phase='idle';this.emit(id,'status');}return;}
    let agent;
    try {agent=await this.agent(id);}catch(error){if(cancelRevision===state.chatCancelRevision)state.pending.unshift(...pending);throw error;}
    if(this.closed||cancelRevision!==state.chatCancelRevision)return;
    const content=pending.filter(item=>item.role==='user').map(item=>({id:item.id,text:item.text,type:item.type,voice:item.voice,attachments:item.attachments,replyTo:item.replyTo,...stickerContext(item,this.locale(),this.catalog())}));
    const taskResults=pending.filter(item=>item.role==='assistant'&&item.type==='task').map(item=>({taskId:item.id,...item.task}));
    const input=createUserMessage({source:{kind:'user'},content:[
      {type:'text',text:JSON.stringify({userMessages:content.map(item=>({...item,attachments:item.attachments?.map(upload=>({id:upload.id,name:upload.name,mediaType:upload.mediaType}))})),
        ...(taskResults.length?{internalTaskResults:taskResults}:{})})},
      ...pending.flatMap(item=>(item.attachments??[]).filter(upload=>upload.mediaType?.startsWith('image/')).map(upload=>({type:'image',attachment:upload.ref}))),
    ]});
    await this.store.updateState(id,{chatNeedsReset:true});
    if(this.closed||cancelRevision!==state.chatCancelRevision)return;
    state.turnAction=null;
    state.inFlightIds=[...new Set([...(state.inFlightIds??[]),...pending.map(item=>item.id)])];
    if (agent.status === 'running') agent.steer(input); else agent.followup(input);
    } finally {this.state(id).dispatching--;finish();}
  }
  async reply(owner,args,signal) {
    const finish=owner.kind==='chat'?this.beginChat(owner.companionId):()=>{};
    try {
    const id=owner.companionId,state=this.state(id),revision=state.revision;
    const profile=this.turnProfiles.get(owner.sessionId)??await this.profileForUse(id),locale=this.locale();
    const abort=new AbortController(); if(owner.kind==='chat')state.replyAbort=abort;
    else state.workReplyAborts.set(owner.sessionId,abort);
    const combined=signal ? AbortSignal.any([signal,abort.signal]):abort.signal;
    if(owner.kind==='chat')state.phase='typing'; this.emit(id,'status');
    const context=await this.context(id,owner,combined),options=this.options.maibot??{};
    const replyTo=args.msg_id||args.replyTo;
    const target=replyTo?context.messages.find(item=>item.id===replyTo):null;
    if(args.quoteReply!==undefined&&typeof args.quoteReply!=='boolean')fail('COMPANION_INVALID_INPUT');
    if(args.quoteReply===true&&(!target||!['user','assistant'].includes(target.role)||target.type==='task'))fail('COMPANION_REPLY_SOURCE_REQUIRED');
    const generate=({system,messages,signal:requestSignal})=>this.generate(profile,system,null,
      {messages:messages.map(item=>({...item,content:typeof item.content==='string'?[{type:'text',text:item.content}]:item.content})),
        signal:requestSignal??combined,sessionId:owner.sessionId});
    const expressions=await selectExpressions({context,args,locale,generate,signal:combined,
      random:options.random,options:options.expression,profile});
    const route=await this.profileForUse(id),preparedCall=await this.ctx.llm.prepareCall(route.model,combined);
    const visual=options.enableVisualMessage!==false&&(preparedCall.inputModalities
      ?preparedCall.inputModalities.includes('image'):options.enableVisualMessage===true);
    if(visual)for(const item of context.messages)for(const attachment of item.attachments??[]) {
      if(attachment.mediaType?.startsWith('image/')) {
        const verified=await readSharedAttachment({ctx:this.ctx,store:this.store,companionId:id,
          attachmentId:attachment.id,signal:combined,locale});
        if(verified.kind==='image')attachment.ref=verified.attachment;
      }
    }
    const request=buildReplyerRequest({profile,locale,context,args,expressions:expressions.prompt,kind:owner.kind,
      options:{...options,enableVisualMessage:visual,stickerCatalog:this.catalog()}});
    const text=companionReplyText(await this.generate(profile,request.system,null,{messages:request.messages.map(item=>({...item,
      content:typeof item.content==='string'?[{type:'text',text:item.content}]:item.content})),signal:combined,sessionId:owner.sessionId,preparedCall}));
    combined.throwIfAborted();
    if (owner.kind === 'work') {
      state.workResults ??= new Map(); state.workResults.set(owner.sessionId,text);
      return {delivered:false,result:text};
    }
    if (revision !== state.revision) fail('COMPANION_REPLY_SUPERSEDED');
    if(replyTo&&!await this.store.getMessage(id,replyTo))fail('COMPANION_REPLY_SOURCE_REQUIRED');
    const imageIds=[...(args.imageIds??[])];
    for(const image of args.attach_pic??[]) {
      const source=await this.store.getMessage(id,image.msg_id),index=image.index??0;
      const attachment=source?.attachments?.filter(item=>item.mediaType?.startsWith('image/'))[index];
      if(!Number.isSafeInteger(index)||index<0||!attachment)fail('COMPANION_REPLY_SOURCE_REQUIRED');
      imageIds.push(attachment.id);
    }
    const images=await Promise.all(imageIds.map(attachmentId=>replyAttachment({ctx:this.ctx,store:this.store,
      companionId:id,attachmentId,signal:combined})));
    const parts=[{type:'text',text:text.trim(),quoteReply:args.quoteReply===true,
      ...this.contextSources.get(owner.sessionId),...(replyTo?{replyTo}:{})}];
    const stickerId=args.stickerId??(options.useNewSendLogic&&args.attach_emoji?this.stickers(profile,{all:true}).find(item=>item.emoji_id===args.attach_emoji)?.id:undefined);
    if(stickerId&&this.stickers(await this.store.getProfile(id),{all:true}).some(item=>item.id===stickerId)) {
      parts.push({type:'sticker',text:'',stickerId,...this.contextSources.get(owner.sessionId)});
    }
    if(images.length) {
      parts.push({type:'image',text:'',attachments:images,...this.contextSources.get(owner.sessionId)});
    }
    const draft=await prepareReply({store:this.store,companionId:id,sourceMessageIds:state.inFlightIds??[],parts,proactive:state.proactiveIntent});
    const delivered=await this.publish(id,draft,combined,{onCommitted:()=>{
      state.delivered++;state.turnAction='reply';state.replyAbort=null;state.phase='idle';delete state.error;this.emit(id,'status');
    }}),ids=delivered.messageIds;
    if(delivered.suppressed){state.phase='idle';this.emit(id,'status');return {delivered:false,reason:delivered.reason??'proactive_no_longer_appropriate'};}
    const notification=delivered.notification;
    return {delivered:true,messageIds:ids,text,parts:draft.parts.filter(part=>ids.includes(part.id)).map(part=>({messageId:part.id,type:part.type,text:part.text,
      ...(part.stickerId?{stickerId:part.stickerId}:{})})),notification};
    } finally {if(owner.kind==='work')this.state(owner.companionId).workReplyAborts.delete(owner.sessionId);finish();}
  }
  catalog() { return this.stickerLibrary?.catalog(this.locale())??stickerCatalog(this.locale()).map(item=>({...item,packId:'droy-default'})); }
  stickerPacks() { return this.stickerLibrary?.packs(this.locale())??[{id:'droy-default',name:this.copy().defaultStickerPack,count:stickerCatalog().length,builtin:true}]; }
  stickers(profile,{query='',tag='',all=false}={}) {
    const packIds=profile?.settings?.stickerPackIds??['droy-default'];
    const enabled=this.catalog().filter(item=>packIds.includes(item.packId)).map((item,index)=>({emoji_id:index+1,...item}));
    if(all)return enabled;
    if(typeof query!=='string'||query.length>2000||typeof tag!=='string'||tag.length>80)fail('COMPANION_INVALID_INPUT');
    if(this.stickerLibrary&&(query.trim()||tag.trim())) {
      const matches=new Set(this.stickerLibrary.search(this.locale(),{packIds,query:query.trim(),tag:tag.trim(),limit:12}).map(item=>item.id));
      return enabled.filter(item=>matches.has(item.id)).slice(0,12);
    }
    return enabled.slice(0,12);
  }
  async importStickerPack(id,bytes,{name,signal}={}) {
    return this.maintenance.run('sticker-import',async()=>{
      if(this.closed)fail('COMPANION_CLOSED');
      if(!this.stickerLibrary)fail('COMPANION_STICKER_PACK_UNSUPPORTED');
      const combined=signal?AbortSignal.any([signal,this.lifetime.signal]):this.lifetime.signal;
      combined.throwIfAborted();await this.store.getProfile(id);
      const pack=await this.stickerLibrary.importZip(bytes,{name,signal:combined});
      combined.throwIfAborted();
      const current=await this.store.getProfile(id);
      await this.store.updateProfile(id,{settings:{stickerPackIds:[...new Set([...(current.settings.stickerPackIds??['droy-default']),pack.id])]}});
      this.emit(id,'profile');return pack;
    },id);
  }
  async searchOnlineStickers(id,{query='',tag='',kind='stickers',format='any',signal}={}) {
    return this.maintenance.run('sticker-online-search',async()=>{
      if(this.closed)fail('COMPANION_CLOSED');
      if(!this.onlineStickers)fail('COMPANION_STICKER_PACK_UNSUPPORTED');
      if(typeof query!=='string'||query.length>200||!query.isWellFormed()||typeof tag!=='string'||tag.length>80
        ||!['stickers','packs'].includes(kind)||!['any','gif','static'].includes(format)
        ||kind==='packs'&&format!=='any'||kind==='stickers'&&!query.trim())fail('COMPANION_INVALID_INPUT');
      const combined=signal?AbortSignal.any([signal,this.lifetime.signal]):this.lifetime.signal;
      combined.throwIfAborted();
      if(id)await this.store.getProfile(id);
      const input={query:query.trim(),tag:tag.trim(),format,limit:12,signal:combined,locale:this.locale()};
      const result=await (kind==='packs'?this.onlineStickers.market(input):this.onlineStickers.search(input));
      combined.throwIfAborted();return result;
    },id);
  }
  async downloadOnlineSticker(id,input,{signal,owner}={}) {
    return this.maintenance.run('sticker-online-download',async()=>{
      if(this.closed)fail('COMPANION_CLOSED');
      if(!this.onlineStickers)fail('COMPANION_STICKER_PACK_UNSUPPORTED');
      if(!input||typeof input!=='object'||Array.isArray(input)||Object.keys(input).some(key=>!['stickerId','packId'].includes(key))
        ||Number(input.stickerId!==undefined)+Number(input.packId!==undefined)!==1)fail('COMPANION_INVALID_INPUT');
      const key=input.stickerId??input.packId;
      if(typeof key!=='string'||!key.trim()||key.length>1024||!key.isWellFormed())fail('COMPANION_INVALID_INPUT');
      const combined=signal?AbortSignal.any([signal,this.lifetime.signal]):this.lifetime.signal;
      const state=this.state(id),revision=state.revision;
      combined.throwIfAborted();await this.store.getProfile(id);
      if(owner) {
        if(owner.kind!=='chat'||owner.companionId!==id)fail('COMPANION_TOOL_NOT_ALLOWED');
        if(revision!==state.revision)fail('COMPANION_REPLY_SUPERSEDED');
        if(state.stickerDownloadRevision!==revision) {state.stickerDownloadRevision=revision;state.stickerDownloads=0;}
        if(state.stickerDownloads>=3)return {downloaded:false,reason:'turn_download_limit',message:this.copy().stickerDownloadLimit};
        state.stickerDownloads++;
      }
      const options={signal:combined,locale:this.locale()};
      const result=await (input.packId!==undefined?this.onlineStickers.install(key,options):this.onlineStickers.download(key,options));
      combined.throwIfAborted();
      // A new user message may supersede a download without removing shared media
      // already saved for another conversation or historical message.
      if(owner&&revision!==state.revision)fail('COMPANION_REPLY_SUPERSEDED');
      const current=await this.store.getProfile(id);
      combined.throwIfAborted();
      if(owner&&revision!==state.revision)fail('COMPANION_REPLY_SUPERSEDED');
      await this.store.updateProfile(id,{settings:{stickerPackIds:[...new Set([...(current.settings.stickerPackIds??['droy-default']),result.pack.id])]}});
      this.emit(id,'profile');return {...result,downloaded:true};
    },id);
  }
  async sendEmoji(owner,args,signal) {
    if(owner.kind==='work')fail('COMPANION_TOOL_NOT_ALLOWED');
    const combined=signal?AbortSignal.any([signal,this.lifetime.signal]):this.lifetime.signal;
    combined.throwIfAborted();
    const finish=this.beginChat(owner.companionId);
    try {
    const id=owner.companionId,state=this.state(id),revision=state.revision,profile=await this.profileForUse(id),locale=this.locale();
    const context=await this.context(id,owner,combined);
    const enabled=this.stickers(profile,{all:true});
    const query=args.query??(enabled.length>12?context.messages.findLast(item=>item.role==='user'&&item.text?.trim())?.text.slice(0,2000)??'':'');
    const exact=args.stickerId===undefined?null:enabled.find(item=>item.id===args.stickerId);
    if(args.stickerId!==undefined&&!exact)fail('COMPANION_INVALID_STICKER');
    const items=(exact?[exact]:this.stickers(profile,{query,tag:args.tag??''})).map((item,index)=>({...item,emoji_id:index+1}));
    if(!items.length)return {delivered:false,reason:enabled.length?'no_matching_sticker':'no_available_stickers',message:this.copy().noStickers};
    const target=context.messages.find(item=>item.id===args.msg_id);
    if(args.msg_id&&!target)fail('COMPANION_REPLY_SOURCE_REQUIRED');
    if(args.quoteReply!==undefined&&typeof args.quoteReply!=='boolean')fail('COMPANION_INVALID_INPUT');
    if(args.quoteReply===true&&(!target||!['user','assistant'].includes(target.role)||target.type==='task'))fail('COMPANION_REPLY_SOURCE_REQUIRED');
    let selected=exact;
    if(!selected) {
      const system=renderMaiBotPrompt('emoji_selection',locale,{emoji_count:items.length,grid_rows:1,grid_columns:items.length});
      const raw=await this.generate(profile,system,{conversation:context.messages.slice(-20),msg_id:args.msg_id??'',
        candidateFormat:this.copy().emojiCandidates,items:items.map(({emoji_id,label,description,tags})=>({emoji_index:emoji_id,label,description,tags}))},
      {signal:combined,sessionId:owner.sessionId});
      const selection=JSON.parse(raw.trim().replace(/^```(?:json)?\s*/u,'').replace(/\s*```$/u,''));
      selected=items.find(item=>item.emoji_id===selection.emoji_index);
    }
    if(!selected)fail('COMPANION_INVALID_STICKER');
    if(!this.stickers(await this.store.getProfile(id),{all:true}).some(item=>item.id===selected.id))
      return {delivered:false,reason:'sticker_no_longer_available',message:this.copy().noStickers};
    combined.throwIfAborted();
    if(revision!==state.revision)fail('COMPANION_REPLY_SUPERSEDED');
    const parts=[{type:'sticker',text:'',stickerId:selected.id,quoteReply:args.quoteReply===true,...this.contextSources.get(owner.sessionId),...(args.msg_id?{replyTo:args.msg_id}:{})}];
    const draft=await prepareReply({store:this.store,companionId:id,sourceMessageIds:state.inFlightIds??[],parts,proactive:state.proactiveIntent});
    const delivered=await this.publish(id,draft,combined,{onCommitted:()=>{
      state.turnAction='emoji';state.delivered++;this.emit(id,'status');
    }});
    if(delivered.suppressed){state.phase='idle';this.emit(id,'status');return {delivered:false,reason:delivered.reason??'proactive_no_longer_appropriate'};}
    return {delivered:true,messageIds:delivered.messageIds,stickerId:selected.id,description:selected.description,notification:delivered.notification};
    } finally {finish();}
  }
  async wait(owner,args,signal) {
    if(owner.kind==='work')fail('COMPANION_WORK_CANNOT_WAIT');
    if(!Number.isFinite(args.seconds)||args.seconds<=0||args.seconds>86400)fail('COMPANION_INVALID_WAIT');
    const finish=this.beginChat(owner.companionId);
    try {
    const id=owner.companionId,state=this.state(id),startedAt=Date.now(),abort=new AbortController();
    const combined=AbortSignal.any([this.lifetime.signal,abort.signal,...(signal?[signal]:[])]);
    state.waitAbort=abort;state.phase='waiting';state.turnAction='wait';state.waitCount=(state.waitCount??0)+1;
    await this.store.updateState(id,{waitingUntil:new Date(startedAt+args.seconds*1000).toISOString(),pendingIntent:{kind:'wait',
      waitId:randomUUID(),sessionId:owner.sessionId,sourceMessageIds:state.inFlightIds??[]}});this.emit(id,'status');
    let reason='timeout';
    try {await pause(args.seconds*1000,undefined,{signal:combined});}
    catch(error) {
      if(abort.signal.reason!=='new_message'||signal?.aborted||this.lifetime.signal.aborted)throw error;
      reason='new_message';
    } finally {
      if(state.waitAbort===abort) {
        state.waitAbort=null;
        // Lifecycle shutdown preserves the deadline for start() to resume.
        if(!this.closed) {
          await this.store.updateState(id,{waitingUntil:null,pendingIntent:null});
          if(state.phase==='waiting')state.phase='thinking';this.emit(id,'status');
        }
      }
    }
    return {waitedSeconds:(Date.now()-startedAt)/1000,reason,...(state.waitCount>1?{reminder:buildWaitReminder(this.locale())}:{})};
    } finally {finish();}
  }
  async publish(id,draft,signal,{alert=true,onCommitted}={}) {
    const finish=this.beginChat(id);
    try {
      let result;
      try {result=await publishReply({store:this.store,companionId:id,draft,signal,onSent:()=>this.emit(id,'message'),
        beforeSend:async({message})=>{
          const profile=await this.store.getProfile(id);
          if(message.stickerId&&!this.stickers(profile,{all:true}).some(item=>item.id===message.stickerId))return false;
          if(!draft.proactive)return;
          const clock=await this.clock(signal),saved=await this.store.getState(id);
          if(!profile.settings.proactiveEnabled||isQuietTime(profile.settings.quietHours,new Date(clock.currentTime),clock.timeZone)
            ||this.state(id).pending.length||Date.parse(saved.lastUserAt??'')>Date.parse(draft.createdAt))fail('COMPANION_PROACTIVE_SUPERSEDED');
        }});}
      catch(error) {
        if(error.code!=='COMPANION_PROACTIVE_SUPERSEDED')throw error;
        await this.store.completeInputs(id,draft.sourceMessageIds,{draftId:draft.id,memoryRevision:draft.memoryRevision});
        this.state(id).proactiveIntent=null;
        return {messageIds:[],newMessageIds:[],suppressed:true};
      }
      if(!result.messageIds.length&&draft.parts.some(part=>part.type==='sticker'))
        return {...result,suppressed:true,reason:'sticker_no_longer_available'};
      if(result.newMessageIds?.length)this.state(id).lastReplyAt=Date.now();
      if(draft.proactive)this.state(id).proactiveIntent=null;
      // A slow notification bridge must not prolong the visible typing state
      // after the chat message itself has already been committed.
      await onCommitted?.(result);
      return {...result,notification:await this.deliverNotifications(id,{alertMessageId:alert?result.newMessageIds?.[0]:undefined})};
    }
    finally {finish();}
  }
  async recoverReply(id) {
    const finish=this.beginChat(id),cancelRevision=this.state(id).chatCancelRevision;
    try {
    const state=this.state(id),saved=await this.store.getState(id),draft=saved.replyDraft;
    if(cancelRevision!==state.chatCancelRevision)return;
    if(!draft)return await this.flush(id);
    const abort=new AbortController();state.replyAbort=abort;state.phase='typing';this.emit(id,'status');
    const delivered=await this.publish(id,draft,AbortSignal.any([abort.signal,this.lifetime.signal]),{alert:false});
    for(const messageId of draft.sourceMessageIds)state.admitted.delete(messageId);
    state.pending=await this.activePending(id,(await this.store.getState(id)).pendingMessageIds??[]);
    state.inFlightIds=[];state.phase='idle';delete state.error;
    await this.store.updateState(id,{chatNeedsReset:state.pending.length>0});
    this.emit(id,'status');if(state.pending.length)return await this.flush(id);
    } finally {finish();}
  }
  notificationPreview(message) {
    if(message.text?.trim())return [...message.text].slice(0,2000).join('');
    const copy=this.copy();
    return message.type==='sticker'?copy.notificationSticker:message.type==='image'?copy.notificationImage
      :message.type==='voice'?copy.notificationVoice:message.type==='file'?copy.notificationFile:copy.notificationMessage;
  }
  sendNotification(value) {
    this.lifetime.signal.throwIfAborted();
    const pending=Promise.resolve().then(()=>this.notify(value,this.lifetime.signal));
    this.background.add(pending);
    pending.finally(()=>this.background.delete(pending)).catch(()=>{});
    return pending;
  }
  deliverNotifications(id,{alertMessageId}={}) {
    const deliveries=this.notificationDeliveries??=new Map(),previous=deliveries.get(id)??Promise.resolve();
    const pending=previous.catch(()=>{}).then(async()=>{
      try {
        if(this.closed)return {status:'stopped'};
        for(const messageId of await this.store.pendingNotificationRemovals(id)) {
          try {
            const {unreadCount}=await this.store.notificationReadState(id);
            const notice=await this.sendNotification({operation:'companionRemove',companionId:id,messageId,unreadCount});
            if(['failed','unknown','dispatching'].includes(notice?.status))throw Object.assign(new Error('Notification cleanup is pending'),{code:'COMPANION_NOTICE_CLEANUP_PENDING'});
            await this.store.acknowledgeNotificationRemovals(id,[messageId]);
          } catch(error) {
            this.ctx.logger.warn('COMPANION_NOTICE_REMOVE_FAILED: '+(error.code||error.name));
            return {status:'failed',reason:'notification_removal'};
          }
        }
        const messages=await this.store.pendingNotifications(id);
        if(!messages.length)return {status:'unchanged'};
        const profile=await this.store.getProfile(id),name=localizedProfile(profile,this.locale()).name;
        let result={status:'unchanged'};
        for(const {message,sequence} of messages) {
          const {throughSequence,unreadCount}=await this.store.notificationReadState(id);
          try {
            const notice=await this.sendNotification({operation:'companion',companionId:id,name,messageId:message.id,
              sequence,sentAt:Date.parse(message.createdAt),text:this.notificationPreview(message),throughSequence,unreadCount,
              alert:message.id===alertMessageId,...(profile.avatar?{avatar:profile.avatar}:{})});
            result=notice??{status:'published'};
            if(['failed','unknown','dispatching'].includes(notice?.status))break;
            await this.store.acknowledgeNotifications(id,[message.id]);
          } catch(error) {
            result={status:'failed'};
            this.ctx.logger.warn('COMPANION_NOTICE_FAILED: '+(error.code||error.name));
            break;
          }
        }
        return result;
      } catch(error) {
        this.ctx.logger.warn('COMPANION_NOTICE_FAILED: '+(error.code||error.name));
        return {status:'failed'};
      }
    });
    deliveries.set(id,pending);
    this.background.add(pending);
    pending.finally(()=>{this.background.delete(pending);if(deliveries.get(id)===pending)deliveries.delete(id);}).catch(()=>{});
    return pending;
  }
  async syncReadNotifications(id,read) {
    try {
      read??=await this.store.notificationReadState(id);
      return await this.sendNotification({operation:'companionRead',companionId:id,...read});
    } catch(error) {
      this.ctx.logger.warn('COMPANION_NOTICE_READ_FAILED: '+(error.code||error.name));
      return {status:'failed'};
    }
  }
  async markRead(id,messageId) {
    if(this.closed)fail('COMPANION_CLOSED');
    const result=await this.store.markRead(id,messageId);
    await this.syncReadNotifications(id,{throughSequence:result.throughSequence,unreadCount:result.unreadCount});
    this.emit(id,'read');
    return result;
  }
  async finishTurn(owner,event) {
    const finish=owner.kind==='chat'?this.beginChat(owner.companionId):()=>{};
    try {
    if(this.closed||this.ctx.get('droyLifecycle')?.stopping)return;
    const id=owner.companionId,state=this.state(id);
    if (owner.kind === 'work') {
      const result=state.workResults?.get(owner.sessionId);
      const completed=event.data?.reason?.kind === 'completed' && typeof result === 'string';
      const taskMessage=await this.store.getMessage(id,owner.taskId);
      if (taskMessage) {
        if(taskMessage.contextExcluded){state.workTasks.delete(owner.taskId);state.workResults?.delete(owner.sessionId);return;}
        const cancelled=state.chatCancelRequested||state.stopPending||taskMessage.task?.cancelRequested
          ||['cancelling','cancelled'].includes(taskMessage.task?.state);
        const task={...taskMessage.task,state:cancelled?'cancelled':completed?'completed':isAborted(event.data?.reason)?'cancelled':'failed',
          summary:cancelled?'':result || '',...(cancelled?{cancelRequested:true}:{})};
        const sources=this.contextSources.get(owner.sessionId)??{};
        const updated=await this.store.updateMessage(id,owner.taskId,{task,status:cancelled?'sent':'received',...sources,
          contextSourceIds:[...new Set([...(taskMessage.contextSourceIds??[]),...(sources.contextSourceIds??[])])]}); this.emit(id);
        state.workResults?.delete(owner.sessionId);
        state.workTasks.delete(owner.taskId);
        if(!cancelled)await this.enqueue(id,updated);
      }
      state.workTasks.delete(owner.taskId);state.workResults?.delete(owner.sessionId);this.activityChanged();
      return;
    }
    if(state.chatCancelRequested||state.stopPending)return;
    if (!state.pending.length && state.phase !== 'waiting') { state.phase='idle'; this.emit(id,'status'); }
    if(state.resetAfterTurn) {
      state.resetAfterTurn=false;state.inFlightIds=[];await this.resetChat(id);
      state.pending=await this.activePending(id,(await this.store.pendingMessages(id)).map(item=>item.id));
      if(state.pending.length)state.timer=setTimeout(()=>this.flush(id).catch(error=>this.report(id,error)),this.options.debounceMs);
      return;
    }
    state.proactiveIntent=null;
    if(event.data?.reason?.kind==='completed') {
      state.waitCount=0;state.proactiveIntent=null;this.turnMemories.delete(owner.sessionId);this.turnProfiles.delete(owner.sessionId);
      const consumed=new Set(state.inFlightIds??[]);state.inFlightIds=[];
      for(const messageId of consumed)state.admitted.delete(messageId);
      await this.store.completeInputs(id,[...consumed]);
      await this.store.updateState(id,{chatNeedsReset:false});
    } else if(!isAborted(event.data?.reason))this.report(id,{code:'COMPANION_MODEL_FAILED'});
    if (event.data?.reason?.kind === 'completed') {
      const task=this.learn(id);this.background.add(task);
      task.catch(error=>{if(!this.closed)this.ctx.logger.warn('COMPANION_LEARNING_FAILED: '+(error.code || error.message));})
        .finally(()=>this.background.delete(task));
    }
    } finally {finish();}
  }
  learn(id) {
    if(this.closed)return Promise.resolve();
    const state=this.state(id);if(state.stopPending||state.chatCancelRequested)return Promise.resolve();
    if(state.learningPromise)return state.learningPromise;
    const controller=new AbortController();this.learningSignals.set(id,controller);
    const task=(async()=>{try {
      let result;
      do {result=await this.learner.maintain(id,{signal:AbortSignal.any([this.lifetime.signal,controller.signal])});}while(result.hasMore&&!result.discarded&&!this.closed);
      if(!this.closed&&!controller.signal.aborted)await this.refreshProactiveSchedule(id);
      return result;
    }finally{if(this.learningSignals.get(id)===controller)this.learningSignals.delete(id);state.learningPromise=null;}})();
    state.learningPromise=task;return task;
  }
  async startWork(id,{title,goal,taskId:existingTaskId}) {
    const state=this.state(id),revision=state.chatCancelRevision;
    const current=()=>!this.closed&&!state.stopPending&&!state.chatCancelRequested&&state.chatCancelRevision===revision;
    if(!current())fail('COMPANION_CANCELLED');
    state.turnAction='work';
    if(existingTaskId) {
      const existing=await this.store.getMessage(id,existingTaskId);
      if(!existing?.task)fail('COMPANION_TASK_NOT_FOUND');
      const owner=await this.store.sessionOwner(existing.task.sessionId);
      if(owner?.companionId!==id||owner.kind!=='work')fail('COMPANION_TASK_NOT_FOUND');
      const invalid=(await this.store.getState(id)).invalidSessionIds??[];
      if(!current())fail('COMPANION_CANCELLED');
      if(!invalid.includes(owner.sessionId)) {
        state.workTasks.set(existingTaskId,owner.sessionId);
        try {
        const agent=await this.agent(id,{kind:'work',sessionId:owner.sessionId,taskId:existingTaskId});
        if(!current())fail('COMPANION_CANCELLED');
        const task={...existing.task,state:'running',summary:'',cancelRequested:false,goal:[existing.task.goal,goal].filter(Boolean).join('\n\n')};
        await this.store.updateMessage(id,existingTaskId,{task,status:'sent'});
        if(!current())fail('COMPANION_CANCELLED');
        this.state(id).workResults?.delete(owner.sessionId);
        if(agent.status==='running')agent.steer(message(goal));else agent.followup(message(goal));
        this.emit(id);return {taskId:existingTaskId,sessionId:agent.id,title:task.title,state:'running'};
        } catch(error) {
          const cancelled=!current()||error.code==='COMPANION_CANCELLED';
          await this.store.updateMessage(id,existingTaskId,{task:{...existing.task,state:cancelled?'cancelled':'failed',summary:'',
            ...(cancelled?{cancelRequested:true}:{error:error.code||error.message})},status:'sent'});
          state.workTasks.delete(existingTaskId);throw error;
        }
      }
    }
    const sessionId=randomUUID(),taskId=randomUUID();
    const task={id:taskId,sessionId,title,goal,state:'running',summary:''};
    const chatId=(await this.store.getProfile(id)).chatSessionId;
    if(!current())fail('COMPANION_CANCELLED');
    state.workTasks.set(taskId,sessionId);
    let appended=false;
    try {
      await this.store.appendMessage(id,{id:taskId,role:'assistant',type:'task',text:title,task,status:'sent',...this.contextSources.get(chatId)});
      appended=true;this.emit(id);
      if(!current())fail('COMPANION_CANCELLED');
      const agent=await this.agent(id,{kind:'work',sessionId,taskId});
      if(!current())fail('COMPANION_CANCELLED');
      task.sessionId=agent.id;
      await this.store.updateMessage(id,taskId,{task});
      if(!current())fail('COMPANION_CANCELLED');
      agent.followup(message(goal)); this.emit(id);
      return {taskId,sessionId:agent.id,title,state:'running'};
    } catch(error) {
      const cancelled=!current()||error.code==='COMPANION_CANCELLED';
      if(appended)await this.store.updateMessage(id,taskId,{task:{...task,state:cancelled?'cancelled':'failed',
        ...(cancelled?{cancelRequested:true}:{error:error.code || error.message})}});
      state.workTasks.delete(taskId);
      this.emit(id);throw error;
    }
  }
  async resumeWork(owner) {
    if(this.closed)return;
    const id=owner.companionId,state=this.state(id),revision=state.chatCancelRevision;
    const current=()=>!this.closed&&!state.stopPending&&!state.chatCancelRequested&&state.chatCancelRevision===revision;
    const item=await this.store.getMessage(id,owner.taskId);
    if(!item?.task||item.contextExcluded){state.workTasks.delete(owner.taskId);return;}
    if(!current()||item.task.cancelRequested||item.task.state==='cancelling') {
      await this.store.updateMessage(id,item.id,{task:{...item.task,state:'cancelled',summary:'',cancelRequested:true},status:'sent'});
      state.workTasks.delete(owner.taskId);return;
    }
    if(!['running','queued'].includes(item.task.state)){state.workTasks.delete(owner.taskId);return;}
    try {
      state.workTasks.set(owner.taskId,owner.sessionId);
      const agent=await this.agent(id,{kind:'work',sessionId:owner.sessionId,taskId:owner.taskId});
      const latest=await this.store.getMessage(id,owner.taskId);
      if(!current()||latest?.task?.cancelRequested) {
        if(latest?.task)await this.store.updateMessage(id,item.id,{task:{...latest.task,state:'cancelled',summary:'',cancelRequested:true},status:'sent'});
        state.workTasks.delete(owner.taskId);return;
      }
      agent.followup(message(JSON.stringify({internalWorkContinuation:{taskId:item.id,goal:item.task.goal??item.task.title,
        instruction:this.copy().resumeWork}})));
    }catch(error) {
      if(this.closed)throw error;
      if(!current()||error.code==='COMPANION_CANCELLED') {
        await this.store.updateMessage(id,item.id,{task:{...item.task,state:'cancelled',summary:'',cancelRequested:true},status:'sent'});
        state.workTasks.delete(owner.taskId);return;
      }
      state.workTasks.delete(owner.taskId);
      const saved=await this.store.updateMessage(id,item.id,{task:{...item.task,state:'failed',summary:'',error:error.code??'COMPANION_WORK_INTERRUPTED'},status:'received'});
      await this.enqueue(id,saved);throw error;
    }
  }
  async refreshProactiveSchedule(id) {
    const revision=this.state(id).revision,profile=await this.profileForUse(id,{requireModel:false});
    if(!profile.settings.proactiveEnabled)return;
    const saved=await this.store.getState(id),clock=await this.clock();
    const {context}=await this.proactiveContext(id,profile,saved,clock);
    if(this.closed||this.state(id).revision!==revision)return;
    const at=proactiveNextCheckAt(context);
    await this.store.updateState(id,{proactiveNextAt:at});this.scheduleProactive(id,at);
  }
  async proactiveContext(id,profile,saved,clock) {
    const now=Date.parse(clock.currentTime),messages=[];let before;
    // A bounded observation window is reported to the model as observed data.
    for(let pageNumber=0;pageNumber<5;pageNumber++) {
      const page=await this.store.listMessages(id,{limit:200,...(before?{before}:{})});
      messages.unshift(...page.messages);
      if(!page.hasMore||!page.nextBefore||page.messages.some(item=>Date.parse(item.createdAt)<now-7*86400000))break;
      before=page.nextBefore;
    }
    const events=profile.settings.memoryEnabled?await this.learner.activeEvents(id,{signal:this.lifetime.signal}):[];
    const known=new Set(messages.map(item=>item.id));
    for(const source of new Set(events.flatMap(item=>item.sourceIds??[])))if(!known.has(source)&&!source.startsWith('memory:')) {
      const item=await this.store.getMessage(id,source);if(item)messages.push(item);
    }
    const visible=[],baseline=Number((saved.forgottenThrough??'seq:0').slice(4));
    for(const item of messages)if(!item.contextExcluded&&!saved.forgottenMessageIds?.includes(item.id)) {
      const cursor=await this.store.getMessageCursor(id,item.id);
      if(cursor&&Number(cursor.slice(4))>baseline)visible.push(item);
    }
    const records=profile.settings.memoryEnabled?(await(await this.memory(id)).query({states:['active'],limit:80,maxBytes:60000})).items:[],memories=[];
    for(const record of records) {
      if(record.companionId!==id)fail('COMPANION_SOURCE_SCOPE');
      if(record.state!=='active'||!record.payload||record.validFrom&&Date.parse(record.validFrom)>now
        ||record.validUntil&&Date.parse(record.validUntil)<=now)continue;
      if(!await this.learner.hasExcludedSources(id,record.sourceRefs,saved,{memoryRefs:true}))memories.push(record);
    }
    let recentOtherContactAt;
    for(const other of await this.store.profiles())if(other.id!==id) {
      const contact=(await this.store.getState(other.id)).proactiveLastContactAt;
      if(contact&&(!recentOtherContactAt||Date.parse(contact)>Date.parse(recentOtherContactAt)))recentOtherContactAt=contact;
    }
    return {context:buildProactiveContext({profile:localizedProfile(profile,this.locale()),messages:visible,memories,events,state:saved,
      now,timeZone:clock.timeZone,recentOtherContactAt}),memories};
  }
  async considerProactive(id) {
    const state=this.state(id),revision=state.revision,saved=await this.store.getState(id);
    const profile=await this.profileForUse(id,{requireModel:false}),clock=await this.clock();
    if(!profile.settings.proactiveEnabled||!profile.model||!saved.lastUserAt)return new Date(Date.now()+3600000).toISOString();
    const {context,memories}=await this.proactiveContext(id,profile,saved,clock);
    const fallback=proactiveNextCheckAt(context);
    if(this.closed||state.stopPending||state.revision!==revision||state.phase!=='idle'||state.pending.length||state.dispatching
      ||this.proactiveConsidering||[...this.states.entries()].some(([other,live])=>other!==id&&live.proactiveIntent)||isQuietTime(profile.settings.quietHours,new Date(clock.currentTime),clock.timeZone))return fallback;
    this.proactiveConsidering=id;
    const controller=new AbortController();state.proactiveAbort=controller;
    const signal=AbortSignal.any([controller.signal,this.lifetime.signal]);
    try {
      const decision=parseProactiveDecision(await this.generate(profile,proactiveDecisionPrompt(this.locale()),context,{signal}),context);
      signal.throwIfAborted();
      const current=await this.store.getState(id);
      if(state.revision!==revision||(current.memoryRevision??0)!==(saved.memoryRevision??0))return fallback;
      const nextAt=proactiveNextCheckAt(context,decision);
      await this.store.updateState(id,{proactiveDecision:{action:decision.action,kind:decision.kind,
        sourceIds:decision.sourceIds,candidateId:decision.candidateId??null,consideredAt:clock.currentTime},
        proactiveFailures:0,proactiveError:null});
      if(decision.action==='contact')await this.wake(id,{kind:'proactive',decision,expectedRevision:revision,
        expectedMemoryRevision:saved.memoryRevision??0,expectedPersonaRevision:profile.personaRevision??1,
        sourceIds:decision.sourceIds,evidence:context.evidence.filter(item=>decision.sourceIds.includes(item.id)),
        memories:memories.filter(item=>decision.sourceIds.includes('memory:'+item.recordId))});
      return nextAt;
    } finally {
      if(state.proactiveAbort===controller)state.proactiveAbort=null;
      if(this.proactiveConsidering===id)this.proactiveConsidering=null;
    }
  }
  scheduleProactive(id,nextAt) {
    const state=this.state(id),cancelRevision=state.chatCancelRevision;clearTimeout(state.proactiveTimer);
    if(this.closed)return;
    const next=Date.parse(nextAt||'');
    const deadline=Number.isFinite(next)?next:Date.now()+this.options.proactiveIntervalMs;
    state.proactiveTimer=this.maintenance.proactive(id,deadline,async()=>{
      if(state.stopPending||state.chatCancelRevision!==cancelRevision)return;
      let at,removed=false;
      try {at=await this.considerProactive(id);}
      catch(error) {
        if(!this.closed&&error.name!=='AbortError'&&error!=='new_message') {
          this.ctx.logger.warn('COMPANION_PROACTIVE_FAILED: '+(error.code||error.name));
          try {
            const current=await this.store.getState(id),failures=(current.proactiveFailures??0)+1;
            await this.store.updateState(id,{proactiveFailures:failures,proactiveError:error.code||error.name});
            at=new Date(Date.now()+Math.min(3600000,300000*2**Math.min(failures-1,4))).toISOString();
          }catch(storageError) {
            removed=storageError.code==='COMPANION_NOT_FOUND';
            if(!removed)this.ctx.logger.warn('COMPANION_PROACTIVE_STATE_FAILED: '+(storageError.code||storageError.name));
          }
        }
      } finally {
        if(!this.closed&&!removed&&state.chatCancelRevision===cancelRevision) {
          at??=new Date(Date.now()+this.options.proactiveIntervalMs).toISOString();
          try {await this.store.updateState(id,{proactiveNextAt:at});}
          catch(error) {removed=error.code==='COMPANION_NOT_FOUND';if(!removed)this.ctx.logger.warn('COMPANION_PROACTIVE_STATE_FAILED: '+(error.code||error.name));}
          if(!this.closed&&!removed)this.scheduleProactive(id,at);
        }
      }
    });
  }
  scheduleWait(id,saved) {
    const state=this.state(id); clearTimeout(state.waitTimer);state.chatWaitScheduled=false;
    this.maintenance.cancelRecovery('wait:'+id);
    if (!saved.waitingUntil || !saved.pendingIntent) return;
    const deadline=Date.parse(saved.waitingUntil);
    if (!Number.isFinite(deadline)) return;
    state.chatWaitScheduled=true;
    state.waitTimer=this.maintenance.recovery('wait:'+id,deadline,()=>{state.chatWaitScheduled=false;return this.wake(id,saved.pendingIntent).catch(error=>this.report(id,error));},{unref:true});
  }
  async wake(id,intent) {
    const finish=this.beginChat(id);
    try {
    if (this.closed||this.state(id).stopPending) return;
    const live=this.state(id),revision=intent.expectedRevision??live.revision,continuingWait=intent.kind==='wait';
    if(live.revision!==revision)return;
    const saved=continuingWait?await this.store.getState(id):null;
    if(continuingWait&&(!saved.waitingUntil||JSON.stringify(saved.pendingIntent)!==JSON.stringify(intent)))return;
    const profile=await this.store.getProfile(id);
    if(intent.expectedPersonaRevision!==undefined&&(profile.personaRevision??1)!==intent.expectedPersonaRevision)return;
    if(intent.expectedMemoryRevision!==undefined&&((await this.store.getState(id)).memoryRevision??0)!==intent.expectedMemoryRevision)return;
    const clock=await this.clock();
    if(!continuingWait&&(!profile.settings.proactiveEnabled||isQuietTime(profile.settings.quietHours,new Date(clock.currentTime),clock.timeZone)))return;
    if(this.closed||live.revision!==revision)return;
    const agent=await this.agent(id);
    if(this.closed||live.revision!==revision)return;
    if(continuingWait) {
      let claimed=false;
      await this.store.updateState(id,current=>{
        if(this.closed||live.revision!==revision||current.waitingUntil!==saved.waitingUntil
          ||JSON.stringify(current.pendingIntent)!==JSON.stringify(intent))return {};
        claimed=true;return {waitingUntil:null,pendingIntent:null};
      });
      if(!claimed||this.closed||live.revision!==revision)return;
      live.chatWaitScheduled=false;
      const state=this.state(id);state.inFlightIds=[...new Set([...(intent.sourceMessageIds??[]),...state.pending.map(item=>item.id)])];
      state.pending=[];state.phase='thinking';
    }
    if(!continuingWait) {
      const latest=await this.store.getProfile(id);
      if(!latest.settings.proactiveEnabled||live.revision!==revision||live.pending.length
        ||intent.expectedPersonaRevision!==undefined&&(latest.personaRevision??1)!==intent.expectedPersonaRevision
        ||isQuietTime(latest.settings.quietHours,new Date((await this.clock()).currentTime),clock.timeZone))return;
      live.proactiveIntent={sourceIds:intent.sourceIds??[],decision:intent.decision??null};
      const current=await this.store.getState(id);
      if(live.revision!==revision||intent.expectedMemoryRevision!==undefined&&(current.memoryRevision??0)!==intent.expectedMemoryRevision){live.proactiveIntent=null;return;}
      this.turnMemories.set(agent.id,{revision:revision+':'+(current.memoryRevision??0),items:intent.memories??[]});
    }
    const {memories:_memories,expectedRevision:_revision,expectedMemoryRevision:_memoryRevision,expectedPersonaRevision:_personaRevision,...publicIntent}=intent;
    const input=message(JSON.stringify({internalIntent:publicIntent,consideration:continuingWait?this.copy().waitResumed:this.copy().proactive}));
    live.chatCancelRequested=false;
    if(agent.status==='running')agent.steer(input);else agent.followup(input);
    } finally {finish();}
  }
  async invalidate(id,sourceIds,{reset=true,all=false,preserveMemorySources=false}={}) {
    const live=this.state(id);live.revision++;live.replyAbort?.abort();live.waitAbort?.abort();live.proactiveAbort?.abort();live.proactiveIntent=null;this.learningSignals.get(id)?.abort();
    const {excludedIds,newlyExcluded}=await this.store.excludeDerivedMessages(id,sourceIds);
    const affected=[...new Set([...sourceIds,...excludedIds])];
    this.learner.invalidate(id);
    const state=await this.store.updateState(id,current=>({...withoutSources(current,affected),
      memoryRevision:(current.memoryRevision??0)+1,waitingUntil:null,pendingIntent:null,replyDraft:null,
      forgottenMessageIds:[...new Set([...(current.forgottenMessageIds??[]),...(preserveMemorySources?[]:sourceIds)])]}));
    clearTimeout(this.state(id).waitTimer);this.state(id).chatWaitScheduled=false;this.state(id).replyAbort?.abort();
    this.maintenance.cancelRecovery('wait:'+id);
    const sources=new Set(affected),invalid=[];
    for(const owner of await this.store.listSessions(id,{kind:'work'})) {
      const used=state.sessionSources?.[owner.sessionId];
      if(all||used?.hasSummary&&newlyExcluded||used?.messageIds.some(source=>sources.has(source))) {
        invalid.push(owner.sessionId);
        this.state(id).workResults?.delete(owner.sessionId);
        this.state(id).workReplyAborts.get(owner.sessionId)?.abort();
        const agent=this.live.get(owner.sessionId);
        if(agent){this.observeWorkIdle(id,agent);agent.cancel({kind:'user'});}
        this.state(id).workTasks.delete(owner.taskId);
        const taskMessage=owner.taskId?await this.store.getMessage(id,owner.taskId):null;
        if(taskMessage?.task&&['running','queued','cancelling'].includes(taskMessage.task.state))
          await this.store.updateMessage(id,owner.taskId,{task:{...taskMessage.task,state:'cancelled',summary:''}});
      }
    }
    await this.store.updateState(id,current=>({invalidSessionIds:[...new Set([...(current.invalidSessionIds??[]),...invalid])]}));
    const saved=await this.store.getState(id),pending=await this.activePending(id,saved.pendingMessageIds??[]),activeIds=new Set(pending.map(item=>item.id));
    this.state(id).pending=this.state(id).pending.filter(item=>activeIds.has(item.id));
    this.state(id).inFlightIds=(this.state(id).inFlightIds??[]).filter(messageId=>activeIds.has(messageId));
    await this.store.updateState(id,{pendingMessageIds:pending.map(item=>item.id)});
    if(reset)await this.resetChat(id);
    return state;
  }
  async forget(id,input,{reset=true,requestMessageId}={}) {
    const result=await (await this.memory(id)).suppress({recordId:input.recordId,expectedRevisionId:input.expectedRevisionId,operationId:input.operationId??randomUUID()});
    const sourceIds=['memory:'+input.recordId,...result.suppressedSources.map(source=>source.replace('im:'+id+':','')),...(requestMessageId?[requestMessageId]:[])];
    await this.invalidate(id,sourceIds,{reset});
    this.emit(id); return result;
  }
  async reviseMemory(id,input) {
    const memory=await this.memory(id),old=await memory.get(input.recordId);
    const sourceIds=[...(old?['memory:'+old.recordId+':'+old.revisionId]:[]),...(old?.sourceRefs??[]).map(source=>source.replace('im:'+id+':',''))];
    const result=await memory.revise({...input,patch:{sourceRefs:[],assertion:'user_statement',actor:'user'}});
    await this.invalidate(id,sourceIds);this.emit(id);return result;
  }
  async removeMessage(id,messageId) {
    const current=await this.store.getMessage(id,messageId);
    let removed;
    if(current) {
      if((await this.store.pendingNotificationRemovals(id)).length>=200) {
        await this.deliverNotifications(id);
        if((await this.store.pendingNotificationRemovals(id)).length>=200)fail('COMPANION_NOTIFICATION_REMOVALS_PENDING');
      }
      await this.invalidate(id,[messageId],{preserveMemorySources:true});
      removed=await this.store.removeMessage(id,messageId);
      this.state(id).pending=this.state(id).pending.filter(item=>item.id!==messageId);
      await this.store.updateState(id,current=>({pendingMessageIds:(current.pendingMessageIds??[]).filter(value=>value!==messageId)}));
    }
    const notification=await this.deliverNotifications(id);
    this.emit(id);
    if(notification.reason==='notification_removal')fail('COMPANION_NOTIFICATION_REMOVE_FAILED');
    return removed??{removed:true};
  }
  async resetMemories(id) {
    fail('COMPANION_MEMORY_WORKBENCH_REQUIRED');
  }
  async clearMemoryContext(id,baseline) {
    await this.store.updateState(id,{expressions:[],jargon:[],jargonCandidates:[],impressions:[],forgottenThrough:baseline,
      learningCursor:baseline,factLearningCursor:baseline,factsThroughMessageId:null,events:[],summaryCursor:baseline,summary:'',summaryState:null,summaryThrough:null});
    await this.invalidate(id,[],{all:true});this.turnMemories.clear();this.emit(id);
  }
  async listMemorySpaces() {
    for(const profile of await this.store.profiles()) {
      if(!await this.memorySpaces.current(profile.id))await this.memorySpaces.ensureActiveSpace(profile.id,{profile});
    }
    return this.memorySpaces.list();
  }
  async purgeMemorySpace(input,authorization) {
    const grant=consumeCompanionMemoryAuthorization(authorization,{operation:'purge',...input});
    const space=await this.memorySpaces.get(input.memorySpaceId);
    if(!space||space.epoch!==grant.epoch)fail('COMPANION_MEMORY_REVISION_CONFLICT');
    const active=space.state==='active',id=space.companionId;
    if(this.memoryManagement.has(id))fail('COMPANION_MEMORY_MANAGEMENT_IN_PROGRESS');
    this.memoryManagement.add(id);
    try {
    this.learningSignals.get(id)?.abort();
    await this.cancel(id).catch(error=>{if(active||error.code!=='COMPANION_NOT_FOUND')throw error;});
    await this.maintenance.drainMemorySpace(id);
    const boundary=active?(await this.store.listMessages(id,{limit:1})).nextAfter:space.sourceCaptureBoundary;
    await this.memorySpaces.beginPurge(space.memorySpaceId,{expectedRevision:input.expectedRevision,operationId:input.operationId,sourceCaptureBoundary:boundary});
    return await this.finishMemoryPurge(await this.memorySpaces.get(space.memorySpaceId));
    } finally {if((await this.memorySpaces.get(space.memorySpaceId))?.state!=='purging')this.memoryManagement.delete(id);}
  }
  async finishMemoryPurge(space,{queueOffline=true}={}) {
    const id=space.companionId;this.memoryManagement.add(id);
    try {
      const profile=(await this.store.profiles()).find(item=>item.id===id);
      if(profile) {
        await this.cancel(id);
        await this.clearMemoryContext(id,space.sourceCaptureBoundary??'seq:0');
      }
      const pending=this.memories.get(id);
      if(pending) {await(await pending).close();this.memories.delete(id);}
      // Live DSH lists cannot prove that older JSONL generations are gone.
      // Only the native stopped-host maintenance runner may finish this purge.
      if(queueOffline) {
        if(!this.options.requestMemoryPurge)fail('COMPANION_MEMORY_SESSION_PURGE_REQUIRES_OFFLINE');
        const receipt=await this.options.requestMemoryPurge({operation:'purge',memorySpaceId:space.memorySpaceId,operationId:space.purgeOperation});
        if(receipt?.pending!==true&&receipt?.completed!==true)fail('COMPANION_MEMORY_PURGE_UNCONFIRMED');
      }
      return {completed:false,pending:true};
    } finally {if((await this.memorySpaces.get(space.memorySpaceId))?.state!=='purging')this.memoryManagement.delete(id);}
  }
  async restoreMemorySpace(input,authorization) {
    const grant=consumeCompanionMemoryAuthorization(authorization,{operation:'restore',...input});
    const space=await this.memorySpaces.get(input.memorySpaceId);
    if(!space||space.epoch!==grant.epoch)fail('COMPANION_MEMORY_REVISION_CONFLICT');
    if(this.memoryManagement.has(space.companionId))fail('COMPANION_MEMORY_MANAGEMENT_IN_PROGRESS');
    this.memoryManagement.add(space.companionId);
    try {
    await this.memorySpaces.beginRestore(space.memorySpaceId,{expectedRevision:input.expectedRevision,operationId:input.operationId});
    await this.finishMemoryRestore(await this.memorySpaces.get(space.memorySpaceId));
    this.emit(null,'profile');return {completed:true};
    } finally {this.memoryManagement.delete(space.companionId);}
  }
  async finishMemoryRestore(space) {
    const snapshot=await this.memorySpaces.persona(space.memorySpaceId);
    await this.store.restoreRetainedProfile(snapshot);
    await this.prepareWorkspace(space.companionId);
    const profile=await this.store.getProfile(space.companionId);
    await this.memorySpaces.restore(space.memorySpaceId,{workspaceId:profile.workspaceId,operationId:space.restoreOperation});
  }
  async recoverMemoryManagement() {
    for(const metadata of await this.memorySpaces.list()) {
      const space=await this.memorySpaces.get(metadata.memorySpaceId);
      if(space.state==='purging')await this.finishMemoryPurge(space,{queueOffline:false});
      else if(space.state==='retained'&&space.restoreOperation)await this.finishMemoryRestore(space);
    }
  }
  async resumeMemoryPurges() {
    for(const metadata of await this.memorySpaces.list({state:'purging'})) {
      const space=await this.memorySpaces.get(metadata.memorySpaceId);
      await this.options.requestMemoryPurge?.({operation:'purge',memorySpaceId:space.memorySpaceId,operationId:space.purgeOperation});
    }
  }
  async clearHistory(id) {
    await this.cancel(id);await this.store.clearMessages(id);
    await this.invalidate(id,[],{all:true});
    await this.store.updateState(id,{pendingMessageIds:[],summary:'',summaryState:null,summaryThrough:null,events:[]});
    const notification=await this.syncReadNotifications(id);
    this.emit(id);
    if(notification?.status==='failed')fail('COMPANION_NOTIFICATION_REMOVE_FAILED');
    return {cleared:true};
  }
  async resetChat(id) {
    const finish=this.beginChat(id);
    try {
    this.state(id).waitAbort?.abort();
    const key=id,handle=this.handles.get(key);
    if (handle) { const resolved=await handle; resolved.agent.cancel({kind:'user'}); await resolved.dispose(); }
    this.live.delete(key); this.handles.delete(key);
    await this.store.updateProfile(id,{chatSessionId:null});
    } finally {finish();}
  }
  async cancel(id,{taskId} = {}) {
    const state=this.state(id);
    if (taskId) {
      const item=await this.store.getMessage(id,taskId);
      if (!item?.task) fail('COMPANION_TASK_NOT_FOUND');
      if(!['running','queued'].includes(item.task.state))return {cancelRequested:false};
      await this.store.updateMessage(id,taskId,{task:{...item.task,state:'cancelling',cancelRequested:true}});
      const agent=this.ctx.agents.get(item.task.sessionId);
      state.workReplyAborts.get(item.task.sessionId)?.abort();
      if(agent){this.observeWorkIdle(id,agent);agent.cancel({kind:'user'});}
      const operation=(async()=>{
        if(agent){if(typeof agent.whenIdle!=='function')fail('COMPANION_STOP_UNCONFIRMED');await agent.whenIdle();}
        const latest=await this.store.getMessage(id,taskId);
        if(latest?.task?.cancelRequested)await this.store.updateMessage(id,taskId,{task:{...latest.task,state:'cancelled',summary:''},status:'sent'});
        state.workTasks.delete(taskId);this.activityChanged();this.emit(id,'status');
      })();
      this.background.add(operation);
      operation.catch(error=>{state.stopUnconfirmed=true;this.ctx.logger.warn('COMPANION_STOP_UNCONFIRMED: '+(error.code||error.name));})
        .finally(()=>this.background.delete(operation));
    } else {
      if(state.stopPending)return {cancelRequested:true};
      state.chatCancelRequested=true;state.chatCancelRevision++;state.chatStopUnconfirmed=false;
      state.stopPending=true;state.stopUnconfirmed=false;
      const finish=this.beginChat(id);
      try {
      this.emit(id,'status');
      state.revision++;state.proactiveAbort?.abort();state.proactiveIntent=null;state.batchStartedAt=null;state.chatWaitScheduled=false;state.chatRecoveryScheduled=false;
      clearTimeout(state.timer);clearTimeout(state.waitTimer);state.pending=[];state.inFlightIds=[];state.replyAbort?.abort();state.waitAbort?.abort();
      this.maintenance.cancelRecovery('chat:'+id);this.maintenance.cancelRecovery('wait:'+id);
      this.maintenance.cancelProactive(id);clearTimeout(state.proactiveTimer);
      this.learningSignals.get(id)?.abort();
      for(const abort of state.workReplyAborts.values())abort.abort();
      for(const timer of state.workRecoveryTimers??[])clearTimeout(timer);
      state.workRecoveryTimers?.clear();
      for(const owner of await this.store.listSessions(id,{kind:'work'})) {
        this.maintenance.cancelRecovery('work:'+owner.sessionId);
        const item=await this.store.getMessage(id,owner.taskId);
        if(item?.task&&['running','queued','cancelling'].includes(item.task.state)) {
          state.workTasks.set(owner.taskId,owner.sessionId);
          await this.store.updateMessage(id,owner.taskId,{task:{...item.task,state:'cancelling',cancelRequested:true},status:'sent'});
        }
      }
      await this.store.completeInputs(id,(await this.store.pendingMessages(id)).map(item=>item.id));
      await this.store.updateState(id,{waitingUntil:null,pendingIntent:null,pendingMessageIds:[],replyDraft:null,chatNeedsReset:false});
      const agent=this.live.get(id);
      if(agent){this.observeChatIdle(id,agent);agent.cancel({kind:'user'});}
      for(const [workTaskId,sessionId] of state.workTasks) {
        this.maintenance.cancelRecovery('work:'+sessionId);
        const work=this.ctx.agents.get(sessionId);
        if(work){this.observeWorkIdle(id,work);work.cancel({kind:'user'});}
      }
      state.workResults?.clear();
      state.phase='idle';delete state.error;
      } catch(error) {state.stopPending=false;state.stopUnconfirmed=true;throw error;}
      finally {finish();}
      const revision=state.chatCancelRevision;
      const operation=Promise.resolve().then(()=>this.settleStop(id,revision));
      state.stopPromise=operation;this.background.add(operation);
      operation.catch(error=>{
        state.stopUnconfirmed=true;state.stopPending=false;
        this.ctx.logger.warn('COMPANION_STOP_UNCONFIRMED: '+(error.code||error.name));
        if(!this.closed)this.emit(id,'status');
      }).finally(()=>{if(state.stopPromise===operation)state.stopPromise=null;this.background.delete(operation);this.activityChanged();});
    }
    this.emit(id); return {cancelRequested:true};
  }
  async settleStop(id,revision) {
    const state=this.state(id);
    for(;;) {
      // Creation can finish after the stop request. Drain accepted operations
      // before proving the final owned Agent set idle.
      await this.maintenance.drainCompanion(id,['cancel']);
      const agents=new Set();
      for(const owner of this.owners.values())if(owner.companionId===id) {
        const agent=this.ctx.agents.get(owner.sessionId);if(agent)agents.add(agent);
      }
      for(const agent of agents) {
        if(typeof agent.whenIdle!=='function')fail('COMPANION_STOP_UNCONFIRMED');
        agent.cancel({kind:'user'});await agent.whenIdle();
      }
      if(state.chatCancelRevision!==revision)return;
      if(this.maintenance.active(id,['cancel']).length)continue;
      for(const [taskId] of state.workTasks) {
        const item=await this.store.getMessage(id,taskId);
        if(item?.task)await this.store.updateMessage(id,taskId,{task:{...item.task,state:'cancelled',summary:'',cancelRequested:true},status:'sent'});
      }
      state.workTasks.clear();state.workResults?.clear();
      if(this.maintenance.active(id,['cancel']).length)continue;
      state.stopPending=false;
      if(!this.closed) {
        const nextAt=new Date(Date.now()+this.options.proactiveIntervalMs).toISOString();
        await this.store.updateState(id,{proactiveNextAt:nextAt});
        this.scheduleProactive(id,nextAt);this.emit(id,'status');
      }
      return;
    }
  }
  report(id,error) {
    if (this.closed) return;
    const state=this.state(id);
    if(!state.chatCancelRequested) {state.phase='error';state.error=error.code || error.message || 'COMPANION_MODEL_FAILED';this.emit(id,'status');}
    this.ctx.logger.warn('COMPANION_RUNTIME_FAILED: '+(error.code || error.message));
  }
  async close() {
    if(this.closed)return;
    this.closed=true;this.maintenance.close();this.lifetime.abort();this.restoreController?.();
    for (const state of this.states.values()) {clearTimeout(state.timer);clearTimeout(state.waitTimer);clearTimeout(state.proactiveTimer);for(const timer of state.workRecoveryTimers??[])clearTimeout(timer);state.replyAbort?.abort();}
    for (const handle of this.handles.values()) await (await handle).dispose();
    await Promise.allSettled([...this.background]);
    await Promise.all([...this.memories.values()].map(async pending=>(await pending).close()));
    this.listeners.clear();this.activityChanged();this.activityListeners.clear();
  }
}
