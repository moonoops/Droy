package io.droyagent.app

import android.content.Context
import android.content.SharedPreferences
import io.droyagent.app.nativeui.data.NativeApiException
import io.droyagent.app.nativeui.settings.NativeSettingsClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

internal interface PersonalizationThemeGateway {
    suspend fun describe(): JSONObject
    suspend fun mutate(body: JSONObject): JSONObject
}

private class PersonalizationThemeClient : PersonalizationThemeGateway {
    private val settings = NativeSettingsClient()
    override suspend fun describe(): JSONObject = settings.describe()
    override suspend fun mutate(body: JSONObject): JSONObject = settings.mutate(body)
}

/** Restore presentation preferences only after the shared engine theme is durably confirmed. */
class NativePersonalizationReset internal constructor(
    private val preferences: SharedPreferences,
    private val theme: PersonalizationThemeGateway,
    private val runtimeReady: () -> Boolean,
    private val refreshPresentation: suspend () -> Unit,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    constructor(context: Context, onPresentationChanged: suspend () -> Unit) : this(
        context.applicationContext.getSharedPreferences("product", Context.MODE_PRIVATE),
        PersonalizationThemeClient(),
        { NativeRuntimeHost.connection() != null },
        presentationRefresh(context.applicationContext, onPresentationChanged),
    )

    data class Receipt(val themeRevision: Long)
    enum class Stage { NOT_READY, ENGINE_CONFLICT, ENGINE_UNCONFIRMED, LOCAL_UNCONFIRMED, PRESENTATION_UNCONFIRMED }
    class Failure(val stage: Stage, val themeRevision: Long? = null) : Exception("personalization_reset_${stage.name}") {
        val engineThemeConfirmed: Boolean get() = themeRevision != null
        val localCommitConfirmed: Boolean get() = stage == Stage.PRESENTATION_UNCONFIRMED
    }

    private val mutation = Mutex()

    suspend fun reset(): Receipt = mutation.withLock {
        if (!runtimeReady()) throw Failure(Stage.NOT_READY)
        val revision = try {
            val before = describeTheme(theme.describe())
            val saved = parseTheme(theme.mutate(themeMutation(before.revision)))
            if (saved.revision < before.revision || !saved.durableSystem) throw Failure(Stage.ENGINE_UNCONFIRMED)
            val current = try { describeTheme(theme.describe()) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { throw Failure(Stage.ENGINE_UNCONFIRMED) }
            if (current.revision < saved.revision || !current.durableSystem) throw Failure(Stage.ENGINE_UNCONFIRMED)
            current.revision
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Failure) { throw failure }
        catch (error: Exception) {
            throw Failure(if (error is NativeApiException && error.code == "settings/conflict") Stage.ENGINE_CONFLICT else Stage.ENGINE_UNCONFIRMED)
        }

        try {
            withContext(io) {
                val editor = preferences.edit()
                for (key in removedKeys) editor.remove(key)
                editor.putString("themePreference", "system")
                if (!editor.commit() || !localResetConfirmed(preferences)) throw Failure(Stage.LOCAL_UNCONFIRMED, revision)
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Failure) { throw failure }
        catch (_: Exception) { throw Failure(Stage.LOCAL_UNCONFIRMED, revision) }

        try { refreshPresentation() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { throw Failure(Stage.PRESENTATION_UNCONFIRMED, revision) }
        Receipt(revision)
    }

    internal data class Theme(val revision: Long, val preference: String, val user: String?, val base: String?) {
        // ConfigEditor removes a redundant user override when it equals the inherited system default.
        val durableSystem: Boolean get() = preference == "system" && (user == "system" || user == null && base == "system")
    }

    companion object {
        internal val removedKeys = setOf(
            "assistantName", "browserEncryptedDns", "darkTheme", "brandSidebarLabel", "brandHeroBadge",
            "brandWelcomeTemplate", "brandComposerTemplate", "brandLogoBackgrounds", "themeColorScheme",
            "themeBackgroundColor", "resetTheme",
        )
        internal fun themeMutation(revision: Long): JSONObject = JSONObject().put("ns", "ui-theme").put("expectedRevision", revision)
            .put("ops", JSONArray().put(JSONObject().put("op", "set").put("path", JSONArray().put("preference")).put("value", "system")))

        internal fun parseTheme(row: JSONObject): Theme {
            require(row.getString("ns") == "ui-theme")
            val revision = row.get("revision")
            require(revision is Number && revision.toDouble().isFinite() && revision.toDouble() == revision.toLong().toDouble()
                && revision.toDouble() in 0.0..9_007_199_254_740_991.0)
            val value = row.getJSONObject("value").getString("preference")
            require(value in setOf("system", "light", "dark"))
            fun source(key: String): String? {
                val source = row.optJSONObject(key) ?: return null
                if (!source.has("preference")) return null
                return source.getString("preference").also { require(it in setOf("system", "light", "dark")) }
            }
            return Theme(revision.toLong(), value, source("user"), source("base"))
        }
        private fun describeTheme(root: JSONObject): Theme {
            if (root.opt("writable") != true) throw Failure(Stage.NOT_READY)
            val namespaces = root.getJSONArray("namespaces")
            val matching = (0 until namespaces.length()).map(namespaces::getJSONObject).filter { it.optString("ns") == "ui-theme" }
            if (matching.size != 1) throw Failure(Stage.NOT_READY)
            return parseTheme(matching.single())
        }
        internal fun localResetConfirmed(preferences: SharedPreferences): Boolean = removedKeys.none(preferences::contains)
            && preferences.getString("themePreference", null) == "system"

        private fun presentationRefresh(context: Context, changed: suspend () -> Unit): suspend () -> Unit = {
            withContext(Dispatchers.Main.immediate) {
                AppBranding.state(context, context.getSharedPreferences("product", Context.MODE_PRIVATE))
                PetOverlay.refresh(context)
                changed()
            }
        }
    }
}
