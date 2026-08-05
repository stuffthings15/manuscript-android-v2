package com.echolink.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalDetachLifecycleCoordinatorTest {
    @Test
    fun noEventsCannotStrandLogicalCompletionAfterPhysicalDetachStalls() {
        val fixture = fixture()
        val surface = OwnedSurface(attached = true)

        fixture.coordinator.begin(surface) { fixture.logicalCompletions += 1 }
        fixture.scheduler.advanceBy(LOGICAL_DEADLINE_MS)

        assertTrue(surface.inert)
        assertTrue(surface.detachOnlyObservedAfterDeactivation)
        assertEquals(1, fixture.logicalCompletions)
        assertTrue(fixture.coordinator.owns(surface))
    }

    @Test
    fun laterEventDetachReleasesOwnershipWithoutRepeatingLogicalCompletion() {
        val fixture = fixture()
        val surface = OwnedSurface(attached = true)
        fixture.coordinator.begin(surface) { fixture.logicalCompletions += 1 }
        fixture.scheduler.advanceBy(LOGICAL_DEADLINE_MS)

        surface.attached = false
        fixture.coordinator.retryFromEvent()

        assertFalse(fixture.coordinator.owns(surface))
        assertEquals(1, fixture.logicalCompletions)
        assertEquals(1, fixture.physicalCompletions)
    }

    @Test
    fun destroyCompletesLogicallyAndRetainsAttachedOwnershipForCleanup() {
        val fixture = fixture()
        val surface = OwnedSurface(attached = true)
        fixture.coordinator.begin(surface) { fixture.logicalCompletions += 1 }

        fixture.coordinator.destroy()

        assertTrue(surface.inert)
        assertEquals(1, fixture.logicalCompletions)
        assertTrue(fixture.coordinator.owns(surface))
    }

    private fun fixture(): Fixture {
        val scheduler = TimedScheduler()
        lateinit var fixture: Fixture
        val coordinator = TerminalDetachLifecycleCoordinator(
            scheduler = scheduler,
            retryDelayMillis = RETRY_MS,
            logicalCompletionDeadlineMillis = LOGICAL_DEADLINE_MS,
            postCompletionRetryDelayMillis = EVENT_RETRY_MS,
            rapidRetryAttempts = 3,
            scheduledCleanupAttempts = 3,
            deactivate = { surface: OwnedSurface -> surface.inert = true },
            attemptDetach = { surface: OwnedSurface ->
                surface.detachAttempts += 1
                surface.detachOnlyObservedAfterDeactivation =
                    surface.detachOnlyObservedAfterDeactivation && surface.inert
                !surface.attached
            },
            onPhysicalDetach = { fixture.physicalCompletions += 1 },
        )
        fixture = Fixture(scheduler, coordinator)
        return fixture
    }

    private data class OwnedSurface(
        var attached: Boolean,
        var inert: Boolean = false,
        var detachAttempts: Int = 0,
        var detachOnlyObservedAfterDeactivation: Boolean = true,
    )

    private data class Fixture(
        val scheduler: TimedScheduler,
        val coordinator: TerminalDetachLifecycleCoordinator<OwnedSurface>,
        var logicalCompletions: Int = 0,
        var physicalCompletions: Int = 0,
    )

    private class TimedScheduler : DelayedWorkScheduler {
        private data class Pending(
            val runnable: Runnable,
            val dueAtMillis: Long,
        )

        private var nowMillis = 0L
        private val pending = mutableListOf<Pending>()

        override fun postDelayed(runnable: Runnable, delayMillis: Long) {
            pending += Pending(runnable, nowMillis + delayMillis)
        }

        override fun remove(runnable: Runnable) {
            pending.removeAll { it.runnable === runnable }
        }

        fun advanceBy(deltaMillis: Long) {
            val target = nowMillis + deltaMillis
            while (true) {
                val next = pending.filter { it.dueAtMillis <= target }.minByOrNull { it.dueAtMillis }
                    ?: break
                pending.remove(next)
                nowMillis = next.dueAtMillis
                next.runnable.run()
            }
            nowMillis = target
        }
    }

    private companion object {
        const val RETRY_MS = 16L
        const val LOGICAL_DEADLINE_MS = 64L
        const val EVENT_RETRY_MS = 250L
    }
}
