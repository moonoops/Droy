package io.droyagent.app.nativeui.media

/** Runtime holds and human-only picker gates have different lifetimes. */
internal class NativeMediaActivityTracker {
    private val work = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val pickers = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    fun update(id: String, phase: NativeMediaPhase?, opening: Boolean): Boolean {
        val active = phase in setOf(NativeMediaPhase.PICKING, NativeMediaPhase.UPLOADING, NativeMediaPhase.IMPORTING) || opening
        val workChanged = if (active) work.add(id) else work.remove(id)
        val pickerChanged = if (phase == NativeMediaPhase.PICKING) pickers.add(id) else pickers.remove(id)
        return workChanged || pickerChanged
    }
    fun remove(id: String): Boolean {
        val workChanged = work.remove(id)
        val pickerChanged = pickers.remove(id)
        return workChanged || pickerChanged
    }
    fun hasRuntimeWork(): Boolean = work.isNotEmpty()
    fun isPickerOpen(): Boolean = pickers.isNotEmpty()
}
