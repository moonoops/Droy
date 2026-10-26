import test from 'node:test';
import assert from 'node:assert/strict';
import { fork } from 'node:child_process';
import { createServer } from 'node:http';
import { mkdtemp, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { companionState } from './fixtures/companion-state.mjs';
const token='fictional-persistence-fixture-token-123456789';
const fixture=fileURLToPath(new URL('./fixtures/persistent-agent.mjs',import.meta.url));
function deferred(){let resolve,reject;const promise=new Promise((a,b)=>{resolve=a;reject=b;});return {promise,resolve,reject};}
async function setup(completed=false){
  const root=await mkdtemp(join(tmpdir(),'droy-real-jsonl-'));const calls=[];const started=deferred(),cancelled=deferred();const children=[];
  const bridge=createServer(async(req,res)=>{
    assert.equal(req.headers['content-type'],'application/json; charset=utf-8');
    let body='';for await(const chunk of req)body+=chunk;
    const call={path:req.url,...JSON.parse(body)};
    if(req.url==='/companion'){
      res.writeHead(200,{'content-type':'application/json'});res.end(JSON.stringify({ok:true,result:companionState}));return;
    }
    calls.push(call);
    if(req.url==='/tool'){started.resolve();if(!completed)await cancelled.promise;}
    if(req.url==='/cancel')cancelled.resolve();
    if(!res.destroyed){res.writeHead(200,{'content-type':'application/json'});res.end(JSON.stringify({ok:true,result:{submitted:true}}));}
  });
  await new Promise(r=>bridge.listen(0,'127.0.0.1',r));
  function launch(mode){
    const child=fork(fixture,[mode,root],{silent:true,env:{...process.env,DROY_WEB_TOKEN:token,DROY_BRIDGE_TOKEN:token,DROY_BRIDGE_PORT:String(bridge.address().port)}});children.push(child);
    const messages=[];const waiters=[];let stderr='';child.stderr.on('data',data=>stderr+=data);child.stdout.resume();
    child.on('message',message=>{messages.push(message);for(const waiter of waiters)if(waiter.type===message.type)waiter.resolve(message);});
    const done=new Promise(resolve=>child.once('exit',(code,signal)=>{resolve({code,signal});for(const waiter of waiters)if(!messages.some(m=>m.type===waiter.type))waiter.reject(new Error(`Fixture exited ${code}/${signal}: ${stderr}`));}));
    return {child,done,wait(type){const found=messages.find(m=>m.type===type);if(found)return Promise.resolve(found);const waiter={type,...deferred()};waiters.push(waiter);return waiter.promise;}};
  }
  return {calls,started,launch,async close(){cancelled.resolve();for(const child of children)if(child.exitCode===null&&child.signalCode===null)child.kill('SIGKILL');bridge.closeAllConnections();await new Promise(r=>bridge.close(r));await rm(root,{recursive:true,force:true});}};
}
for(const scenario of ['SIGKILL during tool','graceful active cancellation','graceful completed turn'])test(`REAL DSH JSONL ${scenario} resumes without automatic model/tool replay (MOCK provider/bridge)`,{timeout:20000},async(t)=>{
  const fixture=await setup(scenario==='graceful completed turn');
  // A test timeout does not unwind a callback awaiting IPC; the hook must own cleanup.
  t.after(()=>fixture.close());
    const original=fixture.launch('run');const ready=await original.wait('ready');await fixture.started.promise;
    if(scenario==='SIGKILL during tool') {original.child.kill('SIGKILL');assert.equal((await original.done).signal,'SIGKILL');}
    else {
      if(scenario==='graceful completed turn')await original.wait('idle');
      const url=`http://127.0.0.1:${ready.port}/droy/shutdown`;
      assert.equal((await fetch(url,{method:'POST'})).status,401);
      const response=await fetch(url,{method:'POST',headers:{cookie:`droy_session=${token}`}});
      assert.equal(response.status,200);assert.deepEqual(await response.json(),{ok:true,code:'DROY_SHUTDOWN_READY'});
      assert.equal((await original.done).code,0);
      if(scenario==='graceful active cancellation')assert.ok(fixture.calls.some(c=>c.path==='/cancel'));
    }
    const before=fixture.calls.length;const resumed=fixture.launch('resume');const recovered=await resumed.wait('recovered');assert.equal((await resumed.done).code,0);
    assert.equal(recovered.modelCalls,0);assert.equal(fixture.calls.length,before);
    const events=recovered.events;assert.ok(events.some(e=>e.type==='tool/call'));
    if(scenario==='SIGKILL during tool'){
      assert.ok(events.some(e=>e.type==='tool/result'&&e.data.error?.code==='TOOL_OUTCOME_UNKNOWN'));
      assert.ok(events.some(e=>e.type==='turn/end'&&e.data.reason.kind==='interrupted'));
    } else {
      assert.ok(!events.some(e=>e.type==='turn/end'&&e.data.reason.kind==='interrupted'));
      if(scenario==='graceful completed turn')assert.match(JSON.stringify(events),/MOCK final answer retained/);
      else assert.ok(events.some(e=>e.type==='turn/end'&&e.data.reason.kind==='aborted'&&e.data.reason.reason.kind==='disposed'));
    }
});
