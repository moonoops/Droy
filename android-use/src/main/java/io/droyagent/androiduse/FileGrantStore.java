package io.droyagent.androiduse;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.UriPermission;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;
import java.util.List;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;

/** Opaque handles for the documents explicitly selected through Android's system picker. */
public final class FileGrantStore {
    private static final Object LOCK = new Object();
    private static final int MAX_GRANTS = 32;
    private static final int MAX_REFERENCES = 512;
    private final Context context;
    private final SharedPreferences preferences;

    public FileGrantStore(Context context) {
        this.context = context.getApplicationContext();
        preferences = this.context.getSharedPreferences("droy_selected_files", Context.MODE_PRIVATE);
    }

    /** The App must takePersistableUriPermission before saving a selection. */
    public JSONObject saveGrant(Uri uri, String label) throws Exception {
        synchronized (LOCK) {
            if (uri == null || !"content".equals(uri.getScheme())
                    || (!DocumentsContract.isTreeUri(uri) && !DocumentsContract.isDocumentUri(context, uri)))
                throw new ActionException("invalid_file_selection", "Select a document or directory using Android's file picker");
            Permission permission = permission(uri);
            if (!permission.read && !permission.write)
                throw new ActionException("file_permission_missing", "Android did not retain access to the selected item");
            JSONArray grants = grants();
            JSONObject selected = null;
            for (int i = 0; i < grants.length(); i++) {
                JSONObject item = grants.getJSONObject(i);
                if (item.getString("uri").equals(uri.toString())) selected = item;
            }
            if (selected == null) {
                if (grants.length() >= MAX_GRANTS)
                    throw new ActionException("file_grant_limit", "Remove an old file authorization before selecting another");
                selected = new JSONObject().put("grantId", UUID.randomUUID().toString())
                        .put("uri", uri.toString()).put("createdAt", System.currentTimeMillis());
                grants.put(selected);
            }
            String name = label == null ? "" : label.strip();
            if (name.isEmpty()) name = displayName(documentUri(uri));
            selected.put("name", name.substring(0, Math.min(name.length(), 200)))
                    .put("tree", DocumentsContract.isTreeUri(uri));
            save("grants", grants);
            return publicGrant(selected, permission);
        }
    }

    public JSONArray listGrants() throws Exception {
        synchronized (LOCK) {
            JSONArray result = new JSONArray();
            JSONArray grants = grants();
            for (int i = 0; i < grants.length(); i++) {
                JSONObject item = grants.getJSONObject(i);
                result.put(publicGrant(item, permission(Uri.parse(item.getString("uri")))));
            }
            return result;
        }
    }

    public void revokeGrant(String grantId) throws Exception {
        synchronized (LOCK) {
            Grant grant = resolveGrant(grantId, false, false);
            Permission permission = permission(grant.uri);
            int flags = (permission.read ? Intent.FLAG_GRANT_READ_URI_PERMISSION : 0)
                    | (permission.write ? Intent.FLAG_GRANT_WRITE_URI_PERMISSION : 0);
            if (flags != 0) {
                try { context.getContentResolver().releasePersistableUriPermission(grant.uri, flags); }
                catch (SecurityException alreadyRevoked) {
                    Permission current = permission(grant.uri);
                    if (current.read || current.write) throw alreadyRevoked;
                }
            }
            JSONArray next = new JSONArray();
            JSONArray grants = grants();
            for (int i = 0; i < grants.length(); i++) {
                JSONObject item = grants.getJSONObject(i);
                if (!item.getString("grantId").equals(grantId)) next.put(item);
            }
            save("grants", next);
            JSONArray retained = new JSONArray();
            JSONArray refs = references();
            for (int i = 0; i < refs.length(); i++) {
                JSONObject item = refs.getJSONObject(i);
                if (!item.getString("grantId").equals(grantId)) retained.put(item);
            }
            save("references", retained);
        }
    }

    Grant resolveGrant(String id, boolean read, boolean write) throws Exception {
        synchronized (LOCK) {
            validateId(id, "grantId");
            JSONArray grants = grants();
            for (int i = 0; i < grants.length(); i++) {
                JSONObject item = grants.getJSONObject(i);
                if (!item.getString("grantId").equals(id)) continue;
                Uri uri = Uri.parse(item.getString("uri"));
                Permission permission = permission(uri);
                if ((read && !permission.read) || (write && !permission.write))
                    throw new ActionException("file_permission_missing", "The selected item's Android permission has been revoked");
                return new Grant(id, uri, item.getString("name"), item.getBoolean("tree"), permission.read, permission.write);
            }
            throw new ActionException("file_grant_missing", "File selection is no longer available; select it again");
        }
    }

    /** Re-check both our selection and Android's live persisted permission, including system revocation. */
    void checkGrant(Grant expected, boolean read, boolean write) throws Exception {
        Grant current = resolveGrant(expected.id, read, write);
        if (!current.uri.equals(expected.uri) || current.tree != expected.tree)
            throw new ActionException("file_permission_missing", "The selected file authorization changed; select it again");
    }

    FileReference resolveFile(String id, boolean read, boolean write) throws Exception {
        synchronized (LOCK) {
            validateId(id, "fileId");
            JSONArray grants = grants();
            for (int i = 0; i < grants.length(); i++) {
                JSONObject item = grants.getJSONObject(i);
                if (item.getString("grantId").equals(id)) {
                    Grant grant = resolveGrant(id, read, write);
                    return new FileReference(id, grant, documentUri(grant.uri));
                }
            }
            JSONArray refs = references();
            for (int i = 0; i < refs.length(); i++) {
                JSONObject item = refs.getJSONObject(i);
                if (!item.getString("fileId").equals(id)) continue;
                Grant grant = resolveGrant(item.getString("grantId"), read, write);
                return new FileReference(id, grant, DocumentsContract.buildDocumentUriUsingTree(grant.uri,
                        item.getString("documentId")));
            }
            throw new ActionException("file_reference_missing", "File reference expired; list its selected directory again");
        }
    }

    String remember(Grant grant, String documentId) throws Exception {
        return rememberAll(grant, java.util.Collections.singletonList(documentId)).get(0);
    }

    List<String> rememberAll(Grant grant, List<String> documentIds) throws Exception {
        synchronized (LOCK) {
            JSONArray refs = references();
            Map<String, String> existing = new HashMap<>();
            for (int i = 0; i < refs.length(); i++) {
                JSONObject item = refs.getJSONObject(i);
                if (item.getString("grantId").equals(grant.id))
                    existing.put(item.getString("documentId"), item.getString("fileId"));
            }
            List<String> result = new ArrayList<>();
            java.util.Set<String> retained = new java.util.HashSet<>();
            JSONArray next = new JSONArray();
            for (String documentId : documentIds) {
                String id = existing.get(documentId);
                if (id == null) id = UUID.randomUUID().toString();
                result.add(id);
                if (retained.add(id)) next.put(new JSONObject().put("fileId", id)
                        .put("grantId", grant.id).put("documentId", documentId));
            }
            for (int i = 0; i < refs.length() && next.length() < MAX_REFERENCES; i++) {
                JSONObject item = refs.getJSONObject(i);
                if (retained.add(item.getString("fileId"))) next.put(item);
            }
            save("references", next);
            return result;
        }
    }

    private JSONObject publicGrant(JSONObject item, Permission permission) throws Exception {
        return new JSONObject().put("grantId", item.getString("grantId"))
                .put("fileId", item.getString("grantId")).put("name", item.getString("name"))
                .put("kind", item.getBoolean("tree") ? "directory" : "document")
                .put("readable", permission.read).put("writable", permission.write)
                .put("available", permission.read || permission.write).put("source", "user_selected_android_document");
    }

    private Permission permission(Uri uri) {
        List<UriPermission> permissions = context.getContentResolver().getPersistedUriPermissions();
        for (UriPermission permission : permissions) {
            if (uri.equals(permission.getUri())) return new Permission(permission.isReadPermission(), permission.isWritePermission());
        }
        return new Permission(false, false);
    }

    static Uri documentUri(Uri uri) {
        return DocumentsContract.isTreeUri(uri)
                ? DocumentsContract.buildDocumentUriUsingTree(uri, DocumentsContract.getTreeDocumentId(uri)) : uri;
    }

    private String displayName(Uri uri) {
        try (Cursor cursor = context.getContentResolver().query(uri,
                new String[]{DocumentsContract.Document.COLUMN_DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst() && !cursor.isNull(0)) return cursor.getString(0);
        }
        return "Selected document";
    }

    static void validateId(String id, String name) throws ActionException {
        if (id == null || !id.matches("[0-9a-fA-F-]{36}"))
            throw new ActionException("invalid_argument", name + " must be an opaque ID returned by a file tool");
    }

    private JSONArray grants() throws Exception { return new JSONArray(preferences.getString("grants", "[]")); }
    private JSONArray references() throws Exception { return new JSONArray(preferences.getString("references", "[]")); }
    private void save(String key, JSONArray value) throws ActionException {
        if (!preferences.edit().putString(key, value.toString()).commit())
            throw new ActionException("file_record_failed", "Could not save the selected file record");
    }

    static final class Grant {
        final String id, name;
        final Uri uri;
        final boolean tree, read, write;
        Grant(String id, Uri uri, String name, boolean tree, boolean read, boolean write) {
            this.id = id; this.uri = uri; this.name = name; this.tree = tree; this.read = read; this.write = write;
        }
    }
    static final class FileReference {
        final String id;
        final Grant grant;
        final Uri uri;
        FileReference(String id, Grant grant, Uri uri) { this.id = id; this.grant = grant; this.uri = uri; }
    }
    private static final class Permission {
        final boolean read, write;
        Permission(boolean read, boolean write) { this.read = read; this.write = write; }
    }
}
