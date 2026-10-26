import test from 'node:test';
import assert from 'node:assert/strict';
import { access, lstat, mkdir, mkdtemp, readFile, readlink, rename, rm, symlink, writeFile } from 'node:fs/promises';
import { join } from 'node:path';
import { tmpdir } from 'node:os';
import { fileURLToPath } from 'node:url';
import { prepareMobileProfile } from '../profile-storage.mjs';
import { mobilePackageManager } from '../package-manager.mjs';

async function fixture(t) {
  const root = await mkdtemp(join(tmpdir(), 'droy-profile-storage-'));
  t.after(() => rm(root, { recursive: true, force: true }));
  const home = join(root, 'home');
  const runtime = join(root, 'runtime-one');
  await mkdir(join(runtime, 'node_modules'), { recursive: true });
  await packageAt(join(runtime, 'node_modules'), '@droyagent/mobile');
  await packageAt(join(runtime, 'node_modules'), '@droyagent/media-input');
  await packageAt(join(runtime, 'node_modules'), '@droyagent/voice');
  for (const name of ['privacy', 'execution', 'memory']) await packageAt(join(runtime, 'node_modules'), `@droyagent/${name}`);
  return { root, home, runtime, profile: await prepareMobileProfile({ home, runtime }) };
}

async function packageAt(root, name, extra = {}, source = 'export default 42;\n') {
  const target = join(root, name);
  await mkdir(target, { recursive: true });
  await writeFile(join(target, 'package.json'), JSON.stringify({ name, version: '1.0.0', type: 'module', main: 'index.mjs', ...extra }));
  await writeFile(join(target, 'index.mjs'), source);
  return target;
}

test('profile restart preserves manifest, selected bundles, workspace settings, patch and installed files', async t => {
  const { profile, home, runtime } = await fixture(t);
  assert.equal((await lstat(join(profile, 'node_modules'))).isSymbolicLink(), false);
  const manifest = JSON.parse(await readFile(join(profile, 'package.json'), 'utf8'));
  manifest.dependencies['@fixture/mobile-plugin'] = '1.0.0';
  manifest.dsh.profile.bundles.push('@fixture/mobile-plugin');
  manifest.description = 'User configuration';
  const expected = JSON.stringify(manifest, null, 4) + '\n';
  const files = { 'package.json': expected, 'cordis.patch.yml': '# User settings\n[]\n', 'pnpm-workspace.yaml': 'packages: [.]\nnodeLinker: hoisted\n', 'pnpm-lock.yaml': 'lockfileVersion: 9\n' };
  for (const [name, value] of Object.entries(files)) await writeFile(join(profile, name), value);
  await packageAt(join(profile, 'node_modules'), '@fixture/mobile-plugin');
  await prepareMobileProfile({ home, runtime });
  for (const [name, value] of Object.entries(files)) assert.equal(await readFile(join(profile, name), 'utf8'), value);
  assert.equal(await readFile(join(profile, 'node_modules/@fixture/mobile-plugin/index.mjs'), 'utf8'), 'export default 42;\n');
});

test('legacy runtime symlink is detached and declared plugins and their dependency closure survive runtime replacement', async t => {
  const { profile, home, runtime, root } = await fixture(t);
  const manifest = JSON.parse(await readFile(join(profile, 'package.json'), 'utf8'));
  manifest.dependencies['@fixture/mobile-plugin'] = '1.0.0';
  await writeFile(join(profile, 'package.json'), JSON.stringify(manifest));
  await packageAt(join(runtime, 'node_modules'), '@fixture/mobile-plugin', { dependencies: { 'fixture-helper': '1.0.0' }, optionalDependencies: { 'fixture-not-installed': '1.0.0' } }, "import value from 'fixture-helper'; export default value;\n");
  await packageAt(join(runtime, 'node_modules'), 'fixture-helper', {}, 'export default 73;\n');
  await packageAt(join(runtime, 'node_modules'), 'untouched-runtime-package');
  await rm(join(profile, 'node_modules'), { recursive: true });
  await symlink(join(runtime, 'node_modules'), join(profile, 'node_modules'));
  await prepareMobileProfile({ home, runtime });
  assert.equal((await lstat(join(profile, 'node_modules'))).isDirectory(), true);
  await access(join(runtime, 'node_modules/untouched-runtime-package/package.json'));
  await assert.rejects(access(join(profile, 'node_modules/untouched-runtime-package')), { code: 'ENOENT' });
  const nextRuntime = join(root, 'runtime-two');
  await mkdir(nextRuntime);
  await packageAt(join(nextRuntime, 'node_modules'), '@droyagent/mobile');
  await packageAt(join(nextRuntime, 'node_modules'), '@droyagent/media-input');
  await packageAt(join(nextRuntime, 'node_modules'), '@droyagent/voice');
  for (const name of ['privacy', 'execution', 'memory']) await packageAt(join(nextRuntime, 'node_modules'), `@droyagent/${name}`);
  await rm(runtime, { recursive: true });
  await prepareMobileProfile({ home, runtime: nextRuntime });
  const loaded = await import(join(profile, 'node_modules/@fixture/mobile-plugin/index.mjs'));
  assert.equal(loaded.default, 73);
  assert.deepEqual(JSON.parse(await readFile(join(profile, 'package.json'), 'utf8')).dependencies, { ...manifest.dependencies,
    '@droyagent/mobile': `link:${join(nextRuntime, 'node_modules/@droyagent/mobile')}`,
    '@droyagent/media-input': `link:${join(nextRuntime, 'node_modules/@droyagent/media-input')}`,
    '@droyagent/voice': `link:${join(nextRuntime, 'node_modules/@droyagent/voice')}`,
    ...Object.fromEntries(['privacy', 'execution', 'memory'].map(name=>[`@droyagent/${name}`, `link:${join(nextRuntime, 'node_modules/@droyagent', name)}`])) });
});

test('incomplete legacy dependency migration retains the original symlink and user manifest', async t => {
  const { profile, home, runtime } = await fixture(t);
  const path = join(profile, 'package.json');
  const manifest = JSON.parse(await readFile(path, 'utf8'));
  manifest.dependencies['fixture-missing'] = '1.0.0';
  const expected = JSON.stringify(manifest);
  await writeFile(path, expected);
  await rm(join(profile, 'node_modules'), { recursive: true });
  await symlink(join(runtime, 'node_modules'), join(profile, 'node_modules'));
  await assert.rejects(prepareMobileProfile({ home, runtime }), /Cannot migrate missing profile dependency/);
  assert.equal(await readlink(join(profile, 'node_modules')), join(runtime, 'node_modules'));
  assert.equal(await readFile(path, 'utf8'), expected);
});

test('a prepared migration resumes after the old symlink was moved but before the new directory was installed', async t => {
  const { profile, home, runtime } = await fixture(t);
  const suffix = '00000000-0000-0000-0000-000000000001';
  const transaction = { staging: `.node_modules-migration-${suffix}`, previous: `.node_modules-legacy-${suffix}` };
  await rm(join(profile, 'node_modules'), { recursive: true });
  await symlink(join(runtime, 'node_modules'), join(profile, transaction.previous));
  await packageAt(join(profile, transaction.staging), 'fixture-preserved');
  await writeFile(join(profile, '.droy-module-migration.json'), JSON.stringify(transaction));
  await prepareMobileProfile({ home, runtime });
  await access(join(profile, 'node_modules/fixture-preserved/package.json'));
  await assert.rejects(access(join(profile, '.droy-module-migration.json')), { code: 'ENOENT' });
  await assert.rejects(lstat(join(profile, transaction.previous)), { code: 'ENOENT' });
});

test('bundled pnpm installs a local plugin offline and keeps it across restart and runtime path changes', { timeout: 30000 }, async t => {
  const { profile, home, runtime, root } = await fixture(t);
  const pnpmRoot = fileURLToPath(new URL('../../dsh/node_modules/pnpm/', import.meta.url));
  await symlink(pnpmRoot, join(runtime, 'node_modules/pnpm'));
  const helper = await packageAt(join(root, 'fixtures'), 'fixture-offline-helper', {}, 'export default 91;\n');
  const plugin = await packageAt(join(root, 'fixtures'), 'fixture-offline-plugin', { dependencies: { 'fixture-offline-helper': `file:${helper}` }, dsh: { bundle: { patch: './cordis.patch.yml' } } }, "import value from 'fixture-offline-helper'; export default value;\n");
  await writeFile(join(plugin, 'cordis.patch.yml'), '[]\n');
  const manager = mobilePackageManager({ runtime });
  assert.equal(manager.command, process.execPath);
  const { runProfilePnpm } = await import('../../dsh/node_modules/@deepseek-ai/dsh-plugin-manager/lib/types/operations.js');
  const installAnchor = fileURLToPath(new URL('../../dsh/node_modules/@deepseek-ai/dsh/package.json', import.meta.url));
  const installed = await runProfilePnpm({ dir: profile, profile: 'mobile', home, cwd: root, installAnchor }, ['add', `file:${plugin}`, '--offline', '--store-dir', join(root, 'store')], { ...manager, env: { ...manager.env, CI: 'true' }, execution: 'service', outputBytes: 16384, idleTimeoutMs: 10000, lookupTimeoutMs: 5000 });
  assert.equal(installed.exitCode, 0, installed.output);
  const manifestPath = join(profile, 'package.json');
  const manifest = JSON.parse(await readFile(manifestPath, 'utf8'));
  assert.ok(manifest.dsh.profile.bundles.includes('fixture-offline-plugin'), 'official plugin command selects the installed bundle');
  await assert.rejects(access(join(runtime, 'node_modules/fixture-offline-plugin')), { code: 'ENOENT' });
  const expected = await readFile(manifestPath, 'utf8');
  const lock = await readFile(join(profile, 'pnpm-lock.yaml'), 'utf8');
  await prepareMobileProfile({ home, runtime });
  const secondRuntime = join(root, 'runtime-two');
  await rename(runtime, secondRuntime);
  await prepareMobileProfile({ home, runtime: secondRuntime });
  assert.equal(await readFile(manifestPath, 'utf8'), expected.replaceAll(runtime, secondRuntime));
  assert.equal(await readlink(join(profile, 'node_modules/@droyagent/mobile')), join(secondRuntime, 'node_modules/@droyagent/mobile'));
  assert.equal(await readlink(join(profile, 'node_modules/@droyagent/media-input')), join(secondRuntime, 'node_modules/@droyagent/media-input'));
  assert.equal(await readFile(join(profile, 'pnpm-lock.yaml'), 'utf8'), lock);
  assert.equal((await import(join(profile, 'node_modules/fixture-offline-plugin/index.mjs'))).default, 91);
  assert.equal(mobilePackageManager({ runtime: secondRuntime }).args[0], join(secondRuntime, 'node_modules/pnpm/bin/pnpm.cjs'));
});

test('missing bundled pnpm is reported instead of falling back to a different version', () => {
  assert.throws(() => mobilePackageManager({ runtime: '/fictional/missing-runtime' }), /Bundled pnpm is missing/);
});
