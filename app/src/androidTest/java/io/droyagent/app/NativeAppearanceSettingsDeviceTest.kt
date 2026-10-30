package io.droyagent.app

import android.view.KeyEvent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.droyagent.app.nativeui.settings.NativeSettingsViewModel
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NativeAppearanceSettingsDeviceTest {
    @get:Rule(order = 0) val compose = createEmptyComposeRule()
    @get:Rule(order = 1) val fixture = NativeIsolatedRuntimeFixture("settings")

    @Test fun themeChoicesApplyInPlaceAndSurviveReturning() {
        lateinit var settings: NativeSettingsViewModel
        fixture.scenario.onActivity { settings = ViewModelProvider(it)[NativeSettingsViewModel::class.java] }
        compose.waitUntil(30_000) { settings.state.value.loaded && settings.state.value.runtime.ready }
        fixture.scenario.onActivity { settings.setLanguage("zh") }
        compose.waitUntil(20_000) { settings.state.value.locale == "zh" && NativeLocale.active() == "zh" && !settings.state.value.saving }
        openGeneral()
        click("appearance-mode:light")
        for (palette in listOf("purple", "teal", "amber", "rose")) {
            click("theme-palette:$palette")
            compose.onNodeWithTag("theme-palette:$palette").assertIsSelected()
            assertEquals(palette, prefs().getString("themePalette", null))
            assertEquals("preset", prefs().getString("colorSource", null))
            fixture.capture("batch2-$palette-light-zh")
        }
        click("appearance-mode:dark")
        fixture.capture("batch2-rose-dark-zh")
        click("color-source:dynamic")
        compose.onNodeWithTag("color-source:dynamic").assertIsSelected()
        compose.onNodeWithTag("theme-palette:rose").assertIsNotSelected()
        fixture.capture("batch2-dynamic-dark-zh")
        back(); openGeneral()
        compose.onNodeWithTag("appearance-mode:dark").assertIsSelected()
        compose.onNodeWithTag("color-source:dynamic").assertIsSelected()
        click("appearance-mode:system")
        assertEquals("system", prefs().getString("appearanceMode", null))
        click("theme-palette:purple")
        click("appearance-mode:light")
        back()
        fixture.scenario.onActivity { settings.setLanguage("en") }
        compose.waitUntil(20_000) { settings.state.value.locale == "en" && NativeLocale.active() == "en" && !settings.state.value.saving }
        openGeneral()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Color source").fetchSemanticsNodes(false).isNotEmpty() }
        compose.onNodeWithText("Color source").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("native-product-switch:nps_return").performScrollTo().assertIsDisplayed()
        fixture.capture("batch2-purple-light-en")
    }
    private fun prefs() = fixture.context.getSharedPreferences("product", 0)
    private fun click(tag: String) {
        compose.onNodeWithTag(tag).performScrollTo().performClick()
        compose.waitForIdle()
    }
    private fun openGeneral() {
        compose.waitUntil(20_000) { compose.onAllNodesWithTag("native-settings-general").fetchSemanticsNodes(false).isNotEmpty() }
        click("native-settings-general")
        compose.waitUntil(20_000) { compose.onAllNodesWithTag("appearance-mode:light").fetchSemanticsNodes(false).isNotEmpty() }
        compose.waitForIdle()
    }
    private fun back() {
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        compose.waitForIdle()
    }
}
