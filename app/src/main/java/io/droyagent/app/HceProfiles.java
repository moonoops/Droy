package io.droyagent.app;

import android.app.KeyguardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.nfc.NfcAdapter;
import android.nfc.cardemulation.CardEmulation;
import io.droyagent.androiduse.Cancellation;
import io.droyagent.androiduse.HardwareSupport;
import io.droyagent.androiduse.NfcProtocol;
import io.droyagent.androiduse.NfcTools;
import java.util.ArrayList;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;

/** Saved profiles describe exact local APDU/state rules, never executable model code. */
public final class HceProfiles {
    private static final Object LOCK = new Object();
    private final Context context;
    private final SharedPreferences profiles, state;
    public HceProfiles(Context context) {
        this.context = context.getApplicationContext();
        profiles = this.context.getSharedPreferences("nfc-hce-profiles", Context.MODE_PRIVATE);
        state = this.context.getSharedPreferences("nfc-hce", Context.MODE_PRIVATE);
    }
    public JSONObject capabilities() throws Exception {
        NfcAdapter adapter = NfcAdapter.getDefaultAdapter(context);
        return new JSONObject().put("supported", context.getPackageManager().hasSystemFeature(PackageManager.FEATURE_NFC_HOST_CARD_EMULATION))
            .put("enabled", adapter != null && adapter.isEnabled()).put("readerActive", NfcTools.readerActive())
            .put("activeProfileId", state.getString("active", "")).put("format", "exact_apdu_state_v1")
            .put("uidConfigurable", false).put("requiresUnlockedDevice", true).put("arbitraryCode", false);
    }
    public JSONObject execute(JSONObject args, String owner, Cancellation cancellation) throws Exception {
        String op = args.optString("operation", "status");
        if (op.equals("disable")) { cancellation.dispatched(); disable(context); return new JSONObject().put("status", "disabled"); }
        if (op.equals("status")) return capabilities();
        checkAllowed(context); cancellation.check();
        synchronized (LOCK) {
            switch (op) {
                case "list": {
                    JSONArray result = new JSONArray();
                    for (String id : profiles.getAll().keySet()) {
                        JSONObject profile = load(id);
                        result.put(new JSONObject().put("profileId", id).put("name", profile.getString("name"))
                            .put("aids", profile.getJSONArray("aids")).put("format", "exact_apdu_state_v1")
                            .put("active", id.equals(state.getString("active", ""))));
                    }
                    return new JSONObject().put("profiles", result);
                }
                case "get": return load(args.getString("profileId")).put("profileId", args.getString("profileId"));
                case "save": {
                    JSONObject profile;
                    try { profile = NfcProtocol.profile(args); }
                    catch (Exception invalid) { throw HardwareSupport.error(context, "invalid_hce_profile", io.droyagent.androiduse.R.string.nfc_hce_invalid); }
                    String id = args.optString("profileId", UUID.randomUUID().toString());
                    if (args.has("profileId") && !profiles.contains(id) || !id.matches("[0-9a-f-]{36}"))
                        throw HardwareSupport.error(context, "invalid_hce_profile", io.droyagent.androiduse.R.string.nfc_hce_invalid);
                    if (!profiles.contains(id) && profiles.getAll().size() >= 16)
                        throw HardwareSupport.error(context, "hce_profile_limit", io.droyagent.androiduse.R.string.nfc_hce_invalid);
                    if (id.equals(state.getString("active", ""))) disable(context);
                    cancellation.dispatched();
                    if (!profiles.edit().putString(id, profile.toString()).commit()) throw HardwareSupport.error(context, "hce_save_failed", io.droyagent.androiduse.R.string.nfc_save_failed);
                    return new JSONObject().put("status", "saved").put("profileId", id).put("format", "exact_apdu_state_v1");
                }
                case "delete": {
                    String id = args.getString("profileId"); load(id);
                    if (id.equals(state.getString("active", ""))) disable(context);
                    cancellation.dispatched();
                    if (!profiles.edit().remove(id).commit()) throw HardwareSupport.error(context, "hce_save_failed", io.droyagent.androiduse.R.string.nfc_save_failed);
                    return new JSONObject().put("status", "deleted");
                }
                case "enable": {
                    String id = args.getString("profileId"); JSONObject profile = load(id);
                    NfcAdapter adapter = NfcAdapter.getDefaultAdapter(context);
                    KeyguardManager keyguard = context.getSystemService(KeyguardManager.class);
                    if (adapter == null || !adapter.isEnabled() || !context.getPackageManager().hasSystemFeature(PackageManager.FEATURE_NFC_HOST_CARD_EMULATION)
                        || NfcTools.readerActive() || keyguard == null || keyguard.isDeviceLocked())
                        throw HardwareSupport.error(context, "hce_unavailable", io.droyagent.androiduse.R.string.nfc_hce_unavailable);
                    ArrayList<String> aids = new ArrayList<>();
                    for (int i = 0; i < profile.getJSONArray("aids").length(); i++) aids.add(profile.getJSONArray("aids").getString(i));
                    cancellation.dispatched();
                    boolean routed = CardEmulation.getInstance(adapter).registerAidsForService(service(context), CardEmulation.CATEGORY_OTHER, aids);
                    if (!routed) throw HardwareSupport.error(context, "hce_route_failed", io.droyagent.androiduse.R.string.nfc_hce_route_failed);
                    if (!state.edit().putString("active", id).commit()) {
                        CardEmulation.getInstance(adapter).removeAidsForService(service(context), CardEmulation.CATEGORY_OTHER);
                        throw HardwareSupport.error(context, "hce_save_failed", io.droyagent.androiduse.R.string.nfc_save_failed);
                    }
                    return new JSONObject().put("status", "route_registered").put("profileId", id).put("aids", profile.getJSONArray("aids"))
                        .put("readerAcceptance", "not_tested").put("uidConfigurable", false);
                }
                case "events": return DroyHostApduService.events();
                default: throw HardwareSupport.error(context, "invalid_argument", io.droyagent.androiduse.R.string.nfc_invalid_request);
            }
        }
    }
    JSONObject load(String id) throws Exception {
        String value = profiles.getString(id, null);
        if (value == null) throw HardwareSupport.error(context, "hce_profile_missing", io.droyagent.androiduse.R.string.nfc_hce_invalid);
        return new JSONObject(value);
    }
    static JSONObject active(Context context) throws Exception {
        checkAllowed(context);
        KeyguardManager keyguard = context.getSystemService(KeyguardManager.class);
        if (NfcTools.readerActive() || keyguard == null || keyguard.isDeviceLocked()) return null;
        HceProfiles store = new HceProfiles(context);
        String id = store.state.getString("active", "");
        if (id.isEmpty()) return null;
        return store.load(id).put("profileId", id);
    }
    static void checkAllowed(Context context) throws Exception {
        SharedPreferences choices = context.getSharedPreferences("product", Context.MODE_PRIVATE);
        if (choices.getBoolean("toolsStopped", false) || !choices.getBoolean("allowNfcEmulation", false))
            throw HardwareSupport.error(context, "product_permission_denied", io.droyagent.androiduse.R.string.nfc_denied);
    }
    private static ComponentName service(Context context) { return new ComponentName(context, DroyHostApduService.class); }
    public static void disable(Context context) {
        synchronized (LOCK) {
            if (!context.getSharedPreferences("nfc-hce", Context.MODE_PRIVATE).edit().remove("active").commit())
                throw new IllegalStateException(HardwareSupport.text(context, io.droyagent.androiduse.R.string.nfc_save_failed));
            NfcAdapter adapter = NfcAdapter.getDefaultAdapter(context);
            if (adapter != null && context.getPackageManager().hasSystemFeature(PackageManager.FEATURE_NFC_HOST_CARD_EMULATION))
                CardEmulation.getInstance(adapter).removeAidsForService(service(context), CardEmulation.CATEGORY_OTHER);
        }
    }
    public void closeOwner(String owner) { /* Explicit activation lasts until disable, pause or revocation. */ }
    public void close() { disable(context); }
}
