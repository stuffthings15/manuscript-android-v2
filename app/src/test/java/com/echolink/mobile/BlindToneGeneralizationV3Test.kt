package com.echolink.mobile

import java.io.File
import kotlin.math.max
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Third blind corpus authored without repository, implementation, or earlier-corpus access. */
class BlindToneGeneralizationV3Test {
    private val androidRoot = File(checkNotNull(System.getProperty("echoLinkAndroidDir")))
    private val sourceLines = File(
        androidRoot,
        "app/src/test/resources/blind-tone-generalization-v3.txt",
    ).readLines().map(String::trim).filter(String::isNotBlank)
    private val source = sourceLines.joinToString(" ")

    @Test
    fun evaluatesAllTonesOnAThirdIndependentDomain() {
        val firstRun = WritingTone.entries.associateWith(::rewriteEachLine)
        val secondRun = WritingTone.entries.associateWith(::rewriteEachLine)
        val proofread = firstRun.getValue(WritingTone.PROOFREAD).text
        val scores = linkedMapOf<WritingTone, Int>()
        val failures = linkedMapOf<WritingTone, List<String>>()
        val report = buildString {
            appendLine("Manuscript blind tone generalization v3")
            appendLine("Domain: marsh wildlife survey")
            appendLine("Independently authored sentences: ${sourceLines.size}")
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
                    "tone" to tonePasses(tone, result, proofread),
                )
                val failed = checks.filterValues { !it }.keys.toList()
                scores[tone] = checks.values.count { it }
                failures[tone] = failed
                appendLine("${tone.label}: ${scores.getValue(tone)}/8")
                appendLine(checks.entries.joinToString { "${it.key}=${if (it.value) "PASS" else "FAIL"}" })
                appendLine("failed=${if (failed.isEmpty()) "none" else failed.joinToString()}")
                result.outputs.forEach(::appendLine)
                appendLine()
            }
        }
        val reportFile = File(androidRoot, "build/reports/blind-tone-generalization-v3.txt")
        reportFile.parentFile?.mkdirs()
        reportFile.writeText(report)

        assertEquals(WritingTone.entries.size, firstRun.size)
        assertTrue(firstRun.values.all { it.outputs.size == 10 })
        assertTrue(
            "Blind generalization v3 failed. See ${reportFile.absolutePath}. Failures: $failures",
            scores.values.all { it == 8 },
        )
    }

    private fun rewriteEachLine(tone: WritingTone): RewriteResult {
        val outputs = sourceLines.map { LocalWritingAssistant.suggest(it, tone) }
        return RewriteResult(outputs.joinToString(" "), outputs)
    }

    private fun correctnessPasses(text: String): Boolean {
        val lower = text.lowercase()
        return listOf(
            "nobody have", "wasnt", " dont ", "everyones", " its pretty", " theres ",
            " thats ", "sorta", "??",
        ).none(lower::contains) &&
            !Regex("(?i)\\bpermit is [^.!?]+,\\s+don't\\b").containsMatchIn(text) &&
            !Regex("(?i)\\bMarch 14, 2027 at\\b").containsMatchIn(text)
    }

    private fun punctuationAndFormatPass(text: String): Boolean =
        !Regex("[ \\t]{2,}").containsMatchIn(text) &&
            !Regex("\\s+[,.;!?]").containsMatchIn(text) &&
            !Regex("[!?]{2,}").containsMatchIn(text) &&
            text.trim().lastOrNull()?.let { it in ".!?" } == true

    private fun protectedFactsPass(text: String): Boolean = listOf(
        "Dr. Lena Ortiz", "March 14, 2027", "6:30 a.m.", "47 nest boxes",
        "Cedar Point Marsh", "39", "MB-20491", "fieldteam@marshwatch.org", "72 hours",
        "https://marshwatch.org/checkin", "8 damaged latches",
        "\"do not enter the north blind\"", "Samir Patel", "2 vans", "Gate C",
        "18 volunteers", "No chicks were handled", "May 3, 2027", "$2,480",
        "120 meters", "Maya Chen", "R-17B", "\"return before sunset\"", "4:45 p.m.",
    ).all(text::contains)

    private fun meaningPass(text: String): Boolean = listOf(
        "survey", "route map", "crew", "permit", "report", "ranger", "park", "inspection",
        "mesh", "label", "pickup",
    ).all { text.contains(it, ignoreCase = true) }

    private fun constraintsPass(text: String): Boolean {
        val lower = text.lowercase()
        val limit = lower.contains("18 volunteers") && listOf(
            "hard limit", "no exceptions", "must not", "cannot", "can't",
        ).any(lower::contains)
        return text.contains("\"do not enter the north blind\"") &&
            text.contains("No chicks were handled") && limit && lower.contains("72 hours")
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

    private fun tonePasses(tone: WritingTone, result: RewriteResult, proofread: String): Boolean {
        val lower = result.text.lowercase()
        val hostilePhrases = listOf(
            "wasn't paying attention", "careless mistakes", "deadlines don't matter",
            "no excuse for messing", "rules are optional", "stop inviting random people",
            "bothered to read", "insanely expensive", "too much to ask",
        )
        val hostileCount = hostilePhrases.count(lower::contains)
        val hedges = listOf("if you can maybe", "i guess", "sorta", "too much to ask")
        val ratio = wordEditRatio(stripStockFrames(proofread), stripStockFrames(result.text))
        return when (tone) {
            WritingTone.PROOFREAD -> ratio <= 0.12 && hostileCount >= 7
            WritingTone.IMPROVE -> ratio >= 0.05 && hostileCount <= 3
            WritingTone.REPHRASE -> ratio >= 0.18 &&
                result.text.length in (proofread.length * 7 / 10)..(proofread.length * 13 / 10)
            WritingTone.SHORTEN -> wordCount(result.text) <= wordCount(proofread) * 85 / 100
            WritingTone.FRIENDLY -> hostileCount == 0 && listOf(
                "please", "thank", "appreciate", "kindly", "together",
            ).any(lower::contains)
            WritingTone.FORMAL -> hostileCount == 0 && listOf(
                "wasn't", "don't", "it's", "there's", "that's", "sorta", "honestly", "i guess",
            ).none(lower::contains)
            WritingTone.PROFESSIONAL -> hostileCount == 0 && listOf(
                "honestly", "i guess", "sorta", "insanely",
            ).none(lower::contains)
            WritingTone.DIPLOMATIC -> hostileCount == 0 && listOf(
                "please", "concern", "review", "address", "clarify",
            ).any(lower::contains)
            WritingTone.EMPATHETIC -> hostileCount == 0 && listOf(
                "understand", "recognize", "appreciate", "acknowledge", "frustrat", "difficult",
            ).any(lower::contains)
            WritingTone.COOPERATIVE -> hostileCount == 0 && listOf(3, 4, 9).all { index ->
                Regex("(?i)^(?:please|could you)\\s+(?:email|use|label)\\b")
                    .containsMatchIn(result.outputs[index])
            }
            WritingTone.ASSERTIVE -> hostileCount == 0 && hedges.none(lower::contains)
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
            WritingTone.COLLEGE -> hostileCount == 0 && ratio >= 0.05
            WritingTone.CAVEMAN -> hostileCount == 0 && hedges.none(lower::contains) &&
                wordCount(result.text) <= wordCount(proofread) * 85 / 100 &&
                result.outputs.all { output ->
                    val sentences = sentences(output)
                    sentences.size <= 4 && sentences.all { sentence ->
                        wordCount(sentence) <= 12 || hasDenseProtectedFact(sentence)
                    }
                }
        }
    }

    private fun hasDenseProtectedFact(sentence: String): Boolean = listOf(
        "http", "@", "March 14, 2027", "May 3, 2027", "$2,480",
        "\"do not enter the north blind\"", "\"return before sunset\"",
    ).any(sentence::contains)

    private fun stripStockFrames(text: String): String = Regex(
        "(?i)(?:Clearly stated:|Put another way:|Hi!|Please note:|For clarity:|" +
            "A balanced approach:|I understand\\.|Let's work together\\.|To be clear:)\\s*",
    ).replace(text, "")

    private fun normalized(text: String): String = text.lowercase().replace(Regex("\\s+"), " ").trim()
    private fun wordCount(text: String): Int = words(text).size
    private fun words(text: String): List<String> = Regex("[A-Za-z0-9$%./?=&@'-]+")
        .findAll(text.lowercase()).map { it.value }.toList()
    private fun sentences(text: String): List<String> {
        val protectedHonorifics = Regex("\\b(Dr|Mr|Ms|Mrs|Prof)\\.")
            .replace(text, "$1\uE100")
        return Regex("(?<=[.!?])\\s+").split(protectedHonorifics)
            .map { it.replace('\uE100', '.') }
            .filter(String::isNotBlank)
    }

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

    private data class RewriteResult(val text: String, val outputs: List<String>)
}
