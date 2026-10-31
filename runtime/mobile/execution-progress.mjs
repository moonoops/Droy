import { petText } from './pet-text.mjs';
import { translateBackendText } from './backend-i18n.mjs';

const summaries = {
  thinking: '正在努力执行', executing: '正在努力执行', verifying: '正在努力执行',
  blocked: '当前步骤需要调整，点我查看任务', completed: '本轮执行已结束，点我查看结果', interrupted: '本轮执行已停止，点我查看任务',
};
const observingTools = new Set(['android_observe', 'android_screenshot']);
const controllingTools = new Set(['android_open_app', 'android_open_settings', 'android_sequence', 'android_tap',
  'android_input_text', 'android_swipe', 'android_gesture', 'android_node_action', 'android_global_action', 'android_back']);
const workingPhases = new Set(['thinking', 'executing', 'verifying']);
// These tools can carry an explicitly announced wait. Their arguments/results
// never become public copy, and an ordinary shell call alone cannot invent a wait.
const waitingTools = new Set(['bash', 'job_output', 'android_wait_for', 'droy_browser_wait']);

/** Project engine facts and intentional public copy, never raw model reasoning. */
export function createExecutionProgress(send, reportError, timers = { setTimeout, clearTimeout }, getLocale = () => 'zh', isPublicTask = () => true) {
  const tasks = new Map();
  const controls = new Map();
  let disposed = false;
  const copy = text => translateBackendText(text, getLocale());
  function accepts(taskId) {
    taskId = String(taskId);
    if (isPublicTask(taskId)) return true;
    const task = tasks.get(taskId);
    if (task) { clearRequest(task); task.queued = undefined; tasks.delete(taskId); }
    controls.delete(taskId);
    return false;
  }
  function taskFor(taskId) {
    taskId = String(taskId);
    let task = tasks.get(taskId);
    if (!task) { task = {}; tasks.set(taskId, task); }
    return task;
  }
  function clearRequest(task) {
    if (task.requestTimer !== undefined) timers.clearTimeout(task.requestTimer);
    task.requestGeneration = (task.requestGeneration ?? 0) + 1;
    task.requestTimer = undefined;
    task.slow = false;
  }
  function startRequest(taskId) {
    const task = taskFor(taskId);
    const control = controls.get(taskId);
    clearRequest(task);
    if (control && control.phase !== 'running') return;
    const generation = task.requestGeneration;
    task.requestTimer = timers.setTimeout(() => {
      if (disposed || !accepts(taskId) || generation !== task.requestGeneration || !task.active || task.waiting || task.recovery) return;
      task.requestTimer = undefined;
      task.slow = true;
      task.publicSummary = undefined; task.publicPhase = undefined; task.recovery = undefined;
      report(taskId, 'thinking', true);
    }, 10000);
    task.requestTimer?.unref?.();
  }
  function report(taskId, phase, active) {
    if (disposed || !taskId || !accepts(taskId)) return;
    taskId = String(taskId);
    const task = taskFor(taskId);
    const control = controls.get(taskId);
    let summary, summaryKind;
    if (active && task.waiting) {
      phase = 'waiting_input';
      summary = task.waiting.message;
    } else if (active && task.slow) {
      phase = 'thinking'; summary = '正在思考中，可随时停止任务'; summaryKind = 'slow';
    } else {
      if (active && task.publicPhase === 'waiting') phase = 'waiting';
      if (phase === 'thinking' && task.recovery) phase = task.recovery.phase;
      summary = task.publicSummary ?? task.recovery?.summary ?? summaries[phase];
      if (!task.publicSummary)
        summaryKind = task.recovery ? task.recovery.summaryKind : workingPhases.has(phase) ? 'working' : undefined;
    }
    if (!active) { clearRequest(task); task.waiting = null; task.publicSummary = undefined; task.publicPhase = undefined; task.recovery = undefined; summary = summaries[phase]; summaryKind = undefined; }
    const activityKind = summaryKind === 'slow' ? 'thinking'
      : summaryKind === 'working' ? task.recovery ? 'working' : task.activityKind ?? 'working' : undefined;
    // Public/model-authored progress stays verbatim; only fixed product copy is translated.
    if (!active || task.slow || !task.waiting && !task.publicSummary) summary = copy(summary);
    const controlled = control && control.phase !== 'running';
    const payload = controlled
      ? { taskId, phase: control.phase, active: control.active, summary: control.summary,
        ...(control.summaryKind ? { summaryKind: control.summaryKind } : {}), pending: task.waiting ?? null,
        controlTaskId: control.taskId, controlRevision: control.controlRevision }
      : { taskId, phase, active, summary, ...(activityKind ? { activityKind } : {}), ...(summaryKind ? { summaryKind } : {}),
        pending: task.waiting ?? null, controlTaskId: control?.taskId ?? taskId, controlRevision: control?.controlRevision ?? 0 };
    const key = JSON.stringify(payload);
    if (task.key === key) return;
    Object.assign(task, { phase: payload.phase, active: payload.active, key, queued: payload });
    if (task.sending) return;
    task.sending = true;
    void (async () => {
      while (task.queued && !disposed) {
        if (!accepts(taskId)) break;
        const payload = task.queued;
        task.queued = undefined;
        try { await send(payload); }
        catch { reportError(); }
      }
      task.sending = false;
      if (!task.active && tasks.get(taskId) === task) tasks.delete(taskId);
    })();
  }
  return {
    bind(taskId, state) {
      if (disposed || !accepts(taskId) || !Number.isSafeInteger(state.controlRevision) || state.controlRevision < 0) return;
      const previous = controls.get(String(taskId));
      if (previous?.taskId === state.taskId && previous.controlRevision > state.controlRevision) return;
      controls.set(String(taskId), state);
    },
    control(taskId, state) {
      if (disposed || !accepts(taskId)) return;
      taskId = String(taskId);
      const previous = controls.get(taskId);
      if (!Number.isSafeInteger(state.controlRevision) || state.controlRevision < 0
        || previous && previous.taskId === state.taskId && previous.controlRevision > state.controlRevision) return;
      controls.set(taskId, state);
      clearRequest(taskFor(taskId));
      report(taskId, state.phase === 'running' ? 'thinking' : state.phase, state.active);
    },
    publicSummary(taskId, summary, phase) {
      if (disposed || !accepts(taskId)) return;
      const text = petText(summary, 120);
      if (!text || !['thinking', 'executing', 'verifying', 'waiting', 'blocked'].includes(phase))
        throw new Error(copy('进展需为不含敏感信息的简短描述（最多120字），阶段须与实际执行一致。'));
      if (disposed) return;
      Object.assign(taskFor(taskId), { publicSummary: text, publicPhase: phase, recovery: undefined });
      report(taskId, phase, true);
    },
    toolFailure(taskId, callId, error) {
      if (disposed || !accepts(taskId)) return;
      // Only use the native error contract; never summarize an error body or screen text.
      const failure = error?.details?.outcomeUnknown === true
        ? { phase: 'verifying', summary: '正在确认刚才的操作是否生效' }
        : { phase: 'thinking', summary: summaries.thinking, summaryKind: 'working' };
      const task = taskFor(taskId);
      (task.failures ??= new Map()).set(callId, failure);
    },
    waiting(taskId, pending) {
      if (disposed || !accepts(taskId)) return;
      const task = tasks.get(String(taskId)) ?? (pending ? taskFor(taskId) : null);
      if (!task) return;
      task.waiting = pending;
      if (pending) clearRequest(task);
      // A finished task cannot be revived by a late UI cleanup.
      if (pending || task.active) report(taskId, pending ? 'waiting_input' : 'thinking', true);
    },
    sessionEvent(session, event) {
      if (disposed || !accepts(session.id)) return;
      switch (event.type) {
        case 'turn/start':
          clearRequest(taskFor(session.id));
          Object.assign(taskFor(session.id), { publicSummary: undefined, publicPhase: undefined, recovery: undefined, activityKind: 'started' });
          report(session.id, 'thinking', true); break;
        case 'step/start':
          if (taskFor(session.id).activityKind !== 'started') taskFor(session.id).activityKind = 'working';
          startRequest(session.id); report(session.id, 'thinking', true); break;
        case 'assistant/message': clearRequest(taskFor(session.id)); break;
        case 'llm/retry': {
          const task = taskFor(session.id);
          clearRequest(task);
          task.publicSummary = undefined; task.publicPhase = undefined;
          task.recovery = { phase: 'thinking', summary: '模型请求失败，正在等待重试，可停止任务', summaryKind: 'error' };
          report(session.id, 'thinking', true);
          break;
        }
        case 'tool/call':
          clearRequest(taskFor(session.id));
          if (waitingTools.has(event.data.name)) (taskFor(session.id).waitCalls ??= new Set()).add(event.data.callId);
          if (event.data.name === 'droy_pet_progress') {
            const task = taskFor(session.id);
            (task.progressCalls ??= new Set()).add(event.data.callId);
            break;
          }
          if (taskFor(session.id).publicPhase === 'blocked'
              || taskFor(session.id).publicPhase === 'waiting' && !waitingTools.has(event.data.name))
            Object.assign(taskFor(session.id), { publicSummary: undefined, publicPhase: undefined });
          taskFor(session.id).recovery = undefined;
          taskFor(session.id).activityKind = observingTools.has(event.data.name) ? 'observing'
            : controllingTools.has(event.data.name) ? 'controlling' : 'working';
          report(session.id, 'executing', true);
          break;
        case 'tool/result':
          if (!taskFor(session.id).progressCalls?.delete(event.data.message?.toolCallId)) {
            const task = taskFor(session.id), message = event.data.message;
            if (task.waitCalls?.delete(message?.toolCallId)) {
              task.publicSummary = undefined; task.publicPhase = undefined;
            }
            const failure = task.failures?.get(message?.toolCallId);
            task.failures?.delete(message?.toolCallId);
            if (message?.isError) {
              task.publicSummary = undefined;
              task.publicPhase = undefined;
              task.recovery = failure ?? { phase: 'thinking', summary: summaries.thinking, summaryKind: 'working' };
              // A rejected tool step is not a blocked task: the agent may observe and continue.
              report(session.id, task.recovery.phase, true);
            } else { task.recovery = undefined; report(session.id, 'verifying', true); }
          }
          break;
        case 'turn/end': report(session.id, event.data.reason?.kind === 'completed' ? 'completed' : 'interrupted', false); break;
      }
    },
    status(agent, status) {
      if (disposed || !accepts(agent.id)) return;
      if (status === 'running') {
        if (!tasks.get(String(agent.id))?.active) taskFor(agent.id).activityKind = 'started';
        report(agent.id, 'thinking', true);
      }
      else if (status === 'idle' && tasks.get(String(agent.id))?.active) report(agent.id, 'interrupted', false);
    },
    removed(agent) {
      if (disposed || !accepts(agent.id)) return;
      if (tasks.get(String(agent.id))?.active) report(agent.id, 'interrupted', false);
    },
    dispose() { disposed = true; for (const task of tasks.values()) clearRequest(task); tasks.clear(); controls.clear(); },
  };
}
