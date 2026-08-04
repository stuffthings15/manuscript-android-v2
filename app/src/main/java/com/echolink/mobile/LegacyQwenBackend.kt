package com.echolink.mobile

import android.content.Context
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class LegacyQwenBackend(context: Context) : LocalRewriteBackend {
    override val backendId: String = "legacy-qwen"
    private val runtime = Qwen3GrammarRuntime(context.applicationContext)
    private val cancelledJobs = ConcurrentHashMap.newKeySet<String>()
    @Volatile private var initialized = false

    override suspend fun initialize(model: InstalledAiModel, backend: BackendKind) {
        require(model.artifact.family == AiModelFamily.LEGACY_QWEN)
        require(backend == BackendKind.LEGACY_LLAMA_CPU)
        initialized = runtime.isReady()
        check(initialized) { "The hidden compatibility AI is not ready." }
    }

    override suspend fun generate(request: LocalGenerationRequest): LocalGenerationResult =
        withContext(Dispatchers.IO) {
            // A cancelled sibling request or a low-memory trim releases the
            // shared runtime and clears this flag. Re-arm instead of failing
            // the user's next correction: the runtime reloads the model on
            // demand, so readiness is the only thing to restore.
            if (!initialized) initialized = runtime.isReady()
            check(initialized) { "The hidden compatibility AI is not initialized." }
            if (cancelledJobs.remove(request.jobId)) {
                return@withContext LocalGenerationResult(
                    jobId = request.jobId,
                    text = "",
                    completed = false,
                    errorMessage = "Cancelled",
                )
            }
            val target = request.firstPassCandidate
                ?.takeIf { request.operation == WritingOperation.REVIEW_CANDIDATE }
                ?: request.originalTarget
            val output = runtime.rewrite(
                source = target,
                tone = request.tone ?: WritingTone.PROOFREAD,
                strict = request.strictRetry,
            )
            LocalGenerationResult(
                jobId = request.jobId,
                text = output.orEmpty(),
                completed = !output.isNullOrBlank(),
                errorMessage = if (output.isNullOrBlank()) "AI generation failed." else null,
            )
        }

    override suspend fun cancel(jobId: String) {
        // Only mark the job. Tearing the shared runtime down here also killed
        // the document run that replaced it, and left the backend flagged
        // uninitialized for every later correction.
        cancelledJobs += jobId
    }

    override fun releaseResources() {
        initialized = false
        runtime.releaseResources()
    }

    fun setKeepWarm(warm: Boolean) = runtime.setKeepWarm(warm)

    fun preload() {
        runtime.preload()
        initialized = runtime.isReady()
    }
}
