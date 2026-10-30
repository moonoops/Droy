package io.droyagent.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.droyagent.app.nativeui.memory.NativeMemoryClient
import io.droyagent.app.nativeui.memory.NativeMemoryMutation
import java.io.File
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Observes the installed app's readiness. Does not enable memory, probe Root, grant access or read records. */
@RunWith(AndroidJUnit4::class)
class NativeMemoryReadinessObservationDeviceTest {
    @Test fun observeActualReadinessWithoutStartingAuthorizationOrChangingSettings() = runBlocking {
        assumeTrue("Explicit read-only observation required", InstrumentationRegistry.getArguments().getString("nativeMemoryObservation") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals("io.droyagent.app.dev", context.packageName)
        DeviceUi.await("The host must open the installed app and wait for its runtime", 30_000) { NativeRuntimeHost.inspect(context).ready }
        val rootBefore = SetupRootVerification.status()
        assertFalse("Do not overlap a user Root request", rootBefore.getBoolean("running"))
        val memoryRootBefore = MemoryRuntime.get(context).status().getJSONObject("root")
        val client = NativeMemoryClient()
        val state = client.status()
        val pending = client.mutate(NativeMemoryMutation("pendingAccess", "{}")) as JSONArray
        val endpoints = client.endpoints()
        val rootAfter = SetupRootVerification.status()
        val memoryRootAfter = MemoryRuntime.get(context).status().getJSONObject("root")
        assertEquals(rootBefore.getLong("checkedAt"), rootAfter.getLong("checkedAt"))
        assertEquals(rootBefore.getBoolean("passed"), rootAfter.getBoolean("passed"))
        assertFalse(rootAfter.getBoolean("running"))
        assertEquals(memoryRootBefore.getLong("checkedAt"), memoryRootAfter.getLong("checkedAt"))
        assertEquals(memoryRootBefore.getBoolean("authorized"), memoryRootAfter.getBoolean("authorized"))
        assertFalse("Personal memory must not require Root", memoryRootAfter.getBoolean("required"))
        val receipt = JSONObject().put("available", state.available).put("gateReady", state.gateReady)
            .put("gateReason", state.gateReason).put("rootAuthorized", state.rootAuthorized ?: JSONObject.NULL)
            .put("enabled", state.settings.enabled).put("managementAvailable", state.managementAvailable)
            .put("pendingAccessRequests", pending.length()).put("storedEndpointCount", endpoints.size)
            .put("rootProbeChanged", false).put("coverage", "readiness_only_not_first_memory_access_consent")
        val evidence = File(context.getExternalFilesDir(null), "native-isolated-evidence")
        assertTrue(evidence.isDirectory || evidence.mkdirs())
        File(evidence, "memory-readiness-observation.json").writeText(receipt.toString(2))
    }
}
