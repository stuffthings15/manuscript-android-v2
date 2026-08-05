package com.echolink.mobile

import java.util.Locale

data class TonePromptSpec(
    val tone: WritingTone,
    val instruction: String,
    val must: List<String>,
    val avoid: List<String>,
)

/**
 * The complete prompt contract for every tone shown in the Android UI.
 *
 * Prompts are sent only to the installed on-device AI.
 */
object TonePromptLibrary {
    private const val SENTENCE_AUDIT =
        "Silently audit every sentence for spelling, articles, pronouns, subject-verb agreement, " +
            "tense, fragments, run-ons, and comma splices, dangling modifiers and faulty " +
            "parallelism, punctuation, capitalization, compound modifiers, and precise word choice."
    private const val TENSE_AUDIT =
        "Keep current roles in present tense and scheduled actions in future tense unless the " +
            "source clearly states otherwise. Do not convert a current job role into past tense."
    private const val MODIFIER_AUDIT =
        "Repair every dangling introductory modifier by naming an actor already present in the " +
            "same paragraph."
    private const val MARKUP_AUDIT =
        "Keep every Markdown heading prefix and list marker exactly; never turn a list into prose."
    private const val MODALITY_AUDIT =
        "Preserve every permission, prohibition, obligation, uncertainty, and level of certainty. " +
            "Do not strengthen, weaken, add, or remove any of them."
    private val commonMust = listOf(
        "Correct every grammar, spelling, punctuation, capitalization, and sentence-structure error.",
        "Use standard American English. Correct nonstandard slang, phonetic spellings, wrong homophones, malformed words, and imprecise vocabulary unless they appear inside a quotation.",
        "Rewrite a broken sentence completely when small edits cannot make it grammatical and natural.",
        "Preserve the exact meaning, facts, names, numbers, dates, links, quotations, and formatting.",
        "Preserve the writer's point of view and make every pronoun referent unambiguous.",
        SENTENCE_AUDIT,
        TENSE_AUDIT,
        MODIFIER_AUDIT,
        MARKUP_AUDIT,
        MODALITY_AUDIT,
        "Return only the rewritten text.",
    )
    private val commonAvoid = listOf(
        "Do not invent, remove, or contradict information.",
        "Do not summarize, merge, or omit claims, examples, or qualifications unless the selected tone is Shorten.",
        "Do not explain the rewrite or mention these instructions.",
        "Never use em dashes.",
    )

    private val specs = listOf(
        TonePromptSpec(
            WritingTone.PROOFREAD,
            "Proofread the text until every sentence is flawless, natural standard English. The source may contain deliberate errors throughout, so inspect every word and sentence.",
            listOf("Preserve the writer's meaning and voice, but never preserve an error merely because it appears intentional or informal."),
            listOf("Do not paraphrase text that is already correct."),
        ),
        TonePromptSpec(
            WritingTone.IMPROVE,
            "Improve the text so it is clear, natural, polished, and easy to follow.",
            listOf("Strengthen clarity, flow, word choice, and sentence structure."),
            listOf("Do not make the text longer without a clear reason."),
        ),
        TonePromptSpec(
            WritingTone.REPHRASE,
            "Rephrase the text with substantially different, natural wording while preserving its full meaning.",
            listOf("Change wording and sentence structure, not facts or intent."),
            listOf("Do not merely swap one or two words."),
        ),
        TonePromptSpec(
            WritingTone.SHORTEN,
            "Shorten the text. Remove filler, repetition, and unnecessary words while retaining every essential fact.",
            listOf("Use the fewest words that still carry the complete message."),
            listOf("Do not remove required actions, limits, dates, names, or qualifications."),
        ),
        TonePromptSpec(
            WritingTone.FRIENDLY,
            "Rewrite the text in a warm, friendly, approachable tone.",
            listOf("Sound positive, conversational, and respectful."),
            listOf("Do not sound childish, fake, overly excited, or unprofessional."),
        ),
        TonePromptSpec(
            WritingTone.FORMAL,
            "Rewrite the text in a formal, precise, grammatically complete style.",
            listOf("Use standard written English and complete sentences."),
            listOf("Do not use contractions, slang, casual filler, or emojis."),
        ),
        TonePromptSpec(
            WritingTone.PROFESSIONAL,
            "Rewrite the text in a polished professional workplace tone.",
            listOf("Be clear, credible, efficient, and action oriented."),
            listOf("Do not use slang, hostility, vague filler, or needless jargon."),
        ),
        TonePromptSpec(
            WritingTone.ACADEMIC,
            "Rewrite the text in a rigorous academic style suitable for scholarly work.",
            listOf("Use precise claims, logical connections, and disciplined formal language."),
            listOf("Do not invent evidence, citations, findings, or certainty."),
        ),
        TonePromptSpec(
            WritingTone.CONVERSATIONAL,
            "Rewrite the text in a natural conversational voice.",
            listOf("Sound fluent, direct, and comfortable when read aloud."),
            listOf("Do not use sloppy grammar, forced slang, or filler."),
        ),
        TonePromptSpec(
            WritingTone.CONCISE,
            "Rewrite the text concisely while preserving every essential fact and action.",
            listOf("Remove redundancy and use economical sentence structures."),
            listOf("Do not remove constraints, reasoning, qualifications, or required context."),
        ),
        TonePromptSpec(
            WritingTone.DETAILED,
            "Rewrite the text with clear, well-organized detail drawn only from the source.",
            listOf("Make relationships, sequence, and existing explanations explicit."),
            listOf("Do not invent examples, facts, causes, or supporting evidence."),
        ),
        TonePromptSpec(
            WritingTone.PERSUASIVE,
            "Rewrite the text persuasively while preserving the writer's position and facts.",
            listOf("Use clear reasoning, credible emphasis, and a specific call to action."),
            listOf("Do not exaggerate, manipulate, or fabricate support."),
        ),
        TonePromptSpec(
            WritingTone.CONFIDENT,
            "Rewrite the text with calm confidence and clear conviction.",
            listOf("Use decisive wording while remaining accurate and respectful."),
            listOf("Do not become aggressive, absolute, boastful, or unsupported."),
        ),
        TonePromptSpec(
            WritingTone.DIPLOMATIC,
            "Rewrite the text tactfully and neutrally. Reduce blame and friction without weakening facts or requirements.",
            listOf("Use calm language that protects the working relationship."),
            listOf("Do not erase accountability, deadlines, risks, or required actions."),
        ),
        TonePromptSpec(
            WritingTone.EMPATHETIC,
            "Rewrite the text with genuine empathy, care, and respect.",
            listOf("Acknowledge the reader's feelings, effort, or impact when the source supports it."),
            listOf("Do not invent feelings, over-apologize, patronize, or weaken the message."),
        ),
        TonePromptSpec(
            WritingTone.CASUAL,
            "Rewrite the text in a relaxed, everyday style.",
            listOf("Use natural, accessible wording and contractions where appropriate."),
            listOf("Do not use careless grammar, obscure slang, or childish language."),
        ),
        TonePromptSpec(
            WritingTone.TECHNICAL,
            "Rewrite the text in a precise technical style.",
            listOf("Use consistent terminology, explicit relationships, and unambiguous instructions."),
            listOf("Do not invent specifications, measurements, mechanisms, or jargon."),
        ),
        TonePromptSpec(
            WritingTone.SIMPLIFIED,
            "Rewrite the text in plain language that is easy to understand.",
            listOf("Use common words, short clear sentences, and explain only what the source contains."),
            listOf("Do not remove essential nuance, requirements, or factual detail."),
        ),
        TonePromptSpec(
            WritingTone.HIGH_SCHOOL,
            "Rewrite the text for a high-school reading level.",
            listOf("Use clear standard vocabulary, varied sentences, and direct explanations."),
            listOf("Do not sound childish or remove necessary subject knowledge."),
        ),
        TonePromptSpec(
            WritingTone.COLLEGE,
            "Rewrite the text for a college reading level.",
            listOf("Use mature vocabulary, coherent development, and precise sentence structure."),
            listOf("Do not become needlessly ornate, vague, or academic-sounding without substance."),
        ),
        TonePromptSpec(
            WritingTone.COOPERATIVE,
            "Rewrite the text in a collaborative tone focused on shared goals and practical next steps.",
            listOf("Use inclusive language and make the requested cooperation clear."),
            listOf("Do not hide ownership, required actions, or decisions."),
        ),
        TonePromptSpec(
            WritingTone.ASSERTIVE,
            "Rewrite the text directly, confidently, and respectfully.",
            listOf("State the position, boundary, decision, or request clearly."),
            listOf("Do not hedge, apologize unnecessarily, threaten, insult, or sound aggressive."),
        ),
        TonePromptSpec(
            WritingTone.CAVEMAN,
            "Rewrite the text with very short, blunt, plain language and correct grammar.",
            listOf("Use common words, one idea per sentence, and no filler."),
            listOf("Do not use broken English, mock accents, jargon, or confusing fragments."),
        ),
    ).associateBy(TonePromptSpec::tone)

    fun spec(tone: WritingTone): TonePromptSpec = requireNotNull(specs[tone]) {
        "No prompt exists for ${tone.label}."
    }

    fun build(tone: WritingTone, source: String): String {
        return buildString {
            appendLine(instructions(tone))
            appendLine("Text:")
            append(source.take(MAX_PROMPT_SOURCE_CHARACTERS))
        }
    }

    /**
     * The constant instruction block for one tone, separated from the text
     * payload so the runtime can cache it inside the system prompt. On a
     * CPU-bound phone the instructions cost 400-600 prefill tokens per
     * paragraph when re-sent; cached once per tone they cost nothing.
     */
    fun instructions(tone: WritingTone): String {
        val spec = spec(tone)
        return buildString {
            appendLine(spec.instruction)
            if (tone != WritingTone.PROOFREAD) {
                appendLine(
                    "Mandatory tone transformation: visibly rewrite the wording and sentence " +
                        "structure. Returning the source unchanged is a failed answer.",
                )
                appendLine(toneExecutionRule(tone))
            }
            appendLine("Requirements:")
            (commonMust + spec.must).forEach { appendLine("- $it") }
            appendLine("Restrictions:")
            (commonAvoid + spec.avoid).forEach { append("- $it").appendLine() }
        }.trimEnd()
    }

    /** Minimal per-chunk payload sent when instructions live in the system prompt. */
    fun payload(source: String): String =
        "Text:\n" + source.take(MAX_PROMPT_SOURCE_CHARACTERS)

    /** Compact instruction block for 512-token contexts, cacheable per tone. */
    fun compactInstructions(tone: WritingTone): String {
        val spec = spec(tone)
        return buildString {
            append(spec.instruction)
            append(' ')
            append(commonMust[0])
            append(' ')
            append(commonMust[1])
            append(' ')
            append(commonMust[3])
            append(' ')
            append(spec.must.first())
            append(' ')
            append(spec.avoid.first())
            append(' ')
            append("Never use em dashes.")
            append(' ')
            append(SENTENCE_AUDIT)
            append(' ')
            append(TENSE_AUDIT)
            append(' ')
            append(MODIFIER_AUDIT)
            append(' ')
            append(MARKUP_AUDIT)
            append(' ')
            append(MODALITY_AUDIT)
            if (tone != WritingTone.PROOFREAD) {
                append(' ')
                append(toneExecutionRule(tone))
                append(" The wording must visibly change.")
            }
        }
    }

    fun buildCompact(tone: WritingTone, source: String): String {
        val spec = spec(tone)
        return buildString {
            append(spec.instruction)
            append(' ')
            append(commonMust[0])
            append(' ')
            append(commonMust[1])
            append(' ')
            append(commonMust[3])
            append(' ')
            append(spec.must.first())
            append(' ')
            append(spec.avoid.first())
            append(' ')
            append("Never use em dashes.")
            append(' ')
            append(SENTENCE_AUDIT)
            append(' ')
            append(TENSE_AUDIT)
            append(' ')
            append(MODIFIER_AUDIT)
            append(' ')
            append(MARKUP_AUDIT)
            append(' ')
            append(MODALITY_AUDIT)
            if (tone != WritingTone.PROOFREAD) {
                append(' ')
                append(toneExecutionRule(tone))
                append(" The wording must visibly change.")
            }
            appendLine()
            appendLine("Text:")
            append(source)
        }
    }

    fun allSpecs(): List<TonePromptSpec> = WritingTone.entries.map(::spec)

    private fun toneExecutionRule(tone: WritingTone): String = when (tone) {
        WritingTone.PROOFREAD -> ""
        WritingTone.IMPROVE ->
            "Make the result noticeably clearer, smoother, and more precise than the source."
        WritingTone.REPHRASE ->
            "Use genuinely different natural wording and a different sentence pattern."
        WritingTone.SHORTEN ->
            "Delete filler and repetition. The result should be materially shorter."
        WritingTone.FRIENDLY ->
            "Turn blunt, blaming, or abrupt language into warm, courteous, non-blaming language. " +
                "Use a polite request when the source contains a demand."
        WritingTone.FORMAL ->
            "Replace casual wording and contractions with complete, formal written English."
        WritingTone.PROFESSIONAL ->
            "Use concise workplace language, a clear action, and a respectful business tone."
        WritingTone.ACADEMIC ->
            "Use scholarly precision, logical transitions, and appropriately qualified claims."
        WritingTone.CONVERSATIONAL ->
            "Make the wording sound natural when spoken while keeping complete, correct sentences."
        WritingTone.CONCISE ->
            "Remove redundancy and compress sentence structure without losing content."
        WritingTone.DETAILED ->
            "Clarify sequence and relationships using only details already present in the source."
        WritingTone.PERSUASIVE ->
            "Strengthen the source's reasoning and call to action without adding unsupported claims."
        WritingTone.CONFIDENT ->
            "State the same message with calm conviction and remove needless hesitation."
        WritingTone.DIPLOMATIC ->
            "State the same problem tactfully, reduce direct blame, and preserve accountability."
        WritingTone.EMPATHETIC ->
            "Acknowledge impact or effort supported by the source, then state the request with care."
        WritingTone.CASUAL ->
            "Use relaxed everyday wording and natural contractions without becoming sloppy."
        WritingTone.TECHNICAL ->
            "Use exact terminology, explicit relationships, and unambiguous instructions."
        WritingTone.SIMPLIFIED ->
            "Use plain common words and short clear sentences without losing nuance."
        WritingTone.HIGH_SCHOOL ->
            "Use clear standard vocabulary and direct explanations at a high-school reading level."
        WritingTone.COLLEGE ->
            "Use mature vocabulary and coherent college-level sentence structure."
        WritingTone.COOPERATIVE ->
            "Frame the message around a shared goal and a clear next step using inclusive language."
        WritingTone.ASSERTIVE ->
            "State the requirement directly and confidently without hostility or apology."
        WritingTone.CAVEMAN ->
            "Use short, direct, grammatical sentences and common words. Remove every nonessential word."
    }

    private const val MAX_PROMPT_SOURCE_CHARACTERS = 4_000
}

object ToneOutputPolicy {
    private val promptEcho = Regex(
        "(?i)\\b(?:requirements|restrictions|return only|rewrite the text|" +
            "mention these instructions|text:)\\b",
    )
    private val words = Regex("[\\p{L}\\p{N}][\\p{L}\\p{N}'’-]*")
    private val stopWords = setOf(
        "a", "an", "and", "are", "as", "at", "be", "been", "but", "by", "for", "from",
        "has", "have", "he", "her", "his", "i", "in", "is", "it", "its", "of", "on",
        "or", "our", "she", "that", "the", "their", "them", "they", "this", "to", "was",
        "we", "were", "will", "with", "you", "your",
    )

    fun isSafe(source: String, output: String, tone: WritingTone): Boolean {
        val minimumLengthDivisor = when (tone) {
            WritingTone.SHORTEN, WritingTone.CONCISE, WritingTone.SIMPLIFIED,
            WritingTone.CAVEMAN -> 5
            else -> 3
        }
        if (!GrammarOutputPolicy.isSafe(source, output, minimumLengthDivisor)) return false
        if (
            tone != WritingTone.PROOFREAD &&
            output.trim().replace(Regex("\\s+"), " ").equals(
                source.trim().replace(Regex("\\s+"), " "),
                ignoreCase = true,
            )
        ) {
            return false
        }
        if (promptEcho.containsMatchIn(output)) return false
        if (Regex("(.)\\1{10,}").containsMatchIn(output)) return false
        val sourceTerms = contentTerms(source)
        if (sourceTerms.isEmpty()) return true
        val retained = sourceTerms.intersect(contentTerms(output)).size.toDouble() / sourceTerms.size
        val minimum = when (tone) {
            WritingTone.PROOFREAD -> 0.55
            WritingTone.REPHRASE, WritingTone.SHORTEN, WritingTone.CONCISE,
            WritingTone.SIMPLIFIED, WritingTone.CAVEMAN -> 0.25
            else -> 0.30
        }
        return retained >= minimum
    }

    private fun contentTerms(value: String): Set<String> = words.findAll(value)
        .map { it.value.lowercase(Locale.ROOT) }
        .filter { it.length >= 3 && it !in stopWords }
        .toSet()
}

object RewriteValidator {
    private val protected = Regex(
        "https?://\\S+|www\\.\\S+|[\\w.+-]+@[\\w.-]+\\.[A-Za-z]{2,}|" +
            "(?:[A-Za-z]:\\\\|/)[^\\s]+|\\b\\d+(?:[.,:/-]\\d+)*%?\\b|" +
            "\"[^\"]*\"|`[^`]*`|\\b[A-Z]{2,}\\b",
    )
    private val bullet = Regex("(?m)^(\\s*)((?:[-*+])|(?:\\d+[.)]))[ \\t]+")
    private val formatPrefix = Regex("(?m)^(\\s*)(#{1,6}|>|```|~~~|\\|)")
    private val capitalizedWord = Regex("\\b[A-Z][a-z]+(?:[-'][A-Z]?[a-z]+)?\\b")
    private val alwaysProtectedNames = setOf(
        "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday",
        "January", "February", "March", "April", "May", "June", "July", "August",
        "September", "October", "November", "December",
    )
    private val correctableCapitalizedWords = setOf("Readyness")
    private val commonCapitalizedWords = setOf(
        "A", "An", "The", "This", "That", "These", "Those", "I", "We", "You", "He",
        "She", "It", "They", "Please", "If", "When", "While", "Because", "Although",
        "However", "Therefore", "Also", "First", "Second", "Finally",
        "Send", "Keep", "Call", "Email", "Meet", "Review", "Check", "Open", "Close",
        "Update", "Create", "Delete", "Move", "Add", "Remove", "Use", "Make", "Tell",
        "Ask", "Give", "Bring", "Take", "Write", "Read", "Fix", "Finish", "Start",
        "Sends", "Delivers", "Reviews", "Checks", "Confirms", "Updates", "Trains",
        "Submits", "Photographs", "Labels", "Tests", "Verifies", "Configures",
        "Records", "Assigns",
    )

    fun isValid(
        source: String,
        prepared: String,
        output: String,
        tone: WritingTone,
    ): Boolean {
        if (output.contains('—')) return false
        if (!ToneOutputPolicy.isSafe(source, output, tone)) return false
        if (protectedCounts(source) != protectedCounts(output)) return false
        if (!preservesProperNames(source, output)) return false
        if (paragraphCount(source) != paragraphCount(output)) return false
        if (lineBreakProfile(source) != lineBreakProfile(output)) return false
        if (bulletProfile(source) != bulletProfile(output)) return false
        if (formatPrefixProfile(source) != formatPrefixProfile(output)) return false
        if (
            tone == WritingTone.PROOFREAD &&
            GrammarQualityGate.errorScore(output) > GrammarQualityGate.errorScore(prepared)
        ) {
            return false
        }
        return true
    }

    private fun protectedCounts(value: String): Map<String, Int> =
        protected.findAll(value).map { it.value }.groupingBy { it }.eachCount()

    private fun properNameCounts(value: String): Map<String, Int> {
        val matches = capitalizedWord.findAll(value).toList()
        return matches.mapIndexedNotNull { index, match ->
            val prefix = value.substring(0, match.range.first)
            val prior = prefix.trimEnd().lastOrNull()
            val beginsSentence =
                prefix.lastOrNull() == '\n' ||
                    prior == null ||
                    prior in charArrayOf('.', '!', '?')
            val adjacentCapitalized =
                matches.getOrNull(index - 1)?.range?.last?.let { previousEnd ->
                    value.substring(previousEnd + 1, match.range.first).isBlank()
                } == true ||
                    matches.getOrNull(index + 1)?.range?.first?.let { nextStart ->
                        value.substring(match.range.last + 1, nextStart).isBlank()
                    } == true
            match.value.takeIf {
                it !in commonCapitalizedWords &&
                    it !in correctableCapitalizedWords &&
                    (it in alwaysProtectedNames || !beginsSentence || adjacentCapitalized)
            }
        }.groupingBy { it }.eachCount()
    }

    private fun preservesProperNames(source: String, output: String): Boolean =
        properNameCounts(source).all { (name, count) ->
            Regex("\\b${Regex.escape(name)}\\b", RegexOption.IGNORE_CASE)
                .findAll(output)
                .count() >= count
        }

    private fun lineBreakProfile(value: String): Pair<Int, Int> =
        "\r\n".toRegex().findAll(value).count() to
            "(?<!\\r)\\n".toRegex().findAll(value).count()

    private fun bulletProfile(value: String): List<Pair<String, String>> =
        bullet.findAll(value).map { match ->
            match.groupValues[1] to match.groupValues[2]
        }.toList()

    private fun formatPrefixProfile(value: String): List<Pair<String, String>> =
        formatPrefix.findAll(value).map { match ->
            match.groupValues[1] to match.groupValues[2]
        }.toList()

    private fun paragraphCount(value: String): Int =
        Regex("\\n\\s*\\n").findAll(value).count()
}
