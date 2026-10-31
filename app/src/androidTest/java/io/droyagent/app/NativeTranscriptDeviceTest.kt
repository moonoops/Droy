package io.droyagent.app

import android.content.Intent
import android.content.res.Configuration
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.droyagent.app.nativeui.data.NativeApiException
import io.droyagent.app.nativeui.navigation.NativeExternalNavigationFailure
import io.droyagent.app.nativeui.navigation.NativeExternalNavigationPhase
import io.droyagent.app.nativeui.navigation.NativeExternalNavigationViewModel
import io.droyagent.app.nativeui.settings.NativeSettingsViewModel
import io.droyagent.app.nativeui.transcript.NativeTranscriptViewModel
import java.io.File
import java.util.Locale
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Real cold JSONL history in the shared, explicitly opted-in emulator fixture. */
@RunWith(AndroidJUnit4::class)
class NativeTranscriptDeviceTest {
    @get:Rule(order = 0) val compose = createEmptyComposeRule()
    @get:Rule(order = 1) val fixture = NativeIsolatedRuntimeFixture()

    private lateinit var transcript: NativeTranscriptViewModel
    private lateinit var navigation: NativeExternalNavigationViewModel
    private lateinit var settings: NativeSettingsViewModel
    private lateinit var receipt: JSONObject
    private val ids: JSONObject get() = receipt.getJSONObject("ids")
    private val child: String get() = ids.getString("child")
    private val parent: String get() = ids.getString("parent")

    @Test fun coldSubagentIntentPaginatesAndRecreatesReadOnlyTranscript() {
        startFixture()
        assertColdAudit(audit(), detached = true)
        assertResolvedChild()
        openSession(child)
        awaitTranscript()
        assertReadOnly("en")
        assertTrue("The opening snapshot must leave older messages to page", transcript.state.value.hasMore)
        assertEquals(60, transcript.state.value.messages.size)
        assertEquals(receipt.getJSONArray("messages").getJSONObject(89).getString("assistantText"), transcript.state.value.messages.last().text)
        fixture.capture("native-transcript-en-opening")

        var pages = 0
        while (transcript.state.value.hasMore && pages++ < 6) {
            compose.onNodeWithTag("native-transcript-history").performScrollToIndex(0)
            awaitTag("native-transcript-load-older")
            val before = transcript.state.value.messages.size
            compose.onNodeWithTag("native-transcript-load-older").assertIsEnabled().performClick()
            compose.waitUntil(20_000) { transcript.state.value.let { !it.loadingOlder && (it.messages.size > before || !it.hasMore || it.pagingError != null) } }
            assertNull(transcript.state.value.pagingError)
        }
        assertFalse("All retained inherited and own messages must be pageable", transcript.state.value.hasMore)
        val messages = transcript.state.value.messages
        assertEquals(receipt.getInt("turns") * 2 + 2, messages.size)
        assertEquals(messages.size, messages.map { it.id }.distinct().size)
        for (index in 0 until receipt.getJSONArray("messages").length()) {
            val expected = receipt.getJSONArray("messages").getJSONObject(index)
            assertTrue(messages.any { it.id == expected.getString("userId") && it.text == expected.getString("userText") })
            assertTrue(messages.any { it.id == expected.getString("assistantId") && it.text == expected.getString("assistantText") })
        }
        assertTrue(messages.any { it.text == "NATIVE_TRANSCRIPT_INHERITED_USER" })
        val reading = receipt.getJSONArray("messages").getJSONObject(10).getString("userText")
        compose.onNodeWithTag("native-transcript-history").performScrollToNode(hasText(reading))
        compose.onNodeWithTag("native-transcript-history").performTouchInput {
            swipe(center, center.copy(y = center.y - 48f), durationMillis = 200)
        }
        compose.waitUntil(10_000) { transcript.state.value.readingPosition?.let { !it.followingTail && it.key.startsWith("message:") } == true }
        val anchor = requireNotNull(transcript.state.value.readingPosition)
        val anchorText = messages.single { "message:${it.id}" == anchor.key }.text
        compose.onNodeWithText(anchorText).assertIsDisplayed()
        val initialModel = transcript
        fixture.capture("native-transcript-en-reading")
        fixture.recreate()
        bindModels()
        assertSame("Activity recreation must retain the transcript reader", initialModel, transcript)
        awaitTranscript()
        compose.waitUntil(20_000) { transcript.state.value.readingPosition?.let { it.key == anchor.key && !it.followingTail } == true }
        compose.onNodeWithText(anchorText).assertIsDisplayed()
        assertEquals(messages.map { it.id }, transcript.state.value.messages.map { it.id })
        assertReadOnly("en")
        fixture.capture("native-transcript-en-recreated")

        val beforeLeave = audit()
        val historyPages = beforeLeave.getJSONArray("pages").objects().filter { it.getLong("throughSeq") >= 0 }
        assertTrue("Pagination must use the typed address and an exact existing cursor", historyPages.isNotEmpty())
        historyPages.forEach { page ->
            assertAddress(page.getJSONObject("address"))
            assertTrue(page.getLong("beforeSeq") <= page.getLong("throughSeq"))
        }
        assertTrue(beforeLeave.getJSONArray("follows").length() >= 2)
        beforeLeave.getJSONArray("follows").objects().forEach { assertAddress(it.getJSONObject("address")) }
        leaveTranscript()
        val afterLeave = awaitDetachedAudit()
        assertColdAudit(afterLeave, detached = true)
        assertTrue("Leaving the page must close the actual follow iterator", afterLeave.getInt("closedFollows") > 0)
        evidence("native-transcript-en-audit", afterLeave)
    }

    @Test fun coldSubagentTranscriptUsesSavedChineseLanguageAndDetachesWithoutCancellation() {
        startFixture()
        fixture.scenario.onActivity { settings.setLanguage("zh") }
        compose.waitUntil(20_000) { settings.state.value.let { !it.saving && it.locale == "zh" && it.error == null } }
        openSession(child)
        awaitTranscript()
        compose.waitUntil(10_000) { transcript.state.value.runtime.locale == "zh" }
        assertReadOnly("zh")
        compose.onNodeWithText(localized("zh", R.string.native_transcript_title)).assertIsDisplayed()
        fixture.capture("native-transcript-zh-opening")
        leaveTranscript()
        assertColdAudit(awaitDetachedAudit(), detached = true)
        evidence("native-transcript-zh-audit", audit())
    }

    @Test fun missingDescriptorIsRejectedAndOrdinaryForkRemainsAnOrdinaryReadTarget() {
        startFixture()
        val fork = resolve(ids.getString("ordinaryFork")).getJSONObject("session")
        assertEquals("workbench", fork.getString("owner"))
        assertFalse(fork.getBoolean("readOnly"))
        assertEquals("session", fork.getJSONObject("address").getString("kind"))
        assertEquals(ids.getString("ordinaryFork"), fork.getJSONObject("address").getString("sessionId"))
        try {
            resolve(ids.getString("missingDescriptor"))
            fail("An origin flag cannot replace a child's own descriptor")
        } catch (error: NativeApiException) { assertEquals("NATIVE_NAVIGATION_SUBAGENT_UNPROVEN", error.code) }
        openSession(ids.getString("missingDescriptor"))
        compose.waitUntil(20_000) { navigation.state.value.let { it.phase == NativeExternalNavigationPhase.FAILED && it.failure == NativeExternalNavigationFailure.UNSUPPORTED } }
        awaitTag("native-external-navigation-notice")
        compose.onNodeWithTag("native-transcript").assertDoesNotExist()
        assertNull(transcript.state.value.address)
        fixture.capture("native-transcript-missing-descriptor")
        compose.onNodeWithTag("native-external-dismiss").performClick()
        assertColdAudit(audit(), detached = true)
        evidence("native-transcript-rejected-audit", audit())
    }

    private fun startFixture() {
        assertTrue("The shared fixture must own the stopped runtime", RuntimeService.canManageHistory())
        File(fixture.home, "native-transcript-fixture-request.json").writeText(JSONObject()
            .put("version", 1).put("fixture", "native-transcript-v1").toString())
        fixture.startRuntime()
        val source = File(fixture.home, "native-transcript-fixture-receipt.json")
        compose.waitUntil(20_000) { source.isFile }
        receipt = JSONObject(source.readText())
        assertEquals("native-transcript-v1", receipt.getString("fixture"))
        assertEquals(90, receipt.getInt("turns"))
        assertTrue(receipt.getInt("inheritedEventCount") > 0)
        assertTrue(receipt.getLong("descriptorSeq") >= receipt.getLong("inheritedEventCount"))
        bindModels()
        compose.waitUntil(20_000) { settings.state.value.let { it.loaded && it.writable && !it.loading } }
    }

    private fun bindModels() {
        fixture.scenario.onActivity { activity ->
            transcript = ViewModelProvider(activity)[NativeTranscriptViewModel::class.java]
            navigation = ViewModelProvider(activity)[NativeExternalNavigationViewModel::class.java]
            settings = ViewModelProvider(activity)[NativeSettingsViewModel::class.java]
        }
    }

    private fun resolve(id: String): JSONObject = runBlocking {
        val envelope = fixture.runtimeTransport.json("/droy/native/navigation/resolve", "POST", JSONObject().put("kind", "engine-session").put("sessionId", id))
        assertTrue(envelope.getBoolean("ok")); envelope.getJSONObject("result")
    }

    private fun assertResolvedChild() {
        val resolved = resolve(child)
        assertEquals("engine-session", resolved.getString("kind"))
        val session = resolved.getJSONObject("session")
        assertEquals(child, session.getString("id")); assertEquals("subagent", session.getString("owner"))
        assertTrue(session.getBoolean("readOnly")); assertFalse(session.getBoolean("archived"))
        assertEquals(receipt.getString("workspaceId"), session.getString("workspaceId"))
        assertAddress(session.getJSONObject("address"))
    }

    private fun openSession(id: String) {
        fixture.scenario.onActivity { activity ->
            activity.startActivity(Intent(activity, NativeAppActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP).putExtra("sessionId", id))
        }
    }

    private fun awaitTranscript() {
        compose.waitUntil(25_000) { transcript.state.value.let { it.address?.parentSessionId == parent && it.address?.childSessionId == child
            && it.baselineReady && it.live && !it.loading && it.error == null && it.messages.isNotEmpty() } }
        awaitTag("native-transcript")
        compose.waitUntil(10_000) { navigation.state.value.request == null }
    }

    private fun assertReadOnly(language: String) {
        compose.onNodeWithTag("native-transcript-readonly").assertIsDisplayed().assertTextEquals(localized(language, R.string.native_transcript_read_only))
        for (tag in listOf("native-composer", "native-composer-attachments", "native-send", "native-stop", "native-decision", "native-engine-session-controls"))
            compose.onNodeWithTag(tag).assertDoesNotExist()
        assertNull(transcript.state.value.error)
    }

    private fun leaveTranscript() {
        val language = transcript.state.value.runtime.locale
        compose.onNodeWithContentDescription(localized(language, R.string.native_companion_back)).performClick()
        compose.waitUntil(10_000) { !transcript.state.value.live && !transcript.state.value.baselineReady }
        compose.onNodeWithTag("native-transcript").assertDoesNotExist()
    }

    private fun audit(): JSONObject = runBlocking {
        val envelope = fixture.runtimeTransport.json("/droy/native/fixture-transcript/audit")
        assertTrue(envelope.getBoolean("ok")); envelope.getJSONObject("result")
    }

    private fun awaitDetachedAudit(): JSONObject {
        var value = audit()
        compose.waitUntil(15_000) { value = audit(); value.getInt("activeFollows") == 0 }
        return value
    }

    private fun assertColdAudit(value: JSONObject, detached: Boolean) {
        assertEquals("History navigation must not call the local model", 0, value.getInt("modelCalls"))
        for (key in listOf("createdSessions", "agentStatuses", "cancelCalls")) assertEquals("Unexpected $key", 0, value.getJSONArray(key).length())
        if (detached) assertEquals(0, value.getInt("activeFollows"))
        assertEquals(receipt.getJSONArray("logs").toString(), value.getJSONArray("logs").toString())
        value.getJSONArray("logs").objects().forEach { log ->
            assertFalse("Cold fixture Session was published", log.getBoolean("liveSession"))
            assertFalse("Cold fixture Agent was created", log.getBoolean("liveAgent"))
        }
    }

    private fun assertAddress(address: JSONObject) {
        assertEquals("subagent", address.getString("kind"))
        assertEquals(parent, address.getString("parentSessionId"))
        assertEquals(child, address.getString("childSessionId"))
        assertEquals("unknown", address.getString("mode"))
    }

    private fun awaitTag(tag: String) {
        compose.waitUntil(10_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag(tag).assertIsDisplayed()
    }

    private fun localized(language: String, resource: Int): String {
        val configuration = Configuration(fixture.context.resources.configuration).apply { setLocale(Locale.forLanguageTag(language)) }
        return fixture.context.createConfigurationContext(configuration).getString(resource)
    }

    private fun evidence(name: String, value: JSONObject) {
        val directory = File(fixture.context.getExternalFilesDir(null), "native-isolated-evidence").apply { assertTrue(isDirectory || mkdirs()) }
        File(directory, "$name.json").writeText(JSONObject().put("receipt", receipt).put("audit", value).toString(2))
    }

    private fun JSONArray.objects(): List<JSONObject> = (0 until length()).map { getJSONObject(it) }
}
