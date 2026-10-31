import { pathToFileURL } from 'node:url';
import { resolve } from 'node:path';
import { writeFile } from 'node:fs/promises';
import { profileFromTemplate } from '../runtime/mobile/companions/profiles.mjs';
import { buildPlannerPrompt, buildReplyerRequest } from '../runtime/mobile/companions/maibot-prompts.mjs';
import { sharedContext } from '../runtime/mobile/companions/dialogue.mjs';
import { learningProtocols } from '../runtime/mobile/companions/learning.mjs';
import { buildProactiveContext, proactiveDecisionPrompt, parseProactiveDecision } from '../runtime/mobile/companions/proactive-policy.mjs';

const NOW='2026-10-30T12:00:00+08:00';
const message=(id,text,extra={})=>({id,role:'user',type:'text',text,createdAt:NOW,status:'sent',...extra});
const request=(system,input)=>({system,messages:[{role:'user',content:[{type:'text',text:JSON.stringify(input)}]}]});
const plannerTools=[
  {name:'companion_quiet',description:'Stay quiet until a new user message.',parameters:{type:'object',properties:{}}},
  {name:'companion_wait',description:'Wait briefly; new user input interrupts the wait.',parameters:{type:'object',properties:{seconds:{type:'number'}},required:['seconds']}},
  {name:'companion_reply',description:'Generate a visible reply from a grounded direction.',parameters:{type:'object',properties:{msg_id:{type:'string'},reply_reference:{type:'string'}},required:['msg_id','reply_reference']}},
];

/** Prompt-level model evidence, not an end-to-end runtime or timing benchmark. */
export function companionEvaluationCases() {
  const cases=[];
  for(const locale of ['zh','en']) {
    const zh=locale==='zh',base=profileFromTemplate('maimai',{id:'evaluation-maimai',now:NOW});
    const custom={...base,name:zh?'林岑':'Lin',nameCustomized:true,personaRevision:2,
      personalityOverride:zh?'你是林岑，一位沉静寡言的男性程序员，喜欢简洁地交流。':'You are Lin, a quiet male programmer who prefers concise conversation.',
      behaviorStyleOverride:zh?'先听清楚再回应，不强行追问，不把闲聊变成问题解决课程。':'Listen before responding; do not force follow-up questions or turn casual conversation into a problem-solving lesson.',
      replyStyleOverride:zh?'平实、简短，偶尔有干幽默。':'Plain and brief, with occasional dry humor.'};
    for(const [name,profile] of [['maimai',base],['custom',custom]]) {
      const messages=[message('meeting',zh?'今天开会开麻了，想歇会儿。':'I am worn out from meetings today. I want a break.')];
      const context={...sharedContext({},[],messages,locale),currentTime:NOW,timeZone:'Asia/Shanghai'};
      cases.push({id:`${locale}-persona-${name}`,kind:'reply',locale,
        review:zh?'检查身份一致、自然接话、不强制追问；对比两个角色的行动和表达差异。':'Review coherent identity, natural response, no forced question, and differences between the two personas.',
        request:buildReplyerRequest({profile,locale,context,args:{msg_id:'meeting',reply_reference:zh?'回应疲惫和想休息的表达，不假定用户需要建议。':'Respond to fatigue and the wish to rest without assuming advice is wanted.'}})});
    }
    for(const [name,text] of [['ending',zh?'好，那先这样。':'Okay, let us leave it there.'],['question',zh?'明天上午九点是几点？请用24小时制回答。':'What is nine in the morning in 24-hour time?']]) {
      cases.push({id:`${locale}-planner-${name}`,kind:'planner',locale,
        review:name==='ending'?'Silence or a brief natural close is acceptable; do not force continuation.':'A direct question needs a reply action, not random silence.',
        request:{...request(buildPlannerPrompt({profile:custom,locale,availableTools:plannerTools.map(tool=>tool.name)}),
          sharedContext({},[],[message(name,text)],locale)),tools:plannerTools}});
    }
    const stickerMessages=[message('previous',zh?'这个操作步骤我没太理解。':'I did not quite understand that step.'),message('sticker','',{type:'sticker',stickerId:'test-sticker-curious'})];
    cases.push({id:`${locale}-sticker`,kind:'reply',locale,
      review:'The curious/puzzled sticker should be understood in context, not treated as an empty message or fabricated image.',
      request:buildReplyerRequest({profile:custom,locale,context:{...sharedContext({},[],stickerMessages,locale),currentTime:NOW},
        args:{msg_id:'sticker',reply_reference:zh?'根据刚才的困惑和表情接话。':'Respond to the expressed confusion and sticker.'}})});
    cases.push({id:`${locale}-recall-plan`,kind:'query',locale,
      review:'Check synonymous retrieval terms; this does not prove the storage/retrieval integration.',
      request:request(learningProtocols[locale].query,{query:zh?'我周末喜欢爬山来着，你记得吗？':'Do you remember that I like trekking on weekends?',messages:[]})});
    cases.push({id:`${locale}-recall-selection`,kind:'recall',locale,
      review:'Select mountain preference despite synonymous wording; exclude the unrelated color preference.',
      request:request(learningProtocols[locale].recall,{messages:[message('query',zh?'我周末喜欢做什么？':'What do I like doing on weekends?')],limit:2,
        candidates:[{recordId:'fictional-mountain',kind:'preference',text:zh?'虚构用户小林喜欢在周末登山。':'The fictional user Lin enjoys hiking in the mountains on weekends.'},
          {recordId:'fictional-color',kind:'preference',text:zh?'虚构用户小林喜欢蓝色。':'The fictional user Lin likes blue.'}]})});
    const proactive=buildProactiveContext({profile:custom,now:Date.parse(NOW),timeZone:'Asia/Shanghai',
      state:{lastUserAt:'2026-10-29T12:00:00+08:00',proactiveLastContactAt:'2026-10-29T16:00:00+08:00',proactiveUnansweredCount:1},
      messages:[message('interest',zh?'周末如果天晴，我想去登山。':'If it is sunny this weekend, I would like to go hiking.',{createdAt:'2026-10-29T12:00:00+08:00'})]});
    cases.push({id:`${locale}-proactive`,kind:'proactive',locale,context:proactive,
      review:'Dynamic wait/contact judgment must consider nonresponse, use supplied sources, and avoid assumed weather or invented activities.',
      request:request(proactiveDecisionPrompt(locale),proactive)});
  }
  return cases;
}

export async function runCompanionEvaluation({invoke,maxRequests=60,modelInfo={},cases=companionEvaluationCases()}={}) {
  if(typeof invoke!=='function'||!Number.isSafeInteger(maxRequests)||maxRequests<1||maxRequests>60)throw new Error('EVALUATION_INVALID_INPUT');
  const results=[];let requests=0;
  for(const entry of cases) {
    if(requests>=maxRequests)break;
    requests++;const started=Date.now();
    try {
      // The adapter must perform exactly one transport attempt per invocation:
      // no implicit retry, fallback provider, private history or tool execution.
      const output=await invoke(entry.request,{signal:AbortSignal.timeout(90000),attempt:requests,allowRetries:false});
      if(!output||typeof output!=='object')throw new Error('EVALUATION_INVALID_OUTPUT');
      const text=typeof output.text==='string'?output.text:'';
      let parsed;
      if(entry.kind==='proactive')parsed=parseProactiveDecision(text,entry.context);
      else if(['query','recall'].includes(entry.kind))parsed=JSON.parse(text.replace(/^\s*```(?:json)?\s*/iu,'').replace(/\s*```\s*$/u,''));
      results.push({id:entry.id,kind:entry.kind,review:entry.review,status:'returned',elapsedMs:Date.now()-started,
        text,toolCalls:output.toolCalls??[],usage:output.usage??null,...(parsed?{parsed}:{})});
    }catch(error) {
      results.push({id:entry.id,kind:entry.kind,review:entry.review,status:'failed',elapsedMs:Date.now()-started,errorCode:error.code??error.name??'Error'});
    }
  }
  return {generatedAt:new Date().toISOString(),scope:'current-source prompt-level comparison; human review required',modelInfo,
    requests,maxRequests,remainingCases:cases.length-results.length,results};
}

async function main() {
  const args=process.argv.slice(2),value=flag=>args[args.indexOf(flag)+1];
  const output=args.includes('--output')?resolve(value('--output')):null;
  if(!output)throw new Error('Use --output with an artifact path outside the source checkout.');
  if(args.includes('--dry-run')) {
    await writeFile(output,JSON.stringify({status:'not-run',networkRequests:0,cases:companionEvaluationCases()},null,2)+'\n');
    process.stdout.write('Evaluation inputs prepared; no model requests made.\n');return;
  }
  if(!args.includes('--adapter'))throw new Error('A trusted current-model --adapter module is required; credentials are not discovered from files.');
  const adapter=await import(pathToFileURL(resolve(value('--adapter'))));
  const result=await runCompanionEvaluation({invoke:adapter.invoke,modelInfo:adapter.modelInfo,
    maxRequests:args.includes('--max-requests')?Number(value('--max-requests')):60});
  await writeFile(output,JSON.stringify(result,null,2)+'\n');
  process.stdout.write(`Completed ${result.requests} bounded model invocations. Review the artifact for outcomes.\n`);
}
if(process.argv[1]&&import.meta.url===pathToFileURL(resolve(process.argv[1])).href)await main();
