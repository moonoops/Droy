import { parentPort, workerData } from 'node:worker_threads';
import { DatabaseSync, backup as sqliteBackup } from 'node:sqlite';
import { randomUUID, createHash, randomBytes, scryptSync, createCipheriv, createDecipheriv } from 'node:crypto';
import { join, dirname, basename, resolve, isAbsolute } from 'node:path';
import { existsSync, mkdirSync, lstatSync, realpathSync, readFileSync, writeFileSync, openSync,
  closeSync, fsyncSync, renameSync, rmSync, readdirSync, statfsSync } from 'node:fs';
import { MEMORY_SCHEMA_VERSION, MemoryError, fail, fields, identity, text, timestamp,
  sourceIds, dependencies, normalizeRecord, canonical, queryInput } from './schema.mjs';

const APP_ID = 1146244434;
let db, owner, directory, databasePath, ownerPath, markerPath, marker, databaseIdentity, ownerIdentity, failed;
const digest = value => createHash('sha256').update(canonical(value)).digest('hex');
const nowISO = () => new Date().toISOString();
function errorCode(error) { return error instanceof MemoryError ? error.code : 'MEMORY_STORE_IO'; }
function regular(path) { const s = lstatSync(path); if (!s.isFile() || s.isSymbolicLink()) fail('MEMORY_STORE_IDENTITY'); return s; }
function syncDirectory(path) { const fd = openSync(path, 'r'); try { fsyncSync(fd); } finally { closeSync(fd); } }
function writeMarker(value, first = false) {
  const temporary = first ? markerPath : markerPath + '.' + randomUUID() + '.tmp';
  let fd;
  try {
    fd = openSync(temporary, 'wx', 0o600); writeFileSync(fd, JSON.stringify(value)); fsyncSync(fd); closeSync(fd); fd = undefined;
    if (!first) renameSync(temporary, markerPath);
    syncDirectory(dirname(markerPath)); marker = value;
  } finally { if (fd !== undefined) closeSync(fd); if (!first && existsSync(temporary)) rmSync(temporary); }
}
function readMarker() {
  if (!existsSync(markerPath)) fail('MEMORY_MARKER_MISSING');
  if (regular(markerPath).size > 1024) fail('MEMORY_MARKER_CORRUPT');
  let value; try { value = JSON.parse(readFileSync(markerPath, 'utf8')); } catch { fail('MEMORY_MARKER_CORRUPT'); }
  if (value.schemaVersion !== 1 || typeof value.instanceId !== 'string' || !Number.isSafeInteger(value.revision) || value.revision < 0) fail('MEMORY_MARKER_CORRUPT');
  return value;
}
function meta() {
  const value = db.prepare('SELECT * FROM metadata WHERE singleton=1').get();
  if (!value || !Number.isSafeInteger(value.revision) || value.revision < 0 || !Number.isSafeInteger(value.origin_seq)) fail('MEMORY_STORE_CORRUPT');
  return value;
}
function check() {
  if (!existsSync(ownerPath)) fail('MEMORY_OWNER_MISSING');
  const lock = regular(ownerPath), file = regular(databasePath), saved = readMarker(), m = meta();
  if (lock.ino !== ownerIdentity.ino || lock.dev !== ownerIdentity.dev || file.ino !== databaseIdentity.ino || file.dev !== databaseIdentity.dev
    || saved.instanceId !== m.instance_id || saved.instanceId !== marker.instanceId) fail('MEMORY_STORE_IDENTITY');
  if (saved.revision > m.revision) fail('MEMORY_STORE_ROLLBACK');
  if (saved.revision < m.revision) writeMarker({ ...saved, revision: m.revision });
  return m;
}
function transaction(callback) {
  db.exec('BEGIN IMMEDIATE');
  try {
    const result = callback();
    db.prepare('UPDATE metadata SET revision=revision+1 WHERE singleton=1').run();
    db.exec('COMMIT');
    const m = meta(); writeMarker({ ...marker, revision: m.revision });
    return result;
  } catch (error) { if (db.isTransaction) db.exec('ROLLBACK'); throw error; }
}
function totalBytes(path = directory) {
  let sum = 0;
  for (const item of readdirSync(path, { withFileTypes: true })) {
    const child = join(path, item.name);
    if (item.isSymbolicLink()) fail('MEMORY_STORE_IDENTITY');
    if (item.isDirectory()) sum += totalBytes(child); else if (item.isFile()) sum += lstatSync(child).size;
  } return sum;
}
function capacity(reserve = 262144) {
  const bytes = totalBytes(), stat = statfsSync(directory), free = stat.bavail * stat.bsize;
  return { bytes, maxBytes: workerData.maxBytes, freeBytes: free, minFreeBytes: workerData.minFreeBytes,
    limited: bytes >= workerData.maxBytes - reserve || free < workerData.minFreeBytes + reserve };
}
function recordById(id) {
  identity(id); const row = db.prepare('SELECT data FROM records WHERE record_id=?').get(id);
  if (!row) return null;
  try { return JSON.parse(row.data); } catch { fail('MEMORY_STORE_CORRUPT'); }
}
function nextSequence() {
  db.prepare('UPDATE metadata SET origin_seq=origin_seq+1 WHERE singleton=1').run(); return meta().origin_seq;
}
function advanceDisclosureEpoch() { db.prepare('UPDATE metadata SET disclosure_epoch=disclosure_epoch+1 WHERE singleton=1').run(); }
function receipt(record) { return { recordId: record.recordId, revisionId: record.revisionId, originSeq: record.originSeq }; }
function save(record, action) {
  const content = canonical(record);
  db.prepare(`INSERT INTO records VALUES(?,?,?,?,?,?,?,?,?,?,?) ON CONFLICT(record_id) DO UPDATE SET revision_id=excluded.revision_id,
    kind=excluded.kind,event_type=excluded.event_type,occurred_ms=excluded.occurred_ms,state=excluded.state,expires_ms=excluded.expires_ms,
    search_text=excluded.search_text,city=excluded.city,origin_seq=excluded.origin_seq,data=excluded.data`).run(
    record.recordId, record.revisionId, record.kind, record.payload?.eventType ?? null, Date.parse(record.occurredAt), record.state,
    record.retention.expiresAt ? Date.parse(record.retention.expiresAt) : null,
    record.payload ? JSON.stringify(record.payload).toLocaleLowerCase('und') : '', record.payload?.city ?? null, record.originSeq, content);
  db.prepare('INSERT INTO revisions VALUES(?,?,?,?)').run(record.revisionId, record.recordId, record.originSeq, content);
  db.prepare('INSERT INTO changes(origin_seq,record_id,revision_id,action) VALUES(?,?,?,?)').run(record.originSeq, record.recordId, record.revisionId, action);
  db.prepare('DELETE FROM dependencies WHERE child_id=?').run(record.recordId);
  for (const dependency of record.derivedFrom) db.prepare('INSERT INTO dependencies VALUES(?,?,?)').run(record.recordId, dependency.recordId, dependency.revisionId);
  return receipt(record);
}
function lineage(input) {
  const refs = [], categories = new Set(input.privacy.categories);
  for (const parent of input.derivedFrom) {
    const record = recordById(parent.recordId);
    if (!record || !['active', 'candidate'].includes(record.state) || record.revisionId !== parent.revisionId) fail('MEMORY_SOURCE_CHANGED');
    refs.push(...record.sourceRefs, ...record.lineageRefs, 'memory-record:' + record.recordId, 'memory-revision:' + record.revisionId);
    for (const category of record.privacy.categories) categories.add(category);
  }
  const result = sourceIds([...new Set(refs)]);
  if (new Set([...result, ...input.sourceRefs]).size > 126) fail('MEMORY_SOURCE_LIMIT');
  input.privacy.categories = [...categories].sort();
  return result;
}
function assertSources(input) {
  for (const source of [...input.sourceRefs, ...input.lineageRefs]) {
    if (db.prepare('SELECT 1 FROM forgotten_sources WHERE source_ref=?').get(source)) fail('MEMORY_SOURCE_FORGOTTEN');
  }
}
function privateCategories(value) {
  if (!Array.isArray(value) || !value.length || value.length > 4 || !value.includes('memory')
    || value.some(v => !['memory', 'activity', 'location', 'derived'].includes(v))) fail('MEMORY_ARCHIVE_INVALID');
  return [...new Set(value)];
}
function create(input, automatic = false, inheritedCategories = []) {
  const value = normalizeRecord(input, nowISO(), automatic), m = meta();
  value.lineageRefs = lineage(value); assertSources(value);
  value.privacy.categories = [...new Set([...value.privacy.categories, ...inheritedCategories])].sort();
  const record = { schemaVersion: 1, spaceId: m.space_id, companionId: m.companion_id,
    recordId: randomUUID(), revisionId: randomUUID(), parentRevisionIds: [], originNodeId: m.node_id, originSeq: nextSequence(),
    recordedAt: nowISO(), ...value };
  if (automatic && record.retention.mode === 'automatic' && Date.parse(record.retention.expiresAt) <= Date.now()) {
    record.payload = null; record.state = 'expired';
  }
  save(record, 'record'); return record;
}
function write(operation, args, callback, allowFull = false) {
  const operationId = identity(args.operationId), hash = digest({ operation, args });
  const prior = db.prepare('SELECT fingerprint,receipt FROM receipts WHERE operation_id=?').get(operationId);
  if (prior) {
    if (prior.fingerprint !== hash) fail('MEMORY_OPERATION_CONFLICT');
    return { ...JSON.parse(prior.receipt), duplicate: true };
  }
  // Include journal/index/page growth, not just the incoming JSON size. Keep
  // headroom for deletion and status calls when ordinary writes are refused.
  if (!allowFull && capacity(1048576 + Buffer.byteLength(canonical(args)) * 8).limited) fail('MEMORY_STORAGE_LIMIT');
  return transaction(() => {
    const result = { ...callback(), duplicate: false };
    db.prepare('INSERT INTO receipts VALUES(?,?,?)').run(operationId, hash, canonical(result)); return result;
  });
}
function purgeBody(recordId) {
  const rows = db.prepare('SELECT revision_id,data FROM revisions WHERE record_id=?').iterate(recordId);
  for (const row of rows) {
    const old = JSON.parse(row.data); old.payload = null; old.state = 'deleted';
    db.prepare('UPDATE revisions SET data=? WHERE revision_id=?').run(canonical(old), row.revision_id);
  }
}
function invalidate(recordId, state, visited = new Set(), affected = Object.assign([], { total: 0 })) {
  if (visited.has(recordId)) return affected;
  visited.add(recordId);
  const existing = recordById(recordId);
  if (!existing || existing.state === 'deleted' || (existing.state === 'expired' && state === 'expired')) return affected;
  const children = db.prepare('SELECT child_id FROM dependencies WHERE parent_id=?').all(recordId).map(v => v.child_id);
  const updated = { ...existing, revisionId: randomUUID(), parentRevisionIds: [existing.revisionId],
    originSeq: nextSequence(), recordedAt: nowISO(), payload: null, state };
  purgeBody(recordId); save(updated, state === 'deleted' ? 'forget' : 'invalidate');
  affected.total++;
  if (affected.length < 64) affected.push({ ...receipt(updated), previousRevisionId: existing.revisionId });
  for (const child of children) invalidate(child, state, visited, affected);
  return affected;
}
function where(input, includeStates = true) {
  const terms = [], values = [];
  const list = (column, items) => { if (items) { terms.push(`${column} IN (${items.map(() => '?').join(',')})`); values.push(...items); } };
  if (includeStates) list('state', input.states ?? ['active', 'candidate']);
  list('kind', input.kinds); list('event_type', input.eventTypes);
  if (input.from) { terms.push('occurred_ms>=?'); values.push(Date.parse(input.from)); }
  if (input.to) { terms.push('occurred_ms<?'); values.push(Date.parse(input.to)); }
  if (input.text) { terms.push('instr(search_text,?)>0'); values.push(input.text.toLocaleLowerCase('und')); }
  return { sql: terms.length ? ' WHERE ' + terms.join(' AND ') : '', values };
}
function coverage(input) {
  const range = where({ from: input.from, to: input.to, states: ['active', 'candidate'] });
  const and = range.sql ? ' AND ' : ' WHERE ';
  return { from: input.from, to: input.to,
    gaps: db.prepare('SELECT COUNT(*) n FROM records' + range.sql + and + "event_type='gap'").get(...range.values).n,
    unresolvedLocations: db.prepare('SELECT COUNT(*) n FROM records' + range.sql + and + "event_type IN ('location','visit') AND city IS NULL").get(...range.values).n,
    mockLocations: db.prepare('SELECT COUNT(*) n FROM records' + range.sql + and + "event_type IN ('location','visit') AND json_extract(data,'$.payload.isMock')=1").get(...range.values).n,
    continuousObservationVerified: false };
}
function query(args) {
  const input = queryInput(args), revision = meta().revision;
  const fingerprint = digest({ ...input, cursor: undefined });
  let after;
  if (input.cursor) {
    try { after = JSON.parse(Buffer.from(input.cursor, 'base64url').toString('utf8')); } catch { fail('MEMORY_INVALID_CURSOR'); }
    if (after.revision !== revision) fail('MEMORY_CURSOR_STALE');
    if (after.fingerprint !== fingerprint || !Number.isSafeInteger(after.time) || typeof after.id !== 'string') fail('MEMORY_INVALID_CURSOR');
  }
  const filter = where(input), total = db.prepare('SELECT COUNT(*) n FROM records' + filter.sql).get(...filter.values).n;
  const suffix = after ? (filter.sql ? ' AND ' : ' WHERE ') + '(occurred_ms<? OR (occurred_ms=? AND record_id>?))' : '';
  const values = after ? [...filter.values, after.time, after.time, after.id] : filter.values;
  const rows = db.prepare('SELECT data,occurred_ms,record_id FROM records' + filter.sql + suffix + ' ORDER BY occurred_ms DESC,record_id LIMIT ?').all(...values, input.limit + 1);
  const items = []; let bytes = 2, truncated = rows.length > input.limit, last;
  for (const row of rows.slice(0, input.limit)) {
    const item = JSON.parse(row.data);
    if (input.projection === 'summary' && item.payload?.text && Buffer.byteLength(item.payload.text) > 512) {
      let preview = '', length = 0;
      for (const character of item.payload.text) { const size = Buffer.byteLength(character); if (length + size > 512) break; preview += character; length += size; }
      item.payload.text = preview; item.contentTruncated = true;
    }
    const size = Buffer.byteLength(canonical(item)) + 1;
    if (bytes + size > input.maxBytes) { truncated = true; break; }
    items.push(item); bytes += size; last = row;
  }
  if (!items.length && rows.length) fail('MEMORY_RESULT_TOO_LARGE');
  return { items, total, revision, truncated, bytes, coverage: coverage(input),
    nextCursor: truncated && last ? Buffer.from(JSON.stringify({ revision, fingerprint, time: last.occurred_ms, id: last.record_id })).toString('base64url') : null };
}
function assertNewPath(path) {
  if (typeof path !== 'string' || !isAbsolute(path) || existsSync(path)) fail('MEMORY_INVALID_DESTINATION');
  const parent = realpathSync(dirname(path));
  if (lstatSync(dirname(path)).isSymbolicLink()) fail('MEMORY_INVALID_DESTINATION');
  return join(parent, basename(path));
}
function fileWrite(path, bytes) {
  const destination = assertNewPath(path), fd = openSync(destination, 'wx', 0o600);
  try { writeFileSync(fd, bytes); fsyncSync(fd); } finally { closeSync(fd); }
  syncDirectory(dirname(destination)); return destination;
}
function passphrase(value) { if (typeof value !== 'string' || value.length < 12 || Buffer.byteLength(value) > 1024) fail('MEMORY_INVALID_PASSPHRASE'); }
function encrypt(value, password) {
  passphrase(password); const salt = randomBytes(16), iv = randomBytes(12), key = scryptSync(password, salt, 32);
  try {
    const cipher = createCipheriv('aes-256-gcm', key, iv); cipher.setAAD(Buffer.from('DROY-MEMORY-ARCHIVE-1'));
    const ciphertext = Buffer.concat([cipher.update(value), cipher.final()]);
    return Buffer.concat([Buffer.from('DRMEM001'), salt, iv, cipher.getAuthTag(), ciphertext]);
  } finally { key.fill(0); }
}
function decrypt(bytes, password) {
  passphrase(password);
  if (bytes.length < 53 || bytes.subarray(0, 8).toString() !== 'DRMEM001') fail('MEMORY_ARCHIVE_INVALID');
  const key = scryptSync(password, bytes.subarray(8, 24), 32);
  try {
    const cipher = createDecipheriv('aes-256-gcm', key, bytes.subarray(24, 36));
    cipher.setAAD(Buffer.from('DROY-MEMORY-ARCHIVE-1')); cipher.setAuthTag(bytes.subarray(36, 52));
    return Buffer.concat([cipher.update(bytes.subarray(52)), cipher.final()]);
  } catch { fail('MEMORY_ARCHIVE_AUTHENTICATION'); } finally { key.fill(0); }
}
async function handle(operation, args) {
  const m = check();
  switch (operation) {
    case 'status': return { schemaVersion: 1, spaceId: m.space_id, companionId: m.companion_id, nodeId: m.node_id,
      revision: m.revision, originSeq: m.origin_seq, disclosureEpoch: m.disclosure_epoch, counts: Object.fromEntries(db.prepare('SELECT state,COUNT(*) n FROM records GROUP BY state').all().map(v => [v.state, v.n])), storage: capacity() };
    case 'record': fields(args, ['operationId', 'record']); return write(operation, args, () => receipt(create(args.record)));
    case 'get': fields(args, ['recordId']); return recordById(args.recordId);
    case 'query': return query(args);
    case 'revise': {
      fields(args, ['operationId', 'recordId', 'expectedRevisionId', 'patch']); identity(args.recordId); identity(args.expectedRevisionId);
      return write(operation, args, () => {
        const old = recordById(args.recordId); if (!old || !['active', 'candidate'].includes(old.state)) fail('MEMORY_NOT_FOUND');
        if (old.revisionId !== args.expectedRevisionId) fail('MEMORY_REVISION_CONFLICT');
        advanceDisclosureEpoch();
        fields(args.patch, ['payload', 'occurredAt', 'validFrom', 'validUntil', 'sourceRefs', 'derivedFrom', 'assertion', 'actor', 'retention', 'state']);
        const input = Object.fromEntries(['kind', 'payload', 'occurredAt', 'validFrom', 'validUntil', 'sourceRefs', 'derivedFrom', 'assertion', 'actor', 'retention', 'state'].map(k => [k, old[k]]));
        const value = normalizeRecord({ ...input, ...args.patch }, nowISO()); value.lineageRefs = lineage(value); assertSources(value);
        value.privacy.categories = [...new Set([...old.privacy.categories, ...value.privacy.categories])].sort();
        if (value.derivedFrom.some(p => p.recordId === old.recordId)) fail('MEMORY_DEPENDENCY_CYCLE');
        const descendants = db.prepare('WITH RECURSIVE children(id) AS (SELECT child_id FROM dependencies WHERE parent_id=? UNION SELECT d.child_id FROM dependencies d JOIN children c ON d.parent_id=c.id) SELECT id FROM children').all(old.recordId).map(v => v.id);
        if (value.derivedFrom.some(p => descendants.includes(p.recordId))) fail('MEMORY_DEPENDENCY_CYCLE');
        const affected = Object.assign([], { total: 0 }), visited = new Set();
        for (const id of descendants) invalidate(id, 'expired', visited, affected);
        const updated = { ...old, ...value, revisionId: randomUUID(), parentRevisionIds: [old.revisionId], originSeq: nextSequence(), recordedAt: nowISO() };
        return { ...save(updated, 'revise'), previousRevisionId: old.revisionId, invalidated: [...affected], invalidatedCount: affected.total, invalidatedTruncated: affected.total > affected.length };
      });
    }
    case 'forget-plan': {
      fields(args, ['recordId', 'expectedRevisionId', 'afterSource', 'limit']);
      identity(args.recordId); identity(args.expectedRevisionId);
      const limit = args.limit ?? 64, after = args.afterSource === undefined ? '' : identity(args.afterSource);
      if (!Number.isInteger(limit) || limit < 1 || limit > 64) fail();
      const record = recordById(args.recordId); if (!record) fail('MEMORY_NOT_FOUND');
      if (record.revisionId !== args.expectedRevisionId) fail('MEMORY_REVISION_CONFLICT');
      const rows = db.prepare(`SELECT DISTINCT s.value AS source FROM revisions r,json_each(r.data,'$.sourceRefs') s
        WHERE r.record_id=? AND s.value>? ORDER BY source LIMIT ?`).all(record.recordId, after, limit + 1);
      const sourceRefs = rows.slice(0, limit).map(row => row.source);
      return { recordId: record.recordId, revisionId: record.revisionId, sourceRefs,
        nextSource: sourceRefs.at(-1) ?? null, hasMore: rows.length > limit,
        spaceId: m.space_id, disclosureEpoch: m.disclosure_epoch };
    }
    case 'forget': {
      fields(args, ['operationId', 'recordId', 'expectedRevisionId']); identity(args.recordId); identity(args.expectedRevisionId);
      return write(operation, args, () => {
        const old = recordById(args.recordId); if (!old) fail('MEMORY_NOT_FOUND');
        if (old.revisionId !== args.expectedRevisionId) fail('MEMORY_REVISION_CONFLICT');
        advanceDisclosureEpoch();
        // An edit may replace its current source list. Forgetting still suppresses
        // this record's historical own sources, without suppressing its parents.
        for (const row of db.prepare('SELECT data FROM revisions WHERE record_id=?').iterate(old.recordId)) {
          const historical = JSON.parse(row.data);
          for (const source of historical.sourceRefs) db.prepare('INSERT OR IGNORE INTO forgotten_sources VALUES(?)').run(source);
        }
        for (const source of ['memory-record:' + old.recordId, 'memory-revision:' + old.revisionId]) db.prepare('INSERT OR IGNORE INTO forgotten_sources VALUES(?)').run(source);
        const affected = invalidate(old.recordId, 'deleted');
        return { ...(affected[0] ?? receipt(old)), affected: [...affected], affectedCount: affected.total,
          affectedTruncated: affected.total > affected.length, forgottenSources: old.sourceRefs };
      }, true);
    }
    case 'ingest': {
      fields(args, ['operationId', 'records', 'recordKeys', 'sourceCategories', 'sourceCursor', 'sourceCursors']);
      if (!Array.isArray(args.records) || args.records.length > 64) fail();
      if (args.sourceCategories !== undefined && (!Array.isArray(args.sourceCategories) || args.sourceCategories.length > 4)) fail();
      const sourceCategories = privateCategories([...new Set(['memory', ...(args.sourceCategories ?? [])])]);
      if (args.recordKeys !== undefined && (!Array.isArray(args.recordKeys) || args.recordKeys.length !== args.records.length)) fail();
      const recordKeys = args.recordKeys?.map(identity) ?? args.records.map(input => 'sha256:' + digest(input));
      if (new Set(recordKeys).size !== recordKeys.length) fail('MEMORY_INGEST_KEY_CONFLICT');
      if (args.sourceCursors !== undefined && (!Array.isArray(args.sourceCursors) || args.sourceCursors.length > 32)) fail();
      const cursors = [...(args.sourceCursor ? [args.sourceCursor] : []), ...(args.sourceCursors ?? [])];
      if (cursors.length > 32 || (!args.records.length && !cursors.length)) fail();
      const sources = new Set();
      for (const cursor of cursors) {
        fields(cursor, ['source', 'cursor']); identity(cursor.source); text(cursor.cursor, 65536);
        if (sources.has(cursor.source)) fail(); sources.add(cursor.source);
      }
      return write(operation, args, () => {
        const items = args.records.map((input, index) => {
          const normalized = normalizeRecord(input, nowISO(), true);
          if (!normalized.sourceRefs.length) fail('MEMORY_SOURCE_REQUIRED');
          const ingestionKey = recordKeys[index], fingerprint = digest({ input, sourceCategories });
          const prior = db.prepare('SELECT record_id,fingerprint FROM ingest_receipts WHERE ingestion_key=?').get(ingestionKey);
          if (prior) {
            if (prior.fingerprint !== fingerprint) fail('MEMORY_INGEST_KEY_CONFLICT');
            const saved = recordById(prior.record_id);
            return { ...receipt(saved), state: saved.state, duplicate: true };
          }
          const record = create(input, true, sourceCategories);
          db.prepare('INSERT INTO ingest_receipts VALUES(?,?,?)').run(ingestionKey, record.recordId, fingerprint);
          return { ...receipt(record), state: record.state, duplicate: false };
        });
        for (const cursor of cursors) db.prepare('INSERT INTO source_cursors VALUES(?,?) ON CONFLICT(source) DO UPDATE SET cursor=excluded.cursor').run(cursor.source, cursor.cursor);
        // Receipts retain only acknowledgements, not prior journal snapshots.
        // The current cursor value has one authoritative row and can be replaced.
        return { items, sourceCursor: args.sourceCursor ? { source: args.sourceCursor.source, updated: true } : null,
          sourceCursors: cursors.map(cursor => ({ source: cursor.source, updated: true })) };
      });
    }
    case 'source-cursor': {
      fields(args, ['source']); identity(args.source);
      return { source: args.source, cursor: db.prepare('SELECT cursor FROM source_cursors WHERE source=?').get(args.source)?.cursor ?? null };
    }
    case 'aggregate': {
      fields(args, ['from', 'to', 'groupBy']); const input = queryInput({ from: args.from, to: args.to });
      if (!['kind', 'city', 'day'].includes(args.groupBy ?? 'kind')) fail();
      const filter = where(input), groupBy = args.groupBy ?? 'kind';
      const expression = groupBy === 'city' ? 'city' : groupBy === 'day' ? "strftime('%Y-%m-%d',occurred_ms/1000,'unixepoch')" : 'kind';
      const extra = groupBy === 'city' ? (filter.sql ? ' AND ' : ' WHERE ') + "event_type IN ('location','visit') AND city IS NOT NULL AND COALESCE(json_extract(data,'$.payload.isMock'),0)<>1" : '';
      const rows = db.prepare(`SELECT ${expression} value,COUNT(*) count FROM records${filter.sql}${extra} GROUP BY ${expression} ORDER BY value LIMIT 1001`).all(...filter.values);
      if (rows.length > 1000) fail('MEMORY_AGGREGATE_TOO_LARGE');
      return { groupBy, items: rows, total: db.prepare('SELECT COUNT(*) n FROM records' + filter.sql).get(...filter.values).n,
        complete: true, revision: m.revision, disclosureEpoch: m.disclosure_epoch, spaceId: m.space_id,
        dayTimezone: groupBy === 'day' ? 'UTC' : undefined, coverage: coverage(input) };
    }
    case 'maintenance-plan': {
      fields(args, ['now', 'limit']); const time = timestamp(args.now ?? nowISO()), limit = args.limit ?? 32;
      if (!Number.isInteger(limit) || limit < 1 || limit > 64) fail();
      const rows = db.prepare("SELECT data FROM records WHERE state IN ('active','candidate') AND expires_ms IS NOT NULL AND expires_ms<=? ORDER BY record_id LIMIT ?").all(Date.parse(time), limit);
      return { now: time, limit, revision: m.revision, disclosureEpoch: m.disclosure_epoch, spaceId: m.space_id,
        targets: rows.map(row => { const r = JSON.parse(row.data); return { recordId: r.recordId, revisionId: r.revisionId, sourceRefs: r.sourceRefs, lineageRefs: r.lineageRefs }; }) };
    }
    case 'maintain': {
      fields(args, ['now', 'limit', 'expectedRevision']); const time = timestamp(args.now), limit = args.limit ?? 32;
      if (!Number.isInteger(limit) || limit < 1 || limit > 64 || !Number.isSafeInteger(args.expectedRevision)) fail();
      if (m.revision !== args.expectedRevision) fail('MEMORY_REVISION_CONFLICT');
      return transaction(() => {
        advanceDisclosureEpoch();
        const rows = db.prepare("SELECT record_id FROM records WHERE state IN ('active','candidate') AND expires_ms IS NOT NULL AND expires_ms<=? ORDER BY record_id LIMIT ?").all(Date.parse(time), limit);
        const items = [];
        for (const row of rows) {
          const old = recordById(row.record_id);
          // Expiry removes the raw material; retained independent summaries may survive.
          const updated = { ...old, payload: null, state: 'expired', revisionId: randomUUID(), parentRevisionIds: [old.revisionId], originSeq: nextSequence(), recordedAt: nowISO() };
          purgeBody(old.recordId); items.push(save(updated, 'expire'));
        }
        return { expired: items, hasMore: db.prepare("SELECT 1 FROM records WHERE state IN ('active','candidate') AND expires_ms IS NOT NULL AND expires_ms<=? LIMIT 1").get(Date.parse(time)) !== undefined };
      });
    }
    case 'changes': {
      fields(args, ['afterSeq', 'limit']); const after = args.afterSeq ?? 0, limit = args.limit ?? 100;
      if (!Number.isSafeInteger(after) || after < 0 || !Number.isInteger(limit) || limit < 1 || limit > 100) fail();
      const rows = db.prepare('SELECT c.*,r.data FROM changes c JOIN revisions r ON r.revision_id=c.revision_id WHERE c.origin_seq>? ORDER BY c.origin_seq LIMIT ?').all(after, limit + 1);
      const items = rows.slice(0, limit).map(v => ({ originSeq: v.origin_seq, action: v.action, record: JSON.parse(v.data) }));
      return { items, nextSeq: items.at(-1)?.originSeq ?? after, hasMore: rows.length > limit };
    }
    case 'backup': {
      fields(args, ['path']); const path = assertNewPath(args.path);
      // SQLite's backup API includes uncheckpointed WAL pages.
      const created = openSync(path, 'wx', 0o600); closeSync(created);
      await sqliteBackup(db, path); const fd = openSync(path, 'r'); try { fsyncSync(fd); } finally { closeSync(fd); }
      syncDirectory(dirname(path)); return { path, schemaVersion: 1, revision: m.revision };
    }
    case 'export': {
      fields(args, ['path', 'passphrase', 'recordIds']); passphrase(args.passphrase);
      if (!Array.isArray(args.recordIds) || !args.recordIds.length || args.recordIds.length > 1000) fail('MEMORY_EXPORT_SCOPE_REQUIRED');
      const records = [...new Set(args.recordIds.map(identity))].map(recordById);
      if (records.some(v => !v || !['active', 'candidate'].includes(v.state))) fail('MEMORY_NOT_FOUND');
      const archive = Buffer.from(canonical({ format: 'droy-memory', schemaVersion: 1, exportedAt: nowISO(), spaceId: m.space_id, records }));
      if (archive.length > 16777216) fail('MEMORY_EXPORT_TOO_LARGE');
      const path = fileWrite(args.path, encrypt(archive, args.passphrase));
      return { path, recordCount: records.length, encrypted: true };
    }
    case 'import': {
      fields(args, ['operationId', 'path', 'passphrase']); identity(args.operationId);
      if (typeof args.path !== 'string' || !isAbsolute(args.path) || regular(args.path).size > 16777300) fail('MEMORY_ARCHIVE_INVALID');
      let archive; try { archive = JSON.parse(decrypt(readFileSync(args.path), args.passphrase)); }
      catch (error) { if (error instanceof MemoryError) throw error; fail('MEMORY_ARCHIVE_INVALID'); }
      if (archive.format !== 'droy-memory' || archive.schemaVersion !== 1 || !Array.isArray(archive.records) || !archive.records.length || archive.records.length > 1000) fail('MEMORY_ARCHIVE_INVALID');
      // Import always generates local identities. External grants, node state and
      // foreign source permissions never become authority on this installation.
      return write(operation, { operationId: args.operationId, archiveHash: digest(archive) }, () => {
        if (db.prepare('SELECT COUNT(*) n FROM records').get().n !== 0) fail('MEMORY_IMPORT_REQUIRES_EMPTY_SPACE');
        const mapping = [], batchId = randomUUID(), originals = new Map(), imported = new Map();
        for (const old of archive.records) {
          identity(old.recordId); identity(old.revisionId); privateCategories(old.privacy?.categories); sourceIds(old.sourceRefs); dependencies(old.derivedFrom);
          if (originals.has(old.recordId)) fail('MEMORY_ARCHIVE_INVALID'); originals.set(old.recordId, old);
        }
        while (imported.size < originals.size) {
          let progressed = false;
          for (const old of originals.values()) {
            if (imported.has(old.recordId)) continue;
            const selectedParents = old.derivedFrom.filter(parent => originals.has(parent.recordId));
            if (selectedParents.some(parent => originals.get(parent.recordId).revisionId !== parent.revisionId)) fail('MEMORY_ARCHIVE_INVALID');
            if (selectedParents.some(parent => !imported.has(parent.recordId))) continue;
            const input = Object.fromEntries(['kind', 'payload', 'occurredAt', 'validFrom', 'validUntil', 'assertion', 'actor', 'retention', 'state'].map(k => [k, old[k]]));
            input.sourceRefs = ['import:' + batchId + ':' + old.recordId];
            input.derivedFrom = selectedParents.map(parent => { const value = imported.get(parent.recordId); return { recordId: value.recordId, revisionId: value.revisionId }; });
            const record = create(input, false, privateCategories(old.privacy.categories)); imported.set(old.recordId, record);
            mapping.push({ importedRecordId: old.recordId, ...receipt(record) }); progressed = true;
          }
          if (!progressed) fail('MEMORY_ARCHIVE_INVALID');
        }
        return { imported: mapping, sourceSpaceId: identity(archive.spaceId), destinationSpaceId: m.space_id };
      });
    }
    case 'close': db.close(); db = undefined; owner.close(); owner = undefined; return { closed: true };
    default: fail('MEMORY_INVALID_OPERATION');
  }
}

try {
  const requested = resolve(workerData.directory);
  const rawDatabase = join(requested, 'memory.sqlite'), rawMarker = requested + '.identity.json';
  if (existsSync(rawDatabase) !== existsSync(rawMarker)) fail('MEMORY_INITIALIZATION_INCOMPLETE');
  if (!existsSync(rawDatabase) && !workerData.initialize) fail('MEMORY_NOT_INITIALIZED');
  mkdirSync(requested, { recursive: true, mode: 0o700 });
  if (lstatSync(requested).isSymbolicLink()) fail('MEMORY_STORE_IDENTITY');
  directory = realpathSync(requested); databasePath = join(directory, 'memory.sqlite');
  markerPath = join(realpathSync(dirname(requested)), basename(requested) + '.identity.json');
  ownerPath = join(directory, 'memory-owner.sqlite');
  if (!existsSync(ownerPath)) {
    if (existsSync(databasePath) || existsSync(markerPath)) fail('MEMORY_OWNER_MISSING');
    try { const fd = openSync(ownerPath, 'wx', 0o600); closeSync(fd); } catch (error) { if (error.code !== 'EEXIST') throw error; }
  }
  ownerIdentity = regular(ownerPath); owner = new DatabaseSync(ownerPath);
  try { owner.exec('PRAGMA busy_timeout=0; BEGIN EXCLUSIVE;'); }
  catch (error) { if (/locked|busy/i.test(String(error.message))) fail('MEMORY_STORE_IN_USE'); fail('MEMORY_OWNER_CORRUPT'); }
  if (existsSync(databasePath) !== existsSync(markerPath)) fail('MEMORY_INITIALIZATION_INCOMPLETE');
  if (!existsSync(databasePath)) {
    marker = { schemaVersion: 1, instanceId: randomUUID(), revision: 0 }; writeMarker(marker, true);
    const fd = openSync(databasePath, 'wx', 0o600); fsyncSync(fd); closeSync(fd);
    db = new DatabaseSync(databasePath);
    db.exec(`PRAGMA application_id=${APP_ID}; PRAGMA user_version=${MEMORY_SCHEMA_VERSION};
      PRAGMA journal_mode=WAL; PRAGMA synchronous=FULL; PRAGMA foreign_keys=ON; BEGIN IMMEDIATE;
      CREATE TABLE metadata(singleton INTEGER PRIMARY KEY,instance_id TEXT NOT NULL,space_id TEXT NOT NULL,companion_id TEXT NOT NULL,node_id TEXT NOT NULL,revision INTEGER NOT NULL,origin_seq INTEGER NOT NULL,disclosure_epoch INTEGER NOT NULL);
      CREATE TABLE records(record_id TEXT PRIMARY KEY,revision_id TEXT NOT NULL,kind TEXT NOT NULL,event_type TEXT,occurred_ms INTEGER NOT NULL,state TEXT NOT NULL,expires_ms INTEGER,search_text TEXT NOT NULL,city TEXT,origin_seq INTEGER NOT NULL,data TEXT NOT NULL);
      CREATE INDEX records_time ON records(state,occurred_ms DESC,record_id);
      CREATE INDEX records_kind_time ON records(kind,event_type,state,occurred_ms);
      CREATE INDEX records_expiry ON records(state,expires_ms);
      CREATE TABLE revisions(revision_id TEXT PRIMARY KEY,record_id TEXT NOT NULL,origin_seq INTEGER NOT NULL,data TEXT NOT NULL);
      CREATE INDEX revisions_record ON revisions(record_id);
      CREATE TABLE changes(origin_seq INTEGER PRIMARY KEY,record_id TEXT NOT NULL,revision_id TEXT NOT NULL,action TEXT NOT NULL);
      CREATE TABLE receipts(operation_id TEXT PRIMARY KEY,fingerprint TEXT NOT NULL,receipt TEXT NOT NULL);
      CREATE TABLE dependencies(child_id TEXT NOT NULL,parent_id TEXT NOT NULL,parent_revision TEXT NOT NULL,PRIMARY KEY(child_id,parent_id));
      CREATE INDEX dependency_parent ON dependencies(parent_id);
      CREATE TABLE forgotten_sources(source_ref TEXT PRIMARY KEY);
      CREATE TABLE ingest_receipts(ingestion_key TEXT PRIMARY KEY,record_id TEXT NOT NULL,fingerprint TEXT NOT NULL);
      CREATE TABLE source_cursors(source TEXT PRIMARY KEY,cursor TEXT NOT NULL);`);
    db.prepare('INSERT INTO metadata VALUES(1,?,?,?,?,0,0,0)').run(marker.instanceId, randomUUID(), randomUUID(), randomUUID());
    db.exec('COMMIT'); syncDirectory(directory);
  } else {
    marker = readMarker(); regular(databasePath); db = new DatabaseSync(databasePath);
    if (db.prepare('PRAGMA user_version').get().user_version !== 1 || db.prepare('PRAGMA application_id').get().application_id !== APP_ID) fail('MEMORY_FORMAT_UNSUPPORTED');
    if (db.prepare('PRAGMA integrity_check').get().integrity_check !== 'ok') fail('MEMORY_STORE_CORRUPT');
    db.exec('PRAGMA journal_mode=WAL; PRAGMA synchronous=FULL; PRAGMA foreign_keys=ON;');
  }
  db.exec('PRAGMA busy_timeout=3000; PRAGMA secure_delete=ON;');
  databaseIdentity = regular(databasePath); check();
} catch (error) {
  failed = errorCode(error); if (db) { try { db.close(); } catch {} } if (owner) owner.close();
  parentPort.postMessage({ fatal: true, code: failed });
}
// Serial chaining includes asynchronous SQLite backup; no write can interleave
// its host-controlled destination validation and completion receipt.
let chain = Promise.resolve();
parentPort.on('message', message => {
  chain = chain.then(async () => {
    const { id, operation, args } = message;
    if (failed) { parentPort.postMessage({ id, ok: false, code: failed }); return; }
    try {
      const value = await handle(operation, args); parentPort.postMessage({ id, ok: true, value });
      if (operation === 'close') parentPort.close();
    } catch (error) {
      const code = errorCode(error);
      if (['MEMORY_STORE_IO', 'MEMORY_STORE_CORRUPT', 'MEMORY_STORE_IDENTITY', 'MEMORY_STORE_ROLLBACK', 'MEMORY_MARKER_MISSING', 'MEMORY_MARKER_CORRUPT', 'MEMORY_OWNER_MISSING'].includes(code)) failed = code;
      parentPort.postMessage({ id, ok: false, code });
    }
  });
});
