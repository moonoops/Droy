import test from 'node:test';
import assert from 'node:assert/strict';
import { createRequire, registerHooks } from 'node:module';
import { pathToFileURL } from 'node:url';
import * as policy from '../plugin-policy.mjs';
import { readUpstreamProfile, mobileProfilePatches, applyEntryPatches } from '../../../tools/mobile_profile.mjs';
import { fileURLToPath } from 'node:url';

const installed = createRequire(new URL('../../dsh/package.json', import.meta.url));
const hooks = registerHooks({ resolve(specifier, context, next) {
  if (specifier.startsWith('@deepseek-ai/')) return { url: pathToFileURL(installed.resolve(specifier)).href, shortCircuit: true };
  return next(specifier, context);
} });
const { Context } = await import('@deepseek-ai/cordis');
const { Loader, Group } = await import('@deepseek-ai/cordis-plugin-loader');
hooks.deregister();
const prohibited = '@deepseek-ai/dsh-compaction-tool-result-pruner';

async function fixture(t) {
  const ctx = new Context();
  t.after(() => ctx.fiber.dispose());
  ctx.provide('tokenMeter', {});
  await ctx.plugin(Loader, { baseUrl: new URL('../../dsh/package.json', import.meta.url).href });
  ctx.loader.builtins.group = Group;
  return ctx;
}

test('the real Loader rejects a user row and an attempt to reenable its disabled row', async t => {
  const ctx = await fixture(t);
  await ctx.plugin(policy);
  await assert.rejects(ctx.loader.root.create({ id: 'user-pruner', name: prohibited }), /prohibited in Droy/);
  assert.equal(ctx.get('toolResultPruner'), undefined);
  await ctx.loader.root.create({ id: 'disabled-pruner', name: prohibited, disabled: true });
  await assert.rejects(ctx.loader.update('disabled-pruner', { disabled: false }), /prohibited in Droy/);
  assert.equal(ctx.get('toolResultPruner'), undefined);
});

test('the real standard preset keeps its disabled pruner declaration without activating or rejecting it', async t => {
  const upstream = await readUpstreamProfile(fileURLToPath(new URL('../../dsh/node_modules/', import.meta.url)));
  const mobile = applyEntryPatches(upstream, mobileProfilePatches(upstream), message => assert.fail(message));
  const standard = mobile.find(row => row.id === 'preset-standard');
  const composition = standard.config.plugins.find(row => row.id === 'compaction');
  const pruner = composition.config.find(row => row.name === prohibited);
  assert.equal(pruner.disabled, true);
  const ctx = await fixture(t);
  await ctx.plugin(policy);
  await ctx.loader.root.create({ ...composition, config: [structuredClone(pruner)] });
  await ctx.loader.await();
  const group = ctx.loader.resolve('compaction').subgroup;
  const entry = group.tree.store[pruner.id];
  assert.ok(entry, 'the disabled declaration remains available to the configuration editor');
  assert.equal(entry.disabled, true);
  assert.equal(entry.fiber, undefined);
  assert.equal(group.ctx.get('toolResultPruner'), undefined);
});

test('the real Loader policy reaches later isolated preset-style groups', async t => {
  const ctx = await fixture(t);
  await ctx.plugin(policy);
  await ctx.loader.root.create({ id: 'late-preset-group', name: 'cordis:group', group: true,
    isolate: { toolResultPruner: true }, config: [{ id: 'nested-pruner', name: prohibited }] });
  await ctx.loader.await();
  const group = ctx.loader.resolve('late-preset-group').subgroup;
  assert.equal(group.ctx.get('toolResultPruner'), undefined);
  assert.equal(ctx.get('toolResultPruner'), undefined);
});

test('policy startup revokes earlier root and isolated instances before it provides readiness', async t => {
  const ctx = await fixture(t);
  await ctx.loader.root.create({ id: 'early-root', name: prohibited });
  await ctx.loader.root.create({ id: 'early-group', name: 'cordis:group', group: true,
    isolate: { toolResultPruner: true }, config: [{ id: 'early-nested', name: prohibited }] });
  await ctx.loader.await();
  const group = ctx.loader.resolve('early-group').subgroup;
  assert.ok(ctx.get('toolResultPruner'));
  assert.ok(group.ctx.get('toolResultPruner'));
  let writes = 0;
  ctx.loader.write = () => { writes++; };
  await ctx.plugin(policy);
  assert.ok(ctx.get('droyPluginPolicy'));
  assert.equal(ctx.get('toolResultPruner'), undefined);
  assert.equal(group.ctx.get('toolResultPruner'), undefined);
  assert.equal(ctx.loader.resolve('early-root').options.disabled, true);
  assert.equal(group.data[0].disabled, true);
  assert.equal(writes, 0, 'startup policy changes runtime admission without writing user configuration');
});

test('DSH voice input can activate and remains outside the prohibited plugins', async t => {
  const ctx = await fixture(t);
  await ctx.plugin(policy);
  const name = '@deepseek-ai/dsh-experimental-client-ui-voice-input';
  const { createRequire } = await import('node:module');
  assert.ok(createRequire(new URL('../../dsh/package.json', import.meta.url)).resolve(name));
  await ctx.loader.root.create({ id: 'old-voice-ui', name, disabled: true });
  await ctx.loader.update('old-voice-ui', { disabled: false });
  assert.ok(ctx.loader.resolve('old-voice-ui').fiber);
  assert.ok(!ctx.get('droyPluginPolicy').prohibitedPlugins.includes(name));
});
