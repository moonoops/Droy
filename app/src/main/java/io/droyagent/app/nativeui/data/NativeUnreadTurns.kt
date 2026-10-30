package io.droyagent.app.nativeui.data

/** Completed turns, not streamed tokens, create a workbench unread result. */
internal class NativeUnreadTurns(saved: Map<String, Int> = emptyMap()) {
    private val read = saved.toMutableMap()
    private val completed = mutableMapOf<String, Int>()
    private val completionSeqs = mutableMapOf<String, Pair<Int, Long>>()
    private val historicalWithoutBoundary = mutableSetOf<String>()

    /** Only the initial cold-safe catalog may establish an existing history baseline. */
    fun seedHistory(session: String, turn: Int?) {
        if (turn == null) {
            if (session !in read) historicalWithoutBoundary += session
            return
        }
        if (turn < 0) return
        if (session !in read) read[session] = turn
        historicalWithoutBoundary -= session
        observe(session, turn)
    }

    fun trackNew(session: String) {
        historicalWithoutBoundary -= session
        read.putIfAbsent(session, 0)
    }

    fun observe(session: String, turn: Int, through: Long? = null): Boolean {
        if (turn < 0) return false
        if (session !in read) read[session] = if (historicalWithoutBoundary.remove(session)) turn else 0
        if (through != null) {
            val gate = completionSeqs[session]
            if (gate == null || turn > gate.first) completionSeqs[session] = turn to through
            else if (turn == gate.first) completionSeqs[session] = turn to minOf(gate.second, through)
        }
        val previous = completed[session] ?: 0
        completed[session] = maxOf(previous, turn)
        return turn > previous
    }

    internal fun forget(session: String) {
        read.remove(session); completed.remove(session); completionSeqs.remove(session); historicalWithoutBoundary.remove(session)
    }
    fun completionSeq(session: String): Long? = completionSeqs[session]?.takeIf { it.first == completed[session] }?.second
    fun markRead(session: String) { completed[session]?.let { read[session] = maxOf(read[session] ?: 0, it) } }
    fun unread(): Set<String> = completed.filter { (id, turn) -> turn > (read[id] ?: turn) }.keys
    fun snapshot(): Map<String, Int> = read.toMap()
}
