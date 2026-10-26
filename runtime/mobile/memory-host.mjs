import { createHash } from 'node:crypto';
import { join } from 'node:path';
import { pipeline } from 'node:stream/promises';
import { MemoryStore } from '../memory/store.mjs';
import { PrivacyStore } from '../privacy/store.mjs';
import { createMemoryMaintenance } from '../memory/maintenance.mjs';
import { RootExecutor } from '../execution/launcher.mjs';
import { createExecutionProxy } from '../execution/proxy.mjs';
import { createBridge } from './bridge.mjs';
import { requireMobileSession } from './auth.mjs';
import { createMemoryFeatureLifecycle } from './memory-lifecycle.mjs';

export const name = 'droy-memory-host';
export const inject = ['llm', 'webServer', 'tools'];
const hash = value => createHash('sha256').update(value).digest('hex');
const failure = code => Object.assign(new Error(code), { code });
const PURPOSES = ['conversation', 'session-title', 'compaction', 'extraction'];
// These tools either use the isolated FS/subprocess services, or fixed product
// operations. Host-code loaders, process-memory access and arbitrary native
// compute are deliberately absent: their old app-UID paths are not isolated.
const REVIEWED_TOOLS = new Set([
  'droy_memory_query', 'droy_memory_get', 'droy_memory_record', 'droy_memory_update', 'droy_memory_forget',
  'glob', 'grep', 'working_directory', 'job_list', 'job_output', 'job_kill',
  'ask_user_question', 'exit_plan_mode', 'todo_write', 'droy_load_tools',
  'android_capabilities', 'android_find_apps', 'android_open_app', 'android_observe', 'android_screenshot',
  'android_sequence', 'android_tap', 'android_input_text', 'android_swipe', 'android_gesture', 'android_node_action',
  'android_global_action', 'android_back', 'android_wait_for', 'android_verification', 'android_system_info',
  'android_open_settings', 'android_file_grants', 'android_file_list', 'android_file_read', 'android_file_export',
  'android_settings_get', 'android_settings_set', 'android_settings_restore', 'android_app_info',
  'android_app_exit_info', 'android_battery_usage', 'android_network_usage', 'android_privacy_usage',
  'android_usage_query', 'android_calendar_query', 'android_contacts_query',
  'android_audio_start', 'android_audio_status', 'android_audio_stop', 'audio_transcribe', 'audio_transcript_read',
  'audio_transcription_stop', 'droy_voice', 'droy_pet_progress',
]);
const CLEAN_NETWORK_TOOLS = new Set(['web_search', 'web_fetch']);
const PRIVATE_LOCAL_TOOLS = new Set([
  'droy_memory_query', 'droy_memory_get', 'droy_memory_record', 'droy_memory_update', 'droy_memory_forget',
  'glob', 'grep', 'working_directory', 'job_list', 'job_output', 'job_kill',
  'ask_user_question', 'exit_plan_mode', 'todo_write', 'droy_load_tools',
  'android_capabilities', 'android_find_apps', 'android_observe', 'android_screenshot', 'android_system_info',
  'android_file_grants', 'android_file_list', 'android_file_read', 'android_settings_get', 'android_app_info',
  'android_app_exit_info', 'android_battery_usage', 'android_network_usage', 'android_privacy_usage',
  'android_usage_query', 'android_calendar_query', 'android_contacts_query', 'android_wait_for',
  'android_audio_status', 'android_audio_stop', 'audio_transcript_read', 'audio_transcription_stop',
]);
function hostPaths(value) {
  if (!value || !/^\/data\/user\/\d+\/io\.droyagent\.app\/files$/.test(value.filesDir)
      || !value.nativeDir?.startsWith('/data/app/') || !Number.isSafeInteger(value.appUid) || value.appUid < 10000) {
    throw failure('MEMORY_NATIVE_HOST_INVALID');
  }
  return { filesDir: value.filesDir, nativeDir: value.nativeDir, appUid: value.appUid };
}

/** Android host adapter. The memory package itself has no Android dependency. */
export function createMobileMemoryHost({ ctx, bridge, environment = process.env,
  openMemory = options => MemoryStore.open(options), openPrivacy = options => PrivacyStore.open(options),
  executorFactory = options => new RootExecutor(options), qualify = qualifyExecutor,
  paths = hostPaths, clock = Date.now, reportError = () => {}, archiveFactory, featureAvailable = () => false } = {}) {
  let snapshot, host, executor, memory, privacyStore, initialization, activation, closed = false;
  let initialized = false, ready = false, active = environment.DROY_MEMORY_PROTECTION_REQUIRED === '1', reason = 'MEMORY_DISABLED';
  let maintenance, extraction, archives, lastHousekeeping = 0, tickBusy = false, timer;
  let startup, shutdown, tickCompletion, createArchives = archiveFactory;
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
    if (closed) return { active, ready: false, reason: 'MEMORY_HOST_CLOSED', rootAuthorized: false, rootCheckedAt: 0 };
    try { await refresh(); }
    catch (error) {
      ready = false; reason = error.code ?? 'MEMORY_NATIVE_UNAVAILABLE'; privacy()?.invalidate();
      stopMaintenance(reason);
      // Once activated, connection failure can never restore the local provider.
      if (!snapshot) throw error;
    }
    if (ready && (!privacy() || !boundary()?.installed?.())) {
      ready = false; reason = 'MEMORY_REQUIRED_PLUGIN_MISSING'; privacy()?.invalidate();
      stopMaintenance(reason);
      try { await bridge.memory({ operation: 'activation.invalidate' }); }
      catch { /* Missing native acknowledgement cannot restore readiness. */ }
    }
    return { active, ready: ready && initialized && !closed,
      reason: ready && initialized ? null : reason,
      rootAuthorized: snapshot?.root?.authorized === true, rootCheckedAt: snapshot?.root?.checkedAt ?? 0 };
  }
  function policy() {
    if (!host) throw failure('MEMORY_HOST_NOT_READY');
    return { workspaceRoot: join(host.filesDir, 'dsh-home', 'workspace'), nodePath: join(host.nativeDir, 'libdroy_node.so'),
      pathAliases: host.filesDir === '/data/user/0/io.droyagent.app/files'
        ? [{ from: '/data/data/io.droyagent.app', to: '/data/user/0/io.droyagent.app' }] : [],
      workspaceRoots: [join(host.filesDir, 'dsh-home', 'workspace'), join(host.filesDir, 'workspace')],
      readOnlyRoots: [join(host.filesDir, 'runtime'), host.nativeDir],
      privateRoots: ['droy-memory', 'droy-privacy', 'droy-history-spool', 'droy-execution', '.dsh', 'dsh-home']
        .map(name => join(host.filesDir, name)) };
  }
  async function initializeStores() {
    ensureOpen();
    if (initialized) return;
    initialization ??= (async () => {
      const permission = await bridge.memory({ operation: 'initialization.prepare' });
      ensureOpen();
      const trusted = paths(permission.host);
      if (trusted.filesDir !== host.filesDir || typeof permission.initialize !== 'boolean') throw failure('MEMORY_INITIALIZATION_DENIED');
      privacyStore = await openPrivacy({ directory: join(host.filesDir, 'droy-privacy'),
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
      ready = false; reason = 'MEMORY_EXECUTION_QUALIFYING';
      if (!privacy() || !boundary()?.installed?.()) throw failure('MEMORY_REQUIRED_PLUGIN_MISSING');
      const prepared = await bridge.memory({ operation: 'activation.prepare' });
      ensureOpen();
      if (prepared.restartRequired) {
        active = true; reason = 'MEMORY_RESTART_REQUIRED';
        throw failure(reason);
      }
      host = paths(prepared.host);
      executor ??= executorFactory({ launcherPath: join(host.nativeDir, 'libdroy_exec.so'), filesDir: host.filesDir,
        uid: host.appUid, proxyFactory: createExecutionProxy });
      const report = await qualify(executor, host);
      ensureOpen();
      // Native stopped legacy app-UID children before this engine started.
      // New calls await the durable active state and cannot use local fallback.
      // Waiting on those calls here would deadlock their store-ready barrier.
      if (!boundary().installed()) throw failure('MEMORY_REQUIRED_PLUGIN_MISSING');
      await bridge.memory({ operation: 'protection-activate', challenge: prepared.challenge,
        runtimeEpoch: prepared.runtimeEpoch,
        qualificationId: hash(JSON.stringify({ native: prepared.nativeQualificationId, report })) });
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
      if (reason !== 'MEMORY_RESTART_REQUIRED') {
        try { await bridge.memory({ operation: 'activation.invalidate' }); }
        catch { /* The local unavailable state is already authoritative. */ }
      }
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
    if (closed || tickBusy || !active) return;
    tickBusy = true;
    tickCompletion = (async () => { try {
      await bridge.memory({ operation: 'root-check' });
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
  const memoryHost = {
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
  const privacyHost = { status, store: getPrivacyStore, activate, routingIdentity };
  const executionHost = { status, policy, get executor() {
    if (!executor) throw failure('MEMORY_EXECUTION_NOT_READY'); return executor;
  }, async authorizeTool(exec, state) {
    if (!REVIEWED_TOOLS.has(exec.name) && !CLEAN_NETWORK_TOOLS.has(exec.name)) return false;
    if (state.state === 'known-clean' || PRIVATE_LOCAL_TOOLS.has(exec.name)) return true;
    // Processing trust is not permission to publish the retrieved content into
    // another app/site. Bind this one-time decision to the actual dispatch.
    const questions = ctx.get('userQuestions');
    if (!questions || !exec.callId || !exec.agent || !exec.arguments) return false;
    const detail = JSON.stringify(exec.arguments);
    if (detail.length > 16000) throw failure('MEMORY_DISCLOSURE_REQUEST_TOO_LARGE');
    const id = 'private-action-' + hash(`${exec.agent.session.id}:${exec.callId}:${exec.name}:${detail}`).slice(0, 32);
    const english = resolveMobileLocale(ctx) === 'en';
    const allow = english ? 'Allow this operation' : '允许本次操作';
    const answer = await questions.ask({ agent: exec.agent, signal: exec.signal, questions: [{ id,
      question: `${english ? 'This operation may send personal memory to another app or website. Allow this operation?' : '本次操作可能把个人记忆带到其他应用或网站。允许这一项操作？'}\n${exec.name}\n${detail}`,
      options: [{ label: english ? 'Cancel' : '取消' }, { label: allow }] }] });
    exec.signal?.throwIfAborted();
    const selected = answer.answers?.find(item => item.id === id);
    return !selected?.custom && selected?.selected?.length === 1 && selected.selected[0] === allow;
  } };
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
        // Cancel a qualification first, then wait for in-flight store opens so
        // no worker can be created after the teardown has already returned.
        const nativeStop = active ? bridge.memory({ operation: 'host-stop' })
          .catch(error => reportError(error.code ?? 'MEMORY_NATIVE_UNAVAILABLE')) : undefined;
        const outcomes = await Promise.allSettled([nativeStop, extraction?.dispose?.(), executor?.close()]);
        await Promise.allSettled([activation, initialization, startup].filter(Boolean));
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

export async function qualifyExecutor(executor, host) {
  const userBase = Math.floor(host.appUid / 100000) * 100000;
  const script = "const fs=require('fs');const uid=process.getuid(),gid=process.getgid();"
    + `if(uid<${userBase + 80000}||uid>${userBase + 80007}||uid===${host.appUid}||process.geteuid()!==uid||gid!==${host.appUid})process.exit(71);`
    + "let blocked=false;try{fs.readFileSync(" + JSON.stringify(join(host.filesDir, 'droy-privacy.identity.json')) + ")}catch{blocked=true}"
    + "if(!blocked||process.env.DROY_BRIDGE_TOKEN||process.env.DROY_WEB_TOKEN)process.exit(72);"
    + `if(fs.existsSync('/proc/uptime')||fs.existsSync('/proc/${process.pid}'))process.exit(73);`
    + "process.stdout.write(JSON.stringify({marker:'DROY_HOST_QUALIFIED_V2',uid,gid,pid:process.pid}));";
  const handle = executor.spawn({ argv: [join(host.nativeDir, 'libdroy_node.so'), '-e', script],
    cwd: join(host.filesDir, 'workspace'), workspaceId: '0'.repeat(64), env: {}, timeoutMs: 15000,
    stdio: { stdin: 'ignore', stdout: { maxBytes: 128 }, stderr: { maxBytes: 1024 } } });
  const exit = await handle.done;
  if (exit.exitCode !== 0 || await handle.waitForExit() !== true) throw failure('MEMORY_HOST_QUALIFICATION_FAILED');
  const ready = await handle.ready, output = handle.collected.stdout.readFrom(0);
  let observation; try { observation = JSON.parse(output.text); } catch { throw failure('MEMORY_HOST_QUALIFICATION_FAILED'); }
  if (output.lossy || observation.marker !== 'DROY_HOST_QUALIFIED_V2' || observation.uid !== ready.uid
    || observation.gid !== host.appUid || observation.pid !== ready.workloadPid
    || ready.processIsolation !== 'dedicated-uid' || ready.procIsolation !== 'private-hidepid-subset'
    || ready.uidLease !== true || ready.descendantsManaged !== true) throw failure('MEMORY_HOST_QUALIFICATION_FAILED');
  return { protocol: 1, node: true, privatePathDenied: true, credentialsAbsent: true,
    processIsolation: 'dedicated-uid', procIsolation: 'private-hidepid-subset', descendantsReaped: true };
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
