package dev.fold7.core

/** Independent owner-thread model only. No socket, Android lifecycle or decoder integration. */
class SessionModel(
    private val limits: StreamLimits = StreamLimits(),
    private val inactivityNanos: Long = 5_000_000_000,
    private val windowNanos: Long = 1_000_000_000,
) {
    private class Active(
        val token: Long,
        var lastInput: Long,
        var lastEvent: Long,
        val parser: AudAccessUnits,
        val diagnostics: Diagnostics,
        val cancel: () -> Unit,
    )

    private var active: Active? = null
    private var generation = 0L

    init {
        require(inactivityNanos > 0 && windowNanos > 0)
    }

    fun open(now: Long, cancel: () -> Unit): Long? {
        if (active != null) return null
        generation = Math.incrementExact(generation)
        active = Active(generation, now, now, AudAccessUnits(limits), Diagnostics(now, windowNanos), cancel)
        return generation
    }

    fun input(token: Long, bytes: ByteArray, now: Long): Boolean {
        val state = live(token, now) ?: return false
        try {
            val before = state.parser.queuedCount
            state.parser.feed(bytes)
            val added = state.parser.queuedCount - before
            state.diagnostics.record(now, bytes = bytes.size, inputAus = added)
            if (bytes.isNotEmpty()) state.lastInput = now
        } catch (failure: Exception) {
            closePreserving(token, failure)
            throw failure
        }
        return true
    }

    fun poll(token: Long, now: Long): ByteArray? = live(token, now)?.parser?.poll()

    fun output(token: Long, now: Long): Boolean {
        val state = live(token, now) ?: return false
        state.diagnostics.record(now, outputs = 1)
        return true
    }

    fun sample(token: Long, now: Long): Rates? = live(token, now)?.diagnostics?.sample(now)

    fun tick(token: Long, now: Long): Boolean = live(token, now) != null

    /** EOF is terminal: validates the tail, then cancels/discards rather than carrying it forward. */
    fun eof(token: Long, now: Long): Boolean {
        val state = live(token, now) ?: return false
        try {
            state.parser.finish()
        } catch (failure: Exception) {
            closePreserving(token, failure)
            throw failure
        }
        close(token)
        return true
    }

    /** Detach before cancellation, so duplicate/stale closes (including reentrant ones) do nothing. */
    fun close(token: Long): Boolean {
        val state = active?.takeIf { it.token == token } ?: return false
        active = null
        state.parser.discard()
        state.cancel()
        return true
    }

    private fun live(token: Long, now: Long): Active? {
        val state = active?.takeIf { it.token == token } ?: return null
        require(now >= state.lastEvent && now - state.lastInput >= 0) { "Invalid monotonic time" }
        state.lastEvent = now
        if (now - state.lastInput >= inactivityNanos) {
            close(token)
            return null
        }
        return state
    }

    private fun closePreserving(token: Long, failure: Exception) {
        try {
            close(token)
        } catch (cleanup: Exception) {
            failure.addSuppressed(cleanup)
        }
    }
}
