package com.echolink.mobile

import android.content.Context
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.util.concurrent.TimeUnit

object GemmaModelSelfTester {
    fun verify(
        context: Context,
        model: InstalledAiModel,
        backendKind: BackendKind,
        stopped: () -> Boolean = { false },
    ): Boolean {
        val startedAtNanos = System.nanoTime()
        var backend: LiteRtGemmaBackend? = null
        return verify(
            stopped = stopped,
            operations = object : ModelSelfTestOperations {
                override fun load() {
                    require(model.artifact.family == AiModelFamily.GEMMA)
                    val created = LiteRtGemmaBackend(context.applicationContext)
                    backend = created
                    runWithinBudget(startedAtNanos) {
                        created.initialize(model, backendKind)
                    }
                }

                override fun generate(): Boolean {
                    val active = checkNotNull(backend)
                    val result = runWithinBudget(startedAtNanos) {
                        active.generate(
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
                    }
                    return result.completed &&
                        result.text.trim().equals("READY", ignoreCase = true)
                }

                override fun cleanup() {
                    backend?.releaseResources()
                }
            },
        )
    }

    internal fun verify(
        stopped: () -> Boolean,
        operations: ModelSelfTestOperations,
    ): Boolean = runGuardedModelSelfTest(stopped, operations)

    private fun <Result : Any> runWithinBudget(
        startedAtNanos: Long,
        action: suspend () -> Result,
    ): Result {
        val elapsedMillis = TimeUnit.NANOSECONDS.toMillis(
            (System.nanoTime() - startedAtNanos).coerceAtLeast(0L),
        )
        val remainingMillis = (SELF_TEST_TIMEOUT_MS - elapsedMillis).coerceAtLeast(1L)
        return runBlocking {
            withTimeoutOrNull(remainingMillis) { action() }
        } ?: throw IOException("AI self-test timed out.")
    }

    private const val SELF_TEST_TIMEOUT_MS = 90_000L
}
