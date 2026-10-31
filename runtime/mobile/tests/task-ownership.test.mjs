import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, mkdir, rm, symlink } from 'node:fs/promises';
import { join } from 'node:path';
import { tmpdir } from 'node:os';
import { assertUserCreate, assertUserSession, isCompanionSession, isUserSession, userControlFrames, userSessionList } from '../task-ownership.mjs';

test('public progress requires initialized ownership and retains user subagents', () => {
  assert.equal(isUserSession({}, 'user'), false);
  assert.throws(() => assertUserSession({}, 'user'), { code: 'DROY_OWNERSHIP_NOT_READY' });
  const ctx = { droyCompanions: { ownsSession: id => id === 'companion-work' } };
  assert.equal(isCompanionSession(ctx, 'companion-work'), true);
  assert.equal(isUserSession(ctx, 'companion-work'), false);
  assert.equal(isUserSession(ctx, 'user-child'), true);
  assert.throws(() => assertUserSession(ctx, 'companion-work'), { code: 'COMPANION_SESSION_MANAGED' });
  const value = { items: [{ sessionId: 'user' }, { sessionId: 'user-child', origin: 'subagent' }, { sessionId: 'companion-work' }] };
  assert.deepEqual(userSessionList(ctx, value), { items: value.items.slice(0, 2), managedSessionIds: ['companion-work'] });
  assert.equal(value.items.length, 3);
});

test('user control filtering keeps each user Session watermark and closes the original observation', async () => {
  const ctx = { droyCompanions: { ownsSession: id => id.startsWith('companion-') } };
  let closed = false;
  const user = { asOfSeq: 12, values: { droyTurnBoundary: { completed: 12 } } };
  async function* frames() {
    try {
      yield { type: 'baseline', value: { projections: { user, 'companion-chat': { asOfSeq: 99, values: {} } } } };
      yield { type: 'projection', sessionId: 'companion-work', key: 'title', value: 'Internal', seq: 40 };
      yield { type: 'projection', sessionId: 'user', key: 'title', value: 'User', seq: 15 };
      yield { type: 'projection', sessionId: 'user-child', key: 'title', value: 'Child', seq: 9 };
    } finally { closed = true; }
  }
  const values = await Array.fromAsync(userControlFrames(ctx, frames()));
  assert.deepEqual(values, [
    { type: 'baseline', value: { projections: { user } } },
    { type: 'projection', sessionId: 'user', key: 'title', value: 'User', seq: 15 },
    { type: 'projection', sessionId: 'user-child', key: 'title', value: 'Child', seq: 9 },
  ]);
  assert.equal(closed, true);
});

test('create refuses companion identities and canonical chat/work descendants without rejecting user siblings', async t => {
  const root = await mkdtemp(join(tmpdir(), 'droy-ownership-test-'));
  t.after(() => rm(root, { recursive: true, force: true }));
  const directory = join(root, 'companions'), chat = join(directory, 'one', 'chat'), work = join(directory, 'one', 'work');
  const user = join(root, 'user'), sibling = work + '-user';
  await Promise.all([mkdir(join(chat, 'nested'), { recursive: true }), mkdir(join(work, 'nested'), { recursive: true }),
    mkdir(user), mkdir(sibling, { recursive: true })]);
  const alias = join(root, 'alias'); await symlink(work, alias);
  const runtime = { directory, ownsSession: id => id === 'owned-session',
    store: { profiles: async () => [{ id: 'one', workspaceId: 'owned-space' }] } };
  const ctx = { droyCompanions: runtime, workspaceRegistry: { get: id => id === 'owned-space' ? { path: work } : undefined } };
  for (const input of [{ sessionId: 'owned-session' }, { workspaceId: 'owned-space' },
    ...[chat, work, join(chat, 'nested'), join(work, 'nested'), alias, join(alias, 'nested')].map(cwd => ({ cwd }))]) {
    await assert.rejects(assertUserCreate(ctx, input), error => ['COMPANION_SESSION_MANAGED', 'COMPANION_WORKSPACE_MANAGED'].includes(error.code));
  }
  for (const cwd of [user, sibling]) await assertUserCreate(ctx, { sessionId: 'user-session', workspaceId: 'user-space', cwd });
  await assertUserCreate({}, { cwd: user }, runtime);
});
