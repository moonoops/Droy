package io.droyagent.androiduse;

import android.accessibilityservice.AccessibilityService;
import android.provider.Settings;
import org.junit.Test;
import static org.junit.Assert.*;

public class SystemNavigationTest {
    @Test public void navigationUsesThePublicSystemAction() throws Exception {
        assertEquals(AccessibilityService.GLOBAL_ACTION_HOME, SystemNavigation.globalId("home"));
        assertEquals(AccessibilityService.GLOBAL_ACTION_RECENTS, SystemNavigation.globalId("recents"));
        assertEquals(AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS, SystemNavigation.globalId("notifications"));
        assertEquals(AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS, SystemNavigation.globalId("quick_settings"));
        assertThrows(ActionException.class, () -> SystemNavigation.globalId("power_dialog"));
        assertThrows(ActionException.class, () -> SystemNavigation.globalId(2));
    }

    @Test public void settingsUseStandardActionsWithoutVendorComponents() throws Exception {
        assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, SystemNavigation.settingsAction("app_details"));
        assertEquals(Settings.ACTION_PRIVACY_SETTINGS, SystemNavigation.settingsAction("privacy"));
        assertEquals(Settings.ACTION_BATTERY_SAVER_SETTINGS, SystemNavigation.settingsAction("battery_saver"));
        assertEquals(Settings.ACTION_USAGE_ACCESS_SETTINGS, SystemNavigation.settingsAction("usage_access"));
        assertEquals(Settings.ACTION_WIFI_SETTINGS, SystemNavigation.settingsAction("wifi"));
        assertThrows(ActionException.class, () -> SystemNavigation.page("component:vendor.Settings"));
        assertThrows(ActionException.class, () -> SystemNavigation.page(null));
    }
}
