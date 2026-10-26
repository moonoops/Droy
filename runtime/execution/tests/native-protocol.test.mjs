import assert from 'node:assert/strict';
import {mkdtempSync, rmSync} from 'node:fs';
import {tmpdir} from 'node:os';
import {join} from 'node:path';
import {spawnSync} from 'node:child_process';
import {fileURLToPath} from 'node:url';
import {after, test} from 'node:test';

const directory = mkdtempSync(join(tmpdir(), 'droy-exec-protocol-'));
const binary = join(directory, 'protocol-driver');
const built = spawnSync(process.env.CXX || 'c++', ['-std=c++17', '-Wall', '-Wextra', '-Werror',
  fileURLToPath(new URL('protocol-driver.cpp', import.meta.url)), '-o', binary], {encoding: 'utf8'});
assert.equal(built.status, 0, built.stderr);
after(() => rmSync(directory, {recursive: true, force: true}));
const u32 = value => { const buffer = Buffer.alloc(4); buffer.writeUInt32BE(value); return buffer; };
const text = value => { const bytes = Buffer.isBuffer(value) ? value : Buffer.from(value); return Buffer.concat([u32(bytes.length), bytes]); };
const list = values => Buffer.concat([u32(values.length), ...values.map(text)]);
function request(overrides = {}) {
  const value = {version: 1, uid: 10123, timeout: 30000, flags: 0, cols: 80, rows: 24, proxy: 0,
    files: '/data/user/0/io.droyagent.app/files', workspaceId: '',
    cwd: '/data/user/0/io.droyagent.app/files/workspace', executable: 'node',
    argv: ['node', '-e', 'console.log("合成内容")'], env: ['LANG=zh_CN.UTF-8'], ...overrides};
  return Buffer.concat([...[value.version, value.uid, value.timeout, value.flags, value.cols, value.rows, value.proxy].map(u32),
    ...[value.files, value.workspaceId, value.cwd, value.executable].map(text), list(value.argv), list(value.env)]);
}
function run(bytes) { return spawnSync(binary, [], {input: bytes, encoding: 'utf8'}); }
function rejects(fields, code) { const result = run(request(fields)); assert.equal(result.status, 2); assert.equal(result.stdout.trim(), code); }

test('normal and protected profiles resolve only the fixed authorized roots', () => {
  let result = run(request()); assert.equal(result.status, 0); assert.match(result.stdout, /files\/workspace\n/);
  const id = 'a'.repeat(64); result = run(request({workspaceId: id}));
  assert.equal(result.status, 0); assert.match(result.stdout, new RegExp(`files/droy-execution/workspaces/${id}/startup\\n`));
  result = run(request({workspaceId: id, cwd: '/data/user/0/io.droyagent.app/files/dsh-home/workspace/project'}));
  assert.equal(result.status, 0); assert.match(result.stdout, new RegExp(`files/droy-execution/workspaces/${id}/default\\n`));
  result = run(request({uid: 110123, files: '/data/user/1/io.droyagent.app/files', cwd: '/data/user/1/io.droyagent.app/files/workspace'}));
  assert.equal(result.status, 0);
});
test('another package, mismatched Android user and path traversal cannot select host roots', () => {
  rejects({files: '/data/user/0/other.app/files'}, 'invalid_files_root');
  rejects({uid: 110123}, 'invalid_files_root');
  rejects({uid: 65534}, 'invalid_host_uid');
  rejects({cwd: '/data/user/0/io.droyagent.app/files/workspace/../dsh-home'}, 'invalid_absolute_path');
  rejects({cwd: '/data/user/0/io.droyagent.app/files/workspace-other'}, 'cwd_outside_workspace');
  rejects({workspaceId: '../private'}, 'invalid_workspace_id');
});
test('unknown flags, invalid dimensions, unbounded lifetimes and private host env are rejected', () => {
  rejects({flags: 4}, 'invalid_terminal_options'); rejects({cols: 1001}, 'invalid_terminal_options');
  rejects({timeout: 86400001}, 'invalid_timeout'); rejects({proxy: 65536}, 'invalid_proxy_port');
  rejects({env: ['DSH_HOME=/private']}, 'private_environment');
  rejects({env: ['DROY_BRIDGE_TOKEN=synthetic']}, 'private_environment');
  rejects({env: ['PATH=/usr/bin', 'PATH=/system/bin']}, 'duplicate_environment');
});
test('malformed wire inputs fail before any native execution work', () => {
  const valid = request();
  for (const size of [0, 3, 27, valid.length - 1]) assert.equal(run(valid.subarray(0, size)).status, 2);
  assert.equal(run(Buffer.concat([valid, Buffer.from([0])])).stdout.trim(), 'trailing_request_data');
  rejects({executable: 'node\0ignored'}, 'nul_in_string');
  rejects({argv: [Buffer.from([0xc0, 0xaf])]}, 'invalid_utf8');
  rejects({argv: [Buffer.from([0xed, 0xa0, 0x80])]}, 'invalid_utf8');
  rejects({argv: []}, 'invalid_argv'); rejects({argv: Array(257).fill('x')}, 'too_many_arguments');
});
