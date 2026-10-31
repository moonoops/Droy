import { DatabaseSync } from 'node:sqlite';
import { mkdirSync, chmodSync } from 'node:fs';
import { isAbsolute, join } from 'node:path';

const phases = new Set(['running', 'pausing', 'paused', 'resuming', 'stopping', 'stopped']);
const APPLICATION_ID = 1146372929;
const identity = value => typeof value === 'string' && /^[A-Za-z0-9][A-Za-z0-9_.:-]{0,255}$/u.test(value);
const fail = code => { throw Object.assign(new Error(code), { code }); };
const clone = value => value === undefined ? undefined : structuredClone(value);

/** Product control metadata is separate from engine configuration and conversation history. */
export class TaskControlStore {
  #db;
  constructor(directory) {
    if (typeof directory !== 'string' || !isAbsolute(directory)) fail('TASK_CONTROL_STORAGE_INVALID');
    mkdirSync(directory, { recursive: true, mode: 0o700 });
    const file = join(directory, 'task-control.sqlite');
    this.#db = new DatabaseSync(file);
    chmodSync(file, 0o600);
    const applicationId = this.#db.prepare('PRAGMA application_id').get().application_id;
    const version = this.#db.prepare('PRAGMA user_version').get().user_version;
    if (applicationId !== 0 && applicationId !== APPLICATION_ID || version > 1) fail('TASK_CONTROL_STORAGE_INVALID');
    if (applicationId === 0 && this.#db.prepare("SELECT name FROM sqlite_master WHERE type='table'").all().length)
      fail('TASK_CONTROL_STORAGE_INVALID');
    this.#db.exec(`PRAGMA application_id=${APPLICATION_ID}; PRAGMA user_version=1;`);
    this.#db.exec('PRAGMA busy_timeout=3000; PRAGMA journal_mode=WAL; PRAGMA synchronous=FULL;');
    this.#db.exec('CREATE TABLE IF NOT EXISTS controls (task_id TEXT PRIMARY KEY, revision INTEGER NOT NULL, data TEXT NOT NULL)');
    this.#db.exec('CREATE TABLE IF NOT EXISTS native_scopes (task_id TEXT PRIMARY KEY, data TEXT NOT NULL)');
    for (const row of this.list()) this.#validate(row);
    for (const row of this.scopes()) this.#validateScope(row);
  }
  #validate(row) {
    if (!row || !identity(row.taskId) || !phases.has(row.phase) || !Number.isSafeInteger(row.revision) || row.revision < 1
      || !Array.isArray(row.members) || !row.members.includes(row.taskId) || row.members.some(id => !identity(id))
      || new Set(row.members).size !== row.members.length) fail('TASK_CONTROL_STORAGE_INVALID');
    if (row.commandId !== undefined && !identity(row.commandId)) fail('TASK_CONTROL_STORAGE_INVALID');
  }
  #validateScope(scope) {
    if (!identity(scope?.taskId) || !identity(scope?.operationId) || !identity(scope?.rootId)
      || scope.epoch !== undefined && (!Number.isSafeInteger(scope.epoch) || scope.epoch < 0)) fail('TASK_CONTROL_STORAGE_INVALID');
  }
  get(taskId) {
    const row = this.#db.prepare('SELECT data FROM controls WHERE task_id=?').get(taskId);
    return row ? JSON.parse(row.data) : undefined;
  }
  list() { return this.#db.prepare('SELECT data FROM controls ORDER BY rowid').all().map(row => JSON.parse(row.data)); }
  scopes() { return this.#db.prepare('SELECT data FROM native_scopes ORDER BY rowid').all().map(row => JSON.parse(row.data)); }
  putScope(scope) {
    this.#validateScope(scope);
    this.#db.prepare('INSERT INTO native_scopes(task_id,data) VALUES(?,?) ON CONFLICT(task_id) DO UPDATE SET data=excluded.data')
      .run(scope.taskId, JSON.stringify(scope));
    return clone(scope);
  }
  deleteScope(taskId) { this.#db.prepare('DELETE FROM native_scopes WHERE task_id=?').run(taskId); }
  put(next, expectedRevision) {
    this.#validate(next);
    this.#db.exec('BEGIN IMMEDIATE');
    try {
      const previous = this.get(next.taskId);
      if ((previous?.revision ?? 0) !== expectedRevision || next.revision !== expectedRevision + 1) fail('TASK_CONTROL_CHANGED');
      this.#db.prepare('INSERT INTO controls(task_id,revision,data) VALUES(?,?,?) ON CONFLICT(task_id) DO UPDATE SET revision=excluded.revision,data=excluded.data')
        .run(next.taskId, next.revision, JSON.stringify(next));
      this.#db.exec('COMMIT');
      return clone(next);
    } catch (error) { this.#db.exec('ROLLBACK'); throw error; }
  }
  close() { this.#db.close(); }
}
