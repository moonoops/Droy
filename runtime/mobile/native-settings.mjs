import { TextDecoder } from 'node:util';
import { resolveMobileLocale } from './locale.mjs';

const base = '/droy/native/settings';
const bodyLimit = 256 * 1024;
const namespaces = new Set(['locale', 'ui-theme', 'llm-pi-ai', 'llm-deepseek', 'web', 'web-search-deepseek', 'web-deepseek-api', 'subagent', 'subagent-model-selection-settings']);
const providerFields = new Set(['displayName', 'baseURL', 'api', 'apiKeyEnv', 'models', 'modelOverrides']);
const protocols = new Set(['openai-completions', 'openai-responses', 'anthropic-messages']);
const unsafe = new Set(['__proto__', 'constructor', 'prototype']);
const copy = {
  invalid: ['设置请求格式无效。', 'The settings request is invalid.'],
  unavailable: ['设置服务尚未就绪。', 'The settings service is not ready.'],
  missing: ['未找到此设置。', 'The setting was not found.'],
  method: ['此设置操作不支持该请求方式。', 'This method is not supported for the settings operation.'],
  conflict: ['设置已发生变化，请刷新后重新修改。', 'The settings have changed. Refresh before editing again.'],
  rejected: ['设置未保存，请检查输入并重试。', 'The settings were not saved. Check the input and try again.'],
  credential: ['凭据未保存，请重新提交该步骤。已保存的模型设置会保留。', 'The credential was not saved. Retry this step. Saved provider settings are retained.'],
  discovery: ['无法获取模型列表，请检查服务地址、协议和凭据。', 'Could not retrieve models. Check the endpoint, protocol, and credential.'],
  failed: ['设置操作未完成，请重试。', 'The settings operation did not complete. Try again.'],
};

class SettingsError extends Error {
  constructor(code, status, text) { super(code); this.code = code; this.status = status; this.text = text; }
}
const bad = () => new SettingsError('NATIVE_SETTINGS_BAD_REQUEST', 400, 'invalid');
const missing = () => new SettingsError('NATIVE_SETTINGS_NOT_FOUND', 404, 'missing');
const object = value => value !== null && typeof value === 'object' && !Array.isArray(value);
function fields(value, allowed, required = allowed) {
  if (!object(value) || Object.keys(value).some(key => !allowed.includes(key)) || required.some(key => !Object.hasOwn(value, key))) throw bad();
}
function identifier(value) {
  if (typeof value !== 'string' || !value.trim() || value.length > 256 || /[\x00-\x1f\x7f]/.test(value) || unsafe.has(value)) throw bad();
  return value;
}
function reference(value) {
  if (typeof value !== 'string' || !/^[A-Za-z_][A-Za-z0-9_]{0,255}$/.test(value) || unsafe.has(value)) throw bad();
  return value;
}
function keyValue(value) {
  if (typeof value !== 'string') throw bad();
  const key = value.trim();
  if (!key || key.length > 16384 || !/^[\x21-\x7e]+$/.test(key) || /^[A-Za-z_][A-Za-z0-9_]*=/.test(key)
    || /^(["']).*\1$/.test(key)) throw bad();
  return key;
}
function endpoint(value) {
  if (typeof value !== 'string' || value.length > 4096) throw bad();
  let url;
  try { url = new URL(value); } catch { throw bad(); }
  if (!['http:', 'https:'].includes(url.protocol) || url.username || url.password) throw bad();
}
function service(ctx, name) {
  const value = typeof ctx.get === 'function' ? ctx.get(name) : ctx[name];
  if (!value) throw new SettingsError('NATIVE_SETTINGS_NOT_READY', 503, 'unavailable');
  return value;
}
function reply(res, status, body) {
  if (res.destroyed || res.writableEnded) return;
  res.writeHead(status, { 'Content-Type': 'application/json; charset=utf-8', 'Cache-Control': 'no-store' });
  res.end(JSON.stringify(body));
}
function errorReply(ctx, res, error) {
  const known = {
    'settings/conflict': [409, 'conflict'], 'settings/rejected': [422, 'rejected'],
    'credential/rejected': [422, 'credential'], 'llm/model-discovery-rejected': [422, 'discovery'],
    'gateway/bad-request': [400, 'invalid'],
  };
  const own = error instanceof SettingsError;
  const mapped = own ? [error.status, error.text] : Object.hasOwn(known, error?.code) ? known[error.code] : [500, 'failed'];
  // Provider errors can contain request headers or URLs; neither their text,
  // details, nor an unrecognized error code may cross this credential surface.
  const code = own ? error.code : Object.hasOwn(known, error?.code) ? error.code : 'NATIVE_SETTINGS_FAILED';
  reply(res, mapped[0], { ok: false, error: { code, message: copy[mapped[1]][resolveMobileLocale(ctx) === 'zh' ? 0 : 1] } });
}
function body(req) {
  if (!/^application\/json(?:\s*;|\s*$)/i.test(req.headers['content-type'] || '')) { req.resume(); throw bad(); }
  if (Number(req.headers['content-length']) > bodyLimit) { req.resume(); throw new SettingsError('NATIVE_SETTINGS_TOO_LARGE', 413, 'invalid'); }
  return new Promise((resolve, reject) => {
    const chunks = []; let bytes = 0;
    const cleanup = () => {
      req.removeListener('data', data); req.removeListener('end', end);
      req.removeListener('error', failed); req.removeListener('aborted', aborted);
    };
    const failed = error => { cleanup(); reject(error); };
    const aborted = () => failed(bad());
    const data = chunk => {
      bytes += chunk.length;
      if (bytes > bodyLimit) { cleanup(); req.resume(); reject(new SettingsError('NATIVE_SETTINGS_TOO_LARGE', 413, 'invalid')); }
      else chunks.push(chunk);
    };
    const end = () => {
      cleanup();
      try { resolve(JSON.parse(new TextDecoder('utf-8', { fatal: true }).decode(Buffer.concat(chunks)))); }
      catch { reject(bad()); }
    };
    req.on('data', data); req.once('end', end); req.once('error', failed); req.once('aborted', aborted);
  });
}
function describe(ctx) {
  // Only the public controller may supply this view: ctx.settings.describe()
  // without redactSecrets would expose credential-bearing configuration.
  const result = service(ctx, 'settingsController').describe();
  return { ...result, namespaces: result.namespaces.filter(entry => namespaces.has(entry.ns)) };
}
function namespace(ctx, ns) {
  if (!namespaces.has(ns)) throw bad();
  const entry = describe(ctx).namespaces.find(row => row.ns === ns);
  if (!entry) throw new SettingsError('NATIVE_SETTINGS_NAMESPACE_UNAVAILABLE', 503, 'unavailable');
  return entry;
}
function checkedValue(field, value) {
  if (field === 'apiKeyEnv') reference(value);
  else if (field === 'baseURL') endpoint(value);
  else if (field === 'displayName') identifier(value);
  else if (field === 'api' && !protocols.has(value)) throw bad();
  else if (field === 'modelOverrides' && !object(value)) throw bad();
  else if (field === 'models' && (!Array.isArray(value) || value.length > 2048)) throw bad();
}
function mutation(ctx, input) {
  fields(input, ['ns', 'ops', 'expectedRevision']);
  const view = namespace(ctx, input.ns);
  if (!Number.isSafeInteger(input.expectedRevision) || input.expectedRevision < 0 || !Array.isArray(input.ops)
    || input.ops.length < 1 || input.ops.length > 128) throw bad();
  for (const op of input.ops) {
    fields(op, op?.op === 'set' ? ['op', 'path', 'value'] : ['op', 'path']);
    if (!['set', 'unset'].includes(op.op) || !Array.isArray(op.path) || !op.path.length
      || op.path.some(key => typeof key !== 'string' || unsafe.has(key))) throw bad();
    const setting = input.ns === 'locale' || input.ns === 'ui-theme';
    if (setting) {
      if (op.path.length !== 1 || op.path[0] !== 'preference' || op.op !== 'set'
        || !(input.ns === 'locale' ? ['zh', 'en'] : ['light', 'dark', 'system']).includes(op.value)) throw bad();
    } else if (['web', 'web-search-deepseek', 'web-deepseek-api', 'subagent', 'subagent-model-selection-settings'].includes(input.ns)) {
      const allowed = {
        web: ['searchProvider', 'fetchProvider'],
        'web-search-deepseek': ['apiKeyEnv', 'baseURL', 'model', 'apiVersion', 'maxTokens', 'maxUses'],
        'web-deepseek-api': ['apiKeyEnv', 'baseURL', 'count', 'sendOriginalQuestion'],
        'subagent-model-selection-settings': ['enabled', 'allowedModels'],
        subagent: ['maxDepth', 'maxActiveSubagents'],
      }[input.ns];
      if (op.path.length !== 1 || !allowed.includes(op.path[0])) throw bad();
      if (op.op === 'set') {
        const key = op.path[0];
        if (['maxDepth', 'maxActiveSubagents'].includes(key) && (!Number.isSafeInteger(op.value) || op.value < (key === 'maxDepth' ? 0 : 1))) throw bad();
        if (key === 'apiKeyEnv') reference(op.value);
        if (key === 'baseURL') endpoint(op.value);
        if (key === 'searchProvider' && !['deepseek-official', 'deepseek-search-api'].includes(op.value)) throw bad();
        if (key === 'fetchProvider' && !['http', 'deepseek-fetch-api'].includes(op.value)) throw bad();
        if (['enabled', 'sendOriginalQuestion'].includes(key) && typeof op.value !== 'boolean') throw bad();
        if (key === 'allowedModels') {
          if (!Array.isArray(op.value)) throw bad();
          const routes = new Set();
          for (const route of op.value) {
            fields(route, ['provider', 'model']); identifier(route.provider); identifier(route.model);
            const id = JSON.stringify([route.provider, route.model]);
            if (routes.has(id)) throw bad();
            routes.add(id);
          }
        }
      }
    } else if (input.ns === 'llm-pi-ai') {
      if (op.path[0] !== 'providers' || ![2, 3].includes(op.path.length)) throw bad();
      identifier(op.path[1]);
      if (op.path.length === 2) {
        if (op.op === 'set') {
          // A full provider object is only accepted on creation. Editing an
          // existing redacted object must never erase undisplayed fields.
          if (Object.hasOwn(view.value?.providers ?? {}, op.path[1])) throw bad();
          fields(op.value, [...providerFields], []);
          for (const [field, value] of Object.entries(op.value)) checkedValue(field, value);
        }
      } else {
        if (!providerFields.has(op.path[2])) throw bad();
        if (op.op === 'set') checkedValue(op.path[2], op.value);
      }
    } else {
      if (op.path.length !== 1 || !['baseURL', 'apiKeyEnv', 'models'].includes(op.path[0])) throw bad();
      if (op.op === 'set') checkedValue(op.path[0], op.value);
    }
  }
  if (input.ns === 'subagent-model-selection-settings') {
    const next = { ...view.value };
    for (const op of input.ops) {
      if (op.op === 'unset') delete next[op.path[0]]; else next[op.path[0]] = op.value;
    }
    if (next.enabled === true && (!Array.isArray(next.allowedModels) || next.allowedModels.length === 0)) throw bad();
  }
  // The owning settings service validates actual mounted schemas and enforces
  // revisions. Do not reconstruct a user section from the redacted view.
  return service(ctx, 'settingsController').mutate(input.ns, input.ops, input.expectedRevision);
}
function credentialRefs(ctx, includeNativeAuth) {
  const refs = new Set();
  for (const entry of describe(ctx).namespaces) {
    const profiles = entry.ns === 'llm-pi-ai' ? Object.values(entry.value?.providers ?? {})
      : ['llm-deepseek', 'web-search-deepseek', 'web-deepseek-api'].includes(entry.ns) ? [entry.value] : [];
    for (const profile of profiles) if (typeof profile?.apiKeyEnv === 'string') refs.add(reference(profile.apiKeyEnv));
  }
  if (includeNativeAuth) for (const provider of service(ctx, 'llm').listConfigurableProviders())
    if (namespaces.has(provider.settingsNs)) for (const ref of provider.nativeAuth?.env ?? []) refs.add(reference(ref));
  return refs;
}
async function credentials(ctx, operation, input) {
  if (operation === 'describe') {
    fields(input, ['refs']);
    if (!Array.isArray(input.refs) || input.refs.length > 64) throw bad();
    const allowed = credentialRefs(ctx, true);
    if (input.refs.some(ref => !allowed.has(reference(ref)))) throw bad();
    return service(ctx, 'credentialsController').describe(input.refs);
  }
  fields(input, operation === 'set' ? ['ref', 'value'] : ['ref']);
  if (!credentialRefs(ctx, false).has(reference(input.ref))) throw bad();
  const controller = service(ctx, 'credentialsController');
  if (operation === 'set') await controller.set(input.ref, keyValue(input.value));
  else await controller.unset(input.ref);
  // Credential writes are independent of settings writes. A blank key draft
  // means the client omits this request; only explicit unset deletes a key.
  return { committed: true, ref: input.ref, operation };
}
async function discover(ctx, input, signal) {
  fields(input, ['settingsNs', 'request']);
  if (input.settingsNs !== 'llm-pi-ai') throw bad();
  namespace(ctx, input.settingsNs);
  fields(input.request, ['provider', 'baseURL', 'api', 'apiKey'], []);
  const request = { ...input.request };
  if (Object.hasOwn(request, 'provider')) identifier(request.provider);
  if (Object.hasOwn(request, 'baseURL')) endpoint(request.baseURL);
  if (Object.hasOwn(request, 'api') && !protocols.has(request.api)) throw bad();
  if (Object.hasOwn(request, 'apiKey')) request.apiKey = keyValue(request.apiKey);
  return service(ctx, 'llm').remoteDiscoverModels(input.settingsNs, request, signal);
}
async function defaultModel(ctx, input) {
  fields(input, ['provider', 'model']); identifier(input.provider); identifier(input.model);
  if (!describe(ctx).writable) throw new SettingsError('NATIVE_SETTINGS_READ_ONLY', 422, 'rejected');
  const catalog = await service(ctx, 'sessionController').modelCatalog();
  if (!catalog.groups.some(group => group.id === input.provider && group.models.some(model => model.id === input.model)))
    throw new SettingsError('NATIVE_SETTINGS_MODEL_UNAVAILABLE', 422, 'rejected');
  const defaults = service(ctx, 'agentDefaultModel');
  await defaults.saveSelection(input);
  const selection = defaults.currentSelection();
  // Upstream saveSelection is deliberately a no-op without a configuration
  // editor. A fulfilled Promise alone is not proof that this choice applied.
  if (selection.provider !== input.provider || selection.model !== input.model || selection.reasoningEffort !== undefined)
    throw new SettingsError('NATIVE_SETTINGS_DEFAULT_NOT_APPLIED', 409, 'conflict');
  return { committed: true, selection: { provider: selection.provider, model: selection.model } };
}

async function removeProvider(ctx, input) {
  fields(input, ['provider', 'expectedRevision']);
  identifier(input.provider);
  if (!Number.isSafeInteger(input.expectedRevision) || input.expectedRevision < 0) throw bad();
  const directory = service(ctx, 'llm').listConfigurableProviders();
  const provider = directory.find(row => row.provider === input.provider && namespaces.has(row.settingsNs));
  if (!provider) throw missing();
  const view = namespace(ctx, provider.settingsNs);
  if (!describe(ctx).writable) throw new SettingsError('NATIVE_SETTINGS_READ_ONLY', 422, 'rejected');
  if (view.revision !== input.expectedRevision) throw new SettingsError('settings/conflict', 409, 'conflict');
  const path = [...provider.settingsPath];
  const defaults = service(ctx, 'agentDefaultModel');
  if (defaults.currentSelection().provider === input.provider) {
    await defaults.saveSelection({ provider: '', model: '' });
    const selection = defaults.currentSelection();
    if (selection.provider !== '' || selection.model !== '')
      throw new SettingsError('NATIVE_SETTINGS_DEFAULT_NOT_APPLIED', 409, 'conflict');
  }
  // Pi-ai registers only configured profiles, including installed catalog
  // providers. The separately mounted DeepSeek route has no removable profile:
  // its empty catalog prevents inherited models/auth from returning. Neither
  // operation deletes credentials shared with another model or search route.
  const ops = provider.settingsNs === 'llm-pi-ai'
    ? [{ op: 'unset', path }]
    : [{ op: 'set', path: [...path, 'models'], value: [] }];
  await mutation(ctx, { ns: provider.settingsNs, expectedRevision: input.expectedRevision, ops });
  return { committed: true, provider: input.provider };
}

/** Called only after native-api's shared loopback authentication succeeds. */
export function createNativeSettings(ctx) {
  const routes = new Map([
    ['', ['GET', () => describe(ctx)]],
    ['/providers', ['GET', () => {
      const llm = service(ctx, 'llm');
      return { active: llm.listProviders().map(({ id, name }) => ({ id, name })), configurable: llm.listConfigurableProviders()
        .filter(row => namespaces.has(row.settingsNs)).map(row => ({
          provider: row.provider, displayName: row.displayName, settingsNs: row.settingsNs, settingsPath: [...row.settingsPath],
          ...(row.declared === undefined ? {} : { declared: row.declared }),
          ...(row.defaultBaseURL === undefined ? {} : { defaultBaseURL: row.defaultBaseURL }),
          ...(row.baseURLEnv === undefined ? {} : { baseURLEnv: [...row.baseURLEnv] }),
          ...(row.nativeAuth === undefined ? {} : { nativeAuth: { env: [...row.nativeAuth.env], oauth: row.nativeAuth.oauth } }),
          ...(row.error ? { unavailable: true } : {}),
        })) };
    }]],
    ['/mutate', ['POST', input => mutation(ctx, input)]],
    ['/credentials/describe', ['POST', input => credentials(ctx, 'describe', input)]],
    ['/credentials/set', ['POST', input => credentials(ctx, 'set', input)]],
    ['/credentials/unset', ['POST', input => credentials(ctx, 'unset', input)]],
    ['/discover-models', ['POST', (input, signal) => discover(ctx, input, signal)]],
    ['/default-model', ['POST', input => defaultModel(ctx, input)]],
    ['/providers/remove', ['POST', input => removeProvider(ctx, input)]],
  ]);
  return {
    async handle(req, res, signal) {
      try {
        const url = new URL(req.url, `http://${req.headers.host}`);
        if (url.search) throw bad();
        const route = routes.get(url.pathname.slice(base.length));
        if (!route || !url.pathname.startsWith(base)) throw missing();
        if (req.method !== route[0]) throw new SettingsError('NATIVE_SETTINGS_METHOD_NOT_ALLOWED', 405, 'method');
        const input = req.method === 'POST' ? await body(req) : undefined;
        signal?.throwIfAborted();
        const result = await route[1](input, signal);
        reply(res, 200, { ok: true, result });
      } catch (error) { errorReply(ctx, res, error); }
    },
  };
}
