package com.echolink.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ToneMenuPolicyTest {
    @Test
    fun theMenuOffersSixPresetsRatherThanEveryTone() {
        // This asserted all twenty-four tones. Curtis asked for six: a strip
        // of twenty-four is one nobody reads to the end of. The other tones
        // still exist and still have prompts, they are simply not presets.
        val items = ToneMenuPolicy.items(WritingTone.FORMAL)

        assertEquals(6, items.size)
        assertEquals(ToneMenuPolicy.PRESETS.map { it.label }, items.map { it.label })
        assertEquals(listOf(WritingTone.FORMAL), items.filter { it.selected }.map { it.tone })
    }

    @Test
    fun aSavedToneOutsideThePresetsIsStillOfferedAndStillSelected() {
        // Curtis's saved tone is Professional, which is not a preset. Without
        // this the menu would show six chips with none of them selected, and
        // no way back to the tone the writing is being corrected in.
        val items = ToneMenuPolicy.items(WritingTone.PROFESSIONAL)

        assertEquals(7, items.size)
        assertTrue(items.any { it.tone == WritingTone.PROFESSIONAL && it.selected })
        assertEquals(1, items.count { it.selected })
    }

    @Test
    fun theFirstPresetIsTheOneTheProductIsFor() {
        assertEquals(WritingTone.PROOFREAD, ToneMenuPolicy.PRESETS.first())
        assertEquals(ToneMenuPolicy.PRESETS.size, ToneMenuPolicy.PRESETS.distinct().size)
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
