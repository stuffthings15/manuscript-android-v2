package com.echolink.mobile

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PrimaryPressActionGateTest {
    @Test
    fun validPrimaryDownInvokesOnceAndSuppressesSameGestureClick() {
        var calls = 0
        val gate = PrimaryPressActionGate { calls += 1 }

        assertTrue(gate.onPrimaryDown(enabled = true, inside = true))
        gate.onClick()

        assertTrue(calls == 1)
    }

    @Test
    fun disabledOrOutsideDownCannotInvokeClose() {
        var calls = 0
        val gate = PrimaryPressActionGate { calls += 1 }

        assertFalse(gate.onPrimaryDown(enabled = false, inside = true))
        assertFalse(gate.onPrimaryDown(enabled = true, inside = false))

        assertTrue(calls == 0)
    }

    @Test
    fun accessibilityClickStillInvokesAfterTouchGestureEnds() {
        var calls = 0
        val gate = PrimaryPressActionGate { calls += 1 }

        assertTrue(gate.onPrimaryDown(enabled = true, inside = true))
        gate.endTouchGesture()
        gate.onClick()

        assertTrue(calls == 2)
    }
}
