import test, { after } from 'node:test';
import assert from 'node:assert/strict';
import { createRequire, registerHooks, isBuiltin } from 'node:module';
import { mkdtemp, readFile, rm, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { pathToFileURL } from 'node:url';
import { patchRequiredLoader, patchRequiredPluginManagerPatch } from '../../privacy/upstream-patch.mjs';
import { assertRequiredPackage } from '../required-plugins.mjs';

const installed = createRequire(new URL('../../dsh/package.json', import.meta.url));
const root = await mkdtemp(join(tmpdir(), 'droy-required-plugins-'));
after(() => rm(root, { recursive: true, force: true }));
await writeFile(join(root, 'loader.mjs'), patchRequiredLoader(await readFile(installed.resolve('@deepseek-ai/cordis-plugin-loader'), 'utf8')));
const hooks = registerHooks({ resolve(specifier, context, next) {
  if (specifier === '@droyagent/mobile/required-plugins') return { url: new URL('../required-plugins.mjs', import.meta.url).href, shortCircuit: true };
  if (specifier.startsWith('@deepseek-ai/')) return { url: pathToFileURL(installed.resolve(specifier)).href, shortCircuit: true };
  return next(specifier, context);
} });
const { Context } = await import('@deepseek-ai/cordis');
const { Loader, Group } = await import(pathToFileURL(join(root, 'loader.mjs')).href);
hooks.deregister();

async function fixture(t) {
  const ctx = new Context();
  await ctx.plugin(Loader, { baseUrl: new URL('../../dsh/package.json', import.meta.url).href });
  ctx.loader.builtins.group = Group;
  const originalImport = ctx.loader.import.bind(ctx.loader);
  const synthetic = { name: 'synthetic-privacy-service', apply: child => child.provide('syntheticRequired', true) };
  const memory = { name: 'synthetic-memory-service', apply: child => child.provide('syntheticMemory', true) };
  ctx.loader.import = name => name === '@droyagent/privacy' ? synthetic : name === '@droyagent/memory' ? memory : originalImport(name);
  t.after(() => ctx.fiber.dispose());
  return ctx;
}

test('patched real Loader refuses disable, replacement, move and removal before any mutation', async t => {
  const ctx = await fixture(t);
  await ctx.loader.root.create({ id: 'droy-privacy', name: '@droyagent/privacy' });
  let writes = 0; ctx.loader.write = () => { writes++; };
  for (const patch of [{ disabled: true }, { name: '@fixture/replacement' }, { config: {} }, { isolate: { droyPrivacy: true } }]) {
    await assert.rejects(ctx.loader.update('droy-privacy', patch), /DROY_REQUIRED_PROTECTION_PLUGIN/);
  }
  await assert.rejects(ctx.loader.update('droy-privacy', {}, ''), /DROY_REQUIRED_PROTECTION_PLUGIN/);
  assert.throws(() => ctx.loader.remove('droy-privacy'), /DROY_REQUIRED_PROTECTION_PLUGIN/);
  assert.equal(writes, 0);
  assert.equal(ctx.get('syntheticRequired'), true);
  assert.deepEqual(ctx.loader.resolve('droy-privacy').options, { id: 'droy-privacy', name: '@droyagent/privacy' });
});

test('whole-group updates and invalid creates cannot bypass required protection', async t => {
  const ctx = await fixture(t);
  const row = { id: 'droy-privacy', name: '@droyagent/privacy' };
  await ctx.loader.root.update([row]);
  await assert.rejects(ctx.loader.root.update([]), /DROY_REQUIRED_PROTECTION_PLUGIN/);
  await assert.rejects(ctx.loader.create({ ...row, disabled: true }), /DROY_REQUIRED_PROTECTION_PLUGIN/);
  assert.equal(ctx.get('syntheticRequired'), true);
  assert.equal(ctx.loader.root.data.length, 1);
  await ctx.loader.root.update([structuredClone(row)]);
});

test('real profile enablement writer rejects protection changes before accessing profile files', async () => {
  const sourcePath = installed.resolve('@deepseek-ai/dsh-plugin-manager/package.json').replace('/package.json', '/lib/types/patch.js');
  const source = await readFile(sourcePath, 'utf8');
  const patched = patchRequiredPluginManagerPatch(source);
  assert.throws(() => patchRequiredPluginManagerPatch(patched), /input changed/);
  // Preserve original relative imports while loading a temporary reviewed module.
  const relocated = patched.replaceAll(/from '(\.\.?\/[^']+)'/g, (_whole, specifier) => `from '${new URL(specifier, pathToFileURL(sourcePath)).href}'`);
  await writeFile(join(root, 'profile-patch.mjs'), relocated);
  const hook = registerHooks({ resolve(specifier, context, next) {
    if (specifier === '@droyagent/mobile/required-plugins') return { url: new URL('../required-plugins.mjs', import.meta.url).href, shortCircuit: true };
    if (isBuiltin(specifier) || specifier.startsWith('file:') || specifier.startsWith('.')) return next(specifier, context);
    try { return { url: pathToFileURL(installed.resolve(specifier)).href, shortCircuit: true }; } catch { return next(specifier, context); }
  } });
  let writer; try { writer = await import(pathToFileURL(join(root, 'profile-patch.mjs')).href); } finally { hook.deregister(); }
  await assert.rejects(writer.writePluginEnabled({ get patchPath() { assert.fail('profile must not be read or modified'); } }, 'droy-privacy', '@droyagent/privacy', false), /DROY_REQUIRED_PROTECTION_PLUGIN/);
  for (const name of ['@droyagent/mobile', '@droyagent/privacy', '@droyagent/execution']) assert.throws(() => assertRequiredPackage(name, false), /DROY_REQUIRED_PROTECTION_PLUGIN/);
  assert.doesNotThrow(() => assertRequiredPackage('@fixture/ordinary', false));
});

test('explicit memory disable awaits capture stop while normal host disposal leaves preferences alone', async t => {
  const ctx = await fixture(t);
  let stops = 0, release;
  ctx.provide('droyMemoryHost', { featureStop: async () => { stops++; await new Promise(resolve => { release = resolve; }); } });
  await ctx.loader.root.update([{ id: 'droy-memory', name: '@droyagent/memory' }]);
  assert.throws(() => ctx.loader.remove('droy-memory'), /DROY_MEMORY_DISABLE_FIRST/);
  const entry = ctx.loader.resolve('droy-memory');
  const disabling = entry.update({ disabled: true });
  await new Promise(resolve => setImmediate(resolve));
  assert.equal(stops, 1);
  assert.equal(entry.options.disabled, undefined, 'stopping capture completes before plugin mutation');
  release(); await disabling;
  assert.equal(entry.options.disabled, true);
  assert.doesNotThrow(() => ctx.loader.remove('droy-memory'));

  const ordinaryShutdown = await fixture(t);
  let shutdownStops = 0;
  ordinaryShutdown.provide('droyMemoryHost', { featureStop: async () => { shutdownStops++; } });
  await ordinaryShutdown.loader.root.update([{ id: 'droy-memory', name: '@droyagent/memory' }]);
  await ordinaryShutdown.fiber.dispose();
  assert.equal(shutdownStops, 0);
});

test('whole-group memory removal also awaits feature stop', async t => {
  const ctx = await fixture(t);
  let stopped = false;
  ctx.provide('droyMemoryHost', { featureStop: async () => { stopped = true; } });
  await ctx.loader.root.update([{ id: 'droy-memory', name: '@droyagent/memory' }]);
  await ctx.loader.root.update([]);
  assert.equal(stopped, true);
  assert.equal(ctx.get('syntheticMemory'), undefined);
});

test('required provider failure does not persist an unrepairable disabled declaration', async t => {
  const ctx = await fixture(t);
  await ctx.loader.root.create({ id: 'droy-privacy', name: '@droyagent/privacy' });
  const entry = ctx.loader.resolve('droy-privacy');
  await entry.fiber.dispose();
  assert.notEqual(entry.options.disabled, true);
});
