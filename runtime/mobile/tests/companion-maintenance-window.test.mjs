import test from 'node:test';
import assert from 'node:assert/strict';
import { createCompanionMaintenanceWindow } from '../companions/maintenance-window.mjs';

function fixture(extra = {}) {
  const calls = [], wait = Promise.withResolvers();
  let ingressQuiet = true, producerQuiet = true, phase = 'open';
  const agent = { inbox: { nextTurn: [], nextStep: [] }, whenIdle() { calls.push('idle'); return extra.idle ?? Promise.resolve(); },
    cancel() { assert.fail('Maintenance must not cancel accepted tasks'); } };
  const producer = { async suspend() { calls.push('suspend'); if (extra.suspendError) throw extra.suspendError; },
    status: () => ({ quiet: producerQuiet }), async resume() { calls.push('resume'); } };
  const admission = { closeNew() { calls.push('close'); phase = 'closing'; }, async waitForQuiet() { return { quiet: ingressQuiet }; },
    status: () => ({ admissionsClosed: phase === 'sealed', quiet: ingressQuiet }),
    seal() { calls.push('seal'); phase = 'sealed'; }, resume() { calls.push('open'); phase = 'open'; } };
  const window = createCompanionMaintenanceWindow({ gate: { acquire: () => admission }, producers: () => [producer], agents: () => [agent],
    flushSessions: async () => { calls.push('sessions'); if (extra.flushError) throw extra.flushError; },
    flushPersistence: async () => { calls.push('persistence'); extra.afterFlush?.(agent); } });
  const operation = window.open({ operationId: 'delete-synthetic', admissionEpoch: 'synthetic-epoch' });
  return { calls, wait, agent, window, operation, busy() { producerQuiet = false; }, active() { ingressQuiet = false; } };
}

test('waits for accepted work without cancellation, then flushes and seals; never claims host stopped', async () => {
  const task = Promise.withResolvers(), f = fixture({ idle: task.promise });
  const pending = f.operation.prepare(); await new Promise(resolve => setImmediate(resolve));
  assert.deepEqual(f.calls, ['close', 'suspend', 'idle']);
  assert.equal(f.operation.status().quiet, false);
  task.resolve(); const result = await pending;
  assert.equal(result.quiet, true); assert.equal(result.hostStopped, false);
  assert.deepEqual(f.calls, ['close', 'suspend', 'idle', 'sessions', 'persistence', 'seal']);
  await f.operation.release(); assert.deepEqual(f.calls.slice(-2), ['resume', 'open']);
  assert.equal(f.operation.status().quiet, false);
});
test('unsettled producer or admitted operation does not authorize a flush or stop', async () => {
  for (const kind of ['busy', 'active']) {
    const f = fixture(); f[kind](); const result = await f.operation.prepare();
    assert.equal(result.phase, 'waiting'); assert.equal(result.quiet, false);
    assert.equal(f.calls.includes('sessions'), false); assert.equal(f.calls.includes('seal'), false);
    await f.operation.release();
  }
});
test('a new input delivered by a flush invalidates the proof', async () => {
  const f = fixture({ afterFlush: agent => agent.inbox.nextTurn.push({ id: 'synthetic-input' }) });
  assert.equal((await f.operation.prepare()).quiet, false); assert.equal(f.calls.includes('seal'), false);
  await f.operation.release();
});
test('preparation failure restores producers and admission without swallowing the cause', async () => {
  for (const key of ['suspendError', 'flushError']) {
    const failure = new Error(key), f = fixture({ [key]: failure });
    await assert.rejects(f.operation.prepare(), error => error === failure);
    assert.deepEqual(f.calls.slice(-2), ['resume', 'open']);
  }
});
test('same operation joins one preparation and conflicting owners are rejected', async () => {
  const task = Promise.withResolvers(), f = fixture({ idle: task.promise });
  const first = f.operation.prepare(); assert.equal(f.operation.prepare(), first);
  assert.equal(f.window.open({ operationId: 'delete-synthetic', admissionEpoch: 'synthetic-epoch' }), f.operation);
  assert.throws(() => f.window.open({ operationId: 'other', admissionEpoch: 'other' }), { code: 'COMPANION_MAINTENANCE_CONFLICT' });
  task.resolve(); await first; await f.operation.release();
});
test('release while an Agent finishes cannot seal the released window', async () => {
  const task = Promise.withResolvers(), f = fixture({ idle: task.promise });
  const pending = f.operation.prepare(); await new Promise(resolve => setImmediate(resolve));
  await f.operation.release(); task.resolve(); assert.equal((await pending).phase, 'released');
  assert.equal(f.calls.includes('seal'), false);
});
