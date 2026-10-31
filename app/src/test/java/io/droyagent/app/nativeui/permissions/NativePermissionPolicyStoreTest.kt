package io.droyagent.app.nativeui.permissions

import android.content.SharedPreferences
import java.lang.reflect.Proxy
import org.junit.Assert.*
import org.junit.Test

class NativePermissionPolicyStoreTest {
    @Test fun noPolicyIsInventedAndSelectingOneDoesNotGrantCapabilities() {
        val fake = Preferences(mapOf("allowContacts" to false, "allowRoot" to true))
        val store = NativePermissionPolicyStore(fake.instance)
        assertNull(store.selected())
        assertEquals(0, fake.commits)
        store.select(NativeAuthorizationPolicy.ON_FIRST_USE)
        assertEquals(NativeAuthorizationPolicy.ON_FIRST_USE, NativePermissionPolicyStore(fake.instance).selected())
        assertEquals(false, fake.values["allowContacts"])
        assertEquals(true, fake.values["allowRoot"])
    }

    @Test fun failedPersistenceDoesNotBecomeASelectedPolicy() {
        val fake = Preferences()
        fake.acceptCommits = false
        val store = NativePermissionPolicyStore(fake.instance)
        try { store.select(NativeAuthorizationPolicy.BATCH); fail("Expected refusal") }
        catch (error: IllegalStateException) { assertEquals("PERMISSION_POLICY_NOT_SAVED", error.message) }
        assertNull(store.selected())
    }

    @Test fun firstUseRefusalAndSkippedRequestsDoNotLoopAutomatically() {
        val fake = Preferences()
        val store = NativePermissionPolicyStore(fake.instance)
        store.select(NativeAuthorizationPolicy.ON_FIRST_USE)
        assertTrue(store.automaticRequestAllowed("contacts"))
        store.recordRequested("contacts", 1)
        store.recordOutcome("contacts", NativePermissionOutcome.NOT_GRANTED, 2)
        assertFalse(NativePermissionPolicyStore(fake.instance).automaticRequestAllowed("contacts"))
        store.recordRequested("contacts", 3)
        store.recordOutcome("contacts", NativePermissionOutcome.SKIPPED, 4)
        assertFalse(store.automaticRequestAllowed("contacts"))
        assertEquals(1L, store.records().getValue("contacts").firstRequestedAt)
        assertEquals(4L, store.records().getValue("contacts").updatedAt)
    }

    @Test fun independentPermissionsAlwaysRequireTheirExplicitEntry() {
        val store = NativePermissionPolicyStore(Preferences().instance)
        store.select(NativeAuthorizationPolicy.ON_FIRST_USE)
        for (permission in listOf("root", "accessibility", "shizuku", "capability:allowRoot"))
            assertFalse(permission, store.automaticRequestAllowed(permission))
        assertTrue(store.automaticRequestAllowed("capability:allowContacts"))
    }

    @Test fun pendingRequestsKeepTaskIdentityAndSuppressDuplicateFirstUsePrompts() {
        val fake = Preferences()
        val store = NativePermissionPolicyStore(fake.instance)
        store.select(NativeAuthorizationPolicy.ON_FIRST_USE)
        val pending = NativePendingPermissionRequest("request-1", "task-1", "call-1", setOf("allowContacts"), setOf("contacts"), 10)
        assertTrue(store.offerPending(pending))
        assertFalse(store.offerPending(pending.copy(createdAt = 11)))
        assertEquals(pending, store.pendingRequests().single())
        assertFalse(store.automaticRequestAllowed("contacts"))
        assertFalse(store.automaticRequestAllowed("capability:allowContacts"))
        try { store.offerPending(pending.copy(taskId = "another-task")); fail("Conflicting identity must be refused") }
        catch (error: IllegalStateException) { assertEquals("PERMISSION_PENDING_ID_CONFLICT", error.message) }
        assertFalse(fake.values.containsKey("allowContacts"))
        store.removePending(pending.id)
        assertTrue(store.pendingRequests().isEmpty())
        assertTrue(store.automaticRequestAllowed("contacts"))
    }

    @Test fun ordinaryProductChoicesPreserveBothExistingRootStates() {
        val choices = NativePermissionPlans.ordinaryProductChoices(setOf("allowRoot", "allowContacts", "allowControl"))
        assertFalse(choices.containsKey("allowRoot"))
        assertEquals(mapOf("allowContacts" to true, "allowControl" to true), choices)
        for (root in listOf(true, false)) {
            val saved = mutableMapOf("allowRoot" to root, "allowContacts" to false)
            saved.putAll(choices)
            assertEquals(root, saved["allowRoot"])
            assertEquals(true, saved["allowContacts"])
        }
    }

    @Test fun actualSystemStateWinsOverOldRequestReceiptsAndProcessingDoesNotGrantAccess() {
        assertEquals(NativePermissionGrant.NOT_GRANTED, NativePermissionFacts.grant(false, true, false))
        assertEquals(NativePermissionGrant.SETTINGS_REQUIRED, NativePermissionFacts.grant(false, true, true))
        assertEquals(NativePermissionGrant.GRANTED, NativePermissionFacts.grant(true, true, true))
        assertEquals(NativePermissionGrant.NOT_REQUESTED, NativePermissionFacts.grant(false, false, false))
    }

    @Test fun retiredCallIdentitySurvivesStoreReloadAndIsBoundToBothTaskAndCall() {
        val fake = Preferences()
        val store = NativePermissionPolicyStore(fake.instance)
        store.retireCall("task-a", "call-a")
        val restored = NativePermissionPolicyStore(fake.instance)
        assertTrue(restored.isRetiredCall("task-a", "call-a"))
        assertFalse(restored.isRetiredCall("task-b", "call-a"))
        assertFalse(restored.isRetiredCall("task-a", "call-b"))
        assertFalse(fake.values.values.any { it is String && it.contains("task-a") })
        val commits = fake.commits
        restored.retireCall("task-a", "call-a")
        assertEquals(commits, fake.commits)
    }

    private class Preferences(initial: Map<String, Any> = emptyMap()) {
        val values = initial.toMutableMap()
        var acceptCommits = true
        var commits = 0
        val instance: SharedPreferences = Proxy.newProxyInstance(SharedPreferences::class.java.classLoader,
            arrayOf(SharedPreferences::class.java)) { _, method, args ->
            when (method.name) {
                "getString" -> values[args!![0]] as? String ?: args[1]
                "contains" -> values.containsKey(args!![0])
                "getAll" -> values.toMap()
                "edit" -> editor()
                else -> throw AssertionError("Unexpected preference read: ${method.name}")
            }
        } as SharedPreferences

        private fun editor(): SharedPreferences.Editor {
            val pending = mutableMapOf<String, Any>()
            lateinit var proxy: SharedPreferences.Editor
            proxy = Proxy.newProxyInstance(SharedPreferences.Editor::class.java.classLoader, arrayOf(SharedPreferences.Editor::class.java)) { _, method, args ->
                when (method.name) {
                    "putString" -> { pending[args!![0] as String] = args[1] as String; proxy }
                    "commit" -> { commits++; if (acceptCommits) values.putAll(pending); acceptCommits }
                    else -> throw AssertionError("Unexpected preference mutation: ${method.name}")
                }
            } as SharedPreferences.Editor
            return proxy
        }
    }
}
