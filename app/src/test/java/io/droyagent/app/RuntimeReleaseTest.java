package io.droyagent.app;

import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class RuntimeReleaseTest {
    private RuntimeRelease release() throws Exception {
        return new RuntimeRelease(new JSONObject().put("droyVersion", "test-dev").put("versionCode", 4)
            .put("dshVersion", "1.2.3").put("mobileVersion", "4.5.6").put("sha256", "a".repeat(64)));
    }

    @Test public void assetReleaseMustMatchTheInstalledApkIdentity() throws Exception {
        RuntimeRelease release = release();
        release.verifyPackage("test-dev", 4);
        assertThrows(java.io.IOException.class, () -> release.verifyPackage("new-dev", 4));
        assertThrows(java.io.IOException.class, () -> release.verifyPackage("test-dev", 5));
        assertThrows(java.io.IOException.class, () -> release.verifyPackage(null, 4));
    }

    @Test public void versionCodeMustBeAPositiveAndroidInteger() throws Exception {
        for (Object code : new Object[]{0, -1, 1.5, "4", (long) Integer.MAX_VALUE + 1}) {
            JSONObject manifest = new JSONObject().put("droyVersion", "test-dev").put("versionCode", code)
                .put("dshVersion", "1.2.3").put("mobileVersion", "4.5.6").put("sha256", "a".repeat(64));
            assertThrows(Exception.class, () -> new RuntimeRelease(manifest));
        }
    }

    @Test public void onlyRunningBoundVersionCountsAsReady() throws Exception {
        RuntimeRelease release = release();
        JSONObject ready = new JSONObject().put("ready", true).put("dshVersion", "1.2.3").put("mobileVersion", "4.5.6");
        release.verifyReady(ready);
        for (String field : new String[]{"dshVersion", "mobileVersion"}) {
            JSONObject mismatch = new JSONObject(ready.toString()).put(field, "older");
            assertThrows(java.io.IOException.class, () -> release.verifyReady(mismatch));
        }
        assertThrows(java.io.IOException.class, () -> release.verifyReady(new JSONObject(ready.toString()).put("ready", false)));
    }

    @Test public void manifestMustContainExactBinding() {
        assertThrows(Exception.class, () -> new RuntimeRelease(new JSONObject().put("sha256", "a".repeat(64))));
    }
}
