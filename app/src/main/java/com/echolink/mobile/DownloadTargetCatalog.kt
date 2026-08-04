package com.echolink.mobile

object DownloadTargetCatalog {
    fun targetFor(selection: AiRuntimeSelection): DownloadTargetKey {
        val target = DownloadTargetKey(
            family = selection.artifact.family,
            tier = selection.artifact.tier,
            backend = selection.backend,
        )
        require(selection.artifact == artifactFor(target)) {
            "Download selections must use a canonical model artifact."
        }
        return target
    }

    fun artifactFor(target: DownloadTargetKey): AiModelArtifact = when (target.family) {
        AiModelFamily.GEMMA -> when (target.tier) {
            AiModelTier.FAST -> GemmaModelCatalog.fast
            AiModelTier.THOROUGH -> GemmaModelCatalog.thorough
            else -> error("Gemma target has an invalid model tier.")
        }
        AiModelFamily.LEGACY_QWEN -> LegacyQwenModelCatalog.forTier(qwenTierFor(target))
    }

    fun selectionFor(target: DownloadTargetKey): AiRuntimeSelection = AiRuntimeSelection(
        artifact = artifactFor(target),
        backend = target.backend,
    )

    fun qwenTierFor(target: DownloadTargetKey): Qwen3Tier {
        require(target.family == AiModelFamily.LEGACY_QWEN) {
            "Only legacy targets have a Qwen tier."
        }
        return when (target.tier) {
            AiModelTier.LEGACY_COMPACT -> Qwen3Tier.BASIC
            AiModelTier.LEGACY_BALANCED -> Qwen3Tier.BALANCED
            AiModelTier.LEGACY_ADVANCED -> Qwen3Tier.ADVANCED
            else -> error("Legacy target has an invalid model tier.")
        }
    }
}
