import test from 'node:test';
import assert from 'node:assert/strict';
import { createRequire } from 'node:module';
import { mkdtemp, mkdir, readFile, writeFile, rm, symlink, realpath } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { createExecutionBoundary, createExecutionProviders, IsolatedStdioTransport } from '../dsh-plugin.mjs';
import { patchExecutionMcp } from '../../privacy/upstream-patch.mjs';

const require = createRequire(new URL('../../dsh/package.json', import.meta.url));
const load = name => import(pathToFileURL(require.resolve(name)).href);
const modules = fileURLToPath(new URL('../../dsh/node_modules/', import.meta.url));
const spec = argv => ({ argv, cwd: '/', env: {}, graceMs: 100,
  stdio: { stdin: 'ignore', stdout: { maxBytes: 8192 }, stderr: { maxBytes: 4096 } } });

async function fixture(t, { active = true, rpcAlias = false } = {}) {
  const [{ Context }, { default: LocalSubprocessRuntime }, { default: LocalFileSystem }] = await Promise.all([
    load('@deepseek-ai/cordis'), load('@deepseek-ai/dsh-subprocess-local'), load('@deepseek-ai/dsh-fs-local'),
  ]);
  const root = await realpath(await mkdtemp(join(tmpdir(), 'droy-execution-adapter-')));
  const workspace = join(root, 'workspace'); await mkdir(workspace);
  await symlink(modules, join(root, 'node_modules'));
  const rpcPath = join(root, 'fs-rpc.mjs');
  await writeFile(rpcPath, await readFile(new URL('../fs-rpc.mjs', import.meta.url)));
  const transport = new Context(); await transport.plugin(LocalSubprocessRuntime);
  const ctx = new Context();
  let activeFlag = active, ready = true;
  const sessions = new Map([['clean', 'known-clean'], ['private', 'protected'], ['other', 'protected']]);
  const calls = [];
  const host = { status: async () => ({ ready }), policy: () => ({ workspaceRoot: workspace, readOnlyRoots: [modules], privateRoots: [join(root, 'private')] }),
    executor: {
      spawn(input) { calls.push(input); return transport.subprocess.spawn({ ...input, cwd: workspace,
        argv: input.argv.map((arg, index) => index === 0 && arg === '/system/bin/sh' ? '/bin/sh' : arg) }); },
    } };
  const privacy = { status: async () => ({ active: activeFlag, ready }),
    async authorizeExecution(agent) {
      const state = sessions.get(agent?.session?.id);
      if (!state) throw new Error('PRIVACY_SESSION_UNKNOWN');
      return { state };
    } };
  ctx.provide('droyPrivacy', privacy);
  let providers;
  const boundary = createExecutionBoundary({ ctx, host, rpcPath: rpcAlias ? join(root, 'legacy-app', 'fs-rpc.mjs') : rpcPath, providersInstalled: () => !!providers
    && ctx.get('subprocess') instanceof providers.DroySubprocess && ctx.get('fs') instanceof providers.DroyFileSystem });
  providers = createExecutionProviders({ LocalSubprocessRuntime, LocalFileSystem, host, boundary });
  await ctx.plugin(providers.DroySubprocess);
  await ctx.plugin(providers.DroyFileSystem, { cwd: workspace });
  boundary.install();
  const run = (sessionId, task, name = 'bash') => boundary.around({ name, agent: { session: { id: sessionId } }, signal: new AbortController().signal }, task);
  t.after(async () => { await boundary.quiesceLegacy(); await ctx.fiber.dispose(); await transport.fiber.dispose(); await rm(root, { recursive: true, force: true }); });
  return { root, workspace, ctx, host, boundary, providers, run, calls, sessions, setActive: value => { activeFlag = value; }, setReady: value => { ready = value; } };
}

test('inactive subprocess keeps upstream behavior; active requests use the host executor with scrubbed env and session workspace', async t => {
  const f = await fixture(t, { active: false });
  await f.run('legacy', async () => {
    const handle = f.ctx.subprocess.spawn({ ...spec([process.execPath, '-e', 'process.stdout.write("plain")']), cwd: f.workspace });
    assert.equal(typeof handle.done.then, 'function');
    assert.equal((await handle.done).exitCode, 0);
    assert.equal(handle.collected.stdout.readFrom(0).text, 'plain');
  });
  assert.equal(f.calls.length, 0);
  f.setActive(true);
  await f.run('private', async () => {
    const handle = f.ctx.subprocess.spawn({ ...spec([process.execPath, '-e', 'process.stdout.write("isolated")']),
      env: { DSH_HOME: 'synthetic', MODEL_API_KEY: 'synthetic', ROOT_CAPABILITY: 'synthetic', NODE_OPTIONS: 'synthetic', LANG: 'C' } });
    assert.equal((await handle.done).exitCode, 0);
    assert.equal(handle.collected.stdout.readFrom(0).text, 'isolated');
  });
  assert.match(f.calls[0].workspaceId, /^[0-9a-f]{64}$/);
  assert.deepEqual(f.calls[0].env, { LANG: 'C' });
  f.setReady(false);
  await f.run('clean', async () => {
    const handle = f.ctx.subprocess.spawn(spec([process.execPath, '-e', 'process.exit(0)']));
    await assert.rejects(handle.done, /ISOLATION_UNAVAILABLE/);
  });
  assert.equal(f.calls.length, 1);
});

test('restricted companion tools bypass legacy Root readiness without relaxing work or subprocess policy', async t => {
  const f = await fixture(t);
  f.setReady(false);
  const agent = { session: { id: 'companion-session' } };
  f.ctx.provide('droyCompanions', {
    ownsSession: id => id === agent.session.id,
    isRestrictedAgent: candidate => candidate === agent,
  });
  const exec = { name: 'companion_reply', agent, signal: new AbortController().signal };
  assert.equal(await f.boundary.around(exec, () => 'scoped reply'), 'scoped reply');
  // An owned identifier alone, or the same tool name in work, is insufficient.
  await assert.rejects(f.run(agent.session.id, () => assert.fail('unrestricted agent ran'), exec.name), /PRIVACY_SESSION_UNKNOWN/);
  await assert.rejects(f.run('clean', () => assert.fail('work tool ran'), exec.name), /EXECUTION_TOOL_NOT_REVIEWED/);
  await f.boundary.around(exec, async () => {
    const handle = f.ctx.subprocess.spawn(spec([process.execPath, '-e', 'process.exit(0)']));
    await assert.rejects(handle.done, /PRIVACY_SESSION_UNKNOWN/);
  });
  assert.equal(f.calls.length, 0);
  await f.run('clean', async () => {
    const handle = f.ctx.subprocess.spawn(spec([process.execPath, '-e', 'process.exit(0)']));
    await assert.rejects(handle.done, /EXECUTION_ISOLATION_UNAVAILABLE/);
  });
  const abort = new AbortController(); abort.abort(new Error('cancelled companion'));
  await assert.rejects(f.boundary.around({ ...exec, signal: abort.signal }, () => assert.fail('cancelled tool ran')), /cancelled companion/);
});

test('namespace file RPC preserves real upstream text/version/edit behavior and binds opaque targets to a session', async t => {
  const f = await fixture(t);
  await writeFile(join(f.workspace, 'synthetic.txt'), '合成记录一');
  let target;
  await f.run('private', async () => {
    target = await f.ctx.fs.resolve('synthetic.txt');
    assert.equal(await f.ctx.fs.readText(target), '合成记录一');
    const metadata = await f.ctx.fs.stat(target);
    const edited = await f.ctx.fs.editText(target, { oldString: '一', newString: '二', replaceAll: false }, { version: metadata.version });
    assert.equal(edited.after, '合成记录二');
    await assert.rejects(f.ctx.fs.editText(target, { oldString: '二', newString: '三', replaceAll: false }, { version: metadata.version }), /FS_STALE_VERSION/);
    const chunks = []; for await (const text of await f.ctx.fs.streamText(target)) chunks.push(text);
    assert.equal(chunks.join(''), '合成记录二');
    assert.equal(f.ctx.fs.processPath(target), join(f.workspace, 'synthetic.txt'));
    await assert.rejects(f.ctx.fs.resolve(join(f.root, 'private', 'secret')), /PRIVATE_PATH/);
  }, 'read');
  await f.run('other', () => assert.rejects(f.ctx.fs.readText(target), /TARGET_SCOPE/), 'read');
  await f.run('private', () => assert.rejects(f.ctx.fs.readText({ ...target }), /TARGET_SCOPE/), 'read');
  assert.ok(f.calls.every(call => /^[0-9a-f]{64}$/.test(call.workspaceId)));
  assert.ok(f.calls.every(call => call.argv[1].endsWith('fs-rpc.mjs')));
});

test('both exact workspace roots remain available without admitting their private parent or sibling', async t => {
  const f = await fixture(t);
  const defaultRoot = join(f.root, 'dsh-home', 'workspace');
  f.host.policy = () => ({ workspaceRoot: defaultRoot, workspaceRoots: [defaultRoot, f.workspace],
    readOnlyRoots: [modules], privateRoots: [join(f.root, 'dsh-home')] });
  assert.equal(f.boundary.checkPath(join(defaultRoot, 'project', 'file.txt')), join(defaultRoot, 'project', 'file.txt'));
  assert.equal(f.boundary.checkPath(join(f.workspace, 'project.txt')), join(f.workspace, 'project.txt'));
  assert.throws(() => f.boundary.checkPath(join(defaultRoot, '..', 'sessions', 'private.json')), /PRIVATE_PATH/);
  assert.throws(() => f.boundary.checkPath(join(f.root, 'dsh-home', 'workspace-other', 'file')), /PRIVATE_PATH/);
});

test('host-declared aliases map bootstrap and workspace paths without admitting private or sibling roots', async t => {
  const f = await fixture(t, { rpcAlias: true });
  const alias = join(f.root, 'legacy-app');
  f.host.policy = () => ({ workspaceRoot: f.workspace, readOnlyRoots: [join(f.root, 'runtime')],
    privateRoots: [join(f.root, 'private')], pathAliases: [{ from: alias, to: f.root }] });
  await writeFile(join(f.workspace, 'synthetic.txt'), 'SYNTHETIC_ALIAS_RECORD');
  await f.run('private', async () => {
    // Activate the real replacement providers before synchronous PTC mapping.
    const target = await f.ctx.fs.resolve(join(alias, 'workspace', 'synthetic.txt'));
    assert.equal(await f.ctx.fs.readText(target), 'SYNTHETIC_ALIAS_RECORD');
    assert.equal(f.calls[0].argv[1], join(f.root, 'fs-rpc.mjs'));
    assert.equal(f.ctx.fs.processPathFromHostPath(join(alias, 'runtime', 'process.js')), join(f.root, 'runtime', 'process.js'));
    assert.equal(f.ctx.fs.processPathFromHostPath(join(alias, 'private', 'secret')), undefined);
    assert.equal(f.ctx.fs.processPathFromHostPath(alias + '-other/runtime/process.js'), undefined);
    await assert.rejects(f.ctx.fs.resolve(join(alias, 'private', 'secret')), /EXECUTION_PRIVATE_PATH/);
    const process = f.ctx.subprocess.spawn({ ...spec([globalThis.process.execPath, '-e', '']), cwd: join(alias, 'workspace') });
    await process.done;
    assert.equal(f.calls.at(-1).cwd, f.workspace);
  }, 'read');
});

test('protection promotion kills a running clean PTC process before returning private data', async t => {
  const f = await fixture(t);
  let handle;
  await f.run('clean', async () => {
    handle = f.ctx.subprocess.spawn({ ...spec([process.execPath, '-e', 'process.stdout.write("ready");setInterval(()=>{},100)']),
      stdio: { stdin: 'ignore', stdout: 'pipe', stderr: { maxBytes: 1024 } } });
    await new Promise(resolve => handle.stdout.once('data', resolve));
    f.sessions.set('clean', 'protected');
    await f.boundary.beforeDisclosure('clean');
    assert.equal(await handle.waitForExit(), true);
  }, 'run_code');
  await handle.done;
  assert.equal(f.calls[0].workspaceId, '');
  await f.run('clean', async () => {
    const next = f.ctx.subprocess.spawn(spec([process.execPath, '-e', 'process.stdout.write("new")']));
    await next.done;
  }, 'run_code');
  assert.match(f.calls[1].workspaceId, /^[0-9a-f]{64}$/);
});

test('active tools require reviewed host policy, including remote MCP and cross-session query tools', async t => {
  const f = await fixture(t);
  for (const name of ['mcp__synthetic__send', 'read_mcp_resource', 'session_query', 'cordis']) {
    await assert.rejects(f.run('private', () => assert.fail('must not execute'), name), /TOOL_NOT_REVIEWED/);
  }
  await assert.rejects(f.run('missing', () => assert.fail('must not execute')), /SESSION_UNKNOWN/);
});

test('MCP stdio transport uses subprocess streams and rejects unreviewed explicit environment after activation', async t => {
  const f = await fixture(t);
  const config = { transport: 'stdio', command: process.execPath, args: ['-e',
    'let s="";process.stdin.setEncoding("utf8");process.stdin.on("data",d=>{s+=d;let n;while((n=s.indexOf("\\n"))>=0){const v=JSON.parse(s.slice(0,n));s=s.slice(n+1);process.stdout.write(JSON.stringify({jsonrpc:"2.0",id:v.id,result:{ok:"合成"}})+"\\n")}})'] };
  const transport = new IsolatedStdioTransport({ ctx: f.ctx, host: f.host, boundary: f.boundary, config });
  t.after(() => transport.close());
  const result = new Promise((resolve, reject) => { transport.onmessage = resolve; transport.onerror = reject; });
  await transport.start(); await transport.send({ jsonrpc: '2.0', id: 1, method: 'synthetic' });
  assert.deepEqual(await result, { jsonrpc: '2.0', id: 1, result: { ok: '合成' } });
  assert.equal(f.calls.length, 1); assert.equal(f.calls[0].workspaceId, '');
  const denied = new IsolatedStdioTransport({ ctx: f.ctx, host: f.host, boundary: f.boundary, config: { ...config, env: { API_KEY: 'synthetic' } } });
  await assert.rejects(denied.start(), /ENV_NOT_REVIEWED/);
  await transport.close();
});

test('MCP build seam is locked and replaces the actual supervisor transport creation once', async () => {
  const source = await readFile(require.resolve('@deepseek-ai/dsh-mcp-client'), 'utf8');
  const patched = patchExecutionMcp(source);
  assert.ok(patched.includes('ctx.get("droyExecution")?.createMcpTransport(config) ?? createTransport(config)'));
  assert.throws(() => patchExecutionMcp(patched), /input changed/);
});

test('actual DSH read tool runs through the around middleware and namespace filesystem provider', async t => {
  const f = await fixture(t);
  const [prompt, tools, filesystemTools] = await Promise.all([
    load('@deepseek-ai/dsh-system-prompt'), load('@deepseek-ai/dsh-tools'), load('@deepseek-ai/dsh-tool-fs'),
  ]);
  await f.ctx.plugin(prompt.default);
  await f.ctx.plugin(tools.default);
  f.ctx.provide('workingDirectory', { ensure: async () => f.workspace });
  await f.ctx.plugin(filesystemTools, { readImage: false });
  await writeFile(join(f.workspace, 'record.txt'), 'SYNTHETIC_LOCAL_RECORD');
  const result = await f.ctx.tools.execute({ callId: 'synthetic-call', name: 'read',
    arguments: { file_path: 'record.txt' }, agent: { session: { id: 'private' } }, signal: new AbortController().signal });
  assert.equal(result.isError, false, JSON.stringify(result));
  assert.match(JSON.stringify(result.content), /SYNTHETIC_LOCAL_RECORD/);
  assert.ok(f.calls.length >= 2);
  assert.ok(f.calls.every(call => /^[0-9a-f]{64}$/.test(call.workspaceId)));
});

test('actual MCP SDK completes initialization and listTools through the replacement stdio transport', async t => {
  const f = await fixture(t);
  const { Client } = await load('@modelcontextprotocol/client');
  const program = `let s='';process.stdin.setEncoding('utf8');process.stdin.on('data',d=>{s+=d;let n;while((n=s.indexOf('\\n'))>=0){const v=JSON.parse(s.slice(0,n));s=s.slice(n+1);if(v.id===undefined)continue;let result=v.method==='initialize'?{protocolVersion:v.params.protocolVersion,capabilities:{tools:{}},serverInfo:{name:'synthetic',version:'1'}}:{tools:[]};process.stdout.write(JSON.stringify({jsonrpc:'2.0',id:v.id,result})+'\\n')}})`;
  const transport = new IsolatedStdioTransport({ ctx: f.ctx, host: f.host, boundary: f.boundary,
    config: { transport: 'stdio', command: process.execPath, args: ['-e', program] } });
  const client = new Client({ name: 'synthetic-test-client', version: '1' }, { capabilities: {} });
  t.after(() => client.close());
  await client.connect(transport);
  assert.deepEqual(await client.listTools(), { tools: [] });
  assert.equal(f.calls.length, 1);
  await client.close();
});

test('actual Node PTC uses the reviewed bootstrap mapping and control channel with session scope', async t => {
  const f = await fixture(t);
  const runtime = await load('@deepseek-ai/dsh-ptc-runtime-node');
  f.ctx.provide('sandbox', { confine: () => { throw new Error('fixture only supports direct policy'); } });
  f.ctx.provide('sandboxPolicy', { defaultMode: 'danger-full-access', resolve: () => ({ mode: 'danger-full-access', workspaceRoot: f.workspace }) });
  await f.ctx.plugin(runtime.default);
  await f.run('private', async () => {
    const request = f.ctx.ptcRuntime.resolve({ program: 'return await tools.synthetic({value: "合成"});',
      bindings: [{ global: 'tools', functions: { synthetic: async args => {
        const current = await f.boundary.scope(); assert.equal(current.state, 'protected'); return args.value;
      } } }], cwd: f.workspace });
    const result = await f.ctx.ptcRuntime.run(request);
    assert.equal(result.value, '合成');
  }, 'run_code');
  assert.ok(f.calls.some(call => call.stdio.control === 'pipe'));
  assert.ok(f.calls.every(call => /^[0-9a-f]{64}$/.test(call.workspaceId)));
});

test('actual clean PTC cannot receive a memory result and write it into its old shared workspace', async t => {
  const f = await fixture(t);
  const runtime = await load('@deepseek-ai/dsh-ptc-runtime-node');
  f.ctx.provide('sandbox', { confine: () => { throw new Error('unexpected confinement'); } });
  f.ctx.provide('sandboxPolicy', { defaultMode: 'danger-full-access', resolve: () => ({ mode: 'danger-full-access', workspaceRoot: f.workspace }) });
  await f.ctx.plugin(runtime.default);
  let disclosed = false;
  await f.run('clean', async () => {
    const request = f.ctx.ptcRuntime.resolve({
      program: `const value = await tools.memory({}); await (await import('node:fs/promises')).writeFile('escape.txt', value); return value;`,
      bindings: [{ global: 'tools', functions: { memory: async () => {
        f.sessions.set('clean', 'protected');
        await f.boundary.beforeDisclosure('clean');
        disclosed = true;
        return 'SYNTHETIC_PRIVATE_CONTENT';
      } } }], cwd: f.workspace });
    const result = await f.ctx.ptcRuntime.run(request);
    assert.notEqual(result.value, 'SYNTHETIC_PRIVATE_CONTENT');
  }, 'run_code');
  assert.equal(disclosed, true);
  await assert.rejects(readFile(join(f.workspace, 'escape.txt')), { code: 'ENOENT' });
});

test('failed process records remain tracked until range quiescence is positively confirmed', async t => {
  const f = await fixture(t);
  let confirmed = false, waits = 0;
  f.host.executor.spawn = () => ({ collected: {}, done: Promise.reject(new Error('synthetic process failure')),
    terminate() {}, async waitForExit() { waits++; if (!confirmed) throw new Error('synthetic range unknown'); return true; } });
  await f.run('clean', async () => {
    const handle = f.ctx.subprocess.spawn(spec(['/synthetic']));
    await assert.rejects(handle.done, /synthetic process failure/);
  });
  await new Promise(resolve => setImmediate(resolve));
  await assert.rejects(f.boundary.close(), /synthetic range unknown/);
  confirmed = true;
  await f.boundary.close();
  const previous = waits;
  await f.boundary.close();
  assert.equal(waits, previous, 'only confirmed records are removed');
});

test('namespace filesystem retains the DSH read-only mutation policy', async t => {
  const f = await fixture(t);
  f.ctx.provide('sandboxPolicy', { defaultMode: 'read-only', resolve: () => ({ mode: 'read-only', workspaceRoot: f.workspace }) });
  await f.run('private', async () => {
    const target = await f.ctx.fs.resolve('denied.txt');
    await assert.rejects(f.ctx.fs.writeText(target, 'synthetic'), /FS_SANDBOX_DENIED/);
  }, 'write');
  await assert.rejects(readFile(join(f.workspace, 'denied.txt')), { code: 'ENOENT' });
});

test('losing a provider dependency cannot leave active tool execution authorized', async t => {
  const f = await fixture(t);
  assert.equal(f.boundary.installed(), true);
  const runtime = f.ctx.registry.get(f.providers.DroyFileSystem);
  for (const fiber of [...runtime.fibers]) await fiber.dispose();
  assert.equal(f.boundary.installed(), false);
  await assert.rejects(f.run('clean', () => assert.fail('tool body must stay blocked')), /REQUIRED_PROVIDER_MISSING/);
  await assert.rejects(f.boundary.scope(), /REQUIRED_PROVIDER_MISSING/);
  assert.equal(f.calls.length, 0);
});
