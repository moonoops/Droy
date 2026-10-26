export const MEMORY_SCHEMA_VERSION = 1;
export const RECORD_KINDS = Object.freeze(['fact', 'preference', 'decision', 'event', 'task_summary']);
export const EVENT_TYPES = Object.freeze(['activity', 'location', 'device', 'visit', 'gap', 'note']);
export class MemoryError extends Error {
  constructor(code) { super(code); this.name = 'MemoryError'; this.code = code; }
}
export function fail(code = 'MEMORY_INVALID_INPUT') { throw new MemoryError(code); }
export function fields(value, allowed) {
  if (!value || typeof value !== 'object' || Array.isArray(value)
    || Object.keys(value).some(key => !allowed.includes(key))) fail();
}
export function identity(value) {
  if (typeof value !== 'string' || !/^[A-Za-z0-9_.:-]{1,160}$/.test(value)) fail('MEMORY_INVALID_ID');
  return value;
}
export function text(value, maxBytes = 16384, empty = false) {
  if (typeof value !== 'string' || (!empty && !value.trim()) || Buffer.byteLength(value) > maxBytes || value.includes('\0')) fail();
  return value;
}
export function timestamp(value, nullable = false) {
  if (nullable && (value === undefined || value === null)) return null;
  if (typeof value !== 'string' || !/^\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d(?:\.\d{1,3})?(?:Z|[+-]\d\d:\d\d)$/.test(value)
    || !Number.isFinite(Date.parse(value))) fail('MEMORY_INVALID_TIME');
  const year = Number(value.slice(0, 4)), month = Number(value.slice(5, 7)), day = Number(value.slice(8, 10));
  const leap = year % 4 === 0 && (year % 100 !== 0 || year % 400 === 0);
  const days = [31, leap ? 29 : 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31];
  if (month < 1 || month > 12 || day < 1 || day > days[month - 1] || Number(value.slice(11, 13)) > 23
    || Number(value.slice(14, 16)) > 59 || Number(value.slice(17, 19)) > 59) fail('MEMORY_INVALID_TIME');
  return value;
}
function number(value, min, max) {
  if (typeof value !== 'number' || !Number.isFinite(value) || value < min || value > max) fail();
  return value;
}
function optional(value, key, check) { if (value[key] !== undefined) check(value[key]); }
function enumValue(value, allowed) { if (!allowed.includes(value)) fail(); return value; }
export function sourceIds(value = []) {
  if (!Array.isArray(value) || value.length > 128) fail('MEMORY_SOURCE_LIMIT');
  return [...new Set(value.map(identity))].sort();
}
export function dependencies(value = []) {
  if (!Array.isArray(value) || value.length > 128) fail('MEMORY_SOURCE_LIMIT');
  const seen = new Set();
  return value.map(item => {
    fields(item, ['recordId', 'revisionId']);
    const result = { recordId: identity(item.recordId), revisionId: identity(item.revisionId) };
    if (seen.has(result.recordId)) fail();
    seen.add(result.recordId); return result;
  });
}
export function payload(kind, value) {
  if (kind !== 'event') { fields(value, ['text']); return { text: text(value.text) }; }
  if (!value || !EVENT_TYPES.includes(value.eventType)) fail('MEMORY_INVALID_EVENT_TYPE');
  const common = ['eventType'];
  switch (value.eventType) {
    case 'activity':
      fields(value, [...common, 'appId', 'appLabel', 'text', 'startedAt', 'endedAt', 'source', 'transition', 'coverage', 'truncated', 'projection']);
      if (typeof value.appId !== 'string' || !/^[A-Za-z][A-Za-z0-9_.]{0,199}$/.test(value.appId)) fail();
      optional(value, 'appLabel', v => text(v, 512)); optional(value, 'text', v => text(v, 8192));
      optional(value, 'source', v => text(v, 160)); optional(value, 'transition', v => text(v, 80));
      optional(value, 'coverage', v => text(v, 160)); optional(value, 'projection', v => text(v, 160));
      optional(value, 'truncated', v => { if (typeof v !== 'boolean') fail(); }); break;
    case 'location': case 'visit':
      fields(value, [...common, 'latitude', 'longitude', 'accuracyMeters', 'provider', 'sampledAt', 'city', 'placeName', 'stale', 'startedAt', 'endedAt', 'approximate', 'elapsedRealtimeNanos', 'isMock']);
      number(value.latitude, -90, 90); number(value.longitude, -180, 180); number(value.accuracyMeters, 0, 1000000);
      optional(value, 'provider', v => text(v, 160)); optional(value, 'sampledAt', timestamp);
      optional(value, 'city', v => text(v, 512)); optional(value, 'placeName', v => text(v, 512));
      optional(value, 'stale', v => { if (typeof v !== 'boolean') fail(); });
      optional(value, 'approximate', v => { if (typeof v !== 'boolean') fail(); });
      optional(value, 'isMock', v => { if (typeof v !== 'boolean') fail(); });
      optional(value, 'elapsedRealtimeNanos', v => { if (typeof v !== 'string' || !/^\d{1,20}$/.test(v)) fail(); });
      if (value.eventType === 'location' && !value.sampledAt) fail();
      if (value.eventType === 'visit' && (!value.startedAt || !value.endedAt)) fail();
      break;
    case 'device':
      fields(value, [...common, 'batteryLevel', 'charging', 'networkType', 'text', 'screenInteractive']);
      optional(value, 'batteryLevel', v => number(v, 0, 100));
      optional(value, 'charging', v => { if (typeof v !== 'boolean') fail(); });
      optional(value, 'screenInteractive', v => { if (typeof v !== 'boolean') fail(); });
      optional(value, 'networkType', v => text(v, 80)); optional(value, 'text', v => text(v, 1024)); break;
    case 'gap':
      fields(value, [...common, 'source', 'reason', 'startedAt', 'endedAt']);
      text(value.source, 160); text(value.reason, 1024);
      // A collector continuity boundary reveals a detection point, not how
      // long observation was unavailable. Other gaps still require a range.
      if (!(value.source === 'all' && ['source_continuity_changed', 'source_continuity_unknown'].includes(value.reason)
        && value.startedAt === undefined && value.endedAt === undefined)
        && (!value.startedAt || !value.endedAt)) fail(); break;
    case 'note': fields(value, [...common, 'text']); text(value.text); break;
  }
  optional(value, 'startedAt', timestamp); optional(value, 'endedAt', timestamp);
  if (value.startedAt && value.endedAt && Date.parse(value.endedAt) < Date.parse(value.startedAt)) fail('MEMORY_INVALID_TIME');
  return structuredClone(value);
}
const retentionDays = { activity: 30, location: 7, visit: 90, device: 30, gap: 30, note: 30 };
export function normalizeRecord(input, now, automatic = false) {
  fields(input, ['kind', 'payload', 'occurredAt', 'validFrom', 'validUntil', 'sourceRefs', 'derivedFrom', 'assertion', 'actor', 'retention', 'state']);
  const kind = enumValue(input.kind, RECORD_KINDS), content = payload(kind, input.payload);
  const occurredAt = timestamp(input.occurredAt ?? content.sampledAt ?? content.startedAt ?? now);
  const validFrom = timestamp(input.validFrom, true), validUntil = timestamp(input.validUntil, true);
  if (validFrom && validUntil && Date.parse(validUntil) < Date.parse(validFrom)) fail('MEMORY_INVALID_TIME');
  const retention = input.retention ?? { mode: automatic ? 'automatic' : 'manual' };
  fields(retention, ['mode', 'expiresAt']); enumValue(retention.mode, ['manual', 'automatic']);
  if (retention.mode === 'manual' && retention.expiresAt != null) fail();
  const expiresAt = retention.mode === 'manual' ? null : timestamp(retention.expiresAt
    ?? new Date(Date.parse(occurredAt) + (content.eventType === 'activity' && content.text ? 2 : retentionDays[content.eventType] ?? 30) * 86400000).toISOString());
  return { kind, payload: content, occurredAt, validFrom, validUntil,
    sourceRefs: sourceIds(input.sourceRefs), derivedFrom: dependencies(input.derivedFrom),
    assertion: enumValue(input.assertion ?? (automatic ? 'tool_observation' : 'user_statement'), ['user_statement', 'tool_observation', 'execution_receipt', 'model_inference']),
    actor: enumValue(input.actor ?? (automatic ? 'unknown' : 'user'), ['user', 'droy', 'droy_active', 'unknown']),
    retention: { mode: retention.mode, expiresAt }, state: enumValue(input.state ?? 'active', ['active', 'candidate']),
    privacy: { categories: [...new Set(['memory', ...(['activity', 'location', 'visit'].includes(content.eventType) ? [content.eventType === 'activity' ? 'activity' : 'location'] : []), ...(input.derivedFrom?.length || input.assertion === 'model_inference' ? ['derived'] : [])])], processing: 'authorized-endpoints' },
    sharing: { mode: 'local' } };
}
export function canonical(value) {
  if (Array.isArray(value)) return '[' + value.map(canonical).join(',') + ']';
  if (value && typeof value === 'object') return '{' + Object.keys(value).sort().filter(k => value[k] !== undefined).map(k => JSON.stringify(k) + ':' + canonical(value[k])).join(',') + '}';
  return JSON.stringify(value);
}
export function queryInput(input = {}) {
  fields(input, ['text', 'kinds', 'eventTypes', 'from', 'to', 'limit', 'maxBytes', 'cursor', 'states', 'projection']);
  const result = { limit: input.limit ?? 8, maxBytes: input.maxBytes ?? 6000, projection: input.projection ?? 'full' };
  enumValue(result.projection, ['full', 'summary']);
  if (!Number.isInteger(result.limit) || result.limit < 1 || result.limit > 100
    || !Number.isInteger(result.maxBytes) || result.maxBytes < 256 || result.maxBytes > 262144) fail();
  if (input.text !== undefined) result.text = text(input.text, 1024);
  for (const [key, values] of [['kinds', RECORD_KINDS], ['eventTypes', EVENT_TYPES], ['states', ['active', 'candidate', 'expired', 'deleted']]]) {
    if (input[key] !== undefined) {
      if (!Array.isArray(input[key]) || !input[key].length || input[key].length > values.length) fail();
      result[key] = [...new Set(input[key].map(v => enumValue(v, values)))];
    }
  }
  result.from = timestamp(input.from, true); result.to = timestamp(input.to, true);
  if (result.from && result.to && Date.parse(result.to) <= Date.parse(result.from)) fail('MEMORY_INVALID_TIME');
  if (input.cursor !== undefined) result.cursor = text(input.cursor, 2048);
  return result;
}
