import { parentPort, workerData } from 'node:worker_threads';
import { DatabaseSync } from 'node:sqlite';
import { randomUUID } from 'node:crypto';
import { join, dirname, relative, isAbsolute, sep, basename } from 'node:path';
import { existsSync, mkdirSync, lstatSync, readFileSync, openSync, writeFileSync,
  fsyncSync, closeSync, renameSync, rmSync, realpathSync } from 'node:fs';
import { PRIVACY_SCHEMA_VERSION, PrivacyError, sessionIdentity, categoryList,
  sourceIdentities, normalizeEndpoint, assertSessionPermission } from './policy.mjs';

const APPLICATION_ID = 1146245206;
let db, owner, marker, dbIdentity, ownerIdentity, failed;
const { initialize } = workerData;
let directory, markerPath, databasePath, ownerPath;

function code(error) { return error instanceof PrivacyError ? error.code : 'PRIVACY_STORE_IO'; }
function privateFile(path) {
  const stat = lstatSync(path);
  if (!stat.isFile() || stat.isSymbolicLink()) throw new PrivacyError('PRIVACY_STORE_IDENTITY');
  return stat;
}
function readMarker() {
  if (!existsSync(markerPath)) throw new PrivacyError('PRIVACY_MARKER_MISSING');
  const stat = privateFile(markerPath);
  if (stat.size > 1024) throw new PrivacyError('PRIVACY_MARKER_CORRUPT');
  let value;
  try { value = JSON.parse(readFileSync(markerPath, 'utf8')); } catch { throw new PrivacyError('PRIVACY_MARKER_CORRUPT'); }
  if (!value || value.schemaVersion !== PRIVACY_SCHEMA_VERSION || typeof value.instanceId !== 'string'
    || !/^[0-9a-f-]{36}$/.test(value.instanceId) || !Number.isSafeInteger(value.revision) || value.revision < 0) {
    throw new PrivacyError('PRIVACY_MARKER_CORRUPT');
  }
  return value;
}
function syncDirectory(path) { const fd = openSync(path, 'r'); try { fsyncSync(fd); } finally { closeSync(fd); } }
function writeMarker(value, exclusive = false) {
  const temporary = exclusive ? markerPath : markerPath + '.' + randomUUID() + '.tmp';
  let fd;
  try {
    fd = openSync(temporary, 'wx', 0o600);
    writeFileSync(fd, JSON.stringify(value) + '\n'); fsyncSync(fd); closeSync(fd); fd = undefined;
    if (!exclusive) renameSync(temporary, markerPath);
    syncDirectory(dirname(markerPath));
    marker = value;
  } finally {
    if (fd !== undefined) closeSync(fd);
    if (!exclusive && existsSync(temporary)) rmSync(temporary);
  }
}
function metadata() {
  const row = db.prepare('SELECT instance_id, revision FROM metadata WHERE singleton=1').get();
  if (!row || !Number.isSafeInteger(row.revision) || row.revision < 0) throw new PrivacyError('PRIVACY_STORE_CORRUPT');
  return row;
}
function checkOwnerIdentity() {
  if (!existsSync(ownerPath)) throw new PrivacyError('PRIVACY_OWNER_MISSING');
  const current = privateFile(ownerPath);
  if (current.ino !== ownerIdentity.ino || current.dev !== ownerIdentity.dev) throw new PrivacyError('PRIVACY_STORE_IDENTITY');
}
function checkIdentity() {
  checkOwnerIdentity();
  const current = readMarker();
  if (!existsSync(databasePath)) throw new PrivacyError('PRIVACY_DATABASE_MISSING');
  const identity = privateFile(databasePath);
  if (identity.ino !== dbIdentity.ino || identity.dev !== dbIdentity.dev) throw new PrivacyError('PRIVACY_STORE_IDENTITY');
  const meta = metadata();
  if (meta.instance_id !== current.instanceId || current.instanceId !== marker.instanceId) throw new PrivacyError('PRIVACY_STORE_IDENTITY');
  if (current.revision > meta.revision) throw new PrivacyError('PRIVACY_STORE_ROLLBACK');
  marker = current;
  if (current.revision < meta.revision) writeMarker({ ...current, revision: meta.revision });
  return meta.revision;
}
function transaction(operation) {
  db.exec('BEGIN IMMEDIATE');
  let result;
  try {
    const revision = metadata().revision + 1;
    result = operation(revision);
    db.prepare('UPDATE metadata SET revision=? WHERE singleton=1').run(revision);
    db.exec('COMMIT');
    // The durable watermark detects restoring an older DB while the independent
    // host marker still knows about acknowledged privacy decisions.
    checkOwnerIdentity();
    writeMarker({ ...marker, revision });
    return result;
  } catch (error) {
    if (db.isTransaction) db.exec('ROLLBACK');
    throw error;
  }
}
function session(sessionId) {
  sessionIdentity(sessionId);
  const row = db.prepare('SELECT state, categories, source_refs, revision FROM sessions WHERE session_id=?').get(sessionId);
  if (!row) return { sessionId, state: 'unknown', categories: [], sourceRefs: [], revision: 0 };
  let parsedCategories, sourceRefs;
  try {
    parsedCategories = categoryList(JSON.parse(row.categories), row.state !== 'protected');
    sourceRefs = sourceIdentities(JSON.parse(row.source_refs));
  } catch { throw new PrivacyError('PRIVACY_STORE_CORRUPT'); }
  if (!['known-clean', 'protected', 'forgotten'].includes(row.state)) throw new PrivacyError('PRIVACY_STORE_CORRUPT');
  if (row.state === 'known-clean' && (parsedCategories.length || sourceRefs.length)) throw new PrivacyError('PRIVACY_STORE_CORRUPT');
  return { sessionId, state: row.state, categories: parsedCategories, sourceRefs, revision: row.revision };
}
function saveSession(value, revision) {
  db.prepare('INSERT INTO sessions(session_id,state,categories,source_refs,revision) VALUES(?,?,?,?,?) '
    + 'ON CONFLICT(session_id) DO UPDATE SET state=excluded.state,categories=excluded.categories,source_refs=excluded.source_refs,revision=excluded.revision')
    .run(value.sessionId, value.state, JSON.stringify(value.categories), JSON.stringify(value.sourceRefs), revision);
  db.prepare('DELETE FROM session_sources WHERE session_id=?').run(value.sessionId);
  const addSource = db.prepare('INSERT INTO session_sources(session_id,source_ref) VALUES(?,?)');
  for (const source of value.sourceRefs) addSource.run(value.sessionId, source);
  return { ...value, revision };
}
function assertSourcesNotForgotten(sources) {
  if (!sources.length) return;
  const row = db.prepare(`SELECT 1 FROM forgotten_sources WHERE source_ref IN (${sources.map(() => '?').join(',')}) LIMIT 1`).get(...sources);
  if (row) throw new PrivacyError('PRIVACY_SOURCE_FORGOTTEN');
}
function mergedSession(first, second) {
  return { sessionId: second.sessionId,
    state: [first.state, second.state].includes('forgotten') ? 'forgotten'
      : [first.state, second.state].includes('protected') ? 'protected' : 'known-clean',
    categories: categoryList([...new Set([...first.categories, ...second.categories])], true),
    sourceRefs: sourceIdentities([...new Set([...first.sourceRefs, ...second.sourceRefs])]),
  };
}
function grantRow(row) {
  if (!row) return undefined;
  try {
    return { ...normalizeEndpoint({ url: row.url, purpose: row.purpose, configVersion: row.config_version }),
      categories: categoryList(JSON.parse(row.categories)), revision: row.revision };
  } catch { throw new PrivacyError('PRIVACY_STORE_CORRUPT'); }
}
function handle(operation, args) {
  const revision = checkIdentity();
  switch (operation) {
    case 'status': return { schemaVersion: PRIVACY_SCHEMA_VERSION, instanceId: marker.instanceId, revision };
    case 'session': return session(args.sessionId);
    case 'register-clean': {
      const previous = session(args.sessionId);
      if (previous.state === 'known-clean') return previous;
      if (previous.state !== 'unknown') throw new PrivacyError('PRIVACY_SESSION_DOWNGRADE');
      return transaction(next => saveSession({ ...previous, state: 'known-clean' }, next));
    }
    case 'protect': {
      const previous = session(args.sessionId);
      if (previous.state === 'forgotten') throw new PrivacyError('PRIVACY_SESSION_FORGOTTEN');
      const value = mergedSession(previous, { sessionId: args.sessionId, state: 'protected',
        categories: categoryList(args.categories), sourceRefs: sourceIdentities(args.sourceRefs) });
      assertSourcesNotForgotten(value.sourceRefs);
      return transaction(next => saveSession(value, next));
    }
    case 'inherit': {
      const source = session(args.parentId), target = session(args.childId);
      if (source.state === 'unknown') throw new PrivacyError('PRIVACY_SESSION_UNKNOWN');
      const value = mergedSession(source, target);
      if (value.state !== 'forgotten') assertSourcesNotForgotten(value.sourceRefs);
      return transaction(next => saveSession(value, next));
    }
    case 'forget': {
      if (!Array.isArray(args.sessionIds) || args.sessionIds.length === 0 || args.sessionIds.length > 128) throw new PrivacyError('PRIVACY_INVALID_INPUT');
      const ids = [...new Set(args.sessionIds.map(sessionIdentity))];
      return transaction(next => ids.map(id => saveSession({ ...session(id), state: 'forgotten' }, next)));
    }
    case 'forget-sources': {
      const sources = sourceIdentities(args.sourceRefs);
      if (!sources.length) throw new PrivacyError('PRIVACY_INVALID_SOURCES');
      const placeholders = sources.map(() => '?').join(',');
      return transaction(next => {
        const mark = db.prepare('INSERT INTO forgotten_sources(source_ref,revision) VALUES(?,?) ON CONFLICT(source_ref) DO NOTHING');
        for (const source of sources) mark.run(source, next);
        return { forgottenCount: db.prepare(`UPDATE sessions SET state='forgotten',revision=?
          WHERE state<>'forgotten' AND session_id IN (SELECT session_id FROM session_sources WHERE source_ref IN (${placeholders}))`)
          .run(next, ...sources).changes, revision: next };
      });
    }
    case 'grant': {
      const endpoint = normalizeEndpoint(args.endpoint), allowed = categoryList(args.categories);
      return transaction(next => {
        const existing = db.prepare('SELECT 1 FROM grants WHERE url=? AND purpose=? AND config_version=?')
          .get(endpoint.url, endpoint.purpose, endpoint.configVersion);
        if (!existing && db.prepare('SELECT COUNT(*) AS count FROM grants').get().count >= 256) throw new PrivacyError('PRIVACY_GRANT_LIMIT');
        db.prepare('INSERT INTO grants(url,purpose,config_version,categories,revision) VALUES(?,?,?,?,?) '
          + 'ON CONFLICT(url,purpose,config_version) DO UPDATE SET categories=excluded.categories,revision=excluded.revision')
          .run(endpoint.url, endpoint.purpose, endpoint.configVersion, JSON.stringify(allowed), next);
        return { ...endpoint, categories: allowed, revision: next };
      });
    }
    case 'revoke': {
      const endpoint = normalizeEndpoint(args.endpoint);
      return transaction(next => ({ revoked: db.prepare('DELETE FROM grants WHERE url=? AND purpose=? AND config_version=?')
        .run(endpoint.url, endpoint.purpose, endpoint.configVersion).changes > 0, revision: next }));
    }
    case 'grants': {
      const rows = db.prepare('SELECT * FROM grants ORDER BY url,purpose,config_version LIMIT 257').all();
      if (rows.length > 256) throw new PrivacyError('PRIVACY_STORE_CORRUPT');
      return rows.map(grantRow);
    }
    case 'authorize': {
      const value = session(args.sessionId), endpoint = normalizeEndpoint(args.endpoint);
      const grant = grantRow(db.prepare('SELECT * FROM grants WHERE url=? AND purpose=? AND config_version=?')
        .get(endpoint.url, endpoint.purpose, endpoint.configVersion));
      assertSessionPermission(value, grant);
      return { allowed: true, state: value.state, revision };
    }
    case 'close': db.close(); db = undefined; owner.close(); owner = undefined; return { closed: true };
    default: throw new PrivacyError('PRIVACY_INVALID_OPERATION');
  }
}

try {
  const rawDirectory = workerData.directory, rawMarker = workerData.markerPath;
  let dbExists = existsSync(join(rawDirectory, 'privacy.sqlite'));
  const markerExists = existsSync(rawMarker);
  if (dbExists !== markerExists) throw new PrivacyError('PRIVACY_INITIALIZATION_INCOMPLETE');
  if (!dbExists && !initialize) throw new PrivacyError('PRIVACY_NOT_INITIALIZED');
  mkdirSync(rawDirectory, { recursive: true, mode: 0o700 });
  mkdirSync(dirname(rawMarker), { recursive: true, mode: 0o700 });
  if (lstatSync(rawDirectory).isSymbolicLink()) throw new PrivacyError('PRIVACY_STORE_IDENTITY');
  directory = realpathSync(rawDirectory);
  markerPath = join(realpathSync(dirname(rawMarker)), basename(rawMarker));
  const relativeMarker = relative(directory, markerPath);
  if (!relativeMarker.startsWith('..' + sep) && !isAbsolute(relativeMarker)) throw new PrivacyError('PRIVACY_MARKER_NOT_INDEPENDENT');
  databasePath = join(directory, 'privacy.sqlite');
  // SQLite's process-lifetime lock avoids stale PID files. A separate lock DB
  // leaves the record DB available to consistent read-only backup operations.
  ownerPath = join(directory, 'privacy-owner.sqlite');
  if (!existsSync(ownerPath)) {
    if (dbExists || existsSync(markerPath)) throw new PrivacyError('PRIVACY_OWNER_MISSING');
    try { const fd = openSync(ownerPath, 'wx', 0o600); closeSync(fd); }
    catch (error) { if (error.code !== 'EEXIST') throw error; }
  }
  ownerIdentity = privateFile(ownerPath);
  owner = new DatabaseSync(ownerPath);
  try { owner.exec('PRAGMA busy_timeout=0; BEGIN EXCLUSIVE;'); }
  catch (error) {
    if (/locked|busy/i.test(String(error.message))) throw new PrivacyError('PRIVACY_STORE_IN_USE');
    throw error;
  }
  dbExists = existsSync(databasePath);
  if (dbExists !== existsSync(markerPath)) throw new PrivacyError('PRIVACY_INITIALIZATION_INCOMPLETE');
  if (!dbExists && !initialize) throw new PrivacyError('PRIVACY_NOT_INITIALIZED');
  if (!dbExists) {
    marker = { schemaVersion: PRIVACY_SCHEMA_VERSION, instanceId: randomUUID(), revision: 0 };
    writeMarker(marker, true);
    const fd = openSync(databasePath, 'wx', 0o600); fsyncSync(fd); closeSync(fd);
    db = new DatabaseSync(databasePath);
    db.exec(`PRAGMA application_id=${APPLICATION_ID}; PRAGMA user_version=${PRIVACY_SCHEMA_VERSION};
      PRAGMA journal_mode=WAL; PRAGMA synchronous=FULL; PRAGMA foreign_keys=ON; BEGIN IMMEDIATE;
      CREATE TABLE metadata(singleton INTEGER PRIMARY KEY CHECK(singleton=1),instance_id TEXT NOT NULL,revision INTEGER NOT NULL CHECK(revision>=0));
      CREATE TABLE sessions(session_id TEXT PRIMARY KEY,state TEXT NOT NULL CHECK(state IN ('known-clean','protected','forgotten')),
        categories TEXT NOT NULL,source_refs TEXT NOT NULL,revision INTEGER NOT NULL);
      CREATE TABLE session_sources(session_id TEXT NOT NULL REFERENCES sessions(session_id),source_ref TEXT NOT NULL,
        PRIMARY KEY(session_id,source_ref));
      CREATE INDEX source_sessions ON session_sources(source_ref,session_id);
      CREATE TABLE forgotten_sources(source_ref TEXT PRIMARY KEY,revision INTEGER NOT NULL);
      CREATE TABLE grants(url TEXT NOT NULL,purpose TEXT NOT NULL,config_version TEXT NOT NULL,categories TEXT NOT NULL,
        revision INTEGER NOT NULL,PRIMARY KEY(url,purpose,config_version));`);
    db.prepare('INSERT INTO metadata VALUES(1,?,0)').run(marker.instanceId);
    db.exec('COMMIT'); syncDirectory(directory);
  } else {
    marker = readMarker(); privateFile(databasePath);
    db = new DatabaseSync(databasePath);
    if (db.prepare('PRAGMA user_version').get().user_version !== PRIVACY_SCHEMA_VERSION
      || db.prepare('PRAGMA application_id').get().application_id !== APPLICATION_ID) throw new PrivacyError('PRIVACY_FORMAT_UNSUPPORTED');
    if (db.prepare('PRAGMA integrity_check').get().integrity_check !== 'ok') throw new PrivacyError('PRIVACY_STORE_CORRUPT');
    const tables = db.prepare("SELECT name FROM sqlite_master WHERE type='table'").all().map(row => row.name);
    if (!['metadata', 'sessions', 'session_sources', 'forgotten_sources', 'grants'].every(name => tables.includes(name))) throw new PrivacyError('PRIVACY_FORMAT_UNSUPPORTED');
    db.exec('PRAGMA journal_mode=WAL; PRAGMA synchronous=FULL; PRAGMA foreign_keys=ON;');
  }
  db.exec('PRAGMA busy_timeout=3000;');
  dbIdentity = privateFile(databasePath);
  checkIdentity();
} catch (error) {
  failed = code(error);
  if (db) { try { db.close(); } catch { /* Already unusable; never acknowledge a request. */ } }
  if (owner) owner.close();
  parentPort.postMessage({ fatal: true, code: failed });
}

parentPort.on('message', ({ id, operation, args }) => {
  if (failed) { parentPort.postMessage({ id, ok: false, code: failed }); return; }
  try {
    const value = handle(operation, args);
    parentPort.postMessage({ id, ok: true, value });
    if (operation === 'close') parentPort.close();
  } catch (error) {
    const failure = code(error);
    if (!['PRIVACY_INVALID_INPUT', 'PRIVACY_INVALID_SESSION', 'PRIVACY_INVALID_SOURCES', 'PRIVACY_SOURCE_LIMIT', 'PRIVACY_INVALID_CATEGORIES',
      'PRIVACY_INVALID_ENDPOINT', 'PRIVACY_SESSION_DOWNGRADE', 'PRIVACY_SESSION_UNKNOWN', 'PRIVACY_SESSION_FORGOTTEN',
      'PRIVACY_ENDPOINT_NOT_AUTHORIZED', 'PRIVACY_SOURCE_FORGOTTEN', 'PRIVACY_GRANT_LIMIT'].includes(failure)) failed = failure;
    parentPort.postMessage({ id, ok: false, code: failure });
  }
});
