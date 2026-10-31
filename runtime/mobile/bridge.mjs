import { renderPhoneResult } from './tool-result.mjs';
import { translateBackendText } from './backend-i18n.mjs';

export class BridgeError extends Error {
  constructor(code, message, details = {}) {
    const rendered = renderPhoneResult(details);
    super(`${code}: ${message}${Object.keys(rendered).length ? `\n${JSON.stringify(rendered)}` : ''}`);
    this.code = code;
    this.details = details;
  }
}

function actionBudget(action, args) {
  const requested = Number.isFinite(args.timeoutMs) && args.timeoutMs >= 0 ? Math.min(args.timeoutMs, 60000) : undefined;
  if (['sensor', 'location', 'infrared', 'bluetooth', 'nfc', 'nfc_emulation', 'wifi', 'cellular', 'network_probe', 'device_data', 'device_shortcut'].includes(action))
    return Math.max(15000, Math.max(requested ?? 0, Number.isFinite(args.durationMs) ? Math.min(args.durationMs, 60000) : 0) + 5000);
  if (action.startsWith('browser_')) return Math.max(15000, (requested ?? 15000) + 10000);
  if (action === 'sequence') return requested ?? 30000;
  if (action === 'wait_for') return Math.max(1000, (requested ?? 10000) + 1500);
  if (action === 'compute_run') return Math.max(15000, (requested ?? 5000) + 10000);
  if (args.afterCondition !== undefined) {
    const wait = Number.isFinite(args.afterTimeoutMs) && args.afterTimeoutMs >= 0 ? Math.min(args.afterTimeoutMs, 60000) : 2000;
    return Math.max(15000, wait + 5000);
  }
  return 15000;
}

export function createBridge({ port = process.env.DROY_BRIDGE_PORT, token = process.env.DROY_BRIDGE_TOKEN, fetchImpl = fetch, getLocale = () => process.env.DROY_LOCALE, getScope, currentScope = () => undefined } = {}) {
  const ownMessage = text => translateBackendText(text, getLocale());
  if (!/^\d+$/.test(String(port)) || Number(port) < 1 || Number(port) > 65535 || !token || token.length < 32) {
    throw new Error(ownMessage('DroyAgent 原生桥配置无效'));
  }
  const post = async (path, body, signal) => {
    const response = await fetchImpl(`http://127.0.0.1:${port}${path}`, {
      method: 'POST', headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json; charset=utf-8' },
      body: JSON.stringify(body), signal: signal ?? AbortSignal.timeout(5000), redirect: 'error',
    });
    const envelope = await response.json();
    if (!response.ok || envelope.ok !== true) {
      const { code = 'BRIDGE_ERROR', message = ownMessage('原生桥请求失败'), ...details } = envelope.error ?? {};
      throw new BridgeError(code, message, details);
    }
    return envelope.result;
  };
  const audioBytes = async (path, payload, signal) => {
    const deadline = new AbortController();
    const timer = setTimeout(() => deadline.abort(new BridgeError('AUDIO_TRANSFER_TIMEOUT', ownMessage('音频接口未及时响应'))), 15000);
    timer.unref?.();
    let response;
    try {
      response = await fetchImpl(`http://127.0.0.1:${port}${path}`, {
        method: 'POST', headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json; charset=utf-8' },
        body: JSON.stringify(payload), signal: signal ? AbortSignal.any([signal, deadline.signal]) : deadline.signal,
        redirect: 'error',
      });
    } finally { clearTimeout(timer); }
    if (!response.ok || response.headers.get('content-type')?.includes('application/json')) {
      const envelope = await response.json();
      const { code = 'AUDIO_TRANSFER_FAILED', message = ownMessage('音频读取失败'), ...details } = envelope.error ?? {};
      throw new BridgeError(code, message, details);
    }
    return response;
  };
  return {
    async call(action, args, exec) {
      if (!exec.agent?.id || !exec.callId) throw new BridgeError('IDENTITY_REQUIRED', ownMessage('工具调用缺少引擎身份'));
      // Capture once: an awaited preflight must not acquire a new epoch after a pause.
      const scope = getScope?.({ taskId: String(exec.agent.id), callId: String(exec.callId), action, signal: exec.signal });
      const identity = { taskId: String(exec.agent.id), callId: String(exec.callId),
        ...(scope ? await scope : undefined) };
      const budgetMs = actionBudget(action, args);
      const deadline = new AbortController();
      const humanBudgetMs = 120000;
      let timer = setTimeout(() => deadline.abort(new BridgeError('permission_timeout',
        ownMessage('授权等待已超时，请重新发起。'), { dispatched: false, outcomeUnknown: false })), humanBudgetMs + 1000);
      timer.unref?.();
      const signal = AbortSignal.any([exec.signal, deadline.signal]);
      let cancellation;
      const cancel = () => { cancellation ??= post('/cancel', identity); cancellation.catch(() => {}); };
      signal.addEventListener('abort', cancel, { once: true });
      try {
        if (signal.aborted) cancel();
        signal.throwIfAborted();
        await post('/permissions/preflight', { action, args, humanBudgetMs, ...identity }, signal);
        signal.throwIfAborted();
        clearTimeout(timer);
        // Human authorization has its own deadline; native execution starts its budget only after it passes.
        timer = setTimeout(() => deadline.abort(new BridgeError('action_timeout',
          ownMessage('手机工具未在预算内返回；先核实当前状态，不重放动作'), { outcomeUnknown: true })), budgetMs + 1000);
        timer.unref?.();
        return await post('/tool', { action, args, budgetMs, ...identity }, signal);
      } finally {
        clearTimeout(timer);
        signal.removeEventListener('abort', cancel);
        // Do not silently lose cancellation: native acknowledgement precedes quiescence.
        if (cancellation) {
          try { await cancellation; }
          catch {
            throw new BridgeError('cancellation_unconfirmed', ownMessage('手机尚未确认停止；恢复连接后先核实状态，不重放动作'),
              { outcomeUnknown: true });
          }
        }
      }
    },
    progress(payload) { return post('/progress', payload); },
    taskControl(payload, signal) { return post('/task/control', payload, signal); },
    clock(signal) { return post('/clock', {}, signal ? AbortSignal.any([signal, AbortSignal.timeout(5000)]) : undefined); },
    setup(payload, signal) { return post('/setup', payload, signal ? AbortSignal.any([signal, AbortSignal.timeout(15000)]) : AbortSignal.timeout(15000)); },
    companion(signal) { return post('/companion', {}, signal ? AbortSignal.any([signal, AbortSignal.timeout(5000)]) : undefined); },
    notifications(payload, signal) { return post('/notifications', payload, signal); },
    memory(payload, signal) { return post('/memory', payload, signal ?? AbortSignal.timeout(45000)); },
    companionMemory(payload, signal) { return post('/companion-memory', payload, signal ?? AbortSignal.timeout(15000)); },
    async embedding(payload, caller) {
      const timeout=AbortSignal.timeout(Math.max(1,Math.min(20000,(payload.deadlineAt??Date.now()+5000)-Date.now()+1000)));
      const signal=caller?AbortSignal.any([caller,timeout]):timeout;
      let cancellation;
      const cancel=()=>{
        if(payload.operation!=='embed')return;
        cancellation??=post('/embedding',{operation:'cancel',requestId:payload.requestId,memorySpaceId:payload.memorySpaceId},AbortSignal.timeout(500));
        cancellation.catch(()=>{});
      };
      signal.addEventListener('abort',cancel,{once:true});
      try {signal.throwIfAborted();return await post('/embedding',payload,signal);}
      finally {signal.removeEventListener('abort',cancel);if(cancellation)await cancellation;}
    },
    history(payload, signal) { return post('/history', payload, signal ?? AbortSignal.timeout(15000)); },
    voice(payload, signal) { return post('/voice', payload, signal ?? AbortSignal.timeout(15000)); },
    notify(payload, signal) { return post('/notify', payload, signal); },
    async audio(payload, caller) {
      const signal = caller ? AbortSignal.any([caller, AbortSignal.timeout(10000)]) : AbortSignal.timeout(10000);
      if (payload.taskId && getScope) payload = { ...payload, ...await getScope({ taskId: String(payload.taskId),
        callId: payload.callId, action: `audio_${payload.operation}`, signal }) };
      if (payload.operation !== 'transcribe') return post('/audio', payload, signal);
      if (!payload.taskId || !payload.callId) throw new BridgeError('IDENTITY_REQUIRED', ownMessage('转写启动缺少可信调用身份'));
      const identity = { taskId: String(payload.taskId), callId: String(payload.callId),
        ...(payload.operationId ? { operationId: payload.operationId, epoch: payload.epoch } : {}) };
      let cancellation;
      const cancel = () => { cancellation ??= post('/cancel', identity); cancellation.catch(() => {}); };
      signal.addEventListener('abort', cancel, { once: true });
      try {
        if (signal.aborted) cancel();
        signal.throwIfAborted();
        return await post('/audio', payload, signal);
      } finally {
        signal.removeEventListener('abort', cancel);
        if (cancellation) {
          try { await cancellation; }
          catch { throw new BridgeError('cancellation_unconfirmed', ownMessage('手机尚未确认停止音频转写；先核实当前作业'), { outcomeUnknown: true }); }
        }
      }
    },
    async audioFile(payload, signal) { return audioBytes('/audio/file', { ...payload,
      ...await getScope?.({ taskId: String(payload.taskId), callId: payload.callId, action: 'audio_file', signal }) }, signal); },
    async audioFrames(payload, signal) { return audioBytes('/audio/frames', { ...payload,
      ...await getScope?.({ taskId: String(payload.taskId), callId: payload.callId, action: 'audio_frames', signal }) }, signal); },
    end(taskId, captured = currentScope(String(taskId))) { return post('/task/end', { taskId: String(taskId), ...captured }); },
  };
}
