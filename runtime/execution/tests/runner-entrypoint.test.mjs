import assert from 'node:assert/strict';
import { mkdtempSync, rmSync, symlinkSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { spawnSync } from 'node:child_process';
import { test } from 'node:test';

test('device verifier invoked through a filesystem alias runs and reports failure outside the App domain', () => {
  const directory = mkdtempSync(join(tmpdir(), 'droy-verifier-entry-'));
  try {
    const alias = join(directory, 'verify.mjs');
    symlinkSync(fileURLToPath(new URL('../../../tools/verify_memory_execution.mjs', import.meta.url)), alias);
    const child = spawnSync(process.execPath, [alias, '--files-dir', '/data/user/0/io.droyagent.app/files',
      '--native-dir', '/data/app/synthetic/lib/arm64'], { encoding: 'utf8', timeout: 5000, maxBuffer: 65536 });
    assert.equal(child.status, 1);
    const report = JSON.parse(child.stdout.trim());
    assert.equal(report.type, 'verification-report');
    assert.equal(report.passed, false);
  } finally { rmSync(directory, { recursive: true, force: true }); }
});
