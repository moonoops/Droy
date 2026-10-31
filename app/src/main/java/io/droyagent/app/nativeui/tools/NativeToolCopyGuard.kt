package io.droyagent.app.nativeui.tools

import io.droyagent.app.nativeui.data.NativeApiException

/** A private copy is published only after contiguous windows and a final stat agree. */
internal class NativeToolCopyGuard(private val plan: NativeToolCopyPlan) {
    var offset: Long = 0; private set
    private var ended = false
    init {
        if (plan.canonicalPath == null || plan.bytes != null && plan.bytes < 0) changed()
        if (plan.bytes != null && plan.bytes > LIMIT) tooLarge()
    }
    fun accept(value: NativeToolBytes) {
        if (ended || !same(value.stat) || value.offset != offset || value.data.size > WINDOW || !value.eof && value.data.isEmpty()) changed()
        val next = offset + value.data.size
        if (next > LIMIT) tooLarge()
        if (plan.bytes != null && (next > plan.bytes || value.eof && next != plan.bytes)) changed()
        offset = next; ended = value.eof
    }
    fun finish(stat: NativeToolStat) { if (!ended || !same(stat) || plan.bytes != null && offset != plan.bytes) changed() }
    private fun same(stat: NativeToolStat) = stat.path == plan.canonicalPath && stat.version == plan.version && stat.bytes == plan.bytes
    private fun changed(): Nothing = throw NativeApiException("NATIVE_TOOLS_COPY_CHANGED")
    private fun tooLarge(): Nothing = throw NativeApiException("NATIVE_TOOLS_EXPORT_TOO_LARGE")
    companion object { const val WINDOW = 65536; const val LIMIT = 100L * 1024 * 1024 }
}
