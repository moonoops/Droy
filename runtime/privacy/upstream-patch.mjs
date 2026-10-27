import { createHash } from 'node:crypto';

// These are narrow build-time seams for the locked DSH release, not runtime
// monkey patches. A different build must be reviewed before it can be shipped.
const HASHES = Object.freeze({
  llm: 'd18d8ae4c8d961e628708c1e4698e09716a0ebb80e55a672e0bde1029bec1155',
  pi: '99b5c20830a52f69641df8a18a39206079b0c725201958cce2c4a934f1d4f7cd',
  mcp: '64dfc9127a47d9d453d72022b47d3b8d8a5c82cad756ac1114b09faf1a956120',
  loader: '638b9ad360e01236120772323f80a65570c4f3f42f4cfc6243330241025b6d72',
  manager: '1b8bf9eb266d7b3b7f899175e3f435c89ed8baa8aabc677212759f8c6cd23132',
  managerPatch: '8ef0de60d03c8cbfc132b8560118e1cb817345381f072c9cad215f2654702626',
  fsLocal: '63fbb41d2c33e07111884b798be507e68c2752acab8249c821c20ade436e894f',
});
function replaceOnce(source, before, after) {
  if (source.split(before).length !== 2) throw new Error('DSH privacy patch anchor changed');
  return source.replace(before, after);
}
function verify(source, kind) {
  const digest = createHash('sha256').update(source).digest('hex');
  if (digest !== HASHES[kind]) throw new Error(`DSH privacy ${kind} input changed; review required`);
}
const KEY = 'Symbol.for("droy.privacy.prepared-route.v1")';

export function patchPrivacyLlm(source) {
  verify(source, 'llm');
  source = replaceOnce(source, '\t\t\t\tadapterDefaults,', `\t\t\t\tadapterDefaults,\n\t\t\t\t[${KEY}]: adapterCall[${KEY}],`);
  source = replaceOnce(source, '\t\t\t\t\t\tdispatch: (options) => adapterCall.stream(options)', `\t\t\t\t\t\tprivacyRoute: adapterCall[${KEY}],\n\t\t\t\t\t\tdispatch: (options) => adapterCall.stream(options)`);
  source = replaceOnce(source, '\t\t\t\tlet dispatch;', '\t\t\t\tlet dispatch;\n\t\t\t\tlet privacyRoute = prepared?.privacyRoute;');
  source = replaceOnce(source, '\t\t\t\t\tdispatch = (options) => adapterCall.stream(options);', `\t\t\t\t\tdispatch = (options) => adapterCall.stream(options);\n\t\t\t\t\tprivacyRoute = adapterCall[${KEY}];`);
  return replaceOnce(source, '\t\t\t\titerator = dispatch(this.forAdapter(projectedOptions, adapter))[Symbol.asyncIterator]();',
    '\t\t\t\tconst finalOptions = this.forAdapter(projectedOptions, adapter);\n'
    + '\t\t\t\tconst privacy = this.ctx.get("droyPrivacy");\n'
    + '\t\t\t\tif (!privacy || !this.ctx.get("droyExecution")?.installed?.()) throw new Error("DROY_REQUIRED_PROTECTION_UNAVAILABLE");\n'
    + '\t\t\t\titerator = privacy.wrapDispatch(finalOptions, privacyRoute, dispatch)[Symbol.asyncIterator]();');
}

export function patchPrivacyPiAi(source) {
  verify(source, 'pi');
  source = replaceOnce(source, '\t\t\tmodel: this.modelInfo(snapshot, provider, model),\n\t\t\tstream: (options) => this.streamWithSnapshot(options, snapshot)',
    `\t\t\tmodel: this.modelInfo(snapshot, provider, model),\n\t\t\t[${KEY}]: this.config.privacy?.()?.capturePiRoute({ provider, model, profile: this.profileOf(snapshot, provider), resolvedModel: this.modelOf(snapshot, provider, model) }),\n\t\t\tstream: (options) => this.streamWithSnapshot(options, snapshot)`);
  source = replaceOnce(source, '\tconst adapter = new PiAiAdapter({\n\t\tprofiles,', '\tconst adapter = new PiAiAdapter({\n\t\tprivacy: () => ctx.get("droyPrivacy"),\n\t\tprofiles,');
  return replaceOnce(source, '\t\t\t\t\t...profileOptions(profile, reasoning, apiKey),', '\t\t\t\t\t...profileOptions(profile, reasoning, apiKey),\n\t\t\t\t\tfetch: this.config.privacy?.()?.fetchFor(options),');
}

export function patchExecutionMcp(source) {
  verify(source, 'mcp');
  return replaceOnce(source, '\t\t\ttransport = createTransport(config);',
    '\t\t\ttransport = ctx.get("droyExecution")?.createMcpTransport(config) ?? createTransport(config);');
}

export function patchExecutionFileMode(source) {
  verify(source, 'fsLocal');
  source = replaceOnce(source, 'async function writeFileAtomic(absolutePath, content, mode, signal, internals = {}, createIfAbsent) {',
    'async function writeFileAtomic(absolutePath, content, mode, signal, internals = {}, createIfAbsent, newFileMode) {');
  source = replaceOnce(source, '\t\tif (mode !== void 0) await handle.chmod(mode);',
    '\t\tconst publicationMode = mode ?? (platform === "win32" ? void 0 : newFileMode);\n\t\tif (publicationMode !== void 0) await handle.chmod(publicationMode);');
  source = replaceOnce(source, '\t\tdiffBasisMaxBytes: z.number().default(DEFAULT_DIFF_BASIS_MAX_BYTES)',
    '\t\tdiffBasisMaxBytes: z.number().default(DEFAULT_DIFF_BASIS_MAX_BYTES),\n\t\tnewFileMode: z.number()');
  source = replaceOnce(source, '\t\tthis.config = resolved;',
    '\t\tif (resolved.newFileMode !== void 0 && (!Number.isSafeInteger(resolved.newFileMode) || resolved.newFileMode < 0 || resolved.newFileMode > 511)) throw new Error("FS_INVALID_NEW_FILE_MODE");\n\t\tthis.config = resolved;');
  return replaceOnce(source,
    'await writeFileAtomic(target.targetKey, content, existing?.mode, signal, this.internals, expected?.kind === "createIfAbsent" ? { displayPath: target.displayPath } : void 0);',
    'await writeFileAtomic(target.targetKey, content, existing?.mode, signal, this.internals, expected?.kind === "createIfAbsent" ? { displayPath: target.displayPath } : void 0, this.config.newFileMode);');
}

export function patchRequiredLoader(source) {
  verify(source, 'loader');
  source = `import { assertRequiredEntryUpdate, assertRequiredGroupUpdate, assertRequiredRemove, assertRequiredCreate, assertRequiredMove, stopMemoryFeatureForUpdate, stopMemoryFeatureForGroup, isRequiredProtectionEntry } from '@droyagent/mobile/required-plugins';\n` + source;
  source = replaceOnce(source, '\t\t\tif (fiber.entry.disabled) return;',
    '\t\t\tif (fiber.entry.disabled || isRequiredProtectionEntry(fiber.entry)) return;');
  source = replaceOnce(source, '\tasync create(options, parent = null, position = Infinity) {',
    '\tasync create(options, parent = null, position = Infinity) {\n\t\tassertRequiredCreate(options);');
  source = replaceOnce(source, '\tasync update(id, options, parent, position) {\n\t\tconst entry = this.resolve(id);',
    '\tasync update(id, options, parent, position) {\n\t\tconst entry = this.resolve(id);\n\t\tassertRequiredEntryUpdate(entry, options, false);\n\t\tassertRequiredMove(entry, parent);');
  source = replaceOnce(source, '\tremove(id, isDispose = false) {\n\t\tconst entry = this.tree.store[id];',
    '\tremove(id, isDispose = false) {\n\t\tconst entry = this.tree.store[id];\n\t\tassertRequiredRemove(entry, isDispose);');
  source = replaceOnce(source, '\tasync update(config) {\n\t\tconst oldConfig = this.data;',
    '\tasync update(config) {\n\t\tassertRequiredGroupUpdate(this, config);\n\t\tawait stopMemoryFeatureForGroup(this, config);\n\t\tconst oldConfig = this.data;');
  return replaceOnce(source, '\tasync update(options, create = false, force = false) {\n\t\tconst legacy = { ...this.options };',
    '\tasync update(options, create = false, force = false) {\n\t\tassertRequiredEntryUpdate(this, options, create);\n\t\tawait stopMemoryFeatureForUpdate(this, options, create);\n\t\tconst legacy = { ...this.options };');
}

export function patchRequiredPluginManager(source) {
  verify(source, 'manager');
  source = `import { assertRequiredPluginEnabled, assertRequiredPackage, stopMemoryFeature } from '@droyagent/mobile/required-plugins';\n` + source;
  source = replaceOnce(source, 'async function writePluginEnabled(profile, id, name, enabled) {',
    'async function writePluginEnabled(profile, id, name, enabled) {\n\tassertRequiredPluginEnabled(id, name, enabled);');
  source = replaceOnce(source, '\t\tremoveBundle(name) {', '\t\tremoveBundle(name) {\n\t\t\tassertRequiredPackage(name, false);');
  source = replaceOnce(source, '\t\t\t\tawait writePluginEnabled(this.profile, row.patchId, row.moduleName, enabled);',
    '\t\t\t\tawait stopMemoryFeature(this.ownerContext, row.moduleName, enabled);\n\t\t\t\tawait writePluginEnabled(this.profile, row.patchId, row.moduleName, enabled);');
  source = replaceOnce(source, '\t\t\t\t\tconst bundle = (await this.listBundles()).find((item) => item.name === name);',
    '\t\t\t\t\tawait stopMemoryFeature(this.ownerContext, name, false);\n\t\t\t\t\tconst bundle = (await this.listBundles()).find((item) => item.name === name);');
  return replaceOnce(source, '\t\tasync selectBundle(name, enabled) {', '\t\tasync selectBundle(name, enabled) {\n\t\t\tassertRequiredPackage(name, enabled);');
}

export function patchRequiredPluginManagerPatch(source) {
  verify(source, 'managerPatch');
  return `import { assertRequiredPluginEnabled } from '@droyagent/mobile/required-plugins';\n`
    + replaceOnce(source, 'export async function writePluginEnabled(profile, id, name, enabled) {',
      'export async function writePluginEnabled(profile, id, name, enabled) {\n    assertRequiredPluginEnabled(id, name, enabled);');
}
