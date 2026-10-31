package io.droyagent.app

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.os.SystemClock
import android.provider.MediaStore
import android.view.KeyEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.droyagent.app.nativeui.companion.data.NativeCompanionViewModel
import io.droyagent.app.nativeui.companion.creation.NativeCompanionCreationViewModel
import io.droyagent.app.nativeui.engine.NativeEngineViewModel
import io.droyagent.app.nativeui.advanced.NativeAdvancedViewModel
import io.droyagent.app.nativeui.data.NativeWorkbenchViewModel
import io.droyagent.app.nativeui.memory.NativeMemoryViewModel
import io.droyagent.app.nativeui.media.NativeMediaKind
import io.droyagent.app.nativeui.media.NativeMediaPhase
import io.droyagent.app.nativeui.media.NativeMediaViewModel
import io.droyagent.app.nativeui.navigation.NativeExternalNavigationTarget
import io.droyagent.app.nativeui.navigation.NativeExternalNavigationViewModel
import io.droyagent.app.nativeui.navigation.NativeExternalRoot
import io.droyagent.app.nativeui.settings.NativeSettingsViewModel
import io.droyagent.app.nativeui.settings.NativeSettingsClient
import io.droyagent.app.nativeui.settings.NativeSettingsJson
import io.droyagent.app.nativeui.standardvoice.NativeStandardVoiceViewModel
import io.droyagent.app.nativeui.ui.WorkbenchReadingSize
import io.droyagent.app.nativeui.voice.NativeVoiceViewModel
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Existing-phone read-only reviews plus a separately opted-in, reversible presentation matrix.
 * Neither path sends messages, requests permissions, downloads resources, or records audio. */
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
    private lateinit var advanced: NativeAdvancedViewModel
    private lateinit var directory: File
    private var step = "launch"

    @Test fun captureRevisedSettingsContainers() {
        assumeTrue("Explicit phone review opt-in required", InstrumentationRegistry.getArguments()
            .getString("nativePhoneReadOnlyGallery") == "true")
        assertTrue("Development package only", context.packageName == "io.droyagent.app.dev")
        directory = File(requireNotNull(context.getExternalFilesDir(null)), "native-phone-review-revised-settings").apply { mkdirs() }
        File(directory, "capture-index.txt").writeText("Revised settings only; existing locale/theme/configuration.\n")
        try {
            scenario = launchPhoneActivity()
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

            product("02-notifications", "notifications", "notifications")
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
            openSafeReviewProvider()
            openSafeReviewModel()
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
            File(directory, "capture-index.txt").appendText("Input inheritance displayed as enabled; existing loopback or unsaved empty provider only; no configuration saved.\n")
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
            scenario = launchPhoneActivity()
            scenario.onActivity { activity ->
                val models = ViewModelProvider(activity)
                navigation = models[NativeExternalNavigationViewModel::class.java]
                work = models[NativeWorkbenchViewModel::class.java]
                companions = models[NativeCompanionViewModel::class.java]
                settings = models[NativeSettingsViewModel::class.java]
                voice = models[NativeVoiceViewModel::class.java]
                memory = models[NativeMemoryViewModel::class.java]
                advanced = models[NativeAdvancedViewModel::class.java]
            }
            compose.waitUntil(30_000) { work.state.value.runtime.ready && settings.state.value.loaded }
            check(!work.state.value.sending && !work.state.value.running && work.state.value.decisions.isEmpty()) {
                "Phone review requires an idle workbench with no pending approvals"
            }

            captureExistingPhonePages()
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

    @Test fun captureCurrentPluginConfigurationPagesWithoutChangingConfiguration() {
        assumeTrue("Explicit phone review opt-in required", InstrumentationRegistry.getArguments()
            .getString("nativePhoneReadOnlyGallery") == "true")
        assertTrue("Development package only", context.packageName == "io.droyagent.app.dev")
        directory = File(requireNotNull(context.getExternalFilesDir(null)), "native-phone-review-plugins").apply { mkdirs() }
        File(directory, "capture-index.txt").writeText("Seven current plugin configuration entries; existing locale/theme; no configuration writes.\n")
        try {
            scenario = launchPhoneActivity()
            scenario.onActivity { activity ->
                val models = ViewModelProvider(activity)
                navigation = models[NativeExternalNavigationViewModel::class.java]
                work = models[NativeWorkbenchViewModel::class.java]
                settings = models[NativeSettingsViewModel::class.java]
                advanced = models[NativeAdvancedViewModel::class.java]
            }
            compose.waitUntil(30_000) { work.state.value.runtime.ready && settings.state.value.loaded }
            check(!work.state.value.sending && !work.state.value.running && work.state.value.decisions.isEmpty()) {
                "Phone review requires an idle workbench with no pending approvals"
            }
            root(NativeExternalRoot.SETTINGS, "native-settings-general")
            capturePluginPages()
            File(directory, "capture-index.txt").appendText("All seven plugin entries reached and returned; no switches, save, credentials, or tool actions exercised.\n")
        } catch (failure: Throwable) {
            runCatching { screenshot("FAILED-$step") }.exceptionOrNull()?.let(failure::addSuppressed)
            runCatching { File(directory, "FAILED-$step-semantics.txt").writeText(
                compose.onRoot(useUnmergedTree = true).printToString()) }.exceptionOrNull()?.let(failure::addSuppressed)
            throw failure
        } finally {
            if (::scenario.isInitialized) scenario.close()
        }
    }

    @Test fun cancelInterruptedPhoneReviewPicker() {
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue("Explicit phone review opt-in required", arguments.getString("nativePhoneReadOnlyGallery") == "true")
        val operationId = arguments.getString("phoneReviewCancelOperationId")
        assumeTrue("An exact phone review picker operation ID is required", !operationId.isNullOrBlank())
        check(context.packageName == "io.droyagent.app.dev") { "Development package only" }
        val external = requireNotNull(context.getExternalFilesDir(null))
        check(!File(external, "native-phone-stage-appearance-restore.json").exists()) {
            "Restore the unfinished presentation review before picker recovery"
        }
        assertNoPhoneFixtureOrRecording()
        check(!NativeRuntimeHost.isFilePickerOpen()) { "An active system picker must finish before interrupted-operation recovery" }
        directory = File(external, "native-phone-review-picker-recovery").apply { mkdirs() }
        File(directory, "capture-index.txt").writeText("Explicit recovery of one identified interrupted empty phone-review picker; no user content selected.\n")
        step = "cancel-identified-interrupted-picker"
        var verifyOwner: (() -> Unit)? = null
        var primaryFailure: Throwable? = null
        try {
            scenario = launchPhoneActivity()
            lateinit var media: NativeMediaViewModel
            scenario.onActivity { activity ->
                val models = ViewModelProvider(activity)
                work = models[NativeWorkbenchViewModel::class.java]
                media = models[NativeMediaViewModel::class.java]
            }
            compose.waitUntil(30_000) { work.state.value.let { it.runtime.ready && it.workspaceResolved && !it.loading } }
            val before = work.state.value
            val attachments = before.draftAttachments.toList()
            check(!before.running && !before.sending && before.pendingRequestId == null && before.decisions.isEmpty()) {
                "Interrupted picker recovery requires an idle task owner"
            }
            verifyOwner = {
                val after = work.state.value
                check(after.draftId == before.draftId && after.selectedSessionId == before.selectedSessionId &&
                    after.selectedWorkspaceId == before.selectedWorkspaceId && after.draft == before.draft &&
                    after.draftAttachments == attachments) { "Interrupted picker recovery changed its task owner or draft" }
            }
            scenario.onActivity {
                val pending = checkNotNull(media.pendingSelection()) { "No interrupted media operation is available for explicit recovery" }
                check(pending.id == operationId && pending.kind == NativeMediaKind.WORKBENCH_DRAFT &&
                    pending.phase == NativeMediaPhase.INTERRUPTED && pending.uris.isEmpty() && pending.receipts.isEmpty() &&
                    !pending.camera && !media.state.value.busy && !NativeRuntimeHost.isFilePickerOpen()) {
                    "Recovery requires the exact requested interrupted empty workbench picker operation"
                }
                media.cancelPending()
            }
            compose.waitUntil(15_000) { media.pendingSelection() == null && media.state.value.phase == null &&
                !media.state.value.busy && !NativeRuntimeHost.isFilePickerOpen() }
            compose.waitUntil(15_000) {
                context.getSharedPreferences("native_media", Context.MODE_PRIVATE).getString("native-media-v1", null)
                    ?.let { JSONObject(it).isNull("selection") } == true
            }
            verifyOwner.invoke()
            reviewNote("The exact identified empty interrupted picker was cancelled and persisted; task owner, text and attachments unchanged.")
        } catch (failure: Throwable) {
            primaryFailure = failure
            runCatching { screenshot("FAILED-$step") }.exceptionOrNull()?.let(failure::addSuppressed)
            throw failure
        } finally {
            try { verifyOwner?.invoke() } catch (failure: Throwable) {
                primaryFailure?.addSuppressed(failure) ?: throw failure
            } finally {
                if (::scenario.isInitialized) scenario.close()
            }
        }
    }

    @Test fun captureConditionalPhoneEntrancesWithoutChangingConfiguration() {
        assumeTrue("Explicit phone review opt-in required", InstrumentationRegistry.getArguments()
            .getString("nativePhoneReadOnlyGallery") == "true")
        check(context.packageName == "io.droyagent.app.dev") { "Development package only" }
        val external = requireNotNull(context.getExternalFilesDir(null))
        check(!File(external, "native-phone-stage-appearance-restore.json").exists()) {
            "Restore the unfinished presentation review before read-only navigation"
        }
        assertNoPhoneFixtureOrRecording()
        check(!NativeRuntimeHost.isFilePickerOpen()) { "Finish the existing file picker before review" }
        directory = File(external, "native-phone-conditional-review").apply { mkdirs() }
        File(directory, "capture-index.txt").writeText(
            "Conditional existing-phone entrances; current configuration; restrictions recorded per entrance.\n")
        try {
            scenario = launchPhoneActivity()
            captureConditionalPhoneEntrances()
        } catch (failure: Throwable) {
            runCatching { screenshot("FAILED-$step") }.exceptionOrNull()?.let(failure::addSuppressed)
            throw failure
        } finally {
            if (::scenario.isInitialized) scenario.close()
        }
    }

    @Test fun captureStagePhonePagesAndRestoreAppearance() {
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue("Explicit phone stage gallery opt-in required", arguments.getString("nativePhoneStageGallery") == "true")
        check(context.packageName == "io.droyagent.app.dev") { "Development package only" }
        val requestedLocale = arguments.getString("phoneStageLocale", "zh")
        val requestedAppearance = arguments.getString("phoneStageAppearance", "light")
        val maximumFont = arguments.getString("phoneStageMaxFont", "false")
        require(requestedLocale in setOf("en", "zh")) { "phoneStageLocale must be en or zh" }
        require(requestedAppearance in setOf("light", "dark", "system")) { "phoneStageAppearance must be light, dark, or system" }
        require(maximumFont in setOf("true", "false")) { "phoneStageMaxFont must be true or false" }
        val requestedFont = if (maximumFont == "true") 22 else WorkbenchReadingSize.DEFAULT
        val external = requireNotNull(context.getExternalFilesDir(null))
        val marker = File(external, "native-phone-stage-appearance-restore.json")
        check(!marker.exists()) { "Restore the unfinished phone presentation review before starting another: ${marker.name}" }
        assertNoPhoneFixtureOrRecording()
        val product = context.getSharedPreferences("product", Context.MODE_PRIVATE)
        val reading = context.getSharedPreferences("droy_native_reading", Context.MODE_PRIVATE)
        val nativeLanguagePresent = product.contains("languagePreference")
        val nativeLanguage = product.getString("languagePreference", null)
        val originalEffectiveLanguage = NativeLocale.active()
        val appearancePresent = product.contains("appearanceMode")
        val appearance = product.getString("appearanceMode", null)
        val fontPresent = reading.contains("workbenchTextSize")
        val font = reading.getInt("workbenchTextSize", WorkbenchReadingSize.DEFAULT)
        directory = File(external, "native-phone-stage-$requestedLocale-$requestedAppearance-${if (maximumFont == "true") "max" else "normal"}")
            .apply { check(isDirectory || mkdirs()) }
        File(directory, "capture-index.txt").writeText("Stage presentation: locale=$requestedLocale appearance=$requestedAppearance workbenchTextSize=$requestedFont; system font scale unchanged.\n")
        var markerOwned = false
        var originalDshLanguage: String? = null
        var primaryFailure: Throwable? = null
        try {
            scenario = launchPhoneActivity()
            scenario.onActivity { activity ->
                val models = ViewModelProvider(activity)
                navigation = models[NativeExternalNavigationViewModel::class.java]
                work = models[NativeWorkbenchViewModel::class.java]
                companions = models[NativeCompanionViewModel::class.java]
                settings = models[NativeSettingsViewModel::class.java]
                voice = models[NativeVoiceViewModel::class.java]
                memory = models[NativeMemoryViewModel::class.java]
                advanced = models[NativeAdvancedViewModel::class.java]
            }
            compose.waitUntil(30_000) { work.state.value.runtime.ready && settings.state.value.loaded && !settings.state.value.loading }
            check(!work.state.value.sending && !work.state.value.running && work.state.value.decisions.isEmpty() &&
                !companions.state.value.sending && !settings.state.value.saving && !settings.state.value.testing) {
                "Phone stage gallery requires idle tasks, companions and settings with no pending approvals"
            }
            assertNoPhoneFixtureOrRecording()
            originalDshLanguage = savedDshLanguage()
            check(originalDshLanguage in setOf("en", "zh")) {
                "The current language service cannot restore an automatic or absent DSH preference; no stage setting was changed"
            }
            check(originalEffectiveLanguage == originalDshLanguage) {
                "Native and DSH saved languages must agree before a reversible stage review"
            }
            val original = JSONObject().put("version", 1)
                .put("dshLanguagePreference", originalDshLanguage)
                .put("nativeLanguagePresent", nativeLanguagePresent).put("nativeLanguagePreference", nativeLanguage ?: JSONObject.NULL)
                .put("appearancePresent", appearancePresent).put("appearanceMode", appearance ?: JSONObject.NULL)
                .put("fontPresent", fontPresent).put("workbenchTextSize", font)
            check(marker.createNewFile()) { "Another phone presentation review owns the restore marker" }
            markerOwned = true
            FileOutputStream(marker).use { output ->
                output.write(original.toString(2).toByteArray(Charsets.UTF_8)); output.fd.sync()
            }
            stageLanguage(requireNotNull(requestedLocale))
            root(NativeExternalRoot.SETTINGS, "native-settings-general")
            settingsEntry("general", "native-product-settings:general")
            waitTag("native-product-body:general")
            compose.onNodeWithTag("appearance-mode:$requestedAppearance").performScrollTo().assertIsEnabled().performClick()
            compose.waitUntil(10_000) { product.getString("appearanceMode", null) == requestedAppearance }
            compose.onNodeWithTag("appearance-mode:$requestedAppearance").assertIsSelected()
            compose.onNodeWithTag("native-product-back").performClick()
            waitTag("native-settings-general")
            scenario.onActivity { WorkbenchReadingSize(reading).set(requestedFont) }
            compose.waitUntil(10_000) { reading.getInt("workbenchTextSize", WorkbenchReadingSize.DEFAULT) == requestedFont }
            when {
                arguments.getString("phoneStageExpandedSheetsOnly") == "true" -> captureConditionalPhoneEntrances(expandedSheetsOnly = true)
                arguments.getString("phoneStageConditionalOnly") == "true" -> captureConditionalPhoneEntrances()
                else -> captureExistingPhonePages()
            }
        } catch (failure: Throwable) {
            primaryFailure = failure
            runCatching { screenshot("FAILED-$step") }.exceptionOrNull()?.let(failure::addSuppressed)
            runCatching { File(directory, "FAILED-$step-semantics.txt").writeText(
                compose.onRoot(useUnmergedTree = true).printToString()) }.exceptionOrNull()?.let(failure::addSuppressed)
            throw failure
        } finally {
            var cleanupFailure: Throwable? = null
            if (markerOwned) {
                try {
                    // Restore the actual host language first; restoring only a native preference leaves prompts in the stage language.
                    stageLanguage(requireNotNull(originalDshLanguage))
                    val nativeRestore = product.edit()
                    if (nativeLanguagePresent) nativeRestore.putString("languagePreference", nativeLanguage) else nativeRestore.remove("languagePreference")
                    check(nativeRestore.commit()) { "Original native language mirror was not restored" }
                } catch (failure: Throwable) { cleanupFailure = failure }
                try {
                    val themeRestore = product.edit()
                    if (appearancePresent) themeRestore.putString("appearanceMode", appearance) else themeRestore.remove("appearanceMode")
                    check(themeRestore.commit()) { "Original appearance preference was not restored" }
                    val fontRestore = reading.edit()
                    if (fontPresent) fontRestore.putInt("workbenchTextSize", font) else fontRestore.remove("workbenchTextSize")
                    check(fontRestore.commit()) { "Original reading size was not restored" }
                    check(product.contains("appearanceMode") == appearancePresent && product.getString("appearanceMode", null) == appearance)
                    check(reading.contains("workbenchTextSize") == fontPresent && reading.getInt("workbenchTextSize", WorkbenchReadingSize.DEFAULT) == font)
                    check(product.contains("languagePreference") == nativeLanguagePresent && product.getString("languagePreference", null) == nativeLanguage)
                    compose.waitUntil(20_000) { work.state.value.runtime.locale == originalEffectiveLanguage && NativeLocale.active() == originalEffectiveLanguage }
                    check(savedDshLanguage() == originalDshLanguage) { "Original DSH language preference was not restored" }
                } catch (failure: Throwable) {
                    if (cleanupFailure == null) cleanupFailure = failure else cleanupFailure?.addSuppressed(failure)
                }
                if (cleanupFailure == null) {
                    try {
                        check(marker.delete()) { "Restored settings, but the stage marker could not be removed" }
                        File(directory, "capture-index.txt").appendText("Original DSH language, native language mirror, appearance and reading size restored, including absent keys.\n")
                    } catch (failure: Throwable) { cleanupFailure = failure }
                }
            }
            if (::scenario.isInitialized) {
                try { scenario.close() } catch (failure: Throwable) {
                    if (cleanupFailure == null) cleanupFailure = failure else cleanupFailure?.addSuppressed(failure)
                }
            }
            val originalFailure = primaryFailure
            cleanupFailure?.let { if (originalFailure != null) originalFailure.addSuppressed(it) else throw it }
        }
    }

    private fun launchPhoneActivity(): ActivityScenario<NativeAppActivity> {
        val automation = instrumentation.uiAutomation
        var returned = false
        var dropped = false
        try {
            automation.adoptShellPermissionIdentity("android.permission.START_ACTIVITIES_FROM_BACKGROUND")
            return ActivityScenario.launch(NativeAppActivity::class.java).also { returned = true }
        } finally {
            try { automation.dropShellPermissionIdentity(); dropped = true }
            finally {
                File(directory, "capture-index.txt").appendText(
                    "NativeAppActivity launch: returned=$returned; temporary background-launch identity dropped=$dropped.\n")
            }
        }
    }

    private fun savedDshLanguage(): String? = runBlocking {
        val locale = NativeSettingsJson.namespaces(NativeSettingsClient().describe())["locale"]?.value
        locale?.opt("preference")?.takeUnless { it === JSONObject.NULL } as? String
    }

    private fun stageLanguage(language: String) {
        scenario.onActivity { settings.dismissError(); settings.setLanguage(language) }
        compose.waitUntil(30_000) { !settings.state.value.saving && !settings.state.value.loading &&
            settings.state.value.locale == language && work.state.value.runtime.locale == language && NativeLocale.active() == language }
        check(settings.state.value.error == null) { "The saved language did not synchronize with the runtime" }
    }

    private fun assertNoPhoneFixtureOrRecording() {
        for (file in listOf(File(context.filesDir, "native-workbench-fixture.json"),
            File(context.cacheDir, "memory-rootless-lifecycle-restore.json"),
            File(context.cacheDir, "memory-rootless-product-restore.json"),
            File(context.cacheDir, "memory-product-fixture-restore.json")))
            check(!file.exists()) { "Restore the unfinished fixture before phone review: ${file.name}" }
        val capture = AudioCapture.get(context)
        check(capture.uiStatus().isNull("active") && !capture.dictationActive() && !VoiceMedia.hasActiveWork(context)) {
            "Phone stage gallery requires idle recording, dictation and voice playback"
        }
    }

    private fun captureExistingPhonePages() {
        step = "01-messages"
        root(NativeExternalRoot.MESSAGES, "native-companion-messages")
        compose.waitUntil(20_000) { !companions.state.value.loading && companions.state.value.companions.isNotEmpty() }
        check(!companions.state.value.sending) { "Phone review requires idle companion messaging" }
        capture()
        step = "01a-my-profile"
        click("native-open-user-profile", "native-user-profile"); capture()
        compose.onNodeWithTag("native-user-bio").performScrollTo().assertIsDisplayed()
        step = "01b-my-profile-bottom"; capture()
        backTo("native-companion-messages")
        step = "01c-creation-templates"
        click("native-add-companion", "native-creation-templates")
        waitTag("native-creation-template-kaka"); capture()
        compose.onNodeWithTag("native-creation-templates")
            .performScrollToNode(hasTestTag("native-creation-template-claude"))
        compose.onNodeWithTag("native-creation-template-claude").assertIsDisplayed()
        step = "01d-creation-templates-bottom"; capture()
        backTo("native-companion-messages")
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
        root(NativeExternalRoot.SETTINGS, "native-settings-general")
        compose.onNodeWithTag("native-settings-my-profile").performScrollTo(); capture()
        compose.onNodeWithTag("native-settings-about").performScrollTo().assertIsDisplayed()
        step = "12a-settings-bottom"; capture()
        product("13-general", "general", "general")
        step = "14-models"
        settingsEntry("models", "native-settings-providers"); capture()
        step = "15-default-model-selector"
        compose.onNodeWithTag("native-settings-default-model").performClick()
        waitText(R.string.native_settings_search_models); capture()
        backTo("native-settings-providers")

        step = "16-local-provider-editor"
        openSafeReviewProvider(); capture()
        step = "17-model-advanced"
        openSafeReviewModel()
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
        step = "19a-voice-wake"
        compose.onNodeWithTag("native-voice-content").performScrollToNode(hasTestTag("native-voice-open-wake"))
        compose.onNodeWithTag("native-voice-open-wake").performClick()
        waitTag("native-voice-save-wake"); capture()
        compose.onNodeWithTag("native-voice-content")
            .performScrollToNode(hasText(text(R.string.native_voice_english)))
        step = "19b-voice-wake-bottom"; capture()
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
        step = "22a-memory-processing-permissions"
        permissionSubpage(R.string.native_memory_endpoints)
        waitTag("native-memory-endpoints")
        compose.waitUntil(15_000) { !memory.state.value.endpointsLoading }; capture()
        backTo("native-memory-settings")
        backTo("native-permissions")
        compose.onNodeWithTag("native-permissions-back").performClick()
        waitTag("native-settings-general")

        step = "23-memory"
        settingsEntry("memory", "native-memory-settings")
        compose.waitUntil(15_000) { !memory.state.value.loading && (memory.state.value.status != null || memory.state.value.statusError != null) }
        capture()
        step = "24-phone-activity"
        compose.onNodeWithTag("native-memory-settings").performScrollToNode(hasTestTag("native-memory-open-capture"))
        click("native-memory-open-capture", "native-memory-capture"); capture()
        compose.onNodeWithTag("native-memory-capture").performScrollToNode(hasTestTag("native-memory-source-locationEnabled"))
        compose.onNodeWithTag("native-memory-source-locationEnabled").assertIsDisplayed()
        step = "24a-phone-activity-sources-bottom"; capture()
        backTo("native-memory-settings")
        backTo("native-settings-general")
        File(directory, "capture-index.txt").appendText("Not navigated: memory records and maintenance have no visible entry on the current settings page; no direct ViewModel route was used.\n")

        captureNotificationPages("25-notifications")
        product("27-pet", "pet", "pet")
        product("28-about", "about", "diagnostics")
        capturePluginPages()
        File(directory, "capture-index.txt").appendText("Navigation completed. No send/save/authorization/download controls exercised.\n")
    }

    private fun captureConditionalPhoneEntrances(expandedSheetsOnly: Boolean = false) {
        assertNoPhoneFixtureOrRecording()
        check(!NativeRuntimeHost.isFilePickerOpen()) { "Finish the existing file picker before review" }
        val creationPreferences = context.getSharedPreferences("native_companion_creation", Context.MODE_PRIVATE)
        val savedCreationBefore = creationPreferences.getString("journal", null)
        var creation: NativeCompanionCreationViewModel? = null
        lateinit var engine: NativeEngineViewModel
        lateinit var standardVoice: NativeStandardVoiceViewModel
        scenario.onActivity { activity ->
            val models = ViewModelProvider(activity)
            navigation = models[NativeExternalNavigationViewModel::class.java]
            work = models[NativeWorkbenchViewModel::class.java]
            companions = models[NativeCompanionViewModel::class.java]
            settings = models[NativeSettingsViewModel::class.java]
            if (savedCreationBefore == null) creation = models[NativeCompanionCreationViewModel::class.java]
            engine = models[NativeEngineViewModel::class.java]
            standardVoice = models[NativeStandardVoiceViewModel::class.java]
        }
        compose.waitUntil(30_000) { work.state.value.let { it.runtime.ready && it.workspaceResolved && !it.loading } &&
            settings.state.value.loaded }
        root(NativeExternalRoot.MESSAGES, "native-companion-messages")
        compose.waitUntil(20_000) { companions.state.value.runtime.ready && !companions.state.value.loading }
        check(!work.state.value.sending && !work.state.value.running && work.state.value.decisions.isEmpty() &&
            work.state.value.pendingRequestId == null && !companions.state.value.sending &&
            !companions.state.value.profileSaving && !companions.state.value.stickerPackImporting &&
            companions.state.value.companions.all { it.status.phase !in setOf("thinking", "typing", "waiting") &&
                it.status.chatExecution?.quiescent != false } &&
            !settings.state.value.saving && !settings.state.value.testing && !settings.state.value.discovering) {
            "Conditional phone review requires idle tasks, companion conversations and configuration"
        }
        val workBefore = work.state.value
        val attachmentsBefore = workBefore.draftAttachments.toList()
        val companionDraftsBefore = companions.state.value.draftsByCompanion.toMap()
        val creationBefore = creation?.state?.value?.journal
        val verifyDrafts = {
            val after = work.state.value
            check(after.draftId == workBefore.draftId && after.draft == workBefore.draft &&
                after.draftAttachments == attachmentsBefore && after.selectedSessionId == workBefore.selectedSessionId &&
                after.selectedWorkspaceId == workBefore.selectedWorkspaceId) { "Task draft, attachments or owner changed during review" }
            check(companions.state.value.draftsByCompanion == companionDraftsBefore) { "Companion drafts changed during review" }
            check(creation?.state?.value?.journal == creationBefore &&
                creationPreferences.getString("journal", null) == savedCreationBefore) { "Companion creation draft changed during review" }
        }

        var primaryFailure: Throwable? = null
        try {
            if (expandedSheetsOnly) captureExpandedPhoneSheets()
            else {
                captureConditionalCompanionEntrances()
                verifyDrafts.invoke()
                captureConditionalTaskEntrances(engine, standardVoice)
                verifyDrafts.invoke()
                captureConditionalPetStudio()
                captureConditionalCreationEditor(creation)
            }
            root(NativeExternalRoot.WORKBENCH, "native-workbench")
            verifyDrafts.invoke()
            assertNoPhoneFixtureOrRecording()
            reviewNote("Reached entrances returned with task/companion drafts unchanged; no send, save, import, generation or recording controls exercised.")
        } catch (failure: Throwable) {
            primaryFailure = failure
            throw failure
        } finally {
            try { verifyDrafts.invoke() } catch (failure: Throwable) {
                primaryFailure?.addSuppressed(failure) ?: throw failure
            }
        }
    }

    private fun captureExpandedPhoneSheets() {
        val owner = companions.state.value.conversations.firstOrNull()
        if (owner != null) {
            step = "03-companion-sticker-tray-expanded"
            compose.onNodeWithTag("native-companion-conversations")
                .performScrollToNode(hasTestTag("native-companion-conversation:${owner.id}"))
            click("native-companion-conversation:${owner.id}", "native-companion-chat")
            compose.waitUntil(15_000) { !companions.state.value.loading && companions.state.value.selectedCompanionId == owner.id }
            click("native-companion-composer-options", "native-companion-choose-attachments")
            captureExpandedReviewSheet(R.string.native_companion_cancel)
            backTo("native-companion-messages")
        } else reviewNote("Restricted expanded sticker tray: no existing visible companion conversation.")

        root(NativeExternalRoot.WORKBENCH, "native-workbench")
        step = "05-task-model-expanded"
        if (reviewEnabled("native-model")) {
            compose.onNodeWithTag("native-model").performClick()
            waitText(R.string.native_work_search_models)
            captureExpandedReviewSheet(R.string.native_close)
        } else reviewNote("Restricted expanded model sheet: control disabled or absent.")

        step = "06-task-reasoning-expanded"
        if (reviewEnabled("native-reasoning") && work.state.value.selectedModel?.reasoningEfforts.orEmpty().any { it in setOf("xhigh", "max") }) {
            compose.onNodeWithTag("native-reasoning").performClick()
            waitText(R.string.native_reasoning_strength)
            captureExpandedReviewSheet(R.string.native_effort_max)
        } else reviewNote("Restricted expanded Maximum reasoning option: control disabled or current model does not expose Maximum.")

        step = "07-task-usage-expanded"
        click("native-context-control", "native-usage-drag-region")
        captureExpandedReviewSheet(R.string.native_close)
        step = "08-task-font-expanded"
        compose.onNodeWithTag("native-workbench-menu").performClick()
        waitText(R.string.native_workbench_font)
        compose.onNodeWithText(text(R.string.native_workbench_font)).performScrollTo().performClick()
        waitTag("native-font-slider")
        captureExpandedReviewSheet(R.string.native_font_reset, R.string.native_font_apply)

        step = "10-task-attachments-expanded"
        if (reviewEnabled("native-composer-attachments")) {
            compose.onNodeWithTag("native-composer-attachments").performClick()
            waitText(R.string.native_share_file)
            captureExpandedReviewSheet(R.string.native_goal_title)
        } else reviewNote("Restricted expanded attachment sheet: composer attachments disabled or absent.")
        reviewNote("Expanded sheets only: terminal controls were displayed, never activated; no system picker, studio or creation page entered.")
    }

    private fun captureExpandedReviewSheet(vararg terminalLabels: Int) {
        compose.waitUntil(15_000) { compose.onAllNodes(isDialog()).fetchSemanticsNodes(false).size == 1 }
        val expand = SemanticsMatcher.keyIsDefined(SemanticsActions.Expand) and hasAnyAncestor(isDialog())
        val collapse = SemanticsMatcher.keyIsDefined(SemanticsActions.Collapse) and hasAnyAncestor(isDialog())
        val dismiss = SemanticsMatcher.keyIsDefined(SemanticsActions.Dismiss) and hasAnyAncestor(isDialog())
        val terminals = terminalLabels.map { hasText(text(it)) and hasAnyAncestor(isDialog()) }
        var primaryFailure: Throwable? = null
        try {
            val expandCount = compose.onAllNodes(expand).fetchSemanticsNodes(false).size
            check(expandCount <= 1) { "The unique sheet exposed multiple Expand actions" }
            if (expandCount == 1) compose.onNode(expand).performSemanticsAction(SemanticsActions.Expand) { check(it()) }
            compose.waitUntil(15_000) { compose.onAllNodes(collapse).fetchSemanticsNodes(false).isNotEmpty() ||
                terminals.all { compose.onNode(it).isDisplayed() } }
            compose.waitForIdle()
            for (terminal in terminals) {
                if (!compose.onNode(terminal).isDisplayed()) {
                    val lazy = hasScrollToIndexAction() and hasAnyAncestor(isDialog())
                    if (compose.onAllNodes(lazy).fetchSemanticsNodes(false).size == 1)
                        compose.onNode(lazy).performScrollToNode(terminal)
                    else compose.onNode(terminal).performScrollTo()
                }
                compose.onNode(terminal).assertIsDisplayed()
            }
            terminals.forEach { compose.onNode(it).assertIsDisplayed() }
            capture()
            reviewNote("$step: expanded sheet terminal controls displayed; no terminal action activated.")
        } catch (failure: Throwable) {
            primaryFailure = failure
            runCatching { screenshot("FAILED-$step-expanded") }.exceptionOrNull()?.let(failure::addSuppressed)
            throw failure
        } finally {
            try {
                check(compose.onAllNodes(dismiss).fetchSemanticsNodes(false).size == 1) { "The unique sheet must expose one Dismiss action" }
                compose.onNode(dismiss).performSemanticsAction(SemanticsActions.Dismiss) { check(it()) }
                compose.waitUntil(15_000) { compose.onAllNodes(isDialog()).fetchSemanticsNodes(false).isEmpty() }
            } catch (failure: Throwable) {
                primaryFailure?.addSuppressed(failure) ?: throw failure
            }
        }
    }

    private fun captureConditionalCompanionEntrances() {
        val owner = companions.state.value.conversations.firstOrNull()
        if (owner == null) {
            reviewNote("Restricted companion packs/market/tray/history preview: no existing visible companion conversation.")
            return
        }
        step = "01-companion-sticker-packs"
        compose.onNodeWithTag("native-companion-conversations")
            .performScrollToNode(hasTestTag("native-companion-conversation:${owner.id}"))
        click("native-companion-conversation:${owner.id}", "native-companion-chat")
        compose.waitUntil(15_000) { !companions.state.value.loading && companions.state.value.selectedCompanionId == owner.id }
        click("native-open-companion-profile", "native-companion-profile")
        compose.onNodeWithTag("native-companion-sticker-packs").performScrollTo().performClick()
        waitTag("native-companion-sticker-packs-page"); capture()
        compose.onNodeWithTag("native-companion-sticker-market").performScrollTo()
        if (reviewEnabled("native-companion-sticker-market")) {
            step = "02-companion-sticker-market"
            click("native-companion-sticker-market", "native-companion-market-page")
            compose.waitUntil(30_000) { compose.onAllNodesWithText(text(R.string.native_companion_market_loading))
                .fetchSemanticsNodes(false).isEmpty() }
            capture()
            reviewNote(if (exists("native-companion-market-error"))
                "Sticker market reached; its real metadata-loading error is captured; no install or retry selected."
                else "Sticker market reached after metadata loading; no pack downloaded or installed.")
            backTo("native-companion-sticker-packs-page")
        } else reviewNote("Restricted sticker market: visible entry disabled; no pack configuration changed.")
        backTo("native-companion-profile")
        backTo("native-companion-chat")

        step = "03-companion-sticker-tray"
        click("native-companion-composer-options", "native-companion-choose-attachments"); capture()
        reviewNote("Composer tray viewed only; native-companion-send-sticker controls were never clicked.")
        backTo("native-companion-chat")
        compose.waitUntil(15_000) { !exists("native-companion-choose-attachments") }
        val stickerMessage = companions.state.value.messages.firstOrNull { message ->
            message.role == "user" && message.type == "sticker" && message.attachments.isEmpty() &&
                companions.state.value.stickers.any { it.id == message.stickerId } &&
                exists("native-companion-message:${message.id}")
        }
        val preview = stickerMessage?.let { message -> compose.onAllNodes(hasClickAction() and
            hasAnyAncestor(hasTestTag("native-companion-message:${message.id}")), useUnmergedTree = true) }
        if (preview != null && preview.fetchSemanticsNodes(false).size == 1) {
            step = "04-existing-sticker-preview"
            preview[0].performClick()
            waitTag("native-companion-image-preview"); capture()
            backTo("native-companion-chat")
        } else reviewNote("Restricted history sticker preview: no unambiguous already-displayed existing sticker preview button; no history or message created.")
        backTo("native-companion-messages")
    }

    private fun captureConditionalTaskEntrances(engine: NativeEngineViewModel, standardVoice: NativeStandardVoiceViewModel) {
        root(NativeExternalRoot.WORKBENCH, "native-workbench")
        for ((tag, title, name) in listOf(
            Triple("native-model", R.string.native_work_search_models, "05-task-model"),
            Triple("native-reasoning", R.string.native_reasoning_strength, "06-task-reasoning"))) {
            step = name
            if (reviewEnabled(tag)) {
                compose.onNodeWithTag(tag).performClick()
                waitText(title); capture()
                backTo("native-workbench")
            } else {
                capture()
                reviewNote("Restricted $name: visible control disabled or absent; no model/reasoning selection changed.")
            }
        }
        step = "07-task-usage"
        click("native-context-control", "native-usage-drag-region"); capture()
        backTo("native-workbench")
        compose.waitUntil(15_000) { !exists("native-usage-drag-region") }
        step = "08-task-font"
        compose.onNodeWithTag("native-workbench-menu").performClick()
        waitText(R.string.native_workbench_font)
        compose.onNodeWithText(text(R.string.native_workbench_font)).performScrollTo().performClick()
        waitTag("native-font-slider"); capture()
        backTo("native-workbench")
        compose.waitUntil(15_000) { !exists("native-font-slider") }

        val standardVoiceJournal = context.getSharedPreferences("native_standard_voice", Context.MODE_PRIVATE).getString("journal", null)
        if (standardVoiceJournal == null && standardVoice.state.value.journal == null) {
            step = "09-task-standard-voice"
            compose.onNodeWithTag("native-workbench-menu").performClick()
            waitText(R.string.native_workbench_font)
            val entry = compose.onAllNodesWithText(text(R.string.nsv_title)).fetchSemanticsNodes(false)
            if (entry.isNotEmpty()) {
                compose.onNodeWithText(text(R.string.nsv_title)).performScrollTo().performClick()
                waitTag("native-standard-voice-panel"); capture()
                backTo("native-workbench")
                compose.waitUntil(15_000) { !exists("native-standard-voice-panel") }
                check(context.getSharedPreferences("native_standard_voice", Context.MODE_PRIVATE).getString("journal", null) == null) {
                    "Standard voice review unexpectedly created a voice operation"
                }
            } else {
                capture(); reviewNote("Restricted standard voice: no current menu entry; no voice operation started.")
                backTo("native-workbench")
            }
        } else reviewNote("Restricted standard voice: existing retained voice operation; closing its panel could stop user work.")

        if (!reviewEnabled("native-composer-attachments")) {
            reviewNote("Restricted attachment/picker/Goal/compact entrances: composer attachments disabled or absent.")
            return
        }
        step = "10-task-attachments"
        compose.onNodeWithTag("native-composer-attachments").performClick()
        waitText(R.string.native_share_file); capture()
        backTo("native-workbench")
        for (photos in listOf(false, true)) captureReviewSystemPickerAndCancel(photos)

        val state = work.state.value
        val target = engine.state.value
        val safeExistingSession = state.selectedSessionId != null && state.sessions.any { it.id == state.selectedSessionId } &&
            !state.running && !state.sending && !state.readOnly && state.pendingRequestId == null &&
            target.sessionId == state.selectedSessionId && target.session?.id == state.selectedSessionId &&
            !target.busy && !target.sessionLoading && target.compaction?.active != true
        if (!safeExistingSession) {
            reviewNote("Restricted Goal/compact: no existing idle writable session with a matching loaded engine target; no session created.")
            return
        }
        for ((label, tag, name) in listOf(
            Triple(R.string.native_engine_compact, "native-engine-compact", "13-task-compact"),
            Triple(R.string.native_goal_title, "", "14-task-goal"))) {
            step = name
            val current = work.state.value
            val currentTarget = engine.state.value
            if (current.selectedSessionId != state.selectedSessionId || current.running || current.sending ||
                current.readOnly || current.pendingRequestId != null || currentTarget.sessionId != current.selectedSessionId ||
                currentTarget.session?.id != current.selectedSessionId || currentTarget.busy || currentTarget.sessionLoading ||
                currentTarget.compaction?.active == true) {
                reviewNote("Restricted $name: the existing idle engine target changed before entry; no action requested.")
                continue
            }
            compose.onNodeWithTag("native-composer-attachments").performClick()
            waitText(R.string.native_share_file)
            val entry = hasText(text(label)) and hasClickAction() and isEnabled()
            if (compose.onAllNodes(entry).fetchSemanticsNodes(false).size == 1) {
                compose.onNode(entry).performClick()
                compose.waitUntil(15_000) { compose.onAllNodesWithText(text(R.string.native_share_file)).fetchSemanticsNodes(false).isEmpty() }
                if (tag.isNotEmpty()) waitTag(tag) else waitText(R.string.native_goal_title)
                capture()
            } else reviewNote("Restricted $name: existing-session entry disabled; no engine action requested.")
            backTo("native-workbench")
        }
    }

    private fun captureReviewSystemPickerAndCancel(photos: Boolean) {
        step = if (photos) "12-task-system-photo-picker" else "11-task-system-file-picker"
        lateinit var media: NativeMediaViewModel
        scenario.onActivity { media = ViewModelProvider(it)[NativeMediaViewModel::class.java] }
        check(activeReviewPackage() == context.packageName && media.state.value.phase == null && !media.state.value.busy) {
            "Picker review requires the foreground owner with no existing media operation"
        }
        val owner = work.state.value
        val expectedPackages = reviewPickerPackages(photos)
        compose.onNodeWithTag("native-composer-attachments").performClick()
        waitText(R.string.native_share_file)
        var launched = false
        var primaryFailure: Throwable? = null
        try {
            launched = true
            compose.onNodeWithText(text(if (photos) R.string.native_work_photos else R.string.native_share_file)).performClick()
            var packageName: String? = null
            compose.waitUntil(15_000) {
                packageName = activeReviewPackage()
                packageName != null && packageName != context.packageName
            }
            val actualPackage = checkNotNull(packageName)
            check(isReviewSystemPicker(actualPackage, expectedPackages) && media.state.value.let {
                it.kind == NativeMediaKind.WORKBENCH_DRAFT && it.ownerId == owner.draftId && it.phase == NativeMediaPhase.PICKING
            } && NativeRuntimeHost.isFilePickerOpen()) {
                "Picker did not open its expected owned system surface: ${reviewPickerDiagnostic(actualPackage, expectedPackages, media, owner.draftId)}"
            }
            screenshot(step)
            reviewNote("$step.png: system picker package=$actualPackage; no content selected.")
        } catch (failure: Throwable) {
            primaryFailure = failure
            val diagnostic = reviewPickerDiagnostic(activeReviewPackage(), expectedPackages, media, owner.draftId)
            failure.addSuppressed(IllegalStateException("Picker failure: $diagnostic"))
            runCatching {
                screenshot("FAILED-$step-picker")
                reviewNote("$step: $diagnostic")
            }.exceptionOrNull()?.let(failure::addSuppressed)
            throw failure
        } finally {
            try {
                if (launched) {
                    // Re-read even after timeout or package validation failure; never Back an unknown foreground app.
                    repeat(2) { attempt ->
                        val currentPackage = activeReviewPackage()
                        val ownedPicker = media.state.value.kind == NativeMediaKind.WORKBENCH_DRAFT &&
                            media.state.value.ownerId == owner.draftId && NativeRuntimeHost.isFilePickerOpen()
                        if (ownedPicker && isReviewSystemPicker(currentPackage, expectedPackages)) {
                            val source = cancelReviewSystemPicker(checkNotNull(currentPackage))
                            reviewNote("$step: cancellation attempt=${attempt + 1} source=$source.")
                            SystemClock.sleep(300)
                        }
                    }
                    compose.waitUntil(15_000) { activeReviewPackage() == context.packageName &&
                        media.state.value.phase == null && !media.state.value.busy && !NativeRuntimeHost.isFilePickerOpen() }
                    waitTag("native-workbench")
                    check(work.state.value.draftId == owner.draftId && work.state.value.selectedSessionId == owner.selectedSessionId &&
                        work.state.value.selectedWorkspaceId == owner.selectedWorkspaceId) { "Cancelled picker did not return to its original owner" }
                    reviewNote("$step: picker operation cancelled and original Droy owner restored; no content selected.")
                }
            } catch (failure: Throwable) {
                failure.addSuppressed(IllegalStateException("Picker cleanup: ${reviewPickerDiagnostic(activeReviewPackage(), expectedPackages, media, owner.draftId)}"))
                primaryFailure?.addSuppressed(failure) ?: throw failure
            }
        }
    }

    private fun reviewPickerPackages(photos: Boolean): Set<String> {
        val intents = mutableListOf(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
            .setType(if (photos) "image/*" else "*/*"))
        // HyperOS advertises this action for the system picker it substitutes for ACTION_OPEN_DOCUMENT.
        intents += Intent("hyper.intent.action.OPEN_DOCUMENT").addCategory(Intent.CATEGORY_OPENABLE)
            .setType(if (photos) "image/*" else "*/*")
        if (photos) intents += Intent(MediaStore.ACTION_PICK_IMAGES).setType("image/*")
        intents += Intent("android.content.pm.action.REQUEST_PERMISSIONS")
        return buildSet {
            for (intent in intents) {
                context.packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName?.let(::add)
                context.packageManager.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
                    .forEach { add(it.activityInfo.packageName) }
            }
        }
    }

    private fun isReviewSystemPicker(packageName: String?, expected: Set<String>): Boolean {
        if (packageName == null || packageName == context.packageName || packageName !in expected) return false
        val application = try { context.packageManager.getApplicationInfo(packageName, 0) }
        catch (_: PackageManager.NameNotFoundException) { return false }
        return application.flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
    }

    private fun reviewPickerDiagnostic(packageName: String?, expected: Set<String>, media: NativeMediaViewModel, owner: String): String {
        val state = media.state.value
        return "actual=$packageName expected=${expected.sorted()} kind=${state.kind} phase=${state.phase} " +
            "ownerMatches=${state.ownerId == owner} pickerOpen=${NativeRuntimeHost.isFilePickerOpen()} " +
            "acceptedSystemPackage=${isReviewSystemPicker(packageName, expected)}"
    }

    private fun cancelReviewSystemPicker(packageName: String): String {
        val root = checkNotNull(instrumentation.uiAutomation.rootInActiveWindow) { "The owned system picker has no active window" }
        val candidates = mutableListOf<AccessibilityNodeInfo>()
        fun collect(node: AccessibilityNodeInfo) {
            if (node.packageName?.toString() == packageName && node.isVisibleToUser && node.isEnabled && node.isClickable &&
                node.contentDescription?.toString() in setOf("取消", "Cancel")) candidates += AccessibilityNodeInfo.obtain(node)
            for (index in 0 until node.childCount) {
                val child = node.getChild(index) ?: continue
                try { collect(child) } finally { child.recycle() }
            }
        }
        try {
            check(root.packageName?.toString() == packageName) { "The foreground window changed before picker cancellation" }
            collect(root)
            check(candidates.size <= 1) { "The owned system picker exposes multiple exact Cancel controls; no control clicked" }
            if (candidates.size == 1) {
                check(candidates.single().performAction(AccessibilityNodeInfo.ACTION_CLICK)) { "The owned system picker rejected its exact Cancel action" }
                return "exact-visible-cancel-control"
            }
            check(instrumentation.uiAutomation.performGlobalAction(
                android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)) {
                "The owned system picker did not accept the global Back action"
            }
            return "global-back-no-exact-cancel-control"
        } finally {
            candidates.forEach { it.recycle() }
            root.recycle()
        }
    }

    private fun activeReviewPackage(): String? {
        val root = instrumentation.uiAutomation.rootInActiveWindow ?: return null
        return try { root.packageName?.toString() } finally { root.recycle() }
    }

    private fun captureConditionalPetStudio() {
        step = "15-pet-studio"
        val petRoot = File(context.filesDir, "dsh-home/pets")
        val existing = if (petRoot.exists()) petRoot.listFiles() else emptyArray()
        if (existing == null || existing.any { it.isDirectory && it.name != "default" && PetPackage.validId(it.name) }) {
            reviewNote("Restricted pet studio: existing candidate directory or unreadable catalog root; studio list could reconcile and save user candidates.")
            return
        }
        root(NativeExternalRoot.SETTINGS, "native-settings-general")
        settingsEntry("pet", "native-product-settings:pet")
        waitTag("native-product-body:pet")
        compose.onNodeWithTag("native-product-list:pet")
            .performScrollToNode(hasTestTag("native-product-pet-studio-open"))
        if (reviewEnabled("native-product-pet-studio-open")) {
            click("native-product-pet-studio-open", "native-pet-studio")
            compose.waitUntil(30_000) { !exists("native-pet-studio-loading") }
            capture()
            reviewNote(if (exists("native-pet-studio-error")) "Pet studio reached with its current loading error captured."
                else "Pet studio reached; no apply, edit or generate selected.")
            compose.onNodeWithTag("native-pet-studio-list").performScrollToNode(hasTestTag("native-pet-studio-form"))
            step = "15a-pet-studio-generation-form"; capture()
            click("native-pet-studio-back", "native-product-body:pet")
        } else reviewNote("Restricted pet studio: visible entry disabled.")
        click("native-product-back", "native-settings-general")
    }

    private fun captureConditionalCreationEditor(creation: NativeCompanionCreationViewModel?) {
        val savedJournal = context.getSharedPreferences("native_companion_creation", Context.MODE_PRIVATE).getString("journal", null)
        if (savedJournal != null || creation == null) {
            reviewNote("Restricted template editor: existing persisted creation journal; creation page not entered or resumed.")
            return
        }
        val before = creation.state.value
        if (!before.restored || !before.canEdit || before.journal != null || before.editor != null || before.createdId != null) {
            reviewNote("Restricted template editor: state not confirmed clean/restored or an in-memory journal exists; creation page not entered.")
            return
        }
        step = "16-companion-template-editor"
        root(NativeExternalRoot.MESSAGES, "native-companion-messages")
        compose.onNodeWithTag("native-add-companion").performClick()
        waitTag("native-creation-templates")
        compose.waitUntil(20_000) { !creation.state.value.loading &&
            (creation.state.value.catalogLoaded || creation.state.value.catalogError != null) }
        val template = creation.state.value.templates.firstOrNull()
        if (template == null) {
            capture(); reviewNote("Restricted concrete template editor: template catalog empty or unavailable; current state captured.")
            backTo("native-companion-messages")
            return
        }
        compose.onNodeWithTag("native-creation-templates")
            .performScrollToNode(hasTestTag("native-creation-template-${template.id}"))
        click("native-creation-template-${template.id}", "native-creation-editor")
        try {
            check(creation.state.value.editor?.dirty == false && creation.state.value.journal?.draftId == null) {
                "Only a clean in-memory template editor may be reviewed without an existing journal"
            }
            capture()
        } finally {
            backTo("native-creation-templates")
            compose.waitUntil(15_000) { creation.state.value.journal == null }
        }
        backTo("native-companion-messages")
    }

    private fun reviewEnabled(tag: String) = compose.onAllNodes(hasTestTag(tag) and isEnabled())
        .fetchSemanticsNodes(false).isNotEmpty()

    private fun reviewNote(note: String) = File(directory, "capture-index.txt").appendText("$note\n")

    private fun openSafeReviewProvider() {
        val provider = settings.state.value.configuredProviders.singleOrNull { it.name == "DroyLocalReview" }
        if (provider != null) {
            compose.onNodeWithTag("native-settings-providers")
                .performScrollToNode(hasTestTag("native-settings-provider:${provider.id}"))
            click("native-settings-provider:${provider.id}", "native-settings-provider-editor")
        } else {
            compose.onNodeWithTag("native-settings-providers").performScrollToNode(hasTestTag("native-settings-add-provider"))
            compose.onNodeWithTag("native-settings-add-provider").assertIsEnabled().performClick()
            waitTag("native-settings-custom-provider")
            val editorStep = step
            step = "$editorStep-provider-chooser"; capture(); step = editorStep
            click("native-settings-custom-provider", "native-settings-provider-editor")
            check(settings.state.value.editor?.isNew == true) { "Only an unsaved empty provider may replace the prepared loopback provider" }
            File(directory, "capture-index.txt").appendText("$step: DroyLocalReview absent; opened an unsaved empty third-party provider, never an existing credentialed provider.\n")
        }
        compose.waitUntil(15_000) { settings.state.value.editor != null && !settings.state.value.loading }
    }

    private fun openSafeReviewModel() {
        val draft = requireNotNull(settings.state.value.editor)
        val local = settings.providerModels(draft).singleOrNull { it.id == "droy-review-local" }
        if (!draft.isNew && local != null) {
            check(local.inputModalities == null) { "The prepared local model must inherit input modalities" }
            compose.onNodeWithTag("native-settings-provider-editor").performScrollToNode(hasText(local.id))
            compose.onNodeWithText(local.id).performClick()
        } else {
            compose.onNodeWithTag("native-settings-provider-editor").performScrollToNode(hasTestTag("native-settings-manual-model"))
            compose.onNodeWithTag("native-settings-manual-model").assertIsEnabled().performClick()
        }
        waitTag("native-settings-manual-model-id")
    }

    private fun captureNotificationPages(name: String) {
        step = name
        settingsEntry("notifications", "native-product-settings:notifications")
        waitTag("native-product-body:notifications"); capture()
        compose.onNodeWithTag("native-product-list:notifications")
            .performScrollToNode(hasTestTag("native-notification-organization"))
        step = "$name-organization"; capture()
        compose.onNodeWithTag("native-product-field:nps_app_management").performScrollTo().performClick()
        waitTag("native-notification-app-search")
        step = "$name-apps"; capture()
        compose.onNodeWithTag("native-product-back").performClick()
        waitTag("native-product-field:nps_rules")
        compose.onNodeWithTag("native-product-field:nps_rules").performScrollTo().performClick()
        waitText(R.string.nps_restore_default)
        step = "$name-rules-read-only"; capture()
        backTo("native-product-field:nps_rules")
        waitTag("native-product-body:notifications")
        compose.onNodeWithTag("native-product-field:nps_archive").performScrollTo().performClick()
        waitText(R.string.nps_archive)
        step = "$name-history"; capture()
        compose.onNodeWithTag("native-product-back").performClick()
        waitTag("native-product-field:nps_archive")
        compose.onNodeWithTag("native-product-back").performClick()
        waitTag("native-settings-general")
    }

    private fun capturePluginPages() {
        step = "29-plugins"
        settingsEntry("plugins", "native-settings-group"); capture()
        compose.onNodeWithTag("native-settings-group:session-titles").performScrollTo().assertIsDisplayed()
        step = "29a-plugins-bottom"; capture()
        val pages = listOf(
            Triple("web-search", "native-search-new-api", "30-plugin-web-search"),
            Triple("subtask-models", "native-subagent-depth", "31-plugin-subtask-models"),
            Triple("agent-loop", "native-advanced", "32-plugin-agent-loop"),
            Triple("agent-team", "native-approved-plugin:agent-team", "33-plugin-agent-team"),
            Triple("terminal", "native-advanced", "34-plugin-terminal"),
            Triple("persistent-terminal", "native-approved-plugin:persistent-terminal", "35-plugin-persistent-terminal"),
            Triple("session-titles", "native-approved-plugin:session-titles", "36-plugin-session-titles"),
        )
        for ((id, destination, name) in pages) {
            step = name
            compose.onNodeWithTag("native-settings-group:$id").performScrollTo().assertIsEnabled().performClick()
            waitTag(destination)
            compose.waitForIdle()
            when (id) {
                "agent-loop", "terminal" -> {
                    val section = if (id == "terminal") "bash-sandbox" else id
                    compose.waitUntil(15_000) { advanced.state.value.let { !it.loading && !it.busy &&
                        (it.draft?.section?.id == section || it.error != null || it.ready && it.sections.none { row -> row.id == section }) } }
                }
                "agent-team", "persistent-terminal", "session-titles" ->
                    compose.waitUntil(15_000) { advanced.state.value.let { !it.loading && !it.busy && (it.approvedLoaded || it.error != null) } }
                else -> compose.waitUntil(15_000) { !settings.state.value.loading }
            }
            capture()
            if (id == "web-search") {
                compose.onNodeWithTag("native-settings-browser-network").performScrollTo().assertIsEnabled().performClick()
                waitTag("native-settings-browser-encrypted-dns")
                step = "$name-browser-network"; capture()
                backTo("native-search-new-api")
            }
            if (id in setOf("agent-loop", "terminal")) {
                scrollToBottom("native-advanced")
                step = "$name-bottom"; capture()
            }
            if (id == "agent-team" && exists("native-approved-team-open")) {
                click("native-approved-team-open", "native-agent-team")
                step = "$name-members"; capture()
                backTo("native-approved-plugin:agent-team")
            }
            backTo("native-settings-group")
            File(directory, "capture-index.txt").appendText("$name: returned to the visible plugin catalog.\n")
        }
        backTo("native-settings-general")
    }

    private fun scrollToBottom(tag: String) {
        val list = compose.onNodeWithTag(tag)
        repeat(12) {
            val before = list.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()
            list.performSemanticsAction(SemanticsActions.ScrollBy) { scroll -> scroll(0f, 10_000f) }
            compose.waitForIdle()
            val range = list.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange]
            if (range.value() == before || range.value() >= range.maxValue()) return
        }
        error("The visible settings list did not reach its last item: $tag")
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
        scrollToBottom("native-product-list:$wire")
        step = "$name-bottom"; capture()
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
