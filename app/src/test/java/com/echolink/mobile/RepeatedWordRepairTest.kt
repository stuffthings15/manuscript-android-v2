package com.echolink.mobile

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A word typed twice is collapsed. A name is not.
 *
 * This rule deleted a pet's name. The writer wrote "or Kevin. Kevin is
 * actually pretty good dinosaur name"; the rule saw "Kevin. Kevin", struck the
 * second one out, and the capitalisation pass tidied up after it, so the app
 * shipped "or Kevin. Is actually a pretty good dinosaur name".
 *
 * Every name guard missed it, and none of them was at fault: they judge the
 * model's edits, and this is not a model edit. It runs in the review sweep, on
 * restored text, after the guards have finished. The rule had no test of its
 * own because it needed a Context to reach; that is why it is an object now.
 *
 * Found in the archive tree and ported here on 2026-08-15, because this tree
 * carried the same regex character for character and therefore the same
 * defect.
 */
class RepeatedWordRepairTest {
    private fun repair(text: String) = RepeatedWordRepair.apply(text)

    @Test
    fun aWordTypedTwiceIsCollapsed() {
        assertEquals("the dog", repair("the the dog"))
        assertEquals("a big dinosaur", repair("a big big dinosaur"))
    }

    @Test
    fun aStutterAcrossACommaIsCollapsed() {
        assertEquals("beyond the city, and why", repair("beyond the city, city, and why"))
    }

    @Test
    fun aRunOnStitchedBackTogetherIsStillCollapsed() {
        // The case the sentence-boundary rule was written for: the second copy
        // is itself followed by punctuation.
        //
        // The doubled punctuation it leaves behind is pre-existing behaviour,
        // asserted here as it actually is rather than as it ought to be. This
        // change is about not deleting names; tidying "., " is a separate
        // question and not one to answer silently inside it.
        assertEquals(
            "renting one room., and why",
            repair("renting one room. Room, and why"),
        )
    }

    @Test
    fun aNameOpeningTheNextSentenceIsNotAStutter() {
        assertEquals(
            "or Kevin. Kevin is actually pretty good",
            repair("or Kevin. Kevin is actually pretty good"),
        )
        assertEquals(
            "I called him Rex. Rex came running.",
            repair("I called him Rex. Rex came running."),
        )
        assertEquals(
            "we landed in Berlin. Berlin was cold.",
            repair("we landed in Berlin. Berlin was cold."),
        )
    }

    @Test
    fun anOrdinaryWordOpeningTheNextSentenceIsAlsoLeftAlone() {
        // Not a name, but still two sentences rather than one stutter. The
        // repair costs a real word either way, so the rule stays out of it.
        assertEquals(
            "he went home. Home was empty.",
            repair("he went home. Home was empty."),
        )
    }
}
