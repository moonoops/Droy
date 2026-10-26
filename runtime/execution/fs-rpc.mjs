import { realpathSync } from 'node:fs';
import { fileURLToPath } from 'node:url';

const METHODS = new Set(['resolve', 'stat', 'lstat', 'readText', 'streamText', 'readBytes',
  'readByteRange', 'listDir', 'writeText', 'editText']);
const MAX_REQUEST_BYTES = 16 * 1024 * 1024;
function encode(value) {
  if (value instanceof Uint8Array) return { $bytes: Buffer.from(value).toString('base64') };
  return value;
}

/** Runs only inside the execution namespace. It never opens a host-side path. */
export async function runFileRequest(fs, request, emit) {
  if (!request || !METHODS.has(request.method) || !Array.isArray(request.args)) throw new Error('EXECUTION_INVALID_FS_REQUEST');
  const value = await fs[request.method](...request.args);
  if (request.method === 'streamText') {
    for await (const text of value) await emit({ kind: 'chunk', text });
    await emit({ kind: 'result' });
  } else await emit({ kind: 'result', value: encode(value) });
}

async function main() {
  const [{ Context }, { default: LocalFileSystem }] = await Promise.all([
    import('@deepseek-ai/cordis'), import('@deepseek-ai/dsh-fs-sandbox'),
  ]);
  const context = new Context();
  let bytes = 0, request = '';
  try {
    process.stdin.setEncoding('utf8');
    for await (const chunk of process.stdin) {
      bytes += Buffer.byteLength(chunk);
      if (bytes > MAX_REQUEST_BYTES) throw new Error('EXECUTION_FS_REQUEST_TOO_LARGE');
      request += chunk.toString('utf8');
    }
    const parsed = JSON.parse(request);
    if (!['read-only', 'workspace-write', 'danger-full-access'].includes(parsed.policy?.mode)) throw new Error('EXECUTION_INVALID_FS_POLICY');
    context.provide('sandboxPolicy', { defaultMode: parsed.policy.mode, resolve: () => parsed.policy });
    await context.plugin(LocalFileSystem, { cwd: process.cwd(), newFileMode: 0o660 });
    await runFileRequest(context.fs, parsed, async value => {
      if (!process.stdout.write(JSON.stringify(value) + '\n')) await new Promise(resolve => process.stdout.once('drain', resolve));
    });
  } catch (error) {
    const code = typeof error?.code === 'string' && /^FS_[A-Z_]+$/.test(error.code) ? error.code : 'EXECUTION_FS_FAILED';
    process.stdout.write(JSON.stringify({ kind: 'error', code }) + '\n');
    process.exitCode = 1;
  } finally { await context.fiber.dispose(); }
}

if (process.argv[1] && realpathSync(fileURLToPath(import.meta.url)) === realpathSync(process.argv[1])) await main();
