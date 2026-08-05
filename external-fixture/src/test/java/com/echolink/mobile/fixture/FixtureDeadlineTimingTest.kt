package com.echolink.mobile.fixture

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FixtureDeadlineTimingTest {
    @Test fun trueSnapshotStartedAt499AndCompletedAt502Passes() {
        val clock = SampleClock(499L, completesAt = 502L, result = true)
        assertTrue(clock.await(deadlineMs = 500L))
    }

    @Test fun falseSnapshotStartedAt499AndCompletedAt502Fails() {
        val clock = SampleClock(499L, completesAt = 502L, result = false)
        assertFalse(clock.await(deadlineMs = 500L))
    }

    @Test fun trueSnapshotStartingAt501FailsWithoutSampling() {
        val clock = SampleClock(501L, completesAt = 502L, result = true)
        assertFalse(clock.await(deadlineMs = 500L))
        assertFalse(clock.sampled)
    }

    private class SampleClock(
        private var now: Long,
        private val completesAt: Long,
        private val result: Boolean,
    ) {
        var sampled = false
            private set

        fun await(deadlineMs: Long): Boolean = FixtureDeadlineTiming.awaitCondition(
            deadlineMs = deadlineMs,
            condition = {
                sampled = true
                now = completesAt
                result
            },
            nowMs = { now },
            pauseMs = { now += it },
        )
    }
}
