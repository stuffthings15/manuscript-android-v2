package com.echolink.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalWritingAssistantTest {
    @Test
    fun proofreadRepairsTheMalformedSentenceFoundByRealQwenEmulatorTesting() {
        assertEquals(
            "I have three errors. It doesn't make sense, and the punctuation is bad.",
            LocalWritingAssistant.suggest(
                "i has three error it dont make sense and punctuation is bad",
                WritingTone.PROOFREAD,
            ),
        )
    }

    @Test fun exposesEveryRequiredTone() {
        assertEquals(
            listOf(
                "Proofread", "Improve", "Rephrase", "Shorten", "Friendly", "Formal",
                "Professional", "Academic", "Conversational", "Concise", "Detailed",
                "Persuasive", "Confident", "Diplomatic", "Empathetic", "Casual",
                "Technical", "Simplified", "High-school level", "College level",
                "Cooperative", "Assertive", "Caveman",
            ),
            WritingTone.entries.map { it.label },
        )
    }

    @Test fun groupsCompleteSentencesAndAdvancesCounter() {
        val source = "this is teh first sentence. this are second! do this work? fourth sentence here."
        val suggestions = LocalWritingAssistant.batches(source, WritingTone.PROOFREAD, 3)
        assertEquals(2, suggestions.size)
        assertEquals(1, suggestions[0].index)
        assertEquals(2, suggestions[1].index)
        assertEquals(2, suggestions[1].total)
    }

    @Test fun replacementChangesOnlyTheExactReviewedBlock() {
        val source = "this is first sentence. second sentence here. Third stays."
        val suggestion = LocalWritingAssistant.batches(source, WritingTone.PROOFREAD, 2).first()
        val replaced = LocalWritingAssistant.replaceExact(source, suggestion)
        assertTrue(replaced.endsWith("Third stays."))
        assertTrue(replaced.startsWith("This is the first sentence."))
    }

    @Test fun completesAThreeBlockReplaceCycleWithoutLosingPosition() {
        val source = listOf(
            "this is teh first sentence.",
            "these is second sentence.",
            "i dont recieve teh file.",
            "this are seperate problem.",
            "final sentence need help.",
            "i cant fix alot of issue.",
        ).joinToString(" ")
        val suggestions = LocalWritingAssistant.batches(source, WritingTone.PROOFREAD, 2)

        assertEquals(listOf(1, 2, 3), suggestions.map { it.index })
        assertTrue(suggestions.all { it.total == 3 })

        val corrected = suggestions.fold(source) { text, suggestion ->
            LocalWritingAssistant.replaceExact(text, suggestion)
        }
        assertEquals(
            "This is the first sentence. This is the second sentence. " +
                "I don't receive the file. This is a separate problem. " +
                "Final sentence needs help. I can't fix a lot of issues.",
            corrected,
        )
    }

    @Test fun selectedToneRefreshesCurrentBlockAndCarriesToTheNextBlock() {
        val suggestions = LocalWritingAssistant.batches(
            "We cannot delay. We cannot stop.",
            WritingTone.PROOFREAD,
            1,
        )
        val selectedTone = WritingTone.FRIENDLY
        val refreshed = suggestions.map { suggestion ->
            suggestion.copy(
                replacement = LocalWritingAssistant.suggest(suggestion.original, selectedTone),
                tone = selectedTone,
            )
        }

        assertEquals(listOf(WritingTone.FRIENDLY, WritingTone.FRIENDLY), refreshed.map { it.tone })
        assertEquals("We can't delay.", refreshed[0].replacement)
        assertEquals("We can't stop.", refreshed[1].replacement)
    }

    @Test fun supportsOnlyOneToThreeSentencesPerSuggestion() {
        for (size in 1..3) {
            assertEquals(
                1,
                LocalWritingAssistant.batches("One. Two. Three.", WritingTone.PROOFREAD, size)
                    .first().index,
            )
        }
        assertFails { LocalWritingAssistant.batches("One.", WritingTone.PROOFREAD, 0) }
        assertFails { LocalWritingAssistant.batches("One.", WritingTone.PROOFREAD, 4) }
    }

    @Test fun proofreadFixesCommonSpellingAndAgreement() {
        val result = LocalWritingAssistant.suggest(
            "these is teh files i recieve", WritingTone.PROOFREAD,
        )
        assertEquals("These are the files I receive.", result)
        assertEquals(
            "This is the second sentence. I don't receive the file.",
            LocalWritingAssistant.suggest(
                "these is second sentence. i dont recieve teh file", WritingTone.PROOFREAD,
            ),
        )
        assertEquals(
            "This is the first sentence. This is the second sentence with bad grammar. I don't receive the file.",
            LocalWritingAssistant.suggest(
                "this is teh first sentence. these is second sentence with bad grammar. i dont recieve teh file",
                WritingTone.PROOFREAD,
            ),
        )
        assertEquals(
            "This is a separate problem. Final sentence needs help. I can't fix a lot of issues.",
            LocalWritingAssistant.suggest(
                "this are seperate problem. final sentence need help. i cant fix alot of issue",
                WritingTone.PROOFREAD,
            ),
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun refusesAmbiguousReplacement() {
        val suggestion = WritingSuggestion("Same.", "Fixed.", WritingTone.PROOFREAD, 1, 2)
        LocalWritingAssistant.replaceExact("Same. Same.", suggestion)
    }

    private fun assertFails(block: () -> Unit) {
        try {
            block()
            throw AssertionError("Expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
            // Expected.
        }
    }
}
