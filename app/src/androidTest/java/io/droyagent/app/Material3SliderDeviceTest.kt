package io.droyagent.app

import android.content.res.Configuration
import android.graphics.Bitmap
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.SoftwareKeyboardController
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.droyagent.app.nativeui.ui.DroyTheme
import io.droyagent.app.nativeui.ui.WorkbenchFontSheet
import io.droyagent.app.nativeui.data.NativeRuntimeState
import io.droyagent.app.nativeui.companion.ui.CompanionBackgroundPage
import io.droyagent.app.nativeui.voice.*
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises the production sheet in the Compose test Activity without starting the runtime. */
@RunWith(AndroidJUnit4::class)
class Material3SliderDeviceTest {
    @get:Rule val compose = createComposeRule()

    @Test fun englishFontSliderFollowsResetAndTextEdits() = verify("en")
    @Test fun chineseFontSliderFollowsResetAndTextEdits() = verify("zh")
    @Test fun englishVoiceRateFollowsResetAndExternalDraft() = verifyVoice("en")
    @Test fun chineseVoiceRateFollowsResetAndExternalDraft() = verifyVoice("zh")
    @Test fun englishBackgroundAxesFollowIncomingSelection() = verifyBackground("en")
    @Test fun chineseBackgroundAxesFollowIncomingSelection() = verifyBackground("zh")

    private fun verifyBackground(language: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        assertEquals("Development package only", "io.droyagent.app.dev", context.packageName)
        val configuration = Configuration(context.resources.configuration).apply {
            setLocale(Locale.forLanguageTag(language))
        }
        val localized = context.createConfigurationContext(configuration)
        fun selection(x: Int, y: Int) = JSONObject().put("type", "upload").put("url", "fixture-background.png")
            .put("position", JSONObject().put("x", x).put("y", y)).toString()
        val incoming = mutableStateOf<String?>(null)
        val saved = AtomicReference<String>()
        val image = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
        val bytes = try {
            image.eraseColor(android.graphics.Color.LTGRAY)
            ByteArrayOutputStream().use { stream ->
                assertTrue(image.compress(Bitmap.CompressFormat.PNG, 100, stream)); stream.toByteArray()
            }
        } finally { image.recycle() }
        compose.setContent {
            CompositionLocalProvider(LocalContext provides localized, LocalConfiguration provides configuration,
                LocalResources provides localized.resources) {
                DroyTheme(dark = false) {
                    CompanionBackgroundPage(initial = selection(25, 75), loader = { bytes }, enabled = true,
                        onChoose = null, onApply = saved::set, onClose = {}, incomingSelection = incoming.value,
                        onIncomingConsumed = { incoming.value = null })
                }
            }
        }
        compose.onNode(hasScrollToIndexAction()).performScrollToKey("position")
        val sliders = compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.SetProgress))
        sliders.assertCountEquals(2)
        fun assertAxes(x: Float, y: Float) {
            sliders[0].assertRangeInfoEquals(ProgressBarRangeInfo(x, 0f..100f))
            sliders[1].assertRangeInfoEquals(ProgressBarRangeInfo(y, 0f..100f))
        }
        assertAxes(25f, 75f)
        sliders[0].performSemanticsAction(SemanticsActions.SetProgress) { assertTrue(it(40f)) }
        assertAxes(40f, 75f)
        sliders[1].performSemanticsAction(SemanticsActions.SetProgress) { assertTrue(it(60f)) }
        assertAxes(40f, 60f)
        compose.runOnIdle { incoming.value = selection(10, 90) }
        compose.waitUntil(5_000) { incoming.value == null }
        assertAxes(10f, 90f)
        sliders[0].performSemanticsAction(SemanticsActions.SetProgress) { assertTrue(it(30f)) }
        assertAxes(30f, 90f)
        compose.waitForIdle()
        val directory = File(context.getExternalFilesDir(null), "material3-slider-evidence")
        assertTrue(directory.isDirectory || directory.mkdirs())
        val screenshot = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        try {
            File(directory, "background-axes-$language.png").outputStream().use {
                assertTrue(screenshot.compress(Bitmap.CompressFormat.PNG, 100, it))
            }
        } finally { screenshot.recycle() }
        compose.onNodeWithTag("native-background-apply").performClick()
        compose.runOnIdle {
            val result = JSONObject(requireNotNull(saved.get()))
            assertEquals("fixture-background.png", result.getString("url"))
            assertEquals(30.0, result.getJSONObject("position").getDouble("x"), 0.001)
            assertEquals(90.0, result.getJSONObject("position").getDouble("y"), 0.001)
        }
    }

    private fun verifyVoice(language: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        assertEquals("Development package only", "io.droyagent.app.dev", context.packageName)
        val configuration = Configuration(context.resources.configuration).apply {
            setLocale(Locale.forLanguageTag(language))
        }
        val localized = context.createConfigurationContext(configuration)
        val state = mutableStateOf(NativeVoiceState(runtime = NativeRuntimeState(ready = true),
            page = NativeVoicePage.READOUT, fresh = true,
            ttsDraft = NativeVoiceTtsDraft("system", "", 1.5, "", "")))
        compose.setContent {
            CompositionLocalProvider(LocalContext provides localized, LocalConfiguration provides configuration,
                LocalResources provides localized.resources) {
                DroyTheme(dark = false) {
                    NativeVoiceScreen(state.value, onAction = { action ->
                        assertTrue("Only local draft edits are permitted", action is NativeVoiceAction.EditTts)
                        state.value = state.value.copy(ttsDraft = (action as NativeVoiceAction.EditTts).draft)
                    }, onBack = {}, onPermissions = {})
                }
            }
        }
        val slider = compose.onNodeWithTag("native-voice-rate")
        slider.performScrollTo().assertRangeInfoEquals(ProgressBarRangeInfo(1.5f, 0.5f..2f))
        slider.performSemanticsAction(SemanticsActions.SetProgress) { assertTrue(it(1.75f)) }
        compose.onNodeWithText(localized.getString(R.string.native_voice_reset_rate)).performScrollTo().performClick()
        slider.assertRangeInfoEquals(ProgressBarRangeInfo(1f, 0.5f..2f))
        compose.runOnIdle { state.value = state.value.copy(ttsDraft = state.value.ttsDraft!!.copy(rate = 0.75)) }
        slider.assertRangeInfoEquals(ProgressBarRangeInfo(0.75f, 0.5f..2f))
        slider.performSemanticsAction(SemanticsActions.SetProgress) { assertTrue(it(1.25f)) }
        compose.runOnIdle { assertEquals(1.25, state.value.ttsDraft!!.rate, 0.001) }
        slider.assertRangeInfoEquals(ProgressBarRangeInfo(1.25f, 0.5f..2f))
        compose.waitForIdle()
        val directory = File(context.getExternalFilesDir(null), "material3-slider-evidence")
        assertTrue(directory.isDirectory || directory.mkdirs())
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        try {
            File(directory, "voice-rate-$language.png").outputStream().use {
                assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
            }
        } finally { bitmap.recycle() }
    }

    private fun verify(language: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        assertEquals("Development package only", "io.droyagent.app.dev", context.packageName)
        val configuration = Configuration(context.resources.configuration).apply {
            setLocale(Locale.forLanguageTag(language))
        }
        val localized = context.createConfigurationContext(configuration)
        val saved = AtomicInteger(-1)
        val dismissed = AtomicBoolean(false)
        var keyboard: SoftwareKeyboardController? = null
        compose.setContent {
            keyboard = LocalSoftwareKeyboardController.current
            CompositionLocalProvider(LocalContext provides localized, LocalConfiguration provides configuration,
                LocalResources provides localized.resources) {
                DroyTheme(dark = false) {
                    WorkbenchFontSheet(size = 18, onSave = saved::set, onDismiss = { dismissed.set(true) })
                }
            }
        }
        val slider = compose.onNodeWithTag("native-font-slider")
        fun assertSize(size: Int) {
            slider.assertRangeInfoEquals(ProgressBarRangeInfo(size.toFloat(), 6f..22f, 15))
            compose.onNodeWithTag("native-font-value").assertTextContains(size.toString())
            val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
            compose.onNodeWithTag("native-font-preview").performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            assertEquals(size.toFloat(), layouts.single().layoutInput.style.fontSize.value, 0.01f)
            assertEquals(size * 1.5f, layouts.single().layoutInput.style.lineHeight.value, 0.01f)
        }
        fun setSlider(size: Float) {
            slider.performSemanticsAction(SemanticsActions.SetProgress) { assertTrue(it(size)) }
        }
        fun capture(size: Int) {
            compose.waitForIdle()
            val directory = File(context.getExternalFilesDir(null), "material3-slider-evidence")
            assertTrue(directory.isDirectory || directory.mkdirs())
            val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
            try {
                File(directory, "font-slider-$size-$language.png").outputStream().use {
                    assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
                }
            } finally { bitmap.recycle() }
        }
        assertSize(18)
        setSlider(20f)
        assertSize(20)
        compose.onNodeWithText(localized.getString(R.string.native_font_reset)).performClick()
        assertSize(14)
        capture(14)
        setSlider(6f)
        assertSize(6)
        capture(6)
        setSlider(21f)
        assertSize(21)

        compose.onNodeWithTag("native-font-value").performClick()
        val input = compose.onNodeWithTag("native-font-input")
        val apply = compose.onNodeWithText(localized.getString(R.string.native_font_apply))
        input.performTextReplacement("7")
        compose.runOnIdle { keyboard?.hide() }
        assertSize(7)
        listOf("5", "23", "").forEach { invalid ->
            input.performTextReplacement(invalid)
            compose.runOnIdle { keyboard?.hide() }
            apply.assertIsNotEnabled()
            assertSize(7)
        }
        setSlider(6f)
        input.assertTextContains("6")
        apply.assertIsEnabled()
        compose.onNodeWithText(localized.getString(R.string.native_font_reset)).performClick()
        assertSize(14)
        input.assertTextContains("14")
        setSlider(22f)
        assertSize(22)
        input.assertTextContains("22")
        apply.assertIsEnabled()
        assertEquals(-1, saved.get())
        assertFalse(dismissed.get())

        capture(22)
        apply.performClick()
        compose.runOnIdle {
            assertEquals(22, saved.get())
            assertTrue(dismissed.get())
        }
    }
}
