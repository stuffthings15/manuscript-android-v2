package com.echolink.mobile

import java.io.File

enum class WritingOperation {
    CORRECT_ONLY,
    POLISH_PRESERVE_VOICE,
    TONE_TRANSFORM,
    REVIEW_CANDIDATE,
}

enum class QualityMode {
    AUTO,
    FAST,
    THOROUGH,
}

enum class BackendKind {
    LITERT_CPU,
    LITERT_GPU,
    LEGACY_LLAMA_CPU,
}

enum class AiModelFamily {
    GEMMA,
    LEGACY_QWEN,
}

enum class AiModelTier {
    FAST,
    THOROUGH,
    LEGACY_COMPACT,
    LEGACY_BALANCED,
    LEGACY_ADVANCED,
}

data class AiModelArtifact(
    val family: AiModelFamily,
    val tier: AiModelTier,
    val repository: String,
    val revision: String,
    val fileName: String,
    val sizeBytes: Long,
    val sha256: String,
) {
    init {
        require(revision.isNotBlank() && revision != "main") {
            "AI model downloads must use a pinned repository revision."
        }
        require(sha256.matches(Regex("[0-9a-f]{64}"))) {
            "AI model downloads require a lowercase SHA-256 value."
        }
        require(sizeBytes > 0L) {
            "AI model downloads require a positive byte count."
        }
    }

    val downloadUrl: String
        get() = "https://huggingface.co/$repository/resolve/$revision/$fileName"
}

data class InstalledAiModel(
    val artifact: AiModelArtifact,
    val file: File,
)

data class LocalGenerationRequest(
    val jobId: String,
    val operation: WritingOperation,
    val previousContext: String?,
    val originalTarget: String,
    val firstPassCandidate: String?,
    val nextContext: String?,
    val qualityMode: QualityMode,
    val strictRetry: Boolean,
    val tone: WritingTone? = null,
)

data class LocalGenerationResult(
    val jobId: String,
    val text: String,
    val completed: Boolean,
    val errorMessage: String? = null,
)

interface LocalRewriteBackend {
    val backendId: String

    suspend fun initialize(model: InstalledAiModel, backend: BackendKind)

    suspend fun generate(request: LocalGenerationRequest): LocalGenerationResult

    suspend fun cancel(jobId: String)

    fun releaseResources()
}

@Deprecated("Use BackendKind in the generic local AI runtime.")
enum class LocalBackendType(val displayName: String) {
    LLAMA_CPP_CPU("llama.cpp CPU"),
    LITERT_LM("LiteRT-LM"),
    MLC_VULKAN("MLC Vulkan"),
}

data class BackendQualification(
    val type: LocalBackendType,
    val qualified: Boolean,
    val reason: String,
)

object LocalInferenceCoordinator {
    val lock = Any()
}

/**
 * Accelerated engines remain disabled until their artifacts pass the same
 * quality, protected-text, stability, and physical-device tests as llama.cpp.
 */
object LocalBackendRegistry {
    fun qualifications(): List<BackendQualification> = listOf(
        BackendQualification(
            LocalBackendType.LLAMA_CPP_CPU,
            qualified = true,
            reason = "Universal ARM64 and x86_64 backend.",
        ),
        BackendQualification(
            LocalBackendType.LITERT_LM,
            qualified = false,
            reason = "No release-qualified accelerated AI artifact is installed.",
        ),
        BackendQualification(
            LocalBackendType.MLC_VULKAN,
            qualified = false,
            reason = "Requires physical GPU qualification for this device.",
        ),
    )
}
