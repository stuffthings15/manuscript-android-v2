package com.echolink.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MasterPlanToneCoverageTest {
    private val requiredLabels = listOf(
        "Proofread",
        "Professional",
        "Academic",
        "Formal",
        "Friendly",
        "Conversational",
        "Concise",
        "Detailed",
        "Persuasive",
        "Confident",
        "Empathetic",
        "Casual",
        "Technical",
        "Simplified",
        "High-school level",
        "College level",
        "Diplomatic",
        "Caveman",
    )

    @Test
    fun everyMasterPlanModeIsExposedAndHasItsOwnPrompt() {
        val labels = WritingTone.entries.map(WritingTone::label)
        val missing = requiredLabels.filterNot(labels::contains)
        assertTrue("Missing master-plan writing modes: $missing", missing.isEmpty())

        val instructions = requiredLabels.map { label ->
            val tone = WritingTone.fromLabel(label)
            assertEquals("Tone label must round-trip", label, tone.label)
            TonePromptLibrary.spec(tone).instruction
        }
        assertEquals(
            "Every required mode needs a distinct prompt instruction",
            requiredLabels.size,
            instructions.distinct().size,
        )
    }

    @Test
    fun everyMasterPlanStyleCorrectsErrorsAndChangesTheStyle() {
        val source = "we was late becuase the report have three error ."
        val proofread = LocalWritingAssistant.suggest(source, WritingTone.PROOFREAD)
        val failures = requiredLabels
            .filterNot { it == "Proofread" }
            .mapNotNull { label ->
                val tone = WritingTone.fromLabel(label)
                val output = LocalWritingAssistant.suggest(source, tone)
                val correct = !Regex(
                    "(?i)\\b(?:we was|becuase|report have|three error)\\b|\\s+[.]",
                ).containsMatchIn(output)
                val changed = output != proofread
                if (correct && changed) null else "$label: $output"
            }
        assertTrue(
            "Required styles must correct objective errors and visibly transform text: " +
                failures.joinToString(" | "),
            failures.isEmpty(),
        )
    }
}
