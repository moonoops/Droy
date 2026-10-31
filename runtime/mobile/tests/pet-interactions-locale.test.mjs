import test from 'node:test';
import assert from 'node:assert/strict';
import { createRequire, registerHooks } from 'node:module';
import { pathToFileURL } from 'node:url';
const installed = createRequire(new URL('../../dsh/package.json', import.meta.url));
const hooks = registerHooks({ resolve(specifier, context, next) {
  if (specifier.startsWith('@deepseek-ai/')) return { url: pathToFileURL(installed.resolve(specifier)).href, shortCircuit: true };
  return next(specifier, context);
} });
const { installPetInteractions } = await import('../pet-interactions.mjs');
hooks.deregister();

function fixture(options = {}) {
  let locale = 'zh', current, route;
  const listeners = new Map(), disposers = [];
  const agent = { id: 'fictional-task', status: 'running' };
  const ctx = {
    get: name => name === 'droyTaskControl' ? options.control
      : name === 'droyCompanions' ? { ownsSession: () => false }
        : ({ describe: () => [{ ns: 'locale', value: { preference: locale } }] }),
    agents: { get: id => id === agent.id ? agent : null },
    webServer: { register(value) { route = value; } },
    on(event, handler) { listeners.set(event, handler); },
    effect(callback) { disposers.push(callback()); },
    waterfall: () => new Promise(() => {}),
  };
  installPetInteractions(ctx, { waiting(_taskId, view) { current = view; } });
  return {
    agent, setLocale(value) { locale = value; }, view: () => current,
    question(request) { return listeners.get('user-questions/request')({ agent, ...request }, () => null); },
    approval(request) { return listeners.get('approval/request')({ agent, ...request }, () => null); },
    async action(data) {
      const oldToken = process.env.DROY_WEB_TOKEN;
      const token = 'fictional-locale-pet-session-1234567890';
      process.env.DROY_WEB_TOKEN = token;
      let status, value;
      try {
        const req = { method: 'POST', headers: { host: '127.0.0.1:12345', cookie: `droy_session=${token}` },
          async *[Symbol.asyncIterator]() { yield Buffer.from(JSON.stringify(data)); } };
        const res = { writeHead(code) { status = code; }, end(body) { value = body ? JSON.parse(body) : null; } };
        await route.handler(req, res);
        return { status, value };
      } finally {
        if (oldToken === undefined) delete process.env.DROY_WEB_TOKEN; else process.env.DROY_WEB_TOKEN = oldToken;
      }
    },
    dispose() { disposers.forEach(dispose => dispose()); },
  };
}

test('new companion questions follow the current language and preserve question text and options', async () => {
  const f = fixture();
  for (const locale of ['zh', 'en', 'zh']) {
    f.setLocale(locale);
    const answer = f.question({ questions: [{ id: 'pick', question: '用户原始问题', options: [{ label: '原始选项' }] }] });
    const view = f.view();
    assert.equal(view.kind, 'question');
    assert.equal(view.title, locale === 'zh' ? '需要你的回答' : 'Your answer is needed');
    assert.equal(view.message, '用户原始问题');
    assert.equal(view.options[0].label, '原始选项');
    assert.equal((await f.action({ taskId: f.agent.id, action: 'answer', requestId: view.requestId, optionId: '0' })).status, 200);
    assert.deepEqual(await answer, { answers: [{ id: 'pick', selected: ['原始选项'] }] });
  }
  f.dispose();
});

test('companion approvals select translated reasons and localize product buttons and HTTP errors', async () => {
  const f = fixture();
  for (const locale of ['zh', 'en']) {
    f.setLocale(locale);
    const approval = f.approval({ reason: 'Original user reason', displayReason: { zh: '中文确认原因', en: 'English approval reason' } });
    const view = f.view();
    assert.equal(view.kind, 'approval');
    assert.equal(view.title, locale === 'zh' ? '需要你确认' : 'Your confirmation is needed');
    assert.equal(view.message, locale === 'zh' ? '中文确认原因' : 'English approval reason');
    assert.deepEqual(view.options.map(item => item.label), locale === 'zh' ? ['允许一次', '拒绝'] : ['Allow once', 'Reject']);
    const stale = await f.action({ taskId: f.agent.id, action: 'answer', requestId: 'expired', optionId: 'allow' });
    assert.equal(stale.status, 409);
    assert.equal(stale.value.error.message, locale === 'zh' ? '这个问题已处理，请查看最新进展' : 'This question has already been handled. Check the latest progress.');
    await f.action({ taskId: f.agent.id, action: 'answer', requestId: view.requestId, optionId: 'reject' });
    assert.equal(await approval, 'rejected');
  }
  f.dispose();
});

test('pause/resume/stop require an explicit safe revision and report accepted state or current CAS conflict in either language', async () => {
  const calls = [];
  const control = { command(input) {
    calls.push(input);
    if (input.expectedRevision !== 2) throw Object.assign(new Error('TASK_CONTROL_CHANGED'), { code: 'TASK_CONTROL_CHANGED' });
    return { accepted: true, control: { taskId: input.taskId, phase: 'pausing', controlRevision: 3, active: true } };
  }, get: () => ({ taskId: 'fictional-task', phase: 'paused', controlRevision: 2 }), revision: () => 2 };
  const f = fixture({ control });
  for (const locale of ['zh', 'en']) {
    f.setLocale(locale);
    for (const expectedRevision of [undefined, -1, 1.5, Number.MAX_SAFE_INTEGER + 1])
      assert.equal((await f.action({ taskId: f.agent.id, action: 'pause', expectedRevision })).status, 400);
    const accepted = await f.action({ taskId: f.agent.id, action: 'pause', expectedRevision: 2 });
    assert.equal(accepted.status, 200); assert.equal(accepted.value.accepted, true); assert.equal(accepted.value.control.controlRevision, 3);
    const stale = await f.action({ taskId: f.agent.id, action: 'resume', expectedRevision: 1 });
    assert.equal(stale.status, 409); assert.equal(stale.value.error.code, 'TASK_CONTROL_CHANGED');
    assert.equal(stale.value.control.controlRevision, 2);
    assert.equal(stale.value.error.message, locale === 'zh' ? '任务状态已更新，请按最新状态操作' : 'The task state has changed. Use the latest controls.');
  }
  assert.equal(calls.length, 4, 'Malformed revision requests never reach product control');
  f.dispose();
});
