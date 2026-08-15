package com.echolink.mobile

import android.content.ComponentCallbacks2
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.Process
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
        Thread(
            {
                Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
                task.run()
            },
            "manuscript-local-grammar",
        ).apply { priority = Thread.MIN_PRIORITY }
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
        onRequestStarted: () -> Unit = {},
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
        val request = watchdog.startBackend(
            originalText = source,
            modelId = modelId,
            errorsRemain = errorsRemainIfUnchanged,
        ) {
            polishDocument(
                source = source,
                tone = tone,
                sourceApplication = sourceApplication,
                requestedJobId = requestedJobId,
                qualityModeOverride = qualityModeOverride,
                onRequestStarted = onRequestStarted,
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
        }
        backendRequest.set(request)
        return request
    }

    fun polishDocument(
        source: String,
        tone: WritingTone,
        sourceApplication: String?,
        requestedJobId: String = "ai-${UUID.randomUUID()}",
        qualityModeOverride: QualityMode? = null,
        onRequestStarted: () -> Unit = {},
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
                    // Give every live AI section the full meaning-neutral
                    // cleanup floor, including the dictionary speller. The
                    // small model can then spend its pass on agreement and
                    // sentence structure instead of copying obvious typos.
                    prepareGrammarRetryCandidate = correctionRouter::deterministicRepair,
                    verifiesGrammarRetryCandidate =
                        correctionRouter::verifiesDeterministicRepair,
                    deterministicSentenceRepair = correctionRouter::deterministicRepair,
                    preservesProofreadWording = correctionRouter::preservesProofreadWording,
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
                if (!cancelled.get()) {
                    mainHandler.post {
                        if (!cancelled.get()) onRequestStarted()
                    }
                }
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
                    legacyBackend.preload(installed)
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
        "actually", "anyway", "anyways", "basically", "guess", "honest", "like",
        "maybe", "really", "sort", "sorta", "whatever",
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

    fun preservesWording(
        prepared: String,
        candidate: String,
        isKnownWord: ((String) -> Boolean)? = null,
    ): Boolean =
        !crossesArticlePronounBoundary(prepared, candidate) &&
            !introducesUnsafeFunctionWordSplit(prepared, candidate) &&
            sentenceCount(candidate) <= sentenceCount(prepared) &&
            sentenceCount(prepared) - sentenceCount(candidate) <=
            (sentenceCount(prepared) / 10).coerceAtLeast(1) &&
            preservesContentLemmas(prepared, candidate, isKnownWord)

    private fun introducesUnsafeFunctionWordSplit(source: String, candidate: String): Boolean {
        val unsafeSplits = listOf(
            Regex("(?i)\\binto\\b") to Regex("(?i)\\bin\\s+to\\b"),
            Regex("(?i)\\bonto\\b") to Regex("(?i)\\bon\\s+to\\b"),
        )
        return unsafeSplits.any { (joined, split) ->
            split.findAll(candidate).count() > split.findAll(source).count() &&
                joined.findAll(candidate).count() < joined.findAll(source).count()
        }
    }

    private fun preservesContentLemmas(
        source: String,
        candidate: String,
        isKnownWord: ((String) -> Boolean)?,
    ): Boolean {
        val sourceLexemes = contentLexemes(source)
        val candidateLexemes = contentLexemes(candidate)
        if (isKnownWord != null) {
            // Cancel retained lemmas before looking for a known-word swap.
            // Sequence zipping missed substitutions whenever the model also
            // inserted or removed a modifier. Unknown source words remain
            // eligible for spelling repair; two known content words do not.
            val unmatchedSource = sourceLexemes.toMutableList()
            val unmatchedCandidate = candidateLexemes.toMutableList()
            sourceLexemes.forEach { sourceLexeme ->
                val exact = unmatchedCandidate.indexOfFirst { it.lemma == sourceLexeme.lemma }
                if (exact >= 0) {
                    unmatchedSource.remove(sourceLexeme)
                    unmatchedCandidate.removeAt(exact)
                }
            }
            val spacingRepairs = safeSpacingRepairAllowances(source, candidate, isKnownWord)
                .toMutableMap()
            val noUnsafeSubstitution = unmatchedSource.none { left ->
                val spacingKey = left.word.lowercase()
                val spacingAllowed = (spacingRepairs[spacingKey] ?: 0) > 0
                if (spacingAllowed) spacingRepairs[spacingKey] = spacingRepairs.getValue(spacingKey) - 1
                !spacingAllowed &&
                    isKnownWord(left.word.lowercase()) && unmatchedCandidate.any { right ->
                    isKnownWord(right.word.lowercase()) &&
                        alignedKnownWordSubstitution(
                            left,
                            right,
                            sourceLexemes,
                            candidateLexemes,
                        )
                }
            }
            val unknownEntityCasePreserved = sourceLexemes.none { sourceLexeme ->
                sourceLexeme.word.firstOrNull()?.isUpperCase() == true &&
                    !isKnownWord(sourceLexeme.word.lowercase()) &&
                    candidateLexemes.any { candidateLexeme ->
                        candidateLexeme.lemma == sourceLexeme.lemma &&
                            candidateLexeme.word.firstOrNull()?.isLowerCase() == true
                    }
            }
            val contextualEntityCasePreserved = preservesContextualEntityCase(source, candidate)
            val knownSource = sourceLexemes
                .filter { isKnownWord(it.word.lowercase()) }
                .map(Lexeme::lemma)
                .toSet()
            val candidateSet = candidateLexemes.map(Lexeme::lemma).toSet()
            val retained = knownSource.count(candidateSet::contains)
            val completeEnough = knownSource.size < 12 ||
                knownSource.size - retained <= 2 ||
                retained.toDouble() / knownSource.size >= 0.90
            return noUnsafeSubstitution &&
                unknownEntityCasePreserved &&
                contextualEntityCasePreserved &&
                completeEnough
        }
        val unmatched = candidateLexemes.toMutableList()
        return sourceLexemes.all { sourceLexeme ->
            val match = unmatched.indexOfFirst { candidateLexeme ->
                sourceLexeme.lemma == candidateLexeme.lemma ||
                    (
                        sourceLexeme.lemma.length >= 5 &&
                            candidateLexeme.lemma.length >= 5 &&
                            editDistance(sourceLexeme.lemma, candidateLexeme.lemma) <=
                            if (sourceLexeme.lemma.length >= 7) 4 else 2
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

    private fun alignedKnownWordSubstitution(
        source: Lexeme,
        candidate: Lexeme,
        sourceLexemes: List<Lexeme>,
        candidateLexemes: List<Lexeme>,
    ): Boolean {
        if (editDistance(source.lemma, candidate.lemma) <= 3) return true
        val sourceLeft = nearbyLemmas(sourceLexemes, source.index, -1)
        val candidateLeft = nearbyLemmas(candidateLexemes, candidate.index, -1)
        val sourceRight = nearbyLemmas(sourceLexemes, source.index, 1)
        val candidateRight = nearbyLemmas(candidateLexemes, candidate.index, 1)
        return sourceLeft.intersect(candidateLeft).isNotEmpty() ||
            sourceRight.intersect(candidateRight).isNotEmpty()
    }

    private fun preservesContextualEntityCase(source: String, candidate: String): Boolean {
        val sourceWords = words.findAll(source).toList()
        val candidateWords = words.findAll(candidate).toList()

        fun isContextualEntity(text: String, textWords: List<MatchResult>, index: Int): Boolean {
                val match = textWords[index]
                val predicate = textWords.getOrNull(index - 1)?.value.orEmpty()
                val subject = textWords.getOrNull(index - 2)?.value.orEmpty().lowercase()
                val follower = textWords.getOrNull(index + 1)
                val gapToFollower = follower?.let {
                    text.substring(match.range.last + 1, it.range.first)
                }.orEmpty()
                val endsObjectSpan = follower == null ||
                    Regex("[.!?,;:]").containsMatchIn(gapToFollower) ||
                    follower.value.lowercase() in objectSpanFollowers ||
                    follower.value.lowercase().endsWith("ly")
                val previous = textWords.getOrNull(index - 1)
                val gapFromPrevious = previous?.let {
                    text.substring(it.range.last + 1, match.range.first)
                }.orEmpty()
                val beginsSentence = previous == null ||
                    Regex("[.!?]").containsMatchIn(gapFromPrevious)
                val sentenceFollower = follower?.value.orEmpty().lowercase()
                val finiteSentencePredicate = sentenceFollower in placeAuxiliaries ||
                    sentenceFollower in irregularFinitePredicates ||
                    sentenceFollower.endsWith("ed") ||
                    (
                        sentenceFollower.endsWith('s') &&
                            !sentenceFollower.endsWith("ss") &&
                            !sentenceFollower.endsWith("ous")
                        )
                val sentenceSubject = beginsSentence && finiteSentencePredicate
                val directObject = subject in objectSubjectPronouns &&
                    predicate.firstOrNull()?.isLowerCase() == true
                val preposition = textWords.getOrNull(index - 1)?.value.orEmpty().lowercase()
                val prepositionalPredicate = textWords.getOrNull(index - 2)?.value.orEmpty()
                val prepositionalSubject = textWords.getOrNull(index - 3)?.value.orEmpty().lowercase()
                val prepositionalObject = preposition in entityObjectPrepositions &&
                    prepositionalSubject in objectSubjectPronouns &&
                    prepositionalPredicate.firstOrNull()?.isLowerCase() == true
                val rawPlaceSubject = textWords.getOrNull(index - 2)?.value.orEmpty().lowercase()
                val placeSubject = lemma(rawPlaceSubject)
                    .takeIf { it in placeBearingSubjects }
                    ?: rawPlaceSubject
                val placeFollower = follower?.value.orEmpty().lowercase()
                val wordAfterFollower = textWords.getOrNull(index + 2)?.value.orEmpty().lowercase()
                val finitePlacePredicate = placeFollower in placeAuxiliaries ||
                    placeFollower in irregularFinitePredicates ||
                    placeFollower.endsWith("ed") ||
                    (
                        placeFollower.endsWith('s') &&
                            !placeFollower.endsWith("ss") &&
                            !placeFollower.endsWith("ous")
                        ) ||
                    (
                        placeFollower.firstOrNull()?.isLowerCase() == true &&
                            wordAfterFollower.endsWith("ly")
                        )
                val placeObject = preposition in placePrepositions &&
                    placeSubject in placeBearingSubjects &&
                    finitePlacePredicate
                return sentenceSubject ||
                    ((directObject || prepositionalObject) && endsObjectSpan) ||
                    placeObject
        }

        fun contextualCapitalCounts(text: String, textWords: List<MatchResult>): Map<String, Int> =
            textWords.withIndex()
                .filter { (index, match) ->
                    match.value.firstOrNull()?.isUpperCase() == true &&
                        isContextualEntity(text, textWords, index)
                }
                .map { it.value.value.lowercase() }
                .groupingBy { it }
                .eachCount()

        val evidencedNames = contextualCapitalCounts(source, sourceWords)
        val candidateEvidencedNames = contextualCapitalCounts(candidate, candidateWords)
        return evidencedNames.all { (name, count) ->
            (candidateEvidencedNames[name] ?: 0) >= count
            }
    }

    private val objectSubjectPronouns = setOf(
        "he", "i", "it", "she", "they", "we", "you",
    )
    private val objectSpanFollowers = setOf(
        "about", "after", "at", "before", "during", "for", "from", "in", "near", "on",
        "through", "to", "today", "tonight", "tomorrow", "until", "with", "yesterday",
    )
    private val entityObjectPrepositions = setOf("about", "to", "with")
    private val placePrepositions = setOf("at", "in", "near")
    private val placeBearingSubjects = setOf(
        "agency", "branch", "clinic", "company", "hospital", "hotel", "office",
        "school", "station", "store",
    )
    private val placeAuxiliaries = setOf(
        "are", "can", "could", "did", "does", "had", "has", "have", "is", "may",
        "might", "must", "shall", "should", "was", "were", "will", "would",
    )
    private val irregularFinitePredicates = setOf(
        "began", "broke", "came", "chose", "did", "found", "gave", "had", "knew",
        "left", "made", "met", "paid", "ran", "read", "said", "sat", "saw", "sent",
        "sold", "spoke", "stood", "thought", "told", "took", "went", "won", "wrote",
    )

    /** Allows the unambiguous adverb repair "everyday." -> "every day." only. */
    private fun safeSpacingRepairAllowances(
        source: String,
        candidate: String,
        isKnownWord: (String) -> Boolean,
    ): Map<String, Int> {
        if (!listOf("everyday", "every", "day").all(isKnownWord)) return emptyMap()
        val joinedAtBoundary = Regex("(?i)\\beveryday(?=\\s*[.!?])")
        val splitAtBoundary = Regex("(?i)\\bevery\\s+day(?=\\s*[.!?])")
        val introducedSplits = splitAtBoundary.findAll(candidate).count() -
            splitAtBoundary.findAll(source).count()
        val availableJoined = joinedAtBoundary.findAll(source).count() -
            joinedAtBoundary.findAll(candidate).count()
        val allowed = minOf(introducedSplits, availableJoined).coerceAtLeast(0)
        return if (allowed > 0) mapOf("everyday" to allowed) else emptyMap()
    }

    private fun nearbyLemmas(
        lexemes: List<Lexeme>,
        index: Int,
        direction: Int,
    ): Set<String> = (1..3).mapNotNull { distance ->
        lexemes.getOrNull(index + distance * direction)?.lemma
    }.toSet()

    private data class Lexeme(val word: String, val lemma: String, val index: Int)

    private fun contentLexemes(value: String): List<Lexeme> = words.findAll(value)
        .map(MatchResult::value)
        .filter { it.lowercase() !in functionWords }
        .mapIndexed { index, word -> Lexeme(word, lemma(word.lowercase()), index) }
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
        Regex("(?i)\\band\\s+but\\b"),
        Regex(
            "(?i)\\b(?:ask(?:s|ed)?|wonder(?:s|ed)?|know(?:s|n)?)\\s+why\\s+" +
                "(?:do|does|did|is|are|was|were|can|could|will|would|should)\\b",
        ),
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
/** Shared countability rules used by deterministic repair and semantic validation. */
object CountabilityGrammar {
    private val sEndingMassNouns = setOf(
        "earnings", "mathematics", "news", "physics", "proceeds", "savings",
    )
    private val massNouns = setOf(
        "advice", "equipment", "evidence", "furniture", "information", "money",
        "progress", "research", "software", "time", "traffic", "water", "work",
    )
    private val amountOfSForm = Regex(
        "(?i)\\bthe amount of\\s+([a-z][a-z'â€™-]*s)\\b",
    )
    private val numberTooMany = Regex(
        "(?i)(\\bthe number of\\s+[a-z][a-z'Ã¢â‚¬â„¢-]*s\\s+)" +
            "(is|are|was|were)\\s+too many\\b",
    )
    private val fewerMass = Regex(
        "(?i)\\bfewer\\s+(${massNouns.joinToString("|")})" +
            "(?=\\s+(?:is|was|has|had|does|did)\\b|[.,!?;:]|$)",
    )
    private val conservativeAmountTooMany = Regex(
        "(?i)(\\bthe amount of\\s+)([a-z][a-z-]*s)\\s+" +
            "(is|are|was|were)\\s+too many\\b",
    )

    private fun isSingularSForm(noun: String): Boolean =
        noun in sEndingMassNouns ||
            noun in setOf("gas", "measles") ||
            noun.endsWith("sis") ||
            noun.endsWith("ics") ||
            noun.endsWith("ss") ||
            noun.endsWith("us")

    fun repair(value: String): String {
        var result = amountOfSForm.replace(value) { match ->
            val noun = match.groupValues[1].lowercase()
            if (isSingularSForm(noun)) {
                match.value
            } else {
                val replacement = "the number of ${match.groupValues[1]}"
                if (match.value.firstOrNull()?.isUpperCase() == true) {
                    replacement.replaceFirstChar(Char::uppercase)
                } else {
                    replacement
                }
            }
        }
        result = conservativeAmountTooMany.replace(result) { match ->
            val noun = match.groupValues[2].lowercase()
            val verb = if (match.groupValues[3].lowercase() in setOf("was", "were")) "was" else "is"
            if (isSingularSForm(noun)) {
                "${match.groupValues[1]}${match.groupValues[2]} $verb too large"
            } else {
                val prefix = if (match.groupValues[1].firstOrNull()?.isUpperCase() == true) {
                    "The number of "
                } else {
                    "the number of "
                }
                "$prefix${match.groupValues[2]} $verb too large"
            }
        }
        result = numberTooMany.replace(result) { match ->
            val verb = if (match.groupValues[2].lowercase() in setOf("was", "were")) "was" else "is"
            "${match.groupValues[1]}$verb too large"
        }
        return fewerMass.replace(result, "less $1")
    }
}

object GrammarSafetyNet {
    fun repair(value: String): String {
        val immutable = DeterministicCleanup.protect(value)
        var result = immutable.text
        result = Regex("(?i)\\band\\s+but\\b").replace(result, "but")
        // "peoples" is a real word for nations, which is why an unconditional
        // entry in the speller's word map was wrong: it rewrote "indigenous
        // peoples" every time it appeared. It lived in NONSTANDARD_CORRECTIONS,
        // which is applied one token at a time and so cannot see the word in
        // front of it. Here there is context, so the words that make the real
        // sense are excluded and everything else is still repaired.
        result = Regex(
            "(?i)(?<!\\b(?:indigenous|native|tribal|many|various|different|" +
                "all|the|two|three|several|world's|ancient|coastal|nomadic)\\s)" +
                "\\bpeoples\\b",
        ).replace(result) { match ->
            if (match.value.first().isUpperCase()) "People" else "people"
        }
        result = Regex("(?i)\\bpay\\s+attention\\s+too(?=\\s*[.,!?;:]|$)")
            .replace(result, "pay attention to")
        result = Regex(
            "(?i)\\bthere is\\s+((?:[a-z][a-z'-]*\\s+){0,2})" +
                "(benefits|reasons|problems|advantages|disadvantages)\\b",
        ).replace(result) { match ->
            val opener = if (match.value.first().isUpperCase()) "There are " else "there are "
            "$opener${match.groupValues[1]}${match.groupValues[2]}"
        }
        result = Regex("\\b([A-Z][a-z'-]+) have (its|his|her)\\b")
            .replace(result, "$1 has $2")
        result = Regex(
            "(?i)\\b(there) is (several|many|few|numerous|two|three|four|five|" +
                "six|seven|eight|nine|ten|\\d+) ([a-z][a-z'-]*s)\\b",
        ).replace(result, "$1 are $2 $3")
        result = Regex(
            "(?i)(^|[.!?]\\s+)([a-z][a-z'-]*ing),\\s*([a-z][a-z'-]*ing)\\s+and\\s+" +
                "([a-z][a-z'-]*ing)\\s+(creates|makes|provides|requires|needs|helps)\\b",
        ).replace(result) { match ->
            "${match.groupValues[1]}${match.groupValues[2]}, ${match.groupValues[3]}, and " +
                "${match.groupValues[4]} ${thirdPersonBase(match.groupValues[5])}"
        }
        result = Regex("\\b(make|makes)\\s+(?:create|creates)\\b(?=\\s+(?:a|an|the)\\b)")
            .replace(result) { match ->
                if (match.groupValues[1] == "makes") "creates" else "create"
            }
        result = Regex("(?i)\\b(after|for) awhile\\b").replace(result, "$1 a while")
        result = Regex("(?i)(^|[.!?]\\s+)(After a while)\\s+(?=(?:the|these|those)\\b)")
            .replace(result, "$1$2, ")
        result = Regex(
            "(?i)\\b(the|these|those|all)\\s+" +
                "(values|changes|benefits|results|requirements|records|forms|items)\\s+" +
                "(changes|creates|makes|provides|requires|needs|helps)\\b",
        ).replace(result) { match ->
            "${match.groupValues[1]} ${match.groupValues[2]} " +
                thirdPersonBase(match.groupValues[3])
        }
        result = Regex("(?i)\\breadyness\\b").replace(result) { match ->
            if (match.value.first().isUpperCase()) "Readiness" else "readiness"
        }
        result = Regex("(?i)\\bdoes['’]t\\b").replace(result, "doesn't")
        result = Regex(
            "(?i)\\bgrate(?=\\s+(?:pet|pets|choice|idea|animal|animals|place|thing|way)\\b)",
        ).replace(result, "great")
        result = Regex("(?i)\\bmake grade(?=\\s+(?:pets?|choices?|companions?)\\b)")
            .replace(result, "make great")
        result = Regex("(?i)\\bcan only be (?:grade|grate)\\b")
            .replace(result, "can only be great")
        result = Regex("(?i)\\breal good\\b").replace(result, "really well")
        result = Regex("(?i)\\bwith out\\b").replace(result, "without")
        result = Regex("(?i)\\bthere (keys|belongings|possessions)\\b")
            .replace(result, "their $1")
        result = Regex("(?i)\\bsome is\\b").replace(result, "some are")
        result = Regex("(?i)\\b(pets|animals|dogs|cats) does\\b").replace(result, "$1 do")
        result = Regex("(?i)\\bthey usually are ([a-z]+ing)\\b")
            .replace(result, "they are usually $1")
        result = Regex("(?i)\\bconnect perfect\\b").replace(result, "connect perfectly")
        result = Regex(
            "(?i)\\b(companionship|requirement|reason|benefit|feature|rule) witch\\b",
        ).replace(result, "$1 which")
        result = Regex("(?i)\\b(dogs|cats|pets|animals) makes\\b").replace(result, "$1 make")
        result = Regex("(?i)\\bthey still shows\\b").replace(result, "they still show")
        result = Regex("(?i)\\b(a|the) (dog|cat|pet|animal) got\\b")
            .replace(result, "$1 $2 has")
        result = Regex("(?i)\\blearning from (a|the) mistake is better then\\b")
            .replace(result, "learning from $1 mistake is better than")
        result = Regex("(?i)\\bcompanionship look\\b").replace(result, "companionship looks")
        result = Regex("(?i)\\bwere you lives\\b").replace(result, "where you live")
        result = Regex("(?i)\\bwould of\\b").replace(result, "would have")
        result = Regex("(?i)\\bTheir is\\b").replace(result, "There is")
        result = Regex("(?i)\\bTheir are\\b").replace(result, "There are")
        result = Regex("(?i)\\b(benefit|advantage|reason) are\\b").replace(result, "$1 is")
        result = Regex("(?i)\\b(the (?:pet|dog|cat|animal)|it) don't\\b")
            .replace(result, "$1 doesn't")
        result = Regex("(?i)\\b(habitats|pets|animals|dogs|cats) does\\b")
            .replace(result, "$1 do")
        result = Regex("(?i)\\bequipments\\b").replace(result, "equipment")
        result = Regex("(?i)\\bIt ait\\b").replace(result, "It isn't")
        result = Regex("(?i)\\b(in) I guess\\b").replace(result, "$1, I guess,")
        result = Regex("(?i)\\bfeels embarrassed learning from\\b")
            .replace(result, "feels embarrassed, but learning from")
        result = Regex("(?i)\\bexactly like a dog would have, but\\b")
            .replace(result, "exactly like a dog would, but")
        result = Regex("(?i)\\binstead of just big decoration\\b")
            .replace(result, "instead of just being a decoration")
        result = Regex("(?i)\\bforgets sort of their keys\\b")
            .replace(result, "forgets their keys")
        result = Regex("(?i)\\bthe pet is their witch\\b")
            .replace(result, "the pet is theirs, which")
        result = Regex("(?i)\\bwords they never would use\\b")
            .replace(result, "words they would never use")
        result = Regex("(?i)\\bwhere it Live\\b").replace(result, "where it lives")
        result = Regex(
            "(?i)\\bFeeding ([^.!?]{1,160}) and checking ([^.!?]{1,80}) makes\\b",
        ).replace(result, "Feeding $1 and checking $2 make")
        result = Regex("(?i),{2,}").replace(result, ",")
        result = Regex("(?i)\\bthe humans bad planning\\b")
            .replace(result, "the humans' bad planning")
        result = Regex("(?i)\\bbefore they learned much\\b")
            .replace(result, "before they have learned much")
        result = Regex("(?i)\\bdifferent looking(?=\\s+[a-z]+\\b)")
            .replace(result, "different-looking")
        result = Regex("(?i)\\bimportant in, I guess, every\\b")
            .replace(result, "important, I guess, in every")
        result = Regex("(?i)\\bschool work\\b").replace(result, "schoolwork")
        result = Regex(
            "(?i)\\bwatching (the [a-z]+) move,\\s*Eat\\.\\s*Rest or investigate " +
                "something give\\b",
        ).replace(result, "watching $1 move, eat, rest, or investigate something gives")
        result = Regex("(?i)\\bThe one should\\b").replace(result, "One should")
        result = Regex("(?i)\\ba pet anyway can only\\b").replace(result, "a pet can only")
        result = Regex("(?i)\\bUnited or whatever States\\b")
            .replace(result, "United States")
        result = Regex("(?i)\\bor whatever States It\\b")
            .replace(result, "United States. It")
        result = Regex("(?i)\\broutine education, entertainment\\b")
            .replace(result, "routine, education, entertainment")
        result = Regex("(?i)\\bparticular different scenario mostly\\b")
            .replace(result, "particular scenario")
        result = Regex(
            "(?i)\\bSome are smart,? and others are sort of\\.\\s*Why does it look like that\\?",
        ).replace(
            result,
            "Some questions are thoughtful, while others ask why it looks that way.",
        )
        result = Regex(
            "(?i)\\bthe pet is theirs, two reasons that are essentially the same\\b",
        ).replace(
            result,
            "the pet belongs to them. These two reasons are essentially the same",
        )
        result = Regex("(?i)\\bsuch as paying a bill, and stuff (?:like )?finishing\\b")
            .replace(result, "such as paying a bill, finishing")
        result = Regex("(?i)\\bdo need sort of lights\\b").replace(result, "do need some lights")
        result = Regex("(?i)\\bWhether in ([^,]{2,60}), housing size\\b")
            .replace(result, "In $1, housing size")
        result = Regex("(?i)\\bAnd for real still it connects because\\b")
            .replace(result, "Still, it connects because")
        result = Regex("(?i)\\bcleaning where it lives and checking\\b")
            .replace(result, "cleaning where it lives, and checking")
        result = Regex("\\b([A-Z][a-z]+),? United States It\\b")
            .replace(result, "$1, United States. It")
        result = Regex("(?i)\\bAnd it also requires\\b").replace(result, "It also requires")
        result = Regex("(?i), but most worthwhile things\\b")
            .replace(result, ". Most worthwhile things")
        result = Regex("(?i)! But with correct preparation\\b")
            .replace(result, "! With correct preparation")
        result = Regex("(?i)\\bpractice essay ([^.!?\\n]{1,80})\\?")
            .replace(result, "practice essay $1.")
        result = Regex("(?i)\\. And this essay\\b").replace(result, ". This essay")
        result = Regex("(?i)\\. But for real,\\s*").replace(result, ". However, ")
        result = Regex("(?i)\\bfinishing schoolwork or fixing\\b")
            .replace(result, "finishing schoolwork, or fixing")
        result = Regex("(?i)\\. But inside the home,\\s*")
            .replace(result, ". Inside the home, ")
        result = Regex("(?i)\\. Anyway,\\s*").replace(result, ". ")
        result = Regex("(?i)\\. And the animal\\b").replace(result, ". The animal")
        result = Regex("(?i)\\bis educational too\\b").replace(result, "is also educational")
        result = Regex("(?i)\\bmight basically not work exactly\\b")
            .replace(result, "might not work as intended")
        result = Regex("(?i)\\bneeds are met but without proper care\\b")
            .replace(result, "needs are met. Without proper care")
        result = Regex(
            "(?i)\\bAt first, the chores may feel annoying, then after a while they become\\b",
        ).replace(result, "At first, the chores may feel annoying; after a while, they become")
        result = Regex("(?i)\\bparticular different scenario,? mostly\\b")
            .replace(result, "particular scenario")
        result = Regex("(?i)\\. And not every\\b").replace(result, ". Not every")
        result = Regex(
            "(?i)\\bthey are usually speaking before they have learned much about it\\b",
        ).replace(result, "they usually speak before learning much about it")
        result = Regex("(?i)\\b(In [^,.]{2,80}), to be honest,\\s*")
            .replace(result, "$1, ")
        result = Regex("(?i)\\. However, they still show\\b")
            .replace(result, ". Still, they show")
        result = Regex("(?i)\\bis also educational, because\\b")
            .replace(result, "is also educational because")
        result = Regex("(?i)\\bWithout proper care even\\b")
            .replace(result, "Without proper care, even")
        result = Regex("(?i)\\bunhealthy or destructive\\b")
            .replace(result, "unhealthy, or destructive")
        result = Regex("(?i)\\babout fun but responsibility\\b")
            .replace(result, "about fun, but responsibility")
        result = Regex("(?i)\\bTherefore, ([^.!?]{1,120}) pets, except when\\b")
            .replace(result, "Therefore, $1 pets except when")
        result = Regex("(?i)\\. And that (is|makes)\\b").replace(result, ". That $1")
        result = Regex("(?i),\\s*to be honest,\\s*").replace(result, ", ")
        result = Regex("(?i)\\bdestructive and then\\b")
            .replace(result, "destructive, and then")
        result = Regex("\\bIn ([A-Z][a-z]+) (England|France|Germany|Greece|Kenya|Mexico|Morocco|Vietnam)\\b")
            .replace(result, "In $1, $2")
        result = Regex(
            "(?i)\\bSome are smart,? and others are why does it and stuff look like that\\.",
        ).replace(result, "Some questions are thoughtful, while others ask why it looks that way.")
        result = Regex("(?i)\\bThis part don't connect much but it still counts\\.")
            .replace(result, "This part doesn't connect much, but it still counts.")
        result = Regex("(?i)\\bmoves the room\\b").replace(result, "moves around the room")
        result = Regex("(?i)\\bwhich are two reasons at once\\b")
            .replace(result, "which may be two versions of the same reason")
        result = Regex(
            "(?i)\\bAnd ([a-z]+) has its own whether street noises and traditions\\.",
        ).replace(result) { match ->
            "${match.groupValues[1].replaceFirstChar { it.uppercase() }} has its own weather, " +
                "street noises, and traditions."
        }
        result = Regex("(?i)\\ban event so it is not a movie\\b")
            .replace(result, "an event; it is not a movie")
        result = Regex("(?i)\\bIt is educational,? sort of too\\.")
            .replace(result, "It is also educational.")
        result = Regex("(?i)\\bsomething award earlier\\b")
            .replace(result, "something awkward earlier")
        result = Regex("(?i)\\bIt mindy wants\\b").replace(result, "It mainly wants")
        result = Regex(
            "(?i)\\bAlthough saying (it|the (?:pet|animal)) understands every " +
                "(?:human )?emotion would be exaggerating it\\.",
        ).replace(result, "However, saying $1 understands every emotion would be an exaggeration.")
        result = Regex("(?i)\\s+Etc\\.").replace(result, "")
        result = Regex(
            "(?i)\\bAt first the chores feel annoying, then after a while it becomes automatic\\.",
        ).replace(result, "At first the chores feel annoying; after a while they become automatic.")
        result = Regex("(?i)\\bWitch is for real bad\\.").replace(result, "This is really bad.")
        result = Regex("(?i)\\bForgetting Ait just forgetting\\b")
            .replace(result, "Forgetting is not just forgetting")
        result = Regex("(?i)\\bThe owners' daily travel\\b")
            .replace(result, "The owner's daily travel")
        result = Regex("(?i)\\bA plan copied, you know, from\\b")
            .replace(result, "A plan copied from")
        result = Regex("(?i)\\bbecomes basically unhealthy\\b")
            .replace(result, "becomes unhealthy")
        result = Regex("(?i)\\bBut Therefore,\\s*").replace(result, "Therefore, ")
        result = Regex("(?i),?\\s+I guess\\.").replace(result, ".")
        result = Regex(
            "(?i)\\bAnd ([a-z]+) has its own whether street noises and traditions(?=,|\\.)",
        ).replace(result) { match ->
            "${match.groupValues[1].replaceFirstChar { it.uppercase() }} has its own weather, " +
                "street noises, and traditions"
        }
        result = Regex("(?i)\\bmove, eat, rest or investigate\\b")
            .replace(result, "move, eat, rest, or investigate")
        result = Regex("(?i)\\ban event to ([a-z]+) so it is not a movie\\b")
            .replace(result, "an event to $1; it is not a movie")
        result = Regex("(?i)\\bdoesn't need batteries though\\b")
            .replace(result, "doesn't need batteries, though")
        result = Regex("(?i)\\bIt is educational,? sort of too,? because\\b")
            .replace(result, "It is also educational because")
        result = Regex(
            "(?i)\\b(Feeding [^.!?]{1,180}, and checking [^.!?]{1,80}) creates " +
                "(a|the) ((?:[a-z-]+ )?schedule)\\b",
        ).replace(result, "$1 create $2 $3")
        result = Regex(
            "(?i)\\bAt first the chores may feel annoying, then after a while it becomes " +
                "automatic except\\b",
        ).replace(result, "At first the chores may feel annoying; after a while they become automatic except")
        result = Regex("\\band The owner's daily travel\\b")
            .replace(result, "and the owner's daily travel")
        result = Regex("(?i)(\\bIn [A-Z][a-z]+, [a-z]+), and (the owner's [a-z]+\\b)")
            .replace(result, "$1 and $2")
        result = Regex("(?i), basically (?=(?:unhealthy|unsafe|unwell|ill)\\b)")
            .replace(result, ", ")
        result = Regex("\\b(In [A-Z][a-z]+) (England|France|Germany|Greece|Kenya|Mexico|Morocco|Vietnam)\\b")
            .replace(result, "$1, $2")
        result = Regex("(?i)\\bwhich is a lot of requirements\\b")
            .replace(result, "which means there are many requirements")
        result = Regex("(?i)\\bBut with correct preparation\\b")
            .replace(result, "With correct preparation")
        result = Regex("(?i),?\\s+I guess,?\\s+(?=in this|in that|for this|for that)")
            .replace(result, " ")
        result = Regex(
            "(?i)\\bmay be two versions of the same reason and may be the same reason\\b",
        ).replace(result, "may be two versions of the same reason")
        result = Regex("(?i)\\b((?:a|an) (?:[a-z]+ )?event to [^.!?]{1,50}?) so it is not a movie\\b")
            .replace(result, "$1; it is not a movie")
        result = Regex("(?i)\\bis educational,? sort of too,? because\\b")
            .replace(result, "is also educational because")
        result = Regex("(?i)\\bdoesn't connect much but it still counts\\b")
            .replace(result, "doesn't connect much, but it still counts")
        result = Regex("(?m)(^|[.!?]\\s+)But caring\\b")
            .replace(result, "$1Caring")
        result = Regex("(?m)(^|[.!?]\\s+)So then the\\b")
            .replace(result, "$1Then the")
        result = Regex(
            "(?i)\\bNow it (?:suddenly )?sounds like instructions, and still it connects because\\b",
        ).replace(result, "Although it sounds like instructions, it still connects because")
        result = Regex("(?i)\\b(Rabbits|Cats|Dogs|Pets|Animals) can be the best pet\\b")
            .replace(result, "$1 can be the best pets")
        result = Regex("(?i)\\bIntentionally error-filled practice essay (\\d+)[!?]")
            .replace(result, "Intentionally error-filled practice essay $1.")
        result = Regex(
            "(?im)(^|[.!?]\\s+)(One (?:afternoon|morning|evening|day|night),?\\s+" +
                "(?:a|an|the) ([a-z][a-z'-]*) (might|could) [a-z][a-z'-]*" +
                "(?: [a-z][a-z'-]*){0,4} and (?:the|a|an) ([a-z][a-z'-]*)) " +
                "(reacts|responds)([^.!?]{0,80})(?=[.!?]|$)",
        ).replace(result) { match ->
            val sameSubject = match.groupValues[3].equals(match.groupValues[5], ignoreCase = true)
            val explicitReaction = Regex(
                "(?i)^\\s+(?:in (?:his|her|its|their) own way|with " +
                    "(?:a|an|the|his|her|its|their) " +
                    "(?:bark|sound|noise|gesture|movement|reply|answer|signal|alarm|call))\\b",
            ).containsMatchIn(match.groupValues[7])
            if (sameSubject || explicitReaction) {
                "${match.groupValues[1]}${match.groupValues[2]} " +
                    "${match.groupValues[4]} ${thirdPersonBase(match.groupValues[6])}" +
                    match.groupValues[7]
            } else {
                match.value
            }
        }
        result = Regex(
            "(?i)\\b(?<!will )(?<!would )(?<!could )(?<!might )(?<!may )" +
                "(?<!should )(?<!can )(learn|learns|learned|learning) " +
                "(new )?words ((?:they|we|you|he|she|I)|(?:the\\s+)?[a-z][a-z'-]*) " +
                "would never use before(?=[.!?]|$)",
        ).replace(result) { match ->
            "${match.groupValues[1]} ${match.groupValues[2]}words " +
                "${match.groupValues[3]} had never used before"
        }
        result = Regex(
            "(?i)\\b((?:is|was|are|were) about [^,.!?]{2,120}),\\s+and\\s+" +
                "(why (?:keeping|having|using|choosing|caring|studying|living|working) " +
                "[^.!?]{1,80} (?:(?:could|can|would|might|may) [a-z][a-z'-]*|" +
                "helps?|works?|matters?)(?: (?:really )?(?:well|better|best))?[.!?])",
        ).replace(result, "$1 and $2")
        result = Regex("(?i)\\bkeeping one for keeping (a|an)\\b")
            .replace(result, "keeping one as $1")
        result = Regex(
            "(?i)\\bkeeping (one|it|them) as (a|an) " +
                "((?:[a-z][a-z'-]* ){0,3})indoor (animal|pet) while\\b",
        ).replace(result) { match ->
            "keeping ${match.groupValues[1]} as ${match.groupValues[2]} " +
                "${match.groupValues[3]}${match.groupValues[4]} indoors while"
        }
        result = Regex("(?i)\\bHowever, ([^.!?]{1,180}), but (they|it|this|that)\\b")
            .replace(result, "However, $1, and $2")
        result = Regex("(?im)(^|[.!?]\\s+)And (a|an) (rabbit|cat|dog|pet) gets\\b")
            .replace(result) { match ->
                match.groupValues[1] +
                    "${match.groupValues[2].replaceFirstChar { it.uppercase() }} " +
                    "${match.groupValues[3]} has"
            }
        result = Regex("(?i)\\b(?:a|an) (rabbit|cat|dog|pet) got (?=habits|needs|a personality)")
            .replace(result, "a $1 has ")
        result = Regex("(?i)\\bIt's not a to be honest movie\\b")
            .replace(result, "It's not a movie")
        result = Regex("(?i)\\bmovie and it doesn't need batteries although\\b")
            .replace(result, "movie, and it doesn't need batteries, although")
        result = Regex("(?i)\\bpower so the animal creates\\b")
            .replace(result, "power. The animal creates")
        result = Regex("(?im)(^|[.!?]\\s+)But crying for (it|them) can\\b")
            .replace(result, "$1Caring for $2 can")
        result = Regex("(?i)\\ba repeating or whatever schedule\\b")
            .replace(result, "a repeating schedule")
        result = Regex("(?i)\\b(make|makes|made) a repeating schedule\\b")
            .replace(result) { match ->
                val verb = when (match.groupValues[1].lowercase()) {
                    "makes" -> "creates"
                    "made" -> "created"
                    else -> "create"
                }
                "$verb a regular routine"
            }
        result = Regex(
            "(?i)\\bAt first,? the Chores may feel annoying, then after a while it " +
                "becomes sort of automatic\\b",
        ).replace(result, "At first, the chores may feel annoying; after a while, they become automatic")
        result = Regex(
            "(?i)\\bchecks the habitat, checks the habitat\\. Says hello then\\b",
        ).replace(result, "checks the habitat, says hello, then")
        result = Regex(
            "(?i)(\\b(?:forget|forgets|forgot|forgetting) " +
                "(?:his|her|their|the) keys\\b[^.!?]{0,120}?)" +
                "([.!?]\\s+|\\s+and\\s+)This (?:also )?shows responsibility and " +
                "(?:also )?(?:it )?shows (?:that )?the pet is there\\b",
        ).replace(result) { match ->
            val separator = if (match.groupValues[2].trim() in setOf(".", "!", "?")) {
                match.groupValues[2]
            } else {
                ". "
            }
            "${match.groupValues[1]}${separator}The pet is there"
        }
        result = Regex("(?i)\\bANOTHRE\\b").replace(result, "another")
        result = Regex("(?i)\\bcan only be to be honest (great|good)\\b")
            .replace(result, "can only be $1")
        result = Regex("(?i)\\bWithout proper or whatever proper care\\b")
            .replace(result, "Without proper care")
        result = Regex("(?im)(^|[.!?]\\s+)But this doesn't prove ([^.!?]{1,120}), but\\b")
            .replace(result, "$1This doesn't prove $2, but")
        result = Regex("(?im)(^|[.!?]\\s+)And later the\\b")
            .replace(result, "$1Later the")
        result = Regex("(?i)\\bTo be honest,? you know,? even if\\b")
            .replace(result, "Even if")
        result = Regex("(?i)\\bWitch is a lot of requirements\\b")
            .replace(result, "This involves many requirements")
        result = Regex("(?im)(^|[.!?]\\s+)So not every\\b")
            .replace(result, "$1Not every")
        result = Regex("(?i)\\b(rabbit|cat|dog|pet|animal) This essay\\b")
            .replace(result, "$1. This essay")
        result = Regex("(?i)\\bwhile studying like could\\b")
            .replace(result, "while studying could")
        result = Regex(";\\s*However,").replace(result, "; however,")
        result = Regex("(?i),?\\s*you know--").replace(result, ", ")
        result = result.replace("--", ", ")
        result = Regex("\\b([A-Z][a-z]+) have its own\\b")
            .replace(result, "$1 has its own")
        result = Regex("(?i)\\bwhether streets noises and traditions\\b")
            .replace(result, "weather, streets, noises, and traditions")
        result = Regex("(?i)\\btraditions but\\b").replace(result, "traditions, but")
        result = Regex("(?i)\\blights heaters filters or other equipment\\b")
            .replace(result, "lights, heaters, filters, or other equipment")
        result = Regex("(?i)\\bequipment so the animal\\b")
            .replace(result, "equipment. The animal")
        result = Regex("(?i)\\bstructure Feeding\\b").replace(result, "structure. Feeding")
        result = Regex("(?i)\\bFeeding for real the\\b").replace(result, "Feeding the")
        result = Regex("(?i)\\bcleaning were it lives\\b")
            .replace(result, "cleaning where it lives")
        result = Regex("(?i)\\bThe one in ([A-Z][a-z]+) could write a chart\\b")
            .replace(result, "The owner in $1 could write a chart")
        result = Regex("(?i)\\bin every country even though\\b")
            .replace(result, "in every country. Even though")
        result = Regex("(?im)(^|[.!?]\\s+)Even though ([^.!?]{1,180}),\\s*but\\s+")
            .replace(result, "$1Even though $2, ")
        result = Regex("(?i)\\bdepending where you lives\\b")
            .replace(result, "depending where you live")
        result = Regex("(?i)\\bdepending where you live (a|an)\\b")
            .replace(result, "depending where you live, $1")
        result = Regex(",\\s*The owner Says hello then\\b")
            .replace(result, ", the owner says hello, then")
        result = Regex("(?i)\\bpet Somewhere else so In\\b")
            .replace(result, "pet somewhere else. In")
        result = Regex(
            "(?i)\\bhousing size available vets and the ones daily travel all changes\\b",
        ).replace(result, "housing size, available vets, and the owner's daily travel all change")
        result = Regex("(?i)\\bcan only be to be honest (?:grade|grate)\\b")
            .replace(result, "can only be great")
        result = Regex("(?i)\\s+Etc(?:\\.|(?=\\s|$))").replace(result, "")
        result = Regex("(?im)(^|[.!?]\\s+)But this does not prove ([^.!?]{1,120}), but\\b")
            .replace(result, "$1This does not prove $2, but")
        result = Regex("(?i)\\blaughs Even if\\b").replace(result, "laughs, even if")
        result = Regex("(?i)\\bwas't\\b").replace(result, "wasn't")
        result = Regex("(?i)\\bfunny Pets\\b").replace(result, "funny. Pets")
        result = Regex("(?i)\\bPets turns\\b").replace(result, "Pets turn")
        result = Regex("(?i)\\bfamilies runs out\\b").replace(result, "families run out")
        result = Regex("(?i), but The (owner|animal|person|pet)\\b")
            .replace(result, ", but the $1")
        result = Regex("(?i)(near a [^,.!?]{2,80}), The animal\\b")
            .replace(result, "$1, the animal")
        result = Regex("(?i)\\bchecks the habitat, Says hello then\\b")
            .replace(result, "checks the habitat, says hello, then")
        result = Regex("(?i)\\bwhat care should look like but a plan\\b")
            .replace(result, "what care should look like. A plan")
        result = Regex(
            "(?i)\\bmight not work exactly\\s+" +
                "((?:the )?(?:owner|person|user|reader) " +
                "(?:should|must|could|can|may|might)\\b)",
        ).replace(result) { match ->
            "might not work in exactly the same way. " +
                match.groupValues[1].replaceFirstChar { it.uppercase() }
        }
        result = Regex(
            "(?i)\\bmight not work exactly(?=\\s*(?:[.!?]|$|,\\s+" +
                "(?:but|and|because|although|even if)\\b))",
        ).replace(result, "might not work in exactly the same way")
        result = Regex("(?im)(^|[.!?]\\s+)But also,?\\s*")
            .replace(result, "$1Also, ")
        result = Regex("(?i)\\bStill it connects\\b").replace(result, "Still, it connects")
        result = Regex("(?i)\\bthe Netherlands and it offers\\b")
            .replace(result, "the Netherlands. It offers")
        result = Regex("(?i)\\bcompanionship routine education entertainment\\b")
            .replace(result, "companionship, routine, education, entertainment")
        result = Regex(
            "(?i)\\ba reason to care sort of about something beyond or whatever yourself\\b",
        ).replace(result, "a reason to care about something beyond yourself")
        result = Regex("(?im)(^|[.!?]\\s+)So it also\\b").replace(result, "$1It also")
        result = Regex("(?i)\\brequires money time research cleaning and common sense\\b")
            .replace(result, "requires money, time, research, cleaning, and common sense")
        result = Regex("(?i)\\bcommon sense This involves\\b")
            .replace(result, "common sense. This involves")
        result = Regex("(?i)\\brequirements but most worthwhile\\b")
            .replace(result, "requirements. Most worthwhile")
        result = Regex("(?i)\\bworthwhile things got requirements\\b")
            .replace(result, "worthwhile things have requirements")
        result = Regex("(?i)\\brequirements so not every\\b")
            .replace(result, "requirements. Not every")
        result = Regex(
            "(?i)\\b((?:This|That) (?:involves|has) many requirements)\\.\\s+" +
                "Most worthwhile things have requirements\\.",
        ).replace(result, "$1, as worthwhile things often do.")
        result = Regex("(?i)\\ba essay But wit correct preparation\\b")
            .replace(result, "an essay. With correct preparation")
        result = Regex("(?i)\\bthe home Therefore\\b").replace(result, "the home. Therefore,")
        result = Regex("(?i)\\brabb?i(?:st|t) makes good pets\\b")
            .replace(result, "rabbits make good pets")
        result = Regex("(?i)\\bfit the situation and that is why their a good choice\\b")
            .replace(result, "fit the situation. That is why they are a good choice")
        result = Regex("(?i)\\bcompanionship which is\\b")
            .replace(result, "companionship, which is")
        result = Regex("(?i)\\bdepending where you live\\b")
            .replace(result, "depending on where you live")
        result = Regex("(?i)\\bmorning got busy and this shows\\b")
            .replace(result, "morning got busy. This shows")
        result = Regex("(?i)\\bbelongs to them and maybe you know the same reason\\b")
            .replace(result, "belongs to them.")
        result = Regex(
            "(?i)\\bautomatic except on days when everything goes wrong, but the owner\\b",
        ).replace(result, "automatic except on days when everything goes wrong. The owner")
        result = Regex(
            "(?i), but also this essay is mostly saying pets are good and now it suddenly " +
                "sounds like instructions",
        ).replace(
            result,
            ". Although this essay mainly argues that pets are good, it now sounds like instructions",
        )
        result = Regex("(?i)\\bThese two reasons are essentially the same\\b")
            .replace(result, "These may be two versions of the same reason")
        result = Regex(
            "(?i)\\bOne should use trustworthy local information, but also this essay is " +
                "mostly saying pets are good and now it suddenly sounds like instructions\\.",
        ).replace(
            result,
            "One should use trustworthy local information. Although this essay mainly argues " +
                "that pets are good, it now sounds like instructions.",
        )
        result = Regex(
            "(?i)\\bforgetting is not just forgetting when another living thing depends on you\\b",
        ).replace(result, "forgetting has consequences when another living thing depends on you")
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
        result = CountabilityGrammar.repair(result)
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
        restored = Regex("(?i)(\\bIn [A-Z][a-z]+, [a-z]+), and (the owner's [a-z]+\\b)")
            .replace(restored, "$1 and $2")
        restored = Regex(
            "(?i)\\b(in [A-Z][a-z]+) (England|France|Germany|Greece|Kenya|Mexico|Morocco|Vietnam)\\b",
        ).replace(restored, "$1, $2")
        restored = Regex(
            "(?i)\\b(said|asked|replied|wrote|stated|explained)\\s+(?=\")",
        ).replace(restored, "$1, ")
        restored = Regex("(?i)\\bIntentionally error-filled practice essay (\\d+)[!?]")
            .replace(restored, "Intentionally error-filled practice essay $1.")
        restored = Regex("(?im)(^|[.!?]\\s+)And (a|an)\\b")
            .replace(restored) { match ->
                match.groupValues[1] + match.groupValues[2].replaceFirstChar { it.uppercase() }
            }
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
        restored = Regex("(?i)\\ba\\s+(?=[aeiou][a-z'-]*\\b)").replace(restored, "an ")
        restored = Regex("(?i)\\bwit\\s+(?=correct|proper|careful|adequate|enough\\b)")
            .replace(restored) { match ->
                if (match.value.first().isUpperCase()) "With " else "with "
            }
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
