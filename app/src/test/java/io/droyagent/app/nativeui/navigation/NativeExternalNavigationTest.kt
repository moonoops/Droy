package io.droyagent.app.nativeui.navigation

import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import org.junit.Assert.*
import org.junit.Test

class NativeExternalNavigationTest {
    private val importedA = "077384ae-6b6c-434e-81cf-52fd8033531c"
    private val importedB = "15134ba2-5f39-4528-b4ab-3042784a9d6d"
    private val ready = NativeExternalNavigationConditions(true, true, true, true)
    private fun engine(id: String, archived: Boolean = false) = NativeExternalResolvedTarget.EngineSession(
        NativeExternalVerifiedSession(id, "workspace", NativeExternalSessionOwner.WORKBENCH, false, archived))
    private class Store(var current: NativeExternalNavigationRequest? = null) : NativeExternalNavigationStore {
        val writes = mutableListOf<NativeExternalNavigationRequest?>()
        var fail = false
        var applyBeforeFailure = false
        var beforeSave: suspend (NativeExternalNavigationRequest?) -> Unit = {}
        override suspend fun load() = current
        override suspend fun save(request: NativeExternalNavigationRequest?) {
            beforeSave(request)
            writes += request
            if (!fail || applyBeforeFailure) current = request
            if (fail) throw IOException("fixture_storage")
        }
    }
    private class Gateway : NativeExternalNavigationGateway {
        val calls = mutableListOf<NativeExternalNavigationTarget>()
        var answer: suspend (NativeExternalNavigationTarget) -> NativeExternalNavigationResolution = { target ->
            val result = when (target) {
                is NativeExternalNavigationTarget.EngineSession -> NativeExternalResolvedTarget.EngineSession(
                    NativeExternalVerifiedSession(target.sessionId, null, NativeExternalSessionOwner.WORKBENCH, false, false), target.petRequestId)
                is NativeExternalNavigationTarget.Root -> NativeExternalResolvedTarget.Root(target.page)
                NativeExternalNavigationTarget.NotificationManagement -> NativeExternalResolvedTarget.NotificationManagement
                is NativeExternalNavigationTarget.AudioAuthorization -> NativeExternalResolvedTarget.AudioAuthorization(target.captureId)
                is NativeExternalNavigationTarget.ImportedMedia -> NativeExternalResolvedTarget.ImportedMedia(target.importId, target.importId)
                is NativeExternalNavigationTarget.VoiceWake -> NativeExternalResolvedTarget.VoiceWake(target.eventId)
                is NativeExternalNavigationTarget.BrowserTab -> NativeExternalResolvedTarget.BrowserTab(target.browserSessionId)
                is NativeExternalNavigationTarget.CompanionChat -> NativeExternalResolvedTarget.CompanionChat(target.companionId)
                is NativeExternalNavigationTarget.CompanionTask -> NativeExternalResolvedTarget.CompanionTask(
                    NativeExternalVerifiedSession(target.sessionId, "managed", NativeExternalSessionOwner.COMPANION, true, false, target.companionId, target.taskId), target.petRequestId)
            }
            NativeExternalNavigationResolution.Ready(result)
        }
        override suspend fun resolve(target: NativeExternalNavigationTarget): NativeExternalNavigationResolution { calls += target; return answer(target) }
    }
    private fun parsed(values: Map<String, Any?>) = (NativeExternalNavigationParser.parse(values) as NativeExternalNavigationInput.Target).target

    @Test fun parsesOnlyExistingScalarIntentContracts() {
        assertEquals(NativeExternalNavigationTarget.EngineSession("task", "question"), parsed(mapOf("petTaskId" to "task", "petRequestId" to "question")))
        assertEquals(NativeExternalNavigationTarget.EngineSession("session"), parsed(mapOf("sessionId" to "session")))
        assertEquals(NativeExternalNavigationTarget.CompanionChat("kaka"), parsed(mapOf("companionId" to "kaka")))
        assertEquals(NativeExternalNavigationTarget.BrowserTab("browser"), parsed(mapOf("browserSessionId" to "browser")))
        assertEquals(NativeExternalNavigationTarget.NotificationManagement, parsed(mapOf("notificationManagement" to true)))
        assertEquals(NativeExternalNavigationTarget.AudioAuthorization("capture"), parsed(mapOf("audioCaptureId" to "capture")))
        assertEquals(NativeExternalNavigationTarget.VoiceWake(42), parsed(mapOf("voiceWakeEventId" to 42L, "voiceWakeTarget" to "standard")))
        assertEquals(NativeExternalNavigationTarget.ImportedMedia(importedA), parsed(mapOf("mediaImportId" to importedA)))
        NativeExternalRoot.entries.forEach { assertEquals(NativeExternalNavigationTarget.Root(it), parsed(mapOf("destination" to it.wire))) }
    }
    @Test fun rejectsAmbiguityAndKeepsLegacyTaskAliasExplicit() {
        assertEquals(NativeExternalNavigationTarget.EngineSession("same"), parsed(mapOf("sessionId" to "same", "petTaskId" to "same")))
        assertEquals(NativeExternalNavigationInput.Invalid(NativeExternalParseFailure.AMBIGUOUS), NativeExternalNavigationParser.parse(mapOf("sessionId" to "a", "petTaskId" to "b")))
        assertEquals(NativeExternalNavigationInput.Invalid(NativeExternalParseFailure.AMBIGUOUS), NativeExternalNavigationParser.parse(mapOf("companionId" to "kaka", "browserSessionId" to "b")))
        assertEquals(NativeExternalNavigationInput.None, NativeExternalNavigationParser.parse(mapOf("taskId" to "not_an_activity_contract")))
        assertEquals(NativeExternalNavigationInput.None, NativeExternalNavigationParser.parse(mapOf("notificationManagement" to false)))
    }
    @Test fun malformedTypesAndIdentitiesDoNotBecomeNewSessions() {
        val malformed = listOf<Map<String, Any?>>(
            mapOf("sessionId" to "../other"), mapOf("sessionId" to ""), mapOf("sessionId" to "a".repeat(257)),
            mapOf("companionId" to 3), mapOf("petRequestId" to "orphan"), mapOf("notificationManagement" to "true"),
            mapOf("voiceWakeEventId" to 0L, "voiceWakeTarget" to "standard"), mapOf("voiceWakeEventId" to "42", "voiceWakeTarget" to "standard"),
            mapOf("voiceWakeEventId" to 42L), mapOf("voiceWakeEventId" to 42L, "voiceWakeTarget" to "companion"),
            mapOf("mediaImportId" to "not-uuid"), mapOf("destination" to "newSession"))
        malformed.forEach { assertEquals(NativeExternalNavigationInput.Invalid(NativeExternalParseFailure.MALFORMED), NativeExternalNavigationParser.parse(it)) }
    }
    @Test fun journalRoundTripsEveryTargetWithoutContentOrUris() = runBlocking {
        val targets = listOf(NativeExternalNavigationTarget.EngineSession("engine", "ask"), NativeExternalNavigationTarget.CompanionChat("kaka"),
            NativeExternalNavigationTarget.CompanionTask("kaka", "task", "engine", "ask"), NativeExternalNavigationTarget.BrowserTab("browser"),
            NativeExternalNavigationTarget.NotificationManagement, NativeExternalNavigationTarget.VoiceWake(Long.MAX_VALUE),
            NativeExternalNavigationTarget.AudioAuthorization("capture"), NativeExternalNavigationTarget.ImportedMedia(importedA),
            NativeExternalNavigationTarget.Root(NativeExternalRoot.SETTINGS))
        var raw: String? = null
        val store = NativeExternalNavigationJsonStore({ raw }, { raw = it; true })
        targets.forEachIndexed { index, target ->
            val request = NativeExternalNavigationRequest("request-$index", target)
            store.save(request); assertEquals(request, store.load())
            assertFalse(raw!!.contains("content://")); assertFalse(raw!!.contains("text"))
        }
        store.save(null); assertNull(raw); assertNull(store.load())
    }
    @Test fun journalRejectsUnknownFieldsDuplicatesCoercionsAndTrailingData() = runBlocking {
        val original = NativeExternalNavigationJsonStore.encode(NativeExternalNavigationRequest("id", NativeExternalNavigationTarget.EngineSession("engine")))
        val invalid = listOf(original.replace("\"version\":1", "\"version\":1.0"), original.replace("\"version\":1", "\"version\":1,\"version\":1"),
            original.dropLast(1) + ",\"token\":\"fixture-secret\"}", original + "{}", "null", " ".repeat(4097),
            original.replace("\"engine\"", "\"../engine\""), original.replace("\"requestId\":\"id\"", "\"requestId\":3"))
        invalid.forEach { raw ->
            val store = NativeExternalNavigationJsonStore({ raw }, { fail("must_not_write"); false })
            try { store.load(); fail("accepted_invalid_journal") } catch (_: NativeExternalNavigationStorageException) { }
        }
    }
    @Test fun falseDurableWriteIsAnExplicitStorageFailure() = runBlocking {
        val store = NativeExternalNavigationJsonStore({ null }, { false })
        try { store.save(NativeExternalNavigationRequest("id", NativeExternalNavigationTarget.Root(NativeExternalRoot.MESSAGES))); fail("write_accepted") }
        catch (_: NativeExternalNavigationStorageException) { }
    }
    @Test fun waitsForForegroundSetupPermissionFlowAndRequiredRuntime() = runBlocking {
        val store = Store(); val gateway = Gateway(); val model = NativeExternalNavigationCoordinator(gateway, store, this)
        val request = model.accept(NativeExternalNavigationTarget.EngineSession("engine"))
        assertEquals(request, store.current); assertEquals(NativeExternalNavigationBlocker.FOREGROUND, model.state.value.blocker)
        model.updateConditions(ready.copy(setupComplete = false)); assertEquals(NativeExternalNavigationBlocker.SETUP, model.state.value.blocker)
        model.updateConditions(ready.copy(permissionFlowIdle = false)); assertEquals(NativeExternalNavigationBlocker.PERMISSION_FLOW, model.state.value.blocker)
        model.updateConditions(ready.copy(runtimeReady = false)); assertEquals(NativeExternalNavigationBlocker.RUNTIME, model.state.value.blocker)
        assertTrue(gateway.calls.isEmpty())
        model.updateConditions(ready); yield(); assertEquals(NativeExternalNavigationPhase.READY, model.state.value.phase)
        assertEquals(request, store.current)
    }
    @Test fun rootNotificationsAudioAndLocalShareRemainAvailableWithStoppedEngine() = runBlocking {
        val targets = listOf(NativeExternalNavigationTarget.Root(NativeExternalRoot.SETTINGS), NativeExternalNavigationTarget.NotificationManagement,
            NativeExternalNavigationTarget.AudioAuthorization("capture"), NativeExternalNavigationTarget.ImportedMedia(importedA))
        for (target in targets) {
            val model = NativeExternalNavigationCoordinator(Gateway(), Store(), this)
            model.updateConditions(ready.copy(runtimeReady = false)); model.accept(target); yield()
            assertEquals(NativeExternalNavigationPhase.READY, model.state.value.phase)
        }
    }
    @Test fun lateNonCooperativeResolveCannotReplaceNewerIntent() = runBlocking {
        val gateway = Gateway(); val old = CompletableDeferred<NativeExternalNavigationResolution>()
        gateway.answer = { target -> if ((target as NativeExternalNavigationTarget.EngineSession).sessionId == "old") withContext(NonCancellable) { old.await() }
            else NativeExternalNavigationResolution.Ready(engine("new")) }
        var next = 0; val store = Store(); val model = NativeExternalNavigationCoordinator(gateway, store, this, { "request-${next++}" })
        model.updateConditions(ready); val first = model.accept(NativeExternalNavigationTarget.EngineSession("old")); yield()
        val latest = model.accept(NativeExternalNavigationTarget.EngineSession("new")); yield()
        old.complete(NativeExternalNavigationResolution.Ready(engine("old"))); yield(); yield()
        assertEquals(latest, model.state.value.request); assertEquals(latest, store.current)
        assertEquals("new", (model.state.value.resolved as NativeExternalResolvedTarget.EngineSession).session.id)
        assertFalse(model.ackOpened(first.id, NativeExternalNavigationOpened(first.target, true, true)))
    }
    @Test fun acknowledgeRequiresActualMatchingLoadedResumedSurfaceAndDurableClear() = runBlocking {
        val store = Store(); val model = NativeExternalNavigationCoordinator(Gateway(), store, this)
        model.updateConditions(ready); val request = model.accept(NativeExternalNavigationTarget.EngineSession("engine", "ask")); yield()
        assertFalse(model.ackOpened("stale", NativeExternalNavigationOpened(request.target, true, true)))
        assertFalse(model.ackOpened(request.id, NativeExternalNavigationOpened(request.target, false, true)))
        assertFalse(model.ackOpened(request.id, NativeExternalNavigationOpened(request.target, true, false)))
        assertFalse(model.ackOpened(request.id, NativeExternalNavigationOpened(NativeExternalNavigationTarget.EngineSession("engine"), true, true)))
        assertEquals(request, store.current)
        assertTrue(model.ackOpened(request.id, NativeExternalNavigationOpened(request.target, true, true)))
        assertNull(store.current); assertEquals(NativeExternalNavigationPhase.EMPTY, model.state.value.phase)
    }
    @Test fun incomingSlowDurableWriteImmediatelyHidesOldReadySurface() = runBlocking {
        var next = 0; val store = Store(); val model = NativeExternalNavigationCoordinator(Gateway(), store, this, { "id-${next++}" })
        model.updateConditions(ready); val old = model.accept(NativeExternalNavigationTarget.EngineSession("old")); yield()
        val writing = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        store.beforeSave = { if ((it?.target as? NativeExternalNavigationTarget.EngineSession)?.sessionId == "latest") { writing.complete(Unit); release.await() } }
        val incoming = async { model.accept(NativeExternalNavigationTarget.EngineSession("latest")) }
        writing.await()
        assertEquals(NativeExternalNavigationPhase.PERSISTING, model.state.value.phase)
        assertNull(model.state.value.resolved)
        assertEquals(old, store.current)
        release.complete(Unit); val latest = incoming.await(); yield()
        assertEquals(latest, model.state.value.request)
        assertFalse(model.ackOpened(old.id, NativeExternalNavigationOpened(old.target, true, true)))
    }
    @Test fun backgroundingInvalidatesReadyWithoutConsumingPending() = runBlocking {
        val store = Store(); val gateway = Gateway(); val model = NativeExternalNavigationCoordinator(gateway, store, this)
        model.updateConditions(ready); val request = model.accept(NativeExternalNavigationTarget.EngineSession("engine")); yield()
        model.updateConditions(ready.copy(foreground = false))
        assertNull(model.state.value.resolved); assertEquals(request, store.current)
        assertFalse(model.ackOpened(request.id, NativeExternalNavigationOpened(request.target, true, true)))
        model.updateConditions(ready); yield(); assertEquals(2, gateway.calls.size)
    }
    @Test fun disconnectionIsRetainedAndOnlyExplicitRetryReadsAgain() = runBlocking {
        val gateway = Gateway(); gateway.answer = { throw IOException("fixture_disconnected") }
        val store = Store(); val model = NativeExternalNavigationCoordinator(gateway, store, this)
        model.updateConditions(ready); val request = model.accept(NativeExternalNavigationTarget.EngineSession("engine")); yield(); yield()
        assertEquals(NativeExternalNavigationBlocker.DISCONNECTED, model.state.value.blocker); assertEquals(request, store.current)
        assertEquals(1, gateway.calls.size); assertFalse(model.ackFailure(request.id, NativeExternalNavigationFailure.NOT_FOUND))
        gateway.answer = { NativeExternalNavigationResolution.Ready(engine("engine")) }; model.retry(); yield()
        assertEquals(NativeExternalNavigationPhase.READY, model.state.value.phase); assertEquals(2, gateway.calls.size)
    }
    @Test fun unexpectedReaderFailureIsVisibleAndCannotBeAcknowledgedAsNotFound() = runBlocking {
        val gateway = Gateway(); gateway.answer = { error("fixture_reader_bug") }
        val store = Store(); val model = NativeExternalNavigationCoordinator(gateway, store, this)
        model.updateConditions(ready); val request = model.accept(NativeExternalNavigationTarget.EngineSession("engine")); yield()
        assertEquals(NativeExternalNavigationBlocker.RESOLVER_FAILED, model.state.value.blocker)
        assertFalse(model.ackFailure(request.id, NativeExternalNavigationFailure.NOT_FOUND)); assertEquals(request, store.current)
    }
    @Test fun notFoundNeedsExplicitMatchingFailureAcknowledgement() = runBlocking {
        val gateway = Gateway(); gateway.answer = { NativeExternalNavigationResolution.Missing(NativeExternalNavigationFailure.NOT_FOUND) }
        val store = Store(); val model = NativeExternalNavigationCoordinator(gateway, store, this)
        model.updateConditions(ready); val request = model.accept(NativeExternalNavigationTarget.EngineSession("missing")); yield()
        assertEquals(request, store.current); assertEquals(NativeExternalNavigationPhase.FAILED, model.state.value.phase)
        assertFalse(model.ackFailure("stale", NativeExternalNavigationFailure.NOT_FOUND))
        assertFalse(model.ackFailure(request.id, NativeExternalNavigationFailure.ACCESS_DENIED))
        assertTrue(model.ackFailure(request.id, NativeExternalNavigationFailure.NOT_FOUND)); assertNull(store.current)
    }
    @Test fun archivedConversationOpensItsReadOnlyHistoryAndRetainsItsIdentity() = runBlocking {
        val gateway = Gateway(); gateway.answer = { NativeExternalNavigationResolution.Ready(engine("engine", true)) }
        val store = Store(); val model = NativeExternalNavigationCoordinator(gateway, store, this)
        model.updateConditions(ready); val request = model.accept(NativeExternalNavigationTarget.EngineSession("engine")); yield()
        assertEquals(NativeExternalNavigationPhase.READY, model.state.value.phase)
        val session = (model.state.value.resolved as NativeExternalResolvedTarget.EngineSession).session
        assertTrue(session.archived); assertTrue(session.readOnly); assertEquals("engine", session.id)
        assertEquals(request, store.current)
        assertTrue(model.ackOpened(request.id, NativeExternalNavigationOpened(request.target, true, true)))
    }
    @Test fun mismatchedSessionNeverFallsBackToANewConversation() = runBlocking {
        val gateway = Gateway(); gateway.answer = { NativeExternalNavigationResolution.Ready(engine("other")) }
        val store = Store(); val model = NativeExternalNavigationCoordinator(gateway, store, this)
        model.updateConditions(ready); val request = model.accept(NativeExternalNavigationTarget.EngineSession("engine")); yield()
        assertEquals(NativeExternalNavigationFailure.INVALID_RESOLUTION, model.state.value.failure)
        assertNull(model.state.value.resolved); assertEquals(request, store.current)
    }
    @Test fun managedTaskResolutionRequiresRealOwnerAndMatchingTaskSurface() = runBlocking {
        val session = NativeExternalVerifiedSession("engine", "managed", NativeExternalSessionOwner.COMPANION, true, false, "kaka", "task")
        val resolved = NativeExternalResolvedTarget.CompanionTask(session, "ask")
        val gateway = Gateway(); gateway.answer = { NativeExternalNavigationResolution.Ready(resolved) }
        val store = Store(); val model = NativeExternalNavigationCoordinator(gateway, store, this)
        model.updateConditions(ready); val request = model.accept(NativeExternalNavigationTarget.EngineSession("engine", "ask")); yield()
        assertFalse(model.ackOpened(request.id, NativeExternalNavigationOpened(request.target, true, true)))
        assertTrue(model.ackOpened(request.id, NativeExternalNavigationOpened(resolved.surface, true, true)))
        try { NativeExternalVerifiedSession("engine", null, NativeExternalSessionOwner.COMPANION, false, false, "kaka", "task"); fail("writable_companion") }
        catch (_: IllegalArgumentException) { }
    }
    @Test fun importedBOpensProvenFifoHeadAWithoutRequiringBToJumpAhead() = runBlocking {
        val gateway = Gateway(); gateway.answer = { NativeExternalNavigationResolution.Ready(NativeExternalResolvedTarget.ImportedMedia(importedB, importedA)) }
        val store = Store(); val model = NativeExternalNavigationCoordinator(gateway, store, this)
        model.updateConditions(ready.copy(runtimeReady = false)); val request = model.accept(NativeExternalNavigationTarget.ImportedMedia(importedB)); yield()
        assertEquals(request, store.current)
        assertFalse(model.ackOpened(request.id, NativeExternalNavigationOpened(request.target, true, true)))
        assertTrue(model.ackOpened(request.id, NativeExternalNavigationOpened(NativeExternalNavigationTarget.ImportedMedia(importedA), true, true)))
        assertEquals(listOf(request, null), store.writes)
    }
    @Test fun processRestoreRetainsIdentityButRequiresFreshResolutionAndUiEvidence() = runBlocking {
        val saved = NativeExternalNavigationRequest("restored", NativeExternalNavigationTarget.VoiceWake(42))
        val store = Store(saved); val gateway = Gateway(); val model = NativeExternalNavigationCoordinator(gateway, store, this)
        model.updateConditions(NativeExternalNavigationConditions()); assertEquals(NativeExternalNavigationBlocker.FOREGROUND, model.state.value.blocker)
        assertFalse(model.ackOpened(saved.id, NativeExternalNavigationOpened(saved.target, true, true)))
        model.updateConditions(ready); yield(); assertEquals(saved, store.current); assertTrue(store.writes.isEmpty())
        assertTrue(model.ackOpened(saved.id, NativeExternalNavigationOpened(saved.target, true, true)))
    }
    @Test fun ambiguousWriteFailureReloadsDurableLatestBeforeAnyResolution() = runBlocking {
        var next = 0; val store = Store(); val gateway = Gateway(); val model = NativeExternalNavigationCoordinator(gateway, store, this, { "id-${next++}" })
        model.updateConditions(ready); model.accept(NativeExternalNavigationTarget.EngineSession("old")); yield()
        store.fail = true; store.applyBeforeFailure = true
        try { model.accept(NativeExternalNavigationTarget.EngineSession("latest")); fail("write_accepted") } catch (_: NativeExternalNavigationStorageException) { }
        assertTrue(model.state.value.storageError); assertNull(model.state.value.resolved); assertEquals(1, gateway.calls.size)
        store.fail = false; model.retry(); yield()
        assertEquals("latest", (model.state.value.resolved as NativeExternalResolvedTarget.EngineSession).session.id)
        assertEquals(store.current, model.state.value.request)
    }
    @Test fun failedClearPreservesJournalAndDoesNotClaimOpenedAcknowledgement() = runBlocking {
        val store = Store(); val model = NativeExternalNavigationCoordinator(Gateway(), store, this)
        model.updateConditions(ready); val request = model.accept(NativeExternalNavigationTarget.EngineSession("engine")); yield()
        store.fail = true
        try { model.ackOpened(request.id, NativeExternalNavigationOpened(request.target, true, true)); fail("clear_accepted") } catch (_: NativeExternalNavigationStorageException) { }
        assertEquals(request, store.current); assertTrue(model.state.value.storageError)
        store.fail = false; model.retry(); yield()
        assertTrue(model.ackOpened(request.id, NativeExternalNavigationOpened(request.target, true, true)))
    }
    @Test fun explicitDismissDoesNotNeedToPretendTheTargetOpened() = runBlocking {
        val store = Store(); val model = NativeExternalNavigationCoordinator(Gateway(), store, this)
        val request = model.accept(NativeExternalNavigationTarget.VoiceWake(42))
        assertEquals(NativeExternalNavigationPhase.WAITING, model.state.value.phase)
        assertTrue(model.dismiss(request.id)); assertNull(store.current)
        assertEquals(NativeExternalNavigationPhase.EMPTY, model.state.value.phase)
    }
    @Test fun aStaleDismissCannotClearANewerTarget() = runBlocking {
        var sequence = 0
        val store = Store(); val model = NativeExternalNavigationCoordinator(Gateway(), store, this, { "open-${sequence++}" })
        val old = model.accept(NativeExternalNavigationTarget.EngineSession("old"))
        val next = model.accept(NativeExternalNavigationTarget.EngineSession("new"))
        assertFalse(model.dismiss(old.id)); assertEquals(next, store.current)
    }
}
