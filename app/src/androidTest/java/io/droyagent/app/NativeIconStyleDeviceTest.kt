package io.droyagent.app

import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.SystemClock
import android.view.KeyEvent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.droyagent.app.nativeui.companion.data.NativeCompanionViewModel
import io.droyagent.app.nativeui.settings.NativeSettingsViewModel
import java.io.File
import java.util.Locale
import java.util.UUID
import kotlin.math.ceil
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Real entry points and Android vector rasterization, using only the isolated local provider. */
@RunWith(AndroidJUnit4::class)
class NativeIconStyleDeviceTest {
    @get:Rule(order = 0) val compose = createEmptyComposeRule()
    @get:Rule(order = 1) val fixture = NativeIsolatedRuntimeFixture("settings", allowRoot = false)
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private lateinit var settings: NativeSettingsViewModel
    private lateinit var companions: NativeCompanionViewModel
    private var language = "en"
    private var theme = "light"

    @Test fun roundedOutlineIconsRenderAndKeepPrimaryActionsReachable() {
        fixture.scenario.onActivity {
            val models = ViewModelProvider(it)
            settings = models[NativeSettingsViewModel::class.java]
            companions = models[NativeCompanionViewModel::class.java]
        }
        compose.waitUntil(30_000) { settings.state.value.loaded && settings.state.value.writable && settings.state.value.runtime.ready }
        val id = runBlocking {
            fixture.runtimeTransport.json("/droy/companions", "POST", JSONObject()
                .put("requestId", UUID.randomUUID().toString()).put("templateId", "maimai").put("name", "Icon Style Fixture")
                .put("model", JSONObject().put("provider", "droy-native-fixture").put("model", "native-alternate"))
                .put("settings", JSONObject().put("proactiveEnabled", false).put("memoryEnabled", false)))
        }.getJSONObject("profile").getString("id")
        fixture.scenario.onActivity { companions.refresh() }
        compose.waitUntil(20_000) { companions.state.value.companions.any { it.id == id } }

        rasterizeVectors()
        for ((locale, appearance) in listOf("zh" to "light", "en" to "dark")) {
            language = locale
            theme = appearance
            fixture.scenario.onActivity { settings.setLanguage(locale) }
            compose.waitUntil(20_000) { settings.state.value.locale == locale && NativeLocale.active() == locale && !settings.state.value.saving }
            assertNull(settings.state.value.error)
            compose.runOnIdle {
                assertTrue(fixture.context.getSharedPreferences("product", 0).edit()
                    .putString("appearanceMode", theme).putString("colorSource", "preset").putString("themePalette", "purple").commit())
            }
            awaitTag("native-settings-home-list")
            assertNavigation()
            compose.onNodeWithTag("native-settings-models").performScrollTo().assertIsDisplayed()
            capture("settings")
            compose.onNodeWithTag("native-settings-general").performScrollTo().performClick()
            awaitTag("theme-palette:purple")
            compose.onNodeWithTag("theme-palette:purple").performScrollTo().assertIsSelected().assertIsDisplayed()
            capture("theme-selection")
            back(); awaitTag("native-settings-home-list")

            compose.onNodeWithTag("native-tab-messages").performClick()
            awaitTag("native-companion-conversation:$id")
            visible("native-add-companion").assertHasClickAction()
            capture("messages")
            compose.onNodeWithTag("native-companion-conversation:$id").performClick()
            awaitTag("native-companion-chat")
            visible("native-open-companion-profile").assertHasClickAction()
            visible("native-companion-composer-options").assertIsEnabled()
            visible("native-companion-send").assertIsNotEnabled()
            capture("chat")
            compose.onNodeWithTag("native-companion-composer-options").performClick()
            awaitTag("native-companion-choose-attachments")
            visible("native-companion-choose-images").assertHasClickAction()
            visible("native-companion-camera").assertHasClickAction()
            visible("native-companion-choose-attachments").assertHasClickAction()
            capture("chat-attachments")
            back(); awaitTag("native-companion-chat")
            compose.onNodeWithTag("native-open-companion-profile").performClick()
            awaitTag("native-companion-profile")
            compose.onNodeWithTag("native-companion-model").performScrollTo().assertIsDisplayed().performClick()
            awaitTag("native-companion-model-search")
            compose.onNodeWithTag("native-companion-model-option:droy-native-fixture:native-alternate").assertIsDisplayed()
            back(); awaitTag("native-companion-profile")
            back(); awaitTag("native-companion-chat")
            back(); awaitTag("native-companion-messages")

            compose.onNodeWithTag("native-tab-workbench").performClick()
            awaitTag("native-workbench")
            for (tag in listOf("native-new-session", "native-workbench-menu", "native-model", "native-composer-attachments", "native-send")) {
                visible(tag).assertHasClickAction()
            }
            capture("workbench")
            compose.onNodeWithTag("native-workbench-menu").performClick()
            awaitText(R.string.native_workbench_font)
            for (label in listOf(R.string.native_task_type, R.string.native_workbench_font, R.string.native_usage, R.string.native_schedule_title)) {
                compose.onNodeWithText(text(label)).assertIsDisplayed().assertHasClickAction()
            }
            capture("workbench-menu")
            compose.onNodeWithText(text(R.string.native_workbench_font)).performClick()
            awaitTag("native-font-slider")
            back(); awaitTag("native-workbench")
            compose.onNodeWithTag("native-composer-attachments").performClick()
            awaitText(R.string.native_engine_compact)
            for (label in listOf(R.string.native_work_photos, R.string.native_work_camera, R.string.native_share_file, R.string.native_engine_compact, R.string.native_goal_title)) {
                compose.onNodeWithText(text(label)).assertIsDisplayed()
            }
            capture("workbench-attachments")
            back(); awaitTag("native-workbench")
            compose.onNodeWithTag("native-tab-settings").performClick()
            awaitTag("native-settings-home-list")
            assertNavigation()
        }
        for (name in listOf("native-fixture-calls.jsonl", "native-fixture-companion.jsonl")) {
            val calls = File(fixture.home, name)
            assertTrue("Icon inspection must not make model calls: $name", !calls.exists() || calls.length() == 0L)
        }
    }

    private fun rasterizeVectors() {
        val resources = R.drawable::class.java.fields.filter { it.name.startsWith("native_") }.sortedBy { it.name }
        assertTrue("The new semantic icons must be packaged", resources.map { it.name }.containsAll(listOf(
            "native_ui_workbench", "native_ui_model", "native_ui_font", "native_ui_task_type",
            "native_ui_goal", "native_ui_compact", "native_ui_apps", "native_ui_warning")))
        val columns = 6
        val tileWidth = 290
        val tileHeight = 148
        val density = fixture.context.resources.displayMetrics.density
        for (dark in listOf(false, true)) {
            val board = Bitmap.createBitmap(columns * tileWidth, 65 + ceil(resources.size / columns.toDouble()).toInt() * tileHeight, Bitmap.Config.ARGB_8888)
            try {
                val canvas = Canvas(board)
                val foreground = if (dark) 0xffe7e0e8.toInt() else 0xff302c35.toInt()
                canvas.drawColor(if (dark) 0xff151218.toInt() else 0xfffef7ff.toInt())
                val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = foreground; textSize = 17f }
                canvas.drawText("Android VectorDrawable: 18 / 20 / 24 dp", 20f, 32f, paint)
                for ((index, field) in resources.withIndex()) {
                    val left = index % columns * tileWidth
                    val top = 65 + index / columns * tileHeight
                    for ((slot, dp) in listOf(18, 20, 24).withIndex()) {
                        val size = ceil(dp * density).toInt()
                        val image = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
                        try {
                            val drawable = requireNotNull(fixture.context.getDrawable(field.getInt(null))).mutate()
                            drawable.setTint(foreground)
                            drawable.setBounds(0, 0, size, size)
                            drawable.draw(Canvas(image))
                            val pixels = IntArray(size * size)
                            image.getPixels(pixels, 0, size, 0, 0, size, size)
                            val ink = pixels.count { Color.alpha(it) > 32 }
                            assertTrue("${field.name} is empty at ${dp}dp", ink > 2)
                            assertTrue("${field.name} lost its outline at ${dp}dp", ink < pixels.size * .75f)
                            canvas.drawBitmap(image, left + 14f + slot * 91, top + 8f, null)
                        } finally { image.recycle() }
                    }
                    canvas.drawText(field.name.removePrefix("native_"), left + 8f, top + 112f, paint)
                }
                val directory = File(fixture.context.getExternalFilesDir(null), "native-isolated-evidence").apply { assertTrue(isDirectory || mkdirs()) }
                File(directory, "icons-vectors-${if (dark) "dark" else "light"}.png").outputStream().use {
                    assertTrue(board.compress(Bitmap.CompressFormat.PNG, 100, it))
                }
            } finally { board.recycle() }
        }
    }

    private fun visible(tag: String): SemanticsNodeInteraction {
        val node = compose.onNodeWithTag(tag).assertIsDisplayed()
        val bounds = node.getUnclippedBoundsInRoot()
        val root = compose.onNodeWithTag("native-app-shell").getUnclippedBoundsInRoot()
        assertTrue("$tag must fit horizontally", bounds.left >= root.left && bounds.right <= root.right)
        assertTrue("$tag must fit vertically", bounds.top >= root.top && bounds.bottom <= root.bottom)
        return node
    }
    private fun assertNavigation() {
        for (tag in listOf("native-tab-messages", "native-tab-workbench", "native-tab-settings")) visible(tag).assertHasClickAction()
    }
    private fun text(resource: Int) = fixture.context.createConfigurationContext(Configuration(fixture.context.resources.configuration)
        .apply { setLocale(Locale.forLanguageTag(language)) }).getString(resource)
    private fun awaitText(resource: Int) {
        compose.waitUntil(20_000) { compose.onAllNodesWithText(text(resource)).fetchSemanticsNodes(false).isNotEmpty() }
        settle()
    }
    private fun awaitTag(tag: String) {
        compose.waitUntil(20_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes(false).isNotEmpty() }
        settle()
    }
    private fun back() { instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK); settle() }
    private fun settle() { compose.waitForIdle(); SystemClock.sleep(250); compose.waitForIdle() }
    private fun capture(scene: String) { settle(); fixture.capture("icons-$scene-$language-$theme") }
}
