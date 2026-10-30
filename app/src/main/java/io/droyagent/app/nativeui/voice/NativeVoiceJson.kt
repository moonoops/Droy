package io.droyagent.app.nativeui.voice

import io.droyagent.app.nativeui.data.NativeApiException
import org.json.JSONObject

internal object NativeVoiceJson {
    fun parse(value: JSONObject): NativeVoiceSnapshot {
        val config = value.getJSONObject("config")
        val tts = config.getJSONObject("tts")
        val runtime = value.getJSONObject("runtime")
        val wake = value.getJSONObject("wake")
        val voices = value.getJSONObject("voices")
        val resources = value.getJSONObject("resources")
        val preview = value.getJSONObject("preview")
        val actualWake = if (wake.getBoolean("available")) wakeDraft(wake) else null
        val readout = NativeVoiceTtsDraft(tts.getString("provider"), tts.getString("voiceId"), tts.getDouble("rate"), tts.getString("url"), tts.getString("model"))
        if (readout.provider !in setOf("system", "melo", "qwen3", "openai") || readout.rate !in 0.5..2.0 || readout.provider == "qwen3" && readout.rate != 1.0)
            throw NativeApiException("malformed_response")
        val voiceItems = if (voices.getBoolean("available")) voices.getJSONArray("items").let { rows -> (0 until rows.length()).map { index ->
            val row = rows.getJSONObject(index)
            NativeReadoutVoice(row.getString("id"), row.getString("provider"), row.getString("voiceId"), row.getString("name"),
                if (row.has("networkRequired")) row.getBoolean("networkRequired") else null, row.optString("language").takeIf { it.isNotBlank() })
        } } else emptyList()
        val modelItems = if (resources.getBoolean("available")) resources.getJSONArray("items").let { rows -> (0 until rows.length()).map { index ->
            val row = rows.getJSONObject(index); NativeVoiceResource(row.getString("provider"), progress(row))
        } } else emptyList()
        return NativeVoiceSnapshot(config.getJSONObject("entries").getBoolean("standard"), readout, tts.getBoolean("hasKey"), wakeDraft(config.getJSONObject("wake")),
            runtime.getBoolean("available"), runtime.optString("state", "unknown"), runtime.optBoolean("speaking"), runtime.optBoolean("failed"),
            wake.getBoolean("available"), actualWake, wake.optString("state", "unknown"), wake.optBoolean("failed"),
            wake.optBoolean("screenAvailable"), wake.optBoolean("suspended"), wake.optBoolean("pausedForVoice"), wake.optBoolean("voiceClaimed"),
            wake.optString("companionName"), wake.optJSONObject("preparation")?.let(::progress) ?: NativeVoiceProgress(),
            voices.getBoolean("available"), voices.optBoolean("systemReady"), voices.optBoolean("failed"), voiceItems,
            resources.getBoolean("available"), modelItems, progress(value.getJSONObject("asr")),
            preview.getBoolean("available"), if (preview.getBoolean("active")) preview.getString("owner") else null, preview.optString("phase").takeIf { it.isNotBlank() })
    }
    fun progress(value: JSONObject): NativeVoiceProgress = if (!value.getBoolean("available")) NativeVoiceProgress() else
        NativeVoiceProgress(true, value.getString("state"), value.getBoolean("ready"), value.getLong("downloadedBytes"), value.getLong("totalBytes"), value.getBoolean("failed"))
    fun wakeDraft(value: JSONObject) = NativeVoiceWakeDraft(value.getBoolean("enabled"), value.getString("timing"), value.getBoolean("useCompanionName"),
        value.getString("chinese"), value.getString("english"))
    fun tts(value: NativeVoiceTtsDraft) = JSONObject().put("provider", value.provider).put("voiceId", value.voiceId).put("rate", value.rate).put("url", value.url).put("model", value.model)
    fun wake(value: NativeVoiceWakeDraft) = JSONObject().put("enabled", value.enabled).put("timing", value.timing).put("useCompanionName", false)
        .put("chinese", value.chinese).put("english", value.english)
}
