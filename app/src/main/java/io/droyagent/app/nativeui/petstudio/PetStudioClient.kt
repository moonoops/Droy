package io.droyagent.app.nativeui.petstudio

import io.droyagent.app.nativeui.data.NativeLocalTransport
import org.json.JSONArray
import org.json.JSONObject

internal enum class PetStage { Draft, Persona, Reference, Actions, ReferenceReady, Ready, Error }
internal data class PetCandidate(
    val id: String, val name: String, val description: String, val personality: String,
    val stage: PetStage, val reference: Boolean, val referenceCurrent: Boolean, val logo: Boolean,
    val updatedAt: Long, val completed: Int, val total: Int, val persona: String, val voice: List<String>,
) { val generating get() = stage in listOf(PetStage.Persona, PetStage.Reference, PetStage.Actions) }
internal data class PetCatalog(val available: Boolean, val imageAvailable: Boolean, val provider: String?,
    val imageModel: String?, val textProvider: String?, val textModel: String?, val pets: List<PetCandidate>)
internal data class PetDraft(val id: String? = null, val name: String = "", val description: String = "", val personality: String = "") {
    fun body(): JSONObject {
        require(name.trim().isNotEmpty() && name.trim().length <= 40 && description.trim().isNotEmpty() && description.length <= 1000 && personality.length <= 1000)
        return JSONObject().put("name", name.trim()).put("description", description.trim()).put("personality", personality.trim()).also { value ->
            id?.let { checkPetId(it); value.put("id", it) }
        }
    }
}
internal fun checkPetId(id: String) { require(id.matches(Regex("[a-f0-9]{8}-[a-f0-9]{4}-4[a-f0-9]{3}-[89ab][a-f0-9]{3}-[a-f0-9]{12}"))) }
internal object PetStudioJson {
    private fun JSONObject.text(key: String, limit: Int): String {
        val value = opt(key); require(value is String && value.length <= limit && !value.contains('\u0000')); return value
    }
    private fun routing(value: Any?): String? = (value as? String)?.takeIf {
        it.matches(Regex("[A-Za-z0-9][A-Za-z0-9_.:/-]{0,159}")) && !it.contains("://") && !it.contains("sk-")
    }
    fun candidate(row: JSONObject): PetCandidate {
        val id = row.text("id", 36); checkPetId(id)
        val stage = when (row.getString("status")) {
            "draft" -> PetStage.Draft; "generating_persona" -> PetStage.Persona; "generating_reference" -> PetStage.Reference
            "generating_actions" -> PetStage.Actions; "reference_ready" -> PetStage.ReferenceReady; "ready" -> PetStage.Ready; "error" -> PetStage.Error
            else -> error("invalid_pet_status")
        }
        val persona = row.optJSONObject("persona")
        val voice = persona?.optJSONObject("voice")
        fun lines(key: String, count: Int): List<String> {
            val lines = voice?.optJSONArray(key) ?: return emptyList()
            return (0 until minOf(count, lines.length())).map { lines.getString(it).also { text -> require(text.length <= 500) } }
        }
        val completed = row.getInt("completedGroups"); val total = row.getInt("totalGroups")
        require(total in 1..12 && completed in 0..total)
        val description = persona?.optString("description") ?: ""; require(description.length <= 3000)
        return PetCandidate(id, row.text("name", 160), row.text("description", 4000), row.text("personality", 4000), stage,
            row.opt("referenceUrl") is String, row.optBoolean("referenceCurrent", true), row.opt("logoUrl") is String,
            row.getLong("updatedAt"), completed, total, description,
            if (row.optBoolean("personaGenerated")) lines("working", 2) + lines("idle", 1) else emptyList())
    }
    fun catalog(value: JSONObject): PetCatalog {
        require(value.opt("available") is Boolean && value.opt("imageAvailable") is Boolean)
        val rows = value.getJSONArray("pets"); require(rows.length() <= 100)
        val pets = (0 until rows.length()).map { candidate(rows.getJSONObject(it)) }; require(pets.map { it.id }.distinct().size == pets.size)
        return PetCatalog(value.getBoolean("available"), value.getBoolean("imageAvailable"), routing(value.opt("provider")),
            routing(value.opt("model")), routing(value.opt("textProvider")), routing(value.opt("textModel")), pets)
    }
}

internal class PetStudioClient(private val request: suspend (String, String, JSONObject?) -> JSONObject = { path, method, body -> NativeLocalTransport().json(path, method, body) }) {
    suspend fun list(): PetCatalog = PetStudioJson.catalog(request("/droy/pets", "GET", null))
    suspend fun status(id: String): PetCandidate { checkPetId(id); return PetStudioJson.candidate(request("/droy/pets/status?id=$id", "GET", null).getJSONObject("pet")) }
    suspend fun create(draft: PetDraft): PetCandidate {
        require(draft.id == null)
        return PetStudioJson.candidate(request("/droy/pets/create", "POST", draft.body()).getJSONObject("pet"))
    }
    suspend fun retry(draft: PetDraft): PetCandidate {
        require(draft.id != null)
        return PetStudioJson.candidate(request("/droy/pets/retry", "POST", draft.body()).getJSONObject("pet"))
    }
    suspend fun generate(id: String): PetCandidate {
        checkPetId(id)
        return PetStudioJson.candidate(request("/droy/pets/generate", "POST", JSONObject().put("id", id)).getJSONObject("pet"))
    }
}
