import test, { after } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, readFile, writeFile, symlink, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { createRequire, registerHooks } from 'node:module';
import { pathToFileURL } from 'node:url';
import { patchNativeScheduleMaintenance } from '../../../tools/native_schedule_maintenance_patch.mjs';

const installed = createRequire(new URL('../../dsh/package.json', import.meta.url));
const packageDirectory = await mkdtemp(join(tmpdir(), 'droy-maintenance-schedule-sdk-'));
const original = await readFile(installed.resolve('@deepseek-ai/dsh-schedule'), 'utf8');
await writeFile(join(packageDirectory, 'index.mjs'), patchNativeScheduleMaintenance(original));
await symlink(new URL('../../dsh/node_modules', import.meta.url), join(packageDirectory, 'node_modules'), 'dir');
const hooks = registerHooks({ resolve(specifier, context, next) {
  if (specifier.startsWith('@deepseek-ai/')) return { url: pathToFileURL(installed.resolve(specifier)).href, shortCircuit: true };
  return next(specifier, context);
} });
const [{ Context }, { default: Storage }, JsonStorage, Domains, Schedule, llm, Jsonl,
  { default: Sessions }, { default: Projections }, { default: SystemPrompt }, { default: Tools, defineTool },
  { default: Agents }, { default: Loop }] = await Promise.all([
  import('@deepseek-ai/cordis'), import('@deepseek-ai/dsh-storage'), import('@deepseek-ai/dsh-storage-json'),
  import('@deepseek-ai/dsh-storage-domain'), import(pathToFileURL(join(packageDirectory, 'index.mjs')).href), import('@deepseek-ai/dsh-llm'),
  import('@deepseek-ai/dsh-session-persistence-jsonl'),
  import('@deepseek-ai/dsh-session'), import('@deepseek-ai/dsh-session-projection'), import('@deepseek-ai/dsh-system-prompt'),
  import('@deepseek-ai/dsh-tools'), import('@deepseek-ai/dsh-agent'), import('@deepseek-ai/dsh-agent-loop'),
]);
hooks.deregister();
after(() => rm(packageDirectory, { recursive: true, force: true }));
const code = value => error => error.code === value;
const deferred = () => Promise.withResolvers();
const NOW = Date.UTC(2030, 0, 1);
const text = value => llm.createUserMessage({ content: [{ type: 'text', text: value }], source: { kind: 'user' } });
function clock(t) { t.mock.timers.enable({ apis: ['Date', 'setTimeout'], now: NOW }); }

async function fixture(t, options = {}) {
  const home = await mkdtemp(join(tmpdir(), 'droy-maintenance-schedule-state-')), ctx = new Context();
  const deliveries = [], warnings = [], calls = [];
  let schedule;
  t.after(async () => { await ctx.fiber.dispose(); await rm(home, { recursive: true, force: true }); });
  ctx.logger.warn = value => warnings.push(value);
  if (options.realAgent) {
    await ctx.plugin(llm.default); await ctx.plugin(Sessions); await ctx.plugin(Projections);
    await ctx.plugin(SystemPrompt, { personaPrefix: 'Synthetic maintenance regression.' }); await ctx.plugin(Tools, { mode: 'native' });
    await ctx.plugin(Agents); await ctx.plugin(Loop, { agents: [] });
    await ctx.plugin(Jsonl, { root: join(home, 'sessions'), compression: 'none' });
  } else {
    ctx.provide('agents', { get: () => undefined, withoutInitiator: operation => operation() });
    ctx.provide('sessions', { async flush(session) {
      calls.push({ kind: 'flush', id: session.id }); await options.beforeFlush?.(session); return true;
    } });
  }
  if (!options.realAgent) ctx.provide('sessionPersistence', {});
  ctx.provide('sessionController', { async resolveAgent(id) {
    calls.push({ kind: 'resolve', id }); await options.beforeResolve?.(id);
    const agent = options.resolveAgent?.(id) ?? { id, session: { id }, followup(message) { deliveries.push({ id, message }); } };
    return { agent };
  } });
  await ctx.plugin(Storage); await ctx.plugin(JsonStorage, { root: home }); await ctx.plugin(Domains, { backend: 'json' });
  await ctx.plugin(Schedule.ScheduleService, { deliveryHistoryDays: 30, deliveryHistoryRecords: 200 });
  schedule = ctx.schedule;
  const table = ctx.storageDomain.get(Schedule.scheduleDomain.name).table('tasks');
  const put = table.put.bind(table), remove = table.delete.bind(table);
  table.put = async (id, value) => { calls.push({ kind: 'put', id }); await options.beforePut?.(id, value); return put(id, value); };
  table.delete = async id => { calls.push({ kind: 'delete', id }); return remove(id); };
  async function settled() {
    while (schedule.runtime.running !== undefined) await schedule.runtime.running;
    await schedule.chain;
  }
  await settled();
  const create = async (title, seconds = 5, sessionId = 'work-a') => {
    const value = await schedule.create(sessionId, { title, prompt: 'Synthetic reminder ' + title, after_seconds: seconds });
    await settled(); return value;
  };
  const rows = () => structuredClone([...table.entries()]);
  const lease = operationId => Schedule.createScheduleMaintenanceLease(schedule, { operationId });
  return { ctx, schedule, home, table, deliveries, warnings, calls, create, rows, settled, lease };
}

test('future and overdue one-shot schedule records stay identical while held and resume exactly once', async t => {
  clock(t);
  const f = await fixture(t), record = await f.create('Future task'), before = f.rows();
  const lease = f.lease('future-window');
  assert.equal(lease.status().quiet, false);
  assert.equal(lease.status().deliveriesSuspended, false);
  const suspended = await lease.suspend();
  assert.equal(suspended.phase, 'suspended'); assert.equal(suspended.quiet, true); assert.equal(suspended.scope, 'schedule-runtime');
  assert.ok(Object.isFrozen(suspended));
  t.mock.timers.tick(25000); await f.settled();
  assert.deepEqual(f.rows(), before); assert.equal(f.deliveries.length, 0); assert.deepEqual(f.warnings, []);
  assert.equal((await f.schedule.history({ sessionId: 'work-a', id: record.id, limit: 10 })).records.length, 0);
  const released = lease.resume(); assert.equal(released.phase, 'released'); assert.equal(released.quiet, false);
  await f.settled(); assert.equal(f.deliveries.length, 1);
  const row = f.rows()[0][1];
  assert.equal(row.record.scheduledAt, record.scheduledAt); assert.equal(row.status, 'inactive');
  assert.equal(row.deliveryHistory.records.length, 1); assert.equal(row.deliveryHistory.records[0].messageId, f.deliveries[0].message.id);
  lease.resume(); await f.settled(); t.mock.timers.tick(50000); await f.settled();
  assert.equal(f.deliveries.length, 1); assert.deepEqual(f.warnings, []);
});

test('an entered delivery completes inbox flush and history; later groups wait and accepted management drains naturally', async t => {
  clock(t);
  const resolution = deferred(), entered = deferred(), flushing = deferred(), flush = deferred(), writing = deferred(), write = deferred();
  let gateWrite = false;
  const f = await fixture(t, {
    beforeResolve: async () => { entered.resolve(); await resolution.promise; },
    beforeFlush: async () => { flushing.resolve(); await flush.promise; },
    beforePut: async (_id, value) => { if (gateWrite && value.record.title === 'Edited while draining') { writing.resolve(); await write.promise; } },
  });
  const first = await f.create('First due', 1), second = await f.create('Second due', 1), future = await f.create('Management target', 3600);
  t.mock.timers.tick(1000); await entered.promise;
  gateWrite = true;
  const updating = f.schedule.update({ sessionId: 'work-a', id: future.id, expected: future, title: 'Edited while draining' });
  const lease = f.lease('drain-window'); let completed = false;
  const suspension = lease.suspend().then(value => { completed = true; return value; });
  assert.equal(lease.status().phase, 'suspending'); assert.equal(lease.status().quiet, false);
  resolution.resolve(); await flushing.promise;
  assert.equal(completed, false); assert.equal(f.deliveries.length, 1);
  assert.equal(f.table.get(first.id).status, 'active');
  flush.resolve(); await writing.promise;
  assert.equal(completed, false); assert.equal(f.table.get(first.id).status, 'inactive');
  assert.equal(f.table.get(second.id).status, 'active');
  write.resolve(); assert.equal((await updating).updated, true);
  const receipt = await suspension; assert.equal(receipt.quiet, true); assert.equal(receipt.managementBarrierSettled, true);
  assert.equal(f.table.get(future.id).record.title, 'Edited while draining'); assert.equal(f.deliveries.length, 1);
  assert.equal(f.calls.filter(call => call.kind === 'resolve').length, 1); assert.deepEqual(f.warnings, []);
  lease.resume(); await f.settled(); assert.equal(f.deliveries.length, 2);
  assert.equal(f.table.get(second.id).deliveryHistory.records.length, 1);
});

test('accepted Agent management remains usable and invalidates a previously observed management barrier', async t => {
  clock(t);
  const started = deferred(), committed = deferred();
  const f = await fixture(t, { beforePut: async () => { started.resolve(); await committed.promise; } });
  const lease = f.lease('management-window'); assert.equal((await lease.suspend()).quiet, true);
  const creating = f.schedule.create('work-a', { title: 'Accepted continuation', prompt: 'Synthetic instruction', after_seconds: 1 });
  await started.promise;
  assert.equal(lease.status().quiet, false); assert.equal(lease.status().managementBarrierSettled, false);
  const observing = lease.suspend(); committed.resolve(); const created = await creating;
  assert.equal((await observing).quiet, true);
  t.mock.timers.tick(5000); await f.settled();
  assert.equal(f.table.get(created.id).status, 'active'); assert.equal(f.deliveries.length, 0); assert.deepEqual(f.warnings, []);
  lease.resume(); await f.settled(); assert.equal(f.deliveries.length, 1);
});

test('recurring batches keep their original due records during suspension and rebuild one next timer without duplicate delivery', async t => {
  clock(t);
  const f = await fixture(t), interval = Schedule.MIN_EVERY_INTERVAL_SECONDS;
  for (const title of ['Recurring A', 'Recurring B']) await f.schedule.create('work-a', { title, prompt: 'Synthetic recurring instruction', every_seconds: interval });
  await f.settled(); const before = f.rows(), lease = f.lease('recurring-window'); await lease.suspend();
  t.mock.timers.tick(interval * 3500); await f.settled(); assert.deepEqual(f.rows(), before);
  const now = Date.now(), expected = before.map(([, row]) => Schedule.resolveRecurringOccurrence(row.record, now));
  lease.resume(); await f.settled();
  assert.equal(f.deliveries.length, 1);
  for (const [index, [, row]] of f.rows().entries()) {
    assert.equal(row.status, 'active'); assert.equal(row.record.scheduledAt, expected[index].nextScheduledAt);
    assert.equal(row.deliveryHistory.records.length, 1); assert.equal(row.deliveryHistory.records[0].messageId, f.deliveries[0].message.id);
  }
  const untilNext = Date.parse(expected[0].nextScheduledAt) - Date.now();
  t.mock.timers.tick(untilNext - 1); await f.settled(); assert.equal(f.deliveries.length, 1);
  t.mock.timers.tick(1); await f.settled(); assert.equal(f.deliveries.length, 2);
  for (const [, row] of f.rows()) assert.equal(row.deliveryHistory.records.length, 2);
  await f.lease('second-window').suspend();
  t.mock.timers.tick(interval * 1000); await f.settled(); assert.equal(f.deliveries.length, 2); assert.deepEqual(f.warnings, []);
});

test('a timer already queued behind an accepted management write parks without resolving or failing its overdue target', async t => {
  clock(t);
  const writing = deferred(), write = deferred(); let gated = false;
  const f = await fixture(t, { beforePut: async (_id, value) => {
    if (gated && value.record.title === 'Management in progress') { writing.resolve(); await write.promise; }
  } });
  const due = await f.create('Timer racing maintenance', 1);
  gated = true;
  const creating = f.schedule.create('work-a', { title: 'Management in progress', prompt: 'Synthetic accepted write', after_seconds: 3600 });
  await writing.promise; t.mock.timers.tick(1000);
  assert.ok(f.schedule.runtime.running, 'The due timer has already joined the management FIFO');
  const lease = f.lease('queued-driver-window'), suspending = lease.suspend();
  write.resolve(); await creating; assert.equal((await suspending).quiet, true);
  assert.equal(f.calls.filter(call => call.kind === 'resolve').length, 0); assert.equal(f.deliveries.length, 0);
  assert.equal(f.table.get(due.id).record.scheduledAt, due.scheduledAt); assert.equal(f.table.get(due.id).status, 'active');
  assert.deepEqual(f.table.get(due.id).deliveryHistory, { records: [], earlierRecordsUnavailable: false });
  assert.deepEqual(f.warnings, []);
  lease.resume(); await f.settled(); assert.equal(f.deliveries.length, 1);
});

test('pre-aborted acquisition has no side effects; aborting a waiter retains the gate until explicit cancellation', async t => {
  clock(t);
  const resolution = deferred(), entered = deferred();
  const f = await fixture(t, { beforeResolve: async () => { entered.resolve(); await resolution.promise; } });
  await f.create('Accepted before maintenance', 1);
  const lease = f.lease('cancel-window'), cancelled = new AbortController(), reason = new Error('Synthetic cancelled waiter');
  cancelled.abort(reason);
  assert.throws(() => lease.suspend({ signal: cancelled.signal }), error => error === reason);
  assert.equal(lease.status().phase, 'ready'); assert.equal(lease.status().deliveriesSuspended, false);
  t.mock.timers.tick(1000); await entered.promise;
  const controller = new AbortController(), observing = lease.suspend({ signal: controller.signal });
  const secondObserver = lease.suspend(); controller.abort(reason);
  await assert.rejects(observing, error => error === reason);
  assert.equal(lease.status().deliveriesSuspended, true); assert.equal(lease.status().quiet, false);
  const released = lease.resume(); assert.equal(released.phase, 'released');
  assert.equal((await secondObserver).phase, 'released');
  assert.equal(f.deliveries.length, 0);
  resolution.resolve(); await f.settled(); assert.equal(f.deliveries.length, 1); assert.deepEqual(f.warnings, []);
});

test('lease ownership, repeated calls and stale objects cannot release a newer maintenance window', async t => {
  clock(t);
  const f = await fixture(t), first = f.lease('first-window');
  assert.equal(f.lease('first-window'), first);
  assert.throws(() => f.lease('other-window'), code('DSH_SCHEDULE_MAINTENANCE_CONFLICT'));
  assert.deepEqual(await first.suspend(), await first.suspend()); first.resume();
  assert.equal((await first.suspend()).phase, 'released');
  const second = f.lease('second-window'); await second.suspend(); first.resume(); await first.suspend();
  assert.equal(second.status().quiet, true); assert.equal(second.status().deliveriesSuspended, true);
  assert.throws(() => f.lease('third-window'), code('DSH_SCHEDULE_MAINTENANCE_CONFLICT'));
  second.resume(); assert.equal(second.resume().phase, 'released');
});

test('invalid or stopping services never issue a quiet receipt', async t => {
  const f = await fixture(t);
  for (const input of [undefined, null, [], {}, { operationId: '' }, { operationId: '../unsafe' }, { operationId: 'valid', extra: true }])
    assert.throws(() => Schedule.createScheduleMaintenanceLease(f.schedule, input), code('DSH_SCHEDULE_MAINTENANCE_INVALID'));
  assert.throws(() => Schedule.createScheduleMaintenanceLease({}, { operationId: 'valid' }), code('DSH_SCHEDULE_MAINTENANCE_NOT_READY'));
  const lease = f.lease('stop-window');
  for (const input of [null, [], { other: true }, { signal: {} }])
    assert.throws(() => lease.suspend(input), code('DSH_SCHEDULE_MAINTENANCE_INVALID'));
  assert.equal(lease.status().deliveriesSuspended, false);
  await lease.suspend(); await f.ctx.fiber.dispose();
  assert.equal(lease.status().phase, 'stopped'); assert.equal(lease.status().quiet, false);
  assert.throws(() => lease.suspend(), code('DSH_SCHEDULE_MAINTENANCE_STOPPED'));
  assert.throws(() => lease.resume(), code('DSH_SCHEDULE_MAINTENANCE_STOPPED'));
});

test('a real active Agent continues its next step and tool call while schedule deliveries are suspended', async t => {
  const f = await fixture(t, { realAgent: true }), started = deferred(), proceed = deferred(), errors = [];
  f.ctx.on('agent/error', event => errors.push(event.error));
  let requests = 0, calls = 0;
  class LocalModel extends llm.LlmAdapter {
    async resolveModel(provider, id) { return { provider, id, name: 'Synthetic local fixture', inputModalities: ['text'] }; }
    async *stream() {
      requests++;
      if (requests === 1) {
        started.resolve(); await proceed.promise;
        yield { type: 'block-start', index: 0, blockType: 'tool-call' };
        yield { type: 'tool-call-delta', index: 0, id: 'fixture-tool-1', name: 'maintenance_fixture', argumentsDelta: '{}' };
        yield { type: 'block-end', index: 0, block: { type: 'tool-call', id: 'fixture-tool-1', name: 'maintenance_fixture', arguments: '{}' } };
        yield { type: 'finish', reason: { kind: 'tool-calls' } };
      } else {
        yield { type: 'block-start', index: 0, blockType: 'text' };
        yield { type: 'text-delta', index: 0, text: 'Synthetic completed response.' };
        yield { type: 'block-end', index: 0, block: { type: 'text', text: 'Synthetic completed response.' } };
        yield { type: 'finish', reason: { kind: 'stop' } };
      }
    }
  }
  f.ctx.llm.registerAdapter(['maintenance-local'], new LocalModel());
  f.ctx.tools.register(defineTool({ name: 'maintenance_fixture', description: 'Synthetic test operation', parameters: {},
    output: { schema: { type: 'json' }, render: (_args, value) => [{ type: 'text', text: JSON.stringify(value) }] },
    async execute() {
      calls++;
      const record = await f.schedule.create('maintenance-active-agent', {
        title: 'Accepted Agent continuation', prompt: 'Synthetic future reminder', after_seconds: 3600,
      });
      return { id: record.id };
    } }));
  const handle = await f.ctx.agents.create({ sessionId: 'maintenance-active-agent', agentOptions: { provider: 'maintenance-local', model: 'fixture' } });
  handle.agent.followup(text('Synthetic accepted task')); await started.promise;
  const lease = f.lease('agent-continuation'); assert.equal((await lease.suspend()).quiet, true);
  assert.equal(handle.agent.status, 'running', 'Schedule quiet must not claim Agent idle');
  proceed.resolve(); await handle.agent.whenIdle();
  assert.equal(requests, 2); assert.equal(calls, 1); assert.deepEqual(errors, []);
  const events = handle.agent.session.snapshotEvents();
  assert.equal(events.filter(event => event.type === 'step/start').length, 2);
  assert.equal(events.findLast(event => event.type === 'turn/end').data.reason.kind, 'completed');
  assert.equal(lease.status().deliveriesSuspended, true); assert.equal(lease.status().quiet, false);
  assert.equal((await lease.suspend()).quiet, true); assert.equal(f.rows().length, 1);
  assert.equal(events.filter(event => event.type === 'tool/result').length, 1);
  lease.resume(); await handle.dispose();
});
