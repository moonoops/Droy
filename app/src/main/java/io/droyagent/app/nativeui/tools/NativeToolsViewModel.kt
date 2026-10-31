package io.droyagent.app.nativeui.tools

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.droyagent.app.NativeRuntimeHost
import io.droyagent.app.NativeToolsFileHost
import android.net.Uri
import io.droyagent.app.nativeui.data.NativeApiException
import io.droyagent.app.nativeui.data.NativeRuntimeState
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

class NativeToolsViewModel(application: Application) : AndroidViewModel(application) {
    private val repository: NativeToolsRepository = NativeToolsClient()
    private val terminal = NativeTerminalConnection(repository)
    val terminalState = terminal.state
    private val mutable = MutableStateFlow(NativeToolsState())
    val state = mutable.asStateFlow()
    private var observation: Job? = null
    private var catalog: Job? = null
    private var reading: Job? = null
    private var watching: Job? = null
    private var generation = 0L
    private var previewReturn = NativeToolsPage.FILES
    private var pendingTerminalId: String? = null
    private var changesSeq: Long? = null
    private var previewPath: String? = null
    private val previewOffsets = mutableListOf(1L)
    private val fileAction = MutableStateFlow<NativeToolFileAction?>(null)
    val fileActions = fileAction.asStateFlow()
    private var savingCopy: NativeToolFileAction? = null
    private var exportingCopy: Job? = null

    init { inspectRuntime() }
    /** The existing reader validates the committed event and attachment ownership. */
    suspend fun loadMessageAttachment(sessionId: String, seq: Long, attachmentId: String): ByteArray =
        repository.attachmentBytes(NativeToolSource.Attachment(sessionId, seq, attachmentId))

    fun startObserving(initialSessionId: String? = null) {
        if (initialSessionId != null && initialSessionId != state.value.selectedSessionId) selectSession(initialSessionId)
        if (observation?.isActive == true) return
        observation = viewModelScope.launch {
            var ready = false
            while (isActive) {
                inspectRuntime()
                val current = state.value.runtime.ready
                if (current && !ready) refreshSessions()
                if (!current && ready) { reading?.cancel(); watching?.cancel(); mutable.update { it.copy(loading = false) } }
                ready = current; delay(750)
            }
        }
    }
    fun stopObserving() { observation?.cancel(); observation = null; catalog?.cancel(); catalog = null; watching?.cancel(); watching = null; reading?.cancel(); reading = null; cancelExport() }
    fun startRuntime() {
        try { NativeRuntimeHost.start(getApplication()); inspectRuntime() } catch (_: Exception) { report("runtime_start_failed") }
    }
    fun report(code: String) { mutable.update { it.copy(error = code) } }
    fun dismissError() { mutable.update { it.copy(error = null) } }
    fun selectSession(id: String) {
        cancelExport()
        generation++; reading?.cancel(); watching?.cancel(); pendingTerminalId = null
        mutable.update { it.copy(selectedSessionId = id, directory = null, content = emptyList(), contentNextBefore = null,
            changes = null, diff = null, preview = null, fileSource = null, fileFeedback = null, terminals = emptyList(), terminalKey = null, terminalCreateUnconfirmed = false, loading = false, error = null) }
        when (state.value.page) {
            NativeToolsPage.FILES -> openDirectory("")
            NativeToolsPage.CONTENT -> loadContent()
            NativeToolsPage.TERMINALS -> loadTerminals()
            NativeToolsPage.TERMINAL, NativeToolsPage.CHANGES, NativeToolsPage.PREVIEW -> mutable.update { it.copy(page = NativeToolsPage.HOME) }
            else -> Unit
        }
    }
    fun open(page: NativeToolsPage) {
        if (page != NativeToolsPage.PREVIEW) cancelExport()
        generation++; reading?.cancel(); watching?.cancel()
        mutable.update { it.copy(page = page, error = null, loading = false,
            pendingBrowserNavigation = it.pendingBrowserNavigation.takeIf { page == NativeToolsPage.BROWSER }) }
        when (page) {
            NativeToolsPage.FILES -> openDirectory(state.value.directory?.path ?: "")
            NativeToolsPage.CONTENT -> loadContent()
            NativeToolsPage.TERMINALS -> loadTerminals()
            else -> Unit
        }
    }
    fun back(): Boolean {
        val current = state.value
        if (current.page == NativeToolsPage.HOME) return false
        if (current.page == NativeToolsPage.FILES && current.directory?.path?.isNotEmpty() == true) { openDirectory(NativeToolsPaths.parent(current.directory.path)); return true }
        if (current.page == NativeToolsPage.CHANGES && current.diff != null) { mutable.update { it.copy(diff = null, error = null) }; return true }
        open(when (current.page) {
            NativeToolsPage.PREVIEW -> previewReturn
            NativeToolsPage.CHANGES -> NativeToolsPage.CONTENT
            NativeToolsPage.TERMINAL -> NativeToolsPage.TERMINALS
            else -> NativeToolsPage.HOME
        }); return true
    }
    fun refresh() {
        when (state.value.page) {
            NativeToolsPage.HOME -> refreshSessions()
            NativeToolsPage.FILES -> openDirectory(state.value.directory?.path ?: "")
            NativeToolsPage.CONTENT -> loadContent()
            NativeToolsPage.CHANGES -> changesSeq?.let(::openChanges)
            NativeToolsPage.PREVIEW -> state.value.fileSource?.let(::openSource)
            NativeToolsPage.TERMINALS -> loadTerminals()
            NativeToolsPage.TERMINAL -> mutable.update { it.copy(terminalReconnect = it.terminalReconnect + 1, error = null) }
            NativeToolsPage.BROWSER -> Unit
        }
    }
    fun refreshSessions() {
        catalog?.cancel()
        catalog = viewModelScope.launch {
            try { val rows = repository.sessions(); mutable.update { it.copy(sessions = rows, selectedSessionId = it.selectedSessionId ?: rows.firstOrNull()?.id) } }
            catch (error: CancellationException) { throw error }
            catch (error: Exception) { if (state.value.page == NativeToolsPage.HOME) report(code(error)) }
        }
    }
    fun openDirectory(path: String) {
        val id = state.value.selectedSessionId ?: return
        watching?.cancel()
        launchRead { token ->
            val directory = repository.directory(id, path)
            if (token != generation || id != state.value.selectedSessionId) return@launchRead
            mutable.update { it.copy(page = NativeToolsPage.FILES, directory = directory, watchAvailable = null) }
            watching = viewModelScope.launch {
                try { repository.watch(id, directory.path).collect { frame ->
                    if (token != generation || state.value.page != NativeToolsPage.FILES) return@collect
                    if (frame.optString("kind") == "ready") mutable.update { it.copy(watchAvailable = true) }
                    else if (frame.optString("kind") == "change") {
                        val refreshed = repository.directory(id, directory.path)
                        if (token == generation) mutable.update { it.copy(directory = refreshed) }
                    }
                } } catch (error: CancellationException) { throw error }
                catch (_: Exception) { if (token == generation) mutable.update { it.copy(watchAvailable = false) } }
            }
        }
    }
    fun openFile(path: String, preserveReturn: Boolean = false) {
        val id = state.value.selectedSessionId ?: return
        if (!preserveReturn) previewReturn = state.value.page
        previewOffsets.clear(); previewOffsets += 1L
        previewPath = path; watching?.cancel()
        openSource(NativeToolSource.Workspace(id, path))
    }
    fun openAttachment(sessionId: String, seq: Long, attachmentId: String) {
        if (seq < 0 || attachmentId.isBlank()) { report("NATIVE_TOOLS_ATTACHMENT_SCOPE"); return }
        previewReturn = if (state.value.page == NativeToolsPage.PREVIEW) NativeToolsPage.HOME else state.value.page
        watching?.cancel(); mutable.update { it.copy(selectedSessionId = sessionId) }
        openSource(NativeToolSource.Attachment(sessionId, seq, attachmentId))
    }
    private fun openSource(source: NativeToolSource) {
        cancelExport()
        mutable.update { it.copy(page = NativeToolsPage.PREVIEW, preview = null, fileSource = source, fileFeedback = null) }
        launchRead { token ->
            val metadata = when (source) {
                is NativeToolSource.Workspace -> repository.stat(source.sessionId, source.path).let { Triple(source.path, it, NativeToolsPaths.kind(source.path)) }
                is NativeToolSource.Attachment -> repository.attachment(source).let { info -> Triple(info.name,
                    NativeToolStat(info.name, info.attachmentId, info.bytes), if (info.kind == "image") "image" else NativeToolsPaths.kind(info.name)) }
            }
            val (path, stat, kind) = metadata
            if (token == generation) mutable.update { it.copy(preview = NativeToolPreview(path, "loading", stat)) }
            suspend fun bytes(): ByteArray = when (source) {
                is NativeToolSource.Workspace -> repository.bytes(source.sessionId, source.path).also { value ->
                    if (!value.eof || value.offset != 0L || value.stat.path != stat.path || value.stat.version != stat.version || value.stat.bytes != stat.bytes || stat.bytes != null && stat.bytes != value.data.size.toLong()) throw NativeApiException("NATIVE_TOOLS_COPY_CHANGED")
                }.data
                is NativeToolSource.Attachment -> repository.attachmentBytes(source)
            }
            val preview = if (kind in setOf("pdf", "office")) try {
                val pdf = if (kind == "office") repository.pdf(source) else NativeToolPdf(bytes(), emptyList())
                val cached = withContext(Dispatchers.IO) { NativeToolsFileHost.cacheBytes(getApplication(), path.substringAfterLast('/').substringBeforeLast('.') + ".pdf", pdf.data) }
                NativeToolPreview(path, "pdf", pdf.sourceStat ?: stat, privatePdf = cached, missingFonts = pdf.missingFonts)
            } catch (error: CancellationException) { throw error }
            catch (_: Exception) { NativeToolPreview(path, "unsupported", stat) }
            else if (kind == "image") NativeToolPreview(path, kind, stat, data = bytes())
            else if (source is NativeToolSource.Workspace && kind == "text") try {
                val text = repository.text(source.sessionId, source.path); NativeToolPreview(path, "text", text.stat, text.text, offset = text.offset, lines = text.lines, eof = text.eof)
            } catch (error: NativeApiException) { if (error.code != "workspace-file/not-text") throw error; NativeToolPreview(path, "unsupported", stat) }
            else try {
                val text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes())).toString()
                if ('\u0000' in text) NativeToolPreview(path, "unsupported", stat)
                else NativeToolPreview(path, kind, stat, text = text)
            } catch (error: java.nio.charset.CharacterCodingException) { NativeToolPreview(path, "unsupported", stat) }
            if (token == generation) mutable.update { it.copy(preview = preview) }
        }
    }
    fun previewPage(next: Boolean) {
        val current = state.value.preview ?: return
        if (state.value.fileSource !is NativeToolSource.Workspace) return
        val id = state.value.selectedSessionId ?: return
        if (current.kind != "text") return
        val offset = if (next) current.offset + current.lines else previewOffsets.getOrNull(previewOffsets.lastIndex - 1) ?: return
        launchRead { token ->
            val value = repository.text(id, current.path, offset)
            if (value.stat.version != current.stat?.version) throw NativeApiException("native_file_changed")
            if (token == generation) {
                if (next) previewOffsets += value.offset else previewOffsets.removeAt(previewOffsets.lastIndex)
                mutable.update { it.copy(preview = current.copy(text = value.text, offset = value.offset, lines = value.lines, eof = value.eof)) }
            }
        }
    }
    fun loadContent(more: Boolean = false) {
        val id = state.value.selectedSessionId ?: return
        val before = if (more) state.value.contentNextBefore ?: return else null
        launchRead { token ->
            val page = repository.content(id, before)
            if (token == generation) mutable.update { it.copy(content = if (more) (it.content + page.entries).distinctBy(NativeToolContent::seq) else page.entries, contentNextBefore = page.nextBefore) }
        }
    }
    fun openChanges(seq: Long) {
        val id = state.value.selectedSessionId ?: return; changesSeq = seq
        mutable.update { it.copy(page = NativeToolsPage.CHANGES, changes = null, diff = null) }
        launchRead { token -> val value = repository.changes(id, seq); if (token == generation) mutable.update { it.copy(changes = value) } }
    }
    fun openDiff(index: Int) {
        val id = state.value.selectedSessionId ?: return
        val changes = state.value.changes ?: return
        launchRead { token -> val value = repository.diff(id, changes.seq, index); if (token == generation) mutable.update { it.copy(diff = value) } }
    }
    fun loadTerminals() {
        val id = state.value.selectedSessionId ?: return
        launchRead { token ->
            val rows = repository.terminals(id)
            if (token == generation) mutable.update { current ->
                val recovered = pendingTerminalId?.let { target -> rows.any { it.id == target } } == true
                if (recovered) pendingTerminalId = null
                current.copy(terminals = rows, terminalCreateUnconfirmed = if (recovered) false else current.terminalCreateUnconfirmed)
            }
        }
    }
    fun createTerminal() {
        val current = state.value; val session = current.selectedSession ?: return
        if (session.readOnly || current.terminalCreating) return
        val id = pendingTerminalId ?: UUID.randomUUID().toString().also { pendingTerminalId = it }
        mutable.update { it.copy(terminalCreating = true, error = null) }
        viewModelScope.launch {
            try {
                repository.createTerminal(session.id, id)
                if (state.value.selectedSessionId == session.id) { pendingTerminalId = null; mutable.update { it.copy(terminalCreateUnconfirmed = false) }; loadTerminals() }
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) { if (state.value.selectedSessionId == session.id) mutable.update { it.copy(terminalCreateUnconfirmed = true, error = code(error)) } }
            finally { mutable.update { it.copy(terminalCreating = false) } }
        }
    }
    fun openTerminal(id: String) {
        val session = state.value.selectedSession ?: return
        if (session.readOnly || state.value.terminals.none { it.id == id }) return
        mutable.update { it.copy(page = NativeToolsPage.TERMINAL, terminalKey = NativeTerminalKey(session.id, id), error = null) }
    }
    fun renameTerminal(id: String, title: String) = changeTerminal(id) { repository.renameTerminal(it, title.trim()) }
    fun closeTerminal(id: String) = changeTerminal(id) { repository.closeTerminal(it) }
    private fun changeTerminal(id: String, action: suspend (NativeTerminalKey) -> Unit) {
        val session = state.value.selectedSession ?: return
        if (session.readOnly) return
        viewModelScope.launch {
            try { action(NativeTerminalKey(session.id, id)); loadTerminals() }
            catch (error: CancellationException) { throw error }
            catch (error: Exception) { report(code(error)) }
        }
    }
    suspend fun terminalFrames(key: NativeTerminalKey, render: (NativeTerminalRenderEvent) -> Unit) = terminal.follow(key, render)
    fun terminalInput(epoch: Long, bytes: ByteArray) = terminal.input(epoch, bytes)
    fun terminalResize(epoch: Long, cols: Int, rows: Int) = terminal.resize(epoch, cols, rows)
    fun terminalInteractionError(code: String) = terminal.interactionError(code)
    fun exportFile(saving: Boolean) {
        val source = state.value.fileSource ?: return
        if (state.value.exporting || fileAction.value != null || savingCopy != null) return
        mutable.update { it.copy(exporting = true, fileFeedback = null, error = null) }
        exportingCopy = viewModelScope.launch {
            try {
                val plan = repository.describeCopy(source)
                val file = withContext(Dispatchers.IO) {
                    NativeToolsFileHost.begin(getApplication(), plan.name, plan.bytes ?: -1).use { transfer ->
                        repository.copy(plan) { transfer.append(it) }; transfer.commit(plan.sha256)
                    }
                }
                fileAction.value = NativeToolFileAction(UUID.randomUUID().toString(), file, plan.mediaType ?: NativeToolsFileHost.mediaType(plan.name), saving)
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) { report(fileCode(error)) }
            finally { mutable.update { it.copy(exporting = false) } }
        }
    }
    private fun cancelExport() { exportingCopy?.cancel(); exportingCopy = null; fileAction.value = null }
    fun consumeFileAction(id: String, saving: Boolean) {
        val value = fileAction.value?.takeIf { it.id == id } ?: return
        if (saving) savingCopy = value
        fileAction.value = null
    }
    fun cancelSaveCopy() { savingCopy = null }
    fun saveCopy(destination: Uri?) {
        val copy = savingCopy ?: return; savingCopy = null
        if (destination == null) return
        mutable.update { it.copy(exporting = true, fileFeedback = null) }
        viewModelScope.launch {
            try { withContext(Dispatchers.IO) { NativeToolsFileHost.saveCopy(getApplication(), copy.file, destination) }; mutable.update { it.copy(fileFeedback = "saved") } }
            catch (error: CancellationException) { throw error }
            catch (error: Exception) { report(fileCode(error)) }
            finally { mutable.update { it.copy(exporting = false) } }
        }
    }
    private fun fileCode(error: Exception): String {
        if (error is NativeApiException) return error.code
        return error.message?.takeIf { it in setOf("NATIVE_TOOLS_EXPORT_TOO_LARGE", "NATIVE_TOOLS_COPY_CHANGED", "NATIVE_TOOLS_CACHE_FAILED", "NATIVE_TOOLS_SAVE_FAILED", "NATIVE_TOOLS_SAVE_INCOMPLETE") } ?: "native_file_copy_failed"
    }
    /** The surface host is the sole navigation executor and retains the existing URL and DNS checks. */
    fun openBrowser(url: String) {
        cancelExport()
        generation++; reading?.cancel(); watching?.cancel()
        val navigation = NativeBrowserNavigation(UUID.randomUUID().toString(), "native-" + UUID.randomUUID(), url)
        mutable.update { it.withBrowserNavigation(navigation) }
    }
    fun takeBrowserNavigation(requestId: String): NativeBrowserNavigation? {
        while (true) {
            val current = mutable.value
            val next = current.consumeBrowserNavigation(requestId)
            if (next === current) return null
            if (mutable.compareAndSet(current, next)) return current.pendingBrowserNavigation
        }
    }
    fun newBrowserTab() { val id = "native-" + UUID.randomUUID(); mutable.update { it.copy(selectedBrowserId = id, browser = NativeBrowserState(id), pendingBrowserNavigation = null, browserSourceUrl = null, error = null) } }
    fun selectBrowser(id: String) { mutable.update { it.copy(selectedBrowserId = id, browser = null, pendingBrowserNavigation = null, browserSourceUrl = null, error = null) } }
    fun browserState(value: JSONObject) {
        try {
            val tabs = value.optJSONArray("tabs")?.let { array -> (0 until array.length()).map { array.getJSONObject(it) }.map { row ->
                NativeBrowserTab(row.getString("sessionId"), row.optString("title"), row.optString("url")) } }
            mutable.update { current ->
                val selected = current.selectedBrowserId ?: value.optString("activeSessionId").takeIf { it.isNotEmpty() && it != "null" } ?: tabs?.firstOrNull()?.id
                val frame = value.optString("sessionId").takeIf { it.isNotEmpty() && it == selected }?.let { id ->
                    NativeBrowserState(id, value.optString("title"), value.optString("url"), value.optBoolean("loading"), value.optBoolean("canGoBack"), value.optBoolean("canGoForward"),
                        value.optBoolean("sessionOpen", true), value.has("error") && !value.isNull("error")) }
                current.copy(browserTabs = tabs ?: current.browserTabs, selectedBrowserId = selected.takeUnless { frame?.open == false }, browser = frame ?: current.browser)
            }
        } catch (_: Exception) { report("malformed_response") }
    }
    fun browserClosed(id: String) { mutable.update { it.copy(browserTabs = it.browserTabs.filterNot { tab -> tab.id == id }, selectedBrowserId = it.selectedBrowserId.takeUnless { selected -> selected == id }, browser = null,
        pendingBrowserNavigation = it.pendingBrowserNavigation.takeUnless { request -> request?.tabId == id },
        browserSourceUrl = if (it.selectedBrowserId == id) null else it.browserSourceUrl) } }
    private fun inspectRuntime() {
        val value = NativeRuntimeHost.inspect(getApplication())
        mutable.update { it.copy(runtime = NativeRuntimeState(value.ready, value.active, value.canStart, value.status, value.locale)) }
    }
    private fun launchRead(block: suspend (Long) -> Unit): Job {
        val token = ++generation; reading?.cancel(); mutable.update { it.copy(loading = true, error = null) }
        return viewModelScope.launch {
            try { block(token) }
            catch (error: CancellationException) { throw error }
            catch (error: Exception) { if (token == generation) report(code(error)) }
            finally { if (token == generation) mutable.update { it.copy(loading = false) } }
        }.also { reading = it }
    }
    private fun code(error: Exception) = (error as? NativeApiException)?.code ?: "network_error"
}
