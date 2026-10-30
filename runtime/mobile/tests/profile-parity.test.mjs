import test from 'node:test';
import assert from 'node:assert/strict';
import { fileURLToPath } from 'node:url';
import { readUpstreamProfile, mobileProfilePatches, serializeMobileProfile, parsePatch, applyEntryPatches, assertNoToolResultPruner } from '../../../tools/mobile_profile.mjs';
import { persona } from '../profile.mjs';

const source=fileURLToPath(new URL('../../dsh/node_modules/',import.meta.url));
const upstream=await readUpstreamProfile(source);
const patches=mobileProfilePatches(upstream);
const mobile=applyEntryPatches(upstream,patches,message=>assert.fail(message));
const byId=new Map(mobile.map(row=>[row.id,row]));
const isPreset=row=>row.name==='@deepseek-ai/dsh-agent-preset';

test('mobile retains upstream capabilities and registers only the two selected built-in modes',()=>{
  assert.deepEqual(parsePatch(serializeMobileProfile(upstream)),patches,'!!js conditions must round-trip');
  for(const row of upstream){
    const actual=byId.get(row.id);
    assert.ok(actual,`${row.id} must be inherited`);
    assert.equal(actual.name,row.name,`${row.id} module identity`);
    if(isPreset(row)&&!['standard','cordis'].includes(row.config.id)){
      assert.equal(actual.disabled,true,`${row.id} is not a Droy built-in mode`);
    }else if(row.id==='attachment-local'){
      assert.equal(actual.disabled,true);
      assert.equal(byId.get('droy-attachments')?.name,'@droyagent/mobile/attachments');
    }else if(['subprocess','fs-sandbox'].includes(row.id)){
      assert.equal(actual.disabled,true,'Droy provides the execution provider with an inactive upstream path');
      assert.equal(byId.get('droy-execution')?.name,'@droyagent/execution');
    }else if(row.id==='ui-brand-official'){
      assert.equal(actual.disabled,true,'Droy supplies the product brand slots');
      assert.equal(byId.get('droy-android-use')?.name,'@droyagent/mobile');
    }else if(row.id==='ui-sidebar-browser')assert.equal(actual.disabled,false);
    else assert.deepEqual(actual.disabled,row.disabled,`${row.id} enablement`);
  }
  assert.equal(byId.get('agent-preset-registry').config.default,'standard');
  assert.deepEqual(mobile.filter(row=>isPreset(row)&&row.disabled!==true).map(row=>row.config.id).sort(),['cordis','standard']);
  assert.equal(byId.has('preset-mobile'),false,'development builds do not carry a legacy mode alias');
  assert.equal(byId.get('droy-notifications')?.name,'@droyagent/mobile/notifications');
  assert.equal(byId.get('droy-media-input')?.name,'@droyagent/media-input');
  assert.equal(byId.get('droy-memory-host')?.name,'@droyagent/mobile/memory-host');
  assert.equal(byId.get('droy-privacy')?.name,'@droyagent/privacy');
  assert.equal(byId.get('droy-memory')?.name,'@droyagent/memory');
  assert.equal(byId.get('droy-voice')?.name,'@droyagent/voice');
  assert.equal(byId.get('droy-attachments').config.maxImagesPerMessage,10);
  assert.equal(byId.get('droy-notifications')?.disabled,undefined);
  assert.equal(byId.get('speech-to-text')?.config.defaultProvider,'droy-sensevoice-local');
  assert.equal(byId.get('api-speech-to-text')?.disabled,undefined);
  assert.notEqual(byId.get('ui-voice-input')?.disabled,true);
  assert.equal(byId.has('speech-to-text-sensevoice'),false,'Android uses the native recognizer rather than desktop Node binaries');
  assert.equal(byId.get('droy-skills')?.name,'@deepseek-ai/dsh-skill-filesystem');
  assert.equal(byId.get('droy-skills').config.providerName,'droy-android');
  assert.equal(byId.get('droy-skills').config.includeDefaultRoots,false);
  assert.deepEqual(byId.get('skill-filesystem'),upstream.find(row=>row.id==='skill-filesystem'));
  for(const id of ['plugin-manager','ui-plugin-manager','schedule','ui-schedule','skill','mcp-resources','terminal-controller','ui-sidebar-terminal','ui-permission']){
    assert.ok(byId.has(id),`${id} must remain available`);
    assert.notEqual(byId.get(id).disabled,true,`${id} must not be hidden`);
  }
});

function undoAndroidAdaptation(rows,original){
  for(let i=0;i<rows.length;i++){
    const row=rows[i],source=original[i];
    if(row.name==='@deepseek-ai/dsh-persona'){
      const replacement=source.config.prefix==='You are a coding agent powered by the {{model}} model.'
        ? 'The model configured for this session is {{model}}.'
        : source.config.prefix==='You are a helpful software engineer assistant.' ? '' : source.config.prefix;
      assert.equal(row.config.prefix,replacement);
      assert.deepEqual(row.config.suffix,source.config.suffix,'assembly owns product rules, not persisted persona suffixes');
      row.config.prefix=source.config.prefix;
    }
    if(row.name==='@deepseek-ai/dsh-terminal-bash'&&row.config?.shellDialect!=='pwsh'){
      assert.deepEqual(row.config.shellPath,{__jsExpr:'process.env.DROY_BASH_PATH'});
      if(source.config?.shellPath===undefined)delete row.config.shellPath;
      else row.config.shellPath=source.config.shellPath;
    }
    if(row.name==='@deepseek-ai/dsh-compaction-tool-result-pruner'){
      assert.equal(row.disabled,true);
      if(source.disabled===undefined)delete row.disabled;else row.disabled=source.disabled;
    }
    if(row.group)undoAndroidAdaptation(row.config,source.config);
  }
}

test('standard and creator preserve tool contracts while replacing the default coding identity',()=>{
  for(const row of upstream.filter(row=>isPreset(row)&&['standard','cordis'].includes(row.config.id))){
    const actual=structuredClone(byId.get(row.id));
    undoAndroidAdaptation(actual.config.plugins,row.config.plugins);
    assert.deepEqual(actual,row,`${row.id} must not be reduced`);
  }
  for(const prohibition of ['不执行 shell','不安装插件','计算仅用'])assert.ok(!persona.includes(prohibition));
});

test('head/tail pruning is disabled throughout every selected preset and rejected if reenabled',()=>{
  assert.doesNotThrow(()=>assertNoToolResultPruner(mobile));
  const rogue={id:'rogue-pruner',name:'@deepseek-ai/dsh-compaction-tool-result-pruner'};
  assert.doesNotThrow(()=>assertNoToolResultPruner([{id:'unused-group',name:'cordis:group',group:true,disabled:true,config:[rogue]}]),'an inactive composition cannot activate its pruner');
  assert.throws(()=>assertNoToolResultPruner([{id:'future-group',name:'cordis:group',group:true,config:[rogue]}]),/prohibited/);
  const future=structuredClone(upstream);
  future.push({id:'future-pruner-group',name:'cordis:group',group:true,config:[rogue]});
  const generated=applyEntryPatches(future,mobileProfilePatches(future),message=>assert.fail(message));
  assert.equal(generated.find(row=>row.id==='future-pruner-group').config[0].disabled,true);
});

test('mobile inherits native compaction defaults and preserves configured upstream policies unchanged',()=>{
  const compactions=rows=>rows.flatMap(row=>[
    ...(row.name==='@deepseek-ai/dsh-compaction-basic'?[row]:[]),
    ...(row.group?compactions(row.config):[]),
    ...(isPreset(row)?compactions(row.config.plugins):[]),
  ]);
  assert.ok(compactions(upstream).length>0);
  assert.deepEqual(compactions(mobile),compactions(upstream),'no Droy compaction overrides at root, group or preset level');
  const custom=structuredClone(upstream);
  const standard=custom.find(row=>row.id==='preset-standard');
  const compaction=standard.config.plugins.find(row=>row.id==='compaction').config.find(row=>row.name==='@deepseek-ai/dsh-compaction-basic');
  const other={provider:'fixture-other',model:'deepseek-flash',thresholdRatio:0.7,retainRatio:0.12,headroomTokens:8192};
  compaction.config={auto:false,thresholdRatio:0.75,retainRatio:0.2,maxTokens:12000,compactionRetries:2,
    modelPolicies:[other,{provider:'deepseek-official',model:'deepseek-flash',retainRatio:0.1,
    summarizationProvider:'fixture-summary',summarizationModel:'fixed-summary',headroomTokens:8192}]};
  const generated=applyEntryPatches(custom,mobileProfilePatches(custom),message=>assert.fail(message));
  assert.deepEqual(compactions(generated),compactions(custom),'upstream and user policies remain owned by DSH');
});

test('user-edited personas and complete policies remain intact in selected presets',()=>{
  const edited=structuredClone(upstream);
  const custom=edited.find(row=>row.id==='preset-standard');
  const config=custom.config.plugins.find(row=>row.name==='@deepseek-ai/dsh-persona').config;
  Object.assign(config,{prefix:'自定义角色：我的旅行规划助手。',suffix:'用户补充规则。',complete:true,includeRuntimeContext:false});
  const generated=applyEntryPatches(edited,mobileProfilePatches(edited),message=>assert.fail(message));
  assert.deepEqual(generated.find(row=>row.id==='preset-standard').config.plugins.find(row=>row.name==='@deepseek-ai/dsh-persona').config,config);
});

test('upstream capabilities remain inherited while new built-in modes require an explicit product choice',()=>{
  const future=structuredClone(upstream);
  future.push({id:'future-capability',name:'@fixture/new-feature',config:{enabledByUpstream:true}});
  const preset=structuredClone(upstream.find(row=>row.id==='preset-standard'));
  preset.id='preset-future';preset.config.id='future';
  preset.config.plugins.push({id:'future-tool',name:'@fixture/new-tool',disabled:{__jsExpr:'false'}});
  future.push(preset);
  const generated=applyEntryPatches(future,mobileProfilePatches(future),message=>assert.fail(message));
  assert.deepEqual(generated.find(row=>row.id==='future-capability'),future.at(-2));
  assert.equal(generated.find(row=>row.id==='preset-future').disabled,true);
});


test('mobile deployment disables additional DeepSeek session upload and keeps local history persistence', () => {
  assert.equal(byId.get('session-log-deepseek').config.enabled, false);
  assert.notEqual(byId.get('session-persistence-jsonl').disabled, true);
  assert.equal(byId.get('session-persistence-jsonl').config.compression, 'none');
});


test('installed DeepSeek extension omits dsh_session_log with the deployed disabled flag', async () => {
  const plugin = await import('../../dsh/node_modules/@deepseek-ai/dsh-session-log-deepseek/lib/index.js');
  let extension;
  plugin.apply({ deepseekLlmApiExtensions: { register(name, value) {
    assert.equal(name, 'dsh_session_log'); extension = value;
  } }, sessions: { get() { throw new Error('Disabled upload must not read local history'); } } },
  { enabled: { get: () => byId.get('session-log-deepseek').config.enabled }, maxBytes: 16384 });
  assert.equal(extension.prepare({ sessionId: 'synthetic-session' }), undefined);
});
