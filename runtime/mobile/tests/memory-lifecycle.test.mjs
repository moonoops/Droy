import test from 'node:test';
import assert from 'node:assert/strict';
import { createRequire } from 'node:module';
import { pathToFileURL } from 'node:url';
import { createMemoryFeatureLifecycle } from '../memory-lifecycle.mjs';

const installed = createRequire(new URL('../../dsh/package.json', import.meta.url));
const { Context } = await import(pathToFileURL(installed.resolve('@deepseek-ai/cordis')).href);
const { Loader } = await import(pathToFileURL(installed.resolve('@deepseek-ai/cordis-plugin-loader')).href);

async function fixture(t, options = {}) {
  const ctx = new Context();
  await ctx.plugin(Loader, { baseUrl: new URL('../../dsh/package.json', import.meta.url).href });
  const events = [];
  const host = { async featureStop() { events.push('stop'); } };
  const lifecycle = createMemoryFeatureLifecycle({ ctx, host, reportError: code => events.push(code) });
  const original = ctx.loader.import.bind(ctx.loader);
  ctx.loader.import = name => {
    if (name !== '@droyagent/memory') return original(name);
    if (options.importFails) throw new Error('synthetic module missing');
    return { name: 'synthetic-memory', async apply(child) {
      await options.wait;
      if (options.applyFails) throw new Error('synthetic startup failed');
      child.provide('droyMemory', {});
    } };
  };
  t.after(async () => { lifecycle.close(); await ctx.fiber.dispose(); });
  return { ctx, events, lifecycle, start: () => lifecycle.start(async () => events.push('host-ready')) };
}

test('parallel memory module startup settles before host-ready without clearing enabled preference', async t => {
  let release;
  const f = await fixture(t, { wait: new Promise(resolve => { release = resolve; }) });
  await f.ctx.loader.root.update([{ id: 'memory', name: '@droyagent/memory' }]);
  const startup = f.start();
  await new Promise(resolve => setImmediate(resolve));
  assert.deepEqual(f.events, []); assert.equal(f.lifecycle.available(), false);
  release(); await startup;
  assert.deepEqual(f.events, ['host-ready']); assert.equal(f.lifecycle.available(), true);
});

test('host injected startup does not become a Loader.await dependency cycle', { timeout: 5000 }, async t => {
  const ctx = new Context();
  await ctx.plugin(Loader, { baseUrl: new URL('../../dsh/package.json', import.meta.url).href });
  const events = []; let lifecycle, startTask, release;
  const wait = new Promise(resolve => { release = resolve; });
  ctx.provide('syntheticDependency', {});
  ctx.loader.import = name => name === '@droyagent/memory'
    ? { async apply(child) { await wait; child.provide('droyMemory', {}); } }
    : { apply(child) {
      lifecycle = createMemoryFeatureLifecycle({ ctx: child, host: { async featureStop() { events.push('stop'); } } });
      child.inject(['syntheticDependency'], () => { startTask = lifecycle.start(async () => events.push('host-ready')); });
      child.effect(() => () => lifecycle.close());
    } };
  t.after(() => ctx.fiber.dispose());
  await ctx.loader.root.update([{ id: 'host', name: '@fixture/host' }, { id: 'memory', name: '@droyagent/memory' }]);
  await new Promise(resolve => setImmediate(resolve)); assert.deepEqual(events, []);
  release(); await ctx.loader.await(); await startTask;
  assert.deepEqual(events, ['host-ready']); assert.equal(lifecycle.available(), true);
});

for (const mode of ['disabled', 'missing', 'import-failed', 'apply-failed']) {
  test(`startup ${mode} memory plugin stops capture before protection host-ready`, async t => {
    const f = await fixture(t, { importFails: mode === 'import-failed', applyFails: mode === 'apply-failed' });
    await f.ctx.loader.root.update(mode === 'missing' ? [] : [{ id: 'memory', name: '@droyagent/memory', disabled: mode === 'disabled' }]);
    await f.start();
    assert.deepEqual(f.events, ['stop', 'host-ready']); assert.equal(f.lifecycle.available(), false);
  });
}

test('runtime memory fiber disposal stops collection but normal engine teardown preserves preference', async t => {
  const f = await fixture(t);
  await f.ctx.loader.root.update([{ id: 'memory', name: '@droyagent/memory' }]); await f.start();
  await f.ctx.loader.resolve('memory').fiber.dispose(); await f.lifecycle.drain();
  assert.deepEqual(f.events, ['host-ready', 'stop']); assert.equal(f.lifecycle.available(), false);

  const shutdown = await fixture(t);
  await shutdown.ctx.loader.root.update([{ id: 'memory', name: '@droyagent/memory' }]); await shutdown.start();
  await shutdown.ctx.fiber.dispose(); await shutdown.lifecycle.drain();
  assert.deepEqual(shutdown.events, ['host-ready']);
});
