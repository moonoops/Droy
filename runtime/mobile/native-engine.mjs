import { TextDecoder } from 'node:util';
import { TYPERT as PRESETS } from '@deepseek-ai/dsh-agent-preset-registry/typert';
import { TYPERT as PERMISSIONS } from '@deepseek-ai/dsh-permission-presets/typert';
import { TYPERT as COMMANDS } from '@deepseek-ai/dsh-commands/typert';
import { TYPERT as SESSIONS } from '@deepseek-ai/dsh-api-session-controller/typert';
import { resolveMobileLocale } from './locale.mjs';

const base = '/droy/native/engine';
const copy = {
  invalid: ['请求格式无效。', 'The request is invalid.'],
  unavailable: ['此引擎能力尚未就绪。', 'This engine capability is not ready.'],
  missing: ['未找到目标会话或预设。', 'The session or preset was not found.'],
  managed: ['请从伙伴对话管理此会话。', 'Manage this session from its companion conversation.'],
  locked: ['此会话已开始，请在新会话中选择 Agent 预设。', 'This session has started. Choose an agent preset in a new session.'],
  conflict: ['设置已变化，请刷新后重新选择。', 'Settings changed. Refresh before choosing again.'],
  saved: ['选择已应用，保存结果未确认。请刷新状态。', 'The selection was applied, but saving is unconfirmed. Refresh its state.'],
  rejected: ['选择未获确认，请检查当前会话状态。', 'The selection was not confirmed. Check the current session state.'],
  cancelled: ['请求已取消，请刷新确认状态。', 'The request was cancelled. Refresh to confirm its state.'],
  failed: ['操作结果未确认，请刷新后核对。', 'The result is unconfirmed. Refresh and review it.'],
};
class Failure extends Error {
  constructor(code, status = 400, text = 'invalid') { super(code); Object.assign(this, { code, status, text }); }
}
const bad = () => new Failure('NATIVE_ENGINE_INVALID');
const unavailable = () => new Failure('NATIVE_ENGINE_UNAVAILABLE', 503, 'unavailable');
const object = value => value !== null && typeof value === 'object' && !Array.isArray(value);
const get = (ctx, name) => typeof ctx.get === 'function' ? ctx.get(name) : ctx[name];
function service(ctx, name, method) {
  const value = get(ctx, name);
  if (!value || method && typeof value[method] !== 'function') throw unavailable();
  return value;
}
function fields(value, allowed, optional = []) {
  if (!object(value) || Object.keys(value).some(key => !allowed.includes(key)) || allowed.some(key => !optional.includes(key) && !Object.hasOwn(value, key))) throw bad();
}
function id(value) {
  if (typeof value !== 'string' || !value || value !== value.trim() || value.length > 1024 || /[\x00-\x1f\x7f]/.test(value)) throw bad();
  return value;
}
function contract(table, namespace, method, args) {
  const descriptor = table.invocations.find(row => row.namespace === namespace && row.method === method);
  if (!descriptor) throw new Error('Locked engine SDK contract changed');
  try { descriptor.input({ strict: true }).create().parse(Object.fromEntries(descriptor.parameters.map((parameter, index) => [parameter.wire, args[index]]))); }
  catch (error) { if (error?.name === 'ZodError') throw bad(); throw error; }
}
const option = row => ({ value: row.value, name: row.name, ...(typeof row.description === 'string' ? { description: row.description } : {}) });
function settingsView(ctx, ns, selected) {
  const owner = get(ctx, 'settingsController');
  if (!owner) return null;
  const root = owner.describe(), row = root.namespaces.find(row => row.ns === ns);
  return row ? { revision: row.revision, selected, writable: root.writable === true } : null;
}
async function root(ctx) {
  const presets = get(ctx, 'agentPresets'), permissions = get(ctx, 'permissionPresets');
  const roster = presets ? await presets.list() : [];
  const catalog = permissions?.catalog();
  if (catalog) contract(PERMISSIONS, 'permissionPresets', 'catalog', []);
  return {
    presets: presets ? { available: true, defaultId: presets.defaultId, setting: settingsView(ctx, 'agent-preset-registry', presets.defaultId),
      options: roster.map(row => ({ id: row.id, ...(typeof row.name === 'string' ? { name: row.name } : {}), ...(typeof row.description === 'string' ? { description: row.description } : {}), broken: typeof row.broken === 'string', isDefault: row.id === presets.defaultId })) } : { available: false },
    permissions: catalog ? { available: true, defaultId: permissions.defaultPreset, setting: settingsView(ctx, 'permission', permissions.defaultPreset),
      options: catalog.options.map(option), defaultOptions: catalog.defaultOptions.map(option) } : { available: false },
  };
}
function ordinary(ctx, sessionId) {
  id(sessionId);
  if (service(ctx, 'droyCompanions', 'ownsSession').ownsSession(sessionId)) throw new Failure('NATIVE_ENGINE_MANAGED', 409, 'managed');
}
async function session(ctx, sessionId, signal) {
  ordinary(ctx, sessionId);
  contract(SESSIONS, 'session', 'projections', [{ sessionId }]);
  const projection = await service(ctx, 'sessionController', 'projections').projections({ sessionId }, signal);
  if (projection === null) throw new Failure('NATIVE_ENGINE_SESSION_MISSING', 404, 'missing');
  if (projection.kind !== 'sequenced') return { sessionId, fresh: false, needsMigration: true };
  const values = projection.values, boundary = values.droyTurnBoundary;
  // Native API installation registers the replayable boundary; missing data does not prove a blank session.
  const presetSelectable = object(boundary) && Number.isInteger(boundary.lastTurn) && boundary.lastTurn >= 0
      && (boundary.openTurnStartSeq === null || Number.isInteger(boundary.openTurnStartSeq) && boundary.openTurnStartSeq >= 0)
      ? boundary.lastTurn === 0 && boundary.openTurnStartSeq === null : undefined;
  return { sessionId, fresh: true, asOfSeq: projection.asOfSeq, context: contextView(values),
    goal: values.goal?.goal ? { ...values.goal.goal, roundsStarted: values.goal.roundsStarted,
      activation: (() => { const agent = get(ctx, 'agents')?.get(sessionId); return agent ? get(ctx, 'goals')?.get(agent)?.activation ?? 'disarmed' : 'disarmed'; })() } : null,
    ...(typeof values.agentPreset === 'string' ? { agentPreset: values.agentPreset } : {}),
    ...(presetSelectable !== undefined ? { presetSelectable } : {}),
    ...(typeof values.permissions?.currentValue === 'string' ? { permission: values.permissions.currentValue, permissionSelectable: !!get(ctx, 'commands') } : {}),
  };
}
function contextView(values) {
  const pressure = values.contextPressure, breakdown = values.contextBreakdown;
  if (!object(pressure) && !object(breakdown)) return null;
  function numbers(source, names) {
    return Object.fromEntries(names.filter(name => source?.[name] !== undefined).map(name => {
      if (typeof source[name] !== 'number' || !Number.isFinite(source[name]) || source[name] < 0) throw new Failure('NATIVE_ENGINE_CONTEXT_INVALID', 500, 'failed');
      return [name, source[name]];
    }));
  }
  return { ...numbers(pressure, ['pressureTokens', 'projectedTokens', 'contextWindow']),
    ...(object(breakdown) ? { breakdown: numbers(breakdown, ['systemTokens', 'toolsTokens', 'messageTokens']) } : {}) };
}
async function skills(ctx, sessionId, signal) {
  ordinary(ctx, sessionId); contract(SESSIONS, 'skills', 'list', [{ sessionId }]);
  const value = await service(ctx, 'sessionSkillCatalog', 'list').list({ sessionId }, signal);
  return { sessionId, skills: value.skills.map(row => ({ name: row.name, description: row.description,
    modelInvocable: row.modelInvocable, ...(typeof row.path === 'string' ? { path: row.path } : {}),
    ...(typeof row.whenToUse === 'string' ? { whenToUse: row.whenToUse } : {}) })) };
}
async function defaultPreset(ctx, input) {
  fields(input, ['id', 'expectedRevision']); id(input.id);
  if (!Number.isSafeInteger(input.expectedRevision) || input.expectedRevision < 0) throw bad();
  const current = await root(ctx), section = current.presets;
  if (!section.available || !section.setting?.writable) throw unavailable();
  const selected = section.options.find(row => row.id === input.id);
  if (!selected || selected.broken) throw bad();
  await service(ctx, 'settingsController', 'mutate').mutate('agent-preset-registry',
    [{ op: 'set', path: ['selectedDefault'], value: input.id }], input.expectedRevision);
  const result = await root(ctx);
  if (result.presets.defaultId !== input.id) throw new Failure('NATIVE_ENGINE_SAVE_UNCONFIRMED', 409, 'saved');
  return result;
}
async function permissionDefault(ctx, input) {
  fields(input, ['id', 'expectedRevision', 'acceptRisk'], ['acceptRisk']);
  if (Object.hasOwn(input, 'acceptRisk') && typeof input.acceptRisk !== 'boolean') throw bad();
  if (input.id === 'danger-full-access' && input.acceptRisk !== true) throw bad();
  const current = await root(ctx), section = current.permissions;
  id(input.id);
  if (!Number.isSafeInteger(input.expectedRevision) || input.expectedRevision < 0) throw bad();
  if (!section.available || !section.setting?.writable) throw unavailable();
  if (!section.defaultOptions.some(row => row.value === input.id) || ['auto', 'custom'].includes(input.id)) throw bad();
  await service(ctx, 'settingsController', 'mutate').mutate('permission', [{ op: 'set', path: ['defaultPreset'], value: input.id }], input.expectedRevision);
  const result = await root(ctx);
  if (result.permissions.defaultId !== input.id) throw new Failure('NATIVE_ENGINE_SAVE_UNCONFIRMED', 409, 'saved');
  return result;
}
async function liveAgent(ctx, sessionId, signal) {
  ordinary(ctx, sessionId); signal?.throwIfAborted();
  const result = await service(ctx, 'sessionController', 'resolveAgent').resolveAgent(sessionId);
  if (result.error) throw result.error;
  if (!result.agent || result.agent.id !== sessionId) throw unavailable();
  signal?.throwIfAborted();
  return result.agent;
}
async function flush(ctx, agent) {
  if (await service(ctx, 'sessions', 'flush').flush(agent.session) !== true) throw new Failure('NATIVE_ENGINE_SAVE_UNCONFIRMED', 409, 'saved');
}
async function select(ctx, input, signal, permission) {
  fields(input, ['sessionId', 'id', 'acceptRisk'], ['acceptRisk']); id(input.id); id(input.sessionId);
  if (Object.hasOwn(input, 'acceptRisk') && typeof input.acceptRisk !== 'boolean') throw bad();
  ordinary(ctx, input.sessionId);
  const catalog = await root(ctx), section = permission ? catalog.permissions : catalog.presets;
  const choice = section.options?.find(row => (permission ? row.value : row.id) === input.id);
  if (!section.available || !choice || choice.broken) throw bad();
  if (permission && ['danger-full-access', 'auto'].includes(input.id) && input.acceptRisk !== true) throw bad();
  service(ctx, 'sessions', 'flush');
  const agent = await liveAgent(ctx, input.sessionId, signal);
  if (permission) {
    const commands = service(ctx, 'commands', 'execute'), line = `/permission ${input.id}`;
    contract(COMMANDS, 'commands', 'execute', [input.sessionId, line, []]);
    const execution = await commands.execute(agent, line, [], signal);
    if (execution?.result?.kind !== 'success') throw new Failure('NATIVE_ENGINE_SELECTION_REJECTED', 422, 'rejected');
  } else {
    contract(PRESETS, 'agentPresets', 'select', [input.sessionId, input.id]);
    if (await service(ctx, 'agentPresets', 'select').select(agent, input.id) !== input.id) throw new Failure('NATIVE_ENGINE_SELECTION_REJECTED', 422, 'rejected');
  }
  await flush(ctx, agent);
  const current = await session(ctx, input.sessionId, signal);
  if ((permission ? current.permission : current.agentPreset) !== input.id) throw new Failure('NATIVE_ENGINE_SAVE_UNCONFIRMED', 409, 'saved');
  return current;
}
async function changeGoal(ctx, input, signal) {
  fields(input, ['sessionId', 'action', 'objective', 'expectedId', 'expectedRevision'], ['objective', 'expectedId', 'expectedRevision']);
  id(input.sessionId);
  if (!['create', 'pause', 'resume', 'clear'].includes(input.action)) throw bad();
  if (input.action === 'create') {
    if (typeof input.objective !== 'string' || !input.objective.trim() || input.objective.length > 16000) throw bad();
  } else if (input.objective !== undefined) throw bad();
  if ((input.expectedId === undefined) !== (input.expectedRevision === undefined)) throw bad();
  if (input.expectedId !== undefined && (!id(input.expectedId) || !Number.isSafeInteger(input.expectedRevision) || input.expectedRevision < 0)) throw bad();
  const agent = await liveAgent(ctx, input.sessionId, signal);
  const goals = service(ctx, 'goals', input.action), current = goals.get(agent);
  if ((current?.id ?? undefined) !== input.expectedId || (current?.revision ?? undefined) !== input.expectedRevision)
    throw new Failure('NATIVE_ENGINE_GOAL_CHANGED', 409, 'conflict');
  if (input.action !== 'create' && !current) throw bad();
  signal?.throwIfAborted();
  if (input.action === 'create') goals.create(agent, { objective: input.objective.trim() });
  else goals[input.action](agent, { id: current.id, revision: current.revision });
  await flush(ctx, agent);
  return session(ctx, input.sessionId, signal);
}
async function body(req) {
  if (!/^application\/json(?:\s*;|\s*$)/i.test(req.headers['content-type'] ?? '')) throw bad();
  const chunks = []; let size = 0;
  for await (const chunk of req) { size += chunk.length; if (size > 32768) throw bad(); chunks.push(chunk); }
  try { return JSON.parse(new TextDecoder('utf-8', { fatal: true }).decode(Buffer.concat(chunks))); } catch { throw bad(); }
}
function reply(res, status, value) {
  if (res.destroyed || res.writableEnded) return;
  res.writeHead(status, { 'Content-Type': 'application/json; charset=utf-8', 'Cache-Control': 'no-store' }); res.end(JSON.stringify(value));
}
/** Central native-api authenticates requests. This adapter never grants Android product or system permissions. */
export function createNativeEngine(ctx) {
  const compactions = new Map(), pending = new Set(); let closed = false;
  function compactSnapshot(sessionId, requestId) {
    const row = compactions.get(requestId);
    if (row && row.sessionId !== sessionId) throw bad();
    return { sessionId, requestId, state: row?.state ?? 'unknown', ...(row?.result ?? {}) };
  }
  function compact(input, signal) {
    fields(input, ['sessionId', 'requestId']); id(input.sessionId); id(input.requestId); ordinary(ctx, input.sessionId);
    signal?.throwIfAborted();
    if (compactions.has(input.requestId)) return compactSnapshot(input.sessionId, input.requestId);
    if ([...compactions.values()].some(row => row.sessionId === input.sessionId && ['running', 'cancelling'].includes(row.state))) throw new Failure('NATIVE_ENGINE_BUSY', 409, 'rejected');
    const commands = service(ctx, 'commands', 'execute'); service(ctx, 'commands', 'find'); service(ctx, 'sessions', 'flush');
    const row = { sessionId: input.sessionId, state: 'running', abort: new AbortController(), dispatched: false };
    compactions.set(input.requestId, row);
    row.pending = Promise.resolve().then(async () => {
      const agent = await liveAgent(ctx, input.sessionId, row.abort.signal);
      if (commands.find(agent, 'compact')?.definitionId !== '@deepseek-ai/dsh-command-compact') throw unavailable();
      row.abort.signal.throwIfAborted();
      contract(COMMANDS, 'commands', 'execute', [input.sessionId, '/compact', []]);
      row.dispatched = true;
      const execution = await commands.execute(agent, '/compact', [], row.abort.signal);
      if (!execution) throw unavailable();
      await flush(ctx, agent);
      const success = execution.result.kind === 'success';
      row.result = { outcome: success ? execution.result.text === 'No compactable history yet.' ? 'empty' : 'success'
        : row.abort.signal.aborted && execution.result.text === 'Compaction cancelled.' ? 'cancelled' : 'failed',
        commandId: execution.commandId, ...(execution.result.sourceEventSeq === undefined ? {} : { sourceEventSeq: execution.result.sourceEventSeq }) };
      row.state = 'settled';
    }).catch(error => {
      row.state = 'settled';
      row.result = { outcome: row.abort.signal.aborted && !row.dispatched ? 'cancelled' : error instanceof Failure && error.code === 'NATIVE_ENGINE_UNAVAILABLE' && !row.dispatched ? 'unavailable' : 'unconfirmed' };
    });
    const operation = row.pending;
    pending.add(operation);
    operation.then(() => pending.delete(operation), () => pending.delete(operation));
    return compactSnapshot(input.sessionId, input.requestId);
  }
  const routes = new Map([
    ['', ['GET', () => root(ctx)]],
    ['/session', ['POST', (input, signal) => { fields(input, ['sessionId']); return session(ctx, input.sessionId, signal); }]],
    ['/presets/read', ['POST', async input => {
      fields(input, ['id']); id(input.id); contract(PRESETS, 'agentPresets', 'read', [input.id]);
      const value = await service(ctx, 'agentPresets', 'readDocument').readDocument(input.id);
      if (typeof value.content !== 'string' || value.content.length > 524288) throw new Failure('NATIVE_ENGINE_DOCUMENT_UNAVAILABLE', 422, 'unavailable');
      return { id: value.agentPreset, content: value.content };
    }]],
    ['/presets/default', ['POST', input => defaultPreset(ctx, input)]],
    ['/presets/select', ['POST', (input, signal) => select(ctx, input, signal, false)]],
    ['/permissions/default', ['POST', input => permissionDefault(ctx, input)]],
    ['/permissions/select', ['POST', (input, signal) => select(ctx, input, signal, true)]],
    ['/skills', ['POST', (input, signal) => { fields(input, ['sessionId']); return skills(ctx, input.sessionId, signal); }]],
    ['/goal', ['POST', (input, signal) => changeGoal(ctx, input, signal)]],
    ['/compact', ['POST', (input, signal) => compact(input, signal)]],
    ['/compact/status', ['POST', input => { fields(input, ['sessionId', 'requestId']); ordinary(ctx, input.sessionId); return compactSnapshot(input.sessionId, id(input.requestId)); }]],
    ['/compact/cancel', ['POST', input => {
      fields(input, ['sessionId', 'requestId']); ordinary(ctx, input.sessionId); id(input.requestId);
      const snapshot = compactSnapshot(input.sessionId, input.requestId), row = compactions.get(input.requestId);
      if (row && row.state === 'running') { row.state = 'cancelling'; row.abort.abort(); }
      return row ? compactSnapshot(input.sessionId, input.requestId) : snapshot;
    }]],
  ]);
  return { pendingWork: () => Object.freeze([...pending]),
    dispose() { closed = true; for (const row of compactions.values()) row.abort.abort(); }, async handle(req, res, signal) {
    try {
      if (closed) throw unavailable();
      const url = new URL(req.url, `http://${req.headers.host}`);
      if (url.search || !url.pathname.startsWith(base)) throw bad();
      const route = routes.get(url.pathname.slice(base.length));
      if (!route) throw new Failure('NATIVE_ENGINE_NOT_FOUND', 404, 'missing');
      if (req.method !== route[0]) throw new Failure('NATIVE_ENGINE_METHOD', 405);
      const input = req.method === 'POST' ? await body(req) : undefined;
      signal?.throwIfAborted();
      reply(res, 200, { ok: true, result: await route[1](input, signal) });
    } catch (error) {
      const known = { 'settings/conflict': [409, 'conflict'], 'agent-preset/locked': [409, 'locked'], 'agent-preset/not-found': [404, 'missing'], 'session/not-found': [404, 'missing'] };
      const own = error instanceof Failure, entry = known[error?.code];
      const cancelled = signal?.aborted || error?.name === 'AbortError';
      const status = own ? error.status : cancelled ? 409 : entry?.[0] ?? 500;
      const key = own ? error.text : cancelled ? 'cancelled' : entry?.[1] ?? 'failed';
      reply(res, status, { ok: false, error: { code: own ? error.code : cancelled ? 'NATIVE_ENGINE_CANCELLED' : entry ? error.code : 'NATIVE_ENGINE_FAILED', message: copy[key][resolveMobileLocale(ctx) === 'zh' ? 0 : 1] } });
    }
  } };
}
