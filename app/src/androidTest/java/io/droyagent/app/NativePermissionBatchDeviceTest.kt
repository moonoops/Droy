package io.droyagent.app

import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.KeyEvent
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.droyagent.app.nativeui.permissions.NativePermissionAction
import io.droyagent.app.nativeui.permissions.NativePermissionsScreen
import io.droyagent.app.nativeui.ui.DroyTheme
import java.util.Locale
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The real coordinator consumes a restored batch without requesting any system authorization. */
@RunWith(AndroidJUnit4::class)
class NativePermissionBatchDeviceTest {
    @get:Rule(order = 0) val compose = createEmptyComposeRule()
    @get:Rule(order = 1) val fixture = NativeIsolatedRuntimeFixture("settings", allowRoot = false)
    private lateinit var coordinator: NativePermissionCoordinator

    @Test fun englishBatchDoesNotReplayConsumedConfirmations() = inspectBatch("en")
    @Test fun chineseBatchDoesNotReplayConsumedConfirmations() = inspectBatch("zh")

    @Test fun batterySettingsOpenOnceAndReturningDoesNotRepeatTheConsumedConfirmation() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val grants = systemGrants()
        val power = requireNotNull(fixture.context.getSystemService(PowerManager::class.java))
        val exempt = power.isIgnoringBatteryOptimizations(fixture.context.packageName)
        val settingsPackage = requireNotNull(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
            .resolveActivity(fixture.context.packageManager)).packageName
        val monitor = instrumentation.addMonitor(IntentFilter(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS), null, false)
        fixture.scenario.onActivity { activity ->
            coordinator = NativePermissionCoordinator(activity, Bundle().apply {
                putStringArrayList("nativePermissionBatchIndependent", arrayListOf("battery"))
            })
            coordinator.resume()
            activity.setContent {
                val state by coordinator.state.collectAsState()
                DroyTheme { NativePermissionsScreen(state, coordinator::onAction, onBack = {}) }
            }
        }
        try {
            compose.waitUntil(15_000) { coordinator.state.value.fresh }
            awaitRefresh()
            compose.onNodeWithTag("native-permissions-confirm:battery").assertIsDisplayed().performClick()
            compose.waitUntil(15_000) { foregroundPackage() == settingsPackage }
            assertEquals("One confirmation must launch the real Android settings page once", 1, monitor.hits)
            assertTrue(coordinator.state.value.batchIndependent.isEmpty())
            fixture.capture("native-permissions-real-battery-settings")

            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            compose.waitUntil(15_000) { foregroundPackage() == fixture.context.packageName }
            fixture.scenario.onActivity { coordinator.resume() }
            awaitRefresh()
            compose.onNodeWithTag("native-permissions-confirm:battery").assertDoesNotExist()
            assertTrue(coordinator.state.value.batchIndependent.isEmpty())
            fixture.capture("native-permissions-real-battery-returned")

            fixture.scenario.onActivity {
                coordinator.onAction(NativePermissionAction.ConfirmIndependent("battery"))
                coordinator.refresh()
            }
            awaitRefresh()
            instrumentation.waitForIdleSync()
            compose.onNodeWithTag("native-permissions-confirm:battery").assertDoesNotExist()
            assertEquals("A consumed confirmation cannot reopen the system page", 1, monitor.hits)
            assertEquals(fixture.context.packageName, foregroundPackage())
            assertEquals("Reviewing settings must not change the battery optimization exemption", exempt,
                power.isIgnoringBatteryOptimizations(fixture.context.packageName))
            assertEquals(grants, systemGrants())
        } finally {
            if (foregroundPackage() == settingsPackage) {
                instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
                compose.waitUntil(15_000) { foregroundPackage() == fixture.context.packageName }
            }
            fixture.scenario.onActivity { coordinator.close() }
            instrumentation.removeMonitor(monitor)
        }
    }

    @Test fun consumedBatchConfirmationCannotRepeatItsSideEffectButManualEntryStillWorks() {
        fixture.scenario.onActivity { activity ->
            coordinator = NativePermissionCoordinator(activity, Bundle().apply {
                putStringArrayList("nativePermissionBatchIndependent", arrayListOf("background"))
            })
            coordinator.resume()
        }
        try {
            compose.waitUntil(15_000) { coordinator.state.value.fresh }
            fixture.scenario.onActivity {
                coordinator.onAction(NativePermissionAction.ConfirmIndependent("background"))
                assertTrue(coordinator.state.value.batchIndependent.isEmpty())
                assertTrue(BackgroundRuntime.enabled(fixture.context))
                coordinator.onAction(NativePermissionAction.SetBackgroundEnabled(false))
                coordinator.onAction(NativePermissionAction.ConfirmIndependent("background"))
                assertFalse("Replaying an old batch confirmation cannot re-enable the capability",
                    BackgroundRuntime.enabled(fixture.context))
                coordinator.onAction(NativePermissionAction.SetBackgroundEnabled(true))
                assertTrue("The separate manual entry must remain usable", BackgroundRuntime.enabled(fixture.context))
                coordinator.onAction(NativePermissionAction.SetBackgroundEnabled(false))
                assertNull(coordinator.state.value.error)
            }
        } finally {
            fixture.scenario.onActivity { coordinator.close() }
        }
    }

    private fun inspectBatch(locale: String) {
        val grants = systemGrants()
        val rootBefore = SetupRootVerification.status()
        val afterSkip = mutableMapOf<String, List<String>>()
        fixture.scenario.onActivity { activity ->
            coordinator = NativePermissionCoordinator(activity, Bundle().apply {
                putStringArrayList("nativePermissionBatchIndependent", arrayListOf("battery", "accessibility"))
            })
            coordinator.resume()
            val localized = activity.createConfigurationContext(Configuration(activity.resources.configuration).apply {
                setLocale(Locale.forLanguageTag(locale))
            })
            activity.setContent {
                val state by coordinator.state.collectAsState()
                CompositionLocalProvider(LocalContext provides localized,
                    LocalConfiguration provides localized.resources.configuration,
                    LocalResources provides localized.resources) {
                    DroyTheme {
                        NativePermissionsScreen(state, onAction = { action ->
                            coordinator.onAction(action)
                            if (action is NativePermissionAction.SkipIndependent)
                                afterSkip[action.id] = coordinator.state.value.batchIndependent
                        }, onBack = {})
                    }
                }
            }
        }
        try {
            compose.waitUntil(15_000) { coordinator.state.value.fresh }
            awaitRefresh()
            compose.onNodeWithTag("native-permissions-confirm:battery").assertIsDisplayed()
            fixture.capture("native-permissions-batch-$locale-battery")
            fixture.scenario.onActivity { coordinator.refresh() }
            cancel(locale)
            assertEquals("Consumption must be published before the action returns",
                listOf("accessibility"), afterSkip["battery"])
            compose.onNodeWithTag("native-permissions-confirm:battery").assertDoesNotExist()
            compose.onNodeWithTag("native-permissions-confirm:accessibility").assertIsDisplayed()

            fixture.scenario.onActivity {
                coordinator.refresh()
                coordinator.onAction(NativePermissionAction.ConfirmIndependent("battery"))
                coordinator.onAction(NativePermissionAction.SkipIndependent("battery"))
                assertEquals(listOf("accessibility"), coordinator.state.value.batchIndependent)
                assertNull("A stale dialog callback is already handled", coordinator.state.value.error)
                assertFalse("A stale callback must not open a system permission flow", coordinator.state.value.queue.active)
            }
            awaitRefresh()
            compose.waitForIdle()
            compose.onNodeWithTag("native-permissions-confirm:battery").assertDoesNotExist()
            compose.onNodeWithTag("native-permissions-confirm:accessibility").assertIsDisplayed()
            fixture.capture("native-permissions-batch-$locale-accessibility")
            cancel(locale)
            assertEquals(emptyList<String>(), afterSkip["accessibility"])
            compose.onNodeWithTag("native-permissions-confirm:accessibility").assertDoesNotExist()

            compose.waitUntil(10_000) {
                var focused = false
                fixture.scenario.onActivity { focused = it.hasWindowFocus() }
                focused
            }

            fixture.scenario.onActivity { activity ->
                val saved = Bundle()
                coordinator.save(saved)
                assertTrue(saved.getStringArrayList("nativePermissionBatchIndependent").orEmpty().isEmpty())
                coordinator.onAction(NativePermissionAction.ConfirmIndependent("accessibility"))
                coordinator.onAction(NativePermissionAction.ConfirmIndependent("battery"))
                assertTrue(coordinator.state.value.batchIndependent.isEmpty())
                assertNull(coordinator.state.value.error)
                assertFalse(coordinator.state.value.queue.active)
                assertTrue("Consumed confirmations must leave the app in front", activity.hasWindowFocus())
            }
            assertEquals(grants, systemGrants())
            val rootAfter = SetupRootVerification.status()
            assertEquals(rootBefore.getLong("checkedAt"), rootAfter.getLong("checkedAt"))
            assertEquals(rootBefore.getBoolean("passed"), rootAfter.getBoolean("passed"))
            assertFalse(rootAfter.getBoolean("running"))
        } finally {
            fixture.scenario.onActivity { coordinator.close() }
        }
    }

    private fun cancel(locale: String) {
        val localized = fixture.context.createConfigurationContext(Configuration(fixture.context.resources.configuration).apply {
            setLocale(Locale.forLanguageTag(locale))
        })
        compose.onNode(hasText(localized.getString(R.string.native_permissions_cancel)) and hasAnyAncestor(isDialog()))
            .performClick()
        compose.waitForIdle()
    }

    private fun awaitRefresh() {
        val refreshing = NativePermissionCoordinator::class.java.getDeclaredField("refreshing").apply { isAccessible = true }
        compose.waitUntil(15_000) {
            var finished = false
            fixture.scenario.onActivity { finished = refreshing.get(coordinator) == null }
            finished
        }
    }

    @Suppress("DEPRECATION") private fun foregroundPackage(): String? {
        val root = InstrumentationRegistry.getInstrumentation().uiAutomation.rootInActiveWindow
        return try { root?.packageName?.toString() } finally { root?.recycle() }
    }

    @Suppress("DEPRECATION") private fun systemGrants(): Map<String, Int> =
        fixture.context.packageManager.getPackageInfo(fixture.context.packageName, PackageManager.GET_PERMISSIONS)
            .requestedPermissions.orEmpty().associateWith(fixture.context::checkSelfPermission)
}
