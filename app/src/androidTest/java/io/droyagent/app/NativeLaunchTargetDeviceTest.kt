package io.droyagent.app

import android.content.ComponentName
import android.content.pm.PackageManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Verify packaged components without starting a task or changing runtime state. */
@RunWith(AndroidJUnit4::class)
class NativeLaunchTargetDeviceTest {
    @Test fun launcherAndExistingShortcutResolveToNativeActivity() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val manager = context.packageManager
        val launch = requireNotNull(manager.getLaunchIntentForPackage(context.packageName))
        assertEquals(NativeAppActivity::class.java.name, launch.component?.className)
        @Suppress("DEPRECATION")
        val legacy = manager.getActivityInfo(ComponentName(context.packageName, MainActivity::class.java.name), 0)
        assertEquals(NativeAppActivity::class.java.name, legacy.targetActivity)
        assertTrue(legacy.exported)
        @Suppress("DEPRECATION")
        val native = manager.getActivityInfo(ComponentName(context, NativeAppActivity::class.java), PackageManager.MATCH_DEFAULT_ONLY)
        assertTrue(native.exported)
        assertEquals(android.content.pm.ActivityInfo.LAUNCH_SINGLE_TASK, native.launchMode)
    }
}
