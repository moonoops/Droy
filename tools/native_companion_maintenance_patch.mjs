import { createHash } from 'node:crypto';

export const NATIVE_COMPANION_MAINTENANCE_PATCH_VERSION = 2;
const end = 'export { Config, JsonlCompressionSchema, apply, name };';
const anchors = [end, 'var JsonlSessionPersistence = class extends SessionPersistence {',
  'function parseGenerationLogFilename(filename, compression) {', 'async function readStableJsonlFile(path, signal) {',
  'function scanZstdFrames(buffer, maxFrames = Number.POSITIVE_INFINITY) {', 'function createZstdFrameDecoder() {',
  '\tasync decodeStoredLog(path, expectedId, buffer, revision, signal) {', '\tasync prepareStoredMigration(id, selected, signal) {',
  'function toHeaderLine(header, inheritedEventCount) {', 'function eventLines(events) {'];

// This code lives inside the provider: only it interprets Session generations and their locators.
const addition = String.raw`
/** Strict read-only inventory for an exclusively stopped offline maintenance owner. */
async function auditOfflineSessionCorpus(backend, options = {}) {
  const refuse = (reason) => { throw Object.assign(new Error('DSH_OFFLINE_AUDIT_BLOCKED'), { code: 'DSH_OFFLINE_AUDIT_BLOCKED', reason }); };
  if (!(backend instanceof JsonlSessionPersistence) || backend.tracker.openHandles.size) refuse('provider-not-offline');
  if (options === null || typeof options !== 'object' || Object.keys(options).some(key => !['signal', 'maxArtifactBytes', 'maxDecodedBytes'].includes(key))) refuse('invalid-options');
  const signal = options.signal ?? new AbortController().signal, maxArtifactBytes = options.maxArtifactBytes ?? 268435456, maxDecodedBytes = options.maxDecodedBytes ?? 536870912;
  if (![maxArtifactBytes, maxDecodedBytes].every(value => Number.isSafeInteger(value) && value > 0)) refuse('invalid-options');
  const fingerprint = createHash('sha256'), entries = [], ids = new Set(); let physicalBytes = 0, artifactCount = 0;
  const canonical = value => Array.isArray(value) ? '[' + value.map(canonical).join(',') + ']' : value !== null && typeof value === 'object'
    ? '{' + Object.keys(value).sort().map(key => JSON.stringify(key) + ':' + canonical(value[key])).join(',') + '}' : JSON.stringify(value);
  const names = async path => (await readdir(path, { withFileTypes: true })).sort((a, b) => a.name < b.name ? -1 : a.name > b.name ? 1 : 0);
  const check = () => signal?.throwIfAborted();
  const directory = async path => { const value = await lstat(path); if (!value.isDirectory() || value.isSymbolicLink()) refuse('linked-or-nondirectory'); };
  check();
  try { await directory(backend.root); } catch (error) { if (error.code === 'ENOENT') return { version: 1, complete: true, missing: true, fingerprint: fingerprint.digest('hex'), physicalBytes, artifactCount, entries }; throw error; }
  for (const project of await names(backend.root)) {
    check(); if (!project.isDirectory() || project.isSymbolicLink()) refuse('unknown-root-artifact');
    const projectPath = join(backend.root, project.name); await directory(projectPath);
    fingerprint.update(JSON.stringify(['project', project.name]));
    const sessions = await names(projectPath); if (!sessions.length) refuse('project-without-session');
    for (const session of sessions) {
      check(); if (!session.isDirectory() || session.isSymbolicLink()) refuse('unknown-project-artifact');
      const sessionPath = join(projectPath, session.name); await directory(sessionPath);
      fingerprint.update(JSON.stringify(['session', project.name, session.name]));
      const generations = []; let id, header;
      for (const artifact of await names(sessionPath)) {
        check(); const path = join(sessionPath, artifact.name), info = await lstat(path);
        if (!artifact.isFile() || artifact.isSymbolicLink() || !info.isFile() || info.nlink !== 1) refuse('unknown-or-linked-session-artifact');
        const version = parseGenerationLogFilename(artifact.name, backend.compression);
        if (version === void 0 && artifact.name !== LEASE_FILENAME) refuse('unknown-session-artifact');
        if (info.size > maxArtifactBytes) refuse('artifact-too-large');
        const snapshot = await readStableJsonlFile(path, signal), bytes = snapshot.bytes;
        const digest = createHash('sha256').update(bytes).digest('hex');
        fingerprint.update(JSON.stringify(['artifact', project.name, session.name, artifact.name, bytes.length, digest]));
        physicalBytes += bytes.length; artifactCount++;
        if (version === void 0) { if (bytes.length) refuse('nonempty-lease-artifact'); continue; }
        if (version > SESSION_FORMAT_VERSION) refuse('unsupported-generation');
        let plaintext = bytes;
        if (backend.compression === 'zstd') {
          const structural = scanZstdFrames(bytes); if (structural.tornStart !== void 0 || !structural.frames.length) refuse('torn-generation');
          const decoder = createZstdFrameDecoder(), chunks = []; let decoded = 0;
          try { for (const chunk of decoder.decode(bytes, structural.frames)) { check(); decoded += chunk.length; if (decoded > maxDecodedBytes) refuse('decoded-too-large'); chunks.push(Buffer.from(chunk)); } }
          finally { decoder.close(); }
          plaintext = Buffer.concat(chunks, decoded);
        }
        if (plaintext.length > maxDecodedBytes) refuse('decoded-too-large');
        let lines;
        try { const text = new TextDecoder('utf-8', { fatal: true }).decode(plaintext); if (!text.endsWith('\n')) refuse('torn-generation'); lines = text.slice(0, -1).split('\n'); for (const line of lines) JSON.parse(line); }
        catch (error) { if (error.code === 'DSH_OFFLINE_AUDIT_BLOCKED') throw error; refuse('malformed-generation'); }
        const selected = { sourcePath: path, sourceVersion: version, currentPath: join(sessionPath, generationLogFilename(SESSION_FORMAT_VERSION, backend.compression)) };
        const metadata = await backend.readGenerationHeader(selected, void 0, signal);
        if (!metadata) refuse('malformed-header');
        if (id !== void 0 && metadata.header.id !== id) refuse('generation-identity-conflict');
        id = metadata.header.id;
        const decoded = version === SESSION_FORMAT_VERSION ? await backend.decodeStoredLog(path, id, bytes, fileRevision(snapshot.identity), signal) : await backend.prepareStoredMigration(id, selected, signal);
        if (decoded.tornTruncateTo !== void 0 || decoded.recoveredTail?.length) refuse('torn-generation');
        const attachmentIds = new Set();
        const collectAttachments = value => {
          if (Array.isArray(value)) { for (const child of value) collectAttachments(child); return; }
          if (value === null || typeof value !== 'object') return;
          if (typeof value.attachmentId === 'string' && /^sha256:[a-f0-9]{64}$/u.test(value.attachmentId)) attachmentIds.add(value.attachmentId);
          else if (typeof value.attachmentId === 'string' && /^[a-f0-9]{64}$/u.test(value.attachmentId)) attachmentIds.add('sha256:' + value.attachmentId);
          else if (typeof value.attachmentId === 'string' && value.attachmentId.startsWith('sha256:')) refuse('malformed-attachment-reference');
          for (const child of Object.values(value)) collectAttachments(child);
        };
        collectAttachments(decoded.events);
        generations.push({ version, bytes: bytes.length, sha256: digest, attachmentIds: [...attachmentIds].sort() });
        if (header === void 0 || version > header.version) header = { version, value: decoded.meta, eventCount: decoded.events.length, inheritedEventCount: decoded.inheritedEventCount,
          logicalDigest: createHash('sha256').update(canonical({ header: decoded.meta, inheritedEventCount: decoded.inheritedEventCount, events: decoded.events })).digest('hex'),
          currentPlainBytes: Buffer.byteLength(JSON.stringify(toHeaderLine(decoded.meta, decoded.inheritedEventCount))) + 1 + (decoded.events.length ? Buffer.byteLength(eventLines(decoded.events)) + 1 : 0) };
      }
      if (!generations.length) refuse('session-without-generation');
      if (ids.has(id)) refuse('duplicate-session'); ids.add(id);
      entries.push({ id, header: header.value, selectedVersion: header.version, eventCount: header.eventCount,
        inheritedEventCount: header.inheritedEventCount, logicalDigest: header.logicalDigest, currentPlainBytes: header.currentPlainBytes, generations: generations.sort((a, b) => a.version - b.version) });
    }
  }
  check();
  return { version: 1, complete: true, missing: false, fingerprint: fingerprint.digest('hex'), physicalBytes, artifactCount,
    entries: entries.sort((a, b) => a.id < b.id ? -1 : a.id > b.id ? 1 : 0) };
}
`;

/** Return patched provider code; callers own preparation, lock records and publication. */
export function patchNativeCompanionMaintenance(source) {
  if (typeof source !== 'string' || source.includes('async function auditOfflineSessionCorpus(')
    || anchors.some(anchor => source.split(anchor).length !== 2)) throw new Error('DSH offline maintenance provider contract changed');
  return source.replace(end, addition + '\n' + end.replace('apply, name', 'apply, name, auditOfflineSessionCorpus'));
}
export function nativeCompanionMaintenancePatchFingerprint(source) {
  const patched = patchNativeCompanionMaintenance(source);
  return { version: NATIVE_COMPANION_MAINTENANCE_PATCH_VERSION, before: createHash('sha256').update(source).digest('hex'),
    after: createHash('sha256').update(patched).digest('hex') };
}
