package dev.fold7.core

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class SessionModelTest {
    @Test fun onlyOneActiveSessionAndCloseIsExactlyOnce() {
        val model = SessionModel()
        var cancels = 0
        val token = model.open(0) { cancels++ }!!
        assertNull(model.open(0) { fail("Rejected session must not own cancellation") })
        assertTrue(model.close(token))
        assertFalse(model.close(token))
        assertEquals(1, cancels)
    }

    @Test fun timeoutIsExactAndEmptyInputOrOutputDoesNotExtendDeadline() {
        val model = SessionModel(inactivityNanos = 10)
        var cancels = 0
        val token = model.open(0) { cancels++ }!!
        assertTrue(model.input(token, byteArrayOf(), 8))
        assertTrue(model.output(token, 9))
        assertFalse(model.tick(token, 10))
        assertFalse(model.tick(token, 11))
        assertEquals(1, cancels)
    }

    @Test fun inputBeforeDeadlineRefreshesItButInputAtDeadlineCannotRevive() {
        val model = SessionModel(inactivityNanos = 10)
        val token = model.open(0) {}!!
        assertTrue(model.input(token, byteArrayOf(0), 9))
        assertTrue(model.tick(token, 18))
        assertFalse(model.input(token, byteArrayOf(0), 19))
    }

    @Test fun staleEventsNeverTouchReplacementStateOrItsClock() {
        val model = SessionModel(inactivityNanos = 100, windowNanos = 10)
        val old = model.open(0) {}!!
        model.input(old, TestStreams.annex(TestStreams.nals), 1)
        model.close(old)
        var cancels = 0
        val fresh = model.open(20) { cancels++ }!!
        assertNotEquals(old, fresh)
        assertFalse(model.input(old, byteArrayOf(99), Long.MAX_VALUE))
        assertFalse(model.output(old, Long.MAX_VALUE))
        assertFalse(model.eof(old, Long.MAX_VALUE))
        assertFalse(model.tick(old, Long.MAX_VALUE))
        assertFalse(model.close(old))
        assertNull(model.poll(old, Long.MAX_VALUE))
        assertNull(model.sample(old, Long.MAX_VALUE))
        assertNull(model.poll(fresh, 20))
        assertNull(model.sample(fresh, 30)!!.bitsPerSecond)
        assertEquals(0, cancels)
        assertTrue(model.tick(fresh, 31))
    }

    @Test fun reconnectDiscardsPartialNalAndConfiguration() {
        val model = SessionModel()
        val old = model.open(0) {}!!
        model.input(old, TestStreams.annex(listOf(TestStreams.aud, TestStreams.sps)), 1)
        model.close(old)
        val fresh = model.open(2) {}!!
        assertThrows(StreamRejected::class.java) {
            model.input(fresh, TestStreams.annex(listOf(TestStreams.pps, TestStreams.idr)), 3)
        }
        assertFalse(model.close(fresh))
    }

    @Test fun parserFailureCancelsAndAllowsFreshSession() {
        val model = SessionModel(limits = StreamLimits(maxQueuedAus = 1))
        var cancels = 0
        val token = model.open(0) { cancels++ }!!
        assertThrows(StreamRejected::class.java) {
            model.input(token, TestStreams.annex(TestStreams.nals + TestStreams.second), 1)
        }
        assertEquals(1, cancels)
        assertFalse(model.close(token))
        assertNotNull(model.open(2) {})
    }

    @Test fun eofValidatesTailThenCancelsAndNoQueuedDataSurvives() {
        val model = SessionModel()
        var cancels = 0
        val token = model.open(0) { cancels++ }!!
        model.input(token, TestStreams.annex(TestStreams.first), 1)
        assertTrue(model.eof(token, 2))
        assertEquals(1, cancels)
        assertNull(model.poll(token, 2))
        val fresh = model.open(3) { cancels++ }!!
        model.input(fresh, byteArrayOf(0, 0, 1, 0x65), 4)
        assertThrows(StreamRejected::class.java) { model.eof(fresh, 5) }
        assertEquals(2, cancels)
    }

    @Test fun cancellationReleasesAnIndependentBlockedWaitExactlyOnce() {
        val blocked = CountDownLatch(1)
        val entered = CountDownLatch(1)
        val worker = Thread {
            entered.countDown()
            blocked.await()
        }
        worker.start()
        try {
            assertTrue(entered.await(1, TimeUnit.SECONDS))
            val model = SessionModel()
            val token = model.open(0) { blocked.countDown() }!!
            model.close(token)
            model.close(token)
            worker.join(1_000)
            assertFalse(worker.isAlive)
        } finally {
            blocked.countDown()
            worker.join(1_000)
        }
    }

    @Test fun detachPrecedesReentrantCancellationAndThrowingCleanup() {
        val model = SessionModel()
        var replacement: Long? = null
        val token = model.open(0) {
            replacement = model.open(1) {}
            throw IllegalStateException("cleanup")
        }!!
        assertThrows(IllegalStateException::class.java) { model.close(token) }
        assertNotNull(replacement)
        assertFalse(model.close(token))
        assertTrue(model.close(replacement!!))
    }

    @Test fun originalParserFailureIsPreservedWhenCancellationThrows() {
        val model = SessionModel()
        val token = model.open(0) { throw IllegalStateException("cleanup") }!!
        val failure = assertThrows(StreamRejected::class.java) { model.input(token, byteArrayOf(99), 1) }
        assertEquals("cleanup", failure.suppressed.single().message)
        assertFalse(model.close(token))
    }

    @Test fun diagnosticsCountAssembledInputSeparatelyFromOutputAndReset() {
        val model = SessionModel(inactivityNanos = 100, windowNanos = 10)
        val token = model.open(0) {}!!
        val stream = TestStreams.annex(TestStreams.nals)
        model.input(token, stream, 1)
        assertNotNull(model.poll(token, 1))
        model.output(token, 2)
        val rates = model.sample(token, 10)!!
        assertEquals(stream.size.toDouble() * 8 * 100_000_000, rates.bitsPerSecond!!, 0.01)
        assertEquals(100_000_000.0, rates.inputAuFps!!, 0.01)
        assertEquals(100_000_000.0, rates.outputFps!!, 0.01)
        model.close(token)
        val fresh = model.open(20) {}!!
        assertNull(model.sample(fresh, 30)!!.inputAuFps)
    }

    @Test fun backwardsClockDoesNotMutateActiveSession() {
        val model = SessionModel(inactivityNanos = 10)
        val token = model.open(5) {}!!
        assertThrows(IllegalArgumentException::class.java) { model.tick(token, 4) }
        assertTrue(model.tick(token, 14))
        assertFalse(model.tick(token, 15))
        assertThrows(IllegalArgumentException::class.java) { SessionModel(inactivityNanos = 0) }
    }
}
