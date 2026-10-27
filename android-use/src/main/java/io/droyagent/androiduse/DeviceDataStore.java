package io.droyagent.androiduse;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import org.json.JSONArray;
import org.json.JSONObject;

/** Immutable binary records; temporary references belong to one task, saved records survive restarts. */
public final class DeviceDataStore {
    public static final int MAX_DATA_BYTES = 4 * 1024 * 1024;
    private static final int MAX_METADATA_BYTES = 32768;
    private static final long MAX_STORAGE_BYTES = 64L * 1024 * 1024;
    private static final int MAX_ENTRIES = 256;
    private static final int MAGIC = 0x44524431;
    private static final Object LOCK = new Object();
    private final Context context;
    private final Path saved, temporary;

    public DeviceDataStore(Context context) {
        this.context = context.getApplicationContext();
        saved = new File(this.context.getFilesDir(), "hardware-data").toPath();
        temporary = new File(this.context.getCacheDir(), "hardware-data").toPath();
    }

    DeviceDataStore(Path saved, Path temporary) { this.context = null; this.saved = saved; this.temporary = temporary; }

    public JSONObject capabilities() throws Exception {
        return new JSONObject().put("available", true).put("maxBytes", MAX_DATA_BYTES)
                .put("maxReadBytes", 16384).put("persistentStorage", "app_private_files")
                .put("temporaryStorage", "task_owned_cache");
    }

    public JSONObject execute(JSONObject args, String owner, Cancellation cancellation) throws Exception {
        cancellation.check();
        String operation = args.optString("operation");
        switch (operation) {
            case "list": return list(owner, args.optBoolean("persistentOnly", false));
            case "read": return readPage(args, owner);
            case "save": {
                byte[] bytes = readPayload(args, owner, MAX_DATA_BYTES);
                JSONObject metadata = args.optJSONObject("metadata");
                // Preserve source coverage and provenance when promoting an existing result.
                if (args.has("dataRef")) {
                    JSONObject source = record(args.getString("dataRef"), owner).metadata.getJSONObject("metadata");
                    JSONObject merged = new JSONObject(source.toString());
                    if (metadata != null) for (Iterator<String> keys = metadata.keys(); keys.hasNext();) {
                        String key = keys.next();
                        if (!merged.has(key)) merged.put(key, metadata.get(key));
                    }
                    metadata = merged;
                }
                cancellation.dispatched();
                JSONObject result = put(owner, bytes, metadata == null ? new JSONObject() : metadata, true,
                        args.optString("name", ""));
                cancellation.check();
                return result.put("status", "saved");
            }
            case "delete": {
                synchronized (LOCK) {
                    Path path = resolve(args.getString("dataRef"), owner);
                    cancellation.dispatched(); Files.delete(path);
                    return new JSONObject().put("status", "deleted").put("dataRef", args.getString("dataRef"));
                }
            }
            case "import": return importFile(args, owner, cancellation);
            case "export": return exportFile(args, owner, cancellation);
            default: throw failure("invalid_argument", R.string.hardware_data_invalid);
        }
    }

    public JSONObject put(String owner, byte[] data, JSONObject metadata, boolean persistent, String name) throws Exception {
        requireOwner(owner);
        if (data == null || data.length > MAX_DATA_BYTES) throw failure("device_data_limit", R.string.hardware_data_limit);
        if (metadata == null) throw failure("invalid_argument", R.string.hardware_data_invalid);
        if (name == null || name.length() > 200 || name.chars().anyMatch(ch -> Character.isISOControl(ch)))
            throw failure("invalid_argument", R.string.hardware_data_invalid);
        String ref = "dd_" + UUID.randomUUID();
        JSONObject header = new JSONObject().put("version", 1).put("dataRef", ref).put("name", name)
                .put("persistent", persistent).put("bytes", data.length).put("sha256", digest(data))
                .put("createdAt", System.currentTimeMillis()).put("metadata", new JSONObject(metadata.toString()));
        byte[] encoded = header.toString().getBytes(StandardCharsets.UTF_8);
        if (encoded.length > MAX_METADATA_BYTES) throw failure("device_data_limit", R.string.hardware_data_limit);
        synchronized (LOCK) {
            Path directory = persistent ? saved : ownerDirectory(owner);
            Files.createDirectories(directory);
            storageLimit(directory, encoded.length + data.length + 12L);
            Path staging = Files.createTempFile(directory, ".write-", ".tmp");
            try {
                try (FileOutputStream file = new FileOutputStream(staging.toFile()); DataOutputStream out = new DataOutputStream(file)) {
                    out.writeInt(MAGIC); out.writeInt(encoded.length); out.writeInt(data.length);
                    out.write(encoded); out.write(data); out.flush(); file.getFD().sync();
                }
                Files.move(staging, directory.resolve(ref + ".data"), StandardCopyOption.ATOMIC_MOVE);
            } finally { Files.deleteIfExists(staging); }
        }
        return header;
    }

    public byte[] readPayload(JSONObject args, String owner, int maxBytes) throws Exception {
        if (maxBytes < 0 || maxBytes > MAX_DATA_BYTES || args.has("hex") == args.has("dataRef"))
            throw failure("invalid_argument", R.string.hardware_data_payload);
        if (args.has("hex")) {
            Object hex = args.opt("hex");
            if (!(hex instanceof String)) throw failure("invalid_argument", R.string.hardware_data_payload);
            try { return HardwareSupport.unhex((String) hex, maxBytes); }
            catch (ActionException invalid) { throw failure("invalid_argument", R.string.hardware_data_payload); }
        }
        Object reference = args.opt("dataRef");
        if (!(reference instanceof String)) throw failure("invalid_argument", R.string.hardware_data_invalid);
        Record record = record((String) reference, owner);
        if (record.bytes.length > maxBytes) throw failure("device_data_limit", R.string.hardware_data_limit);
        return record.bytes;
    }

    public void closeOwner(String owner) {
        if (owner == null || owner.isBlank()) return;
        synchronized (LOCK) {
            Path directory = ownerDirectory(owner);
            if (!Files.exists(directory)) return;
            try (DirectoryStream<Path> entries = Files.newDirectoryStream(directory)) {
                for (Path path : entries) Files.delete(path);
            } catch (IOException error) { throw new IllegalStateException("device_data_cleanup_failed", error); }
            try { Files.deleteIfExists(directory); }
            catch (IOException error) { throw new IllegalStateException("device_data_cleanup_failed", error); }
        }
    }

    public void close() { /* Saved records intentionally outlive the tool host. */ }

    private JSONObject readPage(JSONObject args, String owner) throws Exception {
        Record record = record(args.getString("dataRef"), owner);
        int offset = HardwareSupport.integer(args, "offset", 0, 0, record.bytes.length);
        int limit = HardwareSupport.integer(args, "limit", 4096, 1, 16384);
        int end = Math.min(record.bytes.length, offset + limit);
        return new JSONObject(record.metadata.toString()).put("hex", HardwareSupport.hex(Arrays.copyOfRange(record.bytes, offset, end)))
                .put("offset", offset).put("returnedBytes", end - offset).put("nextOffset", end)
                .put("hasMore", end < record.bytes.length).put("integrityVerified", true);
    }

    private JSONObject list(String owner, boolean persistentOnly) throws Exception {
        requireOwner(owner);
        synchronized (LOCK) {
            JSONArray entries = new JSONArray();
            List<JSONObject> records = new ArrayList<>();
            for (Path directory : persistentOnly ? List.of(saved) : List.of(saved, ownerDirectory(owner))) {
                if (!Files.isDirectory(directory)) continue;
                try (DirectoryStream<Path> files = Files.newDirectoryStream(directory, "dd_*.data")) {
                    for (Path path : files) records.add(readRecord(path, false).metadata);
                }
            }
            records.sort(Comparator.comparingLong(item -> -item.optLong("createdAt")));
            for (JSONObject record : records) entries.put(record);
            return new JSONObject().put("items", entries).put("temporaryScope", "current_task");
        }
    }

    private Record record(String reference, String owner) throws Exception {
        synchronized (LOCK) { return readRecord(resolve(reference, owner), true); }
    }

    private Path resolve(String reference, String owner) throws Exception {
        requireOwner(owner);
        if (reference == null || !reference.matches("dd_[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))
            throw failure("invalid_argument", R.string.hardware_data_reference);
        Path persistent = saved.resolve(reference + ".data");
        if (Files.isRegularFile(persistent, LinkOption.NOFOLLOW_LINKS)) return persistent;
        Path scoped = ownerDirectory(owner).resolve(reference + ".data");
        if (Files.isRegularFile(scoped, LinkOption.NOFOLLOW_LINKS)) return scoped;
        throw failure("device_data_missing", R.string.hardware_data_missing);
    }

    private Record readRecord(Path path, boolean payload) throws Exception {
        try (DataInputStream input = new DataInputStream(Files.newInputStream(path))) {
            if (input.readInt() != MAGIC) throw failure("device_data_corrupt", R.string.hardware_data_corrupt);
            int headerLength = input.readInt(), dataLength = input.readInt();
            if (headerLength < 1 || headerLength > MAX_METADATA_BYTES || dataLength < 0 || dataLength > MAX_DATA_BYTES
                    || Files.size(path) != 12L + headerLength + dataLength)
                throw failure("device_data_corrupt", R.string.hardware_data_corrupt);
            byte[] header = new byte[headerLength]; input.readFully(header);
            JSONObject metadata = new JSONObject(new String(header, StandardCharsets.UTF_8));
            if (metadata.getInt("version") != 1 || metadata.getInt("bytes") != dataLength
                    || !path.getFileName().toString().equals(metadata.getString("dataRef") + ".data"))
                throw failure("device_data_corrupt", R.string.hardware_data_corrupt);
            byte[] bytes = payload ? new byte[dataLength] : new byte[0];
            if (payload) {
                input.readFully(bytes);
                if (!digest(bytes).equals(metadata.getString("sha256"))) throw failure("device_data_corrupt", R.string.hardware_data_corrupt);
            }
            return new Record(metadata, bytes);
        } catch (org.json.JSONException | EOFException invalid) {
            throw failure("device_data_corrupt", R.string.hardware_data_corrupt);
        }
    }

    private JSONObject importFile(JSONObject args, String owner, Cancellation cancellation) throws Exception {
        if (context == null) throw new IllegalStateException("android_context_required");
        FileGrantStore grants = new FileGrantStore(context);
        FileGrantStore.FileReference source = grants.resolveFile(args.getString("fileId"), true, false);
        return CancellableFileIo.run(cancellation, CancellableFileIo.TIMEOUT_MS, guard -> {
            guard.onCheck(() -> {
                try { grants.checkGrant(source.grant, true, false); }
                catch (ActionException revoked) {
                    InterruptedException stopped = new InterruptedException("file_permission_missing");
                    stopped.initCause(revoked); throw stopped;
                } catch (Exception failure) { throw new IllegalStateException("file_grant_check_failed", failure); }
            });
            CancellationSignal signal = new CancellationSignal(); guard.onCancel(signal::cancel);
            byte[] bytes;
            try (ParcelFileDescriptor descriptor = guard.track(context.getContentResolver().openFileDescriptor(source.uri, "r", signal))) {
                if (descriptor == null) throw failure("file_provider_unavailable", R.string.hardware_data_provider);
                try (InputStream input = guard.track(new ParcelFileDescriptor.AutoCloseInputStream(descriptor))) {
                    bytes = readBounded(input, MAX_DATA_BYTES, guard);
                }
            }
            guard.check();
            JSONObject metadata = args.optJSONObject("metadata");
            if (metadata == null) metadata = new JSONObject(); else metadata = new JSONObject(metadata.toString());
            metadata.put("source", "user_selected_document").put("fileId", source.id);
            guard.check(); cancellation.dispatched();
            return put(owner, bytes, metadata, true, args.optString("name", ""));
        });
    }

    private JSONObject exportFile(JSONObject args, String owner, Cancellation cancellation) throws Exception {
        if (context == null) throw new IllegalStateException("android_context_required");
        byte[] bytes = readPayload(new JSONObject().put("dataRef", args.getString("dataRef")), owner, MAX_DATA_BYTES);
        FileGrantStore grants = new FileGrantStore(context);
        FileGrantStore.Grant target = grants.resolveGrant(args.getString("grantId"), true, true);
        if (!target.tree) throw failure("directory_selection_required", R.string.hardware_data_directory);
        String name = SelectedFileTools.exportName(args.opt("name"));
        java.util.concurrent.atomic.AtomicReference<String> createdFile = new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicBoolean creationAttempted = new java.util.concurrent.atomic.AtomicBoolean();
        try { return CancellableFileIo.run(cancellation, CancellableFileIo.TIMEOUT_MS, guard -> {
            guard.onCheck(() -> {
                try { grants.checkGrant(target, true, true); }
                catch (ActionException revoked) {
                    InterruptedException stopped = new InterruptedException("file_permission_missing");
                    stopped.initCause(revoked); throw stopped;
                } catch (Exception failure) { throw new IllegalStateException("file_grant_check_failed", failure); }
            });
            CancellationSignal signal = new CancellationSignal(); guard.onCancel(signal::cancel);
            Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(target.uri, DocumentsContract.getTreeDocumentId(target.uri));
            try (Cursor cursor = guard.track(context.getContentResolver().query(children,
                    new String[]{DocumentsContract.Document.COLUMN_DISPLAY_NAME}, null, null, null, signal))) {
                if (cursor == null) throw failure("file_provider_unavailable", R.string.hardware_data_provider);
                while (cursor.moveToNext()) {
                    guard.check();
                    if (name.equalsIgnoreCase(cursor.getString(0))) throw failure("file_name_conflict", R.string.hardware_data_conflict);
                }
            }
            guard.check(); cancellation.dispatched(); creationAttempted.set(true);
            Uri created = DocumentsContract.createDocument(context.getContentResolver(), FileGrantStore.documentUri(target.uri),
                    "application/octet-stream", name);
            if (created == null) throw failure("file_create_failed", R.string.hardware_data_provider);
            String fileId = grants.remember(target, DocumentsContract.getDocumentId(created));
            createdFile.set(fileId);
            try {
                guard.check();
                try (ParcelFileDescriptor descriptor = guard.track(context.getContentResolver().openFileDescriptor(created, "w", signal))) {
                    if (descriptor == null) throw failure("file_provider_unavailable", R.string.hardware_data_provider);
                    try (OutputStream output = guard.track(new ParcelFileDescriptor.AutoCloseOutputStream(descriptor))) {
                        for (int offset = 0; offset < bytes.length; offset += 4096) {
                            guard.check(); output.write(bytes, offset, Math.min(4096, bytes.length - offset));
                        }
                        output.flush();
                    }
                }
                byte[] readback;
                try (ParcelFileDescriptor descriptor = guard.track(context.getContentResolver().openFileDescriptor(created, "r", signal))) {
                    if (descriptor == null) throw failure("file_provider_unavailable", R.string.hardware_data_provider);
                    try (InputStream input = guard.track(new ParcelFileDescriptor.AutoCloseInputStream(descriptor))) {
                        readback = readBounded(input, MAX_DATA_BYTES, guard);
                    }
                }
                guard.check();
                boolean verified = Arrays.equals(bytes, readback);
                return new JSONObject().put("status", verified ? "export_verified" : "export_unconfirmed")
                        .put("fileId", fileId).put("bytes", bytes.length).put("verified", verified);
            } catch (Exception failure) {
                if (failure instanceof InterruptedException) throw failure;
                throw DeviceDataStore.this.failure("device_data_export_unconfirmed", R.string.hardware_data_export_unconfirmed)
                        .withDetails(new JSONObject().put("fileId", fileId).put("partialFilePossible", true));
            }
        }); } catch (InterruptedException stopped) {
            if (!creationAttempted.get()) throw stopped;
            throw new ActionException("CANCELLED", "cancelled", HardwareSupport.text(context, R.string.hardware_data_export_unconfirmed))
                    .withDetails(exportDetails(createdFile.get()));
        } catch (ActionException failure) {
            if (creationAttempted.get()) failure.withDetails(exportDetails(createdFile.get()));
            throw failure;
        }
    }

    private static JSONObject exportDetails(String fileId) throws org.json.JSONException {
        return new JSONObject().put("partialFilePossible", true)
                .put("fileId", fileId == null ? JSONObject.NULL : fileId);
    }

    private byte[] readBounded(InputStream input, int maximum, Cancellation cancellation) throws Exception {
        ByteArrayOutputStream result = new ByteArrayOutputStream(); byte[] buffer = new byte[4096]; int count;
        while ((count = input.read(buffer)) != -1) {
            cancellation.check();
            if (count > maximum - result.size()) throw failure("device_data_limit", R.string.hardware_data_limit);
            result.write(buffer, 0, count);
        }
        return result.toByteArray();
    }

    private void storageLimit(Path directory, long incoming) throws Exception {
        long bytes = incoming; int count = 0;
        try (DirectoryStream<Path> files = Files.newDirectoryStream(directory, "dd_*.data")) {
            for (Path file : files) { bytes += Files.size(file); count++; }
        }
        if (bytes > MAX_STORAGE_BYTES || count >= MAX_ENTRIES) throw failure("device_data_limit", R.string.hardware_data_limit);
    }

    private Path ownerDirectory(String owner) {
        return temporary.resolve(digest(owner.getBytes(StandardCharsets.UTF_8)));
    }

    private void requireOwner(String owner) throws ActionException {
        if (owner == null || owner.isBlank() || owner.length() > 512) throw failure("invalid_argument", R.string.hardware_data_owner);
    }

    private ActionException failure(String code, int resource) {
        return context == null ? new ActionException(code, code) : HardwareSupport.error(context, code, resource);
    }

    private static String digest(byte[] bytes) {
        try { return HardwareSupport.hex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    private static final class Record {
        final JSONObject metadata; final byte[] bytes;
        Record(JSONObject metadata, byte[] bytes) { this.metadata = metadata; this.bytes = bytes; }
    }
}
