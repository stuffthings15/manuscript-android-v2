package com.echolink.mobile

import java.util.Locale
import org.junit.Assert.assertTrue
import org.junit.Test

/** Generic quality regressions derived from audit findings, not from a sealed corpus. */
class ToneQualityRegressionTest {
    private val constructiveTones = WritingTone.entries.filter { it != WritingTone.PROOFREAD }

    @Test
    fun questionsRemainQuestionsOrBecomeProperlyPunctuatedImperatives() {
        val source = "Could you send the revised agenda when you have a chance?"
        val failures = mutableListOf<String>()
        constructiveTones.forEach { tone ->
            val output = LocalWritingAssistant.suggest(source, tone).trim()
            val startsAsQuestion = Regex(
                "(?i)^(?:could|can|would|will|may|might|should)\\s+(?:you|we)\\b",
            ).containsMatchIn(output)
            val startsAsImperative = Regex(
                "(?i)^(?:please|kindly|send|let's|let us|we should|we need to)\\b",
            ).containsMatchIn(output)
            val malformedTerminal = Regex("(?:[.;:]\\?|[!?]{2,})$").containsMatchIn(output)
            val valid = !malformedTerminal && when {
                output.endsWith('?') -> startsAsQuestion
                output.endsWith('.') || output.endsWith('!') -> startsAsImperative
                else -> false
            }
            if (!valid) failures += "${tone.label}: $output"
        }
        assertTrue("Malformed request rewrites: ${failures.joinToString(" | ")}", failures.isEmpty())
    }

    @Test
    fun proofreadRepairsSentenceFragmentsAndPunctuation() {
        val source = "The supplier's explanation. Is incomplete because the dates conflict , and the final paragraph is unclear ."
        val output = LocalWritingAssistant.suggest(source, WritingTone.PROOFREAD)
        val fragment = Regex("(?i)[.!?]\\s+(?:is|are|was|were|has|have)\\s+")
            .containsMatchIn(output)
        val spaceBeforePunctuation = Regex("\\s+[,.;:!?]").containsMatchIn(output)
        val joinedSubject = Regex("(?i)the supplier's explanation\\s+is incomplete")
            .containsMatchIn(output)
        assertTrue(
            "Proofread left malformed punctuation or a sentence fragment: $output",
            !fragment && !spaceBeforePunctuation && joinedSubject && output.trim().endsWith('.'),
        )
    }

    @Test
    fun proofreadAddsRequiredArticlesAndStyleTonesRetainArticles() {
        val missingArticles = "Please place contract on table beside entrance."
        val proofread = LocalWritingAssistant.suggest(missingArticles, WritingTone.PROOFREAD)
        val missing = bareSingularArticles(proofread)

        val complete = "The coordinator placed the contract on the table beside the entrance."
        val strippedByTone = listOf(
            WritingTone.IMPROVE,
            WritingTone.REPHRASE,
            WritingTone.SHORTEN,
            WritingTone.FORMAL,
            WritingTone.PROFESSIONAL,
            WritingTone.ASSERTIVE,
        ).mapNotNull { tone ->
            val output = LocalWritingAssistant.suggest(complete, tone)
            if (bareSingularArticles(output).isNotEmpty()) "${tone.label}: $output" else null
        }

        assertTrue(
            "Required articles missing. Proofread=[$proofread], missing=$missing, styled=${strippedByTone.joinToString(" | ")}",
            missing.isEmpty() && strippedByTone.isEmpty(),
        )
    }

    @Test
    fun formalProfessionalAndAssertiveRemoveSortOfHedges() {
        val source = "I sort of think we should approve the revised plan."
        val failures = listOf(
            WritingTone.FORMAL,
            WritingTone.PROFESSIONAL,
            WritingTone.ASSERTIVE,
        ).mapNotNull { tone ->
            val output = LocalWritingAssistant.suggest(source, tone)
            val lower = canonical(output)
            val valid = !Regex("\\bsort of\\b").containsMatchIn(lower) &&
                lower.contains("approv") && lower.contains("plan")
            if (valid) null else "${tone.label}: $output"
        }
        assertTrue("Unresolved 'sort of' hedges: ${failures.joinToString(" | ")}", failures.isEmpty())
    }

    @Test
    fun constructiveTonesRemoveScoldingWithoutDroppingTheIssue() {
        val source = "You ignored the safety checklist, so everyone else has to repair the confusion you caused. This is unacceptable."
        val scolding = Regex(
            "(?i)\\b(?:you (?:ignored|failed|forgot|refused|neglected|caused)|everyone else has to|" +
                "your fault|because of you|(?:mess|confusion|problem) you caused|unacceptable|" +
                "should have known|bothered to)\\b",
        )
        val repairLanguage = listOf(
            "review", "address", "resolve", "repair", "fix", "attention", "correct", "complete",
        )
        val failures = constructiveTones.mapNotNull { tone ->
            val output = LocalWritingAssistant.suggest(source, tone)
            val lower = canonical(output)
            val valid = !scolding.containsMatchIn(output) && lower.contains("safety checklist") &&
                repairLanguage.any(lower::contains)
            if (valid) null else "${tone.label}: $output"
        }
        assertTrue("Scolding or lost issue: ${failures.joinToString(" | ")}", failures.isEmpty())
    }

    @Test
    fun everySelectedToneRewritesEveryIndependentBlock() {
        val source = "The amber folder rests beside the window. Ravi inspects the northern gate at dawn. The ceramic sample remains in storage."
        val proofreadBlocks = LocalWritingAssistant.batches(source, WritingTone.PROOFREAD, 1)
        val failures = mutableListOf<String>()
        constructiveTones.forEach { tone ->
            val styled = LocalWritingAssistant.batches(source, tone, 1)
            styled.indices.forEach { index ->
                val baseline = normalized(stripStockFrames(proofreadBlocks[index].replacement))
                val candidate = normalized(stripStockFrames(styled[index].replacement))
                if (candidate == baseline) {
                    failures += "${tone.label} block ${index + 1}: ${styled[index].replacement}"
                }
            }
        }
        assertTrue("Tone no-op blocks: ${failures.joinToString(" | ")}", failures.isEmpty())
    }

    @Test
    fun empatheticToneDoesNotRepeatGenericStockFramesAcrossBlocks() {
        val source = "The delayed permit has left the volunteers frustrated. The missing supplies have made the coordinator anxious. The repeated schedule changes have exhausted the drivers."
        val outputs = LocalWritingAssistant.batches(source, WritingTone.EMPATHETIC, 1)
            .map { it.replacement }
        val genericFrame = Regex(
            "(?i)^(?:I understand the concern|I understand|I recognize the concern)[.!]",
        )
        val genericCount = outputs.count(genericFrame::containsMatchIn)
        val firstSentences = outputs.map { output ->
            output.substringBefore('.').trim().lowercase(Locale.ROOT)
        }
        val acknowledgement = Regex("(?i)\\b(?:understand|recognize|acknowledge|appreciate)\\b")
        val concepts = listOf("permit", "supplies", "schedule")
        val contentBound = outputs.indices.all { index ->
            acknowledgement.containsMatchIn(outputs[index]) &&
                outputs[index].contains(concepts[index], ignoreCase = true)
        }
        assertTrue(
            "Empathetic rewrite repeated a stock frame: ${outputs.joinToString(" | ")}",
            genericCount <= 1 && firstSentences.distinct().size == firstSentences.size && contentBound,
        )
    }

    @Test
    fun constraintsRemainBoundToTheirFactsAndExceptions() {
        val cases = listOf(
            ConstraintCase(
                "Send the signed consent form to legal@example.org, not archive@example.org, before Wednesday.",
            ) { text ->
                val lower = canonical(text)
                lower.contains("legal@example.org") && lower.contains("archive@example.org") &&
                    Regex("legal@example\\.org.{0,45}(?:not|rather than|instead of).{0,15}archive@example\\.org")
                        .containsMatchIn(lower) &&
                    Regex("before wednesday").containsMatchIn(lower)
            },
            ConstraintCase(
                "Order exactly 14 red folders, not 41, and spend no more than $98.",
            ) { text ->
                val lower = canonical(text)
                Regex("exactly 14.{0,20}red folders").containsMatchIn(lower) &&
                    Regex("(?:not|rather than|instead of) 41").containsMatchIn(lower) &&
                    (lower.contains("no more than $98") || lower.contains("at most $98") ||
                        Regex("\\$98.{0,20}(?:limit|maximum|cap)").containsMatchIn(lower))
            },
            ConstraintCase(
                "Move either Alice or Ben, but do not move both.",
            ) { text ->
                val lower = canonical(text)
                Regex("either alice or ben").containsMatchIn(lower) &&
                    listOf("do not move both", "don't move both", "must not move both", "not both")
                        .any(lower::contains)
            },
            ConstraintCase(
                "The meeting may happen outdoors, but I am not promising that it will.",
            ) { text ->
                val lower = canonical(text)
                Regex("meeting.{0,35}(?:may|might|could).{0,20}outdoors").containsMatchIn(lower) &&
                    listOf("not promising", "cannot promise", "can't promise", "not guaranteed")
                        .any(lower::contains)
            },
        )

        val failures = mutableListOf<String>()
        WritingTone.entries.forEach { tone ->
            cases.forEachIndexed { index, case ->
                val output = LocalWritingAssistant.suggest(case.source, tone)
                if (!case.passes(output)) failures += "${tone.label} case ${index + 1}: $output"
            }
        }
        assertTrue("Semantically unbound constraints: ${failures.joinToString(" | ")}", failures.isEmpty())
    }

    @Test
    fun rewordingTonesPreserveTheSecondPersonActorAndNegativeStance() {
        val cases = listOf(
            ActorCase(
                "You ignored the inspection checklist before signing the form.",
                "inspection checklist",
                listOf(
                    "ignored", "overlooked", "missed", "did not review", "didn't review",
                    "failed to review", "did not address", "didn't address",
                ),
            ),
            ActorCase(
                "You failed to submit the permit before the deadline.",
                "permit",
                listOf("failed to submit", "did not submit", "didn't submit", "submission was not completed"),
            ),
        )
        val tones = listOf(
            WritingTone.REPHRASE,
            WritingTone.SHORTEN,
            WritingTone.ASSERTIVE,
            WritingTone.CAVEMAN,
        )
        val failures = mutableListOf<String>()
        tones.forEach { tone ->
            cases.forEachIndexed { index, case ->
                val output = LocalWritingAssistant.suggest(case.source, tone)
                val lower = canonical(output)
                val actorPreserved = Regex("\\b(?:you|your)\\b").containsMatchIn(lower)
                val stancePreserved = case.negativeStances.any(lower::contains)
                val shiftedToUs = Regex("\\b(?:we|let's|let us)\\b.{0,35}\\b(?:review|submit|complete|fix|address)\\b")
                    .containsMatchIn(lower)
                if (!actorPreserved || !stancePreserved || shiftedToUs || !lower.contains(case.subject)) {
                    failures += "${tone.label} case ${index + 1}: $output"
                }
            }
        }
        assertTrue("Actor or stance changed: ${failures.joinToString(" | ")}", failures.isEmpty())
    }

    @Test
    fun nonCommitmentIsNotRewrittenAsInability() {
        val source = "I’m not promising that the ceremony will happen outdoors."
        val failures = WritingTone.entries.mapNotNull { tone ->
            val output = LocalWritingAssistant.suggest(source, tone)
            val lower = canonical(output)
            val inability = Regex("\\b(?:cannot|can't|unable to)\\s+(?:promise|guarantee|commit)\\b")
                .containsMatchIn(lower)
            val nonCommitment = listOf(
                "not promising", "make no promise", "not guaranteeing", "not guaranteed",
                "not committing", "no commitment", "do not promise", "don't promise",
            ).any(lower::contains)
            if (!inability && nonCommitment && lower.contains("ceremony") && lower.contains("outdoors")) {
                null
            } else {
                "${tone.label}: $output"
            }
        }
        assertTrue("Non-commitment became inability: ${failures.joinToString(" | ")}", failures.isEmpty())
    }

    @Test
    fun cooperativeToneDoesNotDowngradeShouldToCan() {
        val source = "We should review the waiver before filing it."
        val output = LocalWritingAssistant.suggest(source, WritingTone.COOPERATIVE)
        val lower = canonical(output)
        val obligation = listOf("should review", "must review", "need to review", "needs to review")
            .any(lower::contains)
        val downgraded = Regex("\\bwe can review\\b").containsMatchIn(lower)
        assertTrue("Cooperative changed 'should' to mere capability: $output", obligation && !downgraded)
    }

    @Test
    fun declarativeWordOrderDoesNotKeepAQuestionMark() {
        val source = "The revised contract is attached?"
        val failures = WritingTone.entries.mapNotNull { tone ->
            val output = LocalWritingAssistant.suggest(source, tone).trim()
            val declarative = Regex("(?i)^(?:the )?(?:revised|updated|amended) contract is attached[.]$")
                .containsMatchIn(stripStockFrames(output))
            val interrogative = Regex("(?i)^is (?:the )?(?:revised|updated|amended) contract attached[?]$")
                .containsMatchIn(stripStockFrames(output))
            if (declarative || interrogative) null else "${tone.label}: $output"
        }
        assertTrue("Declarative-question punctuation remains malformed: ${failures.joinToString(" | ")}", failures.isEmpty())
    }

    @Test
    fun proofreadRepairsCommaSplicesAndRequiredCommas() {
        val commaSplice = LocalWritingAssistant.suggest(
            "The shipment is ready, we can leave now.",
            WritingTone.PROOFREAD,
        )
        val directAddress = LocalWritingAssistant.suggest(
            "Please review the draft Maria.",
            WritingTone.PROOFREAD,
        )
        val leadingWhen = LocalWritingAssistant.suggest(
            "When the courier arrives call the office.",
            WritingTone.PROOFREAD,
        )

        val spliceFixed = !Regex("(?i)\\bready,\\s+we\\b").containsMatchIn(commaSplice) &&
            Regex("(?i)\\bready(?:[.;]|,\\s+(?:and|so|but))\\s+we\\b").containsMatchIn(commaSplice)
        val addressFixed = Regex(
            "(?i)(?:please review the draft,\\s*maria|maria,\\s*please review the draft)",
        ).containsMatchIn(directAddress)
        val whenFixed = Regex("(?i)when the courier arrives,\\s*call the office")
            .containsMatchIn(leadingWhen)

        assertTrue(
            "Required comma repair failed: splice=[$commaSplice], address=[$directAddress], when=[$leadingWhen]",
            spliceFixed && addressFixed && whenFixed,
        )
    }

    @Test
    fun rewritesKeepDeadlinesBoundToTheConditionTheyModify() {
        val source = "Do not release the deposit unless Morgan approves it before Friday."
        val failures = WritingTone.entries.mapNotNull { tone ->
            val output = LocalWritingAssistant.suggest(source, tone)
            val lower = canonical(output)
            val approvalDeadline = Regex(
                "morgan.{0,25}(?:approves?|authorizes?|confirms?).{0,20}before friday",
            ).containsMatchIn(lower)
            val releaseBan = Regex("(?:do not|don't|must not)\\s+release.{0,20}deposit")
                .containsMatchIn(lower)
            val conditional = Regex("\\b(?:unless|only if)\\b").containsMatchIn(lower)
            if (approvalDeadline && releaseBan && conditional) null else "${tone.label}: $output"
        }
        assertTrue("Deadline scope changed: ${failures.joinToString(" | ")}", failures.isEmpty())
    }

    @Test
    fun cooperativeToneDoesNotShiftAssignedResponsibilityToLetUs() {
        val source = "Tell Omar to stop using the reserved entrance."
        val output = LocalWritingAssistant.suggest(source, WritingTone.COOPERATIVE)
        val lower = canonical(output)
        val shifted = Regex("^(?:let's|let us|we (?:can|should|will))\\b")
            .containsMatchIn(stripStockFrames(lower))
        val responsibilityClear =
            Regex("(?:please\\s+)?tell omar.{0,25}stop").containsMatchIn(lower) ||
                Regex("omar.{0,25}(?:must|needs to|should) stop").containsMatchIn(lower)
        assertTrue("Cooperative shifted assigned responsibility: $output", !shifted && responsibilityClear)
    }

    @Test
    fun toneChangesPreserveRandomSelectionScope() {
        val source = "Do not grant random people access to the private archive."
        val failures = constructiveTones.mapNotNull { tone ->
            val output = LocalWritingAssistant.suggest(source, tone)
            val lower = canonical(output)
            val randomScope = listOf("random people", "unvetted people", "unscreened people", "unknown people")
                .any(lower::contains)
            val coreMeaning = lower.contains("access") && lower.contains("private archive") &&
                Regex("(?:do not|don't|must not|cannot)\\s+grant").containsMatchIn(lower)
            if (randomScope && coreMeaning) null else "${tone.label}: $output"
        }
        assertTrue("Random-selection scope changed: ${failures.joinToString(" | ")}", failures.isEmpty())
    }

    @Test
    fun cavemanProducesCompleteGrammaticalSentencesWithArticles() {
        val sources = listOf(
            "The coordinator placed the signed contract on the table beside the entrance.",
            "A technician stored the device in a cabinet near the office.",
        )
        val failures = sources.mapNotNull { sourceText ->
            val output = LocalWritingAssistant.suggest(sourceText, WritingTone.CAVEMAN).trim()
            val lower = canonical(output)
            val complete = output.firstOrNull()?.isUpperCase() == true &&
                output.lastOrNull()?.let { it in ".!?" } == true &&
                Regex("\\b(?:placed|put|stored|kept)\\b").containsMatchIn(lower)
            val articles = if (lower.contains("coordinator")) {
                listOf("the coordinator", "the signed contract", "the table", "the entrance")
                    .all(lower::contains)
            } else {
                listOf("a technician", "the device", "a cabinet", "the office")
                    .all(lower::contains)
            }
            if (complete && articles) null else "$sourceText -> $output"
        }
        assertTrue("Caveman produced article-free fragments: ${failures.joinToString(" | ")}", failures.isEmpty())
    }

    private fun bareSingularArticles(text: String): List<String> {
        val patterns = listOf(
            Regex("(?i)\\bplace(?:d)?\\s+contract\\b"),
            Regex("(?i)\\bon table\\b"),
            Regex("(?i)\\bbeside entrance\\b"),
            Regex("(?i)^coordinator\\s+placed\\b"),
        )
        return patterns.filter { it.containsMatchIn(stripStockFrames(text)) }.map { it.pattern }
    }

    private fun stripStockFrames(text: String): String = Regex(
        "(?i)(?:Clearly stated:|Put another way:|Hi!|Please note:|For clarity:|" +
            "A balanced approach:|I understand(?: the concern)?\\.|Let's work together\\.|To be clear:)\\s*",
    ).replace(text, "")

    private fun canonical(text: String): String = text.lowercase(Locale.ROOT)
        .replace('’', '\'')
        .replace('‘', '\'')

    private fun normalized(text: String): String = canonical(text).replace(Regex("\\s+"), " ").trim()

    private data class ConstraintCase(val source: String, val passes: (String) -> Boolean)
    private data class ActorCase(
        val source: String,
        val subject: String,
        val negativeStances: List<String>,
    )
}
