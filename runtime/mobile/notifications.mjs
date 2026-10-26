import { defineTool } from '@deepseek-ai/dsh-tools';
import { createBridge, BridgeError } from './bridge.mjs';
import { resolveMobileLocale } from './locale.mjs';
import { translateBackendText } from './backend-i18n.mjs';

export const name = 'droy-notifications';
export const inject = ['tools', 'agents', 'sessionProjections'];

export const notificationGuidance = '通知是按需使用的能力，先判断是否值得打扰用户。日常减少频率，合并相关信息；'
  + '简单事项使用简短内容和单一渠道，重要但不紧急的事项集中通知，紧急且重要或用户明确要求时才使用少量互补渠道。'
  + '当前对话中能直接回答时通常无需额外通知；可自行处理的错误继续处理，不逐步骤提醒。'
  + '只选已启用且可用的渠道，优先足够有效且侵入较小的方式。不推断已读，不自动重复提醒或逐级增加渠道。'
  + '用户开启完成后自动返回时，普通结果依靠随后显示的对话，需要保留入口则选quiet系统通知，不先弹出伙伴提示或响铃。';

export const channelsTool = {
  name: 'droy_notification_channels',
  description: '按需查询 Droy 当前可选通知渠道、默认接收目标和展示状态。' + notificationGuidance
    + '手表和其他客户端未接入时不出现在可用渠道中。不要为判断通知持续查询。',
  parameters: {},
};

export const notifyTool = {
  name: 'droy_notify',
  description: notificationGuidance
    + ' send按选定渠道通知；本机直接执行。App返回requires_action只表示准备完成，须由当前任务继续使用Android Use核实会话、输入并发送，'
    + '再观察发送后的会话，用complete记录结果；在最终回复前完成，不另起Agent。相同事项重用noticeKey以避免重复，实质新事项用新key。'
    + 'sent_in_app须引用发送后的android_observe或android_screenshot调用ID，核实正确会话和非输入框中的对应消息；文字观察需包含会话与消息，截图由当前视觉模型核实。单纯打开、输入或点击成功不能证明发送。'
    + '无法核实时用unknown，不盲目重发。发送结果不表示用户已看到或对方已读。它不创建定时任务、问题或审批。',
  parameters: {
    operation: { type: 'string', enum: ['send', 'complete'], required: true },
    noticeKey: { type: 'string', description: 'send必需，本轮同一事项使用稳定短名，如result或needs-input；不同事项不要混用。' },
    title: { type: 'string', description: 'send可选，简短标题。' },
    text: { type: 'string', description: 'send必需，简短必要内容；详情留在原任务。' },
    attention: { type: 'string', enum: ['quiet', 'normal', 'urgent'], description: '默认quiet。normal需要及时关注；urgent用于紧急且重要事项，仍遵循系统设置。' },
    destinations: { type: 'array', items: { type: 'object', additionalProperties: false, properties: {
      channel: { type: 'string', required: true, description: '来自渠道查询的id。通常只选一个。' },
      packageName: { type: 'string', description: 'App渠道使用已允许的真实应用包名。' },
      target: { type: 'string', description: 'App接收目标；任务明确指定时使用该目标，否则使用默认目标。' },
    } }, description: 'send必需。多个App由当前Agent依次send、操作、complete；一个请求最多选择一个App目的地。' },
    noticeId: { type: 'string', description: 'complete必需，send返回的App通知记录ID。' },
    status: { type: 'string', enum: ['sent_in_app', 'failed', 'unknown', 'cancelled'], description: 'complete必需，只记录实际可确认状态。' },
    evidenceCallId: { type: 'string', description: 'sent_in_app必需，发送后的android_observe或android_screenshot调用ID；已核实正确会话和对应消息。' },
  },
};

function identity(ctx, exec) {
  const agent = exec.agent;
  if (!agent?.id || !exec.callId) throw new BridgeError('IDENTITY_REQUIRED', translateBackendText('通知工具缺少任务身份', resolveMobileLocale(ctx)));
  const turnId = ctx.sessionProjections.stateOf(agent.session, 'turnBoundary')?.openTurnStartSeq;
  if (!Number.isSafeInteger(turnId) || turnId < 0) throw new BridgeError('TURN_REQUIRED', translateBackendText('通知需要正在执行的任务轮次', resolveMobileLocale(ctx)));
  return { taskId: String(agent.id), turnId: String(turnId), callId: String(exec.callId) };
}

function toolHistory(session) {
  const calls = new Map();
  const results = [];
  for (const event of session.snapshotEvents()) {
    if (event.type === 'tool/call') calls.set(String(event.data.callId), { ...event.data, seq: event.seq });
    else if (event.type === 'tool/ptc-dispatch-start') calls.set(String(event.data.subCallId), {
      name: event.data.name, arguments: event.data.arguments, seq: event.seq,
    });
    else if (event.type === 'tool/result') results.push({ seq: event.seq, callId: String(event.data.message.toolCallId),
      content: event.data.message.content, isError: event.data.message.isError });
    else if (event.type === 'tool/ptc-dispatch') results.push({ seq: event.seq, callId: String(event.data.subCallId),
      content: event.data.content, isError: event.data.isError });
  }
  return { calls, results };
}

function resultJson(content) {
  for (const block of content ?? []) {
    if (block.type !== 'text') continue;
    try { const value = JSON.parse(block.text); if (value && typeof value === 'object') return value; }
    catch { /* Other tools may return ordinary text rather than a native observation. */ }
  }
  return null;
}

const normalized = value => String(value ?? '').replace(/\s+/gu, ' ').trim();
const pageActions = new Set(['android_tap', 'android_input_text', 'android_gesture', 'android_swipe', 'android_back', 'android_open_app', 'android_verification']);

/** Bind the Agent's semantic conclusion to the actual post-action observation. */
export function notificationEvidence(session, pending, callId) {
  const { calls, results } = toolHistory(session);
  const call = calls.get(String(callId));
  const result = results.find(value => value.callId === String(callId));
  if (!call || !['android_observe', 'android_screenshot'].includes(call.name)
      || call.seq <= pending.afterSeq || !result || result.isError)
    return null;
  const action = results.find(value => value.seq > pending.afterSeq && value.seq < call.seq && !value.isError
    && ['android_tap', 'android_input_text', 'android_gesture'].includes(calls.get(value.callId)?.name));
  if (!action) return null;
  const observation = resultJson(result.content);
  if (!observation) return null;
  let basis, image, observeCallId, packageName = observation.packageName;
  if (call.name === 'android_screenshot') {
    image = result.content?.find(block => block.type === 'image' && block.attachment);
    if (observation.status !== 'captured' || !image) return null;
    if (!packageName) {
      const context = results.filter(value => value.seq > pending.afterSeq && value.seq < call.seq && !value.isError
        && calls.get(value.callId)?.name === 'android_observe').at(-1);
      if (!context) return null;
      packageName = resultJson(context.content)?.packageName;
      if ([...calls.values()].some(value => value.seq > context.seq && value.seq < call.seq && pageActions.has(value.name))) return null;
      observeCallId = context.callId;
    }
    if (packageName !== pending.packageName) return null;
    basis = 'post_send_image_observation';
  } else {
    if (!observation.snapshotId || packageName !== pending.packageName || !Array.isArray(observation.nodes)) return null;
    const visible = observation.nodes.filter(node => !node.editable && !node.password)
      .flatMap(node => [normalized(node.text), normalized(node.description)]).filter(Boolean);
    if (!visible.some(value => value.includes(normalized(pending.target)))
        || !visible.some(value => value === normalized(pending.text))) return null;
    basis = 'post_send_page_observation';
  }
  return { callId: String(callId), seq: result.seq, ...(observation.snapshotId ? { snapshotId: observation.snapshotId } : {}),
    packageName, target: pending.target, messageObserved: true, basis,
    ...(image ? { attachment: image.attachment } : {}), ...(observeCallId ? { observeCallId } : {}) };
}

export function createNotificationService(ctx, bridge, ready = Promise.resolve()) {
  const providers = new Map();
  const pending = new Map();
  const activeTasks = new Set();
  const releasing = new Map();
  const lifetime = new AbortController();
  let disposed = false;
  let activated = false;
  const activatedReady = ready.then(() => { activated = true; });
  // Activation is also awaited by every tool; observing its failure here avoids an unhandled rejection before first use.
  void activatedReady.catch(() => {});
  function registerChannel(provider) {
    if (!provider || typeof provider.id !== 'string' || !provider.id || providers.has(provider.id)
        || typeof provider.describe !== 'function' || (!provider.native && typeof provider.send !== 'function'))
      throw new Error('DROY_NOTIFICATION_CHANNEL_INVALID');
    providers.set(provider.id, provider);
    return () => { if (providers.get(provider.id) === provider) providers.delete(provider.id); };
  }
  for (const id of ['system', 'pet', 'app']) registerChannel({ id, native: true,
    describe: state => state.channels?.find(channel => channel.id === id) ?? { id, available: false } });

  async function available() {
    if (disposed) throw new Error('DROY_NOTIFICATIONS_DISABLED');
    await activatedReady;
  }
  async function channels(signal) {
    await available();
    signal?.throwIfAborted();
    const state = await bridge.notify({ operation: 'get' }, signal);
    // The native settings snapshot also contains the app picker inventory. Notification discovery needs only saved grants.
    const projection = Object.fromEntries(['pluginActive', 'localEnabled', 'appsEnabled', 'allowedApps', 'context', 'channels']
      .filter(key => Object.hasOwn(state, key)).map(key => [key, state[key]]));
    const channels = [];
    for (const provider of providers.values()) channels.push({ ...await provider.describe(projection), id: provider.id });
    return { ...projection, channels };
  }
  async function end(taskId) {
    taskId = String(taskId);
    if (!activeTasks.delete(taskId)) return releasing.get(taskId);
    for (const [id, entry] of pending) if (entry.taskId === taskId) pending.delete(id);
    const operation = bridge.notify({ operation: 'end', taskId });
    releasing.set(taskId, operation);
    try { await operation; }
    finally { if (releasing.get(taskId) === operation) releasing.delete(taskId); }
  }
  async function send(args, exec) {
    await available();
    exec.signal.throwIfAborted();
    const owner = identity(ctx, exec);
    await releasing.get(owner.taskId);
    const signal = AbortSignal.any([exec.signal, lifetime.signal]);
    signal.throwIfAborted();
    if (!args.noticeKey?.trim() || !args.text?.trim() || !args.destinations?.length) throw new Error('DROY_NOTIFICATION_SEND_FIELDS_REQUIRED');
    if (args.destinations.filter(value => value.channel === 'app').length > 1) throw new Error('DROY_NOTIFICATION_APP_SERIAL_REQUIRED');
    for (const destination of args.destinations) if (!providers.has(destination.channel)) throw new Error('DROY_NOTIFICATION_CHANNEL_UNKNOWN');
    const payload = { operation: 'send', ...owner, noticeKey: args.noticeKey, title: args.title ?? '', text: args.text,
      attention: args.attention ?? 'quiet', destinations: args.destinations };
    const afterSeq = exec.agent.session.snapshotEvents().at(-1)?.seq ?? -1;
    activeTasks.add(owner.taskId);
    const abort = () => { void end(owner.taskId).catch(error => ctx.logger.warn('DROY_NOTIFICATION_CLEANUP_FAILED', error.code)); };
    signal.addEventListener('abort', abort, { once: true });
    try {
      const native = payload.destinations.filter(value => providers.get(value.channel).native);
      const results = [];
      if (native.length) {
        const response = await bridge.notify({ ...payload, destinations: native }, signal);
        if (!Array.isArray(response.results)) throw new BridgeError('INVALID_NOTIFICATION_RESULT', translateBackendText('原生通知结果无效', resolveMobileLocale(ctx)));
        signal.throwIfAborted();
        for (const row of response.results) {
          results.push(row);
          if (row.status === 'requires_action') pending.set(row.noticeId, { ...row, ...owner, afterSeq,
            text: row.text ?? payload.text });
        }
      }
      for (const destination of payload.destinations.filter(value => !providers.get(value.channel).native)) {
        signal.throwIfAborted();
        results.push(await providers.get(destination.channel).send({ ...payload, destinations: [destination] }, { ...exec, signal }));
      }
      return { results, ...(results.some(row => row.status === 'requires_action') ? {
        next: translateBackendText('当前任务继续用Android Use核实目标会话并发送，再观察对应消息；完成后调用droy_notify complete。结果未知时不要重发。', resolveMobileLocale(ctx)),
      } : {}) };
    } finally { signal.removeEventListener('abort', abort); }
  }
  async function complete(args, exec) {
    await available();
    exec.signal.throwIfAborted();
    const owner = identity(ctx, exec);
    const entry = pending.get(args.noticeId);
    if (!entry || entry.taskId !== owner.taskId || entry.turnId !== owner.turnId) throw new Error('DROY_NOTIFICATION_NOT_PENDING');
    if (!['sent_in_app', 'failed', 'unknown', 'cancelled'].includes(args.status)) throw new Error('DROY_NOTIFICATION_INVALID_STATUS');
    const evidence = args.status === 'sent_in_app' ? notificationEvidence(exec.agent.session, entry, args.evidenceCallId) : null;
    const status = args.status === 'sent_in_app' && !evidence ? 'unknown' : args.status;
    const result = await bridge.notify({ operation: 'complete', ...owner, noticeId: args.noticeId, status,
      ...(evidence ? { evidence } : {}) }, exec.signal);
    pending.delete(args.noticeId);
    return { ...result, ...(args.status !== status ? { reason: translateBackendText('缺少发送后目标会话中对应消息的观察，已记录为结果未知。', resolveMobileLocale(ctx)) } : {}) };
  }
  return { get active() { return activated && !disposed; }, registerChannel, channels, send, complete, end,
    async dispose() {
      disposed = true;
      const appTasks = [...new Set([...pending.values()].map(entry => entry.taskId))];
      for (const taskId of appTasks) ctx.agents.get(taskId)?.cancel({ kind: 'disposed' });
      lifetime.abort();
      // Cancel native tickets before dropping their notification context. Do not await
      // Agent idle: the current Agent may itself be inside the plugin-management call.
      await Promise.all(appTasks.map(taskId => bridge.end(taskId)));
      pending.clear(); activeTasks.clear(); providers.clear();
      await activatedReady.catch(() => {});
      await Promise.allSettled([...releasing.values()]);
      activated = false;
      await bridge.notify({ operation: 'activate', enabled: false });
    } };
}

export function apply(ctx) {
  const bridge = createBridge({ getLocale: () => resolveMobileLocale(ctx) });
  const ready = bridge.notify({ operation: 'activate', enabled: true });
  void ready.catch(error => ctx.logger.warn('DROY_NOTIFICATION_ACTIVATION_FAILED', error.code));
  const service = createNotificationService(ctx, bridge, ready);
  ctx.provide('droyNotifications', service);
  const output = { schema: { type: 'json' }, render: (_args, value) => [{ type: 'text', text: JSON.stringify(value) }] };
  ctx.tools.register(defineTool({ ...channelsTool, output, execute: (_args, exec) => service.channels(exec.signal) }));
  ctx.tools.register(defineTool({ ...notifyTool, output, execute: (args, exec) => args.operation === 'send'
    ? service.send(args, exec) : service.complete(args, exec) }));
  const finish = taskId => { void service.end(taskId).catch(error => ctx.logger.warn('DROY_NOTIFICATION_CLEANUP_FAILED', error.code)); };
  ctx.on('session/event', (session, event) => { if (event.type === 'turn/end') finish(session.id); });
  ctx.on('agent/disposed', ({ agent }) => finish(agent.id));
  ctx.effect(() => () => service.dispose().catch(error => ctx.logger.warn('DROY_NOTIFICATION_CLEANUP_FAILED', error.code)));
}
