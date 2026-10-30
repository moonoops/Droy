package io.droyagent.app.nativeui.tools

import kotlinx.coroutines.flow.Flow
import org.json.JSONObject

internal interface NativeToolsRepository {
    suspend fun sessions(): List<NativeToolsSession>
    suspend fun directory(sessionId: String, path: String): NativeToolDirectory
    suspend fun stat(sessionId: String, path: String): NativeToolStat
    suspend fun text(sessionId: String, path: String, offset: Long = 1): NativeToolText
    suspend fun bytes(sessionId: String, path: String): NativeToolBytes
    fun watch(sessionId: String, path: String): Flow<JSONObject>
    suspend fun content(sessionId: String, before: Long?): NativeToolContentPage
    suspend fun changes(sessionId: String, seq: Long): NativeToolChanges
    suspend fun diff(sessionId: String, seq: Long, index: Int): NativeToolDiff
    suspend fun terminals(sessionId: String): List<NativeToolTerminal>
    suspend fun createTerminal(sessionId: String, id: String): NativeToolTerminal
    suspend fun renameTerminal(key: NativeTerminalKey, title: String)
    suspend fun closeTerminal(key: NativeTerminalKey)
    suspend fun attach(key: NativeTerminalKey): String
    fun terminalFrames(attachmentId: String): Flow<JSONObject>
    suspend fun input(attachmentId: String, frame: JSONObject)
    suspend fun detach(attachmentId: String)
    suspend fun attachment(source: NativeToolSource.Attachment): NativeToolAttachmentInfo
    suspend fun attachmentBytes(source: NativeToolSource.Attachment): ByteArray
    suspend fun pdf(source: NativeToolSource): NativeToolPdf
    suspend fun describeCopy(source: NativeToolSource): NativeToolCopyPlan
    suspend fun copy(plan: NativeToolCopyPlan, append: suspend (ByteArray) -> Unit)
}
