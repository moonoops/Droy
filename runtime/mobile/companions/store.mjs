import { companionVisibleMessage } from './reply-text.mjs';
import { DatabaseSync } from 'node:sqlite';
import { randomUUID, createHash } from 'node:crypto';
import { mkdir, chmod } from 'node:fs/promises';
import { isAbsolute, join } from 'node:path';
import { assertCompanionId, assertTemplateId, COMPANION_TEMPLATE_IDS, companionTemplates, profileFromTemplate,
  normalizedName, profileNameKeys, upgradeCompanionPreset } from './profiles.mjs';

const APPLICATION_ID = 1146241872;
const BASE_SCHEMA_SQL = `
  CREATE TABLE profiles(id TEXT PRIMARY KEY,data TEXT NOT NULL);
  CREATE TABLE messages(seq INTEGER PRIMARY KEY AUTOINCREMENT,companion_id TEXT NOT NULL REFERENCES profiles(id),message_id TEXT NOT NULL,client_id TEXT,fingerprint TEXT NOT NULL,data TEXT NOT NULL,UNIQUE(companion_id,message_id),UNIQUE(companion_id,client_id));
  CREATE INDEX messages_companion_sequence ON messages(companion_id,seq DESC);
  CREATE TABLE sessions(session_id TEXT PRIMARY KEY,companion_id TEXT NOT NULL REFERENCES profiles(id),kind TEXT NOT NULL,workspace_id TEXT NOT NULL,task_id TEXT);
  CREATE INDEX sessions_owner ON sessions(companion_id,kind);
  CREATE TABLE settings(singleton INTEGER PRIMARY KEY,data TEXT NOT NULL);
  CREATE TABLE read_positions(companion_id TEXT PRIMARY KEY REFERENCES profiles(id),seq INTEGER NOT NULL DEFAULT 0);`;
const EXTENSION_SCHEMA_SQL = `
  CREATE TABLE IF NOT EXISTS companion_state(companion_id TEXT PRIMARY KEY REFERENCES profiles(id),data TEXT NOT NULL);
  CREATE TABLE IF NOT EXISTS conversation_state(companion_id TEXT PRIMARY KEY REFERENCES profiles(id),pinned INTEGER NOT NULL DEFAULT 0,hidden INTEGER NOT NULL DEFAULT 0);
  CREATE TABLE IF NOT EXISTS message_tombstones(companion_id TEXT NOT NULL REFERENCES profiles(id),message_id TEXT NOT NULL,client_id TEXT,PRIMARY KEY(companion_id,message_id));
  CREATE TABLE IF NOT EXISTS companion_uploads(seq INTEGER PRIMARY KEY AUTOINCREMENT,companion_id TEXT NOT NULL REFERENCES profiles(id),upload_id TEXT NOT NULL,data TEXT NOT NULL,UNIQUE(companion_id,upload_id));
  CREATE INDEX IF NOT EXISTS uploads_owner_sequence ON companion_uploads(companion_id,seq DESC);
  CREATE TABLE IF NOT EXISTS user_profile(singleton INTEGER PRIMARY KEY,data TEXT NOT NULL);
  CREATE TABLE IF NOT EXISTS user_uploads(upload_id TEXT PRIMARY KEY,data TEXT NOT NULL);`;
const CREATION_SCHEMA_SQL = `
  CREATE TABLE name_claims(name_key TEXT NOT NULL,companion_id TEXT NOT NULL REFERENCES profiles(id),PRIMARY KEY(name_key,companion_id));
  CREATE INDEX name_claims_owner ON name_claims(companion_id);
  CREATE TABLE creation_drafts(id TEXT PRIMARY KEY,request_id TEXT NOT NULL UNIQUE,state TEXT NOT NULL,created_at TEXT NOT NULL,companion_id TEXT REFERENCES profiles(id));
  CREATE TABLE draft_uploads(draft_id TEXT NOT NULL REFERENCES creation_drafts(id),upload_id TEXT NOT NULL,data TEXT NOT NULL,PRIMARY KEY(draft_id,upload_id));
  CREATE TABLE creation_receipts(request_id TEXT PRIMARY KEY,fingerprint TEXT NOT NULL,companion_id TEXT NOT NULL REFERENCES profiles(id));`;
export const COMPANION_STORE_MAINTENANCE_SQL = `
  CREATE TABLE IF NOT EXISTS creation_tombstones(kind TEXT NOT NULL,digest TEXT NOT NULL,PRIMARY KEY(kind,digest));
  CREATE TABLE IF NOT EXISTS maintenance_operations(operation_id TEXT PRIMARY KEY,epoch_digest TEXT NOT NULL,target_id TEXT NOT NULL,manifest_digest TEXT NOT NULL,retained_digest TEXT NOT NULL,state TEXT NOT NULL,tombstones TEXT NOT NULL);`;

/** The provider owns the exact SQLite schema, including automatic indexes. */
export function companionStoreMaintenanceSchema(version = 3) {
  if (![2, 3].includes(version)) fail('COMPANION_FORMAT_UNSUPPORTED');
  const db = new DatabaseSync(':memory:');
  try {
    db.exec(BASE_SCHEMA_SQL + EXTENSION_SCHEMA_SQL + CREATION_SCHEMA_SQL + (version === 3 ? COMPANION_STORE_MAINTENANCE_SQL : ''));
    return { version, applicationId: APPLICATION_ID,
      catalog: db.prepare('SELECT type,name,tbl_name,sql FROM sqlite_master ORDER BY type,name').all() };
  } finally { db.close(); }
}

export function companionCreationTombstoneDigest(kind, value) {
  if (!['profile-request', 'draft-request', 'draft-id'].includes(kind)) fail('COMPANION_INVALID_INPUT');
  id(value);
  return createHash('sha256').update('droy-companion-creation-v1\0' + kind + '\0' + value).digest('hex');
}
const PROFILE_FIELDS = ['name', 'avatar', 'personality', 'personalityOverride', 'behaviorStyleOverride', 'replyStyleOverride', 'chatBackground', 'behaviorStyle', 'replyStyle', 'model', 'settings', 'workspaceId', 'chatSessionId'];
const CONTEXT_FIELDS = ['contextSourceIds', 'contextRange', 'contextExcluded'];
const MESSAGE_FIELDS = ['id', 'clientId', 'role', 'type', 'text', 'createdAt', 'status', 'attachments', 'replyTo', 'quoteReply', 'task', 'voice', 'stickerId', 'transcript', 'error', ...CONTEXT_FIELDS];
const MUTABLE_MESSAGE_FIELDS = ['text', 'status', 'attachments', 'task', 'voice', 'transcript', 'error', ...CONTEXT_FIELDS];
const RECEIVED_INPUT_SQL = "json_extract(data,'$.status')='received' AND COALESCE(json_extract(data,'$.contextExcluded'),0)=0 AND (json_extract(data,'$.role')='user' OR (json_extract(data,'$.role')='assistant' AND json_extract(data,'$.type')='task' AND json_extract(data,'$.task.state') IN ('completed','failed','cancelled')))";
const CHAT_MESSAGE_SQL = "json_extract(data,'$.role') IN ('user','assistant') AND COALESCE(json_extract(data,'$.type'),'text')!='task'";
const UNREAD_MESSAGE_SQL = "json_extract(data,'$.role')='assistant' AND COALESCE(json_extract(data,'$.type'),'text')!='task'";
const isChatMessage = message => !!message && ['user','assistant'].includes(message.role) && message.type !== 'task';
const fail = code => { throw Object.assign(new Error(code), { code }); };
const object = value => value && typeof value === 'object' && !Array.isArray(value);
const clone = value => structuredClone(value);
const DEFAULT_USER_PROFILE = Object.freeze({ name: '', bio: '', avatarUrl: '/droy/companions/assets/user-default.png' });
const DEFAULT_QUIET_HOURS = Object.freeze({ enabled: true, start: '22:00', end: '09:00' });
const backgroundUrl = imageId => '/droy/companions/assets/wallpaper-' + imageId + '.webp';
function fields(value, allowed) {
  if (!object(value) || Object.keys(value).some(key => !allowed.includes(key))) fail('COMPANION_INVALID_INPUT');
}
function id(value) {
  if (typeof value !== 'string' || !/^[A-Za-z0-9_.:-]{1,160}$/.test(value)) fail('COMPANION_INVALID_ID');
  return value;
}
function text(value, max, empty = true) {
  if (typeof value !== 'string' || (!empty && !value.trim()) || value.includes('\0') || Buffer.byteLength(value) > max) fail('COMPANION_INVALID_INPUT');
  return value;
}
function timestamp(value) {
  if (typeof value !== 'string' || !Number.isFinite(Date.parse(value))) fail('COMPANION_INVALID_TIME');
  return value;
}
function sequence(value) {
  if(typeof value!=='string'||!/^seq:\d+$/u.test(value)||!Number.isSafeInteger(Number(value.slice(4))))fail('COMPANION_INVALID_INPUT');
  return Number(value.slice(4));
}
function afterIndex(values,value) {
  let low=0,high=values.length;
  while(low<high){const middle=(low+high)>>>1;if(values[middle]<=value)low=middle+1;else high=middle;}
  return low;
}
function canonical(value) {
  if (Array.isArray(value)) return '[' + value.map(canonical).join(',') + ']';
  if (object(value)) return '{' + Object.keys(value).sort().filter(key => value[key] !== undefined).map(key => JSON.stringify(key) + ':' + canonical(value[key])).join(',') + '}';
  return JSON.stringify(value);
}
function metadata(value, max = 65536) {
  if (!object(value)) fail('COMPANION_INVALID_INPUT');
  let serialized;
  try { serialized = JSON.stringify(value); } catch { fail('COMPANION_INVALID_INPUT'); }
  if (Buffer.byteLength(serialized) > max) fail('COMPANION_MESSAGE_TOO_LARGE');
  return JSON.parse(serialized);
}
function stateMetadata(value,{keepEmpty=false}={}) {
  if(!object(value))fail('COMPANION_INVALID_INPUT');
  const {pendingNotificationIds,pendingNotificationRemovals,...context}=value;
  const state=metadata(context,262144);
  // Notification identities have their own small budget, so adding a notice
  // cannot reject an otherwise valid chat context at its existing size limit.
  for(const [key,ids] of Object.entries({pendingNotificationIds,pendingNotificationRemovals}))if(ids!==undefined) {
    if(!Array.isArray(ids)||ids.length>200)fail('COMPANION_INVALID_INPUT');
    if(ids.length||keepEmpty)state[key]=[...new Set(ids.map(id))];
  }
  return state;
}
function uploadData(value) {
  const upload=metadata(value);
  id(upload.id);text(upload.name,4096,false);text(upload.mediaType,256,false);
  if(!Number.isSafeInteger(upload.bytes)||upload.bytes<0||!object(upload.ref))fail('COMPANION_INVALID_INPUT');
  return upload;
}
function validateModel(value) {
  if (value === null) return null;
  fields(value, ['provider', 'model', 'reasoningEffort']);
  text(value.provider, 256, false); text(value.model, 256, false);
  if (value.reasoningEffort !== undefined) text(value.reasoningEffort, 80, false);
  return clone(value);
}
function validateSettings(value, current) {
  fields(value, ['proactiveEnabled', 'quietHours', 'allowProfanity', 'memoryEnabled', 'stickerPackIds']);
  if (value.stickerPackIds !== undefined) {
    if (!Array.isArray(value.stickerPackIds) || value.stickerPackIds.length > 100
      || value.stickerPackIds.some(value => typeof value !== 'string' || !/^[A-Za-z0-9_.:-]{1,160}$/u.test(value))
      || new Set(value.stickerPackIds).size !== value.stickerPackIds.length) fail('COMPANION_INVALID_INPUT');
  }
  for (const key of ['proactiveEnabled', 'allowProfanity', 'memoryEnabled']) {
    if (value[key] !== undefined && typeof value[key] !== 'boolean') fail('COMPANION_INVALID_INPUT');
  }
  if (value.quietHours !== undefined) {
    if (value.quietHours !== null) {
      fields(value.quietHours, ['enabled', 'start', 'end']);
      if (value.quietHours.enabled !== undefined && typeof value.quietHours.enabled !== 'boolean') fail('COMPANION_INVALID_INPUT');
      for (const key of ['start', 'end']) if (typeof value.quietHours[key] !== 'string' || !/^(?:[01]\d|2[0-3]):[0-5]\d$/.test(value.quietHours[key])) fail('COMPANION_INVALID_INPUT');
    }
  }
  const next = { ...current, ...clone(value) };
  if (Object.hasOwn(value, 'quietHours')) next.quietHours = value.quietHours === null
    ? { ...DEFAULT_QUIET_HOURS, ...current.quietHours, enabled: false }
    : { enabled: true, ...value.quietHours };
  return next;
}
function validateBackground(value) {
  fields(value, ['type', 'id', 'url', 'position']);
  if (value.type === 'default') {
    if (value.id !== undefined || value.url !== undefined || value.position !== undefined) fail('COMPANION_INVALID_INPUT');
    return { type: 'default' };
  }
  if (value.type === 'preset') {
    if (!['01', '02', '03', '04', '05', '06', '07', '08'].includes(value.id) || value.position !== undefined) fail('COMPANION_INVALID_INPUT');
    const url = backgroundUrl(value.id);
    if (value.url !== undefined && value.url !== url) fail('COMPANION_ATTACHMENT_SCOPE');
    return { type: 'preset', id: value.id, url };
  }
  if (value.type === 'upload') {
    id(value.id);
    if (value.url !== undefined) text(value.url, 4096, false);
    if (value.position !== undefined) {
      fields(value.position, ['x', 'y']);
      for (const axis of ['x', 'y']) if (!Number.isFinite(value.position[axis]) || value.position[axis] < 0 || value.position[axis] > 100)
        fail('COMPANION_INVALID_INPUT');
    }
    return { ...clone(value), position: clone(value.position ?? { x: 50, y: 50 }) };
  }
  fail('COMPANION_INVALID_INPUT');
}
function profilePatch(patch, current) {
  fields(patch, PROFILE_FIELDS);
  const next = clone(patch);
  if (next.name !== undefined) {
    normalizedName(next.name);
    next.name = next.name.trim();
  }
  if (next.avatar !== undefined && next.avatar !== null) {
    text(next.avatar, 4096, false);
    if (!next.avatar.startsWith('/') && !/^https?:\/\//u.test(next.avatar)) fail('COMPANION_INVALID_INPUT');
  }
  for (const key of ['personality', 'behaviorStyle', 'replyStyle']) if (next[key] !== undefined) {
    fields(next[key], ['zh', 'en']);
    text(next[key].zh, 16384, false); text(next[key].en, 16384, false);
  }
  for (const key of ['personalityOverride', 'behaviorStyleOverride', 'replyStyleOverride'])
    if (next[key] !== undefined) text(next[key], 16384);
  if (next.chatBackground !== undefined) next.chatBackground = validateBackground(next.chatBackground);
  if (next.model !== undefined) next.model = validateModel(next.model);
  if (next.settings !== undefined) next.settings = validateSettings(next.settings, current.settings);
  for (const key of ['workspaceId', 'chatSessionId']) if (next[key] !== undefined && next[key] !== null) id(next[key]);
  return next;
}
function messageData(input, messageId) {
  fields(input, MESSAGE_FIELDS);
  if (!['user', 'assistant', 'system'].includes(input.role)) fail('COMPANION_INVALID_INPUT');
  const type = input.type ?? 'text';
  if (!['text', 'voice', 'image', 'file', 'sticker', 'task'].includes(type)) fail('COMPANION_INVALID_INPUT');
  const status = input.status ?? 'sent';
  if (!['pending', 'sending', 'sent', 'failed', 'received', 'processing'].includes(status)) fail('COMPANION_INVALID_INPUT');
  const value = { id: messageId, role: input.role, type, text: text(input.text ?? '', 131072),
    createdAt: timestamp(input.createdAt ?? new Date().toISOString()), status };
  if (input.clientId !== undefined) value.clientId = id(input.clientId);
  if (input.attachments !== undefined) {
    if (!Array.isArray(input.attachments) || input.attachments.length > 10) fail('COMPANION_INVALID_INPUT');
    value.attachments = input.attachments.map(item => metadata(item));
  }
  if (input.replyTo !== undefined && input.replyTo !== null) value.replyTo = id(input.replyTo);
  if (input.quoteReply !== undefined) {
    if (typeof input.quoteReply !== 'boolean' || input.quoteReply && !value.replyTo) fail('COMPANION_INVALID_INPUT');
    value.quoteReply = input.quoteReply;
  }
  if (input.stickerId !== undefined) value.stickerId = id(input.stickerId);
  for (const key of ['voice', 'task']) if (input[key] !== undefined && input[key] !== null) value[key] = metadata(input[key]);
  for (const key of ['transcript', 'error']) if (input[key] !== undefined && input[key] !== null) value[key] = text(input[key], key === 'error' ? 4096 : 131072);
  if(input.contextSourceIds!==undefined) {
    if(!Array.isArray(input.contextSourceIds))fail('COMPANION_INVALID_INPUT');
    value.contextSourceIds=[...new Set(input.contextSourceIds.map(id))];
  }
  if(input.contextRange!==undefined) {
    fields(input.contextRange,['after','through']);
    if(sequence(input.contextRange.after)>sequence(input.contextRange.through))fail('COMPANION_INVALID_INPUT');
    value.contextRange=clone(input.contextRange);
  }
  if(input.contextExcluded!==undefined) {
    if(typeof input.contextExcluded!=='boolean')fail('COMPANION_INVALID_INPUT');
    value.contextExcluded=input.contextExcluded;
  }
  if (type === 'text' && !value.text.trim() && !value.attachments?.length) fail('COMPANION_EMPTY_MESSAGE');
  if (Buffer.byteLength(JSON.stringify(value)) > 262144) fail('COMPANION_MESSAGE_TOO_LARGE');
  return value;
}

/** IM data belongs to the product, while DSH retains execution history. Sequence
 * pagination stays stable when message content or client timestamps change. */
export class CompanionStore {
  #db; #closed = false;
  static async open({ directory } = {}) {
    if (typeof directory !== 'string' || !isAbsolute(directory)) fail('COMPANION_INVALID_DIRECTORY');
    await mkdir(directory, { recursive: true, mode: 0o700 });
    const path = join(directory, 'companions.sqlite');
    const db = new DatabaseSync(path);
    try {
      await chmod(path, 0o600);
      db.exec('PRAGMA busy_timeout=3000; PRAGMA journal_mode=WAL; PRAGMA synchronous=FULL; PRAGMA foreign_keys=ON; PRAGMA secure_delete=ON;');
      const version = db.prepare('PRAGMA user_version').get().user_version;
      const application = db.prepare('PRAGMA application_id').get().application_id;
      if (![0,1,2,3].includes(version) || (version !== 0 && application !== APPLICATION_ID)
        || (version === 0 && (application !== 0 || db.prepare("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%'").get())))
        fail('COMPANION_FORMAT_UNSUPPORTED');
      const store = new CompanionStore(db);
      if (version < 2) store.#transaction(() => {
        if (version === 0) db.exec(BASE_SCHEMA_SQL);
        db.exec(EXTENSION_SCHEMA_SQL + CREATION_SCHEMA_SQL);
        if (version === 0) {
          const profile = profileFromTemplate('kaka');
          db.prepare('INSERT INTO profiles VALUES(?,?)').run(profile.id, JSON.stringify(profile));
        }
        for (const row of db.prepare('SELECT id,data FROM profiles ORDER BY rowid').all()) {
          const saved = JSON.parse(row.data);
          if (saved.id !== row.id) fail('COMPANION_FORMAT_UNSUPPORTED');
          let upgraded = false;
          if (!Object.hasOwn(saved, 'templateId')) { saved.templateId = COMPANION_TEMPLATE_IDS.includes(saved.id) ? saved.id : null; upgraded = true; }
          for (const field of ['personalityOverride', 'chatBackground']) if (!Object.hasOwn(saved, field)) {
            saved[field] = field === 'personalityOverride' ? '' : { type: 'default' }; upgraded = true;
          }
          if (!object(saved.settings)) fail('COMPANION_FORMAT_UNSUPPORTED');
          if (!Object.hasOwn(saved.settings, 'quietHours')) {
            saved.settings.quietHours = { ...DEFAULT_QUIET_HOURS }; upgraded = true;
          } else if (saved.settings.quietHours === null) {
            saved.settings.quietHours = { ...DEFAULT_QUIET_HOURS, enabled: false }; upgraded = true;
          } else if (!Object.hasOwn(saved.settings.quietHours, 'enabled')) {
            saved.settings.quietHours = { ...saved.settings.quietHours, enabled: true }; upgraded = true;
          }
          if (upgraded) db.prepare('UPDATE profiles SET data=? WHERE id=?').run(JSON.stringify(saved), saved.id);
          db.prepare('INSERT OR IGNORE INTO read_positions VALUES(?,0)').run(saved.id);
          store.#putNameClaims(saved, { legacy: true });
        }
        db.prepare('INSERT OR IGNORE INTO settings VALUES(1,?)').run(JSON.stringify({ defaultMode: 'messages' }));
        db.prepare('INSERT OR IGNORE INTO user_profile VALUES(1,?)').run(JSON.stringify(DEFAULT_USER_PROFILE));
        for(const row of db.prepare('SELECT companion_id,data FROM companion_state').all()) {
          const state=JSON.parse(row.data);
          if(!Object.hasOwn(state,'uploads'))continue;
          if(state.uploads!==null&&!object(state.uploads))fail('COMPANION_INVALID_INPUT');
          for(const [uploadId,value] of Object.entries(state.uploads ?? {})) {
            const upload=uploadData(value);
            if(upload.id!==uploadId)fail('COMPANION_INVALID_INPUT');
            store.#saveUpload(row.companion_id,upload);
          }
          delete state.uploads;
          db.prepare('UPDATE companion_state SET data=? WHERE companion_id=?').run(JSON.stringify(state),row.companion_id);
        }
        db.exec(`PRAGMA application_id=${APPLICATION_ID}; PRAGMA user_version=2;`);
      });
      if (version < 3) store.#transaction(() => {
        db.exec(COMPANION_STORE_MAINTENANCE_SQL + 'PRAGMA user_version=3;');
      });
      store.#transaction(() => {
        for (const row of db.prepare('SELECT id,data FROM profiles ORDER BY rowid').all()) {
          const saved = JSON.parse(row.data), updated = upgradeCompanionPreset(saved, { legacySchema: version === 1 });
          if (updated !== saved) db.prepare('UPDATE profiles SET data=? WHERE id=?').run(JSON.stringify(updated), row.id);
        }
      });
      return store;
    } catch (error) { if (db.isTransaction) db.exec('ROLLBACK'); db.close(); throw error; }
  }
  constructor(db) { this.#db = db; }
  #putNameClaims(profile, { previous, legacy = false } = {}) {
    const keys = profileNameKeys(profile), retained = new Set(previous ? profileNameKeys(previous) : []);
    if (!legacy) for (const key of keys) {
      if (!retained.has(key) && this.#db.prepare('SELECT 1 FROM name_claims WHERE name_key=? AND companion_id<>?').get(key, profile.id))
        fail('COMPANION_NAME_CONFLICT');
    }
    this.#db.prepare('DELETE FROM name_claims WHERE companion_id=?').run(profile.id);
    for (const key of keys) this.#db.prepare('INSERT INTO name_claims VALUES(?,?)').run(key, profile.id);
  }
  #ready() { if (this.#closed) fail('COMPANION_STORE_CLOSED'); }
  #transaction(operation) {
    this.#ready(); this.#db.exec('BEGIN IMMEDIATE');
    try { const result = operation(); this.#db.exec('COMMIT'); return result; }
    catch (error) { if (this.#db.isTransaction) this.#db.exec('ROLLBACK'); throw error; }
  }
  #profile(companionId) {
    this.#ready(); assertCompanionId(companionId);
    const row = this.#db.prepare('SELECT data FROM profiles WHERE id=?').get(companionId);
    if (!row) fail('COMPANION_NOT_FOUND');
    return JSON.parse(row.data);
  }
  #creationAvailable(kind, value) {
    if (this.#db.prepare('SELECT 1 FROM creation_tombstones WHERE kind=? AND digest=?').get(kind, companionCreationTombstoneDigest(kind, value)))
      fail('COMPANION_CREATION_DELETED');
  }
  async getProfile(companionId) { return this.#profile(companionId); }
  async suggestName(templateId, locale = 'zh') {
    this.#ready(); assertTemplateId(templateId);
    const base = companionTemplates().find(profile => profile.templateId === templateId).displayName[locale === 'en' ? 'en' : 'zh'];
    for (let suffix = 1; ; suffix++) {
      const name = `${base} ${suffix}`;
      if (!this.#db.prepare('SELECT 1 FROM name_claims WHERE name_key=?').get(normalizedName(name))) return name;
    }
  }
  #draft(draftId, open = false) {
    this.#ready(); id(draftId);
    this.#creationAvailable('draft-id', draftId);
    const draft = this.#db.prepare('SELECT * FROM creation_drafts WHERE id=?').get(draftId);
    if (!draft) fail('COMPANION_DRAFT_NOT_FOUND');
    if (open && draft.state !== 'open') fail('COMPANION_DRAFT_CLOSED');
    return draft;
  }
  async createDraft(input) {
    fields(input, ['requestId']); id(input.requestId);
    return this.#transaction(() => {
      this.#creationAvailable('draft-request', input.requestId);
      let draft = this.#db.prepare('SELECT * FROM creation_drafts WHERE request_id=?').get(input.requestId);
      if (!draft) {
        const draftId = 'draft-' + randomUUID();
        this.#db.prepare('INSERT INTO creation_drafts VALUES(?,?,?,?,NULL)').run(draftId, input.requestId, 'open', new Date().toISOString());
        draft = this.#draft(draftId);
      }
      return { draftId: draft.id, state: draft.state, ...(draft.companion_id ? { companionId: draft.companion_id } : {}) };
    });
  }
  async getDraft(draftId) {
    const draft = this.#draft(draftId);
    return { draftId: draft.id, state: draft.state, createdAt: draft.created_at,
      ...(draft.companion_id ? { companionId: draft.companion_id } : {}),
      uploads: draft.state === 'open' ? this.#db.prepare('SELECT data FROM draft_uploads WHERE draft_id=? ORDER BY rowid').all(draftId).map(row => JSON.parse(row.data)) : [] };
  }
  async putDraftUpload(draftId, input) {
    const upload = uploadData(input);
    if (!upload.mediaType.startsWith('image/') || upload.url !== `/droy/companions/creation-drafts/${draftId}/attachments/${upload.id}`)
      fail('COMPANION_ATTACHMENT_SCOPE');
    return this.#transaction(() => {
      this.#draft(draftId, true);
      const existing = this.#db.prepare('SELECT data FROM draft_uploads WHERE draft_id=? AND upload_id=?').get(draftId, upload.id);
      if (existing) {
        if (canonical(JSON.parse(existing.data)) !== canonical(upload)) fail('COMPANION_ATTACHMENT_CONFLICT');
      } else this.#db.prepare('INSERT INTO draft_uploads VALUES(?,?,?)').run(draftId, upload.id, JSON.stringify(upload));
      return upload;
    });
  }
  async getDraftUpload(draftId, uploadId) {
    this.#draft(draftId, true); id(uploadId);
    const row = this.#db.prepare('SELECT data FROM draft_uploads WHERE draft_id=? AND upload_id=?').get(draftId, uploadId);
    return row ? JSON.parse(row.data) : null;
  }
  async cancelDraft(draftId) {
    return this.#transaction(() => {
      const draft = this.#draft(draftId);
      if (draft.state === 'committed') return { cancelled: false, state: 'committed', companionId: draft.companion_id };
      this.#db.prepare("UPDATE creation_drafts SET state='cancelled' WHERE id=?").run(draftId);
      return { cancelled: true, state: 'cancelled' };
    });
  }
  async createProfile(input) {
    fields(input, ['requestId', 'templateId', 'name', 'personalityOverride', 'behaviorStyleOverride', 'replyStyleOverride', 'model', 'settings', 'chatBackground', 'draftId', 'avatarUploadId']);
    this.#ready(); id(input.requestId); this.#creationAvailable('profile-request', input.requestId);
    assertTemplateId(input.templateId); normalizedName(input.name);
    if (input.draftId !== undefined) id(input.draftId);
    if (input.avatarUploadId !== undefined) { id(input.avatarUploadId); if (!input.draftId) fail('COMPANION_ATTACHMENT_SCOPE'); }
    const preset = profileFromTemplate(input.templateId);
    const patch = profilePatch({ name: input.name, personalityOverride: input.personalityOverride === undefined ? '' : input.personalityOverride,
      ...(input.behaviorStyleOverride === undefined ? {} : { behaviorStyleOverride: input.behaviorStyleOverride }),
      ...(input.replyStyleOverride === undefined ? {} : { replyStyleOverride: input.replyStyleOverride }),
      model: input.model === undefined ? null : input.model,
      ...(input.settings === undefined ? {} : { settings: input.settings }),
      ...(input.chatBackground === undefined ? {} : { chatBackground: input.chatBackground }) }, preset);
    const content = { templateId: input.templateId, ...patch,
      settings: patch.settings ?? clone(preset.settings), chatBackground: patch.chatBackground ?? clone(preset.chatBackground),
      draftId: input.draftId ?? null, avatarUploadId: input.avatarUploadId ?? null };
    for (const key of ['behaviorStyleOverride', 'replyStyleOverride']) if (content[key] === '') delete content[key];
    if (content.chatBackground.type === 'upload') {
      if (!content.draftId) fail('COMPANION_ATTACHMENT_SCOPE');
      const url = `/droy/companions/creation-drafts/${content.draftId}/attachments/${content.chatBackground.id}`;
      if (content.chatBackground.url !== undefined && content.chatBackground.url !== url) fail('COMPANION_ATTACHMENT_SCOPE');
      content.chatBackground.url = url;
    }
    const fingerprint = createHash('sha256').update(canonical(content)).digest('hex');
    return this.#transaction(() => {
      this.#creationAvailable('profile-request', input.requestId);
      const receipt = this.#db.prepare('SELECT * FROM creation_receipts WHERE request_id=?').get(input.requestId);
      if (receipt) {
        const existing = this.#profile(receipt.companion_id);
        // Requests committed before proactive defaults changed must replay exactly.
        const legacyFingerprint = (existing.templateVersion === undefined || existing.legacyProactiveDefault === true) && input.settings?.proactiveEnabled === undefined
          ? createHash('sha256').update(canonical({ ...content, settings: { ...content.settings, proactiveEnabled: false } })).digest('hex') : null;
        if (receipt.fingerprint !== fingerprint && receipt.fingerprint !== legacyFingerprint) fail('COMPANION_CREATION_CONFLICT');
        return { requestId: input.requestId, replayed: true, profile: existing };
      }
      if (content.draftId) this.#draft(content.draftId, true);
      const profile = { ...profileFromTemplate(content.templateId, { name: content.name, model: content.model }),
        personalityOverride: content.personalityOverride, behaviorStyleOverride: content.behaviorStyleOverride ?? '', replyStyleOverride: content.replyStyleOverride ?? '', settings: clone(content.settings), chatBackground: clone(content.chatBackground) };
      const uploads = new Map();
      const transferImage = uploadId => {
        if (!uploads.has(uploadId)) {
          const row = this.#db.prepare('SELECT data FROM draft_uploads WHERE draft_id=? AND upload_id=?').get(content.draftId, uploadId);
          const upload = row ? JSON.parse(row.data) : null;
          if (!upload?.mediaType?.startsWith('image/') || upload.url !== `/droy/companions/creation-drafts/${content.draftId}/attachments/${uploadId}`)
            fail('COMPANION_ATTACHMENT_SCOPE');
          uploads.set(uploadId, { ...upload, url: `/droy/companions/${profile.id}/attachments/${uploadId}` });
        }
        return uploads.get(uploadId);
      };
      if (content.avatarUploadId) profile.avatar = transferImage(content.avatarUploadId).url;
      if (content.chatBackground.type === 'upload') profile.chatBackground.url = transferImage(content.chatBackground.id).url;
      this.#db.prepare('INSERT INTO profiles VALUES(?,?)').run(profile.id, JSON.stringify(profile));
      this.#db.prepare('INSERT INTO read_positions VALUES(?,0)').run(profile.id);
      this.#putNameClaims(profile);
      for (const upload of uploads.values()) {
        this.#saveUpload(profile.id, upload);
        this.#db.prepare('DELETE FROM draft_uploads WHERE draft_id=? AND upload_id=?').run(content.draftId, upload.id);
      }
      this.#db.prepare('INSERT INTO creation_receipts VALUES(?,?,?)').run(input.requestId, fingerprint, profile.id);
      if (content.draftId) this.#db.prepare("UPDATE creation_drafts SET state='committed',companion_id=? WHERE id=?").run(profile.id, content.draftId);
      return { requestId: input.requestId, replayed: false, profile: clone(profile) };
    });
  }
  async getCreation(requestId) {
    this.#ready(); id(requestId);
    this.#creationAvailable('profile-request', requestId);
    const receipt = this.#db.prepare('SELECT companion_id FROM creation_receipts WHERE request_id=?').get(requestId);
    return receipt ? { requestId, profile: this.#profile(receipt.companion_id) } : null;
  }
  async restoreRetainedProfile(snapshot) {
    if(!snapshot||typeof snapshot!=='object'||Array.isArray(snapshot))fail('COMPANION_INVALID_INPUT');
    assertCompanionId(snapshot.id);assertTemplateId(snapshot.templateId);
    const base=profileFromTemplate(snapshot.templateId),patch=profilePatch(Object.fromEntries(
      ['name','personality','behaviorStyle','replyStyle','personalityOverride','behaviorStyleOverride','replyStyleOverride']
        .filter(key=>snapshot[key]!==undefined).map(key=>[key,snapshot[key]])),base);
    return this.#transaction(()=>{
      const existing=this.#db.prepare('SELECT data FROM profiles WHERE id=?').get(snapshot.id);
      if(existing)return clone(JSON.parse(existing.data));
      const profile={...base,...patch,id:snapshot.id,workspaceId:null,chatSessionId:null,
        nameCustomized:snapshot.nameCustomized===true,personaRevision:snapshot.personaRevision??1};
      // A restored name may have been claimed by a new companion. Preserve the
      // archived identity, but use the same unique-name policy as creation.
      if(profileNameKeys(profile).some(key=>this.#db.prepare('SELECT 1 FROM name_claims WHERE name_key=?').get(key))) {
        const name=profile.name;let suffix=2;
        do {profile.name=`${name} ${suffix++}`;} while(this.#db.prepare('SELECT 1 FROM name_claims WHERE name_key=?').get(normalizedName(profile.name)));
        profile.nameCustomized=true;
      }
      this.#db.prepare('INSERT INTO profiles VALUES(?,?)').run(profile.id,JSON.stringify(profile));
      this.#db.prepare('INSERT INTO read_positions VALUES(?,0)').run(profile.id);this.#putNameClaims(profile);
      return clone(profile);
    });
  }
  async getState(companionId) {
    this.#profile(companionId);
    const row = this.#db.prepare('SELECT data FROM companion_state WHERE companion_id=?').get(companionId);
    return row ? JSON.parse(row.data) : {};
  }
  async updateState(companionId, patch) {
    this.#profile(companionId);
    const validated = typeof patch === 'function' ? null : stateMetadata(patch,{keepEmpty:true});
    return this.#transaction(() => {
      const current = this.#db.prepare('SELECT data FROM companion_state WHERE companion_id=?').get(companionId);
      const state = current ? JSON.parse(current.data) : {};
      const update = typeof patch === 'function' ? patch(clone(state)) : validated;
      if (update && typeof update.then === 'function') {
        Promise.resolve(update).catch(() => {});
        fail('COMPANION_INVALID_INPUT');
      }
      if(object(update)&&Object.hasOwn(update,'uploads'))fail('COMPANION_UPLOAD_API_REQUIRED');
      const next = stateMetadata({ ...state, ...stateMetadata(update,{keepEmpty:true}) });
      this.#db.prepare('INSERT INTO companion_state VALUES(?,?) ON CONFLICT(companion_id) DO UPDATE SET data=excluded.data').run(companionId, JSON.stringify(next));
      return next;
    });
  }
  #saveUpload(companionId,upload) {
    const current=this.#db.prepare('SELECT data FROM companion_uploads WHERE companion_id=? AND upload_id=?').get(companionId,upload.id);
    if(current) {
      const existing=JSON.parse(current.data);
      if(canonical(existing)!==canonical(upload))fail('COMPANION_ATTACHMENT_CONFLICT');
      return existing;
    }
    this.#db.prepare('INSERT INTO companion_uploads(companion_id,upload_id,data) VALUES(?,?,?)').run(companionId,upload.id,JSON.stringify(upload));
    return upload;
  }
  async putUpload(companionId,input) {
    this.#profile(companionId);
    const upload=uploadData(input);
    return this.#transaction(()=>this.#saveUpload(companionId,upload));
  }
  async getUpload(companionId,attachmentId) {
    this.#profile(companionId);id(attachmentId);
    const row=this.#db.prepare('SELECT data FROM companion_uploads WHERE companion_id=? AND upload_id=?').get(companionId,attachmentId);
    return row?JSON.parse(row.data):null;
  }
  async removeUpload(companionId,attachmentId) {
    const profile = this.#profile(companionId);id(attachmentId);
    const url = '/droy/companions/' + companionId + '/attachments/' + attachmentId;
    if (profile.avatar === url || profile.chatBackground?.type === 'upload' && profile.chatBackground.id === attachmentId)
      fail('COMPANION_ATTACHMENT_IN_USE');
    const removed=this.#db.prepare('DELETE FROM companion_uploads WHERE companion_id=? AND upload_id=?').run(companionId,attachmentId).changes;
    return {removed:removed>0};
  }
  async listUploads(companionId,{before,limit=100}={}) {
    this.#profile(companionId);
    if(!Number.isInteger(limit)||limit<1||limit>200)fail('COMPANION_INVALID_INPUT');
    let sequence=Number.MAX_SAFE_INTEGER;
    if(before!==undefined&&before!==null) {
      if(typeof before!=='string'||!/^seq:\d+$/u.test(before)||!Number.isSafeInteger(Number(before.slice(4)))||Number(before.slice(4))<1)fail('COMPANION_INVALID_INPUT');
      sequence=Number(before.slice(4));
    }
    const rows=this.#db.prepare('SELECT seq,data FROM companion_uploads WHERE companion_id=? AND seq<? ORDER BY seq DESC LIMIT ?').all(companionId,sequence,limit+1);
    const selected=rows.slice(0,limit),hasMore=rows.length>limit;
    return {uploads:selected.map(row=>JSON.parse(row.data)),hasMore,nextBefore:hasMore?'seq:'+selected.at(-1).seq:null};
  }
  async profiles() {
    this.#ready();
    return this.#db.prepare('SELECT data FROM profiles ORDER BY rowid').all().map(row => {
      const profile = JSON.parse(row.data), companionId = profile.id;
      const last = this.#db.prepare(`SELECT data FROM messages WHERE companion_id=? AND ${CHAT_MESSAGE_SQL} ORDER BY seq DESC LIMIT 1`).get(companionId);
      const unread = this.#db.prepare(`SELECT COUNT(*) count FROM messages WHERE companion_id=? AND seq>(SELECT seq FROM read_positions WHERE companion_id=?) AND ${UNREAD_MESSAGE_SQL}`).get(companionId, companionId);
      const conversation = this.#conversation(companionId), lastMessage = last ? this.#chatMessage(companionId, JSON.parse(last.data)) : null;
      return { ...profile, lastMessage, lastActivityAt: lastMessage?.createdAt ?? null, ...conversation, unreadCount: unread.count };
    });
  }
  #conversation(companionId) {
    const row = this.#db.prepare('SELECT pinned,hidden FROM conversation_state WHERE companion_id=?').get(companionId);
    return { pinned: row?.pinned === 1, hidden: row?.hidden === 1 };
  }
  async updateConversation(companionId, input) {
    this.#profile(companionId);
    fields(input, ['action']);
    if (!['pin', 'unpin', 'hide', 'read'].includes(input.action)) fail('COMPANION_INVALID_INPUT');
    return this.#transaction(() => {
      const current = this.#conversation(companionId);
      const next = input.action === 'hide' ? { pinned: false, hidden: true }
        : { ...current, ...(input.action === 'pin' ? { pinned: true } : input.action === 'unpin' ? { pinned: false } : {}) };
      if (input.action === 'hide' || input.action === 'read') {
        // Snapshot the read boundary in the same write transaction as hiding.
        // Messages committed later retain their own unread state.
        this.#db.prepare('UPDATE read_positions SET seq=MAX(seq,COALESCE((SELECT MAX(seq) FROM messages WHERE companion_id=?),0)) WHERE companion_id=?').run(companionId, companionId);
        this.#updatePendingNotificationIds(companionId,()=>[]);
      }
      this.#db.prepare('INSERT INTO conversation_state VALUES(?,?,?) ON CONFLICT(companion_id) DO UPDATE SET pinned=excluded.pinned,hidden=excluded.hidden')
        .run(companionId, Number(next.pinned), Number(next.hidden));
      const unread = this.#db.prepare(`SELECT COUNT(*) count FROM messages WHERE companion_id=? AND seq>(SELECT seq FROM read_positions WHERE companion_id=?) AND ${UNREAD_MESSAGE_SQL}`).get(companionId, companionId);
      return { ...next, unreadCount: unread.count };
    });
  }
  async updateProfile(companionId, patch) {
    return this.#transaction(() => {
    const current = this.#profile(companionId);
    const next = { ...current, ...profilePatch(patch, current), ...(patch.name !== undefined ? { nameCustomized: true } : {}), updatedAt: new Date().toISOString() };
    if (['personality', 'behaviorStyle', 'replyStyle', 'personalityOverride', 'behaviorStyleOverride', 'replyStyleOverride'].some(key =>
      patch[key] !== undefined && JSON.stringify(patch[key]) !== JSON.stringify(current[key] ?? '')))
      next.personaRevision = (current.personaRevision ?? 1) + 1;
    if (patch.avatar !== undefined && patch.avatar !== current.avatar && patch.avatar !== null
      && !companionTemplates().some(profile => profile.avatar === next.avatar)) {
      const uploadId = String(next.avatar).split('/').at(-1);
      const row = this.#db.prepare('SELECT data FROM companion_uploads WHERE companion_id=? AND upload_id=?').get(companionId, uploadId);
      const upload = row ? JSON.parse(row.data) : null;
      if (!upload?.mediaType?.startsWith('image/') || next.avatar !== `/droy/companions/${companionId}/attachments/${uploadId}` || upload.url !== next.avatar)
        fail('COMPANION_ATTACHMENT_SCOPE');
    }
    if (patch.chatBackground !== undefined && next.chatBackground.type === 'upload') {
      const row = this.#db.prepare('SELECT data FROM companion_uploads WHERE companion_id=? AND upload_id=?').get(companionId, next.chatBackground.id);
      const upload = row ? JSON.parse(row.data) : null;
      if (!upload?.mediaType.startsWith('image/') || next.chatBackground.url !== undefined && next.chatBackground.url !== upload.url)
        fail('COMPANION_ATTACHMENT_SCOPE');
      next.chatBackground.url = upload.url;
    }
    if (patch.name !== undefined) this.#putNameClaims(next, { previous: current });
    this.#db.prepare('UPDATE profiles SET data=? WHERE id=?').run(JSON.stringify(next), companionId);
    return clone(next);
    });
  }
  async getUserProfile() {
    this.#ready();
    return { ...DEFAULT_USER_PROFILE, ...JSON.parse(this.#db.prepare('SELECT data FROM user_profile WHERE singleton=1').get().data) };
  }
  async updateUserProfile(patch) {
    this.#ready(); fields(patch, ['name', 'avatarUrl', 'bio']);
    const next = { ...DEFAULT_USER_PROFILE, ...JSON.parse(this.#db.prepare('SELECT data FROM user_profile WHERE singleton=1').get().data), ...patch };
    if (patch.bio !== undefined) {
      text(patch.bio, 8000);
      if ([...patch.bio].length > 2000 || /[\u0000-\u0008\u000b\u000c\u000e-\u001f\u007f]/u.test(patch.bio)) fail('COMPANION_INVALID_INPUT');
      next.bio = patch.bio.trim();
    }
    if (patch.name !== undefined) {
      text(patch.name, 320, false);
      if ([...patch.name.trim()].length > 80 || /[\u0000-\u001f\u007f]/u.test(patch.name)) fail('COMPANION_INVALID_INPUT');
      next.name = patch.name.trim();
    }
    if (patch.avatarUrl !== undefined) {
      text(patch.avatarUrl, 4096, false);
      if (patch.avatarUrl !== DEFAULT_USER_PROFILE.avatarUrl) {
        const row = this.#db.prepare('SELECT data FROM user_uploads WHERE upload_id=?').get(patch.avatarUrl.split('/').at(-1));
        const upload = row ? JSON.parse(row.data) : null;
        if (!upload?.mediaType.startsWith('image/') || upload.url !== patch.avatarUrl) fail('COMPANION_ATTACHMENT_SCOPE');
      }
    }
    this.#db.prepare('UPDATE user_profile SET data=? WHERE singleton=1').run(JSON.stringify(next));
    return clone(next);
  }
  async putUserUpload(input) {
    this.#ready();
    const upload = uploadData(input);
    if (!upload.mediaType.startsWith('image/') || upload.url !== '/droy/companions/user-profile/attachments/' + upload.id)
      fail('COMPANION_ATTACHMENT_SCOPE');
    const row = this.#db.prepare('SELECT data FROM user_uploads WHERE upload_id=?').get(upload.id);
    if (row) {
      const previous = JSON.parse(row.data);
      if (canonical(previous) !== canonical(upload)) fail('COMPANION_ATTACHMENT_CONFLICT');
      return previous;
    }
    this.#db.prepare('INSERT INTO user_uploads VALUES(?,?)').run(upload.id, JSON.stringify(upload));
    return upload;
  }
  async getUserUpload(attachmentId) {
    this.#ready(); id(attachmentId);
    const row = this.#db.prepare('SELECT data FROM user_uploads WHERE upload_id=?').get(attachmentId);
    return row ? JSON.parse(row.data) : null;
  }
  async removeUserUpload(attachmentId) {
    this.#ready(); id(attachmentId);
    const profile = JSON.parse(this.#db.prepare('SELECT data FROM user_profile WHERE singleton=1').get().data);
    if (profile.avatarUrl === '/droy/companions/user-profile/attachments/' + attachmentId) fail('COMPANION_ATTACHMENT_IN_USE');
    return { removed: this.#db.prepare('DELETE FROM user_uploads WHERE upload_id=?').run(attachmentId).changes > 0 };
  }
  async getSettings() { this.#ready(); return JSON.parse(this.#db.prepare('SELECT data FROM settings WHERE singleton=1').get().data); }
  async updateSettings(patch) {
    this.#ready(); fields(patch, ['defaultMode', 'timeZone']);
    if (patch.defaultMode !== undefined && !['messages', 'work'].includes(patch.defaultMode)) fail('COMPANION_INVALID_INPUT');
    if (patch.timeZone !== undefined) {
      text(patch.timeZone, 128, false);
      const timeZone = patch.timeZone.replace(/^GMT(?=[+-]\d{2}:\d{2}$)/, '');
      if (!/^(?:[A-Za-z_]+(?:\/[A-Za-z0-9_+.-]+)*|[+-]\d{2}:\d{2})$/.test(timeZone)) fail('COMPANION_INVALID_INPUT');
      patch = { ...patch, timeZone };
      try { new Intl.DateTimeFormat('en', { timeZone: patch.timeZone }).format(0); }
      catch (error) { if (error instanceof RangeError) fail('COMPANION_INVALID_INPUT'); throw error; }
    }
    const next = { ...JSON.parse(this.#db.prepare('SELECT data FROM settings WHERE singleton=1').get().data), ...patch };
    this.#db.prepare('UPDATE settings SET data=? WHERE singleton=1').run(JSON.stringify(next));
    return next;
  }
  async bindSession(companionId, binding) {
    this.#profile(companionId);
    fields(binding, ['sessionId', 'workspaceId', 'kind', 'taskId']);
    id(binding.sessionId); id(binding.workspaceId);
    if (!['chat', 'work'].includes(binding.kind)) fail('COMPANION_INVALID_INPUT');
    if (binding.taskId !== undefined && binding.taskId !== null) id(binding.taskId);
    const next = { companionId, sessionId: binding.sessionId, workspaceId: binding.workspaceId, kind: binding.kind, taskId: binding.taskId ?? null };
    this.#transaction(() => {
      const profile = this.#profile(companionId);
      const existing = this.#db.prepare('SELECT * FROM sessions WHERE session_id=?').get(binding.sessionId);
      if (existing && (existing.companion_id !== companionId || existing.kind !== binding.kind || existing.workspace_id !== binding.workspaceId || existing.task_id !== next.taskId)) fail('COMPANION_SESSION_CONFLICT');
      this.#db.prepare('INSERT OR IGNORE INTO sessions VALUES(?,?,?,?,?)').run(binding.sessionId, companionId, binding.kind, binding.workspaceId, next.taskId);
      const updated = { ...profile, workspaceId: binding.workspaceId, ...(binding.kind === 'chat' ? { chatSessionId: binding.sessionId } : {}), updatedAt: new Date().toISOString() };
      this.#db.prepare('UPDATE profiles SET data=? WHERE id=?').run(JSON.stringify(updated), companionId);
    });
    return next;
  }
  async sessionOwner(sessionId) {
    this.#ready(); id(sessionId);
    const row = this.#db.prepare('SELECT * FROM sessions WHERE session_id=?').get(sessionId);
    return row ? { companionId: row.companion_id, sessionId: row.session_id, kind: row.kind, workspaceId: row.workspace_id, taskId: row.task_id } : null;
  }
  async listSessions(companionId, { kind } = {}) {
    this.#profile(companionId);
    if (kind !== undefined && !['chat', 'work'].includes(kind)) fail('COMPANION_INVALID_INPUT');
    const rows = kind === undefined ? this.#db.prepare('SELECT * FROM sessions WHERE companion_id=? ORDER BY rowid').all(companionId)
      : this.#db.prepare('SELECT * FROM sessions WHERE companion_id=? AND kind=? ORDER BY rowid').all(companionId, kind);
    return rows.map(row => ({ companionId, sessionId: row.session_id, kind: row.kind, workspaceId: row.workspace_id, taskId: row.task_id }));
  }
  async retireMemorySessions(companionId,sessionIds,{sourceCaptureBoundary}={}) {
    assertCompanionId(companionId);
    if(!Array.isArray(sessionIds))fail('COMPANION_INVALID_INPUT');
    const selected=new Set(sessionIds.map(id));
    if(sourceCaptureBoundary!==undefined)sequence(sourceCaptureBoundary);
    return this.#transaction(()=>{
      const profile=this.#profile(companionId);
      for(const sessionId of selected) {
        const owner=this.#db.prepare('SELECT companion_id FROM sessions WHERE session_id=?').get(sessionId);
        if(owner&&owner.companion_id!==companionId)fail('COMPANION_SESSION_CONFLICT');
      }
      for(const row of this.#db.prepare('SELECT message_id,data FROM messages WHERE companion_id=?').all(companionId)) {
        const message=JSON.parse(row.data);
        if(message.task&&selected.has(message.task.sessionId)&&['running','queued','cancelling'].includes(message.task.state)) {
          message.task={...message.task,state:'cancelled',summary:''};
          this.#db.prepare('UPDATE messages SET data=? WHERE companion_id=? AND message_id=?').run(JSON.stringify(message),companionId,row.message_id);
        }
      }
      for(const sessionId of selected)this.#db.prepare('DELETE FROM sessions WHERE companion_id=? AND session_id=?').run(companionId,sessionId);
      if(selected.has(profile.chatSessionId)) {profile.chatSessionId=null;this.#db.prepare('UPDATE profiles SET data=? WHERE id=?').run(JSON.stringify(profile),companionId);}
      const row=this.#db.prepare('SELECT data FROM companion_state WHERE companion_id=?').get(companionId);
      const state=row?JSON.parse(row.data):{};
      state.sessionSources=Object.fromEntries(Object.entries(state.sessionSources??{}).filter(([sessionId])=>!selected.has(sessionId)));
      state.invalidSessionIds=[...new Set([...(state.invalidSessionIds??[]),...selected])];
      if(sourceCaptureBoundary!==undefined)Object.assign(state,{memoryRevision:(state.memoryRevision??0)+1,
        expressions:[],jargon:[],jargonCandidates:[],impressions:[],events:[],summary:'',summaryState:null,summaryThrough:null,
        forgottenThrough:sourceCaptureBoundary,learningCursor:sourceCaptureBoundary,factLearningCursor:sourceCaptureBoundary,
        factsThroughMessageId:null,summaryCursor:sourceCaptureBoundary,replyDraft:null,pendingIntent:null,waitingUntil:null,pendingMessageIds:[]});
      this.#db.prepare('INSERT INTO companion_state VALUES(?,?) ON CONFLICT(companion_id) DO UPDATE SET data=excluded.data').run(companionId,JSON.stringify(state));
      return {retired:[...selected]};
    });
  }
  #message(companionId, messageId) {
    this.#profile(companionId); id(messageId);
    const row = this.#db.prepare('SELECT data FROM messages WHERE companion_id=? AND message_id=?').get(companionId, messageId);
    return row ? JSON.parse(row.data) : null;
  }
  async getMessage(companionId, messageId) { return companionVisibleMessage(this.#message(companionId, messageId)); }
  #chatMessage(companionId, value) {
    const message = companionVisibleMessage(value);
    if (!message.replyTo) return message;
    const source = this.#message(companionId, message.replyTo);
    if (!source || isChatMessage(source)) return message;
    const { replyTo, quoteReply, replyPreview, ...visible } = message;
    return visible;
  }
  async getVisibleMessage(companionId, messageId) {
    const message = this.#message(companionId, messageId);
    return isChatMessage(message) ? this.#chatMessage(companionId, message) : null;
  }
  async replyPreviews(companionId, messageIds) {
    this.#profile(companionId);
    if (!Array.isArray(messageIds) || messageIds.length > 200) fail('COMPANION_INVALID_INPUT');
    const ids = [...new Set(messageIds.map(id))];
    if (!ids.length) return {};
    const stateRow = this.#db.prepare('SELECT data FROM companion_state WHERE companion_id=?').get(companionId);
    const state = stateRow ? JSON.parse(stateRow.data) : {};
    const forgotten = new Set(state.forgottenMessageIds ?? []), through = Number((state.forgottenThrough ?? 'seq:0').slice(4));
    const rows = this.#db.prepare('SELECT seq,data FROM messages WHERE companion_id=? AND message_id IN (' + ids.map(() => '?').join(',') + ')').all(companionId, ...ids);
    return Object.fromEntries(rows.flatMap(row => {
      const message = companionVisibleMessage(JSON.parse(row.data));
      if (row.seq <= through || forgotten.has(message.id) || message.contextExcluded || message.role === 'system' || message.type === 'task') return [];
      return [[message.id, { id: message.id, text: [...(message.text || message.transcript || '')].slice(0, 180).join(''), type: message.type }]];
    }));
  }
  async getMessageCursor(companionId,messageId) {
    this.#profile(companionId);id(messageId);
    const row=this.#db.prepare('SELECT seq FROM messages WHERE companion_id=? AND message_id=?').get(companionId,messageId);
    return row?'seq:'+row.seq:null;
  }
  async pendingMessages(companionId) {
    this.#profile(companionId);
    return this.#db.prepare(`SELECT data FROM messages WHERE companion_id=? AND ${RECEIVED_INPUT_SQL} ORDER BY seq`)
      .all(companionId).map(row=>JSON.parse(row.data));
  }
  async completeInputs(companionId,messageIds,options={}) {
    this.#profile(companionId);fields(options,['draftId','memoryRevision']);
    if(!Array.isArray(messageIds))fail('COMPANION_INVALID_INPUT');
    const consumed=new Set(messageIds.map(id)),{draftId,memoryRevision}=options;
    if(draftId!==undefined)id(draftId);
    if(memoryRevision!==undefined&&(!Number.isSafeInteger(memoryRevision)||memoryRevision<0))fail('COMPANION_INVALID_INPUT');
    return this.#transaction(()=>{
      const row=this.#db.prepare('SELECT data FROM companion_state WHERE companion_id=?').get(companionId);
      const state=row?JSON.parse(row.data):{};
      if(draftId!==undefined) {
        const draft=state.replyDraft,expected=memoryRevision??draft?.memoryRevision;
        if(!draft||draft.id!==draftId||draft.companionId!==companionId||!Number.isSafeInteger(expected)
          ||(state.memoryRevision??0)!==expected||draft.memoryRevision!==expected
          ||(state.forgottenThrough??'seq:0')!==draft.baseline)fail('COMPANION_REPLY_STALE');
      }else if(memoryRevision!==undefined&&(state.memoryRevision??0)!==memoryRevision)fail('COMPANION_REPLY_STALE');
      const complete=this.#db.prepare(`UPDATE messages SET data=json_set(data,'$.status','sent') WHERE companion_id=? AND message_id=? AND ${RECEIVED_INPUT_SQL}`);
      const completedIds=[];
      for(const messageId of consumed)if(complete.run(companionId,messageId).changes)completedIds.push(messageId);
      const contact={};
      if(draftId&&state.replyDraft?.proactive&&state.proactiveLastContactDraftId!==draftId) {
        const first=state.replyDraft.parts?.[0];
        const published=first&&this.#db.prepare('SELECT data FROM messages WHERE companion_id=? AND message_id=?').get(companionId,first.id);
        if(published) {
          const at=JSON.parse(published.data).createdAt;
          const repliedSincePrevious=Date.parse(state.lastUserAt??'')>Date.parse(state.proactiveLastContactAt??'');
          Object.assign(contact,{proactiveLastContactDraftId:draftId,proactiveLastContactAt:at,
            proactiveLastContactSourceIds:state.replyDraft.proactive.sourceIds,
            proactiveUnansweredCount:Date.parse(state.lastUserAt??'')>Date.parse(at)?0:(repliedSincePrevious?0:state.proactiveUnansweredCount??0)+1});
        }
      }
      const next={...state,...contact,pendingMessageIds:(state.pendingMessageIds??[]).filter(messageId=>!consumed.has(messageId)),
        ...(draftId!==undefined?{replyDraft:null}:{})};
      this.#db.prepare('INSERT INTO companion_state VALUES(?,?) ON CONFLICT(companion_id) DO UPDATE SET data=excluded.data').run(companionId,JSON.stringify(next));
      return {completedIds};
    });
  }
  async listMessages(companionId, options = {}) { return this.#listMessages(companionId, options); }
  async listVisibleMessages(companionId, options = {}) { return this.#listMessages(companionId, options, true); }
  #listMessages(companionId, { before, after, limit = 50 } = {}, visible = false) {
    this.#profile(companionId);
    if (!Number.isInteger(limit) || limit < 1 || limit > 200) fail('COMPANION_INVALID_INPUT');
    if (before != null && after != null) fail('COMPANION_INVALID_INPUT');
    // Filter before applying the page limit so internal work never creates empty
    // chat pages or consumes a visible message's place in history.
    const filter = visible ? ` AND ${CHAT_MESSAGE_SQL}` : '';
    const project = row => visible ? this.#chatMessage(companionId, JSON.parse(row.data)) : companionVisibleMessage(JSON.parse(row.data));
    if (after !== undefined && after !== null) {
      id(after);
      let sequence;
      if (/^seq:\d+$/u.test(after)) sequence = Number(after.slice(4));
      else {
        const row = this.#db.prepare('SELECT seq FROM messages WHERE companion_id=? AND message_id=?').get(companionId, after);
        if (!row) fail('COMPANION_MESSAGE_NOT_FOUND');
        sequence = row.seq;
      }
      if (!Number.isSafeInteger(sequence) || sequence < 0) fail('COMPANION_INVALID_INPUT');
      const rows = this.#db.prepare(`SELECT seq,data FROM messages WHERE companion_id=? AND seq>?${filter} ORDER BY seq LIMIT ?`).all(companionId, sequence, limit + 1);
      const selected = rows.slice(0, limit);
      return { messages: selected.map(project), hasMore: rows.length > limit,
        nextBefore: null, nextAfter: `seq:${selected.at(-1)?.seq ?? sequence}` };
    }
    let sequence = Number.MAX_SAFE_INTEGER;
    if (before !== undefined && before !== null) {
      id(before);
      if (/^seq:\d+$/u.test(before)) {
        sequence = Number(before.slice(4));
        if (!Number.isSafeInteger(sequence) || sequence < 1) fail('COMPANION_INVALID_INPUT');
      } else {
        const row = this.#db.prepare('SELECT seq FROM messages WHERE companion_id=? AND message_id=?').get(companionId, before);
        if (!row) fail('COMPANION_MESSAGE_NOT_FOUND');
        sequence = row.seq;
      }
    }
    const rows = this.#db.prepare(`SELECT seq,data FROM messages WHERE companion_id=? AND seq<?${filter} ORDER BY seq DESC LIMIT ?`).all(companionId, sequence, limit + 1);
    const hasMore = rows.length > limit;
    const messages = rows.slice(0, limit).map(project).reverse();
    return { messages, hasMore, nextBefore: hasMore ? `seq:${rows[Math.min(limit, rows.length) - 1].seq}` : null,
      nextAfter: `seq:${rows[0]?.seq ?? 0}` };
  }
  async appendMessage(companionId, input, options = {}) {
    this.#profile(companionId);
    fields(options,['notification']);
    if(options.notification!==undefined&&typeof options.notification!=='boolean')fail('COMPANION_INVALID_INPUT');
    const messageId = id(input?.id ?? input?.clientId ?? randomUUID());
    const value = messageData(input, messageId);
    // Server-assigned time is excluded so a retried client message can reuse its
    // identity after a restart without inventing a second visible message.
    const fingerprintInput = { ...value, createdAt: input.createdAt ?? null };
    const fingerprint = createHash('sha256').update(canonical(fingerprintInput)).digest('hex');
    if (this.#db.prepare('SELECT 1 FROM message_tombstones WHERE companion_id=? AND (message_id=? OR (client_id IS NOT NULL AND client_id=?))').get(companionId, messageId, value.clientId ?? null)) fail('COMPANION_MESSAGE_DELETED');
    const existing = this.#db.prepare('SELECT fingerprint,data FROM messages WHERE companion_id=? AND (message_id=? OR (client_id IS NOT NULL AND client_id=?))').get(companionId, messageId, value.clientId ?? null);
    if (existing) {
      if (existing.fingerprint !== fingerprint) fail('COMPANION_MESSAGE_CONFLICT');
      return JSON.parse(existing.data);
    }
    this.#validateMessageSources(companionId,value);
    if (value.replyTo) {
      const source = this.#message(companionId, value.replyTo);
      if (!source) fail('COMPANION_MESSAGE_NOT_FOUND');
      if (value.quoteReply === true && (source.contextExcluded || source.role === 'system' || source.type === 'task')) fail('COMPANION_SOURCE_EXCLUDED');
    }
    this.#transaction(() => {
      this.#db.prepare('INSERT INTO messages(companion_id,message_id,client_id,fingerprint,data) VALUES(?,?,?,?,?)').run(companionId, messageId, value.clientId ?? null, fingerprint, JSON.stringify(value));
      if (isChatMessage(value))
        this.#db.prepare('UPDATE conversation_state SET hidden=0 WHERE companion_id=?').run(companionId);
      // The message and its notification identity commit together, including
      // when a process stops before the publisher can observe the append.
      if(options.notification&&value.role==='assistant'&&value.type!=='task'&&value.status==='sent')
        this.#updatePendingNotificationIds(companionId,ids=>[...new Set([...ids,messageId])]);
    });
    return value;
  }
  async updateMessage(companionId, messageId, patch) {
    fields(patch, MUTABLE_MESSAGE_FIELDS);
    const current = this.#message(companionId, messageId);
    if (!current) fail('COMPANION_MESSAGE_NOT_FOUND');
    const next = messageData({ ...current, ...patch }, messageId);
    if(Object.hasOwn(patch,'contextSourceIds')) {
      if(current.contextExcluded)fail('COMPANION_SOURCE_EXCLUDED');
      this.#validateMessageSources(companionId,next);
    }
    this.#db.prepare('UPDATE messages SET data=? WHERE companion_id=? AND message_id=?').run(JSON.stringify(next), companionId, messageId);
    return next;
  }
  #validateMessageSources(companionId,message) {
    if(message.role!=='assistant'||!message.contextSourceIds?.length)return;
    const excluded=this.#db.prepare("SELECT 1 FROM messages WHERE companion_id=? AND message_id=? AND json_extract(data,'$.contextExcluded')=1");
    const row=this.#db.prepare('SELECT data FROM companion_state WHERE companion_id=?').get(companionId);
    const forgotten=new Set(row?JSON.parse(row.data).forgottenMessageIds??[]:[]);
    if(message.contextSourceIds.some(source=>forgotten.has(source)||excluded.get(companionId,source)))fail('COMPANION_SOURCE_EXCLUDED');
  }
  async excludeDerivedMessages(companionId, sourceIds) {
    this.#profile(companionId);
    if(!Array.isArray(sourceIds))fail('COMPANION_INVALID_INPUT');
    const sources=new Set(sourceIds.map(id));
    return this.#transaction(()=>{
      const excluded=new Set(sources),affected=new Set(),newSequences=[];let added=0;
      const rows=this.#db.prepare("SELECT seq,message_id,json_extract(data,'$.role') role,json_extract(data,'$.contextSourceIds') sources,json_extract(data,'$.contextRange') range,json_extract(data,'$.contextExcluded') excluded FROM messages WHERE companion_id=? ORDER BY seq").all(companionId);
      const mark=this.#db.prepare("UPDATE messages SET data=json_set(data,'$.contextExcluded',json('true')) WHERE companion_id=? AND message_id=?");
      for(const row of rows)if(row.excluded||sources.has(row.message_id)) {
        if(!row.excluded){mark.run(companionId,row.message_id);added++;newSequences.push(row.seq);}
        excluded.add(row.message_id);affected.add(row.message_id);row.excluded=true;
      }
      // Normal sources precede their replies. Repeat only if late task updates
      // introduced a dependency whose source has a newer sequence number.
      let changed;
      do {
        changed=false;
        for(const row of rows) {
          if(row.excluded||row.role!=='assistant')continue;
          const refs=row.sources?JSON.parse(row.sources):[],range=row.range?JSON.parse(row.range):null;
          const first=range?afterIndex(newSequences,sequence(range.after)):newSequences.length;
          // Rebuilt summaries can span old excluded rows. Only newly excluded
          // sources invalidate their ranges during this propagation pass.
          if(!refs.some(source=>excluded.has(source))&&!(range&&first<newSequences.length&&newSequences[first]<=sequence(range.through)))continue;
          mark.run(companionId,row.message_id);row.excluded=true;added++;
          newSequences.splice(afterIndex(newSequences,row.seq),0,row.seq);
          excluded.add(row.message_id);affected.add(row.message_id);changed=true;
        }
      }while(changed);
      if(added) {
        const current=this.#db.prepare('SELECT data FROM companion_state WHERE companion_id=?').get(companionId);
        const state=current?JSON.parse(current.data):{},revision=(state.memoryRevision??0)+1;
        if(!Number.isSafeInteger(revision)||revision<1)fail('COMPANION_INVALID_INPUT');
        const next={...state,memoryRevision:revision,summary:'',summaryState:null,summaryThrough:null,summaryCursor:state.forgottenThrough??'seq:0'};
        this.#db.prepare('INSERT INTO companion_state VALUES(?,?) ON CONFLICT(companion_id) DO UPDATE SET data=excluded.data').run(companionId,JSON.stringify(next));
      }
      return {excludedIds:rows.filter(row=>affected.has(row.message_id)).map(row=>row.message_id),newlyExcluded:added>0};
    });
  }
  async removeMessage(companionId, messageId) {
    const current = this.#message(companionId, messageId);
    if (!current) return { removed: false };
    this.#transaction(() => {
      this.#db.prepare('INSERT INTO message_tombstones VALUES(?,?,?)').run(companionId, messageId, current.clientId ?? null);
      this.#db.prepare('DELETE FROM messages WHERE companion_id=? AND message_id=?').run(companionId, messageId);
      this.#updatePendingNotificationIds(companionId,ids=>ids.filter(value=>value!==messageId));
      if(current.role==='assistant'&&current.type!=='task')this.#updatePendingNotificationRemovals(companionId,ids=>[...new Set([...ids,messageId])]);
    });
    this.#db.exec('PRAGMA wal_checkpoint(TRUNCATE)');
    return { removed: true, message: current };
  }
  async clearMessages(companionId) {
    this.#profile(companionId);
    const removed = this.#transaction(() => {
      this.#db.prepare('UPDATE read_positions SET seq=MAX(seq,COALESCE((SELECT MAX(seq) FROM messages WHERE companion_id=?),0)) WHERE companion_id=?').run(companionId,companionId);
      this.#updatePendingNotificationIds(companionId,()=>[]);
      this.#updatePendingNotificationRemovals(companionId,()=>[]);
      this.#db.prepare('INSERT OR IGNORE INTO message_tombstones SELECT companion_id,message_id,client_id FROM messages WHERE companion_id=?').run(companionId);
      return this.#db.prepare('DELETE FROM messages WHERE companion_id=?').run(companionId).changes;
    });
    this.#db.exec('PRAGMA wal_checkpoint(TRUNCATE)');
    return { removed };
  }
  async markRead(companionId, messageId) {
    this.#profile(companionId);
    const row = messageId === undefined ? this.#db.prepare('SELECT MAX(seq) seq FROM messages WHERE companion_id=?').get(companionId)
      : this.#db.prepare('SELECT seq FROM messages WHERE companion_id=? AND message_id=?').get(companionId, id(messageId));
    if (!row) fail('COMPANION_MESSAGE_NOT_FOUND');
    return this.#transaction(()=>{
      this.#db.prepare('UPDATE read_positions SET seq=MAX(seq,?) WHERE companion_id=?').run(row.seq ?? 0, companionId);
      const read=this.#notificationReadState(companionId);
      const unread=new Set(this.#db.prepare('SELECT message_id FROM messages WHERE companion_id=? AND seq>?').all(companionId,read.throughSequence).map(item=>item.message_id));
      this.#updatePendingNotificationIds(companionId,ids=>ids.filter(value=>unread.has(value)));
      return {read:true,...read};
    });
  }
  #notificationReadState(companionId) {
    const throughSequence=this.#db.prepare('SELECT seq FROM read_positions WHERE companion_id=?').get(companionId)?.seq??0;
    const unreadCount=this.#db.prepare(`SELECT COUNT(*) count FROM messages WHERE companion_id=? AND seq>? AND ${UNREAD_MESSAGE_SQL}`).get(companionId,throughSequence).count;
    return {throughSequence,unreadCount};
  }
  async notificationReadState(companionId) {
    this.#profile(companionId);
    return this.#notificationReadState(companionId);
  }
  #updatePendingNotificationIds(companionId,update) {
    return this.#updateNotificationIds(companionId,'pendingNotificationIds',ids=>update(ids).slice(-200));
  }
  #updatePendingNotificationRemovals(companionId,update) {
    return this.#updateNotificationIds(companionId,'pendingNotificationRemovals',ids=>{
      const next=update(ids);
      if(next.length>200)fail('COMPANION_NOTIFICATION_REMOVALS_PENDING');
      return next;
    });
  }
  #updateNotificationIds(companionId,key,update) {
    const row=this.#db.prepare('SELECT data FROM companion_state WHERE companion_id=?').get(companionId);
    // Notification history is bounded; the chat rows and authoritative unread
    // count remain intact if a bridge is unavailable for many replies.
    const state=row?JSON.parse(row.data):{},ids=update(state[key]??[]);
    if(!ids.length&&!Object.hasOwn(state,key))return;
    if(ids.length)state[key]=ids;else delete state[key];
    this.#db.prepare('INSERT INTO companion_state VALUES(?,?) ON CONFLICT(companion_id) DO UPDATE SET data=excluded.data')
      .run(companionId,JSON.stringify(stateMetadata(state)));
  }
  async pendingNotifications(companionId) {
    this.#profile(companionId);
    return this.#transaction(()=>{
      const row=this.#db.prepare('SELECT data FROM companion_state WHERE companion_id=?').get(companionId);
      const pending=row?JSON.parse(row.data).pendingNotificationIds??[]:[];
      if(!pending.length)return [];
      const {throughSequence}=this.#notificationReadState(companionId);
      const rows=this.#db.prepare('SELECT seq,data FROM messages WHERE companion_id=? AND seq>? AND message_id IN ('+pending.map(()=>'?').join(',')+') ORDER BY seq')
        .all(companionId,throughSequence,...pending).map(row=>({sequence:row.seq,message:JSON.parse(row.data)}))
        .filter(({message})=>message.role==='assistant'&&message.type!=='task'&&message.status==='sent'&&!message.contextExcluded);
      const retained=new Set(rows.map(({message})=>message.id));
      this.#updatePendingNotificationIds(companionId,ids=>ids.filter(value=>retained.has(value)));
      return rows;
    });
  }
  async acknowledgeNotifications(companionId,messageIds) {
    this.#profile(companionId);
    if(!Array.isArray(messageIds))fail('COMPANION_INVALID_INPUT');
    const completed=new Set(messageIds.map(id));
    this.#updatePendingNotificationIds(companionId,ids=>ids.filter(value=>!completed.has(value)));
  }
  async queueNotifications(companionId,messageIds) {
    this.#profile(companionId);
    if(!Array.isArray(messageIds))fail('COMPANION_INVALID_INPUT');
    const selected=messageIds.map(id).filter(messageId=>{
      const message=this.#message(companionId,messageId);
      return message?.role==='assistant'&&message.type!=='task'&&message.status==='sent'&&!message.contextExcluded;
    });
    if(selected.length)this.#updatePendingNotificationIds(companionId,ids=>[...new Set([...ids,...selected])]);
  }
  async pendingNotificationRemovals(companionId) {
    return (await this.getState(companionId)).pendingNotificationRemovals??[];
  }
  async acknowledgeNotificationRemovals(companionId,messageIds) {
    this.#profile(companionId);
    if(!Array.isArray(messageIds))fail('COMPANION_INVALID_INPUT');
    const completed=new Set(messageIds.map(id));
    this.#updatePendingNotificationRemovals(companionId,ids=>ids.filter(value=>!completed.has(value)));
  }
  async close() {
    if (this.#closed) return;
    this.#db.exec('PRAGMA wal_checkpoint(TRUNCATE)'); this.#db.close(); this.#closed = true;
  }
}
