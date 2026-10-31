import { TYPERT } from '@deepseek-ai/dsh-client-file-upload/typert';
import { resolveMobileLocale } from './locale.mjs';

const base = '/droy/native/uploads/files/';
const maxBytes = 20 * 1024 * 1024;
const chunkBytes = 64 * 1024;
const receiptCodec = TYPERT.invocations.find(row => row.namespace === 'fileUploads' && row.method === 'upload').result.create();
const copy = {
  invalid: ['文件上传请求无效。', 'The file upload request is invalid.'],
  missing: ['未找到此上传接口。', 'The upload endpoint was not found.'],
  method: ['此上传接口仅支持发送文件。', 'This endpoint only accepts file uploads.'],
  type: ['请使用原始二进制文件上传。', 'Upload the file as raw binary data.'],
  large: ['文件不能超过 20MiB。', 'The file must not exceed 20MiB.'],
  unavailable: ['文件上传服务尚未就绪。', 'The file upload service is not ready.'],
  session: ['未找到目标工作会话。', 'The target work session was not found.'],
  target: ['此会话不支持上传文件。', 'This session does not accept file uploads.'],
  managed: ['请从伙伴对话添加文件。', 'Add this file from the companion conversation.'],
  attachment: ['文件未能保存，请重试。', 'The file could not be saved. Try again.'],
  cancelled: ['上传已取消，结果尚未确认。', 'The upload was cancelled; its result is unconfirmed.'],
  failed: ['上传结果未确认，请重试。', 'The upload could not be confirmed. Try again.'],
};
class UploadError extends Error {
  constructor(code, status, text) { super(code); this.code = code; this.status = status; this.text = text; }
}
const bad = () => new UploadError('NATIVE_UPLOAD_BAD_REQUEST', 400, 'invalid');
const unavailable = () => new UploadError('NATIVE_UPLOAD_NOT_READY', 503, 'unavailable');
const failed = () => new UploadError('NATIVE_UPLOAD_FAILED', 500, 'failed');
const identifier = value => typeof value === 'string' && value.length > 0 && value.length <= 1024 && value.trim() === value
  && !/[\x00-\x1f\x7f]/.test(value);
const opaque = value => identifier(value) && !/[/\\]/.test(value);
const filename = value => typeof value === 'string' && value.length <= 1024 && value.trim().length > 0
  && !['.', '..'].includes(value.trim()) && !/[\x00-\x1f\x7f/\\]/.test(value);

function service(ctx, name, method) {
  const value = typeof ctx.get === 'function' ? ctx.get(name) : ctx[name];
  if (!value || typeof value[method] !== 'function') throw unavailable();
  return value;
}
function reply(res, status, value) {
  if (res.destroyed || res.writableEnded) return;
  const data = JSON.stringify(value);
  res.writeHead(status, { 'Content-Type': 'application/json; charset=utf-8', 'Cache-Control': 'no-store' });
  res.end(data);
}
function errorReply(ctx, res, error, signal) {
  const known = {
    'session/not-found': [404, 'session'], 'session/attachment-invalid': [422, 'attachment'],
    'subagent/attachment-invalid': [422, 'target'], 'gateway/bad-request': [400, 'invalid'],
  };
  const own = error instanceof UploadError;
  const cancelled = error?.name === 'AbortError' || error?.code === 'gateway/cancelled'
    || (signal?.aborted && (error === signal.reason || error?.cause === signal.reason));
  const mapped = own ? [error.status, error.text] : cancelled ? [409, 'cancelled']
    : Object.hasOwn(known, error?.code) ? known[error.code] : [500, 'failed'];
  const code = own ? error.code : cancelled ? 'NATIVE_UPLOAD_CANCELLED'
    : Object.hasOwn(known, error?.code) ? error.code : 'NATIVE_UPLOAD_FAILED';
  reply(res, mapped[0], { ok: false, error: { code, message: copy[mapped[1]][resolveMobileLocale(ctx) === 'zh' ? 0 : 1] } });
}
function request(req) {
  if (!req.url?.startsWith('/') || req.url.startsWith('//')) throw bad();
  const url = new URL(req.url, `http://${req.headers.host}`);
  if (!url.pathname.startsWith(base)) throw new UploadError('NATIVE_UPLOAD_NOT_FOUND', 404, 'missing');
  if (req.method !== 'POST') throw new UploadError('NATIVE_UPLOAD_METHOD_NOT_ALLOWED', 405, 'method');
  const raw = url.pathname.slice(base.length);
  if (!raw || raw.includes('/') || url.hash || !url.search.startsWith('?name=') || url.search.includes('&')) throw bad();
  let sessionId, name;
  try { sessionId = decodeURIComponent(raw); name = decodeURIComponent(url.search.slice(6).replace(/\+/g, ' ')); }
  catch { throw bad(); }
  if (!identifier(sessionId) || !filename(name)) throw bad();
  if (req.headers['content-type']?.split(';', 1)[0].trim().toLowerCase() !== 'application/octet-stream'
    || (req.headers['content-encoding'] && req.headers['content-encoding'].toLowerCase() !== 'identity'))
    throw new UploadError('NATIVE_UPLOAD_MEDIA_TYPE', 415, 'type');
  const length = req.headers['content-length'];
  if (length !== undefined && (!/^(0|[1-9][0-9]*)$/.test(length) || !Number.isSafeInteger(Number(length)))) throw bad();
  if (Number(length) > maxBytes) throw new UploadError('NATIVE_UPLOAD_TOO_LARGE', 413, 'large');
  return { sessionId, name, expectedBytes: length === undefined ? undefined : Number(length) };
}
async function target(ctx, sessionId, signal) {
  if (service(ctx, 'droyCompanions', 'ownsSession').ownsSession(sessionId))
    throw new UploadError('COMPANION_SESSION_MANAGED', 409, 'managed');
  const snapshot = await service(ctx, 'sessionPersistence', 'stat').stat(sessionId, { signal });
  signal?.throwIfAborted();
  if (snapshot === undefined) throw new UploadError('NATIVE_UPLOAD_SESSION_NOT_FOUND', 404, 'session');
  if (!snapshot?.header || snapshot.header.id !== sessionId) throw unavailable();
  if (snapshot.formatStatus === 'migration-required' || typeof snapshot.header.cwd !== 'string' || !snapshot.header.cwd.trim())
    throw new UploadError('NATIVE_UPLOAD_TARGET_UNAVAILABLE', 409, 'target');
  const depth = snapshot.header.delegationDepth;
  if (depth !== undefined && (!Number.isSafeInteger(depth) || depth < 0)) throw unavailable();
  if (depth > 0 || snapshot.header.origin === 'subagent') throw new UploadError('subagent/attachment-invalid', 422, 'target');
}
function intake(req, expectedBytes, signal) {
  const state = { bytes: 0, complete: false, error: undefined };
  state.data = (async function* () {
    let ended = req.readableEnded, inputError, wake;
    const notify = () => { const resume = wake; wake = undefined; resume?.(); };
    const end = () => { ended = true; notify(); };
    const error = failure => { inputError = failure; notify(); };
    const aborted = () => error(bad());
    req.on('readable', notify); req.once('end', end); req.once('error', error); req.once('aborted', aborted);
    signal?.addEventListener('abort', notify, { once: true });
    try {
      for (;;) {
        signal?.throwIfAborted();
        if (req.aborted || (req.destroyed && !req.readableEnded)) throw bad();
        if (inputError) throw inputError;
        const chunk = req.read(chunkBytes);
        if (chunk !== null) {
          if (!(chunk instanceof Uint8Array)) throw bad();
          state.bytes += chunk.byteLength;
          if (state.bytes > maxBytes) throw new UploadError('NATIVE_UPLOAD_TOO_LARGE', 413, 'large');
          yield chunk;
        } else if (ended || req.readableEnded) {
          if (expectedBytes !== undefined && state.bytes !== expectedBytes) throw bad();
          state.complete = true;
          return;
        } else await new Promise(resolve => { wake = resolve; });
      }
    } catch (failure) { state.error = failure; throw failure; }
    finally {
      req.removeListener('readable', notify); req.removeListener('end', end);
      req.removeListener('error', error); req.removeListener('aborted', aborted);
      signal?.removeEventListener('abort', notify);
    }
  })();
  return state;
}
function receipt(value, upload) {
  const parsed = receiptCodec.parse(value);
  if (!upload.complete || !opaque(parsed.receiptId) || !opaque(parsed.file.attachmentId) || !filename(parsed.file.name)
    || !Number.isSafeInteger(parsed.file.bytes) || parsed.file.bytes !== upload.bytes || parsed.file.bytes > maxBytes) throw failed();
  return { receiptId: parsed.receiptId,
    file: { attachmentId: parsed.file.attachmentId, name: parsed.file.name, bytes: parsed.file.bytes } };
}

/** Called after native-api's shared loopback authentication has succeeded. */
export function createNativeWorkbenchMedia(ctx) {
  return {
    async handle(req, res, signal) {
      let upload;
      try {
        const input = request(req); signal?.throwIfAborted();
        const uploads = service(ctx, 'fileUploads', 'uploadStream');
        await target(ctx, input.sessionId, signal); signal?.throwIfAborted();
        upload = intake(req, input.expectedBytes, signal);
        const value = await uploads.uploadStream({ sessionId: input.sessionId, name: input.name, data: upload.data, signal });
        // A committed, staged receipt remains true even if cancellation follows
        // completion. No prompt or Session creation belongs to this operation.
        reply(res, 200, { ok: true, result: receipt(value, upload) });
      } catch (error) {
        req.resume();
        // FileUploads wraps storage/stream errors; preserve our own bounded
        // intake failure so an oversized body still gets the correct response.
        errorReply(ctx, res, upload?.error ?? error, signal);
      } finally { await upload?.data.return(); }
    },
  };
}
