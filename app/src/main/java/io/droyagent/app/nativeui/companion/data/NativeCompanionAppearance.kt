package io.droyagent.app.nativeui.companion.data

import android.content.SharedPreferences
import io.droyagent.app.nativeui.data.NativeApiClient
import org.json.JSONException
import org.json.JSONObject

/** Display-only hints; live profiles always win and no conversation or authorization is restored here. */
internal object NativeCompanionAppearance {
    private const val PREFIX = "background:"

    fun read(values: Map<String, *>): Map<String, String> = values.entries.mapNotNull { (key, value) ->
        if (!key.startsWith(PREFIX)) return@mapNotNull null
        val owner = key.removePrefix(PREFIX)
        normalize(owner, value as? String)?.let { owner to it }
    }.toMap()

    fun snapshot(companions: List<NativeCompanion>): Map<String, String> = companions.mapNotNull { companion ->
        normalize(companion.id, companion.chatBackgroundJson)?.let { companion.id to it }
    }.toMap()

    fun save(preferences: SharedPreferences, backgrounds: Map<String, String>) {
        val before = preferences.all.filterKeys { it.startsWith(PREFIX) }
        val after = backgrounds.mapKeys { PREFIX + it.key }
        if (before == after) return
        preferences.edit().apply {
            for (key in before.keys - after.keys) remove(key)
            for ((key, value) in after) if (before[key] != value) putString(key, value)
        }.apply()
    }

    private fun normalize(owner: String, raw: String?): String? {
        if (owner.isBlank() || raw == null || raw.length > 4096) return null
        // A damaged display hint can be discarded; the authoritative profile will replace it.
        return try {
            val value = JSONObject(raw)
            val result = when (value.optString("type")) {
                "preset" -> {
                    val id = value.optString("id")
                    if (!id.matches(Regex("0[1-8]"))) return null
                    JSONObject().put("type", "preset").put("id", id)
                }
                "upload" -> {
                    val id = value.optString("id")
                    val url = value.optString("url")
                    if (!id.matches(Regex("[A-Za-z0-9_.:-]{1,160}")) ||
                        url != "/droy/companions/${NativeApiClient.encodeSegment(owner)}/attachments/${NativeApiClient.encodeSegment(id)}") return null
                    JSONObject().put("type", "upload").put("id", id).put("url", url)
                }
                else -> return null
            }
            value.optJSONObject("position")?.let { position ->
                val x = position.optDouble("x", 50.0)
                val y = position.optDouble("y", 50.0)
                if (x.isFinite() && y.isFinite()) result.put("position", JSONObject().put("x", x.coerceIn(0.0, 100.0)).put("y", y.coerceIn(0.0, 100.0)))
            }
            result.toString()
        } catch (_: JSONException) { null }
    }
}
