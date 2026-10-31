package io.droyagent.app;

/** A test-only DSH adapter. Its replies and tool calls remain on the selected device. */
final class SetupFixtureProvider {
    static final String PROVIDER = "droy-setup-fixture";
    static final String MODEL = "setup-local";
    static final String ALTERNATE_MODEL = "setup-local-alternate";
    static final String CONTEXT_CANARY = "SETUP_PRIVATE_CONTEXT_MUST_NOT_REACH_THE_MODEL";
    static final String KAKA_GREETING = "Hi Kaka, let's chat.";
    static final String MAIMAI_GREETING = "Hi Maimai, let's chat.";
    static final String WORK_REQUEST = "Please draft a short weekend plan as a separate task.";
    static final String HOLD_REQUEST = "Hold this fixture reply until released.";
    static final String QUOTE_REQUEST = "Please quote my earlier greeting.";
    static final String SCROLL_REQUEST = "Please send sixty short numbered messages for our scroll check.";
    static final String NEUTRAL_WORK_REQUEST = "DROY_FIXTURE_NEUTRAL_WORK_SMOKE";
    static final String NEUTRAL_WORK_REPLY = "Local neutral work completed without phone operations.";

    private SetupFixtureProvider() {}

    static String source() {
        return """
            import { appendFile, writeFile } from 'node:fs/promises';
            import { join } from 'node:path';
            import { pathToFileURL } from 'node:url';
            export const name = 'droy-setup-local-fixture';
            export const inject = ['llm', 'systemPrompt', 'settings', 'webServer'];
            export async function apply(ctx) {
              const { LlmAdapter } = await import(pathToFileURL(join(process.env.DROY_RUNTIME_DIR,
                'node_modules/@deepseek-ai/dsh-llm/lib/index.js')).href);
              const log = join(process.env.DSH_HOME, 'setup-model-events.jsonl');
              const companionLog = join(process.env.DSH_HOME, 'setup-companion-events.jsonl');
              const startedWorks = new Set();
              const replyReferences = new Map(), stopChoices = new Map();
              ctx.on('agent/status', async ({agent, status}) => {
                const choice = stopChoices.get(agent.id);
                if (status !== 'idle' || !choice) return;
                stopChoices.delete(agent.id);
                await appendFile(join(process.env.DSH_HOME, 'setup-companion-idle.jsonl'), JSON.stringify(choice) + '\\n');
              });
              let sequence = 0, releaseReply, replyReleased = false, lastPlannerRequest = null, lastReplyerRequest = null;
              const contentText = message => (message?.content || []).filter(part => part.type === 'text').map(part => part.text).join('');
              const parsed = text => { try { return JSON.parse(text); } catch { return null; } };
              function* textReply(text) {
                yield { type: 'block-start', index: 0, blockType: 'text' };
                yield { type: 'text-delta', index: 0, text };
                yield { type: 'block-end', index: 0, block: { type: 'text', text } };
                yield { type: 'finish', reason: { kind: 'stop' } };
              }
              function* toolReply(name, args) {
                const id = 'fixture-call-' + ++sequence, value = JSON.stringify(args);
                yield { type: 'block-start', index: 0, blockType: 'tool-call', id, name };
                yield { type: 'tool-call-delta', index: 0, id, name, argumentsDelta: value };
                yield { type: 'block-end', index: 0, block: { type: 'tool-call', id, name, arguments: value } };
                yield { type: 'finish', reason: { kind: 'tool-calls' } };
              }
              class SetupModel extends LlmAdapter {
                async resolveModel(provider, id) {
                  return { provider, id, name: id === 'setup-local-alternate' ? 'Local companion alternate' : 'Local installation fixture', inputModalities: ['text'],
                    context: { contextWindow: 32768 }, defaultMaxTokens: 1024 };
                }
                async listModels(provider) { return [await this.resolveModel(provider, 'setup-local'), await this.resolveModel(provider, 'setup-local-alternate')]; }
                async *stream(options) {
                  if (options.provider !== 'droy-setup-fixture') throw Error('Unexpected fixture provider');
                  const tools = options.tools?.map(tool => tool.name) || [];
                  const userTexts = options.messages.filter(message => message.role === 'user').map(contentText);
                  const text = userTexts.at(-1) || '';
                  const inputs = userTexts.map(parsed);
                  const input = inputs.findLast(value => value?.userMessages || value?.internalTaskResults) || parsed(text);
                  if (tools.includes('droy_setup_echo') || !tools.length && ['你好', 'Hello'].includes(text)) {
                    await appendFile(log, JSON.stringify({ system: options.system, messages: options.messages, tools }) + '\\n');
                    if (tools.includes('droy_setup_echo')) yield* toolReply('droy_setup_echo', { value: input.value });
                    else yield* textReply(text === 'Hello' ? 'Hello, Droy is connected.' : '你好，Droy 已连接。');
                    return;
                  }
                  const system = [options.system || '', ...options.messages.filter(message => message.role === 'system').map(contentText)].filter(Boolean).join('\\n');
                  if (text.startsWith('Generate the session title from this JSON array of human messages:')
                    || system.includes('Create a concise title for an AI coding-assistant session')) {
                    await appendFile(join(process.env.DSH_HOME, 'setup-title-events.jsonl'), JSON.stringify({ input: text }) + '\\n');
                    yield* textReply(text.includes('DROY_FIXTURE_WEEKEND_PLAN') ? 'Local companion work' : 'Local companion conversation');
                    return;
                  }
                  if (userTexts.includes('DROY_FIXTURE_NEUTRAL_WORK_SMOKE')) {
                    await appendFile(join(process.env.DSH_HOME, 'setup-neutral-work-events.jsonl'), JSON.stringify({
                      provider: options.provider, model: options.model, system: options.system, messages: options.messages, tools }) + '\\n');
                    yield* textReply('Local neutral work completed without phone operations.'); return;
                  }
                  const allContext = [system, ...options.messages.map(contentText)].join('\\n');
                  const runtime = ctx.get('droyCompanions');
                  const owner = await runtime?.store.sessionOwner(options.sessionId);
                  if (!owner || !['kaka', 'maimai'].includes(owner.companionId)) throw Error('The local fixture received an unowned companion call');
                  const companion = owner.companionId;
                  const locale = ctx.settings.describe().find(entry => entry.ns === 'locale')?.value?.preference === 'zh' ? 'zh' : 'en';
                  const chinese = locale === 'zh';
                  const planner = tools.includes('companion_reply');
                  if (planner) lastPlannerRequest = { system, messages: options.messages, context: allContext,
                    clockContext: options.messages.filter(message => message.role === 'user').flatMap(message => contentText(message).split('\\n').map(parsed))
                      .findLast(value => value?.currentTime && value?.timeZone && value?.utcOffset) ?? null };
                  const summary = !planner && typeof input?.summary === 'string' && Array.isArray(input.messages);
                  const emoji = !planner && system.includes('emoji_index') && system.includes(chinese
                    ? '你需要根据上下文和当前语气,选择一个合适的表情包来发送'
                    : 'You need to choose a suitable sticker to send based on the context and current tone.');
                  const referenceHeader = chinese ? '【回复信息参考】\\n' : '[Reply Reference]\\n';
                  const referenceMessage = userTexts.findLast(value => value.startsWith(referenceHeader));
                  const reference = referenceMessage?.slice(referenceHeader.length).trim();
                  if (!planner && !summary && !emoji && referenceMessage) lastReplyerRequest = { system, messages: options.messages, context: allContext };
                  const styleVerified = owner.kind === 'work' ? allContext.includes(chinese
                    ? '可以针对问题做出较为详细的评论和说明' : 'You may provide more detailed comments and explanations')
                    : companion !== 'maimai' || allContext.includes(chinese ? '请简短的回复' : 'Keep the reply short.');
                  const emojiCandidatesVerified = emoji && input?.items?.length === 3 && input.items.every((item,index) =>
                    item.emoji_index === index + 1 && typeof item.description === 'string' && item.description.trim());
                  const templateVerified = planner ? system.includes(chinese
                    ? '请你对当前场景和输出规则来进行分析。'
                    : 'Please analyze the current scenario and output rules.') : emoji || system.includes(chinese
                    ? '现在请你读读之前的聊天记录，把握当前的话题，然后给出日常且口语化的回复'
                    : 'You are chatting one-to-one with the user.');
                  const actualMessages = (await runtime.store.listMessages(companion, {limit: 200})).messages;
                  const realHistoryVerified = !planner && options.messages.some(message => message.role === 'user'
                    && actualMessages.some(actual => {
                      if (actual.role !== 'user') return false;
                      const value = contentText(message), newline = value.indexOf('\\n'), header = value.slice(0, newline);
                      return newline >= 0 && (header.startsWith('[msg_id=' + actual.id + ' ') || header === '[msg_id=' + actual.id + ']')
                        && header.endsWith(']') && value.slice(newline + 1) === actual.text;
                    }));
                  const lastUser = options.messages.findLastIndex(message => {
                    if (message.role !== 'user') return false;
                    const value = parsed(contentText(message));
                    return value?.userMessages || value?.internalTaskResults || contentText(message) === 'DROY_FIXTURE_WEEKEND_PLAN';
                  });
                  const turnCalls = options.messages.slice(lastUser + 1).flatMap(message => message.role === 'assistant'
                    ? message.content.filter(block => block.type === 'tool-call').map(block => block.name) : []);
                  const replySchema = options.tools?.find(tool => tool.name === 'companion_reply')?.parameters;
                  const conversationInput = planner ? (input ? JSON.stringify(input)
                    : userTexts.findLast(value => value === 'DROY_FIXTURE_WEEKEND_PLAN') || text) : text;
                  await appendFile(companionLog, JSON.stringify({ kind: planner ? owner.kind === 'work' ? 'work' : 'planner' : summary ? 'summary' : emoji ? 'emoji-selector' : 'replyer',
                    companion, locale, sessionId: options.sessionId, system, messages: options.messages, context: allContext, tools,
                    replySchema, turnCalls, templateVerified, realHistoryVerified, referenceVerified: !!referenceMessage,styleVerified,emojiCandidatesVerified,
                    assistantHistoryPresent: options.messages.some(message => message.role === 'assistant'),
                    input: conversationInput }) + '\\n');
                  if (planner && input?.userMessages?.some(message => message.text === 'Hold this fixture reply until released.') && !turnCalls.includes('companion_reply')) {
                    if (!replyReleased) await new Promise(resolve => { releaseReply = resolve; });
                  }
                  if (planner) {
                    const forbidden = tools.filter(name => !['web_search', 'web_fetch', 'companion_reply', 'companion_wait',
                      'companion_quiet', 'companion_work', 'companion_recall', 'companion_remember', 'companion_forget', 'companion_attachment',
                      'companion_stickers', 'companion_emoji'].includes(name));
                    if (forbidden.length) throw Error('Unexpected companion tools: ' + forbidden.join(','));
                    if (!templateVerified) throw Error('The Planner did not use the original MaiBot template');
                    if (!replySchema?.required?.includes('reply_reference') || replySchema.properties?.intent
                      || replySchema.properties?.stickerId) throw Error('Unexpected model-facing companion reply schema');
                    const target = input?.userMessages?.at(-1)?.id || '';
                    const scroll = input?.userMessages?.some(message => message.text === 'Please send sixty short numbered messages for our scroll check.');
                    const reply = async function* (reference, quote = false, messageId = target) {
                      replyReferences.set(options.sessionId, reference);
                      yield* toolReply('companion_reply', { reply_reference: reference, msg_id: messageId, quoteReply: quote,
                        reply_style: owner.kind === 'work' ? '长回复' : companion === 'maimai' ? '简短表达' : '正常回复',
                        expression_intent: { focus: companion, reply_act: 'respond', scene: 'local dialogue check', tone: 'natural',
                          prefer: ['plain speech'], avoid: ['customer-service formulas'] } });
                    };
                    if (turnCalls.includes('companion_reply')) {
                      const sent = turnCalls.filter(name => name === 'companion_reply').length;
                      if (scroll && sent < 20) {yield* reply('fixture:scroll-history:' + sent);return;}
                      const greeting = !input?.internalTaskResults?.length && input?.userMessages?.some(message =>
                        message.text === "Hi Kaka, let's chat.");
                      if (owner.kind === 'chat' && companion === 'kaka' && greeting) {
                        if (!turnCalls.includes('companion_stickers')) {yield* toolReply('companion_stickers', {});return;}
                        if (!turnCalls.includes('companion_emoji')) {yield* toolReply('companion_emoji', { msg_id: target });return;}
                      }
                      stopChoices.set(options.sessionId, { companion, locale, reference: replyReferences.get(options.sessionId),
                        target, sessionId: options.sessionId });
                      yield* toolReply('companion_quiet', {});return;
                    }
                    if (userTexts.includes('DROY_FIXTURE_WEEKEND_PLAN')) {
                      yield* reply('fixture:work-body'); return;
                    }
                    if (input?.internalTaskResults?.length) {
                      if (input.internalTaskResults.some(result => result.state !== 'completed')) throw Error('The local companion work did not complete');
                      yield* reply('fixture:work-completed'); return;
                    }
                    if (input?.userMessages?.some(message => message.text === 'Please quote my earlier greeting.')) {
                      const earlier = actualMessages.find(message => message.role === 'user' && message.text === "Hi Kaka, let's chat.");
                      if (!earlier) throw Error('Missing earlier greeting for explicit quote fixture');
                      yield* reply('fixture:explicit-quote', true, earlier.id); return;
                    }
                    if (scroll) {
                      yield* reply('fixture:scroll-history:0'); return;
                    }
                    const work = input?.userMessages?.find(message => message.text === "Please draft a short weekend plan as a separate task.");
                    if (work && !startedWorks.has(work.id)) {
                      startedWorks.add(work.id);
                      yield* toolReply('companion_work', { title: chinese ? '周末小计划' : 'Weekend plan', goal: 'DROY_FIXTURE_WEEKEND_PLAN' }); return;
                    }
                    yield* reply(work ? 'fixture:work-started' : 'fixture:' + companion + ':greeting');
                    return;
                  }
                  if (summary) {
                    yield* textReply(chinese ? '伙伴发送了本地滚动测试的编号消息，没有新增用户事实或操作手机。'
                      : 'The companion sent numbered messages for a local scroll check, with no new user facts or phone operations.');
                    return;
                  }
                  if (emoji) {
                    if (!emojiCandidatesVerified) throw Error('The independent emoji selector did not receive three described local candidates');
                    yield* textReply(JSON.stringify({ emoji_index: 1, reason: 'This local sticker acknowledges the user naturally.' }));return;
                  }
                  if (!templateVerified || !referenceMessage || !realHistoryVerified || !styleVerified)
                    throw Error('The Replyer did not receive its original template, real history, and separate reply reference');
                  if (/^fixture:scroll-history:\\d+$/u.test(reference)) {
                    const batch = Number(reference.split(':').at(-1));
                    if (batch < 0 || batch >= 20) throw Error('Unexpected local scroll batch');
                    yield* textReply(Array.from({ length: 3 }, (_, index) => chinese ? '第' + String(batch * 3 + index + 1).padStart(2, '0') + '条'
                      : 'Item ' + String(batch * 3 + index + 1).padStart(2, '0')).join('\\n\\n'));
                    return;
                  }
                  const replies = chinese ? {
                    'fixture:explicit-quote': '你刚才的问候我收到了，我们接着聊。',
                    'fixture:kaka:greeting': '嘿，收到啦。\\n\\n今天想聊点什么？',
                    'fixture:maimai:greeting': '在呢。\\n\\n慢慢说，我听着。',
                    'fixture:work-started': '交给我，我整理好再告诉你。',
                    'fixture:work-body': '周末小计划：上午散步，下午读书。这是本地生成的测试计划，没有操作手机。',
                    'fixture:work-completed': 'DROY_WORK_RESULT 周末小计划整理好了。\\n\\n上午散步，下午读书，留点时间放空。',
                  } : {
                    'fixture:explicit-quote': 'I got your earlier greeting. Let us keep talking.',
                    'fixture:kaka:greeting': 'Hey, got it.\\n\\nWhat would you like to chat about?',
                    'fixture:maimai:greeting': "I'm here.\\n\\nTake your time; I'm listening.",
                    'fixture:work-started': "Leave it with me; I'll tell you when it's ready.",
                    'fixture:work-body': 'Weekend plan: a walk in the morning and reading in the afternoon. This local test plan did not operate the phone.',
                    'fixture:work-completed': 'DROY_WORK_RESULT The weekend plan is ready.\\n\\nA morning walk, afternoon reading, and some time to unwind.',
                  };
                  const reply = replies[reference];
                  if (!reply) throw Error('Unexpected local companion fixture request');
                  yield* textReply(reply);
                }
              }
              const { requireMobileSession } = await import(pathToFileURL(join(process.env.DROY_RUNTIME_DIR,
                'node_modules/@droyagent/mobile/auth.mjs')).href);
              ctx.webServer.register({ kind: 'exact', path: '/droy/setup-chat-fixture', handler: async (req, res) => {
                if (!requireMobileSession(req, res)) return;
                const runtime = ctx.get('droyCompanions');
                if (req.method !== 'POST' || !runtime) {res.writeHead(503);res.end();return;}
                try {
                  let raw = '';for await (const part of req) {raw += part;if (raw.length > 1024) throw Error('Oversized fixture input');}
                  const input = JSON.parse(raw);
                  if (input.operation === 'release') {replyReleased = true;releaseReply?.();releaseReply = undefined;res.writeHead(200, {'Content-Type': 'application/json'});res.end('{"released":true}');return;}
                  if (input.operation === 'clock') {res.writeHead(200, {'Content-Type': 'application/json'});res.end(JSON.stringify({...await runtime.clock(),plannerRequest:lastPlannerRequest,replyerRequest:lastReplyerRequest}));return;}
                  if (!['seed', 'append'].includes(input.operation) || ![100, 1000].includes(input.count)) throw Error('Invalid fixture seed');
                  if (input.operation === 'seed') await runtime.clearHistory('kaka');
                  const seedPrefix = 'fixture-history-' + input.count + '-' + (++sequence) + '-';
                  for (let index = 0; index < input.count; index++) await runtime.store.appendMessage('kaka', {
                    id: seedPrefix + index, role: index % 2 ? 'assistant' : 'user', type: 'text',
                    text: 'Synthetic history ' + index + ': a short message for scrolling and input checks.'
                      + (index % 7 === 0 ? '\\nA second line checks variable message height.' : ''),
                    status: 'received', createdAt: new Date(Date.UTC(2026, 9, 27, 2, 0, index * 30)).toISOString(),
                  });
                  runtime.emit('kaka');
                  res.writeHead(200, {'Content-Type': 'application/json'});res.end(JSON.stringify({count: input.count}));
                } catch (error) {res.writeHead(400, {'Content-Type': 'application/json'});res.end(JSON.stringify({error: String(error.message)}));}
              }});
              ctx.llm.registerAdapter(['droy-setup-fixture'], new SetupModel());
              ctx.systemPrompt.context({ name: 'setup-private-context-canary', order: 1000,
                required: true, text: 'SETUP_PRIVATE_CONTEXT_MUST_NOT_REACH_THE_MODEL' });
              await writeFile(join(process.env.DSH_HOME, 'setup-fixture-ready.json'), '{"ready":true}');
            }
            """;
    }
}
