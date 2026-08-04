package com.echolink.mobile

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class BlindToneGeneralizationV7Test {
    // Freeze this unseen corpus and its rubric before the first run; never tune them to generated output.
    private val echoLinkAndroidDir: Path by lazy {
        val cwd = Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize()
        if (cwd.fileName?.toString() == "app") cwd.parent else cwd
    }

    private data class Check(val label: String, val passed: Boolean, val detail: String = "")

    private val expectedTones = setOf(
        "PROOFREAD", "IMPROVE", "REPHRASE", "SHORTEN", "FRIENDLY", "FORMAL",
        "PROFESSIONAL", "ACADEMIC", "CONVERSATIONAL", "CONCISE", "DETAILED",
        "PERSUASIVE", "CONFIDENT", "DIPLOMATIC", "EMPATHETIC", "CASUAL",
        "TECHNICAL", "SIMPLIFIED", "HIGH_SCHOOL", "COLLEGE", "COOPERATIVE",
        "ASSERTIVE", "CAVEMAN"
    )
    private val assignment = Regex(
        "^\\s*([A-Z][a-z]+)\\s+(must not|may not|cannot|must|should|may|can)\\s+([A-Za-z]+)",
        RegexOption.IGNORE_CASE
    )
    private val protectedPatterns = listOf(
        Regex("\"[^\"]+\""),
        Regex("https?://[A-Za-z0-9._/~:-]*[A-Za-z0-9_~/:-]"),
        Regex("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}"),
        Regex("\\b[A-Z]{2,}(?:-[A-Z0-9]+)+\\b"),
        Regex("\\b\\d{4}-\\d{2}-\\d{2}\\b"),
        Regex("\\b\\d{2}:\\d{2} UTC\\b"),
        Regex("\\b\\d+ (?:mL|lux|scans?|sources?|minutes?|°C|frames?|samples?)\\b")
    )
    private val knownErrors = Regex(
        "\\b(?:teh|recieve|dont|wont|seperate|shoudl|cant|isnt|doesnt|alot|becuase)\\b",
        RegexOption.IGNORE_CASE
    )
    private val hostility = Regex(
        "\\b(?:idiot(?:ic)?|useless|careless|pathetic|sloppy|stupid|clueless|incompetent)\\b|" +
            "\\bas usual\\b|apparently this is too complicated|messed it up|proving you never learn|" +
            "\\bI(?:'ll| will) (?:erase|take|hide|lock|destroy|damage)\\b|" +
            "\\b(?:won't|will not) tolerate\\b|\\bmake(?:s)? it worse\\b|" +
            "\\b(?:can't|cannot) follow (?:easy|simple) rules\\b",
        RegexOption.IGNORE_CASE
    )
    private val distress = Regex("\\b(?:afraid|terrified|worried|anxious|upset|distressed|scared|panic)\\b", RegexOption.IGNORE_CASE)
    private val uncertainty = Regex(
        "\\b(?:uncertain|unknown|might|could|possibly|not confirmed|isn't confirmed|" +
            "not yet known)\\b",
        RegexOption.IGNORE_CASE
    )

    @Test
    fun frozenUnseenV7ToneMatrix() {
        val lines = requireNotNull(javaClass.classLoader?.getResourceAsStream("blind-tone-generalization-v7.txt")) {
            "Missing frozen V7 corpus"
        }.bufferedReader(StandardCharsets.UTF_8).use { it.readLines().filter(String::isNotBlank) }
        assertEquals("V7 must remain an eight-line frozen corpus", 8, lines.size)

        val tones = WritingTone.values().toList()
        assertEquals("WritingTone changed; review without editing the frozen V7 rubric", expectedTones, tones.map { it.name }.toSet())

        val failures = mutableListOf<String>()
        val report = StringBuilder()
        report.append("Blind Tone Generalization V7\n")
            .append("FROZEN BEFORE FIRST RUN: corpus and rubric are independent of generated output.\n")
            .append("domain=museum conservation cases=").append(lines.size * tones.size)
            .append(" calls=").append(lines.size * tones.size * 2).append("\n\n")

        lines.forEachIndexed { index, source ->
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
                    checks += Check("deterministic calls", first == second)
                    checks += evaluate(source, first!!, tone)
                }

                val caseName = "line ${index + 1} / ${tone.name}"
                val failed = checks.filterNot { it.passed }
                report.append(if (failed.isEmpty()) "PASS " else "FAIL ").append(caseName).append('\n')
                if (failed.isNotEmpty()) failures += "$caseName: ${failed.joinToString { it.label }}"
                report.append("SOURCE<<<\n").append(source).append("\n>>>SOURCE\n")
                    .append("FIRST_LENGTH=").append(first?.length ?: -1).append("\nFIRST<<<\n")
                    .append(first ?: "").append("\n>>>FIRST\n")
                    .append("SECOND_LENGTH=").append(second?.length ?: -1).append("\nSECOND<<<\n")
                    .append(second ?: "").append("\n>>>SECOND\n")
                checks.forEach { check ->
                    report.append("CHECK ").append(if (check.passed) "PASS " else "FAIL ").append(check.label)
                    if (check.detail.isNotEmpty()) report.append(" -- ").append(check.detail)
                    report.append('\n')
                }
                report.append('\n')
            }
        }

        val reportPath = echoLinkAndroidDir.resolve("app/build/reports/blind-tone-generalization-v7.txt")
        Files.createDirectories(reportPath.parent)
        Files.write(reportPath, report.toString().toByteArray(StandardCharsets.UTF_8))
        if (failures.isNotEmpty()) {
            fail("${failures.size} V7 cases failed; exact calls and checks are in $reportPath\n${failures.joinToString("\n")}")
        }
    }

    private fun evaluate(source: String, output: String, tone: WritingTone): List<Check> {
        val checks = mutableListOf<Check>()
        fun record(label: String, passed: Boolean, detail: String = "") {
            checks += Check(label, passed, detail)
        }

        val prose = outsideQuotes(output)
        record("nonblank", output.isNotBlank())
        record("trimmed single-space format", output == output.trim() && !output.contains("  "))
        record("capitalized prose", output.firstOrNull { it.isLetter() }?.isUpperCase() == true)
        record("terminal punctuation", Regex("[.!?][\"']?$", RegexOption.DOT_MATCHES_ALL).containsMatchIn(output.trim()))
        record("balanced quotes", output.count { it == '\"' } % 2 == 0)
        record("punctuation spacing outside quotes", !Regex("\\s+[,.!?;:]").containsMatchIn(prose))
        record("spelling outside quotes", !knownErrors.containsMatchIn(prose))
        record(
            "basic grammar outside quotes",
            !Regex("\\bi\\b").containsMatchIn(prose) &&
                !Regex(
                    "\\b(?:you|we|they) is\\b|\\b(?:he|she|it) don't\\b",
                    RegexOption.IGNORE_CASE
                ).containsMatchIn(prose)
        )
        record(
            "no detached contrast modifier fragment",
            !Regex(
                "(?i)(?:^|[.!?]\\s+)However,\\s+(?:by\\s+(?:no more than|at most)|" +
                    "at most|no more than|only if|unless|except|before|after)\\b",
            ).containsMatchIn(prose)
        )

        protectedFacts(source).forEach { fact ->
            record("exact protected fact [$fact]", output.contains(fact), "IDs, dates, times, contacts, URLs, quantities, and quotes are immutable")
        }

        val sourceDuty = requireNotNull(assignment.find(source)) { "Frozen corpus has an invalid actor duty" }
        val actor = sourceDuty.groupValues[1]
        val modal = sourceDuty.groupValues[2].lowercase(Locale.ROOT)
        val action = sourceDuty.groupValues[3].lowercase(Locale.ROOT)
        val actionRoot = action.take(if (action.length <= 5) 4 else 5)
        record("named actor retained", Regex("\\b${Regex.escape(actor)}\\b").containsMatchIn(output))
        record("assigned action retained", Regex("\\b${Regex.escape(actionRoot)}[A-Za-z]*\\b", RegexOption.IGNORE_CASE).containsMatchIn(prose))
        record("duty strength retained", dutyPreserved(modal, prose))
        semanticSignals(source).forEach { signal ->
            record("meaning signal [$signal]", signal in semanticSignals(output), "limits, conditions, negation, exceptions, and uncertainty must survive")
        }

        val correctedOnly = mechanicalCorrection(source)
        record("changed from source", normalized(output) != normalized(source))
        if (tone != WritingTone.PROOFREAD) {
            record("real rewrite beyond corrections", normalized(output) != normalized(correctedOnly))
        }

        val neutral = !hostility.containsMatchIn(prose)
        when (tone) {
            WritingTone.PROOFREAD -> Unit // Correct proofreading may preserve hostile wording.
            WritingTone.IMPROVE -> record("improved neutral delivery", neutral)
            WritingTone.REPHRASE -> Unit
            WritingTone.SHORTEN -> {
                record("shortened neutral delivery", neutral)
                record("meaningful word compression", wordCount(output) < wordCount(correctedOnly))
                record("meaningful character compression", output.length < correctedOnly.length)
            }
            WritingTone.FRIENDLY -> {
                record("friendly neutral delivery", neutral)
                record("courtesy", has(prose, "\\b(?:please|thanks|thank you|appreciate|kindly|glad|happy to|could you)\\b"))
            }
            WritingTone.FORMAL -> {
                record("formal neutral delivery", neutral)
                record(
                    "formal avoids contractions",
                    !Regex(
                        "\\b[A-Za-z]+n['’]t\\b|" +
                            "\\b(?:I|you|we|they|he|she|it|that|there|what|who)" +
                            "['’](?:m|re|ve|ll|d|s)\\b",
                        RegexOption.IGNORE_CASE
                    ).containsMatchIn(prose)
                )
            }
            WritingTone.PROFESSIONAL -> {
                record("professional neutral delivery", neutral)
                record("professional punctuation", !Regex("[!?]{2,}").containsMatchIn(prose))
            }
            WritingTone.DIPLOMATIC -> {
                record("diplomatic neutral delivery", neutral)
                record("diplomatic softening", has(prose, "\\b(?:please|could|would|may|consider|appreciate|understand|request|kindly|for clarity)\\b"))
            }
            WritingTone.EMPATHETIC -> {
                record("empathetic neutral delivery", neutral)
                if (distress.containsMatchIn(source)) {
                    record("contextual empathy", has(prose, "\\b(?:understand|recognize|sorry|appreciate|concern|worri|anxi|reassur)\\w*\\b"))
                } else {
                    record("no invented distress", !has(prose, "\\b(?:you are|you're) (?:afraid|terrified|worried|anxious|upset|distressed|scared)\\b"))
                }
            }
            WritingTone.COOPERATIVE -> {
                record("cooperative neutral delivery", neutral)
                record("cooperation without forced we", has(prose, "\\b(?:please|together|coordinate|collaborate|support|assist|could|let's|thank|work with)\\b"))
            }
            WritingTone.ASSERTIVE -> {
                record("assertive neutral delivery", neutral)
                record("assertive directness", has(prose, "\\b(?:must|need|needs|required|cannot|do not|only|no more than|at most|at least|exactly)\\b"))
                record("no social hedging", !has(prose, "\\b(?:maybe|perhaps|if possible|when convenient|I think)\\b"))
                if (uncertainty.containsMatchIn(source)) record("factual uncertainty retained", uncertainty.containsMatchIn(prose))
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
                record("new master-plan tone neutral delivery", neutral)
                record(
                    "new master-plan tone changes wording",
                    normalized(output) != normalized(correctedOnly),
                )
            }
            WritingTone.CAVEMAN -> {
                record("caveman neutral delivery", neutral)
                record("caveman grammatical predicate", has(prose, "\\b(?:must|may|can(?:not)?|should|will|is|are|has|have|needs?|do|does)\\b"))
                record("caveman concise and fact-dense", wordCount(output) <= wordCount(correctedOnly) && output.length <= correctedOnly.length)
                record("caveman avoids filler", !has(prose, "\\b(?:in order to|it is important to note|please be advised that)\\b"))
            }
        }
        return checks
    }

    private fun protectedFacts(text: String): Set<String> =
        protectedPatterns.flatMap { regex -> regex.findAll(text).map { it.value }.toList() }.toSet()

    private fun outsideQuotes(text: String): String = text.replace(Regex("\"[^\"]*\""), "\"Q\"")

    private fun normalized(text: String): String = text.trim().replace(Regex("\\s+"), " ").lowercase(Locale.ROOT)

    private fun wordCount(text: String): Int = Regex("[A-Za-z0-9]+(?:['’-][A-Za-z0-9]+)*").findAll(text).count()

    private fun has(text: String, pattern: String): Boolean = Regex(pattern, RegexOption.IGNORE_CASE).containsMatchIn(text)

    private fun dutyPreserved(modal: String, output: String): Boolean {
        val pattern = when (modal) {
            "must not", "may not", "cannot" -> "\\b(?:must not|may not|cannot|can't|do not|prohibited|forbidden|not allowed)\\b"
            "must" -> "\\b(?:must|required|has to|needs to|is to|responsible for)\\b"
            "should" -> "\\b(?:should|ought to|recommended|is advised)\\b"
            else -> "\\b(?:may|can|allowed|authorized|permitted)\\b"
        }
        return has(output, pattern)
    }

    private fun semanticSignals(text: String): Set<String> {
        val signals = linkedSetOf<String>()
        fun add(label: String, pattern: String) { if (has(text, pattern)) signals += label }
        add(
            "negation",
            "\\b(?:not|never|cannot|can't|don't|isn't|aren't|wasn't|weren't|doesn't|" +
                "didn't|hasn't|haven't|won't|mustn't|prohibited|forbidden)\\b|" +
                "\\bno\\b(?!\\s+more than)"
        )
        add("limit", "\\b(?:only|no more than|at most|at least|exactly|limit|maximum|minimum)\\b")
        add("exception", "\\b(?:unless|except|other than)\\b")
        add("condition/deadline", "\\b(?:if|when|until|before|after|by|no later than)\\b")
        add(
            "uncertainty",
            "\\b(?:uncertain|unknown|might|could|possibly|not confirmed|isn't confirmed|" +
                "not yet known)\\b"
        )
        return signals
    }

    private fun mechanicalCorrection(text: String): String {
        var corrected = text
        mapOf(
            "teh" to "the", "recieve" to "receive", "dont" to "don't", "wont" to "won't",
            "seperate" to "separate", "shoudl" to "should", "cant" to "can't",
            "isnt" to "isn't", "doesnt" to "doesn't", "alot" to "a lot", "becuase" to "because"
        ).forEach { (wrong, right) ->
            corrected = Regex("\\b${Regex.escape(wrong)}\\b", RegexOption.IGNORE_CASE).replace(corrected, right)
        }
        return corrected
    }
}
