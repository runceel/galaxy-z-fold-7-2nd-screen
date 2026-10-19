package dev.fold7.core

import java.io.ByteArrayOutputStream
import java.util.ArrayDeque

class StreamRejected(message: String) : IllegalArgumentException(message)

/** NAL bytes exclude prefixes; AU bytes include canonical prefixes. NAL count includes AUD. */
data class StreamLimits(
    val maxNalBytes: Int = 256 * 1024,
    val maxAuBytes: Int = 1024 * 1024,
    val maxQueuedAus: Int = 2,
    val maxNalsPerAu: Int = 128,
) {
    init {
        require(maxNalBytes >= 2)
        require(maxAuBytes >= 6)
        require(maxQueuedAus > 0)
        require(maxNalsPerAu > 0)
    }
}

/**
 * Strict, bounded Annex-B framing experiment, not an H.264 syntax validator.
 * Accepts only 3/4-byte start codes; longer zero runs and leading garbage are rejected.
 * Callback bytes exclude start codes and trailing_zero_8bits. Owner-thread use only.
 */
class AnnexBSplitter(private val maxNalBytes: Int, private val onNal: (ByteArray) -> Unit) {
    private val buffer: ByteArray
    private var size = 0
    private var zeros = 0
    private var started = false
    private var ended = false

    init {
        require(maxNalBytes >= 2)
        buffer = ByteArray(maxNalBytes)
    }

    fun feed(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size - offset) {
        require(offset >= 0 && length >= 0 && offset <= bytes.size - length)
        check(!ended) { "Stream already ended; create a new splitter." }
        guarded {
            for (index in offset until offset + length) {
                val value = bytes[index].toInt() and 255
                if (value == 0) {
                    zeros++
                    if (zeros > 3) reject("Unsupported zero run")
                } else if (value == 1 && zeros >= 2) {
                    if (started) emit()
                    started = true
                    zeros = 0
                } else {
                    if (!started) reject("Missing Annex-B start code")
                    repeat(zeros) { append(0) }
                    zeros = 0
                    append(value)
                }
            }
        }
    }

    fun finish() {
        check(!ended) { "Stream already ended; create a new splitter." }
        guarded {
            if (!started) reject("Empty or truncated start code")
            emit()
            ended = true
            zeros = 0
        }
    }

    fun discard() {
        ended = true
        buffer.fill(0)
        size = 0
        zeros = 0
    }

    private fun append(value: Int) {
        if (size == maxNalBytes) reject("NAL byte limit exceeded")
        buffer[size++] = value.toByte()
    }

    private fun emit() {
        if (size < 2) reject("Empty or header-only NAL")
        onNal(buffer.copyOf(size))
        size = 0
    }

    private inline fun guarded(action: () -> Unit) {
        try {
            action()
        } catch (failure: Exception) {
            discard()
            throw failure
        }
    }
}

/**
 * Provisional contract: AUD first in every AU; types 1/5/6/7/8/9 only, progressive
 * single-picture AUs supplied by the encoder. First AU must contain SPS, PPS, IDR.
 * SPS/PPS/SEI precede slices, and slices in an AU have the same NAL type.
 * Does NOT parse SPS/PPS/slice syntax, verify picture identity, or detect arbitrary
 * truncated slice payloads. EOF checks framing/required structure only.
 * Output uses canonical 4-byte prefixes. All limits count these prefixes.
 */
class AudAccessUnits(private val limits: StreamLimits = StreamLimits()) {
    private val queue = ArrayDeque<ByteArray>()
    private val current = ByteArrayOutputStream()
    private var hasAud = false
    private var sliceType = 0
    private var nalCount = 0
    private var hasSps = false
    private var hasPps = false
    private var first = true
    private var ended = false
    private val splitter = AnnexBSplitter(limits.maxNalBytes, ::accept)

    val queuedCount: Int get() = queue.size

    fun feed(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size - offset) = guarded {
        splitter.feed(bytes, offset, length)
    }

    fun finish() = guarded {
        splitter.finish()
        enqueue()
        ended = true
    }

    fun poll(): ByteArray? = queue.pollFirst()

    fun discard() {
        splitter.discard()
        queue.clear()
        current.reset()
        ended = true
        hasAud = false
        sliceType = 0
        nalCount = 0
        hasSps = false
        hasPps = false
    }

    private fun accept(nal: ByteArray) {
        val header = nal[0].toInt() and 255
        val type = header and 31
        if (header and 128 != 0) reject("forbidden_zero_bit")
        if (type !in setOf(1, 5, 6, 7, 8, 9)) reject("Unsupported NAL type $type")
        if ((type in setOf(6, 9) && header and 96 != 0) ||
            (type in setOf(5, 7, 8) && header and 96 == 0)
        ) reject("Invalid nal_ref_idc")
        if (type == 9) {
            if (nal.size != 2 || nal[1].toInt() and 31 != 16) reject("Malformed AUD")
            if (hasAud) enqueue()
            hasAud = true
            sliceType = 0
            nalCount = 0
            hasSps = false
            hasPps = false
        } else {
            if (!hasAud) reject("AUD required before every AU")
            if (type in 6..8 && sliceType != 0) reject("Metadata after slices")
            when (type) {
                7 -> hasSps = true
                8 -> {
                    if (first && !hasSps) reject("SPS required before PPS")
                    hasPps = true
                }
                1, 5 -> {
                    if (sliceType != 0 && sliceType != type) reject("Mixed slice types")
                    if (first && (type != 5 || !hasSps || !hasPps)) {
                        reject("First AU requires SPS, PPS and IDR")
                    }
                    sliceType = type
                }
            }
        }
        if (nalCount == limits.maxNalsPerAu) reject("AU NAL count limit exceeded")
        if (nal.size.toLong() + 4 > limits.maxAuBytes.toLong() - current.size()) {
            reject("AU byte limit exceeded")
        }
        current.write(byteArrayOf(0, 0, 0, 1))
        current.write(nal)
        nalCount++
    }

    private fun enqueue() {
        if (!hasAud || sliceType == 0) reject("AU has no slices")
        if (queue.size == limits.maxQueuedAus) reject("AU queue saturated")
        queue.addLast(current.toByteArray())
        current.reset()
        first = false
    }

    private inline fun guarded(action: () -> Unit) {
        check(!ended) { "Stream already ended; create a new assembler." }
        try {
            action()
        } catch (failure: Exception) {
            discard()
            throw failure
        }
    }
}

private fun reject(message: String): Nothing = throw StreamRejected(message)
