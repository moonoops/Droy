package io.droyagent.app

import android.content.res.Configuration
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.text.AnnotatedString
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.droyagent.app.nativeui.companion.data.NativeCompanionViewModel
import io.droyagent.app.nativeui.data.NativeApiException
import io.droyagent.app.nativeui.data.NativeWorkbenchViewModel
import io.droyagent.app.nativeui.settings.NativeSettingsViewModel
import io.droyagent.app.nativeui.workspaces.NativeWorkspacesAction
import io.droyagent.app.nativeui.workspaces.NativeWorkspacesViewModel
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

/** Real authenticated Host operations; prompts are answered only by the fixture's local provider. */
@RunWith(AndroidJUnit4::class)
class NativeWorkspacesDeviceTest {
    @get:Rule(order = 0) val compose = createEmptyComposeRule()
    @get:Rule(order = 1) val fixture = NativeIsolatedRuntimeFixture()
    @get:Rule(order = 2) val failureEvidence = object : TestWatcher() {
        override fun failed(failure: Throwable, description: Description) {
            val name = "native-spaces-failed-${description.methodName}"
            try { fixture.capture(name) } catch (diagnostic: Throwable) { failure.addSuppressed(diagnostic) }
            try {
                File(fixture.context.getExternalFilesDir(null), "native-isolated-evidence/$name.txt")
                    .writeText(compose.onRoot(useUnmergedTree = true).printToString())
            } catch (diagnostic: Throwable) { failure.addSuppressed(diagnostic) }
        }
    }
    private lateinit var work: NativeWorkbenchViewModel
    private lateinit var spaces: NativeWorkspacesViewModel
    private lateinit var settings: NativeSettingsViewModel
    private lateinit var companions: NativeCompanionViewModel
    private var language = "en"

    @Test fun spacesKeepTheirStackAndManageRealConversationsInEnglishAndChinese() {
        startModels()
        val current = registerFixtureSpace("current")
        val other = registerFixtureSpace("other")
        val otherSession = createSession(other, "other")
        val source = createSession(current, "history")
        select(source)
        val marker = "NATIVE_SPACES_COMPLETED_HISTORY"
        sendCompleted(marker)
        for (locale in listOf("en", "zh")) {
            setLanguage(locale)
            openSpaces()
            assertExpanded(current, true)
            assertExpanded(other, false)
            spaces.state.value.catalog!!.defaultWorkspaceId?.takeIf { it != current }?.let { assertExpanded(it, false) }
            toggleSpace(other)
            assertExpanded(other, true)
            scrollTo("native-space-session:$otherSession")
            compose.onNodeWithTag("native-space-session:$otherSession").assertIsDisplayed()
            scrollTo("native-space-session:$source")
            compose.onNodeWithTag("native-space-session:$source").performClick()
            awaitHistory(source, marker)
            compose.onNodeWithTag("native-tab-workbench").assertDoesNotExist()
            fixture.capture("native-spaces-history-child-$locale")
            backToSpaces()
            assertExpanded(other, true)
            assertExpanded(current, true)
            fixture.recreate(); models()
            awaitSpaces()
            assertExpanded(other, true)

            sessionMenu(source, if (locale == "en") R.string.native_workspaces_pin else R.string.native_workspaces_unpin)
            compose.waitUntil(20_000) { (source in spaces.state.value.catalog!!.pinnedSessionIds) == (locale == "en") && !spaces.state.value.busy }
            val title = "M6 $locale conversation"
            sessionMenu(source, R.string.native_workspaces_rename_session)
            compose.onNodeWithTag("native-workspaces-editor-text").performTextReplacement(title)
            compose.onNodeWithTag("native-workspaces-editor-save").performClick()
            compose.waitUntil(20_000) { spaces.state.value.editor == null && !spaces.state.value.busy && spaces.state.value.catalog!!.sessions.any { it.id == source && it.title == title } }
            sessionMenu(source, R.string.native_workspaces_fork)
            compose.onNodeWithTag("native-workspaces-confirm-fork").assertIsDisplayed().performClick()
            try {
                compose.waitUntil(30_000) { work.state.value.selectedSessionId?.let { it != source && it != otherSession } == true
                    && work.state.value.selectedWorkspaceId == current
                    && work.state.value.messages.any { it.role == "user" && it.text == marker } && !work.state.value.loading }
            } catch (error: Throwable) { throw AssertionError("Fork child did not settle in its source space: ${forkState(current)}", error) }
            val child = requireNotNull(work.state.value.selectedSessionId)
            assertEquals(current, work.state.value.selectedWorkspaceId)
            assertEquals(1, work.state.value.messages.count { it.role == "user" && it.text == marker })
            assertTrue(catalog().getJSONArray("sessions").objects().any { it.getString("id") == child && !it.getBoolean("readOnly") })
            fixture.capture("native-spaces-fork-child-$locale")

            // Leave a real running turn, then archive through the separate spaces page.
            val hold = "NATIVE_HOLD_M6_$locale"
            fixture.scenario.onActivity { work.setDraft(hold) }
            compose.onNodeWithTag("native-send").performClick()
            compose.waitUntil(30_000) { work.state.value.running && work.state.value.messages.any { it.role == "assistant" && it.text.contains("Local hold started") } }
            compose.onNodeWithTag("native-new-session").assertIsEnabled().performClick()
            compose.waitUntil(10_000) { work.state.value.selectedSessionId == null }
            openSpacesDirectly()
            sessionMenu(child, R.string.native_workspaces_archive)
            compose.waitUntil(20_000) { spaces.state.value.archiveActiveSessionId == child && !spaces.state.value.busy }
            assertFalse(child in spaces.state.value.catalog!!.archivedSessionIds)
            compose.onNodeWithText(text(R.string.native_workspaces_active_scope)).assertIsDisplayed()
            captureDialog("native-spaces-stop-archive-confirm-$locale")
            compose.onNodeWithTag("native-workspaces-confirm-stop-archive").performClick()
            compose.waitUntil(30_000) { child in spaces.state.value.catalog!!.archivedSessionIds && !spaces.state.value.busy }
            compose.waitUntil(20_000) { File(fixture.home, "native-fixture-hold-aborted.json").jsonOrNull()?.optString("sessionId") == child }
            toggleArchived()
            compose.waitUntil(10_000) { spaces.state.value.showArchived }
            scrollTo("native-session-restore:$child")
            compose.onNodeWithTag("native-session-restore:$child").assertIsDisplayed().performClick()
            compose.waitUntil(20_000) { child !in spaces.state.value.catalog!!.archivedSessionIds && !spaces.state.value.busy }
            toggleArchived()
            scrollTo("native-space-session:$child")
            compose.onNodeWithTag("native-space-session:$child").performClick()
            awaitHistory(child, marker)
            assertTrue(work.state.value.messages.any { it.role == "user" && it.text == hold })
            fixture.capture("native-spaces-restored-history-$locale")
            backToSpaces(); pressBack()
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("native-tab-workbench").fetchSemanticsNodes(false).isNotEmpty() }
            select(source)
        }
        assertEquals("Forking and reopening history must not resend its original prompt", 1,
            fixtureCalls().count { !it.optBoolean("title") && it.optString("prompt") == marker })
    }

    @Test fun directoryRegistrationRenameOrderAndRemovalKeepFilesAndSessionsInBothLanguages() {
        startModels()
        val managedWorkspaceId = materializeCompanionWorkspace()
        for (locale in listOf("en", "zh")) {
            setLanguage(locale)
            openSpaces()
            compose.onNodeWithTag("native-workspaces-add").assertIsEnabled().performClick()
            compose.onNodeWithText(text(R.string.native_workspaces_existing_folder)).assertIsDisplayed().performClick()
            compose.waitUntil(15_000) { spaces.state.value.directoryOpen && !spaces.state.value.directoryLoading }
            // Stay inside the fixture-owned home; browse uses the same public VM action as a directory row.
            fixture.scenario.onActivity { spaces.onAction(NativeWorkspacesAction.BrowseDirectory(fixture.home.absolutePath)) }
            compose.waitUntil(15_000) { sameDirectory(spaces.state.value.directory?.path, fixture.home) && !spaces.state.value.directoryLoading }
            val folderName = "m6-$locale-${UUID.randomUUID().toString().take(8)}"
            compose.onNodeWithTag("native-workspaces-new-folder").performClick()
            compose.onNodeWithTag("native-workspaces-editor-text").performTextReplacement(folderName)
            compose.onNodeWithTag("native-workspaces-editor-save").performClick()
            val folder = File(fixture.home, folderName)
            compose.waitUntil(20_000) { sameDirectory(spaces.state.value.directory?.path, folder) && !spaces.state.value.directoryLoading && !spaces.state.value.busy }
            assertTrue(folder.isDirectory)
            assertEquals("The created folder must stay directly inside the owned fixture home", fixture.home.canonicalFile, folder.canonicalFile.parentFile)
            File(folder, "retained.txt").writeText("Fictional workspace content $locale")
            compose.onNodeWithTag("native-workspaces-register").assertIsEnabled().performClick()
            compose.waitUntil(20_000) { !spaces.state.value.directoryOpen && spaces.state.value.catalog!!.workspaces.any { sameDirectory(it.path, folder) } && !spaces.state.value.busy }
            val created = spaces.state.value.catalog!!.workspaces.single { sameDirectory(it.path, folder) }.id
            workspaceMenu(created, R.string.native_workspaces_rename)
            val title = "M6 $locale edited"
            compose.onNodeWithTag("native-workspaces-editor-text").performTextReplacement(title)
            fixture.recreate(); models(); awaitSpaces()
            assertEquals(created, spaces.state.value.editor!!.targetId)
            compose.onNodeWithTag("native-workspaces-editor-text").assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString(title)))
            captureDialog("native-spaces-editor-recreated-$locale")
            compose.onNodeWithTag("native-workspaces-editor-save").performClick()
            compose.waitUntil(20_000) { spaces.state.value.editor == null && spaces.state.value.catalog!!.workspaces.any { it.id == created && it.title == title } && !spaces.state.value.busy }
            val other = registerFixtureSpace("order-$locale")
            refreshSpaces()
            val before = customOrder()
            assertTrue(before.indexOf(created) > before.indexOf(other))
            workspaceMenu(created, R.string.native_workspaces_move_up)
            compose.waitUntil(20_000) { !spaces.state.value.busy && customOrder().indexOf(created) < customOrder().indexOf(other) }
            val retained = createSession(created, "retained-$locale")
            refreshSpaces()
            if (!spaceExpanded(created)) toggleSpace(created)
            scrollTo("native-space-session:$retained")
            compose.onNodeWithTag("native-space-session:$retained").assertIsDisplayed()
            workspaceMenu(created, R.string.native_workspaces_remove)
            compose.onNodeWithText(text(R.string.native_workspaces_remove_scope)).assertIsDisplayed()
            compose.onNodeWithTag("native-workspaces-confirm-remove").performClick()
            compose.waitUntil(20_000) { spaces.state.value.catalog!!.workspaces.none { it.id == created } && !spaces.state.value.busy }
            assertEquals("Fictional workspace content $locale", File(folder, "retained.txt").readText())
            assertTrue(spaces.state.value.catalog!!.sessions.any { it.id == retained && it.workspaceId == null })
            if (!spaceExpanded("ungrouped")) toggleSpace("ungrouped")
            scrollTo("native-space-session:$retained")
            fixture.capture("native-spaces-removed-retains-session-$locale")
            compose.onNodeWithTag("native-space-session:$retained").performClick()
            compose.waitUntil(20_000) { work.state.value.selectedSessionId == retained && !work.state.value.loading }
            assertNull(work.state.value.error)
            backToSpaces()
            compose.waitUntil(20_000) { managedWorkspaceId in spaces.state.value.catalog!!.managedWorkspaceIds }
            assertTrue(spaces.state.value.catalog!!.workspaces.none { it.id == managedWorkspaceId })
            compose.onNodeWithTag("native-space:$managedWorkspaceId").assertDoesNotExist()
            expectError("COMPANION_WORKSPACE_MANAGED") { mutate("workspace.rename", JSONObject().put("workspaceId", managedWorkspaceId).put("title", "Forbidden fixture edit")) }
            assertEquals(managedWorkspaceId, companions.state.value.selectedCompanion!!.workspaceId)
            pressBack()
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("native-tab-workbench").fetchSemanticsNodes(false).isNotEmpty() }
        }
    }

    @Test fun newFromRealHistoryRestoresTheSpacesBlankDraftUntilAnExplicitDiscard() {
        startModels()
        val space = requireNotNull(work.state.value.selectedWorkspaceId)
        val draftA = work.state.value.draftId
        fixture.scenario.onActivity { work.setDraft("NATIVE_BLANK_A_UNSENT") }
        val real = createSession(space, "real-r")
        val before = ordinarySessionIds()
        fixture.scenario.onActivity { work.refresh() }
        compose.waitUntil(20_000) { work.state.value.sessions.any { it.id == real && it.workspaceId == space } }
        openSpaces()
        scrollTo("native-space-session:$real")
        compose.onNodeWithTag("native-space-session:$real").performClick()
        compose.waitUntil(20_000) { work.state.value.selectedSessionId == real && work.state.value.selectedWorkspaceId == space && !work.state.value.loading }
        compose.onNodeWithTag("native-tab-workbench").assertDoesNotExist()
        fixture.scenario.onActivity { work.setDraft("NATIVE_REAL_R_UNSENT") }
        compose.onNodeWithTag("native-new-session").performClick()
        compose.waitUntil(10_000) { work.state.value.selectedSessionId == null && work.state.value.draftId == draftA
            && compose.onAllNodesWithTag("native-tab-workbench").fetchSemanticsNodes(false).isNotEmpty() }
        compose.onNodeWithTag("native-tab-workbench").assertIsDisplayed()
        assertNull(work.state.value.selectedSessionId)
        assertEquals(draftA, work.state.value.draftId)
        compose.onNodeWithTag("native-composer").assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("NATIVE_BLANK_A_UNSENT")))
        assertEquals(before, ordinarySessionIds())
        compose.onNodeWithTag("native-new-session").performClick()
        compose.onNodeWithTag("native-confirm-discard-work-draft").assertIsDisplayed()
        assertEquals(draftA, work.state.value.draftId)
        compose.onNode(hasText(text(R.string.native_companion_cancel)) and hasAnyAncestor(isDialog())).performClick()
        assertEquals("NATIVE_BLANK_A_UNSENT", work.state.value.draft)
        compose.onNodeWithTag("native-new-session").performClick()
        compose.onNodeWithTag("native-confirm-discard-work-draft").performClick()
        compose.waitUntil(10_000) { work.state.value.draftId != draftA && work.state.value.selectedSessionId == null && work.state.value.draft.isEmpty() }
        val blankB = work.state.value.draftId
        assertEquals(before, ordinarySessionIds())
        openSpaces()
        scrollTo("native-space-session:$real")
        compose.onNodeWithTag("native-space-session:$real").performClick()
        compose.waitUntil(20_000) { work.state.value.selectedSessionId == real && work.state.value.selectedWorkspaceId == space && !work.state.value.loading }
        compose.onNodeWithTag("native-tab-workbench").assertDoesNotExist()
        assertEquals("NATIVE_REAL_R_UNSENT", work.state.value.draft)
        compose.onNodeWithTag("native-new-session").performClick()
        compose.waitUntil(10_000) { work.state.value.selectedSessionId == null && work.state.value.draftId == blankB
            && compose.onAllNodesWithTag("native-tab-workbench").fetchSemanticsNodes(false).isNotEmpty() }
        compose.onNodeWithTag("native-tab-workbench").assertIsDisplayed()
        assertTrue(work.state.value.draft.isEmpty())
        fixture.capture("native-spaces-blank-restored-then-explicitly-cleared")
        assertTrue("Local New actions never invoke the provider", fixtureCalls().none { !it.optBoolean("title") })
    }

    @Test fun newAfterMaterializingABlankCreatesBAndPreservesAsContinuationDraft() {
        startModels()
        val space = requireNotNull(work.state.value.selectedWorkspaceId)
        val before = ordinarySessionIds()
        val blankA = work.state.value.draftId
        val markerA = "NATIVE_MATERIALIZED_A_FIRST_SEND"
        sendCompleted(markerA)
        val sessionA = requireNotNull(work.state.value.selectedSessionId)
        assertEquals(sessionA, fixture.context.getSharedPreferences("native_workbench", 0).getString("materialized:$blankA", null))
        val continuation = "NATIVE_MATERIALIZED_A_UNSENT_CONTINUATION"
        fixture.scenario.onActivity { work.setDraft(continuation) }
        openSpacesDirectly()
        scrollTo("native-space-session:$sessionA")
        compose.onNodeWithTag("native-space-session:$sessionA").performClick()
        awaitHistory(sessionA, markerA)
        compose.onNodeWithTag("native-tab-workbench").assertDoesNotExist()
        assertEquals(continuation, work.state.value.draft)
        compose.onNodeWithTag("native-new-session").performClick()
        compose.waitUntil(10_000) { work.state.value.selectedSessionId == null && work.state.value.selectedWorkspaceId == space
            && work.state.value.draftId != blankA && work.state.value.draftId != sessionA && work.state.value.draft.isEmpty()
            && compose.onAllNodesWithTag("native-tab-workbench").fetchSemanticsNodes(false).isNotEmpty() }
        val blankB = work.state.value.draftId
        compose.onNodeWithTag("native-tab-workbench").assertIsDisplayed()
        assertEquals(before + sessionA, ordinarySessionIds())
        val markerB = "NATIVE_MATERIALIZED_B_FIRST_SEND"
        sendCompleted(markerB)
        val sessionB = requireNotNull(work.state.value.selectedSessionId)
        assertNotEquals(sessionA, sessionB)
        assertEquals(sessionB, fixture.context.getSharedPreferences("native_workbench", 0).getString("materialized:$blankB", null))
        assertEquals(before + setOf(sessionA, sessionB), ordinarySessionIds())
        assertFalse("B must start its own history", work.state.value.messages.any { it.role == "user" && it.text == markerA })
        openSpacesDirectly()
        scrollTo("native-space-session:$sessionA")
        compose.onNodeWithTag("native-space-session:$sessionA").performClick()
        awaitHistory(sessionA, markerA)
        assertEquals(continuation, work.state.value.draft)
        assertFalse("Sending B must not append to A", work.state.value.messages.any { it.role == "user" && it.text == markerB })
        val callsA = fixtureCalls().filter { !it.optBoolean("title") && it.optString("prompt") == markerA }
        val callsB = fixtureCalls().filter { !it.optBoolean("title") && it.optString("prompt") == markerB }
        assertEquals(1, callsA.size); assertEquals(sessionA, callsA.single().getString("sessionId"))
        assertEquals(1, callsB.size); assertEquals(sessionB, callsB.single().getString("sessionId"))
        fixture.capture("native-spaces-materialized-a-kept-separate-from-b")
    }

    @Test fun removingASpaceRequiresReviewAndExplicitDiscardOfItsUnsentDraft() {
        startModels()
        val defaultSpace = requireNotNull(work.state.value.selectedWorkspaceId)
        val defaultDraft = work.state.value.draftId
        val otherDraftText = "NATIVE_OTHER_SPACE_UNSENT"
        fixture.scenario.onActivity { work.setDraft(otherDraftText) }
        val space = registerFixtureSpace("draft-protection")
        val folder = File(catalog().getJSONArray("workspaces").objects().single { it.getString("id") == space }.getString("path"))
        assertEquals(fixture.home.canonicalFile, folder.canonicalFile.parentFile)
        val retained = File(folder, "retained.txt").apply { writeText("Fictional protected workspace file") }
        val history = createSession(space, "protected-history")
        select(history)
        val marker = "NATIVE_REMOVED_SPACE_RETAINED_HISTORY"
        sendCompleted(marker)
        compose.onNodeWithTag("native-new-session").performClick()
        compose.waitUntil(10_000) { work.state.value.selectedSessionId == null && work.state.value.selectedWorkspaceId == space }
        val draftA = work.state.value.draftId
        val unsent = "NATIVE_REMOVED_SPACE_A_UNSENT"
        fixture.scenario.onActivity { work.setDraft(unsent) }
        val sessionsBefore = ordinarySessionIds()
        val removalsBefore = operationReceipts("workspace.remove")
        openSpacesDirectly()
        workspaceMenu(space, R.string.native_workspaces_remove)
        compose.onNodeWithTag("native-workspaces-draft-blocker").assertIsDisplayed()
        compose.onNodeWithTag("native-workspaces-confirm-remove").assertDoesNotExist()
        assertEquals(removalsBefore, operationReceipts("workspace.remove"))
        assertTrue(catalog().getJSONArray("workspaces").objects().any { it.getString("id") == space })
        compose.onNode(hasText(text(R.string.native_companion_cancel)) and hasAnyAncestor(isDialog())).performClick()
        compose.onNodeWithTag("native-workspaces-draft-blocker").assertDoesNotExist()
        assertEquals(draftA, work.state.value.draftId); assertEquals(unsent, work.state.value.draft)
        assertEquals(removalsBefore, operationReceipts("workspace.remove"))
        workspaceMenu(space, R.string.native_workspaces_remove)
        compose.onNodeWithTag("native-workspaces-draft-blocker").assertIsDisplayed()
        captureDialog("native-spaces-remove-blocked-by-unsent-draft")
        compose.onNodeWithTag("native-workspaces-open-draft").performClick()
        compose.waitUntil(10_000) { work.state.value.selectedSessionId == null && work.state.value.selectedWorkspaceId == space
            && work.state.value.draftId == draftA && compose.onAllNodesWithTag("native-tab-workbench").fetchSemanticsNodes(false).isNotEmpty() }
        compose.onNodeWithTag("native-composer").assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString(unsent)))
        assertEquals(removalsBefore, operationReceipts("workspace.remove"))
        assertEquals(sessionsBefore, ordinarySessionIds())
        compose.onNodeWithTag("native-new-session").performClick()
        compose.onNodeWithTag("native-confirm-discard-work-draft").assertIsDisplayed()
        assertEquals(unsent, work.state.value.draft)
        compose.onNodeWithTag("native-confirm-discard-work-draft").performClick()
        compose.waitUntil(10_000) { work.state.value.selectedSessionId == null && work.state.value.draftId != draftA && work.state.value.draft.isEmpty() }
        openSpacesDirectly()
        workspaceMenu(space, R.string.native_workspaces_remove)
        compose.onNodeWithTag("native-workspaces-draft-blocker").assertDoesNotExist()
        compose.onNodeWithTag("native-workspaces-confirm-remove").assertIsDisplayed().performClick()
        compose.waitUntil(20_000) { spaces.state.value.catalog!!.workspaces.none { it.id == space } && !spaces.state.value.busy
            && work.state.value.workspaces.none { it.id == space } && work.state.value.selectedSessionId == null && work.state.value.selectedWorkspaceId == null }
        assertEquals(removalsBefore.size + 1, operationReceipts("workspace.remove").size)
        assertEquals("Fictional protected workspace file", retained.readText())
        assertEquals(sessionsBefore, ordinarySessionIds())
        assertTrue(catalog().getJSONArray("sessions").objects().any { it.getString("id") == history && it.isNull("workspaceId") })
        pressBack()
        compose.onNodeWithTag("native-tab-workbench").assertIsDisplayed()
        assertNull(work.state.value.selectedWorkspaceId)
        openSpacesDirectly()
        if (!spaceExpanded("ungrouped")) toggleSpace("ungrouped")
        scrollTo("native-space-session:$history")
        compose.onNodeWithTag("native-space-session:$history").performClick()
        awaitHistory(history, marker)
        assertNull(work.state.value.selectedWorkspaceId)
        backToSpaces()
        scrollTo("native-space-new:$defaultSpace")
        compose.onNodeWithTag("native-space-new:$defaultSpace").performClick()
        compose.waitUntil(10_000) { work.state.value.selectedSessionId == null && work.state.value.selectedWorkspaceId == defaultSpace
            && work.state.value.draftId == defaultDraft && compose.onAllNodesWithTag("native-tab-workbench").fetchSemanticsNodes(false).isNotEmpty() }
        assertEquals(otherDraftText, work.state.value.draft)
        assertEquals(1, fixtureCalls().count { !it.optBoolean("title") && it.optString("prompt") == marker })
        fixture.capture("native-spaces-removed-keeps-history-and-other-draft")
    }

    @Test fun immediateNewFromTheOpenedForkKeepsItsKnownSpaceWithoutCreatingASession() {
        startModels()
        val space = registerFixtureSpace("fork-immediate-new")
        val source = createSession(space, "fork-immediate-source")
        select(source)
        val marker = "NATIVE_IMMEDIATE_FORK_SOURCE"
        sendCompleted(marker)
        val before = ordinarySessionIds()
        val forksBefore = operationReceipts("session.fork")
        openSpacesDirectly()
        sessionMenu(source, R.string.native_workspaces_fork)
        compose.onNodeWithTag("native-workspaces-confirm-fork").performClick()
        compose.waitUntil(30_000) { work.state.value.selectedSessionId?.let { it != source } == true
            && compose.onAllNodesWithTag("native-workbench").fetchSemanticsNodes(false).isNotEmpty() }
        val child = requireNotNull(work.state.value.selectedSessionId)
        // Exercise the first available New action without waiting for a metadata/history refresh.
        compose.onNodeWithTag("native-new-session").assertIsEnabled().performClick()
        try {
            compose.waitUntil(10_000) { work.state.value.selectedSessionId == null && work.state.value.selectedWorkspaceId == space
                && compose.onAllNodesWithTag("native-tab-workbench").fetchSemanticsNodes(false).isNotEmpty() }
        } catch (error: Throwable) { throw AssertionError("Immediate New lost the fork's known space: ${forkState(space)}", error) }
        compose.onNodeWithTag("native-tab-workbench").assertIsDisplayed()
        assertTrue(work.state.value.draft.isEmpty())
        assertEquals(before + child, ordinarySessionIds())
        assertEquals(forksBefore.size + 1, operationReceipts("session.fork").size)
        assertTrue(catalog().getJSONArray("sessions").objects().any { it.getString("id") == child && it.optString("workspaceId") == space })
        assertEquals(1, fixtureCalls().count { !it.optBoolean("title") && it.optString("prompt") == marker })
        fixture.capture("native-spaces-immediate-fork-new-keeps-space")
    }

    @Test fun backToAnArchivedConversationPreservesItsDraftAndBlocksSendingUntilRestored() {
        startModels()
        val space = registerFixtureSpace("archive-current")
        val session = createSession(space, "archive-current-history")
        select(session)
        val marker = "NATIVE_ARCHIVED_CURRENT_HISTORY"
        sendCompleted(marker)
        openSpacesDirectly()
        scrollTo("native-space-session:$session")
        compose.onNodeWithTag("native-space-session:$session").performClick()
        awaitHistory(session, marker)
        compose.onNodeWithTag("native-tab-workbench").assertDoesNotExist()
        val draft = "NATIVE_ARCHIVED_CURRENT_UNSENT"
        fixture.scenario.onActivity { work.setDraft(draft) }
        val callsBefore = fixtureCalls().count { !it.optBoolean("title") && it.optString("sessionId") == session }
        val sessionsBefore = ordinarySessionIds()
        openSpacesDirectly()
        assertEquals("Opening Spaces must retain the current real session", session, work.state.value.selectedSessionId)
        sessionMenu(session, R.string.native_workspaces_archive)
        pressBack()
        compose.waitUntil(20_000) { work.state.value.selectedSessionId == session && work.state.value.archived && !work.state.value.loading
            && compose.onAllNodesWithTag("native-workbench").fetchSemanticsNodes(false).isNotEmpty() }
        assertTrue(work.state.value.readOnly)
        assertEquals(space, work.state.value.selectedWorkspaceId)
        assertEquals(draft, work.state.value.draft)
        compose.onNodeWithTag("native-read-only").assertTextEquals(text(R.string.native_workspaces_archived))
        compose.onNodeWithTag("native-composer").assertIsNotEnabled()
        compose.onNodeWithTag("native-send").assertIsNotEnabled()
        compose.onNodeWithTag("native-new-session").assertIsEnabled()
        fixture.scenario.onActivity { work.send() }
        assertEquals("workspace/session-archived", work.state.value.error)
        assertFalse(work.state.value.sending)
        assertNull(work.state.value.pendingRequestId)
        assertEquals(draft, work.state.value.draft)
        assertEquals(sessionsBefore, ordinarySessionIds())
        assertEquals("Direct VM send must not reach the provider for an archived session", callsBefore,
            fixtureCalls().count { !it.optBoolean("title") && it.optString("sessionId") == session })
        fixture.capture("native-spaces-returned-archived-session-is-read-only")
        openSpacesDirectly()
        toggleArchived()
        compose.waitUntil(10_000) { spaces.state.value.showArchived }
        scrollTo("native-session-restore:$session")
        compose.onNodeWithTag("native-session-restore:$session").performClick()
        pressBack()
        compose.waitUntil(20_000) { work.state.value.selectedSessionId == session && !work.state.value.archived && !work.state.value.loading }
        awaitHistory(session, marker)
        assertFalse(work.state.value.readOnly)
        assertNull(work.state.value.error)
        assertEquals(space, work.state.value.selectedWorkspaceId)
        assertEquals(draft, work.state.value.draft)
        compose.onNodeWithTag("native-read-only").assertDoesNotExist()
        compose.onNodeWithTag("native-composer").assertIsEnabled()
        compose.onNodeWithTag("native-send").assertIsEnabled()
        assertEquals(callsBefore, fixtureCalls().count { !it.optBoolean("title") && it.optString("sessionId") == session })
        assertFalse(work.state.value.messages.any { it.role == "user" && it.text == draft })
        fixture.capture("native-spaces-restored-current-session-keeps-draft")
    }

    private fun captureDialog(name: String) {
        // The system dialog window can still be fading after Compose semantics are available.
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        android.os.SystemClock.sleep(400)
        fixture.capture(name)
    }

    private fun startModels() {
        fixture.startRuntime(); models()
        compose.waitUntil(30_000) { work.state.value.runtime.ready && work.state.value.workspaces.isNotEmpty() && settings.state.value.loaded && !settings.state.value.loading }
        compose.onNodeWithTag("native-workbench").assertIsDisplayed()
    }
    private fun models() = fixture.scenario.onActivity {
        work = ViewModelProvider(it)[NativeWorkbenchViewModel::class.java]
        spaces = ViewModelProvider(it)[NativeWorkspacesViewModel::class.java]
        settings = ViewModelProvider(it)[NativeSettingsViewModel::class.java]
        companions = ViewModelProvider(it)[NativeCompanionViewModel::class.java]
    }
    private fun materializeCompanionWorkspace(): String {
        compose.waitUntil(20_000) { companions.state.value.runtime.ready && companions.state.value.companions.any { it.templateId == "kaka" } }
        val id = companions.state.value.companions.first { it.templateId == "kaka" }.id
        fixture.scenario.onActivity { companions.selectCompanion(id) }
        compose.waitUntil(20_000) { companions.state.value.selectedCompanionId == id && !companions.state.value.loading }
        val marker = "NATIVE_SPACES_MANAGED_COMPANION"
        fixture.scenario.onActivity { companions.setDraft(marker); companions.send() }
        compose.waitUntil(45_000) { !companions.state.value.sending
            && companions.state.value.messages.any { it.role == "user" && it.text == marker && it.clientId != null }
            && companions.state.value.messages.any { it.role == "assistant" && it.text == "Native companion reply received." }
            && companions.state.value.status.chatExecution?.quiescent == true }
        assertNull(companions.state.value.error)
        assertNull(companions.state.value.status.error)
        fixture.scenario.onActivity { companions.refresh() }
        compose.waitUntil(20_000) { companions.state.value.selectedCompanion?.workspaceId != null && !companions.state.value.loading }
        val workspaceId = requireNotNull(companions.state.value.selectedCompanion!!.workspaceId)
        val userCatalog = catalog()
        assertTrue("A real companion turn must retain its private workspace ownership",
            (0 until userCatalog.getJSONArray("managedWorkspaceIds").length()).map(userCatalog.getJSONArray("managedWorkspaceIds")::getString).contains(workspaceId))
        assertTrue(userCatalog.getJSONArray("workspaces").objects().none { it.getString("id") == workspaceId })
        assertEquals(1, companions.state.value.messages.count { it.role == "user" && it.text == marker })
        return workspaceId
    }
    private fun setLanguage(locale: String) {
        language = locale
        fixture.scenario.onActivity { settings.setLanguage(locale) }
        compose.waitUntil(20_000) { !settings.state.value.saving && settings.state.value.locale == locale && work.state.value.runtime.locale == locale }
        assertNull(settings.state.value.error)
    }
    private fun select(id: String) {
        fixture.scenario.onActivity { work.refresh() }
        compose.waitUntil(20_000) { work.state.value.sessions.any { it.id == id } }
        fixture.scenario.onActivity { work.selectSession(id) }
        compose.waitUntil(20_000) { work.state.value.selectedSessionId == id && !work.state.value.loading }
    }
    private fun openSpaces() {
        if (work.state.value.selectedSessionId != null) {
            compose.onNodeWithTag("native-new-session").assertIsEnabled().performClick()
            compose.waitUntil(10_000) { work.state.value.selectedSessionId == null }
        }
        openSpacesDirectly()
    }
    private fun openSpacesDirectly() {
        compose.onNodeWithTag("native-space-switch").assertIsDisplayed().assertIsEnabled()
            .assert(hasContentDescription(text(R.string.native_spaces_chats))).performClick()
        awaitSpaces()
    }
    private fun toggleArchived() {
        compose.onNode(hasContentDescription(text(R.string.native_workbench_menu)) and hasAnyAncestor(hasTestTag("native-workspaces")))
            .assertIsDisplayed().assertIsEnabled().performClick()
        compose.onNodeWithTag("native-workspaces-archived").assertIsDisplayed().assertIsEnabled().performClick()
    }
    private fun awaitSpaces() {
        compose.waitUntil(20_000) { compose.onAllNodesWithTag("native-workspaces").fetchSemanticsNodes(false).isNotEmpty()
            && spaces.state.value.catalog != null && !spaces.state.value.loading && !spaces.state.value.busy }
        compose.onNodeWithTag("native-workspaces").assertIsDisplayed()
    }
    private fun pressBack() = fixture.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
    private fun backToSpaces() { pressBack(); awaitSpaces() }
    private fun refreshSpaces() {
        fixture.scenario.onActivity { spaces.refresh() }
        compose.waitUntil(20_000) { !spaces.state.value.loading && !spaces.state.value.busy }
    }
    private fun sendCompleted(marker: String) {
        fixture.scenario.onActivity { work.setDraft(marker) }
        compose.onNodeWithTag("native-send").assertIsEnabled().performClick()
        compose.waitUntil(45_000) { !work.state.value.running && !work.state.value.sending
            && work.state.value.messages.any { it.role == "assistant" && it.text == "Native DSH reply received." && !it.streaming } }
        assertNull(work.state.value.error)
    }
    private fun awaitHistory(id: String, marker: String) {
        compose.waitUntil(20_000) { work.state.value.selectedSessionId == id && !work.state.value.loading && work.state.value.messages.any { it.role == "user" && it.text == marker } }
        assertNull(work.state.value.error)
        compose.onNodeWithTag("native-history").assertIsDisplayed()
    }
    private fun scrollTo(tag: String) { compose.onNodeWithTag("native-spaces-chats").performScrollToNode(hasTestTag(tag)) }
    private fun spaceState(id: String, expanded: Boolean): SemanticsMatcher = (hasTestTag("native-space:$id") or hasAnyAncestor(hasTestTag("native-space:$id"))) and
        SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, text(if (expanded) R.string.native_workspaces_expanded else R.string.native_workspaces_collapsed))
    private fun spaceExpanded(id: String): Boolean { scrollTo("native-space:$id"); return compose.onAllNodes(spaceState(id, true)).fetchSemanticsNodes(false).isNotEmpty() }
    private fun assertExpanded(id: String, expanded: Boolean) { scrollTo("native-space:$id"); compose.onNode(spaceState(id, expanded)).assertExists() }
    private fun toggleSpace(id: String) {
        scrollTo("native-space:$id"); compose.onNode(spaceState(id, true) or spaceState(id, false)).performClick()
    }
    private fun sessionMenu(id: String, label: Int) {
        scrollTo("native-session-menu:$id"); compose.onNodeWithTag("native-session-menu:$id").performClick(); compose.onNodeWithText(text(label)).performClick()
    }
    private fun workspaceMenu(id: String, label: Int) {
        scrollTo("native-workspace-menu:$id"); compose.onNodeWithTag("native-workspace-menu:$id").performClick(); compose.onNodeWithText(text(label)).performClick()
    }
    private fun customOrder() = spaces.state.value.catalog!!.let { catalog -> catalog.workspaces.filter { !it.readOnly && it.id != catalog.defaultWorkspaceId }.map { it.id } }
    private fun sameDirectory(path: String?, expected: File) = path != null && File(path).canonicalFile == expected.canonicalFile
    private fun operationReceipts(kind: String): Set<String> = File(fixture.home, "droy-native-workspaces/receipts").listFiles().orEmpty()
        .filter { it.isFile && it.extension == "json" }.mapNotNull { it.jsonOrNull() }.filter { it.optString("kind") == kind }
        .map { it.getString("requestId") }.toSet()
    private fun forkState(expectedWorkspaceId: String): JSONObject {
        val current = work.state.value
        val selected = current.selectedSessionId
        val screen = spaces.state.value.catalog
        val server = runCatching { catalog() }.getOrNull()
        val serverSession = server?.optJSONArray("sessions")?.objects()?.firstOrNull { it.optString("id") == selected }
        return JSONObject().put("expectedWorkspaceId", expectedWorkspaceId).put("selectedSessionId", selected)
            .put("selectedWorkspaceId", current.selectedWorkspaceId).put("loading", current.loading).put("errorCode", current.error)
            .put("vmSessionWorkspaceId", current.sessions.firstOrNull { it.id == selected }?.workspaceId)
            .put("screenSessionWorkspaceId", screen?.sessions?.firstOrNull { it.id == selected }?.workspaceId)
            .put("serverSessionWorkspaceId", serverSession?.opt("workspaceId"))
            .put("expectedSpaceRegistered", server?.optJSONArray("workspaces")?.objects()?.any { it.optString("id") == expectedWorkspaceId })
    }
    private fun registerFixtureSpace(name: String): String {
        val directory = File(fixture.home, "m6-$name-${UUID.randomUUID()}").apply { assertTrue(mkdir()) }
        return mutate("workspace.register", JSONObject().put("path", directory.absolutePath)).getString("workspaceId")
    }
    private fun createSession(space: String, label: String): String {
        val id = "native-m6-$label-${UUID.randomUUID()}"
        val result = native("/droy/native/session/create", JSONObject().put("sessionId", id).put("workspaceId", space))
        assertEquals(id, result.getString("sessionId")); return id
    }
    private fun ordinarySessionIds(): Set<String> {
        val result = native("/droy/native/session/list", JSONObject())
        val managed = result.getJSONArray("managedSessionIds").strings().toSet()
        return result.getJSONArray("items").objects().map { it.getString("sessionId") }.filterNot { it in managed }.toSet()
    }
    private fun mutate(kind: String, args: JSONObject): JSONObject {
        val receipt = native("/droy/native/workspaces/mutate", JSONObject().put("requestId", UUID.randomUUID().toString()).put("kind", kind).put("args", args))
        assertEquals("The real SDK must confirm this fixture mutation", "complete", receipt.getString("state")); return receipt
    }
    private fun catalog() = native("/droy/native/workspaces")
    private fun native(path: String, body: JSONObject? = null): JSONObject = runBlocking {
        val envelope = fixture.runtimeTransport.json(path, if (body == null) "GET" else "POST", body)
        assertTrue(envelope.getBoolean("ok")); envelope.getJSONObject("result")
    }
    private fun expectError(code: String, action: () -> Unit) {
        try { action(); fail("Expected the server to reject the managed target") }
        catch (error: NativeApiException) { assertEquals(code, error.code) }
    }
    private fun text(resource: Int): String {
        val config = Configuration(fixture.context.resources.configuration).apply { setLocale(Locale.forLanguageTag(language)) }
        return fixture.context.createConfigurationContext(config).getString(resource)
    }
    private fun File.jsonOrNull(): JSONObject? = if (!isFile) null else runCatching { JSONObject(readText()) }.getOrNull()
    private fun fixtureCalls(): List<JSONObject> = File(fixture.home, "native-fixture-calls.jsonl").takeIf { it.isFile }?.readLines()
        ?.filter { it.isNotBlank() }?.mapNotNull { runCatching { JSONObject(it) }.getOrNull() }.orEmpty()
    private fun JSONArray.objects() = (0 until length()).map(::getJSONObject)
    private fun JSONArray.strings() = (0 until length()).map(::getString)
}
