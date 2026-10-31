package io.droyagent.app

import android.content.Intent
import android.os.Build
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.droyagent.app.media.MediaInputStore
import io.droyagent.app.nativeui.data.NativeLocalTransport
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Resumes one existing isolated deletion fixture. Original directories and backups are never restored here. */
@RunWith(AndroidJUnit4::class)
class NativeCompanionDeletionRecoveryDeviceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val sha256 = Regex("[a-f0-9]{64}")
    private val companionId = Regex("companion-[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}")
    private val uuid = Regex("[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}")

    @Test fun resumesExistingOfflineFixtureAndLeavesVerifiedDataForExplicitRestore() {
        assumeTrue("Explicit recovery opt-in required", InstrumentationRegistry.getArguments().getString("recoverCompanionDeletionFixture") == "true")
        assertEquals("io.droyagent.app.dev", context.packageName)
        assertTrue("Recovery is limited to the dedicated emulator", Build.MODEL.contains("sdk_gphone") || Build.FINGERPRINT.contains("emulator"))
        assertTrue(AudioCapture.get(context).uiStatus().isNull("active"))
        val files = context.filesDir
        val home = File(files, "dsh-home")
        val homeMarker = JSONObject(File(files, "native-workbench-fixture.json").readText())
        val mediaMarker = JSONObject(File(files, "native-deletion-media-fixture.json").readText())
        assertEquals("native-isolated-runtime-v1", homeMarker.getString("fixture"))
        assertEquals("running", homeMarker.getString("phase"))
        assertEquals("native-deletion-media-v1", mediaMarker.getString("fixture"))
        val homeBackup = checkedBackup(homeMarker, "backup", Regex("native-workbench-home-[0-9]+"))
        assertEquals(homeBackup.name + "-preferences", homeMarker.getString("preferences"))
        val preferenceBackup = File(files, homeMarker.getString("preferences"))
        assertTrue("Original preference recovery data must exist", preferenceBackup.isDirectory)
        assertEquals(homeMarker.getBoolean("hadHome"), homeBackup.isDirectory)
        val mediaBackup = checkedBackup(mediaMarker, "backup", Regex("native-deletion-media-[0-9]+"))
        assertEquals(mediaMarker.getBoolean("hadRoot"), mediaBackup.isDirectory)
        assertTrue(home.isDirectory)

        val inputFile = File(home, "synthetic-companion-deletion-input.json")
        val input = JSONObject(inputFile.readText())
        assertEquals(setOf("target", "keep"), input.keys().asSequence().toSet())
        val target = input.getString("target"); val keep = input.getString("keep")
        assertTrue(companionId.matches(target)); assertTrue(companionId.matches(keep)); assertNotEquals(target, keep)
        val journal = JSONObject(requireNotNull(context.getSharedPreferences("native_companion_deletion", 0).getString("operation", null)))
        assertEquals(1, journal.getInt("version")); assertEquals("offline", journal.getString("phase"))
        assertEquals(target, journal.getString("companionId")); assertTrue(uuid.matches(journal.getString("operationId")))
        val local = JSONObject(requireNotNull(context.getSharedPreferences("native_companion_local_maintenance", 0).getString("plan", null)))
        for (key in listOf("operationId", "companionId", "admissionEpoch")) assertEquals(key, journal.getString(key), local.getString(key))
        assertTrue(NativeCompanionDeletion.pending(context))

        val expectedFile = File(home, "synthetic-companion-deletion-output.json")
        val expectedBytes = expectedFile.readBytes()
        val expected = JSONObject(expectedBytes.toString(Charsets.UTF_8))
        for (key in listOf("retainedSession", "retainedMemory", "retainedMessages")) assertTrue(sha256.matches(expected.getString(key)))
        assertFalse("The original output must come from pre-deletion seeding", expected.getBoolean("targetAbsent"))
        assertFalse(expected.getBoolean("workspaceAbsent"))
        val savedExpected = File(home, "synthetic-companion-deletion-recovery-expected.json")
        if (savedExpected.exists()) assertArrayEquals(expectedBytes, savedExpected.readBytes())
        else { assertTrue(savedExpected.createNewFile()); savedExpected.writeBytes(expectedBytes) }

        val preferences = context.getSharedPreferences("native_companions", 0)
        val retainedDraft = requireNotNull(preferences.getString("draft:$keep", null))
        assertEquals("Synthetic retained unsent draft", JSONObject(retainedDraft).getString("text"))
        assertEquals("Synthetic target unsent draft", JSONObject(requireNotNull(preferences.getString("draft:$target", null))).getString("text"))
        val nativeImports = MediaInputStore.nativeDrafts(context).list()
        val shares = (0 until nativeImports.length()).map(nativeImports::getJSONObject).filter {
            it.optString("source") == "share" && it.optString("text") == "Synthetic retained shared text" && !it.has("targetSessionId")
        }
        assertEquals("Identify exactly the original retained share", 1, shares.size)
        val shareId = shares.single().getString("id"); assertTrue(uuid.matches(shareId))
        val shareFile = File(files, "media-input/native-v1/$shareId/manifest.json")
        val retainedShare = shareFile.readBytes()
        val legacyImports = MediaInputStore(context).list()
        val targetImports = (0 until legacyImports.length()).map(legacyImports::getJSONObject).filter {
            it.optString("consumer") == "companion" && it.optString("targetSessionId") == "im:$target"
        }.map { it.getString("id").also { id -> assertTrue(uuid.matches(id)) } }
        assertEquals("Identify the original target import", 1, targetImports.size)
        assertNoModelCalls(home)
        val receipt = File(files, "native-companion-deletion-recovery-verified.json")
        assertFalse("An existing verification receipt must be reviewed first", receipt.exists())
        val initialHost = NativeRuntimeHost.runtimeIdentity()
        var activity: ActivityScenario<NativeAppActivity>? = null
        try {
            activity = ActivityScenario.launch(Intent(context, NativeAppActivity::class.java).putExtra("destination", "messages"))
            await(120_000, "The production participant must recover and restart") {
                val state = NativeCompanionDeletion.inspect(context)
                state.phase() in setOf("complete", "idle") && !NativeCompanionDeletion.pending(context) &&
                    !context.getSharedPreferences("native_companion_local_maintenance", 0).contains("plan") &&
                    RuntimeService.ready && NativeRuntimeHost.inspect(context).ready &&
                    NativeRuntimeHost.runtimeIdentity().isNotEmpty() && NativeRuntimeHost.runtimeIdentity() != initialHost
            }
            assertFalse(NativeCompanionDeletion.inspect(context).blocksEditing())
            val profiles = runBlocking { NativeLocalTransport().json("/droy/companions") }.getJSONArray("companions")
            assertFalse((0 until profiles.length()).any { profiles.getJSONObject(it).getString("id") == target })
            assertTrue((0 until profiles.length()).any { profiles.getJSONObject(it).getString("id") == keep })
            assertFalse(File(home, "droy-companions/$target").exists())
            assertFalse(preferences.contains("draft:$target"))
            assertEquals(retainedDraft, preferences.getString("draft:$keep", null))
            assertArrayEquals(retainedShare, shareFile.readBytes())
            targetImports.forEach { assertFalse(File(files, "media-input/v1/$it").exists()) }

            activity.close(); activity = null
            context.startService(Intent(context, RuntimeService::class.java).setAction(RuntimeService.STOP))
            await(30_000, "Stop the recovered Host before checking its storage") { RuntimeService.canManageHistory() }
            val actual = verifyStorage(home, inputFile)
            for (key in listOf("retainedSession", "retainedMemory", "retainedMessages")) assertEquals(key, expected.getString(key), actual.getString(key))
            assertTrue(actual.getBoolean("targetAbsent")); assertTrue(actual.getBoolean("workspaceAbsent"))
            assertNoModelCalls(home)
            assertFalse(NativeCompanionDeletion.pending(context))
            assertFalse(context.getSharedPreferences("native_companion_local_maintenance", 0).contains("plan"))
            assertTrue(RuntimeService.canManageHistory())
            // Only identifiers and digests are emitted; journals may contain tokens and are never copied here.
            val verified = JSONObject().put("version", 1).put("fixture", "native-companion-deletion-recovery-v1")
                .put("verified", true).put("hostStopped", true).put("pending", false).put("modelCalls", 0)
                .put("target", target).put("keep", keep).put("retainedSession", actual.getString("retainedSession"))
                .put("retainedMemory", actual.getString("retainedMemory")).put("retainedMessages", actual.getString("retainedMessages"))
                .put("retainedDraftDigest", digest(retainedDraft.toByteArray())).put("retainedShareDigest", digest(retainedShare))
            assertTrue(receipt.createNewFile())
            receipt.outputStream().use { it.write(verified.toString().toByteArray()); it.fd.sync() }
        } finally {
            // Failure preserves the home, both journals, both markers, and every original backup.
            activity?.close()
        }
    }

    private fun checkedBackup(marker: JSONObject, key: String, pattern: Regex): File {
        val name = marker.getString(key); assertTrue("Unexpected fixture backup name", pattern.matches(name))
        return File(context.filesDir, name)
    }
    private fun digest(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun assertNoModelCalls(home: File) {
        for (name in listOf("native-fixture-calls.jsonl", "native-fixture-companion.jsonl")) {
            val file = File(home, name)
            assertTrue("Recovery must not invoke a model: $name", !file.exists() || file.length() == 0L)
        }
    }
    private fun await(timeout: Long, message: String, predicate: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeout
        while (!predicate() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(100)
        val state = NativeCompanionDeletion.inspect(context)
        assertTrue("$message; phase=${state.phase()}, error=${state.error()}", predicate())
    }
    private fun verifyStorage(home: File, input: File): JSONObject {
        assertTrue(RuntimeService.canManageHistory())
        val script = File(home, "synthetic-companion-deletion.mjs")
        assertTrue("Use only the existing synthetic fixture verifier", script.isFile)
        val output = File(home, "synthetic-companion-deletion-recovery-actual.json")
        val log = File(home, "synthetic-companion-deletion-recovery-log.txt")
        val native = context.applicationInfo.nativeLibraryDir
        val process = ProcessBuilder(File(native, "libdroy_node.so").absolutePath, "--expose-internals", script.absolutePath,
            "verify", input.absolutePath, output.absolutePath).directory(home).redirectErrorStream(true).redirectOutput(log)
            .apply { environment().putAll(mapOf("DSH_HOME" to home.absolutePath, "HOME" to home.absolutePath,
                "DROY_RUNTIME_DIR" to File(context.filesDir, "runtime").absolutePath, "DROY_NATIVE_DIR" to native,
                "LD_LIBRARY_PATH" to native, "TMPDIR" to context.cacheDir.absolutePath)) }.start()
        if (!process.waitFor(30, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            assertTrue("The verifier must stop before manual restoration", process.waitFor(10, TimeUnit.SECONDS))
            fail("Synthetic storage verifier timed out")
        }
        assertEquals("Synthetic fixture verifier failed; inspect its local log", 0, process.exitValue())
        return JSONObject(output.readText())
    }
}
