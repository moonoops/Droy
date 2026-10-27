import { defineTool } from '@deepseek-ai/dsh-tools';
import { createBridge, BridgeError } from './bridge.mjs';
import { toolDefinitions } from './catalog.mjs';
import { runtimeVersions } from './versions.mjs';
import { createExecutionProgress } from './execution-progress.mjs';
import { installPetInteractions } from './pet-interactions.mjs';
import { petProgressTool } from './pet-text.mjs';
import { installPetGeneration } from './pet-generation.mjs';
import { installNotificationManagement } from './notification-management.mjs';
import { createVerificationWorkflow } from './verification.mjs';
import { installNotificationControl } from './notification-control.mjs';
import { installDroySkills } from './skills.mjs';
import { installCompanionIdentity } from './companion.mjs';
import { installAudioServices } from './audio.mjs';
import { installToolGroups } from './tool-groups.mjs';
import { installTokenUsage } from './token-usage.mjs';
import { renderPhoneResult } from './tool-result.mjs';
import { installSetupServices } from './setup.mjs';
import { resolveMobileLocale } from './locale.mjs';
import { translateBackendText } from './backend-i18n.mjs';
import { protectHardwareDisclosure } from './hardware-disclosure.mjs';

export const name = 'droy-android-use';
export const inject = ['tools', 'llm', 'attachments', 'webServer', 'settings', 'agents', 'userQuestions', 'systemPrompt', 'droyPluginPolicy'];
const definitions = toolDefinitions;

export function apply(ctx) {
  ctx.inject(['sessionProjections'], scope => installTokenUsage(scope));
  const getLocale = () => resolveMobileLocale(ctx);
  const message = text => translateBackendText(text, getLocale());
  const bridge = createBridge({ getLocale });
  installSetupServices(ctx, bridge);
  const audio = installAudioServices(ctx, bridge);
  installCompanionIdentity(ctx, bridge);
  const verification = createVerificationWorkflow(ctx, bridge);
  const progress = createExecutionProgress(payload => bridge.progress(payload),
    () => ctx.logger.warn('DROY_PROGRESS_REPORT_FAILED'), undefined, getLocale);
  ctx.on('session/event', (session, event) => progress.sessionEvent(session, event));
  ctx.effect(() => () => progress.dispose());
  installPetInteractions(ctx, progress);
  installPetGeneration(ctx);
  installNotificationManagement(ctx, bridge, defineTool);
  installNotificationControl(ctx);
  installDroySkills(ctx);
  installToolGroups(ctx);
  ctx.tools.register(defineTool({
    ...petProgressTool,
    output: { schema: { type: 'json' }, render: (_args, value) => [{ type: 'text', text: JSON.stringify(value) }] },
    async execute(args, exec) {
      if (!exec.agent?.id) throw new BridgeError('IDENTITY_REQUIRED', message('工具调用缺少引擎身份'));
      progress.publicSummary(exec.agent.id, args.summary, args.phase);
      return { updated: true };
    },
  }));
  ctx.webServer.register({ kind:'exact', path:'/droy/ready', async handler(req,res) {
    if (req.method !== 'GET') { res.writeHead(405); res.end(); return; }
    const required = ['sessionController','tools','settings','agents','agentPresets','attachments','llm','connection','workspaceRegistry','droyInitialization','droyLifecycle','droyPluginPolicy'];
    const tools = ctx.tools.schemas().map(tool => tool.name);
    const ready = !ctx.get('droyLifecycle')?.stopping && required.every(service => ctx.get(service) !== undefined)
      && definitions.every(tool => tools.includes(tool.name));
    res.writeHead(ready ? 200 : 503, {'Content-Type':'application/json','Cache-Control':'no-store'});
    res.end(JSON.stringify({ready,...runtimeVersions}));
  } });
  ctx.webServer.register({ kind:'exact', path:'/droy/preferences', async handler(req,res) {
    if (req.method !== 'POST') { res.writeHead(405); res.end(); return; }
    let body = '';
    for await (const part of req) { body += part.toString('utf8'); if (body.length > 1024) { res.writeHead(413); res.end(); return; } }
    let data; try { data = JSON.parse(body); } catch { res.writeHead(400); res.end(); return; }
    if (!['light','dark','system'].includes(data.theme)) { res.writeHead(400); res.end(); return; }
    await ctx.settings.mutate('ui-theme', [{op:'set',path:['preference'],value:data.theme}]);
    res.writeHead(200, {'Content-Type':'application/json'}); res.end('{"ok":true}');
  } });
  const active = new Set();
  const releasing = new Map();
  const release = agent => {
    if (!active.delete(String(agent.id))) return;
    const operation = (async () => { await audio.settle(agent.id); await bridge.end(agent.id); })()
      .catch(error => { ctx.logger.warn('DROY_TASK_RELEASE_FAILED'); throw error; });
    releasing.set(String(agent.id), operation);
    operation.finally(() => { if (releasing.get(String(agent.id)) === operation) releasing.delete(String(agent.id)); }).catch(() => {});
  };
  // idle is documented to follow complete driver quiescence, including failure/cancel.
  ctx.on('agent/status', ({ agent, status }) => {
    progress.status(agent, status);
    if (status === 'idle') release(agent);
  });
  ctx.on('agent/disposed', ({ agent }) => { progress.removed(agent); release(agent); });
  for (const { action, name, description, parameters } of definitions) {
    ctx.tools.register(defineTool({
      name, description, parameters,
      output: { schema: { type: 'json' }, render: (_args, value) => action === 'screenshot'
        ? [{ type: 'text', text: JSON.stringify(renderPhoneResult({ ...value, attachment: undefined })) }, { type: 'image', attachment: value.attachment }]
        : [{ type: 'text', text: JSON.stringify(renderPhoneResult(value)) }] },
      async execute(args, exec) {
        if (!exec.agent?.id) throw new BridgeError('IDENTITY_REQUIRED', message('工具调用缺少引擎身份'));
        await releasing.get(String(exec.agent.id));
        if (action === 'screenshot') {
          const { provider, model } = exec.agent.options;
          if (!provider || !model) throw new BridgeError('MODEL_IMAGE_UNSUPPORTED', message('当前模型未配置图像输入'));
          const info = await ctx.llm.resolveModelInfo(provider, model, exec.signal);
          if (!info.inputModalities?.includes('image')) throw new BridgeError('MODEL_IMAGE_UNSUPPORTED', message('当前模型不支持或未声明图像输入，请使用 android_observe'));
        }
        active.add(String(exec.agent.id));
        let result;
        try {
          // A denied processing route must be discovered before a physical write.
          await protectHardwareDisclosure(ctx, action, args, exec);
          result = action.startsWith('audio_') ? await audio.execute(action, args, exec)
            : action === 'verification' ? await verification(args, exec) : await bridge.call(action, args, exec);
        }
        catch (error) {
          progress.toolFailure(exec.agent.id, exec.callId, error);
          throw error;
        }
        if (action === 'capabilities') {
          const { provider, model } = exec.agent.options;
          let imageInput = 'not_configured';
          if (provider && model) {
            const info = await ctx.llm.resolveModelInfo(provider, model, exec.signal);
            imageInput = info.inputModalities === undefined ? 'unknown'
              : info.inputModalities.includes('image') ? 'supported' : 'unsupported';
          }
          return { ...result, modelImageInput: imageInput,
            droySkillsDirectory: ctx.get('droyInitialization')?.skillDirectory ?? null,
            screenshotMeaning: imageInput === 'supported' ? message('当前模型声明支持图片；截图仍需要产品授权和可用系统通道。')
              : message('当前模型不支持或尚未声明图片输入；可用时优先读取页面文字和控件。') };
        }
        if (action !== 'screenshot') return result;
        if (result.mimeType !== 'image/png' || typeof result.base64 !== 'string' || result.base64.length > 28_000_000) throw new BridgeError('INVALID_SCREENSHOT', message('原生桥返回了无效截图'));
        exec.signal.throwIfAborted();
        const attachmentStarted = performance.now();
        const attachment = await ctx.attachments.saveImage({ data: Buffer.from(result.base64, 'base64'), mediaType: 'image/png', name: 'android-screen.png' });
        const { base64: _bytes, ...metadata } = result;
        return { ...metadata, attachmentTiming: { saveMs: Math.round(performance.now() - attachmentStarted) }, attachment };
      },
    }));
  }
  // Android capabilities enforce their own grants in the native bridge. Other
  // DSH and plugin tools retain the upstream permission and approval policies.
}
