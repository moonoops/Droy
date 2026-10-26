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

function fixture() {
  let locale = 'zh', current, route;
  const listeners = new Map(), disposers = [];
  const agent = { id: 'fictional-task', status: 'running' };
  const ctx = {
    get: () => ({ describe: () => [{ ns: 'locale', value: { preference: locale } }] }),
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
