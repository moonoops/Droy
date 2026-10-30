import test, { after } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, mkdir, readFile, writeFile, copyFile, symlink, rm, realpath, lstat, readdir } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { basename, dirname, join } from 'node:path';
import { createHash } from 'node:crypto';
import { spawn } from 'node:child_process';
import { once } from 'node:events';
import { createRequire, registerHooks } from 'node:module';
import { pathToFileURL } from 'node:url';
import { patchNativeCompanionMaintenance } from '../../../tools/native_companion_maintenance_patch.mjs';
import { MemoryStore } from '../../memory/store.mjs';
import { CompanionMemorySpaces } from '../companions/memory-spaces.mjs';
const installed=createRequire(new URL('../../dsh/package.json',import.meta.url));
const providerPath=installed.resolve('@deepseek-ai/dsh-session-persistence-jsonl');
const patched=await realpath(await mkdtemp(join(tmpdir(),'droy-delete-sdk-')));
await writeFile(join(patched,'index.mjs'),patchNativeCompanionMaintenance(await readFile(providerPath,'utf8')));
await copyFile(join(dirname(providerPath),'worker.cjs'),join(patched,'worker.cjs'));
await symlink(new URL('../../dsh/node_modules',import.meta.url),join(patched,'node_modules'),'dir');
const hooks=registerHooks({resolve(s,c,n){if(s.startsWith('@deepseek-ai/'))return {url:pathToFileURL(installed.resolve(s)).href,shortCircuit:true};return n(s,c);}});
const [{createCompanionDeletionMaintenance},{createOfflineJsonlProvider},Jsonl,{createUserMessage},{buildForkSeed},{CompanionStore}]=await Promise.all([
  import('../companions/maintenance-delete.mjs'),import('../companions/maintenance-sessions.mjs'),import(pathToFileURL(join(patched,'index.mjs')).href),
  import('@deepseek-ai/dsh-llm'),import('@deepseek-ai/dsh-session'),import('../companions/store.mjs')]);
hooks.deregister();after(()=>rm(patched,{recursive:true,force:true}));
const provider=createOfflineJsonlProvider(Jsonl),hash=value=>createHash('sha256').update(value).digest('hex');
const absent=path=>assert.rejects(lstat(path),{code:'ENOENT'});
const event=(text,seq=0)=>({type:'user/message',seq,time:seq+1,surfaceOp:'append',data:createUserMessage({content:[{type:'text',text}],source:{kind:'user'}})});
async function put(path,value){await mkdir(dirname(path),{recursive:true});await writeFile(path,value);}
async function session(root,header,events,inheritedEventCount=0){const opened=await provider({root,compression:'none'});try{const writer=await opened.store.create(header,{inheritedEventCount});try{await writer.append(events);await writer.flush();}finally{await writer.close();}}finally{await opened.close();}}
async function readSession(root,id){const opened=await provider({root,compression:'none'});try{const reader=await opened.store.open(id,'read');try{return {header:reader.header,...await reader.read()};}finally{await reader.close();}}finally{await opened.close();}}
async function fixture(t,options={}){
  const home=await realpath(await mkdtemp(join(tmpdir(),'droy-delete-home-')));t.after(()=>rm(home,{recursive:true,force:true}));
  const root=join(home,'droy-companions'),store=await CompanionStore.open({directory:root});
  const keep=(await store.profiles())[0].id,target=(await store.createProfile({requestId:'synthetic-request',templateId:'kaka',name:'Synthetic target',model:null})).profile.id;
  const targetPath=join(root,target),keepPath=join(root,keep);await mkdir(join(targetPath,'work'),{recursive:true});await mkdir(join(targetPath,'chat'));await mkdir(join(keepPath,'work'),{recursive:true});
  const memory=await MemoryStore.open({directory:join(targetPath,'memory'),initialize:true});
  await memory.record({operationId:'synthetic-memory',record:{kind:'fact',payload:{text:'SYNTHETIC_TARGET_MEMORY'},sourceRefs:['im:'+target+':remembered-source']}});await memory.close();
  await store.appendMessage(target,{id:'remembered-source',role:'user',type:'text',text:'SYNTHETIC_REFERENCED_SOURCE'});
  await store.appendMessage(target,{id:'unrelated-source',role:'user',type:'text',text:'SYNTHETIC_UNRELATED_SOURCE'});
  await put(join(keepPath,'work','keep.txt'),'keep');await store.updateProfile(target,{workspaceId:'target-workspace',chatSessionId:'target-chat'});
  await store.bindSession(target,{sessionId:'target-chat',workspaceId:'target-workspace',kind:'chat'});await store.bindSession(target,{sessionId:'target-work',workspaceId:'target-workspace',kind:'work',taskId:'target-task'});
  const blob=hash('synthetic-target-blob'),upload={id:'target-upload',name:'synthetic.png',mediaType:'image/png',bytes:21,ref:{attachmentId:'sha256:'+blob,bytes:21,mediaType:'image/png',width:2,height:2},url:'/droy/companions/'+target+'/attachments/target-upload'};
  await store.putUpload(target,upload);await store.updateMessage(target,'remembered-source',{attachments:[{id:'target-upload',name:'synthetic.png',mediaType:'image/png',bytes:21}]});
  await store.appendMessage(target,{id:'target-task',role:'assistant',type:'task',text:'SYNTHETIC_TARGET_TASK',task:{id:'target-task',sessionId:'target-work',state:'completed'}});await store.close();
  await put(join(home,'attachments','v1','objects',blob.slice(0,2),blob),'synthetic-target-blob');
  const ws=(path,sessionIds)=>({path,title:'Synthetic',sessionIds,createdAt:'2030',updatedAt:'2030'});
  await put(join(home,'storages','workspace.json'),JSON.stringify({unit:{name:'workspace',version:2},global:{initialized:true,workspaceIds:['target-workspace','keep-workspace'],archivedSessionIds:[],pinnedSessionIds:[]},tables:{workspaces:{'target-workspace':ws(join(targetPath,'work'),['target-work']),'keep-workspace':ws(join(keepPath,'work'),['keep-fork'])}}}));
  const sessionsRoot=join(home,'sessions'),header=(id,cwd,extra={})=>({version:4,id,cwd,createdAt:1,isSeeded:false,delegationDepth:0,...extra});
  const parentEvent=event('SYNTHETIC_PRIVATE_PARENT_BODY');
  await session(sessionsRoot,header('target-chat',join(targetPath,'chat')),[event('SYNTHETIC_PRIVATE_CHAT_BODY')]);
  await session(sessionsRoot,header('target-work',join(targetPath,'work')),[parentEvent]);
  await session(sessionsRoot,header('target-child',join(targetPath,'work'),{origin:'subagent',parentSession:'target-work',delegationDepth:1}),[event('SYNTHETIC_PRIVATE_CHILD_BODY')]);
  const seed=buildForkSeed([parentEvent],0);await session(sessionsRoot,header('keep-fork',join(keepPath,'work'),{parentSession:'target-work',isSeeded:true}),seed,1);
  const expected=await readSession(sessionsRoot,'keep-fork'),operationId='delete-op',admissionEpoch='delete-epoch';let held=true;
  const androidSnapshot={version:1,complete:true,operationId,companionId:target,admissionEpoch,retained:[],target:[{source:'native_companions:draft:'+target,value:{attachments:[upload]}}],retirementDigest:hash('synthetic-retirement-plan')};
  const authority={async assertHeld(){return {root:home,operationId,companionId:target,admissionEpoch,hostStopped:held,admissionsClosed:held};}};
  const config={home,operationId,companionId:target,admissionEpoch,authority,androidSnapshot,provider,...options};
  return {home,root,target,targetPath,keepPath,sessionsRoot,expected,config,create:extra=>createCompanionDeletionMaintenance({...config,...extra}),lose:()=>{held=false;}};
}
test('full local deletion removes descendants and keeps independent fork, then requires Android completion proof',async t=>{
  const f=await fixture(t),job=await f.create(),prepared=await job.prepare();assert.equal(prepared.phase,'prepared');assert.deepEqual(prepared.removedSessionIds,['target-chat','target-child','target-work']);
  const local=await job.commit();assert.equal(local.phase,'local-complete');assert.equal(local.companionDeletion,'incomplete');assert.deepEqual(local.pending,['android']);
  await absent(f.targetPath);assert.deepEqual(await readSession(f.sessionsRoot,'keep-fork'),f.expected);await assert.rejects(readSession(f.sessionsRoot,'target-child'));
  const spaces=await CompanionMemorySpaces.open({directory:f.root}),retained=await spaces.current(f.target);assert.equal(retained.state,'retained');
  const retainedMemory=await MemoryStore.open({directory:retained.directory});
  try{assert.equal((await retainedMemory.query()).items[0].payload.text,'SYNTHETIC_TARGET_MEMORY');}finally{await retainedMemory.close();}
  const evidence=await readFile(join(retained.directory,'..','evidence.json'),'utf8');assert.equal(evidence.includes('SYNTHETIC_REFERENCED_SOURCE'),true);assert.equal(evidence.includes('SYNTHETIC_UNRELATED_SOURCE'),false);
  assert.equal(await readFile(join(retained.directory,'..','evidence-blobs',hash('synthetic-target-blob')),'utf8'),'synthetic-target-blob');
  const journal=await readFile(join(f.home,'.companion-delete-delete-op.json'),'utf8');assert.equal(journal.includes('SYNTHETIC_PRIVATE'),false);assert.equal(journal.includes('SYNTHETIC_TARGET_MEMORY'),false);
  await assert.rejects(job.acknowledgeAndroid({}),{code:'MAINTENANCE_DELETE_ANDROID_RECEIPT_INVALID'});
  const done=await job.acknowledgeAndroid({version:1,scope:'android-companion-deletion',operationId:f.config.operationId,companionId:f.target,admissionEpoch:f.config.admissionEpoch,snapshotDigest:prepared.androidSnapshotDigest,retirementDigest:prepared.retirementDigest,complete:true});
  assert.equal(done.companionDeletion,'complete');assert.deepEqual(done.pending,[]);assert.equal((await(await f.create({androidSnapshot:undefined})).recover()).phase,'complete');
});
async function aliasFixture(t) {
  const f=await fixture(t), carrier=await mkdtemp(join(tmpdir(),'droy-home-alias-'));
  t.after(()=>rm(carrier,{recursive:true,force:true}));
  await symlink(dirname(f.home),join(carrier,'app-parent'),'dir');
  const aliasRoot=join(carrier,'app-parent',basename(f.home));
  f.config.trustedRootAlias={canonicalRoot:f.home,aliasRoot};
  const originals=await Promise.all(['target-chat','target-work','target-child','keep-fork'].map(id=>readSession(f.sessionsRoot,id)));
  await rm(f.sessionsRoot,{recursive:true});
  for(const row of originals)await session(f.sessionsRoot,{...row.header,cwd:aliasRoot+row.header.cwd.slice(f.home.length)},row.events,row.header.id==='keep-fork'?1:0);
  const workspacePath=join(f.home,'storages','workspace.json'), workspace=JSON.parse(await readFile(workspacePath,'utf8'));
  for(const row of Object.values(workspace.tables.workspaces))row.path=aliasRoot+row.path.slice(f.home.length);
  await writeFile(workspacePath,JSON.stringify(workspace));
  return {...f,aliasRoot,carrier,workspacePath,workspace};
}
test('explicit home ancestor alias proves ownership while retained session and workspace paths remain unchanged',async t=>{
  const f=await aliasFixture(t),expected=await readSession(f.sessionsRoot,'keep-fork');
  const job=await f.create();await job.prepare();
  await assert.rejects((await f.create({trustedRootAlias:undefined})).recover(),{code:'MAINTENANCE_DELETE_ROOT_ALIAS_CHANGED'});
  assert.equal((await(await f.create({androidSnapshot:undefined})).recover()).phase,'local-complete');
  await absent(f.targetPath);assert.deepEqual(await readSession(f.sessionsRoot,'keep-fork'),expected);
  const after=JSON.parse(await readFile(f.workspacePath,'utf8'));
  assert.deepEqual(after.tables.workspaces['keep-workspace'],f.workspace.tables.workspaces['keep-workspace']);
});
test('alias is explicit and cannot be the home symlink itself or point to a different physical home',async t=>{
  const f=await aliasFixture(t);
  await assert.rejects((await f.create({trustedRootAlias:undefined})).prepare(),{code:'MAINTENANCE_DELETE_SESSION_OWNER_UNPROVEN'});
  const direct=join(f.carrier,'direct-home');await symlink(f.home,direct,'dir');
  await assert.rejects(f.create({trustedRootAlias:{canonicalRoot:f.home,aliasRoot:direct}}),{code:'MAINTENANCE_DELETE_ROOT_ALIAS_INVALID'});
  await assert.rejects(f.create({home:direct}),{code:'MAINTENANCE_DELETE_LINKED_PATH'});
  await assert.rejects(f.create({trustedRootAlias:{canonicalRoot:f.home,aliasRoot:f.keepPath}}),{code:'MAINTENANCE_DELETE_ROOT_ALIAS_INVALID'});
  await assert.rejects(f.create({home:f.aliasRoot}),{code:'MAINTENANCE_DELETE_LINKED_PATH'});
});
test('alias-bound recovery succeeds after owned target directories have already been removed',async t=>{
  for(const checkpoint of ['owned:complete','owned'])await t.test(checkpoint,async child=>{
    const f=await aliasFixture(child),expected=await readSession(f.sessionsRoot,'keep-fork');let stopped=false;
    const job=await f.create({onCheckpoint(phase){if(phase===checkpoint&&!stopped){stopped=true;throw Error('synthetic-alias-interruption');}}});
    await job.prepare();await assert.rejects(job.commit(),/synthetic-alias-interruption/);assert.equal(stopped,true);await absent(f.targetPath);
    const recovered=await f.create({androidSnapshot:undefined});assert.equal((await recovered.recover()).phase,'local-complete');
    assert.deepEqual(await readSession(f.sessionsRoot,'keep-fork'),expected);
    const after=JSON.parse(await readFile(f.workspacePath,'utf8'));
    assert.deepEqual(after.tables.workspaces['keep-workspace'],f.workspace.tables.workspaces['keep-workspace']);
  });
});
test('home alias never bypasses missing cwd, home-descendant symlinks, or retained outside aliases into target',async t=>{
  for(const kind of ['missing','symlink','outside-retained'])await t.test(kind,async child=>{
    const f=await aliasFixture(child);
    if(kind==='missing')await rm(join(f.targetPath,'chat'),{recursive:true});
    if(kind==='symlink'){await rm(join(f.targetPath,'chat'),{recursive:true});await symlink(join(f.targetPath,'work'),join(f.targetPath,'chat'),'dir');}
    if(kind==='outside-retained'){
      const link=join(f.carrier,'external-reference');await symlink(join(f.targetPath,'work'),link,'dir');
      await session(f.sessionsRoot,{version:4,id:'outside-session',cwd:link,createdAt:1,isSeeded:false,delegationDepth:0},[event('Synthetic retained external cwd')]);
    }
    await assert.rejects((await f.create()).prepare(),{code:kind==='outside-retained'?'MAINTENANCE_OWNED_WORKSPACE_SHARED':'MAINTENANCE_DELETE_ROOT_ALIAS_PATH_INVALID'});
    assert.ok(await readSession(f.sessionsRoot,'target-work'));
  });
});
test('every destructive coordinator checkpoint resumes without rescanning deleted target data',async t=>{
  for(const stop of ['planning','owned:prepared','prepared','session:source-renamed','sessions','owned:applying','owned','store:deleted','local-complete'])await t.test(stop,async child=>{
    let stopped=false;const f=await fixture(child,{onCheckpoint(phase){if(!stopped&&phase===stop){stopped=true;throw Error('synthetic-interrupt');}}});
    const job=await f.create();try{await job.prepare();await job.commit();}catch(error){assert.equal(error.message,'synthetic-interrupt');}assert.equal(stopped,true);
    const recovered=await f.create({androidSnapshot:undefined,onCheckpoint(){}});assert.equal((await recovered.recover()).phase,'local-complete');await absent(f.targetPath);assert.deepEqual(await readSession(f.sessionsRoot,'keep-fork'),f.expected);
  });
});
test('retained Android hosted upload references protect a blob while corrupt snapshot and lost fence reject',async t=>{
  const f=await fixture(t);f.config.androidSnapshot.retained.push({source:'native_workbench:pending',value:{url:'/droy/companions/'+f.target+'/attachments/target-upload'}});
  const job=await f.create();await job.prepare();f.lose();await assert.rejects(job.commit(),{code:'MAINTENANCE_DELETE_AUTHORITY_LOST'});
  const g=await fixture(t);g.config.androidSnapshot.complete=false;await assert.rejects((await g.create()).prepare(),{code:'MAINTENANCE_DELETE_ANDROID_SNAPSHOT_REQUIRED'});
});

test('older generations expose and remove target-only historical attachments without parsing provider logs in coordinator',async t=>{
  const f=await fixture(t),blob=hash('synthetic-history-blob'),ref={attachmentId:'sha256:'+blob,bytes:22,mediaType:'image/png',width:2,height:2};
  const files=await readdir(f.sessionsRoot,{recursive:true});const current=files.find(path=>path.includes('target-chat')&&path.endsWith('session.v4.jsonl'));assert.ok(current);
  const header=JSON.parse((await readFile(join(f.sessionsRoot,current),'utf8')).split('\n')[0]);header.version=3;
  const historical={type:'user/message',seq:0,time:1,surfaceOp:'append',data:createUserMessage({content:[{type:'image',attachment:ref}],source:{kind:'user'}})};
  await writeFile(join(dirname(join(f.sessionsRoot,current)),'session.v3.jsonl'),JSON.stringify(header)+'\n'+JSON.stringify(historical)+'\n');
  const path=join(f.home,'attachments','v1','objects',blob.slice(0,2),blob);await put(path,'synthetic-history-blob');
  const opened=await provider({root:f.sessionsRoot,compression:'none'});try{const row=(await opened.audit()).entries.find(row=>row.id==='target-chat');assert.deepEqual(row.generations[0].attachmentIds,[ref.attachmentId]);}finally{await opened.close();}
  const job=await f.create();await job.prepare();await job.commit();await absent(path);
});
test('retained Android uploaded URLs protect shared bytes through local commit; ordinary forks inside target cwd block deletion',async t=>{
  const f=await fixture(t);f.config.androidSnapshot.retained.push({source:'native_workbench:pending',value:{url:'/droy/companions/'+f.target+'/attachments/target-upload'}});
  const job=await f.create();await job.prepare();await job.commit();const blob=hash('synthetic-target-blob');assert.equal(await readFile(join(f.home,'attachments','v1','objects',blob.slice(0,2),blob),'utf8'),'synthetic-target-blob');
  const g=await fixture(t);await session(g.sessionsRoot,{version:4,id:'other-fork',cwd:join(g.targetPath,'work'),createdAt:1,isSeeded:false,delegationDepth:0,parentSession:'target-work'},[event('Independent fork')]);
  await assert.rejects((await g.create()).prepare(),{code:'MAINTENANCE_OWNED_WORKSPACE_SHARED'});assert.equal((await readSession(g.sessionsRoot,'target-chat')).events.length,1);
});
test('SIGKILL after source swap and after owned deletions resumes in a fresh process without Android raw snapshot',async t=>{
  for(const stopAt of ['session:source-renamed','owned'])await t.test(stopAt,async child=>{
    const f=await fixture(child),script=join(f.home,'synthetic-kill.mjs');
    await writeFile(script,`import {createRequire,registerHooks} from 'node:module';import {pathToFileURL} from 'node:url';
      const installed=createRequire(${JSON.stringify(new URL('../../dsh/package.json',import.meta.url).href)});
      const hooks=registerHooks({resolve(s,c,n){if(s.startsWith('@deepseek-ai/'))return {url:pathToFileURL(installed.resolve(s)).href,shortCircuit:true};return n(s,c);}});
      const [{createCompanionDeletionMaintenance},{createOfflineJsonlProvider},Jsonl]=await Promise.all([
      import(${JSON.stringify(new URL('../companions/maintenance-delete.mjs',import.meta.url).href)}),
      import(${JSON.stringify(new URL('../companions/maintenance-sessions.mjs',import.meta.url).href)}),import(${JSON.stringify(pathToFileURL(join(patched,'index.mjs')).href)})]);hooks.deregister();
      const home=${JSON.stringify(f.home)},operationId='delete-op',admissionEpoch='delete-epoch',companionId=${JSON.stringify(f.target)};
      const authority={async assertHeld(){return {root:home,operationId,companionId,admissionEpoch,hostStopped:true,admissionsClosed:true};}};
      const job=await createCompanionDeletionMaintenance({home,operationId,companionId,admissionEpoch,authority,provider:createOfflineJsonlProvider(Jsonl),androidSnapshot:${JSON.stringify(f.config.androidSnapshot)},onCheckpoint(phase){if(phase===process.argv[2])process.kill(process.pid,'SIGKILL');}});
      await job.prepare();await job.commit();throw Error('Synthetic checkpoint not reached');`);
    const processChild=spawn(process.execPath,['--expose-internals',script,stopAt],{stdio:['ignore','pipe','pipe']});child.after(()=>processChild.kill());let stderr='';processChild.stderr.on('data',part=>stderr+=part);processChild.stdout.resume();
    const timer=setTimeout(()=>processChild.kill(),15000);let signal;try{[,signal]=await once(processChild,'exit');}finally{clearTimeout(timer);}assert.equal(signal,'SIGKILL',stderr);
    assert.equal((await(await f.create({androidSnapshot:undefined})).recover()).phase,'local-complete');await absent(f.targetPath);assert.deepEqual(await readSession(f.sessionsRoot,'keep-fork'),f.expected);
  });
});

async function runNative(t,f,command,{reply=()=>true}={}){
  const script=join(f.home,'native-runner-bootstrap.mjs');
  await writeFile(script,`import {createRequire,registerHooks} from 'node:module';import {pathToFileURL} from 'node:url';
    const installed=createRequire(${JSON.stringify(new URL('../../dsh/package.json',import.meta.url).href)});
    registerHooks({resolve(s,c,n){if(s==='@deepseek-ai/dsh-session-persistence-jsonl')return {url:${JSON.stringify(pathToFileURL(join(patched,'index.mjs')).href)},shortCircuit:true};if(s.startsWith('@deepseek-ai/'))return {url:pathToFileURL(installed.resolve(s)).href,shortCircuit:true};return n(s,c);}});
    await import(${JSON.stringify(new URL('../companions/maintenance-native-runner.mjs',import.meta.url).href)});`);
  const processChild=spawn(process.execPath,['--expose-internals',script],{stdio:['pipe','pipe','pipe']});t.after(()=>processChild.kill());
  let stderr='',buffer='',lastNonce=0,started=false;const messages=[];let processing=Promise.resolve(),protocolError;
  processChild.stderr.on('data',part=>stderr+=part);processChild.stdin.on('error',error=>{if(error.code!=='EPIPE')protocolError=error;});
  processChild.stdout.on('data',part=>{
    buffer+=part;let boundary;
    while((boundary=buffer.indexOf('\n'))!==-1){const line=buffer.slice(0,boundary);buffer=buffer.slice(boundary+1);
      processing=processing.then(async()=>{
        const value=JSON.parse(line);messages.push(value);
        if(value.kind==='started'){
          assert.equal(started,false);assert.equal(value.pid,processChild.pid);started=true;processChild.stdin.write(JSON.stringify(command)+'\n');
        }else if(value.kind==='authority'){
          assert.equal(started,true);assert.equal(value.nonce,++lastNonce);assert.equal(value.request.scope,'companion-deletion');assert.equal(value.request.root,f.home);
          assert.equal(value.request.operationId,f.config.operationId);assert.equal(value.request.companionId,f.target);assert.equal(value.request.admissionEpoch,f.config.admissionEpoch);
          const decision=await reply(value,messages);
          if(decision==='eof')processChild.stdin.end();else processChild.stdin.write(JSON.stringify({kind:'authority',nonce:decision==='wrong-nonce'?value.nonce+1:value.nonce,held:decision===true})+'\n');
        }else assert.ok(['result','error'].includes(value.kind));
      }).catch(error=>{protocolError=error;processChild.kill();});
    }
  });
  const timer=setTimeout(()=>processChild.kill(),20000);let exitCode,signal;
  try{[exitCode,signal]=await once(processChild,'exit');await processing;}finally{clearTimeout(timer);}
  if(protocolError)throw protocolError;assert.equal(signal,null,stderr);assert.equal(buffer,'');assert.equal(started,true);
  return {exitCode,messages,stderr,authorities:lastNonce,terminal:messages.at(-1)};
}
const nativeCommand=(f,action='commit',extra={})=>({action,home:f.home,operationId:f.config.operationId,companionId:f.target,admissionEpoch:f.config.admissionEpoch,...extra});
test('native stdin/stdout owner protocol commits then acknowledges in separate real Node processes',async t=>{
  const f=await fixture(t),first=await runNative(t,f,nativeCommand(f,'commit',{androidSnapshot:f.config.androidSnapshot}));
  assert.equal(first.exitCode,0,first.stderr);assert.ok(first.authorities>10);assert.equal(first.terminal.kind,'result');assert.equal(first.terminal.receipt.phase,'local-complete');assert.deepEqual(first.terminal.receipt.pending,['android']);
  assert.equal(first.messages.filter(row=>row.kind==='result').length,1);assert.deepEqual(await readSession(f.sessionsRoot,'keep-fork'),f.expected);await absent(f.targetPath);
  const local=first.terminal.receipt,second=await runNative(t,f,nativeCommand(f,'acknowledge',{androidReceipt:{version:1,scope:'android-companion-deletion',operationId:f.config.operationId,companionId:f.target,admissionEpoch:f.config.admissionEpoch,snapshotDigest:local.androidSnapshotDigest,retirementDigest:local.retirementDigest,complete:true}}));
  assert.equal(second.exitCode,0,second.stderr);assert.equal(second.terminal.receipt.phase,'complete');assert.equal(second.terminal.receipt.companionDeletion,'complete');
  const recovery=await runNative(t,f,nativeCommand(f));assert.equal(recovery.exitCode,0,recovery.stderr);assert.equal(recovery.terminal.receipt.phase,'complete');
});
test('native runner rejects false authority, wrong nonce and EOF without deleting any target data',async t=>{
  for(const decision of [false,'wrong-nonce','eof'])await t.test(String(decision),async child=>{
    const f=await fixture(child),before=await readFile(join(f.root,'companions.sqlite'));
    const result=await runNative(child,f,nativeCommand(f,'commit',{androidSnapshot:f.config.androidSnapshot}),{reply:()=>decision});
    assert.equal(result.exitCode,1,result.stderr);assert.equal(result.authorities,1);assert.equal(result.terminal.kind,'error');assert.equal(result.terminal.code,'MAINTENANCE_DELETE_AUTHORITY_LOST');assert.equal(result.messages.some(row=>row.kind==='result'),false);
    assert.deepEqual(await readFile(join(f.root,'companions.sqlite')),before);assert.equal((await readSession(f.sessionsRoot,'target-chat')).events.length,1);assert.equal((await lstat(f.targetPath)).isDirectory(),true);
  });
});
test('native pipe loss after durable prepare stops commit and the next locked process resumes from its journal',async t=>{
  const f=await fixture(t);let closedAtPrepared=false;
  const first=await runNative(t,f,nativeCommand(f,'commit',{androidSnapshot:f.config.androidSnapshot}),{reply:async()=>{
    let journal;try{journal=JSON.parse(await readFile(join(f.home,'.companion-delete-delete-op.json'),'utf8'));}catch(error){if(error.code!=='ENOENT')throw error;}
    if(journal?.phase==='prepared'){closedAtPrepared=true;return 'eof';}return true;
  }});
  assert.equal(closedAtPrepared,true);assert.equal(first.exitCode,1);assert.equal(first.terminal.kind,'error');assert.equal(first.messages.some(row=>row.kind==='result'),false);
  assert.equal((await readSession(f.sessionsRoot,'target-chat')).events.length,1);assert.equal((await lstat(f.targetPath)).isDirectory(),true);
  const recovered=await runNative(t,f,nativeCommand(f));assert.equal(recovered.exitCode,0,recovered.stderr);assert.equal(recovered.terminal.receipt.phase,'local-complete');await absent(f.targetPath);assert.deepEqual(await readSession(f.sessionsRoot,'keep-fork'),f.expected);
});
