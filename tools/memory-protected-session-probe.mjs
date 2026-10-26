// Laboratory adapter only. This exercises the persistent privacy contract with
// installed DSH, without changing a production provider or the mobile profile.
import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { createServer } from 'node:http';
import { createRequire } from 'node:module';
import { mkdtemp, readFile, rm, unlink } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join, relative, resolve } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { PrivacyStore } from '../runtime/privacy/store.mjs';
import { normalizeEndpoint } from '../runtime/privacy/policy.mjs';

const installed = createRequire(new URL('../runtime/dsh/package.json', import.meta.url));
const load = name => import(pathToFileURL(installed.resolve(name)).href);
const MARKER = 'FICTIONAL_DROY_PROTECTED_SESSION_RECORD';
const PROVIDER = 'protected-session-fixture';
const VERSION_A = createHash('sha256').update('synthetic-route-config-a').digest('hex');
const VERSION_B = createHash('sha256').update('synthetic-route-config-b').digest('hex');
const purposes = ['conversation', 'session-title', 'compaction'];
const rejected = expected => error => error.code === expected;

export async function runProtectedSessionProbe() {
  const [{ Context }, { default: LlmRuntime, LlmAdapter, LlmError }] = await Promise.all([
    load('@deepseek-ai/cordis'), load('@deepseek-ai/dsh-llm'),
  ]);
  const root = await mkdtemp(join(tmpdir(), 'droy-protected-session-probe-'));
  const config = { directory: join(root, 'privacy'), markerPath: join(root, 'host', 'marker.json') };
  const ctx = new Context();
  const receipts = [], checks = [], releaseEvidence = [];
  let store, listening = false, currentCase, beforeDispatch, releasedBodies = 0;
  const receiver = createServer(async (req, res) => {
    try {
      let body = '';
      for await (const chunk of req) {
        body += chunk;
        if (Buffer.byteLength(body) > 65536) throw new Error('PROBE_BODY_TOO_LARGE');
      }
      const value = JSON.parse(body);
      // Content inspection exists only in the receiver's evidence collection;
      // neither source access nor endpoint authorization inspects the marker.
      receipts.push({ caseId: value.caseId, path: req.url, sessionId: value.sessionId,
        purpose: value.purpose, containsSyntheticMarker: body.includes(MARKER) });
      res.writeHead(200, { 'content-type': 'application/json' }); res.end('{"ok":true}');
    } catch { res.writeHead(400); res.end(); }
  });
  try {
    await new Promise((done, fail) => {
      receiver.once('error', fail);
      receiver.listen(0, '127.0.0.1', () => { receiver.off('error', fail); done(); });
    });
    listening = true;
    const origin = `http://127.0.0.1:${receiver.address().port}`;
    const originalRoute = Object.freeze({ url: origin + '/approved', configVersion: VERSION_A });
    let route = originalRoute;
    const endpoint = purpose => Object.freeze(normalizeEndpoint({ ...route, purpose }));
    const bindingKey = Symbol('host-issued-dispatch-binding');
    const issuedBindings = new WeakSet();
    const queryAuthority = Symbol('synthetic-host-query-authority');
    const sourceMetadata = new Map([
      ['fixture:record-a', { categories: ['memory', 'location'] }],
      ['fixture:record-b', { categories: ['memory'] }],
    ]);
    store = await PrivacyStore.open({ ...config, initialize: true });

    async function queryPrivateSource(sessionId, sourceRef, authority, purpose = 'conversation') {
      if (authority !== queryAuthority || !sourceMetadata.has(sourceRef)) {
        throw Object.assign(new Error('PROBE_QUERY_NOT_AUTHORIZED'), { code: 'PROBE_QUERY_NOT_AUTHORIZED' });
      }
      const queryEndpoint = endpoint(purpose);
      // Metadata identifies the source before its synthetic body is released.
      const protection = await store.protectSession(sessionId, {
        categories: sourceMetadata.get(sourceRef).categories, sourceRefs: [sourceRef],
      });
      const marker = JSON.parse(await readFile(config.markerPath, 'utf8'));
      const persisted = await store.sessionState(sessionId);
      assert.equal(persisted.state, 'protected');
      assert.ok(persisted.sourceRefs.includes(sourceRef));
      assert.ok(marker.revision >= protection.revision);
      await store.assertAllowed({ sessionId, endpoint: queryEndpoint });
      releaseEvidence.push({ sessionId, sourceRef, protectionRevision: protection.revision,
        markerRevisionBeforeBodyRelease: marker.revision, endpointAuthorizedBeforeBodyRelease: true,
        authorizedPurpose: queryEndpoint.purpose });
      releasedBodies++;
      return MARKER;
    }

    class BoundHttpFixtureAdapter extends LlmAdapter {
      async resolveModel(provider, id) {
        return { provider, id, name: 'Bound loopback privacy fixture', inputModalities: ['text'] };
      }
      async *stream(options) {
        const binding = options[bindingKey];
        assert.ok(binding && issuedBindings.has(binding), 'Missing host-issued dispatch binding');
        assert.equal(binding.sessionId, options.sessionId);
        assert.equal(binding.endpoint.purpose, options.purpose ?? 'conversation');
        assert.equal(new URL(binding.endpoint.url).origin, origin);
        // Check current revocation/state again at the final fixture boundary.
        // The destination is the captured URL, never a second provider lookup.
        try { await store.assertAllowed({ sessionId: binding.sessionId, endpoint: binding.endpoint }); }
        catch (error) { throw new LlmError('Privacy store denied fixture dispatch', error.code, { cause: error }); }
        const response = await fetch(binding.endpoint.url, {
          method: 'POST', headers: { 'content-type': 'application/json' }, redirect: 'error',
          body: JSON.stringify({ caseId: binding.caseId, sessionId: binding.sessionId,
            purpose: binding.endpoint.purpose, messages: options.messages }),
          signal: AbortSignal.timeout(3000),
        });
        assert.equal(response.status, 200); await response.json();
        yield { type: 'finish', reason: { kind: 'stop' } };
      }
    }
    await ctx.plugin(LlmRuntime);
    ctx.llm.registerAdapter([PROVIDER], new BoundHttpFixtureAdapter());
    ctx.on('llm/stream', (options, next) => (async function* () {
      const resolved = endpoint(options.purpose ?? 'conversation');
      await store.assertAllowed({ sessionId: options.sessionId, endpoint: resolved });
      const binding = Object.freeze({ sessionId: options.sessionId, endpoint: resolved, caseId: currentCase });
      issuedBindings.add(binding);
      // DSH can shallow-copy options during model resolution/projection. The
      // symbol survives those copies while the registered object remains fixed.
      Object.defineProperty(options, bindingKey, { value: binding, enumerable: true });
      if (beforeDispatch) await beforeDispatch();
      yield* next();
    })());

    async function send(sessionId, purpose = 'conversation', text = MARKER) {
      let last;
      for await (const chunk of ctx.llm.stream({ provider: PROVIDER, model: 'synthetic',
        ...(sessionId === undefined ? {} : { sessionId }), purpose,
        messages: [{ role: 'user', content: [{ type: 'text', text }] }] })) last = chunk;
      if (last?.reason?.kind === 'error') {
        const code = last.reason.failure.code;
        throw Object.assign(new Error(code), { code });
      }
      assert.equal(last?.reason?.kind, 'stop');
    }
    async function check(id, run) {
      currentCase = id;
      const before = receipts.length;
      const details = await run();
      checks.push({ id, status: 'pass', httpRequests: receipts.length - before, ...details });
    }
    async function deniedSend(sessionId, purpose, code) {
      const before = receipts.length;
      await assert.rejects(send(sessionId, purpose), rejected(code));
      assert.equal(receipts.length, before);
    }
    const grant = purpose => store.grantEndpoint({ ...endpoint(purpose), categories: ['memory', 'location'] });

    await check('query-requires-host-authority', async () => {
      await assert.rejects(queryPrivateSource('unauthorized-query', 'fixture:record-a'), rejected('PROBE_QUERY_NOT_AUTHORIZED'));
      assert.equal(releasedBodies, 0);
      assert.equal((await store.sessionState('unauthorized-query')).state, 'unknown');
      return { bodyReleased: false };
    });
    let body;
    await check('query-rejects-unauthorized-endpoint-before-body-release', async () => {
      await store.registerCleanSession('primary');
      await assert.rejects(queryPrivateSource('primary', 'fixture:record-a', queryAuthority), rejected('PRIVACY_ENDPOINT_NOT_AUTHORIZED'));
      assert.equal(releasedBodies, 0);
      assert.equal((await store.sessionState('primary')).state, 'protected');
      return { bodyReleased: false, persistedState: 'protected' };
    });
    await check('persist-protection-before-body-release', async () => {
      await grant('conversation');
      body = await queryPrivateSource('primary', 'fixture:record-a', queryAuthority);
      assert.equal(releasedBodies, 1);
      return { bodyReleased: true, persistedState: 'protected' };
    });
    for (const purpose of purposes) {
      await check(`purpose-${purpose}`, async () => {
        if (purpose === 'conversation') await store.revokeEndpoint(endpoint(purpose));
        await deniedSend('primary', purpose, 'PRIVACY_ENDPOINT_NOT_AUTHORIZED');
        await grant(purpose); await send('primary', purpose, body);
        assert.equal(receipts.at(-1).purpose, purpose);
        return { deniedHttpRequests: 0, authorizedHttpRequests: 1 };
      });
    }
    await check('missing-session-id', () => deniedSend(undefined, 'conversation', 'PRIVACY_INVALID_SESSION'));
    await check('unknown-session', () => deniedSend('unknown', 'conversation', 'PRIVACY_SESSION_UNKNOWN'));
    await check('fork-inherits-before-send', async () => {
      await deniedSend('fork', 'conversation', 'PRIVACY_SESSION_UNKNOWN');
      await store.inheritSession('primary', 'fork');
      assert.deepEqual((await store.sessionState('fork')).sourceRefs, ['fixture:record-a']);
      await send('fork'); return { inheritedSourceCount: 1 };
    });
    await check('endpoint-revocation', async () => {
      await store.revokeEndpoint(endpoint('conversation'));
      await deniedSend('primary', 'conversation', 'PRIVACY_ENDPOINT_NOT_AUTHORIZED');
      await grant('conversation');
    });
    await check('same-provider-changed-url', async () => {
      route = { ...originalRoute, url: origin + '/alternate' };
      await deniedSend('primary', 'conversation', 'PRIVACY_ENDPOINT_NOT_AUTHORIZED');
      await grant('conversation'); await send('primary');
      assert.equal(receipts.at(-1).path, '/alternate'); route = originalRoute;
      return { deniedHttpRequests: 0, newlyAuthorizedHttpRequests: 1, providerUnchanged: true };
    });
    await check('same-provider-changed-config-version', async () => {
      route = { ...originalRoute, configVersion: VERSION_B };
      await deniedSend('primary', 'conversation', 'PRIVACY_ENDPOINT_NOT_AUTHORIZED');
      await grant('conversation'); await send('primary'); route = originalRoute;
      return { deniedHttpRequests: 0, newlyAuthorizedHttpRequests: 1, providerUnchanged: true };
    });
    await check('dispatch-uses-authorized-endpoint-snapshot', async () => {
      beforeDispatch = async () => { route = { url: origin + '/never-authorized', configVersion: VERSION_B }; };
      try { await send('primary'); } finally { beforeDispatch = undefined; route = originalRoute; }
      assert.equal(receipts.at(-1).path, '/approved');
      return { mutableProviderRouteIgnoredAtDispatch: true };
    });
    await check('revocation-between-middleware-and-dispatch', async () => {
      beforeDispatch = () => store.revokeEndpoint(endpoint('conversation'));
      try { await deniedSend('primary', 'conversation', 'PRIVACY_ENDPOINT_NOT_AUTHORIZED'); }
      finally { beforeDispatch = undefined; }
      await grant('conversation');
    });
    await check('restart-restores-protection-and-grants', async () => {
      await store.close(); store = await PrivacyStore.open(config);
      assert.equal((await store.sessionState('primary')).state, 'protected');
      await send('primary'); await send('fork');
    });
    await check('source-forget-blocks-existing-and-late-reads', async () => {
      assert.equal((await store.forgetSources(['fixture:record-a'])).forgottenCount, 2);
      await deniedSend('primary', 'conversation', 'PRIVACY_SESSION_FORGOTTEN');
      await deniedSend('fork', 'conversation', 'PRIVACY_SESSION_FORGOTTEN');
      const before = releasedBodies;
      await assert.rejects(queryPrivateSource('late-read', 'fixture:record-a', queryAuthority), rejected('PRIVACY_SOURCE_FORGOTTEN'));
      assert.equal(releasedBodies, before);
      await store.inheritSession('primary', 'forgotten-fork');
      await deniedSend('forgotten-fork', 'conversation', 'PRIVACY_SESSION_FORGOTTEN');
      return { lateBodyReleased: false };
    });
    await check('forgotten-state-survives-restart', async () => {
      await store.close(); store = await PrivacyStore.open(config);
      await deniedSend('primary', 'conversation', 'PRIVACY_SESSION_FORGOTTEN');
    });
    const cachedBody = await queryPrivateSource('marker-case', 'fixture:record-b', queryAuthority);
    await check('missing-marker-blocks-cached-body-and-query', async () => {
      await unlink(config.markerPath);
      const before = receipts.length;
      await assert.rejects(send('marker-case', 'conversation', cachedBody), rejected('PRIVACY_MARKER_MISSING'));
      assert.equal(receipts.length, before);
      const releasesBefore = releasedBodies;
      await assert.rejects(queryPrivateSource('marker-late', 'fixture:record-b', queryAuthority), rejected('PRIVACY_MARKER_MISSING'));
      assert.equal(releasedBodies, releasesBefore);
      return { cachedBodyPresent: true, newBodyReleased: false };
    });
    await check('missing-marker-restart-cannot-reinitialize', async () => {
      await assert.rejects(store.close(), rejected('PRIVACY_MARKER_MISSING')); store = undefined;
      await assert.rejects(PrivacyStore.open({ ...config, initialize: true }), rejected('PRIVACY_INITIALIZATION_INCOMPLETE'));
    });
    assert.ok(receipts.every(receipt => receipt.containsSyntheticMarker));
    assert.ok(receipts.every(receipt => ['/approved', '/alternate'].includes(receipt.path)));
    const repository = fileURLToPath(new URL('../', import.meta.url));
    const sourceFiles = ['tools/memory-protected-session-probe.mjs', 'tools/tests/memory-protected-session-probe.test.mjs',
      'runtime/privacy/package.json', 'runtime/privacy/store.mjs', 'runtime/privacy/store-worker.mjs', 'runtime/privacy/policy.mjs']
      .map(path => join(repository, path));
    for (const name of ['@deepseek-ai/dsh-llm', '@deepseek-ai/cordis']) {
      sourceFiles.push(installed.resolve(name), installed.resolve(name + '/package.json'));
    }
    const sourceHashes = await Promise.all(sourceFiles.map(async path => ({ path: relative(repository, path),
      sha256: createHash('sha256').update(await readFile(path)).digest('hex') })));
    const manifest = JSON.parse(await readFile(installed.resolve('@deepseek-ai/dsh-llm/package.json'), 'utf8'));
    return { schemaVersion: 1, probe: 'P1-01 protected session integration', runtime: { node: process.version,
      platform: process.platform, dshVersion: manifest.version }, passed: true, checks, receipts, releaseEvidence,
      localHttpReceiptCount: receipts.length, allDestinationsLoopback: true, realPrivateDataUsed: false,
      contentMarkerUsedForAuthorization: false, productionProfileChanged: false, productionPrivacyReady: false,
      evidenceBoundary: 'Lab-bound HTTP adapter using the real PrivacyStore and installed DSH LlmRuntime. Title and compaction are purpose-labelled LlmRuntime calls, not their complete product workflows. The fixture binds an explicit destination snapshot; existing provider prepareCall/settings integration, all production outlets, in-flight request cancellation, mobile profile and OS isolation are not verified.',
      rerun: ['node tools/memory-protected-session-probe.mjs', 'node --test tools/tests/memory-protected-session-probe.test.mjs'],
      sourceRefs: ['runtime/privacy/store-worker.mjs: protect, authorize, inherit, forget-sources, checkIdentity',
        '@deepseek-ai/dsh-llm/lib/index.js: LlmRuntime.streamWithRegistration / llm/stream / adapterStream',
        'tools/memory-protected-session-probe.mjs: queryPrivateSource / BoundHttpFixtureAdapter'], sourceHashes };
  } finally {
    // A store intentionally failed by a negative case can also reject close;
    // terminate/dispose it without skipping the receiver and temp-dir cleanup.
    await Promise.allSettled([store?.close(), ctx.fiber.dispose()]);
    if (listening) {
      receiver.closeAllConnections();
      await new Promise((done, fail) => receiver.close(error => error ? fail(error) : done()));
    }
    await rm(root, { recursive: true, force: true });
  }
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  process.stdout.write(JSON.stringify(await runProtectedSessionProbe(), null, 2) + '\n');
}
