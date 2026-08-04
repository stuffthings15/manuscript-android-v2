package com.echolink.mobile

import org.junit.Assert.assertEquals
import org.junit.Test

class ReviewPanelExpansionPolicyTest {
    @Test
    fun panelExpandsUpwardWithoutCoveringProtectedBottomArea() {
        val maximum = ReviewPanelExpansionPolicy.maximumHeight(
            screenHeightPx = 2400,
            protectedBottomPx = 120,
            collapsedHeightPx = 700,
        )

        assertEquals(1520, maximum)
        assertEquals(
            1100,
            ReviewPanelExpansionPolicy.resizedHeight(700, 400, 700, maximum),
        )
        assertEquals(maximum, ReviewPanelExpansionPolicy.resizedHeight(700, 5000, 700, maximum))
        assertEquals(700, ReviewPanelExpansionPolicy.resizedHeight(1100, -5000, 700, maximum))
    }

    @Test
    fun upwardSwipeCanExpandThePanelAsSoonAsItAppears() {
        assertEquals(
            false,
            ReviewPanelExpansionPolicy.shouldStartExpansion(
                upwardDragPx = 7,
                currentHeightPx = 700,
                maximumHeightPx = 1520,
                touchSlopPx = 8,
            ),
        )
        assertEquals(
            true,
            ReviewPanelExpansionPolicy.shouldStartExpansion(
                upwardDragPx = 9,
                currentHeightPx = 700,
                maximumHeightPx = 1520,
                touchSlopPx = 8,
            ),
        )
        assertEquals(
            false,
            ReviewPanelExpansionPolicy.shouldStartExpansion(
                upwardDragPx = 200,
                currentHeightPx = 1520,
                maximumHeightPx = 1520,
                touchSlopPx = 8,
            ),
        )
    }
}
