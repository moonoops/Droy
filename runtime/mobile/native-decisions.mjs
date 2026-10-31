import { randomUUID } from 'node:crypto';
import { TextDecoder } from 'node:util';
import { requireMobileSession } from './auth.mjs';
import { resolveMobileLocale } from './locale.mjs';

const base = '/droy/native/decisions';
const maxBytes = 131072;
const copy = {
  invalid: { zh: '回答格式无效。', en: 'The answer is invalid.' },
  ended: { zh: '此请求已结束。', en: 'This request has ended.' },
  pending: { zh: '回答已提交，等待处理。', en: 'The answer is queued for processing.' },
  denied: { zh: '此回答不属于当前任务。', en: 'This answer does not belong to this task.' },
  context: { zh: '操作详情不可用，请先查看完整任务。', en: 'Action details are unavailable. Review the full task first.' },
  large: { zh: '回答内容过大。', en: 'The answer is too large.' },
  unavailable: { zh: '运行服务尚未就绪。', en: 'The runtime is not ready.' },
  failed: { zh: '回答未提交，请重试。', en: 'The answer was not submitted. Try again.' },
  method: { zh: '不支持此请求方式。', en: 'This request method is not supported.' },
  missing: { zh: '未找到此请求。', en: 'This request was not found.' },
};
class DecisionError extends Error {
  constructor(code, status, text) { super(code); this.code = code; this.status = status; this.text = text; }
}
const invalid = () => new DecisionError('NATIVE_DECISION_INVALID', 400, 'invalid');
const ended = () => new DecisionError('NATIVE_DECISION_ENDED', 410, 'ended');
const plain = value => value !== null && typeof value === 'object' && !Array.isArray(value);
const exact = (value, keys) => plain(value) && Object.keys(value).every(key => keys.includes(key));
const identity = value => typeof value === 'string' && value.trim().length > 0 && value.length <= 1024;
const cancelled = (signal, locale) => signal?.reason instanceof Error ? signal.reason
  : Object.assign(new Error(copy.ended[locale]), { code: 'ASK_ABORTED' });

function envelope(ctx, error) {
  const known = error instanceof DecisionError;
  if (!known) ctx.logger?.warn('DROY_NATIVE_DECISION_FAILED');
  return { status: known ? error.status : 500, body: { ok: false, error: {
    code: known ? error.code : 'NATIVE_DECISION_FAILED',
    message: copy[known ? error.text : 'failed'][resolveMobileLocale(ctx)],
  } } };
}
function json(res, status, value) {
  if (res.destroyed || res.writableEnded) return;
  res.writeHead(status, { 'Content-Type': 'application/json; charset=utf-8', 'Cache-Control': 'no-store' });
  res.end(JSON.stringify(value));
}
function body(req) {
  if (!/^application\/json(?:\s*;|\s*$)/i.test(req.headers['content-type'] ?? '')) throw invalid();
  if (Number(req.headers['content-length']) > maxBytes) { req.resume(); throw new DecisionError('NATIVE_DECISION_TOO_LARGE', 413, 'large'); }
  return new Promise((resolve, reject) => {
    let bytes = 0;
    const chunks = [];
    const clean = () => { req.removeListener('data', data); req.removeListener('end', end); req.removeListener('error', error); req.removeListener('aborted', aborted); };
    const error = failure => { clean(); reject(failure); };
    const aborted = () => error(invalid());
    const data = chunk => {
      bytes += chunk.length;
      if (bytes > maxBytes) { clean(); req.resume(); reject(new DecisionError('NATIVE_DECISION_TOO_LARGE', 413, 'large')); return; }
      chunks.push(chunk);
    };
    const end = () => {
      clean();
      try { resolve(JSON.parse(new TextDecoder('utf-8', { fatal: true }).decode(Buffer.concat(chunks)))); }
      catch { reject(invalid()); }
    };
    req.on('data', data); req.once('end', end); req.once('error', error); req.once('aborted', aborted);
  });
}

function questionCopy(questions) {
  if (!Array.isArray(questions) || !questions.length || questions.length > 32) return null;
  const ids = new Set();
  const value = [];
  for (const item of questions) {
    if (!plain(item) || typeof item.id !== 'string' || ids.has(item.id) || typeof item.question !== 'string'
      || item.id.length > 1024 || item.question.length > 65536
      || item.options !== undefined && (!Array.isArray(item.options) || item.options.length > 64)
      || item.multiSelect !== undefined && typeof item.multiSelect !== 'boolean') return null;
    ids.add(item.id);
    const result = { id: item.id, question: item.question };
    for (const key of ['header', 'detail']) {
      if (item[key] !== undefined) {
        if (typeof item[key] !== 'string' || item[key].length > 65536) return null;
        result[key] = item[key];
      }
    }
    if (item.options !== undefined) {
      if (item.options.some(option => !plain(option) || typeof option.label !== 'string'
        || option.label.length > 16384 || option.description !== undefined && typeof option.description !== 'string')) return null;
      result.options = item.options.map(option => ({ label: option.label, ...(option.description === undefined ? {} : { description: option.description }) }));
    }
    if (item.multiSelect !== undefined) result.multiSelect = item.multiSelect;
    if (item.intent !== undefined) {
      if (!exact(item.intent, ['kind', 'approve', 'callId']) || item.intent.kind !== 'plan-review'
        || typeof item.intent.approve !== 'string' || !result.options?.some(option => option.label === item.intent.approve)
        || result.detail === undefined || item.intent.callId !== undefined && !identity(item.intent.callId)) return null;
      result.intent = { ...item.intent };
    }
    value.push(result);
  }
  return Buffer.byteLength(JSON.stringify(value)) <= maxBytes ? value : null;
}
function answersFor(questions, input) {
  if (!Array.isArray(input) || input.length !== questions.length) throw invalid();
  const ids = new Set();
  const answers = [];
  for (const item of input) {
    if (!exact(item, ['id', 'selected', 'custom']) || typeof item.id !== 'string' || ids.has(item.id)
      || !Array.isArray(item.selected) || item.selected.some(label => typeof label !== 'string')
      || item.custom !== undefined && (typeof item.custom !== 'string' || [...item.custom].length > 16000)) throw invalid();
    const question = questions.find(question => question.id === item.id);
    if (!question || new Set(item.selected).size !== item.selected.length
      || item.selected.some(label => !(question.options ?? []).some(option => option.label === label))
      || !question.multiSelect && item.selected.length > 1) throw invalid();
    const custom = item.custom?.trim();
    if (!question.multiSelect && custom && item.selected.length) throw invalid();
    ids.add(item.id);
    answers.push({ id: item.id, selected: [...item.selected], ...(custom ? { custom } : {}) });
  }
  return { answers };
}

/** Host answerer for native presentation; DSH still owns policy, tool execution and audit. */
export function createNativeDecisions(ctx) {
  const entries = new Map();
  const clients = new Set();
  const disposers = [];
  let disposed = false;
  const get = name => ctx.get?.(name) ?? ctx[name];
  const ordinary = agent => {
    const agents = get('agents'), companions = get('droyCompanions');
    return !!agent && identity(agent.id) && agents?.get(agent.id) === agent && agents.roots().includes(agent)
      && typeof companions?.ownsSession === 'function' && !companions.ownsSession(agent.id);
  };
  function visible(entry) { return { ...entry.view }; }
  function snapshot() { return { type: 'snapshot', decisions: [...entries.values()].map(visible) }; }
  function write(client, frame) {
    if (client.res.destroyed || client.res.writableEnded) return;
    if (!client.ready) { client.dirty = true; return; }
    if (client.blocked) { client.dirty = true; return; }
    client.blocked = !client.res.write(`data: ${JSON.stringify(frame)}\n\n`);
  }
  function broadcast(frame) { for (const client of clients) write(client, frame); }
  function remove(entry, reason) {
    if (entries.get(entry.view.id) !== entry) return;
    entries.delete(entry.view.id);
    entry.signal?.removeEventListener('abort', entry.abort);
    entry.claim?.abort();
    clearTimeout(entry.timer);
    broadcast({ type: 'remove', id: entry.view.id, reason });
  }
  function withdraw(entry, reason = 'cancelled') {
    remove(entry, reason);
    if (entry.resolve) {
      if (entry.view.kind === 'approval') entry.resolve('cancelled');
      else entry.reject(cancelled(entry.signal, resolveMobileLocale(ctx)));
    }
  }
  function failStreams(error) {
    const message = envelope(ctx, error).body;
    for (const client of clients) client.res.end(`event: error\ndata: ${JSON.stringify(message)}\n\n`);
  }
  function currentQuestions(agent) {
    return get('sessionProjections')?.snapshot(agent.session, ['userQuestions']).values.userQuestions?.active;
  }
  const queuedAnswer = (agent, callId) => [agent.inbox?.nextTurn ?? [], agent.inbox?.nextStep ?? []].some(queue =>
    queue.some(message => message.source?.kind === 'user-question-reply' && message.source.callId === callId));
  function refreshContinued(agent) {
    if (disposed) return;
    const previous = [...entries.values()].filter(entry => entry.agent === agent && entry.view.state === 'continued');
    if (!ordinary(agent)) { for (const entry of previous) remove(entry, 'cancelled'); return; }
    const active = currentQuestions(agent);
    if (!active) { for (const entry of previous) remove(entry, 'unavailable'); return; }
    for (const entry of previous) if (!active.some(question => question.state === 'continued' && question.callId === entry.view.callId)) remove(entry, 'answered');
    if (!clients.size) return;
    for (const question of active) {
      if (question.state !== 'continued') continue;
      const questions = questionCopy(question.questions);
      if (!questions) continue;
      let entry = previous.find(entry => entries.has(entry.view.id) && entry.view.callId === question.callId);
      const answerPending = !!entry?.view.answerPending || queuedAnswer(agent, question.callId);
      if (!entry) {
        entry = { agent, view: { id: randomUUID(), kind: 'question', sessionId: agent.id,
          callId: question.callId, state: 'continued', questions, answerPending } };
        entries.set(entry.view.id, entry);
        broadcast({ type: 'upsert', decision: visible(entry) });
      } else if (JSON.stringify(entry.view.questions) !== JSON.stringify(questions) || entry.view.answerPending !== answerPending) {
        entry.view = { ...entry.view, questions, answerPending };
        broadcast({ type: 'upsert', decision: visible(entry) });
      }
    }
  }
  function refreshSafely(agent) {
    try { refreshContinued(agent); } catch (error) { failStreams(error); }
  }
  function hydrate() {
    const agents = get('agents');
    if (!agents) throw new DecisionError('NATIVE_DECISION_NOT_READY', 503, 'unavailable');
    for (const entry of [...entries.values()]) if (!ordinary(entry.agent)) withdraw(entry);
    for (const agent of agents.roots()) refreshContinued(agent);
  }
  function approvalDetails(request) {
    const details = {};
    if (request.reason !== undefined && typeof request.reason === 'string') details.reason = request.reason;
    if (plain(request.displayReason)) details.displayReason = Object.fromEntries(Object.entries(request.displayReason).filter(([, value]) => typeof value === 'string'));
    if (request.callId === undefined) return { ...details, canApprove: !!(details.reason?.trim() || Object.values(details.displayReason ?? {}).some(value => value.trim())) };
    const session = request.agent.session;
    if (!session || !Number.isSafeInteger(session.seq) || typeof session.eventAt !== 'function') return { ...details, canApprove: false };
    for (let seq = session.seq - 1; seq >= 0; seq--) {
      const event = session.eventAt(seq);
      if (event?.type === 'turn/end' || event?.type === 'turn/start') break;
      if (event?.type === 'tool/result' && event.data.message?.toolCallId === request.callId) break;
      if (event?.type === 'tool/call' && event.data.callId === request.callId) {
        if (event.data.name === request.toolName && typeof event.data.arguments === 'string'
          && Buffer.byteLength(event.data.arguments) <= maxBytes)
          return { ...details, toolArguments: event.data.arguments, canApprove: true };
        break;
      }
    }
    return { ...details, canApprove: false };
  }
  function claimTimed(entry) {
    if (entry.claim || !clients.size || entry.view.state !== 'open' || !entry.timed || entry.signal?.aborted) return;
    const service = get('userQuestions');
    if (typeof service?.attachWait !== 'function') { withdraw(entry); return; }
    const controller = new AbortController();
    entry.claim = controller;
    (async () => {
      try {
        for await (const frame of service.attachWait(entry.agent, entry.view.callId, controller.signal)) {
          if (controller.signal.aborted || !entries.has(entry.view.id)) break;
          if (!Number.isFinite(frame.remainingMs) || frame.remainingMs < 0) throw new Error('INVALID_QUESTION_WAIT');
          entry.view.expiresAt = Date.now() + frame.remainingMs;
          clearTimeout(entry.timer);
          // Releasing the claim lets the Host's own deadline settle askTimed.
          entry.timer = setTimeout(() => controller.abort(), frame.remainingMs);
          entry.timer.unref?.();
          broadcast({ type: 'upsert', decision: visible(entry) });
        }
      } catch (error) {
        if (!controller.signal.aborted && entries.has(entry.view.id)) {
          remove(entry, 'unavailable'); entry.reject(error);
        }
      } finally {
        if (entry.claim === controller) entry.claim = null;
      }
    })();
  }
  function present(kind, request, next) {
    if (disposed || !clients.size || !ordinary(request.agent)) return next();
    if (request.signal?.aborted) return kind === 'approval' ? Promise.resolve('cancelled') : Promise.reject(cancelled(request.signal, resolveMobileLocale(ctx)));
    const questions = kind === 'question' ? questionCopy(request.questions) : null;
    if (kind === 'question' && (!questions || request.wait && !identity(request.wait.callId))) return next();
    let resolve, reject;
    const promise = new Promise((yes, no) => { resolve = yes; reject = no; });
    const entry = { agent: request.agent, signal: request.signal, request, resolve, reject,
      timed: kind === 'question' && request.wait?.timed === true,
      view: { id: randomUUID(), kind, sessionId: request.agent.id,
        ...(kind === 'approval' ? { toolName: request.toolName, ...approvalDetails(request) }
          : { questions, state: 'open', answerPending: false }),
        ...(request.callId ?? request.wait?.callId ? { callId: request.callId ?? request.wait.callId } : {}),
      } };
    entry.abort = () => withdraw(entry, request.signal?.reason?.code === 'ASK_TIMED_OUT' ? 'expired' : 'cancelled');
    entries.set(entry.view.id, entry);
    entry.signal?.addEventListener('abort', entry.abort, { once: true });
    broadcast({ type: 'upsert', decision: visible(entry) });
    claimTimed(entry);
    return promise.finally(() => remove(entry, 'answered'));
  }
  // Install before Pet so its later prepend observer keeps the answer race.
  // Native must precede Remote forwarding: the Gateway retains unanswered
  // requests for reconnecting Web clients instead of delegating immediately.
  disposers.push(ctx.on('approval/request', (request, next) => present('approval', request, next), { prepend: true }));
  disposers.push(ctx.on('user-questions/request', (request, next) => present('question', request, next), { prepend: true }));
  disposers.push(ctx.on('agent/disposed', ({ agent }) => {
    for (const entry of [...entries.values()]) if (entry.agent === agent) withdraw(entry);
  }));
  disposers.push(ctx.on('agent/status', ({ agent, status }) => {
    if (status === 'idle') for (const entry of [...entries.values()])
      if (entry.agent === agent && entry.view.state !== 'continued') withdraw(entry);
  }));
  disposers.push(ctx.on('agent/inbox/discarded', ({ agent, message }) => {
    if (message.source?.kind !== 'user-question-reply') return;
    for (const entry of entries.values()) if (entry.agent === agent && entry.view.callId === message.source.callId) entry.view.answerPending = false;
    refreshSafely(agent);
  }, { global: true }));
  const changed = registry => registry.onChanged((session, key) => {
    if (key === 'userQuestions') {
      const agent = get('agents')?.get(session.id);
      if (agent) refreshSafely(agent);
    }
  });
  if (typeof ctx.inject === 'function') ctx.inject(['sessionProjections'], scope => {
    const dispose = changed(scope.sessionProjections); disposers.push(dispose); return dispose;
  });
  else if (get('sessionProjections')) disposers.push(changed(get('sessionProjections')));

  async function answer(id, input) {
    if (!plain(input) || !identity(input.sessionId)) throw invalid();
    const entry = entries.get(id);
    if (!entry) throw ended();
    if (input.sessionId !== entry.view.sessionId) throw new DecisionError('NATIVE_DECISION_FORBIDDEN', 403, 'denied');
    if (!ordinary(entry.agent) || entry.signal?.aborted) { withdraw(entry); throw ended(); }
    if (entry.view.kind === 'approval') {
      if (!exact(input, ['sessionId', 'outcome']) || !['allowed-once', 'rejected'].includes(input.outcome)) throw invalid();
      if (input.outcome === 'allowed-once' && (!entry.view.canApprove || !approvalDetails(entry.request).canApprove)) throw new DecisionError('NATIVE_DECISION_CONTEXT_REQUIRED', 409, 'context');
      remove(entry, 'answered'); entry.resolve(input.outcome);
    } else {
      if (!exact(input, ['sessionId', 'answers'])) throw invalid();
      const normalized = answersFor(entry.view.questions, input.answers);
      if (entry.view.answerPending) throw new DecisionError('NATIVE_DECISION_PENDING', 409, 'pending');
      if (entry.view.expiresAt !== undefined && Date.now() >= entry.view.expiresAt) { entry.claim?.abort(); throw ended(); }
      if (entry.view.state === 'continued') {
        refreshContinued(entry.agent);
        if (!entries.has(id)) throw ended();
        if (!get('userQuestions')?.answer(entry.agent, entry.view.callId, normalized)) { remove(entry, 'answered'); throw ended(); }
        entry.view.answerPending = true;
        broadcast({ type: 'upsert', decision: visible(entry) });
      } else { remove(entry, 'answered'); entry.resolve(normalized); }
    }
    return { accepted: true };
  }
  return {
    dispose() {
      if (disposed) return;
      disposed = true;
      for (const entry of [...entries.values()]) withdraw(entry);
      for (const client of [...clients]) client.res.end();
      for (const dispose of disposers) if (typeof dispose === 'function') dispose();
    },
    async handle(req, res) {
      if (!requireMobileSession(req, res)) return;
      try {
        if (disposed) throw new DecisionError('NATIVE_DECISION_NOT_READY', 503, 'unavailable');
        if (!req.url?.startsWith('/') || req.url.startsWith('//')) throw invalid();
        const url = new URL(req.url, `http://${req.headers.host}`);
        if (url.search) throw invalid();
        if (url.pathname === base) {
          if (req.method !== 'GET') throw new DecisionError('NATIVE_DECISION_METHOD', 405, 'method');
          const client = { res, blocked: false, dirty: false, ready: false };
          clients.add(client);
          const closed = () => {
            clearInterval(client.heartbeat);
            clients.delete(client);
            if (!clients.size) for (const entry of entries.values()) { clearTimeout(entry.timer); entry.claim?.abort(); }
          };
          res.once('close', closed);
          res.on('drain', () => { client.blocked = false; if (client.dirty) { client.dirty = false; write(client, snapshot()); } });
          try { hydrate(); } catch (error) { clients.delete(client); res.removeListener('close', closed); throw error; }
          res.writeHead(200, { 'Content-Type': 'text/event-stream; charset=utf-8', 'Cache-Control': 'no-store', Connection: 'keep-alive', 'X-Accel-Buffering': 'no' });
          res.flushHeaders?.();
          client.ready = true; client.dirty = false;
          write(client, snapshot());
          client.heartbeat = setInterval(() => { if (!client.blocked && !res.destroyed && !res.writableEnded) client.blocked = !res.write(': keepalive\n\n'); }, 15000);
          client.heartbeat.unref?.();
          for (const entry of entries.values()) claimTimed(entry);
          return;
        }
        const match = url.pathname.match(/^\/droy\/native\/decisions\/([a-f0-9-]{36})\/answer$/i);
        if (!match) throw new DecisionError('NATIVE_DECISION_NOT_FOUND', 404, 'missing');
        if (req.method !== 'POST') throw new DecisionError('NATIVE_DECISION_METHOD', 405, 'method');
        json(res, 200, { ok: true, result: await answer(match[1], await body(req)) });
      } catch (error) {
        if (res.destroyed || res.writableEnded) return;
        if (res.headersSent) { res.end(`event: error\ndata: ${JSON.stringify(envelope(ctx, error).body)}\n\n`); return; }
        const failure = envelope(ctx, error);
        json(res, failure.status, failure.body);
      }
    },
  };
}
