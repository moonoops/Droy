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
  description: '按需查询 Droy 的系统通知、宠物提示是否可用。' + notificationGuidance + '不要为判断通知持续查询。',
  parameters: {},
};

export const notifyTool = {
  name: 'droy_notify',
  description: notificationGuidance
    + '发送本机系统通知或宠物提示。相同事项重用noticeKey，实质新事项用新key。结果不表示用户已读；此工具不创建定时任务、问题或审批。',
  parameters: {
    operation: { type: 'string', enum: ['send'], required: true },
    noticeKey: { type: 'string', required: true, description: '本轮同一事项使用稳定短名，如result或needs-input；不同事项不要混用。' },
    title: { type: 'string', description: '简短标题，可省略。' },
    text: { type: 'string', required: true, description: '简短必要内容；详情留在原任务。' },
    attention: { type: 'string', enum: ['quiet', 'normal', 'urgent'], description: '默认quiet。normal需要及时关注；urgent用于紧急且重要事项，仍遵循系统设置。' },
    destinations: { type: 'array', required: true, items: { type: 'object', additionalProperties: false, properties: {
      channel: { type: 'string', enum: ['system', 'pet'], required: true, description: '选择已启用的系统通知或宠物提示。' },
    } }, description: '通常只选一个渠道。' },
  },
};

function identity(ctx, exec) {
  const agent = exec.agent;
  if (!agent?.id || !exec.callId) throw new BridgeError('IDENTITY_REQUIRED', translateBackendText('通知工具缺少任务身份', resolveMobileLocale(ctx)));
  const turnId = ctx.sessionProjections.stateOf(agent.session, 'turnBoundary')?.openTurnStartSeq;
  if (!Number.isSafeInteger(turnId) || turnId < 0) throw new BridgeError('TURN_REQUIRED', translateBackendText('通知需要正在执行的任务轮次', resolveMobileLocale(ctx)));
  return { taskId: String(agent.id), turnId: String(turnId), callId: String(exec.callId) };
}

export function createNotificationService(ctx, bridge, ready = Promise.resolve()) {
  const lifetime = new AbortController();
  let disposed = false;
  let activated = false;
  const activatedReady = ready.then(() => { activated = true; });
  // Tool calls still observe the activation failure; keep startup from creating an unhandled rejection.
  void activatedReady.catch(() => {});
  async function available(signal) {
    await activatedReady;
    if (disposed) throw new Error('DROY_NOTIFICATIONS_DISABLED');
    signal?.throwIfAborted();
  }
  async function channels(signal) {
    await available(signal);
    const state = await bridge.notify({ operation: 'get' }, signal);
    return { pluginActive: state.pluginActive, tasksEnabled: state.tasksEnabled,
      context: state.context, channels: (state.channels ?? []).filter(value => ['system', 'pet'].includes(value.id)) };
  }
  async function send(args, exec) {
    await available(exec.signal);
    const owner = identity(ctx, exec);
    const signal = AbortSignal.any([exec.signal, lifetime.signal]);
    signal.throwIfAborted();
    if (args.operation !== 'send' || !args.noticeKey?.trim() || !args.text?.trim() || !Array.isArray(args.destinations)
        || args.destinations.length < 1 || args.destinations.length > 2) throw new Error('DROY_NOTIFICATION_SEND_FIELDS_REQUIRED');
    for (const destination of args.destinations) {
      if (!['system', 'pet'].includes(destination.channel) || Object.keys(destination).some(key => key !== 'channel'))
        throw new Error('DROY_NOTIFICATION_CHANNEL_UNKNOWN');
    }
    const response = await bridge.notify({ operation: 'send', ...owner, noticeKey: args.noticeKey, title: args.title ?? '',
      text: args.text, attention: args.attention ?? 'quiet', destinations: args.destinations }, signal);
    signal.throwIfAborted();
    if (!Array.isArray(response.results)) throw new BridgeError('INVALID_NOTIFICATION_RESULT', translateBackendText('原生通知结果无效', resolveMobileLocale(ctx)));
    return { results: response.results };
  }
  return { get active() { return activated && !disposed; }, channels, send,
    async dispose() {
      disposed = true;
      lifetime.abort();
      await activatedReady.catch(() => {});
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
  ctx.tools.register(defineTool({ ...notifyTool, output, execute: (args, exec) => service.send(args, exec) }));
  ctx.effect(() => () => service.dispose().catch(error => ctx.logger.warn('DROY_NOTIFICATION_CLEANUP_FAILED', error.code)));
}
