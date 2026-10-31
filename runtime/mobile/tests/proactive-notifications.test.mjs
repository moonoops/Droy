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


test('notification schema only exposes local delivery and unload leaves Android Use available', async () => {
  const fixture = await harness([text('No notification needed')]);
  try {
    const schemas = fixture.ctx.tools.schemas();
    const schema = schemas.find(value => value.name === 'droy_notify');
    assert.match(schema.description, /单一渠道/);
    assert.doesNotMatch(JSON.stringify(schema), /requires_action|sent_in_app|packageName|evidenceCallId/);
    assert.deepEqual(notifications.notifyTool.parameters.operation.enum, ['send']);
    assert.deepEqual(notifications.notifyTool.parameters.destinations.items.properties.channel.enum, ['system', 'pet']);
    await fixture.run();
    assert.equal(fixture.requests.filter(value => value.operation === 'send').length, 0);
    await fixture.fiber.dispose();
    assert.ok(!fixture.ctx.tools.schemas().some(value => value.name === 'droy_notify'));
    assert.ok(fixture.ctx.tools.schemas().some(value => value.name === 'android_observe'));
    assert.equal(fixture.requests.at(-1).enabled, false);
  } finally { await fixture.close(); }
});

test('same-session repeated tasks carry distinct trusted turn identities', async () => {
  const fixture = await harness([call('first', 'droy_notify', send()), text('First completed'),
    call('second', 'droy_notify', send()), text('Second completed')]);
  try {
    await fixture.run(); await fixture.run();
    const requests = fixture.requests.filter(value => value.operation === 'send');
    assert.equal(requests.length, 2);
    assert.equal(requests[0].taskId, requests[1].taskId);
    assert.notEqual(requests[0].turnId, requests[1].turnId);
    assert.equal(requests[0].noticeKey, requests[1].noticeKey);
    assert.equal(requests[0].attention, 'quiet');
  } finally { await fixture.close(); }
});

test('retired App destination cannot prepare or execute an external notification', async () => {
  const fixture = await harness([call('retired', 'droy_notify', send('app')), text('Unsupported destination')]);
  try {
    await fixture.run();
    assert.equal(fixture.requests.filter(value => value.operation === 'send').length, 0);
    assert.equal(fixture.requests.filter(value => value.operation === 'complete').length, 0);
    assert.ok(!fixture.agent.session.snapshotEvents().some(event => event.type === 'tool/call'
      && event.data.name.startsWith('android_')));
  } finally { await fixture.close(); }
});

test('disabling notifications leaves ordinary task execution running', async () => {
  const started = deferred(), proceed = deferred(); let cancelled = false;
  const fixture = await harness([call('local-result', 'droy_notify', send()), call('ordinary-action', 'android_tap'), text('Task completed')], {
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

test('channel discovery strips retired external grants and installed-app inventory', async () => {
  const fixture = await harness([], { native: request => request.operation === 'get' ? {
    pluginActive: true, tasksEnabled: true,
    apps: [{ packageName: 'example.private' }], allowedApps: [{ packageName: 'example.messages', target: 'Private recipient' }],
    channels: [{ id: 'system', available: true }, { id: 'pet', available: false }, { id: 'app', available: true }],
  } : undefined });
  try {
    const state = await fixture.ctx.get('droyNotifications').channels();
    assert.deepEqual(state.channels.map(value => value.id), ['system', 'pet']);
    assert.equal(state.allowedApps, undefined); assert.equal(state.apps, undefined);
    assert.doesNotMatch(JSON.stringify(state), /example\.|Private recipient/);
  } finally { await fixture.close(); }
});

test('dispose while activation is pending never permits a late send', async () => {
  const activation = deferred(), calls = [];
  const service = notifications.createNotificationService({}, { async notify(value) { calls.push(value); return {}; } }, activation.promise);
  const stopped = service.dispose();
  const sendAfter = service.send(send(), { signal: new AbortController().signal });
  activation.resolve(); await stopped;
  await assert.rejects(sendAfter, /DROY_NOTIFICATIONS_DISABLED/);
  assert.deepEqual(calls, [{ operation: 'activate', enabled: false }]);
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
