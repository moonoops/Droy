// Executed from --eval in a fresh, permission-restricted Node process. On Android,
// ComputeService also runs under an isolated UID with no application permissions.
import vm from 'node:vm';

const limits = Object.freeze({ code: 16384, input: 16384, stdout: 32768, result: 32768,
  artifact: 65536, artifacts: 8, artifactTotal: 131072 });

function failure(error, status = 'failed') {
  const message = String(error?.message || error).slice(0, 1000);
  return { status, error: message, stdout: '', artifacts: [] };
}

export function runCompute(request) {
  try {
    if (!request || typeof request !== 'object' || typeof request.code !== 'string') throw new Error('code must be a string');
    if (Buffer.byteLength(request.code, 'utf8') > limits.code) throw new Error('code exceeds 16384 bytes');
    const serializedInput = JSON.stringify(request.input ?? null);
    if (Buffer.byteLength(serializedInput, 'utf8') > limits.input) throw new Error('input exceeds 16384 bytes');
    const timeout = request.timeoutMs ?? 5000;
    if (!Number.isInteger(timeout) || timeout < 100 || timeout > 30000) throw new Error('timeoutMs must be 100..30000');
    const context = vm.createContext(Object.create(null), {
      name: 'droy-compute', codeGeneration: { strings: false, wasm: false }, microtaskMode: 'afterEvaluate',
    });
    // Every function and object exposed to user code is created inside this realm.
    // A host-realm console callback would expose its Function constructor.
    new vm.Script(`(() => {
      const stringify = JSON.stringify.bind(JSON);
      let stdout = '', artifacts = [], total = 0;
      const byteLength = text => {
        let count = 0;
        for (const character of text) { const point = character.codePointAt(0); count += point <= 127 ? 1 : point <= 2047 ? 2 : point <= 65535 ? 3 : 4; }
        return count;
      };
      const log = (...values) => {
        const line = values.map(value => typeof value === 'string' ? value : stringify(value)).join(' ') + '\\n';
        if (byteLength(stdout) + byteLength(line) > ${limits.stdout}) throw new Error('stdout limit exceeded');
        stdout += line;
      };
      const save = (name, text, mime = 'text/plain') => {
        if (typeof name !== 'string' || !/^[a-zA-Z0-9][a-zA-Z0-9_.-]{0,79}$/.test(name) || name.includes('..') || name === 'job.json') throw new Error('Invalid artifact name');
        if (typeof text !== 'string') throw new Error('artifact content must be text');
        if (!['text/plain', 'text/csv', 'application/json', 'text/markdown'].includes(mime)) throw new Error('Unsupported artifact MIME type');
        const bytes = byteLength(text);
        if (bytes > ${limits.artifact} || artifacts.length >= ${limits.artifacts} || total + bytes > ${limits.artifactTotal}) throw new Error('artifact limit exceeded');
        if (artifacts.some(item => item.name === name)) throw new Error('Duplicate artifact name');
        artifacts.push({ name, text, mime, bytes }); total += bytes;
        return name;
      };
      const take = result => {
        if (result && typeof result.then === 'function') throw new Error('Async results are not supported');
        const serialized = stringify(result === undefined ? null : result);
        if (byteLength(serialized) > ${limits.result}) throw new Error('result limit exceeded');
        return stringify({status: 'completed', result: JSON.parse(serialized), stdout, artifacts});
      };
      Object.defineProperties(globalThis, {
        input: { value: JSON.parse(${JSON.stringify(serializedInput)}), writable: false, configurable: false },
        console: { value: Object.freeze({log, info: log, warn: log, error: log}), writable: false, configurable: false },
        artifact: { value: save, writable: false, configurable: false },
        __droy_take: { value: take, writable: false, configurable: false },
      });
      // JSON/text jobs do not need external backing stores, which are outside
      // V8's old-space heap budget. Keep these allocation APIs unavailable.
      for (const name of ['ArrayBuffer', 'SharedArrayBuffer', 'DataView', 'Int8Array', 'Uint8Array', 'Uint8ClampedArray', 'Int16Array', 'Uint16Array', 'Int32Array', 'Uint32Array', 'Float16Array', 'Float32Array', 'Float64Array', 'BigInt64Array', 'BigUint64Array', 'WebAssembly', 'Atomics']) {
        Object.defineProperty(globalThis, name, {value: undefined, writable: false, configurable: false});
      }
    })()`, { filename: 'droy-compute-bootstrap' }).runInContext(context, { timeout });
    const result = new vm.Script(`__droy_take((() => {\n${request.code}\n})())`, { filename: 'compute-job.js' })
      .runInContext(context, { timeout });
    return JSON.parse(result);
  } catch (error) {
    return failure(error, error?.code === 'ERR_SCRIPT_EXECUTION_TIMEOUT' ? 'timed_out' : 'failed');
  }
}

// Android supplies no module filename (--eval); host tests import this module.
if (process.argv[1] === undefined) {
  let input = '';
  process.stdin.setEncoding('utf8');
  process.stdin.on('data', chunk => {
    input += chunk;
    if (Buffer.byteLength(input, 'utf8') > 120000) process.exit(65);
  });
  process.stdin.on('end', () => {
    let response;
    try { response = runCompute(JSON.parse(input)); } catch (error) { response = failure(error); }
    process.stdout.write(JSON.stringify(response));
  });
}
