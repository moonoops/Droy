// Real DSH loop with a scripted local model and a mock native bridge. No cloud or App messages are sent.
import test from 'node:test';
import assert from 'node:assert/strict';
import { createRequire, registerHooks } from 'node:module';
import { pathToFileURL } from 'node:url';
import { createServer } from 'node:http';
import { Readable } from 'node:stream';
import { setTimeout as delay } from 'node:timers/promises';
const installed = createRequire(new URL('../../dsh/package.json', import.meta.url));
const hooks = registerHooks({ resolve(specifier, context, next) {
  if (specifier.startsWith('@deepseek-ai/')) return { url: pathToFileURL(installed.resolve(specifier)).href, shortCircuit: true };
  return next(specifier, context);
} });
const { Context } = await import('@deepseek-ai/cordis');
const { default: LlmRuntime, LlmAdapter, createUserMessage } = await import('@deepseek-ai/dsh-llm');
const { default: SessionStore } = await import('@deepseek-ai/dsh-session');
const { default: SessionProjectionRegistry } = await import('@deepseek-ai/dsh-session-projection');
const { default: SystemPrompt } = await import('@deepseek-ai/dsh-system-prompt');
const { default: ToolRuntime, defineTool } = await import('@deepseek-ai/dsh-tools');
const { default: AgentRegistry } = await import('@deepseek-ai/dsh-agent');
const { default: AgentLoop } = await import('@deepseek-ai/dsh-agent-loop');
const notifications = await import('../notifications.mjs');
const { installNotificationControl } = await import('../notification-control.mjs');
hooks.deregister();

const TOKEN = 'fictional-proactive-notification-token-123456789';
const call = (id, name, args = {}) => [
  { type: 'block-start', index: 0, blockType: 'tool-call' },
  { type: 'tool-call-delta', index: 0, id, name, argumentsDelta: JSON.stringify(args) },
  { type: 'block-end', index: 0, block: { type: 'tool-call', id, name, arguments: JSON.stringify(args) } },
  { type: 'finish', reason: { kind: 'tool-calls' } },
];
const text = value => [
  { type: 'block-start', index: 0, blockType: 'text' }, { type: 'text-delta', index: 0, text: value },
  { type: 'block-end', index: 0, block: { type: 'text', text: value } }, { type: 'finish', reason: { kind: 'stop' } },
];
class Model extends LlmAdapter {
  constructor(script) { super(); this.script = script; this.requests = []; }
  async resolveModel(provider, model) { return { provider, id: model, name: 'Local notification fixture', inputModalities: ['text'] }; }
  async *stream(request) {
    this.requests.push(request);
    const step = this.script.shift();
    if (!step) throw new Error('Script exhausted');
    yield* typeof step === 'function' ? step(request) : step;
  }
}
const send = (channel = 'system') => ({ operation: 'send', noticeKey: 'result', text: '虚构任务结果已准备好',
  destinations: [{ channel, ...(channel === 'app' ? { packageName: 'example.messages', target: '测试助手' } : {}) }] });
function deferred() { let resolve; const promise = new Promise(value => { resolve = value; }); return { promise, resolve }; }

async function harness(script, { observation, native, androidAction, screenshot, screenshotImage = true } = {}) {
  const requests = [], sockets = new Set();
  const server = createServer(async (req, res) => {
    assert.ok(['/notify', '/task/end'].includes(req.url)); assert.equal(req.headers.authorization, `Bearer ${TOKEN}`);
    let body = ''; for await (const chunk of req) body += chunk;
    const request = { path: req.url, ...JSON.parse(body) }; requests.push(request);
    let value = native ? await native(request) : undefined;
    if (value === undefined) {
      if (request.operation === 'get') value = { pluginActive: true, localEnabled: true, appsEnabled: true,
        channels: ['system', 'pet', 'app'].map(id => ({ id, available: true })), context: { returnOnCompletion: false } };
      else if (request.operation === 'send') value = { results: request.destinations.map(destination => ({
        ...destination, noticeId: 'fixture-notice', text: request.text,
        status: destination.channel === 'app' ? 'requires_action' : 'published',
      })) };
      else if (request.operation === 'complete') value = { noticeId: request.noticeId, status: request.status };
      else value = {};
    }
    res.writeHead(200, { 'content-type': 'application/json' }); res.end(JSON.stringify({ ok: true, result: value }));
  });
  server.on('connection', socket => { sockets.add(socket); socket.on('close', () => sockets.delete(socket)); });
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  process.env.DROY_BRIDGE_PORT = String(server.address().port); process.env.DROY_BRIDGE_TOKEN = TOKEN;
  const ctx = new Context();
  await ctx.plugin(LlmRuntime); await ctx.plugin(SessionStore); await ctx.plugin(SessionProjectionRegistry);
  await ctx.plugin(SystemPrompt); await ctx.plugin(ToolRuntime, { mode: 'native' });
  await ctx.plugin(AgentRegistry); await ctx.plugin(AgentLoop, { agents: [] });
  for (const name of ['android_tap', 'android_open_app', 'android_observe', 'android_screenshot']) ctx.tools.register(defineTool({ name, description: 'Local App fixture', parameters: {},
    output: { schema: { type: 'json' }, render: (_args, value) => [
      { type: 'text', text: JSON.stringify(value) },
      ...(name === 'android_screenshot' && screenshotImage ? [{ type: 'image', attachment: {
        attachmentId: 'b'.repeat(64), mediaType: 'image/png', bytes: 70, width: 1, height: 1, name: 'fixture.png',
      } }] : []),
    ] },
    async execute(_args, exec) { return name === 'android_tap' ? androidAction ? androidAction(exec) : { dispatched: true }
      : name === 'android_open_app' ? { dispatched: true, packageName: 'example.other' }
      : name === 'android_screenshot' ? screenshot ?? { status: 'captured', snapshotId: 'after-send-image', packageName: 'example.messages' }
      : observation ?? {
      status: 'observed', snapshotId: 'after-send', packageName: 'example.messages',
      nodes: [{ text: '测试助手', editable: false }, { text: '虚构任务结果已准备好', editable: false }],
    }; } }));
  const fiber = await ctx.plugin(notifications);
  const model = new Model(script); ctx.llm.registerAdapter(['notification-fixture'], model);
  const errors = []; ctx.on('agent/error', value => errors.push(value.error));
  const { agent } = await ctx.agents.create({ sessionId: `notifications-${crypto.randomUUID()}`,
    agentOptions: { provider: 'notification-fixture', model: 'local' } });
  return { ctx, agent, model, fiber, requests, errors, async run() {
    agent.followup(createUserMessage({ content: [{ type: 'text', text: '验证虚构通知任务' }], source: { kind: 'user' } }));
    await agent.whenIdle(); assert.deepEqual(errors, []);
  }, async close() {
    await ctx.fiber.dispose(); for (const socket of sockets) socket.destroy(); await new Promise(resolve => server.close(resolve));
  } };
}

test('optional notification tools expose low-interruption guidance and unload independently', async () => {
  const fixture = await harness([text('本轮无需主动通知')]);
  try {
    assert.ok(fixture.ctx.get('droyNotifications'));
    const schemas = fixture.ctx.tools.schemas();
    assert.match(schemas.find(value => value.name === 'droy_notify').description, /单一渠道/);
    assert.match(schemas.find(value => value.name === 'droy_notify').description, /紧急且重要/);
    await fixture.run();
    assert.equal(fixture.requests.filter(value => value.operation === 'send').length, 0);
    await fixture.fiber.dispose();
    assert.ok(!fixture.ctx.tools.schemas().some(value => value.name === 'droy_notify'));
    assert.ok(fixture.ctx.tools.schemas().some(value => value.name === 'android_observe'));
    assert.equal(fixture.ctx.get('droyNotifications'), undefined);
    assert.equal(fixture.requests.at(-1).enabled, false);
  } finally { await fixture.close(); }
});

test('same-session repeated tasks carry distinct engine turn identities without model-supplied task ids', async () => {
  const fixture = await harness([call('first', 'droy_notify', send()), text('第一轮完成'),
    call('second', 'droy_notify', send()), text('第二轮完成')]);
  try {
    await fixture.run(); await fixture.run();
    const requests = fixture.requests.filter(value => value.operation === 'send');
    assert.equal(requests.length, 2); assert.equal(requests[0].taskId, requests[1].taskId);
    assert.notEqual(requests[0].turnId, requests[1].turnId); assert.equal(requests[0].noticeKey, requests[1].noticeKey);
    assert.equal(requests[0].attention, 'quiet'); assert.deepEqual(requests[0].destinations, [{ channel: 'system' }]);
  } finally { await fixture.close(); }
});

test('next turn waits for prior native notification cleanup before preparing a new notification', async () => {
  const cleaning = deferred(), release = deferred(); let ends = 0;
  const fixture = await harness([call('first', 'droy_notify', send('app')), text('第一轮结束'),
    call('second', 'droy_notify', send('app')), text('第二轮结束')], { native: async request => {
    if (request.operation === 'end' && ++ends === 1) { cleaning.resolve(); await release.promise; }
  } });
  try {
    await fixture.run(); await cleaning.promise;
    const second = fixture.run();
    await delay(20);
    assert.equal(fixture.requests.filter(value => value.operation === 'send').length, 1);
    release.resolve(); await second;
    assert.equal(fixture.requests.filter(value => value.operation === 'send').length, 2);
  } finally { release.resolve(); await fixture.close(); }
});

test('an unfinished App notification is retired at turn end without running another Agent or send', async () => {
  const ended = deferred();
  const fixture = await harness([call('prepare', 'droy_notify', send('app')), text('保留待核实结果')], {
    native: request => { if (request.operation === 'end') ended.resolve(request); },
  });
  try {
    await fixture.run();
    const end = await ended.promise;
    assert.equal(end.taskId, fixture.agent.id);
    assert.equal(fixture.requests.filter(value => value.operation === 'send').length, 1);
    assert.equal(fixture.requests.filter(value => value.operation === 'complete').length, 0);
  } finally { await fixture.close(); }
});

test('disabling notifications stops an in-flight App action before clearing its native notification context', async () => {
  const started = deferred(); let stopped = false, deactivated = false;
  const fixture = await harness([call('prepare', 'droy_notify', send('app')), call('sending', 'android_tap'),
    call('must-not-run', 'android_tap'), text('不会执行')], {
    androidAction: async exec => {
      started.resolve();
      await new Promise((resolve, reject) => {
        const stop = () => { stopped = true; reject(new Error('App action cancelled')); };
        if (exec.signal.aborted) stop(); else exec.signal.addEventListener('abort', stop, { once: true });
      });
    },
    native: request => {
      if (request.operation === 'activate' && !request.enabled) {
        assert.equal(stopped, true, 'native context must survive until Android action cancellation');
        deactivated = true;
      }
    },
  });
  try {
    const running = fixture.run(); await started.promise;
    await fixture.fiber.dispose(); await running;
    assert.equal(stopped, true); assert.equal(deactivated, true);
    assert.equal(fixture.model.requests.length, 2);
    assert.equal(fixture.requests.filter(value => value.operation === 'send').length, 1);
    assert.equal(fixture.requests.at(-1).operation, 'activate');
    assert.equal(fixture.requests.at(-1).enabled, false);
  } finally { await fixture.close(); }
});

test('disabling notifications does not cancel a task that used only the system channel', async () => {
  const started = deferred(), proceed = deferred(); let cancelled = false;
  const fixture = await harness([call('local-result', 'droy_notify', send()), call('ordinary-action', 'android_tap'), text('普通任务继续完成')], {
    androidAction: async exec => {
      exec.signal.addEventListener('abort', () => { cancelled = true; }, { once: true });
      started.resolve(); await proceed.promise; return { dispatched: true };
    },
  });
  try {
    const running = fixture.run(); await started.promise;
    await fixture.fiber.dispose();
    assert.equal(cancelled, false);
    proceed.resolve(); await running;
    assert.equal(fixture.model.requests.length, 3);
  } finally { proceed.resolve(); await fixture.close(); }
});

test('the pending App Agent can disable its own notification plugin without waiting on its administrative tool', { timeout: 1500 }, async () => {
  const fixture = await harness([call('prepare', 'droy_notify', send('app')),
    call('disable-self', 'fixture_disable_notifications'), call('must-not-run', 'android_tap')]);
  fixture.ctx.tools.register(defineTool({ name: 'fixture_disable_notifications', description: 'Local plugin management fixture', parameters: {},
    output: { schema: { type: 'json' }, render: (_args, value) => [{ type: 'text', text: JSON.stringify(value) }] },
    async execute() { await fixture.fiber.dispose(); return { disabled: true }; } }));
  try {
    await fixture.run();
    assert.equal(fixture.model.requests.length, 2);
    assert.equal(fixture.ctx.get('droyNotifications'), undefined);
    const ticketEnd = fixture.requests.findIndex(value => value.path === '/task/end');
    const deactivate = fixture.requests.findIndex(value => value.operation === 'activate' && !value.enabled);
    assert.ok(ticketEnd >= 0 && deactivate > ticketEnd, 'native ticket cancellation precedes notification context removal');
  } finally { await fixture.close(); }
});

test('current Agent completes App send only with the post-action target conversation observation', async () => {
  const fixture = await harness([call('prepare', 'droy_notify', send('app')), call('send-click', 'android_tap'),
    call('after-send', 'android_observe'), call('record', 'droy_notify', {
      operation: 'complete', noticeId: 'fixture-notice', status: 'sent_in_app', evidenceCallId: 'after-send',
    }), text('测试会话中已观察到消息')]);
  try {
    await fixture.run();
    const complete = fixture.requests.find(value => value.operation === 'complete');
    assert.equal(complete.status, 'sent_in_app'); assert.equal(complete.evidence.snapshotId, 'after-send');
    assert.equal(complete.evidence.basis, 'post_send_page_observation');
    assert.equal(fixture.requests.filter(value => value.operation === 'send').length, 1);
    const prepare = fixture.agent.session.snapshotEvents().find(event => event.type === 'tool/result'
      && event.data.message.toolCallId === 'prepare');
    assert.match(JSON.stringify(prepare.data.message.content), /requires_action/);
  } finally { await fixture.close(); }
});

test('current visual Agent may complete App notification using a real post-action screenshot result', async () => {
  const fixture = await harness([call('prepare', 'droy_notify', send('app')), call('send-click', 'android_tap'),
    call('after-image', 'android_screenshot'), call('record', 'droy_notify', {
      operation: 'complete', noticeId: 'fixture-notice', status: 'sent_in_app', evidenceCallId: 'after-image',
    }), text('从截图核实测试会话中的消息')]);
  try {
    await fixture.run();
    const complete = fixture.requests.find(value => value.operation === 'complete');
    assert.equal(complete.status, 'sent_in_app');
    assert.equal(complete.evidence.callId, 'after-image');
    assert.equal(complete.evidence.snapshotId, 'after-send-image');
    assert.equal(complete.evidence.basis, 'post_send_image_observation');
    const imageResult = fixture.agent.session.snapshotEvents().find(event => event.type === 'tool/result'
      && event.data.message.toolCallId === 'after-image');
    assert.ok(imageResult.data.message.content.some(block => block.type === 'image'));
  } finally { await fixture.close(); }
});

test('captured pixels remain valid evidence without a coordinate basis when the unchanged post-send app is observed', async () => {
  const fixture = await harness([call('prepare', 'droy_notify', send('app')), call('send-click', 'android_tap'),
    call('app-context', 'android_observe'), call('after-image', 'android_screenshot'), call('record', 'droy_notify', {
      operation: 'complete', noticeId: 'fixture-notice', status: 'sent_in_app', evidenceCallId: 'after-image',
    }), text('从截图核实结果')], {
    observation: { status: 'observed', snapshotId: 'text-context', packageName: 'example.messages',
      nodes: [{ text: '虚构任务结果已准备好', editable: false }] },
    screenshot: { status: 'captured', coordinateBasisStatus: 'unavailable', coordinateBasisReason: 'screen_changed_during_capture' },
  });
  try {
    await fixture.run();
    const complete = fixture.requests.find(value => value.operation === 'complete');
    assert.equal(complete.status, 'sent_in_app');
    assert.equal(complete.evidence.basis, 'post_send_image_observation');
    assert.equal(complete.evidence.observeCallId, 'app-context');
    assert.equal(complete.evidence.attachment.attachmentId, 'b'.repeat(64));
    assert.equal(Object.hasOwn(complete.evidence, 'snapshotId'), false);
  } finally { await fixture.close(); }
});

for (const variant of ['wrong-context-app', 'page-changed', 'no-app-context']) test(`App screenshot without coordinate basis ${variant} remains unknown`, async () => {
  const script = [call('prepare', 'droy_notify', send('app')), call('click', 'android_tap'),
    ...(variant === 'no-app-context' ? [] : [call('app-context', 'android_observe')]),
    ...(variant === 'page-changed' ? [call('switch-app', 'android_open_app')] : []),
    call('image', 'android_screenshot'), call('record', 'droy_notify', { operation: 'complete', noticeId: 'fixture-notice',
      status: 'sent_in_app', evidenceCallId: 'image' }), text('图像来源不足，保留待核实结果')];
  const fixture = await harness(script, {
    observation: { status: 'observed', snapshotId: 'context', packageName: variant === 'wrong-context-app' ? 'example.other' : 'example.messages', nodes: [] },
    screenshot: { status: 'captured', coordinateBasisStatus: 'unavailable' },
  });
  try {
    await fixture.run();
    assert.equal(fixture.requests.find(value => value.operation === 'complete').status, 'unknown');
    assert.equal(fixture.requests.filter(value => value.operation === 'send').length, 1);
  } finally { await fixture.close(); }
});

for (const variant of ['wrong-app', 'before-send', 'missing-image', 'without-action']) test(`App screenshot ${variant} remains unknown`, async () => {
  const screenshot = { status: 'captured', snapshotId: 'image', packageName: variant === 'wrong-app' ? 'example.other' : 'example.messages' };
  const script = variant === 'before-send' ? [call('image', 'android_screenshot'), call('prepare', 'droy_notify', send('app')), call('click', 'android_tap')]
    : [call('prepare', 'droy_notify', send('app')), ...(variant === 'without-action' ? [] : [call('click', 'android_tap')]), call('image', 'android_screenshot')];
  script.push(call('record', 'droy_notify', { operation: 'complete', noticeId: 'fixture-notice', status: 'sent_in_app',
    evidenceCallId: 'image' }), text('截图证据不足，保留待核实结果'));
  const fixture = await harness(script, { screenshot, screenshotImage: variant !== 'missing-image' });
  try {
    await fixture.run();
    assert.equal(fixture.requests.find(value => value.operation === 'complete').status, 'unknown');
    assert.equal(fixture.requests.filter(value => value.operation === 'send').length, 1);
  } finally { await fixture.close(); }
});

for (const variant of ['preparation', 'input-only', 'wrong-app', 'wrong-target']) test(`App ${variant} evidence remains unknown and never retries`, async () => {
  const observation = { snapshotId: 'observed', packageName: variant === 'wrong-app' ? 'example.other' : 'example.messages',
    nodes: [{ text: variant === 'wrong-target' ? '其他会话' : '测试助手', editable: false },
      { text: '虚构任务结果已准备好', editable: variant === 'input-only' }] };
  const script = variant === 'preparation'
    ? [call('old', 'android_observe'), call('prepare', 'droy_notify', send('app'))]
    : [call('prepare', 'droy_notify', send('app')), call('click', 'android_tap'), call('after', 'android_observe')];
  script.push(call('record', 'droy_notify', { operation: 'complete', noticeId: 'fixture-notice', status: 'sent_in_app',
    evidenceCallId: variant === 'preparation' ? 'old' : 'after' }), text('发送结果待核实'));
  const fixture = await harness(script, { observation });
  try {
    await fixture.run();
    assert.equal(fixture.requests.find(value => value.operation === 'complete').status, 'unknown');
    assert.equal(fixture.requests.filter(value => value.operation === 'send').length, 1);
  } finally { await fixture.close(); }
});

test('new channel registration participates in discovery and disappears on unregister', async () => {
  const fixture = await harness([]);
  try {
    const service = fixture.ctx.get('droyNotifications');
    const dispose = service.registerChannel({ id: 'fixture-client', describe: () => ({ available: true, description: 'Local fixture' }),
      async send() { throw new Error('Not sent by discovery'); } });
    assert.ok((await service.channels()).channels.some(value => value.id === 'fixture-client'));
    dispose(); assert.ok(!(await service.channels()).channels.some(value => value.id === 'fixture-client'));
  } finally { await fixture.close(); }
});

test('channel discovery exposes configured grants without disclosing the installed-app picker inventory', async () => {
  const fixture = await harness([], { native: request => request.operation === 'get' ? {
    pluginActive: true, localEnabled: true, appsEnabled: true,
    apps: [{ packageName: 'example.private', label: 'Private installed app' }],
    allowedApps: [{ packageName: 'example.messages', label: 'Messages', target: '测试助手' }],
    channels: [{ id: 'system', available: true }, { id: 'app', available: false, reason: '应用读取已关闭' }],
    context: { returnOnCompletion: false },
  } : undefined });
  try {
    const service = fixture.ctx.get('droyNotifications');
    let providerState;
    service.registerChannel({ id: 'fixture-client', describe(state) { providerState = state; return { available: false }; },
      async send() { throw new Error('Discovery only'); } });
    const channels = await service.channels();
    assert.equal(channels.apps, undefined); assert.equal(providerState.apps, undefined);
    assert.equal(JSON.stringify(channels).includes('example.private'), false);
    assert.equal(channels.allowedApps[0].packageName, 'example.messages');
    assert.equal(channels.channels.find(value => value.id === 'app').available, false);
  } finally { await fixture.close(); }
});

test('host notification enablement control persists through plugin management and remains registered when disabled', async () => {
  let enabled = true;
  const routes = new Map(), changes = [];
  const row = () => ({ entryId: 'droy-notifications', moduleName: '@droyagent/mobile/notifications', enabled });
  const ctx = { webServer: { register(value) { routes.set(value.path, value.handler); } }, logger: { warn() {} },
    get(name) { return name === 'pluginManager' ? { async listPlugins() { return [row()]; }, async setPluginEnabled(id, value) {
      changes.push({ id, value }); enabled = value; return { application: 'applied' };
    } } : name === 'droyNotifications' && enabled ? { active: true } : undefined; } };
  installNotificationControl(ctx);
  const before = process.env.DROY_WEB_TOKEN; process.env.DROY_WEB_TOKEN = TOKEN;
  async function request(method, body, cookie = `droy_session=${TOKEN}`) {
    const req = Readable.from(body === undefined ? [] : [JSON.stringify(body)]);
    req.headers = { host: '127.0.0.1:3000', cookie }; req.method = method;
    const res = { writeHead(status) { this.status = status; }, end(text) { this.body = text; } };
    await routes.get('/droy/notify/plugin')(req, res);
    return { status: res.status, body: res.body.startsWith('{') ? JSON.parse(res.body) : {} };
  }
  try {
    assert.equal((await request('GET', undefined, '')).status, 401);
    assert.equal((await request('POST', { enabled: false })).body.enabled, false);
    assert.equal((await request('GET')).body.active, false);
    assert.equal((await request('POST', { enabled: true })).body.active, true);
    assert.deepEqual(changes, [{ id: 'droy-notifications', value: false }, { id: 'droy-notifications', value: true }]);
    assert.equal((await request('POST', { enabled: 'yes' })).status, 400);
  } finally { if (before === undefined) delete process.env.DROY_WEB_TOKEN; else process.env.DROY_WEB_TOKEN = before; }
});

test('host control reports plugin-management rejection as a failed request', async () => {
  let route;
  const ctx = { webServer: { register(value) { route = value.handler; } }, logger: { warn() {} }, get() { return {
    async listPlugins() { return [{ entryId: 'droy-notifications', moduleName: '@droyagent/mobile/notifications', enabled: true }]; },
    async setPluginEnabled() { return { application: 'overridden' }; },
  }; } };
  installNotificationControl(ctx);
  const before = process.env.DROY_WEB_TOKEN; process.env.DROY_WEB_TOKEN = TOKEN;
  try {
    const req = Readable.from(['{"enabled":false}']); req.method = 'POST';
    req.headers = { host: '127.0.0.1:3000', cookie: `droy_session=${TOKEN}` };
    const res = { writeHead(status) { this.status = status; }, end(value) { this.body = JSON.parse(value); } };
    await route(req, res); assert.equal(res.status, 409); assert.equal(res.body.error, 'NOTIFICATION_PLUGIN_CHANGE_FAILED');
  } finally { if (before === undefined) delete process.env.DROY_WEB_TOKEN; else process.env.DROY_WEB_TOKEN = before; }
});
