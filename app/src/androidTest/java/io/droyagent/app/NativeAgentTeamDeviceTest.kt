package io.droyagent.app

import androidx.activity.compose.setContent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.droyagent.app.nativeui.data.NativeApiClient
import io.droyagent.app.nativeui.team.NativeAgentTeamScreen
import io.droyagent.app.nativeui.ui.DroyTheme
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

/** A real blank DSH session/team projection; no prompt or model call. */
@RunWith(AndroidJUnit4::class)
class NativeAgentTeamDeviceTest {
    @get:Rule(order = 0) val compose = createEmptyComposeRule()
    @get:Rule(order = 1) val fixture = NativeIsolatedRuntimeFixture()

    @Test fun enabledTeamShowsLeadAndEmptyTaskBoardWithoutModelCall() = runBlocking {
        fixture.startRuntime()
        val enabled = fixture.runtimeTransport.json("/droy/native/advanced/approved-plugins/enable", "POST",
            JSONObject().put("id", "agent-team").put("enabled", true).put("expectedEnabled", false))
        assertTrue(enabled.toString(), enabled.getBoolean("ok"))
        val session = "native-team-${UUID.randomUUID()}"
        val api = NativeApiClient()
        val catalog = api.get("workspaces") as JSONObject
        val workspace = catalog.getJSONArray("workspaces").getJSONObject(0).getString("id")
        val created = api.post("create", JSONObject().put("sessionId", session).put("workspaceId", workspace)) as JSONObject
        assertEquals(session, created.getString("sessionId"))
        val projection = api.get("agent-team?sessionId=$session") as JSONObject
        assertTrue(projection.toString(), projection.getBoolean("available"))
        assertEquals(1, projection.getJSONArray("members").length())
        assertEquals(session, projection.getJSONArray("members").getJSONObject(0).getString("id"))
        assertEquals(0, projection.getJSONArray("tasks").length())
        val opened = AtomicReference<String?>()
        fixture.scenario.onActivity { activity -> activity.setContent {
            DroyTheme { NativeAgentTeamScreen(session, {}, opened::set) }
        } }
        compose.waitUntil(15_000) { compose.onAllNodesWithTag("native-team-member:$session").fetchSemanticsNodes(false).isNotEmpty() }
        compose.onNodeWithText(fixture.context.getString(R.string.native_team_no_tasks)).assertIsDisplayed()
        compose.onNodeWithTag("native-team-member:$session").performClick()
        assertEquals(session, opened.get())
        compose.waitForIdle(); compose.mainClock.advanceTimeBy(1_000); compose.waitForIdle()
        fixture.capture("round5-agent-team")
        val requests = File(fixture.home, "native-fixture-calls.jsonl")
        assertFalse("Creating and viewing a team must never request model generation", requests.exists() && requests.length() > 0)
    }
}
