package io.droyagent.app

import android.content.ContentValues
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.view.KeyEvent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.droyagent.app.nativeui.data.NativeWorkbenchViewModel
import io.droyagent.app.nativeui.media.NativeDraftMediaStore
import io.droyagent.app.nativeui.settings.NativeSettingsViewModel
import java.io.File
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** One isolated emulator boot: actual DSH/session persistence, zero submitted prompts or paid providers. */
@RunWith(AndroidJUnit4::class)
class NativeReviewDraftDeviceTest {
    @get:Rule(order = 0) val compose = createEmptyComposeRule()
    @get:Rule(order = 1) val fixture = NativeIsolatedRuntimeFixture()
    private lateinit var work: NativeWorkbenchViewModel
    private lateinit var settings: NativeSettingsViewModel

    @Test fun goalPreparationAndScheduleEntryRetainDraftsWithoutSendingInBothLanguages() = runBlocking {
        models()
        val store = NativeDraftMediaStore(fixture.context)
        val importId = UUID.randomUUID().toString()
        var source: Uri? = null
        try {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, "native-review-$importId.txt")
                put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/DroyNativeReview")
            }
            val uri = requireNotNull(fixture.context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values))
            source = uri
            requireNotNull(fixture.context.contentResolver.openOutputStream(uri)).use { it.write("Fictional goal attachment".toByteArray()) }
            fixture.context.grantUriPermission(fixture.context.packageName, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            val attachments = store.importSelection(importId, listOf(uri), false, 1)
            val original = work.state.value.draftId
            fixture.scenario.onActivity {
                work.setDraft("UNSENT_GOAL_DRAFT")
                work.deliverLocalAttachments(work.mediaTarget(), importId, attachments)
            }
            work.awaitDraftPersistence()
            compose.onNodeWithTag("native-composer-attachments").performClick()
            compose.onNodeWithText(text("en", R.string.native_goal_title)).performClick()
            compose.waitUntil(30_000) { work.state.value.selectedSessionId == original && !work.state.value.sending }
            compose.onNodeWithText(text("en", R.string.native_goal_objective)).assertIsDisplayed()
            assertEquals("UNSENT_GOAL_DRAFT", work.state.value.draft)
            assertEquals(attachments, work.state.value.draftAttachments)
            assertEquals(original, withContext(Dispatchers.Main) { work.ensureSessionForGoal() })
            assertEquals(1, sessions().count { it.getString("id") == original })
            assertNoSubmissions()
            fixture.capture("native-review-goal-draft-preserved")
            // ModalBottomSheet owns a dialog window; dispatch Back to the foreground window,
            // not directly to the underlying Activity's navigation dispatcher.
            InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            compose.waitUntil(10_000) {
                compose.onAllNodesWithText(text("en", R.string.native_goal_objective)).fetchSemanticsNodes(false).isEmpty()
            }
            compose.onNodeWithTag("native-composer").assertIsDisplayed().assertTextContains("UNSENT_GOAL_DRAFT")
            assertEquals(original, work.state.value.selectedSessionId)
            fixture.recreate(); models()
            assertEquals(original, work.state.value.selectedSessionId)
            assertEquals("UNSENT_GOAL_DRAFT", work.state.value.draft)
            assertEquals(attachments, work.state.value.draftAttachments)
            assertEquals("Fictional goal attachment", String(store.bytes(attachments.single())))

            fixture.scenario.onActivity { work.createSession() }
            for (language in listOf("en", "zh")) {
                fixture.scenario.onActivity { settings.setLanguage(language) }
                compose.waitUntil(20_000) { !settings.state.value.saving && settings.state.value.locale == language && work.state.value.runtime.locale == language }
                assertNull(settings.state.value.error)
                val marker = "UNSENT_SCHEDULE_PREDECESSOR_$language"
                fixture.scenario.onActivity { work.setDraft(marker) }
                val retained = work.state.value.draftId
                val workspace = work.state.value.selectedWorkspaceId
                val before = sessions().map { it.getString("id") }.toSet()
                compose.onNodeWithTag("native-workbench-menu").performClick()
                compose.onNodeWithText(text(language, R.string.native_schedule_title)).performClick()
                compose.waitUntil(10_000) { compose.onAllNodesWithTag("native-schedule-create").fetchSemanticsNodes(false).isNotEmpty() }
                compose.onNodeWithTag("native-schedule-create").performClick()
                val prefix = text(language, R.string.native_review_schedule_request)
                assertEquals(if (language == "zh") "替我创建一个定时任务：" else "Create a scheduled task for me: ", prefix)
                compose.waitUntil(30_000) { work.state.value.selectedSessionId == null && work.state.value.draft == prefix && work.state.value.draftId != retained }
                val scheduledDraft = work.state.value.draftId
                compose.onNodeWithTag("native-composer").assertTextContains(prefix)
                assertEquals(before + retained, sessions().map { it.getString("id") }.toSet())
                assertNoSubmissions()
                fixture.capture("native-review-schedule-draft-$language")
                fixture.recreate(); models()
                assertEquals(scheduledDraft, work.state.value.draftId)
                assertEquals(prefix, work.state.value.draft)
                compose.waitUntil(20_000) { work.state.value.sessions.any { it.id == retained } }
                fixture.scenario.onActivity { work.selectSession(retained) }
                compose.waitUntil(20_000) { work.state.value.sessionBaselineId == retained }
                assertEquals(marker, work.state.value.draft)
                compose.onNodeWithTag("native-composer").assertTextContains(marker)
                assertTrue(work.state.value.messages.isEmpty())
                fixture.scenario.onActivity { assertTrue(work.openDraft(workspace)) }
                assertEquals(scheduledDraft, work.state.value.draftId)
                assertEquals(prefix, work.state.value.draft)
                assertNoSubmissions()
            }
        } finally {
            try { store.removeImport(importId) } finally {
                source?.let { uri ->
                    fixture.context.revokeUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    assertEquals(1, fixture.context.contentResolver.delete(uri, null, null))
                }
            }
        }
    }

    private fun models() {
        fixture.scenario.onActivity {
            work = ViewModelProvider(it)[NativeWorkbenchViewModel::class.java]
            settings = ViewModelProvider(it)[NativeSettingsViewModel::class.java]
        }
        compose.waitUntil(30_000) { work.state.value.runtime.ready && work.state.value.workspaceResolved && work.state.value.models.isNotEmpty() }
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("native-composer").fetchSemanticsNodes(false).isNotEmpty() }
    }
    private fun sessions(): List<JSONObject> = runBlocking {
        val response = fixture.runtimeTransport.json("/droy/native/workspaces", "GET", null)
        assertTrue(response.getBoolean("ok"))
        val rows = response.getJSONObject("result").getJSONArray("sessions")
        (0 until rows.length()).map(rows::getJSONObject)
    }
    private fun text(language: String, resource: Int): String {
        val config = Configuration(fixture.context.resources.configuration).apply { setLocale(Locale.forLanguageTag(language)) }
        return fixture.context.createConfigurationContext(config).getString(resource)
    }
    private fun assertNoSubmissions() {
        assertFalse(work.state.value.running)
        assertFalse(work.state.value.sending)
        val calls = File(fixture.home, "native-fixture-calls.jsonl")
        assertTrue("Preparing a goal or a scheduled-task draft must not invoke any model", !calls.exists() || calls.readText().isBlank())
        assertTrue("Draft preparation must not create pending prompt submissions", fixture.context.getSharedPreferences("native_workbench", 0)
            .all.keys.none { it.startsWith("pending:") })
    }
}
