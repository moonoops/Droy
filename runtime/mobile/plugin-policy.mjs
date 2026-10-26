export const name = 'droy-plugin-policy';
const prohibited = '@deepseek-ai/dsh-compaction-tool-result-pruner';
const blocked = [prohibited];
const isProhibited = entry => blocked.some(name => entry?.options?.name === name || entry?.options?.name?.startsWith(name + '/'));
const rejection = () => new Error('DSH head/tail pruning is prohibited in Droy');

export async function apply(ctx) {
  ctx.on('loader/entry-init', entry => { if (isProhibited(entry) && !entry.disabled) throw rejection(entry); }, { global: true, prepend: true });
  ctx.on('loader/patch-context', (entry, next) => {
    // Disabled declarations stop in Loader.update(), before this activation
    // hook. Reject here even if an in-flight import was disabled meanwhile.
    if (isProhibited(entry)) throw rejection(entry);
    return next();
  }, { global: true, prepend: true });

  // Root rows import concurrently. Revoke an earlier activation before phone
  // tools become available; later imports pass through the Loader hook above.
  for (const runtime of ctx.registry.values()) {
    for (const fiber of [...runtime.fibers]) {
      if (!isProhibited(fiber.entry)) continue;
      fiber.entry.options.disabled = true;
      await fiber.dispose();
      ctx.logger.warn('Disabled conflicting DSH plugin', fiber.entry.options.name);
    }
  }
  ctx.provide('droyPluginPolicy', Object.freeze({ prohibitedPlugins: Object.freeze(blocked) }));
}
