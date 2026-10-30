package io.droyagent.app

import android.content.Intent
import android.content.res.Configuration
import android.os.SystemClock
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.droyagent.app.nativeui.companion.data.NativeCompanionViewModel
import io.droyagent.app.nativeui.data.NativeWorkbenchViewModel
import io.droyagent.app.nativeui.navigation.NativeExternalNavigationViewModel
import io.droyagent.app.nativeui.settings.NativeSettingsViewModel
import io.droyagent.app.nativeui.companion.creation.NativeCompanionCreationViewModel
import java.io.File
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.rules.TestWatcher
import org.junit.runner.Description

/** Review screenshots use fictional records in the same isolated, restorable development fixture. */
@RunWith(AndroidJUnit4::class)
class NativePageGalleryDeviceTest {
    @get:Rule(order = 0) val compose = createEmptyComposeRule()
    @get:Rule(order = 1) val fixture = NativeIsolatedRuntimeFixture()
    @get:Rule(order = 2) val failureEvidence = object : TestWatcher() {
        override fun failed(failure: Throwable, description: Description) {
            val name = "native-gallery-failed-${description.methodName}"
            try { fixture.capture(name) } catch (diagnostic: Throwable) { failure.addSuppressed(diagnostic) }
            try {
                File(fixture.context.getExternalFilesDir(null), "native-isolated-evidence/$name.txt")
                    .writeText(compose.onRoot(useUnmergedTree = true).printToString())
            } catch (diagnostic: Throwable) { failure.addSuppressed(diagnostic) }
        }
    }
    private lateinit var work: NativeWorkbenchViewModel
    private lateinit var companions: NativeCompanionViewModel
    private lateinit var settings: NativeSettingsViewModel
    private lateinit var navigation: NativeExternalNavigationViewModel

    @Test fun captureMainNativePagesForVisualReview() {
        val plugin = File(fixture.home, "native-gallery.mjs")
        plugin.writeText("""
            import { writeFile } from 'node:fs/promises';
            import { join } from 'node:path';
            export const name = 'native-isolated-gallery';
            export const inject = ['droyCompanions'];
            export async function apply(ctx) {
              const store = ctx.droyCompanions.store;
              const profile = (await store.profiles())[0];
              const messages = [
                ['user','早上好，咔咔。'],
                ['assistant','早上好！今天想先做点什么？'],
                ['user','先把今天的事情理一理。'],
                ['assistant','好呀，先挑最重要的一件，剩下的慢慢来。'],
                ['user','上午处理工作，晚上想留一点时间看书。'],
                ['assistant','那就把晚上留给自己。想读什么书？']
              ];
              for (let i = 0; i < messages.length; i++) await store.appendMessage(profile.id,
                {id:'gallery-message-'+i,role:messages[i][0],type:'text',text:messages[i][1],status:'sent',
                 createdAt:new Date(Date.now()-((messages.length-i)*60000)).toISOString()});
              await writeFile(join(process.env.DSH_HOME,'native-gallery-ready.json'),JSON.stringify({companionId:profile.id}));
            }
        """.trimIndent())
        val patch = File(fixture.home, "profiles/mobile/cordis.patch.yml")
        patch.writeText(JSONArray(patch.readText()).put(JSONObject().put("insert", JSONArray().put(JSONObject()
            .put("id", "native-isolated-gallery").put("name", plugin.absolutePath)))).toString())
        fixture.scenario.onActivity {
            val provider = ViewModelProvider(it)
            work = provider[NativeWorkbenchViewModel::class.java]
            companions = provider[NativeCompanionViewModel::class.java]
            settings = provider[NativeSettingsViewModel::class.java]
            navigation = provider[NativeExternalNavigationViewModel::class.java]
        }
        fixture.startRuntime()
        compose.waitUntil(30_000) { File(fixture.home, "native-gallery-ready.json").isFile && settings.state.value.loaded && work.state.value.workspaceResolved }
        fixture.scenario.onActivity { settings.setLanguage("zh") }
        compose.waitUntil(20_000) { settings.state.value.locale == "zh" && work.state.value.runtime.locale == "zh" && NativeLocale.active() == "zh" && !settings.state.value.saving }
        val owner = JSONObject(File(fixture.home, "native-gallery-ready.json").readText()).getString("companionId")
        fixture.scenario.onActivity { companions.refresh() }
        compose.waitUntil(20_000) { companions.state.value.companions.any { it.id == owner && it.lastMessage != null } }

        root("messages", "native-companion-messages")
        capture("gallery-01-messages")
        compose.onNodeWithTag("native-companion-conversation:$owner").performClick()
        waitTag("native-companion-chat")
        compose.waitUntil(15_000) { companions.state.value.messages.size == 6 && !companions.state.value.loading }
        capture("gallery-02-companion-chat")
        compose.onNodeWithTag("native-open-companion-profile").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText(text(R.string.native_companion_profile)).fetchSemanticsNodes(false).isNotEmpty() }
        capture("gallery-03-companion-profile")
        back(); back()

        root("workbench", "native-workbench")
        val workspace = requireNotNull(work.state.value.defaultWorkspaceId)
        for ((index, title) in listOf("今日工作计划", "阅读笔记", "图片整理").withIndex()) {
            val session = "gallery-work-$index"
            native("/droy/native/session/create", JSONObject().put("sessionId", session).put("workspaceId", workspace))
            native("/droy/native/workspaces/mutate", JSONObject().put("requestId", UUID.randomUUID().toString()).put("kind", "session.rename")
                .put("args", JSONObject().put("sessionId", session).put("title", title)))
        }
        fixture.scenario.onActivity { work.refresh() }
        compose.waitUntil(15_000) { work.state.value.sessions.size >= 3 }
        capture("gallery-04-workbench")
        compose.onNodeWithTag("native-space-switch").assertIsDisplayed().assertIsEnabled()
            .assert(hasContentDescription(text(R.string.native_spaces_chats))).performClick()
        waitTag("native-workspaces")
        compose.waitUntil(15_000) { compose.onAllNodesWithText("今日工作计划").fetchSemanticsNodes(false).isNotEmpty() }
        capture("gallery-05-spaces")
        back()

        root("settings", "native-settings")
        capture("gallery-06-settings")
        compose.onNodeWithTag("native-settings-models").performClick()
        waitTag("native-settings-providers")
        capture("gallery-07-models")
        back()

        root("messages", "native-companion-messages")
        compose.onNodeWithTag("native-add-companion").performClick()
        waitTag("native-creation-templates")
        val creation = arrayOfNulls<NativeCompanionCreationViewModel>(1)
        fixture.scenario.onActivity { creation[0] = ViewModelProvider(it)[NativeCompanionCreationViewModel::class.java] }
        compose.waitUntil(15_000) { creation[0]!!.state.value.templates.isNotEmpty() && !creation[0]!!.state.value.loading }
        val template = creation[0]!!.state.value.templates.first().id
        compose.onNodeWithTag("native-creation-template-$template").performClick()
        compose.waitUntil(15_000) { creation[0]!!.state.value.editor != null && !creation[0]!!.state.value.busy }
        capture("gallery-08-add-companion")
    }

    private fun root(destination: String, tag: String) {
        fixture.scenario.onActivity { it.startActivity(Intent(it, NativeAppActivity::class.java).putExtra("destination", destination)) }
        waitTag(tag)
        compose.waitUntil(15_000) { navigation.state.value.request == null }
    }
    private fun waitTag(tag: String) = compose.waitUntil(15_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes(false).isNotEmpty() }
    private fun back() { compose.onNodeWithContentDescription(text(R.string.native_companion_back)).performClick(); compose.waitForIdle() }
    private fun native(path: String, body: JSONObject): JSONObject = runBlocking {
        val value = fixture.runtimeTransport.json(path, "POST", body); assertTrue(value.getBoolean("ok")); value.getJSONObject("result")
    }
    private fun text(id: Int) = fixture.context.createConfigurationContext(Configuration(fixture.context.resources.configuration).apply { setLocale(Locale.CHINESE) }).getString(id)
    private fun capture(name: String) {
        compose.waitForIdle()
        // Let the current page's navigation motion settle before taking an unmodified screen image.
        SystemClock.sleep(400)
        fixture.capture(name)
    }
}
