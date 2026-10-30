package io.droyagent.app

import android.content.Context
import android.net.Uri
import androidx.activity.compose.setContent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.droyagent.app.nativeui.settings.NativeBrowserNetworkSettingsScreen
import io.droyagent.app.nativeui.ui.DroyTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Local preferences and UI only; never resolves a domain or sends a DNS request. */
@RunWith(AndroidJUnit4::class)
class NativeBrowserNetworkSettingsDeviceTest {
    @get:Rule(order = 0) val compose = createEmptyComposeRule()
    @get:Rule(order = 1) val fixture = NativeIsolatedRuntimeFixture()

    @Test fun existingDnsChoiceIsVisibleAndWritesTheBrowserPolicy() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val preferences = context.getSharedPreferences("product", Context.MODE_PRIVATE)
        val key = "browserEncryptedDns"
        val existed = preferences.contains(key)
        val previous = preferences.getBoolean(key, false)
        val host = NativeProductSettingsHost(context)
        try {
            assertTrue(preferences.edit().remove(key).commit())
            assertFalse(host.snapshot("browser-network").getBoolean(key))
            assertTrue(preferences.edit().putBoolean(key, true).commit())
            fixture.scenario.onActivity { activity ->
                activity.setContent { DroyTheme { NativeBrowserNetworkSettingsScreen(onBack = {}) } }
            }
            compose.waitUntil(10_000) {
                compose.onAllNodesWithTag("native-settings-browser-encrypted-dns").filter(isEnabled()).fetchSemanticsNodes().size == 1
            }
            compose.onNodeWithTag("native-settings-browser-encrypted-dns").assertIsOn().performClick()
            compose.waitUntil(10_000) { !preferences.getBoolean(key, true) }
            compose.onNodeWithTag("native-settings-browser-encrypted-dns").assertIsOff()
            assertFalse(host.snapshot("browser-network").getBoolean(key))
            val policy = context.contentResolver.call(Uri.parse("content://${context.packageName}.browser-ui"), "dns-policy", null, null)
            assertNotNull(policy)
            assertFalse(requireNotNull(policy).getBoolean("encryptedDns"))
            fixture.capture("native-browser-network")
        } finally {
            val editor = preferences.edit()
            if (existed) editor.putBoolean(key, previous) else editor.remove(key)
            assertTrue(editor.commit())
        }
    }
}
