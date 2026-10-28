import test, { after } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, mkdir, rm, stat, symlink } from 'node:fs/promises';
import { join } from 'node:path';
import { tmpdir } from 'node:os';
import { createRequire, registerHooks } from 'node:module';
import { pathToFileURL } from 'node:url';
import { createServer } from 'node:http';
import { setTimeout as delay } from 'node:timers/promises';
import { CompanionStore } from '../companions/store.mjs';
import { prepareReply } from '../companions/delivery.mjs';
import { localTimeContext, isQuietTime } from '../companions/dialogue.mjs';
import { encodePng } from '../png.mjs';

const installed = createRequire(new URL('../../dsh/package.json', import.meta.url));
const sourceImports = registerHooks({ resolve(specifier, context, next) {
  if(context.parentURL===new URL('../attachments.mjs',import.meta.url).href&&specifier==='./publish.mjs')
    return {url:new URL('./fixtures/host-publish.mjs',import.meta.url).href,shortCircuit:true};
  if (specifier === '@droyagent/memory/store') return { url: new URL('../../memory/store.mjs', import.meta.url).href, shortCircuit: true };
  if (specifier.startsWith('@deepseek-ai/')) return { url: pathToFileURL(installed.resolve(specifier)).href, shortCircuit: true };
  return next(specifier, context);
} });
after(() => sourceImports.deregister());
const [{ CompanionRuntime }, { createCompanionHandler }, { Context }, { createScope }, { default: Tools, defineTool }, { default: Prompt }, { default: MobileAttachments }] = await Promise.all([
  import('../companions/runtime.mjs'), import('../companions/host.mjs'), import('@deepseek-ai/cordis'),
  import('@deepseek-ai/dsh-scope'), import('@deepseek-ai/dsh-tools'), import('@deepseek-ai/dsh-system-prompt'),import('../attachments.mjs'),
]);
const code = expected => error => error.code === expected;
const modelA = { provider: 'fixture-a', model: 'model-a' };
const modelB = { provider: 'fixture-b', model: 'model-b', reasoningEffort: 'low' };

async function eventually(read, description, timeout = 1500) {
  const deadline = Date.now() + timeout;
  while (Date.now() < deadline) {
    const value = await read();
    if (value) return value;
    await delay(5);
  }
  assert.fail('Timed out: ' + description);
}

async function fixture(t, { directory, store: suppliedStore, debounceMs = 1000, learningMessages = 12, stream, failWorkCreation = false,seedKakaModel=true,locale='en',beforeInitialize,withAttachments=false } = {}) {
  const ownsDirectory = !directory;
  directory ??= await mkdtemp(join(tmpdir(), 'droy-companion-runtime-'));
  const store = suppliedStore ?? await CompanionStore.open({ directory });
  const ctx = new Context();
  await ctx.plugin(Tools, { mode: 'native' });
  await ctx.plugin(Prompt);
  const calls = { agents: [], controller: [], llm: [], cancelled: [], disposed: [], notifications: [],defaultInitializations:0 };
  const workspaces = new Map(), agents = new Map();
  let runtimeContext;
  let nextWorkspace = 0;
  ctx.provide('settings', { describe: () => [{ ns: 'locale', value: { preference: locale } }] });
  ctx.provide('workingDirectory',{defaultDirectory:join(directory,'default-workspace')});
  ctx.provide('workspaceRegistry', {
    async create(path, title) {
      let workspace = [...workspaces.values()].find(item => item.path === path);
      if (!workspace) {
        workspace = { id: 'workspace-' + (++nextWorkspace), path, title, sessions: [], async attachSession(id) { this.sessions.push(id); },async setTitle(value){this.title=value;} };
        workspaces.set(workspace.id, workspace);
      }
      return workspace;
    },
    get: id => workspaces.get(id),
    list:()=>[...workspaces.values()].reverse(),
    async initializeDefault(resolveDirectory) {
      calls.defaultInitializations++;
      if(workspaces.size)return undefined;
      const path=await resolveDirectory();await mkdir(path,{recursive:true});
      return this.create(path);
    },
  });
  ctx.provide('agentDefaultModel', { currentSelection: () => modelA });
  const originals = {};
  const controller = {};
  for (const name of ['create', 'prompt', 'fork', 'selectModel']) {
    originals[name] = controller[name] = async request => { calls.controller.push({ name, request }); return { accepted: true }; };
  }
  controller.modelCatalog = async () => ({ groups: [] });
  ctx.provide('sessionController', controller);
  async function makeAgent(input, resumed) {
    const agent = {
      id: resumed ? input.resumeSessionId : input.sessionId,
      options: structuredClone(input.agentOptions), status: 'idle', deliveries: [],
      followup(message) { this.deliveries.push({ mode: 'followup', message }); this.status = 'running'; },
      steer(message) { this.deliveries.push({ mode: 'steer', message }); this.status = 'running'; },
      cancel(reason) { calls.cancelled.push({ id: this.id, reason }); this.status = 'idle'; },
    };
    agent.session = { id: agent.id };
    const scope = createScope(runtimeContext, agent); agent.ctx = scope.ctx;
    await input.setup(scope.ctx, agent);
    const handle = { agent, async dispose() { calls.disposed.push(agent.id); agent.cancel({ kind: 'disposed' }); agents.delete(agent.id); await scope.dispose(); } };
    calls.agents.push({ resumed, input, agent, handle }); agents.set(agent.id, agent);
    return handle;
  }
  ctx.provide('agents', { create: input => {
    if (failWorkCreation && input.meta.cwd.endsWith('/work')) return Promise.reject(new Error('FICTIONAL_WORK_START_FAILED'));
    return makeAgent(input, false);
  }, resume: input => makeAgent(input, true), get: id => agents.get(id) });
  ctx.provide('llm', {
    async prepareCall(config, signal) {
      const call = { config, signal }; calls.llm.push(call);
      return { config, async *stream(input) {
        call.input = input;
        for(const message of input.messages.filter(message=>message.role==='assistant')) {
          assert.equal(message.source?.kind,'model','Replyer history must satisfy DSH assistant provenance');
          assert.equal(typeof message.source.provider,'string');assert.equal(typeof message.source.model,'string');
        }
        if (stream) yield* stream(call);
        else {
          let inputValue;try{inputValue=JSON.parse(input.messages[0].content[0].text);}catch{}
          yield { type: 'text-delta', text: inputValue?.candidates?JSON.stringify({recordIds:inputValue.candidates.map(item=>item.recordId)}):'Fictional companion response.' };
          yield { type: 'finish', reason: { kind: 'stop' } };
        }
      } };
    },
    async resolveCallConfig(config) { return config; },
  });
  if(withAttachments)await ctx.plugin(MobileAttachments,{dshHome:join(directory,'attachment-host')});
  await ctx.plugin({ name: 'companion-runtime-fixture', inject: ['tools', 'systemPrompt', 'settings', 'workspaceRegistry', 'workingDirectory', 'agentDefaultModel', 'sessionController', 'agents', 'llm',...(withAttachments?['attachments']:[])],
    apply(scope) { runtimeContext = scope; } });
  const runtime = new CompanionRuntime({ ctx: runtimeContext, store, directory, options: { debounceMs, learningMessages }, notify: value => calls.notifications.push(value) });
  await beforeInitialize?.({ctx:runtimeContext,store,directory});
  await runtime.initialize();
  if(seedKakaModel)await store.updateProfile('kaka',{model:modelA});
  await store.updateProfile('maimai', { model: modelB });
  let closed = false;
  const close = async () => {
    if (closed) return; closed = true;
    try { await runtime.close(); }
    finally {
      await ctx.fiber.dispose();
      if (!suppliedStore) await store.close();
      if (ownsDirectory) await rm(directory, { recursive: true, force: true });
    }
  };
  t.after(close);
  return { ctx: runtimeContext, store, runtime, calls, workspaces, directory, originals, close };
}

test('ordinary text stays in one exact bubble and does not automatically quote its reply target',async t=>{
  const body='First fictional sentence.\n\nSecond fictional paragraph, with punctuation.';
  const f=await fixture(t,{stream:async function*(){yield {type:'text-delta',text:body};yield {type:'finish',reason:{kind:'stop'}};}});
  await f.runtime.submit('kaka',{clientId:'single-bubble-source',text:'Fictional question'});
  await f.runtime.flush('kaka');
  const agent=await f.runtime.agent('kaka'),events=[];
  const stop=f.runtime.subscribe(event=>events.push(event));
  f.runtime.options.maibot={splitter:{enabled:true,mode:'llm'},typo:{enabled:true}};
  const result=await f.runtime.reply(await f.store.sessionOwner(agent.id),{msg_id:'single-bubble-source',reply_reference:'Answer normally.'});
  stop();
  assert.equal(f.calls.llm.length,1,'Ordinary delivery never calls a model to split its own reply');
  assert.equal(result.messageIds.length,1);
  const saved=await f.store.getMessage('kaka',result.messageIds[0]);
  assert.equal(saved.text,body);assert.equal(saved.replyTo,'single-bubble-source');assert.equal(saved.quoteReply,false);
  assert.deepEqual(events.map(event=>event.kind),['status','message','status']);
  assert.equal(events[0].status.phase,'typing');assert.equal(events.at(-1).status.phase,'idle');
});

test('visible quotes are explicit, scoped, and cannot expose excluded or internal task messages',async t=>{
  const f=await fixture(t),agent=await f.runtime.agent('kaka'),owner=await f.store.sessionOwner(agent.id);
  await f.store.appendMessage('kaka',{id:'earlier-question',role:'user',text:'An earlier fictional question'});
  await f.store.appendMessage('kaka',{id:'newer-question',role:'user',text:'A different fictional question'});
  const result=await f.runtime.reply(owner,{msg_id:'earlier-question',quoteReply:true,reply_reference:'Refer back to the earlier question.'});
  assert.equal((await f.store.getMessage('kaka',result.messageIds[0])).quoteReply,true);
  await f.runtime.submit('kaka',{clientId:'manual-quote',text:'A manual follow-up',replyTo:'earlier-question'});
  assert.equal((await f.store.getMessage('kaka','manual-quote')).quoteReply,true);
  await f.store.appendMessage('maimai',{id:'other-owner',role:'user',text:'Different private conversation'});
  await f.store.appendMessage('kaka',{id:'internal-task',role:'assistant',type:'task',text:'Internal work',task:{state:'completed',summary:'Internal result'}});
  await f.store.appendMessage('kaka',{id:'excluded-question',role:'user',text:'Excluded question',contextExcluded:true});
  const calls=f.calls.llm.length;
  for(const msg_id of ['other-owner','internal-task','excluded-question','missing'])
    await assert.rejects(f.runtime.reply(owner,{msg_id,quoteReply:true,reply_reference:'Invalid visible quote.'}),code('COMPANION_REPLY_SOURCE_REQUIRED'));
  assert.equal(f.calls.llm.length,calls,'Invalid quote targets are rejected before a model request');
});

test('each turn reads the current native clock, user identity and customized personality',async t=>{
  const f=await fixture(t),agent=await f.runtime.agent('kaka'),owner=await f.store.sessionOwner(agent.id);
  await f.store.updateUserProfile({name:'Fictional Alex'});
  await f.store.updateProfile('kaka',{personalityOverride:'Use the user-authored fictional persona verbatim.'});
  const now=Date.parse('2026-10-28T18:07:00Z');let timeZone='Asia/Shanghai',reads=0;
  f.runtime.options.getClock=async()=>{reads++;return {now,timeZone};};
  const first=await f.runtime.context('kaka',owner);
  assert.equal(first.currentTime,'2026-10-29T02:07:00+08:00');assert.equal(first.utcOffset,'+08:00');
  assert.equal(first.userProfile.name,'Fictional Alex');assert.equal(reads,1);
  timeZone='Asia/Kathmandu';
  const next=await f.runtime.context('kaka',owner);
  assert.equal(next.currentTime,'2026-10-28T23:52:00+05:45');assert.equal(reads,2);
  await f.runtime.reply(owner,{msg_id:'',reply_reference:'Fictional request for the current time.'});
  const request=f.calls.llm.at(-1).input;
  assert.match(request.system,/Use the user-authored fictional persona verbatim\./);
  assert.match(request.system,/Fictional Alex/);
  assert.match(JSON.stringify(request.messages),/2026-10-28T23:52:00\+05:45/);
  assert.doesNotMatch(JSON.stringify(request.messages),/2026-10-28T18:07:00/);
});

test('local time and quiet hours share cross-day, fractional-offset and daylight-saving semantics',()=>{
  const overnight={enabled:true,start:'22:00',end:'09:00'};
  assert.equal(isQuietTime(overnight,new Date('2026-10-28T13:59:00Z'),'Asia/Shanghai'),false);
  assert.equal(isQuietTime(overnight,new Date('2026-10-28T14:00:00Z'),'Asia/Shanghai'),true);
  assert.equal(isQuietTime(overnight,new Date('2026-10-29T00:59:00Z'),'Asia/Shanghai'),true);
  assert.equal(isQuietTime(overnight,new Date('2026-10-29T01:00:00Z'),'Asia/Shanghai'),false);
  assert.equal(isQuietTime({...overnight,enabled:false},new Date('2026-10-28T14:00:00Z'),'Asia/Shanghai'),false);
  assert.equal(localTimeContext(new Date('2026-10-28T18:30:00Z'),'Asia/Kathmandu').currentTime,'2026-10-29T00:15:00+05:45');
  assert.deepEqual(localTimeContext(new Date('2026-10-28T18:30:00Z'),'GMT-03:30'),
    {currentTime:'2026-10-28T15:00:00-03:30',timeZone:'-03:30',utcOffset:'-03:30'});
  assert.equal(localTimeContext(new Date('2026-11-01T05:30:00Z'),'America/New_York').currentTime,'2026-11-01T01:30:00-04:00');
  assert.equal(localTimeContext(new Date('2026-11-01T06:30:00Z'),'America/New_York').currentTime,'2026-11-01T01:30:00-05:00');
});

test('first initialization creates the ordinary default directory before companions without model requests',async t=>{
  for(const locale of ['zh','en'])await t.test(locale,async child=>{
    const f=await fixture(child,{locale}),profiles=await f.store.profiles(),managed=new Set(profiles.map(profile=>profile.workspaceId));
    const ordinary=f.ctx.workspaceRegistry.list().filter(workspace=>!managed.has(workspace.id));
    assert.equal(ordinary.length,1);
    assert.equal(ordinary[0].path,f.ctx.workingDirectory.defaultDirectory);
    assert.equal(ordinary[0].title,locale==='zh'?'默认空间':'Default space');
    assert.equal((await stat(ordinary[0].path)).isDirectory(),true);
    assert.equal(f.calls.defaultInitializations,1);
    assert.equal(f.workspaces.size,3);
    assert.equal(f.calls.agents.length,0);assert.equal(f.calls.llm.length,0);assert.equal(f.calls.controller.length,0);
  });
});

test('initialization repairs a registry containing only companion spaces without replacing them',async t=>{
  const existing=[];
  const f=await fixture(t,{beforeInitialize:async({ctx,store,directory})=>{
    for(const profile of await store.profiles()) {
      const path=join(directory,profile.id,'work');await mkdir(path,{recursive:true});
      const workspace=await ctx.workspaceRegistry.create(path,profile.name);existing.push(workspace);
      await store.updateProfile(profile.id,{workspaceId:workspace.id});
    }
  }});
  const ordinary=f.ctx.workspaceRegistry.list().filter(workspace=>!existing.some(item=>item.id===workspace.id));
  assert.equal(ordinary.length,1);assert.equal(ordinary[0].path,f.ctx.workingDirectory.defaultDirectory);
  assert.equal(ordinary[0].title,'Default space');assert.equal(f.workspaces.size,3);
  for(const workspace of existing)assert.equal(f.ctx.workspaceRegistry.get(workspace.id),workspace);
  assert.deepEqual((await f.store.profiles()).map(profile=>profile.workspaceId),existing.map(workspace=>workspace.id));
  assert.equal(f.calls.agents.length,0);assert.equal(f.calls.llm.length,0);assert.equal(f.calls.controller.length,0);
});

test('initialization preserves an existing ordinary workspace and its custom title',async t=>{
  let ordinary;
  const f=await fixture(t,{beforeInitialize:async({ctx,directory})=>{
    const path=join(directory,'existing-project');await mkdir(path,{recursive:true});
    ordinary=await ctx.workspaceRegistry.create(path,'Fictional existing project');
  }});
  assert.equal(f.workspaces.size,3);
  assert.equal(f.ctx.workspaceRegistry.get(ordinary.id),ordinary);
  assert.equal(ordinary.title,'Fictional existing project');
  assert.equal(f.calls.defaultInitializations,0);
  await assert.rejects(stat(f.ctx.workingDirectory.defaultDirectory),error=>error.code==='ENOENT');
  assert.equal(f.calls.agents.length,0);assert.equal(f.calls.llm.length,0);assert.equal(f.calls.controller.length,0);
});

test('each companion uses its own model and resumes its durable chat identity', async t => {
  const f = await fixture(t);
  const kaka = await f.runtime.agent('kaka'), maimai = await f.runtime.agent('maimai');
  assert.notEqual(kaka.id, maimai.id);
  assert.deepEqual(kaka.options, modelA);
  assert.deepEqual(maimai.options, modelB);
  assert.equal(await f.runtime.agent('kaka'), kaka);
  const kakaId = kaka.id;
  await f.runtime.close();
  const resumed = new CompanionRuntime({ ctx: f.ctx, store: f.store, directory: f.directory });
  t.after(() => resumed.close());
  await resumed.initialize();
  const restored = await resumed.agent('kaka');
  assert.equal(restored.id, kakaId);
  assert.equal(f.calls.agents.at(-1).resumed, true);
  assert.deepEqual(restored.options, modelA);
});

test('first use adopts the completed setup model and later work-default changes do not replace it',async t=>{
  const f=await fixture(t,{seedKakaModel:false});assert.equal((await f.store.getProfile('kaka')).model,null);
  f.ctx.agentDefaultModel.currentSelection=()=>modelB;
  const agent=await f.runtime.agent('kaka');assert.deepEqual(agent.options,modelB);
  f.ctx.agentDefaultModel.currentSelection=()=>modelA;
  assert.deepEqual((await f.store.getProfile('kaka')).model,modelB);
  assert.equal(await f.runtime.agent('kaka'),agent);
});

test('separate work requests create separate restricted sessions and preserve task ownership', async t => {
  const f = await fixture(t);
  const one = await f.runtime.startWork('kaka', { title: 'Fictional first work', goal: 'First fictional goal' });
  const two = await f.runtime.startWork('kaka', { title: 'Fictional second work', goal: 'Second fictional goal' });
  assert.notEqual(one.taskId, two.taskId);
  assert.notEqual(one.sessionId, two.sessionId);
  const sessions = await f.store.listSessions('kaka', { kind: 'work' });
  assert.equal(sessions.length, 2);
  assert.equal((await f.store.sessionOwner(one.sessionId)).taskId, one.taskId);
  const firstAgent = f.ctx.agents.get(one.sessionId), secondAgent = f.ctx.agents.get(two.sessionId);
  assert.equal(firstAgent.deliveries[0].message.content[0].text, 'First fictional goal');
  assert.equal(secondAgent.deliveries[0].message.content[0].text, 'Second fictional goal');
  assert.deepEqual(await f.store.listSessions('maimai'), []);
});

test('sequential and concurrent retries do not duplicate pending messages or the delivered batch', async t => {
  const f = await fixture(t);
  const first = { clientId: 'message-one', text: 'First fictional message' };
  await Promise.all([f.runtime.submit('kaka', first), f.runtime.submit('kaka', first)]);
  await f.runtime.submit('kaka', first);
  await f.runtime.submit('kaka', { clientId: 'message-two', text: 'Second fictional message' });
  await f.runtime.flush('kaka');
  const agent = await f.runtime.agent('kaka');
  assert.equal(agent.deliveries.length, 1);
  const batch = JSON.parse(agent.deliveries[0].message.content[0].text);
  assert.deepEqual(batch.userMessages.map(item => item.id), ['message-one', 'message-two']);
  assert.equal((await f.store.listMessages('kaka')).messages.length, 2);
});

test('real DSH restriction hides inherited phone tools and guard blocks a scoped override', async t => {
  const f = await fixture(t);
  let unsafeCalls = 0, publicCalls = 0;
  const output = { schema: { type: 'json' }, render: (_args, value) => [{ type: 'text', text: JSON.stringify(value) }] };
  f.ctx.tools.register(defineTool({ name: 'android_click', description: 'Fictional phone action', parameters: {}, output, execute: () => { unsafeCalls++; return { clicked: true }; } }));
  f.ctx.tools.register(defineTool({ name: 'web_search', description: 'Fictional public search', parameters: {}, output, execute: () => { publicCalls++; return { found: true }; } }));
  const agent = await f.runtime.agent('kaka');
  assert.equal(f.ctx.tools.schemas(agent).some(tool => tool.name === 'android_click'), false);
  const execution = name => ({ name, arguments: {}, agent, callId: 'fixture-' + name, signal: new AbortController().signal });
  const hidden = await f.ctx.tools.execute(execution('android_click'));
  assert.equal(hidden.isError, true);
  const allowed = await f.ctx.tools.execute(execution('web_search'));
  assert.equal(allowed.isError, false, JSON.stringify(allowed));
  assert.equal(publicCalls, 1);
  agent.ctx.tools.register(defineTool({ name: 'android_click', description: 'Fictional scoped override', parameters: {}, output, execute: () => { unsafeCalls++; return { clicked: true }; } }));
  const overridden = await f.ctx.tools.execute(execution('android_click'));
  assert.equal(overridden.isError, true);
  assert.match(JSON.stringify(overridden), /COMPANION_TOOL_NOT_ALLOWED/);
  assert.equal(unsafeCalls, 0);
});

test('managed workspaces and sessions reject normal manual entry including canonical aliases', async t => {
  const f = await fixture(t), agent = await f.runtime.agent('kaka');
  const profile = await f.store.getProfile('kaka'), workspace = f.workspaces.get(profile.workspaceId);
  await assert.rejects(f.ctx.sessionController.create({ workspaceId: profile.workspaceId }), code('COMPANION_WORKSPACE_MANAGED'));
  await assert.rejects(f.ctx.sessionController.create({ cwd: workspace.path }), code('COMPANION_WORKSPACE_MANAGED'));
  for (const name of ['prompt', 'fork', 'selectModel']) await assert.rejects(f.ctx.sessionController[name]({ sessionId: agent.id }), code('COMPANION_SESSION_MANAGED'));
  const alias = join(f.directory, 'workspace-alias');
  await symlink(workspace.path, alias);
  await assert.rejects(f.ctx.sessionController.create({ cwd: alias }), code('COMPANION_WORKSPACE_MANAGED'));
});

test('remember checks real user provenance and forgetting rebuilds context without learned leakage', async t => {
  const f = await fixture(t), agent = await f.runtime.agent('kaka');
  await f.store.appendMessage('kaka', { id: 'private-message', role: 'user', text: 'Fictional private preference' });
  await f.store.appendMessage('maimai', { id: 'other-message', role: 'user', text: 'Other fictional preference' });
  const tool = f.ctx.tools.get('companion_remember', agent);
  const exec = { agent, signal: new AbortController().signal };
  await assert.rejects(tool.execute({ text: 'Borrowed fictional fact', sourceIds: ['other-message'] }, exec), code('COMPANION_LEARNING_SOURCE_REQUIRED'));
  const remembered = await tool.execute({ text: 'Fictional private preference', sourceIds: ['private-message'] }, exec);
  await f.store.updateState('kaka', {
    summary: 'Fictional private preference',
    impressions: [{ text: 'Fictional inferred impression', sourceIds: ['private-message'] }],
    expressions: [{ situation: 'Fictional situation', expression: 'Private fictional phrase', sourceIds: ['private-message'] }],
    jargon: [{ term: 'private', meaning: 'Fictional private jargon', sourceIds: ['private-message'] }],
  });
  await f.runtime.forget('kaka', { recordId: remembered.recordId, expectedRevisionId: remembered.revisionId });
  const context = await f.runtime.context('kaka');
  assert.deepEqual(context.messages, []);
  assert.deepEqual(context.impressions, []);
  assert.deepEqual(context.expressions, []);
  assert.deepEqual(context.jargon, []);
  assert.equal(context.summary, '');
  assert.equal((await f.store.getProfile('kaka')).chatSessionId, null);
  assert.ok(f.calls.cancelled.some(item => item.id === agent.id));
  assert.ok(f.calls.disposed.includes(agent.id));
  assert.notEqual((await f.runtime.agent('kaka')).id, agent.id);
});

test('explicit recall is shared with Replyer once per turn and a new input retires the recalled set',async t=>{
  const f=await fixture(t),agent=await f.runtime.agent('kaka'),owner=await f.store.sessionOwner(agent.id);
  await f.store.appendMessage('kaka',{id:'recall-question',role:'user',text:'Which fictional drink did I prefer?'});
  await f.store.appendMessage('kaka',{id:'prior-assistant',role:'assistant',text:'A fictional earlier reply.'});
  const record=await (await f.runtime.memory('kaka')).remember({text:'Fictional preference for jasmine tea'});
  assert.deepEqual((await f.runtime.context('kaka',owner)).memories,[],'Default upstream heuristic recall is disabled');
  assert.equal(f.calls.llm.length,0);
  await f.ctx.tools.get('companion_recall',agent).execute({query:'jasmine tea'},{agent});
  const recalled=await f.runtime.context('kaka',owner);
  assert.ok(recalled.memories.some(item=>item.recordId===record.recordId));
  const result=await f.runtime.reply(owner,{msg_id:'recall-question',reply_reference:'Use the retrieved fictional preference.'});
  assert.equal(f.calls.llm.length,1,'Replyer uses the Planner recall without a second semantic query');
  const request=f.calls.llm[0].input;
  assert.ok(request.messages.some(item=>item.role==='assistant'&&item.content.some(block=>block.text?.includes('A fictional earlier reply.'))));
  assert.ok(request.messages.some(item=>item.role==='user'&&item.content.some(block=>block.text?.includes('Which fictional drink did I prefer?'))));
  assert.match(JSON.stringify(request.messages),/Fictional preference for jasmine tea/u);
  assert.ok(result.messageIds.length);
  const delivered=await f.store.getMessage('kaka',result.messageIds[0]);
  assert.ok(delivered.contextSourceIds.includes('memory:'+record.recordId+':'+record.revisionId));
  await f.runtime.submit('kaka',{clientId:'new-recall-turn',text:'Now an unrelated fictional subject.'});
  assert.deepEqual((await f.runtime.context('kaka',owner)).memories,[]);
});

test('a recall snapshot returning after correction or forgetting cannot enter the shared reply context',async t=>{
  for(const operation of ['revise','forget'])await t.test(operation,async child=>{
    const f=await fixture(child),agent=await f.runtime.agent('kaka'),memory=await f.runtime.memory('kaka');
    const record=await memory.remember({text:'Fictional superseded preference'}),query=memory.query.bind(memory);
    let release,entered;
    const gate=new Promise(resolve=>{release=resolve;}),captured=new Promise(resolve=>{entered=resolve;});
    memory.query=async input=>{const result=await query(input);entered();await gate;return result;};
    child.after(()=>{release();memory.query=query;});
    const pending=f.ctx.tools.get('companion_recall',agent).execute({query:'superseded preference'},{agent,signal:new AbortController().signal})
      .then(value=>({value}),error=>({error}));
    await captured;
    if(operation==='revise')await f.runtime.reviseMemory('kaka',{recordId:record.recordId,expectedRevisionId:record.revisionId,text:'Fictional corrected preference'});
    else await f.runtime.forget('kaka',{recordId:record.recordId,expectedRevisionId:record.revisionId});
    release();memory.query=query;
    assert.equal((await pending).error?.code,'COMPANION_REPLY_SUPERSEDED');
    assert.equal(f.runtime.turnMemories.has(agent.id),false);
    assert.ok(!JSON.stringify((await f.store.getState('kaka')).sessionSources??{}).includes(record.revisionId));
    const next=await f.runtime.agent('kaka');
    assert.ok(!(await f.runtime.context('kaka',await f.store.sessionOwner(next.id))).memories.some(item=>item.revisionId===record.revisionId));
  });
});

test('chat reply leaves Planner running while a delegated work reply concludes with its actual result',async t=>{
  const f=await fixture(t),chat=await f.runtime.agent('kaka');
  await f.store.appendMessage('kaka',{id:'reply-action',role:'user',text:'A fictional greeting.'});
  let concluded=false;
  const response=await f.ctx.tools.get('companion_reply',chat).execute({msg_id:'reply-action',reply_reference:'Answer this greeting.'},
    {agent:chat,concludeTurn(){concluded=true;}});
  assert.equal(concluded,false);assert.equal(response.delivered,true);
  assert.match(JSON.stringify(response),/Fictional companion response/u,'Planner receives the actual visible body');
  const work=await f.runtime.startWork('kaka',{title:'Fictional final result',goal:'Provide an actual fictional result'}),worker=f.ctx.agents.get(work.sessionId);
  const workReply=await f.ctx.tools.get('companion_reply',worker).execute({msg_id:'',reply_reference:'Deliver the final work result.'},
    {agent:worker,concludeTurn(){concluded=true;}});
  assert.equal(concluded,true);assert.equal(workReply.result,'Fictional companion response.');
  const assembly=await f.ctx.systemPrompt.assemble({agent:worker,scope:worker});
  for(const name of ['companion_wait','companion_quiet','companion_work','companion_stickers','companion_emoji'])
    assert.equal(assembly.tools.some(tool=>tool.name===name),false,'Delegated work cannot use '+name);
});

test('visual Replyer verifies real shared image bytes before sending a scoped image reference',async t=>{
  const f=await fixture(t,{withAttachments:true});
  f.runtime.options.maibot={enableVisualMessage:true,typo:{enabled:false}};
  const png=encodePng({width:2,height:2,channels:4,pixels:Buffer.alloc(16,200)});
  const ref=await f.ctx.attachments.saveImage({data:png,mediaType:'image/png',name:'fictional-shared.png'});
  await f.store.putUpload('kaka',{id:'visual-upload',name:ref.name,mediaType:'image/png',bytes:ref.bytes,ref,url:'/droy/companions/kaka/attachments/visual-upload'});
  await f.store.appendMessage('kaka',{id:'visual-source',role:'user',type:'image',text:'What is in this fictional image?',
    attachments:[{id:'visual-upload',name:ref.name,mediaType:'image/png',bytes:ref.bytes}]});
  const agent=await f.runtime.agent('kaka'),owner=await f.store.sessionOwner(agent.id);
  await f.runtime.reply(owner,{msg_id:'visual-source',reply_reference:'Describe the shared image.'});
  const images=f.calls.llm.at(-1).input.messages.flatMap(message=>message.content.filter(block=>block.type==='image'));
  assert.deepEqual(images.map(block=>block.attachment),[ref]);
  assert.equal((await f.store.getMessage('kaka','visual-source')).attachments[0].ref,undefined,'Read-time image refs must not be written into learned history');
  await f.store.appendMessage('maimai',{id:'other-visual',role:'user',type:'image',attachments:[{id:'visual-upload',name:ref.name,mediaType:'image/png',bytes:ref.bytes}]});
  const other=await f.runtime.agent('maimai');
  await assert.rejects(f.runtime.reply(await f.store.sessionOwner(other.id),{msg_id:'other-visual',reply_reference:'Read the other companion image.'}),code('COMPANION_ATTACHMENT_SCOPE'));
});

test('close aborts an in-flight learning request before any later state write', async t => {
  let release;
  const gate = new Promise(resolve => { release = resolve; });
  const f = await fixture(t, { learningMessages: 1, stream: async function* (call) {
    await Promise.race([gate, new Promise((_, reject) => {
      const abort = () => reject(call.input.signal.reason);
      if (call.input.signal?.aborted) abort();
      else call.input.signal?.addEventListener('abort', abort, { once: true });
    })]);
    call.input.signal?.throwIfAborted();
    yield { type: 'text-delta', text: JSON.stringify({ impressions: [{ text: 'Fictional learned impression', sourceIds: ['learn-source'] }], expressions: [], jargon: [], memories: [] }) };
    yield { type: 'finish', reason: { kind: 'stop' } };
  } });
  await f.store.appendMessage('kaka', { id: 'learn-source', role: 'user', text: 'Fictional learning source' });
  const learning = f.runtime.learn('kaka').then(() => null, error => error);
  try {
    await eventually(() => f.calls.llm.length, 'learning request');
    await f.runtime.close();
    assert.equal(f.calls.llm[0].signal?.aborted, true);
  } finally { release(); await learning; }
  assert.deepEqual((await f.store.getState('kaka')).impressions ?? [], []);
});

test('new chat input does not cancel the reply generation of an independent work task', async t => {
  let release;
  const gate = new Promise(resolve => { release = resolve; });
  const f = await fixture(t, { stream: async function* (call) {
    await gate;
    call.input.signal?.throwIfAborted();
    yield { type: 'text-delta', text: 'Fictional completed work result' };
    yield { type: 'finish', reason: { kind: 'stop' } };
  } });
  const task = await f.runtime.startWork('kaka', { title: 'Fictional independent work', goal: 'Fictional independent goal' });
  const owner = await f.store.sessionOwner(task.sessionId);
  const reply = f.runtime.reply(owner, { intent: 'Report fictional work result' }, new AbortController().signal).then(value => value, error => error);
  try {
    await eventually(() => f.calls.llm.length, 'work reply request');
    await f.runtime.submit('kaka', { clientId: 'unrelated-chat', text: 'An unrelated fictional greeting' });
    assert.equal(f.calls.llm[0].signal.aborted, false);
  } finally { release(); await reply; }
});

test('work startup failure leaves a failed task rather than a permanently running card', async t => {
  const f = await fixture(t, { failWorkCreation: true });
  await assert.rejects(f.runtime.startWork('kaka', { title: 'Fictional startup failure', goal: 'Fictional goal' }), /FICTIONAL_WORK_START_FAILED/);
  const page = await f.store.listMessages('kaka');
  assert.equal(page.messages.length, 1);
  assert.equal(page.messages[0].task.state, 'failed');
});

test('a failed planner round stays retryable without duplicating the acknowledged user message',async t=>{
  const f=await fixture(t);await f.runtime.submit('kaka',{clientId:'retry-source',text:'Fictional request'});await f.runtime.flush('kaka');
  const agent=await f.runtime.agent('kaka'),owner=await f.store.sessionOwner(agent.id);
  agent.status='idle';await f.runtime.finishTurn(owner,{data:{reason:{kind:'failed'}}});
  assert.equal(f.runtime.state('kaka').phase,'error');
  assert.deepEqual((await f.store.getState('kaka')).pendingMessageIds,['retry-source']);
  await f.runtime.retry('kaka');assert.equal(agent.deliveries.length,2);
  assert.equal((await f.store.listMessages('kaka')).messages.length,1);
  await f.runtime.reply(owner,{intent:'Fictional recovered response'});
  await f.runtime.finishTurn(owner,{data:{reason:{kind:'completed'}}});
  assert.deepEqual((await f.store.getState('kaka')).pendingMessageIds,[]);
});

test('a planner can intentionally finish without a tool or visible message',async t=>{
  const f=await fixture(t);await f.runtime.submit('kaka',{clientId:'unanswered',text:'A fictional direct question?'});await f.runtime.flush('kaka');
  const agent=await f.runtime.agent('kaka');await f.runtime.finishTurn(await f.store.sessionOwner(agent.id),{data:{reason:{kind:'completed'}}});
  assert.equal(f.runtime.state('kaka').phase,'idle');
  assert.equal(f.runtime.state('kaka').error,undefined);
  assert.deepEqual((await f.store.getState('kaka')).pendingMessageIds,[]);
  assert.equal((await f.store.listMessages('kaka')).messages.filter(item=>item.role==='assistant').length,0);
});

test('a forgotten pending input is never readmitted by retry or startup recovery',async t=>{
  const f=await fixture(t);await f.runtime.submit('kaka',{clientId:'forgotten-pending',text:'Fictional private preference'});await f.runtime.flush('kaka');
  const old=await f.runtime.agent('kaka');await f.runtime.finishTurn(await f.store.sessionOwner(old.id),{data:{reason:{kind:'failed'}}});
  const memory=await f.runtime.memory('kaka'),record=await memory.remember({text:'Fictional private preference',sourceRefs:['forgotten-pending']});
  await f.runtime.forget('kaka',{recordId:record.recordId,expectedRevisionId:record.revisionId});
  await f.runtime.retry('kaka');assert.deepEqual((await f.store.getState('kaka')).pendingMessageIds,[]);
  assert.equal(f.runtime.state('kaka').pending.length,0);assert.equal(f.calls.agents.length,1);
  await f.runtime.close();const resumed=new CompanionRuntime({ctx:f.ctx,store:f.store,directory:f.directory,options:{debounceMs:0}});t.after(()=>resumed.close());
  await resumed.initialize();await resumed.start();assert.equal(resumed.state('kaka').pending.length,0);
});

test('real DSH aborted-turn reasons remain cancellation rather than failed tasks',async t=>{
  const f=await fixture(t),task=await f.runtime.startWork('kaka',{title:'Fictional cancelled work',goal:'Fictional goal'});
  await f.runtime.finishTurn(await f.store.sessionOwner(task.sessionId),{data:{reason:{kind:'aborted',reason:{kind:'user'}}}});
  assert.equal((await f.store.getMessage('kaka',task.taskId)).task.state,'cancelled');
  const chat=await f.runtime.agent('kaka');await f.runtime.cancel('kaka');
  await f.runtime.finishTurn(await f.store.sessionOwner(chat.id),{data:{reason:{kind:'aborted',reason:{kind:'user'}}}});
  assert.equal(f.runtime.state('kaka').error,undefined);
});

test('restart completes an older saved multi-part reply without replaying the user',async t=>{
  const f=await fixture(t);
  await f.runtime.submit('kaka',{clientId:'partial-reply',text:'Fictional request'});await f.runtime.flush('kaka');
  const agent=await f.runtime.agent('kaka');
  const draft=await prepareReply({store:f.store,companionId:'kaka',sourceMessageIds:['partial-reply'],parts:['Fictional first bubble.','Fictional second bubble.']});
  const abort=new AbortController(),cancelAfterFirst=f.runtime.subscribe(event=>{if(event.kind==='message')abort.abort();});
  await assert.rejects(f.runtime.publish('kaka',draft,abort.signal));cancelAfterFirst();
  assert.equal((await f.store.listMessages('kaka')).messages.filter(item=>item.role==='assistant').length,1);
  await f.runtime.close();const resumed=new CompanionRuntime({ctx:f.ctx,store:f.store,directory:f.directory,options:{debounceMs:0,typingMinMs:0,typingMaxMs:0}});t.after(()=>resumed.close());
  await resumed.initialize();await resumed.start();
  const draftId=(await f.store.getState('kaka')).replyDraft.id;
  await resumed.submit('kaka',{clientId:'partial-reply',text:'Fictional request'});
  assert.equal((await f.store.getState('kaka')).replyDraft.id,draftId,'A repeated HTTP acknowledgement must not supersede the saved reply');
  await eventually(async()=>!(await f.store.getState('kaka')).replyDraft,'remaining reply publication');
  assert.deepEqual((await f.store.listMessages('kaka')).messages.filter(item=>item.role==='assistant').map(item=>item.text),['Fictional first bubble.','Fictional second bubble.']);
  assert.equal(f.calls.agents.length,1,'Recovery publishes the draft without generating or re-admitting input');
  assert.deepEqual((await f.store.getState('kaka')).pendingMessageIds,[]);
});

test('an unrecognized voice remains playable but only starts understanding after its original message is transcribed',async t=>{
  const f=await fixture(t);
  await f.runtime.submit('kaka',{clientId:'voice-message',text:'',voice:{attachmentId:'fictional-audio',url:'/fictional-audio',transcriptionState:'failed'}});
  assert.equal(f.runtime.state('kaka').pending.length,0);assert.equal(f.calls.agents.length,0);
  await f.runtime.transcribeVoice('kaka','voice-message','Fictional recovered transcript');await f.runtime.flush('kaka');
  assert.equal((await f.store.listMessages('kaka')).messages.length,1);
  assert.equal((await f.store.getMessage('kaka','voice-message')).text,'Fictional recovered transcript');
  await f.runtime.transcribeVoice('kaka','voice-message','Repeated recognition');
  assert.equal(f.runtime.state('kaka').pending.length,0);
});

test('continuing and cancelling one work task preserve the current chat and other work',async t=>{
  const f=await fixture(t),task=await f.runtime.startWork('kaka',{title:'Fictional task',goal:'First goal'});
  const continued=await f.runtime.startWork('kaka',{title:'Fictional task',goal:'Changed goal',taskId:task.taskId});
  assert.equal(continued.sessionId,task.sessionId);assert.equal((await f.store.listSessions('kaka',{kind:'work'})).length,1);
  await f.runtime.submit('kaka',{clientId:'parallel-chat',text:'Fictional chat'});
  await f.runtime.cancel('kaka',{taskId:task.taskId});
  assert.equal(f.runtime.state('kaka').pending.length,1);
  assert.deepEqual((await f.store.getState('kaka')).pendingMessageIds,['parallel-chat']);
});

test('a pending wait resumes the existing exchange independently of proactive permission',async t=>{
  const f=await fixture(t),agent=await f.runtime.agent('kaka');
  await f.store.updateState('kaka',{pendingIntent:{kind:'wait',text:'Fictional continuation'},waitingUntil:new Date(0).toISOString()});
  await f.runtime.wake('kaka',{kind:'wait',text:'Fictional continuation'});assert.equal(agent.deliveries.length,1);
  assert.equal((await f.store.getState('kaka')).waitingUntil,null);
  assert.equal((await f.store.getState('kaka')).pendingIntent,null);
});

test('live wait stays in its tool invocation and cancellation clears its durable deadline',async t=>{
  const f=await fixture(t),agent=await f.runtime.agent('kaka'),tool=f.ctx.tools.get('companion_wait',agent);
  const first=await tool.execute({seconds:0.02},{agent,signal:new AbortController().signal});
  assert.equal(first.reason,'timeout');assert.ok(first.waitedSeconds>=0.015);
  assert.equal(agent.deliveries.length,0,'Completing an active wait must not enqueue another user turn');
  assert.equal((await f.store.getState('kaka')).waitingUntil,null);
  const pending=tool.execute({seconds:60},{agent,signal:new AbortController().signal}).then(value=>({value}),error=>({error}));
  await eventually(async()=>(await f.store.getState('kaka')).waitingUntil,'durable waiting deadline');
  await f.runtime.cancel('kaka');
  assert.equal((await pending).error?.name,'AbortError');
  assert.equal((await f.store.getState('kaka')).waitingUntil,null);
  assert.equal((await f.store.getState('kaka')).pendingIntent,null);
  assert.equal(agent.deliveries.length,0);
});

test('startup restores a crashed wait before pending input without replaying the original user message',async t=>{
  const f=await fixture(t);await f.runtime.submit('kaka',{clientId:'waiting-source',text:'Fictional pending continuation'});await f.runtime.flush('kaka');
  const original=await f.runtime.agent('kaka');
  await f.store.updateState('kaka',{chatNeedsReset:true,waitingUntil:new Date(Date.now()+120).toISOString(),
    pendingIntent:{kind:'wait',sessionId:original.id,sourceMessageIds:['waiting-source']}});
  await f.runtime.close();
  const resumed=new CompanionRuntime({ctx:f.ctx,store:f.store,directory:f.directory,options:{debounceMs:0}});t.after(()=>resumed.close());
  await resumed.initialize();await resumed.start();
  assert.equal((await f.store.getProfile('kaka')).chatSessionId,original.id);
  assert.equal(resumed.state('kaka').phase,'waiting');
  assert.equal(f.calls.agents.length,1);
  await eventually(()=>f.calls.agents.length===2,'persisted wait deadline');
  const restored=f.calls.agents.at(-1);
  assert.equal(restored.resumed,true);assert.equal(restored.agent.id,original.id);
  assert.equal(restored.agent.deliveries.length,1);
  const payload=JSON.parse(restored.agent.deliveries[0].message.content[0].text);
  assert.equal(payload.internalIntent.kind,'wait');assert.equal(payload.userMessages,undefined);
  await delay(25);assert.equal(restored.agent.deliveries.length,1);
  await resumed.finishTurn(await f.store.sessionOwner(original.id),{data:{reason:{kind:'completed'}}});
  assert.deepEqual((await f.store.getState('kaka')).pendingMessageIds,[]);
});

test('closing an active wait preserves its deadline and restart resumes it once',async t=>{
  const f=await fixture(t);await f.runtime.submit('kaka',{clientId:'live-wait-source',text:'Fictional unfinished exchange'});await f.runtime.flush('kaka');
  const original=await f.runtime.agent('kaka'),owner=await f.store.sessionOwner(original.id);
  const waiting=f.runtime.wait(owner,{seconds:0.25},new AbortController().signal).then(value=>({value}),error=>({error}));
  await eventually(async()=>(await f.store.getState('kaka')).pendingIntent?.waitId,'active wait persisted');
  const saved=await f.store.getState('kaka');
  await f.runtime.close();assert.equal((await waiting).error?.name,'AbortError');
  assert.equal((await f.store.getState('kaka')).waitingUntil,saved.waitingUntil);
  assert.deepEqual((await f.store.getState('kaka')).pendingIntent,saved.pendingIntent);
  const resumed=new CompanionRuntime({ctx:f.ctx,store:f.store,directory:f.directory,options:{debounceMs:0}});t.after(()=>resumed.close());
  await resumed.initialize();await resumed.start();
  assert.equal(f.calls.agents.length,1);assert.equal(resumed.state('kaka').phase,'waiting');
  await eventually(()=>f.calls.agents.length===2,'active wait resumed after restart');
  assert.ok(Date.now()>=Date.parse(saved.waitingUntil));
  const restored=f.calls.agents.at(-1).agent;assert.equal(restored.id,original.id);
  assert.equal(restored.deliveries.length,1);assert.equal((await f.store.getState('kaka')).waitingUntil,null);
  await resumed.wake('kaka',saved.pendingIntent);assert.equal(restored.deliveries.length,1,'A stale callback cannot resume the same wait twice');
});

test('a wake awaiting profile state cannot dispatch after cancellation or closure',async t=>{
  for(const operation of ['cancel','close'])await t.test(operation,async child=>{
    const f=await fixture(child),agent=await f.runtime.agent('kaka');
    await f.store.updateProfile('kaka',{settings:{proactiveEnabled:true,quietHours:{start:'00:00',end:'00:00'}}});
    const getProfile=f.store.getProfile.bind(f.store);let release,entered;
    const gate=new Promise(resolve=>{release=resolve;}),captured=new Promise(resolve=>{entered=resolve;});
    f.store.getProfile=async id=>{const result=await getProfile(id);entered();await gate;return result;};
    child.after(()=>{release();f.store.getProfile=getProfile;});
    const waking=f.runtime.wake('kaka',{kind:'proactive'});
    await captured;
    if(operation==='cancel')await f.runtime.cancel('kaka');else await f.runtime.close();
    release();await waking;f.store.getProfile=getProfile;
    assert.equal(agent.deliveries.length,0);assert.equal(f.calls.agents.length,1);
  });
});

test('proactive wake still obeys permission and quiet hours',async t=>{
  const f=await fixture(t),agent=await f.runtime.agent('kaka');
  await f.runtime.wake('kaka',{kind:'proactive'});assert.equal(agent.deliveries.length,0);
  const now=new Date(),start=new Date(now.getTime()-60000),end=new Date(now.getTime()+60000);
  const hhmm=date=>String(date.getHours()).padStart(2,'0')+':'+String(date.getMinutes()).padStart(2,'0');
  await f.store.updateProfile('kaka',{settings:{proactiveEnabled:true,quietHours:{start:hhmm(start),end:hhmm(end)}}});
  await f.runtime.wake('kaka',{kind:'proactive'});assert.equal(agent.deliveries.length,0);
  await f.store.updateProfile('kaka',{settings:{quietHours:{start:'00:00',end:'00:00'}}});
  await f.runtime.wake('kaka',{kind:'proactive'});assert.equal(agent.deliveries.length,1);
});

test('editing memory replaces stale shared understanding and reset retains visible history without relearning it',async t=>{
  const f=await fixture(t),memory=await f.runtime.memory('kaka');
  await f.store.appendMessage('kaka',{id:'old-source',role:'user',text:'Fictional old city'});
  const record=await memory.remember({text:'Fictional old city',sourceRefs:['old-source']});
  const changed=await f.runtime.reviseMemory('kaka',{recordId:record.recordId,expectedRevisionId:record.revisionId,text:'Fictional new city'});
  assert.equal(changed.record.payload.text,'Fictional new city');
  assert.deepEqual((await f.runtime.context('kaka')).messages,[]);
  assert.ok((await f.store.getState('kaka')).memoryRevision>0);
  await f.runtime.resetMemories('kaka');
  assert.equal((await f.store.listMessages('kaka')).messages.length,1);
  assert.equal((await memory.query()).total,0);assert.deepEqual((await f.runtime.context('kaka')).messages,[]);
});

test('correcting an unrelated manual memory preserves completed and running work',async t=>{
  const f=await fixture(t),memory=await f.runtime.memory('kaka');
  const completed=await f.runtime.startWork('kaka',{title:'Completed fictional work',goal:'A fictional independent goal'});
  await f.runtime.reply(await f.store.sessionOwner(completed.sessionId),{intent:'An actual fictional work result'});
  await f.runtime.finishTurn(await f.store.sessionOwner(completed.sessionId),{data:{reason:{kind:'completed'}}});
  const before=await f.store.getMessage('kaka',completed.taskId);
  assert.equal(before.task.state,'completed');
  const running=await f.runtime.startWork('kaka',{title:'Running fictional work',goal:'Another independent goal'});
  const record=await memory.remember({text:'An unrelated manually saved preference'});
  await f.runtime.reviseMemory('kaka',{recordId:record.recordId,expectedRevisionId:record.revisionId,text:'The corrected unrelated preference'});
  assert.deepEqual((await f.store.getMessage('kaka',completed.taskId)).task,before.task);
  assert.equal((await f.store.getMessage('kaka',running.taskId)).task.state,'running');
  assert.ok(!f.calls.cancelled.some(item=>item.id===running.sessionId));
});

test('an unrelated manual memory does not cancel work that read a long summary with older exclusions',async t=>{
  const f=await fixture(t),memory=await f.runtime.memory('kaka');
  for(let index=0;index<60;index++)await f.store.appendMessage('kaka',{id:'long-history-'+index,role:'user',text:'Fictional unrelated history '+index});
  await f.store.excludeDerivedMessages('kaka',['long-history-0']);
  const running=await f.runtime.startWork('kaka',{title:'Independent long-context work',goal:'A fictional independent request'});
  const context=await f.runtime.context('kaka',await f.store.sessionOwner(running.sessionId));
  assert.ok(context.summary);
  const record=await memory.remember({text:'A new fictional manual preference never used by that work'});
  await f.runtime.reviseMemory('kaka',{recordId:record.recordId,expectedRevisionId:record.revisionId,text:'The corrected new preference'});
  assert.equal((await f.store.getMessage('kaka',running.taskId)).task.state,'running');
  assert.ok(!f.calls.cancelled.some(item=>item.id===running.sessionId));
});

test('correcting a manual memory invalidates work that read its assistant echo through a summary',async t=>{
  const f=await fixture(t,{stream:async function*(call){
    const input=JSON.parse(call.input.messages[0].content[0].text);
    yield {type:'text-delta',text:input.candidates?JSON.stringify({recordIds:[]}):'A fictional summary of the prior assistant echo.'};
    yield {type:'finish',reason:{kind:'stop'}};
  }}),memory=await f.runtime.memory('kaka');
  const record=await memory.remember({text:'Fictional private preference'});
  await f.store.appendMessage('kaka',{id:'memory-echo',role:'assistant',text:'The fictional private preference',contextSourceIds:['memory:'+record.recordId+':'+record.revisionId]});
  for(let index=0;index<60;index++)await f.store.appendMessage('kaka',{id:'later-history-'+index,role:'user',text:'Fictional later history '+index});
  const running=await f.runtime.startWork('kaka',{title:'Work reading the summary',goal:'A fictional followup'});
  const context=await f.runtime.context('kaka',await f.store.sessionOwner(running.sessionId));
  assert.ok(context.summary);assert.equal(context.memories.length,0);
  assert.ok(!context.messages.some(item=>item.id==='memory-echo'));
  await f.runtime.reviseMemory('kaka',{recordId:record.recordId,expectedRevisionId:record.revisionId,text:'Fictional corrected preference'});
  assert.equal((await f.store.getMessage('kaka','memory-echo')).contextExcluded,true);
  assert.equal((await f.store.getMessage('kaka',running.taskId)).task.state,'cancelled');
  assert.ok(f.calls.cancelled.some(item=>item.id===running.sessionId));
});

test('correcting a used memory cancels dependent running work while retaining completed work as excluded history',async t=>{
  const f=await fixture(t),memory=await f.runtime.memory('kaka');
  const record=await memory.remember({text:'Fictional original location'});
  const completed=await f.runtime.startWork('kaka',{title:'Earlier completed work',goal:'An earlier independent work request'});
  const owner=await f.store.sessionOwner(completed.sessionId),agent=f.ctx.agents.get(completed.sessionId);
  await f.ctx.tools.get('companion_recall',agent).execute({query:'Fictional original location'},{agent});
  await f.runtime.reply(owner,{intent:'The sourced fictional work result'});
  await f.runtime.finishTurn(owner,{data:{reason:{kind:'completed'}}});
  const before=await f.store.getMessage('kaka',completed.taskId);
  const running=await f.runtime.startWork('kaka',{title:'Dependent running work',goal:'A current request using that location'});
  const worker=f.ctx.agents.get(running.sessionId);
  await f.ctx.tools.get('companion_recall',worker).execute({query:'Fictional original location'},{agent:worker});
  await f.runtime.reviseMemory('kaka',{recordId:record.recordId,expectedRevisionId:record.revisionId,text:'Fictional corrected location'});
  const history=await f.store.getMessage('kaka',completed.taskId);
  assert.deepEqual(history.task,before.task);assert.equal(history.contextExcluded,true);
  assert.equal((await f.store.getMessage('kaka',running.taskId)).task.state,'cancelled');
  assert.ok(f.calls.cancelled.some(item=>item.id===running.sessionId));
  assert.ok((await f.runtime.context('kaka')).messages.every(item=>item.id!==completed.taskId));
});

test('manual memory edits and forgetting retire prior replies without preventing the corrected record from being used',async t=>{
  const f=await fixture(t),memory=await f.runtime.memory('kaka'),agent=await f.runtime.agent('kaka');
  const owner=f.runtime.owners.get(agent.id);
  await f.store.appendMessage('kaka',{id:'ask-city',role:'user',text:'Which fictional city do I live in?'});
  const record=await memory.remember({text:'Fictional old city'});
  await f.ctx.tools.get('companion_recall',agent).execute({query:'Fictional old city'},{agent});
  await f.runtime.context('kaka',owner);
  assert.ok(f.runtime.contextSources.get(agent.id).contextSourceIds.includes('memory:'+record.recordId));
  await f.store.appendMessage('kaka',{id:'old-answer',role:'assistant',text:'Fictional old city',...f.runtime.contextSources.get(agent.id)});
  const changed=await f.runtime.reviseMemory('kaka',{recordId:record.recordId,expectedRevisionId:record.revisionId,text:'Fictional new city'});
  assert.equal((await f.store.getMessage('kaka','old-answer')).contextExcluded,true);
  assert.ok((await f.runtime.context('kaka')).messages.every(item=>item.id!=='old-answer'));
  const next=await f.runtime.agent('kaka'),nextOwner=f.runtime.owners.get(next.id);
  await f.ctx.tools.get('companion_recall',next).execute({query:'Fictional new city'},{agent:next});
  await f.runtime.context('kaka',nextOwner);
  const newSources=f.runtime.contextSources.get(next.id);
  assert.ok(newSources.contextSourceIds.includes('memory:'+record.recordId+':'+changed.revisionId));
  await f.store.appendMessage('kaka',{id:'new-answer',role:'assistant',text:'Fictional new city',...newSources});
  await f.runtime.forget('kaka',{recordId:record.recordId,expectedRevisionId:changed.revisionId});
  assert.equal((await f.store.getMessage('kaka','new-answer')).contextExcluded,true);
  assert.ok((await f.runtime.context('kaka')).messages.every(item=>!['old-answer','new-answer'].includes(item.id)));
  assert.equal((await f.store.listMessages('kaka')).messages.length,3);
});

test('a forget request cannot relearn its repeated fact and cancels work that explicitly recalled a manual memory',async t=>{
  const f=await fixture(t),memory=await f.runtime.memory('kaka'),record=await memory.remember({text:'Fictional private city'});
  const task=await f.runtime.startWork('kaka',{title:'Fictional work',goal:'Check the fictional city'});
  const worker=f.ctx.agents.get(task.sessionId);
  await f.ctx.tools.get('companion_recall',worker).execute({query:'Fictional private city'},{agent:worker});
  await f.runtime.submit('kaka',{clientId:'forget-request',text:'Please forget that I live in Fictional private city'});
  const chat=await f.runtime.agent('kaka');let concluded=false;
  const result=await f.ctx.tools.get('companion_forget',chat).execute({recordId:record.recordId,expectedRevisionId:record.revisionId,requestMessageId:'forget-request'},
    {agent:chat,concludeTurn(){concluded=true;}});
  assert.ok(result);assert.equal(concluded,true);
  assert.equal((await f.store.getMessage('kaka','forget-request')).contextExcluded,true);
  assert.equal((await f.store.getMessage('kaka',task.taskId)).task.state,'cancelled');
  assert.ok(f.calls.cancelled.some(item=>item.id===worker.id));
  assert.ok((await f.runtime.context('kaka')).messages.every(item=>!item.text.includes('Fictional private city')));
  assert.equal((await f.store.listMessages('kaka')).messages.at(-1).text,'Okay, I’ve forgotten that.');
});

test('authenticated HTTP attachment and memory access remain scoped to the addressed companion', async t => {
  const f = await fixture(t);
  const oldToken = process.env.DROY_WEB_TOKEN;
  const token = 'fictional-companion-http-token-1234567890'; process.env.DROY_WEB_TOKEN = token;
  t.after(() => { if (oldToken === undefined) delete process.env.DROY_WEB_TOKEN; else process.env.DROY_WEB_TOKEN = oldToken; });
  await f.store.putUpload('maimai', { id: 'other-upload', mediaType: 'image/png', name: 'fictional.png', bytes:4,url: '/droy/companions/maimai/attachments/other-upload', ref: { id: 'fixture' } });
  const memory = await f.runtime.memory('maimai');
  const remembered = await memory.remember({ text: 'Fictional Maimai-only memory' });
  const handler = createCompanionHandler({ ctx: f.ctx, runtime: f.runtime, store: f.store });
  const server = createServer((req, res) => { handler(req, res).catch(error => { res.writeHead(500); res.end(error.code || error.message); }); });
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  t.after(async () => { server.closeAllConnections(); await new Promise(resolve => server.close(resolve)); });
  const base = 'http://127.0.0.1:' + server.address().port;
  const request = (path, options = {}) => fetch(base + '/droy/companions' + path, { ...options, headers: { cookie: 'droy_session=' + token, 'content-type': 'application/json', ...options.headers } });
  assert.equal((await fetch(base + '/droy/companions')).status, 401);
  const upload = await request('/kaka/messages', { method: 'POST', body: JSON.stringify({ clientId: 'scope-one', text: 'Fictional caption', attachments: [{ id: 'other-upload' }] }) });
  assert.equal(upload.status, 400);
  assert.equal((await upload.json()).error, 'COMPANION_ATTACHMENT_SCOPE');
  assert.equal((await request('/kaka/attachments/other-upload')).status, 404);
  const forgotten = await request('/kaka/memories', { method: 'DELETE', body: JSON.stringify({ recordId: remembered.recordId }) });
  assert.equal(forgotten.status, 404);
  assert.equal((await memory.query()).total, 1);
  const own = await request('/maimai/memories');
  assert.equal(own.status, 200);
  assert.equal((await own.json()).items[0].payload.text, 'Fictional Maimai-only memory');
});
