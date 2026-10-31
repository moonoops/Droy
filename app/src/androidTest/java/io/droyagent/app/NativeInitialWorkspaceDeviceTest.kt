package io.droyagent.app

import android.content.res.Configuration
import android.net.Uri
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.droyagent.app.nativeui.data.NativeApiException
import io.droyagent.app.nativeui.data.NativeSession
import io.droyagent.app.nativeui.data.NativeWorkbenchViewModel
import io.droyagent.app.nativeui.media.NativeDraftMediaStore
import java.io.File
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The real stopped Host leaves a deterministic first-catalog window; no model, microphone or camera is used. */
@RunWith(AndroidJUnit4::class)
class NativeInitialWorkspaceDeviceTest {
    @get:Rule(order = 0) val compose = createEmptyComposeRule()
    @get:Rule(order = 1) val fixture = NativeIsolatedRuntimeFixture()
    private lateinit var work: NativeWorkbenchViewModel
    private val preferences get() = fixture.context.getSharedPreferences("native_workbench", 0)

    @Test fun offlineTypingAndPersistedAttachmentBindToDefaultWithTheSameIdentityAfterColdRecreation() = runBlocking {
        models()
        val draftId = work.state.value.draftId
        val marker = "EARLY_OFFLINE_DRAFT"
        for (language in listOf("en", "zh")) {
            assertTrue(fixture.context.getSharedPreferences("product", 0).edit().putString("languagePreference", language).commit())
            fixture.relaunch(); models()
            compose.waitUntil(10_000) { work.state.value.runtime.locale == language }
            assertFalse(work.state.value.workspaceResolved)
            compose.onNodeWithText(text(language, R.string.native_workspace_resolving)).assertIsDisplayed()
            compose.onNodeWithTag("native-composer").assertIsEnabled().performTextReplacement(marker)
            compose.onNodeWithTag("native-send").assertIsNotEnabled()
            fixture.capture("native-initial-space-loading-$language")
        }
        fixture.scenario.onActivity {
            assertNull(work.mediaTargetOrNull())
            work.send()
            assertEquals("native_workspace_resolving", work.state.value.error)
        }
        for (fresh in listOf(false, true)) withContext(Dispatchers.Main) {
            try { work.captureStandardVoiceTarget(fresh); fail("Unresolved voice targets must wait, including fresh voice") }
            catch (error: NativeApiException) { assertEquals("native_workspace_resolving", error.code) }
        }
        assertFalse(preferences.getBoolean("workspaceChosen", false))
        assertFalse(preferences.contains("selectedWorkspaceId"))
        assertTrue(preferences.all.keys.none { it.startsWith("voice-target:") || it.startsWith("pending:") })
        val importId = UUID.randomUUID().toString()
        val store = NativeDraftMediaStore(fixture.context)
        val file = File(fixture.home, "early-persisted-attachment.txt").apply { writeText("Fictional persisted attachment") }
        try {
            val attachments = store.importSelection(importId, listOf(Uri.fromFile(file)), false, 10)
            // Seed an already-persisted local attachment, rather than bypassing the disabled target capture entry.
            assertTrue(preferences.edit().putString("attachments:$draftId", JSONArray(attachments.map { it.json() }).toString()).commit())
            fixture.relaunch(); models()
            assertFalse(work.state.value.workspaceResolved)
            assertEquals(draftId, work.state.value.draftId)
            assertEquals(marker, work.state.value.draft)
            assertEquals(attachments, work.state.value.draftAttachments)
            fixture.startRuntime()
            awaitResolved()
            val default = requireNotNull(work.state.value.defaultWorkspaceId)
            assertEquals(default, work.state.value.selectedWorkspaceId)
            assertEquals(draftId, work.state.value.draftId)
            assertEquals(marker, work.state.value.draft)
            assertEquals(attachments, work.state.value.draftAttachments)
            assertEquals(draftId, preferences.getString("blank-draft:id:$default", null))
            assertFalse(preferences.contains("blank-draft:null"))
            assertEquals("Fictional persisted attachment", String(store.bytes(attachments.single())))
            fixture.relaunch(); models(); awaitResolved()
            assertEquals(default, work.state.value.selectedWorkspaceId)
            assertEquals(draftId, work.state.value.draftId)
            assertEquals(marker, work.state.value.draft)
            assertEquals(attachments, work.state.value.draftAttachments)
            assertEquals(0, catalog().getJSONArray("sessions").length())
            assertNoModelCalls()
        } finally {
            assertTrue(preferences.edit().remove("attachments:$draftId").commit())
            store.removeImport(importId)
        }
    }

    @Test fun explicitUngroupedAndCustomChoicesSurviveCatalogAndColdRecreationWithoutReplacingTheirDrafts() {
        models()
        fixture.scenario.onActivity { work.selectWorkspace(null); work.setDraft("EXPLICIT_UNGROUPED_DRAFT") }
        val ungrouped = work.state.value.draftId
        assertTrue(work.state.value.workspaceResolved)
        fixture.relaunch(); models()
        assertTrue(work.state.value.workspaceResolved)
        fixture.startRuntime(); awaitResolved()
        assertNull(work.state.value.selectedWorkspaceId)
        assertEquals(ungrouped, work.state.value.draftId)
        val path = File(fixture.home, "explicit-custom").apply { assertTrue(mkdir()) }
        val receipt = native("/droy/native/workspaces/mutate", JSONObject().put("requestId", UUID.randomUUID().toString())
            .put("kind", "workspace.register").put("args", JSONObject().put("path", path.absolutePath)))
        assertEquals("complete", receipt.getString("state"))
        val custom = receipt.getString("workspaceId")
        fixture.scenario.onActivity { work.refresh() }
        compose.waitUntil(20_000) { work.state.value.workspaces.any { it.id == custom } }
        fixture.scenario.onActivity { work.selectWorkspace(custom); work.setDraft("EXPLICIT_CUSTOM_DRAFT") }
        val customDraft = work.state.value.draftId
        fixture.relaunch(); models(); awaitResolved()
        assertEquals(custom, work.state.value.selectedWorkspaceId)
        assertEquals(customDraft, work.state.value.draftId)
        assertEquals("EXPLICIT_CUSTOM_DRAFT", work.state.value.draft)
        fixture.scenario.onActivity { work.selectWorkspace(null) }
        assertEquals(ungrouped, work.state.value.draftId)
        assertEquals("EXPLICIT_UNGROUPED_DRAFT", work.state.value.draft)
        assertEquals("EXPLICIT_CUSTOM_DRAFT", preferences.getString("draft:$customDraft", null))
        assertEquals(0, catalog().getJSONArray("sessions").length())
        assertNoModelCalls()
    }

    @Test fun occupiedDefaultKeepsBothDraftsAndShowsTheUngroupedTargetAndOneNotice() {
        models(); fixture.startRuntime(); awaitResolved()
        val default = requireNotNull(work.state.value.defaultWorkspaceId)
        fixture.scenario.onActivity { work.setDraft("EXISTING_DEFAULT_DRAFT") }
        val existing = work.state.value.draftId
        fixture.scenario.onActivity { work.selectWorkspace(null); work.setDraft("EARLY_DRAFT_WITH_DEFAULT_CONFLICT") }
        val early = work.state.value.draftId
        NativeRuntimeHost.stop(fixture.context)
        compose.waitUntil(30_000) { RuntimeService.canManageHistory() && !work.state.value.runtime.ready }
        // Simulate a saved first-launch intent while retaining every space's independent draft records.
        assertTrue(preferences.edit().remove("workspaceChosen").remove("selectedWorkspaceId").remove("selectedSessionId").commit())
        fixture.relaunch(); models()
        assertFalse(work.state.value.workspaceResolved)
        assertEquals(early, work.state.value.draftId)
        fixture.startRuntime(); awaitResolved()
        assertNull(work.state.value.selectedWorkspaceId)
        assertEquals(early, work.state.value.draftId)
        assertEquals("EARLY_DRAFT_WITH_DEFAULT_CONFLICT", work.state.value.draft)
        compose.onNodeWithText(text(work.state.value.runtime.locale, R.string.native_ungrouped)).assertIsDisplayed()
        compose.onNodeWithText(text(work.state.value.runtime.locale, R.string.native_default_workspace_draft_conflict)).assertIsDisplayed()
        fixture.capture("native-initial-space-draft-conflict")
        assertEquals(existing, preferences.getString("blank-draft:id:$default", null))
        assertEquals("EXISTING_DEFAULT_DRAFT", preferences.getString("draft:$existing", null))
        fixture.scenario.onActivity { work.consumeWorkspaceNotice(); work.refresh() }
        compose.waitUntil(20_000) { work.state.value.workspaces.isNotEmpty() }
        assertNull(work.state.value.workspaceNotice)
        fixture.scenario.onActivity { work.selectWorkspace(default) }
        assertEquals(existing, work.state.value.draftId)
        assertEquals("EXISTING_DEFAULT_DRAFT", work.state.value.draft)
        fixture.scenario.onActivity { work.selectWorkspace(null) }
        assertEquals(early, work.state.value.draftId)
        assertEquals("EARLY_DRAFT_WITH_DEFAULT_CONFLICT", work.state.value.draft)
        assertNoModelCalls()
    }

    @Test fun historyAcknowledgementRequiresTheRealSnapshotAndIsInvalidatedWhenObservationStops() {
        models(); fixture.startRuntime(); awaitResolved()
        val workspace = requireNotNull(work.state.value.defaultWorkspaceId)
        val id = "initial-space-history-${UUID.randomUUID()}"
        native("/droy/native/session/create", JSONObject().put("sessionId", id).put("workspaceId", workspace))
        fixture.scenario.onActivity {
            work.selectSession(NativeSession(id, "Fictional history", workspaceId = workspace))
            assertNull("Selecting metadata is not a loaded history baseline", work.state.value.sessionBaselineId)
        }
        compose.waitUntil(20_000) { work.state.value.sessionBaselineId == id }
        fixture.scenario.onActivity { work.stopObserving(); assertNull(work.state.value.sessionBaselineId); work.startObserving() }
        compose.waitUntil(20_000) { work.state.value.sessionBaselineId == id }
        fixture.scenario.onActivity { work.openDraft(workspace); assertNull(work.state.value.sessionBaselineId) }
        assertNoModelCalls()
    }

    private fun models() {
        fixture.scenario.onActivity { work = ViewModelProvider(it)[NativeWorkbenchViewModel::class.java] }
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("native-composer").fetchSemanticsNodes(false).isNotEmpty() }
    }
    private fun awaitResolved() = compose.waitUntil(30_000) { work.state.value.runtime.ready && work.state.value.workspaceResolved && work.state.value.workspaces.isNotEmpty() }
    private fun catalog() = native("/droy/native/workspaces")
    private fun native(path: String, body: JSONObject? = null): JSONObject = runBlocking {
        val response = fixture.runtimeTransport.json(path, if (body == null) "GET" else "POST", body)
        assertTrue(response.getBoolean("ok")); response.getJSONObject("result")
    }
    private fun text(language: String, resource: Int): String {
        val configuration = Configuration(fixture.context.resources.configuration).apply { setLocale(Locale.forLanguageTag(language)) }
        return fixture.context.createConfigurationContext(configuration).getString(resource)
    }
    private fun assertNoModelCalls() {
        val calls = File(fixture.home, "native-fixture-calls.jsonl")
        assertTrue("Initial target resolution and history opening must not invoke the provider", !calls.isFile || calls.readText().isBlank())
    }
}
