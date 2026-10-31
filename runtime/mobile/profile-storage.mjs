import { cp, lstat, mkdir, readFile, readlink, rename, rm, symlink, writeFile } from 'node:fs/promises';
import { dirname, join, resolve } from 'node:path';
import { randomUUID } from 'node:crypto';
import { createRequire } from 'node:module';
import { resolveBuiltinPackage } from './versions.mjs';
const { parseDocument, isMap, isSeq } = createRequire(resolveBuiltinPackage('yaml'))('yaml');

const bundles = ['@deepseek-ai/dsh-base', '@deepseek-ai/dsh-web-app', '@droyagent/mobile'];
const dependencyFields = ['dependencies', 'devDependencies', 'optionalDependencies', 'peerDependencies'];
const packageName = /^(?:@[a-z0-9][a-z0-9._~-]*\/)?[a-z0-9][a-z0-9._~-]*$/;
const migrationFile = '.droy-module-migration.json';
const workspace = 'packages:\n  - .\n\nnodeLinker: hoisted\nautoInstallPeers: false\n';
const mobilePackage = '@droyagent/mobile';
const bundledPackages = [mobilePackage, '@droyagent/media-input', '@droyagent/voice',
  '@droyagent/privacy', '@droyagent/execution', '@droyagent/memory'];

async function optionalStat(path) {
  try { return await lstat(path); }
  catch (error) { if (error.code === 'ENOENT') return undefined; throw error; }
}

async function createOnce(path, text) {
  try { await writeFile(path, text, { flag: 'wx', mode: 0o600 }); }
  catch (error) { if (error.code !== 'EEXIST') throw error; }
}

function dependencyNames(manifest) {
  const names = new Set();
  for (const field of dependencyFields) {
    const entries = manifest[field];
    if (entries === undefined) continue;
    if (!entries || typeof entries !== 'object' || Array.isArray(entries)) throw new Error(`Invalid profile ${field}`);
    for (const name of Object.keys(entries)) {
      if (!packageName.test(name)) throw new Error(`Invalid profile dependency name: ${name}`);
      names.add(name);
    }
  }
  return [...names];
}

async function packageDirectory(name, from, modules) {
  // Resolve package directories without relying on an exported package.json.
  let current = from;
  while (true) {
    const candidate = join(current, 'node_modules', name);
    if (await optionalStat(join(candidate, 'package.json'))) return candidate;
    const parent = dirname(current);
    if (parent === current || current === dirname(modules)) break;
    current = parent;
  }
  const candidate = join(modules, name);
  return await optionalStat(join(candidate, 'package.json')) ? candidate : undefined;
}

async function preserveLegacyDependencies(manifest, modules, destination) {
  const queue = dependencyNames(manifest).filter(name => !bundledPackages.includes(name)).map(name => ({ name, from: dirname(modules), required: Object.hasOwn(manifest.dependencies ?? {}, name) || Object.hasOwn(manifest.devDependencies ?? {}, name) }));
  const copied = new Set();
  for (let next = queue.shift(); next; next = queue.shift()) {
    if (bundledPackages.includes(next.name)) continue;
    if (copied.has(next.name)) continue;
    const source = await packageDirectory(next.name, next.from, modules);
    if (!source) {
      // Preserve the original link on an incomplete migration, rather than
      // replacing it with a profile that silently lost an installed plugin.
      if (next.required) throw new Error(`Cannot migrate missing profile dependency: ${next.name}`);
      continue;
    }
    copied.add(next.name);
    const installed = JSON.parse(await readFile(join(source, 'package.json'), 'utf8'));
    const target = join(destination, next.name);
    await mkdir(dirname(target), { recursive: true, mode: 0o700 });
    await cp(source, target, { recursive: true, dereference: true });
    for (const name of dependencyNames(installed)) queue.push({ name, from: source, required: false });
  }
}

async function finishModuleMigration(profile) {
  const marker = join(profile, migrationFile);
  if (!await optionalStat(marker)) return;
  const transaction = JSON.parse(await readFile(marker, 'utf8'));
  for (const [key, prefix] of [['staging', '.node_modules-migration-'], ['previous', '.node_modules-legacy-']]) {
    if (typeof transaction[key] !== 'string' || !new RegExp(`^${prefix.replaceAll('.', '\\.')}[a-f0-9-]{36}$`).test(transaction[key])) throw new Error('Invalid profile module migration record');
  }
  const modules = join(profile, 'node_modules');
  const staging = join(profile, transaction.staging);
  const previous = join(profile, transaction.previous);
  const installed = await optionalStat(modules);
  if (installed?.isSymbolicLink()) await rename(modules, previous);
  else if (installed && !installed.isDirectory()) throw new Error('Profile node_modules must be a directory');
  if (!installed || installed.isSymbolicLink()) await rename(staging, modules);
  const old = await optionalStat(previous);
  if (old && !old.isSymbolicLink()) throw new Error('Legacy module migration source must be a symlink');
  if (old) await rm(previous);
  await rm(marker);
}

async function detachLegacyModules(profile, manifest) {
  // Complete a prepared copy if Android stopped the process during the swap.
  await finishModuleMigration(profile);
  const modules = join(profile, 'node_modules');
  const existing = await optionalStat(modules);
  if (!existing) { await mkdir(modules, { mode: 0o700 }); return; }
  if (!existing.isSymbolicLink()) {
    if (!existing.isDirectory()) throw new Error('Profile node_modules must be a directory');
    return;
  }
  const legacy = resolve(dirname(modules), await readlink(modules));
  const transaction = { staging: `.node_modules-migration-${randomUUID()}`, previous: `.node_modules-legacy-${randomUUID()}` };
  const staging = join(profile, transaction.staging);
  await mkdir(staging, { mode: 0o700 });
  let prepared = false;
  try {
    await preserveLegacyDependencies(manifest, legacy, staging);
    await writeFile(join(profile, migrationFile), JSON.stringify(transaction) + '\n', { flag: 'wx', mode: 0o600 });
    prepared = true;
    await finishModuleMigration(profile);
  } finally { if (!prepared) await rm(staging, { recursive: true, force: true }); }
}

async function bindBundledPackage(profile, manifest, runtime, name) {
  const target = join(resolve(runtime), 'node_modules', name);
  if (!await optionalStat(join(target, 'package.json'))) throw new Error(`Bundled Droy package is missing: ${name}`);
  const link = join(profile, 'node_modules', name);
  await mkdir(dirname(link), { recursive: true, mode: 0o700 });
  const existing = await optionalStat(link);
  if (existing && !existing.isSymbolicLink()) throw new Error('Droy mobile package must link to its bundled runtime');
  if (!existing || resolve(dirname(link), await readlink(link)) !== target) {
    const staged = `${link}-${randomUUID()}`;
    await symlink(target, staged);
    try { await rename(staged, link); }
    finally { await rm(staged, { force: true }); }
  }
  const spec = `link:${target}`;
  if (manifest.dependencies?.[name] !== spec) {
    manifest.dependencies = { ...manifest.dependencies, [name]: spec };
    const staged = join(profile, `.package-${randomUUID()}.json`);
    await writeFile(staged, JSON.stringify(manifest, null, 2) + '\n', { flag: 'wx', mode: 0o600 });
    try { await rename(staged, join(profile, 'package.json')); }
    finally { await rm(staged, { force: true }); }
  }
}

// Run before the engine loads persisted overrides. Local session persistence is unrelated.
async function disableSessionLogUpload(path) {
  const text = await readFile(path, 'utf8');
  const document = parseDocument(text, { customTags: [{ tag: 'tag:yaml.org,2002:js', resolve: value => value }] });
  if (document.errors.length) throw new Error('Cannot read mobile profile overrides');
  let changed = false;
  function visit(node) {
    if (isSeq(node)) { for (const item of node.items) visit(item); return; }
    if (!isMap(node)) return;
    if (node.get('id') === 'session-log-deepseek') {
      const config = node.get('config', true);
      if (config !== undefined && !isMap(config)) throw new Error('Session log upload override must be a mapping');
      if (!config || config.get('enabled') !== false) {
        node.setIn(['config', 'enabled'], false); changed = true;
      }
    }
    for (const pair of node.items) visit(pair.value);
  }
  visit(document.contents);
  if (!changed) return;
  const staged = path + '-' + randomUUID();
  await writeFile(staged, String(document), { flag: 'wx', mode: 0o600 });
  try { await rename(staged, path); }
  finally { await rm(staged, { force: true }); }
}

export async function prepareMobileProfile({ home, runtime }) {
  if (!home || !runtime) throw new Error('Droy profile requires its home and runtime directories');
  const profile = join(resolve(home), 'profiles', 'mobile');
  await mkdir(profile, { recursive: true, mode: 0o700 });
  const manifestPath = join(profile, 'package.json');
  await createOnce(manifestPath, JSON.stringify({ name: 'droy-mobile-profile', private: true, dependencies: {}, dsh: { profile: { bundles } } }, null, 2) + '\n');
  const manifest = JSON.parse(await readFile(manifestPath, 'utf8'));
  if (!Array.isArray(manifest.dsh?.profile?.bundles)) throw new Error('Mobile profile must declare dsh.profile.bundles');
  await detachLegacyModules(profile, manifest);
  // This carrier is outside DSH's published dependency graph. Keeping its
  // explicit link dependency also prevents pnpm from pruning it on installs.
  for (const name of bundledPackages) await bindBundledPackage(profile, manifest, runtime, name);
  await createOnce(join(profile, 'cordis.patch.yml'), '[]\n');
  await disableSessionLogUpload(join(profile, 'cordis.patch.yml'));
  await createOnce(join(profile, 'pnpm-workspace.yaml'), workspace);
  await mkdir(join(resolve(home), 'workspace'), { recursive: true, mode: 0o700 });
  return profile;
}
