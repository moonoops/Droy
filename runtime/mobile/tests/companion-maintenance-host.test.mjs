import test from 'node:test';
import assert from 'node:assert/strict';
import { createHostMaintenanceExecution } from '../companions/maintenance-host-execution.mjs';

function fixture() {
  const callbacks = new Map(), rows = [], jobs = [], terminals = [];
  const services = { jobs: { list: () => jobs }, terminalController: { list: () => terminals } };
  const ctx = { on(event, callback) { callbacks.set(event, callback); return () => callbacks.delete(event); },
    agents: { list: () => rows }, sessions: { list: () => [{ id: 'synthetic' }] }, get: name => services[name],
    droyNativeUserTasks: { inspect: () => ({ writers: [], busy: [], unfenced: [] }) } };
  return { callbacks, rows, jobs, terminals, services, inspector: createHostMaintenanceExecution(ctx) };
}
test('agent idle promises and queued input are authoritative even when no UI task is visible', async () => {
  const f = fixture(), idle = Promise.withResolvers();
  const agent = { whenIdle: () => idle.promise, inbox: { nextTurn: [], nextStep: [] } };
  f.rows.push(agent); f.callbacks.get('agent/created')({ agent });
  assert.equal(f.inspector.inspect().writers.length, 1);
  idle.resolve(); await new Promise(resolve => setImmediate(resolve));
  assert.equal(f.inspector.inspect().writers.length, 0);
  agent.inbox.nextStep.push({ id: 'input' });
  assert.deepEqual(f.inspector.inspect().busy, [{ kind: 'agent-input', count: 1 }]);
  f.inspector.dispose(); assert.equal(f.callbacks.size, 0);
});
test('jobs, terminal work and missing observation fence cannot be mistaken for idle', () => {
  const f = fixture(); f.jobs.push({ id: 'one', status: 'running' }); f.terminals.push({ state: 'running' });
  assert.deepEqual(f.inspector.inspect().busy, [{ kind: 'background-jobs', count: 1 }, { kind: 'web-terminal', count: 1 }]);
  delete f.services.jobs;
  assert.deepEqual(f.inspector.inspect().unfenced, [{ kind: 'jobs-unavailable', count: 1 }]);
});
test('rejected idle barrier remains unverified rather than becoming success', async () => {
  const f = fixture(), agent = { whenIdle: () => Promise.reject(new Error('synthetic')), inbox: { nextTurn: [], nextStep: [] } };
  f.rows.push(agent); f.callbacks.get('agent/status')({ agent }); await new Promise(resolve => setImmediate(resolve));
  assert.deepEqual(f.inspector.inspect().unfenced, [{ kind: 'agent-idle-failed', count: 1 }]);
});
