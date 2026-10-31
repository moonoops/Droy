package io.droyagent.app

import android.content.SharedPreferences
import io.droyagent.app.nativeui.data.NativeApiException
import java.lang.reflect.Proxy
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class NativePersonalizationResetTest {
    @Test fun engineReceiptAndReadbackPrecedeOneExactLocalCommitAndPresentationRefresh() = runBlocking {
        val fixture = Fixture()
        val before = fixture.preferences.values.toMap()
        val receipt = fixture.service.reset()
        assertEquals(8L, receipt.themeRevision)
        assertEquals(listOf("describe", "mutate", "describe", "commit", "presentation"), fixture.events)
        assertEquals(1, fixture.preferences.commits)
        val removed = setOf("assistantName", "browserEncryptedDns", "darkTheme", "brandSidebarLabel", "brandHeroBadge",
            "brandWelcomeTemplate", "brandComposerTemplate", "brandLogoBackgrounds", "themeColorScheme", "themeBackgroundColor", "resetTheme")
        assertEquals(removed, fixture.preferences.removals.single())
        for (key in removed) assertFalse(key, fixture.preferences.values.containsKey(key))
        assertEquals("system", fixture.preferences.values["themePreference"])
        for ((key, value) in before.filterKeys { it !in removed && it != "themePreference" }) assertEquals(key, value, fixture.preferences.values[key])
        val body = fixture.theme.mutations.single()
        assertEquals("ui-theme", body.getString("ns")); assertEquals(7L, body.getLong("expectedRevision"))
        assertEquals(1, body.getJSONArray("ops").length())
        val operation = body.getJSONArray("ops").getJSONObject(0)
        assertEquals(setOf("op", "path", "value"), operation.keys().asSequence().toSet())
        assertEquals("set", operation.getString("op")); assertEquals("system", operation.getString("value"))
        assertEquals("[\"preference\"]", operation.getJSONArray("path").toString())
    }

    @Test fun anUnreadyRuntimeDoesNotReadOrWriteEitherConfiguration() = runBlocking {
        val fixture = Fixture(); fixture.ready = false
        val error = failure { fixture.service.reset() }
        assertEquals(NativePersonalizationReset.Stage.NOT_READY, error.stage)
        assertFalse(error.engineThemeConfirmed); assertFalse(error.localCommitConfirmed)
        assertTrue(fixture.events.isEmpty())
    }

    @Test fun anExplicitRevisionConflictLeavesAllLocalPersonalizationUntouched() = runBlocking {
        val fixture = Fixture(); fixture.theme.mutationFailure = NativeApiException("settings/conflict")
        val before = fixture.preferences.values.toMap()
        val error = failure { fixture.service.reset() }
        assertEquals(NativePersonalizationReset.Stage.ENGINE_CONFLICT, error.stage)
        assertEquals(before, fixture.preferences.values); assertEquals(0, fixture.preferences.commits)
        assertEquals(listOf("describe", "mutate"), fixture.events)
    }

    @Test fun aLostMutationReceiptDoesNotStartLocalResetOrAutomaticallyReplayTheWrite() = runBlocking {
        val fixture = Fixture(); fixture.theme.mutationFailure = NativeApiException("network_error"); fixture.theme.applyBeforeFailure = true
        val error = failure { fixture.service.reset() }
        assertEquals(NativePersonalizationReset.Stage.ENGINE_UNCONFIRMED, error.stage)
        assertEquals("system", fixture.theme.preference); assertEquals(0, fixture.preferences.commits)
        assertEquals(1, fixture.theme.mutations.size)
        fixture.theme.mutationFailure = null
        fixture.service.reset()
        assertEquals(2, fixture.theme.mutations.size)
        assertEquals(8L, fixture.theme.mutations.last().getLong("expectedRevision"))
        assertEquals(1, fixture.preferences.commits)
    }

    @Test fun aSuccessfulMutationStillRequiresFreshStoredThemeReadback() = runBlocking {
        val fixture = Fixture()
        fixture.theme.readbackTransform = { root -> root.getJSONArray("namespaces").getJSONObject(0).getJSONObject("value").put("preference", "dark"); root }
        val error = failure { fixture.service.reset() }
        assertEquals(NativePersonalizationReset.Stage.ENGINE_UNCONFIRMED, error.stage)
        assertEquals(0, fixture.preferences.commits); assertEquals(listOf("describe", "mutate", "describe"), fixture.events)
    }

    @Test fun aThemeWithNoStoredOrInheritedSystemEvidenceDoesNotClearLocalData() = runBlocking {
        val fixture = Fixture()
        fixture.theme.receiptTransform = { row -> row.remove("user"); row.remove("base"); row }
        val error = failure { fixture.service.reset() }
        assertEquals(NativePersonalizationReset.Stage.ENGINE_UNCONFIRMED, error.stage)
        assertEquals(0, fixture.preferences.commits)
    }

    @Test fun inheritedSystemIsValidWhenTheEditorRemovesARedundantUserOverride() = runBlocking {
        val fixture = Fixture(); fixture.theme.collapseOverride = true
        fixture.service.reset()
        assertEquals("system", fixture.theme.preference); assertNull(fixture.theme.user)
        assertEquals(1, fixture.preferences.commits)
        assertEquals("presentation", fixture.events.last())
    }

    @Test fun aFailedLocalCommitKeepsTheConfirmedEngineChangeAndReportsPartialCompletion() = runBlocking {
        val fixture = Fixture(); fixture.preferences.acceptCommit = false
        val error = failure { fixture.service.reset() }
        assertEquals(NativePersonalizationReset.Stage.LOCAL_UNCONFIRMED, error.stage)
        assertTrue(error.engineThemeConfirmed); assertFalse(error.localCommitConfirmed)
        assertEquals("system", fixture.theme.preference)
        assertEquals(1, fixture.preferences.commits); assertFalse(fixture.events.contains("presentation"))
        fixture.preferences.acceptCommit = true
        fixture.service.reset()
        assertEquals(2, fixture.preferences.commits); assertEquals("presentation", fixture.events.last())
    }

    @Test fun anInterveningLocalEditCannotBeReportedAsAConfirmedReset() = runBlocking {
        val fixture = Fixture(); fixture.preferences.afterCommit = { fixture.preferences.values["themePreference"] = "dark" }
        val error = failure { fixture.service.reset() }
        assertEquals(NativePersonalizationReset.Stage.LOCAL_UNCONFIRMED, error.stage)
        assertEquals("dark", fixture.preferences.values["themePreference"])
        assertFalse(fixture.events.contains("presentation"))
    }

    @Test fun aPresentationFailureRetainsBothCommittedConfigurationsAndRequiresExplicitRetry() = runBlocking {
        val fixture = Fixture(); fixture.presentationFailure = true
        val error = failure { fixture.service.reset() }
        assertEquals(NativePersonalizationReset.Stage.PRESENTATION_UNCONFIRMED, error.stage)
        assertTrue(error.engineThemeConfirmed); assertTrue(error.localCommitConfirmed)
        assertEquals("system", fixture.preferences.values["themePreference"])
        assertEquals(1, fixture.theme.mutations.size); assertEquals(1, fixture.preferences.commits)
        fixture.presentationFailure = false
        fixture.service.reset()
        assertEquals(2, fixture.preferences.commits)
    }

    @Test fun cancellationBeforeReadbackCannotClearLocalPreferencesOrResumeTheOldReset() = runBlocking {
        val fixture = Fixture(); fixture.theme.readbackGate = CompletableDeferred()
        val job = launch { fixture.service.reset() }; yield()
        assertEquals(1, fixture.theme.mutations.size); assertEquals(0, fixture.preferences.commits)
        job.cancelAndJoin(); fixture.theme.readbackGate!!.complete(Unit); yield()
        assertEquals(0, fixture.preferences.commits); assertFalse(fixture.events.contains("presentation"))
        fixture.theme.readbackGate = null
        fixture.service.reset()
        assertEquals(1, fixture.preferences.commits)
    }

    @Test fun malformedRevisionsAndUnavailableThemeNamespacesFailBeforeLocalWrites() = runBlocking {
        for (variant in listOf("readonly", "missing", "string-revision")) {
            val fixture = Fixture()
            fixture.theme.describeTransform = { root ->
                when (variant) {
                    "readonly" -> root.put("writable", false)
                    "missing" -> root.put("namespaces", JSONArray())
                    else -> { root.getJSONArray("namespaces").getJSONObject(0).put("revision", "7"); root }
                }
            }
            failure { fixture.service.reset() }
            assertEquals(0, fixture.preferences.commits); assertTrue(fixture.theme.mutations.isEmpty())
        }
    }

    private suspend fun failure(action: suspend () -> Unit): NativePersonalizationReset.Failure {
        try { action(); throw AssertionError("Expected a partial or rejected reset") }
        catch (failure: NativePersonalizationReset.Failure) { return failure }
    }

    private class Fixture {
        val events = mutableListOf<String>()
        val preferences = Preferences(events)
        val theme = ThemeGateway(events)
        var ready = true
        var presentationFailure = false
        val service = NativePersonalizationReset(preferences.instance, theme, { ready }, {
            events.add("presentation"); if (presentationFailure) throw IllegalStateException("fixture")
        }, Dispatchers.Unconfined)
    }

    private class ThemeGateway(private val events: MutableList<String>) : PersonalizationThemeGateway {
        var revision = 7L
        var preference = "dark"
        var user: String? = "dark"
        var collapseOverride = false
        var mutationFailure: Exception? = null
        var applyBeforeFailure = false
        var readbackGate: CompletableDeferred<Unit>? = null
        var describeTransform: (JSONObject) -> JSONObject = { it }
        var readbackTransform: (JSONObject) -> JSONObject = { it }
        var receiptTransform: (JSONObject) -> JSONObject = { it }
        val mutations = mutableListOf<JSONObject>()
        private var reads = 0
        private fun row() = JSONObject().put("ns", "ui-theme").put("revision", revision)
            .put("value", JSONObject().put("preference", preference)).put("base", JSONObject().put("preference", "system"))
            .put("user", JSONObject().apply { user?.let { put("preference", it) } })
        override suspend fun describe(): JSONObject {
            events.add("describe"); reads++
            if (reads > 1) readbackGate?.await()
            val value = describeTransform(JSONObject().put("writable", true).put("namespaces", JSONArray().put(row())))
            return if (reads > 1) readbackTransform(value) else value
        }
        override suspend fun mutate(body: JSONObject): JSONObject {
            events.add("mutate"); mutations.add(JSONObject(body.toString()))
            if (mutationFailure == null || applyBeforeFailure) { preference = "system"; user = if (collapseOverride) null else "system"; revision++ }
            mutationFailure?.let { throw it }
            return receiptTransform(row())
        }
    }

    private class Preferences(private val events: MutableList<String>) {
        val values = mutableMapOf<String, Any>(
            "assistantName" to "Fixture", "browserEncryptedDns" to true, "darkTheme" to true,
            "brandSidebarLabel" to "Fixture", "brandHeroBadge" to "Fixture", "brandWelcomeTemplate" to "Hello {name}",
            "brandComposerTemplate" to "Ask {name}", "brandLogoBackgrounds" to "{}", "themeColorScheme" to "fixture",
            "themeBackgroundColor" to "#FFFFFF", "themePreference" to "dark", "resetTheme" to "system",
            "petName" to "Fixture companion", "petId" to "fixture-pet", "petEnabled" to true, "petInternalDisplay" to "always", "petExternalDisplay" to "adaptive",
            "allowRoot" to true, "allowControl" to false, "toolsStopped" to true, "apiKeyEnv" to "FIXTURE_KEY",
            "history" to "fixture-history", "automationScreenDisplay" to false, "returnToDroyOnCompletion" to true,
        )
        var acceptCommit = true
        var commits = 0
        var afterCommit: () -> Unit = {}
        val removals = mutableListOf<Set<String>>()
        val instance: SharedPreferences = Proxy.newProxyInstance(SharedPreferences::class.java.classLoader, arrayOf(SharedPreferences::class.java)) { _, method, args ->
            when (method.name) {
                "getString" -> values[args!![0]] as? String ?: args[1]
                "contains" -> values.containsKey(args!![0])
                "edit" -> editor()
                else -> throw AssertionError("Unexpected preference read: ${method.name}")
            }
        } as SharedPreferences
        private fun editor(): SharedPreferences.Editor {
            val removed = mutableSetOf<String>(); val pending = mutableMapOf<String, Any>()
            lateinit var editor: SharedPreferences.Editor
            editor = Proxy.newProxyInstance(SharedPreferences.Editor::class.java.classLoader, arrayOf(SharedPreferences.Editor::class.java)) { _, method, args ->
                when (method.name) {
                    "remove" -> { removed.add(args!![0] as String); editor }
                    "putString" -> { pending[args!![0] as String] = args[1] as String; editor }
                    "commit" -> {
                        events.add("commit"); commits++; removals.add(removed.toSet())
                        // Android may update its memory view even when durable commit reports failure.
                        for (key in removed) values.remove(key)
                        values.putAll(pending); afterCommit(); acceptCommit
                    }
                    else -> throw AssertionError("Unexpected preference mutation: ${method.name}")
                }
            } as SharedPreferences.Editor
            return editor
        }
    }
}
