package com.echolink.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Sections 12 to 14 of the manual: dragging, snapping, and surviving rotation. */
class FloatingAssistantPlacementTest {
    private val size = 144 // 48 dp at 3x
    private val portraitWidth = 1080
    private val portraitHeight = 2400
    private val statusBar = 90
    private val gestureBar = 60

    @Test
    fun theTriggerSnapsToWhicheverEdgeItsCentreIsNearer() {
        assertEquals(
            FloatingAssistantPlacement.Side.LEFT,
            FloatingAssistantPlacement.snapSide(0, size, portraitWidth),
        )
        assertEquals(
            FloatingAssistantPlacement.Side.RIGHT,
            FloatingAssistantPlacement.snapSide(portraitWidth - size, size, portraitWidth),
        )
        // Dead centre belongs to the left rather than being undefined.
        val centred = (portraitWidth - size) / 2
        assertEquals(
            FloatingAssistantPlacement.Side.LEFT,
            FloatingAssistantPlacement.snapSide(centred, size, portraitWidth),
        )
    }

    @Test
    fun aRestingTriggerIsInsetFromTheEdgeAndNeverOffScreen() {
        val margin = 24
        val left = FloatingAssistantPlacement.restingX(
            FloatingAssistantPlacement.Side.LEFT, size, portraitWidth, margin,
        )
        val right = FloatingAssistantPlacement.restingX(
            FloatingAssistantPlacement.Side.RIGHT, size, portraitWidth, margin,
        )
        assertEquals(margin, left)
        assertEquals(portraitWidth - size - margin, right)
        assertTrue(right + size <= portraitWidth)
    }

    @Test
    fun aScreenNarrowerThanTheTriggerStillProducesAPositionOnIt() {
        val x = FloatingAssistantPlacement.restingX(
            FloatingAssistantPlacement.Side.RIGHT,
            triggerSizePx = 400,
            screenWidthPx = 300,
            marginPx = 24,
        )
        assertEquals(0, x)
    }

    @Test
    fun theTriggerNeverRestsUnderTheStatusBarOrTheGestureBar() {
        val tooHigh = FloatingAssistantPlacement.clampY(
            0, size, portraitHeight, statusBar, gestureBar,
        )
        val tooLow = FloatingAssistantPlacement.clampY(
            portraitHeight, size, portraitHeight, statusBar, gestureBar,
        )
        assertEquals(statusBar, tooHigh)
        assertEquals(portraitHeight - gestureBar - size, tooLow)
    }

    @Test
    fun aPositionSurvivesTurningThePhoneSideways() {
        // Two thirds down a portrait screen must still be two thirds down the
        // landscape one. Storing pixels would put it off the bottom.
        val portraitY = FloatingAssistantPlacement.yForFraction(
            0.66f, size, portraitHeight, statusBar, gestureBar,
        )
        val fraction = FloatingAssistantPlacement.verticalFraction(
            portraitY, size, portraitHeight, statusBar, gestureBar,
        )
        val landscapeHeight = 1080
        val landscapeY = FloatingAssistantPlacement.yForFraction(
            fraction, size, landscapeHeight, statusBar, gestureBar,
        )
        assertTrue("landscapeY=$landscapeY", landscapeY + size <= landscapeHeight - gestureBar)
        assertTrue("landscapeY=$landscapeY", landscapeY >= statusBar)
        val round = FloatingAssistantPlacement.verticalFraction(
            landscapeY, size, landscapeHeight, statusBar, gestureBar,
        )
        assertEquals(fraction, round, 0.02f)
    }

    @Test
    fun aFractionIsAlwaysBetweenZeroAndOne() {
        val above = FloatingAssistantPlacement.verticalFraction(
            -500, size, portraitHeight, statusBar, gestureBar,
        )
        val below = FloatingAssistantPlacement.verticalFraction(
            99_999, size, portraitHeight, statusBar, gestureBar,
        )
        assertEquals(0f, above, 0f)
        assertEquals(1f, below, 0f)
    }

    @Test
    fun aScreenWithNoRoomLeftDoesNotProduceANegativePosition() {
        // A split-screen pane shorter than the trigger plus its protected
        // areas. There is no correct answer, and the wrong one is off-screen.
        val y = FloatingAssistantPlacement.clampY(
            500, size, screenHeightPx = 200, protectedTopPx = statusBar, protectedBottomPx = gestureBar,
        )
        assertTrue("y=$y", y >= 0)
    }

    @Test
    fun aStillFingerStillOpensTheReview() {
        assertFalse(FloatingAssistantPlacement.isDrag(totalMovementPx = 5, touchSlopPx = 24))
        assertTrue(FloatingAssistantPlacement.isDrag(totalMovementPx = 60, touchSlopPx = 24))
    }
}
