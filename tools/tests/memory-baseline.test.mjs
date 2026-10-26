import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { collectBaseline, smokeDshContracts, validateFixture } from '../memory-baseline.mjs';

const fixture = JSON.parse(await readFile(new URL('../fixtures/personal-memory/fixture.json', import.meta.url), 'utf8'));

test('baseline aligns installed, declared and release DSH without reading runtime user data', async () => {
  const report = await collectBaseline();
  assert.ok(report.dshVersion);
  assert.ok(report.sources.every(row => !row.path.startsWith('/') && /^[a-f0-9]{64}$/.test(row.sha256)));
  assert.equal(report.fixture.recordCount, 5);
  assert.ok(report.declaredAndroidRuntime.node);
});

test('synthetic input distinguishes retries, unresolved locations and forgotten bodies', () => {
  assert.equal(validateFixture(fixture).deletedCount, 1);
  assert.throws(() => validateFixture({ ...fixture, synthetic: false }));
  const changedRetry = structuredClone(fixture);
  changedRetry.operations[1].recordId = changedRetry.records[0].recordId;
  assert.throws(() => validateFixture(changedRetry), /Retry fixture/);
  const duplicateRecord = structuredClone(fixture);
  duplicateRecord.records.push(duplicateRecord.records[0]);
  assert.throws(() => validateFixture(duplicateRecord));
});

test('isolated real DSH contracts support dispatch guarding, context and request correlation', async () => {
  const result = await smokeDshContracts();
  assert.equal(result.realDshServices, true);
  assert.equal(result.syntheticModel, true);
  assert.equal(result.networkRequests, 0);
  assert.ok(result.unproven.includes('privacy enforcement'));
});
