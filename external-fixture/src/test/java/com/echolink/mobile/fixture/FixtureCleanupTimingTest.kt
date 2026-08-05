package com.echolink.mobile.fixture

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
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

    @Test fun explicitEmulatorRemovalGateUses1000MsWithoutChangingPhysicalDefault() {
        val emulator = FakeClock { now -> now < 1_000L }
        val physical = FakeClock { now -> now < 1_000L }

        assertTrue(emulator.awaitGate(1_000L))
        assertFalse(physical.awaitGate())
    }

    @Test fun fixtureCleanupAlwaysRunsAfterBodyFailure() {
        val calls = mutableListOf<String>()

        assertThrows(AssertionError::class.java) {
            FixtureFinallyCleanup.run(
                body = { calls += "body"; throw AssertionError("expected") },
                cleanup = { calls += "cleanup" },
            )
        }

        assertEquals(listOf("body", "cleanup"), calls)
    }

    private class FakeClock(private val presentAt: (Long) -> Boolean) {
        private var now = 0L

        fun awaitGate(removalGateMs: Long = FixtureCleanupTiming.REMOVAL_GATE_MS): Boolean =
            FixtureCleanupTiming.awaitRemovalThenStableAbsence(
            isOverlayPresent = { presentAt(now) },
            nowMs = { now },
            pauseMs = { now += it },
            removalGateMs = removalGateMs,
        )
    }
}
