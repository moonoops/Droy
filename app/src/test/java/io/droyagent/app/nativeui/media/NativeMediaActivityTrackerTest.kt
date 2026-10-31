package io.droyagent.app.nativeui.media

import org.junit.Assert.*
import org.junit.Test

class NativeMediaActivityTrackerTest {
    @Test fun uploadingAndDownloadingHoldRuntimeWithoutBecomingAFilePicker() {
        val tracker = NativeMediaActivityTracker()
        tracker.update("upload", NativeMediaPhase.UPLOADING, false)
        assertTrue(tracker.hasRuntimeWork()); assertFalse(tracker.isPickerOpen())
        tracker.remove("upload")
        tracker.update("download", null, true)
        assertTrue(tracker.hasRuntimeWork()); assertFalse(tracker.isPickerOpen())
        tracker.remove("download")
        assertFalse(tracker.hasRuntimeWork())
    }

    @Test fun oneInstanceCannotReleaseAnotherInstancesHumanPickerGate() {
        val tracker = NativeMediaActivityTracker()
        tracker.update("first", NativeMediaPhase.PICKING, false)
        tracker.update("second", NativeMediaPhase.PICKING, false)
        tracker.remove("first")
        assertTrue(tracker.isPickerOpen())
        tracker.update("second", NativeMediaPhase.UPLOADING, false)
        assertFalse(tracker.isPickerOpen()); assertTrue(tracker.hasRuntimeWork())
        tracker.remove("second")
        assertFalse(tracker.hasRuntimeWork())
        assertFalse(tracker.remove("second"))
    }

    @Test fun interruptedAndRetiredSelectionsReleaseBothGates() {
        val tracker = NativeMediaActivityTracker()
        tracker.update("selection", NativeMediaPhase.PICKING, false)
        tracker.update("selection", NativeMediaPhase.INTERRUPTED, false)
        assertFalse(tracker.isPickerOpen()); assertFalse(tracker.hasRuntimeWork())
        tracker.update("selection", NativeMediaPhase.PICKING, false)
        tracker.update("selection", null, false)
        assertFalse(tracker.isPickerOpen()); assertFalse(tracker.hasRuntimeWork())
    }
}
