import test, { after } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, mkdir, readFile, writeFile, copyFile, symlink, rm, readdir, realpath, lstat } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { dirname, join } from 'node:path';
import { createRequire, registerHooks } from 'node:module';
import { pathToFileURL } from 'node:url';
import { spawn } from 'node:child_process';
import { once } from 'node:events';
import { patchNativeCompanionMaintenance } from '../../../tools/native_companion_maintenance_patch.mjs';

const installed = createRequire(new URL('../../dsh/package.json', import.meta.url));
const providerPath = installed.resolve('@deepseek-ai/dsh-session-persistence-jsonl');
const packageDirectory = await mkdtemp(join(tmpdir(), 'droy-maintenance-provider-'));
await writeFile(join(packageDirectory, 'index.mjs'), patchNativeCompanionMaintenance(await readFile(providerPath, 'utf8')));
await copyFile(join(dirname(providerPath), 'worker.cjs'), join(packageDirectory, 'worker.cjs'));
await symlink(new URL('../../dsh/node_modules', import.meta.url), join(packageDirectory, 'node_modules'), 'dir');
const hooks = registerHooks({ resolve(specifier, context, next) {
  if (specifier.startsWith('@deepseek-ai/')) return { url: pathToFileURL(installed.resolve(specifier)).href, shortCircuit: true };
  return next(specifier, context);
} });
const [{ createSessionMaintenance, createOfflineJsonlProvider }, Jsonl, { createUserMessage }, { Session, buildForkSeed }] = await Promise.all([
  import('../companions/maintenance-sessions.mjs'), import(pathToFileURL(join(packageDirectory, 'index.mjs')).href),
  import('@deepseek-ai/dsh-llm'), import('@deepseek-ai/dsh-session'),
]);
hooks.deregister();
after(() => rm(packageDirectory, { recursive: true, force: true }));
const provider = createOfflineJsonlProvider(Jsonl);
const code = value => error => error.code === value;
const header = (id, extra = {}) => ({ version: 4, id, createdAt: 1, cwd: '/fictional/maintenance-workspace', isSeeded: false, delegationDepth: 0, ...extra });
const message = text => createUserMessage({ content: [{ type: 'text', text }], source: { kind: 'user' } });
const user = (seq, text) => ({ type: 'user/message', seq, time: seq + 1, surfaceOp: 'append', data: message(text) });
async function writeSession(root, compression, metadata, events, cut = 0) {
  const opened = await provider({ root, compression });
  try { const writer = await opened.store.create(metadata, { inheritedEventCount: cut });
    try { await writer.append(events); await writer.flush(); } finally { await writer.close(); }
  } finally { await opened.close(); }
}
async function readSession(root, compression, id) {
  const opened = await provider({ root, compression });
  try { const reader = await opened.store.open(id, 'read');
    try { const result = await reader.read();
      Session.fromRestore(reader.id, result.events, reader.header, reader.inheritedEventCount, result.eventState);
      return { header: reader.header, cut: reader.inheritedEventCount, events: result.events };
    } finally { await reader.close(); }
  } finally { await opened.close(); }
}
async function files(root) {
  const result = [];
  for (const entry of await readdir(root, { withFileTypes: true })) {
    const path = join(root, entry.name);
    if (entry.isDirectory()) result.push(...await files(path)); else result.push(path);
  }
  return result;
}
async function fixture(t, { compression = 'none', onCheckpoint, reserveBytes } = {}) {
  const directory = await realpath(await mkdtemp(join(tmpdir(), 'droy-maintenance-corpus-'))), root = join(directory, 'sessions');
  t.after(() => rm(directory, { recursive: true, force: true }));
  const parent = user(0, 'Fictional parent history');
  const seed = buildForkSeed([parent], 0);
  const queued = { type: 'agent/inbox/spliced', seq: seed.length, time: 2, data: { target: 'next-turn', start: 0, removedCount: 0, inserted: [message('Retained queued fixture')] } };
  await writeSession(root, compression, header('remove-parent'), [parent]);
  await writeSession(root, compression, header('keep-fork', { parentSession: 'remove-parent', isSeeded: true }), [...seed, queued], 1);
  await writeSession(root, compression, header('keep-child', { parentSession: 'remove-parent', origin: 'subagent', delegationDepth: 1 }), [user(0, 'Independent child fixture')]);
  const expected = new Map();
  for (const id of ['keep-fork', 'keep-child']) expected.set(id, await readSession(root, compression, id));
  const operationId = 'synthetic-operation'; let held = true, epoch = 'synthetic-fence';
  const authority = { async assertHeld() { return { root, operationId, admissionEpoch: epoch, hostStopped: held, admissionsClosed: held }; } };
  const options = { root, operationId, deleteSessionIds: ['remove-parent'], authority, provider, compression, onCheckpoint, ...(reserveBytes === undefined ? {} : { reserveBytes }) };
  return { root, directory, expected, compression, options,
    lose: () => { held = false; }, changeEpoch: () => { epoch = 'other-fence'; },
    create: extra => createSessionMaintenance({ ...options, ...extra }),
    journal: join(directory, '.sessions.droy-maintenance-synthetic-operation.json'),
    backup: join(directory, '.sessions.droy-maintenance-synthetic-operation.backup'),
    staging: join(directory, '.sessions.droy-maintenance-synthetic-operation.staging') };
}

test('public handles preserve retained fork prefix, child header and queued input with the parent absent from a fresh Context', async t => {
  for (const compression of ['none', 'zstd']) await t.test(compression, async child => {
    const f = await fixture(child, { compression }), maintenance = await f.create();
    const prepared = await maintenance.prepare();
    assert.equal(prepared.sessionPhase, 'validated'); assert.equal(prepared.companionDeletion, 'incomplete');
    assert.equal((await readSession(f.root, compression, 'remove-parent')).events.length, 1);
    const result = await maintenance.commit(); assert.equal(result.sessionPhase, 'complete'); assert.equal(result.oldBackupAbsent, true);
    assert.deepEqual(result.pending, ['companion-store', 'companion-memory', 'workspaces', 'schedules', 'attachments', 'android']);
    for (const [id, expected] of f.expected) assert.deepEqual(await readSession(f.root, compression, id), expected);
    await assert.rejects(readSession(f.root, compression, 'remove-parent'));
    await assert.rejects(lstat(f.backup), error => error.code === 'ENOENT');
    assert.equal((await maintenance.recover()).sessionPhase, 'complete');
  });
});
test('the strict provider audit refuses artifacts ordinary list skips, all historical generations, hardlinks and torn bodies', async t => {
  for (const kind of ['unknown-file', 'root-file', 'project-file', 'empty-project', 'empty-session', 'nonempty-lock', 'malformed-highest', 'malformed-older', 'future', 'linked', 'hardlinked', 'torn']) await t.test(kind, async child => {
    const f = await fixture(child), originalFiles = await files(f.root), generation = originalFiles.find(path => path.endsWith('.jsonl'));
    assert.ok(generation);
    if (kind === 'unknown-file') await writeFile(join(dirname(generation), 'unrecognized-data'), 'Fictional unrecognized content');
    if (kind === 'root-file') await writeFile(join(f.root, 'unknown-root-file'), 'Fictional unrecognized content');
    if (kind === 'project-file') await writeFile(join(dirname(dirname(generation)), 'unknown-project-file'), 'Fictional unrecognized content');
    if (kind === 'empty-project') await mkdir(join(f.root, 'unknown-empty-project'));
    if (kind === 'empty-session') await mkdir(join(dirname(dirname(generation)), 'unknown-empty-session'));
    if (kind === 'nonempty-lock') await writeFile(join(dirname(generation), 'session.lock'), 'Fictional non-lock contents');
    if (kind === 'malformed-highest') await writeFile(generation, '{}\n');
    if (kind === 'malformed-older') await writeFile(join(dirname(generation), 'session.v1.jsonl'), '{}\n');
    if (kind === 'future') await writeFile(join(dirname(generation), 'session.v999.jsonl'), '{}\n');
    if (kind === 'linked') await symlink(generation, join(dirname(generation), 'linked'));
    if (kind === 'hardlinked') { const { link } = await import('node:fs/promises'); await link(generation, join(f.directory, 'outside-shared-link')); }
    if (kind === 'torn') await writeFile(generation, (await readFile(generation)).subarray(0, (await readFile(generation)).length - 1));
    const maintenance = await f.create(); await assert.rejects(maintenance.prepare(), code('MAINTENANCE_AUDIT_FAILED'));
    await assert.rejects(lstat(f.journal), error => error.code === 'ENOENT');
    assert.equal((await files(f.root)).length >= originalFiles.length, true);
  });
});
test('missing or lost exclusive authority, space shortage and changed corpus cause no source replacement', async t => {
  const f = await fixture(t);
  await assert.rejects(f.create({ authority: null }), code('MAINTENANCE_AUTHORITY_REQUIRED'));
  const tooLarge = await f.create({ reserveBytes: Number.MAX_SAFE_INTEGER }); await assert.rejects(tooLarge.prepare(), code('MAINTENANCE_SPACE_REQUIRED'));
  const maintenance = await f.create(); await maintenance.prepare();
  f.lose(); await assert.rejects(maintenance.commit(), code('MAINTENANCE_AUTHORITY_LOST'));
  assert.equal((await readSession(f.root, f.compression, 'remove-parent')).events.length, 1);
  await assert.rejects(lstat(f.backup), error => error.code === 'ENOENT');
});
test('a changed source or corrupted retained stage cannot start the destructive swap', async t => {
  for (const changed of ['source', 'stage', 'journal']) await t.test(changed, async child => {
    const f = await fixture(child), maintenance = await f.create(); await maintenance.prepare();
    if (changed === 'source') await writeSession(f.root, f.compression, header('new-unrelated'), [user(0, 'New retained fixture')]);
    if (changed === 'stage') { const path = (await files(f.staging)).find(value => value.endsWith('.jsonl')); await writeFile(path, '{}\n'); }
    if (changed === 'journal') { const journal = JSON.parse(await readFile(f.journal, 'utf8')); journal.retained = []; await writeFile(f.journal, JSON.stringify(journal)); }
    await assert.rejects(maintenance.commit()); assert.equal((await readSession(f.root, f.compression, 'remove-parent')).events.length, 1);
    await assert.rejects(lstat(f.backup), error => error.code === 'ENOENT');
  });
});
test('durable checkpoints recover interrupted preparation, namespace swaps and cleanup without leaving an old backup', async t => {
  for (const stopAt of ['preparing', 'prepared', 'moving-old', 'source-renamed', 'old-moved', 'installing', 'staging-installed', 'installed', 'removing-backup', 'backup-removed', 'complete']) await t.test(stopAt, async child => {
    let interrupted = false;
    const f = await fixture(child, { onCheckpoint: phase => { if (!interrupted && phase === stopAt) { interrupted = true; throw Error('Synthetic process interruption'); } } });
    const first = await f.create();
    try { await first.prepare(); await first.commit(); } catch (error) { assert.equal(error.message, 'Synthetic process interruption'); }
    assert.equal(interrupted, true);
    const recovered = await f.create({ onCheckpoint() {} });
    let result = await recovered.recover(); if (result.sessionPhase === 'validated') result = await recovered.commit();
    assert.equal(result.sessionPhase, 'complete');
    for (const [id, expected] of f.expected) assert.deepEqual(await readSession(f.root, f.compression, id), expected);
    await assert.rejects(lstat(f.backup), error => error.code === 'ENOENT');
    await assert.rejects(lstat(f.staging), error => error.code === 'ENOENT');
  });
});
test('corruption after install never destroys the only remaining old backup', async t => {
  let stop = true;
  const f = await fixture(t, { onCheckpoint: phase => { if (phase === 'removing-backup' && stop) { stop = false; throw Error('Synthetic interruption before cleanup'); } } });
  const first = await f.create(); await first.prepare(); await assert.rejects(first.commit());
  const path = (await files(f.root)).find(value => value.endsWith('.jsonl')); await writeFile(path, '{}\n');
  const recovered = await f.create({ onCheckpoint() {} }); await assert.rejects(recovered.recover());
  assert.equal((await lstat(f.backup)).isDirectory(), true);
});
test('a real SIGKILL at namespace and backup boundaries resumes from the durable journal in another process', async t => {
  for (const stopAt of ['session-copied', 'source-renamed', 'staging-installed', 'backup-removed']) await t.test(stopAt, async child => {
    const f = await fixture(child), script = join(f.directory, 'synthetic-kill.mjs');
    await writeFile(script, `import {createRequire,registerHooks} from 'node:module';import {pathToFileURL} from 'node:url';
      const installed=createRequire(${JSON.stringify(new URL('../../dsh/package.json', import.meta.url).href)});
      const hooks=registerHooks({resolve(s,c,n){if(s.startsWith('@deepseek-ai/'))return {url:pathToFileURL(installed.resolve(s)).href,shortCircuit:true};return n(s,c);}});
      const [{createSessionMaintenance,createOfflineJsonlProvider},provider]=await Promise.all([
        import(${JSON.stringify(new URL('../companions/maintenance-sessions.mjs', import.meta.url).href)}),
        import(${JSON.stringify(pathToFileURL(join(packageDirectory, 'index.mjs')).href)})]);hooks.deregister();
      const root=process.argv[2],operationId='synthetic-operation';
      const authority={async assertHeld(){return {root,operationId,admissionEpoch:'synthetic-fence',hostStopped:true,admissionsClosed:true};}};
      const job=await createSessionMaintenance({root,operationId,deleteSessionIds:['remove-parent'],authority,
        provider:createOfflineJsonlProvider(provider),compression:'none',onCheckpoint(phase){if(phase===process.argv[3])process.kill(process.pid,'SIGKILL');}});
      await job.prepare();await job.commit();throw Error('The synthetic kill checkpoint was not reached');`);
    const processChild = spawn(process.execPath, ['--expose-internals', script, f.root, stopAt], { stdio: ['ignore', 'pipe', 'pipe'] });
    child.after(() => processChild.kill()); let stderr = ''; processChild.stderr.on('data', part => { stderr += part; }); processChild.stdout.resume();
    const timer = setTimeout(() => processChild.kill(), 15_000);
    let signal; try { [, signal] = await once(processChild, 'exit'); } finally { clearTimeout(timer); }
    assert.equal(signal, 'SIGKILL', stderr);
    const maintenance = await f.create(); let receipt = await maintenance.recover();
    if (receipt.sessionPhase === 'validated') receipt = await maintenance.commit(); assert.equal(receipt.sessionPhase, 'complete');
    for (const [id, expected] of f.expected) assert.deepEqual(await readSession(f.root, f.compression, id), expected);
    await assert.rejects(lstat(f.backup), error => error.code === 'ENOENT');
  });
});
test('all recognized old generations are audited before the current public logical log can be retained', async t => {
  const f = await fixture(t), opened = await provider({ root: f.root, compression: f.compression });
  try {
    const logs = (await files(f.root)).filter(path => path.endsWith('session.v4.jsonl'));
    const path = logs.find(path => path.includes('remove-parent')); assert.ok(path);
    const lines = (await readFile(path, 'utf8')).trimEnd().split('\n'), historical = JSON.parse(lines[0]); historical.version = 3;
    await writeFile(join(dirname(path), 'session.v3.jsonl'), [JSON.stringify(historical), ...lines.slice(1)].join('\n') + '\n');
    const audit = await opened.audit(); assert.equal(audit.complete, true);
    assert.deepEqual(audit.entries.find(row => row.id === 'remove-parent').generations.map(row => row.version), [3, 4]);
    const reader = await opened.store.open('remove-parent', 'read');
    try { await assert.rejects(opened.audit(), error => error.code === 'DSH_OFFLINE_AUDIT_BLOCKED' && error.reason === 'provider-not-offline'); }
    finally { await reader.close(); }
  } finally { await opened.close(); }
  const maintenance = await f.create(); await maintenance.prepare(); assert.equal((await maintenance.commit()).sessionPhase, 'complete');
});
test('partially removed old backups can only be cleaned after the installed retained corpus still validates', async t => {
  const f = await fixture(t, { onCheckpoint: phase => { if (phase === 'removing-backup') throw Error('Synthetic interruption during cleanup'); } });
  const first = await f.create(); await first.prepare(); await assert.rejects(first.commit());
  const path = (await files(f.backup)).find(value => value.endsWith('.jsonl')); await rm(path);
  const recovered = await f.create({ onCheckpoint() {} }); assert.equal((await recovered.recover()).sessionPhase, 'complete');
  for (const [id, expected] of f.expected) assert.deepEqual(await readSession(f.root, f.compression, id), expected);
  await assert.rejects(lstat(f.backup), error => error.code === 'ENOENT');
});
test('same-operation calls serialize, empty retained corpora remain valid and unknown targets never authorize a swap', async t => {
  await t.test('serialized retry', async child => {
    const f = await fixture(child), job = await f.create();
    assert.deepEqual(await Promise.all([job.prepare(), job.prepare()]).then(rows => rows.map(row => row.sessionPhase)), ['validated', 'validated']);
    assert.deepEqual(await Promise.all([job.commit(), job.commit()]).then(rows => rows.map(row => row.sessionPhase)), ['complete', 'complete']);
    for (const [id, expected] of f.expected) assert.deepEqual(await readSession(f.root, f.compression, id), expected);
  });
  await t.test('all selected Sessions', async child => {
    const f = await fixture(child), job = await f.create({ deleteSessionIds: ['keep-fork', 'keep-child', 'remove-parent'] });
    await job.prepare(); const receipt = await job.commit(); assert.equal(receipt.retainedCount, 0); assert.equal(receipt.sessionPhase, 'complete');
    const opened = await provider({ root: f.root, compression: f.compression }); try { assert.deepEqual((await opened.audit()).entries, []); } finally { await opened.close(); }
  });
  await t.test('unknown target', async child => {
    const f = await fixture(child), job = await f.create({ deleteSessionIds: ['not-present'] });
    await assert.rejects(job.prepare(), code('MAINTENANCE_TARGET_NOT_FOUND')); await assert.rejects(lstat(f.journal), error => error.code === 'ENOENT');
  });
  await t.test('new fence epoch', async child => {
    const f = await fixture(child), job = await f.create(); await job.prepare(); f.changeEpoch();
    await assert.rejects(job.commit(), code('MAINTENANCE_AUTHORITY_CHANGED'));
    assert.equal((await readSession(f.root, f.compression, 'remove-parent')).events.length, 1);
  });
});
