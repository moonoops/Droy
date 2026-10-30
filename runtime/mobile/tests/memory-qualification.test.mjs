import test from 'node:test';
import assert from 'node:assert/strict';
import { createMobileMemoryHost } from '../memory-host.mjs';

function variantHost(host) {
  const opened = [];
  const store = { close: async () => {} };
  const service = createMobileMemoryHost({
    ctx: { get: name => name === 'droyPrivacy' ? { invalidate() {} } : { installed: () => true } },
    bridge: { async memory({ operation }) {
      if (operation === 'status') return { settings: { privacyEverActivated: true }, execution: { epoch: 'synthetic', ready: true } };
      return { host, runtimeEpoch: 'synthetic', challenge: 'synthetic', nativeQualificationId: 'synthetic', initialize: false };
    } },
    environment: {}, openMemory: async options => { opened.push(options); return store; },
    openPrivacy: async options => { opened.push(options); return store; }, featureAvailable: () => true,
  });
  return { service, opened };
}

test('memory host binds each variant to its own files and primary-user alias', async () => {
  for (const name of ['io.droyagent.app', 'io.droyagent.app.dev']) for (const user of [0, 1]) {
    const filesDir = `/data/user/${user}/${name}/files`;
    const host = { filesDir, nativeDir: '/data/app/synthetic/lib/arm64', appUid: user * 100000 + 10234 };
    const f = variantHost(host);
    try {
      await f.service.privacyHost.activate();
      assert.deepEqual(f.opened.map(value => value.directory), [filesDir + '/droy-privacy', filesDir + '/droy-memory']);
      assert.equal(f.service.executionHost.isolationRequired, false);
      const policy = f.service.executionHost.policy();
      assert.equal(policy.workspaceRoot, filesDir + '/dsh-home/workspace');
      assert.ok(policy.privateRoots.every(path => path.startsWith(filesDir + '/')));
      assert.deepEqual(policy.pathAliases, user === 0 ? [{ from: `/data/data/${name}`, to: `/data/user/0/${name}` }] : []);
    } finally { await f.service.close(); }
  }
});

test('memory host rejects unknown package roots and mismatched Android users before opening private stores', async () => {
  for (const filesDir of ['/data/user/0/other.app/files', '/data/user/0/io.droyagent.app.dev.other/files',
    '/data/user/1/io.droyagent.app.dev/files', '/data/user/00/io.droyagent.app.dev/files', '/data/data/io.droyagent.app.dev/files']) {
    const f = variantHost({ filesDir, nativeDir: '/data/app/synthetic/lib/arm64', appUid: 10234 });
    try {
      await assert.rejects(f.service.privacyHost.activate(), { code: 'MEMORY_NATIVE_HOST_INVALID' });
      assert.deepEqual(f.opened, []);
    } finally { await f.service.close(); }
  }
});
