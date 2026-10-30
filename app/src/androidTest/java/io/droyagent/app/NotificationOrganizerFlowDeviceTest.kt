package io.droyagent.app

import android.Manifest
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Bundle
import android.os.SystemClock
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.view.WindowInsets
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.droyagent.app.nativeui.settings.NativeSettingsViewModel
import java.io.File
import java.util.UUID
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.runner.RunWith

/** Real Android listener -> real Runtime endpoint -> local LlmAdapter -> native actions. */
@RunWith(AndroidJUnit4::class)
class NotificationOrganizerFlowDeviceTest {
    @get:Rule(order = 0) val compose = createEmptyComposeRule()
    private val notificationMemory = OrganizerNotificationMemory()
    @get:Rule(order = 1) val notificationMemoryGuard: ExternalResource = notificationMemory
    @get:Rule(order = 2) val fixture = NativeIsolatedRuntimeFixture("settings", allowRoot = false,
        providerAsset = "notification-organizer-provider.mjs", afterPreferencesRestored = notificationMemory::restore)
    private lateinit var flow: OrganizerTestHarness

    @Before fun prepare() { flow = OrganizerTestHarness(fixture).also { it.prepare() } }
    @After fun restore() { if (::flow.isInitialized) flow.close() }

    @Test fun classificationUsesTheRealPipelineAndKeepsUnsupportedActionsVisible() {
        flow.post(1001, "Organizer ordinary", noisy = true)
        flow.awaitReceipt("Organizer ordinary")
        val ordinary = requireNotNull(flow.source(1001))
        val ranking = NotificationListenerService.Ranking()
        assertTrue(flow.listener().currentRanking.getRanking(ordinary.key, ranking))
        assertEquals("Basic organization changed the original channel", NotificationManager.IMPORTANCE_HIGH, ranking.channel.importance)
        assertFalse(flow.management.state().getBoolean("silentTakeoverEnabled"))
        val originalBackground = flow.preferences.getBoolean("backgroundRuntimeEnabled", false)
        BackgroundRuntime.setEnabled(fixture.context, !originalBackground)
        assertTrue("Background choice changed notification organization", flow.management.state().getBoolean("enabled"))
        flow.configure(JSONObject().put("enabled", false))
        assertEquals("Organization changed the independent background choice", !originalBackground,
            flow.preferences.getBoolean("backgroundRuntimeEnabled", false))
        flow.configure(JSONObject().put("enabled", true))
        BackgroundRuntime.setEnabled(fixture.context, originalBackground)

        flow.post(1002, "Organizer clear")
        flow.await("Advertising removal needs a real Android removal receipt") {
            flow.receipt("Organizer clear")?.optLong("clearedAt", 0)?.let { it > 0 } == true && flow.source(1002) == null
        }
        assertEquals(1, flow.management.state().getInt("clearedAdsCount"))

        flow.post(1003, "Organizer verification code", text = "Your verification code is 246810.")
        flow.await("The fictional verification-code notification must reach Android") { flow.source(1003) != null }
        flow.awaitWorker()
        assertFalse("A verification code reached the model", flow.calls().any { it.optString("title") == "Organizer verification code" })
        assertNull(flow.receipt("Organizer verification code"))

        flow.management.request(JSONObject().put("operation", "setAppMode").put("packageName", flow.sourcePackage).put("mode", "allow"))
        flow.post(1010, "Organizer clear_unselected")
        flow.await("The unselected source must reach Android") { flow.source(1010) != null }
        flow.awaitWorker()
        assertFalse("An unselected application reached the model", flow.calls().any { it.optString("title") == "Organizer clear_unselected" })
        flow.management.request(JSONObject().put("operation", "setAppMode").put("packageName", flow.sourcePackage).put("mode", "managed"))
        flow.await("Selecting the source must enable organization of its current notification") { flow.source(1010) == null }

        flow.configure(JSONObject().put("ruleText", "Use each fictional marker to retain, clear, collect, notify, or mark important."))
        for ((id, action) in listOf(1004 to "collect", 1005 to "notify")) {
            val title = "Organizer ${action}_noisy"
            flow.post(id, title, noisy = true)
            val record = flow.awaitReceipt(title)
            assertEquals("retain", record.getString("action"))
            assertEquals(action, record.getString("requestedAction"))
            assertTrue("The unavailable replacement action was hidden", record.optString("limitationCode").isNotBlank())
            assertNotNull("An unsupported replacement removed its source", flow.source(id))
        }
        flow.post(1006, "Organizer collect_quiet")
        flow.await("A source already silent must be collected through the actual pipeline") {
            flow.receipt("Organizer collect_quiet")?.optString("action") == "collect" && flow.source(1006) == null && flow.managed(NotificationManagement.SUMMARY_ID) != null
        }
        flow.post(1007, "Organizer notify_quiet")
        flow.await("A source already silent must be replaced by an actual Droy alert") {
            flow.receipt("Organizer notify_quiet")?.let { it.optString("action") == "notify" && flow.managed(it.optInt("notificationId")) != null } == true && flow.source(1007) == null
        }

        flow.configure(JSONObject().put("enabled", false))
        flow.clearSources()
        flow.post(1008, "Organizer scan_A")
        flow.post(1009, "Organizer scan_B")
        flow.await("Both disabled source fixtures must exist before the scan") { flow.source(1008) != null && flow.source(1009) != null }
        val before = flow.calls().size
        File(fixture.home, "notification-organizer-control.json").writeText("{\"failNext\":true}")
        flow.configure(JSONObject().put("enabled", true))
        flow.await("One failed item must not stop the remaining source scan") {
            flow.calls().drop(before).let { calls -> calls.count { it.optString("title").startsWith("Organizer scan_") } >= 2 && calls.any { it.optBoolean("failed") } }
        }
        val scanned = flow.calls().drop(before).filter { it.optString("title").startsWith("Organizer scan_") }
        assertTrue("The first model call must reproduce a failure", scanned.first().getBoolean("failed"))
        assertTrue("No later source was classified after the failure", scanned.drop(1).any { !it.getBoolean("failed") })
        assertNotNull(flow.source(1008)); assertNotNull(flow.source(1009))
        assertTrue(flow.receipt("Organizer scan_A") != null || flow.receipt("Organizer scan_B") != null)
    }

    @Test fun summaryFailureAndHistoryCapacityCannotConsumeImportantReminders() {
        flow.configure(JSONObject().put("ruleText", "Collect fictional collect markers and retain important markers with one reminder."))
        flow.post(1101, "Organizer collect_summary")
        flow.await("The summary fixture must actually be published") { flow.managed(NotificationManagement.SUMMARY_ID) != null }
        flow.post(1102, "Organizer important_summary")
        val important = flow.awaitReceipt("Organizer important_summary")
        assertEquals(important.getLong("postedAtMs") + 5 * 60_000L, important.getLong("reminderAt"))
        val summary = requireNotNull(flow.manager.getNotificationChannel("managed_summary"))
        try {
            // A missing summary channel makes the actual publication fail without changing alert permission.
            flow.manager.deleteNotificationChannel("managed_summary")
            flow.maintain("Organizer important_summary", due = true)
            flow.await("Summary publication failure swallowed the independent important reminder") {
                flow.receipt("Organizer important_summary")?.optLong("remindedAt", 0)?.let { it > 0 } == true
            }
            val reminded = requireNotNull(flow.receipt("Organizer important_summary"))
            assertNotNull(flow.managed(reminded.getInt("notificationId")))
            assertNotNull("A reminder must keep its useful source", flow.source(1102))
            val first = reminded.getLong("remindedAt")
            flow.maintain()
            assertEquals("The same reminder was repeated", first, flow.receipt("Organizer important_summary")!!.getLong("remindedAt"))
            flow.cancel(1102)
            flow.await("Removing the source must clear its reminder") { flow.managed(reminded.getInt("notificationId")) == null }
        } finally { flow.manager.createNotificationChannel(summary) }

        flow.post(1103, "Organizer important_capacity")
        val pending = flow.awaitReceipt("Organizer important_capacity")
        flow.appendCompletedHistoryAndTrim(200)
        val kept = requireNotNull(flow.receipt("Organizer important_capacity"))
        assertEquals("Completed history displaced a pending reminder", pending.getLong("reminderAt"), kept.getLong("reminderAt"))
        flow.maintain("Organizer important_capacity", due = true)
        flow.await("The pending reminder disappeared at the history limit") { flow.receipt("Organizer important_capacity")?.optLong("remindedAt", 0)?.let { it > 0 } == true }

        flow.configure(JSONObject().put("reminderMinutes", 10))
        flow.post(1104, "Organizer important_ten")
        val ten = flow.awaitReceipt("Organizer important_ten")
        assertEquals(ten.getLong("postedAtMs") + 10 * 60_000L, ten.getLong("reminderAt"))
        flow.preferences.edit().putBoolean("toolsStopped", true).commit().also { assertTrue(it) }
        flow.maintain("Organizer important_ten", due = true)
        assertFalse("Pausing consumed a pending reminder", flow.receipt("Organizer important_ten")!!.has("reminderAttemptedAt"))
        flow.preferences.edit().putBoolean("toolsStopped", false).commit().also { assertTrue(it) }
        flow.maintain()
        flow.await("A resumed important reminder did not send") { flow.receipt("Organizer important_ten")?.optLong("remindedAt", 0)?.let { it > 0 } == true }
        val delivered = flow.receipt("Organizer important_ten")!!
        val remindedAt = delivered.getLong("remindedAt")
        val postedAt = requireNotNull(flow.managed(delivered.getInt("notificationId"))).postTime
        val priorHost = NativeRuntimeHost.runtimeIdentity()
        NativeRuntimeHost.stop(fixture.context)
        flow.await("The actual fixture Host did not stop", 30_000) { RuntimeService.canManageHistory() }
        NativeRuntimeHost.resumeNotificationManagement(fixture.context)
        fixture.startRuntime()
        assertNotEquals("The test must start a new Host process", priorHost, NativeRuntimeHost.runtimeIdentity())
        flow.management.rescan(); flow.awaitWorker(); flow.maintain()
        assertEquals("Restarting the Host repeated the reminder", remindedAt, flow.receipt("Organizer important_ten")!!.getLong("remindedAt"))
        assertEquals("Restarting the Host republished the reminder", postedAt,
            requireNotNull(flow.managed(delivered.getInt("notificationId"))).postTime)
    }

    @Test fun nativeSettingsSaveAndRecoverInBothLanguages() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        for (locale in listOf("zh", "en")) {
            openSettingsRoot()
            flow.configure(JSONObject().put("resetRule", true).put("silentTakeoverEnabled", false))
            flow.preferences.edit().putString("appearanceMode", if (locale == "zh") "light" else "dark").commit().also { assertTrue(it) }
            lateinit var settings: NativeSettingsViewModel
            fixture.scenario.onActivity {
                settings = ViewModelProvider(it)[NativeSettingsViewModel::class.java]
                settings.startObserving()
                settings.refresh()
            }
            compose.waitUntil(30_000) { settings.state.value.loaded && settings.state.value.writable && settings.state.value.runtime.ready && !settings.state.value.loading }
            assertNull(settings.state.value.error)
            compose.onNodeWithTag("native-settings-general").performScrollTo().performClick()
            compose.waitUntil(15_000) { compose.onAllNodesWithTag("native-product-body:general").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("native-settings-language").performScrollTo().performClick()
            compose.onNodeWithTag("native-settings-language:$locale").performClick()
            compose.waitUntil(20_000) { settings.state.value.locale == locale && NativeLocale.active() == locale && !settings.state.value.saving }
            compose.waitUntil(20_000) { compose.onAllNodesWithText(text(R.string.nps_general)).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("native-product-back").performClick()
            compose.waitUntil(15_000) { compose.onAllNodesWithTag("native-settings-general").fetchSemanticsNodes().isNotEmpty() }
            openNotifications()
            compose.onNodeWithTag("native-notification-status").assertExists()
            compose.onNodeWithTag("native-notification-model-status").assertExists()
            capture("organizer-$locale-overview")
            scroll("native-notification-reminder:10").performClick()
            flow.await("The ten-minute choice was not saved") { flow.management.state().getInt("reminderMinutes") == 10 }
            fixture.recreate(); openNotifications()
            scroll("native-notification-reminder:10").assertIsSelected()
            capture("organizer-$locale-ten-minutes")
            scroll("native-notification-reminder:5").performClick()
            flow.await("The five-minute choice was not saved") { flow.management.state().getInt("reminderMinutes") == 5 }

            scroll("native-product-field:nps_app_management").performClick()
            scroll("native-notification-app-search").performClick()
            scroll("native-notification-app-search").performTextReplacement(flow.sourcePackage)
            compose.waitUntil(10_000) { keyboardVisible() }
            scroll("native-notification-app:${flow.sourcePackage}").assertExists()
            capture("organizer-$locale-app-selection")
            instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
            compose.waitUntil(10_000) { !keyboardVisible() }
            compose.onNodeWithTag("native-notification-app-search").assertExists()
            scroll("native-notification-app:${flow.sourcePackage}").performClick()
            flow.await("The real app selection was not saved") { flow.management.state().getInt("selectedAppCount") == 0 }
            compose.onNodeWithTag("native-product-back").performClick()
            compose.waitUntil(15_000) { compose.onAllNodesWithTag("native-notification-status-action:apps").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("native-notification-status-action:apps").assertExists()
            scroll("native-notification-status")
            capture("organizer-$locale-no-app-selected")
            scroll("native-product-field:nps_app_management").performClick()
            scroll("native-notification-app:${flow.sourcePackage}").performClick()
            flow.await("The app could not be selected again") { flow.management.state().getInt("selectedAppCount") == 1 }
            hideKeyboard()
            instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
            compose.waitUntil(15_000) { compose.onAllNodesWithTag("native-product-field:nps_rules").fetchSemanticsNodes().isNotEmpty() }

            scroll("native-product-field:nps_rules").performClick()
            val rule = "Keep fictional delivery notices for the $locale settings check."
            compose.onNode(hasSetTextAction()).performTextReplacement(rule)
            compose.onNode(hasSetTextAction()).assertTextContains(rule)
            capture("organizer-$locale-rule-editor")
            hideKeyboard()
            compose.onNodeWithText(text(R.string.nps_save)).performClick()
            flow.await("The actual rule editor did not save") { flow.management.state().getString("ruleText") == rule }
            compose.waitUntil(15_000) { compose.onAllNodesWithText(text(R.string.nps_save)).fetchSemanticsNodes().isEmpty() }
            scroll("native-product-field:nps_rules").performClick()
            compose.onNode(hasSetTextAction()).performTextReplacement("Uncommitted fictional draft")
            hideKeyboard()
            instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
            compose.onNodeWithText(text(R.string.nps_discard)).performClick()
            compose.waitUntil(15_000) { compose.onAllNodesWithText(text(R.string.nps_save)).fetchSemanticsNodes().isEmpty() }
            assertEquals(rule, flow.management.state().getString("ruleText"))

            scroll("native-product-switch:nps_silent_takeover").performClick()
            compose.waitUntil(10_000) {
                compose.onAllNodesWithTag("native-notification-takeover-cancel").fetchSemanticsNodes().isNotEmpty() &&
                    compose.onAllNodesWithTag("native-notification-takeover-confirm").fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithTag("native-notification-takeover-cancel").assertIsDisplayed()
            compose.onNodeWithTag("native-notification-takeover-confirm").assertIsDisplayed()
            capture("organizer-$locale-takeover-disclosure")
            compose.onNodeWithTag("native-notification-takeover-cancel").performClick()
            assertFalse("Cancel unexpectedly enabled silent takeover", flow.management.state().getBoolean("silentTakeoverEnabled"))
            scroll("native-product-switch:nps_silent_takeover").performClick()
            compose.onNodeWithTag("native-notification-takeover-confirm").performClick()
            flow.await("The actual takeover choice was not saved") { flow.management.state().getBoolean("silentTakeoverEnabled") }
            flow.await("Missing Root was not shown as limited capability") { flow.management.state().getJSONObject("processing").getString("state") == "limited" }
            scroll("native-notification-status")
            capture("organizer-$locale-limited-takeover")
            scroll("native-product-switch:nps_silent_takeover").performClick()
            flow.await("Silent takeover could not be disabled") { !flow.management.state().getBoolean("silentTakeoverEnabled") }

            scroll("native-product-field:nps_archive").performClick()
            compose.onNodeWithText(text(R.string.nps_no_notification_history)).assertExists()
            capture("organizer-$locale-empty-history")
            compose.onNodeWithTag("native-product-back").performClick()
            compose.onNodeWithTag("native-product-switch:nps_enabled").assertExists()
        }
    }

    @Test fun visibleReadyPageShowsAsynchronousFailureWithoutManualRefresh() {
        openNotifications()
        compose.waitUntil(20_000) {
            compose.onAllNodesWithTag("native-notification-status-title").fetchSemanticsNodes().any { node ->
                node.config.getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text } == text(R.string.nps_organization_ready)
            }
        }
        flow.post(1301, "Organizer fail_ui", noisy = true)
        flow.await("The local model must reproduce the notification failure") {
            JSONObject(flow.preferences.getString("notificationManagementData", "{}")!!)
                .optJSONObject("lastResult")?.optBoolean("success", true) == false
        }
        val failure = flow.management.state().getJSONObject("lastResult")
        val message = failure.getString("message")
        compose.waitUntil(20_000) {
            compose.onAllNodesWithTag("native-notification-result-message").fetchSemanticsNodes().any { node ->
                node.config.getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text } == message
            }
        }
        compose.waitUntil(20_000) {
            compose.onAllNodesWithTag("native-notification-status-title").fetchSemanticsNodes().any { node ->
                node.config.getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text } == text(R.string.nps_organization_limited)
            }
        }
        compose.onNodeWithTag("native-notification-status-title").assertTextEquals(text(R.string.nps_organization_limited))
        compose.onNodeWithTag("native-notification-last-result").assertExists()
        assertNotNull("A failed model call removed the original notification", flow.source(1301))
        val count = flow.calls().count { it.optString("title") == "Organizer fail_ui" }
        assertEquals("The fictional failure must make one model request", 1, count)
        val until = SystemClock.elapsedRealtime() + 2_200
        while (SystemClock.elapsedRealtime() < until) {
            assertEquals("Refreshing the visible state retried the model request", count,
                flow.calls().count { it.optString("title") == "Organizer fail_ui" })
            SystemClock.sleep(100)
        }
        scroll("native-notification-result-message")
        capture("organizer-${NativeLocale.active()}-live-model-failure")
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        InstrumentationRegistry.getInstrumentation().uiAutomation.waitForIdle(350, 10_000)
        fixture.capture(name)
    }
    private fun openSettingsRoot() {
        fixture.scenario.onActivity { it.startActivity(Intent(it, NativeAppActivity::class.java)
            .putExtra("destination", "settings").addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)) }
        compose.waitUntil(15_000) { compose.onAllNodesWithTag("native-settings-general").fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
    }
    private fun openNotifications() {
        openSettingsRoot()
        compose.onNodeWithTag("native-settings-notifications").performScrollTo().performClick()
        compose.waitUntil(15_000) { compose.onAllNodesWithTag("native-product-body:notifications").fetchSemanticsNodes().isNotEmpty() }
        compose.waitUntil(20_000) {
            compose.onAllNodesWithText(text(R.string.native_settings_notifications)).fetchSemanticsNodes().isNotEmpty() &&
                compose.onAllNodesWithText(text(R.string.nps_enabled)).fetchSemanticsNodes().isNotEmpty()
        }
        compose.waitUntil(20_000) {
            val expected = flow.management.state().getJSONObject("processing").getString("message")
            compose.onAllNodesWithTag("native-notification-status-message").fetchSemanticsNodes().any { node ->
                node.config.getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text } == expected
            }
        }
        compose.waitForIdle()
    }
    private fun keyboardVisible(): Boolean {
        var visible = false
        fixture.scenario.onActivity { visible = it.window.decorView.rootWindowInsets?.isVisible(WindowInsets.Type.ime()) == true }
        return visible
    }
    private fun hideKeyboard() {
        compose.waitForIdle()
        if (keyboardVisible()) {
            InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
            compose.waitUntil(10_000) { !keyboardVisible() }
        }
    }
    private fun scroll(tag: String): SemanticsNodeInteraction {
        compose.waitUntil(15_000) { compose.onAllNodesWithTag("native-product-body:notifications").fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
        compose.onNodeWithTag("native-product-list:notifications").performScrollToNode(hasTestTag(tag))
        return compose.onNodeWithTag(tag)
    }
    private fun text(id: Int): String = fixture.context.createConfigurationContext(
        Configuration(fixture.context.resources.configuration).apply { setLocale(Locale.forLanguageTag(NativeLocale.active())) }
    ).getString(id)
}

/** Opt-in wall-clock coverage; run separately so ordinary focused runs have no conditional skip. */
@RunWith(AndroidJUnit4::class)
class NotificationOrganizerRealWaitDeviceTest {
    private val notificationMemory = OrganizerNotificationMemory()
    @get:Rule(order = 0) val notificationMemoryGuard: ExternalResource = notificationMemory
    @get:Rule(order = 1) val fixture = NativeIsolatedRuntimeFixture("settings", allowRoot = false,
        providerAsset = "notification-organizer-provider.mjs", afterPreferencesRestored = notificationMemory::restore)
    private lateinit var flow: OrganizerTestHarness
    @Before fun prepare() {
        assumeTrue("Explicit real notification wait required", InstrumentationRegistry.getArguments().getString("notificationOrganizerRealWait") == "true")
        flow = OrganizerTestHarness(fixture).also { it.prepare() }
    }
    @After fun restore() { if (::flow.isInitialized) flow.close() }
    @Test fun retainedImportantNotificationRemindsAfterTheActualConfiguredWait() {
        val minutes = InstrumentationRegistry.getArguments().getString("notificationOrganizerMinutes", "5").toInt()
        require(minutes == 5 || minutes == 10)
        flow.configure(JSONObject().put("reminderMinutes", minutes).put("ruleText", "Keep fictional important notices and remind once at the configured interval."))
        flow.post(1201, "Organizer important_real_wait")
        val pending = flow.awaitReceipt("Organizer important_real_wait")
        assertEquals(pending.getLong("postedAtMs") + minutes * 60_000L, pending.getLong("reminderAt"))
        assertFalse(pending.has("reminderAttemptedAt"))
        flow.await("The real wall-clock reminder did not arrive", (minutes + 1) * 60_000L) {
            flow.receipt("Organizer important_real_wait")?.optLong("remindedAt", 0)?.let { it > 0 } == true
        }
        val done = flow.receipt("Organizer important_real_wait")!!
        assertTrue("The reminder fired before its actual configured deadline", done.getLong("remindedAt") >= pending.getLong("postedAtMs") + minutes * 60_000L)
        assertEquals("notification", done.getString("reminderDelivery"))
        assertNotNull(flow.source(1201)); assertNotNull(flow.managed(done.getInt("notificationId")))
        val report = Bundle().apply { putString("stream", "\nDROY_ORGANIZER_REAL_WAIT_MINUTES=$minutes actualMs=${done.getLong("remindedAt") - done.getLong("postedAtMs")}\n") }
        InstrumentationRegistry.getInstrumentation().sendStatus(2, report)
        flow.cancel(1201)
        flow.await("Clearing the source did not cancel the delivered reminder") { flow.managed(done.getInt("notificationId")) == null }
    }
}

internal class OrganizerNotificationMemory : ExternalResource() {
    private var original: String? = null
    override fun before() {
        original = InstrumentationRegistry.getInstrumentation().targetContext.getSharedPreferences("product", 0)
            .getString("notificationManagementData", null)
    }
    fun restore() = NotificationTestState.reloadNotificationMemory(InstrumentationRegistry.getInstrumentation().targetContext, original)
}

internal class OrganizerTestHarness(private val fixture: NativeIsolatedRuntimeFixture) {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = fixture.context
    val sourcePackage = instrumentation.context.packageName
    private val receiver = "$sourcePackage/io.droyagent.app.NotificationManagementDeviceTest\$FixtureReceiver"
    private val suffix = UUID.randomUUID().toString().replace("-", "").take(12)
    val preferences get() = context.getSharedPreferences("product", 0)
    val management get() = NotificationManagement.get(context)
    val manager get() = context.getSystemService(NotificationManager::class.java)
    private var listenerGranted = false

    fun prepare() {
        assertEquals("Pregrant development notification permission; restore it after fixture cleanup", PackageManager.PERMISSION_GRANTED,
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS))
        assertEquals("Pregrant the independent source notification permission", PackageManager.PERMISSION_GRANTED,
            context.packageManager.checkPermission(Manifest.permission.POST_NOTIFICATIONS, sourcePackage))
        listenerGranted = DroyNotificationService.hasAccess(context)
        assertTrue(preferences.edit().putBoolean("notificationManagementEnabled", false).putBoolean("toolsStopped", false).putBoolean("allowNotifications", true)
            .putBoolean("backgroundRuntimeStopped", false).putBoolean("petEnabled", false).commit())
        NotificationTestState.reloadNotificationMemory(context, JSONObject().put("config", NotificationManagementRules.configuration(null))
            .put("records", JSONArray()).toString())
        configure(JSONObject().put("enabled", false).put("resetRule", true).put("silentTakeoverEnabled", false))
        awaitWorker()
        val records = NotificationManagement::class.java.getDeclaredField("records").apply { isAccessible = true }
        synchronized(management) { records.set(management, JSONArray()) }
        val apps = management.state().getJSONArray("apps")
        val names = JSONArray((0 until apps.length()).map { apps.getJSONObject(it).getString("packageName") })
        management.request(JSONObject().put("operation", "setApps").put("enabled", false).put("packageNames", names))
        assertTrue("The independent source must be manageable", (0 until apps.length()).any { apps.getJSONObject(it).getString("packageName") == sourcePackage })
        management.request(JSONObject().put("operation", "setAppMode").put("packageName", sourcePackage).put("mode", "managed"))
        shell("cmd notification allow_listener ${ComponentName(context, DroyNotificationService::class.java).flattenToString()}")
        await("The real Android listener must connect", 20_000) { DroyNotificationService.status(context).optBoolean("connected") }
        configure(JSONObject().put("enabled", true))
        await("The selected local adapter must be visible to the real Runtime", 20_000) { management.state().optJSONObject("model")?.optBoolean("configured") == true }
    }
    fun close() {
        try {
            configure(JSONObject().put("enabled", false).put("silentTakeoverEnabled", false))
            awaitWorker(); clearSources(); awaitWorker()
        } finally {
            if (!listenerGranted) {
                shell("cmd notification disallow_listener ${ComponentName(context, DroyNotificationService::class.java).flattenToString()}")
                await("Restore the original notification-listener grant", 10_000) {
                    !DroyNotificationService.hasAccess(context) && !DroyNotificationService.connected()
                }
                awaitWorker()
            }
        }
    }
    fun configure(options: JSONObject): JSONObject = management.request(JSONObject(options.toString()).put("operation", "configure"))
    fun post(id: Int, title: String, noisy: Boolean = false, text: String = "Fictional notification content") {
        val result = shell("am broadcast --receiver-foreground --include-stopped-packages -n $receiver --es suffix $suffix --ei id $id --es kind organizer --es titleBase64 ${encoded(title)} --es textBase64 ${encoded(text)} --ez noisy $noisy")
        assertTrue("The independent UID did not post its source: $result", result.contains("DROY_FIXTURE_POSTED"))
    }
    fun cancel(id: Int) { shell("am broadcast --receiver-foreground --include-stopped-packages -n $receiver --es suffix $suffix --ei id $id --ez cancel true") }
    fun clearSources() { shell("am broadcast --receiver-foreground --include-stopped-packages -n $receiver --es suffix $suffix --ez clear true") }
    fun listener(): DroyNotificationService = DroyNotificationService::class.java.getDeclaredField("connected").apply { isAccessible = true }.get(null) as DroyNotificationService
    fun source(id: Int): StatusBarNotification? = listener().activeNotifications.firstOrNull { it.packageName == sourcePackage && it.tag == "droy-notification-fixture" && it.id == id }
    fun managed(id: Int): StatusBarNotification? = manager.activeNotifications.firstOrNull { it.tag == NotificationManagement.TAG && it.id == id }
    fun receipt(title: String): JSONObject? {
        val values = JSONObject(preferences.getString("notificationManagementData", "{}")!!).optJSONArray("records") ?: return null
        return (0 until values.length()).map { values.getJSONObject(it) }.lastOrNull { it.optString("packageName") == sourcePackage && it.optString("title") == title }
    }
    fun awaitReceipt(title: String): JSONObject {
        await("The actual notification pipeline did not apply $title") { receipt(title)?.optBoolean("applied") == true }
        return requireNotNull(receipt(title))
    }
    fun calls(): List<JSONObject> = File(fixture.home, "notification-organizer-calls.jsonl").takeIf { it.isFile }
        ?.readLines()?.filter { it.isNotBlank() }?.map(::JSONObject).orEmpty()
    private fun worker(): ExecutorService = NotificationManagement::class.java.getDeclaredField("worker").apply { isAccessible = true }.get(management) as ExecutorService
    fun awaitWorker() { worker().submit {}.get(90, TimeUnit.SECONDS) }
    fun maintain(title: String? = null, due: Boolean = false) {
        worker().submit {
            if (due) synchronized(management) {
                val values = NotificationManagement::class.java.getDeclaredField("records").apply { isAccessible = true }.get(management) as JSONArray
                for (i in 0 until values.length()) if (values.getJSONObject(i).optString("title") == title)
                    values.getJSONObject(i).put("reminderAt", System.currentTimeMillis() - 1)
            }
            NotificationManagement::class.java.getDeclaredMethod("maintain").apply { isAccessible = true }.invoke(management)
        }.get(30, TimeUnit.SECONDS)
    }
    fun appendCompletedHistoryAndTrim(count: Int) {
        worker().submit {
            synchronized(management) {
                val field = NotificationManagement::class.java.getDeclaredField("records").apply { isAccessible = true }
                val values = field.get(management) as JSONArray
                val revision = management.state(false).getLong("revision")
                repeat(count) { i -> values.put(JSONObject().put("key", "fictional-completed-$i").put("fingerprint", "fictional-$i")
                    .put("packageName", sourcePackage).put("title", "Fictional completed history $i").put("action", "clear")
                    .put("revision", revision).put("applied", true).put("dismissed", true)
                    .put("savedAtMs", System.currentTimeMillis()).put("postedAtMs", System.currentTimeMillis()).put("reminderAt", 0)) }
                NotificationManagement::class.java.getDeclaredMethod("trim").apply { isAccessible = true }.invoke(management)
                NotificationManagement::class.java.getDeclaredMethod("save").apply { isAccessible = true }.invoke(management)
            }
        }.get(15, TimeUnit.SECONDS)
    }
    fun await(message: String, timeoutMs: Long = 20_000, predicate: () -> Boolean) {
        val until = SystemClock.elapsedRealtime() + timeoutMs
        while (!predicate() && SystemClock.elapsedRealtime() < until) SystemClock.sleep(100)
        assertTrue(message, predicate())
    }
    private fun shell(command: String) = NotificationTestState.shell(instrumentation, command)
    private fun encoded(value: String) = "b64:" + android.util.Base64.encodeToString(value.toByteArray(Charsets.UTF_8),
        android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING)
}
