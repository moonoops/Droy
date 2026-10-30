package io.droyagent.app.nativeui.advanced

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class NativeAdvancedJsonTest {
    @Test fun editsKeepOriginalRevisionAndOnlyTouchSelectedLeaves() {
        val timeout = AdvancedField(listOf("timeoutMs"), "number", "1000")
        val unchanged = AdvancedField(listOf("maxOutputBytes"), "number", "64000")
        val draft = AdvancedDraft(AdvancedSection("bash-sandbox", 7, listOf(timeout, unchanged)), mapOf(timeout.key to "2500"))
        val result = AdvancedJson.mutation(draft)
        assertEquals(7L, result.getLong("expectedRevision"))
        assertEquals(1, result.getJSONArray("ops").length())
        assertEquals(2500.0, result.getJSONArray("ops").getJSONObject(0).getDouble("value"), 0.0)
    }
    @Test fun stringEnumsDoNotBecomeBooleanOrNumberLiterals() {
        val field = AdvancedField(listOf("choice"), "enum", "true", listOf("true", "1"), optionKinds = listOf("string", "string"))
        val result = AdvancedJson.mutation(AdvancedDraft(AdvancedSection("web", 1, listOf(field)), mapOf(field.key to "true")))
        assertTrue(result.getJSONArray("ops").getJSONObject(0).get("value") is String)
    }
    @Test fun resetDoesNotCopyTheRedactedSection() {
        val field = AdvancedField(listOf("timeoutMs"), "number", "1000")
        val result = AdvancedJson.mutation(AdvancedDraft(AdvancedSection("bash-sandbox", 1, listOf(field)), resets = setOf(field.key)))
        val operation = result.getJSONArray("ops").getJSONObject(0)
        assertEquals("unset", operation.getString("op")); assertFalse(operation.has("value"))
    }
    @Test fun schemaRemovalDoesNotSilentlyDiscardRetainedDraft() {
        val field = AdvancedField(listOf("removed"), "string", "")
        try { AdvancedJson.mutation(AdvancedDraft(AdvancedSection("web", 2, emptyList()), mapOf(field.key to "draft"))); fail("A removed field must require review") }
        catch (_: IllegalArgumentException) { }
    }
    @Test fun secretsAndUnknownFieldsCannotBecomeEdits() {
        for (kind in listOf("secret", "unsupported")) {
            val field = AdvancedField(listOf("secret"), kind, "")
            try { AdvancedJson.mutation(AdvancedDraft(AdvancedSection("web", 2, listOf(field)), mapOf(field.key to "fictional"))); fail("Protected field accepted") }
            catch (_: IllegalArgumentException) { }
        }
    }
    @Test fun accountStoredCredentialDoesNotClaimVerifiedProfile() {
        val result = AdvancedJson.account(JSONObject().put("status", "credential-stored").put("attempt", JSONObject.NULL))
        assertNull(result.name); assertTrue(result.balances.isEmpty())
    }
    @Test fun inlineSecretMetadataNeverRetainsASecretValue() {
        val result = AdvancedJson.section(JSONObject("""{"ns":"web-search-deepseek","revision":9,"fields":[{"path":["apiKey"],"kind":"secret","configured":true,"value":"fixture-private-key"},{"path":["apiKeyEnv"],"kind":"string","value":"FIXTURE_KEY","credentialRef":"FIXTURE_KEY"}]}"""))
        assertEquals("", result.fields[0].value)
        assertEquals(AdvancedInlineCredential("FIXTURE_KEY", true), result.inlineCredential)
        assertFalse(result.toString().contains("fixture-private-key"))
        assertNull(result.copy(id = "bash-sandbox").inlineCredential)
    }
    @Test fun configuredAndPnpmRegistriesHaveDifferentWireSemantics() {
        assertFalse(AdvancedJson.registryRequest(JSONObject(), "").has("registry"))
        assertTrue(AdvancedJson.registryRequest(JSONObject(), "pnpm").isNull("registry"))
        assertEquals("https://fixture.invalid/", AdvancedJson.registryRequest(JSONObject(), "https://fixture.invalid/").getString("registry"))
    }
    @Test fun scriptApprovalContainsOnlyAnExplicitPendingSelectionAndItsSourceIdentity() {
        val result = AdvancedJson.approvalRequest("@fixture/plugin", "pnpm", "source", "new-attempt", setOf("fixture-selected"), listOf("fixture-selected", "fixture-other"))
        assertEquals("source", result.getString("approvalFrom")); assertEquals("new-attempt", result.getString("requestId"))
        assertEquals("[\"fixture-selected\"]", result.getJSONArray("approvedBuilds").toString())
        for (selection in listOf(emptySet(), setOf("not-pending"))) {
            try { AdvancedJson.approvalRequest("@fixture/plugin", "", "source", "next", selection, listOf("fixture-selected")); fail("Approval requires explicit current pending names") }
            catch (_: IllegalArgumentException) { }
        }
    }
    @Test fun aVersionGrantRequiresExplicitRiskWhileRevocationDoesNot() {
        try { AdvancedJson.exemptionRequest("@fixture/plugin@1.0.0", "0.2.1-rc.4", true, false); fail("Risk acknowledgement missing") }
        catch (_: IllegalArgumentException) { }
        val grant = AdvancedJson.exemptionRequest("@fixture/plugin@1.0.0", "0.2.1-rc.4", true, true)
        assertTrue(grant.getBoolean("acceptRisk")); assertEquals("0.2.1-rc.4", grant.getString("runtimeVersion"))
        assertFalse(AdvancedJson.exemptionRequest("@fixture/plugin@1.0.0", "0.1.0", false, false).getBoolean("enabled"))
    }
}
