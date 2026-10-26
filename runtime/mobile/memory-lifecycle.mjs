const MEMORY_PLUGIN = '@droyagent/memory';

/** Keep optional memory collection tied to the actual loaded feature. */
export function createMemoryFeatureLifecycle({ ctx, host, reportError = () => {} }) {
  let settled = false, closed = false, startup, stopping, unavailableStopped = false;
  function running() {
    if (closed) return false;
    let fiber = ctx.fiber;
    while (fiber) {
      if (fiber.uid === null || fiber.state === 5) return false;
      if (fiber.parent.fiber === fiber) break;
      fiber = fiber.parent.fiber;
    }
    return true;
  }
  function available() {
    if (!settled || !running() || !ctx.get('droyMemory')) return false;
    return [...ctx.get('loader').entries()].some(entry => entry.options.name === MEMORY_PLUGIN
      && !entry.disabled && entry.fiber?.state === 2);
  }
  function stopIfUnavailable() {
    if (!settled || !running()) return;
    if (available()) { unavailableStopped = false; return; }
    if (unavailableStopped) return;
    stopping ??= Promise.resolve().then(() => host.featureStop())
      .then(() => { unavailableStopped = true; })
      .catch(error => reportError(error.code ?? 'MEMORY_FEATURE_STOP_FAILED'))
      .finally(() => { stopping = undefined; });
    return stopping;
  }
  ctx.on('internal/status', fiber => {
    if (fiber.entry?.options.name === MEMORY_PLUGIN) void stopIfUnavailable();
  }, { global: true });
  ctx.on('internal/service', name => {
    if (name === 'droyMemory') void stopIfUnavailable();
  }, { global: true });
  return {
    available,
    start(callback) {
      // Do not return this task from an injected plugin effect: that would make
      // Loader.await() wait on itself and deadlock concurrent module loading.
      startup ??= Promise.resolve().then(() => ctx.get('loader').await()).then(async () => {
        if (!running()) return;
        settled = true;
        if (!available()) { await host.featureStop(); unavailableStopped = true; }
        if (running()) await callback();
      });
      startup.catch(error => reportError(error.code ?? 'MEMORY_FEATURE_START_FAILED'));
      return startup;
    },
    close() { closed = true; },
    async drain() { await stopping; },
  };
}
