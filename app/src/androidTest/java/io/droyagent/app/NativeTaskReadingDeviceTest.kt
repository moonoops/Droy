package io.droyagent.app

import android.content.res.Configuration
import android.view.KeyEvent
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.droyagent.app.nativeui.data.NativeWorkbenchViewModel
import io.droyagent.app.nativeui.settings.NativeSettingsViewModel
import java.util.Locale
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The isolated emulator fixture keeps its original sessions and preferences intact. */
@RunWith(AndroidJUnit4::class)
class NativeTaskReadingDeviceTest {
    @get:Rule(order = 0) val compose = createEmptyComposeRule()
    @get:Rule(order = 1) val fixture = NativeIsolatedRuntimeFixture()
    private lateinit var work: NativeWorkbenchViewModel
    private lateinit var settings: NativeSettingsViewModel
    private lateinit var replyId: String
    private var language = "en"
    private val reading get() = fixture.context.getSharedPreferences("droy_native_reading", 0)

    @Test fun actualTaskTextUsesDefaultAndPersistsReadingSizeInBothLanguages() {
        bindModels()
        awaitReady()
        fixture.scenario.onActivity { assertTrue(reading.edit().remove("workbenchTextSize").commit()) }
        assertFalse(reading.contains("workbenchTextSize"))
        val request = "NATIVE_HELLO\n" + (1..40).joinToString("\n") { "Fictional task detail $it." }
        compose.onNodeWithTag("native-composer").performTextInput(request)
        compose.onNodeWithTag("native-send").assertIsEnabled().performClick()
        compose.waitUntil(30_000) {
            !work.state.value.running && !work.state.value.sending && work.state.value.messages.any {
                it.role == "assistant" && it.text == REPLY
            }
        }
        replyId = work.state.value.messages.single { it.role == "assistant" && it.text == REPLY }.id
        back()
        val session = requireNotNull(work.state.value.selectedSessionId)

        for (locale in listOf("en", "zh")) {
            language = locale
            fixture.scenario.onActivity { settings.setLanguage(locale) }
            compose.waitUntil(20_000) {
                settings.state.value.locale == locale && !settings.state.value.saving &&
                    work.state.value.runtime.locale == locale && NativeLocale.active() == locale
            }
            assertNull(settings.state.value.error)
            fixture.scenario.onActivity { assertTrue(reading.edit().remove("workbenchTextSize").commit()) }
            assertTaskFont(14)
            capture(14)
            openFont(14)
            dismissFont()
            assertFalse("Opening or cancelling the default preview must not save an override", reading.contains("workbenchTextSize"))

            for (size in listOf(6, 22)) {
                openFont(if (size == 6) 14 else 6)
                compose.onNodeWithTag("native-font-slider").performSemanticsAction(SemanticsActions.SetProgress) {
                    assertTrue(it(size.toFloat()))
                }
                compose.onNodeWithTag("native-font-value").assertTextContains(size.toString())
                applyFont()
                assertEquals(size, reading.getInt("workbenchTextSize", -1))
                assertTaskFont(size)
                capture(size)
                openFont(size)
                dismissFont()
                fixture.recreate()
                bindModels()
                awaitReady()
                assertEquals(session, work.state.value.selectedSessionId)
                assertTaskFont(size)
                openFont(size)
                dismissFont()
            }

            openFont(22)
            compose.onNodeWithText(text(R.string.native_font_reset)).assertIsDisplayed().performClick()
            compose.onNodeWithTag("native-font-value").assertTextContains("14")
            applyFont()
            assertEquals(14, reading.getInt("workbenchTextSize", -1))
            assertTaskFont(14)
            fixture.recreate()
            bindModels()
            awaitReady()
            assertTaskFont(14)
            openFont(14)
            dismissFont()
        }
    }

    private fun bindModels() = fixture.scenario.onActivity {
        val models = ViewModelProvider(it)
        work = models[NativeWorkbenchViewModel::class.java]
        settings = models[NativeSettingsViewModel::class.java]
    }

    private fun awaitReady() {
        compose.waitUntil(30_000) {
            work.state.value.runtime.ready && work.state.value.workspaceResolved && !work.state.value.loading &&
                settings.state.value.loaded && settings.state.value.writable && !settings.state.value.loading
        }
        compose.onNodeWithTag("native-workbench").assertIsDisplayed()
    }

    private fun assertTaskFont(size: Int) {
        compose.waitForIdle()
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNode(hasText(REPLY) and hasAnyAncestor(hasTestTag("native-message-bubble:$replyId")), useUnmergedTree = true)
            .assertIsDisplayed().performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        val layout = layouts.single()
        assertEquals(size.toFloat(), layout.layoutInput.style.fontSize.value, 0.01f)
        assertEquals(size * 1.5f, layout.layoutInput.style.lineHeight.value, 0.01f)
        assertEquals(REPLY, layout.layoutInput.text.text)
        assertFalse("The task reply must keep its complete height", layout.didOverflowHeight)
        for (line in 0 until layout.lineCount) assertFalse(layout.isLineEllipsized(line))
        assertEquals(REPLY.length, layout.getLineEnd(layout.lineCount - 1, visibleEnd = true))
    }

    private fun openFont(expected: Int) {
        compose.onNodeWithTag("native-workbench-menu").assertIsDisplayed().performClick()
        compose.onNodeWithText(text(R.string.native_workbench_font)).assertIsDisplayed().performClick()
        compose.onNodeWithTag("native-font-value").assertIsDisplayed().assertTextContains(expected.toString())
        compose.onNodeWithTag("native-font-slider").assertIsDisplayed()
            .assertRangeInfoEquals(ProgressBarRangeInfo(expected.toFloat(), 6f..22f, 15))
        compose.onNodeWithText(text(R.string.native_font_reset)).assertIsDisplayed()
        fixture.capture("task-font-sheet-$language-$expected")
    }

    private fun applyFont() {
        compose.onNodeWithText(text(R.string.native_font_apply)).assertIsEnabled().performClick()
        awaitSheetClosed()
    }

    private fun dismissFont() { back(); awaitSheetClosed() }
    private fun awaitSheetClosed() {
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("native-font-slider").fetchSemanticsNodes(false).isEmpty() }
    }

    private fun text(id: Int): String {
        val configuration = Configuration(fixture.context.resources.configuration).apply { setLocale(Locale.forLanguageTag(language)) }
        return fixture.context.createConfigurationContext(configuration).getString(id)
    }

    private fun capture(size: Int) { compose.waitForIdle(); fixture.capture("task-reading-$language-$size") }
    private fun back() = InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)

    private companion object { const val REPLY = "Native DSH reply received." }
}
