package com.echolink.mobile

interface ReviewKeyboardRestoreScheduler {
    fun post(runnable: Runnable)
    fun remove(runnable: Runnable)
}

class ReviewKeyboardRestore(
    private val scheduler: ReviewKeyboardRestoreScheduler,
    private val currentState: () -> ReviewSurfaceState,
    private val isEligible: (ReviewTargetId) -> Boolean,
    private val restore: (ReviewTargetId) -> Unit,
) {
    private var pendingTargetId: ReviewTargetId? = null
    private val restoreRunnable = Runnable {
        val targetId = pendingTargetId
        pendingTargetId = null
        if (
            targetId != null &&
            currentState() == ReviewSurfaceState.TriggerVisible(targetId) &&
            isEligible(targetId)
        ) {
            restore(targetId)
        }
    }

    fun schedule(targetId: ReviewTargetId) {
        cancel()
        pendingTargetId = targetId
        scheduler.post(restoreRunnable)
    }

    fun cancel() {
        pendingTargetId = null
        scheduler.remove(restoreRunnable)
    }
}
