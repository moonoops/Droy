package io.droyagent.app.nativeui.companion.data

import io.droyagent.app.nativeui.companion.maintenance.NativeCompanionWriteBarrier
import io.droyagent.app.nativeui.data.NativeApiException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal data class NativeCompanionStickerMarketState(
    val query: String = "", val packs: List<NativeCompanionMarketPack> = emptyList(),
    val loading: Boolean = false, val loaded: Boolean = false, val installingId: String? = null,
    val receipts: Map<String, NativeCompanionMarketReceipt> = emptyMap(),
    val lastReceipt: NativeCompanionMarketReceipt? = null, val error: String? = null,
)

/** Its scope is a child of the companion ViewModel, so existing deletion maintenance drains downloads too. */
internal class NativeCompanionStickerMarketController(parent: CoroutineScope, private val companionId: String,
    private val api: NativeCompanionStickerMarketApi = NativeCompanionStickerMarketClient(),
    private val onInstalled: () -> Unit,
) {
    private val scope = parent
    private val mutable = MutableStateFlow(NativeCompanionStickerMarketState())
    val state = mutable.asStateFlow()
    private var searchJob: Job? = null
    private var installJob: Job? = null
    private var searchRevision = 0
    private var failedPack: NativeCompanionMarketPack? = null

    fun query(value: String) { if (mutable.value.installingId == null) mutable.update { it.copy(query = value) } }

    fun search() {
        if (NativeCompanionWriteBarrier.frozen || mutable.value.installingId != null) return
        val query = mutable.value.query.trim()
        if (query.codePointCount(0, query.length) > 200) {
            mutable.update { it.copy(error = "COMPANION_STICKER_ONLINE_INVALID") }; return
        }
        searchJob?.cancel()
        val revision = ++searchRevision
        failedPack = null
        mutable.update { it.copy(loading = true, error = null) }
        searchJob = scope.launch {
            try {
                val rows = api.search(query)
                currentCoroutineContext().ensureActive()
                if (!NativeCompanionWriteBarrier.frozen) mutable.update { it.copy(packs = rows, loaded = true) }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                mutable.update { it.copy(error = code(error)) }
            } finally { if (revision == searchRevision) mutable.update { it.copy(loading = false) } }
        }
    }

    fun install(pack: NativeCompanionMarketPack) {
        if (NativeCompanionWriteBarrier.frozen || mutable.value.loading || mutable.value.installingId != null) return
        mutable.update { it.copy(installingId = pack.id, error = null, lastReceipt = null) }
        installJob = scope.launch {
            try {
                val receipt = api.install(companionId, pack.id)
                currentCoroutineContext().ensureActive()
                if (!NativeCompanionWriteBarrier.frozen) {
                    mutable.update { it.copy(receipts = it.receipts + (pack.id to receipt), lastReceipt = receipt) }
                    failedPack = null
                    onInstalled()
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                failedPack = pack.takeUnless { code(error) == "COMPANION_STICKER_ONLINE_NOT_FOUND" }
                mutable.update { it.copy(error = code(error)) }
            } finally { mutable.update { it.copy(installingId = null) } }
        }
    }

    fun retry() { failedPack?.let(::install) ?: search() }
    fun close() { searchJob?.cancel(); installJob?.cancel() }
    private fun code(error: Exception) = (error as? NativeApiException)?.code ?: "request_failed"
}
