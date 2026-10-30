package io.droyagent.app.nativeui.companion.creation

internal class NativeCreationActivityTracker {
    private val pickers = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val work = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    fun update(id: String, value: NativeCreationState): Boolean {
        val pickerChanged = if (value.image?.phase == NativeCreationImagePhase.PICKING) pickers.add(id) else pickers.remove(id)
        val workChanged = if (value.busy || value.imageActive) work.add(id) else work.remove(id)
        return pickerChanged || workChanged
    }
    fun remove(id: String): Boolean { val first = pickers.remove(id); val second = work.remove(id); return first || second }
    fun isPickerOpen() = pickers.isNotEmpty()
    fun hasRuntimeWork() = work.isNotEmpty()
}
