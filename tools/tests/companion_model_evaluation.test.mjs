import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile,writeFile,mkdtemp,rm,copyFile,mkdir,readdir,stat,symlink } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { fileURLToPath,pathToFileURL } from 'node:url';
import { setTimeout as delay } from 'node:timers/promises';
import { execFile } from 'node:child_process';
import { promisify } from 'node:util';
import { companionEvaluationCases,runCompanionEvaluation } from '../companion-model-evaluation.mjs';
import { prepareEvaluationHome } from '../../app/src/androidTest/assets/companion-model-evaluation-prepare.mjs';

test('instrumentation evaluates the same bounded fictional case builder',async()=>{
  const source=await readFile(new URL('../companion-model-evaluation.mjs',import.meta.url),'utf8');
  const asset=await readFile(new URL('../../app/src/androidTest/assets/companion-model-evaluation-core.mjs',import.meta.url),'utf8');
  assert.equal(asset,source);
});

test('evaluation uses bilingual fictional cases and current sticker semantics',()=>{
  const cases=companionEvaluationCases();
  assert.equal(cases.length,16);
  assert.equal(new Set(cases.map(item=>item.id)).size,16);
  assert.ok(cases.filter(item=>item.locale==='en').length===8);
  const custom=cases.find(item=>item.id==='en-persona-custom');
  assert.match(custom.request.system,/quiet male programmer/u);
  assert.doesNotMatch(custom.request.system,/female university student/u);
  assert.match(JSON.stringify(cases.find(item=>item.id==='en-sticker').request),/curiosity|puzzlement/u);
});

test('model evaluation respects its invocation limit and does not retry failures',async()=>{
  let count=0;
  const result=await runCompanionEvaluation({maxRequests:3,invoke:async(_request,controls)=>{
    count++;assert.equal(controls.allowRetries,false);
    if(count===2)throw Object.assign(new Error('provider secret must not be logged'),{code:'FICTIONAL_FAILURE'});
    return {text:'Fictional response',usage:{outputTokens:2}};
  }});
  assert.equal(count,3);assert.equal(result.requests,3);assert.equal(result.remainingCases,13);
  assert.equal(result.results[1].errorCode,'FICTIONAL_FAILURE');
  assert.doesNotMatch(JSON.stringify(result),/provider secret/u);
  await assert.rejects(runCompanionEvaluation({maxRequests:61,invoke:async()=>({})}),/EVALUATION_INVALID_INPUT/u);
});

test('in-app evaluator reserves retry budget and leaves existing timers suspended',async t=>{
  const directory=await mkdtemp(join(tmpdir(),'droy-model-eval-'));
  const previous=process.env.DROY_RUNTIME_DIR;
  process.env.DROY_RUNTIME_DIR=fileURLToPath(new URL('../../runtime/dsh/',import.meta.url));
  t.after(async()=>{if(previous===undefined)delete process.env.DROY_RUNTIME_DIR;else process.env.DROY_RUNTIME_DIR=previous;await rm(directory,{recursive:true,force:true});});
  await copyFile(new URL('../../app/src/androidTest/assets/companion-model-evaluation-plugin.mjs',import.meta.url),join(directory,'plugin.mjs'));
  const core=await readFile(new URL('../companion-model-evaluation.mjs',import.meta.url),'utf8');
  const prefix=new URL('../../runtime/mobile/',import.meta.url).href;
  await writeFile(join(directory,'evaluation-core.mjs'),core.replaceAll("'../runtime/mobile/","'"+prefix));
  await writeFile(join(directory,'expected-model.json'),JSON.stringify({provider:'fictional',model:'current'}));
  let calls=0,suspended=false;
  const ctx={effect:()=>{},agents:{list:()=>[]},agentDefaultModel:{currentSelection:()=>({provider:'fictional',model:'current'})},
    get:name=>name==='droyCompanionStartup'?{phase:'ready'}:{maintenance:{acquire:()=>({suspend:async()=>{suspended=true;return {quiet:true};},resume:()=>assert.fail('Do not resume unrelated timers before shutdown')})}},
    llm:{prepareCall:async model=>({config:model,retryPolicy:{mode:'normal',maxRetries:19},async *stream(){calls++;yield {type:'text-delta',text:'Fictional response'};yield {type:'finish',reason:{kind:'stop'}};}})}};
  const plugin=await import(pathToFileURL(join(directory,'plugin.mjs')));plugin.apply(ctx);
  let complete=false;
  for(let i=0;i<200;i++) {try{await readFile(join(directory,'done'));complete=true;break;}catch(error){if(error.code!=='ENOENT')throw error;}await delay(10);}
  assert.equal(complete,true);const report=JSON.parse(await readFile(join(directory,'result.json'),'utf8'));
  assert.equal(suspended,true);assert.equal(report.status,'finished');assert.equal(report.requests,2);
  assert.equal(report.reservedAttempts,40);assert.equal(report.previousInterruptedAttemptReservation,20);
  assert.equal(report.reservedAttempts+report.previousInterruptedAttemptReservation,60);
  assert.equal(calls,2);assert.deepEqual(report.model,{provider:'fictional',model:'current'});
});

test('isolated preparation copies only model configuration and opaque app credentials, never jobs or histories',async t=>{
  const directory=await mkdtemp(join(tmpdir(),'droy-eval-isolation-'));t.after(()=>rm(directory,{recursive:true,force:true}));
  const original=join(directory,'original'),home=join(directory,'home'),files=join(directory,'files');
  await mkdir(join(original,'profiles/mobile'),{recursive:true});await mkdir(home);await mkdir(files);
  await writeFile(join(original,'profiles/mobile/cordis.patch.yml'),JSON.stringify([
    {id:'agent-default-model',config:{provider:'fixture-provider',model:'profile-model',reasoningEffort:'high'}},
    {id:'llm-pi-ai',config:{providers:{'fixture-provider':{apiKeyEnv:'FICTIONAL_KEY_REF',models:['chosen-model']}}}},
    {insert:[{id:'private-plugin',name:'/fictional/private-plugin.mjs'},{id:'agent-loop',config:{agents:[{id:'pending-private-work'}]}}]},
    {id:'schedules',config:{tasks:[{id:'private-schedule'}]}}]));
  await writeFile(join(original,'cordis.patch.yml'),JSON.stringify([{id:'agent-default-model',config:{provider:'fixture-provider',model:'chosen-model'}}]));
  await writeFile(join(original,'.credentials.yaml'),'fictional opaque credential bytes');
  await writeFile(join(original,'private-chat.json'),'fictional private chat must remain in original');
  await writeFile(join(files,'companion-model-evaluation-core.mjs'),"import x from '../runtime/mobile/example.mjs';");
  const runtime=fileURLToPath(new URL('../../runtime/dsh/',import.meta.url));
  await prepareEvaluationHome({runtime,original,home,directory:files});
  const patch=await readFile(join(home,'profiles/mobile/cordis.patch.yml'),'utf8');
  assert.match(patch,/fixture-provider/u);assert.doesNotMatch(patch,/private-plugin|private-schedule|pending-private-work/u);
  assert.deepEqual(JSON.parse(await readFile(join(files,'expected-model.json'),'utf8')),{provider:'fixture-provider',model:'chosen-model'});
  assert.equal(await readFile(join(original,'.credentials.yaml'),'utf8'),await readFile(join(home,'.credentials.yaml'),'utf8'));
  assert.equal((await stat(join(home,'.credentials.yaml'))).mode&0o077,0);
  assert.deepEqual((await readdir(home)).sort(),['.credentials.yaml','profiles']);
  assert.equal(await readFile(join(original,'private-chat.json'),'utf8'),'fictional private chat must remain in original');
  await assert.rejects(prepareEvaluationHome({runtime,original,home:original,directory:files}),/EVALUATION_HOME_NOT_ISOLATED/u);
  await assert.rejects(prepareEvaluationHome({runtime,original,home,directory:files}),/EVALUATION_HOME_NOT_EMPTY/u);
});

test('a replacement default-model config cannot inherit a missing provider from an earlier layer',async t=>{
  const directory=await mkdtemp(join(tmpdir(),'droy-eval-replacement-'));t.after(()=>rm(directory,{recursive:true,force:true}));
  const original=join(directory,'original'),home=join(directory,'home'),files=join(directory,'files');
  await mkdir(join(original,'profiles/mobile'),{recursive:true});await mkdir(home);await mkdir(files);
  await writeFile(join(original,'profiles/mobile/cordis.patch.yml'),JSON.stringify([
    {id:'agent-default-model',config:{provider:'fictional-profile-provider',model:'old-model',reasoningEffort:'high'}}]));
  await writeFile(join(original,'cordis.patch.yml'),JSON.stringify([{id:'agent-default-model',config:{model:'new-model'}}]));
  const runtime=fileURLToPath(new URL('../../runtime/dsh/',import.meta.url));
  await assert.rejects(prepareEvaluationHome({runtime,original,home,directory:files}),/EVALUATION_CURRENT_MODEL_REQUIRED/u);
  assert.deepEqual(await readdir(home),[],'Reject invalid replacement before copying any configuration or credentials');
});

test('preparer CLI executes through a filesystem alias and writes its required launch artifacts',async t=>{
  const directory=await mkdtemp(join(tmpdir(),'droy-eval-alias-'));t.after(()=>rm(directory,{recursive:true,force:true}));
  const original=join(directory,'original'),home=join(directory,'home'),files=join(directory,'files');
  await mkdir(original);await mkdir(home);await mkdir(files);
  await writeFile(join(files,'companion-model-evaluation-core.mjs'),'export const fictionalProbe=true;');
  const target=fileURLToPath(new URL('../../app/src/androidTest/assets/companion-model-evaluation-prepare.mjs',import.meta.url));
  const alias=join(directory,'aliased-prepare.mjs');await symlink(target,alias);
  const runtime=fileURLToPath(new URL('../../runtime/dsh/',import.meta.url));
  const output=await promisify(execFile)(process.execPath,[alias,runtime,original,home,files],{timeout:10000});
  assert.equal(output.stderr,'');
  const selected=JSON.parse(await readFile(join(files,'expected-model.json'),'utf8'));
  assert.equal(typeof selected.provider,'string');assert.ok(selected.provider.length>0);
  assert.equal(await readFile(join(files,'evaluation-core.mjs'),'utf8'),'export const fictionalProbe=true;');
  assert.match(await readFile(join(home,'profiles/mobile/cordis.patch.yml'),'utf8'),/companion-current-model-evaluation/u);
});
