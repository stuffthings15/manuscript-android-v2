package com.echolink.mobile

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FullPageCorruptionRegressionTest {
    @Test
    fun validInflectedWordsAreNotOfferedToSymSpell() {
        val dictionary = setOf("rake", "pesticide", "shovel", "committee")

        assertTrue(SymSpellWordPolicy.isKnownForm("rakes", dictionary::contains))
        assertTrue(SymSpellWordPolicy.isKnownForm("pesticides", dictionary::contains))
        assertTrue(SymSpellWordPolicy.isKnownForm("shovels", dictionary::contains))
        assertTrue(SymSpellWordPolicy.isKnownForm("committees", dictionary::contains))
        assertTrue(SymSpellWordPolicy.isKnownForm("cannot", dictionary::contains))
        assertTrue(SymSpellWordPolicy.isKnownForm("neighborhood", dictionary::contains))
        assertTrue(SymSpellWordPolicy.isKnownForm("wag", dictionary::contains))
        assertFalse(SymSpellWordPolicy.isKnownForm("rtaes", dictionary::contains))
    }

    @Test
    fun dictionaryLoaderRemovesUtf8BomFromTheFirstWord() {
        assertTrue(SymSpellWordPolicy.normalizeDictionaryWord("\uFEFFthe") == "the")
    }

    @Test
    fun proofreadRejectsArticleToPronounCorruption() {
        val source = "The garden used the amount approved by the city."
        val corrupted = "They garden used they amount approved by they city."

        assertFalse(ProofreadPreservationPolicy.preservesWording(source, corrupted))
    }

    @Test
    fun fastLayerDoesNotRunTheLegacyPhraseRewriter() {
        val androidRoot = File(checkNotNull(System.getProperty("echoLinkAndroidDir")))
        val source = File(
            androidRoot,
            "app/src/main/java/com/echolink/mobile/LocalCorrectionPipeline.kt",
        ).readText()

        assertFalse(source.contains("LocalWritingAssistant.suggest(restored, WritingTone.PROOFREAD)"))
    }

    @Test
    fun reviewBlocksWaitForAiInsteadOfShowingRuleOnlyCorrections() {
        val androidRoot = File(checkNotNull(System.getProperty("echoLinkAndroidDir")))
        val main = File(
            androidRoot,
            "app/src/main/java/com/echolink/mobile/MainActivity.kt",
        ).readText()
        val service = File(
            androidRoot,
            "app/src/main/java/com/echolink/mobile/ManuscriptAccessibilityService.kt",
        ).readText()

        assertTrue(main.contains("PendingReviewPolicy.visible"))
        assertTrue(service.contains("PendingReviewPolicy.visible"))
    }

    @Test
    fun balancedModelIsNotReplacedByAWeakModelForModerateLatency() {
        assertFalse(Qwen3InferenceBenchmark(Qwen3Tier.BALANCED, 9_000L, true).requiresCompactTier)
        assertFalse(Qwen3InferenceBenchmark(Qwen3Tier.BALANCED, 9_000L, false).requiresCompactTier)
        assertTrue(Qwen3InferenceBenchmark(Qwen3Tier.BALANCED, 20_000L, true).requiresCompactTier)
    }

    @Test
    fun safetyNetRepairsCommonFullPageGrammarFamilies() {
        val source = """
            last monday the committee meet, but nobody were prepared. Maria had send an agenda however members says it was late.
            There was twelve volunteers, and each of them have a task. The first problem involve water.
            He didnt brought the receipt. This decision were reasonable.
            Everyone is suppose to arrive, they should bring gloves sunscreen and water.
            The tools includes six shovels and two of the shovels is damaged.
            The gate needs remain locked. Visitors signs a waiver, but the committee have not decided.
            This was inaccurate misleading and harmful. The committees credibility suffered.
            The changes is adopted. Saturdays work session starts at 8:00 a.m..
            Maria asked Jordan to help, meanwhile Jordan thought Priya had done it.
            Gloves, rakes and water were listed over and over again repeatedly.
            During May the garden used 18,450 gallons, which are nearly twice the approved amount.
            Shed B has six shovels, three rakes and one wheelbarrow.
            The event starts at 9:30 a.m but nobody sent a notice.
            By the end of the meeting no one knew who owned the task.
        """.trimIndent()

        val repaired = GrammarSafetyNet.repair(source)

        assertTrue(repaired.contains("Last Monday"))
        assertTrue(repaired.contains("committee met, but nobody was prepared"))
        assertTrue(repaired.contains("had sent an agenda; however, members say"))
        assertTrue(repaired.contains("There were twelve volunteers"))
        assertTrue(repaired.contains("each of them has a task"))
        assertTrue(repaired.contains("problem involves water"))
        assertTrue(repaired.contains("He didn't bring the receipt"))
        assertTrue(repaired.contains("decision was reasonable"))
        assertTrue(repaired.contains("is supposed to arrive. They should bring gloves, sunscreen, and water"))
        assertTrue(repaired.contains("tools include six shovels"))
        assertTrue(repaired.contains("shovels are damaged"))
        assertTrue(repaired.contains("needs to remain locked"))
        assertTrue(repaired.contains("Visitors sign a waiver"))
        assertTrue(repaired.contains("committee has not decided"))
        assertTrue(repaired.contains("inaccurate, misleading, and harmful"))
        assertTrue(repaired.contains("committee's credibility"))
        assertTrue(repaired.contains("changes are adopted"))
        assertTrue(repaired.contains("Saturday's work session"))
        assertTrue(repaired.contains("8:00 a.m."))
        assertFalse(repaired.contains("a.m.."))
        assertTrue(repaired.contains("; meanwhile, Jordan"))
        assertTrue(repaired.contains("Gloves, rakes, and water were listed repeatedly"))
        assertTrue(repaired.contains("18,450 gallons, which is nearly twice"))
        assertTrue(repaired.contains("six shovels, three rakes, and one wheelbarrow"))
        assertTrue(repaired.contains("9:30 a.m., but nobody"))
        assertTrue(repaired.contains("By the end of the meeting, no one"))
        assertTrue(
            GrammarSafetyNet.repair("The event starts at 9:30 a.m\u00A0but nobody sent a notice.")
                .contains("9:30 a.m., but nobody"),
        )
    }

    @Test
    fun safetyNetRepairsFrozenLibraryPageFamiliesWithoutDamagingPass() {
        val source = """
            On Thursday the library board meet to review the program however none of the reports was complete.
            Elaine had wrote it. There was twenty seven events, and each of the branches have submitted a file.
            Central Library claim success. The budget discussion were long and the invoices totals ${'$'}26,385.
            That choice were sensible. Saturdays event needs name tags pens and a phone.
            One of the projectors are missing a cable. The final concern involve safety.
            Every volunteer must pass a check, emergency exits needs remain clear, parents signs a form, and the board have not reviewed it.
            Tomas uploaded a unfinished form. The statement was inaccurate confusing and harmful.
            It should document each vote name one owner. The library may waste money, frustrated volunteers and damage the boards credibility.
            Next the board discussed Saturdays event. The equipment stored in Room 12 include four microphones.
            If the train is delayed the program starts later. The statement was inaccurate confusing and likely to fail.
            Elaine wrote it on July 8, 2026 but the report was late. There were twenty seven events.
            Members continued repeating the same explanation over and over again repeatedly. The author train was delayed.
        """.trimIndent()

        val repaired = GrammarSafetyNet.repair(source)

        assertTrue(repaired.contains("board met to review the program; however, none of the reports were complete"))
        assertTrue(repaired.contains("had written it"))
        assertTrue(repaired.contains("There were twenty-seven events"))
        assertTrue(repaired.contains("each of the branches has submitted"))
        assertTrue(repaired.contains("Central Library claims success"))
        assertTrue(repaired.contains("budget discussion was long"))
        assertTrue(repaired.contains("invoices total ${'$'}26,385"))
        assertTrue(repaired.contains("choice was sensible"))
        assertTrue(repaired.contains("Saturday's event"))
        assertTrue(repaired.contains("name tags, pens, and a phone"))
        assertTrue(repaired.contains("One of the projectors is missing"))
        assertTrue(repaired.contains("concern involves safety"))
        assertTrue(repaired.contains("must pass a check"))
        assertFalse(repaired.contains("must pa a check"))
        assertTrue(repaired.contains("emergency exits need to remain clear"))
        assertTrue(repaired.contains("parents sign a form"))
        assertTrue(repaired.contains("board has not reviewed"))
        assertTrue(repaired.contains("an unfinished form"))
        assertTrue(repaired.contains("inaccurate, confusing, and harmful"))
        assertTrue(repaired.contains("each vote, name one owner"))
        assertTrue(repaired.contains("waste money, frustrate volunteers, and damage the board's credibility"))
        assertTrue(repaired.contains("Next, the board discussed Saturday's event"))
        assertTrue(repaired.contains("equipment stored in Room 12 includes four microphones"))
        assertTrue(repaired.contains("If the train is delayed, the program"))
        assertTrue(repaired.contains("inaccurate, confusing, and likely to fail"))
        assertTrue(repaired.contains("July 8, 2026, but the report"))
        assertTrue(repaired.contains("twenty-seven events"))
        assertTrue(repaired.contains("continued repeating the same explanation."))
        assertFalse(repaired.contains("continued repeating the same explanation repeatedly"))
        assertTrue(repaired.contains("author's train"))
    }
}
