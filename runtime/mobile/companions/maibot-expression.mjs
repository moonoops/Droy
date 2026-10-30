// Adapted from Mai-with-u/MaiBot (GPL-3.0), commit
// fe88ec6b6053e40eaf430cb5ba7ed7e805add2cb:
// src/chat/replyer/maisaka_expression_selector.py and src/learners/weighted_sampling.py.
// Droy's distribution is AGPL-3.0; upstream notices are retained in ./maibot/.

import {renderMaiBotPrompt} from './maibot-prompts.mjs';
import {COMPANION_PERSONAS} from './profiles.mjs';

export const expressionDefaults = Object.freeze({enabled:true,mode:'fast',minimumCandidates:10,maxSelected:5,candidatePoolSize:50});

// Authored role examples are prompt material, not evidence of anything the user said.
const personaExamples={
  kaka:{
    zh:[['用户想倾诉','嗯，你说。我先听着。'],['接下明确的工作','行，我先把关键的几处理清楚。'],['被真诚夸奖','这句我收下了，算今天的精神补贴。']],
    en:[['The user wants to vent',"Go on. I'm listening."],['Accepting a clear task',"All right, I'll sort out the key parts first."],['Receiving sincere praise',"I'll take that. A little morale bonus for today."]],
  },
  maimai:{
    zh:[['对有趣的细节好奇','然后呢，怎么变成这样的'],['遇到轻松的小槽点','这也行啊'],['不认同一个观点','这个我倒不太同意']],
    en:[['Curious about an interesting detail','Wait, how did it get to that?'],['Lightly teasing an amusing detail',"Apparently that's a thing now."],['Disagreeing with a view',"Not sure I agree with that one."]],
  },
  dafeiyu:{
    zh:[['用户开了一天会','人都快开熟了。今天这碗饭你值得多盛一点。'],['轻松地被调侃','这是节能思考。'],['被真诚夸奖','现在知道本鲸的厉害了吧。']],
    en:[['The user spent the day in meetings',"You've been thoroughly meeting-cooked. You deserve an extra helping at dinner."],['Being playfully teased',"It's energy-efficient thinking."],['Receiving sincere praise',"Now you see what this whale can do."]],
  },
  claude:{
    zh:[['用户忙碌了一天','今天已经够辛苦了。先缓一会儿。'],['认真讨论分歧','这一点，我的看法稍有不同。'],['被真诚夸奖','谢谢。被你这样认真地肯定，我还是会高兴的。']],
    en:[['The user has had a busy day',"You've done enough for now. Take a moment."],['Discussing a disagreement',"I see this point a little differently."],['Receiving sincere praise',"Thank you. It does make me happy to hear you say that so sincerely."]],
  },
};

const copy = {
  zh: {
    situation:'情景',style:'风格',habits:'你可以视情况自由使用以下句式：',habit:(s,e)=>`- 当"${s}"时，可以用"${e}"来表达。`,
    intent:'表达选择意图：',focus:'贴合对象',reply_act:'回复动作',scene:'表达场景',tone:'期望语气',prefer:'优先表达',avoid:'避免表达',
    reference:'回复信息参考：',reason:'Planner 推理：',history:'真实聊天上下文：',target:'正在回复的消息：',language:'当前界面和默认回复语言是中文；保留候选原文，只返回选择结果。',
    examples:'角色表达示例（不是历史对话或用户事实）：只参考语气，按当前情境重新表达；不必套用，也不要为了使用示例强行接话。',
  },
  en: {
    situation:'Situation',style:'Style',habits:'You may freely use the following expressions when appropriate:',habit:(s,e)=>`- When "${s}", you can express yourself with "${e}".`,
    intent:'Expression selection intent:',focus:'Focus',reply_act:'Reply act',scene:'Scene',tone:'Desired tone',prefer:'Prefer',avoid:'Avoid',
    reference:'Reply reference:',reason:'Planner reasoning:',history:'Actual chat context:',target:'Message being answered:',language:'The current interface and default reply language is English; preserve candidate text and return only the selection result.',
    examples:'Role expression examples (not conversation history or facts about the user): use only as tone references and phrase your response for the current situation. Do not force a reply or copy an example.',
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

function normalizedCandidates(context,profile) {
  // The caller supplies only this companion's scope. There is no global pool,
  // cross-companion cache, or implicit sharing of learned styles.
  return (context.expressions??[]).filter(item=>item.id&&item.situation&&(item.expression||item.style)
    &&(!profile?.personaRevision||(item.personaRevision??1)===profile.personaRevision)).map(item=>({
    ...item,expression:item.expression??item.style,
    // Existing Droy learning tracks repeated evidence instead of a numeric count.
    count:item.count??Math.max(1,new Set(item.sourceIds??[]).size),sourceIds:[...(item.sourceIds??[])],
  }));
}

export function roleExpressionExamples(profile,locale='zh') {
  const l=locale==='en'?'en':'zh',template=COMPANION_PERSONAS[profile?.templateId];
  if(!template||['personalityOverride','behaviorStyleOverride','replyStyleOverride'].some(key=>profile[key]?.trim()))return [];
  if(['personality','behaviorStyle','replyStyle'].some(key=>
    (typeof profile[key]==='string'?profile[key]:profile[key]?.[l])!==template[key][l]))return [];
  return (personaExamples[profile.templateId]?.[l]??[]).map(([situation,expression],index)=>({
    id:`persona-example:${profile.templateId}:${l}:${index+1}`,situation,expression,origin:'persona-example',
  }));
}

function withRoleExamples(selection,profile,locale,enabled) {
  const examples=enabled?roleExpressionExamples(profile,locale):[],c=copy[locale==='en'?'en':'zh'];
  return {...selection,examples,prompt:[selection.prompt,examples.length?c.examples+'\n'+examples
    .map(item=>c.habit(item.situation,item.expression)).join('\n'):''].filter(Boolean).join('\n\n')};
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
export async function selectExpressions({profile,context={},args={},locale='zh',random=Math.random,generate,retrieve,signal,options={}}={}) {
  const settings={...expressionDefaults,...options},all=normalizedCandidates(context,profile),c=copy[locale==='en'?'en':'zh'];
  const finish=(...args)=>withRoleExamples(result(...args),profile,locale,settings.enabled);
  if(!settings.enabled||all.length<settings.minimumCandidates)return finish([],locale,'skipped',0);
  signal?.throwIfAborted();
  let candidates=sampleCandidates(all,random,settings.minimumCandidates),retrievalMode='weighted';
  if(settings.mode!=='fine')return finish(candidates,locale,'fast',0,{retrievalMode});
  const query=expressionQuery(args,locale);
  if(retrieve) {
    const ids=await retrieve({query,candidates:all,limit:settings.candidatePoolSize,signal});
    const byId=new Map(all.map(item=>[item.id,item]));
    const retrieved=[...new Set(ids)].filter(id=>byId.has(id)).slice(0,settings.candidatePoolSize).map(id=>byId.get(id));
    if(retrieved.length){candidates=retrieved;retrievalMode='embedding';}
  }
  if(!generate)return finish(candidates,locale,'fast',0,{retrievalMode,warning:'COMPANION_EXPRESSION_SELECTOR_UNAVAILABLE'});
  const numbered=candidates.map((item,index)=>`${index+1}: ${c.situation}=${item.situation} | ${c.style}=${item.expression}`);
  const history=(context.messages??[]).slice(-10).map(message=>`${message.role}: ${message.text??''}`).join('\n');
  const target=(context.messages??[]).find(message=>message.id===(args.msg_id??args.target_message_id??args.targetMessageId));
  const system=renderMaiBotPrompt('maisaka_expression_selector',locale,{max_selected:settings.maxSelected,candidates:numbered.join('\n')})+'\n\n'+c.language;
  const raw=await generate({system,messages:[{role:'user',content:[c.history+'\n'+history,target?c.target+'\n'+target.text:'',query].filter(Boolean).join('\n\n')}],purpose:'expression_selector',signal});
  signal?.throwIfAborted();
  let parsed;
  try {parsed=JSON.parse(String(raw?.text??raw).replace(/^\s*`{3}(?:json)?\s*/iu,'').replace(/\s*`{3}\s*$/u,''));}
  catch(error){if(!(error instanceof SyntaxError))throw error;return finish([],locale,'fine',1,{retrievalMode,warning:'COMPANION_INVALID_EXPRESSION_SELECTION'});}
  const ids=Array.isArray(parsed?.selected_ids)?[...new Set(parsed.selected_ids)].filter(id=>Number.isInteger(id)&&id>=1&&id<=candidates.length).slice(0,settings.maxSelected):[];
  return finish(ids.map(id=>candidates[id-1]),locale,'fine',1,{retrievalMode});
}
