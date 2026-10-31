package io.droyagent.app.nativeui.companion

import io.droyagent.app.nativeui.companion.ui.companionBackgroundPresets
import io.droyagent.app.nativeui.companion.ui.companionBackgroundPresetUrl
import io.droyagent.app.nativeui.companion.ui.companionBackgroundUrl
import io.droyagent.app.nativeui.companion.ui.companionUploadedBackground
import io.droyagent.app.nativeui.media.NativeAvatarResult
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class CompanionBackgroundsTest {
    @Test fun eightPresetsRetainLegacyIdentifiersAndProvideSeparateThumbnails() {
        assertEquals(listOf("01", "02", "03", "04", "05", "06", "07", "08"), companionBackgroundPresets.map { it.id })
        assertEquals(8, companionBackgroundPresets.map { it.label }.distinct().size)
        companionBackgroundPresets.forEach { preset ->
            assertEquals("/droy/companions/assets/wallpaper-${preset.id}.webp", companionBackgroundPresetUrl(preset.id))
            assertEquals("/droy/companions/assets/wallpaper-${preset.id}-thumb.webp", companionBackgroundPresetUrl(preset.id, thumbnail = true))
            val saved = JSONObject().put("type", "preset").put("id", preset.id).put("url", "ignored-stale-url")
            assertEquals(companionBackgroundPresetUrl(preset.id), companionBackgroundUrl(saved))
        }
    }

    @Test fun invalidPresetIdentifiersDoNotBecomeAssetPaths() {
        listOf("00", "09", "1", "../01", "", "default", "upload").forEach { id ->
            assertNull(companionBackgroundPresetUrl(id))
            assertNull(companionBackgroundUrl(JSONObject().put("type", "preset").put("id", id)))
        }
    }

    @Test fun defaultHasNoImageAndUploadsKeepTheirStoredUrl() {
        assertNull(companionBackgroundUrl(JSONObject().put("type", "default")))
        val uploaded = JSONObject().put("type", "upload").put("id", "fictional-background")
            .put("url", "/droy/companions/fixture/attachments/fictional-background")
            .put("position", JSONObject().put("x", 25).put("y", 75))
        assertEquals(uploaded.getString("url"), companionBackgroundUrl(uploaded))
        assertEquals(25, uploaded.getJSONObject("position").getInt("x"))
        assertNull(companionBackgroundUrl(JSONObject().put("type", "upload").put("url", "")))
    }

    @Test fun uploadedBackgroundUsesTheAttachmentReceiptRatherThanThePickerOperationId() {
        val receipt = NativeAvatarResult("picker-operation", "fictional-companion",
            "/droy/companions/fictional-companion/attachments/uploaded-image", "uploaded-image")
        val selection = JSONObject(companionUploadedBackground(receipt))
        assertEquals("upload", selection.getString("type"))
        assertEquals(receipt.attachmentId, selection.getString("id"))
        assertNotEquals(receipt.id, selection.getString("id"))
        assertEquals(receipt.url, selection.getString("url"))
        assertEquals(50, selection.getJSONObject("position").getInt("x"))
        assertEquals(50, selection.getJSONObject("position").getInt("y"))
    }
}
