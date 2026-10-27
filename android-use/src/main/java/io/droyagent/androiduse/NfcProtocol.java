package io.droyagent.androiduse;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.Arrays;

/** Wire values stay lossless; a card's unread regions are never synthesized. */
public final class NfcProtocol {
    private NfcProtocol() {}

    public static byte[] unhex(String value, int maximum) {
        if (value == null || value.length() % 2 != 0 || value.length() > maximum * 2 || !value.matches("[0-9a-fA-F]*"))
            throw new IllegalArgumentException("invalid_hex");
        byte[] bytes = new byte[value.length() / 2];
        for (int i = 0; i < bytes.length; i++) bytes[i] = (byte) Integer.parseInt(value.substring(i * 2, i * 2 + 2), 16);
        return bytes;
    }

    public static String hex(byte[] value) {
        StringBuilder result = new StringBuilder(value.length * 2);
        for (byte item : value) result.append(String.format(java.util.Locale.ROOT, "%02x", item & 255));
        return result.toString();
    }

    public static JSONObject rule(JSONObject input) throws Exception {
        String state = input.getString("state"), next = input.optString("nextState", state);
        if (!state.matches("[A-Za-z0-9_-]{1,32}") || !next.matches("[A-Za-z0-9_-]{1,32}"))
            throw new IllegalArgumentException("invalid_state");
        byte[] request = unhex(input.getString("requestHex"), 4096), response = unhex(input.getString("responseHex"), 4096);
        if (request.length < 4 || response.length < 2) throw new IllegalArgumentException("invalid_apdu");
        return new JSONObject().put("state", state).put("nextState", next)
            .put("requestHex", hex(request)).put("responseHex", hex(response));
    }

    public static JSONObject profile(JSONObject source) throws Exception {
        String name = source.getString("name").trim();
        if (name.isEmpty() || name.length() > 80) throw new IllegalArgumentException("invalid_name");
        JSONArray aids = source.getJSONArray("aids"), rules = source.getJSONArray("rules");
        if (aids.length() < 1 || aids.length() > 16 || rules.length() < 1 || rules.length() > 64)
            throw new IllegalArgumentException("invalid_profile_size");
        JSONArray cleanAids = new JSONArray(), cleanRules = new JSONArray();
        java.util.Set<String> aidSet = new java.util.HashSet<>(), matchSet = new java.util.HashSet<>();
        int totalBytes = 0;
        for (int i = 0; i < aids.length(); i++) {
            String aid = aids.getString(i).toUpperCase(java.util.Locale.ROOT);
            if (!aid.matches("[0-9A-F]{10,32}") || aid.length() % 2 != 0 || !aidSet.add(aid))
                throw new IllegalArgumentException("invalid_aid");
            cleanAids.put(aid);
        }
        for (int i = 0; i < rules.length(); i++) {
            JSONObject clean = rule(rules.getJSONObject(i));
            if (!matchSet.add(clean.getString("state") + ":" + clean.getString("requestHex")))
                throw new IllegalArgumentException("duplicate_rule");
            totalBytes += clean.getString("requestHex").length() + clean.getString("responseHex").length();
            if (totalBytes > 131072) throw new IllegalArgumentException("profile_too_large");
            cleanRules.put(clean);
        }
        String initialState = source.optString("initialState", "initial");
        if (!initialState.matches("[A-Za-z0-9_-]{1,32}")) throw new IllegalArgumentException("invalid_state");
        return new JSONObject().put("name", name).put("aids", cleanAids).put("rules", cleanRules)
            .put("initialState", initialState).put("format", "exact_apdu_state_v1");
    }

    public static JSONObject respond(JSONObject profile, String state, byte[] request) throws Exception {
        String hex = hex(request);
        JSONArray rules = profile.getJSONArray("rules");
        for (int i = 0; i < rules.length(); i++) {
            JSONObject rule = rules.getJSONObject(i);
            if (state.equals(rule.getString("state")) && hex.equals(rule.getString("requestHex")))
                return new JSONObject().put("responseHex", rule.getString("responseHex")).put("nextState", rule.getString("nextState")).put("matched", true);
        }
        return new JSONObject().put("responseHex", "6a82").put("nextState", state).put("matched", false);
    }

    public static boolean classicWritableBlock(int block, int sectorFirst, int sectorSize) {
        return block > 0 && block >= sectorFirst && block < sectorFirst + sectorSize - 1;
    }

    public static boolean ultralightWritablePages(int first, int count) {
        // Pages 0–3 include UID/manufacturer/lock/OTP data. Other chip-specific
        // protection/configuration pages are not exposed by the ordinary write tool.
        return first >= 4 && count > 0 && first + count <= 16;
    }
}
