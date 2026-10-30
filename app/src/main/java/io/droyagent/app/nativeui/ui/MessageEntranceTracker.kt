package io.droyagent.app.nativeui.ui

import io.droyagent.app.nativeui.data.NativeMessage
import io.droyagent.app.nativeui.companion.data.NativeCompanionMessage

internal fun NativeMessage.itemIdentity(): String = requestId?.let { "request:$it" } ?: id
internal fun NativeCompanionMessage.itemIdentity(): String = clientId ?: id

/** Screen-local identities: snapshots establish a baseline; only a new local send enters. */
internal class MessageEntranceTracker {
    private var initialized = false
    private val seen = mutableSetOf<String>()
    private val entrances = mutableSetOf<String>()

    fun update(identities: List<String>, pending: Set<String>, live: Boolean): Set<String> {
        if (!live) entrances.clear()
        if (initialized && live) entrances.addAll(pending.filter { it !in seen && it in identities })
        seen.addAll(identities)
        entrances.retainAll(identities.toSet())
        initialized = true
        return entrances.toSet()
    }
}
