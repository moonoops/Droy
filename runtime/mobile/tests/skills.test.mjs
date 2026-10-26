// Real DSH discovery, agent loop and file/skill tools with a scripted model.
// This verifies the mobile contract, not model selection quality or phone tasks.
import test from 'node:test';
import assert from 'node:assert/strict';
import { cp, mkdir, mkdtemp, readFile, realpath, rename, rm, stat, symlink, writeFile } from 'node:fs/promises';
import { createRequire, registerHooks } from 'node:module';
import { join, resolve } from 'node:path';
import { setTimeout as delay } from 'node:timers/promises';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { runInNewContext } from 'node:vm';
import { readUpstreamProfile, mobileProfilePatches } from '../../../tools/mobile_profile.mjs';
import * as initialize from '../initialize.mjs';

process.env.DROY_LOCALE = 'zh';

const installed = createRequire(new URL('../../dsh/package.json', import.meta.url));
const hooks = registerHooks({ resolve(specifier, context, next) {
  if (specifier.startsWith('@deepseek-ai/')) return { url: pathToFileURL(installed.resolve(specifier)).href, shortCircuit: true };
  return next(specifier, context);
} });
const { Context } = await import('@deepseek-ai/cordis');
const { default: LlmRuntime, LlmAdapter, createUserMessage } = await import('@deepseek-ai/dsh-llm');
const { default: SessionStore } = await import('@deepseek-ai/dsh-session');
const { default: SessionProjections } = await import('@deepseek-ai/dsh-session-projection');
const { default: SystemPrompt } = await import('@deepseek-ai/dsh-system-prompt');
const { default: ToolRuntime } = await import('@deepseek-ai/dsh-tools');
const { default: AgentRegistry } = await import('@deepseek-ai/dsh-agent');
const { default: AgentLoop } = await import('@deepseek-ai/dsh-agent-loop');
const { default: SandboxedFileSystem } = await import('@deepseek-ai/dsh-fs-sandbox');
const { default: SandboxPolicy, setSandboxMode } = await import('@deepseek-ai/dsh-sandbox-policy');
const { default: PlanMode } = await import('@deepseek-ai/dsh-plan-mode');
const { default: WorkingDirectory } = await import('@deepseek-ai/dsh-working-directory');
const { default: SkillRegistry } = await import('@deepseek-ai/dsh-skill');
const filesystemSkills = await import('@deepseek-ai/dsh-skill-filesystem');
const fileTools = await import('@deepseek-ai/dsh-tool-fs');
const skillTool = await import('@deepseek-ai/dsh-tool-skill');
const { installDroySkills } = await import('../skills.mjs');
hooks.deregister();

const upstream = await readUpstreamProfile(fileURLToPath(new URL('../../dsh/node_modules/', import.meta.url)));
const provider = mobileProfilePatches(upstream).flatMap(row => row.insert ?? []).find(row => row.id === 'droy-skills');
const personalName = 'fictional-shop-comparison';
const personalContent = `---\nname: ${personalName}\ndescription: 比较虚构商城中同一规格商品的可见价格。\n---\n仅用于 Droy Android。按商品和规格参数查询，记录可见价格，再比较结果。\n`;

function configValue(value, env) {
  if (Array.isArray(value)) return value.map(item => configValue(item, env));
  if (value && typeof value === 'object') {
    if (value.__jsExpr) return runInNewContext(value.__jsExpr, { process: { env } });
    return Object.fromEntries(Object.entries(value).map(([key, item]) => [key, configValue(item, env)]));
  }
  return value;
}

async function putSkill(directory, name) {
  await mkdir(directory, { recursive: true });
  await writeFile(join(directory, 'SKILL.md'), `---\nname: ${name}\ndescription: Unrelated desktop fixture.\n---\nDesktop-only fixture.\n`);
}

async function fixture(t) {
  // The sandbox already permits OS temporary directories. A repository-local
  // fixture proves that this product-data path is outside its writable roots.
  const root = await mkdtemp(fileURLToPath(new URL('../../../.droy-skills-test-', import.meta.url)));
  t.after(() => rm(root, { recursive: true, force: true }));
  const home = join(root, 'dsh-home'), workspace = join(home, 'workspace'), runtime = join(root, 'runtime-one');
  await mkdir(workspace, { recursive: true });
  await mkdir(join(workspace, '.git'));
  await cp(new URL('../skills/', import.meta.url), join(runtime, 'mobile/skills'), { recursive: true });
  await putSkill(join(workspace, '.dsh/skills/project-fixture'), 'project-fixture');
  await putSkill(join(home, 'skills/user-fixture'), 'user-fixture');
  const userSkills = join(root, 'droy-skills');
  return { root, home, workspace, runtime, userSkills };
}

function tool(id, name, args) {
  return [
    { type: 'block-start', index: 0, blockType: 'tool-call' },
    { type: 'tool-call-delta', index: 0, id, name, argumentsDelta: JSON.stringify(args) },
    { type: 'block-end', index: 0, block: { type: 'tool-call', id, name, arguments: JSON.stringify(args) } },
    { type: 'finish', reason: { kind: 'tool-calls' } },
  ];
}

const finished = [
  { type: 'block-start', index: 0, blockType: 'text' },
  { type: 'text-delta', index: 0, text: '已保存。' },
  { type: 'block-end', index: 0, block: { type: 'text', text: '已保存。' } },
  { type: 'finish', reason: { kind: 'stop' } },
];

async function openRuntime(paths, script = []) {
  const ctx = new Context();
  try {
    await ctx.plugin(LlmRuntime); await ctx.plugin(SessionStore); await ctx.plugin(SessionProjections);
    await ctx.plugin(SystemPrompt); await ctx.plugin(ToolRuntime, { mode: 'native' });
    await ctx.plugin(AgentRegistry); await ctx.plugin(AgentLoop, { agents: [] });
    await ctx.plugin(SandboxPolicy, { mode: 'workspace-write', workspaceRoot: paths.workspace });
    await ctx.plugin(SandboxedFileSystem, { cwd: paths.workspace });
    await ctx.plugin(WorkingDirectory, { defaultDirectory: paths.workspace });
    await ctx.plugin(PlanMode, { section: 'Plan mode: inspect and discuss without writing files.' });
    await ctx.plugin(SkillRegistry);
    // Workspace creation is outside this fixture; the actual Droy initializer
    // creates and publishes the separate personal-skill directory below.
    ctx.provide('workspaceRegistry', { async initializeDefault(directory) { assert.equal(await directory(), paths.workspace); } });
    const previousHome = process.env.DSH_HOME;
    process.env.DSH_HOME = paths.home;
    try { await ctx.plugin(initialize); }
    finally { if (previousHome === undefined) delete process.env.DSH_HOME; else process.env.DSH_HOME = previousHome; }
    const config = configValue(provider.config, { DSH_HOME: paths.home, DROY_RUNTIME_DIR: paths.runtime });
    await ctx.plugin(filesystemSkills, { dshHome: paths.home, agentsHome: join(paths.root, 'agents-home') });
    await ctx.plugin(filesystemSkills, config);
    await ctx.plugin(fileTools, { readImage: false });
    await ctx.plugin(skillTool);
    await installDroySkills(ctx);
    const requests = [], errors = [];
    class ScriptedModel extends LlmAdapter {
      async resolveModel(provider, id) { return { provider, id, name: 'Scripted skill contract fixture', inputModalities: ['text'] }; }
      async *stream(options) {
        requests.push(options);
        const step = script.shift();
        assert.ok(step, 'scripted skill fixture exhausted');
        for (const chunk of typeof step === 'function' ? await step(options) : step) yield chunk;
      }
    }
    ctx.llm.registerAdapter(['skill-fixture'], new ScriptedModel());
    ctx.on('agent/error', event => errors.push(event.error));
    const { agent } = await ctx.agents.create({ sessionId: `skills-${Math.random().toString(16).slice(2)}`,
      agentOptions: { provider: 'skill-fixture', model: 'scripted' } });
    return { ctx, agent, requests, errors, workspace: paths.workspace,
      async run(prompt) {
        agent.followup(createUserMessage({ content: [{ type: 'text', text: prompt }], source: { kind: 'user' } }));
        await agent.whenIdle();
        assert.deepEqual(errors, []);
      },
      async close() { await ctx.fiber.dispose(); },
    };
  } catch (error) { await ctx.fiber.dispose(); throw error; }
}

test('Droy discovers its one bundled skill and publishes a separate persistent directory without scanning default roots', async t => {
  const paths = await fixture(t);
  const runtime = await openRuntime(paths);
  try {
    assert.equal(runtime.ctx.droyInitialization.skillDirectory, resolve(paths.userSkills));
    assert.equal((await stat(paths.userSkills)).isDirectory(), true);
    const catalog = await runtime.ctx.skills.list({ cwd: paths.workspace, scope: runtime.agent });
    assert.deepEqual(catalog.filter(skill => skill.provider === 'filesystem').map(skill => skill.name), ['project-fixture', 'user-fixture']);
    const skills = catalog.filter(skill => skill.provider === 'droy-android');
    assert.deepEqual(skills.map(skill => skill.name), ['droy-skill-creat']);
    assert.equal(skills[0].provider, 'droy-android');
    assert.equal(skills[0].invocation.modelInvocable, true);
    assert.equal(skills[0].invocation.userInvocable, true);
    assert.equal((await runtime.ctx.skills.get('droy-skill-creat', { cwd: paths.workspace })).metadata.environment, 'droy-android');
  } finally { await runtime.close(); }
});

async function awaitSkill(runtime, name, text) {
  const deadline = Date.now() + 3000;
  while (Date.now() < deadline) {
    const skill = await runtime.ctx.skills.get(name, { cwd: runtime.workspace, scope: runtime.agent });
    if (skill?.content.includes(text)) return skill;
    await delay(25);
  }
  assert.fail(`Skill watcher did not refresh ${name}`);
}

test('plain-language save and edit turns load the creator and save personal skills under workspace-write; runtime replacement preserves them', async t => {
  const paths = await fixture(t);
  const path = join(paths.userSkills, personalName, 'SKILL.md');
  const runtime = await openRuntime(paths, [
    request => {
      assert.ok(request.tools.some(tool => tool.name === 'skill'));
      assert.match(JSON.stringify(request.messages), /droy-skill-creat/);
      return tool('load-creator', 'skill', { name: 'droy-skill-creat' });
    },
    request => {
      assert.match(JSON.stringify(request.messages.filter(message => message.role === 'tool')), /droySkillsDirectory/);
      return tool('save-personal', 'droy_skill_save', { name: personalName, content: personalContent });
    },
    async () => {
      await awaitSkill(runtime, personalName, '记录可见价格');
      return tool('load-personal', 'skill', { name: personalName });
    },
    request => {
      assert.match(JSON.stringify(request.messages.filter(message => message.role === 'tool')), /记录可见价格/);
      return finished;
    },
    tool('read-before-edit', 'read', { file_path: path }),
    tool('edit-personal', 'droy_skill_save', { name: personalName, content: personalContent.replace('记录可见价格', '记录可见价格和优惠条件') }),
    async () => {
      await awaitSkill(runtime, personalName, '记录可见价格和优惠条件');
      return tool('load-updated', 'skill', { name: personalName });
    },
    request => {
      assert.match(JSON.stringify(request.messages.filter(message => message.role === 'tool')), /记录可见价格和优惠条件/);
      return finished;
    },
  ]);
  try {
    const originalPolicy = runtime.ctx.sandboxPolicy.resolve({ session: runtime.agent.session });
    const ordinaryWrite = runtime.ctx.tools.get('write');
    const execution = { agent: runtime.agent, callId: 'ordinary-write', signal: new AbortController().signal };
    await assert.rejects(ordinaryWrite.execute({ file_path: path, content: personalContent }, execution), { code: 'FS_SANDBOX_DENIED' });
    await runtime.run('记住刚才比较虚构商城价格的做法，下次照这样做。');
    assert.equal(await readFile(path, 'utf8'), personalContent);
    await runtime.run('修改这个比价技能，记录价格时也记录优惠条件。');
    assert.equal(await readFile(path, 'utf8'), personalContent.replace('记录可见价格', '记录可见价格和优惠条件'));
    assert.deepEqual(runtime.ctx.sandboxPolicy.resolve({ session: runtime.agent.session }), originalPolicy);
    await assert.rejects(ordinaryWrite.execute({ file_path: join(paths.root, 'unrelated.txt'), content: 'unrelated' }, execution), { code: 'FS_SANDBOX_DENIED' });
    assert.deepEqual((await runtime.ctx.skills.list({ cwd: paths.workspace })).filter(skill => skill.provider === 'droy-android').map(skill => skill.name), ['droy-skill-creat', personalName]);
  } finally { await runtime.close(); }
  const nextRuntime = join(paths.root, 'runtime-two');
  await rename(paths.runtime, nextRuntime);
  const restarted = await openRuntime({ ...paths, runtime: nextRuntime });
  try {
    const personal = await restarted.ctx.skills.get(personalName, { cwd: paths.workspace });
    assert.equal(personal.path, await realpath(path));
    assert.match(personal.content, /记录可见价格和优惠条件/);
    const builtin = await restarted.ctx.skills.get('droy-skill-creat', { cwd: paths.workspace });
    assert.ok(builtin.path.startsWith(await realpath(nextRuntime)));
  } finally { await restarted.close(); }
});

test('personal skill saving respects read-only, plan mode and cancellation', async t => {
  const paths = await fixture(t), runtime = await openRuntime(paths);
  try {
    const save = runtime.ctx.tools.get('droy_skill_save');
    const execution = { agent: runtime.agent, callId: 'save-policy', signal: new AbortController().signal };
    const args = { name: personalName, content: personalContent };
    setSandboxMode(runtime.agent.session, 'read-only');
    await assert.rejects(save.execute(args, execution), /只读或计划模式/);
    setSandboxMode(runtime.agent.session, 'workspace-write');
    runtime.ctx.planMode.set(runtime.agent, true);
    await assert.rejects(save.execute(args, execution), /只读或计划模式/);
    runtime.ctx.planMode.set(runtime.agent, false);
    const cancelled = new AbortController(); cancelled.abort();
    await assert.rejects(save.execute(args, { ...execution, signal: cancelled.signal }), { name: 'AbortError' });
    await assert.rejects(stat(join(paths.userSkills, personalName, 'SKILL.md')), { code: 'ENOENT' });
  } finally { await runtime.close(); }
});

test('personal skill saving rejects traversal, mismatched frontmatter, builtin replacement and symlink escape', async t => {
  const paths = await fixture(t), runtime = await openRuntime(paths);
  try {
    const save = runtime.ctx.tools.get('droy_skill_save');
    const execution = { agent: runtime.agent, callId: 'save-boundary', signal: new AbortController().signal };
    await assert.rejects(save.execute({ name: '../escaped', content: personalContent }, execution), /技能名/);
    await assert.rejects(save.execute({ name: 'different-name', content: personalContent }, execution), /name 必须/);
    await assert.rejects(save.execute({ name: 'droy-skill-creat', content: personalContent }, execution), /不能覆盖内置技能/);
    await assert.rejects(save.execute({ name: personalName, content: personalContent.replace(`name: ${personalName}`, 'name: [invalid') }, execution), /YAML frontmatter 无效/);
    const outside = join(paths.root, 'outside');
    await mkdir(outside);
    await symlink(outside, join(paths.userSkills, personalName));
    await assert.rejects(save.execute({ name: personalName, content: personalContent }, execution), /超出 Droy 个人技能目录/);
    await assert.rejects(stat(join(outside, 'SKILL.md')), { code: 'ENOENT' });
  } finally { await runtime.close(); }
});
