import test from 'node:test';
import assert from 'node:assert/strict';
import { runInNewContext } from 'node:vm';
import { createMobileMemoryHost, qualifyExecutor } from '../memory-host.mjs';

function fixture(options = {}) {
  const host = { filesDir: '/data/user/0/io.droyagent.app/files', nativeDir: '/data/app/synthetic/lib/arm64', appUid: options.appUid ?? 10234 };
  const uid = options.uid ?? Math.floor(host.appUid / 100000) * 100000 + 80003;
  const pid = 9001, gid = options.gid ?? host.appUid;
  let launched;
  const executor = { spawn(spec) {
    launched = spec; let output = '', exitCode = 0;
    const fs = { readFileSync() { if (options.privateVisible) return 'synthetic'; throw new Error('EACCES'); },
      existsSync(path) { return options.procVisible === 'all' || path === options.procVisible; } };
    try {
      runInNewContext(spec.argv[2], { require(name) { assert.equal(name, 'fs'); return fs; },
        process: { getuid: () => uid, geteuid: () => options.euid ?? uid, getgid: () => gid, pid,
          env: options.env ?? {}, stdout: { write(value) { output += value; } },
          exit(code) { throw { exitCode: code }; } } });
    } catch (error) { if (Number.isInteger(error.exitCode)) exitCode = error.exitCode; else throw error; }
    return { done: Promise.resolve({ exitCode, signal: null }), waitForExit: async () => options.quiescent ?? true,
      ready: Promise.resolve({ uid, workloadPid: pid, processIsolation: 'dedicated-uid', procIsolation: 'private-hidepid-subset',
        uidLease: true, descendantsManaged: true, ...options.ready }),
      collected: { stdout: { readFrom: () => ({ text: options.output ?? output, lossy: options.lossy ?? false }) } } };
  } };
  return { qualify: () => qualifyExecutor(executor, host), get launched() { return launched; } };
}

test('host qualification executes dedicated UID checks for both Android user offsets', async () => {
  for (const appUid of [10234, 1010234]) for (const slot of [80000, 80007]) {
    const f = fixture({ appUid, uid: Math.floor(appUid / 100000) * 100000 + slot });
    const result = await f.qualify();
    assert.equal(result.processIsolation, 'dedicated-uid'); assert.equal(result.procIsolation, 'private-hidepid-subset');
    assert.equal(result.descendantsReaped, true); assert.equal(f.launched.workspaceId, '0'.repeat(64));
    assert.deepEqual(f.launched.env, {});
  }
});

test('host qualification rejects wrong identity, exposed private paths and host proc visibility', async () => {
  for (const value of [{ uid: 65534 }, { uid: 79999 }, { uid: 80008 }, { uid: 180000 }, { gid: 80003 },
    { euid: 0 }, { privateVisible: true }, { env: { DROY_BRIDGE_TOKEN: 'synthetic' } },
    { procVisible: '/proc/uptime' }, { procVisible: `/proc/${process.pid}` }]) {
    await assert.rejects(fixture(value).qualify(), /MEMORY_HOST_QUALIFICATION_FAILED/);
  }
});

test('host qualification matches observed workload to its receipt and requires confirmed cleanup', async () => {
  for (const value of [{ ready: { uid: 80004 } }, { ready: { workloadPid: 9002 } }, { ready: { uidLease: false } },
    { ready: { descendantsManaged: false } }, { ready: { processIsolation: 'pid-namespace' } },
    { ready: { procIsolation: 'host' } }, { quiescent: false }, { lossy: true }, { output: 'DROY_HOST_QUALIFIED_V1' }]) {
    await assert.rejects(fixture(value).qualify(), /MEMORY_HOST_QUALIFICATION_FAILED/);
  }
});

function variantHost(host) {
  let configured;
  const store = { close: async () => {} };
  const service = createMobileMemoryHost({
    ctx: { get: name => name === 'droyPrivacy' ? { invalidate() {} } : { installed: () => true } },
    bridge: { async memory({ operation }) {
      if (operation === 'status') return { settings: { privacyEverActivated: true }, execution: { epoch: 'synthetic', ready: true } };
      return { host, runtimeEpoch: 'synthetic', challenge: 'synthetic', nativeQualificationId: 'synthetic', initialize: false };
    } },
    environment: {}, openMemory: async () => store, openPrivacy: async () => store,
    executorFactory: options => { configured = options; return { close: async () => {} }; },
    qualify: async () => ({ synthetic: true }), featureAvailable: () => true,
  });
  return { service, get configured() { return configured; } };
}

test('memory host binds each variant to its own files and primary-user alias', async () => {
  for (const name of ['io.droyagent.app', 'io.droyagent.app.dev']) for (const user of [0, 1]) {
    const filesDir = `/data/user/${user}/${name}/files`;
    const host = { filesDir, nativeDir: '/data/app/synthetic/lib/arm64', appUid: user * 100000 + 10234 };
    const f = variantHost(host);
    try {
      await f.service.privacyHost.activate();
      assert.equal(f.configured.filesDir, filesDir);
      assert.equal(f.configured.uid, host.appUid);
      const policy = f.service.executionHost.policy();
      assert.equal(policy.workspaceRoot, filesDir + '/dsh-home/workspace');
      assert.ok(policy.privateRoots.every(path => path.startsWith(filesDir + '/')));
      assert.deepEqual(policy.pathAliases, user === 0 ? [{ from: `/data/data/${name}`, to: `/data/user/0/${name}` }] : []);
    } finally { await f.service.close(); }
  }
});

test('memory host rejects unknown package roots and mismatched Android users before creating an executor', async () => {
  for (const filesDir of ['/data/user/0/other.app/files', '/data/user/0/io.droyagent.app.dev.other/files',
    '/data/user/1/io.droyagent.app.dev/files', '/data/user/00/io.droyagent.app.dev/files', '/data/data/io.droyagent.app.dev/files']) {
    const f = variantHost({ filesDir, nativeDir: '/data/app/synthetic/lib/arm64', appUid: 10234 });
    try {
      await assert.rejects(f.service.privacyHost.activate(), { code: 'MEMORY_NATIVE_HOST_INVALID' });
      assert.equal(f.configured, undefined);
    } finally { await f.service.close(); }
  }
});
