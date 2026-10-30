package io.droyagent.app.nativeui

import io.droyagent.app.nativeui.data.NativeModelJson
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class NativeModelJsonTest {
    @Test fun catalogKeepsAdapterOwnedEffortsAndDefault() {
        val model = NativeModelJson.catalog("fixture", "Fixture", JSONObject("""{"id":"model","name":"Model","reasoning":{"efforts":[{"id":"disabled","name":"Off"},{"id":"deep","name":"Deep"}],"defaultEffort":"deep"}}"""))
        assertEquals(listOf("disabled", "deep"), model.reasoningEfforts)
        assertEquals("deep", model.defaultReasoningEffort)
        assertEquals("Off", model.reasoningNames["disabled"])
    }
    @Test fun modelsWithoutMetadataDoNotAcquireInventedEfforts() {
        val model = NativeModelJson.catalog("fixture", "Fixture", JSONObject("""{"id":"model","name":"Model"}"""))
        assertTrue(model.reasoningEfforts.isEmpty())
        assertNull(model.defaultReasoningEffort)
    }
    @Test fun draftRoundTripKeepsExplicitEffortIncludingOff() {
        val original = NativeModelJson.draft(JSONObject("""{"provider":"fixture","id":"model","name":"Model","providerName":"Fixture","reasoningEffort":"off"}"""))
        assertEquals(original, NativeModelJson.draft(NativeModelJson.draft(original)))
        assertFalse(NativeModelJson.draft(original.copy(reasoningEffort = null)).has("reasoningEffort"))
    }
}
