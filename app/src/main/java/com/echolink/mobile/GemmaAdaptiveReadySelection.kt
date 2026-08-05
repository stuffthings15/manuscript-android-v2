package com.echolink.mobile

internal class GemmaRuntimeSelectionGate(
    initialSelection: AiRuntimeSelection? = null,
) {
    private var locked: AiRuntimeSelection? = initialSelection?.also(::requireGemma)

    @Synchronized
    fun select(hardwareSelection: () -> AiRuntimeSelection): AiRuntimeSelection =
        locked ?: hardwareSelection()

    @Synchronized
    fun lock(selection: AiRuntimeSelection) {
        requireGemma(selection)
        check(locked == null || locked == selection) {
            "The active Gemma download target must unlock before another target can lock."
        }
        locked = selection
    }

    @Synchronized
    fun unlock(selection: AiRuntimeSelection): Boolean {
        if (locked != selection) return false
        locked = null
        return true
    }

    @Synchronized
    fun clear(): AiRuntimeSelection? = locked.also { locked = null }

    @Synchronized
    fun lockedSelection(): AiRuntimeSelection? = locked

    private fun requireGemma(selection: AiRuntimeSelection) {
        require(selection.artifact.family == AiModelFamily.GEMMA) {
            "Only an exact Gemma download target can be locked."
        }
        require(selection.backend != BackendKind.LEGACY_LLAMA_CPU) {
            "A Gemma download cannot use the legacy backend."
        }
    }
}

internal fun resolveGemmaReadySelection(
    completedSelection: AiRuntimeSelection,
    installed: InstalledAiModel,
    selectedRuntime: () -> AiRuntimeSelection,
    qualifyGpu: (InstalledAiModel) -> Unit,
): AiRuntimeSelection? {
    require(completedSelection.artifact.family == AiModelFamily.GEMMA) {
        "Only a Gemma completion can promote a Gemma runtime."
    }
    require(installed.artifact == completedSelection.artifact) {
        "The completed Gemma selection and installed model must match."
    }

    val beforeQualification = selectedRuntime()
    if (beforeQualification.artifact != completedSelection.artifact) return null
    if (
        completedSelection.backend == BackendKind.LITERT_CPU &&
        beforeQualification.backend == BackendKind.LITERT_CPU
    ) {
        qualifyGpu(installed)
    }
    return selectedRuntime().takeIf { selection ->
        selection.artifact == installed.artifact
    }
}

internal fun shouldPreserveRejectedGemmaModel(
    rejected: AiRuntimeSelection,
    fallback: AiRuntimeSelection,
): Boolean =
    rejected != fallback &&
        rejected.artifact.family == AiModelFamily.GEMMA &&
        rejected.artifact == fallback.artifact &&
        rejected.backend != fallback.backend
