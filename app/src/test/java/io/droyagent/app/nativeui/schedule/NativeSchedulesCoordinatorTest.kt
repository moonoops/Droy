package io.droyagent.app.nativeui.schedule

import io.droyagent.app.nativeui.data.NativeApiException
import java.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.*
import org.junit.Test

class NativeSchedulesCoordinatorTest {
    private val now = Instant.parse("2030-01-01T00:00:00Z")
    private fun entry(id: String = "a", title: String = "Original", at: String = "2099-01-01T00:00:00.000Z") = NativeScheduleEntry(
        NativeScheduleRecord(id, "every", title, "Original instruction", at, everySeconds = 300), "session-$id", "active")
    private fun delivery(id: String) = NativeScheduleDelivery("2099-01-01T00:00:00.000Z", "2099-01-01T00:00:01.000Z", id)

    private class FakeRepository : NativeSchedulesRepository {
        var rows = listOf<NativeScheduleEntry>()
        var targetRows = NativeScheduleTargets(listOf(NativeScheduleTarget("session-a", "Work")), mapOf("session-a" to "Work"))
        val catalogs = ArrayDeque<suspend () -> List<NativeScheduleEntry>>()
        var updates = 0; var creates = 0; var deletes = 0
        var updateAction: suspend (NativeScheduleUpdate) -> NativeScheduleUpdateResult = { request ->
            val old = rows.first { it.key == request.key }
            val record = old.record.copy(title = request.title ?: old.record.title, prompt = request.prompt ?: old.record.prompt)
            rows = rows.map { if (it.key == request.key) it.copy(record = record) else it }
            NativeScheduleUpdateResult.Record(record, true)
        }
        var createAction: suspend (NativeScheduleDraft) -> NativeScheduleRecord = { error("Unconfigured create") }
        var deleteAction: suspend (NativeScheduleKey) -> NativeScheduleDeleteResult = { key -> rows = rows.filterNot { it.key == key }; NativeScheduleDeleteResult(true) }
        var historyAction: suspend (NativeScheduleKey, String?) -> NativeScheduleHistoryResult = { _, _ ->
            NativeScheduleHistoryResult.Page(NativeScheduleHistory(loaded = true, retentionDays = 30, retentionRecords = 200))
        }
        override suspend fun catalog() = if (catalogs.isEmpty()) rows else catalogs.removeFirst().invoke()
        override suspend fun targets() = targetRows
        override suspend fun create(draft: NativeScheduleDraft): NativeScheduleRecord { creates++; return createAction(draft) }
        override suspend fun update(request: NativeScheduleUpdate): NativeScheduleUpdateResult { updates++; return updateAction(request) }
        override suspend fun delete(key: NativeScheduleKey): NativeScheduleDeleteResult { deletes++; return deleteAction(key) }
        override suspend fun history(key: NativeScheduleKey, before: String?) = historyAction(key, before)
    }

    @Test fun aLateCatalogCannotReplaceANewerSuccessfulRead() = runBlocking {
        val repo = FakeRepository()
        val model = NativeSchedulesCoordinator(repo, this, { now }, { "UTC" })
        val old = CompletableDeferred<List<NativeScheduleEntry>>()
        val fresh = CompletableDeferred<List<NativeScheduleEntry>>()
        repo.catalogs.add { old.await() }; repo.catalogs.add { fresh.await() }
        val first = model.refresh(); yield()
        val second = model.refresh(); yield()
        fresh.complete(listOf(entry(title = "New"))); second.join()
        old.complete(listOf(entry(title = "Stale"))); first.join()
        assertEquals("New", model.state.value.entries.single().record.title)
    }

    @Test fun anInFlightReadCannotResurrectThePreSaveRecord() = runBlocking {
        val repo = FakeRepository().apply { rows = listOf(entry()) }
        val model = NativeSchedulesCoordinator(repo, this, { now }, { "UTC" })
        model.refresh().join(); model.open(entry().key); model.beginEdit()
        val old = CompletableDeferred<List<NativeScheduleEntry>>()
        repo.catalogs.add { old.await() }
        val reading = model.refresh(); yield()
        model.edit { it.copy(title = "Saved") }; model.save().join(); model.refresh().join()
        old.complete(listOf(entry())); reading.join()
        assertEquals("Saved", model.state.value.entries.single().record.title)
        assertEquals(1, repo.updates)
    }

    @Test fun duplicateSaveClicksShareOneMutationAndDoNotAutomaticallyRetry() = runBlocking {
        val repo = FakeRepository().apply { rows = listOf(entry()) }
        val receipt = CompletableDeferred<NativeScheduleUpdateResult>()
        repo.updateAction = { receipt.await() }
        val model = NativeSchedulesCoordinator(repo, this, { now }, { "UTC" })
        model.refresh().join(); model.open(entry().key); model.beginEdit(); model.edit { it.copy(title = "Saved") }
        val first = model.save(); val second = model.save(); yield()
        assertEquals(1, repo.updates)
        repo.rows = listOf(entry(title = "Saved"))
        receipt.complete(NativeScheduleUpdateResult.Record(repo.rows.single().record, true))
        first.join(); second.join()
        assertNull(model.state.value.editor)
        assertEquals(1, repo.updates)
    }

    @Test fun conflictKeepsTheOldExpectedUntilAnExplicitFieldPreservingRebase() = runBlocking {
        val repo = FakeRepository().apply { rows = listOf(entry()) }
        val model = NativeSchedulesCoordinator(repo, this, { now }, { "UTC" })
        model.refresh().join(); model.open(entry().key); model.beginEdit(); model.edit { it.copy(title = "My edit") }
        val original = model.state.value.editor!!.expected
        val fresh = entry(at = "2099-01-02T00:00:00.000Z").let { it.copy(record = it.record.copy(prompt = "Changed elsewhere")) }
        repo.updateAction = { repo.rows = listOf(fresh); NativeScheduleUpdateResult.Rejected("schedule_conflict") }
        model.save().join(); model.refresh().join()
        assertEquals("schedule_conflict", model.state.value.error)
        assertEquals(original, model.state.value.editor!!.expected)
        assertEquals("My edit", model.state.value.editor!!.title)
        model.refresh(rebaseEditor = true).join()
        val rebased = model.state.value.editor!!
        assertEquals(fresh.record, rebased.expected)
        assertEquals("My edit", rebased.title)
        assertEquals("Changed elsewhere", rebased.prompt)
        assertEquals(1, repo.updates)
    }

    @Test fun aLostUpdateReceiptIsVerifiedByReadingWithoutResubmitting() = runBlocking {
        val repo = FakeRepository().apply { rows = listOf(entry()) }
        repo.updateAction = { repo.rows = listOf(entry(title = "Requested")); throw NativeApiException("network_error") }
        val model = NativeSchedulesCoordinator(repo, this, { now }, { "UTC" })
        model.refresh().join(); model.open(entry().key); model.beginEdit(); model.edit { it.copy(title = "Requested") }
        model.save().join(); model.refresh().join()
        assertEquals(1, repo.updates)
        assertNull(model.state.value.uncertain)
        assertNull(model.state.value.editor)
        assertEquals("schedule_saved", model.state.value.feedback)
    }

    @Test fun uncertainCreationIsNotAttributedToAnIdenticalNewCatalogRowOrRepeated() = runBlocking {
        val repo = FakeRepository()
        repo.createAction = { draft ->
            val record = NativeScheduleRecord("created", "at", draft.title, draft.prompt, "2030-01-01T01:00:00.000Z")
            repo.rows = listOf(NativeScheduleEntry(record, "session-a", "active"))
            throw NativeApiException("network_error")
        }
        val model = NativeSchedulesCoordinator(repo, this, { now }, { "UTC" })
        model.refresh().join(); model.refreshTargets().join(); model.beginCreate(); model.edit { it.copy(title = "New", prompt = "Instruction") }
        model.save().join(); model.refresh().join(); model.save().join()
        assertEquals(1, repo.creates)
        assertEquals(NativeScheduleOperation.CREATE, model.state.value.uncertain?.operation)
        assertEquals(true, model.state.value.uncertain?.checked)
        assertNotNull(model.state.value.editor)
        assertNull(model.state.value.feedback)
        model.reviewCatalog(); model.refresh().join(); model.acknowledgeReview()
        assertNull(model.state.value.uncertain)
        assertNull(model.state.value.editor)
        assertEquals(1, repo.creates)
    }

    @Test fun anUncertainDeleteIsConfirmedOnlyAfterTheTargetIsAbsent() = runBlocking {
        val repo = FakeRepository().apply { rows = listOf(entry()) }
        repo.deleteAction = { repo.rows = emptyList(); throw NativeApiException("NATIVE_SCHEDULES_FAILED") }
        val model = NativeSchedulesCoordinator(repo, this, { now }, { "UTC" })
        model.refresh().join(); model.open(entry().key); model.delete().join(); model.refresh().join()
        assertEquals(1, repo.deletes)
        assertTrue(model.state.value.entries.isEmpty())
        assertNull(model.state.value.uncertain)
        assertEquals("schedule_deleted", model.state.value.feedback)
    }

    @Test fun anExistingTargetKeepsUncertainDeletionLockedUntilExplicitReview() = runBlocking {
        val repo = FakeRepository().apply { rows = listOf(entry()); deleteAction = { throw NativeApiException("network_error") } }
        val model = NativeSchedulesCoordinator(repo, this, { now }, { "UTC" })
        model.refresh().join(); model.open(entry().key); model.delete().join(); model.refresh().join(); model.delete().join()
        assertEquals(1, repo.deletes)
        assertNotNull(model.state.value.uncertain)
        assertEquals(1, model.state.value.entries.size)
        model.reviewCatalog(); model.refresh().join(); model.acknowledgeReview()
        assertNull(model.state.value.uncertain)
        assertEquals(1, repo.deletes)
    }

    @Test fun aSaveReceiptUpdatesItsRecordWithoutHijackingANewerSelection() = runBlocking {
        val repo = FakeRepository().apply { rows = listOf(entry(), entry("b")) }
        val receipt = CompletableDeferred<NativeScheduleUpdateResult>()
        repo.updateAction = { receipt.await() }
        val model = NativeSchedulesCoordinator(repo, this, { now }, { "UTC" })
        model.refresh().join(); model.open(entry().key); model.beginEdit(); model.edit { it.copy(title = "Saved A") }
        val saving = model.save(); yield(); model.open(entry("b").key)
        repo.rows = listOf(entry(title = "Saved A"), entry("b"))
        receipt.complete(NativeScheduleUpdateResult.Record(repo.rows.first().record, true)); saving.join(); model.refresh().join()
        assertEquals(entry("b").key, model.state.value.selectedKey)
        assertEquals("Saved A", model.state.value.entries.first { it.key == entry().key }.record.title)
    }

    @Test fun historyResponsesCannotCrossSelectionsAndErrorsKeepTheKnownPage() = runBlocking {
        val repo = FakeRepository().apply { rows = listOf(entry(), entry("b")) }
        val old = CompletableDeferred<NativeScheduleHistoryResult>()
        repo.historyAction = { key, _ -> if (key.id == "a") old.await() else NativeScheduleHistoryResult.Page(NativeScheduleHistory(listOf(delivery("b-delivery")), loaded = true)) }
        val model = NativeSchedulesCoordinator(repo, this, { now }, { "UTC" })
        model.refresh().join(); model.open(entry().key)
        val reading = model.history(reset = true); yield(); model.open(entry("b").key); model.history(reset = true).join()
        old.complete(NativeScheduleHistoryResult.Page(NativeScheduleHistory(listOf(delivery("a-delivery")), loaded = true))); reading.join()
        assertEquals(listOf("b-delivery"), model.state.value.history.records.map { it.messageId })
        repo.historyAction = { _, _ -> NativeScheduleHistoryResult.Rejected("delivery_cursor_not_found") }
        model.history(reset = true).join()
        assertEquals("delivery_cursor_not_found", model.state.value.history.error)
        assertEquals(listOf("b-delivery"), model.state.value.history.records.map { it.messageId })
    }

    @Test fun anUnknownCatalogFailureRetainsTheLastSuccessfulData() = runBlocking {
        val repo = FakeRepository().apply { rows = listOf(entry()) }
        val model = NativeSchedulesCoordinator(repo, this, { now }, { "UTC" })
        model.refresh().join()
        repo.catalogs.add { throw NativeApiException("malformed_response") }
        model.refresh().join()
        assertTrue(model.state.value.catalogLoaded)
        assertEquals("malformed_response", model.state.value.catalogError)
        assertEquals(1, model.state.value.entries.size)
    }
}
