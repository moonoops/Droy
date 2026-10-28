import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import vm from 'node:vm';

const source=await readFile(new URL('../companions/input-adapters.js',import.meta.url),'utf8');
function fixture() {
  let recognitionFails=true,disposed=0,started=0;const calls=[],listeners=new Set();
  let readiness={connected:true,catalog:{maxDurationSeconds:60,maxAudioBytes:1024,
    providers:[{id:'droy-sensevoice-local',preparation:{phase:'ready'}}]}};
  const voice={attachmentId:'voice-one',url:'/droy/companions/kaka/attachments/voice-one'};
  const speech={readiness:{getSnapshot:()=>readiness,subscribe:fn=>{listeners.add(fn);return()=>listeners.delete(fn);}},
    createRecording:()=>({async start(){started++;},async stop(){return new Uint8Array([1,2,3,4]).buffer;},async dispose(){disposed++;}}),
    async transcribe(){calls.push('recognize');if(recognitionFails)throw new Error('FICTIONAL_ASR_UNAVAILABLE');return {ok:true,value:{text:'<|en|>A fictional voice message'}};}};
  const target={AbortController,File,btoa:value=>Buffer.from(value,'binary').toString('base64'),
    async fetch(url,options={}) {
      calls.push([url,options.method??'GET']);
      if(url.endsWith('/uploads'))return Response.json({id:voice.attachmentId,url:voice.url},{status:201});
      if(url===voice.url)return new Response(new Uint8Array([1,2,3,4]));
      if(url.endsWith('/transcript'))return Response.json({id:'original-message',text:JSON.parse(options.body).text});
      if(options.method==='DELETE')return Response.json({removed:true});
      throw new Error('Unexpected fixture route '+url);
    }};
  const create=vm.runInNewContext(source+'\ncreateCompanionInputAdapters',{DROY_COMPANION_COPY:{en:{}}});
  const adapter=create({ctx:{},target,bridge:{},loadSpeech:async()=>speech});
  return {adapter,calls,voice,get disposed(){return disposed;},get started(){return started;},get observers(){return listeners.size;},
    readiness(value){readiness=value;for(const fn of [...listeners])fn();},recover(){recognitionFails=false;}};
}

test('failed recognition retains an already uploaded original and retries without recording or uploading again',async()=>{
  const f=fixture(),capture=await f.adapter.recording.start({companionId:'kaka'});
  let failure;try {await capture.finish();}catch(error){failure=error;}
  assert.equal(failure?.message,'FICTIONAL_ASR_UNAVAILABLE');
  assert.equal(failure.retainedVoice.attachmentId,f.voice.attachmentId);
  assert.equal(failure.asText,false);assert.equal(f.disposed,1);
  assert.ok(f.calls.findIndex(value=>Array.isArray(value)&&value[0].endsWith('/uploads'))<f.calls.indexOf('recognize'));
  f.recover();const retry=await f.adapter.recording.retry({companionId:'kaka',voice:failure.retainedVoice});
  assert.equal(retry.text,'A fictional voice message');assert.equal(retry.voice.attachmentId,f.voice.attachmentId);
  assert.equal(retry.voice.transcriptionState,'ready');
  assert.equal(f.calls.filter(value=>Array.isArray(value)&&value[0].endsWith('/uploads')).length,1);
});

test('failed text-conversion preserves intent and a sent voice retry updates the original message',async()=>{
  const f=fixture(),capture=await f.adapter.recording.start({companionId:'kaka'});
  let failure;try {await capture.finish({asText:true});}catch(error){failure=error;}
  assert.equal(failure.asText,true);f.recover();
  const text=await f.adapter.recording.retry({companionId:'kaka',voice:failure.retainedVoice,asText:true});
  assert.equal(text.text,'A fictional voice message');assert.equal(text.voice,undefined);
  const saved=await f.adapter.recording.retryMessage({companionId:'kaka',messageId:'original-message',voice:failure.retainedVoice});
  assert.equal(saved.id,'original-message');
  assert.ok(f.calls.some(value=>Array.isArray(value)&&value[0].endsWith('/messages/original-message/transcript')&&value[1]==='PATCH'));
});

test('the first press waits for the asynchronous speech catalog and checking phase before starting capture',async()=>{
  const f=fixture();f.readiness({connected:false,catalog:null,error:null});
  const capture=f.adapter.recording.start({companionId:'kaka'});
  await new Promise(setImmediate);assert.equal(f.started,0);assert.equal(f.observers,1);
  f.readiness({connected:true,catalog:{providers:[{id:'droy-sensevoice-local',preparation:{phase:'checking'}}]}});
  await Promise.resolve();assert.equal(f.started,0);
  f.readiness({connected:true,catalog:{providers:[{id:'droy-sensevoice-local',preparation:{phase:'ready'}}]}});
  await (await capture).cancel();assert.equal(f.started,1);assert.equal(f.observers,0);
});

test('releasing while speech is connecting removes the observer and never opens a microphone',async()=>{
  const f=fixture(),abort=new AbortController();f.readiness({connected:false,catalog:null,error:null});
  const capture=f.adapter.recording.start({companionId:'kaka',signal:abort.signal});
  await new Promise(setImmediate);abort.abort();await assert.rejects(capture,{name:'AbortError'});
  assert.equal(f.started,0);assert.equal(f.observers,0);
});

function photoFixture({cancel=false,foreign=false,nativeError=false,secondUploadFails=false,completeFails=false}={}) {
  const calls=[],listeners=new Map();let picked,uploads=0;
  const target={File,btoa:value=>Buffer.from(value,'binary').toString('base64'),
    addEventListener:(name,listener)=>listeners.set(name,listener),removeEventListener:name=>listeners.delete(name),
    document:{createElement(){throw new Error('Photos must use the native picker');}},
    async fetch(url,options={}) {
      calls.push({url,method:options.method||'GET',body:options.body&&JSON.parse(options.body)});
      if(url.startsWith('/droy/media-input/'))return new Response(new Uint8Array([1,2,3]),{headers:{'Content-Type':'image/png'}});
      if(secondUploadFails&&++uploads===2)return Response.json({message:'Fictional second upload failure'},{status:503});
      return Response.json({id:'uploaded-image',url:url.replace('/uploads','/attachments/uploaded-image')},{status:201});
    }};
  const bridge={async request(action,args){
    calls.push({action,...args});
    if(args.operation==='pick'){picked=args;return {operationId:'operation-one'};}
    if(args.operation==='complete'){if(completeFails)throw new Error('Fictional completion failure');return {completed:true};}
    return {pending:null,imports:picked&&!cancel?[{id:nativeError?'separate-error-record':'operation-one',consumer:foreign?'work':picked.consumer,
      targetSessionId:foreign?'work-session':picked.targetSessionId,...(nativeError?{error:'Fictional image decoding failure'}:{}),
      items:[{id:'image-one',name:'fixture.png',mediaType:'image/png'},...(secondUploadFails?[{id:'image-two',name:'second.png',mediaType:'image/png'}]:[])]}]:[]};
  }};
  const create=vm.runInNewContext(source+'\ncreateCompanionInputAdapters',{DROY_COMPANION_COPY:{en:{}}});
  return {adapter:create({ctx:{},target,bridge}),calls,listeners};
}

test('album, avatar and background use the system photo picker with their actual selection limit',async()=>{
  for(const kind of ['album','camera','avatar','background']) {
    const f=photoFixture();const selected=await f.adapter.media.pick(kind,{companionId:'kaka',remaining:4});
    assert.equal(selected.length,1);
    const picked=f.calls.find(call=>call.operation==='pick');
    assert.equal(picked.source,kind==='camera'?'camera':'album');
    assert.equal(picked.remaining,['avatar','background'].includes(kind)?1:4);
    assert.equal(picked.consumer,'companion');assert.equal(picked.targetSessionId,'im:kaka');
    assert.ok(f.calls.some(call=>call.url==='/droy/companions/kaka/uploads'));
    assert.ok(f.calls.some(call=>call.operation==='complete'&&call.consumer==='companion'));
    assert.equal(f.listeners.size,0);
  }
});

test('the shared user avatar has its own native consumer and upload scope',async()=>{
  const f=photoFixture();await f.adapter.media.pick('user-avatar');
  const picked=f.calls.find(call=>call.operation==='pick');
  assert.equal(picked.consumer,'profile');assert.equal(picked.targetSessionId,'user');assert.equal(picked.remaining,1);
  assert.ok(f.calls.some(call=>call.url==='/droy/companions/user-profile/uploads'));
  assert.ok(f.calls.some(call=>call.operation==='complete'&&call.consumer==='profile'));
  assert.equal(f.calls.some(call=>call.url?.includes('/kaka/')||call.url?.includes('/maimai/')),false);
});

test('cancelled selections and imports owned by another surface never upload a photo',async()=>{
  for(const options of [{cancel:true},{foreign:true}]) {
    const f=photoFixture(options);const selected=await f.adapter.media.pick('album',{companionId:'maimai'});
    assert.equal(selected.length,0);assert.equal(f.calls.some(call=>call.url),false);assert.equal(f.listeners.size,0);
  }
});

test('native image import errors with a separate record ID are visible failures, never cancellation',async()=>{
  const f=photoFixture({nativeError:true});
  await assert.rejects(f.adapter.media.pick('album',{companionId:'kaka'}),/Fictional image decoding failure/);
  assert.ok(f.calls.some(call=>call.operation==='complete'&&call.id==='separate-error-record'));
  assert.equal(f.calls.some(call=>call.url),false);assert.equal(f.listeners.size,0);
});

test('partial uploads remain available to the UI when a later upload or native completion fails',async()=>{
  for(const options of [{secondUploadFails:true},{completeFails:true}]) {
    const f=photoFixture(options);let failure;
    try {await f.adapter.media.pick('album',{companionId:'kaka'});}catch(error){failure=error;}
    assert.ok(failure);assert.equal(failure.retainedUploads.length,1);
    assert.equal(failure.retainedUploads[0].id,'uploaded-image');
    assert.ok(f.calls.some(call=>call.operation==='complete'&&call.id==='operation-one'));
    assert.equal(f.listeners.size,0);
  }
});
