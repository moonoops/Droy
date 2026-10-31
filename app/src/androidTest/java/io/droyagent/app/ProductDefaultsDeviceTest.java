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
public final class ProductDefaultsDeviceTest {
    @Test public void completionReturnDefaultsOnAndRetainsExplicitOffChoiceAcrossHosts() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        String name = "product-defaults-test-" + java.util.UUID.randomUUID();
        Context isolated = new ContextWrapper(context) {
            @Override public Context getApplicationContext() { return this; }
            @Override public SharedPreferences getSharedPreferences(String requested, int mode) {
                return super.getSharedPreferences(requested.equals("product") ? name : requested, mode);
            }
        };
        try {
            NativeProductSettingsHost host = new NativeProductSettingsHost(isolated);
            assertTrue(host.snapshot("general").getBoolean("returnToDroyOnCompletion"));
            assertFalse(isolated.getSharedPreferences("product", Context.MODE_PRIVATE).contains("returnToDroyOnCompletion"));
            host.setDisplay("returnToDroyOnCompletion", false);
            assertFalse(new NativeProductSettingsHost(isolated).snapshot("general").getBoolean("returnToDroyOnCompletion"));
            host.setDisplay("returnToDroyOnCompletion", true);
            assertTrue(new NativeProductSettingsHost(isolated).snapshot("general").getBoolean("returnToDroyOnCompletion"));
        } finally { context.deleteSharedPreferences(name); }
    }
}
