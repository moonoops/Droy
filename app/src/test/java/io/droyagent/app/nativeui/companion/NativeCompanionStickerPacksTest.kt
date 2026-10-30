package io.droyagent.app.nativeui.companion

import io.droyagent.app.nativeui.companion.data.*
import io.droyagent.app.nativeui.data.NativeApiException
import java.io.ByteArrayInputStream
import java.io.InputStream
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class NativeCompanionStickerPacksTest {
    private fun wire(settings: JSONObject) = JSONObject().put("id", "fixture").put("name", "Fixture")
        .put("avatarUrl", "/droy/companions/assets/kaka.png").put("settings", settings)
    private fun settings() = JSONObject().put("proactiveEnabled", true).put("memoryEnabled", false).put("allowProfanity", false)
        .put("quietHours", JSONObject().put("enabled", true).put("start", "22:00").put("end", "09:00"))

    @Test fun anAbsentSelectionKeepsTheBuiltInPackAndAnEmptySelectionDisablesAllPacks() {
        val all = listOf(NativeCompanionSticker("builtin", "/builtin"), NativeCompanionSticker("imported", "/imported", packId = "pack-fixture"))
        val legacy = NativeCompanionJson.profile(wire(settings())).settings
        assertNull(legacy.stickerPackIds)
        assertEquals(listOf("builtin"), enabledCompanionStickers(legacy, all).map { it.id })
        val disabled = NativeCompanionJson.profile(wire(settings().put("stickerPackIds", JSONArray()))).settings
        assertTrue(disabled.stickerPackIds!!.isEmpty())
        assertTrue(enabledCompanionStickers(disabled, all).isEmpty())
        val importedOnly = disabled.copy(stickerPackIds = listOf("pack-fixture"))
        assertEquals(listOf("imported"), enabledCompanionStickers(importedOnly, all).map { it.id })
        assertEquals(2, all.size) // History still receives the full catalog.
    }

    @Test fun selectionChangesPreserveInteractionSettingsAndSerializeExplicitlyEmptySelections() {
        val parsed = NativeCompanionJson.profile(wire(settings().put("stickerPackIds", JSONArray().put("pack-fixture")))).settings
        val patch = NativeCompanionJson.patch(NativeCompanionProfilePatch(settings = parsed.copy(stickerPackIds = emptyList()))).getJSONObject("settings")
        assertTrue(patch.getBoolean("proactiveEnabled"))
        assertFalse(patch.getBoolean("memoryEnabled"))
        assertFalse(patch.getBoolean("allowProfanity"))
        assertEquals("22:00", patch.getJSONObject("quietHours").getString("start"))
        assertEquals(0, patch.getJSONArray("stickerPackIds").length())
        val interactionCopy = parsed.copy(proactiveEnabled = false)
        assertEquals(listOf("pack-fixture"), interactionCopy.stickerPackIds)
        assertFalse(NativeCompanionJson.patch(NativeCompanionProfilePatch(settings = parsed.copy(stickerPackIds = null)))
            .getJSONObject("settings").has("stickerPackIds"))
    }

    @Test fun catalogParsingKeepsPackMembershipAndStableImageReferences() {
        val sticker = NativeCompanionJson.sticker(JSONObject().put("id", "stable-image").put("url", "/droy/companions/sticker-media/stable-image")
            .put("label", "Original label").put("packId", "pack-fixture"))
        assertEquals("pack-fixture", sticker.packId)
        assertEquals("/droy/companions/sticker-media/stable-image", sticker.url)
        val builtin = NativeCompanionJson.sticker(JSONObject().put("id", "old-id").put("url", "/old"))
        assertEquals("droy-default", builtin.packId)
        val pack = NativeCompanionJson.stickerPack(JSONObject().put("id", "pack-fixture").put("name", "Original name").put("count", 2))
        assertEquals("Original name", pack.name)
        assertEquals(2, pack.count)
        assertFalse(pack.builtin)
    }

    @Test fun importsRejectEmptyAndOversizedStreamsWithoutTruncatedSuccess() {
        assertArrayEquals(byteArrayOf(1, 2, 3), readCompanionStickerPack(ByteArrayInputStream(byteArrayOf(1, 2, 3))))
        try { readCompanionStickerPack(ByteArrayInputStream(byteArrayOf())); fail("Empty archive must fail") }
        catch (error: NativeApiException) { assertEquals("COMPANION_STICKER_PACK_INVALID", error.code) }
        val oversized = object : InputStream() {
            var remaining = MAX_STICKER_PACK_BYTES + 1
            override fun read(): Int = if (remaining-- > 0) 0 else -1
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                if (remaining == 0) return -1
                val count = minOf(remaining, length)
                buffer.fill(0, offset, offset + count)
                remaining -= count
                return count
            }
        }
        try { readCompanionStickerPack(oversized); fail("Oversized archive must fail") }
        catch (error: NativeApiException) { assertEquals("COMPANION_STICKER_PACK_TOO_LARGE", error.code) }
    }
}
