package io.droyagent.app;

import org.json.JSONObject;
import org.junit.Test;
import java.util.Map;
import static org.junit.Assert.*;

public final class NotificationChannelControlTest {
    private static JSONObject state(int importance, Object sound, boolean vibration, boolean bubbles) throws Exception {
        return new JSONObject().put("importance", importance).put("sound", sound)
                .put("vibration", vibration).put("bubbles", bubbles);
    }

    @Test public void quietRequiresAllNotificationEffectsDisabled() throws Exception {
        assertTrue(NotificationChannelControl.quiet(state(2, JSONObject.NULL, false, false)));
        assertTrue(NotificationChannelControl.quiet(state(1, JSONObject.NULL, false, false)));
        assertFalse(NotificationChannelControl.quiet(state(4, JSONObject.NULL, false, false)));
        assertFalse(NotificationChannelControl.quiet(state(2, "content://fictional/sound", false, false)));
        assertFalse(NotificationChannelControl.quiet(state(2, JSONObject.NULL, true, false)));
        assertFalse(NotificationChannelControl.quiet(state(2, JSONObject.NULL, false, true)));
    }

    @Test public void disabledChannelsAlreadyNeedNoSilencing() throws Exception {
        assertTrue(NotificationChannelControl.quiet(state(0, "content://fictional/sound", true, true)));
    }

    @Test public void noLongerManagedSettingsRemainPendingUntilRestored() throws Exception {
        JSONObject originals = new JSONObject()
                .put("example.first\nupdates", new JSONObject().put("packageName", "example.first"))
                .put("example.second\nmessages", new JSONObject().put("packageName", "example.second"));
        assertEquals(0, NotificationChannelControl.pendingRestoreCount(originals, new JSONObject(),
                Map.of("example.first", "managed", "example.second", "managed"), true));
        assertEquals(1, NotificationChannelControl.pendingRestoreCount(originals, new JSONObject(),
                Map.of("example.first", "allow", "example.second", "managed"), true));
        assertEquals(2, NotificationChannelControl.pendingRestoreCount(originals, new JSONObject(), Map.of(), false));
    }

    @Test public void legacyEnabledPackagesNeedRestoringEvenWhileStillSelected() throws Exception {
        JSONObject packages = new JSONObject().put("example.first", new JSONObject()
                .put("original", false).put("applied", true));
        assertEquals(1, NotificationChannelControl.pendingRestoreCount(new JSONObject(), packages,
                Map.of("example.first", "managed"), true));
        assertEquals(Boolean.FALSE, NotificationChannelControl.restoredPackageEnabled(true, packages.getJSONObject("example.first")));
        assertNull("Keep a later manual package choice", NotificationChannelControl.restoredPackageEnabled(false,
                packages.getJSONObject("example.first")));
    }

    @Test public void concurrentChangesAreDetectedBeforeApplyingASnapshot() throws Exception {
        JSONObject applied = state(2, JSONObject.NULL, false, false);
        assertTrue(NotificationChannelShell.sameState(applied, new JSONObject(applied.toString())));
        assertFalse(NotificationChannelShell.sameState(applied, state(4, JSONObject.NULL, false, false)));
        assertFalse(NotificationChannelShell.sameState(applied, state(2, "content://fictional/new-sound", false, false)));
        assertFalse(NotificationChannelShell.sameState(applied, state(2, JSONObject.NULL, true, false)));
        assertFalse(NotificationChannelShell.sameState(applied, state(2, JSONObject.NULL, false, true)));
    }

    @Test public void restorationKeepsManualSoundAndRestoresOtherOwnedFields() throws Exception {
        JSONObject original = state(4, "content://fictional/original", true, true);
        JSONObject applied = state(2, JSONObject.NULL, false, false);
        JSONObject current = state(2, "content://fictional/manual", false, false);
        JSONObject restored = NotificationChannelControl.restoredState(current, applied, original);
        assertEquals(4, restored.getInt("importance"));
        assertEquals("content://fictional/manual", restored.getString("sound"));
        assertTrue(restored.getBoolean("vibration")); assertTrue(restored.getBoolean("bubbles"));
    }

    @Test public void aLaterChannelBlockStaysBlockedWhileOtherOwnedFieldsAreRestored() throws Exception {
        JSONObject restored = NotificationChannelControl.restoredState(state(0, JSONObject.NULL, false, false),
                state(2, JSONObject.NULL, false, false), state(4, "content://fictional/original", true, true));
        assertEquals(0, restored.getInt("importance"));
        assertEquals("content://fictional/original", restored.getString("sound"));
        assertTrue(restored.getBoolean("vibration")); assertTrue(restored.getBoolean("bubbles"));
    }

    @Test public void unrelatedChannelMetadataIsPreservedByFieldScopedRestoration() throws Exception {
        JSONObject applied = state(2, JSONObject.NULL, false, false);
        JSONObject later = new JSONObject(applied.toString()).put("name", "New local name").put("badge", false);
        assertTrue(NotificationChannelShell.sameState(applied, later));
    }
}
