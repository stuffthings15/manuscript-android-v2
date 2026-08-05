package com.echolink.mobile

enum class ManuscriptTriggerState {
    HIDDEN_NO_TEXT,
    HIDDEN_PASSWORD,
    HIDDEN_NOT_EDITABLE,
    HIDDEN_UNFOCUSED,
    HIDDEN_REVIEW_OPEN,
    VISIBLE_PULSING,
    VISIBLE_STATIC_REDUCED_MOTION,
}

data class TriggerFacts(
    val focused: Boolean,
    val editable: Boolean,
    val hasText: Boolean,
    val password: Boolean,
    val reviewOpen: Boolean,
    val animationsEnabled: Boolean,
)

object ManuscriptTriggerPolicy {
    fun shouldPublishAccessibilitySurface(
        attached: Boolean,
        state: ManuscriptTriggerState,
        verifiedEligibleTarget: Boolean,
    ): Boolean =
        attached &&
            verifiedEligibleTarget &&
            (
                state == ManuscriptTriggerState.VISIBLE_PULSING ||
                    state == ManuscriptTriggerState.VISIBLE_STATIC_REDUCED_MOTION
            )

    fun state(
        focused: Boolean,
        editable: Boolean,
        hasText: Boolean,
        password: Boolean,
        reviewOpen: Boolean,
        animationsEnabled: Boolean,
    ): ManuscriptTriggerState = state(
        TriggerFacts(
            focused = focused,
            editable = editable,
            hasText = hasText,
            password = password,
            reviewOpen = reviewOpen,
            animationsEnabled = animationsEnabled,
        ),
    )

    fun state(facts: TriggerFacts): ManuscriptTriggerState = when {
        facts.password -> ManuscriptTriggerState.HIDDEN_PASSWORD
        !facts.editable -> ManuscriptTriggerState.HIDDEN_NOT_EDITABLE
        !facts.focused -> ManuscriptTriggerState.HIDDEN_UNFOCUSED
        !facts.hasText -> ManuscriptTriggerState.HIDDEN_NO_TEXT
        facts.reviewOpen -> ManuscriptTriggerState.HIDDEN_REVIEW_OPEN
        facts.animationsEnabled -> ManuscriptTriggerState.VISIBLE_PULSING
        else -> ManuscriptTriggerState.VISIBLE_STATIC_REDUCED_MOTION
    }
}
