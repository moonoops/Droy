package io.droyagent.app.nativeui.standardvoice

import io.droyagent.app.nativeui.data.NativeApiException
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class NativeStandardVoiceProtocolTest {
    @Test fun getReadsConfigurationWhileCurrentStatusUsesPostAndDoesNotTrustTheGetIdlePlaceholder() = runBlocking {
        val calls = mutableListOf<Pair<String, String?>>()
        val client = NativeStandardVoiceClient { path, method, body ->
            assertEquals("/droy/voice", path); calls += method to body?.optString("operation")
            ok(if (method == "GET") JSONObject("""{"config":{"entries":{"standard":true},"tts":{"provider":"system","voiceId":"","rate":1,"url":"","model":"tts-1"}},"state":{"status":"idle"}}""") else native("recording"))
        }
        assertTrue(client.config().enabled); assertTrue(client.status().active)
        assertEquals(listOf("GET" to null, "POST" to "status"), calls)
    }

    @Test fun realSegmentShapeKeepsTheUtteranceBoundaryAndDoesNotExposePcmToTheCoordinator() = runBlocking {
        var request: JSONObject? = null
        val client = NativeStandardVoiceClient { _, _, body ->
            request = body
            ok(native("recording").put("audio", "ignored-pcm").put("nextCursor", 32768).put("nextTranscriptCursor", 2)
                .put("segments", org.json.JSONArray("""[
                  {"id":"transcript-1:0","utteranceId":"transcript-1:utterance:0","text":"分段","final":true,"endOfUtterance":false,"timing":"segment"},
                  {"id":"transcript-1:1","utteranceId":"transcript-1:utterance:0","text":"结束","final":true,"endOfUtterance":true,"timing":"segment"}
                ]""")))
        }
        val result = client.poll(OPERATION, 20, 1, "capture-1", "transcript-1")
        assertEquals("poll", request!!.getString("operation")); assertEquals(20, request!!.getLong("cursor"))
        assertEquals("capture-1", request!!.getString("captureId")); assertEquals(1, request!!.getInt("transcriptCursor"))
        assertFalse(result.segments.first().complete); assertTrue(result.segments.last().complete)
        assertEquals(result.segments.first().utteranceId, result.segments.last().utteranceId)
        assertFalse(result.toString().contains("ignored-pcm"))
    }

    @Test fun standardStartMuteOutputAndStopUseOnlyTheExistingOperationNamesAndImmutableSourceIdentity() = runBlocking {
        val calls = mutableListOf<JSONObject>()
        val client = NativeStandardVoiceClient { _, method, body ->
            assertEquals("POST", method); calls += JSONObject(body!!.toString())
            ok(when (body.getString("operation")) {
                "speak" -> JSONObject().put("state", "queued").put("utteranceId", body.getString("utteranceId"))
                "stop_speaking" -> JSONObject().put("stopped", true)
                else -> native("recording")
            })
        }
        client.start(OPERATION, "blank-draft", 17); client.mute(OPERATION, true)
        client.speak(OPERATION, "本次回复。", "real-session", "reply-1", "utterance-1", NativeStandardVoiceTone("system", "voice", 1.0, "", "tts-1"))
        client.stopSpeaking(OPERATION); client.stop(OPERATION)
        assertEquals(listOf("start", "mute", "speak", "stop_speaking", "stop"), calls.map { it.getString("operation") })
        assertEquals("standard", calls.first().getString("mode")); assertEquals("blank-draft", calls.first().getString("sessionId"))
        assertEquals(17, calls.first().getLong("wakeEventId")); assertEquals("auto", calls.first().getString("language"))
        assertEquals("real-session", calls[2].getString("sessionId")); assertEquals("reply-1", calls[2].getString("replyId"))
        assertFalse(calls.any { it.has("apiKey") })
        assertTrue(calls.all { it.getString("voiceOperationId") == OPERATION })
    }

    @Test fun wakeStatusConsumeClaimAndReleaseUseTheActualNativeEnvelope() = runBlocking {
        val operations = mutableListOf<String>()
        val client = NativeStandardVoiceClient { _, _, body ->
            val operation = body!!.getString("operation"); operations += operation
            if (operation != "wake.status") assertEquals(OPERATION, body.getString("voiceOperationId"))
            ok(if (operation == "wake.consume") JSONObject().put("consumed", true).put("target", "standard")
                .put("voiceOperationId", JSONObject.NULL).put("startingEventId", 5).put("startingVoiceOperationId", OPERATION)
            else JSONObject("""{"enabled":true,"timing":"screen_on","target":"standard","screenAvailable":true,"suspended":false,"voiceClaimed":false,"eventId":5,"startingEventId":0}"""))
        }
        assertEquals(5, client.wakeStatus().eventId); assertTrue(client.consumeWake(OPERATION, 5)); client.claimWake(OPERATION); client.releaseWake(OPERATION)
        assertEquals(listOf("wake.status", "wake.consume", "wake.claim", "wake.release"), operations)
    }

    @Test fun serverRejectionKeepsItsSafeCodeAndNeverItsRawMessage() = runBlocking {
        val client = NativeStandardVoiceClient { _, _, _ -> JSONObject("""{"ok":false,"error":{"code":"TTS_PRIVATE_SESSION","message":"private rejected content"}}""") }
        try { client.status(); fail("expected rejection") }
        catch (failure: NativeApiException) {
            assertEquals("TTS_PRIVATE_SESSION", failure.code); assertFalse(failure.toString().contains("private rejected content"))
        }
    }

    @Test fun nativeAndLegacyOwnersRemainDistinctAndInvalidOperationIdsNeverLeaveTheClient() = runBlocking {
        var calls = 0
        val client = NativeStandardVoiceClient { _, _, _ -> calls++; ok(native("recording").put("voiceOperationId", JSONObject.NULL)) }
        assertNull(client.status().voiceOperationId)
        try { client.start("not-a-uuid", "same-session"); fail("invalid owner accepted") }
        catch (failure: NativeApiException) { assertEquals("native_standard_voice_invalid", failure.code) }
        assertEquals(1, calls)
        val owned = NativeStandardVoiceClient { _, _, _ -> ok(native("recording")) }
        assertEquals(OPERATION, owned.status().voiceOperationId)
    }

    @Test fun aConsumedWakeReceiptRequiresTheExactStartingOwnerAndEvent() = runBlocking {
        val client = NativeStandardVoiceClient { _, _, _ -> ok(JSONObject().put("consumed", true).put("target", "standard")
            .put("voiceOperationId", JSONObject.NULL).put("startingEventId", 5)
            .put("startingVoiceOperationId", "00000000-0000-0000-0000-000000000099")) }
        try { client.consumeWake(OPERATION, 5); fail("another owner's consume accepted") }
        catch (failure: NativeApiException) { assertEquals("native_standard_voice_invalid", failure.code) }
    }

    @Test fun malformedTypesMissingNativeFactsAndUnsafeErrorCodesAreRejected() = runBlocking {
        val values = listOf(native("recording").put("muted", "false"), native("recording").also { it.remove("captureId"); it.remove("speaking") },
            native("recording").put("level", -1), native("new-unknown-state"))
        for (value in values) {
            val client = NativeStandardVoiceClient { _, _, _ -> ok(value) }
            try { client.status(); fail("malformed response accepted") }
            catch (failure: NativeApiException) { assertEquals("native_standard_voice_invalid", failure.code) }
        }
        val client = NativeStandardVoiceClient { _, _, _ -> JSONObject("""{"ok":false,"error":{"code":"secret arbitrary text"}}""") }
        try { client.status(); fail("unsafe code accepted") }
        catch (failure: NativeApiException) { assertEquals("native_standard_voice_invalid", failure.code) }
    }

    @Test fun aNativeFailedStateIsPreservedEvenWhenNoRawErrorMessageWasReturned() = runBlocking {
        val client = NativeStandardVoiceClient { _, _, _ -> ok(native("failed")) }
        assertTrue(client.status().failed)
    }

    @Test fun finalPrivateDraftRoundTripKeepsTheFrozenNonceBodyAndWakeTargetWithoutAsrOrTtsReplayData() = runBlocking {
        var saved: String? = null
        val store = NativeStandardVoiceJsonStore({ saved }, { saved = it; true })
        val journal = NativeStandardVoiceJournal(OPERATION, "native-process", NativeStandardVoiceTarget("wake-draft", null, "space", "fingerprint"),
            captureId = "capture-1", startAttempted = true, wake = NativeStandardVoiceWake(8, NativeStandardVoiceWakePhase.CONSUMED),
            submissions = listOf(NativeStandardVoiceSubmission("transcript:utterance", "fixed-nonce", "最终私有转写", NativeStandardVoiceDelivery.UNKNOWN)))
        store.save(journal); assertEquals(journal, store.load())
        val keys = JSONObject(saved!!).keys().asSequence().toSet()
        assertFalse(keys.contains("partialTranscript")); assertFalse(keys.contains("cursor")); assertFalse(keys.contains("readAloud")); assertFalse(keys.contains("output"))
        assertEquals("最终私有转写", store.load()!!.submissions.single().text)
    }

    @Test fun corruptPreferencesAndFailedDurableWritesAreExplicitFailures() = runBlocking {
        for (raw in listOf("broken", "{}", """{"version":2}""")) {
            val store = NativeStandardVoiceJsonStore({ raw }, { true })
            try { store.load(); fail("corrupt storage accepted") }
            catch (failure: NativeApiException) { assertEquals("native_standard_voice_storage", failure.code) }
        }
        val failed = NativeStandardVoiceJsonStore({ null }, { false })
        try { failed.save(null); fail("failed commit accepted") }
        catch (failure: NativeApiException) { assertEquals("native_standard_voice_storage", failure.code) }
    }

    companion object {
        private const val OPERATION = "00000000-0000-0000-0000-000000000001"
        private fun ok(result: JSONObject) = JSONObject().put("ok", true).put("result", result)
        // VoiceMedia.status wire facts, not the GET snapshot's placeholder {status:idle}.
        private fun native(state: String) = JSONObject().put("state", state).put("mode", "standard")
            .put("voiceOperationId", OPERATION)
            .put("sessionId", "blank-draft").put("captureId", "capture-1").put("transcriptId", "transcript-1")
            .put("muted", false).put("speaking", false).put("level", 0).put("needsPermission", JSONObject.NULL)
            .put("error", JSONObject.NULL).put("speechError", JSONObject.NULL)
    }
}
