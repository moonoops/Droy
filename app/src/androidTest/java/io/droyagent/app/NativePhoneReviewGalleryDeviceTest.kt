package io.droyagent.app

import android.content.res.Configuration
import android.graphics.Bitmap
import android.os.SystemClock
import android.view.KeyEvent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.droyagent.app.nativeui.companion.data.NativeCompanionViewModel
import io.droyagent.app.nativeui.data.NativeWorkbenchViewModel
import io.droyagent.app.nativeui.memory.NativeMemoryViewModel
import io.droyagent.app.nativeui.navigation.NativeExternalNavigationTarget
import io.droyagent.app.nativeui.navigation.NativeExternalNavigationViewModel
import io.droyagent.app.nativeui.navigation.NativeExternalRoot
import io.droyagent.app.nativeui.settings.NativeSettingsViewModel
import io.droyagent.app.nativeui.voice.NativeVoiceViewModel
import java.io.File
import java.util.Locale
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in review of an already configured development phone. No fixture, configuration writes,
 * synthetic records, model calls, permission requests, or recording/playback actions. */
@RunWith(AndroidJUnit4::class)
class NativePhoneReviewGalleryDeviceTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private lateinit var scenario: ActivityScenario<NativeAppActivity>
    private lateinit var navigation: NativeExternalNavigationViewModel
    private lateinit var work: NativeWorkbenchViewModel
    private lateinit var companions: NativeCompanionViewModel
    private lateinit var settings: NativeSettingsViewModel
    private lateinit var voice: NativeVoiceViewModel
    private lateinit var memory: NativeMemoryViewModel
    private lateinit var directory: File
    private var step = "launch"

    @Test fun captureRevisedSettingsContainers() {
        assumeTrue("Explicit phone review opt-in required", InstrumentationRegistry.getArguments()
            .getString("nativePhoneReadOnlyGallery") == "true")
        assertTrue("Development package only", context.packageName == "io.droyagent.app.dev")
        directory = File(requireNotNull(context.getExternalFilesDir(null)), "native-phone-review-revised-settings").apply { mkdirs() }
        File(directory, "capture-index.txt").writeText("Revised settings only; existing locale/theme/configuration.\n")
        try {
            scenario = ActivityScenario.launch(NativeAppActivity::class.java)
            scenario.onActivity { activity ->
                val models = ViewModelProvider(activity)
                navigation = models[NativeExternalNavigationViewModel::class.java]
                work = models[NativeWorkbenchViewModel::class.java]
                settings = models[NativeSettingsViewModel::class.java]
            }
            compose.waitUntil(30_000) { work.state.value.runtime.ready && settings.state.value.loaded }
            check(!work.state.value.sending && !work.state.value.running && work.state.value.decisions.isEmpty()) {
                "Phone review requires an idle workbench with no pending approvals"
            }
            root(NativeExternalRoot.SETTINGS, "native-settings-general")

            step = "01-product-capabilities"
            settingsEntry("permissions", "native-permissions")
            check(!exists("native-permissions-queue")) { "Finish pending authorization before read-only review" }
            permissionSubpage(R.string.native_permissions_product_capabilities)
            waitTag("native-permissions-operations"); capture()
            compose.onNodeWithTag("native-permissions-back").performClick()
            compose.onNodeWithTag("native-permissions-back").performClick()
            waitTag("native-settings-general")

            product("02-notifications", "notifications", "proactive")
            product("03-notification-sorting", "notification-sorting", "notifications")
            step = "04-pet"
            settingsEntry("pet", "native-product-settings:pet")
            waitTag("native-product-body:pet"); capture()
            step = "05-pet-display-mode"
            compose.onNodeWithTag("native-product-list:pet")
                .performScrollToNode(hasTestTag("native-product-field:nps_external"))
            compose.onNodeWithTag("native-product-field:nps_external").assertIsEnabled().performClick()
            waitTag("native-product-choice:adaptive"); capture()
            backTo("native-product-body:pet")
            compose.onNodeWithTag("native-product-back").performClick()
            waitTag("native-settings-general")

            step = "06-model-input-inheritance"
            settingsEntry("models", "native-settings-providers")
            val provider = settings.state.value.configuredProviders.singleOrNull { it.name == "DroyLocalReview" }
            checkNotNull(provider) { "Prepare DroyLocalReview before running this read-only gallery" }
            compose.onNodeWithTag("native-settings-providers")
                .performScrollToNode(hasTestTag("native-settings-provider:${provider.id}"))
            click("native-settings-provider:${provider.id}", "native-settings-provider-editor")
            compose.waitUntil(15_000) { settings.state.value.editor != null && !settings.state.value.loading }
            val draft = requireNotNull(settings.state.value.editor)
            val localModel = settings.providerModels(draft).single { it.id == "droy-review-local" }
            check(localModel.inputModalities == null) { "The prepared local model must inherit input modalities" }
            compose.onNodeWithTag("native-settings-provider-editor").performScrollToNode(hasText("droy-review-local"))
            compose.onNodeWithText("droy-review-local").performClick()
            waitTag("native-settings-manual-model-id")
            compose.onNodeWithText(text(R.string.native_settings_model_advanced)).performClick()
            waitText(R.string.native_settings_input_types)
            compose.onNode(isToggleable() and hasAnySibling(hasText(text(R.string.native_settings_inherit))),
                useUnmergedTree = true).assertIsOn()
            capture()
            compose.onNodeWithTag("native-settings-model-back").performClick()
            waitTag("native-settings-provider-editor")
            compose.onNodeWithTag("native-settings-back").performClick()
            waitTag("native-settings-providers")
            compose.onNodeWithTag("native-settings-back").performClick()
            waitTag("native-settings-general")
            File(directory, "capture-index.txt").appendText("Six captures completed; input inheritance displayed as enabled; configuration unchanged.\n")
        } catch (failure: Throwable) {
            runCatching { screenshot("FAILED-$step") }.exceptionOrNull()?.let(failure::addSuppressed)
            runCatching {
                File(directory, "FAILED-$step-semantics.txt").writeText(
                    compose.onRoot(useUnmergedTree = true).printToString())
            }.exceptionOrNull()?.let(failure::addSuppressed)
            throw failure
        } finally {
            if (::scenario.isInitialized) scenario.close()
        }
    }

    @Test fun captureExistingPhonePagesWithoutChangingConfiguration() {
        assumeTrue("Explicit phone review opt-in required", InstrumentationRegistry.getArguments()
            .getString("nativePhoneReadOnlyGallery") == "true")
        assertTrue("Development package only", context.packageName == "io.droyagent.app.dev")
        directory = File(requireNotNull(context.getExternalFilesDir(null)), "native-phone-review").apply { mkdirs() }
        File(directory, "capture-index.txt").writeText("Current installed development app; current locale/theme; read-only navigation.\n")
        try {
            // Some OEMs require the operator to foreground this launch with adb; do not change permissions.
            scenario = ActivityScenario.launch(NativeAppActivity::class.java)
            scenario.onActivity { activity ->
                val models = ViewModelProvider(activity)
                navigation = models[NativeExternalNavigationViewModel::class.java]
                work = models[NativeWorkbenchViewModel::class.java]
                companions = models[NativeCompanionViewModel::class.java]
                settings = models[NativeSettingsViewModel::class.java]
                voice = models[NativeVoiceViewModel::class.java]
                memory = models[NativeMemoryViewModel::class.java]
            }
            compose.waitUntil(30_000) { work.state.value.runtime.ready && settings.state.value.loaded }
            check(!work.state.value.sending && !work.state.value.running && work.state.value.decisions.isEmpty()) {
                "Phone review requires an idle workbench with no pending approvals"
            }

            step = "01-messages"
            root(NativeExternalRoot.MESSAGES, "native-companion-messages")
            compose.waitUntil(20_000) { !companions.state.value.loading && companions.state.value.companions.isNotEmpty() }
            check(!companions.state.value.sending) { "Phone review requires idle companion messaging" }
            capture()
            val owner = companions.state.value.companions.first().id
            step = "02-companion-chat"
            click("native-companion-conversation:$owner", "native-companion-chat")
            compose.waitUntil(15_000) { !companions.state.value.loading }
            capture()
            step = "03-companion-profile"
            click("native-open-companion-profile", "native-companion-profile"); capture()
            step = "04-companion-personality"
            click("native-companion-personality", "native-companion-personality-input"); capture()
            backTo("native-companion-profile")
            step = "05-companion-model"
            click("native-companion-model", "native-companion-model-search"); capture()
            backTo("native-companion-profile")
            step = "06-companion-interaction"
            click("native-companion-interaction", "native-companion-interaction-save"); capture()
            step = "07-companion-background"
            compose.onNodeWithTag("native-companion-background").performScrollTo().performClick()
            waitTag("native-background-apply"); capture()
            backTo("native-companion-interaction-save")
            backTo("native-companion-profile")
            backTo("native-companion-chat")
            backTo("native-companion-messages")

            step = "08-workbench"
            root(NativeExternalRoot.WORKBENCH, "native-workbench"); capture()
            step = "09-workbench-menu"
            compose.onNodeWithTag("native-workbench-menu").performClick()
            waitText(R.string.native_workbench_font); capture()
            step = "10-spaces-conversations"
            compose.onNodeWithText(text(R.string.native_spaces_chats)).performClick()
            waitTag("native-workspaces")
            compose.waitUntil(15_000) { !exists("native-workspaces-loading") }; capture()
            backTo("native-workbench")
            step = "11-schedules"
            compose.onNodeWithTag("native-workbench-menu").performClick()
            waitText(R.string.native_schedule_title)
            compose.onNodeWithText(text(R.string.native_schedule_title)).performClick()
            waitTag("native-schedule-catalog"); capture()
            backTo("native-workbench")

            step = "12-settings"
            root(NativeExternalRoot.SETTINGS, "native-settings-general"); capture()
            product("13-general", "general", "general")
            step = "14-models"
            settingsEntry("models", "native-settings-providers"); capture()
            step = "15-default-model-selector"
            compose.onNodeWithTag("native-settings-default-model").performClick()
            waitText(R.string.native_settings_search_models); capture()
            backTo("native-settings-providers")

            // Only the explicitly prepared loopback provider is opened, never a real provider's editor.
            val provider = settings.state.value.configuredProviders.singleOrNull { it.name == "DroyLocalReview" }
            checkNotNull(provider) { "Prepare DroyLocalReview before running this read-only gallery" }
            step = "16-local-provider-editor"
            compose.onNodeWithTag("native-settings-providers")
                .performScrollToNode(hasTestTag("native-settings-provider:${provider.id}"))
            click("native-settings-provider:${provider.id}", "native-settings-provider-editor")
            compose.waitUntil(15_000) { settings.state.value.editor != null && !settings.state.value.loading }
            capture()
            step = "17-model-advanced"
            compose.onNodeWithTag("native-settings-provider-editor").performScrollToNode(hasText("droy-review-local"))
            compose.onNodeWithText("droy-review-local").performClick()
            waitTag("native-settings-manual-model-id")
            compose.onNodeWithText(text(R.string.native_settings_model_advanced)).performClick()
            waitText(R.string.native_settings_context_window); capture()
            compose.onNodeWithTag("native-settings-model-back").performClick()
            waitTag("native-settings-provider-editor")
            compose.onNodeWithTag("native-settings-back").performClick()
            waitTag("native-settings-providers")
            compose.onNodeWithTag("native-settings-back").performClick()
            waitTag("native-settings-general")

            step = "18-voice"
            settingsEntry("voice", "native-voice-open-readout")
            compose.waitUntil(15_000) { !voice.state.value.loading && (voice.state.value.fresh || voice.state.value.error != null) }
            capture()
            step = "19-voice-readout"
            click("native-voice-open-readout", "native-voice-save-tts"); capture()
            backTo("native-voice-open-readout")
            backTo("native-settings-general")

            step = "20-permissions"
            settingsEntry("permissions", "native-permissions")
            check(!exists("native-permissions-queue")) { "Finish any pending authorization queue before read-only review" }
            capture()
            step = "21-system-permissions"
            permissionSubpage(R.string.native_permissions_system_permissions); capture()
            compose.onNodeWithTag("native-permissions-back").performClick()
            step = "22-product-capabilities"
            permissionSubpage(R.string.native_permissions_product_capabilities); capture()
            compose.onNodeWithTag("native-permissions-back").performClick()
            compose.onNodeWithTag("native-permissions-back").performClick()
            waitTag("native-settings-general")

            step = "23-memory"
            settingsEntry("memory", "native-memory-settings")
            compose.waitUntil(15_000) { !memory.state.value.loading && (memory.state.value.status != null || memory.state.value.statusError != null) }
            capture()
            step = "24-phone-activity"
            compose.onNodeWithTag("native-memory-settings").performScrollToNode(hasTestTag("native-memory-open-capture"))
            click("native-memory-open-capture", "native-memory-capture"); capture()
            backTo("native-memory-settings")
            backTo("native-settings-general")

            product("25-notifications", "notifications", "proactive")
            product("26-notification-sorting", "notification-sorting", "notifications")
            product("27-pet", "pet", "pet")
            product("28-about", "about", "diagnostics")
            step = "29-web-search"
            compose.onNodeWithTag("native-settings-web-search").performScrollTo().performClick()
            waitText(R.string.native_settings_search_service)
            compose.waitUntil(15_000) { !settings.state.value.loading }; capture()
            backTo("native-settings-general")
            File(directory, "capture-index.txt").appendText("Navigation completed. No send/save/authorization/download controls exercised.\n")
        } catch (failure: Throwable) {
            runCatching { screenshot("FAILED-$step") }.exceptionOrNull()?.let(failure::addSuppressed)
            runCatching {
                File(directory, "FAILED-$step-semantics.txt").writeText(
                    compose.onRoot(useUnmergedTree = true).printToString())
            }.exceptionOrNull()?.let(failure::addSuppressed)
            throw failure
        } finally {
            if (::scenario.isInitialized) scenario.close()
        }
    }

    private fun root(destination: NativeExternalRoot, tag: String) {
        scenario.onActivity { navigation.accept(NativeExternalNavigationTarget.Root(destination)) }
        waitTag(tag)
        compose.waitUntil(15_000) { navigation.state.value.request == null }
    }

    private fun settingsEntry(entry: String, tag: String) {
        waitTag("native-settings-$entry")
        compose.onNodeWithTag("native-settings-$entry").performScrollTo().performClick()
        waitTag(tag)
    }

    private fun product(name: String, entry: String, wire: String) {
        step = name
        settingsEntry(entry, "native-product-settings:$wire")
        compose.waitUntil(15_000) { exists("native-product-body:$wire") || exists("native-product-error:$wire") }
        capture()
        compose.onNodeWithTag("native-product-back").performClick()
        waitTag("native-settings-general")
    }

    private fun permissionSubpage(label: Int) {
        compose.onNodeWithTag("native-permissions-content").performScrollToNode(hasText(text(label)))
        compose.onNodeWithText(text(label)).performClick()
        waitText(label)
    }

    private fun click(tag: String, destination: String) {
        waitTag(tag)
        compose.onNodeWithTag(tag).performClick()
        waitTag(destination)
    }

    private fun backTo(tag: String) {
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        waitTag(tag)
        compose.waitForIdle()
    }

    private fun exists(tag: String) = compose.onAllNodesWithTag(tag).fetchSemanticsNodes(false).isNotEmpty()
    private fun waitTag(tag: String) = compose.waitUntil(15_000) { exists(tag) }
    private fun waitText(id: Int) = compose.waitUntil(15_000) {
        compose.onAllNodesWithText(text(id)).fetchSemanticsNodes(false).isNotEmpty()
    }
    private fun text(id: Int) = context.createConfigurationContext(Configuration(context.resources.configuration).apply {
        setLocale(Locale.forLanguageTag(work.state.value.runtime.locale))
    }).getString(id)

    private fun capture() {
        compose.waitForIdle()
        SystemClock.sleep(400) // Wait for the real navigation transition before the system capture.
        screenshot(step)
        File(directory, "capture-index.txt").appendText("$step.png\n")
    }

    private fun screenshot(name: String) {
        val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot()) { "System screenshot unavailable" }
        try {
            File(directory, "$name.png").outputStream().use { output ->
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) { "Screenshot write failed" }
            }
        } finally { bitmap.recycle() }
    }
}
