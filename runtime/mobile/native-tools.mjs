import { randomUUID } from 'node:crypto';
import { TextDecoder } from 'node:util';
import { Peer } from '@deepseek-ai/dsh-client-connection';
import { TYPERT as FileApi } from '@deepseek-ai/dsh-api-workspace-files/typert';
import { TYPERT as TerminalApi } from '@deepseek-ai/dsh-api-terminal-controller/typert';
import { TYPERT as OfficeApi } from '@deepseek-ai/dsh-office-to-pdf/typert';
import { resolveMobileLocale } from './locale.mjs';

const base = '/droy/native/tools';
const BODY_LIMIT = 256 * 1024, BYTE_LIMIT = 8 * 1024 * 1024, QUEUE_LIMIT = 256 * 1024;
const files = new Map(FileApi.invocations.map(row => [row.method, row]));
const terminals = new Map(TerminalApi.invocations.map(row => [row.method, row]));
const office = new Map(OfficeApi.invocations.map(row => [row.method, row]));
const EXPORT_LIMIT = 100 * 1024 * 1024;
const copy = {
  invalid: ['工具请求格式无效。', 'The tool request is invalid.'],
  missing: ['未找到此内容。', 'This content was not found.'],
  unavailable: ['工具服务尚未就绪。', 'The tool service is not ready.'],
  failed: ['工具操作未完成，请刷新。', 'The tool operation did not complete. Refresh and try again.'],
  method: ['不支持此操作方式。', 'This request method is not supported.'],
  cancelled: ['请求已取消，请核实当前状态。', 'The request was cancelled. Check the current state.'],
  size: ['内容超出预览范围。', 'This content exceeds the preview limit.'],
  text: ['此文件不能作为文本预览。', 'This file cannot be previewed as text.'],
  scope: ['此路径不可访问。', 'This path is not accessible.'],
  watch: ['当前文件系统不支持实时更新。', 'Live updates are unavailable for this filesystem.'],
  managed: ['此会话仅供查看。', 'This conversation is read-only.'],
  sequence: ['输入状态已变化，请重新连接终端。', 'The input state changed. Reconnect the terminal.'],
  attachment: ['终端连接已断开，请重新连接。', 'The terminal connection ended. Reconnect it.'],
  buffered: ['终端输入尚未确认，请稍候。', 'Terminal input is awaiting confirmation. Please wait.'],
  diff: ['此历史差异已不可用。', 'This historical comparison is unavailable.'],
  preview: ['此文件暂时无法预览。', 'This file cannot be previewed right now.'],
  exportSize: ['副本大小上限为 100 MiB。', 'Copies are limited to 100 MiB.'],
  imageName: ['图片.png', 'image.png'],
};
class ToolError extends Error {
  constructor(code, status, text) { super(code); this.code = code; this.status = status; this.text = text; }
}
const fail = (code, status, text) => { throw new ToolError(code, status, text); };
const bad = () => fail('NATIVE_TOOLS_BAD_REQUEST', 400, 'invalid');
const object = value => value !== null && typeof value === 'object' && !Array.isArray(value);
function fields(value, allowed, required = allowed) {
  if (!object(value) || Object.keys(value).some(key => !allowed.includes(key)) || required.some(key => !Object.hasOwn(value, key))) bad();
}
function string(value, empty = false) {
  if (typeof value !== 'string' || (!empty && !value.trim()) || Buffer.byteLength(value) > 16384 || /[\u0000-\u001f\u007f]/u.test(value) || !value.isWellFormed()) bad();
  return value;
}
function integer(value, min = 0, max = Number.MAX_SAFE_INTEGER) {
  if (!Number.isSafeInteger(value) || value < min || value > max) bad();
  return value;
}
function service(ctx, name, method) {
  const value = typeof ctx.get === 'function' ? ctx.get(name) : ctx[name];
  if (!value || method && typeof value[method] !== 'function') fail('NATIVE_TOOLS_NOT_READY', 503, 'unavailable');
  return value;
}
function parse(descriptor, input) {
  try { return descriptor.input({ strict: true }).create().parse(input); }
  catch (error) { if (error.name === 'ZodError') bad(); throw error; }
}
function message(ctx, key) { return copy[key][resolveMobileLocale(ctx) === 'zh' ? 0 : 1]; }
function failure(ctx, error, signal) {
  if (error instanceof ToolError) return { status: error.status, body: { ok: false, error: { code: error.code, message: message(ctx, error.text) } } };
  const known = {
    'workspace-file/not-found': [404, 'missing'], 'workspace-file/not-regular-file': [422, 'text'],
    'workspace-file/not-directory': [422, 'scope'], 'workspace-file/not-text': [422, 'text'],
    'workspace-file/too-large': [413, 'size'], 'workspace-file/outside-workspace': [403, 'scope'],
    'workspace-file/watch-unsupported': [422, 'watch'], 'terminal/unavailable': [404, 'attachment'],
    'terminal/control-unavailable': [409, 'managed'], 'terminal/limit-reached': [409, 'size'],
    'gateway/arguments-invalid': [400, 'invalid'], 'gateway/input-invalid': [400, 'invalid'],
    'gateway/lookup-not-found': [404, 'missing'], 'gateway/context-not-found': [404, 'missing'],
    'gateway/service-unavailable': [503, 'unavailable'], 'gateway/definition-unavailable': [503, 'unavailable'],
    'SESSION_QUERY_EVENT_NOT_FOUND': [404, 'missing'], 'SESSION_QUERY_SESSION_NOT_FOUND': [404, 'missing'],
    'remote-access/denied': [403, 'scope'], 'gateway/uplink-overflow': [409, 'buffered'],
    'ATTACHMENT_NOT_FOUND': [404, 'missing'], 'ATTACHMENT_CORRUPT': [409, 'failed'],
    'ATTACHMENT_READ_FAILED': [500, 'failed'], 'document-render/failed': [422, 'preview'],
  };
  const cancelled = error?.name === 'AbortError' || error?.code === 'gateway/cancelled' || signal?.aborted && error === signal.reason;
  const pair = known[error?.code];
  const code = cancelled ? 'NATIVE_TOOLS_CANCELLED' : pair ? error.code : 'NATIVE_TOOLS_FAILED';
  return { status: cancelled ? 409 : pair?.[0] ?? 500,
    body: { ok: false, error: { code, message: message(ctx, cancelled ? 'cancelled' : pair?.[1] ?? 'failed') } } };
}
function reply(res, status, value) {
  if (res.destroyed || res.writableEnded) return;
  res.writeHead(status, { 'Content-Type': 'application/json; charset=utf-8', 'Cache-Control': 'no-store' });
  res.end(JSON.stringify(value));
}
function body(req) {
  if (!/^application\/json(?:\s*;|\s*$)/iu.test(req.headers['content-type'] ?? '')) bad();
  if (Number(req.headers['content-length']) > BODY_LIMIT) fail('NATIVE_TOOLS_TOO_LARGE', 413, 'size');
  return new Promise((resolve, reject) => {
    const chunks = []; let bytes = 0;
    const cleanup = () => { req.removeListener('data', data); req.removeListener('end', end); req.removeListener('error', rejected); req.removeListener('aborted', aborted); };
    const rejected = error => { cleanup(); reject(error); };
    const aborted = () => rejected(new ToolError('NATIVE_TOOLS_BAD_REQUEST', 400, 'invalid'));
    const data = chunk => {
      bytes += chunk.length;
      if (bytes > BODY_LIMIT) { cleanup(); req.resume(); reject(new ToolError('NATIVE_TOOLS_TOO_LARGE', 413, 'size')); }
      else chunks.push(chunk);
    };
    const end = () => {
      cleanup();
      try { resolve(JSON.parse(new TextDecoder('utf-8', { fatal: true }).decode(Buffer.concat(chunks)))); }
      catch { reject(new ToolError('NATIVE_TOOLS_BAD_REQUEST', 400, 'invalid')); }
    };
    req.on('data', data); req.once('end', end); req.once('error', rejected); req.once('aborted', aborted);
  });
}
function getInput(url, allowed, required = allowed) {
  const input = {};
  for (const [key, value] of url.searchParams) { if (Object.hasOwn(input, key)) bad(); input[key] = value; }
  fields(input, allowed, required);
  return input;
}
function fileInput(method, input) {
  const extra = method === 'read' ? ['range'] : method === 'readBytes' ? ['options'] : [];
  fields(input, ['sessionId', 'path', ...extra], ['sessionId', 'path']);
  string(input.sessionId); string(input.path, true);
  const args = { workspaceFileScopeId: input.sessionId, path: input.path,
    ...(method === 'read' ? { range: input.range === undefined ? {} : input.range } : method === 'readBytes' ? { options: input.options === undefined ? {} : input.options } : {}) };
  if (method === 'read') { if (!object(args.range)) bad(); for (const value of Object.values(args.range)) integer(value, 1); }
  if (method === 'readBytes' && !object(args.options)) bad();
  if (args.options?.range) {
    if (args.options.range.offset !== undefined) integer(args.options.range.offset, 0);
    if (args.options.range.length !== undefined) integer(args.options.range.length, 1);
  }
  if (args.options?.baseFile !== undefined) string(args.options.baseFile);
  return parse(files.get(method), args);
}
async function ordinary(ctx, sessionId, signal) {
  const runtime = service(ctx, 'droyCompanions', 'ownsSession');
  if (runtime.ownsSession(sessionId)) fail('COMPANION_SESSION_MANAGED', 409, 'managed');
  const live = (typeof ctx.get === 'function' ? ctx.get('sessions') : ctx.sessions)?.get(sessionId)?.header;
  const saved = live ? undefined : await service(ctx, 'sessionPersistence', 'stat').stat(sessionId, { signal });
  const header = live ?? saved?.header;
  if (!header) fail('NATIVE_TOOLS_SESSION_NOT_FOUND', 404, 'missing');
  if (header.id !== sessionId || !header.cwd || saved?.formatStatus === 'migration-required') fail('NATIVE_TOOLS_TARGET_UNAVAILABLE', 409, 'managed');
  if (header.delegationDepth !== undefined) integer(header.delegationDepth);
  if (header.delegationDepth > 0 || header.origin === 'subagent') fail('NATIVE_TOOLS_TARGET_UNAVAILABLE', 409, 'managed');
  signal?.throwIfAborted();
}
function safeTerminal(ctx, value) {
  if (Array.isArray(value)) return value.map(row => safeTerminal(ctx, row));
  if (!object(value)) return value;
  return { ...value, ...(value.info ? { info: safeTerminal(ctx, value.info) } : {}),
    ...(Object.hasOwn(value, 'error') ? { error: message(ctx, 'failed') } : {}) };
}
async function sse(res, value, signal) {
  signal?.throwIfAborted();
  if (res.destroyed || res.writableEnded) throw new DOMException('Closed', 'AbortError');
  const data = 'data: ' + JSON.stringify(value) + '\n\n';
  if (Buffer.byteLength(data) > 16 * 1024 * 1024) fail('NATIVE_TOOLS_TOO_LARGE', 413, 'size');
  if (res.write(data)) return;
  await new Promise((resolve, reject) => {
    const clean = () => { res.removeListener('drain', drained); res.removeListener('close', closed); signal?.removeEventListener('abort', aborted); };
    const drained = () => { clean(); resolve(); };
    const closed = () => { clean(); reject(new DOMException('Closed', 'AbortError')); };
    const aborted = () => { clean(); reject(signal.reason); };
    res.once('drain', drained); res.once('close', closed); signal?.addEventListener('abort', aborted, { once: true });
    if (signal?.aborted) aborted();
  });
}
function streamHeaders(res) {
  res.writeHead(200, { 'Content-Type': 'text/event-stream', 'Cache-Control': 'no-store', 'Connection': 'keep-alive', 'X-Accel-Buffering': 'no' });
  res.write(': connected\n\n');
  const heartbeat = setInterval(() => { if (!res.destroyed && res.writableLength < QUEUE_LIMIT) res.write(': heartbeat\n\n'); }, 15000);
  heartbeat.unref?.(); return () => clearInterval(heartbeat);
}
async function attachmentProof(ctx, input, signal) {
  fields(input, ['sessionId', 'seq', 'attachmentId']); string(input.sessionId); integer(input.seq); string(input.attachmentId);
  const read = await service(ctx, 'sessionQuery', 'readEvent').readEvent({ sessionId: input.sessionId, seq: input.seq, before: 0, after: 0 }, signal);
  signal?.throwIfAborted();
  if (read.session?.id !== input.sessionId || read.target?.seq !== input.seq) fail('NATIVE_TOOLS_ATTACHMENT_SCOPE', 403, 'scope');
  const event = read.target;
  const data = event.type === 'user/message' ? event.data : ['assistant/message', 'system/message', 'developer/message', 'tool/result'].includes(event.type) ? event.data?.message : null;
  const part = Array.isArray(data?.content) ? data.content.find(part => ['image', 'file'].includes(part?.type) && part.attachment?.attachmentId === input.attachmentId) : null;
  if (!part) fail('NATIVE_TOOLS_ATTACHMENT_SCOPE', 403, 'scope');
  const ref = part.attachment;
  if (!object(ref) || !Number.isSafeInteger(ref.bytes) || ref.bytes < 0) fail('NATIVE_TOOLS_ATTACHMENT_SCOPE', 403, 'scope');
  if (ref.bytes > EXPORT_LIMIT) fail('NATIVE_TOOLS_EXPORT_TOO_LARGE', 413, 'exportSize');
  if (part.type === 'file') string(ref.name);
  else if (!['image/png', 'image/jpeg', 'image/webp', 'image/gif'].includes(ref.mediaType)) fail('NATIVE_TOOLS_ATTACHMENT_SCOPE', 403, 'scope');
  const name = ref.name || message(ctx, 'imageName');
  return { ref: structuredClone(ref), kind: part.type, name,
    mediaType: part.type === 'image' ? ref.mediaType : 'application/octet-stream', bytes: ref.bytes, attachmentId: ref.attachmentId };
}
function attachmentMetadata(value) {
  const { ref, ...metadata } = value; return metadata;
}
async function* attachmentSource(ctx, proof, signal) {
  const store = service(ctx, 'attachments', proof.kind === 'image' ? 'readImage' : 'readFileStream');
  let bytes = 0;
  const stream = proof.kind === 'image' ? [(await store.readImage(proof.ref, signal)).data] : store.readFileStream(proof.ref, signal);
  for await (const chunk of stream) {
    signal?.throwIfAborted();
    if (!(chunk instanceof Uint8Array)) fail('NATIVE_TOOLS_FAILED', 500, 'failed');
    bytes += chunk.byteLength;
    if (bytes > proof.bytes || bytes > EXPORT_LIMIT) fail('NATIVE_TOOLS_FAILED', 500, 'failed');
    yield chunk;
  }
  if (bytes !== proof.bytes) fail('NATIVE_TOOLS_FAILED', 500, 'failed');
}
async function attachmentBytes(ctx, proof, signal, limit = BYTE_LIMIT) {
  if (proof.bytes > limit) fail('NATIVE_TOOLS_TOO_LARGE', 413, 'size');
  const chunks = []; let bytes = 0;
  for await (const chunk of attachmentSource(ctx, proof, signal)) {
    bytes += chunk.byteLength;
    if (bytes > limit) fail('NATIVE_TOOLS_TOO_LARGE', 413, 'size');
    chunks.push(chunk);
  }
  return Buffer.concat(chunks, bytes);
}
async function writeBinary(res, chunk, signal) {
  signal?.throwIfAborted();
  if (res.destroyed || res.writableEnded) throw new DOMException('Closed', 'AbortError');
  if (res.write(chunk)) return;
  await new Promise((resolve, reject) => {
    const done = () => { clean(); resolve(); }, failed = () => { clean(); reject(new DOMException('Closed', 'AbortError')); };
    const abort = () => { clean(); reject(signal.reason); };
    const clean = () => { res.removeListener('drain', done); res.removeListener('close', failed); signal?.removeEventListener('abort', abort); };
    res.once('drain', done); res.once('close', failed); signal?.addEventListener('abort', abort, { once: true }); if (signal?.aborted) abort();
  });
}
function pdfResult(value) {
  if (!(value?.data instanceof Uint8Array) || value.data.byteLength > BYTE_LIMIT) fail('NATIVE_TOOLS_TOO_LARGE', 413, 'size');
  return { ...value, data: Buffer.from(value.data).toString('base64'), encoding: 'base64', pdfBytes: value.data.byteLength };
}
class InputQueue {
  items = []; waiting; closed = false;
  push(value) { if (this.closed) fail('NATIVE_TOOLS_ATTACHMENT_CLOSED', 409, 'attachment'); if (this.waiting) { const wake = this.waiting; this.waiting = null; wake({ value, done: false }); } else this.items.push(value); }
  next() { if (this.items.length) return Promise.resolve({ value: this.items.shift(), done: false }); if (this.closed) return Promise.resolve({ done: true }); return new Promise(resolve => { this.waiting = resolve; }); }
  return() { this.closed = true; this.items = []; this.waiting?.({ done: true }); this.waiting = null; return Promise.resolve({ done: true }); }
  [Symbol.asyncIterator]() { return this; }
}

/** Root native-api admits the local owner before dispatching any route here. */
export function createNativeTools(ctx) {
  const attachments = new Map(), active = new Set(); let closed = false;
  async function withPeer(action) {
    if (closed) fail('NATIVE_TOOLS_NOT_READY', 503, 'unavailable');
    const peer = new Peer(ctx, { principal: { kind: 'owner' }, interactions: 'none' }); active.add(peer);
    try { return await action(peer); } finally { active.delete(peer); await peer.dispose(); }
  }
  const invoke = (namespace, method, args, signal) => withPeer(peer => service(ctx, 'typertGateway', 'invoke').invoke({ namespace, method, args, peer, signal }));
  async function content(input, signal) {
    fields(input, ['sessionId', 'beforeSeq', 'limit'], ['sessionId']); string(input.sessionId);
    const limit = input.limit === undefined ? 30 : integer(input.limit, 1, 100);
    const before = input.beforeSeq === undefined ? Number.MAX_SAFE_INTEGER : integer(input.beforeSeq, 0);
    const query = service(ctx, 'sessionQuery', 'listEvents');
    const records = (await query.listEvents(input.sessionId)).filter(row => ['deliverables/presented', 'workspace/changes'].includes(row.type) && row.seq < before)
      .sort((a, b) => b.seq - a.seq);
    const selected = records.slice(0, limit), entries = [];
    for (const record of selected) {
      signal?.throwIfAborted();
      const read = await query.readEvent({ sessionId: input.sessionId, seq: record.seq, before: 0, after: 0 }, signal);
      if (read.session?.id !== input.sessionId || read.target?.type !== record.type || read.target.seq !== record.seq) fail('NATIVE_TOOLS_FAILED', 500, 'failed');
      const event = read.target;
      if (!object(event.data)) fail('NATIVE_TOOLS_FAILED', 500, 'failed');
      integer(event.data.turn, 1);
      if (record.type === 'deliverables/presented') {
        if (typeof event.data.callId !== 'string' || !Array.isArray(event.data.files)) fail('NATIVE_TOOLS_FAILED', 500, 'failed');
        const declared = event.data.files.map((file, index) => {
          if (!object(file) || typeof file.path !== 'string' || !file.path.trim() || file.description !== undefined && typeof file.description !== 'string') fail('NATIVE_TOOLS_FAILED', 500, 'failed');
          return { index, path: file.path, ...(file.description === undefined ? {} : { description: file.description }) };
        });
        entries.push({ seq: event.seq, type: event.type, turn: event.data.turn, files: declared });
      } else entries.push({ seq: event.seq, type: event.type, turn: event.data.turn });
    }
    return { entries, hasMore: records.length > limit, nextBefore: records.length > limit ? selected.at(-1).seq : null };
  }
  async function terminal(method, input, signal) {
    const params = method === 'list' ? ['sessionId'] : method === 'create' ? ['sessionId', 'request']
      : method === 'rename' ? ['sessionId', 'id', 'title'] : ['environment', 'shells'].includes(method) ? ['sessionId'] : ['sessionId', 'id'];
    fields(input, params); string(input.sessionId);
    if (method !== 'list') await ordinary(ctx, input.sessionId, signal);
    const { sessionId, ...rest } = input;
    const args = parse(terminals.get(method), method === 'list' ? input : { agentId: sessionId, ...rest });
    if (rest.id !== undefined && !/^[\w-]{1,128}$/u.test(rest.id)) bad();
    if (method === 'create') { if (!/^[\w-]{1,128}$/u.test(rest.request.id)) bad(); integer(rest.request.cols, 2); integer(rest.request.rows, 1); }
    if (method === 'rename' && (!rest.title.trim() || rest.title.length > 120)) bad();
    return safeTerminal(ctx, await invoke('terminal', method, args, signal));
  }
  async function detach(id) {
    const value = attachments.get(id);
    if (!value) fail('NATIVE_TOOLS_ATTACHMENT_CLOSED', 409, 'attachment');
    attachments.delete(id); clearTimeout(value.expiry); value.abort.abort(); await value.queue.return();
    await value.peer?.dispose();
    return { detached: true };
  }
  async function attachmentStream(id, res, signal) {
    const value = attachments.get(id);
    if (!value || value.claimed) fail('NATIVE_TOOLS_ATTACHMENT_CLOSED', 409, 'attachment');
    value.claimed = true; clearTimeout(value.expiry);
    const abort = () => value.abort.abort(signal?.reason);
    const disconnect = () => value.abort.abort();
    signal?.addEventListener('abort', abort, { once: true }); res.once('close', disconnect);
    const joined = signal ? AbortSignal.any([signal, value.abort.signal]) : value.abort.signal;
    let heartbeat;
    try {
      await withPeer(async peer => {
        value.peer = peer;
        await ordinary(ctx, value.sessionId, joined);
        const source = await service(ctx, 'typertGateway', 'stream').stream({ namespace: 'terminal', method: 'attach',
          args: parse(terminals.get('attach'), { agentId: value.sessionId, id: value.terminalId }), peer, signal: joined,
          uplink: value.queue, uplinkWindow: { maxBytes: value.maxBufferedBytes, frameOverheadBytes: 0 } });
        joined.throwIfAborted(); heartbeat = streamHeaders(res);
        await sse(res, { type: 'native/attached', attachmentId: id, maxInputBytes: value.maxInputBytes, maxBufferedBytes: value.maxBufferedBytes,
          maxCols: value.maxCols, maxRows: value.maxRows }, joined);
        for await (const raw of source) {
          const frame = terminals.get('attach').result.create().parse(raw);
          if (frame.type === 'snapshot') value.ready = true;
          if (frame.type === 'ack') for (const [sequence, bytes] of value.pending) if (sequence <= frame.sequence) { value.pending.delete(sequence); value.pendingBytes -= bytes; }
          await sse(res, safeTerminal(ctx, frame), joined);
        }
      });
    } catch (error) {
      if (!joined.aborted && !res.destroyed) {
        const failed = failure(ctx, error, joined);
        if (res.headersSent) await sse(res, failed.body, joined); else reply(res, failed.status, failed.body);
      }
    } finally {
      heartbeat?.(); signal?.removeEventListener('abort', abort); res.removeListener('close', disconnect);
      attachments.delete(id); value.abort.abort(); await value.queue.return();
      if (!res.destroyed && !res.writableEnded) res.end();
    }
  }
  return {
    async handle(req, res, signal) {
      try {
        if (closed) fail('NATIVE_TOOLS_NOT_READY', 503, 'unavailable');
        if (!req.url?.startsWith('/') || req.url.startsWith('//')) bad();
        const url = new URL(req.url, 'http://127.0.0.1');
        if (url.pathname !== base && !url.pathname.startsWith(base + '/')) fail('NATIVE_TOOLS_NOT_FOUND', 404, 'missing');
        const path = url.pathname.slice(base.length);
        const watch = path === '/files/watch', download = path === '/attachments/export', attachMatch = /^\/terminal\/attachment\/([\w-]+)(?:\/(input|detach))?$/u.exec(path);
        const expected = watch || download || attachMatch && !attachMatch[2] ? 'GET' : 'POST';
        if (req.method !== expected) fail('NATIVE_TOOLS_METHOD_NOT_ALLOWED', 405, 'method');
        if (!watch && !download && !attachMatch && url.search || attachMatch && url.search) bad();
        const input = expected === 'POST' ? await body(req) : watch ? getInput(url, ['sessionId', 'path'])
          : download ? getInput(url, ['sessionId', 'seq', 'attachmentId']) : {};
        if (expected === 'GET' && (Number(req.headers['content-length']) > 0 || req.headers['transfer-encoding'])) bad();
        signal?.throwIfAborted();
        if (path.startsWith('/attachments/')) {
          if (download) { if (!/^(?:0|[1-9]\d*)$/u.test(input.seq)) bad(); input.seq = Number(input.seq); }
          if (!['/attachments/stat', '/attachments/bytes', '/attachments/export', '/attachments/pdf'].includes(path)) fail('NATIVE_TOOLS_NOT_FOUND', 404, 'missing');
          const proof = await attachmentProof(ctx, input, signal);
          if (path === '/attachments/stat') return reply(res, 200, { ok: true, result: attachmentMetadata(proof) });
          if (path === '/attachments/bytes') return reply(res, 200, { ok: true, result: { ...attachmentMetadata(proof), data: (await attachmentBytes(ctx, proof, signal)).toString('base64'), encoding: 'base64' } });
          if (path === '/attachments/pdf') {
            const extension = proof.name.split('.').at(-1).toLowerCase();
            if (proof.kind !== 'file' || !['doc', 'docx', 'xls', 'xlsx', 'ppt', 'pptx'].includes(extension)) fail('NATIVE_TOOLS_PREVIEW_UNAVAILABLE', 422, 'preview');
            const converted = await service(ctx, 'officeToPdf', 'convert').convert({ extension, priority: 'foreground', source: {
              key: JSON.stringify(['native-attachment', proof.attachmentId, proof.name]), version: proof.attachmentId, bytes: proof.bytes,
              async read(upstream, maxBytes) {
                const current = await attachmentProof(ctx, input, upstream);
                if (JSON.stringify(current.ref) !== JSON.stringify(proof.ref)) fail('NATIVE_TOOLS_ATTACHMENT_SCOPE', 403, 'scope');
                return { bytes: await attachmentBytes(ctx, current, upstream, Math.min(maxBytes, EXPORT_LIMIT)), version: current.attachmentId };
              },
            } }, signal);
            return reply(res, 200, { ok: true, result: pdfResult({ ...attachmentMetadata(proof), data: converted.pdf, missingFonts: converted.missingFonts, generation: converted.generation }) });
          }
          // Chunked framing ends only after the store's EOF integrity check.
          // A late verification failure destroys the transfer, never a complete-looking copy.
          const source = attachmentSource(ctx, proof, signal)[Symbol.asyncIterator]();
          try {
            let next = await source.next();
            res.writeHead(200, { 'Content-Type': proof.mediaType, 'Cache-Control': 'no-store', 'X-Content-Type-Options': 'nosniff' });
            while (!next.done) { await writeBinary(res, next.value, signal); next = await source.next(); }
            res.end();
          } catch (error) { if (res.headersSent) { res.destroy(); return; } throw error; }
          finally { await source.return?.(); }
          return;
        }
        if (path === '/files/pdf') {
          fields(input, ['sessionId', 'path']); string(input.sessionId); string(input.path);
          const args = parse(office.get('render'), { workspaceFileScopeId: input.sessionId, path: input.path, priority: 'foreground' });
          const value = await invoke('officeToPdf', 'render', args, signal);
          return reply(res, 200, { ok: true, result: pdfResult(office.get('render').result.create().parse(value)) });
        }
        if (watch) {
          const args = fileInput('changes', input);
          return await withPeer(async peer => {
            const source = await service(ctx, 'typertGateway', 'stream').stream({ namespace: 'workspaceFiles', method: 'changes', args, peer, signal });
            const stop = streamHeaders(res);
            try { for await (const frame of source) await sse(res, files.get('changes').result.create().parse(frame), signal); }
            catch (error) { if (!res.destroyed && !signal?.aborted) await sse(res, failure(ctx, error, signal).body, signal); }
            finally { stop(); if (!res.destroyed && !res.writableEnded) res.end(); }
          });
        }
        if (attachMatch) {
          const [, id, action] = attachMatch;
          if (!action) return await attachmentStream(id, res, signal);
          if (action === 'detach') { fields(input, []); return reply(res, 200, { ok: true, result: await detach(id) }); }
          const value = attachments.get(id);
          if (!value || !value.claimed || !value.ready || value.abort.signal.aborted) fail('NATIVE_TOOLS_ATTACHMENT_CLOSED', 409, 'attachment');
          fields(input, input.type === 'write' ? ['type', 'sequence', 'data'] : ['type', 'sequence', 'cols', 'rows']);
          if (!['write', 'resize'].includes(input.type)) bad(); integer(input.sequence, 1);
          if (input.sequence !== value.sequence + 1) fail('NATIVE_TOOLS_INPUT_SEQUENCE', 409, 'sequence');
          if (input.type === 'write') {
            if (typeof input.data !== 'string' || !input.data.isWellFormed()) bad();
            if (Buffer.byteLength(input.data) > value.maxInputBytes) fail('NATIVE_TOOLS_TOO_LARGE', 413, 'size');
          } else { integer(input.cols, 2, value.maxCols); integer(input.rows, 1, value.maxRows); }
          const bytes = Buffer.byteLength(JSON.stringify(input));
          if (value.pendingBytes + bytes > value.maxBufferedBytes) fail('NATIVE_TOOLS_INPUT_PENDING', 409, 'buffered');
          value.queue.push(input); value.sequence = input.sequence; value.pending.set(input.sequence, bytes); value.pendingBytes += bytes;
          return reply(res, 202, { ok: true, result: { accepted: true, sequence: input.sequence } });
        }
        if (path === '/terminal/attachment') {
          fields(input, ['sessionId', 'id']); string(input.sessionId); if (!/^[\w-]{1,128}$/u.test(input.id)) bad();
          await ordinary(ctx, input.sessionId, signal);
          const rows = await terminal('list', { sessionId: input.sessionId }, signal);
          if (!rows.some(row => row.id === input.id)) fail('terminal/unavailable', 404, 'attachment');
          const env = await terminal('environment', { sessionId: input.sessionId }, signal);
          for (const key of ['maxInputBytes', 'maxBufferedBytes', 'maxCols', 'maxRows']) integer(env[key], 1);
          if (attachments.size >= 8) fail('NATIVE_TOOLS_INPUT_PENDING', 409, 'buffered');
          const id = randomUUID(), value = { sessionId: input.sessionId, terminalId: input.id, abort: new AbortController(),
            queue: new InputQueue(), pending: new Map(), pendingBytes: 0, sequence: 0, ready: false, claimed: false,
            maxInputBytes: Math.min(env.maxInputBytes, 16 * 1024), maxBufferedBytes: Math.min(env.maxBufferedBytes, QUEUE_LIMIT), maxCols: env.maxCols, maxRows: env.maxRows };
          value.expiry = setTimeout(() => { attachments.delete(id); value.abort.abort(); value.queue.return(); }, 30000); value.expiry.unref?.();
          attachments.set(id, value);
          return reply(res, 200, { ok: true, result: { attachmentId: id } });
        }
        const fileMethod = { '/files/list': 'list', '/files/stat': 'stat', '/files/text': 'read', '/files/bytes': 'readBytes' }[path];
        if (fileMethod) {
          const args = fileInput(fileMethod, input), value = await invoke('workspaceFiles', fileMethod, args, signal);
          if (fileMethod === 'readBytes') {
            if (!(value.data instanceof Uint8Array)) fail('NATIVE_TOOLS_FAILED', 500, 'failed');
            if (value.data.byteLength > BYTE_LIMIT) fail('NATIVE_TOOLS_TOO_LARGE', 413, 'size');
            return reply(res, 200, { ok: true, result: { ...value, data: Buffer.from(value.data).toString('base64'), encoding: 'base64' } });
          }
          return reply(res, 200, { ok: true, result: files.get(fileMethod).result.create().parse(value) });
        }
        if (path === '/content') return reply(res, 200, { ok: true, result: await content(input, signal) });
        if (['/changes/summary', '/changes/diff'].includes(path)) {
          fields(input, path.endsWith('/diff') ? ['sessionId', 'seq', 'index'] : ['sessionId', 'seq']); string(input.sessionId); integer(input.seq, 0);
          if (input.index !== undefined) integer(input.index);
          const changes = service(ctx, 'workspaceChanges', path.endsWith('/diff') ? 'diff' : 'summary');
          const result = path.endsWith('/diff') ? await changes.diff(input.sessionId, input.seq, input.index, signal) : changes.summary(input.sessionId, input.seq);
          if (!result) fail('NATIVE_TOOLS_DIFF_UNAVAILABLE', 404, 'diff');
          const { cwd, snapshot, ...view } = result;
          return reply(res, 200, { ok: true, result: view });
        }
        const terminalMethod = /^\/terminal\/(environment|shells|list|create|rename|close)$/u.exec(path)?.[1];
        if (terminalMethod) return reply(res, 200, { ok: true, result: await terminal(terminalMethod, input, signal) ?? null });
        fail('NATIVE_TOOLS_NOT_FOUND', 404, 'missing');
      } catch (error) {
        req.resume(); const failed = failure(ctx, error, signal);
        if (res.headersSent) { if (!res.destroyed && !res.writableEnded) res.end(); }
        else reply(res, failed.status, failed.body);
      }
    },
    async dispose() {
      closed = true;
      await Promise.allSettled([...attachments.keys()].map(id => detach(id)));
      await Promise.allSettled([...active].map(peer => peer.dispose()));
    },
  };
}
