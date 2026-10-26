package io.droyagent.androiduse;

import static org.junit.Assert.*;
import org.junit.Test;

public class DeviceSettingsToolsTest {
    @Test public void restoresOnlyIfCurrentStillMatchesDroysWrite() {
        assertTrue(DeviceSettingsTools.canRestore("available", 3, 3));
        assertFalse(DeviceSettingsTools.canRestore("available", 4, 3));
        for (String state : new String[]{"pending", "unconfirmed", "not_applied", "restoring", "restore_unconfirmed", "restored", "unknown"})
            assertFalse(state, DeviceSettingsTools.canRestore(state, 3, 3));
    }

    @Test public void cancellationAfterPendingRecordPreventsWriteAndCannotLaterRestoreUserValue() {
        java.util.concurrent.atomic.AtomicReference<String> state = new java.util.concurrent.atomic.AtomicReference<>("pending");
        java.util.concurrent.atomic.AtomicBoolean wrote = new java.util.concurrent.atomic.AtomicBoolean();
        assertThrows(InterruptedException.class, () -> DeviceSettingsTools.writeTracked(
                () -> { throw new InterruptedException("stopped after saving record"); },
                () -> wrote.set(true), state::set));
        assertFalse(wrote.get());
        assertEquals("not_applied", state.get());
        assertFalse("Even if the user later chooses Droy's intended value", DeviceSettingsTools.canRestore(state.get(), 3, 3));
    }

    @Test public void interruptedRecordSaveRemainsIneligibleAndWriteFailureIsUnconfirmed() {
        java.util.concurrent.atomic.AtomicReference<String> state = new java.util.concurrent.atomic.AtomicReference<>("pending");
        assertThrows(InterruptedException.class, () -> DeviceSettingsTools.writeTracked(
                () -> { throw new InterruptedException("stopped"); }, () -> fail("must not write"),
                ignored -> { throw new java.io.IOException("could not save cancellation"); }));
        assertFalse(DeviceSettingsTools.canRestore(state.get(), 3, 3));
        ActionException failure = assertThrows(ActionException.class, () -> DeviceSettingsTools.writeTracked(
                () -> {}, () -> { throw new SecurityException("write rejected or uncertain"); }, state::set));
        assertEquals("setting_unconfirmed", failure.getCode());
        assertEquals("unconfirmed", state.get());
        assertFalse(DeviceSettingsTools.canRestore(state.get(), 3, 3));
    }

    @Test public void limitsValuesToReportedDeviceRange() throws Exception {
        DeviceSettingsTools.validateRange(0, 0, 15);
        DeviceSettingsTools.validateRange(15, 0, 15);
        assertThrows(ActionException.class, () -> DeviceSettingsTools.validateRange(16, 0, 15));
        assertThrows(ActionException.class, () -> DeviceSettingsTools.validateRange(-1, 0, 15));
    }

    @Test public void rejectsArbitraryAndroidSettingNames() throws Exception {
        assertEquals("media_volume", DeviceSettingsTools.name("media_volume"));
        assertEquals("brightness", DeviceSettingsTools.name("brightness"));
        assertThrows(ActionException.class, () -> DeviceSettingsTools.name("adb_enabled"));
        assertThrows(ActionException.class, () -> DeviceSettingsTools.name(null));
    }
}
