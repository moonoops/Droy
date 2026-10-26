import test from 'node:test';
import assert from 'node:assert/strict';
import { EventEmitter } from 'node:events';
import { PassThrough } from 'node:stream';
import { mkdtempSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { RootExecutor, FrameDecoder, encodeFrame, encodeStart } from '../launcher.mjs';

const CONFIG = { launcherPath: '/data/app/synthetic/lib/arm64/libdroy_exec.so', filesDir: '/data/user/0/io.droyagent.app/files', uid: 10234 };
const READY = { pid: 4032, topLevelPid: 4032, workloadPid: 4033, uid: 80000, gid: CONFIG.uid, verified: true,
  mountNamespace: true, pidNamespace: false, ipcNamespace: false, netNamespace: true, utsNamespace: true,
  processIsolation: 'dedicated-uid', procIsolation: 'private-hidepid-subset', uidLease: true,
  descendantsManaged: true, ipcSyscallsDenied: true, processLimit: 128,
  initDumpable: false, rootFilesystemPrivate: true, capabilitiesEmpty: true, noNewPrivs: true, inheritedHostFdsClosed: true, seccomp: true, guestProxyPort: 0 };
const EXIT = { exitCode: 0, signal: 0, cancelled: false, timedOut: false, descendantsReaped: true, namespaceCreated: true };
const spec = extras => ({ argv: ['node', '-e', 'process.stdout.write("synthetic")'], cwd: `${CONFIG.filesDir}/workspace`,
  graceMs: 1000, stdio: { stdin: 'ignore', stdout: { maxBytes: 32 }, stderr: { maxBytes: 32 } }, ...extras });
const tick = () => new Promise(resolve => setImmediate(resolve));
function fixture(options = {}) {
  const child = new EventEmitter();
  child.stdin = new PassThrough(); child.stdout = new PassThrough({ highWaterMark: 65536 }); child.stderr = new PassThrough();
  child.killed = []; child.kill = signal => child.killed.push(signal);
  const input = new FrameDecoder(), frames = []; child.stdin.on('data', bytes => frames.push(...input.push(bytes)));
  child.json = (type, value) => child.stdout.write(encodeFrame(type, Buffer.from(JSON.stringify(value))));
  child.ready = value => child.json(16, { ...READY, ...value });
  child.exit = value => child.json(20, { ...EXIT, ...value });
  child.close = () => { child.stdout.once('end', () => child.emit('close', 0, null)); child.stdout.end(); child.stderr.end(); };
  let spawnArgs;
  const executor = new RootExecutor({ ...CONFIG, spawnImpl: (...args) => { spawnArgs = args; return child; }, ...options });
  return { child, frames, executor, get spawnArgs() { return spawnArgs; } };
}
async function opened(f, overrides) { const handle = f.executor.spawn(spec(overrides)); assert.equal(typeof handle.done.then, 'function'); await tick(); f.child.ready(); await handle.ready; return handle; }
async function finish(f, handle, facts) { f.child.exit(facts); f.child.close(); return handle.done; }

// This independently reads the documented native wire layout, rather than
// asserting the JS encoder against a second use of the same encoder.
test('START matches native field order and env values are explicit without host credentials', async () => {
  const f = fixture(); const handle = f.executor.spawn(spec({ env: { LANG: 'zh_CN.UTF-8', OMITTED: undefined }, workspaceId: 'a'.repeat(64) }));
  await tick();
  assert.deepEqual(f.spawnArgs[2].env, { PATH: '/system/bin:/system/xbin', LANG: 'C' });
  assert.deepEqual(f.spawnArgs.slice(0, 2), ['su', ['-c', `exec '${CONFIG.launcherPath}'`]]);
  const body = f.frames[0].body; let offset = 0;
  const number = () => { const n = body.readUInt32BE(offset); offset += 4; return n; };
  const string = () => { const n = number(); const s = body.subarray(offset, offset + n).toString(); offset += n; return s; };
  assert.deepEqual(Array.from({ length: 7 }, number), [1, CONFIG.uid, 3600000, 0, 80, 24, 0]);
  assert.deepEqual([string(), string(), string(), string()], [CONFIG.filesDir, 'a'.repeat(64), spec().cwd, 'node']);
  assert.deepEqual(Array.from({ length: number() }, string), spec().argv);
  assert.deepEqual(Array.from({ length: number() }, string), ['LANG=zh_CN.UTF-8']); assert.equal(offset, body.length);
  f.child.ready(); await handle.ready; await finish(f, handle); assert.equal(await handle.waitForExit(), true);
});

test('encoder rejects NUL, non-string environment and unsupported dimensions before launch', () => {
  for (const change of [{ env: { TOKEN: {} } }, { env: { DROY_BRIDGE_TOKEN: 'synthetic' } }, { env: { PATH: 'a\0b' } }, { cols: 1001 }, { argv: ['a\0b'] }]) {
    assert.throws(() => encodeStart(CONFIG, spec(change)), /EXECUTION_/);
  }
});

test('decoder handles split headers/payloads and bounds malformed or unfinished frames', () => {
  const decoder = new FrameDecoder(), frame = encodeFrame(17, Buffer.from('synthetic'));
  assert.deepEqual(decoder.push(frame.subarray(0, 2)), []); assert.deepEqual(decoder.push(frame.subarray(2, 6)), []);
  assert.deepEqual(decoder.push(frame.subarray(6)), [{ type: 17, body: Buffer.from('synthetic') }]); decoder.finish();
  assert.throws(() => new FrameDecoder().push(Buffer.from([0, 32, 0, 0])), /FRAME_LIMIT/);
  const incomplete = new FrameDecoder(); incomplete.push(frame.subarray(0, 6)); assert.throws(() => incomplete.finish(), /TRUNCATED_FRAME/);
  assert.throws(() => new FrameDecoder().push(Buffer.alloc(2097153)), /TRANSPORT_BUFFER_LIMIT/);
});

test('synchronous handle accepts pending stdin and ready/exit frames may arrive in fragments', async () => {
  const f = fixture(); const handle = f.executor.spawn(spec({ stdio: { stdin: 'pipe', stdout: { maxBytes: 16 }, stderr: { maxBytes: 16 }, control: 'pipe' } }));
  const write = new Promise((resolve, reject) => handle.stdin.write('input', error => error ? reject(error) : resolve()));
  await tick(); const ready = encodeFrame(16, Buffer.from(JSON.stringify(READY)));
  f.child.stdout.write(ready.subarray(0, 3)); f.child.stdout.write(ready.subarray(3)); await handle.ready; await write;
  assert.equal(f.frames[1].type, 2); assert.equal(f.frames[1].body.toString(), 'input');
  f.child.stdout.write(encodeFrame(17, Buffer.from('abcdefghij')));
  await finish(f, handle, { exitCode: 7 }); assert.deepEqual(await handle.done, { exitCode: 7, signal: null });
});

test('collected output retains a bounded tail and never claims an unavailable full spill', async () => {
  const f = fixture(); const handle = await opened(f, { stdio: { stdin: 'ignore', stdout: { maxBytes: 4, spill: { maxBytes: 1000 } }, stderr: { maxBytes: 4 } } });
  f.child.stdout.write(encodeFrame(17, Buffer.from('abc'))); f.child.stdout.write(encodeFrame(17, Buffer.from('defgh')));
  await finish(f, handle);
  assert.deepEqual(handle.collected.stdout.readFrom(0), { text: 'efgh', nextOffset: 8, lossy: true });
  assert.deepEqual(handle.collected.stdout.readFrom(6), { text: 'gh', nextOffset: 8, lossy: false });
  assert.throws(() => handle.collected.stdout.readFrom(9), /OUTPUT_OFFSET/);
});

test('transport close without native descendant receipt rejects both done and range observation', async () => {
  const f = fixture(); const handle = await opened(f); f.child.close();
  await assert.rejects(handle.done, { code: 'EXECUTION_EXIT_UNCONFIRMED' });
  await assert.rejects(handle.waitForExit(), { code: 'EXECUTION_EXIT_UNCONFIRMED' }); assert.deepEqual(f.child.killed, []);
});

test('startup ERROR plus explicit empty-namespace EXIT proves absence but preserves the failure', async () => {
  const f = fixture(); const handle = f.executor.spawn(spec()); await tick();
  f.child.json(21, { code: 'ROOT_NOT_AUTHORIZED' }); f.child.exit({ exitCode: 125, namespaceCreated: false }); f.child.close();
  await assert.rejects(handle.ready, { code: 'ROOT_NOT_AUTHORIZED' }); await assert.rejects(handle.done, { code: 'ROOT_NOT_AUTHORIZED' });
  assert.equal(await handle.waitForExit(), true); assert.equal(f.child.stdin.destroyed, true); assert.deepEqual(f.child.killed, []);
});

test('a missing boundary fact or bad frame cannot produce successful cleanup', async () => {
  for (const mode of ['ready', 'frame']) {
    const f = fixture(); const handle = f.executor.spawn(spec()); await tick();
    if (mode === 'ready') f.child.ready({ seccomp: false }); else f.child.stdout.write(Buffer.from([0, 32, 0, 0]));
    f.child.exit(); f.child.close();
    await assert.rejects(handle.done, /EXECUTION_(INVALID_READY|FRAME_LIMIT)/); await assert.rejects(handle.waitForExit(), /EXIT_UNCONFIRMED/);
    assert.equal(f.child.stdin.destroyed, true); assert.deepEqual(f.child.killed, []);
  }
});

test('READY requires the full dedicated UID contract and rejects the previous namespace contract', async () => {
  const invalid = [
    { uid: 65534, workloadPid: undefined, workloadGuestPid: 2, pidNamespace: true, ipcNamespace: true },
    { uid: 79999 }, { uid: 80008 }, { uid: 180000 }, { uid: '80000' }, { uid: CONFIG.uid }, { gid: 80000 },
    { pidNamespace: true }, { ipcNamespace: true }, { ipcSyscallsDenied: false }, { uidLease: false },
    { descendantsManaged: false }, { processLimit: 127 }, { initDumpable: true },
    { processIsolation: 'namespace' }, { procIsolation: 'host' }, { workloadPid: 1 },
    { workloadPid: READY.topLevelPid }, { workloadGuestPid: 2 }, { topLevelPid: 1, pid: 1 },
  ];
  for (const value of invalid) {
    const f = fixture(); const handle = f.executor.spawn(spec()); await tick();
    f.child.ready(value); f.child.exit(); f.child.close();
    await assert.rejects(handle.ready, /INVALID_READY/);
    await assert.rejects(handle.done, /INVALID_READY/);
    await assert.rejects(handle.waitForExit(), /EXIT_UNCONFIRMED/);
  }
});

test('dedicated UID pools follow the Android user and include both configured endpoints', async () => {
  for (const hostUid of [CONFIG.uid, 1010234]) for (const offset of [80000, 80007]) {
    const f = fixture({ uid: hostUid }); const handle = f.executor.spawn(spec()); await tick();
    const guestUid = Math.floor(hostUid / 100000) * 100000 + offset;
    f.child.ready({ uid: guestUid, gid: hostUid });
    assert.equal((await handle.ready).uid, guestUid); await finish(f, handle);
    assert.equal(await handle.waitForExit(), true);
  }
  const f = fixture({ uid: 1010234 }); const handle = f.executor.spawn(spec()); await tick();
  f.child.ready({ uid: 80000, gid: 1010234 }); f.child.exit(); f.child.close();
  await assert.rejects(handle.done, /INVALID_READY/); await assert.rejects(handle.waitForExit(), /EXIT_UNCONFIRMED/);
});

test('termination during Root approval closes input and waits for native confirmation', async () => {
  const f = fixture(); const handle = f.executor.spawn(spec()); await tick(); handle.terminate();
  assert.equal(f.child.stdin.destroyed, true); assert.deepEqual(f.child.killed, []);
  const controller = new AbortController(); controller.abort(); assert.equal(await handle.waitForExit(controller.signal), false);
  f.child.exit({ exitCode: null, signal: 9, cancelled: true, namespaceCreated: false }); f.child.close();
  await assert.rejects(handle.ready, /CANCELLED/); assert.deepEqual(await handle.done, { exitCode: null, signal: 'SIGKILL' });
  assert.equal(await handle.waitForExit(), true);
});

test('cancellation before asynchronous proxy setup cannot spawn later and closes its proxy', async () => {
  let resolveProxy, closeCalls = 0, spawns = 0;
  const executor = new RootExecutor({ ...CONFIG, proxyFactory: () => new Promise(resolve => { resolveProxy = resolve; }), spawnImpl: () => { spawns++; } });
  const handle = executor.spawn(spec()); handle.terminate(); resolveProxy({ close() { closeCalls++; } });
  await assert.rejects(handle.done, /CANCELLED/); assert.equal(await handle.waitForExit(), true); assert.equal(spawns, 0); assert.equal(closeCalls, 1);
});

test('stdout and the control pipe apply upstream backpressure; cancellation drains receipts without consuming user pipes', async () => {
  for (const stream of ['stdout', 'control']) {
    const f = fixture(); const handle = await opened(f, { stdio: { stdin: 'ignore', stdout: 'pipe', stderr: 'pipe', control: 'pipe' } });
    const frame = encodeFrame(stream === 'stdout' ? 17 : 19, Buffer.alloc(65536, 97));
    f.child.stdout.write(frame); f.child.stdout.write(frame); await tick();
    assert.equal(f.child.stdout.isPaused(), true);
    assert.ok(handle[stream].readableLength <= 2 * 65536);
    handle[stream].resume(); await tick(); assert.equal(f.child.stdout.isPaused(), false);
    handle[stream].pause(); f.child.stdout.write(frame); f.child.stdout.write(frame); await tick();
    handle.terminate(); assert.equal(f.child.stdout.isPaused(), false); assert.equal(f.child.stdin.destroyed, true);
    await finish(f, handle, { signal: 9, exitCode: null, cancelled: true }); assert.equal(await handle.waitForExit(), true);
  }
});

test('PTY uses native foreground response fields and keeps unavailable input-wait observation unknown', async () => {
  const f = fixture(); const opening = f.executor.spawnTerminal({ ...spec(), cols: 80, rows: 24, terminalType: 'xterm-256color' });
  await tick(); f.child.ready(); const terminal = await opening; assert.equal(terminal.pid, READY.topLevelPid);
  const inspect = terminal.inspectForeground(); await tick(); const request = f.frames.findLast(frame => frame.type === 9).body.readUInt32BE(0);
  f.child.json(22, { requestId: request, found: true, groupId: 4033, scope: 'host_pid_dedicated_uid' }); assert.equal(await inspect, undefined);
  const signal = terminal.signalForeground('SIGINT'); await tick(); const signalFrame = f.frames.findLast(frame => frame.type === 8);
  assert.equal(signalFrame.body.readUInt32BE(4), 2); f.child.json(23, { requestId: signalFrame.body.readUInt32BE(0), ok: true, groupId: 4033 });
  assert.equal(await signal, 4033); await assert.rejects(terminal.signalForeground('SIGKILL'), /UNSUPPORTED_SIGNAL/);
  await assert.rejects(terminal.resize(1001, 24), /INVALID_NUMBER/); await terminal.resize(120, 40);
  assert.equal((await terminal.inspectActivity()).state, 'unknown');
  f.child.exit(); f.child.close(); await terminal.done;
});

test('executor close propagates uncertainty instead of treating su exit as quiescence', async () => {
  const f = fixture(); const handle = await opened(f); const closing = f.executor.close(); f.child.close();
  await assert.rejects(closing, /EXIT_UNCONFIRMED/); await assert.rejects(handle.done, /EXIT_UNCONFIRMED/);
  assert.throws(() => f.executor.spawn(spec()), /CLOSED/);
});


test('JS START frames are accepted by the actual native C++ decoder', () => {
  const dir = mkdtempSync(join(tmpdir(), 'droy-launcher-contract-'));
  try {
    const binary = join(dir, 'driver');
    const build = spawnSync(process.env.CXX || 'c++', ['-std=c++17', '-Wall', '-Wextra', '-Werror',
      fileURLToPath(new URL('protocol-driver.cpp', import.meta.url)), '-o', binary], { encoding: 'utf8' });
    assert.equal(build.status, 0, build.stderr);
    for (const change of [{}, { pty: true, cols: 1000, rows: 1000 }, { workspaceId: 'b'.repeat(64), stdio: { control: 'pipe' }, env: { LANG: 'zh_CN.UTF-8' } }]) {
      const decoded = spawnSync(binary, [], { input: encodeStart(CONFIG, spec(change), 24000).subarray(5), encoding: 'utf8' });
      assert.equal(decoded.status, 0, decoded.stdout); assert.match(decoded.stdout, /^10234\n/);
    }
  } finally { rmSync(dir, { recursive: true, force: true }); }
});

test('a non-responsive native cancellation rejects range observation and blocks later launches', async () => {
  const f = fixture(); const handle = await opened(f); handle.terminate();
  const keepAlive = setTimeout(() => {}, 1200);
  try {
    await assert.rejects(handle.done, /TERMINATION_UNCONFIRMED/);
    await assert.rejects(handle.waitForExit(), /EXIT_UNCONFIRMED/);
    assert.throws(() => f.executor.spawn(spec()), /OBSERVATION_LOST/);
    await assert.rejects(f.executor.close(), /EXIT_UNCONFIRMED/);
    assert.deepEqual(f.child.killed, []);
  } finally { clearTimeout(keepAlive); f.child.close(); }
});

test('unrepresentable Linux realtime signal fails outcome without losing proven tree cleanup', async () => {
  const f = fixture(); const handle = await opened(f); f.child.exit({ exitCode: null, signal: 34 }); f.child.close();
  await assert.rejects(handle.done, /UNSUPPORTED_EXIT_SIGNAL/); assert.equal(await handle.waitForExit(), true);
});

test('guest proxy environment uses the fixed guest port while START keeps the host listener port', async () => {
  let closed = false;
  const f = fixture({ proxyFactory: async () => ({ port: 24000,
    env: { HTTP_PROXY: 'http://synthetic-user:synthetic-pass@127.0.0.1:24000', HTTPS_PROXY: 'http://127.0.0.1:24000',
      http_proxy: 'http://127.0.0.1:24000', all_proxy: 'socks5://127.0.0.1:24000', NODE_USE_ENV_PROXY: '1' },
    close() { closed = true; } }) });
  const handle = f.executor.spawn(spec()); await tick();
  assert.equal(f.frames[0].body.readUInt32BE(24), 24000);
  const text = f.frames[0].body.toString();
  assert.ok(text.includes('HTTP_PROXY=http://synthetic-user:synthetic-pass@127.0.0.1:16880/'));
  assert.ok(text.includes('HTTPS_PROXY=http://127.0.0.1:16880/'));
  assert.ok(text.includes('all_proxy=socks5://127.0.0.1:16880'));
  assert.ok(!text.includes(':24000')); f.child.ready({ guestProxyPort: 16880 }); await handle.ready;
  await finish(f, handle); assert.equal(await handle.waitForExit(), true); assert.equal(closed, true);
});

test('failed terminal allocation marks quiescence only after a native range confirmation', async () => {
  for (const confirmed of [true, false]) {
    const f = fixture(); const opening = f.executor.spawnTerminal({ ...spec(), cols: 80, rows: 24, terminalType: 'xterm' });
    await tick(); f.child.json(21, { code: 'synthetic_start_failed' });
    if (confirmed) f.child.exit({ exitCode: 125, namespaceCreated: false });
    f.child.close();
    await assert.rejects(opening, error => {
      assert.equal(error.code, 'synthetic_start_failed');
      assert.equal(error.executionQuiescent, confirmed ? true : undefined);
      return true;
    });
    assert.deepEqual(f.child.killed, []);
  }
});
