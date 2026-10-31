import test, { after } from 'node:test';
import assert from 'node:assert/strict';
import { createServer } from 'node:http';
import { once } from 'node:events';
import { setTimeout as delay } from 'node:timers/promises';
import { createRequire, registerHooks } from 'node:module';
import { pathToFileURL } from 'node:url';
import { createNativeDecisions } from '../native-decisions.mjs';

const installed = createRequire(new URL('../../dsh/package.json', import.meta.url));
const hooks = registerHooks({ resolve(specifier, context, next) {
  if (specifier.startsWith('@deepseek-ai/')) return { url: pathToFileURL(installed.resolve(specifier)).href, shortCircuit: true };
  return next(specifier, context);
} });
const { Context } = await import('@deepseek-ai/cordis');
const { default: ApprovalService } = await import('@deepseek-ai/dsh-user-approval');
const { default: UserQuestions } = await import('@deepseek-ai/dsh-user-questions');
const { installPetInteractions } = await import('../pet-interactions.mjs');
hooks.deregister();

const token = 'fictional-native-decision-cookie-not-a-real-credential-123456';
const previousToken = process.env.DROY_WEB_TOKEN;
process.env.DROY_WEB_TOKEN = token;
after(() => {
  if (previousToken === undefined) delete process.env.DROY_WEB_TOKEN;
  else process.env.DROY_WEB_TOKEN = previousToken;
});
const deferred = () => { let resolve; const promise = new Promise(yes => { resolve = yes; }); return { promise, resolve }; };
function session(id) {
  const events = [{ type: 'turn/start', seq: 0, data: { turn: 0 } }];
  return { id, get seq() { return events.length; }, eventAt(seq) { return events[seq]; },
    append(type, data) { const event = { seq: events.length, type, data }; events.push(event); return event; }, events };
}
function agent(id) {
  const value = { id, session: session(id), inbox: { nextTurn: [], nextStep: [] }, status: 'running' };
  value.steer = message => value.inbox.nextStep.push(message);
  return value;
}
async function fixture(t, locale = 'en', beforeBroker) {
  const ctx = new Context();
  const current = agent('fictional-work-session'), managed = agent('fictional-managed-session'), child = agent('fictional-child-session');
  const agents = new Map([current, managed, child].map(value => [value.id, value]));
  const roots = [current, managed];
  const views = new Map(), changed = new Set(), routes = new Map();
  ctx.provide('webServer', { register(route) { routes.set(route.path, route); } });
  ctx.provide('agents', { get: id => agents.get(id), roots: () => [...roots] });
  ctx.provide('settings', { describe: () => [{ ns: 'locale', value: { preference: locale } }] });
  const releaseCompanions = ctx.provide('droyCompanions', { ownsSession: id => id === managed.id });
  ctx.provide('sessionProjections', {
    register() { return () => {}; },
    onChanged(listener) { changed.add(listener); return () => changed.delete(listener); },
    snapshot(value) { return { asOfSeq: value.seq - 1, values: { userQuestions: views.get(value.id) ?? { active: [], settled: [] } } }; },
    stateOf(value) { return { questions: views.get(value.id) ?? { active: [], settled: [] } }; },
  });
  await ctx.plugin(ApprovalService, { policy: 'ask' });
  await ctx.plugin(UserQuestions);
  beforeBroker?.(ctx);
  const broker = createNativeDecisions(ctx);
  const streams = [];
  const server = createServer((req, res) => {
    if (req.url === '/droy/native/decisions' && req.method === 'GET') {
      const closed = deferred(); res.once('close', closed.resolve); streams.push(closed);
    }
    const route = routes.get(req.url?.split('?')[0]);
    if (route) route.handler(req, res); else broker.handle(req, res);
  });
  server.listen(0, '127.0.0.1'); await once(server, 'listening');
  const url = `http://127.0.0.1:${server.address().port}/droy/native/decisions`;
  const headers = { Cookie: `droy_session=${token}`, 'Content-Type': 'application/json' };
  t.after(async () => {
    broker.dispose(); server.closeAllConnections();
    await new Promise(resolve => server.close(resolve));
    await ctx.fiber.dispose();
  });
  async function open() {
    const abort = new AbortController();
    const response = await fetch(url, { headers, signal: abort.signal });
    assert.equal(response.status, 200);
    assert.match(response.headers.get('content-type'), /^text\/event-stream/);
    const closed = streams.at(-1);
    const reader = response.body.getReader(), decoder = new TextDecoder();
    let buffer = '';
    return {
      async frame() {
        const timeout = setTimeout(() => abort.abort(), 1500); timeout.unref();
        try {
          for (;;) {
            const boundary = buffer.indexOf('\n\n');
            if (boundary >= 0) {
              const message = buffer.slice(0, boundary); buffer = buffer.slice(boundary + 2);
              const data = message.split('\n').filter(line => line.startsWith('data: ')).map(line => line.slice(6)).join('\n');
              if (data) return JSON.parse(data);
              continue;
            }
            const next = await reader.read();
            if (next.done) throw new Error('Decision stream ended before a frame');
            buffer += decoder.decode(next.value, { stream: true });
          }
        } finally { clearTimeout(timeout); }
      },
      async close() { abort.abort(); await reader.cancel().catch(() => {}); await closed.promise; },
    };
  }
  return { ctx, current, managed, child, agents, roots, broker, url, headers, routes, open, releaseCompanions,
    post(id, value, extra = {}) { return fetch(`${url}/${id}/answer`, { method: 'POST', headers, body: JSON.stringify(value), ...extra }); },
    projection(value, active) {
      views.set(value.id, { active, settled: [] });
      for (const listener of changed) listener(value.session, 'userQuestions', views.get(value.id), value.session.seq - 1);
    },
    setProjection(value, active) { views.set(value.id, { active, settled: [] }); },
  };
}

function callApproval(f, request = {}, value = f.current) {
  return f.ctx.approval.request({ agent: value, toolName: 'fictional_action', reason: 'Run this fictional action once?', ...request });
}
function ask(f, questions, extra = {}) { return f.ctx.userQuestions.ask({ agent: f.current, questions, ...extra }); }
const oneQuestion = [{ id: 'choice', question: 'Choose a fictional option', options: [{ label: 'Alpha' }, { label: 'Beta' }] }];

test('native answerer delegates when no native channel exists; real approval service retains the audit pair', async t => {
  const f = await fixture(t);
  assert.equal(await callApproval(f), 'unavailable');
  const audit = f.current.session.events.filter(event => event.type.startsWith('approval/'));
  assert.deepEqual(audit.map(event => event.type), ['approval/asked', 'approval/decided']);
  assert.equal(audit[0].data.id, audit[1].data.id);
  assert.equal(audit[1].data.outcome, 'unavailable');
  await assert.rejects(ask(f, oneQuestion), error => error.code === 'NO_PROVIDER');
});

test('real DSH approval audit is settled only by an explicit correctly scoped one-shot answer', async t => {
  const f = await fixture(t), stream = await f.open();
  assert.deepEqual(await stream.frame(), { type: 'snapshot', decisions: [] });
  f.current.session.append('tool/call', { turn: 0, step: 0, callId: 'fictional-call', name: 'fictional_action', arguments: '{"command":"echo fictional"}' });
  const result = callApproval(f, { callId: 'fictional-call' });
  const { decision } = await stream.frame();
  assert.equal(decision.canApprove, true);
  assert.equal(decision.toolArguments, '{"command":"echo fictional"}');
  assert.equal(decision.callId, 'fictional-call');
  assert.equal(f.current.session.events.at(-1).type, 'approval/asked');
  const denied = await f.post(decision.id, { sessionId: f.managed.id, outcome: 'allowed-once' });
  assert.equal(denied.status, 403);
  assert.equal(f.current.session.events.at(-1).type, 'approval/asked');
  const invalid = await f.post(decision.id, { sessionId: f.current.id, outcome: 'always-allow' });
  assert.equal(invalid.status, 400);
  const response = await f.post(decision.id, { sessionId: f.current.id, outcome: 'allowed-once' });
  assert.deepEqual(await response.json(), { ok: true, result: { accepted: true } });
  assert.equal(await result, 'allowed-once');
  const asked = f.current.session.events.find(event => event.type === 'approval/asked');
  assert.deepEqual(f.current.session.events.at(-1).data, { id: asked.data.id, outcome: 'allowed-once' });
  assert.deepEqual(await stream.frame(), { type: 'remove', id: decision.id, reason: 'answered' });
  assert.equal((await f.post(decision.id, { sessionId: f.current.id, outcome: 'allowed-once' })).status, 410);
});

test('connected native answers precede an earlier Remote forwarder; offline native delegates', async t => {
  const forwarded = [];
  const f = await fixture(t, 'en', ctx => {
    ctx.on('approval/request', request => { forwarded.push(request); return Promise.resolve('rejected'); });
    ctx.on('user-questions/request', request => {
      forwarded.push(request); return Promise.resolve({ answers: [{ id: 'choice', selected: ['Beta'] }] });
    });
  });
  const stream = await f.open(); await stream.frame();
  f.current.session.append('tool/call', { turn: 0, step: 0, callId: 'native-precedes-remote', name: 'fictional_action', arguments: '{}' });
  const approval = callApproval(f, { callId: 'native-precedes-remote' });
  const { decision } = await stream.frame();
  assert.equal(decision.canApprove, true);
  assert.equal(forwarded.length, 0);
  assert.equal((await f.post(decision.id, { sessionId: f.current.id, outcome: 'allowed-once' })).status, 200);
  assert.equal(await approval, 'allowed-once'); await stream.frame();
  const question = ask(f, oneQuestion), questionCard = (await stream.frame()).decision;
  assert.equal(forwarded.length, 0);
  await f.post(questionCard.id, { sessionId: f.current.id, answers: [{ id: 'choice', selected: ['Alpha'] }] });
  assert.deepEqual(await question, { answers: [{ id: 'choice', selected: ['Alpha'] }] }); await stream.frame();
  await stream.close();
  assert.equal(await callApproval(f), 'rejected');
  assert.deepEqual(await ask(f, oneQuestion), { answers: [{ id: 'choice', selected: ['Beta'] }] });
  assert.equal(forwarded.length, 2);
});

test('missing companion ownership service cannot claim or settle a connected native request', async t => {
  const f = await fixture(t), stream = await f.open(); await stream.frame();
  const approval = callApproval(f), { decision } = await stream.frame();
  await f.releaseCompanions();
  assert.equal((await f.post(decision.id, { sessionId: f.current.id, outcome: 'allowed-once' })).status, 410);
  assert.equal(await approval, 'cancelled');
  assert.deepEqual(await stream.frame(), { type: 'remove', id: decision.id, reason: 'cancelled' });
  assert.equal(await callApproval(f), 'unavailable');
  await assert.rejects(ask(f, oneQuestion), error => error.code === 'NO_PROVIDER');
});

test('missing or completed tool context cannot grant approval; rejection remains available', async t => {
  const f = await fixture(t), stream = await f.open(); await stream.frame();
  const running = callApproval(f, { callId: 'unknown-call' });
  const { decision } = await stream.frame();
  assert.equal(decision.canApprove, false);
  assert.equal((await f.post(decision.id, { sessionId: f.current.id, outcome: 'allowed-once' })).status, 409);
  assert.equal((await f.post(decision.id, { sessionId: f.current.id, outcome: 'rejected' })).status, 200);
  assert.equal(await running, 'rejected'); await stream.frame();
  f.current.session.append('tool/call', { turn: 0, step: 0, callId: 'later-ended-call', name: 'fictional_action', arguments: '{}' });
  const later = callApproval(f, { callId: 'later-ended-call' });
  const next = (await stream.frame()).decision;
  assert.equal(next.canApprove, true);
  f.current.session.append('tool/result', { turn: 0, step: 0, message: { toolCallId: 'later-ended-call' } });
  assert.equal((await f.post(next.id, { sessionId: f.current.id, outcome: 'allowed-once' })).status, 409);
  await f.post(next.id, { sessionId: f.current.id, outcome: 'rejected' });
  assert.equal(await later, 'rejected');
});

test('cancelled and replaced live agents cannot receive late answers', async t => {
  const f = await fixture(t), stream = await f.open(); await stream.frame();
  const abort = new AbortController();
  const running = callApproval(f, { signal: abort.signal });
  const { decision } = await stream.frame();
  abort.abort();
  assert.equal(await running, 'cancelled');
  assert.deepEqual(await stream.frame(), { type: 'remove', id: decision.id, reason: 'cancelled' });
  assert.equal((await f.post(decision.id, { sessionId: f.current.id, outcome: 'allowed-once' })).status, 410);
  const replaced = callApproval(f), next = (await stream.frame()).decision;
  f.agents.set(f.current.id, agent(f.current.id));
  assert.equal((await f.post(next.id, { sessionId: f.current.id, outcome: 'allowed-once' })).status, 410);
  assert.equal(await replaced, 'cancelled');
});

test('channel disconnect preserves a live pending decision and reconnect does not approve it', async t => {
  const f = await fixture(t), first = await f.open(); await first.frame();
  const result = callApproval(f), { decision } = await first.frame();
  await first.close();
  assert.equal(f.current.session.events.at(-1).type, 'approval/asked');
  const second = await f.open(), baseline = await second.frame();
  assert.equal(baseline.type, 'snapshot');
  assert.deepEqual(baseline.decisions, [decision]);
  assert.equal(f.current.session.events.at(-1).type, 'approval/asked');
  await f.post(decision.id, { sessionId: f.current.id, outcome: 'rejected' });
  assert.equal(await result, 'rejected');
});

test('managed companions and delegated agents are never claimed by the workbench answerer', async t => {
  const f = await fixture(t), stream = await f.open(); await stream.frame();
  assert.equal(await callApproval(f, {}, f.managed), 'unavailable');
  assert.equal(await callApproval(f, {}, f.child), 'unavailable');
  await assert.rejects(f.ctx.userQuestions.ask({ agent: f.managed, questions: oneQuestion }), error => error.code === 'NO_PROVIDER');
  await assert.rejects(f.ctx.userQuestions.ask({ agent: f.child, questions: oneQuestion }), error => error.code === 'DELEGATED_CALLER');
  const second = await f.open();
  assert.deepEqual(await second.frame(), { type: 'snapshot', decisions: [] });
});

test('question batches preserve multi-select/custom semantics and reject unknown or duplicate fields', async t => {
  const f = await fixture(t), stream = await f.open(); await stream.frame();
  const questions = [...oneQuestion, { id: 'tags', question: 'Choose tags', multiSelect: true,
    options: [{ label: 'One' }, { label: 'Two' }] }, { id: 'text', question: 'Write a fictional note' }];
  const result = ask(f, questions), { decision } = await stream.frame();
  assert.deepEqual(decision.questions, questions);
  const valid = [{ id: 'choice', selected: ['Alpha'] }, { id: 'tags', selected: ['One', 'Two'], custom: ' extra ' }, { id: 'text', selected: [], custom: 'note' }];
  for (const answers of [valid.slice(0, 1), [...valid, valid[0]],
    [{ ...valid[0], selected: ['Unknown'] }, ...valid.slice(1)],
    [{ ...valid[0], selected: ['Alpha', 'Beta'] }, ...valid.slice(1)],
    [{ ...valid[0], custom: 'both' }, ...valid.slice(1)],
    [{ ...valid[0], injected: 'not accepted' }, ...valid.slice(1)]]) {
    assert.equal((await f.post(decision.id, { sessionId: f.current.id, answers })).status, 400);
  }
  assert.equal((await f.post(decision.id, { sessionId: f.current.id, answers: valid, extra: true })).status, 400);
  const response = await f.post(decision.id, { sessionId: f.current.id, answers: valid });
  assert.equal(response.status, 200);
  assert.deepEqual(await result, { answers: [valid[0], { ...valid[1], custom: 'extra' }, valid[2]] });
  assert.equal((await stream.frame()).type, 'remove');
  assert.equal((await f.post(decision.id, { sessionId: f.current.id, answers: valid })).status, 410);
});

test('plan-review intent and full detail survive presentation without inferring an approval from option order', async t => {
  const f = await fixture(t), stream = await f.open(); await stream.frame();
  const questions = [{ id: 'plan', question: 'Review this fictional plan', detail: '# Fictional plan\nPerform only the fixture.',
    options: [{ label: 'Revise' }, { label: 'Approve' }], intent: { kind: 'plan-review', approve: 'Approve', callId: 'plan-source' } }];
  const result = ask(f, questions), { decision } = await stream.frame();
  assert.deepEqual(decision.questions, questions);
  await f.post(decision.id, { sessionId: f.current.id, answers: [{ id: 'plan', selected: ['Revise'] }] });
  assert.deepEqual(await result, { answers: [{ id: 'plan', selected: ['Revise'] }] });
});

test('timed question expires through the real Host wait and rejects a stale foreground answer', async t => {
  const f = await fixture(t), stream = await f.open(); await stream.frame();
  const result = f.ctx.userQuestions.askTimed({ agent: f.current, questions: oneQuestion }, 'timed-fictional-call', 250);
  const { decision } = await stream.frame();
  assert.equal(decision.state, 'open');
  const deadline = await stream.frame();
  assert.equal(deadline.decision.id, decision.id);
  assert.ok(deadline.decision.expiresAt >= Date.now() - 1000);
  assert.deepEqual(await result, { pending: true, callId: 'timed-fictional-call' });
  assert.deepEqual(await stream.frame(), { type: 'remove', id: decision.id, reason: 'expired' });
  assert.equal((await f.post(decision.id, { sessionId: f.current.id, answers: [{ id: 'choice', selected: ['Alpha'] }] })).status, 410);
});

test('continued questions use the public DSH answer method once and remain answerPending until admitted', async t => {
  const f = await fixture(t);
  const continued = { callId: 'continued-fictional-call', state: 'continued', questions: oneQuestion };
  f.setProjection(f.current, [continued]);
  const stream = await f.open(), baseline = await stream.frame();
  assert.equal(baseline.decisions.length, 1);
  const decision = baseline.decisions[0];
  assert.equal(decision.state, 'continued'); assert.equal(decision.answerPending, false);
  const answer = { sessionId: f.current.id, answers: [{ id: 'choice', selected: ['Beta'] }] };
  assert.equal((await f.post(decision.id, answer)).status, 200);
  const upsert = await stream.frame();
  assert.equal(upsert.decision.answerPending, true);
  assert.equal(f.current.inbox.nextStep.length, 1);
  assert.equal(f.current.inbox.nextStep[0].source.kind, 'user-question-reply');
  assert.equal(f.current.inbox.nextStep[0].source.callId, continued.callId);
  assert.equal((await f.post(decision.id, answer)).status, 409);
  assert.equal(f.current.inbox.nextStep.length, 1);
  const second = await f.open(), replay = await second.frame();
  assert.equal(replay.decisions[0].answerPending, true);
  f.projection(f.current, []);
  assert.equal((await stream.frame()).type, 'remove');
  assert.equal((await f.post(decision.id, answer)).status, 410);
});

test('continued replies already present in the public inbox are disabled on initial native snapshot', async t => {
  const f = await fixture(t), continued = { callId: 'already-queued', state: 'continued', questions: oneQuestion };
  f.setProjection(f.current, [continued]);
  f.current.inbox.nextTurn.push({ source: { kind: 'user-question-reply', callId: continued.callId } });
  const stream = await f.open(), baseline = await stream.frame();
  assert.equal(baseline.decisions[0].answerPending, true);
  assert.equal((await f.post(baseline.decisions[0].id, { sessionId: f.current.id, answers: [{ id: 'choice', selected: ['Alpha'] }] })).status, 409);
  assert.equal(f.current.inbox.nextStep.length, 0);
});

test('authentication, method, JSON shape and body limits fail before an answer can settle', async t => {
  const f = await fixture(t, 'zh'), stream = await f.open(); await stream.frame();
  const result = callApproval(f), { decision } = await stream.frame();
  assert.equal((await fetch(f.url)).status, 401);
  assert.equal((await f.post(decision.id, { sessionId: f.current.id, outcome: 'allowed-once' }, { headers: { ...f.headers, Origin: 'https://untrusted.invalid' } })).status, 401);
  const malformed = await f.post(decision.id, {}, { body: Buffer.from([0xff]) });
  assert.equal(malformed.status, 400); assert.equal((await malformed.json()).error.message, '回答格式无效。');
  assert.equal((await f.post(decision.id, {}, { body: 'x'.repeat(131073) })).status, 413);
  assert.equal((await f.post(decision.id, {}, { body: 'null' })).status, 400);
  assert.equal((await fetch(`${f.url}/${decision.id}/answer`, { headers: f.headers })).status, 405);
  assert.equal((await fetch(`${f.url}?injected=true`, { headers: f.headers })).status, 400);
  assert.equal(f.current.session.events.at(-1).type, 'approval/asked');
  await f.post(decision.id, { sessionId: f.current.id, outcome: 'rejected' });
  assert.equal(await result, 'rejected');
});

test('dispose withdraws pending answers and stops accepting new native requests', async t => {
  const f = await fixture(t), stream = await f.open(); await stream.frame();
  const result = callApproval(f), { decision } = await stream.frame();
  f.broker.dispose();
  assert.equal(await result, 'cancelled');
  assert.equal((await f.post(decision.id, { sessionId: f.current.id, outcome: 'allowed-once' })).status, 503);
});

test('upstream never policy rejects without presenting a native approval', async t => {
  const f = await fixture(t), stream = await f.open(); await stream.frame();
  f.ctx.approval.config.policy = 'never';
  assert.equal(await callApproval(f), 'rejected');
  const second = await f.open();
  assert.deepEqual(await second.frame(), { type: 'snapshot', decisions: [] });
  assert.equal(f.current.session.events.at(-1).data.outcome, 'rejected');
});

test('cancelled question and agent idle both withdraw the native card instead of accepting a stale answer', async t => {
  const f = await fixture(t), stream = await f.open(); await stream.frame();
  const abort = new AbortController(), running = ask(f, oneQuestion, { signal: abort.signal });
  const rejected = assert.rejects(running, error => error.code === 'ASK_ABORTED');
  const { decision } = await stream.frame();
  abort.abort(); await rejected;
  assert.equal((await stream.frame()).reason, 'cancelled');
  assert.equal((await f.post(decision.id, { sessionId: f.current.id, answers: [{ id: 'choice', selected: ['Alpha'] }] })).status, 410);
  const approval = callApproval(f), next = (await stream.frame()).decision;
  f.ctx.emit('agent/status', { agent: f.current, status: 'idle' });
  assert.equal(await approval, 'cancelled');
  assert.equal((await stream.frame()).id, next.id);
});

test('slow native observers receive one fresh snapshot after backpressure instead of an unbounded event queue', async t => {
  const f = await fixture(t);
  const { EventEmitter } = await import('node:events');
  const res = new EventEmitter(), wire = [];
  res.destroyed = false; res.writableEnded = false; res.blockWrites = true;
  res.writeHead = () => { res.headersSent = true; };
  res.flushHeaders = () => {};
  res.write = text => { wire.push(text); return !res.blockWrites; };
  res.end = () => { res.writableEnded = true; res.emit('close'); };
  await f.broker.handle({ method: 'GET', url: '/droy/native/decisions', headers: { host: '127.0.0.1:12345', cookie: `droy_session=${token}` } }, res);
  assert.equal(wire.length, 1);
  const first = callApproval(f), second = callApproval(f);
  await new Promise(setImmediate);
  assert.equal(wire.length, 1);
  res.blockWrites = false; res.emit('drain');
  assert.equal(wire.length, 2);
  const frame = JSON.parse(wire.at(-1).slice('data: '.length).trim());
  assert.equal(frame.type, 'snapshot'); assert.equal(frame.decisions.length, 2);
  for (const decision of frame.decisions) await f.post(decision.id, { sessionId: f.current.id, outcome: 'rejected' });
  assert.deepEqual(await Promise.all([first, second]), ['rejected', 'rejected']);
  res.end();
});

test('existing pet observer and native page share one real approval audit and pending lifetime', async t => {
  const f = await fixture(t), waiting = [];
  installPetInteractions(f.ctx, { waiting(taskId, view) { waiting.push({ taskId, view }); } });
  const stream = await f.open(); await stream.frame();
  const result = callApproval(f), { decision } = await stream.frame();
  assert.equal(waiting.at(-1).taskId, f.current.id);
  assert.ok(waiting.at(-1).view.requestId);
  await f.post(decision.id, { sessionId: f.current.id, outcome: 'allowed-once' });
  assert.equal(await result, 'allowed-once');
  assert.equal(waiting.at(-1).view, null);
  assert.deepEqual(f.current.session.events.filter(event => event.type.startsWith('approval/')).map(event => event.type), ['approval/asked', 'approval/decided']);
});

test('an existing pet answer withdraws the borrowed native card and cannot be granted a second time', async t => {
  const f = await fixture(t);
  let waiting;
  installPetInteractions(f.ctx, { waiting(_taskId, view) { waiting = view; } });
  const stream = await f.open(); await stream.frame();
  const result = callApproval(f), { decision } = await stream.frame();
  const response = await fetch(new URL('/droy/pet/action', f.url), { method: 'POST', headers: f.headers,
    body: JSON.stringify({ taskId: f.current.id, action: 'answer', requestId: waiting.requestId, optionId: 'allow' }) });
  assert.equal(response.status, 200);
  assert.equal(await result, 'allowed-once');
  assert.deepEqual(await stream.frame(), { type: 'remove', id: decision.id, reason: 'cancelled' });
  assert.equal((await f.post(decision.id, { sessionId: f.current.id, outcome: 'allowed-once' })).status, 410);
  assert.equal(f.current.session.events.filter(event => event.type === 'approval/decided').length, 1);
});
