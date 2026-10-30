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
import android.provider.MediaStore
import android.view.KeyEvent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.droyagent.app.nativeui.companion.data.NativeCompanionClient
import io.droyagent.app.nativeui.companion.data.NativeCompanionViewModel
import java.io.ByteArrayOutputStream
import java.util.Locale
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Real import, profile and media endpoints; all pictures and conversations are fictional. */
@RunWith(AndroidJUnit4::class)
class NativeCompanionStickerPacksDeviceTest {
    @get:Rule(order = 0) val compose = createEmptyComposeRule()
    @get:Rule(order = 1) val fixture = NativeIsolatedRuntimeFixture()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val language get() = InstrumentationRegistry.getArguments().getString("nativeFixtureLocale", "en")
    private val inserted = mutableListOf<Uri>()
    private lateinit var model: NativeCompanionViewModel

    @After fun removeOnlyFixtureDocuments() {
        inserted.forEach { uri ->
            assertEquals(1, fixture.context.contentResolver.delete(uri, null, null))
            fixture.context.revokeUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    @Test fun importedPacksAreSharedButEnabledPerCompanionAndHistorySurvivesDisabling() {
        bind()
        compose.waitUntil(30_000) { model.state.value.runtime.ready && model.state.value.companions.isNotEmpty() }
        val first = model.state.value.companions.first().id
        request("/$first", "PATCH", JSONObject().put("settings", JSONObject().put("proactiveEnabled", false)))
        val second = request(method = "POST", body = JSONObject().put("requestId", UUID.randomUUID().toString())
            .put("templateId", "maimai").put("name", "Fictional sticker friend")
            .put("settings", JSONObject().put("proactiveEnabled", false))).getJSONObject("profile").getString("id")
        val secondBefore = profile(second).getJSONObject("settings").toString()
        fixture.scenario.onActivity { model.refresh() }
        compose.waitUntil(15_000) { model.state.value.companions.any { it.id == second } }

        compose.onNodeWithTag("native-tab-messages").performClick()
        awaitTag("native-companion-conversation:$first")
        compose.onNodeWithTag("native-companion-conversation:$first").performClick()
        awaitTag("native-open-companion-profile")
        openPacks()
        compose.onNodeWithTag("native-companion-sticker-pack-droy-default").assertIsOn()
        capture("empty-library")

        val gif = android.util.Base64.decode("R0lGODlhAQABAIAAAAAAAP///yH5BAEAAAAALAAAAAABAAEAAAIBRAA7", android.util.Base64.DEFAULT)
        pickZip(document(zip(mapOf("daily/animation.gif" to gif))), expectError = true)
        assertEquals("COMPANION_STICKER_PACK_UNSUPPORTED", model.state.value.error)
        assertEquals(1, model.state.value.stickerPacks.size)
        capture("unsupported-animation")
        fixture.scenario.onActivity { model.dismissError() }

        pickZip(document(zip(linkedMapOf("happy/Fictional-wave.png" to png(Color.rgb(99, 87, 179)),
            "daily/Fictional-rest.png" to png(Color.rgb(52, 126, 115))))))
        val imported = model.state.value.stickerPacks.single { !it.builtin }
        val packId = imported.id
        assertEquals(2, imported.count)
        assertTrue(model.state.value.companions.single { it.id == first }.settings.stickerPackIds.orEmpty().contains(packId))
        assertEquals(secondBefore, profile(second).getJSONObject("settings").toString())
        compose.onNodeWithTag("native-companion-sticker-pack-$packId").assertIsOn()
        capture("imported-library")

        // Save one library for this companion; an unrelated companion keeps its settings.
        compose.onNodeWithTag("native-companion-sticker-pack-droy-default").performClick()
        compose.onNodeWithTag("native-companion-sticker-packs-import").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("native-companion-sticker-packs-save").assertIsEnabled().performClick()
        awaitTag("native-companion-profile")
        awaitEnabled(first, listOf(packId))
        compose.onNodeWithTag("native-companion-sticker-packs").performScrollTo().performClick()
        awaitTag("native-companion-sticker-packs-page")
        compose.onNodeWithTag("native-companion-sticker-pack-droy-default").assertIsOff()
        compose.onNodeWithTag("native-companion-sticker-pack-$packId").assertIsOn()
        capture("saved-selection")
        back(); awaitTag("native-companion-profile")
        back(); awaitTag("native-companion-composer")

        val sticker = model.state.value.stickers.first { it.packId == packId }
        compose.onNodeWithTag("native-companion-composer-options").performClick()
        awaitTag("native-companion-send-sticker:${sticker.id}")
        compose.onNodeWithTag("native-companion-send-sticker:${sticker.id}").assertIsDisplayed()
        compose.onNodeWithTag("native-companion-send-sticker:test-sticker-ok").assertDoesNotExist()
        capture("imported-picker")
        compose.onNodeWithTag("native-companion-send-sticker:${sticker.id}").performClick()
        compose.waitUntil(20_000) {
            !model.state.value.sending && model.state.value.messages.any { it.stickerId == sticker.id && it.status !in setOf("sending", "pending", "failed") }
        }
        compose.onAllNodesWithContentDescription(sticker.label).onFirst().assertIsDisplayed()
        capture("imported-message")

        // Exercise the real companion tool, publication and native assistant row.
        fixture.scenario.onActivity { model.setDraft("NATIVE_COMPANION_STICKER"); model.send() }
        compose.waitUntil(25_000) {
            model.state.value.messages.any { it.role == "assistant" && it.stickerId != null }
        }
        val companionSticker = model.state.value.messages.last { it.role == "assistant" && it.stickerId != null }
        assertTrue(model.state.value.stickers.any { it.id == companionSticker.stickerId && it.packId == packId })
        compose.onNodeWithTag("native-companion-message:${companionSticker.id}").assertIsDisplayed()
        capture("companion-sent-sticker")

        // Disabling controls future sends; the shared catalog remains available to old messages.
        openPacks()
        compose.onNodeWithTag("native-companion-sticker-pack-$packId").performClick()
        capture("no-enabled-packs")
        compose.onNodeWithTag("native-companion-sticker-packs-save").performClick()
        awaitTag("native-companion-profile")
        awaitEnabled(first, emptyList())
        back(); awaitTag("native-companion-composer")
        compose.onAllNodesWithContentDescription(sticker.label).onFirst().assertIsDisplayed()
        compose.onNodeWithTag("native-companion-composer-options").performClick()
        compose.onNodeWithTag("native-companion-send-sticker:${sticker.id}").assertDoesNotExist()
        capture("disabled-picker")
        systemBack()

        // Leaving a changed selection uses the existing discard flow and does not persist it.
        openPacks()
        compose.onNodeWithTag("native-companion-sticker-pack-$packId").performClick()
        back()
        compose.onNodeWithText(text(R.string.native_companion_discard_title)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.native_companion_discard)).performClick()
        awaitTag("native-companion-profile")
        awaitEnabled(first, emptyList())
        compose.onNodeWithTag("native-companion-sticker-packs").performScrollTo().performClick()
        awaitTag("native-companion-sticker-packs-page")
        compose.onNodeWithTag("native-companion-sticker-pack-$packId").assertIsOff()
        back(); awaitTag("native-companion-profile")
        back(); awaitTag("native-companion-composer")

        fixture.recreate(); bind()
        compose.waitUntil(20_000) { model.state.value.runtime.ready && model.state.value.messages.any { it.stickerId == sticker.id } }
        compose.onAllNodesWithContentDescription(sticker.label).onFirst().assertIsDisplayed()
        val restoredBytes = runBlocking { NativeCompanionClient().media(sticker.url) }
        assertTrue(listOf(png(Color.rgb(99, 87, 179)), png(Color.rgb(52, 126, 115))).any { it.contentEquals(restoredBytes) })
        assertEquals(secondBefore, profile(second).getJSONObject("settings").toString())
        assertTrue(request().getJSONArray("stickerPacks").let { packs -> (0 until packs.length()).any { packs.getJSONObject(it).getString("id") == packId } })
        capture("history-after-recreation")
        assertNull(model.state.value.error)
    }

    private fun bind() { fixture.scenario.onActivity { model = ViewModelProvider(it)[NativeCompanionViewModel::class.java] } }
    private fun openPacks() {
        compose.onNodeWithTag("native-open-companion-profile").performClick()
        awaitTag("native-companion-profile")
        compose.onNodeWithTag("native-companion-sticker-packs").performScrollTo().performClick()
        awaitTag("native-companion-sticker-packs-page")
    }
    private fun awaitEnabled(id: String, expected: List<String>) {
        compose.waitUntil(15_000) { !model.state.value.profileSaving && model.state.value.companions.single { it.id == id }.settings.stickerPackIds == expected }
        assertNull(model.state.value.error)
    }
    private fun pickZip(uri: Uri, expectError: Boolean = false) {
        val before = model.state.value.stickerPacks.size
        val filter = IntentFilter().apply { addAction(Intent.ACTION_OPEN_DOCUMENT); addCategory(Intent.CATEGORY_OPENABLE); addDataType("*/*") }
        val result = Intent().setData(uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val monitor = instrumentation.addMonitor(filter, Instrumentation.ActivityResult(Activity.RESULT_OK, result), true)
        try {
            compose.onNodeWithTag("native-companion-sticker-packs-import").performScrollTo().assertIsEnabled().performClick()
            compose.waitUntil(10_000) { monitor.hits == 1 }
            compose.waitUntil(20_000) { !model.state.value.stickerPackImporting &&
                (if (expectError) model.state.value.error != null else model.state.value.stickerPacks.size > before) }
            if (!expectError) assertNull(model.state.value.error)
        } finally { instrumentation.removeMonitor(monitor) }
    }
    private fun document(bytes: ByteArray): Uri {
        val values = ContentValues().apply { put(MediaStore.MediaColumns.DISPLAY_NAME, "fictional-stickers-${UUID.randomUUID()}.zip")
            put(MediaStore.MediaColumns.MIME_TYPE, "application/zip"); put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/DroyNativeFixture") }
        val uri = requireNotNull(fixture.context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values))
        inserted += uri
        requireNotNull(fixture.context.contentResolver.openOutputStream(uri)).use { it.write(bytes) }
        fixture.context.grantUriPermission(fixture.context.packageName, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return uri
    }
    private fun zip(entries: Map<String, ByteArray>): ByteArray = ByteArrayOutputStream().use { bytes ->
        ZipOutputStream(bytes).use { zip -> entries.forEach { (name, data) -> zip.putNextEntry(ZipEntry(name)); zip.write(data); zip.closeEntry() } }
        bytes.toByteArray()
    }
    private fun png(color: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).apply { drawColor(Color.TRANSPARENT); drawCircle(48f, 48f, 36f, Paint().apply { this.color = color }) }
        return try { ByteArrayOutputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)); it.toByteArray() } } finally { bitmap.recycle() }
    }
    private fun request(path: String = "", method: String = "GET", body: JSONObject? = null): JSONObject = runBlocking {
        fixture.runtimeTransport.json("/droy/companions$path", method, body)
    }
    private fun profile(id: String): JSONObject = request().getJSONArray("companions").let { profiles ->
        (0 until profiles.length()).map(profiles::getJSONObject).single { it.getString("id") == id }
    }
    private fun text(resource: Int): String {
        val configuration = Configuration(fixture.context.resources.configuration).apply { setLocale(Locale(language)) }
        return fixture.context.createConfigurationContext(configuration).getString(resource)
    }
    private fun back() { compose.onNodeWithContentDescription(text(R.string.native_companion_back)).performClick(); compose.waitForIdle() }
    private fun systemBack() { instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK); compose.waitForIdle() }
    private fun awaitTag(tag: String) { compose.waitUntil(20_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes(false).isNotEmpty() }; compose.waitForIdle() }
    private fun capture(name: String) {
        compose.waitForIdle(); compose.mainClock.advanceTimeBy(1_000); compose.waitForIdle()
        fixture.capture("sticker-packs-$language-$name")
    }
}
