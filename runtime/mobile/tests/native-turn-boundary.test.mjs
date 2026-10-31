import test from 'node:test';
import assert from 'node:assert/strict';
import { createRequire, registerHooks } from 'node:module';
import { pathToFileURL } from 'node:url';
const installed = createRequire(new URL('../../dsh/package.json', import.meta.url));
const hooks = registerHooks({ resolve(specifier, context, next) {
  if (specifier.startsWith('@deepseek-ai/')) return { url: pathToFileURL(installed.resolve(specifier)).href, shortCircuit: true };
  return next(specifier, context);
} });
const { Context } = await import('@deepseek-ai/cordis');
const { default: Sessions } = await import('@deepseek-ai/dsh-session');
const { default: Projections } = await import('@deepseek-ai/dsh-session-projection');
const { turnBoundaryProjectionDefinition: upstream } = await import('@deepseek-ai/dsh-agent-loop');
const { nativeTurnBoundaryProjection: native } = await import('../native-turn-boundary.mjs');
hooks.deregister();

async function fixture(t) {
  const ctx = new Context();
  await ctx.plugin(Sessions); await ctx.plugin(Projections);
  ctx.sessionProjections.register(upstream);
  ctx.sessionProjections.register(native);
  t.after(() => ctx.fiber.dispose());
  return ctx;
}
test('real SDK wire snapshot and change feed expose completed turns without changing the host-only boundary', async t => {
  const ctx = await fixture(t), session = ctx.sessions.create('native-unread');
  const changes = [];
  ctx.sessionProjections.onChanged((session, key, value, seq) => changes.push({ id: session.id, key, value, seq }));
  assert.equal(upstream.wire, undefined);
  assert.equal(native.apply, upstream.apply);
  assert.equal(ctx.sessionProjections.snapshot(session).values.turnBoundary, undefined);
  session.append('turn/start', { turn: 1 });
  assert.equal(ctx.sessionProjections.snapshot(session).values.droyTurnBoundary.lastTurn, 1);
  assert.equal(ctx.sessionProjections.snapshot(session).values.droyTurnBoundary.openTurnStartSeq, 0);
  session.append('turn/end', { turn: 1 });
  const completed = changes.at(-1);
  assert.equal(completed.key, 'droyTurnBoundary');
  assert.equal(completed.seq, 1);
  assert.equal(completed.value.lastTurn, 1);
  assert.equal(completed.value.openTurnStartSeq, null);
  assert.deepEqual(completed.value, ctx.sessionProjections.stateOf(session, 'turnBoundary'));
});
test('registration folds existing cold history and preserves completed count at the exact replay cursor', async t => {
  const ctx = new Context();
  await ctx.plugin(Sessions); await ctx.plugin(Projections);
  t.after(() => ctx.fiber.dispose());
  const session = ctx.sessions.create('existing-history');
  session.append('turn/start', { turn: 1 }); session.append('turn/end', { turn: 1 });
  ctx.sessionProjections.register(native);
  const snapshot = ctx.sessionProjections.snapshot(session);
  assert.equal(snapshot.asOfSeq, 1);
  assert.equal(snapshot.values.droyTurnBoundary.lastTurn, 1);
  assert.equal(snapshot.values.droyTurnBoundary.openTurnStartSeq, null);
  const cold = ctx.sessions.create('cold-replay', { seed: session.snapshotEvents() });
  assert.deepEqual(ctx.sessionProjections.snapshot(cold).values, snapshot.values);
  assert.equal(ctx.sessionProjections.snapshot(cold).asOfSeq, cold.seq - 1);
  cold.append('turn/start', { turn: 2 });
  const running = ctx.sessionProjections.snapshot(cold).values.droyTurnBoundary;
  assert.equal(running.lastTurn - (running.openTurnStartSeq === null ? 0 : 1), 1);
});
