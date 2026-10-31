import test from 'node:test';
import assert from 'node:assert/strict';
import { createCompanionEmbedder } from '../companions/embedding.mjs';

const request={memorySpaceId:'space-one',epoch:1,role:'document',text:'Fictional tea preference'};
function answer(input){return {...input,model:'fixture',dimensions:2,fingerprint:'fixture-model-v1',vectors:[[0.6,0.8]]};}

test('embedding bridge validates owner epoch and output shape without retaining text',async()=>{
  const calls=[],embed=createCompanionEmbedder({embedding:async input=>{calls.push(input);return answer(input);}});
  assert.deepEqual(await embed(request),{vector:[0.6,0.8],fingerprint:'fixture-model-v1'});
  assert.equal(calls[0].memorySpaceId,request.memorySpaceId);assert.equal(calls[0].epoch,1);
  assert.ok(calls[0].deadlineAt>Date.now());
  for(const invalid of [{memorySpaceId:'other'},{epoch:2},{vectors:[[NaN,1]]},{dimensions:3},{fingerprint:''}]) {
    const broken=createCompanionEmbedder({embedding:async input=>({...answer(input),...invalid})});
    await assert.rejects(broken(request),{code:'COMPANION_EMBEDDING_INVALID_RESULT'});
  }
});

test('queued interactive queries precede indexing and cancelled jobs never reach native',async()=>{
  const started=Promise.withResolvers(),release=Promise.withResolvers(),calls=[];
  const embed=createCompanionEmbedder({embedding:async input=>{
    calls.push(input.texts[0]);if(calls.length===1){started.resolve();await release.promise;}
    return answer(input);
  }});
  const first=embed({...request,text:'first'});await started.promise;
  const index=embed({...request,text:'index'}),abort=new AbortController();
  const cancelled=embed({...request,text:'cancelled',signal:abort.signal});
  const rejection=assert.rejects(cancelled,{name:'AbortError'});abort.abort();
  const query=embed({...request,text:'query',role:'query'});release.resolve();
  await Promise.all([first,index,query,rejection]);
  assert.deepEqual(calls,['first','query','index']);
});

test('already aborted embedding never dispatches',async()=>{
  const embed=createCompanionEmbedder({embedding:()=>assert.fail('must not dispatch')});
  await assert.rejects(embed({...request,signal:AbortSignal.abort()}),{name:'AbortError'});
});

test('query deadline includes waiting behind native indexing',async()=>{
  const release=Promise.withResolvers(),started=Promise.withResolvers(),calls=[];
  const nativeLifetime=setTimeout(()=>release.resolve(),1000);
  const embed=createCompanionEmbedder({embedding:async input=>{
    calls.push(input.role);started.resolve();await release.promise;return answer(input);
  }});
  const document=embed(request);await started.promise;
  try {
    await assert.rejects(embed({...request,role:'query',deadlineAt:Date.now()+30}),{code:'EMBEDDING_DEADLINE'});
    assert.deepEqual(calls,['document']);
  } finally {release.resolve();await document;clearTimeout(nativeLifetime);}
});
