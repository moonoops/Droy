package io.droyagent.app

import android.content.Intent
import android.util.Log
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.droyagent.app.nativeui.data.NativeApiClient
import io.droyagent.app.nativeui.data.NativeWorkbenchViewModel
import java.io.File
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Real DSH control/follow and Compose badge, using only the isolated local fixture adapter. */
@RunWith(AndroidJUnit4::class)
class NativeWorkbenchUnreadDeviceTest {
    @get:Rule(order = 0) val compose = createEmptyComposeRule()
    @get:Rule(order = 1) val fixture = NativeIsolatedRuntimeFixture("messages")
    private lateinit var model: NativeWorkbenchViewModel

    @Test fun backgroundResultMarksWorkbenchUntilActuallyReadAndColdHistoryDoesNotReappear() {
        bindModel()
        compose.waitUntil(30_000) {
            model.state.value.let { it.runtime.ready && it.workspaceResolved && it.selectedModel?.provider == "droy-native-fixture" }
        }
        compose.onNodeWithTag("native-tab-messages").assertIsSelected()
        assertTrue(model.state.value.unreadSessionIds.isEmpty())
        badge().assertCountEquals(0)

        // A real submit while the user remains on Messages exercises local creation,
        // DSH streaming/turn completion, the control stream and background unread delivery.
        fixture.scenario.onActivity { model.setDraft("NATIVE_HELLO"); model.send() }
        awaitStage("local_reply") {
            model.state.value.let { value -> value.selectedSessionId != null && !value.sending && !value.running
                && value.messages.any { it.role == "assistant" && it.text == "Native DSH reply received." } }
        }
        awaitStage("background_unread") {
            model.state.value.let { it.selectedSessionId in it.unreadSessionIds }
        }
        val session = requireNotNull(model.state.value.selectedSessionId)
        val projection = runBlocking { NativeApiClient().post("projections", JSONObject().put("sessionId", session)) as JSONObject }
        assertEquals("sequenced", projection.getString("kind"))
        val boundary = projection.getJSONObject("values").getJSONObject("droyTurnBoundary")
        assertTrue("The real DSH turn must be complete", boundary.isNull("openTurnStartSeq"))
        val completedTurn = boundary.getInt("lastTurn")
        assertTrue(completedTurn > 0)
        compose.onNodeWithTag("native-tab-messages").assertIsSelected()
        compose.waitUntil(10_000) { badge().fetchSemanticsNodes().size == 1 }
        badge().onFirst().assertIsDisplayed()
        fixture.capture("native-workbench-unread-background")

        compose.onNodeWithTag("native-tab-workbench").performClick()
        awaitStage("read_completed_result", 20_000) {
            model.state.value.let { it.sessionBaselineId == session && it.historyCursor >= projection.getLong("asOfSeq")
                && session !in it.unreadSessionIds }
        }
        compose.onNodeWithText("Native DSH reply received.").assertIsDisplayed()
        assertEquals(completedTurn, preferences().getInt("workReadTurn:$session", -1))
        fixture.capture("native-workbench-unread-read")
        compose.onNodeWithTag("native-tab-messages").performClick()
        badge().assertCountEquals(0)
        assertFalse(session in model.state.value.unreadSessionIds)

        // Reopen the persisted synthetic history with no saved read watermark. This is
        // an initial-history migration, not a fake receipt or a manually assigned badge.
        val previousModel = model
        fixture.scenario.moveToState(Lifecycle.State.DESTROYED)
        fixture.context.startService(Intent(fixture.context, RuntimeService::class.java).setAction(RuntimeService.STOP))
        compose.waitUntil(30_000) { RuntimeService.canManageHistory() }
        val beforeCalls = calls().size
        assertTrue(preferences().edit().remove("workReadTurn:$session").commit())
        fixture.relaunch("messages")
        fixture.startRuntime()
        bindModel()
        assertNotSame(previousModel, model)
        compose.waitUntil(30_000) {
            model.state.value.runtime.ready && model.state.value.sessions.any { it.id == session }
                && preferences().getInt("workReadTurn:$session", -1) == completedTurn
        }
        val rows = runBlocking { NativeApiClient().post("list") as JSONObject }.getJSONArray("items")
        val cold = (0 until rows.length()).map(rows::getJSONObject).single { it.getString("sessionId") == session }
        assertFalse("Reading cold history must not restore an Agent", cold.getBoolean("agentAvailable"))
        assertFalse(session in model.state.value.unreadSessionIds)
        compose.onNodeWithTag("native-tab-messages").assertIsSelected()
        badge().assertCountEquals(0)
        assertEquals("Cold history inspection must not invoke a model", beforeCalls, calls().size)
        assertTrue(calls().all { it.getString("model") == "native-local" })
        fixture.capture("native-workbench-unread-cold-baseline")
    }

    @Test fun resultFromPreviousChatHasAnEntryAndKeepsTheNewDraft() {
        bindModel()
        compose.waitUntil(30_000) { model.state.value.let { it.runtime.ready && it.workspaceResolved && it.selectedModel != null } }
        // Settings children share the shell route transitions and restore the root on back.
        compose.onNodeWithTag("native-tab-settings").performClick()
        compose.onNodeWithTag("native-settings-models").performClick()
        compose.onNodeWithTag("native-settings-default-model").assertIsDisplayed()
        compose.onAllNodesWithTag("native-root-navigation").assertCountEquals(0)
        compose.onNodeWithTag("native-settings-back").performClick()
        compose.onNodeWithTag("native-settings-general").assertIsDisplayed()
        compose.onNodeWithTag("native-tab-workbench").performClick()
        fixture.scenario.onActivity {
            model.setDraft("NATIVE_BACKGROUND_REPLY"); model.send()
            assertEquals("Sending consumes the composer synchronously", "", model.state.value.draft)
            // A user may immediately type the same content again; the asynchronous receipt
            // for the first message cannot clear this new draft by text equality.
            model.setDraft("NATIVE_BACKGROUND_REPLY")
        }
        compose.waitUntil(30_000) { model.state.value.let { !it.sending && it.running && it.messages.any { message -> message.text.contains("Background reply started.") } } }
        val prior = requireNotNull(model.state.value.selectedSessionId)
        assertEquals("NATIVE_BACKGROUND_REPLY", model.state.value.draft)
        compose.onNodeWithTag("native-new-session").performClick()
        compose.waitUntil(10_000) { model.state.value.selectedSessionId == null }
        fixture.scenario.onActivity { model.setDraft("Keep this new draft") }
        File(fixture.home, "native-fixture-background-release").writeText("release")
        compose.waitUntil(30_000) { prior in model.state.value.unreadSessionIds }
        assertNull(model.state.value.selectedSessionId)
        assertEquals("Keep this new draft", model.state.value.draft)
        compose.onNodeWithTag("native-workbench-unread-results").assertIsDisplayed().performClick()
        compose.waitUntil(15_000) { compose.onAllNodesWithTag("native-space-unread:$prior", useUnmergedTree = true).fetchSemanticsNodes(false).isNotEmpty() }
        compose.onNodeWithTag("native-space-session:$prior").assertIsDisplayed().performClick()
        compose.waitUntil(30_000) { model.state.value.selectedSessionId == prior && prior !in model.state.value.unreadSessionIds }
        compose.onNodeWithText("Native DSH reply received.").assertIsDisplayed()
        assertNull(model.state.value.error)
        fixture.scenario.onActivity { assertTrue(model.openDraft(model.state.value.selectedWorkspaceId)) }
        assertEquals("Keep this new draft", model.state.value.draft)
    }

    private fun awaitStage(stage: String, timeoutMs: Long = 30_000, condition: () -> Boolean) {
        try {
            compose.waitUntil(timeoutMs) { condition() || model.state.value.error != null }
            assertNull("$stage must not fail before delivery", model.state.value.error)
            assertTrue(stage, condition())
        } catch (error: Throwable) {
            // The fixture home is restored by the rule. Retain only this synthetic test's
            // evidence before cleanup so a timeout distinguishes submit, follow and badge failures.
            val value = model.state.value
            val evidence = JSONObject().put("stage", stage).put("error", value.error)
                .put("sessionId", value.selectedSessionId).put("baseline", value.sessionBaselineId)
                .put("sending", value.sending).put("running", value.running).put("cursor", value.historyCursor)
                .put("readingPosition", value.readingPosition?.let { JSONObject().put("key", it.key).put("followingTail", it.followingTail).put("offset", it.offset) })
                .put("unread", org.json.JSONArray(value.unreadSessionIds.toList()))
                .put("messages", org.json.JSONArray(value.messages.map { JSONObject().put("role", it.role).put("text", it.text) }))
                .put("calls", org.json.JSONArray(calls()))
            value.selectedSessionId?.let { session ->
                try { evidence.put("projection", runBlocking { NativeApiClient().post("projections", JSONObject().put("sessionId", session)) }) }
                catch (inspection: Exception) { evidence.put("projectionError", inspection.javaClass.simpleName) }
                evidence.put("savedRead", preferences().getInt("workReadTurn:$session", -1))
            }
            val directory = File(fixture.context.getExternalFilesDir(null), "native-isolated-evidence").apply { mkdirs() }
            File(directory, "native-workbench-unread-failure.json").writeText(evidence.toString(2))
            Log.e("DroyUnreadFixture", "stage=$stage error=${value.error} session=${value.selectedSessionId != null} sending=${value.sending} running=${value.running} cursor=${value.historyCursor} unread=${value.unreadSessionIds.size} messages=${value.messages.size} calls=${calls().size}")
            throw AssertionError("Unread fixture stage $stage failed; inspect native-workbench-unread-failure.json", error)
        }
    }

    private fun bindModel() = fixture.scenario.onActivity { activity ->
        model = ViewModelProvider(activity)[NativeWorkbenchViewModel::class.java]
    }
    private fun preferences() = fixture.context.getSharedPreferences("native_workbench", 0)
    private fun badge() = compose.onAllNodes(
        hasContentDescription("1 unread") and hasAnyAncestor(hasTestTag("native-tab-workbench")), useUnmergedTree = true)
    private fun calls(): List<JSONObject> = File(fixture.home, "native-fixture-calls.jsonl")
        .takeIf(File::isFile)?.readLines()?.filter(String::isNotBlank)?.map(::JSONObject).orEmpty()
}
