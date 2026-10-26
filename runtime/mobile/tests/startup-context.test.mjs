import test from 'node:test';
import assert from 'node:assert/strict';
import { createRequire, registerHooks } from 'node:module';
import { pathToFileURL } from 'node:url';
import { toolDefinitions } from '../catalog.mjs';
import { runtimeText, installCompanionIdentity } from '../companion.mjs';
import { companionState } from './fixtures/companion-state.mjs';
process.env.DROY_LOCALE = 'zh';
const installed = createRequire(new URL('../../dsh/package.json', import.meta.url));
const hooks = registerHooks({ resolve(specifier, context, next) {
  if (specifier.startsWith('@deepseek-ai/')) return { url: pathToFileURL(installed.resolve(specifier)).href, shortCircuit: true };
  return next(specifier, context);
} });
const { Context } = await import('@deepseek-ai/cordis');
const { bindScopeParent } = await import('@deepseek-ai/dsh-scope');
const { default: LlmRuntime, LlmAdapter, createUserMessage } = await import('@deepseek-ai/dsh-llm');
const { default: Sessions } = await import('@deepseek-ai/dsh-session');
const { default: TokenMeter } = await import('@deepseek-ai/dsh-token-meter');
const { default: Projections } = await import('@deepseek-ai/dsh-session-projection');
const { default: SystemPrompt } = await import('@deepseek-ai/dsh-system-prompt');
const { default: Tools, defineTool } = await import('@deepseek-ai/dsh-tools');
const { default: Agents } = await import('@deepseek-ai/dsh-agent');
const { default: Loop } = await import('@deepseek-ai/dsh-agent-loop');
const { installToolGroups, TOOL_GROUPS, BASE_TOOL_NAMES } = await import('../tool-groups.mjs');
hooks.deregister();

const call = (name, args = {}) => ({ type: 'tool-call', id: crypto.randomUUID(), name, arguments: JSON.stringify(args) });
class Provider extends LlmAdapter {
  requests = []; steps = [];
  async resolveModel(provider, model) { return { provider, id: model, name: 'local fixture', reasoning: { efforts: [{id:'high',name:'High'}] }, inputModalities: model === 'vision' ? ['text', 'image'] : ['text'] }; }
  async *stream(request) {
    this.requests.push(request);
    const block = request.purpose === 'compaction' ? { type: 'text', text: '用户目标保持。收件人：虚构甲；产物 saved-local.png；之前发送结果未知，不重放；保留商品比较结果。' } : this.steps.shift() ?? { type: 'text', text: '完成' };
    yield { type: 'block-start', index: 0, blockType: block.type };
    if (block.type === 'tool-call') yield { type: 'tool-call-delta', index: 0, id: block.id, name: block.name, argumentsDelta: block.arguments };
    else yield { type: 'text-delta', index: 0, text: block.text };
    yield { type: 'block-end', index: 0, block };
    yield { type: 'finish', reason: { kind: block.type === 'tool-call' ? 'tool-calls' : 'stop' } };
  }
}
async function harness({ execute = () => ({}), runtime = () => ({ stopped: false, controlAllowed: true }), beforeCreate = () => {}, locale } = {}) {
  const ctx = new Context();
  if (locale) ctx.provide('settings', {describe: () => [{ns: 'locale', value: {preference: locale()}}]});
  await ctx.plugin(LlmRuntime); await ctx.plugin(Sessions); await ctx.plugin(Projections); await ctx.plugin(TokenMeter);
  await ctx.plugin(SystemPrompt); await ctx.plugin(Tools, { mode: 'native' });
  await ctx.plugin(Agents); await ctx.plugin(Loop, { agents: [] });
  const names = new Set([...BASE_TOOL_NAMES, ...Object.values(TOOL_GROUPS).flatMap(group => group.names), 'fictional_plugin_tool']);
  names.delete('droy_load_tools');
  for (const name of names) {
    const definition = toolDefinitions.find(tool => tool.name === name);
    ctx.tools.register(defineTool({ name, description: definition?.description ?? name, parameters: definition?.parameters ?? {},
      output: { schema: { type: 'json' }, render: (_args, value) => [{ type: 'text', text: JSON.stringify(value) }] },
      async execute(args, exec) { return execute(name, args, exec); } }));
  }
  const groups = installToolGroups(ctx);
  installCompanionIdentity(ctx, { companion: async () => ({ ...companionState, runtime: runtime() }) });
  const provider = new Provider(); ctx.llm.registerAdapter(['fixture', 'summary-fixture'], provider);
  const errors = []; ctx.on('agent/error', event => errors.push(event.error));
  await beforeCreate(ctx);
  const { agent } = await ctx.agents.create({ sessionId: crypto.randomUUID(), agentOptions: { provider: 'fixture', model: 'text' } });
  return { ctx, agent, groups, provider, errors, async run(steps = []) {
    provider.steps.push(...steps);
    agent.followup(createUserMessage({ source: { kind: 'user' }, content: [{ type: 'text', text: '打开虚构应用并继续已授权任务' }] }));
    await agent.whenIdle(); assert.deepEqual(errors, []);
  }, close: () => ctx.fiber.dispose() };
}

test('first real request exposes the core set and opens by name without capability/find/progress turns', async t => {
  const executed = [];
  const f = await harness({ execute: (name, args) => { executed.push({ name, args }); return { dispatched: true }; } });
  t.after(f.close);
  await f.run([call('android_open_app', { appName: '虚构应用' })]);
  const first = f.provider.requests[0];
  assert.deepEqual(new Set(first.tools.map(tool => tool.name)), BASE_TOOL_NAMES);
  assert.ok(!first.tools.some(tool => ['bash', 'android_memory_write', 'android_audio_start'].includes(tool.name)));
  assert.deepEqual(executed, [{ name: 'android_open_app', args: { appName: '虚构应用' } }]);
  assert.ok(JSON.stringify(first.messages).includes('controlAllowed'));
});

test('loading a group exposes its contract next step, survives followup, and keeps other groups hidden', async t => {
  const executed = []; const f = await harness({ execute: name => { executed.push(name); return { checked: true }; } }); t.after(f.close);
  await f.run([call('droy_load_tools', { group: 'memory' }), call('android_process_memory', { operation: 'status' })]);
  assert.ok(f.provider.requests[1].tools.some(tool => tool.name === 'android_memory_scan'));
  assert.ok(!f.provider.requests[1].tools.some(tool => tool.name === 'android_audio_start'));
  assert.match(JSON.stringify(f.provider.requests[1].messages), /不能自动补写、回滚、持续冻结/);
  await f.run([call('android_process_memory', { operation: 'status' })]);
  assert.equal(executed.filter(name => name === 'android_process_memory').length, 2);
  assert.deepEqual(f.groups.groups(f.agent), ['memory']);
});

test('loading audio makes the independent voice settings tool discoverable for the next model step', async t => {
  const executed = []; const f = await harness({ execute: name => { executed.push(name); return {}; } }); t.after(f.close);
  await f.run([call('droy_load_tools', { group: 'audio' }), call('droy_voice', {})]);
  assert.ok(!f.provider.requests[0].tools.some(tool => tool.name === 'droy_voice'));
  assert.ok(f.provider.requests[1].tools.some(tool => tool.name === 'droy_voice'));
  assert.match(JSON.stringify(f.provider.requests[1].messages), /普通朗读音色不会替换实时通话原声/);
  assert.deepEqual(executed, ['droy_voice']);
});

test('every installed tool remains reachable through a named group, including unknown plugins', async t => {
  const f = await harness(); t.after(f.close);
  const full = new Set(f.ctx.tools.schemas().map(tool => tool.name));
  await f.run(Object.keys(TOOL_GROUPS).map(group => call('droy_load_tools', { group })));
  assert.deepEqual(new Set(f.ctx.tools.schemas(f.agent).map(tool => tool.name)), full);
  assert.ok(f.provider.requests.at(-1).tools.some(tool => tool.name === 'fictional_plugin_tool'));
});

test('runtime snapshot reflects revoked controls and model changes without carrying old grants', async t => {
  let state = { stopped: false, controlAllowed: true }; const f = await harness({ runtime: () => state }); t.after(f.close);
  await f.run();
  state = { stopped: true, controlAllowed: false };
  f.agent.options.model = 'vision';
  await f.run();
  const messages = f.provider.requests.at(-1).messages;
  const current = messages.findLast(message => message.source?.kind === 'runtime-context');
  const body = JSON.stringify(current);
  assert.match(body, /controlAllowed\\":false/); assert.match(body, /supported/);
  assert.match(runtimeText({}), /"controlAllowed":"unknown"/);
});

test('known optional instructions follow loaded groups without filtering user or unknown policies', async t => {
  const f = await harness({ beforeCreate: ctx => {
    for (const name of ['team:policy', 'tool:bash', 'plan:policy', 'fictional:user-policy']) {
      ctx.systemPrompt.section({ name, order: 100, text: `policy:${name}` });
    }
  } }); t.after(f.close);
  await f.run();
  let text = JSON.stringify(f.provider.requests.at(-1).messages);
  assert.ok(!text.includes('policy:tool:bash') && !text.includes('policy:team:policy'));
  assert.match(text, /policy:plan:policy/); assert.match(text, /policy:fictional:user-policy/);
  await f.run([call('droy_load_tools', { group: 'agents' }), call('droy_load_tools', { group: 'workspace' })]);
  text = JSON.stringify(f.provider.requests.at(-1).messages);
  assert.match(text, /policy:tool:bash/); assert.match(text, /policy:team:policy/);
});

const page = id => ({ status: 'observed', packageName: 'fictional.shop', snapshotId: id, capturedAt: id,
  nodes: [{ nodeId: id + ':1', text: '甲面包 价格10元', description: '店甲', className: 'android.widget.TextView', resourceId: 'fictional:id/card',
    bounds: { left: 0, top: 0, right: 100, bottom: 100 }, clickable: true, enabled: false, checked: false, supportedActions: ['click'] }],
  text: '甲面包 价格10元\n店甲', truncated: false });

test('Android observations and unknown outcomes remain unchanged until native DSH compaction runs', async t => {
  let n = 0;
  const f = await harness({ execute: () => ({ dispatched: true, outcomeUnknown: true, file: 'saved-local.png',
    recipient: '虚构收件人', afterObservation: page(String(++n)) }) }); t.after(f.close);
  await f.run([call('android_observe'), call('android_observe')]);
  const events = f.agent.session.snapshotEvents();
  const results = events.filter(event => event.type === 'tool/result');
  assert.equal(results.length, 2);
  const messages = f.agent.session.deriveMessages().filter(message => message.role === 'tool');
  for (let index = 0; index < results.length; index++) {
    assert.deepEqual(messages[index].content, results[index].data.message.content);
    const value = JSON.parse(messages[index].content[0].text);
    assert.equal(value.afterObservation.nodes[0].nodeId, `${index + 1}:1`);
    assert.equal(value.outcomeUnknown, true);
  }
  assert.ok(!events.some(event => event.type.startsWith('compaction/')));
  const { agent } = await f.ctx.agents.create({ sessionId: crypto.randomUUID(), seed: events,
    agentOptions: { provider: 'fixture', model: 'vision' } });
  agent.followup(createUserMessage({ source: { kind: 'user' }, content: [{ type: 'text', text: '继续，先核实未知发送结果' }] }));
  await agent.whenIdle(); assert.deepEqual(f.errors, []);
  const restored = f.provider.requests.at(-1).messages.filter(message => message.role === 'tool');
  assert.deepEqual(restored.map(message => message.content), messages.map(message => message.content));
});

test('child discovery can expand beyond its parent without lifting inherited tool restrictions', async t => {
  const executed = [];
  const f = await harness({ execute: name => { executed.push(name); return {}; } }); t.after(f.close);
  await f.run([call('droy_load_tools', { group: 'agents' })]);
  f.agent.ctx.tools.restrict({ deny: ['android_audio_start'] });
  const { agent: child } = await f.agent.ctx.agents.create({ sessionId: crypto.randomUUID(), parentAgent: f.agent,
    agentOptions: { provider: 'fixture', model: 'text' } });
  bindScopeParent(child, f.agent);
  f.provider.steps.push(call('droy_load_tools', { group: 'audio' }), call('android_audio_status', {}));
  child.followup(createUserMessage({ source: { kind: 'user' }, content: [{ type: 'text', text: '查询录音状态' }] }));
  await child.whenIdle(); assert.deepEqual(f.errors, []);
  const request = f.provider.requests.at(-1);
  assert.ok(request.tools.some(tool => tool.name === 'android_audio_status'));
  assert.ok(!request.tools.some(tool => tool.name === 'android_audio_start'), 'parent authorization restriction still applies');
  assert.deepEqual(executed, ['android_audio_status']);
  await f.run();
  assert.ok(!f.provider.requests.at(-1).tools.some(tool => tool.name === 'android_audio_status'), 'child expansion does not expand the parent prompt');
});

test('agent-owned response tools remain available without expanding unrelated plugin tools', async t => {
  const f = await harness(); t.after(f.close);
  f.agent.ctx.tools.register(defineTool({ name: 'fictional_agent_result', description: 'Required response contract', parameters: {},
    output: { schema: { type: 'json' }, render: () => [] }, execute: async () => ({}) }));
  await f.run();
  const names = f.provider.requests.at(-1).tools.map(tool => tool.name);
  assert.ok(names.includes('fictional_agent_result'));
  assert.ok(!names.includes('fictional_plugin_tool'));
});


test('known scoped collaboration tools use the agents group while response machinery remains visible', async t => {
  const f = await harness(); t.after(f.close);
  for (const name of ['spawn_teammate', 'subagent']) f.agent.ctx.tools.register(defineTool({ name,
    description: 'Scoped delegation entry', parameters: {}, output: { schema: { type: 'json' }, render: () => [] }, execute: async () => ({}) }));
  await f.run();
  assert.ok(!f.provider.requests.at(-1).tools.some(tool => ['spawn_teammate', 'subagent'].includes(tool.name)));
  await f.run([call('droy_load_tools', { group: 'agents' })]);
  const names = f.provider.requests.at(-1).tools.map(tool => tool.name);
  assert.ok(names.includes('spawn_teammate') && names.includes('subagent'));
});


test('already loaded extension manuals follow live language changes in the same conversation', async t => {
  let language = 'zh';
  const f = await harness({locale: () => language}); t.after(f.close);
  await f.run([call('droy_load_tools', {group: 'memory'}), call('droy_load_tools', {group: 'audio'})]);
  const Chinese = JSON.stringify(f.provider.requests.at(-1).messages);
  assert.match(Chinese, /不能自动补写、回滚、持续冻结/);
  language = 'en';
  await f.run();
  const request = f.provider.requests.at(-1);
  const latestContext = request.messages.findLast(message => message.source?.kind === 'runtime-context');
  const manuals = latestContext.source.sections.find(section => section.name === 'droy:loaded-tool-instructions');
  assert.match(manuals.text, /Do not automatically write again, roll back, continuously freeze/);
  assert.match(manuals.text, /an ordinary reading voice does not replace realtime call audio/);
  assert.ok(!/\p{Script=Han}/u.test(manuals.text));
  assert.match(request.tools.find(tool => tool.name === 'droy_load_tools').description, /Load an extension tool group/);
  assert.ok(request.messages.some(message => message.source?.kind === 'runtime-context' && JSON.stringify(message).includes('不能自动补写')), 'switching never rewrites older context messages');
  language = 'zh';
  await f.run();
  const latest = f.provider.requests.at(-1).messages.findLast(message => message.source?.kind === 'runtime-context');
  assert.match(JSON.stringify(latest), /不能自动补写、回滚、持续冻结/);
});
