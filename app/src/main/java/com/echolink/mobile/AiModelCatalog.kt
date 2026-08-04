package com.echolink.mobile

object GemmaModelCatalog {
    val fast = AiModelArtifact(
        family = AiModelFamily.GEMMA,
        tier = AiModelTier.FAST,
        repository = "litert-community/gemma-4-E2B-it-litert-lm",
        revision = "6e5c4f1e395deb959c494953478fa5cec4b8008f",
        fileName = "gemma-4-E2B-it.litertlm",
        sizeBytes = 2_588_147_712L,
        sha256 = "181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c",
    )

    val thorough = AiModelArtifact(
        family = AiModelFamily.GEMMA,
        tier = AiModelTier.THOROUGH,
        repository = "litert-community/gemma-4-E4B-it-litert-lm",
        revision = "28299f30ee4d43294517a4ac93abd6163412f07f",
        fileName = "gemma-4-E4B-it.litertlm",
        sizeBytes = 3_659_530_240L,
        sha256 = "0b2a8980ce155fd97673d8e820b4d29d9c7d99b8fa6806f425d969b145bd52e0",
    )

    val legacyCompact = AiModelArtifact(
        family = AiModelFamily.LEGACY_QWEN,
        tier = AiModelTier.LEGACY_COMPACT,
        repository = "Qwen/Qwen3-0.6B-GGUF",
        revision = "23749fefcc72300e3a2ad315e1317431b06b590a",
        fileName = "Qwen3-0.6B-Q8_0.gguf",
        sizeBytes = 639_446_688L,
        sha256 = "9465e63a22add5354d9bb4b99e90117043c7124007664907259bd16d043bb031",
    )
}

object LegacyQwenModelCatalog {
    fun forTier(tier: Qwen3Tier): AiModelArtifact = AiModelArtifact(
        family = AiModelFamily.LEGACY_QWEN,
        tier = when (tier) {
            Qwen3Tier.BASIC -> AiModelTier.LEGACY_COMPACT
            Qwen3Tier.BALANCED -> AiModelTier.LEGACY_BALANCED
            Qwen3Tier.ADVANCED -> AiModelTier.LEGACY_ADVANCED
        },
        repository = tier.repository,
        revision = tier.revision,
        fileName = tier.fileName,
        sizeBytes = tier.sizeBytes,
        sha256 = tier.sha256,
    )
}

enum class AiDeviceFamily {
    GALAXY_S20,
    GALAXY_S23,
    ARM64_CHROMEBOOK,
    X86_64_CHROMEBOOK,
    EMULATOR,
    UNKNOWN,
}

data class AiDeviceProfile(
    val totalRamBytes: Long,
    val availableRamBytes: Long,
    val usableStorageBytes: Long,
    val availableProcessors: Int,
    val is64Bit: Boolean,
    val supportedAbis: Set<String>,
    val androidApi: Int,
    val hasVulkanHardware: Boolean,
    val severeThermalState: Boolean,
    val lowMemoryDevice: Boolean,
    val deviceFamily: AiDeviceFamily,
)

data class BackendQualifications(
    val gemmaGpuQualified: Boolean = false,
    val gemmaThoroughQualified: Boolean = false,
)

data class AiRuntimeSelection(
    val artifact: AiModelArtifact,
    val backend: BackendKind,
)

object AiHardwarePolicy {
    private const val GIB = 1_073_741_824L

    fun select(
        device: AiDeviceProfile,
        qualityMode: QualityMode,
        qualifications: BackendQualifications,
    ): AiRuntimeSelection {
        // Gemma E2B needs roughly 3 GiB resident while generating. On an
        // 8 GB-class phone (reports ~7.2 GiB total, ~3 GiB available) that
        // evicts most of the system and the whole device lags, then the
        // 2.4 GB reload after idle release lags again. Marketed 12 GB
        // devices report ~10.3 GiB, so 9 GiB keeps Gemma exclusive to them;
        // everything below runs the light legacy Qwen tiers that
        // Qwen3CapabilitySelector sizes to the device.
        if (
            !device.is64Bit ||
            device.lowMemoryDevice ||
            device.totalRamBytes < 9L * GIB ||
            device.usableStorageBytes < 4L * GIB
        ) {
            return AiRuntimeSelection(
                GemmaModelCatalog.legacyCompact,
                BackendKind.LEGACY_LLAMA_CPU,
            )
        }

        // Qualification is a capability proof, not a device whitelist: the
        // flags are set only after the same device passed a live self-test on
        // that backend or model, so any OEM that proves itself gets the
        // faster path.
        val canUseGpu =
            qualifications.gemmaGpuQualified &&
                device.hasVulkanHardware &&
                !device.severeThermalState
        val canUseThorough =
            qualityMode == QualityMode.THOROUGH &&
                qualifications.gemmaThoroughQualified &&
                device.totalRamBytes >= 8L * GIB &&
                device.availableRamBytes >= 3L * GIB &&
                device.availableProcessors >= 8 &&
                device.androidApi >= 31 &&
                !device.severeThermalState

        return AiRuntimeSelection(
            artifact = if (canUseThorough) {
                GemmaModelCatalog.thorough
            } else {
                GemmaModelCatalog.fast
            },
            backend = if (canUseGpu) BackendKind.LITERT_GPU else BackendKind.LITERT_CPU,
        )
    }
}
