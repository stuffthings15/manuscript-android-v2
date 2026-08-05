package com.echolink.mobile

interface ReviewSurfaceEffectActions {
    fun cancelStaleCorrectionAndCallbackWork()
    fun detachReviewPanelSafely(): ReviewPanelDetachOutcome
    fun terminallyDetachReviewSurface()
    fun scrubTransientTextHolders()
    fun reacquireFocusedTarget(mode: ReviewTargetReacquisition): ReviewTarget
    fun restoreKeyboard(targetId: ReviewTargetId)
    fun renderReviewSurfaceState(state: ReviewSurfaceState) = Unit
    fun recordLifecycleDiagnostic(diagnostic: String) = Unit
}

enum class ReviewPanelDetachOutcome {
    REMOVED,
    ALREADY_ABSENT,
    STILL_ATTACHED,
}

/**
 * Runs lifecycle effects in controller order. Events raised by an effect are
 * deferred until the full effect batch has finished, so close always cancels,
 * detaches, and scrubs before focus or keyboard restoration can start.
 */
class ReviewSurfaceEffectExecutor(
    private val controller: ReviewSurfaceController,
    private val actions: ReviewSurfaceEffectActions,
) {
    private var callbackGeneration = 0L
    private var detachAttemptCount = 0
    private var dispatching = false
    private val pendingEvents = ArrayDeque<ReviewSurfaceEvent>()

    fun dispatch(event: ReviewSurfaceEvent) {
        pendingEvents.addLast(event)
        drainEvents()
    }

    fun currentCallbackToken(): Long = callbackGeneration

    fun isCurrentCallback(token: Long): Boolean =
        token == callbackGeneration && controller.state != ReviewSurfaceState.Destroyed

    private fun drainEvents() {
        if (dispatching) return
        dispatching = true
        try {
            while (pendingEvents.isNotEmpty()) {
                val event = pendingEvents.removeFirst()
                val previousState = controller.state
                val effects = controller.dispatch(event)
                actions.recordLifecycleDiagnostic(
                    ReviewLifecycleDiagnostics.transition(
                        event = event,
                        previousState = previousState,
                        nextState = controller.state,
                        effects = effects,
                    ),
                )
                if (controller.state != previousState) {
                    actions.renderReviewSurfaceState(controller.state)
                }
                runEffects(effects)
            }
        } finally {
            dispatching = false
        }
    }

    private fun runEffects(effects: List<ReviewSurfaceEffect>) {
        val raisedEvents = ArrayDeque<ReviewSurfaceEvent>()
        var reacquiredTarget: ReviewTarget? = null
        effects.forEach { effect ->
            when (effect) {
                ReviewSurfaceEffect.CancelStaleCorrectionAndCallbackWork -> {
                    callbackGeneration += 1L
                    actions.cancelStaleCorrectionAndCallbackWork()
                    recordEffect(effect, ReviewLifecycleEffectOutcome.COMPLETED)
                }
                ReviewSurfaceEffect.DetachReviewPanelSafely -> {
                    val outcome = actions.detachReviewPanelSafely()
                    recordEffect(
                        effect,
                        when (outcome) {
                            ReviewPanelDetachOutcome.REMOVED -> ReviewLifecycleEffectOutcome.REMOVED
                            ReviewPanelDetachOutcome.ALREADY_ABSENT ->
                                ReviewLifecycleEffectOutcome.ALREADY_ABSENT
                            ReviewPanelDetachOutcome.STILL_ATTACHED ->
                                ReviewLifecycleEffectOutcome.STILL_ATTACHED
                        },
                    )
                    if (outcome != ReviewPanelDetachOutcome.STILL_ATTACHED) {
                        detachAttemptCount = 0
                        raisedEvents.addLast(ReviewSurfaceEvent.PanelDetached)
                    } else {
                        detachAttemptCount += 1
                        if (detachAttemptCount < MAX_DETACH_ATTEMPTS) {
                            raisedEvents.addLast(ReviewSurfaceEvent.CloseRequested)
                        } else {
                            detachAttemptCount = 0
                            actions.terminallyDetachReviewSurface()
                            raisedEvents.addLast(ReviewSurfaceEvent.Destroy)
                        }
                    }
                }
                ReviewSurfaceEffect.ScrubTransientTextHolders ->
                    actions.scrubTransientTextHolders().also {
                        recordEffect(effect, ReviewLifecycleEffectOutcome.COMPLETED)
                    }
                is ReviewSurfaceEffect.ReacquireFocusedTarget -> {
                    val target = actions.reacquireFocusedTarget(effect.mode)
                    recordEffect(
                        effect,
                        if (target.canRestore) {
                            ReviewLifecycleEffectOutcome.ELIGIBLE
                        } else {
                            ReviewLifecycleEffectOutcome.INELIGIBLE
                        },
                    )
                    reacquiredTarget = target
                    raisedEvents.addLast(
                        when (effect.mode) {
                            ReviewTargetReacquisition.AdoptCurrent ->
                                ReviewSurfaceEvent.FocusedTargetAvailable(target)
                            is ReviewTargetReacquisition.RestoreExact -> {
                                if (target.canRestore && target.id == effect.mode.targetId) {
                                    ReviewSurfaceEvent.RestorationCompleted(target)
                                } else {
                                    ReviewSurfaceEvent.RestorationFailed
                                }
                            }
                        },
                    )
                }
                is ReviewSurfaceEffect.RestoreKeyboard -> {
                    val target = reacquiredTarget
                    if (target?.canRestore == true && target.id == effect.targetId) {
                        actions.restoreKeyboard(effect.targetId)
                        recordEffect(effect, ReviewLifecycleEffectOutcome.COMPLETED)
                    } else {
                        recordEffect(effect, ReviewLifecycleEffectOutcome.SKIPPED)
                    }
                }
            }
        }
        pendingEvents.addAll(raisedEvents)
    }

    private fun recordEffect(
        effect: ReviewSurfaceEffect,
        outcome: ReviewLifecycleEffectOutcome,
    ) {
        actions.recordLifecycleDiagnostic(ReviewLifecycleDiagnostics.effect(effect, outcome))
    }

    private companion object {
        const val MAX_DETACH_ATTEMPTS = 3
    }
}
