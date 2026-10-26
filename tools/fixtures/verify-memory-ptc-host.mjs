// Host contract check for the same installed DSH PTC API used by the device
// runner. This does not qualify Android execution or the Root boundary.
import { createRequire } from 'node:module';
import { pathToFileURL } from 'node:url';
import { mkdtemp, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import assert from 'node:assert/strict';
const installed = createRequire(new URL('../../runtime/dsh/package.json', import.meta.url));
const load = name => import(pathToFileURL(installed.resolve(name)).href);
const [{ Context }, { default: Subprocess }, { default: Fs }, { default: Ptc }] = await Promise.all([
  load('@deepseek-ai/cordis'), load('@deepseek-ai/dsh-subprocess-local'), load('@deepseek-ai/dsh-fs-sandbox'), load('@deepseek-ai/dsh-ptc-runtime-node'),
]);
const directory = await mkdtemp(join(tmpdir(), 'droy-ptc-contract-')), ctx = new Context();
try {
  const policy = { mode: 'danger-full-access', workspaceRoot: directory };
  ctx.provide('sandboxPolicy', { defaultMode: policy.mode, resolve: () => policy });
  ctx.provide('sandbox', { confine: async () => { throw new Error('Unexpected confinement'); } });
  await ctx.plugin(Subprocess); await ctx.plugin(Fs, { cwd: directory });
  await ctx.plugin(Ptc, { launch: { kind: 'node-script', executable: process.execPath }, executionMode: 'per-call', timeoutMs: 15000, maxTimeoutMs: 20000,
    maxOutputBytes: 65536, maxMessageBytes: 262144, maxPendingCalls: 8, maxOldGenerationSizeMb: 64, graceMs: 3000 });
  let calls = 0;
  const result = await ctx.ptcRuntime.run(ctx.ptcRuntime.resolve({ program: 'const p = await import("node:process"); const v = await synthetic.echo({value:7}); console.log("DROY_PTC_SYNTHETIC"); return {value:v.value,uid:p.getuid()};',
    cwd: directory, sandboxPolicy: policy, bindings: [{ global: 'synthetic', functions: { echo: async value => { calls++; return { value: value.value + 1 }; } } }] }));
  assert.equal(result.error, undefined, JSON.stringify(result.error)); assert.deepEqual(result.value, { value: 8, uid: process.getuid() });
  assert.equal(calls, 1); assert.ok(result.logs.some(line => line.includes('DROY_PTC_SYNTHETIC')));
  process.stdout.write(JSON.stringify({ hostContractOnly: true, ptcControlRoundtrip: true }) + '\n');
} finally { await ctx.fiber.dispose(); await rm(directory, { recursive: true, force: true }); }
