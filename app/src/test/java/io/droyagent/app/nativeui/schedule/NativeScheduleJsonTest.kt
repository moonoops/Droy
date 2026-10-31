package io.droyagent.app.nativeui.schedule

import io.droyagent.app.nativeui.data.NativeApiException
import java.time.Instant
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class NativeScheduleJsonTest {
    private val common = """{"id":"task-a","title":"Review","prompt":"Check the result","scheduledAt":"2099-01-01T01:00:00.000Z"}""".removeSurrounding("{", "}")
    private fun record(rule: String = "\"kind\":\"every\",\"everySeconds\":300") = JSONObject("{$common,$rule}")
    private fun entry(rule: String = "\"kind\":\"every\",\"everySeconds\":300") = record(rule).put("sessionId", "session-a").put("status", "active")
    private val key = NativeScheduleKey("session-a", "task-a")
    private val now = Instant.parse("2030-01-01T00:00:00Z")

    @Test fun allPublicRecordKindsRoundTripWithoutCatalogMetadata() {
        val rules = listOf("\"kind\":\"after\",\"afterSeconds\":1", "\"kind\":\"at\"",
            "\"kind\":\"every\",\"everySeconds\":60", "\"kind\":\"daily\",\"time\":\"09:00:00.125\",\"timeZone\":\"US/Eastern\"",
            "\"kind\":\"weekly\",\"time\":\"09:00:00.000\",\"timeZone\":\"Asia/Shanghai\",\"weekdays\":[1,5]",
            "\"kind\":\"cron\",\"expression\":\"*/15 9-17 * * 1-5\",\"timeZone\":\"UTC\"")
        for (rule in rules) {
            val original = entry(rule).put("lastDelivery", JSONObject("""{"scheduledAt":"2098-01-01T00:00:00.000Z","deliveredAt":"2098-01-01T00:00:01.000Z","messageId":"delivery-a"}"""))
            val decoded = NativeScheduleJson.catalog(JSONArray().put(original)).single()
            val serialized = NativeScheduleJson.recordValue(decoded.record)
            assertFalse(serialized.has("sessionId")); assertFalse(serialized.has("status")); assertFalse(serialized.has("lastDelivery"))
            assertEquals(decoded.record, NativeScheduleJson.record(serialized))
            assertEquals("delivery-a", decoded.lastDelivery?.messageId)
        }
    }

    @Test fun aBadCatalogRowDoesNotDisappearIntoAPartialOrEmptyList() {
        for (bad in listOf(entry().put("kind", "new-rule"), entry().put("status", "paused"),
            entry().put("everySeconds", "300"), entry().put("everySeconds", 60.5), entry().put("title", JSONObject.NULL),
            entry().put("unknownRuleField", true))) {
            assertCode("malformed_response") { NativeScheduleJson.catalog(JSONArray().put(entry()).put(bad)) }
        }
    }

    @Test fun titleOnlyEditingKeepsTheExactAfterTargetAndDoesNotEmitAChange() {
        val original = NativeScheduleJson.catalog(JSONArray().put(entry("\"kind\":\"after\",\"afterSeconds\":600"))).single()
        val draft = NativeScheduleEdits.edit(original, "Asia/Shanghai").copy(title = " Renamed ")
        val value = NativeScheduleJson.updateValue(NativeScheduleEdits.update(draft)!!)
        assertFalse(value.has("change")); assertFalse(value.has("prompt"))
        assertEquals("Renamed", value.getString("title"))
        assertEquals("after", value.getJSONObject("expected").getString("kind"))
        assertEquals("2099-01-01T01:00:00.000Z", value.getJSONObject("expected").getString("scheduledAt"))
        assertEquals(600L, value.getJSONObject("expected").getLong("afterSeconds"))
        assertFalse(value.getJSONObject("expected").has("sessionId"))
    }

    @Test fun unchangedEditorDoesNotPrepareAMutationAndExpiredUnchangedTimingIsNotReanchored() {
        val current = NativeScheduleEntry(NativeScheduleJson.record(record("\"kind\":\"at\"")).copy(scheduledAt = "2029-01-01T00:00:00.000Z"), "session-a", "active")
        val draft = NativeScheduleEdits.edit(current, "UTC")
        assertNull(NativeScheduleEdits.update(draft))
        NativeScheduleEdits.validate(draft.copy(title = "Changed"), now)
        assertNull(NativeScheduleEdits.update(draft.copy(title = "Changed"))?.change)
    }

    @Test fun timingChangeUsesThePublicDiscriminatedShapeAndRetainsCompleteExpected() {
        val current = NativeScheduleJson.catalog(JSONArray().put(entry("\"kind\":\"weekly\",\"time\":\"09:00:00.125\",\"timeZone\":\"US/Eastern\",\"weekdays\":[1,5]"))).single()
        val draft = NativeScheduleEdits.edit(current, "UTC")
        val changed = draft.copy(timing = draft.timing.copy(kind = "daily", time = "10:15", timeZone = "Asia/Shanghai"))
        val payload = NativeScheduleJson.updateValue(NativeScheduleEdits.update(changed)!!)
        assertEquals("daily", payload.getJSONObject("change").getString("kind"))
        assertEquals("10:15:00.000", payload.getJSONObject("change").getJSONObject("daily").getString("time"))
        assertEquals("Asia/Shanghai", payload.getJSONObject("change").getJSONObject("daily").getString("time_zone"))
        assertEquals("US/Eastern", payload.getJSONObject("expected").getString("timeZone"))
        assertEquals("09:00:00.125", payload.getJSONObject("expected").getString("time"))
        assertEquals(listOf(1, 5), (0..1).map { payload.getJSONObject("expected").getJSONArray("weekdays").getInt(it) })
    }

    @Test fun aNoOpReceiptIsDifferentFromAConflictAndAnInvalidRule() {
        val noOp = NativeScheduleJson.updateResult(key, JSONObject().put("id", key.id).put("updated", false).put("record", record()))
        assertTrue(noOp is NativeScheduleUpdateResult.Record)
        assertFalse((noOp as NativeScheduleUpdateResult.Record).updated)
        for (code in listOf("schedule_conflict", "schedule_ended", "invalid_rule", "future_unknown")) {
            assertEquals(code, (NativeScheduleJson.updateResult(key, JSONObject().put("code", code)) as NativeScheduleUpdateResult.Rejected).code)
        }
    }

    @Test fun creationUsesExactlyOnePublicSelectorAndNoEditorOrCatalogFields() {
        val initial = NativeScheduleEdits.create("session-a", now, "UTC").copy(title = " Task ", prompt = " Instruction ")
        for (kind in listOf("after", "at", "every", "daily", "weekly", "cron")) {
            val editor = initial.copy(timing = initial.timing.copy(kind = kind, weekdays = listOf(1, 5), expression = "*/15 * * * *"))
            NativeScheduleEdits.validate(editor, now)
            val request = NativeScheduleJson.createValue(editor).getJSONObject("request")
            val selector = when (kind) { "after" -> "after_seconds"; "every" -> "every_seconds"; else -> kind }
            assertEquals(setOf("title", "prompt", selector), request.keys().asSequence().toSet())
            assertEquals("Task", request.getString("title")); assertEquals("Instruction", request.getString("prompt"))
        }
    }

    @Test fun creationTargetsExcludeManagedArchivedDelegatedAndUnusableSessionsButKeepOrdinaryForks() {
        val workspaces = JSONObject("""{"items":[{"workspaceId":"ordinary","title":"Work","sessionIds":["root","fork"]},{"workspaceId":"pet","title":"Companion","sessionIds":["space-owned"]}],"managedWorkspaceIds":["pet"],"archivedSessionIds":["archived"]}""")
        val sessions = JSONObject().put("managedSessionIds", JSONArray().put("managed")).put("items", JSONArray().apply {
            for (id in listOf("root", "fork", "managed", "archived", "child", "missing-cwd", "migrate", "space-owned")) put(JSONObject()
                .put("sessionId", id).put("cwd", if (id == "missing-cwd") "" else "/work")
                .put("projections", JSONObject().put("values", JSONObject().put("title", "Name $id")))
                .apply { if (id == "child") put("origin", "subagent"); if (id == "migrate") put("formatStatus", "migration-required"); if (id == "fork") put("parentSessionId", "root") })
        })
        val targets = NativeScheduleJson.targets(workspaces, sessions)
        assertEquals(listOf("root", "fork"), targets.eligible.map { it.id })
        assertEquals("Name managed", targets.titles["managed"])
        assertEquals("Work", targets.eligible.first().workspaceTitle)
    }

    @Test fun malformedTargetMetadataIsAnErrorRatherThanAnAuthoritativeEmptySet() {
        val spaces = JSONObject("""{"items":[],"managedWorkspaceIds":[],"archivedSessionIds":[]}""")
        val sessions = JSONObject("""{"items":[{"sessionId":"a","cwd":"/work","origin":"future-routing"}],"managedSessionIds":[]}""")
        assertCode("malformed_response") { NativeScheduleJson.targets(spaces, sessions) }
    }

    @Test fun historyKeepsReceiptOrderMissingPromptAndDistinctRetentionEvidence() {
        val value = JSONObject("""{"id":"task-a","records":[{"messageId":"newer","scheduledAt":"2099-01-02T00:00:00.000Z","deliveredAt":"2099-01-01T00:00:00.000Z"},{"messageId":"older","scheduledAt":"2099-01-01T00:00:00.000Z","deliveredAt":"2099-01-02T00:00:00.000Z","prompt":"Original instruction"}],"earlierRecordsUnavailable":true,"earlierRecordsPruned":false,"retention":{"days":30,"records":200},"nextBefore":"older"}""")
        val page = (NativeScheduleJson.history(key, value) as NativeScheduleHistoryResult.Page).value
        assertEquals(listOf("newer", "older"), page.records.map { it.messageId })
        assertNull(page.records.first().prompt)
        assertEquals("Original instruction", page.records.last().prompt)
        assertTrue(page.earlierRecordsUnavailable); assertFalse(page.earlierRecordsPruned)
        assertEquals(30, page.retentionDays)
    }

    @Test fun validEmptyHistoryAndCursorFailureAreNotInterchangeable() {
        val empty = JSONObject("""{"id":"task-a","records":[],"earlierRecordsUnavailable":false,"earlierRecordsPruned":false,"retention":{"days":30,"records":200}}""")
        assertTrue((NativeScheduleJson.history(key, empty) as NativeScheduleHistoryResult.Page).value.loaded)
        val failed = NativeScheduleJson.history(key, JSONObject().put("id", key.id).put("code", "delivery_cursor_not_found"))
        assertEquals("delivery_cursor_not_found", (failed as NativeScheduleHistoryResult.Rejected).code)
        assertCode("malformed_response") { NativeScheduleJson.history(key, empty.put("nextBefore", "phantom")) }
    }

    @Test fun recurrenceFrequencyAndMillisecondPrecisionHaveSeparateValidation() {
        val draft = NativeScheduleEdits.create("session-a", now, "UTC").copy(title = "Task", prompt = "Instruction")
        NativeScheduleEdits.validate(draft.copy(timing = draft.timing.copy(kind = "after", seconds = "1")), now)
        assertCode("frequency_too_high") { NativeScheduleEdits.validate(draft.copy(timing = draft.timing.copy(kind = "every", seconds = "59")), now) }
        assertEquals("09:00:00.125", NativeScheduleEdits.normalizedTime("09:00:00.125"))
        assertCode("invalid_rule") { NativeScheduleEdits.normalizedTime("09:00:00.1259") }
    }

    @Test fun oneShotGapIsRejectedAndOverlapChoosesEarlierInstant() {
        val draft = NativeScheduleEdits.create("session-a", now, "America/New_York").copy(title = "Task", prompt = "Instruction")
        assertCode("invalid_rule") { NativeScheduleEdits.validate(draft.copy(timing = draft.timing.copy(date = "2030-03-10", time = "02:30:00")), now) }
        val overlap = draft.copy(timing = draft.timing.copy(date = "2030-11-03", time = "01:30:00"))
        NativeScheduleEdits.validate(overlap, now)
        val earlier = NativeScheduleRecord("new", "at", "Task", "Instruction", "2030-11-03T05:30:00.000Z")
        assertTrue(NativeScheduleEdits.matches(overlap, earlier))
        assertFalse(NativeScheduleEdits.matches(overlap, earlier.copy(scheduledAt = "2030-11-03T06:30:00.000Z")))
    }

    @Test fun anOversizedRequestFailsBeforeDispatchWithoutTruncatingTheInstruction() {
        val draft = NativeScheduleEdits.create("session-a", now, "UTC").copy(title = "Task", prompt = "字".repeat(100_000))
        assertCode("NATIVE_SCHEDULES_TOO_LARGE") { NativeScheduleJson.createValue(draft) }
        assertEquals(100_000, draft.prompt.length)
    }

    private fun assertCode(code: String, action: () -> Unit) {
        try { action(); fail("Expected $code") } catch (error: NativeApiException) { assertEquals(code, error.code) }
    }
}
