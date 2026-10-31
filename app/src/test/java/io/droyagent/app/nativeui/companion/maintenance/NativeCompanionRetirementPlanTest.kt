package io.droyagent.app.nativeui.companion.maintenance

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class NativeCompanionRetirementPlanTest {
    private fun plan(prefs: Map<String, Map<String, Any?>>, native: JSONArray = JSONArray(), legacy: JSONArray = JSONArray()) =
        NativeCompanionRetirementPlan.create("operation", "target", "epoch", prefs, native, legacy)
    private fun attachment(id: String) = JSONObject().put("importId", id).put("id", "item").put("name", "photo.jpg")
    private fun draft(id: String) = JSONObject().put("text", "draft").put("localAttachments", JSONArray().put(attachment(id))).toString()
    private fun manifest(id: String) = JSONObject().put("id", id).put("source", "native-picker").put("consumer", "work").put("items", JSONArray())
    private fun changes(plan: JSONObject) = plan.getJSONArray("changes").let { rows -> (0 until rows.length()).map(rows::getJSONObject) }

    @Test fun retiresOnlyDeletedCompanionsBackgroundHintIncludingPrivateAttachmentReference() {
        val background = JSONObject().put("type", "upload").put("id", "image")
            .put("url", "/droy/companions/target/attachments/image").toString()
        val result = plan(mapOf("native_companions" to mapOf("background:target" to background,
            "background:target-extra" to "{}")))
        assertEquals(listOf("background:target"), changes(result).map { it.getString("key") })
        assertTrue(result.getJSONObject("snapshot").toString().contains("/droy/companions/target/attachments/image"))
    }

    @Test fun retiresVoiceMetadataOnlyForDeletedSession() {
        val result = plan(mapOf("native_workbench" to mapOf(
            "voice-duration:im:target:request" to 3200L,
            "voice-duration:im:other:request" to 4100L)))
        val removed = NativeCompanionRetirementPlan.removedWorkbenchViews(result,
            JSONObject().put("removedSessionIds", JSONArray().put("im:target")))
        assertEquals(listOf("voice-duration:im:target:request"), removed.map { it.getString("key") })
    }
    @Test fun removesOnlyExactCompanionKeysAndItsPendingExternalReceipt() {
        val prefs = mapOf("native_companions" to mapOf<String, Any?>(
            "draft:target" to draft("exclusive"), "draft:target-extra" to draft("other"),
            "pending:target" to JSONObject().put("clientId", "client").toString(), "external:client" to true,
            "external:other" to true, "selectedCompanionId" to "other", "anchor:target" to "{}",
            "media-delivered:target:operation" to setOf("item"), "media-delivered:target-extra:operation" to setOf("item")))
        val result = plan(prefs, JSONArray().put(manifest("exclusive")).put(manifest("other")))
        assertEquals(setOf("draft:target", "pending:target", "external:client", "anchor:target", "media-delivered:target:operation"), changes(result).map { it.getString("key") }.toSet())
        assertEquals("exclusive", result.getJSONArray("imports").getJSONObject(0).getString("id"))
        assertEquals(1, result.getJSONArray("imports").length())
    }
    @Test fun retiredCompanionAlsoRemovesEveryFailedOutboxItemWithoutTouchingAnotherCompanion() {
        val record = JSONObject().put("clientId", "failed-client").put("draft", JSONObject(draft("failed-import"))).toString()
        val result = plan(mapOf("native_companions" to mapOf(
            "pending:target:failed-client" to record, "external:failed-client" to true,
            "pending:other:other-client" to JSONObject().put("clientId", "other-client").toString(), "external:other-client" to true)),
            JSONArray().put(manifest("failed-import")))
        assertEquals(setOf("pending:target:failed-client", "external:failed-client"), changes(result).map { it.getString("key") }.toSet())
        assertEquals("failed-import", result.getJSONArray("imports").getJSONObject(0).getString("id"))
    }
    @Test fun sharedImportAndUnrelatedImportsArePreserved() {
        val result = plan(mapOf("native_companions" to mapOf("draft:target" to draft("shared"), "draft:other" to draft("shared"))),
            JSONArray().put(manifest("shared")).put(manifest("unrelated")))
        assertEquals(0, result.getJSONArray("imports").length())
    }
    @Test fun workbenchDraftAndInboxProtectImports() {
        val result = plan(mapOf("native_companions" to mapOf("draft:target" to draft("inbox")),
            "native_workbench" to mapOf("draft:work" to "{ unfinished code", "attachments:work" to JSONArray().put(attachment("work")).toString()),
            "native_share_inbox" to mapOf("ids" to JSONArray().put("inbox").toString())), JSONArray().put(manifest("inbox")))
        assertEquals(0, result.getJSONArray("imports").length())
        assertTrue(changes(result).none { it.getString("preferences") == "native_workbench" })
    }
    @Test fun detachesShareAndVoiceButKeepsTheirTextAndOriginalImports() {
        val share = JSONObject().put("importId", "share").put("requestId", "old-request").put("text", "keep text")
            .put("attachments", JSONArray().put(attachment("share"))).put("destination", JSONObject().put("companionId", "target"))
            .put("uploaded", JSONObject().put("item", JSONObject().put("url", "/droy/companions/target/attachments/upload")))
        val voice = JSONObject().put("target", JSONObject().put("companionId", "target")).put("source", attachment("voice"))
            .put("transcript", "keep voice").put("uploadedVoice", JSONObject().put("url", "/droy/companions/target/attachments/voice"))
        val result = plan(mapOf("native_share_preview" to mapOf("preview" to share.toString()), "native_voice_input" to mapOf("state" to voice.toString())))
        val replacements = changes(result).associate { it.getString("preferences") to JSONObject(it.getString("after")) }
        assertTrue(replacements.getValue("native_share_preview").isNull("destination"))
        assertEquals("keep text", replacements.getValue("native_share_preview").getString("text"))
        assertNotEquals("old-request", replacements.getValue("native_share_preview").getString("requestId"))
        assertTrue(replacements.getValue("native_voice_input").isNull("target"))
        assertEquals("voice", replacements.getValue("native_voice_input").getJSONObject("source").getString("importId"))
        assertEquals("keep voice", replacements.getValue("native_voice_input").getString("transcript"))
        assertEquals(0, result.getJSONArray("imports").length())
    }
    @Test fun removesTargetMediaRowsAndPreservesWorkbenchSelectionAndUserAvatar() {
        val media = JSONObject().put("selection", JSONObject().put("id", "work-import").put("ownerId", "target").put("kind", "WORKBENCH_DRAFT"))
            .put("avatars", JSONArray().put(JSONObject().put("ownerId", "target")).put(JSONObject().put("ownerId", JSONObject.NULL)))
            .put("backgrounds", JSONArray().put(JSONObject().put("ownerId", "other")))
        val result = plan(mapOf("native_media" to mapOf("native-media-v1" to media.toString())))
        val after = JSONObject(changes(result).single().getString("after"))
        assertEquals("work-import", after.getJSONObject("selection").getString("id"))
        assertEquals(1, after.getJSONArray("avatars").length())
        assertTrue(after.getJSONArray("avatars").getJSONObject(0).isNull("ownerId"))
        assertEquals("other", after.getJSONArray("backgrounds").getJSONObject(0).getString("ownerId"))
    }
    @Test fun legacyImportsRequireExplicitCompanionConsumerAndTarget() {
        val legacy = JSONArray().put(manifest("target-import").put("consumer", "companion").put("targetSessionId", "im:target"))
            .put(manifest("other-import").put("consumer", "companion").put("targetSessionId", "im:other"))
            .put(manifest("work-import"))
        val result = plan(emptyMap(), legacy = legacy)
        assertEquals(1, result.getJSONArray("imports").length())
        assertEquals("target-import", result.getJSONArray("imports").getJSONObject(0).getString("id"))
    }
    @Test(expected = IllegalArgumentException::class) fun malformedOwnedDraftCannotLoseReferenceProtection() {
        plan(mapOf("native_companions" to mapOf("draft:other" to "{ malformed")))
    }
    @Test fun snapshotContainsReferencesRatherThanPrivateDraftBodies() {
        val result = plan(mapOf("native_companions" to mapOf("draft:target" to JSONObject().put("text", "private text").put("attachments", JSONArray().put(JSONObject().put("url", "/droy/companions/target/attachments/item"))).toString())))
        val snapshot = result.getJSONObject("snapshot").toString()
        assertFalse(snapshot.contains("private text")); assertTrue(snapshot.contains("/droy/companions/target/attachments/item"))
        assertTrue(result.getString("retirementDigest").matches(Regex("[0-9a-f]{64}")))
    }
    @Test fun plansStableCurrentDraftRecoveryWithoutOverwritingExistingBlank() {
        val result = plan(mapOf("native_workbench" to mapOf(
            "selectedSessionId" to "removed-session", "selectedWorkspaceId" to "removed-space",
            "draft:removed-session" to "continue editing", "attachments:removed-session" to "[]",
            "blank-draft:null" to "existing-blank", "draft:existing-blank" to "other draft",
            "readingKey:removed-session" to "message", "readingKey:other-session" to "keep")))
        val recovery = result.getJSONObject("workbenchRecovery")
        assertEquals("continue editing", recovery.getString("text"))
        assertNotEquals("existing-blank", recovery.getString("draftId"))
        assertEquals(recovery.getString("draftId"), JSONObject(result.toString()).getJSONObject("workbenchRecovery").getString("draftId"))
        val receipt = JSONObject().put("removedSessionIds", JSONArray().put("removed-session")).put("workspaceIds", JSONArray().put("removed-space"))
        assertEquals(setOf("selectedSessionId", "selectedWorkspaceId", "readingKey:removed-session"),
            NativeCompanionRetirementPlan.removedWorkbenchViews(result, receipt).map { it.getString("key") }.toSet())
        assertTrue(changes(result).none { it.getString("preferences") == "native_workbench" })
    }

}
