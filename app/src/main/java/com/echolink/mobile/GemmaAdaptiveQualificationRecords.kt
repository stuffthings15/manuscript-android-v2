package com.echolink.mobile

internal object GemmaAdaptiveQualificationRecords {
    fun qualificationKey(artifact: AiModelArtifact, backend: BackendKind): String =
        "gemma_${artifactToken(artifact)}_${backendToken(backend)}_qualified_sha256"

    fun rejectionKey(artifact: AiModelArtifact, backend: BackendKind): String =
        "gemma_${artifactToken(artifact)}_${backendToken(backend)}_rejected_sha256"

    fun isQualified(
        recordedSha256: String?,
        artifact: AiModelArtifact,
        backend: BackendKind,
    ): Boolean = isExactRecord(recordedSha256, artifact, backend)

    fun isRejected(
        recordedSha256: String?,
        artifact: AiModelArtifact,
        backend: BackendKind,
    ): Boolean = isExactRecord(recordedSha256, artifact, backend)

    fun lockedSelection(
        recordedSha256: String?,
        recordedBackend: String?,
    ): AiRuntimeSelection? {
        val backend = BackendKind.entries.firstOrNull { it.name == recordedBackend }
            ?.takeIf { it != BackendKind.LEGACY_LLAMA_CPU }
            ?: return null
        val artifact = listOf(GemmaModelCatalog.fast, GemmaModelCatalog.thorough)
            .firstOrNull { it.sha256.equals(recordedSha256, ignoreCase = true) }
            ?: return null
        return AiRuntimeSelection(artifact, backend)
    }

    private fun isExactRecord(
        recordedSha256: String?,
        artifact: AiModelArtifact,
        backend: BackendKind,
    ): Boolean =
        artifact.family == AiModelFamily.GEMMA &&
            backend != BackendKind.LEGACY_LLAMA_CPU &&
            recordedSha256.equals(artifact.sha256, ignoreCase = true)

    private fun artifactToken(artifact: AiModelArtifact): String {
        require(artifact.family == AiModelFamily.GEMMA) {
            "Qualification records require a Gemma artifact."
        }
        return when (artifact.tier) {
            AiModelTier.FAST -> "fast"
            AiModelTier.THOROUGH -> "thorough"
            else -> throw IllegalArgumentException("Qualification records require a Gemma tier.")
        }
    }

    private fun backendToken(backend: BackendKind): String = when (backend) {
        BackendKind.LITERT_CPU -> "cpu"
        BackendKind.LITERT_GPU -> "gpu"
        BackendKind.LEGACY_LLAMA_CPU ->
            throw IllegalArgumentException("A legacy backend cannot qualify Gemma.")
    }
}
