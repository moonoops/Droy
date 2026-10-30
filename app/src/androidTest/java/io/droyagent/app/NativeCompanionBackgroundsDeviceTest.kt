package io.droyagent.app

import android.app.Activity
import android.app.Instrumentation
import android.content.ContentValues
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.provider.MediaStore
import android.view.KeyEvent
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.droyagent.app.nativeui.companion.data.NativeCompanionViewModel
import io.droyagent.app.nativeui.media.NativeMediaViewModel
import io.droyagent.app.nativeui.settings.NativeSettingsViewModel
import java.io.File
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Each locale runs in its own fixture; the picker returns only this test's synthetic image. */
@RunWith(AndroidJUnit4::class)
class NativeCompanionBackgroundsDeviceTest {
    @get:Rule(order = 0) val compose = createEmptyComposeRule()
    @get:Rule(order = 1) val fixture = NativeIsolatedRuntimeFixture("settings", allowRoot = false)
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val language get() = InstrumentationRegistry.getArguments().getString("nativeFixtureLocale", "en")
    private lateinit var companions: NativeCompanionViewModel
    private lateinit var media: NativeMediaViewModel
    private lateinit var settings: NativeSettingsViewModel
    private var theme = "light"

    @Test fun allEightBackgroundsAndCustomUploadKeepApplyAndDiscardBoundaries() {
        fixture.scenario.onActivity {
            val models = ViewModelProvider(it)
            companions = models[NativeCompanionViewModel::class.java]
            media = models[NativeMediaViewModel::class.java]
            settings = models[NativeSettingsViewModel::class.java]
        }
        compose.waitUntil(30_000) { settings.state.value.loaded && settings.state.value.writable && settings.state.value.runtime.ready }
        assertEquals(language, NativeLocale.active())
        setTheme("light")
        val id = request(method = "POST", body = JSONObject().put("requestId", UUID.randomUUID().toString())
            .put("templateId", "maimai").put("name", "Wallpaper fixture")
            .put("model", JSONObject().put("provider", "droy-native-fixture").put("model", "native-alternate"))
            .put("settings", JSONObject().put("proactiveEnabled", false).put("memoryEnabled", false)))
            .getJSONObject("profile").getString("id")
        fixture.scenario.onActivity { companions.refresh() }
        compose.waitUntil(20_000) { companions.state.value.companions.any { it.id == id } }
        compose.onNodeWithTag("native-tab-messages").performClick()
        awaitTag("native-companion-conversation:$id")
        compose.onNodeWithTag("native-companion-conversation:$id").performClick()
        awaitTag("native-companion-chat")
        openEditorFromChat()
        compose.onNodeWithTag("native-background-option-default").assertIsSelected()
        compose.onNodeWithTag("native-creation-background-upload").performScrollTo().assertIsDisplayed().assertIsEnabled()
        capture("default-editor")

        val labels = listOf(
            R.string.native_companion_background_paper_light, R.string.native_companion_background_paper_dark,
            R.string.native_companion_background_mist_light, R.string.native_companion_background_mist_dark,
            R.string.native_companion_background_leaves_light, R.string.native_companion_background_leaves_dark,
            R.string.native_companion_background_lines_light, R.string.native_companion_background_lines_dark)
        for ((index, label) in labels.withIndex()) {
            val preset = "%02d".format(Locale.ROOT, index + 1)
            setTheme(if (index % 2 == 0) "light" else "dark")
            val before = savedBackground(id).toString()
            compose.onNodeWithTag("native-background-option-$preset").performScrollTo()
                .assertIsDisplayed().assertTextContains(localized(label)).performClick().assertIsSelected()
            assertEquals("A preview must not save the profile", before, savedBackground(id).toString())
            if (preset == "08") capture("all-options-bottom")
            compose.onNodeWithTag("native-background-preview").performScrollTo().assertIsDisplayed()
            capture("preset-$preset-preview")
            applyBackground()
            assertEquals(preset, savedBackground(id).getString("id"))
            openBackground()
            compose.onNodeWithTag("native-background-option-$preset").performScrollTo().assertIsSelected()
            back()
            awaitTag("native-companion-interaction-save")
            backToChat()
            compose.onNodeWithTag("native-companion-chat-background").assertIsDisplayed()
            capture("preset-$preset-chat")
            if (preset in setOf("01", "02")) {
                val originalTheme = theme
                setTheme(if (originalTheme == "light") "dark" else "light")
                capture("preset-$preset-chat-cross-theme")
                setTheme(originalTheme)
            }
            openEditorFromChat()
        }

        compose.onNodeWithTag("native-background-option-01").performScrollTo().performClick()
        discardBackground()
        assertEquals("08", savedBackground(id).getString("id"))
        openBackground()
        chooseDefault()
        assertEquals("default", savedBackground(id).getString("type"))
        backToChat()
        compose.onNodeWithTag("native-companion-chat-background").assertDoesNotExist()
        openEditorFromChat()

        val image = insertFixtureImage()
        try {
            val before = savedBackground(id).toString()
            pickBackground(image)
            compose.onNodeWithTag("native-background-option-upload").performScrollTo().assertIsSelected()
            assertEquals("Uploading must not apply the background", before, savedBackground(id).toString())
            setPosition("x", 30f)
            setPosition("y", 75f)
            capture("upload-position")
            applyBackground()
            val custom = savedBackground(id)
            assertEquals("upload", custom.getString("type"))
            assertTrue(custom.getString("url").startsWith("/droy/companions/$id/attachments/"))
            assertEquals(30.0, custom.getJSONObject("position").getDouble("x"), 0.001)
            assertEquals(75.0, custom.getJSONObject("position").getDouble("y"), 0.001)
            openBackground()
            compose.onNodeWithTag("native-background-option-upload").performScrollTo().assertIsSelected()
            assertPosition("x", 30f)
            assertPosition("y", 75f)
            compose.onNodeWithTag("native-background-option-07").performScrollTo().performClick()
            discardBackground()
            assertEquals(custom.toString(), savedBackground(id).toString())
            backToChat()
            compose.onNodeWithTag("native-companion-chat-background").assertIsDisplayed()
            capture("upload-chat")
            openEditorFromChat()
            chooseDefault()
            assertEquals("default", savedBackground(id).getString("type"))
        } finally {
            assertEquals("Delete only this fixture's MediaStore image", 1, fixture.context.contentResolver.delete(image, null, null))
            fixture.context.revokeUriPermission(image, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        for (name in listOf("native-fixture-calls.jsonl", "native-fixture-companion.jsonl")) {
            val calls = File(fixture.home, name)
            assertTrue("Background selection must not call models", !calls.exists() || calls.length() == 0L)
        }
    }

    private fun openEditorFromChat() {
        compose.onNodeWithTag("native-open-companion-profile").performClick()
        awaitTag("native-companion-profile")
        compose.onNodeWithTag("native-companion-interaction").performScrollTo().performClick()
        awaitTag("native-companion-interaction-save")
        openBackground()
    }
    private fun openBackground() {
        compose.onNodeWithTag("native-companion-background").performScrollTo().performClick()
        awaitTag("native-background-apply")
    }
    private fun applyBackground() {
        compose.onNodeWithTag("native-background-apply").performClick()
        try {
            compose.waitUntil(20_000) { !exists("native-background-apply") && !companions.state.value.profileSaving }
        } catch (error: Throwable) {
            val state = companions.state.value
            val failure = AssertionError("Background Apply did not finish: profileSaving=${state.profileSaving}, " +
                "companionError=${state.error}, mediaError=${media.state.value.error}", error)
            try { capture("apply-error") } catch (captureError: Throwable) { failure.addSuppressed(captureError) }
            throw failure
        }
        assertNull(companions.state.value.error)
        awaitTag("native-companion-interaction-save")
    }
    private fun chooseDefault() {
        compose.onNodeWithTag("native-background-option-default").performScrollTo().performClick()
        applyBackground()
    }
    private fun backToChat() {
        back(); awaitTag("native-companion-profile")
        back(); awaitTag("native-companion-chat")
    }
    private fun discardBackground() {
        back()
        val discard = hasText(localized(R.string.native_companion_discard)) and hasAnyAncestor(isDialog())
        compose.waitUntil(10_000) { compose.onAllNodes(discard).fetchSemanticsNodes(false).isNotEmpty() }
        compose.onNode(discard).performClick()
        awaitTag("native-companion-interaction-save")
    }
    private fun setTheme(value: String) {
        theme = value
        compose.runOnIdle { assertTrue(fixture.context.getSharedPreferences("product", 0).edit().putString("appearanceMode", value).commit()) }
        settle()
    }
    private fun setPosition(axis: String, value: Float) {
        compose.onNodeWithTag("native-background-position-$axis").performScrollTo()
            .performSemanticsAction(SemanticsActions.SetProgress) { assertTrue(it(value)) }
        assertPosition(axis, value)
    }
    private fun assertPosition(axis: String, value: Float) {
        compose.onNodeWithTag("native-background-position-$axis").performScrollTo()
            .assertRangeInfoEquals(ProgressBarRangeInfo(value, 0f..100f))
    }
    private fun insertFixtureImage(): Uri {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "droy-wallpaper-fixture-${UUID.randomUUID()}.png")
            put(MediaStore.MediaColumns.MIME_TYPE, "image/png")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/DroyNativeFixture")
        }
        val uri = requireNotNull(fixture.context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values))
        val bitmap = Bitmap.createBitmap(240, 800, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(bitmap)
            canvas.drawColor(Color.rgb(46, 70, 98))
            canvas.drawRect(120f, 0f, 240f, 800f, Paint().apply { color = Color.rgb(226, 213, 188) })
            requireNotNull(fixture.context.contentResolver.openOutputStream(uri)).use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            fixture.context.grantUriPermission(fixture.context.packageName, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            return uri
        } catch (error: Throwable) {
            fixture.context.contentResolver.delete(uri, null, null)
            throw error
        } finally { bitmap.recycle() }
    }
    private fun pickBackground(uri: Uri) {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_OPEN_DOCUMENT)
            if (Build.VERSION.SDK_INT >= 33) addAction(MediaStore.ACTION_PICK_IMAGES)
            addCategory(Intent.CATEGORY_OPENABLE)
            addDataType("image/*")
        }
        val result = Intent().setData(uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val monitor = instrumentation.addMonitor(filter, Instrumentation.ActivityResult(Activity.RESULT_OK, result), true)
        try {
            compose.onNodeWithTag("native-creation-background-upload").performScrollTo().performClick()
            compose.waitUntil(10_000) { monitor.hits == 1 }
            compose.waitUntil(20_000) { exists("native-background-option-upload") && !media.state.value.busy }
            assertNull(media.state.value.error)
        } finally { instrumentation.removeMonitor(monitor) }
    }
    private fun savedBackground(id: String): JSONObject = request().getJSONArray("companions").let { rows ->
        (0 until rows.length()).map(rows::getJSONObject).single { it.getString("id") == id }.getJSONObject("chatBackground")
    }
    private fun request(path: String = "", method: String = "GET", body: JSONObject? = null): JSONObject = runBlocking {
        fixture.runtimeTransport.json("/droy/companions$path", method, body)
    }
    private fun localized(resource: Int) = fixture.context.createConfigurationContext(Configuration(fixture.context.resources.configuration)
        .apply { setLocale(Locale.forLanguageTag(language)) }).getString(resource)
    private fun exists(tag: String) = compose.onAllNodesWithTag(tag).fetchSemanticsNodes(false).isNotEmpty()
    private fun awaitTag(tag: String) { compose.waitUntil(20_000) { exists(tag) }; settle() }
    private fun back() { instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK); instrumentation.waitForIdleSync(); settle() }
    private fun settle() { compose.waitForIdle(); SystemClock.sleep(400); compose.waitForIdle() }
    private fun capture(scene: String) { settle(); fixture.capture("wallpaper-$scene-$language-$theme") }
}
