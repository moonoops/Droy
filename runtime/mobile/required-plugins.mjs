const REQUIRED = Object.freeze({
  'droy-plugin-policy': '@droyagent/mobile/plugin-policy',
  'droy-memory-host': '@droyagent/mobile/memory-host',
  'droy-privacy': '@droyagent/privacy',
  'droy-execution': '@droyagent/execution',
});
const PACKAGES = new Set(['@droyagent/mobile', '@droyagent/privacy', '@droyagent/execution']);
const MEMORY_PACKAGE = '@droyagent/memory';
const reject = () => { throw new Error('DROY_REQUIRED_PROTECTION_PLUGIN: Droy 隐私保护组件不能停用、移除或改写'); };
const requiredId = value => Object.hasOwn(REQUIRED, value?.id) || Object.values(REQUIRED).includes(value?.name);
const children = value => Array.isArray(value?.config) ? value.config : value?.config?.plugins ?? [];
function containsRequired(value) { return requiredId(value) || children(value).some(containsRequired); }
function hasEnabledMemory(value) {
  if (value?.disabled === true) return false;
  return value?.name === MEMORY_PACKAGE || children(value).some(hasEnabledMemory);
}
function validRequired(value) {
  if (!requiredId(value)) return;
  if (REQUIRED[value.id] !== value.name || Object.keys(value).some(key => !['id', 'name', 'disabled'].includes(key))
    || (value.disabled !== undefined && value.disabled !== false)) reject();
}
function validateTree(rows) {
  for (const row of rows) { validRequired(row); validateTree(children(row)); }
}
function merged(original, patch) {
  const result = { ...original };
  for (const [key, value] of Object.entries(patch)) { if (value == null) delete result[key]; else result[key] = value; }
  return result;
}
export function assertRequiredEntryUpdate(entry, options, create) {
  const candidate = create ? options : merged(entry.options, options);
  if (requiredId(entry.options) && (candidate.id !== entry.options.id || candidate.name !== entry.options.name)) reject();
  validRequired(candidate);
  if (!requiredId(entry.options) && containsRequired(entry.options)
    && JSON.stringify(candidate) !== JSON.stringify(entry.options)) reject();
  validateTree(children(candidate));
}
export function assertRequiredGroupUpdate(group, config) {
  validateTree(config);
  for (const original of group.data) {
    if (!containsRequired(original)) continue;
    const candidate = config.find(row => row.id === original.id);
    if (!candidate) reject();
    assertRequiredEntryUpdate({ options: original }, candidate, true);
  }
}
export function assertRequiredCreate(options) { validateTree([options]); }
export function assertRequiredMove(entry, parent) { if (parent !== undefined && containsRequired(entry.options)) reject(); }
export function assertRequiredRemove(entry, isDispose) {
  if (!isDispose && containsRequired(entry?.options)) reject();
  if (!isDispose && hasEnabledMemory(entry?.options))
    throw new Error('DROY_MEMORY_DISABLE_FIRST: 请先停用记忆插件，等待采集停止后再移除');
}
export function assertRequiredPluginEnabled(id, name, enabled) {
  if (!enabled && requiredId({ id, name })) reject();
}
export function assertRequiredPackage(name, enabled) {
  if (!enabled && PACKAGES.has(name)) reject();
}
export const requiredProtectionPlugins = REQUIRED;
export const isRequiredProtectionEntry = entry => containsRequired(entry?.options);
export async function stopMemoryFeature(ctx, name, enabled) {
  if (name !== MEMORY_PACKAGE || enabled !== false) return;
  const service = ctx.get('droyMemoryHost');
  if (typeof service?.featureStop !== 'function') throw new Error('DROY_MEMORY_STOP_UNAVAILABLE');
  await service.featureStop();
}
export async function stopMemoryFeatureForUpdate(entry, options, create) {
  const prior = entry.options;
  if (!hasEnabledMemory(prior)) return;
  const candidate = create ? options : merged(prior, options);
  if (candidate.disabled !== undefined && candidate.disabled !== false || candidate.name !== prior.name || !hasEnabledMemory(candidate))
    await stopMemoryFeature(entry.context, MEMORY_PACKAGE, false);
}
export async function stopMemoryFeatureForGroup(group, config) {
  const removed = group.data.filter(original => hasEnabledMemory(original)
    && !config.some(candidate => candidate.id === original.id && hasEnabledMemory(candidate)));
  if (!removed.length) return;
  await stopMemoryFeature(group.context, MEMORY_PACKAGE, false);
  // remove() is synchronous. This records the already completed stop before
  // its normal disposal path removes the old row.
  for (const row of removed) row.disabled = true;
}
