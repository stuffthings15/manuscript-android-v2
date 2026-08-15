package com.echolink.mobile

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * "peoples" is repaired where it is a mistake and kept where it is a word.
 *
 * It sat in `NONSTANDARD_CORRECTIONS`, the speller's closed word-for-word map,
 * which is applied one token at a time and therefore cannot see the word in
 * front of it. So it rewrote "indigenous peoples" every single time, which is
 * a correct phrase in an essay about language or history.
 *
 * The repair is not to delete the rule - "peoples buy cameras" is still wrong.
 * It is to move the rule somewhere it can see context, which is
 * `GrammarSafetyNet`, and exclude the words that make the real sense.
 */
class PeoplesContextTest {
    private fun repair(text: String) = GrammarSafetyNet.repair(text)

    // Phrases sit mid-sentence on purpose. GrammarSafetyNet also capitalises
    // the first letter of the text it is given, which is pre-existing and
    // nothing to do with this rule; testing at the start of a string would be
    // asserting that behaviour by accident.

    @Test
    fun peoplesIsRepairedWhereItIsAMistake() {
        assertEquals("Right now people buy cameras", repair("Right now peoples buy cameras"))
        assertEquals("It would scare delivery people", repair("It would scare delivery peoples"))
        assertEquals("Some people never learn", repair("Some peoples never learn"))
    }

    @Test
    fun peoplesSurvivesWhereItMeansNations() {
        assertEquals("The indigenous peoples", repair("The indigenous peoples"))
        assertEquals("It lists the peoples of Europe", repair("It lists the peoples of Europe"))
        assertEquals("Where many peoples share it", repair("Where many peoples share it"))
        assertEquals("The native peoples of the coast", repair("The native peoples of the coast"))
    }

    @Test
    fun theSpellerNoLongerRewritesItWithoutLooking() {
        // The old entry lived in the speller's word map. If it comes back,
        // "indigenous peoples" breaks again wherever GrammarSafetyNet is not
        // the layer doing the work, so the map must stay clear of it.
        val source = java.io.File(
            System.getProperty("echoLinkAndroidDir") ?: ".",
            "app/src/main/java/com/echolink/mobile/LocalCorrectionPipeline.kt",
        )
        if (!source.exists()) return
        assertEquals(
            "\"peoples\" must not return to the context-free correction map",
            false,
            source.readText().contains("\"peoples\" to \"people\""),
        )
    }
}
