package io.droyagent.app.nativeui.media

import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.io.RandomAccessFile
import java.nio.file.Files
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class NativeMediaPolicyTest {
    private fun original() = NativeMediaSelection("operation-a", "kaka", NativeMediaKind.ATTACHMENTS, 2, false,
        NativeMediaPhase.UPLOADING, listOf("content://fixture.documents/item-a", "content://fixture.documents/item-b"),
        listOf(NativeMediaReceipt(0, "upload-a", "a.txt", "text/plain", 4, "/droy/companions/kaka/attachments/upload-a")))

    @Test fun selectedUrisAndReceiptsRetainTheirOriginalOwnerAfterRestoration() {
        val restored = NativeMediaSelection.restore(JSONObject(original().json().toString()))
        assertEquals("kaka", restored.ownerId)
        assertEquals(original().uris, restored.uris)
        assertEquals(original().receipts, restored.receipts)
        assertFalse(NativeMediaPolicy.ownedUrl("maimai", restored.receipts[0].id, restored.receipts[0].url))
    }

    @Test fun restoredReceiptFromAnotherOwnerIsRejected() {
        val bad = original().json()
        bad.getJSONArray("receipts").getJSONObject(0).put("url", "/droy/companions/maimai/attachments/upload-a")
        try { NativeMediaSelection.restore(bad); fail("Cross-owner receipt restored") }
        catch (_: IllegalArgumentException) { }
    }

    @Test fun activityRecreationDoesNotWaitForAPickerThatWasNeverLaunched() {
        val prepared = original().copy(phase = NativeMediaPhase.PICKING, uris = emptyList(), receipts = emptyList(), launched = false)
        val restored = NativeMediaSelection.restore(prepared.json()).recoverForActivity(true)
        assertEquals(NativeMediaPhase.INTERRUPTED, restored.phase)
        assertEquals("kaka", restored.ownerId)
        assertEquals(NativeMediaPhase.PICKING, prepared.copy(launched = true).recoverForActivity(true).phase)
        assertEquals(NativeMediaPhase.INTERRUPTED, prepared.copy(launched = true).recoverForActivity(false).phase)
    }

    @Test fun interruptedUploadRetainsAcceptedReceiptBeforeAnyRetry() {
        val restored = NativeMediaSelection.restore(original().json()).recoverForActivity(true)
        assertEquals(NativeMediaPhase.INTERRUPTED, restored.phase)
        assertEquals(original().receipts, restored.receipts)
        assertEquals(original().uris, restored.uris)
        assertEquals("kaka", restored.ownerId)
    }

    @Test fun alreadyDeliveredReceiptRetainsItsCheckpointSeparatelyFromUploadAdmission() {
        val saved = original().copy(receipts = original().receipts.map { it.copy(applied = true) })
        val restored = NativeMediaSelection.restore(saved.json()).recoverForActivity(true)
        assertTrue(restored.receipts.single().applied)
        assertEquals(NativeMediaPhase.INTERRUPTED, restored.phase)
        assertEquals("upload-a", restored.receipts.single().id)
        assertFalse(original().receipts.single().applied)
    }

    @Test fun userAvatarIsSeparateFromCompanionAvatarAndOnlyReturnsAPreview() {
        val selected = NativeMediaSelection("avatar-operation", null, NativeMediaKind.AVATAR, 1, true,
            NativeMediaPhase.UPLOADING, listOf("content://fixture.photos/image-a"))
        assertNull(NativeMediaSelection.restore(selected.json()).ownerId)
        val result = NativeAvatarResult("avatar-operation", null, "/droy/companions/user-profile/attachments/image-a", "image-a")
        val state = NativeMediaState(avatarResults = mapOf(NativeMediaPolicy.ownerKey(null) to result))
        assertEquals(result, state.avatarFor(null))
        assertNull(state.avatarFor("kaka"))
        assertTrue(NativeMediaPolicy.ownedUrl(null, result.attachmentId, result.url))
    }

    @Test fun backgroundPickerKeepsItsPurposeAcrossRecoveryAndNeverPopulatesAvatarResults() {
        val selected = NativeMediaSelection("background-operation", "kaka", NativeMediaKind.BACKGROUND, 1, true,
            NativeMediaPhase.UPLOADING, listOf("content://fixture.photos/background"))
        val restored = NativeMediaSelection.restore(selected.json()).recoverForActivity(true)
        assertEquals(NativeMediaKind.BACKGROUND, restored.kind)
        assertEquals("kaka", restored.ownerId)
        assertEquals(NativeMediaPhase.INTERRUPTED, restored.phase)
        val background = NativeAvatarResult(selected.id, "kaka", "/droy/companions/kaka/attachments/background", "background")
        val avatar = NativeAvatarResult("avatar-operation", "kaka", "/droy/companions/kaka/attachments/avatar", "avatar")
        val state = NativeMediaState(avatarResults = mapOf(NativeMediaPolicy.ownerKey("kaka") to avatar),
            backgroundResults = mapOf(NativeMediaPolicy.ownerKey("kaka") to background))
        assertEquals(background, state.backgroundFor("kaka"))
        assertEquals(avatar, state.avatarFor("kaka"))
        assertNull(state.backgroundFor("other"))
        assertNull(state.backgroundFor(null))
        assertFalse(NativeMediaRetirementGate.references(JSONObject().put("selection", selected.json()).toString(), selected.id))
    }

    @Test fun backgroundRecoveryRejectsAUserProfileTargetAndMultipleOrNonImageSelection() {
        val selected = NativeMediaSelection("background-operation", "kaka", NativeMediaKind.BACKGROUND, 1, true)
        for (invalid in listOf(selected.copy(ownerId = null), selected.copy(maximum = 2), selected.copy(imagesOnly = false))) {
            assertThrows(IllegalArgumentException::class.java) { NativeMediaSelection.restore(invalid.json()) }
        }
    }

    @Test fun filePickerCannotTurnFileUrlsOrOversizedUrisIntoUploads() {
        assertTrue(NativeMediaPolicy.contentUri("content://fixture.documents/item-a"))
        assertFalse(NativeMediaPolicy.contentUri("file:///data/data/private/value"))
        assertFalse(NativeMediaPolicy.contentUri("content://user@fixture.documents/item"))
        assertFalse(NativeMediaPolicy.contentUri("content://fixture.documents/" + "a".repeat(8192)))
        assertFalse(NativeMediaPolicy.ownedUrl("kaka", "a", "http://127.0.0.1:1234/droy/companions/kaka/attachments/a"))
        assertFalse(NativeMediaPolicy.ownedUrl("kaka", "a", "/droy/companions/kaka/attachments/a?token=fixture"))
    }

    @Test fun unknownLengthStreamFailsBeforeAcceptingAnOversizedOrEmptyFile() {
        assertArrayEquals(byteArrayOf(1, 2), NativeMediaPolicy.readBounded(ByteArrayInputStream(byteArrayOf(1, 2))))
        val endless = object : InputStream() {
            override fun read(): Int = 1
            override fun read(buffer: ByteArray, off: Int, len: Int): Int { buffer.fill(1, off, off + len); return len }
        }
        try { NativeMediaPolicy.readBounded(endless); fail("Oversized file accepted") }
        catch (error: NativeMediaProblem) { assertEquals(NativeMediaError.TOO_LARGE, error.problem) }
        try { NativeMediaPolicy.readBounded(ByteArrayInputStream(byteArrayOf())); fail("Empty file accepted") }
        catch (error: NativeMediaProblem) { assertEquals(NativeMediaError.READ, error.problem) }
    }

    @Test fun viewerCacheContainsBytesAndSanitizesNamesInsideItsPrivateDirectory() {
        val directory = Files.createTempDirectory("native-media-test").toFile()
        try {
            val cache = NativeMediaCache(directory)
            val file = cache.write(byteArrayOf(1, 2), "../../outside.txt")
            assertTrue(file.canonicalPath.startsWith(directory.canonicalPath + File.separator))
            assertEquals(directory, file.parentFile.parentFile)
            assertArrayEquals(byteArrayOf(1, 2), file.readBytes())
            assertFalse(File(directory.parentFile, "outside.txt").exists())
        } finally { directory.deleteRecursively() }
    }

    @Test fun cacheEvictsOlderCopiesAndDoesNotEvictCurrentlyPinnedPlayback() {
        val directory = Files.createTempDirectory("native-media-limit").toFile()
        try {
            val old = File(directory, "old").apply { mkdir() }
            val file = File(old, "large.bin")
            RandomAccessFile(file, "rw").use { it.setLength(NativeMediaPolicy.CACHE_BYTES) }
            val cache = NativeMediaCache(directory)
            try { cache.write(byteArrayOf(1), "new.txt", setOf(file.absolutePath)); fail("Pinned audio was evicted") }
            catch (error: NativeMediaProblem) { assertEquals(NativeMediaError.STORAGE, error.problem) }
            assertTrue(file.isFile)
            assertTrue(cache.write(byteArrayOf(1), "new.txt").isFile)
            assertFalse(old.exists())
        } finally { directory.deleteRecursively() }
    }
}
