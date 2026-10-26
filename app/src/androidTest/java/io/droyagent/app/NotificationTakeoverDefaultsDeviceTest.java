package io.droyagent.app;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public final class NotificationTakeoverDefaultsDeviceTest {
    @Test public void missingPreferenceEnablesManagementButSavedOffChoiceIsKept() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        String name = "notification-defaults-test-" + java.util.UUID.randomUUID();
        Context isolated = new ContextWrapper(context) {
            @Override public SharedPreferences getSharedPreferences(String ignored, int mode) {
                return super.getSharedPreferences(name, mode);
            }
        };
        try {
            assertTrue(NotificationManagement.enabled(isolated));
            assertTrue(isolated.getSharedPreferences("product", Context.MODE_PRIVATE).edit()
                    .putBoolean("notificationManagementEnabled", false).commit());
            assertFalse(NotificationManagement.enabled(isolated));
        } finally { context.deleteSharedPreferences(name); }
    }
}
