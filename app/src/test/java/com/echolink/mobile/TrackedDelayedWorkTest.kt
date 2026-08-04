package com.echolink.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackedDelayedWorkTest {
    @Test
    fun cancelRemovesEveryScheduledCallbackAndLateRunsCannotApply() {
        val scheduler = RecordingScheduler()
        val work = TrackedDelayedWork(scheduler)
        val token = work.beginGeneration()
        var applied = 0
        work.schedule(token, 10L) { applied += 1 }
        work.schedule(token, 20L) { applied += 1 }

        work.cancelAll()
        scheduler.runEverythingEvenIfRemoved()

        assertEquals(0, applied)
        assertEquals(0, work.pendingCount)
        assertEquals(2, scheduler.removed.size)
        assertFalse(work.isCurrent(token))
    }

    @Test
    fun completedCallbackReleasesItsRunnableAndStaleTokenCannotSchedule() {
        val scheduler = RecordingScheduler()
        val work = TrackedDelayedWork(scheduler)
        val token = work.beginGeneration()
        var applied = 0
        assertTrue(work.schedule(token, 10L) { applied += 1 })

        scheduler.runNext()
        work.cancelAll()

        assertEquals(1, applied)
        assertEquals(0, work.pendingCount)
        assertFalse(work.schedule(token, 10L) { applied += 1 })
    }

    private class RecordingScheduler : DelayedWorkScheduler {
        val posted = mutableListOf<Runnable>()
        val removed = mutableListOf<Runnable>()

        override fun postDelayed(runnable: Runnable, delayMillis: Long) {
            posted += runnable
        }

        override fun remove(runnable: Runnable) {
            removed += runnable
        }

        fun runNext() {
            posted.removeFirst().run()
        }

        fun runEverythingEvenIfRemoved() {
            posted.toList().forEach(Runnable::run)
            posted.clear()
        }
    }
}
