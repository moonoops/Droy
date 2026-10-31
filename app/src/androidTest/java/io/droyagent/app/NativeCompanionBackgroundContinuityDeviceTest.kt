package io.droyagent.app

import android.graphics.Bitmap
import android.view.KeyEvent
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.droyagent.app.nativeui.companion.data.*
import io.droyagent.app.nativeui.companion.ui.CompanionChatScreen
import io.droyagent.app.nativeui.companion.ui.clearCompanionImageCache
import io.droyagent.app.nativeui.data.NativeRuntimeState
import io.droyagent.app.nativeui.ui.DroyTheme
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Actual IME interaction plus recording-strip geometry; no microphone or model call. */
@RunWith(AndroidJUnit4::class)
class NativeCompanionBackgroundContinuityDeviceTest {
    @get:Rule(order = 0) val compose = createEmptyComposeRule()
    @get:Rule(order = 1) val fixture = NativeIsolatedRuntimeFixture()

    @After fun clearSyntheticImages() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync { clearCompanionImageCache() }
    }

    @Test fun keyboardAndRecordingStripKeepWallpaperGeometryAndDecode() {
        val strip = mutableStateOf(false)
        val reads = AtomicInteger()
        val bitmap = Bitmap.createBitmap(240, 800, Bitmap.Config.ARGB_8888)
        for (y in 0 until bitmap.height) for (x in 0 until bitmap.width)
            bitmap.setPixel(x, y, android.graphics.Color.rgb(x, y % 256, (x + y) % 256))
        val bytes = ByteArrayOutputStream().use { output ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, output); output.toByteArray()
        }
        bitmap.recycle()
        val friend = NativeCompanion("wallpaper-fixture", "Friend", "fixture-avatar",
            chatBackgroundJson = """{"type":"preset","id":"01"}""")
        val state = NativeCompanionState(runtime = NativeRuntimeState(ready = true),
            companions = listOf(friend), selectedCompanionId = friend.id)
        fixture.scenario.onActivity { activity ->
            clearCompanionImageCache()
            val model = ViewModelProvider(activity)[NativeCompanionViewModel::class.java]
            activity.setContent {
                DroyTheme(dark = false) {
                    CompanionChatScreen(state, model, {}, {}, {}, imageLoader = { url ->
                        if (url.contains("wallpaper")) reads.incrementAndGet()
                        bytes
                    }, voiceStatus = { if (strip.value) Spacer(Modifier.fillMaxWidth().height(64.dp)) })
                }
            }
        }
        compose.waitUntil(10_000) { reads.get() > 0 }
        compose.waitForIdle()
        val background = compose.onNodeWithTag("native-companion-chat-background")
        compose.waitUntil(10_000) {
            val image = background.captureToImage().toPixelMap()
            image[image.width / 4, image.height / 3] != image[image.width / 2, image.height / 3]
        }
        val bounds = background.getUnclippedBoundsInRoot()
        val pixels = background.captureToImage().toPixelMap()
        val sample = pixels[pixels.width / 4, pixels.height / 3]
        val initialReads = reads.get()
        val history = compose.onNodeWithTag("native-companion-history")
        fun historyHeight() = history.getUnclippedBoundsInRoot().let { it.bottom - it.top }
        val initialHeight = historyHeight()
        fun assertStable() {
            assertEquals(bounds, background.getUnclippedBoundsInRoot())
            val current = background.captureToImage().toPixelMap()
            assertEquals(sample, current[current.width / 4, current.height / 3])
            assertEquals("Layout changes must not request the wallpaper again", initialReads, reads.get())
        }
        fixture.capture("background-stable-initial")
        compose.runOnIdle { strip.value = true }
        assertTrue(historyHeight() < initialHeight)
        assertStable()
        compose.runOnIdle { strip.value = false }
        assertStable()
        compose.onNodeWithTag("native-companion-composer").performClick()
        compose.waitUntil(10_000) { historyHeight() < initialHeight - 80.dp }
        assertStable()
        fixture.capture("background-stable-keyboard")
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        compose.waitUntil(10_000) { historyHeight() == initialHeight }
        assertStable()
        fixture.capture("background-stable-keyboard-closed")
    }
}
