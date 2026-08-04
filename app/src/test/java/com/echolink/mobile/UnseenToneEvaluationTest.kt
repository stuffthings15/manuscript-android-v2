package com.echolink.mobile

import java.io.File
import kotlin.math.max
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Sealed holdout created without inspecting LocalWritingAssistant or the original tone corpus.
 * Production rewrite code must not be changed during the first execution of this test.
 */
class UnseenToneEvaluationTest {
    private val androidRoot = File(checkNotNull(System.getProperty("echoLinkAndroidDir")))
    private val source = File(
        androidRoot,
        "app/src/test/resources/unseen-tone-holdout-v1.txt",
    ).readText().trim()

    @Test
    fun evaluatesEveryToneAgainstTheSealedHoldout() {
        val firstRun = WritingTone.entries.associateWith(::rewriteWholePage)
        val secondRun = WritingTone.entries.associateWith(::rewriteWholePage)
        val proofread = firstRun.getValue(WritingTone.PROOFREAD).text
        val scores = linkedMapOf<WritingTone, Int>()
        val failures = linkedMapOf<WritingTone, List<String>>()

        val report = buildString {
            appendLine("Manuscript sealed unseen-tone holdout v1")
            appendLine("Corpus: app/src/test/resources/unseen-tone-holdout-v1.txt")
            appendLine("Context heading plus 12 unseen sentences")
            appendLine("Expected review blocks: 5")
            appendLine("Production engine frozen for first run: yes")
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

        val reportFile = File(androidRoot, "build/reports/unseen-tone-evaluation.txt")
        reportFile.parentFile?.mkdirs()
        reportFile.writeText(report)

        assertEquals(WritingTone.entries.size, firstRun.size)
        assertTrue("Each run must use five review blocks.", firstRun.values.all { it.blocks == 5 })
        assertTrue(
            "The sealed holdout failed. See ${reportFile.absolutePath}. Failures: $failures",
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
        return listOf(
            "missng", " dont ", " wasnt ", "logs was", "labels appears",
        ).none(lower::contains) && !Regex("\\bscreenshot\\s+show\\b").containsMatchIn(lower)
    }

    private fun punctuationAndFormatPass(text: String): Boolean =
        !Regex("[ \\t]{2,}").containsMatchIn(text) &&
            !Regex("\\s+[,.;!?]").containsMatchIn(text) &&
            !text.contains('\u00e2') && !text.contains('\u00c3') &&
            text.trim().lastOrNull()?.let { it in ".!?" } == true

    private fun protectedFactsPass(text: String): Boolean = listOf(
        "Harbor Lantern",
        "Q7-04",
        "Tuesday",
        "Friday",
        "3:30 p.m",
        "18,450",
        "7.25%",
        "$4,090.60",
        "https://example.com/a?ref=Q7-04&mode=raw",
        "Thursday",
        "pallet 6",
        "Elena",
        "FRAGILE / KEEP UPRIGHT",
    ).all { text.contains(it) }

    private fun meaningPass(text: String): Boolean = listOf(
        "shipment", "logs", "Maya", "vendor", "labels", "screenshot", "cartons",
        "night", "time slots", "invoice", "handoff",
    ).all { text.contains(it, ignoreCase = true) }

    private fun constraintsPass(text: String): Boolean {
        val lower = text.lowercase()
        val noRounding = listOf("dont round", "don't round", "do not round").any(lower::contains)
        val noChanging = listOf("dont change", "don't change", "do not change", "or change").any(lower::contains)
        return lower.contains("leave batch-id q7-04 alone") && noRounding && noChanging &&
            lower.contains("must stay")
    }

    private fun substantiveRewritePass(
        tone: WritingTone,
        text: String,
        proofread: String,
    ): Boolean {
        if (tone == WritingTone.PROOFREAD) return normalized(text) != normalized(source)
        val ratio = wordEditRatio(stripStockFrames(proofread), stripStockFrames(text))
        return when (tone) {
            WritingTone.SHORTEN, WritingTone.CAVEMAN -> wordCount(text) < wordCount(proofread)
            WritingTone.REPHRASE -> ratio >= 0.20
            else -> ratio >= 0.05
        }
    }

    private fun tonePasses(tone: WritingTone, text: String, proofread: String): Boolean {
        val lower = text.lowercase()
        val hostile = listOf(
            "pile of fog", "wasn't paying attention", "wasnt paying attention",
            "fixing this mess", "finally do their part", "blame game",
        ).any(lower::contains)
        val editRatio = wordEditRatio(stripStockFrames(proofread), stripStockFrames(text))
        return when (tone) {
            WritingTone.PROOFREAD -> editRatio <= 0.12 && hostile
            WritingTone.IMPROVE -> editRatio >= 0.05 &&
                !lower.contains("it would be nice if everyone could finally do their part")
            WritingTone.REPHRASE -> editRatio >= 0.20 &&
                text.length in (proofread.length * 7 / 10)..(proofread.length * 13 / 10)
            WritingTone.SHORTEN -> !hostile &&
                wordCount(text) <= wordCount(proofread) * 90 / 100 &&
                listOf("quick update", "honestly", "fixing this mess", "finally do their part")
                    .none(lower::contains)
            WritingTone.FRIENDLY -> !hostile && listOf("please", "thanks", "appreciate").any(lower::contains)
            WritingTone.FORMAL -> !hostile && '!' !in text && listOf(
                "don't", "wasn't", "let's", "hi team", "quick update", "mess", "blame game",
            ).none(lower::contains)
            WritingTone.PROFESSIONAL -> !hostile && listOf(
                "honestly", "quick update", "it would be nice", "finally",
            ).none(lower::contains)
            WritingTone.DIPLOMATIC -> !hostile && listOf(
                "clarify", "concern", "review", "address", "may", "could",
            ).any(lower::contains)
            WritingTone.EMPATHETIC -> !hostile && listOf(
                "appreciate", "recognize", "understand", "thank", "effort", "difficult",
            ).any(lower::contains)
            WritingTone.COOPERATIVE -> !hostile && listOf(
                "together", "coordinate", "confirm", "let's", "let us",
            ).any(lower::contains)
            WritingTone.ASSERTIVE -> !hostile && listOf(
                "it would be nice", "perhaps", "maybe", "sort of", "finally do their part",
            ).none(lower::contains)
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
            WritingTone.CAVEMAN -> !hostile && wordCount(text) <= wordCount(proofread) * 75 / 100 &&
                sentences(text).filterNot { it.contains("http") || it.contains("FRAGILE") }
                    .all { wordCount(it) <= 15 }
        }
    }

    private fun stripStockFrames(text: String): String = Regex(
        "(?i)(?:Clearly stated:|Put another way:|Hi!|Please note:|For clarity:|" +
            "A balanced approach:|I understand\\.|Let's work together\\.|To be clear:)\\s*",
    ).replace(text, "")

    private fun normalized(text: String): String = text.lowercase().replace(Regex("\\s+"), " ").trim()

    private fun wordCount(text: String): Int = words(text).size

    private fun words(text: String): List<String> = Regex("[A-Za-z0-9$%./?=&'-]+")
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
