package com.echolink.mobile

import android.content.Context
import com.arm.aichat.AiChat
import com.arm.aichat.InferenceEngine
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File

/**
 * Qualifies a downloaded file with a real local load and inference before the
 * model can replace the last known-good tier.
 */
object Qwen3ModelSelfTester {
    fun verify(context: Context, model: File): Boolean =
        synchronized(LocalInferenceCoordinator.lock) {
            runCatching {
                val engine = AiChat.getInferenceEngine(context.applicationContext)
                runBlocking {
                    withTimeout(SELF_TEST_TIMEOUT_MS) {
                        try {
                            awaitInitialization(engine)
                            when (engine.state.value) {
                                is InferenceEngine.State.ModelReady,
                                is InferenceEngine.State.Error,
                                -> engine.cleanUp()
                                else -> Unit
                            }
                            val contextSize = if (model.name.contains("0.6B")) 512 else 1_024
                            val threads = Runtime.getRuntime().availableProcessors().coerceIn(2, 4)
                            engine.loadModel(model.absolutePath, contextSize, threads)
                            engine.setSystemPrompt("Reply with exactly READY.")
                            val output = StringBuilder()
                            engine.sendUserPrompt("/no_think\nReply with exactly READY.", 8)
                                .collect { output.append(it) }
                            require(
                                Regex("""\bREADY\b""", RegexOption.IGNORE_CASE)
                                    .containsMatchIn(output),
                            ) {
                                "The downloaded AI failed its readiness response."
                            }
                        } finally {
                            when (engine.state.value) {
                                is InferenceEngine.State.ModelReady,
                                is InferenceEngine.State.Error,
                                -> engine.cleanUp()
                                else -> Unit
                            }
                        }
                    }
                }
            }.isSuccess
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
