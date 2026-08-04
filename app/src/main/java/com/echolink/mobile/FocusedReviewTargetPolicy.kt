package com.echolink.mobile

object FocusedReviewTargetPolicy {
    fun resolveEligibility(
        baseEligibility: ReviewTargetEligibility,
        expectedTargetId: ReviewTargetId?,
        retainedPackage: String?,
        focusedPackage: String,
    ): ReviewTargetEligibility = when {
        baseEligibility != ReviewTargetEligibility.ELIGIBLE -> baseEligibility
        expectedTargetId != null &&
            retainedPackage != null &&
            retainedPackage != focusedPackage -> ReviewTargetEligibility.SWITCHED_APPLICATION
        else -> ReviewTargetEligibility.ELIGIBLE
    }

    fun accepts(
        isInputFocused: Boolean,
        eligibility: ReviewTargetEligibility,
        actualTargetId: ReviewTargetId?,
        expectedTargetId: ReviewTargetId?,
    ): Boolean =
        isInputFocused &&
            eligibility == ReviewTargetEligibility.ELIGIBLE &&
            actualTargetId != null &&
            (expectedTargetId == null || expectedTargetId == actualTargetId)
}
