import test, { after } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, rm, mkdir, copyFile } from 'node:fs/promises';
import { join } from 'node:path';
import { tmpdir } from 'node:os';
import { registerHooks } from 'node:module';
import { spawnSync } from 'node:child_process';
import { pathToFileURL } from 'node:url';

const sourceImports = registerHooks({ resolve(specifier, context, next) {
  if (specifier === '@droyagent/memory/store') return { url: new URL('../../memory/store.mjs', import.meta.url).href, shortCircuit: true };
  return next(specifier, context);
} });
after(() => sourceImports.deregister());
const { CompanionMemory } = await import('../companions/memory.mjs');

const code = expected => error => error.code === expected;
async function fixture(t) {
  const directory = await mkdtemp(join(tmpdir(), 'droy-companion-memory-'));
  const memories = new Map();
  const open = async companionId => {
    const memory = await CompanionMemory.open({ directory, companionId }); memories.set(companionId, memory); return memory;
  };
  t.after(async () => { await Promise.all([...memories.values()].map(memory => memory.close())); await rm(directory, { recursive: true, force: true }); });
  return { directory, open, async close(companionId) { await memories.get(companionId).close(); memories.delete(companionId); } };
}

test('ordinary companion memory works without bridge or Root and remains independent after restart', async t => {
  const f = await fixture(t), kaka = await f.open('kaka'), maimai = await f.open('maimai');
  const kakaFact = await kaka.remember({ text: 'Fictional user likes coffee', sourceRefs: ['message-one'], operationId: 'write-kaka' });
  await maimai.remember({ text: 'Fictional user likes tea', sourceRefs: ['message-one'], operationId: 'write-maimai' });
  assert.equal(kakaFact.record.companionId, 'kaka');
  assert.deepEqual(kakaFact.record.sourceRefs, ['im:kaka:message-one']);
  assert.equal((await kaka.query({ text: 'tea' })).total, 0);
  assert.equal((await maimai.query({ text: 'coffee' })).total, 0);
  assert.equal(await maimai.get(kakaFact.recordId), null);
  await f.close('kaka');
  const reopened = await f.open('kaka');
  assert.equal((await reopened.query()).items[0].payload.text, 'Fictional user likes coffee');
  assert.equal((await reopened.status()).companionId, 'kaka');
  await assert.rejects(CompanionMemory.open({ directory: f.directory, companionId: '../maimai' }), code('COMPANION_NOT_FOUND'));
});

test('correction uses revision checks and forget suppresses old sources and derived material', async t => {
  const f = await fixture(t), memory = await f.open('kaka');
  const original = await memory.remember({ text: 'Fictional old preference', sourceRefs: ['original'], operationId: 'original' });
  const derived = await memory.remember({ text: 'Fictional derived impression', assertion: 'model_inference',
    derivedFrom: [{ recordId: original.recordId, revisionId: original.revisionId }], operationId: 'derived' });
  const corrected = await memory.revise({ recordId: original.recordId, expectedRevisionId: original.revisionId,
    text: 'Fictional corrected preference', patch: { sourceRefs: ['correction'] }, operationId: 'correction' });
  assert.equal((await memory.get(derived.recordId)).state, 'expired');
  await assert.rejects(memory.revise({ recordId: original.recordId, expectedRevisionId: original.revisionId,
    text: 'Stale fictional correction', operationId: 'stale' }), code('MEMORY_REVISION_CONFLICT'));
  const forgotten = await memory.forget({ recordId: corrected.recordId, expectedRevisionId: corrected.revisionId, operationId: 'forget' });
  assert.deepEqual(forgotten.forgottenSources, ['im:kaka:correction', 'im:kaka:original']);
  const retried = await memory.forget({ recordId: corrected.recordId, expectedRevisionId: corrected.revisionId, operationId: 'forget' });
  assert.equal(retried.duplicate, true);
  assert.deepEqual(retried.forgottenSources, forgotten.forgottenSources);
  assert.equal((await memory.get(original.recordId)).payload, null);
  assert.equal((await memory.query()).total, 0);
  await f.close('kaka');
  const reopened = await f.open('kaka');
  for (const source of ['original', 'correction']) await assert.rejects(reopened.remember({
    text: 'Fictional late extraction', sourceRefs: [source], operationId: 'late-' + source,
  }), code('MEMORY_SOURCE_FORGOTTEN'));
});

test('memory operations cannot claim another companion source or retain recognizable credentials', async t => {
  const f = await fixture(t), memory = await f.open('kaka');
  await assert.rejects(memory.remember({ text: 'Fictional borrowed source', sourceRefs: ['im:maimai:one'] }), code('COMPANION_SOURCE_SCOPE'));
  await assert.rejects(memory.remember({ text: 'api_key: fictional-secret-value' }), code('COMPANION_MEMORY_CREDENTIAL'));
  await assert.rejects(memory.remember({ text: 'Bearer fictional-test-token' }), code('COMPANION_MEMORY_CREDENTIAL'));
  const remembered = await memory.remember({ text: 'Fictional allowed preference' });
  await assert.rejects(memory.revise({ recordId: remembered.recordId, expectedRevisionId: remembered.revisionId,
    text: 'password: fictional-secret-value' }), code('COMPANION_MEMORY_CREDENTIAL'));
  assert.equal((await memory.query()).total, 1);
});

test('shared experiences use the existing note event format and can be corrected', async t => {
  const f = await fixture(t), memory = await f.open('kaka');
  const event = await memory.remember({ kind: 'event', text: 'Fictional shared conversation', sourceRefs: ['event-message'] });
  assert.equal(event.record.payload.eventType, 'note');
  const revised = await memory.revise({ recordId: event.recordId, expectedRevisionId: event.revisionId, text: 'Corrected fictional shared conversation' });
  assert.deepEqual(revised.record.payload, { eventType: 'note', text: 'Corrected fictional shared conversation' });
});

test('memory resolves its package export from both staged mobile deployment locations', async t => {
  const root = await mkdtemp(join(tmpdir(), 'droy-companion-memory-package-'));
  t.after(() => rm(root, { recursive: true, force: true }));
  const memoryPackage = join(root, 'node_modules', '@droyagent', 'memory');
  await mkdir(memoryPackage, { recursive: true });
  for (const name of ['package.json', 'store.mjs', 'store-worker.mjs', 'schema.mjs']) {
    await copyFile(new URL('../../memory/' + name, import.meta.url), join(memoryPackage, name));
  }
  const layouts = [join(root, 'mobile', 'companions'), join(root, 'node_modules', '@droyagent', 'mobile', 'companions')];
  for (const layout of layouts) {
    await mkdir(layout, { recursive: true });
    for (const name of ['memory.mjs', 'profiles.mjs']) await copyFile(new URL('../companions/' + name, import.meta.url), join(layout, name));
    await mkdir(join(layout,'maibot'),{recursive:true});
    await copyFile(new URL('../companions/maibot/persona-source.json',import.meta.url),join(layout,'maibot','persona-source.json'));
  }
  const script = `
    const urls = JSON.parse(process.argv[1]);
    for (const [index, url] of urls.entries()) {
      const { CompanionMemory } = await import(url);
      const memory = await CompanionMemory.open({ directory: process.argv[2] + '/data-' + index, companionId: 'kaka' });
      await memory.remember({ text: 'Fictional deployed memory' });
      if ((await memory.query()).total !== 1) throw new Error('DEPLOYED_MEMORY_MISSING');
      await memory.close();
    }
  `;
  const result = spawnSync(process.execPath, ['--input-type=module', '-e', script,
    JSON.stringify(layouts.map(layout => pathToFileURL(join(layout, 'memory.mjs')).href)), root], { encoding: 'utf8', timeout: 10000 });
  assert.equal(result.error, undefined);
  assert.equal(result.status, 0, result.stderr);
});
