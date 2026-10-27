package io.droyagent.app;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class HardwareToolStateTest {
    @Test public void localProfilesRemainManageableWithoutAnEnabledNfcRadio() throws Exception {
        JSONObject tool = new JSONObject().put("action", "nfc_emulation").put("name", "android_nfc_emulation")
            .put("permissions", new JSONArray()).put("parameters", new JSONObject().put("operation", new JSONObject()
                .put("enum", new JSONArray(new String[]{"get", "save", "enable", "disable"}))))
            .put("operationPermissions", new JSONObject().put("get", new JSONArray().put("nfcEmulation"))
                .put("save", new JSONArray().put("nfcEmulation")).put("enable", new JSONArray().put("nfcEmulation"))
                .put("disable", new JSONArray()));
        JSONObject hardware = new JSONObject().put("nfc_emulation", new JSONObject().put("supported", false))
            .put("permissions", new JSONObject().put("nfcEnabled", false));
        JSONObject state = HardwareToolState.describe(tool, new JSONObject().put("nfcEmulation", true), false, hardware).getJSONObject("operations");
        assertTrue(state.getJSONObject("save").getBoolean("available"));
        assertTrue(state.getJSONObject("get").getBoolean("available"));
        assertFalse(state.getJSONObject("enable").getBoolean("available"));
        assertTrue(state.getJSONObject("disable").getBoolean("available"));
        JSONObject paused = HardwareToolState.describe(tool, new JSONObject(), true, hardware).getJSONObject("operations");
        assertFalse(paused.getJSONObject("save").getBoolean("available"));
        assertTrue(paused.getJSONObject("disable").getBoolean("available"));
    }

    @Test public void rawNfcCommandsRequireWriteWhileCleanupSurvivesRevocation() throws Exception {
        JSONObject tool = new JSONObject().put("action", "nfc").put("name", "android_nfc")
            .put("permissions", new JSONArray()).put("parameters", new JSONObject().put("operation", new JSONObject()
                .put("enum", new JSONArray(new String[]{"read", "transceive", "close"}))))
            .put("operationPermissions", new JSONObject().put("read", new JSONArray().put("nfcRead"))
                .put("transceive", new JSONArray().put("nfcRead").put("nfcWrite")).put("close", new JSONArray()));
        JSONObject hardware = new JSONObject().put("nfc", new JSONObject().put("supported", true))
            .put("permissions", new JSONObject().put("nfcEnabled", true));
        JSONObject operations = HardwareToolState.describe(tool, new JSONObject().put("nfcRead", true), false, hardware).getJSONObject("operations");
        assertTrue(operations.getJSONObject("read").getBoolean("available"));
        assertFalse(operations.getJSONObject("transceive").getBoolean("available"));
        assertTrue(HardwareToolState.describe(tool, new JSONObject(), true, hardware)
            .getJSONObject("operations").getJSONObject("close").getBoolean("available"));
    }
}
