package io.droyagent.app;

import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public final class NativeCompanionMemoryPurgeTest {
    private JSONObject journal() throws Exception {
        return new JSONObject().put("kind", "memory-purge").put("operationId", "memory-management-test")
            .put("memorySpaceId", "space-one").put("companionId", "partner-one")
            .put("admissionEpoch", "epoch-one").put("hostPid", 1234);
    }
    @Test public void offlineAuthorityIsBoundToSpaceAndCannotAuthorizeCompanionDeletion() throws Exception {
        JSONObject journal = journal();
        JSONObject request = new JSONObject(journal.toString()).put("scope", "companion-memory-purge").put("root", "/fixture/home");
        assertTrue(NativeCompanionDeletion.matchesAuthority(journal, "/fixture/home", request));
        for (String key : new String[]{"operationId", "memorySpaceId", "companionId", "admissionEpoch", "root", "scope"}) {
            JSONObject wrong = new JSONObject(request.toString()).put(key, "other");
            assertFalse(key, NativeCompanionDeletion.matchesAuthority(journal, "/fixture/home", wrong));
            wrong.remove(key);
            assertFalse(key, NativeCompanionDeletion.matchesAuthority(journal, "/fixture/home", wrong));
        }
        request.put("scope", "companion-deletion");
        assertFalse(NativeCompanionDeletion.matchesAuthority(journal, "/fixture/home", request));
    }
    @Test public void liveReceiptCannotSwitchHostOrOwnerAfterLeaseIsBound() throws Exception {
        JSONObject journal = journal(), receipt = new JSONObject(journal.toString()).put("phase", "waiting");
        NativeCompanionMemoryPurge.validateReceipt(journal, receipt, true);
        for (String key : new String[]{"operationId", "memorySpaceId", "companionId", "admissionEpoch", "hostPid"}) {
            JSONObject wrong = new JSONObject(receipt.toString()).put(key, key.equals("hostPid") ? 4321 : "other");
            try { NativeCompanionMemoryPurge.validateReceipt(journal, wrong, true); fail(key); }
            catch (java.io.IOException expected) { assertTrue(expected.getMessage().startsWith("COMPANION_")); }
        }
    }
    @Test public void failedPreparationIsNeverAcceptedAsAnOfflineLease() throws Exception {
        JSONObject journal = journal(), receipt = new JSONObject(journal.toString()).put("phase", "failed");
        try { NativeCompanionMemoryPurge.validateReceipt(journal, receipt, false); fail("Expected failed preparation"); }
        catch (java.io.IOException expected) { assertEquals("COMPANION_MEMORY_PREPARATION_FAILED", expected.getMessage()); }
    }
    @Test public void deleteAndMemoryPurgeCannotClaimNativeMaintenanceTogether() {
        NativeRuntimeHost.companionMaintenance(false);
        try {
            assertTrue(NativeRuntimeHost.claimCompanionMaintenance());
            assertFalse(NativeRuntimeHost.claimCompanionMaintenance());
            assertFalse(RuntimeService.canStart());
        } finally { NativeRuntimeHost.companionMaintenance(false); }
    }
}
