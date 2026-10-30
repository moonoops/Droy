package io.droyagent.app

import android.content.res.Configuration
import android.graphics.drawable.AnimatedImageDrawable
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.droyagent.app.nativeui.companion.data.NativeCompanionViewModel
import io.droyagent.app.nativeui.companion.ui.CompanionGifImageView
import java.io.File
import java.util.Locale
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Real public sources and local model fixture; all changes stay inside the isolated development runtime. */
@RunWith(AndroidJUnit4::class)
class NativeCompanionOnlineStickersDeviceTest {
    @get:Rule(order = 0) val compose = createEmptyComposeRule()
    @get:Rule(order = 1) val fixture = NativeIsolatedRuntimeFixture("messages")
    private val language get() = InstrumentationRegistry.getArguments().getString("nativeFixtureLocale", "en")
    private lateinit var model: NativeCompanionViewModel

    @Test fun publicMarketplaceAndAutonomousOnlineImageReachNativeChat() {
        fixture.scenario.onActivity { model = ViewModelProvider(it)[NativeCompanionViewModel::class.java] }
        compose.waitUntil(30_000) { model.state.value.runtime.ready && model.state.value.companions.isNotEmpty() }
        val id = model.state.value.companions.first().id
        request("/$id", "PATCH", JSONObject().put("settings", JSONObject().put("proactiveEnabled", false).put("memoryEnabled", false)))
        fixture.scenario.onActivity { model.refresh() }
        awaitTag("native-companion-conversation:$id")
        compose.onNodeWithTag("native-companion-conversation:$id").performClick()
        awaitTag("native-open-companion-profile")
        compose.onNodeWithTag("native-open-companion-profile").performClick()
        awaitTag("native-companion-profile")
        compose.onNodeWithTag("native-companion-sticker-packs").performScrollTo().performClick()
        awaitTag("native-companion-sticker-packs-page")
        compose.onNodeWithTag("native-companion-sticker-market").performScrollTo().performClick()
        awaitTag("native-companion-market-page")
        compose.waitUntil(90_000) {
            compose.onAllNodes(hasAnyAncestor(hasTestTag("native-companion-market-results")) and hasClickAction()).fetchSemanticsNodes(false).isNotEmpty()
                || compose.onAllNodesWithTag("native-companion-market-error").fetchSemanticsNodes(false).isNotEmpty()
        }
        compose.onNodeWithTag("native-companion-market-error").assertDoesNotExist()
        compose.onNodeWithTag("native-companion-market-results")
            .performScrollToNode(hasTestTag("native-companion-market-install:dafeiyu-001"))
        capture("market-public-list")
        compose.onNodeWithTag("native-companion-market-install:dafeiyu-001").performClick()
        compose.waitUntil(180_000) {
            compose.onAllNodesWithTag("native-companion-market-success").fetchSemanticsNodes(false).isNotEmpty()
                || compose.onAllNodesWithTag("native-companion-market-error").fetchSemanticsNodes(false).isNotEmpty()
        }
        compose.onNodeWithTag("native-companion-market-error").assertDoesNotExist()
        compose.onNodeWithTag("native-companion-market-success").assertIsDisplayed()
        val installed = request().getJSONArray("stickerPacks").let { packs ->
            (0 until packs.length()).map(packs::getJSONObject).single { it.optString("originId") == "dafeiyu-001" }
        }
        assertEquals(24, installed.getInt("count"))
        capture("market-public-installed")
        back(); awaitTag("native-companion-sticker-packs-page")
        back(); awaitTag("native-companion-profile")
        back(); awaitTag("native-companion-composer")

        fixture.scenario.onActivity { model.setDraft("NATIVE_COMPANION_ONLINE_STICKER"); model.send() }
        compose.waitUntil(180_000) { model.state.value.messages.any { it.role == "assistant" && it.stickerId != null } }
        val message = model.state.value.messages.last { it.role == "assistant" && it.stickerId != null }
        val sticker = request().getJSONArray("stickers").let { stickers ->
            (0 until stickers.length()).map(stickers::getJSONObject).single { it.getString("id") == message.stickerId }
        }
        assertTrue(sticker.getString("description").isNotBlank())
        assertTrue(sticker.getString("source").startsWith("https://"))
        assertTrue(sticker.getString("url").startsWith("/droy/companions/sticker-media/"))
        val image = runBlocking { fixture.runtimeTransport.bytes(sticker.getString("url")) }
        assertTrue(String(image.copyOfRange(0, 6), Charsets.US_ASCII) in setOf("GIF87a", "GIF89a"))
        val audit = File(fixture.home, "native-fixture-companion.jsonl").readLines().map(::JSONObject)
            .filter { it.optString("kind") == "online-sticker-tool" }
        assertEquals(listOf("companion_sticker_search", "companion_sticker_download", "companion_emoji"),
            audit.take(3).map { it.getString("name") })
        compose.waitUntil(15_000) {
            var playing = false
            fixture.scenario.onActivity { activity ->
                playing = descendants(activity.window.decorView).filterIsInstance<CompanionGifImageView>()
                    .any { (it.drawable as? AnimatedImageDrawable)?.isRunning == true }
            }
            playing
        }
        compose.waitUntil(15_000) { model.state.value.status.chatExecution?.quiescent == true }
        compose.onNodeWithTag("native-companion-message:${message.id}").assertIsDisplayed()
        capture("companion-online-sticker")
    }

    private fun request(path: String = "", method: String = "GET", body: JSONObject? = null): JSONObject = runBlocking {
        fixture.runtimeTransport.json("/droy/companions$path", method, body)
    }
    private fun back() {
        val configuration = Configuration(fixture.context.resources.configuration).apply { setLocale(Locale(language)) }
        val label = fixture.context.createConfigurationContext(configuration).getString(R.string.native_companion_back)
        compose.onNodeWithContentDescription(label).performClick(); compose.waitForIdle()
    }
    private fun awaitTag(tag: String) {
        compose.waitUntil(20_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes(false).isNotEmpty() }
        compose.waitForIdle()
    }
    private fun capture(stage: String) {
        compose.waitForIdle(); compose.mainClock.advanceTimeBy(1000); compose.waitForIdle()
        fixture.capture("online-stickers-$language-$stage")
    }
    private fun descendants(view: View): List<View> = listOf(view) + if (view is ViewGroup)
        (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
}
