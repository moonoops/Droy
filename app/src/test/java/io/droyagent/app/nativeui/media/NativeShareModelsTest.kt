package io.droyagent.app.nativeui.media

import java.util.UUID
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class NativeShareModelsTest {
    private fun item(importId: String) = NativeLocalAttachment(importId, UUID.randomUUID().toString(), "fixture.txt", "text/plain", 4)

    @Test fun acceptedConsumptionWaitsForTheOriginalSubmissionWithoutANewStateNotification() = runBlocking {
        val state = NativeSharePreviewState(id(), id(), attempted = true, accepted = true)
        val originalSubmission = Job()
        var consumed: NativeSharePreviewState? = null
        val consumer = launch(start = CoroutineStart.UNDISPATCHED) {
            consumed = awaitAcceptedShare(state.requestId!!, originalSubmission) { state }
        }
        assertNull(consumed)
        originalSubmission.complete()
        consumer.join()
        assertEquals(state, consumed)
        assertNull(awaitAcceptedShare(id(), null) { state })
        assertNull(awaitAcceptedShare(state.requestId!!, null) { NativeSharePreviewState() })
    }

    @Test fun importFailureAndDurableDiscardCannotBecomeASendOnRestore() {
        val first = id(); val second = id()
        val base = NativeSharePreviewState(first, id(), true, "fixture", emptyList(), NativeShareDestination(companionId = "kaka"))
        val failed = NativeSharePreviewState.parse(base.copy(importFailed = true).json())
        assertFalse(failed.canSend); assertFalse(failed.editable)
        assertEquals("native_share_import_failed", failed.error)
        val discarded = NativeSharePreviewState.parse(base.copy(discarded = true, pendingImportIds = listOf(first, second)).json())
        assertTrue(discarded.discarded); assertFalse(discarded.canSend)
        assertTrue(discarded.pendingImportIds.isEmpty()) // The FIFO has one independent durable source.
        assertEquals(base.requestId, discarded.requestId)
    }

    private fun id() = UUID.randomUUID().toString()

    @Test fun restoredShareKeepsItsOriginalTargetAndStableRequestWithoutStartingASubmission() {
        val id = UUID.randomUUID().toString()
        val target = NativeWorkbenchMediaTarget("original-draft", null, "original-space")
        val original = NativeSharePreviewState(id, UUID.randomUUID().toString(), true, "fixture", listOf(item(id)),
            NativeShareDestination(workbench = target), attempted = true)
        val restored = NativeSharePreviewState.parse(JSONObject(original.json().toString()))
        assertEquals(original.requestId, restored.requestId)
        assertEquals(target, restored.destination?.workbench)
        assertFalse(restored.sending)
        assertFalse(restored.editable)
        assertFalse(restored.accepted)
        assertEquals("native_share_interrupted", restored.error)
        assertTrue(restored.canSend)
    }

    @Test fun merelyOpeningAPreviewNeverSuppliesAnAcceptanceOrCreationProof() {
        val id = UUID.randomUUID().toString()
        val initial = NativeSharePreviewState(id, UUID.randomUUID().toString(), true, "fixture", emptyList(),
            NativeShareDestination(companionId = "kaka"))
        val restored = NativeSharePreviewState.parse(initial.json())
        assertTrue(restored.editable)
        assertFalse(restored.attempted)
        assertFalse(restored.accepted)
        assertNull(restored.resultSessionId)
        assertEquals("kaka", restored.destination?.companionId)
    }

    @Test fun crossImportAttachmentsAndAmbiguousTargetsAreRejected() {
        val id = UUID.randomUUID().toString()
        val initial = NativeSharePreviewState(id, UUID.randomUUID().toString(), true, "fixture", listOf(item(UUID.randomUUID().toString())),
            NativeShareDestination(companionId = "kaka"))
        assertThrows(IllegalArgumentException::class.java) { NativeSharePreviewState.parse(initial.json()) }
        assertThrows(IllegalArgumentException::class.java) {
            NativeShareDestination(NativeWorkbenchMediaTarget("draft", null, null), "kaka")
        }
    }

    @Test fun workbenchPickerRecoveryCannotBecomeTheCurrentlyVisibleCompanion() {
        val target = NativeWorkbenchMediaTarget("original-draft", null, "space-a")
        val pick = NativeMediaSelection("operation", target.draftId, NativeMediaKind.WORKBENCH_DRAFT, 1, false,
            NativeMediaPhase.IMPORTING, listOf("content://fixture.documents/file"), workTarget = target)
        val restored = NativeMediaSelection.restore(pick.json()).recoverForActivity(true)
        assertEquals(NativeMediaPhase.INTERRUPTED, restored.phase)
        assertEquals(target, restored.workTarget)
        assertEquals("original-draft", restored.ownerId)
        assertTrue(restored.receipts.isEmpty())
        assertThrows(IllegalArgumentException::class.java) {
            NativeMediaSelection.restore(pick.json().put("ownerId", "kaka"))
        }
    }
}
