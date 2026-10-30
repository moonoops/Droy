import { createHash } from 'node:crypto';
import { lstat, open, readFile, realpath, rename, unlink } from 'node:fs/promises';
import { dirname, isAbsolute, join, relative, resolve } from 'node:path';
import { CompanionStore } from './store.mjs';
import { CompanionMemorySpaces } from './memory-spaces.mjs';
import { createCompanionStoreMaintenance } from './maintenance-store.mjs';
import { createOfflineJsonlProvider, createSessionMaintenance } from './maintenance-sessions.mjs';
import { createTrustedRootAlias } from './maintenance-root-alias.mjs';

const object = value => value !== null && typeof value === 'object' && !Array.isArray(value);
const id = value => typeof value === 'string' && /^[A-Za-z0-9_-][A-Za-z0-9_.:-]{0,159}$/u.test(value) && !['.', '..'].includes(value);
const canonical = value => Array.isArray(value) ? '[' + value.map(canonical).join(',') + ']' : object(value)
  ? '{' + Object.keys(value).sort().map(key => JSON.stringify(key) + ':' + canonical(value[key])).join(',') + '}' : JSON.stringify(value);
const digest = value => createHash('sha256').update(canonical(value)).digest('hex');
const inside = (root, path) => path === root || !relative(root, path).startsWith('..') && !isAbsolute(relative(root, path));
const fail = code => { throw Object.assign(new Error('COMPANION_MEMORY_SESSION_' + code), { code: 'COMPANION_MEMORY_SESSION_' + code }); };

function selectSessions(entries, claimedIds, boundOwners, companionId, companionRoot, mapPath = path => resolve(path)) {
  const claims = new Set(claimedIds), rows = new Map(), selected = new Set();
  for (const entry of entries) {
    const header = entry.header;
    if (!object(header) || !id(header.id) || rows.has(header.id) || typeof header.cwd !== 'string' || !isAbsolute(header.cwd)) fail('AUDIT_INVALID');
    rows.set(header.id, header);
    if (claims.has(header.id)) {
      if (boundOwners.get(header.id) !== undefined && boundOwners.get(header.id) !== companionId) fail('SCOPE_CONFLICT');
      if (!inside(companionRoot, mapPath(header.cwd))) fail('SCOPE_UNPROVEN');
      selected.add(header.id);
    }
  }
  let changed;
  do {
    changed = false;
    for (const header of rows.values()) {
      if (!selected.has(header.id) && header.parentSession && selected.has(header.parentSession)) {
        if (boundOwners.get(header.id) !== undefined && boundOwners.get(header.id) !== companionId
          || !inside(companionRoot, mapPath(header.cwd))) fail('RELATED_SCOPE_CONFLICT');
        selected.add(header.id); changed = true;
      }
    }
  } while (changed);
  // A private cwd without a product claim could be an incomplete old binding.
  // Refuse instead of treating arbitrary workspace placement as ownership.
  for (const header of rows.values()) if (!selected.has(header.id) && inside(companionRoot, mapPath(header.cwd))) fail('SCOPE_UNPROVEN');
  return [...selected].sort();
}

async function productScope(store, companionId) {
  const profiles = await store.profiles(), profile = profiles.find(item => item.id === companionId), owners = new Map();
  let claims = [];
  for (const item of profiles) {
    const owned = item.chatSessionId ? [item.chatSessionId] : [];
    for (const binding of await store.listSessions(item.id)) {
      if (binding.companionId !== item.id) fail('SCOPE_CONFLICT');
      owned.push(binding.sessionId);
    }
    const state = await store.getState(item.id);
    owned.push(...(state.invalidSessionIds ?? []), ...Object.keys(state.sessionSources ?? {}));
    let before;
    do {
      const page = await store.listMessages(item.id, { limit: 100, ...(before ? { before } : {}) });
      for (const message of page.messages) if (message.task?.sessionId) owned.push(message.task.sessionId);
      before = page.hasMore ? page.nextBefore : null;
    } while (before);
    for (const sessionId of owned) {
      if (!id(sessionId) || owners.has(sessionId) && owners.get(sessionId) !== item.id) fail('SCOPE_CONFLICT');
      owners.set(sessionId, item.id);
    }
    if (item.id === companionId) claims.push(...owned);
  }
  if (claims.some(value => !id(value))) fail('SCOPE_UNPROVEN');
  return { profile, owners, claims: [...new Set(claims)] };
}

/** The public DSH persistence contract is append-only, and its live list is
 * not a strict historical audit. Completion always requires offline exclusion. */
export async function purgeCompanionMemorySessions({ ctx, store, directory, companionId, signal } = {}) {
  if (!id(companionId) || !isAbsolute(directory ?? '')) fail('INVALID_INPUT');
  signal?.throwIfAborted();
  const persistence = typeof ctx?.get === 'function' ? ctx.get('sessionPersistence') : ctx?.sessionPersistence;
  if (typeof persistence?.list !== 'function') fail('AUDIT_REQUIRED');
  const scope = await productScope(store, companionId);
  const selected = selectSessions(await persistence.list({ signal }), scope.claims, scope.owners, companionId, join(directory, companionId));
  const agents = typeof ctx?.get === 'function' ? ctx.get('agents') : ctx?.agents;
  const live = selected.map(sessionId => agents?.get(sessionId)).filter(Boolean);
  if (live.some(agent => typeof agent.cancel !== 'function' || typeof agent.whenIdle !== 'function')) fail('DRAIN_UNAVAILABLE');
  for (const agent of live) agent.cancel({ kind: 'user' });
  const timeout = AbortSignal.timeout(15_000), combined = signal ? AbortSignal.any([signal, timeout]) : timeout;
  await new Promise((resolveWait, reject) => {
    const aborted = () => { combined.removeEventListener('abort', aborted); reject(Object.assign(new Error('COMPANION_MEMORY_SESSION_DRAIN_PENDING'), { code: 'COMPANION_MEMORY_SESSION_DRAIN_PENDING' })); };
    combined.addEventListener('abort', aborted, { once: true });
    Promise.all(live.map(agent => agent.whenIdle())).then(() => { combined.removeEventListener('abort', aborted); resolveWait(); },
      error => { combined.removeEventListener('abort', aborted); reject(error); });
    if (combined.aborted) aborted();
  });
  signal?.throwIfAborted();
  throw Object.assign(new Error('COMPANION_MEMORY_SESSION_PURGE_REQUIRES_OFFLINE'), {
    code: 'COMPANION_MEMORY_SESSION_PURGE_REQUIRES_OFFLINE', pendingSessionIds: selected,
  });
}

async function safe(path, optional = false) {
  for (let cursor = resolve(path); ; cursor = dirname(cursor)) {
    let info;
    try { info = await lstat(cursor); }
    catch (error) { if (error.code === 'ENOENT' && (cursor !== resolve(path) || optional)) { if (cursor === dirname(cursor)) break; continue; } throw error; }
    if (info.isSymbolicLink() || cursor !== resolve(path) && !info.isDirectory()
      || cursor === resolve(path) && !info.isDirectory() && (!info.isFile() || info.nlink !== 1)) fail('LINKED_PATH');
    if (cursor === dirname(cursor)) break;
  }
}
async function readOptional(path) { try { await safe(path); return await readFile(path, 'utf8'); } catch (error) { if (error.code === 'ENOENT') return null; throw error; } }
async function atomic(path, value) {
  await safe(path, true); const temporary = path + '.writing'; await safe(temporary, true);
  if (await readOptional(temporary) !== null) await unlink(temporary);
  const file = await open(temporary, 'wx', 0o600);
  try { await file.writeFile(value); await file.sync(); } finally { await file.close(); }
  await rename(temporary, path); const directory = await open(dirname(path), 'r'); try { await directory.sync(); } finally { await directory.close(); }
}

async function retireWorkspaceReferences(home, sessionIds, guard) {
  const path = join(home, 'storages', 'workspace.json'), source = await readOptional(path);
  if (source === null) return;
  let doc; try { doc = JSON.parse(source); } catch { fail('WORKSPACE_INVALID'); }
  if (!object(doc) || doc.unit?.name !== 'workspace' || doc.unit?.version !== 2 || !object(doc.global) || !object(doc.tables?.workspaces)
    || Object.keys(doc).some(key => !['unit','global','tables'].includes(key)) || Object.keys(doc.tables).some(key => key !== 'workspaces')
    || Object.keys(doc.unit).some(key => !['name','version'].includes(key)) || doc.global.initialized !== true
    || Object.keys(doc.global).some(key => !['initialized','defaultWorkspaceId','workspaceIds','archivedSessionIds','pinnedSessionIds'].includes(key))
    || !Array.isArray(doc.global.workspaceIds) || new Set(doc.global.workspaceIds).size !== doc.global.workspaceIds.length
    || doc.global.workspaceIds.some(value => !id(value))
    || Object.keys(doc.tables.workspaces).sort().join('\n') !== [...doc.global.workspaceIds].sort().join('\n')) fail('WORKSPACE_INVALID');
  const selected = new Set(sessionIds);
  for (const row of Object.values(doc.tables.workspaces)) {
    if (!object(row) || Object.keys(row).some(key => !['path','title','sessionIds','createdAt','updatedAt'].includes(key))
      || !isAbsolute(row.path ?? '') || typeof row.title !== 'string' || typeof row.createdAt !== 'string' || typeof row.updatedAt !== 'string'
      || !Array.isArray(row.sessionIds) || row.sessionIds.some(value => !id(value))) fail('WORKSPACE_INVALID');
    row.sessionIds = row.sessionIds.filter(value => !selected.has(value));
  }
  for (const key of ['archivedSessionIds','pinnedSessionIds']) if (doc.global[key] !== undefined) {
    if (!Array.isArray(doc.global[key]) || doc.global[key].some(value => !id(value))) fail('WORKSPACE_INVALID');
    doc.global[key] = doc.global[key].filter(value => !selected.has(value));
  }
  await guard();
  if (await readOptional(path) !== source) fail('WORKSPACE_CHANGED');
  const output = JSON.stringify(doc) + '\n';
  if (output !== source) await atomic(path, output);
}

/** Android owns process exclusion and answers every live authority challenge.
 * The journal contains identities and digests, never recalled text or events. */
export async function runOfflineCompanionMemorySessionPurge({ home, operationId, companionId, memorySpaceId,
  admissionEpoch, authority, trustedRootAlias, provider = createOfflineJsonlProvider(), compression = 'none', onCheckpoint = () => {} } = {}) {
  if (!isAbsolute(home ?? '') || ![operationId, companionId, memorySpaceId, admissionEpoch].every(id)
    || typeof authority?.assertHeld !== 'function' || !['none','zstd'].includes(compression)) fail('INVALID_INPUT');
  home = resolve(home); await safe(home);
  if (await realpath(home) !== home) fail('LINKED_PATH');
  const aliases = await createTrustedRootAlias(home, trustedRootAlias, fail);
  const identity = { operationId, companionId, memorySpaceId, admissionEpoch }, root = join(home, 'droy-companions');
  const sessionRoot = join(home, 'sessions'), internalOperation = 'memory-' + digest(operationId);
  const journalPath = join(home, '.companion-memory-purge-' + digest(operationId) + '.json');
  async function guard() {
    const request = { root: home, scope: 'companion-memory-purge', ...identity };
    let proof; try { proof = await authority.assertHeld(request); } catch { fail('AUTHORITY_LOST'); }
    if (!object(proof) || proof.hostStopped !== true || proof.admissionsClosed !== true
      || Object.entries(request).some(([key, value]) => proof[key] !== value)) fail('AUTHORITY_LOST');
  }
  await guard(); await safe(root);
  const spaces = await CompanionMemorySpaces.open({ directory: root });
  let space = await spaces.get(memorySpaceId);
  if (!space || space.companionId !== companionId || !['purging','purged'].includes(space.state) || space.purgeOperation !== operationId) fail('GRANT_REQUIRED');
  let journal;
  const prior = await readOptional(journalPath);
  if (prior !== null) {
    let value; try { value = JSON.parse(prior); } catch { fail('JOURNAL_INVALID'); }
    const { checksum, ...data } = value;
    if (checksum !== digest(data) || data.version !== 1 || data.home !== home || data.compression !== compression
      || Object.entries(identity).some(([key, value]) => data[key] !== value)
      || digest(data.trustedRootAlias) !== digest(aliases.value) || !Array.isArray(data.sessionIds) || data.sessionIds.some(value => !id(value))
      || new Set(data.sessionIds).size !== data.sessionIds.length || !Array.isArray(data.claimedIds) || data.claimedIds.some(value => !id(value))
      || !['planned','sessions','bindings','complete'].includes(data.phase)) fail('JOURNAL_INVALID');
    journal = data;
  } else if (space.state === 'purged') fail('JOURNAL_REQUIRED');
  async function checkpoint(phase, extra = {}) {
    await guard(); journal = { ...journal, ...extra, phase }; await atomic(journalPath, JSON.stringify({ ...journal, checksum: digest(journal) }) + '\n');
    await onCheckpoint(phase);
  }
  const subauthority = { async assertHeld(request) {
    await guard();
    if (request.operationId !== internalOperation || !['sessions','companion-store'].includes(request.scope)
      || request.root !== (request.scope === 'sessions' ? sessionRoot : root)) fail('AUTHORITY_LOST');
    return { ...request, admissionEpoch, hostStopped: true, admissionsClosed: true };
  } };
  const store = await CompanionStore.open({ directory: root });
  try {
    const scope = await productScope(store, companionId);
    const audit = async () => {
      await guard(); const opened = await provider({ root: sessionRoot, compression });
      try { const value = await opened.audit(); if (value.complete !== true || !Array.isArray(value.entries)) fail('AUDIT_REQUIRED'); return value; }
      finally { await opened.close(); }
    };
    const storeAudit = async () => (await createCompanionStoreMaintenance({ root, operationId: internalOperation, companionId, authority: subauthority })).prepare();
    if (!journal) {
      const checkedStore = scope.profile ? await storeAudit() : null;
      const claims = checkedStore ? checkedStore.manifest.sessionClaims.map(item => item.sessionId) : scope.claims;
      const inspected = await audit();
      const mapPath = path => {
        const value = resolve(path), alias = aliases.value?.aliasRoot;
        return alias && inside(alias, value) ? join(home, relative(alias, value)) : value;
      };
      const sessionIds = selectSessions(inspected.entries, claims, scope.owners, companionId, join(root, companionId), mapPath);
      journal = { version: 1, home, ...identity, compression, trustedRootAlias: aliases.value, active: !!scope.profile,
        claimedIds: [...new Set([...claims, ...sessionIds])].sort(), sessionIds, retainedStoreDigest: checkedStore?.retainedDigest ?? null,
        sourceCaptureBoundary: space.sourceCaptureBoundary, phase: 'planned' };
      await checkpoint('planned');
    }
    if (journal.active !== !!scope.profile) fail('PRODUCT_STATE_CHANGED');
    if (journal.phase === 'planned') {
      if (journal.sessionIds.length) {
        const job = await createSessionMaintenance({ root: sessionRoot, operationId: internalOperation, deleteSessionIds: journal.sessionIds,
          authority: subauthority, provider, compression, onCheckpoint: phase => onCheckpoint('sessions:' + phase) });
        await job.prepare(); const result = await job.commit();
        if (result.sessionPhase !== 'complete' || result.oldBackupAbsent !== true) fail('PURGE_INCOMPLETE');
      }
      await checkpoint('sessions');
    }
    if (journal.phase === 'sessions') {
      await guard();
      if (scope.profile) await store.retireMemorySessions(companionId, journal.claimedIds, { sourceCaptureBoundary: journal.sourceCaptureBoundary ?? 'seq:0' });
      await retireWorkspaceReferences(home, journal.sessionIds, guard);
      if (scope.profile && (await storeAudit()).retainedDigest !== journal.retainedStoreDigest) fail('RETAINED_PRODUCT_CHANGED');
      await checkpoint('bindings');
    }
    if (journal.phase === 'bindings') {
      if ((await audit()).entries.some(item => journal.sessionIds.includes(item.id))) fail('PURGE_INCOMPLETE');
      await guard();
      await spaces.finishPurge(memorySpaceId, { operationId, reactivate: journal.active && space.purgePreviousState === 'active',
        sourceCaptureBoundary: journal.sourceCaptureBoundary });
      await checkpoint('complete');
    }
    space = await spaces.get(memorySpaceId);
    if (space?.state !== 'purged' || space.purgeOperation !== operationId
      || (await audit()).entries.some(item => journal.sessionIds.includes(item.id))) fail('PURGE_INCOMPLETE');
    return { version: 1, scope: 'companion-memory-purge', ...identity, completed: true, removedSessionIds: journal.sessionIds,
      replacementSpaceId: space.replacementSpaceId ?? null };
  } finally { await store.close(); }
}
