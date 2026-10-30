package io.droyagent.app.nativeui.companion.maintenance

import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.UUID

/** A bounded plan for this one deletion; no unrelated preference values are journalled. */
internal object NativeCompanionRetirementPlan {
    val preferenceNames = listOf("native_companions", "native_media", "native_share_preview", "native_share_inbox",
        "native_voice_input", "native_standard_voice", "native_companion_creation", "native_workbench")
    fun create(operation: String, companion: String, epoch: String, preferences: Map<String, Map<String, Any?>>, nativeImports: JSONArray, legacyImports: JSONArray): JSONObject {
        val changes = JSONArray(); val target = JSONArray(); val retained = JSONArray()
        val targetImports = linkedSetOf<String>(); val retainedImports = linkedSetOf<String>(); val captures = linkedSetOf<String>()
        fun record(source: String, value: Any?, removed: Boolean) {
            val refs = references(value)
            (if (removed) targetImports else retainedImports).addAll(strings(refs.getJSONArray("imports")))
            (if (removed) target else retained).put(JSONObject().put("source", source).put("value", refs))
        }
        fun change(pref: String, key: String, old: Any?, after: String?) {
            changes.put(JSONObject().put("preferences", pref).put("key", key).put("before", digest(encoded(old)))
                .put("after", after ?: JSONObject.NULL))
        }
        val companionPrefs = preferences["native_companions"].orEmpty()
        val externalKeys = companionPrefs.filterKeys { it == "pending:$companion" || it.startsWith("pending:$companion:") }
            .values.mapNotNull { (parse(it) as? JSONObject)?.optString("clientId")?.takeIf(String::isNotEmpty)?.let { id -> "external:$id" } }.toSet()
        for ((pref, entries) in preferences.toSortedMap()) for ((key, old) in entries.toSortedMap()) {
            val source = "$pref/$key"; val value = if (pref == "native_workbench" && key.startsWith("draft:")) old else parse(old)
            val structured = (pref == "native_companions" && (key.startsWith("draft:") || key.startsWith("pending:") || key.startsWith("anchor:"))) ||
                pref == "native_media" && key == "native-media-v1" || pref == "native_share_preview" && key == "preview" ||
                pref == "native_voice_input" && key == "state" || pref in setOf("native_standard_voice", "native_companion_creation") && key == "journal" ||
                pref == "native_workbench" && (key.startsWith("attachments:") || key.startsWith("pending:"))
            require(!structured || value is JSONObject || value is JSONArray) { "COMPANION_MAINTENANCE_STORAGE" }
            when {
                pref == "native_companions" && (key in setOf("draft:$companion", "pending:$companion", "anchor:$companion") || key in externalKeys || key.startsWith("pending:$companion:") ||
                    key.startsWith("media-delivered:$companion:") || key == "selectedCompanionId" && old == companion) -> {
                    record(source, value, true); change(pref, key, old, null)
                }
                pref == "native_media" && key == "native-media-v1" -> {
                    val original = value as? JSONObject ?: error("COMPANION_MAINTENANCE_STORAGE")
                    val after = JSONObject(original.toString()); var changed = false
                    original.optJSONObject("selection")?.takeIf { it.optString("ownerId") == companion && it.optString("kind") != "WORKBENCH_DRAFT" }?.let {
                        record("$source/selection", it, true)
                        val import = it.optString("captureId").takeUnless { id -> id.isEmpty() || id == "null" } ?: it.getString("id")
                        if (it.optString("kind") == "ATTACHMENTS") targetImports.add(import)
                        if (it.optBoolean("camera")) captures.add(it.getString("captureId"))
                        after.put("selection", JSONObject.NULL)
                        if (after.optJSONObject("cameraAlbumResult")?.optString("operationId") == it.optString("id")) after.put("cameraAlbumResult", JSONObject.NULL)
                        changed = true
                    }
                    for (collection in listOf("avatars", "backgrounds")) {
                        val remaining = JSONArray()
                        original.optJSONArray(collection)?.let { rows -> for (i in 0 until rows.length()) {
                            val row = rows.getJSONObject(i)
                            if (row.optString("ownerId") == companion) { record("$source/$collection/$i", row, true); changed = true }
                            else remaining.put(row)
                        } }
                        after.put(collection, remaining)
                    }
                    if (changed) { after.put("revision", original.optLong("revision") + 1); change(pref, key, old, after.toString()) }
                    record(source, after, false)
                }
                pref == "native_share_preview" && key == "preview" && (value as? JSONObject)?.optJSONObject("destination")?.optString("companionId") == companion -> {
                    val after = JSONObject(value.toString()).put("destination", JSONObject.NULL).put("uploaded", JSONObject())
                        .put("requestId", UUID.randomUUID().toString()).put("attempted", false).put("accepted", false)
                        .put("resultSessionId", JSONObject.NULL).put("revision", value.optLong("revision") + 1)
                    record("$source/uploaded", value.optJSONObject("uploaded"), true)
                    record(source, after, false); change(pref, key, old, after.toString())
                }
                pref == "native_voice_input" && key == "state" && (value as? JSONObject)?.optJSONObject("target")?.optString("companionId") == companion -> {
                    val after = JSONObject(value.toString()).put("target", JSONObject.NULL).put("uploadedVoice", JSONObject.NULL)
                        .put("sendAttempted", false).put("accepted", false).put("rawSubmission", false)
                        .put("resultSessionId", JSONObject.NULL).put("revision", value.optLong("revision") + 1)
                    record("$source/uploadedVoice", value.optJSONObject("uploadedVoice"), true)
                    record(source, after, false); change(pref, key, old, after.toString())
                }
                else -> record(source, value, false)
            }
        }
        // Media operation ids and the inbox FIFO are import identities even without an attachment row yet.
        val media = parse(preferences["native_media"]?.get("native-media-v1")) as? JSONObject
        media?.optJSONObject("selection")?.takeUnless { it.optString("ownerId") == companion && it.optString("kind") != "WORKBENCH_DRAFT" }?.let {
            retainedImports.add(it.optString("captureId").takeUnless { id -> id.isEmpty() || id == "null" } ?: it.getString("id"))
        }
        (parse(preferences["native_share_inbox"]?.get("ids")) as? JSONArray)?.let { retainedImports.addAll(strings(it)) }
        val imports = JSONArray()
        for (i in 0 until nativeImports.length()) {
            val row = nativeImports.getJSONObject(i); val id = row.getString("id")
            if (id in targetImports && id !in retainedImports) {
                require(row.optString("consumer", "work") == "work" && !row.has("targetSessionId")) { "COMPANION_MAINTENANCE_IMPORT_OWNER" }
                imports.put(JSONObject().put("namespace", "native").put("id", id).put("consumer", "work").put("manifestDigest", digest(canonical(row))))
            }
        }
        for (i in 0 until legacyImports.length()) {
            val row = legacyImports.getJSONObject(i)
            if (row.optString("consumer") == "companion" && row.optString("targetSessionId") == "im:$companion") {
                require(row.getString("id") !in retainedImports) { "COMPANION_MAINTENANCE_SHARED_IMPORT" }
                imports.put(JSONObject().put("namespace", "legacy").put("id", row.getString("id")).put("consumer", "companion").put("manifestDigest", digest(canonical(row))))
                record("legacy-import/${row.getString("id")}", row, true)
            }
        }
        val views = JSONArray()
        preferences["native_workbench"].orEmpty().forEach { (key, value) ->
            val session = when {
                key == "selectedSessionId" -> value as? String
                key.startsWith("voice-duration:") -> key.removePrefix("voice-duration:").substringBeforeLast(':')
                listOf("readingKey:", "readingOffset:", "followingTail:", "historyBudget:", "workReadTurn:").any(key::startsWith) -> key.substringAfter(':')
                else -> null
            }
            val workspace = if (key == "selectedWorkspaceId") value as? String else null
            if (session != null || workspace != null) views.put(JSONObject().put("key", key)
                .put("sessionId", session ?: JSONObject.NULL).put("workspaceId", workspace ?: JSONObject.NULL)
                .put("before", digest(encoded(value))))
        }
        val work = preferences["native_workbench"].orEmpty()
        val selected = work["selectedSessionId"] as? String
        val recovery = selected?.let { id ->
            JSONObject().put("sessionId", id).put("draftId", UUID.randomUUID().toString())
                .put("text", work["draft:$id"] as? String ?: "")
                .put("attachments", work["attachments:$id"] as? String ?: "[]")
                .put("textBefore", digest(encoded(work["draft:$id"])))
                .put("attachmentsBefore", digest(encoded(work["attachments:$id"])))
        }
        val plan = JSONObject().put("version", 1).put("operationId", operation).put("companionId", companion).put("admissionEpoch", epoch)
            .put("workbenchRecovery", recovery ?: JSONObject.NULL).put("workbenchViews", views).put("changes", changes).put("imports", imports).put("captures", JSONArray(captures.toList()))
        val retirementDigest = digest(canonical(plan))
        val snapshot = JSONObject().put("version", 1).put("complete", true).put("operationId", operation).put("companionId", companion)
            .put("admissionEpoch", epoch).put("retirementDigest", retirementDigest).put("retained", retained).put("target", target)
        return plan.put("snapshot", snapshot).put("retirementDigest", retirementDigest)
    }
    fun removedWorkbenchViews(plan: JSONObject, receipt: JSONObject): List<JSONObject> {
        val sessions = receipt.optJSONArray("removedSessionIds")?.let(::strings).orEmpty().toSet()
        val workspaces = receipt.optJSONArray("workspaceIds")?.let(::strings).orEmpty().toSet()
        val rows = plan.optJSONArray("workbenchViews") ?: return emptyList()
        return (0 until rows.length()).map(rows::getJSONObject).filter {
            it.optString("sessionId") in sessions || it.optString("workspaceId") in workspaces
        }
    }
    fun references(value: Any?): JSONObject {
        val refs = linkedSetOf<String>(); val imports = linkedSetOf<String>()
        fun visit(item: Any?, key: String? = null) {
            when (item) {
                is JSONObject -> item.keys().forEach { visit(item.get(it), it) }
                is JSONArray -> (0 until item.length()).forEach { visit(item.get(it), key) }
                is String -> when {
                    key == "importId" -> imports.add(item)
                    item.matches(Regex("sha256:[a-f0-9]{64}")) || item.contains("/droy/companions/") && item.contains("/attachments/") -> refs.add(item)
                    key == "attachmentId" && item.matches(Regex("[a-f0-9]{64}")) -> refs.add("sha256:$item")
                }
            }
        }
        visit(value)
        return JSONObject().put("references", JSONArray(refs.toList())).put("imports", JSONArray(imports.toList()))
    }
    fun parse(value: Any?): Any? = if (value is String && (value.startsWith("{") || value.startsWith("["))) {
        try { if (value.startsWith("{")) JSONObject(value) else JSONArray(value) } catch (_: org.json.JSONException) { value }
    } else value
    fun encoded(value: Any?): String = when (value) {
        null -> "null"
        is Set<*> -> canonical(JSONArray(value.map { it.toString() }.sorted()))
        else -> canonical(JSONObject.wrap(value))
    }
    fun canonical(value: Any?): String = when (value) {
        null, JSONObject.NULL -> "null"
        is JSONObject -> value.keys().asSequence().toList().sorted().joinToString(",", "{", "}") { JSONObject.quote(it) + ":" + canonical(value.get(it)) }
        is JSONArray -> (0 until value.length()).joinToString(",", "[", "]") { canonical(value.get(it)) }
        is String -> JSONObject.quote(value)
        else -> value.toString()
    }
    fun digest(value: String): String = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    private fun strings(value: JSONArray): List<String> = (0 until value.length()).map { value.getString(it) }
}
