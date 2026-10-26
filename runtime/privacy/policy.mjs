export const PRIVACY_SCHEMA_VERSION = 1;
export const PRIVACY_CATEGORIES = Object.freeze(['memory', 'activity', 'location', 'derived']);
const categories = new Set(PRIVACY_CATEGORIES);
const purposes = new Set(['conversation', 'compaction', 'session-title', 'extraction', 'tts', 'realtime', 'image', 'embedding', 'rerank', 'geocoding']);

export class PrivacyError extends Error {
  constructor(code) { super(code); this.name = 'PrivacyError'; this.code = code; }
}

export function assertFields(value, allowed) {
  if (!value || typeof value !== 'object' || Array.isArray(value)
    || Object.keys(value).some(key => !allowed.includes(key))) throw new PrivacyError('PRIVACY_INVALID_INPUT');
}

export function sessionIdentity(value) {
  if (typeof value !== 'string' || !/^[A-Za-z0-9_.:-]{1,160}$/.test(value)) throw new PrivacyError('PRIVACY_INVALID_SESSION');
  return value;
}

export function categoryList(value, allowEmpty = false) {
  if (!Array.isArray(value) || value.length > PRIVACY_CATEGORIES.length
    || value.some(item => !categories.has(item)) || (!allowEmpty && value.length === 0)) {
    throw new PrivacyError('PRIVACY_INVALID_CATEGORIES');
  }
  return [...new Set(value)].sort();
}

export function sourceIdentities(value = []) {
  if (!Array.isArray(value)
    || value.some(item => typeof item !== 'string' || !/^[A-Za-z0-9_.:-]{1,160}$/.test(item))) {
    throw new PrivacyError('PRIVACY_INVALID_SOURCES');
  }
  if (value.length > 128) throw new PrivacyError('PRIVACY_SOURCE_LIMIT');
  return [...new Set(value)].sort();
}

// The host resolves the actual destination. URLs carrying credentials or query
// parameters cannot enter this store, even when a caller passes extra fields.
export function normalizeEndpoint(value) {
  assertFields(value, ['url', 'purpose', 'configVersion']);
  if (typeof value.url !== 'string' || value.url.length > 2048) throw new PrivacyError('PRIVACY_INVALID_ENDPOINT');
  let parsed;
  try { parsed = new URL(value.url); } catch { throw new PrivacyError('PRIVACY_INVALID_ENDPOINT'); }
  if (!['https:', 'http:'].includes(parsed.protocol) || !parsed.hostname || parsed.username || parsed.password
    || parsed.search || parsed.hash || !purposes.has(value.purpose)
    || typeof value.configVersion !== 'string' || !/^[a-f0-9]{64}$/.test(value.configVersion)) {
    throw new PrivacyError('PRIVACY_INVALID_ENDPOINT');
  }
  return { url: parsed.href, purpose: value.purpose, configVersion: value.configVersion };
}

export function assertSessionPermission(session, grant) {
  if (session.state === 'unknown') throw new PrivacyError('PRIVACY_SESSION_UNKNOWN');
  if (session.state === 'forgotten') throw new PrivacyError('PRIVACY_SESSION_FORGOTTEN');
  if (session.state === 'known-clean') return;
  if (session.state !== 'protected') throw new PrivacyError('PRIVACY_STORE_CORRUPT');
  const required = categoryList(session.categories);
  if (!grant || !required.every(category => grant.categories.includes(category))) {
    throw new PrivacyError('PRIVACY_ENDPOINT_NOT_AUTHORIZED');
  }
}
