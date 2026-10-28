import { join, isAbsolute } from 'node:path';
import { existsSync } from 'node:fs';
import { randomUUID } from 'node:crypto';
import { MemoryStore } from '@droyagent/memory/store';
import { assertCompanionId } from './profiles.mjs';

const fail = code => { throw Object.assign(new Error(code), { code }); };
function localId(value) {
  if (typeof value !== 'string' || !/^[A-Za-z0-9_.:-]{1,192}$/.test(value)) fail('COMPANION_INVALID_ID');
  return value;
}
function memoryText(value) {
  if (typeof value !== 'string' || !value.trim() || Buffer.byteLength(value) > 16384) fail('COMPANION_INVALID_INPUT');
  if (/(?:\bBearer\s+\S+|\bsk-[\w-]{8,}|-----BEGIN [A-Z ]*PRIVATE KEY-----|(?:api[_ -]?key|password|access[_ -]?token|密码|令牌)\s*[:=：]\s*\S+)/iu.test(value)) fail('COMPANION_MEMORY_CREDENTIAL');
  return value;
}

/** The product binds one instance to one immutable companion identity. The
 * existing local store provides revision, lineage and forget semantics without
 * depending on the legacy capture/protected-execution activation lifecycle. */
export class CompanionMemory {
  #store; #id;
  static async open({ directory, companionId } = {}) {
    assertCompanionId(companionId);
    if (typeof directory !== 'string' || !isAbsolute(directory)) fail('COMPANION_INVALID_DIRECTORY');
    const scopedDirectory = join(directory, companionId, 'memory');
    const initialize = !existsSync(join(scopedDirectory, 'memory.sqlite')) && !existsSync(scopedDirectory + '.identity.json');
    const store = await MemoryStore.open({ directory: scopedDirectory, initialize });
    return new CompanionMemory(store, companionId);
  }
  constructor(store, companionId) { this.#store = store; this.#id = companionId; }
  get companionId() { return this.#id; }
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
  async status() { return { ...await this.#store.status(), companionId: this.#id }; }
  async get(recordId) { return this.#record(await this.#store.get({ recordId })); }
  async query(input = {}) {
    const result = await this.#store.query(input);
    return { ...result, items: result.items.map(record => this.#record(record)) };
  }
  async remember({ text, kind = 'fact', sourceRefs = [], assertion = 'user_statement', derivedFrom = [], operationId = randomUUID(), occurredAt, state = 'active' } = {}) {
    memoryText(text);
    const result = await this.#store.record({ operationId, record: { kind, payload: kind === 'event' ? { eventType: 'note', text } : { text }, sourceRefs: this.#sources(sourceRefs),
      assertion, derivedFrom, state, ...(occurredAt ? { occurredAt } : {}) } });
    return { ...result, record: await this.get(result.recordId) };
  }
  async revise({ recordId, expectedRevisionId, text, patch = {}, operationId = randomUUID() } = {}) {
    const next = { ...patch };
    if (text !== undefined) {
      const old = await this.get(recordId);
      next.payload = { ...(old?.kind === 'event' ? old.payload : {}), text: memoryText(text) };
    }
    if (next.payload?.text !== undefined) memoryText(next.payload.text);
    if (next.sourceRefs !== undefined) next.sourceRefs = this.#sources(next.sourceRefs);
    const result = await this.#store.revise({ operationId, recordId, expectedRevisionId, patch: next });
    return { ...result, record: await this.get(result.recordId) };
  }
  async forget({ recordId, expectedRevisionId, operationId = randomUUID() } = {}) {
    const result = await this.#store.forget({ operationId, recordId, expectedRevisionId });
    const current = await this.#store.get({ recordId });
    const forgottenSources = [];
    let afterSource;
    do {
      const plan = await this.#store.forgetPlan({ recordId, expectedRevisionId: current.revisionId, ...(afterSource ? { afterSource } : {}) });
      forgottenSources.push(...plan.sourceRefs);
      afterSource = plan.hasMore ? plan.nextSource : undefined;
    } while (afterSource);
    return { ...result, forgottenSources };
  }
  close() { return this.#store.close(); }
}
