import { z } from 'zod';

export const COMPACTION_USAGE_KEY = 'droyCompactionUsage';
const count = z.number().int().nonnegative().max(Number.MAX_SAFE_INTEGER);
const bucketsSchema = z.object({
  uncachedInputTokens: count, cacheReadTokens: count, cacheWriteTokens: count, outputTokens: count,
}).strict();
const stateSchema = z.object({
  totals: bucketsSchema, summaryCount: count, missingUsageCount: count,
}).strict();
const zero = () => ({ uncachedInputTokens: 0, cacheReadTokens: 0, cacheWriteTokens: 0, outputTokens: 0 });
const keys = Object.keys(zero());

/** DSH drives this whole-log fold; paged chat nodes and historical replacements are not usage. */
export const compactionUsageProjection = {
  key: COMPACTION_USAGE_KEY,
  stateVersion: 1,
  stateSchema,
  init: () => ({ totals: zero(), summaryCount: 0, missingUsageCount: 0 }),
  apply(state, event) {
    if (event.type !== 'compaction/summary') return state;
    const usage = event.data.usage;
    if (usage === undefined) return { ...state, summaryCount: state.summaryCount + 1, missingUsageCount: state.missingUsageCount + 1 };
    const sample = bucketsSchema.parse({
      uncachedInputTokens: usage.inputTokens, outputTokens: usage.outputTokens,
      cacheReadTokens: usage.cacheReadTokens ?? 0, cacheWriteTokens: usage.cacheWriteTokens ?? 0,
    });
    const totals = bucketsSchema.parse(Object.fromEntries(keys.map(key => [key, state.totals[key] + sample[key]])));
    return { totals, summaryCount: state.summaryCount + 1, missingUsageCount: state.missingUsageCount };
  },
  wire: { viewSchema: stateSchema, view: state => state },
};

export function installTokenUsage(ctx) {
  return ctx.sessionProjections.register(compactionUsageProjection);
}

/** Combine DSH's canonical task totals with its separately recorded summary usage. */
export function combineTokenUsage(task, compaction) {
  if (task === undefined || compaction === undefined) return undefined;
  const ordinary = bucketsSchema.parse(task);
  const summary = stateSchema.parse(compaction);
  const totals = bucketsSchema.parse(Object.fromEntries(keys.map(key => [key, ordinary[key] + summary.totals[key]])));
  const totalTokens = keys.reduce((total, key) => total + totals[key], 0);
  if (!Number.isSafeInteger(totalTokens)) throw new RangeError('Token usage exceeds the supported count');
  return { task: ordinary, compaction: summary.totals, totals, totalTokens,
    summaryCount: summary.summaryCount, missingUsageCount: summary.missingUsageCount };
}
