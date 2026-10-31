package io.droyagent.app

import android.os.SystemClock
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.droyagent.app.nativeui.settings.NativeSettingsViewModel
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Built-in artwork is exercised through the visible settings routes without a paid model. */
@RunWith(AndroidJUnit4::class)
class NativePetBuiltinsDeviceTest {
    @get:Rule(order = 0) val compose = createEmptyComposeRule()
    @get:Rule(order = 1) val fixture = NativeIsolatedRuntimeFixture("settings", allowRoot = false)
    private lateinit var settings: NativeSettingsViewModel
    private val host get() = NativePetStudioHost(fixture.context)
    private val ids = listOf("default", "8dd37d0d-b6ed-47c2-9952-8f3be20fa6d0", "cb5c4126-8c3b-41a5-a1bd-3d3b95cdeee2")
    private val actions = listOf(listOf("knead", "tailCurl", "sleep"), listOf("waveHands", "scream", "startle"),
        listOf("neckStretch", "spitForward", "spitScreen"))
    private var language = "en"

    @Test fun builtinsCanBeSelectedPreviewedAndPersistWithoutGeneration() {
        fixture.scenario.onActivity { settings = ViewModelProvider(it)[NativeSettingsViewModel::class.java] }
        compose.waitUntil(30_000) { settings.state.value.loaded && settings.state.value.writable && settings.state.value.runtime.ready }
        val preferences = fixture.context.getSharedPreferences("product", 0)
        assertTrue(preferences.edit().putBoolean("petEnabled", false).putString("petInternalDisplay", "off")
            .putString("petExternalDisplay", "off").putString("appearanceMode", "light").commit())
        val builtins = host.builtIns()
        assertEquals(3, builtins.length())
        assertEquals(ids, (0 until builtins.length()).map { builtins.getJSONObject(it).getString("id") })
        assertEquals("Droy", PetOverlay.name(fixture.context))

        for (locale in listOf("en", "zh")) {
            language = locale
            fixture.scenario.onActivity { settings.setLanguage(locale) }
            compose.waitUntil(20_000) { settings.state.value.locale == locale && NativeLocale.active() == locale && !settings.state.value.saving }
            assertNull(settings.state.value.error)
            compose.onNodeWithTag("native-settings-pet").performScrollTo().performClick()
            awaitTag("native-product-body:pet")
            for ((index, id) in ids.withIndex()) {
                scrollProduct("native-product-artwork:$id")
                compose.onNodeWithTag("native-product-artwork:$id").assertIsEnabled().performClick()
                compose.waitUntil(10_000) { host.selectedId() == id }
                awaitTag("native-product-body:pet")
                compose.onNodeWithTag("native-product-artwork:$id").assertIsSelected()
                capture("selected-$index")
            }
            // Leave a different selection so previewing the cat can prove it never applies artwork.
            scrollProduct("native-product-artwork:${ids[1]}")
            compose.onNodeWithTag("native-product-artwork:${ids[1]}").performClick()
            compose.waitUntil(10_000) { host.selectedId() == ids[1] }
            scrollProduct("native-product-pet-studio-open")
            compose.onNodeWithTag("native-product-pet-studio-open").performClick()
            awaitTag("native-pet-studio-builtin:default")
            capture("studio")
            for ((index, id) in ids.withIndex()) {
                val selectedBefore = host.selectedId()
                scrollStudio("native-pet-studio-preview:$id")
                compose.onNodeWithTag("native-pet-studio-preview:$id").performClick()
                awaitTag("native-pet-studio-action-picker:$id")
                assertEquals("Opening a preview applied it", selectedBefore, host.selectedId())
                for (action in actions[index]) {
                    scrollStudio("native-pet-studio-action-picker:$id")
                    compose.onNodeWithTag("native-pet-studio-action-picker:$id").performClick()
                    awaitTag("native-pet-studio-action:$action")
                    compose.onNodeWithTag("native-pet-studio-action:$action").performScrollTo().performClick()
                    scrollStudio("native-pet-studio-play")
                    compose.onNodeWithTag("native-pet-studio-play").performClick()
                    scrollStudio("native-pet-studio-stage:$id")
                    capture("preview-$index-$action")
                    assertEquals("Playing an action changed the selected artwork", selectedBefore, host.selectedId())
                }
                scrollStudio("native-pet-studio-apply:$id")
                compose.onNodeWithTag("native-pet-studio-apply:$id").assertIsEnabled().performClick()
                compose.waitUntil(10_000) { host.selectedId() == id }
                awaitTag("native-pet-studio-selected:$id")
                assertFalse("A built-in was copied into the custom-pet store", File(fixture.home, "pets/$id").exists())
            }
            compose.onNodeWithTag("native-pet-studio-back").performClick()
            awaitTag("native-product-body:pet")
            scrollProduct("native-product-artwork:${ids[2]}")
            compose.onNodeWithTag("native-product-artwork:${ids[2]}").assertIsSelected()
            compose.onNodeWithTag("native-product-back").performClick()
            awaitTag("native-settings-home-list")
        }
        fixture.recreate()
        assertEquals("The selected built-in did not survive Activity recreation", ids[2], host.selectedId())
        for (name in listOf("native-fixture-calls.jsonl", "native-fixture-companion.jsonl")) {
            val calls = File(fixture.home, name)
            assertTrue("Pet selection unexpectedly called a model: $name", !calls.exists() || calls.length() == 0L)
        }
    }

    private fun scrollProduct(tag: String) {
        compose.onNodeWithTag("native-product-list:pet").performScrollToNode(hasTestTag(tag))
        compose.onNodeWithTag(tag).assertIsDisplayed()
    }
    private fun scrollStudio(tag: String) {
        compose.onNodeWithTag("native-pet-studio-list").performScrollToNode(hasTestTag(tag))
        compose.onNodeWithTag(tag).assertIsDisplayed()
    }
    private fun awaitTag(tag: String) {
        compose.waitUntil(20_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes(false).isNotEmpty() }
        compose.waitForIdle()
    }
    private fun capture(scene: String) {
        compose.waitForIdle()
        SystemClock.sleep(250)
        fixture.capture("pets-$scene-$language")
    }
}
