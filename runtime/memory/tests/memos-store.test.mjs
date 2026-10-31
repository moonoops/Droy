import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, rm, realpath, readFile } from 'node:fs/promises';
import { join } from 'node:path';
import { tmpdir } from 'node:os';
import { DatabaseSync } from 'node:sqlite';
import { createHash } from 'node:crypto';
import { MemosMemoryStore } from '../memos/store.mjs';
import { MemoryStore } from '../store.mjs';
import { adaptDatabase } from '../memos/upstream/core/storage/connection.mjs';

const code = expected => error => error.code === expected;
const fact = (body, source = 'im:alpha:one') => ({ kind: 'fact', payload: { text: body }, sourceRefs: [source] });
async function fixture(t) {
  const root = await realpath(await mkdtemp(join(tmpdir(), 'droy-memos-test-'))), opened = new Set();
  const open = async (name = 'alpha', options = {}) => {
    const store = await MemosMemoryStore.open({ directory: join(root, name), companionId: name, memorySpaceId: 'space-' + name, initialize: true, ...options });
    opened.add(store); return store;
  };
  t.after(async () => { await Promise.all([...opened].map(store => store.close())); await rm(root, { recursive: true, force: true }); });
  return { root, open, close: async store => { await store.close(); opened.delete(store); } };
}

test('pinned MemOS sources match generated hashes and run without desktop packages', async () => {
  const base = new URL('../memos/upstream/', import.meta.url), manifest = JSON.parse(await readFile(new URL('manifest.json', base), 'utf8'));
  assert.equal(manifest.commit, '64388b50e2a15528bcdf032e65a1ac792bcf63ee');
  for (const file of manifest.files) {
    const content = await readFile(new URL(file.generated, base));
    assert.equal(createHash('sha256').update(content).digest('hex'), file.generatedSha256, file.generated);
  }
  assert.match(await readFile(new URL('LICENSE', base), 'utf8'), /Apache License/);
});

test('MemOS facade handles arrays, named parameters, nested rollback and rejects asynchronous transactions', () => {
  const raw = new DatabaseSync(':memory:'), db = adaptDatabase(raw, ':memory:', 'test');
  try {
    db.exec('CREATE TABLE fixture(id INTEGER PRIMARY KEY,value TEXT)');
    db.tx(() => {
      db.prepare('INSERT INTO fixture VALUES(?,?)').run([1, 'kept']);
      assert.throws(() => db.tx(() => { db.prepare('INSERT INTO fixture VALUES(@id,@value)').run({ id: 2, value: 'rolled back' }); throw new Error('abort'); }));
      assert.equal(db.prepare('SELECT value FROM fixture WHERE id=@id').get({ id: 1 }).value, 'kept');
    });
    assert.equal(db.prepare('SELECT count(*) n FROM fixture').get().n, 1);
    assert.throws(() => db.tx(() => Promise.resolve()), /MEMOS_ASYNC_TRANSACTION/);
    assert.equal(raw.isTransaction, false);
  } finally { db.close(); }
});

test('independent owner databases use real MemOS traces and bilingual keyword retrieval', async t => {
  const f = await fixture(t), a = await f.open(), b = await f.open('beta');
  const item = await a.record({ operationId: 'write-one', record: fact('虚构用户住在杭州，喜欢 coffee。') });
  await b.record({ operationId: 'write-two', record: fact('Fictional user likes tea', 'im:beta:one') });
  assert.equal((await a.status()).engine, 'memos');
  assert.equal((await a.search({ text: '杭州' })).items[0].recordId, item.recordId);
  assert.equal((await a.search({ text: 'coffee' })).items[0].recordId, item.recordId);
  assert.equal((await b.search({ text: '杭州' })).total, 0);
  assert.equal(await b.get({ recordId: item.recordId }), null);
  await f.close(a);
  const db = new DatabaseSync(join(f.root, 'alpha/memory.sqlite'));
  try {
    assert.equal(db.prepare('SELECT user_text FROM traces WHERE id=?').get(item.recordId).user_text, '虚构用户住在杭州，喜欢 coffee。');
    assert.equal(JSON.parse(db.prepare('SELECT data FROM records WHERE record_id=?').get(item.recordId).data).payload.text, undefined);
    assert.equal(db.prepare('SELECT count(*) n FROM schema_migrations').get().n, 14);
  } finally { db.close(); }
  const again = await f.open();
  assert.equal((await again.get({ recordId: item.recordId })).payload.text, '虚构用户住在杭州，喜欢 coffee。');
});

test('revision conflicts and derivative invalidation preserve history but remove obsolete vectors', async t => {
  const f = await fixture(t), s = await f.open();
  const original = await s.record({ operationId: 'old', record: fact('Fictional old address') });
  const derived = await s.record({ operationId: 'derived', record: { ...fact('Fictional impression', 'im:alpha:two'), assertion: 'model_inference', derivedFrom: [{ recordId: original.recordId, revisionId: original.revisionId }] } });
  await s.setEmbedding({ recordId: original.recordId, expectedRevisionId: original.revisionId, vector: [1, 0], model: 'test-v1' });
  assert.equal((await s.search({ text: 'unrelated', vector: [1, 0], model: 'test-v1' })).total, 1);
  const revised = await s.revise({ operationId: 'correction', recordId: original.recordId, expectedRevisionId: original.revisionId, patch: { payload: { text: 'Fictional new address' }, sourceRefs: ['im:alpha:new'] } });
  assert.equal((await s.get({ recordId: derived.recordId })).state, 'expired');
  assert.equal((await s.get({ recordId: derived.recordId })).payload.text, 'Fictional impression');
  assert.equal((await s.search({ text: 'unrelated', vector: [1, 0], model: 'test-v1' })).total, 0);
  assert.ok((await s.search({ text: 'old address' })).items.every(record => record.payload.text !== 'Fictional old address'));
  assert.equal((await s.search({ text: 'new address' })).items[0].revisionId, revised.revisionId);
  await assert.rejects(s.revise({ operationId: 'stale', recordId: original.recordId, expectedRevisionId: original.revisionId, patch: { payload: { text: 'Stale update' } } }), code('MEMORY_REVISION_CONFLICT'));
  await assert.rejects(s.setEmbedding({ recordId: original.recordId, expectedRevisionId: original.revisionId, vector: [1, 0], model: 'test-v1' }), code('MEMORY_SOURCE_CHANGED'));
});

test('embedding model fingerprint and state restrictions are applied before candidate top K', async t => {
  const f = await fixture(t), s = await f.open();
  const a = await s.record({ operationId: 'a', record: fact('Fictional apples') });
  await s.setEmbedding({ recordId: a.recordId, expectedRevisionId: a.revisionId, vector: [1, 0], model: 'fingerprint-A' });
  assert.equal((await s.search({ text: 'semantic-only', vector: [1, 0], model: 'fingerprint-B' })).total, 0);
  assert.equal((await s.search({ text: 'semantic-only', vector: [1, 0], model: 'fingerprint-A' })).total, 1);
  await assert.rejects(s.search({ text: 'semantic-only', vector: [1, 0, 0], model: 'fingerprint-A' }), code('MEMORY_INVALID_VECTOR'));
  await assert.rejects(s.setEmbedding({ recordId: a.recordId, expectedRevisionId: a.revisionId, vector: [0, 0], model: 'fingerprint-A' }), code('MEMORY_INVALID_VECTOR'));
  assert.equal((await s.pendingEmbeddings({ model: 'fingerprint-A' })).items.length, 0);
  assert.equal((await s.pendingEmbeddings({ model: 'fingerprint-B' })).items[0].recordId, a.recordId);
  await s.suppress({ operationId: 'stop-use', recordId: a.recordId, expectedRevisionId: a.revisionId });
  assert.equal((await s.search({ text: 'apples', vector: [1, 0], model: 'fingerprint-A' })).total, 0);
  assert.equal((await s.pendingEmbeddings({ model: 'fingerprint-A' })).items.length, 0);
});

test('correction removes exact old FTS tokens and respects fact validity windows', async t => {
  const f = await fixture(t), s = await f.open();
  const original = await s.record({ operationId: 'old', record: fact('Fictional pineapple preference') });
  await s.revise({ operationId: 'replace', recordId: original.recordId, expectedRevisionId: original.revisionId, patch: { payload: { text: 'Fictional cucumber preference' } } });
  assert.equal((await s.search({ text: 'pineapple' })).total, 0);
  assert.equal((await s.search({ text: 'cucumber' })).total, 1);
  await s.record({ operationId: 'past', record: { ...fact('Fictional ancientvalue', 'im:alpha:past'), validUntil: '2000-01-01T00:00:00Z' } });
  await s.record({ operationId: 'future', record: { ...fact('Fictional futurevalue', 'im:alpha:future'), validFrom: '2999-01-01T00:00:00Z' } });
  assert.equal((await s.search({ text: 'ancientvalue' })).total, 0);
  assert.equal((await s.search({ text: 'futurevalue' })).total, 0);
});

test('non-destructive suppression retains body and evidence and rejects delayed extraction', async t => {
  const f = await fixture(t), s = await f.open();
  const evidence = [{ sourceRef: 'im:alpha:one', text: 'Fictional source message', occurredAt: '2026-01-01T00:00:00Z', role: 'user' }];
  const item = await s.record({ operationId: 'write', record: fact('Fictional memory'), evidence });
  const stopped = await s.suppress({ operationId: 'suppress', recordId: item.recordId, expectedRevisionId: item.revisionId });
  assert.deepEqual(stopped.suppressedSources, ['im:alpha:one']);
  assert.equal((await s.get({ recordId: item.recordId })).payload.text, 'Fictional memory');
  assert.deepEqual((await s.get({ recordId: item.recordId })).evidence, evidence);
  assert.equal((await s.query()).total, 0);
  await assert.rejects(s.record({ operationId: 'late', record: fact('Late model extraction') }), code('MEMORY_SOURCE_SUPPRESSED'));
  assert.equal((await s.suppress({ operationId: 'suppress', recordId: item.recordId, expectedRevisionId: item.revisionId })).duplicate, true);
});

test('permanent forget clears revisions, shared evidence, traces and vector entries', async t => {
  const f = await fixture(t), s = await f.open();
  const evidence = [{ sourceRef: 'im:alpha:one', text: 'Fictional secret event and a second fact', occurredAt: '2026-01-01T00:00:00Z', role: 'user' }];
  const original = await s.record({ operationId: 'first', record: fact('Fictional secret event'), evidence });
  const sibling = await s.record({ operationId: 'second', record: fact('Fictional second fact'), evidence });
  await s.setEmbedding({ recordId: original.recordId, expectedRevisionId: original.revisionId, vector: [0, 1], model: 'test-v1' });
  const result = await s.forget({ operationId: 'delete', recordId: original.recordId, expectedRevisionId: original.revisionId });
  assert.equal(result.affectedCount, 2);
  assert.equal((await s.get({ recordId: sibling.recordId })).payload, null);
  assert.deepEqual((await s.get({ recordId: sibling.recordId })).evidence, []);
  const duplicate = await s.forget({ operationId: 'delete', recordId: original.recordId, expectedRevisionId: original.revisionId });
  assert.equal(duplicate.duplicate, true);
  await assert.rejects(s.record({ operationId: 'late', record: fact('Late reconstruction') }), code('MEMORY_SOURCE_FORGOTTEN'));
  await f.close(s);
  const db = new DatabaseSync(join(f.root, 'alpha/memory.sqlite'));
  try {
    assert.equal(db.prepare('SELECT count(*) n FROM traces').get().n, 0);
    assert.equal(db.prepare('SELECT count(*) n FROM droy_embeddings').get().n, 0);
    for (const row of db.prepare('SELECT data FROM revisions').all()) { assert.equal(JSON.parse(row.data).payload, null); assert.deepEqual(JSON.parse(row.data).evidence, []); }
  } finally { db.close(); }
});

test('evidence snapshots merge on reconfirmation and reject cross-owner, changed and credential-bearing sources', async t => {
  const f = await fixture(t), s = await f.open();
  const first = { sourceRef: 'im:alpha:one', text: 'Fictional first source', occurredAt: '2026-01-01T00:00:00Z', role: 'user' };
  const second = { sourceRef: 'im:alpha:two', text: 'Fictional second source', occurredAt: '2026-01-02T00:00:00Z', role: 'user' };
  const item = await s.record({ operationId: 'first', record: fact('Fictional preference'), evidence: [first] });
  const revised = await s.revise({ operationId: 'confirm', recordId: item.recordId, expectedRevisionId: item.revisionId, patch: { sourceRefs: [first.sourceRef, second.sourceRef] }, evidence: [second] });
  assert.deepEqual((await s.get({ recordId: item.recordId })).evidence, [first, second]);
  await assert.rejects(s.revise({ operationId: 'bad-edit', recordId: item.recordId, expectedRevisionId: revised.revisionId, patch: {}, evidence: [{ ...first, text: 'Changed source' }] }), code('MEMORY_EVIDENCE_CONFLICT'));
  await assert.rejects(s.record({ operationId: 'cross', record: fact('Foreign fact', 'im:beta:one') }), code('COMPANION_SOURCE_SCOPE'));
  await assert.rejects(s.record({ operationId: 'credential', record: fact('Safe derived text'), evidence: [{ ...first, text: 'password: fictional-only' }] }), code('COMPANION_MEMORY_CREDENTIAL'));
});

test('legacy in-place migration preserves identity, revisions, operation receipts and forgotten sources', async t => {
  const f = await fixture(t), path = join(f.root, 'alpha');
  const legacy = await MemoryStore.open({ directory: path, initialize: true });
  const args = { operationId: 'keep', record: fact('Fictional retained old fact') }, kept = await legacy.record(args);
  const erased = await legacy.record({ operationId: 'erase', record: fact('Fictional forgotten fact', 'im:alpha:erased') });
  await legacy.forget({ operationId: 'forget-old', recordId: erased.recordId, expectedRevisionId: erased.revisionId });
  const oldStatus = await legacy.status(); await legacy.close();
  const migrated = await f.open();
  assert.equal((await migrated.get({ recordId: kept.recordId })).revisionId, kept.revisionId);
  assert.equal((await migrated.get({ recordId: kept.recordId })).payload.text, 'Fictional retained old fact');
  assert.equal((await migrated.get({ recordId: erased.recordId })).payload, null);
  assert.equal((await migrated.record({ ...args, evidence: [] })).duplicate, true);
  assert.equal((await migrated.status()).nodeId, oldStatus.nodeId);
  await assert.rejects(migrated.record({ operationId: 'resurrect', record: fact('Late old source', 'im:alpha:erased') }), code('MEMORY_SOURCE_FORGOTTEN'));
  assert.equal((await migrated.search({ text: 'retained' })).items[0].recordId, kept.recordId);
  await f.close(migrated);
  await assert.rejects(MemoryStore.open({ directory: path }), code('MEMORY_FORMAT_UNSUPPORTED'));
  await assert.rejects(f.open('alpha', { memorySpaceId: 'different-space' }), code('MEMORY_SPACE_MISMATCH'));
});

test('exclusive owner, idempotency conflicts and stale pagination are enforced', async t => {
  const f = await fixture(t), s = await f.open();
  await assert.rejects(f.open(), code('MEMORY_STORE_IN_USE'));
  await s.record({ operationId: 'one', record: fact('Fictional one') });
  await assert.rejects(s.record({ operationId: 'one', record: fact('Fictional changed') }), code('MEMORY_OPERATION_CONFLICT'));
  await s.record({ operationId: 'two', record: fact('Fictional two', 'im:alpha:two') });
  const page = await s.query({ limit: 1 }); assert.ok(page.nextCursor);
  await s.record({ operationId: 'three', record: fact('Fictional three', 'im:alpha:three') });
  await assert.rejects(s.query({ limit: 1, cursor: page.nextCursor }), code('MEMORY_CURSOR_STALE'));
});

test('large exact evidence stays available for audit without exhausting retrieval result budgets', async t => {
  const f = await fixture(t), s = await f.open();
  const evidence = [{ sourceRef: 'im:alpha:one', text: 'Fictional source '.repeat(3000), occurredAt: '2026-01-01T00:00:00Z', role: 'user' }];
  const item = await s.record({ operationId: 'large-source', record: fact('Fictional concise fact'), evidence });
  const result = await s.search({ text: 'concise', maxBytes: 2048 });
  assert.equal(result.items[0].recordId, item.recordId);
  assert.equal(result.items[0].evidence, undefined);
  assert.equal(result.items[0].evidenceCount, 1);
  assert.equal((await s.get({ recordId: item.recordId })).evidence[0].text, evidence[0].text);
});
