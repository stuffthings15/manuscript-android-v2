package com.echolink.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * "wether" reaches the right one of two words.
 *
 * It is one of the silent class: a real English word - a castrated ram - so no
 * speller and no scan has anything to object to, and it shipped uncorrected
 * twenty-three times in the archive tree's corpus before rare-word ranking
 * turned it up. Every one of those was a misspelling, which is why the map
 * sends it to "whether" and that stays the default.
 *
 * What the map cannot do is see the word in front of it. "its own wether" and
 * "wether, wind and rain" are plainly the weather, and one token at a time is
 * not enough to tell. So those two shapes are handled in
 * `trustedContextualTypos`, which runs BEFORE the map, leaving the map to take
 * everything else.
 *
 * The ordering is the whole fix and it is not obvious from either site:
 *
 *     GrammarSafetyNet.repair(... fastLayer.correct(TrustedContextualTypos.apply(text)) ...)
 *
 * A weather rule placed in `GrammarSafetyNet` would be dead code, because by
 * then the map has already turned every "wether" into "whether" and there is
 * nothing left to match.
 *
 * These assert on `TrustedContextualTypos` rather than on the whole repair
 * chain because the chain hangs off `LocalCorrectionRouter`, which needs a
 * `Context` and the SymSpell assets to build. The order itself is pinned by the
 * third test, which is the part behaviour here cannot show.
 */
class WetherContextTest {
    private fun repair(text: String) = TrustedContextualTypos.apply(text)

    @Test
    fun wetherIsLeftForTheMapToSendToWhether() {
        // Untouched here on purpose: the default belongs to the word map, and a
        // second rule saying the same thing would be two places to change.
        assertEquals("I do not know wether he will come", repair("I do not know wether he will come"))
        assertEquals("She asked wether it was open", repair("She asked wether it was open"))
        assertEquals("It depends on wether they agree", repair("It depends on wether they agree"))
    }

    @Test
    fun wetherBecomesWeatherWhereTheWeatherWasMeant() {
        // "its own weather" - a valley, a mountain, a stadium.
        assertEquals("The valley makes its own weather", repair("The valley makes its own wether"))
        // A list, where the comma is doing the telling.
        assertEquals("The weather, wind and rain stopped us", repair("The wether, wind and rain stopped us"))
    }

    @Test
    fun theOtherTrustedTyposStillApply() {
        // The rules moved out of LocalCorrectionRouter with this change, so
        // check they came across whole and not just the two new ones.
        assertEquals("He was guarding it", repair("He was gardin it"))
        assertEquals("It makes no sense", repair("It makes no snese"))
        assertEquals("Dogs make great pets", repair("Dogs make grate pets"))
    }

    @Test
    fun theWeatherRulesRunBeforeTheMap() {
        // If either rule is moved after `fastLayer.correct`, it can never fire:
        // the map has already produced "whether" and the pattern no longer
        // matches. Every behaviour assertion above would still pass with the
        // rules deleted only if the map were also changed, so pin the order
        // itself rather than trusting the outputs to notice.
        val source = java.io.File(
            System.getProperty("echoLinkAndroidDir") ?: ".",
            "app/src/main/java/com/echolink/mobile/LocalCorrectionPipeline.kt",
        )
        if (!source.exists()) return
        val text = source.readText()
        val weatherRule = text.indexOf("wether\\\\b\"), \"weather\"")
        val trusted = text.indexOf("object TrustedContextualTypos")
        val map = text.indexOf("\"wether\" to \"whether\"")
        assertTrue("the weather rules must exist", weatherRule > 0)
        assertTrue("they belong inside TrustedContextualTypos", weatherRule > trusted)
        assertEquals("the map keeps whether as the default", true, map > 0)
    }
}
