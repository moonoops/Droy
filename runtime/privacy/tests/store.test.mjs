import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, mkdir, rm, readFile, writeFile, unlink, copyFile } from 'node:fs/promises';
import { join } from 'node:path';
import { tmpdir } from 'node:os';
import { spawn } from 'node:child_process';
import { once } from 'node:events';
import { fileURLToPath } from 'node:url';
import { DatabaseSync, backup } from 'node:sqlite';
import { PrivacyStore } from '../store.mjs';
import { normalizeEndpoint } from '../policy.mjs';

const endpoint = Object.freeze({ url: 'https://fixture.invalid/v1', purpose: 'conversation', configVersion: 'a'.repeat(64) });
const denied = expected => error => error.code === expected;

async function fixture(t, options = {}) {
  const root = await mkdtemp(join(tmpdir(), 'droy-privacy-store-'));
  const config = { directory: join(root, 'privacy'), markerPath: join(root, 'host', 'privacy-marker.json'), ...options };
  const stores = [];
  async function open(initialize = true) {
    const store = await PrivacyStore.open({ ...config, initialize }); stores.push(store); return store;
  }
  t.after(async () => {
    await Promise.allSettled(stores.map(store => store.close()));
    await rm(root, { recursive: true, force: true });
  });
  return { root, config, open, database: join(config.directory, 'privacy.sqlite') };
}

test('Node permission mode fails closed before worker creation rather than dropping host restrictions', async t => {
  const f = await fixture(t);
  const moduleUrl = new URL('../store.mjs', import.meta.url).href;
  const packageDirectory = fileURLToPath(new URL('../', import.meta.url));
  const script = `import { PrivacyStore } from ${JSON.stringify(moduleUrl)};
    try { const store=await PrivacyStore.open(JSON.parse(process.argv[1])); await store.close();
      process.stdout.write('unexpected-success'); process.exitCode=1;
    } catch(error) { process.stdout.write(error.code ?? error.name); }`;
  const child = spawn(process.execPath, ['--permission', '--allow-worker', `--allow-fs-read=${packageDirectory}`,
    '--input-type=module', '-e', script, JSON.stringify({ ...f.config, initialize: true })],
  { stdio: ['ignore', 'pipe', 'pipe'] });
  let output = '', stderr = '';
  child.stdout.on('data', value => { output += value; });
  child.stderr.on('data', value => { stderr += value; });
  const timeout = setTimeout(() => child.kill('SIGKILL'), 5000);
  t.after(() => { clearTimeout(timeout); if (child.exitCode === null) child.kill('SIGKILL'); });
  const [exitCode] = await once(child, 'exit');
  assert.equal(exitCode, 0, stderr);
  assert.equal(output, 'PRIVACY_UNSUPPORTED_RUNTIME_PERMISSIONS');
  await assert.rejects(readFile(f.config.markerPath), error => error.code === 'ENOENT');
});

test('initialization is explicit and the marker must be independently located', async t => {
  const f = await fixture(t);
  await assert.rejects(f.open(false), denied('PRIVACY_NOT_INITIALIZED'));
  await assert.rejects(PrivacyStore.open({ directory: f.config.directory,
    markerPath: join(f.config.directory, 'marker.json'), initialize: true }), denied('PRIVACY_MARKER_NOT_INDEPENDENT'));
  const store = await f.open();
  const status = await store.status();
  const marker = JSON.parse(await readFile(f.config.markerPath, 'utf8'));
  assert.equal(marker.instanceId, status.instanceId);
  assert.equal(marker.schemaVersion, 1);
  assert.equal(status.revision, 0);
});

test('unknown is denied; only host registration admits clean sessions; protection requires exact endpoint grants', async t => {
  const f = await fixture(t), store = await f.open();
  assert.equal((await store.sessionState('s1')).state, 'unknown');
  await assert.rejects(store.assertAllowed({ sessionId: 's1', endpoint }), denied('PRIVACY_SESSION_UNKNOWN'));
  await store.registerCleanSession('s1');
  assert.equal((await store.assertAllowed({ sessionId: 's1', endpoint })).state, 'known-clean');
  await store.protectSession('s1', { categories: ['memory'], sourceRefs: ['node1:record1'] });
  await assert.rejects(store.assertAllowed({ sessionId: 's1', endpoint }), denied('PRIVACY_ENDPOINT_NOT_AUTHORIZED'));
  await store.grantEndpoint({ ...endpoint, categories: ['memory'] });
  assert.equal((await store.assertAllowed({ sessionId: 's1', endpoint })).state, 'protected');
  await store.protectSession('s1', { categories: ['location'], sourceRefs: ['node1:place1'] });
  await assert.rejects(store.assertAllowed({ sessionId: 's1', endpoint }), denied('PRIVACY_ENDPOINT_NOT_AUTHORIZED'));
  await store.grantEndpoint({ ...endpoint, categories: ['location', 'memory'] });
  assert.equal((await store.assertAllowed({ sessionId: 's1', endpoint })).allowed, true);
  for (const changed of [ { ...endpoint, url: 'https://other.invalid/v1' },
    { ...endpoint, purpose: 'compaction' }, { ...endpoint, configVersion: 'b'.repeat(64) } ]) {
    await assert.rejects(store.assertAllowed({ sessionId: 's1', endpoint: changed }), denied('PRIVACY_ENDPOINT_NOT_AUTHORIZED'));
  }
  await store.revokeEndpoint(endpoint);
  await assert.rejects(store.assertAllowed({ sessionId: 's1', endpoint }), denied('PRIVACY_ENDPOINT_NOT_AUTHORIZED'));
  assert.deepEqual(await store.listGrants(), []);
});

test('protection, fork inheritance and forgotten sessions survive restarts without downgrade', async t => {
  const f = await fixture(t);
  let store = await f.open();
  await store.registerCleanSession('parent');
  await store.protectSession('parent', { categories: ['activity'], sourceRefs: ['node:event'] });
  await store.inheritSession('parent', 'child');
  await store.grantEndpoint({ ...endpoint, categories: ['activity'] });
  await assert.rejects(store.registerCleanSession('parent'), denied('PRIVACY_SESSION_DOWNGRADE'));
  await store.close();
  store = await f.open(false);
  assert.equal((await store.assertAllowed({ sessionId: 'child', endpoint })).allowed, true);
  assert.deepEqual((await store.sessionState('child')).sourceRefs, ['node:event']);
  await store.forgetSessions(['parent', 'child']);
  await store.inheritSession('parent', 'grandchild');
  await assert.rejects(store.registerCleanSession('child'), denied('PRIVACY_SESSION_DOWNGRADE'));
  await assert.rejects(store.protectSession('child', { categories: ['memory'] }), denied('PRIVACY_SESSION_FORGOTTEN'));
  await store.close();
  store = await f.open(false);
  for (const sessionId of ['parent', 'child', 'grandchild']) {
    await assert.rejects(store.assertAllowed({ sessionId, endpoint }), denied('PRIVACY_SESSION_FORGOTTEN'));
  }
  await assert.rejects(store.inheritSession('unknown-parent', 'new-child'), denied('PRIVACY_SESSION_UNKNOWN'));
  assert.equal((await store.sessionState('new-child')).state, 'unknown');
});

test('a clean parent cannot downgrade an existing protected or forgotten child', async t => {
  const f = await fixture(t), store = await f.open();
  await store.registerCleanSession('parent');
  await store.protectSession('child', { categories: ['derived'] });
  assert.equal((await store.inheritSession('parent', 'child')).state, 'protected');
  await store.forgetSessions(['child']);
  assert.equal((await store.inheritSession('parent', 'child')).state, 'forgotten');
});

test('loss of either store or marker cannot be bypassed with initialize=true', async t => {
  for (const loss of ['database', 'marker']) {
    await t.test(loss, async t => {
      const f = await fixture(t), store = await f.open();
      await store.protectSession('existing', { categories: ['memory'] }); await store.close();
      await unlink(loss === 'database' ? f.database : f.config.markerPath);
      await assert.rejects(f.open(true), denied('PRIVACY_INITIALIZATION_INCOMPLETE'));
    });
  }
});

test('initialization interrupted after marker creation is never silently completed', async t => {
  const f = await fixture(t);
  await mkdir(join(f.root, 'host'), { recursive: true });
  await writeFile(f.config.markerPath, JSON.stringify({ schemaVersion: 1,
    instanceId: '11111111-1111-4111-8111-111111111111', revision: 0 }));
  await assert.rejects(f.open(true), denied('PRIVACY_INITIALIZATION_INCOMPLETE'));
});

test('invalid database, missing metadata, unsupported format and changed identity fail closed', async t => {
  for (const corruption of ['bytes', 'metadata', 'format', 'identity', 'marker']) {
    await t.test(corruption, async t => {
      const f = await fixture(t), store = await f.open();
      await store.protectSession('existing', { categories: ['memory'] }); await store.close();
      if (corruption === 'bytes') await writeFile(f.database, 'not sqlite');
      else if (corruption === 'marker') await writeFile(f.config.markerPath, '{broken');
      else {
        const db = new DatabaseSync(f.database);
        if (corruption === 'metadata') db.exec('DELETE FROM metadata');
        else if (corruption === 'format') db.exec('PRAGMA user_version=99');
        else db.exec("UPDATE metadata SET instance_id='different-instance'");
        db.close();
      }
      await assert.rejects(f.open(true), error => /^PRIVACY_(STORE|FORMAT|MARKER)/.test(error.code));
    });
  }
});

test('independent watermark prevents an older valid database from reviving a forgotten session', async t => {
  const f = await fixture(t), store = await f.open();
  await store.protectSession('s1', { categories: ['memory'] });
  const snapshot = join(f.root, 'old.sqlite');
  const source = new DatabaseSync(f.database, { readOnly: true });
  await backup(source, snapshot); source.close();
  await store.forgetSessions(['s1']); await store.close();
  await copyFile(snapshot, f.database);
  await assert.rejects(f.open(false), denied('PRIVACY_STORE_ROLLBACK'));
});

test('runtime marker loss blocks subsequent reads and authorization', async t => {
  const f = await fixture(t), store = await f.open();
  await store.registerCleanSession('s1');
  await unlink(f.config.markerPath);
  await assert.rejects(store.sessionState('s1'), denied('PRIVACY_MARKER_MISSING'));
  await assert.rejects(store.assertAllowed({ sessionId: 's1', endpoint }), denied('PRIVACY_MARKER_MISSING'));
});

test('runtime database removal does not keep authorizing from an open SQLite handle', async t => {
  const f = await fixture(t), store = await f.open();
  await store.registerCleanSession('s1');
  await unlink(f.database);
  await assert.rejects(store.assertAllowed({ sessionId: 's1', endpoint }), denied('PRIVACY_DATABASE_MISSING'));
});

test('loss of the live owner file cannot start a second store or continue the first', async t => {
  const f = await fixture(t), store = await f.open();
  await store.registerCleanSession('s1');
  await unlink(join(f.config.directory, 'privacy-owner.sqlite'));
  await assert.rejects(f.open(false), denied('PRIVACY_OWNER_MISSING'));
  await assert.rejects(store.assertAllowed({ sessionId: 's1', endpoint }), denied('PRIVACY_OWNER_MISSING'));
});

test('serialized concurrent protections retain every category and source', async t => {
  const f = await fixture(t), store = await f.open();
  const results = await Promise.all(['memory', 'activity', 'location', 'derived'].map((category, i) =>
    store.protectSession('s1', { categories: [category], sourceRefs: [`source-${i}`] })));
  assert.equal(new Set(results.map(row => row.revision)).size, 4);
  assert.deepEqual((await store.sessionState('s1')).categories, ['activity', 'derived', 'location', 'memory']);
  assert.equal((await store.sessionState('s1')).sourceRefs.length, 4);
  const marker = JSON.parse(await readFile(f.config.markerPath, 'utf8'));
  assert.equal(marker.revision, (await store.status()).revision);
});

test('only one store owns writes and an interrupted process releases ownership', async t => {
  const f = await fixture(t), store = await f.open();
  await assert.rejects(f.open(false), denied('PRIVACY_STORE_IN_USE'));
  await store.close();
  const moduleUrl = new URL('../store.mjs', import.meta.url).href;
  const script = `import { PrivacyStore } from ${JSON.stringify(moduleUrl)};
    const store=await PrivacyStore.open(JSON.parse(process.argv[1]));
    await store.protectSession('crash-session',{categories:['memory']});
    process.stdout.write('ready\\n');`;
  const child = spawn(process.execPath, ['--input-type=module', '-e', script, JSON.stringify(f.config)], { stdio: ['ignore', 'pipe', 'pipe'] });
  t.after(() => { if (child.exitCode === null) child.kill('SIGKILL'); });
  let output = '', stderr = '';
  child.stderr.on('data', value => { stderr += value; });
  await new Promise((resolve, reject) => {
    const timeout = setTimeout(() => reject(new Error('Child did not initialize: ' + stderr)), 5000);
    child.once('error', error => { clearTimeout(timeout); reject(error); });
    child.stdout.on('data', value => {
      output += value;
      if (output.includes('ready\n')) { clearTimeout(timeout); resolve(); }
    });
    child.once('exit', code => { clearTimeout(timeout); reject(new Error('Child exited before ready: ' + code + ' ' + stderr)); });
  });
  await assert.rejects(f.open(false), denied('PRIVACY_STORE_IN_USE'));
  const exited = once(child, 'exit'); child.kill('SIGKILL'); await exited;
  const reopened = await f.open(false);
  assert.equal((await reopened.sessionState('crash-session')).state, 'protected');
});

test('pending work is bounded and close drains already accepted writes', async t => {
  const f = await fixture(t, { maxPending: 2 }), store = await f.open();
  const writes = Array.from({ length: 8 }, (_, i) => store.protectSession(`s${i}`, { categories: ['memory'] }));
  const settled = Promise.allSettled(writes);
  await store.close();
  const values = await settled;
  assert.equal(values.filter(value => value.status === 'fulfilled').length, 2);
  assert.ok(values.filter(value => value.status === 'rejected').every(value => value.reason.code === 'PRIVACY_BUSY'));
  const reopened = await f.open(false);
  assert.equal((await reopened.sessionState('s0')).state, 'protected');
  assert.equal((await reopened.sessionState('s1')).state, 'protected');
  assert.equal((await reopened.sessionState('s2')).state, 'unknown');
});

test('invalid inputs cannot partially change protection and endpoint lists contain no secrets', async t => {
  const f = await fixture(t), store = await f.open();
  await store.registerCleanSession('s1');
  await assert.rejects(store.forgetSessions(['s1', '../invalid']), denied('PRIVACY_INVALID_SESSION'));
  assert.equal((await store.sessionState('s1')).state, 'known-clean');
  await assert.rejects(store.protectSession('s1', { categories: ['unknown'] }), denied('PRIVACY_INVALID_CATEGORIES'));
  await assert.rejects(store.protectSession('s1', { categories: ['memory'], sourceRefs: ['private prose containing spaces'] }), denied('PRIVACY_INVALID_SOURCES'));
  await assert.rejects(store.protectSession('s1', { categories: ['memory'], sourceRefs: Array.from({ length: 129 }, (_, i) => `ref-${i}`) }), denied('PRIVACY_SOURCE_LIMIT'));
  for (const url of ['https://fixture.invalid/v1?token=fictional', 'https://fictional:password@fixture.invalid/',
    'https://fixture.invalid/v1#fragment', 'file:///private/memory']) {
    await assert.rejects(store.grantEndpoint({ ...endpoint, url, categories: ['memory'] }), denied('PRIVACY_INVALID_ENDPOINT'));
  }
  await assert.rejects(store.grantEndpoint({ ...endpoint, apiKey: 'fictional', categories: ['memory'] }), denied('PRIVACY_INVALID_INPUT'));
  await store.grantEndpoint({ ...endpoint, categories: ['memory'] });
  assert.deepEqual(Object.keys((await store.listGrants())[0]).sort(), ['categories', 'configVersion', 'purpose', 'revision', 'url']);
  assert.equal(normalizeEndpoint({ ...endpoint, url: 'https://FIXTURE.invalid:443/v1' }).url, endpoint.url);
});

test('endpoint grant capacity rejects growth without deleting existing grants', async t => {
  const f = await fixture(t), store = await f.open();
  for (let i = 0; i < 256; i++) {
    await store.grantEndpoint({ ...endpoint, url: `https://fixture.invalid/${i}`, categories: ['memory'] });
  }
  await assert.rejects(store.grantEndpoint({ ...endpoint, categories: ['memory'] }), denied('PRIVACY_GRANT_LIMIT'));
  assert.equal((await store.listGrants()).length, 256);
  await store.revokeEndpoint({ ...endpoint, url: 'https://fixture.invalid/0' });
  await store.grantEndpoint({ ...endpoint, categories: ['memory'] });
  assert.equal((await store.listGrants()).length, 256);
});

test('forgetting a source blocks every referencing session and inherited fork atomically', async t => {
  const f = await fixture(t);
  let store = await f.open();
  await store.protectSession('mixed', { categories: ['memory'], sourceRefs: ['record-a', 'record-b'] });
  await store.protectSession('other-reference', { categories: ['activity'], sourceRefs: ['record-a'] });
  await store.protectSession('unrelated', { categories: ['memory'], sourceRefs: ['record-c'] });
  await store.inheritSession('mixed', 'fork');
  await store.grantEndpoint({ ...endpoint, categories: ['memory', 'activity'] });
  assert.equal((await store.forgetSources(['record-a'])).forgottenCount, 3);
  assert.equal((await store.forgetSources(['record-a'])).forgottenCount, 0);
  await store.close(); store = await f.open(false);
  for (const sessionId of ['mixed', 'other-reference', 'fork']) {
    await assert.rejects(store.assertAllowed({ sessionId, endpoint }), denied('PRIVACY_SESSION_FORGOTTEN'));
  }
  assert.equal((await store.assertAllowed({ sessionId: 'unrelated', endpoint })).allowed, true);
  assert.deepEqual((await store.sessionState('mixed')).sourceRefs, ['record-a', 'record-b']);
});

test('source overflow rejects the whole protection change instead of dropping lineage', async t => {
  const f = await fixture(t), store = await f.open();
  const refs = Array.from({ length: 128 }, (_, i) => `ref-${i}`);
  await store.protectSession('full', { categories: ['memory'], sourceRefs: refs });
  await assert.rejects(store.protectSession('full', { categories: ['location'], sourceRefs: ['overflow'] }), denied('PRIVACY_SOURCE_LIMIT'));
  assert.equal((await store.sessionState('full')).sourceRefs.length, 128);
  assert.deepEqual((await store.sessionState('full')).categories, ['memory']);
  await store.protectSession('child', { categories: ['derived'], sourceRefs: ['other'] });
  await assert.rejects(store.inheritSession('full', 'child'), denied('PRIVACY_SOURCE_LIMIT'));
  assert.deepEqual((await store.sessionState('child')).sourceRefs, ['other']);
  assert.equal((await store.forgetSources(['ref-127'])).forgottenCount, 1);
});

test('a late read cannot protect and disclose a previously forgotten source in another session', async t => {
  const f = await fixture(t);
  let store = await f.open();
  await store.grantEndpoint({ ...endpoint, categories: ['memory'] });
  await store.protectSession('early', { categories: ['memory'], sourceRefs: ['used-old-record'] });
  // The source can be in a pending fetch before any session has registered it.
  assert.equal((await store.forgetSources(['pending-old-record', 'used-old-record'])).forgottenCount, 1);
  await assert.rejects(store.protectSession('late', { categories: ['memory'], sourceRefs: ['pending-old-record'] }), denied('PRIVACY_SOURCE_FORGOTTEN'));
  await assert.rejects(store.protectSession('late-used', { categories: ['memory'], sourceRefs: ['used-old-record'] }), denied('PRIVACY_SOURCE_FORGOTTEN'));
  assert.equal((await store.sessionState('late')).state, 'unknown');
  await store.close(); store = await f.open(false);
  await assert.rejects(store.protectSession('late-after-restart', { categories: ['memory'], sourceRefs: ['pending-old-record'] }), denied('PRIVACY_SOURCE_FORGOTTEN'));
  await store.protectSession('new-record', { categories: ['memory'], sourceRefs: ['user-explicitly-recorded-new-id'] });
  assert.equal((await store.assertAllowed({ sessionId: 'new-record', endpoint })).allowed, true);
});
