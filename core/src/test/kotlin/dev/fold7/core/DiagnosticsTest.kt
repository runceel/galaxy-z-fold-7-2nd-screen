package dev.fold7.core

import org.junit.Assert.*
import org.junit.Test

class DiagnosticsTest {
    @Test fun unknownUntilSamplesAndACompleteWindow() {
        val diagnostics = Diagnostics(0, 1_000_000_000)
        assertNull(diagnostics.sample(0).bitsPerSecond)
        diagnostics.record(1, bytes = 1_000)
        val early = diagnostics.sample(999_999_999)
        assertNull(early.bitsPerSecond)
        val measured = diagnostics.sample(1_000_000_000)
        assertEquals(8_000.0, measured.bitsPerSecond!!, 0.000001)
        assertNull(measured.inputAuFps)
        assertNull(measured.outputFps)
    }

    @Test fun arithmeticUsesActualElapsedMonotonicWindowNotConfiguredDuration() {
        val diagnostics = Diagnostics(20, 1_000_000_000)
        diagnostics.record(20, bytes = 1_000, inputAus = 60, outputs = 55)
        diagnostics.record(500_000_020, bytes = 2_000, inputAus = 30, outputs = 25)
        val measured = diagnostics.sample(2_000_000_020)
        assertEquals(2_000_000_000, measured.elapsedNanos)
        assertEquals(12_000.0, measured.bitsPerSecond!!, 0.000001)
        assertEquals(45.0, measured.inputAuFps!!, 0.000001)
        assertEquals(40.0, measured.outputFps!!, 0.000001)
        val idle = diagnostics.sample(3_000_000_020)
        assertEquals(0.0, idle.bitsPerSecond!!, 0.0)
        assertEquals(0.0, idle.inputAuFps!!, 0.0)
        assertEquals(0.0, idle.outputFps!!, 0.0)
    }

    @Test fun largeByteCountsDoNotOverflowIntBitrateArithmetic() {
        val diagnostics = Diagnostics(0, 1)
        repeat(3) { diagnostics.record(0, bytes = Int.MAX_VALUE) }
        assertEquals(Int.MAX_VALUE.toDouble() * 3 * 8, diagnostics.sample(1_000_000_000).bitsPerSecond!!, 0.00001)
    }

    @Test fun invalidClockAndNegativeCountsAreRejected() {
        val diagnostics = Diagnostics(-100, 10)
        diagnostics.record(-99, outputs = 1)
        assertThrows(IllegalArgumentException::class.java) { diagnostics.sample(-100) }
        assertThrows(IllegalArgumentException::class.java) { diagnostics.record(0, bytes = -1) }
        assertThrows(IllegalArgumentException::class.java) { Diagnostics(0, 0) }
        assertNull(diagnostics.sample(-90).bitsPerSecond)
    }
}
