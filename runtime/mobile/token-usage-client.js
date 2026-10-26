// Inlined in the mobile client factory; the slot supplies session-scoped public projections.
function installTokenUsageClient(ctx, React) {
  const h = React.createElement;
  const total = usage => usage.uncachedInputTokens + usage.cacheReadTokens + usage.cacheWriteTokens + usage.outputTokens;
  const format = value => value.toLocaleString(ctx.locale?.getSnapshot().active === 'en' ? 'en-US' : 'zh-CN');
  function TokenUsage({ useProjection }) {
    useDroyLocale();
    const task = useProjection('tokenUsage');
    const summary = useProjection('droyCompactionUsage');
    if (!task || !summary || summary.summaryCount === 0) return null;
    const ordinary = total(task), compressed = total(summary.totals);
    const missing = summary.missingUsageCount > 0;
    const label = droyText("总用量（含上下文摘要） {0}{1} tokens", [missing ? '≥ ' : '', format(ordinary + compressed)]);
    const details = droyText("普通请求 {0}；上下文摘要 {1}；未缓存输入 {2}；缓存读取 {3}；缓存写入 {4}；输出 {5}。{6}", [format(ordinary), format(compressed), format(task.uncachedInputTokens + summary.totals.uncachedInputTokens), format(task.cacheReadTokens + summary.totals.cacheReadTokens), format(task.cacheWriteTokens + summary.totals.cacheWriteTokens), format(task.outputTokens + summary.totals.outputTokens), missing ? droyText("部分摘要未记录用量，合计为已记录部分。") : droyText("合计为已记录的普通请求与摘要用量。")]);
    return h('span', {
      'data-droy-token-usage': true, title: details, 'aria-label': `${label}。${details}`,
      style: { fontSize: 'var(--dsh-content-font-size-secondary,13px)', lineHeight: '1.5', color: 'var(--dsw-alias-label-tertiary)',
        flex: '1 0 100%', order: 1, minWidth: 0, maxWidth: '100%', whiteSpace: 'normal', overflowWrap: 'anywhere' },
    }, label);
  }
  ctx.slots.inject('conversation.composer.dock', () => ctx.slots.register({
    name: 'conversation.composer.dock', id: 'droy-total-token-usage', order: 2,
  }, TokenUsage));
  return TokenUsage;
}
