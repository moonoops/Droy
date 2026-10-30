import { nativeTurnBoundaryProjection } from './native-turn-boundary.mjs';
import { once } from 'node:events';
import { TextDecoder } from 'node:util';
import { TYPERT } from '@deepseek-ai/dsh-api-session-controller/typert';
import { requireMobileSession } from './auth.mjs';
import { resolveMobileLocale } from './locale.mjs';
import { selectNativeSessionModel } from './native-models.mjs';
import { createNativeDecisions } from './native-decisions.mjs';
import { createNativeSettings } from './native-settings.mjs';
import { createNativeSchedules } from './native-schedules.mjs';
import { createNativeWorkbenchMedia } from './native-workbench-media.mjs';
import { createNativeMemory } from './native-memory.mjs';
import { createNativeVoice } from './native-voice.mjs';
import { createNativeAdvanced } from './native-advanced.mjs';
import { createNativeAgentTeam } from './native-agent-team.mjs';
import { createNativeSpeech } from './native-speech.mjs';
import { createNativeTools } from './native-tools.mjs';
import { createNativeEngine } from './native-engine.mjs';
import { createNativeWorkspaces } from './native-workspaces.mjs';
import { createNativeNavigationService, NativeNavigationError } from './native-navigation.mjs';

const base = '/droy/native';
const maxBodyBytes = 32 * 1024 * 1024;
const commands = new Set(['list', 'create', 'page', 'prompt', 'cancel', 'projections']);
const methods = new Map(TYPERT.invocations.filter(method => method.namespace === 'session').map(method => [method.method, method]));
const copy = {
  invalid: { zh: '请求格式无效。', en: 'The request is invalid.' },
  large: { zh: '请求内容过大。', en: 'The request is too large.' },
  unavailable: { zh: '运行服务尚未就绪，请稍后重试。', en: 'The runtime is not ready. Try again shortly.' },
  missing: { zh: '未找到请求的内容。', en: 'The requested item was not found.' },
  method: { zh: '此操作不支持该请求方式。', en: 'This request method is not supported.' },
  managed: { zh: '请从伙伴对话处理此任务。', en: 'Manage this task from its companion conversation.' },
  workspace: { zh: '此空间由伙伴管理，请选择其他空间。', en: 'This workspace belongs to a companion. Choose another workspace.' },
  model: { zh: '所选模型不可用，请检查模型设置。', en: 'The selected model is unavailable. Check model settings.' },
  failed: { zh: '操作未完成，请检查运行状态后重试。', en: 'The operation failed. Check the runtime status and try again.' },
};

class NativeApiError extends Error {
  constructor(code, status, text) { super(code); this.code = code; this.status = status; this.text = text; }
}
const badRequest = () => new NativeApiError('NATIVE_BAD_REQUEST', 400, 'invalid');

function errorBody(ctx, error) {
  if (error instanceof NativeNavigationError)
    return { status: error.status, body: { ok: false, error: { code: error.code, message: error.message } } };
  const known = error instanceof NativeApiError;
  const rawCode = typeof error?.code === 'string' && /^[a-zA-Z][a-zA-Z0-9_/-]{1,100}$/.test(error.code) ? error.code : 'NATIVE_OPERATION_FAILED';
  const code = known ? error.code : rawCode;
  const status = known ? error.status : /not-found|NOT_FOUND/.test(code) ? 404
    : /bad-request|INVALID|invalid/.test(code) ? 400
      : /unavailable|NOT_READY|not-ready/.test(code) ? 503
        : /busy|conflict|MANAGED|ALREADY/.test(code) ? 409 : 500;
  const key = known ? error.text : code === 'COMPANION_SESSION_MANAGED' ? 'managed' : code === 'COMPANION_WORKSPACE_MANAGED' ? 'workspace'
    : code === 'session/model-unavailable' ? 'model' : status === 404 ? 'missing' : status === 400 ? 'invalid' : status === 503 ? 'unavailable' : 'failed';
  return { status, body: { ok: false, error: { code, message: copy[key][resolveMobileLocale(ctx)] } } };
}

function json(res, status, value) {
  if (res.destroyed || res.writableEnded) return;
  const body = JSON.stringify(value);
  res.writeHead(status, { 'Content-Type': 'application/json; charset=utf-8', 'Cache-Control': 'no-store' });
  res.end(body);
}

function parse(method, value) {
  const descriptor = methods.get(method);
  const parameter = descriptor.parameters[0].wire;
  const parser = descriptor.input({ strict: true }).create();
  let parsed;
  try {
    parsed = parser.parse({ [parameter]: value });
  } catch (error) {
    if (error?.name === 'ZodError') throw badRequest();
    throw error;
  }
  const request = parsed[parameter];
  if (!request || Array.isArray(request)) throw badRequest();
  if ('sessionId' in request && (!request.sessionId.trim() || request.sessionId.length > 1024)) throw badRequest();
  if ('requestId' in request && (!request.requestId.trim() || request.requestId.length > 1024)) throw badRequest();
  if ('maxMessages' in request && (!Number.isSafeInteger(request.maxMessages) || request.maxMessages < 1)) throw badRequest();
  return request;
}

function requestBody(req) {
  if (!/^application\/json(?:\s*;|\s*$)/i.test(req.headers['content-type'] ?? '')) { req.resume(); throw badRequest(); }
  if (Number(req.headers['content-length']) > maxBodyBytes) {
    req.resume();
    throw new NativeApiError('NATIVE_REQUEST_TOO_LARGE', 413, 'large');
  }
  return new Promise((resolve, reject) => {
    const chunks = [];
    let bytes = 0;
    const clean = () => {
      req.removeListener('data', data); req.removeListener('end', end);
      req.removeListener('error', error); req.removeListener('aborted', aborted);
    };
    const error = failure => { clean(); reject(failure); };
    const aborted = () => error(badRequest());
    const data = part => {
      bytes += part.length;
      if (bytes > maxBodyBytes) {
        clean(); req.resume(); reject(new NativeApiError('NATIVE_REQUEST_TOO_LARGE', 413, 'large')); return;
      }
      chunks.push(part);
    };
    const end = () => {
      clean();
      try { resolve(JSON.parse(new TextDecoder('utf-8', { fatal: true }).decode(Buffer.concat(chunks)))); }
      catch { reject(badRequest()); }
    };
    req.on('data', data); req.once('end', end); req.once('error', error); req.once('aborted', aborted);
  });
}

function service(ctx, name) {
  const value = ctx.get?.(name) ?? ctx[name];
  if (!value) throw new NativeApiError('NATIVE_SERVICE_NOT_READY', 503, 'unavailable');
  return value;
}

function companions(ctx) {
  const runtime = service(ctx, 'droyCompanions');
  if (typeof runtime.ownsSession !== 'function' || typeof runtime.store?.profiles !== 'function')
    throw new NativeApiError('NATIVE_SERVICE_NOT_READY', 503, 'unavailable');
  return runtime;
}

async function stream(res, iterable, signal, ctx) {
  const iterator = iterable[Symbol.asyncIterator]();
  let heartbeat;
  try {
    // Resolve the opening frame before committing 200, so a missing Session
    // still returns an ordinary actionable HTTP failure.
    let next = await iterator.next();
    signal.throwIfAborted();
    res.writeHead(200, {
      'Content-Type': 'text/event-stream; charset=utf-8', 'Cache-Control': 'no-store',
      Connection: 'keep-alive', 'X-Accel-Buffering': 'no',
    });
    res.flushHeaders?.();
    heartbeat = setInterval(() => {
      if (!res.destroyed && !res.writableEnded && !res.writableNeedDrain) res.write(': keepalive\n\n');
    }, 15000);
    heartbeat.unref?.();
    while (!next.done) {
      signal.throwIfAborted();
      if (!res.write(`data: ${JSON.stringify(next.value)}\n\n`)) await once(res, 'drain', { signal });
      next = await iterator.next();
    }
    if (!res.destroyed && !res.writableEnded) res.end();
  } catch (error) {
    if (signal.aborted || res.destroyed) return;
    if (!res.headersSent) throw error;
    res.end(`event: error\ndata: ${JSON.stringify(errorBody(ctx, error).body)}\n\n`);
  } finally {
    clearInterval(heartbeat);
    await iterator.return?.();
  }
}

/** Only explicitly detached user work; this is not an all-Host idle proof. */
export function createNativeUserTasks(ctx, { engine, advanced }) {
  const idle = Promise.resolve(); let waiting;
  const failure = () => new NativeApiError('NATIVE_USER_TASKS_OBSERVATION_FAILED', 503, 'unavailable');
  const inspect = () => {
    const pet = typeof ctx.get === 'function' ? ctx.get('droyPetGeneration') : ctx.droyPetGeneration;
    const writers = [], unfenced = [], seen = new Set();
    for (const [kind, owner] of [['native-compact', engine], ['native-plugin-install', advanced], ['pet-generation', pet]]) {
      if (!owner || typeof owner.pendingWork !== 'function') { unfenced.push(Object.freeze({ kind: kind + '-owner-unavailable', count: 1 })); continue; }
      let pending;
      try { pending = owner.pendingWork(); } catch { throw failure(); }
      if (!Array.isArray(pending) || pending.some(operation => !(operation instanceof Promise))) throw failure();
      for (const done of pending) if (!seen.has(done)) { seen.add(done); writers.push(Object.freeze({ kind, done })); }
    }
    return Object.freeze({ writers: Object.freeze(writers), busy: Object.freeze([]), unfenced: Object.freeze(unfenced) });
  };
  const assertObserved = value => {
    if (value.unfenced.length) throw new NativeApiError('NATIVE_USER_TASKS_NOT_READY', 503, 'unavailable');
    return value;
  };
  return Object.freeze({ scope: 'explicit-native-user-tasks', inspect,
    whenIdle() {
      let current;
      try { current = assertObserved(inspect()); } catch (error) { return Promise.reject(error); }
      if (!current.writers.length) return idle;
      if (waiting) return waiting;
      const operation = (async () => {
        try {
          let snapshot = current;
          while (snapshot.writers.length) {
            await Promise.allSettled(snapshot.writers.map(row => row.done));
            snapshot = assertObserved(inspect());
          }
        } finally { if (waiting === operation) waiting = undefined; }
      })();
      waiting = operation;
      return operation;
    },
  });
}

export function createNativeApi(ctx, { decisions } = {}) {
  const active = new Set();
  const settings = createNativeSettings(ctx);
  const schedules = createNativeSchedules(ctx);
  const uploads = createNativeWorkbenchMedia(ctx);
  const memory = createNativeMemory(ctx);
  const voice = createNativeVoice(ctx);
  const advanced = createNativeAdvanced(ctx);
  const agentTeam = createNativeAgentTeam(ctx);
  const speech = createNativeSpeech(ctx);
  const tools = createNativeTools(ctx);
  const engine = createNativeEngine(ctx);
  const workspaces = createNativeWorkspaces(ctx);
  const navigation = createNativeNavigationService(ctx);
  const userTasks = createNativeUserTasks(ctx, { engine, advanced });
  return {
    userTasks,
    async dispose() {
      for (const controller of active) controller.abort();
      engine.dispose();
      await tools.dispose();
      await workspaces.dispose();
    },
    async handle(req, res) {
      if (!requireMobileSession(req, res)) return;
      const cancellation = new AbortController();
      const aborted = () => cancellation.abort();
      const closed = () => { if (!res.writableEnded) cancellation.abort(); };
      req.once('aborted', aborted); res.once('close', closed); active.add(cancellation);
      try {
        if (!req.url?.startsWith('/') || req.url.startsWith('//')) throw badRequest();
        const url = new URL(req.url, `http://${req.headers.host}`);
        const path = url.pathname;
        if (path !== base && !path.startsWith(`${base}/`)) throw new NativeApiError('NATIVE_NOT_FOUND', 404, 'missing');
        if (path === `${base}/settings` || path.startsWith(`${base}/settings/`)) {
          await settings.handle(req, res, cancellation.signal);
          return;
        }
        if (path === `${base}/schedules` || path.startsWith(`${base}/schedules/`)) {
          await schedules.handle(req, res, cancellation.signal);
          return;
        }
        if (path === `${base}/uploads` || path.startsWith(`${base}/uploads/`)) {
          await uploads.handle(req, res, cancellation.signal);
          return;
        }
        if (path === `${base}/memory` || path.startsWith(`${base}/memory/`)) {
          await memory.handle(req, res, cancellation.signal);
          return;
        }
        if (path === `${base}/voice` || path.startsWith(`${base}/voice/`)) {
          await voice.handle(req, res, cancellation.signal);
          return;
        }
        if (path === `${base}/agent-team`) {
          await agentTeam.handle(req, res, cancellation.signal);
          return;
        }
        if (path === `${base}/advanced` || path.startsWith(`${base}/advanced/`)) {
          await advanced.handle(req, res, cancellation.signal);
          return;
        }
        if (path === `${base}/speech` || path.startsWith(`${base}/speech/`)) {
          await speech.handle(req, res, cancellation.signal);
          return;
        }
        if (path === `${base}/tools` || path.startsWith(`${base}/tools/`)) {
          await tools.handle(req, res, cancellation.signal);
          return;
        }
        if (path === `${base}/engine` || path.startsWith(`${base}/engine/`)) {
          await engine.handle(req, res, cancellation.signal);
          return;
        }
        if (path === `${base}/workspaces` || path.startsWith(`${base}/workspaces/`)) {
          await workspaces.handle(req, res, cancellation.signal);
          return;
        }
        if (path === `${base}/decisions` || path.startsWith(`${base}/decisions/`)) {
          if (!decisions) throw new NativeApiError('NATIVE_SERVICE_NOT_READY', 503, 'unavailable');
          await decisions.handle(req, res);
          return;
        }
        if (path === `${base}/navigation/resolve`) {
          if (req.method !== 'POST') throw new NativeApiError('NATIVE_METHOD_NOT_ALLOWED', 405, 'method');
          if (url.search) throw badRequest();
          json(res, 200, { ok: true, result: await navigation.resolve(await requestBody(req), { signal: cancellation.signal }) });
          return;
        }
        if (path === `${base}/transcript/follow`) {
          if (req.method !== 'GET') throw new NativeApiError('NATIVE_METHOD_NOT_ALLOWED', 405, 'method');
          const allowed = new Set(['parentSessionId', 'childSessionId', 'maxMessages']);
          if ([...url.searchParams.keys()].some(key => !allowed.has(key) || url.searchParams.getAll(key).length !== 1)) throw badRequest();
          const parentSessionId = url.searchParams.get('parentSessionId'), childSessionId = url.searchParams.get('childSessionId');
          const validId = value => typeof value === 'string' && value.length <= 256 && /^[A-Za-z0-9][A-Za-z0-9_.:-]*$/u.test(value);
          if (!validId(parentSessionId) || !validId(childSessionId) || parentSessionId === childSessionId) throw badRequest();
          const limit = url.searchParams.get('maxMessages');
          if (limit !== null && !/^[1-9][0-9]*$/.test(limit)) throw badRequest();
          const input = parse('follow', { address: { kind: 'subagent', parentSessionId, childSessionId, mode: 'unknown' },
            assistantStream: true, maxMessages: limit === null ? 60 : Number(limit) });
          await stream(res, service(ctx, 'sessionController').follow(input, cancellation.signal), cancellation.signal, ctx);
          return;
        }
        const command = path.startsWith(`${base}/session/`) ? path.slice(`${base}/session/`.length) : null;
        const controller = service(ctx, 'sessionController');
        if (commands.has(command) || command === 'model') {
          if (req.method !== 'POST') throw new NativeApiError('NATIVE_METHOD_NOT_ALLOWED', 405, 'method');
          if (url.search) throw badRequest();
          const body = await requestBody(req);
          const runtime = companions(ctx);
          if (command === 'model') {
            json(res, 200, { ok: true, result: await selectNativeSessionModel(ctx, body) });
            return;
          }
          const input = parse(command, body);
          if (command === 'cancel' && runtime.ownsSession(input.sessionId))
            throw new NativeApiError('COMPANION_SESSION_MANAGED', 409, 'managed');
          const method = methods.get(command);
          const result = method.cancellation ? await controller[command](input, cancellation.signal) : await controller[command](input);
          const value = command === 'list'
            ? { ...result, managedSessionIds: result.items.filter(item => runtime.ownsSession(item.sessionId)).map(item => item.sessionId) }
            : result;
          json(res, 200, { ok: true, result: value });
          return;
        }
        const followsSession = path.startsWith(`${base}/session/`) && path.endsWith('/follow');
        const knownGet = followsSession || [`${base}/session/control`, `${base}/models`].includes(path);
        if (!knownGet) throw new NativeApiError('NATIVE_NOT_FOUND', 404, 'missing');
        if (req.method !== 'GET') throw new NativeApiError('NATIVE_METHOD_NOT_ALLOWED', 405, 'method');
        if (url.search && !followsSession) throw badRequest();
        if (path === `${base}/session/control`) {
          await stream(res, controller.control(cancellation.signal), cancellation.signal, ctx);
        } else if (path === `${base}/models`) {
          const catalog = await controller.modelCatalog();
          // Upstream failures carry raw provider exception messages, which may include headers.
          const failures = catalog.failures.map(({ id, name }) => ({ id, name,
            message: copy.model[resolveMobileLocale(ctx)] }));
          json(res, 200, { ok: true, result: { ...catalog, failures } });
        } else if (followsSession) {
          const raw = path.slice(`${base}/session/`.length, -'/follow'.length);
          let sessionId;
          try { sessionId = decodeURIComponent(raw); } catch { throw badRequest(); }
          if (!sessionId || raw.includes('/') || sessionId.length > 1024 || /[\u0000-\u001f\u007f]/.test(sessionId)) throw badRequest();
          if ([...url.searchParams.keys()].some(key => key !== 'maxMessages') || url.searchParams.getAll('maxMessages').length > 1) throw badRequest();
          const limit = url.searchParams.get('maxMessages');
          if (limit !== null && !/^[1-9][0-9]*$/.test(limit)) throw badRequest();
          const input = parse('follow', { address: { kind: 'session', sessionId }, assistantStream: true, maxMessages: limit === null ? 60 : Number(limit) });
          await stream(res, controller.follow(input, cancellation.signal), cancellation.signal, ctx);
        } else throw new NativeApiError('NATIVE_NOT_FOUND', 404, 'missing');
      } catch (error) {
        req.resume();
        if (!cancellation.signal.aborted && !res.destroyed) {
          const failure = errorBody(ctx, error);
          json(res, failure.status, failure.body);
        }
      } finally {
        req.removeListener('aborted', aborted); res.removeListener('close', closed); active.delete(cancellation);
      }
    },
  };
}

export function installNativeApi(ctx) {
  // Register before Pet's prepend listener and the Remote gateway forwarding path.
  // Waiting for HTTP dependencies here would put native requests behind Remote's pending owner queue.
  const decisions = createNativeDecisions(ctx);
  ctx.effect(() => () => decisions.dispose());
  return ctx.inject(['sessionController', 'workspaceController', 'droyCompanions', 'sessionProjections'], scope => {
    scope.sessionProjections.register(nativeTurnBoundaryProjection);
    const api = createNativeApi(scope, { decisions });
    scope.webServer.register({ kind: 'exact', path: base, handler: api.handle });
    scope.webServer.register({ kind: 'prefix', path: base, handler: api.handle });
    scope.provide('droyNativeApi', true);
    scope.provide('droyNativeUserTasks', api.userTasks);
    scope.effect(() => () => api.dispose());
  });
}
