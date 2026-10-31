import test from 'node:test';
import assert from 'node:assert/strict';
import { createServer } from 'node:http';
import { once } from 'node:events';
import { createNativeAdvanced, advancedFields } from '../native-advanced.mjs';
const schema = { uid: 1, refs: {
  1: { type: 'object', dict: { enabled: 2, count: 3, names: 4, choice: 6, key: 9, apiKeyEnv: 10, unsupported: 11 } },
  2: { type: 'boolean' }, 3: { type: 'number', meta: { min: 1 } }, 4: { type: 'array', inner: 5 }, 5: { type: 'string' },
  6: { type: 'union', list: [7, 8] }, 7: { type: 'const', value: 'one' }, 8: { type: 'const', value: 'two' },
  9: { type: 'string', meta: { role: 'secret' } }, 10: { type: 'string', meta: { role: 'credential-ref' } }, 11: { type: 'dict', inner: 5 },
} };
function row(ns = 'bash-sandbox') { return { ns, revision: 3, schema, value: { enabled: true, count: 3, names: [], choice: 'one', apiKeyEnv: 'FICTIONAL_SEARCH_KEY' }, secrets: [{ path: ['key'], set: true }] }; }
async function fixture(t, overrides = {}) {
  const calls = [];
  let finishInstall;
  const ctx = {
    settings: { describe: () => [{ ns: 'locale', value: { preference: 'en' } }] },
    settingsController: {
      describe: () => ({ writable: true, namespaces: [row(), row('not-exposed')] }),
      mutate: async (ns, ops, revision) => { calls.push(['mutate', ns, ops, revision]); if (revision !== 3) throw Object.assign(new Error('fictional-secret'), { code: 'settings/conflict' }); return { ...row(ns), revision: 4 }; },
    },
    credentialsController: { describe: refs => Object.fromEntries(refs.map(ref => [ref, { configured: true, writable: true }])), set: async (ref, value) => calls.push(['key', ref, value]), unset: async ref => calls.push(['unset', ref]) },
    pluginManager: {
      listPlugins: async () => [{ entryId: 'droy-privacy', patchId: 'droy-privacy', moduleName: '@droyagent/privacy', enabled: true, fiberPhase: 'active' }, { entryId: 'ui', patchId: 'ui', moduleName: '@deepseek-ai/dsh-client-ui-chat', enabled: true, fiberPhase: 'active' }],
      listBundles: async () => [],
      registries: async () => ({ registry: 'https://registry.fixture.invalid/', fallbackRegistries: ['https://fallback.fixture.invalid/'], resolved: 'https://pnpm.fixture.invalid/' }),
      listVersionExemptions: () => ({ exemptions: {}, warnings: [] }),
      inspect: async (spec, options) => { calls.push(['inspect', spec, options]); return { status: 'accepted', name: spec, bundle: true, description: 'Fixture' }; },
      setPluginEnabled: async (...args) => { calls.push(['enable', ...args]); return { changed: true, application: 'restart-required', stage: 'enable', target: args[0] }; },
      installBundle: (spec, options) => { calls.push(['install', spec, options]); return new Promise(resolve => { finishInstall = resolve; }); },
      cancelInstall: async id => { calls.push(['cancel', id]); return { status: 'too-late' }; },
      removeBundle: async name => { calls.push(['remove', name]); return { changed: false, application: 'failed', stage: 'remove', target: name, error: { code: 'operation-error', diagnostic: 'fictional-secret' }, packageResult: { output: 'fictional-secret', exitCode: 1 } }; },
    },
    accountController: { getState: async () => ({ status: 'signed-out', attempt: null, links: {} }), hasRunningAccountTasks: () => true, signOut: async () => calls.push(['signOut']),
      startSignIn: async (...args) => { calls.push(['signIn', ...args]); return { status: 'signed-out', attempt: { id: 'attempt', phase: 'waiting-browser', authorizeUrl: 'https://fixture.invalid/login' } }; } },
    ...overrides,
  };
  const owner = createNativeAdvanced(ctx);
  const server = createServer((req, res) => owner.handle(req, res, new AbortController().signal));
  server.listen(0, '127.0.0.1'); await once(server, 'listening'); t.after(() => { server.closeAllConnections(); server.close(); });
  const origin = `http://127.0.0.1:${server.address().port}`;
  return { calls, ctx, owner, finish: value => finishInstall(value), origin, async request(path = '', body) { const response = await fetch(origin + '/droy/native/advanced' + path, body === undefined ? {} : { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) }); return { status: response.status, data: await response.json() }; } };
}
test('schema projection keeps unsupported and secret fields visible without secret values', () => {
  const fields = advancedFields(row());
  assert.equal(fields.find(f => f.path[0] === 'unsupported').kind, 'unsupported');
  assert.deepEqual(fields.find(f => f.path[0] === 'key'), { path: ['key'], kind: 'secret', configured: true });
  assert.equal(fields.find(f => f.path[0] === 'apiKeyEnv').credentialRef, 'FICTIONAL_SEARCH_KEY');
  assert.deepEqual(fields.find(f => f.path[0] === 'choice').options, ['one', 'two']);
});
test('settings are allowlisted, leaf-only and revision fenced; conflicts do not expose provider errors', async t => {
  const f = await fixture(t);
  const described = await f.request();
  assert.deepEqual(described.data.result.settings.namespaces.map(s => s.ns), ['bash-sandbox']);
  const request = { ns: 'bash-sandbox', expectedRevision: 3, ops: [{ op: 'set', path: ['count'], value: 5 }] };
  assert.equal((await f.request('/mutate', request)).status, 200);
  for (const path of [[], ['key'], ['unsupported'], ['__proto__']]) assert.equal((await f.request('/mutate', { ...request, ops: [{ op: 'set', path, value: 'no' }] })).status, 400);
  assert.equal((await f.request('/mutate', { ...request, ns: 'not-exposed' })).status, 400);
  assert.equal((await f.request('/mutate', { ns: 'session-log-deepseek', expectedRevision: 1, ops: [{ op: 'set', path: ['enabled'], value: true }] })).status, 400);
  const conflict = await f.request('/mutate', { ...request, expectedRevision: 2 });
  assert.equal(conflict.status, 409); assert.equal(conflict.data.error.code, 'settings/conflict'); assert.ok(!JSON.stringify(conflict).includes('fictional-secret'));
});
test('credentials are restricted to mounted declared references and have independent writes', async t => {
  const f = await fixture(t);
  assert.equal((await f.request('/credentials', { operation: 'set', ref: 'UNRELATED_KEY', value: 'fictional-secret' })).status, 400);
  assert.equal((await f.request('/credentials', { operation: 'set', ref: 'FICTIONAL_SEARCH_KEY', value: 'fictional-secret' })).status, 200);
  assert.equal(f.calls.filter(c => c[0] === 'mutate').length, 0);
});
test('required plugins remain protected and management diagnostics never expose raw output', async t => {
  const f = await fixture(t);
  assert.equal((await f.request('/plugins/enable', { kind: 'plugin', id: 'droy-privacy', enabled: false })).status, 422);
  assert.equal(f.calls.length, 0);
  const enabled = await f.request('/plugins/enable', { kind: 'plugin', id: 'ui', enabled: false });
  assert.equal(enabled.data.result.application, 'restart-required');
  const removed = await f.request('/plugins/remove', { name: '@fixture/plugin' });
  assert.ok(!JSON.stringify(removed).includes('fictional-secret'));
  assert.equal((await f.request('/plugins')).data.result.plugins[1].presentation, 'web-ui');
});
test('install identity is not replayed; cancellation and settlement remain distinct', async t => {
  const f = await fixture(t), input = { spec: '@fixture/plugin', requestId: 'fixture-install' };
  assert.deepEqual(f.owner.pendingWork(), []);
  assert.equal((await f.request('/plugins/install', input)).data.result.state, 'running');
  const snapshot = f.owner.pendingWork(), pending = snapshot[0]; assert.ok(Object.isFrozen(snapshot)); assert.ok(pending instanceof Promise);
  await f.request('/plugins/install', input);
  assert.equal(f.owner.pendingWork()[0], pending); assert.equal(snapshot.length, 1);
  assert.equal(f.calls.filter(c => c[0] === 'install').length, 1);
  assert.equal((await f.request('/plugins/cancel', { requestId: input.requestId })).data.result.status, 'too-late');
  assert.equal((await f.request('/plugins/install-state', { requestId: input.requestId })).data.result.state, 'running');
  assert.equal(f.owner.pendingWork()[0], pending);
  f.finish({ changed: true, application: 'applied', stage: 'install', target: input.spec });
  await pending; assert.deepEqual(f.owner.pendingWork(), []);
  assert.equal((await f.request('/plugins/install-state', { requestId: input.requestId })).data.result.result.application, 'applied');
});

test('failed detached installation retires its same Promise and preserves the existing failed snapshot', async t => {
  const operation = Promise.withResolvers(), f = await fixture(t), input = { spec: '@fixture/plugin', requestId: 'observed-install-failure' };
  f.ctx.pluginManager.installBundle = () => operation.promise;
  const response = await f.request('/plugins/install', input); assert.equal(response.data.result.state, 'running');
  const pending = f.owner.pendingWork()[0]; assert.ok(pending instanceof Promise);
  operation.reject(new Error('fictional-private-install-failure')); await pending;
  assert.deepEqual(f.owner.pendingWork(), []);
  const failed = await f.request('/plugins/install-state', { requestId: input.requestId });
  assert.deepEqual(failed.data.result, { requestId: input.requestId, state: 'failed' });
});
test('account signout is guarded; login derives locale and loopback callback', async t => {
  const f = await fixture(t);
  assert.equal((await f.request('/account/sign-out', { timezoneOffsetSeconds: 28800 })).status, 409);
  assert.equal(f.calls.length, 0);
  await f.request('/account/sign-in', { timezoneOffsetSeconds: 28800 });
  assert.equal(f.calls[0][1].locale, 'en'); assert.equal(f.calls[0][2], f.origin); assert.equal(f.calls[0][3], 'web');
});

test('installed DSH schema envelopes and public method signatures match the adapter', async () => {
  const { createRequire } = await import('node:module');
  const require = createRequire(new URL('../../dsh/package.json', import.meta.url));
  for (const name of ['dsh-bash-sandbox', 'dsh-agent-loop', 'dsh-subagent', 'dsh-web', 'dsh-web-search-deepseek', 'dsh-web-deepseek-api']) {
    const module = require(`@deepseek-ai/${name}`), config = module.Config ?? module.default?.Config;
    const fields = advancedFields({ schema: config.toJSON(), value: {}, secrets: [] });
    assert.ok(fields.length > 0, name);
    assert.ok(fields.every(field => field.path.length > 0), name);
    if (['dsh-web-search-deepseek', 'dsh-web-deepseek-api'].includes(name)) {
      const mounted = advancedFields({ schema: config.toJSON(), value: { apiKeyEnv: 'FIXTURE_KEY' }, secrets: [{ path: ['apiKey'], set: true }] });
      assert.equal(mounted.find(field => field.path[0] === 'apiKey').configured, true);
      assert.equal(mounted.find(field => field.path[0] === 'apiKeyEnv').credentialRef, 'FIXTURE_KEY');
    }
  }
  const models = require('@deepseek-ai/dsh-tool-subagent/model-selection-settings');
  assert.equal(advancedFields({ schema: (models.Config ?? models.default?.Config).toJSON(), value: {}, secrets: [] }).find(f => f.path[0] === 'allowedModels').kind, 'array');
  for (const [pkg, namespace, calls] of [
    ['dsh-plugin-manager', 'pluginManager', [['setPluginEnabled', ['entry-id', false]], ['installBundle', ['@fixture/plugin', { requestId: 'fixture', saveExact: true, approvedBuilds: ['fixture-builder'], registry: null }]], ['cancelInstall', ['fixture']], ['inspect', ['@fixture/plugin', { registry: 'https://registry.fixture.invalid/' }]], ['listVersionExemptions', []], ['setVersionExemption', ['@fixture/plugin@1.2.3', '0.2.1-rc.4', true, true]]]],
    ['dsh-api-account-controller', 'account', [['startSignIn', [{ version: 'Droy Android', locale: 'zh', timezoneOffsetSeconds: 28800 }, 'http://127.0.0.1:1234', 'web']], ['signOut', [{ version: 'Droy Android', locale: 'en', timezoneOffsetSeconds: 0 }]]]],
  ]) {
    const { TYPERT } = require(`@deepseek-ai/${pkg}/typert`);
    for (const [method, args] of calls) {
      const descriptor = TYPERT.invocations.find(d => d.namespace === namespace && d.method === method);
      assert.ok(descriptor, `${namespace}/${method}`);
      descriptor.input({ strict: true }).create().parse(Object.fromEntries(descriptor.parameters.map((p, i) => [p.wire, args[i]])));
    }
  }
  const manager = require('@deepseek-ai/dsh-plugin-manager').default;
  assert.equal(typeof manager.prototype.registries, 'function');
});

const inlineSchema = { uid: 1, refs: {
  1: { type: 'object', dict: { apiKey: 2, apiKeyEnv: 3 } },
  2: { type: 'string', meta: { role: 'secret' } }, 3: { type: 'string', meta: { role: 'credential-ref' } },
} };
async function inlineFixture(t, options = {}) {
  const writes = [], sections = new Map(['web-search-deepseek', 'web-deepseek-api'].map(ns => [ns, { ns, revision: 3, schema: inlineSchema, value: { apiKeyEnv: 'FICTIONAL_SEARCH_KEY' }, secrets: [{ path: ['apiKey'], set: true }] }]));
  const f = await fixture(t, {
    settingsController: {
      describe: () => ({ writable: true, namespaces: [...sections.values()] }),
      mutate: async (ns, ops, revision) => {
        writes.push(['mutate', ns, ops, revision]);
        if (options.mutate) return options.mutate({ ns, ops, revision, sections });
        const old = sections.get(ns);
        if (old.revision !== revision) throw Object.assign(new Error('fixture-private-error'), { code: 'settings/conflict' });
        const saved = { ...old, revision: revision + 1, secrets: [{ path: ['apiKey'], set: false }] }; sections.set(ns, saved); return saved;
      },
    },
    credentialsController: { describe: refs => Object.fromEntries(refs.map(ref => [ref, { configured: true, writable: true }])), set: async (ref, value) => { writes.push(['credential', ref]); if (options.set) await options.set({ ref, value, sections }); }, unset: async ref => writes.push(['unset', ref]) },
  });
  return { ...f, writes, sections };
}
test('inline keys explicitly shadow credential writes; migration never reads or returns an old key', async t => {
  const f = await inlineFixture(t);
  const saved = await f.request('/credentials', { operation: 'set', ref: 'FICTIONAL_SEARCH_KEY', value: 'fixture-new-key' });
  assert.deepEqual(saved.data.result.overriddenByInline, ['web-search-deepseek', 'web-deepseek-api']);
  f.writes.length = 0;
  const migrated = await f.request('/credentials/inline', { ns: 'web-search-deepseek', expectedRevision: 3, operation: 'migrate', value: 'fixture-new-key' });
  assert.equal(migrated.data.result.status, 'completed');
  assert.equal(migrated.data.result.credential, 'committed'); assert.equal(migrated.data.result.inline, 'cleared');
  assert.deepEqual(f.writes, [['credential', 'FICTIONAL_SEARCH_KEY'], ['mutate', 'web-search-deepseek', [{ op: 'unset', path: ['apiKey'] }], 3]]);
  assert.ok(!JSON.stringify(migrated).includes('fixture-new-key'));
  const root = await f.request(); assert.ok(!JSON.stringify(root).includes('fixture-new-key'));
});
test('credential rejection or an uncertain key write keeps the inline key and never attempts cleanup', async t => {
  for (const known of [false, true]) {
    const f = await inlineFixture(t, { set: async () => { throw Object.assign(new Error('fixture-new-key'), known ? { code: 'credential/rejected' } : {}); } });
    const result = await f.request('/credentials/inline', { ns: 'web-deepseek-api', expectedRevision: 3, operation: 'migrate', value: 'fixture-new-key' });
    assert.equal(result.data.result.credential, known ? 'rejected' : 'unknown');
    assert.equal(result.data.result.inline, 'unchanged'); assert.equal(f.writes.length, 1);
    assert.ok(!JSON.stringify(result).includes('fixture-new-key'));
  }
});
test('a conflict after the credential commit can retry only explicit cleanup at the new revision', async t => {
  const f = await inlineFixture(t, { set: async ({ sections }) => { const old = sections.get('web-search-deepseek'); sections.set(old.ns, { ...old, revision: 4 }); } });
  const result = await f.request('/credentials/inline', { ns: 'web-search-deepseek', expectedRevision: 3, operation: 'migrate', value: 'fixture-new-key' });
  assert.equal(result.data.result.status, 'conflict'); assert.equal(result.data.result.credential, 'committed');
  const cleanup = await f.request('/credentials/inline', { ns: 'web-search-deepseek', expectedRevision: 4, operation: 'clear' });
  assert.equal(cleanup.data.result.status, 'completed'); assert.equal(cleanup.data.result.credential, 'not-requested');
  assert.equal(f.writes.filter(row => row[0] === 'credential').length, 1);
});
test('an uncertain inline unset reports uncertainty, while a higher-priority inline value stays overridden', async t => {
  for (const overridden of [false, true]) {
    const f = await inlineFixture(t, { mutate: async ({ ns, sections }) => {
      if (overridden) return { ...sections.get(ns), revision: 4 };
      const old = sections.get(ns); sections.set(ns, { ...old, revision: 4, secrets: [{ path: ['apiKey'], set: false }] });
      throw new Error('fixture-private-error');
    } });
    const result = await f.request('/credentials/inline', { ns: 'web-search-deepseek', expectedRevision: 3, operation: 'migrate', value: 'fixture-new-key' });
    assert.equal(result.data.result.status, overridden ? 'overridden' : 'unknown');
    assert.equal(result.data.result.credential, 'committed');
    assert.equal(result.data.result.inline, overridden ? 'configured' : 'unknown');
    assert.equal(f.writes.length, 2); assert.ok(!JSON.stringify(result).includes('fixture-private-error'));
  }
});
test('inline changes are fixed namespace/leaf/revision operations and reject stale drafts before writing keys', async t => {
  const f = await inlineFixture(t);
  for (const input of [
    { ns: 'bash-sandbox', expectedRevision: 3, operation: 'clear' },
    { ns: 'web-search-deepseek', expectedRevision: 3, operation: 'clear', value: 'fixture-new-key' },
    { ns: 'web-search-deepseek', expectedRevision: 3, operation: 'clear', path: ['apiKeyEnv'] },
    { ns: 'web-search-deepseek', expectedRevision: 3, operation: 'migrate', value: ' ' },
  ]) assert.equal((await f.request('/credentials/inline', input)).status, 400);
  const stale = await f.request('/credentials/inline', { ns: 'web-search-deepseek', expectedRevision: 2, operation: 'migrate', value: 'fixture-new-key' });
  assert.equal(stale.status, 409); assert.equal(f.writes.length, 0);
});
test('registry selection comes from the public manager catalog and binds inspection and installation', async t => {
  const f = await fixture(t);
  const listed = await f.request('/plugins');
  assert.equal(listed.data.result.registries.resolved, 'https://pnpm.fixture.invalid/');
  assert.equal((await f.request('/plugins/inspect', { spec: '@fixture/plugin', registry: 'https://outside.fixture.invalid/' })).status, 400);
  await f.request('/plugins/inspect', { spec: '@fixture/plugin', registry: 'https://fallback.fixture.invalid/' });
  assert.deepEqual(f.calls[0], ['inspect', '@fixture/plugin', { registry: 'https://fallback.fixture.invalid/' }]);
  await f.request('/plugins/install', { spec: '@fixture/plugin', registry: null, requestId: 'selected-registry' });
  await new Promise(resolve => setImmediate(resolve));
  assert.deepEqual(f.calls[1][2], { registry: null, requestId: 'selected-registry', saveExact: true });
  assert.equal((await f.request('/plugins/install', { spec: '@fixture/plugin', registry: 'https://fallback.fixture.invalid/', requestId: 'selected-registry' })).status, 400);
});
test('build scripts require explicit selected pending names, a matching source attempt, and a fresh identity', async t => {
  const f = await fixture(t), spec = '@fixture/plugin';
  await f.request('/plugins/install', { spec, requestId: 'blocked' });
  f.finish({ changed: false, application: 'failed', stage: 'install', target: spec, pendingBuilds: ['fixture-builder', '@fixture/native'], error: { code: 'operation-error' } });
  await new Promise(resolve => setImmediate(resolve));
  for (const extra of [
    { approvedBuilds: ['not-pending'], approvalFrom: 'blocked' },
    { approvedBuilds: ['fixture-builder'], approvalFrom: 'missing' },
    { approvedBuilds: ['fixture-builder'], approvalFrom: 'blocked', registry: null },
  ]) assert.equal((await f.request('/plugins/install', { spec, requestId: 'invalid-approval', ...extra })).status, 409);
  assert.equal(f.calls.filter(call => call[0] === 'install').length, 1);
  const approved = { spec, requestId: 'approved', approvedBuilds: ['@fixture/native'], approvalFrom: 'blocked' };
  await f.request('/plugins/install', approved); await f.request('/plugins/install', approved);
  assert.equal(f.calls.filter(call => call[0] === 'install').length, 2);
  assert.deepEqual(f.calls.at(-1)[2].approvedBuilds, ['@fixture/native']);
  f.finish({ changed: false, application: 'failed', stage: 'install', target: spec, error: { code: 'stale-approval' } });
  await new Promise(resolve => setImmediate(resolve));
  const settled = await f.request('/plugins/install-state', { requestId: 'approved' });
  assert.equal(settled.data.result.result.error.code, 'stale-approval');
  assert.equal((await f.request('/plugins/install', { ...approved, requestId: 'do-not-reuse-source' })).status, 409);
  assert.equal(f.calls.filter(call => call[0] === 'install').length, 2);
});
test('version exemptions use exact running app-boot version, explicit risk, known packages and actual receipts', async t => {
  const f = await fixture(t), grants = [];
  let exemptions = { '@fixture/old@1.0.0': ['0.1.0'] };
  f.ctx.pluginManager.listBundles = async () => [{ name: '@fixture/plugin', version: '1.2.3', enabled: false, installed: true, removable: true }];
  f.ctx.pluginManager.listVersionExemptions = () => ({ exemptions, warnings: [] });
  f.ctx.pluginManager.setVersionExemption = async (...args) => {
    grants.push(args); exemptions = args[2] ? { ...exemptions, [args[0]]: [args[1]] } : Object.fromEntries(Object.entries(exemptions).filter(([name]) => name !== args[0]));
    return { changed: true, application: 'restart-required', restartReason: 'no-hmr', stage: 'enable', target: args[0], enabled: args[2] };
  };
  const listed = (await f.request('/plugins')).data.result, runtimeVersion = listed.runtimeVersion;
  assert.ok(runtimeVersion); assert.deepEqual(listed.versionExemptions.exemptions, exemptions);
  const input = { packageVersion: '@fixture/plugin@1.2.3', runtimeVersion, enabled: true, acceptRisk: true };
  assert.equal((await f.request('/plugins/version-exemption', { ...input, acceptRisk: false })).status, 400);
  assert.equal((await f.request('/plugins/version-exemption', { ...input, runtimeVersion: '0.0.0' })).status, 409);
  assert.equal((await f.request('/plugins/version-exemption', { ...input, packageVersion: '@unknown/plugin@1.2.3' })).status, 400);
  assert.equal((await f.request('/plugins/version-exemption', { ...input, packageVersion: '@droyagent/privacy@1.2.3' })).status, 422);
  assert.equal(grants.length, 0);
  const granted = await f.request('/plugins/version-exemption', input);
  assert.equal(granted.data.result.application, 'restart-required'); assert.equal(granted.data.result.changed, true);
  assert.deepEqual(grants[0], ['@fixture/plugin@1.2.3', runtimeVersion, true, true]);
  const revoked = await f.request('/plugins/version-exemption', { packageVersion: '@fixture/old@1.0.0', runtimeVersion: '0.1.0', enabled: false, acceptRisk: false });
  assert.equal(revoked.data.result.application, 'restart-required');
  assert.deepEqual(grants[1], ['@fixture/old@1.0.0', '0.1.0', false, false]);
});

test('registry credentials and compatibility diagnostics are not returned to native state', async t => {
  const f = await fixture(t);
  f.ctx.pluginManager.registries = async () => ({ registry: 'https://fixture-user:fixture-private@registry.fixture.invalid/', fallbackRegistries: ['https://safe.fixture.invalid/'], resolved: 'https://fixture.invalid/?token=fixture-private' });
  f.ctx.pluginManager.listVersionExemptions = () => ({ exemptions: {}, warnings: ['fixture-private diagnostic'] });
  const result = await f.request('/plugins');
  assert.equal(result.data.result.registries.registry, null); assert.equal(result.data.result.registries.resolved, null);
  assert.deepEqual(result.data.result.registries.fallbackRegistries, ['https://safe.fixture.invalid/']);
  assert.deepEqual(result.data.result.versionExemptions.warnings, ['record-problem']);
  assert.ok(!JSON.stringify(result).includes('fixture-private'));
});
test('new approval and stale runtime errors follow the shared Chinese or English locale', async t => {
  for (const locale of ['zh', 'en']) {
    const f = await fixture(t, { settings: { describe: () => [{ ns: 'locale', value: { preference: locale } }] } });
    const approval = await f.request('/plugins/install', { spec: '@fixture/plugin', requestId: 'fixture-missing-source', approvalFrom: 'missing', approvedBuilds: ['fixture-builder'] });
    assert.equal(approval.data.error.code, 'NATIVE_ADVANCED_STALE_APPROVAL');
    assert.equal(/[\u4e00-\u9fff]/.test(approval.data.error.message), locale === 'zh');
    const version = await f.request('/plugins/version-exemption', { packageVersion: '@fixture/plugin@1.2.3', runtimeVersion: '0.0.0', enabled: true, acceptRisk: true });
    assert.equal(version.data.error.code, 'NATIVE_ADVANCED_RUNTIME_CHANGED');
    assert.equal(/[\u4e00-\u9fff]/.test(version.data.error.message), locale === 'zh');
  }
});

test('approved plugin catalog contains only the three bundled features and reports real availability', async t => {
  const { approvedPluginBundles } = await import('../native-advanced.mjs');
  const f = await fixture(t);
  f.ctx.pluginManager.listBundles = async () => [
    { name: approvedPluginBundles['agent-team'], version: 'fixture-version', enabled: false, availability: 'available' },
    { name: approvedPluginBundles['session-titles'], enabled: true, availability: 'available', readOnlyReason: 'fixture' },
    { name: approvedPluginBundles['persistent-terminal'], enabled: false, availability: 'missing' },
    { name: '@fixture/unapproved', enabled: true, availability: 'available' },
  ];
  const result = (await f.request('/approved-plugins')).data.result.plugins;
  assert.deepEqual(result.map(r => r.id), ['agent-team', 'session-titles', 'persistent-terminal']);
  assert.equal(result[0].writable, true); assert.equal(result[1].writable, false);
  assert.equal(result[2].available, false); assert.ok(!JSON.stringify(result).includes('@fixture/unapproved'));
});

test('approved toggles only change fixed bundles and reject stale or unavailable requests', async t => {
  const { approvedPluginBundles } = await import('../native-advanced.mjs');
  const f = await fixture(t);
  let enabled = false;
  f.ctx.pluginManager.listBundles = async () => [{ name: approvedPluginBundles['agent-team'], enabled, availability: 'available' }];
  f.ctx.pluginManager.setBundleEnabled = async (name, next) => { f.calls.push(['bundle', name, next]); enabled = next; return { changed: true, application: 'applied' }; };
  const changed = await f.request('/approved-plugins/enable', { id: 'agent-team', enabled: true, expectedEnabled: false });
  assert.equal(changed.status, 200); assert.equal(changed.data.result.plugins[0].enabled, true);
  assert.deepEqual(f.calls, [['bundle', approvedPluginBundles['agent-team'], true]]);
  assert.equal((await f.request('/approved-plugins/enable', { id: 'agent-team', enabled: false, expectedEnabled: false })).status, 409);
  assert.equal((await f.request('/approved-plugins/enable', { id: '@fixture/unapproved', enabled: true, expectedEnabled: false })).status, 400);
  assert.equal((await f.request('/approved-plugins/enable', { id: 'persistent-terminal', enabled: true, expectedEnabled: false })).status, 503);
  assert.equal(f.calls.length, 1);
});

test('approved toggle serializes in-flight changes and exposes failed application without success-shaped state', async t => {
  const { approvedPluginBundles } = await import('../native-advanced.mjs');
  const f = await fixture(t), apply = Promise.withResolvers();
  f.ctx.pluginManager.listBundles = async () => [{ name: approvedPluginBundles['agent-team'], enabled: false, availability: 'available' }];
  f.ctx.pluginManager.setBundleEnabled = async () => { f.calls.push(['bundle']); return apply.promise; };
  const first = f.request('/approved-plugins/enable', { id: 'agent-team', enabled: true, expectedEnabled: false });
  while (!f.calls.length) await new Promise(resolve => setImmediate(resolve));
  assert.equal((await f.request('/approved-plugins/enable', { id: 'agent-team', enabled: true, expectedEnabled: false })).status, 409);
  apply.resolve({ changed: false, application: 'failed', error: { code: 'fixture-failure', diagnostic: 'private' } });
  const result = await first;
  assert.equal(result.data.result.change.application, 'failed'); assert.equal(result.data.result.plugins[0].enabled, false);
  assert.ok(!JSON.stringify(result).includes('private'));
});
