package com.echolink.mobile

import java.io.File
import java.util.Locale
import kotlin.math.max
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Fourth blind corpus authored independently of the repository and prior tone corpora.
 *
 * Keep this corpus and rubric fixed after their first execution. Each source line is rewritten
 * independently so facts or tone markers from one line cannot satisfy another line's checks.
 */
class BlindToneGeneralizationV4Test {
    private val androidRoot = File(checkNotNull(System.getProperty("echoLinkAndroidDir")))
    private val sourceLines = File(
        androidRoot,
        "app/src/test/resources/blind-tone-generalization-v4.txt",
    ).readLines(Charsets.UTF_8).map(String::trim).filter(String::isNotBlank)
    private val source = sourceLines.joinToString(" ")

    private val factsByLine = listOf(
        listOf("Lucía", "7:45 p.m.", "Friday", "October 13", "Aunt Noreen", "Nobody boards early", "7:15"),
        listOf("18", "ivory masks", "80", "$126", "six guests", "noon"),
        listOf("Theo", "seating chart", "divorced parents", "Maren", "Joel"),
        listOf("Priya", "blue after midnight", "12:00 a.m.", "blue lanterns"),
        listOf(
            "allergy list",
            "RSVP@celestine-ball.org",
            "rsvp@celestine.com",
            "https://celestine-ball.org/menu?deck=2",
            "Tuesday",
        ),
        listOf("Bracelet ID RB-00419", "Émile", "ticket C7", "RB-00491"),
        listOf("fog", "9", "upper-deck vows", "outdoors"),
        listOf("cousin", "gong", "toast", "stop now"),
        listOf("Selene", "veil", "$2,400", "Cabin 3B", "Darius"),
        listOf("two gluten-free", "three nut-free", "five ordinary desserts", "ten desserts", "coconut", "Ana", "cake"),
    )

    @Test
    fun evaluatesAllTonesOnAFourthIndependentDomain() {
        assertEquals("The sealed corpus must contain exactly ten independent lines.", 10, sourceLines.size)

        val firstRun = WritingTone.entries.associateWith(::rewriteEachLine)
        val secondRun = WritingTone.entries.associateWith(::rewriteEachLine)
        val proofread = firstRun.getValue(WritingTone.PROOFREAD)
        val scores = linkedMapOf<WritingTone, Int>()
        val failures = linkedMapOf<WritingTone, List<String>>()
        val report = buildString {
            appendLine("Manuscript blind tone generalization v4")
            appendLine("Domain: destination wedding logistics")
            appendLine("Independently authored sentences: ${sourceLines.size}")
            appendLine("Execution: every sentence independently, twice, for all 23 tones")
            appendLine()
            WritingTone.entries.forEach { tone ->
                val result = firstRun.getValue(tone)
                val checks = linkedMapOf(
                    "correctness" to correctnessPasses(result.outputs),
                    "punctuation_format" to punctuationAndFormatPass(result.outputs),
                    "protected_facts" to protectedFactsPass(result.outputs),
                    "meaning" to meaningPass(result.outputs),
                    "negation_constraints" to constraintsPass(tone, result.outputs),
                    "deterministic" to (result == secondRun.getValue(tone)),
                    "substantive_rewrite" to substantiveRewritePass(tone, result, proofread),
                    "tone" to tonePasses(tone, result, proofread),
                )
                val failed = checks.filterValues { !it }.keys.toList()
                scores[tone] = checks.values.count { it }
                failures[tone] = failed
                appendLine("${tone.label}: ${scores.getValue(tone)}/8")
                appendLine(checks.entries.joinToString { "${it.key}=${if (it.value) "PASS" else "FAIL"}" })
                appendLine("failed=${if (failed.isEmpty()) "none" else failed.joinToString()}")
                appendLine(
                    "diagnostic_edit_ratio=${"%.4f".format(Locale.ROOT, wordEditRatio(proofread.text, result.text))}; " +
                        "shorter_lines=${result.outputs.indices.count { index -> wordCount(result.outputs[index]) < wordCount(proofread.outputs[index]) }}; " +
                        "changed_lines=${result.outputs.indices.count { index -> normalized(result.outputs[index]) != normalized(proofread.outputs[index]) }}",
                )
                if (tone == WritingTone.CAVEMAN) {
                    appendLine(
                        "diagnostic_caveman_shape=" + result.outputs.indices.joinToString { index ->
                            "${index + 1}:${if (cavemanShapePass(result.outputs[index], index)) "PASS" else "FAIL"}"
                        },
                    )
                    appendLine(
                        "diagnostic_caveman_metrics=" + result.outputs.indices.joinToString { index ->
                            val metrics = sentences(result.outputs[index]).joinToString("/") { sentence ->
                                val protectedCount = factsByLine[index].count { fact ->
                                    sentence.contains(fact, ignoreCase = true)
                                }
                                "${ordinaryWordCount(sentence, index)}:${wordCount(sentence)}:$protectedCount"
                            }
                            "${index + 1}[$metrics]"
                        },
                    )
                }
                result.outputs.forEachIndexed { index, output -> appendLine("${index + 1}) $output") }
                appendLine()
            }
        }

        val reportFile = File(androidRoot, "build/reports/blind-tone-generalization-v4.txt")
        reportFile.parentFile?.mkdirs()
        reportFile.writeText(report, Charsets.UTF_8)

        assertEquals(23, WritingTone.entries.size)
        assertEquals(WritingTone.entries.size, firstRun.size)
        assertTrue(firstRun.values.all { result ->
            result.outputs.size == sourceLines.size && result.outputs.all(String::isNotBlank)
        })
        assertTrue(
            "Blind generalization v4 failed. See ${reportFile.absolutePath}. Failures: $failures",
            scores.values.all { it == 8 },
        )
    }

    private fun rewriteEachLine(tone: WritingTone): RewriteResult {
        val outputs = sourceLines.map { sourceLine -> LocalWritingAssistant.suggest(sourceLine, tone) }
        return RewriteResult(outputs.joinToString(" "), outputs)
    }

    private fun correctnessPasses(outputs: List<String>): Boolean {
        if (outputs.size != 10) return false
        val joined = canonical(outputs.joinToString(" "))
        val badGrammar = listOf(
            Regex("\\bme and lucía\\b"),
            Regex("\\bisnt\\b"),
            Regex("\\bcant\\b"),
            Regex("\\bdont\\b"),
            Regex("\\blucía (?:isn't|is not) arriving\\b"),
            Regex("\\b(?:masks|guests|desserts) (?:is|was|has)\\b"),
            Regex("\\bdoes not,\\s+and\\s+(?:don't|do not)\\b"),
        )
        val correctedArrival = listOf(
            Regex("\\blucía and i\\b.{0,35}\\b(?:are not|aren't|will not|won't)\\b.{0,20}\\barriv"),
            Regex("\\bneither lucía nor i\\b.{0,35}\\barriv"),
        ).any { it.containsMatchIn(joined) }
        val correctedLanternRule = listOf("can't be used", "cannot be used", "may not be used")
            .any(canonical(outputs[3])::contains)
        val correctedSwapRule = listOf("don't swap", "do not swap", "must not swap")
            .any(canonical(outputs[5])::contains)
        return badGrammar.none { it.containsMatchIn(joined) } && correctedArrival &&
            correctedLanternRule && correctedSwapRule &&
            outputs.none(::hasConditionalFragment) && outputs.all(::directQuestionsEndCorrectly)
    }

    private fun punctuationAndFormatPass(outputs: List<String>): Boolean =
        outputs.size == 10 && outputs.all { output ->
            val trimmed = output.trim()
            trimmed.isNotEmpty() &&
                trimmed.last() in ".!?" &&
                !Regex("[ \\t]{2,}").containsMatchIn(trimmed) &&
                !Regex("\\s+[,.;:!?]").containsMatchIn(trimmed) &&
                !Regex("[,;:][.!?]").containsMatchIn(trimmed) &&
                !Regex("[!?]{2,}|[.]{3,}").containsMatchIn(trimmed) &&
                listOf("â", "Ã", "�").none(trimmed::contains) &&
                trimmed.count { it == '“' } == trimmed.count { it == '”' } &&
                trimmed.count { it == '\"' } % 2 == 0 &&
                trimmed.firstOrNull(Char::isLetter)?.isUpperCase() == true
        }

    private fun protectedFactsPass(outputs: List<String>): Boolean =
        outputs.size == factsByLine.size && outputs.indices.all { index ->
            factsByLine[index].all { fact -> factOccursExactlyOnce(outputs[index], fact) }
        } &&
            outputs[4].contains("RSVP@celestine-ball.org") &&
            outputs[4].contains("rsvp@celestine.com") &&
            outputs[4].contains("https://celestine-ball.org/menu?deck=2") &&
            outputs[0].contains("“Nobody boards early,”") &&
            outputs[3].contains("“blue after midnight”") &&
            outputs[5].contains("RB-00419") && outputs[5].contains("RB-00491") &&
            outputs[5].contains("C7")

    private fun meaningPass(outputs: List<String>): Boolean {
        if (outputs.size != 10) return false
        return lineHas(outputs[0], listOf("arriv", "before", "boards")) &&
            lineHasAny(outputs[0], listOf("said", "stated", "told")) &&
            lineHasAny(outputs[1], listOf("order", "buy", "purchase")) &&
            lineHas(outputs[1], listOf("masks", "spend", "guests", "cancel")) &&
            lineHas(outputs[2], listOf("seating", "parents", "together", "move")) &&
            lineHas(outputs[3], listOf("mean", "lantern", "used")) &&
            lineHasAny(outputs[3], listOf("turn blue", "become blue")) && outputs[3].contains('?') &&
            lineHas(outputs[4], listOf("send", "allergy", "use", "before")) &&
            lineHas(outputs[5], listOf("bracelet", "ticket", "swap")) &&
            lineHasAny(outputs[5], listOf("belongs to", "assigned to")) &&
            lineHasAny(outputs[6], listOf("promis", "guarantee", "commit")) &&
            lineHas(outputs[6], listOf("fog", "clear", "vows", "outdoors")) &&
            lineHas(outputs[7], listOf("cousin", "gong", "toast", "stop")) &&
            lineHas(outputs[8], listOf("veil", "left", "cabin")) &&
            lineHasAny(outputs[8], listOf("asked", "inquired")) &&
            lineHas(outputs[9], listOf("dessert", "gluten", "nut", "ordinary", "coconut", "cake"))
    }

    private fun constraintsPass(tone: WritingTone, outputs: List<String>): Boolean {
        if (outputs.size != 10) return false
        val lines = outputs.map(::canonical)

        val arrivalNotEarly = listOf(
            Regex("(?:are not|aren't|will not|won't)\\s+arriv.{0,25}before 7:45 p\\.m\\."),
            Regex("arriv.{0,25}(?:no earlier than|at or after) 7:45 p\\.m\\."),
        ).any { it.containsMatchIn(lines[0]) }
        val noreenQuote = Regex("aunt noreen.{0,35}(?:said|stated|told).{0,45}nobody boards early")
            .containsMatchIn(lines[0])
        val notSevenFifteen = listOf(
            Regex("(?:did not|didn't|never).{0,15}(?:say|said|state|stated|mention|mentioned).{0,15}7:15"),
            Regex("7:15.{0,20}(?:was not|wasn't).{0,20}(?:said|stated|the time)"),
        ).any { it.containsMatchIn(lines[0]) }

        val exactMaskOrder = Regex("exactly 18.{0,20}ivory masks").containsMatchIn(lines[1])
        val notEighty = Regex("(?:not|rather than|instead of) 80").containsMatchIn(lines[1])
        val maskOrderTentative = Regex(
            "\\b(?:maybe|perhaps|consider|could|may)\\b|\\bdecide whether\\b",
        ).containsMatchIn(lines[1])
        val budget = listOf(
            Regex("no more than \\$126"),
            Regex("at most \\$126"),
            Regex("\\$126.{0,20}(?:limit|maximum|cap|at most)"),
        ).any { it.containsMatchIn(lines[1]) }
        val cancellationException = listOf(
            Regex("unless six guests cancel by noon"),
            Regex("only if six guests cancel by noon"),
        ).any { it.containsMatchIn(lines[1]) }

        val exactlyOneParent = Regex("either maren or joel").containsMatchIn(lines[2]) &&
            listOf("do not move both", "don't move both", "must not move both", "not both")
                .any(lines[2]::contains)

        val midnightAmbiguity = lines[3].contains("blue after midnight") &&
            lines[3].contains("12:00 a.m.") && lines[3].contains(" or ") &&
            lineHasAny(lines[3], listOf("can't be used", "cannot be used", "may not be used")) &&
            lines[3].contains('?')

        val addressRouting = Regex(
            "(?:send|email).{0,30}allergy list.{0,30}rsvp@celestine-ball\\.org.{0,30}" +
                "(?:not|rather than|instead of) rsvp@celestine\\.com",
        ).containsMatchIn(lines[4]) &&
            lines[4].contains("https://celestine-ball.org/menu?deck=2") &&
            Regex("before tuesday").containsMatchIn(lines[4])

        val braceletBelongs = Regex("rb-00419.{0,30}belongs to émile")
            .containsMatchIn(lines[5])
        val braceletAssigned = Regex("rb-00419.{0,30}is assigned to émile")
            .containsMatchIn(lines[5])
        val braceletOwnership = braceletBelongs || braceletAssigned
        val ticketNonOwnership = listOf(
            Regex("ticket c7.{0,25}(?:does not|doesn't) belong"),
            Regex("ticket c7.{0,15}(?:does not|doesn't)"),
        ).any { it.containsMatchIn(lines[5]) } ||
            braceletAssigned && Regex("ticket c7.{0,15}is not(?:[.;]|\\s|$)")
                .containsMatchIn(lines[5])
        val noSwap = listOf("do not swap", "don't swap", "must not swap")
            .any(lines[5]::contains) && lines[5].contains("rb-00491")

        val fogUncertain = Regex("fog.{0,25}(?:might|may|could|possibly).{0,20}clear.{0,20}(?:around|near) 9")
            .containsMatchIn(lines[6])
        val vowsUncertain = listOf(
            Regex("(?:not promising|cannot promise|can't promise).{0,45}upper-deck vows.{0,30}outdoors"),
            Regex("upper-deck vows.{0,35}(?:are not guaranteed|may not|might not).{0,20}outdoors"),
        ).any { it.containsMatchIn(lines[6]) }

        val gongBoundary = Regex("cousin.{0,40}gong.{0,35}toast").containsMatchIn(lines[7]) &&
            listOf("stop now", "must stop", "needs to stop", "has to stop")
                .any(lines[7]::contains)
        val gongCondition = Regex(
            "(?:if|when).{0,50}cousin.{0,50}gong.{0,50}toast.{0,50}(?:one more time|again)",
        ).containsMatchIn(lines[7])
        val gongConditionAndConsequence =
            Regex("(?:if|when).{0,120}(?:one more time|again).{0,120}(?:throw|toss|put).{0,60}river")
                .containsMatchIn(lines[7])
        val propertyDisposalThreat = Regex(
            "(?:throw|toss|dump|hurl|pitch|put).{0,25}(?:it|gong).{0,35}" +
                "(?:river|trash|garbage|away)",
        ).containsMatchIn(lines[7])
        val gongConstraint = if (tone == WritingTone.PROOFREAD) {
            gongBoundary && gongConditionAndConsequence
        } else {
            gongBoundary && gongCondition && !propertyDisposalThreat
        }

        val veilNeverThere = Regex("veil.{0,45}(?:was never|was not|wasn't).{0,20}(?:left in|in) cabin 3b")
            .containsMatchIn(lines[8])
        val dariusOnlyAsked = Regex("darius.{0,20}only (?:asked|inquired|questioned).{0,25}whether")
            .containsMatchIn(lines[8])

        val dessertCounts = listOf(
            "two gluten-free", "three nut-free", "five ordinary desserts", "ten desserts",
        ).all(lines[9]::contains)
        val coconutBan = listOf("none may contain coconut", "must not contain coconut", "no coconut")
            .any(lines[9]::contains)
        val anaNoCake = listOf("ana gets no cake", "no cake for ana", "ana must not get cake")
            .any(lines[9]::contains)

        return arrivalNotEarly && noreenQuote && notSevenFifteen &&
            exactMaskOrder && notEighty && maskOrderTentative && budget && cancellationException &&
            exactlyOneParent &&
            midnightAmbiguity && addressRouting && braceletOwnership && ticketNonOwnership && noSwap &&
            fogUncertain && vowsUncertain && gongConstraint &&
            veilNeverThere && dariusOnlyAsked &&
            dessertCounts && coconutBan && anaNoCake
    }

    private fun substantiveRewritePass(
        tone: WritingTone,
        result: RewriteResult,
        proofread: RewriteResult,
    ): Boolean {
        if (tone == WritingTone.PROOFREAD) {
            return normalized(result.text) != normalized(source) &&
                listOf(0, 3, 5).all { normalized(result.outputs[it]) != normalized(sourceLines[it]) }
        }

        val ratios = result.outputs.indices.map { index ->
            wordEditRatio(
                stripStockFrames(proofread.outputs[index]),
                stripStockFrames(result.outputs[index]),
            )
        }
        val lexicallyChangedLines = result.outputs.indices.count { index ->
            words(stripStockFrames(proofread.outputs[index])) !=
                words(stripStockFrames(result.outputs[index]))
        }
        val aggregateRatio = wordEditRatio(
            stripStockFrames(proofread.text),
            stripStockFrames(result.text),
        )
        val shorterLines = result.outputs.indices.count { index ->
            wordCount(result.outputs[index]) < wordCount(proofread.outputs[index])
        }
        return when (tone) {
            WritingTone.REPHRASE -> lexicallyChangedLines >= 9 &&
                ratios.count { it >= 0.035 } >= 8 &&
                protectedFactsPass(result.outputs) && meaningPass(result.outputs) &&
                constraintsPass(tone, result.outputs)
            WritingTone.SHORTEN, WritingTone.CAVEMAN ->
                wordCount(result.text) < wordCount(proofread.text) && shorterLines >= 5
            else -> aggregateRatio >= 0.06 && ratios.count { it >= 0.04 } >= 4
        }
    }

    private fun tonePasses(
        tone: WritingTone,
        result: RewriteResult,
        proofread: RewriteResult,
    ): Boolean {
        val lower = canonical(result.text)
        val ratio = wordEditRatio(stripStockFrames(proofread.text), stripStockFrames(result.text))
        val sourceHostilityRetained = result.outputs[2].contains("idiotic", ignoreCase = true) &&
            result.outputs[7].contains("dreadful", ignoreCase = true) &&
            canonical(result.outputs[7]).contains("throw it in the river")
        val cleanDelivery = !hasInsultOrThreat(result.outputs[2]) && !hasInsultOrThreat(result.outputs[7])
        val lineTwoUncertainty = listOf(
            "maybe", "perhaps", "consider", "could", "may", "decide whether",
        )
            .any(canonical(result.outputs[1])::contains)
        val lineSevenUncertainty = Regex("\\b(?:might|may|could|possibly)\\b")
            .containsMatchIn(canonical(result.outputs[6])) &&
            listOf("not promising", "cannot promise", "can't promise", "not guaranteed", "may not", "might not")
                .any(canonical(result.outputs[6])::contains)

        return when (tone) {
            WritingTone.PROOFREAD -> sourceHostilityRetained && lineTwoUncertainty &&
                lineSevenUncertainty && ratio <= 0.10
            WritingTone.IMPROVE -> cleanDelivery && ratio >= 0.06
            WritingTone.REPHRASE -> cleanDelivery &&
                substantiveRewritePass(tone, result, proofread) &&
                result.text.length in (proofread.text.length * 7 / 10)..(proofread.text.length * 13 / 10)
            WritingTone.SHORTEN -> cleanDelivery &&
                wordCount(result.text) < wordCount(proofread.text)
            WritingTone.FRIENDLY -> cleanDelivery && listOf(2, 7).any { index ->
                lineHasAny(result.outputs[index], listOf("please", "thank", "appreciate", "kindly", "could you"))
            }
            WritingTone.FORMAL -> cleanDelivery && !containsContraction(result.text) &&
                listOf("idiotic", "dreadful", "maybe", "i suppose").none(lower::contains)
            WritingTone.PROFESSIONAL -> cleanDelivery && listOf(1, 2, 4, 7).count { index ->
                lineHasAny(
                    result.outputs[index],
                    listOf("please", "must", "needs to", "should", "request", "ensure", "confirm"),
                )
            } >= 2
            WritingTone.DIPLOMATIC -> cleanDelivery && listOf(2, 7).all { index ->
                lineHasAny(
                    result.outputs[index],
                    listOf("please", "concern", "clarify", "address", "resolve", "consider", "could we", "may we"),
                )
            }
            WritingTone.EMPATHETIC -> cleanDelivery && explicitAcknowledgement(result.outputs[2]) ||
                cleanDelivery && explicitAcknowledgement(result.outputs[7])
            WritingTone.COOPERATIVE -> cleanDelivery && listOf(2, 7).all { index ->
                cooperativeAction(result.outputs[index])
            }
            WritingTone.ASSERTIVE -> cleanDelivery && lineTwoUncertainty && lineSevenUncertainty &&
                lineHasAny(result.outputs[2], listOf("move either", "must move", "needs to move")) &&
                lineHasAny(result.outputs[7], listOf("stop now", "must stop", "needs to stop"))
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
            WritingTone.COLLEGE -> cleanDelivery && ratio >= 0.05
            WritingTone.CAVEMAN -> cleanDelivery && lineTwoUncertainty && lineSevenUncertainty &&
                result.outputs.indices.all { index -> cavemanShapePass(result.outputs[index], index) }
        }
    }

    private fun hasInsultOrThreat(text: String): Boolean {
        val lower = canonical(text)
        val insults = listOf("idiotic", "stupid", "foolish", "ridiculous", "dreadful", "awful", "obnoxious")
        val personThreat = Regex(
            "\\b(?:hurt|harm|hit|strike|throw|toss|dump|hurl|pitch)\\s+" +
                "(?:you|him|her|the cousin|your cousin)\\b",
        ).containsMatchIn(lower)
        return insults.any(lower::contains) || personThreat
    }

    private fun explicitAcknowledgement(text: String): Boolean {
        val lower = canonical(text)
        val acknowledgement = Regex(
            "\\b(?:understand|recognize|acknowledge|appreciate)\\b.{0,70}" +
                "\\b(?:concern|frustration|situation|seating|parents|toast|gong|request|need)\\b",
        )
        return acknowledgement.containsMatchIn(lower)
    }

    private fun cooperativeAction(text: String): Boolean {
        val lower = canonical(text)
        val marker = Regex(
            "\\b(?:we|together|let's|let us|coordinate|collaborate|please|could you)\\b",
        )
            .containsMatchIn(lower)
        val action = Regex("\\b(?:move|resolve|adjust|stop|handle|review|fix|address)\\b")
            .containsMatchIn(lower)
        return marker && action
    }

    private fun cavemanShapePass(output: String, lineIndex: Int): Boolean {
        val outputSentences = sentences(output)
        if (outputSentences.isEmpty() || outputSentences.size > 4) return false
        return outputSentences.all { sentence ->
            val protectedCount = factsByLine[lineIndex].count { fact ->
                sentence.contains(fact, ignoreCase = true)
            }
            val denseFacts = protectedCount >= 3 || sentence.contains("http") || sentence.contains('@')
            ordinaryWordCount(sentence, lineIndex) <= (if (denseFacts) 16 else 12) &&
                wordCount(sentence) <= (if (denseFacts) 28 else 12)
        }
    }

    private fun hasConditionalFragment(text: String): Boolean = sentences(text).any { sentence ->
        val lower = canonical(sentence).trimStart { !it.isLetterOrDigit() }
        val beginsWithCondition = lower.startsWith("if ") || lower.startsWith("unless ")
        beginsWithCondition && ',' !in lower && ';' !in lower
    }

    private fun directQuestionsEndCorrectly(text: String): Boolean = sentences(text).all { sentence ->
        val trimmed = canonical(sentence).trim()
        val beginning = trimmed.trimStart { !it.isLetterOrDigit() }
        val directQuestion = Regex("\\b(?:could|can|would|will) you\\b")
            .containsMatchIn(beginning) ||
            Regex(
                "^(?:or\\s+)?(?:(?:do|does|did)(?!\\s+not\\b)|is|are|was|were|has|have|" +
                    "should)\\s+\\p{L}",
            )
                .containsMatchIn(beginning) ||
            Regex("\\bdid (?:she|he|they|you|it) mean\\b").containsMatchIn(beginning)
        !directQuestion || trimmed.lastOrNull { it in ".!?" } == '?'
    }

    private fun ordinaryWordCount(text: String, lineIndex: Int): Int {
        var remainder = text
        factsByLine[lineIndex].sortedByDescending(String::length).forEach { fact ->
            remainder = Regex(Regex.escape(fact), RegexOption.IGNORE_CASE).replace(remainder, " ")
        }
        return wordCount(remainder)
    }

    private fun containsContraction(text: String): Boolean {
        val lower = canonical(text)
        return Regex(
            "\\b(?:isn't|aren't|wasn't|weren't|can't|couldn't|won't|wouldn't|don't|doesn't|" +
                "didn't|i'm|i've|i'll|we're|we've|we'll|they're|they've|they'll|that's|there's|" +
                "it's|let's)\\b",
        ).containsMatchIn(lower)
    }

    private fun factOccursExactlyOnce(text: String, fact: String): Boolean =
        Regex(Regex.escape(fact), RegexOption.IGNORE_CASE).findAll(text).count() == 1

    private fun lineHas(text: String, fragments: List<String>): Boolean {
        val lower = canonical(text)
        return fragments.all(lower::contains)
    }

    private fun lineHasAny(text: String, fragments: List<String>): Boolean {
        val lower = canonical(text)
        return fragments.any(lower::contains)
    }

    private fun stripStockFrames(text: String): String = Regex(
        "(?i)(?:Clearly stated:|Put another way:|Hi!|Please note:|For clarity:|" +
            "A balanced approach:|I understand\\.|Let's work together\\.|To be clear:)\\s*",
    ).replace(text, "")

    private fun canonical(text: String): String = text.lowercase(Locale.ROOT)
        .replace('’', '\'')
        .replace('‘', '\'')
        .replace('“', '\"')
        .replace('”', '\"')

    private fun normalized(text: String): String = canonical(text).replace(Regex("\\s+"), " ").trim()

    private fun wordCount(text: String): Int = words(text).size

    private fun words(text: String): List<String> = Regex("[\\p{L}0-9$%./?=&@'-]+")
        .findAll(canonical(text)).map { it.value }.toList()

    private fun sentences(text: String): List<String> {
        var protected = Regex("(?i)\\b(?:a|p)\\.m\\.").replace(text) { match ->
            match.value.replace('.', '\uE100')
        }
        protected = Regex("(?i)\\b(?:mr|mrs|ms|dr|prof)\\.").replace(protected) { match ->
            match.value.replace('.', '\uE100')
        }
        return Regex("(?<=[.!?])(?:[\"”']+)?\\s+").split(protected)
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
