package io.droyagent.app

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.droyagent.app.nativeui.companion.data.*
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Real companion endpoints, only fictional data and the emulator's local model. */
@RunWith(AndroidJUnit4::class)
class NativeCompanionOutboxDeviceTest {
    @get:Rule(order = 0) val compose = createEmptyComposeRule()
    @get:Rule(order = 1) val fixture = NativeIsolatedRuntimeFixture()
    private lateinit var model: NativeCompanionViewModel
    private fun bind() { fixture.scenario.onActivity { model = ViewModelProvider(it)[NativeCompanionViewModel::class.java] } }
    @Test fun failedDeliveryDoesNotBlockNextMessageAndCanBeRetriedOrDeletedAfterRecreation() = runBlocking {
        bind(); fixture.startRuntime()
        compose.waitUntil(30_000) { model.state.value.runtime.ready && model.state.value.companions.isNotEmpty() }
        val companion = model.state.value.companions.first()
        val id = companion.id
        compose.waitUntil(10_000) { model.state.value.models.any { it.provider == "droy-native-fixture" && it.id == "native-local" } }
        val localModel = model.state.value.models.first { it.provider == "droy-native-fixture" && it.id == "native-local" }
        fixture.scenario.onActivity { model.updateProfile(id, NativeCompanionProfilePatch(model = localModel,
            settings = companion.settings.copy(proactiveEnabled = false))) }
        compose.waitUntil(15_000) { !model.state.value.profileSaving && model.state.value.companions.any { it.id == id && !it.modelInherited && it.model?.provider == "droy-native-fixture" } }
        compose.onNodeWithTag("native-tab-messages").performClick()
        compose.onNodeWithTag("native-companion-conversation:$id").performClick()
        compose.waitUntil(10_000) { model.state.value.selectedCompanionId == id && !model.state.value.loading }
        // A rejected owner-scoped upload produces a genuine send failure before admission.
        val missing = NativeCompanionAttachment("missing-fixture", "fictional.txt", "text/plain", 1,
            "/droy/companions/$id/attachments/missing-fixture")
        fixture.scenario.onActivity { model.addAttachments(id, listOf(missing)); model.setDraft("Rejected fixture attachment"); model.send() }
        compose.waitUntil(15_000) { !model.state.value.sending && model.state.value.messages.any { it.status == "failed" } }
        val failed = model.state.value.messages.single { it.status == "failed" }
        assertFalse(model.state.value.draft.canSend)
        compose.waitForIdle(); compose.mainClock.advanceTimeBy(1_000); compose.waitForIdle(); fixture.capture("native-companion-failed-icon")
        fixture.scenario.onActivity { model.setDraft("A new independent fictional message"); model.send() }
        compose.waitUntil(15_000) { !model.state.value.sending && model.state.value.messages.any { it.text == "A new independent fictional message" && it.status != "failed" } }
        fixture.scenario.onActivity { it.viewModelStore.clear() }; fixture.recreate(); bind()
        compose.waitUntil(20_000) { model.state.value.runtime.ready && model.state.value.messages.any { it.id == failed.id } }
        fixture.scenario.onActivity { model.retryPending(failed.id) }
        compose.waitUntil(15_000) { !model.state.value.sending && model.state.value.messages.any { it.id == failed.id && it.status == "failed" } }
        assertEquals(1, model.state.value.messages.count { it.id == failed.id })
        // The row center is an actionable file button. Long-press the message text itself.
        compose.onNodeWithText("Rejected fixture attachment").performScrollTo().performTouchInput { longClick() }
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("native-companion-delete-message").fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle(); compose.mainClock.advanceTimeBy(1_000); compose.waitForIdle(); fixture.capture("native-companion-failed-actions")
        compose.onNodeWithTag("native-companion-delete-message").assertIsEnabled().performClick()
        compose.onNodeWithTag("native-companion-confirm-delete").performClick()
        compose.waitUntil(15_000) { model.state.value.messages.none { it.id == failed.id } }
        val prefs = fixture.context.getSharedPreferences("native_companions", 0)
        assertFalse(prefs.contains("pending:$id:${failed.id}"))
        assertEquals(1, NativeCompanionClient().page(id).messages.count { it.text == "A new independent fictional message" })

        // Reconstruct precisely the crash checkpoint after the server accepted but before its receipt was retired.
        val record = NativeCompanionSubmission.prepare(id, NativeCompanionDraft("Accepted once before recovery"), "2026-10-30T00:00:00Z")
        NativeCompanionClient().send(id, JSONObject(record.payload))
        assertTrue(prefs.edit().putString("pending:$id", record.copy(uncertain = true).json().toString()).commit())
        fixture.scenario.onActivity { it.viewModelStore.clear() }; fixture.recreate(); bind()
        compose.waitUntil(20_000) { model.state.value.runtime.ready && model.state.value.messages.any { it.id == record.clientId } }
        fixture.scenario.onActivity { model.retryPending(record.clientId) }
        compose.waitUntil(15_000) { !model.state.value.sending }
        assertEquals(1, NativeCompanionClient().page(id).messages.count { it.id == record.clientId || it.clientId == record.clientId })
        compose.waitUntil(15_000) { compose.onAllNodesWithText("Accepted once before recovery").fetchSemanticsNodes(false).isNotEmpty() }
        compose.onNodeWithText("Accepted once before recovery").assertIsDisplayed()
        compose.waitForIdle(); compose.mainClock.advanceTimeBy(1_000); compose.waitForIdle(); fixture.capture("native-companion-outbox-recovered")
    }
}
