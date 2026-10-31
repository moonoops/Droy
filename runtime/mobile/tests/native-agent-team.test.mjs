import test from 'node:test';
import assert from 'node:assert/strict';
import { createServer } from 'node:http';
import { once } from 'node:events';
import { createNativeAgentTeam, nativeAgentTeamSnapshot } from '../native-agent-team.mjs';

function fixture(team) {
  const calls = [];
  const ctx = { droyCompanions: { ownsSession: id => id.startsWith('im:') },
    sessionController: { projections: async input => { calls.push(input.sessionId); return input.sessionId === 'missing' ? null
      : { kind: 'sequenced', asOfSeq: 2, values: { agentTeam: team } }; } } };
  return { ctx, calls };
}
const team = { members: [
  { id: 'work', name: 'Lead', role: 'lead', phase: 'active' },
  { id: 'work:child', name: 'Research', role: 'teammate', phase: 'active' },
  { id: 'im:private', name: 'Private', role: 'teammate', phase: 'active' },
  { id: 'failed', name: 'Failed', role: 'teammate', phase: 'failed', error: 'sensitive internal detail' },
], tasks: [
  { id: '1', subject: 'Read', description: 'Read fictional sources', status: 'in_progress', ownerName: 'Research',
    blockedBy: ['2'], writeScopes: ['notes/'], ready: false, writeScopeWarnings: [] },
  { id: '2', status: 'deleted' },
] };

test('durable team projection returns roster and task board without creating agents', async () => {
  const { ctx, calls } = fixture(team);
  const result = await nativeAgentTeamSnapshot(ctx, 'work');
  assert.deepEqual(calls, ['work']);
  assert.equal(result.available, true);
  assert.deepEqual(result.members.map(row => row.canOpen), [true, true, false, false]);
  assert.equal(JSON.stringify(result).includes('sensitive internal detail'), false);
  assert.equal(result.tasks.length, 1);
  assert.deepEqual(result.tasks[0].blockedBy, ['2']);
  assert.deepEqual(result.tasks[0].writeScopes, ['notes/']);
});
test('managed session reads are rejected before projection access; missing and disabled remain distinct', async () => {
  const { ctx, calls } = fixture(undefined);
  await assert.rejects(nativeAgentTeamSnapshot(ctx, 'im:private'), { status: 409 });
  assert.deepEqual(calls, []);
  await assert.rejects(nativeAgentTeamSnapshot(ctx, 'missing'), { status: 404 });
  assert.equal((await nativeAgentTeamSnapshot(ctx, 'work')).available, false);
  await assert.rejects(nativeAgentTeamSnapshot({}, 'work'), { status: 503 });
});
test('HTTP only accepts read-only scoped requests and redacts internal failures', async t => {
  const { ctx } = fixture(team);
  const server = createServer((req, res) => createNativeAgentTeam(ctx).handle(req, res));
  server.listen(0, '127.0.0.1'); await once(server, 'listening');
  t.after(() => new Promise(resolve => server.close(resolve)));
  const url = `http://127.0.0.1:${server.address().port}/droy/native/agent-team`;
  for (const query of ['', '?sessionId=a&sessionId=b', '?sessionId=a&other=b', '?sessionId=%00']) {
    assert.equal((await fetch(url + query)).status, 400);
  }
  assert.equal((await fetch(url + '?sessionId=work', { method: 'POST' })).status, 405);
  ctx.sessionController.projections = async () => { throw new Error('secret detail'); };
  const failed = await fetch(url + '?sessionId=work');
  assert.equal(failed.status, 500);
  assert.equal((await failed.text()).includes('secret detail'), false);
});
