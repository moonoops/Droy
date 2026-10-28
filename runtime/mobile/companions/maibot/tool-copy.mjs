// Chinese descriptions are retained from upstream reply.py; English is a paired translation.
// The sticker ID and picture reference descriptions reflect Droy's actual media contract.
export const MAIBOT_REPLY_TOOL_COPY = Object.freeze({
  zh: {
    description: '根据当前思考生成并发送可见回复；可以使用参数附加回复内容。',
    msg_id: '要回复的消息msg_id。',
    reply_reference: '提供本次回复的上下文关键信息与回复方向，包括当前聊天状态、人物关系、事实信息、回忆信息；不是回复正文。',
    reply_style: '可选。控制本次回复的篇幅和表达方式',
    expression_intent: '可选。给 replyer 表达方式选择使用的结构化意图，不是回复正文。当这次回复需要特定语气、场景或话术时填写，避免表达选择只按关键词匹配。',
    focus: '本次表达主要应该贴合的目标消息、片段或话题。',
    reply_act: '这次回复要完成的动作，例如澄清、安抚、调侃、追问、解释边界。',
    scene: '当前表达场景，例如技术排查、截图分享、撒娇玩笑、价格询问。',
    tone: '期望语气，例如轻松、可靠、吐槽、委婉、简短肯定。',
    prefer: '优先考虑的表达类型或话术倾向。',
    avoid: '需要避免的表达类型、误判方向或不该注入的具体结论。',
    attach_pic: '可选。随本次回复附加一张或多张上下文图片。每项使用 msg_id + index 指向已经分享的图片。',
    picture_msg_id: '图片所在的消息编号。',
    picture_index: '同一消息中的图片序号，从 0 开始。',
    emoji_id: '可选。从 companion_stickers 返回的本地候选选择真实的表情包 ID，在文字后发送；不填表示不发送表情包。',
  },
  en: {
    description: 'Generate and send a visible reply based on the current reasoning; parameters can attach additional content.',
    msg_id: 'The msg_id of the message to reply to.',
    reply_reference: 'Provide essential context and reply direction, including the current conversation, relationships, facts, and memories; this is not the reply text.',
    reply_style: 'Optional. Control the length and expression style of this reply.',
    expression_intent: 'Optional structured intent for the replyer’s expression selection, not reply text. Supply it when this reply needs a particular tone, scene, or phrasing so expression selection does not rely only on keywords.',
    focus: 'The target message, passage, or topic this expression should fit.',
    reply_act: 'What this reply should accomplish, such as clarify, comfort, tease, ask a follow-up question, or explain a boundary.',
    scene: 'The current expression setting, such as technical troubleshooting, sharing a screenshot, playful teasing, or asking about a price.',
    tone: 'The desired tone, such as relaxed, reliable, teasing, tactful, or briefly affirmative.',
    prefer: 'Expression types or phrasing tendencies to prefer.',
    avoid: 'Expression types, mistaken interpretations, or specific conclusions that should not be introduced.',
    attach_pic: 'Optional. Attach one or more context pictures to this reply. Each entry uses msg_id + index to reference an already shared image.',
    picture_msg_id: 'The message ID containing the picture.',
    picture_index: 'The picture index within the message, starting at 0.',
    emoji_id: 'Optional. Choose an existing local sticker ID returned by companion_stickers to send after the text; omit it to send no sticker.',
  },
});

export function replyToolDescriptions(locale = 'zh') {
  return MAIBOT_REPLY_TOOL_COPY[/^en(?:-|$)/i.test(locale) ? 'en' : 'zh'];
}
