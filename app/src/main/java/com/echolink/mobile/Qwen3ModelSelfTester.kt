package com.echolink.mobile

import android.content.Context
import com.arm.aichat.AiChat
import com.arm.aichat.InferenceEngine
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Qualifies a downloaded file with a real local load and inference before the
 * model can replace the last known-good tier.
 */
object Qwen3ModelSelfTester {
    fun verify(
        context: Context,
        model: File,
        stopped: () -> Boolean = { false },
    ): Boolean =
        synchronized(LocalInferenceCoordinator.lock) {
            val startedAtNanos = System.nanoTime()
            var engine: InferenceEngine? = null
            verify(
                stopped = stopped,
                operations = object : ModelSelfTestOperations {
                    override fun load() {
                        val created = AiChat.getInferenceEngine(context.applicationContext)
                        engine = created
                        runWithinBudget(startedAtNanos) {
                            awaitInitialization(created)
                            when (created.state.value) {
                                is InferenceEngine.State.ModelReady,
                                is InferenceEngine.State.Error,
                                -> {
                                    throwIfModelSelfTestStopped(stopped)
                                    created.cleanUp()
                                }
                                else -> Unit
                            }
                            throwIfModelSelfTestStopped(stopped)
                            val contextSize = if (model.name.contains("0.6B")) 512 else 1_024
                            val threads = Runtime.getRuntime()
                                .availableProcessors()
                                .coerceIn(2, 4)
                            created.loadModel(model.absolutePath, contextSize, threads)
                        }
                    }

                    override fun generate(): Boolean {
                        val active = checkNotNull(engine)
                        return runWithinBudget(startedAtNanos) {
                            active.setSystemPrompt("Reply with exactly READY.")
                            val output = StringBuilder()
                            active.sendUserPrompt("/no_think\nReply with exactly READY.", 8)
                                .collect { output.append(it) }
                            Regex("""\bREADY\b""", RegexOption.IGNORE_CASE)
                                .containsMatchIn(output)
                        }
                    }

                    override fun cleanup() {
                        val active = engine ?: return
                        when (active.state.value) {
                            is InferenceEngine.State.ModelReady,
                            is InferenceEngine.State.Error,
                            -> active.cleanUp()
                            else -> Unit
                        }
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

    private suspend fun awaitInitialization(engine: InferenceEngine) {
        val current = engine.state.value
        if (
            current is InferenceEngine.State.Initialized ||
            current is InferenceEngine.State.ModelReady
        ) {
            return
        }
        val settled = engine.state.first {
            it is InferenceEngine.State.Initialized ||
                it is InferenceEngine.State.ModelReady ||
                it is InferenceEngine.State.Error
        }
        if (settled is InferenceEngine.State.Error) throw settled.exception
    }

    private const val SELF_TEST_TIMEOUT_MS = 45_000L
}
