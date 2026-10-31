import { parentPort, workerData } from 'node:worker_threads';
import { DatabaseSync } from 'node:sqlite';
import { randomUUID, createHash } from 'node:crypto';
import { join, dirname, basename, resolve } from 'node:path';
import { existsSync, mkdirSync, lstatSync, realpathSync, readFileSync, writeFileSync, openSync,
  closeSync, fsyncSync, renameSync, rmSync, readdirSync, statfsSync } from 'node:fs';
import { MemoryError, fail, fields, identity, text, timestamp, sourceIds, normalizeRecord, canonical, queryInput } from '../schema.mjs';
import { adaptDatabase } from './upstream/core/storage/connection.mjs';
import { runMigrations } from './upstream/core/storage/migrator.mjs';
import { makeTracesRepo } from './upstream/core/storage/repos/traces.mjs';
import { prepareFtsMatch, extractPatternTerms } from './upstream/core/storage/keyword.mjs';

const APP_ID = 1146244434;
const UPSTREAM = '64388b50e2a15528bcdf032e65a1ac792bcf63ee';
let db, owner, traces, directory, databasePath, ownerPath, markerPath, marker, databaseIdentity, ownerIdentity, failed;
const digest = value => createHash('sha256').update(canonical(value)).digest('hex');
const nowISO = () => new Date().toISOString();
const errorCode = error => error instanceof MemoryError ? error.code : 'MEMORY_STORE_IO';
function regular(path) { const s = lstatSync(path); if (!s.isFile() || s.isSymbolicLink() || s.nlink !== 1) fail('MEMORY_STORE_IDENTITY'); return s; }
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
  if (m.space_id !== workerData.memorySpaceId || m.companion_id !== workerData.companionId) fail('MEMORY_SPACE_MISMATCH');
  if (saved.revision > m.revision) fail('MEMORY_STORE_ROLLBACK');
  if (saved.revision < m.revision) writeMarker({ ...saved, revision: m.revision });
  return m;
}
function transaction(callback) {
  db.exec('BEGIN IMMEDIATE');
  try {
    const result = callback(); db.prepare('UPDATE metadata SET revision=revision+1 WHERE singleton=1').run(); db.exec('COMMIT');
    writeMarker({ ...marker, revision: meta().revision }); return result;
  } catch (error) { if (db.isTransaction) db.exec('ROLLBACK'); throw error; }
}
function totalBytes(path = directory) {
  let sum = 0;
  for (const item of readdirSync(path, { withFileTypes: true })) {
    const child = join(path, item.name); if (item.isSymbolicLink()) fail('MEMORY_STORE_IDENTITY');
    if (item.isDirectory()) sum += totalBytes(child); else if (item.isFile()) sum += lstatSync(child).size;
  } return sum;
}
function capacity(reserve = 262144) {
  const bytes = totalBytes(), stat = statfsSync(directory), free = stat.bavail * stat.bsize;
  return { bytes, maxBytes: workerData.maxBytes, freeBytes: free, minFreeBytes: workerData.minFreeBytes,
    limited: bytes >= workerData.maxBytes - reserve || free < workerData.minFreeBytes + reserve };
}
function recordById(id) {
  identity(id); const row = db.prepare('SELECT data FROM records WHERE record_id=?').get(id); if (!row) return null;
  let record; try { record = JSON.parse(row.data); } catch { fail('MEMORY_STORE_CORRUPT'); }
  if (record.payload !== null) {
    const trace = traces.getById(id);
    if (!trace || trace.ownerProfileId !== workerData.memorySpaceId || trace.ownerAgentKind !== 'droy-companion') fail('MEMORY_STORE_CORRUPT');
    record.payload = { ...record.payload, text: trace.userText };
  }
  return record;
}
function nextSequence() { db.prepare('UPDATE metadata SET origin_seq=origin_seq+1 WHERE singleton=1').run(); return meta().origin_seq; }
function receipt(record) { return { recordId: record.recordId, revisionId: record.revisionId, originSeq: record.originSeq }; }
function traceRecord(record) {
  return { id: record.recordId, sessionId: 'droy:' + workerData.memorySpaceId, episodeId: 'droy-memory:' + workerData.memorySpaceId,
    ownerAgentKind: 'droy-companion', ownerProfileId: workerData.memorySpaceId, ownerWorkspaceId: null,
    ts: Date.parse(record.occurredAt), userText: record.payload.text, agentText: '', summary: null,
    value: 0, alpha: 0, priority: 0, tags: [record.kind], vecSummary: null, vecAction: null,
    share: { scope: 'private' }, turnId: Date.parse(record.occurredAt), schemaVersion: 1 };
}
function save(record, action, { revision = true } = {}) {
  // Current prose lives in the upstream trace repository. Product metadata and
  // historical revisions supply contracts MemOS does not yet implement.
  const metadata = structuredClone(record);
  if (record.payload !== null) {
    delete metadata.payload.text;
    // SQLite REPLACE does not fire delete triggers unless recursive_triggers
    // is enabled. Explicit deletion prevents stale FTS rows after a revision.
    traces.deleteById(record.recordId);
    traces.insert(traceRecord(record));
  } else traces.deleteById(record.recordId);
  db.prepare('DELETE FROM droy_embeddings WHERE record_id=?').run(record.recordId);
  db.prepare(`INSERT INTO records VALUES(?,?,?,?,?,?,?,?,?,?,?) ON CONFLICT(record_id) DO UPDATE SET revision_id=excluded.revision_id,
    kind=excluded.kind,event_type=excluded.event_type,occurred_ms=excluded.occurred_ms,state=excluded.state,expires_ms=excluded.expires_ms,
    search_text=excluded.search_text,city=excluded.city,origin_seq=excluded.origin_seq,data=excluded.data`).run(
    record.recordId, record.revisionId, record.kind, record.payload?.eventType ?? null, Date.parse(record.occurredAt), record.state,
    null, '', null, record.originSeq, canonical(metadata));
  if (revision) {
    db.prepare('INSERT INTO revisions VALUES(?,?,?,?)').run(record.revisionId, record.recordId, record.originSeq, canonical(record));
    db.prepare('INSERT INTO changes(origin_seq,record_id,revision_id,action) VALUES(?,?,?,?)').run(record.originSeq, record.recordId, record.revisionId, action);
  }
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
  input.privacy.categories = [...categories].sort(); return result;
}
function assertSources(input) {
  for (const source of [...input.sourceRefs, ...input.lineageRefs]) {
    if (source.startsWith('im:') && !source.startsWith('im:' + workerData.companionId + ':')) fail('COMPANION_SOURCE_SCOPE');
    if (db.prepare('SELECT 1 FROM forgotten_sources WHERE source_ref=?').get(source)) fail('MEMORY_SOURCE_FORGOTTEN');
    if (db.prepare('SELECT 1 FROM droy_suppressed_sources WHERE source_ref=?').get(source)) fail('MEMORY_SOURCE_SUPPRESSED');
  }
}
function safeText(value, maxBytes = 16384) {
  text(value, maxBytes);
  if (/(?:\bBearer\s+\S+|\bsk-[\w-]{8,}|-----BEGIN [A-Z ]*PRIVATE KEY-----|(?:api[_ -]?key|password|access[_ -]?token|密码|令牌)\s*[:=：]\s*\S+)/iu.test(value)) fail('COMPANION_MEMORY_CREDENTIAL');
  return value;
}
function evidenceFor(sources, incoming = [], previous = []) {
  if (!Array.isArray(incoming) || incoming.length > 128) fail();
  const bySource = new Map(previous.map(item => [item.sourceRef, item]));
  for (const item of incoming) {
    fields(item, ['sourceRef', 'text', 'occurredAt', 'role']); identity(item.sourceRef); safeText(item.text, 196608); timestamp(item.occurredAt);
    if (item.role !== 'user' || !item.sourceRef.startsWith('im:' + workerData.companionId + ':') || !sources.includes(item.sourceRef)) fail('MEMORY_EVIDENCE_SCOPE');
    const existing = bySource.get(item.sourceRef);
    if (existing && canonical(existing) !== canonical(item)) fail('MEMORY_EVIDENCE_CONFLICT');
    bySource.set(item.sourceRef, item);
  }
  return [...bySource.values()].filter(item => sources.includes(item.sourceRef));
}
function create(input, evidence) {
  const value = normalizeRecord(input, nowISO()), m = meta();
  if (typeof value.payload?.text !== 'string') fail('MEMORY_UNSUPPORTED_RECORD');
  safeText(value.payload.text); value.retention = { mode: 'manual', expiresAt: null };
  value.lineageRefs = lineage(value); assertSources(value);
  const record = { schemaVersion: 1, spaceId: m.space_id, companionId: m.companion_id, recordId: randomUUID(), revisionId: randomUUID(),
    parentRevisionIds: [], originNodeId: m.node_id, originSeq: nextSequence(), recordedAt: nowISO(), ...value,
    evidence: evidenceFor(value.sourceRefs, evidence) };
  save(record, 'record'); return record;
}
function write(operation, args, callback, allowFull = false) {
  // The previous store had no evidence envelope. Empty evidence carries no new
  // write intent and must preserve pre-migration operation fingerprints.
  const intent = Array.isArray(args.evidence) && args.evidence.length === 0 ? { ...args, evidence: undefined } : args;
  const operationId = identity(args.operationId), hash = digest({ operation, args: intent });
  const prior = db.prepare('SELECT fingerprint,receipt FROM receipts WHERE operation_id=?').get(operationId);
  if (prior) { if (prior.fingerprint !== hash) fail('MEMORY_OPERATION_CONFLICT'); return { ...JSON.parse(prior.receipt), duplicate: true }; }
  if (!allowFull && capacity(1048576 + Buffer.byteLength(canonical(args)) * 8).limited) fail('MEMORY_STORAGE_LIMIT');
  return transaction(() => {
    const result = { ...callback(), duplicate: false };
    db.prepare('INSERT INTO receipts VALUES(?,?,?)').run(operationId, hash, canonical(result)); return result;
  });
}
function purgeBody(recordId) {
  for (const row of db.prepare('SELECT revision_id,data FROM revisions WHERE record_id=?').all(recordId)) {
    const old = JSON.parse(row.data); old.payload = null; old.evidence = []; old.state = 'deleted';
    db.prepare('UPDATE revisions SET data=? WHERE revision_id=?').run(canonical(old), row.revision_id);
  }
}
function invalidate(recordId, state, visited = new Set(), affected = Object.assign([], { total: 0 })) {
  if (visited.has(recordId)) return affected; visited.add(recordId);
  const existing = recordById(recordId); if (!existing || existing.state === 'deleted') return affected;
  const children = db.prepare('SELECT child_id FROM dependencies WHERE parent_id=?').all(recordId).map(v => v.child_id);
  const updated = { ...existing, revisionId: randomUUID(), parentRevisionIds: [existing.revisionId], originSeq: nextSequence(), recordedAt: nowISO(), state };
  if (state === 'deleted') { updated.payload = null; updated.evidence = []; purgeBody(recordId); }
  save(updated, state === 'deleted' ? 'forget' : 'invalidate'); affected.total++;
  if (affected.length < 64) affected.push({ ...receipt(updated), previousRevisionId: existing.revisionId });
  for (const child of children) invalidate(child, state, visited, affected);
  return affected;
}
function where(input) {
  const terms = [], values = [];
  const list = (column, items) => { if (items) { terms.push(`${column} IN (${items.map(() => '?').join(',')})`); values.push(...items); } };
  list('state', input.states ?? ['active', 'candidate']); list('kind', input.kinds); list('event_type', input.eventTypes);
  if (input.from) { terms.push('occurred_ms>=?'); values.push(Date.parse(input.from)); }
  if (input.to) { terms.push('occurred_ms<?'); values.push(Date.parse(input.to)); }
  if (input.text) { terms.push("record_id IN (SELECT id FROM traces WHERE instr(lower(user_text),?)>0)"); values.push(input.text.toLocaleLowerCase('und')); }
  return { sql: terms.length ? ' WHERE ' + terms.join(' AND ') : '', values };
}
function bounded(records, input) {
  const items = []; let bytes = 2;
  for (const record of records) {
    const item = structuredClone(record);
    item.evidenceCount = item.evidence?.length ?? 0;
    delete item.evidence;
    if (input.projection === 'summary' && item.payload?.text && Buffer.byteLength(item.payload.text) > 512) {
      let preview = '', length = 0;
      for (const character of item.payload.text) { const size = Buffer.byteLength(character); if (length + size > 512) break; preview += character; length += size; }
      item.payload.text = preview; item.contentTruncated = true;
    }
    const size = Buffer.byteLength(canonical(item)) + 1;
    if (bytes + size > input.maxBytes) break;
    items.push(item); bytes += size;
  }
  if (!items.length && records.length) fail('MEMORY_RESULT_TOO_LARGE');
  return { items, bytes };
}
function query(args) {
  const input = queryInput(args), revision = meta().revision, fingerprint = digest({ ...input, cursor: undefined }); let after;
  if (input.cursor) {
    try { after = JSON.parse(Buffer.from(input.cursor, 'base64url').toString('utf8')); } catch { fail('MEMORY_INVALID_CURSOR'); }
    if (after.revision !== revision) fail('MEMORY_CURSOR_STALE');
    if (after.fingerprint !== fingerprint || !Number.isSafeInteger(after.time) || typeof after.id !== 'string') fail('MEMORY_INVALID_CURSOR');
  }
  const filter = where(input), total = db.prepare('SELECT COUNT(*) n FROM records' + filter.sql).get(...filter.values).n;
  const suffix = after ? ' AND (occurred_ms<? OR (occurred_ms=? AND record_id>?))' : '';
  const values = after ? [...filter.values, after.time, after.time, after.id] : filter.values;
  const rows = db.prepare('SELECT record_id,occurred_ms FROM records' + filter.sql + suffix + ' ORDER BY occurred_ms DESC,record_id LIMIT ?').all(...values, input.limit + 1);
  const result = bounded(rows.slice(0, input.limit).map(row => recordById(row.record_id)), input), truncated = rows.length > result.items.length;
  const last = rows[result.items.length - 1];
  return { ...result, total, revision, truncated, coverage: { from: input.from, to: input.to, continuousObservationVerified: false },
    nextCursor: truncated && last ? Buffer.from(JSON.stringify({ revision, fingerprint, time: last.occurred_ms, id: last.record_id })).toString('base64url') : null };
}
function checkedVector(vector) {
  if (!(Array.isArray(vector) || vector instanceof Float32Array) || vector.length < 1 || vector.length > 4096 || [...vector].some(v => !Number.isFinite(v))) fail('MEMORY_INVALID_VECTOR');
  const magnitude = [...vector].reduce((sum, value) => sum + value * value, 0);
  if (!Number.isFinite(magnitude) || magnitude === 0) fail('MEMORY_INVALID_VECTOR');
  return new Float32Array(vector);
}
function search(args) {
  fields(args, ['text', 'query', 'vector', 'model', 'limit', 'maxBytes', 'kinds', 'states', 'from', 'to']);
  const { vector, model, query: queryText, text: textQuery, ...options } = args;
  const q = text(textQuery ?? queryText, 1024), input = queryInput(options);
  // Metadata restrictions are inside every upstream candidate query, before top K.
  const filter = where(input), named = {}; let index = 0;
  const sql = filter.sql.replace(/^ WHERE /, '').replace(/\?/g, () => { const key = 'droy_' + index; named[key] = filter.values[index++]; return '@' + key; });
  const owner = 'owner_agent_kind=@agent AND owner_profile_id=@space AND share_scope=\'private\'';
  const eligibility = `${owner} AND id IN (SELECT record_id FROM records WHERE ${sql}
    AND (json_extract(data,'$.validFrom') IS NULL OR julianday(json_extract(data,'$.validFrom'))<=julianday('now'))
    AND (json_extract(data,'$.validUntil') IS NULL OR julianday(json_extract(data,'$.validUntil'))>julianday('now')))`;
  const params = { ...named, agent: 'droy-companion', space: workerData.memorySpaceId };
  const k = Math.min(500, Math.max(input.limit * 4, 48)), channels = [];
  const fts = prepareFtsMatch(q);
  if (fts) channels.push(traces.searchByText(fts, k, { where: eligibility.replace(/\bid\b/g, 't.id').replace(/SELECT record_t\.id/g, 'SELECT record_id'), params }));
  const patterns = extractPatternTerms(q);
  // Exact literal also preserves punctuation-heavy identifiers and single characters.
  channels.push(traces.searchByPattern([...new Set([q, ...patterns])], k, { where: eligibility, params }));
  if (vector !== undefined) {
    text(model, 512);
    const checked = checkedVector(vector), indexed = db.prepare('SELECT dimensions FROM droy_embeddings WHERE model=? LIMIT 1').get(model);
    if (indexed && indexed.dimensions !== checked.length) fail('MEMORY_INVALID_VECTOR');
    channels.push(traces.searchByVector(checked, k, { where: eligibility + ' AND id IN (SELECT record_id FROM droy_embeddings WHERE model=@model)', params: { ...params, model } }));
  }
  const ranks = new Map();
  for (const channel of channels) channel.forEach((hit, rank) => ranks.set(hit.id, (ranks.get(hit.id) ?? 0) + 1 / (60 + rank + 1)));
  const ids = [...ranks].sort((a, b) => b[1] - a[1] || a[0].localeCompare(b[0])).map(([id]) => id);
  const result = bounded(ids.slice(0, input.limit).map(recordById), input);
  return { ...result, total: ids.length, revision: meta().revision, truncated: ids.length > result.items.length, nextCursor: null,
    retrieval: { mode: vector === undefined ? 'lexical' : 'hybrid', candidateCount: ids.length, upstream: UPSTREAM } };
}
function historicalSources(id) {
  return db.prepare("SELECT DISTINCT s.value AS source FROM revisions r,json_each(r.data,'$.sourceRefs') s WHERE r.record_id=? ORDER BY source").all(id).map(row => row.source);
}
function forgetClosure(recordId) {
  const records = new Set(), sources = new Set(), queue = [recordId];
  for (let index = 0; index < queue.length; index++) {
    const id = queue[index]; if (records.has(id)) continue; records.add(id);
    for (const row of db.prepare('SELECT child_id FROM dependencies WHERE parent_id=?').all(id)) queue.push(row.child_id);
    for (const source of historicalSources(id)) {
      if (sources.has(source)) continue; sources.add(source);
      for (const row of db.prepare(`SELECT DISTINCT r.record_id FROM revisions r,json_each(r.data,'$.sourceRefs') s WHERE s.value=?`).all(source)) queue.push(row.record_id);
    }
  }
  return { records: [...records], sources: [...sources].sort() };
}
function handle(operation, args) {
  const m = check();
  switch (operation) {
    case 'status': return { schemaVersion: 2, engine: 'memos', upstream: UPSTREAM, spaceId: m.space_id, companionId: m.companion_id, nodeId: m.node_id,
      revision: m.revision, originSeq: m.origin_seq, disclosureEpoch: m.disclosure_epoch,
      counts: Object.fromEntries(db.prepare('SELECT state,COUNT(*) n FROM records GROUP BY state').all().map(v => [v.state, v.n])),
      embedding: { indexed: db.prepare('SELECT count(*) n FROM droy_embeddings').get().n }, storage: capacity() };
    case 'record': fields(args, ['operationId', 'record', 'evidence']); return write(operation, args, () => receipt(create(args.record, args.evidence)));
    case 'get': fields(args, ['recordId']); return recordById(args.recordId);
    case 'query': return query(args);
    case 'search': return search(args);
    case 'pending-embeddings': {
      fields(args, ['model', 'limit']); text(args.model, 512);
      const limit = args.limit ?? 8;
      if (!Number.isInteger(limit) || limit < 1 || limit > 100) fail();
      const rows = db.prepare(`SELECT r.record_id FROM records r LEFT JOIN droy_embeddings e ON e.record_id=r.record_id
        WHERE r.state IN ('active','candidate') AND (e.model IS NULL OR e.model<>? OR e.revision_id<>r.revision_id)
        ORDER BY r.occurred_ms DESC,r.record_id LIMIT ?`).all(args.model, limit);
      return bounded(rows.map(row => recordById(row.record_id)), { maxBytes: 262144 });
    }
    case 'set-embedding': {
      fields(args, ['recordId', 'expectedRevisionId', 'vector', 'model']); identity(args.recordId); identity(args.expectedRevisionId); text(args.model, 512);
      const vector = checkedVector(args.vector);
      return transaction(() => {
        const record = recordById(args.recordId);
        if (!record || !['active', 'candidate'].includes(record.state) || record.revisionId !== args.expectedRevisionId) fail('MEMORY_SOURCE_CHANGED');
        const prior = db.prepare('SELECT dimensions FROM droy_embeddings WHERE model=? LIMIT 1').get(args.model);
        if (prior && prior.dimensions !== vector.length) fail('MEMORY_INVALID_VECTOR');
        traces.updateVector(record.recordId, 'vecSummary', vector);
        db.prepare('INSERT INTO droy_embeddings VALUES(?,?,?,?) ON CONFLICT(record_id) DO UPDATE SET revision_id=excluded.revision_id,model=excluded.model,dimensions=excluded.dimensions').run(record.recordId, record.revisionId, args.model, vector.length);
        return { ...receipt(record), indexed: true };
      });
    }
    case 'revise': {
      fields(args, ['operationId', 'recordId', 'expectedRevisionId', 'patch', 'evidence']); identity(args.recordId); identity(args.expectedRevisionId);
      return write(operation, args, () => {
        const old = recordById(args.recordId); if (!old || !['active', 'candidate'].includes(old.state)) fail('MEMORY_NOT_FOUND');
        if (old.revisionId !== args.expectedRevisionId) fail('MEMORY_REVISION_CONFLICT');
        fields(args.patch, ['payload', 'occurredAt', 'validFrom', 'validUntil', 'sourceRefs', 'derivedFrom', 'assertion', 'actor', 'retention', 'state']);
        const input = Object.fromEntries(['kind', 'payload', 'occurredAt', 'validFrom', 'validUntil', 'sourceRefs', 'derivedFrom', 'assertion', 'actor', 'retention', 'state'].map(k => [k, old[k]]));
        const value = normalizeRecord({ ...input, ...args.patch }, nowISO()); safeText(value.payload.text); value.retention = { mode: 'manual', expiresAt: null };
        value.lineageRefs = lineage(value); assertSources(value); value.privacy.categories = [...new Set([...old.privacy.categories, ...value.privacy.categories])].sort();
        const descendants = db.prepare('WITH RECURSIVE children(id) AS (SELECT child_id FROM dependencies WHERE parent_id=? UNION SELECT d.child_id FROM dependencies d JOIN children c ON d.parent_id=c.id) SELECT id FROM children').all(old.recordId).map(v => v.id);
        if (value.derivedFrom.some(p => p.recordId === old.recordId || descendants.includes(p.recordId))) fail('MEMORY_DEPENDENCY_CYCLE');
        const affected = Object.assign([], { total: 0 }), visited = new Set();
        for (const id of descendants) invalidate(id, 'expired', visited, affected);
        db.prepare('UPDATE metadata SET disclosure_epoch=disclosure_epoch+1 WHERE singleton=1').run();
        const updated = { ...old, ...value, revisionId: randomUUID(), parentRevisionIds: [old.revisionId], originSeq: nextSequence(), recordedAt: nowISO(),
          evidence: evidenceFor(value.sourceRefs, args.evidence, old.evidence ?? []) };
        return { ...save(updated, 'revise'), previousRevisionId: old.revisionId, invalidated: [...affected], invalidatedCount: affected.total, invalidatedTruncated: affected.total > affected.length };
      });
    }
    case 'forget-plan': {
      fields(args, ['recordId', 'expectedRevisionId', 'afterSource', 'limit']); identity(args.recordId); identity(args.expectedRevisionId);
      const limit = args.limit ?? 64, after = args.afterSource === undefined ? '' : identity(args.afterSource);
      if (!Number.isInteger(limit) || limit < 1 || limit > 64) fail();
      const record = recordById(args.recordId); if (!record) fail('MEMORY_NOT_FOUND');
      if (record.revisionId !== args.expectedRevisionId) fail('MEMORY_REVISION_CONFLICT');
      const sources = historicalSources(record.recordId).filter(source => source > after), sourceRefs = sources.slice(0, limit);
      return { recordId: record.recordId, revisionId: record.revisionId, sourceRefs, nextSource: sourceRefs.at(-1) ?? null, hasMore: sources.length > limit, spaceId: m.space_id, disclosureEpoch: m.disclosure_epoch };
    }
    case 'suppress': case 'forget': {
      fields(args, ['operationId', 'recordId', 'expectedRevisionId']); identity(args.recordId); identity(args.expectedRevisionId);
      const result = write(operation, args, () => {
        const old = recordById(args.recordId); if (!old) fail('MEMORY_NOT_FOUND');
        if (old.revisionId !== args.expectedRevisionId) fail('MEMORY_REVISION_CONFLICT');
        const closure = operation === 'forget' ? forgetClosure(old.recordId) : null;
        const sources = closure?.sources ?? historicalSources(old.recordId), table = operation === 'forget' ? 'forgotten_sources' : 'droy_suppressed_sources';
        for (const source of [...sources, 'memory-record:' + old.recordId, 'memory-revision:' + old.revisionId]) db.prepare('INSERT OR IGNORE INTO ' + table + ' VALUES(?)').run(source);
        db.prepare('UPDATE metadata SET disclosure_epoch=disclosure_epoch+1 WHERE singleton=1').run();
        const affected = invalidate(old.recordId, operation === 'forget' ? 'deleted' : 'expired');
        if (operation === 'forget') {
          // A captured user message may support several records. Erasing its
          // evidence must also erase copies retained in those revisions.
          for (const id of closure.records) invalidate(id, 'deleted', new Set(), affected);
        }
        return { ...(affected[0] ?? receipt(old)), affected: [...affected], affectedCount: affected.total, affectedTruncated: affected.total > affected.length,
          ...(operation === 'forget' ? { forgottenSources: sources } : { suppressedSources: sources }) };
      }, true);
      if (operation === 'forget') db.exec('PRAGMA wal_checkpoint(TRUNCATE);');
      return result;
    }
    case 'close': db.close(); db = undefined; owner.close(); owner = undefined; return { closed: true };
    default: fail('MEMORY_INVALID_OPERATION');
  }
}

function productSchema() {
  db.exec(`CREATE TABLE IF NOT EXISTS metadata(singleton INTEGER PRIMARY KEY,instance_id TEXT NOT NULL,space_id TEXT NOT NULL,companion_id TEXT NOT NULL,node_id TEXT NOT NULL,revision INTEGER NOT NULL,origin_seq INTEGER NOT NULL,disclosure_epoch INTEGER NOT NULL);
    CREATE TABLE IF NOT EXISTS records(record_id TEXT PRIMARY KEY,revision_id TEXT NOT NULL,kind TEXT NOT NULL,event_type TEXT,occurred_ms INTEGER NOT NULL,state TEXT NOT NULL,expires_ms INTEGER,search_text TEXT NOT NULL,city TEXT,origin_seq INTEGER NOT NULL,data TEXT NOT NULL);
    CREATE INDEX IF NOT EXISTS records_time ON records(state,occurred_ms DESC,record_id);
    CREATE TABLE IF NOT EXISTS revisions(revision_id TEXT PRIMARY KEY,record_id TEXT NOT NULL,origin_seq INTEGER NOT NULL,data TEXT NOT NULL);
    CREATE INDEX IF NOT EXISTS revisions_record ON revisions(record_id);
    CREATE TABLE IF NOT EXISTS changes(origin_seq INTEGER PRIMARY KEY,record_id TEXT NOT NULL,revision_id TEXT NOT NULL,action TEXT NOT NULL);
    CREATE TABLE IF NOT EXISTS receipts(operation_id TEXT PRIMARY KEY,fingerprint TEXT NOT NULL,receipt TEXT NOT NULL);
    CREATE TABLE IF NOT EXISTS dependencies(child_id TEXT NOT NULL,parent_id TEXT NOT NULL,parent_revision TEXT NOT NULL,PRIMARY KEY(child_id,parent_id));
    CREATE INDEX IF NOT EXISTS dependency_parent ON dependencies(parent_id);
    CREATE TABLE IF NOT EXISTS forgotten_sources(source_ref TEXT PRIMARY KEY);
    CREATE TABLE IF NOT EXISTS droy_suppressed_sources(source_ref TEXT PRIMARY KEY);
    CREATE TABLE IF NOT EXISTS droy_embeddings(record_id TEXT PRIMARY KEY,revision_id TEXT NOT NULL,model TEXT NOT NULL,dimensions INTEGER NOT NULL);
    CREATE TABLE IF NOT EXISTS droy_memos_binding(singleton INTEGER PRIMARY KEY,space_id TEXT NOT NULL,companion_id TEXT NOT NULL,upstream TEXT NOT NULL);`);
}
function init() {
  identity(workerData.companionId); identity(workerData.memorySpaceId);
  const requested = resolve(workerData.directory), rawDatabase = join(requested, 'memory.sqlite'), rawMarker = requested + '.identity.json';
  if (existsSync(rawDatabase) !== existsSync(rawMarker)) fail('MEMORY_INITIALIZATION_INCOMPLETE');
  if (!existsSync(rawDatabase) && !workerData.initialize) fail('MEMORY_NOT_INITIALIZED');
  // Resolve only after rejecting links anywhere on the caller-selected path.
  for (let path = requested; ; path = dirname(path)) { if (existsSync(path) && lstatSync(path).isSymbolicLink()) fail('MEMORY_STORE_IDENTITY'); if (path === dirname(path)) break; }
  mkdirSync(requested, { recursive: true, mode: 0o700 }); directory = realpathSync(requested); databasePath = join(directory, 'memory.sqlite');
  markerPath = join(realpathSync(dirname(requested)), basename(requested) + '.identity.json'); ownerPath = join(directory, 'memory-owner.sqlite');
  if (!existsSync(ownerPath)) {
    if (existsSync(databasePath) || existsSync(markerPath)) fail('MEMORY_OWNER_MISSING');
    const fd = openSync(ownerPath, 'wx', 0o600); closeSync(fd);
  }
  ownerIdentity = regular(ownerPath); owner = new DatabaseSync(ownerPath);
  try { owner.exec('PRAGMA busy_timeout=0; BEGIN EXCLUSIVE;'); }
  catch (error) { if (/locked|busy/i.test(String(error.message))) fail('MEMORY_STORE_IN_USE'); fail('MEMORY_OWNER_CORRUPT'); }
  const fresh = !existsSync(databasePath);
  if (fresh) {
    marker = { schemaVersion: 1, instanceId: randomUUID(), revision: 0 }; writeMarker(marker, true);
    const fd = openSync(databasePath, 'wx', 0o600); fsyncSync(fd); closeSync(fd);
  } else marker = readMarker();
  regular(databasePath); db = new DatabaseSync(databasePath);
  const version = db.prepare('PRAGMA user_version').get().user_version;
  if (!fresh && (db.prepare('PRAGMA application_id').get().application_id !== APP_ID || ![1, 2].includes(version))) fail('MEMORY_FORMAT_UNSUPPORTED');
  if (db.prepare('PRAGMA integrity_check').get().integrity_check !== 'ok') fail('MEMORY_STORE_CORRUPT');
  db.exec('PRAGMA journal_mode=WAL; PRAGMA synchronous=FULL; PRAGMA foreign_keys=ON; PRAGMA busy_timeout=3000; PRAGMA secure_delete=ON;');
  if (!fresh) { const prior = meta(); if (prior.instance_id !== marker.instanceId || marker.revision > prior.revision) fail('MEMORY_STORE_ROLLBACK'); }
  const storage = adaptDatabase(db, databasePath, 'droy-companion');
  runMigrations(storage); productSchema(); traces = makeTracesRepo(storage);
  if (fresh) db.prepare('INSERT INTO metadata VALUES(1,?,?,?,?,0,0,0)').run(marker.instanceId, workerData.memorySpaceId, workerData.companionId, randomUUID());
  const binding = db.prepare('SELECT * FROM droy_memos_binding WHERE singleton=1').get();
  if (!fresh && version === 2 && !binding) fail('MEMORY_STORE_CORRUPT');
  if (binding && (binding.space_id !== workerData.memorySpaceId || binding.companion_id !== workerData.companionId || binding.upstream !== UPSTREAM)) fail('MEMORY_SPACE_MISMATCH');
  if (!binding) transaction(() => {
    db.prepare('UPDATE metadata SET space_id=?,companion_id=? WHERE singleton=1').run(workerData.memorySpaceId, workerData.companionId);
    const at = Date.now(), sessionId = 'droy:' + workerData.memorySpaceId, episodeId = 'droy-memory:' + workerData.memorySpaceId;
    db.prepare('INSERT INTO sessions(id,agent,owner_agent_kind,owner_profile_id,started_at,last_seen_at) VALUES(?,?,?,?,?,?)').run(sessionId, 'droy-companion', 'droy-companion', workerData.memorySpaceId, at, at);
    db.prepare('INSERT INTO episodes(id,session_id,owner_agent_kind,owner_profile_id,started_at,status) VALUES(?,?,?,?,?,?)').run(episodeId, sessionId, 'droy-companion', workerData.memorySpaceId, at, 'open');
    let after = '';
    for (;;) {
      const page = db.prepare('SELECT record_id,data FROM records WHERE record_id>? ORDER BY record_id LIMIT 100').all(after);
      if (!page.length) break;
      for (const row of page) {
        const record = JSON.parse(row.data);
        if (record.payload !== null && typeof record.payload?.text !== 'string') fail('MEMORY_MIGRATION_UNSUPPORTED_RECORD');
        if (record.sourceRefs.some(source => source.startsWith('im:') && !source.startsWith('im:' + workerData.companionId + ':'))) fail('MEMORY_MIGRATION_SCOPE');
        record.spaceId = workerData.memorySpaceId; record.companionId = workerData.companionId;
        record.retention = { mode: 'manual', expiresAt: null }; record.evidence ??= [];
        save(record, 'migrate', { revision: false });
      }
      after = page.at(-1).record_id;
    }
    db.prepare('INSERT INTO droy_memos_binding VALUES(1,?,?,?)').run(workerData.memorySpaceId, workerData.companionId, UPSTREAM);
    db.exec(`PRAGMA application_id=${APP_ID}; PRAGMA user_version=2;`);
  });
  databaseIdentity = regular(databasePath); check(); syncDirectory(directory);
}
try { init(); }
catch (error) {
  failed = errorCode(error); if (db) { try { db.close(); } catch {} } if (owner) owner.close();
  parentPort.postMessage({ fatal: true, code: failed });
}
let chain = Promise.resolve();
parentPort.on('message', message => {
  chain = chain.then(() => {
    const { id, operation, args } = message;
    if (failed) { parentPort.postMessage({ id, ok: false, code: failed }); return; }
    try {
      const value = handle(operation, args); parentPort.postMessage({ id, ok: true, value }); if (operation === 'close') parentPort.close();
    } catch (error) {
      const code = errorCode(error);
      if (['MEMORY_STORE_IO', 'MEMORY_STORE_CORRUPT', 'MEMORY_STORE_IDENTITY', 'MEMORY_STORE_ROLLBACK', 'MEMORY_MARKER_MISSING', 'MEMORY_MARKER_CORRUPT', 'MEMORY_OWNER_MISSING'].includes(code)) failed = code;
      parentPort.postMessage({ id, ok: false, code });
    }
  });
});
