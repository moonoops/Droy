import test, { after } from 'node:test';
import assert from 'node:assert/strict';
import { createRequire, registerHooks, isBuiltin } from 'node:module';
import { mkdtemp, readFile, writeFile, stat, chmod, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { pathToFileURL } from 'node:url';
import { patchExecutionFileMode } from '../../privacy/upstream-patch.mjs';
import { runFileRequest } from '../fs-rpc.mjs';

const installed = createRequire(new URL('../../dsh/package.json', import.meta.url));
const root = await mkdtemp(join(tmpdir(), 'droy-file-publication-'));
after(() => rm(root, { recursive: true, force: true }));
const source = await readFile(installed.resolve('@deepseek-ai/dsh-fs-local'), 'utf8');
const patched = patchExecutionFileMode(source);
await writeFile(join(root, 'fs-local.mjs'), patched);
const hook = registerHooks({ resolve(specifier, context, next) {
  if (!isBuiltin(specifier) && !specifier.startsWith('.') && !specifier.startsWith('file:')) return { url: pathToFileURL(installed.resolve(specifier)).href, shortCircuit: true };
  return next(specifier, context);
} });
const { Context } = await import('@deepseek-ai/cordis');
const { default: LocalFileSystem } = await import(pathToFileURL(join(root, 'fs-local.mjs')).href);
hook.deregister();

async function backend(t, newFileMode) {
  const directory = await mkdtemp(join(root, 'files-'));
  const ctx = new Context(); await ctx.plugin(LocalFileSystem, { cwd: directory, ...(newFileMode === undefined ? {} : { newFileMode }) });
  t.after(() => ctx.fiber.dispose());
  const request = async (method, args) => { let result; await runFileRequest(ctx.fs, { method, args }, row => { result = row.value; }); return result; };
  return { ctx, directory, request };
}

test('atomic publication patch rejects unknown inputs and repeat application', () => {
  assert.throws(() => patchExecutionFileMode(source + '\n'), /input changed/);
  assert.throws(() => patchExecutionFileMode(patched), /input changed/);
});

test('isolated create receipt directly guards replacement while staging remains private', async t => {
  const f = await backend(t, 0o660), stagedModes = [];
  f.ctx.fs.internals.inspectTemp = async ({ stagingDir, tempPath }) => {
    stagedModes.push([(await stat(stagingDir)).mode & 0o777, (await stat(tempPath)).mode & 0o777]);
  };
  const target = await f.request('resolve', ['synthetic.txt']);
  const created = await f.request('writeText', [target, 'alpha', { kind: 'createIfAbsent' }]);
  const replaced = await f.request('writeText', [target, 'beta', { kind: 'replaceIfVersion', version: created.version }]);
  assert.notEqual(replaced.version, created.version);
  assert.deepEqual(stagedModes, [[0o700, 0o600], [0o700, 0o600]]);
  assert.equal((await stat(join(f.directory, 'synthetic.txt'))).mode & 0o777, 0o660);
  await assert.rejects(f.request('writeText', [target, 'stale', { kind: 'replaceIfVersion', version: created.version }]), { code: 'FS_STALE_VERSION' });
  assert.equal(await readFile(join(f.directory, 'synthetic.txt'), 'utf8'), 'beta');
});

test('ordinary files keep their default mode and replacements retain executable permissions', async t => {
  const plain = await backend(t);
  const target = await plain.request('resolve', ['plain.txt']);
  await plain.request('writeText', [target, 'plain', { kind: 'createIfAbsent' }]);
  assert.equal((await stat(join(plain.directory, 'plain.txt'))).mode & 0o777, 0o600);
  const isolated = await backend(t, 0o660);
  const executable = join(isolated.directory, 'run.sh'); await writeFile(executable, 'old'); await chmod(executable, 0o770);
  const program = await isolated.request('resolve', ['run.sh']);
  const observed = await isolated.request('stat', [program]);
  await isolated.request('writeText', [program, 'new', { kind: 'replaceIfVersion', version: observed.version }]);
  assert.equal((await stat(executable)).mode & 0o777, 0o770);
});
