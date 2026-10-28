import assert from 'node:assert/strict';
import { mkdtemp, rm } from 'node:fs/promises';
import { join } from 'node:path';
import { tmpdir } from 'node:os';
import { fileURLToPath } from 'node:url';
import { createServer } from 'node:http';
import { companionState } from './fixtures/companion-state.mjs';

// Real packaged profile; the bridge rejects before any host path or Root action.
const runtime = fileURLToPath(new URL('../../generated/dsh/', import.meta.url));
const home = await mkdtemp(join(tmpdir(), 'droy-memory-active-profile-'));
const token = 'fictional-active-profile-token-1234567890';
const reservation = createServer();
await new Promise(resolve => reservation.listen(0, '127.0.0.1', resolve));
const port = reservation.address().port; await new Promise(resolve => reservation.close(resolve));
const calls = []; let prepared;
const preparation = new Promise(resolve => { prepared = resolve; });
const memoryState = { settings: { enabled: false, recallEnabled: true, automaticExtraction: false, privacyEverActivated: true },
  root: { authorized: true, checkedAt: 1 }, execution: { ready: false, epoch: 'synthetic-epoch', reason: 'EXECUTION_NOT_READY' },
  activation: { pendingEnable: true, cleanupReady: true } };
const nativeBridge = createServer(async (req, res) => {
  assert.equal(req.headers.authorization, `Bearer ${token}`);
  let body = ''; for await (const chunk of req) body += chunk;
  const input = JSON.parse(body); calls.push({ path: req.url, operation: input.operation });
  if (req.url === '/memory' && input.operation === 'activation.prepare') {
    prepared(); res.writeHead(409, { 'content-type': 'application/json' });
    res.end(JSON.stringify({ ok: false, error: { code: 'SYNTHETIC_STOP_BEFORE_ROOT', message: 'Synthetic boundary reached; no Root action is performed.' } }));
    return;
  }
  if (req.url === '/memory' && input.operation === 'settings') Object.assign(memoryState.settings, input.patch);
  const result = req.url === '/memory' ? memoryState : req.url === '/history' ? { config: { activityEnabled: false, locationEnabled: false }, running: false }
    : req.url === '/clock' ? { now: Date.now(), timeZone: 'Asia/Shanghai' }
    : req.url === '/companion' ? companionState : { pluginActive: true, localEnabled: true, appsEnabled: false, channels: [] };
  res.writeHead(200, { 'content-type': 'application/json' }); res.end(JSON.stringify({ ok: true, result }));
});
await new Promise(resolve => nativeBridge.listen(0, '127.0.0.1', resolve));
Object.assign(process.env, { DSH_HOME: home, DROY_RUNTIME_DIR: runtime, DROY_LOCALE: 'zh',
  DROY_MEMORY_PROTECTION_REQUIRED: '1', DROY_WEB_TOKEN: token, DROY_BRIDGE_TOKEN: token,
  DROY_BRIDGE_PORT: String(nativeBridge.address().port), DROY_WEB_PORT: String(port), DSH_TELEMETRY_DISABLED: '1' });
let profile;
const watchdog = setTimeout(() => { console.error(JSON.stringify({ failure: 'PROFILE_ACTIVE_START_TIMEOUT', calls })); process.exit(1); }, 20000);
try {
  await import(new URL('../../generated/dsh/mobile/bootstrap.mjs', import.meta.url));
  const { runProfile } = await import(new URL('../../generated/dsh/node_modules/@deepseek-ai/dsh/lib/profile-boot.js', import.meta.url));
  const { createLaunchEnvironmentSnapshot } = await import(new URL('../../generated/dsh/node_modules/@deepseek-ai/dsh-launch-environment/lib/index.js', import.meta.url));
  const { mobilePackageManager } = await import(new URL('../../generated/dsh/mobile/package-manager.mjs', import.meta.url));
  profile = await runProfile({ profile: 'mobile', patchFiles: [], args: ['--no-open'], packageManager: mobilePackageManager(),
    environment: createLaunchEnvironmentSnapshot([{ source: 'process', values: process.env }]) });
  const { ctx } = profile;
  const reached = await Promise.race([preparation.then(() => true), new Promise(resolve => setTimeout(() => resolve(false), 3000))]);
  const hostEntry = [...ctx.loader.entries()].find(entry => entry.options.name === '@droyagent/mobile/memory-host');
  const ancestry = []; let parent = hostEntry?.fiber;
  while (parent && ancestry.length < 12) { ancestry.push({ name: parent.name, uid: parent.uid, state: parent.state }); if (parent === parent.parent.fiber) break; parent = parent.parent.fiber; }
  assert.equal(reached, true, JSON.stringify({ calls, dependencies: Object.fromEntries(['droyMemoryHost', 'droyPrivacy', 'droyExecution',
    'droyMemory', 'sessions', 'sessionPersistence', 'agentDefaultModel'].map(name => [name, !!ctx.get(name)])),
    pending: ctx.loader.getTasks().length, memoryEntries: [...ctx.loader.entries()].filter(entry => /memory|privacy|execution/.test(entry.options.name))
      .map(entry => ({ name: entry.options.name, disabled: entry.disabled, state: entry.fiber?.state })), ancestry,
    children: [...ctx.registry.values()].flatMap(runtime => [...runtime.fibers]).filter(fiber => fiber.parent.fiber === hostEntry?.fiber)
      .map(fiber => ({ name: fiber.name, uid: fiber.uid, state: fiber.state, inject: Object.keys(fiber.inject) })) }));
  assert.ok(!calls.some(call => ['protection-activate', 'initialization.prepare', 'host-ready'].includes(call.operation)));
  assert.equal((await ctx.get('droyPrivacy').status()).ready, false);
  console.log('PASS: active packaged profile reaches activation.prepare before any Root, store initialization or collection readiness');
} finally {
  clearTimeout(watchdog);
  await profile?.shutdown.shutdown(0);
  nativeBridge.closeAllConnections(); await new Promise(resolve => nativeBridge.close(resolve));
  await rm(home, { recursive: true, force: true });
}
