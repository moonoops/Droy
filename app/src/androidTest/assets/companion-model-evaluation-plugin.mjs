import { readFile,writeFile } from 'node:fs/promises';
import { join, dirname } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { setTimeout as delay } from 'node:timers/promises';

export const name='droy-companion-model-evaluation';
export const inject=['llm','agentDefaultModel','agents'];

export function apply(ctx) {
  const directory=dirname(fileURLToPath(import.meta.url)),abort=new AbortController();
  ctx.effect(()=>()=>abort.abort());
  const operation=(async()=>{
    const result={status:'starting',requests:0,reservedAttempts:0,previousInterruptedAttemptReservation:0,maximumAttempts:60,results:[]};
    let lease;
    try {
      await writeFile(join(directory,'result.json'),JSON.stringify(result,null,2));
      const deadline=Date.now()+90000;
      while(ctx.get('droyCompanionStartup')?.phase!=='ready') {
        abort.signal.throwIfAborted();
        if(Date.now()>deadline)throw Object.assign(new Error(),{code:'EVALUATION_RUNTIME_NOT_READY'});
        await delay(50,undefined,{signal:abort.signal});
      }
      if(ctx.agents.list().some(agent=>agent.status!=='idle'))throw Object.assign(new Error(),{code:'EVALUATION_EXISTING_WORK_ACTIVE'});
      lease=ctx.get('droyCompanions').maintenance.acquire({operationId:'companion-model-evaluation'});
      const quiet=await lease.suspend({signal:AbortSignal.any([abort.signal,AbortSignal.timeout(10000)])});
      if(!quiet.quiet)throw Object.assign(new Error(),{code:'EVALUATION_EXISTING_WORK_PENDING'});
      const model=ctx.agentDefaultModel.currentSelection();
      if(!model?.provider||!model?.model)throw Object.assign(new Error(),{code:'EVALUATION_CURRENT_MODEL_REQUIRED'});
      const expected=JSON.parse(await readFile(join(directory,'expected-model.json'),'utf8'));
      if(['provider','model','reasoningEffort'].some(key=>model[key]!==expected[key]))
        throw Object.assign(new Error(),{code:'EVALUATION_MODEL_SELECTION_CHANGED'});
      result.model={provider:model.provider,model:model.model};
      result.status='running';
      await writeFile(join(directory,'result.json'),JSON.stringify(result,null,2));
      const {companionEvaluationCases}=await import(pathToFileURL(join(directory,'evaluation-core.mjs')));
      const {createUserMessage,createAssistantMessage}=await import(pathToFileURL(join(process.env.DROY_RUNTIME_DIR,'node_modules/@deepseek-ai/dsh-llm/lib/index.js')));
      for(const entry of companionEvaluationCases().filter(item=>item.locale==='zh')) {
        abort.signal.throwIfAborted();
        if(ctx.agents.list().some(agent=>agent.status!=='idle'))throw Object.assign(new Error(),{code:'EVALUATION_EXISTING_WORK_ACTIVE'});
        const signal=AbortSignal.any([abort.signal,AbortSignal.timeout(90000)]),prepared=await ctx.llm.prepareCall(model,signal);
        // Direct streams do not use the Agent retry plugin. Reserve its maximum
        // nevertheless, so even a provider-specific retry policy stays bounded.
        const policy=prepared.retryPolicy;
        if(policy?.mode!=='normal'||!Number.isSafeInteger(policy.maxRetries)||policy.maxRetries<0)
          throw Object.assign(new Error(),{code:'EVALUATION_UNBOUNDED_RETRY_POLICY'});
        const reservation=1+policy.maxRetries;
        result.previousInterruptedAttemptReservation=Math.max(result.previousInterruptedAttemptReservation,reservation);
        if(result.reservedAttempts+reservation+result.previousInterruptedAttemptReservation>60)break;
        result.reservedAttempts+=reservation;result.requests++;
        const started=Date.now(),row={id:entry.id,review:entry.review,status:'running',text:'',toolCalls:[]};
        result.results.push(row);
        await writeFile(join(directory,'result.json'),JSON.stringify(result,null,2));
        try {
          const messages=entry.request.messages.map(message=>message.role==='assistant'
            ?createAssistantMessage({content:message.content,source:{provider:model.provider,model:model.model}})
            :createUserMessage({content:message.content,source:{kind:'user'}}));
          let finished=false;
          for await(const chunk of prepared.stream({...prepared.config,system:entry.request.system,messages,
            ...(entry.request.tools?{tools:entry.request.tools}:{}),sessionId:'fictional-companion-evaluation-'+entry.id,signal})) {
            if(chunk.type==='text-delta')row.text+=chunk.text;
            if(chunk.type==='block-end'&&chunk.block?.type==='tool-call')row.toolCalls.push(chunk.block);
            if(chunk.type==='usage')row.usage=chunk.usage??chunk;
            if(chunk.type==='finish') {
              finished=true;row.finish=chunk.reason?.kind;
              row.status=['stop','tool-calls'].includes(chunk.reason?.kind)?'returned':'failed';
              if(row.status==='failed')row.errorCode=chunk.reason?.failure?.code??'EVALUATION_MODEL_FAILED';
            }
          }
          if(!finished){row.status='failed';row.errorCode='EVALUATION_INCOMPLETE_STREAM';}
        } catch(error) {row.status='failed';row.errorCode=error.code??error.name??'Error';}
        row.elapsedMs=Date.now()-started;
        await writeFile(join(directory,'result.json'),JSON.stringify(result,null,2));
      }
      result.status='finished';
    } catch(error) {result.status='blocked';result.errorCode=error.code??error.name??'Error';}
    finally {
      // Keep timers suspended until the instrumentation stops this temporary
      // runtime; resuming now could dispatch unrelated pending proactive work.
      result.generatedAt=new Date().toISOString();
      await writeFile(join(directory,'result.json'),JSON.stringify(result,null,2));
      await writeFile(join(directory,'done'),result.status);
    }
  })();
  operation.catch(()=>writeFile(join(directory,'done'),'EVALUATION_REPORT_FAILED'));
}
