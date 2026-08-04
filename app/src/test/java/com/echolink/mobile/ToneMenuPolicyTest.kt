package com.echolink.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ToneMenuPolicyTest {
    @Test
    fun swipeMenuShowsEveryToneInWritingToneOrder() {
        val items = ToneMenuPolicy.items(WritingTone.FORMAL)

        assertEquals(WritingTone.entries.map { it.label }, items.map { it.label })
        assertEquals(listOf(WritingTone.FORMAL), items.filter { it.selected }.map { it.tone })
    }

    @Test
    fun changingToneRegeneratesOnlyWhenAnOpenReviewNeedsANewTone() {
        assertTrue(
            ToneMenuPolicy.shouldRegenerate(
                current = WritingTone.PROOFREAD,
                next = WritingTone.FRIENDLY,
                reviewOpen = true,
            ),
        )
        assertFalse(
            ToneMenuPolicy.shouldRegenerate(
                current = WritingTone.FRIENDLY,
                next = WritingTone.FRIENDLY,
                reviewOpen = true,
            ),
        )
        assertFalse(
            ToneMenuPolicy.shouldRegenerate(
                current = WritingTone.PROOFREAD,
                next = WritingTone.FRIENDLY,
                reviewOpen = false,
            ),
        )
    }
}
