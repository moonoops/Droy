package io.droyagent.app.nativeui.engine

import android.content.SharedPreferences
import io.droyagent.app.nativeui.data.NativeApiException
import io.droyagent.app.nativeui.data.NativeRuntimeState
import java.lang.reflect.Proxy
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class NativeEngineCoordinatorTest {
    @Test fun capturesAreDurablePerDraftAndDoNotReadALaterStageDuringPreparation() = runBlocking {
        val f = fixture(this)
        f.coordinator.bindDraft("draft-a")
        f.coordinator.onAction(NativeEngineAction.SessionPreset("cordis"))
        val token = f.coordinator.captureSelection("draft-a")!!
        f.coordinator.onAction(NativeEngineAction.SessionPermission("auto", true))
        assertEquals("auto", f.coordinator.state.value.draftSelection!!.permissionPresetId)
        f.coordinator.prepareSession("work-a", JSONObject(token.toString()))
        assertEquals(1, f.api.presetWrites); assertEquals(0, f.api.permissionWrites)
        assertEquals("cordis", f.api.current.agentPreset)
        assertEquals("workspace-write", f.api.current.permission)
        assertEquals("auto", NativeEngineDraftStore(f.preferences.instance).selection("draft-a")!!.permissionPresetId)
        assertEquals(0, f.api.defaultWrites)
    }

    @Test fun restoringADraftRetainsItsChoiceWithoutChangingEngineDefaults() = runBlocking {
        val f = fixture(this); f.coordinator.bindDraft("draft-a")
        f.coordinator.onAction(NativeEngineAction.SessionPreset("cordis"))
        val restored = NativeEngineCoordinator(f.api, NativeEngineDraftStore(f.preferences.instance), this)
        restored.bindDraft("draft-b"); assertNull(restored.state.value.draftSelection)
        restored.bindDraft("draft-a"); assertEquals("cordis", restored.state.value.draftSelection!!.agentPresetId)
        assertEquals(0, f.api.defaultWrites); assertEquals(0, f.api.presetWrites)
    }

    @Test fun unknownPrepareOutcomesAreReconciledBeforeRetryAndNeverRepeatAnAppliedPermissionCommand() = runBlocking {
        val f = fixture(this); f.coordinator.bindDraft("draft-a")
        f.coordinator.onAction(NativeEngineAction.SessionPreset("cordis"))
        f.coordinator.onAction(NativeEngineAction.SessionPermission("auto", true))
        val token = f.coordinator.captureSelection("draft-a")!!
        f.api.failPermissionAfterApplying = true
        assertFailure { f.coordinator.prepareSession("work-a", token) }
        assertEquals(1, f.api.presetWrites); assertEquals(1, f.api.permissionWrites)
        val restarted = NativeEngineCoordinator(f.api, NativeEngineDraftStore(f.preferences.instance), this)
        f.api.failPermissionAfterApplying = false
        restarted.prepareSession("work-a", JSONObject(token.toString()))
        assertEquals(1, f.api.presetWrites); assertEquals(1, f.api.permissionWrites)
        assertNull(NativeEngineDraftStore(f.preferences.instance).selection("draft-a"))
        restarted.prepareSession("work-a", token)
        assertEquals(1, f.api.permissionWrites)
    }

    @Test fun capturedNonceCannotMoveToAnotherSessionOrAcceptChangedPayload() = runBlocking {
        val f = fixture(this); f.coordinator.bindDraft("draft-a")
        f.coordinator.onAction(NativeEngineAction.SessionPreset("cordis"))
        val token = f.coordinator.captureSelection("draft-a")!!
        f.coordinator.prepareSession("work-a", token)
        val reads = f.api.sessionReads
        assertFailure { f.coordinator.prepareSession("work-b", token) }
        assertFailure { f.coordinator.prepareSession("work-a", JSONObject(token.toString()).put("agentPresetId", "standard")) }
        assertEquals(reads, f.api.sessionReads); assertEquals(1, f.api.presetWrites)
    }

    @Test fun aPreparedSelectionCannotSilentlyUndoALaterExplicitSessionChoice() = runBlocking {
        val f = fixture(this); f.coordinator.bindDraft("draft-a")
        f.coordinator.onAction(NativeEngineAction.SessionPreset("cordis"))
        val token = f.coordinator.captureSelection("draft-a")!!
        f.coordinator.prepareSession("work-a", token)
        f.api.current = f.api.current.copy(agentPreset = "standard")
        assertFailure { f.coordinator.prepareSession("work-a", token) }
        assertEquals(1, f.api.presetWrites)
    }

    @Test fun failedDraftPersistenceCannotBeCapturedAsAConfirmedChoice() = runBlocking {
        val f = fixture(this); f.coordinator.bindDraft("draft-a"); f.preferences.acceptCommit = false
        f.coordinator.onAction(NativeEngineAction.SessionPreset("cordis"))
        assertNull(f.coordinator.state.value.draftSelection)
        assertEquals("NATIVE_ENGINE_DRAFT_STORAGE", f.coordinator.state.value.error)
        assertFailure { f.coordinator.captureSelection("draft-a") }
        assertEquals(0, f.api.presetWrites)
    }

    @Test fun preparedMarkerMustPersistBeforeThePromptOwnerCanContinue() = runBlocking {
        val f = fixture(this); f.coordinator.bindDraft("draft-a")
        f.coordinator.onAction(NativeEngineAction.SessionPermission("auto", true))
        val token = f.coordinator.captureSelection("draft-a")!!
        f.api.afterSessionRead = { if (f.api.sessionReads == 2) f.preferences.acceptCommit = false }
        assertFailure { f.coordinator.prepareSession("work-a", token) }
        assertEquals(1, f.api.permissionWrites)
        f.preferences.acceptCommit = true; f.api.afterSessionRead = {}
        f.coordinator.prepareSession("work-a", token)
        assertEquals(1, f.api.permissionWrites)
    }

    @Test fun aSessionReadFromThePreviousTargetCannotReplaceTheNewTarget() = runBlocking {
        val f = fixture(this); val gate = CompletableDeferred<Unit>(); f.api.sessionGate = gate
        f.coordinator.bindSession("work-a"); yield()
        f.coordinator.bindSession("work-b"); yield(); gate.complete(Unit); yield()
        assertEquals("work-b", f.coordinator.state.value.sessionId)
        assertEquals("work-b", f.coordinator.state.value.session!!.id)
    }

    @Test fun aFreshReadClearsOnlyItsEarlierMissingSessionObservation() = runBlocking {
        val f = fixture(this)
        f.api.sessionFailure = "NATIVE_ENGINE_SESSION_MISSING"
        f.coordinator.bindSession("work-a"); yield()
        assertEquals("NATIVE_ENGINE_SESSION_MISSING", f.coordinator.state.value.error)
        f.api.sessionFailure = null
        f.coordinator.refreshSession(); yield()
        assertTrue(f.coordinator.state.value.session!!.fresh)
        assertNull(f.coordinator.state.value.error)
    }

    @Test fun aSuccessfulReadDoesNotHideAnUnconfirmedPermissionMutation() = runBlocking {
        val f = fixture(this)
        f.coordinator.bindSession("work-a"); yield()
        f.api.failPermissionAfterApplying = true
        f.coordinator.onAction(NativeEngineAction.SessionPermission("auto", true)); yield()
        assertEquals("network_error", f.coordinator.state.value.error)
        f.coordinator.refreshSession(); yield()
        assertEquals("auto", f.coordinator.state.value.session!!.permission)
        assertEquals("network_error", f.coordinator.state.value.error)
        // An identical code from a non-read owner is not cleared just by fetching a session.
        f.coordinator.report("NATIVE_ENGINE_SESSION_MISSING")
        f.coordinator.refreshSession(); yield()
        assertEquals("NATIVE_ENGINE_SESSION_MISSING", f.coordinator.state.value.error)
    }

    @Test fun creatorStagingUsesTheRealDraftAndNeverSendsOrCreatesASession() = runBlocking {
        val f = fixture(this); f.coordinator.stagePreset("creator-draft", "cordis")
        val restore = NativeEngineDraftStore(f.preferences.instance).selection("creator-draft")!!
        assertEquals("cordis", restore.agentPresetId); assertEquals("creator-draft", restore.draftId)
        assertEquals(0, f.api.presetWrites); assertEquals(0, f.api.permissionWrites); assertEquals(0, f.api.sessionReads)
    }

    @Test fun unknownSessionCapabilitiesAreNotReplacedByZeroOrGlobalDefaults() {
        val value = NativeEngineJson.session(JSONObject("""{"sessionId":"work-a","fresh":true}"""))
        assertNull(value.agentPreset); assertNull(value.permission); assertNull(value.presetSelectable); assertNull(value.permissionSelectable)
    }
    @Test fun unknownContextFieldsStayNullWhileRealZeroAndIndependentBreakdownArePreserved() {
        val value = NativeEngineJson.context(JSONObject("""{"projectedTokens":0,"contextWindow":32000,"breakdown":{"systemTokens":100,"messageTokens":250}}"""))!!
        assertNull(value.pressureTokens); assertNull(value.toolsTokens)
        assertEquals(0.0, value.projectedTokens!!, 0.0); assertEquals(32000.0, value.contextWindow!!, 0.0)
        assertEquals(100.0, value.systemTokens!!, 0.0); assertEquals(250.0, value.messageTokens!!, 0.0)
        assertNull(NativeEngineJson.context(null))
    }

    @Test fun aLostCompactStartReceiptCanOnlyPollTheSameIdentityAndDoesNotAutomaticallyDispatchAgain() = runBlocking {
        val f = fixture(this); f.coordinator.bindSession("work-a"); yield(); f.api.failCompactStart = true
        f.coordinator.onAction(NativeEngineAction.Compact); yield()
        assertEquals(1, f.api.compactRequests.size)
        assertEquals("unknown", f.coordinator.state.value.compaction!!.state)
        val id = f.coordinator.state.value.compaction!!.requestId
        f.coordinator.onAction(NativeEngineAction.RefreshCompact); yield()
        assertEquals(1, f.api.compactRequests.size); assertEquals(id, f.api.compactStatusRequests.single().second)
        assertEquals("success", f.coordinator.state.value.compaction!!.outcome)
    }

    @Test fun cancellationAcknowledgementDoesNotPretendTheCommandHasStopped() = runBlocking {
        val f = fixture(this); f.coordinator.bindSession("work-a"); yield()
        f.api.compactPhase = "running"; f.coordinator.onAction(NativeEngineAction.Compact); yield()
        f.coordinator.onAction(NativeEngineAction.CancelCompact); yield()
        assertEquals("cancelling", f.coordinator.state.value.compaction!!.state)
        assertNull(f.coordinator.state.value.compaction!!.outcome)
        assertEquals(1, f.api.compactRequests.size)
        f.coordinator.stopReads(); f.api.compactPhase = "settled"; f.api.compactOutcome = "cancelled"
        f.coordinator.onAction(NativeEngineAction.RefreshCompact); yield()
        assertEquals("cancelled", f.coordinator.state.value.compaction!!.outcome)
        assertEquals(1, f.api.compactRequests.size)
    }

    @Test fun skillsComeFromTheBoundSessionAndLateResultsCannotCrossATargetChange() = runBlocking {
        val f = fixture(this); f.coordinator.bindSession("work-a"); yield()
        f.api.skillsGate = CompletableDeferred()
        f.coordinator.onAction(NativeEngineAction.LoadSkills); yield()
        f.coordinator.bindSession("work-b"); f.coordinator.onAction(NativeEngineAction.LoadSkills); yield()
        f.api.skillsGate!!.complete(Unit); yield()
        assertEquals("work-b", f.coordinator.state.value.skillsSessionId)
        assertEquals("work-b-skill", f.coordinator.state.value.skills.single().name)
        assertFalse(f.coordinator.state.value.skills.single().modelInvocable)
        assertEquals(0, f.api.defaultWrites)
    }

    private suspend fun assertFailure(block: suspend () -> Unit) {
        try { block(); fail("Expected a rejected or unconfirmed engine preparation") } catch (_: NativeApiException) { }
    }
    private data class Fixture(val coordinator: NativeEngineCoordinator, val api: Gateway, val preferences: Preferences)
    private suspend fun fixture(scope: CoroutineScope): Fixture {
        val preferences = Preferences(); val api = Gateway()
        val coordinator = NativeEngineCoordinator(api, NativeEngineDraftStore(preferences.instance), scope)
        coordinator.runtime(NativeRuntimeState(ready = true)); coordinator.refresh(); yield()
        return Fixture(coordinator, api, preferences)
    }
    private class Gateway : NativeEngineGateway {
        val base = NativeEngineCatalog(true, listOf(NativeAgentPreset("standard", null, null, false, true), NativeAgentPreset("cordis", null, null, false, false)), "standard", NativeEngineDefault(1, "standard", true),
            true, listOf(NativeDshPermission("workspace-write", "workspace-write", null), NativeDshPermission("auto", "auto", null)), listOf(NativeDshPermission("workspace-write", "workspace-write", null)), "workspace-write", NativeEngineDefault(1, "workspace-write", true))
        var current = NativeEngineSession("work-a", true, false, "standard", true, "workspace-write", true)
        var presetWrites = 0; var permissionWrites = 0; var defaultWrites = 0; var sessionReads = 0
        var failPermissionAfterApplying = false
        var sessionGate: CompletableDeferred<Unit>? = null
        var sessionFailure: String? = null
        var afterSessionRead: () -> Unit = {}
        var skillsGate: CompletableDeferred<Unit>? = null
        var compactPhase = "settled"; var compactOutcome = "success"; var failCompactStart = false
        val compactRequests = mutableListOf<Pair<String, String>>()
        val compactStatusRequests = mutableListOf<Pair<String, String>>()
        override suspend fun catalog() = base
        override suspend fun session(id: String): NativeEngineSession { sessionReads++; sessionGate?.await(); sessionFailure?.let { throw NativeApiException(it) }; afterSessionRead(); return current.copy(id = id) }
        override suspend fun document(id: String) = NativePresetDocument(id, "- name: fixture")
        override suspend fun defaultPreset(id: String, revision: Long): NativeEngineCatalog { defaultWrites++; return base.copy(defaultPreset = id) }
        override suspend fun defaultPermission(id: String, revision: Long, acceptRisk: Boolean): NativeEngineCatalog { defaultWrites++; return base.copy(defaultPermission = id) }
        override suspend fun sessionPreset(sessionId: String, id: String): NativeEngineSession { presetWrites++; current = current.copy(id = sessionId, agentPreset = id); return current }
        override suspend fun sessionPermission(sessionId: String, id: String, acceptRisk: Boolean): NativeEngineSession {
            permissionWrites++; current = current.copy(id = sessionId, permission = id)
            if (failPermissionAfterApplying) throw NativeApiException("network_error")
            return current
        }
        override suspend fun skills(sessionId: String): Pair<String, List<NativeComposerSkill>> {
            skillsGate?.await(); return sessionId to listOf(NativeComposerSkill("$sessionId-skill", "Fixture", false, "/fixture/SKILL.md", null))
        }
        override suspend fun compact(sessionId: String, requestId: String): NativeCompactOperation {
            compactRequests.add(sessionId to requestId)
            if (failCompactStart) throw NativeApiException("network_error")
            return NativeCompactOperation(sessionId, requestId, "running")
        }
        override suspend fun compactStatus(sessionId: String, requestId: String): NativeCompactOperation {
            compactStatusRequests.add(sessionId to requestId)
            return NativeCompactOperation(sessionId, requestId, compactPhase, if (compactPhase == "settled") compactOutcome else null)
        }
        override suspend fun cancelCompact(sessionId: String, requestId: String): NativeCompactOperation { compactPhase = "cancelling"; return NativeCompactOperation(sessionId, requestId, "cancelling") }
    }
    private class Preferences {
        val values = mutableMapOf<String, String>(); var acceptCommit = true
        val instance: SharedPreferences = Proxy.newProxyInstance(SharedPreferences::class.java.classLoader, arrayOf(SharedPreferences::class.java)) { _, method, args -> when (method.name) {
            "getString" -> values[args!![0]] ?: args[1]; "edit" -> editor(); else -> throw AssertionError(method.name)
        } } as SharedPreferences
        private fun editor(): SharedPreferences.Editor {
            val pending = mutableMapOf<String, String>(); lateinit var editor: SharedPreferences.Editor
            editor = Proxy.newProxyInstance(SharedPreferences.Editor::class.java.classLoader, arrayOf(SharedPreferences.Editor::class.java)) { _, method, args -> when (method.name) {
                "putString" -> { pending[args!![0] as String] = args[1] as String; editor }
                "commit" -> { values.putAll(pending); acceptCommit }
                else -> throw AssertionError(method.name)
            } } as SharedPreferences.Editor
            return editor
        }
    }
}
