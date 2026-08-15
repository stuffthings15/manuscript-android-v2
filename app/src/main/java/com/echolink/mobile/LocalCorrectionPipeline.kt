package com.echolink.mobile

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.text.Normalizer
import java.util.Locale
import kotlin.math.exp

object CorrectionErrorEvidence {
    fun hasErrors(source: String): Boolean =
        source.isNotBlank() && GrammarSafetyNet.repair(source).trim() != source.trim()
}

enum class CorrectionRoute {
    CLEAN,
    RULES_ONLY,
    QWEN_GRAMMAR,
    QWEN_TONE,
}

enum class CorrectionIssue {
    SPELLING,
    PUNCTUATION,
    GRAMMAR,
    STRUCTURE,
    FORMATTING,
    TONE,
}

data class CorrectionAnalysis(
    val preparedText: String,
    val route: CorrectionRoute,
    val issues: Set<CorrectionIssue>,
    val acceptableProbability: Float?,
)

data class FastCorrectionResult(
    val text: String,
    val issues: Set<CorrectionIssue>,
)

class LocalCorrectionRouter(context: Context) {
    private val fastLayer = FastCorrectionLayer(context.applicationContext)
    private val classifier = Int8GrammarClassifier(context.applicationContext)

    fun analyze(source: String, tone: WritingTone): CorrectionAnalysis {
        val fast = fastLayer.correct(source)
        val structuralIssues = detectStructuralIssues(fast.text)
        if (tone != WritingTone.PROOFREAD) {
            return CorrectionAnalysis(
                preparedText = fast.text,
                route = CorrectionRoute.QWEN_TONE,
                issues = fast.issues + structuralIssues + CorrectionIssue.TONE,
                acceptableProbability = classifier.acceptableProbability(fast.text),
            )
        }

        val probability = classifier.acceptableProbability(fast.text)
        val residualRuleErrors = GrammarQualityGate.errorScore(fast.text)
        val route = when {
            fast.text == source &&
                structuralIssues.isEmpty() &&
                probability != null &&
                probability >= CLEAN_THRESHOLD ->
                CorrectionRoute.CLEAN
            residualRuleErrors == 0 &&
                structuralIssues.isEmpty() &&
                probability != null &&
                probability >= RULES_THRESHOLD &&
                editRatio(source, fast.text) <= MAX_RULE_EDIT_RATIO ->
                CorrectionRoute.RULES_ONLY
            else -> CorrectionRoute.QWEN_GRAMMAR
        }
        val issues = if (route == CorrectionRoute.QWEN_GRAMMAR) {
            fast.issues + structuralIssues + CorrectionIssue.GRAMMAR
        } else {
            fast.issues + structuralIssues
        }
        return CorrectionAnalysis(fast.text, route, issues, probability)
    }

    // There is deliberately no isConfidentlyClean() helper any more. The
    // document pipeline used it to copy "clean" paragraphs through without
    // the model, and paragraphs carrying real errors scored above the clean
    // threshold, so whole sections came back uncorrected. Every paragraph
    // now goes to the model; this router only classifies and gates output.

    /**
     * Meaning-neutral repair with no model involved: spelling, spacing,
     * duplicated words, punctuation. Used as the floor for a sentence the
     * model refused to rewrite safely, so a paragraph is never handed back
     * with raw misspellings sitting in the middle of corrected text.
     */
    fun deterministicRepair(text: String): String =
        GrammarSafetyNet.repair(
            HeadingAgreementRepair.apply(
                ContractionRepair.apply(
                    fastLayer.correct(trustedContextualTypos(shoutedWordsCalmed(text))).text,
                ),
            ),
        )

    fun verifiesDeterministicRepair(source: String, proposal: String): Boolean {
        if (proposal != deterministicRepair(source)) return false
        val trustedBaseline = GrammarSafetyNet.repair(trustedContextualTypos(source))
        return ProofreadPreservationPolicy.preservesWording(
            trustedBaseline,
            proposal,
            fastLayer::knowsWord,
        )
    }

    private fun trustedContextualTypos(value: String): String = value
        .replace(Regex("(?i)\\bgardin\\b"), "guarding")
        .replace(Regex("(?i)\\bsnese\\b"), "sense")
        .replace(Regex("(?i)\\bgivse\\b"), "gives")
        .replace(Regex("(?i)\\bcorretc\\b"), "correct")
        .replace(Regex("(?i)\\bgrate(?=\\s+[a-z][a-z'-]*s\\b)"), "great")

    fun preservesProofreadWording(prepared: String, candidate: String): Boolean =
        ProofreadPreservationPolicy.preservesWording(
            prepared,
            candidate,
            fastLayer::knowsWord,
        )

    /**
     * Lowercases a word typed in capitals when the lowercase form is an
     * ordinary dictionary word, so "THIS can be especially true" reads
     * normally. An acronym whose lowercase form is not a word (USA, NASA,
     * DLAT) is left exactly as the writer typed it.
     */
    private fun shoutedWordsCalmed(text: String): String =
        SHOUTED_WORD.replace(text) { match ->
            val word = match.value
            val lower = word.lowercase(Locale.ROOT)
            // Two-letter words are below the dictionary's minimum length, so
            // "IN PUEBLA" kept shouting; the closed class of short function
            // words is safe to calm.
            if (fastLayer.knowsWord(lower) || lower in SHORT_FUNCTION_WORDS) {
                lower
            } else {
                word
            }
        }

    fun releaseResources() {
        classifier.releaseResources()
    }

    fun acceptsGeneratedGrammar(text: String): Boolean {
        if (!passesGeneratedGrammarChecks(text)) return false
        val probability = classifier.acceptableProbability(text)
        return probability == null || probability >= GENERATED_TEXT_THRESHOLD
    }

    fun passesGeneratedGrammarChecks(text: String): Boolean {
        if (GrammarQualityGate.errorScore(text) != 0) return false
        if (GrammarSafetyNet.repair(text) != text) return false
        return true
    }

    private fun detectStructuralIssues(text: String): Set<CorrectionIssue> {
        val issues = linkedSetOf<CorrectionIssue>()
        val sentences = Regex("(?<=[.!?])\\s+|\\n+").split(text)
        if (sentences.any { sentence -> Regex("\\b[\\p{L}\\p{N}']+\\b").findAll(sentence).count() > 45 }) {
            issues += CorrectionIssue.STRUCTURE
        }
        if (
            Regex("(?m)^\\s*[-*+]\\S").containsMatchIn(text) ||
            Regex("(?m)^\\s*\\d+[.)]\\S").containsMatchIn(text)
        ) {
            issues += CorrectionIssue.FORMATTING
        }
        return issues
    }

    private fun editRatio(source: String, output: String): Double {
        if (source == output) return 0.0
        val maximum = maxOf(source.length, output.length).coerceAtLeast(1)
        val sharedPrefix = source.commonPrefixWith(output).length
        val sharedSuffix = source.substring(sharedPrefix)
            .commonSuffixWith(output.substring(sharedPrefix))
            .length
        val changed = maximum - sharedPrefix - sharedSuffix
        return changed.coerceAtLeast(1).toDouble() / maximum
    }

    companion object {
        private val SHOUTED_WORD = Regex("\\b[A-Z]{2,}\\b")
        private val SHORT_FUNCTION_WORDS = setOf(
            "in", "on", "at", "to", "of", "is", "it", "as", "an", "or", "if",
            "we", "he", "be", "by", "do", "no", "so", "up", "us", "my", "me",
            "and", "the", "for", "but", "not", "you", "she", "her", "his",
            "its", "our", "are", "was", "has", "had", "can", "may",
        )
        private const val CLEAN_THRESHOLD = 0.86f
        private const val RULES_THRESHOLD = 0.76f
        private const val MAX_RULE_EDIT_RATIO = 0.35
        private const val GENERATED_TEXT_THRESHOLD = 0.66f
    }
}

class FastCorrectionLayer(context: Context) {
    private val spelling = SymSpellCorrector(context)

    fun knowsWord(lower: String): Boolean = spelling.knows(lower)

    fun correct(source: String): FastCorrectionResult {
        val immutable = DeterministicCleanup.protect(source)
        var value = immutable.text
        val issues = linkedSetOf<CorrectionIssue>()

        val spaced = normalizeSpacing(value)
        if (spaced != value) {
            value = spaced
            issues += CorrectionIssue.FORMATTING
            issues += CorrectionIssue.PUNCTUATION
        }

        val deduplicated = removeRepeatedWords(value)
        if (deduplicated != value) {
            value = deduplicated
            issues += CorrectionIssue.GRAMMAR
        }

        val spelled = spelling.correct(value)
        if (spelled != value) {
            value = spelled
            issues += CorrectionIssue.SPELLING
        }

        val proofread = GrammarSafetyNet.repair(value)
        if (proofread != value) {
            value = proofread
            issues += CorrectionIssue.GRAMMAR
        }
        val restored = DeterministicCleanup.restore(value, immutable.tokens)
        return FastCorrectionResult(
            text = restored,
            issues = if (restored == source) emptySet() else issues,
        )
    }

    private fun normalizeSpacing(source: String): String {
        var value = source
        value = Regex("[ \\t]+([,.;:!?])").replace(value, "$1")
        value = Regex("([,.;:!?])(?=[A-Za-z])").replace(value, "$1 ")
        value = Regex("([.!?]){2,}").replace(value, "$1")
        return value
    }

    private fun removeRepeatedWords(source: String): String =
        RepeatedWordRepair.apply(source)
}

/**
 * Collapses a word the writer typed twice, and only then.
 *
 * Lifted out of [FastCorrectionLayer] so it can be tested without a Context,
 * which is how the defect below went unseen: the rule ran only inside the
 * review sweep, on restored text, where no test and no name guard could reach
 * it.
 *
 * It deleted a pet's name. The writer wrote "or Kevin. Kevin is actually
 * pretty good dinosaur name", the rule saw "Kevin. Kevin" and struck the
 * second one out, and the capitalisation pass tidied up after it, so the essay
 * shipped "or Kevin. Is actually a pretty good dinosaur name". Every name
 * guard was blind: they judge the model's edits, and this is not a model edit.
 *
 * Found and fixed in the archive tree first, then ported here on 2026-08-15
 * because this tree carried the same regex character for character.
 */
object RepeatedWordRepair {
    /** "beyond the city, city, and why" - a stutter across a comma. */
    private val acrossPunctuation = Regex(
        "(?i)\\b([A-Za-z][A-Za-z'-]{2,})([,;:])[ \\t]+\\1\\b([,;:])?",
    )

    /**
     * The same across a full stop, which needs more evidence than a comma
     * does, because a word that ends one sentence and opens the next is
     * usually a name rather than a stutter.
     *
     * The case this was built for is a run-on stitched back together:
     * "renting one room. Room, and why", where the second copy is itself
     * followed by punctuation. Requiring that keeps the repair and leaves a
     * sentence that simply begins with somebody's name.
     */
    private val acrossSentence = Regex(
        "(?i)\\b([A-Za-z][A-Za-z'-]{2,})(\\.)[ \\t]+\\1\\b(?=[,;:])",
    )

    private val adjacent = Regex("(?i)\\b([A-Za-z][A-Za-z'-]*)\\b(?:[,;]?[ \\t]+\\1\\b)+")

    fun apply(source: String): String =
        // A comma between the repeats still makes it a repeat:
        // "beyond the city, city, and why" came through untouched.
        adjacent.replace(source, "$1")
            .let { acrossPunctuation.replace(it, "$1$2") }
            .let { acrossSentence.replace(it, "$1$2") }
}

object SymSpellWordPolicy {
    /**
     * True when two spellings are the same word in different Englishes
     * (color/colour, organize/organise, center/centre, traveling/
     * travelling). Manuscript writes American English, so a "correction"
     * that only crosses that line is not a correction at all.
     */
    fun isRegionalVariant(a: String, b: String): Boolean =
        americanized(a) == americanized(b)

    private fun americanized(word: String): String {
        var value = word.lowercase(Locale.ROOT)
        value = value.replace("our", "or")
        value = value.replace("ise", "ize")
        value = value.replace("isation", "ization")
        value = value.replace("yse", "yze")
        if (value.endsWith("re")) value = value.dropLast(2) + "er"
        value = value.replace("ae", "e").replace("oe", "e")
        return value
    }

    private val validRegionalWords = setOf("neighborhood", "neighborhoods", "neighbor", "neighbors")

    fun normalizeDictionaryWord(raw: String): String = raw.trimStart('\uFEFF')

    fun isKnownForm(word: String, dictionaryContains: (String) -> Boolean): Boolean {
        if (word.length <= 3) return true
        if (word == "cannot" || word in validRegionalWords || dictionaryContains(word)) return true
        val stems = buildSet {
            if (word.endsWith("s") && word.length > 3) add(word.dropLast(1))
            if (word.endsWith("es") && word.length > 4) add(word.dropLast(2))
            if (word.endsWith("ies") && word.length > 4) add("${word.dropLast(3)}y")
            if (word.endsWith("ed") && word.length > 4) {
                add(word.dropLast(2))
                add(word.dropLast(1))
            }
            if (word.endsWith("ing") && word.length > 5) {
                val stem = word.dropLast(3)
                add(stem)
                add("${stem}e")
                if (stem.length > 2 && stem.last() == stem[stem.lastIndex - 1]) {
                    add(stem.dropLast(1))
                }
            }
        }
        return stems.any(dictionaryContains)
    }
}

class SymSpellCorrector(context: Context) {
    private val appContext = context.applicationContext
    private val dictionary by lazy(LazyThreadSafetyMode.SYNCHRONIZED, ::loadDictionary)

    fun correct(source: String): String {
        val protected = DeterministicCleanup.protect(source)
        val wordPattern = Regex("[A-Za-z]{3,}")
        val correctedOnce = wordPattern.replace(protected.text) { match ->
            val value = match.value
            val lower = value.lowercase(Locale.ROOT)
            // Capitalized words are corrected too, at Curtis's direction:
            // "Petts", "Warrsaw" and "Leeipzig" survived every pass while the
            // speller skipped anything capitalized. A word the dictionary
            // already knows is still left alone, and an unusual name simply
            // has no close match, so it stays as typed. The tradeoff is
            // accepted: a surname one edit from a common word can be
            // rewritten.
            val forced = NONSTANDARD_CORRECTIONS[lower]
            if (forced != null) {
                preserveCase(value, forced)
            } else if (
                value.startsWith("ECHOLINKTOKEN") ||
                lower in APOSTROPHE_OMISSION_WORDS ||
                SymSpellWordPolicy.isKnownForm(lower, dictionary::contains)
            ) {
                value
            } else if (value.first().isUpperCase()) {
                dictionary.correct(lower)
                    ?.replaceFirstChar { it.uppercase(Locale.ROOT) }
                    ?: value
            } else {
                dictionary.correct(value) ?: value
            }
        }
        // A misspelling can first land on another nonstandard form
        // ("peopes" -> "peoples"). Apply the closed correction map once
        // more without another fuzzy lookup, so it reaches "people" and
        // cannot wander to a third unrelated word.
        val corrected = wordPattern.replace(correctedOnce) { match ->
            NONSTANDARD_CORRECTIONS[match.value.lowercase(Locale.ROOT)]
                ?.let { preserveCase(match.value, it) }
                ?: match.value
        }
        return DeterministicCleanup.restore(corrected, protected.tokens)
    }

    fun knows(lower: String): Boolean =
        runCatching { dictionary.contains(lower) }.getOrDefault(false)

    private fun loadDictionary(): SymSpellDictionary {
        val frequencies = linkedMapOf<String, Long>()
        appContext.assets.open(DICTIONARY_ASSET).bufferedReader().useLines { lines ->
            lines.take(MAX_DICTIONARY_WORDS).forEach { line ->
                val split = line.trim().split(Regex("\\s+"))
                val word = SymSpellWordPolicy.normalizeDictionaryWord(
                    split.getOrNull(0)?.lowercase(Locale.ROOT).orEmpty(),
                )
                val frequency = split.getOrNull(1)?.toLongOrNull() ?: 0L
                if (
                    word.length in MIN_WORD_LENGTH..MAX_WORD_LENGTH &&
                    word.all { it in 'a'..'z' }
                ) {
                    frequencies[word] = frequency
                }
            }
        }
        return SymSpellDictionary(frequencies)
    }

    private class SymSpellDictionary(
        private val frequencies: Map<String, Long>,
    ) {
        private val deletes: Map<String, List<String>> =
            mutableMapOf<String, MutableList<String>>().apply {
                frequencies.keys.forEach { word ->
                    deleteVariants(word).forEach { deletion ->
                        getOrPut(deletion) { mutableListOf() }.add(word)
                    }
                }
            }.mapValues { (_, words) ->
                words.toList()
            }

        fun correct(value: String): String? {
            val lower = value.lowercase(Locale.ROOT)
            if (frequencies.containsKey(lower)) return null
            val candidates = linkedSetOf<String>()
            deleteVariants(lower).forEach { deletion ->
                deletes[deletion]?.let(candidates::addAll)
                if (frequencies.containsKey(deletion)) candidates += deletion
            }
            deletes[lower]?.let(candidates::addAll)
            // Keep the opening letters, then prefer the common word.
            // Frequency alone turned "esay" into "ebay"; ranking on the
            // longest shared prefix instead turned "pett" into "petty".
            // Requiring only the first two letters to survive rules out the
            // brand name while still letting a dropped letter be restored.
            val opening = lower.take(2)
            val best = candidates
                .asSequence()
                .filter { damerauLevenshteinAtMostOne(lower, it) }
                .maxWithOrNull(
                    compareBy<String> { if (it.startsWith(opening)) 1 else 0 }
                        .thenBy { frequencies[it] ?: 0L }
                        .thenByDescending { it },
                )
                ?: return null
            if ((frequencies[best] ?: 0L) < MIN_CORRECTION_FREQUENCY) return null
            // Never swap American spelling for British. The word list leans
            // British, so once capitalized words became correctable
            // "Colorful" was rewritten to "Colourful".
            if (SymSpellWordPolicy.isRegionalVariant(lower, best)) return null
            return preserveCase(value, best)
        }

        fun contains(word: String): Boolean = frequencies.containsKey(word)

        private fun deleteVariants(word: String): Set<String> = buildSet {
            for (index in word.indices) add(word.removeRange(index, index + 1))
        }

        private fun damerauLevenshteinAtMostOne(first: String, second: String): Boolean {
            if (first == second) return true
            if (kotlin.math.abs(first.length - second.length) > 1) return false
            if (first.length == second.length) {
                val mismatches = first.indices.filter { first[it] != second[it] }
                if (mismatches.size == 1) return true
                return mismatches.size == 2 &&
                    mismatches[1] == mismatches[0] + 1 &&
                    first[mismatches[0]] == second[mismatches[1]] &&
                    first[mismatches[1]] == second[mismatches[0]]
            }
            val shorter = if (first.length < second.length) first else second
            val longer = if (first.length < second.length) second else first
            var shortIndex = 0
            var longIndex = 0
            var skipped = false
            while (shortIndex < shorter.length && longIndex < longer.length) {
                if (shorter[shortIndex] == longer[longIndex]) {
                    shortIndex += 1
                    longIndex += 1
                } else if (skipped) {
                    return false
                } else {
                    skipped = true
                    longIndex += 1
                }
            }
            return true
        }

        private fun preserveCase(source: String, replacement: String): String = when {
            source.all(Char::isUpperCase) -> replacement.uppercase(Locale.ROOT)
            source.firstOrNull()?.isUpperCase() == true ->
                replacement.replaceFirstChar { it.uppercase(Locale.ROOT) }
            else -> replacement
        }
    }

    companion object {
        private const val DICTIONARY_ASSET = "symspell/frequency_dictionary_en.txt"
        // Start of text, or just after a sentence end.
        private val SENTENCE_START = Regex("(?:^|(?<=[.!?]\\s))[A-Za-z]")
        // Keep ordinary lower-frequency words such as "nicest" and "grate"
        // in the known-word set. The old 25,000-word cut changed them into
        // unrelated, higher-frequency words such as "incest" and "grade".
        private const val MAX_DICTIONARY_WORDS = 50_000
        private const val MIN_WORD_LENGTH = 3
        private const val MAX_WORD_LENGTH = 24
        private const val MIN_CORRECTION_FREQUENCY = 10_000L
        private val APOSTROPHE_OMISSION_WORDS = setOf(
            "dont", "doesnt", "didnt", "cant", "wont", "isnt", "arent", "wasnt",
            "werent", "hasnt", "havent", "couldnt", "wouldnt", "shouldnt",
        )
        /** Common informal and phonetic forms that large web dictionaries list as words. */
        private val NONSTANDARD_CORRECTIONS = mapOf(
            "wen" to "when",
            "bout" to "about",
            "cud" to "could",
            "sorta" to "sort of",
            "becuz" to "because",
            "probly" to "probably",
            "alot" to "a lot",
            "gonna" to "going to",
            "wanna" to "want to",
            "gotta" to "have to",
            "oner" to "owner",
            "cair" to "care",
            "animul" to "animal",
            "edjucashunal" to "educational",
            "anyways" to "anyway",
            "thaey" to "they",
            "skool" to "school",
            "prie" to "pride",
            "wether" to "whether",
            "lernt" to "learned",
            "sumtimes" to "sometimes",
            "exagerating" to "exaggerating",
            "wakees" to "wakes",
            "makees" to "makes",
            "dsty" to "dusty",
            "looing" to "looking",
            "fud" to "food",
        )

        private fun preserveCase(source: String, replacement: String): String = when {
            source.all(Char::isUpperCase) -> replacement.uppercase(Locale.ROOT)
            source.firstOrNull()?.isUpperCase() == true ->
                replacement.replaceFirstChar { it.uppercase(Locale.ROOT) }
            else -> replacement
        }
    }
}

class Int8GrammarClassifier(context: Context) {
    private val appContext = context.applicationContext
    @Volatile private var activeRuntime: ClassifierRuntime? = null

    fun acceptableProbability(text: String): Float? {
        if (text.isBlank()) return 1f
        return runCatching {
            synchronized(this) {
                runtime().classify(textBlocks(text))
            }
        }.onFailure { error ->
            Log.w(LOG_TAG, "Local grammar classifier unavailable: ${error.javaClass.simpleName}")
        }.getOrNull()
    }

    fun releaseResources() {
        synchronized(this) {
            activeRuntime?.session?.close()
            activeRuntime = null
        }
    }

    private fun runtime(): ClassifierRuntime =
        activeRuntime ?: synchronized(this) {
            activeRuntime ?: createRuntime().also { activeRuntime = it }
        }

    private fun textBlocks(text: String): List<String> {
        val sentences = Regex("(?<=[.!?])\\s+|\\n+")
            .split(text)
            .map(String::trim)
            .filter(String::isNotEmpty)
        val blocks = if (sentences.isEmpty()) {
            listOf(text.take(MAX_CLASSIFIER_CHARACTERS))
        } else {
            sentences.map { it.take(MAX_CLASSIFIER_CHARACTERS) }
        }
        if (blocks.size <= MAX_CLASSIFIER_BLOCKS) return blocks
        return List(MAX_CLASSIFIER_BLOCKS) { index ->
            val sourceIndex = index * (blocks.lastIndex) / (MAX_CLASSIFIER_BLOCKS - 1)
            blocks[sourceIndex]
        }
    }

    private fun createRuntime(): ClassifierRuntime {
        val directory = File(appContext.noBackupFilesDir, MODEL_DIRECTORY)
        require(directory.exists() || directory.mkdirs()) {
            "Cannot create the classifier directory."
        }
        val model = materializeAsset(MODEL_ASSET, File(directory, MODEL_FILE))
        val vocabulary = appContext.assets.open(VOCABULARY_ASSET).bufferedReader().useLines {
            it.toList().withIndex().associate { entry -> entry.value to entry.index }
        }
        val options = OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(
                Runtime.getRuntime().availableProcessors().coerceIn(1, MAX_CLASSIFIER_THREADS),
            )
            setInterOpNumThreads(1)
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        }
        val environment = OrtEnvironment.getEnvironment("ManuscriptGrammarClassifier")
        val session = options.use {
            environment.createSession(model.absolutePath, it)
        }
        return ClassifierRuntime(
            environment,
            session,
            WordPieceTokenizer(vocabulary),
        )
    }

    private fun materializeAsset(asset: String, destination: File): File {
        if (destination.isFile && destination.length() == CLASSIFIER_MODEL_SIZE_BYTES) {
            return destination
        }
        val temporary = File(destination.parentFile, "${destination.name}.part")
        appContext.assets.open(asset).use { input ->
            FileOutputStream(temporary, false).use { output -> input.copyTo(output) }
        }
        require(temporary.renameTo(destination) || run {
            temporary.copyTo(destination, overwrite = true)
            temporary.delete()
        }) {
            "Cannot prepare the local grammar classifier."
        }
        return destination
    }

    private data class ClassifierRuntime(
        val environment: OrtEnvironment,
        val session: OrtSession,
        val tokenizer: WordPieceTokenizer,
    ) {
        fun classify(texts: List<String>): Float {
            val encoded = texts.map(tokenizer::encode)
            val width = encoded.maxOf { it.size }
            val tokens = Array(encoded.size) { row ->
                LongArray(width).also { encoded[row].copyInto(it) }
            }
            val attention = Array(encoded.size) { row ->
                LongArray(width) { column -> if (column < encoded[row].size) 1L else 0L }
            }
            val tokenTypes = Array(encoded.size) { LongArray(width) }
            OnnxTensor.createTensor(environment, tokens).use { tokenTensor ->
                OnnxTensor.createTensor(environment, attention).use { attentionTensor ->
                    OnnxTensor.createTensor(environment, tokenTypes).use { typeTensor ->
                        session.run(
                            mapOf(
                                "input_ids" to tokenTensor,
                                "attention_mask" to attentionTensor,
                                "token_type_ids" to typeTensor,
                            ),
                        ).use { output ->
                            val logits = (output.get(0) as OnnxTensor).floatBuffer
                            var minimum = 1f
                            repeat(texts.size) { row ->
                                val unacceptable = logits.get(row * 2)
                                val acceptable = logits.get(row * 2 + 1)
                                val maximum = maxOf(unacceptable, acceptable)
                                val first = exp((unacceptable - maximum).toDouble())
                                val second = exp((acceptable - maximum).toDouble())
                                minimum = minOf(minimum, (second / (first + second)).toFloat())
                            }
                            return minimum
                        }
                    }
                }
            }
        }
    }

    private class WordPieceTokenizer(
        private val vocabulary: Map<String, Int>,
    ) {
        fun encode(text: String): LongArray {
            val output = mutableListOf(CLS_TOKEN_ID)
            basicTokens(text).forEach { token ->
                if (output.size >= MAX_TOKENS - 1) return@forEach
                wordPieces(token).forEach { piece ->
                    if (output.size < MAX_TOKENS - 1) {
                        output += vocabulary[piece] ?: UNK_TOKEN_ID
                    }
                }
            }
            output += SEP_TOKEN_ID
            return output.map(Int::toLong).toLongArray()
        }

        private fun basicTokens(text: String): List<String> {
            val normalized = Normalizer.normalize(text.lowercase(Locale.ROOT), Normalizer.Form.NFD)
                .filter { Character.getType(it) != Character.NON_SPACING_MARK.toInt() }
            return Regex("[a-z0-9]+|[^\\sA-Za-z0-9]")
                .findAll(normalized)
                .map { it.value }
                .toList()
        }

        private fun wordPieces(token: String): List<String> {
            if (token.length > MAX_WORDPIECE_CHARACTERS) return listOf("[UNK]")
            if (vocabulary.containsKey(token)) return listOf(token)
            val output = mutableListOf<String>()
            var start = 0
            while (start < token.length) {
                var end = token.length
                var found: String? = null
                while (start < end) {
                    val piece = (if (start == 0) "" else "##") + token.substring(start, end)
                    if (vocabulary.containsKey(piece)) {
                        found = piece
                        break
                    }
                    end -= 1
                }
                if (found == null) return listOf("[UNK]")
                output += found
                start = end
            }
            return output
        }

        companion object {
            private const val CLS_TOKEN_ID = 101
            private const val SEP_TOKEN_ID = 102
            private const val UNK_TOKEN_ID = 100
            private const val MAX_TOKENS = 128
            private const val MAX_WORDPIECE_CHARACTERS = 100
        }
    }

    companion object {
        private const val MODEL_DIRECTORY = "local_ai/grammar-classifier-int8-v1"
        private const val MODEL_ASSET =
            "grammar_classifier/grammar_classifier_int8.onnx"
        private const val VOCABULARY_ASSET = "grammar_classifier/vocab.txt"
        private const val MODEL_FILE = "grammar_classifier_int8.onnx"
        private const val CLASSIFIER_MODEL_SIZE_BYTES = 32_766_402L
        private const val MAX_CLASSIFIER_THREADS = 2
        private const val MAX_CLASSIFIER_CHARACTERS = 600
        private const val MAX_CLASSIFIER_BLOCKS = 16
        private const val LOG_TAG = "ManuscriptClassifier"
    }
}

/**
 * Subject-verb agreement after a singular article, and misspellings inside
 * Title Case words. Both are things the small local model refuses to touch in
 * a heading: it answered "A Parrot Make a Colorful Pett in Cairns" unchanged
 * on every attempt, including the strict retry. "A <noun> make" is wrong in
 * any context, and a capitalized word is only skipped by the speller to
 * protect real names, so the repair is limited to words the dictionary knows
 * are wrong and can correct.
 */
object HeadingAgreementRepair {
    private val singularSubjectVerb = Regex(
        "(?i)\\b(a|an)\\s+([\\p{L}]+)\\s+(make|have|do|go|say|give|need|seem|look)\\b",
    )

    // Words that end in "s" but are singular, plus the -is/-us/-ss endings
    // handled by the pattern itself. Without these, "analysis is" and
    // "the news is" would be "corrected" into nonsense.
    private val singularLookingPlurals = setOf(
        "news", "physics", "mathematics", "economics", "politics", "ethics",
        "statistics", "athletics", "gymnastics", "measles", "series",
        "species", "means", "headquarters", "works", "aerobics", "linguistics",
    )

    private val pluralSubjectVerb = Regex(
        "(?i)\\b([\\p{L}]+s)\\s+(is|was)\\b",
    )

    private fun repairPluralAgreement(value: String): String =
        pluralSubjectVerb.replace(value) { match ->
            val noun = match.groupValues[1]
            val verb = match.groupValues[2]
            val lower = noun.lowercase(Locale.ROOT)
            val singularLooking = lower in singularLookingPlurals ||
                lower.endsWith("ss") ||
                lower.endsWith("is") ||
                lower.endsWith("us")
            if (singularLooking) {
                match.value
            } else {
                val replacement = if (verb.equals("is", ignoreCase = true)) {
                    "are"
                } else {
                    "were"
                }
                val cased = if (verb.first().isUpperCase()) {
                    replacement.replaceFirstChar { it.uppercase(Locale.ROOT) }
                } else {
                    replacement
                }
                "$noun $cased"
            }
        }

    // A pronoun subject with an adverb in the way: "they usually is
    // speaking". The quality gate only ever matched "they is" with nothing
    // between, so this survived every pass.
    private val pronounSubjectVerb = Regex(
        "(?i)\\b(i|you|we|they)\\s+((?:\\w+ly\\s+)?)(is|was)\\b",
    )

    private fun repairPronounAgreement(value: String): String =
        pronounSubjectVerb.replace(value) { match ->
            val subject = match.groupValues[1]
            val adverb = match.groupValues[2]
            val verb = match.groupValues[3]
            val singularFirstPerson = subject.equals("i", ignoreCase = true)
            val replacement = when {
                singularFirstPerson && verb.equals("is", ignoreCase = true) -> "am"
                singularFirstPerson -> verb
                verb.equals("is", ignoreCase = true) -> "are"
                else -> "were"
            }
            val cased = if (verb.first().isUpperCase()) {
                replacement.replaceFirstChar { it.uppercase(Locale.ROOT) }
            } else {
                replacement
            }
            "$subject $adverb$cased"
        }

    fun apply(value: String): String =
        repairPronounAgreement(repairPluralAgreement(singularSubjectVerb.replace(value) { match ->
            val article = match.groupValues[1]
            val noun = match.groupValues[2]
            val verb = match.groupValues[3]
            val repaired = when (verb.lowercase(Locale.ROOT)) {
                "have" -> "has"
                "do" -> "does"
                "go" -> "goes"
                "say" -> "says"
                "give" -> "gives"
                else -> verb + "s"
            }
            val cased = if (verb.first().isUpperCase()) {
                repaired.replaceFirstChar { it.uppercase(Locale.ROOT) }
            } else {
                repaired
            }
            "$article $noun $cased"
        }))
}


/**
 * Rewrites apostrophe-less contractions in the text itself. The validator
 * already normalised these for meaning comparison, but nothing repaired them
 * on screen, so "a certain animal cant be a pet" reached the writer unchanged
 * whenever the model refused the sentence.
 */
object ContractionRepair {
    private val replacements: List<Pair<Regex, String>> = listOf(
        "cant" to "can't", "dont" to "don't", "doesnt" to "doesn't",
        "didnt" to "didn't", "isnt" to "isn't", "arent" to "aren't",
        "wasnt" to "wasn't", "werent" to "weren't", "hasnt" to "hasn't",
        "havent" to "haven't", "hadnt" to "hadn't", "couldnt" to "couldn't",
        "wouldnt" to "wouldn't", "shouldnt" to "shouldn't",
        "mustnt" to "mustn't", "youre" to "you're", "theyre" to "they're",
        "thats" to "that's", "whats" to "what's",
    ).map { (typo, fixed) ->
        Regex("(?i)\\b" + typo + "\\b") to fixed
    }

    fun apply(value: String): String {
        var result = value
        replacements.forEach { (pattern, fixed) ->
            result = pattern.replace(result) { match ->
                if (match.value.first().isUpperCase()) {
                    fixed.replaceFirstChar { it.uppercase(Locale.ROOT) }
                } else {
                    fixed
                }
            }
        }
        return result
    }
}
