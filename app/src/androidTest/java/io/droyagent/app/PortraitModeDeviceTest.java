package io.droyagent.app;

import android.content.Context;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Check the merged, installed activity policy, including the share and permission entry points. */
@RunWith(AndroidJUnit4.class)
public final class PortraitModeDeviceTest {
    @Test public void allInstalledProductActivitiesUsePortrait() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        PackageInfo installed = context.getPackageManager().getPackageInfo(context.getPackageName(), PackageManager.GET_ACTIVITIES);
        int count = 0;
        for (ActivityInfo activity : installed.activities) {
            if (!activity.name.startsWith("io.droyagent.app.")) continue;
            assertEquals(activity.name, ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, activity.screenOrientation);
            count++;
        }
        assertTrue("No Droy activity declarations were checked", count > 0);
    }
}
