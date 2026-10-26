import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, readFile, readdir, rm, writeFile } from 'node:fs/promises';
import { join } from 'node:path';
import { tmpdir } from 'node:os';
import { reportMobileStartupFailure, startupDiagnostic } from '../diagnostics.mjs';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
const secret='fictional-secret-MUST-NEVER-APPEAR';
test('startup diagnostics contain only fixed codes and allowlisted component names',async()=>{
  const home=await mkdtemp(join(tmpdir(),'droy-diagnostic-'));const output=[];
  const error=Object.assign(new Error(secret),{config:{apiKey:secret},cause:new Error(secret),entries:[{id:'settings',module:secret,outcome:{kind:'failed',error:new Error(secret)}},{id:secret,outcome:{kind:secret}}]});
  try{
    await reportMobileStartupFailure(error,{home,version:secret,profile:secret},text=>output.push(text));
    const files=await readdir(join(home,'logs'));assert.equal(files.length,1);
    const log=await readFile(join(home,'logs',files[0]),'utf8');
    assert.ok(!log.includes(secret));assert.ok(!output.join('').includes(secret));
    assert.deepEqual(JSON.parse(log).components,[{component:'settings',state:'failed'},{component:'unknown-component',state:'unknown'}]);
    let accessed=false;const getter=Object.defineProperty({},'entries',{get(){accessed=true;throw new Error(secret);}});
    assert.deepEqual(startupDiagnostic(getter).components,[]);assert.equal(accessed,false);
    const blocked=join(home,'blocked');await writeFile(blocked,secret);
    await reportMobileStartupFailure(error,{home:blocked},text=>output.push(text));
    assert.ok(output.includes('DROY_DIAGNOSTIC_WRITE_FAILED\n'));assert.ok(!output.join('').includes(secret));
  }finally{await rm(home,{recursive:true,force:true});}
});
test('generated CLI dispatch uses safe diagnostics and retains no raw startup dump',async()=>{
  const cli=await readFile(new URL('../../generated/dsh/node_modules/@deepseek-ai/dsh/lib/bin.js',import.meta.url),'utf8');
  assert.ok(cli.includes('await reportMobileStartupFailure(error, context, write)'));
  assert.ok(cli.includes('await reportMobileStartupFailure(error, {home: process.env.DSH_HOME})'));
  assert.ok(!cli.includes('Raw diagnostics'));assert.ok(!cli.includes('inspect({'));assert.ok(!cli.includes('`${error.message}'));
});
test('actual generated CLI startup failure does not echo fictional secret in profile name',async()=>{
  const home=await mkdtemp(join(tmpdir(),'droy-cli-failure-'));
  try {
    const cli=fileURLToPath(new URL('../../generated/dsh/node_modules/@deepseek-ai/dsh/lib/bin.js',import.meta.url));
    const result=spawnSync(process.execPath,['--expose-internals',cli,'--profile',secret],{env:{...process.env,DSH_HOME:home,DSH_TELEMETRY_DISABLED:'1'},encoding:'utf8',timeout:5000});
    assert.equal(result.status,1);assert.ok(!result.stdout.includes(secret));assert.ok(!result.stderr.includes(secret));assert.match(result.stderr,/DROY_STARTUP_FAILED/);
    const logs=await readdir(join(home,'logs'));assert.equal(logs.length,1);assert.ok(!(await readFile(join(home,'logs',logs[0]),'utf8')).includes(secret));
  } finally {await rm(home,{recursive:true,force:true});}
});
