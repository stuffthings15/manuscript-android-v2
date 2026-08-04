package com.echolink.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ApprovedTextPolicyTest {
    @Test
    fun normalizesLineEndingsAndRemovesUnsafeControls() {
        val result = ApprovedTextPolicy.validate("  first\r\nsecond\u0000\tvalue  ")

        assertTrue(result.accepted)
        assertEquals("first\nsecond\tvalue", result.text)
    }

    @Test
    fun rejectsBlankText() {
        val result = ApprovedTextPolicy.validate(" \n\t ")

        assertFalse(result.accepted)
        assertEquals("Enter text before approving it.", result.error)
    }

    @Test
    fun rejectsOversizedText() {
        val result = ApprovedTextPolicy.validate("x".repeat(ApprovedTextPolicy.MAX_CHARACTERS + 1))

        assertFalse(result.accepted)
    }
}
