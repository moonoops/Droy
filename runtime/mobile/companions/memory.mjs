import { join, isAbsolute } from 'node:path';
import { existsSync } from 'node:fs';
import { randomUUID } from 'node:crypto';
import { MemosMemoryStore } from '@droyagent/memory/memos';
import { assertCompanionId } from './profiles.mjs';
import { CompanionMemorySpaces } from './memory-spaces.mjs';

const fail = code => { throw Object.assign(new Error(code), { code }); };
function localId(value) {
  if (typeof value !== 'string' || !/^[A-Za-z0-9_.:-]{1,192}$/.test(value)) fail('COMPANION_INVALID_ID');
  return value;
}
function memoryText(value, maxBytes=16384) {
  if (typeof value !== 'string' || !value.trim() || Buffer.byteLength(value) > maxBytes) fail('COMPANION_INVALID_INPUT');
  if (/(?:\bBearer\s+\S+|\bsk-[\w-]{8,}|-----BEGIN [A-Z ]*PRIVATE KEY-----|(?:api[_ -]?key|password|access[_ -]?token|密码|令牌)\s*[:=：]\s*\S+)/iu.test(value)) fail('COMPANION_MEMORY_CREDENTIAL');
  return value;
}

/** A fixed vault binding fences asynchronous writes after retention or purge. */
export class CompanionMemory {
  #store; #id; #spaces; #binding; #embed; #closed = false;
  #jobs = new Set(); #indexing = new Set(); #indexed = new Set(); #abort = new AbortController(); #indexStatus = 'lexical'; #validateSources;
  #sweeping = false;
  static async open({ directory, companionId, profile, spaces, embed, validateSources } = {}) {
    assertCompanionId(companionId);
    if (typeof directory !== 'string' || !isAbsolute(directory)) fail('COMPANION_INVALID_DIRECTORY');
    spaces ??= await CompanionMemorySpaces.open({ directory });
    const binding = await spaces.ensureActiveSpace(companionId, { profile });
    const scopedDirectory = binding.directory;
    const initialize = !existsSync(join(scopedDirectory, 'memory.sqlite')) && !existsSync(scopedDirectory + '.identity.json');
    const store = await MemosMemoryStore.open({ directory: scopedDirectory, initialize, companionId, memorySpaceId: binding.memorySpaceId });
    return new CompanionMemory(store, companionId, { spaces, binding, embed, validateSources });
  }
  constructor(store, companionId, { spaces, binding, embed, validateSources }) {
    this.#store = store; this.#id = companionId; this.#spaces = spaces; this.#binding = binding; this.#embed = embed; this.#validateSources = validateSources;
    this.#sweep();
  }
  get companionId() { return this.#id; }
  get memorySpaceId() { return this.#binding.memorySpaceId; }
  #run(operation) {
    if (this.#closed) fail('COMPANION_CLOSED');
    return this.#spaces.withActive(this.#binding, operation);
  }
  sourceRef(messageId) { return `im:${this.#id}:${localId(messageId)}`; }
  #sources(sources = []) {
    if (!Array.isArray(sources)) fail('COMPANION_INVALID_INPUT');
    return sources.map(source => {
      localId(source);
      if (source.startsWith('im:')) {
        if (!source.startsWith(`im:${this.#id}:`)) fail('COMPANION_SOURCE_SCOPE');
        return source;
      }
      return this.sourceRef(source);
    });
  }
  #record(record) { return record ? { ...record, companionId: this.#id } : null; }
  async status() { return this.#run(async () => ({ ...await this.#store.status(), companionId: this.#id,
    memorySpaceId: this.memorySpaceId, embedding: this.#indexStatus })); }
  async get(recordId) { return this.#run(async () => this.#record(await this.#store.get({ recordId }))); }
  async query(input = {}) {
    return this.#run(async () => {
      const result = await this.#store.query(input);
      return { ...result, items: result.items.map(record => this.#record(record)) };
    });
  }
  async search({ query, text = query, signal, ...input } = {}) {
    signal?.throwIfAborted();
    await this.#run(()=>undefined);
    let embedding;
    if (this.#embed && text) {
      try { embedding = await this.#embed({ ...this.#binding, text, role: 'query', signal: signal ? AbortSignal.any([signal, this.#abort.signal]) : this.#abort.signal }); }
      catch (error) {
        signal?.throwIfAborted();this.#abort.signal.throwIfAborted();
        if(!['EMBEDDING_MODEL_UNAVAILABLE','EMBEDDING_PAUSED','EMBEDDING_CANCELLED','EMBEDDING_DEADLINE','EMBEDDING_BUSY','COMPANION_EMBEDDING_BUSY'].includes(error.code)
          &&error.name!=='TimeoutError')throw error;
        this.#indexStatus=error.code??'EMBEDDING_DEADLINE';
      }
    }
    const result = await this.#run(() => this.#store.search({ ...input, text,
      ...(embedding ? { vector: embedding.vector, model: embedding.fingerprint } : {}) }));
    signal?.throwIfAborted();
    for (const record of result.items) this.#index(record);
    this.#sweep();
    return { ...result, embedding: this.#indexStatus, items: result.items.map(record => this.#record(record)) };
  }
  #sweep() {
    if(!this.#embed?.status||this.#closed||this.#sweeping)return;
    this.#sweeping=true;
    const work=(async()=>{
      const status=await this.#embed.status(this.#abort.signal);
      if(!status.ready){this.#indexStatus=status.reason??'EMBEDDING_MODEL_UNAVAILABLE';return;}
      if(typeof status.fingerprint!=='string'||!status.fingerprint)fail('COMPANION_EMBEDDING_INVALID_RESULT');
      while(!this.#closed) {
        const page=await this.#run(()=>this.#store.pendingEmbeddings({model:status.fingerprint,limit:4}));
        const records=Array.isArray(page)?page:page.items;
        if(!records.length)break;
        for(const record of records) {
          this.#abort.signal.throwIfAborted();
          const result=await this.#embed({...this.#binding,text:record.payload.text,role:'document',signal:this.#abort.signal});
          if(result.fingerprint!==status.fingerprint)fail('COMPANION_EMBEDDING_INVALID_RESULT');
          try {await this.#run(()=>this.#store.setEmbedding({recordId:record.recordId,expectedRevisionId:record.revisionId,vector:result.vector,model:result.fingerprint}));}
          catch(error){if(!['MEMORY_REVISION_CONFLICT','MEMORY_NOT_FOUND','MEMORY_SOURCE_CHANGED'].includes(error.code))throw error;}
        }
        this.#indexStatus='ready';
      }
    })();
    this.#jobs.add(work);
    work.catch(error=>{this.#indexStatus=error.code??'EMBEDDING_UNAVAILABLE';}).finally(()=>{this.#jobs.delete(work);this.#sweeping=false;});
  }
  #index(record) {
    if (!this.#embed || this.#closed || !record?.payload?.text || !['active', 'candidate'].includes(record.state)) return;
    const key=record.recordId+':'+record.revisionId;
    if(this.#indexing.has(key)||this.#indexed.has(key))return;
    this.#indexing.add(key);
    const work = (async () => {
      const result = await this.#embed({ ...this.#binding, text: record.payload.text, role: 'document', signal: this.#abort.signal });
      await this.#run(() => this.#store.setEmbedding({ recordId: record.recordId, expectedRevisionId: record.revisionId,
        vector: result.vector, model: result.fingerprint }));
      this.#indexStatus = 'ready';
      this.#indexed.add(key);
    })();
    this.#jobs.add(work);
    work.catch(error => { this.#indexStatus = error.code ?? 'EMBEDDING_UNAVAILABLE'; }).finally(() => {this.#jobs.delete(work);this.#indexing.delete(key);});
  }
  #evidence(evidence = []) {
    if (!Array.isArray(evidence)) fail('COMPANION_INVALID_INPUT');
    return evidence.map(item => ({ ...item, sourceRef: this.#sources([item.sourceRef])[0], text: memoryText(item.text,196608) }));
  }
  async remember({ text, kind = 'fact', sourceRefs = [], assertion = 'user_statement', derivedFrom = [], operationId = randomUUID(), occurredAt, state = 'active', evidence = [] } = {}) {
    memoryText(text);
    const result = await this.#run(async () => {
      await this.#validateSources?.(this.#sources(sourceRefs),this.#binding);
      const value = await this.#store.record({ operationId, evidence: this.#evidence(evidence), record: { kind, payload: kind === 'event' ? { eventType: 'note', text } : { text }, sourceRefs: this.#sources(sourceRefs),
        assertion, derivedFrom, state, ...(occurredAt ? { occurredAt } : {}) } });
      return { ...value, record: this.#record(await this.#store.get({ recordId: value.recordId })) };
    });
    this.#index(result.record); return result;
  }
  async revise({ recordId, expectedRevisionId, text, patch = {}, operationId = randomUUID(), evidence = [] } = {}) {
    const next = { ...patch };
    if (text !== undefined) {
      const old = await this.get(recordId);
      next.payload = { ...(old?.kind === 'event' ? old.payload : {}), text: memoryText(text) };
    }
    if (next.payload?.text !== undefined) memoryText(next.payload.text);
    if (next.sourceRefs !== undefined) next.sourceRefs = this.#sources(next.sourceRefs);
    const result = await this.#run(async () => {
      if(next.sourceRefs) {
        const old=await this.#store.get({recordId});
        await this.#validateSources?.(next.sourceRefs.filter(source=>!old?.sourceRefs?.includes(source)),this.#binding);
      }
      const value = await this.#store.revise({ operationId, recordId, expectedRevisionId, patch: next, evidence: this.#evidence(evidence) });
      return { ...value, record: this.#record(await this.#store.get({ recordId: value.recordId })) };
    });
    this.#index(result.record); return result;
  }
  async suppress(input) { return this.#run(() => this.#store.suppress(input)); }
  async forget({ recordId, expectedRevisionId, operationId = randomUUID() } = {}) {
    return this.#run(async () => {
    const result = await this.#store.forget({ operationId, recordId, expectedRevisionId });
    const current = await this.#store.get({ recordId });
    const forgottenSources = [];
    let afterSource;
    do {
      const plan = await this.#store.forgetPlan({ recordId, expectedRevisionId: current.revisionId, ...(afterSource ? { afterSource } : {}) });
      forgottenSources.push(...plan.sourceRefs);
      afterSource = plan.hasMore ? plan.nextSource : undefined;
    } while (afterSource);
    return { ...result, forgottenSources:[...new Set([...(result.forgottenSources??[]),...forgottenSources])] };
    });
  }
  async close() {
    if (this.#closed) return;
    this.#closed = true; this.#abort.abort();
    await Promise.allSettled([...this.#jobs]);
    await this.#store.close();
  }
}
