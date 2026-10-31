package io.droyagent.app.nativeui.setup

import io.droyagent.app.nativeui.companion.data.NativeUserProfile
import io.droyagent.app.nativeui.data.NativeApiException
import io.droyagent.app.nativeui.data.NativeRuntimeState
import io.droyagent.app.nativeui.permissions.NativeAuthorizationPolicy
import org.json.JSONException
import org.json.JSONObject

internal object NativeSetupJson {
    val checkIds = setOf("runtime", "model", "modelTools", "identity", "permissionPolicy")
    private val avatar = Regex("(?:/droy/companions/assets/user-default\\.png|/droy/companions/user-profile/attachments/[A-Za-z0-9_.:-]{1,160})")

    fun route(value: JSONObject): NativeSetupRoute = decode {
        if (integer(value, "nativeSchemaVersion") != 1) malformed()
        val completed = boolean(value, "completed")
        val required = boolean(value, "required")
        if (completed == required) malformed()
        val step = integer(value, "step").also { if (it !in 1..6) malformed() }.coerceAtMost(5)
        val locale = string(value, "locale").also { if (it !in setOf("zh", "en")) malformed() }
        val environment = value.getJSONObject("environment")
        val policy = value.getJSONObject("permissionPolicy")
        if (!policy.has("mode")) malformed()
        val selected = boolean(policy, "selected")
        val mode = if (policy.isNull("mode")) null else NativeAuthorizationPolicy.fromId(string(policy, "mode")) ?: malformed()
        if (selected != (mode != null)) malformed()
        val acknowledgements = value.getJSONObject("acknowledgements")
        val confirmations = acknowledgements.keys().asSequence().associateWith { boolean(acknowledgements, it) }
        NativeSetupRoute(completed, required, step, integer(value, "legacyStep"), string(value, "source"), integer(value, "schemaVersion"),
            NativeRuntimeState(boolean(environment, "ready"), boolean(environment, "active"), boolean(environment, "canStart"), string(environment, "status"), locale), mode, confirmations)
    }

    fun live(envelope: JSONObject): NativeSetupLive = decode {
        if (!boolean(envelope, "ok")) throw NativeApiException(envelope.optionalString("code") ?: "request_failed")
        val value = envelope.getJSONObject("state")
        val route = route(value)
        val array = value.getJSONArray("checks")
        val checks = (0 until array.length()).map { index ->
            val item = array.getJSONObject(index)
            NativeSetupCheck(string(item, "id"), boolean(item, "required"), boolean(item, "passed"))
        }
        if (checks.map { it.id }.toSet() != checkIds || checks.size != checkIds.size) malformed()
        val canComplete = boolean(value, "canComplete")
        if (canComplete && checks.any { it.required && !it.passed }) malformed()
        val identity = value.getJSONObject("identity")
        val profile = profile(identity.getJSONObject("profile"))
        val saved = boolean(identity, "saved")
        val confirmed = boolean(identity, "confirmed")
        if (confirmed && (!saved || profile.name.isEmpty())) malformed()
        if (!envelope.has("selection")) malformed()
        val selection = if (envelope.isNull("selection")) null else envelope.getJSONObject("selection")
        val provider = selection?.let { string(it, "provider") }
        val model = selection?.let { string(it, "model") }
        if (checks.first { it.id == "model" }.passed && (provider.isNullOrBlank() || model.isNullOrBlank())) malformed()
        if (checks.first { it.id == "modelTools" }.passed && !checks.first { it.id == "model" }.passed) malformed()
        NativeSetupLive(route, checks, canComplete, saved, confirmed, profile, provider, model)
    }

    fun profile(value: JSONObject): NativeUserProfile = decode {
        val name = string(value, "name")
        val url = string(value, "avatarUrl")
        val bio = if (value.has("bio")) string(value, "bio") else ""
        if (!validName(name, allowEmpty = true) || !validAvatar(url) || !validBio(bio) || bio != bio.trim()) malformed()
        NativeUserProfile(name, url, bio)
    }

    fun validateIdentity(draft: NativeSetupIdentityDraft): NativeUserProfile {
        val name = draft.name.trim()
        val url = draft.avatarUrl
        val bio = draft.bio?.trim() ?: throw NativeApiException("setup_identity_invalid")
        if (!validName(name, allowEmpty = false) || url == null || !validAvatar(url) || !validBio(bio)) throw NativeApiException("setup_identity_invalid")
        return NativeUserProfile(name, url, bio)
    }

    fun validAvatar(value: String): Boolean = avatar.matches(value)
    fun validName(value: String, allowEmpty: Boolean): Boolean {
        if ((!allowEmpty && value.isEmpty()) || value.trim() != value || value.codePointCount(0, value.length) > 80
            || value.toByteArray(Charsets.UTF_8).size > 320 || value.any { it.code < 32 || it.code == 127 }) return false
        var index = 0
        while (index < value.length) {
            val char = value[index]
            if (Character.isHighSurrogate(char)) {
                if (index + 1 >= value.length || !Character.isLowSurrogate(value[index + 1])) return false
                index += 2
            } else {
                if (Character.isLowSurrogate(char)) return false
                index++
            }
        }
        return true
    }

    fun validBio(value: String): Boolean {
        if (value.codePointCount(0, value.length) > 2000 || value.toByteArray(Charsets.UTF_8).size > 8000
            || value.any { (it.code < 32 && it !in "\t\r\n") || it.code == 127 }) return false
        var index = 0
        while (index < value.length) {
            if (Character.isHighSurrogate(value[index])) {
                if (index + 1 >= value.length || !Character.isLowSurrogate(value[index + 1])) return false
                index += 2
            } else {
                if (Character.isLowSurrogate(value[index])) return false
                index++
            }
        }
        return true
    }

    fun identityRequest(value: NativeUserProfile): JSONObject = JSONObject().put("expectedProfile", JSONObject().put("name", value.name).put("avatarUrl", value.avatarUrl).put("bio", value.bio))

    fun draft(value: JSONObject): NativeSetupIdentityDraft = decode {
        val version = integer(value, "version")
        val expected = setOf("version", "name", "avatarUrl", "edited") + if (version == 2) setOf("bio") else emptySet()
        if (version !in 1..2 || value.keys().asSequence().toSet() != expected) malformed()
        val url = if (value.isNull("avatarUrl")) null else string(value, "avatarUrl").also { if (!validAvatar(it)) malformed() }
        NativeSetupIdentityDraft(string(value, "name"), url, boolean(value, "edited"),
            if (version == 1 || value.isNull("bio")) null else string(value, "bio"))
    }
    fun draftValue(value: NativeSetupIdentityDraft): JSONObject = JSONObject().put("version", 2).put("name", value.name)
        .put("avatarUrl", value.avatarUrl ?: JSONObject.NULL).put("edited", value.edited).put("bio", value.bio ?: JSONObject.NULL)

    private fun string(value: JSONObject, key: String): String = value.get(key) as? String ?: malformed()
    private fun boolean(value: JSONObject, key: String): Boolean = value.get(key) as? Boolean ?: malformed()
    private fun integer(value: JSONObject, key: String): Int {
        val number = value.get(key) as? Number ?: malformed()
        val integer = number.toInt()
        if (!number.toDouble().isFinite() || integer.toDouble() != number.toDouble()) malformed()
        return integer
    }
    private fun JSONObject.optionalString(key: String): String? = if (has(key) && !isNull(key)) get(key) as? String ?: malformed() else null
    private fun malformed(): Nothing = throw NativeApiException("malformed_response")
    private inline fun <T> decode(block: () -> T): T = try { block() } catch (error: JSONException) { throw NativeApiException("malformed_response", error) }
}
