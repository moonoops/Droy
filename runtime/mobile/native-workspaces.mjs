import { TextDecoder } from 'node:util';
import { createHash, randomUUID } from 'node:crypto';
import { constants } from 'node:fs';
import { lstat, mkdir, open, realpath, rename, unlink } from 'node:fs/promises';
import { isAbsolute, join, relative, sep } from 'node:path';
import { TYPERT as WORKSPACES } from '@deepseek-ai/dsh-api-workspace-controller/typert';
import { TYPERT as SESSIONS } from '@deepseek-ai/dsh-api-session-controller/typert';
import { resolveMobileLocale } from './locale.mjs';
import { assertUserDirectory } from './task-ownership.mjs';

const base = '/droy/native/workspaces';
const limit = 32 * 1024;
const operations = new Map([
  ['workspace.register', ['workspace', 'create', ['path']]],
  ['workspace.create', ['workspace', 'create', ['name']]],
  ['workspace.rename', ['workspace', 'rename', ['workspaceId', 'title']]],
  ['workspace.remove', ['workspace', 'delete', ['workspaceId']]],
  ['workspace.move', ['workspace', 'insertBefore', ['workspaceId', 'beforeWorkspaceId'], ['workspaceId']]],
  ['session.move', ['workspace', 'insertSessionBefore', ['workspaceId', 'sessionId', 'beforeSessionId'], ['workspaceId', 'sessionId']]],
  ['session.pin', ['workspace', 'pinSession', ['sessionId']]],
  ['session.unpin', ['workspace', 'unpinSession', ['sessionId']]],
  ['session.archive', ['workspace', 'archiveSession', ['sessionId', 'stopActivity'], ['sessionId']]],
  ['session.restore', ['workspace', 'unarchiveSession', ['sessionId']]],
  ['session.rename', ['session', 'rename', ['sessionId', 'title']]],
  ['session.fork', ['session', 'fork', ['sessionId', 'allowMigration']]],
  ['directory.create', ['directoryPicker', 'createDirectory', ['path', 'name']]],
]);
const messages = {
  invalid: ['空间请求无效。', 'The workspace request is invalid.'],
  unavailable: ['空间服务尚未就绪。', 'The workspace service is not ready.'],
  managed: ['请从伙伴页面管理此内容。', 'Manage this content from the companion page.'],
  subagent: ['此会话不能执行该操作。', 'This operation is unavailable for this session.'],
  missing: ['未找到空间接口。', 'The workspace endpoint was not found.'],
  method: ['不支持此请求方式。', 'This request method is not supported.'],
  conflict: ['请求标识已被使用。', 'This request ID has already been used.'],
  storage: ['操作记录不可用，请稍后再试。', 'Operation records are unavailable. Try again later.'],
  full: ['操作繁忙，请稍后再试。', 'Operations are busy. Try again later.'],
  failed: ['无法确认操作结果，请检查状态。', 'The operation could not be confirmed. Check its status.'],
};
class WorkspaceError extends Error {
  constructor(code, status = 400, key = 'invalid') { super(code); this.code = code; this.status = status; this.key = key; }
}
const bad = () => new WorkspaceError('NATIVE_WORKSPACES_BAD_REQUEST');
const unavailable = () => new WorkspaceError('NATIVE_WORKSPACES_NOT_READY', 503, 'unavailable');
const storageError = () => new WorkspaceError('NATIVE_WORKSPACES_STORAGE', 503, 'storage');
function fields(value, allowed, required = allowed) {
  if (!value || typeof value !== 'object' || Array.isArray(value) || Object.keys(value).some(key => !allowed.includes(key))
    || required.some(key => !Object.hasOwn(value, key))) throw bad();
}
function identifier(value) {
  if (typeof value !== 'string' || !value || value.trim() !== value || value.length > 1024 || /[\x00-\x1f\x7f]/u.test(value)) throw bad();
  return value;
}
function hostPath(value) {
  if (typeof value !== 'string' || !isAbsolute(value) || value.length > 8192 || /[\x00-\x1f\x7f]/u.test(value)) throw bad();
  return value;
}
function get(ctx, name) { return typeof ctx.get === 'function' ? ctx.get(name) : ctx[name]; }
function service(ctx, name, method) {
  const value = get(ctx, name);
  if (!value || typeof value[method] !== 'function') throw unavailable();
  return value;
}
function descriptor(namespace, method) {
  const value = (namespace === 'session' ? SESSIONS : WORKSPACES).invocations.find(row => row.namespace === namespace && row.method === method);
  if (!value) throw unavailable();
  return value;
}
function parse(namespace, method, args) {
  const spec = descriptor(namespace, method);
  try { return spec.input({ strict: true }).create().parse(Object.fromEntries(spec.parameters.map((p, i) => [p.wire, args[i]]))); }
  catch (error) { if (error?.name === 'ZodError') throw bad(); throw error; }
}
function checked(namespace, method, value) { return descriptor(namespace, method).result.create().parse(value); }
function canonical(value) {
  if (Array.isArray(value)) return value.map(canonical);
  if (value && typeof value === 'object') return Object.fromEntries(Object.keys(value).sort().map(key => [key, canonical(value[key])]));
  return value;
}
function operation(value) {
  fields(value, ['requestId', 'kind', 'args']);
  if (!/^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/u.test(value.requestId)) throw bad();
  const spec = operations.get(value.kind);
  if (!spec) throw bad();
  const [namespace, method, keys, required] = spec;
  fields(value.args, keys, required ?? keys);
  for (const key of ['workspaceId', 'beforeWorkspaceId', 'sessionId', 'beforeSessionId']) if (Object.hasOwn(value.args, key)) identifier(value.args[key]);
  if (Object.hasOwn(value.args, 'path')) hostPath(value.args.path);
  if (Object.hasOwn(value.args, 'title') && (typeof value.args.title !== 'string' || !value.args.title.trim() || value.args.title.length > 4096)) throw bad();
  if (['directory.create', 'workspace.create'].includes(value.kind) && (typeof value.args.name !== 'string' || !value.args.name.trim()
    || value.args.name.trim() === '.' || value.args.name.trim() === '..' || /[\\/\x00-\x1f\x7f]/u.test(value.args.name) || value.args.name.length > 255)) throw bad();
  if (value.kind !== 'workspace.create') parse(namespace, method, namespace === 'directoryPicker' ? [value.args.path, value.args.name] : [value.args]);
  const args = canonical(value.args);
  return { requestId: value.requestId, kind: value.kind, args,
    digest: createHash('sha256').update(JSON.stringify([value.kind, args])).digest('hex') };
}
async function body(req) {
  if (!/^application\/json(?:\s*;|\s*$)/iu.test(req.headers['content-type'] || '') || Number(req.headers['content-length']) > limit) { req.resume(); throw bad(); }
  const chunks = []; let bytes = 0;
  for await (const chunk of req) { bytes += chunk.length; if (bytes > limit) throw bad(); chunks.push(chunk); }
  try { return JSON.parse(new TextDecoder('utf-8', { fatal: true }).decode(Buffer.concat(chunks))); }
  catch { throw bad(); }
}
function reply(res, status, value) {
  if (res.destroyed || res.writableEnded) return;
  res.writeHead(status, { 'Content-Type': 'application/json; charset=utf-8', 'Cache-Control': 'no-store' }); res.end(JSON.stringify(value));
}

// Each request has a small independently read record. Old receipts are never
// evicted: eviction could replay an uncertain create or fork.
class Receipts {
  constructor(path) { this.path = path; }
  async directory() {
    if (typeof this.path !== 'string' || !isAbsolute(this.path)) throw storageError();
    await mkdir(this.path, { recursive: true, mode: 0o700 });
    if ((await lstat(this.path)).isSymbolicLink()) throw storageError();
  }
  async read(op) {
    await this.directory();
    let file;
    try {
      file = await open(join(this.path, op.requestId + '.json'), constants.O_RDONLY | constants.O_NOFOLLOW);
      const stat = await file.stat();
      if (!stat.isFile() || stat.nlink !== 1 || stat.size > limit) throw storageError();
      const value = JSON.parse(await file.readFile('utf8'));
      if (value.version !== 1 || value.requestId !== op.requestId || !['pending', 'complete', 'failed', 'unknown'].includes(value.state)
        || typeof value.digest !== 'string' || typeof value.kind !== 'string') throw storageError();
      if (value.digest !== op.digest || value.kind !== op.kind) throw new WorkspaceError('NATIVE_WORKSPACES_REQUEST_CONFLICT', 409, 'conflict');
      return value;
    } catch (error) { if (error.code === 'ENOENT') return null; if (error instanceof WorkspaceError) throw error; throw storageError(); }
    finally { await file?.close(); }
  }
  async write(value) {
    await this.directory();
    const text = JSON.stringify(value); if (Buffer.byteLength(text) > limit) throw storageError();
    const temp = join(this.path, '.' + randomUUID()); let file;
    try {
      file = await open(temp, constants.O_CREAT | constants.O_EXCL | constants.O_WRONLY, 0o600);
      await file.writeFile(text); await file.sync(); await file.close(); file = null;
      await rename(temp, join(this.path, value.requestId + '.json'));
      const directory = await open(this.path, constants.O_RDONLY);
      try { await directory.sync(); } finally { await directory.close(); }
    } finally { await file?.close(); await unlink(temp).catch(error => { if (error.code !== 'ENOENT') throw error; }); }
  }
}
function publicReceipt(value) {
  if (!value) return null;
  const { digest, version, ...receipt } = value;
  return { ...receipt, state: receipt.state === 'pending' ? 'unknown' : receipt.state };
}
async function managed(ctx) {
  const companions = service(ctx, 'droyCompanions', 'ownsSession');
  if (typeof companions.store?.profiles !== 'function') throw unavailable();
  const profiles = await companions.store.profiles();
  if (!Array.isArray(profiles) || profiles.some(profile => !profile || typeof profile !== 'object'
    || (profile.workspaceId != null && (typeof profile.workspaceId !== 'string' || !profile.workspaceId)))) throw unavailable();
  return { companions, workspaceIds: new Set(profiles.map(row => row.workspaceId).filter(Boolean)) };
}
async function baseline(ctx, signal) {
  const iterator = service(ctx, 'workspaceController', 'follow').follow(signal)[Symbol.asyncIterator]();
  try {
    const first = await iterator.next();
    const frame = checked('workspace', 'follow', first.value);
    if (frame.type !== 'baseline') throw unavailable();
    return frame.value;
  } finally { await iterator.return?.(); }
}
async function catalog(ctx, signal) {
  const { companions, workspaceIds } = await managed(ctx);
  const [value, listed] = await Promise.all([
    baseline(ctx, signal), service(ctx, 'sessionController', 'list').list({}, signal),
  ]);
  const allRows = checked('session', 'list', listed).items;
  const managedSessionIds = allRows.filter(row => companions.ownsSession(row.sessionId)).map(row => row.sessionId);
  const owned = id => companions.ownsSession(id);
  const rows = allRows.filter(row => !owned(row.sessionId));
  const items = value.items.filter(row => !workspaceIds.has(row.workspaceId))
    .map(row => ({ ...row, sessionIds: row.sessionIds.filter(id => !owned(id)) }));
  const workspaces = items.map(row => ({ id: row.workspaceId, path: row.path, title: row.title,
    sessionIds: row.sessionIds, readOnly: false }));
  const sessions = rows.map(row => ({ id: row.sessionId, title: typeof row.projections?.values?.title === 'string' ? row.projections.values.title : null,
    running: row.running, blank: row.blank, parentSessionId: row.parentSessionId ?? null, origin: row.origin ?? null,
    needsMigration: row.formatStatus === 'migration-required', readOnly: row.origin === 'subagent',
    workspaceId: workspaces.find(space => space.sessionIds.includes(row.sessionId))?.id ?? null }));
  const defaultWorkspaceId = get(ctx, 'droyInitialization')?.defaultWorkspaceId ?? null;
  return { ...value, items, archivedSessionIds: value.archivedSessionIds.filter(id => !owned(id)),
    pinnedSessionIds: value.pinnedSessionIds.filter(id => !owned(id)), managedSessionIds,
    managedWorkspaceIds: [...workspaceIds], defaultWorkspaceId: workspaceIds.has(defaultWorkspaceId) ? null : defaultWorkspaceId,
    workspaces, sessions, directoryBrowsingAvailable: get(ctx, 'directoryPicker')?.capability?.()?.kind === 'browse' };
}
async function guard(ctx, op, signal) {
  const { companions, workspaceIds } = await managed(ctx);
  const args = op.args;
  if ([args.workspaceId, args.beforeWorkspaceId].some(id => id && workspaceIds.has(id)))
    throw new WorkspaceError('COMPANION_WORKSPACE_MANAGED', 409, 'managed');
  for (const sessionId of [args.sessionId, args.beforeSessionId].filter(Boolean)) {
    if (companions.ownsSession(sessionId)) throw new WorkspaceError('COMPANION_SESSION_MANAGED', 409, 'managed');
    const snapshot = await service(ctx, 'sessionPersistence', 'stat').stat(sessionId, { signal });
    if (!snapshot) throw new WorkspaceError('session/not-found', 404, 'subagent');
    if (snapshot.header?.id !== sessionId) throw unavailable();
    if (snapshot.header.delegationDepth !== undefined && (!Number.isSafeInteger(snapshot.header.delegationDepth) || snapshot.header.delegationDepth < 0)) throw unavailable();
    if (snapshot.header.origin === 'subagent' || snapshot.header.delegationDepth > 0)
      throw new WorkspaceError('NATIVE_WORKSPACES_SUBAGENT', 409, 'subagent');
  }
  if (args.path) {
    const path = await realpath(hostPath(args.path));
    try { await assertUserDirectory(ctx, path, companions); }
    catch (error) {
      if (error.code === 'COMPANION_WORKSPACE_MANAGED') throw new WorkspaceError(error.code, 409, 'managed');
      throw error;
    }
    const value = await baseline(ctx, signal);
    for (const row of value.items.filter(row => workspaceIds.has(row.workspaceId))) {
      const root = await realpath(row.path), part = relative(root, path);
      if (!part || part !== '..' && !part.startsWith('..' + sep) && !isAbsolute(part))
        throw new WorkspaceError('COMPANION_WORKSPACE_MANAGED', 409, 'managed');
    }
  }
}
function increasedTitle(title) {
  const ascii = /^(.*?)\((\d+)\)$/u.exec(title), wide = /^(.*?)（(\d+)）$/u.exec(title);
  if (ascii) return `${ascii[1]}(${BigInt(ascii[2]) + 1n})`;
  if (wide) return `${wide[1]}（${BigInt(wide[2]) + 1n}）`;
  return `${title} (1)`;
}
async function checkpoint(ctx, sessionId) {
  const sessions = service(ctx, 'sessions', 'flush');
  const live = sessions.get(sessionId);
  if (!live || await sessions.flush(live) !== true) throw unavailable();
}
// These SDK errors have explicit non-mutating rejection semantics. Unknown
// transport/storage failures remain uncertain rather than being retried.
const refusal = new Set(['workspace/session-active', 'workspace/name-conflict', 'workspace/not-found', 'workspace/move-invalid',
  'workspace/session-not-found', 'workspace/session-archived', 'session/not-found', 'session/migration-required',
  'session/fork-unavailable', 'session/title-invalid', 'session/agent-busy', 'directory-picker/exists', 'directory-picker/unavailable', 'gateway/bad-request']);

/** Shared authentication is performed by native-api before dispatch. One owner per Context. */
export function createNativeWorkspaces(ctx, options = {}) {
  const workspaceRoot = options.workspaceDirectory ?? (process.env.DSH_HOME ? join(process.env.DSH_HOME, '..', 'workspace', 'spaces') : null);
  const receipts = new Receipts(options.operationDirectory ?? (process.env.DSH_HOME ? join(process.env.DSH_HOME, 'droy-native-workspaces', 'receipts') : null));
  let tail = Promise.resolve(), closed = false, queued = 0;
  const enqueue = run => {
    if (closed) throw unavailable();
    if (queued >= 32) throw new WorkspaceError('NATIVE_WORKSPACES_BUSY', 503, 'full');
    queued++;
    const task = tail.then(run).finally(() => { queued--; }); tail = task.catch(() => {}); return task;
  };
  async function mutate(op, signal) {
    const previous = await receipts.read(op);
    if (previous) return publicReceipt(previous);
    if (closed) throw unavailable();
    await guard(ctx, op, signal); signal?.throwIfAborted();
    if (op.kind === 'workspace.create' && !workspaceRoot) throw unavailable();
    const row = { version: 1, requestId: op.requestId, kind: op.kind, digest: op.digest, state: 'pending' };
    await receipts.write(row, true);
    const [namespace, method] = operations.get(op.kind);
    try {
      let value;
      if (op.kind === 'workspace.create') {
        const current = await baseline(ctx, signal);
        if (current.items.some(space => space.title === op.args.name.trim()))
          throw new WorkspaceError('workspace/name-conflict', 409);
        await mkdir(workspaceRoot, { recursive: true });
        const root = await realpath(workspaceRoot);
        const path = join(root, op.args.name.trim());
        try { await mkdir(path); }
        catch (error) { if (error.code === 'EEXIST') throw Object.assign(new Error('directory exists'), { code: 'directory-picker/exists' }); throw error; }
        row.path = path;
        value = checked('workspace', 'create', await service(ctx, 'workspaceController', 'create').create({ path }));
        row.workspaceId = value.workspace.workspaceId;
        row.state = 'complete';
        await receipts.write(row);
      } else if (op.kind === 'session.fork') {
        // The public client increments a title only after a successful fork.
        // Record the child before that second write so a rename failure cannot
        // cause another child to be created on reconnect.
        const listed = checked('session', 'list', await service(ctx, 'sessionController', 'list').list({}, signal));
        const title = listed.items.find(item => item.sessionId === op.args.sessionId)?.projections?.values?.title;
        value = checked('session', 'fork', await service(ctx, 'sessionController', 'fork').fork(op.args));
        Object.assign(row, { state: 'unknown', resultSessionId: value.sessionId, renameUnconfirmed: typeof title === 'string' });
        await receipts.write(row);
        await checkpoint(ctx, value.sessionId);
        row.state = 'complete'; await receipts.write(row);
        if (typeof title === 'string') {
          try {
            const renamed = checked('session', 'rename', await service(ctx, 'sessionController', 'rename').rename({ sessionId: value.sessionId, title: increasedTitle(title) }));
            await checkpoint(ctx, value.sessionId);
            row.title = renamed.title; row.renameUnconfirmed = false; await receipts.write(row);
          } catch { /* The primary fork receipt remains complete and names the created child. */ }
        }
      } else {
        const target = service(ctx, namespace === 'session' ? 'sessionController' : namespace === 'workspace' ? 'workspaceController' : 'directoryPickerController', method);
        value = checked(namespace, method, await (namespace === 'directoryPicker' ? target[method](op.args.path, op.args.name) : target[method](op.args)));
        if (op.kind === 'session.rename') await checkpoint(ctx, op.args.sessionId);
        row.state = 'complete';
        if (value?.workspace) row.workspaceId = value.workspace.workspaceId;
        if (op.args.workspaceId) row.workspaceId = op.args.workspaceId;
        if (op.kind === 'session.rename') row.title = value.title;
        if (op.kind === 'directory.create') row.path = hostPath(value);
        await receipts.write(row);
      }
      return publicReceipt(row);
    } catch (error) {
      // If a receipt write itself fails after a returned SDK result, preserve
      // the on-disk pending record; never turn it into a safe-to-repeat error.
      if (row.state === 'complete') return publicReceipt({ ...row, state: row.resultSessionId ? 'complete' : 'unknown', errorCode: 'NATIVE_WORKSPACES_STORAGE' });
      row.state = refusal.has(error?.code) ? 'failed' : 'unknown';
      row.errorCode = refusal.has(error?.code) ? error.code : 'NATIVE_WORKSPACES_UNCONFIRMED';
      try { await receipts.write(row); } catch { /* The durable pending admission still prevents replay. */ }
      return publicReceipt(row);
    }
  }
  return {
    async handle(req, res, signal) {
      try {
        if (!req.url?.startsWith('/') || req.url.startsWith('//')) throw bad();
        const url = new URL(req.url, `http://${req.headers.host}`);
        if (url.search) throw bad();
        const suffix = url.pathname.startsWith(base) ? url.pathname.slice(base.length) : null;
        const method = suffix === '' ? 'GET' : ['/directory/list', '/mutate', '/operations/status'].includes(suffix) ? 'POST' : null;
        if (!method) throw new WorkspaceError('NATIVE_WORKSPACES_NOT_FOUND', 404, 'missing');
        if (req.method !== method) throw new WorkspaceError('NATIVE_WORKSPACES_METHOD_NOT_ALLOWED', 405, 'method');
        if (method === 'GET' && (Number(req.headers['content-length']) > 0 || req.headers['transfer-encoding'])) throw bad();
        const input = method === 'POST' ? await body(req) : undefined;
        signal?.throwIfAborted();
        let result;
        if (suffix === '') result = await catalog(ctx, signal);
        else if (suffix === '/directory/list') {
          fields(input, ['path'], []); if (input.path !== undefined) hostPath(input.path);
          parse('directoryPicker', 'list', [input.path]);
          result = checked('directoryPicker', 'list', await service(ctx, 'directoryPickerController', 'list').list(input.path, signal));
        } else {
          const op = operation(input);
          result = suffix === '/mutate' ? await enqueue(() => mutate(op, signal)) : publicReceipt(await receipts.read(op))
            ?? { requestId: op.requestId, kind: op.kind, state: 'missing' };
        }
        reply(res, 200, { ok: true, result });
      } catch (error) {
        req.resume();
        const own = error instanceof WorkspaceError, key = own ? error.key : 'failed';
        reply(res, own ? error.status : 500, { ok: false, error: { code: own ? error.code : 'NATIVE_WORKSPACES_FAILED',
          message: messages[key][resolveMobileLocale(ctx) === 'zh' ? 0 : 1] } });
      }
    },
    async dispose() { closed = true; await tail; },
  };
}
