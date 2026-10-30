package io.droyagent.app.nativeui.memory

import io.droyagent.app.nativeui.data.NativeApiException
import io.droyagent.app.nativeui.data.NativeRuntimeState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class NativeMemoryCoordinatorTest {
    private class Repository : NativeMemoryRepository {
        var currentStatus = NativeMemoryFixtures.status()
        var rows = listOf(NativeMemoryFixtures.record())
        var statuses = 0
        val writes = mutableListOf<NativeMemoryMutation>()
        val statusQueue = ArrayDeque<suspend () -> NativeMemoryStatus>()
        val queryQueue = ArrayDeque<suspend () -> NativeMemoryQueryResult>()
        val detailQueue = ArrayDeque<suspend () -> NativeMemoryRecord?>()
        var endpointRows = listOf(NativeMemoryEndpoint("endpoint-a", "conversation", "Fictional model", "fixture", "https://model.example.invalid/v1/chat/completions", true, listOf("memory"), "a".repeat(64)))
        var mutation: suspend (NativeMemoryMutation) -> Any? = { value ->
            val args = JSONObject(value.argsJson)
            when (value.operation) {
                "record" -> NativeMemoryFixtures.record("created", args.getString("text"), "created-revision").let { row -> rows = rows + row; receipt(row) }
                "revise" -> {
                    val row = rows.first { it.recordId == args.getString("recordId") }
                    if (row.revisionId != args.getString("expectedRevisionId")) throw NativeApiException("MEMORY_REVISION_CONFLICT")
                    val next = row.copy(text = args.getJSONObject("patch").getJSONObject("payload").getString("text"), revisionId = "new-revision")
                    rows = rows.map { if (it.recordId == row.recordId) next else it }; receipt(next)
                }
                "forget" -> {
                    val row = rows.first { it.recordId == args.getString("recordId") }
                    val next = row.copy(state = "deleted", text = null, payloadJson = null, revisionId = "deleted-revision")
                    rows = rows.map { if (it.recordId == row.recordId) next else it }; receipt(next)
                }
                "settings" -> { currentStatus = currentStatus.copy(settings = currentStatus.settings.copy(enabled = args.optBoolean("enabled", currentStatus.settings.enabled))); JSONObject() }
                else -> JSONObject()
            }
        }
        override suspend fun status(): NativeMemoryStatus { statuses++; return if (statusQueue.isEmpty()) currentStatus else statusQueue.removeFirst().invoke() }
        override suspend fun endpoints() = endpointRows
        override suspend fun apps() = NativeMemoryApps(emptyList(), false)
        override suspend fun query(filter: NativeMemoryFilter, cursor: String?) = if (queryQueue.isEmpty()) NativeMemoryFixtures.query(rows.filter { it.state in setOf("active", "candidate") }) else queryQueue.removeFirst().invoke()
        override suspend fun get(id: String) = if (detailQueue.isEmpty()) rows.find { it.recordId == id } else detailQueue.removeFirst().invoke()
        override suspend fun mutate(value: NativeMemoryMutation): Any? { writes += value; return mutation(value) }
        companion object { fun receipt(row: NativeMemoryRecord) = JSONObject().put("recordId", row.recordId).put("revisionId", row.revisionId) }
    }
    private suspend fun ready(model: NativeMemoryCoordinator) {
        model.runtime(NativeRuntimeState(ready = true, active = true)); model.refresh(includePage = false).join()
    }

    @Test fun enablingCaptureUnpausesWithoutChangingAnExistingApplicationScope() = runBlocking {
        val repo = Repository(); val model = NativeMemoryCoordinator(repo, this); ready(model)
        model.capture("enabled", true)?.join()
        val patch = JSONObject(repo.writes.single().argsJson).getJSONObject("args")
        assertTrue(patch.getBoolean("enabled")); assertFalse(patch.getBoolean("paused"))
        assertFalse(patch.has("allApplications"))
    }
    @Test fun memoryCanBeEnabledAndSavedWithoutRootAuthorization() = runBlocking {
        val repo = Repository().apply { currentStatus = currentStatus.copy(rootAuthorized = false, settings = currentStatus.settings.copy(enabled = false), available = false) }
        val model = NativeMemoryCoordinator(repo, this); ready(model)
        model.settings("enabled", true)?.join()
        repo.currentStatus = repo.currentStatus.copy(available = true)
        model.refresh(false).join(); model.create(); model.draft(model.state.value.editor!!.copy(text = "Fictional non-root memory"))
        model.save()?.join()
        assertEquals(listOf("settings", "record"), repo.writes.map { it.operation })
        assertEquals(false, model.state.value.status?.rootAuthorized)
        assertTrue(repo.rows.any { it.text == "Fictional non-root memory" })
    }
    @Test fun explicitlyAcceptingAllApplicationsClearsLegacyScopeAndUnpauses() = runBlocking {
        val repo = Repository(); val model = NativeMemoryCoordinator(repo, this); ready(model)
        model.enableAllApplicationCapture()?.join()
        val patch = JSONObject(repo.writes.single().argsJson).getJSONObject("args")
        assertTrue(patch.getBoolean("allApplications")); assertTrue(patch.getBoolean("enabled"))
        assertFalse(patch.getBoolean("paused")); assertEquals(0, patch.getJSONArray("allowedPackages").length())
        assertEquals(0, patch.getJSONArray("excludedPackages").length())
    }
    @Test fun olderStatusReadsCannotOverwriteANewerRead() = runBlocking {
        val repo = Repository(); val model = NativeMemoryCoordinator(repo, this)
        model.runtime(NativeRuntimeState(ready = true))
        val old = CompletableDeferred<NativeMemoryStatus>(); val fresh = CompletableDeferred<NativeMemoryStatus>()
        repo.statusQueue.add { old.await() }; repo.statusQueue.add { fresh.await() }
        val first = model.refresh(false); yield(); val second = model.refresh(false); yield()
        fresh.complete(repo.currentStatus.copy(rootAuthorized = false)); second.join()
        old.complete(repo.currentStatus); first.join()
        assertEquals(false, model.state.value.status?.rootAuthorized)
    }
    @Test fun aFailedQueryKeepsExistingRecordsAndDoesNotClaimAnEmptyLibrary() = runBlocking {
        val repo = Repository(); val model = NativeMemoryCoordinator(repo, this); ready(model)
        model.query().join(); repo.queryQueue.add { throw NativeApiException("network_error") }; model.query().join()
        assertEquals(1, model.state.value.records.size); assertEquals("network_error", model.state.value.recordsError)
    }
    @Test fun switchingFiltersRejectsALatePageFromTheOldFilter() = runBlocking {
        val repo = Repository(); val model = NativeMemoryCoordinator(repo, this); ready(model)
        val delayed = CompletableDeferred<NativeMemoryQueryResult>()
        repo.queryQueue.add { delayed.await() }; val first = model.query(); yield()
        repo.rows = listOf(NativeMemoryFixtures.record("new", "New filter"))
        model.filter(NativeMemoryFilter(NativeMemoryGroup.PLACES)); yield()
        delayed.complete(NativeMemoryFixtures.query(listOf(NativeMemoryFixtures.record("old")))); first.join()
        assertEquals("new", model.state.value.records.single().recordId)
    }
    @Test fun cursorRevisionChangesRequireRefreshAndNeverAppendMixedSnapshots() = runBlocking {
        val repo = Repository(); val model = NativeMemoryCoordinator(repo, this); ready(model)
        repo.queryQueue.add { NativeMemoryFixtures.query(repo.rows, "next", 1) }; model.query().join()
        repo.queryQueue.add { NativeMemoryFixtures.query(listOf(NativeMemoryFixtures.record("page-b")), null, 2) }; model.query(reset = false).join()
        assertEquals(listOf("record-a"), model.state.value.records.map { it.recordId }); assertEquals("MEMORY_CURSOR_STALE", model.state.value.recordsError)
    }
    @Test fun duplicateSaveClicksProduceOnlyOneMutation() = runBlocking {
        val repo = Repository(); val result = CompletableDeferred<Any?>(); repo.mutation = { result.await() }
        val model = NativeMemoryCoordinator(repo, this); ready(model); model.create(); model.draft(model.state.value.editor!!.copy(text = "Fictional draft"))
        val first = model.save(); val second = model.save(); yield()
        assertNull(second); assertEquals(1, repo.writes.size)
        result.complete(Repository.receipt(NativeMemoryFixtures.record("created"))); first?.join()
        assertNull(model.state.value.editor)
    }
    @Test fun revisionConflictPreservesTheUserDraftUntilExplicitRebase() = runBlocking {
        val repo = Repository(); val model = NativeMemoryCoordinator(repo, this); ready(model)
        model.open("record-a"); yield(); model.edit(); model.draft(model.state.value.editor!!.copy(text = "My correction"))
        repo.rows = listOf(NativeMemoryFixtures.record(text = "Changed elsewhere", revision = "newer"))
        model.save()?.join()
        assertTrue(model.state.value.conflict); assertEquals("My correction", model.state.value.editor?.text)
        model.refresh().join()
        assertTrue(model.state.value.conflict); assertEquals("revision-a", model.state.value.editor?.original?.revisionId)
        model.reloadEditor(); yield()
        assertFalse(model.state.value.conflict); assertEquals("My correction", model.state.value.editor?.text)
        assertEquals("newer", model.state.value.editor?.original?.revisionId)
    }
    @Test fun rebasePreservesOnlyTheFieldsTheUserActuallyChanged() = runBlocking {
        val original = NativeMemoryFixtures.record().copy(kind = "event", eventType = "location", text = null, placeName = "Original place", city = "Original city")
        val repo = Repository().apply { rows = listOf(original) }; val model = NativeMemoryCoordinator(repo, this); ready(model)
        model.open(original.recordId); yield(); model.edit(); model.draft(model.state.value.editor!!.copy(place = "My place"))
        repo.rows = listOf(original.copy(revisionId = "newer", placeName = "Changed place", city = "New city"))
        model.reloadEditor(); yield()
        assertEquals("My place", model.state.value.editor?.place); assertEquals("New city", model.state.value.editor?.city)
        assertEquals("newer", model.state.value.editor?.original?.revisionId)
    }
    @Test fun anUncertainCreateRetainsItsIdentityAndRequiresReviewBeforeExplicitRetry() = runBlocking {
        val repo = Repository(); repo.mutation = { throw NativeApiException("network_error") }
        val model = NativeMemoryCoordinator(repo, this) { "native:fixed-identity" }; ready(model)
        model.create(); model.draft(model.state.value.editor!!.copy(text = "Fictional draft")); model.save()?.join()
        assertEquals(1, repo.writes.size); assertFalse(model.state.value.unconfirmed!!.checked)
        assertNull(model.retryConfirmedIdentity()); assertEquals("Fictional draft", model.state.value.editor?.text)
        model.refresh().join(); assertTrue(model.state.value.unconfirmed!!.checked)
        repo.mutation = { Repository.receipt(NativeMemoryFixtures.record("created")) }; model.retryConfirmedIdentity()?.join()
        assertEquals(2, repo.writes.size); assertEquals(repo.writes[0], repo.writes[1])
        assertNull(model.state.value.unconfirmed); assertNull(model.state.value.editor)
    }
    @Test fun uncertainWritesAreNeverAutomaticallySubmittedByRefresh() = runBlocking {
        val repo = Repository(); repo.mutation = { throw NativeApiException("network_error") }
        val model = NativeMemoryCoordinator(repo, this); ready(model)
        model.settings("automaticExtraction", true)?.join(); assertEquals(1, repo.writes.size)
        repeat(3) { model.refresh().join() }
        assertEquals(1, repo.writes.size); assertTrue(model.state.value.unconfirmed!!.checked)
        model.acknowledgeReview(); assertNull(model.state.value.unconfirmed)
    }
    @Test fun disablingAndRevokingRemainPossibleAfterProtectionLoss() = runBlocking {
        val repo = Repository(); val model = NativeMemoryCoordinator(repo, this); ready(model)
        repo.currentStatus = repo.currentStatus.copy(available = false, managementAvailable = false, gateReady = false)
        model.refresh(false).join(); model.settings("enabled", false)?.join(); model.revoke(repo.endpointRows.single())?.join()
        assertEquals(listOf("settings", "revoke"), repo.writes.map { it.operation })
        assertNull(model.settings("recallEnabled", true)); assertNull(model.grant(repo.endpointRows.single(), listOf("memory")))
    }
    @Test fun disablingIsNotBlockedByAnInFlightWriteOrUnknownOutcome() = runBlocking {
        val repo = Repository(); val result = CompletableDeferred<Any?>()
        repo.mutation = { value -> if (value.operation == "record") result.await() else JSONObject() }
        val model = NativeMemoryCoordinator(repo, this); ready(model); model.create(); model.draft(model.state.value.editor!!.copy(text = "Fictional draft"))
        val writing = model.save(); yield(); model.settings("enabled", false)?.join()
        result.completeExceptionally(NativeApiException("network_error")); writing?.join()
        assertEquals(listOf("record", "settings"), repo.writes.map { it.operation })
        assertTrue(model.state.value.unconfirmed?.mutation?.hasReceipt == true)
    }
    @Test fun staleDetailCannotResurrectForgottenContent() = runBlocking {
        val repo = Repository(); val model = NativeMemoryCoordinator(repo, this); ready(model); model.open("record-a"); yield()
        val delayed = CompletableDeferred<NativeMemoryRecord?>(); repo.detailQueue.add { delayed.await() }
        val reading = model.refresh(); yield(); model.forget()?.join()
        delayed.complete(NativeMemoryFixtures.record()); reading.join()
        assertNull(model.state.value.selected); assertTrue(model.state.value.records.isEmpty())
    }
    @Test fun aDisabledFeatureStillAllowsViewingAndForgettingExistingRecords() = runBlocking {
        val repo = Repository(); repo.currentStatus = repo.currentStatus.copy(available = false, settings = repo.currentStatus.settings.copy(enabled = false))
        val model = NativeMemoryCoordinator(repo, this); ready(model); model.query().join(); model.open("record-a"); yield()
        model.create(); assertEquals(NativeMemoryPage.DETAIL, model.state.value.page)
        model.forget()?.join(); assertEquals("forget", repo.writes.single().operation)
    }
    @Test fun endpointIdentityMustStillMatchTheCurrentCatalogBeforeGranting() = runBlocking {
        val repo = Repository(); val model = NativeMemoryCoordinator(repo, this); ready(model); model.endpoints().join()
        val stale = repo.endpointRows.single().copy(url = "https://changed.example.invalid")
        assertNull(model.grant(stale, listOf("memory"))); assertEquals("MEMORY_ENDPOINT_REFRESH_REQUIRED", model.state.value.error)
        assertTrue(repo.writes.isEmpty())
    }
    @Test fun appScopesAreMutuallyExclusiveAndRetainedAcrossStatusRefreshesWhileEditing() = runBlocking {
        val repo = Repository(); val model = NativeMemoryCoordinator(repo, this); ready(model)
        model.appScope("app.fictional", 1); model.appScope("app.fictional", 2); model.refresh(false).join()
        assertFalse("app.fictional" in model.state.value.appAllow); assertTrue("app.fictional" in model.state.value.appExclude)
        assertTrue(model.state.value.appScopeDirty)
    }
    @Test fun invalidTimeRangeKeepsThePreviousQueryAndDoesNotSendANewRequest() = runBlocking {
        val repo = Repository(); val model = NativeMemoryCoordinator(repo, this); ready(model); model.query().join()
        model.filter(NativeMemoryFilter(from = "2030-02-01T00:00:00Z", to = "2030-01-01T00:00:00Z"))
        assertEquals("memory_invalid_range", model.state.value.error); assertEquals(1, model.state.value.records.size)
        assertEquals(NativeMemoryFilter(), model.state.value.filter)
    }
    @Test fun repeatedLoadMoreDoesNotInvalidateThePageAlreadyInFlight() = runBlocking {
        val repo = Repository(); val model = NativeMemoryCoordinator(repo, this); ready(model)
        repo.queryQueue.add { NativeMemoryFixtures.query(repo.rows, "next") }; model.query().join()
        val next = CompletableDeferred<NativeMemoryQueryResult>()
        repo.queryQueue.add { next.await() }; val first = model.query(reset = false); yield()
        model.query(reset = false).join()
        next.complete(NativeMemoryFixtures.query(listOf(NativeMemoryFixtures.record("page-b")))); first.join()
        assertEquals(listOf("record-a", "page-b"), model.state.value.records.map { it.recordId }); assertFalse(model.state.value.recordsLoading)
    }
    @Test fun lossOfManagementProtectionClearsSavedContentButRetainsThePrivateDraft() = runBlocking {
        val repo = Repository(); val model = NativeMemoryCoordinator(repo, this); ready(model)
        model.query().join(); model.open("record-a"); yield(); model.edit(); model.draft(model.state.value.editor!!.copy(text = "My unsaved correction"))
        repo.currentStatus = repo.currentStatus.copy(managementAvailable = false, available = false, gateReady = false)
        model.refresh(false).join()
        assertTrue(model.state.value.records.isEmpty()); assertNull(model.state.value.selected); assertFalse(model.state.value.recordsLoaded)
        assertEquals("My unsaved correction", model.state.value.editor?.text); assertNull(model.save())
    }
}
