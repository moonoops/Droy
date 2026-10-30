import { TextDecoder } from 'node:util';
import { createRequire } from 'node:module';
import { pathToFileURL } from 'node:url';
import { resolveMobileLocale } from './locale.mjs';
import { resolveBuiltinPackage } from './versions.mjs';
import { assertRequiredPluginEnabled, assertRequiredPackage, stopMemoryFeature, requiredProtectionPlugins } from './required-plugins.mjs';

const base = '/droy/native/advanced';
export const advancedNamespaces = new Set(['bash-sandbox', 'pwsh-sandbox', 'agent-loop', 'subagent', 'subagent-model-selection-settings', 'web', 'web-search-deepseek', 'web-deepseek-api']);
export const approvedPluginBundles = Object.freeze({
  'agent-team': '@deepseek-ai/dsh-experimental-agent-team-profile',
  'session-titles': '@deepseek-ai/dsh-experimental-session-titles-bundle',
  'persistent-terminal': '@deepseek-ai/dsh-experimental-terminal-bundle',
});
const unsafe = new Set(['__proto__', 'prototype', 'constructor']);
const inlineNamespaces = new Set(['web-search-deepseek', 'web-deepseek-api']);
const packageName = /^(?:@[a-z0-9][a-z0-9._-]*\/)?[a-z0-9][a-z0-9._-]*$/;
// Resolve the same fixed installation's public API that PluginManager uses.
const bootRequire = createRequire(resolveBuiltinPackage('@deepseek-ai/dsh-app-boot'));
async function currentRuntimeVersion() {
  const boot = await import(pathToFileURL(bootRequire.resolve('@deepseek-ai/dsh-app-boot')).href);
  return boot.getDshRuntimeVersion();
}
const messages = {
  invalid: ['请求格式无效。', 'The request is invalid.'],
  unavailable: ['此服务尚未就绪。', 'This service is not ready.'],
  conflict: ['设置已变化。草稿已保留，请重新载入后核对。', 'Settings changed. Your draft is retained. Reload and review it.'],
  rejected: ['操作未应用，请检查输入或插件状态。', 'The operation was not applied. Check the input or plugin state.'],
  tasks: ['账号仍有运行中的任务，请先停止或等待完成后退出。', 'Account tasks are running. Stop them or wait before signing out.'],
  failed: ['操作未完成。请刷新状态后再决定是否重试。', 'The operation did not complete. Refresh its state before retrying.'],
  approval: ['待批准脚本已变化，请重新检查安装结果。', 'Pending scripts changed. Review the installation result again.'],
  runtime: ['运行版本已变化，请刷新后重新确认。', 'The runtime version changed. Refresh and confirm again.'],
};
class Failure extends Error { constructor(code, status = 400, copy = 'invalid') { super(code); Object.assign(this, { code, status, copy }); } }
const bad = () => new Failure('NATIVE_ADVANCED_INVALID');
const object = v => v !== null && typeof v === 'object' && !Array.isArray(v);
const get = (ctx, name) => typeof ctx.get === 'function' ? ctx.get(name) : ctx[name];
function service(ctx, name) { const value = get(ctx, name); if (!value) throw new Failure('NATIVE_ADVANCED_UNAVAILABLE', 503, 'unavailable'); return value; }
function shape(value, keys, optional = []) { if (!object(value) || Object.keys(value).some(k => !keys.includes(k)) || keys.filter(k => !optional.includes(k)).some(k => !Object.hasOwn(value, k))) throw bad(); }
function text(value, limit = 256) { if (typeof value !== 'string' || !value.trim() || value.length > limit || /[\x00-\x1f\x7f]/.test(value) || unsafe.has(value)) throw bad(); return value; }
function at(value, path) { return path.reduce((item, key) => object(item) ? item[key] : undefined, value); }
const same = (a, b) => JSON.stringify(a) === JSON.stringify(b);

/** Interpret only reviewed schema shapes; unknown forms stay visible and read-only. */
export function advancedFields(section) {
  const envelope = section.schema ?? {}, refs = envelope.refs ?? {}, result = [];
  const blocked = section.secrets ?? [];
  function supportedArrayItem(id, seen = new Set()) {
    const node = refs[id]; if (!node || seen.has(id) || node.meta?.role === 'secret') return false;
    if (['boolean', 'number', 'string', 'const'].includes(node.type)) return true;
    if (node.type === 'object' && object(node.dict)) return Object.values(node.dict).every(child => supportedArrayItem(child, new Set(seen).add(id)));
    if (node.type === 'union') return node.list?.every(child => refs[child]?.type === 'const');
    return false;
  }
  function walk(id, path, seen = new Set()) {
    const node = refs[id] ?? (object(id) ? id : {}), meta = node.meta ?? {};
    if (path.length > 16 || seen.has(id)) { result.push({ path, kind: 'unsupported' }); return; }
    const next = new Set(seen).add(id);
    if (meta.role === 'secret' || blocked.some(row => row.path.length <= path.length && row.path.every((v, i) => path[i] === v))) {
      result.push({ path, kind: 'secret', configured: blocked.some(row => same(row.path, path) && row.set) }); return;
    }
    if (node.type === 'object' && object(node.dict)) { for (const [key, child] of Object.entries(node.dict)) if (!unsafe.has(key)) walk(child, [...path, key], next); return; }
    if (node.type === 'intersect' && Array.isArray(node.list)) { for (const child of node.list) walk(child, path, next); return; }
    if (!path.length) { result.push({ path, kind: 'unsupported' }); return; }
    const value = at(section.value, path);
    const options = node.type === 'union' ? node.list?.map(id => refs[id]).filter(Boolean) : undefined;
    let kind = ['boolean', 'number', 'string'].includes(node.type) ? node.type : 'unsupported';
    if (options?.length && options.every(n => n.type === 'const' && ['string', 'number', 'boolean'].includes(typeof n.value))) kind = 'enum';
    const inner = refs[node.inner];
    if (node.type === 'array' && supportedArrayItem(node.inner) && !blocked.some(row => path.every((v, i) => row.path[i] === v))) kind = 'array';
    result.push({ path, kind, ...(value === undefined ? {} : { value }), ...(kind === 'enum' ? { options: options.map(n => n.value) } : {}),
      ...(kind === 'array' ? { itemKind: inner.type } : {}), ...(meta.role === 'credential-ref' ? { credentialRef: typeof value === 'string' ? value : null } : {}),
      ...(typeof meta.min === 'number' ? { min: meta.min } : {}), ...(typeof meta.max === 'number' ? { max: meta.max } : {}),
      ...(typeof meta.step === 'number' ? { step: meta.step } : {}) });
  }
  walk(envelope.uid, []);
  return result.filter((row, index) => result.findIndex(other => same(row.path, other.path)) === index);
}
function describe(ctx) {
  const result = service(ctx, 'settingsController').describe();
  return { writable: result.writable, namespaces: result.namespaces.filter(s => advancedNamespaces.has(s.ns)).map(s => ({ ns: s.ns, revision: s.revision, fields: advancedFields(s) })) };
}
function inlineCredential(view) {
  if (!inlineNamespaces.has(view.ns)) throw bad();
  const secret = view.fields.find(f => same(f.path, ['apiKey']) && f.kind === 'secret');
  const ref = view.fields.find(f => same(f.path, ['apiKeyEnv']))?.credentialRef;
  if (!secret || typeof ref !== 'string' || !/^[A-Za-z_][A-Za-z0-9_]{0,255}$/.test(ref)) throw bad();
  return { configured: secret.configured === true, ref };
}
function section(ctx, ns) { if (!advancedNamespaces.has(ns)) throw bad(); const result = describe(ctx).namespaces.find(s => s.ns === ns); if (!result) throw new Failure('NATIVE_ADVANCED_UNAVAILABLE', 503, 'unavailable'); return result; }
function validValue(field, value) {
  if (field.kind === 'enum') return field.options.some(option => same(option, value));
  if (field.kind === 'array') return Array.isArray(value) && value.length <= 1024 && value.every(v => (field.itemKind === 'object' ? object(v) : typeof v === field.itemKind) && (typeof v !== 'number' || Number.isFinite(v)));
  return ['boolean', 'number', 'string'].includes(field.kind) && typeof value === field.kind && (field.kind !== 'number' || Number.isFinite(value));
}
async function mutate(ctx, input) {
  shape(input, ['ns', 'ops', 'expectedRevision']);
  const view = section(ctx, input.ns);
  if (!Number.isSafeInteger(input.expectedRevision) || input.expectedRevision < 0 || !Array.isArray(input.ops) || !input.ops.length || input.ops.length > 128) throw bad();
  for (const op of input.ops) {
    shape(op, op?.op === 'set' ? ['op', 'path', 'value'] : ['op', 'path']);
    if (!['set', 'unset'].includes(op.op) || !Array.isArray(op.path) || op.path.some(k => typeof k !== 'string' || unsafe.has(k))) throw bad();
    const field = view.fields.find(f => same(f.path, op.path));
    if (!field || ['secret', 'unsupported'].includes(field.kind) || op.op === 'set' && !validValue(field, op.value)) throw bad();
  }
  const saved = await service(ctx, 'settingsController').mutate(input.ns, input.ops, input.expectedRevision);
  return { ns: saved.ns, revision: saved.revision, fields: advancedFields(saved) };
}
function credentialRefs(ctx) { return new Set(describe(ctx).namespaces.flatMap(s => s.fields.map(f => f.credentialRef)).filter(v => typeof v === 'string' && /^[A-Za-z_][A-Za-z0-9_]{0,255}$/.test(v))); }
async function credentials(ctx, input) {
  shape(input, ['operation', 'ref', 'value'], ['value']);
  if (!credentialRefs(ctx).has(input.ref) || !['describe', 'set', 'unset'].includes(input.operation)) throw bad();
  const owner = service(ctx, 'credentialsController');
  if (input.operation === 'describe') return owner.describe([input.ref]);
  if (input.operation === 'set') { text(input.value, 16384); await owner.set(input.ref, input.value.trim()); }
  else await owner.unset(input.ref);
  return { committed: true, overriddenByInline: describe(ctx).namespaces.filter(s => inlineNamespaces.has(s.ns)).filter(s => {
    const value = inlineCredential(s); return value.ref === input.ref && value.configured;
  }).map(s => s.ns) };
}
async function changeInlineCredential(ctx, input, signal) {
  shape(input, ['ns', 'expectedRevision', 'operation', 'value'], ['value']);
  if (!inlineNamespaces.has(input.ns) || !['clear', 'migrate'].includes(input.operation)
    || !Number.isSafeInteger(input.expectedRevision) || input.expectedRevision < 0
    || (input.operation === 'clear' && Object.hasOwn(input, 'value'))) throw bad();
  const view = section(ctx, input.ns), inline = inlineCredential(view);
  if (view.revision !== input.expectedRevision) throw new Failure('settings/conflict', 409, 'conflict');
  if (!inline.configured) throw new Failure('NATIVE_ADVANCED_INLINE_CHANGED', 409, 'conflict');
  let credential = 'not-requested';
  if (input.operation === 'migrate') {
    text(input.value, 16384);
    try { await service(ctx, 'credentialsController').set(inline.ref, input.value.trim()); credential = 'committed'; }
    catch (error) { return { status: 'credential-unknown', credential: error?.code === 'credential/rejected' ? 'rejected' : 'unknown', inline: 'unchanged', ref: inline.ref }; }
  }
  if (signal?.aborted) return { status: 'interrupted', credential, inline: 'unchanged', ref: inline.ref };
  try {
    // This is deliberately a fixed leaf unset, never a replacement of a redacted object.
    const saved = await service(ctx, 'settingsController').mutate(input.ns, [{ op: 'unset', path: ['apiKey'] }], input.expectedRevision);
    const projected = { ns: saved.ns, revision: saved.revision, fields: advancedFields(saved) };
    const cleared = !inlineCredential(projected).configured;
    return { status: cleared ? 'completed' : 'overridden', credential, inline: cleared ? 'cleared' : 'configured', ref: inline.ref, section: projected };
  } catch (error) {
    return { status: error?.code === 'settings/conflict' ? 'conflict' : error?.code === 'settings/rejected' ? 'rejected' : 'unknown',
      credential, inline: error?.code === 'settings/conflict' || error?.code === 'settings/rejected' ? 'unchanged' : 'unknown', ref: inline.ref };
  }
}
const pick = (value, keys) => Object.fromEntries(keys.filter(k => value?.[k] !== undefined).map(k => [k, value[k]]));
function incompatible(rows) {
  return (Array.isArray(rows) ? rows : []).filter(row => object(row)).map(row => ({ ...pick(row, ['name', 'version', 'runtimeVersion']),
    requirements: Object.fromEntries(Object.entries(row.requirements ?? {}).filter(([key, value]) => typeof key === 'string' && typeof value === 'string')) }));
}
function change(value) {
  return { ...pick(value, ['changed', 'application', 'restartReason', 'stage', 'target', 'enabled', 'bundle', 'version', 'pendingBuilds', 'approvedBuilds']),
    ...(value.error ? { error: { ...pick(value.error, ['code']), ...(value.error.incompatible ? { incompatible: incompatible(value.error.incompatible) } : {}) } } : {}),
    ...(value.packageResult ? { packageResult: { ...pick(value.packageResult, ['exitCode', 'kind', 'timedOut']), ...(value.packageResult.incompatible ? { incompatible: incompatible(value.packageResult.incompatible) } : {}) } } : {}) };
}
function safeRegistry(value) {
  if (typeof value !== 'string') return null;
  try { const url = new URL(value); return ['http:', 'https:'].includes(url.protocol) && !url.username && !url.password && !url.search && !url.hash ? url.href : null; } catch { return null; }
}
async function registryView(manager) {
  const value = await manager.registries();
  return { registry: safeRegistry(value.registry), fallbackRegistries: value.fallbackRegistries.map(safeRegistry).filter(Boolean), resolved: safeRegistry(value.resolved) };
}
async function registryOption(manager, input) {
  if (!Object.hasOwn(input, 'registry')) return {};
  if (input.registry === null) return { registry: null };
  const view = await registryView(manager);
  if (typeof input.registry !== 'string' || ![view.registry, view.resolved, ...view.fallbackRegistries].includes(input.registry)) throw bad();
  return { registry: input.registry };
}
function pluginRow(row) {
  const protectedEntry = Object.values(requiredProtectionPlugins).includes(row.moduleName);
  return { ...pick(row, ['entryId', 'moduleName', 'enabled', 'fiberPhase', 'patchId', 'readOnlyReason']),
    ...(protectedEntry ? { readOnlyReason: 'management-required' } : {}),
    presentation: /^@deepseek-ai\/dsh-(?:experimental-)?client-/.test(row.moduleName) ? 'web-ui' : 'not-assessed' };
}
async function plugins(ctx) {
  const manager = get(ctx, 'pluginManager'), inventory = get(ctx, 'pluginInventory');
  if (!manager) return { available: false, plugins: [], bundles: [], presets: [] };
  const entries = await manager.listPlugins(), bundles = await manager.listBundles();
  const snapshot = inventory ? await inventory.list() : null;
  const registries = await registryView(manager), exemptions = await manager.listVersionExemptions();
  return { available: true, registries, runtimeVersion: await currentRuntimeVersion(), versionExemptions: {
    exemptions: exemptions.exemptions, warnings: exemptions.warnings.map(() => 'record-problem'),
  },
    plugins: entries.map(pluginRow), bundles: bundles.map(row => ({ ...pick(row, ['name', 'version', 'enabled', 'installed', 'availability', 'removable', 'readOnlyReason']),
      ...(row.error ? { error: { ...pick(row.error, ['code']), ...(row.error.incompatible ? { incompatible: incompatible(row.error.incompatible) } : {}) } } : {}) })),
    presets: (snapshot?.agentPresets ?? []).map(p => ({ ...pick(p, ['id', 'name', 'isDefault']), rows: p.rows.map(pluginRow) })) };
}
async function approvedPlugins(ctx) {
  const manager = get(ctx, 'pluginManager');
  const bundles = manager ? await manager.listBundles() : [];
  return { plugins: Object.entries(approvedPluginBundles).map(([id, name]) => {
    const bundle = bundles.find(row => row.name === name);
    return { id, enabled: bundle?.enabled === true, available: !!bundle && bundle.availability !== 'missing' && !bundle.error,
      writable: !!bundle && !bundle.readOnlyReason && bundle.availability !== 'missing' && !bundle.error,
      ...(bundle?.version ? { version: bundle.version } : {}) };
  }) };
}

function accountClient(ctx, input) {
  shape(input, ['timezoneOffsetSeconds']);
  if (!Number.isInteger(input.timezoneOffsetSeconds) || Math.abs(input.timezoneOffsetSeconds) > 86400) throw bad();
  return { version: 'Droy Android', locale: resolveMobileLocale(ctx), timezoneOffsetSeconds: input.timezoneOffsetSeconds };
}
async function readBody(req) {
  if (!/^application\/json(?:\s*;|\s*$)/i.test(req.headers['content-type'] || '')) throw bad();
  const chunks = []; let size = 0;
  for await (const chunk of req) { size += chunk.length; if (size > 256 * 1024) throw bad(); chunks.push(chunk); }
  try { return JSON.parse(new TextDecoder('utf-8', { fatal: true }).decode(Buffer.concat(chunks))); } catch { throw bad(); }
}
function reply(res, status, value) { if (res.destroyed || res.writableEnded) return; res.writeHead(status, { 'Content-Type': 'application/json; charset=utf-8', 'Cache-Control': 'no-store' }); res.end(JSON.stringify(value)); }

/** Shared native-api authentication must run before this adapter. No operation uses unredacted settings. */
export function createNativeAdvanced(ctx) {
  const installs = new Map(), pending = new Set();
  let changingApproved = false;
  async function enableApproved(input) {
    shape(input, ['id', 'enabled', 'expectedEnabled']);
    if (!Object.hasOwn(approvedPluginBundles, input.id) || typeof input.enabled !== 'boolean' || typeof input.expectedEnabled !== 'boolean') throw bad();
    if (changingApproved) throw new Failure('settings/conflict', 409, 'conflict');
    changingApproved = true;
    try {
      const current = (await approvedPlugins(ctx)).plugins.find(row => row.id === input.id);
      if (!current?.available || !current.writable) throw new Failure('NATIVE_ADVANCED_UNAVAILABLE', 503, 'unavailable');
      if (current.enabled !== input.expectedEnabled) throw new Failure('settings/conflict', 409, 'conflict');
      const result = change(await service(ctx, 'pluginManager').setBundleEnabled(approvedPluginBundles[input.id], input.enabled));
      return { change: result, ...await approvedPlugins(ctx) };
    } finally { changingApproved = false; }
  }
  function installSnapshot(id) { const row = installs.get(id); return row ? { requestId: id, state: row.state, ...(row.result ? { result: row.result } : {}) } : { requestId: id, state: 'unknown' }; }
  async function install(input) {
    shape(input, ['spec', 'requestId', 'registry', 'approvedBuilds', 'approvalFrom'], ['registry', 'approvedBuilds', 'approvalFrom']);
    text(input.spec, 2048); text(input.requestId);
    const identity = JSON.stringify(input);
    const previous = installs.get(input.requestId);
    if (previous) { if (previous.identity !== identity) throw bad(); return installSnapshot(input.requestId); }
    const manager = service(ctx, 'pluginManager'), options = await registryOption(manager, input);
    // Check again after the public registry lookup: two requests may have awaited it together.
    const current = installs.get(input.requestId);
    if (current) { if (current.identity !== identity) throw bad(); return installSnapshot(input.requestId); }
    if ([...installs.values()].some(v => v.state === 'running')) throw new Failure('NATIVE_ADVANCED_INSTALL_BUSY', 409, 'rejected');
    let source;
    if (Object.hasOwn(input, 'approvedBuilds') || Object.hasOwn(input, 'approvalFrom')) {
      if (!Array.isArray(input.approvedBuilds) || !input.approvedBuilds.length || input.approvedBuilds.length > 128
        || new Set(input.approvedBuilds).size !== input.approvedBuilds.length || input.approvedBuilds.some(name => typeof name !== 'string' || !packageName.test(name))) throw bad();
      source = installs.get(text(input.approvalFrom));
      if (!source || source.state !== 'settled' || source.approvalUsed || source.spec !== input.spec
        || !same(source.options, options) || input.approvedBuilds.some(name => !source.result?.pendingBuilds?.includes(name))) {
        throw new Failure('NATIVE_ADVANCED_STALE_APPROVAL', 409, 'approval');
      }
    }
    const row = { state: 'running', spec: input.spec, options, identity };
    if (source) source.approvalUsed = true;
    installs.set(input.requestId, row);
    row.pending = Promise.resolve().then(() => manager.installBundle(input.spec, {
      requestId: input.requestId, saveExact: true, ...options,
      ...(source ? { approvedBuilds: [...input.approvedBuilds] } : {}),
    })).then(value => { row.state = 'settled'; row.result = change(value); }, () => { row.state = 'failed'; });
    const operation = row.pending;
    pending.add(operation);
    operation.then(() => pending.delete(operation), () => pending.delete(operation));
    return installSnapshot(input.requestId);
  }
  async function versionExemption(input) {
    shape(input, ['packageVersion', 'runtimeVersion', 'enabled', 'acceptRisk']);
    text(input.packageVersion, 512); text(input.runtimeVersion, 128);
    if (typeof input.enabled !== 'boolean' || typeof input.acceptRisk !== 'boolean' || input.enabled && !input.acceptRisk) throw bad();
    const separator = input.packageVersion.lastIndexOf('@'), name = input.packageVersion.slice(0, separator);
    if (separator <= 0 || !packageName.test(name)) throw bad();
    assertRequiredPackage(name, false);
    const runtime = await currentRuntimeVersion();
    if (input.enabled && input.runtimeVersion !== runtime) throw new Failure('NATIVE_ADVANCED_RUNTIME_CHANGED', 409, 'runtime');
    const manager = service(ctx, 'pluginManager');
    if (input.enabled) {
      const known = new Set();
      for (const bundle of await manager.listBundles()) {
        if (bundle.version) known.add(`${bundle.name}@${bundle.version}`);
        for (const issue of bundle.error?.incompatible ?? []) known.add(`${issue.name}@${issue.version}`);
      }
      for (const row of installs.values()) for (const issue of [...(row.result?.error?.incompatible ?? []), ...(row.result?.packageResult?.incompatible ?? [])]) known.add(`${issue.name}@${issue.version}`);
      if (!known.has(input.packageVersion)) throw bad();
    } else {
      const listed = await manager.listVersionExemptions();
      if (!listed.exemptions[input.packageVersion]?.includes(input.runtimeVersion)) throw bad();
    }
    return change(await manager.setVersionExemption(input.packageVersion, input.runtimeVersion, input.enabled, input.acceptRisk));
  }
  const routes = new Map([
    ['', ['GET', () => ({ settings: describe(ctx), pluginsAvailable: !!get(ctx, 'pluginManager'), accountAvailable: !!get(ctx, 'accountController') })]],
    ['/mutate', ['POST', input => mutate(ctx, input)]],
    ['/credentials', ['POST', input => credentials(ctx, input)]],
    ['/credentials/inline', ['POST', (input, signal) => changeInlineCredential(ctx, input, signal)]],
    ['/plugins', ['GET', () => plugins(ctx)]],
    ['/approved-plugins', ['GET', () => approvedPlugins(ctx)]],
    ['/approved-plugins/enable', ['POST', input => enableApproved(input)]],
    ['/plugins/inspect', ['POST', async (input, signal) => { shape(input, ['spec', 'registry'], ['registry']); const manager = service(ctx, 'pluginManager'); const options = await registryOption(manager, input); const value = await manager.inspect(text(input.spec, 2048), Object.keys(options).length ? options : undefined, signal); return pick(value, ['status', 'kind', 'name', 'version', 'description', 'bundle', 'problem']); }]],
    ['/plugins/enable', ['POST', async input => {
      shape(input, ['kind', 'id', 'enabled']); if (typeof input.enabled !== 'boolean') throw bad(); text(input.id);
      const manager = service(ctx, 'pluginManager');
      if (input.kind === 'plugin') {
        const row = (await manager.listPlugins()).find(row => row.entryId === input.id);
        if (!row) throw bad(); assertRequiredPluginEnabled(row.patchId ?? row.entryId, row.moduleName, input.enabled);
        await stopMemoryFeature(ctx, row.moduleName, input.enabled);
        return change(await manager.setPluginEnabled(input.id, input.enabled));
      }
      if (input.kind !== 'bundle') throw bad(); assertRequiredPackage(input.id, input.enabled); await stopMemoryFeature(ctx, input.id, input.enabled);
      return change(await manager.setBundleEnabled(input.id, input.enabled));
    }]],
    ['/plugins/remove', ['POST', async input => { shape(input, ['name']); text(input.name); assertRequiredPackage(input.name, false); return change(await service(ctx, 'pluginManager').removeBundle(input.name)); }]],
    ['/plugins/install', ['POST', input => install(input)]],
    ['/plugins/version-exemption', ['POST', input => versionExemption(input)]],
    ['/plugins/install-state', ['POST', input => { shape(input, ['requestId']); return installSnapshot(text(input.requestId)); }]],
    ['/plugins/cancel', ['POST', input => { shape(input, ['requestId']); return service(ctx, 'pluginManager').cancelInstall(text(input.requestId)); }]],
    ['/account', ['GET', async () => { const owner = get(ctx, 'accountController'); return owner ? { available: true, view: await owner.getState(), runningTasks: owner.hasRunningAccountTasks() } : { available: false }; }]],
    ['/account/details', ['POST', async input => { const client = accountClient(ctx, input), owner = service(ctx, 'accountController'); return { profile: await owner.getProfile(client), balance: await owner.getBalance(client) }; }]],
    ['/account/sign-in', ['POST', (input, _signal, origin) => service(ctx, 'accountController').startSignIn(accountClient(ctx, input), origin, 'web')]],
    ['/account/cancel', ['POST', input => { shape(input, ['attemptId']); return service(ctx, 'accountController').cancelSignIn(text(input.attemptId)); }]],
    ['/account/sign-out', ['POST', input => { const owner = service(ctx, 'accountController'), client = accountClient(ctx, input); if (owner.hasRunningAccountTasks()) throw new Failure('NATIVE_ADVANCED_ACCOUNT_TASKS', 409, 'tasks'); return owner.signOut(client); }]],
  ]);
  return { pendingWork: () => Object.freeze([...pending]), async handle(req, res, signal) {
    try {
      const url = new URL(req.url, `http://${req.headers.host}`);
      if (url.search || !url.pathname.startsWith(base)) throw bad();
      const route = routes.get(url.pathname.slice(base.length));
      if (!route) throw new Failure('NATIVE_ADVANCED_NOT_FOUND', 404);
      if (req.method !== route[0]) throw new Failure('NATIVE_ADVANCED_METHOD', 405);
      const input = req.method === 'POST' ? await readBody(req) : undefined;
      signal?.throwIfAborted();
      reply(res, 200, { ok: true, result: await route[1](input, signal, url.origin) });
    } catch (error) {
      const conflict = error?.code === 'settings/conflict';
      const own = error instanceof Failure;
      const key = own ? error.copy : conflict ? 'conflict' : error?.code === 'settings/rejected' || /^DROY_/.test(error?.message ?? '') ? 'rejected' : 'failed';
      reply(res, own ? error.status : conflict ? 409 : key === 'rejected' ? 422 : 500,
        { ok: false, error: { code: own ? error.code : conflict ? 'settings/conflict' : 'NATIVE_ADVANCED_FAILED', message: messages[key][resolveMobileLocale(ctx) === 'zh' ? 0 : 1] } });
    }
  } };
}
