import { createHash } from 'node:crypto';
import { PrivacyError, normalizeEndpoint, sessionIdentity } from './policy.mjs';

export const name = 'droy-privacy';
export const inject = ['llm', 'droyPrivacyHost'];
export const PREPARED_ROUTE = Symbol.for('droy.privacy.prepared-route.v1');
const REQUEST_PURPOSES = new Set(['conversation', 'compaction', 'session-title', 'extraction']);
const fail = code => { throw new PrivacyError(code); };
const purposeOf = value => {
  const purpose = value ?? 'conversation';
  if (!REQUEST_PURPOSES.has(purpose)) fail('PRIVACY_UNSUPPORTED_PURPOSE');
  return purpose;
};
function stable(value) {
  if (Array.isArray(value)) return value.map(stable);
  if (value && typeof value === 'object') return Object.fromEntries(Object.keys(value).sort()
    .filter(key => value[key] !== undefined).map(key => [key, stable(value[key])]));
  return value;
}
function abortError() { return new PrivacyError('PRIVACY_AUTHORIZATION_REVOKED'); }
const DISPATCH_MESSAGES = Object.freeze({
  PRIVACY_SESSION_UNKNOWN: {
    zh: '这段旧对话的隐私状态无法确认，请新建对话继续。',
    en: 'The privacy status of this older conversation cannot be verified. Start a new conversation to continue.',
  },
  PRIVACY_SESSION_FORGOTTEN: {
    zh: '这段对话包含已忘记的内容，请新建对话继续。',
    en: 'This conversation contains forgotten information. Start a new conversation to continue.',
  },
  PRIVACY_ENDPOINT_NOT_AUTHORIZED: {
    zh: '当前模型尚未获准处理这些私人信息，请到记忆设置的“处理授权”中检查。',
    en: 'This model is not authorized to process this private information. Check Processing permissions in Memory settings.',
  },
  PRIVACY_EXECUTION_UNVERIFIED: {
    zh: '私人信息保护尚未就绪，请检查 Root 授权和记忆设置中的保护状态后重试。',
    en: 'Private information protection is not ready. Check Root permission and the protection status in Memory settings, then try again.',
  },
  PRIVACY_NOT_INITIALIZED: {
    zh: '私人信息保护正在初始化，请检查 Root 授权和记忆设置中的保护状态后重试。',
    en: 'Private information protection is initializing. Check Root permission and the protection status in Memory settings, then try again.',
  },
});
function dispatchError(error, ctx) {
  if (!(error instanceof PrivacyError) && !Object.hasOwn(DISPATCH_MESSAGES, error?.code)) return error;
  const preference = ctx.get('settings')?.describe().find(entry => entry.ns === 'locale')?.value?.preference;
  const locale = typeof preference === 'string' && /^zh(?:[-_]|$)/i.test(preference) ? 'zh' : 'en';
  const result = new PrivacyError(error.code);
  result.message = DISPATCH_MESSAGES[error.code]?.[locale] ?? error.message;
  // DSH's adapter boundary accepts this immutable failure snapshot; a foreign
  // Error.code alone would be normalized to UNKNOWN.
  result.failure = Object.freeze({ code: result.code, message: result.message });
  return result;
}

/** Trusted host service. No method here is a model-facing tool. */
export function createDshPrivacyService({ ctx, store, host, fetchImpl = globalThis.fetch } = {}) {
  const capabilities = new WeakMap();
  const outbound = new WeakMap();
  const calls = new Set();
  const invalidationListeners = new Set();
  const pendingProtection = new Map();
  let protectionEpoch = 0;
  let disposed = false;
  let everActive = false;
  let storePromise;
  async function getStore() {
    if (!store && !host?.store) fail('PRIVACY_HOST_UNAVAILABLE');
    storePromise ??= Promise.resolve().then(() => typeof store === 'function' ? store() : store ?? host.store()).catch(error => {
      // Native activation can finish after an early session lifecycle event.
      // Integrity failures remain sticky; only a not-yet-created store retries.
      if (error?.code === 'PRIVACY_NOT_INITIALIZED') storePromise = undefined;
      throw error;
    });
    return storePromise;
  }

  async function status() {
    if (disposed) return { active: everActive, ready: false, reason: 'PRIVACY_SERVICE_CLOSED' };
    if (!host || (!store && !host.store)) return { active: false, ready: false, reason: 'PRIVACY_HOST_UNAVAILABLE' };
    const state = await host.status();
    if (!state || typeof state.active !== 'boolean' || typeof state.ready !== 'boolean') fail('PRIVACY_INVALID_HOST_STATE');
    everActive ||= state.active;
    if (everActive && !state.active) fail('PRIVACY_ACTIVATION_DOWNGRADE');
    if (state.active && state.ready) await (await getStore()).status();
    return { active: state.active, ready: state.ready,
      reason: state.ready ? null : state.reason ?? 'PRIVACY_EXECUTION_UNVERIFIED' };
  }
  async function requireReady(activate = false) {
    const state = await status();
    if (!state.ready) fail('PRIVACY_EXECUTION_UNVERIFIED');
    if (activate && !state.active) {
      if (typeof host.activate !== 'function') fail('PRIVACY_ACTIVATION_REQUIRED');
      await host.activate();
      const activated = await status();
      if (!activated.active || !activated.ready) fail('PRIVACY_ACTIVATION_REQUIRED');
    }
  }
  function routingIdentity() {
    const value = host?.routingIdentity?.();
    if (typeof value !== 'string' || !value.length || value.length > 512) fail('PRIVACY_ROUTING_IDENTITY_UNAVAILABLE');
    return value;
  }
  function issue(detail) {
    const capability = Object.freeze(Object.create(null));
    capabilities.set(capability, Object.freeze(detail));
    return capability;
  }
  function details(capability, purpose) {
    const detail = capability && capabilities.get(capability);
    if (!detail) fail('PRIVACY_UNREVIEWED_ROUTE');
    if (detail.routingIdentity !== routingIdentity()) fail('PRIVACY_ROUTING_CHANGED');
    return { ...detail, endpoint: normalizeEndpoint({ url: detail.url,
      purpose: purposeOf(purpose ?? detail.purpose), configVersion: detail.configVersion }) };
  }
  function describeRoute(capability) {
    const detail = details(capability);
    return { ...detail.endpoint, provider: detail.provider, model: detail.model };
  }
  function capturePiRoute({ provider, model, profile, resolvedModel }) {
    // These reviewed SSE protocols forward a request-scoped fetch implementation.
    // WebSocket and provider-native transports need their own reviewed binding.
    const endpoint = resolvedModel?.api === 'openai-completions' ? '/chat/completions'
      : resolvedModel?.api === 'openai-responses' ? '/responses' : undefined;
    if (disposed || !endpoint
      || (profile.transport && profile.transport !== 'sse')) return undefined;
    let identity, url;
    try {
      identity = routingIdentity();
      const base = normalizeEndpoint({ url: resolvedModel.baseUrl,
        purpose: 'conversation', configVersion: '0'.repeat(64) }).url.replace(/\/+$/, '');
      url = normalizeEndpoint({ url: `${base}${endpoint}`,
        purpose: 'conversation', configVersion: '0'.repeat(64) }).url;
    } catch (error) {
      if (error instanceof PrivacyError) return undefined;
      throw error;
    }
    const configVersion = createHash('sha256').update(JSON.stringify(stable({
      provider, model, url, api: resolvedModel.api, modelProvider: resolvedModel.provider,
      modelHeaders: resolvedModel.headers, profileHeaders: profile.headers,
      credentialRef: profile.apiKeyEnv, transport: profile.transport, routingIdentity: identity,
    }))).digest('hex');
    return issue({ provider, model, url, configVersion, routingIdentity: identity, purpose: 'conversation' });
  }
  async function prepareRoute(config, purpose = 'conversation', signal) {
    await requireReady();
    signal?.throwIfAborted();
    if (!config || typeof config.provider !== 'string' || typeof config.model !== 'string') fail('PRIVACY_INVALID_MODEL');
    const prepared = await ctx.llm.prepareCall({ provider: config.provider, model: config.model }, signal);
    return routeForPrepared(prepared, purpose);
  }
  function routeForPrepared(prepared, purpose = 'conversation') {
    const detail = details(prepared[PREPARED_ROUTE], purpose);
    return issue({ ...detail, endpoint: undefined, purpose: detail.endpoint.purpose });
  }
  async function routeForAgent(agent, purpose = 'conversation', signal) {
    // A running tool belongs to the last persisted request, even if the user
    // has selected another model for the next turn. The next dispatch rechecks.
    const config = agent?.session?.requestHeader?.()?.config ?? agent?.options;
    return prepareRoute(config, purpose, signal);
  }
  function abortCalls(predicate) {
    for (const call of calls) if (predicate(call)) call.controller.abort(abortError());
  }
  function notifyInvalidated(sessionId) {
    const event = Object.freeze(sessionId === undefined ? {} : { sessionId });
    for (const listener of invalidationListeners) listener(event);
  }
  function onInvalidated(listener) {
    if (typeof listener !== 'function') throw new TypeError('Invalid privacy invalidation listener');
    if (disposed) { listener(Object.freeze({})); return () => {}; }
    invalidationListeners.add(listener);
    return () => invalidationListeners.delete(listener);
  }
  async function outputState(sessionId) {
    const epoch = protectionEpoch;
    const state = await status();
    if (pendingProtection.has(sessionId)) return { active: true, state: 'protected' };
    if (!state.active) return epoch === protectionEpoch ? { active: false, state: 'inactive' } : { active: true, state: 'unknown' };
    if (sessionId === undefined || sessionId === null || sessionId === '') return { active: true, state: 'unknown' };
    sessionIdentity(sessionId);
    if (pendingProtection.has(sessionId)) return { active: true, state: 'protected' };
    const record = await (await getStore()).sessionState(sessionId);
    if (pendingProtection.has(sessionId)) return { active: true, state: 'protected' };
    if (epoch !== protectionEpoch) return { active: true, state: 'unknown' };
    return { active: true, state: record.state };
  }
  async function beforeDisclosure({ sessionId, categories, sourceRefs, route, signal } = {}) {
    await requireReady(true);
    signal?.throwIfAborted();
    const detail = details(route);
    sessionIdentity(sessionId);
    pendingProtection.set(sessionId, (pendingProtection.get(sessionId) ?? 0) + 1);
    protectionEpoch++;
    let persisted = false;
    try {
      notifyInvalidated(sessionId);
      abortCalls(call => call.sessionId === sessionId);
      await (await getStore()).protectSession(sessionId, { categories, sourceRefs });
      persisted = true;
      await ctx.get('droyExecution')?.beforeDisclosure(sessionId);
      await (await getStore()).assertAllowed({ sessionId, endpoint: detail.endpoint });
      await requireReady();
      signal?.throwIfAborted();
    } finally {
      const pending = pendingProtection.get(sessionId) - 1;
      if (pending) pendingProtection.set(sessionId, pending); else pendingProtection.delete(sessionId);
      if (!persisted) notifyInvalidated(sessionId);
    }
  }
  async function onAgentCreated({ agent, source, signal }) {
    if (!host || !(await status()).active) return;
    const current = await (await getStore()).sessionState(sessionIdentity(agent?.session?.id));
    if (current.state !== 'unknown') return;
    const header = agent.session.header;
    signal?.throwIfAborted();
    if (header.parentSession) {
      const parent = await (await getStore()).sessionState(header.parentSession);
      if (parent.state !== 'unknown') await (await getStore()).inheritSession(header.parentSession, header.id);
      return;
    }
    // Imported/resumed, compacted or seeded content has no clean-state proof.
    // Keep it inspectable locally; dispatch will refuse unknown provenance.
    if (source !== 'startup' || header.isSeeded || agent.session.snapshotEvents().length !== 0) return;
    await (await getStore()).registerCleanSession(header.id);
  }
  async function checkDispatch(options, route) {
    const state = await status();
    if (!state.active) {
      // Inactive is allowed only before protection exists. The host persists
      // activation; the store catches a mistaken downgrade for a known session.
      if (storePromise && options.sessionId) {
        const record = await (await getStore()).sessionState(options.sessionId);
        if (record.state === 'protected' || record.state === 'forgotten') fail('PRIVACY_EXECUTION_UNVERIFIED');
      }
      return undefined;
    }
    sessionIdentity(options.sessionId);
    const session = await (await getStore()).sessionState(options.sessionId);
    if (session.state === 'unknown') fail('PRIVACY_SESSION_UNKNOWN');
    if (session.state === 'forgotten') fail('PRIVACY_SESSION_FORGOTTEN');
    if (session.state === 'known-clean') return undefined;
    await requireReady();
    const detail = details(route, options.purpose);
    if (detail.provider !== options.provider || detail.model !== options.model) fail('PRIVACY_ROUTE_MISMATCH');
    await (await getStore()).assertAllowed({ sessionId: options.sessionId, endpoint: detail.endpoint });
    return detail;
  }
  async function authorizeExecution(agent, signal) {
    const state = await status();
    if (!state.active) return { state: 'inactive' };
    const sessionId = sessionIdentity(agent?.session?.id);
    const record = await (await getStore()).sessionState(sessionId);
    if (record.state === 'unknown') fail('PRIVACY_SESSION_UNKNOWN');
    if (record.state === 'forgotten') fail('PRIVACY_SESSION_FORGOTTEN');
    if (record.state === 'protected') {
      const route = await routeForAgent(agent, 'conversation', signal);
      await (await getStore()).assertAllowed({ sessionId, endpoint: details(route).endpoint });
    }
    signal?.throwIfAborted();
    return { state: record.state };
  }
  async function getSourceSession(sessionId, { route, signal } = {}) {
    await requireReady();
    sessionIdentity(sessionId);
    const record = await (await getStore()).sessionState(sessionId);
    if (record.state === 'unknown') fail('PRIVACY_SESSION_UNKNOWN');
    if (record.state === 'forgotten') fail('PRIVACY_SESSION_FORGOTTEN');
    if (route) await (await getStore()).assertAllowed({ sessionId, endpoint: details(route).endpoint });
    signal?.throwIfAborted();
    return { state: record.state, categories: [...record.categories], sourceRefs: [...record.sourceRefs], revision: record.revision };
  }
  async function* wrapDispatch(options, route, dispatch) {
    try {
      const detail = await checkDispatch(options, route);
      if (!detail) { yield* dispatch(options); return; }
      if (calls.size >= 64) fail('PRIVACY_DISPATCH_BUSY');
      const controller = new AbortController();
      const signal = options.signal ? AbortSignal.any([controller.signal, options.signal]) : controller.signal;
      const scoped = { ...options, signal };
      const call = { sessionId: options.sessionId, detail, controller, signal };
      calls.add(call);
      outbound.set(scoped, call);
      try { signal.throwIfAborted(); yield* dispatch(scoped); }
      finally { outbound.delete(scoped); calls.delete(call); controller.abort('privacy dispatch finished'); }
    } catch (error) { throw dispatchError(error, ctx); }
  }
  function fetchFor(options) {
    const call = outbound.get(options);
    if (!call) return undefined;
    return async (input, init) => {
      call.signal.throwIfAborted();
      const request = new Request(input, init);
      if (request.url !== call.detail.endpoint.url) fail('PRIVACY_DESTINATION_MISMATCH');
      if (request.method !== 'POST') fail('PRIVACY_UNSUPPORTED_HTTP_METHOD');
      await requireReady();
      if (call.detail.routingIdentity !== routingIdentity()) fail('PRIVACY_ROUTING_CHANGED');
      await (await getStore()).assertAllowed({ sessionId: call.sessionId, endpoint: call.detail.endpoint });
      call.signal.throwIfAborted();
      return fetchImpl(new Request(request, { redirect: 'error',
        signal: AbortSignal.any([request.signal, call.signal]) }));
    };
  }
  async function grantEndpoint(route, { categories } = {}) {
    await requireReady();
    return (await getStore()).grantEndpoint({ ...details(route).endpoint, categories });
  }
  async function revokeEndpoint(route) {
    const endpoint = details(route).endpoint;
    const result = await (await getStore()).revokeEndpoint(endpoint);
    abortCalls(call => Object.keys(endpoint).every(key => call.detail.endpoint[key] === endpoint[key]));
    notifyInvalidated();
    return result;
  }
  async function forgetSources(refs) {
    notifyInvalidated();
    const result = await (await getStore()).forgetSources(refs);
    for (const call of calls) if ((await (await getStore()).sessionState(call.sessionId)).state === 'forgotten') call.controller.abort(abortError());
    return result;
  }
  function dispose() {
    disposed = true;
    abortCalls(() => true);
    notifyInvalidated(); invalidationListeners.clear();
  }
  function invalidate() { abortCalls(() => true); notifyInvalidated(); }
  return Object.freeze({ status, prepareRoute, routeForPrepared, routeForAgent, describeRoute,
    capturePiRoute, wrapDispatch, fetchFor, onAgentCreated, beforeDisclosure,
    grantEndpoint, revokeEndpoint, forgetSources, authorizeExecution, getSourceSession, outputState, onInvalidated, invalidate, dispose });
}

export function apply(ctx) {
  const host = ctx.get('droyPrivacyHost');
  const service = createDshPrivacyService({ ctx, host });
  ctx.provide('droyPrivacy', service);
  ctx.on('agent/created', payload => service.onAgentCreated(payload));
  ctx.effect(() => () => service.dispose());
}
