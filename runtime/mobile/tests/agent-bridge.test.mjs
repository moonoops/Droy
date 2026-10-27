import { toolDefinitions } from '../catalog.mjs';
// Contract integration only: real DSH loop and tools, MOCK model and Android HTTP
// executor, in-memory sessions. This is not model quality or real-device evidence.
import test from 'node:test';
import assert from 'node:assert/strict';
import { createRequire, registerHooks } from 'node:module';
import { pathToFileURL } from 'node:url';
import { createServer } from 'node:http';
import { setTimeout as delay } from 'node:timers/promises';
import { companionState } from './fixtures/companion-state.mjs';
const installed = createRequire(new URL('../../dsh/package.json', import.meta.url));
const hooks = registerHooks({ resolve(specifier, context, next) {
  if (specifier === 'zod' || specifier.startsWith('@deepseek-ai/')) return {url:pathToFileURL(installed.resolve(specifier)).href,shortCircuit:true};
  return next(specifier,context);
} });
const { Context }=await import('@deepseek-ai/cordis');
const { default: LlmRuntime, LlmAdapter, createUserMessage }=await import('@deepseek-ai/dsh-llm');
const { default: SessionStore }=await import('@deepseek-ai/dsh-session');
const { default: SessionProjectionRegistry }=await import('@deepseek-ai/dsh-session-projection');
const { default: SystemPrompt }=await import('@deepseek-ai/dsh-system-prompt');
const { default: ToolRuntime, defineTool }=await import('@deepseek-ai/dsh-tools');
const { default: AgentRegistry }=await import('@deepseek-ai/dsh-agent');
const { default: AgentLoop }=await import('@deepseek-ai/dsh-agent-loop');
const { default: UserQuestions }=await import('@deepseek-ai/dsh-user-questions');
const { default: UserApproval }=await import('@deepseek-ai/dsh-user-approval');
const mobile=await import('../plugin.mjs');
const pluginPolicy=await import('../plugin-policy.mjs');
const { TOOL_GROUPS }=await import('../tool-groups.mjs');
const {persona}=await import('../profile.mjs');
hooks.deregister();
const TOKEN='fictional-integration-bridge-token-123456789';
const tool=(id,name,args={})=>[
  {type:'block-start',index:0,blockType:'tool-call'},
  {type:'tool-call-delta',index:0,id,name,argumentsDelta:JSON.stringify(args)},
  {type:'block-end',index:0,block:{type:'tool-call',id,name,arguments:JSON.stringify(args)}},
  {type:'finish',reason:{kind:'tool-calls'}},
];
const text=message=>[
  {type:'block-start',index:0,blockType:'text'},
  {type:'text-delta',index:0,text:message},
  {type:'block-end',index:0,block:{type:'text',text:message}},
  {type:'finish',reason:{kind:'stop'}},
];
class ScriptedMockModel extends LlmAdapter {
  requests=[];
  constructor(script){super();this.script=[...script];}
  async resolveModel(provider,model){return {provider,id:model,name:'MOCK model — no external inference',inputModalities:['text']};}
  async *stream(options){
    this.requests.push(options);
    const step=this.script.shift(); if(!step)throw new Error('Mock model script exhausted');
    const chunks=typeof step==='function'?await step(options):step;
    for(const chunk of chunks){options.signal?.throwIfAborted();yield chunk;}
  }
}
function deferred(){let resolve;const promise=new Promise(r=>{resolve=r;});return {promise,resolve};}
async function eventually(predicate,label){const deadline=Date.now()+3000;while(!predicate()){if(Date.now()>deadline)throw new Error(`Timed out: ${label}`);await delay(10);}}
async function harness(script,implementation,options={}){
  const requests=[],progressRequests=[],routes=new Map();const sockets=new Set();
  const bridge=createServer(async(req,res)=>{
    try{
      if(routes.has(req.url)){await routes.get(req.url)(req,res);return;}
      assert.equal(req.headers.authorization,`Bearer ${TOKEN}`);
      assert.equal(req.headers['content-type'],'application/json; charset=utf-8');
      let body='';for await(const data of req)body+=data;
      const call={path:req.url,...JSON.parse(body)};
      if(call.path==='/companion'){
        res.writeHead(200,{'content-type':'application/json'});res.end(JSON.stringify({ok:true,result:companionState}));return;
      }
      if(call.path==='/progress'){
        progressRequests.push(call);
        res.writeHead(200,{'content-type':'application/json'});res.end(JSON.stringify({ok:true,result:{}}));return;
      }
      requests.push(call);
      const result=await implementation(call,requests);
      if(!res.destroyed){res.writeHead(200,{'content-type':'application/json'});res.end(JSON.stringify(result??{ok:true,result:{}}));}
    }catch(error){if(!res.destroyed){res.writeHead(500,{'content-type':'application/json'});res.end(JSON.stringify({ok:false,error:{code:'MOCK_FAILURE',message:error.message}}));}}
  });
  bridge.on('connection',socket=>{sockets.add(socket);socket.on('close',()=>sockets.delete(socket));});
  await new Promise(r=>bridge.listen(0,'127.0.0.1',r));
  process.env.DROY_BRIDGE_PORT=String(bridge.address().port);process.env.DROY_BRIDGE_TOKEN=TOKEN;process.env.DROY_WEB_TOKEN=TOKEN;
  const ctx=new Context();
  await ctx.plugin(LlmRuntime);await ctx.plugin(SessionStore);await ctx.plugin(SessionProjectionRegistry);
  await ctx.plugin(SystemPrompt,{personaPrefix:persona});await ctx.plugin(ToolRuntime,{mode:'native'});
  await ctx.plugin(AgentRegistry);await ctx.plugin(AgentLoop,{agents:[]});
  // Non-task UI surfaces are explicit fixture providers; agent execution is real.
  ctx.provide('webServer',{register(route){routes.set(route.path,route.handler);return()=>routes.delete(route.path);}});
  ctx.provide('settings',{describe:()=>[{ns:'locale',value:{preference:'zh'}}],async mutate(){throw new Error('Settings are outside this contract fixture');}});
  ctx.provide('attachments',{async saveImage(){throw new Error('Text-only fixture must never save screenshots');}});
  await ctx.plugin(UserQuestions);await ctx.plugin(UserApproval,{policy:options.approvalPolicy??'ask'});
  await ctx.plugin(pluginPolicy);
  if (options.privacy) ctx.provide('droyPrivacy', options.privacy);
  await ctx.plugin(mobile);
  const adapter=new ScriptedMockModel(script);ctx.llm.registerAdapter(['mock-android-contract'],adapter);
  const errors=[];ctx.on('agent/error',event=>errors.push(event.error));
  const handle=await ctx.agents.create({sessionId:`mock-session-${Math.random().toString(16).slice(2)}`,agentOptions:{provider:'mock-android-contract',model:'scripted-text'}});
  const agent=handle.agent;
  // These fixtures exercise expanded capability contracts; startup-context separately tests the compact default.
  for (const group of Object.keys(TOOL_GROUPS))
    await ctx.tools.get('droy_load_tools',agent).execute({group},{agent});
  return {ctx,agent,adapter,requests,progressRequests,errors,
    action(data,headers={Cookie:`droy_session=${TOKEN}`}){return fetch(`http://127.0.0.1:${bridge.address().port}/droy/pet/action`,{method:'POST',headers:{'Content-Type':'application/json',...headers},body:JSON.stringify(data)});},
    async run(prompt='仅测试：读取页面并填写指定文字'){agent.followup(createUserMessage({content:[{type:'text',text:prompt}],source:{kind:'user'}}));await agent.whenIdle();},
    async close(){await ctx.fiber.dispose();for(const socket of sockets)socket.destroy();await new Promise(r=>bridge.close(r));},
  };
}
function toolResults(request){return request.messages.filter(message=>message.role==='tool');}

test('denied device processing authorization prevents a physical command from reaching the native bridge', async t => {
  let checked = false;
  const fixture = await harness([
    tool('denied-infrared', 'android_infrared', {operation: 'transmit', carrierHz: 38000, patternMicros: [100, 100]}),
    options => { assert.match(JSON.stringify(toolResults(options)), /PRIVACY_ENDPOINT_NOT_AUTHORIZED/); return text('处理授权不足，未发射。'); },
  ], async call => {
    assert.notEqual(call.path, '/tool', 'A denied processing route must prevent the physical operation');
    return {ok: true, result: {}};
  }, {privacy: {status: async () => ({active: true}), routeForAgent: async () => ({}),
    beforeDisclosure: async () => { checked = true; throw new Error('PRIVACY_ENDPOINT_NOT_AUTHORIZED'); }}});
  t.after(() => fixture.close());
  await fixture.run('只验证拒绝授权路径，不发射信号');
  assert.equal(checked, true);
  assert.equal(fixture.requests.filter(call => call.path === '/tool').length, 0);
});

test('production mobile plugin keeps full Android results without custom history replacements', async t => {
  let observation = 0;
  const fixture = await harness([
    tool('observe-original-1', 'android_observe'), tool('observe-original-2', 'android_observe'), text('两次观察已保留。'),
  ], async call => {
    if (call.path === '/task/end') return { ok: true, result: {} };
    const number = ++observation;
    return { ok: true, result: { status: 'observed', packageName: 'fictional.shop', snapshotId: `fixture-${number}`,
      nodes: [{ nodeId: `n${number}`, text: '虚构商品', description: '', className: 'android.widget.Button',
        bounds: { left: 0, top: 0, right: 100, bottom: 100 }, clickable: true, enabled: true, supportedActions: ['click'] }],
      text: '虚构商品', truncated: false } };
  });
  t.after(() => fixture.close());
  await fixture.run('在本地读两次虚构页面');
  assert.deepEqual(fixture.errors, []);
  const shown = toolResults(fixture.adapter.requests.at(-1)).map(message => JSON.parse(message.content[0].text));
  assert.deepEqual(shown.map(value => value.nodes[0].nodeId), ['n1', 'n2']);
  const events = fixture.agent.session.snapshotEvents();
  assert.equal(events.filter(event => event.type === 'tool/result').length, 2);
  assert.ok(!events.some(event => event.type === 'compaction/prune'));
});

test('one model decision submits a short search sequence through the authorized native bridge',async t=>{
  const args={snapshotId:'observed-search',packageName:'com.example.fixture',steps:[
    {action:'input_text',target:{nodeId:'n1'},text:'练习面包'},
    {action:'tap',target:{text:'搜索'},afterCondition:{text:'搜索结果'}},
  ]};
  const f=await harness([tool('search-sequence','android_sequence',args),text('已到搜索结果。')],async call=>{
    if(call.path==='/task/end')return {ok:true,result:{}};
    assert.equal(call.action,'sequence');
    assert.deepEqual(call.args,args);
    assert.equal(call.budgetMs,30000);
    return {ok:true,result:{status:'completed',completedSteps:2,steps:[{action:'input_text',dispatched:true},{action:'tap',dispatched:true}],
      afterObservation:{status:'observed',packageName:'com.example.fixture',snapshotId:'results',nodes:[]}}};
  });
  t.after(()=>f.close());await f.run('搜索练习面包');
  assert.equal(f.requests.filter(call=>call.path==='/tool').length,1);
  assert.equal(f.adapter.requests.length,2);
  assert.match(JSON.stringify(toolResults(f.adapter.requests[1])),/completedSteps/);
  assert.deepEqual(f.errors,[]);
});

test('partial sequence and unknown submission are returned to the model without replay',async t=>{
  const f=await harness([
    tool('partial-sequence','android_sequence',{snapshotId:'current',packageName:'com.example.fixture',steps:[
      {action:'tap',target:{nodeId:'n1'}},{action:'tap',target:{text:'发送'},terminal:true},
    ]}),text('已派发，结果需要核实。'),
  ],async call=>call.path==='/task/end'?{ok:true,result:{}}:{ok:true,result:{status:'partial',completedSteps:1,
    failedStep:1,dispatched:true,outcomeUnknown:true,replayedAction:false}});
  t.after(()=>f.close());await f.run('执行已授权的练习任务');
  assert.equal(f.requests.filter(call=>call.path==='/tool').length,1);
  assert.match(JSON.stringify(toolResults(f.adapter.requests[1])),/outcomeUnknown/);
  assert.deepEqual(f.errors,[]);
});

test('audio capture survives a normal real-agent idle and native transcription uses its own job endpoint', async t => {
  let recording = false;
  const fixture = await harness([
    tool('audio-start', 'android_audio_start', { source: 'microphone', saveFile: false }), text('录音已开始。'),
    tool('audio-status', 'android_audio_status', { captureId: 'capture-contract' }),
    tool('audio-transcribe', 'audio_transcribe', { captureId: 'capture-contract' }), text('已开启实时转写。'),
  ], async call => {
    if (call.path === '/task/end') return { ok: true, result: {} };
    if (call.path === '/audio') {
      assert.equal(call.operation, 'transcribe');
      assert.equal(call.captureId, 'capture-contract');
      assert.equal(call.taskId, fixture.agent.id);
      return { ok: true, result: { transcriptId: 'transcript-contract', state: 'running' } };
    }
    assert.equal(call.path, '/tool');
    if (call.action === 'audio_start') recording = true;
    assert.equal(recording, true);
    return { ok: true, result: { captureId: 'capture-contract', state: 'recording', fileReady: false } };
  });
  t.after(() => fixture.close());
  await fixture.run('开始录音，不保存文件');
  await eventually(() => fixture.requests.some(call => call.path === '/task/end'), 'first audio turn release');
  assert.equal(recording, true);
  await fixture.run('查询刚才录音，并开启实时转写');
  assert.deepEqual(fixture.requests.filter(call => call.path === '/tool').map(call => call.action), ['audio_start', 'audio_status']);
  assert.equal(fixture.requests.filter(call => call.path === '/audio').length, 1);
  assert.equal(fixture.errors.length, 0);
});

test('explicit cancellation of a real DSH turn stops only the capture created by that turn', async t => {
  const thinking = deferred(); let recording = false;
  const fixture = await harness([
    tool('new-audio', 'android_audio_start', { source: 'microphone', saveFile: false }),
    options => new Promise((_, reject) => {
      thinking.resolve(); options.signal.addEventListener('abort', () => reject(options.signal.reason), { once: true });
    }),
  ], async call => {
    if (call.path === '/task/end') { assert.equal(recording, false); return { ok: true, result: {} }; }
    assert.equal(call.path, '/tool');
    if (call.action === 'audio_start') recording = true;
    if (call.action === 'audio_stop') { assert.equal(call.args.captureId, 'turn-capture'); recording = false; }
    return { ok: true, result: { captureId: 'turn-capture', state: recording ? 'recording' : 'completed', fileReady: false } };
  });
  t.after(() => fixture.close());
  const running = fixture.run('开始录音，然后继续处理任务');
  await thinking.promise; fixture.agent.cancel({ kind: 'user' }); await running;
  await eventually(() => fixture.requests.some(call => call.path === '/task/end'), 'cancelled audio release');
  assert.deepEqual(fixture.requests.filter(call => call.path === '/tool').map(call => call.action), ['audio_start', 'audio_stop']);
  assert.equal(recording, false);
});

test('cancelling a new transcription turn preserves the earlier persistent recording', async t => {
  const thinking = deferred(); let recording = false, jobStopped = false;
  const fixture = await harness([
    tool('old-audio', 'android_audio_start', { source: 'microphone', saveFile: false }), text('录音正在继续。'),
    tool('new-transcript', 'audio_transcribe', { captureId: 'earlier-capture' }),
    options => new Promise((_, reject) => {
      thinking.resolve(); options.signal.addEventListener('abort', () => reject(options.signal.reason), { once: true });
    }),
  ], async call => {
    if (call.path === '/task/end') return { ok: true, result: {} };
    if (call.path === '/audio') {
      if (call.operation === 'stop_transcription') jobStopped = true;
      return { ok: true, result: { transcriptId: 'new-job', state: jobStopped ? 'cancelled' : 'running', finished: jobStopped } };
    }
    assert.equal(call.action, 'audio_start', 'the earlier capture must not be stopped with the new turn');
    recording = true;
    return { ok: true, result: { captureId: 'earlier-capture', state: 'recording', fileReady: false } };
  });
  t.after(() => fixture.close());
  await fixture.run('开始录音');
  const running = fixture.run('为刚才录音开启转写，然后继续处理任务');
  await thinking.promise; fixture.agent.cancel({ kind: 'user' }); await running;
  await eventually(() => jobStopped, 'new transcription cancelled');
  assert.equal(recording, true);
  assert.deepEqual(fixture.requests.filter(call => call.path === '/tool').map(call => call.action), ['audio_start']);
});

test('repeated model tool-call ids in later turns do not reuse an earlier native audio start', async t => {
  const starts = new Map();
  const fixture = await harness([
    tool('call_0', 'android_audio_start', { source: 'microphone', saveFile: false }), text('第一段录音已结束。'),
    tool('call_0', 'android_audio_start', { source: 'microphone', saveFile: false }), text('第二段录音已结束。'),
  ], async call => {
    if (call.path === '/task/end') return { ok: true, result: {} };
    const key = `${call.taskId}:${call.callId}`;
    if (!starts.has(key)) starts.set(key, `capture-${starts.size + 1}`);
    return { ok: true, result: { captureId: starts.get(key), state: 'completed', fileReady: false } };
  });
  t.after(() => fixture.close());
  await fixture.run('录一小段'); await fixture.run('再录一小段');
  assert.equal(starts.size, 2);
  assert.notEqual(fixture.requests.filter(call => call.path === '/tool')[0].callId,
    fixture.requests.filter(call => call.path === '/tool')[1].callId);
});

test('conversation rules are actually persisted through the native endpoint in the real agent loop', async () => {
  const rule = { packageName: 'example.shop', instruction: '只有闪送送达时提醒，其他收纳', entries: [], defaultAction: 'analyze' };
  const fixture = await harness([
    tool('notification-current', 'droy_notification_rules', { operation: 'get' }),
    tool('notification-save', 'droy_notification_rules', { operation: 'upsertRule', rule }),
    options => {
      assert.match(JSON.stringify(toolResults(options)), /saved-rule/);
      return text('已设置：只有闪送送达时提醒。');
    },
  ], async call => {
    if (call.path === '/notifications') return { ok: true, result: call.operation === 'get'
      ? { enabled: true, rules: [], apps: [{ packageName: 'example.shop', label: '测试商城' }] }
      : { enabled: true, rules: [{ ...call.rule, id: 'saved-rule' }] } };
    return { ok: true, result: {} };
  });
  try {
    await fixture.run('测试：淘宝只有闪送送达时提醒，其他收纳');
    assert.deepEqual(fixture.requests.filter(call => call.path === '/notifications').map(({ path, ...body }) => body),
      [{ operation: 'get' }, { operation: 'upsertRule', rule }]);
    assert.equal(fixture.errors.length, 0);
  } finally { await fixture.close(); }
});

test('editing an app policy persists one complete policy containing earlier and new user requirements', async () => {
  const original = { id: 'existing-policy', packageName: 'example.sms', instruction: '广告短信清除，验证码保留',
    entries: [{ containsAny: ['退订回T'], action: 'clear' }], defaultAction: 'analyze' };
  const updated = { ...original, instruction: '广告短信清除，验证码保留；快递取件短信提醒，其他短信按重要性分析',
    entries: [...original.entries, { containsAll: ['取件码'], action: 'notify' }] };
  let policies = [original];
  const fixture = await harness([
    tool('policy-before', 'droy_notification_rules', { operation: 'get' }),
    options => {
      assert.match(JSON.stringify(toolResults(options)), /广告短信清除，验证码保留/);
      return tool('policy-update', 'droy_notification_rules', { operation: 'upsertRule', rule: updated });
    },
    text('已设置：快递取件短信提醒，广告与验证码按原规则处理。'),
  ], async call => {
    if (call.path !== '/notifications') return { ok: true, result: {} };
    if (call.operation === 'upsertRule') policies = [...policies.filter(rule => rule.packageName !== call.rule.packageName), call.rule];
    return { ok: true, result: { enabled: true, rules: policies, apps: [{ packageName: 'example.sms', label: '测试短信' }] } };
  });
  try {
    await fixture.run('测试：另外把快递取件短信提醒我，之前的要求保持');
    assert.deepEqual(policies, [updated]);
    assert.equal(policies[0].id, original.id);
    assert.deepEqual(policies[0].entries[0], original.entries[0]);
    assert.equal(fixture.errors.length, 0);
  } finally { await fixture.close(); }
});

test('dialogue app exceptions and content cleanup persist, and restoring all app modes keeps content rules', async () => {
  const smsRule = { packageName: 'example.sms', instruction: '只清理广告短信，其他短信保留',
    entries: [], defaultAction: 'analyze' };
  let exceptions = [], rules = [];
  const apps = [{ packageName: 'example.chat', label: '测试聊天' }, { packageName: 'example.shop', label: '测试商城' },
    { packageName: 'example.sms', label: '测试短信' }];
  const fixture = await harness([
    tool('app-inventory', 'droy_notification_rules', { operation: 'get' }),
    options => {
      assert.match(JSON.stringify(toolResults(options)), /测试聊天/);
      return tool('allow-chat', 'droy_notification_rules', { operation: 'setAppMode', packageName: 'example.chat', mode: 'allow' });
    },
    tool('block-shop', 'droy_notification_rules', { operation: 'setAppMode', packageName: 'example.shop', mode: 'block' }),
    tool('sms-content', 'droy_notification_rules', { operation: 'upsertRule', rule: smsRule }),
    text('已设置：聊天通知放行，商城通知关闭，短信只清理广告。'),
    tool('current-exceptions', 'droy_notification_rules', { operation: 'get' }),
    options => {
      const result = JSON.stringify(toolResults(options));
      assert.match(result, /allow/); assert.match(result, /block/);
      return tool('restore-app-modes', 'droy_notification_rules', { operation: 'configure', exceptions: [] });
    },
    text('已恢复全部应用智能管理，短信内容规则保留。'),
  ], async call => {
    if (call.path !== '/notifications') return { ok: true, result: {} };
    if (call.operation === 'setAppMode') {
      exceptions = exceptions.filter(entry => entry.packageName !== call.packageName);
      if (call.mode !== 'managed') exceptions.push({ packageName: call.packageName, mode: call.mode });
    }
    if (call.operation === 'upsertRule') rules = [{ ...call.rule, id: 'sms-content-policy' }];
    if (call.operation === 'configure') exceptions = call.exceptions;
    return { ok: true, result: { enabled: true, cloudAnalysisEnabled: true, exceptions, rules,
      autoSilence: { status: 'ready' }, apps: apps.map(app => ({ ...app,
        mode: exceptions.find(entry => entry.packageName === app.packageName)?.mode ?? 'managed' })) } };
  });
  try {
    await fixture.run('测试：放行聊天通知，关闭商城通知，只清理广告短信，其他短信保留');
    assert.deepEqual(exceptions, [{ packageName: 'example.chat', mode: 'allow' }, { packageName: 'example.shop', mode: 'block' }]);
    assert.equal(rules[0].instruction, smsRule.instruction);
    await fixture.run('测试：全部恢复智能管理');
    assert.deepEqual(exceptions, []);
    assert.equal(rules[0].instruction, smsRule.instruction);
    assert.deepEqual(fixture.requests.filter(call => call.path === '/notifications').map(call => call.operation),
      ['get', 'setAppMode', 'setAppMode', 'upsertRule', 'get', 'configure']);
    assert.equal(fixture.errors.length, 0);
  } finally { await fixture.close(); }
});

test('native stale rejection reaches the model unchanged while pet reports recovery rather than a blocked task', async () => {
  const fixture = await harness([
    tool('stale-action', 'android_tap', { snapshotId: 'old', nodeId: 'target' }),
    async options => {
      assert.match(JSON.stringify(toolResults(options)), /stale_snapshot/);
      await delay(20);
      return tool('fresh-page', 'android_observe');
    },
    text('已重新查看当前页面。'),
  ], async call => {
    if (call.action === 'tap') return { ok: false, error: { code: 'stale_snapshot', message: 'private fixture screen',
      reason: 'window_changed', dispatched: false, outcomeUnknown: false } };
    return { ok: true, result: { snapshotId: 'fresh' } };
  });
  try {
    await fixture.run();
    await eventually(() => fixture.progressRequests.some(row => row.phase === 'completed'), 'completion');
    assert.ok(fixture.progressRequests.some(row => row.summary === '正在努力执行' && row.summaryKind === 'working'));
    assert.ok(fixture.progressRequests.every(row => row.phase !== 'blocked'));
    assert.equal(JSON.stringify(fixture.progressRequests).includes('private fixture screen'), false);
    assert.deepEqual(fixture.requests.filter(row => row.path === '/tool').map(row => row.action), ['tap', 'observe']);
  } finally { await fixture.close(); }
});

test('REAL DSH loop → MOCK provider → HTTP Android bridge → verified tool result → final response',async()=>{
  let value='';
  const fixture=await harness([
    options=>{assert.deepEqual(options.tools.map(t=>t.name).sort(),[...toolDefinitions.map(x=>x.name),'droy_pet_progress','droy_notification_rules','droy_load_tools'].sort());return tool('caps','android_capabilities');},
    tool('before','android_observe'),
    tool('input','android_input_text',{snapshotId:'before',nodeId:'input',text:'你好 DroyAgent',taskId:'forged-model-id'}),
    tool('after','android_observe'),
    options=>{assert.match(JSON.stringify(toolResults(options)),/你好 DroyAgent/);return text('测试结果：已重新观察并确认填写内容。');},
  ],async call=>{
    if(call.path==='/task/end')return {ok:true,result:{released:true}};
    if(call.action==='capabilities')return {ok:true,result:{channel:'accessibility',allowed:true}};
    if(call.action==='input_text'){value=call.args.text;return {ok:true,result:{submitted:true}};}
    if(call.action==='observe')return {ok:true,result:{snapshotId:value?'after':'before',nodes:[{nodeId:'input',text:value,editable:true}]}};
    throw new Error('Unexpected mock action');
  });
  try{
    await fixture.run();await eventually(()=>fixture.requests.some(r=>r.path==='/task/end'),'task release');
    await eventually(()=>fixture.progressRequests.some(r=>r.phase==='completed'),'task progress completion');
    assert.ok(fixture.progressRequests.some(r=>r.phase==='thinking'));
    assert.ok(fixture.progressRequests.some(r=>r.phase==='executing'));
    assert.ok(fixture.progressRequests.every(r=>Object.keys(r).every(key=>['path','taskId','phase','active','summary','summaryKind','activityKind','pending'].includes(key))));
    assert.deepEqual(fixture.errors,[]);
    assert.equal(JSON.parse(toolResults(fixture.adapter.requests[1])[0].content[0].text).modelImageInput, 'unsupported');
    assert.deepEqual(fixture.requests.filter(r=>r.path==='/tool').map(r=>r.action),['capabilities','observe','input_text','observe']);
    assert.ok(fixture.requests.every(r=>r.taskId===String(fixture.agent.id)));
    assert.deepEqual(fixture.requests.filter(r=>r.path==='/tool').map(r=>r.callId),['caps','before','input','after']);
    assert.match(JSON.stringify(fixture.agent.session.snapshotEvents()),/已重新观察并确认/);
  }finally{await fixture.close();}
});

test('REAL agent cancellation reaches /cancel before task release and starts no later action',async()=>{
  const started=deferred(),cancelled=deferred();
  const fixture=await harness([tool('long-swipe','android_swipe',{snapshotId:'fixture-basis',startX:1,startY:1,endX:1,endY:300}),tool('must-not-run','android_back')],async call=>{
    if(call.path==='/tool'){started.resolve();await cancelled.promise;return {ok:false,error:{code:'CANCELLED',message:'MOCK cancelled'}};}
    if(call.path==='/cancel'){cancelled.resolve();return {ok:true,result:{cancelled:true}};}
    return {ok:true,result:{released:true}};
  });
  try{
    const running=fixture.run();await started.promise;fixture.agent.cancel({kind:'user'});await running;
    await eventually(()=>fixture.requests.some(r=>r.path==='/task/end'),'cancelled task release');
    await eventually(()=>fixture.progressRequests.some(r=>r.phase==='interrupted'),'cancelled progress');
    assert.deepEqual(fixture.requests.map(r=>r.path),['/tool','/cancel','/task/end']);
    assert.equal(fixture.requests[1].callId,'long-swipe');assert.equal(fixture.adapter.requests.length,1);
  }finally{cancelled.resolve();await fixture.close();}
});

test('product STOPPED failure is delivered to model, released, and not reported as tool success',async()=>{
  const fixture=await harness([tool('blocked','android_observe'),options=>{const results=toolResults(options);assert.equal(results.at(-1).isError,true);assert.match(JSON.stringify(results),/STOPPED/);return text('操作已停止，请在应用中重新授权。');}],async call=>call.path==='/tool'?{ok:false,error:{code:'STOPPED',message:'请在应用重新授权'}}:{ok:true,result:{released:true}});
  try{await fixture.run();await eventually(()=>fixture.requests.some(r=>r.path==='/task/end'),'failed task release');assert.deepEqual(fixture.errors,[]);}finally{await fixture.close();}
});

test('a plugin tool outside the Android catalogue executes under the same DSH agent',async()=>{
  let calls=0;
  const fixture=await harness([tool('plugin','fixture_plugin_echo',{text:'upstream tool'}),options=>{
    const result=toolResults(options).at(-1);
    assert.equal(result.isError,false);
    assert.match(JSON.stringify(result),/upstream tool/);
    return text('插件工具已执行。');
  }],async()=>{throw new Error('Non-Android tools must not enter the Android bridge');});
  try{
    fixture.ctx.tools.register(defineTool({
      name:'fixture_plugin_echo',description:'Fictional plugin fixture',
      parameters:{text:{type:'string'}},
      output:{schema:{type:'json'},render:(_args,value)=>[{type:'text',text:JSON.stringify(value)}]},
      async execute(args){calls++;return {text:args.text};},
    }));
    await fixture.run();
    assert.equal(calls,1);assert.deepEqual(fixture.errors,[]);assert.deepEqual(fixture.requests,[]);
  }finally{await fixture.close();}
});

test('text-only model screenshot request fails before native capture',async()=>{
  const fixture=await harness([tool('no-vision','android_screenshot'),options=>{assert.match(JSON.stringify(toolResults(options)),/MODEL_IMAGE_UNSUPPORTED/);return text('当前模型未声明图像能力，改用元素观察。');}],async()=>{throw new Error('Screenshot must not reach bridge');});
  try{await fixture.run();assert.deepEqual(fixture.requests,[]);assert.deepEqual(fixture.errors,[]);}finally{await fixture.close();}
});

test('next turn with the same agent waits for prior native control release acknowledgement',async()=>{
  const releasing=deferred(),releaseAck=deferred();let endCount=0;
  const fixture=await harness([tool('first','android_observe'),text('第一轮完成'),tool('second','android_observe'),text('第二轮完成')],async call=>{
    if(call.path==='/task/end' && ++endCount===1){releasing.resolve();await releaseAck.promise;}
    return {ok:true,result:call.path==='/tool'?{snapshotId:call.callId,nodes:[]}:{released:true}};
  });
  try{
    await fixture.run();await releasing.promise;
    const second=fixture.run('继续第二轮');await eventually(()=>fixture.adapter.requests.length===3,'second model request');
    await delay(25);assert.equal(fixture.requests.filter(r=>r.path==='/tool').length,1);
    releaseAck.resolve();await second;await eventually(()=>endCount===2,'second native release');
    assert.deepEqual(fixture.requests.map(r=>r.path),['/tool','/task/end','/tool','/task/end']);assert.deepEqual(fixture.errors,[]);
  }finally{releaseAck.resolve();await fixture.close();}
});

test('expanded tools preserve domain results, query arguments, and engine-owned identity',async()=>{
  const sequence=[
    ['info','android_system_info',{sections:['battery','network']},{status:'queried',capturedAt:100,sections:{battery:{status:'available',percent:73}}}],
    ['apps','android_find_apps',{query:'练习'},{status:'ambiguous',apps:[{packageName:'example.one'},{packageName:'example.two'}],dispatched:false}],
    ['calendar','android_calendar_query',{startMs:1000,endMs:2000,calendarId:4},{source:'android_calendar',items:[],truncated:false}],
    ['file','android_file_read',{fileId:'fixture-handle',maxChars:100},{text:'fixture text',truncated:false,source:'user_selected_android_documents'}],
    ['browser','droy_browser_wait',{text:'loading complete',timeoutMs:100},{status:'wait_timeout',conditionVerified:false}],
    ['compute','droy_compute_run',{code:'return input.a+input.b;',input:{a:2,b:3}},{status:'completed',result:5,artifacts:[]}],
  ];
  const fixture=await harness([...sequence.map(([id,name,args])=>tool(id,name,args)),options=>{
    const results=JSON.stringify(toolResults(options));
    for(const marker of ['queried','ambiguous','android_calendar','fixture text','wait_timeout','completed']) assert.ok(results.includes(marker));
    return text('已返回查询和计算结果；同名应用未打开，网页等待超时。');
  }],async call=>{
    if(call.path==='/task/end')return {ok:true,result:{released:true}};
    const entry=sequence.find(([id])=>id===call.callId);assert.ok(entry);
    assert.equal(call.action,toolDefinitions.find(t=>t.name===entry[1]).action);
    assert.deepEqual(call.args,entry[2]);return {ok:true,result:entry[3]};
  });
  try{
    await fixture.run();await eventually(()=>fixture.requests.some(r=>r.path==='/task/end'),'expanded task release');
    assert.deepEqual(fixture.errors,[]);
    assert.equal(fixture.requests.filter(r=>r.path==='/tool').length,sequence.length);
    assert.ok(fixture.requests.every(r=>r.taskId===String(fixture.agent.id)));
  }finally{await fixture.close();}
});

test('screenshot tool keeps fresh coordinate metadata beside the image without copying base64 into model text',async()=>{
  const {encodePng}=await import('../png.mjs');
  const png=encodePng({width:1,height:1,channels:4,pixels:Buffer.from([1,2,3,255])});
  const ref={attachmentId:'a'.repeat(64),mediaType:'image/png',bytes:png.length,width:1,height:1,name:'fixture.png'};
  const fixture=await harness([],async call=>({ok:true,result:call.path==='/tool'
    ?{status:'captured',mimeType:'image/png',base64:png.toString('base64'),width:1,height:1,snapshotId:'fresh-coordinate-basis',coordinateBasisStatus:'available'}
    :{released:true}}));
  try{
    fixture.ctx.llm.resolveModelInfo=async()=>({inputModalities:['text','image']});
    fixture.ctx.attachments.saveImage=async input=>{assert.deepEqual(input.data,png);return ref;};
    const registered=fixture.ctx.tools.get('android_screenshot');
    const value=await registered.execute({}, {agent:fixture.agent,callId:'screenshot-metadata',signal:new AbortController().signal});
    assert.equal(value.snapshotId,'fresh-coordinate-basis');assert.equal(value.coordinateBasisStatus,'available');
    assert.equal('base64' in value,false);
    const content=registered.output.render({},value);
    assert.equal(content[0].type,'text');assert.equal(JSON.parse(content[0].text).snapshotId,'fresh-coordinate-basis');
    assert.equal(content[1].type,'image');assert.deepEqual(content[1].attachment,ref);
    assert.ok(!content[0].text.includes(png.toString('base64')));
  }finally{await fixture.close();}
});

function registerFixtureInteraction(fixture, ask) {
  fixture.ctx.tools.register(defineTool({ name:'fixture_interaction',description:'Fictional user interaction',parameters:{},
    output:{schema:{type:'json'},render:(_args,value)=>[{type:'text',text:JSON.stringify(value)}]},
    execute:(_args,exec)=>ask(exec),
  }));
}
function pageAnswerer(fixture,event) {
  let request,resolve,reject,withdrawals=0;
  fixture.ctx.on(event,(value)=>{
    request=value;
    return new Promise((yes,no)=>{
      resolve=yes;reject=no;
      value.signal?.addEventListener('abort',()=>{withdrawals++;no(Error('page request withdrawn'));},{once:true});
    });
  });
  return {get request(){return request;},get withdrawals(){return withdrawals;},answer(value){resolve(value);},reject(error){reject(error);}};
}
async function pendingRequest(fixture) {
  await eventually(()=>fixture.progressRequests.some(row=>row.pending),'pet pending request');
  return fixture.progressRequests.find(row=>row.pending).pending;
}

test('verification handoff uses the real question service then observes and reviews before native completion', async () => {
  let foreground = 'io.droyagent.app';
  const fixture = await harness([
    tool('verification-assess', 'android_verification', { operation: 'assess', kind: 'otp',
      solvable: false, snapshotId: 'before-verification' }),
    options => {
      const results = JSON.stringify(toolResults(options));
      assert.match(results, /awaiting_review/); assert.match(results, /after-human/);
      assert.doesNotMatch(results, /"state":"verified"/);
      return tool('verification-complete', 'android_verification', { operation: 'complete',
        passed: true, snapshotId: 'after-human' });
    },
    text('已核实原应用通过验证，继续任务。'),
  ], async call => {
    if (call.path !== '/tool') return { ok: true, result: {} };
    if (call.action === 'observe') {
      assert.equal(foreground, 'example.fixture', 'Answering in chat must restore the original app before observing');
      return { ok: true, result: { snapshotId: 'after-human', packageName: foreground } };
    }
    assert.equal(call.action, 'verification');
    if (call.args.operation === 'assess') assert.equal(call.args.imageSupported, false);
    if (call.args.operation === 'return_to_app') {
      assert.deepEqual(call.args, { operation: 'return_to_app' });
      foreground = 'example.fixture';
    }
    return { ok: true, result: { state: call.args.operation === 'resume' ? 'awaiting_review'
      : call.args.operation === 'complete' ? 'verified' : 'manual_required' } };
  });
  const page = pageAnswerer(fixture, 'user-questions/request');
  try {
    const running = fixture.run('测试：遇到口令验证时交给我完成。');
    const pending = await pendingRequest(fixture);
    assert.equal(pending.inline, true);
    assert.deepEqual(pending.options.map(option => option.label), ['已完成，继续', '暂不处理']);
    assert.deepEqual(fixture.requests.filter(call => call.path === '/tool').map(call => call.args.operation), ['assess', 'handoff']);
    assert.equal((await fixture.action({ taskId: fixture.agent.id, action: 'answer', requestId: pending.requestId, optionId: '0' })).status, 200);
    await running;
    assert.deepEqual(fixture.requests.filter(call => call.path === '/tool').map(call => `${call.action}:${call.args.operation ?? ''}`),
      ['verification:assess', 'verification:handoff', 'verification:return_to_app', 'observe:', 'verification:resume', 'verification:complete']);
    assert.equal(page.withdrawals, 1); assert.deepEqual(fixture.errors, []);
  } finally { await fixture.close(); }
});

test('deferring a verification preserves native manual gate and does not claim success or reobserve', async () => {
  const fixture = await harness([
    tool('verification-handoff', 'android_verification', { operation: 'handoff' }),
    options => {
      const results = JSON.stringify(toolResults(options));
      assert.match(results, /manual_required/); assert.match(results, /userDeferred/);
      return text('任务停在验证步骤，等待你处理。');
    },
  ], async call => ({ ok: true, result: call.path === '/tool' ? { state: 'manual_required' } : {} }));
  pageAnswerer(fixture, 'user-questions/request');
  try {
    const running = fixture.run('测试：暂不处理验证。');
    const pending = await pendingRequest(fixture);
    await fixture.action({ taskId: fixture.agent.id, action: 'answer', requestId: pending.requestId, optionId: '1' });
    await running;
    assert.deepEqual(fixture.requests.filter(call => call.path === '/tool').map(call => call.args.operation), ['handoff']);
    assert.deepEqual(fixture.errors, []);
  } finally { await fixture.close(); }
});

test('native choice answers a REAL user question exactly once and withdraws the page composer',async()=>{
  const fixture=await harness([tool('question','fixture_interaction'),options=>{
    assert.match(JSON.stringify(toolResults(options)),/应用乙/);return text('继续使用应用乙。');
  }],async()=>{throw Error('Question must not execute Android tools');});
  const page=pageAnswerer(fixture,'user-questions/request');
  registerFixtureInteraction(fixture,exec=>fixture.ctx.userQuestions.ask({agent:exec.agent,signal:exec.signal,
    questions:[{id:'app',question:'使用哪一个应用？',options:[{label:'应用甲'},{label:'应用乙'}]}]}));
  try{
    const running=fixture.run();const pending=await pendingRequest(fixture);
    assert.equal(pending.inline,true);assert.equal(pending.message,'使用哪一个应用？');
    const action={taskId:fixture.agent.id,action:'answer',requestId:pending.requestId,optionId:'1'};
    assert.equal((await fixture.action({...action,taskId:'different-task'})).status,409);
    assert.equal((await fixture.action(action)).status,200);
    assert.equal((await fixture.action(action)).status,409);
    await running;assert.equal(page.withdrawals,1);assert.deepEqual(fixture.errors,[]);
    await eventually(()=>fixture.progressRequests.at(-1)?.active===false,'question turn ends');
    assert.equal(fixture.progressRequests.at(-1).pending,null);
  }finally{await fixture.close();}
});

test('page answer retires the native multi-select form',async()=>{
  const fixture=await harness([tool('question','fixture_interaction'),text('收到选择。')],async()=>{});
  const page=pageAnswerer(fixture,'user-questions/request');
  registerFixtureInteraction(fixture,exec=>fixture.ctx.userQuestions.ask({agent:exec.agent,signal:exec.signal,
    questions:[{id:'app',question:'选择应用',multiSelect:true,options:[{label:'应用甲'},{label:'应用乙'}]}]}));
  try{
    const running=fixture.run();const pending=await pendingRequest(fixture);
    assert.equal(pending.inline,true);assert.deepEqual(pending.options,[]);assert.equal(pending.questions[0].multiSelect,true);
    assert.equal((await fixture.action({taskId:fixture.agent.id,action:'answer',requestId:pending.requestId,optionId:'0'})).status,409);
    page.answer({answers:[{id:'app',selected:['应用甲','应用乙']}]});await running;
    assert.deepEqual(fixture.errors,[]);
  }finally{await fixture.close();}
});

test('native form answers multiple REAL questions with descriptions, checkboxes and custom text', async () => {
  const fixture = await harness([tool('question', 'fixture_interaction'), options => {
    const result = JSON.stringify(toolResults(options));
    assert.match(result, /应用甲/); assert.match(result, /应用乙/); assert.match(result, /手动补充/);
    return text('已收到全部回答。');
  }], async () => {});
  const page = pageAnswerer(fixture, 'user-questions/request');
  const questions = [
    {id:'apps', question:'选择应用', detail:'选择可以一起使用的应用。', multiSelect:true,
      options:[{label:'应用甲', description:'测试选项的完整说明'},{label:'应用乙'},{label:'应用丙'},{label:'应用丁'}]},
    {id:'note', question:'还有什么要求？'},
  ];
  registerFixtureInteraction(fixture, exec => fixture.ctx.userQuestions.ask({agent:exec.agent, signal:exec.signal, questions}));
  try {
    const running = fixture.run(); const pending = await pendingRequest(fixture);
    assert.equal(pending.inline, true); assert.equal(pending.questions.length, 2);
    assert.equal(pending.questions[0].options[0].description, '测试选项的完整说明');
    assert.equal(pending.questions[1].allowCustom, true);
    const action = {taskId:fixture.agent.id, action:'answer', requestId:pending.requestId,
      answers:[{id:'apps', selected:['应用甲','应用乙'], custom:'手动补充'}, {id:'note', selected:[], custom:'  晚上使用  '}]};
    assert.equal((await fixture.action({...action, answers:[{id:'apps',selected:['不存在']},{id:'note',selected:[]}]})).status,409);
    assert.equal((await fixture.action({...action, answers:[{id:'apps',selected:['应用甲']}]})).status,409);
    assert.equal((await fixture.action({...action, answers:[{id:'apps',selected:['应用甲','应用甲']},{id:'note',selected:[]}]})).status,409);
    assert.equal((await fixture.action(action)).status,200);
    assert.equal((await fixture.action(action)).status,409);
    await running; assert.equal(page.withdrawals,1); assert.deepEqual(fixture.errors,[]);
  } finally { await fixture.close(); }
});

test('native form remains answerable without a connected page provider', async () => {
  const fixture=await harness([tool('question','fixture_interaction'),text('已收到悬浮回答。')],async()=>{});
  registerFixtureInteraction(fixture,exec=>fixture.ctx.userQuestions.ask({agent:exec.agent,signal:exec.signal,
    questions:[{id:'text',question:'请填写要求'}]}));
  try {
    const running=fixture.run(), pending=await pendingRequest(fixture);
    await delay(30);
    assert.equal(fixture.progressRequests.at(-1).pending?.requestId,pending.requestId);
    assert.equal((await fixture.action({taskId:fixture.agent.id,action:'answer',requestId:pending.requestId,
      answers:[{id:'text',selected:[],custom:'仅通过悬浮气泡回答'}]})).status,200);
    await running; assert.deepEqual(fixture.errors,[]);
  } finally {await fixture.close();}
});

test('native approval remains auditable without a connected page provider', async () => {
  const fixture=await harness([tool('approval','fixture_interaction'),text('已确认。')],async()=>{});
  registerFixtureInteraction(fixture,exec=>fixture.ctx.approval.request({agent:exec.agent,signal:exec.signal,
    toolName:'fixture_allowed_operation',reason:'是否执行测试操作？'}));
  try {
    const running=fixture.run(), pending=await pendingRequest(fixture);
    await delay(30);
    assert.equal((await fixture.action({taskId:fixture.agent.id,action:'answer',requestId:pending.requestId,
      answers:[{id:'allow',selected:['allowed-once']}]})).status,409);
    assert.equal((await fixture.action({taskId:fixture.agent.id,action:'answer',requestId:pending.requestId,optionId:'allow'})).status,200);
    await running;
    assert.equal(fixture.agent.session.snapshotEvents().filter(event=>event.type==='approval/decided').at(-1).data.outcome,'allowed-once');
    assert.deepEqual(fixture.errors,[]);
  } finally {await fixture.close();}
});

test('native radio custom answer cannot combine a selected option', async () => {
  const fixture = await harness([tool('question','fixture_interaction'),text('收到。')], async()=>{});
  pageAnswerer(fixture,'user-questions/request');
  registerFixtureInteraction(fixture,exec=>fixture.ctx.userQuestions.ask({agent:exec.agent,signal:exec.signal,
    questions:[{id:'one',question:'选择一个',options:[{label:'选项'}]}]}));
  try {
    const running=fixture.run(), pending=await pendingRequest(fixture);
    const action={taskId:fixture.agent.id,action:'answer',requestId:pending.requestId};
    assert.equal((await fixture.action({...action,answers:[{id:'one',selected:['选项'],custom:'另一项'}]})).status,409);
    assert.equal((await fixture.action({...action,answers:[{id:'one',selected:[],custom:3}]})).status,409);
    assert.equal((await fixture.action({...action,answers:[{id:'one',selected:[],custom:'另一项'}]})).status,200);
    await running; assert.deepEqual(fixture.errors,[]);
  } finally {await fixture.close();}
});

test('native answer respects REAL approval audit and call-detail approvals return to the original task',async()=>{
  const fixture=await harness([tool('approval','fixture_interaction'),text('已经收到审批结果。'),tool('details','fixture_interaction'),text('已拒绝。')],async()=>{});
  const page=pageAnswerer(fixture,'approval/request');let call=0;
  registerFixtureInteraction(fixture,exec=>fixture.ctx.approval.request({agent:exec.agent,signal:exec.signal,
    toolName:'fixture_allowed_operation',reason:'是否执行这次测试操作？',...(call++?{callId:exec.callId}:{})}));
  try{
    const first=fixture.run();const pending=await pendingRequest(fixture);
    assert.equal(pending.inline,true);
    assert.equal((await fixture.action({taskId:fixture.agent.id,action:'answer',requestId:pending.requestId,optionId:'allow'})).status,200);
    await first;assert.equal(page.withdrawals,1);
    const audit=fixture.agent.session.snapshotEvents().filter(event=>event.type.startsWith('approval/'));
    assert.deepEqual(audit.map(event=>event.type),['approval/asked','approval/decided']);
    assert.equal(audit[1].data.outcome,'allowed-once');
    fixture.progressRequests.length=0;
    const second=fixture.run();const detailed=await pendingRequest(fixture);
    assert.equal(detailed.inline,false);assert.deepEqual(detailed.options,[]);
    page.answer('rejected');await second;
    assert.equal(fixture.agent.session.snapshotEvents().filter(event=>event.type==='approval/decided').at(-1).data.outcome,'rejected');
    assert.deepEqual(fixture.errors,[]);
  }finally{await fixture.close();}
});

test('pet action route authenticates and cancels only its REAL task, withdrawing pending choices',async()=>{
  const fixture=await harness([tool('question','fixture_interaction'),tool('must-not-run','android_observe')],async()=>{throw Error('Cancellation must stop later tools');});
  const page=pageAnswerer(fixture,'user-questions/request');
  registerFixtureInteraction(fixture,exec=>fixture.ctx.userQuestions.ask({agent:exec.agent,signal:exec.signal,
    questions:[{id:'app',question:'使用哪一个应用？',options:[{label:'应用甲'},{label:'应用乙'}]}]}));
  try{
    const running=fixture.run();const pending=await pendingRequest(fixture);
    const action={taskId:fixture.agent.id,action:'cancel'};
    assert.equal((await fixture.action(action,{})).status,401);
    assert.equal((await fixture.action(action,{Cookie:`droy_session=${TOKEN}`,Origin:'https://untrusted.example'})).status,401);
    assert.equal(fixture.agent.status,'running');
    assert.equal((await fixture.action(action)).status,200);
    await running;assert.equal(page.withdrawals,1);assert.equal(fixture.adapter.requests.length,1);
    assert.equal((await fixture.action({taskId:fixture.agent.id,action:'answer',requestId:pending.requestId,optionId:'0'})).status,409);
    assert.deepEqual(fixture.requests,[]);
    assert.deepEqual(fixture.errors,[]);
  }finally{await fixture.close();}
});

test('main model public progress uses one existing turn with no Android action or secondary inference',async()=>{
  const fixture=await harness([tool('public','droy_pet_progress',{summary:'正在筛选符合预算的商品',phase:'executing'}),async options=>{
    assert.match(JSON.stringify(toolResults(options)),/updated/);
    await eventually(()=>fixture.progressRequests.some(row=>row.summary==='正在筛选符合预算的商品'),'public summary while task runs');
    return text('已完成测试。');
  }],async()=>{throw Error('Public progress is not an Android action');});
  try{
    await fixture.run();await eventually(()=>fixture.progressRequests.some(row=>row.summary==='正在筛选符合预算的商品'),'public summary');
    assert.equal(fixture.adapter.requests.length,2);assert.deepEqual(fixture.requests,[]);assert.deepEqual(fixture.errors,[]);
  }finally{await fixture.close();}
});

test('answering the original page wins over native options without a duplicate reply',async()=>{
  const fixture=await harness([tool('question','fixture_interaction'),text('收到原页选择。')],async()=>{});
  const page=pageAnswerer(fixture,'user-questions/request');
  registerFixtureInteraction(fixture,exec=>fixture.ctx.userQuestions.ask({agent:exec.agent,signal:exec.signal,
    questions:[{id:'app',question:'使用哪一个应用？',options:[{label:'应用甲'},{label:'应用乙'}]}]}));
  try{
    const running=fixture.run();const pending=await pendingRequest(fixture);
    page.answer({answers:[{id:'app',selected:['应用甲']}]});await running;
    assert.equal((await fixture.action({taskId:fixture.agent.id,action:'answer',requestId:pending.requestId,optionId:'1'})).status,409);
    assert.equal(fixture.adapter.requests.length,2);assert.deepEqual(fixture.errors,[]);
  }finally{await fixture.close();}
});

test('upstream never approval policy rejects before the pet can present a grant',async()=>{
  const fixture=await harness([tool('approval','fixture_interaction'),options=>{
    assert.match(JSON.stringify(toolResults(options)),/rejected/);return text('策略已拒绝。');
  }],async()=>{}, {approvalPolicy:'never'});
  registerFixtureInteraction(fixture,exec=>fixture.ctx.approval.request({agent:exec.agent,signal:exec.signal,
    toolName:'fixture_allowed_operation',reason:'是否执行这次测试操作？'}));
  try{
    await fixture.run();await eventually(()=>fixture.progressRequests.at(-1)?.active===false,'denied turn ends');
    assert.ok(fixture.progressRequests.every(row=>row.pending===null));
    assert.equal(fixture.agent.session.snapshotEvents().filter(event=>event.type==='approval/decided').at(-1).data.outcome,'rejected');
    assert.deepEqual(fixture.errors,[]);
  }finally{await fixture.close();}
});
