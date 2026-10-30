package io.droyagent.app.nativeui.companion

import io.droyagent.app.nativeui.companion.data.*
import io.droyagent.app.nativeui.data.NativeApiException
import io.droyagent.app.nativeui.media.NativeLocalAttachment
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class NativeCompanionLocalMediaTest {
    private fun local(name: String) = NativeLocalAttachment(UUID.randomUUID().toString(), UUID.randomUUID().toString(), name, "image/png", 12)
    private fun uploaded(item: NativeLocalAttachment) = NativeCompanionAttachment("up-${item.id}", item.name, item.mediaType, item.bytes,
        "/droy/companions/kaka/attachments/up-${item.id}")

    @Test fun choosingAFileIsARecoverableLocalDraftWithoutAHostedUrlOrSendReceipt() {
        val locals = mutableListOf(local("one.png"))
        val draft = NativeCompanionDraft(localAttachments = locals)
        assertTrue(draft.canSend)
        val pending = NativeCompanionSubmission.prepare("kaka", draft, "2026-10-29T00:00:00Z")
        locals.clear()
        assertEquals(1, pending.draft.localAttachments.size)
        assertFalse(pending.payloadReady)
        assertTrue(pending.uploadReceipts.isEmpty())
        val restored = NativeCompanionSubmission.restore(JSONObject(pending.json().toString()))
        assertEquals(pending.clientId, restored.clientId)
        assertEquals(pending.draft, restored.draft)
        assertFalse(restored.payloadReady)
        assertEquals("image", restored.optimistic().type)
        assertFalse(JSONObject(restored.payload).getJSONArray("attachments").length() > 0)
    }

    @Test fun retryUsesTheOriginalSnapshotAndDurablePerFileReceiptsBeforeFreezingThePayload() = runBlocking {
        val first = local("one.png"); val second = local("two.png")
        val original = NativeCompanionSubmission.prepare("kaka", NativeCompanionDraft("Original", localAttachments = listOf(first, second)), "2026-10-29T00:00:00Z")
        var journal = original.json()
        val uploadCalls = mutableListOf<String>()
        try {
            materializeCompanionSubmission(original, { journal = JSONObject(it.json().toString()) }) { item ->
                uploadCalls += item.id
                if (item == second) throw NativeApiException("network_error")
                uploaded(item)
            }
            fail("An unconfirmed upload must prevent sending")
        } catch (error: NativeApiException) { assertEquals("network_error", error.code) }
        val interrupted = NativeCompanionSubmission.restore(journal)
        assertEquals(setOf(first.id), interrupted.uploadReceipts.keys)
        assertFalse(interrupted.payloadReady)
        val ready = materializeCompanionSubmission(interrupted, { journal = JSONObject(it.json().toString()) }) { item ->
            uploadCalls += item.id; uploaded(item)
        }
        assertEquals(listOf(first.id, second.id, second.id), uploadCalls)
        assertEquals(original.clientId, ready.clientId)
        assertEquals(original.draft, ready.draft)
        assertTrue(ready.payloadReady)
        val payload = JSONObject(ready.payload)
        assertEquals("Original", payload.getString("text"))
        assertEquals(listOf(uploaded(first).id, uploaded(second).id), (0 until 2).map { payload.getJSONArray("attachments").getJSONObject(it).getString("id") })
        assertFalse(payload.toString().contains("url"))
        val restored = NativeCompanionSubmission.restore(journal)
        assertEquals(ready.payload, restored.payload)
        materializeCompanionSubmission(restored, { fail("already frozen") }) { fail("already uploaded"); uploaded(it) }
        assertTrue(restored.payloadReady)
    }

    @Test fun normalizedCameraImageCanSendWithoutTextAndRetryItsSavedUpload() = runBlocking {
        val image = local("Photo-20270101-123030.jpg").copy(mediaType = "image/jpeg", bytes = 3500000)
        val original = NativeCompanionSubmission.prepare("kaka", NativeCompanionDraft(localAttachments = listOf(image)), "now")
        val normalized = uploaded(image).copy(mediaType = "image/png", bytes = 120000)
        val ready = materializeCompanionSubmission(original, {}) { normalized }
        assertEquals("", JSONObject(ready.payload).getString("text"))
        assertEquals(normalized.id, JSONObject(ready.payload).getJSONArray("attachments").getJSONObject(0).getString("id"))
        val restored = NativeCompanionSubmission.restore(ready.json())
        assertEquals(normalized, restored.uploadReceipts[image.id])
        assertEquals(restored, materializeCompanionSubmission(restored, { fail("Already saved") }) { fail("Must not upload twice"); normalized })
    }

    @Test fun anotherOwnersUploadReceiptOrAChangedFrozenPayloadCannotBeRestored() {
        val item = local("one.png")
        val record = NativeCompanionSubmission.prepare("kaka", NativeCompanionDraft(localAttachments = listOf(item)), "now")
        assertEquals("COMPANION_ATTACHMENT_SCOPE", assertThrows(NativeApiException::class.java) {
            record.withUpload(item, uploaded(item).copy(url = "/droy/companions/other/attachments/up-${item.id}"))
        }.code)
        val frozen = record.withUpload(item, uploaded(item)).freezePayload()
        val broken = frozen.json().put("payload", JSONObject(frozen.payload).put("text", "changed").toString())
        assertThrows(IllegalArgumentException::class.java) { NativeCompanionSubmission.restore(broken) }
        assertFalse(NativeCompanionJson.sameSubmissionContent(record.draft, record.draft.copy(localAttachments = emptyList())))
    }
}
