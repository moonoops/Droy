import { readFileSync } from 'node:fs';
import { defineTool } from '@deepseek-ai/dsh-tools';
import { resolveMobileLocale } from './locale.mjs';

export const BASE_TOOL_NAMES = new Set([
  'android_capabilities', 'android_find_apps', 'android_open_app', 'android_observe', 'android_screenshot',
  'android_sequence', 'android_tap', 'android_input_text', 'android_swipe', 'android_gesture', 'android_node_action',
  'android_global_action', 'android_back', 'android_wait_for', 'android_verification', 'android_system_info',
  'android_open_settings', 'android_file_grants', 'android_file_export', 'ask_user_question',
  'droy_pet_progress', 'droy_load_tools', 'web_search', 'web_fetch', 'read_image',
]);
const manuals = Object.fromEntries(['memory', 'audio', 'notifications', 'device', 'browser'].map(name =>
  [name, Object.fromEntries(['zh', 'en'].map(locale => [locale, readFileSync(new URL(`./prompts/extensions/${name}${locale === 'en' ? '.en' : ''}.md`, import.meta.url), 'utf8').trim()]))]));
export const TOOL_GROUPS = {
  memory: { label: '指定应用进程内存查询/扫描/读写', names: ['android_process_memory', 'android_memory_scan', 'android_memory_read', 'android_memory_write'] },
  audio: { label: '录音、转写及助手音色查询与切换', names: ['android_audio_start', 'android_audio_status', 'android_audio_stop', 'audio_transcribe', 'audio_transcript_read', 'audio_transcription_stop', 'droy_voice'] },
  notifications: { label: '通知查询、管理规则及主动通知', names: ['android_notifications_query', 'droy_notification_rules', 'droy_notification_channels', 'droy_notify'] },
  device: { label: '设置、应用诊断、日历、联系人、授权文件', names: ['android_settings_get', 'android_settings_set', 'android_settings_restore', 'android_app_info', 'android_app_exit_info', 'android_battery_usage', 'android_network_usage', 'android_privacy_usage', 'android_usage_query', 'android_calendar_query', 'android_contacts_query', 'android_file_list', 'android_file_read'] },
  browser: { label: 'Droy 内置浏览器页面交互', names: ['droy_browser_status', 'droy_browser_open', 'droy_browser_observe', 'droy_browser_click', 'droy_browser_input', 'droy_browser_back', 'droy_browser_wait'] },
  compute: { label: '本地结构化计算及产物', names: ['droy_compute_run', 'droy_compute_jobs', 'droy_compute_artifact'] },
  workspace: { label: '工作区读写/搜索与开发命令（Shell 须有可用沙箱）', names: ['read', 'write', 'edit', 'glob', 'grep', 'bash', 'working_directory', 'job_list', 'job_output', 'job_kill'] },
  automation: { label: '定时任务、持续目标及计划', names: ['schedule_create', 'schedule_list', 'schedule_update', 'schedule_delete', 'create_goal', 'get_goal', 'update_goal', 'todo_write', 'exit_plan_mode'] },
  skills: { label: '读取/明确要求时保存技能与工作流', names: ['skill', 'droy_skill_save', 'workflow'] },
  agents: { label: '用户任务需要的代理协作', names: ['subagent', 'subagent_fork', 'list_agents', 'send_message', 'interrupt_agent', 'spawn_teammate', 'team_task_create', 'team_task_get', 'team_task_list', 'team_task_update', 'wait_agent'] },
  extensions: { label: '其他已安装插件工具及展示能力', names: [] },
};
export const TOOL_GROUP_LABELS_EN = {
  memory: 'Query, scan, read, and write a specified app process memory',
  audio: 'Record, transcribe, query, and select assistant voices',
  notifications: 'Query notifications, manage rules, and notify proactively',
  device: 'Settings, app diagnostics, calendar, contacts, and granted files',
  browser: 'Interact with Droy built-in browser pages',
  compute: 'Local structured computation and artifacts',
  workspace: 'Read, write, and search workspace files; development commands (Shell requires an available sandbox)',
  automation: 'Scheduled tasks, ongoing goals, and plans',
  skills: 'Read skills/workflows and save them only when explicitly requested',
  agents: 'Agent collaboration required by the user task',
  extensions: 'Other installed plugin tools and display capabilities',
};
function toolGroupGuide(locale) {
  return Object.entries(TOOL_GROUPS).map(([name, group]) => `${name}: ${locale === 'zh' ? group.label : TOOL_GROUP_LABELS_EN[name]}`).join('; ');
}
function loaderDescription(locale) {
  return locale === 'zh'
    ? `按任务需要展开一个扩展工具组及使用说明；本会话复用已展开组，不重复加载。${toolGroupGuide(locale)}。加载不增加权限，也不表示 Shell 或系统通道可用。`
    : `Load an extension tool group and its instructions when needed; reuse loaded groups in this session without repeated loading. ${toolGroupGuide(locale)}. Loading grants no permissions and does not establish that Shell or a system channel is available.`;
}
const assigned = new Set([...BASE_TOOL_NAMES, ...Object.values(TOOL_GROUPS).flatMap(group => group.names)]);
const sectionGroups = new Map([
  ...['context:file-reference', 'tool:bash', 'tool:read', 'tool:write', 'tool:edit', 'tool:glob', 'tool:grep',
    'tool:jobs', 'harness:source', 'app:web-surface'].map(name => [name, 'workspace']),
  ['tool:goal', 'automation'], ['tool:workflow', 'skills'],
  ['team:policy', 'agents'], ['tool:subagent', 'agents'], ['tool:subagent_fork', 'agents'],
  ['ui:deliverable-file-references', 'extensions'],
]);
export const TOOL_GROUP_GUIDE = Object.entries(TOOL_GROUPS).map(([name, group]) => `${name}：${group.label}`).join('；');

/** Limit model discovery without adding inheritable permission restrictions. */
export function installToolGroups(ctx) {
  const states = new WeakMap();
  function stateFor(agent) {
    let state = states.get(agent);
    if (!state) {
      const loaded = new Set(), calls = new Set();
      // Successful loader receipts survive model changes/resume; old full headers do not opt in.
      for (const event of agent.session.snapshotEvents()) {
        if (event.type === 'tool/call' && event.data.name === 'droy_load_tools') calls.add(`${event.data.turn}:${event.data.step}:${event.data.callId}`);
        if (event.type !== 'tool/result' || event.data.message.isError || !calls.has(`${event.data.turn}:${event.data.step}:${event.data.message.toolCallId}`)) continue;
        for (const block of event.data.message.content) {
          if (block.type !== 'text') continue;
          let result; try { result = JSON.parse(block.text); } catch { continue; }
          for (const group of Array.isArray(result?.loadedGroups) ? result.loadedGroups : []) if (Object.hasOwn(TOOL_GROUPS, group)) loaded.add(group);
        }
      }
      state = { loaded }; states.set(agent, state);
    }
    return state;
  }
  function exposedNames(agent) {
    const state = stateFor(agent), allowed = new Set(BASE_TOOL_NAMES);
    const inherited = ctx.tools.view(agent).restrictableNames;
    for (const group of state.loaded) for (const name of TOOL_GROUPS[group].names) allowed.add(name);
    return new Set(ctx.tools.schemas(agent).map(tool => tool.name).filter(name => !inherited.has(name) && !assigned.has(name) || allowed.has(name)
      || state.loaded.has('extensions') && !assigned.has(name)));
  }
  ctx.systemPrompt.context({ name: 'droy:loaded-tool-instructions', order: 106, required: true, interpolate: false,
    text: context => [...states.get(context.agent)?.loaded ?? []].map(group => manuals[group]?.[resolveMobileLocale(ctx)]).filter(Boolean).join('\n\n'),
  });
  ctx.on('agent/created', ({ agent }) => { stateFor(agent); });
  ctx.on('agent/disposed', ({ agent }) => { states.delete(agent); });
  ctx.on('system-prompt/assemble', async (_assembly, context, next) => {
    if (!context.agent) return next();
    stateFor(context.agent);
    const assembly = await next();
    // Product discovery is per agent; inherited user/plugin restrictions still apply.
    const visible = exposedNames(context.agent);
    assembly.tools = assembly.tools.filter(tool => visible.has(tool.name)).map(tool => tool.name === 'droy_load_tools'
      ? { ...tool, description: loaderDescription(resolveMobileLocale(ctx)) } : tool);
    // Keep known optional manuals with their tools, while preserving user/unknown policy sections.
    const loaded = stateFor(context.agent).loaded;
    assembly.sections = assembly.sections.filter(section => !sectionGroups.has(section.name)
      || loaded.has(sectionGroups.get(section.name)));
    return assembly;
  }, { prepend: true });
  ctx.tools.register(defineTool({
    name: 'droy_load_tools',
    description: loaderDescription(resolveMobileLocale(ctx)),
    parameters: { group: { type: 'string', enum: Object.keys(TOOL_GROUPS), required: true } },
    output: { schema: { type: 'json' }, render: (_args, value) => [{ type: 'text', text: JSON.stringify(value) }] },
    async execute({ group }, exec) {
      if (!exec.agent || !Object.hasOwn(TOOL_GROUPS, group)) throw new Error(resolveMobileLocale(ctx) === 'zh' ? '无效工具组或会话' : 'Invalid tool group or session');
      stateFor(exec.agent).loaded.add(group);
      const state = stateFor(exec.agent);
      const visible = ctx.tools.schemas(exec.agent).map(tool => tool.name);
      const names = group === 'extensions' ? visible.filter(name => !assigned.has(name))
        : TOOL_GROUPS[group].names.filter(name => visible.includes(name));
      return { group, tools: names, loadedGroups: [...state.loaded],
        ...(group === 'workspace' ? { shell: resolveMobileLocale(ctx) === 'zh' ? '仅在已有沙箱可用时执行；不可用时使用现有 Android 或计算能力，不绕过沙箱。' : 'Execute only with an existing available sandbox. Otherwise use available Android or computation capabilities; do not bypass the sandbox.' } : {}) };
    },
  }));
  return { load(agent, groups) { for (const group of groups) {
    if (!Object.hasOwn(TOOL_GROUPS, group)) throw new Error(resolveMobileLocale(ctx) === 'zh' ? '无效工具组' : 'Invalid tool group');
    stateFor(agent).loaded.add(group);
  } }, groups: agent => [...stateFor(agent).loaded] };
}
