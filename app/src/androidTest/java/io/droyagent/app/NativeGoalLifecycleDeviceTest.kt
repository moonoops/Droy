package io.droyagent.app

import android.content.Intent
import android.content.res.Configuration
import android.view.KeyEvent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.droyagent.app.nativeui.data.NativeWorkbenchViewModel
import io.droyagent.app.nativeui.engine.NativeEngineViewModel
import io.droyagent.app.nativeui.settings.NativeSettingsViewModel
import java.io.File
import java.util.Locale
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Actual SDK goal rounds and native controls; only the isolated, local provider is used. */
@RunWith(AndroidJUnit4::class)
class NativeGoalLifecycleDeviceTest {
    @get:Rule(order = 0) val compose = createEmptyComposeRule()
    @get:Rule(order = 1) val fixture = NativeIsolatedRuntimeFixture()
    private lateinit var work: NativeWorkbenchViewModel
    private lateinit var engine: NativeEngineViewModel
    private lateinit var settings: NativeSettingsViewModel
    private var language = "en"

    @Test fun nativeGoalControlsPauseResumeStopAndReplayWithoutLosingTheUnsentDraft() {
        models()
        val retainedDraft = "UNSENT_GOAL_LIFECYCLE_DRAFT"
        val objective = "NATIVE_GOAL_LIFECYCLE: verify local control and durable replay."
        val sessionId = work.state.value.draftId
        fixture.scenario.onActivity { work.setDraft(retainedDraft) }
        openGoal()
        assertEquals(sessionId, work.state.value.selectedSessionId)
        assertEquals(retainedDraft, work.state.value.draft)
        assertTrue(calls().isEmpty())
        compose.onNodeWithText(text(R.string.native_goal_objective)).performTextInput(objective)
        compose.onNodeWithText(text(R.string.native_goal_start)).performScrollTo().assertIsEnabled().performClick()
        awaitGoal("active", "armed")
        compose.waitUntil(30_000) { goalCalls().size == 1 && work.state.value.running }
        val created = goal()
        val goalId = created.getString("id")
        assertEquals(objective, created.getString("objective"))
        assertEquals(1, goalCalls().single().getJSONObject("goal").getInt("round"))
        assertEquals(goalId, goalCalls().single().getJSONObject("goal").getString("goalId"))

        compose.onNodeWithText(text(R.string.native_work_pause)).performScrollTo().performClick()
        awaitGoal("paused", "disarmed")
        val paused = goal()
        assertEquals(goalId, paused.getString("id"))
        assertTrue(paused.getLong("revision") > created.getLong("revision"))
        // The locked SDK also cancels the current round when a human pauses its goal.
        compose.waitUntil(30_000) { !work.state.value.running && !work.state.value.sending && File(fixture.home, "native-fixture-goal-aborted.json").isFile }
        assertEquals(1, JSONObject(File(fixture.home, "native-fixture-goal-aborted.json").readText()).getInt("round"))
        awaitGoal("paused", "disarmed")
        compose.onNodeWithText(text(R.string.native_goal_paused)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.native_goal_resume)).assertIsEnabled()
        assertEquals(1, goalCalls().size)
        compose.waitForIdle()
        fixture.capture("native-goal-paused-en")
        closeGoal()
        fixture.recreate()
        models()
        assertEquals(sessionId, work.state.value.selectedSessionId)
        assertEquals(retainedDraft, work.state.value.draft)
        fixture.scenario.onActivity { settings.setLanguage("zh") }
        compose.waitUntil(20_000) { settings.state.value.locale == "zh" && !settings.state.value.saving && work.state.value.runtime.locale == "zh" }
        assertNull(settings.state.value.error)
        language = "zh"
        openGoal()
        awaitGoal("paused", "disarmed")
        assertEquals(goalId, goal().getString("id"))
        assertEquals(1, goalCalls().size)
        compose.onNodeWithText(text(R.string.native_goal_resume)).performScrollTo().assertIsEnabled().performClick()
        awaitGoal("active", "armed")
        compose.waitUntil(30_000) { goalCalls().size == 2 && work.state.value.running }
        val resumed = goal()
        assertTrue(resumed.getLong("revision") > paused.getLong("revision"))
        assertEquals(listOf(1, 2), goalCalls().map { it.getJSONObject("goal").getInt("round") })
        assertTrue(goalCalls().all { it.getJSONObject("goal").getString("goalId") == goalId })
        compose.onNodeWithText(text(R.string.native_goal_active)).assertIsDisplayed()
        compose.waitForIdle()
        fixture.capture("native-goal-resumed-zh")
        closeGoal()
        compose.onNodeWithContentDescription(text(R.string.native_stop)).assertIsEnabled().performClick()
        compose.waitUntil(30_000) { !work.state.value.running && File(fixture.home, "native-fixture-goal-aborted.json").isFile }
        assertEquals(2, JSONObject(File(fixture.home, "native-fixture-goal-aborted.json").readText()).getInt("round"))
        openGoal()
        awaitGoal("paused", "disarmed")
        compose.onNodeWithText(text(R.string.native_goal_paused)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.native_goal_resume)).assertIsEnabled()
        compose.onNodeWithText(text(R.string.native_work_pause)).assertDoesNotExist()
        assertTrue(goal().getLong("revision") > resumed.getLong("revision"))
        compose.onNodeWithText(text(R.string.native_goal_resume)).performScrollTo().performClick()
        awaitGoal("active", "armed")
        compose.waitUntil(30_000) { goalCalls().size == 3 && work.state.value.running }
        closeGoal()

        // A real model error disarms an active goal without inventing a paused durable phase.
        File(fixture.home, "native-fixture-goal-fail-round-3").writeText("fail the owned local model call")
        compose.waitUntil(30_000) { !work.state.value.running && File(fixture.home, "native-fixture-goal-failed.json").isFile }
        assertEquals(3, JSONObject(File(fixture.home, "native-fixture-goal-failed.json").readText()).getInt("round"))
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("native-error").fetchSemanticsNodes(false).isNotEmpty() }
        val errorBounds = compose.onNodeWithTag("native-error").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val composerBounds = compose.onNodeWithTag("native-composer").assertIsDisplayed().assertTextContains(retainedDraft).fetchSemanticsNode().boundsInRoot
        assertTrue("A real model error must remain visible above the composer", errorBounds.bottom <= composerBounds.top)
        compose.waitForIdle()
        fixture.capture("native-goal-provider-error-inline-zh")
        openGoal()
        awaitGoal("active", "disarmed")
        compose.onNodeWithText(text(R.string.native_goal_paused)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.native_goal_resume)).assertIsEnabled()
        assertEquals(3, goalCalls().size)
        val beforeRestart = goal()
        compose.waitForIdle()
        fixture.capture("native-goal-model-error-disarmed-zh")
        closeGoal()

        // Restart the actual Host to verify durable active does not grant automatic authority.
        fixture.scenario.moveToState(Lifecycle.State.CREATED)
        fixture.context.startService(Intent(fixture.context, RuntimeService::class.java).setAction(RuntimeService.STOP))
        compose.waitUntil(30_000) { RuntimeService.canManageHistory() && !NativeRuntimeHost.inspect(fixture.context).ready }
        fixture.scenario.moveToState(Lifecycle.State.RESUMED)
        compose.waitUntil(120_000) { NativeRuntimeHost.inspect(fixture.context).ready && work.state.value.runtime.ready }
        openGoal()
        awaitGoal("active", "disarmed")
        assertEquals(goalId, goal().getString("id"))
        assertEquals(beforeRestart.getLong("revision"), goal().getLong("revision"))
        assertEquals(3, goalCalls().size)
        assertEquals(retainedDraft, work.state.value.draft)
        compose.onNodeWithText(text(R.string.native_goal_paused)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.native_goal_resume)).assertIsEnabled()
        compose.onNodeWithText(text(R.string.native_work_pause)).assertDoesNotExist()
        compose.waitForIdle()
        // Host/window recreation also crosses Android's real compositor clock.
        android.os.SystemClock.sleep(500)
        fixture.capture("native-goal-restarted-disarmed-zh")
        compose.onNodeWithText(text(R.string.native_goal_clear)).performScrollTo().performClick()
        compose.waitUntil(15_000) { !engine.state.value.busy && !engine.state.value.sessionLoading && engine.state.value.session?.goal == null }
        assertTrue(session().isNull("goal"))
        compose.onNodeWithText(text(R.string.native_goal_objective)).assertIsDisplayed()
        assertEquals(3, goalCalls().size)
        val operations = goalOperations(sessionId)
        assertEquals(listOf("create", "pause", "resume", "pause", "resume", "clear"), operations)
        assertTrue("Goal controls must not submit the retained ordinary draft", calls().all { it.optBoolean("title") || it.has("goal") })
        assertEquals(retainedDraft, work.state.value.draft)
        assertTrue(fixture.context.getSharedPreferences("native_workbench", 0).all.keys.none { it.startsWith("pending:") })
        compose.waitForIdle()
        fixture.capture("native-goal-cleared-zh")
        val receipt = JSONObject().put("sessionId", sessionId).put("goalId", goalId).put("operations", JSONArray(operations))
            .put("modelCalls", goalCalls().size).put("goalRounds", JSONArray(listOf(1, 2, 3)))
            .put("ordinaryDraftPreserved", true).put("actualStopDisarmed", true).put("hostRestartDisarmed", true)
            .put("modelErrorDisarmed", true)
            .put("localProviderOnly", true).put("coverage", "actual_native_controls_sdk_goal_rounds_pause_resume_stop_host_restart_clear")
        File(fixture.context.getExternalFilesDir(null), "native-isolated-evidence/native-goal-lifecycle-receipt.json").writeText(receipt.toString(2))
    }

    private fun models() {
        fixture.scenario.onActivity {
            work = ViewModelProvider(it)[NativeWorkbenchViewModel::class.java]
            engine = ViewModelProvider(it)[NativeEngineViewModel::class.java]
            settings = ViewModelProvider(it)[NativeSettingsViewModel::class.java]
        }
        compose.waitUntil(30_000) { work.state.value.runtime.ready && work.state.value.workspaceResolved && work.state.value.models.isNotEmpty() }
        compose.waitUntil(15_000) { compose.onAllNodesWithTag("native-composer").fetchSemanticsNodes(false).isNotEmpty() }
    }
    private fun openGoal() {
        compose.onNodeWithTag("native-composer-attachments").performClick()
        compose.onNodeWithText(text(R.string.native_goal_title)).assertIsEnabled().performClick()
        compose.waitUntil(30_000) { engine.state.value.session?.id == work.state.value.selectedSessionId && engine.state.value.session?.fresh == true && !engine.state.value.sessionLoading }
        compose.onNodeWithText(text(R.string.native_goal_title)).assertIsDisplayed()
    }
    private fun closeGoal() {
        back()
        compose.waitUntil(10_000) { compose.onAllNodesWithText(text(R.string.native_goal_title)).fetchSemanticsNodes(false).isEmpty() }
    }
    private fun back() = InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
    private fun awaitGoal(phase: String, activation: String) {
        try {
            compose.waitUntil(15_000) { engine.state.value.let { !it.busy && !it.sessionLoading && it.session?.goal?.phase == phase && it.session?.goal?.activation == activation } }
        } catch (error: ComposeTimeoutException) {
            throw AssertionError("Expected $phase/$activation; native=${engine.state.value.session}; actual=${session()}", error)
        }
        val actual = goal()
        assertEquals(phase, actual.getString("phase"))
        assertEquals(activation, actual.getString("activation"))
        assertNull(engine.state.value.error)
    }
    private fun session(): JSONObject = native("/droy/native/engine/session", JSONObject().put("sessionId", work.state.value.selectedSessionId))
    private fun goal(): JSONObject = session().getJSONObject("goal")
    private fun goalOperations(sessionId: String): List<String> {
        val snapshot = session()
        val page = native("/droy/native/session/page", JSONObject().put("address", JSONObject().put("kind", "session").put("sessionId", sessionId))
            .put("throughSeq", snapshot.getLong("asOfSeq")).put("maxMessages", 100))
        val records = page.getJSONArray("records")
        return (0 until records.length()).map(records::getJSONObject)
            .mapNotNull { it.optJSONObject("event") }.filter { it.optString("type") == "goal/change" }
            .mapNotNull { it.optJSONObject("data") }
            .filter { it.optString("kind") == "goal/change" }.map { it.getString("operation") }
    }
    private fun native(path: String, body: JSONObject): JSONObject = runBlocking {
        val response = fixture.runtimeTransport.json(path, "POST", body)
        assertTrue(response.toString(), response.getBoolean("ok"))
        response.getJSONObject("result")
    }
    private fun calls(): List<JSONObject> = File(fixture.home, "native-fixture-calls.jsonl").takeIf { it.isFile }
        ?.readLines()?.filter { it.isNotBlank() }?.map(::JSONObject).orEmpty()
    private fun goalCalls() = calls().filter { !it.optBoolean("title") && it.has("goal") }
    private fun text(resource: Int): String {
        val configuration = Configuration(fixture.context.resources.configuration).apply { setLocale(Locale.forLanguageTag(language)) }
        return fixture.context.createConfigurationContext(configuration).getString(resource)
    }
}
