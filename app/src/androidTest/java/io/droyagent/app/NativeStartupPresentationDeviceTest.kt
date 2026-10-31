package io.droyagent.app

import androidx.activity.compose.setContent
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.droyagent.app.nativeui.companion.data.NativeCompanionViewModel
import io.droyagent.app.nativeui.companion.ui.CompanionMessagesScreen
import io.droyagent.app.nativeui.ui.DroyTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NativeStartupPresentationDeviceTest {
    @get:Rule(order = 0) val compose = createEmptyComposeRule()
    @get:Rule(order = 1) val fixture = NativeIsolatedRuntimeFixture("messages")

    @Test fun startupAndRefreshKeepExistingConversationsAtTheSamePosition() {
        lateinit var model: NativeCompanionViewModel
        fixture.scenario.onActivity { model = ViewModelProvider(it)[NativeCompanionViewModel::class.java] }
        compose.waitUntil(30_000) { model.state.value.runtime.ready && model.state.value.conversations.isNotEmpty() }
        val initial = model.state.value
        val displayed = mutableStateOf(initial)
        fixture.scenario.onActivity { activity -> activity.setContent { DroyTheme {
            CompanionMessagesScreen(displayed.value, model, {}, {})
        } } }
        val row = compose.onNodeWithTag("native-companion-conversation:${initial.conversations.first().id}")
        val original = row.assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        compose.runOnIdle { displayed.value = initial.copy(loading = true,
            runtime = initial.runtime.copy(ready = false, active = true, canStart = false)) }
        compose.onNodeWithTag("native-companion-starting").assertIsDisplayed()
        assertEquals(original, row.assertIsDisplayed().fetchSemanticsNode().boundsInRoot)
        compose.onNodeWithTag("native-companion-start-runtime").assertDoesNotExist()
        compose.runOnIdle { displayed.value = initial }
        compose.onNodeWithTag("native-companion-starting").assertDoesNotExist()
        assertEquals(original, row.assertIsDisplayed().fetchSemanticsNode().boundsInRoot)
    }
}
