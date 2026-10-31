import { createHash, randomBytes } from 'node:crypto';
import { requireMobileSession } from './auth.mjs';
import { resolveMobileLocale } from './locale.mjs';
import { localizeSetupResponse } from './backend-i18n.mjs';

const MAX_BODY = 4096;
const MAX_OUTPUT = 8192;
const MODEL_TIMEOUT = 90000;
const MODEL_ID = /^[^\s\u0000-\u001f\u007f]{1,200}$/u;
const REFERENCE = /^[A-Za-z_][A-Za-z0-9_]*$/;
const RECORD_ID = /^[a-z0-9]+(?:-[a-z0-9]+)*$/;
const ECHO_TOOL = 'droy_setup_echo';
const nativeCopy = {
  identityUnavailable: { zh: '用户资料暂不可用，请重试。', en: 'Your profile is unavailable. Try again.' },
  identityRequired: { zh: '请先保存你的名称和头像。', en: 'Save your name and avatar first.' },
  identityChanged: { zh: '用户资料已变化，请重新确认。', en: 'Your profile changed. Confirm it again.' },
  invalid: { zh: '原生安装请求格式无效。', en: 'The native setup request is invalid.' },
  notReady: { zh: '安装条件尚未完成。', en: 'Setup requirements are incomplete.' },
  unavailable: { zh: '安装检查暂不可用，请重试。', en: 'Setup checks are unavailable. Try again.' },
};
function nativeFailure(ctx, key, code, status = 400) {
  return new SetupError(code, nativeCopy[key][resolveMobileLocale(ctx)], status);
}

function profileValue(value) {
  if (!object(value) || typeof value.name !== 'string' || typeof value.avatarUrl !== 'string'
    || [...value.name].length > 80 || Buffer.byteLength(value.name) > 320 || value.name !== value.name.trim()
    || /[\u0000-\u001f\u007f]/u.test(value.name)
    || [...value.name].some(char => char.length === 1 && /[\ud800-\udfff]/u.test(char))
    || !/^(?:\/droy\/companions\/assets\/user-default\.png|\/droy\/companions\/user-profile\/attachments\/[A-Za-z0-9_.:-]{1,160})$/u.test(value.avatarUrl)) return null;
  const bio = Object.hasOwn(value, 'bio') ? value.bio : '';
  if (typeof bio !== 'string' || [...bio].length > 2000 || Buffer.byteLength(bio) > 8000 || bio !== bio.trim()
    || /[\u0000-\u0008\u000b\u000c\u000e-\u001f\u007f]/u.test(bio)
    || [...bio].some(char => char.length === 1 && /[\ud800-\udfff]/u.test(char))) return null;
  return { name: value.name, avatarUrl: value.avatarUrl, bio };
}

/** This digest binds confirmation to the saved identity, never to the old welcome address. */
export function setupUserProfileFingerprint(profile) {
  const value = profileValue(profile);
  if (!value) throw new TypeError('INVALID_SETUP_PROFILE');
  // Preserve confirmations from the pre-biography schema when no bio was saved.
  const identity = { name: value.name, avatarUrl: value.avatarUrl, ...(value.bio ? { bio: value.bio } : {}) };
  return createHash('sha256').update(JSON.stringify(identity)).digest('hex');
}

export class SetupError extends Error {
  constructor(code, message, status = 400) { super(message); this.code = code; this.status = status; }
}

function object(value) { return value !== null && typeof value === 'object' && !Array.isArray(value); }
function canonical(value) {
  if (Array.isArray(value)) return value.map(canonical);
  if (!object(value)) return value;
  return Object.fromEntries(Object.keys(value).sort().map(key => [key, canonical(value[key])]));
}
function modelIdentity(value) {
  if (!object(value) || !MODEL_ID.test(value.provider ?? '') || !MODEL_ID.test(value.model ?? '')) return null;
  return { provider: value.provider, model: value.model,
    ...(typeof value.reasoningEffort === 'string' ? { reasoningEffort: value.reasoningEffort } : {}) };
}

/** Only stable categories reach the UI: provider exceptions can contain credentials or request bodies. */
function privacyFailure(code) {
  return typeof code === 'string' && (code.startsWith('PRIVACY_')
    || code === 'DROY_REQUIRED_PROTECTION_UNAVAILABLE' || code === 'MODEL_TEST_PRIVACY_UNAVAILABLE');
}
function modelFailure(error, signal) {
  if (error instanceof SetupError) return error;
  if (signal?.aborted) return new SetupError('MODEL_TEST_INTERRUPTED', '模型验证已取消或超时，请检查网络后重试。', 503);
  const code = typeof error?.code === 'string' ? error.code : '';
  const status = error?.status;
  if (privacyFailure(code))
    return new SetupError('MODEL_TEST_PRIVACY_UNAVAILABLE', '模型测试的隐私校验尚未就绪，请稍后重试。', 503);
  if (status === 401) return new SetupError('MODEL_CREDENTIAL_REQUIRED', '模型服务拒绝了 API Key（HTTP 401），请在模型设置中更新。', 422);
  if (status === 403) return new SetupError('MODEL_CREDENTIAL_REQUIRED', '模型服务拒绝访问（HTTP 403），请检查 API Key 的权限或服务限制。', 422);
  if (['MISSING_CREDENTIAL', 'INVALID_API_KEY', 'NO_CREDENTIAL_STORE'].includes(code))
    return new SetupError('MODEL_CREDENTIAL_REQUIRED', '尚未配置有效的 API Key，请在模型设置中填写。', 422);
  if (status === 429 || ['RATE_LIMIT', 'RATE_LIMITED', 'QUOTA_EXCEEDED'].includes(code))
    return new SetupError('MODEL_QUOTA_LIMITED', '模型额度不足或请求过多，请检查额度后重试。', 422);
  if (['NO_ADAPTER', 'INVALID_MODEL', 'MODEL_NOT_FOUND', 'UNSUPPORTED_MODEL'].includes(code) || status === 404)
    return new SetupError('MODEL_NOT_CONFIGURED', '所选模型尚未配置或不可用，请在模型设置中检查。', 422);
  return new SetupError('MODEL_TEST_FAILED', '模型验证未通过，请检查模型配置、网络和服务额度后重试。', 422);
}

function safeReceipt(value) {
  if (!object(value)) return { passed: false, toolsPassed: false };
  return {
    passed: value.passed === true,
    toolsPassed: value.toolsPassed === true || value.toolCallingPassed === true,
    imageInput: value.imageInput === true,
    ...(typeof value.provider === 'string' ? { provider: value.provider } : {}),
    ...(typeof value.model === 'string' ? { model: value.model } : {}),
    ...(Number.isFinite(value.checkedAt) ? { checkedAt: value.checkedAt } : {}),
    ...(typeof value.detail === 'string' ? { detail: value.detail } : {}),
  };
}

function publicState(state) {
  if (!object(state)) throw new SetupError('SETUP_STATE_UNAVAILABLE', '安装状态暂不可用，请重试。', 503);
  const result = { ...state };
  // The native boundary already redacts this field; keep the HTTP boundary
  // explicit because it contains a digest of credential-bearing configuration.
  delete result.configFingerprint;
  if (object(result.modelVerification)) result.modelVerification = safeReceipt(result.modelVerification);
  return result;
}

/** Read only the selected route's profile and credential sources, and retain their digest privately. */
export async function setupModelFingerprint(ctx, selected) {
  const entry = ctx.llm.listConfigurableProviders?.().find(row => row.provider === selected.provider);
  const section = entry && ctx.settings.describe({ redactSecrets: false }).find(row => row.ns === entry.settingsNs);
  const profile = section && (entry.settingsPath ?? []).reduce((value, key) => value?.[key], section.value);
  const references = new Set([...(entry?.baseURLEnv ?? []), ...(entry?.nativeAuth?.env ?? [])]);
  if (typeof profile?.apiKeyEnv === 'string') references.add(profile.apiKeyEnv);
  const credentials = ctx.get('credentials');
  const resolved = {};
  for (const ref of [...references].filter(name => REFERENCE.test(name)).sort()) {
    const credential = credentials ? await credentials.resolve(ref) : undefined;
    resolved[ref] = credential?.value ?? ctx.get('launchEnvironment')?.get(ref)?.value ?? process.env[ref] ?? null;
  }
  let record;
  if (RECORD_ID.test(selected.provider) && credentials?.readRecord)
    record = await credentials.readRecord(`llm-pi-ai/${selected.provider}`);
  return createHash('sha256').update(JSON.stringify(canonical({ selection: selected, profile: profile ?? null,
    endpoint: entry?.defaultBaseURL ?? null, resolved, record: record ?? null }))).digest('hex');
}

function abortable(promise, signal) {
  signal.throwIfAborted();
  return new Promise((resolve, reject) => {
    const aborted = () => reject(signal.reason ?? new Error('aborted'));
    signal.addEventListener('abort', aborted, { once: true });
    Promise.resolve(promise).then(value => { signal.removeEventListener('abort', aborted); resolve(value); },
      error => { signal.removeEventListener('abort', aborted); reject(error); });
  });
}

async function collectModel(ctx, options, signal) {
  let text = '', finish;
  const blocks = new Map(), partials = new Map();
  let bytes = 0;
  const iterator = ctx.llm.stream({ ...options, signal })[Symbol.asyncIterator]();
  let exhausted = false;
  try {
  while (true) {
    const item = await abortable(iterator.next(), signal);
    if (item.done) { exhausted = true; break; }
    const chunk = item.value;
    signal.throwIfAborted();
    if (chunk.type === 'text-delta') {
      text += chunk.text;
      bytes += Buffer.byteLength(chunk.text);
    } else if (chunk.type === 'tool-call-delta') {
      const partial = partials.get(chunk.index) ?? { type: 'tool-call', id: chunk.id, name: '', arguments: '' };
      if (chunk.name) partial.name = chunk.name;
      partial.arguments += chunk.argumentsDelta;
      partials.set(chunk.index, partial);
      bytes += Buffer.byteLength(chunk.argumentsDelta);
    } else if (chunk.type === 'block-end') {
      blocks.set(chunk.index, chunk.block);
      bytes += Buffer.byteLength(JSON.stringify(chunk.block));
    } else if (chunk.type === 'finish') finish = chunk.reason;
    if (bytes > MAX_OUTPUT) throw new SetupError('MODEL_RESPONSE_TOO_LARGE', '模型验证返回过长，请检查模型后重试。', 422);
  }
  } finally {
    if (!exhausted && iterator.return) {
      const closing = iterator.return();
      if (signal.aborted) Promise.resolve(closing).catch(() => {});
      else await closing;
    }
  }
  signal.throwIfAborted();
  if (finish?.kind === 'error' || finish?.kind === 'aborted') throw modelFailure(finish.failure, signal);
  if (finish?.kind !== 'stop' && finish?.kind !== 'tool-calls')
    throw new SetupError('MODEL_RESPONSE_INCOMPLETE', '模型验证未完整返回，请检查模型后重试。', 422);
  const complete = [...blocks.values()];
  const calls = complete.filter(block => block.type === 'tool-call');
  if (!calls.length) calls.push(...partials.values());
  return { text: text || complete.filter(block => block.type === 'text').map(block => block.text).join(''), calls, finish };
}

function validEcho(result, nonce) {
  if (result.finish.kind !== 'tool-calls' || result.calls.length !== 1) return false;
  const call = result.calls[0];
  if (call.name !== ECHO_TOOL || typeof call.arguments !== 'string' || call.arguments.length > 2048) return false;
  try {
    const args = JSON.parse(call.arguments);
    return object(args) && Object.keys(args).length === 1 && args.value === nonce;
  } catch { return false; }
}

/** One-shot LLM requests bypass Agent assembly so no history, memory or device tools enter a test. */
export function createSetupService(ctx, bridge) {
  let testing = false;
  const lifetime = new AbortController();
  const selection = () => modelIdentity(ctx.get('agentDefaultModel')?.currentSelection());

  async function models(signal) {
    const providers = ctx.llm.listProviders();
    const entries = await Promise.all(providers.map(async provider => {
      try {
        const rows = await abortable(ctx.llm.listModels(provider.id), signal);
        return { models: rows.filter(row => modelIdentity({ provider: provider.id, model: row.id }))
          .map(row => ({ provider: provider.id, model: row.id, name: row.name || row.id, providerName: provider.name })) };
      } catch (error) {
        const failure = modelFailure(error);
        return { models: [], error: { provider: provider.id, code: failure.code, message: failure.message } };
      }
    }));
    return { models: entries.flatMap(row => row.models), modelErrors: entries.flatMap(row => row.error ? [row.error] : []) };
  }

  async function invalidateChanged(signal) {
    const current = selection();
    if (!current) { await bridge.setup({ operation: 'recordModel', passed: false }, signal); return null; }
    const fingerprint = await setupModelFingerprint(ctx, current);
    const match = await bridge.setup({ operation: 'checkModelFingerprint', configFingerprint: fingerprint }, signal);
    if (match.matches !== true) await bridge.setup({ operation: 'recordModel', passed: false }, signal);
    return current;
  }

  async function envelope(state, current, includeModels = false, signal) {
    const safe = publicState(state);
    const catalog = includeModels ? await models(signal) : undefined;
    return { ok: true, state: safe, selection: current ? { provider: current.provider, model: current.model } : null,
      modelVerification: safeReceipt(safe.modelVerification), ...catalog,
      ...(catalog ? { selectionAvailable: catalog.models.some(row => row.provider === current?.provider && row.model === current?.model) } : {}) };
  }

  function nativeSignal(signal) {
    return AbortSignal.any([lifetime.signal, ...(signal ? [signal] : []), AbortSignal.timeout(15000)]);
  }

  async function nativeProfile(signal) {
    const store = (ctx.get?.('droyCompanions') ?? ctx.droyCompanions)?.store;
    if (typeof store?.getUserProfile !== 'function')
      throw nativeFailure(ctx, 'identityUnavailable', 'NATIVE_SETUP_IDENTITY_UNAVAILABLE', 503);
    let saved;
    try { saved = await abortable(store.getUserProfile(), signal); }
    catch { throw nativeFailure(ctx, 'identityUnavailable', 'NATIVE_SETUP_IDENTITY_UNAVAILABLE', 503); }
    const profile = profileValue(saved);
    if (!profile) throw nativeFailure(ctx, 'identityUnavailable', 'NATIVE_SETUP_IDENTITY_UNAVAILABLE', 503);
    return { profile, fingerprint: setupUserProfileFingerprint(profile), ready: profile.name.length > 0 };
  }

  async function nativeContext(signal) {
    const current = await invalidateChanged(signal);
    const identity = await nativeProfile(signal);
    // Completion carries a fresh live digest instead of trusting an earlier UI status response.
    const fingerprint = current ? await setupModelFingerprint(ctx, current) : null;
    if (JSON.stringify(canonical(current)) !== JSON.stringify(canonical(selection()))) {
      await bridge.setup({ operation: 'recordModel', passed: false }, signal);
      throw new SetupError('MODEL_CONFIG_CHANGED', resolveMobileLocale(ctx) === 'zh'
        ? '模型配置已变化，请重新验证。' : 'Model configuration changed. Verify it again.', 409);
    }
    if (fingerprint !== null) {
      const match = await bridge.setup({ operation: 'checkModelFingerprint', configFingerprint: fingerprint }, signal);
      if (match.matches !== true) await bridge.setup({ operation: 'recordModel', passed: false }, signal);
    }
    signal.throwIfAborted();
    return { current, identity, facts: { configFingerprint: fingerprint,
      profileFingerprint: identity.fingerprint, profileReady: identity.ready } };
  }

  async function nativeEnvelope(operation, signal) {
    const live = await nativeContext(signal);
    const state = await bridge.setup({ operation, ...live.facts }, signal);
    if (!object(state) || typeof state.completed !== 'boolean' || typeof state.canComplete !== 'boolean'
      || !object(state.identity) || typeof state.identity.confirmed !== 'boolean')
      throw nativeFailure(ctx, 'unavailable', 'NATIVE_SETUP_STATE_UNAVAILABLE', 503);
    if (operation === 'nativeComplete' && !state.completed)
      throw nativeFailure(ctx, 'notReady', 'NATIVE_SETUP_NOT_READY', 409);
    const value = await envelope(state, live.current);
    value.state.identity = { ...value.state.identity, profile: live.identity.profile };
    return value;
  }

  return {
    async status(signal) {
      const bounded = AbortSignal.any([lifetime.signal, ...(signal ? [signal] : []), AbortSignal.timeout(15000)]);
      const current = await invalidateChanged(bounded);
      return envelope(await bridge.setup({ operation: 'status' }, bounded), current, true, bounded);
    },
    async verify(signal) {
      const current = await invalidateChanged(signal);
      return envelope(await bridge.setup({ operation: 'verify' }, signal), current);
    },
    async complete(signal) {
      const current = await invalidateChanged(signal);
      return envelope(await bridge.setup({ operation: 'complete' }, signal), current);
    },
    async nativeStatus(signal) { return nativeEnvelope('nativeStatus', nativeSignal(signal)); },
    async nativeComplete(signal) { return nativeEnvelope('nativeComplete', nativeSignal(signal)); },
    async nativeConfirmIdentity(input, signal) {
      if (!object(input) || Object.keys(input).length !== 1 || !Object.hasOwn(input, 'expectedProfile')
        || !object(input.expectedProfile) || !['avatarUrl,bio,name', 'avatarUrl,name'].includes(Object.keys(input.expectedProfile).sort().join(','))
        || !profileValue(input.expectedProfile))
        throw nativeFailure(ctx, 'invalid', 'INVALID_NATIVE_SETUP_REQUEST');
      const bounded = nativeSignal(signal);
      const identity = await nativeProfile(bounded);
      if (!identity.ready) throw nativeFailure(ctx, 'identityRequired', 'NATIVE_SETUP_IDENTITY_REQUIRED', 409);
      if (setupUserProfileFingerprint(input.expectedProfile) !== identity.fingerprint)
        throw nativeFailure(ctx, 'identityChanged', 'NATIVE_SETUP_IDENTITY_CHANGED', 409);
      bounded.throwIfAborted();
      await bridge.setup({ operation: 'nativeAcknowledgeIdentity', profileFingerprint: identity.fingerprint, profileReady: true }, bounded);
      return nativeEnvelope('nativeStatus', bounded);
    },
    dispose() { lifetime.abort(); },
    async test(input = {}, signal) {
      if (!object(input) || Object.keys(input).some(key => !['provider', 'model'].includes(key))
        || (Object.hasOwn(input, 'provider') !== Object.hasOwn(input, 'model')))
        throw new SetupError('INVALID_MODEL_SELECTION', '请选择模型，不要在安装验证中提交 API Key。');
      if (testing) throw new SetupError('MODEL_TEST_BUSY', '模型验证正在进行，请等待结果。', 409);
      const selected = Object.hasOwn(input, 'provider') ? modelIdentity(input) : selection();
      if (!selected) throw new SetupError('MODEL_NOT_CONFIGURED', '请先在模型设置中配置并选择文本模型。', 422);
      testing = true;
      const combined = AbortSignal.any([lifetime.signal, ...(signal ? [signal] : []), AbortSignal.timeout(MODEL_TIMEOUT)]);
      let testSession;
      try {
        await bridge.setup({ operation: 'recordModel', passed: false }, combined);
        if (!ctx.llm.listProviders().some(provider => provider.id === selected.provider))
          throw new SetupError('MODEL_NOT_CONFIGURED', '所选模型提供商尚未配置，请打开模型设置。', 422);
        const metadata = await abortable(ctx.llm.resolveModelInfo(selected.provider, selected.model, combined), combined);
        if (Object.hasOwn(input, 'provider')) {
          const defaults = ctx.get('agentDefaultModel');
          if (!defaults?.saveSelection) throw new SetupError('MODEL_SELECTION_UNAVAILABLE', '默认模型尚未就绪，请重新打开应用。', 503);
          await defaults.saveSelection(selected);
        }
        const before = await setupModelFingerprint(ctx, selected);
        const language = resolveMobileLocale(ctx);
        const privacy = ctx.get('droyPrivacy');
        if (typeof privacy?.openSetupTestSession !== 'function')
          throw new SetupError('MODEL_TEST_PRIVACY_UNAVAILABLE', '模型测试的隐私校验尚未就绪，请稍后重试。', 503);
        testSession = privacy.openSetupTestSession();
        const greeting = await collectModel(ctx, { ...selected, sessionId: testSession.sessionId, maxTokens: 512,
          system: language === 'zh' ? '这是 Droy 首次安装的连接测试。请只用简短中文回复一句问候。不要请求任何个人资料。'
            : 'This is the Droy first-install connection test. Reply with one short English greeting. Do not request personal information.',
          messages: [{ role: 'user', content: [{ type: 'text', text: language === 'zh' ? '你好' : 'Hello' }] }] }, combined);
        if (!greeting.text.trim() || greeting.calls.length || greeting.finish.kind !== 'stop')
          throw new SetupError('MODEL_GREETING_FAILED', '模型未返回完整的文本问候，请检查所选文本模型。', 422);
        const nonce = randomBytes(12).toString('hex');
        let toolsPassed = false;
        try {
          const toolResult = await collectModel(ctx, { ...selected, sessionId: testSession.sessionId, maxTokens: 768,
            system: language === 'zh' ? '这是 Droy 安装时的工具协议测试。只调用一次 droy_setup_echo，把用户提供的 value 原样放入参数。不调用其他工具，不输出说明。'
              : 'This is the Droy setup tool protocol test. Call droy_setup_echo exactly once with the user-provided value unchanged. Do not call other tools or output explanations.',
            messages: [{ role: 'user', content: [{ type: 'text', text: JSON.stringify({ value: nonce }) }] }],
            tools: [{ name: ECHO_TOOL, description: language === 'zh' ? '仅在安装测试内回读固定测试值，不读取手机、不执行任何操作。'
              : 'Echo a fixed value only within setup verification. Does not read the phone or perform actions.',
              parameters: { type: 'object', properties: { value: { type: 'string' } }, required: ['value'], additionalProperties: false } }],
          }, combined);
          toolsPassed = validEcho(toolResult, nonce);
        } catch (error) {
          // A text-only model remains useful for dialogue. Cancellation aborts
          // the whole test; an unsupported tool protocol is a separate result.
          if (combined.aborted || privacyFailure(error?.code)) throw error;
        }
        combined.throwIfAborted();
        const after = await setupModelFingerprint(ctx, selected);
        const current = selection();
        if (after !== before || !current || current.provider !== selected.provider || current.model !== selected.model)
          throw new SetupError('MODEL_CONFIG_CHANGED', '验证期间模型配置发生变化，请使用当前配置重新验证。', 409);
        const receipt = { operation: 'recordModel', passed: true, toolsPassed,
          provider: selected.provider, model: selected.model, configFingerprint: after,
          detail: toolsPassed ? '模型对话和工具调用验证通过。' : '模型对话可用，工具调用尚未通过。',
          imageInput: metadata.inputModalities?.includes('image') === true, checkedAt: Date.now() };
        const state = await bridge.setup(receipt, combined);
        return { ...await envelope(state, current), test: { ...safeReceipt(receipt),
          message: toolsPassed ? '模型对话和工具调用验证通过。' : '模型对话验证通过；工具调用尚未通过，可先使用对话。' } };
      } catch (error) {
        throw modelFailure(error, combined);
      } finally { testSession?.close(); testing = false; }
    },
  };
}

async function requestBody(req, limit = MAX_BODY) {
  const parts = []; let bytes = 0;
  for await (const part of req) {
    bytes += part.length;
    if (bytes > limit) throw new SetupError('SETUP_REQUEST_TOO_LARGE', '安装请求过大。', 413);
    parts.push(Buffer.from(part));
  }
  let value;
  try { value = JSON.parse(Buffer.concat(parts).toString('utf8') || '{}'); }
  catch { throw new SetupError('INVALID_SETUP_REQUEST', '安装请求格式无效。'); }
  if (!object(value)) throw new SetupError('INVALID_SETUP_REQUEST', '安装请求格式无效。');
  return value;
}

export function installSetupServices(ctx, bridge) {
  const service = createSetupService(ctx, bridge);
  ctx.effect?.(() => () => service.dispose());
  for (const [path, method, operation] of [
    ['/droy/setup/status', 'GET', 'status'], ['/droy/setup/model-test', 'POST', 'test'],
    ['/droy/setup/verify', 'POST', 'verify'], ['/droy/setup/complete', 'POST', 'complete'],
    ['/droy/setup/native/status', 'GET', 'nativeStatus'],
    ['/droy/setup/native/identity-confirm', 'POST', 'nativeConfirmIdentity'],
    ['/droy/setup/native/complete', 'POST', 'nativeComplete'],
  ]) {
    ctx.webServer.register({ kind: 'exact', path, async handler(req, res) {
      if (!requireMobileSession(req, res)) return;
      const json = (status, value) => {
        if (res.destroyed || res.writableEnded) return;
        res.writeHead(status, { 'Content-Type': 'application/json; charset=utf-8', 'Cache-Control': 'no-store' });
        res.end(JSON.stringify(localizeSetupResponse(value, resolveMobileLocale(ctx))));
      };
      if (req.method !== method) { json(405, { ok: false, code: 'METHOD_NOT_ALLOWED', message: '安装接口请求方式无效。' }); return; }
      const cancellation = new AbortController();
      const aborted = () => cancellation.abort();
      const closed = () => { if (!res.writableEnded) cancellation.abort(); };
      req.once('aborted', aborted); res.once('close', closed);
      try {
        const native = operation.startsWith('native');
        if (native && (new URL(req.url, 'http://127.0.0.1').pathname !== path || new URL(req.url, 'http://127.0.0.1').search
          || method === 'POST' && !/^application\/json(?:\s*;|\s*$)/i.test(req.headers['content-type'] ?? '')))
          throw nativeFailure(ctx, 'invalid', 'INVALID_NATIVE_SETUP_REQUEST');
        const input = method === 'POST' ? await requestBody(req, operation === 'nativeConfirmIdentity' ? 16384 : MAX_BODY) : {};
        if (operation !== 'test' && operation !== 'nativeConfirmIdentity' && Object.keys(input).length)
          throw new SetupError('INVALID_SETUP_REQUEST', '此安装检查不接受配置或凭据。');
        const result = operation === 'test' || operation === 'nativeConfirmIdentity' ? await service[operation](input, cancellation.signal) : await service[operation](cancellation.signal);
        json(200, result);
      } catch (error) {
        const failure = error instanceof SetupError ? error : operation.startsWith('native')
          ? nativeFailure(ctx, operation === 'nativeComplete' ? 'notReady' : 'unavailable', operation === 'nativeComplete' ? 'NATIVE_SETUP_NOT_READY' : 'NATIVE_SETUP_STATE_UNAVAILABLE', operation === 'nativeComplete' ? 409 : 503)
          : operation === 'test' ? modelFailure(error, cancellation.signal)
          : new SetupError(operation === 'complete' ? 'SETUP_NOT_READY' : 'SETUP_STATE_UNAVAILABLE',
            operation === 'complete' ? '安装条件尚未通过，请重新检查未完成项。' : '安装检查暂不可用，请重试。', operation === 'complete' ? 409 : 503);
        json(failure.status, { ok: false, code: failure.code, message: failure.message });
      } finally { req.removeListener('aborted', aborted); res.removeListener('close', closed); }
    } });
  }
  return service;
}
