package com.echolink.mobile

object FocusedReviewTargetPolicy {
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
