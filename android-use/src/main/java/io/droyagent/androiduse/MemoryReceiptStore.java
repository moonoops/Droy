package io.droyagent.androiduse;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/** Small durable metadata ledger; it never contains process memory or replayable commands. */
final class MemoryReceiptStore {
    private final File file;
    private final List<JSONObject> receipts = new ArrayList<>();
    private IOException loadFailure;

    MemoryReceiptStore(File file) {
        this.file = file;
        if (!file.exists()) return;
        try (FileInputStream input = new FileInputStream(file)) {
            if (file.length() > 1048576) throw new IOException("Memory receipt file exceeded its limit");
            java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
            byte[] chunk = new byte[4096]; int count;
            while ((count = input.read(chunk)) != -1) {
                if (buffer.size() + count > 1048576) throw new IOException("Memory receipt file exceeded its limit");
                buffer.write(chunk, 0, count);
            }
            byte[] bytes = buffer.toByteArray();
            JSONArray array = new JSONObject(new String(bytes, StandardCharsets.UTF_8)).getJSONArray("receipts");
            if (array.length() > 256) throw new IOException("Too many memory receipts");
            for (int i = 0; i < array.length(); i++) {
                JSONObject receipt = array.getJSONObject(i);
                if (receipt.optString("status").equals("pending")) {
                    receipt.put("status", "outcome_unknown").put("writeResultKnown", false)
                        .put("bytesWritten", JSONObject.NULL).put("helperExited", false);
                }
                receipts.add(receipt);
            }
        } catch (Exception failure) { loadFailure = new IOException("Memory receipt state could not be recovered", failure); }
    }

    synchronized JSONObject find(String owner, String receiptId) throws Exception {
        healthy();
        for (JSONObject receipt : receipts) if (owner.equals(receipt.optString("owner")) && receiptId.equals(receipt.optString("writeReceiptId")))
            return copy(receipt);
        return null;
    }

    synchronized JSONObject duplicate(String owner, String call, String readId) throws Exception {
        healthy();
        for (JSONObject receipt : receipts) if (owner.equals(receipt.optString("owner"))
            && (call.equals(receipt.optString("callId")) || readId.equals(receipt.optString("readId")))) return copy(receipt);
        return null;
    }

    synchronized List<JSONObject> unresolvedHelpers() throws Exception {
        healthy();
        List<JSONObject> result = new ArrayList<>();
        for (JSONObject receipt : receipts) if (!receipt.optBoolean("helperExited", true)) result.add(copy(receipt));
        return result;
    }

    synchronized JSONArray forOwner(String owner) throws Exception {
        healthy();
        JSONArray result = new JSONArray();
        for (JSONObject receipt : receipts) if (owner.equals(receipt.optString("owner"))) result.put(publicReceipt(receipt));
        return result;
    }

    synchronized void save(JSONObject receipt) throws Exception {
        healthy();
        JSONObject updated = copy(receipt);
        for (JSONObject current : receipts) {
            if (current.optString("writeReceiptId").equals(updated.optString("writeReceiptId"))
                    && current.optBoolean("taskEnded")) updated.put("taskEnded", true);
        }
        List<JSONObject> replacement = new ArrayList<>(receipts);
        replacement.removeIf(r -> r.optString("writeReceiptId").equals(updated.optString("writeReceiptId")));
        boolean discard = updated.optBoolean("taskEnded") && updated.optBoolean("helperExited", true);
        if (!discard) {
            if (replacement.size() >= 256) throw new ActionException("memory_receipt_limit", "End previous memory tasks before writing again");
            replacement.add(updated);
        }
        persist(replacement);
        receipts.clear(); receipts.addAll(replacement);
    }

    synchronized void end(String owner) {
        if (loadFailure != null || receipts.isEmpty()) return;
        try {
            List<JSONObject> replacement = new ArrayList<>();
            for (JSONObject current : receipts) {
                JSONObject receipt = copy(current);
                if (owner.equals(receipt.optString("owner"))) receipt.put("taskEnded", true);
                if (!(receipt.optBoolean("taskEnded") && receipt.optBoolean("helperExited", true))) replacement.add(receipt);
            }
            persist(replacement); receipts.clear(); receipts.addAll(replacement);
        } catch (Exception failure) { loadFailure = new IOException("Memory receipt cleanup could not be saved", failure); }
    }

    private void persist(List<JSONObject> values) throws Exception {
        File parent = file.getParentFile();
        if (!parent.isDirectory() && !parent.mkdirs()) throw new IOException("Memory receipt directory is unavailable");
        byte[] bytes = new JSONObject().put("version", 1).put("receipts", new JSONArray(values)).toString().getBytes(StandardCharsets.UTF_8);
        if (bytes.length > 1048576) throw new IOException("Memory receipt file exceeded its limit");
        File temporary = new File(parent, file.getName() + ".new");
        try {
            try (FileOutputStream output = new FileOutputStream(temporary)) { output.write(bytes); output.getFD().sync(); }
            Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            // Sync the directory entry as well as the file contents before allowing the write.
            try (java.nio.channels.FileChannel directory = java.nio.channels.FileChannel.open(parent.toPath(), java.nio.file.StandardOpenOption.READ)) {
                directory.force(true);
            }
        } finally { Files.deleteIfExists(temporary.toPath()); }
    }

    private void healthy() throws IOException { if (loadFailure != null) throw loadFailure; }
    static JSONObject copy(JSONObject value) throws Exception { return new JSONObject(value.toString()); }
    static JSONObject publicReceipt(JSONObject receipt) throws Exception {
        JSONObject result = copy(receipt);
        result.remove("owner"); result.remove("callId"); result.remove("readId"); result.remove("fingerprint"); result.remove("helper");
        return result;
    }
}
