import test, { after } from 'node:test';
import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { createServer, request as httpRequest } from 'node:http';
import { EventEmitter, once } from 'node:events';
import { createRequire, registerHooks } from 'node:module';
import { pathToFileURL } from 'node:url';
import { mkdtemp, readdir, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { Readable } from 'node:stream';
import { requireMobileSession } from '../auth.mjs';

const installed = createRequire(new URL('../../dsh/package.json', import.meta.url));
const hooks = registerHooks({ resolve(specifier, context, next) {
  if (specifier.startsWith('@deepseek-ai/')) return { url: pathToFileURL(installed.resolve(specifier)).href, shortCircuit: true };
  if (context.parentURL === new URL('../attachments.mjs', import.meta.url).href && specifier === './publish.mjs')
    return { url: new URL('./fixtures/host-publish.mjs', import.meta.url).href, shortCircuit: true };
  return next(specifier, context);
} });
const { createNativeWorkbenchMedia } = await import('../native-workbench-media.mjs');
const { FileUploads } = await import('@deepseek-ai/dsh-client-file-upload');
const { TYPERT } = await import('@deepseek-ai/dsh-api-session-controller/typert');
const { Context } = await import('@deepseek-ai/cordis');
const { default: MobileAttachments } = await import('../attachments.mjs');
hooks.deregister();

const token = 'fictional-native-workbench-media-token-123456789';
const secret = 'fictional-private-upload-error-never-return';
const previousToken = process.env.DROY_WEB_TOKEN;
process.env.DROY_WEB_TOKEN = token;
after(() => { if (previousToken === undefined) delete process.env.DROY_WEB_TOKEN; else process.env.DROY_WEB_TOKEN = previousToken; });
const prefix = '/droy/native/uploads/files/';
const maxBytes = 20 * 1024 * 1024;
const payload = Buffer.from([0, 255, 17, 33, 10, 0, 128, 88]);
function* chunks(bytes) {
  const block = Buffer.alloc(64 * 1024, 117);
  while (bytes > 0) { const count = Math.min(bytes, block.length); yield block.subarray(0, count); bytes -= count; }
}
const safe = text => assert.ok(!text.includes(secret) && !text.includes('/private/fictional'), 'No private path, raw upstream error or key is returned');

async function fixture(t, options = {}) {
  const home = await mkdtemp(join(tmpdir(), 'droy-native-workbench-media-')), context = new Context();
  await context.plugin(MobileAttachments, { dshHome: home });
  const storage = context.attachments, calls = [], events = new EventEmitter(), agents = new Map(), headersById = new Map();
  for (const id of ['work-a', 'work-b', 'cold-session', 'managed-session', 'child-origin', 'child-depth', 'no-cwd', 'migrating']) {
    const header = { version: 1, id, createdAt: 1, isSeeded: false,
      ...(id === 'no-cwd' ? {} : { cwd: '/private/fictional/workspace' }),
      ...(id === 'child-origin' ? { origin: 'subagent' } : {}), ...(id === 'child-depth' ? { delegationDepth: 1 } : {}) };
    headersById.set(id, header);
    if (id !== 'cold-session') agents.set(id, { id, session: { id, header } });
  }
  let largestChunk = 0, receivedBytes = 0;
  const controller = options.controller ?? new AbortController();
  const ctx = {
    get(name) { return this[name]; },
    settings: { describe: () => [{ ns: 'locale', value: { preference: options.locale || 'en' } }] },
    droyCompanions: { ownsSession: id => id === 'managed-session' },
    sessionPersistence: { async stat(id, statOptions) {
      calls.push({ kind: 'stat', id, signal: statOptions.signal });
      if (options.statFailure) throw options.statFailure;
      await options.beforeStat?.();
      const header = headersById.get(id);
      return header && { header: structuredClone(header), revision: 'fictional-revision', ...(id === 'migrating' ? { formatStatus: 'migration-required' } : {}) };
    } },
    agents: { get: id => agents.get(id) },
    sessionController: { create() { assert.fail('Uploading must not create a Session'); }, prompt() { assert.fail('Uploading must not send a prompt'); } },
    attachments: {
      isAttachmentError: error => storage.isAttachmentError(error),
      async saveFileStream(input) {
        calls.push({ kind: 'save', signal: input.signal, name: input.name });
        if (options.saveFailure) throw options.saveFailure;
        const source = input.data;
        return storage.saveFileStream({ ...input, data: (async function* () {
          for await (const chunk of source) {
            largestChunk = Math.max(largestChunk, chunk.byteLength); receivedBytes += chunk.byteLength;
            events.emit('chunk', chunk.byteLength); yield chunk;
            await options.afterChunk?.();
          }
        })() });
      },
    },
  };
  // The real public upload service mints, binds and resolves receipts over the
  // real Droy streaming attachment store in an isolated temporary directory.
  const uploads = Object.create(FileUploads.prototype, { ctx: { value: ctx } });
  uploads.stagedFiles = new WeakMap();
  uploads.registerAgentResolver(async id => {
    calls.push({ kind: 'resume', id });
    if (!headersById.has(id)) throw Object.assign(new Error(secret), { code: 'session/not-found' });
    const agent = { id, session: { id, header: headersById.get(id) } }; agents.set(id, agent); return agent;
  });
  const uploadStream = uploads.uploadStream.bind(uploads);
  uploads.uploadStream = async input => {
    calls.push({ kind: 'upload', sessionId: input.sessionId, name: input.name, signal: input.signal });
    if (options.uploadFailure) throw options.uploadFailure;
    const value = await uploadStream(input);
    return options.afterUpload ? options.afterUpload(value) : value;
  };
  ctx.fileUploads = uploads;
  const api = createNativeWorkbenchMedia(ctx), incoming = [], unexpected = [];
  const server = createServer(async (req, res) => {
    if (!requireMobileSession(req, res)) return;
    incoming.push(req);
    const aborted = () => controller.abort();
    const closed = () => { if (!res.writableEnded) controller.abort(); };
    req.once('aborted', aborted); res.once('close', closed);
    try { await api.handle(req, res, controller.signal); }
    catch (error) { unexpected.push(error); res.destroy(error); }
    finally { req.removeListener('aborted', aborted); res.removeListener('close', closed); events.emit('complete'); }
  });
  server.listen(0, '127.0.0.1'); await once(server, 'listening');
  t.after(async () => {
    controller.abort(); server.closeAllConnections(); await new Promise(resolve => server.close(resolve));
    await context.fiber.dispose(); await rm(home, { recursive: true, force: true }); assert.deepEqual(unexpected, []);
  });
  const origin = `http://127.0.0.1:${server.address().port}`, headers = { Cookie: `droy_session=${token}`, 'Content-Type': 'application/octet-stream' };
  async function request(data = payload, { sessionId = 'work-a', name = 'fictional-report.bin', path, ...init } = {}) {
    const response = await fetch(origin + (path ?? prefix + encodeURIComponent(sessionId) + '?name=' + encodeURIComponent(name)), {
      method: 'POST', headers, ...(data === null ? {} : { body: data }), ...init,
    });
    const text = await response.text(); safe(text);
    return { status: response.status, headers: response.headers, value: text.startsWith('{') ? JSON.parse(text) : null, text };
  }
  return { ctx, api, storage, agents, uploads, calls, events, incoming, home, controller, request, origin, headers,
    get largestChunk() { return largestChunk; }, get receivedBytes() { return receivedBytes; } };
}
function mockResponse() {
  return { destroyed: false, writableEnded: false,
    writeHead(status, headers) { this.status = status; this.headers = headers; },
    end(text) { this.writableEnded = true; safe(text); this.value = JSON.parse(text); } };
}

test('shared authentication runs before any target lookup or storage work', async t => {
  const f = await fixture(t);
  assert.equal((await f.request(payload, { headers: {} })).status, 401);
  assert.equal((await f.request(payload, { headers: { ...f.headers, Origin: 'https://foreign.invalid' } })).status, 401);
  assert.equal(f.calls.length, 0);
});

test('raw bytes receive a real content-addressed staged receipt scoped to the exact receiving Session', async t => {
  const f = await fixture(t), response = await f.request(Readable.from([payload.subarray(0, 3), payload.subarray(3)]), { duplex: 'half', name: '研发说明.bin' });
  assert.equal(response.status, 200); assert.equal(response.headers.get('cache-control'), 'no-store');
  const value = response.value.result; assert.equal(response.value.ok, true);
  assert.match(value.receiptId, /^[0-9a-f-]{36}$/); assert.equal(value.file.bytes, payload.length); assert.equal(value.file.name, '研发说明.bin');
  assert.equal(value.file.attachmentId, 'sha256:' + createHash('sha256').update(payload).digest('hex'));
  assert.deepEqual(f.uploads.resolve(f.agents.get('work-a'), value.receiptId), value.file);
  assert.equal(f.uploads.resolve(f.agents.get('work-b'), value.receiptId), undefined);
  assert.equal(f.uploads.resolve({ id: 'work-a', session: { id: 'work-a' } }, value.receiptId), undefined);
  const saved = []; for await (const chunk of f.storage.readFileStream(value.file)) saved.push(chunk);
  assert.deepEqual(Buffer.concat(saved), payload);
  assert.equal(f.calls.find(call => call.kind === 'upload').signal, f.controller.signal);
  const prompt = TYPERT.invocations.find(row => row.namespace === 'session' && row.method === 'prompt').input({ strict: true }).create();
  assert.doesNotThrow(() => prompt.parse({ request: { sessionId: 'work-a', requestId: 'fixture-send-1', mode: 'queue', content: [{ type: 'file', receiptId: value.receiptId }] } }));
  assert.throws(() => prompt.parse({ request: { sessionId: 'work-a', requestId: 'fixture-send-2', mode: 'queue', content: [{ type: 'file', file: value.file }] } }));
});

test('uploading to an already existing cold Session preserves public resume semantics without creating or sending', async t => {
  const f = await fixture(t), response = await f.request(payload, { sessionId: 'cold-session' });
  assert.equal(response.status, 200);
  assert.deepEqual(f.calls.filter(call => call.kind === 'resume'), [{ kind: 'resume', id: 'cold-session' }]);
  assert.deepEqual(f.uploads.resolve(f.agents.get('cold-session'), response.value.result.receiptId), response.value.result.file);
  assert.equal((await f.request(payload, { sessionId: 'unknown-new-draft' })).value.error.code, 'NATIVE_UPLOAD_SESSION_NOT_FOUND');
  assert.equal(f.calls.filter(call => call.kind === 'resume').length, 1);
});

test('an opaque Session identity is decoded once from its single encoded path segment', async t => {
  const f = await fixture(t), id = 'fixture/work session';
  const header = { version: 1, id, createdAt: 1, isSeeded: false, cwd: '/fictional/work' };
  f.agents.set(id, { id, session: { id, header } });
  const stat = f.ctx.sessionPersistence.stat;
  f.ctx.sessionPersistence.stat = (sessionId, options) => sessionId === id ? Promise.resolve({ header, revision: 'fixture' }) : stat(sessionId, options);
  const response = await f.request(payload, { sessionId: id }); assert.equal(response.status, 200);
  assert.equal(f.calls.find(call => call.kind === 'upload').sessionId, id);
  assert.deepEqual(f.uploads.resolve(f.agents.get(id), response.value.result.receiptId), response.value.result.file);
});

test('managed, delegated, unknown and unsupported targets are refused before upload side effects', async t => {
  const f = await fixture(t);
  for (const [sessionId, status, code] of [['managed-session', 409, 'COMPANION_SESSION_MANAGED'],
    ['child-origin', 422, 'subagent/attachment-invalid'], ['child-depth', 422, 'subagent/attachment-invalid'],
    ['unknown', 404, 'NATIVE_UPLOAD_SESSION_NOT_FOUND'], ['no-cwd', 409, 'NATIVE_UPLOAD_TARGET_UNAVAILABLE'],
    ['migrating', 409, 'NATIVE_UPLOAD_TARGET_UNAVAILABLE']]) {
    const response = await f.request(payload, { sessionId }); assert.equal(response.status, status); assert.equal(response.value.error.code, code);
  }
  f.ctx.sessionPersistence.stat = async () => { throw new Error('/private/fictional ' + secret); };
  assert.equal((await f.request()).value.error.code, 'NATIVE_UPLOAD_FAILED');
  f.ctx.sessionPersistence.stat = async () => ({ header: { id: 'wrong-target', cwd: '/fictional' } });
  assert.equal((await f.request()).status, 503);
  delete f.ctx.droyCompanions.ownsSession; assert.equal((await f.request()).status, 503);
  assert.equal(f.calls.some(call => call.kind === 'upload' || call.kind === 'save' || call.kind === 'resume'), false);
});

test('exactly 20MiB passes through bounded chunks and uses the real storage byte receipt', async t => {
  const f = await fixture(t), response = await f.request(Readable.from(chunks(maxBytes)), { duplex: 'half' });
  assert.equal(response.status, 200); assert.equal(response.value.result.file.bytes, maxBytes);
  assert.equal(f.receivedBytes, maxBytes); assert.ok(f.largestChunk <= 64 * 1024);
  const digest = createHash('sha256'); for (const chunk of chunks(maxBytes)) digest.update(chunk);
  assert.equal(response.value.result.file.attachmentId, 'sha256:' + digest.digest('hex'));
  assert.deepEqual(await readdir(join(f.storage.root, 'tmp')), []);
});

test('a chunked upload over 20MiB returns 413 after actual counting and removes the partial file', async t => {
  const f = await fixture(t), response = await f.request(Readable.from(chunks(maxBytes + 1)), { duplex: 'half' });
  assert.equal(response.status, 413); assert.equal(response.value.error.code, 'NATIVE_UPLOAD_TOO_LARGE');
  assert.equal(response.value.result, undefined); assert.ok(f.receivedBytes <= maxBytes);
  assert.deepEqual(await readdir(join(f.storage.root, 'tmp')), []);
});

test('actual stream counting remains authoritative with a dishonest small Content-Length', async t => {
  const f = await fixture(t), req = Readable.from(chunks(maxBytes + 1), { objectMode: false }), res = mockResponse();
  Object.assign(req, { method: 'POST', url: prefix + 'work-a?name=fictional.bin', headers: { host: '127.0.0.1', 'content-type': 'application/octet-stream', 'content-length': '1' } });
  await f.api.handle(req, res, f.controller.signal);
  assert.equal(res.status, 413); assert.equal(res.value.error.code, 'NATIVE_UPLOAD_TOO_LARGE');
  assert.deepEqual(await readdir(join(f.storage.root, 'tmp')), []);
});

test('an oversized declared length fails before target or storage access', async t => {
  const f = await fixture(t), url = new URL(f.origin);
  const response = await new Promise((resolve, reject) => {
    const req = httpRequest({ hostname: url.hostname, port: url.port, path: prefix + 'work-a?name=large.bin', method: 'POST',
      headers: { ...f.headers, 'Content-Length': String(maxBytes + 1) } }, res => {
      let text = ''; res.setEncoding('utf8'); res.on('data', chunk => { text += chunk; });
      res.on('end', () => { safe(text); resolve({ status: res.statusCode, value: JSON.parse(text) }); });
    });
    req.on('error', reject); req.end();
  });
  assert.equal(response.status, 413); assert.equal(response.value.error.code, 'NATIVE_UPLOAD_TOO_LARGE'); assert.equal(f.calls.length, 0);
});

test('empty files keep their genuine zero-byte receipt', async t => {
  const f = await fixture(t), response = await f.request(Buffer.alloc(0));
  assert.equal(response.status, 200); assert.equal(response.value.result.file.bytes, 0);
  assert.equal(response.value.result.file.attachmentId, 'sha256:' + createHash('sha256').digest('hex'));
});

test('strict route, method, name and raw-media framing reject invalid requests before side effects', async t => {
  const f = await fixture(t);
  for (const [path, options, status] of [[prefix + 'work-a?name=ok.bin', { method: 'GET' }, 405],
    ['/droy/native/uploads/images/work-a?name=x', {}, 404], [prefix + 'work-a', {}, 400],
    [prefix + 'work-a?name=', {}, 400], [prefix + 'work-a?name=..', {}, 400], [prefix + 'work-a?name=%2Fprivate%2Fx', {}, 400],
    [prefix + 'work-a?name=C%3A%5Cprivate%5Cx', {}, 400], [prefix + 'work-a?name=x%00.bin', {}, 400],
    [prefix + 'work-a?name=%E0%A4', {}, 400], [prefix + '%E0%A4?name=x', {}, 400],
    [prefix + 'work-a/extra?name=x', {}, 400], [prefix + 'work-a?name=x&name=y', {}, 400],
    [prefix + 'work-a?name=x&apiKey=' + secret, {}, 400],
    [prefix + 'work-a?name=x', { headers: { ...f.headers, 'Content-Type': 'application/json' } }, 415],
    [prefix + 'work-a?name=x', { headers: { ...f.headers, 'Content-Type': 'multipart/form-data' } }, 415],
    [prefix + 'work-a?name=x', { headers: { ...f.headers, 'Content-Encoding': 'gzip' } }, 415]]) {
    const response = await f.request(options.method === 'GET' ? null : payload, { path, ...options }); assert.equal(response.status, status, path);
  }
  assert.equal(f.calls.length, 0);
});

test('cancellation before entry or during target observation prevents upload and cold resume', async t => {
  const first = await fixture(t); first.controller.abort(new Error(secret));
  assert.equal((await first.request()).value.error.code, 'NATIVE_UPLOAD_CANCELLED'); assert.equal(first.calls.length, 0);
  const entered = Promise.withResolvers(), release = Promise.withResolvers(); t.after(() => release.resolve());
  const second = await fixture(t, { beforeStat: async () => { entered.resolve(); await release.promise; } });
  const pending = second.request(payload, { sessionId: 'cold-session' }); await entered.promise;
  second.controller.abort(new Error(secret)); release.resolve();
  assert.equal((await pending).value.error.code, 'NATIVE_UPLOAD_CANCELLED');
  assert.equal(second.calls.some(call => call.kind === 'upload' || call.kind === 'resume'), false);
});

test('cancellation while awaiting more bytes settles promptly, cleans partial storage and releases request listeners', { timeout: 5000 }, async t => {
  const f = await fixture(t), address = new URL(f.origin), consumed = once(f.events, 'chunk'), complete = once(f.events, 'complete');
  let outgoing;
  const response = new Promise((resolve, reject) => {
    outgoing = httpRequest({ hostname: address.hostname, port: address.port, method: 'POST', path: prefix + 'work-a?name=partial.bin',
      headers: { ...f.headers, 'Transfer-Encoding': 'chunked' } }, res => {
      let text = ''; res.setEncoding('utf8'); res.on('data', chunk => { text += chunk; });
      res.on('end', () => { safe(text); resolve({ status: res.statusCode, value: JSON.parse(text) }); });
    });
    outgoing.on('error', reject); outgoing.write(Buffer.alloc(64 * 1024, 1));
  });
  t.after(() => outgoing?.destroy());
  await consumed; f.controller.abort(new Error(secret));
  const result = await response; await complete; outgoing.destroy();
  assert.equal(result.status, 409); assert.equal(result.value.error.code, 'NATIVE_UPLOAD_CANCELLED');
  assert.equal(f.incoming[0].listenerCount('readable'), 0);
  assert.deepEqual(await readdir(join(f.storage.root, 'tmp')), []);
});

test('a disconnected client releases the streamed intake and temporary storage', { timeout: 5000 }, async t => {
  const release = Promise.withResolvers(); t.after(() => release.resolve());
  const f = await fixture(t), client = new AbortController(), consumed = once(f.events, 'chunk'), complete = once(f.events, 'complete');
  async function* source() { yield Buffer.alloc(64 * 1024, 1); await release.promise; yield Buffer.alloc(64 * 1024, 2); }
  const pending = f.request(Readable.from(source()), { duplex: 'half', signal: client.signal }).then(value => ({ value }), error => ({ error }));
  await consumed; client.abort(); release.resolve();
  assert.ok((await pending).error); await complete;
  assert.equal(f.controller.signal.aborted, true); assert.equal(f.incoming[0].listenerCount('readable'), 0);
  assert.deepEqual(await readdir(join(f.storage.root, 'tmp')), []);
});

test('cancellation after a real staged commit does not turn its valid receipt into a rollback claim', async t => {
  let f;
  f = await fixture(t, { afterUpload(value) { f.controller.abort(new Error(secret)); return value; } });
  const response = await f.request(); assert.equal(response.status, 200); assert.equal(response.value.ok, true);
  assert.deepEqual(f.uploads.resolve(f.agents.get('work-a'), response.value.result.receiptId), response.value.result.file);
});

test('receipt boundaries expose only the public fields and reject unconsumed or mismatched confirmations', async t => {
  const extra = await fixture(t, { afterUpload(value) { return { ...value, privatePath: '/private/fictional/' + secret,
    file: { ...value.file, path: '/private/fictional/' + secret } }; } });
  const good = await extra.request(); assert.equal(good.status, 200);
  assert.deepEqual(Object.keys(good.value.result).sort(), ['file', 'receiptId']);
  assert.deepEqual(Object.keys(good.value.result.file).sort(), ['attachmentId', 'bytes', 'name']);
  const mismatch = await fixture(t, { afterUpload(value) { return { ...value, file: { ...value.file, bytes: value.file.bytes + 1 } }; } });
  assert.equal((await mismatch.request()).value.error.code, 'NATIVE_UPLOAD_FAILED');
  mismatch.ctx.fileUploads.uploadStream = async () => ({ receiptId: 'fictional-authority', file: { attachmentId: 'sha256:fictional', name: 'fictional.bin', bytes: 0 } });
  assert.equal((await mismatch.request()).value.error.code, 'NATIVE_UPLOAD_FAILED');
});

test('paired safe error copy contains neither raw upstream errors nor private paths', async t => {
  const messages = [];
  for (const locale of ['zh', 'en']) {
    const f = await fixture(t, { locale, saveFailure: Object.assign(new Error('/private/fictional ' + secret), { code: secret }) });
    const response = await f.request(); assert.equal(response.status, 500); assert.equal(response.value.error.code, 'NATIVE_UPLOAD_FAILED');
    messages.push(response.value.error.message);
    f.ctx.fileUploads.uploadStream = async () => { throw Object.assign(new Error('/private/fictional ' + secret), { code: 'session/attachment-invalid', details: { path: secret } }); };
    const invalid = await f.request(); assert.equal(invalid.status, 422); assert.equal(invalid.value.error.code, 'session/attachment-invalid');
    delete f.ctx.fileUploads; assert.equal((await f.request()).value.error.code, 'NATIVE_UPLOAD_NOT_READY');
  }
  assert.notEqual(messages[0], messages[1]);
});
