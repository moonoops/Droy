// Adapted from Mai-with-u/MaiBot (GPL-3.0), commit
// fe88ec6b6053e40eaf430cb5ba7ed7e805add2cb:
// src/chat/replyer/maisaka_expression_selector.py and src/learners/weighted_sampling.py.
// Droy's distribution is AGPL-3.0; upstream notices are retained in ./maibot/.

import {renderMaiBotPrompt} from './maibot-prompts.mjs';

export const expressionDefaults = Object.freeze({enabled:true,mode:'fast',minimumCandidates:10,maxSelected:5,candidatePoolSize:50});

const copy = {
  zh: {
    situation:'情景',style:'风格',habits:'你可以视情况自由使用以下句式：',habit:(s,e)=>`- 当"${s}"时，可以用"${e}"来表达。`,
    intent:'表达选择意图：',focus:'贴合对象',reply_act:'回复动作',scene:'表达场景',tone:'期望语气',prefer:'优先表达',avoid:'避免表达',
    reference:'回复信息参考：',reason:'Planner 推理：',history:'真实聊天上下文：',target:'正在回复的消息：',language:'当前界面和默认回复语言是中文；保留候选原文，只返回选择结果。',
  },
  en: {
    situation:'Situation',style:'Style',habits:'You may freely use the following expressions when appropriate:',habit:(s,e)=>`- When "${s}", you can express yourself with "${e}".`,
    intent:'Expression selection intent:',focus:'Focus',reply_act:'Reply act',scene:'Scene',tone:'Desired tone',prefer:'Prefer',avoid:'Avoid',
    reference:'Reply reference:',reason:'Planner reasoning:',history:'Actual chat context:',target:'Message being answered:',language:'The current interface and default reply language is English; preserve candidate text and return only the selection result.',
  },
};

/** Upstream's bounded count weight is recomputed after each draw. */
export function expressionWeights(population) {
  const counts=population.map(item=>Number.isFinite(Number(item.count))?Math.max(0,Number(item.count)):1);
  const minimum=Math.min(...counts),maximum=Math.max(...counts);
  return counts.map(count=>minimum===maximum?1:1+(count-minimum)/(maximum-minimum)*4);
}

export function weightedSampleExpressions(population,k,random=Math.random) {
  if(k<=0)return [];
  if(population.length<=k)return population.slice();
  const remaining=population.slice(),selected=[];
  for(let i=0;i<k&&remaining.length;i++) {
    const weights=expressionWeights(remaining),threshold=random()*weights.reduce((a,b)=>a+b,0);
    let cumulative=0,index=weights.length-1;
    for(let j=0;j<weights.length;j++){cumulative+=weights[j];if(threshold<=cumulative){index=j;break;}}
    selected.push(remaining.splice(index,1)[0]);
  }
  return selected;
}

function sampleCandidates(all,random,minimumCandidates) {
  if(all.length<minimumCandidates)return [];
  const frequent=all.filter(item=>item.count>1);
  const high=frequent.length>=minimumCandidates?weightedSampleExpressions(frequent,5,random):[];
  return [...new Map([...high,...weightedSampleExpressions(all,5,random)].map(item=>[item.id,item])).values()];
}

export function expressionQuery(args={},locale='zh') {
  const c=copy[locale==='en'?'en':'zh'],intent=args.expression_intent,parts=[];
  if(intent&&typeof intent==='object') {
    const fields=['focus','reply_act','scene','tone','prefer','avoid'].flatMap(key=>{
      const value=Array.isArray(intent[key])?intent[key].join(locale==='en'?', ':'、'):String(intent[key]??'').trim();
      return value?[`- ${c[key]}: ${value}`]:[];
    });
    if(fields.length)parts.push(c.intent+'\n'+fields.join('\n'));
  } else if(intent)parts.push(c.intent+'\n'+String(intent));
  const reference=args.reply_reference??args.replyReference;
  if(reference)parts.push(c.reference+'\n'+reference);
  else if(args.reason)parts.push(c.reason+'\n'+args.reason);
  return parts.join('\n\n');
}

function normalizedCandidates(context) {
  // The caller supplies only this companion's scope. There is no global pool,
  // cross-companion cache, or implicit sharing of learned styles.
  return (context.expressions??[]).filter(item=>item.id&&item.situation&&(item.expression||item.style)).map(item=>({
    ...item,expression:item.expression??item.style,
    // Existing Droy learning tracks repeated evidence instead of a numeric count.
    count:item.count??Math.max(1,new Set(item.sourceIds??[]).size),sourceIds:[...(item.sourceIds??[])],
  }));
}

function result(expressions,locale,selectionMode,modelCalls,extra={}) {
  const c=copy[locale==='en'?'en':'zh'];
  return {expressions,selectedIds:expressions.map(item=>item.id),sourceIds:[...new Set(expressions.flatMap(item=>item.sourceIds))],
    prompt:expressions.length?c.habits+'\n'+expressions.map(item=>c.habit(item.situation,item.expression)).join('\n'):'',
    selectionMode,modelCalls,...extra};
}

/** Fast mode follows MaiBot's sampling, with no extra LLM call. Fine mode uses
 * optional embedding retrieval followed by the upstream contextual selector.
 * generate receives a normal {system,messages,purpose,signal} request.
 * retrieve, when supplied, must return IDs from the provided scoped candidates. */
export async function selectExpressions({context={},args={},locale='zh',random=Math.random,generate,retrieve,signal,options={}}={}) {
  const settings={...expressionDefaults,...options},all=normalizedCandidates(context),c=copy[locale==='en'?'en':'zh'];
  if(!settings.enabled||all.length<settings.minimumCandidates)return result([],locale,'skipped',0);
  signal?.throwIfAborted();
  let candidates=sampleCandidates(all,random,settings.minimumCandidates),retrievalMode='weighted';
  if(settings.mode!=='fine')return result(candidates,locale,'fast',0,{retrievalMode});
  const query=expressionQuery(args,locale);
  if(retrieve) {
    const ids=await retrieve({query,candidates:all,limit:settings.candidatePoolSize,signal});
    const byId=new Map(all.map(item=>[item.id,item]));
    const retrieved=[...new Set(ids)].filter(id=>byId.has(id)).slice(0,settings.candidatePoolSize).map(id=>byId.get(id));
    if(retrieved.length){candidates=retrieved;retrievalMode='embedding';}
  }
  if(!generate)return result(candidates,locale,'fast',0,{retrievalMode,warning:'COMPANION_EXPRESSION_SELECTOR_UNAVAILABLE'});
  const numbered=candidates.map((item,index)=>`${index+1}: ${c.situation}=${item.situation} | ${c.style}=${item.expression}`);
  const history=(context.messages??[]).slice(-10).map(message=>`${message.role}: ${message.text??''}`).join('\n');
  const target=(context.messages??[]).find(message=>message.id===(args.msg_id??args.target_message_id??args.targetMessageId));
  const system=renderMaiBotPrompt('maisaka_expression_selector',locale,{max_selected:settings.maxSelected,candidates:numbered.join('\n')})+'\n\n'+c.language;
  const raw=await generate({system,messages:[{role:'user',content:[c.history+'\n'+history,target?c.target+'\n'+target.text:'',query].filter(Boolean).join('\n\n')}],purpose:'expression_selector',signal});
  signal?.throwIfAborted();
  let parsed;
  try {parsed=JSON.parse(String(raw?.text??raw).replace(/^\s*`{3}(?:json)?\s*/iu,'').replace(/\s*`{3}\s*$/u,''));}
  catch(error){if(!(error instanceof SyntaxError))throw error;return result([],locale,'fine',1,{retrievalMode,warning:'COMPANION_INVALID_EXPRESSION_SELECTION'});}
  const ids=Array.isArray(parsed?.selected_ids)?[...new Set(parsed.selected_ids)].filter(id=>Number.isInteger(id)&&id>=1&&id<=candidates.length).slice(0,settings.maxSelected):[];
  return result(ids.map(id=>candidates[id-1]),locale,'fine',1,{retrievalMode});
}
