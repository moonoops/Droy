package io.droyagent.app

import androidx.activity.compose.setContent
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.droyagent.app.nativeui.productsettings.*
import io.droyagent.app.nativeui.ui.DroyTheme
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Reentering a visited settings page must render its last snapshot before its refresh finishes. */
@RunWith(AndroidJUnit4::class)
class NativeSettingsLoadingDeviceTest {
    @get:Rule(order = 0) val compose = createEmptyComposeRule()
    @get:Rule(order = 1) val fixture = NativeIsolatedRuntimeFixture("settings")

    @Test fun returningToSettingsStartsWithContentAndKeepsControlsEnabledDuringRefresh() {
        val shown = mutableStateOf(true)
        val presentation = ProductSettingsPresentationState()
        val ready = CopyOnWriteArrayList<Boolean>()
        val host = NativeProductSettingsHost(fixture.context)
        fixture.scenario.onActivity { activity -> activity.setContent {
            DroyTheme {
                if (shown.value) NativeProductSettingsScreen(host,
                    ProductSettingsActions({}, {}, {}, {}, {}),
                    initialSection = ProductSettingsSection.General,
                    generalPreferences = { Text("Fixture appearance") },
                    onReady = { ready.add(it) }, presentation = presentation)
            }
        } }
        compose.waitUntil(10_000) { ready.lastOrNull() == true }
        compose.onNodeWithText("Fixture appearance").assertIsDisplayed()
        val firstBounds = compose.onNodeWithTag("native-product-list:general").fetchSemanticsNode().boundsInRoot
        assertNotNull(presentation.current(ProductSettingsSection.General))
        compose.runOnIdle { shown.value = false }
        compose.waitForIdle()
        ready.clear()
        compose.runOnIdle { shown.value = true }
        compose.waitUntil(10_000) { ready.isNotEmpty() }
        assertTrue("The first presented frame already has the previous settings; no blank loading body", ready.first())
        compose.waitForIdle()
        assertFalse("Refreshing must not turn the whole page busy and recolor controls", ready.contains(false))
        compose.onNodeWithText("Fixture appearance").assertIsDisplayed()
        compose.onNodeWithTag("native-product-body:general").assertIsDisplayed()
        assertEquals(firstBounds, compose.onNodeWithTag("native-product-list:general").fetchSemanticsNode().boundsInRoot)
        fixture.capture("round5-settings-repeat-entry")
    }
}
