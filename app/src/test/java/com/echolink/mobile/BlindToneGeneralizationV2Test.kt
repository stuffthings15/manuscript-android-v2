package com.echolink.mobile

import java.io.File
import kotlin.math.max
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Second blind corpus authored without access to the implementation or first holdout. */
class BlindToneGeneralizationV2Test {
    private val androidRoot = File(checkNotNull(System.getProperty("echoLinkAndroidDir")))
    private val source = File(
        androidRoot,
        "app/src/test/resources/blind-tone-generalization-v2.txt",
    ).readText().trim()

    @Test
    fun evaluatesAllTonesOnAnIndependentDomain() {
        val firstRun = WritingTone.entries.associateWith(::rewriteWholePage)
        val secondRun = WritingTone.entries.associateWith(::rewriteWholePage)
        val proofread = firstRun.getValue(WritingTone.PROOFREAD).text
        val scores = linkedMapOf<WritingTone, Int>()
        val failures = linkedMapOf<WritingTone, List<String>>()
        val report = buildString {
            appendLine("Manuscript blind tone generalization v2")
            appendLine("Domain: community arts fundraiser")
            appendLine("Sentences: 10")
            appendLine("Expected review blocks: 4")
            appendLine()
            WritingTone.entries.forEach { tone ->
                val result = firstRun.getValue(tone)
                val checks = linkedMapOf(
                    "correctness" to correctnessPasses(result.text),
                    "punctuation_format" to punctuationAndFormatPass(result.text),
                    "protected_facts" to protectedFactsPass(result.text),
                    "meaning" to meaningPass(result.text),
                    "negation_constraints" to constraintsPass(result.text),
                    "deterministic" to (result == secondRun.getValue(tone)),
                    "substantive_rewrite" to substantiveRewritePass(tone, result.text, proofread),
                    "tone" to tonePasses(tone, result.text, proofread),
                )
                val failed = checks.filterValues { !it }.keys.toList()
                scores[tone] = checks.values.count { it }
                failures[tone] = failed
                appendLine("${tone.label}: ${scores.getValue(tone)}/8")
                appendLine("blocks=${result.blocks}; ${checks.entries.joinToString { "${it.key}=${if (it.value) "PASS" else "FAIL"}" }}")
                appendLine("failed=${if (failed.isEmpty()) "none" else failed.joinToString()}")
                appendLine(result.text)
                appendLine()
            }
        }
        val reportFile = File(androidRoot, "build/reports/blind-tone-generalization-v2.txt")
        reportFile.parentFile?.mkdirs()
        reportFile.writeText(report)

        assertEquals(WritingTone.entries.size, firstRun.size)
        assertTrue("Each run must use four review blocks.", firstRun.values.all { it.blocks == 4 })
        assertTrue(
            "Blind generalization v2 failed. See ${reportFile.absolutePath}. Failures: $failures",
            scores.values.all { it == 8 },
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

    private fun correctnessPasses(text: String): Boolean {
        val lower = text.lowercase()
        return listOf("childrens", "incorectly").none(lower::contains) &&
            !Regex("\\bmarisol\\s+say\\b").containsMatchIn(lower) &&
            !Regex("\\bvolunteer\\s+schedule\\s+are\\b").containsMatchIn(lower) &&
            !Regex("\\bpaintings\\s+was\\b").containsMatchIn(lower)
    }

    private fun punctuationAndFormatPass(text: String): Boolean =
        !Regex("[ \\t]{2,}").containsMatchIn(text) &&
            !Regex("\\s+[,.;!?]").containsMatchIn(text) &&
            !text.contains('\u00e2') && !text.contains('\u00c3') &&
            text.trim().lastOrNull()?.let { it in ".!?" } == true

    private fun protectedFactsPass(text: String): Boolean = listOf(
        "Marisol", "October 17, 2027", "ART-5831", "46", "12", "north entrance",
        "guest list", "120", "Devon", "6:40 p.m.", "7:10 p.m.", "Monday", "Priya",
        "neighborhood", "access@harborarts.org", "https://harborarts.org/gala-2027",
    ).all { text.contains(it) }

    private fun meaningPass(text: String): Boolean = listOf(
        "gallery", "volunteer", "donation", "fire marshal", "printed program",
        "welcome speech", "seating plan", "fundraiser", "donor names", "accessibility",
        "venue",
    ).all { text.contains(it, ignoreCase = true) }

    private fun constraintsPass(text: String): Boolean {
        val lower = text.lowercase()
        val privateList = (
            listOf("do not publish", "don't publish", "must not publish")
                .any(lower::contains) && lower.contains("outside the committee")
            ) || Regex("keep the guest list.{0,20}within the committee").containsMatchIn(lower) ||
            Regex("(?:do not|don't|must not) share.{0,30}guest list.{0,30}outside the committee")
                .containsMatchIn(lower)
        val hardLimit = lower.contains("120") && listOf(
            "may not", "must not", "cannot", "can't", "do not", "hard limit",
        ).any(lower::contains) && listOf("exceed", "exceeded", "over").any(lower::contains)
        return privateList && hardLimit
    }

    private fun substantiveRewritePass(tone: WritingTone, text: String, proofread: String): Boolean {
        if (tone == WritingTone.PROOFREAD) return normalized(text) != normalized(source)
        val ratio = wordEditRatio(stripStockFrames(proofread), stripStockFrames(text))
        return when (tone) {
            WritingTone.SHORTEN, WritingTone.CAVEMAN -> wordCount(text) < wordCount(proofread)
            WritingTone.REPHRASE -> ratio >= 0.18
            else -> ratio >= 0.05
        }
    }

    private fun tonePasses(tone: WritingTone, text: String, proofread: String): Boolean {
        val lower = text.lowercase()
        val hostile = listOf(
            "you ignored", "everyone else has to repair", "confusion you caused",
        ).any(lower::contains)
        val hedges = listOf("was wondering", "perhaps", "might possibly", "consider revising")
        val editRatio = wordEditRatio(stripStockFrames(proofread), stripStockFrames(text))
        return when (tone) {
            WritingTone.PROOFREAD -> editRatio <= 0.12 && hostile && lower.contains("close to tears")
            WritingTone.IMPROVE -> editRatio >= 0.05 && hedges.count(lower::contains) <= 1
            WritingTone.REPHRASE -> editRatio >= 0.18 &&
                text.length in (proofread.length * 7 / 10)..(proofread.length * 13 / 10)
            WritingTone.SHORTEN -> wordCount(text) <= wordCount(proofread) * 90 / 100 &&
                hedges.none(lower::contains) && !lower.contains("very lengthy")
            WritingTone.FRIENDLY -> !hostile && listOf(
                "please", "thank", "appreciate", "together",
            ).any(lower::contains)
            WritingTone.FORMAL -> !hostile && '!' !in text && listOf(
                "i'm", "i’m", "was wondering", "perhaps", "might possibly", "honestly",
            ).none(lower::contains)
            WritingTone.PROFESSIONAL -> !hostile && listOf(
                "was wondering", "perhaps", "might possibly", "honestly", "close to tears",
            ).none(lower::contains)
            WritingTone.DIPLOMATIC -> !hostile && listOf(
                "resolve", "review", "clarify", "address", "concern",
            ).any(lower::contains)
            WritingTone.EMPATHETIC -> !hostile && listOf(
                "understand", "recognize", "appreciate", "acknowledge", "support",
            ).any(lower::contains) && listOf("exhausted", "worried", "tears").any(lower::contains)
            WritingTone.COOPERATIVE -> !hostile && (
                listOf("together", "coordinate", "we can", "let's", "let us", "work with")
                    .any(lower::contains) ||
                    Regex("\\b(?:please|could you)\\s+(?:send|revise|confirm|review|resolve)\\b")
                        .containsMatchIn(lower)
                )
            WritingTone.ASSERTIVE -> !hostile && hedges.none(lower::contains)
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
            WritingTone.COLLEGE -> !hostile && editRatio >= 0.05
            WritingTone.CAVEMAN -> !hostile && wordCount(text) <= wordCount(proofread) * 85 / 100 &&
                sentences(text).all { wordCount(it) <= 15 || it.contains("http") || it.contains("@")} 
        }
    }

    private fun stripStockFrames(text: String): String = Regex(
        "(?i)(?:Clearly stated:|Put another way:|Hi!|Please note:|For clarity:|" +
            "A balanced approach:|I understand\\.|Let's work together\\.|To be clear:)\\s*",
    ).replace(text, "")

    private fun normalized(text: String): String = text.lowercase().replace(Regex("\\s+"), " ").trim()
    private fun wordCount(text: String): Int = words(text).size
    private fun words(text: String): List<String> = Regex("[A-Za-z0-9$%./?=&@'-]+")
        .findAll(text.lowercase()).map { it.value }.toList()
    private fun sentences(text: String): List<String> =
        Regex("(?<=[.!?])\\s+").split(text).filter { it.isNotBlank() }

    private fun wordEditRatio(left: String, right: String): Double {
        val a = words(left)
        val b = words(right)
        if (a.isEmpty() && b.isEmpty()) return 0.0
        var previous = IntArray(b.size + 1) { it }
        a.forEachIndexed { row, leftWord ->
            val current = IntArray(b.size + 1)
            current[0] = row + 1
            b.forEachIndexed { column, rightWord ->
                current[column + 1] = minOf(
                    current[column] + 1,
                    previous[column + 1] + 1,
                    previous[column] + if (leftWord == rightWord) 0 else 1,
                )
            }
            previous = current
        }
        return previous[b.size].toDouble() / max(a.size, b.size).coerceAtLeast(1)
    }

    private data class RewriteResult(val text: String, val blocks: Int)
}
