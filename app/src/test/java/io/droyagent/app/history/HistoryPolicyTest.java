package io.droyagent.app.history;

import java.nio.charset.StandardCharsets;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class HistoryPolicyTest {
    @Test public void defaultsNeverRetainEvents() {
        HistoryPolicy policy = HistoryPolicy.defaults();
        assertFalse(policy.wantsCapture()); assertFalse(policy.locationEnabled);
        assertFalse(policy.pageTextEnabled); assertTrue(policy.allowedPackages.isEmpty());
    }
    @Test public void masterSwitchControlsActivityAndLocation() throws Exception {
        HistoryPolicy policy = HistoryPolicy.defaults().patch(new JSONObject().put("activityEnabled", true));
        assertFalse(policy.wantsCapture());
        policy = policy.patch(new JSONObject().put("enabled", true)); assertTrue(policy.wantsCapture());
        assertFalse(policy.patch(new JSONObject().put("paused", true)).wantsCapture());
        assertFalse(policy.patch(new JSONObject().put("enabled", false)).wantsCapture());
        HistoryPolicy locationOnly = HistoryPolicy.defaults().patch(new JSONObject().put("locationEnabled", true));
        assertFalse(locationOnly.wantsCapture()); assertFalse(locationOnly.enabled);
        assertTrue(locationOnly.patch(new JSONObject().put("enabled", true)).wantsCapture());
        assertFalse(locationOnly.patch(new JSONObject().put("paused", true)).wantsCapture());
    }
    @Test public void exclusionsWinAndBrowserBodyCannotBeInferredSafe() throws Exception {
        HistoryPolicy policy = HistoryPolicy.defaults().patch(new JSONObject().put("enabled", true).put("pageTextEnabled", true)
            .put("allowedPackages", new JSONArray().put("example.allowed").put("example.blocked").put("io.droyagent.app"))
            .put("excludedPackages", new JSONArray().put("example.blocked")));
        assertTrue(policy.allowsPackage("example.allowed", "io.droyagent.app"));
        assertFalse(policy.allowsPackage("example.blocked", "io.droyagent.app"));
        assertFalse(policy.allowsPackage("io.droyagent.app", "io.droyagent.app"));
        assertTrue(policy.allowsPage("example.allowed", "io.droyagent.app", true, false, false));
        assertFalse(policy.allowsPage("example.allowed", "io.droyagent.app", true, false, true));
        assertFalse(policy.allowsPage("example.allowed", "io.droyagent.app", true, true, false));
        assertFalse(policy.allowsPage("example.allowed", "io.droyagent.app", false, false, false));
    }
    @Test public void aSettingsValueCannotSetPrivacyReadiness() throws Exception {
        for (JSONObject input : new JSONObject[]{new JSONObject().put("executionPrivacyReady", true),
            new JSONObject().put("enabled", "true"),new JSONObject().put("allowedPackages", new JSONArray().put("../private"))}) {
            try { HistoryPolicy.parse(input); fail(); } catch (IllegalArgumentException expected) { assertTrue(expected.getMessage().startsWith("HISTORY_")); }
        }
    }
    @Test public void reducingOneSourceRemainsAllowedAfterReadinessIsLost() throws Exception {
        HistoryPolicy policy = HistoryPolicy.defaults().patch(new JSONObject().put("enabled", true)
            .put("activityEnabled", true).put("locationEnabled", true));
        assertFalse(policy.patch(new JSONObject().put("locationEnabled", false)).expandsConsentFrom(policy));
        assertFalse(policy.patch(new JSONObject().put("paused", true)).expandsConsentFrom(policy));
        assertTrue(policy.patch(new JSONObject().put("pageTextEnabled", true)).expandsConsentFrom(policy));
        assertTrue(policy.patch(new JSONObject().put("allowedPackages", new JSONArray().put("example.app"))).expandsConsentFrom(policy));
    }
    @Test public void allApplicationsRequiresExplicitExpansionAndKeepsSensitiveExclusions() throws Exception {
        HistoryPolicy old = HistoryPolicy.defaults().patch(new JSONObject().put("enabled", true)
            .put("pageTextEnabled", true).put("allowedPackages", new JSONArray().put("example.allowed")));
        assertFalse(old.allowsPackage("example.new", "io.droyagent.app.dev"));
        HistoryPolicy all = old.patch(new JSONObject().put("allApplications", true));
        assertTrue(all.expandsConsentFrom(old));
        assertTrue(all.allowsPackage("example.new", "io.droyagent.app.dev"));
        assertFalse(all.allowsPackage("io.droyagent.app.dev", "io.droyagent.app.dev"));
        assertFalse(all.allowsPage("example.new", "io.droyagent.app.dev", true, true, false));
        assertFalse(all.allowsPage("example.new", "io.droyagent.app.dev", true, false, true));
        assertTrue(HistoryPolicy.parse(all.toJson()).allApplications);
    }
    @Test public void utf8LimitKeepsChineseAndEmojiBoundaries() {
        String input = "早饭🐈记录";
        assertEquals("早饭", HistoryPolicy.boundedUtf8(input, 9));
        assertEquals("早饭🐈", HistoryPolicy.boundedUtf8(input, 10));
        assertTrue(HistoryPolicy.boundedUtf8(input.repeat(1000), HistoryPolicy.PAGE_BYTES).getBytes(StandardCharsets.UTF_8).length <= HistoryPolicy.PAGE_BYTES);
    }
    @Test public void stoppingAndReconfiguringRejectEveryOlderCallback() {
        HistoryEpoch gate = new HistoryEpoch();
        assertEquals(-1, gate.ticket());
        long first = gate.replace(true); assertTrue(gate.accepts(first));
        gate.replace(false); assertFalse(gate.accepts(first));
        long second = gate.replace(true); assertFalse(gate.accepts(first)); assertTrue(gate.accepts(second));
        gate.replace(true); assertFalse(gate.accepts(second));
    }
}
