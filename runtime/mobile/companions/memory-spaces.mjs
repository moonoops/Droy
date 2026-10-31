import { DatabaseSync } from 'node:sqlite';
import { randomUUID } from 'node:crypto';
import { isAbsolute, join, dirname, resolve } from 'node:path';
import { existsSync, lstatSync, realpathSync, mkdirSync, readdirSync, readFileSync,
  openSync, closeSync, fsyncSync, writeFileSync, renameSync, rmSync } from 'node:fs';

const APP_ID = 1146244435;
const queues = new Map();
const identifier = value => typeof value === 'string' && /^[A-Za-z0-9][A-Za-z0-9_.:-]{0,159}$/u.test(value);
const fail = code => { throw Object.assign(new Error('COMPANION_MEMORY_' + code), { code: 'COMPANION_MEMORY_' + code }); };
const object = value => value !== null && typeof value === 'object' && !Array.isArray(value);
const states = new Set(['active', 'archiving', 'retained', 'purging', 'purged']);
function safe(path, { tree = false } = {}) {
  for (let current = resolve(path); ; current = dirname(current)) {
    if (existsSync(current)) {
      const info = lstatSync(current);
      if (info.isSymbolicLink() || !info.isDirectory() && current !== resolve(path)) fail('LINKED_PATH');
      if (current === resolve(path) && !info.isDirectory() && (!info.isFile() || info.nlink !== 1)) fail('LINKED_PATH');
    }
    if (dirname(current) === current) break;
  }
  if (tree && existsSync(path) && lstatSync(path).isDirectory()) for (const name of readdirSync(path)) safe(join(path, name), { tree: true });
}
function sync(path) { const fd = openSync(path, 'r'); try { fsyncSync(fd); } finally { closeSync(fd); } }
function atomic(path, value) {
  safe(path); const temp = path + '.writing'; safe(temp);
  if (existsSync(temp)) rmSync(temp);
  const fd = openSync(temp, 'wx', 0o600);
  try { writeFileSync(fd, JSON.stringify(value) + '\n'); fsyncSync(fd); } finally { closeSync(fd); }
  renameSync(temp, path); sync(dirname(path));
}
function serial(root, action) {
  const result = (queues.get(root) ?? Promise.resolve()).then(action);
  const tail = result.catch(() => {}); queues.set(root, tail);
  void tail.finally(() => { if (queues.get(root) === tail) queues.delete(root); });
  return result;
}
function rowData(row) {
  if (!row) return null;
  let data; try { data = JSON.parse(row.data); } catch { fail('REGISTRY_CORRUPT'); }
  if (!object(data) || data.memorySpaceId !== row.id || data.companionId !== row.companion_id || data.state !== row.state
    || !identifier(data.memorySpaceId) || !identifier(data.companionId) || !states.has(data.state)
    || !Number.isSafeInteger(data.epoch) || data.epoch < 1 || !Number.isSafeInteger(data.revision) || data.revision < 1) fail('REGISTRY_CORRUPT');
  return data;
}
function snapshotProfile(profile, companionId) {
  if (profile === undefined || profile === null) return null;
  if (!object(profile) || profile.id !== companionId) fail('PROFILE_SCOPE');
  // Credentials, model selection and execution settings are not personality.
  return Object.fromEntries(['id', 'templateId', 'name', 'nameCustomized', 'displayName', 'personality', 'behaviorStyle', 'replyStyle',
    'personalityOverride', 'behaviorStyleOverride', 'replyStyleOverride', 'templateVersion', 'personaRevision'].filter(key => profile[key] !== undefined)
    .map(key => [key, structuredClone(profile[key])]));
}

/** Product-private identity and lifecycle. Callers still own host admission and
 * close their engines before offline migration, retain, or physical purge. */
export class CompanionMemorySpaces {
  constructor(directory) { this.directory = directory; this.vault = directory + '.memory-vault'; }
  static async open({ directory } = {}) {
    if (typeof directory !== 'string' || !isAbsolute(directory)) fail('DIRECTORY_INVALID');
    if (existsSync(directory) && lstatSync(directory).isSymbolicLink()) fail('LINKED_PATH');
    mkdirSync(directory, { recursive: true, mode: 0o700 });
    directory = realpathSync(directory);
    const result = new CompanionMemorySpaces(directory);
    await serial(result.vault, () => result.#db(() => {}));
    return result;
  }
  #db(action, { write = false } = {}) {
    safe(this.vault); mkdirSync(this.vault, { recursive: true, mode: 0o700 });
    const path = join(this.vault, 'spaces.sqlite');
    for (const suffix of ['', '-wal', '-shm', '-journal']) safe(path + suffix);
    const db = new DatabaseSync(path);
    try {
      db.exec('PRAGMA busy_timeout=3000; PRAGMA synchronous=FULL; PRAGMA secure_delete=ON;');
      const version = db.prepare('PRAGMA user_version').get().user_version;
      if (version === 0) {
        if (db.prepare("SELECT 1 FROM sqlite_master WHERE type='table'").get()) fail('REGISTRY_CORRUPT');
        db.exec(`BEGIN IMMEDIATE; CREATE TABLE spaces(id TEXT PRIMARY KEY,companion_id TEXT NOT NULL,state TEXT NOT NULL,data TEXT NOT NULL);
          CREATE TABLE bindings(companion_id TEXT PRIMARY KEY,space_id TEXT NOT NULL REFERENCES spaces(id));
          PRAGMA application_id=${APP_ID}; PRAGMA user_version=1; COMMIT;`);
        sync(this.vault);
      } else if (version !== 1 || db.prepare('PRAGMA application_id').get().application_id !== APP_ID) fail('REGISTRY_CORRUPT');
      if (write) db.exec('BEGIN IMMEDIATE');
      const value = action(db);
      if (write) db.exec('COMMIT');
      return value;
    } finally { if (db.isTransaction) db.exec('ROLLBACK'); db.close(); }
  }
  #save(db, value) { db.prepare('INSERT OR REPLACE INTO spaces VALUES(?,?,?,?)').run(value.memorySpaceId, value.companionId, value.state, JSON.stringify(value)); }
  #get(db, memorySpaceId) { return rowData(db.prepare('SELECT * FROM spaces WHERE id=?').get(memorySpaceId)); }
  #current(db, companionId) { return rowData(db.prepare('SELECT s.* FROM spaces s JOIN bindings b ON s.id=b.space_id WHERE b.companion_id=?').get(companionId)); }
  #binding(value) { return value ? { ...value, directory: join(this.vault, value.memorySpaceId, 'memory') } : null; }
  #new(db, companionId, options = {}) {
    const now = new Date().toISOString();
    const value = { memorySpaceId: options.memorySpaceId ?? randomUUID(), companionId, state: 'active', epoch: 1, revision: 1, createdAt: now,
      displayName: options.profile?.name ?? options.profile?.displayName ?? companionId,
      workspaceId: options.profile?.workspaceId ?? null, originalWorkspaceId: options.profile?.workspaceId ?? null,
      personaRevision: options.profile?.personaRevision ?? null, migration: 'pending', sourceCaptureBoundary: options.sourceCaptureBoundary ?? null };
    this.#save(db, value); db.prepare('INSERT OR REPLACE INTO bindings VALUES(?,?)').run(companionId, value.memorySpaceId);
    return value;
  }
  #migrate(value) {
    const space = join(this.vault, value.memorySpaceId); safe(space); mkdirSync(space, { recursive: true, mode: 0o700 }); sync(this.vault);
    for (const name of ['memory', 'memory.identity.json']) {
      const source = join(this.directory, value.companionId, name), target = join(space, name);
      safe(source, { tree: true }); safe(target, { tree: true });
      if (existsSync(source)) {
        if (existsSync(target)) fail('MIGRATION_CONFLICT');
        renameSync(source, target); sync(dirname(source)); sync(space);
      }
    }
    this.#db(db => { const current = this.#get(db, value.memorySpaceId); current.migration = 'complete'; this.#save(db, current); }, { write: true });
    return this.#binding({ ...value, migration: 'complete' });
  }
  #ensure(companionId, options = {}) {
    if (!identifier(companionId)) fail('SCOPE_INVALID');
    const profile = snapshotProfile(options.profile, companionId);
    const value = this.#db(db => {
      let current = this.#current(db, companionId);
      if (current && current.state !== 'active') fail('SPACE_' + current.state.toUpperCase());
      if (!current) current = this.#new(db, companionId, options);
      else if (profile) {
        const next = { displayName: options.profile.name ?? options.profile.displayName ?? companionId,
          workspaceId: options.profile.workspaceId ?? current.workspaceId, personaRevision: options.profile.personaRevision ?? current.personaRevision };
        if (Object.entries(next).some(([key, value]) => JSON.stringify(value) !== JSON.stringify(current[key]))) {
          Object.assign(current, next); current.revision++; this.#save(db, current);
        }
      }
      return current;
    }, { write: true });
    const binding = value.migration === 'complete' ? this.#binding(value) : this.#migrate(value);
    if (profile) atomic(join(this.vault, value.memorySpaceId, 'persona.json'), profile);
    return binding;
  }
  ensureActiveSpace(companionId, options) { return serial(this.vault, () => this.#ensure(companionId, options)); }
  async assertActive(binding) {
    const value = this.#db(db => this.#get(db, binding?.memorySpaceId));
    if (!value || value.companionId !== binding?.companionId || value.state !== 'active' || value.migration !== 'complete'
      || value.epoch !== binding.epoch) fail('SPACE_STALE');
    return this.#binding(value);
  }
  withActive(binding, action) {
    if (typeof action !== 'function') return Promise.reject(Object.assign(new Error('COMPANION_MEMORY_INVALID_INPUT'), { code: 'COMPANION_MEMORY_INVALID_INPUT' }));
    return serial(this.vault, async () => { await this.assertActive(binding); const result = await action(); await this.assertActive(binding); return result; });
  }
  get(memorySpaceId) { return serial(this.vault, () => this.#binding(this.#db(db => this.#get(db, memorySpaceId)))); }
  persona(memorySpaceId) {
    return serial(this.vault, () => {
      const value = this.#db(db => this.#get(db, memorySpaceId));
      if (!value || !['active','retained'].includes(value.state)) fail('PERSONA_SNAPSHOT_UNAVAILABLE');
      const path = join(this.vault, memorySpaceId, 'persona.json'); safe(path);
      if (!existsSync(path)) fail('PERSONA_SNAPSHOT_MISSING');
      let profile; try { profile = JSON.parse(readFileSync(path, 'utf8')); } catch { fail('PERSONA_SNAPSHOT_INVALID'); }
      return snapshotProfile(profile, value.companionId);
    });
  }
  current(companionId) { return serial(this.vault, () => this.#binding(this.#db(db => this.#current(db, companionId)))); }
  list({ state } = {}) {
    if (state !== undefined && !states.has(state)) return Promise.reject(Object.assign(new Error('COMPANION_MEMORY_STATE_INVALID'), { code: 'COMPANION_MEMORY_STATE_INVALID' }));
    return serial(this.vault, () => this.#db(db => db.prepare('SELECT * FROM spaces' + (state ? ' WHERE state=?' : '') + ' ORDER BY id')
      .all(...(state ? [state] : [])).map(row => {
        const value = rowData(row);
        return Object.fromEntries(['memorySpaceId','companionId','displayName','state','epoch','revision','createdAt','retainedAt','purgedAt','restoreOperation'].filter(key => value[key] !== undefined).map(key => [key, value[key]]));
      })));
  }
  stageArchive(companionId, { profile, evidence = [] } = {}) {
    return serial(this.vault, () => {
      if (!Array.isArray(evidence)) fail('INVALID_INPUT');
      const current = this.#db(db => this.#current(db, companionId));
      if (current?.state === 'retained' || current?.state === 'archiving') return this.#binding(current);
      const binding = this.#ensure(companionId, { profile });
      atomic(join(this.vault, binding.memorySpaceId, 'evidence.json'), { version: 1, companionId, sources: evidence });
      return binding;
    });
  }
  retain(companionId, { profile, evidence = [], operationId } = {}) {
    return serial(this.vault, () => {
      if (!identifier(operationId) || !Array.isArray(evidence)) fail('INVALID_INPUT');
      let value = this.#db(db => this.#current(db, companionId));
      if (value?.state === 'retained') {
        if (value.retentionOperation !== operationId) fail('OPERATION_CONFLICT');
        return this.#binding(value);
      }
      if (!value || value.state === 'active') {
        value = this.#ensure(companionId, { profile });
        this.#db(db => { value = this.#get(db, value.memorySpaceId); value.state = 'archiving'; value.epoch++; value.revision++;
          value.retentionOperation = operationId; delete value.restoreOperation; this.#save(db, value); }, { write: true });
      }
      if (value.state !== 'archiving' || value.retentionOperation !== operationId) fail('OPERATION_CONFLICT');
      const target = join(this.vault, value.memorySpaceId, 'evidence.json');
      if (!existsSync(target)) atomic(target, { version: 1, companionId, sources: evidence });
      this.#db(db => { value = this.#get(db, value.memorySpaceId); value.state = 'retained'; value.retainedAt = new Date().toISOString(); value.revision++; this.#save(db, value); }, { write: true });
      return this.#binding(value);
    });
  }
  beginRestore(memorySpaceId, { expectedRevision, operationId } = {}) {
    return serial(this.vault, () => this.#db(db => {
      if (!identifier(operationId)) fail('INVALID_INPUT');
      const value = this.#get(db, memorySpaceId); if (!value) fail('SPACE_NOT_FOUND');
      if (value.restoreOperation === operationId && ['active','retained'].includes(value.state)) return this.#binding(value);
      if (value.state !== 'retained' || value.restoreOperation) fail('OPERATION_CONFLICT');
      if (value.revision !== expectedRevision) fail('REVISION_CONFLICT');
      const path = join(this.vault, memorySpaceId, 'persona.json'); safe(path);
      if (!existsSync(path)) fail('PERSONA_SNAPSHOT_MISSING');
      value.restoreOperation = operationId; value.revision++; this.#save(db, value); return this.#binding(value);
    }, { write: true }));
  }
  restore(memorySpaceId, { workspaceId, operationId } = {}) {
    return serial(this.vault, () => {
      if (!identifier(workspaceId)) fail('SCOPE_INVALID');
      return this.#db(db => {
        const value = this.#get(db, memorySpaceId);
        if (!value || !identifier(operationId) || value.restoreOperation !== operationId) fail('OPERATION_CONFLICT');
        if (value.state === 'active' && value.workspaceId === workspaceId) return this.#binding(value);
        if (value.state !== 'retained') fail('SPACE_NOT_RETAINED');
        if (this.#current(db, value.companionId)?.memorySpaceId !== memorySpaceId) fail('OPERATION_CONFLICT');
        safe(join(this.vault, memorySpaceId), { tree: true });
        if (!existsSync(join(this.vault, memorySpaceId, 'persona.json'))) fail('PERSONA_SNAPSHOT_MISSING');
        value.state = 'active'; value.epoch++; value.revision++; value.workspaceId = workspaceId; delete value.retentionOperation;
        this.#save(db, value); return this.#binding(value);
      }, { write: true });
    });
  }
  beginPurge(memorySpaceId, { expectedRevision, operationId, sourceCaptureBoundary = null } = {}) {
    return serial(this.vault, () => this.#db(db => {
      if (!identifier(operationId)) fail('INVALID_INPUT');
      const value = this.#get(db, memorySpaceId); if (!value) fail('SPACE_NOT_FOUND');
      if (['purging','purged'].includes(value.state) && value.purgeOperation === operationId) return this.#binding(value);
      if (!['active','retained'].includes(value.state)) fail('OPERATION_CONFLICT');
      if (value.state === 'retained' && value.restoreOperation) fail('OPERATION_CONFLICT');
      if (value.revision !== expectedRevision) fail('REVISION_CONFLICT');
      const personaPath = join(this.vault, memorySpaceId, 'persona.json'); safe(personaPath);
      if (value.state === 'active' && existsSync(personaPath)) value.purgePersona = snapshotProfile(JSON.parse(readFileSync(personaPath, 'utf8')), value.companionId);
      value.sourceCaptureBoundary = sourceCaptureBoundary;
      value.purgePreviousState = value.state; value.state = 'purging'; value.purgeOperation = operationId; value.epoch++; value.revision++; delete value.restoreOperation;
      this.#save(db, value); return this.#binding(value);
    }, { write: true }));
  }
  finishPurge(memorySpaceId, { operationId, reactivate = false, sourceCaptureBoundary } = {}) {
    return serial(this.vault, () => {
      let value = this.#db(db => this.#get(db, memorySpaceId));
      if (!value || !['purging','purged'].includes(value.state) || value.purgeOperation !== operationId) fail('OPERATION_CONFLICT');
      if (reactivate && value.purgePreviousState !== 'active') fail('SPACE_NOT_ACTIVE');
      if (sourceCaptureBoundary !== undefined && value.sourceCaptureBoundary !== null
        && JSON.stringify(sourceCaptureBoundary) !== JSON.stringify(value.sourceCaptureBoundary)) fail('OPERATION_CONFLICT');
      const boundary = sourceCaptureBoundary ?? value.sourceCaptureBoundary;
      if (reactivate && !value.replacementSpaceId) {
        value.replacementSpaceId = randomUUID();
        this.#db(db => this.#save(db, value), { write: true });
      }
      if (reactivate) {
        const replacementPath = join(this.vault, value.replacementSpaceId); safe(replacementPath);
        mkdirSync(replacementPath, { recursive: true, mode: 0o700 });
        if (value.purgePersona) atomic(join(replacementPath, 'persona.json'), value.purgePersona);
        sync(this.vault);
      }
      const target = join(this.vault, memorySpaceId); safe(target, { tree: true });
      if (existsSync(target)) { rmSync(target, { recursive: true }); sync(this.vault); }
      return this.#db(db => {
        value = this.#get(db, memorySpaceId);
        if (value.state !== 'purged') { value.state = 'purged'; value.purgedAt = new Date().toISOString(); value.revision++; value.sourceCaptureBoundary = boundary; this.#save(db, value); }
        let replacement = value.replacementSpaceId ? this.#get(db, value.replacementSpaceId) : null;
        if (reactivate && !replacement) {
          replacement = this.#new(db, value.companionId, { memorySpaceId: value.replacementSpaceId, sourceCaptureBoundary: boundary,
            profile: value.purgePersona ? { ...value.purgePersona, workspaceId: value.workspaceId } : undefined }); replacement.migration = 'complete';
          replacement.displayName = value.displayName; replacement.workspaceId = value.workspaceId; replacement.originalWorkspaceId = value.originalWorkspaceId;
          this.#save(db, replacement);
        }
        delete value.purgePersona; this.#save(db, value);
        return { purged: true, memorySpaceId, replacement: this.#binding(replacement) };
      }, { write: true });
    });
  }
}
