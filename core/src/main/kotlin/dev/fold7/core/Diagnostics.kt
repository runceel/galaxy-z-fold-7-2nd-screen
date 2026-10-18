package dev.fold7.core

data class Rates(
    val elapsedNanos: Long,
    val bitsPerSecond: Double?,
    val inputAuFps: Double?,
    val outputFps: Double?,
)

/** Tumbling monotonic windows. Null until a metric's first event and a full window. */
class Diagnostics(startNanos: Long, private val windowNanos: Long) {
    private var start = startNanos
    private var last = startNanos
    private var bytes = 0L
    private var input = 0L
    private var output = 0L
    private var seenBytes = false
    private var seenInput = false
    private var seenOutput = false

    init {
        require(windowNanos > 0)
    }

    fun record(now: Long, bytes: Int = 0, inputAus: Int = 0, outputs: Int = 0) {
        require(bytes >= 0 && inputAus >= 0 && outputs >= 0)
        advance(now)
        this.bytes = Math.addExact(this.bytes, bytes.toLong())
        input = Math.addExact(input, inputAus.toLong())
        output = Math.addExact(output, outputs.toLong())
        seenBytes = seenBytes || bytes > 0
        seenInput = seenInput || inputAus > 0
        seenOutput = seenOutput || outputs > 0
    }

    /** A completed window consumes counters; callers choose when to publish it. */
    fun sample(now: Long): Rates {
        advance(now)
        val elapsed = now - start
        if (elapsed < windowNanos) return Rates(elapsed, null, null, null)
        val seconds = elapsed / 1_000_000_000.0
        val rates = Rates(
            elapsed,
            if (seenBytes) bytes.toDouble() * 8 / seconds else null,
            if (seenInput) input / seconds else null,
            if (seenOutput) output / seconds else null,
        )
        bytes = 0
        input = 0
        output = 0
        start = now
        return rates
    }

    private fun advance(now: Long) {
        require(now >= last) { "Monotonic clock moved backwards" }
        require(now - start >= 0) { "Clock interval overflow" }
        last = now
    }
}
