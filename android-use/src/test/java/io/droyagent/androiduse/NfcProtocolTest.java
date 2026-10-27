package io.droyagent.androiduse;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class NfcProtocolTest {
    @Test public void binaryRoundTripDoesNotAlterZeroOrHighBytes() {
        assertEquals("0001feff", NfcProtocol.hex(NfcProtocol.unhex("0001FEff", 4)));
        for (String bad : new String[]{"0", "zz", "00 01", "0000000000"}) {
            try { NfcProtocol.unhex(bad, 4); fail(); } catch (IllegalArgumentException expected) { }
        }
    }
    @Test public void protectedCardBlocksAreExcludedFromOrdinaryWrites() {
        assertFalse(NfcProtocol.classicWritableBlock(0, 0, 4));
        assertFalse(NfcProtocol.classicWritableBlock(3, 0, 4));
        assertTrue(NfcProtocol.classicWritableBlock(1, 0, 4));
        assertFalse(NfcProtocol.ultralightWritablePages(3, 1));
        assertFalse(NfcProtocol.ultralightWritablePages(15, 2));
        assertTrue(NfcProtocol.ultralightWritablePages(4, 12));
    }
    private JSONObject profile() throws Exception {
        return new JSONObject().put("name", "Test").put("aids", new JSONArray().put("F001020304"))
            .put("rules", new JSONArray().put(new JSONObject().put("state", "initial").put("nextState", "selected")
                .put("requestHex", "00a4040005f001020304").put("responseHex", "9000")));
    }
    @Test public void hceMatchesExactBytesAndStateWithoutReplayingOtherCommands() throws Exception {
        JSONObject profile = NfcProtocol.profile(profile());
        JSONObject response = NfcProtocol.respond(profile, "initial", NfcProtocol.unhex("00a4040005f001020304", 32));
        assertTrue(response.getBoolean("matched"));
        assertEquals("selected", response.getString("nextState"));
        assertFalse(NfcProtocol.respond(profile, "selected", NfcProtocol.unhex("00a4040005f001020304", 32)).getBoolean("matched"));
        assertEquals("6a82", NfcProtocol.respond(profile, "initial", new byte[]{0, 1, 2, 3}).getString("responseHex"));
    }
    @Test public void profileRejectsAmbiguousRoutesAndDuplicateRules() throws Exception {
        JSONObject input = profile();
        input.getJSONArray("rules").put(input.getJSONArray("rules").getJSONObject(0));
        try { NfcProtocol.profile(input); fail(); } catch (IllegalArgumentException expected) { }
        input = profile(); input.put("aids", new JSONArray().put("F001020304*"));
        try { NfcProtocol.profile(input); fail(); } catch (IllegalArgumentException expected) { }
    }
}
