package io.droyagent.androiduse;

import org.junit.Test;
import static org.junit.Assert.*;

public final class AccessibilityRecoveryRulesTest {
    private static final String SELF = "example.droy/example.droy.Accessibility";

    @Test public void onlyAnActualEnableCreatesTheInitialAuthorization() {
        assertFalse(AccessibilityRecoveryRules.authorized(false, false, "android", "com.android.settings"));
        assertTrue(AccessibilityRecoveryRules.authorized(false, true, "com.android.settings", "com.android.settings"));
    }

    @Test public void forceStopAndRebootRetainThePreviousAuthorization() {
        for (String writer : new String[]{"android", "root", "com.android.shell", "", "example.droy"})
            assertTrue(writer, AccessibilityRecoveryRules.authorized(true, false, writer, "com.android.settings"));
    }

    @Test public void settingsRevocationSurvivesLaterSystemWritesAndAnAppRestart() {
        boolean saved = AccessibilityRecoveryRules.authorized(true, false, "com.android.settings", "com.android.settings");
        assertFalse(saved);
        assertFalse(AccessibilityRecoveryRules.authorized(saved, false, "android", "com.android.settings"));
        assertFalse(AccessibilityRecoveryRules.authorized(saved, false, "root", "com.android.settings"));
    }

    @Test public void vendorSettingsCanRevokeAndANewManualEnableReauthorizes() {
        assertFalse(AccessibilityRecoveryRules.authorized(true, false, "example.settings", "example.settings"));
        assertTrue(AccessibilityRecoveryRules.authorized(false, true, "example.settings", "example.settings"));
    }

    @Test public void restoringAndRebindingPreserveOtherServicesAndTheirOrder() {
        String others = "example.reader/.Service:example.switch/example.switch.Service";
        assertEquals(others + ":" + SELF, AccessibilityRecoveryRules.withService(others, SELF, true));
        assertEquals(others, AccessibilityRecoveryRules.withService(others + ":" + SELF, SELF, false));
    }

    @Test public void shorthandAlreadyEnabledIsNotDuplicated() {
        assertEquals("example.droy/.Accessibility", AccessibilityRecoveryRules.withService("example.droy/.Accessibility", SELF, true));
        assertEquals("", AccessibilityRecoveryRules.withService("example.droy/.Accessibility", SELF, false));
    }

    @Test public void emptyListCanBeEnabledWithoutWritingOtherServiceNames() {
        assertEquals(SELF, AccessibilityRecoveryRules.withService(null, SELF, true));
        assertEquals(SELF, AccessibilityRecoveryRules.withService("null", SELF, true));
        assertEquals(SELF, AccessibilityRecoveryRules.withService("", SELF, true));
    }

    @Test public void malformedListDoesNotBecomeAReplacementList() {
        for (String malformed : new String[]{"invalid", "bad/service\nsecond", "example/.A:invalid"}) {
            try { AccessibilityRecoveryRules.withService(malformed, SELF, true); fail(malformed); }
            catch (IllegalArgumentException expected) { }
        }
    }

    @Test public void auditWriterMustMatchTheExactKeyAndCurrentValue() {
        String dump = "_id:1 name:enabled_accessibility_services_extra pkg:android value:wrong\n"
            + "_id:2 name:enabled_accessibility_services pkg:com.android.settings value:example.reader/.Service default:other defaultSystemSet:true\n"
            + "_id:3 name:accessibility_enabled pkg:android value:1 notPreservedInRestore\n";
        assertEquals("com.android.settings", AccessibilityRecoveryRules.writer(dump, "enabled_accessibility_services", "example.reader/.Service"));
        assertEquals("", AccessibilityRecoveryRules.writer(dump, "enabled_accessibility_services", SELF));
        assertEquals("android", AccessibilityRecoveryRules.writer(dump, "accessibility_enabled", "1"));
    }

    @Test public void emptyAndMissingAuditRowsDoNotInventAWriter() {
        assertEquals("com.android.settings", AccessibilityRecoveryRules.writer(
            "_id:2 name:enabled_accessibility_services pkg:com.android.settings value: default: defaultSystemSet:true\n",
            "enabled_accessibility_services", ""));
        assertEquals("", AccessibilityRecoveryRules.writer("Permission denied", "accessibility_enabled", "0"));
        assertEquals("", AccessibilityRecoveryRules.writer("_id:2 name:accessibility_enabled value:0\n", "accessibility_enabled", "0"));
    }
}
