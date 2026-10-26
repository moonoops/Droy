import test from 'node:test';
import assert from 'node:assert/strict';
import {access, readFile} from 'node:fs/promises';
import path from 'node:path';
import vm from 'node:vm';
import {patchNodePtyLoader} from '../../../tools/mobile_native_patch.mjs';

const upstream = await readFile(new URL('../../dsh/node_modules/node-pty/lib/utils.js', import.meta.url), 'utf8');

function load(overrides = {}) {
  const exports = {};
  const calls = [];
  const process = {platform:'android', arch:'arm64', env:{DROY_NATIVE_DIR:'/app/native'},
    dlopen(module, file) { calls.push(file); module.exports = {fork:'native-pty'}; }, ...overrides};
  vm.runInNewContext(patchNodePtyLoader(upstream), {exports, process, require(name) {
    if (name === 'path') return path;
    throw new Error(`Unexpected native fallback: ${name}`);
  }});
  return {load:exports.loadNativeModule, calls};
}

test('Android node-pty loads only the installed native-library path', () => {
  const runtime = load();
  const result = runtime.load('pty');
  assert.equal(result.module.fork, 'native-pty');
  assert.equal(result.dir, '/app/native');
  assert.deepEqual(runtime.calls, ['/app/native/libdroy_pty.so']);
});

test('Android PTY reports missing installation and loader errors', () => {
  assert.throws(() => load({env:{}}).load('pty'), /DROY_NATIVE_DIR is required/);
  assert.throws(() => load({dlopen() { throw new Error('bad ELF'); }}).load('pty'), /bad ELF/);
});

test('non-Android node-pty retains the official native module lookup', () => {
  const runtime = load({platform:'darwin'});
  assert.throws(() => runtime.load('pty'), /Failed to load native module/);
  assert.deepEqual(runtime.calls, []);
});

test('node-pty loader patch rejects upstream drift and repeat application', () => {
  assert.throws(() => patchNodePtyLoader('changed upstream'), /anchor changed/);
  assert.throws(() => patchNodePtyLoader(patchNodePtyLoader(upstream)), /anchor changed/);
});

test('prepared Android PTY excludes host executables and keeps its license', async () => {
  const prepared = new URL('../../generated/dsh/node_modules/node-pty/', import.meta.url);
  await assert.rejects(access(new URL('prebuilds/', prepared)), {code: 'ENOENT'});
  const [loader, license] = await Promise.all([
    readFile(new URL('lib/utils.js', prepared), 'utf8'),
    readFile(new URL('LICENSE', prepared), 'utf8'),
  ]);
  assert.match(loader, /DROY_NATIVE_DIR/);
  assert.match(license, /Permission is hereby granted/);
});
