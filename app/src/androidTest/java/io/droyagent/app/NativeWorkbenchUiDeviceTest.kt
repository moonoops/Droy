package io.droyagent.app

import android.app.UiModeManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.webkit.WebView
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.droyagent.app.nativeui.companion.data.NativeCompanionViewModel
import io.droyagent.app.nativeui.data.NativeWorkbenchViewModel
import io.droyagent.app.nativeui.settings.NativeSettingsViewModel
import java.io.File
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestWatcher
import org.junit.rules.ExternalResource
import org.junit.runner.Description
import org.junit.runner.RunWith

/** Native foreground entry starts the isolated local engine without sending a prompt. */
@RunWith(AndroidJUnit4::class)
class NativeWorkbenchUiDeviceTest {
    @get:Rule(order = 0) val compose = createEmptyComposeRule()
    @get:Rule(order = 1) val fixture = NativeIsolatedRuntimeFixture()
    @get:Rule(order = 2) val systemTheme = object : ExternalResource() {
        override fun after() = restoreSystemTheme()
    }
    @get:Rule(order = 3) val failureEvidence = object : TestWatcher() {
        override fun failed(failure: Throwable, description: Description) {
            val name = "native-ui-failed-${description.methodName}"
            try { fixture.capture(name) } catch (diagnostic: Throwable) { failure.addSuppressed(diagnostic) }
            try {
                File(context.getExternalFilesDir(null), "native-isolated-evidence/$name.txt")
                    .writeText(compose.onRoot(useUnmergedTree = true).printToString())
            } catch (diagnostic: Throwable) { failure.addSuppressed(diagnostic) }
        }
    }
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = fixture.context
    private val scenario get() = fixture.scenario
    private lateinit var work: NativeWorkbenchViewModel
    private lateinit var settings: NativeSettingsViewModel
    private lateinit var companions: NativeCompanionViewModel
    private var originalNightMode = UiModeManager.MODE_NIGHT_AUTO
    private var ownsNightMode = false

    @Before fun prepare() {
        originalNightMode = context.getSystemService(UiModeManager::class.java).nightMode
        assertTrue("Restorable emulator theme required", originalNightMode in setOf(
            UiModeManager.MODE_NIGHT_AUTO, UiModeManager.MODE_NIGHT_NO, UiModeManager.MODE_NIGHT_YES))
        ownsNightMode = true
        // The shared fixture owns the complete home and preference backup. Its foreground
        // launch has already booted the local provider through the production startup path.
        assertTrue(RuntimeService.ready)
        assertTrue(File(fixture.home, "native-fixture-ready.json").isFile)
        assertTrue(context.getSharedPreferences("product", 0).edit().putString("appearanceMode", "system")
            .putString("colorSource", "preset").putString("themePalette", "purple").commit())
        setNight(false)
        bindModels()
        awaitRuntime()
        setLanguage("zh")
    }

    private fun restoreSystemTheme() {
        if (!ownsNightMode) return
        val night = when (originalNightMode) {
            UiModeManager.MODE_NIGHT_YES -> "yes"
            UiModeManager.MODE_NIGHT_NO -> "no"
            else -> "auto"
        }
        shell("cmd uimode night $night")
        instrumentation.waitForIdleSync()
        ownsNightMode = false
        // NativeIsolatedRuntimeFixture stops the engine before restoring home/preferences.
    }

    @Test fun nativeColdUiRetainsDraftAcrossRecreationAndFollowsLocaleAndTheme() {
        compose.onNodeWithTag("native-tab-workbench").assertIsDisplayed().performClick()
        awaitWorkbench("zh")
        compose.onNodeWithTag("native-start-runtime").assertDoesNotExist()
        scenario.onActivity { activity ->
            assertEquals("Native entry must not host a hidden browser", 0, countWebViews(activity.window.decorView))
            assertTrue("The native surface must count as sensitive UI", NativeRuntimeHost.isVisible())
        }
        assertTrue("Foreground entry must start the local engine automatically", RuntimeService.isStartingOrRunning())
        capture("cold-zh-light", expectedSurface = 0xFFFEF7FF.toInt())

        compose.onNodeWithTag("native-tab-messages").performClick()
        awaitTag("native-open-user-profile")
        compose.onNodeWithTag("native-open-user-profile").performClick()
        awaitTag("native-user-name")
        compose.waitUntil(15_000) { companions.state.value.runtime.ready && !companions.state.value.loading && !companions.state.value.profileSaving }
        compose.onNodeWithTag("native-user-name").assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithTag("native-companion-start-runtime").assertDoesNotExist()
        scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        awaitTag("native-root-navigation")
        compose.onNodeWithTag("native-tab-workbench").performClick()
        awaitWorkbench("zh")

        val draft = "native-ui-fixture-draft"
        compose.onNodeWithTag("native-composer").assertIsEnabled().performClick().performTextReplacement(draft)
        compose.onNodeWithTag("native-composer").assertTextContains(draft)
        compose.waitUntil(timeoutMillis = 10_000) { isImeVisible() }
        capture("cold-ime-draft")
        fixture.recreate()
        bindModels()
        awaitRuntime()
        awaitWorkbench("zh")
        compose.onNodeWithTag("native-composer").assertTextContains(draft)
        scenario.onActivity { activity ->
            assertEquals(0, countWebViews(activity.window.decorView))
            assertTrue(NativeRuntimeHost.isVisible())
            WindowCompat.getInsetsController(activity.window, activity.window.decorView).hide(WindowInsetsCompat.Type.ime())
        }
        compose.waitUntil(timeoutMillis = 10_000) { !isImeVisible() }
        capture("cold-recreated-draft")

        setLanguage("en")
        setNight(true)
        awaitWorkbench("en")
        compose.onNodeWithTag("native-composer").assertTextContains(draft)
        compose.onNodeWithTag("native-start-runtime").assertDoesNotExist()
        capture("cold-en-dark", expectedSurface = 0xFF151218.toInt())
        assertTrue("The local engine remains available across theme and Activity changes", RuntimeService.ready)
        val calls = File(fixture.home, "native-fixture-calls.jsonl")
        assertTrue("Navigation, drafts and appearance changes must not call a model", !calls.exists() || calls.length() == 0L)
    }

    private fun bindModels() = scenario.onActivity {
        val models = ViewModelProvider(it)
        work = models[NativeWorkbenchViewModel::class.java]
        settings = models[NativeSettingsViewModel::class.java]
        companions = models[NativeCompanionViewModel::class.java]
    }
    private fun awaitRuntime() {
        compose.waitUntil(30_000) { RuntimeService.ready && work.state.value.runtime.ready && work.state.value.workspaceResolved &&
            settings.state.value.loaded && settings.state.value.writable && !settings.state.value.loading }
    }
    private fun setLanguage(locale: String) {
        scenario.onActivity { settings.setLanguage(locale) }
        compose.waitUntil(20_000) { settings.state.value.locale == locale && !settings.state.value.saving &&
            work.state.value.runtime.locale == locale && NativeLocale.active() == locale }
        assertNull(settings.state.value.error)
    }
    private fun awaitWorkbench(locale: String) {
        awaitTag("native-workbench")
        compose.waitUntil(15_000) { compose.onAllNodes(hasTestTag("native-workbench-title") and
            hasText(localized(locale, R.string.native_workbench))).fetchSemanticsNodes(false).isNotEmpty() }
        compose.onNodeWithTag("native-workbench").assertIsDisplayed()
        compose.onNodeWithTag("native-workbench-title").assertIsDisplayed().assertTextContains(localized(locale, R.string.native_workbench))
        compose.onNodeWithTag("native-composer").assertIsDisplayed().assertIsEnabled()
    }
    private fun awaitTag(tag: String) = compose.waitUntil(15_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes(false).isNotEmpty() }

    private fun isImeVisible(): Boolean {
        val visible = AtomicBoolean()
        scenario.onActivity { visible.set(it.window.decorView.rootWindowInsets?.isVisible(WindowInsets.Type.ime()) == true) }
        return visible.get()
    }

    private fun setNight(dark: Boolean) {
        shell("cmd uimode night ${if (dark) "yes" else "no"}")
        instrumentation.waitForIdleSync()
        val expected = if (dark) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
        compose.waitUntil(timeoutMillis = 10_000) {
            (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == expected
        }
    }

    private fun capture(name: String, expectedSurface: Int? = null) {
        compose.waitForIdle()
        instrumentation.waitForIdleSync()
        if (expectedSurface != null) awaitStableSurface(expectedSurface)
        val image = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        try {
            val directory = File(context.getExternalFilesDir(null), "native-ui-evidence")
            check(directory.isDirectory || directory.mkdirs())
            File(directory, "$name.png").outputStream().use { assertTrue(image.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            if (expectedSurface != null) {
                assertTrue("Expected the system-selected semantic surface", surfaceMatches(image, expectedSurface))
            }
        } finally {
            image.recycle()
        }
    }

    private fun awaitStableSurface(expectedSurface: Int) {
        var stableSince: Long? = null
        // Activity recreation can leave an idle Compose tree under a compositor cross-fade.
        compose.waitUntil(timeoutMillis = 10_000) {
            val sample = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
            val matches = try { surfaceMatches(sample, expectedSurface) } finally { sample.recycle() }
            val now = SystemClock.elapsedRealtime()
            if (!matches) {
                stableSince = null
                false
            } else {
                val firstMatch = stableSince ?: now.also { stableSince = it }
                now - firstMatch >= 300L
            }
        }
    }

    private fun surfaceMatches(image: Bitmap, expected: Int): Boolean {
        // Sample the unobstructed scaffold edge; the current empty page has a central welcome illustration.
        val actual = image.getPixel(image.width / 100, image.height * 3 / 5)
        return listOf(0, 8, 16).all { shift ->
            kotlin.math.abs(((actual shr shift) and 255) - ((expected shr shift) and 255)) <= 3
        }
    }

    private fun shell(command: String) {
        android.os.ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command)).use { it.readBytes() }
    }

    private fun localized(tag: String, id: Int): String {
        val configuration = Configuration(context.resources.configuration)
        configuration.setLocale(Locale.forLanguageTag(tag))
        return context.createConfigurationContext(configuration).getString(id)
    }

    private fun countWebViews(view: View): Int = when (view) {
        is WebView -> 1
        is ViewGroup -> (0 until view.childCount).sumOf { countWebViews(view.getChildAt(it)) }
        else -> 0
    }

}
