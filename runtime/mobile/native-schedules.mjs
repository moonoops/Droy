import { TextDecoder } from 'node:util';
import { TYPERT } from '@deepseek-ai/dsh-schedule/typert';
import { decodeScheduleRecord } from '@deepseek-ai/dsh-schedule';
import { resolveMobileLocale } from './locale.mjs';

const base = '/droy/native/schedules';
const bodyLimit = 256 * 1024;
const descriptors = new Map(TYPERT.invocations.filter(row => row.namespace === 'schedule').map(row => [row.method, row]));
const copy = {
  invalid: ['定时任务请求格式无效。', 'The schedule request is invalid.'],
  unavailable: ['定时任务服务尚未就绪。', 'The schedule service is not ready.'],
  missing: ['未找到此定时任务接口。', 'The schedule endpoint was not found.'],
  session: ['未找到目标工作会话。', 'The target work session was not found.'],
  target: ['此会话不能接收定时任务。', 'This session cannot receive scheduled tasks.'],
  managed: ['请从伙伴入口创建任务。', 'Create this task from the companion conversation.'],
  method: ['此定时任务操作不支持该请求方式。', 'This method is not supported for the schedule operation.'],
  prompt: ['请输入任务名称和执行内容。', 'Enter a task name and instruction.'],
  selector: ['请选择一种定时方式。', 'Choose one schedule rule.'],
  rule: ['定时规则无效，请检查时间设置。', 'The schedule rule is invalid. Check its timing.'],
  zone: ['时区无效，请重新选择。', 'The time zone is invalid. Choose another time zone.'],
  future: ['请选择未来的时间。', 'Choose a time in the future.'],
  range: ['定时时间超出支持范围。', 'The scheduled time is outside the supported range.'],
  frequency: ['定时间隔不能少于一分钟。', 'The recurring interval must be at least one minute.'],
  cancelled: ['请求已取消，请刷新确认任务状态。', 'The request was cancelled. Refresh to confirm its state.'],
  failed: ['操作结果未确认，请刷新任务列表。', 'The operation could not be confirmed. Refresh the task list.'],
};
const publicErrors = new Map([
  ['invalid_prompt', 'prompt'], ['invalid_selector', 'selector'], ['invalid_rule', 'rule'],
  ['invalid_time_zone', 'zone'], ['not_future', 'future'], ['time_out_of_range', 'range'],
  ['frequency_too_high', 'frequency'], ['subagent_session', 'target'], ['internal_error', 'failed'],
]);

class ScheduleError extends Error {
  constructor(code, status, text) { super(code); this.code = code; this.status = status; this.text = text; }
}
const bad = () => new ScheduleError('NATIVE_SCHEDULES_BAD_REQUEST', 400, 'invalid');
const unavailable = () => new ScheduleError('NATIVE_SCHEDULES_NOT_READY', 503, 'unavailable');
const localize = (ctx, key) => copy[key][resolveMobileLocale(ctx) === 'zh' ? 0 : 1];
function fields(value, allowed, required = allowed) {
  if (!value || typeof value !== 'object' || Array.isArray(value) || Object.keys(value).some(key => !allowed.includes(key))
    || required.some(key => !Object.hasOwn(value, key))) throw bad();
}
function identifier(value) {
  if (typeof value !== 'string' || !value || value.trim() !== value || value.length > 1024 || /[\x00-\x1f\x7f]/.test(value)) throw bad();
}
function service(ctx, name, method) {
  const value = typeof ctx.get === 'function' ? ctx.get(name) : ctx[name];
  if (!value || typeof value[method] !== 'function') throw unavailable();
  return value;
}
function reply(res, status, value) {
  if (res.destroyed || res.writableEnded) return;
  const data = JSON.stringify(value);
  res.writeHead(status, { 'Content-Type': 'application/json; charset=utf-8', 'Cache-Control': 'no-store' });
  res.end(data);
}
function errorReply(ctx, res, error, signal) {
  const own = error instanceof ScheduleError;
  const cancelled = error?.name === 'AbortError' || (signal?.aborted && error === signal.reason) || error?.code === 'gateway/cancelled';
  const known = publicErrors.get(error?.code);
  const status = own ? error.status : cancelled ? 409 : known ? error.code === 'internal_error' ? 500 : 422 : 500;
  const code = own ? error.code : cancelled ? 'NATIVE_SCHEDULES_CANCELLED' : known ? error.code : 'NATIVE_SCHEDULES_FAILED';
  const key = own ? error.text : cancelled ? 'cancelled' : known ?? 'failed';
  reply(res, status, { ok: false, error: { code, message: localize(ctx, key) } });
}
function body(req) {
  if (!/^application\/json(?:\s*;|\s*$)/i.test(req.headers['content-type'] || '')) { req.resume(); throw bad(); }
  if (Number(req.headers['content-length']) > bodyLimit) {
    req.resume(); throw new ScheduleError('NATIVE_SCHEDULES_TOO_LARGE', 413, 'invalid');
  }
  return new Promise((resolve, reject) => {
    const chunks = []; let bytes = 0;
    const cleanup = () => {
      req.removeListener('data', data); req.removeListener('end', end);
      req.removeListener('error', failed); req.removeListener('aborted', aborted);
    };
    const failed = error => { cleanup(); reject(error); };
    const aborted = () => failed(bad());
    const data = chunk => {
      bytes += chunk.length;
      if (bytes > bodyLimit) { cleanup(); req.resume(); reject(new ScheduleError('NATIVE_SCHEDULES_TOO_LARGE', 413, 'invalid')); }
      else chunks.push(chunk);
    };
    const end = () => {
      cleanup();
      try { resolve(JSON.parse(new TextDecoder('utf-8', { fatal: true }).decode(Buffer.concat(chunks)))); }
      catch { reject(bad()); }
    };
    req.on('data', data); req.once('end', end); req.once('error', failed); req.once('aborted', aborted);
  });
}
function parse(method, input) {
  let request;
  try { request = descriptors.get(method).input({ strict: true }).create().parse({ request: input }).request; }
  catch (error) { if (error?.name === 'ZodError') throw bad(); throw error; }
  identifier(request.sessionId); identifier(request.id);
  if (method === 'history') {
    if (!Number.isSafeInteger(request.limit) || request.limit < 1 || request.limit > 100) throw bad();
    if (request.before !== undefined) identifier(request.before);
  }
  return request;
}
function calendar(value, type) {
  if (type === 'at' && typeof value === 'string') return;
  const allowed = type === 'at' ? ['date', 'time', 'time_zone'] : type === 'cron' ? ['expression', 'time_zone']
    : type === 'weekly' ? ['time', 'time_zone', 'weekdays'] : ['time', 'time_zone'];
  fields(value, allowed);
  for (const key of allowed) {
    if (key === 'weekdays') {
      if (!Array.isArray(value.weekdays) || value.weekdays.some(day => typeof day !== 'number' || !Number.isFinite(day))) throw bad();
    } else if (typeof value[key] !== 'string') throw bad();
  }
}
function createInput(input) {
  // create is a public local ScheduleService method, without a Typert codec.
  // Mirror only its exported request shape; the service owns rule/time semantics.
  fields(input, ['sessionId', 'request']); identifier(input.sessionId);
  const request = input.request;
  fields(request, ['prompt', 'title', 'after_seconds', 'at', 'every_seconds', 'daily', 'weekly', 'cron'], ['prompt', 'title']);
  if (typeof request.prompt !== 'string' || typeof request.title !== 'string') throw bad();
  for (const key of ['after_seconds', 'every_seconds']) if (Object.hasOwn(request, key)
    && (typeof request[key] !== 'number' || !Number.isFinite(request[key]))) throw bad();
  for (const key of ['at', 'daily', 'weekly', 'cron']) if (Object.hasOwn(request, key)) calendar(request[key], key);
  return input;
}
async function create(ctx, input, signal) {
  const runtime = service(ctx, 'droyCompanions', 'ownsSession');
  if (runtime.ownsSession(input.sessionId)) throw new ScheduleError('COMPANION_SESSION_MANAGED', 409, 'managed');
  const snapshot = await service(ctx, 'sessionPersistence', 'stat').stat(input.sessionId, { signal });
  signal?.throwIfAborted();
  if (snapshot === undefined) throw new ScheduleError('NATIVE_SCHEDULES_SESSION_NOT_FOUND', 404, 'session');
  if (!snapshot?.header || snapshot.header.id !== input.sessionId) throw unavailable();
  if (snapshot.formatStatus === 'migration-required' || !snapshot.header.cwd)
    throw new ScheduleError('NATIVE_SCHEDULES_TARGET_UNAVAILABLE', 409, 'target');
  if (snapshot.header.delegationDepth !== undefined && (!Number.isSafeInteger(snapshot.header.delegationDepth) || snapshot.header.delegationDepth < 0))
    throw unavailable();
  if (snapshot.header.delegationDepth > 0) throw new ScheduleError('subagent_session', 422, 'target');
  return service(ctx, 'schedule', 'create').create(input.sessionId, input.request, signal);
}
function result(ctx, method, value) {
  const parsed = method === 'create' ? decodeScheduleRecord(value) : descriptors.get(method).result.create().parse(value);
  // Rule errors are structured non-mutating update outcomes. Keep their codes
  // and result shape, but never expose provider/storage exception text as copy.
  return publicErrors.has(parsed?.code) && 'message' in parsed ? { ...parsed, message: localize(ctx, publicErrors.get(parsed.code)) } : parsed;
}

/** Called after native-api's shared loopback authentication has succeeded. */
export function createNativeSchedules(ctx) {
  const routes = new Map([['', ['GET', 'catalog']], ['/create', ['POST', 'create']], ['/update', ['POST', 'update']],
    ['/delete', ['POST', 'delete']], ['/history', ['POST', 'history']]]);
  return {
    async handle(req, res, signal) {
      try {
        if (!req.url?.startsWith('/') || req.url.startsWith('//')) throw bad();
        const url = new URL(req.url, `http://${req.headers.host}`);
        if (url.search) throw bad();
        const route = (url.pathname === base || url.pathname.startsWith(base + '/')) && routes.get(url.pathname.slice(base.length));
        if (!route) throw new ScheduleError('NATIVE_SCHEDULES_NOT_FOUND', 404, 'missing');
        if (req.method !== route[0]) throw new ScheduleError('NATIVE_SCHEDULES_METHOD_NOT_ALLOWED', 405, 'method');
        if (req.method === 'GET' && (Number(req.headers['content-length']) > 0 || req.headers['transfer-encoding'])) throw bad();
        const method = route[1], input = req.method === 'POST' ? await body(req) : undefined;
        const request = method === 'create' ? createInput(input) : method === 'catalog' ? undefined : parse(method, input);
        signal?.throwIfAborted();
        const schedule = service(ctx, 'schedule', method);
        const value = method === 'catalog' ? await schedule.catalog() : method === 'create' ? await create(ctx, request, signal)
          : descriptors.get(method).cancellation ? await schedule[method](request, signal) : await schedule[method](request);
        // Cancellation after persistence begins cannot undo a committed change.
        // Preserve a returned receipt instead of claiming that it was rolled back.
        reply(res, 200, { ok: true, result: result(ctx, method, value) });
      } catch (error) { req.resume(); errorReply(ctx, res, error, signal); }
    },
  };
}
