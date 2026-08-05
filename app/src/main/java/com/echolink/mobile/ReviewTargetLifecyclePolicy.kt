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
