import test from 'node:test';
import assert from 'node:assert/strict';
import { EventEmitter } from 'node:events';
import { createRequire } from 'node:module';
import { pathToFileURL } from 'node:url';
import * as Lifecycle from '../lifecycle.mjs';
const { apply }=Lifecycle;

const tick=()=>new Promise(resolve=>setImmediate(resolve));
const deferred=()=>Promise.withResolvers();
function fixture(t,{flush=async()=>{}}={}) {
  const events=new Map(),services=new Map(),agents=[],sessions=[{id:'fixture-session'}],calls={cancel:[],exit:[],flush:0};
  let handler,guard,busy=false,detached=false,jobs=[];
  const ctx={
    agents:{list:()=>agents},sessions:{list:()=>sessions,async flush(){calls.flush++;await flush();}},
    sessionPersistence:{async flush(){}},droyNativeUserTasks:{inspect:()=>({writers:detached?[{kind:'fixture-detached',done:new Promise(()=>{})}]:[],busy:[],unfenced:[]})},
    tools:{guard(value){guard=value;}},webServer:{register(route){handler=route.handler;}},appExit:code=>calls.exit.push(code),
    get:name=>services.get(name),provide(name,value){services.set(name,value);},effect(dispose){t.after(dispose());},
    on(name,listener){let listeners=events.get(name);if(!listeners)events.set(name,listeners=new Set());listeners.add(listener);return()=>listeners.delete(listener);},
  };
  services.set('jobs',{list:()=>jobs});services.set('droyCompanions',{activity:()=>({busy})});services.set('droyNativeUserTasks',ctx.droyNativeUserTasks);
  apply(ctx);
  const request=async(body='',options={})=>{
    const res=new EventEmitter();res.writeHead=status=>{res.status=status;};res.end=value=>{res.value=value?JSON.parse(value):null;res.emit('finish');};
    const req={method:'POST',headers:{},resume(){},async *[Symbol.asyncIterator](){if(body)yield Buffer.from(body);await options.bodyGate;}};
    await handler(req,res);return res;
  };
  const agent=()=>{const value={id:'fixture-agent-'+agents.length,status:'idle',inbox:{nextTurn:[],nextStep:[]},async whenIdle(){},cancel(){calls.cancel.push(this.id);this.status='idle';}};
    agents.push(value);for(const listener of events.get('agent/created')??[])listener({agent:value});return value;};
  return {ctx,calls,request,agent,setBusy(value){busy=value;},setDetached(value){detached=value;},setJobs(value){jobs=value;},
    guard:()=>guard(),llm:()=>[...(events.get('llm/stream')??[])][0]({},()=>true)};
}

test('conditional shutdown rechecks new partner work after an earlier idle observation',async t=>{
  const f=fixture(t);assert.equal(f.ctx.get('droyCompanions').activity().busy,false);
  f.setBusy(true);
  const res=await f.request('{"ifIdle":true}');
  assert.equal(res.status,409);assert.equal(res.value.code,'DROY_SHUTDOWN_BUSY');
  assert.deepEqual(f.calls.cancel,[]);assert.deepEqual(f.calls.exit,[]);assert.equal(f.calls.flush,0);
  assert.equal(f.ctx.get('droyLifecycle').stopping,false);
});

test('conditional shutdown observes work arriving while its request body is still being read',async t=>{
  const f=fixture(t),bodyGate=deferred();
  const pending=f.request('{"ifIdle":true}',{bodyGate:bodyGate.promise});
  await tick();f.setBusy(true);bodyGate.resolve();
  assert.equal((await pending).status,409);assert.deepEqual(f.calls.cancel,[]);assert.deepEqual(f.calls.exit,[]);
});

for(const kind of ['running-agent','queued-agent','detached-native','background-job'])test('conditional shutdown preserves '+kind,async t=>{
  const f=fixture(t);
  if(kind.endsWith('agent')){const agent=f.agent();if(kind==='running-agent')agent.status='running';else agent.inbox.nextTurn.push({id:'queued'});}
  if(kind==='detached-native')f.setDetached(true);
  if(kind==='background-job')f.setJobs([{id:'job-a',status:'running'}]);
  await tick();const response=await f.request('{"ifIdle":true}');
  assert.equal(response.status,409);assert.deepEqual(f.calls.cancel,[]);assert.deepEqual(f.calls.exit,[]);
});

test('a truly idle conditional shutdown fences admission before its first asynchronous flush and never cancels agents',async t=>{
  const started=deferred(),release=deferred();t.after(()=>release.resolve());
  const f=fixture(t,{flush:async()=>{started.resolve();await release.promise;}});f.agent();await tick();
  const pending=f.request('{"ifIdle":true}');await started.promise;
  assert.equal(f.ctx.get('droyLifecycle').stopping,true);assert.equal(f.guard(),'DROY_STOPPING');assert.throws(()=>f.llm(),/DROY_STOPPING/);
  assert.deepEqual(f.calls.cancel,[]);release.resolve();const response=await pending;
  assert.equal(response.status,200);assert.deepEqual(response.value,{ok:true,code:'DROY_SHUTDOWN_READY'});assert.deepEqual(f.calls.exit,[0]);
});

test('shutdown request bodies are strictly bounded and invalid requests never stop the host',async t=>{
  for(const body of ['[]','{"ifIdle":false}','{"ifIdle":true,"other":1}','not-json']) {
    const f=fixture(t);assert.equal((await f.request(body)).status,400);assert.equal(f.ctx.get('droyLifecycle').stopping,false);assert.deepEqual(f.calls.exit,[]);
  }
  const f=fixture(t);assert.equal((await f.request(' '.repeat(1025))).status,413);assert.deepEqual(f.calls.exit,[]);
});

test('explicit shutdown retains active cancellation and its original failure result',async t=>{
  const f=fixture(t,{flush:async()=>{throw new Error('Fictional persistence failure');}}),agent=f.agent();agent.status='running';await tick();
  const response=await f.request();
  assert.equal(response.status,500);assert.deepEqual(response.value,{ok:false,code:'DROY_SHUTDOWN_FAILED'});
  assert.deepEqual(f.calls.cancel,[agent.id]);assert.deepEqual(f.calls.exit,[1]);
});

test('conditional shutdown observes optional native work through a real strict Cordis lifecycle scope',async t=>{
  const installed=createRequire(new URL('../../dsh/package.json',import.meta.url));
  const {Context}=await import(pathToFileURL(installed.resolve('@deepseek-ai/cordis')).href);
  const ctx=new Context(),exits=[];let handler;
  for(const [name,value] of Object.entries({
    agents:{list:()=>[]},sessions:{list:()=>[]},sessionPersistence:{async flush(){}},llm:{},tools:{guard(){}},
    webServer:{register(route){handler=route.handler;}},appExit:code=>exits.push(code),
    droyCompanions:{activity:()=>({busy:false})},droyNativeUserTasks:{inspect:()=>({writers:[],busy:[],unfenced:[]})},jobs:{list:()=>[]},
  }))ctx.provide(name,value);
  t.after(()=>ctx.fiber.dispose());
  await ctx.plugin(Lifecycle);
  assert.equal(typeof handler,'function');
  const req={method:'POST',headers:{},resume(){},async *[Symbol.asyncIterator](){yield Buffer.from('{"ifIdle":true}');}};
  const res=new EventEmitter();res.writeHead=status=>{res.status=status;};res.end=value=>{res.value=JSON.parse(value);res.emit('finish');};
  await handler(req,res);
  assert.equal(res.status,200);assert.deepEqual(exits,[0]);assert.equal(ctx.get('droyLifecycle').stopping,true);
});

test('a missing native work observer cannot produce a successful idle exit',async t=>{
  const f=fixture(t),get=f.ctx.get;
  f.ctx.get=name=>name==='droyNativeUserTasks'?undefined:get(name);
  const response=await f.request('{"ifIdle":true}');
  assert.equal(response.status,500);assert.equal(f.ctx.get('droyLifecycle').stopping,false);assert.deepEqual(f.calls.exit,[]);
});
