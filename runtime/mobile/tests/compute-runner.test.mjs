import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { spawn } from 'node:child_process';
import { runCompute } from '../compute-runner.mjs';

const source = await readFile(new URL('../compute-runner.mjs', import.meta.url), 'utf8');
function start(request, runner = source) {
  const child = spawn(process.execPath, ['--permission', '--no-addons', '--disable-proto=throw', '--max-old-space-size=64', '--input-type=module', '--eval', runner], { env: {}, stdio: ['pipe', 'pipe', 'pipe'] });
  let output = '', stderr = '';
  child.stdout.on('data', chunk => output += chunk);
  child.stderr.on('data', chunk => stderr += chunk);
  const done = new Promise((resolve, reject) => {
    child.once('error', reject);
    child.once('close', (code, signal) => resolve({ code, signal, output, stderr }));
  });
  child.stdin.end(JSON.stringify(request));
  return { child, done };
}

test('real restricted Node computes JSON, captures output and creates named text artifacts', async () => {
  const { done } = start({ input: [2, 5, 9], code: 'const total = input.reduce((a,b)=>a+b,0); console.log("总计",total); artifact("summary.csv", "total\\n"+total, "text/csv"); return {total};' });
  const result = await done;
  assert.equal(result.code, 0, result.stderr);
  const response = JSON.parse(result.output);
  assert.equal(response.status, 'completed');
  assert.deepEqual(response.result, { total: 16 });
  assert.equal(response.stdout, '总计 16\n');
  assert.deepEqual(response.artifacts, [{ name: 'summary.csv', text: 'total\n16', mime: 'text/csv', bytes: 8 }]);
});

test('script has no process, credentials, shell, timers, fetch, or filesystem API', () => {
  const response = runCompute({ code: 'return [typeof process, typeof require, typeof fetch, typeof setTimeout, typeof Buffer];' });
  assert.deepEqual(response.result, Array(5).fill('undefined'));
  assert.deepEqual(runCompute({code:'return [typeof ArrayBuffer,typeof SharedArrayBuffer,typeof Uint8Array,typeof WebAssembly];'}).result, Array(4).fill('undefined'));
  for (const code of ['return console.log.constructor("return process")();', 'return globalThis.constructor.constructor("return process")();']) {
    const result = runCompute({ code });
    assert.equal(result.status, 'failed');
    assert.match(result.error, /Code generation|undefined|not a function/);
  }
});

test('even trusted Node entry has no inherited environment and cannot read files or spawn', async () => {
  const runner = `import fs from 'node:fs'; import {spawnSync} from 'node:child_process'; let files,spawn;try{fs.readFileSync('/etc/hosts');files='allowed'}catch(e){files=e.code}try{spawnSync('/bin/sh',['-c','echo bad']);spawn='allowed'}catch(e){spawn=e.code}process.stdout.write(JSON.stringify({files,spawn,env:Object.keys(process.env)}));`;
  const response = JSON.parse((await start({}, runner).done).output);
  assert.equal(response.files, 'ERR_ACCESS_DENIED');
  assert.equal(response.spawn, 'ERR_ACCESS_DENIED');
  // macOS injects this locale key even when exec receives an empty environment.
  assert.ok(response.env.every(key => process.platform === 'darwin' && key === '__CF_USER_TEXT_ENCODING'));
});

test('compute timeout ends synchronous and microtask loops', async () => {
  for (const code of ['while(true){}', 'Promise.resolve().then(()=>{while(true){}}); return 1;']) {
    const began = Date.now();
    const result = await start({ code, timeoutMs: 100 }).done;
    assert.equal(result.code, 0, result.stderr);
    assert.equal(JSON.parse(result.output).status, 'timed_out');
    assert.ok(Date.now() - began < 3000);
  }
});

test('process cancellation terminates work promptly and produces no completed result', async () => {
  const { child, done } = start({ code: 'while(true){}', timeoutMs: 30000 });
  setTimeout(() => child.kill('SIGKILL'), 100);
  const result = await done;
  assert.equal(result.signal, 'SIGKILL');
  assert.equal(result.output, '');
});

test('limits reject oversized code, input, stdout, results, paths and artifacts', () => {
  for (const request of [
    { code: ' '.repeat(16385) }, { code: 'return input', input: 'x'.repeat(16385) },
    { code: 'console.log("x".repeat(32769));' }, { code: 'return "x".repeat(32769);' },
    { code: 'artifact("../escape", "x");' }, { code: 'artifact("large.txt", "x".repeat(65537));' },
    { code: 'for(let i=0;i<9;i++)artifact("file"+i,"x");' },
    { code: 'artifact("x", "x"); artifact("x", "y");' },
    { code: 'artifact("x", "x", "text/html");' }, { code: '', timeoutMs: 30001 },
  ]) assert.equal(runCompute(request).status, 'failed', JSON.stringify(request).slice(0, 100));
});

test('job contexts do not share data or artifact state', () => {
  assert.equal(runCompute({ code: 'globalThis.previous=9; artifact("one", "hello"); return 1;' }).status, 'completed');
  const next = runCompute({ code: 'return typeof previous;' });
  assert.equal(next.result, 'undefined'); assert.deepEqual(next.artifacts, []);
});

test('heap limit kills excess allocation without a successful protocol response', async () => {
  const result = await start({ code: 'const arrays=[]; for(;;) arrays.push(new Array(1000000).fill(1));', timeoutMs: 5000 }).done;
  assert.notEqual(result.code, 0);
  assert.equal(result.output, '');
});
