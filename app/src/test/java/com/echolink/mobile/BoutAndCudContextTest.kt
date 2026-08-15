package com.echolink.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * "bout" and "cud" are repaired where they are mistakes and kept where they
 * are words.
 *
 * Both sat in `NONSTANDARD_CORRECTIONS`, the speller's closed word-for-word
 * map, which is applied one token at a time and therefore cannot see what
 * comes before the word. So a boxing bout became "a about", and a cow chewing
 * its cud became "its could".
 *
 * They are the same defect as "peoples", found in the same map at the same
 * time. The archive tree hit "bout" seventeen times across its corpus before
 * anyone noticed, because every word in "learned much bout it" is spelled
 * correctly and no speller has anything to object to.
 *
 * What decides the meaning is the determiner. The noun wants one in front of
 * it; the preposition and the modal do not.
 *
 * Phrases sit mid-sentence because GrammarSafetyNet also capitalises the first
 * letter of the text it is given, which is pre-existing and nothing to do with
 * these rules.
 */
class BoutAndCudContextTest {
    private fun repair(text: String) = GrammarSafetyNet.repair(text)

    @Test
    fun boutIsRepairedWhereAboutWasMeant() {
        assertEquals("They learned much about it", repair("They learned much bout it"))
        assertEquals(
            "A reason to care about something",
            repair("A reason to care bout something"),
        )
        assertEquals("She had questions about the dog", repair("She had questions bout the dog"))
    }

    @Test
    fun aRealBoutSurvives() {
        assertEquals("He had a bout of flu", repair("He had a bout of flu"))
        assertEquals("The title bout is tonight", repair("The title bout is tonight"))
        assertEquals("Every bout he fought", repair("Every bout he fought"))
        assertEquals("It was a boxing bout in Leeds", repair("It was a boxing bout in Leeds"))
    }

    @Test
    fun cudIsRepairedWhereCouldWasMeant() {
        assertEquals("I cannot say what she could do", repair("I cannot say what she cud do"))
        assertEquals("They could not stay", repair("They cud not stay"))
    }

    @Test
    fun aRealCudSurvives() {
        assertEquals("The cow chews its cud all day", repair("The cow chews its cud all day"))
        assertEquals("A goat brings up the cud again", repair("A goat brings up the cud again"))
    }

    @Test
    fun neitherReturnsToTheContextFreeMap() {
        // If either goes back into NONSTANDARD_CORRECTIONS, the real readings
        // break again everywhere GrammarSafetyNet is not the layer doing the
        // work, and no behaviour test here would notice.
        val source = java.io.File(
            System.getProperty("echoLinkAndroidDir") ?: ".",
            "app/src/main/java/com/echolink/mobile/LocalCorrectionPipeline.kt",
        )
        if (!source.exists()) return
        val text = source.readText()
        assertFalse("\"bout\" must stay out of the context-free map", text.contains("\"bout\" to "))
        assertFalse("\"cud\" must stay out of the context-free map", text.contains("\"cud\" to "))
    }
}
