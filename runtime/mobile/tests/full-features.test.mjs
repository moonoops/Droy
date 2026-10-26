// Real generated mobile profile, durable DSH storage and dispatch. The model
// is scripted, with host flock and publication fixtures in place of Android
// native primitives; no provider API or Android device is exercised here.
import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdir, mkdtemp, rm, writeFile } from 'node:fs/promises';
import { join } from 'node:path';
import { tmpdir } from 'node:os';
import { fileURLToPath } from 'node:url';
import { createServer } from 'node:http';
import { setTimeout as delay } from 'node:timers/promises';
import { registerHooks } from 'node:module';
import { companionState } from './fixtures/companion-state.mjs';

const runtime = fileURLToPath(new URL('../../generated/dsh/', import.meta.url));
const token = 'fictional-full-features-token-1234567890';

async function eventually(read, description, timeout = 8000) {
  const deadline = Date.now() + timeout;
  while (Date.now() < deadline) {
    const result = await read();
    if (result) return result;
    await delay(25);
  }
  assert.fail(`Timed out waiting for ${description}`);
}

test('restored mobile DSH executes schedule, goal, plan and manual-compaction flows', { timeout: 60000 }, async t => {
  const home = await mkdtemp(join(tmpdir(), 'droy-full-features-'));
  const reservation = createServer();
  await new Promise(resolve => reservation.listen(0, '127.0.0.1', resolve));
  const port = reservation.address().port;
  await new Promise(resolve => reservation.close(resolve));
  const nativeBridge = createServer(async (req, res) => {
    assert.equal(req.headers.authorization, `Bearer ${token}`);
    for await (const _ of req) { /* consume the JSON request */ }
    const result = req.url === '/companion' ? companionState
      : req.url === '/notify' ? { pluginActive: true, localEnabled: true, appsEnabled: false, channels: [] }
        : req.url === '/memory' ? { settings: { enabled: false, recallEnabled: true, automaticExtraction: false, privacyEverActivated: false },
          root: { authorized: false, checkedAt: 0 }, execution: { ready: false, reason: 'MEMORY_DISABLED' } }
        : req.url === '/history' ? { config: { activityEnabled: false, locationEnabled: false }, running: false }
        : ['/progress', '/task/end'].includes(req.url) ? {} : undefined;
    res.writeHead(result === undefined ? 404 : 200, { 'content-type': 'application/json' });
    res.end(JSON.stringify(result === undefined
      ? { ok: false, error: { code: 'MOCK_NO_ROUTE', message: 'Native action is outside this fixture' } }
      : { ok: true, result }));
  });
  await new Promise(resolve => nativeBridge.listen(0, '127.0.0.1', resolve));
  const environment = { DSH_HOME: home, DROY_RUNTIME_DIR: runtime, DROY_LOCALE: 'zh', DROY_WEB_TOKEN: token, DROY_BRIDGE_TOKEN: token, DROY_BRIDGE_PORT: String(nativeBridge.address().port), DROY_WEB_PORT: String(port), DSH_TELEMETRY_DISABLED: '1' };
  const previous = Object.fromEntries(Object.keys(environment).map(key => [key, process.env[key]]));
  Object.assign(process.env, environment);
  let shutdown;
  const hostHooks = registerHooks({ resolve(specifier, context, next) {
    if (specifier === '@deepseek-ai/node-addon-system/flock') return { url: new URL('../../dsh/node_modules/@deepseek-ai/node-addon-system/lib/flock.js', import.meta.url).href, shortCircuit: true };
    if (specifier === '@droyagent/mobile/publish') return { url: new URL('./fixtures/host-publish.mjs', import.meta.url).href, shortCircuit: true };
    return next(specifier, context);
  } });
  t.after(async () => {
    try { if (shutdown) await shutdown.shutdown(0); }
    finally {
      hostHooks.deregister();
      nativeBridge.closeAllConnections();
      await new Promise(resolve => nativeBridge.close(resolve));
      for (const [key, value] of Object.entries(previous)) {
        if (value === undefined) delete process.env[key]; else process.env[key] = value;
      }
      await rm(home, { recursive: true, force: true });
    }
  });
  await import(new URL('../../generated/dsh/mobile/bootstrap.mjs', import.meta.url));
  const { runProfile } = await import(new URL('../../generated/dsh/node_modules/@deepseek-ai/dsh/lib/profile-boot.js', import.meta.url));
  const { createLaunchEnvironmentSnapshot } = await import(new URL('../../generated/dsh/node_modules/@deepseek-ai/dsh-launch-environment/lib/index.js', import.meta.url));
  const { mobilePackageManager } = await import(new URL('../../generated/dsh/mobile/package-manager.mjs', import.meta.url));
  const packageManager = mobilePackageManager();
  packageManager.env = { ...packageManager.env, npm_config_offline: 'true', CI: 'true' };
  const boot = () => runProfile({ profile: 'mobile', patchFiles: [], args: ['--no-open'], packageManager, environment: createLaunchEnvironmentSnapshot([{ source: 'process', values: process.env }]) });
  const running = await boot();
  shutdown = running.shutdown;
  const ctx = running.ctx;
  const { LlmAdapter, createUserMessage } = await import(new URL('../../generated/dsh/node_modules/@deepseek-ai/dsh-llm/lib/index.js', import.meta.url));
  const calls = [];
  const failures = [];
  let summaryFinish = 'stop';
  let summaryCancellation;
  ctx.on('agent/error', event => failures.push(String(event.error)));
  class LocalModel extends LlmAdapter {
    async resolveModel(provider, id) { return { provider, id, name: 'Local acceptance fixture', inputModalities: ['text'], context: { contextWindow: 262144 }, defaultMaxTokens: 1024 }; }
    async listModels(provider) { return [await this.resolveModel(provider, 'scripted')]; }
    async *stream(options) {
      assert.equal(options.provider, 'droy-fixture');
      calls.push({ purpose: options.purpose, sessionId: options.sessionId, messages: options.messages, maxTokens: options.maxTokens });
      let finish = 'stop';
      if (options.purpose === 'compaction') {
        finish = summaryFinish;
        summaryFinish = 'stop';
        if (summaryCancellation) {
          summaryCancellation.abort(new Error('Local compaction cancellation'));
          summaryCancellation = undefined;
          options.signal?.throwIfAborted();
        }
      }
      const text = options.purpose === 'compaction' ? '## Current Work\n- Fictional regression history retained.\n## Next Step\n- Complete local acceptance checks.' : 'Local fixture response.';
      yield { type: 'block-start', index: 0, blockType: 'text' };
      yield { type: 'text-delta', index: 0, text };
      yield { type: 'block-end', index: 0, block: { type: 'text', text } };
      yield { type: 'finish', reason: { kind: finish } };
    }
  }
  ctx.llm.registerAdapter(['droy-fixture'], new LocalModel());
  await ctx.agentDefaultModel.saveSelection({ provider: 'droy-fixture', model: 'scripted' });
  const workspace = ctx.workspaceRegistry.list()[0];
  async function newAgent() {
    const created = await ctx.sessionController.create({ workspaceId: workspace.id, agentPreset: 'standard' });
    const resolved = await ctx.sessionController.resolveAgent(created.sessionId);
    if ('error' in resolved) throw resolved.error;
    return resolved.agent;
  }
  async function events(agent) {
    assert.equal(await ctx.sessions.flush(agent.session), true);
    const reader = await ctx.sessionPersistence.open(agent.id, 'read');
    try { return (await reader.read()).events; }
    finally { await reader.close(); }
  }
  async function command(agent, text) {
    const result = await ctx.commands.execute(agent, text, [], new AbortController().signal);
    assert.equal(result?.result.kind, 'success', `${text}: ${result?.result.text}`);
    return result;
  }

  await t.test('schedule CRUD persists updates, stale edits conflict, deletion removes the active task', async () => {
    const agent = await newAgent();
    const count = calls.length;
    const task = await ctx.schedule.create(agent.id, { title: 'Local CRUD fixture', prompt: 'Fictional scheduled instruction', after_seconds: 60 });
    assert.equal((await ctx.schedule.list({ sessionId: agent.id }))[0].id, task.id);
    const edited = await ctx.schedule.update({ sessionId: agent.id, id: task.id, expected: task, title: 'Edited fixture', prompt: 'Updated fictional instruction' });
    assert.equal(edited.updated, true);
    assert.equal(edited.record.title, 'Edited fixture');
    assert.equal(edited.record.scheduledAt, task.scheduledAt);
    const conflict = await ctx.schedule.update({ sessionId: agent.id, id: task.id, expected: task, title: 'Stale edit' });
    assert.equal(conflict.code, 'schedule_conflict');
    assert.deepEqual(await ctx.schedule.delete({ sessionId: agent.id, id: task.id }), { id: task.id, deleted: true });
    assert.deepEqual(await ctx.schedule.list({ sessionId: agent.id }), []);
    assert.equal((await ctx.schedule.catalog()).some(item => item.id === task.id), false);
    assert.equal(calls.length, count, 'management does not start a model turn');
  });

  await t.test('actual timer dispatch reaches its original session, executes a local model turn and records one durable receipt', async () => {
    const agent = await newAgent();
    const prompt = 'Fictional timer dispatch acceptance marker';
    const task = await ctx.schedule.create(agent.id, { title: 'One second fixture', prompt, after_seconds: 1 });
    await eventually(async () => (await ctx.schedule.catalog()).find(item => item.id === task.id)?.lastDelivery, 'schedule delivery receipt');
    await agent.whenIdle();
    const catalog = (await ctx.schedule.catalog()).find(item => item.id === task.id);
    assert.equal(catalog.status, 'inactive');
    assert.equal(catalog.sessionId, agent.id);
    assert.equal(calls.filter(call => call.sessionId === agent.id).length, 1);
    const history = await ctx.schedule.history({ sessionId: agent.id, id: task.id, limit: 10 });
    assert.equal(history.records.length, 1);
    assert.equal(history.records[0].messageId, catalog.lastDelivery.messageId);
    const saved = await events(agent);
    const delivered = saved.filter(event => event.type === 'user/message' && event.data.source?.kind === 'schedule');
    assert.equal(delivered.length, 1);
    assert.match(JSON.stringify(delivered[0].data), new RegExp(prompt));
    assert.ok(saved.some(event => event.type === 'assistant/message'));
    await delay(100);
    assert.equal((await ctx.schedule.history({ sessionId: agent.id, id: task.id, limit: 10 })).records.length, 1);
    await ctx.schedule.delete({ sessionId: agent.id, id: task.id });
  });

  await t.test('goal lifecycle and human commands save revisions in the session', async () => {
    const agent = await newAgent();
    const created = ctx.goals.create(agent, { objective: 'Fictional goal before edit', maxGoalRounds: 1 });
    ctx.goals.pause(agent, created);
    assert.equal(ctx.goals.get(agent).phase, 'paused');
    await command(agent, '/goal edit Fictional edited goal');
    assert.equal(ctx.goals.get(agent).objective, 'Fictional edited goal');
    const shown = await command(agent, '/goal');
    assert.match(shown.result.text, /Fictional edited goal/);
    const resumed = ctx.goals.resume(agent, ctx.goals.get(agent));
    const completed = ctx.goals.complete(agent, resumed);
    assert.equal(completed.phase, 'complete');
    assert.equal(completed.activation, 'disarmed');
    const saved = await events(agent);
    assert.ok(saved.filter(event => event.type === 'goal/change').length >= 5);
    assert.ok(saved.some(event => event.type === 'command/done'));
    await command(agent, '/goal clear');
    assert.equal(ctx.goals.get(agent), undefined);
  });

  await t.test('mobile history automatically compacts through the real isolated preset services', async () => {
    const agent = await newAgent();
    agent.followup(createUserMessage({ content: [{ type: 'text', text: 'Start a fictional history check.' }], source: { kind: 'user' } }));
    await agent.whenIdle();
    assert.ok(agent.ctx.get('tokenMeter'));
    const compaction = ctx.agentPresets.serviceFor(agent, 'compaction');
    assert.ok(compaction);
    assert.equal(compaction.constructor.name, 'BasicCompactionEngine');
    assert.equal(compaction.config.thresholdRatio, 0.8);
    assert.equal(compaction.config.retainRatio, 0.16);
    assert.equal(compaction.config.retainTokens, undefined);
    assert.equal(compaction.config.headroomTokens, 65536);
    assert.equal(compaction.config.maxTokens, 65536);
    assert.equal(compaction.config.compactionRetries, 1);
    assert.equal(compaction.config.auto, true);
    assert.equal(compaction.config.maxOverflowRetries, 1);
    assert.deepEqual(compaction.config.modelPolicies, []);
    assert.equal(ctx.agentPresets.serviceFor(agent, 'toolResultPruner'), undefined);
    assert.equal(ctx.get('compaction'), undefined, 'the installation context cannot see the preset compactor');
    for (let index = 0; index < 6; index++) {
      agent.followup(createUserMessage({ content: [{ type: 'text', text: `Fictional page ${index}. ` + 'Historical product comparison detail. '.repeat(4500) }], source: { kind: 'user' } }));
      await agent.whenIdle();
    }
    agent.followup(createUserMessage({ content: [{ type: 'text', text: 'Retain the latest criteria and continue without phone actions.' }], source: { kind: 'user' } }));
    await agent.whenIdle();
    const requests = calls.filter(call => call.sessionId === agent.id);
    assert.ok(requests.some(call => call.purpose === 'compaction'));
    assert.ok(requests.filter(call => call.purpose === 'compaction').every(call => call.maxTokens === compaction.config.maxTokens));
    assert.ok(requests.filter(call => call.purpose === 'compaction').every(call =>
      JSON.stringify(call.messages.at(-1)).includes('Primary Request and Intent')), 'the native DSH summary template is unchanged');
    assert.ok(requests.at(-1).messages.some(message => message.source?.kind === 'compact-checkpoint'));
    assert.match(JSON.stringify(requests.at(-1).messages), /Fictional page 5/);
    const saved = await events(agent);
    assert.ok(saved.some(event => event.type === 'compaction/start'));
    assert.ok(saved.some(event => event.type === 'compaction/end' && !event.data.error));
  });

  await t.test('short mobile turns use native pressure rules without rewriting observations or calling a summary', async () => {
    const agent = await newAgent();
    for (let index = 0; index < 3; index++) {
      agent.followup(createUserMessage({ content: [{ type: 'text', text: `Fictional short turn ${index}.` }], source: { kind: 'user' } }));
      await agent.whenIdle();
    }
    assert.equal(calls.filter(call => call.sessionId === agent.id && call.purpose === 'compaction').length, 0);
    assert.ok(!(await events(agent)).some(event => event.type === 'compaction/prune'));
  });

  await t.test('native summary truncation and cancellation keep the original history intact', async () => {
    const agent = await newAgent();
    for (let index = 0; index < 2; index++) {
      agent.followup(createUserMessage({ content: [{ type: 'text', text: `Unknown fictional send to recipient Fixture A, attachment fixture-photo.png. ${index}. ` + 'Original history must remain available. '.repeat(500) }], source: { kind: 'user' } }));
      await agent.whenIdle();
    }
    const compaction = ctx.agentPresets.serviceFor(agent, 'compaction');
    const originalNodes = [...agent.session.surface.nodes];
    const originalMessages = agent.session.deriveMessages();
    const before = agent.session.seq;
    summaryFinish = 'max-tokens';
    await assert.rejects(compaction.compactNow(agent, new AbortController().signal));
    assert.deepEqual(agent.session.surface.nodes, originalNodes);
    assert.deepEqual(agent.session.deriveMessages(), originalMessages);
    assert.ok(!agent.session.snapshotEvents().slice(before).some(event => event.type === 'compaction/summary'));
    const cancellation = new AbortController();
    summaryCancellation = cancellation;
    await assert.rejects(compaction.compactNow(agent, cancellation.signal));
    assert.equal(cancellation.signal.aborted, true);
    assert.deepEqual(agent.session.surface.nodes, originalNodes);
    assert.deepEqual(agent.session.deriveMessages(), originalMessages);
    assert.ok(!agent.session.snapshotEvents().slice(before).some(event => event.type === 'compaction/summary'));
  });

  await t.test('plan command commits both transitions and manual compact produces a durable summary', async () => {
    const agent = await newAgent();
    await command(agent, '/plan');
    assert.equal(ctx.sessionProjections.stateOf(agent.session, 'plan').active, true);
    await command(agent, '/plan off');
    assert.equal(ctx.sessionProjections.stateOf(agent.session, 'plan').active, false);
    const blank = await command(agent, '/compact');
    assert.match(blank.result.text, /No compactable history/);
    for (let index = 0; index < 3; index++) {
      agent.followup(createUserMessage({ content: [{ type: 'text', text: `Fictional history ${index}. ` + 'Retained regression detail, generated only for this local compaction fixture. '.repeat(3000) }], source: { kind: 'user' } }));
      await agent.whenIdle();
    }
    const compacted = await command(agent, '/compact');
    assert.match(compacted.result.text, /^Compacted \d+ history items/);
    assert.ok(calls.some(call => call.sessionId === agent.id && call.purpose === 'compaction'));
    const saved = await events(agent);
    assert.deepEqual(saved.filter(event => event.type === 'plan/mode').map(event => event.data.active), [true, false]);
    assert.ok(saved.some(event => event.type === 'compaction/start'));
    assert.ok(saved.some(event => event.type === 'compaction/end'));
    assert.ok(saved.some(event => event.type === 'user/message' && event.data.source?.kind === 'compact-checkpoint'));
  });
  assert.deepEqual(failures, [], 'no agent execution error was swallowed');

  await t.test('a custom mode installs through plugin management and survives a restart with the renamed space', async () => {
    assert.deepEqual((await ctx.agentPresets.list()).map(row => row.id).sort(), ['cordis', 'standard']);
    assert.equal(workspace.title, '默认空间');
    await workspace.setTitle('虚构旅行空间');
    const bundle = join(home, 'fixture-custom-mode');
    await mkdir(bundle);
    await writeFile(join(bundle, 'package.json'), JSON.stringify({
      name: 'fixture-custom-mode', version: '1.0.0', type: 'module',
      dsh: { bundle: { patch: './cordis.patch.yml' } },
    }));
    await writeFile(join(bundle, 'cordis.patch.yml'), JSON.stringify([{ insert: [{
      id: 'preset-fixture-custom', name: '@deepseek-ai/dsh-agent-preset', config: {
        id: 'fixture-custom', name: '虚构旅行规划', plugins: [{
          id: 'persona', name: '@deepseek-ai/dsh-persona',
          config: { prefix: '只使用虚构资料规划旅行。', complete: true },
        }],
      },
    }] }]));
    const installed = await ctx.pluginManager.installBundle(`file:${bundle}`);
    assert.ok(['applied', 'restart-required'].includes(installed.application), JSON.stringify(installed));
    await shutdown.shutdown(0);
    const restarted = await boot();
    shutdown = restarted.shutdown;
    restarted.ctx.llm.registerAdapter(['droy-fixture'], new LocalModel());
    const restoredWorkspace = restarted.ctx.workspaceRegistry.list()[0];
    assert.equal(restoredWorkspace.id, workspace.id);
    assert.equal(restoredWorkspace.path, workspace.path);
    assert.equal(restoredWorkspace.title, '虚构旅行空间', 'startup preserves the user title');
    const presets = await restarted.ctx.agentPresets.list();
    assert.deepEqual(presets.map(row => row.id).sort(), ['cordis', 'fixture-custom', 'standard']);
    assert.equal(presets.find(row => row.id === 'fixture-custom').broken, undefined);
    const created = await restarted.ctx.sessionController.create({ workspaceId: restoredWorkspace.id, agentPreset: 'fixture-custom' });
    const agent = restarted.ctx.agents.get(created.sessionId);
    assert.ok(agent, 'a persisted custom mode remains selectable');
    const assembly = await restarted.ctx.systemPrompt.assemble({ scope: agent, agent });
    const prompt = assembly.sections.map(section => section.text).join('\n\n');
    assert.match(prompt, /只使用虚构资料规划旅行/);
    assert.match(prompt, /默认优先使用本机对应 App/);
    assert.ok(assembly.tools.some(tool => tool.name === 'android_observe'));
    agent.followup(createUserMessage({ content: [{ type: 'text', text: '规划一次虚构旅行。' }], source: { kind: 'user' } }));
    await agent.whenIdle();
    assert.ok(calls.some(call => call.sessionId === agent.id), 'the restored custom mode executes a local model turn');
  });
});
