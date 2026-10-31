package io.droyagent.app

import android.content.Intent
import android.os.SystemClock
import android.view.WindowInsets
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.droyagent.app.nativeui.companion.data.*
import io.droyagent.app.nativeui.data.NativeApiClient
import io.droyagent.app.nativeui.data.NativeWorkbenchViewModel
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.rules.TestWatcher
import org.junit.runner.Description

/** Real Host and native input/navigation, with fictional data and an entirely local model. */
@RunWith(AndroidJUnit4::class)
class NativeCompanionIsolationDeviceTest {
    @get:Rule(order = 0) val compose = createEmptyComposeRule()
    @get:Rule(order = 1) val fixture = NativeIsolatedRuntimeFixture("messages",
        providerAsset = "native-companion-isolation-provider.mjs")
    @get:Rule(order = 2) val failureEvidence = object : TestWatcher() {
        override fun failed(failure: Throwable, description: Description) {
            try {
                val directory = File(fixture.context.getExternalFilesDir(null), "native-isolated-evidence").apply { mkdirs() }
                File(directory, "failure-${description.methodName}.txt").writeText(RuntimeService.diagnosticsSnapshot())
                fixture.home.listFiles()?.filter { it.isFile && it.name.startsWith("isolation-") }?.forEach {
                    it.copyTo(File(directory, "failure-${description.methodName}-${it.name}"), overwrite = true)
                }
            } catch (diagnostic: Throwable) { failure.addSuppressed(diagnostic) }
        }
    }
    private val client = NativeCompanionClient()
    private val api = NativeApiClient()
    private lateinit var model: NativeCompanionViewModel
    private lateinit var work: NativeWorkbenchViewModel

    private fun bind(): String {
        fixture.scenario.onActivity {
            model = ViewModelProvider(it)[NativeCompanionViewModel::class.java]
            work = ViewModelProvider(it)[NativeWorkbenchViewModel::class.java]
        }
        compose.waitUntil(30_000) { model.state.value.runtime.ready && model.state.value.companions.isNotEmpty() }
        val first = model.state.value.companions.first()
        runBlocking { client.updateProfile(first.id, NativeCompanionProfilePatch(
            settings = first.settings.copy(proactiveEnabled = false, memoryEnabled = false))) }
        fixture.scenario.onActivity { model.refresh() }
        assertTrue(fixture.context.getSharedPreferences("product", 0).edit()
            .putBoolean("backgroundRuntimeEnabled", false).putBoolean("returnToDroyOnCompletion", true).commit())
        return first.id
    }

    private fun openChat(id: String) {
        fixture.scenario.onActivity { model.refresh() }
        compose.waitUntil(15_000) { model.state.value.companions.any { it.id == id } }
        if (compose.onAllNodesWithTag("native-tab-messages").fetchSemanticsNodes(false).isNotEmpty())
            compose.onNodeWithTag("native-tab-messages").performClick()
        compose.onNodeWithTag("native-companion-conversation:$id").performClick()
        compose.waitUntil(15_000) { model.state.value.selectedCompanionId == id && !model.state.value.loading }
    }

    private fun marker(name: String) = File(fixture.home, "isolation-$name")
    private fun await(message: String, timeout: Long = 20_000, condition: () -> Boolean) {
        val until = SystemClock.elapsedRealtime() + timeout
        while (!condition() && SystemClock.elapsedRealtime() < until) SystemClock.sleep(50)
        assertTrue(message, condition())
    }
    private fun imeVisible(): Boolean {
        var visible = false
        fixture.scenario.onActivity { visible = it.window.decorView.rootWindowInsets?.isVisible(WindowInsets.Type.ime()) == true }
        return visible
    }
    private fun petAttached(): Boolean {
        var attached = false
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val pet = PetOverlay::class.java.getDeclaredField("pet").apply { isAccessible = true }
            attached = pet.get(PetOverlay.get(fixture.context)) != null
        }
        return attached
    }
    private suspend fun send(id: String, value: String) = client.send(id,
        JSONObject().put("clientId", UUID.randomUUID().toString()).put("text", value))

    @Test fun replyPreservesKeyboardDraftAndNeverStartsThePetOrAUserTask() = runBlocking {
        val id = bind()
        assertTrue(fixture.context.getSharedPreferences("product", 0).edit().putBoolean("petEnabled", true)
            .putString("petInternalDisplay", "adaptive").putString("petExternalDisplay", "off").commit())
        PetOverlay.refresh(fixture.context)
        openChat(id)
        compose.onNodeWithTag("native-companion-composer").performClick().performTextInput("ISOLATION_CHAT_KEYBOARD")
        compose.onNodeWithTag("native-companion-send").performClick()
        await("Replyer entered its real local model") { marker("running-chat-$id.json").isFile }
        compose.onNodeWithTag("native-companion-composer").performTextInput("Fictional next draft")
        await("Keyboard must remain visible while the partner replies") { imeVisible() }
        val before = ToolProgress.current(fixture.context).toString()
        repeat(12) { assertFalse("Internal chat must not attach the adaptive pet", petAttached()); SystemClock.sleep(50) }
        marker("release-chat-$id").writeText("true")
        compose.waitUntil(20_000) { model.state.value.messages.any { it.role == "assistant" && it.type == "text" } }
        compose.waitUntil(20_000) { model.state.value.status.execution?.quiescent == true }
        compose.waitForIdle()
        assertTrue("Completion must preserve the user's open keyboard", imeVisible())
        compose.onNodeWithTag("native-companion-composer").assertIsFocused()
        assertEquals("Fictional next draft", model.state.value.draft.text)
        assertEquals(before, ToolProgress.current(fixture.context).toString())
        assertFalse(petAttached())
        assertTrue(model.state.value.messages.none { it.type == "task" })
        fixture.capture("companion-isolation-keyboard")
    }

    @Test fun stoppingOneCompanionLeavesAnotherCompanionAndTheUsersTaskRunning() = runBlocking {
        val first = bind()
        val created = fixture.runtimeTransport.json("/droy/companions", "POST", JSONObject()
            .put("requestId", "isolation-second").put("templateId", "maimai").put("name", "Fictional second companion")
            .put("settings", JSONObject().put("memoryEnabled", false).put("proactiveEnabled", false)))
        val second = created.getJSONObject("profile").getString("id")
        compose.onNodeWithTag("native-tab-workbench").performClick()
        fixture.scenario.onActivity { work.setDraft("ISOLATION_USER_HOLD"); work.send() }
        compose.waitUntil(20_000) { work.state.value.selectedSessionId != null && work.state.value.running }
        val user = requireNotNull(work.state.value.selectedSessionId)
        await("User model request is running") { marker("running-user-$user.json").isFile }
        send(first, "ISOLATION_WORK_FIRST"); send(second, "ISOLATION_WORK_SECOND")
        await("Both independent work requests must start") {
            marker("running-work-$first.json").isFile && marker("running-work-$second.json").isFile
        }
        val firstWork = JSONObject(marker("running-work-$first.json").readText()).getString("sessionId")
        val secondWork = JSONObject(marker("running-work-$second.json").readText()).getString("sessionId")
        val catalog = api.get("workspaces") as JSONObject
        val visible = catalog.getJSONArray("sessions").let { rows -> (0 until rows.length()).map { rows.getJSONObject(it).getString("id") } }
        assertTrue(user in visible); assertFalse(firstWork in visible); assertFalse(secondWork in visible)
        assertEquals(user, work.state.value.selectedSessionId)
        assertEquals(user, ToolProgress.current(fixture.context).getString("taskId"))
        openChat(first)
        compose.onNodeWithTag("native-companion-composer-options").performClick()
        compose.onNodeWithTag("native-companion-stop").performClick()
        compose.waitUntil(20_000) { model.state.value.status.execution?.let { it.cancelRequested && it.quiescent } == true
            && !model.state.value.stopRequested }
        compose.waitForIdle()
        await("The stopped work provider must observe cancellation") { marker("aborted-work-$first").isFile }
        assertFalse(marker("aborted-work-$second").exists())
        assertFalse(client.page(second).status.execution!!.quiescent)
        assertTrue(ToolProgress.current(fixture.context).getBoolean("active"))
        assertEquals(user, ToolProgress.current(fixture.context).getString("taskId"))
        assertEquals(user, work.state.value.selectedSessionId)
        assertTrue(model.state.value.messages.none { it.type == "task" })
        fixture.capture("companion-isolation-stopped-owner")
        marker("release-work-$second").writeText("true")
        await("Second companion completes independently") { runBlocking { client.page(second).status.execution?.quiescent == true } }
        assertTrue(client.page(second).messages.any { it.role == "assistant" && it.type == "text" })
        assertTrue(client.page(first).messages.none { it.role == "assistant" })
        api.post("cancel", JSONObject().put("sessionId", user))
        await("User request is stopped separately") { marker("aborted-user-$user").isFile }
    }

    @Test fun hiddenWorkSurvivesBackgroundingAndFinishesWithoutReturningToTheApp() = runBlocking {
        val id = bind()
        send(id, "ISOLATION_WORK_BACKGROUND")
        await("Hidden background work must start") { marker("running-work-$id.json").isFile }
        assertFalse(ToolProgress.current(fixture.context).optBoolean("active"))
        assertTrue(CompanionRuntimeActivity.busy(RuntimeService.webPort, RuntimeService.webToken))
        fixture.context.startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        await("The Droy activity must leave the foreground") { !BackgroundRuntime.visible() }
        repeat(16) {
            assertTrue("Hidden work must keep its Host alive without public task progress", RuntimeService.ready)
            assertFalse(BackgroundRuntime.visible()); SystemClock.sleep(250)
        }
        marker("release-work-$id").writeText("true")
        await("After real completion the nonresident Host must be released", 30_000) { RuntimeService.canManageHistory() }
        assertFalse("A companion completion must not return to Droy", BackgroundRuntime.visible())
        assertTrue(File(fixture.home, "droy-companions/companions.sqlite").isFile)
    }

    @Test fun returningToTheForegroundDuringCommittedIdleExitRestartsTheHost() {
        bind()
        val previous = RuntimeService.runtimeIdentity
        marker("hold-idle-response").writeText("true")
        fixture.context.startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        await("The Host must commit its idle exit before foreground recovery") { marker("idle-committed").isFile }
        fixture.relaunch("messages")
        fixture.scenario.onActivity { model = ViewModelProvider(it)[NativeCompanionViewModel::class.java] }
        await("Droy is visible during the delayed shutdown response") { BackgroundRuntime.visible() }
        marker("release-idle-response").writeText("true")
        await("A fresh Host must start after the old idle Host really exits", 90_000) {
            RuntimeService.ready && RuntimeService.runtimeIdentity != previous
        }
        compose.waitUntil(20_000) { model.state.value.runtime.ready && !model.state.value.loading
            && model.state.value.error == null && model.state.value.companions.isNotEmpty() }
        compose.waitForIdle()
        compose.onNodeWithTag("native-companion-conversation:${model.state.value.companions.first().id}").assertIsDisplayed()
        fixture.capture("companion-isolation-foreground-recovery")
    }
}
