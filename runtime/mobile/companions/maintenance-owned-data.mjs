import { createHash } from 'node:crypto';
import { DatabaseSync } from 'node:sqlite';
import { isDeepStrictEqual } from 'node:util';
import { companionStoreMaintenanceSchema } from './store.mjs';
import { requestImageVariantId } from '@deepseek-ai/dsh-attachment-local';
import { lstat, realpath, readFile, readdir, open, rename, unlink, rmdir, mkdir } from 'node:fs/promises';
import { dirname, isAbsolute, join, relative, resolve } from 'node:path';
import { scheduleDomain } from '@deepseek-ai/dsh-schedule';
import { createTrustedRootAlias } from './maintenance-root-alias.mjs';
import { CompanionMemorySpaces } from './memory-spaces.mjs';

const object = value => value !== null && typeof value === 'object' && !Array.isArray(value);
const id = value => typeof value === 'string' && /^[A-Za-z0-9_-][A-Za-z0-9_.:-]{0,159}$/u.test(value) && !['.', '..'].includes(value);
const canonical = value => Array.isArray(value) ? '[' + value.map(canonical).join(',') + ']' : object(value)
  ? '{' + Object.keys(value).sort().map(key => JSON.stringify(key) + ':' + canonical(value[key])).join(',') + '}' : JSON.stringify(value);
const hash = value => createHash('sha256').update(value).digest('hex');
const digest = value => hash(canonical(value));
const fail = (code, cause) => { throw Object.assign(new Error('MAINTENANCE_OWNED_' + code, cause ? { cause } : undefined), { code: 'MAINTENANCE_OWNED_' + code }); };
const inside = (parent, child) => child === parent || !relative(parent, child).startsWith('..') && !isAbsolute(relative(parent, child));
async function info(path) { try { return await lstat(path); } catch (error) { if (error.code === 'ENOENT') return null; throw error; } }
async function safePath(path) {
  for (let current = resolve(path); ; current = dirname(current)) {
    const value = await info(current);
    if (value?.isSymbolicLink()) fail('LINKED_PATH');
    if (value && current !== path && !value.isDirectory()) fail('PATH_INVALID');
    if (dirname(current) === current) break;
  }
}
async function syncDirectory(path) { const file = await open(path, 'r'); try { await file.sync(); } finally { await file.close(); } }
async function atomic(path, value) {
  const temporary = path + '.owned-tmp'; await safePath(temporary);
  if (await info(temporary)) { const current = await lstat(temporary); if (!current.isFile() || current.nlink !== 1) fail('LINKED_PATH'); await unlink(temporary); }
  const file = await open(temporary, 'wx', 0o600);
  try { await file.writeFile(value); await file.sync(); } finally { await file.close(); }
  await rename(temporary, path); await syncDirectory(dirname(path));
}
async function inventory(path, { hardlinks = false } = {}) {
  await safePath(path); const value = await info(path); if (!value) return [];
  if (value.isDirectory()) {
    const result = [{ path, type: 'directory' }];
    for (const name of (await readdir(path)).sort()) result.push(...await inventory(join(path, name), { hardlinks }));
    return result;
  }
  if (!value.isFile() || !hardlinks && value.nlink !== 1) fail('LINKED_PATH');
  return [{ path, type: 'file', digest: hash(await readFile(path)), dev: value.dev, ino: value.ino, nlink: value.nlink }];
}

async function memoryArchiveSnapshot(root, companionId, spaces) {
  const storePath = join(root, 'companions.sqlite'); await safePath(storePath);
  // The lower-level maintenance helper can operate without the product store;
  // the complete coordinator always supplies an audited store beforehand.
  if (!await info(storePath)) return { evidence: [] };
  const binding = await spaces.current(companionId);
  const memoryPath = join(binding?.directory ?? join(root, companionId, 'memory'), 'memory.sqlite');
  await safePath(memoryPath);
  const sourceIds = new Set();
  if (await info(memoryPath)) {
    let memory;
    try {
      memory = new DatabaseSync(memoryPath, { readOnly: true });
      if (memory.prepare('PRAGMA application_id').get().application_id !== 1146244434
        || ![1,2].includes(memory.prepare('PRAGMA user_version').get().user_version)) fail('MEMORY_SOURCE_UNSUPPORTED');
      for (const row of memory.prepare('SELECT data FROM revisions').iterate()) {
        const record = JSON.parse(row.data);
        if (!record.payload) continue;
        for (const source of record.sourceRefs ?? []) {
          if (typeof source !== 'string' || !source.startsWith('im:' + companionId + ':')) fail('MEMORY_SOURCE_SCOPE');
          sourceIds.add(source.slice(('im:' + companionId + ':').length));
        }
      }
    } catch (error) { if (error.code?.startsWith('MAINTENANCE_OWNED_')) throw error; fail('MEMORY_SOURCE_UNREADABLE', error); }
    finally { memory?.close(); }
  }
  const store = new DatabaseSync(storePath, { readOnly: true });
  try {
    const row = store.prepare('SELECT data FROM profiles WHERE id=?').get(companionId);
    if (!row) fail('TARGET_MISSING');
    const profile = JSON.parse(row.data), evidence = [];
    for (const messageId of sourceIds) {
      const sourceRef = 'im:' + companionId + ':' + messageId;
      const message = store.prepare('SELECT data FROM messages WHERE companion_id=? AND message_id=?').get(companionId, messageId);
      if (!message) { evidence.push({ sourceRef, messageId, unavailable: true }); continue; }
      const value = JSON.parse(message.data);
      const text = typeof value.text === 'string' ? value.text : '';
      const sensitive = /(?:\bBearer\s+\S+|\bsk-[\w-]{8,}|-----BEGIN [A-Z ]*PRIVATE KEY-----|(?:api[_ -]?key|password|access[_ -]?token|密码|令牌)\s*[:=：]\s*\S+)/iu.test(text);
      const attachments = [];
      if (!sensitive) for (const attachment of value.attachments ?? []) {
        const upload = store.prepare('SELECT data FROM companion_uploads WHERE companion_id=? AND upload_id=?').get(companionId, attachment.id);
        if (!upload) fail('MEMORY_EVIDENCE_ATTACHMENT_MISSING');
        const uploaded = JSON.parse(upload.data);
        if (!uploaded.ref?.attachmentId || !/^sha256:[a-f0-9]{64}$/u.test(uploaded.ref.attachmentId)) fail('MEMORY_EVIDENCE_ATTACHMENT_UNSUPPORTED');
        attachments.push({ id: attachment.id, ref: uploaded.ref });
      }
      evidence.push({ sourceRef, messageId, role: value.role, ...(value.createdAt ? { createdAt: value.createdAt } : {}),
        ...(sensitive ? { unavailable: true, reason: 'credential' } : { text: [...text].slice(0, 16384).join(''), truncated: [...text].length > 16384,
          ...(attachments.length ? { attachments } : {}) }) });
    }
    return { profile, evidence };
  } finally { store.close(); }
}
async function preserveEvidenceAttachments(evidence, binding, attachmentRoot) {
  for (const attachmentId of refs(evidence)) {
    const key = attachmentId.slice(7), source = join(attachmentRoot, 'objects', key.slice(0, 2), key);
    await safePath(source); const sourceInfo = await info(source);
    if (!sourceInfo?.isFile()) fail('MEMORY_EVIDENCE_ATTACHMENT_MISSING');
    const bytes = await readFile(source); if (hash(bytes) !== key) fail('MEMORY_EVIDENCE_ATTACHMENT_CHANGED');
    const directory = join(dirname(binding.directory), 'evidence-blobs'), target = join(directory, key);
    await safePath(directory); await mkdir(directory, { recursive: true, mode: 0o700 }); await safePath(target);
    const current = await info(target);
    if (current) { if (!current.isFile() || current.nlink !== 1 || hash(await readFile(target)) !== key) fail('MEMORY_EVIDENCE_ATTACHMENT_CHANGED'); }
    else {
      const file = await open(target, 'wx', 0o600);
      try { await file.writeFile(bytes); await file.sync(); } finally { await file.close(); }
      await syncDirectory(directory);
    }
  }
}
function refs(value, output = new Set()) {
  if (Array.isArray(value)) for (const item of value) refs(item, output);
  else if (object(value)) {
    if (typeof value.attachmentId === 'string' && /^[a-f0-9]{64}$/u.test(value.attachmentId)) output.add('sha256:' + value.attachmentId);
    else if (value.attachmentId !== undefined && typeof value.attachmentId === 'string' && value.attachmentId.startsWith('sha256:')) {
      if (!/^sha256:[a-f0-9]{64}$/u.test(value.attachmentId)) fail('REFERENCE_INVALID');
      output.add(value.attachmentId);
    }
    for (const child of Object.values(value)) refs(child, output);
  }
  return output;
}
function unit(document, name, version, table) {
  if (!object(document) || Object.keys(document).some(key => !['unit', 'global', 'tables'].includes(key))
    || !object(document.unit) || document.unit.name !== name || document.unit.version !== version
    || Object.keys(document.unit).some(key => !['name', 'version'].includes(key)) || !object(document.tables)
    || Object.keys(document.tables).some(key => key !== table) || !object(document.tables[table])) fail('SCHEMA_UNSUPPORTED');
}

/** Offline only. inspectSessions must return a complete strict-provider audit with
 * [{id, header, events}], including inherited history, before Sessions are removed.
 * For blob deletion the audit also proves attachmentReferencesComplete and lists
 * retainedAttachmentIds from all remaining product rows, Android drafts and
 * other host-owned stores. targetAttachmentIds can include older-generation
 * target references. requestImageVariants can supply exact historical upstream
 * transform descriptors; current model settings do not prove historical ones.
 * Without complete retained-reference evidence blobs remain pending.
 * The coordinator persists this plan before committing any other deletion phase. */
export async function createCompanionOwnedDataMaintenance({ root, storageRoot, attachmentRoot, attachmentCacheRoot,
  deepSeekIndexPath, operationId, manifest, manifestDigest, authority, inspectSessions, trustedRootAlias, onCheckpoint = () => {} } = {}) {
  if (![root, storageRoot, attachmentRoot, attachmentCacheRoot, deepSeekIndexPath].every(value => typeof value === 'string' && isAbsolute(value))
    || !id(operationId) || !object(manifest) || manifest.version !== 1 || manifest.scope !== 'companion-store'
    || !id(manifest.companionId) || digest(manifest) !== manifestDigest || typeof authority?.assertHeld !== 'function'
    || typeof inspectSessions !== 'function' || typeof onCheckpoint !== 'function') fail('INVALID_INPUT');
  for (const path of [root, storageRoot, attachmentRoot, attachmentCacheRoot, deepSeekIndexPath]) await safePath(path);
  if (await realpath(root) !== resolve(root)) fail('LINKED_PATH');
  root = resolve(root); storageRoot = resolve(storageRoot); attachmentRoot = resolve(attachmentRoot); attachmentCacheRoot = resolve(attachmentCacheRoot); deepSeekIndexPath = resolve(deepSeekIndexPath);
  const storedPaths = await createTrustedRootAlias(dirname(root), trustedRootAlias, fail);
  const legacyAttachmentRoot = join(dirname(attachmentRoot), 'droy-png-v1');
  const roots = [root, storageRoot, attachmentRoot, attachmentCacheRoot, deepSeekIndexPath, legacyAttachmentRoot];
  const replacementPaths = [join(storageRoot, 'workspace.json'), join(storageRoot, 'schedule.json'), deepSeekIndexPath];
  const target = join(root, manifest.companionId), journalPath = join(root, '.owned-maintenance-' + operationId + '.json');
  if ([storageRoot, attachmentRoot, attachmentCacheRoot, deepSeekIndexPath, legacyAttachmentRoot].some(path => inside(target, path) || inside(path, target))) fail('ROOT_CONFLICT');
  const sessions = new Set(manifest.sessionClaims.map(row => row.sessionId));
  if ([...sessions].some(value => !id(value)) || !Array.isArray(manifest.workspaceClaims) || !Array.isArray(manifest.blobs)) fail('MANIFEST_INVALID');
  let epoch, journal;
  const memorySpaces = await CompanionMemorySpaces.open({ directory: root });
  async function guard() {
    let proof; try { proof = await authority.assertHeld({ root, operationId, scope: 'companion-owned-data', manifestDigest }); } catch (error) { fail('AUTHORITY_LOST', error); }
    if (!object(proof) || proof.root !== root || proof.operationId !== operationId || proof.manifestDigest !== manifestDigest
      || !id(proof.admissionEpoch) || proof.hostStopped !== true || proof.admissionsClosed !== true) fail('AUTHORITY_LOST');
    if (epoch && epoch !== proof.admissionEpoch) fail('AUTHORITY_CHANGED'); epoch = proof.admissionEpoch;
  }
  async function load() {
    await safePath(journalPath); const file = await info(journalPath); if (!file) return false;
    if (!file.isFile() || file.nlink !== 1) fail('JOURNAL_INVALID');
    const saved = JSON.parse(await readFile(journalPath, 'utf8'));
    if (saved.version !== 1 || saved.operationId !== operationId || saved.manifestDigest !== manifestDigest || saved.epoch !== epoch
      || saved.planDigest !== digest(saved.plan) || !['prepared', 'applying', 'complete'].includes(saved.phase)
      || saved.plan.roots.join('\n') !== roots.join('\n') || digest(saved.plan.trustedRootAlias ?? null) !== digest(storedPaths.value)) fail('JOURNAL_INVALID');
    const allowed = path => inside(target, path) || inside(attachmentRoot, path) || inside(attachmentCacheRoot, path) || inside(legacyAttachmentRoot, path);
    if (!saved.plan.deletions.every(row => allowed(row.path)) || !saved.plan.replacements.every(row => replacementPaths.includes(row.path))) fail('JOURNAL_INVALID');
    // Older prepared plans include the memory directory in their deletion set.
    // Do not resume those plans under the retention contract.
    if (!id(saved.plan.memorySpaceId) || saved.plan.deletions.some(row => row.path === join(target, 'memory.identity.json') || inside(join(target, 'memory'), row.path))) fail('MEMORY_RETENTION_PLAN_REQUIRED');
    journal = saved; return true;
  }
  async function checkpoint(phase) {
    await guard(); journal.phase = phase; await atomic(journalPath, JSON.stringify(journal) + '\n'); await onCheckpoint(phase);
  }
  const receipt = () => ({ version: 1, scope: 'companion-owned-data', operationId, companionId: manifest.companionId,
    phase: journal.phase, manifestDigest, planDigest: journal.planDigest,
    resources: { deepSeekUploadIndex: journal.phase, memory: journal.phase === 'complete' ? 'retained' : journal.phase, workspaces: journal.phase, schedules: journal.phase,
      attachments: journal.plan.pending.length ? 'incomplete' : journal.phase },
    memorySpaceId: journal.plan.memorySpaceId, preservedSharedBlobIds: journal.plan.shared, pending: journal.plan.pending, companionDeletion: 'incomplete' });
  async function prepare() {
    await guard(); if (await load()) { await validate(); return receipt(); }
    const audit = await inspectSessions();
    if (!object(audit) || audit.complete !== true || !Array.isArray(audit.sessions)) fail('SESSION_AUDIT_REQUIRED');
    const listed = new Set();
    for (const row of audit.sessions) {
      if (!id(row.id) || listed.has(row.id) || !object(row.header) || row.header.id !== row.id || !Array.isArray(row.events)
        || typeof row.header.cwd !== 'string' || !isAbsolute(row.header.cwd)) fail('SESSION_AUDIT_REQUIRED');
      listed.add(row.id);
      const cwd = await (sessions.has(row.id) ? storedPaths.path(row.header.cwd) : storedPaths.retainedPath(row.header.cwd));
      if (!sessions.has(row.id) && inside(target, cwd)) fail('WORKSPACE_SHARED');
    }
    for (const value of sessions) if (!listed.has(value)) fail('SESSION_MISSING');
    if (audit.attachmentReferencesComplete === true && (!Array.isArray(audit.retainedAttachmentIds) || audit.retainedAttachmentIds.some(value => !/^sha256:[a-f0-9]{64}$/u.test(value)))) fail('REFERENCE_INVALID');
    await guard();
    const archive = await memoryArchiveSnapshot(root, manifest.companionId, memorySpaces);
    await guard();
    const memoryBinding = await memorySpaces.stageArchive(manifest.companionId, archive);
    await preserveEvidenceAttachments(archive.evidence, memoryBinding, attachmentRoot);
    await guard();
    const retainedRefs = new Set(manifest.blobs.filter(row => row.retainedInStore.length).map(row => row.ref.attachmentId));
    for (const value of audit.retainedAttachmentIds ?? []) retainedRefs.add(value);
    const candidateRefs = new Set([...manifest.blobs.map(row => row.ref.attachmentId), ...(audit.targetAttachmentIds ?? [])]);
    for (const row of audit.sessions) refs(row, sessions.has(row.id) ? candidateRefs : retainedRefs);
    const deletions = [], replacements = [], shared = [], pending = [];
    // Every target directory entry is product-scoped; unknown siblings are refused.
    const targetFiles = await inventory(target);
    if (targetFiles.length && (await readdir(target)).some(name => !['work', 'chat', 'memory', 'memory.identity.json'].includes(name))) fail('TARGET_LAYOUT_UNSUPPORTED');
    deletions.push(...targetFiles);
    const workspacePath = join(storageRoot, 'workspace.json'), schedulePath = join(storageRoot, 'schedule.json');
    async function readUnit(path) {
      await safePath(path); const file = await info(path); if (!file) return null;
      if (!file.isFile() || file.nlink !== 1) fail('LINKED_PATH');
      const before = await readFile(path, 'utf8'); return { before, value: JSON.parse(before) };
    }
    const workspace = await readUnit(workspacePath), claimed = new Set(manifest.workspaceClaims.map(row => row.workspaceId));
    for (const row of manifest.workspaceClaims) if (!id(row.workspaceId) || row.retainedProfileIds.length || row.retainedSessionIds.length) fail('WORKSPACE_SHARED');
    if (!workspace && claimed.size) fail('WORKSPACE_MISSING');
    if (workspace) {
      const doc = workspace.value; unit(doc, 'workspace', 2, 'workspaces'); const global = doc.global;
      if (!object(global) || global.initialized !== true || global.pendingMutation !== undefined
        || Object.keys(global).some(key => !['initialized','defaultWorkspaceId','workspaceIds','archivedSessionIds','pinnedSessionIds'].includes(key))
        || !Array.isArray(global.workspaceIds) || new Set(global.workspaceIds).size !== global.workspaceIds.length
        || global.workspaceIds.some(value => !id(value)) || Object.keys(doc.tables.workspaces).sort().join('\n') !== [...global.workspaceIds].sort().join('\n')) fail('WORKSPACE_INVALID');
      for (const name of ['archivedSessionIds','pinnedSessionIds']) if (global[name] !== undefined && (!Array.isArray(global[name]) || global[name].some(value => !id(value)))) fail('WORKSPACE_INVALID');
      for (const [key, row] of Object.entries(doc.tables.workspaces)) {
        if (!object(row) || Object.keys(row).some(value => !['path','title','sessionIds','createdAt','updatedAt'].includes(value))
          || typeof row.path !== 'string' || !isAbsolute(row.path) || !Array.isArray(row.sessionIds) || row.sessionIds.some(value => !id(value))
          || typeof row.title !== 'string' || typeof row.createdAt !== 'string' || typeof row.updatedAt !== 'string') fail('WORKSPACE_INVALID');
        const workspaceDirectory = await (claimed.has(key) ? storedPaths.path(row.path) : storedPaths.retainedPath(row.path));
        if (claimed.has(key)) {
          if (workspaceDirectory !== join(target, 'work') || row.sessionIds.some(value => !sessions.has(value))) fail('WORKSPACE_SHARED');
          delete doc.tables.workspaces[key];
        } else {
          if (inside(target, workspaceDirectory) || inside(workspaceDirectory, target)) fail('WORKSPACE_SHARED');
          row.sessionIds = row.sessionIds.filter(value => !sessions.has(value));
        }
      }
      for (const value of claimed) if (!global.workspaceIds.includes(value)) fail('WORKSPACE_MISSING');
      global.workspaceIds = global.workspaceIds.filter(value => !claimed.has(value));
      for (const name of ['archivedSessionIds','pinnedSessionIds']) if (global[name]) global[name] = global[name].filter(value => !sessions.has(value));
      replacements.push({ path: workspacePath, before: hash(workspace.before), after: JSON.stringify(doc, null, 2) + '\n' });
    }
    const schedule = await readUnit(schedulePath);
    if (schedule) {
      const doc = schedule.value; unit(doc, 'schedule', 1, 'tasks'); if (doc.global !== null) fail('SCHEMA_UNSUPPORTED');
      for (const [key, value] of Object.entries(doc.tables.tasks)) {
        if (!scheduleDomain.tables.tasks.valueSchema.safeParse(value).success) fail('SCHEDULE_INVALID');
        if (sessions.has(value.sessionId)) { refs(value, candidateRefs); delete doc.tables.tasks[key]; } else refs(value, retainedRefs);
      }
      replacements.push({ path: schedulePath, before: hash(schedule.before), after: JSON.stringify(doc, null, 2) + '\n' });
    }
    // Other JSON domains may carry durable attachment references. Unknown media
    // cannot establish an exhaustive ownership proof and blocks blob deletion.
    let referenceCoverage = audit.attachmentReferencesComplete === true;
    if (await info(storageRoot)) for (const entry of await readdir(storageRoot, { withFileTypes: true })) {
      if (['workspace.json','schedule.json'].includes(entry.name)) continue;
      const path = join(storageRoot, entry.name);
      for (const file of await inventory(path)) if (file.type === 'file') {
        if (!file.path.endsWith('.json')) { referenceCoverage = false; continue; }
        try { refs(JSON.parse(await readFile(file.path, 'utf8')), retainedRefs); } catch { referenceCoverage = false; }
      }
    }
    const index = await readUnit(deepSeekIndexPath), variants = new Map();
    const noteVariant = (variantId, attachmentId) => {
      if (!/^sha256:[a-f0-9]{64}$/u.test(variantId) || !/^sha256:[a-f0-9]{64}$/u.test(attachmentId)) fail('REFERENCE_INVALID');
      if (variants.has(variantId) && variants.get(variantId) !== attachmentId) fail('VARIANT_CONFLICT');
      variants.set(variantId, attachmentId);
    };
    if (index) {
      const doc = index.value;
      if (!object(doc) || doc.formatVersion !== 3 || !Array.isArray(doc.records) || Object.keys(doc).some(key => !['formatVersion','records'].includes(key))) fail('UPLOAD_INDEX_INVALID');
      const keys = new Set();
      for (const row of doc.records) {
        if (!object(row) || Object.keys(row).some(key => !['scope','attachmentId','variantId','fileId','bytes','createdAt','expiresAt'].includes(key))
          || !/^[a-f0-9]{64}$/u.test(row.scope) || typeof row.fileId !== 'string' || !row.fileId
          || ['bytes','createdAt','expiresAt'].some(key => !Number.isSafeInteger(row[key]) || row[key] < 0)) fail('UPLOAD_INDEX_INVALID');
        const key = row.scope + ':' + row.variantId; if (keys.has(key)) fail('UPLOAD_INDEX_INVALID'); keys.add(key);
        noteVariant(row.variantId, row.attachmentId);
      }
      doc.records = doc.records.filter(row => !referenceCoverage || !candidateRefs.has(row.attachmentId) || retainedRefs.has(row.attachmentId));
      replacements.push({ path: deepSeekIndexPath, before: hash(index.before), after: JSON.stringify(doc, null, 2) + '\n' });
    }
    for (const row of audit.requestImageVariants ?? []) {
      if (!object(row) || !object(row.target) || ['width','height','maxBytes'].some(key => !Number.isSafeInteger(row.target[key]) || row.target[key] < 1)) fail('REFERENCE_INVALID');
      noteVariant(requestImageVariantId({ attachmentId: row.attachmentId }, row.target), row.attachmentId);
    }
    for (const attachmentId of [...candidateRefs].sort()) {
      if (!/^sha256:[a-f0-9]{64}$/u.test(attachmentId)) fail('REFERENCE_INVALID');
      if (retainedRefs.has(attachmentId)) { shared.push(attachmentId); continue; }
      if (!referenceCoverage) { pending.push('attachment-reference-coverage'); continue; }
      const key = attachmentId.slice(7), group = [];
      for (const path of [join(attachmentRoot, 'objects', key.slice(0,2), key), join(attachmentRoot, 'file-objects', key.slice(0,2), key), join(attachmentRoot, 'files', key.slice(0,2), key), join(legacyAttachmentRoot, key + '.png')]) group.push(...await inventory(path, { hardlinks: true }));
      for (const file of group.filter(row => row.type === 'file')) {
        if (file.digest !== key || group.filter(other => other.type === 'file' && other.dev === file.dev && other.ino === file.ino).length !== file.nlink) fail('ATTACHMENT_LINK_UNPROVEN');
      }
      deletions.push(...group);
    }
    // Every cache leaf needs positive attribution; a retained mapping protects it
    // even if no live Session currently cites it. Unknown historical budgets are
    // never guessed from the current model's configuration.
    for (const row of await inventory(attachmentCacheRoot)) if (row.type === 'file') {
      const key = row.path.split('/').at(-1), attachmentId = variants.get('sha256:' + key);
      if (!attachmentId || row.path !== join(attachmentCacheRoot, 'request-images', key.slice(0,2), key)) {
        if (candidateRefs.size) pending.push('attachment-request-cache-unproven');
      } else if (referenceCoverage && candidateRefs.has(attachmentId) && !retainedRefs.has(attachmentId)) deletions.push(row);
    }
    const plan = { roots, memorySpaceId: memoryBinding.memorySpaceId, ...(storedPaths.value ? { trustedRootAlias: storedPaths.value } : {}), deletions, replacements, shared, pending: [...new Set(pending)] };
    journal = { version: 1, operationId, manifestDigest, epoch, phase: 'prepared', planDigest: digest(plan), plan };
    await checkpoint('prepared'); return receipt();
  }
  async function validate() {
    for (const action of journal.plan.replacements) {
      await safePath(action.path); const file = await info(action.path);
      if (!file?.isFile() || file.nlink !== 1) fail('SOURCE_CHANGED');
      const current = hash(await readFile(action.path)); if (!(journal.phase === 'complete' ? [hash(action.after)] : [action.before, hash(action.after)]).includes(current)) fail('SOURCE_CHANGED');
    }
    const planned = new Map(journal.plan.deletions.map(row => [row.path, row]));
    for (const row of journal.plan.deletions) {
      await safePath(row.path); const value = await info(row.path); if (value && journal.phase === 'complete') fail('RESIDUE_PRESENT'); if (!value) { if (journal.phase === 'prepared') fail('SOURCE_CHANGED'); continue; }
      if (row.type === 'directory') {
        if (!value.isDirectory() || (await readdir(row.path)).some(name => !planned.has(join(row.path, name)))) fail('SOURCE_CHANGED');
      } else if (!value.isFile() || hash(await readFile(row.path)) !== row.digest || value.dev !== row.dev || value.ino !== row.ino || value.nlink > row.nlink) fail('SOURCE_CHANGED');
    }
  }
  async function commit() {
    await guard(); if (!await load()) fail('PREPARE_REQUIRED'); await validate();
    if (journal.phase === 'complete') return receipt();
    await retainMemory();
    await checkpoint('applying');
    for (const action of journal.plan.replacements) {
      await guard(); await safePath(action.path);
      const current = await lstat(action.path);
      if (!current.isFile() || current.nlink !== 1 || ![action.before, hash(action.after)].includes(hash(await readFile(action.path)))) fail('SOURCE_CHANGED');
      await atomic(action.path, action.after); await onCheckpoint('replaced:' + action.path);
    }
    for (const row of [...journal.plan.deletions].sort((a,b) => b.path.length - a.path.length)) {
      await guard(); await safePath(row.path); if (!await info(row.path)) continue;
      if (row.type === 'file') {
        const current = await lstat(row.path);
        if (!current.isFile() || current.dev !== row.dev || current.ino !== row.ino || current.nlink > row.nlink || hash(await readFile(row.path)) !== row.digest) fail('SOURCE_CHANGED');
        await unlink(row.path);
      } else await rmdir(row.path);
      await syncDirectory(dirname(row.path)); await onCheckpoint('removed:' + row.path);
    }
    await validate(); await checkpoint('complete'); return receipt();
  }
  let chain = Promise.resolve();
  const serial = operation => { const result = chain.then(operation); chain = result.catch(() => {}); return result; };
  async function retainMemory() {
    await guard(); if (!journal && !await load()) fail('PREPARE_REQUIRED');
    const value = await memorySpaces.retain(manifest.companionId, { operationId });
    if (value.memorySpaceId !== journal.plan.memorySpaceId || value.state !== 'retained') fail('MEMORY_RETENTION_CONFLICT');
    return { memorySpaceId: value.memorySpaceId, state: value.state };
  }
  return Object.freeze({ prepare: () => serial(prepare), commit: () => serial(commit), recover: () => serial(commit), retainMemory: () => serial(retainMemory),
    status: () => serial(async () => { await guard(); if (!await load()) return null; await validate(); return receipt(); }) });
}

/** Read-only source of all companion SQLite attachment ownership, including
 * blobs seen only in engine history. Run before store deletion under the same
 * offline fence. Resolve Android hosted upload IDs/URLs through uploads. */
export async function auditCompanionStoreAttachmentReferences({ root, operationId, manifest, manifestDigest, authority } = {}) {
  if (typeof root !== 'string' || !isAbsolute(root) || !object(manifest) || digest(manifest) !== manifestDigest
    || !id(manifest.companionId) || !id(operationId) || typeof authority?.assertHeld !== 'function') fail('INVALID_INPUT');
  const proof = await authority.assertHeld({ root, operationId, scope: 'companion-owned-data', manifestDigest });
  if (proof?.root !== root || proof?.operationId !== operationId || proof?.manifestDigest !== manifestDigest
    || proof?.hostStopped !== true || proof?.admissionsClosed !== true || !id(proof?.admissionEpoch)) fail('AUTHORITY_LOST');
  const path = join(root, 'companions.sqlite');
  for (const suffix of ['', '-wal', '-shm', '-journal']) {
    await safePath(path + suffix); const value = await info(path + suffix);
    if (!value) { if (!suffix) fail('STORE_MISSING'); continue; }
    if (!value.isFile() || value.nlink !== 1 || suffix === '-journal' && value.size) fail('STORE_INVALID');
  }
  const db = new DatabaseSync(path, { readOnly: true });
  try {
    db.exec('PRAGMA query_only=ON; BEGIN');
    const version = db.prepare('PRAGMA user_version').get().user_version;
    if (![2,3].includes(version)) fail('SCHEMA_UNSUPPORTED');
    const expected = companionStoreMaintenanceSchema(version);
    const normalize = values => values.map(row => ({ ...row, sql: row.sql?.replace(/\bIF\s+NOT\s+EXISTS\b/giu, '').replace(/\s+/gu, ' ').replace(/\s*([(),=])\s*/gu, '$1').trim().toLowerCase() ?? null }));
    const catalog = db.prepare('SELECT type,name,tbl_name,sql FROM sqlite_master ORDER BY type,name').all();
    if (db.prepare('PRAGMA application_id').get().application_id !== expected.applicationId
      || !isDeepStrictEqual(normalize(catalog), normalize(expected.catalog)) || db.prepare('PRAGMA quick_check').all().some(row => row.quick_check !== 'ok')
      || db.prepare('PRAGMA foreign_key_check').all().length) fail('STORE_INVALID');
    if (!db.prepare('SELECT 1 FROM profiles WHERE id=?').get(manifest.companionId)) fail('TARGET_MISSING');
    const deletedDrafts = new Set(db.prepare('SELECT id FROM creation_drafts WHERE companion_id=?').all(manifest.companionId).map(row => row.id));
    const uploads = [], retained = new Set(), removed = new Set(), rows = [];
    for (const table of catalog.filter(row => row.type === 'table').map(row => row.name)) {
      if (['maintenance_operations','creation_tombstones'].includes(table)) continue;
      for (const row of db.prepare('SELECT * FROM "' + table + '"').iterate()) {
        const deleted = table === 'profiles' ? row.id === manifest.companionId
          : table === 'draft_uploads' ? deletedDrafts.has(row.draft_id) : row.companion_id === manifest.companionId;
        if (row.data === undefined) continue;
        let value; try { value = JSON.parse(row.data); } catch { fail('STORE_INVALID'); }
        if (!object(value)) fail('STORE_INVALID');
        rows.push({ value, deleted }); refs(value, deleted ? removed : retained);
        if (['companion_uploads','user_uploads','draft_uploads'].includes(table)) {
          if (!object(value.ref) || !/^sha256:[a-f0-9]{64}$/u.test(value.ref.attachmentId) || typeof value.url !== 'string' || value.id !== row.upload_id) fail('REFERENCE_INVALID');
          uploads.push({ ownerKind: table === 'companion_uploads' ? 'companion' : table === 'draft_uploads' ? 'draft' : 'user',
            ownerId: row.companion_id ?? row.draft_id ?? 'user-profile', uploadId: row.upload_id,
            url: value.url, attachmentId: value.ref.attachmentId, removed: deleted });
        }
      }
    }
    const urls = new Map(uploads.map(row => [row.url, row.attachmentId]));
    if (urls.size !== uploads.length) fail('REFERENCE_CONFLICT');
    const visit = (value, output) => {
      if (typeof value === 'string' && value.startsWith('/droy/companions/') && value.includes('/attachments/')) {
        const found = urls.get(value); if (!found) fail('REFERENCE_UNPROVEN'); output.add(found);
      } else if (Array.isArray(value)) for (const child of value) visit(child, output);
      else if (object(value)) for (const child of Object.values(value)) visit(child, output);
    };
    for (const row of rows) visit(row.value, row.deleted ? removed : retained);
    const after = await authority.assertHeld({ root, operationId, scope: 'companion-owned-data', manifestDigest });
    if (after?.admissionEpoch !== proof.admissionEpoch || after?.hostStopped !== true || after?.admissionsClosed !== true
      || after?.root !== root || after?.operationId !== operationId || after?.manifestDigest !== manifestDigest) fail('AUTHORITY_LOST');
    return { version: 1, scope: 'companion-store-attachment-references', complete: true, operationId, manifestDigest,
      retainedAttachmentIds: [...retained].sort(), targetAttachmentIds: [...removed].sort(), uploads,
      sessionOwners: db.prepare('SELECT session_id AS sessionId,companion_id AS companionId FROM sessions ORDER BY session_id').all() };
  } finally { if (db.isTransaction) db.exec('ROLLBACK'); db.close(); }
}
