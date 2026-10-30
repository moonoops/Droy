package io.droyagent.app

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.UiAutomation
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.AtomicFile
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import io.droyagent.app.nativeui.data.NativeWorkbenchViewModel
import io.droyagent.app.nativeui.permissions.NativeAuthorizationPolicy
import io.droyagent.app.nativeui.permissions.NativePermissionOutcome
import io.droyagent.app.nativeui.permissions.NativePermissionPolicyStore
import io.droyagent.app.nativeui.standardvoice.NativeStandardVoiceAction
import io.droyagent.app.nativeui.standardvoice.NativeStandardVoicePhase
import io.droyagent.app.nativeui.standardvoice.NativeStandardVoiceViewModel
import java.io.File
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.runner.RunWith

/** Real denial only. This fixture never grants RECORD_AUDIO, starts ASR, or sends a prompt. */
@RunWith(AndroidJUnit4::class)
class NativeStandardVoiceAuthorizationDeviceTest {
    @get:Rule(order = 0) val compose = createEmptyComposeRule()
    @get:Rule(order = 1) val untouchedDevice = object : ExternalResource() {
        override fun before() {
            assumeTrue("Explicit isolated native fixture required", InstrumentationRegistry.getArguments().getString("nativeWorkbenchFixture") == "true")
            assertEquals(PACKAGE, context.packageName)
            assertTrue("Only a dedicated emulator can deny a system permission", Build.MODEL.contains("sdk_gphone") || Build.FINGERPRINT.contains("emulator"))
            assertDenied()
            assertTrue("Never interrupt an existing engine", RuntimeService.canManageHistory())
            assertTrue("Never interrupt an existing capture", capture.uiStatus().isNull("active"))
            assertFalse(capture.dictationActive()); assertFalse(asr.hasActiveJobs())
            assertFalse(VoiceMedia.hasActiveWork(context)); assertFalse(VoiceWake.hasActiveWork(context))
            assertFalse("Never interrupt another readout", VoiceRuntime.request(context, JSONObject().put("operation", "status")).getBoolean("speaking"))
        }
    }
    @get:Rule(order = 2) val fixture = NativeIsolatedRuntimeFixture()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = fixture.context
    private val automation: UiAutomation get() = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
    private val product get() = context.getSharedPreferences("product", 0)
    private val policy get() = NativePermissionPolicyStore(product)
    private val capture get() = AudioCapture.get(context)
    private val asr get() = AudioTranscription.get(context)
    private lateinit var work: NativeWorkbenchViewModel
    private lateinit var voice: NativeStandardVoiceViewModel
    private var expectedDraft: String? = null
    private var runtimeIdentity: String? = null
    private var clickedProductAllow = false
    private val operations = linkedSetOf<String>()
    private val captures = linkedSetOf<String>()
    private var originalFlags: MicrophoneFlags? = null
    private var flagsMayHaveChanged = false
    private var recovery: AtomicFile? = null
    private var recoveryValue: JSONObject? = null
    private var originalAutomationFlags: Int? = null
    private var originalCaptureFiles = emptyMap<String, String>()
    private var productDialogWait = 0

    @Test fun cancellingProductConsentAndDenyingTheRealSystemPromptPreserveTheDraftAndNeverOpenAudio() {
        var failure: Throwable? = null
        try {
            assertDenied()
            val accessibility = automation.serviceInfo
            originalAutomationFlags = accessibility.flags
            accessibility.flags = accessibility.flags or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
            automation.serviceInfo = accessibility
            assertTrue("An existing capture must not be interrupted", capture.uiStatus().isNull("active"))
            assertFalse("An existing dictation must not be interrupted", capture.dictationActive())
            assertFalse("An existing ASR job must not be interrupted", asr.hasActiveJobs())
            originalCaptureFiles = captureFiles()
            prepareDeniedMicrophoneFlags()
            val systemGrants = otherSystemGrants()
            assertTrue(product.edit().putBoolean("allowAudioMicrophone", false).putBoolean("allowAudioTranscription", false)
                .putBoolean(ProductPermissions.STOPPED, false).commit())
            policy.select(NativeAuthorizationPolicy.ON_FIRST_USE)
            assertTrue("The isolated microphone must have no previous policy receipt", policy.automaticRequestAllowed("microphone"))
            fixture.startRuntime()
            runtimeIdentity = NativeRuntimeHost.runtimeIdentity().also { assertTrue(it.isNotBlank()) }
            fixture.scenario.onActivity {
                work = ViewModelProvider(it)[NativeWorkbenchViewModel::class.java]
                voice = ViewModelProvider(it)[NativeStandardVoiceViewModel::class.java]
                work.setDraft(ORDINARY_DRAFT)
            }
            compose.waitUntil(30_000) { work.state.value.runtime.ready && work.state.value.workspaces.isNotEmpty() && voice.entryEnabled.value == true }
            await(30_000, "Bundled offline ASR must already be ready; this fixture never prepares or downloads it") {
                asr.request(JSONObject().put("operation", "preparation"), VoiceMedia.OWNER).optBoolean("ready")
            }
            expectedDraft = work.state.value.draftId
            val sessionsBefore = sessions()
            assertTrue("The authorization fixture must not call even the local model", fixtureCalls().isEmpty())
            val capabilitiesBefore = capabilityChoices()
            val recordsBefore = product.getString("nativePermissionRequestRecords", "{}")
            assertFalse(ProductPermissions.stopped(context))
            assertFalse(NativeStandardVoiceHost.productAccessGranted(context))
            assertNoAudio()

            compose.onNodeWithTag("native-composer-attachments").performClick()
            compose.onNodeWithTag("native-open-standard-voice").performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("native-standard-voice-start").fetchSemanticsNodes(false).isNotEmpty() }
            compose.onNodeWithTag("native-standard-voice-start").assertIsDisplayed().assertIsEnabled().performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("native-voice-access-confirmation").fetchSemanticsNodes(false).isNotEmpty() }
            awaitOwnDialog()
            screenshot("native-standard-voice-authorization-product")
            compose.onNodeWithTag("native-voice-access-cancel").performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("native-voice-access-confirmation").fetchSemanticsNodes(false).isEmpty() }
            compose.onNodeWithTag("native-voice-access-confirmation").assertDoesNotExist()
            assertEquals(capabilitiesBefore, capabilityChoices())
            assertEquals(recordsBefore, product.getString("nativePermissionRequestRecords", "{}"))
            assertTrue(policy.automaticRequestAllowed("microphone"))
            assertNull(voice.state.value.journal)
            assertEquals(sessionsBefore, sessions()); assertNoAudio()
            evidence("product-cancelled")

            compose.onNodeWithTag("native-standard-voice-start").performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("native-voice-access-confirmation").fetchSemanticsNodes(false).isNotEmpty() }
            awaitOwnDialog()
            // No Compose/Espresso call occurs after this native click until the test has ended.
            // The confirmation can open a different Activity and the system permission window immediately.
            clickedProductAllow = true
            assertTrue("The real product confirmation must be clicked", tapOwnText(localized(R.string.native_voice_input_authorize)))
            await(15_000, "The actual standard voice operation must own a pending microphone capture") {
                rememberOwnedCapture()
                captures.isNotEmpty() && capture.pendingAuthorization()?.optString("captureId") in captures
            }
            assertOnlyVoiceCapabilitiesChanged(capabilitiesBefore)
            assertEquals(NativePermissionOutcome.GRANTED, policy.records()["capability:allowAudioMicrophone"]?.outcome)
            assertEquals(NativePermissionOutcome.GRANTED, policy.records()["capability:allowAudioTranscription"]?.outcome)
            assertDenied()
            awaitSystemDialog()
            assertEquals(NativePermissionOutcome.REQUESTING, policy.records()["microphone"]?.outcome)
            assertPendingWithoutAudio()
            evidence("system-prompt")
            screenshot("native-standard-voice-authorization-system-deny")
            assertTrue("Only this application's real system Deny button may be clicked", denyOwnSystemDialog())
            await(10_000, "The real Android denial must be recorded") {
                assertDenied()
                policy.records()["microphone"]?.outcome == NativePermissionOutcome.NOT_GRANTED && !policy.automaticRequestAllowed("microphone")
            }
            awaitDeniedAuthorizationActivity()
            assertNoAudio()
            evidence("system-denied")
            assertTrue("Cancel only this capture's real authorization Activity", tapOwnText(CANCEL_CAPTURE_LABELS))
            await(10_000, "The owned authorization Activity must close and the native page must report the stopped capture") {
                !AudioPermissionActivity.isVisible() && NativeRuntimeHost.isVisible()
                    && voice.state.value.phase == NativeStandardVoicePhase.ENDED && ownRootHasAny(listOf(localized(R.string.nsv_title)))
            }
            awaitStableWindow { ownRootHasAny(listOf(localized(R.string.nsv_title))) && !AudioPermissionActivity.isVisible() }
            assertDenied(); assertNoAudio()
            assertOnlyVoiceCapabilitiesChanged(capabilitiesBefore)
            assertEquals(NativePermissionOutcome.NOT_GRANTED, policy.records()["microphone"]?.outcome)
            assertFalse(policy.automaticRequestAllowed("microphone"))
            assertEquals(sessionsBefore, sessions())
            assertTrue("No ASR text or model prompt may be generated by authorization", fixtureCalls().isEmpty())
            assertEquals(expectedDraft, work.state.value.draftId); assertEquals(ORDINARY_DRAFT, work.state.value.draft)
            assertOtherSystemGrants(systemGrants)
            screenshot("native-standard-voice-authorization-denied-return")
            evidence("returned-with-denial")
        } catch (error: Throwable) {
            failure = error
            throw error
        } finally {
            var cleanupFailure: Throwable? = null
            fun cleanup(block: () -> Unit) {
                try { block() } catch (error: Throwable) {
                    if (cleanupFailure == null) cleanupFailure = error else cleanupFailure!!.addSuppressed(error)
                }
            }
            cleanup { cleanOwnedAudio() }
            cleanup { restoreMicrophoneFlags() }
            cleanup { originalAutomationFlags?.let { flags -> automation.serviceInfo = automation.serviceInfo.apply { this.flags = flags } } }
            if (cleanupFailure != null) {
                if (failure != null) failure.addSuppressed(cleanupFailure!!) else throw cleanupFailure!!
            }
        }
    }

    private fun prepareDeniedMicrophoneFlags() {
        assertEquals("Only the primary dedicated emulator user is supported", "0", shell("am get-current-user").trim())
        assertTrue("The target must belong to the primary Android user", context.applicationInfo.uid in 10_000..99_999)
        val help = shell("pm help")
        assertTrue("Permission flags cannot be restored on this emulator; do not change them",
            help.contains("set-permission-flags") && help.contains("clear-permission-flags") && help.contains("user-set") && help.contains("user-fixed"))
        val original = microphoneFlags()
        assertFalse(original.granted)
        assertTrue("Unsupported/volatile microphone flags cannot be safely restored by this user-flag-only fixture",
            original.flags.all { it in USER_FLAGS || it in STABLE_SENSITIVITY_FLAGS })
        val file = AtomicFile(File(evidenceDirectory(), "native-standard-voice-authorization-microphone-flags.json"))
        if (file.baseFile.exists() || File(file.baseFile.path + ".bak").exists() || File(file.baseFile.path + ".new").exists()) {
            val prior = file.openRead().bufferedReader().use { JSONObject(it.readText()) }
            assertTrue("Restore the previous microphone flag receipt before running this fixture again", prior.optBoolean("flagsRestored"))
        }
        originalFlags = original; recovery = file
        recoveryValue = JSONObject().put("package", PACKAGE).put("permission", MICROPHONE).put("userId", 0)
            .put("originalGranted", false).put("originalFlags", JSONArray(original.flags.sorted()))
            .put("originalUserSet", "USER_SET" in original.flags).put("originalUserFixed", "USER_FIXED" in original.flags)
            .put("flagsRestored", false).put("startedAtMillis", System.currentTimeMillis())
        evidence("flags-captured")
        flagsMayHaveChanged = true
        checkedShell("pm clear-permission-flags --user 0 $PACKAGE $MICROPHONE user-set user-fixed")
        val cleared = microphoneFlags()
        assertFalse(cleared.granted); assertFalse("USER_SET" in cleared.flags); assertFalse("USER_FIXED" in cleared.flags)
        assertEquals(original.flags - USER_FLAGS, cleared.flags - USER_FLAGS)
        evidence("flags-cleared")
    }

    private data class MicrophoneFlags(val granted: Boolean, val flags: Set<String>)
    private fun microphoneFlags(): MicrophoneFlags {
        // Read the real package-manager receipt. Never print its full package dump or infer missing flags.
        val dump = shell("dumpsys package $PACKAGE")
        val users = Regex("(?m)^\\s*User (\\d+):[^\\n]*$").findAll(dump).toList()
        val permission = Regex("(?m)^\\s*android\\.permission\\.RECORD_AUDIO:\\s*granted=(true|false),\\s*flags=\\[([^]\\r\\n]*)]\\s*$")
        val rows = users.withIndex().filter { it.value.groupValues[1] == "0" }.flatMap { (index, user) ->
            val block = dump.substring(user.range.first, users.getOrNull(index + 1)?.range?.first ?: dump.length)
            val runtime = block.indexOf("runtime permissions:")
            if (runtime < 0) emptyList() else permission.findAll(block.substring(runtime)).toList()
        }
        check(rows.size == 1) { "No unique microphone flags; do not guess or change them" }
        val row = rows.single()
        val flags = row.groupValues[2].trim().split(Regex("[|,\\s]+")).filter(String::isNotEmpty).toSet()
        check(flags.all { it.matches(Regex("[A-Z][A-Z0-9_]*")) }) { "Unsupported package permission flag format" }
        return MicrophoneFlags(row.groupValues[1] == "true", flags)
    }

    private fun restoreMicrophoneFlags() {
        if (!flagsMayHaveChanged) return
        val original = requireNotNull(originalFlags)
        assertDenied(); evidence("restoring-flags")
        checkedShell("pm clear-permission-flags --user 0 $PACKAGE $MICROPHONE user-set user-fixed")
        val set = listOf("user-set" to "USER_SET", "user-fixed" to "USER_FIXED").filter { it.second in original.flags }.map { it.first }
        if (set.isNotEmpty()) checkedShell("pm set-permission-flags --user 0 $PACKAGE $MICROPHONE ${set.joinToString(" ")}")
        val restored = microphoneFlags()
        assertFalse(restored.granted)
        assertEquals("Restore both original user decision flags exactly", original.flags.intersect(USER_FLAGS), restored.flags.intersect(USER_FLAGS))
        assertEquals("No other microphone permission flags may change", original.flags - USER_FLAGS, restored.flags - USER_FLAGS)
        recoveryValue!!.put("flagsRestored", true).put("restoredAtMillis", System.currentTimeMillis())
        evidence("restored")
        flagsMayHaveChanged = false
    }

    private fun rememberOwnedCapture() {
        if (!clickedProductAllow || !::voice.isInitialized) return
        val journal = voice.state.value.journal ?: return
        if (journal.runtimeIdentity != runtimeIdentity || journal.target.draftId != expectedDraft) return
        assertEquals(journal.operationId, UUID.fromString(journal.operationId).toString())
        operations += journal.operationId
        journal.captureId?.let { id -> assertEquals(VoiceMedia.OWNER, capture.ownerForUi(id)); captures += id }
        val native = VoiceRuntime.request(context, JSONObject().put("operation", "status"))
        if (native.optString("voiceOperationId") !in operations) return
        val id = native.optString("captureId").takeUnless { it.isEmpty() || it == "null" } ?: return
        assertEquals(VoiceMedia.OWNER, capture.ownerForUi(id)); captures += id
    }

    private fun assertPendingWithoutAudio() {
        rememberOwnedCapture(); assertDenied()
        assertTrue(operations.isNotEmpty()); assertTrue(captures.isNotEmpty())
        val native = VoiceRuntime.request(context, JSONObject().put("operation", "status"))
        assertTrue(native.getString("voiceOperationId") in operations)
        assertEquals("awaiting_user", native.getString("state")); assertFalse(native.getBoolean("speaking"))
        assertTrue(native.optString("transcriptId").isEmpty())
        for (id in captures) {
            val value = capture.status(id, VoiceMedia.OWNER)
            assertEquals("awaiting_user", value.getString("state")); assertEquals(0L, value.getLong("bytes"))
            assertEquals(0L, value.getLong("nextCursor")); assertFalse(value.getBoolean("fileReady"))
        }
        assertFalse(asr.hasActiveJobs()); assertFalse(capture.dictationActive())
    }

    private fun assertNoAudio() {
        assertDenied()
        assertTrue("No recording can run after microphone denial", capture.uiStatus().isNull("active"))
        assertFalse(capture.dictationActive()); assertFalse(asr.hasActiveJobs())
        for (id in captures) {
            val value = capture.status(id, VoiceMedia.OWNER)
            assertEquals(0L, value.getLong("bytes")); assertFalse(value.getBoolean("fileReady"))
            assertTrue(value.getString("state") in setOf("completed", "interrupted", "failed"))
        }
    }

    private fun cleanOwnedAudio() {
        rememberOwnedCapture()
        if (captures.isNotEmpty() && capture.pendingAuthorization()?.optString("captureId") in captures) denyOwnSystemDialog()
        instrumentation.runOnMainSync { ownPermissionActivitiesOnMain().forEach { it.finish() } }
        if (::voice.isInitialized && voice.state.value.journal?.operationId in operations)
            instrumentation.runOnMainSync { voice.onAction(NativeStandardVoiceAction.End) }
        for (operation in operations) {
            try { VoiceRuntime.request(context, JSONObject().put("operation", "stop").put("voiceOperationId", operation)) }
            catch (foreign: VoiceSessionOwnership.Failure) { assertEquals("VOICE_OWNER_MISMATCH", foreign.code) }
        }
        await(10_000, "Only this fixture's authorization Activity/capture/ASR may need closing") {
            !AudioPermissionActivity.isVisible() && ownPermissionActivities().isEmpty()
                && capture.uiStatus().isNull("active") && !asr.hasActiveJobs() && !capture.dictationActive()
        }
        for (id in captures) {
            assertEquals(VoiceMedia.OWNER, capture.ownerForUi(id))
            val value = capture.status(id, VoiceMedia.OWNER)
            assertEquals(0L, value.getLong("bytes")); capture.deleteFromUi(id)
        }
        assertEquals("Existing recording files must be preserved", originalCaptureFiles, captureFiles())
        assertDenied()
    }

    private fun ownPermissionActivities(): List<AudioPermissionActivity> {
        var result = emptyList<AudioPermissionActivity>()
        instrumentation.runOnMainSync { result = ownPermissionActivitiesOnMain() }
        return result
    }
    private fun ownPermissionActivitiesOnMain(): List<AudioPermissionActivity> =
        listOf(Stage.RESUMED, Stage.STARTED, Stage.PAUSED, Stage.STOPPED).flatMap {
            ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(it)
        }.filterIsInstance<AudioPermissionActivity>().distinct().filter { it.intent.getStringExtra("captureId") in captures }

    private fun awaitDeniedAuthorizationActivity() {
        val started = SystemClock.elapsedRealtime()
        val initial = deniedAuthorizationObservation()
        try {
            await(10_000, "Return to the actual audio authorization Activity after denial") {
                ownRootHasAny(CANCEL_CAPTURE_LABELS) && ownPermissionActivities().isNotEmpty()
            }
        } catch (failure: Throwable) {
            try {
                File(evidenceDirectory(), "native-standard-voice-authorization-audio-return-timeout.json").writeText(
                    JSONObject().put("elapsedMs", SystemClock.elapsedRealtime() - started)
                        .put("initial", initial).put("last", deniedAuthorizationObservation()).toString(2))
            } catch (diagnostic: Throwable) { failure.addSuppressed(diagnostic) }
            try { rawScreenshot("native-standard-voice-authorization-audio-return-timeout") }
            catch (diagnostic: Throwable) { failure.addSuppressed(diagnostic) }
            throw failure
        }
    }

    private fun deniedAuthorizationObservation(): JSONObject {
        val result = JSONObject().put("expectedCancelLabels", JSONArray(CANCEL_CAPTURE_LABELS))
            .put("ownedCaptureCount", captures.size).put("ownedOperationCount", operations.size)
        fun observe(name: String, read: () -> Any?) {
            try { result.put(name, read() ?: JSONObject.NULL) }
            catch (diagnostic: Throwable) { result.put("${name}ErrorClass", diagnostic.javaClass.name) }
        }
        observe("nativeWindow") { nativeObservation() }
        observe("cancelTextMatches") { ownRootHasAny(CANCEL_CAPTURE_LABELS) }
        observe("authorizationActivityVisible") { AudioPermissionActivity.isVisible() }
        observe("nativeHostVisible") { NativeRuntimeHost.isVisible() }
        observe("permissionOutcome") { policy.records()["microphone"]?.outcome?.name }
        observe("automaticRequestAllowed") { policy.automaticRequestAllowed("microphone") }
        observe("systemMicrophoneGranted") { context.checkSelfPermission(MICROPHONE) == PackageManager.PERMISSION_GRANTED }
        observe("authorizationActivities") {
            val rows = JSONArray()
            instrumentation.runOnMainSync {
                for (stage in listOf(Stage.RESUMED, Stage.STARTED, Stage.PAUSED, Stage.STOPPED)) {
                    ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(stage)
                        .filterIsInstance<AudioPermissionActivity>().take(8).forEach { activity ->
                            rows.put(JSONObject().put("class", activity.javaClass.name).put("stage", stage.name)
                                .put("ownsCapture", activity.intent.getStringExtra("captureId") in captures)
                                .put("finishing", activity.isFinishing).put("destroyed", activity.isDestroyed)
                                .put("windowFocus", activity.hasWindowFocus()).put("decorShown", activity.window.decorView.isShown))
                        }
                }
            }
            rows
        }
        observe("voice") {
            if (!::voice.isInitialized) JSONObject().put("initialized", false) else {
                val state = voice.state.value
                JSONObject().put("initialized", true).put("phase", state.phase.name)
                    .put("journalOwned", state.journal?.operationId in operations)
                    .put("journalCaptureOwned", state.journal?.captureId in captures)
                    .put("stopRequested", state.journal?.stopRequested ?: JSONObject.NULL)
                    .put("nativeState", state.native?.state ?: JSONObject.NULL)
                    .put("nativeOperationOwned", state.native?.voiceOperationId in operations)
            }
        }
        observe("nativeVoice") {
            val native = VoiceRuntime.request(context, JSONObject().put("operation", "status"))
            JSONObject().put("state", native.opt("state") ?: JSONObject.NULL)
                .put("operationOwned", native.optString("voiceOperationId") in operations)
                .put("captureOwned", native.optString("captureId") in captures)
                .put("needsPermission", native.opt("needsPermission") ?: JSONObject.NULL)
                .put("speaking", native.opt("speaking") ?: JSONObject.NULL)
        }
        observe("ownedCaptures") {
            val rows = JSONArray()
            for (id in captures.take(8)) {
                val row = JSONObject().put("captureId", id)
                try {
                    val ownerMatches = capture.ownerForUi(id) == VoiceMedia.OWNER
                    row.put("ownerMatches", ownerMatches)
                    if (ownerMatches) {
                        val status = capture.status(id, VoiceMedia.OWNER)
                        for (key in listOf("state", "bytes", "nextCursor", "fileReady", "needsPermission"))
                            row.put(key, status.opt(key) ?: JSONObject.NULL)
                    }
                } catch (diagnostic: Throwable) { row.put("readErrorClass", diagnostic.javaClass.name) }
                rows.put(row)
            }
            rows
        }
        observe("captureActive") { !capture.uiStatus().isNull("active") }
        observe("pendingCaptureOwned") { capture.pendingAuthorization()?.optString("captureId") in captures }
        observe("dictationActive") { capture.dictationActive() }
        observe("asrActive") { asr.hasActiveJobs() }
        return result
    }

    private fun awaitOwnDialog() = awaitStableWindow("product-dialog-${++productDialogWait}") {
        ownRootHasAny(listOf(localized(R.string.native_voice_input_permission))) && ownRootHasAny(listOf(localized(R.string.native_voice_input_authorize)))
    }
    private fun awaitSystemDialog() = awaitStableWindow("system-permission") { rememberOwnedCapture(); ownSystemDenyNode()?.let { it.recycle(); true } == true }
    private fun awaitStableWindow(name: String = "return-page", predicate: () -> Boolean) {
        var stableSince = 0L
        var previousPixels: Long? = null
        val initial = nativeObservation()
        val samples = JSONArray()
        var nextSample = 0L
        var count = 0
        var matched = 0
        var hashChanges = 0
        var observedPixels: Long? = null
        val started = SystemClock.elapsedRealtime()
        fun save(outcome: String) {
            File(evidenceDirectory(), "native-standard-voice-authorization-native-$name-$outcome.json").writeText(
                JSONObject().put("outcome", outcome).put("elapsedMs", SystemClock.elapsedRealtime() - started)
                    .put("polls", count).put("predicateMatches", matched).put("observedHashChanges", hashChanges)
                    .put("initial", initial).put("last", nativeObservation()).put("samples", samples).toString(2))
        }
        try {
            await(15_000, "The real native window must settle before its screenshot or action") {
                assertDenied()
                val now = SystemClock.elapsedRealtime()
                val ownsWindow = predicate(); count++; if (ownsWindow) matched++
                val pixels = if (ownsWindow || now >= nextSample) screenFingerprint() else null
                if (!ownsWindow) { stableSince = 0L; previousPixels = null }
                else {
                    if (pixels == null || previousPixels != pixels) stableSince = 0L
                    else if (stableSince == 0L) stableSince = now
                    previousPixels = pixels
                }
                if (now >= nextSample) {
                    if (observedPixels != null && pixels != observedPixels) hashChanges++
                    observedPixels = pixels
                    if (samples.length() >= 40) samples.remove(0)
                    samples.put(JSONObject().put("elapsedMs", now - started).put("ownsWindow", ownsWindow)
                        .put("pixelHash", pixels?.toString(16) ?: JSONObject.NULL).put("stableForMs", if (stableSince == 0L) 0 else now - stableSince)
                        .put("serviceFlags", automation.serviceInfo.flags))
                    nextSample = now + 250
                }
                stableSince != 0L && now - stableSince >= 400
            }
            save("stable")
        } catch (failure: Throwable) {
            try { save("timeout") } catch (diagnostic: Throwable) { failure.addSuppressed(diagnostic) }
            try { rawScreenshot("native-standard-voice-authorization-native-$name-timeout") }
            catch (diagnostic: Throwable) { failure.addSuppressed(diagnostic) }
            throw failure
        }
    }
    private fun screenFingerprint(): Long? {
        val image = automation.takeScreenshot() ?: return null
        return try {
            // Ignore system-clock/navigation edges; include the real dialog's content and dimmed app surface.
            var hash = 1125899906842597L
            for (y in image.height / 8 until image.height * 7 / 8 step maxOf(1, image.height / 48))
                for (x in image.width / 10 until image.width * 9 / 10 step maxOf(1, image.width / 32))
                    hash = hash * 31 + image.getPixel(x, y)
            hash
        } finally { image.recycle() }
    }
    private fun ownSystemDenyNode(): AccessibilityNodeInfo? {
        val root = automation.rootInActiveWindow ?: return null
        try {
            val owner = root.packageName?.toString() ?: return null
            if (owner !in PERMISSION_CONTROLLERS || !hasText(root, listOf(context.packageManager.getApplicationLabel(context.applicationInfo).toString()))) return null
            // Google's controller retains AOSP resource IDs; the owning package and resource namespace can differ.
            var result: AccessibilityNodeInfo? = null
            visitNodes(root) { node ->
                if (node.viewIdResourceName !in PERMISSION_DENY_VIEW_IDS || !node.isVisibleToUser || !node.isEnabled || !node.isClickable) false
                else { result = AccessibilityNodeInfo.obtain(node); true }
            }
            return result
        } finally { root.recycle() }
    }
    private fun denyOwnSystemDialog(): Boolean = ownSystemDenyNode()?.let { node ->
        try { node.performAction(AccessibilityNodeInfo.ACTION_CLICK) } finally { node.recycle() }
    } == true
    private fun ownRootHasAny(labels: List<String>): Boolean {
        val root = automation.rootInActiveWindow ?: return false
        return try { root.packageName?.toString() == PACKAGE && hasText(root, labels) } finally { root.recycle() }
    }
    private fun hasText(root: AccessibilityNodeInfo, labels: List<String>): Boolean {
        // Compose's virtual node provider does not implement the find-by-text RPC.
        // Traverse its actual accessibility children; the package and complete label checks stay mandatory.
        return visitNodes(root) { node -> node.isVisibleToUser && labels.any { node.text?.toString()?.contains(it) == true } }
    }
    private fun visitNodes(root: AccessibilityNodeInfo, visit: (AccessibilityNodeInfo) -> Boolean): Boolean {
        val pending = ArrayDeque<AccessibilityNodeInfo>()
        pending.add(AccessibilityNodeInfo.obtain(root))
        var inspected = 0
        try {
            while (pending.isNotEmpty() && inspected++ < 256) {
                val node = pending.removeFirst()
                try {
                    if (visit(node)) return true
                    for (index in 0 until minOf(node.childCount, 64)) node.getChild(index)?.let { pending.addLast(it) }
                } finally { node.recycle() }
            }
        } finally { pending.forEach(AccessibilityNodeInfo::recycle) }
        return false
    }
    private fun nativeObservation(): JSONObject {
        val api = automation
        val flags = api.serviceInfo.flags
        val result = JSONObject().put("serviceFlags", flags)
            .put("reportViewIds", flags and AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS != 0)
            .put("retrieveInteractiveWindows", flags and AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS != 0)
            .put("automationIdentity", System.identityHashCode(api))
        val root = api.rootInActiveWindow ?: return result.put("rootPackage", JSONObject.NULL)
        try {
            val owner = root.packageName?.toString()
            result.put("rootPackage", owner ?: JSONObject.NULL).put("rootClass", root.className?.toString() ?: JSONObject.NULL)
                .put("rootWindowId", root.windowId).put("rootVisible", root.isVisibleToUser)
            val labels = JSONArray()
            val expected = listOf(localized(R.string.native_voice_input_permission), localized(R.string.native_voice_input_authorize), localized(R.string.nsv_title))
            result.put("expectedLabels", JSONArray(expected))
            if (owner == PACKAGE || owner in PERMISSION_CONTROLLERS) {
                visitNodes(root) { node ->
                    if (node.isVisibleToUser && labels.length() < 40 && (!node.text.isNullOrBlank() || !node.contentDescription.isNullOrBlank())) {
                        val bounds = android.graphics.Rect().also(node::getBoundsInScreen)
                        labels.put(JSONObject().put("text", node.text?.toString()?.take(160) ?: JSONObject.NULL)
                            .put("description", node.contentDescription?.toString()?.take(160) ?: JSONObject.NULL)
                            .put("viewId", node.viewIdResourceName ?: JSONObject.NULL).put("class", node.className?.toString() ?: JSONObject.NULL)
                            .put("clickable", node.isClickable).put("bounds", bounds.toShortString()))
                    }
                    false
                }
                val searches = JSONArray()
                for (label in expected) {
                    val rpc = root.findAccessibilityNodeInfosByText(label).orEmpty()
                    try { searches.put(JSONObject().put("label", label).put("rpcMatches", rpc.size)
                        .put("traversalMatches", hasText(root, listOf(label)))) }
                    finally { rpc.forEach(AccessibilityNodeInfo::recycle) }
                }
                result.put("labels", labels).put("textSearch", searches)
            }
            return result
        } finally { root.recycle() }
    }
    private fun tapOwnText(label: String) = tapOwnText(listOf(label))
    private fun tapOwnText(labels: List<String>): Boolean {
        val root = automation.rootInActiveWindow ?: return false
        try {
            if (root.packageName?.toString() != PACKAGE) return false
            return visitNodes(root) { match ->
                if (!match.isVisibleToUser || match.text?.toString() !in labels) return@visitNodes false
                var candidate: AccessibilityNodeInfo? = AccessibilityNodeInfo.obtain(match)
                try {
                    repeat(5) {
                        val node = candidate ?: return@repeat
                        if (node.packageName?.toString() != PACKAGE) return@visitNodes false
                        if (node.isClickable && node.isEnabled) return@visitNodes node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                        candidate = node.parent; node.recycle()
                    }
                    false
                } finally { candidate?.recycle() }
            }
        } finally { root.recycle() }
    }

    private fun capabilityChoices(): Map<String, Any?> {
        val rows = CapabilityCatalog(context).groups()
        return (0 until rows.length()).map { rows.getJSONObject(it).getString("preference") }.associateWith { product.all[it] }
    }
    private fun assertOnlyVoiceCapabilitiesChanged(before: Map<String, Any?>) {
        val after = capabilityChoices()
        for (key in before.keys) {
            if (key in VOICE_CAPABILITIES) assertEquals(true, after[key]) else assertEquals("Do not change $key", before[key], after[key])
        }
        assertTrue(NativeStandardVoiceHost.productAccessGranted(context)); assertFalse(ProductPermissions.stopped(context))
    }
    private fun otherSystemGrants(): Map<String, Boolean> {
        val info = context.packageManager.getPackageInfo(PACKAGE, PackageManager.GET_PERMISSIONS)
        return info.requestedPermissions.orEmpty().filter { it != MICROPHONE }.associateWith { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }
    }
    private fun assertOtherSystemGrants(before: Map<String, Boolean>) {
        for ((permission, granted) in before) assertEquals("Do not alter another system permission: $permission", granted,
            context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED)
    }
    private fun sessions(): Set<String> = runBlocking {
        val result = fixture.runtimeTransport.json("/droy/native/session/list", "POST", JSONObject())
        assertTrue("The real session list must be readable", result.getBoolean("ok"))
        val rows = result.getJSONObject("result").getJSONArray("items")
        (0 until rows.length()).map { rows.getJSONObject(it).getString("sessionId") }.toSet()
    }
    private fun fixtureCalls(): List<String> = File(fixture.home, "native-fixture-calls.jsonl").takeIf(File::isFile)?.readLines()?.filter(String::isNotBlank).orEmpty()
    private fun captureFiles(): Map<String, String> = File(context.filesDir, "audio-captures").listFiles().orEmpty()
        .associate { it.name to "${it.length()}:${it.lastModified()}" }
    private fun localized(id: Int): String = context.createConfigurationContext(Configuration(context.resources.configuration).apply { setLocale(Locale.ENGLISH) }).getString(id)
    private fun assertDenied() = assertEquals("This test must never grant or open the microphone", PackageManager.PERMISSION_DENIED, context.checkSelfPermission(MICROPHONE))
    private fun await(timeoutMs: Long, message: String, predicate: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (true) {
            if (predicate()) return
            if (SystemClock.elapsedRealtime() >= deadline) throw AssertionError(message)
            SystemClock.sleep(60)
        }
    }
    private fun screenshot(name: String) {
        assertDenied()
        rawScreenshot(name)
    }
    private fun rawScreenshot(name: String) {
        val image = requireNotNull(automation.takeScreenshot())
        try { File(evidenceDirectory(), "$name.png").outputStream().use { assertTrue(image.compress(Bitmap.CompressFormat.PNG, 100, it)) } }
        finally { image.recycle() }
    }
    private fun evidenceDirectory() = File(context.getExternalFilesDir(null), "native-isolated-evidence").apply { check(isDirectory || mkdirs()) }
    private fun evidence(phase: String) {
        val file = recovery ?: return
        val value = recoveryValue ?: return
        value.put("phase", phase).put("updatedAtMillis", System.currentTimeMillis())
            .put("ownedVoiceOperations", JSONArray(operations.toList())).put("ownedCaptureIds", JSONArray(captures.toList()))
        val output = file.startWrite()
        try { output.write(value.toString(2).toByteArray(Charsets.UTF_8)); file.finishWrite(output) }
        catch (failure: Throwable) { file.failWrite(output); throw failure }
    }
    private fun shell(command: String): String {
        val bytes = ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(command)).use { input ->
            val result = java.io.ByteArrayOutputStream(); val buffer = ByteArray(8192)
            while (true) { val count = input.read(buffer); if (count < 0) break; result.write(buffer, 0, count); check(result.size() <= 512 * 1024) { "Bounded package output exceeded" } }
            result.toByteArray()
        }
        return String(bytes, Charsets.UTF_8)
    }
    private fun checkedShell(command: String) { check(shell(command).isBlank()) { "Permission flag command failed; retain the recovery receipt" } }

    companion object {
        private const val PACKAGE = "io.droyagent.app.dev"
        private const val MICROPHONE = Manifest.permission.RECORD_AUDIO
        private const val ORDINARY_DRAFT = "NATIVE_STANDARD_VOICE_AUTHORIZATION_UNSENT_DRAFT"
        private val USER_FLAGS = setOf("USER_SET", "USER_FIXED")
        private val STABLE_SENSITIVITY_FLAGS = setOf("USER_SENSITIVE_WHEN_GRANTED", "USER_SENSITIVE_WHEN_DENIED")
        private val VOICE_CAPABILITIES = setOf("allowAudioMicrophone", "allowAudioTranscription")
        private val PERMISSION_CONTROLLERS = setOf("com.android.permissioncontroller", "com.google.android.permissioncontroller")
        private val PERMISSION_DENY_VIEW_IDS = PERMISSION_CONTROLLERS.map { "$it:id/permission_deny_button" }.toSet()
        private val CANCEL_CAPTURE_LABELS = listOf("Cancel capture", "CANCEL CAPTURE", "取消采集")
    }
}
