package io.droyagent.app

import android.view.KeyEvent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.droyagent.app.nativeui.advanced.NativeAdvancedViewModel
import io.droyagent.app.nativeui.settings.NativeSettingsViewModel
import java.io.File
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Only isolated bundled-plugin configuration; no package install, model request or shell command. */
@RunWith(AndroidJUnit4::class)
class NativeApprovedPluginToggleDeviceTest {
    @get:Rule(order = 0) val compose = createEmptyComposeRule()
    @get:Rule(order = 1) val fixture = NativeIsolatedRuntimeFixture("settings")

    @Test fun allThreeApprovedBundlesTogglePersistAndRestoreThroughNativeControls() {
        lateinit var settings: NativeSettingsViewModel
        lateinit var advanced: NativeAdvancedViewModel
        fixture.scenario.onActivity {
            settings = ViewModelProvider(it)[NativeSettingsViewModel::class.java]
            advanced = ViewModelProvider(it)[NativeAdvancedViewModel::class.java]
        }
        compose.waitUntil(30_000) { settings.state.value.loaded && settings.state.value.runtime.ready }
        compose.onNodeWithTag("native-settings-plugins").performScrollTo().performClick()
        val bundles = mapOf("agent-team" to "@deepseek-ai/dsh-experimental-agent-team-profile",
            "session-titles" to "@deepseek-ai/dsh-experimental-session-titles-bundle",
            "persistent-terminal" to "@deepseek-ai/dsh-experimental-terminal-bundle")
        for ((id, name) in bundles) {
            compose.onNodeWithTag("native-settings-group:$id").performScrollTo().performClick()
            compose.waitUntil(20_000) { advanced.state.value.ready && !advanced.state.value.busy && advanced.state.value.approvedLoaded
                && advanced.state.value.approvedPlugins.any { it.id == id } }
            val before = advanced.state.value.approvedPlugins.single { it.id == id }
            assertTrue("Pinned bundle must be available: $id", before.available && before.writable)
            val control = compose.onNodeWithTag("native-approved-plugin-switch:$id")
            control.assertIsEnabled().performClick()
            compose.waitUntil(20_000) { !advanced.state.value.busy && advanced.state.value.approvedPlugins.single { it.id == id }.enabled != before.enabled }
            assertNull(advanced.state.value.error)
            assertEquals("The local pinned bundle must actually apply", "applied", advanced.state.value.receipt)
            val changed = projection(id)
            assertEquals(!before.enabled, changed.getBoolean("enabled"))
            val manifest = JSONObject(File(fixture.home, "profiles/mobile/package.json").readText())
            val selected = manifest.getJSONObject("dsh").getJSONObject("profile").getJSONArray("bundles")
            assertEquals(!before.enabled, (0 until selected.length()).any { selected.getString(it) == name })
            compose.waitForIdle(); compose.mainClock.advanceTimeBy(1_000); compose.waitForIdle()
            fixture.capture("round5-plugin-$id-enabled")
            control.performClick()
            compose.waitUntil(20_000) { !advanced.state.value.busy && advanced.state.value.approvedPlugins.single { it.id == id }.enabled == before.enabled }
            assertNull(advanced.state.value.error)
            assertEquals(before.enabled, projection(id).getBoolean("enabled"))
            InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            compose.waitForIdle()
        }
        assertFalse("Plugin configuration must not invoke a model", File(fixture.home, "native-fixture-calls.jsonl").exists())
    }
    private fun projection(id: String): JSONObject = runBlocking {
        val result = fixture.runtimeTransport.json("/droy/native/advanced/approved-plugins", "GET")
        assertTrue(result.toString(), result.getBoolean("ok"))
        val rows = result.getJSONObject("result").getJSONArray("plugins")
        (0 until rows.length()).map(rows::getJSONObject).single { it.getString("id") == id }
    }
}
