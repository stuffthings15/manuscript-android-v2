package com.echolink.mobile

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class BlindToneGeneralizationV6Test {
    // Freeze this corpus and rubric before the first execution. Never tune either to observed output.
    private val echoLinkAndroidDir: Path by lazy {
        val cwd = Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize()
        if (cwd.fileName?.toString() == "app") cwd.parent else cwd
    }

    private data class Check(val name: String, val passed: Boolean, val detail: String = "")

    private val expectedTones = setOf(
        "PROOFREAD", "IMPROVE", "REPHRASE", "SHORTEN", "FRIENDLY", "FORMAL",
        "PROFESSIONAL", "ACADEMIC", "CONVERSATIONAL", "CONCISE", "DETAILED",
        "PERSUASIVE", "CONFIDENT", "DIPLOMATIC", "EMPATHETIC", "CASUAL",
        "TECHNICAL", "SIMPLIFIED", "HIGH_SCHOOL", "COLLEGE", "COOPERATIVE",
        "ASSERTIVE", "CAVEMAN"
    )

    private val factPatterns = listOf(
        Regex("\"[^\"]+\""),
        Regex("https?://[A-Za-z0-9._/~:-]*[A-Za-z0-9_~/:-]"),
        Regex("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}"),
        Regex("\\b[A-Z]{2,}(?:-[A-Z0-9]+)+\\b"),
        Regex("\\b\\d{4}-\\d{2}-\\d{2}\\b"),
        Regex("\\b\\d{2}:\\d{2} UTC\\b"),
        Regex("\\b\\d+ (?:crates?|pumps?|valves?|filters?|carts?|scanners?|frames?)\\b", RegexOption.IGNORE_CASE)
    )

    private val knownErrors = Regex(
        "\\b(?:teh|dont|recieve|wont|seperate|shoudl|isnt|doesnt|cant|alot|becuase)\\b",
        RegexOption.IGNORE_CASE
    )
    private val hostile = Regex(
        "\\b(?:lazy|idiot(?:ic)?|incompetent|pathetic|useless|clueless|careless|ridiculous|stupid|sloppy|hopeless)\\b|\\bas usual\\b|apparently reading",
        RegexOption.IGNORE_CASE
    )
    private val propertyThreat = Regex(
        "\\bI\\s+(?:will|'ll)\\s+(?:take|keep|hide|lock|throw|damage|confiscate)\\b",
        RegexOption.IGNORE_CASE
    )
    private val distress = Regex("\\b(?:afraid|terrified|worried|anxious|upset|distressed|scared|panic)\\b", RegexOption.IGNORE_CASE)
    private val uncertainty = Regex("\\b(?:uncertain|unknown|might|could|possibly|not confirmed|not yet known)\\b", RegexOption.IGNORE_CASE)
    private val assignment = Regex(
        "^\\s*([A-Z][a-z]+)\\s+(must not|may not|cannot|must|should|may|can)\\s+([A-Za-z]+)",
        RegexOption.IGNORE_CASE
    )

    @Test
    fun blindToneGeneralizationV6() {
        val lines = requireNotNull(
            javaClass.classLoader?.getResourceAsStream("blind-tone-generalization-v6.txt")
        ) { "Missing frozen V6 corpus" }.bufferedReader(StandardCharsets.UTF_8).use { reader ->
            reader.readLines().filter { it.isNotBlank() }
        }
        assertEquals("The frozen V6 corpus must contain exactly eight lines", 8, lines.size)

        val tones = WritingTone.values().toList()
        assertEquals("WritingTone changed; review without modifying the frozen rubric", expectedTones, tones.map { it.name }.toSet())

        val report = StringBuilder()
        val failures = mutableListOf<String>()
        report.append("Blind Tone Generalization V6\n")
            .append("FROZEN BEFORE FIRST RUN: corpus and rubric must not be tuned to outputs.\n")
            .append("cases=").append(lines.size * tones.size).append(" calls=").append(lines.size * tones.size * 2).append("\n\n")

        lines.forEachIndexed { lineIndex, source ->
            tones.forEach { tone ->
                var first: String? = null
                var second: String? = null
                val checks = mutableListOf<Check>()
                try {
                    first = LocalWritingAssistant.suggest(source, tone)
                } catch (error: Throwable) {
                    checks += Check("first call", false, "${error.javaClass.simpleName}: ${error.message}")
                }
                try {
                    second = LocalWritingAssistant.suggest(source, tone)
                } catch (error: Throwable) {
                    checks += Check("second call", false, "${error.javaClass.simpleName}: ${error.message}")
                }

                if (first != null && second != null) {
                    checks += Check("determinism", first == second, "both calls must match exactly")
                    checks += evaluate(source, first!!, tone)
                }

                val caseName = "line ${lineIndex + 1} / ${tone.name}"
                if (checks.none { !it.passed }) {
                    report.append("PASS ").append(caseName).append('\n')
                } else {
                    report.append("FAIL ").append(caseName).append('\n')
                    failures += caseName + ": " + checks.filterNot { it.passed }.joinToString { it.name }
                }
                report.append("SOURCE<<<\n").append(source).append("\n>>>SOURCE\n")
                    .append("FIRST_LENGTH=").append(first?.length ?: -1).append("\nFIRST<<<\n")
                    .append(first ?: "").append("\n>>>FIRST\n")
                    .append("SECOND_LENGTH=").append(second?.length ?: -1).append("\nSECOND<<<\n")
                    .append(second ?: "").append("\n>>>SECOND\n")
                checks.forEach { check ->
                    report.append("CHECK ").append(if (check.passed) "PASS " else "FAIL ")
                        .append(check.name)
                    if (check.detail.isNotEmpty()) report.append(" -- ").append(check.detail)
                    report.append('\n')
                }
                report.append('\n')
            }
        }

        val reportPath = echoLinkAndroidDir.resolve("app/build/reports/blind-tone-generalization-v6.txt")
        Files.createDirectories(reportPath.parent)
        Files.write(reportPath, report.toString().toByteArray(StandardCharsets.UTF_8))
        if (failures.isNotEmpty()) {
            fail("${failures.size} V6 cases failed; exact outputs and checks: $reportPath\n${failures.joinToString("\n")}")
        }
    }

    private fun evaluate(source: String, output: String, tone: WritingTone): List<Check> {
        val checks = mutableListOf<Check>()
        fun check(name: String, passed: Boolean, detail: String = "") {
            checks += Check(name, passed, detail)
        }

        val outside = outsideQuotes(output)
        check("nonblank", output.isNotBlank())
        check("trimmed format", output == output.trim() && !output.contains("  "))
        check("terminal punctuation", Regex("[.!?][\"']?$", RegexOption.DOT_MATCHES_ALL).containsMatchIn(output.trim()))
        check("balanced exact quotes", output.count { it == '\"' } % 2 == 0)
        check("punctuation spacing outside quotes", !Regex("\\s+[,.!?;:]").containsMatchIn(outside))
        check("spelling outside quotes", !knownErrors.containsMatchIn(outside))
        check(
            "basic grammar outside quotes",
            !Regex("\\bi\\b").containsMatchIn(outside) &&
                !Regex("\\b(?:you|we|they) is\\b", RegexOption.IGNORE_CASE).containsMatchIn(outside)
        )

        protectedFacts(source).forEach { fact ->
            check("protected fact [$fact]", output.contains(fact), "must remain exact")
        }

        val sourceAssignment = requireNotNull(assignment.find(source)) { "Corpus line lacks a parseable actor duty: $source" }
        val actor = sourceAssignment.groupValues[1]
        val modal = sourceAssignment.groupValues[2].lowercase(Locale.ROOT)
        val action = sourceAssignment.groupValues[3].lowercase(Locale.ROOT)
        val actionRoot = action.take(if (action.length <= 5) 4 else 5)
        check("named actor", Regex("\\b${Regex.escape(actor)}\\b").containsMatchIn(output), "assigned actor must not be lost or swapped")
        check("assigned action", Regex("\\b${Regex.escape(actionRoot)}[A-Za-z]*\\b", RegexOption.IGNORE_CASE).containsMatchIn(outside))
        check("duty scope", dutyPreserved(modal, outside), "obligation, permission, recommendation, or prohibition changed")

        signalCategories(source).forEach { category ->
            check("constraint $category", signalCategories(output).contains(category), "scope, limit, exception, or uncertainty was lost")
        }
        check("substantive change", normalize(output) != normalize(source), "output only echoed the input")

        val isNeutral = !hostile.containsMatchIn(outside) && !propertyThreat.containsMatchIn(outside)
        when (tone) {
            WritingTone.PROOFREAD -> Unit // Proofreading may correctly preserve the speaker's hostility.
            WritingTone.IMPROVE -> check("improved neutral tone", isNeutral)
            WritingTone.REPHRASE -> check("substantive rephrase", normalize(output) != normalize(mechanicalCorrection(source)))
            WritingTone.SHORTEN -> check("shorter wording", wordCount(output) < wordCount(mechanicalCorrection(source)))
            WritingTone.FRIENDLY -> {
                check("friendly neutral tone", isNeutral)
                check("courteous wording", Regex("\\b(?:please|thanks|thank you|appreciate|kindly|glad|happy to|could you)\\b", RegexOption.IGNORE_CASE).containsMatchIn(outside))
            }
            WritingTone.FORMAL -> {
                check("formal neutral tone", isNeutral)
                check("no contractions", !Regex("\\b[A-Za-z]+['’][A-Za-z]+\\b").containsMatchIn(outside))
            }
            WritingTone.PROFESSIONAL -> check("professional neutral tone", isNeutral)
            WritingTone.DIPLOMATIC -> {
                check("diplomatic neutral tone", isNeutral)
                check("softened delivery", Regex("\\b(?:please|could|would|may|consider|appreciate|understand|request|kindly|for clarity)\\b", RegexOption.IGNORE_CASE).containsMatchIn(outside))
            }
            WritingTone.EMPATHETIC -> {
                check("empathetic neutral tone", isNeutral)
                if (distress.containsMatchIn(source)) {
                    check("contextual empathy", Regex("\\b(?:understand|recognize|sorry|appreciate|concern|worried|afraid|distress|reassure)\\w*\\b", RegexOption.IGNORE_CASE).containsMatchIn(outside))
                } else {
                    check("no invented distress", !Regex("\\b(?:you are|you're) (?:afraid|terrified|worried|anxious|upset|distressed|scared)\\b", RegexOption.IGNORE_CASE).containsMatchIn(outside))
                }
            }
            WritingTone.COOPERATIVE -> {
                check("cooperative neutral tone", isNeutral)
                check("cooperative wording without forced we", Regex("\\b(?:please|together|coordinate|collaborate|support|assist|could|let's|thank|work with)\\b", RegexOption.IGNORE_CASE).containsMatchIn(outside))
            }
            WritingTone.ASSERTIVE -> {
                check("assertive neutral tone", isNeutral)
                check("direct delivery", Regex("\\b(?:must|need|needs|required|cannot|do not|only|no more than|at most)\\b", RegexOption.IGNORE_CASE).containsMatchIn(outside))
                check("no courtesy hedge", !Regex("\\b(?:maybe|perhaps|if possible|when convenient|I think)\\b", RegexOption.IGNORE_CASE).containsMatchIn(outside))
                if (uncertainty.containsMatchIn(source)) check("assertive factual uncertainty", uncertainty.containsMatchIn(outside))
            }
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
            WritingTone.COLLEGE -> {
                check("new master-plan tone neutral delivery", isNeutral)
                check("new master-plan tone changes wording", normalize(output) != normalize(source))
            }
            WritingTone.CAVEMAN -> {
                check("caveman neutral tone", isNeutral)
                check("caveman grammatical predicate", Regex("\\b(?:must|may|can(?:not)?|should|will|is|are|has|have|needs?|do|does)\\b", RegexOption.IGNORE_CASE).containsMatchIn(outside))
                val densityAllowance = protectedFacts(source).size + signalCategories(source).size
                check("caveman concise with density allowance", wordCount(output) <= wordCount(source) + densityAllowance,
                    "dense facts allow $densityAllowance extra words")
                check("caveman no filler", !Regex("\\b(?:in order to|it is important to note|please be advised that)\\b", RegexOption.IGNORE_CASE).containsMatchIn(outside))
            }
        }
        return checks
    }

    private fun protectedFacts(text: String): Set<String> =
        factPatterns.flatMap { pattern -> pattern.findAll(text).map { it.value }.toList() }.toSet()

    private fun outsideQuotes(text: String): String = text.replace(Regex("\"[^\"]*\""), "\"Q\"")

    private fun normalize(text: String): String = text.trim().replace(Regex("\\s+"), " ").lowercase(Locale.ROOT)

    private fun wordCount(text: String): Int = Regex("[A-Za-z0-9]+(?:['’-][A-Za-z0-9]+)*").findAll(text).count()

    private fun dutyPreserved(modal: String, output: String): Boolean {
        val pattern = when (modal) {
            "must not", "may not", "cannot" -> "\\b(?:must not|may not|cannot|can't|do not|prohibited|forbidden|not allowed)\\b"
            "must" -> "\\b(?:must|required|has to|needs to|is to|responsible for)\\b"
            "should" -> "\\b(?:should|ought to|recommended|is advised)\\b"
            else -> "\\b(?:may|can|allowed|authorized|permitted)\\b"
        }
        return Regex(pattern, RegexOption.IGNORE_CASE).containsMatchIn(output)
    }

    private fun signalCategories(text: String): Set<String> {
        val categories = linkedSetOf<String>()
        fun add(name: String, pattern: String) {
            if (Regex(pattern, RegexOption.IGNORE_CASE).containsMatchIn(text)) categories += name
        }
        add("negation", "\\b(?:not|no|never|cannot|can't|don't|mustn't|prohibited|forbidden)\\b")
        add("limit", "\\b(?:only|no more than|at most|at least|exactly|limit|maximum|minimum)\\b")
        add("exception", "\\b(?:unless|except|other than)\\b")
        add("condition/deadline", "\\b(?:if|when|until|before|after|by|no later than)\\b")
        add("uncertainty", "\\b(?:uncertain|unknown|might|could|possibly|not confirmed|not yet known)\\b")
        return categories
    }

    private fun mechanicalCorrection(text: String): String {
        var corrected = text
        val replacements = mapOf(
            "teh" to "the", "dont" to "don't", "recieve" to "receive", "wont" to "won't",
            "seperate" to "separate", "shoudl" to "should", "isnt" to "isn't",
            "doesnt" to "doesn't", "cant" to "can't", "alot" to "a lot", "becuase" to "because"
        )
        replacements.forEach { (wrong, right) ->
            corrected = Regex("\\b${Regex.escape(wrong)}\\b", RegexOption.IGNORE_CASE).replace(corrected, right)
        }
        return corrected
    }
}
