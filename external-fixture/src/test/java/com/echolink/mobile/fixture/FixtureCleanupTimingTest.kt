package com.echolink.mobile.fixture

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FixtureCleanupTimingTest {
    @Test fun removalAtGateThenStableAbsencePasses() {
        val clock = FakeClock { now -> now < 300L }
        assertTrue(clock.awaitGate())
    }

    @Test fun removalAfterGateFails() {
        val clock = FakeClock { now -> now <= 300L }
        assertFalse(clock.awaitGate())
    }

    @Test fun reappearanceDuringStableIntervalFails() {
        val clock = FakeClock { now -> now < 100L || now in 500L..550L }
        assertFalse(clock.awaitGate())
    }

    private class FakeClock(private val presentAt: (Long) -> Boolean) {
        private var now = 0L

        fun awaitGate(): Boolean = FixtureCleanupTiming.awaitRemovalThenStableAbsence(
            isOverlayPresent = { presentAt(now) },
            nowMs = { now },
            pauseMs = { now += it },
        )
    }
}
