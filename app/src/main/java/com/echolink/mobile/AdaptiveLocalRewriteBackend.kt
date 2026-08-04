package com.echolink.mobile

class AdaptiveLocalRewriteBackend(
    private val gemmaBackend: LocalRewriteBackend,
    private val legacyBackend: LocalRewriteBackend,
) : LocalRewriteBackend {
    override val backendId: String
        get() = activeBackend?.backendId ?: "uninitialized-ai"

    @Volatile private var activeBackend: LocalRewriteBackend? = null

    override suspend fun initialize(model: InstalledAiModel, backend: BackendKind) {
        val selected = when (model.artifact.family) {
            AiModelFamily.GEMMA -> {
                require(backend == BackendKind.LITERT_CPU || backend == BackendKind.LITERT_GPU)
                gemmaBackend
            }
            AiModelFamily.LEGACY_QWEN -> {
                require(backend == BackendKind.LEGACY_LLAMA_CPU)
                legacyBackend
            }
        }
        val previous = activeBackend
        if (previous != null && previous !== selected) previous.releaseResources()
        selected.initialize(model, backend)
        activeBackend = selected
    }

    override suspend fun generate(
        request: LocalGenerationRequest,
    ): LocalGenerationResult =
        checkNotNull(activeBackend) { "Local AI is not initialized." }.generate(request)

    override suspend fun cancel(jobId: String) {
        activeBackend?.cancel(jobId)
    }

    override fun releaseResources() {
        gemmaBackend.releaseResources()
        legacyBackend.releaseResources()
        activeBackend = null
    }
}
