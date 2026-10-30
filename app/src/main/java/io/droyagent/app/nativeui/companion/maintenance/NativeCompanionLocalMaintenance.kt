package io.droyagent.app.nativeui.companion.maintenance

import android.app.Application
import android.content.Intent
import androidx.core.content.FileProvider
import io.droyagent.app.NativeCompanionDeletion
import io.droyagent.app.NativeRuntimeHost
import io.droyagent.app.NativeCameraInputHost
import io.droyagent.app.media.MediaInputStore
import io.droyagent.app.nativeui.companion.creation.NativeCompanionCreationViewModel
import io.droyagent.app.nativeui.companion.data.NativeCompanionViewModel
import io.droyagent.app.nativeui.companion.ui.clearCompanionImageCache
import io.droyagent.app.nativeui.data.NativeWorkbenchViewModel
import io.droyagent.app.nativeui.media.*
import io.droyagent.app.nativeui.standardvoice.NativeStandardVoiceViewModel
import io.droyagent.app.nativeui.voiceinput.NativeVoiceInputViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.nio.file.Files

/** Retained by the activity ViewModel; Node owns the companion data, this owns native drafts and caches. */
class NativeCompanionLocalMaintenance(
    private val application: Application,
    private val companions: NativeCompanionViewModel,
    private val media: NativeMediaViewModel,
    private val share: NativeSharePreviewViewModel,
    private val voiceInput: NativeVoiceInputViewModel,
    private val standardVoice: NativeStandardVoiceViewModel,
    private val creation: NativeCompanionCreationViewModel,
    private val workbench: NativeWorkbenchViewModel,
) : NativeCompanionDeletion.LocalParticipant {
    companion object {
        @JvmStatic fun restoreAdmission(application: Application) {
            if (application.getSharedPreferences("native_companion_local_maintenance", 0).contains("plan"))
                NativeCompanionWriteBarrier.frozen = true
        }
    }
    private val journal = application.getSharedPreferences("native_companion_local_maintenance", 0)
    private var acquired = false
    private var drained = false
    init { if (journal.contains("plan")) NativeCompanionWriteBarrier.frozen = true }
    fun setForeground(value: Boolean) { NativeCompanionWriteBarrier.foreground = value }

    override fun prepare(operationId: String, companionId: String, admissionEpoch: String): JSONObject = runBlocking {
        val saved = journal.getString("plan", null)?.let(::JSONObject)
        saved?.let { requireIdentity(it, operationId, companionId, admissionEpoch) }
        withContext(Dispatchers.Main.immediate) {
            if (!drained) {
                check(NativeCompanionWriteBarrier.foreground && !NativeRuntimeHost.isFilePickerOpen() &&
                    !NativeCompanionCreationViewModel.isPickerOpen() && voiceInput.isQuiescentForCompanionDeletion() &&
                    standardVoice.isQuiescentForCompanionDeletion()) { "COMPANION_MAINTENANCE_BUSY" }
                NativeCompanionWriteBarrier.frozen = true
                acquired = true
                // Long-lived observation is stopped first; finite submissions and persistence finish normally.
                companions.freezeForCompanionDeletion()
                workbench.freezeForCompanionDeletion()
                media.freezeForCompanionDeletion(saved == null)
                share.freezeForCompanionDeletion(saved == null)
                voiceInput.freezeForCompanionDeletion(saved == null)
                standardVoice.freezeForCompanionDeletion()
                creation.freezeForCompanionDeletion()
                companions.freezeForCompanionDeletion(); workbench.freezeForCompanionDeletion()
                clearCompanionImageCache()
                drained = true
            }
        }
        val plan = saved ?: NativeCompanionRetirementPlan.create(operationId, companionId, admissionEpoch,
            preferenceSnapshot(), MediaInputStore.nativeDrafts(application).list(), MediaInputStore(application).list()).also(::save)
        JSONObject(plan.getJSONObject("snapshot").toString())
    }

    override fun retire(runtimeReceipt: JSONObject): JSONObject = runBlocking {
        check(acquired && NativeCompanionWriteBarrier.frozen) { "COMPANION_MAINTENANCE_BUSY" }
        val plan = JSONObject(journal.getString("plan", null) ?: error("COMPANION_MAINTENANCE_STORAGE"))
        requireIdentity(plan, runtimeReceipt.getString("operationId"), runtimeReceipt.getString("companionId"), runtimeReceipt.getString("admissionEpoch"))
        check(runtimeReceipt.getString("retirementDigest") == plan.getString("retirementDigest")) { "COMPANION_MAINTENANCE_IDENTITY" }
        val recovery = plan.optJSONObject("workbenchRecovery")?.takeIf { entry ->
            runtimeReceipt.optJSONArray("removedSessionIds")?.let { rows ->
                (0 until rows.length()).any { rows.getString(it) == entry.getString("sessionId") }
            } == true
        }
        val removedViews = NativeCompanionRetirementPlan.removedWorkbenchViews(plan, runtimeReceipt)
        val workPreferences = application.getSharedPreferences("native_workbench", 0)
        recovery?.let { entry ->
            val id = entry.getString("draftId"); val oldId = entry.getString("sessionId")
            if (workPreferences.getString("maintenanceDraftId", null) != id) {
                check(NativeCompanionRetirementPlan.digest(NativeCompanionRetirementPlan.encoded(workPreferences.all["draft:$oldId"])) == entry.getString("textBefore") &&
                    NativeCompanionRetirementPlan.digest(NativeCompanionRetirementPlan.encoded(workPreferences.all["attachments:$oldId"])) == entry.getString("attachmentsBefore")) { "COMPANION_MAINTENANCE_LOCAL_CHANGED" }
                check(!workPreferences.contains("draft:$id") && !workPreferences.contains("attachments:$id")) { "COMPANION_MAINTENANCE_LOCAL_CHANGED" }
                check(workPreferences.edit().putString("draft:$id", entry.getString("text"))
                    .putString("attachments:$id", entry.getString("attachments")).putString("maintenanceDraftId", id)
                    .putBoolean("workspaceChosen", true).remove("draft:$oldId").remove("attachments:$oldId").commit()) { "COMPANION_MAINTENANCE_STORAGE" }
            } else {
                check(workPreferences.getString("draft:$id", null) == entry.getString("text") &&
                    workPreferences.getString("attachments:$id", null) == entry.getString("attachments")) { "COMPANION_MAINTENANCE_LOCAL_CHANGED" }
                // Cold ViewModel initialization may have flushed an empty obsolete draft before detach.
                check(workPreferences.edit().remove("draft:$oldId").remove("attachments:$oldId").commit()) { "COMPANION_MAINTENANCE_STORAGE" }
            }
        }
        for (row in removedViews) {
            val key = row.getString("key"); val current = workPreferences.all[key] ?: continue
            check(NativeCompanionRetirementPlan.digest(NativeCompanionRetirementPlan.encoded(current)) == row.getString("before")) { "COMPANION_MAINTENANCE_LOCAL_CHANGED" }
            check(workPreferences.edit().remove(key).commit()) { "COMPANION_MAINTENANCE_STORAGE" }
        }
        val changes = plan.getJSONArray("changes")
        for (i in 0 until changes.length()) {
            val row = changes.getJSONObject(i)
            val prefs = application.getSharedPreferences(row.getString("preferences"), 0)
            val key = row.getString("key"); val current = prefs.all[key]
            val after = if (row.isNull("after")) null else row.getString("after")
            if (current != after) {
                check(NativeCompanionRetirementPlan.digest(NativeCompanionRetirementPlan.encoded(current)) == row.getString("before")) { "COMPANION_MAINTENANCE_LOCAL_CHANGED" }
                if (row.getString("preferences") == "native_media" && after != null) NativeMediaImportRetention.commit(application, after)
                else check(prefs.edit().apply { if (after == null) remove(key) else putString(key, after) }.commit()) { "COMPANION_MAINTENANCE_STORAGE" }
            }
        }
        // All exact references have been removed before physical originals are retired.
        val retainedImportIds = preferenceSnapshot().values.flatMap { it.values }.flatMap { value ->
            val refs = NativeCompanionRetirementPlan.references(NativeCompanionRetirementPlan.parse(value)).getJSONArray("imports")
            (0 until refs.length()).map(refs::getString)
        }.toSet() + NativeShareInbox.pending(application)
        val imports = plan.getJSONArray("imports")
        for (i in 0 until imports.length()) {
            val item = imports.getJSONObject(i); val id = item.getString("id")
            check(id !in retainedImportIds) { "COMPANION_MAINTENANCE_SHARED_IMPORT" }
            val store = if (item.getString("namespace") == "native") MediaInputStore.nativeDrafts(application) else MediaInputStore(application)
            val rows = store.list()
            val current = (0 until rows.length()).map(rows::getJSONObject).firstOrNull { it.getString("id") == id }
            if (current != null) {
                check(NativeCompanionRetirementPlan.digest(NativeCompanionRetirementPlan.canonical(current)) == item.getString("manifestDigest")) { "COMPANION_MAINTENANCE_LOCAL_CHANGED" }
                if (item.getString("namespace") == "native") NativeDraftMediaStore(application).removeImport(id)
                else store.complete(id, item.getString("consumer"))
                val remaining = store.list()
                check((0 until remaining.length()).none { remaining.getJSONObject(it).getString("id") == id }) { "COMPANION_MAINTENANCE_STORAGE" }
            }
        }
        val captures = plan.getJSONArray("captures")
        for (i in 0 until captures.length()) NativeCameraInputHost.discardOutput(application, captures.getString(i))
        clearOpenCache()
        NativeRuntimeHost.clearCompanionNotifications(application, plan.getString("companionId"))
        withContext(Dispatchers.Main.immediate) {
            companions.retireCompanionLocal(plan.getString("companionId"))
            media.reloadAfterCompanionDeletion(); share.reloadAfterCompanionDeletion(); voiceInput.reloadAfterCompanionDeletion()
            workbench.retireDeletedConversationViews(runtimeReceipt, recovery?.getString("draftId"))
            clearCompanionImageCache()
        }
        save(plan.put("retired", true))
        JSONObject().put("version", 1).put("scope", "android-companion-deletion").put("operationId", plan.getString("operationId"))
            .put("companionId", plan.getString("companionId")).put("admissionEpoch", plan.getString("admissionEpoch"))
            .put("snapshotDigest", runtimeReceipt.getString("androidSnapshotDigest"))
            .put("retirementDigest", plan.getString("retirementDigest")).put("complete", true)
    }

    override fun release() = runBlocking {
        // A cancelled live preparation may never have acquired local ownership.
        check(journal.edit().remove("plan").commit()) { "COMPANION_MAINTENANCE_STORAGE" }
        withContext(Dispatchers.Main.immediate) {
            NativeCompanionWriteBarrier.frozen = false
            if (NativeCompanionWriteBarrier.foreground) {
                companions.startObserving(); workbench.startObserving(); creation.startObserving(); voiceInput.startObserving()
                standardVoice.resumeAfterCompanionDeletion()
            }
            acquired = false; drained = false
        }
    }
    private fun preferenceSnapshot(): Map<String, Map<String, Any?>> = NativeCompanionRetirementPlan.preferenceNames.associateWith {
        application.getSharedPreferences(it, 0).all.toMap()
    }
    private fun requireIdentity(plan: JSONObject, operation: String, companion: String, epoch: String) {
        check(plan.getInt("version") == 1 && plan.getString("operationId") == operation && plan.getString("companionId") == companion && plan.getString("admissionEpoch") == epoch) { "COMPANION_MAINTENANCE_IDENTITY" }
    }
    private fun save(value: JSONObject) {
        check(journal.edit().putString("plan", value.toString()).commit()) { "COMPANION_MAINTENANCE_STORAGE" }
    }
    private fun clearOpenCache() {
        val root = File(application.filesDir, "media-input/captures/native-open")
        check(root.canonicalFile == File(application.filesDir.canonicalFile, "media-input/captures/native-open") && !Files.isSymbolicLink(root.toPath()) && !Files.isSymbolicLink(root.parentFile!!.toPath())) { "COMPANION_MAINTENANCE_STORAGE" }
        if (!root.exists()) return
        val directories = root.listFiles() ?: error("COMPANION_MAINTENANCE_STORAGE")
        for (directory in directories) {
            check(!Files.isSymbolicLink(directory.toPath()) && directory.isDirectory &&
                java.util.UUID.fromString(directory.name).toString() == directory.name && directory.canonicalFile.parentFile == root.canonicalFile) { "COMPANION_MAINTENANCE_STORAGE" }
            val files = directory.listFiles() ?: error("COMPANION_MAINTENANCE_STORAGE")
            for (file in files) {
                check(!Files.isSymbolicLink(file.toPath()) && file.isFile && file.canonicalFile.parentFile == directory.canonicalFile) { "COMPANION_MAINTENANCE_STORAGE" }
                val uri = FileProvider.getUriForFile(application, "${application.packageName}.media-input-files", file)
                application.revokeUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                check(file.delete()) { "COMPANION_MAINTENANCE_STORAGE" }
            }
            check(directory.delete()) { "COMPANION_MAINTENANCE_STORAGE" }
        }
    }
}
