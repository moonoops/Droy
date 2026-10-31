import { DatabaseSync } from 'node:sqlite';
import { existsSync } from 'node:fs';
import { mkdir } from 'node:fs/promises';
import { join } from 'node:path';
import { defaultProfiles } from '../../companions/profiles.mjs';
import { CompanionStore } from '../../companions/store.mjs';

/** A real version-1 database, used only by tests of the shipped two identities. */
export async function seedLegacyCompanionDb(directory, { profiles = defaultProfiles() } = {}) {
  await mkdir(directory, { recursive: true });
  const db = new DatabaseSync(join(directory, 'companions.sqlite'));
  try {
    if (db.prepare('PRAGMA user_version').get().user_version || db.prepare("SELECT name FROM sqlite_master WHERE type='table'").get())
      throw new Error('Legacy fixtures require an empty database');
    db.exec(`PRAGMA foreign_keys=ON; BEGIN IMMEDIATE;
      CREATE TABLE profiles(id TEXT PRIMARY KEY,data TEXT NOT NULL);
      CREATE TABLE messages(seq INTEGER PRIMARY KEY AUTOINCREMENT,companion_id TEXT NOT NULL REFERENCES profiles(id),message_id TEXT NOT NULL,client_id TEXT,fingerprint TEXT NOT NULL,data TEXT NOT NULL,UNIQUE(companion_id,message_id),UNIQUE(companion_id,client_id));
      CREATE INDEX messages_companion_sequence ON messages(companion_id,seq DESC);
      CREATE TABLE sessions(session_id TEXT PRIMARY KEY,companion_id TEXT NOT NULL REFERENCES profiles(id),kind TEXT NOT NULL,workspace_id TEXT NOT NULL,task_id TEXT);
      CREATE INDEX sessions_owner ON sessions(companion_id,kind);
      CREATE TABLE settings(singleton INTEGER PRIMARY KEY,data TEXT NOT NULL);
      CREATE TABLE read_positions(companion_id TEXT PRIMARY KEY REFERENCES profiles(id),seq INTEGER NOT NULL DEFAULT 0);
      CREATE TABLE companion_state(companion_id TEXT PRIMARY KEY REFERENCES profiles(id),data TEXT NOT NULL);
      CREATE TABLE conversation_state(companion_id TEXT PRIMARY KEY REFERENCES profiles(id),pinned INTEGER NOT NULL DEFAULT 0,hidden INTEGER NOT NULL DEFAULT 0);
      CREATE TABLE message_tombstones(companion_id TEXT NOT NULL REFERENCES profiles(id),message_id TEXT NOT NULL,client_id TEXT,PRIMARY KEY(companion_id,message_id));
      CREATE TABLE companion_uploads(seq INTEGER PRIMARY KEY AUTOINCREMENT,companion_id TEXT NOT NULL REFERENCES profiles(id),upload_id TEXT NOT NULL,data TEXT NOT NULL,UNIQUE(companion_id,upload_id));
      CREATE INDEX uploads_owner_sequence ON companion_uploads(companion_id,seq DESC);
      CREATE TABLE user_profile(singleton INTEGER PRIMARY KEY,data TEXT NOT NULL);
      CREATE TABLE user_uploads(upload_id TEXT PRIMARY KEY,data TEXT NOT NULL);`);
    for (const value of profiles) {
      const profile = structuredClone(value); delete profile.templateId;
      db.prepare('INSERT INTO profiles VALUES(?,?)').run(profile.id, JSON.stringify(profile));
      db.prepare('INSERT INTO read_positions VALUES(?,0)').run(profile.id);
    }
    db.prepare('INSERT INTO settings VALUES(1,?)').run(JSON.stringify({ defaultMode: 'messages' }));
    db.prepare('INSERT INTO user_profile VALUES(1,?)').run(JSON.stringify({ name: '', avatarUrl: '/droy/companions/assets/user-default.png' }));
    db.exec('PRAGMA application_id=1146241872; PRAGMA user_version=1; COMMIT;');
  } catch (error) { if (db.isTransaction) db.exec('ROLLBACK'); throw error; }
  finally { db.close(); }
}

export async function openLegacyCompanionStore({ directory }) {
  if (!existsSync(join(directory, 'companions.sqlite'))) await seedLegacyCompanionDb(directory);
  return CompanionStore.open({ directory });
}

/** Reconstruct a schema-1 snapshot before a legacy-upgrade test edits it. */
export function markLegacyFixture(db) {
  for (const table of ['creation_receipts', 'creation_drafts']) {
    if (db.prepare(`SELECT COUNT(*) count FROM ${table}`).get().count) throw new Error('Cannot rewind a fixture containing new creation data');
  }
  db.exec(`DROP TABLE creation_receipts; DROP TABLE draft_uploads; DROP TABLE creation_drafts; DROP TABLE name_claims;
    UPDATE profiles SET data=json_remove(data,'$.templateId'); PRAGMA user_version=1;`);
}
