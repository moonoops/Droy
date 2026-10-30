package io.droyagent.app

import android.app.UiModeManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.Configuration
import android.graphics.Bitmap
import android.os.Build
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.webkit.WebView
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Cold UI only: no model calls, synthetic conversations or engine start. */
@RunWith(AndroidJUnit4::class)
class NativeWorkbenchUiDeviceTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private var scenario: ActivityScenario<NativeAppActivity>? = null
    private var product: SharedPreferences? = null
    private var workbench: SharedPreferences? = null
    private var originalProduct = emptyMap<String, Any?>()
    private var originalWorkbench = emptyMap<String, Any?>()
    private val otherPreferences = linkedMapOf<SharedPreferences, Map<String, Any?>>()
    private var originalNightMode = UiModeManager.MODE_NIGHT_AUTO
    private var owned = false

    @Before fun prepare() {
        assertEquals("Development package only", "io.droyagent.app.dev", context.packageName)
        assertTrue("Use -e nativeUiFixture true on a dedicated emulator",
            InstrumentationRegistry.getArguments().getString("nativeUiFixture") == "true")
        assertTrue("Physical devices are not allowed for this fixture",
            Build.HARDWARE.contains("ranchu") || Build.HARDWARE.contains("goldfish") ||
                Build.FINGERPRINT.startsWith("generic") || Build.MODEL.contains("sdk_gphone"))
        assertTrue("Stop existing tasks before the cold UI fixture", RuntimeService.canManageHistory())
        originalNightMode = context.getSystemService(UiModeManager::class.java).nightMode
        assertTrue("Restoreable emulator theme required", originalNightMode in setOf(
            UiModeManager.MODE_NIGHT_AUTO, UiModeManager.MODE_NIGHT_NO, UiModeManager.MODE_NIGHT_YES))
        product = context.getSharedPreferences("product", Context.MODE_PRIVATE)
        workbench = context.getSharedPreferences("native_workbench", Context.MODE_PRIVATE)
        originalProduct = product!!.all.mapValues { copyValue(it.value) }
        originalWorkbench = workbench!!.all.mapValues { copyValue(it.value) }
        for (name in listOf("native_companions", "native_media", "native_setup_draft", "native_companion_creation",
            "native_share_preview", "native_share_inbox", "native_voice_input", "native_voice_capture", "native-engine-drafts", "native_workspaces", "native_standard_voice")) {
            val preference = context.getSharedPreferences(name, Context.MODE_PRIVATE)
            otherPreferences[preference] = preference.all.mapValues { copyValue(it.value) }
        }
        val installation = context.getSharedPreferences("installation", Context.MODE_PRIVATE)
        otherPreferences[installation] = installation.all.mapValues { copyValue(it.value) }
        owned = true
        otherPreferences.keys.filterNot { it == installation }.forEach { assertTrue(it.edit().clear().commit()) }
        assertTrue(installation.edit().putBoolean("completed", true).commit())
        assertTrue(product!!.edit().putBoolean(BackgroundRuntime.STOPPED, true).commit())
        assertTrue(workbench!!.edit().clear().commit())
        instrumentation.runOnMainSync { NativeLocale.adopt("zh") }
        setNight(false)
    }

    @After fun restore() {
        if (!owned) return
        try {
            scenario?.close()
            scenario = null
            instrumentation.waitForIdleSync()
            assertTrue("Cold fixture unexpectedly started an engine", RuntimeService.canManageHistory())
        } finally {
            val night = when (originalNightMode) {
                UiModeManager.MODE_NIGHT_YES -> "yes"
                UiModeManager.MODE_NIGHT_NO -> "no"
                else -> "auto"
            }
            shell("cmd uimode night $night")
            workbench?.let { restorePreferences(it, originalWorkbench, null) }
            otherPreferences.forEach { (preference, values) -> restorePreferences(preference, values, null) }
            product?.let { restorePreferences(it, originalProduct, null) }
            owned = false
        }
    }

    @Test fun nativeColdUiRetainsDraftAcrossRecreationAndFollowsLocaleAndTheme() {
        scenario = ActivityScenario.launch(Intent(context, NativeAppActivity::class.java).putExtra("destination", "workbench"))
        compose.waitForIdle()
        compose.onNodeWithTag("native-workbench").assertIsDisplayed()
        compose.onNodeWithTag("native-start-runtime").assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithTag("native-workbench-title").assertIsDisplayed().assertTextContains(localized("zh", R.string.native_workbench))
        scenario!!.onActivity { activity ->
            assertEquals("Native entry must not host a hidden browser", 0, countWebViews(activity.window.decorView))
            assertTrue("The new native surface must count as sensitive UI", NativeRuntimeHost.isVisible())
        }
        assertFalse("No engine starts without an explicit action", RuntimeService.isStartingOrRunning())
        capture("cold-zh-light", expectedSurface = 0xFFFEF7FF.toInt())

        compose.onNodeWithTag("native-tab-messages").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("native-open-user-profile").fetchSemanticsNodes(false).isNotEmpty() }
        compose.onNodeWithTag("native-open-user-profile").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("native-user-name").fetchSemanticsNodes(false).isNotEmpty() }
        compose.onNodeWithTag("native-user-name").assertIsNotEnabled()
        compose.onNodeWithTag("native-companion-start-runtime").assertIsDisplayed()
        scenario!!.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("native-root-navigation").fetchSemanticsNodes(false).isNotEmpty() }
        compose.onNodeWithTag("native-tab-workbench").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("native-composer").fetchSemanticsNodes(false).isNotEmpty() }

        val draft = "native-ui-fixture-draft"
        compose.onNodeWithTag("native-composer").performClick().performTextReplacement(draft)
        compose.onNodeWithTag("native-composer").assertTextContains(draft)
        compose.waitUntil(timeoutMillis = 10_000) { isImeVisible() }
        capture("cold-ime-draft")
        scenario!!.recreate()
        compose.waitForIdle()
        compose.onNodeWithTag("native-composer").assertTextContains(draft)
        scenario!!.onActivity { activity ->
            assertEquals(0, countWebViews(activity.window.decorView))
            WindowCompat.getInsetsController(activity.window, activity.window.decorView).hide(WindowInsetsCompat.Type.ime())
        }
        compose.waitUntil(timeoutMillis = 10_000) { !isImeVisible() }
        capture("cold-recreated-draft")

        instrumentation.runOnMainSync { NativeLocale.adopt("en") }
        setNight(true)
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithText(localized("en", R.string.native_workbench)).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("native-composer").assertTextContains(draft)
        compose.onNodeWithTag("native-start-runtime").assertIsDisplayed()
        capture("cold-en-dark", expectedSurface = 0xFF151218.toInt())
        assertFalse("Theme and Activity changes must not start the engine", RuntimeService.isStartingOrRunning())
        assertTrue(product!!.getBoolean(BackgroundRuntime.STOPPED, false))
    }

    private fun isImeVisible(): Boolean {
        val visible = AtomicBoolean()
        scenario!!.onActivity { visible.set(it.window.decorView.rootWindowInsets?.isVisible(WindowInsets.Type.ime()) == true) }
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
        val actual = image.getPixel(image.width / 2, image.height * 3 / 5)
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

    private fun copyValue(value: Any?): Any? = if (value is Set<*>) value.toSet() else value

    private fun restorePreferences(preferences: SharedPreferences, values: Map<String, Any?>, keys: Set<String>?) {
        val editor = preferences.edit()
        if (keys == null) editor.clear() else keys.forEach(editor::remove)
        values.forEach { (key, value) ->
            when (value) {
                is String -> editor.putString(key, value)
                is Boolean -> editor.putBoolean(key, value)
                is Int -> editor.putInt(key, value)
                is Long -> editor.putLong(key, value)
                is Float -> editor.putFloat(key, value)
                is Set<*> -> { @Suppress("UNCHECKED_CAST") editor.putStringSet(key, value as Set<String>) }
                else -> error("Unsupported fixture preference type")
            }
        }
        assertTrue("Could not restore fixture preferences", editor.commit())
    }
}
