package io.droyagent.app.nativeui.media

import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class NativeCameraModelsTest {
    private fun camera() = NativeMediaSelection(UUID.randomUUID().toString(), "original-draft", NativeMediaKind.WORKBENCH_DRAFT, 10, true,
        workTarget = NativeWorkbenchMediaTarget("original-draft", null, "original-space"), camera = true, captureId = UUID.randomUUID().toString())

    @Test fun inFlightRegistryRecoveryRetainsOnlyTheOriginalCaptureAndDraft() {
        val original = camera().copy(launched = true)
        val recovered = NativeMediaSelection.restore(JSONObject(original.json().toString())).recoverForActivity(true)
        assertEquals(original.captureId, recovered.captureId)
        assertEquals(original.workTarget, recovered.workTarget)
        assertTrue(recovered.needsPicker)
        assertEquals(NativeMediaPhase.PICKING, recovered.phase)
        val gate = NativeMediaActivityTracker()
        gate.update("camera", recovered.phase, false)
        assertTrue(gate.isPickerOpen())
        assertEquals(NativeMediaPhase.INTERRUPTED, recovered.recoverForActivity(false).phase)
    }

    @Test fun aReturnedPrivatePhotoIsRetainedForExplicitImportRetryWithoutLaunchingAnotherCamera() {
        val ready = camera().copy(cameraReady = true, launched = true, phase = NativeMediaPhase.IMPORTING)
        val recovered = NativeMediaSelection.restore(ready.json()).recoverForActivity(true)
        assertEquals(NativeMediaPhase.INTERRUPTED, recovered.phase)
        assertTrue(recovered.cameraReady)
        assertFalse(recovered.needsPicker)
        assertEquals(ready.captureId, recovered.captureId)
        assertEquals(ready.ownerId, recovered.ownerId)
        assertTrue(recovered.uris.isEmpty()); assertTrue(recovered.receipts.isEmpty())
        assertThrows(IllegalArgumentException::class.java) {
            NativeMediaSelection.restore(ready.json().put("uris", JSONArray().put("content://untrusted-camera/returned")))
        }
    }

    @Test fun albumReceiptDoesNotInventAnAttachmentReceiptAndCannotBeBothSavedAndFailed() {
        val original = camera()
        val failedAlbum = NativeCameraAlbumResult(original.id, original.captureId!!, false, true)
        assertEquals(failedAlbum, NativeCameraAlbumResult.parse(failedAlbum.json()))
        assertTrue(original.receipts.isEmpty())
        assertThrows(IllegalArgumentException::class.java) { NativeCameraAlbumResult.parse(failedAlbum.json().put("saved", true)) }
        assertThrows(IllegalArgumentException::class.java) { NativeMediaSelection.restore(original.json().put("camera", false)) }
        assertThrows(IllegalArgumentException::class.java) { NativeMediaSelection.restore(original.json().put("captureId", "../../another")) }
    }
}
