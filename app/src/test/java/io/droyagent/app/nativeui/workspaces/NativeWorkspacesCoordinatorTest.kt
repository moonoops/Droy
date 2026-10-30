package io.droyagent.app.nativeui.workspaces

import io.droyagent.app.nativeui.data.NativeApiException
import io.droyagent.app.nativeui.data.NativeRuntimeState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class NativeWorkspacesCoordinatorTest {
    private class Store : NativeWorkspacesStore {
        var value = NativeWorkspacesSaved()
        var fail = false
        override fun load() = value
        override fun save(value: NativeWorkspacesSaved) { if (fail) throw NativeApiException("NATIVE_WORKSPACES_STORAGE"); this.value = value }
    }
    private fun catalog() = NativeWorkspacesCatalog(
        listOf(NativeWorkspaceRow("a", "/host/one", "One", listOf("s1"), false), NativeWorkspaceRow("managed", "/host/companion", "Companion", listOf("partner"), true)),
        listOf(NativeWorkspaceSession("s1", "Original", false, false, false, "a"), NativeWorkspaceSession("s2", "Other", false, false, false, null),
            NativeWorkspaceSession("partner", "Companion", false, false, true, "managed")), "a", emptyList(), emptyList(), true)
    private class Repository(var current: NativeWorkspacesCatalog) : NativeWorkspacesRepository {
        val sent = mutableListOf<NativeWorkspaceOperation>()
        val statusReads = mutableListOf<NativeWorkspaceOperation>()
        val directoryReads = mutableListOf<String?>()
        val catalogs = ArrayDeque<suspend () -> NativeWorkspacesCatalog>()
        var action: suspend (NativeWorkspaceOperation) -> NativeWorkspaceReceipt = { NativeWorkspaceReceipt(it.requestId, it.kind, "complete") }
        var statusAction: suspend (NativeWorkspaceOperation) -> NativeWorkspaceReceipt = { NativeWorkspaceReceipt(it.requestId, it.kind, "unknown") }
        override suspend fun catalog() = if (catalogs.isEmpty()) current else catalogs.removeFirst().invoke()
        override suspend fun directory(path: String?): NativeWorkspaceDirectory {
            directoryReads += path
            return NativeWorkspaceDirectory(path ?: "/host/home", "/host/home", emptyList(), listOf(NativeWorkspaceDirectoryEntry("Child", "/canonical/child", false)), false)
        }
        override suspend fun mutate(operation: NativeWorkspaceOperation): NativeWorkspaceReceipt { sent += operation; return action(operation) }
        override suspend fun status(operation: NativeWorkspaceOperation): NativeWorkspaceReceipt { statusReads += operation; return statusAction(operation) }
    }
    @Test fun duplicateClicksFreezeTheOriginalTargetAndBody() = runBlocking {
        val repo = Repository(catalog()); val store = Store(); val model = NativeWorkspacesCoordinator(repo, store, this)
        model.runtime(NativeRuntimeState(ready = true)); model.refresh().join()
        model.onAction(NativeWorkspacesAction.BeginSessionRename("s1")); model.onAction(NativeWorkspacesAction.EditText("Saved"))
        val receipt = CompletableDeferred<NativeWorkspaceReceipt>(); repo.action = { receipt.await() }
        val saving = model.onAction(NativeWorkspacesAction.SaveEditor)!!
        assertNull(model.onAction(NativeWorkspacesAction.SaveEditor)); model.onAction(NativeWorkspacesAction.BeginSessionRename("s2")); yield()
        assertEquals(1, repo.sent.size)
        val captured = repo.sent.single(); assertEquals("s1", JSONObject(captured.argumentsJson).getString("sessionId"))
        assertEquals("Saved", JSONObject(captured.argumentsJson).getString("title")); assertEquals(captured, store.value.pending)
        receipt.complete(NativeWorkspaceReceipt(captured.requestId, captured.kind, "complete", title = "Saved")); saving.join()
        assertNull(store.value.pending); assertNull(model.state.value.editor)
    }
    @Test fun renameConflictPreservesDraftAndDoesNotInventCompareAndSwap() = runBlocking {
        val repo = Repository(catalog()); val store = Store(); val model = NativeWorkspacesCoordinator(repo, store, this)
        model.runtime(NativeRuntimeState(ready = true)); model.refresh().join()
        model.onAction(NativeWorkspacesAction.BeginWorkspaceRename("a")); model.onAction(NativeWorkspacesAction.EditText("My draft"))
        repo.action = { NativeWorkspaceReceipt(it.requestId, it.kind, "failed", errorCode = "workspace/name-conflict") }
        model.onAction(NativeWorkspacesAction.SaveEditor)!!.join()
        assertEquals("My draft", model.state.value.editor!!.text); assertEquals("a", model.state.value.editor!!.targetId)
        assertEquals("workspace/name-conflict", model.state.value.error); assertNull(model.state.value.uncertain)
        assertEquals(setOf("workspaceId", "title"), JSONObject(repo.sent.single().argumentsJson).keys().asSequence().toSet())
    }
    @Test fun uncertainRequestsAreRestoredForExplicitStatusOnly() = runBlocking {
        val repo = Repository(catalog()); val store = Store(); val model = NativeWorkspacesCoordinator(repo, store, this)
        model.runtime(NativeRuntimeState(ready = true)); model.refresh().join()
        repo.action = { throw NativeApiException("transport_failed") }
        model.onAction(NativeWorkspacesAction.Fork("s1"))!!.join()
        val frozen = store.value.pending!!; assertEquals(false, JSONObject(frozen.argumentsJson).getBoolean("allowMigration"))
        val restored = NativeWorkspacesCoordinator(repo, store, this); restored.runtime(NativeRuntimeState(ready = true)); restored.refresh().join()
        assertEquals(1, repo.sent.size); assertFalse(restored.state.value.uncertain!!.checked)
        restored.onAction(NativeWorkspacesAction.AcknowledgeUncertain); assertNotNull(restored.state.value.uncertain)
        restored.onAction(NativeWorkspacesAction.CheckUncertain)!!.join()
        assertEquals(listOf(frozen), repo.statusReads); assertTrue(restored.state.value.uncertain!!.checked)
        restored.onAction(NativeWorkspacesAction.AcknowledgeUncertain)
        assertNull(store.value.pending); assertEquals(1, repo.sent.size)
    }
    @Test fun knownForkReceiptOpensTheKnownChildWithoutReplayingTheFork() = runBlocking {
        val repo = Repository(catalog()); val store = Store(); val operation = NativeWorkspaceOperation("frozen-id", "session.fork", "{\"sessionId\":\"s1\",\"allowMigration\":false}")
        store.value = NativeWorkspacesSaved(pending = operation)
        repo.statusAction = { NativeWorkspaceReceipt(it.requestId, it.kind, "complete", resultSessionId = "created", renameUnconfirmed = true) }
        val model = NativeWorkspacesCoordinator(repo, store, this); model.runtime(NativeRuntimeState(ready = true))
        model.onAction(NativeWorkspacesAction.CheckUncertain)!!.join()
        assertEquals("created", model.state.value.createdSessionId); assertEquals("fork_title_unconfirmed", model.state.value.feedback)
        assertTrue(repo.sent.isEmpty()); assertNull(store.value.pending)
        model.onAction(NativeWorkspacesAction.ConsumeCreatedSession("another")); assertEquals("created", model.state.value.createdSessionId)
        model.onAction(NativeWorkspacesAction.ConsumeCreatedSession("created")); assertNull(model.state.value.createdSessionId)
    }
    @Test fun archiveAndMigrationRefusalsRequireFreshExplicitConfirmedActions() = runBlocking {
        val repo = Repository(catalog()); val model = NativeWorkspacesCoordinator(repo, Store(), this)
        model.runtime(NativeRuntimeState(ready = true)); model.refresh().join()
        repo.action = { NativeWorkspaceReceipt(it.requestId, it.kind, "failed", errorCode = "workspace/session-active") }
        model.onAction(NativeWorkspacesAction.Archive("s1"))!!.join()
        assertEquals("s1", model.state.value.archiveActiveSessionId); assertEquals(1, repo.sent.size)
        repo.action = { NativeWorkspaceReceipt(it.requestId, it.kind, "complete") }
        model.onAction(NativeWorkspacesAction.Archive("s1", true))!!.join()
        assertTrue(JSONObject(repo.sent.last().argumentsJson).getBoolean("stopActivity")); assertNotEquals(repo.sent[0].requestId, repo.sent[1].requestId)
        repo.action = { NativeWorkspaceReceipt(it.requestId, it.kind, "failed", errorCode = "session/migration-required") }
        model.onAction(NativeWorkspacesAction.Fork("s1"))!!.join(); assertEquals("s1", model.state.value.forkMigrationSessionId)
        repo.action = { NativeWorkspaceReceipt(it.requestId, it.kind, "complete", resultSessionId = "fork") }
        model.onAction(NativeWorkspacesAction.Fork("s1", true))!!.join(); assertTrue(JSONObject(repo.sent.last().argumentsJson).getBoolean("allowMigration"))
    }
    @Test fun storageFailureCannotDispatchAndUnknownCannotBeAcknowledgedBeforeReview() = runBlocking {
        val repo = Repository(catalog()); val store = Store(); val model = NativeWorkspacesCoordinator(repo, store, this)
        model.runtime(NativeRuntimeState(ready = true)); model.refresh().join(); store.fail = true
        assertNull(model.onAction(NativeWorkspacesAction.Fork("s1")))
        assertEquals("NATIVE_WORKSPACES_STORAGE", model.state.value.error); assertTrue(repo.sent.isEmpty())
        assertNotNull(model.state.value.uncertain)
        model.onAction(NativeWorkspacesAction.AcknowledgeUncertain); assertNotNull(model.state.value.uncertain)
    }
    @Test fun managedTargetsCannotDispatchAndDefaultWorkspaceCanBeRenamedOrRemoved() = runBlocking {
        val repo = Repository(catalog()); val model = NativeWorkspacesCoordinator(repo, Store(), this)
        model.runtime(NativeRuntimeState(ready = true)); model.refresh().join()
        assertNull(model.onAction(NativeWorkspacesAction.Pin("partner", true)))
        assertNull(model.onAction(NativeWorkspacesAction.RemoveWorkspace("managed")))
        model.onAction(NativeWorkspacesAction.BeginWorkspaceRename("a")); assertNotNull(model.state.value.editor)
        model.onAction(NativeWorkspacesAction.CloseEditor); model.onAction(NativeWorkspacesAction.RemoveWorkspace("a"))!!.join()
        assertEquals("workspace.remove", repo.sent.single().kind)
    }
    @Test fun directorySelectionUsesReturnedCanonicalPathAndCreationReturnsASeparatePath() = runBlocking {
        val repo = Repository(catalog()); val model = NativeWorkspacesCoordinator(repo, Store(), this)
        model.runtime(NativeRuntimeState(ready = true)); model.refresh().join()
        model.onAction(NativeWorkspacesAction.BrowseDirectory())!!.join()
        model.onAction(NativeWorkspacesAction.BrowseDirectory(model.state.value.directory!!.entries.single().path))!!.join()
        model.onAction(NativeWorkspacesAction.BeginDirectoryCreate); model.onAction(NativeWorkspacesAction.EditText("Folder"))
        repo.action = { NativeWorkspaceReceipt(it.requestId, it.kind, "complete", path = "/host/actually-created") }
        model.onAction(NativeWorkspacesAction.SaveEditor)!!.join(); yield()
        assertEquals("/canonical/child", JSONObject(repo.sent.single().argumentsJson).getString("path"))
        assertEquals("/host/actually-created", repo.directoryReads.last())
        repo.action = { NativeWorkspaceReceipt(it.requestId, it.kind, "complete", workspaceId = "registered") }
        model.onAction(NativeWorkspacesAction.RegisterDirectory)!!.join()
        assertEquals("/host/actually-created", JSONObject(repo.sent.last().argumentsJson).getString("path"))
        assertFalse(model.state.value.directoryOpen); assertEquals("registered", model.state.value.changedWorkspaceId)
    }
    @Test fun namedSpaceCreationDoesNotRequireDirectoryBrowsingOrSendAClientPath() = runBlocking {
        val repo = Repository(catalog().copy(directoryBrowsingAvailable = false))
        val model = NativeWorkspacesCoordinator(repo, Store(), this)
        model.runtime(NativeRuntimeState(ready = true)); model.refresh().join()
        model.onAction(NativeWorkspacesAction.BeginWorkspaceCreate)
        assertEquals(NativeWorkspaceEditorKind.WORKSPACE_NAME, model.state.value.editor!!.kind)
        model.onAction(NativeWorkspacesAction.EditText("  Research  "))
        repo.action = { NativeWorkspaceReceipt(it.requestId, it.kind, "complete", workspaceId = "created-space") }
        model.onAction(NativeWorkspacesAction.SaveEditor)!!.join()
        assertEquals("workspace.create", repo.sent.single().kind)
        val arguments = JSONObject(repo.sent.single().argumentsJson)
        assertEquals(setOf("name"), arguments.keys().asSequence().toSet())
        assertEquals("Research", arguments.getString("name"))
        assertTrue(repo.directoryReads.isEmpty())
        assertNull(model.state.value.editor)
        assertEquals("created-space", model.state.value.changedWorkspaceId)
    }

    @Test fun lateCatalogCannotRestorePreMutationGrouping() = runBlocking {
        val repo = Repository(catalog()); val model = NativeWorkspacesCoordinator(repo, Store(), this)
        model.runtime(NativeRuntimeState(ready = true)); model.refresh().join()
        val stale = CompletableDeferred<NativeWorkspacesCatalog>(); repo.catalogs += { stale.await() }
        val reading = model.refresh(); yield()
        repo.current = repo.current.copy(workspaces = repo.current.workspaces.filterNot { it.id == "a" })
        model.onAction(NativeWorkspacesAction.RemoveWorkspace("a"))!!.join(); yield()
        stale.complete(catalog()); reading.join()
        assertFalse(model.state.value.catalog!!.workspaces.any { it.id == "a" })
    }
    @Test fun mismatchedStatusReceiptCannotClearPendingOrEnableAcknowledgement() = runBlocking {
        val repo = Repository(catalog()); val store = Store()
        store.value = NativeWorkspacesSaved(pending = NativeWorkspaceOperation("frozen", "session.fork", "{\"sessionId\":\"s1\",\"allowMigration\":false}"))
        repo.statusAction = { NativeWorkspaceReceipt("different", it.kind, "complete", resultSessionId = "wrong") }
        val model = NativeWorkspacesCoordinator(repo, store, this); model.runtime(NativeRuntimeState(ready = true))
        model.onAction(NativeWorkspacesAction.CheckUncertain)!!.join()
        assertNotNull(store.value.pending); assertFalse(model.state.value.uncertain!!.checked); assertNull(model.state.value.createdSessionId)
        assertEquals("malformed_response", model.state.value.error)
    }
}
