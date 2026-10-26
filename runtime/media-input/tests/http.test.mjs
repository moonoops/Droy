import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, mkdir, writeFile, rm, symlink } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { createServer } from 'node:http';
import { createMediaHandler, openMediaItem } from '../http.mjs';

const importId = '00000000-0000-4000-8000-000000000001', itemId = '00000000-0000-4000-8000-000000000002';
async function fixture(t) {
  const root = await mkdtemp(join(tmpdir(), 'droy-media-'));
  const folder = join(root, importId); await mkdir(folder);
  await writeFile(join(folder, 'manifest.json'), JSON.stringify({ id: importId, items: [{ id: itemId, name: '<private>.txt', bytes: 3 }] }));
  await writeFile(join(folder, `${itemId}.blob`), 'abc');
  t.after(() => rm(root, { recursive: true, force: true }));
  return { root, folder };
}

test('serves only authenticated listed bytes with no executable content type', async t => {
  const { root } = await fixture(t);
  const handler = createMediaHandler({ root, authorize(req, res) { if (req.headers.authorization === 'test') return true; res.writeHead(401); res.end(); return false; } });
  const server = createServer(handler); await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  t.after(() => server.close());
  const address = `http://127.0.0.1:${server.address().port}/droy/media-input/${importId}/${itemId}`;
  assert.equal((await fetch(address)).status, 401);
  const response = await fetch(address, { headers: { Authorization: 'test' } });
  assert.equal(response.status, 200);
  assert.equal(await response.text(), 'abc');
  assert.equal(response.headers.get('content-type'), 'application/octet-stream');
  assert.equal(response.headers.get('x-content-type-options'), 'nosniff');
  assert.equal(response.headers.get('cache-control'), 'no-store');
  assert.equal((await fetch(address + 'x', { headers: { Authorization: 'test' } })).status, 404);
});

test('rejects invalid ids and objects absent from native manifest', async t => {
  const { root } = await fixture(t);
  await assert.rejects(openMediaItem(root, '..', itemId));
  await assert.rejects(openMediaItem(root, importId, '00000000-0000-4000-8000-000000000003'));
});

test('rejects symlinked payloads, manifests and batch directories', async t => {
  const { root, folder } = await fixture(t);
  const outside = join(root, 'outside'); await writeFile(outside, 'abc');
  await rm(join(folder, `${itemId}.blob`)); await symlink(outside, join(folder, `${itemId}.blob`));
  await assert.rejects(openMediaItem(root, importId, itemId));
  await rm(join(folder, `${itemId}.blob`)); await writeFile(join(folder, `${itemId}.blob`), 'abc');
  const manifest = join(folder, 'manifest.json'), saved = await import('node:fs/promises').then(fs => fs.readFile(manifest));
  await writeFile(outside, saved); await rm(manifest); await symlink(outside, manifest);
  await assert.rejects(openMediaItem(root, importId, itemId));
});

test('detects incomplete copied bytes and oversized manifests', async t => {
  const { root, folder } = await fixture(t);
  await writeFile(join(folder, `${itemId}.blob`), 'truncated');
  await assert.rejects(openMediaItem(root, importId, itemId), /完整/);
  await writeFile(join(folder, 'manifest.json'), ' '.repeat(512 * 1024 + 1));
  await assert.rejects(openMediaItem(root, importId, itemId), /无效/);
});

test('supports native bounded Chinese share text larger than 128 KiB', async t => {
  const { root, folder } = await fixture(t);
  await writeFile(join(folder, 'manifest.json'), JSON.stringify({ id: importId, text: '中文'.repeat(40000), items: [{ id: itemId, name: 'test.txt', bytes: 3 }] }));
  const source = await openMediaItem(root, importId, itemId);
  assert.equal(source.stat.size, 3);
  await source.handle.close();
});
