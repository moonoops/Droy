package io.droyagent.app.nativeui.setup

import io.droyagent.app.nativeui.companion.data.NativeUserProfile
import io.droyagent.app.nativeui.data.NativeApiException
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class NativeSetupJsonTest {
    private val avatar = "/droy/companions/assets/user-default.png"
    private fun route(step: Int = 3, completed: Boolean = false) = JSONObject().put("nativeSchemaVersion", 1).put("schemaVersion", 4)
        .put("completed", completed).put("required", !completed).put("step", step).put("legacyStep", 5).put("source", "usb").put("locale", "en")
        .put("environment", JSONObject().put("ready", true).put("active", true).put("canStart", false).put("status", "Ready"))
        .put("permissionPolicy", JSONObject().put("selected", true).put("mode", "on_first_use")).put("acknowledgements", JSONObject().put("welcome", true))
    private fun envelope() = JSONObject().put("ok", true).put("selection", JSONObject().put("provider", "fixture").put("model", "fixture-model"))
        .put("modelVerification", JSONObject().put("passed", true).put("toolsPassed", true))
        .put("state", route().put("checks", JSONArray().apply {
            for (id in listOf("runtime", "model", "modelTools", "identity", "permissionPolicy")) put(JSONObject().put("id", id).put("required", true).put("passed", true))
        }).put("canComplete", true).put("identity", JSONObject().put("saved", true).put("confirmed", true)
            .put("profile", JSONObject().put("name", "User").put("avatarUrl", avatar))))

    @Test fun nativeProgressDoesNotDeriveFromTheLegacyStepOrSource() {
        val parsed = NativeSetupJson.route(route(step = 2))
        assertEquals(2, parsed.step); assertEquals(5, parsed.legacyStep); assertEquals("usb", parsed.source)
        assertTrue(parsed.required); assertFalse(parsed.completed)
        val old = NativeSetupJson.route(route(step = 6, completed = true).put("legacyStep", 2))
        assertTrue(old.completed); assertFalse(old.required); assertEquals(2, old.legacyStep)
    }

    @Test fun unknownOrMalformedRoutingDoesNotLookLikeACompletedInstallation() {
        for (value in listOf(route().put("nativeSchemaVersion", 2), route().put("step", 7), route().put("completed", "true"),
            route().put("required", false), route().put("step", 2.5), route().put("permissionPolicy", JSONObject().put("selected", true).put("mode", "future")))) {
            assertCode("malformed_response") { NativeSetupJson.route(value) }
        }
    }

    @Test fun checksRequireTheCompleteKnownSetAndRealBooleans() {
        val value = envelope()
        assertTrue(NativeSetupJson.live(value).canComplete)
        value.getJSONObject("state").getJSONArray("checks").getJSONObject(0).put("passed", "true")
        assertCode("malformed_response") { NativeSetupJson.live(value) }
        val missing = envelope().apply { getJSONObject("state").getJSONArray("checks").remove(4) }
        assertCode("malformed_response") { NativeSetupJson.live(missing) }
        val extra = envelope().apply { getJSONObject("state").getJSONArray("checks").put(JSONObject().put("id", "future").put("required", true).put("passed", true)) }
        assertCode("malformed_response") { NativeSetupJson.live(extra) }
    }

    @Test fun anUnavailableCheckCannotContradictACompletionClaim() {
        val value = envelope()
        value.getJSONObject("state").getJSONArray("checks").getJSONObject(2).put("passed", false)
        assertCode("malformed_response") { NativeSetupJson.live(value) }
        value.getJSONObject("state").put("canComplete", false)
        assertFalse(NativeSetupJson.live(value).canComplete)
    }

    @Test fun identityRequestContainsExactlyTheSavedProfileAndNoReceiptFields() {
        val saved = NativeUserProfile("Saved user", avatar)
        val request = NativeSetupJson.identityRequest(saved)
        assertEquals(setOf("expectedProfile"), request.keys().asSequence().toSet())
        assertEquals(setOf("name", "avatarUrl"), request.getJSONObject("expectedProfile").keys().asSequence().toSet())
        assertEquals("Saved user", request.getJSONObject("expectedProfile").getString("name"))
        assertFalse(request.toString().contains("confirmed"))
        assertFalse(request.toString().contains("completed"))
    }

    @Test fun identityValidationKeepsUnicodeNamesButRejectsForeignAvatarsAndBrokenText() {
        val unicode = "😀".repeat(80)
        assertEquals(unicode, NativeSetupJson.validateIdentity(NativeSetupIdentityDraft(unicode, avatar)).name)
        assertEquals("用户", NativeSetupJson.validateIdentity(NativeSetupIdentityDraft("  用户  ", avatar)).name)
        for (draft in listOf(NativeSetupIdentityDraft("", avatar), NativeSetupIdentityDraft("Name", "https://example.invalid/photo.png"),
            NativeSetupIdentityDraft("Name", "/droy/companions/kaka/attachments/other"), NativeSetupIdentityDraft("bad\nname", avatar),
            NativeSetupIdentityDraft("\uD800", avatar), NativeSetupIdentityDraft("a".repeat(81), avatar))) {
            assertCode("setup_identity_invalid") { NativeSetupJson.validateIdentity(draft) }
        }
    }

    @Test fun interruptionDraftsContainOnlyNonSecretIdentityFields() {
        val value = NativeSetupJson.draftValue(NativeSetupIdentityDraft("Draft", avatar, true))
        assertEquals(setOf("version", "name", "avatarUrl", "edited"), value.keys().asSequence().toSet())
        assertEquals("Draft", NativeSetupJson.draft(value).name)
        assertTrue(NativeSetupJson.draft(value).edited)
        assertCode("malformed_response") { NativeSetupJson.draft(value.put("apiKey", "fictional-test-value")) }
    }

    @Test fun privateConfigurationExtrasNeverReachTheTypedPublicState() {
        val value = envelope().put("configFingerprint", "fictional-private-digest").put("apiKey", "fictional-test-value")
        value.getJSONObject("state").put("configFingerprint", "fictional-private-digest")
        val parsed = NativeSetupJson.live(value).toString()
        assertFalse(parsed.contains("fictional-private-digest")); assertFalse(parsed.contains("fictional-test-value"))
    }

    private fun assertCode(code: String, action: () -> Unit) {
        try { action(); fail("Expected $code") } catch (error: NativeApiException) { assertEquals(code, error.code) }
    }
}
