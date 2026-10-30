package io.droyagent.app.nativeui.productsettings

import io.droyagent.app.NativeProductSettingsHost
import io.droyagent.app.nativeui.data.NativeLocalTransport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

enum class ProductSettingsSection(val wire: String) {
    General("general"), Notifications("notifications"), Proactive("proactive"), Pet("pet"), Diagnostics("diagnostics")
}

/** Last rendered values belong to one app shell; services remain the source of truth. */
class ProductSettingsPresentationState {
    private val snapshots = mutableMapOf<ProductSettingsSection, JSONObject>()
    internal fun current(section: ProductSettingsSection): JSONObject? = snapshots[section]
    internal fun remember(section: ProductSettingsSection, snapshot: JSONObject) { snapshots[section] = snapshot }
}

internal class ProductSettingsController(private val host: NativeProductSettingsHost) {
    private var pluginRestartRequired = false
    suspend fun snapshot(section: ProductSettingsSection): JSONObject {
        val value = withContext(Dispatchers.IO) { host.snapshot(section.wire) }
        return value
    }
    suspend fun notificationPlugin(enabled: Boolean? = null): JSONObject {
        val value = NativeLocalTransport().json("/droy/notify/plugin", if (enabled == null) "GET" else "POST",
            enabled?.let { JSONObject().put("enabled", it) })
        require(value.opt("enabled") is Boolean) { "invalid_plugin_response" }
        if (enabled != null) pluginRestartRequired = value.optBoolean("restartRequired")
        else if (value.optBoolean("active") == value.getBoolean("enabled")) pluginRestartRequired = false
        return value
    }
    suspend fun display(key: String, value: Any) = withContext(Dispatchers.IO) { host.setDisplay(key, value) }
    suspend fun notifications(body: JSONObject) = withContext(Dispatchers.IO) { host.notificationManagement(body) }
    suspend fun openMessageNotificationSettings(quiet: Boolean) = withContext(Dispatchers.Main.immediate) { host.openMessageNotificationSettings(quiet) }
    suspend fun proactive(body: JSONObject): JSONObject {
        val previous = if (body.optBoolean("tasksEnabled")) snapshot(ProductSettingsSection.Proactive) else null
        val result = withContext(Dispatchers.IO) { host.configureProactive(body) }
        if (previous != null) {
            try {
                val plugin = notificationPlugin(true)
                check(plugin.optBoolean("active") && !plugin.optBoolean("restartRequired")) { "notification_plugin_not_ready" }
            } catch (error: Exception) {
                withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) {
                    host.configureProactive(JSONObject().put("tasksEnabled", previous.optBoolean("tasksEnabled")))
                }
                throw error
            }
        }
        return result
    }
    suspend fun selectPet(id: String) = withContext(Dispatchers.IO) { host.selectPet(id) }
    suspend fun preview() = withContext(Dispatchers.Main.immediate) { host.previewPet() }
    suspend fun builtInPets(): List<JSONObject> = withContext(Dispatchers.IO) {
        host.builtInPets().rows().map { JSONObject(it.toString()).put("status", "ready") }
    }
    suspend fun pets(): List<JSONObject> {
        val builtIns = builtInPets()
        val builtInIds = builtIns.map { it.getString("id") }.toSet()
        return builtIns + NativeLocalTransport().json("/droy/pets").getJSONArray("pets").rows().filter { it.optString("id") !in builtInIds }
    }
}
internal fun JSONArray?.rows(): List<JSONObject> = if (this == null) emptyList() else (0 until length()).map { getJSONObject(it) }

/** Null maintenance callbacks mean unavailable: never substitute the legacy global attachment deletion. */
data class ProductSettingsActions(
    val onBack: () -> Unit,
    val onPermissions: () -> Unit,
    val onOverlayPermission: () -> Unit,
    val onStartRuntime: () -> Unit,
    val onStopRuntime: () -> Unit,
    val onResetPersonalization: (suspend () -> Unit)? = null,
    val onSafeHistoryMaintenance: (() -> Unit)? = null,
    val onOpenPetStudio: (() -> Unit)? = null,
    val resetPersonalizationReady: Boolean = false,
    val onModels: (() -> Unit)? = null,
)
