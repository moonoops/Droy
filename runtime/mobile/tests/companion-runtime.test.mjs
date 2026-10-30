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
import { openLegacyCompanionStore } from './fixtures/legacy-companion-db.mjs';
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

async function fixture(t, { directory, store: suppliedStore, legacy = true, debounceMs = 1000, learningMessages = 12, stream, failWorkCreation = false,seedKakaModel=true,locale='en',beforeInitialize,withAttachments=false } = {}) {
  const ownsDirectory = !directory;
  directory ??= await mkdtemp(join(tmpdir(), 'droy-companion-runtime-'));
  const store = suppliedStore ?? await (legacy ? openLegacyCompanionStore({ directory }) : CompanionStore.open({ directory }));
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
  ctx.provide('droyInitialization',{defaultWorkspaceId:null});
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
    let status='idle',activity=Promise.resolve(),retire;
    Object.defineProperty(agent,'status',{get:()=>status,set(value){
      const previous=status;
      if(value==='running'&&previous!=='running')activity=new Promise(resolve=>{retire=resolve;});
      status=value;
      if(value==='idle'&&previous!=='idle')retire?.();
      if(value!==previous)runtimeContext.emit('agent/status',{agent,status:value});
    }});
    agent.whenIdle=async()=>{let current;do{await(current=activity);}while(current!==activity);};
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
  await ctx.plugin({ name: 'companion-runtime-fixture', inject: ['tools', 'systemPrompt', 'settings', 'workspaceRegistry', 'workingDirectory', 'agentDefaultModel', 'droyInitialization', 'sessionController', 'agents', 'llm',...(withAttachments?['attachments']:[])],
    apply(scope) { runtimeContext = scope; } });
  const runtime = new CompanionRuntime({ ctx: runtimeContext, store, directory, options: { debounceMs, learningMessages }, notify: value => calls.notifications.push(value) });
  await beforeInitialize?.({ctx:runtimeContext,store,directory});
  await runtime.initialize();
  for (const profile of await store.profiles()) {
    if (seedKakaModel && profile.id === 'kaka') await store.updateProfile(profile.id, { model: modelA });
    if (profile.id === 'maimai') await store.updateProfile(profile.id, { model: modelB });
  }
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

test('new instances remain lazy and first concurrent use creates one durable private workspace and chat', async t => {
  const f = await fixture(t, { legacy: false, seedKakaModel: false });
  const initial = (await f.store.profiles())[0];
  await f.runtime.start();
  assert.equal(f.workspaces.size, 1); assert.equal(initial.workspaceId, null);
  assert.equal(f.calls.agents.length, 0); assert.equal(f.calls.llm.length, 0);
  const created = await f.store.createProfile({ requestId: 'lazy-create', templateId: 'maimai', name: 'Fictional second instance', model: null });
  await Promise.all([f.runtime.startInstance(created.profile.id), f.runtime.startInstance(created.profile.id)]);
  assert.equal(f.workspaces.size, 1); assert.equal(f.calls.agents.length, 0);
  const [first, second] = await Promise.all([f.runtime.agent(created.profile.id), f.runtime.agent(created.profile.id)]);
  assert.equal(first, second); assert.equal(f.calls.agents.length, 1); assert.equal(f.workspaces.size, 2);
  const profile = await f.store.getProfile(created.profile.id);
  assert.equal(profile.chatSessionId, first.id); assert.equal(profile.model, null);
  assert.equal(f.workspaces.get(profile.workspaceId).path, join(f.directory, profile.id, 'work'));
  assert.deepEqual((await f.store.listSessions(profile.id)).map(owner => owner.sessionId), [first.id]);
  assert.equal((await f.runtime.identityForAgent(first)).appearanceId, 'maimai');
  const originalWorkspace = profile.workspaceId;
  await f.runtime.close();
  const resumed = new CompanionRuntime({ ctx: f.ctx, store: f.store, directory: f.directory });
  t.after(() => resumed.close()); await resumed.initialize();
  const restored = await resumed.agent(profile.id);
  assert.equal(restored.id, first.id); assert.equal((await f.store.getProfile(profile.id)).workspaceId, originalWorkspace);
  assert.equal(f.workspaces.size, 2); assert.equal(f.calls.agents.at(-1).resumed, true);
});

test('missing saved workspace bindings surface an error without inventing a replacement identity', async t => {
  const f = await fixture(t, { legacy: false, seedKakaModel: false }), profile = (await f.store.profiles())[0];
  await f.store.updateProfile(profile.id, { workspaceId: 'missing-durable-workspace' });
  const count = f.workspaces.size;
  await assert.rejects(f.runtime.agent(profile.id), code('COMPANION_WORKSPACE_NOT_FOUND'));
  assert.equal((await f.store.getProfile(profile.id)).workspaceId, 'missing-durable-workspace');
  assert.equal(f.workspaces.size, count); assert.equal(f.calls.agents.length, 0);
  await f.runtime.close();
  const reopened = new CompanionRuntime({ ctx: f.ctx, store: f.store, directory: f.directory });
  t.after(() => reopened.close());
  await assert.rejects(reopened.initialize(), code('COMPANION_WORKSPACE_NOT_FOUND'));
  assert.equal((await f.store.getProfile(profile.id)).workspaceId, 'missing-durable-workspace');
});

test('same-template instances keep Planner, Replyer, memory, media, work, notifications and stop state isolated', async t => {
  const f = await fixture(t, { legacy: false, seedKakaModel: false, withAttachments: true, learningMessages: 1,
    stream: async function* (call) {
      let input; try { input = JSON.parse(call.input.messages[0].content[0].text); } catch {}
      const text = input?.stage ? '[]' : input?.suppliedMemories ? JSON.stringify({ impressions: [], expressions: [], jargon: [], memories: [] })
        : input?.candidates ? JSON.stringify({ recordIds: input.candidates.map(item => item.recordId) }) : 'A fictional scoped reply.';
      yield { type: 'text-delta', text }; yield { type: 'finish', reason: { kind: 'stop' } };
    } });
  const alpha = (await f.store.createProfile({ requestId: 'same-template-alpha', templateId: 'kaka', name: 'Fictional Alpha',
    personalityOverride: 'Fictional Alpha persona', model: null,
    settings: { proactiveEnabled: true, quietHours: { enabled: false, start: '22:00', end: '09:00' } } })).profile;
  const beta = (await f.store.createProfile({ requestId: 'same-template-beta', templateId: 'kaka', name: 'Fictional Beta',
    personalityOverride: 'Fictional Beta persona', model: modelA })).profile;
  assert.equal(alpha.templateId, beta.templateId); assert.notEqual(alpha.id, beta.id);
  await Promise.all([f.runtime.startInstance(alpha.id), f.runtime.startInstance(beta.id)]);
  const [a, b] = await Promise.all([f.runtime.agent(alpha.id), f.runtime.agent(beta.id)]);
  const aOwner = await f.store.sessionOwner(a.id), bOwner = await f.store.sessionOwner(b.id);
  assert.notEqual(a.id, b.id); assert.notEqual(aOwner.workspaceId, bOwner.workspaceId);
  assert.equal(aOwner.companionId, alpha.id); assert.equal(bOwner.companionId, beta.id);
  const file = Buffer.from('Fictional Alpha-only file content');
  const ref = await f.ctx.attachments.saveFile({ data: file, name: 'alpha.txt' });
  const upload = { id: 'alpha-private-file', name: ref.name, mediaType: 'text/plain', bytes: ref.bytes, ref,
    url: `/droy/companions/${alpha.id}/attachments/alpha-private-file` };
  await f.store.putUpload(alpha.id, upload);
  await f.runtime.submit(alpha.id, { clientId: 'alpha-source', text: 'Fictional Alpha-only conversation', attachments: [upload] });
  await f.runtime.submit(beta.id, { clientId: 'beta-source', text: 'Fictional Beta-only conversation' });
  await Promise.all([f.runtime.flush(alpha.id), f.runtime.flush(beta.id)]);
  for (const [agent, own, other] of [[a, 'Alpha-only', 'Beta-only'], [b, 'Beta-only', 'Alpha-only']]) {
    const assembly = await f.ctx.systemPrompt.assemble({ agent, scope: agent });
    assert.ok(assembly.sections.some(section => section.text.includes(own.split('-')[0] + ' persona')));
    assert.match(JSON.stringify(assembly.contexts), new RegExp(own));
    assert.doesNotMatch(JSON.stringify(assembly.contexts), new RegExp(other));
  }
  const exec = agent => ({ agent, signal: new AbortController().signal });
  const saved = await f.ctx.tools.get('companion_remember', a).execute({ text: 'Fictional Alpha preference', sourceIds: ['alpha-source'] }, exec(a));
  assert.equal(saved.record.companionId, alpha.id);
  assert.deepEqual(saved.record.sourceRefs, [`im:${alpha.id}:alpha-source`]);
  assert.equal((await (await f.runtime.memory(beta.id)).query()).total, 0);
  await assert.rejects(f.ctx.tools.get('companion_remember', b).execute({ text: 'Borrowed fictional fact', sourceIds: ['alpha-source'] }, exec(b)), code('COMPANION_LEARNING_SOURCE_REQUIRED'));
  const read = await f.ctx.tools.get('companion_attachment', a).execute({ attachmentId: upload.id }, exec(a));
  assert.equal(read.text, file.toString()); assert.equal(read.integrityVerified, true);
  await assert.rejects(f.ctx.tools.get('companion_attachment', b).execute({ attachmentId: upload.id }, exec(b)), code('COMPANION_ATTACHMENT_SCOPE'));
  assert.equal((await f.runtime.learner.maintain(alpha.id)).learned, true);
  assert.ok(!JSON.stringify(await f.store.getState(beta.id)).includes('alpha-source'));
  f.ctx.agentDefaultModel.currentSelection = () => modelB;
  await f.runtime.reply(aOwner, { msg_id: 'alpha-source', reply_reference: 'Respond to Alpha only.' });
  assert.deepEqual(f.calls.llm.at(-1).config, modelB);
  assert.match(f.calls.llm.at(-1).input.system, /Fictional Alpha persona/);
  assert.doesNotMatch(JSON.stringify(f.calls.llm.at(-1).input), /Beta-only/);
  await f.runtime.reply(bOwner, { msg_id: 'beta-source', reply_reference: 'Respond to Beta only.' });
  assert.deepEqual(f.calls.llm.at(-1).config, modelA);
  assert.deepEqual(f.calls.notifications.map(value => [value.companionId, value.name]), [[alpha.id, alpha.name], [beta.id, beta.name]]);
  assert.equal((await f.store.getProfile(alpha.id)).model, null);
  assert.deepEqual((await f.store.getProfile(beta.id)).model, modelA);
  const [aTask, bTask] = await Promise.all([
    f.runtime.startWork(alpha.id, { title: 'Fictional Alpha work', goal: 'Alpha-only goal' }),
    f.runtime.startWork(beta.id, { title: 'Fictional Beta work', goal: 'Beta-only goal' }),
  ]);
  assert.equal((await f.store.sessionOwner(aTask.sessionId)).companionId, alpha.id);
  assert.equal((await f.store.sessionOwner(bTask.sessionId)).companionId, beta.id);
  const bDeliveries = b.deliveries.length;
  await f.runtime.cancel(alpha.id);
  await eventually(() => f.runtime.chatExecution(alpha.id).quiescent, 'Alpha chat stopped');
  assert.equal(f.runtime.chatExecution(beta.id).quiescent, false);
  assert.equal(b.status, 'running'); assert.equal(b.deliveries.length, bDeliveries);
  assert.equal(f.ctx.agents.get(aTask.sessionId).status, 'running');
  assert.equal(f.ctx.agents.get(bTask.sessionId).status, 'running');
  await f.runtime.wake(alpha.id, { kind: 'proactive' });
  assert.equal(a.deliveries.length, 2); assert.equal(b.deliveries.length, bDeliveries);
  await f.runtime.wake(beta.id, { kind: 'proactive' });
  assert.equal(b.deliveries.length, bDeliveries, 'Beta retains its own proactive permission');
  for (const profile of [alpha, beta]) {
    const current = await f.store.getProfile(profile.id);
    await assert.rejects(f.ctx.sessionController.create({ workspaceId: current.workspaceId }), code('COMPANION_WORKSPACE_MANAGED'));
    await assert.rejects(f.ctx.sessionController.prompt({ sessionId: current.chatSessionId, prompt: 'Ordinary entry' }), code('COMPANION_SESSION_MANAGED'));
  }
});

test('an inherited model stays null and an existing Planner follows each current global selection',async t=>{
  const f=await fixture(t,{seedKakaModel:false});assert.equal((await f.store.getProfile('kaka')).model,null);
  f.ctx.agentDefaultModel.currentSelection=()=>modelB;
  const agent=await f.runtime.agent('kaka');assert.deepEqual(agent.options,modelB);
  const request=async()=>{
    await f.ctx.systemPrompt.assemble({agent,scope:agent});
    return agent.ctx.waterfall('agent/request',{},()=>Promise.resolve(agent.options));
  };
  assert.deepEqual(await request(),modelB);
  assert.equal((await f.store.getProfile('kaka')).model,null);
  f.ctx.agentDefaultModel.currentSelection=()=>modelA;
  assert.deepEqual(await request(),modelA);
  assert.equal((await f.store.getProfile('kaka')).model,null);
  assert.equal(await f.runtime.agent('kaka'),agent);
});

test('existing saved references remain explicit and clearing the override resumes inheritance',async t=>{
  const f=await fixture(t),agent=await f.runtime.agent('kaka');
  f.ctx.agentDefaultModel.currentSelection=()=>modelB;
  const request=async()=>{
    await f.ctx.systemPrompt.assemble({agent,scope:agent});
    return agent.ctx.waterfall('agent/request',{},()=>Promise.resolve(agent.options));
  };
  assert.deepEqual(await request(),modelA);
  assert.deepEqual((await f.store.getProfile('kaka')).model,modelA,'A pre-existing non-null reference remains an explicit selection');
  await f.store.updateProfile('kaka',{model:null});
  assert.deepEqual(await request(),modelB);
  assert.equal((await f.store.getProfile('kaka')).model,null);
  f.ctx.agentDefaultModel.currentSelection=()=>modelA;
  assert.deepEqual(await request(),modelA);
  assert.equal(await f.runtime.agent('kaka'),agent,'Changing the source does not replace the durable chat identity');
});

test('an in-flight prepared request keeps its model snapshot and the next request resolves the new default',async t=>{
  const started=Promise.withResolvers(),release=Promise.withResolvers();t.after(()=>release.resolve());
  const f=await fixture(t,{seedKakaModel:false,stream:async function*(){started.resolve();await release.promise;
    yield {type:'text-delta',text:'Fictional stable request'};yield {type:'finish',reason:{kind:'stop'}};}});
  const selection=structuredClone(modelA);f.ctx.agentDefaultModel.currentSelection=()=>selection;
  const profile=await f.store.getProfile('kaka'),first=f.runtime.generate(profile,'Fictional system','Fictional input');
  await started.promise;Object.assign(selection,modelB);
  assert.deepEqual(f.calls.llm[0].config,modelA);
  assert.equal(f.calls.llm[0].input.provider,modelA.provider);assert.equal(f.calls.llm[0].input.model,modelA.model);
  release.resolve();await first;
  await f.runtime.generate(profile,'Fictional system','A new fictional input');
  assert.deepEqual(f.calls.llm[1].config,modelB);
  assert.equal((await f.store.getProfile('kaka')).model,null);
});

test('inherited Replyer and emoji requests resolve the current global without saving an override',async t=>{
  const f=await fixture(t,{seedKakaModel:false,stream:async function*(call){
    yield {type:'text-delta',text:call.input.system.includes('emoji_index')?JSON.stringify({emoji_index:1}):'Fictional inherited reply'};
    yield {type:'finish',reason:{kind:'stop'}};
  }}),agent=await f.runtime.agent('kaka'),owner=await f.store.sessionOwner(agent.id);
  await f.store.appendMessage('kaka',{id:'inherited-reply-source',role:'user',text:'Fictional inherited source'});
  await f.runtime.reply(owner,{msg_id:'inherited-reply-source',reply_reference:'Fictional answer'});
  assert.deepEqual(f.calls.llm.at(-1).config,modelA);
  f.ctx.agentDefaultModel.currentSelection=()=>modelB;
  await f.runtime.sendEmoji(owner,{msg_id:'inherited-reply-source'});
  assert.deepEqual(f.calls.llm.at(-1).config,modelB);
  assert.equal((await f.store.getProfile('kaka')).model,null);
});

test('inherited learning and context compaction use current models while raw memory guards remain authoritative',async t=>{
  let f;
  f=await fixture(t,{seedKakaModel:false,learningMessages:1,stream:async function*(call){
    const input=JSON.parse(call.input.messages[0].content[0].text);
    const text=input.stage?'[]':input.suppliedMemories?JSON.stringify({impressions:[],expressions:[],jargon:[],memories:[]}):'A fictional compacted context';
    if(input.suppliedMemories)f.ctx.agentDefaultModel.currentSelection=()=>modelB;
    yield {type:'text-delta',text};yield {type:'finish',reason:{kind:'stop'}};
  }});
  await f.store.appendMessage('kaka',{id:'inherited-learning-source',role:'user',text:'Fictional inherited learning evidence'});
  const learned=await f.runtime.learner.maintain('kaka');
  assert.equal(learned.learned,true);
  assert.deepEqual(f.calls.llm[0].config,modelA);
  assert.ok(f.calls.llm.slice(1).every(call=>call.config.provider===modelB.provider));
  await f.store.updateProfile('kaka',{settings:{memoryEnabled:false}});
  Object.assign(f.runtime.learner.options,{compactAfter:4,retainMessages:1,batchMessages:8});
  for(let index=0;index<4;index++)await f.store.appendMessage('kaka',{id:'inherited-compaction-'+index,role:'user',text:'Fictional context '+index});
  const context=await f.runtime.learner.contextMessages('kaka');
  assert.equal(context.state.summary,'A fictional compacted context');
  assert.deepEqual(f.calls.llm.at(-1).config,modelB);
  assert.equal((await f.store.getProfile('kaka')).model,null);
});

test('proactive inherited companions can wake while a missing model still rejects explicit sending',async t=>{
  const f=await fixture(t,{seedKakaModel:false});
  f.ctx.agentDefaultModel.currentSelection=()=>undefined;
  assert.equal((await f.runtime.learner.maintain('kaka')).skipped,'model_required');
  await assert.rejects(f.runtime.submit('kaka',{clientId:'no-effective-model',text:'Fictional missing-model input'}),code('COMPANION_MODEL_REQUIRED'));
  assert.equal((await f.store.listMessages('kaka')).messages.length,0);
  f.ctx.agentDefaultModel.currentSelection=()=>modelB;
  await f.store.updateProfile('kaka',{settings:{proactiveEnabled:true,quietHours:{enabled:false,start:'22:00',end:'09:00'}}});
  await f.store.updateState('kaka',{lastUserAt:new Date(0).toISOString()});
  f.runtime.scheduleProactive('kaka',new Date().toISOString());
  await eventually(()=>f.calls.agents.some(call=>call.agent.deliveries.length),'inherited proactive dispatch');
  assert.deepEqual(f.calls.agents[0].agent.options,modelB);
  assert.equal((await f.store.getProfile('kaka')).model,null);
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

test('chat cancellation confirms a pure queue and a scheduled wait without creating an Agent',async t=>{
  const f=await fixture(t);
  assert.deepEqual(f.runtime.chatExecution('kaka'),{quiescent:true,cancelRequested:false});
  await f.runtime.submit('kaka',{clientId:'cancel-queued-only',text:'Fictional queued input'});
  assert.equal(f.runtime.chatExecution('kaka').quiescent,false);
  assert.deepEqual(await f.runtime.cancel('kaka'),{cancelRequested:true});
  assert.deepEqual(f.runtime.chatExecution('kaka'),{quiescent:true,cancelRequested:true});
  assert.deepEqual((await f.store.getState('kaka')).pendingMessageIds,[]);
  const saved={waitingUntil:new Date(Date.now()+60000).toISOString(),pendingIntent:{kind:'wait',sessionId:'unopened-chat'}};
  await f.store.updateState('kaka',saved);f.runtime.scheduleWait('kaka',saved);
  assert.equal(f.runtime.chatExecution('kaka').quiescent,false);
  await f.runtime.cancel('kaka');
  assert.deepEqual(f.runtime.chatExecution('kaka'),{quiescent:true,cancelRequested:true});
  assert.equal((await f.store.getState('kaka')).waitingUntil,null);
  assert.equal(f.calls.agents.length,0);
  await f.runtime.submit('kaka',{clientId:'after-cancel-new-input',text:'A new fictional input'});
  assert.equal(f.runtime.chatExecution('kaka').cancelRequested,false);
});

test('a send still persisting at cancellation prevents an unsupported quiescent observation',async t=>{
  const started=Promise.withResolvers(),release=Promise.withResolvers();t.after(()=>release.resolve());
  const f=await fixture(t),append=f.store.appendMessage.bind(f.store);
  f.store.appendMessage=async(id,input)=>{started.resolve();await release.promise;return append(id,input);};
  const sending=f.runtime.submit('kaka',{clientId:'persisting-send-source',text:'Fictional input being persisted'});
  await started.promise;await f.runtime.cancel('kaka');
  assert.deepEqual(f.runtime.chatExecution('kaka'),{quiescent:false,cancelRequested:true});
  release.resolve();await sending;
  assert.equal(f.runtime.chatExecution('kaka').quiescent,false);
  await f.runtime.cancel('kaka');
  assert.deepEqual(f.runtime.chatExecution('kaka'),{quiescent:true,cancelRequested:true});
  assert.equal(f.calls.agents.length,0);
});

test('idle phase and Agent status do not confirm cancellation until public whenIdle resolves',async t=>{
  const f=await fixture(t),agent=await f.runtime.agent('kaka'),gate=Promise.withResolvers(),events=[];
  await eventually(()=>f.runtime.chatExecution('kaka').quiescent,'initial public idle observation');
  agent.whenIdle=()=>gate.promise;
  const stop=f.runtime.subscribe(event=>events.push(event));
  assert.deepEqual(await f.runtime.cancel('kaka'),{cancelRequested:true});
  assert.equal(agent.status,'idle');assert.equal(f.runtime.state('kaka').phase,'idle');
  assert.deepEqual(f.runtime.chatExecution('kaka'),{quiescent:false,cancelRequested:true});
  assert.ok(events.every(event=>!event.status.chatExecution.quiescent));
  gate.resolve();
  await eventually(()=>f.runtime.chatExecution('kaka').quiescent,'public activity retirement');
  stop();assert.deepEqual(events.at(-1).status.chatExecution,{quiescent:true,cancelRequested:true});
});

test('a rejected cancel remains unconfirmed while its real Agent is running',async t=>{
  const f=await fixture(t);
  await f.runtime.submit('kaka',{clientId:'rejected-cancel-source',text:'Fictional active input'});await f.runtime.flush('kaka');
  const agent=await f.runtime.agent('kaka'),cancel=agent.cancel.bind(agent);
  agent.cancel=()=>{throw Object.assign(new Error('Fictional cancellation rejection'),{code:'FIXTURE_CANCEL_REJECTED'});};
  await assert.rejects(f.runtime.cancel('kaka'),code('FIXTURE_CANCEL_REJECTED'));
  assert.equal(agent.status,'running');
  assert.deepEqual(f.runtime.chatExecution('kaka'),{quiescent:false,cancelRequested:true});
  agent.cancel=cancel;cancel({kind:'user'});
  await eventually(()=>f.runtime.chatExecution('kaka').quiescent,'late retirement after refused cancellation');
});

test('a failing public idle observation cannot become a successful stop confirmation',async t=>{
  const f=await fixture(t),agent=await f.runtime.agent('kaka');
  await eventually(()=>f.runtime.chatExecution('kaka').quiescent,'initial public idle observation');
  const whenIdle=agent.whenIdle;
  agent.whenIdle=async()=>{throw new Error('Fictional retirement observation failure');};
  await f.runtime.cancel('kaka');
  await eventually(()=>f.runtime.state('kaka').chatStopUnconfirmed,'failed public observation');
  assert.deepEqual(f.runtime.chatExecution('kaka'),{quiescent:false,cancelRequested:true});
  agent.whenIdle=whenIdle;
  await f.runtime.cancel('kaka');
  await eventually(()=>f.runtime.chatExecution('kaka').quiescent,'a subsequent valid observation');
});

test('an aborted Replyer that settles late keeps cancellation pending even with an idle Agent',async t=>{
  const started=Promise.withResolvers(),release=Promise.withResolvers();let signal;
  const f=await fixture(t,{stream:async function*(call){signal=call.input.signal;started.resolve();await release.promise;
    yield {type:'text-delta',text:'A fictional late reply'};yield {type:'finish',reason:{kind:'stop'}};}});
  const agent=await f.runtime.agent('kaka'),owner=await f.store.sessionOwner(agent.id);
  await f.store.appendMessage('kaka',{id:'late-reply-source',role:'user',text:'Fictional source'});
  const reply=f.runtime.reply(owner,{msg_id:'late-reply-source',reply_reference:'Fictional answer'}).then(value=>({value}),error=>({error}));
  await started.promise;
  await f.runtime.cancel('kaka');
  assert.equal(agent.status,'idle');assert.equal(signal.aborted,true);
  assert.deepEqual(f.runtime.status('kaka').chatExecution,{quiescent:false,cancelRequested:true});
  release.resolve();assert.ok((await reply).error);
  await eventually(()=>f.runtime.chatExecution('kaka').quiescent,'aborted Replyer promise settlement');
  assert.equal((await f.store.listMessages('kaka')).messages.some(message=>message.role==='assistant'),false);
});

test('cancellation observes an unopened recovery and prevents its saved reply from being published later',async t=>{
  const f=await fixture(t),started=Promise.withResolvers(),release=Promise.withResolvers();
  await f.store.appendMessage('kaka',{id:'recovery-cancel-source',role:'user',text:'Fictional recovery source',status:'received'});
  await prepareReply({store:f.store,companionId:'kaka',sourceMessageIds:['recovery-cancel-source'],parts:['A saved fictional reply']});
  const getState=f.store.getState.bind(f.store);let blocked=false;
  f.store.getState=async id=>{const saved=await getState(id);if(!blocked){blocked=true;started.resolve();await release.promise;}return saved;};
  const recovery=f.runtime.recoverReply('kaka');await started.promise;
  await f.runtime.cancel('kaka');
  assert.deepEqual(f.runtime.chatExecution('kaka'),{quiescent:false,cancelRequested:true});
  release.resolve();await recovery;
  assert.deepEqual(f.runtime.chatExecution('kaka'),{quiescent:true,cancelRequested:true});
  assert.equal((await f.store.listMessages('kaka')).messages.some(message=>message.role==='assistant'),false);
  assert.equal(f.calls.agents.length,0);
});

test('a chat Agent that finishes opening after cancellation never receives the cancelled batch',async t=>{
  const f=await fixture(t),started=Promise.withResolvers(),release=Promise.withResolvers();
  const create=f.ctx.agents.create.bind(f.ctx.agents);
  f.ctx.agents.create=async input=>{started.resolve();await release.promise;return create(input);};
  await f.runtime.submit('kaka',{clientId:'late-opening-source',text:'Fictional input cancelled during opening'});
  const flushing=f.runtime.flush('kaka');await started.promise;
  await f.runtime.cancel('kaka');
  assert.deepEqual(f.runtime.chatExecution('kaka'),{quiescent:false,cancelRequested:true});
  release.resolve();await flushing;
  await eventually(()=>f.runtime.chatExecution('kaka').quiescent,'late opening settles without a driver');
  assert.deepEqual(f.calls.agents[0].agent.deliveries,[]);
  assert.deepEqual(f.runtime.state('kaka').pending,[]);
  assert.deepEqual((await f.store.getState('kaka')).pendingMessageIds,[]);
});

test('a rejected late opening cannot put a cancelled batch back into the pending queue',async t=>{
  const f=await fixture(t),started=Promise.withResolvers(),release=Promise.withResolvers();
  f.ctx.agents.create=async()=>{started.resolve();await release.promise;throw new Error('Fictional late opening rejection');};
  await f.runtime.submit('kaka',{clientId:'rejected-opening-source',text:'Fictional input cancelled during opening'});
  const flushing=f.runtime.flush('kaka').then(value=>({value}),error=>({error}));await started.promise;
  await f.runtime.cancel('kaka');assert.equal(f.runtime.chatExecution('kaka').quiescent,false);
  release.resolve();assert.ok((await flushing).error);
  assert.deepEqual(f.runtime.chatExecution('kaka'),{quiescent:true,cancelRequested:true});
  assert.deepEqual(f.runtime.state('kaka').pending,[]);
});

test('chat stop confirmation does not claim that an independent work task has stopped',async t=>{
  const f=await fixture(t),task=await f.runtime.startWork('kaka',{title:'Independent fictional work',goal:'Continue independent work'});
  const worker=f.ctx.agents.get(task.sessionId);
  assert.equal(worker.status,'running');
  await f.runtime.cancel('kaka');
  assert.deepEqual(f.runtime.chatExecution('kaka'),{quiescent:true,cancelRequested:true});
  assert.equal(worker.status,'running');
  assert.equal((await f.store.getMessage('kaka',task.taskId)).task.state,'running');
});

test('authenticated list, message snapshots and SSE distinguish cancellation acknowledgement from quiescence',{timeout:8000},async t=>{
  const gate=Promise.withResolvers();t.after(()=>gate.resolve());
  const f=await fixture(t),agent=await f.runtime.agent('kaka');
  await eventually(()=>f.runtime.chatExecution('kaka').quiescent,'initial public idle observation');
  agent.whenIdle=()=>gate.promise;
  const token='fictional-companion-stop-observation-token',previousToken=process.env.DROY_WEB_TOKEN;
  process.env.DROY_WEB_TOKEN=token;
  const handler=createCompanionHandler({ctx:f.ctx,runtime:f.runtime,store:f.store}),abort=new AbortController();
  const server=createServer((req,res)=>handler(req,res));
  await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));
  t.after(async()=>{
    abort.abort();gate.resolve();server.closeAllConnections();await new Promise(resolve=>server.close(resolve));
    if(previousToken===undefined)delete process.env.DROY_WEB_TOKEN;else process.env.DROY_WEB_TOKEN=previousToken;
  });
  const base='http://127.0.0.1:'+server.address().port+'/droy/companions',headers={cookie:'droy_session='+token,'content-type':'application/json'};
  const api=async(path='',options={})=>{const response=await fetch(base+path,{headers,...options}),value=await response.json();
    assert.equal(response.status,200,JSON.stringify(value));return value;};
  assert.equal((await fetch(base+'/kaka/messages')).status,401);
  const events=await fetch(base+'/events',{headers,signal:AbortSignal.any([abort.signal,AbortSignal.timeout(5000)])});
  const reader=events.body.getReader();await reader.read();
  let buffered='';const decoder=new TextDecoder();
  const nextStatus=async quiescent=>{
    for(;;){
      let end;
      while((end=buffered.indexOf('\n\n'))>=0){
        const frame=buffered.slice(0,end);buffered=buffered.slice(end+2);
        if(frame.startsWith('data: ')){
          const event=JSON.parse(frame.slice(6));
          if(event.companionId==='kaka'&&event.status?.chatExecution.quiescent===quiescent)return event.status;
        }
      }
      const part=await reader.read();assert.equal(part.done,false);buffered+=decoder.decode(part.value,{stream:true});
    }
  };
  assert.deepEqual(await api('/kaka/cancel',{method:'POST',body:'{}'}),{cancelRequested:true});
  assert.deepEqual((await nextStatus(false)).chatExecution,{quiescent:false,cancelRequested:true});
  const pending=await api('/kaka/messages'),listed=await api();
  assert.equal(pending.status.phase,'idle');
  assert.deepEqual(pending.status.chatExecution,{quiescent:false,cancelRequested:true});
  assert.deepEqual(listed.companions.find(profile=>profile.id==='kaka').status.chatExecution,pending.status.chatExecution);
  gate.resolve();
  assert.deepEqual((await nextStatus(true)).chatExecution,{quiescent:true,cancelRequested:true});
  assert.deepEqual((await api('/kaka/messages')).status.chatExecution,{quiescent:true,cancelRequested:true});
  abort.abort();
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

test('an unrecognized voice is admitted with empty text and remains playable',async t=>{
  const f=await fixture(t);
  await f.runtime.submit('kaka',{clientId:'voice-message',text:'',voice:{attachmentId:'fictional-audio',url:'/fictional-audio',transcriptionState:'failed'}});
  assert.equal(f.runtime.state('kaka').pending.length,1);
  assert.equal((await f.store.getMessage('kaka','voice-message')).text,'');
  assert.equal((await f.store.getMessage('kaka','voice-message')).voice.transcriptionState,'failed');
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

test('deleting a locally rejected message does not need memory or change another message', async t => {
  const f = await fixture(t);
  await f.store.appendMessage('kaka', { id: 'kept-message', role: 'user', text: 'Keep this fictional message.' });
  f.runtime.memory = () => { throw new Error('A missing message has no memory to clean'); };
  assert.deepEqual(await f.runtime.removeMessage('kaka', 'never-admitted'), { removed: true });
  assert.equal((await f.store.getMessage('kaka', 'kept-message')).text, 'Keep this fictional message.');
});

test('reply transport headers never enter delivered text or notifications',async t=>{
  const raw='[msg_id=12345678-1234-1234-1234-123456789abc 2026-10-30T02:21:58.247Z reply_target=87654321-4321-4321-4321-cba987654321]\nFictional reply';
  const f=await fixture(t,{stream:async function*(){yield {type:'text-delta',text:raw};yield {type:'finish',reason:{kind:'stop'}};}});
  await f.runtime.submit('kaka',{clientId:'header-source',text:'Fictional question'});
  await f.runtime.flush('kaka');
  const agent=await f.runtime.agent('kaka');
  const result=await f.runtime.reply(await f.store.sessionOwner(agent.id),{msg_id:'header-source',reply_reference:'Reply naturally.'});
  assert.equal(result.text,'Fictional reply');
  assert.equal((await f.store.getMessage('kaka',result.messageIds[0])).text,'Fictional reply');
});
