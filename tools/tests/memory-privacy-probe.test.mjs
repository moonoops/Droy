import test from 'node:test';
import assert from 'node:assert/strict';
import { runPrivacyProbe } from '../memory-privacy-probe.mjs';

test('real DSH middleware gates bound endpoints while exposing missing attribution and read isolation', { timeout: 30000 }, async () => {
  const result = await runPrivacyProbe();
  const checks = new Map(result.checks.map(check => [check.id, check]));
  for (const name of ['conversation', 'compaction', 'session-title']) {
    const check = checks.get(`session-bound-${name}`);
    assert.equal(check.status, 'pass');
    assert.equal(check.deniedHttpRequests, 0);
    assert.equal(check.approvedHttpRequests, 1);
  }
  assert.equal(checks.get('same-provider-changed-endpoint').status, 'pass');
  assert.equal(checks.get('anonymous-private-request').status, 'gap-demonstrated');
  assert.equal(checks.get('unmarked-session-file-result').unapprovedHttpRequests, 1);
  const filesystem = checks.get('official-read-only-filesystem');
  assert.equal(filesystem.outsideWorkspaceReadable, true);
  assert.equal(filesystem.symlinkOutsideReadable, true);
  assert.equal(filesystem.writeDenied, true);
  assert.equal(result.localHttpReceiptCount, 5);
  assert.equal(result.productionPrivacyReady, false);
  assert.equal(result.realPrivateDataUsed, false);
});
