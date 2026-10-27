package io.droyagent.androiduse;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.nfc.NdefMessage;
import android.nfc.NdefRecord;
import android.nfc.NfcAdapter;
import android.nfc.Tag;
import android.nfc.tech.*;
import android.os.Bundle;
import android.os.SystemClock;
import java.io.IOException;
import java.lang.ref.WeakReference;
import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.IntConsumer;
import org.json.JSONArray;
import org.json.JSONObject;

/** The foreground reader owns short-lived tag handles; tools never reconstruct one from a UID. */
public final class NfcTools {
    private static volatile NfcTools current;
    private final Context context;
    private final ExecutorService io = Executors.newSingleThreadExecutor(r -> { Thread t = new Thread(r, "droy-nfc"); t.setDaemon(true); return t; });
    private final DeviceDataStore data;
    private volatile Reader reader;

    private static final class Reader {
        final String token = UUID.randomUUID().toString(), owner;
        final CompletableFuture<JSONObject> detected = new CompletableFuture<>();
        volatile WeakReference<Activity> activity = new WeakReference<>(null);
        volatile IntConsumer status;
        volatile boolean resumed, closed;
        volatile Card card;
        Reader(String owner) { this.owner = owner; }
    }
    private static final class Card {
        final Tag tag;
        final String id = UUID.randomUUID().toString();
        final AtomicBoolean busy = new AtomicBoolean();
        volatile TagTechnology connected;
        volatile String technology;
        volatile boolean closed;
        Card(Tag tag) { this.tag = tag; }
    }
    public NfcTools(Context context) {
        this.context = context.getApplicationContext(); data = new DeviceDataStore(this.context);
    }
    public JSONObject capabilities() throws Exception {
        NfcAdapter adapter = NfcAdapter.getDefaultAdapter(context);
        return new JSONObject().put("supported", adapter != null).put("enabled", adapter != null && adapter.isEnabled())
            .put("readerActive", readerActive()).put("requiresForegroundActivity", true)
            .put("hceSupported", context.getPackageManager().hasSystemFeature(PackageManager.FEATURE_NFC_HOST_CARD_EMULATION));
    }
    public JSONObject execute(JSONObject args, String owner, Cancellation cancellation) throws Exception {
        String operation = args.optString("operation", "status");
        if (operation.equals("close")) { closeOwner(owner); return new JSONObject().put("status", "closed"); }
        if (operation.equals("status")) return capabilities();
        requireProductAllowed(false); cancellation.check();
        if (operation.equals("credentials")) { requireProductAllowed(true); return new JSONObject().put("credentials", new NfcCredentials(context).list()); }
        if (operation.equals("credential_add")) {
            requireProductAllowed(true); cancellation.dispatched();
            context.startActivity(new Intent().setClassName(context, "io.droyagent.app.NfcCredentialActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            return new JSONObject().put("status", "user_input_requested");
        }
        if (operation.equals("credential_delete")) {
            requireProductAllowed(true); cancellation.dispatched(); new NfcCredentials(context).delete(args.getString("credentialRef"));
            return new JSONObject().put("status", "deleted");
        }
        requireAllowed(false);
        if (operation.equals("scan")) return scan(args, owner, cancellation);
        boolean writes = java.util.Set.of("transceive", "authenticate", "ndef_write", "format", "block_write", "page_write").contains(operation);
        requireAllowed(writes);
        Reader selected = reader;
        if (selected == null || selected.closed || !selected.owner.equals(owner) || !selected.resumed)
            throw error("nfc_session_lost", R.string.nfc_session_lost);
        Card card = selected.card;
        if (card == null || card.closed || !card.id.equals(args.optString("sessionId"))) throw error("nfc_session_lost", R.string.nfc_session_lost);
        if (!card.busy.compareAndSet(false, true)) throw error("nfc_busy", R.string.nfc_reader_busy);
        Future<JSONObject> future = io.submit(() -> {
            try {
                requireCard(selected, card, writes); cancellation.check();
                JSONObject result = perform(operation, args, card, owner, cancellation);
                requireCard(selected, card, writes); cancellation.check();
                return result.put("sessionId", card.id);
            } finally { card.busy.set(false); }
        });
        try {
            long deadline = SystemClock.elapsedRealtime() + cancellation.remainingMillis(10000);
            for (;;) {
                cancellation.check(); requireCard(selected, card, writes);
                if (SystemClock.elapsedRealtime() >= deadline) throw error("nfc_timeout", R.string.nfc_io_failed);
                try { return future.get(50, TimeUnit.MILLISECONDS); }
                catch (TimeoutException waiting) { }
                catch (ExecutionException failure) {
                    Throwable cause = failure.getCause();
                    if (cause instanceof ActionException) throw (ActionException) cause;
                    if (cause instanceof InterruptedException) throw (InterruptedException) cause;
                    if (cause instanceof IOException || cause instanceof android.nfc.FormatException) {
                        closeCard(card); throw error("nfc_io_failed", R.string.nfc_io_failed);
                    }
                    if (cause instanceof Exception) throw (Exception) cause;
                    throw new IllegalStateException(cause);
                }
            }
        } catch (Exception failure) { if (!future.isDone()) { closeCard(card); future.cancel(true); } throw failure; }
    }
    private JSONObject scan(JSONObject args, String owner, Cancellation cancellation) throws Exception {
        NfcAdapter adapter = NfcAdapter.getDefaultAdapter(context);
        if (adapter == null || !adapter.isEnabled()) throw error("nfc_unavailable", R.string.nfc_unavailable);
        if (!context.getSharedPreferences("nfc-hce", Context.MODE_PRIVATE).getString("active", "").isEmpty())
            throw error("nfc_hce_active", R.string.nfc_hce_active);
        int timeout = HardwareSupport.integer(args, "timeoutMs", 10000, 500, 30000);
        // Only an actual scan claims the foreground UI bridge. Creating a host
        // for local credential operations must not replace an existing reader.
        synchronized (NfcTools.class) {
            NfcTools previous = current;
            if (previous != null && previous != this) previous.close();
            current = this;
        }
        close(); Reader selected = new Reader(owner); reader = selected;
        cancellation.dispatched();
        try {
            context.startActivity(new Intent().setClassName(context, "io.droyagent.app.NfcReaderActivity")
                .putExtra("readerToken", selected.token).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            long deadline = SystemClock.elapsedRealtime() + cancellation.remainingMillis(timeout);
            while (SystemClock.elapsedRealtime() < deadline) {
                cancellation.check(); requireAllowed(false);
                if (selected.closed) throw error("nfc_session_lost", R.string.nfc_session_lost);
                try { return selected.detected.get(50, TimeUnit.MILLISECONDS); }
                catch (TimeoutException waiting) { }
                catch (ExecutionException failure) { throw error("nfc_io_failed", R.string.nfc_io_failed); }
            }
            throw error("nfc_timeout", R.string.nfc_timeout);
        } catch (Exception failure) { closeReader(selected); throw failure; }
    }
    private JSONObject perform(String op, JSONObject args, Card card, String owner, Cancellation cancellation) throws Exception {
        if (op.equals("inspect")) return describe(card);
        if (op.equals("read")) {
            Ndef ndef = (Ndef) connect(card, "Ndef");
            NdefMessage message = ndef.getNdefMessage();
            JSONObject result = new JSONObject().put("format", "ndef").put("capacityBytes", ndef.getMaxSize())
                .put("writable", ndef.isWritable()).put("records", new JSONArray()).put("coverage", "ndef_message_only");
            if (message == null) return result.put("status", "empty");
            JSONArray records = result.getJSONArray("records");
            NdefRecord[] sourceRecords = message.getRecords();
            result.put("recordCount", sourceRecords.length).put("recordsTruncated", sourceRecords.length > 16);
            for (int index = 0; index < Math.min(16, sourceRecords.length); index++) {
                NdefRecord record = sourceRecords[index];
                byte[] payload = record.getPayload();
                JSONObject row = new JSONObject().put("tnf", record.getTnf()).put("typeHex", NfcProtocol.hex(record.getType()))
                    .put("idHex", NfcProtocol.hex(record.getId())).put("payloadHex", NfcProtocol.hex(Arrays.copyOf(payload, Math.min(payload.length, 512))))
                    .put("payloadBytes", payload.length).put("payloadTruncated", payload.length > 512);
                if (record.toUri() != null && record.toUri().toString().length() <= 2048) row.put("uri", record.toUri().toString());
                records.put(row);
            }
            byte[] bytes = message.toByteArray();
            JSONObject stored = data.put(owner, bytes, new JSONObject().put("format", "ndef").put("coverage", "ndef_message_only").put("complete", false), false, HardwareSupport.text(context, R.string.nfc_data_ndef));
            return result.put("status", "read").put("data", stored);
        }
        if (op.equals("ndef_write") || op.equals("format")) {
            byte[] bytes = data.readPayload(args, owner, 65536);
            NdefMessage message;
            try { message = new NdefMessage(bytes); }
            catch (android.nfc.FormatException invalid) { throw error("invalid_argument", R.string.nfc_invalid_request); }
            cancellation.check();
            if (op.equals("format")) {
                NdefFormatable format = (NdefFormatable) connect(card, "NdefFormatable");
                cancellation.dispatched(); format.format(message);
                // Android's original Tag may not enumerate Ndef until the next tap.
                format.close(); card.connected = null;
                return new JSONObject().put("status", "formatted").put("verified", false).put("retapRequired", true);
            }
            Ndef target = (Ndef) connect(card, "Ndef");
            if (!target.isWritable() || target.getMaxSize() < bytes.length) throw error("nfc_write_rejected", R.string.nfc_write_rejected);
            cancellation.dispatched(); target.writeNdefMessage(message); cancellation.check();
            NdefMessage observed = target.getNdefMessage();
            boolean verified = observed != null && Arrays.equals(bytes, observed.toByteArray());
            return new JSONObject().put("status", verified ? "written_verified" : "write_unconfirmed").put("verified", verified).put("bytes", bytes.length);
        }
        if (op.equals("authenticate")) {
            MifareClassic classic = (MifareClassic) connect(card, "MifareClassic");
            int sector = requiredInteger(args, "sector", 0, classic.getSectorCount() - 1);
            String kind = args.optString("keyType", "A");
            if (!kind.equals("A") && !kind.equals("B")) throw error("invalid_argument", R.string.nfc_invalid_request);
            if (!args.has("credentialRef")) throw error("credential_required", R.string.nfc_auth_required);
            byte[] key = new NfcCredentials(context).read(args.getString("credentialRef"));
            try {
                cancellation.dispatched();
                boolean authenticated = kind.equals("A") ? classic.authenticateSectorWithKeyA(sector, key) : classic.authenticateSectorWithKeyB(sector, key);
                return new JSONObject().put("status", authenticated ? "authenticated" : "authentication_failed").put("sector", sector).put("authenticated", authenticated);
            } finally { Arrays.fill(key, (byte) 0); }
        }
        if (op.equals("block_read") || op.equals("block_write")) {
            MifareClassic classic = (MifareClassic) connect(card, "MifareClassic");
            int block = requiredInteger(args, "block", 0, classic.getBlockCount() - 1);
            if (op.equals("block_read")) {
                byte[] bytes = classic.readBlock(block);
                return new JSONObject().put("status", "read").put("block", block).put("hex", NfcProtocol.hex(bytes))
                    .put("coverage", new JSONObject().put("unit", "block").put("start", block).put("count", 1))
                    .put("data", data.put(owner, bytes, new JSONObject().put("format", "mifare_classic_block").put("block", block).put("readRanges", new JSONArray().put(new JSONObject().put("start", block).put("count", 1))).put("coverage", "one_block_only").put("complete", false), false, HardwareSupport.text(context, R.string.nfc_data_block)));
            }
            int sector = classic.blockToSector(block);
            byte[] bytes = data.readPayload(args, owner, 16);
            if (bytes.length != 16 || !NfcProtocol.classicWritableBlock(block, classic.sectorToBlock(sector), classic.getBlockCountInSector(sector)))
                throw error("nfc_write_rejected", R.string.nfc_write_rejected);
            cancellation.dispatched(); classic.writeBlock(block, bytes); cancellation.check();
            boolean verified = Arrays.equals(bytes, classic.readBlock(block));
            return new JSONObject().put("status", verified ? "written_verified" : "write_unconfirmed").put("verified", verified).put("block", block);
        }
        if (op.equals("page_read") || op.equals("page_write")) {
            MifareUltralight light = (MifareUltralight) connect(card, "MifareUltralight");
            int page = requiredInteger(args, "page", 0, 255);
            if (op.equals("page_read")) {
                int count = HardwareSupport.integer(args, "count", 4, 1, 16);
                if (page + count > 256) throw error("invalid_argument", R.string.nfc_invalid_request);
                byte[] bytes = new byte[count * 4];
                for (int offset = 0; offset < count; offset += 4) {
                    cancellation.check(); byte[] group = light.readPages(page + offset);
                    System.arraycopy(group, 0, bytes, offset * 4, Math.min(group.length, (count - offset) * 4));
                }
                return new JSONObject().put("status", "read").put("page", page).put("count", count).put("hex", NfcProtocol.hex(bytes))
                    .put("data", data.put(owner, bytes, new JSONObject().put("format", "mifare_ultralight_pages").put("page", page).put("count", count).put("readRanges", new JSONArray().put(new JSONObject().put("start", page).put("count", count))).put("coverage", "specified_pages_only").put("complete", false), false, HardwareSupport.text(context, R.string.nfc_data_pages)));
            }
            byte[] bytes = data.readPayload(args, owner, 4);
            if (bytes.length != 4 || !NfcProtocol.ultralightWritablePages(page, 1)) throw error("nfc_write_rejected", R.string.nfc_write_rejected);
            cancellation.dispatched(); light.writePage(page, bytes); cancellation.check();
            boolean verified = Arrays.equals(bytes, Arrays.copyOf(light.readPages(page), 4));
            return new JSONObject().put("status", verified ? "written_verified" : "write_unconfirmed").put("verified", verified).put("page", page);
        }
        if (op.equals("transceive")) {
            String technology = args.getString("technology");
            TagTechnology target = connect(card, technology);
            byte[] request = data.readPayload(args, owner, 65536);
            int maximum = maximum(target);
            if (request.length < 1 || request.length > maximum) throw error("invalid_argument", R.string.nfc_invalid_request);
            cancellation.dispatched(); byte[] response;
            if (target instanceof IsoDep t) response = t.transceive(request);
            else if (target instanceof NfcA t) response = t.transceive(request);
            else if (target instanceof NfcB t) response = t.transceive(request);
            else if (target instanceof NfcF t) response = t.transceive(request);
            else if (target instanceof NfcV t) response = t.transceive(request);
            else if (target instanceof MifareClassic t) response = t.transceive(request);
            else if (target instanceof MifareUltralight t) response = t.transceive(request);
            else throw error("nfc_unsupported_technology", R.string.nfc_unsupported_tech);
            JSONObject result = new JSONObject().put("status", "response_received").put("technology", technology).put("bytes", response.length)
                .put("data", data.put(owner, response, new JSONObject().put("format", "nfc_response").put("technology", technology).put("complete", true), false, HardwareSupport.text(context, R.string.nfc_data_response)));
            if (response.length <= 2048) result.put("hex", NfcProtocol.hex(response));
            return result;
        }
        throw error("invalid_argument", R.string.nfc_invalid_request);
    }
    private TagTechnology connect(Card card, String technology) throws Exception {
        if (card.connected != null && technology.equals(card.technology) && card.connected.isConnected()) return card.connected;
        if (card.connected != null) card.connected.close();
        TagTechnology next = switch (technology) {
            case "Ndef" -> Ndef.get(card.tag);
            case "NdefFormatable" -> NdefFormatable.get(card.tag);
            case "IsoDep" -> IsoDep.get(card.tag);
            case "NfcA" -> NfcA.get(card.tag);
            case "NfcB" -> NfcB.get(card.tag);
            case "NfcF" -> NfcF.get(card.tag);
            case "NfcV" -> NfcV.get(card.tag);
            case "MifareClassic" -> MifareClassic.get(card.tag);
            case "MifareUltralight" -> MifareUltralight.get(card.tag);
            default -> null;
        };
        if (next == null) throw error("nfc_unsupported_technology", R.string.nfc_unsupported_tech);
        card.connected = next; card.technology = technology;
        next.connect();
        if (next instanceof IsoDep t) t.setTimeout(3000);
        if (next instanceof NfcA t) t.setTimeout(3000);
        if (next instanceof NfcF t) t.setTimeout(3000);
        if (next instanceof MifareClassic t) t.setTimeout(3000);
        if (next instanceof MifareUltralight t) t.setTimeout(3000);
        if (card.closed) { next.close(); throw error("nfc_session_lost", R.string.nfc_session_lost); }
        return next;
    }
    private static int maximum(TagTechnology target) {
        if (target instanceof IsoDep t) return t.getMaxTransceiveLength();
        if (target instanceof NfcA t) return t.getMaxTransceiveLength();
        if (target instanceof NfcB t) return t.getMaxTransceiveLength();
        if (target instanceof NfcF t) return t.getMaxTransceiveLength();
        if (target instanceof NfcV t) return t.getMaxTransceiveLength();
        if (target instanceof MifareClassic t) return t.getMaxTransceiveLength();
        if (target instanceof MifareUltralight t) return t.getMaxTransceiveLength();
        return 0;
    }
    private JSONObject describe(Card card) throws Exception {
        JSONArray technologies = new JSONArray();
        for (String tech : card.tag.getTechList()) technologies.put(tech.substring(tech.lastIndexOf('.') + 1));
        JSONObject result = new JSONObject().put("status", "detected").put("sessionId", card.id)
            .put("uidHex", NfcProtocol.hex(card.tag.getId())).put("technologies", technologies).put("observedAt", System.currentTimeMillis())
            .put("encryption", "not_determined").put("coverage", "technology_metadata_only");
        NfcA a = NfcA.get(card.tag); if (a != null) result.put("nfcA", new JSONObject().put("atqaHex", NfcProtocol.hex(a.getAtqa())).put("sak", a.getSak()));
        NfcB b = NfcB.get(card.tag); if (b != null) result.put("nfcB", new JSONObject().put("applicationDataHex", NfcProtocol.hex(b.getApplicationData())).put("protocolInfoHex", NfcProtocol.hex(b.getProtocolInfo())));
        NfcF f = NfcF.get(card.tag); if (f != null) result.put("nfcF", new JSONObject().put("manufacturerHex", NfcProtocol.hex(f.getManufacturer())).put("systemCodeHex", NfcProtocol.hex(f.getSystemCode())));
        NfcV v = NfcV.get(card.tag); if (v != null) result.put("nfcV", new JSONObject().put("dsfId", v.getDsfId() & 255).put("responseFlags", v.getResponseFlags() & 255));
        IsoDep iso = IsoDep.get(card.tag); if (iso != null) {
            JSONObject details = new JSONObject().put("maxTransceiveBytes", iso.getMaxTransceiveLength()).put("extendedLengthApdu", iso.isExtendedLengthApduSupported());
            if (iso.getHistoricalBytes() != null) details.put("historicalHex", NfcProtocol.hex(iso.getHistoricalBytes()));
            if (iso.getHiLayerResponse() != null) details.put("higherLayerResponseHex", NfcProtocol.hex(iso.getHiLayerResponse()));
            result.put("isoDep", details);
        }
        Ndef ndef = Ndef.get(card.tag); if (ndef != null) result.put("ndef", new JSONObject().put("type", ndef.getType()).put("capacityBytes", ndef.getMaxSize()).put("writable", ndef.isWritable()));
        MifareClassic classic = MifareClassic.get(card.tag); if (classic != null) result.put("classic", new JSONObject().put("sizeBytes", classic.getSize()).put("sectors", classic.getSectorCount()).put("blocks", classic.getBlockCount()));
        MifareUltralight light = MifareUltralight.get(card.tag); if (light != null) result.put("ultralight", new JSONObject().put("type", light.getType()));
        return result;
    }
    private void requireProductAllowed(boolean write) throws Exception {
        android.content.SharedPreferences choices = context.getSharedPreferences("product", Context.MODE_PRIVATE);
        if (choices.getBoolean("toolsStopped", false) || !choices.getBoolean("allowNfcRead", false) || write && !choices.getBoolean("allowNfcWrite", false))
            throw error("product_permission_denied", R.string.nfc_denied);
    }
    private void requireAllowed(boolean write) throws Exception {
        requireProductAllowed(write);
        if (context.checkSelfPermission(Manifest.permission.NFC) != PackageManager.PERMISSION_GRANTED)
            throw error("system_permission_denied", R.string.nfc_denied);
        NfcAdapter adapter = NfcAdapter.getDefaultAdapter(context);
        if (adapter == null || !adapter.isEnabled()) throw error("nfc_unavailable", R.string.nfc_unavailable);
    }
    private void requireCard(Reader selected, Card card, boolean write) throws Exception {
        requireAllowed(write);
        if (selected != reader || selected.closed || !selected.resumed || selected.card != card || card.closed)
            throw error("nfc_session_lost", R.string.nfc_session_lost);
    }
    private ActionException error(String code, int resource) { return HardwareSupport.error(context, code, resource); }
    private int requiredInteger(JSONObject args, String key, int minimum, int maximum) throws ActionException {
        if (!args.has(key)) throw error("invalid_argument", R.string.nfc_invalid_request);
        return HardwareSupport.integer(args, key, minimum, minimum, maximum);
    }
    public static boolean readerActive() { NfcTools value = current; return value != null && value.reader != null && !value.reader.closed; }
    public static boolean resumeReader(Activity activity, String token, IntConsumer status) {
        NfcTools value = current; Reader selected = value == null ? null : value.reader;
        if (selected == null || selected.closed || !selected.token.equals(token)) return false;
        try {
            value.requireAllowed(false);
            selected.activity = new WeakReference<>(activity); selected.status = status; selected.resumed = true;
            Bundle options = new Bundle(); options.putInt(NfcAdapter.EXTRA_READER_PRESENCE_CHECK_DELAY, 100);
            NfcAdapter.getDefaultAdapter(activity).enableReaderMode(activity, tag -> {
                try {
                    value.requireAllowed(false);
                    if (value.reader != selected || selected.closed || !selected.resumed) return;
                    Card previous = selected.card; if (previous != null) closeCard(previous);
                    Card card = new Card(tag); selected.card = card;
                    JSONObject result = value.describe(card); selected.detected.complete(result);
                    activity.runOnUiThread(() -> status.accept(R.string.nfc_detected));
                } catch (Exception failure) { value.closeReader(selected); }
            }, NfcAdapter.FLAG_READER_NFC_A | NfcAdapter.FLAG_READER_NFC_B | NfcAdapter.FLAG_READER_NFC_F | NfcAdapter.FLAG_READER_NFC_V, options);
            status.accept(R.string.nfc_waiting); return true;
        } catch (Exception failure) { value.closeReader(selected); return false; }
    }
    public static void pauseReader(Activity activity, String token) {
        NfcTools value = current; Reader selected = value == null ? null : value.reader;
        if (selected == null || !selected.token.equals(token) || selected.activity.get() != activity) return;
        selected.resumed = false;
        NfcAdapter adapter = NfcAdapter.getDefaultAdapter(activity); if (adapter != null) adapter.disableReaderMode(activity);
        if (selected.card != null) closeCard(selected.card);
        if (activity.isFinishing()) value.closeReader(selected);
    }
    public static void cancelReader(String token) {
        NfcTools value = current; Reader selected = value == null ? null : value.reader;
        if (selected != null && selected.token.equals(token)) value.closeReader(selected);
    }
    private static void closeCard(Card card) {
        card.closed = true;
        TagTechnology connected = card.connected;
        if (connected != null) try { connected.close(); } catch (IOException ignored) { /* Closing invalidates this handle even if the tag has already left. */ }
    }
    private void closeReader(Reader selected) {
        selected.closed = true; selected.resumed = false;
        if (selected.card != null) closeCard(selected.card);
        selected.detected.completeExceptionally(new IllegalStateException("reader_closed"));
        Activity activity = selected.activity.get();
        if (activity != null) activity.runOnUiThread(() -> { NfcAdapter adapter = NfcAdapter.getDefaultAdapter(activity); if (adapter != null) adapter.disableReaderMode(activity); activity.finish(); });
        if (reader == selected) reader = null;
    }
    public void closeOwner(String owner) { Reader selected = reader; if (selected != null && selected.owner.equals(owner)) closeReader(selected); }
    public void close() { Reader selected = reader; if (selected != null) closeReader(selected); }
}
