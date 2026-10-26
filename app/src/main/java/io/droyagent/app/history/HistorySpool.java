package io.droyagent.app.history;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteFullException;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;

/** Single-writer delivery queue; the memory plugin remains the long-term store. */
final class HistorySpool implements AutoCloseable {
    private static final int VERSION = 2, APPLICATION_ID = 0x44524853;
    private static final long LOW_DISK_BYTES = 16L * 1024 * 1024;
    private final SQLiteDatabase db;
    private final File file;
    private final long logicalLimit;
    private final long reserve;

    HistorySpool(File directory, long budget) {
        if (budget < 65536 || budget > HistoryPolicy.SPOOL_BYTES) throw new IllegalArgumentException("HISTORY_INVALID_BUDGET");
        if (Files.isSymbolicLink(directory.toPath())) throw new IllegalStateException("HISTORY_SPOOL_IDENTITY");
        if (!directory.isDirectory() && !directory.mkdirs()) throw new IllegalStateException("HISTORY_SPOOL_DIRECTORY");
        file = new File(directory, "queue.db");
        for (String suffix : new String[]{"", "-journal", "-wal", "-shm"})
            if (Files.isSymbolicLink(new File(directory, "queue.db" + suffix).toPath())) throw new IllegalStateException("HISTORY_SPOOL_IDENTITY");
        boolean existed = file.exists();
        db = SQLiteDatabase.openDatabase(file.getAbsolutePath(), null, SQLiteDatabase.CREATE_IF_NECESSARY);
        try {
            long previousVersion=existed?number("PRAGMA user_version"):0;
            if (existed && ((previousVersion!=1&&previousVersion!=VERSION) || number("PRAGMA application_id") != APPLICATION_ID))
                throw new IllegalStateException("HISTORY_SPOOL_FORMAT");
            // These pragmas return rows even when assigning a value. Android's
            // execSQL update-only path rejects them before schema creation.
            try (Cursor mode = db.rawQuery("PRAGMA journal_mode=DELETE",null)) {
                if(!mode.moveToFirst()||!"delete".equalsIgnoreCase(mode.getString(0))) throw new IllegalStateException("HISTORY_SPOOL_JOURNAL");
            }
            db.execSQL("PRAGMA synchronous=FULL");
            if(number("PRAGMA secure_delete=ON")!=1||number("PRAGMA busy_timeout=3000")!=3000)
                throw new IllegalStateException("HISTORY_SPOOL_CONFIGURATION");
            // A rollback journal can approach the database size. Reserve half
            // of the total budget for that journal rather than counting payload alone.
            db.setMaximumSize(budget / 2);
            logicalLimit = Math.max(8192, budget / 2 - 32768); reserve = Math.min(1024 * 1024, logicalLimit / 8);
            if (!existed) {
                db.beginTransaction();
                try {
                    db.execSQL("CREATE TABLE events(seq INTEGER PRIMARY KEY AUTOINCREMENT,event_id TEXT UNIQUE NOT NULL,body TEXT NOT NULL,bytes INTEGER NOT NULL,expires_at INTEGER NOT NULL)");
                    db.execSQL("CREATE INDEX events_expiry ON events(expires_at)");
                    db.execSQL("CREATE TABLE state(singleton INTEGER PRIMARY KEY CHECK(singleton=1),dropped INTEGER NOT NULL,last_gap TEXT NOT NULL,gap_at INTEGER NOT NULL,last_acked TEXT NOT NULL,continuity_id TEXT NOT NULL)");
                    db.execSQL("INSERT INTO state VALUES(1,0,'',0,'',?)",new Object[]{UUID.randomUUID().toString()});
                    db.execSQL("CREATE TABLE delivery(singleton INTEGER PRIMARY KEY CHECK(singleton=1),batch_id TEXT NOT NULL,last_seq INTEGER NOT NULL)");
                    db.execSQL("PRAGMA application_id=" + APPLICATION_ID); db.execSQL("PRAGMA user_version=" + VERSION);
                    db.setTransactionSuccessful();
                } finally { db.endTransaction(); }
            } else if(previousVersion==1) {
                db.beginTransaction();
                try {
                    db.execSQL("ALTER TABLE state ADD COLUMN continuity_id TEXT NOT NULL DEFAULT ''");
                    db.execSQL("UPDATE state SET continuity_id=?",new Object[]{UUID.randomUUID().toString()});
                    db.execSQL("PRAGMA user_version="+VERSION);db.setTransactionSuccessful();
                } finally {db.endTransaction();}
            }
            try (Cursor cursor = db.rawQuery("PRAGMA quick_check", null)) {
                if (!cursor.moveToFirst() || !"ok".equals(cursor.getString(0))) throw new IllegalStateException("HISTORY_SPOOL_CORRUPT");
            }
        } catch (RuntimeException error) { db.close(); throw error; }
    }

    boolean append(JSONObject event, boolean coverage) throws Exception {
        String id = event.getString("eventId");
        if (!id.matches("[0-9a-f-]{36}")) throw new IllegalArgumentException("HISTORY_INVALID_EVENT");
        long expiresAt = event.getLong("expiresAtMs");
        db.beginTransaction();
        try {
            if (number("SELECT count(*) FROM events WHERE event_id=?", id) > 0) { db.setTransactionSuccessful(); return true; }
            String body=new JSONObject(event.toString()).put("continuityId",continuity()).toString();
            int bytes=body.getBytes(StandardCharsets.UTF_8).length;
            if(bytes>16384)throw new IllegalArgumentException("HISTORY_EVENT_TOO_LARGE");
            if (file.getParentFile().getUsableSpace() < LOW_DISK_BYTES
                || number("SELECT COALESCE(SUM(bytes),0) FROM events") + bytes > logicalLimit - (coverage ? 0 : reserve)) {
                gap("storage_limited"); db.setTransactionSuccessful(); return false;
            }
            ContentValues values = new ContentValues(); values.put("event_id", id); values.put("body", body);
            values.put("bytes", bytes); values.put("expires_at", expiresAt);
            db.insertOrThrow("events", null, values);
            db.setTransactionSuccessful(); return true;
        } catch (SQLiteFullException full) {
            // The transaction rolls back; capture exposes the failure and stops
            // accumulating callbacks until a successful drain frees capacity.
            throw new IllegalStateException("HISTORY_SPOOL_FULL", full);
        } finally { db.endTransaction(); }
    }

    void noteGap(String reason) {
        if (!reason.matches("[a-z_]{1,64}")) throw new IllegalArgumentException("HISTORY_INVALID_GAP");
        // A lifecycle gap does not establish how many events were missed.
        db.execSQL("UPDATE state SET last_gap=?,gap_at=?,continuity_id=? WHERE singleton=1", new Object[]{reason, System.currentTimeMillis(),UUID.randomUUID().toString()});
    }
    private void gap(String reason) { db.execSQL("UPDATE state SET dropped=dropped+1,last_gap=?,gap_at=?,continuity_id=? WHERE singleton=1", new Object[]{reason, System.currentTimeMillis(),UUID.randomUUID().toString()}); }
    private String continuity() {
        try(Cursor cursor=db.rawQuery("SELECT continuity_id FROM state WHERE singleton=1",null)){
            if(!cursor.moveToFirst()||!cursor.getString(0).matches("[0-9a-f-]{36}"))throw new IllegalStateException("HISTORY_SPOOL_CORRUPT");
            return cursor.getString(0);
        }
    }

    long expire(long now) {
        db.beginTransaction();
        try { long expired = expireRows(now); db.setTransactionSuccessful(); return expired; }
        finally { db.endTransaction(); }
    }
    private long expireRows(long now) {
        long expired = number("SELECT count(*) FROM events WHERE expires_at<=?", Long.toString(now));
        if (expired > 0) {
            db.delete("delivery", null, null);
            db.delete("events", "expires_at<=?", new String[]{Long.toString(now)});
            String gapId=UUID.randomUUID().toString();
            db.execSQL("UPDATE state SET dropped=dropped+?,last_gap='retention_expired',gap_at=?,continuity_id=? WHERE singleton=1", new Object[]{expired, now,gapId});
            // Retention may remove a middle observation, including one that
            // predates an unacknowledged batch. Preserve remaining observations
            // but do not infer continuity across any of that incomplete backlog.
            try(Cursor rows=db.rawQuery("SELECT seq,event_id,body FROM events ORDER BY seq",null)){
                while(rows.moveToNext()){
                    try {
                        String token=UUID.nameUUIDFromBytes((gapId+":"+rows.getString(1)).getBytes(StandardCharsets.UTF_8)).toString();
                        String body=new JSONObject(rows.getString(2)).put("continuityId",token).toString();
                        ContentValues changed=new ContentValues();changed.put("body",body);changed.put("bytes",body.getBytes(StandardCharsets.UTF_8).length);
                        db.update("events",changed,"seq=?",new String[]{Long.toString(rows.getLong(0))});
                    }catch(org.json.JSONException invalid){throw new IllegalStateException("HISTORY_SPOOL_CORRUPT",invalid);}
                }
            }
        }
        return expired;
    }

    JSONObject readBatch(int limit, int maxBytes, long now) throws Exception {
        if (limit < 1 || limit > 200 || maxBytes < 16384 || maxBytes > 262144) throw new IllegalArgumentException("HISTORY_INVALID_BATCH_LIMIT");
        db.beginTransaction();
        try {
            expireRows(now);
            String batchId = null; long last = -1;
            try (Cursor cursor = db.rawQuery("SELECT batch_id,last_seq FROM delivery WHERE singleton=1", null)) {
                if (cursor.moveToFirst()) { batchId = cursor.getString(0); last = cursor.getLong(1); }
            }
            JSONArray items = new JSONArray(); int bytes = 0; long upto = 0;
            String sql = "SELECT seq,body,bytes FROM events" + (batchId == null ? "" : " WHERE seq<=?") + " ORDER BY seq LIMIT 201";
            try (Cursor cursor = db.rawQuery(sql, batchId == null ? null : new String[]{Long.toString(last)})) {
                while (cursor.moveToNext()) {
                    if (items.length() >= limit || bytes + cursor.getInt(2) > maxBytes) {
                        if (batchId != null) throw new IllegalArgumentException("HISTORY_PENDING_BATCH_LIMIT");
                        break;
                    }
                    upto = cursor.getLong(0); bytes += cursor.getInt(2); items.put(new JSONObject(cursor.getString(1)));
                }
            }
            if (batchId == null && items.length() > 0) {
                batchId = UUID.randomUUID().toString();
                db.execSQL("INSERT INTO delivery VALUES(1,?,?)", new Object[]{batchId, upto});
            }
            JSONObject result = new JSONObject().put("batchId", batchId == null ? JSONObject.NULL : batchId)
                .put("items", items).put("bytes", bytes).put("hasMore", number("SELECT count(*) FROM events") > items.length());
            if(items.length()==0)result.put("continuityId",continuity());
            db.setTransactionSuccessful(); return result;
        } finally { db.endTransaction(); }
    }

    JSONObject acknowledge(String batchId) throws Exception {
        if (batchId == null || !batchId.matches("[0-9a-f-]{36}")) throw new IllegalArgumentException("HISTORY_INVALID_BATCH");
        db.beginTransaction();
        try {
            try (Cursor cursor = db.rawQuery("SELECT last_acked FROM state WHERE singleton=1", null)) {
                if (cursor.moveToFirst() && batchId.equals(cursor.getString(0))) {
                    db.setTransactionSuccessful(); return new JSONObject().put("acknowledged", true).put("duplicate", true);
                }
            }
            long last;
            try (Cursor cursor = db.rawQuery("SELECT last_seq FROM delivery WHERE singleton=1 AND batch_id=?", new String[]{batchId})) {
                if (!cursor.moveToFirst()) throw new IllegalArgumentException("HISTORY_UNKNOWN_BATCH");
                last = cursor.getLong(0);
            }
            int removed = db.delete("events", "seq<=?", new String[]{Long.toString(last)});
            db.delete("delivery", null, null);
            db.execSQL("UPDATE state SET last_acked=? WHERE singleton=1", new Object[]{batchId});
            db.setTransactionSuccessful();
            return new JSONObject().put("acknowledged", true).put("duplicate", false).put("removed", removed);
        } finally { db.endTransaction(); }
    }

    JSONObject status() throws Exception {
        JSONObject result = new JSONObject().put("queued", number("SELECT count(*) FROM events"))
            .put("payloadBytes", number("SELECT COALESCE(SUM(bytes),0) FROM events"))
            .put("pendingBatch", number("SELECT count(*) FROM delivery") > 0).put("schemaVersion", VERSION);
        try (Cursor cursor = db.rawQuery("SELECT dropped,last_gap,gap_at FROM state WHERE singleton=1", null)) {
            if (!cursor.moveToFirst()) throw new IllegalStateException("HISTORY_SPOOL_CORRUPT");
            result.put("dropped", cursor.getLong(0)).put("lastGap", cursor.getString(1)).put("gapAtMs", cursor.getLong(2));
        }
        return result;
    }
    private long number(String sql, String... args) {
        try (Cursor cursor = db.rawQuery(sql, args.length == 0 ? null : args)) {
            if (!cursor.moveToFirst()) throw new IllegalStateException("HISTORY_SPOOL_QUERY");
            return cursor.getLong(0);
        }
    }
    @Override public void close() { db.close(); }
}
