package com.echolink.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReviewTargetLossDeadlineTest {
    @Test
    fun oneHundredChurnEventsCannotExtendTheExactTargetsEightHundredMillisecondDeadline() {
        val deadline = ReviewTargetLossDeadline(graceMillis = 800L)
        val retained = ReviewTargetId("retained-editor")

        assertEquals(800L, deadline.arm(retained, nowMillis = 0L))
        repeat(100) { index ->
            val now = (index + 1L) * 7L
            assertEquals(800L - now, deadline.arm(retained, nowMillis = now))
            assertFalse(deadline.consumeIfDue(retained, nowMillis = now))
        }

        assertTrue(deadline.consumeIfDue(retained, nowMillis = 800L))
        assertFalse(deadline.consumeIfDue(retained, nowMillis = 801L))
    }

    @Test
    fun aDifferentRetainedTargetGetsItsOwnDeadlineAndCancellationDropsOwnership() {
        val deadline = ReviewTargetLossDeadline(graceMillis = 800L)
        val first = ReviewTargetId("first")
        val second = ReviewTargetId("second")

        deadline.arm(first, nowMillis = 10L)
        assertEquals(800L, deadline.arm(second, nowMillis = 500L))
        assertFalse(deadline.consumeIfDue(first, nowMillis = 810L))

        deadline.cancel()
        assertFalse(deadline.consumeIfDue(second, nowMillis = 1_300L))
    }
}
