package io.droyagent.app

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.os.SystemClock
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.droyagent.app.nativeui.presentation.NativeProductPresentationViewModel
import io.droyagent.app.nativeui.settings.NativeSettingsViewModel
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Uses the isolated development fixture; retired preference values are deliberately left on disk. */
@RunWith(AndroidJUnit4::class)
class NativePresentationDeviceTest {
    @get:Rule(order = 0) val compose = createEmptyComposeRule()
    @get:Rule(order = 1) val fixture = NativeIsolatedRuntimeFixture()
    private val context get() = fixture.context
    private lateinit var presentation: NativeProductPresentationViewModel
    private lateinit var settings: NativeSettingsViewModel

    @Test fun retiredBrandingCannotAlterNativeCopyAfterRecreationOrLanguageChanges() {
        start()
        val preferences = context.getSharedPreferences("product", Context.MODE_PRIVATE)
        val permissionValues = preferences.all.filterKeys { it.startsWith("allow") || it == "toolsStopped" }
        val model = settings.state.value.defaultModel
        assertTrue(preferences.edit().putString("assistantName", "Legacy Aster")
            .putString("brandSidebarLabel", "Legacy workbench")
            .putString("brandHeroBadge", "Legacy badge")
            .putString("brandWelcomeTemplate", "Legacy welcome")
            .putString("brandComposerTemplate", "Legacy hint")
            .putString("brandLogoBackgrounds", "invalid legacy value").commit())
        assertFixedPresentation()
        fixture.recreate(); bindModels(); assertFixedPresentation()
        for (language in listOf("zh", "en")) {
            fixture.scenario.onActivity { settings.setLanguage(language) }
            compose.waitUntil(20_000) { settings.state.value.locale == language && NativeLocale.active() == language && !settings.state.value.saving }
            assertFixedPresentation()
            compose.onNodeWithTag("native-tab-settings").performClick()
            compose.onNodeWithTag("native-settings-general").performScrollTo().performClick()
            awaitTag("native-product-body:general")
            for (tag in listOf("nps_name", "nps_sidebar", "nps_badge", "nps_welcome", "nps_composer"))
                compose.onNodeWithTag("native-product-field:$tag").assertDoesNotExist()
            fixture.capture("native-presentation-general-$language")
            compose.onNodeWithTag("native-product-back").performClick()
            compose.onNodeWithTag("native-tab-workbench").performClick()
        }
        assertEquals("Legacy Aster", preferences.getString("assistantName", null))
        assertEquals(permissionValues, preferences.all.filterKeys { it.startsWith("allow") || it == "toolsStopped" })
        assertEquals(model?.provider, settings.state.value.defaultModel?.provider)
        assertEquals(model?.id, settings.state.value.defaultModel?.id)
    }

    @Test fun appThemeUsesPresetSurfacesAndSystemModeIgnoresOldLogoColors() {
        start()
        val preferences = context.getSharedPreferences("product", Context.MODE_PRIVATE)
        assertTrue(preferences.edit().putString("brandLogoBackgrounds", "{\"default\":{\"light\":\"#112233\",\"dark\":\"#445566\"}}").commit())
        for ((mode, expected) in listOf("light" to "#FEF7FF", "dark" to "#151218")) {
            assertTrue(preferences.edit().putString("appearanceMode", mode).commit())
            assertWorkbenchSurface(Color.parseColor(expected))
            assertFixedPresentation()
            fixture.capture("native-presentation-preset-$mode")
        }
        assertTrue(preferences.edit().putString("appearanceMode", "system").commit())
        val dark = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        assertWorkbenchSurface(Color.parseColor(if (dark) "#151218" else "#FEF7FF"))
        fixture.recreate(); bindModels(); assertFixedPresentation()
        assertEquals("system", preferences.getString("appearanceMode", null))
    }

    private fun start() {
        fixture.startRuntime(); bindModels()
        compose.waitUntil(20_000) { settings.state.value.loaded && presentation.state.value.logo != null && !presentation.state.value.loading }
        awaitTag("native-tab-workbench")
        compose.onNodeWithTag("native-tab-workbench").performClick()
        awaitTag("native-workbench")
    }
    private fun bindModels() { fixture.scenario.onActivity {
        presentation = ViewModelProvider(it)[NativeProductPresentationViewModel::class.java]
        settings = ViewModelProvider(it)[NativeSettingsViewModel::class.java]
    } }
    private fun assertFixedPresentation() {
        compose.waitUntil(10_000) { presentation.state.value.presentation != null && !presentation.state.value.loading }
        val value = requireNotNull(presentation.state.value.presentation)
        assertNull(presentation.state.value.error)
        assertNull(value.workbenchTitleOverride); assertNull(value.welcomeOverride); assertNull(value.composerHintOverride)
        assertNull(value.logoLightColor); assertNull(value.logoDarkColor); assertEquals("", value.heroBadge)
        assertNotEquals("Legacy Aster", value.appName)
        for (text in listOf("Legacy workbench", "Legacy badge", "Legacy welcome", "Legacy hint")) compose.onAllNodesWithText(text).assertCountEquals(0)
    }
    private fun assertWorkbenchSurface(expected: Int) {
        awaitTag("native-workbench")
        val bounds = compose.onNodeWithTag("native-workbench").fetchSemanticsNode().boundsInWindow
        val x = (bounds.left + 2 * context.resources.displayMetrics.density).toInt()
        val y = bounds.center.y.toInt()
        val deadline = SystemClock.elapsedRealtime() + 10_000
        var observed: Int? = null
        while (SystemClock.elapsedRealtime() < deadline) {
            val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
            if (bitmap != null) try { observed = bitmap.getPixel(x, y); if (observed == expected) return } finally { bitmap.recycle() }
            SystemClock.sleep(100)
        }
        assertEquals("Global preset surface must be rendered", expected, observed)
    }
    private fun awaitTag(tag: String) {
        compose.waitUntil(20_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes(false).isNotEmpty() }
        compose.onNodeWithTag(tag).assertIsDisplayed()
    }
}
