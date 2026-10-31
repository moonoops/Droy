package io.droyagent.app.nativeui.companion.creation

import io.droyagent.app.nativeui.data.NativeApiException
import io.droyagent.app.nativeui.data.NativeRuntimeState
import io.droyagent.app.nativeui.media.NativeLocalAttachment
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class NativeCompanionCreationCoordinatorTest {
    private class Journal : NativeCreationJournalStore {
        var value: NativeCreationJournal? = null
        val writes = mutableListOf<NativeCreationJournal?>()
        var beforeWrite: suspend (NativeCreationJournal?) -> Unit = {}
        override suspend fun read() = value
        override suspend fun write(value: NativeCreationJournal?) { beforeWrite(value); this.value = value; writes += value }
    }
    private class Images : NativeCreationImageStore {
        var imports = 0; var removes = 0
        override suspend fun importImage(operationId: String, uri: String): NativeLocalAttachment {
            imports++; return NativeLocalAttachment(operationId, NativeCreationFixtures.itemId, "fictional.png", "image/png", 20)
        }
        override suspend fun remove(importId: String) { removes++ }
    }
    private class Repository : NativeCreationRepository {
        var status = NativeCreationDraftStatus("draft-fixture", "open", null, emptyList())
        var ensures = 0; var reads = 0; var uploads = 0; var cancellations = 0
        val bodies = mutableListOf<String>(); val preparationIds = mutableListOf<String>()
        var readAction: suspend () -> NativeCreationDraftStatus = { status }
        var ensureAction: suspend (String) -> NativeCreationDraftStatus = { status }
        var createAction: suspend (String) -> NativeCreationReceipt = { body ->
            val input = JSONObject(body); status = status.copy(state = "committed", companionId = "companion-fixture")
            NativeCreationReceipt(input.getString("requestId"), "companion-fixture", input.getString("templateId"), false)
        }
        var cancelAction: suspend () -> NativeCreationCancel = { status = status.copy(state = "cancelled"); NativeCreationCancel(true, "cancelled", null) }
        var uploadAction: suspend (String, NativeLocalAttachment) -> io.droyagent.app.nativeui.companion.data.NativeCompanionAttachment = { draft, _ -> NativeCreationFixtures.upload(draft, "upload-$uploads") }
        override suspend fun templates() = listOf(NativeCreationFixtures.template)
        override suspend fun models() = emptyList<io.droyagent.app.nativeui.data.NativeModel>()
        override suspend fun ensureDraft(requestId: String): NativeCreationDraftStatus { ensures++; preparationIds += requestId; return ensureAction(requestId) }
        override suspend fun draft(id: String): NativeCreationDraftStatus { reads++; return readAction() }
        override suspend fun create(payload: String): NativeCreationReceipt { bodies += payload; return createAction(payload) }
        override suspend fun cancelDraft(id: String): NativeCreationCancel { cancellations++; return cancelAction() }
        override suspend fun upload(draftId: String, item: NativeLocalAttachment): io.droyagent.app.nativeui.companion.data.NativeCompanionAttachment { uploads++; return uploadAction(draftId, item) }
    }
    private suspend fun ready(model: NativeCreationCoordinator) { model.runtime(NativeRuntimeState(ready = true, active = true)); model.restore().join(); model.refresh().join() }
    private fun id() = UUID.randomUUID().toString()

    @Test fun choosingATemplateDoesNotPrepareAServiceDraftOrCreateAnInstance() = runBlocking {
        val repo = Repository(); val storage = Journal(); val model = NativeCreationCoordinator(repo, storage, Images(), this)
        ready(model); model.selectTemplate(NativeCreationFixtures.template); yield()
        assertEquals(0, repo.ensures); assertTrue(repo.bodies.isEmpty()); assertEquals(NativeCreationFixtures.template.suggestedName, model.state.value.editor?.name)
        assertNull(storage.value)
    }
    @Test fun discardDoesNotLeaveAResumableDraft() = runBlocking {
        val repo = Repository(); val storage = Journal(); val model = NativeCreationCoordinator(repo, storage, Images(), this)
        ready(model); model.selectTemplate(NativeCreationFixtures.template); model.edit { it.copy(name = "Changed") }; yield()
        assertNotNull(storage.value)
        val observed = mutableListOf<NativeCreationState>()
        val observation = launch(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) { model.state.collect { observed += it } }
        var exited = false
        model.leaveEditor(false) { exited = true }?.join()
        observation.cancel()
        assertTrue(exited); assertNull(storage.value); assertNull(model.state.value.editor)
        assertEquals(NativeCreationPage.TEMPLATES, model.state.value.page)
        assertFalse("The editor must not lose its content before switching pages", observed.any { it.page == NativeCreationPage.EDITOR && it.editor == null })
    }
    @Test fun explicitlySavedDraftCanResumeAndUncertainCancellationKeepsIt() = runBlocking {
        val repo = Repository(); val storage = Journal(); val model = NativeCreationCoordinator(repo, storage, Images(), this)
        ready(model); model.selectTemplate(NativeCreationFixtures.template)
        model.leaveEditor(true) { }?.join()
        assertNotNull(storage.value); assertNotNull(model.state.value.editor)
        val resumed = NativeCreationCoordinator(repo, storage, Images(), this)
        ready(resumed)
        assertEquals(NativeCreationPage.EDITOR, resumed.state.value.page)
        resumed.prepareImage(NativeCreationImageKind.AVATAR, false)?.let { resumed.selectedImage(it.id, null)?.join() }
        repo.cancelAction = { throw NativeApiException("network_error") }
        var exited = false
        resumed.leaveEditor(false) { exited = true }?.join()
        assertFalse(exited); assertNotNull(storage.value); assertNotNull(resumed.state.value.editor)
    }
    @Test fun requestIdentityAndCompletePayloadAreDurableBeforeTheirHttpWrites() = runBlocking {
        val repo = Repository(); val storage = Journal(); val model = NativeCreationCoordinator(repo, storage, Images(), this)
        ready(model); model.selectTemplate(NativeCreationFixtures.template); model.edit { it.copy(name = "My fictional name", personalityOverride = "My fictional persona") }
        repo.ensureAction = { nonce -> assertEquals(nonce, storage.value?.requestId); repo.status }
        repo.createAction = { body ->
            assertEquals(body, storage.value?.frozenPayload); assertEquals("draft-fixture", storage.value?.draftId)
            NativeCreationReceipt(JSONObject(body).getString("requestId"), "companion-fixture", "kaka", false)
        }
        model.create()?.join()
        val sent = JSONObject(repo.bodies.single())
        assertEquals("My fictional name", sent.getString("name")); assertEquals("My fictional persona", sent.getString("personalityOverride"))
        assertTrue(sent.has("settings")); assertTrue(sent.has("chatBackground")); assertEquals("companion-fixture", model.state.value.createdId)
    }
    @Test fun repeatedCreateClicksDoNotSendParallelCreates() = runBlocking {
        val repo = Repository(); val storage = Journal(); val result = CompletableDeferred<NativeCreationReceipt>()
        repo.createAction = { result.await() }
        val model = NativeCreationCoordinator(repo, storage, Images(), this); ready(model); model.selectTemplate(NativeCreationFixtures.template)
        val first = model.create(); assertNull(model.create()); yield()
        assertEquals(1, repo.bodies.size)
        result.complete(NativeCreationReceipt(model.state.value.journal!!.requestId, "companion-fixture", "kaka", false)); first?.join()
        assertNull(model.create())
    }
    @Test fun processRecoveryOfACommittedDraftUsesReadsWithoutRecreating() = runBlocking {
        val repo = Repository(); val storage = Journal(); val images = Images()
        val model = NativeCreationCoordinator(repo, storage, images, this); ready(model); model.selectTemplate(NativeCreationFixtures.template)
        repo.createAction = { repo.status = repo.status.copy(state = "committed", companionId = "companion-fixture"); throw NativeApiException("network_error") }
        model.create()?.join(); assertNotNull(storage.value?.frozenPayload)
        val restored = NativeCreationCoordinator(repo, storage, images, this); ready(restored)
        assertEquals("companion-fixture", restored.state.value.createdId); assertEquals(1, repo.bodies.size)
        assertEquals(1, repo.ensures)
    }
    @Test fun anOpenUnknownCreationOnlyPermitsAnExplicitRetryOfTheFrozenPayload() = runBlocking {
        val repo = Repository(); val storage = Journal(); val model = NativeCreationCoordinator(repo, storage, Images(), this)
        ready(model); model.selectTemplate(NativeCreationFixtures.template); repo.createAction = { throw NativeApiException("network_error") }
        model.create()?.join(); val original = repo.bodies.single(); val originalName = model.state.value.editor!!.name
        model.edit { it.copy(name = "Must not replace unknown input") }; assertEquals(originalName, model.state.value.editor?.name)
        assertNull(model.retryCreate()); model.reconcile().join(); assertTrue(model.state.value.checked)
        repeat(3) { model.refresh().join() }; assertEquals(1, repo.bodies.size)
        repo.createAction = { NativeCreationReceipt(JSONObject(it).getString("requestId"), "companion-fixture", "kaka", true) }
        model.retryCreate()?.join()
        assertEquals(listOf(original, original), repo.bodies)
    }
    @Test fun aNameConflictKeepsTheChosenNameAndAllOtherEdits() = runBlocking {
        val repo = Repository(); val model = NativeCreationCoordinator(repo, Journal(), Images(), this); ready(model)
        model.selectTemplate(NativeCreationFixtures.template); model.edit { it.copy(name = "Existing fictional name", personalityOverride = "My edits") }
        repo.createAction = { throw NativeApiException("COMPANION_NAME_CONFLICT") }; model.create()?.join()
        assertEquals("Existing fictional name", model.state.value.editor?.name); assertEquals("My edits", model.state.value.editor?.personalityOverride)
        assertEquals("COMPANION_NAME_CONFLICT", model.state.value.error); assertNull(model.state.value.journal?.frozenPayload)
        model.edit { it.copy(name = "My next name") }; assertEquals("My next name", model.state.value.editor?.name)
    }
    @Test fun aLostPreparationReceiptReusesItsSavedNonceOnlyAfterAnotherUserAction() = runBlocking {
        val repo = Repository(); val storage = Journal(); val model = NativeCreationCoordinator(repo, storage, Images(), this); ready(model)
        model.selectTemplate(NativeCreationFixtures.template); repo.ensureAction = { throw NativeApiException("network_error") }; model.create()?.join()
        val nonce = storage.value!!.requestId
        assertNull(storage.value?.draftId); assertTrue(repo.bodies.isEmpty())
        val restored = NativeCreationCoordinator(repo, storage, Images(), this); ready(restored)
        assertEquals(1, repo.ensures); assertTrue(repo.bodies.isEmpty())
        repo.ensureAction = { repo.status }; restored.create()?.join()
        assertEquals(listOf(nonce, nonce), repo.preparationIds); assertEquals(1, repo.bodies.size)
    }
    @Test fun failureToPersistTheFrozenRequestPreventsCreation() = runBlocking {
        val repo = Repository(); val storage = Journal(); val model = NativeCreationCoordinator(repo, storage, Images(), this); ready(model)
        model.selectTemplate(NativeCreationFixtures.template); yield()
        storage.beforeWrite = { if (it?.frozenPayload != null) throw NativeApiException("storage_error") }
        model.create()?.join()
        assertTrue(repo.bodies.isEmpty()); assertTrue(model.state.value.restoreError); assertFalse(model.state.value.canCreate)
    }
    @Test fun anOlderOpenReadCannotUndoANewerCreationReceipt() = runBlocking {
        val repo = Repository(); val storage = Journal().apply { value = NativeCreationFixtures.journal() }
        val model = NativeCreationCoordinator(repo, storage, Images(), this); ready(model)
        val receipt = CompletableDeferred<NativeCreationReceipt>(); repo.createAction = { receipt.await() }
        val creating = model.create(); yield()
        val old = CompletableDeferred<NativeCreationDraftStatus>(); repo.readAction = { old.await() }; val reading = model.reconcile(); yield()
        receipt.complete(NativeCreationReceipt(model.state.value.journal!!.requestId, "companion-fixture", "kaka", false)); creating?.join()
        old.complete(NativeCreationDraftStatus("draft-fixture", "open", null, emptyList())); reading.join()
        assertEquals("companion-fixture", model.state.value.createdId); assertEquals("committed", model.state.value.draftState)
    }
    @Test fun cancellingAnAlreadyCommittedDraftKeepsTheRealCompanion() = runBlocking {
        val repo = Repository(); val journal = NativeCreationFixtures.journal().let { it.copy(frozenPayload = NativeCreationJson.payload(it)) }
        val model = NativeCreationCoordinator(repo, Journal().apply { value = journal }, Images(), this); ready(model)
        repo.cancelAction = { NativeCreationCancel(false, "committed", "companion-fixture") }; model.cancelCreation()?.join()
        assertEquals("companion-fixture", model.state.value.createdId); assertTrue(repo.bodies.isEmpty()); assertEquals(1, repo.cancellations)
    }
    @Test fun aLostCancelResponseRecoversTheClosedDraftWithoutCreatingANewOne() = runBlocking {
        val repo = Repository(); val journal = NativeCreationFixtures.journal().let { it.copy(frozenPayload = NativeCreationJson.payload(it)) }
        val storage = Journal().apply { value = journal }; val model = NativeCreationCoordinator(repo, storage, Images(), this); ready(model)
        repo.cancelAction = { repo.status = repo.status.copy(state = "cancelled"); throw NativeApiException("network_error") }
        model.cancelCreation()?.join(); assertTrue(storage.value!!.pendingCancel)
        val restored = NativeCreationCoordinator(repo, storage, Images(), this); ready(restored)
        assertFalse(restored.state.value.journal!!.pendingCancel); assertNull(restored.state.value.journal?.draftId)
        assertNotEquals(journal.requestId, restored.state.value.journal?.requestId); assertTrue(repo.bodies.isEmpty()); assertEquals(0, repo.ensures)
    }
    @Test fun imageSelectionIsOwnedAndDurableBeforeThePickerCanLaunch() = runBlocking {
        val repo = Repository(); val storage = Journal(); val images = Images(); val tracker = NativeCreationActivityTracker()
        val model = NativeCreationCoordinator(repo, storage, images, this, activityChanged = { tracker.update("owner", it) }); ready(model); model.selectTemplate(NativeCreationFixtures.template)
        val request = model.prepareImage(NativeCreationImageKind.AVATAR, false)!!
        assertEquals(request.id, storage.value?.image?.id); assertEquals("draft-fixture", storage.value?.image?.draftId)
        assertTrue(tracker.isPickerOpen()); assertTrue(tracker.hasRuntimeWork()); assertTrue(repo.bodies.isEmpty())
        assertNull(model.selectedImage(id(), "content://fictional/image")); assertEquals(0, images.imports)
        model.selectedImage(request.id, "content://fictional/image")?.join()
        assertEquals(1, images.imports); assertEquals(1, repo.uploads); assertFalse(tracker.isPickerOpen()); assertFalse(tracker.hasRuntimeWork())
        assertEquals("upload-1", model.state.value.editor?.avatarUpload?.id)
    }
    @Test fun aLateCancelledPickerCannotApplyToTheNextSelection() = runBlocking {
        val repo = Repository(); val model = NativeCreationCoordinator(repo, Journal(), Images(), this); ready(model); model.selectTemplate(NativeCreationFixtures.template)
        val first = model.prepareImage(NativeCreationImageKind.AVATAR, false)!!; model.discardImage()?.join()
        val second = model.prepareImage(NativeCreationImageKind.BACKGROUND, false)!!
        assertNull(model.selectedImage(first.id, "content://fictional/old-image")); assertEquals(second.id, model.state.value.image?.id); assertEquals(0, repo.uploads)
        model.selectedImage(second.id, null)?.join()
        Unit
    }
    @Test fun interruptedUploadsRecoverWithoutAutomaticallyReadingOrUploading() = runBlocking {
        val image = NativeCreationImageOperation(NativeCreationFixtures.imageId, NativeCreationFixtures.nonce, "draft-fixture", NativeCreationImageKind.AVATAR,
            NativeCreationImagePhase.UPLOADING, "content://fictional/image", NativeLocalAttachment(NativeCreationFixtures.imageId, NativeCreationFixtures.itemId, "fictional.png", "image/png", 20))
        val repo = Repository(); val images = Images(); val storage = Journal().apply { value = NativeCreationFixtures.journal().copy(image = image) }
        val model = NativeCreationCoordinator(repo, storage, images, this); ready(model)
        assertEquals(NativeCreationImagePhase.INTERRUPTED, model.state.value.image?.phase); assertEquals(0, repo.uploads); assertEquals(0, images.imports)
        model.retryImage()?.join(); assertEquals(1, repo.uploads); assertEquals(0, images.imports); assertNull(model.state.value.image)
    }
    @Test fun avatarAndBackgroundAreSubmittedTogetherInTheSingleCreationTransaction() = runBlocking {
        val repo = Repository(); val model = NativeCreationCoordinator(repo, Journal(), Images(), this); ready(model); model.selectTemplate(NativeCreationFixtures.template)
        val avatar = model.prepareImage(NativeCreationImageKind.AVATAR, false)!!; model.selectedImage(avatar.id, "content://fictional/avatar")?.join()
        val background = model.prepareImage(NativeCreationImageKind.BACKGROUND, false)!!; model.selectedImage(background.id, "content://fictional/background")?.join()
        assertTrue(repo.bodies.isEmpty()); model.create()?.join()
        val value = JSONObject(repo.bodies.single())
        assertEquals("upload-1", value.getString("avatarUploadId")); assertEquals("upload-2", value.getJSONObject("chatBackground").getString("id"))
        assertEquals("draft-fixture", value.getString("draftId"))
    }
    @Test fun uploadResultsCannotClaimADifferentDraftOrExistingProfile() = runBlocking {
        val repo = Repository(); repo.uploadAction = { _, _ -> NativeCreationFixtures.upload("different-draft") }
        val model = NativeCreationCoordinator(repo, Journal(), Images(), this); ready(model); model.selectTemplate(NativeCreationFixtures.template)
        val image = model.prepareImage(NativeCreationImageKind.AVATAR, false)!!; model.selectedImage(image.id, "content://fictional/image")?.join()
        assertNull(model.state.value.editor?.avatarUpload); assertEquals("COMPANION_ATTACHMENT_SCOPE", model.state.value.imageError)
        assertFalse(model.state.value.canCreate)
    }
    @Test fun pickerAndRuntimeHoldsHaveDifferentLifetimesAcrossMultipleOwners() {
        val tracker = NativeCreationActivityTracker()
        val image = NativeCreationImageOperation(id(), id(), "draft-fixture", NativeCreationImageKind.AVATAR, NativeCreationImagePhase.PICKING)
        val journal = NativeCreationFixtures.journal().copy(image = image)
        tracker.update("one", NativeCreationState(journal = journal)); assertTrue(tracker.isPickerOpen())
        tracker.update("one", NativeCreationState(journal = journal.copy(image = image.copy(phase = NativeCreationImagePhase.UPLOADING))))
        tracker.update("two", NativeCreationState(busy = true))
        assertFalse(tracker.isPickerOpen()); assertTrue(tracker.hasRuntimeWork())
        tracker.remove("one"); assertTrue(tracker.hasRuntimeWork())
        tracker.update("two", NativeCreationState(journal = NativeCreationFixtures.journal().copy(frozenPayload = "unknown-result")))
        assertFalse(tracker.hasRuntimeWork()); assertFalse(tracker.isPickerOpen())
    }
}
