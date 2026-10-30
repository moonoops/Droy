package io.droyagent.app

import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.view.KeyEvent
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.droyagent.app.nativeui.data.NativeWorkbenchViewModel
import io.droyagent.app.nativeui.settings.NativeSettingsViewModel
import io.droyagent.app.nativeui.tools.NativeToolsPage
import io.droyagent.app.nativeui.tools.NativeToolsViewModel
import io.droyagent.app.nativeui.workspaces.NativeWorkspacesViewModel
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Real local DSH logs/attachments and the existing isolated fixture; no remote provider or device permissions. */
@RunWith(AndroidJUnit4::class)
class NativeWorkbenchReviewFlowDeviceTest {
    @get:Rule(order = 0) val compose = createEmptyComposeRule()
    @get:Rule(order = 1) val fixture = NativeIsolatedRuntimeFixture()
    private lateinit var work: NativeWorkbenchViewModel
    private lateinit var spaces: NativeWorkspacesViewModel
    private lateinit var tools: NativeToolsViewModel
    private lateinit var settings: NativeSettingsViewModel
    private lateinit var receipt: JSONObject
    private var language = "en"

    @Test fun reviewMenusGroupedHistoryAndDurableMediaUseTheRealLocalServices() {
        seedOwnedRuntime()
        bindModels()
        val session = receipt.getString("session")
        fixture.scenario.onActivity { work.refresh() }
        compose.waitUntil(20_000) { work.state.value.sessions.any { it.id == session } }
        fixture.scenario.onActivity { work.selectSession(session) }
        awaitHistory(session)

        for (locale in listOf("en", "zh")) {
            language = locale
            fixture.scenario.onActivity { settings.setLanguage(locale) }
            compose.waitUntil(20_000) { !settings.state.value.saving && settings.state.value.locale == locale && work.state.value.runtime.locale == locale }
            assertNull(settings.state.value.error)
            compose.onNodeWithTag("native-composer-attachments").performClick()
            compose.onNodeWithText(text(R.string.native_work_photos)).assertIsDisplayed()
            compose.onNodeWithText(text(R.string.native_share_file)).assertIsDisplayed()
            compose.onNodeWithText(text(R.string.native_engine_compact)).assertIsDisplayed().performClick()
            compose.onNodeWithTag("native-engine-compact").assertIsDisplayed()
            compose.onNodeWithText(text(R.string.native_engine_compact)).performClick()
            compose.onNodeWithText(text(R.string.native_engine_compact_cost)).assertExists()
            back(); back()
            awaitTag("native-composer-attachments")
            compose.onNodeWithTag("native-composer-attachments").performClick()
            compose.onNodeWithText(text(R.string.native_goal_title)).assertIsDisplayed().performClick()
            compose.waitUntil(15_000) { compose.onAllNodesWithText(text(R.string.native_goal_objective)).fetchSemanticsNodes(false).isNotEmpty() }
            compose.onNodeWithText(text(R.string.native_goal_start)).assertIsNotEnabled()
            back()
            menu(R.string.native_usage)
            compose.onNodeWithText(text(R.string.native_usage_empty)).assertIsDisplayed()
            compose.onNodeWithText(text(R.string.native_engine_compact)).assertDoesNotExist()
            compose.onNodeWithText(text(R.string.native_goal_start)).assertDoesNotExist()
            fixture.capture("native-review-statistics-readonly-$locale")
            back()
            menu(R.string.native_spaces_chats)
            awaitTag("native-workspaces")
            compose.waitUntil(20_000) { spaces.state.value.catalog != null && !spaces.state.value.loading }
            assertGroup(receipt.getString("space"), true)
            assertGroup(receipt.getString("otherSpace"), false)
            spaceScroll("native-space:${receipt.getString("otherSpace")}")
            compose.onNodeWithTag("native-space:${receipt.getString("otherSpace")}").performClick()
            assertGroup(receipt.getString("otherSpace"), true)
            spaceScroll("native-space-session:${receipt.getString("otherSession")}")
            compose.onNodeWithTag("native-space-session:${receipt.getString("otherSession")}").assertIsDisplayed()
            spaceScroll("native-space-session:$session")
            compose.onNodeWithTag("native-space-session:$session").performClick()
            awaitHistory(session)
            assertNoCalls()
            fixture.capture("native-review-grouped-history-$locale")
            // Return to the spaces page, then its original workbench caller; keep the next loop's stack bounded.
            back(); awaitTag("native-workspaces"); back(); awaitTag("native-workbench")
        }

        val media = work.state.value.messages.single { it.text == "REVIEW_MEDIA_MESSAGE" }
        val image = media.attachments.single { it.kind == "image" }
        val audio = media.attachments.single { it.name == "review-silence.wav" }
        assertNull("The actual DSH FileAttachmentRef has no MIME field", audio.mediaType)
        assertEquals("file", audio.kind)
        val readAudio = runBlocking { tools.loadMessageAttachment(session, requireNotNull(media.seq), requireNotNull(audio.attachmentId)) }
        assertArrayEquals(File(fixture.home, "review-silence.wav").readBytes(), readAudio)
        compose.onNodeWithTag("native-history").performScrollToNode(hasText("review-silence.wav"))
        compose.onNodeWithContentDescription(text(R.string.native_work_play)).assertIsDisplayed().performClick()
        compose.waitUntil(15_000) { compose.onAllNodesWithContentDescription(text(R.string.native_work_pause)).fetchSemanticsNodes(false).isNotEmpty() }
        compose.onNodeWithContentDescription(text(R.string.native_work_pause)).performClick()
        val slider = SemanticsMatcher.keyIsDefined(SemanticsActions.SetProgress)
        compose.onNode(slider).performSemanticsAction(SemanticsActions.SetProgress) { assertTrue(it(5000f)) }
        val range = compose.onNode(slider).fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo]
        assertEquals(5000f, range.current, 1f)
        compose.onNodeWithText(text(R.string.native_work_audio_failed)).assertDoesNotExist()
        fixture.capture("native-review-durable-audio-player")

        val index = media.attachments.indexOf(image)
        val imageTag = "native-message-attachment:${media.id}:$index"
        compose.onNodeWithTag("native-history").performScrollToNode(hasTestTag(imageTag))
        compose.onNodeWithTag(imageTag).assertIsEnabled().performClick()
        awaitTag("native-tools")
        compose.waitUntil(20_000) { !tools.state.value.loading && tools.state.value.preview?.kind == "image" }
        val decoded = BitmapFactory.decodeByteArray(requireNotNull(tools.state.value.preview?.data), 0, tools.state.value.preview!!.data!!.size)
        assertEquals(320, decoded.width); assertEquals(240, decoded.height); decoded.recycle()
        compose.onNodeWithContentDescription("review-image.png").performTouchInput { doubleClick(center) }
        val zoom = SemanticsMatcher("Image has zoom accessibility actions") { node ->
            node.config.getOrNull(SemanticsActions.CustomActions)?.any { it.label == text(R.string.native_work_zoom_reset) } == true
        }
        val zoomActions = compose.onNode(zoom).fetchSemanticsNode().config[SemanticsActions.CustomActions]
        compose.runOnIdle { assertTrue(zoomActions.single { it.label == text(R.string.native_work_zoom_reset) }.action()) }
        fixture.capture("native-review-image-zoom")
        back(); awaitHistory(session)

        // This emulator's system DNS returns a reserved FakeIP. Use the existing public-DNS policy; the fixture restores product preferences.
        assertTrue(fixture.context.getSharedPreferences("product", 0).edit().putBoolean("browserEncryptedDns", true).commit())
        compose.onNodeWithTag("native-history").performScrollToNode(hasText("Example Domain"))
        compose.onNodeWithText("Example Domain").assertIsDisplayed().performClick()
        awaitTag("native-tools-browser-surface")
        // The browser and its DNS perform public read-only requests; no credentials or model calls are involved.
        try {
            compose.waitUntil(25_000) {
                tools.state.value.browser?.let { browser ->
                    browser.url == receipt.getString("url") && !browser.loading && !browser.failed && browser.title == "Example Domain"
                } == true
            }
        } catch (failure: Throwable) {
            try { captureBrowserFailure() } catch (diagnostic: Throwable) { failure.addSuppressed(diagnostic) }
            throw failure
        }
        assertEquals(NativeToolsPage.BROWSER, tools.state.value.page)
        assertNull(tools.state.value.error)
        fixture.capture("native-review-public-web-return")
        back(); awaitHistory(session)
        assertNoCalls()

        // Reopening history above never resent a prompt; this one explicit send is the continuation.
        fixture.scenario.onActivity {
            work.selectModel(work.state.value.models.single { it.id == "native-vision" })
        }
        compose.waitUntil(15_000) { !work.state.value.modelChanging && work.state.value.selectedModel?.id == "native-vision" }
        fixture.scenario.onActivity { work.setDraft("NATIVE_REVIEW_CONTINUATION") }
        compose.onNodeWithTag("native-send").assertIsEnabled().performClick()
        compose.waitUntil(40_000) { !work.state.value.running && !work.state.value.sending && work.state.value.messages.any { it.role == "assistant" && it.text == "Native DSH reply received." && !it.streaming } }
        assertEquals(session, work.state.value.selectedSessionId)
        assertNull(work.state.value.error)
        assertEquals(listOf("NATIVE_REVIEW_CONTINUATION"), calls().filterNot { it.optBoolean("title") }.map { it.getString("prompt") })
        fixture.capture("native-review-history-continued")
    }

    private fun bindModels() {
        fixture.scenario.onActivity {
            val provider = ViewModelProvider(it)
            work = provider[NativeWorkbenchViewModel::class.java]
            spaces = provider[NativeWorkspacesViewModel::class.java]
            tools = provider[NativeToolsViewModel::class.java]
            settings = provider[NativeSettingsViewModel::class.java]
        }
        compose.waitUntil(30_000) { work.state.value.runtime.ready && work.state.value.models.isNotEmpty() && settings.state.value.loaded }
        awaitTag("native-tab-workbench")
        compose.onNodeWithTag("native-tab-workbench").assertIsDisplayed().performClick()
        awaitTag("native-workbench")
        compose.onNodeWithTag("native-workbench").assertIsDisplayed()
    }
    private fun awaitHistory(id: String) {
        awaitTag("native-history")
        compose.onNodeWithTag("native-workbench").assertIsDisplayed()
        compose.waitUntil(20_000) { work.state.value.selectedSessionId == id && !work.state.value.loading && work.state.value.messages.any { it.text == "REVIEW_MEDIA_MESSAGE" } }
        assertNull(work.state.value.error)
    }
    private fun awaitTag(tag: String) = compose.waitUntil(20_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes(false).isNotEmpty() }
    private fun back() { InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK); compose.waitForIdle() }
    private fun menu(resource: Int) { compose.onNodeWithTag("native-workbench-menu").performClick(); compose.onNodeWithText(text(resource)).performClick() }
    private fun spaceScroll(tag: String) { compose.onNodeWithTag("native-spaces-chats").performScrollToNode(hasTestTag(tag)) }
    private fun assertGroup(id: String, expanded: Boolean) {
        spaceScroll("native-space:$id")
        val tag = hasTestTag("native-space:$id")
        compose.onNode((tag or hasAnyAncestor(tag)) and SemanticsMatcher.expectValue(SemanticsProperties.StateDescription,
            text(if (expanded) R.string.native_workspaces_expanded else R.string.native_workspaces_collapsed))).assertExists()
    }
    private fun text(resource: Int): String {
        val config = Configuration(fixture.context.resources.configuration).apply { setLocale(Locale.forLanguageTag(language)) }
        return fixture.context.createConfigurationContext(config).getString(resource)
    }
    private fun calls(): List<JSONObject> = File(fixture.home, "native-fixture-calls.jsonl").takeIf(File::isFile)?.readLines()?.filter(String::isNotBlank)?.map(::JSONObject).orEmpty()
    private fun assertNoCalls() { assertTrue("Reading history and settings must not invoke a model", calls().isEmpty()) }

    private fun captureBrowserFailure() {
        val state = tools.state.value
        val id = state.selectedBrowserId
        val report = JSONObject().put("page", state.page.name).put("selectedBrowserId", id ?: JSONObject.NULL)
            .put("errorCode", state.error ?: JSONObject.NULL).put("sourceUrl", state.browserSourceUrl ?: JSONObject.NULL)
            .put("pendingRequestId", state.pendingBrowserNavigation?.requestId ?: JSONObject.NULL)
            .put("pendingTabId", state.pendingBrowserNavigation?.tabId ?: JSONObject.NULL)
            .put("pendingUrl", state.pendingBrowserNavigation?.url ?: JSONObject.NULL)
            .put("runtimeReady", state.runtime.ready).put("browserUiVisible", BrowserUiProtection.isVisible())
        state.browser?.let { browser -> report.put("nativeView", JSONObject().put("id", browser.id).put("url", browser.url)
            .put("title", browser.title).put("loading", browser.loading).put("failed", browser.failed).put("open", browser.open)) }
        // Read only the existing cache: do not bind a missing service or replay the failed navigation.
        val cached = BrowserSession.status()
        val tabs = cached.optJSONArray("tabs")
        report.put("cachedStatusUnknown", cached.optString("sessionState") == "unknown")
            .put("cachedTabCount", tabs?.length() ?: JSONObject.NULL)
        val current = cached.takeIf { id != null && it.optString("sessionId") == id }
            ?: tabs?.let { values -> (0 until values.length()).map(values::getJSONObject).firstOrNull { it.optString("sessionId") == id } }
        current?.let { frame ->
            val owned = JSONObject()
            for (key in listOf("sessionId", "sessionOpen", "url", "title", "loading", "canGoBack", "canGoForward", "revision", "error", "status"))
                if (frame.has(key)) owned.put(key, frame.get(key))
            report.put("cachedOwnedTab", owned)
        }
        fixture.scenario.onActivity { activity ->
            report.put("activityLifecycle", activity.lifecycle.currentState.name).put("windowFocused", activity.hasWindowFocus())
                .put("decorAttached", activity.window.decorView.isAttachedToWindow)
        }
        val directory = File(fixture.context.getExternalFilesDir(null), "native-isolated-evidence").apply { assertTrue(isDirectory || mkdirs()) }
        File(directory, "review-flow-browser-failure-${System.currentTimeMillis()}.json").writeText(report.toString(2))
        fixture.capture("native-review-browser-failure")
    }

    private fun seedOwnedRuntime() {
        fixture.scenario.moveToState(Lifecycle.State.CREATED)
        fixture.context.startService(Intent(fixture.context, RuntimeService::class.java).setAction(RuntimeService.STOP))
        compose.waitUntil(30_000) { RuntimeService.canManageHistory() }
        val bitmap = Bitmap.createBitmap(320, 240, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.rgb(102, 80, 164)) }
        File(fixture.home, "review-image.png").outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }; bitmap.recycle()
        val sampleBytes = 8000 * 30 * 2
        val wav = ByteBuffer.allocate(44 + sampleBytes).order(ByteOrder.LITTLE_ENDIAN)
        wav.put("RIFF".toByteArray()).putInt(36 + sampleBytes).put("WAVEfmt ".toByteArray()).putInt(16).putShort(1).putShort(1)
            .putInt(8000).putInt(16000).putShort(2).putShort(16).put("data".toByteArray()).putInt(sampleBytes)
        File(fixture.home, "review-silence.wav").writeBytes(wav.array())
        val plugin = File(fixture.home, "native-review-flow.mjs")
        plugin.writeText(seedPlugin)
        val patch = File(fixture.home, "profiles/mobile/cordis.patch.yml")
        patch.writeText(JSONArray(patch.readText()).put(JSONObject().put("insert", JSONArray().put(JSONObject()
            .put("id", "native-review-flow").put("name", plugin.absolutePath)))).toString())
        try {
            // The real service releases an idle background runtime. Seed while the app is visible,
            // just like normal foreground entry, after the stopped profile has been updated.
            fixture.scenario.moveToState(Lifecycle.State.RESUMED)
            fixture.startRuntime()
            compose.waitUntil(30_000) { File(fixture.home, "native-review-flow-ready.json").isFile }
            receipt = JSONObject(File(fixture.home, "native-review-flow-ready.json").readText())
        } finally {
            val directory = File(fixture.context.getExternalFilesDir(null), "native-isolated-evidence").apply { assertTrue(isDirectory || mkdirs()) }
            val report = JSONObject().put("runtimeReady", RuntimeService.ready).put("canManageHistory", RuntimeService.canManageHistory())
                .put("runtimeStatus", NativeRuntimeHost.inspect(fixture.context).status)
            for (name in listOf("native-review-flow-phase.json", "native-review-flow-error.json", "native-review-flow-ready.json")) {
                val source = File(fixture.home, name)
                if (source.isFile) report.put(name, JSONObject(source.readText()))
            }
            File(directory, "review-flow-seed-" + System.currentTimeMillis() + ".json").writeText(report.toString(2))
        }
    }

    private val seedPlugin = """
        import { mkdir, readFile, writeFile } from 'node:fs/promises';
        import { join } from 'node:path';
        import { pathToFileURL } from 'node:url';
        export const name = 'native-review-flow';
        export const inject = ['sessions','sessionPersistence','workspaceRegistry','workspaceController','attachments'];
        export async function apply(ctx) {
          const home = process.env.DSH_HOME;
          const phase = value => writeFile(join(home,'native-review-flow-phase.json'), JSON.stringify({phase:value}));
          try {
          await phase('entered-apply');
          const marker = JSON.parse(await readFile(join(home,'..','native-workbench-fixture.json'),'utf8'));
          if (marker.fixture !== 'native-isolated-runtime-v1' || marker.phase !== 'running') throw new Error('Requires owned fixture');
          const {createUserMessage,createAssistantMessage} = await import(pathToFileURL(join(process.env.DROY_RUNTIME_DIR,'node_modules/@deepseek-ai/dsh-llm/lib/index.js')).href);
          const url = 'https://example.com/';
          await phase('saving-image');
          const image = await ctx.attachments.saveImage({data:await readFile(join(home,'review-image.png')),mediaType:'image/png',name:'review-image.png'});
          await phase('saving-audio');
          const audio = await ctx.attachments.saveFile({data:await readFile(join(home,'review-silence.wav')),name:'review-silence.wav'});
          async function make(suffix,rich) {
            await phase('creating-space-'+suffix);
            const path=join(home,'review-space-'+suffix); await mkdir(path);
            const {workspace}=await ctx.workspaceController.create({path});
            const id='native-review-flow-'+suffix;
            if (await ctx.sessionPersistence.stat(id)) throw new Error('Fixture requires fresh session');
            const session=ctx.sessions.prepare(id,{meta:{cwd:path}});
            session.append('turn/start',{turn:1});
            session.append('user/message',createUserMessage({content:[{type:'text',text:rich?'REVIEW_MEDIA_MESSAGE':'OTHER_REVIEW_HISTORY'},
              ...(rich?[{type:'image',attachment:image},{type:'file',attachment:audio}]:[])],source:{kind:'user'}}),{surfaceOp:'append'});
            session.append('step/start',{turn:1,step:1});
            const text=rich?'[Example Domain]('+url+')\n\n> Fictional quoted text\n\n'+String.fromCharCode(96).repeat(3)+'kotlin\nval review = true\n'+String.fromCharCode(96).repeat(3):'Other saved reply';
            session.append('assistant/message',{turn:1,step:1,message:createAssistantMessage({content:[{type:'text',text}],source:{provider:'droy-native-fixture',model:'native-vision'}}),stream:[]},{surfaceOp:'append'});
            session.append('step/end',{turn:1,step:1}); session.append('turn/end',{turn:1,reason:{kind:'completed'}});
            await phase('persisting-session-'+suffix);
            const writer=await ctx.sessionPersistence.create(session.header,{inheritedEventCount:session.inheritedEventCount});
            try { await writer.append(session.snapshotEvents()); await writer.flush(); } finally { await writer.close(); }
            await ctx.workspaceRegistry.get(workspace.workspaceId).attachSession(id);
            return {id,space:workspace.workspaceId};
          }
          const current=await make('current',true),other=await make('other',false);
          await writeFile(join(home,'native-review-flow-ready.json'),JSON.stringify({session:current.id,space:current.space,otherSession:other.id,otherSpace:other.space,url}));
          await phase('complete');
          } catch (error) {
            await writeFile(join(home,'native-review-flow-error.json'), JSON.stringify({code:error?.code ?? error?.name ?? 'Error',message:String(error?.message ?? error).slice(0,2000),stack:String(error?.stack ?? '').slice(0,8000)}));
            throw error;
          }
        }
    """.trimIndent()
}
