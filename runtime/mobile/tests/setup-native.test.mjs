import test from 'node:test';
import assert from 'node:assert/strict';
import { createServer } from 'node:http';
import { once } from 'node:events';
import { createHash, randomUUID } from 'node:crypto';
import { mkdtemp, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { CompanionStore } from '../companions/store.mjs';
import { createSetupService, installSetupServices, setupUserProfileFingerprint, SetupError } from '../setup.mjs';

async function fixture(t, options = {}) {
  const directory = await mkdtemp(join(tmpdir(), 'droy-native-setup-'));
  const store = await CompanionStore.open({ directory });
  t.after(async () => { await store.close(); await rm(directory, { recursive: true, force: true }); });
  const selection = { provider: 'native-fixture', model: 'native-text' };
  const config = { apiKeyEnv: 'FIXTURE_SETUP_KEY', baseURL: 'https://fixture.invalid/v1' };
  const credential = { value: 'fictional-native-setup-key' };
  const calls = [], requests = [];
  const native = { completed: options.completed ?? false, source: 'usb', legacyStep: 4,
    permissionPolicy: Object.hasOwn(options, 'policy') ? options.policy : 'on_first_use', modelVerification: { passed: false, toolsPassed: false } };
  let modelFingerprint, confirmation;
  const ctx = {
    identityAvailable: true,
    settings: { describe: () => [{ ns: 'native-model', value: config }, { ns: 'locale', value: { preference: options.locale ?? 'en' } }] },
    get(name) {
      if (name === 'droyCompanions') return this.identityAvailable ? { store } : undefined;
      if (name === 'agentDefaultModel') return { currentSelection: () => ({ ...selection }) };
      if (name === 'credentials') return { resolve: async () => credential, readRecord: async () => undefined };
      if (name === 'settings') return this.settings;
      if (name === 'droyPrivacy') return { openSetupTestSession: () => ({ sessionId: randomUUID(), close() {} }) };
    },
    llm: {
      listProviders: () => [{ id: selection.provider, name: 'Fixture provider' }],
      listConfigurableProviders: () => [{ provider: selection.provider, settingsNs: 'native-model', settingsPath: [] }],
      listModels: async () => [{ id: selection.model, name: 'Fixture model' }],
      resolveModelInfo: async () => ({ inputModalities: ['text'] }),
      async *stream(request) {
        requests.push(request);
        if (!request.tools || options.noTools) {
          yield { type: 'text-delta', index: 0, text: 'Synthetic setup reply' };
          yield { type: 'finish', reason: { kind: 'stop' } }; return;
        }
        const value = JSON.parse(request.messages[0].content[0].text).value;
        yield { type: 'block-end', index: 0, block: { type: 'tool-call', id: 'echo-a', name: 'droy_setup_echo', arguments: JSON.stringify({ value }) } };
        yield { type: 'finish', reason: { kind: 'tool-calls' } };
      },
    },
  };
  const bridge = { async setup(input) {
    calls.push(structuredClone(input));
    if (input.operation === 'recordModel') { native.modelVerification = { ...input }; modelFingerprint = input.configFingerprint; }
    if (input.operation === 'checkModelFingerprint') return { matches: native.modelVerification.passed && input.configFingerprint === modelFingerprint };
    if (input.operation === 'nativeAcknowledgeIdentity') {
      assert.equal(input.profileFingerprint, setupUserProfileFingerprint(await store.getUserProfile()));
      confirmation = input.profileFingerprint;
    }
    if (input.operation === 'nativeStatus' || input.operation === 'nativeComplete') {
      const matches = native.modelVerification.passed && modelFingerprint === input.configFingerprint;
      const confirmed = input.profileReady && confirmation === input.profileFingerprint;
      const ready = matches && native.modelVerification.toolsPassed && confirmed && ['batch', 'on_first_use'].includes(native.permissionPolicy);
      if (input.operation === 'nativeComplete') {
        if (!native.completed && !ready) throw new SetupError('NATIVE_SETUP_NOT_READY', 'Setup requirements are incomplete.', 409);
        native.completed = true;
      }
      return { ...native, required: !native.completed, step: native.completed ? 5 : 1, canComplete: Boolean(ready),
        identity: { saved: input.profileReady, confirmed: Boolean(confirmed) } };
    }
    if (input.operation === 'complete') native.completed = true;
    return { ...native };
  } };
  const service = createSetupService(ctx, bridge);
  t.after(() => service.dispose());
  return { store, ctx, bridge, service, calls, requests, native, config, credential };
}

async function prepare(f) {
  await f.store.updateUserProfile({ name: 'Fixture user' });
  await f.service.test();
  await f.service.nativeConfirmIdentity({ expectedProfile: await f.store.getUserProfile() });
}

test('native completion binds a saved identity and current verified model while allowing first-use policy', async t => {
  const f = await fixture(t);
  await prepare(f);
  const result = await f.service.nativeComplete();
  assert.equal(result.state.completed, true);
  assert.equal(result.state.identity.confirmed, true);
  assert.deepEqual(result.state.identity.profile, await f.store.getUserProfile());
  assert.equal(result.state.permissionPolicy, 'on_first_use');
  assert.equal(f.requests.length, 2);
  assert.equal(f.calls.some(value => ['recordDevice', 'permission', 'root', 'accessibility'].includes(value.operation)), false);
  assert.ok(!JSON.stringify(result).includes(f.credential.value));
  assert.ok(!JSON.stringify(result).includes('configFingerprint'));
  assert.ok(!JSON.stringify(result).includes('profileFingerprint'));
});

test('native status observes identity without confirming it and never substitutes the welcome address', async t => {
  const f = await fixture(t);
  f.ctx.userAddress = 'An old welcome address';
  await f.service.test();
  const result = await f.service.nativeStatus();
  assert.equal(result.state.identity.saved, false);
  assert.equal(result.state.identity.confirmed, false);
  assert.equal(result.state.canComplete, false);
  assert.equal(f.calls.some(value => value.operation === 'nativeAcknowledgeIdentity'), false);
  await assert.rejects(f.service.nativeConfirmIdentity({ expectedProfile: await f.store.getUserProfile() }),
    error => error.code === 'NATIVE_SETUP_IDENTITY_REQUIRED');
  f.ctx.identityAvailable = false;
  await assert.rejects(f.service.nativeStatus(), error => error.code === 'NATIVE_SETUP_IDENTITY_UNAVAILABLE');
});

test('changing either saved name or avatar invalidates confirmation until the current profile is confirmed', async t => {
  const f = await fixture(t);
  await prepare(f);
  const old = await f.store.getUserProfile();
  await f.store.updateUserProfile({ name: 'Changed fixture name' });
  assert.equal((await f.service.nativeStatus()).state.identity.confirmed, false);
  await assert.rejects(f.service.nativeConfirmIdentity({ expectedProfile: old }), error => error.code === 'NATIVE_SETUP_IDENTITY_CHANGED');
  await assert.rejects(f.service.nativeComplete(), error => error.code === 'NATIVE_SETUP_NOT_READY');
  await f.service.nativeConfirmIdentity({ expectedProfile: await f.store.getUserProfile() });
  await f.store.putUserUpload({ id: 'avatar-two', name: 'avatar.png', mediaType: 'image/png', bytes: 4,
    ref: { synthetic: true }, url: '/droy/companions/user-profile/attachments/avatar-two' });
  await f.store.updateUserProfile({ avatarUrl: '/droy/companions/user-profile/attachments/avatar-two' });
  assert.equal((await f.service.nativeStatus()).state.identity.confirmed, false);
  await assert.rejects(f.service.nativeComplete(), error => error.code === 'NATIVE_SETUP_NOT_READY');
});

test('native completion rechecks credentials and endpoint instead of trusting a prior passed status', async t => {
  const f = await fixture(t);
  await prepare(f);
  assert.equal((await f.service.nativeStatus()).state.canComplete, true);
  f.credential.value = 'fictional-updated-native-key';
  await assert.rejects(f.service.nativeComplete(), error => error.code === 'NATIVE_SETUP_NOT_READY');
  assert.equal(f.native.completed, false);
  assert.equal(f.native.modelVerification.passed, false);
  await f.service.test();
  f.config.baseURL = 'https://changed.fixture.invalid/v1';
  await assert.rejects(f.service.nativeComplete(), error => error.code === 'NATIVE_SETUP_NOT_READY');
  assert.equal(f.native.completed, false);
});

test('text-only validation cannot complete native setup, and status never reruns model requests', async t => {
  const f = await fixture(t, { noTools: true });
  await prepare(f);
  assert.equal((await f.service.nativeStatus()).modelVerification.toolsPassed, false);
  await assert.rejects(f.service.nativeComplete(), error => error.code === 'NATIVE_SETUP_NOT_READY');
  assert.equal(f.requests.length, 2);
});

test('configuration changed while the saved identity is read cannot reuse an earlier model check', async t => {
  const f = await fixture(t);
  await prepare(f);
  const read = f.store.getUserProfile.bind(f.store);
  f.store.getUserProfile = async () => {
    const profile = await read();
    f.config.baseURL = 'https://changed-during-profile.fixture.invalid/v1';
    return profile;
  };
  await assert.rejects(f.service.nativeComplete(), error => error.code === 'NATIVE_SETUP_NOT_READY');
  assert.equal(f.native.completed, false);
  assert.equal(f.native.modelVerification.passed, false);
});

test('existing completed schema-one installation remains completed without new native proofs', async t => {
  const f = await fixture(t, { completed: true, policy: null });
  const result = await f.service.nativeStatus();
  assert.equal(result.state.completed, true);
  assert.equal(result.state.required, false);
  assert.equal(result.state.source, 'usb');
  assert.equal(result.state.legacyStep, 4);
  assert.equal(result.state.canComplete, false);
  assert.equal((await f.service.nativeComplete()).state.completed, true);
  assert.equal(f.native.legacyStep, 4);
});

test('a permission strategy must be explicitly selected, and deferring grants remains valid', async t => {
  const f = await fixture(t, { policy: null });
  await prepare(f);
  await assert.rejects(f.service.nativeComplete(), error => error.code === 'NATIVE_SETUP_NOT_READY');
  assert.equal(f.native.completed, false);
  f.native.permissionPolicy = 'on_first_use';
  assert.equal((await f.service.nativeComplete()).state.completed, true);
  assert.equal(f.calls.some(value => value.operation === 'permission'), false);
});

test('native setup errors follow the same saved Chinese and English language setting', async t => {
  const options = { locale: 'zh' };
  const f = await fixture(t, options);
  f.ctx.identityAvailable = false;
  await assert.rejects(f.service.nativeStatus(), error => error.code === 'NATIVE_SETUP_IDENTITY_UNAVAILABLE'
    && error.message === '用户资料暂不可用，请重试。');
  options.locale = 'en';
  await assert.rejects(f.service.nativeStatus(), error => error.code === 'NATIVE_SETUP_IDENTITY_UNAVAILABLE'
    && error.message === 'Your profile is unavailable. Try again.');
});

test('native identity input rejects forged saved facts and permits no model receipt or credential fields', async t => {
  const f = await fixture(t);
  for (const input of [{ completed: true }, { expectedProfile: { name: 'Forged', avatarUrl: 'https://fixture.invalid/avatar.png' } },
    { expectedProfile: { name: 'Fake', avatarUrl: '/droy/companions/assets/user-default.png', passed: true } },
    { expectedProfile: { name: 'Fake', avatarUrl: '/droy/companions/assets/user-default.png' }, profileReady: true }])
    await assert.rejects(f.service.nativeConfirmIdentity(input), error => error.code === 'INVALID_NATIVE_SETUP_REQUEST');
  assert.equal(f.calls.some(value => value.operation === 'nativeAcknowledgeIdentity'), false);
  assert.equal(f.requests.length, 0);
});

test('native HTTP routes authenticate, reject forged completion bodies and retain paired safe errors', async t => {
  const token = 'fictional-native-setup-session-1234567890';
  const previous = process.env.DROY_WEB_TOKEN; process.env.DROY_WEB_TOKEN = token;
  t.after(() => { if (previous === undefined) delete process.env.DROY_WEB_TOKEN; else process.env.DROY_WEB_TOKEN = previous; });
  const f = await fixture(t);
  const routes = new Map();
  f.ctx.webServer = { register: route => routes.set(route.path, route.handler) };
  installSetupServices(f.ctx, f.bridge);
  const server = createServer((req, res) => {
    const handler = routes.get(new URL(req.url, 'http://127.0.0.1').pathname);
    if (handler) handler(req, res); else { res.writeHead(404); res.end(); }
  });
  server.listen(0, '127.0.0.1'); await once(server, 'listening');
  t.after(async () => { server.closeAllConnections(); await new Promise(resolve => server.close(resolve)); });
  const base = `http://127.0.0.1:${server.address().port}`;
  const headers = { cookie: `droy_session=${token}`, 'content-type': 'application/json' };
  assert.equal((await fetch(`${base}/droy/setup/native/status`)).status, 401);
  assert.equal((await fetch(`${base}/droy/setup/native/status`, { headers: { ...headers, origin: 'https://untrusted.invalid' } })).status, 401);
  assert.equal((await fetch(`${base}/droy/setup/native/status?passed=true`, { headers })).status, 400);
  for (const body of [{ completed: true }, { passed: true, toolsPassed: true }, { configFingerprint: 'a'.repeat(64) }, { apiKey: 'fictional-invalid-input' }])
    assert.equal((await fetch(`${base}/droy/setup/native/complete`, { method: 'POST', headers, body: JSON.stringify(body) })).status, 400);
  assert.equal(f.calls.some(value => value.operation === 'nativeComplete'), false);
  await f.store.updateUserProfile({ name: 'Fixture user', bio: '好'.repeat(2000) });
  const identity = await fetch(`${base}/droy/setup/native/identity-confirm`, {
    method: 'POST', headers, body: JSON.stringify({ expectedProfile: await f.store.getUserProfile() }),
  });
  assert.equal(identity.status, 200);
  assert.equal((await identity.json()).state.identity.profile.bio, '好'.repeat(2000));
  f.ctx.identityAvailable = false;
  const failure = await fetch(`${base}/droy/setup/native/status`, { headers });
  assert.equal(failure.status, 503);
  const response = await failure.json();
  assert.equal(response.code, 'NATIVE_SETUP_IDENTITY_UNAVAILABLE');
  assert.match(response.message, /profile/i);
  assert.ok(!JSON.stringify(response).includes(f.credential.value));
});


test('biography is returned, confirmed, and invalidates a receipt when changed', async t => {
  const f = await fixture(t);
  await f.store.updateUserProfile({ name: 'Fixture user', bio: 'One line\nSecond line' });
  await f.service.test();
  const old = await f.store.getUserProfile();
  const confirmed = await f.service.nativeConfirmIdentity({ expectedProfile: old });
  assert.equal(confirmed.state.identity.profile.bio, old.bio);
  await f.store.updateUserProfile({ bio: 'Changed biography' });
  assert.equal((await f.service.nativeStatus()).state.identity.confirmed, false);
  await assert.rejects(f.service.nativeConfirmIdentity({ expectedProfile: old }), error => error.code === 'NATIVE_SETUP_IDENTITY_CHANGED');
  await assert.rejects(f.service.nativeComplete(), error => error.code === 'NATIVE_SETUP_NOT_READY');
  await f.service.nativeConfirmIdentity({ expectedProfile: await f.store.getUserProfile() });
  assert.equal((await f.service.nativeComplete()).state.completed, true);
  assert.equal(f.requests.length, 2, 'completion and profile checks do not run new model calls');
});

test('empty biography retains the existing identity fingerprint and omitted legacy bio stays compatible', async t => {
  const f = await fixture(t);
  const profile = { name: 'Fixture user', avatarUrl: '/droy/companions/assets/user-default.png' };
  const oldFingerprint = createHash('sha256').update(JSON.stringify(profile)).digest('hex');
  assert.equal(setupUserProfileFingerprint({ ...profile, bio: '' }), oldFingerprint);
  await f.store.updateUserProfile(profile);
  assert.equal((await f.service.nativeConfirmIdentity({ expectedProfile: profile })).state.identity.confirmed, true);
  await f.store.updateUserProfile({ bio: 'Existing biography' });
  await assert.rejects(f.service.nativeConfirmIdentity({ expectedProfile: profile }), error => error.code === 'NATIVE_SETUP_IDENTITY_CHANGED');
});

test('native confirmation rejects invalid biography text instead of dropping it', async t => {
  const f = await fixture(t);
  for (const bio of [null, 12, 'a'.repeat(2001), 'bad\u0000text', '\ud800']) {
    await assert.rejects(f.service.nativeConfirmIdentity({ expectedProfile: {
      name: 'Fixture user', avatarUrl: '/droy/companions/assets/user-default.png', bio,
    } }), error => error.code === 'INVALID_NATIVE_SETUP_REQUEST');
  }
  assert.equal(f.calls.some(value => value.operation === 'nativeAcknowledgeIdentity'), false);
});
