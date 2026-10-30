package io.droyagent.app

import android.content.res.Configuration
import android.os.SystemClock
import android.view.KeyEvent
import android.view.inputmethod.InputMethodManager
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.droyagent.app.nativeui.companion.data.NativeCompanionViewModel
import io.droyagent.app.nativeui.permissions.NativeAuthorizationPolicy
import io.droyagent.app.nativeui.settings.NativeSettingsViewModel
import io.droyagent.app.nativeui.setup.NativeOnboardingViewModel
import io.droyagent.app.nativeui.data.NativeWorkbenchViewModel
import java.io.File
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.rules.TestWatcher
import org.junit.runner.Description

/** Later wizard pages are inspected separately: this fixture has no configurable provider. */
@RunWith(AndroidJUnit4::class)
class NativeBatchEightGalleryDeviceTest {
    @get:Rule(order = 0) val compose = createEmptyComposeRule()
    @get:Rule(order = 1) val fixture = NativeIsolatedRuntimeFixture("settings", allowRoot = false)
    @get:Rule(order = 2) val failureEvidence = object : TestWatcher() {
        override fun failed(failure: Throwable, description: Description) {
            val name = "native-batch8-failed-$language-${description.methodName}"
            try { fixture.capture(name) } catch (diagnostic: Throwable) { failure.addSuppressed(diagnostic) }
            try {
                File(fixture.context.getExternalFilesDir(null), "native-isolated-evidence/$name.txt")
                    .writeText(compose.onRoot(useUnmergedTree = true).printToString())
            } catch (diagnostic: Throwable) { failure.addSuppressed(diagnostic) }
        }
    }
    private lateinit var settings: NativeSettingsViewModel
    private lateinit var setup: NativeOnboardingViewModel
    private lateinit var companions: NativeCompanionViewModel
    private lateinit var work: NativeWorkbenchViewModel
    private var language = "en"

    @Test fun englishPagesAndSavedIdentity() = inspectBatch("en")
    @Test fun chinesePagesAndSavedIdentity() = inspectBatch("zh")

    private fun inspectBatch(locale: String) {
        language = locale
        bind()
        compose.waitUntil(30_000) { settings.state.value.loaded && settings.state.value.writable && !settings.state.value.loading && settings.state.value.runtime.ready }
        fixture.scenario.onActivity { settings.setLanguage(locale) }
        compose.waitUntil(20_000) { NativeLocale.active() == locale && settings.state.value.locale == locale &&
            work.state.value.runtime.locale == locale && !settings.state.value.saving }
        assertTrue(fixture.context.getSharedPreferences("product", 0).edit().putString("appearanceMode", "light").commit())
        inspectBackground()
        startWizard()
        capture("01-welcome")
        compose.onNodeWithTag("native-setup-back").assertDoesNotExist()
        next(2)
        compose.waitUntil(20_000) { setup.state.value.route?.environment?.ready == true }
        capture("02-environment")
        compose.onNodeWithTag("native-setup-previous").assertDoesNotExist()
        next(3)
        awaitTag("native-settings-add-provider")
        compose.waitUntil(20_000) { settings.state.value.loaded && !settings.state.value.loading }
        compose.onNodeWithTag("native-setup-next").assertIsNotEnabled()
        compose.onNodeWithTag("native-settings-test-model").assertDoesNotExist()
        capture("03-model-empty")
        compose.onNodeWithTag("native-settings-add-provider").performScrollTo().performClick()
        awaitTag("native-settings-custom-provider")
        capture("03-provider-chooser")
        back()
        compose.waitUntil(10_000) { !exists("native-settings-custom-provider") }
        assertEquals("Closing the chooser must stay on model setup", 3, setup.state.value.route?.step)
        // Deliberately open step 4 without model proofs to inspect later layouts. Finish
        // must still reject this incomplete setup; no fake pass or paid call is made.
        NativeSetupHost.setStep(fixture.context, 4)
        fixture.scenario.onActivity { setup.refresh() }
        awaitStep(4)
        awaitTag("native-setup-bio")
        val name = "Droy Review"
        val bio = if (locale == "zh") "喜欢阅读、散步和尝试新事物。\n仅用于界面验证的虚构简介。" else "I enjoy reading, walking and trying new things.\nFictional profile for UI review."
        compose.onNodeWithTag("native-setup-name").performTextReplacement(name)
        compose.onNodeWithTag("native-setup-bio").performScrollTo().performTextReplacement(bio)
        hideKeyboard()
        capture("04-identity-bio")
        next(5)
        val persisted = request("/user-profile")
        assertEquals(name, persisted.getString("name"))
        assertEquals(bio, persisted.getString("bio"))
        compose.onNodeWithTag("native-setup-back").performClick()
        awaitStep(4)
        fixture.recreate()
        bind()
        awaitStep(4)
        awaitTag("native-setup-bio")
        compose.onNodeWithTag("native-setup-name").assertTextContains(name)
        compose.onNodeWithTag("native-setup-bio").assertTextContains(bio)
        next(5)
        awaitTag("native-permissions-first-use")
        compose.onNodeWithTag("native-setup-finish").assertIsNotEnabled()
        openSystemPermissions()
        awaitTag("native-permissions-system:camera")
        compose.onNodeWithTag("native-setup-back").assertDoesNotExist()
        compose.onNodeWithTag("native-setup-finish").assertDoesNotExist()
        compose.onNodeWithTag("native-permissions-back").assertIsDisplayed()
        capture("05-permissions-child")
        compose.onNodeWithTag("native-permissions-back").performClick()
        awaitTag("native-permissions-content")
        compose.onNodeWithTag("native-permissions-content").performScrollToNode(hasTestTag("native-permissions-first-use"))
        compose.onNodeWithTag("native-permissions-first-use").assertIsDisplayed()
        assertEquals(5, setup.state.value.route?.step)
        // Hardware back must close the same child rather than leaving the wizard step.
        openSystemPermissions()
        awaitTag("native-permissions-system:camera")
        back()
        awaitTag("native-permissions-content")
        compose.onNodeWithTag("native-permissions-content").performScrollToNode(hasTestTag("native-permissions-first-use"))
        compose.onNodeWithTag("native-permissions-first-use").assertIsDisplayed()
        assertEquals(5, setup.state.value.route?.step)
        compose.onNodeWithTag("native-permissions-content").performScrollToNode(hasTestTag("native-permissions-first-use"))
        compose.onNodeWithTag("native-permissions-first-use").performClick()
        compose.waitUntil(15_000) { setup.state.value.route?.permissionPolicy == NativeAuthorizationPolicy.ON_FIRST_USE }
        compose.onNodeWithTag("native-setup-finish").assertIsEnabled()
        capture("05-permissions-finish")
        compose.onNodeWithTag("native-setup-finish").performClick()
        awaitStep(3)
        compose.onNodeWithTag("native-setup-error").assertExists()
        compose.onNodeWithTag("native-setup-check:modelTools").assertDoesNotExist()
        assertFalse("Incomplete model configuration must not complete setup", fixture.context.getSharedPreferences("installation", 0).getBoolean("completed", false))
        assertEquals("Failed finish must preserve the saved bio", bio, request("/user-profile").getString("bio"))
        for (nameOfLog in listOf("native-fixture-calls.jsonl", "native-fixture-companion.jsonl")) {
            val calls = File(fixture.home, nameOfLog)
            assertTrue("UI review and completion checks must not call models: $nameOfLog", !calls.exists() || calls.length() == 0L)
        }
    }

    private fun startWizard() {
        assertTrue(fixture.context.getSharedPreferences("installation", 0).edit()
            .putBoolean("completed", false).putInt("nativeWizardStep", 1).remove("nativeUserConfirmation").commit())
        assertTrue(fixture.context.getSharedPreferences("native_setup_draft", 0).edit().clear().commit())
        fixture.relaunch("messages")
        bind()
        awaitStep(1)
    }

    private fun inspectBackground() {
        val id = request(method = "POST", body = JSONObject().put("requestId", UUID.randomUUID().toString())
            .put("templateId", "maimai").put("name", "Background Review")
            .put("model", JSONObject().put("provider", "droy-native-fixture").put("model", "native-alternate"))
            .put("settings", JSONObject().put("proactiveEnabled", false).put("memoryEnabled", true).put("allowProfanity", true)
                .put("quietHours", JSONObject().put("enabled", true).put("start", "22:00").put("end", "09:00"))))
            .getJSONObject("profile").getString("id")
        fixture.scenario.onActivity { companions.refresh() }
        compose.waitUntil(20_000) { companions.state.value.companions.any { it.id == id } }
        compose.onNodeWithTag("native-tab-messages").performClick()
        awaitTag("native-companion-conversation:$id")
        compose.onNodeWithTag("native-companion-conversation:$id").performClick()
        awaitTag("native-open-companion-profile")
        compose.onNodeWithTag("native-open-companion-profile").performClick()
        awaitTag("native-companion-profile")
        compose.onNodeWithTag("native-companion-interaction").performScrollTo().performClick()
        awaitTag("native-companion-interaction-save")
        openBackground()
        val original = savedProfile(id).optJSONObject("chatBackground")?.toString()
        compose.onNodeWithTag("native-background-option-01").performScrollTo().performClick()
        compose.onNodeWithTag("native-background-option-01").assertIsSelected()
        assertEquals("Selection previews without saving", original, savedProfile(id).optJSONObject("chatBackground")?.toString())
        capture("background-selected")
        compose.onNodeWithTag("native-background-apply").performClick()
        compose.waitUntil(20_000) { !exists("native-background-apply") && !companions.state.value.profileSaving }
        assertNull(companions.state.value.error)
        assertEquals("01", savedProfile(id).getJSONObject("chatBackground").getString("id"))
        openBackground()
        compose.onNodeWithTag("native-background-option-01").assertIsSelected()
        back()
        awaitTag("native-companion-interaction-save")
    }

    private fun openBackground() {
        compose.onNodeWithTag("native-companion-background").performScrollTo().performClick()
        awaitTag("native-background-apply")
    }
    private fun bind() {
        fixture.scenario.onActivity {
            val models = ViewModelProvider(it)
            settings = models[NativeSettingsViewModel::class.java]
            setup = models[NativeOnboardingViewModel::class.java]
            companions = models[NativeCompanionViewModel::class.java]
            work = models[NativeWorkbenchViewModel::class.java]
        }
    }
    private fun next(step: Int) { compose.onNodeWithTag("native-setup-next").assertIsEnabled().performClick(); awaitStep(step) }
    private fun openSystemPermissions() {
        val label = localized(R.string.native_permissions_system_permissions)
        // This link is a late LazyColumn item. Ask the list to compose and reveal it
        // before resolving the real localized navigation control.
        compose.onNodeWithTag("native-permissions-content").performScrollToNode(hasText(label))
        compose.onNode(hasText(label) and hasClickAction()).assertIsDisplayed().assertIsEnabled().performClick()
        awaitTag("native-permissions-back")
        compose.onNodeWithTag("native-permissions-content").performScrollToNode(hasTestTag("native-permissions-system:camera"))
        compose.onNodeWithTag("native-permissions-system:camera").assertIsDisplayed()
    }
    private fun awaitStep(step: Int) { compose.waitUntil(20_000) { setup.state.value.route?.step == step && !setup.state.value.busy }; settle() }
    private fun hideKeyboard() {
        fixture.scenario.onActivity { activity ->
            activity.currentFocus?.clearFocus()
            activity.getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(activity.window.decorView.windowToken, 0)
        }
        settle()
    }
    private fun localized(resource: Int) = fixture.context.createConfigurationContext(Configuration(fixture.context.resources.configuration)
        .apply { setLocale(Locale.forLanguageTag(language)) }).getString(resource)
    private fun savedProfile(id: String): JSONObject = request().getJSONArray("companions").let { rows ->
        (0 until rows.length()).map(rows::getJSONObject).single { it.getString("id") == id }
    }
    private fun request(path: String = "", method: String = "GET", body: JSONObject? = null): JSONObject = runBlocking {
        fixture.runtimeTransport.json("/droy/companions$path", method, body)
    }
    private fun exists(tag: String) = compose.onAllNodesWithTag(tag).fetchSemanticsNodes(false).isNotEmpty()
    private fun awaitTag(tag: String) { compose.waitUntil(20_000) { exists(tag) }; settle() }
    private fun back() { InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK); settle() }
    private fun settle() { compose.waitForIdle(); SystemClock.sleep(500); compose.waitForIdle() }
    private fun capture(scene: String) { settle(); fixture.capture("batch8-$scene-$language") }
}
