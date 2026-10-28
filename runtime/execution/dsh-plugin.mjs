import { AsyncLocalStorage } from 'node:async_hooks';
import { createHash } from 'node:crypto';
import { PassThrough, Duplex } from 'node:stream';
import { isAbsolute, relative, resolve, sep } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

export const name = 'droy-execution';
export const inject = ['droyExecutionHost', 'droyPrivacy'];
const SAFE_TOOLS = new Set(['read', 'write', 'edit', 'read_image', 'bash', 'run_code']);
const SAFE_ENV = new Set(['LANG', 'LC_ALL', 'LC_CTYPE', 'TERM', 'COLORTERM', 'TZ']);
const FS_METHODS = ['stat', 'readText', 'readBytes', 'readByteRange', 'listDir', 'writeText', 'editText'];
const MAX_RPC_BYTES = 64 * 1024 * 1024;
function fail(code) { throw Object.assign(new Error(code), { code }); }
function workspaceId(sessionId) { return createHash('sha256').update(sessionId).digest('hex'); }
function inside(root, path) {
  const tail = relative(root, path);
  return tail === '' || (tail !== '..' && !tail.startsWith('..' + sep) && !isAbsolute(tail));
}
function guestEnv(env) {
  return Object.fromEntries(Object.entries(env ?? {}).filter(([key, value]) => SAFE_ENV.has(key)
    && typeof value === 'string' && value.length <= 4096 && !value.includes('\0')));
}
function pipeDuplex() {
  const input = new PassThrough(), output = new PassThrough();
  return { stream: Duplex.from({ readable: output, writable: input }), input, output };
}

/** Preserve the synchronous DSH handle while host authorization is awaited. */
export function deferredHandle(spec, start) {
  spec.signal?.throwIfAborted();
  const controller = new AbortController();
  const signal = spec.signal ? AbortSignal.any([controller.signal, spec.signal]) : controller.signal;
  const stdin = spec.stdio.stdin === 'pipe' ? new PassThrough() : undefined;
  const stdout = spec.stdio.stdout === 'pipe' ? new PassThrough() : undefined;
  const stderr = spec.stdio.stderr === 'pipe' ? new PassThrough() : undefined;
  const control = spec.stdio.control ? pipeDuplex() : undefined;
  for (const stream of [stdin, stdout, stderr, control?.stream]) stream?.on('error', error => controller.abort(error));
  let handle;
  const started = Promise.resolve().then(async () => {
    signal.throwIfAborted();
    handle = await start({ ...spec, signal });
    stdin?.pipe(handle.stdin);
    handle.stdout?.pipe(stdout ?? new PassThrough().resume());
    handle.stderr?.pipe(stderr ?? new PassThrough().resume());
    if (control) {
      if (!handle.control) fail('EXECUTION_CONTROL_UNAVAILABLE');
      control.input.pipe(handle.control); handle.control.pipe(control.output);
    }
    return handle;
  });
  const collected = {};
  for (const stream of ['stdout', 'stderr']) if (typeof spec.stdio[stream] === 'object') {
    collected[stream] = { readFrom: offset => handle?.collected[stream]?.readFrom(offset)
      ?? { text: '', nextOffset: 0, lossy: false } };
  }
  const done = started.then(child => child.done).finally(() => {
    stdin?.end(); stdout?.end(); stderr?.end(); control?.output.end();
  });
  done.catch(() => {});
  return { stdin, stdout, stderr, control: control?.stream, collected, done,
    terminate() { controller.abort(new Error('EXECUTION_TERMINATED')); handle?.terminate(); },
    async waitForExit(waitSignal) {
      if (waitSignal?.aborted) return false;
      let child;
      try { child = await started; } catch (error) { if (controller.signal.aborted && !handle) return true; throw error; }
      return child.waitForExit(waitSignal);
    } };
}

/** Host-owned policy and execution context; it is never exposed as a tool. */
export function createExecutionBoundary({ ctx, host, providersInstalled = () => false, rpcPath = fileURLToPath(new URL('./fs-rpc.mjs', import.meta.url)) }) {
  const local = new AsyncLocalStorage();
  const processes = new Set();
  const revisions = new Map();
  let everActive = false;
  let middlewareInstalled = false;
  function canonicalPath(path, cwd) {
    const policy = host.policy();
    const absolute = resolve(cwd ?? policy.workspaceRoot, path);
    for (const alias of policy.pathAliases ?? []) if (inside(alias.from, absolute)) return resolve(alias.to, relative(alias.from, absolute));
    return absolute;
  }
  async function scope() {
    const privacy = ctx.get('droyPrivacy');
    const state = await privacy.status();
    everActive ||= state.active;
    if (!state.active && everActive) fail('EXECUTION_ACTIVATION_DOWNGRADE');
    if (!state.active) return { active: false, workspaceId: '', state: 'inactive' };
    if (!middlewareInstalled || !providersInstalled()) fail('EXECUTION_REQUIRED_PROVIDER_MISSING');
    const current = local.getStore();
    let record = { state: 'known-clean' };
    if (current) record = await privacy.authorizeExecution(current.agent, current.signal);
    const availability = await host.status();
    if (!availability.ready) fail('EXECUTION_ISOLATION_UNAVAILABLE');
    return { active: true, state: record.state, sessionId: current?.agent?.session?.id,
      revision: revisions.get(current?.agent?.session?.id) ?? 0,
      workspaceId: record.state === 'protected' ? workspaceId(current.agent.session.id) : '' };
  }
  function process(spec, fallback) {
    const current = local.getStore();
    const sessionId = current?.agent?.session?.id;
    const revision = revisions.get(sessionId) ?? 0;
    const record = { sessionId, workspaceId: undefined, active: false, handle: undefined };
    processes.add(record);
    const handle = deferredHandle(spec, async scoped => {
      const state = await scope();
      if (revision !== (revisions.get(sessionId) ?? 0)) fail('EXECUTION_SESSION_CHANGED');
      record.workspaceId = state.workspaceId;
      record.active = state.active;
      scoped.signal.throwIfAborted();
      return state.active ? host.executor.spawn({ ...scoped, cwd: canonicalPath(scoped.cwd ?? host.policy().workspaceRoot),
        workspaceId: state.workspaceId, env: guestEnv(scoped.env) }) : fallback(scoped);
    });
    record.handle = handle;
    handle.done.catch(() => {}).then(async () => {
      if (await handle.waitForExit() === true) processes.delete(record);
    }).catch(() => {});
    return handle;
  }
  async function terminal(spec, fallback) {
    const sessionId = local.getStore()?.agent?.session?.id;
    const revision = revisions.get(sessionId) ?? 0;
    const controller = new AbortController();
    const record = { sessionId, workspaceId: undefined, active: false, handle: undefined };
    let handle;
    let launchAttempted = false;
    const opening = (async () => {
      const state = await scope();
      if (revision !== (revisions.get(sessionId) ?? 0)) fail('EXECUTION_SESSION_CHANGED');
      controller.signal.throwIfAborted();
      record.workspaceId = state.workspaceId;
      record.active = state.active;
      const scoped = { ...spec, signal: spec.signal ? AbortSignal.any([spec.signal, controller.signal]) : controller.signal };
      launchAttempted = true;
      handle = await (state.active ? host.executor.spawnTerminal({ ...scoped, cwd: canonicalPath(scoped.cwd ?? host.policy().workspaceRoot),
        workspaceId: state.workspaceId, env: guestEnv(scoped.env) }) : fallback(scoped));
      return handle;
    })();
    async function terminate() {
      controller.abort(new Error('EXECUTION_TERMINATED'));
      try { await opening; } catch (error) {
        if (!launchAttempted || error.executionQuiescent === true) return;
        throw error;
      }
      await handle.terminate();
    }
    record.handle = { terminate, waitForExit: async () => { await terminate(); return true; } };
    processes.add(record);
    try {
      const opened = await opening;
      opened.done.catch(() => {}).then(async () => {
        await opened.terminate();
        processes.delete(record);
      }).catch(() => {});
      async function check() {
        const current = await scope();
        if (current.workspaceId !== record.workspaceId) fail('EXECUTION_SESSION_CHANGED');
        controller.signal.throwIfAborted();
      }
      return { pid: opened.pid, output: opened.output, done: opened.done,
        write: async data => { await check(); return opened.write(data); },
        resize: async (cols, rows) => { await check(); return opened.resize(cols, rows); },
        inspectForeground: async () => { await check(); return opened.inspectForeground(); },
        inspectActivity: async () => { await check(); return opened.inspectActivity(); },
        signalForeground: async signal => { await check(); return opened.signalForeground(signal); },
        terminate };
    } catch (error) {
      if (!launchAttempted || error.executionQuiescent === true) processes.delete(record);
      throw error;
    }
  }
  async function beforeDisclosure(sessionId) {
    revisions.set(sessionId, (revisions.get(sessionId) ?? 0) + 1);
    const stale = [...processes].filter(record => record.sessionId === sessionId && record.workspaceId !== workspaceId(sessionId));
    for (const record of stale) Promise.resolve(record.handle.terminate()).catch(() => {});
    for (const record of stale) {
      if (await record.handle.waitForExit() !== true) fail('EXECUTION_NOT_QUIESCENT');
      processes.delete(record);
    }
  }
  async function quiesceLegacy() {
    const stale = [...processes].filter(record => !record.active);
    for (const record of stale) Promise.resolve(record.handle.terminate()).catch(() => {});
    for (const record of stale) {
      if (await record.handle.waitForExit() !== true) fail('EXECUTION_NOT_QUIESCENT');
      processes.delete(record);
    }
  }
  async function close() {
    const live = [...processes];
    for (const record of live) Promise.resolve(record.handle.terminate()).catch(() => {});
    for (const record of live) {
      if (await record.handle.waitForExit() !== true) fail('EXECUTION_NOT_QUIESCENT');
      processes.delete(record);
    }
  }
  async function around(exec, next) {
    const companions = ctx.get('droyCompanions');
    if (companions?.ownsSession(exec.agent?.session?.id) === true
      && companions.isRestrictedAgent?.(exec.agent) === true) {
      // The product's scoped registry and monotonic guards have already run.
      // This does not relax scope(): filesystem/process access still uses the
      // existing execution policy, including its Root readiness requirements.
      exec.signal?.throwIfAborted();
      return local.run({ agent: exec.agent, signal: exec.signal, workspaceId: '' }, next);
    }
    const privacy = ctx.get('droyPrivacy');
    const status = await privacy.status();
    if (!status.active) return local.run({ agent: exec.agent, signal: exec.signal, workspaceId: '' }, next);
    if (!middlewareInstalled || !providersInstalled()) fail('EXECUTION_REQUIRED_PROVIDER_MISSING');
    const record = await privacy.authorizeExecution(exec.agent, exec.signal);
    const allowed = SAFE_TOOLS.has(exec.name) || await host.authorizeTool?.(exec, record);
    if (!allowed) fail('EXECUTION_TOOL_NOT_REVIEWED');
    return local.run({ agent: exec.agent, signal: exec.signal,
      workspaceId: record.state === 'protected' ? workspaceId(exec.agent.session.id) : '' }, next);
  }
  function checkTarget(detail, current) {
    if (!detail || detail.workspaceId !== current.workspaceId) fail('EXECUTION_FS_TARGET_SCOPE');
    return detail.target;
  }
  function checkPath(path, cwd) {
    if (typeof path !== 'string' || path.includes('\0')) fail('EXECUTION_INVALID_PATH');
    const policy = host.policy();
    const absolute = canonicalPath(path, cwd);
    if ([policy.workspaceRoot, ...(policy.workspaceRoots ?? [])].some(root => inside(canonicalPath(root), absolute))) return absolute;
    for (const root of policy.privateRoots ?? []) if (inside(canonicalPath(root), absolute)) fail('EXECUTION_PRIVATE_PATH');
    return absolute;
  }
  async function* rpc(request, current, signal) {
    const policy = host.policy();
    const sandboxPolicy = ctx.get('sandboxPolicy')?.resolve() ?? { mode: 'workspace-write', workspaceRoot: policy.workspaceRoot };
    const payload = JSON.stringify({ ...request, policy: { ...sandboxPolicy, workspaceRoot: canonicalPath(sandboxPolicy.workspaceRoot) } });
    if (Buffer.byteLength(payload) > 16 * 1024 * 1024) fail('EXECUTION_FS_REQUEST_TOO_LARGE');
    if (current.revision !== (revisions.get(current.sessionId) ?? 0)) fail('EXECUTION_SESSION_CHANGED');
    const handle = process({ argv: [policy.nodePath ?? globalThis.process.execPath, canonicalPath(rpcPath)],
      cwd: policy.workspaceRoot, workspaceId: current.workspaceId,
      env: {}, signal, graceMs: 1000,
      stdio: { stdin: { data: payload }, stdout: 'pipe', stderr: { maxBytes: 1024 } } }, () => fail('EXECUTION_INACTIVE_FS_RPC'));
    let pending = '', result = false;
    try {
      handle.stdout.setEncoding('utf8');
      for await (const chunk of handle.stdout) {
        pending += chunk.toString('utf8');
        if (Buffer.byteLength(pending) > MAX_RPC_BYTES) fail('EXECUTION_FS_RESPONSE_TOO_LARGE');
        let index;
        while ((index = pending.indexOf('\n')) !== -1) {
          const message = JSON.parse(pending.slice(0, index)); pending = pending.slice(index + 1);
          if (message.kind === 'error') fail(message.code === 'EXECUTION_FS_FAILED' || /^FS_[A-Z_]+$/.test(message.code)
            ? message.code : 'EXECUTION_FS_FAILED');
          if (message.kind === 'result') { if (result) fail('EXECUTION_FS_PROTOCOL'); result = true; }
          else if (message.kind !== 'chunk') fail('EXECUTION_FS_PROTOCOL');
          yield message;
        }
      }
      const outcome = await handle.done;
      if (outcome.exitCode !== 0 || !result || pending) fail('EXECUTION_FS_PROTOCOL');
    } finally { handle.terminate(); await handle.waitForExit(); }
  }
  async function fileCall(method, args, current, signal) {
    let result;
    for await (const message of rpc({ method, args }, current, signal)) {
      if (message.kind !== 'result') fail('EXECUTION_FS_PROTOCOL');
      result = message.value?.$bytes !== undefined ? Uint8Array.from(Buffer.from(message.value.$bytes, 'base64')) : message.value;
    }
    return result;
  }
  function createMcpTransport(config) {
    if (config.transport !== 'stdio') return undefined;
    return new IsolatedStdioTransport({ ctx, host, boundary: { scope }, config });
  }
  return Object.freeze({ scope, around, rpc, fileCall, checkTarget, checkPath, canonicalPath, process, terminal, beforeDisclosure, quiesceLegacy, createMcpTransport, close,
    currentWorkspaceId: () => local.getStore()?.workspaceId ?? '',
    installed: () => middlewareInstalled && providersInstalled(),
    isActive: () => everActive, guestEnv,
    install() {
      ctx.on('tools/execute', (exec, next) => around(exec, next));
      middlewareInstalled = true;
      ctx.effect(() => () => { middlewareInstalled = false; return close(); });
    } });
}

export class IsolatedStdioTransport {
  #ctx; #host; #boundary; #config; #handle; #closed = false;
  constructor({ ctx, host, boundary, config }) { this.#ctx = ctx; this.#host = host; this.#boundary = boundary; this.#config = config; }
  async start() {
    if (this.#handle || this.#closed) fail('EXECUTION_MCP_ALREADY_STARTED');
    const config = this.#config;
    const state = await this.#boundary.scope();
    if (state.active && Object.keys(config.env ?? {}).some(key => !SAFE_ENV.has(key))) fail('EXECUTION_MCP_ENV_NOT_REVIEWED');
    this.#handle = this.#ctx.subprocess.spawn({ argv: [config.command, ...(config.args ?? [])],
      cwd: config.cwd ?? (state.active ? this.#host.policy().workspaceRoot : process.cwd()), env: config.env, graceMs: 1000,
      stdio: { stdin: 'pipe', stdout: 'pipe', stderr: { maxBytes: 4096 } } });
    const handle = this.#handle;
    (async () => {
      let pending = '';
      try {
        handle.stdout.setEncoding('utf8');
        for await (const chunk of handle.stdout) {
          pending += chunk.toString('utf8');
          if (Buffer.byteLength(pending) > 16 * 1024 * 1024) fail('EXECUTION_MCP_MESSAGE_TOO_LARGE');
          let at;
          while ((at = pending.indexOf('\n')) !== -1) {
            const line = pending.slice(0, at); pending = pending.slice(at + 1);
            if (line.trim()) this.onmessage?.(JSON.parse(line));
          }
        }
        const result = await handle.done;
        if (pending || result.exitCode !== 0) fail('EXECUTION_MCP_PROCESS_FAILED');
      } catch (error) { if (!this.#closed) this.onerror?.(error); }
      finally { await this.close(); }
    })().catch(error => this.onerror?.(error));
  }
  async send(message) {
    if (!this.#handle || this.#closed) fail('EXECUTION_MCP_NOT_CONNECTED');
    const data = JSON.stringify(message) + '\n';
    if (Buffer.byteLength(data) > 16 * 1024 * 1024) fail('EXECUTION_MCP_MESSAGE_TOO_LARGE');
    await new Promise((resolve, reject) => this.#handle.stdin.write(data, error => error ? reject(error) : resolve()));
  }
  async close() {
    if (this.#closed) return;
    this.#closed = true;
    this.#handle?.terminate();
    if (this.#handle && !(await this.#handle.waitForExit())) fail('EXECUTION_NOT_QUIESCENT');
    this.onclose?.();
  }
}

export function createExecutionProviders({ LocalSubprocessRuntime, LocalFileSystem, host, boundary }) {
  class DroySubprocess extends LocalSubprocessRuntime {
    spawn(spec) {
      return boundary.process(spec, scoped => super.spawn(scoped));
    }
    async terminalEnvironment(signal) {
      const state = await boundary.scope();
      return state.active ? { platform: 'posix', defaultShell: '/system/bin/sh' } : super.terminalEnvironment(signal);
    }
    async spawnTerminal(spec) {
      return boundary.terminal(spec, scoped => super.spawnTerminal(scoped));
    }
    async resolveExecutable(command, env, signal) {
      const state = await boundary.scope();
      if (!state.active) return super.resolveExecutable(command, env, signal);
      if (typeof command !== 'string' || !command || command.includes('\0') || (!isAbsolute(command) && command.includes('/'))) fail('EXECUTION_INVALID_EXECUTABLE');
      const handle = host.executor.spawn({ argv: ['/system/bin/sh', '-c',
        'p=$(command -v "$1") && test -x "$p" && printf "%s" "$p"', 'droy-lookup', command],
        cwd: host.policy().workspaceRoot, workspaceId: state.workspaceId, env: guestEnv(env), signal, graceMs: 1000,
        stdio: { stdin: 'ignore', stdout: { maxBytes: 8192 }, stderr: { maxBytes: 1024 } } });
      const outcome = await handle.done;
      const found = handle.collected.stdout.readFrom(0);
      if (outcome.exitCode !== 0 || found.lossy || !isAbsolute(found.text)) fail('EXECUTION_EXECUTABLE_NOT_FOUND');
      return found.text;
    }
  }
  const fsTargets = new WeakMap();
  function wrapTarget(target, state) {
      if (!target || typeof target.targetKey !== 'string' || typeof target.displayPath !== 'string') fail('EXECUTION_FS_PROTOCOL');
      const wrapped = Object.freeze({ targetKey: `droy:${state.workspaceId}:${target.targetKey}`, displayPath: target.displayPath });
      fsTargets.set(wrapped, { workspaceId: state.workspaceId, target });
      return wrapped;
  }
  class DroyFileSystem extends LocalFileSystem {
    async resolve(path, opts) {
      const state = await boundary.scope();
      if (!state.active) return super.resolve(path, opts);
      const canonical = boundary.checkPath(path, opts?.cwd);
      return wrapTarget(await boundary.fileCall('resolve', [canonical, opts?.cwd ? { cwd: boundary.canonicalPath(opts.cwd) } : undefined], state, opts?.signal), state);
    }
    processPath(target) {
      const detail = fsTargets.get(target);
      if (!detail) { if (boundary.isActive()) fail('EXECUTION_FS_TARGET_SCOPE'); return super.processPath(target); }
      return boundary.checkTarget(detail, { workspaceId: boundary.currentWorkspaceId() }).targetKey;
    }
    fileUrl(target) { return pathToFileURL(this.processPath(target)).href; }
    contains(parent, child) { return inside(this.processPath(parent), this.processPath(child)); }
    processPathFromHostPath(path) {
      if (!boundary.isActive()) return super.processPathFromHostPath(path);
      const roots = [host.policy().workspaceRoot, ...(host.policy().workspaceRoots ?? []), ...(host.policy().readOnlyRoots ?? [])];
      if (!isAbsolute(path)) return undefined;
      const canonical = boundary.canonicalPath(path);
      return roots.some(root => inside(boundary.canonicalPath(root), canonical)) ? canonical : undefined;
    }
    async lstat(path, opts, signal) {
      const state = await boundary.scope();
      if (!state.active) return super.lstat(path, opts, signal);
      const canonical = boundary.checkPath(path, opts?.cwd);
      return boundary.fileCall('lstat', [canonical, opts?.cwd ? { ...opts, cwd: boundary.canonicalPath(opts.cwd) } : opts], state, signal);
    }
    async watch(target, changed, signal) {
      const state = await boundary.scope();
      if (!state.active) return super.watch(target, changed, signal);
      fail('EXECUTION_FS_WATCH_UNAVAILABLE');
    }
    async streamText(target, signal) {
      const state = await boundary.scope();
      if (!state.active) return super.streamText(target, signal);
      const original = boundary.checkTarget(fsTargets.get(target), state);
      return (async function* () {
        for await (const message of boundary.rpc({ method: 'streamText', args: [original] }, state, signal)) {
          if (message.kind === 'chunk') yield message.text;
        }
      })();
    }
    async call(method, target, ...args) {
      const state = await boundary.scope();
      if (!state.active) return LocalFileSystem.prototype[method].call(this, target, ...args);
      const original = boundary.checkTarget(fsTargets.get(target), state);
      const signalIndex = { stat: 0, readText: 0, readBytes: 0, readByteRange: 1, listDir: 0, writeText: 2, editText: 2 }[method];
      const signal = args[signalIndex];
      args[signalIndex] = undefined;
      const value = await boundary.fileCall(method, [original, ...args], state, signal);
      return method === 'listDir' ? value.map(entry => ({ ...entry, target: wrapTarget(entry.target, state) })) : value;
    }
  }
  for (const method of FS_METHODS) Object.defineProperty(DroyFileSystem.prototype, method, {
    value: function (target, ...args) { return this.call(method, target, ...args); }, configurable: false,
  });
  return { DroySubprocess, DroyFileSystem };
}

export async function apply(ctx) {
  const host = ctx.get('droyExecutionHost');
  const [{ default: LocalSubprocessRuntime }, { default: LocalFileSystem }] = await Promise.all([
    import('@deepseek-ai/dsh-subprocess-local'), import('@deepseek-ai/dsh-fs-sandbox'),
  ]);
  let providers;
  const boundary = createExecutionBoundary({ ctx, host, providersInstalled: () => !!providers
    && ctx.get('subprocess') instanceof providers.DroySubprocess && ctx.get('fs') instanceof providers.DroyFileSystem });
  providers = createExecutionProviders({ LocalSubprocessRuntime, LocalFileSystem, host, boundary });
  ctx.provide('droyExecution', boundary);
  boundary.install();
  await ctx.plugin(providers.DroySubprocess);
  await ctx.plugin(providers.DroyFileSystem, { cwd: process.cwd() });
}
