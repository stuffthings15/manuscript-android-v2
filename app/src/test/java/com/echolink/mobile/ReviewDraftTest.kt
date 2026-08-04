package com.echolink.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReviewDraftTest {
    @Test
    fun keepsRawSpeechAndPresentsCleanedTextForReview() {
        val draft = ReviewDraft.fromTranscript(
            "um echo link echo link comma ready new line how are you question mark",
            mapOf("echo link" to "Echo-Link"),
        )

        assertEquals(
            "um echo link echo link comma ready new line how are you question mark",
            draft.rawText,
        )
        assertEquals("Echo-Link, ready\nHow are you?", draft.reviewedText)
        assertTrue(draft.changed)
    }
}
