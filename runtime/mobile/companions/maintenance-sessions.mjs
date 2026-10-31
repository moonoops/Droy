import { createHash } from 'node:crypto';
import { lstat, mkdir, open, readFile, realpath, rename, rm, rmdir, statfs } from 'node:fs/promises';
import { basename, dirname, isAbsolute, join, resolve } from 'node:path';
import { isDeepStrictEqual } from 'node:util';
import { Context } from '@deepseek-ai/cordis';
import * as Jsonl from '@deepseek-ai/dsh-session-persistence-jsonl';

const SCHEMA = 1;
const phases = ['preparing', 'prepared', 'validated', 'moving-old', 'old-moved', 'installing', 'installed', 'removing-backup', 'complete'];
const pending = ['companion-store', 'companion-memory', 'workspaces', 'schedules', 'attachments', 'android'];
export class CompanionMaintenanceError extends Error {
  constructor(code, cause) { super(code, cause ? { cause } : undefined); this.code = code; }
}
const fail = code => { throw new CompanionMaintenanceError(code); };
const object = value => value !== null && typeof value === 'object' && !Array.isArray(value);
function canonical(value) {
  if (Array.isArray(value)) return '[' + value.map(canonical).join(',') + ']';
  if (object(value)) return '{' + Object.keys(value).sort().map(key => JSON.stringify(key) + ':' + canonical(value[key])).join(',') + '}';
  return JSON.stringify(value);
}
const digest = value => createHash('sha256').update(canonical(value)).digest('hex');
const id = value => typeof value === 'string' && /^[A-Za-z0-9_-]{1,80}$/u.test(value);
async function info(path) {
  try { const value = await lstat(path, { bigint: true }); if (value.isSymbolicLink()) fail('MAINTENANCE_LINKED_PATH'); return value; }
  catch (error) { if (error.code === 'ENOENT') return null; throw error; }
}
async function syncDirectory(path) { const handle = await open(path, 'r'); try { await handle.sync(); } finally { await handle.close(); } }
async function publish(path, value) {
  const temporary = path + '.writing';
  const previous = await info(temporary); if (previous && (!previous.isFile() || previous.nlink !== 1n)) fail('MAINTENANCE_JOURNAL_INVALID');
  // Only the operation's exclusively owned journal temporary can be replaced on recovery.
  const handle = await open(temporary, 'w', 0o600);
  const payload = { ...value }; delete payload.checksum; payload.checksum = digest(payload);
  try { await handle.writeFile(JSON.stringify(payload) + '\n'); await handle.sync(); } finally { await handle.close(); }
  await rename(temporary, path); await syncDirectory(dirname(path));
}
function identity(value) { return value ? { dev: String(value.dev), ino: String(value.ino) } : null; }
function sameIdentity(value, expected) { return value && expected && String(value.dev) === expected.dev && String(value.ino) === expected.ino; }

/** Only storage is mounted: no Agent, schedule timer, Web server or product plugin starts. */
export function createOfflineJsonlProvider(module = Jsonl) {
  return async ({ root, compression, auditOptions }) => {
    if (typeof module.auditOfflineSessionCorpus !== 'function') fail('MAINTENANCE_PROVIDER_AUDIT_REQUIRED');
    const ctx = new Context();
    try { await ctx.plugin(module, { root, compression }); }
    catch (error) { await ctx.fiber.dispose(); throw error; }
    return { store: ctx.sessionPersistence, audit: () => module.auditOfflineSessionCorpus(ctx.sessionPersistence, auditOptions), close: () => ctx.fiber.dispose() };
  };
}

/**
 * Session-only building block. The trusted Android/offline assembly supplies the
 * live lease capability; no CLI or HTTP input can mint one in this module.
 */
export async function createSessionMaintenance({ root, operationId, deleteSessionIds, authority,
  provider = createOfflineJsonlProvider(), compression = 'zstd', auditOptions, reserveBytes = 16 * 1024 * 1024,
  onCheckpoint = () => {} } = {}) {
  if (typeof root !== 'string' || !isAbsolute(root) || !id(operationId) || !Array.isArray(deleteSessionIds) || !deleteSessionIds.length
    || deleteSessionIds.some(value => typeof value !== 'string' || !value || value.length > 1024) || new Set(deleteSessionIds).size !== deleteSessionIds.length
    || !['none', 'zstd'].includes(compression) || !Number.isSafeInteger(reserveBytes) || reserveBytes < 0) fail('MAINTENANCE_INVALID_INPUT');
  if (!authority || typeof authority.assertHeld !== 'function') fail('MAINTENANCE_AUTHORITY_REQUIRED');
  const parent = await realpath(dirname(resolve(root))), source = join(parent, basename(root));
  if ((await info(source))?.isDirectory() === false) fail('MAINTENANCE_INVALID_ROOT');
  const prefix = '.' + basename(source) + '.droy-maintenance-' + operationId;
  const paths = { source, staging: join(parent, prefix + '.staging'), backup: join(parent, prefix + '.backup'), journal: join(parent, prefix + '.json') };
  const removed = [...deleteSessionIds].sort(), inputDigest = digest({ source, operationId, removed, compression });
  let stamp;
  async function guard() {
    let value;
    try { value = await authority.assertHeld({ root: source, operationId, scope: 'sessions' }); }
    catch (error) { throw new CompanionMaintenanceError('MAINTENANCE_AUTHORITY_LOST', error); }
    if (!object(value) || value.operationId !== operationId || value.root !== source || !id(value.admissionEpoch)
      || value.hostStopped !== true || value.admissionsClosed !== true) fail('MAINTENANCE_AUTHORITY_LOST');
    const next = digest({ root: source, operationId, admissionEpoch: value.admissionEpoch });
    if (stamp && stamp !== next) fail('MAINTENANCE_AUTHORITY_CHANGED'); stamp = next;
  }
  await guard();
  let journal;
  async function load() {
    const file = await info(paths.journal); if (!file) return null;
    if (!file.isFile() || file.nlink !== 1n || file.size > 16 * 1024 * 1024) fail('MAINTENANCE_JOURNAL_INVALID');
    let value; try { value = JSON.parse(await readFile(paths.journal, 'utf8')); } catch { fail('MAINTENANCE_JOURNAL_INVALID'); }
    if (!object(value)) fail('MAINTENANCE_JOURNAL_INVALID');
    const { checksum, ...content } = value;
    if (checksum !== digest(content)) fail('MAINTENANCE_JOURNAL_INVALID');
    if (value.version !== SCHEMA || value.inputDigest !== inputDigest || value.authorityStamp !== stamp || !phases.includes(value.phase)
      || !Array.isArray(value.retained) || value.retained.some(row => !object(row) || typeof row.id !== 'string' || !/^[a-f0-9]{64}$/u.test(row.logicalDigest))) fail('MAINTENANCE_JOURNAL_INVALID');
    if (!/^[a-f0-9]{64}$/u.test(value.sourceFingerprint) || new Set(value.retained.map(row => row.id)).size !== value.retained.length
      || value.retained.some(row => removed.includes(row.id)) || phases.indexOf(value.phase) >= phases.indexOf('prepared') && value.retainedDigest !== digest(value.retained)) fail('MAINTENANCE_JOURNAL_INVALID');
    journal = content; return journal;
  }
  async function checkpoint(phase, extra = {}) {
    await guard(); journal = { ...journal, ...extra, phase }; await publish(paths.journal, journal); await onCheckpoint(phase);
  }
  async function using(root, run) {
    const opened = await provider({ root, compression, auditOptions });
    try { return await run(opened); } finally { await opened.close(); }
  }
  async function audit(root) {
    try { return await using(root, opened => opened.audit()); }
    catch (error) { if (error.code === 'MAINTENANCE_PROVIDER_AUDIT_REQUIRED') throw error; throw new CompanionMaintenanceError('MAINTENANCE_AUDIT_FAILED', error); }
  }
  async function observation(store, sessionId) {
    const reader = await store.open(sessionId, 'read');
    try { const { events } = await reader.read(); return { header: reader.header, inheritedEventCount: reader.inheritedEventCount, events }; }
    finally { await reader.close(); }
  }
  function summary(value) { return { id: value.header.id, header: value.header, inheritedEventCount: value.inheritedEventCount,
    eventCount: value.events.length, logicalDigest: digest(value) }; }
  async function validate(root) {
    return using(root, async opened => {
      const actual = await opened.audit();
      if (journal.stagingFingerprint && actual.fingerprint !== journal.stagingFingerprint) fail('MAINTENANCE_RETAINED_MISMATCH');
      if (actual.entries.length !== journal.retained.length || actual.entries.some(row => removed.includes(row.id))) fail('MAINTENANCE_RETAINED_MISMATCH');
      const rows = [];
      for (const expected of journal.retained) {
        await guard(); const value = summary(await observation(opened.store, expected.id));
        if (!isDeepStrictEqual(value, expected)) fail('MAINTENANCE_RETAINED_MISMATCH'); rows.push(value);
      }
      return { audit: actual, logicalDigest: digest(rows) };
    });
  }
  function receipt() {
    return { version: SCHEMA, scope: 'sessions-only', operationId, sessionPhase: journal.phase,
      removedSessionIds: removed, retainedCount: journal.retained.length, retainedDigest: journal.retainedDigest,
      oldBackupAbsent: journal.phase === 'complete', companionDeletion: 'incomplete', pending: [...pending] };
  }
  async function populate(inspected) {
    await guard(); await mkdir(paths.staging, { mode: 0o700 }); await syncDirectory(parent);
    journal.stagingIdentity = identity(await info(paths.staging)); await checkpoint('preparing', { retained: [] });
    const retained = [];
    await using(source, async from => using(paths.staging, async to => {
      for (const row of inspected.entries.filter(row => !removed.includes(row.id))) {
        await guard(); const value = await observation(from.store, row.id), observed = summary(value);
        if (!isDeepStrictEqual(observed.header, row.header) || observed.inheritedEventCount !== row.inheritedEventCount
          || observed.eventCount !== row.eventCount || observed.logicalDigest !== row.logicalDigest) fail('MAINTENANCE_SOURCE_CHANGED');
        retained.push(observed);
        const writer = await to.store.create(value.header, { inheritedEventCount: value.inheritedEventCount });
        try { for (let index = 0; index < value.events.length; index += 256) { await guard(); await writer.append(value.events.slice(index, index + 256)); } await writer.flush(); }
        finally { await writer.close(); }
        await onCheckpoint('session-copied');
      }
    }));
    retained.sort((a, b) => a.id < b.id ? -1 : a.id > b.id ? 1 : 0);
    await checkpoint('prepared', { retained, retainedDigest: digest(retained) });
    const checked = await validate(paths.staging);
    await checkpoint('validated', { stagingFingerprint: checked.audit.fingerprint });
  }
  async function prepare() {
    await guard(); const existing = await load(); if (existing) return recover();
    if (await info(paths.staging) || await info(paths.backup) || await info(paths.journal + '.writing')) fail('MAINTENANCE_OPERATION_COLLISION');
    const inspected = await audit(source);
    if (removed.some(sessionId => !inspected.entries.some(row => row.id === sessionId))) fail('MAINTENANCE_TARGET_NOT_FOUND');
    const keep = inspected.entries.filter(row => !removed.includes(row.id));
    const requiredBytes = keep.reduce((sum, row) => sum + row.currentPlainBytes * 2 + 4096, reserveBytes);
    if (!Number.isSafeInteger(requiredBytes)) fail('MAINTENANCE_SPACE_REQUIRED');
    const available = await statfs(parent, { bigint: true });
    if (available.bavail * available.bsize < BigInt(requiredBytes)) fail('MAINTENANCE_SPACE_REQUIRED');
    journal = { version: SCHEMA, operationId, inputDigest, authorityStamp: stamp, phase: 'preparing', sourceFingerprint: inspected.fingerprint,
      sourceMissing: inspected.missing, sourceIdentity: identity(await info(source)), retained: [], removed, compression,
      pending: [...pending], requiredBytes, startedAt: Date.now() };
    await checkpoint('preparing'); await populate(inspected); return receipt();
  }
  async function recover(commit = false) {
    await guard(); if (!await load()) fail('MAINTENANCE_JOURNAL_MISSING');
    if (journal.phase === 'complete') {
      if (await info(paths.backup) || await info(paths.staging)) fail('MAINTENANCE_RESIDUE_PRESENT');
      await validate(source); return receipt();
    }
    if (journal.phase === 'preparing') {
      if (await info(paths.backup) || !sameIdentity(await info(source), journal.sourceIdentity)) fail('MAINTENANCE_SOURCE_CHANGED');
      const inspected = await audit(source);
      if (inspected.fingerprint !== journal.sourceFingerprint) fail('MAINTENANCE_SOURCE_CHANGED');
      const staging = await info(paths.staging);
      if (staging) {
        await guard();
        if (journal.stagingIdentity) { if (!sameIdentity(staging, journal.stagingIdentity)) fail('MAINTENANCE_SOURCE_CHANGED'); await rm(paths.staging, { recursive: true }); }
        else await rmdir(paths.staging); // The crash gap precedes all provider writes: only an empty allocation is ours.
        await syncDirectory(parent);
      }
      await populate(inspected);
    } else if (journal.phase === 'prepared') {
      if (!sameIdentity(await info(paths.staging), journal.stagingIdentity)) fail('MAINTENANCE_SOURCE_CHANGED');
      const checked = await validate(paths.staging); await checkpoint('validated', { stagingFingerprint: checked.audit.fingerprint });
    }
    if (journal.phase === 'validated' && !commit) return receipt();
    let original = await info(source), staging = await info(paths.staging), backup = await info(paths.backup);
    if (journal.phase === 'validated') {
      if (backup || !sameIdentity(staging, journal.stagingIdentity) || !sameIdentity(original, journal.sourceIdentity)) fail('MAINTENANCE_SOURCE_CHANGED');
      if ((await audit(source)).fingerprint !== journal.sourceFingerprint) fail('MAINTENANCE_SOURCE_CHANGED');
      await validate(paths.staging); await checkpoint('moving-old');
    }
    if (['moving-old', 'old-moved'].includes(journal.phase)) {
      original = await info(source); backup = await info(paths.backup); staging = await info(paths.staging);
      if (!sameIdentity(staging, journal.stagingIdentity)) fail('MAINTENANCE_SOURCE_CHANGED');
      if (!backup) {
        if (!sameIdentity(original, journal.sourceIdentity)) fail('MAINTENANCE_SOURCE_CHANGED');
        await guard(); await rename(source, paths.backup); await syncDirectory(parent); await onCheckpoint('source-renamed');
        backup = await info(paths.backup);
      } else if (original || !sameIdentity(backup, journal.sourceIdentity)) fail('MAINTENANCE_SOURCE_CHANGED');
      await checkpoint('old-moved', { backupIdentity: identity(backup) }); await checkpoint('installing');
    }
    if (journal.phase === 'installing') {
      original = await info(source); staging = await info(paths.staging);
      if (!original) {
        if (!sameIdentity(staging, journal.stagingIdentity)) fail('MAINTENANCE_SOURCE_CHANGED');
        await validate(paths.staging); await guard(); await rename(paths.staging, source); await syncDirectory(parent); await onCheckpoint('staging-installed');
      } else if (staging || !sameIdentity(original, journal.stagingIdentity)) fail('MAINTENANCE_SOURCE_CHANGED');
      await validate(source); await checkpoint('installed');
    }
    if (journal.phase === 'installed') {
      if (!sameIdentity(await info(paths.backup), journal.backupIdentity)) fail('MAINTENANCE_SOURCE_CHANGED');
      if ((await audit(paths.backup)).fingerprint !== journal.sourceFingerprint) fail('MAINTENANCE_SOURCE_CHANGED');
      await validate(source); await checkpoint('removing-backup');
    }
    if (journal.phase === 'removing-backup') {
      backup = await info(paths.backup);
      if (backup && !sameIdentity(backup, journal.backupIdentity)) fail('MAINTENANCE_SOURCE_CHANGED');
      await validate(source);
      await guard(); if (backup) { await rm(paths.backup, { recursive: true }); await syncDirectory(parent); } await onCheckpoint('backup-removed');
      await validate(source);
      if (await info(paths.backup) || await info(paths.staging)) fail('MAINTENANCE_RESIDUE_PRESENT');
      await checkpoint('complete', { completedAt: Date.now() });
    }
    return receipt();
  }
  let chain = Promise.resolve();
  const serial = action => { const result = chain.then(action); chain = result.catch(() => {}); return result; };
  return Object.freeze({ prepare: () => serial(prepare), commit: () => serial(() => recover(true)), recover: () => serial(() => recover()),
    status: () => serial(async () => { await guard(); if (!await load()) return null; return journal.phase === 'complete' ? recover() : receipt(); }) });
}
