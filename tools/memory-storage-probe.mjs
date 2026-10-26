#!/usr/bin/env node
// Runs only against a new, explicitly supplied directory containing synthetic data.
import assert from 'node:assert/strict';
import { mkdir, readFile, stat, writeFile } from 'node:fs/promises';
import { resolve, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { Worker, isMainThread, parentPort, workerData } from 'node:worker_threads';
import { spawn } from 'node:child_process';
import { once } from 'node:events';

const filename = fileURLToPath(import.meta.url);

async function openDatabase(path) {
  const { DatabaseSync } = await import('node:sqlite');
  const db = new DatabaseSync(path);
  const version = db.prepare('PRAGMA user_version').get().user_version;
  if (version > 2) {
    db.close();
    throw new Error(`unsupported_schema:${version}`);
  }
  db.exec('PRAGMA busy_timeout=3000; PRAGMA foreign_keys=ON;');
  return db;
}

function commit(db, { operationId, recordId, payload, deleted = false }) {
  const prior = db.prepare('SELECT revision FROM receipts WHERE operation_id=?').get(operationId);
  if (prior) return { revision: prior.revision, duplicate: true };
  db.exec('BEGIN IMMEDIATE');
  try {
    const revision = db.prepare('SELECT COALESCE(MAX(seq),0)+1 AS next FROM changes').get().next;
    db.prepare('INSERT INTO records VALUES(?,?,?,?) ON CONFLICT(id) DO UPDATE SET payload=excluded.payload, revision=excluded.revision, deleted=excluded.deleted')
      .run(recordId, deleted ? null : payload, revision, Number(deleted));
    db.prepare('INSERT INTO changes VALUES(?,?,?)').run(revision, recordId, deleted ? 'delete' : 'put');
    db.prepare('INSERT INTO receipts VALUES(?,?)').run(operationId, revision);
    db.exec('COMMIT');
    return { revision, duplicate: false };
  } catch (error) {
    db.exec('ROLLBACK');
    throw error;
  }
}

function snapshot(db) {
  return {
    records: db.prepare('SELECT * FROM records ORDER BY id').all(),
    changes: db.prepare('SELECT * FROM changes ORDER BY seq').all(),
    receipts: db.prepare('SELECT * FROM receipts ORDER BY operation_id').all(),
    schema: db.prepare('PRAGMA user_version').get().user_version,
    integrity: db.prepare('PRAGMA integrity_check').get().integrity_check,
  };
}

async function workerMain() {
  const { DatabaseSync, backup } = await import('node:sqlite');
  const db = await openDatabase(workerData.path);
  parentPort.on('message', async ({ id, command, value }) => {
    try {
      let result;
      if (command === 'initialize') {
        db.exec(`PRAGMA journal_mode=WAL; PRAGMA synchronous=FULL; PRAGMA wal_autocheckpoint=0;
          BEGIN IMMEDIATE;
          CREATE TABLE records(id TEXT PRIMARY KEY,payload TEXT,revision INTEGER NOT NULL,deleted INTEGER NOT NULL);
          CREATE TABLE changes(seq INTEGER PRIMARY KEY,record_id TEXT NOT NULL,action TEXT NOT NULL);
          CREATE TABLE receipts(operation_id TEXT PRIMARY KEY,revision INTEGER NOT NULL);
          PRAGMA user_version=1; COMMIT; PRAGMA wal_checkpoint(TRUNCATE);`);
        result = { sqlite: db.prepare('SELECT sqlite_version() AS version').get().version,
          mode: db.prepare('PRAGMA journal_mode').get().journal_mode,
          synchronous: db.prepare('PRAGMA synchronous').get().synchronous };
      } else if (command === 'commit') {
        result = commit(db, value);
      } else if (command === 'read') {
        result = snapshot(db);
      } else if (command === 'backup') {
        const walBytes = (await stat(workerData.path + '-wal')).size;
        assert.ok(walBytes > 0, 'New commits must still be in WAL for this test');
        const pages = await backup(db, value);
        const copied = new DatabaseSync(value, { readOnly: true });
        result = { walBytes, pages, snapshot: snapshot(copied) };
        copied.close();
      } else if (command === 'reader-isolation') {
        const reader = new DatabaseSync(workerData.path, { readOnly: true });
        const before = reader.prepare('SELECT COUNT(*) AS count FROM records').get().count;
        db.exec('BEGIN IMMEDIATE');
        db.prepare('INSERT INTO records VALUES(?,?,?,0)').run('uncommitted-reader', 'synthetic', 999);
        const during = reader.prepare('SELECT COUNT(*) AS count FROM records').get().count;
        db.exec('ROLLBACK');
        reader.close();
        result = { before, during, equal: before === during };
      } else if (command === 'migration') {
        db.exec('BEGIN IMMEDIATE; ALTER TABLE records ADD COLUMN occurred_at TEXT; PRAGMA user_version=2; COMMIT;');
        let failed = false;
        db.exec('BEGIN IMMEDIATE');
        try {
          db.exec('ALTER TABLE records ADD COLUMN bad_column TEXT; PRAGMA user_version=3;');
          throw new Error('synthetic_migration_interruption');
        } catch {
          db.exec('ROLLBACK');
          failed = true;
        }
        result = { failed, schema: db.prepare('PRAGMA user_version').get().user_version,
          columns: db.prepare('PRAGMA table_info(records)').all().map(row => row.name) };
      } else if (command === 'close') {
        db.close();
        result = { closed: true };
      } else throw new Error('unknown_probe_command');
      parentPort.postMessage({ id, result });
    } catch (error) {
      parentPort.postMessage({ id, error: String(error.message) });
    }
  });
  parentPort.postMessage({ ready: true });
}

async function startWorker(path) {
  const worker = new Worker(new URL(import.meta.url), {
    workerData: { path }, resourceLimits: { maxOldGenerationSizeMb: 64, stackSizeMb: 4 },
  });
  let serial = 0;
  const pending = new Map();
  let readyResolve;
  let readyReject;
  const ready = new Promise((resolveReady, rejectReady) => { readyResolve = resolveReady; readyReject = rejectReady; });
  worker.on('message', message => {
    if (message.ready) return readyResolve();
    const request = pending.get(message.id);
    if (!request) return;
    pending.delete(message.id);
    message.error ? request.reject(new Error(message.error)) : request.resolve(message.result);
  });
  worker.on('error', error => {
    readyReject(error);
    for (const request of pending.values()) request.reject(error);
    pending.clear();
  });
  worker.on('exit', code => {
    for (const request of pending.values()) request.reject(new Error(`worker_exit:${code}`));
    pending.clear();
  });
  await ready;
  return {
    request(command, value) {
      const id = ++serial;
      return new Promise((resolveRequest, rejectRequest) => {
        pending.set(id, { resolve: resolveRequest, reject: rejectRequest });
        worker.postMessage({ id, command, value });
      });
    },
    async close() { await this.request('close'); await worker.terminate(); },
    async terminate() { await worker.terminate(); },
  };
}

async function crashWriter(directory, phase) {
  const db = await openDatabase(join(directory, 'records.db'));
  if (phase === 'after-commit') {
    // This child simulates a durable commit whose application-level reply was lost.
    commit(db, { operationId: 'lost-reply', recordId: 'lost-reply-record', payload: 'synthetic committed value' });
  } else {
    db.exec('BEGIN IMMEDIATE');
    db.prepare('INSERT INTO records(id,payload,revision,deleted) VALUES(?,?,?,0)')
      .run('unfinished-record', 'synthetic uncommitted value', 998);
  }
  process.stdout.write('ready-to-kill\n');
  setInterval(() => {}, 1000);
}

async function killWriter(directory, phase) {
  const child = spawn(process.execPath, [filename, '--crash-writer', directory, phase], { stdio: ['ignore', 'pipe', 'pipe'] });
  let stderr = '';
  child.stderr.on('data', chunk => { stderr += chunk; });
  const closed = once(child, 'close');
  const timer = setTimeout(() => child.kill('SIGKILL'), 15000);
  try {
    const ready = new Promise(resolveReady => {
      let output = '';
      child.stdout.on('data', chunk => {
        output += chunk;
        if (output.includes('\n')) resolveReady(output);
      });
    });
    const output = await Promise.race([
      ready,
      closed.then(() => { throw new Error(`crash_writer_early_exit:${stderr}`); }),
    ]);
    assert.equal(output, 'ready-to-kill\n');
    child.kill('SIGKILL');
    const [code, signal] = await closed;
    assert.equal(signal, 'SIGKILL');
    return { code, signal };
  } finally { clearTimeout(timer); }
}

async function main() {
  assert.equal(process.argv[2], '--output-dir', 'Usage: node tools/memory-storage-probe.mjs --output-dir <new-directory>');
  assert.ok(process.argv[3], 'A new output directory is required');
  const directory = resolve(process.argv[3]);
  // Exclusive creation prevents this probe from opening a user's existing database.
  await mkdir(directory, { mode: 0o700 });
  const report = { schemaVersion: 1, syntheticDataOnly: true, runtime: {
    node: process.version, platform: process.platform, arch: process.arch,
    uid: process.getuid?.(),
  }, checks: [], passed: false };
  const check = (name, details = {}) => report.checks.push({ name, passed: true, ...details });
  const path = join(directory, 'records.db');
  let worker;
  try {
    worker = await startWorker(path);
    const initialized = await worker.request('initialize');
    assert.equal(initialized.mode, 'wal');
    assert.equal(initialized.synchronous, 2);
    check('worker_sqlite_wal_full', initialized);
    const first = await worker.request('commit', { operationId: 'remember-1', recordId: 'preference-1', payload: 'synthetic preference' });
    assert.deepEqual(first, { revision: 1, duplicate: false });
    const repeated = await worker.request('commit', { operationId: 'remember-1', recordId: 'preference-1', payload: 'synthetic preference' });
    assert.deepEqual(repeated, { revision: 1, duplicate: true });
    check('durable_receipt_and_operation_deduplication');
    await worker.request('commit', { operationId: 'forget-1', recordId: 'preference-1', deleted: true });
    const saved = await worker.request('backup', join(directory, 'snapshot.db'));
    assert.equal(saved.snapshot.records[0].deleted, 1);
    assert.equal(saved.snapshot.records[0].payload, null);
    assert.equal(saved.snapshot.changes.length, 2);
    assert.equal(saved.snapshot.receipts.length, 2);
    assert.equal(saved.snapshot.integrity, 'ok');
    check('consistent_backup_before_checkpoint', { walBytes: saved.walBytes, pages: saved.pages });
    const isolation = await worker.request('reader-isolation');
    assert.equal(isolation.equal, true);
    check('concurrent_read_does_not_observe_uncommitted_write', isolation);
    await worker.close();
    worker = null;
    check('close_and_reopen', { crashSignal: await killWriter(directory, 'after-commit') });
    worker = await startWorker(path);
    let current = await worker.request('read');
    assert.equal(current.records.length, 2);
    assert.equal(current.changes.length, 3);
    assert.equal(current.receipts.length, 3);
    const retried = await worker.request('commit', { operationId: 'lost-reply', recordId: 'lost-reply-record', payload: 'synthetic committed value' });
    assert.equal(retried.duplicate, true);
    check('process_sigkill_after_commit_retry_keeps_single_revision');
    await worker.close();
    worker = null;
    await killWriter(directory, 'before-commit');
    worker = await startWorker(path);
    current = await worker.request('read');
    assert.equal(current.records.length, 2);
    assert.equal(current.changes.length, 3);
    assert.equal(current.records.some(record => record.id === 'unfinished-record'), false);
    assert.equal(current.integrity, 'ok');
    check('process_sigkill_before_commit_rolls_back_entire_transaction');
    const migrated = await worker.request('migration');
    assert.equal(migrated.schema, 2);
    assert.equal(migrated.failed, true);
    assert.ok(migrated.columns.includes('occurred_at'));
    assert.ok(!migrated.columns.includes('bad_column'));
    check('transactional_migration_and_failed_migration_rollback');
    await worker.close();
    worker = null;
    const { DatabaseSync } = await import('node:sqlite');
    const future = new DatabaseSync(join(directory, 'snapshot.db'));
    future.exec('PRAGMA user_version=99');
    future.close();
    const before = await readFile(join(directory, 'snapshot.db'));
    await assert.rejects(openDatabase(join(directory, 'snapshot.db')), /unsupported_schema:99/);
    assert.deepEqual(await readFile(join(directory, 'snapshot.db')), before);
    check('unknown_schema_refused_without_rebuilding_database');
    report.passed = true;
  } catch (error) {
    report.failure = { name: error.name, message: error.message };
    process.exitCode = 1;
  } finally {
    if (worker) await worker.terminate();
    await writeFile(join(directory, 'storage-probe.json'), JSON.stringify(report, null, 2) + '\n', { mode: 0o600 });
    console.log(JSON.stringify(report));
  }
}

if (!isMainThread) await workerMain();
else if (process.argv[2] === '--crash-writer') await crashWriter(process.argv[3], process.argv[4]);
else await main();
