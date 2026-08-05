package com.echolink.mobile

enum class ReviewLifecycleEffectOutcome {
    COMPLETED,
    REMOVED,
    ALREADY_ABSENT,
    STILL_ATTACHED,
    ELIGIBLE,
    INELIGIBLE,
    SKIPPED,
}

class ReviewRawEventDiagnosticSampler(
    private val minimumIntervalMillis: Long,
) {
    private var lastLoggedAtMillis: Long? = null

    fun shouldLog(nowMillis: Long): Boolean {
        val previous = lastLoggedAtMillis
        if (previous != null && nowMillis - previous < minimumIntervalMillis) return false
        lastLoggedAtMillis = nowMillis
        return true
    }
}

object ReviewLifecycleDiagnostics {
    const val MAX_DIAGNOSTIC_LENGTH = 320
    private const val MAX_IDENTITY_LENGTH = 96

    fun accessibilityEvent(
        eventType: Int,
        eventPackage: String,
        eventWindowId: Int,
        verifiedPackage: String?,
        verifiedWindowId: Int?,
        verified: Boolean,
    ): String = bounded(
        "kind=accessibility-event" +
            ";eventType=$eventType" +
            ";eventPackage=${safeIdentity(eventPackage)}" +
            ";eventWindow=$eventWindowId" +
            ";verifiedPackage=${safeIdentity(verifiedPackage.orEmpty())}" +
            ";verifiedWindow=${verifiedWindowId ?: -1}" +
            ";verified=$verified",
    )

    fun transition(
        event: ReviewSurfaceEvent,
        previousState: ReviewSurfaceState,
        nextState: ReviewSurfaceState,
        effects: List<ReviewSurfaceEffect>,
    ): String = bounded(
        "kind=surface-transition" +
            ";event=${eventName(event)}" +
            ";from=${stateName(previousState)}" +
            ";to=${stateName(nextState)}" +
            ";effects=${effects.size}",
    )

    fun effect(
        effect: ReviewSurfaceEffect,
        outcome: ReviewLifecycleEffectOutcome,
    ): String = bounded(
        "kind=surface-effect;effect=${effectName(effect)};outcome=${outcome.name}",
    )

    private fun safeIdentity(value: String): String = value
        .filter { character -> character.isLetterOrDigit() || character in "._-" }
        .take(MAX_IDENTITY_LENGTH)

    private fun bounded(value: String): String = value.take(MAX_DIAGNOSTIC_LENGTH)

    private fun eventName(event: ReviewSurfaceEvent): String = when (event) {
        is ReviewSurfaceEvent.FocusedTargetAvailable -> "FocusedTargetAvailable"
        ReviewSurfaceEvent.OpenRequested -> "OpenRequested"
        ReviewSurfaceEvent.ReviewAttached -> "ReviewAttached"
        ReviewSurfaceEvent.CloseRequested -> "CloseRequested"
        ReviewSurfaceEvent.StaleWorkCancelled -> "StaleWorkCancelled"
        ReviewSurfaceEvent.PanelDetached -> "PanelDetached"
        is ReviewSurfaceEvent.RestorationCompleted -> "RestorationCompleted"
        ReviewSurfaceEvent.RestorationFailed -> "RestorationFailed"
        ReviewSurfaceEvent.TargetLost -> "TargetLost"
        ReviewSurfaceEvent.PasswordFocused -> "PasswordFocused"
        ReviewSurfaceEvent.ApplicationSwitched -> "ApplicationSwitched"
        ReviewSurfaceEvent.ServiceReconnected -> "ServiceReconnected"
        ReviewSurfaceEvent.Destroy -> "Destroy"
    }

    private fun stateName(state: ReviewSurfaceState): String = when (state) {
        ReviewSurfaceState.Hidden -> "Hidden"
        is ReviewSurfaceState.TriggerVisible -> "TriggerVisible"
        is ReviewSurfaceState.Opening -> "Opening"
        is ReviewSurfaceState.ReviewOpen -> "ReviewOpen"
        is ReviewSurfaceState.Closing -> "Closing"
        is ReviewSurfaceState.RestoringFocus -> "RestoringFocus"
        ReviewSurfaceState.Destroyed -> "Destroyed"
    }

    private fun effectName(effect: ReviewSurfaceEffect): String = when (effect) {
        ReviewSurfaceEffect.CancelStaleCorrectionAndCallbackWork ->
            "CancelStaleCorrectionAndCallbackWork"
        ReviewSurfaceEffect.DetachReviewPanelSafely -> "DetachReviewPanelSafely"
        ReviewSurfaceEffect.ScrubTransientTextHolders -> "ScrubTransientTextHolders"
        is ReviewSurfaceEffect.ReacquireFocusedTarget -> "ReacquireFocusedTarget"
        is ReviewSurfaceEffect.RestoreKeyboard -> "RestoreKeyboard"
    }
}
