// Droy's Android implementation of the MemOS StorageDb contract. This is not a
// better-sqlite3 emulation: only the pinned storage facade is supported.
import { DatabaseSync } from 'node:sqlite';

export const MARK_READY = Symbol.for('memos.storage.markReady');
export function markReady(db) { db[MARK_READY](); }
export function adaptDatabase(raw, filepath, agent) {
  let ready = false, nesting = 0;
  const db = {
    raw: { unsafeMode() { throw new Error('MEMOS_UNSUPPORTED_UNSAFE_MIGRATION'); } }, filepath, agent,
    isReady: () => ready, [MARK_READY]: () => { ready = true; },
    exec: sql => raw.exec(sql), close: () => raw.close(),
    tx(fn) {
      const nested = raw.isTransaction, savepoint = 'memos_' + ++nesting;
      raw.exec(nested ? 'SAVEPOINT ' + savepoint : 'BEGIN IMMEDIATE');
      try {
        const result = fn(db);
        if (result?.then) throw new Error('MEMOS_ASYNC_TRANSACTION');
        raw.exec(nested ? 'RELEASE SAVEPOINT ' + savepoint : 'COMMIT');
        return result;
      } catch (error) {
        if (nested) { raw.exec('ROLLBACK TO SAVEPOINT ' + savepoint); raw.exec('RELEASE SAVEPOINT ' + savepoint); }
        else if (raw.isTransaction) raw.exec('ROLLBACK');
        throw error;
      }
    },
    prepare(sql) {
      const statement = raw.prepare(sql);
      const invoke = method => args => args === undefined ? statement[method]() : Array.isArray(args)
        ? statement[method](...args) : statement[method](args);
      return { run: invoke('run'), get: invoke('get'), all: invoke('all'), iterate: invoke('iterate') };
    },
  };
  return db;
}
export function openDb({ filepath, agent, readonly = false }) {
  const raw = new DatabaseSync(filepath, { readOnly: readonly });
  raw.exec('PRAGMA foreign_keys=ON; PRAGMA busy_timeout=3000;');
  if (!readonly) raw.exec('PRAGMA journal_mode=WAL; PRAGMA synchronous=FULL; PRAGMA secure_delete=ON;');
  return adaptDatabase(raw, filepath, agent);
}
