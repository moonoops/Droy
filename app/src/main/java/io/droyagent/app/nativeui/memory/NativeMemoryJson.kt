package io.droyagent.app.nativeui.memory

import io.droyagent.app.nativeui.data.NativeApiException
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

internal object NativeMemoryJson {
    private fun invalid(): Nothing = throw NativeApiException("malformed_response")
    private fun JSONObject.string(key: String): String = get(key) as? String ?: invalid()
    private fun JSONObject.bool(key: String): Boolean = get(key) as? Boolean ?: invalid()
    private fun JSONObject.optionalString(key: String): String? = if (!has(key) || isNull(key)) null else string(key)
    private fun JSONObject.optionalBool(key: String): Boolean? = if (!has(key) || isNull(key)) null else bool(key)
    private fun JSONObject.optionalObject(key: String): JSONObject? = if (!has(key) || isNull(key)) null else getJSONObject(key)
    private fun JSONObject.number(key: String): Double {
        val value = get(key) as? Number ?: invalid()
        return value.toDouble().takeIf { it.isFinite() } ?: invalid()
    }
    private fun JSONObject.long(key: String): Long = number(key).let { if (it < 0 || it > 9_007_199_254_740_991.0 || it % 1.0 != 0.0) invalid() else it.toLong() }
    private fun JSONObject.optionalLong(key: String): Long? = if (!has(key) || isNull(key)) null else long(key)
    private fun JSONObject.optionalNumber(key: String): Double? = if (!has(key) || isNull(key)) null else number(key)
    private fun JSONArray.strings(): List<String> = (0 until length()).map { get(it) as? String ?: invalid() }
    private fun JSONObject.strings(key: String): List<String> = getJSONArray(key).strings()
    private inline fun <T> decode(block: () -> T): T = try { block() } catch (error: JSONException) { throw NativeApiException("malformed_response", error) }

    fun status(value: JSONObject): NativeMemoryStatus = decode {
        val settings = value.getJSONObject("settings")
        val gate = value.getJSONObject("gate")
        val managementAvailable = value.bool("managementAvailable")
        val store = value.optionalObject("store")
        if (managementAvailable && store == null) invalid()
        val counts = store?.getJSONObject("counts")
        NativeMemoryStatus(value.bool("available"), managementAvailable, gate.bool("ready"), gate.string("reason"),
            gate.optionalBool("rootAuthorized"), NativeMemorySettings(settings.bool("enabled"), settings.bool("recallEnabled"), settings.bool("automaticExtraction")),
            value.optionalObject("capture")?.let(::capture), value.bool("maintenanceAvailable"), value.optionalObject("maintenance")?.let(::maintenance),
            counts?.keys()?.asSequence()?.associateWith { counts.long(it) }.orEmpty(), store?.optionalObject("storage")?.optionalBool("limited"),
            store?.optionalObject("storage")?.optionalLong("bytes"))
    }
    private fun capture(value: JSONObject): NativeMemoryCapture = decode {
        val config = value.getJSONObject("configuration")
        NativeMemoryCapture(value.string("state"), NativeMemoryCaptureConfig(config.bool("enabled"), config.bool("paused"), config.bool("activityEnabled"),
            config.bool("pageTextEnabled"), config.bool("deviceStateEnabled"), config.bool("locationEnabled"), config.strings("allowedPackages"), config.strings("excludedPackages"), config.optBoolean("allApplications")),
            value.bool("locationPermission"), value.bool("backgroundLocationPermission"), value.optionalString("activityState"), value.optionalString("locationState"), value.optionalString("failure"))
    }
    private fun maintenance(value: JSONObject): NativeMemoryMaintenance = decode {
        val held = if (!value.has("heldSources") || value.isNull("heldSources")) null else value.getJSONArray("heldSources")
        val budget = value.optionalObject("budget")
        NativeMemoryMaintenance(value.long("pending"), value.long("held"), value.optionalBool("busy") ?: false, value.bool("stopped"),
            if (held == null) emptyList() else (0 until held.length()).map { held.getJSONObject(it).let { row ->
                NativeMemoryHeld(row.string("id"), row.optionalString("sourceId"), row.optionalString("revision"), row.optionalString("code")) } },
            budget?.optionalLong("calls"), budget?.optionalLong("tokens"), value.optionalString("lastCode"))
    }
    fun endpoints(value: Any): List<NativeMemoryEndpoint> = decode {
        val rows = value as? JSONArray ?: invalid()
        (0 until rows.length()).map { index -> rows.getJSONObject(index).let { row ->
            NativeMemoryEndpoint(row.string("id"), row.string("purpose"), row.string("label"), row.string("model"), row.string("url"),
                row.bool("authorized"), row.strings("categories"), row.optionalString("configVersion")) } }.also { result ->
            if (result.map { it.key }.distinct().size != result.size) invalid()
        }
    }
    fun apps(value: Any): NativeMemoryApps = decode {
        val objectValue = value as? JSONObject ?: invalid()
        val rows = objectValue.getJSONArray("items")
        NativeMemoryApps((0 until rows.length()).map { index -> rows.getJSONObject(index).let { NativeMemoryApp(it.string("packageName"), it.string("label")) } }, objectValue.bool("truncated"))
    }
    fun query(value: JSONObject): NativeMemoryQueryResult = decode {
        val items = value.getJSONArray("items")
        val coverage = value.getJSONObject("coverage")
        NativeMemoryQueryResult((0 until items.length()).map { record(items.getJSONObject(it)) }, value.long("total"), value.optionalString("nextCursor"),
            value.long("revision"), value.bool("truncated"), NativeMemoryCoverage(coverage.long("gaps"), coverage.long("unresolvedLocations"), coverage.long("mockLocations"), coverage.bool("continuousObservationVerified")))
            .also { if (it.items.map { record -> record.recordId }.distinct().size != it.items.size || it.nextCursor != null && it.items.isEmpty()) invalid() }
    }
    fun record(value: JSONObject): NativeMemoryRecord = decode {
        if (value.long("schemaVersion") != 1L) invalid()
        val payload = if (value.isNull("payload")) null else value.getJSONObject("payload")
        val retention = value.getJSONObject("retention")
        val derived = value.getJSONArray("derivedFrom")
        NativeMemoryRecord(value.string("recordId"), value.string("revisionId"), value.string("kind"), value.string("state"), payload?.toString(),
            value.string("occurredAt"), value.optionalString("recordedAt"), value.string("assertion"), value.string("actor"), retention.string("mode"), retention.optionalString("expiresAt"),
            value.strings("sourceRefs"), value.strings("lineageRefs"), (0 until derived.length()).map { derived.getJSONObject(it).let { row ->
                NativeMemoryDependency(row.string("recordId"), row.string("revisionId")) } }, value.getJSONObject("privacy").strings("categories"),
            value.optionalString("validFrom"), value.optionalString("validUntil"), payload?.optionalString("text"), payload?.optionalString("eventType"),
            payload?.optionalString("appLabel"), payload?.optionalString("appId"), payload?.optionalString("placeName"), payload?.optionalString("city"),
            payload?.optionalNumber("latitude"), payload?.optionalNumber("longitude"), payload?.optionalNumber("accuracyMeters"),
            payload?.optionalBool("stale"), payload?.optionalBool("isMock"), payload?.optionalBool("approximate"))
    }
    fun recordOrNull(value: Any?): NativeMemoryRecord? = if (value == null || value === JSONObject.NULL) null else record(value as? JSONObject ?: invalid())

    fun filterValue(value: NativeMemoryFilter, cursor: String? = null): JSONObject = JSONObject().apply {
        when (value.group) {
            NativeMemoryGroup.MEMORY -> put("kinds", JSONArray(listOf("fact", "preference", "decision", "task_summary")))
            NativeMemoryGroup.ACTIVITY -> { put("kinds", JSONArray(listOf("event"))); put("eventTypes", JSONArray(listOf("activity", "device", "gap", "note"))) }
            NativeMemoryGroup.PLACES -> { put("kinds", JSONArray(listOf("event"))); put("eventTypes", JSONArray(listOf("location", "visit"))) }
        }
        value.text.trim().takeIf { it.isNotEmpty() }?.let { put("text", it) }
        value.from.trim().takeIf { it.isNotEmpty() }?.let { put("from", it) }
        value.to.trim().takeIf { it.isNotEmpty() }?.let { put("to", it) }
        cursor?.let { put("cursor", it) }
    }
    fun mutation(draft: NativeMemoryDraft, operationId: String): NativeMemoryMutation {
        if (!draft.valid) throw NativeApiException("MEMORY_INVALID_INPUT")
        val record = draft.original
        val args = JSONObject().put("operationId", operationId)
        if (record == null) {
            if (draft.kind !in setOf("fact", "preference", "decision", "task_summary")) throw NativeApiException("MEMORY_INVALID_KIND")
            args.put("kind", draft.kind).put("text", draft.text.trim())
        } else {
            if (!record.editable) throw NativeApiException("MEMORY_READ_ONLY")
            val payload = JSONObject(record.payloadJson!!)
            if (record.location) {
                for ((key, text) in listOf("placeName" to draft.place, "city" to draft.city)) {
                    if (text.trim().isEmpty()) payload.remove(key) else payload.put(key, text.trim())
                }
            } else payload.put("text", draft.text.trim())
            args.put("recordId", record.recordId).put("expectedRevisionId", record.revisionId).put("patch", JSONObject().put("payload", payload))
        }
        return NativeMemoryMutation(if (record == null) "record" else "revise", args.toString(), true)
    }
}
