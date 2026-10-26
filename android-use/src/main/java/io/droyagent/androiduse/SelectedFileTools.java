package io.droyagent.androiduse;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.os.CancellationSignal;
import android.provider.DocumentsContract;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.Reader;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/** Access is limited to persistent user selections; model arguments never contain paths or URIs. */
public final class SelectedFileTools {
    private static final int MAX_LIST = 200;
    private static final String[] COLUMNS = {
            DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED, DocumentsContract.Document.COLUMN_FLAGS
    };
    private final Context context;
    private final FileGrantStore store;

    public SelectedFileTools(Context context) {
        this.context = context.getApplicationContext();
        store = new FileGrantStore(this.context);
    }

    public synchronized JSONObject execute(String action, JSONObject args, Cancellation cancellation) throws Exception {
        cancellation.check();
        try {
            return CancellableFileIo.run(cancellation, CancellableFileIo.TIMEOUT_MS, guard -> {
                Operation operation = new Operation(guard);
                switch (action) {
                    case "file_grants": return new JSONObject().put("grants", store.listGrants())
                            .put("source", "user_selected_android_documents");
                    case "file_list": return list(args, operation);
                    case "file_read": return read(args, operation);
                    case "file_export": return export(args, operation);
                    default: throw new ActionException("unsupported_action", "Unsupported selected-file action");
                }
            });
        } catch (InterruptedException stopped) {
            if (!"file_export".equals(action)) throw stopped;
            InterruptedException exportStopped = new InterruptedException("Export stopped; a new or partial file may exist in the selected directory. Check the output before requesting another export");
            exportStopped.initCause(stopped);
            throw exportStopped;
        } catch (ActionException failure) {
            if ("file_export".equals(action) && failure.getCode().equals("file_operation_timeout"))
                throw new ActionException("file_export_unconfirmed", "Export exceeded the file provider time limit; a new or partial file may exist. Check the selected directory before requesting another export", failure);
            throw failure;
        } catch (SecurityException denied) {
            throw new ActionException("file_permission_missing", "Android denied access; select the file or directory again", denied);
        } catch (java.io.FileNotFoundException missing) {
            throw new ActionException("file_unavailable", "Selected file is missing or its provider is unavailable", missing);
        }
    }

    private JSONObject list(JSONObject args, Operation cancellation) throws Exception {
        String id = id(args, args.has("fileId") ? "fileId" : "grantId");
        FileGrantStore.FileReference file = store.resolveFile(id, true, false);
        cancellation.bindGrant(store, file.grant, true, false);
        JSONObject self = metadata(file.uri, file.id, file.grant, cancellation);
        if (!self.getBoolean("directory")) return new JSONObject().put("items", new JSONArray().put(self))
                .put("truncated", false).put("source", source(file.grant));
        if (!file.grant.tree) throw new ActionException("directory_selection_required", "Select a directory using Android's directory picker");
        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(file.grant.uri,
                DocumentsContract.getDocumentId(file.uri));
        JSONArray items = new JSONArray();
        List<String> documentIds = new ArrayList<>();
        boolean truncated = false;
        try (Cursor cursor = cancellation.guard.track(context.getContentResolver().query(children, COLUMNS, null, null, null, cancellation.signal))) {
            if (cursor == null) throw new ActionException("file_provider_unavailable", "File provider did not return directory contents");
            while (cursor.moveToNext()) {
                cancellation.check();
                if (items.length() == MAX_LIST) { truncated = true; break; }
                String documentId = value(cursor, DocumentsContract.Document.COLUMN_DOCUMENT_ID);
                if (documentId.isEmpty()) continue;
                documentIds.add(documentId);
                items.put(metadata(cursor, "", file.grant));
            }
        }
        cancellation.check();
        List<String> fileIds = store.rememberAll(file.grant, documentIds);
        for (int i = 0; i < fileIds.size(); i++) items.getJSONObject(i).put("fileId", fileIds.get(i));
        return new JSONObject().put("items", items).put("truncated", truncated).put("limit", MAX_LIST)
                .put("source", source(file.grant)).put("directoryFileId", file.id);
    }

    private JSONObject read(JSONObject args, Operation cancellation) throws Exception {
        FileGrantStore.FileReference file = store.resolveFile(id(args, "fileId"), true, false);
        cancellation.bindGrant(store, file.grant, true, false);
        int maxChars = args.has("maxChars") ? ActionValidation.integer(args.opt("maxChars"), "maxChars") : 20000;
        if (maxChars < 1 || maxChars > 40000)
            throw new ActionException("invalid_argument", "maxChars must be between 1 and 40000");
        JSONObject metadata = metadata(file.uri, file.id, file.grant, cancellation);
        if (metadata.getBoolean("directory")) throw new ActionException("not_a_text_file", "List the directory and choose a text file");
        if (!textMime(metadata.getString("mimeType")))
            throw new ActionException("unsupported_file_type", "Only UTF-8 text files can be read; this document is " + metadata.getString("mimeType"));
        TextRead text;
        cancellation.check();
        try (ParcelFileDescriptor descriptor = cancellation.guard.track(context.getContentResolver().openFileDescriptor(file.uri, "r", cancellation.signal))) {
            if (descriptor == null) throw new ActionException("file_provider_unavailable", "File provider did not open the selected file");
            try (InputStream stream = cancellation.guard.track(new ParcelFileDescriptor.AutoCloseInputStream(descriptor))) {
                text = readUtf8(stream, maxChars, cancellation);
            }
        }
        cancellation.check();
        return metadata.put("text", text.text).put("truncated", text.truncated)
                .put("maxChars", maxChars).put("encoding", "UTF-8");
    }

    private JSONObject export(JSONObject args, Operation cancellation) throws Exception {
        FileGrantStore.Grant grant = store.resolveGrant(id(args, "grantId"), true, true);
        cancellation.bindGrant(store, grant, true, true);
        if (!grant.tree) throw new ActionException("directory_selection_required", "Choose a writable output directory in Droy first");
        String name = exportName(args.opt("name"));
        Object body = args.opt("text");
        if (!(body instanceof String) || ((String) body).length() > 20000 || ((String) body).indexOf('\0') >= 0)
            throw new ActionException("invalid_argument", "text must contain at most 20000 characters without NUL");
        String text = (String) body;
        Uri parent = FileGrantStore.documentUri(grant.uri);
        requireNewName(grant, name, cancellation);
        cancellation.check();
        String fileId = "not_returned";
        try {
            Uri created = DocumentsContract.createDocument(context.getContentResolver(), parent, "text/plain", name);
            if (created == null) throw new ActionException("file_create_failed", "File provider did not confirm creating the export");
            fileId = store.remember(grant, DocumentsContract.getDocumentId(created));
            cancellation.check();
            byte[] encoded = text.getBytes(StandardCharsets.UTF_8);
            try (ParcelFileDescriptor descriptor = cancellation.guard.track(context.getContentResolver().openFileDescriptor(created, "w", cancellation.signal))) {
                if (descriptor == null) throw new ActionException("file_provider_unavailable", "File provider did not open the export");
                try (OutputStream output = cancellation.guard.track(new ParcelFileDescriptor.AutoCloseOutputStream(descriptor))) {
                    for (int offset = 0; offset < encoded.length; offset += 4096) {
                        cancellation.check();
                        output.write(encoded, offset, Math.min(4096, encoded.length - offset));
                    }
                    output.flush();
                }
            }
            cancellation.check();
            // Check the saved text, rather than treating close() as proof of persisted content.
            TextRead saved;
            try (ParcelFileDescriptor descriptor = cancellation.guard.track(context.getContentResolver().openFileDescriptor(created, "r", cancellation.signal))) {
                if (descriptor == null) throw new ActionException("file_export_unconfirmed", "Export was written but cannot be checked");
                try (InputStream input = cancellation.guard.track(new ParcelFileDescriptor.AutoCloseInputStream(descriptor))) {
                    saved = readUtf8(input, 20000, cancellation);
                }
            }
            boolean verified = !saved.truncated && saved.text.equals(text);
            return metadata(created, fileId, grant, cancellation).put("status", verified ? "export_verified" : "export_unconfirmed")
                    .put("verified", verified).put("createdNewDocument", true).put("characters", text.length());
        } catch (InterruptedException stopped) {
            throw new InterruptedException("Export stopped; a new or partial file may exist in the selected directory. Check before exporting again. fileId=" + fileId);
        } catch (Exception failed) {
            throw new ActionException("file_export_unconfirmed", "Export was not confirmed; a new or partial file may exist. Check before exporting again. fileId=" + fileId, failed);
        }
    }

    private void requireNewName(FileGrantStore.Grant grant, String name, Operation cancellation) throws Exception {
        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(grant.uri, DocumentsContract.getTreeDocumentId(grant.uri));
        try (Cursor cursor = cancellation.guard.track(context.getContentResolver().query(children,
                new String[]{DocumentsContract.Document.COLUMN_DISPLAY_NAME}, null, null, null, cancellation.signal))) {
            if (cursor == null) throw new ActionException("file_provider_unavailable", "Could not check output directory");
            while (cursor.moveToNext()) {
                cancellation.check();
                if (name.equalsIgnoreCase(value(cursor, DocumentsContract.Document.COLUMN_DISPLAY_NAME)))
                    throw new ActionException("file_name_conflict", "A file with this name already exists; choose a different export name");
            }
        }
    }

    private JSONObject metadata(Uri uri, String fileId, FileGrantStore.Grant grant, Operation cancellation) throws Exception {
        cancellation.check();
        try (Cursor cursor = cancellation.guard.track(context.getContentResolver().query(uri, COLUMNS, null, null, null, cancellation.signal))) {
            if (cursor == null || !cursor.moveToFirst()) throw new ActionException("file_unavailable", "Selected document no longer exists");
            return metadata(cursor, fileId, grant);
        }
    }

    private JSONObject metadata(Cursor cursor, String fileId, FileGrantStore.Grant grant) throws Exception {
        String mime = value(cursor, DocumentsContract.Document.COLUMN_MIME_TYPE);
        return new JSONObject().put("fileId", fileId).put("grantId", grant.id)
                .put("name", value(cursor, DocumentsContract.Document.COLUMN_DISPLAY_NAME))
                .put("mimeType", mime).put("directory", DocumentsContract.Document.MIME_TYPE_DIR.equals(mime))
                .put("sizeBytes", nullableLong(cursor, DocumentsContract.Document.COLUMN_SIZE))
                .put("lastModified", nullableLong(cursor, DocumentsContract.Document.COLUMN_LAST_MODIFIED))
                .put("source", source(grant));
    }

    private static JSONObject source(FileGrantStore.Grant grant) throws Exception {
        return new JSONObject().put("type", "user_selected_android_document").put("grantId", grant.id)
                .put("selectionName", grant.name);
    }

    private static String id(JSONObject args, String key) throws Exception {
        Object value = args.opt(key);
        if (!(value instanceof String)) throw new ActionException("invalid_argument", key + " is required");
        FileGrantStore.validateId((String) value, key);
        return (String) value;
    }

    static String exportName(Object value) throws ActionException {
        if (!(value instanceof String)) throw new ActionException("invalid_argument", "name must be a filename");
        String name = (String) value;
        if (name.isBlank() || name.length() > 120 || !name.equals(name.strip()) || name.equals(".") || name.equals(".."))
            throw new ActionException("invalid_argument", "name must be a nonempty filename of at most 120 characters");
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c == '/' || c == '\\' || Character.isISOControl(c))
                throw new ActionException("invalid_argument", "name must not contain a path or control characters");
        }
        return name;
    }

    static boolean textMime(String mime) {
        return mime != null && (mime.startsWith("text/") || mime.equals("application/json")
                || mime.endsWith("+json") || mime.equals("application/xml") || mime.endsWith("+xml")
                || mime.equals("application/javascript"));
    }

    static TextRead readUtf8(InputStream input, int maxChars, Cancellation cancellation) throws Exception {
        Reader reader = new InputStreamReader(input, StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT));
        StringBuilder text = new StringBuilder(Math.min(maxChars, 4096));
        char[] buffer = new char[1024];
        while (text.length() <= maxChars) {
            cancellation.check();
            int count;
            try { count = reader.read(buffer, 0, Math.min(buffer.length, maxChars + 1 - text.length())); }
            catch (java.nio.charset.CharacterCodingException invalid) {
                throw new ActionException("unsupported_text_encoding", "File is not valid UTF-8 text", invalid);
            }
            if (count == -1) break;
            text.append(buffer, 0, count);
        }
        if (text.indexOf("\0") >= 0) throw new ActionException("not_a_text_file", "File contains binary NUL data");
        boolean truncated = text.length() > maxChars;
        if (truncated) text.setLength(maxChars);
        // Avoid returning half of a Unicode character at the truncation boundary.
        if (truncated && text.length() > 0 && Character.isHighSurrogate(text.charAt(text.length() - 1)))
            text.setLength(text.length() - 1);
        cancellation.check();
        return new TextRead(text.toString(), truncated);
    }

    private static String value(Cursor cursor, String name) {
        int index = cursor.getColumnIndex(name);
        return index < 0 || cursor.isNull(index) ? "" : cursor.getString(index);
    }

    private static Object nullableLong(Cursor cursor, String name) {
        int index = cursor.getColumnIndex(name);
        return index < 0 || cursor.isNull(index) ? JSONObject.NULL : cursor.getLong(index);
    }

    static final class TextRead {
        final String text;
        final boolean truncated;
        TextRead(String text, boolean truncated) { this.text = text; this.truncated = truncated; }
    }

    private static final class Operation implements Cancellation {
        final CancellableFileIo.Guard guard;
        final CancellationSignal signal = new CancellationSignal();
        Operation(CancellableFileIo.Guard guard) throws InterruptedException {
            this.guard = guard;
            guard.onCancel(signal::cancel);
        }
        void bindGrant(FileGrantStore store, FileGrantStore.Grant grant, boolean read, boolean write) throws InterruptedException {
            guard.onCheck(() -> {
                try { store.checkGrant(grant, read, write); }
                catch (Exception revoked) {
                    InterruptedException stopped = new InterruptedException("Selected file authorization is no longer valid; no further file access or result delivery is allowed");
                    stopped.initCause(revoked);
                    throw stopped;
                }
            });
            guard.check();
        }
        @Override public void check() throws InterruptedException { guard.check(); }
    }
}
