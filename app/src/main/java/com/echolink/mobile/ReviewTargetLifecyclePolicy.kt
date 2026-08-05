package com.echolink.mobile

enum class RetainedTargetEvidence {
    VERIFIED_EXACT_TARGET_ELIGIBLE,
    VERIFIED_DIFFERENT_ELIGIBLE_TARGET,
    VERIFIED_ELIGIBLE_FOCUS_LOST,
    VERIFIED_EXACT_TARGET_DETACHED,
    VERIFIED_PASSWORD_FOCUS,
    VERIFIED_APPLICATION_SWITCH,
    UNVERIFIED_EVENT,
}

enum class ReviewTargetLifecycleAction {
    KEEP,
    ADOPT_VERIFIED_TARGET,
    DEFER_TARGET_LOSS,
    TARGET_LOST,
    PASSWORD_FOCUSED,
    APPLICATION_SWITCHED,
}

object ReviewTargetLifecyclePolicy {
    fun action(evidence: RetainedTargetEvidence): ReviewTargetLifecycleAction = when (evidence) {
        RetainedTargetEvidence.VERIFIED_EXACT_TARGET_ELIGIBLE,
        RetainedTargetEvidence.UNVERIFIED_EVENT,
        -> ReviewTargetLifecycleAction.KEEP
        RetainedTargetEvidence.VERIFIED_DIFFERENT_ELIGIBLE_TARGET ->
            ReviewTargetLifecycleAction.ADOPT_VERIFIED_TARGET
        RetainedTargetEvidence.VERIFIED_ELIGIBLE_FOCUS_LOST ->
            ReviewTargetLifecycleAction.DEFER_TARGET_LOSS
        RetainedTargetEvidence.VERIFIED_EXACT_TARGET_DETACHED ->
            ReviewTargetLifecycleAction.TARGET_LOST
        RetainedTargetEvidence.VERIFIED_PASSWORD_FOCUS ->
            ReviewTargetLifecycleAction.PASSWORD_FOCUSED
        RetainedTargetEvidence.VERIFIED_APPLICATION_SWITCH ->
            ReviewTargetLifecycleAction.APPLICATION_SWITCHED
    }
}

/** A non-sliding deadline owned by one exact retained editor identity. */
class ReviewTargetLossDeadline(
    private val graceMillis: Long,
) {
    private var owner: ReviewTargetId? = null
    private var deadlineMillis: Long? = null

    fun arm(targetId: ReviewTargetId, nowMillis: Long): Long {
        if (owner != targetId || deadlineMillis == null) {
            owner = targetId
            deadlineMillis = nowMillis + graceMillis
        }
        return (requireNotNull(deadlineMillis) - nowMillis).coerceAtLeast(0L)
    }

    fun consumeIfDue(targetId: ReviewTargetId, nowMillis: Long): Boolean {
        val deadline = deadlineMillis ?: return false
        if (owner != targetId || nowMillis < deadline) return false
        cancel()
        return true
    }

    fun cancel() {
        owner = null
        deadlineMillis = null
    }
}

/** Platform nodes can become stale between any two property reads. */
object RetainedTargetInspectionBoundary {
    fun inspect(block: () -> RetainedTargetEvidence): RetainedTargetEvidence = try {
        block()
    } catch (_: RuntimeException) {
        RetainedTargetEvidence.VERIFIED_EXACT_TARGET_DETACHED
    }
}

object RetainedTargetWindowChangeInspection {
    fun inspect(
        isWindowsChanged: Boolean,
        exactNodeStillAttached: () -> Boolean,
    ): RetainedTargetEvidence {
        if (!isWindowsChanged) return RetainedTargetEvidence.UNVERIFIED_EVENT
        return RetainedTargetInspectionBoundary.inspect {
            if (exactNodeStillAttached()) {
                RetainedTargetEvidence.VERIFIED_EXACT_TARGET_ELIGIBLE
            } else {
                RetainedTargetEvidence.VERIFIED_EXACT_TARGET_DETACHED
            }
        }
    }
}
