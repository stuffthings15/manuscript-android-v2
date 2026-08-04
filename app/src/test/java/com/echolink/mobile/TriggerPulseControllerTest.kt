package com.echolink.mobile

import org.junit.Assert.assertEquals
import org.junit.Test

class TriggerPulseControllerTest {
    @Test
    fun onlyThePulsingStateStartsAnAnimator() {
        ManuscriptTriggerState.entries.forEach { state ->
            assertEquals(
                state == ManuscriptTriggerState.VISIBLE_PULSING,
                TriggerPulsePolicy.shouldAnimate(state),
            )
        }
    }

    @Test
    fun visibleStatesStayVisibleAndHiddenStatesDoNot() {
        ManuscriptTriggerState.entries.forEach { state ->
            assertEquals(
                state == ManuscriptTriggerState.VISIBLE_PULSING ||
                    state == ManuscriptTriggerState.VISIBLE_STATIC_REDUCED_MOTION,
                TriggerPulsePolicy.isVisible(state),
            )
        }
    }
}
