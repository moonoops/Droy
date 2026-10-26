import { spawn } from 'node:child_process';
import { PassThrough, Writable, Duplex } from 'node:stream';

const MAX_FRAME = 1024 * 1024;
const MAX_BUFFER = 2 * MAX_FRAME;
const SIGNALS = Object.freeze({ SIGHUP: 1, SIGINT: 2, SIGQUIT: 3, SIGTERM: 15, SIGTSTP: 20, SIGCONT: 18, SIGWINCH: 28 });
const LINUX_SIGNALS = 'SIGHUP SIGINT SIGQUIT SIGILL SIGTRAP SIGABRT SIGBUS SIGFPE SIGKILL SIGUSR1 SIGSEGV SIGUSR2 SIGPIPE SIGALRM SIGTERM SIGSTKFLT SIGCHLD SIGCONT SIGSTOP SIGTSTP SIGTTIN SIGTTOU SIGURG SIGXCPU SIGXFSZ SIGVTALRM SIGPROF SIGWINCH SIGIO SIGPWR SIGSYS'.split(' ');
const BOUNDARIES = ['mountNamespace', 'netNamespace', 'utsNamespace', 'uidLease', 'descendantsManaged', 'ipcSyscallsDenied',
  'rootFilesystemPrivate', 'capabilitiesEmpty', 'noNewPrivs', 'inheritedHostFdsClosed', 'seccomp'];
const fail = code => Object.assign(new Error(code), { code });
const deferred = () => { let resolve, reject; const promise = new Promise((yes, no) => { resolve = yes; reject = no; }); return { promise, resolve, reject }; };
function integer(value, min, max) { if (!Number.isInteger(value) || value < min || value > max) throw fail('EXECUTION_INVALID_NUMBER'); return value; }
function u32(value) { const result = Buffer.alloc(4); result.writeUInt32BE(integer(value, 0, 0xffffffff)); return result; }
function string(value) {
  if (typeof value !== 'string' || value.includes('\0')) throw fail('EXECUTION_INVALID_STRING');
  const bytes = Buffer.from(value);
  if (bytes.length > 65536) throw fail('EXECUTION_STRING_LIMIT');
  return Buffer.concat([u32(bytes.length), bytes]);
}
export function encodeFrame(type, body = Buffer.alloc(0)) {
  integer(type, 1, 255);
  if (!Buffer.isBuffer(body)) body = Buffer.from(body);
  if (body.length + 1 > MAX_FRAME) throw fail('EXECUTION_FRAME_LIMIT');
  return Buffer.concat([u32(body.length + 1), Buffer.from([type]), body]);
}
export function encodeStart(config, spec, proxyPort = 0) {
  if (!Array.isArray(spec.argv) || !spec.argv.length || spec.argv.length > 256) throw fail('EXECUTION_INVALID_ARGV');
  if (spec.workspaceId && !/^[a-f0-9]{64}$/.test(spec.workspaceId)) throw fail('EXECUTION_INVALID_WORKSPACE');
  const env = Object.entries(spec.env ?? {}).filter(([, value]) => value !== undefined).map(([name, value]) => {
    if (!/^[A-Za-z_][A-Za-z0-9_]*$/.test(name) || name.startsWith('DROY_') || name === 'DSH_HOME'
      || ['LD_PRELOAD', 'NODE_OPTIONS', 'NODE_PATH'].includes(name)) throw fail('EXECUTION_FORBIDDEN_ENV');
    if (typeof value !== 'string' || value.includes('\0')) throw fail('EXECUTION_INVALID_ENV');
    return `${name}=${value}`;
  });
  if (env.length > 256) throw fail('EXECUTION_ENV_LIMIT');
  const flags = (spec.pty ? 1 : 0) | (spec.stdio?.control === 'pipe' ? 2 : 0);
  return encodeFrame(1, Buffer.concat([
    u32(1), u32(integer(config.uid, 10000, 2147483647)), u32(integer(spec.timeoutMs ?? 3600000, 100, 86400000)),
    u32(flags), u32(integer(spec.cols ?? 80, 1, 1000)), u32(integer(spec.rows ?? 24, 1, 1000)),
    u32(integer(proxyPort, 0, 65535)), string(config.filesDir), string(spec.workspaceId ?? ''),
    string(spec.cwd), string(spec.argv[0]), u32(spec.argv.length), ...spec.argv.map(string),
    u32(env.length), ...env.map(string),
  ]));
}
function proxyEnvironment(proxy) {
  const env = { ...proxy?.env };
  for (const name of ['HTTP_PROXY', 'HTTPS_PROXY', 'ALL_PROXY', 'http_proxy', 'https_proxy', 'all_proxy']) {
    if (env[name] === undefined) continue;
    let url;
    try { url = new URL(env[name]); } catch { throw fail('EXECUTION_INVALID_PROXY'); }
    if (!proxy?.port || !['http:', 'https:', 'socks5:', 'socks5h:'].includes(url.protocol)
      || url.hostname !== '127.0.0.1' || Number(url.port) !== proxy.port || url.pathname !== '/' && url.pathname !== ''
      || url.search || url.hash) throw fail('EXECUTION_INVALID_PROXY');
    url.port = '16880'; env[name] = url.toString();
  }
  return env;
}

export class FrameDecoder {
  #buffer = Buffer.alloc(0);
  push(chunk) {
    if (chunk.length > MAX_BUFFER) throw fail('EXECUTION_TRANSPORT_BUFFER_LIMIT');
    this.#buffer = Buffer.concat([this.#buffer, chunk]);
    const result = [];
    while (this.#buffer.length >= 4) {
      const length = this.#buffer.readUInt32BE(0);
      if (length < 1 || length > MAX_FRAME) throw fail('EXECUTION_FRAME_LIMIT');
      if (this.#buffer.length < length + 4) break;
      result.push({ type: this.#buffer[4], body: Buffer.from(this.#buffer.subarray(5, length + 4)) });
      this.#buffer = this.#buffer.subarray(length + 4);
    }
    return result;
  }
  finish() { if (this.#buffer.length) throw fail('EXECUTION_TRUNCATED_FRAME'); }
}

function collected(mode) {
  if (!mode || typeof mode !== 'object') return undefined;
  const capacity = integer(mode.maxBytes, 1, 16 * 1024 * 1024);
  if (mode.spill !== undefined) integer(mode.spill.maxBytes, 1, Number.MAX_SAFE_INTEGER);
  // This backend has no trusted spill-file adapter. Report a lossy tail and
  // never advertise a complete file that was not actually retained.
  let retained = Buffer.alloc(0), count = 0;
  return {
    append(bytes) {
      count += bytes.length;
      retained = bytes.length >= capacity ? Buffer.from(bytes.subarray(-capacity))
        : Buffer.concat([retained.subarray(Math.max(0, retained.length + bytes.length - capacity)), bytes]);
    },
    readFrom(offset) {
      integer(offset, 0, Number.MAX_SAFE_INTEGER);
      if (offset > count) throw fail('EXECUTION_OUTPUT_OFFSET');
      const first = count - retained.length;
      return { text: retained.subarray(Math.max(0, offset - first)).toString('utf8'), nextOffset: count, lossy: offset < first };
    },
  };
}

/** Trusted host only. START parameters are never accepted from a model tool. */
export class RootExecutor {
  #config; #jobs = new Set(); #closed = false; #uncertain = false;
  constructor({ launcherPath, filesDir, uid, proxyFactory, suPath = 'su', spawnImpl = spawn }) {
    if (typeof launcherPath !== 'string' || !launcherPath.startsWith('/') || launcherPath.includes('\0')) throw fail('EXECUTION_INVALID_LAUNCHER');
    this.#config = { launcherPath, filesDir, uid, proxyFactory, suPath, spawnImpl };
  }
  spawn(spec) {
    if (this.#closed) throw fail('EXECUTION_CLOSED');
    if (this.#uncertain) throw fail('EXECUTION_OBSERVATION_LOST');
    const owner = this, config = this.#config, ready = deferred(), done = deferred(), gone = deferred();
    const stdout = new PassThrough({ highWaterMark: 65536 }), stderr = new PassThrough({ highWaterMark: 65536 });
    const output = { stdout: collected(spec.stdio?.stdout), stderr: collected(spec.stdio?.stderr) };
    let child, proxy, started = false, settled = false, ending = false, discardOutput = false;
    let exit, failure, protocolBroken = false, timer, readyTimer, nextRequest = 0, revision = 0;
    let pendingBytes = 0; const pending = [], requests = new Map(), blocked = new Set();
    const decoder = new FrameDecoder();
    for (const item of [ready, done, gone]) item.promise.catch(() => {});
    function unblock(stream) {
      blocked.delete(stream);
      if (!blocked.size && !settled) child?.stdout.resume();
    }
    function block(stream) {
      if (discardOutput || blocked.has(stream)) return;
      blocked.add(stream); child?.stdout.pause();
      if (stream !== control) stream.once('drain', () => unblock(stream));
    }
    function send(type, data, callback = () => {}) {
      if (ending || settled) { callback(fail('EXECUTION_CLOSED')); return; }
      let frame; try { frame = encodeFrame(type, data); } catch (error) { callback(error); return; }
      if (!child || !started) {
        if (pendingBytes + frame.length > MAX_BUFFER) { callback(fail('EXECUTION_INPUT_LIMIT')); return; }
        pendingBytes += frame.length; pending.push({ frame, callback }); return;
      }
      if (child.stdin.writableLength + frame.length > MAX_BUFFER) { callback(fail('EXECUTION_INPUT_LIMIT')); return; }
      child.stdin.write(frame, callback);
    }
    const input = new Writable({ highWaterMark: 65536,
      write(bytes, _encoding, callback) { revision++; send(2, bytes, callback); },
      final(callback) { send(3, undefined, callback); } });
    const control = new Duplex({ highWaterMark: 65536,
      write(bytes, _encoding, callback) { send(6, bytes, callback); },
      final(callback) { send(7, undefined, callback); },
      read() { unblock(control); },
    });
    for (const stream of [input, control, stdout, stderr]) stream.on('error', () => {});
    function rejectPending(error) {
      for (const item of pending.splice(0)) item.callback(error);
      pendingBytes = 0;
      for (const request of requests.values()) { clearTimeout(request.timer); request.reject(error); }
      requests.clear();
    }
    function cleanup(error, confirmed) {
      if (settled) return;
      settled = true; ending = true; clearTimeout(timer); clearTimeout(readyTimer);
      if (!confirmed) owner.#uncertain = true;
      spec.signal?.removeEventListener('abort', terminate);
      rejectPending(error ?? fail('EXECUTION_CLOSED'));
      stdout.end(); stderr.end(); control.push(null); input.destroy();
      if (!started) ready.reject(error ?? fail('EXECUTION_CANCELLED'));
      if (error) done.reject(error); else done.resolve(exit);
      Promise.resolve().then(() => proxy?.close?.()).then(() => {
        if (confirmed) gone.resolve(true); else gone.reject(fail('EXECUTION_EXIT_UNCONFIRMED'));
      }, () => { owner.#uncertain = true; gone.reject(fail('EXECUTION_PROXY_CLEANUP_FAILED')); });
    }
    function cancelInput() {
      // Closing this pipe is the native cancellation channel even while Root
      // approval or workload input is blocked. Killing su is not a receipt.
      child?.stdin.destroy();
    }
    function beginStop(error) {
      if (settled) return;
      failure ??= error; ending = true; discardOutput = true; clearTimeout(readyTimer);
      rejectPending(error ?? fail('EXECUTION_CANCELLED'));
      blocked.clear(); child?.stdout.resume(); cancelInput();
      timer ??= setTimeout(() => cleanup(failure ?? fail('EXECUTION_TERMINATION_UNCONFIRMED'), !child),
        Math.max(1000, Math.min(spec.graceMs ?? 3000, 30000)));
      timer.unref?.();
    }
    function terminate() { if (!settled && !ending) beginStop(); }
    function broken(error) { if (!settled) { protocolBroken = true; beginStop(error); } }
    function route(type, bytes) {
      if (discardOutput) return;
      const mode = spec.stdio?.[type];
      if (output[type]) output[type].append(bytes);
      else if (mode === 'inherit') { if (!process[type].write(bytes)) block(process[type]); }
      else if (mode === 'pipe' || (type === 'stdout' && spec.pty)) {
        const stream = type === 'stdout' ? stdout : stderr;
        if (!stream.write(bytes)) block(stream);
      }
    }
    function frame(item) {
      if ([17, 18, 19].includes(item.type)) {
        if (!started || exit) throw fail('EXECUTION_OUTPUT_OUTSIDE_LIFETIME');
        revision++;
        if (item.type === 19) {
          if (spec.stdio?.control !== 'pipe') throw fail('EXECUTION_UNEXPECTED_CONTROL');
          if (!discardOutput && !control.push(item.body)) block(control);
        } else route(item.type === 17 ? 'stdout' : 'stderr', item.body);
        return;
      }
      let value; try { value = JSON.parse(item.body.toString('utf8')); } catch { throw fail('EXECUTION_INVALID_RESPONSE'); }
      if (!value || typeof value !== 'object' || Array.isArray(value)) throw fail('EXECUTION_INVALID_RESPONSE');
      if (item.type === 16) {
        const userBase = Math.floor(config.uid / 100000) * 100000;
        if (started || exit || value.verified !== true || !Number.isInteger(value.topLevelPid) || value.topLevelPid <= 1
          || value.pid !== value.topLevelPid || !Number.isInteger(value.workloadPid) || value.workloadPid <= 1
          || value.workloadPid === value.topLevelPid || value.workloadGuestPid !== undefined
          || !Number.isInteger(value.uid) || value.uid < userBase + 80000 || value.uid > userBase + 80007
          || value.uid === config.uid || value.gid !== config.uid || value.pidNamespace !== false || value.ipcNamespace !== false
          || value.processIsolation !== 'dedicated-uid' || value.procIsolation !== 'private-hidepid-subset' || value.processLimit !== 128
          || value.initDumpable !== false || BOUNDARIES.some(key => value[key] !== true)
          || value.guestProxyPort !== (proxy?.port ? 16880 : 0)) throw fail('EXECUTION_INVALID_READY');
        started = true; clearTimeout(readyTimer);
        if (ending) { ready.reject(failure ?? fail('EXECUTION_CANCELLED')); return; }
        ready.resolve(value);
        const queued = pending.splice(0); pendingBytes = 0;
        for (const item of queued) child.stdin.write(item.frame, item.callback);
      } else if (item.type === 20) {
        if (exit || value.descendantsReaped !== true || typeof value.namespaceCreated !== 'boolean'
          || (!started && !failure && !ending) || (started && !value.namespaceCreated)
          || typeof value.cancelled !== 'boolean' || typeof value.timedOut !== 'boolean'
          || (value.exitCode !== null && (!Number.isInteger(value.exitCode) || value.exitCode < 0 || value.exitCode > 255))
          || !Number.isInteger(value.signal) || value.signal < 0 || value.signal > 64) throw fail('EXECUTION_INVALID_EXIT');
        if (value.signal > LINUX_SIGNALS.length) failure ??= fail('EXECUTION_UNSUPPORTED_EXIT_SIGNAL');
        exit = { exitCode: value.signal ? null : value.exitCode, signal: value.signal ? LINUX_SIGNALS[value.signal - 1] ?? null : null };
        ending = true; cancelInput();
      } else if (item.type === 21) {
        beginStop(fail(typeof value.code === 'string' && /^[A-Za-z0-9_:-]{1,96}$/.test(value.code) ? value.code : 'EXECUTION_NATIVE_FAILED'));
      } else if (item.type === 22 || item.type === 23) {
        const waiting = requests.get(value.requestId);
        if (!waiting || waiting.type !== item.type) {
          if (ending) return;
          throw fail('EXECUTION_UNEXPECTED_RESPONSE');
        }
        if ((value.groupId !== undefined && (!Number.isInteger(value.groupId) || value.groupId <= 0))
          || (item.type === 22 && (typeof value.found !== 'boolean' || value.scope !== 'host_pid_dedicated_uid' || (value.found && !value.groupId)))
          || (item.type === 23 && (typeof value.ok !== 'boolean' || (value.ok && !value.groupId)))) throw fail('EXECUTION_INVALID_CONTROL_RESPONSE');
        clearTimeout(waiting.timer); requests.delete(value.requestId); waiting.resolve(value);
      } else throw fail('EXECUTION_UNKNOWN_FRAME');
    }
    const call = (type, responseType, data = Buffer.alloc(0)) => new Promise((resolve, reject) => {
      if (requests.size >= 64) { reject(fail('EXECUTION_CONTROL_LIMIT')); return; }
      const id = ++nextRequest;
      const timer = setTimeout(() => { requests.delete(id); reject(fail('EXECUTION_CONTROL_TIMEOUT')); }, 5000);
      timer.unref?.(); requests.set(id, { type: responseType, resolve, reject, timer });
      send(type, Buffer.concat([u32(id), data]), error => { if (error) { clearTimeout(timer); requests.delete(id); reject(error); } });
    });
    const handle = {
      stdin: spec.stdio?.stdin === 'pipe' ? input : undefined,
      stdout: spec.stdio?.stdout === 'pipe' || spec.pty ? stdout : undefined,
      stderr: spec.stdio?.stderr === 'pipe' ? stderr : undefined,
      control: spec.stdio?.control === 'pipe' ? control : undefined,
      collected: Object.fromEntries(Object.entries(output).filter(([, value]) => value)),
      done: done.promise, ready: ready.promise, terminate,
      async waitForExit(signal) {
        if (signal?.aborted) return false;
        if (!signal) return gone.promise;
        let abort; try { return await Promise.race([gone.promise, new Promise(resolve => { abort = () => resolve(false); signal.addEventListener('abort', abort, { once: true }); })]); }
        finally { signal.removeEventListener('abort', abort); }
      },
      resize(cols, rows) { return new Promise((resolve, reject) => {
        try { send(5, Buffer.concat([u32(integer(cols, 1, 1000)), u32(integer(rows, 1, 1000))]), error => error ? reject(error) : resolve()); }
        catch (error) { reject(error); }
      }); },
      inspectForeground: () => call(9, 22),
      inspectActivity: async () => ({ state: 'unknown', revision }),
      signalForeground: signal => SIGNALS[signal] ? call(8, 23, u32(SIGNALS[signal])) : Promise.reject(fail('EXECUTION_UNSUPPORTED_SIGNAL')),
    };
    this.#jobs.add(handle);
    gone.promise.then(() => this.#jobs.delete(handle), () => {});
    spec.signal?.addEventListener('abort', terminate, { once: true });
    void (async () => {
      try {
        if (spec.signal?.aborted) { beginStop(fail('EXECUTION_CANCELLED')); cleanup(failure, true); return; }
        proxy = await config.proxyFactory?.(spec);
        if (ending || settled) { if (settled) await proxy?.close?.(); else cleanup(failure ?? fail('EXECUTION_CANCELLED'), true); return; }
        const request = encodeStart(config, { ...spec, env: { ...spec.env, ...proxyEnvironment(proxy) } }, proxy?.port ?? 0);
        const quoted = `'${config.launcherPath.replaceAll("'", "'\\''")}'`;
        child = config.spawnImpl(config.suPath, ['-c', `exec ${quoted}`], {
          env: { PATH: '/system/bin:/system/xbin', LANG: 'C' }, stdio: ['pipe', 'pipe', 'pipe'],
        });
        child.on('error', () => { beginStop(fail('EXECUTION_SPAWN_FAILED')); });
        child.stdout.on('data', bytes => {
          if (settled || protocolBroken) return;
          try { for (const item of decoder.push(bytes)) frame(item); } catch (error) { broken(error); }
        });
        let diagnostics = 0;
        child.stderr.on('data', bytes => { diagnostics += bytes.length; if (diagnostics > 16384) beginStop(fail('EXECUTION_DIAGNOSTIC_LIMIT')); });
        child.stdin.on('error', () => { if (!ending) beginStop(fail('EXECUTION_CONTROL_DISCONNECTED')); });
        child.on('close', () => {
          let error = failure;
          try { decoder.finish(); } catch (cause) { error ??= cause; protocolBroken = true; }
          const confirmed = !!exit && !protocolBroken;
          cleanup(error ?? (confirmed ? undefined : fail('EXECUTION_EXIT_UNCONFIRMED')), confirmed);
        });
        readyTimer = setTimeout(() => beginStop(fail('EXECUTION_ROOT_OR_START_TIMEOUT')), 20000); readyTimer.unref?.();
        child.stdin.write(request);
        if (typeof spec.stdio?.stdin === 'object') input.end(spec.stdio.stdin.data);
        else if (spec.stdio?.stdin !== 'pipe' && !spec.pty) input.end();
      } catch { if (child) beginStop(fail('EXECUTION_START_FAILED')); else cleanup(fail('EXECUTION_START_FAILED'), true); }
    })();
    return handle;
  }
  async spawnTerminal(spec) {
    const handle = this.spawn({ ...spec, pty: true, stdio: { stdin: 'pipe', stdout: 'pipe', stderr: 'pipe' },
      env: { ...spec.env, TERM: spec.terminalType } });
    let ready;
    try { ready = await handle.ready; }
    catch (error) {
      handle.terminate();
      try { if (await handle.waitForExit() === true) error.executionQuiescent = true; }
      catch { /* Preserve an unconfirmed range for the owning boundary. */ }
      throw error;
    }
    return { pid: ready.topLevelPid, output: handle.stdout, done: handle.done,
      write: data => new Promise((resolve, reject) => handle.stdin.write(data, error => error ? reject(error) : resolve())),
      resize: handle.resize,
      // DSH requires a proven input-wait flag alongside the group. The native
      // protocol only observes the group, so this combined capability is unknown.
      async inspectForeground() { await handle.inspectForeground(); return undefined; },
      inspectActivity: handle.inspectActivity,
      async signalForeground(signal) { const result = await handle.signalForeground(signal); if (!result.ok) throw fail('EXECUTION_SIGNAL_REJECTED'); return result.groupId; },
      async terminate() { handle.terminate(); await handle.waitForExit(); await handle.done; },
    };
  }
  async close() {
    this.#closed = true;
    const jobs = [...this.#jobs];
    for (const job of jobs) job.terminate();
    await Promise.all(jobs.map(job => job.waitForExit()));
  }
}
