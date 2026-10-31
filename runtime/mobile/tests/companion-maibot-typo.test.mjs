import test from 'node:test';
import assert from 'node:assert/strict';
import {readFile} from 'node:fs/promises';
import {createHash} from 'node:crypto';
import {spawnSync} from 'node:child_process';
import {ChineseTypoGenerator,processCompanionTypos,typoDefaults} from '../companions/maibot-typo.mjs';

const dataDirectory=new URL('../companions/maibot-typo-data/',import.meta.url);
const reference=JSON.parse(await readFile(new URL('reference-cases.json',dataDirectory),'utf8'));
function seeded(seed) {let state=seed;return()=>{state=(Math.imul(state,1664525)+1013904223)>>>0;return state/4294967296;};}
const options={errorRate:.4,minFrequency:9,toneErrorRate:.3,wordReplaceRate:.3};
const segment=(text,separator='')=>({text,separator,quotePrevious:false,isCorrection:false});

test('bundled lexicon preserves the complete pinned upstream dictionaries and hashes',async()=>{
  const manifest=JSON.parse(await readFile(new URL('provenance.json',dataDirectory),'utf8'));
  const data=await readFile(new URL('lexicon.json.gz',dataDirectory));
  assert.equal(createHash('sha256').update(data).digest('hex'),manifest.lexiconSha256);
  assert.equal(manifest.wordCount,349045);assert.equal(manifest.characterCount,20991);
  assert.equal(manifest.phraseCount,47111);assert.equal(manifest.homophoneWordCount,337394);
  for(const [path,hash] of Object.entries(manifest.sourceHashes))assert.equal(createHash('sha256').update(await readFile(new URL(path,dataDirectory))).digest('hex'),hash,path);
  for(const [name,hash] of Object.entries(manifest.upstream.sourceGitBlobs)) {
    const source=await readFile(new URL('upstream/'+name,dataDirectory));
    assert.equal(createHash('sha1').update(Buffer.from(`blob ${source.length}\0`)).update(source).digest('hex'),hash,name);
  }
  assert.deepEqual(typoDefaults,{enabled:true,errorRate:.01,minFrequency:9,toneErrorRate:.1,wordReplaceRate:.006,maxFrequencyDifference:200,correctionQuote:true,correctionQuoteProbability:1});
});

test('DAG and HMM segmentation, phrase pronunciation and candidates agree with pinned Python sources',async()=>{
  const generator=await ChineseTypoGenerator.create();
  for(const item of reference.segmentation)assert.deepEqual(generator.segment(item.input),item.words,item.input);
  for(const item of reference.wordCandidates){assert.deepEqual(generator.wordPinyin(item.word),item.pinyin,item.word);assert.deepEqual(generator.wordHomophones(item.word),item.candidates,item.word);}
});

test('80 identical random streams preserve upstream character, word, tone and correction decisions',async()=>{
  let changed=0,corrected=0;
  for(const item of reference.cases) {
    const generator=await ChineseTypoGenerator.create({random:seeded(item.seed),options});
    assert.deepEqual(generator.createTypoSentence(item.input),{text:item.text,correction:item.correction},`seed ${item.seed}: ${item.input}`);
    changed+=Number(item.text!==item.input);corrected+=Number(!!item.correction);
  }
  assert.ok(changed>20);assert.ok(corrected>10);
});

test('split -> typo -> correction metadata agrees with upstream postprocessor on 40 random streams',async()=>{
  let corrected=0;
  for(const item of reference.postProcessing) {
    const actual=await processCompanionTypos(item.input.map(([text,separator])=>segment(text,separator)),{locale:'zh',random:seeded(item.seed),options});
    const expected=item.output.map(value=>({text:value.text,separator:value.separator,quotePrevious:value.quote_previous,isCorrection:value.is_correction}));
    assert.deepEqual(actual,expected,`postprocessing seed ${item.seed}`);
    corrected+=actual.filter(value=>value.isCorrection).length;
  }
  assert.ok(corrected>5);
});

test('work content, English, explicit disable and formatted blocks avoid Chinese lexicon initialization',()=>{
  const url=new URL('../companions/maibot-typo.mjs',import.meta.url).href;
  const script=`const before=process.memoryUsage().heapUsed;const {processCompanionTypos}=await import(${JSON.stringify(url)});for(const opts of [{locale:'en'},{formalResult:true},{preserveFormatting:true},{options:{enabled:false}}])await processCompanionTypos([{text:'重要的工作结果',separator:'',quotePrevious:false,isCorrection:false}],opts);await processCompanionTypos([{text:'1. 重要的工作结果',separator:'',quotePrevious:false,isCorrection:false}]);console.log(process.memoryUsage().heapUsed-before);`;
  const child=spawnSync(process.execPath,['--input-type=module','-e',script],{encoding:'utf8'});
  assert.equal(child.status,0,child.stderr);
  assert.ok(Number(child.stdout)<10_000_000,child.stdout);
});

test('host preserves code, URLs, identifiers, explicit names and whole structured work output',async()=>{
  const text='看 https://example.com/事情?id=123 和 `你好`，API_key_23 是咔咔的';
  const generator=await ChineseTypoGenerator.create({random:seeded(8),options:{errorRate:1,wordReplaceRate:1,minFrequency:0}});
  const actual=generator.createTypoSentence(text,{protectedStrings:['咔咔']}).text;
  assert.ok(actual.includes('https://example.com/事情?id=123'));assert.ok(actual.includes('`你好`'));
  assert.ok(actual.includes('API_key_23'));assert.ok(actual.includes('咔咔'));
  for(const text of ['```js\nconst 你好=1;\n```','1. 明天查天气\n2. 然后回家','| 字段 | 数值 |\n| 你好 | 20 |']) {
    const input=[segment(text)];assert.strictEqual(await processCompanionTypos(input),input);
  }
});

test('disabled correction quotes preserve correction bubbles and cancellation remains observable',async()=>{
  const sample=reference.postProcessing.find(item=>item.output.some(value=>value.is_correction));
  const actual=await processCompanionTypos(sample.input.map(([text,separator])=>segment(text,separator)),{random:seeded(sample.seed),options:{...options,correctionQuote:false}});
  assert.ok(actual.some(value=>value.isCorrection));assert.ok(actual.every(value=>!value.quotePrevious));
  const abort=new AbortController();abort.abort(new Error('stopped'));
  await assert.rejects(processCompanionTypos([segment('你好啊')],{signal:abort.signal}),/stopped/);
});
