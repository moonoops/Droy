package io.droyagent.app;

import org.json.JSONObject;
import org.junit.Test;
import java.util.Set;
import static org.junit.Assert.*;

public class NativeProductSettingsHostTest {
    @Test public void displayWriterCannotMutatePermissionsCredentialsOrMaintenance() {
        for (String key : new String[]{"allowControl", "backgroundRuntimeEnabled", "notificationManagementEnabled", "apiKey", "resetTheme", "clearHistory", "petId"})
            assertThrows(IllegalArgumentException.class, () -> NativeProductSettingsHost.checkedDisplay(key, true));
    }
    @Test public void displayModesAndBooleanValuesAreNotCoerced() throws Exception {
        assertEquals("adaptive", NativeProductSettingsHost.checkedDisplay("petExternalDisplay", "adaptive"));
        assertEquals(false, NativeProductSettingsHost.checkedDisplay("automationScreenDisplay", false));
        assertEquals(true, NativeProductSettingsHost.checkedDisplay("browserEncryptedDns", true));
        assertEquals(false, NativeProductSettingsHost.checkedDisplay("browserEncryptedDns", false));
        assertThrows(IllegalArgumentException.class, () -> NativeProductSettingsHost.checkedDisplay("petInternalDisplay", "sometimes"));
        assertThrows(IllegalArgumentException.class, () -> NativeProductSettingsHost.checkedDisplay("browserEncryptedDns", "false"));
    }
    @Test public void retiredBrandAndPetNamePreferencesCannotBeChanged() {
        for (String key : new String[]{"assistantName", "petName", "brandWelcomeTemplate", "brandHeroBadge", "brandSidebarLabel"})
            assertThrows(IllegalArgumentException.class, () -> NativeProductSettingsHost.checkedDisplay(key, "Custom"));
    }
    @Test public void operationBoundaryRejectsExtraFields() throws Exception {
        NativeProductSettingsHost.fields(new JSONObject().put("localEnabled", false), Set.of("localEnabled"));
        assertThrows(IllegalArgumentException.class, () -> NativeProductSettingsHost.fields(
            new JSONObject().put("localEnabled", false).put("operation", "send"), Set.of("localEnabled")));
    }

    private JSONObject rule(String id, String pkg) throws Exception {
        JSONObject rule = new JSONObject().put("packageName", pkg).put("instruction", "Keep delivery updates")
            .put("defaultAction", "retain").put("repeatMinutes", 10).put("clearAfterHours", 24)
            .put("entries", new org.json.JSONArray().put(new JSONObject().put("action", "notify")
                .put("containsAny", new org.json.JSONArray().put("delivery")).put("containsAll", new org.json.JSONArray())));
        if (id != null) rule.put("id", id);
        return rule;
    }
    private JSONObject request(JSONObject rule, Object revision) throws Exception {
        return new JSONObject().put("operation", "upsertRule").put("rule", rule).put("expectedRevision", revision);
    }
    private JSONObject current(JSONObject... rules) throws Exception {
        return new JSONObject().put("revision", 7).put("rules", new org.json.JSONArray(java.util.List.of(rules)));
    }
    @Test public void ruleEditPreservesIdentityConditionsAndInput() throws Exception {
        JSONObject input = rule("existing", "example.shipping"); String before = input.toString();
        JSONObject checked = NativeProductSettingsHost.prepareRule(request(input, 7), current(input));
        assertEquals("existing", checked.getString("id"));
        assertEquals("notify", checked.getJSONArray("entries").getJSONObject(0).getString("action"));
        assertEquals(before, input.toString());
        JSONObject created = NativeProductSettingsHost.prepareRule(request(rule(null, "*"), 7), current());
        assertFalse(created.getString("id").isEmpty());
    }
    @Test public void staleDeletedOrSamePackageRulesCannotBeOverwritten() throws Exception {
        JSONObject existing = rule("existing", "example.shipping");
        assertThrows(NativeProductSettingsHost.RuleConflictException.class, () -> NativeProductSettingsHost.prepareRule(request(existing, 6), current(existing)));
        assertThrows(NativeProductSettingsHost.RuleConflictException.class, () -> NativeProductSettingsHost.prepareRule(request(existing, 7), current()));
        assertThrows(NativeProductSettingsHost.RuleConflictException.class, () -> NativeProductSettingsHost.prepareRule(request(rule(null, "example.shipping"), 7), current(existing)));
        assertThrows(NativeProductSettingsHost.RuleConflictException.class, () -> NativeProductSettingsHost.prepareRule(request(rule("other", "example.shipping"), 7), current(existing, rule("other", "example.other"))));
    }
    @Test public void ruleSchemaUsesActualServiceLimitsAndRejectsSilentCoercion() throws Exception {
        for (Object revision : new Object[]{"7", 7.5, -1, JSONObject.NULL})
            assertThrows(IllegalArgumentException.class, () -> NativeProductSettingsHost.prepareRule(request(rule(null, "*"), revision), current()));
        for (String field : new String[]{"repeatMinutes", "clearAfterHours"}) {
            JSONObject input = rule(null, "*").put(field, 1441);
            assertThrows(IllegalArgumentException.class, () -> NativeProductSettingsHost.prepareRule(request(input, 7), current()));
        }
        JSONObject empty = rule(null, "*"); empty.getJSONArray("entries").getJSONObject(0).put("containsAny", new org.json.JSONArray());
        assertThrows(IllegalArgumentException.class, () -> NativeProductSettingsHost.prepareRule(request(empty, 7), current()));
        JSONObject foreign = rule(null, "*").put("credentials", "fictional-value");
        assertThrows(IllegalArgumentException.class, () -> NativeProductSettingsHost.prepareRule(request(foreign, 7), current()));
        JSONObject invalid = rule(null, "*"); invalid.getJSONArray("entries").getJSONObject(0).put("containsAny", "delivery");
        assertThrows(org.json.JSONException.class, () -> NativeProductSettingsHost.prepareRule(request(invalid, 7), current()));
    }

    @Test public void diagnosticsOnlyReturnsStructuredFieldsAndKnownCodeCounts() throws Exception {
        JSONObject versions = new JSONObject().put("droyVersion", "0.0.3").put("dshVersion", "1.2.3-beta.1")
            .put("mobileVersion", "2026.10.29").put("apiKey", "fictional-version-key");
        JSONObject progress = new JSONObject().put("phase", "verifying").put("active", true)
            .put("taskId", "private-task").put("summary", "private-user-input").put("pending", "private-question");
        String raw = "EACCES /private/user/file fictional-api-key\nunknown private-response\nEACCES token=fictional-token\nERR_MODULE_NOT_FOUND /private/module\n";
        JSONObject result = NativeProductSettingsHost.diagnosticSnapshot(versions, "16", 36, true, true, false, progress, raw);
        assertEquals("verifying", result.getString("phase")); assertTrue(result.getBoolean("phaseActive"));
        assertEquals("16", result.getString("androidRelease")); assertEquals(36, result.getInt("androidApi"));
        assertEquals(2, result.getJSONArray("notices").length());
        assertEquals("EACCES", result.getJSONArray("notices").getJSONObject(0).getString("code"));
        assertEquals(2, result.getJSONArray("notices").getJSONObject(0).getInt("count"));
        for (String forbidden : new String[]{"private", "fictional", "apiKey", "summary", "taskId", "pending", "raw", "unknown"})
            assertFalse(result.toString().contains(forbidden));
    }
    @Test public void diagnosticsHasNoRawFallbackForUnknownOrMalformedSources() throws Exception {
        JSONObject result = NativeProductSettingsHost.diagnosticSnapshot(new JSONObject().put("droyVersion", "/private/version")
            .put("dshVersion", "Bearer fictional-secret"), "/private/android", 36, false, false, true,
            new JSONObject().put("phase", "user-controlled-phase").put("active", "true"), "arbitrary exception message: secret=fictional-secret");
        assertFalse(result.has("phase")); assertFalse(result.has("phaseActive"));
        assertFalse(result.has("droyVersion")); assertFalse(result.has("dshVersion")); assertFalse(result.has("androidRelease"));
        assertEquals(0, result.getJSONArray("notices").length()); assertFalse(result.toString().contains("fictional"));
        JSONObject bounded = NativeProductSettingsHost.diagnosticSnapshot(new JSONObject(), "16", 36, false, false, false,
            new JSONObject(), "ENOSPC /private/source\n".repeat(1000));
        assertTrue(bounded.getJSONArray("notices").getJSONObject(0).getInt("count") <= 256);
    }
}
