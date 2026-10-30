package io.droyagent.app.nativeui.data

import org.json.JSONObject

/** Effort IDs belong to the exact provider route; never infer them from a model name. */
internal object NativeModelJson {
    fun catalog(provider: String, providerName: String, value: JSONObject): NativeModel {
        val reasoning = value.optJSONObject("reasoning")
        val options = reasoning?.optJSONArray("efforts")
        val names = linkedMapOf<String, String>()
        if (options != null) for (index in 0 until options.length()) {
            val option = options.getJSONObject(index)
            names[option.getString("id")] = option.getString("name")
        }
        return NativeModel(provider, value.getString("id"), value.getString("name"), providerName,
            reasoningEfforts = names.keys.toList(),
            defaultReasoningEffort = reasoning?.takeUnless { it.isNull("defaultEffort") }?.getString("defaultEffort"),
            reasoningNames = names)
    }

    fun draft(value: JSONObject) = NativeModel(value.getString("provider"), value.getString("id"),
        value.getString("name"), value.getString("providerName"),
        reasoningEffort = value.takeUnless { it.isNull("reasoningEffort") }?.getString("reasoningEffort"))

    fun draft(model: NativeModel): JSONObject = JSONObject().put("provider", model.provider).put("id", model.id)
        .put("name", model.name).put("providerName", model.providerName).apply {
            model.reasoningEffort?.let { put("reasoningEffort", it) }
        }
}
