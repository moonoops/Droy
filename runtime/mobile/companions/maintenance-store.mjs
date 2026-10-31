import { DatabaseSync } from 'node:sqlite';
import { createHash } from 'node:crypto';
import { lstat, realpath, statfs } from 'node:fs/promises';
import { basename, dirname, isAbsolute, join, resolve } from 'node:path';
import { isDeepStrictEqual } from 'node:util';
import { companionStoreMaintenanceSchema, COMPANION_STORE_MAINTENANCE_SQL, companionCreationTombstoneDigest } from './store.mjs';

const scope = 'companion-store';
const ownerTables = ['messages', 'sessions', 'read_positions', 'companion_state', 'conversation_state', 'message_tombstones', 'companion_uploads', 'name_claims'];
const maintenanceTables = new Set(['creation_tombstones', 'maintenance_operations']);
const object = value => value !== null && typeof value === 'object' && !Array.isArray(value);
const identifier = value => typeof value === 'string' && /^[A-Za-z0-9_.:-]{1,160}$/u.test(value);
const operationIdValid = value => typeof value === 'string' && /^[A-Za-z0-9_-]{1,80}$/u.test(value);
const hex = value => typeof value === 'string' && /^[a-f0-9]{64}$/u.test(value);
const kinds = ['profile-request', 'draft-request', 'draft-id'];
export class CompanionStoreMaintenanceError extends Error {
  constructor(code, cause) { super(code, cause === undefined ? undefined : { cause }); this.code = code; }
}
const fail = (code, cause) => { throw new CompanionStoreMaintenanceError('MAINTENANCE_STORE_' + code, cause); };
function canonical(value) {
  if (Array.isArray(value)) return '[' + value.map(canonical).join(',') + ']';
  if (object(value)) return '{' + Object.keys(value).sort().map(key => JSON.stringify(key) + ':' + canonical(value[key])).join(',') + '}';
  return JSON.stringify(value);
}
const digest = value => createHash('sha256').update(canonical(value)).digest('hex');
const sql = value => value === null ? null : value.replace(/\bIF\s+NOT\s+EXISTS\b/giu, '').replace(/\s+/gu, ' ').replace(/\s*([(),=])\s*/gu, '$1').trim().toLowerCase();
const rows = (db, table) => db.prepare('SELECT * FROM "' + table + '" ORDER BY rowid').iterate();
function json(value) {
  try { const parsed = JSON.parse(value); if (!object(parsed)) fail('DATA_INVALID'); return parsed; }
  catch (cause) { fail('DATA_INVALID', cause); }
}
function auditSchema(db) {
  const version = db.prepare('PRAGMA user_version').get().user_version;
  if (![2, 3].includes(version)) fail('SCHEMA_UNSUPPORTED');
  if (db.prepare('PRAGMA journal_mode').get().journal_mode !== 'wal') fail('JOURNAL_UNSUPPORTED');
  const expected = companionStoreMaintenanceSchema(version);
  if (db.prepare('PRAGMA application_id').get().application_id !== expected.applicationId) fail('SCHEMA_UNSUPPORTED');
  const catalog = db.prepare('SELECT type,name,tbl_name,sql FROM sqlite_master ORDER BY type,name').all();
  const normalize = value => value.map(row => ({ ...row, sql: sql(row.sql) }));
  if (!isDeepStrictEqual(normalize(catalog), normalize(expected.catalog))) fail('SCHEMA_UNSUPPORTED');
  if (db.prepare('PRAGMA quick_check').all().some(row => row.quick_check !== 'ok') || db.prepare('PRAGMA foreign_key_check').all().length) fail('INTEGRITY_FAILED');
  return { version, tables: catalog.filter(row => row.type === 'table').map(row => row.name) };
}
function reference(value) {
  if (!object(value) || !/^sha256:[a-f0-9]{64}$/u.test(value.attachmentId ?? '') || !Number.isSafeInteger(value.bytes) || value.bytes < 0) fail('REFERENCE_INVALID');
  const fields = new Set(['attachmentId', 'bytes', 'name', 'mediaType', 'width', 'height', 'originalDimensions']);
  if (Object.keys(value).some(key => !fields.has(key))) fail('REFERENCE_INVALID');
  if (value.name !== undefined && (typeof value.name !== 'string' || !value.name || /[\x00/\\]/u.test(value.name))) fail('REFERENCE_INVALID');
  if (value.mediaType === undefined) { if (typeof value.name !== 'string' || value.width !== undefined || value.height !== undefined || value.originalDimensions !== undefined) fail('REFERENCE_INVALID'); }
  else {
    if (!['image/png', 'image/jpeg', 'image/webp', 'image/gif'].includes(value.mediaType)
      || !Number.isSafeInteger(value.width) || value.width < 1 || !Number.isSafeInteger(value.height) || value.height < 1) fail('REFERENCE_INVALID');
    if (value.originalDimensions !== undefined && (!object(value.originalDimensions)
      || Object.keys(value.originalDimensions).some(key => !['width', 'height'].includes(key))
      || !Number.isSafeInteger(value.originalDimensions.width) || value.originalDimensions.width < 1
      || !Number.isSafeInteger(value.originalDimensions.height) || value.originalDimensions.height < 1)) fail('REFERENCE_INVALID');
  }
  return structuredClone(value);
}
function validateOperation(row, db) {
  if (!operationIdValid(row.operation_id) || !identifier(row.target_id) || !hex(row.epoch_digest)
    || !hex(row.manifest_digest) || !hex(row.retained_digest) || !['deleted', 'complete'].includes(row.state)) fail('JOURNAL_INVALID');
  let tombstones; try { tombstones = JSON.parse(row.tombstones); } catch { fail('JOURNAL_INVALID'); }
  if (!Array.isArray(tombstones) || tombstones.some(value => !object(value) || Object.keys(value).some(key => !['kind', 'digest'].includes(key))
    || !kinds.includes(value.kind) || !hex(value.digest)) || new Set(tombstones.map(canonical)).size !== tombstones.length) fail('JOURNAL_INVALID');
  for (const value of tombstones) if (!db.prepare('SELECT 1 FROM creation_tombstones WHERE kind=? AND digest=?').get(value.kind, value.digest)) fail('JOURNAL_INVALID');
}
function scan(db, targetId, { requireTarget = true } = {}) {
  const schema = auditSchema(db), profiles = new Map(), sessions = new Map(), uploadByUrl = new Map(), uploads = [];
  for (const row of rows(db, 'profiles')) {
    const profile = json(row.data);
    if (!identifier(row.id) || profile.id !== row.id || !object(profile.settings)
      || profile.workspaceId !== null && !identifier(profile.workspaceId) || profile.chatSessionId !== null && !identifier(profile.chatSessionId)) fail('DATA_INVALID');
    profiles.set(row.id, profile);
  }
  if (requireTarget && !profiles.has(targetId)) fail('TARGET_NOT_FOUND');
  for (const row of rows(db, 'sessions')) {
    if (!identifier(row.session_id) || !['chat', 'work'].includes(row.kind) || !identifier(row.workspace_id)
      || row.task_id !== null && !identifier(row.task_id)) fail('REFERENCE_INVALID');
    sessions.set(row.session_id, row);
  }
  const drafts = [...rows(db, 'creation_drafts')];
  for (const row of drafts) if (!identifier(row.id) || !identifier(row.request_id) || !['open', 'cancelled', 'committed'].includes(row.state)
    || !Number.isFinite(Date.parse(row.created_at)) || row.state === 'committed' !== (row.companion_id !== null)) fail('DATA_INVALID');
  const targetDrafts = drafts.filter(row => row.companion_id === targetId), removedDrafts = new Set(targetDrafts.map(row => row.id));
  const receipts = [...rows(db, 'creation_receipts')];
  for (const row of receipts) if (!identifier(row.request_id) || !hex(row.fingerprint)) fail('DATA_INVALID');
  if (schema.version === 3) {
    for (const row of rows(db, 'creation_tombstones')) if (!kinds.includes(row.kind) || !hex(row.digest)) fail('JOURNAL_INVALID');
    for (const row of rows(db, 'maintenance_operations')) validateOperation(row, db);
  }
  const deleted = (table, row) => table === 'profiles' ? row.id === targetId
    : ownerTables.includes(table) || table === 'creation_receipts' ? row.companion_id === targetId
      : table === 'creation_drafts' ? removedDrafts.has(row.id) : table === 'draft_uploads' ? removedDrafts.has(row.draft_id) : false;
  const addUpload = (row, ownerKind, ownerId, removed) => {
    const value = json(row.data), uploadId = row.upload_id, ref = reference(value.ref);
    if (value.id !== uploadId || !identifier(uploadId) || typeof value.name !== 'string' || typeof value.mediaType !== 'string'
      || value.bytes !== ref.bytes || typeof value.url !== 'string') fail('REFERENCE_INVALID');
    const prefix = ownerKind === 'draft' ? '/droy/companions/creation-drafts/' + ownerId : ownerKind === 'user' ? '/droy/companions/user-profile' : '/droy/companions/' + ownerId;
    if (value.url !== prefix + '/attachments/' + uploadId) fail('REFERENCE_UNPROVEN');
    const entry = { ownerKind, ownerId, uploadId, ref, removed };
    if (uploadByUrl.has(value.url)) fail('REFERENCE_CONFLICT');
    uploadByUrl.set(value.url, entry); uploads.push(entry);
  };
  for (const row of rows(db, 'companion_uploads')) addUpload(row, 'companion', row.companion_id, row.companion_id === targetId);
  for (const row of rows(db, 'draft_uploads')) addUpload(row, 'draft', row.draft_id, removedDrafts.has(row.draft_id));
  for (const row of rows(db, 'user_uploads')) addUpload(row, 'user', 'user-profile', false);
  const claims = new Map(), resources = new Map(), taskClaims = [];
  const noteSession = (sessionId, source, companionId) => {
    if (!identifier(sessionId)) fail('REFERENCE_INVALID');
    const bound = sessions.get(sessionId);
    if (bound && companionId && bound.companion_id !== companionId) fail('REFERENCE_CONFLICT');
    if (companionId === targetId) claims.set(sessionId, { sessionId, bound: !!bound, source });
  };
  const addRef = (ref, removed, source) => {
    const valid = reference(ref), key = canonical(valid), existing = resources.get(key);
    if (existing) { existing.sources.push({ removed, source }); return; }
    resources.set(key, { ref: valid, sources: [{ removed, source }] });
  };
  for (const entry of uploads) addRef(entry.ref, entry.removed, entry.ownerKind + ':' + entry.ownerId + ':' + entry.uploadId);
  const walk = (value, { removed, source, companionId }, depth = 0) => {
    if (depth > 80) fail('DATA_INVALID');
    if (Array.isArray(value)) { for (const item of value) walk(item, { removed, source, companionId }, depth + 1); return; }
    if (!object(value)) return;
    if (value.ref !== undefined) addRef(value.ref, removed, source);
    if (typeof value.attachmentId === 'string' && value.attachmentId.startsWith('sha256:')) addRef(value, removed, source);
    else if (value.attachmentId !== undefined) {
      const found = uploads.find(row => row.ownerKind === 'companion' && row.ownerId === companionId && row.uploadId === value.attachmentId);
      if (!found) fail('REFERENCE_UNPROVEN'); addRef(found.ref, removed, source);
    }
    for (const key of ['url', 'avatar', 'avatarUrl']) if (typeof value[key] === 'string' && value[key].startsWith('/droy/companions/') && !value[key].startsWith('/droy/companions/assets/')) {
      const upload = uploadByUrl.get(value[key]); if (!upload) fail('REFERENCE_UNPROVEN');
      if (!removed && upload.removed) fail('REFERENCE_CONFLICT');
      addRef(upload.ref, removed, source);
    }
    for (const key of ['sessionId', 'chatSessionId']) if (value[key] !== undefined && value[key] !== null) noteSession(value[key], source, companionId);
    if (value.invalidSessionIds !== undefined) { if (!Array.isArray(value.invalidSessionIds)) fail('REFERENCE_INVALID'); for (const id of value.invalidSessionIds) noteSession(id, source, companionId); }
    if (value.sessionSources !== undefined) { if (!object(value.sessionSources)) fail('REFERENCE_INVALID'); for (const id of Object.keys(value.sessionSources)) noteSession(id, source, companionId); }
    for (const [key, item] of Object.entries(value)) if (key !== 'ref') walk(item, { removed, source, companionId }, depth + 1);
  };
  const retained = createHash('sha256'), removedDigest = createHash('sha256'), counts = {};
  for (const table of schema.tables.filter(name => !maintenanceTables.has(name)).sort()) {
    retained.update(table + '\n'); removedDigest.update(table + '\n'); let removedCount = 0;
    for (const row of rows(db, table)) {
      const remove = deleted(table, row);
      if (remove) { removedCount++; removedDigest.update(canonical(row) + '\n'); } else retained.update(canonical(row) + '\n');
      if (row.data !== undefined) {
        const value = json(row.data), companionId = table === 'profiles' ? row.id : row.companion_id;
        if (table === 'messages') {
          if (value.id !== row.message_id || !identifier(row.message_id) || !['user', 'assistant', 'system'].includes(value.role)) fail('DATA_INVALID');
          if (value.task?.sessionId && companionId === targetId) taskClaims.push({ taskId: row.message_id, sessionId: value.task.sessionId, bound: sessions.get(value.task.sessionId)?.task_id === row.message_id });
        }
        walk(value, { removed: remove, source: table + ':' + (row.message_id ?? row.id ?? row.upload_id ?? row.companion_id ?? row.singleton), companionId });
      }
    }
    counts[table] = removedCount;
  }
  const blobs = [...resources.values()].filter(row => row.sources.some(source => source.removed)).map(row => ({ ref: row.ref,
    sources: row.sources.filter(source => source.removed).map(source => source.source),
    retainedInStore: [...resources.values()].filter(other => other.ref.attachmentId === row.ref.attachmentId).flatMap(other => other.sources.filter(source => !source.removed).map(source => source.source)) }));
  const workspaceIds = new Set([...profiles.values()].filter(row => row.id === targetId).map(row => row.workspaceId).filter(Boolean));
  const targetSessions = [...sessions.values()].filter(row => row.companion_id === targetId);
  for (const row of targetSessions) { workspaceIds.add(row.workspace_id); noteSession(row.session_id, 'sessions', targetId); }
  const manifest = { version: 1, scope, companionId: targetId,
    sessions: targetSessions.map(row => ({ sessionId: row.session_id, workspaceId: row.workspace_id, kind: row.kind, taskId: row.task_id })),
    sessionClaims: [...claims.values()], taskClaims,
    workspaceClaims: [...workspaceIds].map(workspaceId => ({ workspaceId,
      retainedProfileIds: [...profiles.values()].filter(row => row.id !== targetId && row.workspaceId === workspaceId).map(row => row.id),
      retainedSessionIds: [...sessions.values()].filter(row => row.companion_id !== targetId && row.workspace_id === workspaceId).map(row => row.session_id) })),
    uploads: uploads.filter(row => row.removed).map(({ removed, ...value }) => value), blobs,
    creation: { receipts: receipts.filter(row => row.companion_id === targetId).map(row => ({ requestId: row.request_id })),
      drafts: targetDrafts.map(row => ({ draftId: row.id, requestId: row.request_id, state: row.state })) },
    removedCounts: counts, targetDigest: removedDigest.digest('hex'), retainedDigest: retained.digest('hex') };
  return { schema, manifest, manifestDigest: digest(manifest), deleted };
}

/** No HTTP/CLI capability minting. Root's trusted coordinator owns this lease. */
export async function createCompanionStoreMaintenance({ root, operationId, companionId, authority, onCheckpoint = () => {} } = {}) {
  if (typeof root !== 'string' || !isAbsolute(root) || !operationIdValid(operationId) || !identifier(companionId) || typeof onCheckpoint !== 'function') fail('INVALID_INPUT');
  if (typeof authority?.assertHeld !== 'function') fail('AUTHORITY_REQUIRED');
  const parent = await realpath(dirname(resolve(root))), source = join(parent, basename(root));
  const directory = await lstat(source); if (!directory.isDirectory() || directory.isSymbolicLink()) fail('LINKED_PATH');
  const path = join(source, 'companions.sqlite'); let stamp, prepared;
  async function guard(manifestDigest) {
    let value;
    try { value = await authority.assertHeld({ root: source, operationId, scope, ...(manifestDigest ? { manifestDigest } : {}) }); }
    catch (cause) { fail('AUTHORITY_LOST', cause); }
    if (!object(value) || value.root !== source || value.operationId !== operationId || !operationIdValid(value.admissionEpoch)
      || value.hostStopped !== true || value.admissionsClosed !== true) fail('AUTHORITY_LOST');
    const observed = digest({ root: source, operationId, admissionEpoch: value.admissionEpoch });
    if (stamp && stamp !== observed) fail('AUTHORITY_CHANGED'); stamp = observed;
    if (manifestDigest && value.manifestDigest !== manifestDigest) fail('MANIFEST_NOT_HELD');
    for (const suffix of ['', '-wal', '-shm', '-journal']) {
      let file; try { file = await lstat(path + suffix); } catch (cause) { if (cause.code === 'ENOENT' && suffix) continue; throw cause; }
      if (!file.isFile() || file.isSymbolicLink() || file.nlink !== 1) fail('LINKED_PATH');
      if (suffix === '-journal' && file.size) fail('JOURNAL_UNSUPPORTED');
    }
  }
  await guard();
  const openDb = readOnly => new DatabaseSync(path, { readOnly });
  function existing(db) {
    const schema = auditSchema(db);
    if (schema.version !== 3) return null;
    const row = db.prepare('SELECT * FROM maintenance_operations WHERE operation_id=?').get(operationId);
    if (row) { validateOperation(row, db); if (row.target_id !== companionId || row.epoch_digest !== stamp) fail('OPERATION_CONFLICT'); }
    return row;
  }
  const receipt = (row, observedComplete = false) => ({ version: 1, scope: 'companion-store-only', operationId, companionId,
    phase: row.state === 'complete' && !observedComplete ? 'hygiene-pending' : row.state,
    manifestDigest: row.manifest_digest, retainedDigest: row.retained_digest,
    companionDeletion: 'incomplete', pending: ['sessions', 'memory', 'workspaces', 'schedules', 'attachments', 'android'],
    hygiene: { scope: 'current-sqlite-main-wal', complete: observedComplete, externalBackupsErased: false } });
  async function observe(db, row) {
    const checked = scan(db, companionId, { requireTarget: false });
    if (checked.manifest.retainedDigest !== row.retained_digest || checked.manifest.removedCounts.profiles) fail('RETAINED_MISMATCH');
    let walSize = 0;
    try { walSize = (await lstat(path + '-wal')).size; } catch (cause) { if (cause.code !== 'ENOENT') throw cause; }
    const clean = row.state === 'complete' && walSize === 0 && db.prepare('PRAGMA freelist_count').get().freelist_count === 0;
    return receipt(row, clean);
  }
  async function prepare() {
    await guard(); const db = openDb(true);
    try {
      db.exec('PRAGMA query_only=ON; BEGIN');
      const previous = existing(db); if (previous) return await observe(db, previous);
      const value = scan(db, companionId); prepared = value;
      return { version: 1, scope, phase: 'prepared', operationId, companionId,
        manifestDigest: value.manifestDigest, retainedDigest: value.manifest.retainedDigest, manifest: value.manifest };
    } finally { if (db.isTransaction) db.exec('ROLLBACK'); db.close(); }
  }
  async function hygiene(db, row) {
    await guard(row.manifest_digest);
    await observe(db, row);
    const checkpoint = () => { const value = db.prepare('PRAGMA wal_checkpoint(TRUNCATE)').get(); if (value.busy !== 0 || value.log !== 0 || value.checkpointed !== 0) fail('CHECKPOINT_BUSY'); };
    checkpoint(); await onCheckpoint('checkpointed');
    if (row.state !== 'complete') { await guard(row.manifest_digest); db.exec('VACUUM'); await onCheckpoint('vacuumed'); }
    await guard(row.manifest_digest);
    const checked = scan(db, companionId, { requireTarget: false });
    if (checked.manifest.retainedDigest !== row.retained_digest || checked.manifest.removedCounts.profiles) fail('RETAINED_MISMATCH');
    if (row.state !== 'complete') db.prepare("UPDATE maintenance_operations SET state='complete' WHERE operation_id=?").run(operationId);
    checkpoint(); await onCheckpoint('complete'); return receipt({ ...row, state: 'complete' }, true);
  }
  async function commit() {
    await guard();
    const snapshot = prepared ? { manifestDigest: prepared.manifestDigest } : await prepare();
    await guard(snapshot.manifestDigest);
    const db = openDb(false);
    try {
      db.exec('PRAGMA foreign_keys=ON; PRAGMA busy_timeout=0; PRAGMA synchronous=FULL; PRAGMA secure_delete=ON;');
      if (db.prepare('PRAGMA secure_delete').get().secure_delete !== 1) fail('SECURE_DELETE_UNAVAILABLE');
      const previous = existing(db);
      if (previous) { await guard(previous.manifest_digest); return await hygiene(db, previous); }
      await guard(prepared.manifestDigest);
      const size = (await lstat(path)).size, available = await statfs(source, { bigint: true });
      if (available.bavail * available.bsize < BigInt(size) * 2n + 8n * 1024n * 1024n) fail('SPACE_REQUIRED');
      db.exec('BEGIN IMMEDIATE'); await onCheckpoint('transaction-open'); await guard(prepared.manifestDigest);
      const current = scan(db, companionId);
      if (current.manifestDigest !== prepared.manifestDigest) fail('SOURCE_CHANGED');
      if (current.schema.version === 2) db.exec(COMPANION_STORE_MAINTENANCE_SQL + 'PRAGMA user_version=3;');
      const tombstones = [];
      const tombstone = (kind, value) => {
        const key = companionCreationTombstoneDigest(kind, value); tombstones.push({ kind, digest: key });
        db.prepare('INSERT OR IGNORE INTO creation_tombstones VALUES(?,?)').run(kind, key);
      };
      for (const row of current.manifest.creation.receipts) tombstone('profile-request', row.requestId);
      for (const row of current.manifest.creation.drafts) { tombstone('draft-request', row.requestId); tombstone('draft-id', row.draftId); }
      for (const row of current.manifest.creation.drafts) db.prepare('DELETE FROM draft_uploads WHERE draft_id=?').run(row.draftId);
      db.prepare('DELETE FROM creation_drafts WHERE companion_id=?').run(companionId);
      db.prepare('DELETE FROM creation_receipts WHERE companion_id=?').run(companionId);
      for (const table of ownerTables) db.prepare('DELETE FROM "' + table + '" WHERE companion_id=?').run(companionId);
      db.prepare('DELETE FROM profiles WHERE id=?').run(companionId);
      const row = { operation_id: operationId, epoch_digest: stamp, target_id: companionId,
        manifest_digest: current.manifestDigest, retained_digest: current.manifest.retainedDigest, state: 'deleted', tombstones: canonical(tombstones) };
      db.prepare('INSERT INTO maintenance_operations VALUES(?,?,?,?,?,?,?)').run(...Object.values(row));
      const checked = scan(db, companionId, { requireTarget: false });
      if (checked.manifest.retainedDigest !== row.retained_digest) fail('RETAINED_MISMATCH');
      await onCheckpoint('rows-deleted'); await guard(current.manifestDigest);
      db.exec('COMMIT'); await onCheckpoint('deleted');
      return await hygiene(db, row);
    } catch (cause) { if (db.isTransaction) db.exec('ROLLBACK'); throw cause; }
    finally { if (db.isOpen) db.close(); }
  }
  return Object.freeze({ prepare, commit, recover: commit,
    async status() {
      await guard(); const db = openDb(true);
      try { const row = existing(db); return row ? await observe(db, row) : { version: 1, scope, operationId, companionId, phase: prepared ? 'prepared' : 'unprepared' }; }
      finally { db.close(); }
    } });
}
