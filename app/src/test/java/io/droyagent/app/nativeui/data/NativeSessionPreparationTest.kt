package io.droyagent.app.nativeui.data

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class NativeSessionPreparationTest {
    private fun record() = JSONObject().put("draftId", "draft-a").put("sessionId", "session-a").put("needsCreate", true)

    @Test fun mediaOwnershipSurvivesOnlyItsOwnMaterializationAndNotAnotherSpaceOrDraft() {
        val original = io.droyagent.app.nativeui.media.NativeWorkbenchMediaTarget("draft-a", null, "space-a")
        assertTrue(NativeSubmissions.matchesMediaTarget(original, "draft-a", null, "space-a") { null })
        assertTrue(NativeSubmissions.matchesMediaTarget(original, "draft-a", "draft-a", "space-a") { null })
        val materialized: (String) -> String? = { if (it == "draft-a") "session-a" else null }
        assertTrue(NativeSubmissions.matchesMediaTarget(original, "session-a", "session-a", "space-a", materialized))
        assertFalse(NativeSubmissions.matchesMediaTarget(original, "draft-b", null, "space-a", materialized))
        assertFalse(NativeSubmissions.matchesMediaTarget(original, "session-a", "session-a", "space-b", materialized))
        assertFalse(NativeSubmissions.matchesMediaTarget(original, "draft-b", "session-b", "space-a", materialized))
    }

    @Test fun capturedSelectionIsAnImmutableCopyAndRetryNeverCapturesALaterStage() = runBlocking {
        val body = record()
        val original = JSONObject().put("nonce", "original").put("agentPresetId", "preset-a")
        NativeSubmissions.captureSessionPreparation(body) { assertEquals("draft-a", it); original }
        original.put("nonce", "later")
        NativeSubmissions.captureSessionPreparation(body) { fail("recaptured"); null }
        val restored = JSONObject(body.toString())
        assertEquals("original", restored.getJSONObject("sessionPreparation").getString("nonce"))
        val order = mutableListOf<String>()
        NativeSubmissions.prepareSession(restored, { order += if (it.getBoolean("sessionPrepared")) "prepared-durable" else "attempt-durable" }) { sessionId, token ->
            assertEquals("session-a", sessionId)
            assertEquals("original", token!!.getString("nonce"))
            order += "engine-read-and-prepare"
            token.put("nonce", "caller-mutation")
        }
        order += "prompt"
        assertEquals(listOf("attempt-durable", "engine-read-and-prepare", "prepared-durable", "prompt"), order)
        assertEquals("original", restored.getJSONObject("sessionPreparation").getString("nonce"))
        NativeSubmissions.prepareSession(JSONObject(restored.toString()), { fail("persisted again") }) { _, _ -> fail("prepared again") }
    }

    @Test fun unknownPreparationKeepsFrozenTokenAndBlocksPromptUntilExplicitRetryChecksIt() = runBlocking {
        val body = record()
        NativeSubmissions.captureSessionPreparation(body) { JSONObject().put("nonce", "frozen") }
        var durable: JSONObject? = null
        var prompt = false
        try {
            NativeSubmissions.prepareSession(body, { durable = JSONObject(it.toString()) }) { _, _ -> throw NativeApiException("NATIVE_ENGINE_SAVE_UNCONFIRMED") }
            prompt = true
        } catch (expected: NativeApiException) { assertEquals("NATIVE_ENGINE_SAVE_UNCONFIRMED", expected.code) }
        assertFalse(prompt)
        assertTrue(durable!!.getBoolean("sessionPreparationAttempted"))
        assertFalse(durable!!.getBoolean("sessionPrepared"))
        NativeSubmissions.prepareSession(durable!!, {}) { _, token -> assertEquals("frozen", token!!.getString("nonce")) }
        assertTrue(durable!!.getBoolean("sessionPrepared"))
    }

    @Test fun legacyPendingAndExplicitDefaultDoNotCaptureOrApplyANewSelection() = runBlocking {
        NativeSubmissions.prepareSession(record(), { fail("legacy rewritten") }) { _, _ -> fail("legacy selection changed") }
        val body = record()
        NativeSubmissions.captureSessionPreparation(body) { null }
        assertTrue(body.has("sessionPreparation")); assertTrue(body.isNull("sessionPreparation"))
        assertTrue(body.getBoolean("sessionPrepared"))
        NativeSubmissions.prepareSession(body, { fail("default rewritten") }) { _, _ -> fail("default changed") }
    }

    @Test fun continuingAnExistingOrAlreadyMaterializedSessionNeverCapturesANewDraftStage() = runBlocking {
        val existing = record().put("needsCreate", false)
        NativeSubmissions.captureSessionPreparation(existing) { fail("An existing session has no draft binding"); null }
        assertTrue(existing.isNull("sessionPreparation"))
        assertTrue(existing.getBoolean("sessionPrepared"))
        NativeSubmissions.prepareSession(existing, { fail("existing session rewritten") }) { _, _ -> fail("existing session changed") }
    }
}
