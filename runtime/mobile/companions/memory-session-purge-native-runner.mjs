import { createInterface } from 'node:readline';
import { runOfflineCompanionMemorySessionPurge } from './memory-session-purge.mjs';

const input = createInterface({ input: process.stdin, crlfDelay: Infinity });
const buffered = [], waiting = [];
let closed = false;
input.on('line', line => waiting.length ? waiting.shift().resolve(line) : buffered.push(line));
input.on('close', () => { closed = true; for (const waiter of waiting.splice(0)) waiter.reject(new Error('COMPANION_MEMORY_SESSION_AUTHORITY_LOST')); });
const read = async () => buffered.length ? buffered.shift() : closed ? Promise.reject(new Error('COMPANION_MEMORY_SESSION_AUTHORITY_LOST'))
  : new Promise((resolve, reject) => waiting.push({ resolve, reject }));
const send = value => process.stdout.write(JSON.stringify(value) + '\n');
send({ kind: 'started', pid: process.pid });
try {
  const value = JSON.parse(await read());
  if (!value || value.action !== 'commit') throw new Error('COMPANION_MEMORY_SESSION_COMMAND_INVALID');
  let sequence = 0;
  const authority = { async assertHeld(request) {
    if (closed) throw new Error('COMPANION_MEMORY_SESSION_AUTHORITY_LOST');
    const nonce = ++sequence; send({ kind: 'authority', nonce, request });
    const reply = JSON.parse(await read());
    if (reply.kind !== 'authority' || reply.nonce !== nonce || reply.held !== true || closed) throw new Error('COMPANION_MEMORY_SESSION_AUTHORITY_LOST');
    return { ...request, hostStopped: true, admissionsClosed: true };
  } };
  const receipt = await runOfflineCompanionMemorySessionPurge({ ...value, authority });
  send({ kind: 'result', receipt });
} catch (error) {
  send({ kind: 'error', code: /^[A-Z_]+$/.test(error.code ?? error.message ?? '') ? error.code ?? error.message : 'COMPANION_MEMORY_SESSION_PURGE_FAILED' });
  process.exitCode = 1;
} finally { input.close(); process.stdin.destroy(); }
