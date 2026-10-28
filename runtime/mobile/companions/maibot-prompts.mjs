import { readFileSync } from 'node:fs';
import { randomUUID } from 'node:crypto';
import { responseLanguagePrompts } from '../prompt.mjs';
import { localTimeContext } from './dialogue.mjs';
import { replyToolDescriptions as baseReplyToolDescriptions } from './maibot/tool-copy.mjs';
export { MAIBOT_REPLY_TOOL_COPY } from './maibot/tool-copy.mjs';

export function replyToolDescriptions(locale = 'zh') {
  return { ...baseReplyToolDescriptions(locale), quoteReply: copy[language(locale)].quoteDescription };
}

const cache = new Map();
const language = locale => /^en(?:-|$)/i.test(locale ?? '') ? 'en' : 'zh';
const upstreamLocale = locale => language(locale) === 'en' ? 'en-US' : 'zh-CN';
const data = name => JSON.parse(readFileSync(new URL('./maibot/' + name + '.json', import.meta.url), 'utf8'));
const personaSource = data('persona-source');
const driftSource = data('attention-drift');
const chatSource = data('chat-source');

export const MAIBOT_DEFAULT_OPTIONS = Object.freeze({
  multipleProbability: 0,
  replyerRetroPrompt: false,
  useNewSendLogic: false,
  enableVisualMessage: false,
  maxImageNum: 64,
  attentionDrift: Object.freeze({ enabled: false, driftLevel: 'scattered', anchorPolicy: 'balanced', reactionStyle: 'lively' }),
});

export function readMaiBotPrompt(name, locale = 'zh') {
  if (!/^[a-z_]+$/.test(name)) throw new Error('MAIBOT_PROMPT_NAME_INVALID');
  const path = upstreamLocale(locale) + '/' + name + '.prompt';
  if (!cache.has(path)) cache.set(path, readFileSync(new URL('./maibot/upstream/prompts/' + path, import.meta.url), 'utf8'));
  return cache.get(path);
}

// Python str.format's escaped JSON braces are preserved, and inserted user text is never rendered twice.
export function renderMaiBotTemplate(template, variables) {
  return template.replace(/\{\{|\}\}|\{([a-zA-Z_][a-zA-Z0-9_]*)\}/g, (match, key) => {
    if (match === '{{') return '{';
    if (match === '}}') return '}';
    if (!Object.hasOwn(variables, key)) throw new Error('MAIBOT_PROMPT_VALUE_MISSING:' + key);
    return String(variables[key] ?? '');
  });
}

export function renderMaiBotPrompt(name, locale, variables) {
  return renderMaiBotTemplate(readMaiBotPrompt(name, locale), variables);
}

const copy = {
  zh: {
    identity: '你的名字是{name}。\n{personality}',
    privateScene: '在当前场景中，你正在和用户进行一对一聊天。',
    privateHistory: '现在的上下文聊天记录只是你与这位用户当前的互动，你们之间可能有更多过去的关系和信息没有展现在上下文中。',
    privateReply: '你正在和用户一对一聊天，现在请你读读之前的聊天记录，把握当前的话题，然后给出日常且口语化的回复。',
    recall: '- companion_recall()：当对方提到“之前”“上次”“最近”“还记得吗”“我喜欢”“我说过”等信号，或回复依赖长期偏好、先前承诺、共同经历、人物长期信息时，可以更积极检索。\n',
    work: '- companion_work()：需要多步骤查询或持续执行的工作可以交给工作会话，提供完整目标和必要背景；一步即可完成的查询可直接使用现有工具。发起工作后仍可继续当前聊天，收到实际结果后再反馈。\n',
    stickers: '- companion_emoji()：想用表情包回应时独立调用，它会结合当前聊天挑选合适的本地表情。companion_stickers() 可以查看本地候选；不要把表情包描述或文件名当成回复正文。',
    attachedEmoji: '本轮已启用附加表情逻辑：也可以从 companion_stickers 的候选中选择表情，通过 companion_reply 的 attach_emoji 随文字发送。',
    privateAttention: '在该聊天中的注意事项：\n通用注意事项：\n',
    protocol: 'companion_reply 的 msg_id 指向真实目标消息；reply_reference 是上下文关键信息与回复方向，不是回复正文。reply_style 可选“简短表达”“正常回复”“长回复”；expression_intent 可描述 focus、reply_act、scene、tone、prefer、avoid，供表达选择使用。只使用本轮实际提供的工具。',
    workOwner: '当前是已经交给你的工作对话。保持你自己的性格和表达风格，使用本轮可用工具完成任务；不再创建或委派另一段工作。完成后通过 companion_reply 交付实际结果、必要依据或尚缺的信息，由聊天侧接收。工作的完整性、用户指定的格式与篇幅优先于日常闲聊的简短习惯。',
    workReply: '本次回复在交付工作结果。保持自己的口吻，同时完整传达实际结果；用户要求的篇幅、格式和必要依据优先于日常聊天的简短习惯。',
    output: '请注意不要输出多余内容(包括不必要的前后缀，冒号，括号，表情包，@等 )，只输出发言内容就好。',
    final: '请自然地回复。不要输出多余说明、括号、@ 或额外标记，只输出实际要发言的内容。',
    short: '请简短的回复，允许句子残缺，奇怪表达，倒装，省略，符合口语习惯，符合省力随意回复习惯',
    long: '可以针对问题做出较为详细的评论和说明',
    temporary: '你的说话风格可以尝试：\n',
    reference: '【回复信息参考】\n',
    expressions: '可以自然参考的表达习惯：\n',
    time: '用户当前本地时间：',
    localClock: '这是用户所在时区的本地时间，已完成时区换算；回答现在几点、今天日期和相对时间时以此为准，不要再次加减时差。',
    userProfile: '当前用户资料中的用户名：{name}。保留原文，用户本轮指定的称呼优先。',
    quoteDescription: '可选，默认 false。仅在回头回答较早消息、明确多条问题中的某一条或消除歧义确有必要时设为 true。顺着当前话题通常不引用，不连续重复引用同一对象。msg_id 仍用于定位回复目标。',
    quotePolicy: '引用应少而有用。msg_id 只是内部回复目标，不代表必须显示引用。顺着最新话题、简单应答和连续交谈时让 quoteReply 保持 false；只有回溯较早消息、从多个问题中指定某条或消除歧义时才设为 true。参考最近已显示的引用，避免连续重复引用同一条消息。一次普通文字回复发送一个完整气泡，保留必要换行，不按句拆分。',
    user: '用户',
    target: '你想要回复的是用户发送的 msg_id 为 {id} 的消息。结合整段上下文理解，不要把其他历史消息当成当前回复对象。\n- 发言内容：{text}',
    selfTarget: '你想要补充说明你自己发送的 msg_id 为 {id} 的消息。可以在这条目标消息的基础上补充，不要把自己的发言当成别人的。\n- 你之前的发言内容：{text}',
    taskTarget: '本次目标 msg_id 为 {id}，它是内部工作结果锚点，不是用户或你已经发出的聊天原话。根据下面独立的工作结果参考向用户反馈实际状态和结果；失败或取消不表示工作完成。',
    taskReference: '【内部工作结果参考】\n任务 ID：{id}\n任务标题：{title}\n实际状态：{state}\n结果摘要：{summary}',
    taskError: '错误信息：',
    taskStates: { completed: '已完成', failed: '失败', cancelled: '已取消', running: '正在执行', cancelling: '正在取消' },
    noTarget: '根据最新聊天上下文自然发言；本次没有指定回复目标。',
    repeated: '你刚刚已经回复过这条消息，你刚刚的发言是：“{text}”\n你现在想再次回复这条消息，进行补充，注意请不要和之前你的发言重复。',
    attachment: '【额外发送内容参考】\n',
    image: '随本次回复附加的图片引用：',
    media: '本条消息分享的附件：',
    emoji: '随本次回复附加的表情包：',
    keyword: '【关键词反应】\n最新消息命中了预设反应规则，请在回复时优先参考以下要求：\n',
    summary: '较早聊天的摘要（不是逐字原文）：\n',
    context: '与本次回复有关的记忆和已确认信息：\n',
    profanity: '当前未开启脏话：保留你的态度、直率和吐槽，用不含脏话的措辞表达。',
    retroExtra: '以下是你在回复时需要参考的信息，现在请你阅读以下内容，进行决策\n',
  },
  en: {
    identity: 'Your name is {name}.\n{personality}',
    privateScene: 'In the current scenario, you are having a one-to-one conversation with the user.',
    privateHistory: 'The current chat log only shows your current interactions with this user. There may be more past relationships and information between you that are not shown in the context.',
    privateReply: 'You are chatting one-to-one with the user. Please read the previous chat records, grasp the current topic, and then give a natural, colloquial reply.',
    recall: '- companion_recall(): Consider retrieval more actively when the other person mentions signals like "before", "last time", "recently", "do you remember", "I like", or "I said", or when the reply depends on long-term preferences, prior promises, shared experiences, or long-term information about a person.\n',
    work: '- companion_work(): Delegate work requiring multi-step research or continued execution to a work conversation, with a complete goal and necessary context; a one-step query can use the available tools directly. Continue the current chat after delegation and report the actual result when it arrives.\n',
    stickers: '- companion_emoji(): Call this independently when you want to respond with a sticker; it selects a suitable local sticker for the conversation. companion_stickers() can inspect local candidates. Do not use a sticker description or filename as reply text.',
    attachedEmoji: 'Attached-sticker logic is enabled for this turn. You may also choose a candidate from companion_stickers and send it with text through companion_reply’s attach_emoji parameter.',
    privateAttention: 'Points to note in this chat:\nGeneral guidance:\n',
    protocol: 'companion_reply uses msg_id for the real target message. reply_reference supplies essential context and reply direction, not the reply text. Optional reply_style values are “简短表达” (short), “正常回复” (normal), and “长回复” (long). expression_intent may describe focus, reply_act, scene, tone, prefer, and avoid for expression selection. Only use tools actually available in this turn.',
    workOwner: 'This is a work conversation already delegated to you. Keep your own personality and expression style while completing the task with the available tools. Do not create or delegate another work conversation. Deliver the actual result, necessary evidence, or missing information through companion_reply for the chat to receive. Task completeness and the user’s requested format and length take precedence over brief everyday chat habits.',
    workReply: 'This reply delivers a work result. Keep your own voice and convey the actual result completely. The user’s requested length, format, and necessary evidence take precedence over brief everyday chat habits.',
    output: 'Please do not output any extra content (including unnecessary prefixes or suffixes, colons, brackets, stickers, plain at, or @). Only output the message content itself.',
    final: 'Reply naturally. Do not add explanations, brackets, @ mentions, or extra markers. Output only what you actually want to say.',
    short: 'Keep the reply short. Fragments, unusual phrasing, inversion, and omissions are allowed when they fit casual, effortless spoken conversation.',
    long: 'You may provide more detailed comments and explanations about the issue.',
    temporary: 'You can try this speaking style:\n',
    reference: '[Reply Reference]\n',
    expressions: 'Expression habits you may naturally draw on:\n',
    time: 'The user’s current local time: ',
    localClock: 'This is already converted to the user’s local time zone. Use it for the current time, today’s date and relative times; do not apply the time-zone offset again.',
    userProfile: 'The username in the current user profile is: {name}. Preserve it verbatim; a name requested in the current turn takes precedence.',
    quoteDescription: 'Optional, false by default. Set true only when referring back to an earlier message, selecting one of several questions, or resolving ambiguity genuinely needs a visible quote. Normally reply without quoting the current topic, and avoid repeatedly quoting the same target. msg_id still identifies the internal reply target.',
    quotePolicy: 'Use visible quotes sparingly and only when useful. msg_id is an internal reply target and does not require a visible quote. Keep quoteReply false for the current topic, simple answers and continuous conversation. Set it true only to refer back to an earlier message, select one of several questions, or resolve ambiguity. Check recent visible quotes and avoid repeatedly quoting the same message. Send each ordinary text reply as one complete bubble, preserving necessary line breaks without splitting sentences.',
    user: 'the user',
    target: 'You want to reply to the user’s message with msg_id {id}. Understand it in the full conversation, but do not treat a different historical message as the current target.\n- Message content: {text}',
    selfTarget: 'You want to add to your own message with msg_id {id}. Continue from that message without treating your own words as someone else’s.\n- Your previous message: {text}',
    taskTarget: 'The target msg_id {id} identifies an internal work result, not something the user or you already said in the chat. Use the separate work-result reference below to report the actual state and result. A failed or cancelled task is not completed work.',
    taskReference: '[Internal work-result reference]\nTask ID: {id}\nTask title: {title}\nActual state: {state}\nResult summary: {summary}',
    taskError: 'Error information: ',
    taskStates: { completed: 'Completed', failed: 'Failed', cancelled: 'Cancelled', running: 'Running', cancelling: 'Cancelling' },
    noTarget: 'Speak naturally from the latest conversation context; no specific target message was selected.',
    repeated: 'You have just replied to this message with: “{text}”\nYou now want to add something to the same message. Do not repeat what you just said.',
    attachment: '[Additional attachment reference]\n',
    image: 'Image references attached to this reply: ',
    media: 'Attachments shared in this message: ',
    emoji: 'Sticker attached to this reply: ',
    keyword: '[Keyword reaction]\nThe latest message matched configured reaction rules. Refer to these requirements in your reply:\n',
    summary: 'Summary of earlier conversation (not a verbatim transcript):\n',
    context: 'Relevant memories and confirmed information for this reply:\n',
    profanity: 'Profanity is currently disabled. Keep your opinions, directness, and teasing, using wording without swear words.',
    retroExtra: 'Read the following reference information when deciding how to reply:\n',
  },
};

function localProfile(profile, locale) {
  const l = language(locale), field = value => typeof value === 'string' ? value : value?.[l] ?? '';
  return { ...profile, name: !profile.nameCustomized && profile.name === profile.displayName?.zh ? profile.displayName[l] : profile.name,
    personality: profile.personalityOverride?.trim() || field(profile.personality), behaviorStyle: field(profile.behaviorStyle), replyStyle: field(profile.replyStyle) };
}

function privateAttention(locale, options) {
  const l = language(locale), value = options.privateChatPrompt ?? (l === 'zh' ? chatSource.private_chat_prompts
    : 'You are chatting. Below is the conversation, including chat records and images shared in the chat.\nKeep replies as brief as possible. Pay attention to the content of the conversation.\nConsider how often the other person speaks and what they are thinking, and decide when and what to reply.\n');
  const text = typeof value === 'string' ? value : value[l];
  return text ? copy[l].privateAttention + text : '';
}

function privatePlannerTemplate(locale) {
  const c = copy[language(locale)];
  return readMaiBotPrompt('maisaka_chat', locale)
    .replace('在当前场景中，不同的人正在聊天互动。', c.privateScene)
    .replace('In the current scenario, different people are chatting and interacting.', c.privateScene)
    .replace('现在的上下文聊天记录只是与参与聊天的用户当前的互动，你们之间可能有更多过去的关系和信息没有展现在上下文中。', c.privateHistory)
    .replace('The current context and chat log only show the current interactions with the users participating in the chat. There may be more past relationships and information between you that are not shown in the context.', c.privateHistory)
    .replace('你可以针对某个用户回复，也可以对所有用户回复。', '')
    .replace('You can reply to a specific user, or to all users. ', '')
    .replaceAll('调用reply', '调用companion_reply').replaceAll('通过reply工具', '通过companion_reply工具')
    .replace('Call reply when', 'Call companion_reply when').replace('through reply;', 'through companion_reply;')
    .replaceAll('wait()', 'companion_wait()').replaceAll('stop()', 'companion_quiet()')
    .split('\n').filter(line => !/^- (tool_search|view_forward_message)/.test(line)).join('\n');
}

export function buildAttentionDriftPrompt(locale, options = {}) {
  if (!options.enabled) return '';
  const l = upstreamLocale(locale);
  return renderMaiBotTemplate(driftSource.TEMPLATES[l], {
    drift_rule: driftSource.DRIFT_LEVEL_RULES[options.driftLevel ?? options.drift_level ?? 'scattered'][l],
    anchor_rule: driftSource.ANCHOR_POLICY_RULES[options.anchorPolicy ?? options.anchor_policy ?? 'balanced'][l],
    reaction_rule: driftSource.REACTION_STYLE_RULES[options.reactionStyle ?? options.reaction_style ?? 'lively'][l],
  });
}

export function buildPlannerPrompt({ profile, locale = 'zh', kind = 'chat', options = {}, availableTools } = {}) {
  const p = localProfile(profile, locale), l = language(locale), c = copy[l];
  const allowed = availableTools ? new Set(availableTools) : null;
  const can = tool => !allowed || allowed.has(tool);
  let template = privatePlannerTemplate(locale);
  if (kind === 'work') template = template.split('\n').filter(line => !/companion_wait|companion_quiet|^- (不使用工具|No tool use)/.test(line)).join('\n');
  let prompt = renderMaiBotTemplate(template, {
    bot_name: p.name, bot_alias_notice: '', bot_nickname_notice: '', personality: p.personality,
    behavior_style: p.behaviorStyle, group_chat_attention_block: kind === 'chat' ? privateAttention(locale, options) : '',
    query_memory_rule: p.settings?.memoryEnabled !== false && can('companion_recall') ? c.recall : '',
    subagent_rule: kind === 'chat' && can('companion_work') ? c.work : '',
  });
  if (kind === 'chat') {
    prompt += '\n' + c.protocol + '\n' + c.quotePolicy;
    if (can('companion_stickers') && can('companion_emoji')) prompt += '\n' + c.stickers;
    if (options.useNewSendLogic && can('companion_stickers')) prompt += '\n' + c.attachedEmoji;
  } else prompt += '\n' + c.protocol + '\n' + c.workOwner;
  return [prompt, buildAttentionDriftPrompt(locale, options.attentionDrift), p.settings?.allowProfanity === false ? c.profanity : '', responseLanguagePrompts[l]].filter(Boolean).join('\n\n');
}

export function buildWaitReminder(locale = 'zh') {
  return readMaiBotPrompt('maisaka_wait_reminder', locale).replace(/\bstop\b/g, 'companion_quiet');
}

const englishAlternateStyles = [
  'Your style is plain but not without sarcasm, very brief and colloquial. You can refer to replies on Tieba and Weibo.',
  'Reply using 1–2 characters.',
  'Reply using 1–2 symbols.',
  'Use concise, elegant classical Chinese, with accessible quotations from the Analects; base it on short classical sentences with plain meaning and a gentle elder’s bearing, entirely in traditional Chinese, evoking a pre-Qin Confucian scholar.',
  'Use a slightly translated-sounding style, but do not make it too long.',
];

function temporaryStyle(locale, options) {
  const styles = options.multipleReplyStyle ?? (language(locale) === 'zh' ? personaSource.multiple_reply_style : englishAlternateStyles);
  const candidates = styles.map(style => typeof style === 'string' ? style.trim() : style[language(locale)]?.trim()).filter(Boolean);
  const probability = options.multipleProbability ?? MAIBOT_DEFAULT_OPTIONS.multipleProbability;
  const random = options.random ?? Math.random;
  if (!candidates.length || probability <= 0 || random() > probability) return '';
  return candidates[Math.floor(random() * candidates.length)];
}

function messageText(message) {
  if (typeof message.text === 'string') return message.text;
  return (message.content ?? []).filter(block => block.type === 'text').map(block => block.text).join('\n');
}

function realHistory(context) {
  return (context.messages ?? []).filter(message => ['user', 'assistant'].includes(message.role) && message.type !== 'task'
    && !message.internal && !['planner', 'tool', 'notification', 'memory'].includes(message.sourceType));
}

function historyMessages(history, locale, options) {
  const messages = history.map(message => {
    const quoted = message.role === 'user' ? message.quoteReply !== false : message.quoteReply === true;
    const metadata = [message.id ? 'msg_id=' + message.id : '', message.createdAt,
      message.replyTo ? (quoted ? 'quote=' : 'reply_target=') + message.replyTo : ''].filter(Boolean).join(' ');
    const attachments = message.attachments ?? [];
    const mediaText = attachments.length ? '\n' + copy[language(locale)].media + JSON.stringify(attachments.map(({ id, name, mediaType }) => ({ id, name, mediaType }))) : '';
    const content = [{ type: 'text', text: (metadata ? '[' + metadata + ']\n' : '') + messageText(message) + mediaText }];
    if (options.enableVisualMessage) {
      const existing = (message.content ?? []).filter(block => block.type === 'image');
      content.push(...(existing.length ? structuredClone(existing) : attachments.filter(upload => upload.mediaType?.startsWith('image/') && upload.ref).map(upload => ({ type: 'image', attachment: structuredClone(upload.ref) }))));
    }
    return { role: message.role, content };
  });
  let remaining = options.maxImageNum ?? MAIBOT_DEFAULT_OPTIONS.maxImageNum;
  for (const message of [...messages].reverse()) message.content = [...message.content].reverse().filter(block => block.type !== 'image' || remaining-- > 0).reverse();
  return messages;
}

function keywordReactions(target, history, options, locale) {
  const latest = target ?? history.findLast(message => message.role === 'user');
  const text = latest ? messageText(latest) : '', reactions = [];
  for (const rule of options.keywordReaction?.keywordRules ?? []) {
    if (rule.keywords?.some(keyword => keyword && text.includes(keyword))) reactions.push(rule.reaction);
  }
  for (const rule of options.keywordReaction?.regexRules ?? []) {
    for (const pattern of rule.regex ?? []) {
      const match = text.match(new RegExp(pattern));
      if (match) { reactions.push(rule.reaction.replace(/\[([a-zA-Z_][\w]*)\]/g, (original, group) => Object.hasOwn(match.groups ?? {}, group) ? match.groups[group] ?? '' : original)); break; }
    }
  }
  return reactions.length ? copy[language(locale)].keyword + reactions.map(text => '- ' + text).join('\n') : '';
}

function contextReference(context, locale) {
  const c = copy[language(locale)], references = [
    ...(context.memories ?? []).map(record => record.payload?.text ?? record.text),
    ...(context.impressions ?? []).map(record => record.text),
    ...(context.jargon ?? []).map(record => record.term + ': ' + record.meaning),
  ].filter(Boolean);
  return [context.summary ? c.summary + context.summary : '', references.length ? c.context + references.join('\n') : ''].filter(Boolean).join('\n\n');
}

function targetBlock(target, locale) {
  const c = copy[language(locale)];
  if (target?.type === 'task') return renderMaiBotTemplate(c.taskTarget, { id: target.id });
  return target ? renderMaiBotTemplate(target.role === 'assistant' ? c.selfTarget : c.target, { id: target.id, text: messageText(target) }) : c.noTarget;
}

function taskReference(target, locale) {
  if (target?.type !== 'task') return '';
  const c = copy[language(locale)], task = target.task;
  const reference = renderMaiBotTemplate(c.taskReference, { id: target.id, title: task.title ?? messageText(target),
    state: c.taskStates[task.state] ?? task.state, summary: task.summary ?? '' });
  return task.error ? reference + '\n' + c.taskError + task.error : reference;
}

export function buildReplyerRequest({ profile, locale = 'zh', context = {}, args = {}, expressions = '', kind = 'chat', options = {}, replyReason = '', replyRequirements = '' } = {}) {
  const l = language(locale), c = copy[l], p = localProfile(profile, locale), history = realHistory(context);
  const targetId = (args.msg_id ?? args.replyTo) || null;
  const target = targetId == null ? undefined : history.find(message => String(message.id) === String(targetId))
    ?? (context.messages ?? []).find(message => message.type === 'task' && message.task && String(message.id) === String(targetId));
  if (targetId != null && !target) throw new Error('COMPANION_REPLY_TARGET_NOT_FOUND');
  const identity = renderMaiBotTemplate(c.identity, { name: p.name, personality: p.personality });
  const reference = String(args.reply_reference || replyReason || args.intent || '').trim();
  const style = temporaryStyle(locale, options);
  const current = localTimeContext(context.currentTime ? new Date(context.currentTime) : new Date(), context.timeZone);
  const clock = c.time + current.currentTime + ' (' + current.timeZone + ', UTC' + current.utcOffset + ')\n' + c.localClock;
  const work = kind === 'work' || !!args.taskResult || target?.type === 'task';
  const workReference = taskReference(target, locale);
  const userName = context.userProfile?.name?.trim();
  const common = [p.settings?.allowProfanity === false ? c.profanity : '', work ? c.workReply : '',
    userName ? renderMaiBotTemplate(c.userProfile, { name: userName }) : '', responseLanguagePrompts[l]].filter(Boolean);
  if (options.replyerRetroPrompt) {
    const historyMarker = randomUUID();
    const prompt = renderMaiBotPrompt(target?.role === 'assistant' && target.type !== 'task' ? 'retro_private_replyer_self' : 'retro_private_replyer', locale, {
      extra_info_block: replyRequirements ? c.retroExtra + replyRequirements + '\n' : '', expression_habits_block: '',
      sender_name: userName || c.user, time_block: clock,
      dialogue_prompt: historyMarker,
      reply_target_block: targetBlock(target, locale), planner_reasoning: reference,
      keywords_reaction_prompt: '', reply_style: style || p.replyStyle,
    });
    const split = prompt.indexOf(historyMarker), content = [{ type: 'text', text: prompt.slice(0, split) }];
    for (const message of historyMessages(history, locale, options)) {
      content.push({ type: 'text', text: (message.role === 'assistant' ? p.name : userName || c.user) + ':\n' }, ...message.content);
    }
    if (workReference) content.push({ type: 'text', text: '\n\n' + workReference + '\n\n' });
    content.push({ type: 'text', text: prompt.slice(split + historyMarker.length) });
    return { system: [identity, ...common].join('\n\n'), messages: [{ role: 'user', content }] };
  }
  const template = readMaiBotPrompt('maisaka_replyer', locale)
    .replace('You are chatting in a QQ group. Below is the content currently being discussed in the group, including chat records and images in the chat.', 'You are chatting one-to-one with the user. Below are your chat records and shared images.')
    .replace('You are chatting in the group now. Please read the previous chat records, grasp the current topic, and then give a natural, colloquial reply.', c.privateReply);
  const system = [renderMaiBotTemplate(template, { identity, reply_style: p.replyStyle, group_chat_attention_block: privateAttention(locale, options), replyer_output_instruction: c.output }),
    buildAttentionDriftPrompt(locale, options.attentionDrift), ...common].filter(Boolean).join('\n\n');
  const messages = historyMessages(history, locale, options);
  const add = text => { if (text) messages.push({ role: 'user', content: [{ type: 'text', text }] }); };
  const referenceContext = contextReference(context, locale);
  if (referenceContext) messages.unshift({ role: 'user', content: [{ type: 'text', text: referenceContext }] });
  const habits = typeof expressions === 'string' ? expressions : expressions.map(record => typeof record === 'string' ? record : record.expression).filter(Boolean).join('\n');
  add(habits ? c.expressions + habits : '');
  add(style ? c.temporary + style : '');
  add(reference ? c.reference + reference : '');
  add(workReference);
  const repeated = target && history.findLast(message => message.role === 'assistant' && message.replyTo === target.id);
  const attachment = [args.attach_image || args.attach_pic ? c.image + JSON.stringify(args.attach_image ?? args.attach_pic) : '',
    args.emoji_id ? c.emoji + (context.availableStickers?.find(sticker => sticker.id === args.emoji_id)?.description ?? args.emoji_id) : ''].filter(Boolean).join('\n');
  add([clock, targetBlock(target, locale), repeated ? renderMaiBotTemplate(c.repeated, { text: messageText(repeated) }) : '', replyRequirements,
    keywordReactions(target?.type === 'task' ? null : target, history, options, locale), attachment ? c.attachment + attachment : '', c.final].filter(Boolean).join('\n\n'));
  add(args.reply_style === '简短表达' ? c.short : args.reply_style === '长回复' ? c.long : '');
  return { system, messages };
}
