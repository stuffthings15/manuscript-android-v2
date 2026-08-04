package com.echolink.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InlineEditDiffTest {
    @Test
    fun applicationDiffUsesStableRangesForRepeatedSentencesAndClassifiesChanges() {
        val original = "Dogs is loyal. Dogs is loyal."
        val replacement = "Dogs are loyal. Dogs are loyal."

        val changes = InlineEditDiff.changes(
            original,
            replacement,
            WritingTone.PROOFREAD,
        )

        assertEquals(2, changes.size)
        assertEquals(listOf(5, 20), changes.map { it.originalStart })
        assertTrue(changes.all { it.originalText == "is" })
        assertTrue(changes.all { it.replacementText == "are" })
        assertTrue(changes.all { it.category == EditCategory.AGREEMENT })
        assertTrue(changes.all { it.note.isNotBlank() })
    }

    @Test
    fun acceptingOneApplicationGeneratedChangeDoesNotTouchRepeatedMatch() {
        val original = "Dogs is loyal. Dogs is loyal."
        val replacement = "Dogs are loyal. Dogs are loyal."
        val changes = InlineEditDiff.changes(original, replacement, WritingTone.PROOFREAD)

        assertEquals(
            "Dogs are loyal. Dogs is loyal.",
            InlineEditDiff.applyDecisions(original, changes, setOf(changes.first().id)),
        )
    }

    @Test
    fun keepsRemovedTextBeforeHighlightedReplacement() {
        val segments = InlineEditDiff.segments("I recieve teh file.", "I receive the file.")
        val removed = segments.filter { it.kind == InlineEditKind.REMOVED }.joinToString("") { it.text }
        val inserted = segments.filter { it.kind == InlineEditKind.INSERTED }.joinToString("") { it.text }

        assertEquals("recieveteh", removed.filterNot(Char::isWhitespace))
        assertEquals("receivethe", inserted.filterNot(Char::isWhitespace))
        assertTrue(segments.indexOfFirst { it.kind == InlineEditKind.REMOVED } <
            segments.indexOfFirst { it.kind == InlineEditKind.INSERTED })
    }

    @Test
    fun explainsProofreadingAndToneRewrites() {
        assertEquals(
            "Spelling and grammar corrected.",
            InlineEditDiff.note("i dont recieve teh file.", "I don't receive the file.", WritingTone.PROOFREAD),
        )
        assertEquals(
            "Rewritten in the Friendly tone.",
            InlineEditDiff.note("We cannot wait.", "We can't wait.", WritingTone.FRIENDLY),
        )
    }
}
