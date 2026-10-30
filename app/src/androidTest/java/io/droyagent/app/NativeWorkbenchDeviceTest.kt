package io.droyagent.app

import android.app.Activity
import android.app.Instrumentation
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.provider.MediaStore
import android.webkit.WebView
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.droyagent.app.nativeui.data.NativeWorkbenchViewModel
import io.droyagent.app.nativeui.data.NativeApiClient
import io.droyagent.app.nativeui.data.NativeApiException
import io.droyagent.app.nativeui.data.NativeLocalTransport
import io.droyagent.app.nativeui.companion.data.NativeCompanionViewModel
import io.droyagent.app.nativeui.media.NativeMediaViewModel
import io.droyagent.app.nativeui.media.NativeSharePreviewViewModel
import io.droyagent.app.nativeui.media.NativeShareInbox
import io.droyagent.app.nativeui.tools.NativeToolsViewModel
import io.droyagent.app.nativeui.tools.NativeToolsPage
import io.droyagent.app.nativeui.memory.NativeMemoryViewModel
import io.droyagent.app.nativeui.voice.NativeVoiceViewModel
import io.droyagent.app.nativeui.advanced.NativeAdvancedViewModel
import io.droyagent.app.nativeui.engine.NativeEngineViewModel
import io.droyagent.app.nativeui.settings.NativeSettingsViewModel
import io.droyagent.app.nativeui.schedule.NativeSchedulesViewModel
import io.droyagent.app.nativeui.schedule.NativeSchedulePage
import io.droyagent.app.nativeui.schedule.NativeScheduleJson
import io.droyagent.app.nativeui.setup.NativeOnboardingViewModel
import io.droyagent.app.nativeui.permissions.NativePermissionPolicyStore
import io.droyagent.app.nativeui.permissions.NativeAuthorizationPolicy
import java.io.File
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestName
import org.junit.Assert.*
import org.junit.runner.RunWith

/** Real on-device DSH and native UI, with a local adapter and an isolated, restorable home. */
@RunWith(AndroidJUnit4::class)
class NativeWorkbenchDeviceTest {
    @get:Rule val compose = createEmptyComposeRule()
    @get:Rule val testName = TestName()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private var scenario: ActivityScenario<NativeAppActivity>? = null
    private lateinit var model: NativeWorkbenchViewModel
    private lateinit var companionModel: NativeCompanionViewModel
    private lateinit var settingsModel: NativeSettingsViewModel
    private lateinit var mediaModel: NativeMediaViewModel
    private lateinit var schedulesModel: NativeSchedulesViewModel
    private lateinit var onboardingModel: NativeOnboardingViewModel
    private var owned = false
    private lateinit var home: File
    private lateinit var backup: File
    private lateinit var marker: File
    private lateinit var preferenceBackup: File
    private var hadHome = false
    private val preferences = linkedMapOf<String, Map<String, *>>()
    private val createdMedia = mutableListOf<Uri>()

    @Before fun prepare() {
        org.junit.Assume.assumeTrue("Explicit isolated native fixture required", InstrumentationRegistry.getArguments().getString("nativeWorkbenchFixture") == "true")
        assertEquals("Native fixtures only use the development application", "io.droyagent.app.dev", context.packageName)
        assertTrue("Native fixtures require a dedicated emulator", Build.MODEL.contains("sdk_gphone") || Build.FINGERPRINT.contains("emulator"))
        assertTrue("Do not interrupt an existing runtime", RuntimeService.canManageHistory())
        marker = File(context.filesDir, "native-workbench-fixture.json")
        assertFalse("Recover the previous native fixture before running again", marker.exists())
        home = File(context.filesDir, "dsh-home")
        backup = File(context.filesDir, "native-workbench-home-${System.nanoTime()}")
        hadHome = home.exists()
        for (name in listOf("product", "installation", "native_workbench", "native_companions", "native_media", "native_setup_draft",
            "native_companion_creation", "native_share_preview", "native_share_inbox", "native_voice_input", "native_voice_capture", "native-engine-drafts", "native_workspaces", "native_standard_voice"))
            preferences[name] = HashMap(context.getSharedPreferences(name, 0).all)
        preferenceBackup = File(context.filesDir, "${backup.name}-preferences").apply { assertTrue(mkdirs()) }
        for (name in preferences.keys) {
            val original = File(context.applicationInfo.dataDir, "shared_prefs/$name.xml")
            if (original.isFile) original.copyTo(File(preferenceBackup, "$name.xml"))
            else File(preferenceBackup, "$name.absent").writeText("")
        }
        marker.writeText(JSONObject().put("backup", backup.name).put("hadHome", hadHome)
            .put("preferences", preferenceBackup.name).toString())
        if (hadHome) assertTrue("Preserve the existing home", home.renameTo(backup))
        owned = true
        val profile = File(home, "profiles/mobile").apply { assertTrue(mkdirs()) }
        val plugin = File(home, "native-fixture.mjs")
        plugin.writeText(instrumentation.context.assets.open("native-workbench-provider.mjs").bufferedReader().use { it.readText() })
        File(profile, "cordis.patch.yml").writeText(JSONArray()
            .put(JSONObject().put("id", "agent-default-model").put("config", JSONObject()
                .put("provider", "droy-native-fixture").put("model", "native-local")))
            .put(JSONObject().put("id", "locale").put("config", JSONObject().put("preference", "en")))
            .put(JSONObject().put("insert", JSONArray().put(JSONObject()
                .put("id", "native-local-fixture").put("name", plugin.absolutePath)))).toString())
        for (name in preferences.keys.filter { it.startsWith("native") }) assertTrue(context.getSharedPreferences(name, 0).edit().clear().commit())
        val freshSetup = testName.methodName == "nativeFreshWizardConfirmsRealModelAndIdentityBeforeMessages"
        val firstUse = testName.methodName.startsWith("nativeFirstUse")
        val installation = context.getSharedPreferences("installation", 0).edit().clear()
        if (freshSetup) installation.putBoolean("initializing", true) else installation.putBoolean("completed", true)
        assertTrue(installation.commit())
        assertTrue(context.getSharedPreferences("product", 0).edit().clear()
            .putString("languagePreference", "en").putBoolean(BackgroundRuntime.STOPPED, true)
            .apply { if (firstUse) putBoolean(ProductPermissions.DEFAULT_DENY, true).putBoolean(ProductPermissions.STOPPED, false) }.commit())
        if (freshSetup) SetupManager.initialize(context)
        scenario = ActivityScenario.launch(Intent(context, NativeAppActivity::class.java).putExtra("destination", "workbench"))
        scenario!!.onActivity { activity ->
            model = ViewModelProvider(activity)[NativeWorkbenchViewModel::class.java]
            companionModel = ViewModelProvider(activity)[NativeCompanionViewModel::class.java]
            settingsModel = ViewModelProvider(activity)[NativeSettingsViewModel::class.java]
            mediaModel = ViewModelProvider(activity)[NativeMediaViewModel::class.java]
            schedulesModel = ViewModelProvider(activity)[NativeSchedulesViewModel::class.java]
            onboardingModel = ViewModelProvider(activity)[NativeOnboardingViewModel::class.java]
        }
        if (testName.methodName == "nativeChatTraversesDshAndRestoresWithoutDuplicateMessages") {
            compose.waitUntil(120_000) { RuntimeService.ready && File(home, "native-fixture-ready.json").isFile }
            compose.waitUntil(20_000) { model.state.value.runtime.ready }
        } else {
            compose.waitUntil(10_000) { model.state.value.runtime.canStart && !model.state.value.runtime.active }
        }
    }

    @After fun restore() {
        if (!owned) return
        try {
            val evidence = File(context.getExternalFilesDir(null), "native-workbench-evidence").apply { mkdirs() }
            if (::model.isInitialized) {
                val state = model.state.value
                File(evidence, "native-final-state.json").writeText(JSONObject()
                    .put("running", state.running).put("sending", state.sending).put("error", state.error)
                    .put("sessionId", state.selectedSessionId)
                    .put("decisions", JSONArray(state.decisions.map { JSONObject().put("id", it.id).put("kind", it.kind)
                        .put("canApprove", it.canApprove).put("toolName", it.toolName).put("callId", it.callId) }))
                    .put("messages", JSONArray(state.messages.map { JSONObject().put("role", it.role).put("kind", it.kind)
                        .put("toolName", it.toolName).put("text", it.text.take(4000)) })).toString(2))
                capture("native-final-state")
            }
            if (::companionModel.isInitialized) {
                val state = companionModel.state.value
                File(evidence, "native-final-companions.json").writeText(JSONObject().put("selected", state.selectedCompanionId)
                    .put("error", state.error).put("phase", state.status.phase).put("stopRequested", state.stopRequested)
                    .put("messages", JSONArray(state.messages.map { JSONObject().put("id", it.id).put("role", it.role)
                        .put("type", it.type).put("text", it.text).put("status", it.status).put("clientId", it.clientId) })).toString(2))
            }
            File(evidence, "${testName.methodName}-diagnostics.json").writeText(JSONObject().apply {
                if (::settingsModel.isInitialized) settingsModel.state.value.let { state ->
                    put("settings", JSONObject().put("error", state.error).put("discovering", state.discovering)
                        .put("discoveryComplete", state.discoveryComplete).put("discoveredCount", state.discoveredModels.size))
                }
                if (::mediaModel.isInitialized) mediaModel.state.value.let { state ->
                    put("media", JSONObject().put("error", state.error?.name).put("phase", state.phase?.name).put("owner", state.ownerId))
                }
                if (::model.isInitialized) model.state.value.let { state ->
                    put("workbench", JSONObject().put("sessionId", state.selectedSessionId).put("loading", state.loading)
                        .put("hasMore", state.hasMore).put("messages", state.messages.size).put("error", state.error)
                        .put("position", state.readingPosition?.let { JSONObject().put("key", it.key).put("offset", it.offset).put("tail", it.followingTail) })
                        .put("humanIds", JSONArray(state.messages.filter { it.role == "user" }.map { JSONObject().put("id", it.id).put("text", it.text) })))
                }
                if (::schedulesModel.isInitialized) schedulesModel.state.value.let { state ->
                    put("schedules", JSONObject().put("page", state.page.name).put("error", state.error).put("catalogError", state.catalogError)
                        .put("targetsError", state.targetsError).put("entries", state.entries.size).put("saving", state.saving)
                        .put("selectedId", state.selectedKey?.id).put("editorTitle", state.editor?.title).put("editorPrompt", state.editor?.prompt))
                }
                if (::onboardingModel.isInitialized) onboardingModel.state.value.let { state ->
                    put("onboarding", JSONObject().put("step", state.route?.step).put("required", state.requiresSetup)
                        .put("error", state.error).put("localError", state.localError).put("statusError", state.statusError)
                        .put("liveFresh", state.liveFresh).put("canComplete", state.live?.canComplete).put("busy", state.busy))
                }
                File(home, "native-fixture-first-use.jsonl").takeIf { it.isFile }?.let { source ->
                    put("firstUseOutcomes", JSONArray(source.readLines().map(::JSONObject)))
                }
                put("createdMedia", JSONArray(createdMedia.map { uri -> JSONObject().put("uri", uri.toString())
                    .put("providerUid", context.packageManager.resolveContentProvider(uri.authority!!, 0)?.applicationInfo?.uid)
                    .put("readGrant", context.checkUriPermission(uri, android.os.Process.myPid(), android.os.Process.myUid(), Intent.FLAG_GRANT_READ_URI_PERMISSION)) }))
            }.toString(2))
            capture("${testName.methodName}-final")
            for (name in listOf("native-fixture-calls.jsonl", "native-fixture-status.jsonl", "native-fixture-tools.jsonl", "native-fixture-hold-aborted.json",
                "native-fixture-companion.jsonl", "native-fixture-companion-aborted.json", "native-fixture-settings.jsonl", "native-fixture-first-use.jsonl")) {
                val source = File(home, name)
                if (source.isFile) source.copyTo(File(evidence, name), overwrite = true)
            }
        } finally {
            context.startService(Intent(context, RuntimeService::class.java).setAction(RuntimeService.STOP))
            scenario?.close(); scenario = null
            val until = SystemClock.elapsedRealtime() + 30_000
            while (!RuntimeService.canManageHistory() && SystemClock.elapsedRealtime() < until) SystemClock.sleep(100)
            assertTrue("Leave fixture recovery evidence if the engine cannot stop", RuntimeService.canManageHistory())
            assertTrue("Remove only the owned fixture home", home.deleteRecursively())
            if (hadHome) assertTrue("Restore original home", backup.renameTo(home))
            preferences.forEach { (name, values) -> restorePreferences(context.getSharedPreferences(name, 0), values) }
            assertTrue(preferenceBackup.deleteRecursively())
            assertTrue(marker.delete())
            createdMedia.forEach { assertEquals("Remove only the fixture's inserted media", 1, context.contentResolver.delete(it, null, null)) }
            createdMedia.clear()
        }
    }

    @Test fun nativeChatTraversesDshAndRestoresWithoutDuplicateMessages() {
        // Foreground entry starts the engine; use the actual product tab to select this surface.
        awaitNode("native-tab-workbench")
        compose.onNodeWithTag("native-tab-workbench").assertIsDisplayed().performClick()
        awaitNode("native-workbench")
        compose.onNodeWithTag("native-workbench").assertIsDisplayed()
        compose.waitUntil(20_000) { model.state.value.runtime.ready && model.state.value.workspaces.isNotEmpty() }
        assertNotNull("The fixture starts in its default space", model.state.value.selectedWorkspaceId)
        scenario!!.onActivity { model.selectWorkspace(null) }
        scenario!!.recreate()
        scenario!!.onActivity { activity -> model = ViewModelProvider(activity)[NativeWorkbenchViewModel::class.java] }
        compose.waitUntil(20_000) { model.state.value.runtime.ready && model.state.value.workspaces.isNotEmpty() }
        assertNull("A chosen Ungrouped draft survives refresh and recreation", model.state.value.selectedWorkspaceId)
        awaitNode("native-workbench")
        scenario!!.onActivity { activity -> assertFalse("Native workbench must not contain a WebView", containsWebView(activity.window.decorView)) }
        compose.waitUntil(10_000) { model.state.value.models.any { it.id == "native-alternate" } }
        compose.onNodeWithTag("native-model").performClick()
        compose.onNodeWithText("Native alternate fixture").performClick()
        compose.waitUntil(10_000) { model.state.value.selectedModel?.id == "native-alternate" }
        compose.onNodeWithTag("native-composer").performTextInput("NATIVE_HELLO")
        compose.onNodeWithTag("native-send").performClick()
        compose.waitUntil(30_000) { model.state.value.messages.any { it.role == "assistant" && it.text == "Native DSH reply received." && !it.streaming } }
        val session = requireNotNull(model.state.value.selectedSessionId)
        assertEquals(1, model.state.value.messages.count { it.role == "user" && it.text == "NATIVE_HELLO" })
        assertEquals("native-alternate", model.state.value.selectedModel?.id)
        val catalog = runBlocking { NativeApiClient().get("models") as JSONObject }
        assertEquals("Session selection must preserve the global default", "native-local", catalog.getJSONObject("default").getString("model"))
        compose.waitUntil(10_000) { !model.state.value.running && model.state.value.messages.none { it.streaming } }
        compose.onNodeWithTag("native-stop").assertDoesNotExist()
        capture("native-dsh-reply")
        scenario!!.onActivity { model.setDraft("Retained native draft") }
        scenario!!.recreate()
        scenario!!.onActivity { activity -> model = ViewModelProvider(activity)[NativeWorkbenchViewModel::class.java] }
        compose.waitUntil(15_000) { model.state.value.messages.any { it.role == "assistant" && it.text == "Native DSH reply received." } }
        awaitNode("native-workbench")
        assertEquals(session, model.state.value.selectedSessionId)
        assertEquals("Retained native draft", model.state.value.draft)
        assertEquals(1, model.state.value.messages.count { it.role == "user" && it.text == "NATIVE_HELLO" })
        scenario!!.onActivity { model.refreshUsage() }
        compose.waitUntil(10_000) { model.state.value.usage?.totalTokens != null }
        assertEquals(38L, model.state.value.usage!!.totalTokens)
        assertEquals(10L, model.state.value.usage!!.cacheReadTokens)
        sendMarker("NATIVE_APPROVAL_ALLOW")
        compose.waitUntil(30_000) { model.state.value.decisions.any { it.kind == "approval" && it.canApprove } }
        awaitNode("native-pending-decisions")
        compose.onNodeWithTag("native-history").performScrollToIndex(0)
        compose.onNodeWithTag("native-pending-decisions").assertIsDisplayed().performClick()
        compose.onNodeWithTag("native-allow-once").assertIsEnabled()
        capture("native-dsh-approval")
        compose.onNodeWithTag("native-allow-once").performClick()
        awaitReply("Native approval: allowed-once.")
        sendMarker("NATIVE_APPROVAL_REJECT")
        compose.waitUntil(30_000) { model.state.value.decisions.any { it.kind == "approval" } }
        awaitNode("native-pending-decisions")
        compose.onNodeWithTag("native-pending-decisions").assertIsDisplayed().performClick()
        compose.onNodeWithTag("native-reject").performClick()
        awaitReply("Native approval: rejected.")
        sendMarker("NATIVE_QUESTION")
        compose.waitUntil(30_000) { model.state.value.decisions.any { it.kind == "question" } }
        awaitNode("native-pending-decisions")
        compose.onNodeWithTag("native-pending-decisions").assertIsDisplayed().performClick()
        compose.onNodeWithText("Alpha").performClick()
        capture("native-dsh-question")
        compose.onNodeWithTag("native-submit-answer").assertIsEnabled().performClick()
        awaitReply("Native question: Alpha.")
        val answeredCount = model.state.value.messages.count { it.role == "assistant" && it.text == "Native question: Alpha." }
        sendMarker("NATIVE_QUESTION_CANCEL")
        compose.waitUntil(30_000) { model.state.value.decisions.any { it.kind == "question" } }
        val withdrawn = model.state.value.decisions.first { it.kind == "question" }
        compose.onNodeWithTag("native-stop").performClick()
        compose.waitUntil(20_000) { !model.state.value.running && model.state.value.decisions.none { it.id == withdrawn.id } }
        val late = runBlocking {
            try {
                NativeApiClient().answerDecision(withdrawn.id, JSONObject().put("sessionId", session)
                    .put("answers", JSONArray().put(JSONObject().put("id", "choice").put("selected", JSONArray().put("Alpha")))))
                null
            } catch (error: NativeApiException) { error.code }
        }
        assertEquals("A withdrawn question cannot be answered", "NATIVE_DECISION_ENDED", late)
        assertEquals(answeredCount, model.state.value.messages.count { it.role == "assistant" && it.text == "Native question: Alpha." })
        scenario!!.onActivity { model.setDraft("NATIVE_HOLD") }
        compose.onNodeWithTag("native-send").performClick()
        compose.waitUntil(20_000) { model.state.value.messages.any { it.streaming && it.text.contains("Local hold started") } }
        compose.onNodeWithTag("native-stop").performClick()
        val aborted = File(home, "native-fixture-hold-aborted.json")
        compose.waitUntil(20_000) { aborted.isFile }
        val abort = JSONObject(aborted.readText())
        assertEquals(session, abort.getString("sessionId"))
        compose.waitUntil(20_000) {
            val status = File(home, "native-fixture-status.jsonl")
            status.isFile && status.readLines().map(::JSONObject).any {
                it.optString("sessionId") == session && it.optString("status") == "idle" && it.getLong("time") >= abort.getLong("time")
            }
        }
        compose.waitUntil(20_000) { !model.state.value.running && !model.state.value.sending && model.state.value.messages.none { it.streaming } }
        compose.onNodeWithTag("native-stop").assertDoesNotExist()
        val calls = File(home, "native-fixture-calls.jsonl").readLines().map(::JSONObject)
        assertEquals("Recreation did not submit another request", 1, calls.count { !it.optBoolean("title") && it.optBoolean("hello") && it.optString("sessionId") == session })
        assertEquals("native-alternate", calls.first { !it.optBoolean("title") && it.optBoolean("hello") }.getString("model"))
        capture("native-dsh-stopped")
    }

    @Test fun nativeMessagesKeepCompanionIdentityDraftsProfilesAndStopConfirmation() {
        compose.onNodeWithTag("native-start-runtime").performClick()
        compose.waitUntil(120_000) { RuntimeService.ready && File(home, "native-fixture-ready.json").isFile }
        compose.waitUntil(20_000) { companionModel.state.value.companions.any { it.templateId == "kaka" } }
        val kaka = companionModel.state.value.companions.first { it.templateId == "kaka" }.id
        compose.onNodeWithTag("native-tab-messages").performClick()
        awaitNode("native-companion-conversation:$kaka")
        compose.onNodeWithTag("native-companion-conversation:$kaka").performClick()
        awaitNode("native-companion-composer")
        compose.onNodeWithTag("native-root-navigation").assertDoesNotExist()
        compose.onNodeWithTag("native-companion-composer").performTextInput("NATIVE_COMPANION_HELLO")
        compose.onNodeWithTag("native-companion-send").performClick()
        compose.waitUntil(30_000) { companionModel.state.value.messages.any { it.role == "assistant" && it.text == "Native companion reply received." } }
        compose.waitUntil(10_000) { companionModel.state.value.status.chatExecution?.quiescent == true }
        val human = companionModel.state.value.messages.single { it.role == "user" && it.text == "NATIVE_COMPANION_HELLO" }
        assertNotNull(human.clientId)
        capture("native-companion-reply")
        scenario!!.onActivity { companionModel.setDraft("Retained companion draft") }
        scenario!!.recreate()
        scenario!!.onActivity { activity ->
            companionModel = ViewModelProvider(activity)[NativeCompanionViewModel::class.java]
            model = ViewModelProvider(activity)[NativeWorkbenchViewModel::class.java]
            settingsModel = ViewModelProvider(activity)[NativeSettingsViewModel::class.java]
        }
        awaitNode("native-companion-composer")
        compose.onNodeWithTag("native-companion-composer").assertTextContains("Retained companion draft")
        assertEquals(kaka, companionModel.state.value.selectedCompanionId)
        assertEquals(1, companionModel.state.value.messages.count { it.clientId == human.clientId })
        compose.onNodeWithTag("native-open-companion-profile").performClick()
        awaitNode("native-companion-name")
        compose.onNodeWithTag("native-companion-name").performTextReplacement("Native Kaka")
        compose.onNodeWithTag("native-companion-profile-save").performClick()
        compose.waitUntil(10_000) { companionModel.state.value.selectedCompanion?.name == "Native Kaka" && !companionModel.state.value.profileSaving }
        assertTrue(companionModel.state.value.selectedCompanion!!.modelInherited)
        capture("native-companion-profile")
        compose.onNodeWithContentDescription(context.getString(R.string.native_companion_back)).performClick()
        awaitNode("native-companion-composer")
        assertEquals("Retained companion draft", companionModel.state.value.draft.text)
        scenario!!.onActivity { companionModel.setDraft("NATIVE_COMPANION_HOLD") }
        compose.onNodeWithTag("native-companion-send").performClick()
        val calls = File(home, "native-fixture-companion.jsonl")
        compose.waitUntil(30_000) { calls.isFile && calls.readLines().map(::JSONObject).any { it.optString("kind") == "replyer" && it.optBoolean("hold") } }
        awaitNode("native-companion-stop")
        compose.onNodeWithTag("native-companion-stop").performClick()
        compose.waitUntil(20_000) { File(home, "native-fixture-companion-aborted.json").isFile }
        compose.waitUntil(20_000) {
            companionModel.state.value.status.chatExecution?.let { it.cancelRequested && it.quiescent } == true && !companionModel.state.value.stopRequested
        }
        capture("native-companion-stopped")
        compose.onNodeWithContentDescription(context.getString(R.string.native_companion_back)).performClick()
        awaitNode("native-root-navigation")
        compose.onNodeWithTag("native-companion-conversation:$kaka").performTouchInput { longClick() }
        compose.onNodeWithTag("native-companion-pin").performClick()
        compose.waitUntil(10_000) { companionModel.state.value.companions.first { it.id == kaka }.pinned }
        compose.onNodeWithTag("native-tab-workbench").performClick()
        awaitNode("native-composer")
        scenario!!.onActivity { model.setDraft("Retained workbench draft") }
        compose.onNodeWithTag("native-tab-messages").performClick()
        awaitNode("native-companion-messages")
        compose.onNodeWithTag("native-tab-workbench").performClick()
        awaitNode("native-composer")
        compose.onNodeWithTag("native-composer").assertTextContains("Retained workbench draft")
        scenario!!.onActivity { activity -> assertFalse(containsWebView(activity.window.decorView)) }
    }

    @Test fun nativeSettingsUseSharedLanguageProvidersCredentialsAndModelVerification() {
        compose.onNodeWithTag("native-start-runtime").performClick()
        compose.waitUntil(120_000) { RuntimeService.ready && File(home, "native-fixture-ready.json").isFile }
        compose.waitUntil(20_000) { settingsModel.state.value.loaded && settingsModel.state.value.writable }
        compose.onNodeWithTag("native-tab-settings").performClick()
        awaitNode("native-settings-general")
        compose.onNodeWithTag("native-settings-general").performClick()
        compose.onNodeWithTag("native-settings-language").performClick()
        compose.onNodeWithTag("native-settings-language:zh").performClick()
        compose.waitUntil(10_000) { settingsModel.state.value.locale == "zh" && model.state.value.runtime.locale == "zh" }
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("native-settings-language:zh").fetchSemanticsNodes(false).isEmpty() }
        compose.onNodeWithTag("native-settings-language").assert(hasText("中文"))
        compose.onNodeWithTag("native-root-navigation").assertDoesNotExist()
        capture("native-settings-zh")
        compose.onNodeWithTag("native-settings-language").performClick()
        compose.onNodeWithTag("native-settings-language:en").performClick()
        compose.waitUntil(10_000) { settingsModel.state.value.locale == "en" && model.state.value.runtime.locale == "en" }
        compose.onNodeWithTag("native-product-back").performClick()
        awaitNode("native-settings-models")
        compose.onNodeWithTag("native-settings-models").performClick()
        compose.onNodeWithTag("native-settings-default-model").performClick()
        compose.onNodeWithTag("native-settings-default:droy-native-fixture:native-alternate").performClick()
        compose.waitUntil(10_000) { settingsModel.state.value.defaultModel?.id == "native-alternate" && !settingsModel.state.value.saving }
        compose.waitUntil(10_000) { model.state.value.selectedModel?.id == "native-alternate" }
        val callsBeforeTest = File(home, "native-fixture-calls.jsonl").let { if (it.isFile) it.readLines().size else 0 }
        compose.onNodeWithTag("native-settings-test-model").performClick()
        compose.onNodeWithTag("native-settings-confirm-test").assertIsDisplayed()
        assertFalse(settingsModel.state.value.testing)
        compose.onNodeWithTag("native-settings-cancel-test").performClick()
        assertEquals(callsBeforeTest, File(home, "native-fixture-calls.jsonl").let { if (it.isFile) it.readLines().size else 0 })
        compose.onNodeWithTag("native-settings-test-model").performClick()
        compose.onNodeWithTag("native-settings-confirm-test").performClick()
        compose.waitUntil(30_000) { !settingsModel.state.value.testing && settingsModel.state.value.verification?.passed == true }
        assertEquals("native-alternate", settingsModel.state.value.verification!!.model)
        assertTrue(settingsModel.state.value.verification!!.toolsPassed)
        capture("native-settings-verified")
        compose.onNodeWithTag("native-settings-add-provider").performClick()
        compose.onNodeWithTag("native-settings-custom-provider").performClick()
        awaitNode("native-settings-provider-editor")
        compose.onNodeWithTag("native-settings-provider-name").performTextReplacement("Native API")
        val upstreamPort = JSONObject(File(home, "native-fixture-api.json").readText()).getInt("port")
        compose.onNodeWithTag("native-settings-provider-base-url").performTextReplacement("http://127.0.0.1:$upstreamPort/v1")
        compose.onNodeWithTag("native-settings-provider-editor").performScrollToNode(hasTestTag("native-settings-provider-key"))
        compose.onNodeWithTag("native-settings-provider-key").performTextInput("fictional-settings-key")
        compose.onNodeWithTag("native-settings-provider-editor").performScrollToNode(hasTestTag("native-settings-fetch-models"))
        compose.onNodeWithTag("native-settings-fetch-models").performClick()
        compose.waitUntil(30_000) { settingsModel.state.value.discoveryComplete || settingsModel.state.value.error != null }
        assertNull(settingsModel.state.value.error)
        awaitNode("native-settings-discovered:fixture-upstream")
        compose.onNodeWithTag("native-settings-discovered:fixture-upstream").performClick()
        compose.onNodeWithTag("native-settings-add-discovered").performClick()
        compose.onNodeWithTag("native-settings-manual-model").performClick()
        compose.onNodeWithTag("native-settings-manual-model-id").performTextInput("fixture-manual")
        compose.onNodeWithTag("native-settings-save-model").performClick()
        compose.onNodeWithTag("native-settings-save-provider").performClick()
        compose.waitUntil(20_000) { !settingsModel.state.value.saving && settingsModel.state.value.editor == null }
        assertNull(settingsModel.state.value.error)
        val created = settingsModel.state.value.providers.single { it.name == "Native API" }
        assertTrue(created.credentialConfigured == true)
        assertEquals(2, created.modelCount)
        val activity = File(home, "native-fixture-settings.jsonl")
        val first = activity.readLines().map(::JSONObject)
        assertTrue(first.any { it.optString("kind") == "discovery" && it.optBoolean("authenticated") })
        assertEquals(1, first.count { it.optString("kind") == "credential-set" })
        compose.onNodeWithTag("native-settings-providers").performScrollToNode(hasTestTag("native-settings-provider:${created.id}"))
        compose.onNodeWithTag("native-settings-provider:${created.id}").performClick()
        awaitNode("native-settings-provider-name")
        compose.onNodeWithTag("native-settings-provider-name").performTextReplacement("Native API renamed")
        compose.onNodeWithTag("native-settings-save-provider").performClick()
        compose.waitUntil(20_000) { !settingsModel.state.value.saving && settingsModel.state.value.editor == null }
        assertNull(settingsModel.state.value.error)
        assertTrue(settingsModel.state.value.providers.single { it.id == created.id }.credentialConfigured == true)
        assertEquals("Blank key input preserves the configured credential", 1,
            activity.readLines().map(::JSONObject).count { it.optString("kind") == "credential-set" })
        compose.waitUntil(10_000) { !imeVisible() }
        capture("native-settings-providers")
    }

    @Test fun nativeWorkbenchPagesRealHistoryAndRestoresItsReadingPosition() {
        compose.onNodeWithTag("native-start-runtime").performClick()
        compose.waitUntil(120_000) { RuntimeService.ready && File(home, "native-fixture-ready.json").isFile }
        compose.waitUntil(20_000) { model.state.value.runtime.ready && model.state.value.workspaces.isNotEmpty() }
        val turns = 40
        fun marker(index: Int) = "NATIVE_HISTORY_${index.toString().padStart(2, '0')}"
        for (index in 0 until turns) {
            scenario!!.onActivity { model.setDraft(marker(index)); model.send() }
            compose.waitUntil(20_000) {
                val state = model.state.value
                !state.running && !state.sending && state.messages.any { it.role == "user" && it.text == marker(index) } &&
                    state.messages.count { it.role == "assistant" && it.text == "Native DSH reply received." && !it.streaming } == index + 1
            }
            assertNull(model.state.value.error)
        }
        val sessionId = requireNotNull(model.state.value.selectedSessionId)
        scenario!!.onActivity { model.stopObserving(); model.startObserving() }
        compose.waitUntil(20_000) { model.state.value.hasMore && model.state.value.messages.none { it.role == "user" && it.text == marker(0) } }
        assertNull(model.state.value.error)
        var pages = 0
        while (model.state.value.hasMore && pages++ < 4) {
            compose.onNodeWithTag("native-history").performScrollToIndex(0)
            awaitNode("native-load-older")
            val before = model.state.value.messages.size
            compose.onNodeWithTag("native-load-older").performClick()
            compose.waitUntil(15_000) { model.state.value.messages.size > before || !model.state.value.hasMore }
        }
        assertFalse("All fixture history is reachable through pagination", model.state.value.hasMore)
        val humans = model.state.value.messages.filter { it.role == "user" && it.text.startsWith("NATIVE_HISTORY_") }
        assertEquals(turns, humans.size)
        assertEquals(turns, humans.map { it.id }.distinct().size)
        compose.onNodeWithTag("native-history").performScrollToNode(hasText(marker(5)))
        compose.onNodeWithTag("native-history").performTouchInput { swipe(center, center.copy(y = center.y - 48f), durationMillis = 200) }
        compose.waitUntil(10_000) { model.state.value.readingPosition?.followingTail == false }
        compose.onNodeWithText(marker(5)).assertIsDisplayed()
        capture("native-history-reading")
        compose.onNodeWithTag("native-tab-messages").performClick()
        awaitNode("native-companion-messages")
        compose.onNodeWithTag("native-tab-workbench").performClick()
        awaitNode("native-history")
        compose.onNodeWithText(marker(5)).assertIsDisplayed()
        compose.onNodeWithTag("native-new-session").performClick()
        compose.onNodeWithText(context.getString(R.string.native_spaces_chats)).performClick()
        awaitNode("native-spaces-chats")
        compose.onNodeWithTag("native-spaces-chats").performScrollToNode(hasTestTag("native-space-session:$sessionId"))
        compose.onNodeWithTag("native-space-session:$sessionId").performClick()
        compose.waitUntil(20_000) { model.state.value.messages.any { it.role == "user" && it.text == marker(0) } }
        awaitNode("native-history")
        compose.onNodeWithText(marker(5)).assertIsDisplayed()
        scenario!!.recreate()
        scenario!!.onActivity { activity -> model = ViewModelProvider(activity)[NativeWorkbenchViewModel::class.java] }
        compose.waitUntil(20_000) { model.state.value.messages.any { it.role == "user" && it.text == marker(0) } }
        awaitNode("native-history")
        compose.onNodeWithText(marker(5)).assertIsDisplayed()
        assertEquals(turns, model.state.value.messages.count { it.role == "user" && it.text.startsWith("NATIVE_HISTORY_") })
        capture("native-history-restored")
    }

    @Test fun nativeFirstUseAllowsOnlyTheRequestedCapabilityAndNeverReplaysIt() {
        startFirstUseFixture()
        sendMarker("NATIVE_FIRST_USE_APPS_ALLOWED")
        val policy = NativePermissionPolicyStore(ProductPermissions.preferences(context))
        compose.waitUntil(20_000) { policy.pendingRequests().isNotEmpty() }
        val pending = policy.pendingRequests().single()
        assertEquals(setOf("allowApps"), pending.capabilityKeys)
        assertTrue(pending.permissionIds.isEmpty())
        assertFalse(ProductPermissions.allowed(ProductPermissions.preferences(context), "allowApps"))
        awaitNode("native-permissions-approve:${pending.id}")
        scenario!!.recreate()
        awaitNode("native-permissions-approve:${pending.id}")
        assertEquals(pending.id, policy.pendingRequests().single().id)
        compose.onNodeWithTag("native-permission-request").assertIsDisplayed()
        capture("native-first-use-before-approval")
        compose.onNodeWithTag("native-permissions-approve:${pending.id}").assertIsEnabled().performClick()
        compose.waitUntil(15_000) { policy.pendingRequests().isEmpty() }
        awaitReply("Native first-use allowed.")
        val preferences = ProductPermissions.preferences(context)
        assertTrue(ProductPermissions.allowed(preferences, "allowApps"))
        for (key in SetupManager.capabilityKeys(context).filterNot { it == "allowApps" })
            assertFalse("Only the requested capability is enabled: $key", ProductPermissions.allowed(preferences, key))
        awaitNode("native-workbench")
        compose.onNodeWithTag("native-tab-settings").performClick()
        awaitNode("native-settings-permissions")
        compose.onNodeWithTag("native-settings-permissions").performClick()
        awaitNode("native-permissions-content")
        compose.onNodeWithTag("native-permissions-content").performScrollToNode(hasTestTag("native-permissions-product:allowApps"))
        compose.onNodeWithTag("native-permissions-product:allowApps").performClick()
        assertFalse(ProductPermissions.allowed(preferences, "allowApps"))
        assertTrue("Turning off the last capability pauses phone operations", ProductPermissions.stopped(context))
        compose.onNodeWithTag("native-permissions-content").performScrollToNode(hasTestTag("native-permissions-operations"))
        val paused = hasTestTag("native-permissions-operations").and(SemanticsMatcher.expectValue(
            androidx.compose.ui.semantics.SemanticsProperties.ToggleableState, androidx.compose.ui.state.ToggleableState.Off))
        compose.waitUntil(5000) { compose.onAllNodes(paused).fetchSemanticsNodes(false).isNotEmpty() }
        compose.onNode(paused).performClick()
        assertFalse("Resume operations explicitly before testing the prior-request guard", ProductPermissions.stopped(context))
        compose.onNodeWithTag("native-permissions-back").performClick()
        awaitNode("native-tab-workbench")
        compose.onNodeWithTag("native-tab-workbench").performClick()
        awaitNode("native-workbench")
        sendMarker("NATIVE_FIRST_USE_APPS_REVOKED_REFUSED")
        awaitReply("Native first-use refused.")
        compose.waitUntil(5000) { compose.onAllNodesWithTag("native-permission-request").fetchSemanticsNodes(false).isEmpty() }
        assertTrue("Revocation must not automatically ask again", policy.pendingRequests().isEmpty())
        val outcomes = File(home, "native-fixture-first-use.jsonl").readLines().map(::JSONObject)
        assertEquals(2, outcomes.size)
        assertFalse(outcomes[0].getBoolean("refused"))
        assertTrue(outcomes[1].getBoolean("refused"))
        assertTrue(outcomes[1].getString("result").contains("authorization_previously_requested"))
        capture("native-first-use-revoked")
    }

    @Test fun nativeFirstUseSkipEndsTheOriginalCallWithoutAnotherAutomaticPrompt() {
        startFirstUseFixture()
        sendMarker("NATIVE_FIRST_USE_APPS_SKIPPED_REFUSED")
        val policy = NativePermissionPolicyStore(ProductPermissions.preferences(context))
        compose.waitUntil(20_000) { policy.pendingRequests().isNotEmpty() }
        val pending = policy.pendingRequests().single()
        awaitNode("native-permissions-decline:${pending.id}")
        compose.onNodeWithTag("native-permissions-decline:${pending.id}").performClick()
        awaitReply("Native first-use refused.")
        assertTrue(policy.pendingRequests().isEmpty())
        assertFalse(ProductPermissions.allowed(ProductPermissions.preferences(context), "allowApps"))
        awaitNode("native-workbench")
        sendMarker("NATIVE_FIRST_USE_APPS_AGAIN_REFUSED")
        compose.waitUntil(20_000) { model.state.value.messages.count { it.role == "assistant" && it.text == "Native first-use refused." && !it.streaming } == 2 }
        compose.waitUntil(10_000) { !model.state.value.running }
        assertTrue(policy.pendingRequests().isEmpty())
        compose.onNodeWithTag("native-workbench").assertIsDisplayed()
        val outcomes = File(home, "native-fixture-first-use.jsonl").readLines().map(::JSONObject)
        assertEquals(2, outcomes.size)
        assertTrue(outcomes.all { it.getBoolean("refused") })
        val skipped = JSONObject(outcomes[0].getString("result").substringAfter('\n'))
        assertFalse("Skipping cannot dispatch a phone action", skipped.getBoolean("dispatched"))
        assertFalse("No uncertain action may be hidden by the skip receipt", skipped.getBoolean("outcomeUnknown"))
        assertTrue(outcomes[1].getString("result").contains("authorization_previously_requested"))
        capture("native-first-use-skipped")
    }

    @Test fun nativeFreshWizardConfirmsRealModelAndIdentityBeforeMessages() {
        val systemPermissions = listOf(android.Manifest.permission.RECORD_AUDIO, android.Manifest.permission.CAMERA,
            android.Manifest.permission.READ_CONTACTS, android.Manifest.permission.ACCESS_FINE_LOCATION,
            android.Manifest.permission.POST_NOTIFICATIONS)
        val grants = systemPermissions.associateWith(context::checkSelfPermission)
        awaitNode("native-onboarding")
        compose.onNodeWithTag("native-root-navigation").assertDoesNotExist()
        compose.waitUntil(10_000) { onboardingModel.state.value.route?.step == 1 }
        compose.onNodeWithTag("native-setup-next").performClick()
        compose.waitUntil(10_000) { onboardingModel.state.value.route?.step == 2 }
        compose.onNodeWithTag("native-setup-start-runtime").performClick()
        compose.waitUntil(120_000) { RuntimeService.ready && File(home, "native-fixture-ready.json").isFile }
        compose.waitUntil(15_000) { onboardingModel.state.value.route?.environment?.ready == true }
        compose.onNodeWithTag("native-setup-next").assertIsEnabled().performClick()
        compose.waitUntil(15_000) { onboardingModel.state.value.route?.step == 3 && settingsModel.state.value.loaded && settingsModel.state.value.runtime.ready }
        awaitNode("native-settings-test-model")
        compose.onNodeWithTag("native-setup-next").assertIsNotEnabled()
        compose.onNodeWithTag("native-settings-test-model").assertIsEnabled().performClick()
        compose.onNodeWithTag("native-settings-confirm-test").performClick()
        compose.waitUntil(30_000) { !settingsModel.state.value.testing && settingsModel.state.value.verification?.toolsPassed == true }
        compose.waitUntil(15_000) { onboardingModel.state.value.liveFresh && onboardingModel.state.value.live?.passed("modelTools") == true }
        capture("native-setup-model-verified")
        compose.onNodeWithTag("native-setup-next").assertIsEnabled().performClick()
        compose.waitUntil(15_000) { onboardingModel.state.value.route?.step == 4 }
        awaitNode("native-setup-name")
        compose.onNodeWithTag("native-setup-name").performTextReplacement("Native setup user")
        compose.onNodeWithTag("native-setup-bio").performScrollTo().performTextReplacement("Synthetic biography\nFor setup review")
        val avatar = insertMedia("native-setup-avatar.png", "image/png", images = true) { output ->
            val bitmap = Bitmap.createBitmap(48, 48, Bitmap.Config.ARGB_8888)
            try { bitmap.eraseColor(android.graphics.Color.rgb(116, 96, 168)); bitmap.compress(Bitmap.CompressFormat.PNG, 100, output) }
            finally { bitmap.recycle() }
        }
        pickerResult(Activity.RESULT_OK, avatar) { compose.onNodeWithTag("native-setup-avatar").performClick() }
        compose.waitUntil(15_000) { onboardingModel.state.value.draft.avatarUrl?.startsWith("/droy/companions/user-profile/attachments/") == true }
        val selectedAvatar = onboardingModel.state.value.draft.avatarUrl
        scenario!!.recreate()
        awaitNode("native-setup-name")
        assertEquals(4, onboardingModel.state.value.route!!.step)
        assertEquals("Native setup user", onboardingModel.state.value.draft.name)
        assertEquals("Synthetic biography\nFor setup review", onboardingModel.state.value.draft.bio)
        assertEquals(selectedAvatar, onboardingModel.state.value.draft.avatarUrl)
        capture("native-setup-identity-restored")
        compose.onNodeWithTag("native-setup-next").assertIsEnabled().performClick()
        compose.waitUntil(20_000) { onboardingModel.state.value.route?.step == 5 }
        assertEquals("Native setup user", companionModel.state.value.userProfile.name)
        assertEquals("Synthetic biography\nFor setup review", companionModel.state.value.userProfile.bio)
        assertEquals(selectedAvatar, companionModel.state.value.userProfile.avatarUrl)
        compose.onNodeWithTag("native-permissions-first-use").assertIsEnabled().performClick()
        compose.waitUntil(10_000) { onboardingModel.state.value.route?.permissionPolicy == NativeAuthorizationPolicy.ON_FIRST_USE }
        assertFalse(ProductPermissions.stopped(context))
        for (key in SetupManager.capabilityKeys(context)) assertFalse("First-use strategy is not consent: $key", ProductPermissions.allowed(ProductPermissions.preferences(context), key))
        assertEquals(grants, systemPermissions.associateWith(context::checkSelfPermission))
        capture("native-setup-first-use-policy")
        assertEquals(5, onboardingModel.state.value.route!!.step)
        compose.onNodeWithTag("native-setup-finish").assertIsEnabled().performClick()
        awaitNode("native-companion-messages")
        compose.onNodeWithTag("native-onboarding").assertDoesNotExist()
        assertTrue(context.getSharedPreferences("installation", 0).getBoolean("completed", false))
        assertFalse(context.getSharedPreferences("native_setup_draft", 0).contains("identity"))
        scenario!!.recreate()
        awaitNode("native-companion-messages")
        compose.onNodeWithTag("native-tab-messages").assertIsSelected()
        compose.onNodeWithTag("native-onboarding").assertDoesNotExist()
        assertEquals("Native setup user", companionModel.state.value.userProfile.name)
        assertEquals("Synthetic biography\nFor setup review", companionModel.state.value.userProfile.bio)
        assertEquals(listOf("kaka"), companionModel.state.value.companions.map { it.templateId })
        assertTrue(companionModel.state.value.companions.single().id.startsWith("companion-"))
        capture("native-setup-completed-messages")
    }

    @Test fun nativeSchedulesKeepBindingsResolveConflictsAndReadRealDeliveries() {
        compose.onNodeWithTag("native-start-runtime").performClick()
        compose.waitUntil(120_000) { RuntimeService.ready && File(home, "native-fixture-ready.json").isFile }
        compose.waitUntil(20_000) { model.state.value.runtime.ready && model.state.value.workspaces.isNotEmpty() }
        scenario!!.onActivity { model.setDraft("NATIVE_SCHEDULE_CONTEXT"); model.send() }
        awaitReply("Native DSH reply received.")
        val session = requireNotNull(model.state.value.selectedSessionId)
        compose.onNodeWithTag("native-open-schedules").performClick()
        awaitNode("native-schedules")
        compose.waitUntil(20_000) { schedulesModel.state.value.catalogLoaded && schedulesModel.state.value.targetsLoaded }
        assertNull(schedulesModel.state.value.catalogError)
        assertNull(schedulesModel.state.value.targetsError)
        assertEquals(listOf(session), schedulesModel.state.value.targets.map { it.id })
        compose.onNodeWithTag("native-root-navigation").assertDoesNotExist()
        compose.onNodeWithTag("native-schedule-create").assertIsEnabled().performClick()
        awaitNode("native-schedule-name")
        compose.onNodeWithTag("native-schedule-name").performTextReplacement("Native reminder")
        compose.onNodeWithTag("native-schedule-prompt").performTextReplacement("NATIVE_SCHEDULE_ORIGINAL")
        compose.onNodeWithTag("native-schedule-save").performClick()
        compose.waitUntil(20_000) { !schedulesModel.state.value.saving && schedulesModel.state.value.page == NativeSchedulePage.DETAIL }
        assertNull(schedulesModel.state.value.error)
        val created = requireNotNull(schedulesModel.state.value.selected)
        assertEquals(session, created.sessionId)
        assertEquals("NATIVE_SCHEDULE_ORIGINAL", created.record.prompt)
        capture("native-schedule-created")
        compose.onNodeWithTag("native-schedule-edit").performClick()
        awaitNode("native-schedule-prompt")
        compose.onNodeWithTag("native-schedule-prompt").performTextReplacement("NATIVE_SCHEDULE_REVISED")
        val external = runBlocking { NativeLocalTransport().json("/droy/native/schedules/update", "POST", JSONObject()
            .put("sessionId", session).put("id", created.record.id).put("expected", NativeScheduleJson.recordValue(created.record))
            .put("title", "Native renamed elsewhere")) }
        assertTrue(external.getJSONObject("result").getBoolean("updated"))
        compose.onNodeWithTag("native-schedule-save").performClick()
        compose.waitUntil(15_000) { !schedulesModel.state.value.saving && schedulesModel.state.value.error == "schedule_conflict" }
        assertEquals("NATIVE_SCHEDULE_REVISED", schedulesModel.state.value.editor!!.prompt)
        compose.onNodeWithText(context.getString(R.string.native_schedule_reload_editor)).performClick()
        compose.waitUntil(15_000) { schedulesModel.state.value.error == null && schedulesModel.state.value.editor?.title == "Native renamed elsewhere" }
        assertEquals("NATIVE_SCHEDULE_REVISED", schedulesModel.state.value.editor!!.prompt)
        compose.onNodeWithTag("native-schedule-save").performClick()
        compose.waitUntil(20_000) { !schedulesModel.state.value.saving && schedulesModel.state.value.page == NativeSchedulePage.DETAIL }
        assertNull(schedulesModel.state.value.error)
        assertEquals(created.record.scheduledAt, schedulesModel.state.value.selected!!.record.scheduledAt)
        assertEquals("Native renamed elsewhere", schedulesModel.state.value.selected!!.record.title)
        assertEquals("NATIVE_SCHEDULE_REVISED", schedulesModel.state.value.selected!!.record.prompt)
        compose.waitUntil(10_000) { !imeVisible() }
        capture("native-schedule-conflict-resolved")
        compose.onNodeWithTag("native-schedule-delete").performClick()
        compose.onNodeWithTag("native-schedule-confirm-delete").performClick()
        compose.waitUntil(15_000) { !schedulesModel.state.value.saving && schedulesModel.state.value.entries.none { it.key == created.key } }
        assertNull(schedulesModel.state.value.error)
        val delivery = runBlocking { NativeLocalTransport().json("/droy/native/schedules/create", "POST", JSONObject()
            .put("sessionId", session).put("request", JSONObject().put("title", "Native delivered reminder")
                .put("prompt", "NATIVE_SCHEDULE_DELIVERY").put("after_seconds", 1))) }.getJSONObject("result")
        val deliveredId = delivery.getString("id")
        scenario!!.onActivity { schedulesModel.refresh() }
        compose.waitUntil(25_000) { schedulesModel.state.value.entries.any { it.record.id == deliveredId && it.lastDelivery != null } }
        compose.onNodeWithTag("native-schedule:$deliveredId").performClick()
        compose.onNodeWithTag("native-schedule-history").performClick()
        compose.waitUntil(15_000) { schedulesModel.state.value.history.loaded && schedulesModel.state.value.history.records.isNotEmpty() }
        assertNull(schedulesModel.state.value.history.error)
        assertEquals("NATIVE_SCHEDULE_DELIVERY", schedulesModel.state.value.history.records.single().prompt)
        capture("native-schedule-delivery-history")
        compose.onNodeWithContentDescription(context.getString(R.string.native_schedule_back)).performClick()
        compose.onNodeWithTag("native-schedule-open-session").performClick()
        awaitNode("native-workbench")
        compose.onNodeWithTag("native-root-navigation").assertDoesNotExist()
        assertEquals(session, model.state.value.selectedSessionId)
        compose.onNodeWithContentDescription(context.getString(R.string.native_companion_back)).performClick()
        awaitNode("native-schedules")
        assertEquals(deliveredId, schedulesModel.state.value.selectedKey?.id)
    }

    @Test fun nativeMediaKeepsOwnerDraftAndAvatarPreviewAcrossRecreation() {
        compose.onNodeWithTag("native-start-runtime").performClick()
        compose.waitUntil(120_000) { RuntimeService.ready && File(home, "native-fixture-ready.json").isFile }
        compose.waitUntil(20_000) { companionModel.state.value.companions.any { it.templateId == "kaka" } }
        val kaka = companionModel.state.value.companions.first { it.templateId == "kaka" }.id
        compose.onNodeWithTag("native-tab-messages").performClick()
        awaitNode("native-companion-conversation:$kaka")
        compose.onNodeWithTag("native-companion-conversation:$kaka").performClick()
        awaitNode("native-companion-composer")

        val document = insertMedia("native-fixture.txt", "text/plain", images = false) {
            it.write("Synthetic native attachment fixture".toByteArray())
        }
        pickerResult(Activity.RESULT_OK, document) {
            compose.onNodeWithTag("native-companion-composer-options").performClick()
            compose.onNodeWithTag("native-companion-choose-attachments").performClick()
        }
        compose.waitUntil(15_000) { companionModel.state.value.draft.localAttachments.size == 1 }
        assertNull(mediaModel.state.value.error)
        val attachment = companionModel.state.value.draft.localAttachments.single()
        assertEquals("native-fixture.txt", attachment.name)
        assertTrue("No server reference exists before Send", companionModel.state.value.draft.attachments.isEmpty())
        assertTrue("Picking a file must not send a chat message", companionModel.state.value.messages.none { it.role == "user" })
        assertTrue(companionModel.state.value.draftsByCompanion.filterKeys { it != kaka }.values.all { it.attachments.isEmpty() && it.localAttachments.isEmpty() })
        capture("native-companion-attachment-draft")

        pickerResult(Activity.RESULT_CANCELED, null) {
            compose.onNodeWithTag("native-companion-composer-options").performClick()
            compose.onNodeWithTag("native-companion-choose-attachments").performClick()
        }
        assertEquals(listOf(attachment.id), companionModel.state.value.draft.localAttachments.map { it.id })
        scenario!!.recreate()
        scenario!!.onActivity { activity ->
            companionModel = ViewModelProvider(activity)[NativeCompanionViewModel::class.java]
            mediaModel = ViewModelProvider(activity)[NativeMediaViewModel::class.java]
        }
        awaitNode("native-companion-composer")
        compose.waitUntil(10_000) { companionModel.state.value.draft.localAttachments.size == 1 }
        assertEquals(listOf(attachment.id), companionModel.state.value.draft.localAttachments.map { it.id })
        compose.onNodeWithText("native-fixture.txt").assertIsDisplayed()
        assertNull(mediaModel.state.value.phase)

        compose.onNodeWithTag("native-open-companion-profile").performClick()
        awaitNode("native-companion-avatar-edit")
        val originalAvatar = companionModel.state.value.selectedCompanion!!.avatarUrl
        val image = insertMedia("native-fixture-avatar.png", "image/png", images = true) { output ->
            val bitmap = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
            try {
                bitmap.eraseColor(0xff6750a4.toInt())
                assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
            } finally { bitmap.recycle() }
        }
        pickerResult(Activity.RESULT_OK, image) {
            compose.onNodeWithTag("native-companion-avatar-edit").performClick()
        }
        compose.waitUntil(10_000) {
            compose.onAllNodes(hasTestTag("native-companion-profile-save") and isEnabled()).fetchSemanticsNodes(false).isNotEmpty()
        }
        compose.onNodeWithTag("native-companion-profile-save").assertIsEnabled()
        assertNull(mediaModel.state.value.error)
        assertEquals("Avatar selection remains a preview until Save", originalAvatar, companionModel.state.value.selectedCompanion!!.avatarUrl)
        capture("native-companion-avatar-preview")
        scenario!!.recreate()
        scenario!!.onActivity { activity -> companionModel = ViewModelProvider(activity)[NativeCompanionViewModel::class.java] }
        awaitNode("native-companion-profile-save")
        compose.onNodeWithTag("native-companion-profile-save").assertIsEnabled().performClick()
        compose.waitUntil(15_000) {
            companionModel.state.value.selectedCompanion?.avatarUrl.let { it != null && it != originalAvatar } && !companionModel.state.value.profileSaving
        }
        assertNull(companionModel.state.value.error)
        assertTrue(companionModel.state.value.selectedCompanion!!.avatarUrl!!.startsWith("/droy/companions/$kaka/attachments/"))
        assertEquals(listOf(attachment.id), companionModel.state.value.draftsByCompanion[kaka]!!.localAttachments.map { it.id })
        capture("native-companion-avatar-saved")
    }

    @Test fun nativeWorkbenchKeepsLocalFileUntilSendAndOpensItsDurableAttachment() {
        compose.onNodeWithTag("native-start-runtime").performClick()
        compose.waitUntil(120_000) { RuntimeService.ready && File(home, "native-fixture-ready.json").isFile }
        compose.waitUntil(20_000) { model.state.value.runtime.ready && model.state.value.workspaces.isNotEmpty() }
        val sessions = model.state.value.sessions.map { it.id }
        val draft = model.state.value.draftId
        val document = insertMedia("native-work-file.txt", "text/plain", images = false) { it.write("Synthetic workbench document".toByteArray()) }
        pickerResult(Activity.RESULT_OK, document) {
            compose.onNodeWithTag("native-composer-attachments").performClick()
            compose.onNodeWithTag("native-choose-files").performClick()
        }
        compose.waitUntil(15_000) { model.state.value.draftAttachments.size == 1 }
        val attachment = model.state.value.draftAttachments.single()
        assertNull(model.state.value.selectedSessionId)
        assertEquals(sessions, model.state.value.sessions.map { it.id })
        assertFalse("Private file admission cannot call the model", File(home, "native-fixture-calls.jsonl").exists())
        assertEquals("Synthetic workbench document", runBlocking { model.loadLocalAttachment(attachment) }.toString(Charsets.UTF_8))
        scenario!!.recreate()
        scenario!!.onActivity { activity -> model = ViewModelProvider(activity)[NativeWorkbenchViewModel::class.java] }
        awaitNode("native-workbench")
        compose.waitUntil(15_000) { model.state.value.runtime.ready && model.state.value.draftAttachments.size == 1 }
        assertEquals(draft, model.state.value.draftId)
        assertEquals(attachment.id, model.state.value.draftAttachments.single().id)
        capture("native-work-file-local-draft")
        compose.onNodeWithTag("native-send").assertIsEnabled().performClick()
        awaitReply("Native DSH reply received.")
        val session = requireNotNull(model.state.value.selectedSessionId)
        val sent = model.state.value.messages.single { it.role == "user" && it.attachments.any { item -> item.name == "native-work-file.txt" } }
        assertNotNull(sent.seq)
        assertNotNull(sent.attachments.single().attachmentId)
        compose.waitUntil(10_000) { model.state.value.draftAttachments.isEmpty() }
        compose.onNodeWithTag("native-history").performScrollToNode(hasTestTag("native-message-attachment:${sent.id}:0"))
        compose.onNodeWithTag("native-message-attachment:${sent.id}:0").assertIsEnabled().performClick()
        awaitNode("native-tools")
        val tools = arrayOfNulls<NativeToolsViewModel>(1)
        scenario!!.onActivity { tools[0] = ViewModelProvider(it)[NativeToolsViewModel::class.java] }
        compose.waitUntil(20_000) { tools[0]!!.state.value.page == NativeToolsPage.PREVIEW && !tools[0]!!.state.value.loading }
        assertEquals(session, tools[0]!!.state.value.selectedSessionId)
        assertNull(tools[0]!!.state.value.error)
        compose.onNodeWithText("Synthetic workbench document").assertIsDisplayed()
        capture("native-work-file-history-preview")
        instrumentation.uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
        compose.waitForIdle()
        assertEquals(1, model.state.value.messages.count { it.id == sent.id })
    }

    @Test fun nativeExternalSharesKeepFifoAndSendOnlyTheConfirmedTarget() {
        compose.onNodeWithTag("native-start-runtime").performClick()
        compose.waitUntil(120_000) { RuntimeService.ready && File(home, "native-fixture-ready.json").isFile }
        compose.waitUntil(20_000) { model.state.value.runtime.ready && companionModel.state.value.companions.isNotEmpty() }
        scenario!!.onActivity { model.setDraft("Retained workbench input") }
        val originalDraftId = model.state.value.draftId
        val share = arrayOfNulls<NativeSharePreviewViewModel>(1)
        scenario!!.onActivity { share[0] = ViewModelProvider(it)[NativeSharePreviewViewModel::class.java] }
        fun receive(text: String) { instrumentation.runOnMainSync {
            context.startActivity(Intent(context, io.droyagent.app.media.ShareReceiveActivity::class.java)
                .setAction(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } }
        receive("NATIVE_SHARED_FIRST")
        awaitNode("native-share-preview")
        compose.waitUntil(15_000) { share[0]!!.state.value.text == "NATIVE_SHARED_FIRST" && !share[0]!!.state.value.loading }
        val firstImport = requireNotNull(share[0]!!.state.value.importId)
        assertNotNull(share[0]!!.state.value.destination?.workbench)
        assertNull(model.state.value.selectedSessionId)
        assertFalse(File(home, "native-fixture-calls.jsonl").exists())
        receive("NATIVE_SHARED_SECOND")
        compose.waitUntil(15_000) { NativeShareInbox.pending(context).size == 2 }
        assertEquals(firstImport, share[0]!!.state.value.importId)
        assertEquals("NATIVE_SHARED_FIRST", share[0]!!.state.value.text)
        assertNull("An admitted second share must wait quietly without invalidating the current preview", share[0]!!.state.value.error)
        val owner = companionModel.state.value.companions.single().id
        compose.onNodeWithTag("native-share-target").performClick()
        compose.onNodeWithTag("native-share-target:$owner").performClick()
        assertEquals(owner, share[0]!!.state.value.destination?.companionId)
        capture("native-share-confirm-companion")
        compose.onNodeWithTag("native-share-confirm").assertIsEnabled().performClick()
        compose.waitUntil(30_000) { share[0]!!.state.value.importId != firstImport && share[0]!!.state.value.text == "NATIVE_SHARED_SECOND" }
        assertEquals(1, NativeShareInbox.pending(context).size)
        compose.waitUntil(20_000) { companionModel.state.value.messages.any { it.role == "user" && it.text == "NATIVE_SHARED_FIRST" } }
        assertEquals(1, companionModel.state.value.messages.count { it.role == "user" && it.text == "NATIVE_SHARED_FIRST" })
        assertEquals(originalDraftId, model.state.value.draftId)
        assertEquals("Retained workbench input", model.state.value.draft)
        assertNull(model.state.value.selectedSessionId)
        assertTrue(companionModel.state.value.messages.none { it.text == "NATIVE_SHARED_SECOND" })
        capture("native-share-second-preview")
        compose.onNodeWithTag("native-share-cancel").performClick()
        compose.waitUntil(15_000) { share[0]!!.state.value.importId == null && NativeShareInbox.pending(context).isEmpty() }
        assertTrue(companionModel.state.value.messages.none { it.text == "NATIVE_SHARED_SECOND" })
    }

    @Test fun nativeSettingsReachSharedServicesInBothLanguagesAndReturnThroughTheSameStack() {
        compose.onNodeWithTag("native-start-runtime").performClick()
        compose.waitUntil(120_000) { RuntimeService.ready && File(home, "native-fixture-ready.json").isFile }
        compose.waitUntil(20_000) { settingsModel.state.value.loaded && settingsModel.state.value.runtime.ready }
        val memory = arrayOfNulls<NativeMemoryViewModel>(1)
        val voice = arrayOfNulls<NativeVoiceViewModel>(1)
        val advanced = arrayOfNulls<NativeAdvancedViewModel>(1)
        val engine = arrayOfNulls<NativeEngineViewModel>(1)
        scenario!!.onActivity {
            memory[0] = ViewModelProvider(it)[NativeMemoryViewModel::class.java]
            voice[0] = ViewModelProvider(it)[NativeVoiceViewModel::class.java]
            advanced[0] = ViewModelProvider(it)[NativeAdvancedViewModel::class.java]
            engine[0] = ViewModelProvider(it)[NativeEngineViewModel::class.java]
        }
        fun goBack(tag: String) {
            instrumentation.uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
            awaitNode(tag)
        }
        compose.onNodeWithTag("native-tab-settings").performClick()
        for (language in listOf("en", "zh")) {
            scenario!!.onActivity { settingsModel.setLanguage(language) }
            compose.waitUntil(20_000) { !settingsModel.state.value.saving && settingsModel.state.value.locale == language && model.state.value.runtime.locale == language }
            for ((item, screen) in listOf("memory" to "native-memory", "voice" to "native-voice", "tools" to "native-tools")) {
                compose.onNodeWithTag("native-settings-$item").performScrollTo().performClick()
                awaitNode(screen)
                compose.onNodeWithTag("native-root-navigation").assertDoesNotExist()
                when (item) {
                    "memory" -> { compose.waitUntil(20_000) { memory[0]!!.state.value.status != null }; assertNull(memory[0]!!.state.value.statusError) }
                    "voice" -> { compose.waitUntil(20_000) { voice[0]!!.state.value.snapshot != null }; assertNull(voice[0]!!.state.value.error) }
                }
                scenario!!.onActivity { assertFalse("Native settings screens must not embed the old chat", containsWebView(it.window.decorView)) }
                capture("native-$item-$language")
                goBack("native-settings-memory")
            }
            compose.onNodeWithTag("native-settings-notifications").performScrollTo().performClick()
            awaitNode("native-settings-group")
            for (section in listOf("notifications", "proactive", "pet")) {
                compose.onNodeWithTag("native-settings-group:$section").performClick()
                awaitNode("native-product-settings:$section")
                awaitNode("native-product-body:$section")
                capture("native-product-$section-$language")
                if (section == "pet") {
                    compose.onNodeWithTag("native-product-open-studio").performScrollTo().performClick()
                    awaitNode("native-pet-studio")
                    awaitNode("native-pet-studio-body")
                    if (compose.onAllNodesWithTag("native-pet-studio-unavailable").fetchSemanticsNodes(false).isNotEmpty())
                        compose.onNodeWithTag("native-pet-studio-generate").assertIsNotEnabled()
                    capture("native-pet-studio-$language")
                    goBack("native-product-settings:pet")
                }
                goBack("native-settings-group")
            }
            goBack("native-settings-advanced")
            compose.onNodeWithTag("native-settings-advanced").performScrollTo().performClick()
            awaitNode("native-settings-group")
            for (section in listOf("presets", "permissions", "plugins")) {
                compose.onNodeWithTag("native-settings-group:$section").performClick()
                awaitNode(if (section == "plugins") "native-advanced" else "native-engine-settings")
                if (section == "plugins") {
                    compose.waitUntil(20_000) { advanced[0]!!.state.value.ready && !advanced[0]!!.state.value.loading }
                    assertNull(advanced[0]!!.state.value.error)
                    val localized = android.content.res.Configuration(context.resources.configuration).apply { setLocale(java.util.Locale.forLanguageTag(language)) }
                    compose.onNodeWithText(context.createConfigurationContext(localized).getString(R.string.native_advanced_plugins)).performScrollTo().performClick()
                    awaitNode("native-advanced-page:plugins")
                    compose.waitUntil(20_000) { !advanced[0]!!.state.value.busy && advanced[0]!!.state.value.plugins.isNotEmpty() }
                    assertTrue(advanced[0]!!.state.value.pluginsAvailable)
                    capture("native-plugins-manager-$language")
                    goBack("native-advanced-page:home")
                } else {
                    compose.waitUntil(20_000) { engine[0]!!.state.value.catalog != null && !engine[0]!!.state.value.loading }
                    assertNull(engine[0]!!.state.value.error)
                }
                capture("native-advanced-$section-$language")
                goBack("native-settings-group")
            }
            goBack("native-settings-about")
            compose.onNodeWithTag("native-settings-about").performScrollTo().performClick()
            awaitNode("native-product-settings:diagnostics")
            awaitNode("native-product-body:diagnostics")
            compose.onNodeWithTag("native-product-runtime-start").assertIsNotEnabled()
            capture("native-diagnostics-$language")
            goBack("native-settings-general")
            compose.onNodeWithTag("native-settings-general").performScrollTo().performClick()
            awaitNode("native-product-settings:general")
            awaitNode("native-product-body:general")
            capture("native-general-$language")
            goBack("native-settings-memory")
        }
        assertFalse("Opening settings does not submit a model request", File(home, "native-fixture-calls.jsonl").exists())
    }

    private fun insertMedia(name: String, type: String, images: Boolean, write: (java.io.OutputStream) -> Unit): Uri {
        val collection = if (images) MediaStore.Images.Media.EXTERNAL_CONTENT_URI else MediaStore.Downloads.EXTERNAL_CONTENT_URI
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, type)
            put(MediaStore.MediaColumns.RELATIVE_PATH, if (images) "Pictures/DroyNativeFixture" else "Download/DroyNativeFixture")
        }
        val uri = requireNotNull(context.contentResolver.insert(collection, values))
        createdMedia += uri
        requireNotNull(context.contentResolver.openOutputStream(uri)).use(write)
        // ActivityMonitor returns data without the system picker's grant transaction.
        context.grantUriPermission(context.packageName, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        assertEquals(android.content.pm.PackageManager.PERMISSION_GRANTED,
            context.checkUriPermission(uri, android.os.Process.myPid(), android.os.Process.myUid(), Intent.FLAG_GRANT_READ_URI_PERMISSION))
        assertNotNull(context.packageManager.resolveContentProvider(uri.authority!!, 0))
        return uri
    }

    private fun pickerResult(code: Int, uri: Uri?, choose: () -> Unit) {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_OPEN_DOCUMENT)
            addAction(MediaStore.ACTION_PICK_IMAGES)
            addCategory(Intent.CATEGORY_OPENABLE)
            addDataType("*/*")
        }
        val result = uri?.let { Intent().setData(it).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        val monitor = instrumentation.addMonitor(filter, Instrumentation.ActivityResult(code, result), true)
        try {
            choose()
            compose.waitUntil(10_000) { monitor.hits == 1 }
            compose.waitUntil(20_000) { !mediaModel.state.value.busy }
            compose.waitForIdle()
        } finally { instrumentation.removeMonitor(monitor) }
    }

    private fun sendMarker(value: String) {
        compose.waitUntil(10_000) { !model.state.value.running && !model.state.value.sending }
        scenario!!.onActivity { model.setDraft(value) }
        compose.onNodeWithTag("native-send").performClick()
    }

    private fun startFirstUseFixture() {
        compose.onNodeWithTag("native-start-runtime").performClick()
        compose.waitUntil(120_000) { RuntimeService.ready && File(home, "native-fixture-ready.json").isFile }
        compose.waitUntil(20_000) { model.state.value.runtime.ready && model.state.value.workspaces.isNotEmpty() }
        compose.onNodeWithTag("native-tab-settings").performClick()
        awaitNode("native-settings-permissions")
        compose.onNodeWithTag("native-settings-permissions").performClick()
        awaitNode("native-permissions-first-use")
        compose.onNodeWithTag("native-permissions-first-use").assertIsEnabled().performClick()
        assertEquals(NativeAuthorizationPolicy.ON_FIRST_USE, NativePermissionPolicyStore(ProductPermissions.preferences(context)).selected())
        compose.onNodeWithTag("native-permissions-back").performClick()
        awaitNode("native-tab-workbench")
        compose.onNodeWithTag("native-tab-workbench").performClick()
        awaitNode("native-workbench")
    }

    private fun imeVisible(): Boolean {
        var visible = false
        scenario!!.onActivity { visible = it.window.decorView.rootWindowInsets?.isVisible(android.view.WindowInsets.Type.ime()) == true }
        return visible
    }

    private fun awaitNode(tag: String) {
        // The backend StateFlow can lead the recreated Activity's next Compose frame.
        compose.waitUntil(10_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes(false).isNotEmpty() }
        compose.waitForIdle()
    }

    private fun awaitReply(text: String) {
        compose.waitUntil(30_000) { model.state.value.messages.any { it.role == "assistant" && it.text == text && !it.streaming } }
        compose.waitUntil(10_000) { !model.state.value.running && model.state.value.decisions.isEmpty() }
        compose.onNodeWithTag("native-pending-decisions").assertDoesNotExist()
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        instrumentation.waitForIdleSync()
        val directory = File(context.getExternalFilesDir(null), "native-workbench-evidence").apply { mkdirs() }
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        try { File(directory, "$name.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) } }
        finally { bitmap.recycle() }
    }

    private fun containsWebView(view: View): Boolean = view is WebView || view is ViewGroup && (0 until view.childCount).any { containsWebView(view.getChildAt(it)) }
    private fun restorePreferences(target: SharedPreferences, values: Map<String, *>) {
        val edit = target.edit().clear()
        values.forEach { (key, value) -> when (value) {
            is String -> edit.putString(key, value)
            is Boolean -> edit.putBoolean(key, value)
            is Int -> edit.putInt(key, value)
            is Long -> edit.putLong(key, value)
            is Float -> edit.putFloat(key, value)
            is Set<*> -> @Suppress("UNCHECKED_CAST") edit.putStringSet(key, value as Set<String>)
        } }
        assertTrue(edit.commit())
    }
}
