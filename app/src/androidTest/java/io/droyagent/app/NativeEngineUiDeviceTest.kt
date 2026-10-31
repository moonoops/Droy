package io.droyagent.app

import android.content.res.Configuration
import android.accessibilityservice.AccessibilityService
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.droyagent.app.nativeui.data.NativeRuntimeState
import io.droyagent.app.nativeui.engine.*
import io.droyagent.app.nativeui.ui.DroyTheme
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Synthetic presentation only; the shared fixture isolates and restores app data without starting its engine. */
@RunWith(AndroidJUnit4::class)
class NativeEngineUiDeviceTest {
    @get:Rule(order = 0) val compose = createEmptyComposeRule()
    @get:Rule(order = 1) val fixture = NativeIsolatedRuntimeFixture()

    @Test fun englishPresetDetailsDefaultAndCreatorHaveSeparateActions() = presetPage("en")
    @Test fun chinesePresetDetailsDefaultAndCreatorHaveSeparateActions() = presetPage("zh")

    private fun presetPage(language: String) {
        val state = mutableStateOf(fake(language))
        val actions = mutableListOf<NativeEngineAction>()
        val creator = AtomicReference<String?>(); val back = AtomicInteger()
        render(language) {
            NativeEngineSettingsScreen(state.value, { action ->
                actions.add(action)
                if (action is NativeEngineAction.ViewPreset) state.value = state.value.copy(document = NativePresetDocument(action.id, "- name: fixture-plugin"))
                if (action == NativeEngineAction.CloseDocument) state.value = state.value.copy(document = null)
            }, onBack = { back.incrementAndGet() }, onCreatePreset = { creator.set(it) })
        }
        compose.onNodeWithText(copy(language, R.string.native_engine_make_default)).performClick()
        compose.runOnIdle { assertEquals(NativeEngineAction.DefaultPreset("cordis"), actions.last()) }
        compose.onNodeWithText(copy(language, R.string.native_engine_create)).performScrollTo().performClick()
        assertEquals("cordis", creator.get())
        compose.onAllNodesWithText(copy(language, R.string.native_engine_details)).onFirst().performScrollTo().performClick()
        compose.onNodeWithTag("native-engine-preset-document").assertIsDisplayed()
        systemBack()
        compose.onNodeWithTag("native-engine-preset-document").assertDoesNotExist()
        assertEquals(0, back.get())
        compose.onNodeWithContentDescription(copy(language, R.string.native_engine_back)).performClick()
        assertEquals(1, back.get())
        assertTrue(actions.none { it is NativeEngineAction.SessionPreset || it is NativeEngineAction.SessionPermission })
        fixture.capture("native-engine-presets-$language")
    }

    @Test fun englishSessionPermissionNeedsRiskAcknowledgementAndDoesNotWriteADefault() = sessionPermission("en")
    @Test fun chineseSessionPermissionNeedsRiskAcknowledgementAndDoesNotWriteADefault() = sessionPermission("zh")

    private fun sessionPermission(language: String) {
        val actions = mutableListOf<NativeEngineAction>()
        render(language) { NativeEngineSessionSelector(fake(language), { actions.add(it) }) }
        compose.onNodeWithText(copy(language, R.string.native_engine_workspace_write)).performClick()
        compose.onNodeWithText(copy(language, R.string.native_engine_full_access)).performClick()
        compose.onNodeWithText(copy(language, R.string.native_engine_confirm)).assertIsNotEnabled()
        assertTrue(actions.none { it is NativeEngineAction.SessionPermission })
        compose.onNode(isToggleable()).performClick()
        compose.onNodeWithText(copy(language, R.string.native_engine_confirm)).assertIsEnabled().performClick()
        compose.runOnIdle {
            val action = actions.filterIsInstance<NativeEngineAction.SessionPermission>().single()
            assertEquals("danger-full-access", action.id); assertTrue(action.acceptRisk)
            assertEquals(NativeEngineTarget("work-a", "draft-a"), action.expectedTarget)
            assertTrue(actions.none { it is NativeEngineAction.DefaultPermission })
        }
        compose.onNodeWithText(copy(language, R.string.native_engine_permission_confirm)).assertDoesNotExist()
        fixture.capture("native-engine-permission-$language")
    }

    @Test fun changingTheTargetDismissesPendingPermissionAndCompactConfirmations() {
        val state = mutableStateOf(fake("en")); val actions = mutableListOf<NativeEngineAction>()
        render("en") {
            Column {
                NativeEngineSessionSelector(state.value, { actions.add(it) })
                NativeEngineCompactControl(state.value, { actions.add(it) })
            }
        }
        compose.onNodeWithText(copy("en", R.string.native_engine_workspace_write)).performClick()
        compose.onNodeWithText(copy("en", R.string.native_engine_full_access)).performClick()
        compose.onNodeWithText(copy("en", R.string.native_engine_permission_confirm)).assertIsDisplayed()
        compose.runOnIdle { state.value = state.value.copy(sessionId = "work-b", draftId = "draft-b", session = state.value.session!!.copy(id = "work-b")) }
        compose.onNodeWithText(copy("en", R.string.native_engine_permission_confirm)).assertDoesNotExist()
        compose.onNodeWithText(copy("en", R.string.native_engine_compact)).performClick()
        compose.onNodeWithText(copy("en", R.string.native_engine_compact_cost)).assertIsDisplayed()
        compose.runOnIdle { state.value = state.value.copy(sessionId = "work-c", draftId = "draft-c", session = state.value.session!!.copy(id = "work-c")) }
        compose.onNodeWithText(copy("en", R.string.native_engine_compact_cost)).assertDoesNotExist()
        assertTrue(actions.none { it is NativeEngineAction.SessionPermission || it == NativeEngineAction.Compact || it is NativeEngineAction.CompactAt })
    }

    @Test fun thePickerClosesOnBackAndOnTargetChangeWithoutApplyingAChoice() {
        val state = mutableStateOf(fake("en")); val actions = mutableListOf<NativeEngineAction>()
        render("en") { NativeEngineSessionSelector(state.value, { actions.add(it) }) }
        compose.onNodeWithText(copy("en", R.string.native_engine_standard)).performClick()
        compose.onNodeWithText(copy("en", R.string.native_engine_presets)).assertIsDisplayed()
        systemBack()
        compose.onNodeWithText(copy("en", R.string.native_engine_presets)).assertDoesNotExist()
        compose.onNodeWithTag("native-engine-session-controls").assertIsDisplayed()
        fixture.scenario.onActivity { assertFalse("Dialog Back must not finish its Activity", it.isFinishing) }
        compose.onNodeWithText(copy("en", R.string.native_engine_standard)).performClick()
        compose.runOnIdle { state.value = state.value.copy(sessionId = "work-b", draftId = "draft-b", session = state.value.session!!.copy(id = "work-b")) }
        compose.onNodeWithText(copy("en", R.string.native_engine_presets)).assertDoesNotExist()
        assertTrue(actions.none { it is NativeEngineAction.SessionPreset || it is NativeEngineAction.SessionPermission })
    }

    @Test fun cancellingTheCompactCostDialogDoesNotDispatchACommand() {
        val actions = mutableListOf<NativeEngineAction>()
        render("en") { NativeEngineCompactControl(fake("en"), { actions.add(it) }) }
        compose.onNodeWithText(copy("en", R.string.native_engine_compact)).performClick()
        compose.onNodeWithText(copy("en", R.string.native_engine_compact_cost)).assertIsDisplayed()
        compose.onNodeWithText(copy("en", R.string.native_engine_cancel)).performClick()
        assertTrue(actions.isEmpty())
    }

    @Test fun slashSkillsInsertAReferenceAndPreviewOnlyTheSessionBoundPath() {
        val inserted = AtomicReference<String?>(); val preview = AtomicReference<Pair<String, String>?>()
        val state = fake("en").copy(skillsSessionId = "work-a", skills = listOf(NativeComposerSkill(
            "fixture-notes", "Synthetic skill", false, "/fixture/skills/fixture-notes/SKILL.md", null)))
        render("en") {
            NativeEngineSkillSuggestions(state, "/fi", {}, { inserted.set(it) }, { session, path -> preview.set(session to path) })
        }
        compose.onNodeWithText("/fixture-notes").performClick()
        assertEquals("/fixture-notes ", inserted.get()); assertNull(preview.get())
        compose.onNodeWithContentDescription(copy("en", R.string.native_engine_preview_skill, "fixture-notes")).performClick()
        assertEquals("work-a" to "/fixture/skills/fixture-notes/SKILL.md", preview.get())
    }

    @Test fun absentContextIsUnknownAndOnlyARealZeroIsShownAsZero() {
        val context = mutableStateOf<NativeEngineContext?>(null)
        render("en") { NativeEngineContextSummary(context.value) }
        compose.onNodeWithText(copy("en", R.string.native_engine_context_unknown)).assertIsDisplayed()
        compose.onAllNodesWithText("0").assertCountEquals(0)
        compose.runOnIdle { context.value = NativeEngineContext(null, 0.0, 32000.0, null, null, null) }
        compose.onAllNodesWithText(copy("en", R.string.native_engine_unknown)).assertCountEquals(4)
        compose.onAllNodesWithText("0").assertCountEquals(1)
    }

    @Test fun longChipLabelsWrapAtLargeFontAndRemainReachable() {
        val preset = "A very long agent preset name retained in full for large text"
        val permission = "A very long permission preset name retained in full for large text"
        val initial = fake("en")
        val state = initial.copy(catalog = initial.catalog!!.copy(
            presets = listOf(initial.catalog.presets.first().copy(name = preset)),
            permissions = listOf(initial.catalog.permissions.first().copy(name = permission))))
        render("en", fontScale = 1.6f) { NativeEngineSessionSelector(state, {}) }
        val first = compose.onNodeWithText(preset).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val second = compose.onNodeWithText(permission).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertTrue("The second full label must wrap to another row", second.top >= first.bottom)
        fixture.capture("native-engine-long-labels-large-font")
    }

    private fun render(language: String, fontScale: Float = 1f, content: @Composable () -> Unit) {
        fixture.scenario.onActivity { activity ->
            val configuration = Configuration(activity.resources.configuration).apply { setLocale(Locale.forLanguageTag(language)); this.fontScale = fontScale }
            val localized = activity.createConfigurationContext(configuration)
            val density = Density(activity.resources.displayMetrics.density, fontScale)
            activity.setContent { CompositionLocalProvider(LocalContext provides localized, LocalConfiguration provides configuration,
                LocalResources provides localized.resources, LocalDensity provides density) {
                DroyTheme { Column(Modifier.fillMaxWidth().statusBarsPadding().navigationBarsPadding()) { content() } }
            } }
        }
        compose.waitForIdle()
    }
    private fun systemBack() {
        assertTrue(InstrumentationRegistry.getInstrumentation().uiAutomation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK))
        compose.waitForIdle()
    }
    private fun copy(language: String, resource: Int, vararg values: Any): String {
        val configuration = Configuration(fixture.context.resources.configuration).apply { setLocale(Locale.forLanguageTag(language)) }
        return fixture.context.createConfigurationContext(configuration).getString(resource, *values)
    }
    private fun fake(language: String): NativeEngineState = NativeEngineState(
        runtime = NativeRuntimeState(ready = true, active = true, locale = language), fresh = true,
        catalog = NativeEngineCatalog(true,
            listOf(NativeAgentPreset("standard", null, null, false, true), NativeAgentPreset("cordis", null, null, false, false)),
            "standard", NativeEngineDefault(1, "standard", true), true,
            listOf(NativeDshPermission("workspace-write", "workspace-write", null), NativeDshPermission("danger-full-access", "danger-full-access", null)),
            listOf(NativeDshPermission("workspace-write", "workspace-write", null), NativeDshPermission("danger-full-access", "danger-full-access", null)),
            "workspace-write", NativeEngineDefault(1, "workspace-write", true)),
        sessionId = "work-a", draftId = "draft-a", session = NativeEngineSession("work-a", true, false, "standard", true, "workspace-write", true),
    )
}
