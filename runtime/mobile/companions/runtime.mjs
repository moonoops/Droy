import { companionReplyText } from './reply-text.mjs';
import { randomUUID } from 'node:crypto';
import { mkdir,realpath } from 'node:fs/promises';
import { join } from 'node:path';
import { setTimeout as pause } from 'node:timers/promises';
import { defineTool } from '@deepseek-ai/dsh-tools';
import { createUserMessage,createAssistantMessage } from '@deepseek-ai/dsh-llm';
import { applyWebSearchTool,applyWebFetchTool } from '@deepseek-ai/dsh-tool-web';
import { CompanionMemory } from './memory.mjs';
import { CompanionLearning } from './learning.mjs';
import { installCompanionMaintenance } from './maintenance-runtime.mjs';
import { stickerCatalog,readSharedAttachment,replyAttachment } from './media.mjs';
import { prepareReply,publishReply } from './delivery.mjs';
import { localizedProfile, profileName } from './profiles.mjs';
import { dialoguePrompts } from './dialogue-prompts.mjs';
import { buildPlannerPrompt,buildReplyerRequest,buildWaitReminder,renderMaiBotPrompt,replyToolDescriptions } from './maibot-prompts.mjs';
import { selectExpressions } from './maibot-expression.mjs';
import { isQuietTime, localTimeContext, withoutSources, sharedContext } from './dialogue.mjs';
import { resolveMobileLocale } from '../locale.mjs';
import { translateBackendText } from '../backend-i18n.mjs';
import { MOBILE_PROMPT_SECTION, COMPANION_PROMPT_SECTION } from '../prompt.mjs';

export const COMPANION_TOOLS = new Set(['web_search','web_fetch','companion_reply','companion_wait',
  'companion_quiet','companion_work','companion_recall','companion_remember','companion_forget','companion_attachment',
  'companion_stickers','companion_emoji']);
const CHAT_ONLY_TOOLS=new Set(['companion_wait','companion_quiet','companion_work','companion_stickers','companion_emoji']);
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
  constructor({ctx,store,directory,options = {},notify = () => {}}) {
    this.ctx = ctx; this.store = store; this.directory = directory; this.notify = notify;
    this.options = {debounceMs:650,historyMessages:80,learningMessages:6,
      proactiveIntervalMs:1800000,proactiveIdleMs:900000,...options};
    this.owners = new Map(); this.live = new Map(); this.memories = new Map(); this.states = new Map();
    this.listeners = new Set(); this.handles = new Map(); this.routes = new WeakMap(); this.closed = false;
    this.lifetime=new AbortController();this.background=new Set();this.decorated=new WeakSet();
    this.restrictedAgents=new Map();
    this.workspaceStarts=new Map();this.instanceStarts=new Map();
    this.contextSources=new Map();this.learningSignals=new Map();this.turnMemories=new Map();
    this.learner=new CompanionLearning({store,memoryFor:id=>this.memory(id),generate:(...args)=>this.generate(...args),
      getProfile:id=>this.profileForUse(id,{requireModel:false}),
      getLocale:()=>this.locale(),options:{learningUserMessages:this.options.learningMessages}});
    this.maintenance=installCompanionMaintenance(this);
  }
  locale() { return resolveMobileLocale(this.ctx); }
  copy() { return dialoguePrompts[this.locale()]; }
  emit(companionId,kind='message') {
    const event={companionId,kind,...(companionId?{status:this.status(companionId)}:{})};
    for (const listener of this.listeners) listener(event);
  }
  status(id) {
    const state=this.state(id);
    return {phase:state.phase,...(state.error?{error:this.copy().generationFailed}:{}),chatExecution:this.chatExecution(id)};
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
    const settled=this.maintenance.begin('chat');
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
      chatOperations:new Set(),chatObservedIdle:new WeakSet(),chatIdleObservers:new Map(),chatCancelRequested:false,chatCancelRevision:0});
    return this.states.get(id);
  }
  ownsSession(id) { return this.owners.has(String(id)); }
  isRestrictedAgent(agent) { return !!agent && this.ownsSession(agent.id) && this.decorated.has(agent); }
  isRestrictedSession(id) { return this.isRestrictedAgent(this.restrictedAgents.get(String(id))); }
  async initialize() {
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
        }
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
    for(const profile of await this.store.profiles())await this.startInstance(profile.id);
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
    const starting=(async()=>{
      await this.store.getProfile(id);
      const saved=await this.store.getState(id),state=this.state(id);
      const recoveringWait=saved.waitingUntil&&saved.pendingIntent?.kind==='wait'&&!saved.replyDraft;
      if((saved.chatNeedsReset&&!recoveringWait)||saved.replyDraft)await this.store.updateProfile(id,{chatSessionId:null});
      state.pending=await this.activePending(id,[...(saved.pendingMessageIds??[]),...(await this.store.pendingMessages(id)).map(item=>item.id)]);
      for(const message of state.pending)state.admitted.add(message.id);
      await this.store.updateState(id,{pendingMessageIds:state.pending.map(item=>item.id)});
      const covered=new Set(saved.replyDraft?.sourceMessageIds??[]);
      if(recoveringWait) {
        state.phase='waiting';this.scheduleWait(id,saved);
      } else if(saved.replyDraft?.sourceMessageIds?.length&&state.pending.every(item=>covered.has(item.id))) {
        state.chatRecoveryScheduled=true;
        state.timer=this.maintenance.recovery('chat:'+id,Date.now()+this.options.debounceMs,()=>{state.chatRecoveryScheduled=false;return this.recoverReply(id).catch(error=>this.report(id,error));});
      } else {
        if(saved.replyDraft)await this.store.updateState(id,{replyDraft:null});
        if(state.pending.length)state.timer=this.maintenance.recovery('chat:'+id,Date.now()+this.options.debounceMs,()=>this.flush(id).catch(error=>this.report(id,error)));
        else this.scheduleWait(id,saved);
      }
      this.scheduleProactive(id,saved.proactiveNextAt);
      state.workRecoveryTimers??=new Set();
      for(const owner of await this.store.listSessions(id,{kind:'work'})) {
        const taskMessage=await this.store.getMessage(id,owner.taskId);
        if(!taskMessage?.contextExcluded&&['running','queued','cancelling'].includes(taskMessage?.task?.state)) {
          const timer=this.maintenance.recovery('work:'+owner.sessionId,Date.now()+this.options.debounceMs,()=>{
            state.workRecoveryTimers.delete(timer);
            const operation=this.resumeWork(owner);this.background.add(operation);
            operation.catch(error=>this.report(id,error)).finally(()=>this.background.delete(operation));
            return operation;
          },{unref:true});if(timer)state.workRecoveryTimers.add(timer);
        }
      }
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
    for (const name of ['create','prompt','fork','selectModel']) {
      originals[name] = controller[name];
      if (typeof originals[name] !== 'function') continue;
      const runtime = this;
      const wrapped = async function(request,...args) {
        if (name === 'create') {
          const profiles = await runtime.store.profiles();
          const cwd = request.cwd ? await realpath(request.cwd) : null;
          for (const profile of profiles) {
            const workspace = runtime.ctx.workspaceRegistry.get(profile.workspaceId);
            if (request.workspaceId === profile.workspaceId || cwd && workspace && cwd === await realpath(workspace.path))
              fail('COMPANION_WORKSPACE_MANAGED');
          }
        } else if (runtime.ownsSession(request.sessionId)) fail('COMPANION_SESSION_MANAGED');
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
    await this.store.getProfile(id);
    if (!this.memories.has(id)) {
      const opening = CompanionMemory.open({directory:this.directory,companionId:id});
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
    const result={...sharedContext(state,memories,messages),...clock,
      userProfile:{name:userProfile.name,bio:userProfile.bio??''},availableStickers:stickerCatalog(this.locale())};
    if(owner) {
      const sourceContext={contextSourceIds:[...new Set([...messages.map(item=>item.id),
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
  async generate(profile,system,input,{signal,onDelta,messages,sessionId=profile.chatSessionId ?? 'companion:'+profile.id} = {}) {
    signal=signal?AbortSignal.any([signal,this.lifetime.signal]):this.lifetime.signal;
    signal.throwIfAborted();
    const {model}=await this.profileForUse(profile.id);
    signal.throwIfAborted();
    // Resolve once per model request. Later global/profile edits apply to the
    // next request, while this prepared stream keeps its selected route.
    const prepared = await this.ctx.llm.prepareCall(model,signal);
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
      const assembly = await next();
      const profile = await this.profileForUse(owner.companionId), locale = this.locale();
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
      execute:async (args,exec) => this.maintenance.run('tool:'+name,()=>{exec.signal?.throwIfAborted();return execute(args,exec);}),
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
      const result = await this.reply(owner,args,exec.signal);
      if(owner.kind==='work')exec.concludeTurn();
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
      const result=await (await this.memory(owner.companionId)).query({text:args.query});
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
      return (await this.memory(owner.companionId)).remember({text:args.text,kind:args.kind,sourceRefs:args.sourceIds});
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
    register('companion_stickers','stickersDescription',{},()=>({items:this.stickers()}));
    register('companion_emoji','emojiDescription',{msg_id:{type:'string'},quoteReply:{type:'boolean'}},(args,exec)=>this.sendEmoji(owner,args,exec.signal));
    agent.ctx.tools.register(defineTool({name:'companion_attachment',description:this.copy().attachmentDescription,
      parameters:{attachmentId:{type:'string',required:true}},
      output:{schema:{type:'json'},render:(_args,value)=>[
        {type:'text',text:JSON.stringify({...value,attachment:undefined})},
        ...(value.kind==='image'?[{type:'image',attachment:value.attachment}]:[]),
      ]},execute:(args,exec)=>readSharedAttachment({ctx:this.ctx,store:this.store,companionId:owner.companionId,
        attachmentId:args.attachmentId,signal:exec.signal,locale:this.locale()})}));
    this.decorated.add(agent);this.restrictedAgents.set(agent.id,agent);
    agent.ctx.effect(()=>()=>{this.restrictedAgents.delete(agent.id);this.contextSources.delete(agent.id);this.turnMemories.delete(agent.id);this.decorated.delete(agent);});
  }
  async submit(id,input) {
    const finish=this.beginChat(id);
    try {
    if (this.closed) fail('COMPANION_CLOSED');
    const profile = await this.profileForUse(id);
    if (!profile.model) fail('COMPANION_MODEL_REQUIRED');
    const {clientId,text='',attachments=[],voice,replyTo,stickerId} = input;
    if (!text.trim() && !attachments.length && !voice && !stickerId) fail('COMPANION_EMPTY_MESSAGE');
    const state=this.state(id),existing=clientId ? await this.store.getMessage(id,clientId):null;
    const saved=await this.store.appendMessage(id,{...(clientId?{id:clientId,clientId}:{}),role:'user',
      type:voice?'voice':stickerId?'sticker':attachments[0]?.mediaType?.startsWith('image/')?'image':attachments.length?'file':'text',
      text,attachments,...(voice?{voice}:{}),...(replyTo?{replyTo,quoteReply:true}:{}),...(stickerId?{stickerId}:{}),
      status:'received'});
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
    delete state.error;
    state.chatCancelRequested=false;state.chatWaitScheduled=false;state.chatRecoveryScheduled=false;
    state.revision++; state.pending.push(saved); state.replyAbort?.abort();
    clearTimeout(state.timer); clearTimeout(state.waitTimer);
    this.maintenance.cancelRecovery('chat:'+id);this.maintenance.cancelRecovery('wait:'+id);
    if(this.options.maibot?.messageSteering)state.waitAbort?.abort('new_message');
    await this.store.updateState(id,current=>({...(state.waitAbort?{}:{waitingUntil:null,pendingIntent:null}),replyDraft:null,
      ...(saved.role==='user'?{lastUserAt:new Date().toISOString()}:{}),
      pendingMessageIds:[...new Set([...(current.pendingMessageIds??[]),saved.id])]}));
    state.phase='thinking'; this.emit(id);
    state.timer=setTimeout(()=>this.flush(id).catch(error=>this.report(id,error)),this.options.debounceMs);
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
      if(message?.status==='received'&&!message.contextExcluded&&!state.forgottenMessageIds?.includes(messageId)&&cursor&&Number(cursor.slice(4))>baseline)active.push(message);
    }
    return active;
  }
  async flush(id) {
    const finish=this.beginChat(id),cancelRevision=this.state(id).chatCancelRevision;
    try {
    const state=this.state(id),pending=await this.activePending(id,state.pending.splice(0).map(item=>item.id));
    if(cancelRevision!==state.chatCancelRevision)return;
    if (!pending.length || this.closed) {if(!this.closed){state.phase='idle';this.emit(id,'status');}return;}
    let agent;
    try {agent=await this.agent(id);}catch(error){if(cancelRevision===state.chatCancelRevision)state.pending.unshift(...pending);throw error;}
    if(this.closed||cancelRevision!==state.chatCancelRevision)return;
    const content=pending.filter(item=>item.role==='user').map(item=>({id:item.id,text:item.text,type:item.type,voice:item.voice,attachments:item.attachments,replyTo:item.replyTo}));
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
    } finally {finish();}
  }
  async reply(owner,args,signal) {
    const finish=owner.kind==='chat'?this.beginChat(owner.companionId):()=>{};
    try {
    const id=owner.companionId,state=this.state(id),revision=state.revision;
    const profile=await this.profileForUse(id),locale=this.locale();
    const abort=new AbortController(); if(owner.kind==='chat')state.replyAbort=abort;
    const combined=signal ? AbortSignal.any([signal,abort.signal]):abort.signal;
    if(owner.kind==='chat')state.phase='typing'; this.emit(id,'status');
    const context=await this.context(id,owner,combined),options=this.options.maibot??{};
    if(options.enableVisualMessage)for(const item of context.messages)for(const attachment of item.attachments??[]) {
      if(attachment.mediaType?.startsWith('image/')) {
        const verified=await readSharedAttachment({ctx:this.ctx,store:this.store,companionId:id,
          attachmentId:attachment.id,signal:combined,locale});
        if(verified.kind==='image')attachment.ref=verified.attachment;
      }
    }
    const replyTo=args.msg_id||args.replyTo;
    const target=replyTo?context.messages.find(item=>item.id===replyTo):null;
    if(args.quoteReply!==undefined&&typeof args.quoteReply!=='boolean')fail('COMPANION_INVALID_INPUT');
    if(args.quoteReply===true&&(!target||!['user','assistant'].includes(target.role)||target.type==='task'))fail('COMPANION_REPLY_SOURCE_REQUIRED');
    const generate=({system,messages,signal:requestSignal})=>this.generate(profile,system,null,
      {messages:messages.map(item=>({...item,content:typeof item.content==='string'?[{type:'text',text:item.content}]:item.content})),
        signal:requestSignal??combined,sessionId:owner.sessionId});
    const expressions=await selectExpressions({context,args,locale,generate,signal:combined,
      random:options.random,options:options.expression});
    const request=buildReplyerRequest({profile,locale,context,args,expressions:expressions.prompt,kind:owner.kind,options});
    const text=companionReplyText(await generate(request));
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
    const stickerId=args.stickerId??(options.useNewSendLogic&&args.attach_emoji?this.stickers().find(item=>item.emoji_id===args.attach_emoji)?.id:undefined);
    if(stickerId&&stickerCatalog().some(item=>item.id===stickerId)) {
      parts.push({type:'sticker',text:'',stickerId,...this.contextSources.get(owner.sessionId)});
    }
    if(images.length) {
      parts.push({type:'image',text:'',attachments:images,...this.contextSources.get(owner.sessionId)});
    }
    const draft=await prepareReply({store:this.store,companionId:id,sourceMessageIds:state.inFlightIds??[],parts});
    const delivered=await this.publish(id,draft,combined),ids=delivered.messageIds;
    state.delivered++;state.turnAction='reply';state.replyAbort=null;state.phase='idle';delete state.error;this.emit(id,'status');
    let notification;
    try {notification=await this.notify({companionId:id,name:localizedProfile(profile,locale).name,text,messageId:ids[0]});}
    catch(error){notification={status:'failed'};this.ctx.logger.warn('COMPANION_NOTICE_FAILED: '+(error.code||error.name));}
    return {delivered:true,messageIds:ids,text,parts:parts.map((part,index)=>({messageId:ids[index],type:part.type,text:part.text,
      ...(part.stickerId?{stickerId:part.stickerId}:{})})),notification};
    } finally {finish();}
  }
  stickers() { return stickerCatalog(this.locale()).map((item,index)=>({emoji_id:index+1,...item})); }
  async sendEmoji(owner,args,signal) {
    if(owner.kind==='work')fail('COMPANION_TOOL_NOT_ALLOWED');
    const finish=this.beginChat(owner.companionId);
    try {
    const id=owner.companionId,state=this.state(id),revision=state.revision,profile=await this.profileForUse(id),locale=this.locale();
    const context=await this.context(id,owner,signal),items=this.stickers();
    const target=context.messages.find(item=>item.id===args.msg_id);
    if(args.msg_id&&!target)fail('COMPANION_REPLY_SOURCE_REQUIRED');
    if(args.quoteReply!==undefined&&typeof args.quoteReply!=='boolean')fail('COMPANION_INVALID_INPUT');
    if(args.quoteReply===true&&(!target||!['user','assistant'].includes(target.role)||target.type==='task'))fail('COMPANION_REPLY_SOURCE_REQUIRED');
    const system=renderMaiBotPrompt('emoji_selection',locale,{emoji_count:items.length,grid_rows:1,grid_columns:items.length});
    const raw=await this.generate(profile,system,{conversation:context.messages.slice(-20),msg_id:args.msg_id??'',
      candidateFormat:this.copy().emojiCandidates,items:items.map(({emoji_id,label,description,tags})=>({emoji_index:emoji_id,label,description,tags}))},
    {signal,sessionId:owner.sessionId});
    const selection=JSON.parse(raw.trim().replace(/^```(?:json)?\s*/u,'').replace(/\s*```$/u,''));
    const selected=items.find(item=>item.emoji_id===selection.emoji_index);
    if(!selected)fail('COMPANION_INVALID_STICKER');
    if(revision!==state.revision)fail('COMPANION_REPLY_SUPERSEDED');
    const parts=[{type:'sticker',text:'',stickerId:selected.id,quoteReply:args.quoteReply===true,...this.contextSources.get(owner.sessionId),...(args.msg_id?{replyTo:args.msg_id}:{})}];
    const draft=await prepareReply({store:this.store,companionId:id,sourceMessageIds:state.inFlightIds??[],parts});
    const delivered=await this.publish(id,draft,signal);state.turnAction='emoji';state.delivered++;this.emit(id,'status');
    return {delivered:true,messageIds:delivered.messageIds,stickerId:selected.id,description:selected.description};
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
  async publish(id,draft,signal) {
    const finish=this.beginChat(id);
    try {return await publishReply({store:this.store,companionId:id,draft,signal,onSent:()=>this.emit(id,'message')});}
    finally {finish();}
  }
  async recoverReply(id) {
    const finish=this.beginChat(id),cancelRevision=this.state(id).chatCancelRevision;
    try {
    const state=this.state(id),saved=await this.store.getState(id),draft=saved.replyDraft;
    if(cancelRevision!==state.chatCancelRevision)return;
    if(!draft)return await this.flush(id);
    const abort=new AbortController();state.replyAbort=abort;state.phase='typing';this.emit(id,'status');
    const delivered=await this.publish(id,draft,AbortSignal.any([abort.signal,this.lifetime.signal]));
    for(const messageId of draft.sourceMessageIds)state.admitted.delete(messageId);
    state.pending=await this.activePending(id,(await this.store.getState(id)).pendingMessageIds??[]);
    state.inFlightIds=[];state.phase='idle';delete state.error;
    await this.store.updateState(id,{chatNeedsReset:state.pending.length>0});
    if(delivered.newMessageIds.length) {
      const profile=await this.store.getProfile(id),text=draft.parts.filter(part=>part.type==='text').map(part=>part.text).join('\n\n');
      try{await this.notify({companionId:id,name:localizedProfile(profile,this.locale()).name,text,messageId:delivered.messageIds[0]});}
      catch(error){this.ctx.logger.warn('COMPANION_NOTICE_FAILED: '+(error.code||error.name));}
    }
    this.emit(id,'status');if(state.pending.length)return await this.flush(id);
    } finally {finish();}
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
        if(taskMessage.contextExcluded)return;
        const task={...taskMessage.task,state:completed?'completed':isAborted(event.data?.reason)?'cancelled':'failed',summary:result || ''};
        const sources=this.contextSources.get(owner.sessionId)??{};
        const updated=await this.store.updateMessage(id,owner.taskId,{task,status:'received',...sources,
          contextSourceIds:[...new Set([...(taskMessage.contextSourceIds??[]),...(sources.contextSourceIds??[])])]}); this.emit(id);
        state.workResults?.delete(owner.sessionId);
        await this.enqueue(id,updated);
      }
      return;
    }
    if (!state.pending.length && state.phase !== 'waiting') { state.phase='idle'; this.emit(id,'status'); }
    if(state.resetAfterTurn) {
      state.resetAfterTurn=false;state.inFlightIds=[];await this.resetChat(id);
      state.pending=await this.activePending(id,(await this.store.pendingMessages(id)).map(item=>item.id));
      if(state.pending.length)state.timer=setTimeout(()=>this.flush(id).catch(error=>this.report(id,error)),this.options.debounceMs);
      return;
    }
    if(event.data?.reason?.kind==='completed') {
      state.waitCount=0;this.turnMemories.delete(owner.sessionId);
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
    const state=this.state(id);if(state.learningPromise)return state.learningPromise;
    const controller=new AbortController();this.learningSignals.set(id,controller);
    const task=(async()=>{try {
      let result;
      do {result=await this.learner.maintain(id,{signal:AbortSignal.any([this.lifetime.signal,controller.signal])});}while(result.hasMore&&!result.discarded&&!this.closed);
      return result;
    }finally{if(this.learningSignals.get(id)===controller)this.learningSignals.delete(id);state.learningPromise=null;}})();
    state.learningPromise=task;return task;
  }
  async startWork(id,{title,goal,taskId:existingTaskId}) {
    this.state(id).turnAction='work';
    if(existingTaskId) {
      const existing=await this.store.getMessage(id,existingTaskId);
      if(!existing?.task)fail('COMPANION_TASK_NOT_FOUND');
      const owner=await this.store.sessionOwner(existing.task.sessionId);
      if(owner?.companionId!==id||owner.kind!=='work')fail('COMPANION_TASK_NOT_FOUND');
      const invalid=(await this.store.getState(id)).invalidSessionIds??[];
      if(!invalid.includes(owner.sessionId)) {
        const agent=await this.agent(id,{kind:'work',sessionId:owner.sessionId,taskId:existingTaskId});
        const task={...existing.task,state:'running',summary:'',goal:[existing.task.goal,goal].filter(Boolean).join('\n\n')};
        await this.store.updateMessage(id,existingTaskId,{task,status:'sent'});
        this.state(id).workResults?.delete(owner.sessionId);
        if(agent.status==='running')agent.steer(message(goal));else agent.followup(message(goal));
        this.emit(id);return {taskId:existingTaskId,sessionId:agent.id,title:task.title,state:'running'};
      }
    }
    const sessionId=randomUUID(),taskId=randomUUID();
    const task={id:taskId,sessionId,title,goal,state:'running',summary:''};
    const chatId=(await this.store.getProfile(id)).chatSessionId;
    await this.store.appendMessage(id,{id:taskId,role:'assistant',type:'task',text:title,task,status:'sent',...this.contextSources.get(chatId)}); this.emit(id);
    try {
      const agent=await this.agent(id,{kind:'work',sessionId,taskId});
      task.sessionId=agent.id;
      await this.store.updateMessage(id,taskId,{task});
      agent.followup(message(goal)); this.emit(id);
      return {taskId,sessionId:agent.id,title,state:'running'};
    } catch(error) {
      await this.store.updateMessage(id,taskId,{task:{...task,state:'failed',error:error.code || error.message}});
      this.emit(id);throw error;
    }
  }
  async resumeWork(owner) {
    if(this.closed)return;
    const id=owner.companionId,item=await this.store.getMessage(id,owner.taskId);
    if(!item?.task||item.contextExcluded)return;
    if(item.task.state==='cancelling') {
      const saved=await this.store.updateMessage(id,item.id,{task:{...item.task,state:'cancelled',summary:''},status:'received'});
      return this.enqueue(id,saved);
    }
    if(!['running','queued'].includes(item.task.state))return;
    try {
      const agent=await this.agent(id,{kind:'work',sessionId:owner.sessionId,taskId:owner.taskId});
      agent.followup(message(JSON.stringify({internalWorkContinuation:{taskId:item.id,goal:item.task.goal??item.task.title,
        instruction:this.copy().resumeWork}})));
    }catch(error) {
      if(this.closed)throw error;
      const saved=await this.store.updateMessage(id,item.id,{task:{...item.task,state:'failed',summary:'',error:error.code??'COMPANION_WORK_INTERRUPTED'},status:'received'});
      await this.enqueue(id,saved);throw error;
    }
  }
  scheduleProactive(id,nextAt) {
    const state=this.state(id);clearTimeout(state.proactiveTimer);
    if(this.closed)return;
    const next=Date.parse(nextAt||'');
    const deadline=Number.isFinite(next)?next:Date.now()+this.options.proactiveIntervalMs;
    state.proactiveTimer=this.maintenance.proactive(id,deadline,async()=>{
      try {
        const saved=await this.store.getState(id),profile=await this.profileForUse(id,{requireModel:false});
        if(profile.settings.proactiveEnabled&&profile.model&&state.phase==='idle'&&!state.pending.length&&saved.lastUserAt
          && Date.now()-Date.parse(saved.lastUserAt)>=this.options.proactiveIdleMs)
          await this.wake(id,{kind:'proactive'});
        const at=new Date(Date.now()+this.options.proactiveIntervalMs).toISOString();
        await this.store.updateState(id,{proactiveNextAt:at});this.scheduleProactive(id,at);
      }catch(error){if(!this.closed)this.report(id,error);}
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
    if (this.closed) return;
    const live=this.state(id),revision=live.revision,continuingWait=intent.kind==='wait';
    const saved=continuingWait?await this.store.getState(id):null;
    if(continuingWait&&(!saved.waitingUntil||JSON.stringify(saved.pendingIntent)!==JSON.stringify(intent)))return;
    const profile=await this.store.getProfile(id);
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
    const input=message(JSON.stringify({internalIntent:intent,consideration:continuingWait?this.copy().waitResumed:this.copy().proactive}));
    live.chatCancelRequested=false;
    if(agent.status==='running')agent.steer(input);else agent.followup(input);
    } finally {finish();}
  }
  async invalidate(id,sourceIds,{reset=true,all=false}={}) {
    const live=this.state(id);live.revision++;live.replyAbort?.abort();live.waitAbort?.abort();this.learningSignals.get(id)?.abort();
    const {excludedIds,newlyExcluded}=await this.store.excludeDerivedMessages(id,sourceIds);
    const affected=[...new Set([...sourceIds,...excludedIds])];
    this.learner.invalidate(id);
    const state=await this.store.updateState(id,current=>({...withoutSources(current,affected),
      memoryRevision:(current.memoryRevision??0)+1,waitingUntil:null,pendingIntent:null,replyDraft:null,
      forgottenMessageIds:[...new Set([...(current.forgottenMessageIds??[]),...sourceIds])]}));
    clearTimeout(this.state(id).waitTimer);this.state(id).chatWaitScheduled=false;this.state(id).replyAbort?.abort();
    this.maintenance.cancelRecovery('wait:'+id);
    const sources=new Set(affected),invalid=[];
    for(const owner of await this.store.listSessions(id,{kind:'work'})) {
      const used=state.sessionSources?.[owner.sessionId];
      if(all||used?.hasSummary&&newlyExcluded||used?.messageIds.some(source=>sources.has(source))) {
        invalid.push(owner.sessionId);
        this.state(id).workResults?.delete(owner.sessionId);
        const agent=this.live.get(owner.sessionId);agent?.cancel({kind:'user'});
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
    const result=await (await this.memory(id)).forget(input);
    const sourceIds=['memory:'+input.recordId,...result.forgottenSources.map(source=>source.replace('im:'+id+':','')),...(requestMessageId?[requestMessageId]:[])];
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
    if (!await this.store.getMessage(id,messageId)) return {removed:true};
    await this.invalidate(id,[messageId]);
    const memory=await this.memory(id);
    let page,cursor;const matches=[];
    do {
      page=await memory.query({limit:100,maxBytes:262144,...(cursor?{cursor}:{})});cursor=page.nextCursor;
      matches.push(...page.items.filter(item=>item.sourceRefs?.includes(memory.sourceRef(messageId))));
    }while(cursor);
    for(const item of matches)await memory.forget({recordId:item.recordId,expectedRevisionId:item.revisionId});
    const removed=await this.store.removeMessage(id,messageId);
    this.state(id).pending=this.state(id).pending.filter(item=>item.id!==messageId);
    await this.store.updateState(id,current=>({pendingMessageIds:(current.pendingMessageIds??[]).filter(value=>value!==messageId)}));
    this.emit(id);return removed??{removed:true};
  }
  async resetMemories(id) {
    const memory=await this.memory(id);let page;
    do {page=await memory.query({limit:100,maxBytes:262144});
      for(const item of page.items)await memory.forget({recordId:item.recordId,expectedRevisionId:item.revisionId});
    }while(page.items.length);
    const latest=await this.store.listMessages(id,{limit:1});
    const baseline=latest.nextAfter;
    await this.store.updateState(id,{expressions:[],jargon:[],jargonCandidates:[],impressions:[],forgottenThrough:baseline,
      learningCursor:baseline,summaryCursor:baseline,summary:'',summaryThrough:null});
    await this.invalidate(id,[],{all:true});this.emit(id);return {reset:true};
  }
  async clearHistory(id) {
    await this.cancel(id);await this.store.clearMessages(id);
    await this.invalidate(id,[],{all:true});
    await this.store.updateState(id,{pendingMessageIds:[],summary:'',summaryThrough:null});
    this.emit(id);return {cleared:true};
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
      await this.store.updateMessage(id,taskId,{task:{...item.task,state:'cancelling'}});
      this.ctx.agents.get(item.task.sessionId)?.cancel({kind:'user'});
    } else {
      state.chatCancelRequested=true;state.chatCancelRevision++;state.chatStopUnconfirmed=false;
      const finish=this.beginChat(id);
      try {
      this.emit(id,'status');
      state.revision++;state.chatWaitScheduled=false;state.chatRecoveryScheduled=false;
      clearTimeout(state.timer);clearTimeout(state.waitTimer);state.pending=[];state.inFlightIds=[];state.replyAbort?.abort();state.waitAbort?.abort();
      this.maintenance.cancelRecovery('chat:'+id);this.maintenance.cancelRecovery('wait:'+id);
      await this.store.completeInputs(id,(await this.store.pendingMessages(id)).map(item=>item.id));
      await this.store.updateState(id,{waitingUntil:null,pendingIntent:null,pendingMessageIds:[],replyDraft:null,chatNeedsReset:false});
      const agent=this.live.get(id);
      if(agent){this.observeChatIdle(id,agent);agent.cancel({kind:'user'});}
      state.phase='idle';delete state.error;
      } finally {finish();}
    }
    this.emit(id); return {cancelRequested:true};
  }
  report(id,error) {
    if (this.closed) return;
    this.state(id).phase='error'; this.state(id).error=error.code || error.message || 'COMPANION_MODEL_FAILED'; this.emit(id,'status');
    this.ctx.logger.warn('COMPANION_RUNTIME_FAILED: '+(error.code || error.message));
  }
  async close() {
    if(this.closed)return;
    this.closed=true;this.maintenance.close();this.lifetime.abort();this.restoreController?.();
    for (const state of this.states.values()) {clearTimeout(state.timer);clearTimeout(state.waitTimer);clearTimeout(state.proactiveTimer);for(const timer of state.workRecoveryTimers??[])clearTimeout(timer);state.replyAbort?.abort();}
    for (const handle of this.handles.values()) await (await handle).dispose();
    await Promise.allSettled([...this.background]);
    await Promise.all([...this.memories.values()].map(async pending=>(await pending).close()));
    this.listeners.clear();
  }
}
