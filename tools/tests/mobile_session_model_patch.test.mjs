import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { patchSessionModelSelection } from '../mobile_session_model_patch.mjs';

const upstream = await readFile(new URL('../../runtime/dsh/node_modules/@deepseek-ai/dsh-api-session-controller/lib/index.js', import.meta.url), 'utf8');
const patched = patchSessionModelSelection(upstream);

function actualCommand(source) {
  const start = source.indexOf('\tasync selectModel(request) {');
  const end = source.indexOf('\n\t/**', start);
  assert.ok(start >= 0 && end > start, 'The actual upstream command body must be available');
  class RemoteError extends Error { constructor(code, message) { super(message); this.code = code; } }
  return Function('ReasoningEffortId', 'remoteErrorOf', 'RemoteError',
    `return ({${source.slice(start, end).trim()}}).selectModel;`)(id => id, error => error.code, RemoteError);
}

function actualRemoteForwarder(source) {
  const body = '\t\tselectModel(request) {\n\t\t\treturn this.commands.selectModel(request);\n\t\t}';
  assert.ok(source.includes(body));
  return Function(`return ({${body.trim()}}).selectModel;`)();
}

function fixture(source = patched) {
  const selections = new Map();
  const writes = [];
  let global = { provider: 'fixture', model: 'initial-global' };
  const context = {
    async resolveAgent(id) { return { id }; },
    async requireModel(request) {
      if (!request.provider || !request.model) throw Object.assign(new Error('Missing route'), { code: 'fixture/invalid' });
    },
    agents: {
      async serializeImageAdmission(_agent, run) { return run(); },
      selectForNextRequest(agent, selected) { selections.set(agent.id, selected); },
    },
    ctx: {
      llm: { async resolveCallConfig(route) { return { ...route }; } },
      agentDefaultModel: { async saveSelection(selected) { writes.push(selected); global = selected; } },
      logger: { warn() {} },
    },
  };
  const command = actualCommand(source).bind(context);
  const remote = actualRemoteForwarder(source).bind({ commands: { selectModel: command } });
  return { context, remote, selections, writes, get global() { return global; } };
}

test('native Session selection executes the patched upstream command without saving the global default', async () => {
  const f = fixture();
  const selected = { provider: 'fixture', model: 'native-model', reasoningEffort: 'high' };
  assert.deepEqual(await f.remote({ sessionId: 'native-session', ...selected, persistDefault: false }), { selected });
  assert.deepEqual(f.selections.get('native-session'), selected);
  assert.deepEqual(f.global, { provider: 'fixture', model: 'initial-global' });
  assert.deepEqual(f.writes, []);
});

test('existing upstream callers still save their Session choice as the default', async () => {
  for (const persistDefault of [undefined, true]) {
    const f = fixture();
    const selected = { provider: 'fixture', model: 'web-model' };
    await f.remote({ sessionId: 'web-session', ...selected, ...(persistDefault === undefined ? {} : { persistDefault }) });
    assert.deepEqual(f.global, selected);
    assert.deepEqual(f.writes, [selected]);
  }
});

test('the unpatched upstream command demonstrates the global write this patch isolates', async () => {
  const f = fixture(upstream);
  await f.remote({ sessionId: 'native-session', provider: 'fixture', model: 'native-model', persistDefault: false });
  assert.equal(f.global.model, 'native-model');
  assert.equal(f.writes.length, 1);
});

test('concurrent native Sessions cannot undo an explicit global selection', async () => {
  const f = fixture();
  const pending = new Map();
  f.context.ctx.llm.resolveCallConfig = route => route.model.startsWith('native-')
    ? new Promise(resolve => pending.set(route.model, () => resolve(route))) : Promise.resolve(route);
  const first = f.remote({ sessionId: 'session-a', provider: 'fixture', model: 'native-a', persistDefault: false });
  const second = f.remote({ sessionId: 'session-b', provider: 'fixture', model: 'native-b', persistDefault: false });
  await new Promise(resolve => setImmediate(resolve));
  assert.equal(pending.size, 2);
  await f.remote({ sessionId: 'web-session', provider: 'fixture', model: 'explicit-global' });
  pending.get('native-b')(); await second;
  pending.get('native-a')(); await first;
  assert.equal(f.selections.get('session-a').model, 'native-a');
  assert.equal(f.selections.get('session-b').model, 'native-b');
  assert.equal(f.global.model, 'explicit-global');
  assert.deepEqual(f.writes, [{ provider: 'fixture', model: 'explicit-global' }]);
});

test('model rejection leaves both Session and global selections untouched', async () => {
  const f = fixture();
  f.context.ctx.llm.resolveCallConfig = async () => { throw new Error('Fictional unavailable model'); };
  await assert.rejects(f.remote({ sessionId: 'a', provider: 'fixture', model: 'missing', persistDefault: false }),
    error => error.code === 'session/model-unavailable');
  assert.equal(f.selections.size, 0);
  assert.equal(f.writes.length, 0);
});

test('patch rejects source drift and repeated application instead of silently changing another operation', () => {
  assert.throws(() => patchSessionModelSelection(patched), /contract changed/);
  assert.throws(() => patchSessionModelSelection(upstream.replace('return this.commands.selectModel(request);', 'return this.commands.selectModel({ ...request });')), /contract changed/);
  assert.throws(() => patchSessionModelSelection(upstream + '\n' + upstream), /contract changed/);
});
