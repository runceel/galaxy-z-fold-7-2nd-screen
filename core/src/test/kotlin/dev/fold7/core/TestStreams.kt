package dev.fold7.core

internal object TestStreams {
    val aud = byteArrayOf(9, 0x10)
    val sps = byteArrayOf(0x67, 0x42, 0x80.toByte())
    val pps = byteArrayOf(0x68, 0xCE.toByte(), 0x80.toByte())
    val idr = byteArrayOf(0x65, 0x88.toByte(), 0x80.toByte())
    val slice = byteArrayOf(0x41, 0x88.toByte(), 0x80.toByte())
    val first = listOf(aud, sps, pps, byteArrayOf(6, 0x80.toByte()), idr, idr)
    val second = listOf(aud, slice, slice)
    val nals = first + second

    // Deliberately structural stubs, not decodable H.264 fixtures.
    fun annex(nals: List<ByteArray>, mixed: Boolean = false): ByteArray =
        nals.flatMapIndexed { index, nal ->
            (if (mixed && index % 2 == 0) listOf<Byte>(0, 0, 1) else listOf<Byte>(0, 0, 0, 1)) +
                nal.toList()
        }.toByteArray()

    fun collect(stream: ByteArray, chunks: List<Int>, limits: StreamLimits = StreamLimits()): List<ByteArray> {
        val assembler = AudAccessUnits(limits)
        var offset = 0
        for (size in chunks) {
            assembler.feed(stream, offset, size)
            offset += size
        }
        check(offset == stream.size)
        assembler.finish()
        return generateSequence { assembler.poll() }.toList()
    }
}
