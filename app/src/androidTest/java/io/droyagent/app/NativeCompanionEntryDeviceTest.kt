package io.droyagent.app

import android.graphics.Bitmap
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.droyagent.app.nativeui.companion.data.*
import io.droyagent.app.nativeui.companion.ui.*
import io.droyagent.app.nativeui.data.NativeRuntimeState
import io.droyagent.app.nativeui.ui.DroyTheme
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.delay
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NativeCompanionEntryDeviceTest {
    @get:Rule(order = 0) val compose = createEmptyComposeRule()
    @get:Rule(order = 1) val fixture = NativeIsolatedRuntimeFixture()

    @Test fun enteringStartsAtLatestAndDelayedImagesDoNotDisplaceTailOrHistoryReader() {
        lateinit var model: NativeCompanionViewModel
        val png = imageBytes()
        val image = NativeCompanionAttachment("photo", "Photo.png", "image/png", png.size.toLong(), "entry-image")
        val rows = (1..24).map { index -> NativeCompanionMessage("message-$index", "assistant", "text",
            "Fictional history $index\nSecond line\nThird line", "2026-10-30T01:00:00Z", "sent") }
        val shown = mutableStateOf(NativeCompanionState(runtime = NativeRuntimeState(ready = true),
            companions = listOf(NativeCompanion("friend", "Friend", "entry-avatar")), selectedCompanionId = "friend",
            messages = rows + NativeCompanionMessage("latest", "user", "image", "Latest message", "2026-10-30T01:00:00Z", "sent", attachments = listOf(image)),
            readingAnchor = NativeCompanionReadingAnchor("message-1", 0)))
        fixture.scenario.onActivity { activity ->
            model = ViewModelProvider(activity)[NativeCompanionViewModel::class.java]
            activity.setContent { DroyTheme { CompanionChatScreen(shown.value, model, {}, {}, {},
                imageLoader = { url -> if (url == "entry-image") delay(400); png }) } }
        }
        compose.onNodeWithText("Latest message").assertIsDisplayed()
        compose.waitUntil(10_000) { cachedCompanionImageSize("entry-image") != null }
        compose.onNodeWithText("Latest message").assertIsDisplayed()
        fixture.capture("round5-companion-entry-latest")
        compose.onNodeWithTag("native-companion-history").performScrollToNode(hasTestTag("native-companion-message:message-8"))
        compose.onNodeWithTag("native-companion-history").performTouchInput {
            swipe(center, center + androidx.compose.ui.geometry.Offset(0f, 40f), 200)
        }
        compose.onNodeWithText("Fictional history 8\nSecond line\nThird line").assertIsDisplayed()
        compose.runOnIdle { shown.value = shown.value.copy(messages = shown.value.messages +
            NativeCompanionMessage("new-reply", "assistant", "text", "New incoming reply", "2026-10-30T01:01:00Z", "sent")) }
        compose.onNodeWithText("Fictional history 8\nSecond line\nThird line").assertIsDisplayed()
        compose.onNodeWithText("New incoming reply").assertIsNotDisplayed()
        fixture.capture("round5-companion-history-reader")
    }

    @Test fun repeatedVisibleAvatarsShareOneInFlightDecode() {
        clearCompanionImageCache()
        val calls = AtomicInteger()
        val png = imageBytes()
        fixture.scenario.onActivity { activity -> activity.setContent { DroyTheme {
            Column { repeat(6) { index -> CompanionAvatar("Avatar $index", "entry-coalesced-avatar", {
                calls.incrementAndGet(); delay(250); png
            }) } }
        } } }
        compose.waitUntil(10_000) { calls.get() > 0 }
        compose.waitUntil(10_000) { compose.onAllNodesWithText("A").fetchSemanticsNodes(false).isEmpty() }
        assertEquals(1, calls.get())
    }

    private fun imageBytes(): ByteArray = Bitmap.createBitmap(96, 192, Bitmap.Config.ARGB_8888).let { bitmap ->
        try { ByteArrayOutputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it); it.toByteArray() } }
        finally { bitmap.recycle() }
    }
}
