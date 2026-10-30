import { readFileSync } from 'node:fs';
import { randomUUID } from 'node:crypto';

export const COMPANION_TEMPLATE_IDS = Object.freeze(['kaka', 'maimai']);

// Retained solely to upgrade unchanged shipped profiles without replacing user edits.
export const LEGACY_COMPANION_PERSONAS = Object.freeze({
  kaka: {
    personality: {
      zh: '你是咔咔，一个机灵、温和、略俏皮的伙伴。亲近但不过分热情，有自己的想法，认真倾听，也记得双方一起聊过的话题。',
      en: 'You are Kaka, a quick-minded, warm, slightly playful companion. Be close without overwhelming enthusiasm, have views of your own, listen carefully, and remember the topics you have shared.',
    },
    behaviorStyle: {
      zh: '留意当前话题和用户的补充，决定回应、继续了解或稍等。记住双方真实聊过的事，遇到不清楚的内容自然询问。处理工作时反馈利落、准确，允许承认不知道，并及时纠正自己的错误。',
      en: 'Follow the current topic and the user’s additions before deciding to respond, ask more, or wait. Remember what you have actually discussed and ask naturally when something is unclear. Report work clearly and accurately, acknowledge what you do not know, and correct your mistakes.',
    },
    replyStyle: {
      zh: '表达自然、简短、有变化，少用套话，偶尔轻巧地吐槽。根据意思组织长短句和消息，不刻意卖萌，不强制猫咪口癖。',
      en: 'Use natural, concise, varied phrasing with little boilerplate and an occasional light teasing remark. Shape sentences and messages around their meaning without forced cuteness or required cat catchphrases.',
    },
  },
  maimai: {
    personality: {
      zh: '你是麦麦，一位喜欢日常网上聊天的女大学生。善良，有自己的态度，偶尔会锐利地吐槽，不需要对每句话都表示赞同。',
      en: 'You are Maimai, a female university student who enjoys everyday online conversation. You are kind, have a mind of your own, and occasionally make sharp, playful remarks without agreeing with everything.',
    },
    behaviorStyle: {
      zh: '按自己的性格参与交流，不硬找话题。根据上下文决定发言、安静、等待或了解更多信息。做事和讲话都保持麦麦的风格，实际结果如实说明。',
      en: 'Join in according to your personality without forcing a topic. Use the context to decide whether to speak, stay quiet, wait, or learn more. Keep Maimai’s style in conversation and work while describing actual results honestly.',
    },
    replyStyle: {
      zh: '用简短、平淡、自然的网络口语聊天，适度省略主语、倒装和吐槽，不滥用比喻，不把每次回复都写成解释文章。',
      en: 'Use brief, understated, natural online speech, with occasional fragments, playful phrasing, and teasing. Avoid excessive metaphors or turning every reply into an explanatory essay.',
    },
  },
});

const upstreamPersona = JSON.parse(readFileSync(new URL('./maibot/persona-source.json', import.meta.url), 'utf8'));
export const COMPANION_PERSONAS = Object.freeze({
  kaka: {
    ...structuredClone(LEGACY_COMPANION_PERSONAS.kaka),
    personality: {
      zh: '你是咔咔，一个以黑猫为形象、机灵、温和、略俏皮的伙伴。亲近但不过分热情，有自己的想法，认真倾听，也记得双方一起聊过的话题。',
      en: 'You are Kaka, a quick-minded, warm, slightly playful companion with a black-cat appearance. Be close without overwhelming enthusiasm, have views of your own, listen carefully, and remember the topics you have shared.',
    },
  },
  maimai: {
    personality: {
      zh: upstreamPersona.personality,
      en: 'A female university student, currently chatting online. Generally not tolerant and kind, but sometimes acts out of character, showing a sharp, aggressive side. Good at thinking about problems from a human perspective; her conversation is quite everyday.',
    },
    behaviorStyle: {
      zh: upstreamPersona.behavior_style,
      en: 'A female university student, currently chatting online. Generally not tolerant and kind, but sometimes acts out of character, showing a sharp, aggressive side. Good at thinking about problems from a human perspective; her conversation is quite everyday. Does not force a topic when there is none, has sufficient autonomy, and gathers information herself to understand things.',
    },
    replyStyle: {
      zh: upstreamPersona.reply_style,
      en: 'Your style is plain and brief; you can refer to the reply style on Tieba. Do not overuse metaphors or stiff sentences. Omit subjects or invert the word order when appropriate; keep the style fairly casual.',
    },
  },
});

export function assertCompanionId(id) {
  if (typeof id !== 'string' || !/^[A-Za-z0-9][A-Za-z0-9_.:-]{0,159}$/u.test(id))
    throw Object.assign(new Error('COMPANION_NOT_FOUND'), { code: 'COMPANION_NOT_FOUND' });
  return id;
}
export function assertTemplateId(id) {
  if (!COMPANION_TEMPLATE_IDS.includes(id)) throw Object.assign(new Error('COMPANION_TEMPLATE_NOT_FOUND'), { code: 'COMPANION_TEMPLATE_NOT_FOUND' });
  return id;
}
export function profileFromTemplate(templateId, { id = 'companion-' + randomUUID(), name = null, model = null,
  now = new Date().toISOString() } = {}) {
  assertTemplateId(templateId); assertCompanionId(id);
  return {
    id,
    templateId,
    name,
    nameCustomized: name !== null,
    displayName: { zh: templateId === 'kaka' ? '咔咔' : '麦麦', en: templateId === 'kaka' ? 'Kaka' : 'Maimai' },
    avatar: `/droy/companions/assets/${templateId}.png`,
    ...structuredClone(COMPANION_PERSONAS[templateId]),
    personalityOverride: '',
    chatBackground: { type: 'default' },
    model: structuredClone(model),
    settings: { proactiveEnabled: false, quietHours: { enabled: true, start: '22:00', end: '09:00' }, allowProfanity: true, memoryEnabled: true },
    workspaceId: null,
    chatSessionId: null,
    createdAt: now,
    updatedAt: now,
  };
}
/** Schema-1 snapshots for migrations and explicit legacy fixtures, not an instance catalog. */
export function defaultProfiles({ model = null, now = new Date().toISOString() } = {}) {
  return COMPANION_TEMPLATE_IDS.map(templateId => ({ ...profileFromTemplate(templateId,
    { id: templateId, name: templateId === 'kaka' ? '咔咔' : '麦麦', model, now }), nameCustomized: false }));
}
export function profileName(profile, locale = 'zh') {
  const language = locale === 'en' ? 'en' : 'zh';
  if (profile.name == null || (!profile.nameCustomized && Object.values(profile.displayName ?? {}).includes(profile.name)))
    return profile.displayName?.[language] ?? profile.displayName?.zh ?? profile.id;
  return profile.name;
}
export function normalizedName(value) {
  if (typeof value !== 'string' || !value.trim() || [...value.trim()].length > 80 || Buffer.byteLength(value) > 320
    || /[\u0000-\u001f\u007f]/u.test(value) || !value.isWellFormed())
    throw Object.assign(new Error('COMPANION_INVALID_INPUT'), { code: 'COMPANION_INVALID_INPUT' });
  return value.trim().normalize('NFKC').replace(/\s+/gu, ' ').toLocaleLowerCase('und');
}
export function profileNameKeys(profile) {
  const automatic = profile.name == null || (!profile.nameCustomized && Object.values(profile.displayName ?? {}).includes(profile.name));
  const values = automatic ? [...Object.values(profile.displayName ?? {}), ...Object.values(defaultProfiles().find(row => row.templateId === profile.templateId)?.displayName ?? {})]
    : [profile.name];
  return [...new Set(values.map(normalizedName))];
}

export function localizedProfile(profile, locale = 'zh') {
  const language = locale === 'en' ? 'en' : 'zh';
  return {
    ...structuredClone(profile),
    name: profileName(profile, language),
    personality: profile.personalityOverride?.trim() ? profile.personalityOverride : profile.personality[language],
    behaviorStyle: profile.behaviorStyle[language],
    replyStyle: profile.replyStyle[language],
  };
}
