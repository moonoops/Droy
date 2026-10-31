import test from 'node:test';
import assert from 'node:assert/strict';
import { createRequire, registerHooks } from 'node:module';
import { pathToFileURL } from 'node:url';

const installed = createRequire(new URL('../../dsh/package.json', import.meta.url));
const hooks = registerHooks({ resolve(specifier, context, next) {
  if (specifier.startsWith('@deepseek-ai/')) return { url: pathToFileURL(installed.resolve(specifier)).href, shortCircuit: true };
  return next(specifier, context);
} });
const { selectNativeSessionModel } = await import('../native-models.mjs');
hooks.deregister();

test('native selection preserves its validated route and forces Session-only persistence', async () => {
  const calls = [];
  const selected = { provider: 'fixture-provider', model: 'fixture-model', reasoningEffort: 'high' };
  const ctx = { sessionController: { selectModel: async request => { calls.push(request); return { selected }; } } };
  const request = { sessionId: 'fixture-session', ...selected };
  assert.deepEqual(await selectNativeSessionModel(ctx, request), { selected });
  assert.deepEqual(calls, [{ ...request, persistDefault: false }]);
  assert.equal(Object.hasOwn(request, 'persistDefault'), false);
});

test('public input cannot inject the internal persistence flag or unknown fields', () => {
  const ctx = { sessionController: { selectModel: () => { throw new Error('Must not run'); } } };
  const request = { sessionId: 'fixture-session', provider: 'fixture-provider', model: 'fixture-model' };
  for (const value of [null, [], {}, { ...request, persistDefault: true }, { ...request, persistDefault: false },
    { ...request, unknown: true }, { ...request, sessionId: ' ' }, { ...request, sessionId: 'x'.repeat(1025) },
    { ...request, model: 3 }, { ...request, reasoningEffort: 3 }]) {
    assert.throws(() => selectNativeSessionModel(ctx, value), error => error.code === 'gateway/bad-request');
  }
});

test('native selection uses the installed service and surfaces its refusal without fallback', async () => {
  const request = { sessionId: 'fixture-session', provider: 'fixture-provider', model: 'fixture-model' };
  assert.throws(() => selectNativeSessionModel({}, request), error => error.code === 'session/not-ready');
  const refused = Object.assign(new Error('Fixture refusal'), { code: 'session/model-unavailable' });
  const ctx = { get: name => name === 'sessionController' ? { selectModel: async () => { throw refused; } } : undefined };
  await assert.rejects(selectNativeSessionModel(ctx, request), error => error === refused);
});

test('native workbench cannot change a companion-owned Session model', () => {
  let invoked = false;
  const ctx = {
    droyCompanions: { ownsSession: id => id === 'companion-session' },
    sessionController: { selectModel: () => { invoked = true; } },
  };
  assert.throws(() => selectNativeSessionModel(ctx,
    { sessionId: 'companion-session', provider: 'fixture', model: 'fixture' }),
  error => error.code === 'COMPANION_SESSION_MANAGED');
  assert.equal(invoked, false);
});
