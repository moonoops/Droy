import { Worker } from 'node:worker_threads';
import { isAbsolute } from 'node:path';
import { PrivacyError } from './policy.mjs';

const MAX_REQUEST_BYTES = 65536;

// This service is for trusted host code. It is not a set of model-facing tools:
// session registration and endpoint grants must come from product authority.
export class PrivacyStore {
  #worker;
  #pending = new Map();
  #next = 0;
  #limit;
  #failure;
  #closing = false;
  #closePromise;

  static async open({ directory, markerPath, initialize = false, maxPending = 64 } = {}) {
    if (typeof directory !== 'string' || typeof markerPath !== 'string'
      || !isAbsolute(directory) || !isAbsolute(markerPath) || typeof initialize !== 'boolean'
      || !Number.isInteger(maxPending) || maxPending < 1 || maxPending > 256) throw new PrivacyError('PRIVACY_INVALID_INPUT');
    const store = new PrivacyStore(directory, markerPath, initialize, maxPending);
    try { await store.#request('status', {}); return store; }
    catch (error) { await store.#worker.terminate(); throw error; }
  }

  constructor(directory, markerPath, initialize, maxPending) {
    // An empty worker execArgv disables Node's host permission mode. Refuse
    // that unsupported combination instead of silently widening access.
    if (process.permission) throw new PrivacyError('PRIVACY_UNSUPPORTED_RUNTIME_PERMISSIONS');
    this.#limit = maxPending;
    // The worker loads only this package and built-in modules. Host loaders and
    // process-wide/V8 flags (expanded by Android Node) must not enter it.
    this.#worker = new Worker(new URL('./store-worker.mjs', import.meta.url), {
      execArgv: [],
      workerData: { directory, markerPath, initialize }, resourceLimits: { maxOldGenerationSizeMb: 32 },
    });
    this.#worker.on('message', message => {
      if (message.fatal) { this.#fail(new PrivacyError(message.code)); return; }
      const pending = this.#pending.get(message.id);
      if (!pending) return;
      this.#pending.delete(message.id);
      if (message.ok) pending.resolve(message.value);
      else pending.reject(new PrivacyError(message.code));
    });
    this.#worker.on('error', () => this.#fail(new PrivacyError('PRIVACY_WORKER_FAILED')));
    this.#worker.on('exit', code => {
      if (!this.#closing || code !== 0 || this.#pending.size) this.#fail(new PrivacyError('PRIVACY_WORKER_EXITED'));
    });
  }

  #fail(error) {
    this.#failure ??= error;
    for (const pending of this.#pending.values()) pending.reject(this.#failure);
    this.#pending.clear();
  }

  #request(operation, args, closing = false) {
    if (this.#failure) return Promise.reject(this.#failure);
    if (this.#closing && !closing) return Promise.reject(new PrivacyError('PRIVACY_STORE_CLOSED'));
    if (this.#pending.size >= this.#limit) return Promise.reject(new PrivacyError('PRIVACY_BUSY'));
    let bytes;
    try { bytes = Buffer.byteLength(JSON.stringify(args)); }
    catch { return Promise.reject(new PrivacyError('PRIVACY_INVALID_INPUT')); }
    if (bytes > MAX_REQUEST_BYTES) return Promise.reject(new PrivacyError('PRIVACY_REQUEST_TOO_LARGE'));
    const id = ++this.#next;
    return new Promise((resolve, reject) => {
      this.#pending.set(id, { resolve, reject });
      try { this.#worker.postMessage({ id, operation, args }); }
      catch { this.#pending.delete(id); reject(new PrivacyError('PRIVACY_INVALID_INPUT')); }
    });
  }

  status() { return this.#request('status', {}); }
  registerCleanSession(sessionId) { return this.#request('register-clean', { sessionId }); }
  protectSession(sessionId, { categories, sourceRefs = [] } = {}) {
    return this.#request('protect', { sessionId, categories, sourceRefs });
  }
  inheritSession(parentId, childId) { return this.#request('inherit', { parentId, childId }); }
  forgetSessions(sessionIds) { return this.#request('forget', { sessionIds }); }
  forgetSources(sourceRefs) { return this.#request('forget-sources', { sourceRefs }); }
  sessionState(sessionId) { return this.#request('session', { sessionId }); }
  grantEndpoint({ url, purpose, configVersion, categories, ...unexpected } = {}) {
    return this.#request('grant', { endpoint: { url, purpose, configVersion, ...unexpected }, categories });
  }
  revokeEndpoint(endpoint) { return this.#request('revoke', { endpoint }); }
  listGrants() { return this.#request('grants', {}); }
  assertAllowed({ sessionId, endpoint } = {}) { return this.#request('authorize', { sessionId, endpoint }); }

  close() {
    if (this.#closePromise) return this.#closePromise;
    this.#closing = true;
    this.#closePromise = (async () => {
      // A full queue still needs to drain, rather than silently dropping writes.
      await Promise.allSettled([...this.#pending.values()].map(pending => new Promise(resolve => {
        const success = pending.resolve, failure = pending.reject;
        pending.resolve = value => { success(value); resolve(); };
        pending.reject = error => { failure(error); resolve(); };
      })));
      try { if (!this.#failure) await this.#request('close', {}, true); }
      finally { await this.#worker.terminate(); }
    })();
    return this.#closePromise;
  }
}
