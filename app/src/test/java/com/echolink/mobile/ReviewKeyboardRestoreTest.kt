package com.echolink.mobile

import org.junit.Assert.assertEquals
import org.junit.Test

class ReviewKeyboardRestoreTest {
    private val targetId = ReviewTargetId("main-editor")

    @Test
    fun invalidationCancelsPendingRestoreAndLateRunnableDoesNothing() {
        val scheduler = RecordingKeyboardScheduler()
        var state: ReviewSurfaceState = ReviewSurfaceState.TriggerVisible(targetId)
        val restored = mutableListOf<ReviewTargetId>()
        val tracker = ReviewKeyboardRestore(
            scheduler = scheduler,
            currentState = { state },
            isEligible = { true },
            restore = restored::add,
        )

        tracker.schedule(targetId)
        tracker.cancel()
        state = ReviewSurfaceState.Hidden
        scheduler.runRemovedCallbacks()

        assertEquals(emptyList<ReviewTargetId>(), restored)
    }

    @Test
    fun pendingRestoreRequiresMatchingVisibleTargetAndCurrentEligibility() {
        val scheduler = RecordingKeyboardScheduler()
        var state: ReviewSurfaceState = ReviewSurfaceState.RestoringFocus(targetId)
        var eligible = true
        val restored = mutableListOf<ReviewTargetId>()
        val tracker = ReviewKeyboardRestore(
            scheduler = scheduler,
            currentState = { state },
            isEligible = { eligible },
            restore = restored::add,
        )

        tracker.schedule(targetId)
        scheduler.runNext()
        assertEquals(emptyList<ReviewTargetId>(), restored)

        state = ReviewSurfaceState.TriggerVisible(targetId)
        eligible = false
        tracker.schedule(targetId)
        scheduler.runNext()
        assertEquals(emptyList<ReviewTargetId>(), restored)

        eligible = true
        tracker.schedule(targetId)
        scheduler.runNext()
        assertEquals(listOf(targetId), restored)
    }

    private class RecordingKeyboardScheduler : ReviewKeyboardRestoreScheduler {
        private val pending = ArrayDeque<Runnable>()
        private val removed = mutableListOf<Runnable>()

        override fun post(runnable: Runnable) {
            pending.addLast(runnable)
        }

        override fun remove(runnable: Runnable) {
            pending.remove(runnable)
            removed += runnable
        }

        fun runNext() {
            pending.removeFirst().run()
        }

        fun runRemovedCallbacks() {
            removed.toList().forEach(Runnable::run)
        }
    }
}
