// Adapted from Mai-with-u/MaiBot (GPL-3.0), commit
// fe88ec6b6053e40eaf430cb5ba7ed7e805add2cb:
// src/chat/utils/{utils.py,llm_sentence_splitter.py}. Upstream notices: ./maibot/.
// Keep the sentence/merge behavior, but never delete bracketed information or
// replace a long answer with a canned response: Droy also delivers work results.

export const splitterDefaults=Object.freeze({enabled:true,mode:'rule',maxSplitNum:3});
const splitterPrompts={
  zh:'请不要思考，快速地把下面的回复进行断句，按自然语义拆成若干条适合逐条发送的消息。\n要求：\n1. 只能返回 JSON 字符串数组，例如 ["第一句。", "第二句？"]，不要 Markdown、解释或代码块。\n2. 只能在原文已有字符之间选择切分位置，不能改写文本、增删或调整顺序。\n3. 不要把括号、引号、颜文字或代码从中间拆开。如果是单独的句子，不要在在结尾留逗号。\n4. 如果是完整代码，公式，或者整理阅读更合适的文段，不需要拆分，返回"no_split"。\n5. 如果是本身比较短，或者表意清晰的句子，不需要拆分，返回"no_split"。',
  en:'Do not reason at length. Quickly split the following reply by natural meaning into messages suitable for sending one by one.\nRequirements:\n1. Return only a JSON string array, for example ["First sentence.", "Second sentence?"], with no Markdown, explanations or code fences.\n2. Choose split positions only between existing characters. Do not rewrite, add, remove or reorder text.\n3. Do not split inside parentheses, quotes, kaomoji or code. Do not leave a comma at the end of a standalone sentence.\n4. For complete code, formulas or passages better read as a whole, return "no_split".\n5. For an already short or clear sentence, return "no_split".',
};
const segment=(text,separator='')=>({text,separator,quotePrevious:false,isCorrection:false});

function formattedResult(text) {
  return /^\s*(?:`{3}|~{3}|#{1,6}\s|(?:[-*+]\s|\d+[.)、]\s)|\|.*\|\s*$|\$\$)/mu.test(text);
}

/** Protect structural spans before finding conversational pause characters. */
function protectedBoundaries(text) {
  const protectedAt=new Set(),mark=(start,end)=>{for(let i=start+1;i<end;i++)protectedAt.add(i);};
  for(const match of text.matchAll(/`+[^`\n]+`+|https?:\/\/[^\s<>，。！？；]+|\b[+-]?\d[\d,._:/%-]*\d\b/gu))mark(match.index,match.index+match[0].length);
  const closes=new Map([['(',')'],['（','）'],['[',']'],['【','】'],['{','}'],['“','”'],['‘','’'],['「','」'],['『','』'],['"','"'],["'","'"]]);
  const stack=[];
  for(let i=0;i<text.length;i++) {
    const char=text[i],last=stack.at(-1);
    if(last&&char===last.close){stack.pop();mark(last.start,i+1);continue;}
    if((char==="'"||char==='’')&&/[\p{L}\p{N}]/u.test(text[i-1]??'')&&/[\p{L}\p{N}]/u.test(text[i+1]??''))continue;
    if(closes.has(char))stack.push({start:i,close:closes.get(char)});
  }
  return protectedAt;
}

function splitRule(text,random) {
  const chars=[...text];
  if(chars.length<3)return random()<0.01?chars.map(char=>segment(char)):[segment(text)];
  const protectedAt=protectedBoundaries(text),separators=new Set(['，',',',' ','。',';','\n']),parts=[];
  let current='';
  for(let i=0;i<text.length;i++) {
    const char=text[i],prev=text[i-1]??'',next=text[i+1]??'';
    let canSplit=separators.has(char)&&!protectedAt.has(i);
    if(char!=='\n'&&(prev===':'||prev==='：'||next===':'||next==='：'))canSplit=false;
    if(char===' '&&(/[—-]/u.test(prev)||/[—-]/u.test(next)||/[A-Za-z0-9]/u.test(prev)&&/[A-Za-z0-9]/u.test(next)))canSplit=false;
    if(canSplit&&current.trim()){parts.push(segment(current,char));current='';}
    else if(canSplit&&!current&&parts.length)parts.at(-1).separator+=char;
    else current+=char;
  }
  if(current)parts.push(segment(current));
  if(!parts.length)return [segment(text)];
  const mergeProbability=1-(chars.length<12?0.2:chars.length<32?0.6:0.7),merged=[];
  for(let i=0;i<parts.length;i++) {
    const current=parts[i],next=parts[i+1];
    if(next&&!current.separator.includes('\n')&&random()<mergeProbability){merged.push({...current,text:current.text+current.separator+next.text,separator:next.separator});i++;}
    else merged.push(current);
  }
  return merged;
}

/** Upstream's even grouping keeps correction messages as independent bubbles. */
export function mergeReplySegments(segments,maxCount=splitterDefaults.maxSplitNum) {
  if(!Number.isSafeInteger(maxCount)||maxCount<1)throw new Error('COMPANION_INVALID_SPLITTER_CONFIG');
  if(segments.length<=maxCount)return segments;
  const starts=new Set([0]),even=[];
  segments.forEach((item,index)=>{if(item.isCorrection||item.quotePrevious){starts.add(index);if(index+1<segments.length)starts.add(index+1);}});
  let start=0;
  for(let group=0;group<maxCount;group++){even.push(start);start+=Math.ceil((segments.length-start)/(maxCount-group));}
  for(const index of even){if(starts.size>=maxCount)break;starts.add(index);}
  const indices=[...starts].sort((a,b)=>a-b);
  return indices.map((index,i)=>{
    const group=segments.slice(index,indices[i+1]??segments.length);
    return {...group[0],text:group.map((item,j)=>item.text+(j<group.length-1?item.separator:'')).join(''),separator:group.at(-1).separator};
  });
}

const punctuation=new Map([['。','.'],['、',','],['‘',"'"],['’',"'"],['“','"'],['”','"'],['【','['],['】',']'],['《','<'],['》','>']]);
function comparableChars(text) {
  return [...text].filter(char=>! /\s/u.test(char)).map(char=>{
    const code=char.codePointAt(0);
    return code>=0xff01&&code<0xff5f?String.fromCodePoint(code-0xfee0):punctuation.get(char)??char;
  });
}

function parseLlmSegments(text,raw) {
  const payload=String(raw?.text??raw).trim().replace(/^`{3}(?:json)?\s*/iu,'').replace(/\s*`{3}$/u,'');
  if(payload.replace(/^"|"$/gu,'')==='no_split')return [segment(text)];
  const values=JSON.parse(payload);
  if(Array.isArray(values)&&values.length===1&&values[0]==='no_split')return [segment(text)];
  if(!Array.isArray(values)||!values.length||values.some(value=>typeof value!=='string'||!value.trim()))throw new Error('COMPANION_INVALID_SPLIT');
  if(comparableChars(text).join('')!==comparableChars(values.join('')).join(''))throw new Error('COMPANION_INVALID_SPLIT');
  // Map the model's boundaries back to original characters, so punctuation and
  // whitespace normalization never changes the message that is sent.
  const protectedAt=protectedBoundaries(text),parts=[];
  let start=0,offset=0;
  for(let i=0;i<values.length;i++) {
    if(i===values.length-1){parts.push(segment(text.slice(start)));break;}
    let count=0,target=comparableChars(values[i]).length;
    while(offset<text.length&&count<target){const char=String.fromCodePoint(text.codePointAt(offset));offset+=char.length;if(! /\s/u.test(char))count++;}
    if(protectedAt.has(offset))throw new Error('COMPANION_INVALID_SPLIT');
    while(offset<text.length&&/\s/u.test(text[offset]))offset++;
    const part=text.slice(start,offset),trailing=part.match(/[，,\s]+$/u)?.[0]??'';
    parts.push(trailing.includes('，')||trailing.includes(',')?segment(part.slice(0,-trailing.length),trailing):segment(part));
    start=offset;
  }
  return parts;
}

export async function processCompanionReply(text,{locale='zh',random=Math.random,generate,signal,preserveFormatting=false,formalResult=false,deferMerge=false,...options}={}) {
  const settings={...splitterDefaults,...options},value=String(text??'').trim();
  if(!Number.isSafeInteger(settings.maxSplitNum)||settings.maxSplitNum<1||!['rule','llm'].includes(settings.mode))throw new Error('COMPANION_INVALID_SPLITTER_CONFIG');
  if(!value)return {segments:[],mode:settings.mode,modelCalls:0,warnings:[]};
  if(!settings.enabled||preserveFormatting||formalResult||formattedResult(value))return {segments:[segment(value)],mode:'intact',modelCalls:0,warnings:[]};
  signal?.throwIfAborted();
  let segments,modelCalls=0,warnings=[];
  if(settings.mode==='llm') {
    if(!generate)throw new Error('COMPANION_SPLITTER_MODEL_REQUIRED');
    modelCalls=1;
    const raw=await generate({system:splitterPrompts[locale==='en'?'en':'zh'],messages:[{role:'user',content:value}],purpose:'response_splitter',signal});
    signal?.throwIfAborted();
    try {segments=parseLlmSegments(value,raw);}
    catch(error) {
      if(!(error instanceof SyntaxError)&&error.message!=='COMPANION_INVALID_SPLIT')throw error;
      // A bad segmentation may not erase an otherwise successful reply.
      segments=[segment(value)];warnings=['COMPANION_INVALID_SPLIT'];
    }
  } else segments=splitRule(value,random);
  return {segments:deferMerge?segments:mergeReplySegments(segments,settings.maxSplitNum),mode:settings.mode,modelCalls,warnings};
}

export async function splitCompanionReply(text,options={}) {
  const result=await processCompanionReply(text,options);
  return result.segments.map(item=>item.text.trim()).filter(Boolean);
}
