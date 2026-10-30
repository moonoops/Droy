package io.droyagent.app.nativeui.schedule

import io.droyagent.app.nativeui.data.NativeRuntimeState

data class NativeScheduleKey(val sessionId: String, val id: String)

/** The pure public ScheduleRecord; catalog metadata never enters a CAS expectation. */
data class NativeScheduleRecord(
    val id: String,
    val kind: String,
    val title: String,
    val prompt: String,
    val scheduledAt: String,
    val afterSeconds: Long? = null,
    val everySeconds: Long? = null,
    val time: String? = null,
    val timeZone: String? = null,
    val weekdays: List<Int> = emptyList(),
    val expression: String? = null,
)

data class NativeScheduleDelivery(
    val scheduledAt: String,
    val deliveredAt: String,
    val messageId: String,
    val prompt: String? = null,
)

data class NativeScheduleEntry(
    val record: NativeScheduleRecord,
    val sessionId: String,
    val status: String,
    val lastDelivery: NativeScheduleDelivery? = null,
) {
    val key: NativeScheduleKey get() = NativeScheduleKey(sessionId, record.id)
}

data class NativeScheduleTarget(val id: String, val title: String, val workspaceTitle: String? = null)
internal data class NativeScheduleTargets(val eligible: List<NativeScheduleTarget>, val titles: Map<String, String>)

data class NativeScheduleHistory(
    val records: List<NativeScheduleDelivery> = emptyList(),
    val nextBefore: String? = null,
    val earlierRecordsUnavailable: Boolean = false,
    val earlierRecordsPruned: Boolean = false,
    val retentionDays: Int? = null,
    val retentionRecords: Int? = null,
    val loaded: Boolean = false,
    val loading: Boolean = false,
    val error: String? = null,
)

data class NativeScheduleTiming(
    val kind: String,
    val date: String = "",
    val time: String = "09:00:00.000",
    val timeZone: String = "UTC",
    val seconds: String = "3600",
    val weekdays: List<Int> = emptyList(),
    val expression: String = "",
)

data class NativeScheduleDraft(
    val sessionId: String?,
    val title: String,
    val prompt: String,
    val timing: NativeScheduleTiming,
    val expected: NativeScheduleRecord? = null,
    val originalTiming: NativeScheduleTiming? = null,
) {
    val creating: Boolean get() = expected == null
    val dirty: Boolean get() {
        val original = expected ?: return title.isNotEmpty() || prompt.isNotEmpty() || originalTiming != null && timing != originalTiming
        return title.trim() != original.title || prompt.trim() != original.prompt || timing != originalTiming
    }
}

data class NativeScheduleUpdate(val key: NativeScheduleKey, val expected: NativeScheduleRecord, val title: String?, val prompt: String?, val change: NativeScheduleTiming?)

sealed interface NativeScheduleUpdateResult {
    data class Record(val record: NativeScheduleRecord, val updated: Boolean) : NativeScheduleUpdateResult
    data class Rejected(val code: String) : NativeScheduleUpdateResult
}

data class NativeScheduleDeleteResult(val deleted: Boolean, val code: String? = null)

sealed interface NativeScheduleHistoryResult {
    data class Page(val value: NativeScheduleHistory) : NativeScheduleHistoryResult
    data class Rejected(val code: String) : NativeScheduleHistoryResult
}

enum class NativeSchedulePage { CATALOG, DETAIL, EDITOR, HISTORY }
enum class NativeScheduleOperation { CREATE, UPDATE, DELETE }

data class NativeScheduleUncertain(
    val operation: NativeScheduleOperation,
    val key: NativeScheduleKey?,
    val draft: NativeScheduleDraft?,
    val checked: Boolean = false,
)

data class NativeSchedulesState(
    val runtime: NativeRuntimeState = NativeRuntimeState(),
    val entries: List<NativeScheduleEntry> = emptyList(),
    val catalogLoaded: Boolean = false,
    val catalogError: String? = null,
    val loading: Boolean = false,
    val targets: List<NativeScheduleTarget> = emptyList(),
    val sessionTitles: Map<String, String> = emptyMap(),
    val targetsLoaded: Boolean = false,
    val targetsLoading: Boolean = false,
    val targetsError: String? = null,
    val page: NativeSchedulePage = NativeSchedulePage.CATALOG,
    val selectedKey: NativeScheduleKey? = null,
    val history: NativeScheduleHistory = NativeScheduleHistory(),
    val editor: NativeScheduleDraft? = null,
    val saving: Boolean = false,
    val error: String? = null,
    val feedback: String? = null,
    val uncertain: NativeScheduleUncertain? = null,
) {
    val selected: NativeScheduleEntry? get() = entries.firstOrNull { it.key == selectedKey }
}
