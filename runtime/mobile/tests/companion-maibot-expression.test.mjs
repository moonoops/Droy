import test from 'node:test';
import assert from 'node:assert/strict';
import {selectExpressions,expressionWeights,weightedSampleExpressions,expressionQuery} from '../companions/maibot-expression.mjs';
import {splitCompanionReply,processCompanionReply,mergeReplySegments} from '../companions/maibot-splitter.mjs';
import {withoutSources,sharedContext} from '../companions/dialogue.mjs';

const expressions=(length=12,prefix='kaka')=>Array.from({length},(_,i)=>({id:`${prefix}:e${i}`,situation:`情境${i}`,expression:`表达${i}`,sourceIds:[`${prefix}:m${i}`]}));

test('forgetting source messages also removes pending jargon and never exposes candidates as facts',()=>{
  const state={jargonCandidates:[{term:'甲',sourceIds:['m1']},{term:'乙',sourceIds:['m2']}]};
  const next=withoutSources(state,['m1']);assert.deepEqual(next.jargonCandidates,[{term:'乙',sourceIds:['m2']}]);
  assert.equal(Object.hasOwn(sharedContext(next,[],[]),'jargonCandidates'),false);
});

test('MaiBot fast selection skips small pools and makes no extra model request',async()=>{
  const generate=()=>{throw new Error('fast selection must not call a model');};
  assert.equal((await selectExpressions({context:{expressions:expressions(9)},generate})).expressions.length,0);
  const chosen=await selectExpressions({context:{expressions:expressions()},generate,random:()=>0});
  assert.equal(chosen.selectionMode,'fast');assert.equal(chosen.modelCalls,0);assert.equal(chosen.expressions.length,5);
  assert.deepEqual(chosen.selectedIds,['kaka:e0','kaka:e1','kaka:e2','kaka:e3','kaka:e4']);
  assert.deepEqual(chosen.sourceIds,['kaka:m0','kaka:m1','kaka:m2','kaka:m3','kaka:m4']);
  assert.match(chosen.prompt,/视情况自由使用/);assert.doesNotMatch(chosen.prompt,/表达11/);
  assert.equal((await selectExpressions({context:{expressions:expressions()},options:{enabled:false}})).expressions.length,0);
});

test('upstream count weights are bounded and sampling is without replacement',()=>{
  assert.deepEqual(expressionWeights([{count:1},{count:6},{count:11}]),[1,3,5]);
  assert.deepEqual(expressionWeights([{count:3},{count:3}]),[1,1]);
  assert.deepEqual(expressionWeights([{count:'invalid'},{count:-1}]),[5,1]);
  const selected=weightedSampleExpressions([{id:'a',count:1},{id:'b',count:10},{id:'c',count:1}],1,()=>0.3);
  assert.equal(selected[0].id,'b');
  assert.equal(new Set(weightedSampleExpressions(expressions(),5,()=>0.5).map(item=>item.id)).size,5);
});

test('frequent pool and all-pool samples are combined, with IDs deduplicated',async()=>{
  let draws=0;
  const chosen=await selectExpressions({context:{expressions:expressions(20).map(item=>({...item,count:2}))},random:()=>draws++<5?0:0.999});
  assert.equal(chosen.expressions.length,10);assert.equal(new Set(chosen.selectedIds).size,10);
  const same=await selectExpressions({context:{expressions:expressions(20).map(item=>({...item,count:2}))},random:()=>0});
  assert.equal(same.expressions.length,5);
});

test('fine selection uses real context and expression intent, maps IDs back and preserves scope',async()=>{
  let request,retrieval;
  const chosen=await selectExpressions({locale:'en',context:{expressions:expressions(),messages:[{id:'kaka:m11',role:'user',text:'Could you explain that again?'}]},
    args:{target_message_id:'kaka:m11',expression_intent:{focus:'that point',reply_act:'clarify',avoid:['jokes']},reply_reference:'Use the previous example.'},
    options:{mode:'fine'},retrieve:async args=>{retrieval=args;return ['kaka:e11','other:e1','kaka:e3'];},
    generate:async args=>{request=args;return '{"selected_ids":[2,1,2,999,"1"]}';}});
  assert.equal(chosen.modelCalls,1);assert.equal(chosen.retrievalMode,'embedding');
  assert.deepEqual(chosen.selectedIds,['kaka:e3','kaka:e11']);assert.deepEqual(chosen.sourceIds,['kaka:m3','kaka:m11']);
  assert.match(request.system,/Prioritize natural/);assert.doesNotMatch(request.system,/你只负责/);
  assert.match(request.messages[0].content,/clarify/);assert.match(request.messages[0].content,/Could you explain/);
  assert.match(retrieval.query,/Use the previous example/);assert.equal(retrieval.candidates.length,12);
  const maimai=await selectExpressions({context:{expressions:expressions(12,'maimai')},random:()=>0});
  assert.ok(maimai.sourceIds.every(id=>id.startsWith('maimai:')));assert.doesNotMatch(maimai.prompt,/kaka/);
});

test('fine semantic mode is callable without embeddings and allows no suitable expression',async()=>{
  let request;
  const chosen=await selectExpressions({locale:'zh',context:{expressions:expressions()},options:{mode:'fine'},generate:async args=>{request=args;return '{"selected_ids":[]}';},random:()=>0});
  assert.deepEqual(chosen.expressions,[]);assert.equal(chosen.retrievalMode,'weighted');assert.equal(chosen.modelCalls,1);
  assert.match(request.system,/挑选合适的表达/);assert.match(request.system,/默认回复语言是中文/);assert.doesNotMatch(request.system,/\{max_selected\}|\{candidates\}|Your only task/);
  const invalid=await selectExpressions({context:{expressions:expressions()},options:{mode:'fine'},generate:async()=>'invalid'});
  assert.equal(invalid.warning,'COMPANION_INVALID_EXPRESSION_SELECTION');assert.deepEqual(invalid.expressions,[]);
  assert.match(expressionQuery({expression_intent:{scene:'散步',tone:'轻松'}},'zh'),/表达场景: 散步/);
});

test('rule splitter retains upstream short-text probability and length-dependent merging',async()=>{
  assert.deepEqual(await splitCompanionReply('好啊',{random:()=>0.005}),['好','啊']);
  assert.deepEqual(await splitCompanionReply('好啊',{random:()=>0.5}),['好啊']);
  assert.deepEqual(await splitCompanionReply('先吃饭，再出门。',{random:()=>0.5}),['先吃饭，再出门']);
  assert.deepEqual(await splitCompanionReply('先吃饭，再出门。',{random:()=>0.99}),['先吃饭','再出门']);
});

test('rule splitter combines excess parts evenly and accepts an explicit different maximum',async()=>{
  const text='先出门，去商店，买蔬菜，回家做饭，然后吃饭。';
  assert.deepEqual(await splitCompanionReply(text,{random:()=>0.99}),['先出门，去商店','买蔬菜，回家做饭','然后吃饭']);
  assert.equal((await splitCompanionReply(text,{random:()=>0.99,maxSplitNum:5})).length,5);
  const result=await processCompanionReply(text,{random:()=>0.99});
  assert.equal(result.segments.map(item=>item.text+item.separator).join(''),text);
  assert.equal((await processCompanionReply(text,{random:()=>0.99,deferMerge:true})).segments.length,5);
});

test('brackets, quoted phrases, inline code, numeric quantities and URLs stay intact',async()=>{
  const text='看这里（备注，别漏掉），他说“先吃饭，再出门”，输入`a, b`，价格1,234.50元，链接https://example.org/a,b?q=1。';
  const parts=await splitCompanionReply(text,{random:()=>0.99,maxSplitNum:10});
  assert.ok(parts.includes('看这里（备注，别漏掉）'));assert.ok(parts.includes('他说“先吃饭，再出门”'));
  assert.ok(parts.includes('输入`a, b`'));assert.ok(parts.includes('价格1,234.50元'));assert.ok(parts.includes('链接https://example.org/a,b?q=1'));
  const english=await splitCompanionReply("Don't split English words or 12 000 units.",{random:()=>0.99});
  assert.deepEqual(english,["Don't split English words or 12 000 units."]);
});

test('complete work results and arbitrarily long replies are never truncated or replaced',async()=>{
  for(const text of ['Here is the code:\n```js\nconst x = [1, 2];\n\nconsole.log(x);\n```','搬家清单\n1. 联系货车\n2. 打包书籍\n3. 搬家当天检查水电','| Item | Value |\n| --- | --- |\n| A | 2 |'])
    assert.deepEqual(await splitCompanionReply(text,{random:()=>0.99}),[text]);
  const long='这是一份需要完整保留的正式结果，'.repeat(200);
  const result=await processCompanionReply(long,{random:()=>0.99});
  assert.equal(result.segments.map(item=>item.text+item.separator).join(''),long);
  assert.deepEqual(await splitCompanionReply(long,{formalResult:true}),[long]);
});

test('LLM splitting invokes the provided model and maps normalized punctuation back to the original',async()=>{
  let request;
  const result=await processCompanionReply('你好， 世界。',{mode:'llm',locale:'en',generate:async args=>{request=args;return '["你好,","世界."]';}});
  assert.match(request.system,/Choose split positions only/);assert.equal(request.purpose,'response_splitter');
  assert.equal(result.modelCalls,1);assert.deepEqual(result.warnings,[]);
  assert.equal(result.segments.map(item=>item.text+item.separator).join(''),'你好， 世界。');
  assert.deepEqual(result.segments.map(item=>item.text),['你好','世界。']);
});

test('LLM rewrites or cuts inside protected spans cannot damage the generated reply',async()=>{
  const original='你好（今天，明天），再见。';
  for(const response of ['["重写了"]','["你好（今天，","明天），再见。"]','not-json']){
    const result=await processCompanionReply(original,{mode:'llm',generate:async()=>response});
    assert.deepEqual(result.warnings,['COMPANION_INVALID_SPLIT']);assert.equal(result.segments[0].text,original);
  }
  assert.deepEqual(await splitCompanionReply(original,{mode:'llm',generate:async()=>'"no_split"'}),[original]);
});

test('corrections retain their own message boundaries even above the nominal maximum',()=>{
  const segments=[{text:'原消息',separator:'，'},{text:'更正',separator:'',isCorrection:true,quotePrevious:true},{text:'继续',separator:'，'},{text:'结束',separator:''}];
  const merged=mergeReplySegments(segments,2);
  assert.equal(merged.length,3);assert.equal(merged[1].text,'更正');assert.equal(merged[1].quotePrevious,true);
  assert.equal(merged[2].text,'继续，结束');
});
