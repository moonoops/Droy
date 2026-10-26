// Real DSH assembly + agent loop with a local scripted provider. No cloud calls.
import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { createRequire, registerHooks } from 'node:module';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { patchSystemPrompt } from '../../../tools/mobile_prompt_patch.mjs';
import { readUpstreamProfile, mobileProfilePatches, applyEntryPatches } from '../../../tools/mobile_profile.mjs';
import { persona, defaultCompanionPersona } from '../profile.mjs';
import { finalizeMobileAssembly, responseLanguagePrompts } from '../prompt.mjs';
import { mobilePersonas, companionPersonas } from '../profile.mjs';
import { resolveMobileLocale, normalizeMobileLocale } from '../locale.mjs';
process.env.DROY_LOCALE = 'zh';
import { installCompanionIdentity, COMPANION_CONTEXT } from '../companion.mjs';
import { companionState } from './fixtures/companion-state.mjs';

const installed=createRequire(new URL('../../dsh/package.json',import.meta.url));
const hooks=registerHooks({resolve(specifier,context,next){
  if(specifier==='@droyagent/mobile/prompt')return {url:new URL('../prompt.mjs',import.meta.url).href,shortCircuit:true};
  if(specifier.startsWith('@deepseek-ai/'))return {url:pathToFileURL(installed.resolve(specifier)).href,shortCircuit:true};
  return next(specifier,context);
}});
const {Context}=await import('@deepseek-ai/cordis');
const {default:LlmRuntime,LlmAdapter,createUserMessage}=await import('@deepseek-ai/dsh-llm');
const {default:SessionStore}=await import('@deepseek-ai/dsh-session');
const {default:SessionProjectionRegistry}=await import('@deepseek-ai/dsh-session-projection');
const {default:ToolRuntime,defineTool}=await import('@deepseek-ai/dsh-tools');
const {default:AgentRegistry}=await import('@deepseek-ai/dsh-agent');
const {default:AgentLoop}=await import('@deepseek-ai/dsh-agent-loop');
const Persona=await import('@deepseek-ai/dsh-persona');
const {applyChildComposition}=await import('@deepseek-ai/dsh-subagent');
const source=await readFile(new URL('../../dsh/node_modules/@deepseek-ai/dsh-system-prompt/lib/index.js',import.meta.url),'utf8');
const {default:SystemPrompt,renderPrompt}=await import('data:text/javascript;base64,'+Buffer.from(patchSystemPrompt(source)).toString('base64'));
hooks.deregister();

const upstream=await readUpstreamProfile(fileURLToPath(new URL('../../dsh/node_modules/',import.meta.url)));
const mobile=applyEntryPatches(upstream,mobileProfilePatches(upstream),message=>assert.fail(message));
const presets=mobile.filter(row=>row.name==='@deepseek-ai/dsh-agent-preset'&&row.disabled!==true);
const textOf=request=>request.messages.filter(message=>message.role==='system').flatMap(message=>message.content).filter(block=>block.type==='text').map(block=>block.text).join('\n');
const assertProduct=prompt=>{
  assert.equal(prompt.split(persona).length-1,1,'full production prompt is included exactly once');
  assert.equal(prompt.split(defaultCompanionPersona).length-1,1,'default companion personality is included exactly once');
  assert.match(prompt,/默认优先使用本机对应 App/);
  assert.match(prompt,/一次临时加载、快照失效或点击失败/);
  assert.match(prompt,/输入后核对实际文字/);
  assert.match(prompt,/动作已派发但结果未知/);
  assert.match(prompt,/直接 android_open_app/);
  assert.match(prompt,/droy_load_tools/);
  assert.match(prompt,/navigation=true/);
  assert.match(prompt,/发送、付款、删除、通用确认不使用 navigation/);
  assert.ok(!prompt.includes('maxCandidates'), 'module details are loaded with their tool group');
};

class MockProvider extends LlmAdapter {
  requests=[];
  async resolveModel(provider,model){return {provider,id:model,name:'Local prompt fixture',inputModalities:['text']};}
  async *stream(request){
    this.requests.push(request);
    yield {type:'block-start',index:0,blockType:'text'};
    yield {type:'text-delta',index:0,text:'本地提示词装配验证完成。'};
    yield {type:'block-end',index:0,block:{type:'text',text:'本地提示词装配验证完成。'}};
    yield {type:'finish',reason:{kind:'stop'}};
  }
}

async function harness({ companion = async () => companionState, locale } = {}){
  const ctx=new Context();
  if (locale) ctx.provide('settings', { describe: () => [{ ns: 'locale', value: { preference: locale() } }] });
  await ctx.plugin(LlmRuntime);await ctx.plugin(SessionStore);await ctx.plugin(SessionProjectionRegistry);
  await ctx.plugin(SystemPrompt);await ctx.plugin(ToolRuntime,{mode:'native'});
  installCompanionIdentity(ctx, { companion });
  await ctx.plugin(AgentRegistry);await ctx.plugin(AgentLoop,{agents:[]});
  ctx.systemPrompt.section({name:'fixture:tool-contract',order:1000,text:'FICTIONAL_TOOL_CONTRACT'});
  ctx.systemPrompt.context({name:'fixture:optional',order:100,text:'optional runtime fact'});
  ctx.systemPrompt.context({name:'fixture:required',order:110,text:'required runtime fact',required:true});
  ctx.tools.register(defineTool({name:'fixture_tool',description:'Local fixture',parameters:{},
    output:{schema:{type:'json'},render:()=>[]},async execute(){return {};}}));
  const adapter=new MockProvider();ctx.llm.registerAdapter(['local-prompt-fixture'],adapter);
  const errors=[];ctx.on('agent/error',event=>errors.push(event.error));
  return {ctx,adapter,errors,async create(config,extra={}){
    const handle=await ctx.agents.create({sessionId:`prompt-fixture-${crypto.randomUUID()}`,
      agentOptions:{provider:'local-prompt-fixture',model:'local-text-model'},
      setup:async agentCtx=>{await agentCtx.plugin(Persona,config);},...extra});
    return handle.agent;
  },async run(agent,prompt='在本地验证提示词。'){
    agent.followup(createUserMessage({content:[{type:'text',text:prompt}],source:{kind:'user'}}));await agent.whenIdle();
    assert.deepEqual(errors,[]);return adapter.requests.at(-1);
  },async close(){await ctx.fiber.dispose();}};
}

for(const preset of presets){
  test(`REAL DSH ${preset.config.id} persona assembly reaches the provider with Android defaults once`,async()=>{
    const fixture=await harness();
    try{
      const config=preset.config.plugins.find(row=>row.name==='@deepseek-ai/dsh-persona').config;
      const agent=await fixture.create(config);
      const assembly=await fixture.ctx.systemPrompt.assemble({scope:agent,agent});
      assertProduct(renderPrompt(assembly));
      assert.deepEqual(assembly.tools.map(tool=>tool.name),['fixture_tool']);
      if(config.complete){
        assert.equal(assembly.sections.length,1,'complete mode remains a single section');
        assert.deepEqual(assembly.contexts.map(context=>context.name),[COMPANION_CONTEXT,'fixture:required']);
        assert.ok(!renderPrompt(assembly).includes('FICTIONAL_TOOL_CONTRACT'));
      }else{
        assert.match(renderPrompt(assembly),/FICTIONAL_TOOL_CONTRACT/);
        assert.equal(assembly.contexts.length,3);
      }
      assertProduct(textOf(await fixture.run(agent)));
      assert.ok(JSON.stringify(fixture.adapter.requests.at(-1).messages).includes(companionState.name));
      assertProduct(textOf(await fixture.run(agent,'继续同一会话。')));
      assert.ok(!textOf(fixture.adapter.requests.at(-1)).includes('You are a coding agent'));
    }finally{await fixture.close();}
  });
}

for(const complete of [false,true]){
  test(`custom persona remains verbatim with complete=${complete} in final provider request`,async()=>{
    const fixture=await harness();
    const prefix='自定义角色：仅使用虚构资料规划旅行。';
    const suffix='保留用户的个人偏好。';
    try{
      const agent=await fixture.create({prefix,suffix,complete});
      const request=await fixture.run(agent),prompt=textOf(request);
      assertProduct(prompt);assert.ok(prompt.includes(prefix));
      assert.ok(JSON.stringify(request.messages).includes(companionState.personality),'custom or complete persona retains current companion context');
      assert.equal(prompt.includes(suffix),!complete,'complete persona preserves upstream suffix suppression');
      assert.deepEqual(request.tools.map(tool=>tool.name),['fixture_tool']);
    }finally{await fixture.close();}
  });
}

test('REAL DSH child composition with a direct persona override retains product defaults',async()=>{
  const fixture=await harness();
  try{
    const parent=await fixture.create({prefix:'父代理任务身份。',suffix:'父代理规则。'});
    const childPersona='子代理专注核对虚构商品信息。';
    const child=await fixture.create({}, {parentAgent:parent,
      setup:childCtx=>applyChildComposition(childCtx,parent,{persona:childPersona})});
    const prompt=textOf(await fixture.run(child));
    assertProduct(prompt);assert.ok(prompt.includes(childPersona));
    assert.ok(JSON.stringify(fixture.adapter.requests.at(-1).messages).includes(companionState.name),'child agent receives the companion identity');
    assert.ok(!prompt.includes('父代理任务身份。'));
  }finally{await fixture.close();}
});

test('assembly adapter is idempotent and never changes tools, contexts or variables',()=>{
  const input={sections:[{name:'custom',text:'literal custom persona',interpolate:false}],tools:[{name:'fixture_tool'}],contexts:[],variables:{model:'fixture'}};
  const ordinary=finalizeMobileAssembly(input);
  assert.equal(finalizeMobileAssembly(ordinary),ordinary);assert.equal(ordinary.tools,input.tools);assert.equal(ordinary.contexts,input.contexts);assert.equal(ordinary.variables,input.variables);
  const complete=finalizeMobileAssembly(input,'custom');
  assert.equal(complete.sections.length,1);assert.ok(complete.sections[0].text.startsWith(input.sections[0].text));
  assert.equal(finalizeMobileAssembly(complete,'custom'),complete);assert.deepEqual(input.sections,[{name:'custom',text:'literal custom persona',interpolate:false}]);
  assert.throws(()=>patchSystemPrompt(source.replace('return transformed;','return changed;')),/assembly changed/);
});

test('legacy product rules acquire the new default personality without duplicating either block',()=>{
  const legacy={sections:[{name:'legacy',text:persona,interpolate:false}],tools:[],contexts:[],variables:{}};
  const updated=finalizeMobileAssembly(legacy);
  assertProduct(renderPrompt(updated));
  assert.equal(finalizeMobileAssembly(updated),updated);
});

test('the same live conversation receives renamed and customized companion data literally on its next model step',async()=>{
  let current={...companionState};
  const fixture=await harness({companion:async()=>current});
  try{
    const agent=await fixture.create({prefix:'保留自定义角色。',complete:true});
    await fixture.run(agent);
    current={name:'小墨{{literal}}',displayName:'我的伙伴入口',appearanceId:'fixture-fox',personality:'冷幽默，回答简洁；字符串 {{not_a_variable}} 保持原样。'};
    const request=await fixture.run(agent,'使用当前伙伴资料。');
    const contexts=agent.session.snapshotEvents().filter(event=>event.type==='user/message'&&event.data.source?.kind==='runtime-context');
    const latest=JSON.stringify(contexts.at(-1));
    assert.ok(latest.includes(current.name));assert.ok(latest.includes(current.personality));
    assert.ok(!latest.includes(companionState.name));
    assert.ok(JSON.stringify(request.messages).includes(current.name));
    assert.ok(textOf(request).includes('保留自定义角色。'));
  }finally{await fixture.close();}
});

test('saved companion text with control characters reaches the model as literal JSON data',async()=>{
  const current={...companionState,name:'小墨\u0001{{literal}}',personality:'简洁\u0002，保留换行\n与制表\t及 {{not_a_variable}}。'};
  const fixture=await harness({companion:async()=>current});
  try{
    const agent=await fixture.create({prefix:'保留既有资料。',complete:true});
    const request=await fixture.run(agent);
    const context=request.messages.findLast(message=>message.role==='user'&&message.source?.kind==='runtime-context');
    const text=context.content.filter(block=>block.type==='text').map(block=>block.text).join('\n');
    const encoded=text.split('\n').find(line=>line.startsWith('{"name":'));
    assert.equal(encoded,JSON.stringify(current));
    assert.deepEqual(JSON.parse(encoded),current);
    assert.ok(!text.includes('\u0001')&&!text.includes('\u0002'),'control characters remain JSON escapes');
    assert.equal(fixture.errors.length,0);
  }finally{await fixture.close();}
});

test('unavailable or malformed companion identity fails before a provider request instead of silently reusing a default',async()=>{
  for(const failure of [new Error('fixture identity unavailable'),{...companionState,name:null}]){
    const fixture=await harness({companion:async()=>{if(failure instanceof Error)throw failure;return failure;}});
    try{
      const agent=await fixture.create({prefix:'身份失败测试。'});
      agent.followup(createUserMessage({content:[{type:'text',text:'检查身份失败。'}],source:{kind:'user'}}));
      await agent.whenIdle();
      assert.equal(fixture.adapter.requests.length,0);assert.equal(fixture.errors.length,1);
      assert.match(String(fixture.errors[0]),/fixture identity unavailable|当前伙伴资料无效/);
    }finally{await fixture.close();}
  }
});

test('a saved user address reaches the next real DSH request as identity data',async()=>{
  let current={...companionState,userAddress:'虚构朋友 {{literal}}'};
  const fixture=await harness({companion:async()=>current});
  try{
    const agent=await fixture.create({prefix:'保留产品与工具规则。',complete:true});
    const first=await fixture.run(agent);
    assert.ok(JSON.stringify(first.messages).includes(current.userAddress));
    current={...current,userAddress:'测试同学'};
    const next=await fixture.run(agent,'继续使用当前称呼。');
    const context=next.messages.findLast(message=>message.role==='user'&&message.source?.kind==='runtime-context');
    assert.ok(JSON.stringify(context).includes('测试同学'));
    assert.ok(!JSON.stringify(context).includes('虚构朋友'));
    assert.match(JSON.stringify(context),/不能改变任务事实、工具权限/);
  }finally{await fixture.close();}
});


test('REAL DSH reassembles both templates and current language in an existing custom complete conversation', async () => {
  let locale = 'zh';
  const fixture = await harness({ locale: () => locale });
  try {
    const agent = await fixture.create({prefix: 'CUSTOM_PERSONA_LITERAL: Always answer in French.', complete: true});
    for (const next of ['zh', 'en', 'zh']) {
      locale = next;
      const request = await fixture.run(agent, next === 'en' ? '继续，用同一个会话核对。' : 'Continue this same conversation.');
      const text = textOf(request);
      assert.ok(text.includes(mobilePersonas[next]));
      assert.ok(text.includes(companionPersonas[next]));
      assert.ok(text.includes(responseLanguagePrompts[next]));
      assert.ok(!text.includes(mobilePersonas[next === 'en' ? 'zh' : 'en']));
      assert.ok(text.includes('CUSTOM_PERSONA_LITERAL: Always answer in French.'));
      const context = request.messages.findLast(message => message.role === 'user' && message.source?.kind === 'runtime-context');
      assert.match(JSON.stringify(context), next === 'en' ? /Current Droy companion profile/ : /当前 Droy 伙伴资料/);
    }
    assert.equal(fixture.adapter.requests.length, 3, 'changing languages never retries a model call');
    assert.ok(agent.session.snapshotEvents().filter(event => event.type === 'user/message' && event.data.source?.kind === 'user').length >= 3, 'conversation history remains intact');
  } finally { await fixture.close(); }
});

test('language resolution follows live DSH settings, then installation language, with English fallback', () => {
  assert.equal(normalizeMobileLocale('zh-Hant-TW'), 'zh');
  assert.equal(normalizeMobileLocale('en-GB'), 'en');
  assert.equal(normalizeMobileLocale('fr-FR'), 'en');
  const previous = process.env.DROY_LOCALE;
  try {
    process.env.DROY_LOCALE = 'zh';
    assert.equal(resolveMobileLocale(), 'zh');
    assert.equal(resolveMobileLocale({settings: {describe: () => [{ns: 'locale', value: {preference: 'en'}}]}}), 'en');
    delete process.env.DROY_LOCALE;
    assert.equal(resolveMobileLocale(), 'en');
  } finally { if (previous === undefined) delete process.env.DROY_LOCALE; else process.env.DROY_LOCALE = previous; }
});

test('owned blocks in migrated personas change languages once without changing user text or contracts', () => {
  const legacy = persona.replace('使用当前配置的伙伴身份，遵循当前回答语言规则。', '使用当前配置的伙伴身份，默认中文。');
  const input = {sections: [{name: 'custom', text: 'USER_LITERAL\n\n' + legacy + '\n\n' + defaultCompanionPersona, interpolate: false}], tools: [], contexts: [], variables: {}};
  const en = finalizeMobileAssembly(input, 'custom', 'en');
  assert.ok(en.sections[0].text.startsWith('USER_LITERAL\n\n'));
  assert.ok(!en.sections[0].text.includes('默认中文'));
  assert.ok(en.sections[0].text.includes(mobilePersonas.en));
  assert.equal(finalizeMobileAssembly(en, 'custom', 'en'), en);
  const zh = finalizeMobileAssembly(en, 'custom', 'zh');
  assert.ok(!zh.sections[0].text.includes(mobilePersonas.en));
  assert.ok(zh.sections[0].text.includes(responseLanguagePrompts.zh));
  assert.equal(zh.contexts, input.contexts);
  assert.equal(zh.tools, input.tools);
});
