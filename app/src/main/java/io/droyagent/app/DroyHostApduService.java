package io.droyagent.app;

import android.nfc.cardemulation.HostApduService;
import android.os.Bundle;
import io.droyagent.androiduse.NfcProtocol;
import java.util.ArrayDeque;
import org.json.JSONArray;
import org.json.JSONObject;

/** RF responses run entirely locally; no network/model call occurs inside a tap. */
public final class DroyHostApduService extends HostApduService {
    private static final ArrayDeque<JSONObject> EVENTS = new ArrayDeque<>();
    private String profileId = "", state = "";
    @Override public byte[] processCommandApdu(byte[] request, Bundle extras) {
        if (request == null || request.length > 4096) return new byte[]{0x67, 0};
        try {
            JSONObject profile = HceProfiles.active(this);
            if (profile == null) { reset(); return new byte[]{0x69, (byte) 0x85}; }
            String id = profile.getString("profileId");
            if (!id.equals(profileId)) { profileId = id; state = profile.getString("initialState"); }
            JSONObject response = NfcProtocol.respond(profile, state, request);
            record(new JSONObject().put("observedAt", System.currentTimeMillis()).put("profileId", id).put("state", state)
                .put("requestHex", NfcProtocol.hex(request)).put("responseHex", response.getString("responseHex")).put("matched", response.getBoolean("matched")));
            state = response.getString("nextState");
            return NfcProtocol.unhex(response.getString("responseHex"), 4096);
        } catch (Exception unavailable) {
            try { record(new JSONObject().put("observedAt", System.currentTimeMillis()).put("status", "failed")
                .put("errorType", unavailable instanceof io.droyagent.androiduse.ActionException action ? action.getCode() : unavailable.getClass().getSimpleName())); }
            catch (org.json.JSONException invalidRecord) { throw new IllegalStateException(invalidRecord); }
            reset(); return new byte[]{0x69, (byte) 0x85};
        }
    }
    @Override public void onDeactivated(int reason) {
        try { record(new JSONObject().put("observedAt", System.currentTimeMillis()).put("deactivatedReason", reason)); }
        catch (org.json.JSONException impossible) { throw new IllegalStateException(impossible); }
        reset();
    }
    private void reset() { profileId = ""; state = ""; }
    private static synchronized void record(JSONObject event) { if (EVENTS.size() >= 64) EVENTS.removeFirst(); EVENTS.addLast(event); }
    static synchronized JSONObject events() throws Exception {
        JSONArray rows = new JSONArray(); for (JSONObject event : EVENTS) rows.put(new JSONObject(event.toString()));
        return new JSONObject().put("events", rows).put("retention", "last_64_in_process");
    }
}
