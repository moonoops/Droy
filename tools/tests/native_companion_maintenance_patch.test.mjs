import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { patchNativeCompanionMaintenance, nativeCompanionMaintenancePatchFingerprint } from '../native_companion_maintenance_patch.mjs';

const source = await readFile(new URL('../../runtime/dsh/node_modules/@deepseek-ai/dsh-session-persistence-jsonl/lib/index.js', import.meta.url), 'utf8');
test('offline audit patch adds one provider-owned export without changing existing provider methods', () => {
  const output = patchNativeCompanionMaintenance(source), end = 'export { Config, JsonlCompressionSchema, apply, name };';
  assert.equal(output.slice(0, source.indexOf(end)), source.slice(0, source.indexOf(end)));
  assert.match(output, /export \{ Config, JsonlCompressionSchema, apply, name, auditOfflineSessionCorpus \}/u);
  assert.equal(output.split('async function auditOfflineSessionCorpus(').length, 2);
});
test('provider fingerprint is reproducible and binds both the upstream source and patch output', () => {
  const fingerprint = nativeCompanionMaintenancePatchFingerprint(source);
  assert.equal(fingerprint.version, 1); assert.match(fingerprint.before, /^[a-f0-9]{64}$/u); assert.match(fingerprint.after, /^[a-f0-9]{64}$/u);
  assert.notEqual(fingerprint.before, fingerprint.after); assert.deepEqual(nativeCompanionMaintenancePatchFingerprint(source), fingerprint);
});
test('reapplication, ambiguous source and incompatible provider helpers refuse loudly', () => {
  for (const changed of [patchNativeCompanionMaintenance(source), source + '\n' + source,
    source.replace('function scanZstdFrames(buffer, maxFrames = Number.POSITIVE_INFINITY) {', 'function changedFrameContract() {'),
    source.replace('var JsonlSessionPersistence = class extends SessionPersistence {', 'var OtherProvider = class {')])
    assert.throws(() => patchNativeCompanionMaintenance(changed), /contract changed/u);
});
