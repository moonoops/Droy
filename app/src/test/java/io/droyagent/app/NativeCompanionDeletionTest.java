package io.droyagent.app;

import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public final class NativeCompanionDeletionTest {
    @Test public void offlineCapabilityCannotBeReusedForAnotherOwnerOrRoot() throws Exception {
        JSONObject identity = new JSONObject().put("operationId", "synthetic-operation")
            .put("companionId", "synthetic-partner").put("admissionEpoch", "synthetic-epoch");
        JSONObject request = new JSONObject(identity.toString()).put("root", "/synthetic/home").put("scope", "companion-deletion");
        assertTrue(NativeCompanionDeletion.matchesAuthority(identity, "/synthetic/home", request));
        for (String key : new String[]{"operationId", "companionId", "admissionEpoch", "root", "scope"}) {
            JSONObject changed = new JSONObject(request.toString()).put(key, "different");
            assertFalse(key, NativeCompanionDeletion.matchesAuthority(identity, "/synthetic/home", changed));
            changed.remove(key);
            assertFalse(key, NativeCompanionDeletion.matchesAuthority(identity, "/synthetic/home", changed));
        }
    }

    @Test public void pendingLiveMaintenanceRejectsAutomaticRestart() throws Exception {
        NativeRuntimeHost.companionMaintenance(true);
        try {
            assertFalse(RuntimeService.canStart());
            NativeRuntimeHost.start(null);
        } finally { NativeRuntimeHost.companionMaintenance(false); }
    }
}
