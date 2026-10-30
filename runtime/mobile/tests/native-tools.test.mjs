import test, { after } from 'node:test';
import assert from 'node:assert/strict';
import { createServer, request as httpRequest } from 'node:http';
import { once } from 'node:events';
import { createRequire, registerHooks } from 'node:module';
import { pathToFileURL } from 'node:url';
import { mkdtemp, rm, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { createHash } from 'node:crypto';
import { requireMobileSession } from '../auth.mjs';
import { encodePng } from '../png.mjs';

const installed = createRequire(new URL('../../dsh/package.json', import.meta.url));
const attachmentUrl = new URL('../attachments.mjs', import.meta.url).href;
const imports = registerHooks({ resolve(specifier, context, next) {
  if (specifier.startsWith('@deepseek-ai/')) return { url: pathToFileURL(installed.resolve(specifier)).href, shortCircuit: true };
  if (context.parentURL === attachmentUrl && specifier === './publish.mjs') return { url: new URL('./fixtures/host-publish.mjs', import.meta.url).href, shortCircuit: true };
  return next(specifier, context);
} });
const [{ createNativeTools }, { Context }, { Peer }, { TYPERT: FileApi }, { TYPERT: TerminalApi }, { TYPERT: OfficeApi }, { default: MobileAttachments }] = await Promise.all([
  import('../native-tools.mjs'), import('@deepseek-ai/cordis'), import('@deepseek-ai/dsh-client-connection'),
  import('@deepseek-ai/dsh-api-workspace-files/typert'), import('@deepseek-ai/dsh-api-terminal-controller/typert'),
  import('@deepseek-ai/dsh-office-to-pdf/typert'), import('../attachments.mjs'),
]);
const [{ default: TypertRegistry }, { default: TypertGateway }, { TypertRemoteService }] = await Promise.all([
  import('@deepseek-ai/dsh-typert-registry'), import('@deepseek-ai/dsh-api-gateway'), import('@deepseek-ai/dsh-typert-protocol'),
]);
imports.deregister();
const previous = process.env.DROY_WEB_TOKEN, token = 'fictional-native-tools-session-123456789'; process.env.DROY_WEB_TOKEN = token;
after(() => { if (previous === undefined) delete process.env.DROY_WEB_TOKEN; else process.env.DROY_WEB_TOKEN = previous; });
const secret = '/fictional/private/credential-never-return';
const header = id => ({ version: 1, id, createdAt: 1, isSeeded: false, cwd: '/fictional/session/' + id });
const info = { id: 'terminal-a', title: 'Fictional shell', shell: { path: '/fixture/sh', args: [], name: 'Fixture' },
  cwd: '/fictional/session/work-a', cols: 80, rows: 24, state: 'running', exitCode: null };
class Queue {
  values = []; waiters = []; ended = false;
  push(value) { if (this.waiters.length) this.waiters.shift()({ value, done: false }); else this.values.push(value); }
  next() { if (this.values.length) return Promise.resolve({ value: this.values.shift(), done: false }); if (this.ended) return Promise.resolve({ done: true }); return new Promise(resolve => this.waiters.push(resolve)); }
  return() { this.ended = true; this.values = []; for (const wake of this.waiters.splice(0)) wake({ done: true }); return Promise.resolve({ done: true }); }
  [Symbol.asyncIterator]() { return this; }
}
async function fixture(t, options = {}) {
  const ctx = new Context(), calls = [], streams = [], commands = [];
  if (options.realAttachments) {
    const directory = await mkdtemp(join(tmpdir(), 'droy-native-tools-'));
    await ctx.plugin(MobileAttachments, { dshHome: directory });
    t.after(async () => { await rm(directory, { recursive: true, force: true }); });
  } else if (options.attachments) ctx.provide('attachments', options.attachments);
  if (options.office) ctx.provide('officeToPdf', options.office);
  ctx.provide('settings', { describe: () => [{ ns: 'locale', value: { preference: options.locale ?? 'en' } }] });
  ctx.provide('droyCompanions', { ownsSession: id => id === 'managed' });
  ctx.provide('sessions', { get() {} });
  ctx.provide('sessionPersistence', { async stat(id, { signal }) {
    signal?.throwIfAborted(); calls.push({ type: 'stat', id });
    if (options.statFailure) throw options.statFailure;
    return ['work-a', 'managed', 'child'].includes(id) ? { header: { ...header(id), ...(id === 'child' ? { delegationDepth: 1 } : {}) } } : undefined;
  } });
  const records = [
    { sessionId: 'work-a', seq: 3, type: 'deliverables/presented', time: 1 },
    { sessionId: 'work-a', seq: 5, type: 'workspace/changes', time: 2 },
    { sessionId: 'work-a', seq: 8, type: 'assistant/message', time: 3 },
  ];
  ctx.provide('sessionQuery', {
    async listEvents(id) { calls.push({ type: 'events', id }); return records; },
    async readEvent(request, signal) {
      signal?.throwIfAborted(); calls.push({ type: 'event', request });
      if (options.readEvent) return options.readEvent(request, signal);
      return { session: header(request.sessionId), target: { seq: request.seq,
        type: request.seq === 3 ? 'deliverables/presented' : 'workspace/changes',
        data: request.seq === 3 ? { turn: 1, callId: 'fixture-call', files: [{ path: 'report.md', description: 'Fictional report' }] } : { turn: 2 } } };
    },
  });
  ctx.provide('workspaceChanges', {
    summary(id, seq) { calls.push({ type: 'summary', id, seq }); return seq === 5 ? { turn: 2, cwd: secret, snapshot: { before: 'private-before', after: 'private-after' },
      files: [{ path: 'report.md', display: 'report.md', added: 2, deleted: 1 }], total: 4, added: 10, deleted: 5 } : undefined; },
    async diff(id, seq, index, signal) { signal?.throwIfAborted(); calls.push({ type: 'diff', id, seq, index }); return seq === 5 && index === 0
      ? { kind: 'text', path: 'report.md', display: 'report.md', before: true, after: true, coarse: false,
        hunks: [{ oldStart: 1, oldLines: 1, newStart: 1, newLines: 2, lines: ['-old', '+new', '+next'] }] } : undefined; },
  });
  const descriptors = new Map([...FileApi.invocations, ...TerminalApi.invocations, ...OfficeApi.invocations].map(row => [row.namespace + '/' + row.method, row]));
  const gatewayStub = {
    async invoke(request) {
      const { namespace, method, args, signal, peer } = request;
      signal?.throwIfAborted(); assert.ok(peer instanceof Peer); assert.equal(peer.principal.kind, 'owner'); assert.equal(peer.interactions, 'none');
      descriptors.get(namespace + '/' + method).input({ strict: true }).create().parse(args);
      calls.push({ type: 'invoke', namespace, method, args, peer });
      if (options.failure) throw options.failure;
      if (namespace === 'officeToPdf') return { absolutePath: '/fictional/server-root/report.docx', version: 'office-v1', bytes: 400,
        offset: 0, eof: true, data: Buffer.from('%PDF-fixture'), missingFonts: ['Fixture Font'], generation: 'fixture-converter-generation' };
      if (namespace === 'workspaceFiles') {
        if (method === 'list') return { path: args.path, entries: [{ name: 'report.md', type: 'file', size: 4 }], truncated: true };
        const stat = { absolutePath: '/fictional/server-root/report.md', version: 'opaque-v1', bytes: 4 };
        if (method === 'read') return { ...stat, text: 'test', offset: args.range.offset ?? 1, lines: 1, eof: true };
        if (method === 'readBytes') return { ...stat, offset: 0, eof: true, data: options.largeBytes ? new Uint8Array(8 * 1024 * 1024 + 1) : Uint8Array.of(0, 1, 250, 255) };
        return stat;
      }
      if (method === 'list') return [info];
      if (method === 'environment') return { cwd: info.cwd, maxInputBytes: 32, maxBufferedBytes: options.buffer ?? 256, maxCols: 240, maxRows: 80, scrollback: 1000 };
      if (method === 'shells') return [info.shell];
      if (method === 'create') { options.afterCommit?.(); return { ...info, id: args.request.id, cols: args.request.cols, rows: args.request.rows }; }
      return undefined;
    },
    async stream(request) {
      const { namespace, method, args, peer } = request;
      assert.ok(peer instanceof Peer); assert.equal(peer.principal.kind, 'owner');
      descriptors.get(namespace + '/' + method).input({ strict: true }).create().parse(args);
      calls.push({ type: 'stream', namespace, method, args, peer });
      if (options.streamFailure) throw options.streamFailure;
      const signal = request.signal ? AbortSignal.any([request.signal, peer.signal]) : peer.signal;
      const queue = new Queue(), stream = { queue, request, finished: false }; streams.push(stream);
      const stop = () => { queue.return(); request.uplink?.return?.(); };
      signal.addEventListener('abort', stop, { once: true });
      return (async function* () {
        let input;
        try {
          if (namespace === 'workspaceFiles') { yield { kind: 'ready' }; yield* queue; return; }
          yield { type: 'snapshot', sequence: 10, screen: '\u001b[2Jfixture', info, writable: true, inputWindow: request.uplinkWindow };
          input = (async () => { for await (const command of request.uplink) {
            commands.push(command); if (!options.noAck) queue.push({ type: 'ack', sequence: command.sequence });
          } })();
          yield* queue;
        } finally { stop(); await input; stream.finished = true; signal.removeEventListener('abort', stop); }
      })();
    },
  };
  if (!options.realGateway) ctx.provide('typertGateway', gatewayStub);
  else {
    await ctx.plugin(TypertRegistry);
    ctx.provide('remoteAccess', { decide() { assert.fail('Only the authenticated owner fixture is admitted'); } });
    ctx.typert.register(FileApi); ctx.typert.register(TerminalApi); ctx.typert.register(OfficeApi);
    ctx.typert.lookups.register('agent', { parameter: 'agent', wire: 'agentId',
      hostTypeSymbol: '@deepseek-ai/dsh-agent#Agent', wireTypeSymbol: '@deepseek-ai/dsh-session/types#SessionId',
      resolve: id => ({ id }) });
    ctx.typert.lookups.register('workspaceFileScope', { parameter: 'workspaceFileScope', wire: 'workspaceFileScopeId',
      hostTypeSymbol: '@deepseek-ai/dsh-api-workspace-files#WorkspaceFileScope', wireTypeSymbol: '@deepseek-ai/dsh-session/types#SessionId',
      async resolve(sessionId) {
        const value = await ctx.sessionPersistence.stat(sessionId, {});
        return value ? { sessionId, workspaceRoot: value.header.cwd } : undefined;
      } });
    class Files extends TypertRemoteService {
      constructor(scope) { super(scope, 'workspaceFiles'); }
      list(scope, path, signal) {
        assert.equal(scope.workspaceRoot, '/fictional/session/' + scope.sessionId);
        assert.ok(this.ctx.invocation.peer instanceof Peer);
        return gatewayStub.invoke({ namespace: 'workspaceFiles', method: 'list', args: { workspaceFileScopeId: scope.sessionId, path }, signal, peer: this.ctx.invocation.peer });
      }
    }
    class Terminal extends TypertRemoteService {
      constructor(scope) { super(scope, 'terminalController', { namespace: 'terminal' }); }
      list(sessionId) { return gatewayStub.invoke({ namespace: 'terminal', method: 'list', args: { sessionId }, peer: this.ctx.invocation.peer }); }
      environment(agent, signal) { return gatewayStub.invoke({ namespace: 'terminal', method: 'environment', args: { agentId: agent.id }, signal, peer: this.ctx.invocation.peer }); }
      attach(agent, id, signal) {
        const invocation = this.ctx.invocation;
        assert.ok(invocation, 'The real Gateway must supply the Remote invocation');
        assert.equal(invocation.request.namespace, 'terminal'); assert.equal(invocation.request.method, 'attach');
        const uplink = invocation.uplink();
        assert.throws(() => invocation.uplink(), /once/);
        return (async function* () { yield* await gatewayStub.stream({ namespace: 'terminal', method: 'attach', args: { agentId: agent.id, id }, signal,
          peer: invocation.peer, uplink, uplinkWindow: invocation.uplinkWindow }); })();
      }
    }
    class Office extends TypertRemoteService {
      constructor(scope) { super(scope, 'officeToPdf'); }
      render(scope, path, priority, signal) {
        assert.equal(scope.workspaceRoot, '/fictional/session/' + scope.sessionId);
        return gatewayStub.invoke({ namespace: 'officeToPdf', method: 'render', args: { workspaceFileScopeId: scope.sessionId, path, priority }, signal, peer: this.ctx.invocation.peer });
      }
    }
    new Files(ctx); new Terminal(ctx); new Office(ctx);
    await ctx.plugin(TypertGateway, { websocketHeartbeatIntervalMs: 2000, streamInboxBytes: 262144 });
  }
  const api = createNativeTools(ctx), server = createServer(async (req, res) => {
    if (!requireMobileSession(req, res)) return;
    const abort = options.requestAbort ?? new AbortController(); const disconnect = () => { if (!res.writableEnded) abort.abort(); };
    res.once('close', disconnect);
    try { await api.handle(req, res, abort.signal); } finally { res.removeListener('close', disconnect); }
  });
  server.listen(0, '127.0.0.1'); await once(server, 'listening');
  t.after(async () => { server.closeAllConnections(); await api.dispose(); await new Promise(resolve => server.close(resolve)); await ctx.fiber.dispose(); });
  const url = `http://127.0.0.1:${server.address().port}/droy/native/tools`, headers = { Cookie: 'droy_session=' + token, 'Content-Type': 'application/json' };
  async function request(path, input, extra = {}) {
    const response = await fetch(url + path, { headers, method: input === undefined ? 'GET' : 'POST', ...(input === undefined ? {} : { body: JSON.stringify(input) }), ...extra });
    const text = await response.text(); assert.ok(!text.includes(secret)); return { status: response.status, value: text.startsWith('{') ? JSON.parse(text) : text };
  }
  async function follow(path) {
    const abort = new AbortController(); t.after(() => abort.abort());
    const response = await fetch(url + path, { headers, signal: abort.signal }); assert.equal(response.status, 200);
    const reader = response.body.getReader(), decoder = new TextDecoder(); let text = '';
    return { response, abort, async next() {
      while (true) {
        const boundary = text.indexOf('\n\n');
        if (boundary >= 0) { const event = text.slice(0, boundary); text = text.slice(boundary + 2); if (event.startsWith('data: ')) return JSON.parse(event.slice(6)); }
        else { const { value, done } = await reader.read(); if (done) return null; text += decoder.decode(value, { stream: true }); }
      }
    } };
  }
  const begin = async () => {
    const opened = await request('/terminal/attachment', { sessionId: 'work-a', id: info.id }); assert.equal(opened.status, 200);
    const id = opened.value.result.attachmentId, stream = await follow('/terminal/attachment/' + id);
    assert.equal((await stream.next()).type, 'native/attached'); assert.equal((await stream.next()).type, 'snapshot');
    return { ...stream, id, prefix: '/terminal/attachment/' + id };
  };
  return { ctx, calls, streams, commands, request, follow, begin, url, headers, api };
}

test('authentication precedes tools, scopes, peers and terminal allocation', async t => {
  const f = await fixture(t);
  assert.equal((await f.request('/files/list', { sessionId: 'work-a', path: '' }, { headers: {} })).status, 401);
  assert.equal((await f.request('/terminal/create', {}, { headers: { ...f.headers, Origin: 'https://foreign.invalid' } })).status, 401);
  assert.equal(f.calls.length, 0);
});
test('file requests use the public lookup identity and preserve truncation, versions and exact byte windows', async t => {
  const f = await fixture(t), input = { sessionId: 'work-a', path: 'nested' };
  const listing = await f.request('/files/list', input); assert.equal(listing.status, 200); assert.equal(listing.value.result.truncated, true);
  assert.deepEqual(f.calls.at(-1).args, { workspaceFileScopeId: 'work-a', path: 'nested' }); assert.equal(f.calls.at(-1).peer.signal.aborted, true);
  const text = await f.request('/files/text', { ...input, range: { offset: 2, limit: 30 } }); assert.equal(text.value.result.offset, 2); assert.equal(text.value.result.eof, true);
  const bytes = await f.request('/files/bytes', { ...input, options: { range: { offset: 0, length: 4 }, baseFile: 'nested/report.md' } });
  assert.equal(bytes.value.result.version, 'opaque-v1'); assert.equal(bytes.value.result.encoding, 'base64');
  assert.deepEqual([...Buffer.from(bytes.value.result.data, 'base64')], [0, 1, 250, 255]);
  for (const value of [{ ...input, workspaceRoot: '/' }, { ...input, root: '/' }, { ...input, range: { offset: 0 } }, { ...input, range: null }])
    assert.equal((await f.request('/files/text', value)).status, 400);
  assert.equal((await f.request('/files/rename', input)).status, 404);
});
test('content reads exact durable declarations, pages their real coordinates and leaves historical diff unavailable', async t => {
  const f = await fixture(t), first = await f.request('/content', { sessionId: 'work-a', limit: 1 });
  assert.deepEqual(first.value.result, { entries: [{ seq: 5, type: 'workspace/changes', turn: 2 }], hasMore: true, nextBefore: 5 });
  const second = await f.request('/content', { sessionId: 'work-a', beforeSeq: 5 });
  assert.deepEqual(second.value.result.entries[0].files, [{ index: 0, path: 'report.md', description: 'Fictional report' }]);
  const summary = await f.request('/changes/summary', { sessionId: 'work-a', seq: 5 });
  assert.equal(summary.value.result.total, 4); assert.equal('cwd' in summary.value.result, false); assert.equal('snapshot' in summary.value.result, false);
  assert.equal((await f.request('/changes/diff', { sessionId: 'work-a', seq: 5, index: 0 })).value.result.kind, 'text');
  assert.equal((await f.request('/changes/summary', { sessionId: 'work-a', seq: 99 })).value.error.code, 'NATIVE_TOOLS_DIFF_UNAVAILABLE');
  assert.ok(!f.calls.some(call => call.type === 'stat' || call.type === 'invoke'));
});
test('watch SSE forwards readiness and changes, cancelling only its public stream and peer', async t => {
  const f = await fixture(t), stream = await f.follow('/files/watch?sessionId=work-a&path=nested');
  assert.deepEqual(await stream.next(), { kind: 'ready' });
  f.streams[0].queue.push({ kind: 'change', change: { absolutePath: '/fictional/nested', version: 'opaque-v2' } });
  assert.equal((await stream.next()).change.version, 'opaque-v2'); stream.abort.abort();
  await new Promise(resolve => setTimeout(resolve, 10)); assert.equal(f.streams[0].request.peer.signal.aborted, true);
  assert.ok(!f.calls.some(call => call.method === 'close'));
});
test('terminal creation and attachment reject managed, delegated and unknown sessions before Gateway activation', async t => {
  const f = await fixture(t);
  for (const sessionId of ['managed', 'child', 'unknown']) {
    assert.ok([404, 409].includes((await f.request('/terminal/create', { sessionId, request: { id: 'new', cols: 80, rows: 24 } })).status));
    assert.ok([404, 409].includes((await f.request('/terminal/attachment', { sessionId, id: info.id })).status));
  }
  assert.ok(!f.calls.some(call => call.type === 'invoke'));
  const list = await f.request('/terminal/list', { sessionId: 'managed' }); assert.equal(list.status, 200);
});
test('one attachment carries real snapshot/output/state/ACK and refuses input replay or a second physical stream', async t => {
  const f = await fixture(t), stream = await f.begin();
  assert.equal((await f.request(stream.prefix + '/input', { type: 'write', sequence: 1, data: 'echo fixture\r' })).status, 202);
  assert.deepEqual(await stream.next(), { type: 'ack', sequence: 1 });
  assert.deepEqual(f.commands, [{ type: 'write', sequence: 1, data: 'echo fixture\r' }]);
  assert.equal((await f.request(stream.prefix + '/input', { type: 'write', sequence: 1, data: 'duplicate\r' })).status, 409);
  assert.equal((await f.request(stream.prefix + '/input', { type: 'resize', sequence: 2, cols: 100, rows: 30 })).status, 202);
  assert.equal((await stream.next()).sequence, 2);
  f.streams[0].queue.push({ type: 'output', sequence: 11, data: 'fixture output\r\n' }); assert.equal((await stream.next()).data, 'fixture output\r\n');
  f.streams[0].queue.push({ type: 'state', info, writable: false }); assert.equal((await stream.next()).writable, false);
  assert.equal((await f.request(stream.prefix)).status, 409);
  assert.equal((await f.request(stream.prefix + '/detach', {})).value.result.detached, true);
  assert.equal((await f.request(stream.prefix + '/input', { type: 'write', sequence: 3, data: 'late' })).status, 409);
  assert.ok(!f.calls.some(call => call.method === 'close'));
});
test('real Typert registry and Gateway provide a header-derived scope and single-consumer Remote invocation uplink', async t => {
  const f = await fixture(t, { realGateway: true });
  const files = await f.request('/files/list', { sessionId: 'work-a', path: 'nested' }); assert.equal(files.status, 200);
  const stream = await f.begin();
  assert.equal((await f.request(stream.prefix + '/input', { type: 'write', sequence: 1, data: 'fixture\r' })).status, 202);
  assert.deepEqual(await stream.next(), { type: 'ack', sequence: 1 });
  assert.equal(f.commands.length, 1); assert.equal(f.commands[0].data, 'fixture\r');
  await f.request(stream.prefix + '/detach', {});
  assert.ok(!f.calls.some(call => call.method === 'close'));
});
test('input remains bounded until provider ACK, HTTP acceptance does not release credits and reconnect never replays it', async t => {
  const f = await fixture(t, { noAck: true, buffer: 80 }), first = await f.begin();
  assert.equal((await f.request(first.prefix + '/input', { type: 'write', sequence: 1, data: 'a'.repeat(30) })).status, 202);
  assert.equal((await f.request(first.prefix + '/input', { type: 'write', sequence: 2, data: 'b'.repeat(30) })).value.error.code, 'NATIVE_TOOLS_INPUT_PENDING');
  await f.request(first.prefix + '/detach', {});
  const second = await f.begin(); assert.notEqual(first.id, second.id);
  assert.equal((await f.request(second.prefix + '/input', { type: 'write', sequence: 1, data: 'new' })).status, 202);
  assert.deepEqual(f.commands.map(command => command.data), ['a'.repeat(30), 'new']);
});
test('strict input and actual body caps reject malformed paths, extra fields, dimensions and oversized unknown-length JSON', async t => {
  const f = await fixture(t);
  assert.equal((await f.request('/files/list?root=%2F', {})).status, 400);
  assert.equal((await f.request('/files/watch?sessionId=work-a&sessionId=other&path=x')).status, 400);
  assert.equal((await f.request('/files/list')).status, 405);
  const stream = await f.begin();
  for (const input of [{ type: 'resize', sequence: 1, cols: 1, rows: 24 }, { type: 'write', sequence: 0, data: 'x' },
    { type: 'write', sequence: 1, data: 'x'.repeat(33) }, { type: 'write', sequence: 1, data: 'x', tabId: 'other' }])
    assert.ok([400, 413].includes((await f.request(stream.prefix + '/input', input)).status));
  const response = await new Promise((resolve, reject) => {
    const req = httpRequest(f.url + '/files/list', { method: 'POST', headers: f.headers }, res => { let value = ''; res.setEncoding('utf8'); res.on('data', part => value += part); res.on('end', () => resolve({ status: res.statusCode, value })); });
    req.on('error', reject); req.write(' '.repeat(256 * 1024 + 1)); req.end();
  });
  assert.equal(response.status, 413);
});
test('only paired safe errors cross tools and bytes larger than the preview bound fail without truncation', async t => {
  for (const locale of ['zh', 'en']) await t.test(locale, async child => {
    const f = await fixture(child, { locale, failure: Object.assign(new Error(secret), { code: secret }) });
    const result = await f.request('/files/list', { sessionId: 'work-a', path: '' });
    assert.equal(result.status, 500); assert.equal(result.value.error.code, 'NATIVE_TOOLS_FAILED');
    assert.match(result.value.error.message, locale === 'zh' ? /工具/ : /tool/);
  });
  const f = await fixture(t, { largeBytes: true });
  const result = await f.request('/files/bytes', { sessionId: 'work-a', path: 'large.bin' }); assert.equal(result.status, 413); assert.equal(result.value.ok, false);
});
test('pre-cancelled requests never allocate a peer or terminal', async t => {
  const abort = new AbortController(); abort.abort();
  const f = await fixture(t, { requestAbort: abort });
  const result = await f.request('/terminal/create', { sessionId: 'work-a', request: { id: 'never-created', cols: 80, rows: 24 } });
  assert.equal(result.status, 409); assert.equal(result.value.error.code, 'NATIVE_TOOLS_CANCELLED'); assert.equal(f.calls.length, 0);
});
test('a terminal create receipt returned after commit is not turned into a false cancellation rollback', async t => {
  const abort = new AbortController();
  const f = await fixture(t, { requestAbort: abort, afterCommit: () => abort.abort() });
  const result = await f.request('/terminal/create', { sessionId: 'work-a', request: { id: 'committed-terminal', cols: 80, rows: 24 } });
  assert.equal(result.status, 200); assert.equal(result.value.result.id, 'committed-terminal');
  assert.equal(f.calls.filter(call => call.method === 'create').length, 1);
});

test('attachment reads require the exact durable message coordinates and original ref, without accepting caller ref fields', async t => {
  let ref, kind = 'user/message';
  const f = await fixture(t, { realAttachments: true, readEvent: request => ({ session: header(request.sessionId), target: { seq: request.seq,
    type: request.seq === 12 ? kind : 'workspace/changes', data: request.seq === 12 ? kind === 'user/message'
      ? { content: [{ type: 'file', attachment: ref }] } : { message: { content: [{ type: 'file', attachment: ref }] } } : { turn: 1 } } }) });
  ref = await f.ctx.attachments.saveFile({ name: 'fictional.txt', data: Buffer.from('Shared fixture text') });
  const proof = { sessionId: 'work-a', seq: 12, attachmentId: ref.attachmentId };
  for (kind of ['user/message', 'assistant/message', 'system/message', 'developer/message', 'tool/result']) {
    const result = await f.request('/attachments/bytes', proof);
    assert.equal(result.status, 200, kind); assert.equal(Buffer.from(result.value.result.data, 'base64').toString(), 'Shared fixture text');
    assert.equal(result.value.result.bytes, ref.bytes); assert.equal('ref' in result.value.result, false);
  }
  for (const input of [{ ...proof, seq: 11 }, { ...proof, attachmentId: 'sha256:' + 'a'.repeat(64) }]) {
    const result = await f.request('/attachments/stat', input); assert.equal(result.status, 403); assert.equal(result.value.error.code, 'NATIVE_TOOLS_ATTACHMENT_SCOPE');
  }
  for (const input of [{ ...proof, ref }, { ...proof, seq: null }, { ...proof, seq: -1 }]) assert.equal((await f.request('/attachments/bytes', input)).status, 400);
  kind = 'deliverables/presented'; assert.equal((await f.request('/attachments/bytes', proof)).status, 403);
  assert.ok(!f.calls.some(call => call.type === 'invoke' || call.type === 'stat'), 'proof reads must not activate a session or use a global blob path');
});
test('proof mismatches and over-limit declarations never reach the attachment store', async t => {
  let reads = 0, bytes = 1, mismatched = true;
  const f = await fixture(t, { attachments: { readFileStream() { reads++; throw Error('must not read'); } }, readEvent: request => ({ session: header(mismatched ? 'other' : request.sessionId),
    target: { seq: request.seq, type: 'user/message', data: { content: [{ type: 'file', attachment: { attachmentId: 'sha256:' + 'b'.repeat(64), name: 'large.bin', bytes } }] } } }) });
  const proof = { sessionId: 'work-a', seq: 12, attachmentId: 'sha256:' + 'b'.repeat(64) };
  assert.equal((await f.request('/attachments/bytes', proof)).status, 403);
  mismatched = false; bytes = 100 * 1024 * 1024 + 1;
  assert.equal((await f.request('/attachments/stat', proof)).status, 413); assert.equal(reads, 0);
  assert.equal((await f.request('/attachments/export?sessionId=work-a&seq=1&seq=2&attachmentId=x')).status, 400);
});
test('image attachment proof uses the public verified image reader and keeps its real media type', async t => {
  let ref;
  const f = await fixture(t, { realAttachments: true, readEvent: request => ({ session: header(request.sessionId), target: { seq: request.seq,
    type: 'assistant/message', data: { message: { content: [{ type: 'image', attachment: ref }] } } } }) });
  const data = encodePng({ width: 2, height: 2, channels: 4, pixels: Buffer.alloc(16, 200) });
  ref = await f.ctx.attachments.saveImage({ data, mediaType: 'image/png', name: 'fixture.png' });
  const proof = { sessionId: 'managed', seq: 6, attachmentId: ref.attachmentId };
  const result = await f.request('/attachments/bytes', proof); assert.equal(result.status, 200);
  assert.equal(result.value.result.kind, 'image'); assert.equal(result.value.result.mediaType, 'image/png');
  assert.deepEqual(Buffer.from(result.value.result.data, 'base64'), data);
  const response = await fetch(f.url + '/attachments/export?' + new URLSearchParams(proof), { headers: f.headers });
  assert.equal(response.headers.get('content-type'), 'image/png'); assert.deepEqual(Buffer.from(await response.arrayBuffer()), data);
  const corrupted = Buffer.from(data); corrupted[corrupted.length - 1] ^= 1;
  await writeFile(f.ctx.attachments.imageHostPath(ref), corrupted);
  assert.equal((await f.request('/attachments/bytes', proof)).value.error.code, 'ATTACHMENT_CORRUPT');
  assert.ok(!f.calls.some(call => call.type === 'stat' || call.type === 'invoke'), 'managed messages stay readable without enabling any mutation');
});
test('attachment export remains complete beyond the preview bound and a real store EOF digest failure cannot finish the HTTP transfer', async t => {
  let ref;
  const f = await fixture(t, { realAttachments: true, readEvent: request => ({ session: header(request.sessionId), target: { seq: request.seq,
    type: 'user/message', data: { content: [{ type: 'file', attachment: ref }] } } }) });
  const bytes = Buffer.alloc(9 * 1024 * 1024 + 7, 0x71);
  ref = await f.ctx.attachments.saveFile({ name: 'large-fictional.bin', data: bytes });
  const proof = { sessionId: 'work-a', seq: 2, attachmentId: ref.attachmentId };
  assert.equal((await f.request('/attachments/bytes', proof)).status, 413);
  const path = '/attachments/export?' + new URLSearchParams(proof);
  const response = await fetch(f.url + path, { headers: f.headers });
  assert.equal(response.status, 200); assert.equal(response.headers.get('content-length'), null);
  const copied = Buffer.from(await response.arrayBuffer()); assert.equal(copied.length, bytes.length);
  assert.equal(createHash('sha256').update(copied).digest('hex'), ref.attachmentId.slice(7));
  bytes[bytes.length - 1] = 0x72; await writeFile(f.ctx.attachments.fileHostPath(ref), bytes);
  await assert.rejects(async () => { const corrupted = await fetch(f.url + path, { headers: f.headers }); await corrupted.arrayBuffer(); });
});
test('Office workspace rendering uses the real Remote scope and keeps source identity separate from complete PDF size', async t => {
  const f = await fixture(t, { realGateway: true });
  const result = await f.request('/files/pdf', { sessionId: 'work-a', path: 'report.docx' });
  assert.equal(result.status, 200); assert.equal(result.value.result.bytes, 400);
  assert.equal(result.value.result.pdfBytes, Buffer.byteLength('%PDF-fixture')); assert.equal(Buffer.from(result.value.result.data, 'base64').toString(), '%PDF-fixture');
  assert.deepEqual(result.value.result.missingFonts, ['Fixture Font']);
  assert.deepEqual(f.calls.find(call => call.namespace === 'officeToPdf').args, { workspaceFileScopeId: 'work-a', path: 'report.docx', priority: 'foreground' });
});
test('attachment Office conversion reads only after public converter admission and rechecks the event proof', async t => {
  let ref, changed = false, admitted = 0, reads = 0;
  const f = await fixture(t, { realAttachments: true, readEvent: request => ({ session: header(request.sessionId), target: { seq: request.seq,
    type: 'user/message', data: { content: [{ type: 'file', attachment: changed ? { ...ref, name: 'changed.docx' } : ref }] } } }),
    office: { async convert(request, signal) {
      admitted++; assert.equal(request.extension, 'docx'); assert.equal(request.priority, 'foreground');
      assert.equal(request.source.version, ref.attachmentId); assert.equal(reads, 0);
      const source = await request.source.read(signal, 1024); reads++;
      assert.equal(source.version, ref.attachmentId); assert.equal(source.bytes.toString(), 'Fictional office bytes');
      return { pdf: Buffer.from('%PDF-converted'), missingFonts: [], generation: 'fixture-generation', cacheKey: 'fixture-cache' };
    } } });
  ref = await f.ctx.attachments.saveFile({ name: 'fictional.docx', data: Buffer.from('Fictional office bytes') });
  const proof = { sessionId: 'work-a', seq: 3, attachmentId: ref.attachmentId };
  const result = await f.request('/attachments/pdf', proof); assert.equal(result.status, 200); assert.equal(result.value.result.pdfBytes, 14); assert.equal(admitted, 1); assert.equal(reads, 1);
  reads = 0; changed = false;
  f.ctx.officeToPdf.convert = async (request, signal) => { changed = true; return request.source.read(signal, 1024); };
  const invalid = await f.request('/attachments/pdf', proof); assert.equal(invalid.status, 403); assert.equal(invalid.value.error.code, 'NATIVE_TOOLS_ATTACHMENT_SCOPE');
});
test('converter failures expose paired safe preview errors and pre-cancelled attachment requests read nothing', async t => {
  for (const locale of ['zh', 'en']) {
    const f = await fixture(t, { locale, failure: Object.assign(Error(secret), { code: 'document-render/failed', details: { reason: 'unavailable', diagnostic: secret } }) });
    const result = await f.request('/files/pdf', { sessionId: 'work-a', path: 'report.docx' });
    assert.equal(result.status, 422); assert.match(result.value.error.message, locale === 'zh' ? /预览/ : /preview/);
  }
  const abort = new AbortController(); abort.abort();
  const f = await fixture(t, { requestAbort: abort });
  assert.equal((await f.request('/attachments/stat', { sessionId: 'work-a', seq: 1, attachmentId: 'x' })).status, 409); assert.equal(f.calls.length, 0);
});
