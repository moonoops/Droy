import { createInterface } from 'node:readline';
import { createCompanionDeletionMaintenance } from './maintenance-delete.mjs';

// Only the Android owner holding the runtime exclusion lock can answer each
// authority challenge. Closing its pipe revokes the capability immediately.
const input = createInterface({ input: process.stdin, crlfDelay: Infinity });
const buffered = [], waiting = [];
let closed = false;
input.on('line', line => waiting.length ? waiting.shift().resolve(line) : buffered.push(line));
input.on('close', () => { closed = true; for (const waiter of waiting.splice(0)) waiter.reject(new Error('MAINTENANCE_OWNER_LOST')); });
const read = async () => buffered.length ? buffered.shift() : closed ? Promise.reject(new Error('MAINTENANCE_OWNER_LOST'))
  : new Promise((resolve, reject) => waiting.push({ resolve, reject }));
const send = value => process.stdout.write(JSON.stringify(value) + '\n');
send({ kind: 'started', pid: process.pid });
try {
  const value = JSON.parse(await read());
  if (!value || !['commit', 'acknowledge'].includes(value.action)) throw new Error('MAINTENANCE_COMMAND_INVALID');
  let sequence = 0;
  const authority = { async assertHeld(request) {
    if (closed) throw new Error('MAINTENANCE_OWNER_LOST');
    const nonce = ++sequence; send({ kind: 'authority', nonce, request });
    const reply = JSON.parse(await read());
    if (reply.kind !== 'authority' || reply.nonce !== nonce || reply.held !== true || closed) throw new Error('MAINTENANCE_OWNER_LOST');
    return { ...request, hostStopped: true, admissionsClosed: true };
  } };
  const operation = await createCompanionDeletionMaintenance({ ...value, authority });
  if (value.action === 'commit') await operation.prepare();
  const receipt = value.action === 'acknowledge' ? await operation.acknowledgeAndroid(value.androidReceipt)
    : await operation.commit();
  send({ kind: 'result', receipt });
} catch (error) {
  send({ kind: 'error', code: /^[A-Z_]+$/.test(error.code ?? error.message ?? '') ? error.code ?? error.message : 'COMPANION_MAINTENANCE_FAILED' });
  process.exitCode = 1;
} finally { input.close(); process.stdin.destroy(); }
