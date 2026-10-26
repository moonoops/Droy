#!/usr/bin/env node
// Explicit device acceptance runner. Execute from the application's own service
// or instrumentation UID/domain; run-as/root-shell success is not qualification.
// All files and programs below are synthetic. No memory activation, recording,
// model call, account, private library or existing task is opened.
// The public-network check requests only the fixed example.com fixture pages.
import assert from 'node:assert/strict';
import { createRequire } from 'node:module';
import { createHash, randomUUID } from 'node:crypto';
import { mkdir, readFile, writeFile, rm, realpath } from 'node:fs/promises';
import { join, isAbsolute, dirname } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { createServer } from 'node:net';
import { request as httpRequest } from 'node:http';
import { lookup } from 'node:dns/promises';

const hash = text => createHash('sha256').update(text).digest('hex');
const wait = ms => new Promise(resolve => setTimeout(resolve, ms));
const STEPS = ['node-private-file-and-environment', 'actual-dsh-file-rpc', 'packaged-bash-and-ripgrep',
  'actual-dsh-ptc-control-roundtrip', 'managed-subtree-cancellation', 'host-loopback-network-denied', 'production-public-proxy-clean-protected-contrast'];
function argumentsOf(argv) {
  const value = {};
  for (let index = 0; index < argv.length; index += 2) {
    if (!['--files-dir', '--native-dir', '--runtime-dir', '--only-step'].includes(argv[index]) || !argv[index + 1]) throw new Error('VERIFY_INVALID_ARGUMENTS');
    value[argv[index].slice(2)] = argv[index + 1];
  }
  const filesDir = value['files-dir'], nativeDir = value['native-dir'], runtimeDir = value['runtime-dir'] ?? join(filesDir ?? '/', 'runtime');
  if (!/^\/data\/user\/\d+\/io\.droyagent\.app\/files$/.test(filesDir ?? '') || !isAbsolute(nativeDir ?? '') || runtimeDir !== join(filesDir, 'runtime')) throw new Error('VERIFY_INVALID_PATHS');
  return { filesDir, nativeDir, runtimeDir, onlyStep: value['only-step'] };
}
function errorCode(error) {
  const code = error?.code;
  return typeof code === 'string' && /^[A-Za-z0-9_:-]{1,100}$/.test(code) ? code : 'VERIFY_FAILED';
}

export async function verifyMemoryExecution({ filesDir, nativeDir, runtimeDir, onlyStep }) {
  if (onlyStep !== undefined && !STEPS.includes(onlyStep)) throw new Error('VERIFY_INVALID_STEP');
  const uid = process.getuid(), domain = (await readFile('/proc/self/attr/current', 'utf8')).replace(/\0/g, '').trim();
  assert.ok(uid % 100000 >= 10000 && uid % 100000 <= 19999, 'Runner must start as the Android application UID');
  assert.equal(Math.floor(uid / 100000), Number(filesDir.split('/')[3]));
  assert.match(domain, /^u:r:untrusted_app(?:_[0-9]+)?:/, 'Runner must use the real application SELinux domain, not run-as or su');
  const installed = createRequire(join(runtimeDir, 'package.json'));
  const load = name => import(pathToFileURL(installed.resolve(name)).href);
  const [{ Context }, { RootExecutor }, execution, { default: LocalSubprocessRuntime }, { default: LocalFileSystem }, { default: NodePtcRuntime }] = await Promise.all([
    load('@deepseek-ai/cordis'), load('@droyagent/execution/launcher'), load('@droyagent/execution'),
    load('@deepseek-ai/dsh-subprocess-local'), load('@deepseek-ai/dsh-fs-sandbox'), load('@deepseek-ai/dsh-ptc-runtime-node'),
  ]);
  const { createExecutionProxy, publicAddress } = await import(pathToFileURL(join(dirname(installed.resolve('@droyagent/execution/package.json')), 'proxy.mjs')).href);
  const sessionId = `verify-${randomUUID()}`, cleanSessionId = `verify-clean-${randomUUID()}`, workspaceId = hash(sessionId);
  const workspace = join(filesDir, 'workspace');
  const privateFixture = join(filesDir, `.droy-execution-verify-${randomUUID()}`);
  const sourceWorkspace = join(filesDir, 'droy-execution/workspaces', workspaceId);
  await mkdir(privateFixture, { mode: 0o700 });
  await mkdir(join(filesDir, 'droy-execution/workspaces'), { recursive: true, mode: 0o700 });
  await mkdir(sourceWorkspace, { mode: 0o700 });
  // Existing empty branches prevent native seeding from either user workspace.
  await mkdir(join(sourceWorkspace, 'startup'), { mode: 0o700 });
  await mkdir(join(sourceWorkspace, 'default'), { mode: 0o700 });
  const canary = join(privateFixture, 'synthetic.txt'); await writeFile(canary, 'DROY_SYNTHETIC_PRIVATE_CANARY', { mode: 0o600 });
  const native = name => join(nativeDir, `libdroy_${name}.so`);
  function assertGuestIdentity(value) {
    const userBase = Math.floor(uid / 100000) * 100000;
    assert.ok(Number.isInteger(value.uid) && value.uid >= userBase + 80000 && value.uid <= userBase + 80007);
    assert.notEqual(value.uid, uid); assert.equal(value.euid, value.uid); assert.equal(value.gid, uid);
  }
  const executor = new RootExecutor({ launcherPath: native('exec'), filesDir, uid, proxyFactory: createExecutionProxy });
  const ctx = new Context(), results = [], launches = [];
  let providers, cleanupConfirmed = false;
  const host = {
    status: async () => ({ ready: true }),
    policy: () => ({ workspaceRoot: workspace, nodePath: native('node'), readOnlyRoots: [runtimeDir, nativeDir], privateRoots: [privateFixture],
      pathAliases: filesDir === '/data/user/0/io.droyagent.app/files'
        ? [{ from: '/data/data/io.droyagent.app', to: '/data/user/0/io.droyagent.app' }] : [] }),
    executor: { spawn(spec) {
      const launch = { control: spec.stdio?.control === 'pipe', command: spec.argv[0] === native('node') ? 'node' : 'other', protected: !!spec.workspaceId };
      launches.push(launch); const handle = executor.spawn(spec);
      handle.ready.then(ready => { launch.guestProxyPort = ready.guestProxyPort; }, () => {}); return handle;
    },
      spawnTerminal: spec => executor.spawnTerminal(spec) },
  };
  // Only synthetic session authority is supplied. The actual DSH providers,
  // RPC implementation, Root launcher and PTC worker are the installed builds.
  ctx.provide('droyPrivacy', { status: async () => ({ active: true, ready: true }),
    authorizeExecution: async agent => {
      if (agent?.session?.id === cleanSessionId) return { state: 'known-clean' };
      assert.equal(agent?.session?.id, sessionId); return { state: 'protected' };
    } });
  const policy = { mode: 'danger-full-access', workspaceRoot: workspace, sessionId };
  ctx.provide('sandboxPolicy', { defaultMode: policy.mode, resolve: () => policy });
  ctx.provide('sandbox', { confine: async () => { throw new Error('VERIFY_UNEXPECTED_SECOND_SANDBOX'); } });
  const boundary = execution.createExecutionBoundary({ ctx, host,
    rpcPath: installed.resolve('@droyagent/execution/fs-rpc'),
    providersInstalled: () => !!providers && ctx.get('subprocess') instanceof providers.DroySubprocess && ctx.get('fs') instanceof providers.DroyFileSystem });
  providers = execution.createExecutionProviders({ LocalSubprocessRuntime, LocalFileSystem, host, boundary });
  ctx.provide('droyExecution', boundary); boundary.install();
  await ctx.plugin(providers.DroySubprocess); await ctx.plugin(providers.DroyFileSystem, { cwd: workspace });
  await ctx.plugin(NodePtcRuntime, { launch: { kind: 'node-script', executable: native('node') }, executionMode: 'per-call', timeoutMs: 15000, maxTimeoutMs: 20000,
    maxOutputBytes: 65536, maxMessageBytes: 262144, maxPendingCalls: 8, maxOldGenerationSizeMb: 64, graceMs: 3000 });
  const run = (name, operation, id = sessionId, timeoutMs = 25000) => boundary.around({ name, agent: { session: { id } }, signal: AbortSignal.timeout(timeoutMs) }, operation);
  const spec = argv => ({ argv, cwd: workspace, env: {}, graceMs: 3000, timeoutMs: 20000,
    stdio: { stdin: 'ignore', stdout: { maxBytes: 65536 }, stderr: { maxBytes: 8192 } } });
  async function collected(argv, additions = {}) {
    const handle = ctx.subprocess.spawn({ ...spec(argv), ...additions });
    const outcome = await handle.done; assert.equal(await handle.waitForExit(), true);
    const output = handle.collected.stdout.readFrom(0), diagnostics = handle.collected.stderr.readFrom(0);
    assert.equal(output.lossy, false); assert.equal(outcome.exitCode, 0,
      `Synthetic workload exit ${outcome.exitCode}; bounded diagnostic ${diagnostics.text.slice(0, 300)}`);
    return output.text;
  }
  async function step(name, operation) {
    if (onlyStep && onlyStep !== name) return;
    const started = Date.now();
    try { const facts = await operation(); results.push({ name, passed: true, elapsedMs: Date.now() - started, ...facts }); }
    catch (error) { results.push({ name, passed: false, elapsedMs: Date.now() - started, code: errorCode(error),
      diagnostic: String(error?.message ?? '').slice(0, 400), ...(error.diagnostics ? { diagnostics: error.diagnostics } : {}) }); }
    process.stdout.write(JSON.stringify({ type: 'verification-step', ...results.at(-1) }) + '\n');
  }
  try {
    await step('node-private-file-and-environment', () => run('bash', async () => {
      const aliasCanary = canary.replace('/data/user/0/', '/data/data/');
      const aliasRuntime = installed.resolve('@droyagent/execution/package.json').replace('/data/user/0/', '/data/data/');
      const program = `const fs=require('fs');let blocked=false,privateAliasDenied=false;try{fs.readFileSync(${JSON.stringify(canary)})}catch{blocked=true};try{fs.readFileSync(${JSON.stringify(aliasCanary)})}catch{privateAliasDenied=true};process.stdout.write(JSON.stringify({uid:process.getuid(),euid:process.geteuid(),gid:process.getgid(),blocked,privateAliasDenied,runtimeAliasVisible:fs.existsSync(${JSON.stringify(aliasRuntime)}),credentialAbsent:process.env.SYNTHETIC_CREDENTIAL===undefined,hostProcHidden:!fs.existsSync('/proc/${process.pid}'),globalProcHidden:!fs.existsSync('/proc/uptime')}));`;
      const value = JSON.parse(await collected([native('node'), '-e', program], { env: { SYNTHETIC_CREDENTIAL: 'synthetic-only' } }));
      assertGuestIdentity(value); assert.equal(value.blocked, true, 'canonical private canary must be denied');
      assert.equal(value.credentialAbsent, true, 'synthetic credential must not enter the workload');
      assert.equal(value.hostProcHidden, true, 'host process must be hidden by private procfs');
      assert.equal(value.globalProcHidden, true, 'global procfs entries must be hidden');
      assert.equal(value.privateAliasDenied, true, 'private canary must remain denied through the Android app alias');
      assert.equal(value.runtimeAliasVisible, true, 'installed execution package must be visible through the Android app alias'); return value;
    }));
    await step('actual-dsh-file-rpc', () => run('write', async () => {
      const target = await ctx.fs.resolve('synthetic.txt');
      const created = await ctx.fs.writeText(target, 'synthetic-initial\n', { kind: 'createIfAbsent' });
      const replaced = await ctx.fs.writeText(target, 'synthetic-alpha\n', { kind: 'replaceIfVersion', version: created.version });
      assert.equal(replaced.after, 'synthetic-alpha\n', 'create result version must authorize the next direct replacement');
      const edited = await ctx.fs.editText(target, { oldString: 'alpha', newString: 'beta', replaceAll: false }, { version: replaced.version });
      assert.equal(edited.after, 'synthetic-beta\n');
      const editedAgain = await ctx.fs.editText(target, { oldString: 'beta', newString: 'gamma', replaceAll: false }, { version: edited.version });
      assert.equal(editedAgain.after, 'synthetic-gamma\n', 'edit result version must authorize the next direct edit');
      await assert.rejects(ctx.fs.editText(target, { oldString: 'gamma', newString: 'stale', replaceAll: false }, { version: edited.version }), /FS_STALE_VERSION/);
      assert.equal(await ctx.fs.readText(target), 'synthetic-gamma\n');
      assert.equal((await ctx.fs.stat(target)).version, editedAgain.version, 'returned version must remain stable across process launches');
      const chunks = []; for await (const chunk of await ctx.fs.streamText(target)) chunks.push(chunk);
      assert.equal(chunks.join(''), 'synthetic-gamma\n');
      await assert.rejects(ctx.fs.resolve(canary), /EXECUTION_PRIVATE_PATH/);
      return { writeReadEditStream: true, createVersionReusable: true, editVersionReusable: true, staleVersionRejected: true, privateTargetRejected: true };
    }));
    await step('packaged-bash-and-ripgrep', () => run('bash', async () => {
      // Host-owned mode 0700 models an existing user's executable project file;
      // a different leased UID must retain execution via the workspace group.
      await writeFile(join(sourceWorkspace, 'startup', 'owner-exec.synthetic'),
        '#!/usr/bin/bash\nprintf "synthetic-executable\\n"\n', { mode: 0o700 });
      const text = await collected([native('bash'), '--noprofile', '--norc', '-c', 'printf "synthetic-search\n" > search.synthetic; "$1" --fixed-strings synthetic-search search.synthetic', 'verification', native('rg')]);
      assert.equal(text, 'synthetic-search\n');
      assert.equal(await collected([native('bash'), '--noprofile', '--norc', '-c', './owner-exec.synthetic']), 'synthetic-executable\n');
      return { bash: true, ripgrep: true, hostOwnedExecutableAcrossLaunches: true };
    }));
    await step('actual-dsh-ptc-control-roundtrip', () => run('run_code', async () => {
      let calls = 0;
      const request = ctx.ptcRuntime.resolve({ program: 'const p = await import("node:process"); const v = await synthetic.echo({value:7}); console.log("DROY_PTC_SYNTHETIC"); return {value:v.value,uid:p.getuid(),euid:p.geteuid(),gid:p.getgid()};',
        cwd: workspace, sandboxPolicy: policy, timeoutMs: 15000, bindings: [{ global: 'synthetic', functions: { echo: async value => { calls++; assert.deepEqual(value, { value: 7 }); return { value: 8 }; } } }] });
      const result = await ctx.ptcRuntime.run(request);
      assert.equal(result.error, undefined, `PTC ${result.error?.kind}: ${result.error?.message?.slice(0, 200)}`);
      assertGuestIdentity(result.value); assert.equal(result.value.value, 8); assert.equal(calls, 1);
      assert.ok(result.logs.some(line => line.includes('DROY_PTC_SYNTHETIC')));
      return { inheritedControl: true, hostBindingRoundtrip: true, resultDecoded: true };
    }));
    await step('managed-subtree-cancellation', () => run('bash', async () => {
      const childProgram = 'const fs=require("fs");let n=0;setInterval(()=>fs.writeFileSync("cancel.synthetic",String(++n)),25)';
      const program = `const fs=require('fs');const child=require('child_process').spawn(process.execPath,['-e',${JSON.stringify(childProgram)}],{detached:true,stdio:'ignore'});child.unref();const t=setInterval(()=>{if(fs.existsSync('cancel.synthetic')){clearInterval(t);process.stdout.write('READY')}} ,25);setInterval(()=>{},1000);`;
      const handle = ctx.subprocess.spawn({ ...spec([native('node'), '-e', program]),
        stdio: { stdin: 'ignore', stdout: 'pipe', stderr: { maxBytes: 8192 } } });
      try {
        await new Promise((resolve, reject) => { let text = ''; const timer = setTimeout(() => reject(new Error('VERIFY_CHILD_READY_TIMEOUT')), 10000);
          handle.stdout.on('data', chunk => { text += chunk; if (text.includes('READY')) { clearTimeout(timer); resolve(); } });
          handle.done.catch(error => { clearTimeout(timer); reject(error); }); });
        handle.terminate(); assert.equal(await handle.waitForExit(AbortSignal.timeout(10000)), true); await handle.done;
        const before = await readFile(join(sourceWorkspace, 'startup', 'cancel.synthetic'), 'utf8'); await wait(200);
        assert.equal(await readFile(join(sourceWorkspace, 'startup', 'cancel.synthetic'), 'utf8'), before);
        return { descendantsConfirmedGone: true, detachedChildStoppedWriting: true };
      } finally { handle.terminate(); await handle.waitForExit(); }
    }));
    await step('host-loopback-network-denied', () => run('bash', async () => {
      let connections = 0;
      const server = createServer(socket => { connections++; socket.destroy(); });
      await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
      try {
        const program = `const net=require('net');let finished=false;const s=net.connect({host:'127.0.0.1',port:${server.address().port}});function end(denied){if(finished)return;finished=true;s.destroy();process.stdout.write(JSON.stringify({denied}));}s.on('connect',()=>end(false));s.on('error',()=>end(true));s.setTimeout(1500,()=>end(true));`;
        const result = JSON.parse(await collected([native('node'), '-e', program]));
        assert.equal(result.denied, true); assert.equal(connections, 0); return { directHostLoopbackDenied: true };
      } finally { await new Promise(resolve => server.close(resolve)); }
    }));
    await step('production-public-proxy-clean-protected-contrast', async () => {
      const deniedProgram = `(async()=>{let denied=false;try{await fetch('https://example.com/',{signal:AbortSignal.timeout(2000)})}catch{denied=true}process.stdout.write(JSON.stringify({denied,proxyAbsent:!process.env.HTTP_PROXY&&!process.env.HTTPS_PROXY}))})().catch(()=>{process.exitCode=1});`;
      const denied = await run('bash', async () => JSON.parse(await collected([native('node'), '-e', deniedProgram])));
      assert.deepEqual(denied, { denied: true, proxyAbsent: true });
      const hostDiagnostics = { node: process.version };
      try {
        const addresses = await lookup('example.com', { all: true, verbatim: true });
        hostDiagnostics.dns = { count: addresses.length, allPublic: addresses.every(row => publicAddress(row.address)), families: [...new Set(addresses.map(row => row.family))] };
      } catch (error) { hostDiagnostics.dns = { code: errorCode(error) }; }
      const probe = await createExecutionProxy({ workspaceId: '', timeoutMs: 5000 });
      try {
        hostDiagnostics.httpProxy = await new Promise(resolve => {
          const request = httpRequest({ hostname: '127.0.0.1', port: probe.port, path: 'http://example.com/', method: 'GET', agent: false,
            headers: { host: 'example.com', connection: 'close' } }, response => {
            let bytes = 0, text = ''; response.on('data', chunk => { bytes += chunk.length; if (bytes > 65536) request.destroy(); else text += chunk; });
            response.on('end', () => resolve({ status: response.statusCode, fixtureMatched: text.includes('Example Domain') }));
            response.on('error', error => resolve({ code: errorCode(error) }));
          });
          request.setTimeout(6000, () => request.destroy(Object.assign(new Error(), { code: 'VERIFY_HOST_PROXY_TIMEOUT' })));
          request.on('error', error => resolve({ code: errorCode(error) })); request.end();
        });
      } finally { await probe.close(); }
      // This clean job exercises the actual product proxy and original DNS.
      // Its program only fetches fixed public pages; it never accesses projects.
      const program = `let stage='environment';(async()=>{
        const proxy=new URL(process.env.HTTP_PROXY);const secureProxy=new URL(process.env.HTTPS_PROXY);
        if(proxy.hostname!=='127.0.0.1'||proxy.port!=='16880'||secureProxy.href!==proxy.href)throw new Error('VERIFY_GUEST_PROXY_ENV');
        const results=[];
        for(const url of ['http://example.com/','https://example.com/']){
          stage=url.startsWith('https:')?'https-fetch':'http-fetch';
          const response=await fetch(url,{redirect:'manual',signal:AbortSignal.timeout(12000)});
          let text='',bytes=0;for await(const chunk of response.body){bytes+=chunk.byteLength;if(bytes>65536)throw new Error('VERIFY_PUBLIC_BODY_LIMIT');text+=Buffer.from(chunk).toString('utf8')}
          if(response.status!==200||!text.includes('Example Domain'))throw new Error('VERIFY_PUBLIC_RESPONSE_'+response.status);
          results.push({url,status:response.status,fixtureMatched:true});
        }
        process.stdout.write(JSON.stringify({guestProxyPort:Number(proxy.port),results}));
      })().catch(error=>{const chain=[];for(let e=error;e&&chain.length<4;e=e.cause){const item={};if(typeof e.code==='string'&&/^[A-Za-z0-9_:-]{1,80}$/.test(e.code))item.code=e.code;if(typeof e.name==='string'&&/^[A-Za-z]{1,40}$/.test(e.name))item.name=e.name;if(Array.isArray(e.errors))item.errors=e.errors.slice(0,4).map(v=>({code:/^[A-Za-z0-9_:-]{1,80}$/.test(v.code||'')?v.code:'unknown'}));chain.push(item)}process.stderr.write(JSON.stringify({stage,node:process.version,envProxyEnabled:process.env.NODE_USE_ENV_PROXY==='1',chain}));process.exitCode=1});`;
      let actual;
      try { actual = await run('bash', async () => JSON.parse(await collected([native('node'), '-e', program], { timeoutMs: 35000 })), cleanSessionId, 40000); }
      catch (error) { error.diagnostics = hostDiagnostics; throw error; }
      assert.equal(actual.guestProxyPort, 16880); assert.equal(actual.results.length, 2);
      assert.ok(launches.some(launch => !launch.protected && launch.guestProxyPort === 16880));
      assert.ok(launches.filter(launch => launch.protected).every(launch => launch.guestProxyPort === 0));
      return { productProxy: true, unmodifiedDnsAndAddressPolicy: true, httpGet: true, httpsConnect: true,
        protectedNetworkDenied: true, guestProxyPort: actual.guestProxyPort, publicResponses: actual.results };
    });
  } finally {
    try { await boundary.close(); await ctx.fiber.dispose(); await executor.close(); cleanupConfirmed = true; }
    catch (error) { results.push({ name: 'cleanup', passed: false, code: errorCode(error) }); }
    if (cleanupConfirmed) { await rm(sourceWorkspace, { recursive: true }); await rm(privateFixture, { recursive: true }); }
  }
  return { schemaVersion: 1, syntheticOnly: true, settingsChanged: false, uid, selinuxDomain: domain,
    ...(onlyStep ? { onlyStep } : {}),
    publicNetworkTargets: ['http://example.com/', 'https://example.com/'],
    runtimeVersion: installed('@droyagent/execution/package.json').version,
    launchCount: launches.length, controlLaunchCount: launches.filter(value => value.control).length,
    cleanupConfirmed, passed: cleanupConfirmed && results.length === (onlyStep ? 1 : STEPS.length) && results.every(result => result.passed), results };
}

if (process.argv[1] && fileURLToPath(import.meta.url) === await realpath(process.argv[1])) {
  try {
    const report = await verifyMemoryExecution(argumentsOf(process.argv.slice(2)));
    process.stdout.write(JSON.stringify({ type: 'verification-report', ...report }) + '\n'); process.exitCode = report.passed ? 0 : 1;
  } catch (error) {
    process.stdout.write(JSON.stringify({ type: 'verification-report', passed: false, code: errorCode(error), diagnostic: String(error?.message ?? '').slice(0, 400) }) + '\n'); process.exitCode = 1;
  }
}
