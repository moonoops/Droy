// Execute the pinned delegation tool with local registries; no provider requests.
import test from 'node:test';
import assert from 'node:assert/strict';
import { fileURLToPath } from 'node:url';
import { readUpstreamProfile, mobileProfilePatches, applyEntryPatches } from '../../../tools/mobile_profile.mjs';
import { apply as installTool } from '../../dsh/node_modules/@deepseek-ai/dsh-tool-subagent/lib/index.js';
import { apply as installSpawn } from '../../dsh/node_modules/@deepseek-ai/dsh-subagent-spawn-in-process/lib/index.js';
import { apply as installFork } from '../../dsh/node_modules/@deepseek-ai/dsh-subagent-fork-in-process/lib/index.js';
import { resolveChildAgentOptions } from '../../dsh/node_modules/@deepseek-ai/dsh-subagent/lib/index.js';

const upstream = await readUpstreamProfile(fileURLToPath(new URL('../../dsh/node_modules/', import.meta.url)));
const mobile = applyEntryPatches(upstream, mobileProfilePatches(upstream), message => assert.fail(message));
const standard = mobile.find(row => row.name === '@deepseek-ai/dsh-agent-preset' && row.config.id === 'standard');
function flatten(rows) { return rows.flatMap(row => [row, ...(row.group ? flatten(row.config) : [])]); }
const tools = flatten(standard.config.plugins).filter(row => row.name === '@deepseek-ai/dsh-tool-subagent' && !row.disabled);
const routes = [{ provider: 'allowed-fixture', model: 'child' }];

function session() {
  return { header: {}, firstLiveSeq: 0, nextSeq: 0, policy: null, eventAt: () => undefined,
    append(type, data) { assert.equal(type, 'subagent/model-selection-policy'); this.policy = structuredClone(data.allowedModels); this.nextSeq++; } };
}
async function harness(t, config, settings, target = session()) {
  const registered = new Map(), providers = new Map(), children = [], preflights = [], cleanups = [];
  const ctx = {
    get(name) { return this[name]; }, on() {}, effect(callback) { const dispose = callback(); cleanups.push(dispose); return dispose; },
    subagentModelSelection: { current: () => structuredClone(settings) },
    sessionProjections: { register() {}, stateOf: value => value.policy },
    tools: { register(tool) { registered.set(tool.name, tool); return () => registered.delete(tool.name); }, get: name => registered.get(name) },
    systemPrompt: { section() {}, context() {}, getSectionOrder: () => 0, getContextOrder: () => 0 },
    llm: { async resolveCallConfig(value, signal) { signal.throwIfAborted(); preflights.push(value); return value; } },
    subagents: {
      registerProvider(provider) { providers.set(provider.name, provider); }, getProvider: name => providers.get(name),
      resolveMaxDepth: () => 4,
      async startActivation(input) { input.signal.throwIfAborted(); children.push({ input, options: resolveChildAgentOptions(input.request.parent, input.request.agentOptions, 1) }); return { childId: 'local-child' }; },
    },
  };
  installSpawn(ctx, { providerName: 'spawn' }); installFork(ctx, { providerName: 'fork' });
  for (const provider of providers.values()) assert.equal(provider.agentRouteDefaults, undefined, 'shipped provider must not override the parent route');
  await installTool(ctx, config, target);
  t.after(() => cleanups.forEach(dispose => dispose()));
  const parent = { options: { provider: 'created-fixture', model: 'old', reasoningEffort: 'low', maxTokens: 1200 },
    session: { requestHeader: () => ({ config: { provider: 'current-fixture', model: 'current', reasoningEffort: 'high' } }) } };
  return { target, children, preflights, tool: registered.get(config.toolName), registered,
    execute(extra = {}, signal = new AbortController().signal) { return registered.get(config.toolName).execute({ description: 'Local regression child', prompt: 'Fictional local work', ...extra }, { agent: parent, signal }); } };
}

test('generated default task type has selectable spawn and inheriting fork without configured child overrides', () => {
  assert.equal(standard.disabled, undefined);
  assert.equal(tools.length, 2);
  assert.equal(tools.find(row => row.config.provider === 'spawn').config.modelSelectionSettings, true);
  for (const row of tools) {
    assert.equal(row.config.agentOptions, undefined);
    assert.equal(row.config.agentPreset, undefined);
    assert.equal(row.config.allowedAgentPresets, undefined);
  }
  assert.ok(mobile.some(row => row.name === '@deepseek-ai/dsh-tool-subagent/model-selection-settings' && !row.disabled));
});

test('disabled selection really inherits the latest conversation route and effort in spawn and fork', async t => {
  for (const row of tools) {
    const f = await harness(t, row.config, { enabled: false, allowedModels: routes });
    assert.equal(f.registered.has('list_subagent_models'), false);
    await f.execute();
    assert.deepEqual(f.children[0].options, { provider: 'current-fixture', model: 'current', reasoningEffort: 'high', maxTokens: 1200, subagentDepth: 1 });
    assert.equal(f.children[0].input.request.agentOptions, undefined);
    await assert.rejects(f.execute({ provider: 'allowed-fixture', model: 'child' }));
    assert.equal(f.children.length, 1, 'disabled explicit choices must never create a child');
  }
});

test('enabled selection enforces exact routes and inherits when the caller does not select another model', async t => {
  const f = await harness(t, tools.find(row => row.config.provider === 'spawn').config, { enabled: true, allowedModels: routes });
  assert.equal(f.registered.has('list_subagent_models'), true);
  await f.execute();
  assert.equal(f.children[0].options.provider, 'current-fixture');
  await f.execute({ provider: 'allowed-fixture', model: 'child' });
  assert.deepEqual(f.children[1].options, { provider: 'allowed-fixture', model: 'child', maxTokens: 1200, subagentDepth: 1 });
  assert.deepEqual(f.preflights, [{ provider: 'allowed-fixture', model: 'child' }]);
  await assert.rejects(f.execute({ provider: 'different-fixture', model: 'child' }), /not allowed/);
  assert.equal(f.children.length, 2);
  const controller = new AbortController(); controller.abort(new Error('cancelled fixture'));
  await assert.rejects(f.execute({}, controller.signal), /cancelled fixture/);
  assert.equal(f.children.length, 2, 'cancellation remains effective before child creation');
});

test('changing the preference only affects new sessions while durable prior policies survive', async t => {
  const config = tools.find(row => row.config.provider === 'spawn').config;
  const oldSession = session();
  await harness(t, config, { enabled: true, allowedModels: routes }, oldSession);
  oldSession.firstLiveSeq = oldSession.nextSeq;
  const resumed = await harness(t, config, { enabled: false, allowedModels: [] }, oldSession);
  await resumed.execute({ provider: 'allowed-fixture', model: 'child' });
  assert.equal(resumed.children[0].options.provider, 'allowed-fixture');
  const next = await harness(t, config, { enabled: false, allowedModels: routes });
  assert.equal(next.registered.has('list_subagent_models'), false);
  await next.execute(); assert.equal(next.children[0].options.provider, 'current-fixture');
  const existingWithoutPolicy = session(); existingWithoutPolicy.firstLiveSeq = 3;
  const unchanged = await harness(t, config, { enabled: true, allowedModels: routes }, existingWithoutPolicy);
  assert.equal(unchanged.registered.has('list_subagent_models'), false, 'enabling does not alter an existing fixed-route conversation');
});

test('upstream configured child options outrank inheritance, so preset contract must remain override-free', async t => {
  const config = { ...tools.find(row => row.config.provider === 'spawn').config,
    agentOptions: { provider: 'configured-fixture', model: 'configured', reasoningEffort: 'low' } };
  const f = await harness(t, config, { enabled: false, allowedModels: routes });
  await f.execute();
  assert.equal(f.children[0].options.provider, 'configured-fixture');
  assert.equal(f.children[0].options.model, 'configured');
  assert.equal(f.children[0].options.reasoningEffort, 'low');
});
