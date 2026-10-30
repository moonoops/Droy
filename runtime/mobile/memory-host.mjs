import { createHash } from 'node:crypto';
import { join } from 'node:path';
import { pipeline } from 'node:stream/promises';
import { MemoryStore } from '../memory/store.mjs';
import { PrivacyStore } from '../privacy/store.mjs';
import { createMemoryMaintenance } from '../memory/maintenance.mjs';
import { createBridge } from './bridge.mjs';
import { requireMobileSession } from './auth.mjs';
import { createMemoryFeatureLifecycle } from './memory-lifecycle.mjs';

export const name = 'droy-memory-host';
export const inject = ['llm', 'webServer', 'tools'];
const hash = value => createHash('sha256').update(value).digest('hex');
const failure = code => Object.assign(new Error(code), { code });
const PURPOSES = ['conversation', 'session-title', 'compaction', 'extraction'];
function hostPaths(value) {
  const match = /^\/data\/user\/(0|[1-9]\d*)\/io\.droyagent\.app(?:\.dev)?\/files$/.exec(value?.filesDir);
  if (!match || !value.nativeDir?.startsWith('/data/app/') || !Number.isSafeInteger(value.appUid)
      || value.appUid % 100000 < 10000 || value.appUid % 100000 > 19999
      || Number(match[1]) !== Math.floor(value.appUid / 100000)) {
    throw failure('MEMORY_NATIVE_HOST_INVALID');
  }
  return { filesDir: value.filesDir, nativeDir: value.nativeDir, appUid: value.appUid };
}

/** Android host adapter. The memory package itself has no Android dependency. */
export function createMobileMemoryHost({ ctx, bridge, environment = process.env,
  openMemory = options => MemoryStore.open(options), openPrivacy = options => PrivacyStore.open(options),
  paths = hostPaths, clock = Date.now, reportError = () => {}, archiveFactory, featureAvailable = () => false } = {}) {
  let snapshot, host, memory, privacyStore, privacyOpening, privacyInspection, initialization, activation, closed = false;
  let initialized = false, ready = false, active = environment.DROY_MEMORY_PROTECTION_REQUIRED === '1', reason = 'MEMORY_DISABLED';
  let maintenance, extraction, archives, lastHousekeeping = 0, tickBusy = false, timer;
  let startup, shutdown, tickCompletion, createArchives = archiveFactory, maintenanceOwner;
  const routes = new Map();
  const privacy = () => ctx.get('droyPrivacy');
  const boundary = () => ctx.get('droyExecution');
  function ensureOpen() { if (closed) throw failure('MEMORY_HOST_CLOSED'); }
  function stopMaintenance(code) {
    // State getters are also called inside maintenance's serialized operations.
    // stop() aborts synchronously; awaiting its queued journal write here deadlocks.
    void maintenance?.stop(code).catch(error => reportError(error.code ?? 'MEMORY_MAINTENANCE_FAILED'));
  }
  function setupArchives() {
    if (!archives && host && createArchives) archives = createArchives({ store,
      directory: join(host.filesDir, 'droy-memory-archives'), authorizeUi: ({ req, res }) => requireMobileSession(req, res) });
  }
  async function refresh() {
    ensureOpen();
    const next = await bridge.memory({ operation: 'status' });
    ensureOpen();
    if (!next?.settings || typeof next.settings.privacyEverActivated !== 'boolean') throw failure('MEMORY_NATIVE_STATUS_INVALID');
    if (active && !next.settings.privacyEverActivated) throw failure('PRIVACY_ACTIVATION_DOWNGRADE');
    active ||= next.settings.privacyEverActivated;
    if (snapshot?.execution?.epoch && snapshot.execution.epoch !== next.execution?.epoch || ready && !next.execution?.ready) {
      ready = false; reason = next.execution?.reason ?? 'MEMORY_RUNTIME_CHANGED';
      privacy()?.invalidate(); stopMaintenance(reason);
    }
    snapshot = next;
    return next;
  }
  async function status() {
    if (closed) return { active, ready: false, reason: 'MEMORY_HOST_CLOSED' };
    try { await refresh(); }
    catch (error) {
      ready = false; reason = error.code ?? 'MEMORY_NATIVE_UNAVAILABLE'; privacy()?.invalidate();
      stopMaintenance(reason);
      // Keep existing model-processing grants authoritative across a host outage.
      if (!snapshot) throw error;
    }
    if (ready && (!privacy() || !boundary()?.installed?.())) {
      ready = false; reason = 'MEMORY_REQUIRED_PLUGIN_MISSING'; privacy()?.invalidate();
      stopMaintenance(reason);
      try { await bridge.memory({ operation: 'activation.invalidate' }); }
      catch { /* Missing native acknowledgement cannot restore readiness. */ }
    }
    return { active, ready: ready && initialized && !closed,
      reason: ready && initialized ? null : reason };
  }
  function policy() {
    if (!host) throw failure('MEMORY_HOST_NOT_READY');
    return { workspaceRoot: join(host.filesDir, 'dsh-home', 'workspace'), nodePath: join(host.nativeDir, 'libdroy_node.so'),
      pathAliases: /^\/data\/user\/0\/io\.droyagent\.app(?:\.dev)?\/files$/.test(host.filesDir)
        ? [{ from: '/data/data/' + host.filesDir.split('/')[4], to: host.filesDir.slice(0, -6) }] : [],
      workspaceRoots: [join(host.filesDir, 'dsh-home', 'workspace'), join(host.filesDir, 'workspace')],
      readOnlyRoots: [join(host.filesDir, 'runtime'), host.nativeDir],
      privateRoots: ['droy-memory', 'droy-privacy', 'droy-history-spool', 'droy-execution', '.dsh', 'dsh-home']
        .map(name => join(host.filesDir, name)) };
  }
  async function openPrivacyOnce(options) {
    if (privacyStore) return privacyStore;
    privacyOpening ??= openPrivacy(options).then(value => { privacyStore = value; return value; })
      .finally(() => { privacyOpening = undefined; });
    return privacyOpening;
  }
  async function initializeStores() {
    ensureOpen();
    if (initialized) return;
    initialization ??= (async () => {
      const permission = await bridge.memory({ operation: 'initialization.prepare' });
      ensureOpen();
      const trusted = paths(permission.host);
      if (trusted.filesDir !== host.filesDir || typeof permission.initialize !== 'boolean') throw failure('MEMORY_INITIALIZATION_DENIED');
      privacyStore = await openPrivacyOnce({ directory: join(host.filesDir, 'droy-privacy'),
        markerPath: join(host.filesDir, 'droy-privacy.identity.json'), initialize: permission.initialize });
      ensureOpen();
      memory = await openMemory({ directory: join(host.filesDir, 'droy-memory'), initialize: permission.initialize });
      ensureOpen();
      initialized = true;
    })();
    return initialization;
  }
  async function store() {
    if (!initialized) {
      if (activation) await activation;
      if (!initialized) throw failure('MEMORY_NOT_INITIALIZED');
    }
    return memory;
  }
  async function getPrivacyStore() {
    if (!privacyStore && activation) await activation;
    if (!privacyStore) throw failure('PRIVACY_NOT_INITIALIZED');
    return privacyStore;
  }
  async function activate() {
    ensureOpen();
    if (ready && initialized) return;
    if (activation) return activation;
    activation = (async () => {
      ready = false; reason = 'MEMORY_HOST_INITIALIZING';
      if (!privacy() || !boundary()?.installed?.()) throw failure('MEMORY_REQUIRED_PLUGIN_MISSING');
      const prepared = await bridge.memory({ operation: 'activation.prepare' });
      ensureOpen();
      host = paths(prepared.host);
      if (!boundary().installed()) throw failure('MEMORY_REQUIRED_PLUGIN_MISSING');
      await bridge.memory({ operation: 'protection-activate', challenge: prepared.challenge,
        runtimeEpoch: prepared.runtimeEpoch,
        qualificationId: hash(JSON.stringify({ runtimeEpoch: prepared.runtimeEpoch, challenge: prepared.challenge })) });
      active = true;
      await initializeStores();
      ensureOpen(); setupArchives();
      if (!featureAvailable()) await saveSettings({ enabled: false });
      await bridge.memory({ operation: 'host-ready', runtimeEpoch: prepared.runtimeEpoch });
      await refresh();
      if (!snapshot.execution?.ready) throw failure('MEMORY_EXECUTION_NOT_READY');
      ready = true; reason = null;
      if (snapshot.activation?.pendingEnable && featureAvailable()) await saveSettings({ enabled: true });
      maintenance?.resume();
    })().catch(async error => {
      ready = false; reason = error.code ?? 'MEMORY_ACTIVATION_FAILED'; privacy()?.invalidate();
      try { await bridge.memory({ operation: 'activation.invalidate' }); }
      catch { /* The local unavailable state is already authoritative. */ }
      throw error;
    }).finally(() => { activation = null; });
    return activation;
  }
  async function getSettings() { await refresh(); return { enabled: snapshot.settings.enabled && featureAvailable(),
    recallEnabled: snapshot.settings.recallEnabled, automaticExtraction: snapshot.settings.automaticExtraction }; }
  async function saveSettings(patch) {
    if (patch.enabled === true && !featureAvailable()) throw failure('MEMORY_FEATURE_UNAVAILABLE');
    if (patch.enabled === true) await activate();
    if (patch.enabled === false) { privacy()?.invalidate(); await maintenance?.stop('MEMORY_DISABLED'); }
    const result = await bridge.memory({ operation: 'settings', patch });
    if (patch.enabled === true && !featureAvailable()) {
      await saveSettings({ enabled: false });
      throw failure('MEMORY_FEATURE_UNAVAILABLE');
    }
    await refresh(); if (result.enabled) maintenance?.resume();
    return result;
  }
  function routingIdentity() {
    return hash(JSON.stringify(Object.fromEntries(['HTTP_PROXY', 'HTTPS_PROXY', 'ALL_PROXY', 'NO_PROXY',
      'http_proxy', 'https_proxy', 'all_proxy', 'no_proxy', 'NODE_USE_ENV_PROXY', 'NODE_EXTRA_CA_CERTS']
      .map(key => [key, environment[key] ?? '']))));
  }
  async function existingAuthorizationStore() {
    ensureOpen();
    if (privacyStore) return privacyStore;
    privacyInspection ??= (async () => {
      const inspection = await bridge.memory({ operation: 'authorization.inspect' });
      ensureOpen();
      if (inspection.available !== true) return null;
      const existing = paths(inspection.host);
      // This path opens only an existing identity. It never initializes memory,
      // starts capture or enables model disclosure.
      privacyStore = await openPrivacyOnce({ directory: join(existing.filesDir, 'droy-privacy'),
        markerPath: join(existing.filesDir, 'droy-privacy.identity.json'), initialize: false });
      return privacyStore;
    })().finally(() => { privacyInspection = undefined; });
    return privacyInspection;
  }
  const storedAuthorizationId = grant => hash(JSON.stringify([grant.url, grant.purpose, grant.configVersion]));
  async function storedAuthorizations() {
    const store = await existingAuthorizationStore();
    if (!store) return [];
    return (await store.listGrants()).map(grant => ({ id: storedAuthorizationId(grant), purpose: grant.purpose,
      label: new URL(grant.url).host, model: new URL(grant.url).host, url: grant.url, authorized: true,
      categories: grant.categories, configVersion: grant.configVersion }));
  }
  async function revokeStoredAuthorization(id, purpose) {
    const store = await existingAuthorizationStore();
    const grant = store && (await store.listGrants()).find(value => storedAuthorizationId(value) === id && value.purpose === purpose);
    if (!grant) throw failure('MEMORY_ENDPOINT_REFRESH_REQUIRED');
    // Invalidate waiters and active dispatch before committing the exact revoke.
    privacy()?.invalidate();
    return store.revokeEndpoint({ url: grant.url, purpose: grant.purpose, configVersion: grant.configVersion });
  }
  async function processingEndpoints() {
    if (!(await status()).ready) return [];
    routes.clear();
    const grants = await (await getPrivacyStore()).listGrants();
    const result = [];
    for (const provider of ctx.llm.listProviders()) {
      const models = await ctx.llm.listModels(provider.id);
      for (const model of models.slice(0, 128)) for (const purpose of PURPOSES) {
        let route;
        try { route = await privacy().prepareRoute({ provider: provider.id, model: model.id }, purpose, AbortSignal.timeout(10000)); }
        catch (error) {
          if (['PRIVACY_UNREVIEWED_ROUTE', 'PRIVACY_ROUTING_IDENTITY_UNAVAILABLE'].includes(error.code)) continue;
          throw error;
        }
        const detail = privacy().describeRoute(route), id = hash(JSON.stringify([provider.id, model.id, detail]));
        const grant = (Array.isArray(grants) ? grants : grants.grants ?? []).find(row => {
          const value = row.endpoint ?? row;
          return value.url === detail.url && value.configVersion === detail.configVersion && value.purpose === purpose;
        });
        routes.set(id + ':' + purpose, { route, config: { provider: provider.id, model: model.id }, detail });
        result.push({ id, purpose, label: `${provider.name ?? provider.id} / ${model.name ?? model.id}`, model: model.id,
          url: detail.url, authorized: !!grant && grant.revokedAt == null,
          categories: grant?.categories ?? [], configVersion: detail.configVersion });
      }
    }
    return result;
  }
  async function resolveProcessingEndpoint(id, purpose) {
    const prior = routes.get(id + ':' + purpose);
    if (!prior || !PURPOSES.includes(purpose)) throw failure('MEMORY_ENDPOINT_REFRESH_REQUIRED');
    const fresh = await privacy().prepareRoute(prior.config, purpose, AbortSignal.timeout(10000));
    if (JSON.stringify(privacy().describeRoute(fresh)) !== JSON.stringify(prior.detail)) throw failure('PRIVACY_ROUTING_CHANGED');
    return fresh;
  }
  const managementStatus = async () => ({ ready: (await status()).ready && initialized });
  async function requireManagement() {
    if (!(await managementStatus()).ready) throw failure('MEMORY_PROTECTION_NOT_READY');
    if (!archives) throw failure('MEMORY_ARCHIVE_UNAVAILABLE');
  }
  function tick() {
    if (closed || tickBusy || !active || maintenanceOwner?.held) return;
    tickBusy = true;
    tickCompletion = (async () => { try {
      if (!(await status()).ready) return;
      const settings = await getSettings();
      if (settings.enabled) {
        await maintenance?.ingestOnce();
        if (settings.automaticExtraction) await maintenance?.extractOnce();
      }
      if (clock() - lastHousekeeping >= 30 * 60 * 1000) {
        await maintenance?.maintainOnce({ housekeeping: true }); lastHousekeeping = clock();
      }
    } catch (error) { reportError(error.code ?? 'MEMORY_MAINTENANCE_FAILED'); }
    finally { tickBusy = false; } })();
    return tickCompletion;
  }
  function maintenanceLease({ operationId }) {
    ensureOpen();
    if (typeof operationId !== 'string' || !/^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$/.test(operationId)) throw failure('MEMORY_MAINTENANCE_INVALID');
    if (maintenanceOwner?.operationId === operationId) return maintenanceOwner.api;
    if (maintenanceOwner?.held) throw failure('MEMORY_MAINTENANCE_CONFLICT');
    const owner = { operationId, held: false, observedTick: undefined, observedRevision: undefined };
    const status = () => ({ scope: 'memory-producers', operationId,
      quiet: !closed && owner.held && !tickBusy && owner.observedTick === tickCompletion
        && owner.observedRevision === maintenance?.revision() && !(extraction?.pendingWork?.().length),
      producersSuspended: owner.held && !closed });
    owner.api = Object.freeze({
      async suspend() {
        ensureOpen(); owner.held = true;
        await startup; await tickCompletion;
        await extraction?.whenIdle?.(); await maintenance?.whenIdle();
        owner.observedTick = tickCompletion; owner.observedRevision = maintenance?.revision();
        return status();
      },
      resume() { owner.held = false; return status(); }, status,
    });
    maintenanceOwner = owner; return owner.api;
  }
  const memoryHost = {
    maintenanceLease, storedAuthorizations, revokeStoredAuthorization,
    executionStatus: status, managementStatus, store, authorizeUi: requireMobileSession, getSettings, saveSettings,
    async featureStop() { if (!closed) await saveSettings({ enabled: false }); },
    capture: (operation, args) => bridge.history(operation === 'configure'
      ? { operation, patch: args } : { ...args, operation }), processingEndpoints, resolveProcessingEndpoint,
    assertWritable(exec) {
      const plan = ctx.get('planMode')?.get(exec.agent);
      if (plan?.active || plan?.pending === true || exec.sandbox?.mode === 'read-only') throw failure('MEMORY_READ_ONLY');
    },
    maintenanceStatus: () => maintenance?.status() ?? { pending: 0, held: 0, stopped: true, lastCode: 'MEMORY_DISABLED' },
    async maintenanceAction(operation, { id } = {}) {
      if (!(await status()).ready || !maintenance) throw failure('MEMORY_PROTECTION_NOT_READY');
      if (operation === 'discard') return maintenance.discardExtraction(id);
      if (operation !== 'retry') throw failure('MEMORY_INVALID_REQUEST');
      if (!(await getSettings()).enabled) throw failure('MEMORY_DISABLED');
      await maintenance.retryExtraction(id); return maintenance.extractOnce({ manual: true });
    },
    async exportArchive(args, context) { await requireManagement(); return archives.exportArchive(args, context); },
    async importArchive(args, context) { await requireManagement(); return archives.importArchive(args, context); },
    async downloadArchive(req, res, id) {
      await requireManagement();
      const value = await archives.downloadArchive(id, { req, res });
      if (!value || res.writableEnded) return;
      res.writeHead(200, { 'Content-Type': value.contentType, 'Content-Length': value.contentLength,
        'Content-Disposition': `attachment; filename="${value.filename}"`, 'Cache-Control': 'no-store' });
      await pipeline(value.stream, res);
    },
  };
  const privacyHost = { nativeAccessPrompts: true, status, store: getPrivacyStore, activate, routingIdentity };
  const executionHost = { isolationRequired: false, status, policy };
  return {
    memoryHost, privacyHost, executionHost,
    start({ extractionAdapter, createArchives: archiveConstructor = archiveFactory } = {}) {
      ensureOpen();
      startup ??= (async () => {
        extraction = extractionAdapter; createArchives = archiveConstructor;
        await refresh();
        if (active) { try { await activate(); } catch (error) { reportError(error.code ?? 'MEMORY_ACTIVATION_FAILED'); } }
        if (closed) return;
        maintenance = createMemoryMaintenance({ store, existingStore: () => memory ?? null,
        executionStatus: status, settings: getSettings, privacy: { forgetSources: refs => privacy().forgetSources(refs) },
        history: { readBatch: (limit, maxBytes) => bridge.history({ operation: 'read', limit, maxBytes }),
          acknowledge: batchId => bridge.history({ operation: 'ack', batchId }) }, extraction: extraction?.extraction ?? extraction,
        aggregateSourceRef: value => `memory-epoch:${value.spaceId}:${value.disclosureEpoch}`, reportError });
        extraction?.subscribe?.(source => maintenance.enqueueExtraction(source));
        setupArchives();
        timer = setInterval(() => { void tick(); }, 30000); timer.unref?.();
        return tick();
      })();
      return startup;
    },
    tick,
    close() {
      if (shutdown) return shutdown;
      closed = true; ready = false; clearInterval(timer); privacy()?.invalidate(); stopMaintenance('MEMORY_HOST_CLOSED');
      shutdown = (async () => {
        // Await in-flight store opens before closing their workers.
        const nativeStop = active ? bridge.memory({ operation: 'host-stop' })
          .catch(error => reportError(error.code ?? 'MEMORY_NATIVE_UNAVAILABLE')) : undefined;
        const outcomes = await Promise.allSettled([nativeStop, extraction?.dispose?.()]);
        await Promise.allSettled([activation, initialization, privacyInspection, privacyOpening, startup].filter(Boolean));
        outcomes.push(...await Promise.allSettled([maintenance?.close(), archives?.close()]));
        await Promise.allSettled([tickCompletion].filter(Boolean));
        outcomes.push(...await Promise.allSettled([memory?.close(), privacyStore?.close()]));
        const failed = outcomes.find(value => value.status === 'rejected');
        if (failed) throw failed.reason;
      })();
      return shutdown;
    },
  };
}

export function apply(ctx) {
  let lifecycle;
  const reportError = code => ctx.logger.warn(code);
  const service = createMobileMemoryHost({ ctx, bridge: createBridge({ getLocale: () => resolveMobileLocale(ctx) }), reportError,
    featureAvailable: () => lifecycle?.available() === true });
  lifecycle = createMemoryFeatureLifecycle({ ctx, host: service.memoryHost, reportError });
  ctx.provide('droyMemoryHost', service.memoryHost);
  ctx.provide('droyPrivacyHost', service.privacyHost);
  ctx.provide('droyExecutionHost', service.executionHost);
  ctx.inject(['llm', 'droyPrivacy', 'droyExecution', 'sessions', 'sessionPersistence', 'agentDefaultModel'], child => {
    void lifecycle.start(async () => {
      const { createDshMemoryExtraction } = await import('./memory-extraction.mjs');
      const { createMemoryArchives } = await import('./memory-archive.mjs');
      // Cordis grants the extraction dependencies to this injected child.
      const extraction = createDshMemoryExtraction({ ctx: child, privacy: child.droyPrivacy, settings: service.memoryHost.getSettings });
      await service.start({ extractionAdapter: extraction, createArchives: createMemoryArchives });
    });
  });
  ctx.effect(() => () => { lifecycle.close(); return service.close(); });
}
import { resolveMobileLocale } from './locale.mjs';
