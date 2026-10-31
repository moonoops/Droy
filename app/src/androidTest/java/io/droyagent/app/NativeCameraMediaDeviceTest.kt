package io.droyagent.app

import android.Manifest
import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.content.pm.PackageManager
import android.database.sqlite.SQLiteDatabase
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.provider.MediaStore
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.droyagent.app.media.MediaInputStore
import io.droyagent.app.nativeui.companion.data.NativeCompanionViewModel
import io.droyagent.app.nativeui.companion.data.NativeCompanionProfilePatch
import io.droyagent.app.nativeui.data.NativeApiClient
import io.droyagent.app.nativeui.data.NativeWorkbenchViewModel
import io.droyagent.app.nativeui.media.NativeLocalAttachment
import io.droyagent.app.nativeui.media.NativeMediaViewModel
import io.droyagent.app.nativeui.tools.NativeToolSource
import io.droyagent.app.nativeui.tools.NativeToolsPage
import io.droyagent.app.nativeui.tools.NativeToolsViewModel
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.runner.RunWith

/** Synthetic camera-app results through the real registry/FileProvider/MediaStore, not camera-sensor acceptance. */
@RunWith(AndroidJUnit4::class)
class NativeCameraMediaDeviceTest {
    @get:Rule(order = 0) val compose = createEmptyComposeRule()
    @get:Rule(order = 1) val requirements = object : ExternalResource() {
        override fun before() {
            val args = InstrumentationRegistry.getArguments()
            assumeTrue("Explicit synthetic camera fixture required", args.getString("nativeWorkbenchFixture") == "true"
                && args.getString("nativeCameraFixture") == "true")
            assertEquals("Root must grant CAMERA before instrumentation and restore its original grant afterward",
                PackageManager.PERMISSION_GRANTED, instrumentation.targetContext.checkSelfPermission(Manifest.permission.CAMERA))
        }
    }
    @get:Rule(order = 2) val fixture = NativeIsolatedRuntimeFixture()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = fixture.context
    private lateinit var work: NativeWorkbenchViewModel
    private lateinit var companions: NativeCompanionViewModel
    private lateinit var media: NativeMediaViewModel
    private lateinit var tools: NativeToolsViewModel
    private val ownOutputs = CopyOnWriteArrayList<Output>()
    private val ownAlbumRows = linkedSetOf<Uri>()
    private val monitors = mutableListOf<Instrumentation.ActivityMonitor>()

    @Before fun startIsolatedRuntimeWithoutOpeningAnyHardware() {
        bindModels()
        fixture.startRuntime()
        compose.waitUntil(20_000) { work.state.value.runtime.ready && work.state.value.workspaces.isNotEmpty()
            && companions.state.value.companions.isNotEmpty() }
        assertFalse("The fixture has not paused product operations", ProductPermissions.stopped(context))
        assertTrue("The human camera entry must be foreground", NativeCameraInputHost.inspect(context).foreground)
        awaitNode("native-workbench")
    }

    @After fun removeOnlyThisSyntheticCamerasExactFilesAndAlbumRows() {
        monitors.toList().forEach(instrumentation::removeMonitor); monitors.clear()
        if (::media.isInitialized) {
            fixture.scenario.onActivity { media.cancelPending() }
            compose.waitUntil(15_000) { media.state.value.phase == null && !media.state.value.busy }
        }
        val imports = MediaInputStore.nativeDrafts(context)
        val ownedIds = ownOutputs.map { it.id }.toSet()
        val rows = imports.list()
        for (index in 0 until rows.length()) {
            val row = rows.getJSONObject(index)
            if (row.getString("id") !in ownedIds) continue
            assertEquals("Only this test's synthetic camera imports may be cleaned", "camera", row.getString("source"))
            rememberAlbum(row)
            imports.complete(row.getString("id"), "work")
        }
        ownAlbumRows.forEach { uri ->
            val removed = context.contentResolver.delete(uri, null, null)
            assertTrue("Only the recorded row is removed; an unpublished row may already be gone", removed in 0..1)
        }
        ownOutputs.forEach { NativeCameraInputHost.discardOutput(context, it.id) }
        assertEquals("This test never grants or revokes the system camera permission", PackageManager.PERMISSION_GRANTED,
            context.checkSelfPermission(Manifest.permission.CAMERA))
        // NativeIsolatedRuntimeFixture owns original preferences/home restoration after this exact-ID cleanup.
    }

    @Test fun syntheticCameraResultStaysInWorkbenchDraftThenNativeVisionSendsAndToolsOpensDurableImage() {
        val marker = "NATIVE_SYNTHETIC_CAMERA_VISION"
        fixture.scenario.onActivity { work.setDraft(marker) }
        val before = generationBoundary()
        val draft = work.state.value.draftId
        syntheticCamera(Activity.RESULT_OK, Activity.RESULT_CANCELED).use { camera ->
            chooseWorkbenchCamera()
            awaitCamera(camera, 1)
            compose.waitUntil(15_000) { work.state.value.draftAttachments.size == 1 && !media.state.value.busy }
            val local = work.state.value.draftAttachments.single()
            assertEquals(camera.outputs.single().id, local.importId)
            assertEquals(draft, work.state.value.draftId)
            assertEquals(marker, work.state.value.draft)
            assertNull("Private camera admission cannot materialize a work session", work.state.value.selectedSessionId)
            assertNull(media.state.value.error)
            assertSyntheticImage(runBlocking { work.loadLocalAttachment(local) })
            assertAlbumOutcomeIndependentOfPrivateDraft(local)
            assertNoGenerationOrUpload(before)
            compose.waitForIdle(); compose.mainClock.advanceTimeBy(1_000); compose.waitForIdle(); fixture.capture("synthetic-camera-result-workbench-private-draft")
            dismissAlbumNotice()

            chooseWorkbenchCamera()
            awaitCamera(camera, 2)
            compose.waitUntil(10_000) { media.state.value.phase == null && !media.state.value.busy }
            assertEquals("Cancelling another synthetic result keeps the first draft image", listOf(local), work.state.value.draftAttachments)
            assertEquals(marker, work.state.value.draft)
            assertEquals(2, camera.outputs.map { it.uri }.distinct().size)
            assertFalse("A cancelled camera output is not a private import", imports().any { it.getString("id") == camera.outputs.last().id })

            fixture.recreate(); bindModels(); awaitNode("native-workbench")
            compose.waitUntil(15_000) { work.state.value.draftAttachments == listOf(local) && work.state.value.runtime.ready }
            assertEquals(draft, work.state.value.draftId)
            assertEquals(marker, work.state.value.draft)
            assertNoCameraReopen(camera, 2)
            assertNoGenerationOrUpload(before)
            compose.waitForIdle(); compose.mainClock.advanceTimeBy(1_000); compose.waitForIdle(); fixture.capture("synthetic-camera-result-workbench-recreated")

            compose.onNodeWithTag("native-model").performClick()
            compose.onNodeWithText("Native vision fixture").performClick()
            compose.waitUntil(10_000) { work.state.value.selectedModel?.id == "native-vision" }
            assertNoGenerationOrUpload(before)
            compose.onNodeWithTag("native-send").assertIsEnabled().performClick()
            compose.waitUntil(30_000) { work.state.value.messages.any { it.role == "assistant" && it.text == "Native DSH reply received." && !it.streaming }
                && !work.state.value.sending && !work.state.value.running && work.state.value.messages.none { it.streaming } }
            assertNull(work.state.value.error)
            val session = requireNotNull(work.state.value.selectedSessionId)
            val sent = work.state.value.messages.single { it.role == "user" && it.text == marker }
            val committed = sent.attachments.single()
            assertEquals("image", committed.kind)
            val seq = requireNotNull(sent.seq)
            val ref = requireNotNull(committed.attachmentId)
            val call = log("native-fixture-calls.jsonl").single { !it.optBoolean("title") && it.optString("prompt") == marker }
            assertEquals("native-vision", call.getString("model"))
            assertTrue("The actual SDK provider input must retain an image part", call.getJSONArray("inputKinds").let { kinds ->
                (0 until kinds.length()).any { kinds.getString(it) == "image" }
            })
            compose.waitUntil(10_000) { work.state.value.draftAttachments.isEmpty() }

            fixture.recreate(); bindModels(); awaitNode("native-workbench")
            compose.waitUntil(15_000) { work.state.value.messages.any { it.id == sent.id && it.seq == seq && it.attachments.any { item -> item.attachmentId == ref } } }
            compose.onNodeWithTag("native-history").performScrollToNode(hasTestTag("native-message-attachment:${sent.id}:0"))
            compose.onNodeWithTag("native-message-attachment:${sent.id}:0").assertIsEnabled().performClick()
            awaitNode("native-tools")
            compose.waitUntil(20_000) { tools.state.value.page == NativeToolsPage.PREVIEW && !tools.state.value.loading && tools.state.value.preview?.kind == "image" }
            assertNull(tools.state.value.error)
            assertEquals(NativeToolSource.Attachment(session, seq, ref), tools.state.value.fileSource)
            assertSyntheticImage(requireNotNull(tools.state.value.preview?.data))
            assertNoCameraReopen(camera, 2)
            compose.waitForIdle(); compose.mainClock.advanceTimeBy(1_000); compose.waitForIdle(); fixture.capture("synthetic-camera-result-native-vision-durable-tools-image")
        }
    }

    @Test fun syntheticCameraResultPreviewsThenSendsImageOnlyAfterCancelAndRecreation() {
        val owner = companions.state.value.companions.first { it.templateId == "kaka" }.id
        val workDraft = "Workbench draft retained during synthetic companion camera"
        fixture.scenario.onActivity { work.setDraft(workDraft) }
        val workTarget = work.state.value.draftId
        compose.onNodeWithTag("native-tab-messages").performClick()
        awaitNode("native-companion-conversation:$owner")
        compose.onNodeWithTag("native-companion-conversation:$owner").performClick()
        awaitNode("native-companion-composer")
        val marker = "Companion draft with a synthetic camera photo"
        fixture.scenario.onActivity { companions.setDraft(marker) }
        val before = generationBoundary()
        syntheticCamera(Activity.RESULT_OK, Activity.RESULT_CANCELED).use { camera ->
            chooseCompanionCamera()
            awaitCamera(camera, 1)
            compose.waitUntil(15_000) { companions.state.value.draft.localAttachments.size == 1 && !media.state.value.busy }
            val local = companions.state.value.draft.localAttachments.single()
            assertEquals(camera.outputs.single().id, local.importId)
            assertEquals(owner, companions.state.value.selectedCompanionId)
            assertEquals(marker, companions.state.value.draft.text)
            assertTrue("There is no hosted upload reference before Send", companions.state.value.draft.attachments.isEmpty())
            assertTrue(companions.state.value.messages.none { it.role == "user" })
            assertTrue(companions.state.value.draftsByCompanion.filterKeys { it != owner }.values.all { it.localAttachments.isEmpty() })
            assertEquals(workTarget, work.state.value.draftId)
            assertEquals(workDraft, work.state.value.draft)
            assertTrue(work.state.value.draftAttachments.isEmpty())
            assertNull(media.state.value.error)
            assertSyntheticImage(runBlocking { companions.loadLocalAttachment(local) })
            assertAlbumOutcomeIndependentOfPrivateDraft(local)
            assertNoGenerationOrUpload(before)
            compose.waitForIdle(); compose.mainClock.advanceTimeBy(1_000); compose.waitForIdle(); fixture.capture("synthetic-camera-result-original-companion-private-draft")
            dismissAlbumNotice()

            chooseCompanionCamera()
            awaitCamera(camera, 2)
            compose.waitUntil(10_000) { media.state.value.phase == null && !media.state.value.busy }
            assertEquals(listOf(local), companions.state.value.draft.localAttachments)
            assertEquals(marker, companions.state.value.draft.text)
            fixture.recreate(); bindModels(); awaitNode("native-companion-composer")
            compose.waitUntil(15_000) { companions.state.value.draft.localAttachments == listOf(local) }
            assertEquals(owner, companions.state.value.selectedCompanionId)
            assertEquals(marker, companions.state.value.draft.text)
            assertNoCameraReopen(camera, 2)
            assertNoGenerationOrUpload(before)
            compose.onNodeWithTag("native-companion-send").assertIsEnabled()
            compose.waitForIdle(); compose.mainClock.advanceTimeBy(1_000); compose.waitForIdle(); fixture.capture("synthetic-camera-result-companion-recreated-unsent")
            dismissAlbumNotice()
            compose.onNodeWithTag("native-preview-local-attachment:${local.id}").performClick()
            compose.onNodeWithTag("native-local-preview-close").assertIsDisplayed()
            compose.waitForIdle(); compose.mainClock.advanceTimeBy(1_000); compose.waitForIdle(); fixture.capture("companion-camera-full-preview")
            compose.onNodeWithTag("native-local-preview-close").performClick()
            val profile = companions.state.value.companions.first { it.id == owner }
            val vision = companions.state.value.models.first { it.provider == "droy-native-fixture" && it.id == "native-vision" }
            fixture.scenario.onActivity {
                companions.updateProfile(owner, NativeCompanionProfilePatch(model = vision, settings = profile.settings.copy(proactiveEnabled = false)))
            }
            compose.waitUntil(15_000) { !companions.state.value.profileSaving && companions.state.value.companions.any {
                it.id == owner && it.model?.id == "native-vision" && !it.modelInherited } }
            fixture.scenario.onActivity { companions.setDraft("") }
            compose.onNodeWithTag("native-companion-send").assertIsEnabled().performClick()
            compose.waitUntil(30_000) { companions.state.value.messages.any { it.role == "user" && it.attachments.isNotEmpty() && it.status !in setOf("pending", "sending", "failed") }
                && !companions.state.value.sending && companions.state.value.draft.localAttachments.isEmpty() }
            val sent = companions.state.value.messages.single { it.role == "user" && it.attachments.isNotEmpty() }
            val durable = runBlocking { io.droyagent.app.nativeui.companion.data.NativeCompanionClient().page(owner) }.messages.single { it.id == sent.id }
            assertEquals(sent.attachments, durable.attachments)
            assertEquals("A photo does not require synthetic message text", "", durable.text)
            assertTrue(sent.attachments.single().name.matches(Regex("Photo-\\d{8}-\\d{6}\\.[a-z0-9]+")))
            val imageTag = "native-companion-image:${sent.attachments.single().id}"
            compose.waitUntil(10_000) {
                val bounds = compose.onAllNodesWithTag(imageTag, useUnmergedTree = true)
                    .fetchSemanticsNodes(false).singleOrNull()?.boundsInRoot
                bounds != null && bounds.height > 0 && kotlin.math.abs(bounds.width / bounds.height - 1.5f) < .015f
            }
            val previewBounds = compose.onNodeWithTag(imageTag, useUnmergedTree = true).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            assertEquals("The 96x64 photo must keep its decoded 3:2 shape even without receipt dimensions",
                1.5f, previewBounds.width / previewBounds.height, .015f)
            compose.waitForIdle(); compose.mainClock.advanceTimeBy(1_000); compose.waitForIdle(); fixture.capture("companion-camera-image-only-sent")
        }
    }

    private fun bindModels() = fixture.scenario.onActivity { activity ->
        work = ViewModelProvider(activity)[NativeWorkbenchViewModel::class.java]
        companions = ViewModelProvider(activity)[NativeCompanionViewModel::class.java]
        media = ViewModelProvider(activity)[NativeMediaViewModel::class.java]
        tools = ViewModelProvider(activity)[NativeToolsViewModel::class.java]
    }
    private fun chooseWorkbenchCamera() {
        compose.onNodeWithTag("native-composer-attachments").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Camera").fetchSemanticsNodes(false).isNotEmpty() }
        compose.onNodeWithText("Camera").performClick()
    }
    private fun chooseCompanionCamera() {
        compose.onNodeWithTag("native-companion-composer-options").performClick()
        compose.onNodeWithTag("native-companion-camera").performClick()
    }
    private fun dismissAlbumNotice() { fixture.scenario.onActivity { media.dismissCameraAlbumResult() }; compose.waitForIdle() }
    private fun awaitNode(tag: String) { compose.waitUntil(15_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }; compose.onNodeWithTag(tag).assertIsDisplayed() }
    private fun imports(): List<JSONObject> = MediaInputStore.nativeDrafts(context).list().let { rows -> (0 until rows.length()).map(rows::getJSONObject) }
    private fun log(name: String): List<JSONObject> = File(fixture.home, name).let { file -> if (file.isFile) file.readLines().filter(String::isNotBlank).map(::JSONObject) else emptyList() }
    private fun attachmentObjects(): List<String> = File(fixture.home, "attachments/v1").let { root ->
        if (root.isDirectory) root.walkTopDown().filter { it.isFile }.map { it.relativeTo(root).path + ":" + it.length() }.sorted().toList() else emptyList()
    }
    private fun companionUploadCount(): Long {
        val database = File(fixture.home, "droy-companions/companions.sqlite")
        assertTrue(database.isFile)
        return SQLiteDatabase.openDatabase(database.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            db.rawQuery("SELECT COUNT(*) FROM companion_uploads", null).use { row -> assertTrue(row.moveToFirst()); row.getLong(0) }
        }
    }
    private data class Boundary(val sessions: Set<String>, val objects: List<String>, val uploads: Long, val modelCalls: Int, val companionCalls: Int)
    private fun generationBoundary(): Boundary {
        val sessions = runBlocking { NativeApiClient().post("list") as JSONObject }.getJSONArray("items")
        return Boundary((0 until sessions.length()).map { sessions.getJSONObject(it).getString("sessionId") }.toSet(),
            attachmentObjects(), companionUploadCount(), log("native-fixture-calls.jsonl").size, log("native-fixture-companion.jsonl").size)
    }
    private fun assertNoGenerationOrUpload(before: Boundary) { assertEquals("Private selection must not create a session, publish an engine attachment, upload, or call a model", before, generationBoundary()) }

    private fun assertAlbumOutcomeIndependentOfPrivateDraft(local: NativeLocalAttachment) {
        val manifest = imports().single { it.getString("id") == local.importId }
        assertEquals("camera", manifest.getString("source")); assertEquals("work", manifest.getString("consumer"))
        assertTrue("Camera names use a readable local timestamp", manifest.getString("originalName").matches(Regex("Photo-\\d{8}-\\d{6}\\.[a-z0-9]+")))
        assertFalse("The private photo has not been rebound to an engine session", manifest.has("targetSessionId"))
        rememberAlbum(manifest)
        compose.waitUntil(5_000) { media.state.value.cameraAlbumResult?.captureId == local.importId }
        val result = requireNotNull(media.state.value.cameraAlbumResult)
        assertEquals(manifest.optBoolean("albumSaved"), result.saved)
        assertEquals(manifest.optString("albumError").isNotBlank(), result.failed)
        assertTrue("Album completion must be explicit and independent of the readable private draft", result.saved != result.failed)
        if (result.saved) {
            val uri = Uri.parse(manifest.getString("albumUri"))
            context.contentResolver.query(uri, arrayOf(MediaStore.Images.Media.IS_PENDING), null, null, null)!!.use { row ->
                assertTrue(row.moveToFirst()); assertEquals(0, row.getInt(0))
            }
            assertSyntheticImage(context.contentResolver.openInputStream(uri)!!.use { it.readBytes() })
        }
        val notice = context.getString(if (result.saved) R.string.native_voice_input_album_saved else R.string.native_voice_input_album_failed)
        compose.waitUntil(5_000) { compose.onAllNodesWithText(notice).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(notice).assertIsDisplayed()
    }
    private fun rememberAlbum(manifest: JSONObject) {
        manifest.optString("albumUri").takeIf { it.isNotBlank() }?.let { raw ->
            val uri = Uri.parse(raw)
            assertEquals("content", uri.scheme); assertEquals(MediaStore.AUTHORITY, uri.authority)
            assertTrue(requireNotNull(uri.lastPathSegment).all(Char::isDigit))
            ownAlbumRows += uri
        }
    }
    private fun assertSyntheticImage(bytes: ByteArray) {
        val bitmap = requireNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size))
        try { assertEquals(96, bitmap.width); assertEquals(64, bitmap.height) } finally { bitmap.recycle() }
    }

    private data class Output(val id: String, val uri: Uri)
    private inner class SyntheticCamera(resultCodes: IntArray) : AutoCloseable {
        val hits = AtomicInteger()
        val outputs = CopyOnWriteArrayList<Output>()
        val failure = AtomicReference<Throwable?>()
        private val remaining = ConcurrentLinkedQueue(resultCodes.toList())
        private val monitor = object : Instrumentation.ActivityMonitor() {
            @Suppress("DEPRECATION")
            override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult? {
                if (intent.action != MediaStore.ACTION_IMAGE_CAPTURE) return null
                this@SyntheticCamera.hits.incrementAndGet()
                return try {
                    val code = remaining.poll() ?: throw AssertionError("Unexpected camera reopening after a synthetic result")
                    val output: Uri? = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(MediaStore.EXTRA_OUTPUT, Uri::class.java)
                        else intent.getParcelableExtra(MediaStore.EXTRA_OUTPUT)
                    val uri = requireNotNull(output)
                    assertEquals("content", uri.scheme); assertEquals("${context.packageName}.media-input-files", uri.authority)
                    val name = requireNotNull(uri.lastPathSegment)
                    val id = name.removePrefix("native-").removeSuffix(".jpg")
                    assertEquals("native-${UUID.fromString(id)}.jpg", name)
                    val record = Output(id, uri); outputs += record; ownOutputs += record
                    assertTrue(intent.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION != 0)
                    assertTrue(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
                    assertEquals(uri, intent.clipData!!.getItemAt(0).uri)
                    if (code == Activity.RESULT_OK) context.contentResolver.openOutputStream(uri, "wt")!!.use { it.write(syntheticJpeg()) }
                    Instrumentation.ActivityResult(code, null)
                } catch (error: Throwable) {
                    failure.set(error); Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null)
                }
            }
        }
        init { instrumentation.addMonitor(monitor); monitors += monitor }
        override fun close() { instrumentation.removeMonitor(monitor); monitors.remove(monitor) }
    }
    private fun syntheticCamera(vararg codes: Int) = SyntheticCamera(codes)
    private fun syntheticJpeg(): ByteArray {
        val bitmap = Bitmap.createBitmap(96, 64, Bitmap.Config.ARGB_8888)
        return try {
            bitmap.eraseColor(0xffe1a649.toInt())
            ByteArrayOutputStream().use { output -> assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 95, output)); output.toByteArray() }
        } finally { bitmap.recycle() }
    }
    private fun awaitCamera(camera: SyntheticCamera, expected: Int) {
        compose.waitUntil(10_000) { camera.hits.get() >= expected }
        camera.failure.get()?.let { throw AssertionError("Synthetic camera-app boundary failed", it) }
        assertEquals(expected, camera.hits.get())
    }
    private fun assertNoCameraReopen(camera: SyntheticCamera, expected: Int) {
        val until = SystemClock.elapsedRealtime() + 1_500
        while (SystemClock.elapsedRealtime() < until) {
            camera.failure.get()?.let { throw AssertionError("Synthetic camera-app boundary failed", it) }
            assertEquals("Recreation must not reopen a completed or cancelled synthetic camera operation", expected, camera.hits.get())
            SystemClock.sleep(100)
        }
    }
}
