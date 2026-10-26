package io.droyagent.app;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ProviderInfo;
import androidx.core.content.FileProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import static org.junit.Assert.*;

/** Synthetic files only; this class never downloads or invokes a package installer. */
@RunWith(AndroidJUnit4.class)
public final class SetupComponentsDeviceTest {
    private final Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();

    @Test public void installationEntryIsPrivateAndDoesNotExposePartialDownloads() throws Exception {
        ProviderInfo provider = context.getPackageManager().resolveContentProvider(context.getPackageName() + ".setup-files", 0);
        assertNotNull(provider); assertFalse(provider.exported); assertTrue(provider.grantUriPermissions);
        assertEquals("Setup and media providers must be separate Android components", SetupFileProvider.class.getName(), provider.name);
        assertEquals(context.getPackageName() + ".setup-files", provider.authority);
        ProviderInfo media = context.getPackageManager().resolveContentProvider(context.getPackageName() + ".media-input-files", 0);
        assertNotNull(media);
        assertNotEquals("Duplicate provider component names can break cross-UID URI grants", provider.name, media.name);
        assertEquals(context.getPackageName() + ".media-input-files", media.authority);
        PackageInfo info = context.getPackageManager().getPackageInfo(context.getPackageName(), PackageManager.GET_PERMISSIONS);
        assertTrue(java.util.Arrays.asList(info.requestedPermissions).contains("android.permission.REQUEST_INSTALL_PACKAGES"));
        File partial = new File(context.getCacheDir(), "setup-components/fixture.part");
        assertThrows(IllegalArgumentException.class, () -> FileProvider.getUriForFile(context,
            context.getPackageName() + ".setup-files", partial));
        File capture = new File(context.getFilesDir(), "media-input/captures/component-fixture.png");
        assertThrows(IllegalArgumentException.class, () -> FileProvider.getUriForFile(context,
            context.getPackageName() + ".setup-files", capture));
        File verified = new File(context.getCacheDir(), "setup-components/verified/component-fixture.apk");
        assertEquals(context.getPackageName() + ".setup-files", FileProvider.getUriForFile(context,
            context.getPackageName() + ".setup-files", verified).getAuthority());
        assertThrows(IllegalArgumentException.class, () -> FileProvider.getUriForFile(context,
            context.getPackageName() + ".media-input-files", verified));
    }

    @Test public void syntheticChecksummedFileStillRequiresARealSignedShizukuApk() throws Exception {
        File fixture = File.createTempFile("setup-component-", ".apk", context.getCacheDir());
        byte[] bytes = "public synthetic apk fixture".getBytes(StandardCharsets.UTF_8);
        try {
            try (FileOutputStream output = new FileOutputStream(fixture)) { output.write(bytes); }
            StringBuilder digest = new StringBuilder();
            for (byte part : MessageDigest.getInstance("SHA-256").digest(bytes)) digest.append(String.format("%02x", part & 255));
            JSONObject spec = new JSONObject().put("bytes", bytes.length).put("sha256", digest.toString())
                .put("versionCode", 1086).put("signerSha256", "a".repeat(64));
            assertThrows(IOException.class, () -> SetupComponents.verifyArchive(context, fixture, spec));
            spec.put("sha256", "b".repeat(64));
            assertThrows(IOException.class, () -> SetupComponents.verifyArchive(context, fixture, spec));
        } finally { assertTrue(!fixture.exists() || fixture.delete()); }
    }
}
