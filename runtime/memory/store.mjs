import { Worker } from 'node:worker_threads';
import { isAbsolute } from 'node:path';
import { MemoryError } from './schema.mjs';

export class MemoryStore {
  #worker; #pending = new Map(); #id = 0; #limit; #failed; #closing = false; #closePromise;
  static async open({ directory, initialize = false, maxPending = 64, maxBytes = 268435456, minFreeBytes = 16777216 } = {}) {
    if (typeof directory !== 'string' || !isAbsolute(directory) || typeof initialize !== 'boolean'
      || !Number.isInteger(maxPending) || maxPending < 1 || maxPending > 256
      || !Number.isSafeInteger(maxBytes) || maxBytes < 1048576 || !Number.isSafeInteger(minFreeBytes) || minFreeBytes < 0) throw new MemoryError('MEMORY_INVALID_INPUT');
    const store = new MemoryStore({ directory, initialize, maxPending, maxBytes, minFreeBytes });
    try { await store.status(); return store; } catch (error) { await store.#worker.terminate(); throw error; }
  }
  constructor(options) {
    if (process.permission) throw new MemoryError('MEMORY_UNSUPPORTED_RUNTIME_PERMISSIONS');
    this.#limit = options.maxPending;
    this.#worker = new Worker(new URL('./store-worker.mjs', import.meta.url), {
      execArgv: [], workerData: options, resourceLimits: { maxOldGenerationSizeMb: 64 },
    });
    this.#worker.on('message', message => {
      if (message.fatal) { this.#fail(message.code); return; }
      const pending = this.#pending.get(message.id); if (!pending) return;
      this.#pending.delete(message.id);
      if (message.ok) pending.resolve(message.value); else pending.reject(new MemoryError(message.code));
    });
    this.#worker.on('error', () => this.#fail('MEMORY_WORKER_FAILED'));
    this.#worker.on('exit', code => { if (!this.#closing || code !== 0 || this.#pending.size) this.#fail('MEMORY_WORKER_EXITED'); });
  }
  #fail(code) { this.#failed ??= new MemoryError(code); for (const item of this.#pending.values()) item.reject(this.#failed); this.#pending.clear(); }
  #request(operation, args = {}, closing = false) {
    if (this.#failed) return Promise.reject(this.#failed);
    if (this.#closing && !closing) return Promise.reject(new MemoryError('MEMORY_CLOSED'));
    if (this.#pending.size >= this.#limit) return Promise.reject(new MemoryError('MEMORY_BUSY'));
    try { if (Buffer.byteLength(JSON.stringify(args)) > 262144) throw new Error(); }
    catch { return Promise.reject(new MemoryError('MEMORY_REQUEST_TOO_LARGE')); }
    const id = ++this.#id;
    return new Promise((resolve, reject) => {
      this.#pending.set(id, { resolve, reject });
      try { this.#worker.postMessage({ id, operation, args }); }
      catch { this.#pending.delete(id); reject(new MemoryError('MEMORY_INVALID_INPUT')); }
    });
  }
  status() { return this.#request('status'); }
  record(args) { return this.#request('record', args); }
  revise(args) { return this.#request('revise', args); }
  forget(args) { return this.#request('forget', args); }
  forgetPlan(args) { return this.#request('forget-plan', args); }
  get(args) { return this.#request('get', args); }
  query(args = {}) { return this.#request('query', args); }
  aggregate(args = {}) { return this.#request('aggregate', args); }
  ingest(args) { return this.#request('ingest', args); }
  getSourceCursor(args) { return this.#request('source-cursor', args); }
  maintenancePlan(args = {}) { return this.#request('maintenance-plan', args); }
  maintain(args = {}) { return this.#request('maintain', args); }
  changes(args = {}) { return this.#request('changes', args); }
  backup(args) { return this.#request('backup', args); }
  exportArchive(args) { return this.#request('export', args); }
  importArchive(args) { return this.#request('import', args); }
  close() {
    if (this.#closePromise) return this.#closePromise;
    this.#closing = true;
    this.#closePromise = (async () => {
      await Promise.allSettled([...this.#pending.values()].map(pending => new Promise(resolve => {
        const success = pending.resolve, failure = pending.reject;
        pending.resolve = v => { success(v); resolve(); }; pending.reject = e => { failure(e); resolve(); };
      })));
      try { if (!this.#failed) await this.#request('close', {}, true); }
      finally { await this.#worker.terminate(); }
    })(); return this.#closePromise;
  }
}
