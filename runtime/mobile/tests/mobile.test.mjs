import test from 'node:test';
import assert from 'node:assert/strict';
import { authorized, requireMobileSession } from '../auth.mjs';
import { createBridge } from '../bridge.mjs';
import { encodePng, decodePng, resizePng } from '../png.mjs';
import { publishExclusive } from '../publish.mjs';
import { mkdtemp, writeFile, readFile, readdir, rm } from 'node:fs/promises';
import { linkSync, unlinkSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
const token = 'fictional-test-session-token-12345678';
const request = extra => ({headers:{host:'127.0.0.1:3030',cookie:`droy_session=${token}`,...extra}});
const toolStarted = () => new Promise(resolve => setImmediate(resolve));
test('cookie is required on all routes and tied to loopback origin', () => {
  assert.equal(authorized(request(),token),true);
  for(const headers of [{cookie:''},{cookie:`droy_session=wrong`},{cookie:`droy_session=${token}; droy_session=${token}`},{host:'attacker.test:3030'},{origin:'https://attacker.test'},{origin:'null'}]) assert.equal(authorized(request(headers),token),false);
  assert.equal(authorized(request(),undefined),false);
  assert.equal(authorized(request(),''),false);
});
test('bridge identity is engine owned and cancellation is acknowledged', async () => {
  const calls=[]; const controller=new AbortController();
  const bridge=createBridge({port:3031,token,fetchImpl:async (url,options)=>{
    calls.push({url,body:JSON.parse(options.body),auth:options.headers.Authorization});
    if(url.endsWith('/tool')) return await new Promise((_,reject)=>options.signal.addEventListener('abort',()=>reject(options.signal.reason),{once:true}));
    return {ok:true,json:async()=>({ok:true,result:{cancelled:true}})};
  }});
  const pending=bridge.call('tap',{taskId:'forged',x:1,y:2},{agent:{id:'engine-session'},callId:'call-1',signal:controller.signal});
  controller.abort(new Error('user stopped'));
  await assert.rejects(pending,/user stopped/);
  assert.equal(calls[0].body.taskId,'engine-session');
  assert.equal(calls[1].url,'http://127.0.0.1:3031/cancel');
  assert.deepEqual(calls[1].body,{taskId:'engine-session',callId:'call-1'});
  await assert.rejects(bridge.call('tap',{}, {signal:new AbortController().signal}),/IDENTITY_REQUIRED/);
});
test('native bridge errors remain failures',async()=>{
  const bridge=createBridge({port:3031,token,fetchImpl:async()=>({ok:true,json:async()=>({ok:false,error:{code:'STOPPED',message:'停止后需用户重新启用'}})})});
  await assert.rejects(bridge.call('observe',{}, {agent:{id:'s'},callId:'c',signal:new AbortController().signal}),/STOPPED/);
});
test('an unresponsive tool has a total budget even while the engine signal remains live',async t=>{
  t.mock.timers.enable({apis:['setTimeout']});
  const calls=[], controller=new AbortController();
  const bridge=createBridge({port:3031,token,fetchImpl:async(url,options)=>{
    calls.push({path:new URL(url).pathname,body:JSON.parse(options.body)});
    if(url.endsWith('/tool'))return await new Promise((_,reject)=>options.signal.addEventListener('abort',()=>reject(options.signal.reason),{once:true}));
    return {ok:true,json:async()=>({ok:true,result:{}})};
  }});
  const pending=bridge.call('tap',{}, {agent:{id:'s'},callId:'timed-out',signal:controller.signal});
  await toolStarted();
  t.mock.timers.tick(16000);
  await assert.rejects(pending,error=>error.code==='action_timeout'&&error.details.outcomeUnknown===true);
  assert.equal(controller.signal.aborted,false);
  assert.deepEqual(calls.map(c=>c.path),['/permissions/preflight','/tool','/cancel']);
  assert.equal(calls[1].body.budgetMs,15000);
});
test('a condition wait retains its requested budget and is cancelled without replay',async t=>{
  t.mock.timers.enable({apis:['setTimeout']});
  const controller=new AbortController(),calls=[];
  const bridge=createBridge({port:3031,token,fetchImpl:async(url,options)=>{
    calls.push({path:new URL(url).pathname,body:JSON.parse(options.body)});
    if(url.endsWith('/tool'))return await new Promise((_,reject)=>options.signal.addEventListener('abort',()=>reject(options.signal.reason),{once:true}));
    return {ok:true,json:async()=>({ok:true,result:{}})};
  }});
  const pending=bridge.call('wait_for',{timeoutMs:60000}, {agent:{id:'s'},callId:'long-wait',signal:controller.signal});
  await toolStarted();
  t.mock.timers.tick(16000);
  assert.equal(calls.length,2);
  assert.equal(calls[1].body.budgetMs,61500);
  controller.abort(new Error('user stopped'));
  await assert.rejects(pending,/user stopped/);
  assert.deepEqual(calls.map(c=>c.path),['/permissions/preflight','/tool','/cancel']);
});
test('unacknowledged cancellation and native rejection preserve actionable outcomes',async()=>{
  const controller=new AbortController();
  const bridge=createBridge({port:3031,token,fetchImpl:async(url,options)=>{
    if(url.endsWith('/cancel'))throw new Error('transport unavailable');
    return await new Promise((_,reject)=>options.signal.addEventListener('abort',()=>reject(options.signal.reason),{once:true}));
  }});
  const pending=bridge.call('tap',{}, {agent:{id:'s'},callId:'c',signal:controller.signal});
  controller.abort();
  await assert.rejects(pending,error=>error.code==='cancellation_unconfirmed'&&error.details.outcomeUnknown===true);
  const denied=createBridge({port:3031,token,fetchImpl:async()=>({ok:true,json:async()=>({ok:false,error:{code:'stale_snapshot',message:'请重新观察',reason:'target_changed',dispatched:false,outcomeUnknown:false}})})});
  await assert.rejects(denied.call('tap',{}, {agent:{id:'s'},callId:'c2',signal:new AbortController().signal}),error=>error.details.reason==='target_changed'&&/"dispatched":false/.test(error.message));
});
test('long compute keeps the existing service startup allowance',async()=>{
  let budget;
  const bridge=createBridge({port:3031,token,fetchImpl:async(_url,options)=>{
    budget=JSON.parse(options.body).budgetMs;
    return {ok:true,json:async()=>({ok:true,result:{status:'completed'}})};
  }});
  await bridge.call('compute_run',{timeoutMs:30000},{agent:{id:'s'},callId:'long-compute',signal:new AbortController().signal});
  assert.equal(budget,40000);
});
test('an explicit postcondition wait is included in the same action budget',async()=>{
  let budget;
  const bridge=createBridge({port:3031,token,fetchImpl:async(_url,options)=>{
    budget=JSON.parse(options.body).budgetMs;
    return {ok:true,json:async()=>({ok:true,result:{status:'action_completed',afterConditionStatus:'matched'}})};
  }});
  await bridge.call('tap',{afterCondition:{text:'结果'},afterTimeoutMs:60000},
    {agent:{id:'s'},callId:'postcondition',signal:new AbortController().signal});
  assert.equal(budget,65000);
});
test('a short sequence shares one deadline and cancellation never repeats its steps',async t=>{
  t.mock.timers.enable({apis:['setTimeout']});
  const calls=[], controller=new AbortController();
  const bridge=createBridge({port:3031,token,fetchImpl:async(url,options)=>{
    calls.push({path:new URL(url).pathname,body:JSON.parse(options.body)});
    if(url.endsWith('/tool'))return new Promise((_,reject)=>options.signal.addEventListener('abort',()=>reject(options.signal.reason),{once:true}));
    return {ok:true,json:async()=>({ok:true,result:{}})};
  }});
  const steps=[{action:'input_text',target:{nodeId:'n1'},text:'练习面包'},{action:'tap',target:{text:'搜索'},afterCondition:{text:'搜索结果'}}];
  const pending=bridge.call('sequence',{snapshotId:'current',packageName:'com.example.fixture',steps},
    {agent:{id:'s'},callId:'sequence-cancel',signal:controller.signal});
  await toolStarted();
  t.mock.timers.tick(16000);
  assert.equal(calls.length,2);
  assert.equal(calls[1].body.budgetMs,30000);
  assert.deepEqual(calls[1].body.args.steps,steps);
  controller.abort(new Error('user stopped'));
  await assert.rejects(pending,/user stopped/);
  assert.deepEqual(calls.map(call=>call.path),['/permissions/preflight','/tool','/cancel']);
});
test('PNG codec verifies pixels, corrupt bytes, limits, and request resizing',()=>{
  const source={width:4,height:3,channels:4,pixels:Buffer.from(Array.from({length:48},(_,i)=>i*3))};
  const png=encodePng(source), decoded=decodePng(png);
  assert.deepEqual(decoded,source);
  const resized=decodePng(encodePng(resizePng(decoded,2,1)));
  assert.equal(resized.width,2);assert.equal(resized.height,1);
  assert.deepEqual(resized.pixels.subarray(0,4),source.pixels.subarray(0,4));
  const corrupt=Buffer.from(png);corrupt[40]^=1;assert.throws(()=>decodePng(corrupt),/校验/);
  assert.throws(()=>decodePng(png.subarray(0,30)),/长度|截断/);
});
const noReplace=(a,b)=>{try{linkSync(a,b);unlinkSync(a);return 0;}catch(e){return Math.abs(e.errno);}};
test('atomic publication preserves source and exactly one concurrent writer wins',async()=>{
  const dir=await mkdtemp(join(tmpdir(),'droy-publish-'));
  try{
    const sources=Array.from({length:8},(_,i)=>join(dir,`source-${i}`)),dest=join(dir,'final');
    await Promise.all(sources.map((s,i)=>writeFile(s,Buffer.alloc(100_000,i))));
    const results=await Promise.allSettled(sources.map(s=>publishExclusive(s,dest,noReplace)));
    assert.equal(results.filter(r=>r.status==='fulfilled').length,1);
    for(const result of results.filter(r=>r.status==='rejected'))assert.equal(result.reason.code,'EEXIST');
    const data=await readFile(dest);assert.equal(data.length,100_000);assert.ok(data.every(x=>x===data[0]));
    for(const path of sources)assert.equal((await readFile(path)).length,100_000);
    assert.equal((await readdir(dir)).filter(x=>x.endsWith('.tmp')).length,0);
  }finally{await rm(dir,{recursive:true});}
});
test('process death before or after atomic commit never exposes a partial destination',async()=>{
  const dir=await mkdtemp(join(tmpdir(),'droy-crash-'));
  try{
    const src=join(dir,'source');await writeFile(src,Buffer.alloc(256_000,123));
    for(const after of [false,true]){
      const dest=join(dir,after?'after':'before');
      const script=`import {publishExclusive} from ${JSON.stringify(new URL('../publish.mjs',import.meta.url).href)};import{linkSync,unlinkSync}from'node:fs';await publishExclusive(${JSON.stringify(src)},${JSON.stringify(dest)},(a,b)=>{${after?'linkSync(a,b);unlinkSync(a);':''}process.exit(42)});`;
      const result=spawnSync(process.execPath,['--input-type=module','-e',script]);assert.equal(result.status,42);
      if(after)assert.deepEqual(await readFile(dest),await readFile(src));else await assert.rejects(readFile(dest),{code:'ENOENT'});
    }
    assert.equal((await readFile(src)).length,256_000);
  }finally{await rm(dir,{recursive:true});}
});
