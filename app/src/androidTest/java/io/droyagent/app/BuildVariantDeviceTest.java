package io.droyagent.app;

import android.content.ComponentName;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.os.LocaleList;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.File;
import java.util.Arrays;
import java.util.Locale;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

/** Inspects installed package identities without changing either app's data or permissions. */
@RunWith(AndroidJUnit4.class)
public final class BuildVariantDeviceTest {
    private static final String RELEASE = "io.droyagent.app";
    private static final String DEVELOPMENT = RELEASE + ".dev";

    @Test public void installedComponentsAndFilesBelongToTheSelectedVariant() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        String name = context.getPackageName();
        assertTrue(name.equals(RELEASE) || name.equals(DEVELOPMENT));
        assertEquals(name + ".test", InstrumentationRegistry.getInstrumentation().getContext().getPackageName());
        assertEquals(new File(context.getApplicationInfo().dataDir, "files").getCanonicalPath(),
                context.getFilesDir().getCanonicalPath());
        assertEquals(name, context.getResources().getResourcePackageName(R.id.test_input));
        PackageManager packages = context.getPackageManager();
        String[][] providers = {{"androidx.core.content.FileProvider", ".media-input-files"},
                {"io.droyagent.app.BrowserUiProtection", ".browser-ui"},
                {"io.droyagent.app.SetupFileProvider", ".setup-files"},
                {"io.droyagent.app.PermissionSetupProvider", ".permissions"}};
        for (String[] provider : providers)
            assertEquals(name + provider[1], packages.getProviderInfo(new ComponentName(name, provider[0]), 0).authority);
        assertEquals(name, packages.getActivityInfo(new ComponentName(name, "io.droyagent.app.MainActivity"), 0).packageName);
        assertEquals(name, packages.getServiceInfo(new ComponentName(name,
                "io.droyagent.androiduse.DroyAccessibilityService"), 0).packageName);
    }

    @Test public void developmentLabelIsDistinctInBothSupportedLanguages() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        assumeTrue("Development labels belong to the development variant", context.getPackageName().equals(DEVELOPMENT));
        assertTrue((context.getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0);
        for (Locale locale : new Locale[]{Locale.ENGLISH, Locale.SIMPLIFIED_CHINESE}) {
            Configuration configuration = new Configuration(context.getResources().getConfiguration());
            configuration.setLocales(new LocaleList(locale));
            Context localized = context.createConfigurationContext(configuration);
            String label = localized.getString(context.getApplicationInfo().labelRes);
            assertTrue("Development app label is ambiguous in " + locale + ": " + label,
                    label.contains(locale.getLanguage().equals("zh") ? "开发版" : "Dev"));
        }
    }

    @Test public void coinstalledVariantHasSeparateUidDataAndSigningIdentity() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        PackageManager packages = context.getPackageManager();
        String name = context.getPackageName(), other = name.equals(DEVELOPMENT) ? RELEASE : DEVELOPMENT;
        PackageInfo retained;
        try { retained = packages.getPackageInfo(other, PackageManager.GET_SIGNING_CERTIFICATES); }
        catch (PackageManager.NameNotFoundException absent) {
            assumeTrue("Install both variants to verify coexistence", false);
            return;
        }
        PackageInfo current = packages.getPackageInfo(name, PackageManager.GET_SIGNING_CERTIFICATES);
        assertNotEquals(current.applicationInfo.uid, retained.applicationInfo.uid);
        assertNotEquals(current.applicationInfo.dataDir, retained.applicationInfo.dataDir);
        assertNotNull(current.signingInfo);
        assertNotNull(retained.signingInfo);
        assertFalse("Development must not reuse the official signing certificate",
                Arrays.equals(current.signingInfo.getApkContentsSigners(), retained.signingInfo.getApkContentsSigners()));
    }
}
