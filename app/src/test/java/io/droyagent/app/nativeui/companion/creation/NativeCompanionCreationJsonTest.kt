package io.droyagent.app.nativeui.companion.creation

import io.droyagent.app.nativeui.companion.data.NativeCompanionAttachment
import io.droyagent.app.nativeui.companion.data.NativeCompanionSettings
import io.droyagent.app.nativeui.data.NativeApiException
import io.droyagent.app.nativeui.data.NativeModel
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

internal object NativeCreationFixtures {
    val template = NativeCreationTemplate("kaka", "Fictional Kaka", "Fictional Kaka 1", "/droy/companions/assets/kaka.png", "Fictional persona", "Fictional behavior", "Fictional reply style",
        NativeCompanionSettings(false, true, true, "{\"enabled\":true,\"start\":\"19:35\",\"end\":\"07:25\"}"), "{\"type\":\"default\"}")
    const val nonce = "11111111-1111-4111-8111-111111111111"
    const val imageId = "22222222-2222-4222-8222-222222222222"
    const val itemId = "33333333-3333-4333-8333-333333333333"
    fun upload(draft: String = "draft-fixture", id: String = "upload-fixture") = NativeCompanionAttachment(id, "fictional.png", "image/png", 20, "/droy/companions/creation-drafts/$draft/attachments/$id")
    fun journal() = NativeCreationJournal(nonce, NativeCreationEditor(template, name = "Fictional chosen name"), "draft-fixture")
    fun templateValue() = JSONObject().put("templateId", template.id).put("name", template.name).put("suggestedName", template.suggestedName).put("avatarUrl", template.avatarUrl)
        .put("personality", template.personality).put("behaviorStyle", template.behaviorStyle).put("replyStyle", template.replyStyle)
        .put("settings", JSONObject().put("proactiveEnabled", false).put("memoryEnabled", true).put("allowProfanity", true).put("quietHours", JSONObject(template.settings.quietHoursJson!!)))
        .put("chatBackground", JSONObject(template.backgroundJson))
}

class NativeCompanionCreationJsonTest {
    private fun malformed(block: () -> Unit) {
        try { block(); fail("Expected invalid response") } catch (error: NativeApiException) { assertEquals("malformed_response", error.code) }
    }
    @Test fun templatesPreserveRealDefaultsAndSuggestedNames() {
        val row = NativeCreationJson.templates(JSONObject().put("templates", JSONArray().put(NativeCreationFixtures.templateValue()))).single()
        assertEquals("Fictional Kaka 1", row.suggestedName)
        assertEquals("19:35", JSONObject(row.settings.quietHoursJson!!).getString("start"))
        assertEquals("07:25", JSONObject(row.settings.quietHoursJson).getString("end"))
        malformed { NativeCreationJson.templates(JSONObject().put("templates", JSONArray().put(NativeCreationFixtures.templateValue().apply { getJSONObject("settings").remove("quietHours") }))) }
    }
    @Test fun aCreationIsOneCompletePayloadWithoutProfilePatchOrModelMetadata() {
        val journal = NativeCreationFixtures.journal().let { it.copy(editor = it.editor.copy(personalityOverride = "Fictional edited persona",
            model = NativeModel("provider", "model-id", "Visible model name", "Visible provider", "high"), avatarUpload = NativeCreationFixtures.upload())) }
        val value = JSONObject(NativeCreationJson.payload(journal))
        assertEquals(setOf("requestId", "draftId", "templateId", "name", "personalityOverride", "model", "settings", "chatBackground", "avatarUploadId"), value.keys().asSequence().toSet())
        assertEquals(setOf("provider", "model", "reasoningEffort"), value.getJSONObject("model").keys().asSequence().toSet())
        assertEquals("Fictional edited persona", value.getString("personalityOverride")); assertEquals("upload-fixture", value.getString("avatarUploadId"))
        assertFalse(value.has("avatarUrl")); assertEquals("19:35", value.getJSONObject("settings").getJSONObject("quietHours").getString("start"))
    }
    @Test fun inheritedModelIsExplicitNullAndDoesNotChangeGlobalDefaults() {
        val value = JSONObject(NativeCreationJson.payload(NativeCreationFixtures.journal()))
        assertTrue(value.has("model")); assertTrue(value.isNull("model")); assertFalse(value.has("defaultModel"))
    }
    @Test fun journalRestoresTheExactFrozenPayloadAndDraftIdentity() {
        val original = NativeCreationFixtures.journal().let { it.copy(frozenPayload = NativeCreationJson.payload(it)) }
        val restored = NativeCreationJson.journal(NativeCreationJson.journal(original))
        assertEquals(original.requestId, restored.requestId); assertEquals(original.draftId, restored.draftId); assertEquals(original.frozenPayload, restored.frozenPayload)
        assertEquals(original.editor.settings.proactiveEnabled, restored.editor.settings.proactiveEnabled)
        assertEquals(original.editor.settings.memoryEnabled, restored.editor.settings.memoryEnabled)
        val expectedHours = JSONObject(original.editor.settings.quietHoursJson!!)
        val restoredHours = JSONObject(restored.editor.settings.quietHoursJson!!)
        for (key in listOf("enabled", "start", "end")) assertEquals(expectedHours.get(key), restoredHours.get(key))
    }
    @Test fun frozenPayloadIgnoresObjectOrderButRejectsAdditionalSubmissionFields() {
        val base = NativeCreationFixtures.journal()
        val payload = JSONObject(NativeCreationJson.payload(base))
        val reordered = JSONObject().apply { payload.keys().asSequence().toList().reversed().forEach { put(it, payload.get(it)) } }.toString()
        assertEquals(reordered, NativeCreationJson.journal(NativeCreationJson.journal(base.copy(frozenPayload = reordered))).frozenPayload)
        val extra = JSONObject(reordered).put("unexpected", true).toString()
        malformed { NativeCreationJson.journal(NativeCreationJson.journal(base.copy(frozenPayload = extra))) }
    }
    @Test fun aFrozenPayloadCannotBeSilentlyRebasedToEditedFields() {
        val original = NativeCreationFixtures.journal().let { it.copy(frozenPayload = NativeCreationJson.payload(it)) }
        val value = JSONObject(NativeCreationJson.journal(original)); value.getJSONObject("editor").put("name", "Different fictional name")
        malformed { NativeCreationJson.journal(value.toString()) }
    }
    @Test fun draftImagesMustMatchTheExactCreationOwner() {
        val value = JSONObject().put("id", "upload-fixture").put("name", "fictional.png").put("mediaType", "image/png").put("bytes", 20)
            .put("url", NativeCreationFixtures.upload().url)
        assertEquals("upload-fixture", NativeCreationJson.upload(value, "draft-fixture").id)
        malformed { NativeCreationJson.upload(value, "different-draft") }
        malformed { NativeCreationJson.upload(JSONObject(value.toString()).put("url", "/droy/companions/other/attachments/upload-fixture"), "draft-fixture") }
    }
    @Test fun aRestoredImageKeepsItsOperationNonceAndCannotSwitchDrafts() {
        val image = NativeCreationImageOperation(NativeCreationFixtures.imageId, NativeCreationFixtures.nonce, "draft-fixture", NativeCreationImageKind.AVATAR, NativeCreationImagePhase.PICKING)
        val original = NativeCreationFixtures.journal().copy(image = image)
        assertEquals(image, NativeCreationJson.journal(NativeCreationJson.journal(original)).image)
        val invalid = JSONObject(NativeCreationJson.journal(original)); invalid.getJSONObject("image").put("draftId", "other-draft")
        malformed { NativeCreationJson.journal(invalid.toString()) }
    }
    @Test fun UnicodeNamesRespectCodePointsBytesControlsAndWellFormedText() {
        val emoji = String(Character.toChars(0x1F63A))
        assertTrue(NativeCreationNames.valid(emoji.repeat(80))); assertFalse(NativeCreationNames.valid(emoji.repeat(81)))
        assertFalse(NativeCreationNames.valid(" ")); assertFalse(NativeCreationNames.valid("\uFEFF")); assertFalse(NativeCreationNames.valid("Name\n"))
        assertFalse(NativeCreationNames.valid("\uD800")); assertFalse(NativeCreationNames.valid("\uDC00"))
        assertFalse(NativeCreationNames.valid(" ".repeat(321) + "x"))
    }
    @Test fun unknownDraftStateAndMissingCommittedOwnerAreNeverTreatedAsOpen() {
        malformed { NativeCreationJson.draft(JSONObject().put("draftId", "draft-fixture").put("state", "unknown")) }
        malformed { NativeCreationJson.draft(JSONObject().put("draftId", "draft-fixture").put("state", "committed")) }
        malformed { NativeCreationJson.draft(JSONObject().put("draftId", "wrong-draft").put("state", "open"), "draft-fixture") }
    }
    @Test fun receiptsAndCancellationResultsMustBeUnambiguous() {
        val value = JSONObject().put("requestId", "nonce").put("replayed", true).put("profile", JSONObject().put("id", "companion-fixture").put("templateId", "kaka"))
        assertEquals("companion-fixture", NativeCreationJson.receipt(value, "nonce").companionId)
        malformed { NativeCreationJson.receipt(value, "different-nonce") }
        malformed { NativeCreationJson.cancellation(JSONObject().put("cancelled", true).put("state", "committed").put("companionId", "companion-fixture")) }
    }
    @Test fun uploadedPhaseRequiresBothThePrivateImportAndARealUploadReceipt() {
        val image = NativeCreationImageOperation(NativeCreationFixtures.imageId, NativeCreationFixtures.nonce, "draft-fixture", NativeCreationImageKind.AVATAR, NativeCreationImagePhase.UPLOADED)
        malformed { NativeCreationJson.journal(NativeCreationJson.journal(NativeCreationFixtures.journal().copy(image = image))) }
    }
}
