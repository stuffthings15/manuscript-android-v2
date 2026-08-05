package com.echolink.mobile

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/** Content-free evidence that every document section reached a live AI call. */
enum class LocalAiTerminalProvenance(val wireValue: String) {
    MODEL_ACCEPTED("model_accepted"),
    MODEL_PLUS_SAFE_REPAIRS("model_plus_safe_repairs"),
    CACHE_ONLY("cache_only"),
    KEPT_ORIGINAL("kept_original"),
}

data class LocalAiSectionAttemptEvidence(
    val attempts: Int,
    val terminalProvenance: LocalAiTerminalProvenance?,
)

object LocalAiAttemptEvidence {
    private val attemptsByDocument =
        ConcurrentHashMap<String, ConcurrentHashMap<Int, AtomicInteger>>()
    private val terminalByDocument =
        ConcurrentHashMap<String, ConcurrentHashMap<Int, LocalAiTerminalProvenance>>()
    private val paragraphNumber = Regex("-p(\\d+)-")

    fun record(documentJobId: String, requestJobId: String) {
        val section = paragraphNumber.find(requestJobId)?.groupValues?.get(1)?.toIntOrNull()
            ?: return
        attemptsByDocument.computeIfAbsent(documentJobId) { ConcurrentHashMap() }
            .computeIfAbsent(section) { AtomicInteger() }
            .incrementAndGet()
    }

    fun recordTerminal(
        documentJobId: String,
        section: Int,
        provenance: LocalAiTerminalProvenance,
    ) {
        terminalByDocument.computeIfAbsent(documentJobId) { ConcurrentHashMap() }
            .put(section, provenance)
    }

    fun snapshot(): Map<String, Set<Int>> = attemptsByDocument.mapValues { it.value.keys.toSet() }

    fun snapshotDetails(): Map<String, Map<Int, LocalAiSectionAttemptEvidence>> {
        val documentIds = attemptsByDocument.keys + terminalByDocument.keys
        return documentIds.associateWith { documentId ->
            val attempts = attemptsByDocument[documentId].orEmpty()
            val terminals = terminalByDocument[documentId].orEmpty()
            (attempts.keys + terminals.keys).associateWith { section ->
                LocalAiSectionAttemptEvidence(
                    attempts = attempts[section]?.get() ?: 0,
                    terminalProvenance = terminals[section],
                )
            }
        }
    }

    fun reset() {
        attemptsByDocument.clear()
        terminalByDocument.clear()
    }
}

data class DocumentCorrectionRequest(
    val jobId: String,
    val source: String,
    val tone: WritingTone,
    val qualityMode: QualityMode,
)

enum class SafetyIssue {
    EMPTY_OUTPUT,
    TRUNCATED_OUTPUT,
    PROTECTED_SPAN_CHANGED,
    MARKDOWN_HIERARCHY_CHANGED,
    LINE_STRUCTURE_CHANGED,
    NEGATION_CHANGED,
    OBLIGATION_CHANGED,
    PERMISSION_CHANGED,
    PROHIBITION_CHANGED,
    EM_DASH_PRESENT,
    UNCHANGED_TRANSFORMATION,
    CONTENT_WORD_CHANGED,
    CONTENT_REMOVED,
    SENTENCE_STRUCTURE_CHANGED,
    TONE_TRANSFORMATION,
    RESIDUAL_GRAMMAR_RISK,
}

data class SafetyValidation(
    val hardFailures: Set<SafetyIssue>,
    val highRisks: Set<SafetyIssue>,
) {
    val safe: Boolean
        get() = hardFailures.isEmpty()
}

data class CorrectedParagraph(
    val index: Int,
    val original: String,
    val firstPassText: String,
    val preReviewText: String,
    val text: String,
    val reviewed: Boolean,
    val usedFallback: Boolean,
    val validated: Boolean,
    val risks: Set<SafetyIssue>,
)

data class DocumentCorrectionResult(
    val text: String,
    val paragraphs: List<CorrectedParagraph>,
    val warnings: List<String>,
)

class ParagraphCorrectionException(
    val paragraphIndex: Int,
    message: String,
    val stage: String? = null,
    val rejectedCandidate: String? = null,
    val validation: SafetyValidation? = null,
) : IllegalStateException(message)

object AiSafetyValidator {
    private val placeholder = ProtectedDocument.PLACEHOLDER
    private val markdownPrefix = Regex(
        "(?m)^[ \\t]*(?:#{1,6}[ \\t]+|[-*+][ \\t]+|\\d+[.)][ \\t]+)",
    )
    private val negation = Regex(
        "(?i)\\b(?:not|never|no(?:body|thing|where)?|neither|nor|without|cannot|can't|won't|don't|" +
            "doesn't|didn't|isn't|aren't|wasn't|weren't|hasn't|haven't|hadn't|" +
            "couldn't|wouldn't|shouldn't|mustn't|ain't)\\b",
    )
    // "needs?" alone, not "needs to": broken sources drop the "to"
    // ("exits needs remain clear"), and repairing it must not read as a new
    // obligation. A dropped or added need-word still changes the count.
    // "got to"/"gotta" are the nonstandard form of the same obligation.
    // Without them, repairing "a good pet dont got to be" into "does not have
    // to be" read as inventing an obligation, so the sentence repair was
    // discarded and the writer kept looking at the broken sentence.
    private val obligation = Regex(
        "(?i)\\b(?:must(?!\\s+(?:not|have|of)\\b)|shall|required|mandatory|" +
            "needs?\\b|ha(?:ve|s)\\s+to|got\\s+t[oa]|gotta)\\b",
    )
    private val permission = Regex(
        "(?i)\\b(?:may(?!\\s+not\\b)|can(?!not\\b)(?!['’]t)(?!\\s+not\\b)|" +
            "allowed|permitted|authorized)\\b",
    )
    private val prohibition = Regex(
        "(?i)\\b(?:cannot|can't|must\\s+not|may\\s+not|prohibited|forbidden|not\\s+allowed)\\b",
    )
    // A negated verb followed in the same clause by a negative pronoun or a
    // "no + noun" phrase is a double negative whose repair removes exactly
    // one negation ("dont send no email" -> "do not send an email").
    private val doubleNegative = Regex(
        "(?i)\\b(?:don't|doesn't|didn't|can't|won't|couldn't|wouldn't|never|ain't|not)\\b" +
            "[^.!?]{0,60}?\\b(?:never|nobody|nothing|nowhere|none|no\\s+one|no\\s+\\p{L}+)\\b|" +
            "(?i)\\b(?:nobody|nothing|nowhere|none|no\\s+\\p{L}+)\\b" +
            "[^.!?]{0,60}?\\bneither\\b",
    )
    // Unpunctuated or mispunctuated contraction typos map to their canonical
    // form BEFORE meaning counting, so a correct spelling repair never reads as
    // a meaning change. Mirrors the Windows polish fix of 2026-07-31.
    private val contractionTypos: List<kotlin.Pair<Regex, String>> = listOf(
        Regex("(?i)\\bcant\\b") to "can't",
        Regex("(?i)\\bwont\\b") to "won't",
        Regex("(?i)\\bdont\\b") to "don't",
        Regex("(?i)\\bdoesnt\\b") to "doesn't",
        Regex("(?i)\\bdidnt\\b") to "didn't",
        Regex("(?i)\\bisnt\\b") to "isn't",
        Regex("(?i)\\barent\\b") to "aren't",
        Regex("(?i)\\bwasnt\\b") to "wasn't",
        Regex("(?i)\\bwerent\\b") to "weren't",
        Regex("(?i)\\bhasnt\\b") to "hasn't",
        Regex("(?i)\\bhavent\\b") to "haven't",
        Regex("(?i)\\bhadnt\\b") to "hadn't",
        Regex("(?i)\\bcouldnt\\b") to "couldn't",
        Regex("(?i)\\bwouldnt\\b") to "wouldn't",
        Regex("(?i)\\bshouldnt\\b") to "shouldn't",
        Regex("(?i)\\bmustnt\\b") to "mustn't",
        Regex("(?i)\\byoure\\b") to "you're",
        Regex("(?i)\\btheyre\\b") to "they're",
        Regex("(?i)\\bmust\\s+of\\b") to "must have",
        Regex("(?i)\\bshould\\s+of\\b") to "should have",
        Regex("(?i)\\bcould\\s+of\\b") to "could have",
        Regex("(?i)\\bwould\\s+of\\b") to "would have",
        // Misplaced apostrophes: was'nt -> wasn't, is'nt -> isn't.
        Regex("(?i)\\b(was|were|is|are|has|have|had|does|did|could|would|should|must)" +
            "['’]nt\\b") to "$1n't",
        // Split negative pronouns: "no body" -> "nobody".
        Regex("(?i)\\bno\\s+(body|thing)\\b") to "no$1",
        // "need too" is the frequent typo of "need to"; count it as the
        // obligation it was meant to be.
        Regex("(?i)\\b(need|needs)\\s+too\\b(?!\\s*[,.!?])") to "$1 to",
        // "no" misspelling of "know" after a subject+verb opener stays a
        // negation; only the exact frequent typo "dont/don't no" is repaired.
        Regex("(?i)\\b(don't|doesn't|didn't)\\s+no\\b") to "$1 know",
        Regex("(?i)\\baint\\b") to "ain't",
        // Misspelled obligation words. The writer's "nees"/"neesd" is not
        // counted as an obligation, so the model's correct "needs" read as a
        // brand new obligation and the whole repair was vetoed, leaving the
        // broken sentence on screen.
        Regex("(?i)\\b(?:nees|neesd|needd|neded|needz)\\b") to "needs",
        Regex("(?i)\\b(?:hafta|haveto|hasto)\\b") to "have to",
        Regex("(?i)\\bmus\\b") to "must",
        // Stance idioms carry no prohibition or negation of the surrounding
        // claim; dropping them in a rewrite is not a meaning flip.
        Regex("(?i)\\b(?:can't|cannot|cant)\\s+(believe|beleive|belive|wait|stand|help)\\b") to "$1",
    )

    private fun normalizeMeaning(value: String): String {
        var result = value
        contractionTypos.forEach { (regex, replacement) ->
            result = regex.replace(result, replacement)
        }
        return result
    }
    private val word = Regex("[\\p{L}][\\p{L}'’\\-]*")
    // "one" is deliberately absent: it is far more often a pronoun ("no one",
    // "one of the branches") than a protected quantity, and a swapped value
    // is still caught through the other number in the pair.
    private val writtenNumberValues = mapOf(
        "zero" to "0", "two" to "2", "three" to "3",
        "four" to "4", "five" to "5", "six" to "6", "seven" to "7",
        "eight" to "8", "nine" to "9", "ten" to "10", "eleven" to "11",
        "twelve" to "12", "thirteen" to "13", "fourteen" to "14",
        "fifteen" to "15", "sixteen" to "16", "seventeen" to "17",
        "eighteen" to "18", "nineteen" to "19", "twenty" to "20",
        "thirty" to "30", "forty" to "40", "fifty" to "50", "sixty" to "60",
        "seventy" to "70", "eighty" to "80", "ninety" to "90",
        "hundred" to "100", "thousand" to "1000", "million" to "1000000",
        "billion" to "1000000000",
    )
    // Digits (with an attached, spaced, or dotted meridiem so "3pm" -> "5pm"
    // is caught while "9am" -> "9 a.m." compares equal) or written number
    // words, in one pass so order is preserved.
    private val anyNumberToken = Regex(
        "(?i)\\d+(?:[.,:]\\d+)*(?:\\s?[ap]\\.?m\\.?(?![\\p{L}]))?|" +
            "\\b(?:twenty|thirty|forty|fifty|sixty|seventy|eighty|ninety)" +
            "[-\\s](?:one|two|three|four|five|six|seven|eight|nine)\\b|" +
            "\\b(?:zero|two|three|four|five|six|seven|eight|nine|ten|" +
            "eleven|twelve|thirteen|fourteen|fifteen|sixteen|seventeen|" +
            "eighteen|nineteen|twenty|thirty|forty|fifty|sixty|seventy|" +
            "eighty|ninety|hundred|thousand|million|billion)\\b",
    )
    private val writtenUnitValues = mapOf(
        "one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5,
        "six" to 6, "seven" to 7, "eight" to 8, "nine" to 9,
    )
    private val writtenTensValues = mapOf(
        "twenty" to 20, "thirty" to 30, "forty" to 40, "fifty" to 50,
        "sixty" to 60, "seventy" to 70, "eighty" to 80, "ninety" to 90,
    )
    private val sentenceEnd = Regex("[.!?]+(?:\\s|$)")
    private val explicitMeaningCorrection = Regex(
        "(?i)\\b(?:this|that|the)\\s+" +
            "(?:permission|prohibition|restriction|requirement|obligation|statement|" +
            "claim|wording|instruction|rule|information)\\s+" +
            "(?:is|was|remains?)\\s+" +
            "(?:incorrect|wrong|inaccurate|invalid|contradictory|unclear)|" +
            "\\b(?:should|must|needs?\\s+to)\\s+instead\\b|" +
            "\\b(?:correct|replace|revise|clarify)\\s+(?:this|that|the)\\b",
    )
    private val imperativeListWithModal = Regex(
        "(?im)^(?:\\[\\[MS_MARKDOWN_PREFIX_\\d{4}]]|\\s*[-*+]\\s+)" +
            "[^:\\n]+:\\s+[a-z][^\\n]{0,180}\\band\\s+" +
            "(?:he|she|they)\\s+(?:must|should|will|can)\\s+[a-z]",
    )
    private val imperativeListWithoutRepeatedModal = Regex(
        "(?im)^(?:\\[\\[MS_MARKDOWN_PREFIX_\\d{4}]]|\\s*[-*+]\\s+)" +
            "[^:\\n]+:\\s+[a-z][^\\n]{0,180}\\band\\s+[a-z]",
    )
    private val stopWords = setOf(
        "a", "an", "and", "are", "as", "at", "be", "been", "being", "but", "by",
        "for", "from", "had", "has", "have", "he", "her", "hers", "him", "his",
        "i", "in", "is", "it", "its", "me", "my", "of", "on", "or", "our", "ours",
        "she", "that", "the", "their", "theirs", "them", "they", "this", "to", "us",
        "was", "we", "were", "will", "with", "you", "your", "yours",
    )

    /**
     * Manuscript never emits an em dash. Permanent product rule, set by
     * Curtis on 2026-08-01. Models still produce them, and rejecting the
     * whole correction over punctuation threw away good grammar fixes, so
     * any em dash the model invents is rewritten deterministically before
     * validation. An em dash the writer typed themselves is left alone:
     * this rule governs our corrections, not their source text.
     */
    fun removeInventedEmDashes(original: String, candidate: String): String {
        if (EM_DASH !in candidate) return candidate
        if (EM_DASH in original) return candidate
        return candidate
            // " word — word " reads as a clause break: a comma carries it.
            .replace(Regex("""\s+""" + EM_DASH + """\s+"""), ", ")
            // "word—word" is a joiner: a hyphen preserves the reading.
            .replace(EM_DASH, '-')
    }

    const val EM_DASH = '—'

    fun validate(
        original: String,
        candidate: String,
        toneTransformation: Boolean = false,
        requiresVisibleChange: Boolean = toneTransformation,
    ): SafetyValidation {
        val hard = linkedSetOf<SafetyIssue>()
        val high = linkedSetOf<SafetyIssue>()
        if (candidate.isBlank()) hard += SafetyIssue.EMPTY_OUTPUT
        if (
            original.length >= 80 &&
            candidate.length < original.length * 0.40
        ) {
            hard += SafetyIssue.TRUNCATED_OUTPUT
        }
        if ('\u2014' in candidate) hard += SafetyIssue.EM_DASH_PRESENT
        if (
            requiresVisibleChange &&
            original.trim().replace(Regex("\\s+"), " ").equals(
                candidate.trim().replace(Regex("\\s+"), " "),
                ignoreCase = true,
            )
        ) {
            hard += SafetyIssue.UNCHANGED_TRANSFORMATION
        }
        val normalizedOriginal = normalizeMeaning(original)
        val normalizedCandidate = normalizeMeaning(candidate)
        if (
            placeholder.findAll(original).map(MatchResult::value).toList() !=
            placeholder.findAll(candidate).map(MatchResult::value).toList()
        ) {
            hard += SafetyIssue.PROTECTED_SPAN_CHANGED
        }
        // Source quotations are placeholders before this validator runs.
        // Any quote mark visible here was invented by the model and can turn
        // ordinary prose into a claim attributed to somebody else.
        if (quotationMarks(original) != quotationMarks(candidate)) {
            hard += SafetyIssue.PROTECTED_SPAN_CHANGED
        }
        // One unified number check. Written and numeric forms of the same value
        // compare equal ("three" == "3"), while a changed value, changed order,
        // or changed meridiem ("3pm" -> "5pm") stays a hard failure.
        if (numberTokens(normalizedOriginal) != numberTokens(normalizedCandidate)) {
            hard += SafetyIssue.PROTECTED_SPAN_CHANGED
        }
        if (entitySwapDetected(normalizedOriginal, normalizedCandidate)) {
            hard += SafetyIssue.PROTECTED_SPAN_CHANGED
        }
        if (
            markdownPrefix.findAll(original).map(MatchResult::value).toList() !=
            markdownPrefix.findAll(candidate).map(MatchResult::value).toList()
        ) {
            hard += SafetyIssue.MARKDOWN_HIERARCHY_CHANGED
        }
        if (original.count { it == '\n' } != candidate.count { it == '\n' }) {
            hard += SafetyIssue.LINE_STRUCTURE_CHANGED
        }
        val sourceExplicitlyRequiresMeaningCorrection =
            explicitMeaningCorrection.containsMatchIn(original)
        val originalNegations = negation.findAll(normalizedOriginal).count()
        val candidateNegations = negation.findAll(normalizedCandidate).count()
        // Repairing a double negative ("don't judge nobody" -> "doesn't judge
        // anyone") legitimately removes one negation per double-negative pair.
        val allowedNegationDrop =
            doubleNegative.findAll(normalizedOriginal).count()
        val negationPreserved =
            candidateNegations == originalNegations ||
                (
                    originalNegations - candidateNegations in 1..allowedNegationDrop
                    )
        // A requested tone rewrite may legitimately paraphrase a negated
        // clause ("nobody showed up" -> "attendance was delayed"), so modality
        // counting is advisory there; the strict correction paths stay hard.
        val modality =
            if (toneTransformation) high else hard
        if (!sourceExplicitlyRequiresMeaningCorrection && !negationPreserved) {
            modality += SafetyIssue.NEGATION_CHANGED
        }
        val listImperativePreservesObligation =
            obligation.findAll(normalizedOriginal).count() ==
            obligation.findAll(normalizedCandidate).count() + 1 &&
                imperativeListWithModal.containsMatchIn(original) &&
                imperativeListWithoutRepeatedModal.containsMatchIn(candidate)
        if (
            !sourceExplicitlyRequiresMeaningCorrection &&
            !listImperativePreservesObligation &&
            obligation.findAll(normalizedOriginal).count() !=
            obligation.findAll(normalizedCandidate).count()
        ) {
            modality += SafetyIssue.OBLIGATION_CHANGED
        }
        // A bare "can"/"may" count difference is usually a fluency repair
        // (dropping a redundant modal), so it is advisory. Real permission
        // flips add or remove a negated modal, which the prohibition and
        // negation hard checks still catch.
        if (
            !sourceExplicitlyRequiresMeaningCorrection &&
            permission.findAll(normalizedOriginal).count() !=
            permission.findAll(normalizedCandidate).count()
        ) {
            high += SafetyIssue.PERMISSION_CHANGED
        }
        if (
            !sourceExplicitlyRequiresMeaningCorrection &&
            prohibition.findAll(normalizedOriginal).count() !=
            prohibition.findAll(normalizedCandidate).count()
        ) {
            modality += SafetyIssue.PROHIBITION_CHANGED
        }

        val originalContent = contentWords(normalizedOriginal)
        val candidateContent = contentWords(normalizedCandidate)
        if (originalContent != candidateContent) {
            high += SafetyIssue.CONTENT_WORD_CHANGED
            if (
                !toneTransformation &&
                !doubleNegative.containsMatchIn(normalizedOriginal) &&
                !sourceExplicitlyRequiresMeaningCorrection &&
                suspiciousEqualCountSubstitution(originalContent, candidateContent)
            ) {
                hard += SafetyIssue.CONTENT_WORD_CHANGED
            }
        }
        val redundantModalCleanup =
            Regex("(?i)\\b(?:can|could|may|might) be able to\\b").containsMatchIn(original)
        val minimumContentRatio = if (redundantModalCleanup) 0.75 else 0.85
        if (
            originalContent.isNotEmpty() &&
            candidateContent.size < originalContent.size * minimumContentRatio
        ) {
            // A proofread that silently drops a quarter of the content words
            // deleted a claim; that is a hard failure. Deliberate rewrites
            // (Shorten and other tones) keep this advisory.
            if (toneTransformation) {
                high += SafetyIssue.CONTENT_REMOVED
            } else {
                hard += SafetyIssue.CONTENT_REMOVED
            }
        }
        val originalSentences = sentenceEnd.findAll(original).count()
        val candidateSentences = sentenceEnd.findAll(candidate).count()
        if (
            kotlin.math.abs(originalSentences - candidateSentences) > 1 &&
            kotlin.math.abs(originalSentences - candidateSentences) >
            maxOf(1, originalSentences / 2)
        ) {
            high += SafetyIssue.SENTENCE_STRUCTURE_CHANGED
        }
        if (toneTransformation) high += SafetyIssue.TONE_TRANSFORMATION
        return SafetyValidation(hard, high)
    }

    fun contentWordCountChanged(original: String, candidate: String): Boolean =
        contentWords(original).size != contentWords(candidate).size

    /**
     * Content-free counterpart of [diagnose] for production logs: counts only,
     * never a word from the writer's text, so a rejection can be diagnosed
     * from a device log without recording what the user wrote.
     */
    fun diagnoseCounts(original: String, candidate: String): String {
        val no = normalizeMeaning(original)
        val nc = normalizeMeaning(candidate)
        return listOf(
            "numbers=${numberTokens(no).size}->${numberTokens(nc).size}",
            "negations=${negation.findAll(no).count()}->${negation.findAll(nc).count()}",
            "obligations=${obligation.findAll(no).count()}->${obligation.findAll(nc).count()}",
            "prohibitions=${prohibition.findAll(no).count()}->${prohibition.findAll(nc).count()}",
            "entities=${entityWords(no).size}->${entityWords(nc).size}",
            "placeholders=${placeholder.findAll(original).count()}->" +
                "${placeholder.findAll(candidate).count()}",
            entitySwapDetail(no, nc),
        ).joinToString(",")
    }

    /** Diagnostic detail for evidence reports: which sub-check disagrees. */
    fun diagnose(original: String, candidate: String): String {
        val no = normalizeMeaning(original)
        val nc = normalizeMeaning(candidate)
        return listOf(
            "numbers=${numberTokens(no)}->${numberTokens(nc)}",
            "negations=${negation.findAll(no).count()}->${negation.findAll(nc).count()}",
            "doubleNegAllowance=${doubleNegative.findAll(no).count()}",
            "obligations=${obligation.findAll(no).count()}->${obligation.findAll(nc).count()}",
            "prohibitions=${prohibition.findAll(no).count()}->${prohibition.findAll(nc).count()}",
            "entities=${entityWords(no)}->${entityWords(nc)}",
        ).joinToString("; ")
    }

    private fun contentWords(value: String): List<String> =
        word.findAll(placeholder.replace(value, " "))
            .map { it.value.lowercase() }
            .filterNot(stopWords::contains)
            .toList()

    private fun quotationMarks(value: String): List<Char> =
        value.filter { it == '"' || it == '\u201c' || it == '\u201d' }.toList()

    private fun suspiciousEqualCountSubstitution(
        original: List<String>,
        candidate: List<String>,
    ): Boolean {
        if (original.size != candidate.size) return false
        val originalCounts = original.map(::semanticContentStem).groupingBy { it }.eachCount()
        val candidateCounts = candidate.map(::semanticContentStem).groupingBy { it }.eachCount()
        val missing = originalCounts.flatMap { (word, count) ->
            List((count - (candidateCounts[word] ?: 0)).coerceAtLeast(0)) { word }
        }
        val added = candidateCounts.flatMap { (word, count) ->
            List((count - (originalCounts[word] ?: 0)).coerceAtLeast(0)) { word }
        }
        if (missing.isEmpty() || missing.size != added.size || missing.size > 2) return false
        return missing.all { lost -> added.minOf { replacement -> editDistance(lost, replacement) } > 2 }
    }

    private fun semanticContentStem(value: String): String = when (val lower = entityStem(value)) {
        "forgot", "forgotten", "forget", "forgetting" -> "forget"
        "spoke", "spoken", "speak", "speaking" -> "speak"
        "learnt", "learned", "learn", "learning" -> "learn"
        "wrote", "written", "write", "writing" -> "write"
        "brought", "bring", "bringing" -> "bring"
        "sent", "send", "sending" -> "send"
        else -> lower.removeSuffix("ing").removeSuffix("ed")
    }

    // Adjacent written-number words ("two hundred", "twenty-two", "three
    // thousand five hundred") combine into one value so a style change
    // between digits and words never reads as a changed quantity.
    private fun numberTokens(value: String): List<String> {
        val text = placeholder.replace(value, " ")
        val matches = anyNumberToken.findAll(text).toList()
        val tokens = mutableListOf<String>()
        var index = 0
        while (index < matches.size) {
            val match = matches[index]
            val raw = match.value.lowercase()
            if (raw.first().isDigit()) {
                tokens += raw.replace(" ", "").replace(".", "")
                index++
                continue
            }
            var total = 0L
            var current = 0L
            var end = index
            while (end < matches.size && !matches[end].value.first().isDigit()) {
                if (end > index) {
                    val gap = text.substring(
                        matches[end - 1].range.last + 1,
                        matches[end].range.first,
                    )
                    if (!Regex("^[\\s-]*(?:and\\s+)?$").matches(gap)) break
                }
                matches[end].value.lowercase().split('-', ' ').forEach { word ->
                    when (word) {
                        "hundred" -> current = (if (current == 0L) 1L else current) * 100
                        "thousand" -> {
                            total += (if (current == 0L) 1L else current) * 1_000
                            current = 0
                        }
                        "million" -> {
                            total += (if (current == 0L) 1L else current) * 1_000_000
                            current = 0
                        }
                        "billion" -> {
                            total += (if (current == 0L) 1L else current) * 1_000_000_000
                            current = 0
                        }
                        else -> current += writtenNumberValues[word]?.toLong() ?: 0L
                    }
                }
                end++
            }
            tokens += (total + current).toString()
            index = end
        }
        return tokens
    }

    private val entityStopwords = (
        stopWords + setOf(
            "if", "when", "while", "because", "although", "though", "after",
            "before", "then", "next", "first", "second", "third", "finally",
            "also", "however", "meanwhile", "please", "yesterday", "today",
            "tomorrow", "tonight", "there", "here", "now", "yes", "no", "not",
            "never", "nobody", "nothing", "everyone", "everything", "every",
            "all", "some", "any", "each", "both", "more", "most", "other",
            "another", "new", "being", "due", "do", "does", "did", "don't",
            "doesn't", "didn't", "can't", "won't", "isn't", "aren't", "wasn't",
            "weren't", "it's", "let's", "what", "who", "why", "how", "where",
            "which", "one", "two", "three", "so", "but", "and", "or", "thanks",
            "thank", "dear", "hello", "hi", "ok", "okay", "despite", "during",
            "volunteers", "members", "everybody",
        )
        ).map { entityStem(it) }.toSet()

    /**
     * True when the candidate both drops a proper noun from the original and
     * introduces a different one - the signature of a swapped actor, place, or
     * product name. Pure capitalization repairs are excluded because the
     * lowercase form still exists in the other text.
     */
    /**
     * Content-free shape of an entity-swap rejection: how many stems went
     * missing, how many appeared, their lengths and the closest edit distance
     * between them. Enough to tell a spelling repair the tolerance missed
     * from a real name swap, without logging a word the writer typed.
     */
    private fun entitySwapDetail(original: String, candidate: String): String {
        val originalEntities = entityWords(original)
        val candidateEntities = entityWords(candidate)
        val originalLowerWords = allWordsLowercase(original)
        val candidateLowerWords = allWordsLowercase(candidate)
        val missing = originalEntities.filter { entity ->
            entity !in candidateEntities && entity !in candidateLowerWords
        }
        val added = candidateEntities.filter { entity ->
            entity !in originalEntities && entity !in originalLowerWords
        }
        val closest = missing.flatMap { lost ->
            added.map { addition ->
                "${lost.length}/${addition.length}d${editDistance(lost, addition)}"
            }
        }.take(4)
        return "entitySwap=missing${missing.size}+added${added.size}" +
            if (closest.isEmpty()) "" else "[${closest.joinToString("|")}]"
    }

    private fun entitySwapDetected(original: String, candidate: String): Boolean {
        val originalEntities = entityWords(original)
        val candidateEntities = entityWords(candidate)
        val originalLowerWords = allWordsLowercase(original)
        val candidateLowerWords = allWordsLowercase(candidate)
        var missing = originalEntities.filter { entity ->
            entity !in candidateEntities && entity !in candidateLowerWords
        }
        var added = candidateEntities.filter { entity ->
            entity !in originalEntities && entity !in originalLowerWords
        }
        // A missing/added pair that is nearly the same word is a spelling or
        // hyphenation repair ("Readyness" -> "Readiness"), not a swap.
        val repairedAdditions = added.filter { addition ->
            missing.any { lost -> isMinorSpellingRepair(lost, addition) }
        }
        missing = missing.filterNot { lost ->
            added.any { addition -> isMinorSpellingRepair(lost, addition) }
        }
        added = added - repairedAdditions.toSet()
        return missing.isNotEmpty() && added.isNotEmpty()
    }

    private fun isMinorSpellingRepair(a: String, b: String): Boolean {
        if (a == b) return true
        if (kotlin.math.abs(a.length - b.length) > 2) return false
        val shorter = minOf(a.length, b.length)
        // Short words need the one-edit allowance too. "Petts" -> "Pet" in a
        // heading is plainly a spelling repair, but a zero-edit rule for
        // words under four letters classified it as a proper-noun swap and
        // left the whole heading uncorrected. Genuine name swaps differ by
        // more than a single character.
        val allowedDistance = when {
            shorter >= 5 -> 2
            shorter >= 3 -> 1
            else -> 0
        }
        return editDistance(a, b) <= allowedDistance
    }

    private fun editDistance(a: String, b: String): Int {
        val previous = IntArray(b.length + 1) { it }
        val current = IntArray(b.length + 1)
        for (i in 1..a.length) {
            current[0] = i
            for (j in 1..b.length) {
                val substitution = previous[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1
                current[j] = minOf(previous[j] + 1, current[j - 1] + 1, substitution)
            }
            current.copyInto(previous)
        }
        return previous[b.length]
    }

    // Hyphenated words split into their parts so a hyphenation repair
    // ("Five Page" -> "Five-Page") never reads as new or lost entities.
    private fun entityWords(value: String): Set<String> =
        word.findAll(placeholder.replace(value, " "))
            .filter { it.value.first().isUpperCase() }
            .flatMap { it.value.split('-').asSequence() }
            .filter { it.isNotBlank() }
            .map { entityStem(it) }
            .filterNot(entityStopwords::contains)
            .toSet()

    private fun allWordsLowercase(value: String): Set<String> =
        word.findAll(placeholder.replace(value, " "))
            .flatMap { it.value.split('-').asSequence() }
            .filter { it.isNotBlank() }
            .map { entityStem(it) }
            .toSet()

    // Possessive and plural suffixes are stripped on BOTH sides, so repairing
    // "Saturdays event" to "Saturday's event" never looks like a name swap.
    private fun entityStem(value: String): String =
        value.lowercase()
            .removeSuffix("'s")
            .removeSuffix("’s")
            .removeSuffix("s")
}

object MeaningNeutralCleanup {
    data class Result(
        val text: String,
        val warnings: List<String>,
    )

    fun apply(value: String): Result {
        var cleaned = value
        cleaned = Regex("[ \\t]+([,.;:!?])").replace(cleaned, "$1")
        cleaned = Regex("([,;:!?])(?=[^\\s\\n])").replace(cleaned, "$1 ")
        cleaned = Regex("([!?.,])\\1+").replace(cleaned, "$1")
        val duplicateWords = Regex(
            "(?i)\\b([\\p{L}][\\p{L}'’\\-]*)[ \\t]+\\1\\b",
        ).findAll(cleaned).map { match ->
            "Repeated adjacent word: ${match.groupValues[1]}"
        }.toList()
        return Result(cleaned, duplicateWords)
    }
}

class DocumentCorrectionPipeline(
    private val primaryBackend: LocalRewriteBackend,
    private val fallbackBackend: LocalRewriteBackend? = null,
    private val acceptsGeneratedGrammar: (String) -> Boolean = { true },
    private val acceptsStrictlyReviewedGrammar: (String) -> Boolean =
        acceptsGeneratedGrammar,
    private val prepareGrammarRetryCandidate: (String) -> String = { it },
    // Model-free repair used when the model will not touch a sentence.
    private val deterministicSentenceRepair: (String) -> String =
        prepareGrammarRetryCandidate,
    private val preservesProofreadWording: (String, String) -> Boolean = { _, _ -> true },
    private val correctionCacheGet: (String) -> String? = { null },
    private val correctionCachePut: (String, String) -> Unit = { _, _ -> },
    // Content-free per-paragraph outcome trace. A section that comes back
    // unchanged must always be explainable from a log line alone.
    private val logParagraphOutcome: (String) -> Unit = {},
) {
    private val activeGenerationJobs = ConcurrentHashMap<String, String>()

    suspend fun process(
        request: DocumentCorrectionRequest,
        onParagraphCompleted: suspend (CorrectedParagraph) -> Unit = {},
        onProgress: (completed: Int, total: Int) -> Unit = { _, _ -> },
    ): DocumentCorrectionResult {
        val normalized = LosslessSourceNormalizer.normalize(request.source)
        val protected = ProtectedSpanCodec.protect(normalized.text)
        val chunks = ParagraphChunker.chunk(protected.encodedText)
        val corrected = mutableListOf<String>()
        val paragraphResults = mutableListOf<CorrectedParagraph>()
        chunks.forEachIndexed { offset, chunk ->
            if (chunk.text.isBlank()) {
                corrected += chunk.text + chunk.separatorAfter
                val blankParagraph = CorrectedParagraph(
                    index = chunk.index,
                    original = chunk.text,
                    firstPassText = chunk.text,
                    preReviewText = chunk.text,
                    text = chunk.text,
                    reviewed = false,
                    usedFallback = false,
                    validated = true,
                    risks = emptySet(),
                )
                paragraphResults += blankParagraph
                onParagraphCompleted(blankParagraph)
                onProgress(offset + 1, chunks.size)
                logParagraphOutcome(
                    "paragraph=${chunk.index}/${chunks.size};path=blank",
                )
                return@forEachIndexed
            }

            // There is deliberately no "this paragraph looks clean" shortcut.
            // The classifier called paragraphs clean that still carried real
            // errors, and the user saw whole sections come back untouched, so
            // every non-blank paragraph now goes to the model. The only paths
            // that skip generation are an exact cache hit (which replays an
            // earlier model result for identical text) and a hard failure,
            // both of which are logged.

            // Phase-4 cache: an identical paragraph corrected before (same
            // tone and mode) returns instantly. Tone switches, undo, and the
            // background prefetch all stop re-paying the model.
            val restoredChunkText = restoreFragment(protected, chunk.text)
            correctionCacheGet(restoredChunkText)?.let { cachedRestored ->
                val encodedCached = runCatching {
                    encodeFragment(protected, chunk.text, cachedRestored)
                }.getOrNull()
                if (encodedCached != null) {
                    LocalAiAttemptEvidence.recordTerminal(
                        request.jobId,
                        chunk.index,
                        LocalAiTerminalProvenance.CACHE_ONLY,
                    )
                    val cachedParagraph = CorrectedParagraph(
                        index = chunk.index,
                        original = chunk.text,
                        firstPassText = encodedCached,
                        preReviewText = encodedCached,
                        text = encodedCached,
                        reviewed = false,
                        usedFallback = false,
                        validated = true,
                        risks = emptySet(),
                    )
                    corrected += cachedParagraph.text + chunk.separatorAfter
                    onParagraphCompleted(
                        cachedParagraph.copy(
                            original = restoredChunkText,
                            firstPassText = cachedRestored,
                            preReviewText = cachedRestored,
                            text = cachedRestored,
                        ),
                    )
                    onProgress(offset + 1, chunks.size)
                    paragraphResults += cachedParagraph
                    logParagraphOutcome(
                        "paragraph=${chunk.index}/${chunks.size};path=cache;" +
                            "changed=${cachedRestored.trim() != restoredChunkText.trim()}",
                    )
                    return@forEachIndexed
                }
            }
            var outcomePath = "model"
            var outcomeDetail = ""
            var paragraph = try {
                correctParagraph(
                    document = request,
                    chunk = chunk,
                    acceptsGrammar = { candidate ->
                        acceptsGeneratedGrammar(restoreFragment(protected, candidate))
                    },
                    acceptsStrictReview = { candidate ->
                        acceptsStrictlyReviewedGrammar(
                            restoreFragment(protected, candidate),
                        )
                    },
                    prepareGrammarRetry = { candidate ->
                        val restoredCandidate = restoreFragment(protected, candidate)
                        val proposed = prepareGrammarRetryCandidate(restoredCandidate)
                        encodeFragment(protected, candidate, proposed)
                    },
                )
            } catch (failure: ParagraphCorrectionException) {
                // One veto-rejected paragraph must not fail the whole document.
                // Keep the model's meaning-safe correction when the only
                // objection was the advisory grammar veto. For a requested
                // tone rewrite whose chunk keeps violating the fact gates,
                // degrade that chunk to a validated plain proofread so the
                // rest of the document still transforms. Any other hard
                // failure fails the job so the user sees a real error.
                outcomeDetail = ";failures=" +
                    failure.validation?.hardFailures.orEmpty()
                        .joinToString("+") { it.name }
                        .ifEmpty { "none" } +
                    failure.rejectedCandidate
                        ?.let { ";counts=" + AiSafetyValidator.diagnoseCounts(chunk.text, it) }
                        .orEmpty()
                recoverParagraph(chunk, failure)
                    ?.also { outcomePath = "grammar-veto-kept" }
                    ?: proofreadFallbackParagraph(
                        request,
                        chunk,
                        failure,
                        { candidate ->
                            acceptsGeneratedGrammar(restoreFragment(protected, candidate))
                        },
                        { candidate ->
                            acceptsStrictlyReviewedGrammar(
                                restoreFragment(protected, candidate),
                            )
                        },
                        { candidate ->
                            val restoredCandidate = restoreFragment(protected, candidate)
                            val proposed = prepareGrammarRetryCandidate(restoredCandidate)
                            encodeFragment(protected, candidate, proposed)
                        },
                    )
                        ?.also { outcomePath = "proofread-fallback" }
                    ?: salvageParagraphBySentence(
                        request,
                        chunk,
                        acceptsGrammar = { candidate ->
                            acceptsGeneratedGrammar(restoreFragment(protected, candidate))
                        },
                        deterministicRepair = { sentence ->
                            val restored = restoreFragment(protected, sentence)
                            val proposed = deterministicSentenceRepair(restored)
                            encodeFragment(protected, sentence, proposed)
                        },
                    )
                        ?.also { outcomePath = "sentence-salvage" }
                    ?: keepOriginalParagraph(chunk, failure)
                        ?.also { outcomePath = "kept-original" }
                    ?: throw failure
            }
            val restoredBeforeSweep = restoreFragment(protected, paragraph.text)
            val repairedRestored = residualRepair(
                restoreFragment(protected, chunk.text),
                restoredBeforeSweep,
            )
            val encodedAfterSweep = runCatching {
                encodeFragment(protected, paragraph.text, repairedRestored)
            }.getOrNull() ?: paragraph.text
            val terminalProvenance = when {
                !paragraph.validated || outcomePath == "kept-original" ->
                    LocalAiTerminalProvenance.KEPT_ORIGINAL
                outcomePath == "sentence-salvage" ||
                    repairedRestored.trim() != restoredBeforeSweep.trim() ->
                    LocalAiTerminalProvenance.MODEL_PLUS_SAFE_REPAIRS
                else -> LocalAiTerminalProvenance.MODEL_ACCEPTED
            }
            LocalAiAttemptEvidence.recordTerminal(
                request.jobId,
                chunk.index,
                terminalProvenance,
            )
            paragraph = paragraph.copy(text = encodedAfterSweep)
            logParagraphOutcome(
                "paragraph=${chunk.index}/${chunks.size};path=$outcomePath;" +
                    "changed=${paragraph.text.trim() != chunk.text.trim()};" +
                    "len=${chunk.text.length}->${paragraph.text.length};" +
                    "validated=${paragraph.validated}$outcomeDetail",
            )
            corrected += paragraph.text + chunk.separatorAfter
            if (paragraph.validated) {
                runCatching {
                    correctionCachePut(
                        restoredChunkText,
                        restoreFragment(protected, paragraph.text),
                    )
                }
            }
            val restoredParagraph = paragraph.copy(
                original = restoreFragment(protected, paragraph.original),
                firstPassText = restoreFragment(protected, paragraph.firstPassText),
                preReviewText = restoreFragment(protected, paragraph.preReviewText),
                text = restoreFragment(protected, paragraph.text),
            )
            paragraphResults += restoredParagraph
            onParagraphCompleted(restoredParagraph)
            onProgress(offset + 1, chunks.size)
        }
        val encodedResult = corrected.joinToString("")
        val cleaned = MeaningNeutralCleanup.apply(encodedResult)
        val restored = protected.restore(cleaned.text)
        return DocumentCorrectionResult(
            text = normalized.restoreLineEndings(restored),
            paragraphs = paragraphResults,
            warnings = cleaned.warnings,
        )
    }

    suspend fun cancel(jobId: String) {
        val activeJobId = activeGenerationJobs.remove(jobId) ?: jobId
        primaryBackend.cancel(activeJobId)
        fallbackBackend?.cancel(activeJobId)
    }

    private fun recoverParagraph(
        chunk: ParagraphChunk,
        failure: ParagraphCorrectionException,
    ): CorrectedParagraph? {
        // A rejected candidate is safe to keep only when the meaning-preserving
        // validator passed and the sole hard failure was RESIDUAL_GRAMMAR_RISK
        // (the advisory grammar veto). In that case every protected fact, number,
        // and negation was preserved, so the correction is better than the raw
        // original. Any other hard failure means the candidate is not
        // trustworthy and the caller must surface the failure.
        val meaningSafeCandidate = failure.rejectedCandidate
            ?.takeIf { it.isNotBlank() }
            ?.takeIf {
                val validation = failure.validation ?: return@takeIf false
                validation.hardFailures.isNotEmpty() &&
                    validation.hardFailures.all { issue ->
                        issue == SafetyIssue.RESIDUAL_GRAMMAR_RISK
                    }
            }
            ?: return null
        return CorrectedParagraph(
            index = chunk.index,
            original = chunk.text,
            firstPassText = chunk.text,
            preReviewText = meaningSafeCandidate,
            text = meaningSafeCandidate,
            reviewed = false,
            usedFallback = false,
            validated = true,
            risks = failure.validation?.highRisks.orEmpty() +
                SafetyIssue.RESIDUAL_GRAMMAR_RISK,
        )
    }

    /**
     * A paragraph the validator vetoed as a whole is usually stubborn in one
     * sentence only: the model swaps a single word ("wants" to "needs") and
     * the modality gate rightly rejects the batch, which used to leave the
     * entire section with every one of its errors intact. Correct the
     * sentences one at a time instead. Each replacement is validated on its
     * own, and any sentence whose candidate fails keeps the writer's original
     * wording, so nothing unverified is presented as a correction. Returns
     * null when no sentence could be improved, and the caller falls back to
     * keeping the original paragraph.
     */
    private suspend fun salvageParagraphBySentence(
        document: DocumentCorrectionRequest,
        chunk: ParagraphChunk,
        deterministicRepair: (String) -> String,
        acceptsGrammar: (String) -> Boolean,
    ): CorrectedParagraph? {
        val pieces = SENTENCE_SPLIT.findAll(chunk.text)
            .map { it.value }
            .flatMap { piece ->
                // A run-on with no full stops is one enormous "sentence" the
                // model refuses outright, which left whole sections raw. Cut
                // an over-long piece at clause boundaries so each request is
                // something the model will actually repair.
                if (piece.length <= MAX_SALVAGE_PIECE) {
                    sequenceOf(piece)
                } else {
                    CLAUSE_SPLIT.findAll(piece).map { it.value }
                }
            }
            .toList()
        if (pieces.size < 2) {
            logParagraphOutcome(
                "salvage=skipped;paragraph=${chunk.index};sentences=${pieces.size}",
            )
            return null
        }

        var rejected = 0
        var repaired = 0
        var skipped = 0
        val risks = linkedSetOf<SafetyIssue>()
        val rebuilt = StringBuilder()
        pieces.forEach { piece ->
            val sentence = piece.trim()
            if (sentence.isEmpty()) {
                rebuilt.append(piece)
                return@forEach
            }
            // Only broken sentences are worth a model call. Every call costs
            // about nine seconds of prompt processing on a phone regardless
            // of length, and a paragraph is usually vetoed over one sentence,
            // so re-generating the clean ones was most of the salvage cost.
            if (acceptsGrammar(sentence)) {
                skipped += 1
                rebuilt.append(piece)
                return@forEach
            }
            val candidate = runCatching {
                generateValidated(
                    backend = primaryBackend,
                    document = document,
                    chunk = chunk,
                    operation = WritingOperation.CORRECT_ONLY,
                    generationTarget = sentence,
                    immutableOriginal = sentence,
                    toneTransformation = false,
                    allowFallback = false,
                    acceptsGrammar = { true },
                )
            }.getOrNull()
            if (candidate == null || candidate.text.isBlank()) {
                // The model could not produce a fact-safe rewrite of this one
                // sentence. Leaving it verbatim left visible misspellings in
                // the middle of an otherwise corrected paragraph, so fall
                // back to the deterministic repair layer, which only fixes
                // spelling and punctuation and cannot change meaning. It is
                // still validated before it is kept.
                rejected += 1
                val repairedSentence = runCatching { deterministicRepair(sentence) }
                    .getOrNull()
                    ?.takeIf { it.isNotBlank() && it.trim() != sentence }
                    ?.takeIf { proposal ->
                        AiSafetyValidator.validate(
                            original = sentence,
                            candidate = proposal,
                            toneTransformation = false,
                            requiresVisibleChange = false,
                        ).safe
                    }
                if (repairedSentence != null) {
                    repaired += 1
                    rebuilt.append(piece.replace(sentence, repairedSentence.trim()))
                } else {
                    rebuilt.append(piece)
                }
                return@forEach
            }
            if (candidate.text.trim() != sentence) repaired += 1
            risks += candidate.validation.highRisks
            rebuilt.append(piece.replace(sentence, candidate.text.trim()))
        }
        logParagraphOutcome(
            "salvage=${if (repaired == 0) "empty" else "applied"};" +
                "paragraph=${chunk.index};sentences=${pieces.size};" +
                "repaired=$repaired;rejected=$rejected;skipped=$skipped",
        )
        if (repaired == 0) return null

        val text = rebuilt.toString()
        return CorrectedParagraph(
            index = chunk.index,
            original = chunk.text,
            firstPassText = text,
            preReviewText = text,
            text = text,
            reviewed = false,
            usedFallback = false,
            validated = true,
            risks = risks,
        )
    }

    /**
     * Last resort: one stubborn chunk must not destroy a whole-document run.
     * The chunk keeps the user's ORIGINAL text, is marked not validated, and
     * carries the hard failures as risks so the review UI can say this
     * paragraph was left unchanged. Rejected model text is never used here,
     * so nothing unverified is presented as a correction. Empty and truncated
     * failures still fail the job: they signal a broken engine, not a
     * stubborn paragraph.
     */
    private fun keepOriginalParagraph(
        chunk: ParagraphChunk,
        failure: ParagraphCorrectionException,
    ): CorrectedParagraph? {
        val hardFailures = failure.validation?.hardFailures.orEmpty()
        if (
            hardFailures.isEmpty() ||
            SafetyIssue.EMPTY_OUTPUT in hardFailures ||
            SafetyIssue.TRUNCATED_OUTPUT in hardFailures ||
            SafetyIssue.UNCHANGED_TRANSFORMATION in hardFailures
        ) {
            return null
        }
        return CorrectedParagraph(
            index = chunk.index,
            original = chunk.text,
            firstPassText = chunk.text,
            preReviewText = chunk.text,
            text = chunk.text,
            reviewed = false,
            usedFallback = false,
            validated = false,
            risks = failure.validation?.highRisks.orEmpty() + hardFailures,
        )
    }

    /**
     * Tone-only rescue: correct the chunk as a plain proofread so the
     * document completes with grammar fixed even where the model could not
     * produce a fact-safe rewrite in the selected tone. Returns null for
     * non-tone documents or when the proofread also fails.
     */
    private suspend fun proofreadFallbackParagraph(
        document: DocumentCorrectionRequest,
        chunk: ParagraphChunk,
        failure: ParagraphCorrectionException,
        acceptsGrammar: (String) -> Boolean,
        acceptsStrictReview: (String) -> Boolean,
        prepareGrammarRetry: (String) -> String,
    ): CorrectedParagraph? {
        if (document.tone in setOf(WritingTone.PROOFREAD, WritingTone.IMPROVE)) {
            return null
        }
        // Rescue applies only to fact-gate rejections. An unchanged, empty,
        // truncated, or em-dash answer keeps failing loudly: silently
        // downgrading those would let a tone job pretend it ran.
        val factGates = setOf(
            SafetyIssue.PROTECTED_SPAN_CHANGED,
            SafetyIssue.NEGATION_CHANGED,
            SafetyIssue.OBLIGATION_CHANGED,
            SafetyIssue.PROHIBITION_CHANGED,
            SafetyIssue.CONTENT_REMOVED,
            SafetyIssue.RESIDUAL_GRAMMAR_RISK,
        )
        val hardFailures = failure.validation?.hardFailures.orEmpty()
        if (hardFailures.isEmpty() || !factGates.containsAll(hardFailures)) {
            return null
        }
        val proofread = runCatching {
            correctParagraph(
                document = document.copy(tone = WritingTone.PROOFREAD),
                chunk = chunk,
                acceptsGrammar = acceptsGrammar,
                acceptsStrictReview = acceptsStrictReview,
                prepareGrammarRetry = prepareGrammarRetry,
            )
        }.getOrNull() ?: return null
        return proofread.copy(
            risks = proofread.risks + SafetyIssue.TONE_TRANSFORMATION,
        )
    }

    private suspend fun correctParagraph(
        document: DocumentCorrectionRequest,
        chunk: ParagraphChunk,
        acceptsGrammar: (String) -> Boolean,
        acceptsStrictReview: (String) -> Boolean,
        prepareGrammarRetry: (String) -> String,
    ): CorrectedParagraph {
        // Give the AI a meaning-neutral spelling and punctuation floor.
        // Small local models waste their limited context copying deliberate
        // misspellings, then never reach the sentence structure. The floor is
        // validated against the untouched chunk later, and every section
        // still receives a live model generation.
        val preparedTarget = runCatching { prepareGrammarRetry(chunk.text) }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?: chunk.text
        val initial = when (document.tone) {
            WritingTone.IMPROVE -> generateValidated(
                backend = primaryBackend,
                document = document,
                chunk = chunk,
                operation = WritingOperation.POLISH_PRESERVE_VOICE,
                generationTarget = preparedTarget,
                immutableOriginal = chunk.text,
                toneTransformation = false,
                allowFallback = true,
                acceptsGrammar = acceptsGrammar,
            )
            else -> generateValidated(
                backend = primaryBackend,
                document = document,
                chunk = chunk,
                operation = WritingOperation.CORRECT_ONLY,
                generationTarget = preparedTarget,
                immutableOriginal = chunk.text,
                toneTransformation = false,
                allowFallback = true,
                acceptsGrammar = acceptsGrammar,
            )
        }

        val styled = when (document.tone) {
            WritingTone.PROOFREAD,
            WritingTone.IMPROVE,
            -> initial
            else -> generateValidated(
                backend = primaryBackend,
                document = document,
                chunk = chunk,
                operation = WritingOperation.TONE_TRANSFORM,
                generationTarget = initial.text,
                immutableOriginal = chunk.text,
                toneTransformation = true,
                allowFallback = true,
                acceptsGrammar = acceptsGrammar,
            ).mergeFallback(initial)
        }

        // Best Quality pays for the second full review. A measured 4B Auto
        // run with review took 6.5 minutes for ten sections and still left
        // errors, so Auto keeps one AI pass plus the meaning-neutral cleanup
        // floor. Faster also keeps one pass.
        // CONTENT_WORD_CHANGED from same-count substitutions never forces the
        // second pass: fixing a misspelling always changes the content-word
        // list, so treating it as review-worthy doubled the latency of nearly
        // every proofread. Added or removed content words still review.
        val reviewTriggeringRisks =
            if (
                SafetyIssue.CONTENT_WORD_CHANGED in styled.validation.highRisks &&
                AiSafetyValidator.contentWordCountChanged(chunk.text, styled.text)
            ) {
                styled.validation.highRisks
            } else {
                styled.validation.highRisks - SafetyIssue.CONTENT_WORD_CHANGED
            }
        val needsReview = document.qualityMode == QualityMode.THOROUGH
        val finalCandidate = if (needsReview && !styled.usedFallback) {
            reviewCandidate(
                document,
                chunk,
                styled,
                acceptsGrammar,
                acceptsStrictReview,
                prepareGrammarRetry,
            )
        } else {
            styled
        }
        if (!finalCandidate.validation.safe) {
            throw ParagraphCorrectionException(
                chunk.index,
                "AI could not produce a validated correction.",
            )
        }
        return CorrectedParagraph(
            index = chunk.index,
            original = chunk.text,
            firstPassText = initial.text,
            preReviewText = styled.text,
            text = finalCandidate.text,
            reviewed = needsReview && !styled.usedFallback,
            usedFallback = finalCandidate.usedFallback,
            validated = true,
            risks = finalCandidate.validation.highRisks,
        )
    }

    /**
     * Last sweep before a correction is shown. The model routinely repairs the
     * opening sentences of a badly broken paragraph and hands the rest back
     * untouched; that output still validates, so the user was shown a
     * half-corrected section with raw misspellings in it. When the
     * deterministic quality gate still sees errors, run the model-free repair
     * (spelling, spacing, duplicated words, punctuation) over the candidate
     * and keep it only if it too preserves meaning.
     */
    private fun residualRepair(
        original: String,
        candidate: String,
    ): String {
        // Deliberately NOT gated on acceptsGrammar: that gate scores grammar
        // rules and knows nothing about spelling, so it called a paragraph
        // full of "peeple", "disaagree" and "hoem" acceptable and the sweep
        // never ran. The repair itself is model-free and meaning-neutral, so
        // running it on every candidate is safe; validation still decides.
        if (candidate.isBlank()) return candidate
        // Punctuation cleanup runs on the joined document, but the review
        // card shows and accepts ONE paragraph at a time, so that cleanup
        // never reached the text the user actually keeps: doubled commas
        // ("need,,") survived all the way onto the screen. Apply it per
        // paragraph as well; it is meaning-neutral by construction.
        val tidied = MeaningNeutralCleanup.apply(candidate).text
        val raw = runCatching { deterministicSentenceRepair(tidied) }
            .getOrNull()
            ?: tidied
        val proposal = raw.takeIf { it.isNotBlank() && it != candidate }
        if (proposal == null) {
            logParagraphOutcome("sweep=nochange;len=${candidate.length}")
            return candidate
        }
        val validation = AiSafetyValidator.validate(
            original = original,
            candidate = proposal,
            toneTransformation = false,
            requiresVisibleChange = false,
        )
        // Modality counts are expected to move here and must not veto the
        // sweep. The repair only rewrites misspelled words, so restoring
        // "neesd" to "need" makes an obligation word appear where the
        // validator previously saw gibberish; rejecting on that handed the
        // user back the raw misspellings. Numbers, protected spans, entities
        // and content removal stay strict.
        val blocking = validation.hardFailures - SPELLING_NEUTRAL_FAILURES
        logParagraphOutcome(
            "sweep=${if (blocking.isEmpty()) "applied" else "rejected"};" +
                "len=${candidate.length}->${proposal.length};" +
                "failures=" + validation.hardFailures.joinToString("+") { it.name }
                    .ifEmpty { "none" },
        )
        if (blocking.isEmpty()) return proposal

        // A single aggressive or unsafe sentence must not cancel harmless
        // spelling and punctuation repairs elsewhere in the paragraph. Each
        // sentence is validated against the already-safe model candidate,
        // then only independently safe repairs are composed.
        var partialRepairs = 0
        val partial = SENTENCE_SPLIT.replace(candidate) { match ->
            val sentence = match.value
            val repaired = runCatching { deterministicSentenceRepair(sentence) }
                .getOrNull()
                ?.takeIf { it.isNotBlank() && it != sentence }
                ?: return@replace sentence
            val sentenceValidation = AiSafetyValidator.validate(
                original = sentence,
                candidate = repaired,
                toneTransformation = false,
                requiresVisibleChange = false,
            )
            val sentenceBlocking = sentenceValidation.hardFailures - SPELLING_NEUTRAL_FAILURES
            if (sentenceBlocking.isEmpty() || sameLexicalTokens(sentence, repaired)) {
                partialRepairs += 1
                repaired
            } else {
                sentence
            }
        }
        logParagraphOutcome("sweep=partial;repairs=$partialRepairs")
        return partial
    }

    private fun sameLexicalTokens(left: String, right: String): Boolean {
        fun tokens(value: String): List<String> =
            Regex("[\\p{L}\\p{N}]+")
                .findAll(value)
                .map { it.value.lowercase() }
                .toList()
        return tokens(left) == tokens(right)
    }

    private suspend fun reviewCandidate(
        document: DocumentCorrectionRequest,
        chunk: ParagraphChunk,
        candidate: ValidatedCandidate,
        acceptsGrammar: (String) -> Boolean,
        acceptsStrictReview: (String) -> Boolean,
        prepareGrammarRetry: (String) -> String,
    ): ValidatedCandidate {
        var lastValidation: SafetyValidation? = null
        var lastCandidate: String? = null
        var reviewInput = candidate.text
        repeat(2) { attempt ->
            val rewriteProposedProofread =
                attempt == 1 && document.tone == WritingTone.PROOFREAD
            val request = generationRequest(
                document = document,
                chunk = chunk,
                operation = if (rewriteProposedProofread) {
                    WritingOperation.CORRECT_ONLY
                } else {
                    WritingOperation.REVIEW_CANDIDATE
                },
                target = if (rewriteProposedProofread) reviewInput else chunk.text,
                firstPassCandidate = if (rewriteProposedProofread) null else reviewInput,
                strictRetry = attempt == 1,
                stage = "review",
                includeContext = !rewriteProposedProofread,
            )
            val result = generate(primaryBackend, document.jobId, request)
            val reviewedText = result.text.takeIf { result.completed }.orEmpty()
            lastCandidate = reviewedText
            val validation = AiSafetyValidator.validate(
                original = chunk.text,
                candidate = reviewedText,
                toneTransformation =
                    document.tone !in setOf(WritingTone.PROOFREAD, WritingTone.IMPROVE),
                requiresVisibleChange = document.tone != WritingTone.PROOFREAD,
            )
            val grammarAccepted =
                validation.safe &&
                    (
                        acceptsGrammar(reviewedText) ||
                            (
                                attempt == 1 &&
                                    acceptsStrictReview(reviewedText)
                                )
                        )
            lastValidation = if (grammarAccepted || !validation.safe) {
                validation
            } else {
                validation.copy(
                    hardFailures =
                        validation.hardFailures + SafetyIssue.RESIDUAL_GRAMMAR_RISK,
                )
            }
            if (grammarAccepted) {
                return ValidatedCandidate(
                    reviewedText,
                    validation,
                    candidate.usedFallback,
                )
            }
            if (validation.safe) {
                reviewInput = prepareGrammarRetry(reviewedText)
            }
        }
        if (fallbackBackend != null) {
            return generateWithFallbackOnly(document, chunk, acceptsGrammar)
        }
        throw ParagraphCorrectionException(
            chunk.index,
            validationFailureMessage("review", lastValidation),
            stage = "review",
            rejectedCandidate = lastCandidate,
            validation = lastValidation,
        )
    }

    private suspend fun generateValidated(
        backend: LocalRewriteBackend,
        document: DocumentCorrectionRequest,
        chunk: ParagraphChunk,
        operation: WritingOperation,
        generationTarget: String,
        immutableOriginal: String,
        toneTransformation: Boolean,
        allowFallback: Boolean,
        acceptsGrammar: (String) -> Boolean,
    ): ValidatedCandidate {
        var lastValidation: SafetyValidation? = null
        var lastCandidate: String? = null
        repeat(2) { attempt ->
            val generationRequest = generationRequest(
                document = document,
                chunk = chunk,
                operation = operation,
                target = generationTarget,
                firstPassCandidate = null,
                strictRetry = attempt == 1 ||
                    (
                        operation == WritingOperation.CORRECT_ONLY &&
                            document.qualityMode != QualityMode.THOROUGH &&
                            generationTarget != immutableOriginal
                        ),
                stage = operation.name.lowercase(),
            )
            val result = generate(backend, document.jobId, generationRequest)
            val text = result.text.takeIf { result.completed }.orEmpty()
            lastCandidate = text
            val safetyValidation = AiSafetyValidator.validate(
                original = immutableOriginal,
                candidate = text,
                toneTransformation = toneTransformation,
                requiresVisibleChange = operation != WritingOperation.CORRECT_ONLY,
            )
            val validation = if (
                document.tone == WritingTone.PROOFREAD &&
                operation == WritingOperation.CORRECT_ONLY &&
                !preservesProofreadWording(generationTarget, text)
            ) {
                safetyValidation.copy(
                    hardFailures = safetyValidation.hardFailures +
                        SafetyIssue.CONTENT_WORD_CHANGED,
                )
            } else {
                safetyValidation
            }
            lastValidation = validation
            if (validation.safe) {
                // A model that echoes its input back has not corrected
                // anything, so always spend the second attempt on a strict
                // retry. This deliberately does NOT consult the rule gate:
                // "A Parrot Make a Colorful Pet in Cairns" passes every
                // deterministic rule (they only cover pronoun subjects) yet
                // is plainly wrong, and gating the retry on the rules let
                // that heading reach the user untouched. A genuinely clean
                // paragraph costs one short extra generation and is then
                // accepted unchanged on the last attempt.
                // Only short targets earn the extra attempt. Headings are
                // where an echo actually hides an error; a long paragraph
                // that comes back identical is almost always already fine,
                // and the retry costs a full nine-second prefill.
                val echoedWithoutChange = attempt == 0 &&
                    generationTarget.length <= MAX_ECHO_RETRY_CHARS &&
                    cosmeticallyIdentical(text, generationTarget)
                if (!echoedWithoutChange) {
                    return ValidatedCandidate(text, validation, usedFallback = false)
                }
            }
        }
        lastCandidate
            ?.takeIf { lastValidation?.safe == true }
            ?.let { echoed ->
                return ValidatedCandidate(echoed, lastValidation!!, usedFallback = false)
            }
        if (allowFallback && fallbackBackend != null) {
            return generateWithFallbackOnly(document, chunk, acceptsGrammar)
        }
        throw ParagraphCorrectionException(
            chunk.index,
            validationFailureMessage(operation.name, lastValidation),
            stage = operation.name,
            rejectedCandidate = lastCandidate,
            validation = lastValidation,
        )
    }

    private suspend fun generateWithFallbackOnly(
        document: DocumentCorrectionRequest,
        chunk: ParagraphChunk,
        acceptsGrammar: (String) -> Boolean,
    ): ValidatedCandidate {
        val fallback = fallbackBackend ?: throw ParagraphCorrectionException(
            chunk.index,
            "AI failed paragraph validation and no fallback is installed.",
        )
        val operation = when (document.tone) {
            WritingTone.PROOFREAD -> WritingOperation.CORRECT_ONLY
            WritingTone.IMPROVE -> WritingOperation.POLISH_PRESERVE_VOICE
            else -> WritingOperation.TONE_TRANSFORM
        }
        val fallbackRequest = generationRequest(
                document = document,
                chunk = chunk,
                operation = operation,
                target = chunk.text,
                firstPassCandidate = null,
                strictRetry = true,
                stage = "fallback",
            )
        val fallbackResult = generate(
            fallback,
            document.jobId,
            fallbackRequest,
        )
        val text = fallbackResult.text.takeIf { fallbackResult.completed }.orEmpty()
        val validation = AiSafetyValidator.validate(
            original = chunk.text,
            candidate = text,
            toneTransformation =
                document.tone !in setOf(WritingTone.PROOFREAD, WritingTone.IMPROVE),
            requiresVisibleChange = document.tone != WritingTone.PROOFREAD,
        )
        if (!validation.safe || !acceptsGrammar(text)) {
            throw ParagraphCorrectionException(
                chunk.index,
                if (!validation.safe) {
                    "Primary and fallback AI both failed paragraph validation."
                } else {
                    "Primary and fallback AI both left residual grammar risk."
                },
            )
        }
        return ValidatedCandidate(text, validation, usedFallback = true)
    }

    private fun generationRequest(
        document: DocumentCorrectionRequest,
        chunk: ParagraphChunk,
        operation: WritingOperation,
        target: String,
        firstPassCandidate: String?,
        strictRetry: Boolean,
        stage: String,
        includeContext: Boolean = true,
    ) = LocalGenerationRequest(
        jobId = "${document.jobId}-p${chunk.index}-$stage",
        operation = operation,
        // Tone transforms get NO neighbor context: live S23 evidence showed
        // the local model rewriting the reference paragraph instead of the
        // target when both were present. Proofread keeps context because it
        // needs it for cross-sentence agreement.
        previousContext = chunk.previousContext.takeIf {
            includeContext && operation != WritingOperation.TONE_TRANSFORM
        },
        originalTarget = target,
        firstPassCandidate = firstPassCandidate,
        nextContext = chunk.nextContext.takeIf {
            includeContext && operation != WritingOperation.TONE_TRANSFORM
        },
        qualityMode = document.qualityMode,
        strictRetry = strictRetry,
        tone = document.tone,
    )

    private suspend fun generate(
        backend: LocalRewriteBackend,
        documentJobId: String,
        request: LocalGenerationRequest,
    ): LocalGenerationResult {
        activeGenerationJobs[documentJobId] = request.jobId
        LocalAiAttemptEvidence.record(documentJobId, request.jobId)
        return try {
            val result = backend.generate(request)
            val repaired = AiSafetyValidator.removeInventedEmDashes(
                request.originalTarget,
                repairPlaceholderIdentity(
                    request.originalTarget,
                    repairPlaceholderBrackets(result.text),
                ),
            )
            result.copy(text = repaired)
        } finally {
            activeGenerationJobs.remove(documentJobId, request.jobId)
        }
    }

    private companion object {
        /**
         * True when the only difference is cosmetic. A model that answers
         * "A Parrot Make a Colorful Pett in Cairns" with the same words plus
         * a full stop has corrected nothing, and comparing raw strings let
         * that through as a real answer.
         */
        fun cosmeticallyIdentical(candidate: String, target: String): Boolean {
            fun key(value: String) = value
                .trim()
                .replace(Regex("\\s+"), " ")
                .trimEnd('.', '!', '?', ',', ';', ':')
                .lowercase()
            return key(candidate) == key(target)
        }

        // Meaning checks that a pure spelling repair legitimately moves.
        private val SPELLING_NEUTRAL_FAILURES = setOf(
            SafetyIssue.OBLIGATION_CHANGED,
            SafetyIssue.PERMISSION_CHANGED,
            SafetyIssue.PROHIBITION_CHANGED,
            SafetyIssue.NEGATION_CHANGED,
        )

        // Above this length an echoed answer is accepted without a retry.
        private const val MAX_ECHO_RETRY_CHARS = 240

        // Longest piece handed to the model during salvage.
        private const val MAX_SALVAGE_PIECE = 320

        // Clause boundary: after a comma, semicolon or colon, or before a
        // coordinating conjunction. Used only to break up a run-on.
        private val CLAUSE_SPLIT = Regex(
            "[^,;:]{1,320}(?:[,;:]\\s*|$)",
        )

        // One sentence plus the whitespace that follows it, so rejoining the
        // pieces reproduces the paragraph byte for byte.
        private val SENTENCE_SPLIT =
            Regex("[^.!?\\n]+(?:[.!?]+[\"'”’)\\]]*)?\\s*")

        // Small models sometimes echo a protected token without its double
        // brackets ("MS_NAME_0003" instead of "[[MS_NAME_0003]]"). The token
        // text itself is immutable protected content, so restoring the exact
        // bracket wrapper is meaning-neutral and lets validation compare the
        // real placeholder lists instead of rejecting the whole paragraph.
        private val degeneratePlaceholder =
            Regex("\\[{0,2}(MS_[A-Z_]+_\\d{4})]{0,2}")


        fun repairPlaceholderBrackets(text: String): String =
            degeneratePlaceholder.replace(text) { match -> "[[${match.groupValues[1]}]]" }

        // Small models occasionally echo a protected token with the wrong
        // identity ("[[MS_TIME_0000]]" where the target held
        // "[[MS_MEASUREMENT_0001]]"). When the counts match, mapping the
        // candidate's tokens back to the target's tokens in order is
        // meaning-neutral: the token content is restored from the target
        // either way. A count mismatch is left for validation to reject.
        fun repairPlaceholderIdentity(target: String, candidate: String): String {
            val expected = ProtectedDocument.PLACEHOLDER
                .findAll(target).map(MatchResult::value).toList()
            val actual = ProtectedDocument.PLACEHOLDER
                .findAll(candidate).map(MatchResult::value).toList()
            if (expected.isEmpty() || actual.size != expected.size || actual == expected) {
                return candidate
            }
            var index = 0
            return ProtectedDocument.PLACEHOLDER.replace(candidate) {
                expected[index++]
            }
        }
    }

    private fun restoreFragment(
        protected: ProtectedDocument,
        fragment: String,
    ): String {
        var restored = fragment
        protected.placeholders.forEach { span ->
            restored = restored.replace(span.token, span.original)
        }
        return restored
    }

    private fun encodeFragment(
        protected: ProtectedDocument,
        encodedTemplate: String,
        restoredCandidate: String,
    ): String {
        var encoded = restoredCandidate
        val spansByToken = protected.placeholders.associateBy(ProtectedSpan::token)
        ProtectedDocument.PLACEHOLDER.findAll(encodedTemplate).forEach { match ->
            val span = requireNotNull(spansByToken[match.value]) {
                "Unknown protected placeholder in grammar retry."
            }
            val location = encoded.indexOf(span.original)
            require(location >= 0) {
                "Grammar retry changed protected text."
            }
            encoded = encoded.replaceRange(
                location,
                location + span.original.length,
                span.token,
            )
        }
        return encoded
    }

    private fun validationFailureMessage(
        stage: String,
        validation: SafetyValidation?,
    ): String {
        val issues = validation?.hardFailures
            ?.map(SafetyIssue::name)
            ?.sorted()
            ?.joinToString()
            .orEmpty()
        return if (issues.isEmpty()) {
            "AI failed $stage validation."
        } else {
            "AI failed $stage validation: $issues."
        }
    }

    private data class ValidatedCandidate(
        val text: String,
        val validation: SafetyValidation,
        val usedFallback: Boolean,
    ) {
        fun mergeFallback(previous: ValidatedCandidate): ValidatedCandidate =
            copy(usedFallback = usedFallback || previous.usedFallback)
    }
}
