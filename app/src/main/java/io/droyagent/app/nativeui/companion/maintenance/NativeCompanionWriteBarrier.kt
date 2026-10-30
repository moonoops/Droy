package io.droyagent.app.nativeui.companion.maintenance

/** Admission closes only after the live task gate has drained; waiting tasks keep their controls. */
internal object NativeCompanionWriteBarrier {
    @Volatile var frozen: Boolean = false
    @Volatile var imageGeneration: Long = 0
    @Volatile var foreground: Boolean = false
}

internal suspend fun drainCompanionWriters(scope: kotlinx.coroutines.CoroutineScope) {
    while (true) {
        val children = scope.coroutineContext[kotlinx.coroutines.Job]?.children?.toList().orEmpty()
        if (children.isEmpty()) return
        children.forEach { it.join() }
    }
}
