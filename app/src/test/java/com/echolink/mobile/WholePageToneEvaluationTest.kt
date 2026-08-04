package com.echolink.mobile

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WholePageToneEvaluationTest {
    private val source = listOf(
        "i definately dont recieve teh report because it has alot of issue .",
        "these is the first section and it need help !",
        "we was supposed to send it yesterday , but it didnt happen .",
        "at this point in time we need to make a decision .",
        "i would like to explain this because the reason is that we cannot wait .",
        "i think we should fix the issue as soon as possible .",
        "you failed to follow the instructions and this is unacceptable .",
        "i know this is frustrating and that is hard .",
        "we need to work with each other in order to finish .",
        "i would just like to say maybe we cannot continue .",
        "please let me know when you have a chance .",
        "the final sentence need help",
    ).joinToString("\n\n")

    @Test
    fun evaluatesEveryToneAcrossAWholePageReplacementCycle() {
        val expectedBlocks = Regex("\\n\\s*\\n").findAll(source).count() + 1
        val results = WritingTone.entries.associateWith(::rewriteWholePage)
        val proofread = results.getValue(WritingTone.PROOFREAD).text
        val scores = linkedMapOf<WritingTone, Int>()
        val report = buildString {
            appendLine("Whole-page Manuscript tone evaluation")
            appendLine("Source sentences: 12")
            appendLine("Expected paragraph-aware review blocks: $expectedBlocks")
            appendLine()
            results.forEach { (tone, result) ->
                val checks = linkedMapOf(
                    "spelling" to spellingIsCorrect(result.text),
                    "grammar" to grammarIsCorrect(result.text),
                    "punctuation" to punctuationIsCorrect(result.text),
                    "formatting" to formattingIsCorrect(result.text),
                    "meaning" to basicMeaningIsPreserved(result.text),
                    "tone" to toneMatches(tone, result.text, proofread),
                )
                scores[tone] = checks.values.count { it }
                appendLine("${tone.label}: ${checks.values.count { it }}/6")
                appendLine("blocks=${result.blocks}; ${checks.entries.joinToString { "${it.key}=${if (it.value) "PASS" else "FAIL"}" }}")
                appendLine(result.text)
                appendLine()
            }
        }

        val androidRoot = File(checkNotNull(System.getProperty("echoLinkAndroidDir")))
        val reportFile = File(androidRoot, "build/reports/whole-page-tone-evaluation.txt")
        reportFile.parentFile.mkdirs()
        reportFile.writeText(report)

        assertEquals(WritingTone.entries.size, results.size)
        assertTrue(results.values.all { it.blocks == expectedBlocks })
        assertTrue(results.values.all { it.text.isNotBlank() })
        assertTrue(
            "Every tone must pass all six checks. See ${reportFile.absolutePath}",
            scores.values.all { it == 6 },
        )
    }

    private fun rewriteWholePage(tone: WritingTone): RewriteResult {
        val session = checkNotNull(WritingReviewSession.create(source, sentencesPerBlock = 3))
        val total = session.total
        var text = source
        while (!session.complete) {
            val block = checkNotNull(session.current(tone))
            check(
                session.setModelReplacement(
                    block.index,
                    tone,
                    LocalWritingAssistant.suggest(block.original, tone),
                ),
            )
            text = session.replaceCurrent(text, tone)
        }
        return RewriteResult(text, total)
    }

    private fun spellingIsCorrect(text: String): Boolean = listOf(
        "definately", "recieve", "teh", "alot", " dont ", " didnt ",
    ).none { text.lowercase().contains(it) }

    private fun grammarIsCorrect(text: String): Boolean = listOf(
        "these is", "we was", "section need help", "sentence need help", "it need help",
        "a lot of issue.", "since it. has", "respectfully, at", "and that is hard",
        "and that sounds difficult",
    ).none { text.lowercase().contains(it) }

    private fun punctuationIsCorrect(text: String): Boolean =
        !Regex("\\s+[,.!?]").containsMatchIn(text) && text.trim().last() in ".!?"

    private fun formattingIsCorrect(text: String): Boolean =
        !text.contains("  ") && !text.contains("\n\n\n") &&
            !text.contains('\u00e2') && !text.contains('\u00c3') &&
            !text.contains("â€") && !text.contains("Ã") &&
            text.lines().filter { it.isNotBlank() }.all { it.firstOrNull()?.isUpperCase() == true }

    private fun basicMeaningIsPreserved(text: String): Boolean = listOf(
        "report", "yesterday", "instructions", "frustrating", "finish", "continue", "final",
    ).all { text.contains(it, ignoreCase = true) }

    private fun toneMatches(tone: WritingTone, text: String, proofread: String): Boolean {
        val lower = text.lowercase()
        val actorPreserved = lower.contains("you did not follow") || lower.contains("you failed to follow")
        val oldScolding = lower.contains("you failed") || lower.contains("unacceptable")
        val filler = listOf(
            "at this point in time", "because the reason is that", "i would just like to say",
            "i want to say", "when you have a chance",
        )
        val contractions = listOf("don't", "didn't", "can't", "won't", "i'd", "i'm", "let's")
        fun words(value: String): Int = Regex("[A-Za-z0-9'-]+").findAll(value).count()

        return when (tone) {
            WritingTone.PROOFREAD -> text == proofread && oldScolding
            WritingTone.IMPROVE -> lower.contains("now we need to decide") &&
                !lower.contains("because the reason is that") && !lower.contains("unacceptable")
            WritingTone.REPHRASE -> lower.contains("since") && actorPreserved && text != proofread
            WritingTone.SHORTEN -> words(text) < words(proofread) && filler.none(lower::contains)
            WritingTone.FRIENDLY -> !oldScolding && actorPreserved &&
                listOf("please", "looks like", "can't", "won't").any(lower::contains)
            WritingTone.FORMAL -> !oldScolding && '!' !in text && contractions.none(lower::contains) &&
                listOf("maybe", "just like to say", "when you have a chance").none(lower::contains)
            WritingTone.PROFESSIONAL -> !oldScolding && '!' !in text && contractions.none(lower::contains) &&
                lower.contains("resolve the issue") && lower.contains("promptly") &&
                listOf("maybe", "i want to say", "when you have a chance").none(lower::contains)
            WritingTone.DIPLOMATIC -> !oldScolding && actorPreserved &&
                lower.contains("it appears that you did not follow") &&
                lower.contains("needs further attention")
            WritingTone.EMPATHETIC -> !oldScolding && actorPreserved &&
                lower.contains("recognize") && lower.contains("frustrating")
            WritingTone.COOPERATIVE -> !oldScolding && actorPreserved &&
                lower.contains("let's") && lower.contains("work together") &&
                !lower.contains("we can fix")
            WritingTone.ASSERTIVE -> !oldScolding && actorPreserved &&
                lower.contains("we may be unable to continue") && lower.contains("respond promptly") &&
                listOf("maybe", "i think", "i want to", "when you have a chance").none(lower::contains)
            WritingTone.ACADEMIC,
            WritingTone.CONVERSATIONAL,
            WritingTone.CONCISE,
            WritingTone.DETAILED,
            WritingTone.PERSUASIVE,
            WritingTone.CONFIDENT,
            WritingTone.CASUAL,
            WritingTone.TECHNICAL,
            WritingTone.SIMPLIFIED,
            WritingTone.HIGH_SCHOOL,
            WritingTone.COLLEGE -> text != proofread && !oldScolding
            WritingTone.CAVEMAN -> words(text) <= words(proofread) * 85 / 100 &&
                filler.none(lower::contains) && listOf(
                    "because the reason is that", "in order to", "as soon as possible",
                ).none(lower::contains)
        }
    }

    @Suppress("unused")
    private fun legacyToneMatches(tone: WritingTone, text: String, proofread: String): Boolean = when (tone) {
        WritingTone.PROOFREAD -> text == proofread
        WritingTone.IMPROVE -> text.contains("Now we need to decide", ignoreCase = true) &&
            !text.contains("because the reason is that", ignoreCase = true)
        WritingTone.REPHRASE -> text.contains("since", ignoreCase = true) && text.contains("I'd like to", ignoreCase = true) &&
            !text.contains("since it. has", ignoreCase = true)
        WritingTone.SHORTEN -> text.length < proofread.length &&
            !text.contains("at this point in time", ignoreCase = true) &&
            !text.contains("because the reason is that", ignoreCase = true) &&
            !text.contains("I would like to", ignoreCase = true)
        WritingTone.FRIENDLY -> (text.contains("won't", ignoreCase = true) || text.contains("can't", ignoreCase = true)) &&
            !text.contains("cannot", ignoreCase = true) &&
            !text.contains("You failed", ignoreCase = true) && !text.contains("unacceptable", ignoreCase = true)
        WritingTone.FORMAL -> text.contains("do not", ignoreCase = true) &&
            (text.contains("cannot", ignoreCase = true) || text.contains("unable to", ignoreCase = true)) &&
            listOf("don't", "didn't", "can't", "won't", "I'd").none { text.contains(it, ignoreCase = true) }
        WritingTone.PROFESSIONAL -> text.contains("I recommend that we", ignoreCase = true) &&
            text.contains("resolve the issue", ignoreCase = true) &&
            !text.contains("maybe", ignoreCase = true) && !text.contains("You failed", ignoreCase = true)
        WritingTone.DIPLOMATIC -> text.contains("we still need to follow", ignoreCase = true) &&
            text.contains("needs further attention", ignoreCase = true) &&
            !text.contains("Respectfully, At", ignoreCase = true)
        WritingTone.EMPATHETIC -> text.contains("I recognize that this is frustrating", ignoreCase = true) &&
            text.contains("sounds difficult", ignoreCase = true) &&
            !text.contains("You failed", ignoreCase = true) && !text.contains("unacceptable", ignoreCase = true)
        WritingTone.COOPERATIVE -> text.contains("Let's", ignoreCase = true) &&
            text.contains("work together", ignoreCase = true) &&
            !text.contains('\u00e2') && !text.contains('\u00c3') &&
            !text.contains("You failed", ignoreCase = true) && !text.contains("unacceptable", ignoreCase = true) &&
            !text.contains("â€")
        WritingTone.ASSERTIVE -> text.contains("I want to", ignoreCase = true) &&
            !text.contains("maybe", ignoreCase = true) && !text.contains("I think", ignoreCase = true) &&
            !text.contains("I would like to", ignoreCase = true)
        WritingTone.ACADEMIC,
        WritingTone.CONVERSATIONAL,
        WritingTone.CONCISE,
        WritingTone.DETAILED,
        WritingTone.PERSUASIVE,
        WritingTone.CONFIDENT,
        WritingTone.CASUAL,
        WritingTone.TECHNICAL,
        WritingTone.SIMPLIFIED,
        WritingTone.HIGH_SCHOOL,
        WritingTone.COLLEGE -> text != proofread
        WritingTone.CAVEMAN -> text.length < proofread.length && listOf(
            "at this point in time", "because the reason is that", "I would like to",
            "in order to", "as soon as possible",
        ).none { text.contains(it, ignoreCase = true) }
    }

    private data class RewriteResult(val text: String, val blocks: Int)
}
