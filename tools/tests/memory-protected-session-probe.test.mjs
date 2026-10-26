import test from 'node:test';
import assert from 'node:assert/strict';
import { runProtectedSessionProbe } from '../memory-protected-session-probe.mjs';

test('persistent session protection gates actual DSH loopback dispatch through a bound laboratory adapter', { timeout: 30000 }, async () => {
  const result = await runProtectedSessionProbe();
  const checks = new Map(result.checks.map(check => [check.id, check]));
  assert.equal(result.passed, true);
  assert.equal(result.checks.length, 19);
  assert.ok(result.checks.every(check => check.status === 'pass'));
  for (const purpose of ['conversation', 'session-title', 'compaction']) {
    const check = checks.get(`purpose-${purpose}`);
    assert.equal(check.authorizedHttpRequests, 1);
    assert.equal(check.deniedHttpRequests, 0);
  }
  for (const id of ['query-requires-host-authority', 'query-rejects-unauthorized-endpoint-before-body-release',
    'missing-session-id', 'unknown-session', 'endpoint-revocation',
    'revocation-between-middleware-and-dispatch', 'source-forget-blocks-existing-and-late-reads',
    'forgotten-state-survives-restart', 'missing-marker-blocks-cached-body-and-query', 'missing-marker-restart-cannot-reinitialize']) {
    assert.equal(checks.get(id).httpRequests, 0, id);
  }
  assert.equal(checks.get('dispatch-uses-authorized-endpoint-snapshot').mutableProviderRouteIgnoredAtDispatch, true);
  assert.equal(checks.get('restart-restores-protection-and-grants').httpRequests, 2);
  const rejectedQuery = checks.get('query-rejects-unauthorized-endpoint-before-body-release');
  assert.equal(rejectedQuery.bodyReleased, false);
  assert.equal(rejectedQuery.persistedState, 'protected');
  assert.equal(result.localHttpReceiptCount, 9);
  assert.ok(result.receipts.every(receipt => receipt.containsSyntheticMarker === true));
  assert.ok(result.releaseEvidence.every(release => release.markerRevisionBeforeBodyRelease >= release.protectionRevision));
  assert.ok(result.releaseEvidence.every(release => release.endpointAuthorizedBeforeBodyRelease === true));
  assert.ok(result.sourceHashes.every(source => /^[a-f0-9]{64}$/.test(source.sha256)));
  assert.equal(result.productionPrivacyReady, false);
  assert.equal(result.productionProfileChanged, false);
  assert.equal(result.realPrivateDataUsed, false);
  assert.equal(result.contentMarkerUsedForAuthorization, false);
  assert.ok(!JSON.stringify(result).includes('FICTIONAL_DROY_PROTECTED_SESSION_RECORD'));
});
