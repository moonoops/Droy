// MaiBot ChineseTypoGenerator and response correction processing, ported from
// fe88ec6b6053e40eaf430cb5ba7ed7e805add2cb (GPL-3.0). Lexicon, dependency
// licenses, original sources and reproducible generator: ./maibot-typo-data/.
import {readFile} from 'node:fs/promises';
import {gunzipSync} from 'node:zlib';

export const typoDefaults=Object.freeze({enabled:true,errorRate:0.01,minFrequency:9,
  toneErrorRate:0.1,wordReplaceRate:0.006,maxFrequencyDifference:200,
  correctionQuote:true,correctionQuoteProbability:1});
const chinese=/[\u4e00-\u9fff]/u;
const chineseWord=/^[\u4e00-\u9fff]+$/u;
let lexiconPromise;
async function loadLexicon() {
  lexiconPromise??=readFile(new URL('./maibot-typo-data/lexicon.json.gz',import.meta.url))
    .then(bytes=>{
      const data=JSON.parse(gunzipSync(bytes).toString('utf8'));
      for(const field of ['wordFrequencies','wordHomophoneHashes','wordHomophoneIndices']) {
        const bytes=Buffer.from(data[field],'base64');
        data[field]=new Uint32Array(bytes.buffer,bytes.byteOffset,bytes.byteLength/4);
      }
      return data;
    });
  return lexiconPromise;
}
const choose=(items,random)=>items[Math.floor(random()*items.length)];
const minimum=-3.14e100;
const previous={B:'ES',M:'MB',S:'SE',E:'BM'};
function lowerBound(values,target) {
  let low=0,high=values.length;
  while(low<high){const middle=(low+high)>>>1;if(values[middle]<target)low=middle+1;else high=middle;}
  return low;
}
function wordFrequency(word,data) {
  const index=lowerBound(data.words,word);
  return data.words[index]===word?data.wordFrequencies[index]:0;
}
function pinyinHash(indices) {
  let value=2166136261;
  for(const index of indices)value=Math.imul(value^index,16777619)>>>0;
  return value;
}

function hmmChinese(text,data) {
  const chars=[...text],states=['B','M','E','S'];
  let scores={},paths={};
  for(const state of states){scores[state]=data.start[state]+(data.emission[state][chars[0]]??minimum);paths[state]=[state];}
  for(const char of chars.slice(1)) {
    const nextScores={},nextPaths={};
    for(const state of states) {
      let best=-Infinity,bestState;
      for(const prev of previous[state]) {
        const score=scores[prev]+(data.transition[prev][state]??minimum)+(data.emission[state][char]??minimum);
        if(score>best||score===best&&prev>bestState){best=score;bestState=prev;}
      }
      nextScores[state]=best;nextPaths[state]=[...paths[bestState],state];
    }
    scores=nextScores;paths=nextPaths;
  }
  const selected=scores.E>scores.S?'E':'S',result=[];
  let begin=0,next=0;
  for(let i=0;i<chars.length;i++) {
    const state=paths[selected][i];
    if(state==='B')begin=i;
    else if(state==='E'){result.push(chars.slice(begin,i+1).join(''));next=i+1;}
    else if(state==='S'){result.push(chars[i]);next=i+1;}
  }
  if(next<chars.length)result.push(chars.slice(next).join(''));
  return result;
}
function hmmCut(text,data) {
  return text.split(/([\u4e00-\u9fd5]+)/u).filter(Boolean).flatMap(block=>
    /^[\u4e00-\u9fd5]+$/u.test(block)?hmmChinese(block,data):block.split(/([a-zA-Z0-9]+(?:\.\d+)?%?)/u).filter(Boolean));
}
function dagCut(text,data) {
  const chars=[...text],route=Array(chars.length+1),logTotal=Math.log(data.total),result=[];
  route[chars.length]={score:0,end:chars.length};
  for(let start=chars.length-1;start>=0;start--) {
    let best=-Infinity,end=start,found=false;
    for(let length=1;length<=data.maxWordLength&&start+length<=chars.length;length++) {
      const word=chars.slice(start,start+length).join(''),frequency=wordFrequency(word,data);
      if(!frequency)continue;
      found=true;
      const last=start+length-1,score=Math.log(frequency)-logTotal+route[last+1].score;
      if(score>=best){best=score;end=last;}
    }
    if(!found)best=-logTotal+route[start+1].score;
    route[start]={score:best,end};
  }
  let position=0,buffer='';
  const flush=()=>{
    if(buffer.length===1)result.push(buffer);
    else if(buffer)result.push(...(wordFrequency(buffer,data)?[...buffer]:hmmCut(buffer,data.hmm)));
    buffer='';
  };
  while(position<chars.length) {
    const end=route[position].end+1,word=chars.slice(position,end).join('');
    if(end-position===1)buffer+=word;
    else {flush();result.push(word);}
    position=end;
  }
  flush();return result;
}
function segment(text,data) {
  return text.split(/([\u4e00-\u9fd5a-zA-Z0-9+#&._%\-]+)/u).filter(Boolean).flatMap(block=>
    /^[\u4e00-\u9fd5a-zA-Z0-9+#&._%\-]+$/u.test(block)?dagCut(block,data):
      block.split(/(\r\n|\s)/u).filter(Boolean).flatMap(part=>/^\s+$/u.test(part)?[part]:[...part]));
}
function wordPinyin(word,data) {
  const chars=[...word],result=[];
  for(let start=0;start<chars.length;) {
    let matched;
    for(let length=Math.min(data.maxPhraseLength,chars.length-start);length>1;length--) {
      const candidate=chars.slice(start,start+length).join('');
      if(data.phrases[candidate]){matched=data.phrases[candidate];start+=length;break;}
    }
    if(matched)result.push(...matched);
    else result.push(data.charPinyin[chars[start++]]);
  }
  return result;
}

export class ChineseTypoGenerator {
  static async create({random=Math.random,options={}}={}) {
    return new ChineseTypoGenerator(await loadLexicon(),random,options);
  }
  constructor(data,random,options) {this.data=data;this.random=random;this.options={...typoDefaults,...options};}
  segment(text){return segment(text,this.data);}
  wordPinyin(word){return wordPinyin(word,this.data).map(index=>this.data.syllables[index]);}
  replacementProbability(original,target) {
    if(target>original)return 1;
    const difference=original-target;
    return difference>this.options.maxFrequencyDifference?0:Math.exp(-3*difference/this.options.maxFrequencyDifference);
  }
  similarCharacters(char,pinyinIndex) {
    const {data,random,options}=this,homophones=[];
    if(random()<options.toneErrorRate) {
      const syllable=data.syllables[pinyinIndex];
      let wrong;
      if(!/\d$/u.test(syllable))wrong=syllable+'1';
      else {
        const tone=Number(syllable.at(-1)),tones=[1,2,3,4].filter(value=>value!==tone);
        wrong=syllable.slice(0,-1)+choose(tones,random);
      }
      const index=data.syllables.indexOf(wrong);
      homophones.push(...(data.pinyinGroups[index]??[]));
    }
    homophones.push(...(data.pinyinGroups[pinyinIndex]??[]));
    const original=data.charFrequency[char]??0;
    return homophones.filter(value=>value!==char&&(data.charFrequency[value]??0)>=options.minFrequency)
      .map(value=>({value,probability:this.replacementProbability(original,data.charFrequency[value]??0)}))
      .filter(item=>item.probability>0).sort((a,b)=>b.probability-a.probability).slice(0,5).map(item=>item.value);
  }
  wordHomophones(word) {
    if([...word].length===1)return [];
    const {data,options}=this,pinyin=wordPinyin(word,data),hash=pinyinHash(pinyin),minimumWordFrequency=wordFrequency(word,data)*0.1,candidates=[];
    for(let index=lowerBound(data.wordHomophoneHashes,hash);index<data.wordHomophoneHashes.length&&data.wordHomophoneHashes[index]===hash;index++) {
      const wordIndex=data.wordHomophoneIndices[index],candidate=data.words[wordIndex],frequency=data.wordFrequencies[wordIndex];
      if(candidate!==word&&frequency>0&&frequency>=minimumWordFrequency&&candidate.length===pinyin.length&&[...candidate].every((char,i)=>data.charPinyin[char]===pinyin[i]))candidates.push({word:candidate,frequency});
    }
    return candidates.map(({word:candidate,frequency})=>({word:candidate,score:frequency*0.7+
        [...candidate].reduce((sum,char)=>sum+(data.charFrequency[char]??0),0)/[...candidate].length*0.3}))
      .filter(candidate=>candidate.score>=options.minFrequency).sort((a,b)=>b.score-a.score).slice(0,5).map(candidate=>candidate.word);
  }
  createTypoSentence(sentence,{protectedStrings=[]}={}) {
    const {data,random,options}=this,parts=[],wordTypos=[],characterTypos=[];
    let offset=0;
    const ranges=protectedRanges(sentence,protectedStrings);
    for(const word of segment(sentence,data)) {
      const start=offset;offset+=word.length;
      if(!chineseWord.test(word)||ranges.some(range=>start<range.end&&offset>range.start)){parts.push(word);continue;}
      const characters=[...word],pinyin=wordPinyin(word,data);
      if(characters.length>1&&random()<options.wordReplaceRate) {
        const candidates=this.wordHomophones(word);
        if(candidates.length){parts.push(choose(candidates,random));wordTypos.push(word);continue;}
      }
      const next=[],rate=options.errorRate*(0.7**(characters.length-1));
      for(let index=0;index<characters.length;index++) {
        const char=characters[index];
        if(random()<rate) {
          const candidates=this.similarCharacters(char,pinyin[index]);
          if(candidates.length) {
            const candidate=choose(candidates,random),probability=this.replacementProbability(data.charFrequency[char]??0,data.charFrequency[candidate]??0);
            if(random()<probability){next.push(candidate);characterTypos.push(char);continue;}
          }
        }
        next.push(char);
      }
      parts.push(next.join(''));
    }
    let correction=null;
    if(random()<0.5){if(wordTypos.length)correction=choose(wordTypos,random);else if(characterTypos.length)correction=choose(characterTypos,random);}
    return {text:parts.join(''),correction};
  }
}

function protectedRanges(text,strings) {
  const ranges=[];
  for(const match of text.matchAll(/`+[^`\n]*`+|https?:\/\/[^\s<>]+|\b[\w.+-]+@[\w.-]+\.[A-Za-z]{2,}\b|\b[A-Za-z_][A-Za-z0-9_.:/@#-]*\b|[+-]?\d[\d.,:/%_-]*/gu))ranges.push({start:match.index,end:match.index+match[0].length});
  for(const value of strings.filter(Boolean)) {
    for(let start=text.indexOf(value);start!==-1;start=text.indexOf(value,start+value.length))ranges.push({start,end:start+value.length});
  }
  return ranges;
}
const structured=text=>/^\s*(?:`{3}|~{3}|#{1,6}\s|[-*+]\s|\d+[.)、]\s|\|.*\|\s*$|\$\$)/mu.test(text);

/** Correction bubbles retain their source relationship through delivery. */
export async function processCompanionTypos(segments,{locale='zh',random=Math.random,options={},formalResult=false,preserveFormatting=false,protectedStrings=[],signal}={}) {
  const settings={...typoDefaults,...options};
  if(!settings.enabled||!String(locale).startsWith('zh')||formalResult||preserveFormatting||!segments.some(item=>!item.isCorrection&&!structured(item.text)&&chinese.test(item.text)))return segments;
  signal?.throwIfAborted();
  const generator=await ChineseTypoGenerator.create({random,options:settings}),result=[];
  signal?.throwIfAborted();
  for(const segment of segments) {
    if(segment.isCorrection||structured(segment.text)){result.push(segment);continue;}
    const typo=generator.createTypoSentence(segment.text,{protectedStrings});
    if(typo.correction) {
      if(random()<0.5) {
        const quotePrevious=settings.correctionQuote&&random()<settings.correctionQuoteProbability;
        result.push({...segment,text:typo.text});
        result.push({text:typo.correction,separator:'',quotePrevious,isCorrection:true});
      } else result.push(segment);
    } else result.push({...segment,text:typo.text});
  }
  return result;
}
