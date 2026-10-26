package io.droyagent.app;

import org.junit.Test;
import static org.junit.Assert.*;

public final class PermissionSetupProviderTest {
    @Test public void onlyRootShellAndTheExactApplicationUidCanConfigurePermissions() {
        int applicationUid = 10345;
        assertTrue(PermissionSetupProvider.allowedUid(0, applicationUid));
        assertTrue(PermissionSetupProvider.allowedUid(2000, applicationUid));
        assertTrue(PermissionSetupProvider.allowedUid(applicationUid, applicationUid));
    }

    @Test public void ordinaryAppsSystemAndIsolatedProcessesCannotConfigurePermissions() {
        int applicationUid = 10345;
        for (int other : new int[]{-1, 1, 1000, 2001, 10000, 10344, 10346, 99001, 110345, Integer.MAX_VALUE})
            assertFalse("Unexpected caller admitted: " + other, PermissionSetupProvider.allowedUid(other, applicationUid));
    }
}
