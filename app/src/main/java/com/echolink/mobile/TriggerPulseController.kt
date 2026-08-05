package com.echolink.mobile

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.view.View
import java.util.WeakHashMap

object TriggerPulsePolicy {
    fun shouldAnimate(state: ManuscriptTriggerState): Boolean =
        state == ManuscriptTriggerState.VISIBLE_PULSING

    fun isVisible(state: ManuscriptTriggerState): Boolean =
        state == ManuscriptTriggerState.VISIBLE_PULSING ||
            state == ManuscriptTriggerState.VISIBLE_STATIC_REDUCED_MOTION
}

object TriggerPulseController {
    private val animators = WeakHashMap<View, AnimatorSet>()

    fun applyState(view: View, state: ManuscriptTriggerState) {
        if (!TriggerPulsePolicy.isVisible(state)) {
            stop(view)
            view.visibility = View.GONE
            return
        }

        val wasGone = view.visibility == View.GONE
        view.visibility = View.VISIBLE
        if (wasGone) view.requestLayout()
        if (!TriggerPulsePolicy.shouldAnimate(state)) {
            stop(view)
            return
        }
        if (animators[view]?.isStarted == true) return

        val alpha = pulse(
            view,
            View.ALPHA,
            ManuscriptTriggerPresentation.PULSE_ALPHA_FROM,
            ManuscriptTriggerPresentation.PULSE_ALPHA_TO,
        )
        val scaleX = pulse(
            view,
            View.SCALE_X,
            ManuscriptTriggerPresentation.PULSE_SCALE_FROM,
            ManuscriptTriggerPresentation.PULSE_SCALE_TO,
        )
        val scaleY = pulse(
            view,
            View.SCALE_Y,
            ManuscriptTriggerPresentation.PULSE_SCALE_FROM,
            ManuscriptTriggerPresentation.PULSE_SCALE_TO,
        )
        AnimatorSet().also { animator ->
            animator.playTogether(alpha, scaleX, scaleY)
            animators[view] = animator
            animator.start()
        }
    }

    fun stop(view: View) {
        animators.remove(view)?.cancel()
        view.alpha = ManuscriptTriggerPresentation.PULSE_ALPHA_FROM
        view.scaleX = ManuscriptTriggerPresentation.PULSE_SCALE_FROM
        view.scaleY = ManuscriptTriggerPresentation.PULSE_SCALE_FROM
    }

    private fun pulse(
        view: View,
        property: android.util.Property<View, Float>,
        from: Float,
        to: Float,
    ): ObjectAnimator = ObjectAnimator.ofFloat(view, property, from, to).apply {
        duration = ManuscriptTriggerPresentation.PULSE_HALF_CYCLE_MS
        repeatMode = ValueAnimator.REVERSE
        repeatCount = ValueAnimator.INFINITE
    }
}
