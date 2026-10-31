package io.droyagent.app

import android.content.pm.PackageManager
import android.content.res.Configuration
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.Lifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.droyagent.app.nativeui.permissions.NativeAuthorizationPolicy
import io.droyagent.app.nativeui.permissions.NativePermissionPolicyStore
import io.droyagent.app.nativeui.permissions.NativeProductPermission
import java.io.File
import java.util.Locale
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Real native navigation and product consent; never confirms an Android, Root, or microphone request. */
@RunWith(AndroidJUnit4::class)
class NativePermissionReviewDeviceTest {
    @get:Rule(order = 0) val compose = createEmptyComposeRule()
    @get:Rule(order = 1) val fixture = NativeIsolatedRuntimeFixture("settings")
    private lateinit var coordinator: NativePermissionCoordinator
    private var initialRootChoice = false
    private var initialRootCheckedAt = 0L
    private var initialRootPassed = false
    private val preferences get() = fixture.context.getSharedPreferences("product", 0)
    private val policy get() = NativePermissionPolicyStore(preferences)

    @Test fun nativePermissionNavigationConsentAndCancelledConfirmationsPreserveSystemGrants() {
        bindCoordinator()
        compose.waitUntil(15_000) { exists("native-settings-permissions") }
        compose.onNodeWithTag("native-settings-permissions").performScrollTo().performClick()
        ready()
        val grants = systemGrants()
        // The existing-home fixture adopts legacy product choices; allowRoot is not a system grant.
        initialRootChoice = ProductPermissions.allowed(preferences, "allowRoot")
        val root = SetupRootVerification.status()
        assertFalse("Do not overlap an actual Root authorization", root.getBoolean("running"))
        initialRootCheckedAt = root.getLong("checkedAt")
        initialRootPassed = root.getBoolean("passed")
        val records = policy.records()

        compose.onNodeWithTag("native-permissions-first-use").performScrollTo().performClick()
        compose.waitUntil(10_000) { coordinator.state.value.policy == NativeAuthorizationPolicy.ON_FIRST_USE }
        assertEquals(NativeAuthorizationPolicy.ON_FIRST_USE, policy.selected())
        compose.onNodeWithTag("native-permissions-first-use").assertIsSelected()

        // Confirmation schedules queue advancement immediately after approval; cancellation must precede approval.
        cancelConfirmation("native-permissions-set-up-all", "batch")
        cancelConfirmation("native-permissions-accessibility", "accessibility")
        cancelConfirmation("native-permissions-root", "root")
        assertEquals(records, policy.records())
        assertNoAuthorization(grants)

        val rootAtSystem = scrollTo(text(R.string.native_permissions_system_permissions))
        assertTrue("The root page must actually be scrolled before entering its child", rootAtSystem.estimatedOffset > 0f)
        fixture.capture("native-permissions-root-before-system")
        compose.onNodeWithText(text(R.string.native_permissions_system_permissions)).performClick()
        compose.onNodeWithTag("native-permissions-system:notifications").assertIsDisplayed()
        assertAtTop()
        fixture.capture("native-permissions-system-initial-top")
        val lastSystem = coordinator.state.value.system.last().id
        list().performScrollToNode(hasTestTag("native-permissions-system:$lastSystem"))
        val systemPosition = anchor(hasTestTag("native-permissions-system:$lastSystem"))
        back()
        fixture.capture("native-permissions-root-after-system")
        assertAnchor(rootAtSystem)

        val rootAtProducts = scrollTo(text(R.string.native_permissions_product_capabilities))
        compose.onNodeWithText(text(R.string.native_permissions_product_capabilities)).performClick()
        compose.onNodeWithTag("native-permissions-operations").assertIsDisplayed()
        assertAtTop()
        fixture.capture("native-permissions-products-initial-top")

        val entry = coordinator.state.value.products.first { it.id == "settingsRead" }
        val group = coordinator.state.value.products.filter { it.section == entry.section }
        assertTrue(group.size > 1)
        val productPosition = scrollTo(entry.section)
        compose.onNodeWithText(entry.section).performClick()
        compose.onNodeWithTag("native-permissions-product:${group.first().preference}", useUnmergedTree = true).assertIsDisplayed()
        assertAtTop()

        val unrelated = capabilityChoices().filterKeys { key -> group.none { it.preference == key } }
        compose.onNodeWithText(text(R.string.native_permissions_clear_all)).performClick()
        assertGroup(group, false)
        // The checkbox itself delegates semantics to its actual toggleable row.
        compose.onNodeWithText(entry.label).performClick()
        compose.waitUntil(10_000) { coordinator.state.value.products.first { it.preference == entry.preference }.enabled }
        assertBackend(entry.preference, true)
        assertFalse("No other row is implicitly enabled", group.filterNot { it == entry }.any { ProductPermissions.allowed(preferences, it.preference) })
        compose.onNodeWithText(text(R.string.native_permissions_select_all)).performClick()
        assertGroup(group, true)
        compose.onNodeWithText(text(R.string.native_permissions_clear_all)).performClick()
        assertGroup(group, false)
        assertEquals(unrelated, capabilityChoices().filterKeys { key -> group.none { it.preference == key } })
        fixture.capture("native-permissions-native-checkbox-group")
        back()
        assertAnchor(productPosition)
        back()
        assertAnchor(rootAtProducts)

        // Existing child positions remain separate from the root and survive Activity recreation.
        scrollTo(text(R.string.native_permissions_system_permissions))
        compose.onNodeWithText(text(R.string.native_permissions_system_permissions)).performClick()
        assertAnchor(systemPosition)
        fixture.recreate()
        bindCoordinator()
        ready()
        fixture.capture("native-permissions-system-after-recreate")
        assertAnchor(systemPosition)
        back()
        list().performScrollToNode(hasTestTag("native-permissions-first-use"))
        compose.onNodeWithTag("native-permissions-first-use").assertIsSelected()
        assertEquals(NativeAuthorizationPolicy.ON_FIRST_USE, policy.selected())
        assertEquals(records, policy.records())
        assertNoAuthorization(grants)
        val calls = File(fixture.home, "native-fixture-calls.jsonl")
        assertTrue("Permission review must not invoke a model", !calls.exists() || calls.readText().isBlank())
    }

    private fun bindCoordinator() {
        fixture.scenario.onActivity { activity ->
            // Read the real Activity owner without adding a production test hook or bypassing its actions.
            val field = NativeAppActivity::class.java.getDeclaredField("permissions").apply { isAccessible = true }
            coordinator = field.get(activity) as NativePermissionCoordinator
        }
    }
    private fun ready() {
        compose.waitUntil(15_000) { coordinator.state.value.fresh && !coordinator.state.value.loading && !coordinator.state.value.queue.active }
        compose.waitForIdle()
        assertNull(coordinator.state.value.error)
    }
    private fun cancelConfirmation(button: String, kind: String) {
        list().performScrollToNode(hasTestTag(button))
        compose.onNodeWithTag(button).performClick()
        compose.onNodeWithTag("native-permissions-confirm:$kind").assertIsDisplayed()
        compose.onNode(hasText(text(R.string.native_permissions_cancel)) and hasAnyAncestor(isDialog())).performClick()
        compose.onNodeWithTag("native-permissions-confirm:$kind").assertDoesNotExist()
        assertFalse(coordinator.state.value.queue.active)
        assertEquals(NativeAuthorizationPolicy.ON_FIRST_USE, policy.selected())
    }
    private fun assertGroup(group: List<NativeProductPermission>, enabled: Boolean) {
        compose.waitUntil(10_000) { group.all { expected -> coordinator.state.value.products.first { it.preference == expected.preference }.enabled == enabled } }
        group.forEach {
            compose.onNodeWithTag("native-permissions-product:${it.preference}", useUnmergedTree = true).assertExists()
            val row = compose.onNode(hasText(it.label) and hasClickAction())
            if (enabled) row.assertIsOn() else row.assertIsOff()
            assertBackend(it.preference, enabled)
        }
    }
    private fun assertBackend(key: String, expected: Boolean) {
        assertEquals(expected, preferences.getBoolean(key, !expected))
        assertEquals(expected, ProductPermissions.allowed(preferences, key))
        val groups = RuntimeService.capabilityStatus(fixture.context).getJSONArray("groups")
        val actual = (0 until groups.length()).map(groups::getJSONObject).single { it.getString("preference") == key }
        assertEquals("Native UI and the execution boundary must agree for $key", expected, actual.getBoolean("allowed"))
    }
    private fun capabilityChoices(): Map<String, Boolean> = coordinator.state.value.products.associate { it.preference to ProductPermissions.allowed(preferences, it.preference) }
    private fun assertNoAuthorization(grants: Map<String, Int>) {
        assertEquals(grants, systemGrants())
        var activityState = Lifecycle.State.INITIALIZED
        var windowFocused = false
        var foregroundPackage: String? = null
        // Dismissing a dialog briefly leaves accessibility without an active window.
        compose.waitUntil(10_000) {
            fixture.scenario.onActivity { activity ->
                activityState = activity.lifecycle.currentState
                windowFocused = activity.hasWindowFocus()
            }
            val foreground = InstrumentationRegistry.getInstrumentation().uiAutomation.rootInActiveWindow
            try { foregroundPackage = foreground?.packageName?.toString() }
            finally { @Suppress("DEPRECATION") foreground?.recycle() }
            activityState == Lifecycle.State.RESUMED && windowFocused && foregroundPackage == fixture.context.packageName
        }
        assertEquals(Lifecycle.State.RESUMED, activityState)
        assertTrue("The app window must regain focus after cancelling confirmation", windowFocused)
        assertEquals(fixture.context.packageName, foregroundPackage)
        assertEquals(grants, systemGrants())
        assertFalse(coordinator.state.value.queue.active)
        assertTrue(coordinator.state.value.batchIndependent.isEmpty())
        assertFalse(coordinator.state.value.root.running)
        assertEquals("Cancelling confirmation must preserve the existing Root product choice",
            initialRootChoice, ProductPermissions.allowed(preferences, "allowRoot"))
        val root = SetupRootVerification.status()
        assertFalse(root.getBoolean("running"))
        assertEquals("No actual Root probe may run", initialRootCheckedAt, root.getLong("checkedAt"))
        assertEquals(initialRootPassed, root.getBoolean("passed"))
        assertTrue(AudioCapture.get(fixture.context).uiStatus().isNull("active"))
    }
    @Suppress("DEPRECATION") private fun systemGrants(): Map<String, Int> =
        fixture.context.packageManager.getPackageInfo(fixture.context.packageName, PackageManager.GET_PERMISSIONS)
            .requestedPermissions.orEmpty().associateWith(fixture.context::checkSelfPermission)
    private fun list() = compose.onNodeWithTag("native-permissions-content")
    private fun position(): Float {
        compose.waitForIdle()
        return list().fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()
    }
    private data class ScrollAnchor(val matcher: SemanticsMatcher, val bounds: Rect, val estimatedOffset: Float)
    private fun anchor(matcher: SemanticsMatcher): ScrollAnchor {
        compose.waitForIdle()
        val viewport = list().fetchSemanticsNode().boundsInRoot
        val node = compose.onNode(matcher).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        return ScrollAnchor(matcher, Rect(node.left - viewport.left, node.top - viewport.top,
            node.right - viewport.left, node.bottom - viewport.top), position())
    }
    private fun scrollTo(label: String): ScrollAnchor {
        val matcher = hasText(label) and hasClickAction()
        list().performScrollToNode(matcher)
        return anchor(matcher)
    }
    private fun back() { compose.onNodeWithTag("native-permissions-back").performClick(); compose.waitForIdle() }
    private fun assertAtTop() = assertEquals("A new permission destination starts at index zero with zero offset", 0f, position(), 0f)
    private fun assertAnchor(expected: ScrollAnchor) {
        val actual = anchor(expected.matcher)
        // Lazy semantics reports index * 500 + offset, not pixels. Assert the visible row itself, without scrolling it into view.
        assertEquals("The same row must retain its exact viewport bounds; diagnostic lazy estimates ${expected.estimatedOffset} -> ${actual.estimatedOffset}",
            expected.bounds, actual.bounds)
    }
    private fun exists(tag: String) = compose.onAllNodesWithTag(tag).fetchSemanticsNodes(false).isNotEmpty()
    private fun text(id: Int): String = fixture.context.createConfigurationContext(Configuration(fixture.context.resources.configuration).apply {
        setLocale(Locale.ENGLISH)
    }).getString(id)
}
