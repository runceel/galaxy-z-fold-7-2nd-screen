package dev.fold7.core

import org.junit.Assert.*
import org.junit.Test
import java.util.Random

class AnnexBTest {
    @Test fun everySingleSplitAndEveryPairOfSplitsPreserveAccessUnits() {
        val stream = TestStreams.annex(TestStreams.nals, mixed = true)
        val expected = listOf(TestStreams.annex(TestStreams.first), TestStreams.annex(TestStreams.second))
        for (first in 0..stream.size) {
            for (second in first..stream.size) {
                assertAus(expected, TestStreams.collect(stream, listOf(first, second - first, stream.size - second)))
            }
        }
    }

    @Test fun bytewiseAndRandomChunksMatchCoalescedStream() {
        val stream = TestStreams.annex(TestStreams.nals, mixed = true)
        val expected = TestStreams.collect(stream, listOf(stream.size))
        assertAus(expected, TestStreams.collect(stream, List(stream.size) { 1 }))
        val random = Random(42)
        repeat(30) {
            var remaining = stream.size
            val chunks = mutableListOf<Int>()
            while (remaining > 0) {
                val count = minOf(remaining, random.nextInt(9) + 1)
                chunks.add(count)
                remaining -= count
            }
            assertAus(expected, TestStreams.collect(stream, chunks))
        }
    }

    @Test fun splitterPreservesEmulationPreventionAcrossAllSplits() {
        val nal = byteArrayOf(0x65, 0, 0, 3, 1, 0x80.toByte())
        val stream = TestStreams.annex(listOf(nal), mixed = true)
        for (split in 0..stream.size) {
            val result = mutableListOf<ByteArray>()
            val splitter = AnnexBSplitter(nal.size, result::add)
            splitter.feed(stream, 0, split)
            splitter.feed(stream, split)
            splitter.finish()
            assertArrayEquals(nal, result.single())
        }
    }

    @Test fun splitterEmitsLastNalOnlyAtEofAndIgnoresTrailingZeros() {
        val output = mutableListOf<ByteArray>()
        val splitter = AnnexBSplitter(8, output::add)
        splitter.feed(TestStreams.annex(listOf(TestStreams.idr)) + byteArrayOf(0, 0))
        assertTrue(output.isEmpty())
        splitter.finish()
        assertArrayEquals(TestStreams.idr, output.single())
        assertThrows(IllegalStateException::class.java) { splitter.feed(byteArrayOf()) }
        assertThrows(IllegalStateException::class.java) { splitter.finish() }
    }

    @Test fun noAudIsExplicitlyRejected() {
        rejects(TestStreams.annex(listOf(TestStreams.sps, TestStreams.pps, TestStreams.idr)), "AUD")
    }

    @Test fun nonAnnexBLeadingGarbageAndTruncatedPrefixesAreRejected() {
        for (bytes in listOf(byteArrayOf(), byteArrayOf(0), byteArrayOf(0, 0), byteArrayOf(0, 0, 0), byteArrayOf(1, 2))) {
            rejects(bytes)
        }
        rejects(byteArrayOf(0x44) + TestStreams.annex(TestStreams.first))
    }

    @Test fun emptyHeaderOnlyAndDanglingStartCodeAreRejected() {
        val prefix = byteArrayOf(0, 0, 1)
        for (suffix in listOf(prefix, prefix + 0x65.toByte(), prefix + prefix)) {
            rejects(TestStreams.annex(TestStreams.first) + suffix)
        }
    }

    @Test fun longZeroRunsAreOutsideTheProvisionalContract() {
        rejects(byteArrayOf(0, 0, 0, 0, 1, 9, 0x10), "zero run")
        rejects(TestStreams.annex(TestStreams.first) + byteArrayOf(0, 0, 0, 0), "zero run")
    }

    @Test fun unsupportedTypesAndForbiddenBitAreRejected() {
        for (type in listOf(0, 2, 3, 4, 10, 11, 12, 13, 14, 15, 19, 20, 31)) {
            rejects(TestStreams.annex(listOf(TestStreams.aud, byteArrayOf(type.toByte(), 0x80.toByte()))))
        }
        rejects(TestStreams.annex(listOf(byteArrayOf(0x89.toByte(), 0x10))), "forbidden")
    }

    @Test fun invalidReferenceFlagsAndAudStructureAreRejected() {
        for (nal in listOf(byteArrayOf(0x69, 0x10), byteArrayOf(9, 0), byteArrayOf(9, 0x10, 0x80.toByte()), byteArrayOf(0x66, 0x80.toByte()))) {
            rejects(TestStreams.annex(listOf(nal)))
        }
        rejects(TestStreams.annex(listOf(TestStreams.aud, byteArrayOf(7, 0x80.toByte()))))
    }

    @Test fun initialConfigurationAndRecoveryPointAreMandatory() {
        for (nals in listOf(
            listOf(TestStreams.aud, TestStreams.slice),
            listOf(TestStreams.aud, TestStreams.idr),
            listOf(TestStreams.aud, TestStreams.pps, TestStreams.sps, TestStreams.idr),
            listOf(TestStreams.aud, TestStreams.sps, TestStreams.idr),
            listOf(TestStreams.aud, TestStreams.sps, TestStreams.pps, TestStreams.slice),
        )) rejects(TestStreams.annex(nals))
    }

    @Test fun emptyAuAndMetadataAfterSlicesAreRejected() {
        rejects(TestStreams.annex(listOf(TestStreams.aud)), "slices")
        rejects(TestStreams.annex(TestStreams.first + TestStreams.aud + TestStreams.aud), "slices")
        rejects(TestStreams.annex(TestStreams.first + listOf(TestStreams.sps)), "Metadata")
        rejects(TestStreams.annex(TestStreams.first + listOf(TestStreams.slice)), "Mixed")
    }

    @Test fun exactNalByteLimitPassesAndOneMoreFailsBeforeEof() {
        val nal = byteArrayOf(0x65, 0x22, 0x33, 0x80.toByte())
        val output = mutableListOf<ByteArray>()
        val splitter = AnnexBSplitter(4, output::add)
        splitter.feed(TestStreams.annex(listOf(nal)))
        splitter.finish()
        assertArrayEquals(nal, output.single())
        val tooLarge = AnnexBSplitter(4) {}
        assertThrows(StreamRejected::class.java) {
            tooLarge.feed(TestStreams.annex(listOf(nal + 0x55.toByte())))
        }
        assertThrows(IllegalStateException::class.java) { tooLarge.finish() }
    }

    @Test fun exactAuByteLimitIncludesCanonicalPrefixes() {
        val stream = TestStreams.annex(TestStreams.first, mixed = true)
        val bytes = TestStreams.annex(TestStreams.first).size
        val exact = StreamLimits(maxNalBytes = 10, maxAuBytes = bytes, maxQueuedAus = 1)
        assertEquals(1, TestStreams.collect(stream, listOf(stream.size), exact).size)
        rejects(stream, "AU byte limit", exact.copy(maxAuBytes = bytes - 1))
    }

    @Test fun exactNalCountIncludesAudMetadataAndAllSlices() {
        val stream = TestStreams.annex(TestStreams.first)
        val exact = StreamLimits(maxNalsPerAu = TestStreams.first.size)
        assertEquals(1, TestStreams.collect(stream, listOf(stream.size), exact).size)
        rejects(stream, "NAL count", exact.copy(maxNalsPerAu = TestStreams.first.size - 1))
        val secondAu = TestStreams.annex(TestStreams.first + TestStreams.second)
        assertEquals(2, TestStreams.collect(secondAu, listOf(secondAu.size), exact).size)
    }

    @Test fun queueSaturationFailsClosedAndDiscardsAllPendingData() {
        val assembler = AudAccessUnits(StreamLimits(maxQueuedAus = 1))
        assembler.feed(TestStreams.annex(TestStreams.nals))
        assertEquals(1, assembler.queuedCount)
        assertThrows(StreamRejected::class.java) { assembler.finish() }
        assertEquals(0, assembler.queuedCount)
        assertNull(assembler.poll())
        assertThrows(IllegalStateException::class.java) { assembler.feed(byteArrayOf()) }
    }

    @Test fun drainingQueueAllowsLongBoundedStream() {
        val assembler = AudAccessUnits(StreamLimits(maxQueuedAus = 1))
        var count = 0
        for (nal in TestStreams.first + List(100) { TestStreams.second }.flatten()) {
            assembler.feed(TestStreams.annex(listOf(nal)))
            while (assembler.poll() != null) count++
        }
        assembler.finish()
        while (assembler.poll() != null) count++
        assertEquals(101, count)
    }

    @Test fun invalidLimitsAndOffsetsFailImmediately() {
        assertThrows(IllegalArgumentException::class.java) { StreamLimits(maxNalBytes = 1) }
        assertThrows(IllegalArgumentException::class.java) { StreamLimits(maxAuBytes = 5) }
        assertThrows(IllegalArgumentException::class.java) { StreamLimits(maxQueuedAus = 0) }
        assertThrows(IllegalArgumentException::class.java) { StreamLimits(maxNalsPerAu = 0) }
        val splitter = AnnexBSplitter(4) {}
        assertThrows(IllegalArgumentException::class.java) { splitter.feed(byteArrayOf(0), 1, Int.MAX_VALUE) }
        assertThrows(IllegalArgumentException::class.java) { splitter.feed(byteArrayOf(0), -1, 1) }
    }

    private fun rejects(bytes: ByteArray, message: String? = null, limits: StreamLimits = StreamLimits()) {
        val assembler = AudAccessUnits(limits)
        val failure = assertThrows(StreamRejected::class.java) {
            assembler.feed(bytes)
            assembler.finish()
        }
        if (message != null) assertTrue(failure.message.orEmpty(), failure.message.orEmpty().contains(message))
        assertNull(assembler.poll())
    }

    private fun assertAus(expected: List<ByteArray>, actual: List<ByteArray>) {
        assertEquals(expected.size, actual.size)
        expected.zip(actual).forEach { (a, b) -> assertArrayEquals(a, b) }
    }
}
