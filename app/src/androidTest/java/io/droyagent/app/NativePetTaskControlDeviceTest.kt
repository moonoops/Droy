package io.droyagent.app

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.UiAutomation
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.droyagent.androiduse.AccessibilityRecovery
import io.droyagent.androiduse.DroyAccessibilityService
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import org.junit.runner.RunWith

/** Real Host/SDK/product HTTP/native gate. No synthetic ToolProgress or control engine is installed. */
@RunWith(AndroidJUnit4::class)
class NativePetTaskControlDeviceTest {
    @get:Rule(order = 0) val connectedBeforeIsolation = object : ExternalResource() {
        override fun before() {
            assumeTrue("Explicit isolated native fixture required", InstrumentationRegistry.getArguments().getString("nativeWorkbenchFixture") == "true")
            assertEquals("Only the development application can run this fixture", "io.droyagent.app.dev", context.packageName)
            assertTrue("A dedicated emulator is required", Build.MODEL.contains("sdk_gphone") || Build.FINGERPRINT.contains("emulator"))
            assertTrue("Do not interrupt an existing runtime", RuntimeService.canManageHistory())
            assertTrue("Do not interrupt audio capture", AudioCapture.get(context).uiStatus().isNull("active"))
            assertFalse("Recover the previous native fixture before accessibility preflight", File(context.filesDir, "native-workbench-fixture.json").exists())
            automation = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
            try {
                val info = automation.serviceInfo
                accessibilityFlags = info.flags
                info.flags = info.flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
                automation.serviceInfo = info
                val service = ComponentName(context, DroyAccessibilityService::class.java)
                assertTrue("The runner must already have enabled the real Droy accessibility service", serviceEnabled(service))
                // A foreground owned page permits normal recovery without opening the
                // product runtime or authorizing a previously disabled service.
                recoveryPage = instrumentation.startActivitySync(Intent(context, TestPageActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as TestPageActivity
                AccessibilityRecovery.get(context).onAppOpened(false)
                var connectedSince = 0L
                // A recovery off/on cycle owns a delayed callback. Finish it before
                // the isolated fixture clears its authorization preferences.
                await("The already-enabled Droy service did not bind before preference isolation", 15_000) {
                    if (!serviceEnabled(service) || !DroyAccessibilityService.isConnected(context)) {
                        connectedSince = 0L; false
                    } else {
                        val now = SystemClock.elapsedRealtime()
                        if (connectedSince == 0L) connectedSince = now
                        now - connectedSince >= 750L
                    }
                }
                assertTrue("The accessibility bootstrap must not start the task runtime", RuntimeService.canManageHistory())
                closeRecoveryPage()
            } catch (failure: Throwable) {
                try { captureEvidence("pet-control-pre-fixture-accessibility-${SystemClock.elapsedRealtime()}") }
                catch (diagnostic: Throwable) { failure.addSuppressed(diagnostic) }
                try { closeRecoveryPage() }
                catch (cleanup: Throwable) { failure.addSuppressed(cleanup) }
                try { restoreAutomationFlags() }
                catch (cleanup: Throwable) { failure.addSuppressed(cleanup) }
                throw failure
            }
        }
        override fun after() {
            try { closeRecoveryPage() } finally { restoreAutomationFlags() }
        }
    }
    @get:Rule(order = 1) val fixture = NativeIsolatedRuntimeFixture(providerAsset = "native-pet-task-control-provider.mjs")
    @get:Rule(order = 2) val evidence = object : TestWatcher() {
        override fun failed(failure: Throwable, description: Description) {
            try { captureEvidence("pet-control-failed-${description.methodName}") }
            catch (diagnostic: Throwable) { failure.addSuppressed(diagnostic) }
            try {
                val source = File(fixture.home, "native-pet-control-events.jsonl")
                if (source.isFile) source.copyTo(File(fixture.context.getExternalFilesDir(null),
                    "native-isolated-evidence/pet-control-failed-${description.methodName}.jsonl"), overwrite = true)
            } catch (diagnostic: Throwable) { failure.addSuppressed(diagnostic) }
        }
    }
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = fixture.context
    private lateinit var automation: UiAutomation
    private lateinit var ui: DeviceUi
    private var accessibilityFlags: Int? = null
    private var recoveryPage: TestPageActivity? = null

    @Before fun showTheActualInternalPetWithoutChangingSystemPermissions() {
        check(::automation.isInitialized) { "The accessibility preflight did not run before isolation" }
        await("The enabled accessibility service did not actually reconnect on the owned emulator", 15_000) {
            DroyAccessibilityService.isConnected(context)
        }
        val preferences = context.getSharedPreferences("product", Context.MODE_PRIVATE)
        assertTrue(preferences.edit().putBoolean("petEnabled", true).putString("petInternalDisplay", "always")
            .putString("petExternalDisplay", "off").putString("petId", "default")
            .putBoolean(BackgroundRuntime.ENABLED, true).putBoolean(BackgroundRuntime.STOPPED, false)
            .putBoolean(ProductPermissions.DEFAULT_DENY, true).putBoolean("allowObserve", true)
            .putBoolean(ProductPermissions.STOPPED, false).putBoolean("allowRoot", false).putBoolean("allowControl", false).commit())
        RuntimeService.applySavedOperationState()
        ui = DeviceUi(instrumentation, null)
        PetOverlay.refresh(context)
        await("The real internal pet did not attach", 10_000) {
            onMain { PetOverlay.get(context).petBounds().isEmpty.not() && overlayField("entering") == false }
        }
    }

    private fun restoreAutomationFlags() {
        if (::automation.isInitialized) accessibilityFlags?.let { original ->
            val info = automation.serviceInfo; info.flags = original; automation.serviceInfo = info
        }
        accessibilityFlags = null
    }

    private fun serviceEnabled(service: ComponentName): Boolean =
        Settings.Secure.getString(context.contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED) == "1" &&
            Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
                .orEmpty().split(':').any { ComponentName.unflattenFromString(it) == service }

    private fun closeRecoveryPage() {
        recoveryPage?.let { activity -> instrumentation.runOnMainSync { activity.finish() } }
        recoveryPage = null
    }

    @Test fun petIconsPauseTheRealNativeWaitParkMessagesAndObserveBeforeResuming() {
        val session = startSession("PET_TASK_CONTROL_LIFECYCLE")
        val initial = awaitNativeWait(session, "pet-wait-before-pause")
        val originalOperation = initial.getJSONObject("scope").getString("operationId")
        val initialEpoch = nativeState(session, originalOperation).epoch()
        openTaskBubble()
        tapControl("droy-pet-pause", R.string.pet_overlay_pause)
        val paused = awaitPhase(session, "paused")
        val pausedNative = nativeState(session, originalOperation)
        assertTrue("The actual native operation did not become quiescent", pausedNative.quiescent())
        assertEquals("The native fence did not enter paused", "paused", pausedNative.phase())
        assertTrue("Pause did not advance the native admission epoch", pausedNative.epoch() > initialEpoch)
        assertTrue(pausedNative.pending().isEmpty())
        assertEquals(session, paused.getString("sessionHeaderId"))
        assertNotEquals("The real driver kept running after pause", "running", paused.getString("agentStatus"))
        val revision = paused.getJSONObject("control").getLong("controlRevision")
        assertTrue(revision > 0)
        assertThrows("A stale native pet command must be rejected", IOException::class.java) {
            PetActions.control(session, "resume", 0)
        }
        assertEquals(revision, probe(session).getJSONObject("control").getLong("controlRevision"))

        val callsBefore = modelCalls(probe(session)).size
        val receipt = native("/droy/native/session/prompt", JSONObject().put("sessionId", session)
            .put("requestId", "pet-parked-${UUID.randomUUID()}").put("mode", "queue")
            .put("content", JSONArray().put(JSONObject().put("type", "text").put("text", "PET_PARKED_USER_MESSAGE"))))
        assertTrue("The actual product prompt did not admit the parked user message", receipt.getBoolean("accepted"))
        await("The paused driver lost the parked inbox message", 10_000) { probe(session).getBoolean("parked") }
        SystemClock.sleep(1500)
        val parked = probe(session)
        assertEquals("A queued user message woke the held model", callsBefore, modelCalls(parked).size)
        assertEquals("The queued user message resumed the controller", "paused", parked.getJSONObject("control").getString("phase"))
        assertTrue(nativeState(session, originalOperation).quiescent())
        captureEvidence("pet-real-paused-with-parked-user")

        tapControl("droy-pet-resume", R.string.pet_overlay_resume)
        val resumed = awaitNativeWait(session, "pet-wait-after-resume")
        assertEquals("Resume replaced the existing session", session, resumed.getString("sessionHeaderId"))
        assertEquals("Resume replaced the native operation instead of observing it", originalOperation,
            resumed.getJSONObject("scope").getString("operationId"))
        val events = rows(resumed.getJSONArray("events"))
        val freshObservation = events.single { it.optString("kind") == "tool-exit" && it.optString("name") == "android_observe" && !it.optBoolean("isError") }
        val resumedModel = modelCalls(resumed).single { it.getBoolean("resumed") }
        val afterWait = events.single { it.optString("kind") == "tool-enter" && it.optString("callId") == "pet-wait-after-resume" }
        assertTrue("The model resumed before the fresh actual observation finished", freshObservation.getLong("sequence") < resumedModel.getLong("sequence"))
        assertTrue("A later native action ran before observation", freshObservation.getLong("sequence") < afterWait.getLong("sequence"))
        assertTrue("Resume lost the original objective/context", resumedModel.getBoolean("originalContext"))
        assertTrue("Resume lost the parked user message", resumedModel.getBoolean("parkedContext") || resumed.getBoolean("parked"))
        assertEquals("The old interrupted action was blindly replayed", 1, events.count {
            it.optString("kind") == "tool-enter" && it.optString("callId") == "pet-wait-before-pause"
        })
        assertTrue(nativeState(session, originalOperation).epoch() > pausedNative.epoch())
        awaitProgressRevision(session, resumed.getJSONObject("control").getLong("controlRevision"))
        captureEvidence("pet-real-resumed-after-fresh-observation")
        tapControl("droy-pet-stop", R.string.pet_overlay_stop)
        val stopped = awaitPhase(session, "stopped")
        val stoppedNative = nativeState(session, originalOperation)
        assertTrue("Stop returned before the actual Android wait settled", stoppedNative.quiescent())
        assertEquals("stopped", stoppedNative.phase())
        assertTrue(stoppedNative.pending().isEmpty())
        assertNotEquals("Stop left the SDK driver running", "running", stopped.getString("agentStatus"))
        assertFalse(stopped.getBoolean("parked"))
        assertThrows(IOException::class.java) {
            PetActions.control(session, "resume", stopped.getJSONObject("control").getLong("controlRevision"))
        }
        captureEvidence("pet-real-stopped")
        writeEvidence("pet-real-control-lifecycle", stopped)
    }

    @Test fun realPetQuestionsSendMixedAnswersSkipExplicitlyAndRejectCancelledReplies() {
        for (mode in listOf("mixed", "skip")) {
            val session = startSession("PET_QUESTION_${mode.uppercase(java.util.Locale.ROOT)}")
            val pending = awaitQuestion(session)
            tap(awaitNode("Alpha"))
            tap(awaitNode("Beta"))
            val input = localized(R.string.pet_overlay_custom_description, "Choose fictional preferences")
            tap(awaitNode(input, description = true))
            await("The actual answer window did not open the IME", 5000) { keyboardVisible() }
            assertTrue("The actual question input rejected accessibility text", setText(input, "Fictional extra detail"))
            await("The actual answer draft lost its manual text", 5000) {
                onMain { (overlayField("questionDraft") as? PetQuestionDraft)?.answers?.firstOrNull()?.custom == "Fictional extra detail" }
            }
            val draft = onMain { overlayField("questionDraft") as PetQuestionDraft }
            assertEquals(2, draft.encode().getJSONObject(0).getJSONArray("selected").length())
            assertTrue(automation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK))
            await("Cancelling input did not close the IME", 5000) { !keyboardVisible() }
            singlePetTap()
            await("The actual pending question did not fold", 5000) { onMain { overlayField("bubbleHost") == null } }
            singlePetTap()
            awaitNode("Fictional extra detail")
            assertSame("A real pending request lost its draft on reopening", draft, onMain { overlayField("questionDraft") })
            captureEvidence("pet-real-question-$mode-before-answer")
            if (mode == "skip") tap(awaitNode(localized(R.string.pet_overlay_leave_unanswered_description), description = true))
            else tap(awaitNode(localized(R.string.pet_overlay_send_answer)))
            await("The real user-question service did not receive its answer", 15_000) {
                rows(probe(session).getJSONArray("events")).any { it.optString("kind") == "question-answer" }
            }
            val answered = probe(session)
            val answerEvent = rows(answered.getJSONArray("events")).single { it.optString("kind") == "question-answer" }
            val answer = answerEvent.getJSONObject("value").getJSONArray("answers").getJSONObject(0)
            assertEquals("preferences", answer.getString("id"))
            if (mode == "mixed") {
                assertEquals(listOf("Alpha", "Beta"), strings(answer.getJSONArray("selected")))
                assertEquals("Fictional extra detail", answer.getString("custom"))
            } else {
                assertEquals("Skip submitted selected answers", 0, answer.getJSONArray("selected").length())
                assertFalse("Skip submitted a manual answer", answer.has("custom"))
            }
            assertThrows("An acknowledged request was answered twice", IOException::class.java) {
                PetActions.answer(session, pending.getString("requestId"), draft.encode())
            }
            writeEvidence("pet-real-question-$mode", probe(session))
        }
        val cancelledSession = startSession("PET_QUESTION_CANCEL")
        val withdrawn = awaitQuestion(cancelledSession)
        tap(awaitNode("Alpha"))
        val draft = onMain { overlayField("questionDraft") as PetQuestionDraft }
        val revision = probe(cancelledSession).getJSONObject("control").getLong("controlRevision")
        PetActions.control(cancelledSession, "stop", revision)
        val cancelled = awaitPhase(cancelledSession, "stopped")
        await("Stopping a real question left its old request pending", 10_000) {
            ToolProgress.current(context).optJSONObject("pending")?.optString("requestId") != withdrawn.getString("requestId")
        }
        assertThrows("A cancelled real question accepted a stale answer", IOException::class.java) {
            PetActions.answer(cancelledSession, withdrawn.getString("requestId"), draft.encode())
        }
        assertEquals(0, rows(probe(cancelledSession).getJSONArray("events")).count { it.optString("kind") == "question-answer" })
        assertTrue(rows(cancelled.getJSONArray("events")).any { it.optString("kind") == "question-cancelled" })
        writeEvidence("pet-real-question-cancelled", probe(cancelledSession))
    }

    private fun startSession(prompt: String): String {
        val id = "pet-native-${UUID.randomUUID()}"
        val created = native("/droy/native/session/create", JSONObject().put("sessionId", id).put("agentPreset", "standard"))
        assertEquals(id, created.getString("sessionId"))
        val receipt = native("/droy/native/session/prompt", JSONObject().put("sessionId", id)
            .put("requestId", "pet-initial-${UUID.randomUUID()}").put("mode", "queue")
            .put("content", JSONArray().put(JSONObject().put("type", "text").put("text", prompt))))
        assertTrue(receipt.getBoolean("accepted"))
        return id
    }
    private fun awaitNativeWait(session: String, call: String): JSONObject {
        await("The SDK never entered the actual native wait: $call", 30_000) {
            val journal = File(fixture.home, "native-pet-control-events.jsonl")
            if (!journal.isFile || journal.readLines().filter { it.isNotBlank() }.map(::JSONObject)
                    .none { it.optString("sessionId") == session && it.optString("kind") == "model" && !it.optBoolean("title") }) false
            else {
                val result = probe(session)
                val scope = result.optJSONObject("scope")
                scope != null && nativeState(session, scope.getString("operationId")).pending().any { it.callId() == call }
            }
        }
        return probe(session)
    }
    private fun awaitPhase(session: String, phase: String): JSONObject {
        await("The real task did not confirm $phase", 30_000) { probe(session).getJSONObject("control").getString("phase") == phase }
        return probe(session)
    }
    private fun awaitQuestion(session: String): JSONObject {
        await("The real SDK question did not reach native progress", 30_000) {
            val state = ToolProgress.current(context)
            state.optString("taskId") == session && state.optJSONObject("pending")?.optJSONArray("questions")?.length() == 1
        }
        PetOverlay.refresh(context)
        if (onMain { overlayField("bubbleHost") == null }) singlePetTap()
        awaitNode("Alpha")
        return JSONObject(ToolProgress.current(context).getJSONObject("pending").toString())
    }
    private fun awaitProgressRevision(session: String, revision: Long) = await("Native progress retained a stale controller revision", 10_000) {
        val value = ToolProgress.current(context)
        value.optString("taskId") == session && value.optLong("controlRevision") == revision
    }
    private fun nativeState(session: String, operation: String) = NativeTaskControl.shared().status(session, operation)
    private fun probe(session: String) = native("/droy/native/fixture/pet-control?sessionId=$session")
    private fun modelCalls(result: JSONObject) = rows(result.getJSONArray("events")).filter { it.optString("kind") == "model" && !it.getBoolean("title") }
    private fun native(path: String, body: JSONObject? = null): JSONObject = runBlocking {
        val result = fixture.runtimeTransport.json(path, if (body == null) "GET" else "POST", body)
        assertTrue(result.getBoolean("ok")); result.getJSONObject("result")
    }
    private fun captureEvidence(name: String) {
        require(name.matches(Regex("[A-Za-z0-9_-]+")))
        check(::automation.isInitialized) { "The non-suppressing automation is not initialized" }
        instrumentation.waitForIdleSync()
        val directory = File(context.getExternalFilesDir(null), "native-isolated-evidence").apply { assertTrue(isDirectory || mkdirs()) }
        val bitmap = requireNotNull(automation.takeScreenshot()) { "Could not capture actual pet control evidence" }
        try { File(directory, "$name.png").outputStream().use { assertTrue(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)) } }
        finally { bitmap.recycle() }
    }
    private fun writeEvidence(name: String, value: JSONObject) {
        val directory = File(context.getExternalFilesDir(null), "native-isolated-evidence").apply { assertTrue(isDirectory || mkdirs()) }
        File(directory, "$name.json").writeText(value.toString(2))
    }
    private fun openTaskBubble() { singlePetTap(); awaitNode(localized(R.string.pet_overlay_pause), description = true) }
    private fun tapControl(tag: String, description: Int) {
        if (onMain { overlayField("bubbleHost") == null }) singlePetTap()
        await("The actual pet control was not enabled: $tag", 10_000) {
            onMain { (overlayField("bubble") as? View)?.findViewWithTag<View>(tag)?.isEnabled == true }
        }
        val bounds = awaitNode(localized(description), description = true)
        assertTrue(bounds.width() >= dp(48) && bounds.height() >= dp(48))
        tap(bounds)
    }
    private fun singlePetTap() {
        SystemClock.sleep(android.view.ViewConfiguration.getDoubleTapTimeout() + 32L)
        tap(onMain { PetOverlay.get(context).petBounds() })
    }
    private fun tap(bounds: Rect) { ui.tapAt(bounds.centerX().toFloat(), bounds.centerY().toFloat()) }
    private fun localized(resource: Int, vararg arguments: Any): String {
        val config = android.content.res.Configuration(context.resources.configuration)
        config.setLocale(java.util.Locale.forLanguageTag(NativeLocale.active()))
        return context.createConfigurationContext(config).getString(resource, *arguments)
    }
    private fun overlayField(name: String): Any? = PetOverlay::class.java.getDeclaredField(name).apply { isAccessible = true }.get(PetOverlay.get(context))
    private fun <T> onMain(block: () -> T): T {
        val result = AtomicReference<T>()
        instrumentation.runOnMainSync { result.set(block()) }
        return result.get()
    }
    private fun awaitNode(value: String, description: Boolean = false): Rect {
        var result: Rect? = null
        await("The real native overlay node was not visible: $value", 10_000) {
            result = findNode(value, description); result != null
        }
        return requireNotNull(result)
    }
    private fun findNode(value: String, description: Boolean): Rect? = withNode(value, description) { node -> Rect().also(node::getBoundsInScreen) }
    private fun setText(description: String, value: String): Boolean = withNode(description, true) { node ->
        node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value) })
    } ?: false
    private fun <T> withNode(value: String, description: Boolean, use: (AccessibilityNodeInfo) -> T): T? {
        fun visit(node: AccessibilityNodeInfo): T? {
            val label = if (description) node.contentDescription else node.text
            if (node.isVisibleToUser && label?.toString() == value) return use(node)
            for (i in 0 until node.childCount) node.getChild(i)?.let { child ->
                try { visit(child)?.let { return it } } finally { child.recycle() }
            }
            return null
        }
        for (window in automation.windows) {
            try { window.root?.let { root -> try { visit(root)?.let { return it } } finally { root.recycle() } } }
            finally { window.recycle() }
        }
        return null
    }
    private fun keyboardVisible(): Boolean {
        var visible = false
        for (window in automation.windows) try { if (window.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD) visible = true }
        finally { window.recycle() }
        return visible
    }
    private fun dp(value: Int) = (value * context.resources.displayMetrics.density).toInt()
    private fun rows(values: JSONArray) = (0 until values.length()).map(values::getJSONObject)
    private fun strings(values: JSONArray) = (0 until values.length()).map(values::getString)
    private fun await(message: String, timeout: Long, condition: () -> Boolean) {
        val end = SystemClock.elapsedRealtime() + timeout
        while (!condition() && SystemClock.elapsedRealtime() < end) SystemClock.sleep(100)
        assertTrue(message, condition())
    }
}
