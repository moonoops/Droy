import { randomUUID } from 'node:crypto';

const fail=code=>Object.assign(new Error(code),{code});

/** Share model execution, never text/result caches, across fixed memory spaces. */
export function createCompanionEmbedder(bridge) {
  const waiting=[];let running=false;
  async function drain() {
    if(running)return;running=true;
    try {
      while(waiting.length) {
        const job=waiting.shift(),{input,resolve,reject}=job;
        input.signal?.removeEventListener('abort',job.cancel);
        try {
          input.signal?.throwIfAborted();
          const requestId=randomUUID(),deadlineAt=input.deadlineAt;
          const result=await bridge.embedding({operation:'embed',requestId,memorySpaceId:input.memorySpaceId,
            epoch:input.epoch,role:input.role,texts:[input.text],deadlineAt},input.signal);
          input.signal?.throwIfAborted();
          if(result.requestId!==requestId||result.memorySpaceId!==input.memorySpaceId||result.epoch!==input.epoch
            ||typeof result.fingerprint!=='string'||!result.fingerprint||!Array.isArray(result.vectors)||result.vectors.length!==1
            ||!Number.isInteger(result.dimensions)||result.dimensions<1||result.dimensions>4096
            ||!Array.isArray(result.vectors[0])||result.vectors[0].length!==result.dimensions
            ||result.vectors[0].some(value=>!Number.isFinite(value)))throw fail('COMPANION_EMBEDDING_INVALID_RESULT');
          resolve({vector:result.vectors[0],fingerprint:result.fingerprint});
        } catch(error) {reject(error);}
      }
    } finally {running=false;}
  }
  const embed=request=>new Promise((resolve,reject)=>{
    if(request.signal?.aborted){reject(request.signal.reason);return;}
    if(waiting.length>=32){reject(fail('COMPANION_EMBEDDING_BUSY'));return;}
    const deadlineAt=Math.min(request.deadlineAt??Infinity,Date.now()+(request.role==='query'?2500:15000));
    if(deadlineAt<=Date.now()){reject(fail('EMBEDDING_DEADLINE'));return;}
    const deadline=new AbortController(),timer=setTimeout(()=>deadline.abort(fail('EMBEDDING_DEADLINE')),deadlineAt-Date.now());
    timer.unref?.();
    const input={...request,deadlineAt,signal:request.signal?AbortSignal.any([request.signal,deadline.signal]):deadline.signal};
    const job={input,resolve:value=>{clearTimeout(timer);resolve(value);},reject:error=>{clearTimeout(timer);reject(error);}};
    job.cancel=()=>{const index=waiting.indexOf(job);if(index>=0)waiting.splice(index,1);job.reject(input.signal.reason);};
    input.signal.addEventListener('abort',job.cancel,{once:true});
    if(input.role==='query')waiting.unshift(job);else waiting.push(job);
    void drain();
  });
  embed.status=signal=>bridge.embedding({operation:'status'},signal);
  return embed;
}
