import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { patchNativeScheduleMaintenance, nativeScheduleMaintenancePatchFingerprint } from '../native_schedule_maintenance_patch.mjs';

const source = await readFile(new URL('../../runtime/dsh/node_modules/@deepseek-ai/dsh-schedule/lib/index.js', import.meta.url), 'utf8');
test('schedule patch changes only transient Runtime admission and adds one provider-owned lease export', () => {
  const output = patchNativeScheduleMaintenance(source);
  const runtime = 'var ScheduleRuntime = class {', service = 'let ScheduleService = (() => {', exported = 'export { MAX_TITLE_LENGTH,';
  assert.equal(output.slice(0, output.indexOf(runtime)), source.slice(0, source.indexOf(runtime)));
  assert.equal(output.slice(output.indexOf(service), output.indexOf('const nativeScheduleMaintenanceOwners')),
    source.slice(source.indexOf(service), source.indexOf(exported)) + '\n');
  assert.match(output, /export \{ createScheduleMaintenanceLease, MAX_TITLE_LENGTH,/u);
  assert.equal(output.split('function createScheduleMaintenanceLease(').length, 2);
  assert.equal(output.includes('agent/pre-step'), source.includes('agent/pre-step'));
  assert.equal(output.includes('agent.cancel('), source.includes('agent.cancel('));
});
test('schedule fingerprint binds both the unchanged locked source and deterministic patched output', () => {
  const fingerprint = nativeScheduleMaintenancePatchFingerprint(source);
  assert.equal(fingerprint.version, 1); assert.match(fingerprint.before, /^[a-f0-9]{64}$/u); assert.match(fingerprint.after, /^[a-f0-9]{64}$/u);
  assert.notEqual(fingerprint.before, fingerprint.after);
  assert.deepEqual(nativeScheduleMaintenancePatchFingerprint(source), fingerprint);
});
test('schedule patch refuses reapplication, ambiguous source and drift at every load-bearing lifecycle boundary', () => {
  for (const changed of [patchNativeScheduleMaintenance(source), source + source,
    source.replace('var ScheduleRuntime = class {', 'var ChangedRuntime = class {'),
    source.replace('let ScheduleService = (() => {', 'let ChangedService = (() => {'),
    source.replace('resolved.agent.followup(message);', 'resolved.agent.followup(other);'),
    source.replace('await this.runtime?.dispose();', 'await this.runtime?.other();'),
    source.replace('while (this.requested && !this.stopping) {', 'while (this.requested) {'),
    source.replace('if (this.running !== void 0) return;', 'if (this.running) return;'),
    source.replace('const next = this.tasks().filter', 'const other = this.tasks().filter'),
    source.replace('serialize(work) {', 'changedSerialize(work) {')])
    assert.throws(() => patchNativeScheduleMaintenance(changed), /contract changed/u);
});
