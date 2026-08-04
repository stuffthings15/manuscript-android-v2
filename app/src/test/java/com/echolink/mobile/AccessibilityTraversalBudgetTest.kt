package com.echolink.mobile

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AccessibilityTraversalBudgetTest {
    @Test
    fun stopsTraversalAfterTheNodeLimit() {
        val budget = AccessibilityTraversalBudget(maxDepth = 32, maxNodes = 3)

        assertTrue(budget.tryVisit(depth = 0))
        assertTrue(budget.tryVisit(depth = 1))
        assertTrue(budget.tryVisit(depth = 2))
        assertFalse(budget.tryVisit(depth = 3))
    }

    @Test
    fun stopsTraversalPastTheDepthLimit() {
        val budget = AccessibilityTraversalBudget(maxDepth = 2, maxNodes = 20)

        assertTrue(budget.tryVisit(depth = 0))
        assertTrue(budget.tryVisit(depth = 2))
        assertFalse(budget.tryVisit(depth = 3))
        assertFalse(budget.tryVisit(depth = -1))
    }
}
