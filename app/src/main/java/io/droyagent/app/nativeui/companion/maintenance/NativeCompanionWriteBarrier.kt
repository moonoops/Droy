package io.droyagent.app.nativeui.companion.maintenance

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

internal data class NativeCompanionImageAccess(val frozen: Boolean = false, val generation: Long = 0)

/** Admission closes only after the live task gate has drained; waiting tasks keep their controls. */
internal object NativeCompanionWriteBarrier {
    private val imageAccessState = MutableStateFlow(NativeCompanionImageAccess())
    val imageAccess = imageAccessState.asStateFlow()
    var frozen: Boolean
        get() = imageAccessState.value.frozen
        set(value) { imageAccessState.update { it.copy(frozen = value) } }
    var imageGeneration: Long
        get() = imageAccessState.value.generation
        set(value) { imageAccessState.update { it.copy(generation = value) } }
    @Volatile var foreground: Boolean = false
}

internal suspend fun drainCompanionWriters(scope: kotlinx.coroutines.CoroutineScope) {
    while (true) {
        val children = scope.coroutineContext[kotlinx.coroutines.Job]?.children?.toList().orEmpty()
        if (children.isEmpty()) return
        children.forEach { it.join() }
    }
}
