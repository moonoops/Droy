package io.droyagent.app

import android.app.Activity
import android.app.Instrumentation
import android.content.ContentValues
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Base64
import androidx.compose.ui.test.*
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.droyagent.app.nativeui.companion.creation.NativeCompanionCreationViewModel
import io.droyagent.app.nativeui.companion.creation.NativeCreationJson
import io.droyagent.app.nativeui.companion.creation.NativeCreationPage
import io.droyagent.app.nativeui.companion.creation.NativeCreationPreferences
import io.droyagent.app.nativeui.companion.data.NativeCompanionViewModel
import io.droyagent.app.nativeui.companion.ui.cachedCompanionImageSize
import io.droyagent.app.nativeui.data.NativeApiException
import java.io.ByteArrayOutputStream
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith

/** Only the explicit emulator fixture is used; all models resolve to the local test provider. */
@RunWith(AndroidJUnit4::class)
class NativeCompanionCreationDeviceTest {
    @get:Rule(order = 0) val compose = createEmptyComposeRule()
    @get:Rule(order = 1) val fixture = NativeIsolatedRuntimeFixture()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = fixture.context
    private lateinit var creation: NativeCompanionCreationViewModel
    private lateinit var companions: NativeCompanionViewModel
    private val insertedMedia = mutableListOf<Uri>()
    private val importedIds = linkedSetOf<String>()
    private val instancePattern = Regex("companion-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")

    @Before fun start() { bindModels(); fixture.startRuntime(); compose.waitUntil(20_000) { companions.state.value.runtime.ready && companions.state.value.companions.size == 1 } }
    @After fun removeOnlyInsertedFixtureMedia() {
        insertedMedia.forEach { uri ->
            assertEquals("Remove only this test's MediaStore row", 1, context.contentResolver.delete(uri, null, null))
            context.revokeUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        // Successful imports clean themselves up; a failed test may leave only these exact operation IDs.
        val store = io.droyagent.app.media.MediaInputStore.nativeCompanionCreation(context)
        val rows = store.list()
        for (index in 0 until rows.length()) {
            val id = rows.getJSONObject(index).getString("id")
            if (id in importedIds) store.complete(id, "work")
        }
    }

    @Test fun customPersonaPreviewEditsAllFieldsAndKeepsNewProactiveDefault() {
        openCreation()
        compose.onNodeWithTag("native-creation-template-maimai").performClick()
        awaitNode("native-creation-name")
        assertTrue(creation.state.value.editor!!.settings.proactiveEnabled)
        compose.onNodeWithTag("native-creation-name").performTextReplacement("Fictional persona fixture")
        compose.onNodeWithTag("native-creation-personality").performScrollTo().performClick()
        awaitNode("native-companion-personality-input")
        compose.onNodeWithTag("native-companion-personality-input").performTextReplacement("Fictional reserved male programmer.")
        val locale = InstrumentationRegistry.getArguments().getString("nativeFixtureLocale", "en")
        val expectedBehavior = if (locale == "zh") "根据上面的人格描述形成一致的行动习惯。" else "Let the personality above guide consistent actions."
        compose.onNodeWithTag("native-companion-behavior-input").performScrollTo().assertTextContains(expectedBehavior, substring = true)
        settleAndCapture("persona-custom-defaults-$locale")
        compose.onNodeWithTag("native-companion-behavior-input").performTextReplacement("Listen before offering advice.")
        compose.onNodeWithTag("native-companion-reply-style-input").performScrollTo().performTextReplacement("Dry humor and short replies.")
        compose.onNodeWithTag("native-companion-reply-style-input").assertTextContains("Dry humor and short replies.")
        settleAndCapture("persona-custom-fields-$locale")
        compose.onNodeWithTag("native-companion-personality-save").performClick()
        awaitNode("native-creation-name")
        val editor = creation.state.value.editor!!
        assertEquals("Listen before offering advice.", editor.behaviorStyleOverride)
        assertEquals("Dry humor and short replies.", editor.replyStyleOverride)
        awaitJournal(creation.state.value.journal!!.requestId)
        fixture.recreate(); bindModels()
        awaitNode("native-creation-name")
        compose.waitUntil(20_000) { creation.state.value.canCreate }
        assertEquals(editor, creation.state.value.editor)
        compose.onNodeWithTag("native-creation-create").performClick()
        compose.waitUntil(20_000) { companions.state.value.companions.any { it.name == "Fictional persona fixture" } }
        val saved = profiles().single { it.getString("name") == "Fictional persona fixture" }
        assertEquals("Fictional reserved male programmer.", saved.getString("personality"))
        assertEquals("Listen before offering advice.", saved.getString("behaviorStyle"))
        assertEquals("Dry humor and short replies.", saved.getString("replyStyle"))
        assertTrue(saved.getJSONObject("settings").getBoolean("proactiveEnabled"))
    }

    @Test fun fourPresetCatalogPreviewsAndCreatesIndependentCompanionsInSavedLanguage() {
        val locale = InstrumentationRegistry.getArguments().getString("nativeFixtureLocale", "en")
        val names = if (locale == "zh") linkedMapOf("kaka" to "咔咔", "maimai" to "麦麦", "dafeiyu" to "大肥鱼", "claude" to "Claude 大小姐")
            else linkedMapOf("kaka" to "Kaka", "maimai" to "Maimai", "dafeiyu" to "Dafeiyu", "claude" to "Lady Claude")
        val seed = profiles().single()
        assertEquals("kaka", seed.getString("templateId"))
        assertEquals(names.getValue("kaka"), seed.getString("name"))
        assertTrue(instancePattern.matches(seed.getString("id")))
        val templates = request("/templates").getJSONArray("templates").let { values ->
            (0 until values.length()).map(values::getJSONObject)
        }
        assertEquals(names.keys.toList(), templates.map { it.getString("templateId") })
        assertEquals(4, templates.map { it.getString("avatarUrl") }.distinct().size)
        assertEquals(4, templates.map { it.getString("personality") }.distinct().size)
        val created = linkedMapOf<String, String>()
        openCreation()
        for ((index, template) in templates.withIndex()) {
            val id = template.getString("templateId")
            val tag = "native-creation-template-$id"
            compose.onNodeWithTag("native-creation-templates").performScrollToNode(hasTestTag(tag))
            compose.onNodeWithTag(tag).assertIsDisplayed().assertTextContains(names.getValue(id), substring = true)
            val nameLayouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
            compose.onNodeWithTag("native-creation-template-name-$id", useUnmergedTree = true)
                .performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult) { it(nameLayouts) }
            assertEquals("Template names must stay on one line", 1, nameLayouts.single().lineCount)
            assertFalse("Template name $id must remain fully visible", nameLayouts.single().isLineEllipsized(0))
            val avatar = template.getString("avatarUrl")
            compose.waitUntil(15_000) { cachedCompanionImageSize(avatar) != null }
            val dimensions = requireNotNull(cachedCompanionImageSize(avatar))
            assertEquals("The selected square avatar must decode in the template UI", dimensions.first, dimensions.second)
            val image = runBlocking { creation.loadImage(avatar) }
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(image, 0, image.size, bounds)
            assertTrue("Template avatar must be a decodable image", bounds.outWidth > 0)
            assertEquals(bounds.outWidth, bounds.outHeight)
            if (id == "kaka" || id == "claude") settleAndCapture("four-presets-$locale-catalog-$id")
            compose.onNodeWithTag(tag).performClick()
            awaitNode("native-creation-name")
            assertEquals(id, creation.state.value.editor!!.template.id)
            assertEquals(template.getString("suggestedName"), creation.state.value.editor!!.name)
            compose.onNodeWithTag("native-creation-personality").performScrollTo().performClick()
            awaitNode("native-companion-personality-input")
            compose.onNodeWithTag("native-companion-personality-input").assertTextContains(template.getString("personality"))
            settleAndCapture("four-presets-$locale-$id-identity")
            compose.onNodeWithTag("native-companion-behavior-input").performScrollTo().assertTextContains(template.getString("behaviorStyle"))
            compose.onNodeWithTag("native-companion-reply-style-input").performScrollTo().assertTextContains(template.getString("replyStyle"))
            settleAndCapture("four-presets-$locale-$id-styles")
            pressAndroidBack()
            awaitNode("native-creation-name")
            if (id == "kaka") {
                pressAndroidBack()
                awaitNode("native-creation-templates")
                assertEquals(1, profiles().size)
            } else {
                val name = creation.state.value.editor!!.name
                compose.waitUntil(20_000) { creation.state.value.canCreate }
                compose.onNodeWithTag("native-creation-create").performClick()
                compose.waitUntil(20_000) { companions.state.value.companions.any { it.name == name } }
                awaitNode("native-companion-composer")
                val saved = profiles().single { it.getString("name") == name }
                val instance = saved.getString("id")
                assertTrue(instancePattern.matches(instance))
                assertNotEquals(seed.getString("id"), instance)
                assertEquals(id, saved.getString("templateId"))
                for (field in listOf("personality", "behaviorStyle", "replyStyle")) assertEquals(field, template.getString(field), saved.getString(field))
                for (field in listOf("personalityOverride", "behaviorStyleOverride", "replyStyleOverride")) assertEquals(field, "", saved.getString(field))
                assertEquals(avatar, saved.getString("avatarUrl"))
                assertTrue(saved.getBoolean("modelInherited"))
                assertEquals(instance, companions.state.value.selectedCompanionId)
                created[id] = instance
                settleAndCapture("four-presets-$locale-$id-created")
                if (index < templates.lastIndex) {
                    pressAndroidBack()
                    awaitNode("native-add-companion")
                    openCreation()
                }
            }
        }
        assertEquals(3, created.values.distinct().size)
        assertEquals(4, profiles().size)
        stopRuntime()
        fixture.startRuntime()
        fixture.recreate()
        bindModels()
        compose.waitUntil(20_000) { companions.state.value.runtime.ready && companions.state.value.companions.size == 4 }
        val restored = profiles()
        assertEquals(seed.getString("id"), restored.single { it.getString("templateId") == "kaka" }.getString("id"))
        for (template in templates.filter { it.getString("templateId") != "kaka" }) {
            val id = template.getString("templateId")
            val saved = restored.single { it.getString("id") == created.getValue(id) }
            assertTrue(saved.getString("name").startsWith(names.getValue(id)))
            for (field in listOf("personality", "behaviorStyle", "replyStyle")) assertEquals(field, template.getString(field), saved.getString(field))
        }
        settleAndCapture("four-presets-$locale-restored")
    }

    @Test fun expandedStickerTrayScrollsAndSendsLastSticker() {
        val id = companions.state.value.companions.single().id
        compose.onNodeWithTag("native-tab-messages").performClick()
        compose.onNodeWithTag("native-companion-conversation:$id").performClick()
        compose.waitUntil(15_000) { companions.state.value.selectedCompanionId == id && companions.state.value.stickers.size == 9 && !companions.state.value.loading }
        compose.onNodeWithTag("native-companion-composer-options").performClick()
        val row = compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.HorizontalScrollAxisRange))
        row.performScrollToIndex(8)
        val last = companions.state.value.stickers.last()
        assertEquals("sticker-thinking", last.id)
        compose.onNodeWithContentDescription(last.label).assertIsDisplayed()
        val locale = InstrumentationRegistry.getArguments().getString("nativeFixtureLocale", "en")
        settleAndCapture("expanded-stickers-$locale")
        compose.onNodeWithContentDescription(last.label).performClick()
        compose.waitUntil(15_000) { companions.state.value.messages.any { it.role == "user" && it.stickerId == last.id && it.status != "failed" } }
        assertEquals(1, companions.state.value.messages.count { it.role == "user" && it.stickerId == last.id })
        val sent = companions.state.value.messages.single { it.role == "user" && it.stickerId == last.id }
        compose.onNodeWithTag("native-companion-message:${sent.id}").assertIsDisplayed()
        settleAndCapture("expanded-sticker-sent-$locale")
    }

    @Test fun nativeTemplateCreationPersistsAllEditableFieldsAndOwnedAvatarAcrossRecreation() {
        val seed = profiles().single()
        assertEquals("kaka", seed.getString("templateId")); assertTrue(instancePattern.matches(seed.getString("id")))
        assertEquals("Kaka", seed.getString("name")); assertTrue(seed.getBoolean("modelInherited"))
        assertNotEquals("kaka", seed.getString("id"))
        openCreation()
        compose.onNodeWithTag("native-creation-template-kaka").performClick()
        awaitNode("native-creation-name")
        assertEquals("Kaka 1", creation.state.value.editor!!.name)
        compose.onNodeWithTag("native-creation-name").performTextReplacement("Native creation fixture")
        compose.onNodeWithTag("native-creation-personality").performScrollTo().performClick()
        awaitNode("native-creation-personality-input")
        compose.onNodeWithTag("native-creation-personality-input").performTextReplacement("A fictional calm companion used only by this local fixture.")
        done()
        compose.onNodeWithTag("native-creation-model").performScrollTo().performClick()
        compose.onNodeWithTag("native-creation-models").performScrollToNode(hasText("Native alternate fixture"))
        compose.onNodeWithText("Native alternate fixture").performClick()
        compose.waitUntil(10_000) { creation.state.value.editor?.model?.id == "native-alternate" }
        compose.onNodeWithTag("native-creation-interaction").performScrollTo().performClick()
        toggle(R.string.native_companion_proactive); assertTrue(creation.state.value.editor!!.settings.proactiveEnabled)
        toggle(R.string.native_companion_proactive); assertFalse(creation.state.value.editor!!.settings.proactiveEnabled)
        toggle(R.string.native_companion_memory)
        toggle(R.string.native_companion_civil_language)
        toggle(R.string.native_companion_quiet_hours)
        assertFalse(JSONObject(requireNotNull(creation.state.value.editor!!.settings.quietHoursJson)).getBoolean("enabled"))
        toggle(R.string.native_companion_quiet_hours)
        val settings = creation.state.value.editor!!.settings
        assertFalse(settings.memoryEnabled); assertFalse(settings.allowProfanity)
        assertTrue(JSONObject(requireNotNull(settings.quietHoursJson)).getBoolean("enabled"))
        compose.onNodeWithTag("native-creation-background").performScrollTo().performClick()
        compose.onNodeWithTag("native-creation-backgrounds").performScrollToNode(hasText(context.getString(R.string.native_companion_creation_background_preset, 3)))
        compose.onNodeWithText(context.getString(R.string.native_companion_creation_background_preset, 3)).performClick()
        back(); done()
        assertEquals(NativeCreationPage.EDITOR, creation.state.value.page)
        val uri = imageUri()
        pickImage(uri)
        val journal = requireNotNull(creation.state.value.journal)
        val upload = requireNotNull(journal.editor.avatarUpload)
        val draft = requireNotNull(journal.draftId)
        assertEquals("/droy/companions/creation-drafts/$draft/attachments/${upload.id}", upload.url)
        assertEquals(1, profiles().size)
        awaitJournal(journal.requestId)
        fixture.capture("native-creation-complete-draft")
        fixture.recreate(); bindModels()
        awaitNode("native-creation-name")
        compose.waitUntil(20_000) { creation.state.value.checked && !creation.state.value.loading && creation.state.value.canCreate }
        val restored = requireNotNull(creation.state.value.journal)
        assertEquals(journal.requestId, restored.requestId); assertEquals(draft, restored.draftId)
        assertEquals(journal.editor, restored.editor); assertEquals(1, profiles().size)
        compose.onNodeWithTag("native-creation-name").assertTextContains("Native creation fixture")
        compose.onNodeWithTag("native-creation-create").assertIsEnabled().performClick()
        compose.waitUntil(20_000) { companions.state.value.companions.any { it.name == "Native creation fixture" } }
        val created = companions.state.value.companions.single { it.name == "Native creation fixture" }
        assertTrue(instancePattern.matches(created.id)); assertNotEquals(seed.getString("id"), created.id)
        awaitNode("native-companion-composer")
        assertEquals(created.id, companions.state.value.selectedCompanionId)
        val saved = profiles().single { it.getString("id") == created.id }
        assertEquals("kaka", saved.getString("templateId"))
        assertEquals(journal.editor.personalityOverride, saved.getString("personalityOverride"))
        assertEquals("droy-native-fixture", saved.getJSONObject("model").getString("provider"))
        assertEquals("native-alternate", saved.getJSONObject("model").getString("model")); assertFalse(saved.getBoolean("modelInherited"))
        val savedSettings = saved.getJSONObject("settings")
        assertFalse(savedSettings.getBoolean("proactiveEnabled")); assertFalse(savedSettings.getBoolean("memoryEnabled")); assertFalse(savedSettings.getBoolean("allowProfanity"))
        val expectedQuiet = JSONObject(requireNotNull(settings.quietHoursJson))
        for (field in listOf("enabled", "start", "end")) assertEquals(field, expectedQuiet.get(field), savedSettings.getJSONObject("quietHours").get(field))
        assertEquals("03", saved.getJSONObject("chatBackground").getString("id"))
        val ownedAvatar = "/droy/companions/${created.id}/attachments/${upload.id}"
        assertEquals(ownedAvatar, saved.getString("avatarUrl"))
        assertTrue(runBlocking { fixture.runtimeTransport.bytes(ownedAvatar) }.isNotEmpty())
        assertApiCode("COMPANION_DRAFT_CLOSED") { request("/creation-drafts/$draft/attachments/${upload.id}") }
        assertEquals(2, profiles().size)
        assertEquals(seed.getString("avatarUrl"), profiles().single { it.getString("id") == seed.getString("id") }.getString("avatarUrl"))
        fixture.recreate(); bindModels(); awaitNode("native-companion-composer")
        assertEquals(created.id, companions.state.value.selectedCompanionId); assertEquals(2, profiles().size)
        fixture.capture("native-creation-owned-profile")
    }

    @Test fun sameTemplateInstancesKeepUniqueNormalizedNamesSuggestionsAndIdempotentReceipts() {
        assertEquals("Kaka 1", template().getString("suggestedName"))
        for (reserved in listOf("咔咔", "ＫＡＫＡ")) assertApiCode("COMPANION_NAME_CONFLICT") { request(method = "POST", body = payload(reserved)) }
        assertEquals(1, profiles().size)
        val firstInput = payload("Kaka 1").put("model", JSONObject().put("provider", "droy-native-fixture").put("model", "native-alternate"))
        val first = request(method = "POST", body = firstInput).getJSONObject("profile")
        assertEquals("Kaka 2", template().getString("suggestedName"))
        assertApiCode("COMPANION_NAME_CONFLICT") { request(method = "POST", body = payload("ＫＡＫＡ   1")) }
        val secondInput = payload("Kaka 2")
        val second = request(method = "POST", body = secondInput).getJSONObject("profile")
        assertNotEquals(first.getString("id"), second.getString("id")); assertEquals("kaka", second.getString("templateId"))
        assertTrue(instancePattern.matches(first.getString("id"))); assertTrue(instancePattern.matches(second.getString("id")))
        assertFalse(first.getBoolean("modelInherited")); assertTrue(second.getBoolean("modelInherited"))
        assertApiCode("COMPANION_NAME_CONFLICT") { request("/${second.getString("id")}", "PATCH", JSONObject().put("name", " kaka  1 ")) }
        assertEquals("Kaka 3", template().getString("suggestedName"))
        assertEquals(3, profiles().size)
        stopRuntime(); fixture.startRuntime()
        val replay = request(method = "POST", body = firstInput)
        assertTrue(replay.getBoolean("replayed")); assertEquals(first.getString("id"), replay.getJSONObject("profile").getString("id"))
        assertApiCode("COMPANION_CREATION_CONFLICT") { request(method = "POST", body = JSONObject(firstInput.toString()).put("name", "Changed retry")) }
        val after = profiles(); assertEquals(3, after.size)
        assertEquals("native-alternate", after.single { it.getString("id") == first.getString("id") }.getJSONObject("model").getString("model"))
        assertEquals("Kaka 2", after.single { it.getString("id") == second.getString("id") }.getString("name"))
    }

    @Test fun coldReceiptRecoveryResolvesTheCommittedDraftWithoutCreatingAnotherInstance() {
        openCreation(); compose.onNodeWithTag("native-creation-template-kaka").performClick(); awaitNode("native-creation-name")
        compose.onNodeWithTag("native-creation-name").performTextReplacement("Cold creation fixture")
        val initial = requireNotNull(creation.state.value.journal)
        awaitJournal(initial.requestId)
        val draft = request("/creation-drafts", "POST", JSONObject().put("requestId", initial.requestId)).getString("draftId")
        val pending = initial.copy(draftId = draft)
        val frozen = NativeCreationJson.payload(pending)
        fixture.scenario.onActivity { creation.stopObserving() }
        // Commit through the real service while withholding its receipt from the UI.
        val committed = request(method = "POST", body = JSONObject(frozen)).getJSONObject("profile").getString("id")
        stopRuntime(); fixture.scenario.close()
        // Seed the exact journal production persists before submitting a creation.
        runBlocking { NativeCreationPreferences(context).write(pending.copy(frozenPayload = frozen)) }
        fixture.relaunch("messages"); bindModels(); fixture.startRuntime()
        openCreation(assertVisible = false)
        compose.waitUntil(20_000) { companions.state.value.selectedCompanionId == committed && creation.state.value.journal == null }
        awaitNode("native-companion-composer")
        assertEquals(2, profiles().size); assertEquals(1, profiles().count { it.getString("name") == "Cold creation fixture" })
        val draftStatus = request("/creation-drafts/$draft")
        assertEquals("committed", draftStatus.getString("state")); assertEquals(committed, draftStatus.getString("companionId"))
        val retry = request(method = "POST", body = JSONObject(frozen))
        assertTrue(retry.getBoolean("replayed")); assertEquals(committed, retry.getJSONObject("profile").getString("id")); assertEquals(2, profiles().size)
        fixture.capture("native-creation-cold-receipt-recovered")
    }

    @Test fun cancellingAnUploadedCreationDraftLeavesTheExistingCompanionAndUserUntouched() {
        val before = profiles().single()
        val user = request("/user-profile", "PATCH", JSONObject().put("name", "Fictional retained user"))
        val draftInput = JSONObject().put("requestId", UUID.randomUUID().toString())
        val draft = request("/creation-drafts", "POST", draftInput).getString("draftId")
        assertEquals(draft, request("/creation-drafts", "POST", draftInput).getString("draftId"))
        val upload = request("/creation-drafts/$draft/uploads", "POST", imageBody())
        assertEquals(1, profiles().size)
        val result = request("/creation-drafts/$draft/cancel", "POST", JSONObject())
        assertTrue(result.getBoolean("cancelled")); assertEquals("cancelled", result.getString("state"))
        assertTrue(request("/creation-drafts/$draft/cancel", "POST", JSONObject()).getBoolean("cancelled"))
        assertApiCode("COMPANION_DRAFT_CLOSED") { request(method = "POST", body = payload("Cancelled fixture").put("requestId", draftInput.getString("requestId")).put("draftId", draft).put("avatarUploadId", upload.getString("id"))) }
        assertEquals(1, profiles().size)
        val after = profiles().single()
        for (field in listOf("id", "name", "avatarUrl", "templateId")) assertEquals(field, before.get(field), after.get(field))
        val afterUser = request("/user-profile")
        for (field in listOf("name", "avatarUrl")) assertEquals(field, user.get(field), afterUser.get(field))
        assertEquals("cancelled", request("/creation-drafts/$draft").getString("state"))
    }

    private fun bindModels() { fixture.scenario.onActivity { activity -> creation = ViewModelProvider(activity)[NativeCompanionCreationViewModel::class.java]; companions = ViewModelProvider(activity)[NativeCompanionViewModel::class.java] } }
    private fun openCreation(assertVisible: Boolean = true) {
        lateinit var navigation: io.droyagent.app.nativeui.navigation.NativeExternalNavigationViewModel
        fixture.scenario.onActivity { navigation = ViewModelProvider(it)[io.droyagent.app.nativeui.navigation.NativeExternalNavigationViewModel::class.java] }
        // Tab buttons exist before the initial launch destination has been acknowledged.
        // Wait for that destination so its deferred root navigation cannot replace this tap.
        compose.waitUntil(20_000) { navigation.state.value.restored && navigation.state.value.request == null }
        awaitNode("native-tab-messages"); compose.onNodeWithTag("native-tab-messages").performClick()
        awaitNode("native-add-companion"); compose.onNodeWithTag("native-add-companion").performClick()
        compose.waitUntil(20_000) { creation.state.value.runtime.ready && creation.state.value.catalogLoaded && !creation.state.value.loading }
        if (assertVisible) awaitNode("native-companion-creation")
    }
    private fun settleAndCapture(name: String) {
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
        SystemClock.sleep(250)
        fixture.capture(name)
    }
    private fun awaitNode(tag: String) {
        try {
            compose.waitUntil(20_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes(false).isNotEmpty() }
            compose.waitForIdle()
        } catch (failure: ComposeTimeoutException) {
            fixture.capture("creation-missing-$tag")
            throw failure
        }
    }
    private fun pressAndroidBack() { instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK); compose.waitForIdle() }
    private fun done() { compose.onNodeWithText(context.getString(R.string.native_companion_creation_apply)).performScrollTo().performClick() }
    private fun back() { compose.onNodeWithContentDescription(context.getString(R.string.native_companion_back)).performClick() }
    private fun toggle(resource: Int) { compose.onNodeWithText(context.getString(resource)).performScrollTo().performClick(); compose.waitForIdle() }
    private fun awaitJournal(id: String) { compose.waitUntil(10_000) { context.getSharedPreferences("native_companion_creation", 0).getString("journal", null)?.let { NativeCreationJson.journal(it).let { journal -> journal.requestId == id && journal.editor == creation.state.value.editor } } == true } }
    private fun request(path: String = "", method: String = "GET", body: JSONObject? = null): JSONObject = runBlocking { fixture.runtimeTransport.json("/droy/companions$path", method, body) }
    private fun profiles(): List<JSONObject> = request().getJSONArray("companions").let { values -> (0 until values.length()).map(values::getJSONObject) }
    private fun template(): JSONObject = request("/templates").getJSONArray("templates").let { values -> (0 until values.length()).map(values::getJSONObject).single { it.getString("templateId") == "kaka" } }
    private fun payload(name: String) = JSONObject().put("requestId", UUID.randomUUID().toString()).put("templateId", "kaka").put("name", name).put("model", JSONObject.NULL)
    private fun assertApiCode(expected: String, action: () -> Unit) { assertEquals(expected, assertThrows(NativeApiException::class.java, action).code) }
    private fun stopRuntime() {
        context.startService(Intent(context, RuntimeService::class.java).setAction(RuntimeService.STOP))
        val until = SystemClock.elapsedRealtime() + 30_000
        while (!RuntimeService.canManageHistory() && SystemClock.elapsedRealtime() < until) SystemClock.sleep(100)
        assertTrue(RuntimeService.canManageHistory())
    }
    private fun png(): ByteArray {
        val bitmap = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.rgb(122, 86, 177)) }
        return try { ByteArrayOutputStream().use { output -> assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)); output.toByteArray() } } finally { bitmap.recycle() }
    }
    private fun imageBody() = JSONObject().put("name", "fictional-creation.png").put("mediaType", "image/png").put("base64", Base64.encodeToString(png(), Base64.NO_WRAP))
    private fun imageUri(): Uri {
        val values = ContentValues().apply { put(MediaStore.MediaColumns.DISPLAY_NAME, "native-creation-${UUID.randomUUID()}.png"); put(MediaStore.MediaColumns.MIME_TYPE, "image/png"); put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/DroyNativeFixture") }
        val uri = requireNotNull(context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)); insertedMedia += uri
        requireNotNull(context.contentResolver.openOutputStream(uri)).use { it.write(png()) }
        context.grantUriPermission(context.packageName, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return uri
    }
    private fun pickImage(uri: Uri) {
        val filter = IntentFilter().apply { addAction(Intent.ACTION_OPEN_DOCUMENT); addCategory(Intent.CATEGORY_OPENABLE); addDataType("image/*") }
        val result = Intent().setData(uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val monitor = instrumentation.addMonitor(filter, Instrumentation.ActivityResult(Activity.RESULT_OK, result), true)
        try {
            compose.onNodeWithTag("native-creation-avatar").performScrollTo().performClick()
            compose.waitUntil(10_000) { monitor.hits == 1 }
            creation.state.value.image?.let { importedIds += it.id }
            compose.waitUntil(20_000) {
                creation.state.value.image?.let { importedIds += it.id }
                creation.state.value.editor?.avatarUpload != null && creation.state.value.image == null && !creation.state.value.busy
            }
            assertNull(creation.state.value.imageError)
        } finally { instrumentation.removeMonitor(monitor) }
    }
}
