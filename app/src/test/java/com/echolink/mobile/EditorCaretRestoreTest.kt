package com.echolink.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The manual's rule that a correction must not move the writer's cursor. */
class EditorCaretRestoreTest {
    @Test
    fun aCorrectionAfterTheCursorLeavesItWhereItWas() {
        val old = "We was going. The dog run fast."
        val new = "We was going. The dog runs fast."
        assertEquals(5, EditorCaretRestore.offsetAfter(old, new, 5))
    }

    @Test
    fun aCorrectionBeforeTheCursorCarriesItAlong() {
        // "was" becomes "were": one character longer, so a caret sitting after
        // it must move one character to stay on the same word.
        val old = "We was going home."
        val new = "We were going home."
        val caret = old.indexOf("home")
        assertEquals(new.indexOf("home"), EditorCaretRestore.offsetAfter(old, new, caret))
    }

    @Test
    fun aShorterCorrectionPullsTheCursorBack() {
        val old = "They are is going home."
        val new = "They are going home."
        val caret = old.indexOf("home")
        assertEquals(new.indexOf("home"), EditorCaretRestore.offsetAfter(old, new, caret))
    }

    @Test
    fun theResultIsAlwaysInsideTheNewText() {
        val old = "A long document that loses most of its length."
        val new = "Short."
        val offset = EditorCaretRestore.offsetAfter(old, new, old.length)
        assertTrue("offset=$offset", offset in 0..new.length)
    }

    @Test
    fun aCursorAtTheStartStaysAtTheStart() {
        assertEquals(0, EditorCaretRestore.offsetAfter("we was", "we were", 0))
        // A negative offset is what an EditText reports when there is no
        // selection at all, and it must not become a crash or a stray caret.
        assertEquals(0, EditorCaretRestore.offsetAfter("we was", "we were", -1))
    }

    @Test
    fun aCursorPastTheOldTextIsClamped() {
        val offset = EditorCaretRestore.offsetAfter("short", "shorter", 999)
        assertTrue("offset=$offset", offset in 0.."shorter".length)
    }

    @Test
    fun unchangedTextDoesNotMoveTheCursorAtAll() {
        val text = "Nothing in this sentence changed."
        assertEquals(10, EditorCaretRestore.offsetAfter(text, text, 10))
    }
}
