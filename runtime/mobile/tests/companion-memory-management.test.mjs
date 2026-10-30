import test from 'node:test';
import assert from 'node:assert/strict';
import { createRequire } from 'node:module';
import { pathToFileURL } from 'node:url';
import { companionMemoryManagementTool, consumeCompanionMemoryAuthorization,
  installCompanionMemoryManagement } from '../companions/memory-management.mjs';

const installed = createRequire(new URL('../../dsh/package.json', import.meta.url));
const { defineTool } = await import(pathToFileURL(installed.resolve('@deepseek-ai/dsh-tools')).href);
const deferred = () => { let resolve; const promise = new Promise(done => { resolve = done; }); return { promise, resolve }; };

function fixture({ language = 'en', states = ['active', 'retained'], ask, operate } = {}) {
  const rows = states.map((state, index) => ({ memorySpaceId: `space-${index}`, companionId: `companion-${index}`,
    revision: 1, epoch: 3, state, displayNameSnapshot: `Fictional ${index}`, createdAt: '2026-10-30T00:00:00.000Z',
    payload: { text: 'PRIVATE CORPUS' }, personaSnapshot: 'PRIVATE PERSONA', directory: '/private/internal' }));
  const events = [{ type: 'turn/start', seq: 0 }];
  const agent = { id: 'workbench-session', session: { id: 'workbench-session', get seq() { return events.length; }, eventAt: index => events[index] } };
  const agents = new Map([[agent.id, agent]]), roots = [agent], owned = new Set();
  const signal = new AbortController(), questions = [], calls = [], registered = [], hooks = new Map();
  const services = {
    agents: { get: key => agents.get(key), roots: () => roots },
    droyCompanions: { ownsSession: key => owned.has(key), isRestrictedAgent: value => owned.has(value.id) },
    settings: { describe: () => [{ ns: 'locale', value: { preference: language } }] },
    userQuestions: { async ask(input) {
      questions.push(input);
      return ask ? ask(input) : { answers: [{ id: input.questions[0].id, selected: [input.questions[0].options[1].label] }] };
    } },
  };
  let cleanup;
  const ctx = { get: name => services[name], tools: { register: value => registered.push(value) },
    effect: fn => { cleanup = fn(); }, on: (event, fn) => hooks.set(event, fn) };
  const exec = { agent, callId: 'call-one', signal: signal.signal };
  const service = { list: async () => rows };
  for (const operation of ['purge', 'restore']) service[operation] = async (input, authorization) => {
    calls.push({ operation, input, authorization });
    if (operate) return operate({ operation, input, authorization, exec });
    const receipt = consumeCompanionMemoryAuthorization(authorization, { operation, ...input, sessionId: agent.session.id });
    assert.equal(receipt.epoch, 3);
    return { completed: true, body: 'DO NOT DISPLAY THIS', directory: '/private/hidden' };
  };
  const api = installCompanionMemoryManagement(ctx, service, { defineTool });
  return { api, ctx, services, exec, events, agent, agents, roots, owned, signal, rows, questions, calls, registered, hooks,
    dispose: () => cleanup(), setLanguage: value => { language = value; } };
}

test('list returns only whitelisted identities and state, never memory or persona bodies', async () => {
  const f = fixture();
  const result = await f.registered[0].execute({ operation: 'list' }, f.exec);
  assert.equal(result.items.length, 2);
  assert.deepEqual(Object.keys(result.items[0]).sort(), ['companionId', 'createdAt', 'displayName', 'epoch', 'memorySpaceId', 'revision', 'state']);
  assert.doesNotMatch(JSON.stringify(result), /PRIVATE|internal|payload|Snapshot/);
  assert.equal(f.questions.length, 0);
});

test('purge obtains exact-scope user authorization and exposes only a completion receipt', async () => {
  const f = fixture();
  const result = await f.registered[0].execute({ operation: 'purge', memorySpaceId: 'space-0', expectedRevision: 1 }, f.exec);
  assert.equal(result.completed, true);
  assert.match(f.questions[0].questions[0].question, /all local memory.*Fictional 0.*space-0/);
  assert.doesNotMatch(JSON.stringify(result), /DO NOT DISPLAY|private/);
  assert.equal(f.calls.length, 1);
  assert.match(f.calls[0].input.operationId, /^memory-management-[a-f0-9]{64}$/);
  assert.deepEqual(JSON.parse(JSON.stringify(f.calls[0].authorization)), {});
});

test('only retained spaces can be restored and current state is checked before asking', async () => {
  const f = fixture();
  await assert.rejects(f.api.execute({ operation: 'restore', memorySpaceId: 'space-0', expectedRevision: 1 }, f.exec), { code: 'COMPANION_MEMORY_STATE_CONFLICT' });
  assert.equal(f.questions.length, 0);
  assert.equal((await f.api.execute({ operation: 'restore', memorySpaceId: 'space-1', expectedRevision: 1 }, f.exec)).completed, true);
  assert.match(f.questions[0].questions[0].question, /does not restore all deleted chats and tasks/);
});

test('missing targets, wrong revisions, paths, extra authorization and message IDs are rejected', async () => {
  const f = fixture();
  for (const args of [
    { operation: 'purge', memorySpaceId: 'missing', expectedRevision: 1 },
    { operation: 'purge', memorySpaceId: 'space-0', expectedRevision: 2 },
    { operation: 'purge', memorySpaceId: '../../space-0', expectedRevision: 1 },
    { operation: 'purge', memorySpaceId: 'space-0', expectedRevision: 1, requestMessageId: 'claimed-user-request' },
    { operation: 'purge', memorySpaceId: 'space-0', expectedRevision: 1, authorization: {} },
    { operation: 'list', expectedRevision: 1 },
  ]) await assert.rejects(f.api.execute(args, f.exec));
  assert.equal(f.questions.length, 0);
  assert.equal(f.calls.length, 0);
});

test('companions including work sessions cannot list, purge, or restore', async () => {
  const f = fixture();
  f.owned.add(f.agent.session.id);
  for (const operation of ['list', 'purge', 'restore']) await assert.rejects(f.api.execute({ operation }, f.exec), { code: 'COMPANION_MEMORY_WORKBENCH_REQUIRED' });
  assert.equal(f.questions.length, 0);
});

test('identity must be the exact live root agent, not a fabricated or child context', async () => {
  const f = fixture();
  await assert.rejects(f.api.execute({ operation: 'list' }, { ...f.exec, agent: { ...f.agent } }), { code: 'COMPANION_MEMORY_WORKBENCH_REQUIRED' });
  f.roots.length = 0;
  await assert.rejects(f.api.execute({ operation: 'list' }, f.exec), { code: 'COMPANION_MEMORY_WORKBENCH_REQUIRED' });
  f.roots.push(f.agent);
  delete f.services.droyCompanions;
  await assert.rejects(f.api.execute({ operation: 'list' }, f.exec), { code: 'COMPANION_MEMORY_WORKBENCH_REQUIRED' });
});

test('cancel, free text, multiple choices, or a wrong question do not authorize mutations', async () => {
  for (const select of [q => ({ id: q.id, selected: [q.options[0].label] }),
    q => ({ id: q.id, custom: 'yes', selected: [q.options[1].label] }),
    q => ({ id: q.id, selected: q.options.map(option => option.label) }),
    q => ({ id: 'other', selected: [q.options[1].label] })]) {
    const f = fixture({ ask: async input => ({ answers: [select(input.questions[0])] }) });
    const result = await f.api.execute({ operation: 'purge', memorySpaceId: 'space-0', expectedRevision: 1 }, f.exec);
    assert.equal(result.cancelled, true);
    assert.equal(f.calls.length, 0);
  }
});

test('ownership, turn and space changes while awaiting an answer invalidate authorization', async () => {
  for (const change of [f => f.owned.add(f.agent.session.id), f => f.events.push({ type: 'turn/start' }),
    f => f.rows[0].revision++, f => f.rows[0].epoch++, f => f.rows[0].state = 'retained',
    f => f.agents.delete(f.agent.id)]) {
    const answer = deferred();
    const f = fixture({ ask: () => answer.promise });
    const pending = f.api.execute({ operation: 'purge', memorySpaceId: 'space-0', expectedRevision: 1 }, f.exec);
    await new Promise(resolve => setImmediate(resolve));
    const q = f.questions[0].questions[0]; change(f);
    answer.resolve({ answers: [{ id: q.id, selected: [q.options[1].label] }] });
    await assert.rejects(pending);
    assert.equal(f.calls.length, 0);
  }
});

test('cancellation and plugin disposal revoke pending authorization', async () => {
  for (const cancel of [f => f.signal.abort(), f => f.dispose()]) {
    const answer = deferred(), f = fixture({ ask: () => answer.promise });
    const pending = f.api.execute({ operation: 'purge', memorySpaceId: 'space-0', expectedRevision: 1 }, f.exec);
    await new Promise(resolve => setImmediate(resolve));
    const q = f.questions[0].questions[0]; cancel(f);
    answer.resolve({ answers: [{ id: q.id, selected: [q.options[1].label] }] });
    await assert.rejects(pending);
    assert.equal(f.calls.length, 0);
  }
});

test('opaque grants cannot be forged, copied, replayed or used for another operation or session', async () => {
  assert.throws(() => consumeCompanionMemoryAuthorization({}, { operation: 'purge' }), { code: 'COMPANION_MEMORY_AUTHORIZATION_REQUIRED' });
  for (const tamper of [x => ({ ...x, operation: 'restore' }), x => ({ ...x, memorySpaceId: 'space-1' }),
    x => ({ ...x, expectedRevision: 2 }), x => ({ ...x, sessionId: 'another-session' }), x => ({ ...x, operationId: 'replayed-call' })]) {
    const f = fixture({ operate({ input, operation, authorization }) {
      assert.throws(() => consumeCompanionMemoryAuthorization(authorization, tamper({ operation, ...input })), { code: 'COMPANION_MEMORY_AUTHORIZATION_REQUIRED' });
      assert.throws(() => consumeCompanionMemoryAuthorization(authorization, { operation, ...input }), { code: 'COMPANION_MEMORY_AUTHORIZATION_REQUIRED' });
      return { completed: false };
    } });
    await assert.rejects(f.api.execute({ operation: 'purge', memorySpaceId: 'space-0', expectedRevision: 1 }, f.exec), { code: 'COMPANION_MEMORY_OPERATION_INCOMPLETE' });
  }
  const f = fixture();
  await f.api.execute({ operation: 'purge', memorySpaceId: 'space-0', expectedRevision: 1 }, f.exec);
  const { input, operation, authorization } = f.calls[0];
  assert.throws(() => consumeCompanionMemoryAuthorization(authorization, { operation, ...input }), { code: 'COMPANION_MEMORY_AUTHORIZATION_REQUIRED' });
});

test('pending journals and services that skip authorization cannot produce success receipts', async () => {
  const f = fixture({ operate: () => ({ completed: true }) });
  await assert.rejects(f.api.execute({ operation: 'purge', memorySpaceId: 'space-0', expectedRevision: 1 }, f.exec), { code: 'COMPANION_MEMORY_AUTHORIZATION_NOT_CONSUMED' });
  const g = fixture({ operate({ input, operation, authorization }) {
    consumeCompanionMemoryAuthorization(authorization, { operation, ...input });
    return { state: 'purging' };
  } });
  await assert.rejects(g.api.execute({ operation: 'purge', memorySpaceId: 'space-0', expectedRevision: 1 }, g.exec), { code: 'COMPANION_MEMORY_OPERATION_INCOMPLETE' });
});

test('duplicate in-flight calls do not create multiple authorization requests', async () => {
  const answer = deferred(), f = fixture({ ask: () => answer.promise });
  const args = { operation: 'purge', memorySpaceId: 'space-0', expectedRevision: 1 };
  const pending = f.api.execute(args, f.exec);
  await new Promise(resolve => setImmediate(resolve));
  await assert.rejects(f.api.execute(args, f.exec), { code: 'COMPANION_MEMORY_REQUEST_PENDING' });
  answer.resolve({ answers: [] });
  assert.equal((await pending).cancelled, true);
  assert.equal(f.questions.length, 1);
});

test('scope arguments are captured before asynchronous authorization', async () => {
  const answer = deferred(), f = fixture({ ask: () => answer.promise });
  const args = { operation: 'purge', memorySpaceId: 'space-0', expectedRevision: 1 };
  const pending = f.api.execute(args, f.exec);
  await new Promise(resolve => setImmediate(resolve));
  const q = f.questions[0].questions[0];
  args.operation = 'restore'; args.memorySpaceId = 'space-1'; args.expectedRevision = 99;
  answer.resolve({ answers: [{ id: q.id, selected: [q.options[1].label] }] });
  assert.equal((await pending).operation, 'purge');
  assert.equal(f.calls[0].operation, 'purge');
  assert.equal(f.calls[0].input.memorySpaceId, 'space-0');
  assert.equal(f.calls[0].input.expectedRevision, 1);
});

test('tool schemas, scope confirmation and receipts follow the current saved language', async () => {
  const f = fixture();
  assert.match(f.registered[0].description, /only in the workbench/);
  const run = f.hooks.get('system-prompt/assemble');
  f.setLanguage('zh-CN');
  const assembly = await run({}, {}, async () => ({ tools: [f.registered[0]], sections: [{ name: 'tools:sdk', text: f.registered[0].description }] }));
  assert.match(assembly.tools[0].description, /仅在工作台/);
  assert.match(assembly.tools[0].parameters.properties.memorySpaceId.description, /不是名称或路径/);
  assert.match(assembly.sections[0].text, /仅在工作台/);
  const result = await f.registered[0].execute({ operation: 'purge', memorySpaceId: 'space-0', expectedRevision: 1 }, f.exec);
  assert.match(f.questions[0].questions[0].question, /永久删除/);
  assert.equal(result.message, '已删除所选空间的本地记忆。');
  for (const locale of ['zh', 'en']) {
    const tool = defineTool({ ...companionMemoryManagementTool(locale), execute: async () => ({}), output: { schema: { type: 'json' }, render: () => [] } });
    assert.equal(tool.parameters.properties.expectedRevision.oneOf.length, 2);
  }
});
