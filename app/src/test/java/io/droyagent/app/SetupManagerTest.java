package io.droyagent.app;

import java.util.Set;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public final class SetupManagerTest {
    private static final Set<String> CAPABILITIES = Set.of("allowObserve", "allowControl", "allowRoot");

    @Test public void acceptsOnlyTypedChoicesAndTrimsIdentity() throws Exception {
        JSONObject configured = SetupManager.validatedUpdate(new JSONObject()
            .put("source", "usb").put("mode", "custom").put("step", 4)
            .put("companionName", "  小黑  ").put("userAddress", "  阿明  ")
            .put("personality", "沉稳\n简洁")
            .put("capabilities", new JSONObject().put("allowObserve", true).put("allowRoot", false)), CAPABILITIES);
        assertEquals("小黑", configured.getString("companionName"));
        assertEquals("阿明", configured.getString("userAddress"));
        assertEquals("沉稳\n简洁", configured.getString("personality"));
        assertFalse(configured.getJSONObject("capabilities").getBoolean("allowRoot"));
    }

    @Test public void acceptsAllPermissionsMode() throws Exception {
        assertEquals("all", SetupManager.validatedUpdate(new JSONObject().put("mode", "all"), CAPABILITIES).getString("mode"));
    }

    @Test public void rejectsSecretsUnknownKeysAndForgedReceipts() throws Exception {
        for (String key : new String[]{"apiKey", "token", "completed", "modelVerification", "deviceVerification", "configFingerprint", "modeSelected"}) {
            assertThrows(key, IllegalArgumentException.class, () ->
                SetupManager.validatedUpdate(new JSONObject().put(key, "fictional-value"), CAPABILITIES));
        }
    }

    @Test public void rejectsUnknownCapabilitiesAndStringBooleans() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> SetupManager.validatedUpdate(new JSONObject()
            .put("capabilities", new JSONObject().put("allowEverything", true)), CAPABILITIES));
        assertThrows(IllegalArgumentException.class, () -> SetupManager.validatedUpdate(new JSONObject()
            .put("capabilities", new JSONObject().put("allowObserve", "true")), CAPABILITIES));
        assertThrows(IllegalArgumentException.class, () -> SetupManager.validatedUpdate(new JSONObject()
            .put("capabilities", "all"), CAPABILITIES));
        assertThrows(IllegalArgumentException.class, () -> SetupManager.validatedUpdate(new JSONObject()
            .put("mode", "dialogue").put("capabilities", new JSONObject().put("allowObserve", true)), CAPABILITIES));
    }

    @Test public void rejectsInvalidStepsModesAndControlCharacters() throws Exception {
        for (Object value : new Object[]{0, 7, 1.5, "2", true, JSONObject.NULL})
            assertThrows(IllegalArgumentException.class, () ->
                SetupManager.validatedUpdate(new JSONObject().put("step", value), CAPABILITIES));
        for (String value : new String[]{"", "everything", "root", "Assistant"})
            assertThrows(IllegalArgumentException.class, () ->
                SetupManager.validatedUpdate(new JSONObject().put("mode", value), CAPABILITIES));
        for (String name : new String[]{"", "name\nnext", "name\u0000", "猫".repeat(41)})
            assertThrows(IllegalArgumentException.class, () ->
                SetupManager.validatedUpdate(new JSONObject().put("companionName", name), CAPABILITIES));
        assertThrows(IllegalArgumentException.class, () ->
            SetupManager.validatedUpdate(new JSONObject().put("personality", "喵".repeat(1001)), CAPABILITIES));
    }

    @Test public void limitsNamesByUnicodeCodePoints() throws Exception {
        String name = "🐈".repeat(40);
        assertEquals(name, SetupManager.validatedUpdate(new JSONObject().put("companionName", name), CAPABILITIES).getString("companionName"));
    }

    @Test public void legacyDataRequiresActualSavedState() {
        assertFalse(SetupManager.hasLegacyState(true, false));
        assertTrue(SetupManager.hasLegacyState(false, false));
        assertTrue(SetupManager.hasLegacyState(true, true));
    }

    @Test public void fingerprintsRequireFixedSha256Format() {
        assertTrue(SetupManager.validFingerprint("a".repeat(64)));
        for (String value : new String[]{"", "a".repeat(63), "A".repeat(64), "g".repeat(64), "fictional-key"})
            assertFalse(SetupManager.validFingerprint(value));
        assertFalse(SetupManager.validFingerprint(null));
    }

    @Test public void rootThenPracticePreservesBothIndependentProofs() throws Exception {
        JSONObject root = SetupManager.mergeRootVerification(new JSONObject(), "a", true, 10);
        assertFalse(root.getBoolean("passed"));
        JSONObject practice = SetupManager.mergeDeviceVerification(root, "a", new JSONObject()
            .put("passed", true).put("channel", "accessibility"), 20);
        assertTrue(practice.getBoolean("passed"));
        assertTrue(practice.getBoolean("rootVerified"));
        assertEquals(10, practice.getLong("rootCheckedAt"));
        assertEquals("accessibility", practice.getString("channel"));
    }

    @Test public void practiceThenRootPreservesPracticeAndRootFailureIsRecorded() throws Exception {
        JSONObject practice = SetupManager.mergeDeviceVerification(new JSONObject(), "a", new JSONObject()
            .put("passed", true).put("channel", "accessibility"), 10);
        JSONObject root = SetupManager.mergeRootVerification(practice, "a", true, 20);
        assertTrue(root.getBoolean("passed"));
        assertTrue(root.getBoolean("rootVerified"));
        assertEquals("accessibility", root.getString("channel"));
        JSONObject denied = SetupManager.mergeRootVerification(root, "a", false, 30);
        assertTrue(denied.getBoolean("passed"));
        assertFalse(denied.getBoolean("rootVerified"));
    }

    @Test public void changedCapabilitiesCannotReuseOldRootOrPracticeProof() throws Exception {
        JSONObject previous = new JSONObject().put("choicesFingerprint", "a").put("passed", true)
            .put("channel", "accessibility").put("rootVerified", true);
        JSONObject changedPractice = SetupManager.mergeDeviceVerification(previous, "b", new JSONObject()
            .put("passed", true).put("channel", "accessibility"), 20);
        assertFalse(changedPractice.getBoolean("rootVerified"));
        JSONObject changedRoot = SetupManager.mergeRootVerification(previous, "b", true, 20);
        assertTrue(changedRoot.getBoolean("rootVerified"));
        assertFalse(changedRoot.getBoolean("passed"));
        assertEquals("none", changedRoot.getString("channel"));
        JSONObject explicitFailure = SetupManager.mergeDeviceVerification(previous, "a", new JSONObject()
            .put("passed", false).put("rootVerified", false), 20);
        assertFalse(explicitFailure.getBoolean("rootVerified"));
    }
}
