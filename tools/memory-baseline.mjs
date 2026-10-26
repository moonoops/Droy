#!/usr/bin/env node
import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { readFile } from 'node:fs/promises';
import { createRequire } from 'node:module';
import { dirname, join, relative, resolve } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

export const repositoryRoot = resolve(dirname(fileURLToPath(import.meta.url)), '..');

const sources = [
  ['tools/mobile_profile.mjs', ['mobileProfilePatches', '@droyagent/mobile/lifecycle', '@droyagent/media-input'], 'profile composition'],
  ['tools/mobile_client_bundle.mjs', ['client'], 'client packaging'],
  ['runtime/mobile/lifecycle.mjs', ['llm/stream', 'tools.guard', 'sessions.flush'], 'stop and drain'],
  ['runtime/mobile/plugin.mjs', ['defineTool', 'attachments.saveImage', 'session/event'], 'Android tool results'],
  ['runtime/mobile/companion.mjs', ['system-prompt/assemble', 'systemPrompt.context'], 'bounded context assembly'],
  ['runtime/mobile/initialize.mjs', ['DSH_HOME'], 'durable data paths'],
  ['runtime/mobile/attachments.mjs', ['attachments'], 'attachment copies'],
  ['runtime/voice/http.mjs', ['fetch'], 'direct HTTP speech output'],
  ['runtime/voice/realtime.mjs', [], 'realtime audio/video entry'],
  ['runtime/mobile/pet-generation.mjs', ['fetch'], 'direct image-generation entry'],
  ['app/src/main/java/io/droyagent/app/ProductBridge.java', ['executeTool', 'checkPermission'], 'native authorization'],
  ['app/src/main/java/io/droyagent/app/ProductPermissions.java', ['enableAll', 'allowed'], 'ordinary capability defaults'],
  ['app/src/main/java/io/droyagent/app/BackgroundRuntime.java', ['keepRunning'], 'native background lifecycle'],
  ['app/src/main/java/io/droyagent/app/RuntimeService.java', [], 'Node service lifecycle'],
  ['android-use/src/main/java/io/droyagent/androiduse/DroyAccessibilityService.java', ['onAccessibilityEvent'], 'activity event source'],
  ['android-use/src/main/java/io/droyagent/androiduse/DeviceInfoTools.java', [], 'battery and network source'],
  ['android-use/src/main/java/io/droyagent/androiduse/PersonalDataTools.java', ['usageEvents'], 'bounded usage history source'],
  ['app/src/main/AndroidManifest.xml', [], 'Android permissions and service declarations'],
];

const packages = [
  '@deepseek-ai/dsh', '@deepseek-ai/cordis', '@deepseek-ai/dsh-tools',
  '@deepseek-ai/dsh-llm', '@deepseek-ai/dsh-session', '@deepseek-ai/dsh-system-prompt',
  '@deepseek-ai/dsh-session-persistence-jsonl', '@deepseek-ai/dsh-session-query',
  '@deepseek-ai/dsh-compaction-basic', '@deepseek-ai/dsh-subagent',
];

const json = async path => JSON.parse(await readFile(path, 'utf8'));
const sha256 = data => createHash('sha256').update(data).digest('hex');

export function validateFixture(fixture) {
  assert.equal(fixture.fixtureVersion, 1);
  assert.equal(fixture.synthetic, true, 'Only explicitly synthetic fixtures are permitted');
  assert.ok(Array.isArray(fixture.records) && fixture.records.length > 0);
  assert.equal(new Set(fixture.records.map(row => row.recordId)).size, fixture.records.length);
  for (const kind of ['preference', 'task_summary', 'event', 'fact'])
    assert.ok(fixture.records.some(row => row.kind === kind), `Missing fixture kind ${kind}`);
  assert.ok(fixture.records.some(row => row.payload?.eventType === 'app_activity'));
  assert.ok(fixture.records.some(row => row.payload?.eventType === 'location'));
  assert.ok(fixture.records.some(row => row.state === 'deleted' && row.payload === null));
  const duplicate = fixture.operations.filter(row => row.operationId === fixture.expected.duplicateOperationId);
  assert.equal(duplicate.length, 2);
  assert.deepEqual(duplicate[0], duplicate[1], 'Retry fixture must preserve operation identity and body');
  for (const record of fixture.records) {
    assert.equal(record.sourceRefs[0].nodeId, fixture.scope.nodeId);
    assert.ok(record.sourceRefs[0].sourceId.startsWith('fixture-'));
  }
  return { recordCount: fixture.records.length, operationCount: fixture.operations.length,
    deletedCount: fixture.records.filter(row => row.state === 'deleted').length };
}

export async function collectBaseline(root = repositoryRoot) {
  const declaration = await json(join(root, 'runtime/dsh/package.json'));
  const release = await json(join(root, 'runtime/release.json'));
  const nativeLock = await json(join(root, 'runtime/node-runtime.lock.json'));
  const installed = createRequire(join(root, 'runtime/dsh/package.json'));
  const dependencies = [];
  for (const name of packages) {
    const path = installed.resolve(`${name}/package.json`);
    const manifest = await json(path);
    dependencies.push({ name, version: manifest.version, manifest: relative(root, path) });
  }
  const dshVersion = dependencies.find(row => row.name === '@deepseek-ai/dsh').version;
  assert.equal(declaration.dependencies['@deepseek-ai/dsh'], dshVersion, 'Declared and installed DSH differ');
  assert.equal(release.dshVersion, dshVersion, 'Release and installed DSH differ');
  const entries = [];
  for (const [path, anchors, role] of sources) {
    const content = await readFile(join(root, path));
    const text = content.toString('utf8');
    const absent = anchors.filter(anchor => !text.includes(anchor));
    assert.deepEqual(absent, [], `${path} changed; review the integration contract`);
    entries.push({ path, role, sha256: sha256(content), anchors });
  }
  const fixture = await json(join(root, 'tools/fixtures/personal-memory/fixture.json'));
  const { readUpstreamProfile, mobileProfilePatches, applyEntryPatches } =
    await import(pathToFileURL(join(root, 'tools/mobile_profile.mjs')).href);
  const upstream = await readUpstreamProfile(join(root, 'runtime/dsh/node_modules'));
  const composed = applyEntryPatches(upstream, mobileProfilePatches(upstream),
    (message, id) => { throw new Error(`Profile contract failed: ${message}: ${id}`); });
  const activePlugins = composed.filter(row => row.disabled !== true).map(({ id, name }) => ({ id, name }));
  assert.ok(activePlugins.some(row => row.name === '@droyagent/mobile/lifecycle'));
  assert.ok(activePlugins.some(row => row.name === '@droyagent/mobile'));
  return {
    reportVersion: 1,
    scope: 'Source metadata and isolated host DSH contracts; no device or privacy acceptance claim',
    host: { node: process.versions.node, platform: process.platform, arch: process.arch },
    declaredAndroidRuntime: { architecture: nativeLock.architecture,
      node: nativeLock.packages.find(row => row.name === 'nodejs-lts')?.version ?? null,
      evidence: 'Package lock only; actual Android execution belongs to the storage probe' },
    dshVersion, dependencies, profile: { source: 'Installed base/web bundles plus mobile patches',
      activePlugins, booted: false }, sources: entries, fixture: validateFixture(fixture),
  };
}

export async function smokeDshContracts(root = repositoryRoot) {
  const installed = createRequire(join(root, 'runtime/dsh/package.json'));
  const load = name => import(pathToFileURL(installed.resolve(name)).href);
  const [{ Context }, { default: Tools, defineTool }, { default: Sessions },
    { default: Prompt }, { default: Llm, LlmAdapter }] = await Promise.all([
    load('@deepseek-ai/cordis'), load('@deepseek-ai/dsh-tools'), load('@deepseek-ai/dsh-session'),
    load('@deepseek-ai/dsh-system-prompt'), load('@deepseek-ai/dsh-llm'),
  ]);
  const ctx = new Context();
  let dispatched = 0;
  let interceptedSession;
  try {
    await ctx.plugin(Sessions);
    await ctx.plugin(Tools, { mode: 'native' });
    await ctx.plugin(Prompt);
    await ctx.plugin(Llm);
    const session = ctx.sessions.create('fixture-memory-baseline');
    assert.equal(typeof ctx.sessions.flush, 'function');
    assert.equal(typeof ctx.sessions.fork, 'function');
    ctx.tools.register(defineTool({ name: 'fixture_memory_echo', description: 'Synthetic local contract probe',
      parameters: {},
      output: { schema: { type: 'json' }, render: (_args, value) => [{ type: 'text', text: JSON.stringify(value) }] },
      execute() { dispatched++; return { synthetic: true }; },
    }));
    const deny = ctx.tools.guard(exec => exec.name === 'fixture_memory_echo' ? 'FIXTURE_DENIED' : undefined);
    const denied = await ctx.tools.execute({ name: 'fixture_memory_echo', arguments: {},
      callId: 'fixture-denied', signal: new AbortController().signal });
    assert.equal(dispatched, 0, 'DSH guard must prevent the tool body');
    assert.match(JSON.stringify(denied), /FIXTURE_DENIED/);
    deny();
    await ctx.tools.execute({ name: 'fixture_memory_echo', arguments: {},
      callId: 'fixture-allowed', signal: new AbortController().signal });
    assert.equal(dispatched, 1);
    ctx.systemPrompt.context({ name: 'fixture:memory-baseline', order: 110, required: true,
      text: 'Synthetic context only.' });
    const assembly = await ctx.systemPrompt.assemble({});
    assert.ok(assembly.contexts.some(row => row.name === 'fixture:memory-baseline'));
    class LocalAdapter extends LlmAdapter {
      async resolveModel(provider, id) { return { provider, id, name: 'Synthetic baseline', inputModalities: ['text'] }; }
      async *stream() { yield { type: 'finish', reason: { kind: 'stop' } }; }
    }
    ctx.llm.registerAdapter(['fixture-baseline'], new LocalAdapter());
    ctx.on('llm/stream', (options, next) => { interceptedSession = options.sessionId; return next(); });
    for await (const chunk of ctx.llm.stream({ provider: 'fixture-baseline', model: 'local-script',
      sessionId: session.id, messages: [], signal: new AbortController().signal })) {
      assert.notEqual(chunk.reason?.kind, 'error');
    }
    assert.equal(interceptedSession, session.id);
    return { realDshServices: true, syntheticModel: true, persistence: 'in-memory only',
      networkRequests: 0, checks: ['tool guard prevents dispatch', 'guard disposal restores dispatch',
        'context contribution is assembled', 'llm/stream retains explicit sessionId'],
      unproven: ['privacy enforcement', 'durability', 'fork protection inheritance', 'Android storage', 'model quality'] };
  } finally { await ctx.fiber.dispose(); }
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  try {
    assert.deepEqual(process.argv.slice(2), [], 'Usage: node tools/memory-baseline.mjs');
    const report = await collectBaseline();
    report.smoke = await smokeDshContracts();
    process.stdout.write(`${JSON.stringify(report, null, 2)}\n`);
  } catch (error) {
    process.stderr.write(`Memory baseline failed: ${error.message}\n`);
    process.exitCode = 1;
  }
}
