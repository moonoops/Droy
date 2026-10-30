import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { createHash } from 'node:crypto';
import { defaultProfiles, COMPANION_PERSONAS, LEGACY_COMPANION_PERSONAS } from '../companions/profiles.mjs';
import { buildPlannerPrompt, buildReplyerRequest, buildWaitReminder, buildAttentionDriftPrompt,
  readMaiBotPrompt, renderMaiBotPrompt, MAIBOT_DEFAULT_OPTIONS, replyToolDescriptions } from '../companions/maibot-prompts.mjs';

const source = new URL('../companions/maibot/upstream/', import.meta.url);
const profile = () => defaultProfiles()[1];
const texts = request => request.messages.map(message => message.content.filter(block => block.type === 'text').map(block => block.text).join('\n'));
const context = () => ({ currentTime: '2026-10-28T10:00:00+08:00', timeZone: 'Asia/Shanghai', messages: [
  { id: 'u1', role: 'user', text: '我昨天说的那个计划', createdAt: '2026-10-27T10:00:00+08:00' },
  { id: 'a1', role: 'assistant', text: '你说周末去爬山', replyTo: 'u1' },
  { id: 'u2', role: 'user', text: '下雨就改天吧', createdAt: '2026-10-28T10:00:00+08:00' },
] });

test('sticker-only history and reply targets preserve verified meaning in both prompt languages',()=>{
  for(const locale of ['zh','en']) {
    const current={...context(),messages:[{id:'fictional-sticker',role:'user',type:'sticker',text:'',stickerId:'sticker-support',
      sticker:{description:'Unverified invented meaning'}}]};
    const request=buildReplyerRequest({profile:profile(),locale,context:current,args:{msg_id:'fictional-sticker'}});
    const history=texts(request)[0],target=texts(request).at(-1);
    assert.match(history,/sticker-support/);assert.match(target,/sticker-support/);
    assert.match(history,locale==='zh'?/爱心/:/pink heart/);
    assert.match(target,locale==='zh'?/爱心/:/pink heart/);
    assert.doesNotMatch(texts(request).join('\n'),/Unverified invented meaning/);
  }
});

test('paired prompts separate reply anchors from sparse visible quotes and preserve customized identity',()=>{
  for(const locale of ['zh','en']) {
    const p={...profile(),personalityOverride:'原样保留的自定义性格 / custom persona'};
    const planner=buildPlannerPrompt({profile:p,locale});
    assert.match(planner,/quoteReply/);assert.match(planner,/false/);
    assert.match(planner,/原样保留的自定义性格 \/ custom persona/);
    assert.ok(replyToolDescriptions(locale).quoteReply);
    const current=context();current.currentTime='2026-10-28T02:00:00Z';current.userProfile={name:'Fictional Lin'};
    current.messages[1].quoteReply=false;
    let request=buildReplyerRequest({profile:p,locale,context:current,args:{msg_id:'u2'}});
    assert.doesNotMatch(texts(request)[1],/msg_id|reply_target|quote=/);assert.match(request.system,/"replyTo":"u1"/);assert.match(request.system,/"quoteReply":false/);
    assert.match(request.system,/Fictional Lin/);assert.match(request.system,/原样保留的自定义性格 \/ custom persona/);
    assert.match(texts(request).at(-1),/2026-10-28T10:00:00\+08:00/);
    assert.match(texts(request).at(-1),/Asia\/Shanghai, UTC\+08:00/);
    current.messages[1].quoteReply=true;
    request=buildReplyerRequest({profile:p,locale,context:current,args:{msg_id:'u2'}});
    assert.match(request.system,/"quoteReply":true/);assert.equal(texts(request)[1],'你说周末去爬山');
  }
});

test('vendored sources match both upstream Git blob hashes and SHA-256', () => {
  const manifest = JSON.parse(readFileSync(new URL('manifest.json', source), 'utf8'));
  assert.equal(manifest.revision, 'fe88ec6b6053e40eaf430cb5ba7ed7e805add2cb');
  assert.ok(manifest.files.length >= 70);
  for (const file of manifest.files) {
    const bytes = readFileSync(new URL(file.path, source));
    assert.equal(createHash('sha256').update(bytes).digest('hex'), file.sha256, file.path);
    assert.equal(createHash('sha1').update('blob ' + bytes.length + '\0').update(bytes).digest('hex'), file.gitBlobSha, file.path);
  }
});

test('Maimai defaults retain all three exact original Chinese persona fields', () => {
  const original = JSON.parse(readFileSync(new URL('../companions/maibot/persona-source.json', import.meta.url), 'utf8'));
  assert.equal(COMPANION_PERSONAS.maimai.personality.zh, original.personality);
  assert.equal(COMPANION_PERSONAS.maimai.behaviorStyle.zh, original.behavior_style);
  assert.equal(COMPANION_PERSONAS.maimai.replyStyle.zh, original.reply_style);
  assert.match(COMPANION_PERSONAS.maimai.personality.zh, /一般不包容且善良/);
  assert.notEqual(COMPANION_PERSONAS.maimai.personality.zh, LEGACY_COMPANION_PERSONAS.maimai.personality.zh);
  assert.match(COMPANION_PERSONAS.kaka.personality.zh, /男性上班族/);
  assert.doesNotMatch(COMPANION_PERSONAS.kaka.personality.zh, /黑猫/);
  for (const persona of Object.values(COMPANION_PERSONAS)) for (const field of ['personality', 'behaviorStyle', 'replyStyle']) {
    assert.ok(persona[field].zh); assert.ok(persona[field].en);
  }
});

test('normal Planner retains independent decisions, private-chat pacing and mapped available tools', () => {
  for (const locale of ['zh', 'en']) {
    const prompt = buildPlannerPrompt({ profile: profile(), locale });
    assert.match(prompt, /companion_reply/); assert.match(prompt, /companion_wait/); assert.match(prompt, /companion_quiet/);
    assert.match(prompt, /companion_recall/); assert.match(prompt, /companion_work/); assert.match(prompt, /companion_emoji/);
    assert.match(prompt, /reply_reference/); assert.match(prompt, /expression_intent/);
    assert.doesNotMatch(prompt, /tool_search|view_forward_message|deferred tools|session_id|attach_emoji/);
    assert.doesNotMatch(prompt, /different people|all users|QQ群|群聊|对所有用户/);
    assert.match(prompt, locale === 'zh' ? /考虑对方的发言频率/ : /Consider how often the other person speaks/);
    assert.match(prompt, locale === 'zh' ? /当前请求中明确指定/ : /current user request explicitly specifies/);
  }
});

test('work Planner keeps the persona and completes through reply without recursive delegation or idle chat tools', () => {
  for (const locale of ['zh', 'en']) {
    const prompt = buildPlannerPrompt({ profile: profile(), locale, kind: 'work' });
    assert.match(prompt, /companion_reply/);
    assert.doesNotMatch(prompt, /companion_work|companion_wait|companion_quiet|companion_emoji|companion_stickers/);
    assert.ok(prompt.includes(COMPANION_PERSONAS.maimai.personality[locale]));
    assert.match(prompt, locale === 'zh' ? /不再创建或委派/ : /Do not create or delegate/);
  }
});

test('unavailable memory and work tools are omitted; attached emoji requires enabled new-send logic', () => {
  const p = profile(); p.settings.memoryEnabled = false;
  assert.doesNotMatch(buildPlannerPrompt({ profile: p, availableTools: ['companion_reply', 'companion_wait', 'companion_quiet'] }), /companion_recall|companion_work|companion_emoji/);
  assert.match(buildPlannerPrompt({ profile: profile(), options: { useNewSendLogic: true } }), /attach_emoji/);
});

test('Replyer history remains user/assistant messages and reference, target and length are separate', () => {
  const request = buildReplyerRequest({ profile: profile(), context: context(), args: { msg_id: 'u2', reply_reference: '理解改天的打算，可以自然接话', reply_style: '简短表达' }, expressions: '用“那就”自然接话' });
  assert.deepEqual(request.messages.slice(0, 3).map(message => message.role), ['user', 'assistant', 'user']);
  assert.match(texts(request)[0], /^\[msg_id=u1 .*\]\n我昨天说的那个计划$/);
  assert.equal(texts(request)[1], '你说周末去爬山');
  assert.match(texts(request)[3], /表达习惯/);
  assert.match(texts(request)[4], /【回复信息参考】/);
  assert.match(texts(request)[5], /msg_id 为 u2/);
  assert.match(texts(request)[5], /2026-10-28T10:00:00\+08:00/);
  assert.match(texts(request).at(-1), /允许句子残缺/);
  assert.match(request.system, /不用完全遵守/);
  assert.doesNotMatch(request.system, /理解改天的打算/);
});

test('internal tasks and system messages do not impersonate real history or become system instructions', () => {
  const current = context();
  current.messages.push({ id: 'internal', role: 'system', text: 'SYSTEM_SENTINEL' }, { id: 'work', role: 'user', type: 'task', text: 'TASK_SENTINEL' }, { id: 'notice', role: 'user', sourceType: 'notification', text: 'NOTIFY_SENTINEL' });
  const request = buildReplyerRequest({ profile: profile(), context: current, args: { msg_id: 'u2', reply_reference: 'REFERENCE_SENTINEL' } });
  assert.doesNotMatch(JSON.stringify(request), /SYSTEM_SENTINEL|TASK_SENTINEL|NOTIFY_SENTINEL/);
  assert.doesNotMatch(request.system, /REFERENCE_SENTINEL/);
  assert.ok(texts(request).some(text => text.includes('REFERENCE_SENTINEL')));
});

test('reply targets validate real IDs; empty target is allowed for proactive and work messages', () => {
  assert.throws(() => buildReplyerRequest({ profile: profile(), context: context(), args: { msg_id: 'invented' } }), /COMPANION_REPLY_TARGET_NOT_FOUND/);
  assert.doesNotThrow(() => buildReplyerRequest({ profile: profile(), context: context(), args: { msg_id: '' } }));
  const self = buildReplyerRequest({ profile: profile(), context: context(), args: { msg_id: 'a1' } });
  assert.match(texts(self).at(-1), /补充说明你自己/);
  const repeated = buildReplyerRequest({ profile: profile(), context: context(), args: { msg_id: 'u1' } });
  assert.match(texts(repeated).at(-1), /你刚刚已经回复过这条消息/);
});

test('alternate style and attention drift are real opt-in paths with unchanged defaults', () => {
  assert.equal(MAIBOT_DEFAULT_OPTIONS.multipleProbability, 0);
  assert.equal(MAIBOT_DEFAULT_OPTIONS.attentionDrift.enabled, false);
  const normal = buildReplyerRequest({ profile: profile(), context: context() });
  assert.doesNotMatch(JSON.stringify(normal), /你的说话风格可以尝试|注意力漂移风格/);
  const opted = buildReplyerRequest({ profile: profile(), context: context(), options: { multipleProbability: 1, random: () => 0, attentionDrift: { enabled: true } } });
  assert.ok(texts(opted).some(text => text.includes('你的风格平淡但不失讽刺')));
  assert.match(opted.system, /漂移档位：明显发散/);
  assert.match(buildAttentionDriftPrompt('en', { enabled: true, driftLevel: 'wild', anchorPolicy: 'strict', reactionStyle: 'reserved' }), /Drift level: wild/);
});

test('retro private-chat path uses original templates and deliberately skips normal expression/drift injection', () => {
  for (const locale of ['zh', 'en']) {
    const request = buildReplyerRequest({ profile: profile(), locale, context: context(), args: { msg_id: 'a1', reply_reference: 'REFERENCE_SENTINEL' }, expressions: 'EXPRESSION_SENTINEL', options: { replyerRetroPrompt: true, attentionDrift: { enabled: true } } });
    assert.equal(request.messages.length, 1);
    assert.match(texts(request)[0], /REFERENCE_SENTINEL/);
    assert.match(texts(request)[0], /a1/);
    assert.doesNotMatch(JSON.stringify(request), /EXPRESSION_SENTINEL|Attention drift style|注意力漂移风格/);
    assert.match(request.system, locale === 'zh' ? /当前回答语言/ : /Current response language/);
  }
});

test('keyword reaction and named capture replacements are applied to the real target only', () => {
  const request = buildReplyerRequest({ profile: profile(), context: context(), args: { msg_id: 'u2' }, options: { keywordReaction: {
    keywordRules: [{ keywords: ['下雨'], reaction: '接住改期的想法' }, { keywords: ['计划'], reaction: 'WRONG_TARGET' }],
    regexRules: [{ regex: ['(?<weather>下雨)'], reaction: '提到[weather]' }],
  } } });
  assert.match(texts(request).at(-1), /接住改期的想法/);
  assert.match(texts(request).at(-1), /提到下雨/);
  assert.doesNotMatch(JSON.stringify(request), /WRONG_TARGET/);
});

test('template rendering preserves escaped JSON braces and never interpolates inserted text twice', () => {
  const rendered = renderMaiBotPrompt('maisaka_expression_selector', 'en', { max_selected: 3, candidates: 'literal {max_selected} {{untrusted}}' });
  assert.match(rendered, /"selected_ids"/);
  assert.match(rendered, /literal \{max_selected\} \{\{untrusted\}\}/);
  assert.throws(() => renderMaiBotPrompt('maisaka_expression_selector', 'zh', { candidates: '' }), /MAIBOT_PROMPT_VALUE_MISSING:max_selected/);
  assert.throws(() => readMaiBotPrompt('../../secret'), /MAIBOT_PROMPT_NAME_INVALID/);
  for (const locale of ['zh', 'en']) {
    assert.match(buildWaitReminder(locale), /companion_quiet/);
    const descriptions = replyToolDescriptions(locale);
    for (const key of ['description', 'msg_id', 'reply_reference', 'reply_style', 'expression_intent', 'focus', 'reply_act', 'scene', 'tone', 'prefer', 'avoid', 'attach_pic']) assert.ok(descriptions[key]);
  }
});

test('current English language and user-specified language exception survive original persona, work and style prompts', () => {
  const request = buildReplyerRequest({ profile: profile(), locale: 'en', kind: 'work', context: context(), args: { reply_style: '长回复' } });
  assert.match(request.system, /current user request explicitly specifies a different response language/);
  assert.match(request.system, /user’s requested length, format/);
  assert.doesNotMatch(request.system, /QQ group|chatting in the group/);
  assert.match(texts(request).at(-1), /more detailed comments/);
});

test('visual mode keeps shared opaque image references in their historical positions and caps the newest images', () => {
  const current = context();
  current.messages[0].attachments = [{ id: 'im1', name: 'first.png', mediaType: 'image/png', ref: { attachmentId: 'opaque1' } }];
  current.messages[2].attachments = [{ id: 'im2', name: 'second.png', mediaType: 'image/png', ref: { attachmentId: 'opaque2' } }];
  const normal = buildReplyerRequest({ profile: profile(), context: current });
  assert.equal(normal.messages.flatMap(message => message.content).filter(block => block.type === 'image').length, 0);
  assert.match(texts(normal)[0], /first.png/);
  const visual = buildReplyerRequest({ profile: profile(), context: current, options: { enableVisualMessage: true, maxImageNum: 1 } });
  const images = visual.messages.flatMap(message => message.content).filter(block => block.type === 'image');
  assert.deepEqual(images, [{ type: 'image', attachment: { attachmentId: 'opaque2' } }]);
  const retro = buildReplyerRequest({ profile: profile(), context: current, options: { replyerRetroPrompt: true, enableVisualMessage: true, maxImageNum: 1 } });
  assert.deepEqual(retro.messages[0].content.filter(block => block.type === 'image'), images);
  assert.match(retro.messages[0].content.at(-1).text, /只输出回复内容/);
});

test('completed and failed task IDs are internal result anchors, never fabricated assistant history', () => {
  for (const locale of ['zh', 'en']) for (const state of ['completed', 'failed']) for (const replyerRetroPrompt of [false, true]) {
    const current = context();
    current.messages.push({ id: 'task-weather', role: 'assistant', type: 'task', text: 'TASK_CARD_TEXT', task: {
      title: 'WEATHER_TASK_TITLE', state, summary: state === 'completed' ? 'WEATHER_REPORT_RESULT' : 'WEATHER_SERVICE_FAILED',
      ...(state === 'failed' ? { error: 'WEATHER_SERVICE_UNAVAILABLE' } : {}),
    } });
    const request = buildReplyerRequest({ profile: profile(), locale, context: current,
      args: { msg_id: 'task-weather', reply_reference: 'Report the actual task result' }, options: { replyerRetroPrompt } });
    const all = texts(request).join('\n');
    assert.match(all, /WEATHER_TASK_TITLE/);
    assert.match(all, state === 'completed' ? /WEATHER_REPORT_RESULT/ : /WEATHER_SERVICE_FAILED/);
    assert.match(all, locale === 'zh' ? /内部工作结果参考/ : /Internal work-result reference/);
    assert.match(all, locale === 'zh' ? /不是用户或你已经发出的聊天原话/ : /not something the user or you already said/);
    assert.match(all, locale === 'zh' ? /失败或取消不表示工作完成/ : /failed or cancelled task is not completed work/);
    assert.doesNotMatch(all, /TASK_CARD_TEXT/);
    assert.doesNotMatch(all, /补充说明你自己|You want to add to your own message/);
    assert.ok(!request.messages.some(message => message.role === 'assistant' && message.content.some(block => block.text?.includes('WEATHER_TASK_TITLE'))));
    assert.match(request.system, locale === 'zh' ? /本次回复在交付工作结果/ : /This reply delivers a work result/);
    if (state === 'failed') assert.match(all, /WEATHER_SERVICE_UNAVAILABLE/);
    assert.throws(() => buildReplyerRequest({ profile: profile(), locale, context: current, args: { msg_id: 'unknown-task' }, options: { replyerRetroPrompt } }), /COMPANION_REPLY_TARGET_NOT_FOUND/);
  }
});

test('voice messages are identified to Replyer without inventing an empty transcript', () => {
  for (const locale of ['zh', 'en']) {
    for (const text of ['', 'Fictional recognized words']) {
      const message = { id: 'voice-source', role: 'user', type: 'voice', text, voice: { transcriptionState: text ? 'ready' : 'failed' } };
      const request = buildReplyerRequest({ profile: profile(), locale, context: { ...context(), messages: [message] }, args: { msg_id: message.id } });
      assert.match(texts(request)[0], locale === 'zh' ? /用户发送的是语音消息/ : /user sent a voice message/);
      assert.match(texts(request)[0], locale === 'zh' ? /不要猜测录音内容/ : /Do not invent the recording content/);
      if (text) assert.ok(texts(request)[0].endsWith(text));
      assert.equal(message.text, text);
    }
  }
});

test('reply model receives current user biography in both locales', () => {
  for (const locale of ['zh','en']) {
    const current=context();current.userProfile={name:'Fictional Lin',bio:'Fictional architect'};
    const request=buildReplyerRequest({profile:profile(),locale,context:current,args:{msg_id:'u2'}});
    assert.match(request.system,/Fictional Lin/);assert.match(request.system,/Fictional architect/);
  }
});

test('custom identity resolves a coherent role for both planners and replyers in either language', () => {
  const original = profile(), snapshot = structuredClone(original);
  for (const locale of ['zh', 'en']) {
    const custom = { ...original, personalityOverride: 'Fictional male programmer; reserved and direct.' };
    const planner = buildPlannerPrompt({ profile: custom, locale });
    const replyer = buildReplyerRequest({ profile: custom, locale, context: context() });
    for (const prompt of [planner, replyer.system]) {
      assert.match(prompt, /Fictional male programmer/);
      assert.doesNotMatch(prompt, /女大学生|female university student|Tieba|贴吧/);
    }
    const edited = { ...custom, behaviorStyleOverride: 'Ask only when clarification is needed.', replyStyleOverride: 'Dry humor without catchphrases.' };
    assert.match(buildPlannerPrompt({ profile: edited, locale }), /Ask only when clarification is needed/);
    assert.match(buildReplyerRequest({ profile: edited, locale, context: context() }).system, /Dry humor without catchphrases/);
  }
  assert.deepEqual(original, snapshot, 'resolving a custom instance never mutates its preset');
});

test('replyer labels tentative impressions separately and prioritizes the user correction', () => {
  for (const locale of ['zh', 'en']) {
    const request = buildReplyerRequest({ profile: profile(), locale, context: { ...context(),
      memories: [{ text: 'Confirmed fictional memory' }], impressions: [{ text: 'Tentative fictional impression' }] } });
    const references = texts(request).join('\n');
    const marker = locale === 'zh' ? '不是已确认事实' : 'not confirmed facts';
    assert.ok(references.indexOf(marker) > references.indexOf('Confirmed fictional memory'));
    assert.ok(references.indexOf('Tentative fictional impression') > references.indexOf(marker));
  }
});
