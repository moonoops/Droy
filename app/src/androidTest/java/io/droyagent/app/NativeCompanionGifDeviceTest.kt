package io.droyagent.app

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Point
import android.graphics.drawable.AnimatedImageDrawable
import android.os.SystemClock
import android.util.Base64
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import androidx.activity.compose.setContent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.droyagent.app.nativeui.companion.data.*
import io.droyagent.app.nativeui.companion.ui.*
import io.droyagent.app.nativeui.companion.maintenance.NativeCompanionWriteBarrier
import io.droyagent.app.nativeui.data.NativeRuntimeState
import io.droyagent.app.nativeui.ui.DroyTheme
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Two literal frame colors prove native GIF playback through the production chat, picker and preview. */
@RunWith(AndroidJUnit4::class)
class NativeCompanionGifDeviceTest {
    @get:Rule(order = 0) val compose = createEmptyComposeRule()
    @get:Rule(order = 1) val fixture = NativeIsolatedRuntimeFixture()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val originalFrozen = NativeCompanionWriteBarrier.frozen

    @After fun restoreImageAdmission() { NativeCompanionWriteBarrier.frozen = originalFrozen }

    @Test fun gifsAnimateIndependentlyInChatPickerAndPreviewAndStopOutsideTheVisibleLifecycle() {
        clearCompanionImageCache()
        val bytes = Base64.decode(GIF, Base64.DEFAULT)
        val url = "/droy/companions/sticker-media/fictional-two-frame-gif"
        val label = "Fictional animated sticker"
        val sticker = NativeCompanionSticker("fictional-gif", url, label)
        val loads = AtomicInteger()
        val shown = NativeCompanionState(runtime = NativeRuntimeState(ready = true),
            companions = listOf(NativeCompanion("friend", "Fictional friend", "fixture-avatar")), selectedCompanionId = "friend",
            stickers = listOf(sticker), messages = listOf(
                NativeCompanionMessage("gif-user", "user", "sticker", "", "2026-10-30T01:00:00Z", "sent", stickerId = sticker.id),
                NativeCompanionMessage("gif-companion", "assistant", "sticker", "", "2026-10-30T01:00:01Z", "sent", stickerId = sticker.id)))
        fixture.scenario.onActivity { activity ->
            val model = ViewModelProvider(activity)[NativeCompanionViewModel::class.java]
            activity.setContent { DroyTheme(dark = false) {
                CompanionChatScreen(shown, model, {}, {}, {}, imageLoader = { if (it == url) { loads.incrementAndGet(); bytes } else byteArrayOf() },
                    onChooseAttachments = {}, onChooseImages = {}, onCamera = {})
            } }
        }
        compose.onNodeWithTag("native-companion-message:gif-user").assertIsDisplayed()
        compose.onNodeWithTag("native-companion-message:gif-companion").assertIsDisplayed()
        val players = awaitChatPlayers()
        fixture.scenario.onActivity {
            assertNotSame(players[0].drawable, players[1].drawable)
            assertTrue(players.all { (it.drawable as AnimatedImageDrawable).isRunning })
        }
        assertMotion("gif-chat-user") { center(players[0]) }
        assertMotion("gif-chat-companion") { center(players[1]) }

        compose.onNodeWithTag("native-companion-composer-options").performClick()
        compose.onNodeWithTag("native-companion-send-sticker:fictional-gif").assertIsDisplayed()
        assertMotion("gif-picker") {
            val point = compose.onNodeWithTag("native-companion-send-sticker:fictional-gif").fetchSemanticsNode().boundsInWindow.center
            Point(point.x.toInt(), point.y.toInt())
        }
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        compose.onNodeWithTag("native-companion-message:gif-user").assertIsDisplayed()
        compose.onAllNodesWithContentDescription(label).onFirst().performClick()
        compose.onNodeWithTag("native-companion-image-preview").assertIsDisplayed()
        assertMotion("gif-preview") {
            val point = compose.onNodeWithTag("native-companion-image-preview").fetchSemanticsNode().boundsInWindow.center
            Point(point.x.toInt(), point.y.toInt())
        }
        compose.onNodeWithContentDescription(fixture.context.getString(R.string.native_companion_close)).performClick()
        awaitChatPlayers()

        fixture.scenario.moveToState(Lifecycle.State.CREATED)
        fixture.scenario.onActivity {
            assertTrue(players.all { (it.drawable as? AnimatedImageDrawable)?.isRunning != true })
            assertTrue(players.all { it.drawable?.callback == null })
        }
        fixture.scenario.moveToState(Lifecycle.State.RESUMED)
        awaitChatPlayers()
        assertMotion("gif-resumed") { center(players[0]) }
        val drawables = players.map { it.drawable }
        val loadsBeforeRetirement = loads.get()
        fixture.scenario.onActivity {
            NativeCompanionWriteBarrier.frozen = true
            clearCompanionImageCache()
        }
        compose.runOnIdle {
            assertTrue(players.all { it.drawable == null })
            assertTrue(drawables.all { it?.callback == null })
            assertTrue(drawables.all { (it as? AnimatedImageDrawable)?.isRunning != true })
        }
        assertEquals(loadsBeforeRetirement, loads.get())
        fixture.scenario.onActivity { NativeCompanionWriteBarrier.frozen = false }
        val restored = awaitChatPlayers()
        assertTrue("The same retained URL must reload after maintenance", loads.get() > loadsBeforeRetirement)
        assertMotion("gif-unfrozen-user") { center(restored[0]) }
        assertMotion("gif-unfrozen-companion") { center(restored[1]) }
    }

    private fun awaitChatPlayers(): List<CompanionGifImageView> {
        var views = emptyList<CompanionGifImageView>()
        compose.waitUntil(10_000) {
            var playing = false
            fixture.scenario.onActivity { activity ->
                views = descendants(activity.window.decorView).filterIsInstance<CompanionGifImageView>()
                playing = views.size == 2 && views.all { (it.drawable as? AnimatedImageDrawable)?.isRunning == true }
            }
            playing
        }
        return views
    }

    private fun descendants(view: View): List<View> = listOf(view) + if (view is ViewGroup)
        (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()

    private fun center(view: View): Point {
        val point = Point()
        fixture.scenario.onActivity {
            val location = IntArray(2)
            view.getLocationOnScreen(location)
            point.set(location[0] + view.width / 2, location[1] + view.height / 2)
        }
        return point
    }

    private fun assertMotion(name: String, location: () -> Point) {
        val seen = mutableSetOf<String>()
        val deadline = SystemClock.elapsedRealtime() + 8_000
        while (seen.size < 2 && SystemClock.elapsedRealtime() < deadline) {
            val point = location()
            val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
            try {
                val color = bitmap.getPixel(point.x.coerceIn(0, bitmap.width - 1), point.y.coerceIn(0, bitmap.height - 1))
                val frame = when {
                    Color.red(color) > 180 && Color.blue(color) < 90 -> "red"
                    Color.blue(color) > 180 && Color.red(color) < 90 -> "blue"
                    else -> null
                }
                if (frame != null && seen.add(frame)) {
                    val directory = File(fixture.context.getExternalFilesDir(null), "native-isolated-evidence").apply { check(isDirectory || mkdirs()) }
                    File(directory, "$name-$frame.png").outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
                }
            } finally { bitmap.recycle() }
            SystemClock.sleep(73)
        }
        assertEquals("$name must display both actual GIF frame colors", setOf("red", "blue"), seen)
    }

    companion object {
        // Original 32×32 red/blue frames, 240 ms each, infinite loop; no external artwork.
        private const val GIF = "R0lGODlhIAAgAIEAAPAoKAAAAAAAAAAAACH/C05FVFNDQVBFMi4wAwEAAAAh+QQAGAAAACwAAAAAIAAgAAAINQABCBxIsKDBgwgTKlzIsKHDhxAjSpxIsaLFixgzatzIsaPHjyBDihxJsqTJkyhTqlzJUmRAACH5BAAYAAAALAAAAAAgACAAgShQ8AAAAAAAAAAAAAg1AAEIHEiwoMGDCBMqXMiwocOHECNKnEixosWLGDNq3Mixo8ePIEOKHEmypMmTKFOqXMlSZEAAOw=="
    }
}
