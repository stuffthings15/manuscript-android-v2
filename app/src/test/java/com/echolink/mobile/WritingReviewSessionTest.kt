package com.echolink.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class WritingReviewSessionTest {
    @Test
    fun individualChangesCanBeAcceptedOrRejectedWithoutTouchingTheOthers() {
        val original = "Dogs is loyal. Dogs is loyal."
        val session = requireNotNull(WritingReviewSession.createParagraphs(original))
        assertTrue(
            session.setModelReplacement(
                index = 1,
                tone = WritingTone.PROOFREAD,
                replacement = "Dogs are loyal. Dogs are loyal.",
            ),
        )

        val firstChanges = session.currentChanges(WritingTone.PROOFREAD)
        assertEquals(2, firstChanges.size)
        val afterAccept = session.acceptChange(
            original,
            WritingTone.PROOFREAD,
            firstChanges.first().id,
        )

        assertEquals("Dogs are loyal. Dogs is loyal.", afterAccept)
        val remaining = session.currentChanges(WritingTone.PROOFREAD)
        assertEquals(1, remaining.size)
        val afterReject = session.rejectChange(
            afterAccept,
            WritingTone.PROOFREAD,
            remaining.single().id,
        )

        assertEquals(afterAccept, afterReject)
        assertTrue(session.complete)
    }

    @Test
    fun paragraphModeKeepsWholeParagraphsAndDoesNotTrustDamagedPunctuation() {
        val source =
            "this first sentence is broke it got no stop and another thought follows\n\n" +
                "second paragraph has bad punctuation,, but remains one review block"
        val session = requireNotNull(WritingReviewSession.createParagraphs(source))

        assertEquals(2, session.total)
        assertEquals(
            "this first sentence is broke it got no stop and another thought follows",
            session.current(WritingTone.PROOFREAD)?.original,
        )
        session.dismissCurrent()
        assertEquals(
            "second paragraph has bad punctuation,, but remains one review block",
            session.current(WritingTone.PROOFREAD)?.original,
        )
    }

    @Test
    fun acceptAllUndoAndRestoreOriginalAreRangeSafe() {
        val source = "teh first paragraph.\n\nteh second paragraph."
        val session = requireNotNull(WritingReviewSession.createParagraphs(source))
        session.pending(WritingTone.PROOFREAD).forEach { block ->
            assertTrue(
                session.setModelReplacement(
                    block.index,
                    WritingTone.PROOFREAD,
                    block.original.replace("teh", "The"),
                ),
            )
        }

        val accepted = session.acceptAll(source, WritingTone.PROOFREAD)
        assertEquals("The first paragraph.\n\nThe second paragraph.", accepted)
        assertTrue(session.complete)

        val undone = session.undo(accepted)
        assertEquals(source, undone)
        assertFalse(session.complete)

        session.select(0)
        session.setModelReplacement(1, WritingTone.PROOFREAD, "The first paragraph.")
        val partlyAccepted = session.replaceCurrent(undone, WritingTone.PROOFREAD)
        assertEquals("The first paragraph.\n\nteh second paragraph.", partlyAccepted)
        assertEquals(source, session.restoreOriginal(partlyAccepted))
    }

    @Test
    fun pendingRuleCorrectionStaysHiddenUntilAiFinishes() {
        val block = WritingReviewBlock(
            start = 0,
            end = 20,
            original = "Dogs wag their tail.",
            replacement = "Dogs was their tail.",
            tone = WritingTone.PROOFREAD,
            index = 1,
            total = 1,
        )

        assertEquals(block.original, PendingReviewPolicy.visible(block, aiReady = false).replacement)
        assertEquals(block.replacement, PendingReviewPolicy.visible(block, aiReady = true).replacement)
    }

    @Test
    fun reviewCannotReplaceTextBeforeAiFinishes() {
        val source = "dogs is happy."
        val session = checkNotNull(WritingReviewSession.create(source))
        val pending = checkNotNull(session.current(WritingTone.PROOFREAD))

        assertEquals(source, pending.replacement)
        assertThrows(IllegalStateException::class.java) {
            session.replaceCurrent(source, WritingTone.PROOFREAD)
        }
    }

    @Test
    fun threeSentenceBlocksNeverCrossParagraphBoundaries() {
        val source = "One. Two.\n\nThree. Four. Five."
        val session = requireNotNull(
            WritingReviewSession.create(
                source = source,
                sentencesPerBlock = 3,
            ),
        )

        assertEquals("One. Two.", session.current(WritingTone.PROOFREAD)?.original)
        session.dismissCurrent()
        assertEquals("Three. Four. Five.", session.current(WritingTone.PROOFREAD)?.original)
    }

    @Test
    fun replacesThreeSentenceBlocksAndAdvancesTheCounterToCompletion() {
        val source = listOf(
            "this is teh first sentence.",
            "these is second sentence.",
            "i dont recieve teh file.",
            "this are seperate problem.",
            "final sentence need help.",
            "i cant fix alot of issue.",
            "this is teh last sentence.",
        ).joinToString(" ")
        val session = checkNotNull(WritingReviewSession.create(source, sentencesPerBlock = 3))

        assertEquals(3, session.total)
        var text = source
        for (expectedIndex in 1..3) {
            assertEquals(expectedIndex, session.current(WritingTone.PROOFREAD)?.index)
            installFixtureAiCorrection(session, WritingTone.PROOFREAD)
            text = session.replaceCurrent(text, WritingTone.PROOFREAD)
        }

        assertTrue(session.complete)
        assertEquals(null, session.current(WritingTone.PROOFREAD))
        assertFalse(text.contains("teh", ignoreCase = true))
        assertFalse(text.contains("recieve", ignoreCase = true))
        assertFalse(text.contains("seperate", ignoreCase = true))
    }

    @Test
    fun repeatedSentencesRemainSafelyAnchoredByRange() {
        val source = "i dont recieve teh file. i dont recieve teh file."
        val session = checkNotNull(WritingReviewSession.create(source, sentencesPerBlock = 1))

        installFixtureAiCorrection(session, WritingTone.PROOFREAD)
        val afterFirst = session.replaceCurrent(source, WritingTone.PROOFREAD)
        assertEquals(2, session.current(WritingTone.PROOFREAD)?.index)
        installFixtureAiCorrection(session, WritingTone.PROOFREAD)
        val afterSecond = session.replaceCurrent(afterFirst, WritingTone.PROOFREAD)

        assertEquals(
            "I don't receive the file. I don't receive the file.",
            afterSecond,
        )
    }

    @Test
    fun selectionLimitsTheReviewWithoutChangingOutsideText() {
        val source = "Keep this. i dont recieve teh file. this are seperate problem. Keep that."
        val selectionStart = source.indexOf("i dont")
        val selectionEnd = source.indexOf(" Keep that")
        val session = checkNotNull(
            WritingReviewSession.create(source, selectionStart, selectionEnd, sentencesPerBlock = 2),
        )

        installFixtureAiCorrection(session, WritingTone.PROOFREAD)
        val corrected = session.replaceCurrent(source, WritingTone.PROOFREAD)

        assertTrue(corrected.startsWith("Keep this. "))
        assertTrue(corrected.endsWith(" Keep that."))
        assertTrue(corrected.contains("I don't receive the file. This is a separate problem."))
    }

    @Test
    fun changingToneImmediatelyRefreshesCurrentAndCarriesForward() {
        val source = "We cannot delay. We cannot stop."
        val session = checkNotNull(WritingReviewSession.create(source, sentencesPerBlock = 1))

        installFixtureAiCorrection(session, WritingTone.FRIENDLY)
        installFixtureAiCorrection(session, WritingTone.FORMAL)
        assertEquals("We can't delay.", session.current(WritingTone.FRIENDLY)?.replacement)
        assertEquals("We are unable to delay.", session.current(WritingTone.FORMAL)?.replacement)
        val updated = session.replaceCurrent(source, WritingTone.FRIENDLY)
        installFixtureAiCorrection(session, WritingTone.FRIENDLY)
        val next = checkNotNull(session.current(WritingTone.FRIENDLY))

        assertEquals(WritingTone.FRIENDLY, next.tone)
        assertEquals("We can't stop.", next.replacement)
        assertEquals("We can't delay. We cannot stop.", updated)
    }

    @Test
    fun dismissAdvancesWithoutChangingText() {
        val session = checkNotNull(
            WritingReviewSession.create("First sentence. Second sentence.", sentencesPerBlock = 1),
        )

        session.dismissCurrent()

        assertEquals(2, session.current(WritingTone.PROOFREAD)?.index)
    }

    @Test
    fun pendingCorrectionsCanBeTappedOutOfOrderAndStillCompleteSafely() {
        val source = "teh first sentence. teh second sentence. teh third sentence."
        val session = checkNotNull(WritingReviewSession.create(source, sentencesPerBlock = 1))

        assertEquals(listOf(1, 2, 3), session.pending(WritingTone.PROOFREAD).map { it.index })
        assertTrue(session.select(2))
        installFixtureAiCorrection(session, WritingTone.PROOFREAD)
        val afterThird = session.replaceCurrent(source, WritingTone.PROOFREAD)

        assertEquals(listOf(1, 2), session.pending(WritingTone.PROOFREAD).map { it.index })
        assertEquals(1, session.current(WritingTone.PROOFREAD)?.index)
        installFixtureAiCorrection(session, WritingTone.PROOFREAD)
        val afterFirst = session.replaceCurrent(afterThird, WritingTone.PROOFREAD)
        installFixtureAiCorrection(session, WritingTone.PROOFREAD)
        val afterSecond = session.replaceCurrent(afterFirst, WritingTone.PROOFREAD)

        assertTrue(session.complete)
        assertEquals("The first sentence. The second sentence. The third sentence.", afterSecond)
    }

    private fun installFixtureAiCorrection(
        session: WritingReviewSession,
        tone: WritingTone,
    ) {
        val block = checkNotNull(session.current(tone))
        assertTrue(
            session.setModelReplacement(
                block.index,
                tone,
                LocalWritingAssistant.suggest(block.original, tone),
            ),
        )
    }
}
