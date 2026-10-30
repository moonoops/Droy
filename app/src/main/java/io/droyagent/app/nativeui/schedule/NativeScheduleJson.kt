package io.droyagent.app.nativeui.schedule

import io.droyagent.app.nativeui.data.NativeApiException
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.time.Instant
import java.time.format.DateTimeParseException

internal object NativeScheduleJson {
    private val kinds = setOf("after", "at", "every", "daily", "weekly", "cron")
    private val common = setOf("id", "kind", "title", "prompt", "scheduledAt")
    private fun malformed(): Nothing = throw NativeApiException("malformed_response")

    fun catalog(value: Any): List<NativeScheduleEntry> = decode {
        val array = value as? JSONArray ?: malformed()
        val rows = objects(array).map { row ->
            val status = string(row, "status").also { if (it !in setOf("active", "inactive")) malformed() }
            NativeScheduleEntry(record(row, catalog = true), identifier(row, "sessionId"), status,
                row.optionalObject("lastDelivery")?.let { delivery(it, includePrompt = false) })
        }
        if (rows.map { it.record.id }.distinct().size != rows.size) malformed()
        rows
    }

    fun record(value: JSONObject, catalog: Boolean = false): NativeScheduleRecord = decode {
        val kind = string(value, "kind").also { if (it !in kinds) malformed() }
        val ruleFields = when (kind) {
            "after" -> setOf("afterSeconds")
            "every" -> setOf("everySeconds")
            "daily" -> setOf("time", "timeZone")
            "weekly" -> setOf("time", "timeZone", "weekdays")
            "cron" -> setOf("expression", "timeZone")
            else -> emptySet()
        }
        val allowed = common + ruleFields + if (catalog) setOf("sessionId", "status", "lastDelivery") else emptySet()
        if (value.keys().asSequence().any { it !in allowed }) malformed()
        val title = string(value, "title").also { if (it.isBlank() || it.trim() != it || it.length > 120) malformed() }
        val prompt = string(value, "prompt").also { if (it.isBlank() || it.trim() != it) malformed() }
        val weekdays = if (kind == "weekly") integers(value.getJSONArray("weekdays")) else emptyList()
        if (kind == "weekly" && (weekdays.isEmpty() || weekdays != weekdays.distinct().sorted() || weekdays.any { it !in 1..7 })) malformed()
        NativeScheduleRecord(identifier(value, "id"), kind, title, prompt, instant(value, "scheduledAt"),
            afterSeconds = if (kind == "after") integer(value, "afterSeconds", 1) else null,
            everySeconds = if (kind == "every") integer(value, "everySeconds", 60) else null,
            time = if (kind in setOf("daily", "weekly")) string(value, "time") else null,
            timeZone = if (kind in setOf("daily", "weekly", "cron")) string(value, "timeZone").also { if (it.isBlank()) malformed() } else null,
            weekdays = weekdays, expression = if (kind == "cron") string(value, "expression") else null)
    }

    fun recordValue(value: NativeScheduleRecord): JSONObject = JSONObject().put("id", value.id).put("kind", value.kind)
        .put("title", value.title).put("prompt", value.prompt).put("scheduledAt", value.scheduledAt).apply {
            when (value.kind) {
                "after" -> put("afterSeconds", requireNotNull(value.afterSeconds))
                "every" -> put("everySeconds", requireNotNull(value.everySeconds))
                "daily" -> put("time", requireNotNull(value.time)).put("timeZone", requireNotNull(value.timeZone))
                "weekly" -> put("time", requireNotNull(value.time)).put("timeZone", requireNotNull(value.timeZone)).put("weekdays", JSONArray(value.weekdays))
                "cron" -> put("expression", requireNotNull(value.expression)).put("timeZone", requireNotNull(value.timeZone))
                "at" -> Unit
                else -> malformed()
            }
        }

    fun createValue(draft: NativeScheduleDraft): JSONObject = JSONObject().put("sessionId", draft.sessionId)
        .put("request", JSONObject().put("title", draft.title.trim()).put("prompt", draft.prompt.trim()).apply {
            val timing = timingValue(draft.timing)
            when (draft.timing.kind) {
                "after" -> put("after_seconds", draft.timing.seconds.toLong())
                "at" -> put("at", timing.get("at"))
                "every" -> put("every_seconds", draft.timing.seconds.toLong())
                "daily", "weekly", "cron" -> put(draft.timing.kind, timing.get(draft.timing.kind))
            }
        }).also(::bounded)

    fun updateValue(request: NativeScheduleUpdate): JSONObject = keyValue(request.key)
        .put("expected", recordValue(request.expected)).apply {
            request.title?.let { put("title", it) }
            request.prompt?.let { put("prompt", it) }
            request.change?.let { put("change", timingValue(it)) }
        }.also(::bounded)

    private fun timingValue(value: NativeScheduleTiming): JSONObject = JSONObject().put("kind", value.kind).apply {
        when (value.kind) {
            "at" -> put("at", JSONObject().put("date", value.date).put("time", NativeScheduleEdits.normalizedTime(value.time)).put("time_zone", value.timeZone.trim()))
            "every" -> put("every_seconds", value.seconds.toLong())
            "daily" -> put("daily", calendarValue(value))
            "weekly" -> put("weekly", calendarValue(value).put("weekdays", JSONArray(value.weekdays.distinct().sorted())))
            "cron" -> put("cron", JSONObject().put("expression", value.expression.trim()).put("time_zone", value.timeZone.trim()))
        }
    }

    private fun calendarValue(value: NativeScheduleTiming) = JSONObject().put("time", NativeScheduleEdits.normalizedTime(value.time))
        .put("time_zone", value.timeZone.trim())
    fun keyValue(key: NativeScheduleKey): JSONObject = JSONObject().put("sessionId", key.sessionId).put("id", key.id)

    fun updateResult(key: NativeScheduleKey, value: JSONObject): NativeScheduleUpdateResult = decode {
        if (value.has("code")) return@decode NativeScheduleUpdateResult.Rejected(string(value, "code"))
        if (identifier(value, "id") != key.id) malformed()
        val updated = value.get("updated") as? Boolean ?: malformed()
        val record = record(value.getJSONObject("record"))
        if (record.id != key.id) malformed()
        NativeScheduleUpdateResult.Record(record, updated)
    }

    fun deleteResult(key: NativeScheduleKey, value: JSONObject): NativeScheduleDeleteResult = decode {
        if (identifier(value, "id") != key.id) malformed()
        val deleted = value.get("deleted") as? Boolean ?: malformed()
        val code = if (deleted) null else string(value, "code").also { if (it != "schedule_not_found") malformed() }
        NativeScheduleDeleteResult(deleted, code)
    }

    fun history(key: NativeScheduleKey, value: JSONObject): NativeScheduleHistoryResult = decode {
        if (identifier(value, "id") != key.id) malformed()
        if (value.has("code")) return@decode NativeScheduleHistoryResult.Rejected(string(value, "code"))
        val rows = objects(value.getJSONArray("records")).map { delivery(it, includePrompt = true) }
        if (rows.map { it.messageId }.distinct().size != rows.size) malformed()
        val next = value.optionalString("nextBefore")
        if (next != null && (rows.isEmpty() || next != rows.last().messageId)) malformed()
        val retention = value.getJSONObject("retention")
        NativeScheduleHistoryResult.Page(NativeScheduleHistory(rows, next,
            earlierRecordsUnavailable = value.get("earlierRecordsUnavailable") as? Boolean ?: malformed(),
            earlierRecordsPruned = value.get("earlierRecordsPruned") as? Boolean ?: malformed(),
            retentionDays = count(retention, "days"), retentionRecords = count(retention, "records"), loaded = true))
    }

    fun targets(workspaces: JSONObject, sessions: JSONObject): NativeScheduleTargets = decode {
        val managed = strings(sessions.getJSONArray("managedSessionIds")).toSet()
        val managedSpaces = strings(workspaces.getJSONArray("managedWorkspaceIds")).toSet()
        val archived = strings(workspaces.getJSONArray("archivedSessionIds")).toSet()
        val spaceRows = objects(workspaces.getJSONArray("items"))
        val groups = spaceRows.flatMap { space -> strings(space.getJSONArray("sessionIds")).map { id -> id to space } }.toMap()
        val names = mutableMapOf<String, String>()
        val eligible = objects(sessions.getJSONArray("items")).mapNotNull { row ->
            val id = identifier(row, "sessionId")
            val title = row.optJSONObject("projections")?.optJSONObject("values")?.optionalString("title")?.takeIf(String::isNotBlank) ?: id
            if (names.put(id, title) != null) malformed()
            val group = groups[id]
            val origin = row.optionalString("origin")
            val format = row.optionalString("formatStatus")
            if (origin != null && origin != "subagent" || format != null && format !in setOf("current", "migration-required")) malformed()
            val cwd = row.optionalString("cwd")
            if (id in managed || id in archived || cwd.isNullOrBlank() || origin == "subagent" || format == "migration-required"
                || group?.let { identifier(it, "workspaceId") in managedSpaces } == true) return@mapNotNull null
            NativeScheduleTarget(id, title, group?.let { string(it, "title") })
        }
        NativeScheduleTargets(eligible, names)
    }

    private fun delivery(value: JSONObject, includePrompt: Boolean): NativeScheduleDelivery = NativeScheduleDelivery(
        instant(value, "scheduledAt"), instant(value, "deliveredAt"), identifier(value, "messageId"),
        if (includePrompt) value.optionalString("prompt") else null)

    private fun bounded(value: JSONObject) {
        if (value.toString().toByteArray(Charsets.UTF_8).size > 256 * 1024) throw NativeApiException("NATIVE_SCHEDULES_TOO_LARGE")
    }
    private fun instant(value: JSONObject, key: String): String = string(value, key).also {
        if (!it.matches(Regex("\\d{4}-\\d{2}-\\d{2}T.+Z"))) malformed()
        try { Instant.parse(it) } catch (_: DateTimeParseException) { malformed() }
    }
    private fun identifier(value: JSONObject, key: String): String = string(value, key).also {
        if (it.isBlank() || it.trim() != it || it.length > 1024 || it.any { char -> char.code < 32 || char.code == 127 }) malformed()
    }
    private fun string(value: JSONObject, key: String): String = value.get(key) as? String ?: malformed()
    private fun JSONObject.optionalString(key: String): String? = if (!has(key)) null else get(key) as? String ?: malformed()
    private fun JSONObject.optionalObject(key: String): JSONObject? = if (!has(key)) null else get(key) as? JSONObject ?: malformed()
    private fun integer(value: JSONObject, key: String, minimum: Long): Long = number(value.get(key), minimum)
    private fun count(value: JSONObject, key: String): Int = integer(value, key, 1).also { if (it > Int.MAX_VALUE) malformed() }.toInt()
    private fun number(value: Any, minimum: Long): Long {
        val number = value as? Number ?: malformed()
        val decimal = number.toDouble()
        val integer = number.toLong()
        if (!decimal.isFinite() || integer.toDouble() != decimal || integer < minimum || integer > 9_007_199_254_740_991L) malformed()
        return integer
    }
    private fun integers(array: JSONArray): List<Int> = (0 until array.length()).map { number(array.get(it), 0).also { n -> if (n > Int.MAX_VALUE) malformed() }.toInt() }
    private fun strings(array: JSONArray): List<String> = (0 until array.length()).map { array.get(it) as? String ?: malformed() }
    private fun objects(array: JSONArray): List<JSONObject> = (0 until array.length()).map { array.get(it) as? JSONObject ?: malformed() }
    private inline fun <T> decode(block: () -> T): T = try { block() } catch (error: JSONException) { throw NativeApiException("malformed_response", error) }
}
