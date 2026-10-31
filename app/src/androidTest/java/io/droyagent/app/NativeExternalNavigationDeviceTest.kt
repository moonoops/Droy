package io.droyagent.app

import android.content.Intent
import android.content.res.Configuration
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.droyagent.app.nativeui.companion.data.NativeCompanionViewModel
import io.droyagent.app.nativeui.data.NativeWorkbenchViewModel
import io.droyagent.app.nativeui.navigation.*
import java.io.File
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NativeExternalNavigationDeviceTest {
    @get:Rule(order = 0) val compose = createEmptyComposeRule()
    @get:Rule(order = 1) val fixture = NativeIsolatedRuntimeFixture()
    private lateinit var navigation: NativeExternalNavigationViewModel
    private lateinit var work: NativeWorkbenchViewModel
    private lateinit var companions: NativeCompanionViewModel

    @Test fun offlinePetTargetSurvivesRecreationAndOpensTheExactHistoryAfterExplicitStart() {
        models(); fixture.startRuntime(); awaitCatalog()
        val space = requireNotNull(work.state.value.defaultWorkspaceId)
        val session = "external-${UUID.randomUUID()}"
        native("/droy/native/session/create", JSONObject().put("sessionId", session).put("workspaceId", space))
        fixture.scenario.onActivity { work.setDraft("EXTERNAL_KEEP_ORDINARY_DRAFT") }
        val draft = work.state.value.draftId
        NativeRuntimeHost.stop(fixture.context)
        compose.waitUntil(30_000) { RuntimeService.canManageHistory() && !work.state.value.runtime.ready }
        deliver(Intent().putExtra("petTaskId", session).putExtra("petRequestId", "opaque-pet-request"))
        compose.waitUntil(10_000) { navigation.state.value.blocker == NativeExternalNavigationBlocker.RUNTIME }
        val request = requireNotNull(navigation.state.value.request).id
        fixture.recreate(); models()
        compose.waitUntil(10_000) { navigation.state.value.request?.id == request && navigation.state.value.blocker == NativeExternalNavigationBlocker.RUNTIME }
        assertFalse(RuntimeService.ready); assertTrue(RuntimeService.canManageHistory())
        compose.onNodeWithTag("native-external-start").assertIsDisplayed().performClick()
        compose.waitUntil(60_000) { work.state.value.sessionBaselineId == session && navigation.state.value.request == null }
        assertEquals(session, work.state.value.selectedSessionId)
        assertEquals(space, work.state.value.selectedWorkspaceId)
        assertEquals("EXTERNAL_KEEP_ORDINARY_DRAFT", fixture.context.getSharedPreferences("native_workbench", 0).getString("draft:$draft", null))
        assertFalse("Opaque pet IDs must not auto-answer anything", work.state.value.decisions.any { it.id == "opaque-pet-request" })
        assertNoCalls(); fixture.capture("native-external-exact-history")
    }

    @Test fun missingTargetStaysVisibleUntilDismissedAndArchivedHistoryRemainsReadOnly() {
        models(); fixture.startRuntime(); awaitCatalog()
        val before = sessions()
        deliver(Intent().putExtra("sessionId", "missing-${UUID.randomUUID()}"))
        compose.waitUntil(15_000) { navigation.state.value.failure == NativeExternalNavigationFailure.NOT_FOUND }
        compose.onNodeWithText(text(R.string.native_external_missing)).assertIsDisplayed()
        val pending = navigation.state.value.request?.id
        fixture.recreate(); models()
        compose.waitUntil(15_000) { navigation.state.value.request?.id == pending && navigation.state.value.failure == NativeExternalNavigationFailure.NOT_FOUND }
        assertEquals(before, sessions())
        compose.onNodeWithTag("native-external-dismiss").performClick()
        compose.waitUntil(10_000) { navigation.state.value.request == null }
        val session = "external-archived-${UUID.randomUUID()}"
        native("/droy/native/session/create", JSONObject().put("sessionId", session).put("workspaceId", work.state.value.defaultWorkspaceId))
        val archived = native("/droy/native/workspaces/mutate", JSONObject().put("requestId", UUID.randomUUID().toString()).put("kind", "session.archive")
            .put("args", JSONObject().put("sessionId", session)))
        assertEquals("complete", archived.getString("state"))
        deliver(Intent().putExtra("sessionId", session))
        compose.waitUntil(20_000) { work.state.value.sessionBaselineId == session && navigation.state.value.request == null }
        assertTrue(work.state.value.archived); assertTrue(work.state.value.readOnly)
        compose.onNodeWithTag("native-composer").assertIsNotEnabled()
        compose.onNodeWithTag("native-send").assertIsNotEnabled()
        assertNoCalls(); fixture.capture("native-external-archived-read-only")
    }

    @Test fun emptyCompanionTargetOpensItsChatWithoutCreatingAnEngineConversation() {
        models(); fixture.startRuntime(); awaitCatalog()
        compose.waitUntil(20_000) { companions.state.value.companions.isNotEmpty() }
        val companion = companions.state.value.companions.first()
        assertNull(companion.chatSessionId)
        val before = sessions()
        deliver(Intent().putExtra("companionId", companion.id))
        compose.waitUntil(20_000) { navigation.state.value.request == null && companions.state.value.selectedCompanionId == companion.id && !companions.state.value.loading }
        compose.onNodeWithTag("native-root-navigation").assertDoesNotExist()
        assertEquals(before, sessions()); assertNoCalls()
        fixture.capture("native-external-empty-companion")
    }

    @Test fun localNotificationSettingsOpenWithTheEngineStoppedAndInvalidTargetsHaveBilingualFeedback() {
        models()
        deliver(Intent().putExtra("notificationManagement", true))
        compose.waitUntil(15_000) { compose.onAllNodesWithTag("native-product-settings:notifications").fetchSemanticsNodes(false).isNotEmpty() }
        compose.waitUntil(15_000) { navigation.state.value.request == null }
        assertFalse(RuntimeService.ready); assertTrue(RuntimeService.canManageHistory())
        for (language in listOf("en", "zh")) {
            assertTrue(fixture.context.getSharedPreferences("product", 0).edit().putString("languagePreference", language).commit())
            compose.waitUntil(10_000) { work.state.value.runtime.locale == language }
            deliver(Intent().putExtra("sessionId", "../invalid"))
            compose.waitUntil(10_000) { navigation.error.value != null }
            compose.onNodeWithText(text(R.string.native_external_invalid)).assertIsDisplayed()
            fixture.capture("native-external-invalid-$language")
            compose.onNodeWithTag("native-external-dismiss").performClick()
            compose.waitUntil(10_000) { navigation.error.value == null }
        }
        assertTrue(RuntimeService.canManageHistory()); assertNoCalls()
    }

    private fun models() = fixture.scenario.onActivity {
        val provider = ViewModelProvider(it)
        navigation = provider[NativeExternalNavigationViewModel::class.java]
        work = provider[NativeWorkbenchViewModel::class.java]
        companions = provider[NativeCompanionViewModel::class.java]
    }
    private fun deliver(intent: Intent) = fixture.scenario.onActivity {
        it.startActivity(Intent(it, NativeAppActivity::class.java).putExtras(intent))
    }
    private fun awaitCatalog() = compose.waitUntil(30_000) { work.state.value.runtime.ready && work.state.value.workspaceResolved && work.state.value.workspaces.isNotEmpty() }
    private fun native(path: String, body: JSONObject? = null): JSONObject = runBlocking {
        val result = fixture.runtimeTransport.json(path, if (body == null) "GET" else "POST", body)
        assertTrue(result.getBoolean("ok")); result.getJSONObject("result")
    }
    private fun sessions(): Set<String> {
        val rows = native("/droy/native/session/list", JSONObject()).getJSONArray("items")
        return (0 until rows.length()).map { rows.getJSONObject(it).getString("sessionId") }.toSet()
    }
    private fun text(id: Int): String = fixture.context.createConfigurationContext(Configuration(fixture.context.resources.configuration).apply {
        setLocale(Locale.forLanguageTag(work.state.value.runtime.locale))
    }).getString(id)
    private fun assertNoCalls() {
        val file = File(fixture.home, "native-fixture-calls.jsonl")
        assertTrue("Opening a target must not invoke even the local model", !file.exists() || file.readText().isBlank())
    }
}
