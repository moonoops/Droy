package io.droyagent.app.nativeui.memory

import io.droyagent.app.nativeui.data.NativeApiException
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

internal object NativeMemoryFixtures {
    fun recordValue(id: String = "record-a", text: String = "Fictional note", revision: String = "revision-a") = JSONObject()
        .put("schemaVersion", 1).put("recordId", id).put("revisionId", revision).put("kind", "fact").put("state", "active")
        .put("payload", JSONObject().put("text", text)).put("occurredAt", "2030-01-01T10:00:00.000Z").put("recordedAt", "2030-01-01T10:01:00.000Z")
        .put("assertion", "user_statement").put("actor", "user").put("retention", JSONObject().put("mode", "manual").put("expiresAt", JSONObject.NULL))
        .put("sourceRefs", JSONArray(listOf("fixture:source"))).put("lineageRefs", JSONArray(listOf("fixture:lineage"))).put("derivedFrom", JSONArray())
        .put("privacy", JSONObject().put("categories", JSONArray(listOf("memory")))).put("validFrom", JSONObject.NULL).put("validUntil", JSONObject.NULL)
    fun record(id: String = "record-a", text: String = "Fictional note", revision: String = "revision-a") = NativeMemoryJson.record(recordValue(id, text, revision))
    fun statusValue() = JSONObject().put("available", true).put("managementAvailable", true)
        .put("gate", JSONObject().put("ready", true).put("reason", "ready"))
        .put("settings", JSONObject().put("enabled", true).put("recallEnabled", true).put("automaticExtraction", false))
        .put("maintenanceAvailable", false).put("store", JSONObject().put("counts", JSONObject().put("active", 1)).put("storage", JSONObject().put("limited", false).put("bytes", 4096)))
    fun status() = NativeMemoryJson.status(statusValue())
    fun query(rows: List<NativeMemoryRecord>, cursor: String? = null, revision: Long = 1L) = NativeMemoryQueryResult(rows, rows.size.toLong(), cursor, revision,
        cursor != null, NativeMemoryCoverage(0, 0, 0, false))
}

class NativeMemoryJsonTest {
    private fun malformed(block: () -> Unit) {
        try { block(); fail("Expected malformed response") } catch (error: NativeApiException) { assertEquals("malformed_response", error.code) }
    }
    @Test fun recordsPreserveProvenanceAttributionAndExactRevision() {
        val value = NativeMemoryFixtures.recordValue().put("assertion", "model_inference").put("actor", "unknown")
            .put("derivedFrom", JSONArray().put(JSONObject().put("recordId", "parent").put("revisionId", "exact-parent-revision")))
        val record = NativeMemoryJson.record(value)
        assertEquals("revision-a", record.revisionId); assertEquals("model_inference", record.assertion); assertEquals("unknown", record.actor)
        assertEquals(listOf("fixture:source"), record.sourceRefs); assertEquals(listOf("fixture:lineage"), record.lineageRefs)
        assertEquals("exact-parent-revision", record.derivedFrom.single().revisionId)
    }
    @Test fun deletedTombstonesAreReadableWithoutRecreatingBodies() {
        val record = NativeMemoryJson.record(NativeMemoryFixtures.recordValue().put("state", "deleted").put("payload", JSONObject.NULL))
        assertNull(record.text); assertNull(record.payloadJson); assertFalse(record.editable)
    }
    @Test fun malformedKnownFieldsAreNotInterpretedAsEmptyRecords() {
        malformed { NativeMemoryJson.record(NativeMemoryFixtures.recordValue().put("sourceRefs", "bad")) }
        malformed { NativeMemoryJson.record(NativeMemoryFixtures.recordValue().put("schemaVersion", 2)) }
        malformed { NativeMemoryJson.record(NativeMemoryFixtures.recordValue().put("payload", JSONArray())) }
    }
    @Test fun statusRequiresActualBooleansAndAvailableManagementRequiresItsStore() {
        malformed { NativeMemoryJson.status(NativeMemoryFixtures.statusValue().put("available", "true")) }
        malformed { NativeMemoryJson.status(NativeMemoryFixtures.statusValue().apply { remove("store") }) }
        malformed { NativeMemoryJson.status(NativeMemoryFixtures.statusValue().put("capture", "bad")) }
    }
    @Test fun memoryReadinessDoesNotDependOnRootAuthorization() {
        for (root in listOf(false, JSONObject.NULL)) {
            val value = NativeMemoryFixtures.statusValue().apply { getJSONObject("gate").put("rootAuthorized", root) }
            val status = NativeMemoryJson.status(value)
            assertTrue(status.available); assertTrue(status.managementAvailable); assertTrue(status.gateReady)
        }
        assertTrue(NativeMemoryJson.status(NativeMemoryFixtures.statusValue()).available)
    }
    @Test fun unavailableManagementDoesNotInventStoreCountsOrRootAuthorization() {
        val value = NativeMemoryFixtures.statusValue().put("managementAvailable", false).put("available", false).apply {
            remove("store"); getJSONObject("gate").put("rootAuthorized", JSONObject.NULL)
        }
        val status = NativeMemoryJson.status(value)
        assertTrue(status.counts.isEmpty()); assertNull(status.rootAuthorized); assertFalse(status.managementAvailable)
    }
    @Test fun invalidNumbersAndNegativeCountsAreRejected() {
        malformed { NativeMemoryJson.status(NativeMemoryFixtures.statusValue().apply { getJSONObject("store").getJSONObject("counts").put("active", -1) }) }
        malformed { NativeMemoryJson.status(NativeMemoryFixtures.statusValue().apply { getJSONObject("store").getJSONObject("counts").put("active", 1.5) }) }
    }
    @Test fun aCorrectionUsesTheReadRevisionAndDoesNotSubmitSourceOverrides() {
        val record = NativeMemoryFixtures.record()
        val mutation = NativeMemoryJson.mutation(NativeMemoryDraft(record, record.kind, "Corrected fictional content"), "native:stable")
        val args = JSONObject(mutation.argsJson)
        assertEquals("revise", mutation.operation); assertEquals(record.revisionId, args.getString("expectedRevisionId"))
        assertEquals(setOf("payload"), args.getJSONObject("patch").keys().asSequence().toSet())
        assertEquals("native:stable", args.getString("operationId"))
    }
    @Test fun locationCorrectionsKeepCoordinatesMockFlagsAndAccuracy() {
        val payload = JSONObject().put("eventType", "location").put("latitude", 1.5).put("longitude", 2.5).put("accuracyMeters", 20)
            .put("sampledAt", "2030-01-01T10:00:00Z").put("isMock", true).put("approximate", true).put("city", "Old fictional city")
        val record = NativeMemoryJson.record(NativeMemoryFixtures.recordValue().put("kind", "event").put("payload", payload))
        val mutation = NativeMemoryJson.mutation(NativeMemoryDraft(record, record.kind, place = "Fictional place", city = ""), "native:location")
        val next = JSONObject(mutation.argsJson).getJSONObject("patch").getJSONObject("payload")
        assertEquals(1.5, next.getDouble("latitude"), 0.0); assertEquals(20, next.getInt("accuracyMeters"))
        assertTrue(next.getBoolean("isMock")); assertTrue(next.getBoolean("approximate")); assertFalse(next.has("city"))
        assertEquals("Fictional place", next.getString("placeName"))
    }
    @Test fun unknownRecordKindsRemainReadableButAreNotEditable() {
        val record = NativeMemoryJson.record(NativeMemoryFixtures.recordValue().put("kind", "future-kind"))
        assertEquals("future-kind", record.kind); assertFalse(record.editable)
    }
    @Test fun captureApplicationTruncationRemainsVisible() {
        val apps = NativeMemoryJson.apps(JSONObject().put("items", JSONArray().put(JSONObject().put("packageName", "app.fictional").put("label", "Fictional"))).put("truncated", true))
        assertTrue(apps.truncated); assertEquals("app.fictional", apps.items.single().packageName)
    }
    @Test fun pageMetadataRetainsCoverageAndCursorWithoutClaimingContinuousObservation() {
        val value = JSONObject().put("items", JSONArray().put(NativeMemoryFixtures.recordValue())).put("total", 3).put("revision", 4)
            .put("nextCursor", "opaque-cursor").put("truncated", true).put("coverage", JSONObject().put("gaps", 2).put("unresolvedLocations", 1).put("mockLocations", 1).put("continuousObservationVerified", false))
        val page = NativeMemoryJson.query(value)
        assertEquals(4, page.revision); assertEquals("opaque-cursor", page.nextCursor); assertEquals(2, page.coverage.gaps)
        assertFalse(page.coverage.continuousObservationVerified)
    }
    @Test fun filtersRetainTimeBoundsAndAllEventGroupsWithoutAddingStoreControls() {
        val value = NativeMemoryJson.filterValue(NativeMemoryFilter(NativeMemoryGroup.PLACES, " City ", "2030-01-01T00:00:00Z", "2030-02-01T00:00:00Z"), "cursor")
        assertEquals("City", value.getString("text")); assertEquals("2030-02-01T00:00:00Z", value.getString("to"))
        assertEquals(listOf("location", "visit"), (0 until value.getJSONArray("eventTypes").length()).map { value.getJSONArray("eventTypes").getString(it) })
        assertFalse(value.has("states")); assertFalse(value.has("limit")); assertEquals("cursor", value.getString("cursor"))
    }
    @Test fun contentByteLimitUsesUtf8RatherThanCharacterCount() {
        assertTrue(NativeMemoryDraft(text = "中".repeat(5000)).valid)
        assertFalse(NativeMemoryDraft(text = "中".repeat(5500)).valid)
    }
    @Test fun duplicateEndpointIdentitiesAreRejected() {
        val endpoint = JSONObject().put("id", "opaque").put("purpose", "conversation").put("label", "Fictional model").put("model", "fixture")
            .put("url", "https://model.example.invalid/v1/chat/completions").put("authorized", false).put("categories", JSONArray())
        malformed { NativeMemoryJson.endpoints(JSONArray().put(endpoint).put(endpoint)) }
    }
    @Test fun appScopeSaveRespectsProtectionWhileStillPermittingConsentReduction() {
        val config = NativeMemoryCaptureConfig(true, false, true, false, false, false, listOf("app.kept", "app.removed"), listOf("app.excluded"))
        val status = NativeMemoryFixtures.status().copy(available = false, capture = NativeMemoryCapture("off", config, false, false, null, null, null))
        val scope = NativeMemoryState(status = status, appAllow = config.allowedPackages, appExclude = config.excludedPackages)
        assertTrue(scope.copy(appAllow = listOf("app.kept")).appScopeConsentAllowsSave)
        assertTrue(scope.copy(appExclude = listOf("app.excluded", "app.new-exclusion")).appScopeConsentAllowsSave)
        assertFalse(scope.copy(appAllow = config.allowedPackages + "app.new").appScopeConsentAllowsSave)
        assertFalse(scope.copy(appExclude = emptyList()).appScopeConsentAllowsSave)
        assertTrue(scope.copy(status = status.copy(available = true), appAllow = config.allowedPackages + "app.new").appScopeConsentAllowsSave)
    }
}
