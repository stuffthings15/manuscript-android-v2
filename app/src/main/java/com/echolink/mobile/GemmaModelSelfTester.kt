package com.echolink.mobile

import android.content.Context
import android.util.Log
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

object GemmaModelSelfTester {
    fun verify(
        context: Context,
        model: InstalledAiModel,
        backendKind: BackendKind,
    ): Boolean {
        val verification = runCatching {
            require(model.artifact.family == AiModelFamily.GEMMA)
            runBlocking {
                withTimeout(SELF_TEST_TIMEOUT_MS) {
                    val backend = LiteRtGemmaBackend(context.applicationContext)
                    try {
                        backend.initialize(model, backendKind)
                        val result = backend.generate(
                            LocalGenerationRequest(
                                jobId = "ai-self-test",
                                operation = WritingOperation.CORRECT_ONLY,
                                previousContext = null,
                                originalTarget = "READY",
                                firstPassCandidate = null,
                                nextContext = null,
                                qualityMode = QualityMode.FAST,
                                strictRetry = true,
                                tone = WritingTone.PROOFREAD,
                            ),
                        )
                        require(
                            result.completed &&
                                result.text.trim().equals("READY", ignoreCase = true),
                        ) {
                            "AI failed its readiness response."
                        }
                    } finally {
                        backend.releaseResources()
                    }
                }
            }
        }
        verification.exceptionOrNull()?.let { failure ->
            Log.e(
                LOG_TAG,
                "AI self-test failed: ${failure.javaClass.simpleName}: " +
                    failure.message.orEmpty().take(MAX_DIAGNOSTIC_LENGTH),
            )
        }
        return verification.isSuccess
    }

    private const val SELF_TEST_TIMEOUT_MS = 90_000L
    private const val MAX_DIAGNOSTIC_LENGTH = 400
    private const val LOG_TAG = "ManuscriptAiSetup"
}
