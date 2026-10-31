import { randomUUID } from 'node:crypto';
import { scopeTarget } from '@deepseek-ai/dsh-scope';
import { requireMobileSession } from './auth.mjs';
import { petText } from './pet-text.mjs';
import { resolveMobileLocale } from './locale.mjs';

const text = (locale, zh, en) => locale === 'zh' ? zh : en;

// Keep the original question content and answer vocabulary shared with DSH.
// Oversized or specialised review surfaces still link to the full task.
function questionPresentation(request, locale) {
  const questions = request.questions;
  const validText = (value, max, empty = false) => typeof value === 'string'
    && (empty || value.trim().length > 0) && [...value].length <= max;
  const inline = questions.length > 0 && questions.length <= 16 && questions.every(question =>
    validText(question.id, 160) && validText(question.question, 4000) && !question.intent && !question.presentation
    && (question.header === undefined || validText(question.header, 512, true))
    && (question.detail === undefined || validText(question.detail, 16000, true))
    && (question.options ?? []).length <= 32 && (question.options ?? []).every(option =>
      validText(option.label, 512) && (option.description === undefined || validText(option.description, 4000, true))))
    && new Set(questions.map(question => question.id)).size === questions.length
    && Buffer.byteLength(JSON.stringify(questions)) <= 96000;
  const question = questions.length === 1 ? questions[0] : null;
  const message = question && petText(question.question, 120);
  const quick = inline && question && !question.multiSelect && !question.detail
    && (question.options ?? []).length > 0 && (question.options ?? []).length <= 3
    && (question.options ?? []).every(option => !option.description && petText(option.label, 24));
  return {
    title: text(locale, '需要你的回答', 'Your answer is needed'),
    message: inline ? message || text(locale, '有问题需要你回答', 'A question needs your answer')
      : text(locale, '有问题需要你回答，点我回到任务查看完整内容', 'A question needs your answer. Open the task to see the full details.'),
    inline,
    questions: inline ? questions.map(({ id, question, header, detail, options = [], multiSelect = false }) =>
      ({ id, question, ...(header !== undefined ? { header } : {}), ...(detail !== undefined ? { detail } : {}),
        options: options.map(({ label, description }) => ({ label, ...(description !== undefined ? { description } : {}) })),
        multiSelect, allowCustom: true })) : undefined,
    choices: quick ? question.options.map((option, index) => ({ id: String(index), label: option.label,
      answer: { answers: [{ id: question.id, selected: [option.label] }] } })) : [],
  };
}

function validatedAnswers(questions, answers) {
  if (!Array.isArray(answers) || answers.length !== questions.length) return null;
  const ids = new Set();
  const normalized = [];
  for (const question of questions) {
    const answer = answers.find(answer => answer?.id === question.id);
    if (!answer || ids.has(answer.id) || !Array.isArray(answer.selected)
      || answer.selected.some(label => typeof label !== 'string' || !question.options.some(option => option.label === label))
      || new Set(answer.selected).size !== answer.selected.length
      || (!question.multiSelect && answer.selected.length > 1)
      || (answer.custom !== undefined && (typeof answer.custom !== 'string' || [...answer.custom].length > 16000))) return null;
    ids.add(answer.id);
    const custom = answer.custom?.trim();
    // DSH uses a custom answer instead of a selected radio option, and allows
    // custom text alongside checkboxes. An empty selection is an explicit skip.
    if (!question.multiSelect && custom && answer.selected.length) return null;
    normalized.push({ id: question.id, selected: [...answer.selected], ...(custom ? { custom } : {}) });
  }
  return { answers: normalized };
}

function approvalPresentation(request, locale) {
  const message = petText(request.displayReason?.[locale] ?? request.reason, 120);
  // A tool call can have additional command/file detail in the original approval UI.
  // Never offer a grant in a compact bubble that cannot present that detail.
  const inline = !!message && !request.callId;
  return {
    title: text(locale, '需要你确认', 'Your confirmation is needed'),
    message: inline ? message : text(locale, '有操作需要你确认，点我回到任务查看完整内容', 'An action needs your confirmation. Open the task to see the full details.'), inline,
    choices: inline ? [
      { id: 'allow', label: text(locale, '允许一次', 'Allow once'), answer: 'allowed-once' },
      { id: 'reject', label: text(locale, '拒绝', 'Reject'), answer: 'rejected' },
    ] : [],
  };
}

function abortedQuestion(locale) {
  return Object.assign(new Error(text(locale, '用户问题已结束', 'The user question has ended.')), { code: 'ASK_ABORTED' });
}

/** A second answer surface for real DSH requests; the upstream service owns policy and audit. */
export function installPetInteractions(ctx, progress) {
  const pending = new Map();
  const relayed = new WeakSet();
  let disposed = false;

  function showTask(taskId) {
    const latest = [...pending.values()].filter(entry => entry.taskId === taskId).at(-1);
    progress.waiting(taskId, latest?.view ?? null);
  }

  async function present(kind, request, next) {
    if (disposed || relayed.has(request) || !request.agent || request.signal?.aborted) return next();
    const agent = request.agent;
    const taskId = String(agent.id), requestId = randomUUID();
    const locale = resolveMobileLocale(ctx);
    const presentation = kind === 'question' ? questionPresentation(request, locale) : approvalPresentation(request, locale);
    const view = { requestId, kind, title: presentation.title, message: presentation.message,
      inline: presentation.inline, ...(presentation.questions ? { questions: presentation.questions } : {}), options: presentation.choices.map(({ id, label }) => ({ id, label })) };
    const withdrawal = new AbortController();
    const signal = request.signal ? AbortSignal.any([request.signal, withdrawal.signal]) : withdrawal.signal;
    let resolveAnswer, rejectAnswer;
    const answer = new Promise((resolve, reject) => { resolveAnswer = resolve; rejectAnswer = reject; });
    // A fallback request has no native answer race, but must still drain this promise on disposal.
    answer.catch(() => {});
    const abort = () => kind === 'approval' ? resolveAnswer('cancelled') : rejectAnswer(abortedQuestion(locale));
    signal.addEventListener('abort', abort, { once: true });
    const entry = { agent, taskId, view, questions: presentation.questions, choices: presentation.choices, answer: resolveAnswer, withdrawal, signal, settled: false };
    pending.set(requestId, entry);
    showTask(taskId);
    try {
      if (!presentation.inline) return await next();
      // Redispatch a borrowed request copy so native settlement also withdraws the
      // original page's composer. The actual request and caller signal stay intact.
      const copy = { ...request, signal };
      relayed.add(copy);
      const unavailable = () => kind === 'approval' ? Promise.resolve('unavailable')
        : Promise.reject(Object.assign(new Error(text(locale, '没有可用的问题界面', 'No question interface is available.')), { code: 'NO_PROVIDER' }));
      const page = Promise.resolve().then(() => ctx.waterfall(scopeTarget(agent, agent),
        kind === 'question' ? 'user-questions/request' : 'approval/request', copy, unavailable));
      // An Android overlay remains a valid answerer when the background WebView
      // is disconnected. Only absence of a page provider yields to it; real page
      // failures still surface, and request abort/disposal settles `answer`.
      const availablePage = page.then(value => kind === 'approval' && value === 'unavailable' ? answer : value,
        error => { if (error?.code === 'NO_PROVIDER') return answer; throw error; });
      return await Promise.race([answer, availablePage]);
    } finally {
      entry.settled = true;
      pending.delete(requestId);
      signal.removeEventListener('abort', abort);
      withdrawal.abort();
      showTask(taskId);
    }
  }

  ctx.on('user-questions/request', (request, next) => present('question', request, next), { prepend: true });
  ctx.on('approval/request', (request, next) => present('approval', request, next), { prepend: true });
  ctx.on('agent/disposed', ({ agent }) => {
    for (const entry of pending.values()) if (entry.agent === agent) entry.withdrawal.abort();
  });
  ctx.effect(() => () => {
    disposed = true;
    for (const entry of pending.values()) entry.withdrawal.abort();
    pending.clear();
  });

  function act(data) {
    const locale = resolveMobileLocale(ctx);
    const control = ctx.get('droyTaskControl');
    if (['pause', 'resume', 'stop'].includes(data.action)) {
      if (typeof control?.command !== 'function') return { status: 503, code: 'TASK_CONTROL_UNAVAILABLE',
        message: text(locale, '任务控制服务尚未就绪，请稍后重试', 'Task controls are not ready. Try again shortly.') };
      try { return { status: 200, ...control.command(data) }; }
      catch (error) {
        const code = /^[A-Z][A-Z0-9_]{2,80}$/u.test(error?.code ?? '') ? error.code : 'TASK_CONTROL_FAILED';
        return { status: code === 'TASK_CONTROL_INVALID_INPUT' ? 400 : 409, code,
          message: code === 'TASK_CONTROL_CHANGED'
            ? text(locale, '任务状态已更新，请按最新状态操作', 'The task state has changed. Use the latest controls.')
            : text(locale, '暂时无法执行此任务操作，请查看最新状态后重试', 'This task action is unavailable. Check the latest state and try again.'),
          control: control.get(data.taskId) };
      }
    }
    const agent = ctx.agents.get(data.taskId);
    if (!agent) return { status: 409, code: 'TASK_ENDED', message: text(locale, '这个任务已结束，请回到任务查看', 'This task has ended. Open the task to review it.') };
    if (data.action === 'cancel') {
      if (typeof control?.command === 'function' && !ctx.get('droyCompanions')?.ownsSession(data.taskId)) {
        const controllerId = control.get(data.taskId)?.taskId ?? data.taskId;
        return act({ taskId: controllerId, action: 'stop', expectedRevision: control.revision(controllerId) });
      }
      if (agent.status !== 'running') return { status: 409, code: 'TASK_IDLE', message: text(locale, '这个任务当前没有正在执行的步骤', 'This task has no step currently running.') };
      agent.cancel({ kind: 'user' });
      return { status: 200 };
    }
    const entry = pending.get(data.requestId);
    if (!entry || entry.agent !== agent || entry.taskId !== data.taskId || entry.settled || entry.signal.aborted)
      return { status: 409, code: 'REQUEST_ENDED', message: text(locale, '这个问题已处理，请查看最新进展', 'This question has already been handled. Check the latest progress.') };
    const option = entry.choices.find(option => option.id === data.optionId);
    const answer = data.answers !== undefined && entry.questions
      ? validatedAnswers(entry.questions, data.answers) : option?.answer;
    if (!answer) return { status: 409, code: 'OPEN_TASK', message: text(locale, '请回到任务查看完整内容后回答', 'Open the task and read the full details before answering.') };
    entry.settled = true;
    entry.answer(answer);
    return { status: 200 };
  }

  ctx.webServer.register({ kind: 'exact', path: '/droy/pet/action', async handler(req, res) {
    if (!requireMobileSession(req, res)) return;
    if (req.method !== 'POST') { res.writeHead(405); res.end(); return; }
    const parts = [];
    let length = 0;
    for await (const part of req) {
      length += part.length;
      if (length > 131072) { res.writeHead(413); res.end(); return; }
      parts.push(part);
    }
    let data;
    try { data = JSON.parse(Buffer.concat(parts).toString('utf8')); }
    catch { res.writeHead(400); res.end(); return; }
    if (!data || typeof data.taskId !== 'string' || !data.taskId || data.taskId.length > 160
      || !['answer', 'cancel', 'pause', 'resume', 'stop'].includes(data.action)
      || (['pause', 'resume', 'stop'].includes(data.action) && (!Number.isSafeInteger(data.expectedRevision) || data.expectedRevision < 0))
      || (data.action === 'answer' && (typeof data.requestId !== 'string' || (typeof data.optionId !== 'string' && !Array.isArray(data.answers))))) {
      res.writeHead(400); res.end(); return;
    }
    const result = act(data);
    res.writeHead(result.status, { 'Content-Type': 'application/json; charset=utf-8', 'Cache-Control': 'no-store' });
    res.end(JSON.stringify(result.status === 200 ? { ok: true,
      ...(result.accepted !== undefined ? { accepted: result.accepted, control: result.control } : {}) }
      : { ok: false, error: { code: result.code, message: result.message }, ...(result.control ? { control: result.control } : {}) }));
  } });
}
