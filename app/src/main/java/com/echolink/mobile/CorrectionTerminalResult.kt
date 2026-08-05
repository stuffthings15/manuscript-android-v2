package com.echolink.mobile

sealed interface CorrectionTerminalResult {
    data class Success(
        val correctedText: String,
        val modelId: String,
        val elapsedMs: Long,
    ) : CorrectionTerminalResult

    data class RetryableFailure(
        val userMessage: String,
        val elapsedMs: Long,
    ) : CorrectionTerminalResult

    data object ModelRequired : CorrectionTerminalResult
}

object CorrectionTerminalResolver {
    fun resolve(
        backendResult: Result<String>,
        originalText: String,
        modelId: String,
        elapsedMs: Long,
        errorsRemain: Boolean = false,
    ): CorrectionTerminalResult = backendResult.fold(
        onSuccess = { correctedText ->
            when {
                correctedText.isBlank() -> CorrectionTerminalResult.RetryableFailure(
                    userMessage = "AI returned no correction. Tap Retry.",
                    elapsedMs = elapsedMs,
                )
                correctedText.contains('\u2014') -> CorrectionTerminalResult.RetryableFailure(
                    userMessage = "AI correction needs another pass. Tap Retry.",
                    elapsedMs = elapsedMs,
                )
                errorsRemain && correctedText.trim() == originalText.trim() ->
                    CorrectionTerminalResult.RetryableFailure(
                        userMessage = "Errors remain. Tap Retry.",
                        elapsedMs = elapsedMs,
                    )
                else -> CorrectionTerminalResult.Success(
                    correctedText = correctedText,
                    modelId = modelId,
                    elapsedMs = elapsedMs,
                )
            }
        },
        onFailure = { error ->
            if (error is LocalModelRequiredException) {
                CorrectionTerminalResult.ModelRequired
            } else {
                CorrectionTerminalResult.RetryableFailure(
                    userMessage = "Correction failed. Tap Retry.",
                    elapsedMs = elapsedMs,
                )
            }
        },
    )
}
