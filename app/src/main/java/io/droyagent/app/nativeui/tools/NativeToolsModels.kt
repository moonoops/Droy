package io.droyagent.app.nativeui.tools

import io.droyagent.app.nativeui.data.NativeRuntimeState
import java.io.File

enum class NativeToolsPage { HOME, FILES, CONTENT, CHANGES, PREVIEW, BROWSER, TERMINALS, TERMINAL }
data class NativeToolsSession(val id: String, val title: String, val readOnly: Boolean)
data class NativeToolFile(val name: String, val type: String, val size: Long?)
data class NativeToolDirectory(val path: String, val entries: List<NativeToolFile>, val truncated: Boolean)
data class NativeToolStat(val path: String, val version: String, val bytes: Long?)
data class NativeToolText(val stat: NativeToolStat, val text: String, val offset: Long, val lines: Long, val eof: Boolean)
data class NativeToolBytes(val stat: NativeToolStat, val data: ByteArray, val offset: Long, val eof: Boolean)
data class NativeToolDeclaration(val index: Int, val path: String, val description: String?)
data class NativeToolContent(val seq: Long, val turn: Long, val type: String, val files: List<NativeToolDeclaration>)
data class NativeToolContentPage(val entries: List<NativeToolContent>, val nextBefore: Long?)
data class NativeToolChange(val path: String, val display: String, val added: Long, val deleted: Long, val binary: Boolean, val oversized: Boolean)
data class NativeToolChanges(val seq: Long, val turn: Long, val files: List<NativeToolChange>, val total: Long, val added: Long, val deleted: Long)
data class NativeToolDiff(val kind: String, val path: String, val display: String, val coarse: Boolean, val lines: List<String>)
data class NativeToolPreview(val path: String, val kind: String, val stat: NativeToolStat?, val text: String = "",
    val data: ByteArray? = null, val offset: Long = 1, val lines: Long = 0, val eof: Boolean = true,
    val privatePdf: File? = null, val missingFonts: List<String> = emptyList())
sealed interface NativeToolSource {
    data class Workspace(val sessionId: String, val path: String) : NativeToolSource
    data class Attachment(val sessionId: String, val seq: Long, val attachmentId: String) : NativeToolSource
}
data class NativeToolAttachmentInfo(val kind: String, val attachmentId: String, val name: String, val bytes: Long, val mediaType: String)
data class NativeToolPdf(val data: ByteArray, val missingFonts: List<String>, val sourceStat: NativeToolStat? = null)
data class NativeToolCopyPlan(val source: NativeToolSource, val name: String, val bytes: Long?, val version: String,
    val canonicalPath: String? = null, val mediaType: String? = null, val sha256: String? = null)
data class NativeToolFileAction(val id: String, val file: File, val mediaType: String, val saving: Boolean)
data class NativeToolTerminal(val id: String, val title: String, val shellName: String, val shellPath: String,
    val cwd: String, val cols: Int, val rows: Int, val state: String, val exitCode: Long?, val failed: Boolean)
data class NativeTerminalKey(val sessionId: String, val id: String)
data class NativeBrowserTab(val id: String, val title: String, val url: String)
data class NativeBrowserState(val id: String, val title: String = "", val url: String = "", val loading: Boolean = false,
    val canGoBack: Boolean = false, val canGoForward: Boolean = false, val open: Boolean = true, val failed: Boolean = false)
data class NativeBrowserNavigation(val requestId: String, val tabId: String, val url: String)
data class NativeToolsState(
    val runtime: NativeRuntimeState = NativeRuntimeState(), val page: NativeToolsPage = NativeToolsPage.HOME,
    val sessions: List<NativeToolsSession> = emptyList(), val selectedSessionId: String? = null,
    val loading: Boolean = false, val error: String? = null, val directory: NativeToolDirectory? = null,
    val watchAvailable: Boolean? = null, val content: List<NativeToolContent> = emptyList(), val contentNextBefore: Long? = null,
    val changes: NativeToolChanges? = null, val diff: NativeToolDiff? = null, val preview: NativeToolPreview? = null,
    val terminals: List<NativeToolTerminal> = emptyList(), val terminalKey: NativeTerminalKey? = null,
    val terminalEpoch: Long = 0, val terminalWritable: Boolean = false, val terminalConnected: Boolean = false,
    val terminalPendingBytes: Int = 0, val terminalCreating: Boolean = false, val terminalCreateUnconfirmed: Boolean = false,
    val terminalReconnect: Int = 0, val browserTabs: List<NativeBrowserTab> = emptyList(), val selectedBrowserId: String? = null,
    val browser: NativeBrowserState? = null,
    val pendingBrowserNavigation: NativeBrowserNavigation? = null,
    val browserSourceUrl: String? = null,
    val fileSource: NativeToolSource? = null, val exporting: Boolean = false, val fileFeedback: String? = null,
) {
    val selectedSession: NativeToolsSession? get() = sessions.firstOrNull { it.id == selectedSessionId }
    val browserDisplayUrl: String get() = browser?.takeIf { it.id == selectedBrowserId }?.url?.takeIf(String::isNotBlank)
        ?: browserSourceUrl.orEmpty()
}

internal fun NativeToolsState.withBrowserNavigation(navigation: NativeBrowserNavigation): NativeToolsState = copy(
    page = NativeToolsPage.BROWSER, selectedBrowserId = navigation.tabId, browser = null,
    pendingBrowserNavigation = navigation, loading = false, error = null,
    browserSourceUrl = navigation.url,
)

internal fun NativeToolsState.consumeBrowserNavigation(requestId: String): NativeToolsState =
    if (pendingBrowserNavigation?.requestId == requestId && pendingBrowserNavigation.tabId == selectedBrowserId)
        copy(pendingBrowserNavigation = null) else this

internal object NativeToolsPaths {
    fun child(parent: String, name: String): String {
        require(name.isNotEmpty() && name !in setOf(".", "..") && '/' !in name && '\u0000' !in name)
        return if (parent.isEmpty()) name else "$parent/$name"
    }
    fun parent(path: String): String = path.substringBeforeLast('/', "")
    fun kind(path: String): String = when (path.substringAfterLast('.').lowercase(java.util.Locale.ROOT)) {
        "png", "jpg", "jpeg", "webp", "gif" -> "image"
        "html", "htm" -> "html"
        "pdf" -> "pdf"
        "doc", "docx", "xls", "xlsx", "ppt", "pptx" -> "office"
        else -> "text"
    }
}
