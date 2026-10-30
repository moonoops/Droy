package io.droyagent.app

import android.content.res.Configuration
import android.view.KeyEvent
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.droyagent.app.nativeui.settings.NativeSettingsViewModel
import java.util.Locale
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises the real shell in a restored, opt-in emulator fixture; no external model or permission requests. */
@RunWith(AndroidJUnit4::class)
class NativeExpressiveSettingsDeviceTest {
    @get:Rule(order = 0) val compose = createEmptyComposeRule()
    @get:Rule(order = 1) val fixture = NativeIsolatedRuntimeFixture("settings")
    private lateinit var settings: NativeSettingsViewModel
    private val host get() = NativeProductSettingsHost(fixture.context)
    private var language = "en"

    @Test fun groupedSettingsKeepNotificationStateNavigationAndScrollAcrossThemesAndLanguages() {
        fixture.scenario.onActivity { settings = ViewModelProvider(it)[NativeSettingsViewModel::class.java] }
        compose.waitUntil(30_000) { settings.state.value.loaded && settings.state.value.writable && settings.state.value.runtime.ready }
        awaitTag("native-settings-home-list")

        val organizationBefore = host.snapshot("notifications").getBoolean("enabled")
        val taskBefore = host.snapshot("proactive").getBoolean("tasksEnabled")
        val messageBefore = host.snapshot("proactive").getBoolean("messagesEnabled")
        openNotifications()
        assertNotificationSections()
        assertSwitch("nps_message_notifications", messageBefore)
        assertSwitch("nps_task_notifications", taskBefore)
        compose.onNodeWithTag("native-product-switch:nps_message_notifications").performClick()
        compose.waitUntil(10_000) { host.snapshot("proactive").getBoolean("messagesEnabled") != messageBefore }
        awaitTag("native-product-body:notifications")
        assertSwitch("nps_message_notifications", !messageBefore)
        assertEquals(organizationBefore, host.snapshot("notifications").getBoolean("enabled"))
        assertEquals(taskBefore, host.snapshot("proactive").getBoolean("tasksEnabled"))
        back(); awaitTag("native-settings-home-list")
        openNotifications()
        assertSwitch("nps_message_notifications", !messageBefore)
        checkNotificationChildBackPaths()
        assertEquals(organizationBefore, host.snapshot("notifications").getBoolean("enabled"))
        back(); awaitTag("native-settings-home-list")

        for (locale in listOf("zh", "en")) {
            language = locale
            fixture.scenario.onActivity { settings.setLanguage(locale) }
            compose.waitUntil(20_000) {
                settings.state.value.locale == locale && NativeLocale.active() == locale && !settings.state.value.saving
            }
            assertNull(settings.state.value.error)
            for (mode in listOf("light", "dark")) {
                assertTrue(fixture.context.getSharedPreferences("product", 0).edit().putString("appearanceMode", mode).commit())
                repeat(2) { compose.onNodeWithTag("native-settings-home-list").performTouchInput { swipeDown() } }
                compose.waitForIdle()
                compose.onNodeWithTag("native-settings-my-profile").assertIsDisplayed()
                compose.onNodeWithTag("native-settings-notification-sorting").assertDoesNotExist()
                compose.onAllNodesWithTag("native-settings-notifications").assertCountEquals(1)
                compose.onNodeWithTag("native-settings-general").assertTextContains(text(R.string.native_settings_general))
                val expandedHeight = compose.onNodeWithTag("native-settings-home-bar").fetchSemanticsNode().boundsInRoot.height
                capture("batch1-settings-$locale-$mode")

                // Touch scrolling must collapse the title; entering a child must not reset this position.
                repeat(2) { compose.onNodeWithTag("native-settings-home-list").performTouchInput { swipeUp() } }
                compose.waitForIdle()
                compose.onNodeWithTag("native-settings-about").assertIsDisplayed()
                val collapsedHeight = compose.onNodeWithTag("native-settings-home-bar").fetchSemanticsNode().boundsInRoot.height
                assertTrue("The settings title must release vertical space while scrolling", collapsedHeight < expandedHeight)
                compose.onNodeWithTag("native-settings-notifications").assertIsDisplayed()
                val beforeScroll = compose.onNodeWithTag("native-settings-home-list").fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()
                val beforeReturn = compose.onNodeWithTag("native-settings-notifications").fetchSemanticsNode().boundsInRoot
                capture("batch1-settings-scrolled-$locale-$mode")
                compose.onNodeWithTag("native-settings-notifications").performClick()
                awaitTag("native-product-body:notifications")
                assertNotificationSections()
                compose.onNodeWithTag("native-product-switch:nps_message_notifications")
                    .assertTextContains(text(R.string.nps_message_notifications))
                compose.onNodeWithTag("native-product-switch:nps_task_notifications")
                    .assertTextContains(text(R.string.nps_task_notifications))
                assertSwitch("nps_message_notifications", !messageBefore)
                assertSwitch("nps_task_notifications", taskBefore)
                capture("batch1-notifications-$locale-$mode")
                back(); awaitTag("native-settings-home-list")
                val afterReturn = compose.onNodeWithTag("native-settings-notifications").fetchSemanticsNode().boundsInRoot
                val afterScroll = compose.onNodeWithTag("native-settings-home-list").fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()
                val returnedBarHeight = compose.onNodeWithTag("native-settings-home-bar").fetchSemanticsNode().boundsInRoot.height
                capture("batch1-settings-returned-$locale-$mode")
                assertEquals("Returning must preserve the settings reading position (scroll $beforeScroll → $afterScroll; bar $collapsedHeight → $returnedBarHeight)", beforeReturn.top, afterReturn.top, 2f)
                assertEquals("Returning must preserve the collapsed title", collapsedHeight,
                    compose.onNodeWithTag("native-settings-home-bar").fetchSemanticsNode().boundsInRoot.height, 2f)

                compose.onNodeWithTag("native-settings-plugins").performScrollTo().performClick()
                awaitTag("native-settings-group")
                for (destination in listOf("web-search", "subtask-models", "agent-loop", "terminal", "agent-team", "session-titles", "persistent-terminal")) {
                    compose.onNodeWithTag("native-settings-group:$destination").assertExists()
                }
                compose.onNodeWithText(text(R.string.native_settings_plugins)).assertIsDisplayed()
                capture("batch1-plugins-$locale-$mode")
                back(); awaitTag("native-settings-home-list")
            }
        }
        assertEquals(organizationBefore, host.snapshot("notifications").getBoolean("enabled"))
        assertEquals(taskBefore, host.snapshot("proactive").getBoolean("tasksEnabled"))
    }

    private fun openNotifications() {
        compose.onNodeWithTag("native-settings-notification-sorting").assertDoesNotExist()
        compose.onAllNodesWithTag("native-settings-notifications").assertCountEquals(1)
        compose.onNodeWithTag("native-settings-notifications").performScrollTo().performClick()
        awaitTag("native-product-body:notifications")
        compose.onNodeWithTag("native-product-settings:notifications").assertIsDisplayed()
    }

    private fun assertNotificationSections() {
        compose.onNodeWithTag("native-product-list:notifications")
            .performScrollToNode(hasTestTag("native-notification-delivery"))
        compose.onNodeWithTag("native-notification-delivery").assertIsDisplayed()
        compose.onNodeWithTag("native-notification-organization").assertIsDisplayed()
        compose.onNodeWithTag("native-product-switch:nps_message_notifications").assertIsEnabled()
        compose.onNodeWithTag("native-product-switch:nps_task_notifications").assertIsEnabled()
    }

    private fun checkNotificationChildBackPaths() {
        for ((field, title) in listOf("nps_app_management" to R.string.nps_app_management, "nps_archive" to R.string.nps_archive)) {
            compose.onNodeWithTag("native-product-list:notifications")
                .performScrollToNode(hasTestTag("native-product-field:$field"))
            compose.onNodeWithTag("native-product-field:$field").performClick()
            compose.onNodeWithText(text(title)).assertIsDisplayed()
            compose.onNodeWithTag("native-notification-delivery").assertDoesNotExist()
            back(); awaitTag("native-product-body:notifications")
            assertNotificationSections()
        }
        val ruleBefore = host.snapshot("notifications").getString("ruleText")
        compose.onNodeWithTag("native-product-list:notifications")
            .performScrollToNode(hasTestTag("native-product-field:nps_rules"))
        compose.onNodeWithTag("native-product-field:nps_rules").performClick()
        compose.onNodeWithText(text(R.string.nps_restore_default)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.nps_save)).assertIsDisplayed()
        back()
        compose.onNodeWithText(text(R.string.nps_restore_default)).assertDoesNotExist()
        awaitTag("native-product-body:notifications")
        assertNotificationSections()
        assertEquals("Leaving an unchanged rule sheet must not mutate the rule", ruleBefore,
            host.snapshot("notifications").getString("ruleText"))
    }

    private fun assertSwitch(resourceName: String, enabled: Boolean) {
        compose.onNodeWithTag("native-product-switch:$resourceName").assert(
            SemanticsMatcher.expectValue(SemanticsProperties.ToggleableState,
                if (enabled) ToggleableState.On else ToggleableState.Off))
    }
    private fun text(id: Int): String = fixture.context.createConfigurationContext(
        Configuration(fixture.context.resources.configuration).apply { setLocale(Locale.forLanguageTag(language)) }
    ).getString(id)
    private fun awaitTag(tag: String) {
        compose.waitUntil(20_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes(false).isNotEmpty() }
        compose.waitForIdle()
        compose.onNodeWithTag(tag).assertIsDisplayed()
    }
    private fun back() {
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        compose.waitForIdle()
    }
    private fun capture(name: String) {
        compose.waitForIdle(); fixture.capture(name)
    }
}
