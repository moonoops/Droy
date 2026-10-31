import test from 'node:test';
import assert from 'node:assert/strict';
import { createBridge } from '../bridge.mjs';

const token = 'fictional-first-use-bridge-token-123456789';
const ok = result => ({ ok: true, json: async () => ({ ok: true, result }) });
const flush = () => new Promise(resolve => setImmediate(resolve));
const exec = signal => ({ agent: { id: 'trusted-task' }, callId: 'trusted-call', signal });
const aborted = signal => new Promise((_, reject) => {
  if (signal.aborted) reject(signal.reason);
  else signal.addEventListener('abort', () => reject(signal.reason), { once: true });
});

test('human approval precedes the action budget and releases the original call only once', async t => {
  t.mock.timers.enable({ apis: ['setTimeout'] });
  const calls = [], controller = new AbortController();
  let approve;
  const bridge = createBridge({ port: 12345, token, fetchImpl: async (url, options) => {
    const call = { path: new URL(url).pathname, body: JSON.parse(options.body) }; calls.push(call);
    if (call.path === '/permissions/preflight') return await new Promise(resolve => { approve = () => resolve(ok({})); });
    return ok({ receipt: 'one-actual-call' });
  } });
  const args = { taskId: 'forged', query: 'fixture only' };
  const pending = bridge.call('contacts_query', args, exec(controller.signal));
  t.mock.timers.tick(16000); await flush();
  assert.deepEqual(calls.map(call => call.path), ['/permissions/preflight']);
  assert.equal(calls[0].body.humanBudgetMs, 120000);
  assert.equal(calls[0].body.taskId, 'trusted-task'); assert.equal(calls[0].body.callId, 'trusted-call');
  approve(); assert.deepEqual(await pending, { receipt: 'one-actual-call' });
  assert.deepEqual(calls.map(call => call.path), ['/permissions/preflight', '/tool']);
  assert.equal(calls[1].body.budgetMs, 15000); assert.deepEqual(calls[1].body.args, args);
});

test('refused authorization never dispatches or retries the original action', async () => {
  const calls = [];
  const bridge = createBridge({ port: 12345, token, fetchImpl: async (url) => {
    calls.push(new URL(url).pathname);
    return { ok: true, json: async () => ({ ok: false, error: { code: 'PERMISSION_DENIED', reason: 'authorization_not_granted', message: 'fixture refusal' } }) };
  } });
  await assert.rejects(bridge.call('contacts_query', {}, exec(new AbortController().signal)), error => error.details.reason === 'authorization_not_granted');
  assert.deepEqual(calls, ['/permissions/preflight']);
});

test('an already cancelled call sends only its matching cancellation', async () => {
  const calls = [], controller = new AbortController(); controller.abort(new Error('stopped before authorization'));
  const bridge = createBridge({ port: 12345, token, fetchImpl: async (url, options) => {
    calls.push({ path: new URL(url).pathname, body: JSON.parse(options.body) }); return ok({});
  } });
  await assert.rejects(bridge.call('contacts_query', {}, exec(controller.signal)), /stopped before authorization/);
  assert.deepEqual(calls, [{ path: '/cancel', body: { taskId: 'trusted-task', callId: 'trusted-call' } }]);
});

test('cancelling a human wait waits for acknowledgement and cannot dispatch later', async () => {
  const calls = [], controller = new AbortController();
  let acknowledge;
  const bridge = createBridge({ port: 12345, token, fetchImpl: async (url, options) => {
    calls.push({ path: new URL(url).pathname, body: JSON.parse(options.body) });
    if (url.endsWith('/permissions/preflight')) return aborted(options.signal);
    return new Promise(resolve => { acknowledge = () => resolve(ok({})); });
  } });
  const pending = bridge.call('contacts_query', {}, exec(controller.signal));
  controller.abort(new Error('stop waiting'));
  let settled = false; pending.finally(() => { settled = true; }).catch(() => {});
  await flush(); assert.equal(settled, false);
  assert.deepEqual(calls.map(call => call.path), ['/permissions/preflight', '/cancel']);
  assert.deepEqual(calls[1].body, { taskId: 'trusted-task', callId: 'trusted-call' });
  acknowledge(); await assert.rejects(pending, /stop waiting/); await flush();
  assert.equal(calls.some(call => call.path === '/tool'), false);
});

test('cancellation at the authorization handoff is checked before the tool request', async () => {
  const calls = [], controller = new AbortController();
  const bridge = createBridge({ port: 12345, token, fetchImpl: async url => {
    calls.push(new URL(url).pathname);
    if (url.endsWith('/permissions/preflight')) return { ok: true, json: async () => {
      controller.abort(new Error('handoff stopped')); return { ok: true, result: {} };
    } };
    return ok({});
  } });
  await assert.rejects(bridge.call('contacts_query', {}, exec(controller.signal)), /handoff stopped/);
  assert.deepEqual(calls, ['/permissions/preflight', '/cancel']);
});

test('human timeout is bounded, bilingual, undispatched and acknowledged', async t => {
  t.mock.timers.enable({ apis: ['setTimeout'] });
  for (const locale of ['zh', 'en']) {
    const calls = [];
    const bridge = createBridge({ port: 12345, token, getLocale: () => locale, fetchImpl: async (url, options) => {
      calls.push(new URL(url).pathname);
      return url.endsWith('/permissions/preflight') ? aborted(options.signal) : ok({});
    } });
    const pending = bridge.call('contacts_query', {}, exec(new AbortController().signal));
    t.mock.timers.tick(121000);
    await assert.rejects(pending, error => error.code === 'permission_timeout' && error.details.dispatched === false
      && error.details.outcomeUnknown === false && (locale === 'zh' ? /授权等待已超时/.test(error.message) : /Permission request expired/.test(error.message)));
    assert.deepEqual(calls, ['/permissions/preflight', '/cancel']);
  }
});

test('the existing native action timeout begins only after human authorization returns', async t => {
  t.mock.timers.enable({ apis: ['setTimeout'] });
  const calls = []; let approve;
  const bridge = createBridge({ port: 12345, token, fetchImpl: async (url, options) => {
    calls.push(new URL(url).pathname);
    if (url.endsWith('/permissions/preflight')) return new Promise(resolve => { approve = () => resolve(ok({})); });
    return url.endsWith('/tool') ? aborted(options.signal) : ok({});
  } });
  const pending = bridge.call('contacts_query', {}, exec(new AbortController().signal));
  t.mock.timers.tick(30000); await flush(); assert.equal(calls.length, 1);
  approve(); await flush(); t.mock.timers.tick(16000);
  await assert.rejects(pending, error => error.code === 'action_timeout' && error.details.outcomeUnknown === true);
  assert.deepEqual(calls, ['/permissions/preflight', '/tool', '/cancel']);
});
