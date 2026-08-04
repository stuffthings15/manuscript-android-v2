package com.echolink.mobile

import android.content.Context
import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.MessageCallback
import com.google.ai.edge.litertlm.SamplerConfig
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

data class LiteRtSamplingPolicy(
    val topK: Int,
    val topP: Double,
    val temperature: Double,
    val seed: Int,
)

interface LiteRtEngineClient {
    fun initialize(
        model: InstalledAiModel,
        backend: BackendKind,
        cacheDirectory: File,
    )

    suspend fun generate(
        jobId: String,
        systemInstruction: String,
        prompt: String,
        samplingPolicy: LiteRtSamplingPolicy,
        maximumOutputTokens: Int,
    ): String

    fun cancel(jobId: String)

    fun close()
}

internal class GoogleLiteRtEngineClient : LiteRtEngineClient {
    private var engine: Engine? = null
    private val conversations = ConcurrentHashMap<String, Conversation>()

    override fun initialize(
        model: InstalledAiModel,
        backend: BackendKind,
        cacheDirectory: File,
    ) {
        require(model.artifact.family == AiModelFamily.GEMMA)
        require(backend == BackendKind.LITERT_CPU || backend == BackendKind.LITERT_GPU)
        close()
        cacheDirectory.mkdirs()
        val runtimeBackend = when (backend) {
            BackendKind.LITERT_CPU -> Backend.CPU()
            BackendKind.LITERT_GPU -> Backend.GPU()
            BackendKind.LEGACY_LLAMA_CPU -> error("Legacy AI cannot use LiteRT.")
        }
        engine = Engine(
            EngineConfig(
                modelPath = model.file.absolutePath,
                backend = runtimeBackend,
                maxNumTokens = ENGINE_CONTEXT_TOKENS,
                cacheDir = cacheDirectory.absolutePath,
            ),
        ).also(Engine::initialize)
    }

    override suspend fun generate(
        jobId: String,
        systemInstruction: String,
        prompt: String,
        samplingPolicy: LiteRtSamplingPolicy,
        maximumOutputTokens: Int,
    ): String = withContext(Dispatchers.IO) {
        val readyEngine = checkNotNull(engine) { "LiteRT AI is not initialized." }
        val conversationStartedAt = System.nanoTime()
        // Measured: reusing a conversation saved nothing. Prefill is charged
        // per message by this runtime (about ten seconds whether the prompt
        // is 2,200 or 4,200 characters), so a fresh conversation per call is
        // simpler and costs the same.
        val conversation = readyEngine.createConversation(
            ConversationConfig(
                systemInstruction = Contents.of(systemInstruction),
                samplerConfig = SamplerConfig(
                    topK = samplingPolicy.topK,
                    topP = samplingPolicy.topP,
                    temperature = samplingPolicy.temperature,
                    seed = samplingPolicy.seed,
                ),
            ),
        )
        val conversationMs = (System.nanoTime() - conversationStartedAt) / 1_000_000L
        conversations[jobId] = conversation
        try {
            val output = StringBuilder()
            val completion = CompletableDeferred<Unit>()
            val stopped = AtomicBoolean(false)
            // Time to the first fragment is prompt processing; the rest is
            // token generation. Splitting them is the only way to know
            // whether reusing a conversation (prompt cost) or a faster model
            // (token cost) is the lever worth pulling.
            val requestedAt = System.nanoTime()
            var firstFragmentAt = 0L
            val callback = object : MessageCallback {
                override fun onMessage(message: Message) {
                    if (stopped.get()) return
                    if (firstFragmentAt == 0L) firstFragmentAt = System.nanoTime()
                    val fragment = message.toString()
                    synchronized(output) {
                        if (!LiteRtOutputBudget.canAppend(output, fragment, maximumOutputTokens)) {
                            if (stopped.compareAndSet(false, true)) {
                                conversation.cancelProcess()
                                completion.completeExceptionally(LiteRtOutputLimitException())
                            }
                            return
                        }
                        output.append(fragment)
                    }
                }

                override fun onDone() {
                    if (stopped.compareAndSet(false, true)) {
                        completion.complete(Unit)
                    }
                }

                override fun onError(throwable: Throwable) {
                    if (stopped.compareAndSet(false, true)) {
                        completion.completeExceptionally(throwable)
                    }
                }
            }
            conversation.sendMessageAsync(prompt, callback, emptyMap())
            try {
                completion.await()
            } catch (cancelled: CancellationException) {
                stopped.set(true)
                conversation.cancelProcess()
                throw cancelled
            }
            val text = synchronized(output) { output.toString() }
            val totalMs = (System.nanoTime() - requestedAt) / 1_000_000L
            val prefillMs = if (firstFragmentAt == 0L) {
                totalMs
            } else {
                (firstFragmentAt - requestedAt) / 1_000_000L
            }
            Log.i(
                TIMING_TAG,
                "generate;contextTokens=$ENGINE_CONTEXT_TOKENS;" +
                    "conversationMs=$conversationMs;" +
                    "promptChars=${prompt.length};systemChars=" +
                    "${systemInstruction.length};outChars=${text.length};" +
                    "prefillMs=$prefillMs;decodeMs=${totalMs - prefillMs};" +
                    "totalMs=$totalMs",
            )
            text
        } finally {
            conversations.remove(jobId, conversation)
            conversation.close()
        }
    }

    override fun cancel(jobId: String) {
        conversations[jobId]?.cancelProcess()
    }

    override fun close() {
        conversations.values.forEach { conversation ->
            runCatching { conversation.cancelProcess() }
            runCatching { conversation.close() }
        }
        conversations.clear()
        runCatching { engine?.close() }
        engine = null
    }

    companion object {
        private const val TIMING_TAG = "ManuscriptTiming"
        // A paragraph plus its instructions needs roughly a thousand
        // tokens. The window was four times that, and prefill appears to
        // scale with the window rather than the prompt, so it is sized to
        // the work instead.
        private const val ENGINE_CONTEXT_TOKENS = 2_048
    }
}

internal class LiteRtOutputLimitException :
    IllegalStateException("AI output exceeded its safe generation limit.")

object LiteRtOutputBudget {
    private const val MAX_CHARACTERS_PER_TOKEN = 8

    fun canAppend(
        output: StringBuilder,
        fragment: String,
        maximumOutputTokens: Int,
    ): Boolean =
        output.length.toLong() + fragment.length <=
            maximumOutputTokens.toLong() * MAX_CHARACTERS_PER_TOKEN
}

class LiteRtGemmaBackend internal constructor(
    private val engineFactory: () -> LiteRtEngineClient,
    private val cacheDirectory: File,
    private val idleReleaseMillis: Long,
    private val generationTimeoutMillis: Long = DEFAULT_GENERATION_TIMEOUT_MILLIS,
) : LocalRewriteBackend {
    constructor(context: Context) : this(
        engineFactory = ::GoogleLiteRtEngineClient,
        cacheDirectory = File(context.applicationContext.cacheDir, "litertlm"),
        idleReleaseMillis = DEFAULT_IDLE_RELEASE_MILLIS,
    )

    override val backendId: String = "litert-gemma"
    private val generationMutex = Mutex()
    private val lifecycleLock = Any()
    private val cancelledJobs = ConcurrentHashMap.newKeySet<String>()
    private val idleExecutor = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "manuscript-ai-idle").apply { isDaemon = true }
    }
    @Volatile private var engineClient: LiteRtEngineClient? = null
    @Volatile private var activeModelPath: String? = null
    @Volatile private var activeBackend: BackendKind? = null
    // The last model this backend was told to run, remembered ACROSS a
    // release. An idle timeout or a low-memory trim can close the engine in
    // the middle of a document; without this the next paragraph died with
    // "LiteRT AI is not initialized" and took the whole run with it.
    @Volatile private var lastModel: InstalledAiModel? = null
    @Volatile private var lastBackend: BackendKind? = null
    @Volatile private var keepWarm: Boolean = false
    private var idleRelease: ScheduledFuture<*>? = null

    override suspend fun initialize(model: InstalledAiModel, backend: BackendKind) {
        require(model.artifact.family == AiModelFamily.GEMMA)
        require(backend == BackendKind.LITERT_CPU || backend == BackendKind.LITERT_GPU)
        generationMutex.withLock {
            loadEngine(model, backend)
        }
    }

    /** Caller must hold [generationMutex]. */
    private suspend fun loadEngine(model: InstalledAiModel, backend: BackendKind) {
        lastModel = model
        lastBackend = backend
        if (
            activeModelPath == model.file.absolutePath &&
            activeBackend == backend &&
            engineClient != null
        ) {
            scheduleIdleRelease()
            return
        }
        withContext(Dispatchers.IO) {
            synchronized(lifecycleLock) {
                idleRelease?.cancel(false)
                engineClient?.close()
                val replacement = engineFactory()
                try {
                    replacement.initialize(model, backend, cacheDirectory)
                } catch (failure: Throwable) {
                    replacement.close()
                    throw failure
                }
                engineClient = replacement
                activeModelPath = model.file.absolutePath
                activeBackend = backend
            }
        }
        scheduleIdleRelease()
    }

    override suspend fun generate(
        request: LocalGenerationRequest,
    ): LocalGenerationResult = generationMutex.withLock {
        idleRelease?.cancel(false)
        // Re-arm rather than fail. A low-memory trim or the idle timer may
        // have closed the engine since the last paragraph; the model file is
        // still installed, so reload it and carry on with the document.
        if (engineClient == null) {
            val model = lastModel
            val backend = lastBackend
            if (model != null && backend != null) {
                runCatching { loadEngine(model, backend) }
            }
        }
        val client = checkNotNull(engineClient) { "LiteRT AI is not initialized." }
        if (cancelledJobs.remove(request.jobId)) {
            return@withLock cancelledResult(request.jobId)
        }
        val output = runCatching {
            withTimeout(generationTimeoutMillis) {
                client.generate(
                    jobId = request.jobId,
                    systemInstruction = LiteRtPromptBuilder.systemInstruction,
                    prompt = LiteRtPromptBuilder.build(request),
                    samplingPolicy = DEFAULT_SAMPLING_POLICY,
                    maximumOutputTokens = outputTokenLimit(request.originalTarget),
                )
            }
        }
        if (output.exceptionOrNull() is TimeoutCancellationException) {
            client.cancel(request.jobId)
        }
        val cancelled = cancelledJobs.remove(request.jobId)
        scheduleIdleRelease()
        if (cancelled) return@withLock cancelledResult(request.jobId)
        val text = output.getOrNull()
            ?.let { value -> LiteRtPromptBuilder.cleanOutput(value, request.originalTarget) }
            .orEmpty()
        LocalGenerationResult(
            jobId = request.jobId,
            text = text,
            completed = text.isNotBlank(),
            errorMessage = output.exceptionOrNull()?.message
                ?: if (text.isBlank()) "AI generation failed." else null,
        )
    }

    override suspend fun cancel(jobId: String) {
        cancelledJobs += jobId
        synchronized(lifecycleLock) {
            engineClient?.cancel(jobId)
        }
    }

    override fun releaseResources() {
        synchronized(lifecycleLock) {
            idleRelease?.cancel(false)
            idleRelease = null
            engineClient?.close()
            engineClient = null
            activeModelPath = null
            activeBackend = null
            cancelledJobs.clear()
        }
    }

    /**
     * Pins the engine in memory for the duration of a run. Reloading this
     * model costs tens of seconds, so a document must never pay it between
     * paragraphs.
     */
    fun setKeepWarm(warm: Boolean) {
        synchronized(lifecycleLock) {
            keepWarm = warm
            if (warm) {
                idleRelease?.cancel(false)
                idleRelease = null
            }
        }
        if (!warm) scheduleIdleRelease()
    }

    private fun scheduleIdleRelease() {
        synchronized(lifecycleLock) {
            if (keepWarm) return
            idleRelease?.cancel(false)
            idleRelease = idleExecutor.schedule(
                ::releaseResources,
                idleReleaseMillis,
                TimeUnit.MILLISECONDS,
            )
        }
    }

    private fun outputTokenLimit(source: String): Int =
        (source.length / 3 * 3 / 2 + 64).coerceIn(96, 2_048)

    private fun cancelledResult(jobId: String) = LocalGenerationResult(
        jobId = jobId,
        text = "",
        completed = false,
        errorMessage = "Cancelled",
    )

    companion object {
        private const val DEFAULT_IDLE_RELEASE_MILLIS = 2L * 60L * 1_000L
        private const val DEFAULT_GENERATION_TIMEOUT_MILLIS = 2L * 60L * 1_000L
        // Greedy decoding: with topK 40 / topP 0.9 the same text produced
        // different corrections on different runs, which users read as the
        // app being unreliable. topK 1 always takes the highest-probability
        // token, so one input gives one output. Mirrors the Windows sampler
        // pin of 2026-08-01; temperature stays positive for engine safety.
        private val DEFAULT_SAMPLING_POLICY = LiteRtSamplingPolicy(
            topK = 1,
            topP = 1.0,
            temperature = 0.1,
            seed = 42,
        )
    }
}

object LiteRtPromptBuilder {
    val systemInstruction: String = """
        You are the local Manuscript writing AI.
        Return only the requested replacement target.
        Previous and next context are reference material only. Never copy context into the answer.
        Preserve all facts, actors, names, numbers, dates, links, quotations, permissions, prohibitions, obligations, uncertainty, and formatting.
        Do not add facts or remove essential ideas.
        Do not use em dashes.
        Do not explain your answer.
    """.trimIndent()

    fun build(request: LocalGenerationRequest): String = buildString {
        appendLine(operationInstruction(request))
        if (request.strictRetry) {
            appendLine(
                "Run a strict final audit. Repair every issue the earlier pass missed while " +
                    "applying every preservation rule more strictly.",
            )
            appendLine(
                "The text below includes proposed repairs from a local grammar checker. " +
                    "Start from that text. Keep every valid spelling, capitalization, " +
                    "hyphenation, punctuation, agreement, and parallelism repair. Reject only a " +
                    "proposal that changes meaning or protected facts. Never copy a known error " +
                    "back from the immutable original. Return your own fully verified final target.",
            )
        }
        appendLine()
        appendLine("REFERENCE_CONTEXT_BEGIN")
        appendLine("PREVIOUS_CONTEXT")
        appendLine(request.previousContext.orEmpty())
        appendLine()
        appendLine("NEXT_CONTEXT")
        appendLine(request.nextContext.orEmpty())
        appendLine("REFERENCE_CONTEXT_END")
        appendLine()
        if (request.operation == WritingOperation.REVIEW_CANDIDATE) {
            appendLine("IMMUTABLE_ORIGINAL_TARGET_BEGIN")
            appendLine(request.originalTarget)
            appendLine("IMMUTABLE_ORIGINAL_TARGET_END")
            appendLine()
            appendLine("FIRST_PASS_CANDIDATE_BEGIN")
            appendLine(request.firstPassCandidate.orEmpty())
            appendLine("FIRST_PASS_CANDIDATE_END")
        } else {
            appendLine("TARGET_BEGIN")
            appendLine(request.originalTarget)
            appendLine("TARGET_END")
        }
        appendLine()
        append(
            "Return only the corrected target text. Do not return labels, markers, " +
                "previous context, or next context.",
        )
    }

    fun cleanOutput(value: String, originalTarget: String? = null): String {
        var result = value.trim()
        result = Regex("(?s)<think>.*?</think>").replace(result, "").trim()
        if (result.startsWith("```") && result.endsWith("```")) {
            result = result.removePrefix("```").removeSuffix("```").trim()
            result = result.removePrefix("text").trimStart()
        }
        if (result.length >= 2 && result.first() == '"' && result.last() == '"') {
            result = result.substring(1, result.lastIndex)
        }
        result = extractMarkedTarget(result)
        val normalizedTarget = originalTarget?.trimStart().orEmpty()
        val targetIsMarkdownHeading =
            normalizedTarget.startsWith("#") ||
                normalizedTarget.startsWith("[[MS_MARKDOWN_PREFIX_")
        if (targetIsMarkdownHeading && '\n' !in normalizedTarget && '\n' in result) {
            result = result.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty()
        }
        result = restoreQuotationBoundaryPunctuation(
            original = originalTarget.orEmpty(),
            candidate = result,
        )
        if (
            originalTarget.orEmpty().trimEnd().endsWith(":") &&
            result.trimEnd().endsWith(".")
        ) {
            result = result.trimEnd().dropLast(1) + ":"
        }
        return result.trim()
    }

    private fun extractMarkedTarget(value: String): String {
        val markers = listOf(
            "TARGET_BEGIN" to "TARGET_END",
            "FIRST_PASS_CANDIDATE_BEGIN" to "FIRST_PASS_CANDIDATE_END",
        )
        markers.forEach { (start, end) ->
            val startAt = value.indexOf(start)
            val endAt = value.indexOf(end, startIndex = (startAt + start.length).coerceAtLeast(0))
            if (startAt >= 0 && endAt > startAt) {
                return value.substring(startAt + start.length, endAt).trim()
            }
        }
        return value
    }

    private fun restoreQuotationBoundaryPunctuation(
        original: String,
        candidate: String,
    ): String {
        var restored = candidate
        val quotationToken = Regex("\\[\\[MS_QUOTATION_\\d{4}]]")
        quotationToken.findAll(original).map(MatchResult::value).forEach { token ->
            val originalAt = original.indexOf(token)
            val candidateAt = restored.indexOf(token)
            if (originalAt < 0 || candidateAt < 0) return@forEach
            val expected = Regex("^[.!?]+")
                .find(original.substring(originalAt + token.length))
                ?.value
                .orEmpty()
            val actual = Regex("^[.!?]+")
                .find(restored.substring(candidateAt + token.length))
                ?.value
                .orEmpty()
            if (actual != expected) {
                val suffixAt = candidateAt + token.length
                restored = restored.replaceRange(
                    suffixAt,
                    suffixAt + actual.length,
                    expected,
                )
            }
        }
        return restored
    }

    private fun operationInstruction(request: LocalGenerationRequest): String {
        val tone = when (request.operation) {
            WritingOperation.CORRECT_ONLY -> WritingTone.PROOFREAD
            WritingOperation.POLISH_PRESERVE_VOICE -> WritingTone.IMPROVE
            WritingOperation.TONE_TRANSFORM,
            WritingOperation.REVIEW_CANDIDATE,
            -> request.tone ?: WritingTone.PROOFREAD
        }
        val spec = TonePromptLibrary.spec(tone)
        return buildString {
            if (request.operation == WritingOperation.REVIEW_CANDIDATE) {
                appendLine(
                    "Review the candidate against the immutable original sentence by sentence. " +
                        "Return a fully repaired candidate, not comments about it.",
                )
                appendLine("Reviewer checklist:")
                appendLine(
                    "- Find every remaining spelling or real-word error, agreement error, " +
                        "pronoun error, tense error, fragment, run-on, comma splice, dangling " +
                        "modifier, parallelism error, punctuation error, and unclear construction.",
                )
                appendLine(
                    "- Audit every changed noun, verb, adjective, and adverb. Restore the " +
                        "original word unless changing it is required for correctness or the " +
                        "selected tone. Never replace a correct word with a similar-looking word.",
                )
                appendLine(
                    "- Preserve current roles, required action order, domain terms, and the " +
                        "difference between permission, prohibition, and obligation.",
                )
                appendLine(
                    "- If the original explicitly says a nearby statement is incorrect, " +
                        "repair that statement using only meaning established by the context.",
                )
                appendLine(
                    "- Check compound modifiers, collective-noun agreement, objective versus " +
                        "reflexive pronouns, correlative subject-verb agreement, parallel verb " +
                        "forms, quotation punctuation, and comma-splice repairs explicitly.",
                )
                appendLine(
                    "- Also check dangling modifiers, unresolved statements explicitly labeled " +
                        "incorrect, direct-quotation commas, title capitalization, and parallel " +
                        "list grammar.",
                )
                appendLine(
                    "- Do not assume the first-pass candidate is correct. Return it only after " +
                        "every word and sentence passes this checklist.",
                )
                appendLine("- Recheck the complete candidate after all repairs.")
            }
            appendLine(spec.instruction)
            if (tone != WritingTone.PROOFREAD && tone != WritingTone.IMPROVE) {
                appendLine(
                    "Mandatory tone transformation: visibly rewrite the wording and " +
                        "sentence structure in the selected tone. Returning the target " +
                        "unchanged or nearly unchanged is a failed answer.",
                )
            }
            appendLine("Requirements:")
            appendLine("- Correct every grammar, spelling, punctuation, capitalization, vocabulary, and sentence-structure error.")
            appendLine("- Preserve all facts, actors, names, numbers, dates, links, quotations, permissions, prohibitions, obligations, uncertainty, and formatting.")
            appendLine(
                "- Copy every [[MS_TYPE_0000]] protected token exactly once, unchanged, " +
                    "and in the same order.",
            )
            appendLine(
                "- Keep each protected token at its original word boundary. Never insert a " +
                    "token inside a word, and preserve the spaces or punctuation touching it.",
            )
            appendLine(
                "- Preserve every correct content word. Do not replace a word merely because " +
                    "another word looks or sounds similar.",
            )
            appendLine(
                "- Use the surrounding context to resolve agreement, pronouns, tense, " +
                    "modifiers, action order, and explicit contradictions.",
            )
            appendLine(
                "- Correct spelling and capitalization inside Markdown headings while keeping " +
                    "the heading prefix and line structure unchanged.",
            )
            val targetLineCount = request.originalTarget.count { it == '\n' } + 1
            appendLine(
                "- The target has $targetLineCount line${if (targetLineCount == 1) "" else "s"}. " +
                    "Return exactly $targetLineCount line${if (targetLineCount == 1) "" else "s"} " +
                    "in the same order.",
            )
            val markdownMarkerCount =
                Regex("\\[\\[MS_MARKDOWN_PREFIX_\\d{4}]]")
                    .findAll(request.originalTarget)
                    .count()
            if (markdownMarkerCount > 0) {
                appendLine(
                    "- Return all $markdownMarkerCount protected Markdown prefix " +
                        "token${if (markdownMarkerCount == 1) "" else "s"}, each on its original line.",
                )
            }
            appendLine(
                "- Verify that every non-protected word uses standard American English spelling, " +
                    "including words in headings and words that merely look like valid words.",
            )
            appendLine(
                "- Protected quotation tokens already preserve their quotation text. Keep the " +
                    "punctuation immediately after each quotation token exactly as supplied.",
            )
            appendLine("- Never copy previous or next context into the returned target.")
            appendLine(
                "- When the source explicitly labels a statement incorrect, correct only that " +
                    "statement and only from facts already present in the source.",
            )
            appendLine(
                "- When a permission is explicitly labeled incorrect by a stated privacy or " +
                    "safety restriction, repair the permission so it matches that restriction.",
            )
            spec.must.forEach { appendLine("- $it") }
            appendLine("Restrictions:")
            appendLine("- Do not invent or remove facts or essential ideas.")
            appendLine("- Do not use em dashes.")
            spec.avoid.forEach { appendLine("- $it") }
        }.trimEnd()
    }
}
