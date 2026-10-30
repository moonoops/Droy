package io.droyagent.app

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Notification
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ShortcutManager
import android.content.res.Configuration
import android.graphics.Rect
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.service.notification.StatusBarNotification
import android.view.InputDevice
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.core.app.NotificationCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import io.droyagent.app.nativeui.companion.data.NativeCompanionViewModel
import io.droyagent.app.nativeui.settings.NativeSettingsViewModel
import io.droyagent.app.nativeui.navigation.NativeExternalNavigationTarget
import io.droyagent.app.nativeui.navigation.NativeExternalNavigationViewModel
import io.droyagent.app.nativeui.data.NativeApiException
import java.io.File
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.UUID
import java.util.Locale
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.json.JSONArray
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.runner.RunWith

/** Actual shade rendering and local-model delivery, with the existing restorable native fixture. */
@RunWith(AndroidJUnit4::class)
class NativeNotificationPresentationDeviceTest {
    @get:Rule(order = 0) val compose = createEmptyComposeRule()
    @get:Rule(order = 1) val notificationInstance = object : ExternalResource() {
        private val field = ProactiveNotifications::class.java.getDeclaredField("instance").apply { isAccessible = true }
        private var original: Any? = null
        private var owned = false
        override fun before() {
            assertTrue("Do not replace notification memory while an existing engine is running", RuntimeService.canManageHistory())
            assertFalse("Recover the prior fixture before replacing notification memory",
                File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir, "native-workbench-fixture.json").exists())
            original = field.get(null)
            field.set(null, null)
            owned = true
        }
        override fun after() {
            if (!owned) return
            assertFalse("Restore notification memory only after the isolated home and preferences are restored",
                File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir, "native-workbench-fixture.json").exists())
            field.set(null, original)
        }
    }
    @get:Rule(order = 2) val fixture = NativeIsolatedRuntimeFixture("settings", allowRoot = false)
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val automation get() = instrumentation.uiAutomation
    private val context get() = fixture.context
    private val manager get() = context.getSystemService(NotificationManager::class.java)
    private val createdCompanions = mutableSetOf<String>()
    private val shortcutIds = mutableSetOf<String>()
    private lateinit var companions: NativeCompanionViewModel
    private lateinit var settings: NativeSettingsViewModel
    private lateinit var externalNavigation: NativeExternalNavigationViewModel
    private lateinit var fixtureLaunchIntent: Intent
    private var language = "en"
    private var originalAccessibilityFlags: Int? = null

    @Before fun prepareNotifications() {
        assertEquals("Grant development notification permission before the isolated run; restore it after fixture cleanup",
            PackageManager.PERMISSION_GRANTED, context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS))
        assertTrue("System notifications must be enabled for the development app", manager.areNotificationsEnabled())
        for (posted in manager.activeNotifications) assertFalse("Do not cancel existing user message notifications",
            posted.tag?.startsWith(ProactiveNotifications.COMPANION_TAG) == true)
        val service = automation.serviceInfo
        originalAccessibilityFlags = service.flags
        service.flags = service.flags or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        automation.serviceInfo = service
        fixture.scenario.onActivity {
            fixtureLaunchIntent = Intent(it.intent)
            val models = ViewModelProvider(it)
            companions = models[NativeCompanionViewModel::class.java]
            settings = models[NativeSettingsViewModel::class.java]
            externalNavigation = models[NativeExternalNavigationViewModel::class.java]
        }
        compose.waitUntil(30_000) { settings.state.value.loaded && settings.state.value.writable && settings.state.value.runtime.ready }
        ProactiveNotifications.get(context).request(JSONObject().put("operation", "activate").put("enabled", true))
        ProactiveNotifications.get(context).request(JSONObject().put("operation", "configure").put("messagesEnabled", true).put("tasksEnabled", false))
        assertTrue("The normal message channel must be enabled without changing its existing choices",
            manager.getNotificationChannel(ProactiveNotifications.MESSAGE_CHANNEL).importance > NotificationManager.IMPORTANCE_NONE)
        BackgroundRuntime.setEnabled(context, true)
    }

    @After fun clearOwnedNotifications() {
        shell("cmd statusbar collapse")
        context.startService(Intent(context, RuntimeService::class.java).setAction(RuntimeService.STOP))
        await(30_000, "Stop the isolated engine before clearing its final notifications") { RuntimeService.canManageHistory() }
        for (id in createdCompanions) ProactiveNotifications.get(context).clearCompanion(id)
        if (shortcutIds.isNotEmpty()) context.getSystemService(ShortcutManager::class.java).removeDynamicShortcuts(shortcutIds.toList())
        // Shell/task launches may leave a paused Native Activity behind the latest surface.
        val ownedActivities = mutableSetOf<NativeAppActivity>()
        instrumentation.runOnMainSync {
            val monitor = ActivityLifecycleMonitorRegistry.getInstance()
            Stage.values().filter { it != Stage.DESTROYED }.forEach { stage ->
                ownedActivities += monitor.getActivitiesInStage(stage).filterIsInstance<NativeAppActivity>()
            }
            ownedActivities.forEach {
                // ActivityScenario ignores lifecycle callbacks after a deep link changes getIntent().
                if (::fixtureLaunchIntent.isInitialized) it.intent = Intent(fixtureLaunchIntent)
                it.finishAndRemoveTask()
            }
        }
        await(20_000, "Destroy all fixture Native Activities before the scenario restores its home") { ownedActivities.all { it.isDestroyed } }
        originalAccessibilityFlags?.let { flags -> automation.serviceInfo = automation.serviceInfo.apply { this.flags = flags } }
    }

    @Test fun notificationsRenderAndKeepUnreadStateWhenTheShadeCoversChat() = withFailureEvidence {
        for (locale in listOf("zh", "en")) {
            language = locale
            openRoot("settings")
            fixture.scenario.onActivity { settings.setLanguage(locale) }
            compose.waitUntil(20_000) { settings.state.value.locale == locale && NativeLocale.active() == locale && !settings.state.value.saving }
            assertNull(settings.state.value.error)
            assertTrue(context.getSharedPreferences("product", 0).edit().putString("appearanceMode", if (locale == "zh") "light" else "dark").commit())
            inspectPersistentStatus()
            inspectNotificationSettings()

            val first = createCompanion("Notice $locale Alpha")
            val second = createCompanion("Notice $locale Beta")
            openChat(first)
            await(10_000, "The latest chat must be visibly readable before suppression") { NativeRuntimeHost.isCompanionVisible(first) }
            reply(first, "VISIBLE_$locale")
            await(15_000, "A visible reply must advance the actual read cursor") { unread(first) == 0 }
            assertNull("Reading the actual chat must not leave a system message notification", find(first))

            shell("cmd statusbar expand-notifications")
            await(10_000, "The shade must remove app window focus") { !windowFocused() }
            fixture.scenario.onActivity {
                assertTrue("The fixture reproduces shade focus loss without an Activity pause", it.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
            }
            await(10_000, "A covered chat must not suppress its incoming notification") { !NativeRuntimeHost.isCompanionVisible(first) }
            reply(first, "SHADE_$locale")
            val shadeNotice = posted(first)
            assertConversation(shadeNotice.notification, "Notice $locale Alpha", 1)
            assertShadeRetainsUnread(first)
            await(10_000, "A message arriving in the already open shade must become visible") {
                systemText("Notice $locale Alpha") != null
            }
            assertShadeRetainsUnread(first)
            capture("notification-$locale-shade-chat-covered")
            assertShadeRetainsUnread(first)
            shell("cmd statusbar collapse")
            await(15_000, "Returning to the visible latest chat clears only its read notification") { unread(first) == 0 && find(first) == null }

            openRoot("settings")
            shell("input keyevent KEYCODE_HOME")
            await(10_000, "Home must put the native app in the background before the incoming reply") { !NativeRuntimeHost.isVisible() }
            reply(first, "FIRST_UNREAD_$locale")
            assertTrue("Continuous background mode keeps the real runtime ready", RuntimeService.ready)
            assertPersistentCount()
            assertConversation(posted(first).notification, "Notice $locale Alpha", 1)
            shell("cmd statusbar expand-notifications")
            await(10_000, "A message received while the app is backgrounded appears in the shade") { systemText("Notice $locale Alpha") != null }
            capture("notification-$locale-background-message")
            shell("cmd statusbar collapse")
            // The shell represents an explicit foreground launch; a background Activity cannot bypass BAL.
            shell("am start -f 0x24000000 -n io.droyagent.app.dev/io.droyagent.app.NativeAppActivity --es destination settings")
            awaitTag("native-settings-home-list")
            val oldReadAction = markRead(posted(first).notification)
            reply(second, "OTHER_COMPANION_$locale")
            assertConversation(posted(second).notification, "Notice $locale Beta", 1)
            val latest = reply(first, "SECOND_UNREAD_$locale")
            val combined = posted(first).notification
            assertConversation(combined, "Notice $locale Alpha", 2)
            assertEquals("Each companion keeps one notification", 1, manager.activeNotifications.count { it.tag == ProactiveNotifications.companionTag(first) })
            val beforeReplay = posted(first).postTime
            val duplicate = ProactiveNotifications.get(context).request(JSONObject().put("operation", "companion")
                .put("companionId", first).put("name", "Notice $locale Alpha").put("messageId", latest.getString("id"))
                .put("text", latest.getString("text")).put("sequence", latest.getLong("fixtureSequence"))
                .put("sentAt", System.currentTimeMillis()).put("quiet", false).put("alert", true).put("unreadCount", 2))
            assertTrue("Replaying a committed identity must be deduplicated", duplicate.getBoolean("duplicate"))
            assertEquals("Duplicate delivery must not re-post the system notification", beforeReplay, posted(first).postTime)
            inspectNotificationLocaleRefresh(first, "Notice $locale Alpha", 2, second)

            oldReadAction.actionIntent.send()
            await(15_000, "An old read action must leave the later unread reply") { unread(first) == 1 && find(first)?.notification?.let { messaging(it).messages.size == 1 } == true }
            assertNotNull("Reading one companion must retain the other", find(second))
            val beforeRepeatedRead = posted(first).postTime
            oldReadAction.actionIntent.send()
            await(15_000, "A stale repeated read retains the later unread reply") { unread(first) == 1 }
            compose.waitForIdle()
            SystemClock.sleep(400)
            assertEquals("A stale repeated action cannot advance to a new message", 1, unread(first))
            assertEquals("A stale repeated read cannot re-post an unchanged message notification", beforeRepeatedRead, posted(first).postTime)
            markRead(posted(first).notification).actionIntent.send()
            await(15_000, "The current read action must update the actual runtime read cursor") { unread(first) == 0 && find(first) == null }

            shell("cmd statusbar expand-notifications")
            await(10_000, "The remaining companion must be visible in the notification shade") { systemText("Notice $locale Beta") != null }
            expandNotice("Notice $locale Beta", translated("打开对话", "Open chat"))
            capture("notification-$locale-message-expanded")
            tapNotificationAction("Notice $locale Beta", translated("打开对话", "Open chat"))
            awaitTag("native-companion-composer")
            await(15_000, "Notification opens the exact companion and clears its read messages") {
                companions.state.value.selectedCompanionId == second && unread(second) == 0 && find(second) == null
            }

            openRoot("settings")
            val now = LocalTime.now()
            val format = DateTimeFormatter.ofPattern("HH:mm")
            request("/$first", "PATCH", JSONObject().put("settings", JSONObject().put("quietHours", JSONObject()
                .put("enabled", true).put("start", now.minusHours(1).format(format)).put("end", now.plusHours(1).format(format)))))
            reply(first, "QUIET_$locale")
            val quiet = posted(first).notification
            val quietId = ProactiveNotifications.get(context).status().getJSONObject("messageDelivery").getString("quietChannelId")
            assertEquals("Quiet hours retain a separate silent shade entry", quietId, quiet.channelId)
            val channel = manager.getNotificationChannel(quietId)
            assertEquals(NotificationManager.IMPORTANCE_LOW, channel.importance)
            assertNull("The quiet channel cannot inherit a sound", channel.sound)
            assertFalse("The quiet channel must not vibrate", channel.shouldVibrate())
            assertEquals("Quiet publication remains silent even if the user raises channel importance",
                Notification.GROUP_ALERT_SUMMARY, quiet.groupAlertBehavior)
            assertEquals(1, unread(first))
            shell("cmd statusbar expand-notifications")
            await(10_000, "The quiet message remains in the actual shade") { systemText("Notice $locale Alpha") != null }
            expandNotice("Notice $locale Alpha", translated("标为已读", "Mark as read"))
            capture("notification-$locale-quiet-expanded")
            tapSystemText(translated("标为已读", "Mark as read"))
            await(15_000, "Touching mark as read clears the quiet entry through the runtime") { unread(first) == 0 && find(first) == null }
            shell("cmd statusbar collapse")
            assertPersistentCount()
        }
    }

    private fun inspectPersistentStatus() {
        await(10_000, "The status notification must follow the saved language") {
            persistent()?.extras?.getString(Notification.EXTRA_TITLE) == translated("后台运行中", "Running in background")
        }
        assertPersistentCount()
        val originalPostTime = manager.activeNotifications.single { it.tag == null && it.id == ForegroundStatusNotifications.ID }.postTime
        fixture.scenario.onActivity { activity -> repeat(8) { ForegroundStatusNotifications.refresh(activity) } }
        compose.waitForIdle()
        SystemClock.sleep(300)
        assertEquals("Unchanged status refreshes must not consume Android notification publication capacity", originalPostTime,
            manager.activeNotifications.single { it.tag == null && it.id == ForegroundStatusNotifications.ID }.postTime)
        val stale = Notification.Builder.recoverBuilder(context, requireNotNull(persistent()))
            .setContentTitle("STALE_STATUS_FIXTURE").build()
        manager.notify(ForegroundStatusNotifications.ID, stale)
        await(5_000, "Install the stale system presentation for recovery verification") {
            persistent()?.extras?.getString(Notification.EXTRA_TITLE) == "STALE_STATUS_FIXTURE"
        }
        ForegroundStatusNotifications.refresh(context)
        await(5_000, "An unchanged requested state must repair a stale system presentation") {
            persistent()?.extras?.getString(Notification.EXTRA_TITLE) == translated("后台运行中", "Running in background")
        }
        val status = requireNotNull(persistent())
        assertEquals(translated("点按打开 Droy", "Tap to open Droy"), status.extras.getString(Notification.EXTRA_TEXT))
        val labels = status.actions.map { it.title.toString() }
        assertEquals(listOf(translated("打开 Droy", "Open Droy"), translated("后台设置", "Background settings")), labels)
        assertEquals(NotificationManager.IMPORTANCE_LOW, manager.getNotificationChannel(status.channelId).importance)
        shell("cmd statusbar expand-notifications")
        await(10_000, "The stable title must appear in the actual shade") { systemText(translated("后台运行中", "Running in background")) != null }
        val title = translated("后台运行中", "Running in background")
        val action = translated("后台设置", "Background settings")
        if (systemText(action) != null) {
            tap(expansionControl(title))
            await(5_000, "The collapsed status hides its expanded actions") { systemText(action) == null }
        }
        capture("notification-$language-status-collapsed")
        expandNotice(title, action)
        capture("notification-$language-status-expanded")
        tapSystemText(translated("后台设置", "Background settings"))
        awaitTag("native-permissions")
        compose.onNodeWithTag("native-permissions-background").performScrollTo().assertIsDisplayed()
        capture("notification-$language-background-settings")
        compose.onNodeWithTag("native-permissions-back").performClick()
        openRoot("settings")
    }

    private fun inspectNotificationSettings() {
        compose.onNodeWithTag("native-settings-notifications").performScrollTo().performClick()
        awaitTag("native-message-delivery-status")
        compose.onNodeWithTag("native-message-delivery-status").assertTextEquals(resource(R.string.nps_message_delivery_ready))
        clickWhenEnabled("native-product-switch:nps_message_notifications")
        compose.waitUntil(10_000) { compose.onAllNodesWithText(resource(R.string.nps_message_delivery_off)).fetchSemanticsNodes(false).isNotEmpty() }
        clickWhenEnabled("native-product-switch:nps_message_notifications")
        compose.waitUntil(10_000) { compose.onAllNodesWithText(resource(R.string.nps_message_delivery_ready)).fetchSemanticsNodes(false).isNotEmpty() }
        compose.onNodeWithTag("native-message-quiet-channel-settings").performScrollTo().assertIsDisplayed()
        capture("notification-$language-delivery-settings")
        compose.onNodeWithTag("native-message-channel-settings").performScrollTo()
        clickWhenEnabled("native-message-channel-settings")
        await(10_000, "The channel settings action must open Android Settings") { automation.rootInActiveWindow?.packageName?.toString() == "com.android.settings" }
        capture("notification-$language-android-channel-settings")
        shell("input keyevent KEYCODE_BACK")
        awaitTag("native-message-delivery-status")
        openRoot("settings")
    }

    private fun inspectNotificationLocaleRefresh(id: String, name: String, unread: Int, otherCompanion: String) {
        val originalLanguage = language
        val expectedTags = setOf(ProactiveNotifications.companionTag(id), ProactiveNotifications.companionTag(otherCompanion))
        val originalMessages = messaging(posted(id).notification).messages.map {
            Triple(it.text.toString(), it.timestamp, it.person?.name?.toString())
        }
        for (next in listOf(if (originalLanguage == "zh") "en" else "zh", originalLanguage)) {
            language = next
            fixture.scenario.onActivity { settings.setLanguage(next) }
            compose.waitUntil(20_000) { settings.state.value.locale == next && NativeLocale.active() == next && !settings.state.value.saving }
            assertNull(settings.state.value.error)
            try {
                await(10_000, "Existing notification actions refresh with the saved language") {
                    val current = manager.activeNotifications.associateBy { it.tag }
                    val expected = listOf(resource(R.string.cnotice_open), resource(R.string.cnotice_mark_read))
                    expectedTags.all { tag -> current[tag]?.notification?.actions?.map { it.title.toString() } == expected }
                }
            } catch (failure: AssertionError) {
                val expected = listOf(resource(R.string.cnotice_open), resource(R.string.cnotice_mark_read))
                val actual = manager.activeNotifications.filter { it.tag in expectedTags }
                    .associate { it.tag to it.notification.actions?.map { action -> action.title.toString() } }
                val tags = manager.activeNotifications.filter {
                    it.id == (id.hashCode() and 0x7fffffff) || it.tag == ProactiveNotifications.companionTag(id)
                }.map { it.tag }
                throw AssertionError("Existing notification actions refresh with the saved language; expected=$expected; actual=$actual; " +
                    "activeLocale=${NativeLocale.active()}; companionNotificationTags=$tags", failure)
            }
            val refreshed = posted(id).notification
            assertConversation(refreshed, name, unread)
            val localized = context.createConfigurationContext(Configuration(context.resources.configuration).apply { setLocale(Locale.forLanguageTag(next)) })
            assertEquals(localized.resources.getQuantityString(R.plurals.cnotice_unread, unread, unread), refreshed.extras.getString(Notification.EXTRA_SUB_TEXT))
            assertEquals(resource(R.string.cnotice_you), messaging(refreshed).user.name.toString())
            assertEquals("Language refresh preserves original message contents, times and companion names", originalMessages,
                messaging(refreshed).messages.map { Triple(it.text.toString(), it.timestamp, it.person?.name?.toString()) })
            assertEquals("Language refresh updates the existing companion notification", 1,
                manager.activeNotifications.count { it.tag == ProactiveNotifications.companionTag(id) })
            assertEquals("Language refresh cannot ring the existing notification", Notification.GROUP_ALERT_SUMMARY, refreshed.groupAlertBehavior)
            assertTrue(refreshed.flags and Notification.FLAG_ONLY_ALERT_ONCE != 0)
        }
    }

    private fun createCompanion(name: String): String {
        val id = request(method = "POST", body = JSONObject().put("requestId", UUID.randomUUID().toString()).put("templateId", "maimai")
            .put("name", name).put("model", JSONObject().put("provider", "droy-native-fixture").put("model", "native-local"))
            .put("settings", JSONObject().put("proactiveEnabled", false).put("memoryEnabled", false)
                .put("quietHours", JSONObject().put("enabled", false).put("start", "22:00").put("end", "09:00"))))
            .getJSONObject("profile").getString("id")
        createdCompanions += id
        fixture.scenario.onActivity { companions.refresh() }
        await(15_000, "Created companion is present in the native catalog") { companions.state.value.companions.any { it.id == id } }
        return id
    }

    private fun reply(id: String, marker: String): JSONObject {
        val before = assistantMessages(id).map { it.getString("id") }.toSet()
        request("/$id/messages", "POST", JSONObject().put("clientId", UUID.randomUUID().toString()).put("text", "NATIVE_COMPANION_$marker"))
        var result: JSONObject? = null
        await(35_000, "The isolated local model must commit a new companion reply") {
            val page = request("/$id/messages")
            val rows = page.getJSONArray("messages")
            result = (0 until rows.length()).map { rows.getJSONObject(it) }.lastOrNull { it.optString("role") == "assistant" && it.getString("id") !in before }
                ?.apply { put("fixtureSequence", page.getString("nextAfter").removePrefix("seq:").toLong()) }
            result != null && page.getJSONObject("status").optJSONObject("chatExecution")?.optBoolean("quiescent") == true
        }
        return requireNotNull(result)
    }

    private fun assistantMessages(id: String): List<JSONObject> {
        val rows = request("/$id/messages").getJSONArray("messages")
        return (0 until rows.length()).map { rows.getJSONObject(it) }.filter { it.optString("role") == "assistant" }
    }
    private fun unread(id: String): Int {
        val rows = request().getJSONArray("companions")
        return (0 until rows.length()).map { rows.getJSONObject(it) }.single { it.getString("id") == id }.getInt("unreadCount")
    }
    private fun assertShadeRetainsUnread(id: String) {
        assertEquals("The shade must not mark the incoming reply as read", 1, unread(id))
        assertNotNull("The covered chat retains its system notification", find(id))
        assertFalse("The covered chat must not suppress message delivery", NativeRuntimeHost.isCompanionVisible(id))
        assertFalse("The same expanded shade must keep app window focus lost", windowFocused())
    }
    private fun request(path: String = "", method: String = "GET", body: JSONObject? = null): JSONObject = runBlocking {
        fixture.runtimeTransport.json("/droy/companions$path", method, body)
    }
    private fun find(id: String): StatusBarNotification? = manager.activeNotifications.firstOrNull { it.tag == ProactiveNotifications.companionTag(id) }
    private fun posted(id: String): StatusBarNotification {
        await(10_000, "The expected companion notification must be published") { find(id) != null }
        return requireNotNull(find(id))
    }
    private fun persistent(): Notification? = manager.activeNotifications.firstOrNull { it.tag == null && it.id == ForegroundStatusNotifications.ID }?.notification
    private fun assertPersistentCount() = assertEquals("All foreground lifetimes share one status notification", 1,
        manager.activeNotifications.count { it.notification.flags and Notification.FLAG_FOREGROUND_SERVICE != 0 })
    private fun messaging(notification: Notification): NotificationCompat.MessagingStyle = requireNotNull(NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(notification))
    private fun assertConversation(notification: Notification, name: String, unread: Int) {
        val style = messaging(notification)
        assertEquals(unread, style.messages.size)
        val sender = requireNotNull(style.messages.last().person)
        assertEquals(name, sender.name?.toString())
        assertTrue(sender.isBot)
        assertEquals(unread, notification.number)
        val shortcut = requireNotNull(notification.shortcutId)
        shortcutIds += shortcut
        // The framework exposes shortcut identity and locus; long-lived/person builder flags have no public getters.
        val publishedShortcut = context.getSystemService(ShortcutManager::class.java).dynamicShortcuts.singleOrNull { it.id == shortcut }
        assertNotNull("The conversation shortcut is published", publishedShortcut)
        assertTrue(requireNotNull(publishedShortcut).isDynamic)
        assertEquals(shortcut, publishedShortcut.locusId?.id)
        assertEquals(publishedShortcut.locusId, notification.locusId)
        assertEquals(name, publishedShortcut.shortLabel?.toString())
        assertEquals("The shortcut routes to the same companion as the message sender", sender.key,
            publishedShortcut.intent?.getStringExtra("companionId"))
        val people = notification.extras.getParcelableArrayList<android.app.Person>(Notification.EXTRA_PEOPLE_LIST).orEmpty()
        assertTrue("Notification people metadata retains its companion sender", people.any { it.key == sender.key && it.name?.toString() == name && it.isBot })
        assertNotNull(notification.publicVersion)
        assertFalse("The public lock-screen version cannot expose message contents", notification.publicVersion.extras.toString().contains("Native companion reply received."))
    }
    private fun markRead(notification: Notification): Notification.Action = notification.actions.single { it.title.toString() == translated("标为已读", "Mark as read") }
    private fun translated(zh: String, en: String) = if (language == "zh") zh else en
    private fun resource(id: Int): String = context.createConfigurationContext(Configuration(context.resources.configuration).apply {
        setLocale(Locale.forLanguageTag(language))
    }).getString(id)
    private fun openRoot(destination: String) {
        shell("cmd statusbar collapse")
        fixture.scenario.onActivity { it.startActivity(Intent(it, NativeAppActivity::class.java).putExtra("destination", destination)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)) }
        awaitTag(if (destination == "settings") "native-settings-home-list" else "native-companion-messages")
    }
    private fun openChat(id: String) {
        fixture.scenario.onActivity { it.startActivity(Intent(it, NativeAppActivity::class.java).putExtra("companionId", id)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)) }
        awaitTag("native-companion-composer")
        await(15_000, "The exact companion must be selected") { companions.state.value.selectedCompanionId == id && !companions.state.value.loading }
    }
    private fun awaitTag(tag: String) {
        compose.waitUntil(20_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes(false).isNotEmpty() }
        compose.onNodeWithTag(tag).assertIsDisplayed()
    }
    private fun clickWhenEnabled(tag: String) {
        compose.waitUntil(10_000) {
            compose.onAllNodes(hasTestTag(tag) and isEnabled()).fetchSemanticsNodes(false).size == 1
        }
        compose.onNodeWithTag(tag).assertIsEnabled().performClick()
    }
    private fun capture(name: String) {
        SystemClock.sleep(500)
        fixture.capture(name)
    }
    private fun withFailureEvidence(action: () -> Unit) {
        try { action() }
        catch (failure: Throwable) {
            runCatching { compose.waitForIdle() }.exceptionOrNull()?.let(failure::addSuppressed)
            runCatching { capture("notification-$language-failed") }.exceptionOrNull()?.let(failure::addSuppressed)
            runCatching {
                val directory = File(context.getExternalFilesDir(null), "native-isolated-evidence").apply { check(isDirectory || mkdirs()) }
                val windows = JSONArray()
                for (window in automation.windows) windows.put(JSONObject().put("id", window.id).put("type", window.type)
                    .put("title", window.title?.toString() ?: JSONObject.NULL).put("active", window.isActive)
                    .put("focused", window.isFocused).put("root", accessibilityJson(window.root)))
                File(directory, "notification-$language-failed-system-ui.json").writeText(JSONObject()
                    .put("activeRoot", accessibilityJson(automation.rootInActiveWindow)).put("windows", windows).toString(2))
            }.exceptionOrNull()?.let(failure::addSuppressed)
            runCatching {
                val directory = File(context.getExternalFilesDir(null), "native-isolated-evidence").apply { check(isDirectory || mkdirs()) }
                File(directory, "notification-$language-failed-semantics.txt").writeText(
                    compose.onRoot(useUnmergedTree = true).printToString())
            }.exceptionOrNull()?.let(failure::addSuppressed)
            runCatching {
                val directory = File(context.getExternalFilesDir(null), "native-isolated-evidence").apply { check(isDirectory || mkdirs()) }
                File(directory, "notification-$language-failed-navigation.json").writeText(navigationDiagnostic().toString(2))
            }.exceptionOrNull()?.let(failure::addSuppressed)
            runCatching {
                val directory = File(context.getExternalFilesDir(null), "native-isolated-evidence").apply { check(isDirectory || mkdirs()) }
                File(directory, "notification-$language-failed-publication.json").writeText(publicationDiagnostic().toString(2))
            }.exceptionOrNull()?.let(failure::addSuppressed)
            throw failure
        }
    }
    private fun publicationDiagnostic(): JSONObject {
        val entries = JSONArray()
        for (item in manager.activeNotifications) {
            val notice = item.notification
            val channel = manager.getNotificationChannel(notice.channelId)
            entries.put(JSONObject().put("tag", item.tag ?: JSONObject.NULL).put("id", item.id).put("postTime", item.postTime)
                .put("channelId", notice.channelId).put("channelImportance", channel?.importance ?: JSONObject.NULL)
                .put("channelSound", channel?.sound != null).put("channelVibration", channel?.shouldVibrate() ?: false)
                .put("category", notice.category ?: JSONObject.NULL).put("flags", notice.flags).put("visibility", notice.visibility)
                .put("template", notice.extras.getString(Notification.EXTRA_TEMPLATE) ?: JSONObject.NULL)
                .put("groupKey", item.groupKey).put("groupAlertBehavior", notice.groupAlertBehavior).put("number", notice.number))
        }
        val value = JSONObject().put("entries", entries).put("hostVisible", NativeRuntimeHost.isVisible()).put("windowFocused", windowFocused())
        var resumed = false
        fixture.scenario.onActivity { resumed = it.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) }
        value.put("activityResumed", resumed)
        if (::companions.isInitialized) companions.state.value.selectedCompanionId?.let { id ->
            value.put("selectedCompanionId", id).put("companionVisible", NativeRuntimeHost.isCompanionVisible(id))
            if (RuntimeService.ready) value.put("runtimeUnread", unread(id))
        }
        return value
    }
    private fun navigationDiagnostic(): JSONObject {
        if (!::externalNavigation.isInitialized) return JSONObject().put("initialized", false)
        val state = externalNavigation.state.value
        val companion = (state.request?.target as? NativeExternalNavigationTarget.CompanionChat)?.companionId
        val value = JSONObject().put("initialized", true).put("phase", state.phase.name)
            .put("blocker", state.blocker?.name ?: JSONObject.NULL).put("failure", state.failure?.name ?: JSONObject.NULL)
            .put("inputError", externalNavigation.error.value ?: JSONObject.NULL).put("companionId", companion ?: JSONObject.NULL)
            .put("runtimeReady", RuntimeService.ready)
        File(fixture.home, "native-fixture-runtime-paths.json").takeIf { it.isFile }?.let {
            value.put("runtimePaths", JSONObject(it.readText()))
        }
        if (companion != null && RuntimeService.ready) {
            try {
                value.put("runtimeSnapshot", runBlocking {
                    fixture.runtimeTransport.json("/droy/native/fixture-notifications/navigation")
                }.getJSONObject("result"))
            } catch (error: NativeApiException) { value.put("snapshotErrorCode", error.code) }
            try {
                val result = runBlocking { fixture.runtimeTransport.json("/droy/native/navigation/resolve", "POST",
                    JSONObject().put("kind", "companion-chat").put("companionId", companion)) }.getJSONObject("result")
                value.put("resolverKind", result.optString("kind")).put("resolverCompanionId", result.opt("companionId") ?: JSONObject.NULL)
            } catch (error: NativeApiException) { value.put("resolverErrorCode", error.code) }
        }
        return value
    }
    private fun windowFocused(): Boolean {
        var result = false
        fixture.scenario.onActivity { result = it.hasWindowFocus() }
        return result
    }
    private fun await(timeout: Long, message: String, predicate: () -> Boolean) {
        try { compose.waitUntil(timeout) { predicate() } }
        catch (failure: ComposeTimeoutException) { throw AssertionError(message, failure) }
    }
    private fun shell(command: String): String = ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(command)).bufferedReader().use { it.readText() }
    private fun node(root: AccessibilityNodeInfo?, predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        if (root == null) return null
        if (root.isVisibleToUser && predicate(root)) return root
        for (index in 0 until root.childCount) node(root.getChild(index), predicate)?.let { return it }
        return null
    }
    private fun accessibilityJson(root: AccessibilityNodeInfo?): Any {
        if (root == null) return JSONObject.NULL
        val children = JSONArray()
        for (index in 0 until root.childCount) children.put(accessibilityJson(root.getChild(index)))
        val bounds = Rect().also(root::getBoundsInScreen)
        return JSONObject().put("id", root.viewIdResourceName ?: JSONObject.NULL)
            .put("class", root.className?.toString() ?: JSONObject.NULL).put("package", root.packageName?.toString() ?: JSONObject.NULL)
            .put("text", root.text?.toString() ?: JSONObject.NULL).put("description", root.contentDescription?.toString() ?: JSONObject.NULL)
            .put("bounds", bounds.flattenToString()).put("visible", root.isVisibleToUser).put("clickable", root.isClickable)
            .put("children", children)
    }
    private fun systemText(value: String): AccessibilityNodeInfo? = node(automation.rootInActiveWindow) { it.text?.toString() == value }
    private fun expandNotice(title: String, action: String) {
        if (notificationAction(title, action) != null) return
        tap(expansionControl(title))
        await(5_000, "Expanded notification exposes its action: $action") { notificationAction(title, action) != null }
    }
    private fun notificationCard(title: String): AccessibilityNodeInfo? {
        var ancestor = systemText(title)
        repeat(12) {
            val current = ancestor ?: return null
            // The real SystemUI row reports FrameLayout and contains multiple templates with the same framework ID.
            if (current.viewIdResourceName == "com.android.systemui:id/expandableNotificationRow") return current
            ancestor = current.parent
        }
        return null
    }
    private fun notificationAction(title: String, action: String): AccessibilityNodeInfo? =
        node(notificationCard(title)) { it.text?.toString() == action }
    private fun tapNotificationAction(title: String, action: String) {
        await(5_000, "The selected notification card exposes its action: $title / $action") { notificationAction(title, action) != null }
        tap(requireNotNull(notificationAction(title, action)))
    }
    private fun expansionControl(title: String): AccessibilityNodeInfo {
        val expand = node(notificationCard(title)) { it.viewIdResourceName == "android:id/expand_button" }
        assertNotNull("The notification expansion control must be reachable: $title", expand)
        return requireNotNull(expand)
    }
    private fun tapSystemText(text: String) {
        await(5_000, "The real notification action must be visible: $text") { systemText(text) != null }
        tap(requireNotNull(systemText(text)))
    }
    private fun tap(target: AccessibilityNodeInfo) {
        val bounds = Rect().also(target::getBoundsInScreen)
        assertFalse("Notification touch target has visible bounds", bounds.isEmpty)
        val now = SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, bounds.exactCenterX(), bounds.exactCenterY(), 0)
        val up = MotionEvent.obtain(now, now + 60, MotionEvent.ACTION_UP, bounds.exactCenterX(), bounds.exactCenterY(), 0)
        try {
            down.source = InputDevice.SOURCE_TOUCHSCREEN; up.source = InputDevice.SOURCE_TOUCHSCREEN
            assertTrue(automation.injectInputEvent(down, true)); assertTrue(automation.injectInputEvent(up, true))
        } finally { down.recycle(); up.recycle() }
    }
}
