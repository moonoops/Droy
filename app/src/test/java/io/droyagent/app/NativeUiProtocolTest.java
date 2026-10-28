package io.droyagent.app;

import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class NativeUiProtocolTest {
    @Test public void keyboardDismissalAcceptsOnlyAnEmptyArgumentObject() throws Exception {
        JSONObject request = NativeUiProtocol.parse("{\"id\":\"keyboard\",\"action\":\"hideKeyboard\",\"args\":{}}");
        NativeUiProtocol.noArguments(request.getJSONObject("args"));
        assertThrows(IllegalArgumentException.class,
            () -> NativeUiProtocol.noArguments(new JSONObject().put("target", "other-window")));
    }

    @Test public void memoryArchiveSaveBuildsOnlyTheCurrentSessionDownloadUrl() throws Exception {
        String id="a".repeat(64);
        assertEquals("http://127.0.0.1:3456/droy/memory/archive/download/"+id,NativeUiProtocol.memoryArchiveDownload(new JSONObject().put("archiveId",id),3456));
        for(Object value:new Object[]{"../private","https://example.test","A".repeat(64),"a".repeat(63),true,JSONObject.NULL})
            assertThrows(IllegalArgumentException.class,()->NativeUiProtocol.memoryArchiveDownload(new JSONObject().put("archiveId",value),3456));
        assertThrows(IllegalArgumentException.class,()->NativeUiProtocol.memoryArchiveDownload(new JSONObject().put("archiveId",id).put("url","http://example.test"),3456));
        assertThrows(IllegalArgumentException.class,()->NativeUiProtocol.memoryArchiveDownload(new JSONObject().put("archiveId",id),0));
    }
    @Test public void onlyCurrentTopLevelLoopbackOriginIsTrusted() {
        assertTrue(NativeUiProtocol.trustedOrigin("http://127.0.0.1:3456", 3456, true));
        assertFalse(NativeUiProtocol.trustedOrigin("http://127.0.0.1:3456", 3456, false));
        assertFalse(NativeUiProtocol.trustedOrigin("http://127.0.0.1:3457", 3456, true));
        assertFalse(NativeUiProtocol.trustedOrigin("http://localhost:3456", 3456, true));
        assertFalse(NativeUiProtocol.trustedOrigin("https://127.0.0.1:3456", 3456, true));
        assertFalse(NativeUiProtocol.trustedOrigin("http://127.0.0.1:3456@evil.test", 3456, true));
        assertFalse(NativeUiProtocol.trustedOrigin("http://user@127.0.0.1:3456", 3456, true));
        assertFalse(NativeUiProtocol.trustedOrigin("http://127.0.0.1:3456/?x=1", 3456, true));
        assertFalse(NativeUiProtocol.trustedOrigin("file:///android_asset/mobile.html", 3456, true));
    }

    @Test public void malformedRequestsCannotBecomePreferenceChanges() throws Exception {
        assertEquals("getState", NativeUiProtocol.parse("{\"id\":\"one\",\"action\":\"getState\",\"args\":{}}").getString("action"));
        for (String payload : new String[]{"[]", "{}", "{\"id\":1,\"action\":\"getState\"}",
            "{\"id\":\"one\",\"action\":\"getState\",\"args\":false}"}) {
            try { NativeUiProtocol.parse(payload); fail("Accepted invalid request: " + payload); }
            catch (Exception expected) { }
        }
        assertFalse(NativeUiProtocol.booleanArgument(new JSONObject("{\"value\":false}"), "value"));
        try { NativeUiProtocol.booleanArgument(new JSONObject("{\"value\":\"false\"}"), "value"); fail("String coerced to boolean"); }
        catch (IllegalArgumentException expected) { }
    }

    @Test public void microphoneGrantCannotIncludeCameraOrForeignOrigins() {
        String microphone = "android.webkit.resource.AUDIO_CAPTURE";
        assertTrue(NativeUiProtocol.trustedMicrophoneRequest("http://127.0.0.1:3456", 3456, new String[]{microphone}));
        assertFalse(NativeUiProtocol.trustedMicrophoneRequest("http://127.0.0.1:3457", 3456, new String[]{microphone}));
        assertFalse(NativeUiProtocol.trustedMicrophoneRequest("https://example.test", 3456, new String[]{microphone}));
        assertFalse(NativeUiProtocol.trustedMicrophoneRequest("http://127.0.0.1:3456", 3456,
            new String[]{microphone, "android.webkit.resource.VIDEO_CAPTURE"}));
        assertFalse(NativeUiProtocol.trustedMicrophoneRequest("http://127.0.0.1:3456", 3456, new String[]{}));
    }

    @Test public void cameraGrantIsSeparateFromTheNativeVoiceMicrophone() {
        String camera = "android.webkit.resource.VIDEO_CAPTURE";
        assertTrue(NativeUiProtocol.trustedCameraRequest("http://127.0.0.1:3456", 3456, new String[]{camera}));
        assertFalse(NativeUiProtocol.trustedCameraRequest("http://127.0.0.1:3457", 3456, new String[]{camera}));
        assertFalse(NativeUiProtocol.trustedCameraRequest("https://example.test", 3456, new String[]{camera}));
        assertFalse(NativeUiProtocol.trustedCameraRequest("http://127.0.0.1:3456", 3456,
            new String[]{camera, "android.webkit.resource.AUDIO_CAPTURE"}));
    }

    @Test public void petModesAcceptOnlyTheThreeExplicitValues() throws Exception {
        for (String mode : new String[]{"always", "adaptive", "off"})
            assertEquals(mode, NativeUiProtocol.petDisplayArgument(new JSONObject().put("value", mode)));
        for (Object value : new Object[]{true, false, "true", " auto", "auto", "ALWAYS", 1, JSONObject.NULL})
            assertThrows(IllegalArgumentException.class, () -> NativeUiProtocol.petDisplayArgument(new JSONObject().put("value", value)));
        assertThrows(org.json.JSONException.class, () -> NativeUiProtocol.petDisplayArgument(new JSONObject()));
    }

    @Test public void themeBackgroundAcceptsOnlyOpaqueRgbHexStrings() throws Exception {
        assertEquals("#FFFFFF", NativeUiProtocol.backgroundColorArgument(new JSONObject().put("backgroundColor", "#ffffff")));
        assertEquals("#151517", NativeUiProtocol.backgroundColorArgument(new JSONObject().put("backgroundColor", "#151517")));
        for (Object value : new Object[]{"white", "#fff", "#15151700", "rgb(21,21,23)", "#GG1517", " #151517", "#151517 ", 151517, false, JSONObject.NULL}) {
            JSONObject args = new JSONObject().put("backgroundColor", value);
            assertThrows("Accepted an invalid theme background", IllegalArgumentException.class,
                    () -> NativeUiProtocol.backgroundColorArgument(args));
        }
        assertThrows("Missing background must not overwrite the current theme", org.json.JSONException.class,
                () -> NativeUiProtocol.backgroundColorArgument(new JSONObject()));
    }
}
