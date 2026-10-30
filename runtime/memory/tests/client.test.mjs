import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import vm from 'node:vm';
const source = await readFile(new URL('../client.js', import.meta.url), 'utf8');
let exports;
vm.runInNewContext(source, { window: { __ModuleLoader__: { load({ factory }) { exports = factory(() => ({})); } } } });
const { createMemoryController, saveMemoryArchive } = exports;
const enabled = () => ({ available: true, managementAvailable: true, gate: { ready: true }, settings: { enabled: true, recallEnabled: true, automaticExtraction: false } });
const plain = value => JSON.parse(JSON.stringify(value));
const result = items => ({ items, total: items.length, nextCursor: null, coverage: {} });

test('blocked status renders no records and does not request a database query', async () => {
  const calls = [];
  const controller = createMemoryController(async operation => { calls.push(operation); return { ...enabled(), available: false, managementAvailable: false, gate: { ready: false } }; }, () => 'id');
  await controller.refresh();
  assert.deepEqual(calls, [undefined]); assert.equal(controller.getSnapshot().items.length, 0);
  assert.equal(controller.getSnapshot().phase, 'ready');
});

test('disabled master still permits safe local management queries', async () => {
  const calls = [], row = { recordId: 'record', payload: { text: 'Fictional' } };
  const controller = createMemoryController(async operation => {
    calls.push(operation); return operation === 'query' ? result([row]) : { ...enabled(), available: false, settings: { ...enabled().settings, enabled: false } };
  }, () => 'id');
  await controller.refresh(); assert.deepEqual(calls, [undefined, 'query']); assert.equal(controller.getSnapshot().items[0].recordId, 'record');
});

test('late query results cannot replace a newer selected tab', async () => {
  let release;
  const controller = createMemoryController(async (operation, args) => {
    if (!operation) return enabled();
    if (args.eventTypes?.includes('location')) return result([{ recordId: 'place' }]);
    return new Promise(resolve => { release = resolve; });
  }, () => 'id');
  const old = controller.refresh();
  while (!release) await new Promise(resolve => setImmediate(resolve));
  await controller.setTab('places'); release(result([{ recordId: 'old-memory' }])); await old;
  assert.equal(controller.getSnapshot().tab, 'places'); assert.equal(controller.getSnapshot().items[0].recordId, 'place');
});

test('a protection-state refresh discards a previously pending private query', async () => {
  let release, allowed = true;
  const controller = createMemoryController(async operation => {
    if (!operation) return { ...enabled(), available: allowed, managementAvailable: allowed };
    return new Promise(resolve => { release = resolve; });
  }, () => 'id');
  const initial = controller.refresh();
  while (!release) await new Promise(resolve => setImmediate(resolve));
  allowed = false; await controller.refresh();
  release(result([{ recordId: 'late-private-record' }])); await initial;
  assert.equal(controller.getSnapshot().status.managementAvailable, false);
  assert.equal(controller.getSnapshot().items.length, 0);
});

test('an older status response cannot restore access after a newer blocked status', async () => {
  let release, requestCount = 0;
  const controller = createMemoryController(async operation => {
    if (operation) return result([]);
    if (++requestCount === 1) return new Promise(resolve => { release = resolve; });
    return { ...enabled(), available: false, managementAvailable: false };
  }, () => 'id');
  const first = controller.refresh(); await controller.refresh(); release(enabled()); await first;
  assert.equal(controller.getSnapshot().status.managementAvailable, false);
});

test('a retry preserves the mutation ID after an ambiguous network failure', async () => {
  const ids = []; let attempts = 0, count = 0;
  const controller = createMemoryController(async (operation, args) => {
    if (!operation) return enabled();
    if (operation === 'query') return result([]);
    ids.push(args.operationId); if (++attempts === 1) throw new Error('Synthetic network failure');
    return { recorded: true };
  }, () => `id-${++count}`);
  const input = { kind: 'fact', text: 'Fictional fact' };
  await controller.mutate('record', input, 'saved'); await controller.mutate('record', input, 'saved');
  assert.deepEqual(ids, ['ui:id-1', 'ui:id-1']); assert.equal(controller.getSnapshot().notice, 'saved');
});

test('only one mutation can be admitted while an earlier mutation is pending', async () => {
  let release, mutations = 0;
  const controller = createMemoryController(async operation => {
    if (!operation) return enabled(); if (operation === 'query') return result([]);
    mutations++; return new Promise(resolve => { release = resolve; });
  }, () => 'id');
  const pending = controller.mutate('record', { kind: 'fact', text: 'one' }, 'saved');
  await controller.mutate('record', { kind: 'fact', text: 'two' }, 'saved');
  assert.equal(mutations, 1); release({}); await pending; assert.equal(controller.getSnapshot().busy, false);
});

test('a rejected enable refreshes actual service status without retrying the mutation', async () => {
  let attempts = 0;
  const controller = createMemoryController(async operation => {
    if (operation === 'settings') { attempts++; throw Object.assign(new Error('Unavailable'), { code: 'MEMORY_EXECUTION_NOT_READY' }); }
    if (operation === 'query') return result([]);
    return { ...enabled(), available: false, managementAvailable: false, gate: { ready: false },
      settings: { ...enabled().settings, enabled: false } };
  }, () => 'id');
  await controller.mutate('settings', { enabled: true }, 'enabled');
  assert.equal(attempts, 1); assert.equal(controller.getSnapshot().status.gate.ready, false);
  assert.equal(controller.getSnapshot().status.settings.enabled, false); assert.match(controller.getSnapshot().error, /记忆服务尚未就绪/);
});

test('client sends local-time selections with explicit offset conversion and the correct category filter', async () => {
  const calls = [];
  const controller = createMemoryController(async (operation, args) => { calls.push({ operation, args }); return operation ? result([]) : enabled(); }, () => 'id');
  await controller.refresh(); controller.setFilters({ text: ' 健身 ', from: '2026-10-01T08:00', to: '2026-10-02T08:00' });
  await controller.setTab('activity');
  const args = calls.at(-1).args;
  assert.equal(args.text, '健身'); assert.ok(args.from.endsWith('Z')); assert.ok(args.to.endsWith('Z'));
  assert.deepEqual(plain(args.eventTypes), ['activity', 'device', 'gap']);
});

test('dispose does not publish a pending query result', async () => {
  let release, notifications = 0;
  const controller = createMemoryController(async operation => operation ? new Promise(resolve => { release = resolve; }) : enabled(), () => 'id');
  controller.subscribe(() => notifications++);
  const pending = controller.refresh(); while (!release) await new Promise(resolve => setImmediate(resolve));
  controller.dispose(); const previous = notifications; release(result([{ recordId: 'late' }])); await pending;
  assert.equal(notifications, previous);
});

test('client exposes the required Droy memory entry and main switch without embedding personal text in HTML', () => {
  assert.equal(source.split('/* DROY_MEMORY_STYLES */').length, 2);
  assert.match(source, /label: \(\) => tr\("Droy 记忆"\)/);
  assert.match(source, /title: tr\("Droy记忆"\)/);
  assert.doesNotMatch(source, /Root|rootAuthorized|data-droy-memory-root|执行保护/);
  assert.ok(!source.includes('dangerouslySetInnerHTML'));
});

test('backup choices persist across category tabs and clear after losing safe management access', async () => {
  let allowed = true;
  const controller = createMemoryController(async operation => operation ? result([]) : { ...enabled(), managementAvailable: allowed }, () => 'id');
  await controller.refresh(); controller.chooseExport('record-one', true); controller.chooseExport('record-one', true);
  await controller.setTab('places'); controller.chooseExport('place-one', true);
  assert.deepEqual(plain(controller.getSnapshot().exportIds), ['record-one', 'place-one']);
  controller.chooseExport('record-one', false); assert.deepEqual(plain(controller.getSnapshot().exportIds), ['place-one']);
  allowed = false; await controller.refresh(); assert.equal(controller.getSnapshot().exportIds.length, 0);
});

test('archive failures never retry or retain passwords and external download URLs never render', async () => {
  let calls = 0;
  const controller = createMemoryController(async operation => operation ? result([]) : enabled(), () => 'id', async () => {
    calls++; return { downloadUrl: 'https://outside.invalid/archive', expiresAt: 123 };
  });
  await controller.refresh();
  await controller.archive('export', { recordIds: ['fictional'], passphrase: 'synthetic-private-password' });
  assert.equal(calls, 1); assert.equal(controller.getSnapshot().download, null);
  assert.ok(!JSON.stringify(controller.getSnapshot()).includes('synthetic-private-password'));
  assert.match(controller.getSnapshot().error, /备份结果无效/);
});

test('turning memory off remains available during a pending maintenance request', async () => {
  let release, allowed = true;
  const calls = [];
  const controller = createMemoryController(async (operation, args) => {
    if (!operation) return { ...enabled(), available: allowed, settings: { ...enabled().settings, enabled: allowed } };
    if (operation === 'query') return result([]);
    calls.push(operation);
    if (operation === 'maintenance') return new Promise(resolve => { release = resolve; });
    if (operation === 'settings') { allowed = args.enabled; return {}; }
  }, () => 'id');
  await controller.refresh();
  const processing = controller.mutate('maintenance', { operation: 'retry', id: 'fixture' }, 'retry');
  await controller.mutate('settings', { enabled: false }, 'off');
  assert.deepEqual(calls, ['maintenance', 'settings']); assert.equal(controller.getSnapshot().status.settings.enabled, false);
  assert.equal(controller.getSnapshot().busy, true);
  release({}); await processing; assert.equal(controller.getSnapshot().busy, false);
});

test('an export that completes after a blocked refresh cannot restore a download handle', async () => {
  let release, allowed = true;
  const controller = createMemoryController(async operation => operation ? result([]) : { ...enabled(), managementAvailable: allowed }, () => 'id',
    async () => new Promise(resolve => { release = resolve; }));
  await controller.refresh(); const exporting = controller.archive('export', { recordIds: ['fixture'], passphrase: 'fixture-password' });
  allowed = false; await controller.refresh();
  release({ downloadUrl: '/droy/memory/archive/download/' + 'a'.repeat(64), expiresAt: Date.now() + 50000 });
  await exporting; assert.equal(controller.getSnapshot().download, null);
});

test('imports require active memory and remain an explicit one-shot request', async () => {
  let calls = 0, active = false;
  const controller = createMemoryController(async operation => operation ? result([]) : { ...enabled(), available: active }, () => 'id', async () => {
    calls++; throw Object.assign(new Error('Bad fixture'), { code: 'MEMORY_ARCHIVE_INVALID' });
  });
  await controller.refresh(); await controller.archive('import', { archiveBase64: 'AQ==', passphrase: 'fixture-password' }); assert.equal(calls, 0);
  active = true; await controller.refresh(); await controller.archive('import', { archiveBase64: 'AQ==', passphrase: 'fixture-password' });
  assert.equal(calls, 1); assert.match(controller.getSnapshot().error, /未导入记录/);
  assert.ok(!JSON.stringify(controller.getSnapshot()).includes('AQ=='));
});

test('saving an archive hands its opaque ID directly to native without a preflight GET', async () => {
  const calls = [], id = 'a'.repeat(64);
  const target = { DroyMobile: { saveMemoryArchive: async value => { calls.push(value); return { pickerOpened: true }; } },
    fetch() { assert.fail('A preflight GET would consume the single-use archive'); } };
  const download = { downloadUrl: `/droy/memory/archive/download/${id}`, expiresAt: Date.now() + 60000 };
  assert.deepEqual(await saveMemoryArchive(target, download), { pickerOpened: true });
  assert.deepEqual(calls, [id]);
  await assert.rejects(saveMemoryArchive({}, download), { code: 'MEMORY_ARCHIVE_SAVE_UNAVAILABLE' });
  await assert.rejects(saveMemoryArchive(target, { ...download, downloadUrl: 'https://untrusted.invalid/archive' }), { code: 'MEMORY_ARCHIVE_RESPONSE_INVALID' });
  await assert.rejects(saveMemoryArchive(target, { ...download, expiresAt: Date.now() - 1 }), { code: 'MEMORY_ARCHIVE_EXPIRED' });
  assert.equal(calls.length, 1);
  assert.ok(!source.includes("href: state.download.downloadUrl"));
});
