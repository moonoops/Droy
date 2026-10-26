import { createHash } from 'node:crypto';
import { EXTRACTION_INSTRUCTIONS, MAINTENANCE_POLICY } from '../memory/maintenance.mjs';
import { resolveMobileLocale } from './locale.mjs';

const PURPOSE = 'extraction';
const MAX_SEGMENT_EVENTS = 16;
const MAX_OUTPUT_BYTES = 32768;
const fail = code => { throw Object.assign(new Error(code), { code }); };
const cancelled = signal => { if (signal?.aborted) fail(signal.reason?.name === 'TimeoutError' ? 'MEMORY_EXTRACTION_TIMEOUT' : 'MEMORY_MAINTENANCE_CANCELLED'); };
const identity = value => {
  if (typeof value !== 'string' || !/^[A-Za-z0-9_.:-]{1,120}$/.test(value)) fail('MEMORY_EXTRACTION_SOURCE_ID');
  return value;
};
const hash = value => createHash('sha256').update(JSON.stringify(value)).digest('hex');
const sourceIdFor = id => `dsh:${identity(id)}`;
const sessionRef = id => `dsh-session:${identity(id)}`;
const headerRevision = header => hash([header.id, header.version, header.createdAt, header.parentSession ?? null, header.isSeeded ?? false]);
function segment(source) {
  if (!source.sourceId?.startsWith('dsh:')) fail('MEMORY_EXTRACTION_SOURCE_ID');
  const sessionId = identity(source.sourceId.slice(4));
  const match = /^v1:(\d+):(\d+):([a-f0-9]{64})$/.exec(source.revision);
  if (!match) fail('MEMORY_EXTRACTION_SOURCE_REVISION');
  const from = Number(match[1]), to = Number(match[2]);
  if (!Number.isSafeInteger(from) || !Number.isSafeInteger(to) || from < 0 || to <= from || to - from > MAX_SEGMENT_EVENTS) fail('MEMORY_EXTRACTION_SOURCE_REVISION');
  return { sessionId, from, to, header: match[3] };
}
function projectEvent(event) {
  let content, actor;
  if (event.type === 'user/message') { content = event.data?.content; actor = 'user'; }
  else if (event.type === 'assistant/message') { content = event.data?.message?.content; actor = 'droy'; }
  else if (event.type === 'tool/result') { content = event.data?.message?.content; actor = 'tool_observation'; }
  else return null;
  if (!Array.isArray(content)) fail('MEMORY_EXTRACTION_SOURCE_FORMAT');
  const text = content.filter(block => block?.type === 'text').map(block => block.text).join('\n');
  if (typeof text !== 'string') fail('MEMORY_EXTRACTION_SOURCE_FORMAT');
  if (!text.trim()) return null;
  return { seq: event.seq, actor, ...(event.type === 'tool/result' ? { isError: event.data.message.isError === true } : {}), text };
}
function requestInput(input, locale) {
  return { system: EXTRACTION_INSTRUCTIONS[locale],
    messages: [{ role: 'user', content: [{ type: 'text', text: JSON.stringify({ sources: input.sources }) }] }] };
}

/** Adapter over DSH-owned model dispatch and session persistence. No Agent or
 * second session database is created; queued descriptors carry identities only. */
export function createDshMemoryExtraction({ ctx, privacy, settings,
  getModel = () => ctx.agentDefaultModel?.currentSelection(), reportError = () => {} }) {
  if (!ctx?.llm || !ctx?.sessionPersistence || !privacy || typeof settings !== 'function') fail('MEMORY_EXTRACTION_CONFIGURATION');
  const routes = new WeakMap(), starts = new Map(), pending = new Set();
  const lifetime = new AbortController();
  let unsubscribe, disposed = false;
  const scopeSignal = signal => signal ? AbortSignal.any([lifetime.signal, signal]) : lifetime.signal;
  async function enabled(signal, automatic = false) {
    cancelled(signal);
    if (disposed) fail('MEMORY_EXTRACTION_CLOSED');
    const current = await settings();
    if (!current?.enabled) fail('MEMORY_DISABLED');
    if (automatic && !current.automaticExtraction) fail('MEMORY_EXTRACTION_DISABLED');
    cancelled(signal);
  }
  function routeState(route) {
    const state = routes.get(route);
    if (!state) fail('MEMORY_EXTRACTION_ROUTE_UNKNOWN');
    return state;
  }
  async function resolveRoute({ purpose, signal } = {}) {
    signal = scopeSignal(signal); await enabled(signal);
    if (purpose !== PURPOSE) fail('MEMORY_EXTRACTION_PURPOSE');
    const selection = await getModel();
    if (!selection || typeof selection.provider !== 'string' || typeof selection.model !== 'string') fail('MEMORY_EXTRACTION_MODEL_UNAVAILABLE');
    const prepared = await ctx.llm.prepareCall({ provider: selection.provider, model: selection.model,
      ...(selection.reasoningEffort ? { reasoningEffort: selection.reasoningEffort } : {}), maxTokens: MAINTENANCE_POLICY.maxOutputTokens }, signal);
    if (prepared.inputModalities && !prepared.inputModalities.includes('text')) fail('MEMORY_EXTRACTION_MODEL_TEXT_UNSUPPORTED');
    const route = await privacy.routeForPrepared(prepared, PURPOSE, signal);
    if (prepared.config.maxTokens !== MAINTENANCE_POLICY.maxOutputTokens) fail('MEMORY_EXTRACTION_OUTPUT_BUDGET_UNSUPPORTED');
    routes.set(route, { prepared, sessionId: null, categories: [], sourceRefs: [], sources: new Map(), used: false });
    await enabled(signal); return route;
  }
  async function authorize({ route, categories, sourceRefs, signal } = {}) {
    signal = scopeSignal(signal); await enabled(signal);
    const state = routeState(route);
    state.sessionId ??= `memory-extraction:${hash([...sourceRefs].sort())}`;
    for (const [sessionId, expected] of state.sources) {
      const current = await privacy.getSourceSession(sessionId, { route, signal });
      if (current.categories.some(value => !expected.categories.includes(value))
        || current.sourceRefs.some(value => !expected.sourceRefs.includes(value))) fail('MEMORY_EXTRACTION_SOURCE_PRIVACY_CHANGED');
    }
    await privacy.beforeDisclosure({ sessionId: state.sessionId, categories, sourceRefs, route, signal });
    state.categories = [...new Set([...state.categories, ...categories])];
    state.sourceRefs = [...new Set([...state.sourceRefs, ...sourceRefs])];
    await enabled(signal);
  }
  async function readSource(source, { route, signal } = {}) {
    signal = scopeSignal(signal); await enabled(signal);
    const state = routeState(route), parsed = segment(source);
    if (!state.sessionId || source.categories.some(value => !state.categories.includes(value))
      || source.sourceRefs.some(value => !state.sourceRefs.includes(value))) fail('MEMORY_EXTRACTION_SOURCE_UNAUTHORIZED');
    const protection = await privacy.getSourceSession(parsed.sessionId, { route, signal });
    if (protection.categories.some(value => !source.categories.includes(value))
      || protection.sourceRefs.some(value => !source.sourceRefs.includes(value))) fail('MEMORY_EXTRACTION_SOURCE_PRIVACY_CHANGED');
    const handle = await ctx.sessionPersistence.open(parsed.sessionId, 'read', { signal });
    try {
      if (headerRevision(handle.header) !== parsed.header || parsed.from < handle.inheritedEventCount) fail('MEMORY_SOURCE_CHANGED');
      const { events } = await handle.read(parsed.from, parsed.to - parsed.from, { signal });
      if (events.length !== parsed.to - parsed.from || events.some((event, index) => event.seq !== parsed.from + index)) fail('MEMORY_SOURCE_CHANGED');
      const projected = [];
      let bytes = 0;
      for (const event of events) {
        const value = projectEvent(event); if (!value) continue;
        bytes += Buffer.byteLength(JSON.stringify(value));
        if (bytes > MAINTENANCE_POLICY.maxSourceBytes - 256) fail('MEMORY_EXTRACTION_INPUT_LIMIT');
        projected.push(value);
      }
      const latest = await privacy.getSourceSession(parsed.sessionId, { route, signal });
      if (latest.categories.some(value => !source.categories.includes(value))
        || latest.sourceRefs.some(value => !source.sourceRefs.includes(value))) fail('MEMORY_EXTRACTION_SOURCE_PRIVACY_CHANGED');
      state.sources.set(parsed.sessionId, { categories: source.categories, sourceRefs: source.sourceRefs });
      await enabled(signal);
      return { revision: source.revision, text: JSON.stringify(projected) };
    } finally { await handle.close(); }
  }
  async function countTokens({ route, input, signal } = {}) {
    signal = scopeSignal(signal); await enabled(signal); routeState(route);
    // DSH exposes an estimator, not an exact provider tokenizer. This explicit
    // reserve covers either live locale if it changes before dispatch.
    return Math.max(...['zh', 'en'].map(locale => {
      const request = requestInput(input, locale);
      const estimate = ctx.tokenMeter?.estimateMessage?.({ role: 'user', content: [{ type: 'text', text: request.system + '\n' + request.messages[0].content[0].text }] });
      return Math.max(Number.isSafeInteger(estimate) && estimate >= 0 ? estimate : 0,
        Buffer.byteLength(request.system) + Buffer.byteLength(request.messages[0].content[0].text) + 256);
    }));
  }
  async function generate({ route, input, maxOutputTokens, signal } = {}) {
    signal = AbortSignal.any([scopeSignal(signal), AbortSignal.timeout(60000)]); await enabled(signal);
    const state = routeState(route);
    if (!state.sessionId || state.used || maxOutputTokens !== MAINTENANCE_POLICY.maxOutputTokens) fail('MEMORY_EXTRACTION_CALL_INVALID');
    state.used = true;
    const request = { ...requestInput(input, resolveMobileLocale(ctx)), ...state.prepared.config, sessionId: state.sessionId, purpose: PURPOSE, signal };
    let text = '', finished = false, outputBytes = 0;
    for await (const chunk of state.prepared.stream(request)) {
      // The host aborts this signal on switch-off/revocation; do not poll the
      // Android settings bridge once per token. Recheck settings before commit.
      cancelled(signal);
      if (finished) fail('MEMORY_EXTRACTION_INVALID_STREAM');
      if (typeof chunk.text === 'string') outputBytes += Buffer.byteLength(chunk.text);
      if (outputBytes > MAX_OUTPUT_BYTES) fail('MEMORY_EXTRACTION_OUTPUT_LIMIT');
      if (chunk.type === 'text-delta') {
        text += chunk.text;
        if (Buffer.byteLength(text) > MAX_OUTPUT_BYTES) fail('MEMORY_EXTRACTION_OUTPUT_LIMIT');
      } else if (chunk.type === 'tool-call-delta' || chunk.type === 'block-start' && !['text', 'reasoning'].includes(chunk.blockType)) fail('MEMORY_EXTRACTION_UNEXPECTED_TOOL');
      else if (chunk.type === 'finish') {
        if (finished || chunk.reason?.kind !== 'stop') fail(chunk.reason?.kind === 'aborted' ? 'MEMORY_MAINTENANCE_CANCELLED' : 'MEMORY_EXTRACTION_MODEL_FAILED');
        finished = true;
      } else if (finished) fail('MEMORY_EXTRACTION_INVALID_STREAM');
    }
    if (!finished) fail('MEMORY_EXTRACTION_INCOMPLETE_STREAM');
    await enabled(signal);
    let result; try { result = JSON.parse(text); } catch { fail('MEMORY_INVALID_EXTRACTION_RESULT'); }
    if (!result || Array.isArray(result) || Object.keys(result).some(key => key !== 'candidates') || !Array.isArray(result.candidates)) fail('MEMORY_INVALID_EXTRACTION_RESULT');
    return result;
  }
  async function enqueueCompletedTurn(session, end, start, enqueueExtraction) {
    const signal = lifetime.signal; await enabled(signal, true);
    if (end.type !== 'turn/end' || end.data?.reason?.kind !== 'completed' || !start || start.turn !== end.data.turn
      || !Number.isSafeInteger(start.seq) || start.seq < 0 || start.seq >= end.seq || !session.isOwnSeq(start.seq)) fail('MEMORY_EXTRACTION_TURN_BOUNDARY');
    identity(session.id);
    const protection = await privacy.getSourceSession(session.id, { signal });
    if (await ctx.sessions.flush(session) !== true) fail('MEMORY_EXTRACTION_SOURCE_NOT_DURABLE');
    await enabled(signal, true);
    const header = headerRevision(session.header), receipts = [];
    for (let from = start.seq; from <= end.seq; from += MAX_SEGMENT_EVENTS) {
      const to = Math.min(from + MAX_SEGMENT_EVENTS, end.seq + 1), revision = `v1:${from}:${to}:${header}`;
      const sourceRefs = [...new Set([sessionRef(session.id), `dsh-segment:${hash([session.id, revision])}`, ...protection.sourceRefs])];
      const descriptor = { sourceId: sourceIdFor(session.id), revision, occurredAt: new Date(end.time).toISOString(),
        expiresAt: new Date(end.time + 48 * 3600000).toISOString(), actor: 'unknown',
        categories: [...new Set(['memory', ...protection.categories])], sourceRefs };
      await enabled(signal, true);
      const receipt = await enqueueExtraction(descriptor); receipts.push(receipt);
      if (receipt.status === 'blocked') { reportError(receipt.code); break; }
    }
    return receipts;
  }
  function subscribe(enqueueExtraction) {
    if (unsubscribe || typeof enqueueExtraction !== 'function') fail('MEMORY_EXTRACTION_SUBSCRIPTION');
    unsubscribe = ctx.on('session/event', (session, event) => {
      if (disposed) return;
      if (event.type === 'turn/start') {
        if (starts.size >= 128 && !starts.has(session.id)) { reportError('MEMORY_EXTRACTION_SESSION_LIMIT'); return; }
        starts.set(session.id, { seq: event.seq, turn: event.data.turn }); return;
      }
      if (event.type !== 'turn/end') return;
      const start = starts.get(session.id); starts.delete(session.id);
      if (event.data?.reason?.kind !== 'completed' || !start) return;
      if (pending.size >= 64) { reportError('MEMORY_EXTRACTION_ENQUEUE_LIMIT'); return; }
      const operation = enqueueCompletedTurn(session, event, start, enqueueExtraction)
        .catch(error => { if (!['MEMORY_DISABLED', 'MEMORY_EXTRACTION_DISABLED', 'MEMORY_EXTRACTION_CLOSED'].includes(error.code)) reportError(error.code ?? 'MEMORY_EXTRACTION_ENQUEUE_FAILED'); })
        .finally(() => pending.delete(operation));
      pending.add(operation);
    });
    return () => { unsubscribe?.(); unsubscribe = undefined; starts.clear(); };
  }
  async function dispose() {
    disposed = true; lifetime.abort(); unsubscribe?.(); unsubscribe = undefined; starts.clear();
    await Promise.allSettled([...pending]);
  }
  return { extraction: { resolveRoute, authorize, readSource, countTokens, generate, language: () => resolveMobileLocale(ctx) },
    subscribe, enqueueCompletedTurn, dispose };
}
