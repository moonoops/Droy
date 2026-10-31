import test from 'node:test';
import assert from 'node:assert/strict';
import { createServer } from 'node:http';
import { once } from 'node:events';
import { createRequire, registerHooks } from 'node:module';
import { pathToFileURL } from 'node:url';
const installed = createRequire(new URL('../../dsh/package.json', import.meta.url));
const hook = registerHooks({ resolve(specifier, context, next) {
  if (specifier.startsWith('@deepseek-ai/')) return { url: pathToFileURL(installed.resolve(specifier)).href, shortCircuit: true };
  return next(specifier, context);
} });
const { createNativeEngine } = await import('../native-engine.mjs');
const { TYPERT: PRESETS } = await import('@deepseek-ai/dsh-agent-preset-registry/typert');
const { TYPERT: PERMISSIONS } = await import('@deepseek-ai/dsh-permission-presets/typert');
const { TYPERT: COMMANDS } = await import('@deepseek-ai/dsh-commands/typert');
const { TYPERT: SESSIONS } = await import('@deepseek-ai/dsh-api-session-controller/typert');
hook.deregister();
const privateError = 'fixture-private-provider-detail';
function check(table, namespace, method, args, result) {
  const descriptor = table.invocations.find(row => row.namespace === namespace && row.method === method);
  assert.ok(descriptor, `${namespace}/${method}`);
  descriptor.input({ strict: true }).create().parse(Object.fromEntries(descriptor.parameters.map((p, i) => [p.wire, args[i]])));
  if (result !== undefined) descriptor.result.create().parse(result);
}
async function fixture(t, locale = 'en') {
  const calls = [];
  let presetDefault = 'standard', permissionDefault = 'workspace-write';
  const revisions = { 'agent-preset-registry': 5, permission: 6 };
  const values = { agentPreset: 'standard', permissions: { currentValue: 'workspace-write' }, droyTurnBoundary: { lastTurn: 0, openTurnStartSeq: null } };
  const agent = { id: 'work-a', session: { id: 'work-a' } };
  const options = ['workspace-write', 'danger-full-access', 'auto'].map(value => ({ value, name: value }));
  const ctx = {
    settings: { describe: () => [{ ns: 'locale', value: { preference: locale } }] },
    settingsController: {
      describe: () => ({ writable: true, namespaces: [
        { ns: 'agent-preset-registry', revision: revisions['agent-preset-registry'], value: { selectedDefault: presetDefault } },
        { ns: 'permission', revision: revisions.permission, value: { defaultPreset: permissionDefault } },
      ] }),
      mutate: async (ns, ops, revision) => {
        calls.push(['settings', ns, ops, revision]);
        if (revision !== revisions[ns]) throw Object.assign(new Error(privateError), { code: 'settings/conflict' });
        if (ns === 'permission') permissionDefault = ops[0].value; else presetDefault = ops[0].value;
        revisions[ns]++; return ctx.settingsController.describe().namespaces.find(row => row.ns === ns);
      },
    },
    agentPresets: {
      get defaultId() { return presetDefault; },
      list: async () => [{ id: 'standard' }, { id: 'cordis' }, { id: 'fixture-broken', broken: privateError }],
      readDocument: async id => { calls.push(['document', id]); const result = { agentPreset: id, content: '- name: fixture-plugin\n' }; check(PRESETS, 'agentPresets', 'read', [id], result); return result; },
      select: async (target, id) => {
        check(PRESETS, 'agentPresets', 'select', [target.id, id], id); calls.push(['preset', target.id, id]);
        if (values.droyTurnBoundary !== undefined && (values.droyTurnBoundary.openTurnStartSeq !== null || values.droyTurnBoundary.lastTurn > 0))
          throw Object.assign(new Error(privateError), { code: 'agent-preset/locked' });
        values.agentPreset = id; return id;
      },
    },
    permissionPresets: { get defaultPreset() { return permissionDefault; }, catalog: () => {
      const value = { options, defaultOptions: options.filter(row => row.value !== 'auto'), defaultPreset: permissionDefault };
      check(PERMISSIONS, 'permissionPresets', 'catalog', [], value); return value;
    } },
    droyCompanions: { ownsSession: id => id === 'companion-a' },
    sessionController: {
      projections: async ({ sessionId }) => { calls.push(['projection', sessionId]); return { kind: 'sequenced', asOfSeq: 9, values: structuredClone(values) }; },
      resolveAgent: async id => { calls.push(['resolve', id]); return { agent: { ...agent, id, session: { id } } }; },
    },
    sessions: { flush: async target => { calls.push(['flush', target.id]); return true; } },
    sessionSkillCatalog: { list: async (request, signal) => {
      assert.ok(signal); calls.push(['skills', request.sessionId]);
      const result = { skills: [{ name: 'fixture-notes', description: 'Fictional skill', modelInvocable: false, path: '/fixture/skills/fixture-notes/SKILL.md' }] };
      check(SESSIONS, 'skills', 'list', [request], result); return result;
    } },
    commands: { find: () => ({ definitionId: '@deepseek-ai/dsh-command-compact' }), execute: async (target, line, attachments, signal) => {
      check(COMMANDS, 'commands', 'execute', [target.id, line, attachments]); assert.ok(signal);
      calls.push(['command', target.id, line]); values.permissions.currentValue = line.slice('/permission '.length);
      return { commandId: 'fixture-command', result: { kind: 'success', text: 'fixture' } };
    } },
  };
  const adapter = createNativeEngine(ctx), abort = new AbortController();
  const server = createServer((req, res) => adapter.handle(req, res, abort.signal));
  server.listen(0, '127.0.0.1'); await once(server, 'listening'); t.after(() => { server.closeAllConnections(); server.close(); });
  const origin = `http://127.0.0.1:${server.address().port}`;
  return { ctx, calls, values, abort, adapter, async request(path = '', body) {
    const result = await fetch(origin + '/droy/native/engine' + path, body === undefined ? {} : { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) });
    return { status: result.status, value: await result.json() };
  } };
}
test('roster and catalog use actual defaults, keep failed rows visible and redact activation diagnostics', async t => {
  const f = await fixture(t), response = await f.request();
  assert.equal(response.value.result.presets.defaultId, 'standard');
  assert.equal(response.value.result.presets.setting.revision, 5);
  assert.equal(response.value.result.presets.options[2].broken, true);
  assert.ok(!JSON.stringify(response).includes(privateError));
  assert.deepEqual(response.value.result.permissions.defaultOptions.map(row => row.value), ['workspace-write', 'danger-full-access']);
  assert.equal(f.calls.length, 0);
});
test('preset details call only the public read contract; a create API is not invented', async t => {
  const f = await fixture(t), response = await f.request('/presets/read', { id: 'standard' });
  assert.equal(response.value.result.content, '- name: fixture-plugin\n');
  assert.deepEqual(f.calls, [['document', 'standard']]);
  assert.equal((await f.request('/presets/create', { id: 'new' })).status, 404);
  assert.equal(PRESETS.invocations.some(row => row.namespace === 'agentPresets' && row.method === 'create'), false);
});
test('global preset changes only selectedDefault with the original revision and confirms the new effective default', async t => {
  const f = await fixture(t), response = await f.request('/presets/default', { id: 'cordis', expectedRevision: 5 });
  assert.equal(response.value.result.presets.defaultId, 'cordis');
  assert.deepEqual(f.calls, [['settings', 'agent-preset-registry', [{ op: 'set', path: ['selectedDefault'], value: 'cordis' }], 5]]);
  assert.equal((await f.request('/presets/default', { id: 'fixture-broken', expectedRevision: 6 })).status, 400);
  const stale = await f.request('/presets/default', { id: 'standard', expectedRevision: 5 });
  assert.equal(stale.status, 409); assert.equal(stale.value.error.code, 'settings/conflict');
  assert.ok(!JSON.stringify(stale).includes(privateError));
});
test('cold session reads do not activate agents and missing product boundary does not imply blankness', async t => {
  const f = await fixture(t); delete f.values.droyTurnBoundary; delete f.values.permissions;
  const response = await f.request('/session', { sessionId: 'work-a' });
  assert.equal(response.value.result.fresh, true);
  assert.equal(Object.hasOwn(response.value.result, 'presetSelectable'), false);
  assert.equal(Object.hasOwn(response.value.result, 'permission'), false);
  assert.deepEqual(f.calls, [['projection', 'work-a']]);
  f.ctx.sessionController.projections = async () => ({ kind: 'migration-required', values: { agentPreset: 'standard' } });
  const migration = (await f.request('/session', { sessionId: 'work-a' })).value.result;
  assert.equal(migration.needsMigration, true); assert.equal(migration.fresh, false); assert.equal(Object.hasOwn(migration, 'agentPreset'), false);
});
test('missing or malformed product boundary never claims blankness', async t => {
  const f = await fixture(t); delete f.values.droyTurnBoundary;
  const selected = await f.request('/presets/select', { sessionId: 'work-a', id: 'cordis' });
  assert.equal(selected.status, 200); assert.equal(selected.value.result.agentPreset, 'cordis');
  assert.equal(Object.hasOwn(selected.value.result, 'presetSelectable'), false);
  assert.equal(f.calls.filter(row => row[0] === 'preset').length, 1);
  assert.equal(f.calls.filter(row => row[0] === 'command' || row[0] === 'settings').length, 0);
  for (const boundary of [null, {}, { lastTurn: -1, openTurnStartSeq: null }, { lastTurn: 0, openTurnStartSeq: 'invalid' }]) {
    f.values.droyTurnBoundary = boundary;
    const current = await f.request('/session', { sessionId: 'work-a' });
    assert.equal(Object.hasOwn(current.value.result, 'presetSelectable'), false);
  }
});
test('a resolver returning another Agent cannot select a preset or permission or flush either session', async t => {
  const f = await fixture(t);
  f.ctx.sessionController.resolveAgent = async id => { f.calls.push(['resolve', id]); return { agent: { id: 'different-agent', session: { id: 'different-agent' } } }; };
  for (const [path, id] of [['/presets/select', 'cordis'], ['/permissions/select', 'workspace-write']]) {
    const response = await f.request(path, { sessionId: 'work-a', id });
    assert.equal(response.status, 503); assert.equal(response.value.error.code, 'NATIVE_ENGINE_UNAVAILABLE');
  }
  assert.deepEqual(f.calls, [['resolve', 'work-a'], ['resolve', 'work-a']]);
});
test('session Agent selection uses its resolved owner and durable flush, never the global default', async t => {
  const f = await fixture(t), response = await f.request('/presets/select', { sessionId: 'work-a', id: 'cordis' });
  assert.equal(response.value.result.agentPreset, 'cordis');
  assert.deepEqual(f.calls, [['resolve', 'work-a'], ['preset', 'work-a', 'cordis'], ['flush', 'work-a'], ['projection', 'work-a']]);
  assert.equal((await f.request()).value.result.presets.defaultId, 'standard');
});
test('a started session remains locked and companion-owned sessions cannot be rebound through engine routes', async t => {
  const f = await fixture(t); f.values.droyTurnBoundary.lastTurn = 1;
  const locked = await f.request('/presets/select', { sessionId: 'work-a', id: 'cordis' });
  assert.equal(locked.status, 409); assert.equal(locked.value.error.code, 'agent-preset/locked');
  f.calls.length = 0;
  for (const path of ['/presets/select', '/permissions/select']) assert.equal((await f.request(path, { sessionId: 'companion-a', id: 'workspace-write' })).status, 409);
  assert.equal((await f.request('/session', { sessionId: 'companion-a' })).status, 409);
  assert.equal(f.calls.length, 0);
});
test('DSH permission default options and risk acknowledgement do not grant Android capabilities', async t => {
  const f = await fixture(t);
  assert.equal((await f.request('/permissions/default', { id: 'auto', expectedRevision: 6, acceptRisk: true })).status, 400);
  assert.equal((await f.request('/permissions/default', { id: 'danger-full-access', expectedRevision: 6 })).status, 400);
  assert.equal(f.calls.length, 0);
  const response = await f.request('/permissions/default', { id: 'danger-full-access', expectedRevision: 6, acceptRisk: true });
  assert.equal(response.value.result.permissions.defaultId, 'danger-full-access');
  assert.deepEqual(f.calls, [['settings', 'permission', [{ op: 'set', path: ['defaultPreset'], value: 'danger-full-access' }], 6]]);
});
test('current-session permission calls the canonical /permission command with cancellation, flushes and reads back', async t => {
  const f = await fixture(t);
  assert.equal((await f.request('/permissions/select', { sessionId: 'work-a', id: 'auto' })).status, 400);
  const response = await f.request('/permissions/select', { sessionId: 'work-a', id: 'auto', acceptRisk: true });
  assert.equal(response.value.result.permission, 'auto');
  assert.deepEqual(f.calls, [['resolve', 'work-a'], ['command', 'work-a', '/permission auto'], ['flush', 'work-a'], ['projection', 'work-a']]);
  assert.equal((await f.request()).value.result.permissions.defaultId, 'workspace-write');
});
test('failed command or persistence acknowledgement is never reported as saved and is not automatically replayed', async t => {
  const f = await fixture(t);
  f.ctx.sessions.flush = async () => false;
  const response = await f.request('/permissions/select', { sessionId: 'work-a', id: 'danger-full-access', acceptRisk: true });
  assert.equal(response.value.error.code, 'NATIVE_ENGINE_SAVE_UNCONFIRMED');
  assert.equal(f.calls.filter(row => row[0] === 'command').length, 1);
  assert.equal(f.values.permissions.currentValue, 'danger-full-access');
  f.ctx.commands.execute = async () => ({ commandId: 'fixture', result: { kind: 'error', text: privateError } });
  const rejected = await f.request('/permissions/select', { sessionId: 'work-a', id: 'workspace-write' });
  assert.equal(rejected.value.error.code, 'NATIVE_ENGINE_SELECTION_REJECTED'); assert.ok(!JSON.stringify(rejected).includes(privateError));
});
test('cancelled requests cannot resume or mutate a session and safe errors follow the shared locale', async t => {
  for (const locale of ['zh', 'en']) {
    const f = await fixture(t, locale); f.abort.abort();
    const response = await f.request('/permissions/select', { sessionId: 'work-a', id: 'workspace-write' });
    assert.equal(response.value.error.code, 'NATIVE_ENGINE_CANCELLED'); assert.equal(/[\u4e00-\u9fff]/.test(response.value.error.message), locale === 'zh');
    assert.equal(f.calls.length, 0);
  }
});

test('slash skills use the Session-addressed cold catalog without loading bodies or starting an Agent', async t => {
  const f = await fixture(t), response = await f.request('/skills', { sessionId: 'work-a' });
  assert.equal(response.value.result.sessionId, 'work-a');
  assert.equal(response.value.result.skills[0].modelInvocable, false);
  assert.equal(response.value.result.skills[0].path, '/fixture/skills/fixture-notes/SKILL.md');
  assert.deepEqual(f.calls, [['skills', 'work-a']]);
  assert.equal((await f.request('/skills', { sessionId: 'companion-a' })).status, 409);
  assert.equal((await f.request('/skills', { sessionId: 'work-a', path: '/untrusted/file' })).status, 400);
});
test('context projections retain provider pressure, projected estimate and independent heuristic breakdown without filling unknown values', async t => {
  const f = await fixture(t);
  f.values.contextPressure = { projectedTokens: 1500, contextWindow: 32000 };
  f.values.contextBreakdown = { systemTokens: 100, toolsTokens: 250, messageTokens: 900 };
  const context = (await f.request('/session', { sessionId: 'work-a' })).value.result.context;
  assert.equal(context.projectedTokens, 1500); assert.equal(context.contextWindow, 32000);
  assert.equal(Object.hasOwn(context, 'pressureTokens'), false);
  assert.deepEqual(context.breakdown, f.values.contextBreakdown);
  assert.notEqual(context.projectedTokens, Object.values(context.breakdown).reduce((a, b) => a + b));
  f.values.contextPressure = { pressureTokens: 0 }; delete f.values.contextBreakdown;
  const zero = (await f.request('/session', { sessionId: 'work-a' })).value.result.context;
  assert.deepEqual(zero, { pressureTokens: 0 });
  delete f.values.contextPressure;
  assert.equal((await f.request('/session', { sessionId: 'work-a' })).value.result.context, null);
});
test('manual compact dispatches one real fixed command per Session/request identity and never prompts a model through session.prompt', async t => {
  const f = await fixture(t); let finish;
  f.ctx.commands.execute = async (agent, line, attachments, signal) => {
    assert.equal(line, '/compact'); assert.deepEqual(attachments, []); assert.ok(signal);
    calls.push([agent.id, line]);
    return new Promise(resolve => { finish = resolve; });
  };
  const calls = [], input = { sessionId: 'work-a', requestId: 'compact-one' };
  assert.equal((await f.request('/compact', input)).value.result.state, 'running');
  await f.request('/compact', input); assert.equal(calls.length, 1);
  assert.equal((await f.request('/compact', { sessionId: 'work-a', requestId: 'compact-other' })).status, 409);
  finish({ commandId: 'fixture-compact', result: { kind: 'success', sourceEventSeq: 11, text: 'Compacted 3 history items (~200 tokens).' } });
  await new Promise(resolve => setImmediate(resolve));
  const result = (await f.request('/compact/status', input)).value.result;
  assert.equal(result.state, 'settled'); assert.equal(result.outcome, 'success'); assert.equal(result.sourceEventSeq, 11);
  assert.equal((await f.request('/compact', input)).value.result.commandId, 'fixture-compact'); assert.equal(calls.length, 1);
  assert.equal((await f.request('/compact/status', { ...input, sessionId: 'work-b' })).status, 400);
});
test('compact cancellation stays cancelling until its owned AbortSignal receives an actual command completion', async t => {
  const f = await fixture(t); let finish, aborted = false;
  f.ctx.commands.execute = async (_agent, _line, _attachments, signal) => {
    signal.addEventListener('abort', () => { aborted = true; }, { once: true });
    return new Promise(resolve => { finish = resolve; });
  };
  const input = { sessionId: 'work-a', requestId: 'compact-cancel' };
  await f.request('/compact', input);
  const pending = f.adapter.pendingWork()[0]; assert.ok(pending instanceof Promise);
  const cancellation = (await f.request('/compact/cancel', input)).value.result;
  assert.equal(aborted, true); assert.equal(cancellation.state, 'cancelling'); assert.equal(Object.hasOwn(cancellation, 'outcome'), false);
  assert.equal((await f.request('/compact/status', input)).value.result.state, 'cancelling');
  assert.equal(f.adapter.pendingWork()[0], pending);
  finish({ commandId: 'fixture-compact', result: { kind: 'error', text: 'Compaction cancelled.' } });
  await pending; assert.deepEqual(f.adapter.pendingWork(), []);
  assert.equal((await f.request('/compact/status', input)).value.result.outcome, 'cancelled');
});

test('compact pendingWork keeps one real Promise through command completion and final persistence without replay', async t => {
  const f = await fixture(t), command = Promise.withResolvers(), saving = Promise.withResolvers(), persisted = Promise.withResolvers();
  const input = { sessionId: 'work-a', requestId: 'observed-compact' }; let calls = 0, signal;
  f.ctx.commands.execute = async (_agent, _line, _attachments, value) => { calls++; signal = value; return command.promise; };
  f.ctx.sessions.flush = async () => { saving.resolve(); await persisted.promise; return true; };
  assert.deepEqual(f.adapter.pendingWork(), []);
  const receipt = await f.request('/compact', input); assert.equal(receipt.value.result.state, 'running');
  const snapshot = f.adapter.pendingWork(), pending = snapshot[0];
  assert.ok(Object.isFrozen(snapshot)); assert.ok(pending instanceof Promise); assert.equal(snapshot.length, 1);
  await f.request('/compact', input); assert.equal(f.adapter.pendingWork()[0], pending); assert.equal(calls, 1);
  command.resolve({ commandId: 'synthetic-completed', result: { kind: 'success', text: 'Synthetic compacted history.' } });
  await saving.promise; assert.equal(f.adapter.pendingWork()[0], pending); assert.equal(signal.aborted, false);
  assert.equal((await f.request('/compact/status', input)).value.result.state, 'running');
  persisted.resolve(); await pending;
  assert.deepEqual(f.adapter.pendingWork(), []); assert.equal((await f.request('/compact/status', input)).value.result.outcome, 'success');
});

test('failed compact commands retire their real Promise only after the existing unconfirmed receipt is recorded', async t => {
  const f = await fixture(t), command = Promise.withResolvers(), input = { sessionId: 'work-a', requestId: 'observed-failure' };
  f.ctx.commands.execute = () => command.promise;
  await f.request('/compact', input); const pending = f.adapter.pendingWork()[0];
  command.reject(new Error(privateError)); await pending;
  assert.deepEqual(f.adapter.pendingWork(), []);
  const response = await f.request('/compact/status', input);
  assert.equal(response.value.result.state, 'settled'); assert.equal(response.value.result.outcome, 'unconfirmed');
  assert.equal(JSON.stringify(response).includes(privateError), false);
});
test('late cancellation cannot overwrite completed compact success and failed persistence remains unconfirmed', async t => {
  const f = await fixture(t);
  f.ctx.commands.execute = async () => ({ commandId: 'fixture-compact', result: { kind: 'success', text: 'No compactable history yet.' } });
  const first = { sessionId: 'work-a', requestId: 'compact-empty' };
  await f.request('/compact', first); await new Promise(resolve => setImmediate(resolve));
  assert.equal((await f.request('/compact/cancel', first)).value.result.outcome, 'empty');
  f.ctx.sessions.flush = async () => false;
  const second = { sessionId: 'work-a', requestId: 'compact-unsaved' };
  await f.request('/compact', second); await new Promise(resolve => setImmediate(resolve));
  assert.equal((await f.request('/compact/status', second)).value.result.outcome, 'unconfirmed');
  assert.equal((await f.request('/compact/status', { sessionId: 'work-a', requestId: 'unknown-id' })).value.result.state, 'unknown');
});
test('disposing the adapter prevents a queued compact from dispatching and a different compact definition is unavailable', async t => {
  const f = await fixture(t); let resolveAgent, dispatches = 0;
  f.ctx.sessionController.resolveAgent = async () => new Promise(resolve => { resolveAgent = resolve; });
  f.ctx.commands.execute = async () => { dispatches++; return { commandId: 'fixture', result: { kind: 'success' } }; };
  await f.request('/compact', { sessionId: 'work-a', requestId: 'dispose-before-dispatch' });
  f.adapter.dispose(); resolveAgent({ agent: { id: 'work-a', session: { id: 'work-a' } } });
  await new Promise(resolve => setImmediate(resolve)); assert.equal(dispatches, 0);
  const g = await fixture(t); g.ctx.commands.find = () => ({ definitionId: '@fixture/not-compact' });
  const input = { sessionId: 'work-a', requestId: 'wrong-definition' };
  await g.request('/compact', input); await new Promise(resolve => setImmediate(resolve));
  assert.equal((await g.request('/compact/status', input)).value.result.outcome, 'unavailable');
});

test('native goal operations bind the owning session and reject a stale goal revision', async t => {
  const f = await fixture(t); let current;
  f.ctx.goals = {
    get: agent => { assert.equal(agent.id, 'work-a'); return current; },
    create: (agent, request) => {
      current = { id: 'fixture-goal', revision: 1, objective: request.objective, phase: 'active', maxGoalRounds: 40 };
      f.values.goal = { goal: current, roundsStarted: 0 };
    },
    pause: (agent, ref) => { assert.deepEqual(ref, { id: current.id, revision: current.revision }); current = { ...current, revision: 2, phase: 'paused' }; f.values.goal.goal = current; },
    clear: (agent, ref) => { assert.deepEqual(ref, { id: current.id, revision: current.revision }); current = undefined; f.values.goal = null; },
  };
  const created = await f.request('/goal', { sessionId: 'work-a', action: 'create', objective: '  Fixture goal  ' });
  assert.equal(created.status, 200); assert.equal(created.value.result.goal.objective, 'Fixture goal');
  assert.equal(created.value.result.goal.phase, 'active');
  assert.equal((await f.request('/goal', { sessionId: 'work-a', action: 'create', objective: 'replacement' })).status, 409);
  const paused = await f.request('/goal', { sessionId: 'work-a', action: 'pause', expectedId: 'fixture-goal', expectedRevision: 1 });
  assert.equal(paused.value.result.goal.phase, 'paused');
  assert.equal((await f.request('/goal', { sessionId: 'work-a', action: 'clear', expectedId: 'fixture-goal', expectedRevision: 1 })).status, 409);
  assert.equal((await f.request('/goal', { sessionId: 'work-a', action: 'clear', expectedId: 'fixture-goal', expectedRevision: 2 })).value.result.goal, null);
  assert.equal(f.calls.filter(row => row[0] === 'flush').length, 3);
  assert.equal(f.calls.filter(row => row[0] === 'command').length, 0);
});

test('native goals reject invalid objectives and managed sessions before mutation', async t => {
  const f = await fixture(t);
  for (const objective of ['', ' '.repeat(8), 'x'.repeat(16001), 1]) {
    assert.equal((await f.request('/goal', { sessionId: 'work-a', action: 'create', objective })).status, 400);
  }
  assert.equal((await f.request('/goal', { sessionId: 'companion-a', action: 'create', objective: 'Fixture' })).status, 409);
  assert.equal((await f.request('/goal', { sessionId: 'work-a', action: 'delete' })).status, 400);
  assert.equal(f.calls.length, 0);
});
