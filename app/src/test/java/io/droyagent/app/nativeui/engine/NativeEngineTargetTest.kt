package io.droyagent.app.nativeui.engine

import android.content.SharedPreferences
import io.droyagent.app.nativeui.data.NativeApiException
import io.droyagent.app.nativeui.data.NativeRuntimeState
import java.lang.reflect.Proxy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class NativeEngineTargetTest {
    @Test fun originalDraftCaptureIgnoresTheDisplayedDraftAndNeverMutatesItsSelection() = runBlocking {
        val f = fixture(this)
        f.coordinator.bindDraft("original")
        f.coordinator.stagePreset("original", "cordis")
        val captured = f.coordinator.captureSelection("original")!!
        f.coordinator.bindDraft("displayed")
        f.coordinator.stagePreset("displayed", "standard")
        val again = f.coordinator.captureSelection("original")!!
        assertEquals("original", again.getString("draftId"))
        assertEquals(captured.getString("nonce"), again.getString("nonce"))
        assertEquals("cordis", again.getString("agentPresetId"))
        assertEquals("displayed", f.coordinator.state.value.draftId)
        assertEquals("standard", f.coordinator.state.value.draftSelection!!.agentPresetId)
        assertNull(f.coordinator.captureSelection("never-staged"))
        assertTrue(f.writes.isEmpty())
    }

    @Test fun aStoredDraftWithAMismatchedIdentityCannotBeCaptured() = runBlocking {
        val f = fixture(this)
        f.coordinator.stagePreset("original", "cordis")
        val root = JSONObject(f.values.getValue("draft-selections-v1"))
        root.getJSONObject("drafts").getJSONObject("original").put("draftId", "different")
        f.values["draft-selections-v1"] = root.toString()
        try { f.coordinator.captureSelection("original"); fail("A draft identity mismatch must be rejected") }
        catch (error: NativeApiException) { assertEquals("NATIVE_ENGINE_DRAFT_CHANGED", error.code) }
        assertTrue(f.writes.isEmpty())
    }

    @Test fun staleSessionChoicesCannotDispatchAgainstAnotherTarget() = runBlocking {
        val f = fixture(this)
        f.coordinator.bindSession("work-b"); yield()
        val stale = NativeEngineTarget("work-a", null)
        f.coordinator.onAction(NativeEngineAction.SessionPreset("cordis", stale))
        f.coordinator.onAction(NativeEngineAction.SessionPermission("danger-full-access", true, stale))
        f.coordinator.onAction(NativeEngineAction.CompactAt(stale)); yield()
        assertTrue(f.writes.isEmpty())
        assertEquals("NATIVE_ENGINE_DRAFT_CHANGED", f.coordinator.state.value.error)
    }

    @Test fun aStaleBlankDraftChoiceCannotPersistIntoTheNewDraftOrChangeEngineDefaults() = runBlocking {
        val f = fixture(this)
        f.coordinator.bindDraft("original")
        val oldTarget = NativeEngineTarget(null, "original")
        f.coordinator.bindDraft("displayed")
        val before = f.values.toMap()
        f.coordinator.onAction(NativeEngineAction.SessionPreset("cordis", oldTarget))
        f.coordinator.onAction(NativeEngineAction.SessionPermission("danger-full-access", true, oldTarget)); yield()
        assertEquals(before, f.values)
        assertNull(f.coordinator.state.value.draftSelection)
        assertEquals("displayed", f.coordinator.state.value.draftId)
        assertTrue(f.writes.isEmpty())
        assertEquals("NATIVE_ENGINE_DRAFT_CHANGED", f.coordinator.state.value.error)
    }

    @Test fun aTargetSwitchBeforeCoroutineDispatchCancelsBothLegacyAndExplicitCompactActions() = runBlocking {
        for (explicit in listOf(false, true)) {
            val f = fixture(this)
            f.coordinator.bindSession("work-a"); yield()
            f.coordinator.onAction(if (explicit) NativeEngineAction.CompactAt(NativeEngineTarget("work-a", null)) else NativeEngineAction.Compact)
            f.coordinator.bindSession("work-b")
            yield()
            assertTrue("The old fee confirmation cannot compact either target", f.writes.isEmpty())
            assertEquals("NATIVE_ENGINE_DRAFT_CHANGED", f.coordinator.state.value.error)
        }
    }

    @Test fun aTargetSwitchBeforeCoroutineDispatchCancelsTheSessionMutation() = runBlocking {
        val f = fixture(this)
        f.coordinator.bindSession("work-a"); yield()
        f.coordinator.onAction(NativeEngineAction.SessionPermission("danger-full-access", true, NativeEngineTarget("work-a", null)))
        f.coordinator.bindSession("work-b"); yield()
        assertTrue(f.writes.isEmpty())
        assertEquals("NATIVE_ENGINE_DRAFT_CHANGED", f.coordinator.state.value.error)
    }

    @Test fun staleGoalConfirmationsCannotCreateOrClearADifferentSessionsGoal() = runBlocking {
        val f = fixture(this)
        f.coordinator.bindSession("work-a"); yield()
        val oldTarget = NativeEngineTarget("work-a", null)
        f.coordinator.bindSession("work-b"); yield()
        f.coordinator.onAction(NativeEngineAction.Goal("create", "Fixture goal", oldTarget, null))
        f.coordinator.onAction(NativeEngineAction.Goal("clear", expectedTarget = oldTarget,
            expectedGoal = NativeEngineGoal("fixture", 2, "Fixture goal", "active", 0, 40)))
        yield()
        assertTrue(f.writes.isEmpty())
        assertEquals("NATIVE_ENGINE_DRAFT_CHANGED", f.coordinator.state.value.error)
    }

    private data class Fixture(val coordinator: NativeEngineCoordinator, val values: MutableMap<String, String>, val writes: MutableList<String>)
    private suspend fun fixture(scope: CoroutineScope): Fixture {
        val values = mutableMapOf<String, String>(); val writes = mutableListOf<String>()
        val preferences = Proxy.newProxyInstance(SharedPreferences::class.java.classLoader, arrayOf(SharedPreferences::class.java)) { _, method, args ->
            when (method.name) {
                "getString" -> values[args!![0]] ?: args[1]
                "edit" -> {
                    val updates = mutableMapOf<String, String>(); lateinit var editor: SharedPreferences.Editor
                    editor = Proxy.newProxyInstance(SharedPreferences.Editor::class.java.classLoader, arrayOf(SharedPreferences.Editor::class.java)) { _, call, input ->
                        when (call.name) {
                            "putString" -> { updates[input!![0] as String] = input[1] as String; editor }
                            "commit" -> { values.putAll(updates); true }
                            else -> throw AssertionError(call.name)
                        }
                    } as SharedPreferences.Editor
                    editor
                }
                else -> throw AssertionError(method.name)
            }
        } as SharedPreferences
        val catalog = NativeEngineCatalog(true,
            listOf(NativeAgentPreset("standard", null, null, false, true), NativeAgentPreset("cordis", null, null, false, false)),
            "standard", NativeEngineDefault(1, "standard", true), true,
            listOf(NativeDshPermission("workspace-write", "workspace-write", null), NativeDshPermission("danger-full-access", "danger-full-access", null)),
            emptyList(), "workspace-write", NativeEngineDefault(1, "workspace-write", true))
        val gateway = Proxy.newProxyInstance(NativeEngineGateway::class.java.classLoader, arrayOf(NativeEngineGateway::class.java)) { _, method, args ->
            when (method.name) {
                "catalog" -> catalog
                "session" -> NativeEngineSession(args!![0] as String, true, false, "standard", true, "workspace-write", true)
                else -> { writes += method.name; throw AssertionError("Unexpected engine write: ${method.name}") }
            }
        } as NativeEngineGateway
        val coordinator = NativeEngineCoordinator(gateway, NativeEngineDraftStore(preferences), scope)
        coordinator.runtime(NativeRuntimeState(ready = true)); coordinator.refresh(); yield()
        return Fixture(coordinator, values, writes)
    }
}
