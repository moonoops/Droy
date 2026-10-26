package io.droyagent.androiduse;

import org.junit.Test;
import static org.junit.Assert.*;

public class ActionValidationTest {
    @Test public void coordinatesRejectOutOfDisplayAndNonIntegers() throws Exception {
        assertEquals(0, ActionValidation.coordinate(0, "x", 1080));
        assertEquals(1079, ActionValidation.coordinate(1079, "x", 1080));
        for (Object value : new Object[]{-1, 1080, 1.5, Double.NaN, Double.POSITIVE_INFINITY, "12; reboot", null}) {
            expect("invalid_argument", () -> ActionValidation.coordinate(value, "x", 1080));
        }
    }

    @Test public void durationIsBoundedToKeepStopResponsive() throws Exception {
        assertEquals(50, ActionValidation.duration(50));
        assertEquals(2000, ActionValidation.duration(2000));
        for (Object value : new Object[]{0, -1, 49, 2001, Long.MAX_VALUE, "300"}) {
            expect("invalid_argument", () -> ActionValidation.duration(value));
        }
    }

    @Test public void packageNameRejectsShellAndIntentPayloads() throws Exception {
        assertEquals("com.example.demo", ActionValidation.packageName("com.example.demo"));
        for (String value : new String[]{"", "com.demo;id", "com.demo/Activity", "com.demo\n", "../demo", "intent:#Intent"}) {
            expect("invalid_argument", () -> ActionValidation.packageName(value));
        }
    }

    @Test public void coordinateHoldHasAnExplicitBoundedDuration() throws Exception {
        assertEquals(450, ActionValidation.holdDuration(450));
        assertEquals(2000, ActionValidation.holdDuration(2000));
        for (Object value : new Object[]{0, 70, 449, 2001, 800.5, Double.NaN, "800", null})
            expect("invalid_argument", () -> ActionValidation.holdDuration(value));
    }

    @Test public void unicodeInputIsPreservedAndNulIsRejected() throws Exception {
        assertEquals("你好 Android 🌙", ActionValidation.text("你好 Android 🌙"));
        assertEquals("", ActionValidation.text(""));
        expect("invalid_argument", () -> ActionValidation.text("hidden\0value"));
        expect("invalid_argument", () -> ActionValidation.text("x".repeat(10001)));
    }

    @Test public void revokedRootConsentAndNonAllowlistedCommandsCannotExecute() throws Exception {
        expect("root_not_enabled", () -> ActionValidation.rootAllowed(false, "tap"));
        for (String command : new String[]{"shell", "input_text", "observe", "open_app", "reboot"}) {
            expect("unsupported_root_action", () -> ActionValidation.rootAllowed(true, command));
        }
        for (String command : new String[]{"tap", "swipe", "back", "screenshot"}) {
            ActionValidation.rootAllowed(true, command);
        }
    }

    private static void expect(String code, Throwing action) throws Exception {
        try {
            action.run();
            fail("Expected " + code);
        } catch (ActionException error) {
            assertEquals(code, error.getCode());
        }
    }
    private interface Throwing { void run() throws Exception; }
}
