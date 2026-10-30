package io.droyagent.app

import android.Manifest
import android.app.Activity
import android.app.NotificationManager
import android.content.Intent
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import io.droyagent.androiduse.AccessibilityRecovery
import io.droyagent.androiduse.DroyAccessibilityService
import io.droyagent.androiduse.ShizukuAccess
import io.droyagent.app.nativeui.permissions.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** Activity owns Android prompts; product choices remain in their existing services. */
class NativePermissionCoordinator(private val owner: Activity, saved: Bundle?) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val main = Handler(Looper.getMainLooper())
    private val preferences = ProductPermissions.preferences(owner)
    private val policy = NativePermissionPolicyStore(preferences)
    private val firstUse = NativeFirstUseAuthorization.get(owner)
    private val catalog = CapabilityCatalog(owner)
    private val capabilityPreferences = catalog.groups().let { groups ->
        (0 until groups.length()).map { groups.getJSONObject(it).getString("preference") }.toSet()
    }
    private val _state = MutableStateFlow(NativePermissionsState())
    val state: StateFlow<NativePermissionsState> = _state.asStateFlow()
    private lateinit var queue: PermissionSetup
    private var polling: Job? = null
    private var refreshing: Job? = null
    private var refreshAgain = false
    private var resumed = false
    private var screenVisible = false
    private var closed = false
    private var batchIndependent = saved?.getStringArrayList("nativePermissionBatchIndependent")?.toList().orEmpty()
    private var observedStep: String? = saved?.getString("nativePermissionObservedStep")
    private var pendingApprovalId: String? = saved?.getString("nativePermissionPendingApprovalId")
    private val preferenceListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == null || key in capabilityPreferences || key in observedPreferences
            || key.startsWith("nativePermission") || key.startsWith("permissionRequested:")) refresh()
    }

    init {
        SetupManager.initialize(owner)
        queue = PermissionSetup(owner, ::queueChanged, saved)
        reconcilePendingApproval()
        preferences.registerOnSharedPreferenceChangeListener(preferenceListener)
        refresh()
    }

    fun setScreenVisible(visible: Boolean) {
        if (closed) return
        if (Looper.myLooper() != Looper.getMainLooper()) { main.post { setScreenVisible(visible) }; return }
        val becameVisible = visible && !screenVisible
        screenVisible = visible
        if (becameVisible) refresh()
        reconcilePolling()
    }

    fun resume() {
        if (closed) return
        resumed = true
        queue.resume()
        refresh()
        reconcilePolling()
    }

    fun pause() {
        resumed = false
        queue.pause()
        reconcilePolling()
    }

    fun save(out: Bundle) {
        queue.save(out)
        out.putStringArrayList("nativePermissionBatchIndependent", ArrayList(batchIndependent))
        out.putString("nativePermissionObservedStep", observedStep)
        out.putString("nativePermissionPendingApprovalId", pendingApprovalId)
    }

    fun close() {
        closed = true; resumed = false
        preferences.unregisterOnSharedPreferenceChangeListener(preferenceListener)
        queue.close(); scope.cancel(); main.removeCallbacksAndMessages(null)
    }

    fun onRuntimeResult(request: Int, permissions: Array<out String>, grants: IntArray): Boolean {
        val handled = queue.onRuntimeResult(request, permissions)
        if (handled) {
            HardwareRuntime.onPermissionsChanged(owner)
            NotificationManagement.refreshAccess(owner)
            refresh()
        }
        return handled
    }

    fun onActivityResult(request: Int, result: Int, data: Intent?): Boolean {
        val handled = queue.onActivityResult(request)
        if (handled) {
            HardwareRuntime.onPermissionsChanged(owner)
            NotificationManagement.refreshAccess(owner)
            refresh()
        }
        return handled
    }

    fun onAction(action: NativePermissionAction) {
        if (closed) return
        if (Looper.myLooper() != Looper.getMainLooper()) { main.post { onAction(action) }; return }
        try {
            when (action) {
                NativePermissionAction.Refresh -> { _state.update { it.copy(error = null) }; refresh(); return }
                NativePermissionAction.DismissError -> { _state.update { it.copy(error = null) }; return }
                is NativePermissionAction.SelectPolicy -> selectPolicy(action)
                is NativePermissionAction.ApprovePending -> {
                    requireForeground(); requireIdleQueue()
                    val request = firstUse.approvePending(action.id)
                    pendingApprovalId = request.id
                    try {
                        if (request.capabilityKeys.isNotEmpty()) SetupManager.update(owner, JSONObject().put("capabilities",
                            JSONObject(request.capabilityKeys.associateWith { true })))
                        NotificationManagement.get(owner).rescan()
                        check(firstUse.active(action.id)) { "PERMISSION_PENDING_EXPIRED" }
                        queue.requestOrdinaryPermissions(request.permissionIds)
                    } catch (failure: Exception) {
                        pendingApprovalId = null
                        firstUse.declinePending(action.id)
                        throw failure
                    }
                }
                is NativePermissionAction.DeclinePending -> {
                    if (pendingApprovalId == action.id) { pendingApprovalId = null; queue.cancel() }
                    firstUse.declinePending(action.id)
                }
                NativePermissionAction.SetUpOrdinary -> {
                    requireForeground(); requireIdleQueue()
                    val choices = NativePermissionPlans.ordinaryProductChoices(SetupManager.capabilityKeys(owner))
                    SetupManager.update(owner, JSONObject().put("capabilities", JSONObject(choices)))
                    policy.select(NativeAuthorizationPolicy.BATCH)
                    NotificationManagement.get(owner).rescan()
                    batchIndependent = buildList {
                        if (!DroyAccessibilityService.isConnected(owner)) add("accessibility")
                        if (rootExecutablePresent() && !SetupManager.status(owner).getJSONObject("deviceVerification").optBoolean("rootVerified")) add("root")
                        if (shizukuInstalled() && !ShizukuAccess.get(owner).status().optBoolean("authorized")) add("shizuku")
                        if (!BackgroundRuntime.state(owner).optBoolean("backgroundRuntimeEnabled")) add("background")
                        if (owner.getSystemService(android.os.PowerManager::class.java)?.isIgnoringBatteryOptimizations(owner.packageName) == false) add("battery")
                    }
                    queue.requestOrdinaryBatch(choices.keys)
                }
                is NativePermissionAction.SkipIndependent -> {
                    require(action.id == batchIndependent.firstOrNull())
                    policy.recordOutcome(action.id, NativePermissionOutcome.SKIPPED)
                    batchIndependent = batchIndependent.drop(1)
                }
                is NativePermissionAction.SetCapabilityGroup -> {
                    require(action.preferences.isNotEmpty() && action.preferences.all { it in SetupManager.capabilityKeys(owner) && it != "allowRoot" })
                    requireIdleQueue()
                    SetupManager.update(owner, JSONObject().put("capabilities", JSONObject(action.preferences.associateWith { action.enabled })))
                    NotificationManagement.get(owner).rescan()
                    if (action.enabled && policy.selected() == NativeAuthorizationPolicy.BATCH) queue.requestOrdinaryBatch(action.preferences)
                }
                NativePermissionAction.GrantVoiceCapabilities -> {
                    requireForeground(); requireIdleQueue()
                    // One explicit product choice; system authorization remains owned by the pending capture/queue.
                    val keys = listOf("allowAudioMicrophone", "allowAudioTranscription")
                    keys.forEach { policy.recordRequested("capability:$it") }
                    SetupManager.update(owner, JSONObject().put("capabilities", JSONObject()
                        .put("allowAudioMicrophone", true).put("allowAudioTranscription", true)))
                    keys.forEach { policy.recordOutcome("capability:$it", NativePermissionOutcome.GRANTED) }
                    NotificationManagement.get(owner).rescan()
                }
                is NativePermissionAction.SetCapability -> {
                    require(action.preference in SetupManager.capabilityKeys(owner) && action.preference != "allowRoot")
                    if (action.enabled) requireIdleQueue() else if (queue.active()) queue.cancel()
                    SetupManager.update(owner, JSONObject().put("capabilities", JSONObject().put(action.preference, action.enabled)))
                    NotificationManagement.get(owner).rescan()
                    if (action.enabled && policy.selected() == NativeAuthorizationPolicy.BATCH) {
                        requireForeground(); queue.forOrdinaryPreference(action.preference)
                    }
                }
                is NativePermissionAction.RequestSystem -> {
                    require(action.id in ordinaryIds)
                    requireForeground(); requireIdleQueue()
                    if (!queue.requestIfNeeded(action.id)) openSystemSettings(action.id)
                }
                NativePermissionAction.ConfirmAccessibility -> {
                    requireForeground(); requireIdleQueue()
                    if (batchIndependent.firstOrNull() == "accessibility") batchIndependent = batchIndependent.drop(1)
                    if (!queue.requestIfNeeded("accessibility")) openSystemSettings("accessibility")
                }
                NativePermissionAction.ConfirmRoot -> {
                    requireForeground(); requireIdleQueue()
                    if (batchIndependent.firstOrNull() == "root") batchIndependent = batchIndependent.drop(1)
                    SetupManager.update(owner, JSONObject().put("capabilities", JSONObject().put("allowRoot", true)))
                    policy.recordRequested("root")
                    SetupRootVerification.start(owner)
                }
                NativePermissionAction.DisableRoot -> {
                    if (queue.active()) queue.cancel()
                    SetupManager.update(owner, JSONObject().put("capabilities", JSONObject().put("allowRoot", false)))
                }
                NativePermissionAction.ConfirmShizuku -> {
                    requireForeground(); requireIdleQueue()
                    if (batchIndependent.firstOrNull() == "shizuku") batchIndependent = batchIndependent.drop(1)
                    if (!ShizukuAccess.get(owner).status().getBoolean("running")) openShizuku()
                    else if (!queue.requestIfNeeded("shizuku")) openShizuku()
                }
                NativePermissionAction.OpenShizuku -> { requireForeground(); openShizuku() }
                NativePermissionAction.CancelQueue -> {
                    batchIndependent = emptyList();
                    pendingApprovalId = null
                    val current = queue.state()
                    val skipped = current.getJSONArray("pending").let { values -> (0 until values.length()).map(values::getString) } +
                        listOfNotNull(current.optString("current").takeIf(String::isNotBlank))
                    queue.cancel()
                    firstUse.cancelPending()
                    skipped.forEach { policy.recordOutcome(it, NativePermissionOutcome.SKIPPED) }
                }
                is NativePermissionAction.SetBackgroundEnabled -> {
                    requireForeground()
                    if (batchIndependent.firstOrNull() == "background") batchIndependent = batchIndependent.drop(1)
                    BackgroundRuntime.setEnabled(owner, action.enabled)
                    saveDecision(BACKGROUND_DECISION, if (action.enabled) "enabled" else "disabled")
                }
                NativePermissionAction.ReviewBackgroundLater -> saveDecision(BACKGROUND_DECISION, "later")
                NativePermissionAction.RequestRuntimeNotifications -> {
                    requireForeground(); requireIdleQueue()
                    if (!queue.requestIfNeeded("notifications")) openRuntimeNotificationSettings()
                }
                NativePermissionAction.OpenRuntimeNotificationSettings -> { requireForeground(); openRuntimeNotificationSettings() }
                NativePermissionAction.OpenEnergySettings -> {
                    requireForeground()
                    if (batchIndependent.firstOrNull() == "battery") batchIndependent = batchIndependent.drop(1)
                    owner.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                }
                is NativePermissionAction.ReviewEnergy -> saveDecision(ENERGY_DECISION, if (action.later) "later" else "reviewed")
                is NativePermissionAction.SetToolsPaused -> {
                    ProductPermissions.setStopped(owner, action.paused)
                    RuntimeService.applySavedOperationState()
                    if (action.paused) { pendingApprovalId = null; queue.cancel(); firstUse.cancelPending() }
                }
            }
            _state.update { it.copy(error = null) }
        } catch (error: Exception) {
            val code = when (error.message) {
                "PERMISSION_QUEUE_BUSY" -> "permission_queue_busy"
                "PERMISSION_ACTIVITY_NOT_READY" -> "permission_activity_not_ready"
                "PERMISSION_POLICY_NOT_SAVED" -> "permission_policy_not_saved"
                "SHIZUKU_NOT_RUNNING" -> "permission_shizuku_not_running"
                "PERMISSION_PENDING_EXPIRED" -> "permission_pending_expired"
                else -> if (error is io.droyagent.androiduse.ActionException && error.reason == "authorization_expired")
                    "permission_pending_expired" else "permission_action_failed"
            }
            _state.update { it.copy(error = code) }
        }
        refresh()
    }

    fun refresh() {
        if (closed || !::queue.isInitialized) return
        if (Looper.myLooper() != Looper.getMainLooper()) { main.post { refresh() }; return }
        reconcilePendingApproval()
        reconcilePolling()
        if (refreshing?.isActive == true) { refreshAgain = true; return }
        val queueSnapshot = queue.state()
        refreshing = scope.launch {
            if (!_state.value.fresh) _state.update { it.copy(loading = true) }
            try {
                val snapshot = withContext(Dispatchers.IO) { snapshot(queueSnapshot) }
                if (!snapshot.root.running && policy.records()["root"]?.outcome == NativePermissionOutcome.REQUESTING)
                    policy.recordOutcome("root", if (snapshot.root.verified) NativePermissionOutcome.GRANTED else NativePermissionOutcome.NOT_GRANTED)
                _state.update { snapshot.copy(error = it.error?.takeUnless { error -> error == "permission_state_failed" }, loading = false, fresh = true) }
            } catch (error: Exception) {
                if (error is kotlinx.coroutines.CancellationException) throw error
                _state.update { it.copy(loading = false, fresh = false, error = "permission_state_failed") }
            } finally {
                refreshing = null
                reconcilePolling()
                if (refreshAgain && !closed) { refreshAgain = false; refresh() }
            }
        }
    }

    private fun shouldPoll(): Boolean = !closed && resumed &&
        (screenVisible || queue.active() || SetupRootVerification.status().optBoolean("running"))

    private fun reconcilePolling() {
        if (!shouldPoll()) {
            polling?.cancel(); polling = null
        } else if (polling?.isActive != true) polling = scope.launch {
            // Product changes arrive through preferences; only live permission flows need periodic system reads.
            while (isActive && shouldPoll()) {
                delay(750)
                if (shouldPoll()) refresh()
            }
        }
    }

    private fun queueChanged() {
        if (!::queue.isInitialized || closed) return
        val firstUseRequest = pendingApprovalId
        try {
            val current = queue.state().optString("current").takeIf(String::isNotBlank)
            if (current != observedStep) {
                observedStep?.let { previous ->
                    val approved = granted(previous)
                    policy.recordOutcome(previous, if (approved) NativePermissionOutcome.GRANTED else NativePermissionOutcome.NOT_GRANTED)
                    if (!approved && pendingApprovalId != null) {
                        val id = pendingApprovalId!!
                        pendingApprovalId = null
                        firstUse.finishPending(id)
                        queue.cancel()
                    }
                }
                if (current != null) policy.recordRequested(current)
                observedStep = current
            }
            if (!queue.active()) pendingApprovalId?.let { id ->
                pendingApprovalId = null
                firstUse.finishPending(id)
            }
        } catch (_: Exception) {
            if (firstUseRequest != null) {
                pendingApprovalId = null
                observedStep = null
                queue.cancel()
                firstUse.declinePending(firstUseRequest)
            }
            _state.update { it.copy(error = "permission_policy_not_saved") }
        }
        refresh()
    }

    private fun reconcilePendingApproval() {
        pendingApprovalId?.takeIf { !firstUse.active(it) }?.let {
            pendingApprovalId = null
            queue.cancel()
        }
    }

    private fun selectPolicy(action: NativePermissionAction.SelectPolicy) {
        val installation = owner.getSharedPreferences("installation", Context.MODE_PRIVATE)
        val resumeInitial = action.policy == NativeAuthorizationPolicy.ON_FIRST_USE && NativeFirstUseAuthorization.initialFirstUseMayResume(
            action.initialSetup, installation.getBoolean("nativeFirstUseEligible", false), !installation.getBoolean("completed", false),
            installation.getString("source", "phone") == "legacy", policy.selected() == null,
            capabilityPreferences.none { ProductPermissions.allowed(preferences, it) }, preferences.getBoolean("nativePermissionExplicitPause", false))
        policy.select(action.policy)
        if (resumeInitial) {
            check(installation.edit().putBoolean("nativeFirstUseEligible", false).commit()) { "PERMISSION_POLICY_NOT_SAVED" }
            ProductPermissions.setStopped(owner, false)
            RuntimeService.applySavedOperationState()
        }
    }

    private fun snapshot(queue: JSONObject): NativePermissionsState {
        val setup = SetupManager.status(owner)
        val hardware = HardwarePermissionState.status(owner).getJSONObject("hardware")
        val queueState = NativePermissionQueue(queue.getBoolean("active"), queue.optString("current").takeIf(String::isNotBlank),
            queue.getJSONArray("pending").let { values -> (0 until values.length()).map(values::getString) },
            queue.getInt("total"), queue.getInt("completed"), queue.optString("error").takeIf(String::isNotBlank)?.let(NativeLocale::generatedStatus))
        val records = policy.records()
        val system = ordinaryIds.filter { id -> supported(id, hardware) }.map { id ->
            val permissions = PermissionSetup.runtimePermissions(id)
            val blocked = permissions?.any { permission ->
                owner.checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED
                    && preferences.getBoolean("permissionRequested:$permission", false) && !owner.shouldShowRequestPermissionRationale(permission)
            } == true
            NativeSystemPermission(id, NativePermissionFacts.grant(granted(id), records[id] != null, blocked))
        }
        val access = AccessibilityRecovery.get(owner)
        val root = SetupRootVerification.status()
        val shizuku = ShizukuAccess.get(owner).status()
        val rootOptIn = ProductPermissions.allowed(preferences, "allowRoot")
        val background = BackgroundRuntime.state(owner)
        val notifications = requireNotNull(owner.getSystemService(NotificationManager::class.java))
        val channel = notifications.getNotificationChannel(ForegroundStatusNotifications.CHANNEL)
        val power = owner.getSystemService(PowerManager::class.java)
        val groups = catalog.groups()
        val products = (0 until groups.length()).map { index -> groups.getJSONObject(index) }.map { group ->
            NativeProductPermission(group.getString("id"), group.getString("preference"), group.getString("label"),
                group.getString("section"), ProductPermissions.allowed(preferences, group.getString("preference")))
        }.filterNot { it.preference == "allowRoot" }
        return NativePermissionsState(products = products, policy = policy.selected(), system = system, queue = queueState,
            accessibility = NativeAccessibilityPermission(access.enabled(), DroyAccessibilityService.isConnected(owner)),
            root = NativeRootPermission(rootOptIn, rootExecutablePresent(), root.getBoolean("running"),
                rootOptIn && setup.getJSONObject("deviceVerification").optBoolean("rootVerified"),
                root.optString("error").takeIf(String::isNotBlank)?.let(NativeLocale::generatedStatus)),
            shizuku = NativeShizukuPermission(shizukuInstalled(), shizuku.getBoolean("running"), shizuku.getBoolean("authorized"),
                shizuku.optInt("uid", -1) == 0 && !rootOptIn),
            background = NativeBackgroundPermission(background.getBoolean("backgroundRuntimeEnabled"), background.getBoolean("backgroundRuntimeRunning"),
                Build.VERSION.SDK_INT < 33 || owner.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED,
                notifications.areNotificationsEnabled(), channel?.let { it.importance != NotificationManager.IMPORTANCE_NONE },
                preferences.contains(BACKGROUND_DECISION)),
            energy = NativeEnergyPermission(power?.isIgnoringBatteryOptimizations(owner.packageName), preferences.getString(ENERGY_DECISION, null)),
            toolsPaused = ProductPermissions.stopped(owner), pendingRequests = policy.pendingRequests(), batchIndependent = batchIndependent)
    }

    private fun supported(id: String, hardware: JSONObject): Boolean = when (id) {
        "notifications" -> Build.VERSION.SDK_INT >= 33
        "camera" -> owner.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)
        "microphone" -> owner.packageManager.hasSystemFeature(PackageManager.FEATURE_MICROPHONE)
        "bluetooth" -> hardware.getBoolean("bluetooth")
        "nearbyWifi" -> hardware.getBoolean("wifi")
        "phoneState" -> hardware.getBoolean("telephony")
        "activityRecognition" -> hardware.getBoolean("stepCounter")
        "bodySensors" -> hardware.getBoolean("heartRate")
        "location" -> owner.packageManager.hasSystemFeature(PackageManager.FEATURE_LOCATION) || hardware.getBoolean("wifi") || hardware.getBoolean("telephony")
        else -> true
    }

    private fun granted(id: String): Boolean {
        if (id in ordinaryIds) return !PermissionSetup.needsSystemPermission(owner, id)
        return when (id) {
            "accessibility" -> DroyAccessibilityService.isConnected(owner)
            "shizuku" -> ShizukuAccess.get(owner).available()
            "root" -> ProductPermissions.allowed(preferences, "allowRoot") && SetupRootVerification.status().getBoolean("passed")
            else -> throw IllegalArgumentException("UNKNOWN_PERMISSION")
        }
    }

    private fun openSystemSettings(id: String) {
        if (id == "notifications") { openRuntimeNotificationSettings(); return }
        val action = when (id) {
            "accessibility" -> Settings.ACTION_ACCESSIBILITY_SETTINGS
            "notificationAccess" -> Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS
            "usage" -> Settings.ACTION_USAGE_ACCESS_SETTINGS
            "brightness" -> Settings.ACTION_MANAGE_WRITE_SETTINGS
            "petOverlay" -> Settings.ACTION_MANAGE_OVERLAY_PERMISSION
            else -> Settings.ACTION_APPLICATION_DETAILS_SETTINGS
        }
        val intent = Intent(action)
        if (id !in setOf("accessibility", "notificationAccess")) intent.data = Uri.parse("package:${owner.packageName}")
        owner.startActivity(intent)
    }

    private fun openRuntimeNotificationSettings() {
        val manager = requireNotNull(owner.getSystemService(NotificationManager::class.java))
        val channel = manager.getNotificationChannel(ForegroundStatusNotifications.CHANNEL)
        val appSettings = channel == null || !manager.areNotificationsEnabled()
        val intent = Intent(if (appSettings) Settings.ACTION_APP_NOTIFICATION_SETTINGS else Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, owner.packageName)
        if (!appSettings) intent.putExtra(Settings.EXTRA_CHANNEL_ID, ForegroundStatusNotifications.CHANNEL)
        owner.startActivity(intent)
    }

    private fun openShizuku() {
        val intent = owner.packageManager.getLaunchIntentForPackage(SetupComponents.PACKAGE) ?: throw IllegalStateException("SHIZUKU_NOT_RUNNING")
        owner.startActivity(intent)
    }

    private fun shizukuInstalled(): Boolean = try { owner.packageManager.getPackageInfo(SetupComponents.PACKAGE, 0); true }
        catch (_: PackageManager.NameNotFoundException) { false }

    private fun rootExecutablePresent(): Boolean = System.getenv("PATH")?.split(":")?.any { java.io.File(it, "su").canExecute() } == true
    private fun requireForeground() { check(resumed && !owner.isFinishing && !owner.isDestroyed) { "PERMISSION_ACTIVITY_NOT_READY" } }
    private fun requireIdleQueue() { check(!queue.active()) { "PERMISSION_QUEUE_BUSY" } }
    private fun saveDecision(key: String, value: String) { check(preferences.edit().putString(key, value).commit()) { "PERMISSION_POLICY_NOT_SAVED" } }

    companion object {
        private const val BACKGROUND_DECISION = "nativePermissionBackgroundDecision"
        private const val ENERGY_DECISION = "nativePermissionEnergyDecision"
        private val observedPreferences = setOf(ProductPermissions.STOPPED, ProductPermissions.DEFAULT_DENY,
            BackgroundRuntime.ENABLED, BackgroundRuntime.STOPPED, "notificationManagementEnabled", "languagePreference")
        private val ordinaryIds = listOf("notifications", "calendar", "contacts", "microphone", "camera", "location", "bluetooth", "nearbyWifi",
            "phoneState", "activityRecognition", "bodySensors", "notificationAccess", "usage", "brightness", "petOverlay")
    }
}
