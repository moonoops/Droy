package io.droyagent.app.nativeui.petstudio

import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class PetStudioClientTest {
    private val id = "a00b0000-0000-4000-8000-000000000001"
    private fun pet() = JSONObject().put("id", id).put("name", "Fixture pet").put("description", "A black cat").put("personality", "Friendly")
        .put("status", "reference_ready").put("referenceUrl", "/droy/pets/asset?id=$id&file=reference.png").put("referenceCurrent", true)
        .put("updatedAt", 1).put("completedGroups", 0).put("totalGroups", 8).put("error", "Bearer fictional-secret /private/fixture")
        .put("stage", "private provider exception").put("rawToken", "fictional-secret")
    private fun catalog() = JSONObject().put("available", true).put("imageAvailable", true).put("provider", "OpenAI").put("model", "gpt-image-1.5")
        .put("textProvider", "fixture").put("textModel", "fixture-model").put("pets", JSONArray().put(pet()))
    @Test fun restoreReadsCatalogWithoutGeneratingAndDropsRawFailures() = runBlocking {
        val calls = mutableListOf<String>()
        val client = PetStudioClient { path, method, body -> calls.add("$method $path"); assertNull(body); catalog() }
        val result = client.list()
        assertEquals(listOf("GET /droy/pets"), calls)
        assertEquals(PetStage.ReferenceReady, result.pets.single().stage)
        for (secret in listOf("fictional-secret", "/private", "exception", "rawToken")) assertFalse(result.toString().contains(secret))
    }
    @Test fun mutationsUseOnlyDocumentedBodiesAndExplicitCalls() = runBlocking {
        val calls = mutableListOf<Pair<String, JSONObject>>()
        val client = PetStudioClient { path, method, body -> assertEquals("POST", method); calls.add(path to requireNotNull(body)); JSONObject().put("pet", pet()) }
        val draft = PetDraft(name = "Fixture", description = "Cat", personality = "Calm")
        client.create(draft); client.retry(draft.copy(id = id)); client.generate(id)
        assertEquals(listOf("/droy/pets/create", "/droy/pets/retry", "/droy/pets/generate"), calls.map { it.first })
        assertFalse(calls[0].second.has("id")); assertEquals(id, calls[1].second.getString("id")); assertEquals(1, calls[2].second.length())
    }
    @Test fun idsAndDraftValidationFailBeforeAnyRequest() = runBlocking {
        var calls = 0
        val client = PetStudioClient { _, _, _ -> calls++; catalog() }
        try { client.status("../private"); fail() } catch (_: IllegalArgumentException) {}
        try { client.generate("default&file=secret"); fail() } catch (_: IllegalArgumentException) {}
        try { client.create(PetDraft(name = "", description = "Cat")); fail() } catch (_: IllegalArgumentException) {}
        assertEquals(0, calls)
    }
    @Test fun responseUrlsAreOnlyAvailabilityFlagsNeverRequestTargets() {
        val source = pet().put("referenceUrl", "https://untrusted.example/secret").put("logoUrl", "/private/source")
        val value = PetStudioJson.candidate(source)
        assertTrue(value.reference); assertTrue(value.logo)
        assertFalse(value.toString().contains("untrusted")); assertFalse(value.toString().contains("/private"))
    }
    private fun builtIn(id: String, zh: String, en: String) = JSONObject().put("id", id)
        .put("nameLocales", JSONObject().put("zh", zh).put("en", en))
        .put("descriptionLocales", JSONObject().put("zh", "像素伙伴").put("en", "Pixel companion"))
        .put("referenceUrl", "https://untrusted.example/not-a-request-target")

    @Test fun builtInsKeepPairedNamesSeparateFromGeneratedDraftsWithoutAConfiguredModel() {
        val source = catalog().put("available", false).put("imageAvailable", false).put("builtIns", JSONArray()
            .put(builtIn("default", "黑猫", "Black cat"))
            .put(builtIn("8dd37d0d-b6ed-47c2-9952-8f3be20fa6d0", "土拨鼠", "Marmot"))
            .put(builtIn("cb5c4126-8c3b-41a5-a1bd-3d3b95cdeee2", "羊驼", "Alpaca")))
        val result = PetStudioJson.catalog(source)
        assertFalse(result.available)
        assertEquals(listOf("default", "8dd37d0d-b6ed-47c2-9952-8f3be20fa6d0", "cb5c4126-8c3b-41a5-a1bd-3d3b95cdeee2"), result.builtIns.map { it.id })
        assertEquals("羊驼", result.builtIns.last().name.text("zh"))
        assertEquals("Alpaca", result.builtIns.last().name.text("en"))
        assertEquals(id, result.pets.single().id)
        assertFalse(result.builtIns.toString().contains("untrusted.example"))
    }

    @Test fun malformedBuiltInDirectoriesAreRejected() {
        for (rows in listOf(
            JSONArray().put(builtIn("default&file=secret", "猫", "Cat")),
            JSONArray().put(builtIn("default", "猫", "Cat")).put(builtIn("default", "猫", "Cat")),
            JSONArray().put(builtIn("default", "猫", "Cat").put("nameLocales", JSONObject().put("zh", "猫"))),
        )) {
            try { PetStudioJson.builtIns(rows); fail("Malformed directory was accepted") } catch (_: Exception) {}
        }
    }

    @Test fun previewActionsExposeSignatureStatesWithPairedLabelsAndRejectInvalidIds() {
        val rows = JSONArray()
        for ((id, zh, en) in listOf(Triple("idle", "待机", "Idle"), Triple("scream", "抬头啊", "Meme scream"), Triple("spitScreen", "朝屏幕吐水", "Spit toward viewer")))
            rows.put(JSONObject().put("id", id).put("labelLocales", JSONObject().put("zh", zh).put("en", en)))
        val actions = PetStudioJson.actions(rows)
        assertEquals(listOf("idle", "scream", "spitScreen"), actions.map { it.id })
        assertEquals("朝屏幕吐水", actions.last().label.text("zh"))
        assertEquals("Spit toward viewer", actions.last().label.text("en"))
        try { PetStudioJson.actions(JSONArray().put(JSONObject().put("id", "../private").put("label", "Invalid"))); fail() }
        catch (_: IllegalArgumentException) {}
    }
}
