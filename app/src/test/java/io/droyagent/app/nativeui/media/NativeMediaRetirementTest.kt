package io.droyagent.app.nativeui.media

import io.droyagent.app.nativeui.data.NativeApiException
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class NativeMediaRetirementTest {
    @Test fun removingADeliveredDraftItemCannotDeleteAnImportWhileItsOldMediaReceiptCanStillRecover() = runBlocking {
        val id = UUID.randomUUID().toString()
        val selection = NativeMediaSelection(id, "kaka", NativeMediaKind.ATTACHMENTS, 1, false,
            NativeMediaPhase.IMPORTING, listOf("content://fixture/file"))
        var journal = JSONObject().put("selection", selection.json()).toString()
        val gate = NativeMediaRetirementGate()
        var removed = false
        assertFalse(gate.retire(id, { journal }) { removed = true })
        assertFalse(removed)
        val committing = CompletableDeferred<Unit>()
        val finishCommit = CompletableDeferred<Boolean>()
        val writer = launch(start = CoroutineStart.UNDISPATCHED) {
            try { gate.write { journal = JSONObject().put("selection", JSONObject.NULL).toString(); committing.complete(Unit); finishCommit.await() } }
            catch (error: NativeApiException) { assertEquals("storage_error", error.code) }
        }
        committing.await()
        var retired: Boolean? = null
        val retire = launch(start = CoroutineStart.UNDISPATCHED) {
            try { retired = gate.retire(id, { journal }) { removed = true } }
            catch (error: NativeApiException) { assertEquals("storage_error", error.code) }
        }
        assertNull(retired); assertFalse(removed)
        finishCommit.complete(false)
        writer.join(); retire.join()
        assertNull(retired); assertFalse(removed)
        gate.write { true }
        assertTrue(gate.retire(id, { journal }) { removed = true })
        assertTrue(removed)
    }

    @Test fun cameraRetentionUsesItsFrozenCaptureRatherThanTheVisibleDraftOrOperationId() {
        val operation = UUID.randomUUID().toString(); val capture = UUID.randomUUID().toString()
        val selection = NativeMediaSelection(operation, "original", NativeMediaKind.ATTACHMENTS, 1, true,
            camera = true, captureId = capture)
        val raw = JSONObject().put("selection", selection.json()).toString()
        assertTrue(NativeMediaRetirementGate.references(raw, capture))
        assertFalse(NativeMediaRetirementGate.references(raw, operation))
        assertThrows(NativeApiException::class.java) { NativeMediaRetirementGate.references("broken", capture) }
    }
}
