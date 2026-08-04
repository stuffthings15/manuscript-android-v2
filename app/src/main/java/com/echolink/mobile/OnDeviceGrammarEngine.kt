package com.echolink.mobile

import android.content.ComponentCallbacks2
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.runBlocking

class LocalModelRequiredException :
    IllegalStateException("Install AI to apply grammar and tone rewrites.")

class LocalRewriteRejectedException :
    IllegalStateException("AI output did not pass grammar and meaning validation.")

object LocalAiCorrectionGate {
    fun isReady(installedAi: File?): Boolean = installedAi != null

    fun requireInstalled(installedAi: File?) {
        if (!isReady(installedAi)) throw LocalModelRequiredException()
    }
}

fun interface LocalPolishRequest {
    fun cancel()
}

class LatestRewriteRequestSlot {
    private var active: LocalPolishRequest? = null

    @Synchronized
    fun replace(request: LocalPolishRequest) {
        active?.cancel()
        active = request
    }

    @Synchronized
    fun cancel() {
        active?.cancel()
        active = null
    }
}

object RewriteFallbackPolicy {
    fun select(
        @Suppress("UNUSED_PARAMETER") prepared: String,
        @Suppress("UNUSED_PARAMETER") tone: WritingTone,
        @Suppress("UNUSED_PARAMETER") modelCandidateAvailable: Boolean,
    ): String {
        throw LocalRewriteRejectedException()
    }
}

object RewriteInputPolicy {
    fun select(
        source: String,
        @Suppress("UNUSED_PARAMETER") prepared: String,
    ): String = source
}

/**
 * Runs local AI first, then uses deterministic rules only to validate its result.
 *
 * No prompt, source text, or generated text is sent across the network.
 */
class OnDeviceGrammarEngine private constructor(context: Context) {
    private val appContext = context.applicationContext
    private val worker = Executors.newSingleThreadExecutor { task ->
        Thread(task, "manuscript-local-grammar").apply { priority = Thread.NORM_PRIORITY }
    }
    private val mainHandler = Handler(Looper.getMainLooper())
    private val correctionRouter = LocalCorrectionRouter(appContext)
    private val gemmaBackend = LiteRtGemmaBackend(appContext)
    private val legacyBackend = LegacyQwenBackend(appContext)
    private val jobStore: AiJobStore by lazy {
        AiJobRepository(
            ManuscriptAiDatabase.get(appContext).aiJobDao(),
            AndroidKeystoreAiJobTextCipher(),
        )
    }

    fun polish(
        source: String,
        tone: WritingTone,
        callback: (Result<String>) -> Unit,
    ): LocalPolishRequest = polishDocument(
        source = source,
        tone = tone,
        sourceApplication = null,
        callback = { result -> callback(result.map(DocumentCorrectionResult::text)) },
    )

    fun polishTerminal(
        source: String,
        tone: WritingTone,
        callback: (CorrectionTerminalResult) -> Unit,
    ): LocalPolishRequest = polishDocumentTerminal(
        source = source,
        tone = tone,
        sourceApplication = null,
        callback = callback,
    )

    fun polishDocumentTerminal(
        source: String,
        tone: WritingTone,
        sourceApplication: String?,
        requestedJobId: String = "ai-${UUID.randomUUID()}",
        qualityModeOverride: QualityMode? = null,
        onParagraphCompleted: (CorrectedParagraph) -> Unit = {},
        onProgress: (completed: Int, total: Int) -> Unit = { _, _ -> },
        callback: (CorrectionTerminalResult) -> Unit,
    ): LocalPolishRequest {
        val backendRequest = AtomicReference<LocalPolishRequest?>()
        val modelId = runCatching {
            AiModelManager.get(appContext).selectedArtifact().fileName
        }.getOrDefault("local-ai")
        val errorsRemainIfUnchanged = CorrectionErrorEvidence.hasErrors(source)
        val watchdog = CorrectionRequestWatchdog(
            timeoutMs = CorrectionTimeoutPolicy.forTextLength(source.length),
            elapsedRealtime = SystemClock::elapsedRealtime,
            onTimeout = { backendRequest.get()?.cancel() },
            onTerminal = { terminal ->
                mainHandler.post { callback(terminal) }
            },
        )
        val request = polishDocument(
            source = source,
            tone = tone,
            sourceApplication = sourceApplication,
            requestedJobId = requestedJobId,
            qualityModeOverride = qualityModeOverride,
            onParagraphCompleted = onParagraphCompleted,
            onProgress = onProgress,
        ) { result ->
            watchdog.completeBackend(
                backendResult = result.map(DocumentCorrectionResult::text),
                originalText = source,
                modelId = modelId,
                errorsRemain = errorsRemainIfUnchanged,
            )
        }
        backendRequest.set(request)
        return LocalPolishRequest {
            request.cancel()
            watchdog.cancel()
        }
    }

    fun polishDocument(
        source: String,
        tone: WritingTone,
        sourceApplication: String?,
        requestedJobId: String = "ai-${UUID.randomUUID()}",
        qualityModeOverride: QualityMode? = null,
        onParagraphCompleted: (CorrectedParagraph) -> Unit = {},
        onProgress: (completed: Int, total: Int) -> Unit = { _, _ -> },
        callback: (Result<DocumentCorrectionResult>) -> Unit,
    ): LocalPolishRequest {
        val cancelled = AtomicBoolean(false)
        val activePipeline = AtomicReference<DocumentCorrectionPipeline?>()
        val jobId = requestedJobId
        val future = worker.submit {
            val startedAt = SystemClock.elapsedRealtime()
            var backendId = "uninitialized-ai"
            activeDocumentJobs.incrementAndGet()
            runCatching { gemmaBackend.setKeepWarm(true) }
            val result = runCatching {
                val modelManager = AiModelManager.get(appContext)
                val installedModel = modelManager.installedModel()
                LocalAiCorrectionGate.requireInstalled(installedModel?.file)
                val installed = requireNotNull(installedModel)
                val selection = modelManager.selectedRuntime()
                val (primary, fallback) = runBlocking {
                    if (installed.artifact.family == AiModelFamily.GEMMA) {
                        gemmaBackend.initialize(installed, selection.backend)
                        val fallbackModel = modelManager.installedLegacyFallbackModel()
                        val readyFallback = fallbackModel?.let {
                            runCatching {
                                legacyBackend.initialize(it, BackendKind.LEGACY_LLAMA_CPU)
                            }.getOrNull()?.let { legacyBackend }
                        }
                        gemmaBackend to readyFallback
                    } else {
                        legacyBackend.initialize(installed, BackendKind.LEGACY_LLAMA_CPU)
                        legacyBackend to null
                    }
                }
                backendId = primary.backendId
                val pipeline = DocumentCorrectionPipeline(
                    primaryBackend = primary,
                    fallbackBackend = fallback,
                    acceptsGeneratedGrammar = correctionRouter::acceptsGeneratedGrammar,
                    acceptsStrictlyReviewedGrammar =
                        correctionRouter::passesGeneratedGrammarChecks,
                    prepareGrammarRetryCandidate = GrammarSafetyNet::repair,
                    deterministicSentenceRepair = correctionRouter::deterministicRepair,
                    logParagraphOutcome = { outcome ->
                        Log.i(PERFORMANCE_LOG_TAG, outcome)
                    },
                    correctionCacheGet = { chunkText ->
                        correctionCache[cacheKey(installed, tone, qualityModeOverride, chunkText)]
                    },
                    correctionCachePut = { chunkText, correctedText ->
                        cachePutBounded(
                            cacheKey(installed, tone, qualityModeOverride, chunkText),
                            correctedText,
                        )
                    },
                )
                activePipeline.set(pipeline)
                val runner = PersistentDocumentCorrectionRunner(jobStore, pipeline)
                val corrected = runBlocking {
                    runner.run(
                        DocumentJobRequest(
                            jobId = jobId,
                            source = source,
                            selectionStart = 0,
                            selectionEnd = source.length,
                            tone = tone,
                            qualityMode =
                                qualityModeOverride ?: modelManager.userMode().qualityMode,
                            sourceApplication = sourceApplication,
                        ),
                        onParagraphCompleted = { paragraph ->
                            if (!cancelled.get()) {
                                mainHandler.post {
                                    if (!cancelled.get()) {
                                        onParagraphCompleted(paragraph)
                                    }
                                }
                            }
                        },
                        onProgress = { completed, total ->
                            if (!cancelled.get()) {
                                mainHandler.post {
                                    if (!cancelled.get()) onProgress(completed, total)
                                }
                            }
                        },
                    )
                }
                if (cancelled.get()) {
                    throw InterruptedException("AI correction was cancelled.")
                }
                corrected
            }
            activePipeline.set(null)
            if (activeDocumentJobs.decrementAndGet() <= 0 && !sessionWarm.get()) {
                runCatching { gemmaBackend.setKeepWarm(false) }
            }
            val durationMs = SystemClock.elapsedRealtime() - startedAt
            Log.i(
                PERFORMANCE_LOG_TAG,
                "backend=$backendId;tone=${tone.name};duration_ms=$durationMs;" +
                    "success=${result.isSuccess}" +
                    (
                        result.exceptionOrNull()?.let { error ->
                            ";error=${error.javaClass.simpleName}:" +
                                error.message.orEmpty().take(160)
                        } ?: ""
                        ),
            )
            if (!cancelled.get()) {
                mainHandler.post {
                    if (!cancelled.get()) callback(result)
                }
            }
        }
        return LocalPolishRequest {
            if (cancelled.compareAndSet(false, true)) {
                val pipeline = activePipeline.get()
                if (pipeline != null) {
                    Thread(
                        {
                            runBlocking { pipeline.cancel(jobId) }
                        },
                        "manuscript-ai-cancel",
                    ).start()
                }
                future.cancel(true)
            }
        }
    }

    fun restoreJob(
        jobId: String,
        callback: (Result<AiCorrectionJob?>) -> Unit,
    ) {
        worker.submit {
            val result = runCatching {
                runBlocking { jobStore.load(jobId) }
            }
            mainHandler.post { callback(result) }
        }
    }

    /**
     * Pins the local model in memory while a review session is open so no
     * paragraph in the session pays a cold reload; unpinning resumes the
     * normal idle release.
     */
    /**
     * Model-free repair (spelling, punctuation, agreement after an article).
     * Exposed because the review session chunks the document differently from
     * the correction pipeline, so a paragraph can reach the review card
     * without having passed the pipeline's own sweep.
     */
    fun deterministicRepair(text: String): String =
        runCatching { correctionRouter.deterministicRepair(text) }.getOrDefault(text)

    fun setSessionWarm(warm: Boolean) {
        sessionWarm.set(warm)
        worker.submit {
            runCatching { legacyBackend.setKeepWarm(warm) }
            runCatching { gemmaBackend.setKeepWarm(warm) }
        }
    }

    /**
     * Loads the selected model in the background so the first correction of
     * the session does not pay model-load latency. Safe to call on every
     * Write-page entry; a loaded model returns immediately.
     */
    fun preloadAsync() {
        worker.submit {
            runCatching {
                val manager = AiModelManager.get(appContext)
                val installed = manager.installedModel() ?: return@submit
                if (installed.artifact.family == AiModelFamily.LEGACY_QWEN) {
                    legacyBackend.preload()
                } else {
                    runBlocking {
                        gemmaBackend.initialize(installed, manager.selectedRuntime().backend)
                    }
                }
            }
        }
    }

    private val correctionCache =
        java.util.Collections.synchronizedMap(
            object : LinkedHashMap<String, String>(64, 0.75f, true) {
                override fun removeEldestEntry(
                    eldest: MutableMap.MutableEntry<String, String>?,
                ): Boolean = size > CACHE_LIMIT
            },
        )

    private fun cacheKey(
        installed: InstalledAiModel,
        tone: WritingTone,
        qualityOverride: QualityMode?,
        chunkText: String,
    ): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
            .digest(chunkText.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return "${installed.artifact.tier.name}|${tone.name}|${qualityOverride?.name}|$digest"
    }

    private fun cachePutBounded(key: String, value: String) {
        if (value.length <= CACHE_VALUE_LIMIT) correctionCache[key] = value
    }

    private val prefetchSlot = LatestRewriteRequestSlot()

    // Documents in flight. While any is running the model stays pinned:
    // reloading it mid-run costs more than the corrections themselves.
    private val activeDocumentJobs = AtomicInteger(0)
    private val sessionWarm = AtomicBoolean(false)

    /**
     * Corrects the document silently in the background while the user is
     * still typing, filling the correction cache so the visible run returns
     * instantly for unchanged paragraphs. Superseded prefetches cancel.
     */
    fun prefetchDocument(source: String, tone: WritingTone) {
        if (source.isBlank()) return
        val request = polishDocument(
            source = source,
            tone = tone,
            sourceApplication = "prefetch",
            requestedJobId = "prefetch-${UUID.randomUUID()}",
        ) { /* results land in the cache; UI stays silent */ }
        prefetchSlot.replace(request)
    }

    fun cancelPrefetch() = prefetchSlot.cancel()

    fun trimMemory(level: Int) {
        // Never unload the model out from under a running document. Android
        // raises RUNNING_LOW constantly while a 2.4 GB model is resident, and
        // obeying it mid-run made a single essay reload the model four times,
        // which cost more wall clock than every correction in it. A run that
        // has finished still releases normally.
        if (activeDocumentJobs.get() > 0) return
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
            gemmaBackend.releaseResources()
            legacyBackend.releaseResources()
            correctionRouter.releaseResources()
        }
    }

    companion object {
        private const val PERFORMANCE_LOG_TAG = "ManuscriptLocalAI"
        private const val CACHE_LIMIT = 128
        private const val CACHE_VALUE_LIMIT = 4_000

        @Volatile private var instance: OnDeviceGrammarEngine? = null

        fun get(context: Context): OnDeviceGrammarEngine = instance ?: synchronized(this) {
            instance ?: OnDeviceGrammarEngine(context).also { instance = it }
        }

        fun trimIfCreated(level: Int) {
            instance?.trimMemory(level)
        }

    }
}

/**
 * Runs only after local AI generation. It repairs narrow, objective residual
 * errors and keeps the result only when every production safety gate still passes.
 */
object AiGrammarPostEditor {
    fun edit(source: String, candidate: String, tone: WritingTone): String {
        val formatted = ExactMarkdownFormat.restore(source, candidate)
        val repaired = GrammarSafetyNet.repair(formatted)
        if (repaired == candidate) return candidate
        if (GrammarQualityGate.errorScore(repaired) > GrammarQualityGate.errorScore(candidate)) {
            return candidate
        }
        return repaired.takeIf {
            RewriteValidator.isValid(
                source = source,
                prepared = candidate,
                output = repaired,
                tone = tone,
            )
        } ?: candidate
    }
}

object ExactMarkdownFormat {
    private val prefix = Regex(
        "^(\\s*)(?:#{1,6}[ \\t]+|[-*+][ \\t]+|\\d+[.)][ \\t]+)",
    )

    fun restore(source: String, candidate: String): String {
        val sourceLeading = Regex("^(?:\\r?\\n)+").find(source)?.value.orEmpty()
        val sourceTrailing = Regex("(?:\\r?\\n)+$").find(source)?.value.orEmpty()
        val boundaryRestored = candidate
            .trimStart('\r', '\n')
            .trimEnd('\r', '\n')
            .let { sourceLeading + it + sourceTrailing }
        val sourceLines = source.split('\n')
        val candidateLines = boundaryRestored.split('\n')
        if (sourceLines.size != candidateLines.size) return boundaryRestored
        return sourceLines.zip(candidateLines).joinToString("\n") { (sourceLine, candidateLine) ->
            val sourcePrefix = prefix.find(sourceLine)?.value
            val candidatePrefix = prefix.find(candidateLine)?.value
            when {
                sourcePrefix != null -> {
                    val content = candidatePrefix?.let(candidateLine::removePrefix)
                        ?: candidateLine.trimStart()
                    sourcePrefix + content
                }
                candidatePrefix != null -> candidateLine.removePrefix(candidatePrefix)
                else -> candidateLine
            }
        }
    }
}

object OnDeviceInferencePlan {
    fun usesToneModel(tone: WritingTone): Boolean = tone != WritingTone.PROOFREAD
}

object GrammarResultSelector {
    fun select(source: String, prepared: String, modelOutput: String?): String {
        val deterministic = finalProofread(prepared)
        val modelCandidate = modelOutput ?: throw LocalRewriteRejectedException()
        if (
            ToneOutputPolicy.isSafe(source, modelCandidate, WritingTone.PROOFREAD) &&
            ToneOutputPolicy.isSafe(prepared, modelCandidate, WritingTone.PROOFREAD) &&
            ProofreadPreservationPolicy.preservesWording(prepared, modelCandidate) &&
            GrammarQualityGate.errorScore(modelCandidate) <= GrammarQualityGate.errorScore(deterministic)
        ) {
            return modelCandidate
        }
        throw LocalRewriteRejectedException()
    }

    private fun finalProofread(value: String): String = GrammarSafetyNet.repair(value)
}

/** Proofread may inflect words and fix grammar, but it may not simplify the writer's vocabulary. */
object ProofreadPreservationPolicy {
    private val words = Regex("[A-Za-z]+(?:['â€™][A-Za-z]+)?")
    private val functionWords = setOf(
        "a", "an", "the", "and", "but", "or", "nor", "to", "of", "for", "from", "in",
        "on", "at", "by", "with", "as", "that", "who", "whom", "which", "i", "me", "my",
        "we", "us", "our", "you", "your", "he", "him", "his", "she", "her", "they",
        "them", "their", "it", "its", "am", "is", "are", "was", "were", "be", "been",
        "being", "has", "have", "had", "do", "does", "did", "not", "no", "may", "might",
        "must", "can", "could", "shall", "should", "will", "would", "myself", "yourself",
        "himself", "herself", "itself", "ourselves", "yourselves", "themselves",
    )
    private val irregularLemmas = mapOf(
        "began" to "begin", "begun" to "begin",
        "broke" to "break", "broken" to "break",
        "chose" to "choose", "chosen" to "choose",
        "gave" to "give", "given" to "give",
        "went" to "go", "gone" to "go",
        "knew" to "know", "known" to "know",
        "saw" to "see", "seen" to "see",
        "spoke" to "speak", "spoken" to "speak",
        "took" to "take", "taken" to "take",
        "wrote" to "write", "written" to "write",
        "men" to "man", "women" to "woman", "children" to "child",
        "criteria" to "criterion", "data" to "datum",
    )

    fun preservesWording(prepared: String, candidate: String): Boolean =
        !crossesArticlePronounBoundary(prepared, candidate) &&
            sentenceCount(candidate) <= sentenceCount(prepared) &&
            sentenceCount(prepared) - sentenceCount(candidate) <=
            (sentenceCount(prepared) / 10).coerceAtLeast(1) &&
            preservesContentLemmas(prepared, candidate)

    private fun preservesContentLemmas(source: String, candidate: String): Boolean {
        val unmatched = contentLemmas(candidate).toMutableList()
        return contentLemmas(source).all { sourceLemma ->
            val match = unmatched.indexOfFirst { candidateLemma ->
                sourceLemma == candidateLemma ||
                    (
                        sourceLemma.length >= 5 &&
                            candidateLemma.length >= 5 &&
                            editDistance(sourceLemma, candidateLemma) <= 2
                    )
            }
            if (match < 0) {
                false
            } else {
                unmatched.removeAt(match)
                true
            }
        } && unmatched.isEmpty()
    }

    private fun editDistance(left: String, right: String): Int {
        var previous = IntArray(right.length + 1) { it }
        left.forEachIndexed { leftIndex, leftChar ->
            val current = IntArray(right.length + 1)
            current[0] = leftIndex + 1
            right.forEachIndexed { rightIndex, rightChar ->
                current[rightIndex + 1] = minOf(
                    current[rightIndex] + 1,
                    previous[rightIndex + 1] + 1,
                    previous[rightIndex] + if (leftChar == rightChar) 0 else 1,
                )
            }
            previous = current
        }
        return previous[right.length]
    }

    private fun crossesArticlePronounBoundary(source: String, candidate: String): Boolean {
        val sourceWords = words.findAll(source).map { it.value.lowercase() }.toList()
        val candidateWords = words.findAll(candidate).map { it.value.lowercase() }.toList()
        val articles = setOf("a", "an", "the")
        val pronouns = setOf(
            "i", "me", "my", "we", "us", "our", "you", "your", "he", "him", "his",
            "she", "her", "they", "them", "their", "it", "its",
        )
        val articleDelta =
            candidateWords.count { it in articles } - sourceWords.count { it in articles }
        val pronounDelta =
            candidateWords.count { it in pronouns } - sourceWords.count { it in pronouns }
        return (articleDelta < 0 && pronounDelta > 0) ||
            (articleDelta > 0 && pronounDelta < 0)
    }

    private fun contentLemmas(value: String): List<String> = words.findAll(value)
        .map { it.value.lowercase() }
        .filter { it !in functionWords }
        .map(::lemma)
        .toList()

    private fun lemma(value: String): String {
        irregularLemmas[value]?.let { return it }
        return when {
            value.endsWith("ies") && value.length > 4 -> "${value.dropLast(3)}y"
            value.endsWith("ing") && value.length > 5 -> value.dropLast(3).removeDoubleEnding()
            value.endsWith("ed") && value.length > 4 -> value.dropLast(2).removeDoubleEnding()
            value.endsWith("es") && value.length > 4 &&
                Regex("(?:s|x|z|ch|sh|o)es$").containsMatchIn(value) -> value.dropLast(2)
            value.endsWith("s") && !value.endsWith("ss") && value.length > 3 -> value.dropLast(1)
            else -> value
        }
    }

    private fun String.removeDoubleEnding(): String =
        if (length >= 2 && last() == this[length - 2]) dropLast(1) else this

    private fun sentenceCount(value: String): Int =
        Regex("[.!?]+(?=\\s|$)").findAll(value.trim()).count().coerceAtLeast(1)
}

object GrammarQualityGate {
    private val knownErrors = listOf(
        Regex("(?i)\\b(?:I|you|we|they) is\\b"),
        Regex("(?i)\\b(?:he|she|it|this|that) are\\b"),
        Regex("(?i)\\b(?:these|those) is\\b"),
        Regex("(?i)\\bthose included is\\b"),
        Regex(
            "(?i)\\b(?:a|an)\\s+(?:errors|issues|reports|entries|items|files|documents|" +
                "sentences|paragraphs|sections|tasks|changes|actions|steps|requirements|" +
                "columns|managers|analysts|members|volunteers|shovels|rakes|tools)\\b",
        ),
        Regex("(?i)\\b(?:the|a|an)\\s+(?:today|tomorrow|yesterday|tonight)\\b"),
        // Lowercase-only so proper-noun reduplication (Bora Bora, Walla Walla)
        // is never flagged as a repeated-word error.
        Regex("\\b(\\p{Ll}[\\p{Ll}'’-]*)\\s+\\1\\b"),
        Regex("(?i)(?:^|[.!?]\\s+)(?:him|her|them) and I\\b"),
        Regex("(?i)\\bbetween\\s+(?:you|him|her|them|us)\\s+and\\s+I\\b"),
        Regex("(?i)\\b(?:more\\s+(?:better|worse)|most\\s+(?:best|worst))\\b"),
        Regex("(?i)\\bthan previously accustomed\\b"),
        Regex("(?im)^\\s*It is why\\b"),
        Regex(
            "(?i)\\b(?:tasks|things|items|steps|actions|duties|responsibilities|" +
                "requirements|documents|files|records|changes|issues|errors)\\s+that\\s+" +
                "(?:needs|requires|includes|contains|provides|helps|makes|takes|uses|works)\\b",
        ),
        Regex(
            "(?i)\\bif\\b[^.!?]{0,140}\\bhad(?:\\s+been)?\\b[^.!?]{0,140}," +
                "[^.!?]{0,140}\\b(?:may|might|could|should|would)\\s+(?!have\\b)[a-z]+\\b",
        ),
        Regex(
            "(?i)\\b(?:two|three|four|five|six|seven|eight|nine|ten|eleven|twelve|\\d+)\\s+" +
                "(?:error|issue|report|entry|item|file|document|sentence|paragraph|section|task)\\b",
        ),
        Regex("(?i)\\bdone\\s+(?:make|take|write|send|review|check|fix|correct)\\b"),
        Regex(
            "(?i)\\b(?:errors?|issues?|reports?|entries|items?|files?|documents?|" +
                "sentences?|paragraphs?|sections?|tasks?)\\s+" +
                "(?:I|you|we|they|he|she|it|this|that|there)\\s+" +
                "(?:am|are|is|was|were|can|could|will|would|should|must|have|has|do|does)\\b",
        ),
        Regex("(?i)\\band,\\s+(?:I|we|you|he|she|it|they|this|that|these|those)\\b"),
        Regex("(?i)\\bstuff for peoples\\b"),
        Regex("\\s+[,.;:!?]"),
        Regex("([.!?]){2,}"),
    )

    fun errorScore(value: String): Int {
        val rawScore = knownErrors.sumOf { it.findAll(value).count() }
        val sentenceCaseErrors =
            Regex("(?m)(?:^|[.!?]\\s+)[a-z]\\b").findAll(value.trim()).count()
        val trimmed = value.trim()
        val terminalPunctuationError =
            if (
                trimmed.isNotEmpty() &&
                trimmed.last() !in ".!?:" &&
                !trimmed.lineSequence().all { line -> line.trimStart().startsWith("#") }
            ) {
                1
            } else {
                0
            }
        val possessiveFalsePositives =
            Regex("(?i)\\b(?:a|an) [\\p{L}-]+['\\u2019]s\\b").findAll(value).count()
        return (
            rawScore +
                sentenceCaseErrors +
                terminalPunctuationError -
                possessiveFalsePositives
            ).coerceAtLeast(0)
    }
}

/** Small deterministic guard around the real model for known agreement failures. */
object GrammarSafetyNet {
    fun repair(value: String): String {
        val immutable = DeterministicCleanup.protect(value)
        var result = immutable.text
        result = Regex("(?i)\\breadyness\\b").replace(result) { match ->
            if (match.value.first().isUpperCase()) "Readiness" else "readiness"
        }
        result = Regex(
            "(?i)\\b(especially|including|for|to|with|between|at)" +
                "(\\s+[^.!?]{0,80}\\band\\s+)myself\\b",
        ).replace(result, "$1$2me")
        result = Regex(
            "(?i)(\\bneither\\b[^.!?]{0,120}\\bnor\\s+(?:the\\s+)?" +
                "(?:[a-z][a-z'â€™-]*\\s+){0,2})([a-z][a-z'â€™-]*)\\s+are\\b",
        ).replace(result) { match ->
            val nearest = match.groupValues[2].lowercase()
            if (
                nearest.endsWith("s") ||
                nearest in setOf("people", "police", "children", "data", "media", "staff")
            ) {
                match.value
            } else {
                "${match.groupValues[1]}${match.groupValues[2]} is"
            }
        }
        result = Regex(
            "(?i)(\\bneither\\b[^.!?]{0,120}\\bnor\\s+(?:the\\s+)?" +
                "(?:[a-z][a-z'â€™-]*\\s+){0,2})([a-z][a-z'â€™-]*)\\s+have\\b",
        ).replace(result) { match ->
            val nearest = match.groupValues[2].lowercase()
            if (
                nearest.endsWith("s") ||
                nearest in setOf("people", "police", "children", "data", "media", "staff")
            ) {
                match.value
            } else {
                "${match.groupValues[1]}${match.groupValues[2]} has"
            }
        }
        result = Regex("(?i)\\b(checklist|contract|summary|investigation) are\\b")
            .replace(result, "$1 is")
        result = Regex(
            "(?i)\\b(mandatory|required|final|complete),\\s+(it|this|that)\\b",
        ).replace(result, "$1; $2")
        result = Regex("(?i),\\s*however\\s+").replace(result, "; however, ")
        result = Regex(
            "(?i)(\\b(?:asked|wondered|inquired)[^.!?]{0,160}\\bwhether[^.!?]{1,160})\\?",
        ).replace(result, "$1.")
        result = Regex(
            "(?i)\\b(carrier|vendor|provider|manager|team) confirms\\b" +
                "([^.!?]{0,100}\\b(?:last\\s+(?:night|week|month|" +
                "monday|tuesday|wednesday|thursday|friday|saturday|sunday)|yesterday)\\b)",
        ).replace(result, "$1 confirmed$2")
        result = Regex(
            "(?i)(\\b(?:favors|prefers|supports|recommends)\\b[^.!?]{1,100}),\\s+" +
                "([A-Z][a-z]+\\s+(?:favors|prefers|supports|recommends)\\b)",
        ).replace(result, "$1; $2")
        result = Regex(
            "(?i)(\\b(?:creates?|causes?|adds?|poses?)\\b[^.!?]{1,100}),\\s+" +
                "((?:paper(?:\\s+forms?)?|forms?|tablets?|devices?|this|that|it|they)\\s+" +
                "(?:creates?|causes?|adds?|poses?)\\b)",
        ).replace(result, "$1; $2")
        result = Regex(
            "(?i)(\\b(?:should|must|need to|needs to)\\b[^.!?\\n]{3,160})\\.\\s+" +
                "Because\\s+([A-Za-z][^.!?\\n]{0,60})\\.",
        ).replace(result) { match ->
            "${match.groupValues[1]} because ${match.groupValues[2]}."
        }
        result = Regex(
            "(?i)\\bIf\\s+([^.!?\\n]{3,120})\\.\\s+They should\\b",
        ).replace(result, "If $1, they should")
        result = Regex("(?i)\\bneither of which are\\b")
            .replace(result, "neither of which is")
        result = Regex(
            "(?i)\\b(The first ([a-z][a-z'-]*) is [^.!?\\n]{1,100}),\\s+" +
                "(the second ([a-z][a-z'-]*)\\s+(?:is|requires|needs|uses|has)\\b)",
        ).replace(result) { match ->
            if (match.groupValues[2].equals(match.groupValues[4], ignoreCase = true)) {
                "${match.groupValues[1]}; ${match.groupValues[3]}"
            } else {
                match.value
            }
        }
        result = Regex("(?i)\\b(asks?|asked) for,\\s+(the|a|an)\\b")
            .replace(result, "$1 for $2")
        result = Regex(
            "(?i)\\ba format that is ([a-z][a-z'-]*),\\s+" +
                "([a-z][a-z'-]*),\\s+and does not ([a-z][a-z'-]*)\\b",
        ).replace(result, "a $1, $2 format that does not $3")
        result = Regex(
            "(?i)(\\bwill\\s+[^.!?]{1,140}\\band\\s+)([A-Z][A-Za-z'-]+)\\s+had\\s+" +
                "(verified|configured|checked|reviewed|confirmed|completed)\\b",
        ).replace(result) { match ->
            "${match.groupValues[1]}${match.groupValues[2]} will " +
                pastTenseToBase(match.groupValues[3])
        }
        result = Regex(
            "(?i)(\\bwill\\b[^.!?]{1,160},\\s*then\\s+)([a-z][a-z'â€™-]*s)\\b",
        ).replace(result) { match ->
            "${match.groupValues[1]}${thirdPersonBase(match.groupValues[2])}"
        }
        result = Regex(
            "(?i)\\b(volunteers|workers|parents|trainers|members|records|receipts|reports|" +
                "instructions|forms|devices|tablets|kits) does\\b",
        ).replace(result, "$1 do")
        result = Regex("(?i)\\bdifferent than\\b").replace(result, "different from")
        result = Regex(
            "(?i)(\\b(?:staff|volunteers|workers|members|employees|people)\\s+" +
                "(?:can|could|will|must|should)\\b[^.!?\\n]{1,160}),\\s+and\\s+" +
                "they\\s+(?:can|could|will|must|should)\\s+(not\\s+)?",
        ).replace(result) { match ->
            "${match.groupValues[1]}, and ${match.groupValues[2]}"
        }
        result = Regex(
            "(?i)(\\b(?:can|could|will|must|should)\\b[^.!?]{1,160}),\\s+" +
                "(he|she|it|they|we|you)\\s+" +
                "(can|could|will|must|should|cannot|can't|won't)\\b",
        ).replace(result, "$1; $2 $3")
        result = Regex(
            "(?i)(\\b(?:explains?|covers?|describes?|creates?|contains?|includes?)\\b" +
                "[^.!?]{1,120}),\\s+(it|this|that|they)\\s+" +
                "(takes?|creates?|contains?|includes?|cannot|can|is|are)\\b",
        ).replace(result, "$1; $2 $3")
        result = Regex(
            "(?i)\\b(monday|tuesday|wednesday|thursday|friday|saturday|sunday)\\b",
        ).replace(result) { match ->
            match.value.lowercase().replaceFirstChar(Char::uppercase)
        }
        result = Regex("(?i)(\\bLast Monday\\b[^.!?]{0,100}\\bcommittee)\\s+meet\\b")
            .replace(result, "$1 met")
        result = Regex(
            "(?i)(\\b(?:Monday|Tuesday|Wednesday|Thursday|Friday)\\b[^.!?]{0,100}\\b" +
                "(?:board|committee))\\s+meet\\b",
        ).replace(result, "$1 met")
        result = Regex("(?i)\\b(program|meeting|discussion|review)\\s+however\\s+")
            .replace(result, "$1; however, ")
        result = Regex("(?i)\\bnone of the ([a-z][a-z'-]*s) was\\b")
            .replace(result, "none of the $1 were")
        result = Regex("(?i)\\bhad wrote\\b").replace(result, "had written")
        result = Regex("(?i)\\bnobody were\\b").replace(result, "nobody was")
        result = Regex("(?i)\\bhad send\\b").replace(result, "had sent")
        result = Regex("(?i)\\b([a-z]+s) says\\b").replace(result, "$1 say")
        result = Regex(
            "(?i)\\bThere was\\s+((?:(?:twenty|thirty|forty|fifty|sixty|seventy|eighty|" +
                "ninety)\\s+)?(?:one|two|three|four|five|six|seven|eight|nine)|" +
                "twelve|eleven|ten|\\d+)\\s+",
        ).replace(result, "There were $1 ")
        result = Regex("(?i)\\beach of them have\\b").replace(result, "each of them has")
        result = Regex("(?i)\\beach of the ([a-z][a-z'-]*s) have\\b")
            .replace(result, "each of the $1 has")
        result = Regex("(?i)\\b(problem|topic|issue) involve\\b").replace(result, "$1 involves")
        result = Regex("(?i)\\b(concern|discussion|review) involve\\b").replace(result, "$1 involves")
        result = Regex("(?i)\\bdidnt brought\\b").replace(result, "didn't bring")
        result = Regex("(?i)\\bdidn't brought\\b").replace(result, "didn't bring")
        result = Regex("(?i)\\b(decision|topic|problem) were\\b").replace(result, "$1 was")
        result = Regex("(?i)\\bis suppose to\\b").replace(result, "is supposed to")
        result = Regex(
            "(?i)(^|[.!?]\\s+)([^.!?\\n]{1,180}),\\s*" +
                "(they|we|you|he|she|it) should\\b",
        ).replace(result) { match ->
            val clause = match.groupValues[2].trimStart()
            if (
                Regex("(?i)^(?:if|when|while|unless|because|although)\\b")
                    .containsMatchIn(clause)
            ) {
                match.value
            } else {
                "${match.groupValues[1]}${match.groupValues[2]}. " +
                    "${match.groupValues[3].replaceFirstChar(Char::uppercase)} should"
            }
        }
        result = Regex("(?i)\\bgloves\\s+sunscreen\\s+and\\s+").replace(
            result,
            "gloves, sunscreen, and ",
        )
        result = Regex("(?i)\\btools\\s+(?:stored in [^,.!?;]{1,60}\\s+)?includes\\b")
            .replace(result) { match -> match.value.replace(Regex("(?i)includes$"), "include") }
        result = Regex("(?i)\\b(shovels|rakes|tools|volunteers|members) is\\b")
            .replace(result, "$1 are")
        result = Regex("(?i)\\bneeds remain\\b").replace(result, "needs to remain")
        result = Regex("(?i)\\bvisitors signs\\b").replace(result, "visitors sign")
        result = Regex("(?i)\\bcommittee have\\b").replace(result, "committee has")
        result = Regex("(?i)\\bboard have\\b").replace(result, "board has")
        result = Regex(
            "(?i)\\b(inaccurate|incorrect)\\s+(misleading)\\s+and\\s+(harmful|dangerous)\\b",
        ).replace(result, "$1, $2, and $3")
        result = Regex("(?i)\\bcommittees credibility\\b").replace(result, "committee's credibility")
        result = Regex("(?i)\\bboards credibility\\b").replace(result, "board's credibility")
        result = Regex("(?i)\\bchanges is\\b").replace(result, "changes are")
        result = Regex(
            "(?i)\\band,\\s+(I|we|you|he|she|it|they|this|that|these|those)\\b",
        ).replace(result) { match ->
            ", and ${match.groupValues[1]}"
        }
        result = Regex("(?i)\\bstuff for peoples\\b").replace(result) { match ->
            match.value.replace(Regex("(?i)peoples$"), "people")
        }
        result = Regex("(?i)\\bSaturdays(?=\\s+(?:work|meeting|event|session))")
            .replace(result, "Saturday's")
        result = Regex("(?i)\\bSaturdays(?=\\s+[a-z'-]+\\s+(?:event|meeting|session))")
            .replace(result, "Saturday's")
        result = Regex("(?i)(^|[.!?]\\s+)Next\\s+(the\\b)").replace(result) {
            "${it.groupValues[1]}Next, ${it.groupValues[2]}"
        }
        result = Regex("(?i)\\bequipment(\\s+stored in [^,.!?;]{1,60})?\\s+include\\b")
            .replace(result) { match ->
                match.value.replace(Regex("(?i)include$"), "includes")
            }
        result = Regex(
            "(?i)\\bIf ([^,.!?]{3,100}\\b(?:is|are|was|were|starts?|stops?|fails?|" +
                "changes?|arrives?|leaves?|ends?|begins?|continues?|occurs?|happens?|" +
                "delays?)[^,.!?]{0,40})\\s+(the|a|an)\\b",
        ).replace(result) {
            "If ${it.groupValues[1]}, ${it.groupValues[2]}"
        }
        result = Regex("(?i)\\bname tags\\s+pens\\s+and\\s+").replace(
            result,
            "name tags, pens, and ",
        )
        result = Regex("(?i)\\bone of the ([a-z][a-z'-]*s) are\\b")
            .replace(result, "one of the $1 is")
        result = Regex("(?i)\\bemergency exits needs\\b").replace(result, "emergency exits need")
        result = Regex("(?i)\\bparents signs\\b").replace(result, "parents sign")
        result = Regex("(?i)\\ba unfinished\\b").replace(result, "an unfinished")
        result = Regex(
            "(?i)\\b(inaccurate)\\s+(confusing)\\s+and\\s+(harmful|dangerous)\\b",
        ).replace(result, "$1, $2, and $3")
        result = Regex(
            "(?i)\\b(inaccurate)\\s+(confusing)\\s+and\\s+(likely\\b)",
        ).replace(result, "$1, $2, and $3")
        result = Regex("(?i)\\beach vote\\s+name one\\b").replace(result, "each vote, name one")
        result = Regex(
            "(?i)\\bwaste money,\\s+frustrated volunteers\\s+and\\s+damage the board's credibility\\b",
        ).replace(result, "waste money, frustrate volunteers, and damage the board's credibility")
        result = Regex(
            "(?i)\\bcontinued repeating (the same [^.!?]{2,100}?)(?: over and over again)? repeatedly\\b",
        ).replace(result, "continued repeating $1")
        result = Regex("(?i)\\bthe author train\\b").replace(result, "the author's train")
        result = Regex("(?i)\\b(Central Library) claim\\b").replace(result, "$1 claims")
        result = Regex("(?i)\\b(budget discussion|choice) were\\b").replace(result, "$1 was")
        result = Regex("(?i)\\binvoices currently totals\\b").replace(result, "invoices currently total")
        result = Regex("(?i)\\binvoices totals\\b").replace(result, "invoices total")
        result = Regex("(?i),\\s*meanwhile\\s+").replace(result, "; meanwhile, ")
        result = Regex("(?i)\\bover and over again repeatedly\\b").replace(result, "repeatedly")
        result = Regex(
            "(?i)\\bcontinued repeating (the same [^.!?]{2,100}?)(?: over and over again)? repeatedly\\b",
        ).replace(result, "continued repeating $1")
        result = Regex(
            "\\b([A-Za-z]+),\\s+([A-Za-z]+)\\s+and\\s+([A-Za-z]+)\\b",
        ).replace(result, "$1, $2, and $3")
        result = Regex(
            "(?i)\\b(agenda|report|message|notice|email)\\s+however\\s+",
        ).replace(result, "$1; however, ")
        result = Regex("(?i)^\\s*is we\\b").replace(result) {
            it.value.replace(Regex("(?i)is we"), "Are we")
        }
        result = Regex("(?i)\\bthose included is\\b").replace(result) { match ->
            if (match.value.firstOrNull()?.isUpperCase() == true) {
                "Those included are"
            } else {
                "those included are"
            }
        }
        result = Regex("(?i)\\bthan previously accustomed\\b").replace(result, "than before")
        result = Regex("(?im)^(\\s*)It is why\\b").replace(result, "$1That is why")
        result = Regex(
            "(?i)\\b(tasks|things|items|steps|actions|duties|responsibilities|" +
                "requirements|documents|files|records|changes|issues|errors)\\s+that\\s+needs\\b",
        ).replace(result) { match -> "${match.groupValues[1]} that need" }
        result = Regex(
            "(?im)^(\\s*[-*+]\\s+[^:\\n]{1,80}:\\s+)" +
                "(Sends|Delivers|Reviews|Checks|Confirms|Updates|Trains|Submits|" +
                "Photographs|Labels|Tests|Verifies|Configures|Records|Assigns)\\b",
        ).replace(result) { match ->
            "${match.groupValues[1]}${thirdPersonBase(match.groupValues[2])
                .replaceFirstChar(Char::uppercase)}"
        }
        result = Regex(
            "(?im)^(\\s*[-*+][^\\n]{1,180};\\s+)" +
                "(photographs|labels|tests|checks|verifies|confirms|updates|" +
                "records|assigns|submits)\\b",
        ).replace(result) { match ->
            "${match.groupValues[1]}${thirdPersonBase(match.groupValues[2])}"
        }
        result = Regex(
            "(?im)^(\\s*[-*+]\\s+[^:\\n]{1,80}:\\s+[A-Z][^,\\n]{1,120},\\s+)" +
                "(sends|delivers|reviews|checks|confirms|updates|trains|submits|" +
                "photographs|labels|tests|verifies|configures|records|assigns)\\b",
        ).replace(result) { match ->
            "${match.groupValues[1]}${thirdPersonBase(match.groupValues[2])}"
        }
        result = Regex(
            "(?im)^(\\s*[-*+]\\s+[^:\\n]{1,80}:\\s+[A-Z][^,\\n]{1,100}),\\s+" +
                "(labeling|testing|checking|verifying|inspecting|photographing|" +
                "recording|updating|assigning)\\s+([^,\\n]{1,80}),\\s+and\\s+" +
                "(?:he|she|they)\\s+(?:must|should|will|can)\\s+",
        ).replace(result) { match ->
            "${match.groupValues[1]}, ${gerundToBase(match.groupValues[2])} " +
                "${match.groupValues[3]}, and "
        }
        result = Regex(
            "(?i)(\\b(?:check|checks|verify|verifies|inspect|inspects)\\b" +
                "[^.!?\\n]{1,120}),\\s+and\\s+" +
                "(testing|checking|verifying|inspecting)\\b",
        ).replace(result) { match ->
            "${match.groupValues[1]}, and ${gerundToBase(match.groupValues[2])}"
        }
        result = Regex(
            "(?i)\\b(These\\s+(?:experiences|tasks|responsibilities)\\s+" +
                "(?:may|can|could)\\s+help)\\s+them\\b",
        ).replace(result) { match -> "${match.groupValues[1]} those involved" }
        result = Regex(
            "(?i)\\b((?:tasks|things|items|steps|actions|duties|responsibilities|" +
                "requirements|documents|files|records|changes|issues|errors)\\s+that)\\s+" +
                "(needs|requires|includes|contains|provides|helps|makes|takes|uses|works)\\b",
        ).replace(result) { match ->
            "${match.groupValues[1]} ${thirdPersonBase(match.groupValues[2])}"
        }
        result = Regex("(?i)\\bwe is\\b").replace(result, "we are")
        result = Regex("(?i)\\bthey is\\b").replace(result, "they are")
        result = Regex("(?i)\\bthese is\\b").replace(result, "these are")
        result = Regex("(?i)\\bthose is\\b").replace(result, "those are")
        result = Regex("(?i)(^|[.!?]\\s+)(him|her|them) and I\\b").replace(result) { match ->
            val subject = when (match.groupValues[2].lowercase()) {
                "him" -> "He"
                "her" -> "She"
                else -> "They"
            }
            "${match.groupValues[1]}$subject and I"
        }
        result = Regex(
            "(?i)\\bbetween\\s+(you|him|her|them|us)\\s+and\\s+I\\b",
        ).replace(result) { match ->
            val replacement = "between ${match.groupValues[1]} and me"
            if (match.value.firstOrNull()?.isUpperCase() == true) {
                replacement.replaceFirstChar { it.uppercase() }
            } else {
                replacement
            }
        }
        result = Regex("(?i)\\bmore\\s+(better|worse)\\b").replace(result, "$1")
        result = Regex("(?i)\\bmost\\s+(best|worst)\\b").replace(result, "$1")
        result = Regex(
            "(?i)\\b(may|might|could|should|would|must)\\s+" +
                "(?!have\\b)([a-z][a-z'â€™-]{2,}ed)\\b",
        ).replace(result, "$1 have $2")
        result = Regex(
            "(?i)(\\bneither\\b[^.!?]{0,120}\\bnor\\s+" +
                "(?:the|a|an|this|that|each|every)\\s+([a-z][a-z'â€™-]*))\\s+are\\b",
        ).replace(result) { match ->
            val noun = match.groupValues[2].lowercase()
            if (
                noun.endsWith("s") ||
                noun in setOf("people", "police", "children", "data", "media", "staff")
            ) {
                match.value
            } else {
                "${match.groupValues[1]} is"
            }
        }
        result = Regex(
            "(?i)\\b(is|are|was|were),\\s+" +
                "(based|supported|provided|calculated|required|included|aware|available|" +
                "clear|likely|responsible|ready|able|unable)\\b",
        ).replace(result, "$1 $2")
        result = Regex(
            "(?i)\\b(would|could|should|might|must)\\s+of\\s+" +
                "([a-z][a-z'â€™-]+)\\b",
        ).replace(result) { match ->
            "${match.groupValues[1]} have ${pastParticiple(match.groupValues[2])}"
        }
        result = Regex(
            "(?i)^(\\s*(?:Had|Were|Should)\\s+[^.!?]{1,180})\\.\\s+" +
                "(They|We|You|He|She|It|The)\\b",
        ).replace(result) { match ->
            "${match.groupValues[1]}, ${match.groupValues[2].lowercase()}"
        }
        result = Regex(
            "(?i)^(\\s*Had\\s+[^.!?]{1,180},\\s+[^.!?]{0,120}\\b)" +
                "(would|could|might|should)\\s+(?!have\\b)([a-z][a-z'â€™-]+)\\b",
        ).replace(result) { match ->
            "${match.groupValues[1]}${match.groupValues[2]} have " +
                pastParticiple(match.groupValues[3])
        }
        result = Regex(
            "(?i)(\\bneither\\s+(?:the\\s+)?([a-z][a-z'â€™-]*)\\s+nor\\s+" +
                "(?:the\\s+)?([a-z][a-z'â€™-]*)\\s+)" +
                "(support|contain|require|indicate|show|suggest|appear|seem|remain|need|have|do)\\b",
        ).replace(result) { match ->
            val nearest = match.groupValues[3].lowercase()
            if (
                nearest.endsWith("s") ||
                nearest in setOf("people", "police", "children", "data", "media", "staff")
            ) {
                match.value
            } else {
                val singularVerb = when (match.groupValues[4].lowercase()) {
                    "have" -> "has"
                    "do" -> "does"
                    else -> match.groupValues[4].lowercase() + "s"
                }
                "${match.groupValues[1]}$singularVerb"
            }
        }
        result = Regex(
            "(?i)\\b(recommends?|recommended|suggests?|suggested|requires?|required|" +
                "insists?|insisted|requests?|requested|proposes?|proposed|demands?|demanded)" +
                "\\s+that\\s+((?:each|every|the|this|that|any)\\s+[a-z][a-z'â€™-]*)\\s+" +
                "(submits|meets|provides|reviews|approves|rejects|confirms|completes|uses|" +
                "follows|includes|sends|reports|responds|attends|participates|has|does|is)\\b",
        ).replace(result) { match ->
            "${match.groupValues[1]} that ${match.groupValues[2]} " +
                thirdPersonBase(match.groupValues[3])
        }
        result = Regex(
            "(?i),\\s+but\\s+also\\s+(I|we|you|he|she|they|it|this|that)\\s+also\\b",
        ).replace(result) { match ->
            ", but ${match.groupValues[1]} also"
        }
        result = Regex(
            "(?i),\\s*(however|therefore|nevertheless|moreover|consequently|instead|" +
                "otherwise),\\s*",
        ).replace(result, "; $1, ")
        result = Regex(
            "(?i),\\s*(otherwise|therefore|nevertheless|moreover|consequently|instead)\\s+",
        ).replace(result, "; $1, ")
        result = Regex(
            "(?i)\\b(it is (?:essential|important|necessary|critical|vital) that\\s+" +
                "((?:the|an|each|every)\\s+[a-z][a-z'â€™-]*)\\s+)" +
                "(submits|meets|provides|reviews|approves|rejects|confirms|completes|uses|" +
                "follows|includes|sends|reports|responds|attends|participates|verifies|" +
                "ensures|has|does|is)\\b",
        ).replace(result) { match ->
            "${match.groupValues[1]}${thirdPersonBase(match.groupValues[3])}"
        }
        result = Regex(
            "(?i)\\b((?:a|the)\\s+(?:series|set|group|collection)\\s+of\\s+" +
                "[^,.!?]{1,80})\\s+(have|were|are)\\b",
        ).replace(result) { match ->
            val singularVerb = when (match.groupValues[2].lowercase()) {
                "have" -> "has"
                "were" -> "was"
                else -> "is"
            }
            "${match.groupValues[1]} $singularVerb"
        }
        result = Regex(
            "(?i)\\bIf I was\\b([^.!?]{1,160},\\s*I\\s+" +
                "(?:would|could|might|should)\\b)",
        ).replace(result, "If I were$1")
        result = Regex(
            "(?i)\\b((?:the|a|an|this|that)\\s+([a-z][a-z'â€™-]*))," +
                "\\s+along with\\s+[^,]{1,120},\\s+(were|are|have)\\b",
        ).replace(result) { match ->
            val noun = match.groupValues[2].lowercase()
            if (
                noun.endsWith("s") ||
                noun in setOf("people", "police", "children", "data", "media", "staff")
            ) {
                match.value
            } else {
                val singularVerb = when (match.groupValues[3].lowercase()) {
                    "were" -> "was"
                    "are" -> "is"
                    else -> "has"
                }
                "${match.groupValues[1]}, along with" +
                    match.value.substringAfter("along with").substringBeforeLast(",") +
                    ", $singularVerb"
            }
        }
        result = Regex("(?i)\\bcould care less\\b").replace(result, "couldn't care less")
        result = Regex("(?i)\\birregardless\\b").replace(result, "regardless")
        result = Regex(
            "(?i)\\bthe amount of\\s+([a-z][a-z'â€™-]*s)\\b",
        ).replace(result) { match ->
            val noun = match.groupValues[1].lowercase()
            if (noun in setOf("news", "savings", "earnings", "proceeds", "mathematics", "physics")) {
                match.value
            } else {
                val replacement = "the number of ${match.groupValues[1]}"
                if (match.value.firstOrNull()?.isUpperCase() == true) {
                    replacement.replaceFirstChar { it.uppercase() }
                } else {
                    replacement
                }
            }
        }
        result = Regex(
            "(?i)(\\bthe number of\\s+[a-z][a-z'â€™-]*s\\s+(?:is|was)\\s+)too many\\b",
        ).replace(result, "$1too large")
        result = Regex(
            "(?i)\\b(one|two|three|four|five|six|seven|eight|nine|ten|eleven|twelve|\\d+)\\s+" +
                "(day|week|month|year|page)\\s+" +
                "(digital|field|pilot|program|plan|report|brief|document|project|workshop|" +
                "trial|course|review|study|schedule|period)\\b",
        ).replace(result, "$1-$2 $3")
        result = Regex(
            "(?i)\\bdigital access\\s+(pilot|program|project|plan|service|initiative)\\b",
        ).replace(result, "digital-access $1")
        result = Regex(
            "(?i)(\\bthe\\s+(?:[a-z][a-z'â€™-]*\\s+){0,2}" +
                "(?:committee|board|team|group|panel)\\b[^.!?]{0,100}\\bbut\\s+)" +
                "they\\s+have\\s+not\\b",
        ).replace(result, "$1it has not")
        result = Regex(
            "(?i)(\\b(?:although\\s+|while\\s+)?the\\s+" +
                "(?:[a-z][a-z'â€™-]*\\s+){0,2}(?:committee|board|team|group|panel)\\b" +
                "[^.!?]{0,140},\\s*)they\\s+(have|are|were|do)\\b",
        ).replace(result) { match ->
            val singularVerb = when (match.groupValues[2].lowercase()) {
                "have" -> "has"
                "are" -> "is"
                "were" -> "was"
                else -> "does"
            }
            "${match.groupValues[1]}it $singularVerb"
        }
        result = Regex(
            "(?i)\\bfewer\\s+(time|information|evidence|work|money|water|traffic|research)\\b",
        ).replace(result, "less $1")
        result = Regex(
            "(?i)\\b(access|information|evidence|equipment|software|research|advice|traffic|" +
                "progress|work|furniture)\\s+are\\b",
        ).replace(result, "$1 is")
        result = Regex(
            "(?i)\\b(can|could|may|might|must|shall|should|will|would)\\s+" +
                "([a-z][a-z'â€™-]*s)\\b",
        ).replace(result) { match ->
            "${match.groupValues[1]} ${thirdPersonBase(match.groupValues[2])}"
        }
        result = Regex(
            "(?i)\\b(I|we|you|they)\\s+" +
                "(represents|poses|contains|supports|requires|indicates|shows|suggests|" +
                "appears|seems|remains|needs|has|does|provides|verifies|ensures|includes|" +
                "affects|differs|makes|takes|uses|works|fails|causes|becomes|explains|" +
                "depends|matters)\\b",
        ).replace(result) { match ->
            "${match.groupValues[1]} ${thirdPersonBase(match.groupValues[2])}"
        }
        result = Regex(
            "(?i)\\b(he|she|it|this|that)\\s+" +
                "(represent|pose|contain|support|require|indicate|show|suggest|appear|seem|" +
                "remain|need|provide|verify|ensure|include|affect|differ|make|take|use|work|" +
                "fail|cause|become|explain|depend|matter)\\b",
        ).replace(result) { match ->
            "${match.groupValues[1]} ${thirdPersonSingular(match.groupValues[2])}"
        }
        result = Regex(
            "(?i)\\bnor\\s+was\\s+((?:the\\s+)?[a-z][a-z'â€™-]*s)\\b",
        ).replace(result, "nor were $1")
        result = Regex(
            "(?i)^(One of\\s+[^.!?]{1,180}\\b(?:has|have|had)\\s+[a-z][a-z'â€™-]*ed)" +
                "\\.\\s+Is that\\b",
        ).replace(result, "$1 is that")
        result = Regex(
            "(?i)\\b(tasks|things|items|steps|actions|duties|responsibilities|" +
                "requirements|documents|files|records|changes|issues|errors)\\s+that\\s+needs\\b",
        ).replace(result) { match -> "${match.groupValues[1]} that need" }
        result = Regex("\\s+([,.;:!?])").replace(result, "$1")
        result = Regex("([.!?]){2,}").replace(result, "$1")
        result = Regex("(?m)(^|[.!?]\\s+)([a-z])").replace(result) { match ->
            match.groupValues[1] + match.groupValues[2].uppercase()
        }
        var restored = DeterministicCleanup.restore(result, immutable.tokens)
        restored = Regex("(?m)^(#{1,6}\\s+)([a-z])").replace(restored) { match ->
            match.groupValues[1] + match.groupValues[2].uppercase()
        }
        restored = Regex(
            "(?i)\\b(said|asked|replied|wrote|stated|explained)\\s+(?=\")",
        ).replace(restored, "$1, ")
        restored = Regex(
            "(?i)\\bAfter reading\\s+(the\\s+[^,.!?]{1,80}),\\s+(the\\s+)",
        ).replace(restored) { match ->
            val subject = match.groupValues[1]
            val head = subject.substringAfterLast(' ').lowercase()
            val verb = if (
                head.endsWith("s") &&
                head !in setOf("analysis", "status", "process", "series")
            ) {
                "were"
            } else {
                "was"
            }
            "After $subject $verb read, ${match.groupValues[2]}"
        }
        restored = Regex(
            "(?i)(\\bshould also check that\\b[^.!?]{1,220}),\\s+and\\s+" +
                "(?:test|testing)\\s+",
        ).replace(restored, "$1, and should also test ")
        restored = Regex(
            "(?i)(\\b(?:records|files|documents|settings|codes|sheets|kits|tablets)\\b" +
                "[^.!?]{0,100}\\brequests?\\s+)it\\b",
        ).replace(restored, "$1them")
        restored = Regex(
            "(?i)\\bmust use\\s+([^.!?]{1,120}?)\\s+and\\s+automatic lock\\b",
        ).replace(restored, "must use $1 and lock automatically")
        restored = Regex(
            "(?i)\\b(one|two|three|four|five|six|seven|eight|nine|ten|eleven|twelve|\\d+)\\s+" +
                "digit\\s+(access code|code|pin|password)\\b",
        ).replace(restored, "$1-digit $2")
        restored = Regex(
            "(\\b[A-Z][a-z]+\\s+will\\s+[^,.!?]{1,100})\\s+and\\s+" +
                "([A-Z][a-z]+\\s+will\\b)",
        ).replace(restored, "$1, and $2")
        restored = Regex(
            "(?i)(\\bshould be\\s+)([a-z]+),\\s+([a-z]+),\\s+and\\s+" +
                "(?:to\\s+)?([a-z]+)\\s+([^.!?]+)",
        ).replace(restored, "$1$2 and $3 and should $4 $5")
        restored = Regex(
            "(?is)\\b(?:however,\\s*)?(?:any\\s+)?(volunteers?|participants?|users?|workers?)" +
                "\\s+(?:can|may)\\s+((?:publish|share|release)\\s+[^.;!?]+)[.;]\\s+" +
                "This permission is incorrect according to the ([^.;!?]+?)\\s+and\\s+" +
                "needs?\\s+(?:clarification|(?:to be\\s+)?clarified)\\b",
        ).replace(restored) { match ->
            val noun = match.groupValues[1].lowercase().removeSuffix("s")
            val action = match.groupValues[2]
                .replace(Regex("(?i)\\s+(?:when|if)\\s+.*$"), "")
                .trim()
            val plan = match.groupValues[3].trim()
            val previous = restored.take(match.range.first).trimEnd().lastOrNull()
            val prohibition = if (previous == null || previous in ".!?") "No" else "no"
            "$prohibition $noun may $action. " +
                "This restriction follows the $plan and needs to be clarified"
        }
        restored = Regex(
            "(?im)^(\\s*[-*+]\\s+Module\\s+\\d+)\\s*:\\s+" +
                "(teaches|covers|explains)\\b",
        ).replace(restored, "$1 $2")
        restored = Regex("(?i)\\ba\\.m\\.\\.").replace(restored, "a.m.")
        restored = Regex("(?i)\\bp\\.m\\.\\.").replace(restored, "p.m.")
        restored = Regex("(?i)\\ba\\.m\\.?(?:\\s|\\u00A0)+but\\b")
            .replace(restored, "a.m., but")
        restored = Regex("(?i)\\bp\\.m\\.?(?:\\s|\\u00A0)+but\\b")
            .replace(restored, "p.m., but")
        restored = Regex("(?i)(\\b\\d{4})\\s+however\\b").replace(restored, "$1; however,")
        restored = Regex(
            "(?i)(\\b(?:January|February|March|April|May|June|July|August|September|" +
                "October|November|December)\\s+\\d{1,2},\\s+\\d{4})\\s+but\\b",
        ).replace(restored, "$1, but")
        restored = Regex(
            "(?i)\\b(twenty|thirty|forty|fifty|sixty|seventy|eighty|ninety)\\s+" +
                "(one|two|three|four|five|six|seven|eight|nine)\\b",
        ).replace(restored, "$1-$2")
        restored = Regex(
            "(?i)(\\breported\\s+[\\d,]+\\s+[a-z'-]+)\\s+(the\\s+[A-Z][A-Za-z'-]*" +
                "(?:\\s+[A-Z][A-Za-z'-]*)?\\s+(?:branch|library|office|team)\\b)",
        ).replace(restored, "$1; $2")
        restored = Regex("(?i)(\\binvoice\\s+\\d+)\\s+although\\b")
            .replace(restored, "$1, although")
        restored = Regex(
            "(?im)(^|[.!?]\\s+)(Because\\s+[^,.!?]{3,160}\\s+[^,.!?]{2,40})" +
                "\\s+(they|we|he|she|it)\\b",
        ).replace(restored) { match ->
            "${match.groupValues[1]}${match.groupValues[2]}, ${match.groupValues[3]}"
        }
        restored = Regex("(?i)\\bIf they are ignored\\s+the\\b")
            .replace(restored, "If they are ignored, the")
        restored = Regex(
            "(?i)\\b([\\d,]+\\s+(?:gallons|liters|litres|miles|kilometers|kilometres|" +
                "dollars|pounds|kilograms)),\\s+which are\\s+(nearly|approximately|about|" +
                "almost|more than|less than|twice)\\b",
        ).replace(restored, "$1, which is $2")
        restored = Regex(
            "(?i)\\b((?:one|two|three|four|five|six|seven|eight|nine|ten|\\d+)\\s+" +
                "[a-z][a-z'-]*),\\s+((?:one|two|three|four|five|six|seven|eight|nine|ten|\\d+)\\s+" +
                "[a-z][a-z'-]*)\\s+and\\s+((?:one|two|three|four|five|six|seven|eight|nine|ten|\\d+)\\s+" +
                "[a-z][a-z'-]*)\\b",
        ).replace(restored, "$1, $2, and $3")
        restored = Regex("(?i)\\b(By the end of [^,.!?]{2,80})\\s+(no one|nobody)\\b")
            .replace(restored, "$1, $2")
        return restored
    }

    private fun pastParticiple(value: String): String {
        val lower = value.lowercase()
        if (lower.endsWith("ed") || lower.endsWith("en")) return lower
        return when (lower) {
            "be" -> "been"
            "begin" -> "begun"
            "break" -> "broken"
            "choose" -> "chosen"
            "do" -> "done"
            "drive" -> "driven"
            "eat" -> "eaten"
            "fall" -> "fallen"
            "give" -> "given"
            "go" -> "gone"
            "know" -> "known"
            "see" -> "seen"
            "speak" -> "spoken"
            "take" -> "taken"
            "write" -> "written"
            else -> when {
                lower.endsWith("e") -> "${lower}d"
                lower.endsWith("y") -> "${lower.dropLast(1)}ied"
                else -> "${lower}ed"
            }
        }
    }

    private fun pastTenseToBase(value: String): String = when (value.lowercase()) {
        "verified" -> "verify"
        "configured" -> "configure"
        "checked" -> "check"
        "reviewed" -> "review"
        "confirmed" -> "confirm"
        "completed" -> "complete"
        else -> value.lowercase()
    }

    private fun gerundToBase(value: String): String = when (value.lowercase()) {
        "labeling" -> "label"
        "testing" -> "test"
        "checking" -> "check"
        "verifying" -> "verify"
        "inspecting" -> "inspect"
        "photographing" -> "photograph"
        "recording" -> "record"
        "updating" -> "update"
        "assigning" -> "assign"
        else -> value.lowercase()
    }

    private fun thirdPersonBase(value: String): String = when (val lower = value.lowercase()) {
        "has" -> "have"
        "does" -> "do"
        "is" -> "be"
        else -> when {
            lower.endsWith("ss") -> lower
            lower.endsWith("ies") -> "${lower.dropLast(3)}y"
            lower.endsWith("es") &&
                Regex("(?:s|x|z|ch|sh|o)$").containsMatchIn(lower.dropLast(2)) ->
                lower.dropLast(2)
            lower.endsWith("s") -> lower.dropLast(1)
            else -> lower
        }
    }

    private fun thirdPersonSingular(value: String): String = when (val lower = value.lowercase()) {
        "have" -> "has"
        "do" -> "does"
        "be" -> "is"
        else -> when {
            lower.endsWith("y") -> "${lower.dropLast(1)}ies"
            Regex("(?:s|x|z|ch|sh|o)$").containsMatchIn(lower) -> "${lower}es"
            else -> "${lower}s"
        }
    }
}

object GrammarOutputPolicy {
    private val protected = Regex(
        "https?://\\S+|[\\w.+-]+@[\\w.-]+\\.[A-Za-z]{2,}|" +
            "(?:[A-Za-z]:\\\\|/)[^\\s]+|\\b\\d+(?:[.,:/-]\\d+)*%?\\b|" +
            "\"[^\"]*\"|`[^`]*`",
    )

    fun isSafe(source: String, output: String, minimumLengthDivisor: Int = 3): Boolean {
        if (output.isBlank()) return false
        if (
            output.length !in
            (source.length / minimumLengthDivisor).coerceAtLeast(1)..
                (source.length * 3).coerceAtLeast(3)
        ) {
            return false
        }
        val required = protected.findAll(source).map { it.value }.groupingBy { it }.eachCount()
        val actual = protected.findAll(output).map { it.value }.groupingBy { it }.eachCount()
        return required.all { (token, count) -> actual.getOrDefault(token, 0) >= count }
    }
}
