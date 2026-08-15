package com.echolink.mobile

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A numeral the writer typed comes back as a numeral.
 *
 * "Imagine buying 80 packs" shipped as "eighty packs" and "sticking out 20
 * feet" as "twenty feet". Spelling a number out is a house style, not a
 * correction, and Proofread was not asked for one.
 *
 * The validator is deliberately NOT the place for this. It compares numbers by
 * value so that "three" and "3" are equal while a changed value stays a hard
 * failure, and a hard failure rejects the whole paragraph and every other
 * correction in it. So the value check keeps its job and the form is restored
 * afterwards.
 */
class WrittenNumeralRestoreTest {
    private fun restore(original: String, candidate: String) =
        WrittenNumeralRestore.apply(original, candidate)

    @Test
    fun aNumeralTheWriterTypedIsPutBack() {
        assertEquals(
            "Imagine buying 80 packs of hamburger.",
            restore(
                "Imagine buying 80 packs hamburger.",
                "Imagine buying eighty packs of hamburger.",
            ),
        )
        assertEquals(
            "its tail sticking out 20 feet.",
            restore(
                "tail sticking out 20 feet.",
                "its tail sticking out twenty feet.",
            ),
        )
    }

    @Test
    fun theHyphenatedFormIsRecognisedToo() {
        assertEquals(
            "about 25 people came",
            restore("about 25 people came", "about twenty-five people came"),
        )
    }

    @Test
    fun aNumeralThatSurvivedIsLeftAlone() {
        assertEquals(
            "Room 12 has 186 guests and a budget.",
            restore(
                "Room 12 has 186 guests and budget.",
                "Room 12 has 186 guests and a budget.",
            ),
        )
    }

    @Test
    fun aNumberTheWriterSpelledOutStaysSpelledOut() {
        // Nothing to restore it to. The writer chose the words.
        assertEquals(
            "he owns eighty packs",
            restore("he owns eighty packs", "he owns eighty packs"),
        )
    }

    @Test
    fun aNumberTheModelIntroducedIsLeftAlone() {
        // "three" is not in the writer's text as a numeral, so there is no
        // writer's form to restore and the rule must not invent one.
        assertEquals(
            "there were three reasons",
            restore("there were reasons", "there were three reasons"),
        )
    }

    @Test
    fun theRestOfTheCorrectionSurvives() {
        // The whole point of doing this here rather than in the validator: the
        // paragraph keeps every other repair it earned.
        assertEquals(
            "The dogs bite people and 80 packs arrived.",
            restore(
                "The dogs bites people and 80 packs arrived.",
                "The dogs bite people and eighty packs arrived.",
            ),
        )
    }
}
