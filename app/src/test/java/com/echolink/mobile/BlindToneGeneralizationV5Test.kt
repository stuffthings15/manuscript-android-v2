package com.echolink.mobile

import java.io.File
import java.nio.charset.StandardCharsets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BlindToneGeneralizationV5Test {
    private data class BlindCase(val id: String, val line: String)

    private data class Assignment(val actor: String, val dutyStem: String)

    private data class CheckResult(val name: String, val passed: Boolean, val detail: String)

    private val assignments = mapOf(
        "V5-01" to listOf(Assignment("Maya", "releas"), Assignment("Luis", "verif")),
        "V5-02" to listOf(Assignment("Rina", "said"), Assignment("Omar", "inspect")),
        "V5-03" to listOf(Assignment("Priya", "email"), Assignment("Chen", "open")),
        "V5-04" to listOf(Assignment("Derek", "move"), Assignment("Ana", "sign")),
        "V5-05" to listOf(Assignment("Nora", "load"), Assignment("Eli", "scan")),
        "V5-06" to listOf(Assignment("Samir", "seal"), Assignment("Jo", "dispatch")),
    )

    private val facts = mapOf(
        "V5-01" to listOf("WH-482", "2026-08-14", "07:30", "QA-9"),
        "V5-02" to listOf("4", "Bay-12", "MX-771"),
        "V5-03" to listOf("dock.alerts@example.com", "16:45", "https://warehouse.example/door-7", "SR-208"),
        "V5-04" to listOf("3", "Zone-C", "2026-09-02", "HAZ-44", "SC-6", "20"),
        "V5-05" to listOf("8", "TR-55", "PK-903", "05:20", "Gate-2"),
        "V5-06" to listOf("CN-610", "2026-10-11", "2 kg", "CAPA-31"),
    )

    private val knownErrors = listOf(
        Regex("\\brealease\\b", RegexOption.IGNORE_CASE),
        Regex("\\bdont\\b", RegexOption.IGNORE_CASE),
        Regex("\\bmore then\\b", RegexOption.IGNORE_CASE),
        Regex("\\bis broke\\b", RegexOption.IGNORE_CASE),
        Regex("\\bPriya need\\b", RegexOption.IGNORE_CASE),
        Regex("\\bDerek only move\\b", RegexOption.IGNORE_CASE),
        Regex("\\bisnt\\b", RegexOption.IGNORE_CASE),
        Regex("\\bEli scan\\b", RegexOption.IGNORE_CASE),
        Regex("\\bJo dont\\b", RegexOption.IGNORE_CASE),
        Regex("\\bLuis only verify\\b", RegexOption.IGNORE_CASE),
    )

    private val hostileWords = Regex(
        "\\b(idiot|useless|dumb|stupid|ridiculous|messed this up)\\b",
        RegexOption.IGNORE_CASE,
    )

    @Test
    fun everyToneGeneralizesAcrossTwoFrozenWarehouseCases() {
        val cases = loadCases()
        val tones = WritingTone.values().toList()
        assertEquals("The blind matrix is defined for all writing tones", 23, tones.size)
        assertEquals("The frozen blind corpus must contain six cases", 6, cases.size)

        // FROZEN BEFORE FIRST EXECUTION: do not edit this corpus or weaken these checks after seeing results.
        val failures = mutableListOf<String>()
        val report = mutableListOf(
            "Blind tone generalization v5",
            "Corpus: 6 frozen manufacturing/warehouse lines",
            "Matrix: 23 tones x 2 distinct cases, with deterministic repeat calls",
            "Checks per tone: correctness, punctuation, facts, meaning+actor, negation+constraints, determinism, substantive behavior, tone behavior",
            "",
        )

        tones.forEachIndexed { toneIndex, tone ->
            val selected = listOf(cases[toneIndex % cases.size], cases[(toneIndex + 3) % cases.size])
            val first = selected.map { LocalWritingAssistant.suggest(it.line, tone) }
            val repeated = selected.map { LocalWritingAssistant.suggest(it.line, tone) }
            val checks = listOf(
                check("correctness") {
                    first.all { output ->
                        output.isNotBlank() && knownErrors.none {
                            it.containsMatchIn(proseOutsideQuotes(output))
                        }
                    }
                },
                check("punctuation") { first.all(::hasSoundPunctuation) },
                check("facts") {
                    selected.zip(first).all { (case, output) ->
                        facts.getValue(case.id).all { fact -> output.contains(fact, ignoreCase = true) }
                    }
                },
                check("meaning+actor") {
                    selected.zip(first).all { (case, output) -> actorAssignmentsHold(case, output) }
                },
                check("negation+constraints") {
                    selected.zip(first).all { (case, output) -> constraintsHold(case, output) }
                },
                check("determinism") { first == repeated },
                check("substantive behavior") {
                    selected.zip(first).all { (case, output) ->
                        normalize(output) != normalize(case.line) && knownErrors.none {
                            it.containsMatchIn(proseOutsideQuotes(output))
                        }
                    }
                },
                check("tone behavior") {
                    selected.zip(first).all { (case, output) -> toneBehaviorHolds(tone, case, output) }
                },
            )

            report += "[${tone.name}] ${selected.joinToString { it.id }}"
            checks.forEach { result ->
                val status = if (result.passed) "PASS" else "FAIL"
                report += "  $status ${result.name}: ${result.detail}"
                if (!result.passed) failures += "${tone.name}/${result.name}"
            }
            selected.zip(first).forEach { (case, output) -> report += "  ${case.id}: $output" }
            report += ""
        }

        val reportFile = File("build/reports/blind-tone-generalization-v5.txt")
        reportFile.parentFile?.mkdirs()
        reportFile.writeText(report.joinToString(System.lineSeparator()), StandardCharsets.UTF_8)

        assertTrue(
            "Blind v5 failures (${failures.size}); see ${reportFile.path}: ${failures.joinToString()}",
            failures.isEmpty(),
        )
    }

    private fun loadCases(): List<BlindCase> {
        val stream = requireNotNull(javaClass.classLoader?.getResourceAsStream("blind-tone-generalization-v5.txt")) {
            "Missing frozen blind-tone-generalization-v5.txt"
        }
        return stream.bufferedReader(StandardCharsets.UTF_8).useLines { lines ->
            lines.filter { it.isNotBlank() }.map { line ->
                val fields = line.split('\t', limit = 2)
                require(fields.size == 2) { "Malformed frozen blind case" }
                BlindCase(fields[0], fields[1])
            }.toList()
        }
    }

    private fun check(name: String, predicate: () -> Boolean): CheckResult = try {
        val passed = predicate()
        CheckResult(name, passed, if (passed) "generic invariant held" else "generic invariant failed")
    } catch (error: Throwable) {
        CheckResult(name, false, "${error::class.java.simpleName}: ${error.message.orEmpty()}")
    }

    private fun hasSoundPunctuation(output: String): Boolean {
        val text = output.trim()
        if (text.isEmpty()) return false
        val straightQuotesBalanced = text.count { it == '"' } % 2 == 0
        val curlyQuotesBalanced = text.count { it == '“' } == text.count { it == '”' }
        val noBrokenSpacing = !Regex("\\s+[,.;:!?]").containsMatchIn(text)
        val noRepeatedCommas = !text.contains(",,")
        val beginsLikeSentence = text.first().isUpperCase() || text.first() == '"' || text.first() == '“'
        return straightQuotesBalanced && curlyQuotesBalanced && noBrokenSpacing && noRepeatedCommas &&
            beginsLikeSentence && text.last() in ".!?"
    }

    private fun actorAssignmentsHold(case: BlindCase, output: String): Boolean {
        val clauses = output.split(Regex("(?<=[.;!?])\\s+|[;\\n]+"))
        return assignments.getValue(case.id).all { assignment ->
            clauses.any { clause ->
                clause.contains(assignment.actor, ignoreCase = true) &&
                    clause.contains(assignment.dutyStem, ignoreCase = true)
            }
        }
    }

    private fun constraintsHold(case: BlindCase, output: String): Boolean {
        val lower = output.lowercase()
        val keepsNegation = listOf(
            "must not", "do not", "don't", "dont", "cannot", "can't", "isn't", "not ",
            "prohibit", "forbid", "only after", "before", "until",
        ).any(lower::contains)
        val keepsLimit = facts.getValue(case.id).any { fact ->
            fact.any(Char::isDigit) && output.contains(fact, ignoreCase = true)
        }
        val keepsException = listOf("unless", "except", "only if", "when").any(lower::contains)
        val keepsUncertainty = listOf(
            "might", "may", "could", "maybe", "possibly", "uncertain", "potential",
        ).any(lower::contains)
        return keepsNegation && keepsLimit && keepsException && keepsUncertainty
    }

    private fun toneBehaviorHolds(tone: WritingTone, case: BlindCase, output: String): Boolean {
        if (output.isBlank()) return false
        val toneName = tone.name.uppercase()
        if ("PROOFREAD" in toneName) return true
        if (hostileWords.containsMatchIn(proseOutsideQuotes(output))) return false
        if ("COOPERATIVE" in toneName && !actorAssignmentsHold(case, output)) return false
        if ("CAVEMAN" in toneName && !isGrammaticalShortOrDense(case, output)) return false
        return true
    }

    private fun isGrammaticalShortOrDense(case: BlindCase, output: String): Boolean {
        if (
            !hasSoundPunctuation(output) ||
            knownErrors.any { it.containsMatchIn(proseOutsideQuotes(output)) }
        ) {
            return false
        }
        val sourceWords = words(case.line)
        val outputWords = words(output)
        if (outputWords.size <= sourceWords.size) return true

        val semanticAnchors = facts.getValue(case.id) +
            assignments.getValue(case.id).flatMap { listOf(it.actor, it.dutyStem) }
        val outputAnchorCount = semanticAnchors.count { output.contains(it, ignoreCase = true) }
        val sourceAnchorCount = semanticAnchors.count { case.line.contains(it, ignoreCase = true) }
        val outputDensity = outputAnchorCount.toDouble() / outputWords.size.coerceAtLeast(1)
        val sourceDensity = sourceAnchorCount.toDouble() / sourceWords.size.coerceAtLeast(1)
        return outputDensity >= sourceDensity
    }

    private fun words(value: String): List<String> =
        Regex("[A-Za-z0-9@:/.-]+").findAll(value).map { it.value }.toList()

    private fun normalize(value: String): String = value.trim().replace(Regex("\\s+"), " ").lowercase()

    private fun proseOutsideQuotes(value: String): String = Regex(
        "\"[^\"]*\"|“[^”]*”",
    ).replace(value, " ")
}
