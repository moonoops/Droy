package io.droyagent.app;

import android.content.Context;
import android.content.Intent;
import androidx.test.platform.app.InstrumentationRegistry;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;
import static org.junit.Assume.*;

/** Explicit recovery of confirmed settings after an interrupted empty-store fixture. */
public final class MemoryFixtureRecoveryDeviceTest {
    @Test public void restoreExplicitlyConfirmedDisplayAndBackgroundChoices() throws Exception {
        var instrumentation=InstrumentationRegistry.getInstrumentation();var args=InstrumentationRegistry.getArguments();
        assumeTrue("Explicit fixture recovery only","true".equals(args.getString("recoverEmptyMemoryFixture")));
        String external=args.getString("externalDisplay"),internal=args.getString("internalDisplay");
        assertTrue(java.util.Set.of("always","adaptive","off").contains(external));
        assertTrue(java.util.Set.of("always","adaptive","off").contains(internal));
        assertEquals("true",args.getString("backgroundEnabled"));
        Context context=instrumentation.getTargetContext();
        assertTrue(RuntimeService.canManageHistory());
        for(String name:new String[]{"droy-memory","droy-memory.identity.json","droy-privacy","droy-privacy.identity.json"})
            assertFalse("An existing private store cannot lose its protection latch",new java.io.File(context.getFilesDir(),name).exists());
        // Prior empty-store fixture assertions establish these two false values.
        // Preserve every other memory key, whose original presence is unknown.
        assertTrue(context.getSharedPreferences("droy_memory",Context.MODE_PRIVATE).edit().putBoolean("enabled",false)
            .putBoolean("privacyEverActivated",false).putBoolean("pendingEnable",false).commit());
        MainActivity activity=(MainActivity)instrumentation.startActivitySync(new Intent(context,MainActivity.class)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK));
        var action=MainActivity.class.getDeclaredMethod("performUiAction",String.class,JSONObject.class);action.setAccessible(true);
        java.util.concurrent.atomic.AtomicReference<Throwable> failed=new java.util.concurrent.atomic.AtomicReference<>();
        instrumentation.runOnMainSync(()->{
            try {
                action.invoke(activity,"setPreference",new JSONObject().put("key","petEnabled").put("value",true));
                action.invoke(activity,"setPreference",new JSONObject().put("key","petExternalDisplay").put("value",external));
                action.invoke(activity,"setPreference",new JSONObject().put("key","petInternalDisplay").put("value",internal));
                action.invoke(activity,"setPreference",new JSONObject().put("key","backgroundRuntimeEnabled").put("value",true));
            }catch(Throwable failure){failed.set(failure);}
        });
        if(failed.get()!=null)throw new AssertionError("Confirmed settings could not be restored through native product actions",failed.get());
        DeviceUi.await("Restored background runtime did not become ready",90000,()->RuntimeService.ready);
        var product=context.getSharedPreferences("product",Context.MODE_PRIVATE);
        assertEquals(external,product.getString("petExternalDisplay",""));assertEquals(internal,product.getString("petInternalDisplay",""));
        assertTrue(product.getBoolean("petEnabled",false));assertTrue(BackgroundRuntime.enabled(context));assertFalse(BackgroundRuntime.explicitlyStopped(context));
        assertFalse(MemoryRuntime.get(context).protectionRequired());assertFalse(MemoryRuntime.get(context).settings().getBoolean("enabled"));
        instrumentation.runOnMainSync(activity::finish);instrumentation.waitForIdleSync();
        assertTrue(RuntimeService.ready);assertTrue(BackgroundRuntime.state(context).getBoolean("backgroundRuntimeRunning"));
    }
}
