package io.droyagent.app

import android.content.res.Configuration
import android.graphics.Bitmap
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import io.droyagent.app.nativeui.companion.ui.CompanionBackgroundPage
import io.droyagent.app.nativeui.companion.ui.clearCompanionImageCache
import io.droyagent.app.nativeui.ui.DroyTheme
import java.io.ByteArrayOutputStream
import java.util.Locale
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** The outgoing image owns its crop; position changes on the same image do not fade. */
class NativeBackgroundPreviewTransitionDeviceTest {
    @get:Rule(order = 0) val compose = createEmptyComposeRule()
    @get:Rule(order = 1) val fixture = NativeIsolatedRuntimeFixture()
    private val fixtureLocale get() = InstrumentationRegistry.getArguments().getString("nativeFixtureLocale", "en")
    private val uploadUrl = "fixture-background-transition-stripes"

    @After fun removeSyntheticImages() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync { clearCompanionImageCache() }
    }

    @Test fun outgoingBackgroundKeepsItsCropWhileFadingToDefault() {
        showBackground()
        awaitRedPreview()
        fixture.capture("background-crop-$fixtureLocale-before")
        compose.mainClock.autoAdvance = false
        try {
            compose.onNodeWithTag("native-background-option-default").assertIsDisplayed().performClick()
            for (frame in 0..2) {
                compose.mainClock.advanceTimeBy(32)
                val color = previewSample()
                assertTrue("Outgoing left crop must remain red at frame $frame: $color", color.red > color.green + .10f)
                assertTrue("The outgoing image must remain visible at frame $frame: $color", color.red > color.blue + .10f)
                fixture.capture("background-crop-$fixtureLocale-fade-$frame")
            }
            compose.mainClock.advanceTimeBy(2_000)
            val settled = previewSample()
            assertTrue("Default background must replace the colored image: $settled", kotlin.math.abs(settled.red - settled.green) < .10f)
            compose.onNodeWithTag("native-background-option-default").assertIsSelected()
            fixture.capture("background-crop-$fixtureLocale-default")
        } finally { compose.mainClock.autoAdvance = true }
    }

    @Test fun movingTheSameBackgroundChangesCropWithoutCrossfading() {
        val incoming = mutableStateOf<String?>(null)
        showBackground(incoming)
        awaitRedPreview()
        compose.mainClock.autoAdvance = false
        try {
            // This is the same selection update used by the position sliders, with the URL retained.
            compose.runOnIdle { incoming.value = upload(100) }
            compose.mainClock.advanceTimeByFrame()
            compose.mainClock.advanceTimeByFrame()
            val color = previewSample()
            assertTrue("The new right crop must appear immediately: $color", color.blue > .98f)
            assertTrue("The previous red crop must not remain as a fading layer: $color", color.red < .02f && color.green < .02f)
            fixture.capture("background-crop-$fixtureLocale-same-image-right")
            compose.runOnIdle { incoming.value = upload(0) }
            compose.mainClock.advanceTimeByFrame()
            compose.mainClock.advanceTimeByFrame()
            assertRed(previewSample())
            fixture.capture("background-crop-$fixtureLocale-same-image-left")
        } finally { compose.mainClock.autoAdvance = true }
    }

    private fun showBackground(incoming: androidx.compose.runtime.MutableState<String?> = mutableStateOf(null)) {
        val bytes = stripedImage()
        fixture.scenario.onActivity { activity ->
            clearCompanionImageCache()
            val configuration = Configuration(activity.resources.configuration).apply { setLocale(Locale.forLanguageTag(fixtureLocale)) }
            val localized = activity.createConfigurationContext(configuration)
            activity.setContent {
                CompositionLocalProvider(LocalContext provides localized, LocalConfiguration provides configuration,
                    LocalResources provides localized.resources) {
                    DroyTheme(dark = false) {
                        CompanionBackgroundPage(initial = upload(0), loader = { bytes }, enabled = true,
                            onChoose = null, onApply = {}, onClose = {}, incomingSelection = incoming.value)
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    private fun awaitRedPreview() {
        compose.waitUntil(10_000) {
            val color = previewSample()
            color.red > .98f && color.green < .02f && color.blue < .02f
        }
        compose.waitForIdle()
        assertRed(previewSample())
    }

    private fun previewSample(): Color {
        val image = compose.onNodeWithTag("native-background-preview").captureToImage().toPixelMap()
        // Above the sample chat bubbles and away from the rounded outline.
        return image[image.width / 2, image.height / 10]
    }

    private fun assertRed(color: Color) {
        assertTrue("The left crop must show only the red stripe: $color", color.red > .98f && color.green < .02f && color.blue < .02f)
    }

    private fun upload(x: Int) = JSONObject().put("type", "upload").put("id", "transition-fixture")
        .put("url", uploadUrl).put("position", JSONObject().put("x", x).put("y", 50)).toString()

    private fun stripedImage(): ByteArray {
        val bitmap = Bitmap.createBitmap(900, 180, Bitmap.Config.ARGB_8888)
        try {
            val colors = intArrayOf(android.graphics.Color.RED, android.graphics.Color.GREEN, android.graphics.Color.BLUE)
            val row = IntArray(bitmap.width) { colors[it / 300] }
            for (y in 0 until bitmap.height) bitmap.setPixels(row, 0, bitmap.width, 0, y, bitmap.width, 1)
            return ByteArrayOutputStream().use { output ->
                assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
                output.toByteArray()
            }
        } finally { bitmap.recycle() }
    }
}
