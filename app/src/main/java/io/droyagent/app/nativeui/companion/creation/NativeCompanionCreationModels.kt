package io.droyagent.app.nativeui.companion.creation

import io.droyagent.app.nativeui.companion.data.NativeCompanionAttachment
import io.droyagent.app.nativeui.companion.data.NativeCompanionSettings
import io.droyagent.app.nativeui.data.NativeModel
import io.droyagent.app.nativeui.data.NativeRuntimeState
import io.droyagent.app.nativeui.media.NativeLocalAttachment

enum class NativeCreationPage { TEMPLATES, EDITOR, PERSONALITY, MODEL, INTERACTION, BACKGROUND }
enum class NativeCreationImageKind { AVATAR, BACKGROUND }
enum class NativeCreationImagePhase { PICKING, IMPORTING, UPLOADING, INTERRUPTED, UPLOADED }
data class NativeCreationTemplate(
    val id: String, val name: String, val suggestedName: String, val avatarUrl: String,
    val personality: String, val behaviorStyle: String, val replyStyle: String,
    val settings: NativeCompanionSettings, val backgroundJson: String,
)
data class NativeCreationEditor(
    val template: NativeCreationTemplate, val name: String = template.suggestedName,
    val personalityOverride: String = "", val model: NativeModel? = null,
    val settings: NativeCompanionSettings = template.settings,
    val backgroundJson: String = template.backgroundJson, val avatarUpload: NativeCompanionAttachment? = null,
) {
    val avatarUrl get() = avatarUpload?.url ?: template.avatarUrl
    val dirty get() = name != template.suggestedName || personalityOverride.isNotEmpty() || model != null || settings != template.settings || backgroundJson != template.backgroundJson || avatarUpload != null
    val valid get() = NativeCreationNames.valid(name) && personalityOverride.toByteArray().size <= 16384
}
data class NativeCreationImageOperation(
    val id: String, val ownerRequestId: String, val draftId: String, val kind: NativeCreationImageKind,
    val phase: NativeCreationImagePhase, val uri: String? = null, val local: NativeLocalAttachment? = null,
    val uploaded: NativeCompanionAttachment? = null,
)
data class NativeCreationJournal(
    val requestId: String, val editor: NativeCreationEditor, val draftId: String? = null,
    val frozenPayload: String? = null, val pendingCancel: Boolean = false, val image: NativeCreationImageOperation? = null,
)
data class NativeCreationDraftStatus(val id: String, val state: String, val companionId: String?, val uploads: List<NativeCompanionAttachment>)
data class NativeCreationReceipt(val requestId: String, val companionId: String, val templateId: String?, val replayed: Boolean)
data class NativeCreationCancel(val cancelled: Boolean, val state: String, val companionId: String?)
data class NativeCreationImageRequest(val id: String, val kind: NativeCreationImageKind)
data class NativeCreationState(
    val runtime: NativeRuntimeState = NativeRuntimeState(), val page: NativeCreationPage = NativeCreationPage.TEMPLATES,
    val templates: List<NativeCreationTemplate> = emptyList(), val models: List<NativeModel> = emptyList(),
    val defaultModel: NativeModel? = null,
    val loading: Boolean = false, val catalogLoaded: Boolean = false, val catalogError: String? = null,
    val modelsLoaded: Boolean = false, val modelsError: String? = null,
    val journal: NativeCreationJournal? = null, val restored: Boolean = false, val restoreError: Boolean = false,
    val busy: Boolean = false, val checking: Boolean = false, val checked: Boolean = false,
    val error: String? = null, val imageError: String? = null, val createdId: String? = null,
    val draftState: String? = null,
) {
    val editor get() = journal?.editor
    val image get() = journal?.image
    val creating get() = journal?.frozenPayload != null
    val imageActive get() = image?.phase in setOf(NativeCreationImagePhase.PICKING, NativeCreationImagePhase.IMPORTING, NativeCreationImagePhase.UPLOADING)
    val canEdit get() = restored && !restoreError && !busy && !checking && !creating && journal?.pendingCancel != true && !imageActive
    val canCreate get() = runtime.ready && canEdit && editor?.valid == true && image == null
}

internal object NativeCreationNames {
    fun valid(value: String): Boolean {
        val trimmed = value.trim { it.isWhitespace() || it == '\uFEFF' }
        if (trimmed.isEmpty() || trimmed.codePointCount(0, trimmed.length) > 80 || value.toByteArray().size > 320 || value.any { it.code <= 31 || it.code == 127 }) return false
        var index = 0
        while (index < value.length) {
            val char = value[index]
            if (Character.isHighSurrogate(char)) {
                if (index + 1 >= value.length || !Character.isLowSurrogate(value[index + 1])) return false
                index += 2
            } else {
                if (Character.isLowSurrogate(char)) return false
                index++
            }
        }
        return true
    }
}

internal interface NativeCreationRepository {
    suspend fun templates(): List<NativeCreationTemplate>
    suspend fun models(): List<NativeModel>
    fun defaultModel(): NativeModel? = null
    suspend fun ensureDraft(requestId: String): NativeCreationDraftStatus
    suspend fun draft(id: String): NativeCreationDraftStatus
    suspend fun create(payload: String): NativeCreationReceipt
    suspend fun cancelDraft(id: String): NativeCreationCancel
    suspend fun upload(draftId: String, item: NativeLocalAttachment): NativeCompanionAttachment
}
internal interface NativeCreationJournalStore {
    suspend fun read(): NativeCreationJournal?
    suspend fun write(value: NativeCreationJournal?)
}
internal interface NativeCreationImageStore {
    suspend fun importImage(operationId: String, uri: String): NativeLocalAttachment
    suspend fun remove(importId: String)
}
