import test from 'node:test';
import assert from 'node:assert/strict';
import { createRequire, registerHooks } from 'node:module';
import { pathToFileURL } from 'node:url';
import { readFile } from 'node:fs/promises';
import vm from 'node:vm';
const installed = createRequire(new URL('../../dsh/package.json', import.meta.url));
const hooks = registerHooks({ resolve(specifier, context, next) {
  if (specifier === 'zod' || specifier.startsWith('@deepseek-ai/'))
    return { url: pathToFileURL(installed.resolve(specifier)).href, shortCircuit: true };
  return next(specifier, context);
} });
const { Context } = await import('@deepseek-ai/cordis');
const { default: Sessions } = await import('@deepseek-ai/dsh-session');
const { default: Projections } = await import('@deepseek-ai/dsh-session-projection');
const { installTokenUsage, compactionUsageProjection, combineTokenUsage, COMPACTION_USAGE_KEY } = await import('../token-usage.mjs');
hooks.deregister();

const usage = { inputTokens: 100, outputTokens: 30, cacheReadTokens: 200, cacheWriteTokens: 0, totalTokens: 330 };
const task = { uncachedInputTokens: 600, outputTokens: 150, cacheReadTokens: 1200, cacheWriteTokens: 40 };
function appendSummary(session, id, usage) {
  session.append('compaction/start', { compactionId: id, turn: null });
  session.append('compaction/summary', { compactionId: id, summary: [{ type: 'text', text: '虚构任务检查点' }],
    shadowedRange: { start: 0, end: 0 }, shadowedSeqs: [0], shadowedTokenCount: 500,
    provider: 'fictional', model: 'scripted', ...(usage === undefined ? {} : { usage }) });
  session.append('compaction/end', { compactionId: id, turn: null });
}
async function fixture(t) {
  const ctx = new Context();
  await ctx.plugin(Sessions); await ctx.plugin(Projections);
  installTokenUsage(ctx);
  t.after(() => ctx.fiber.dispose());
  const session = ctx.sessions.create('usage-fixture');
  return { ctx, session, read: () => ctx.sessionProjections.snapshot(session).values[COMPACTION_USAGE_KEY] };
}

test('summary usage is added to native task totals without re-counting task or historical replacement events', async t => {
  const f = await fixture(t);
  appendSummary(f.session, 'summary-1', usage);
  const first = f.read();
  assert.deepEqual(first, { totals: { uncachedInputTokens: 100, outputTokens: 30, cacheReadTokens: 200, cacheWriteTokens: 0 }, summaryCount: 1, missingUsageCount: 0 });
  for (const event of [
    { type: 'assistant/message', data: { usage } },
    { type: 'tool/result', data: { message: { content: [{ type: 'text', text: 'historical replacement' }] } } },
    { type: 'compaction/prune', data: { shadowedTokenCount: 999999 } },
    { type: 'request/context', data: { contextWindow: 1000000 } },
  ]) assert.equal(compactionUsageProjection.apply(first, event), first);
  const sum = combineTokenUsage(task, first);
  assert.equal(sum.totalTokens, 2320);
  assert.equal(sum.totals.cacheWriteTokens, 40);
  assert.equal(sum.compaction.outputTokens, 30);
  assert.equal(sum.task.outputTokens, 150);
});

test('real DSH projection repeated reads and seed replay retain one contribution per summary', async t => {
  const f = await fixture(t);
  appendSummary(f.session, 'summary-1', usage);
  appendSummary(f.session, 'summary-2', { ...usage, inputTokens: 10, totalTokens: 240 });
  const expected = f.read();
  for (let index = 0; index < 4; index++) assert.deepEqual(f.read(), expected);
  const replayed = f.ctx.sessions.create('usage-replayed', { seed: f.session.snapshotEvents() });
  assert.deepEqual(f.ctx.sessionProjections.snapshot(replayed).values[COMPACTION_USAGE_KEY], expected);
  assert.equal(expected.summaryCount, 2);
  assert.equal(expected.totals.uncachedInputTokens, 110);
});

test('unrecorded summary usage remains explicitly missing rather than claiming zero consumption', async t => {
  const f = await fixture(t);
  appendSummary(f.session, 'summary-without-usage');
  appendSummary(f.session, 'summary-with-usage', usage);
  const state = f.read();
  assert.equal(state.summaryCount, 2); assert.equal(state.missingUsageCount, 1);
  assert.equal(combineTokenUsage(task, state).missingUsageCount, 1);
  assert.equal(combineTokenUsage(undefined, state), undefined);
  assert.throws(() => combineTokenUsage({ ...task, outputTokens: -1 }, state));
});

test('compact summary usage line uses public session projections and appears only after a summary', async () => {
  const source = await readFile(new URL('../token-usage-client.js', import.meta.url), 'utf8');
  const records = [];
  const context = { slots: { inject(name, apply) { assert.equal(name, 'conversation.composer.dock'); apply(); },
    register(options, component) { records.push({ options, component }); } } };
  const React = { createElement(type, props, ...children) { return { type, props, children }; } };
  const copy = await readFile(new URL('../ui-copy.js', import.meta.url), 'utf8');
  const install = vm.runInNewContext(`${copy}\n${source}\ninstallTokenUsageClient`);
  const Component = install(context, React);
  assert.equal(records[0].options.id, 'droy-total-token-usage');
  const summary = { totals: { uncachedInputTokens: 100, outputTokens: 30, cacheReadTokens: 200, cacheWriteTokens: 0 }, summaryCount: 1, missingUsageCount: 0 };
  const render = values => Component({ useProjection: key => values[key] });
  assert.equal(render({ tokenUsage: task, droyCompactionUsage: { ...summary, summaryCount: 0 } }), null);
  assert.equal(render({ droyCompactionUsage: summary }), null);
  const line = render({ tokenUsage: task, droyCompactionUsage: summary });
  assert.match(line.children[0], /总用量（含上下文摘要） 2,320 tokens/);
  assert.match(line.props.title, /普通请求 1,990；上下文摘要 330/);
  assert.equal(line.props.style.whiteSpace, 'normal');
  const partial = render({ tokenUsage: task, droyCompactionUsage: { ...summary, missingUsageCount: 1 } });
  assert.match(partial.children[0], /≥/);
  assert.match(partial.props['aria-label'], /部分摘要未记录用量/);
});
