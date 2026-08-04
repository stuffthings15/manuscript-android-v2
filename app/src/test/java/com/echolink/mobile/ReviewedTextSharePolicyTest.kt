package com.echolink.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReviewedTextSharePolicyTest {
    @Test
    fun rejectsBlankDrafts() {
        assertNull(ReviewedTextSharePolicy.accept(""))
        assertNull(ReviewedTextSharePolicy.accept(" \n\t "))
    }

    @Test
    fun preservesTheReviewedDraftExactly() {
        val reviewed = "  Keep my reviewed spacing.\nSecond line.  "

        assertEquals(reviewed, ReviewedTextSharePolicy.accept(reviewed))
    }
}
