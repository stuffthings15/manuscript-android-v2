package com.echolink.mobile

import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

fun interface CorrectionWatchdogTimeout {
    fun cancel()
}

fun interface CorrectionWatchdogScheduler {
    fun schedule(delayMs: Long, task: () -> Unit): CorrectionWatchdogTimeout
}

object SystemCorrectionWatchdogScheduler : CorrectionWatchdogScheduler {
    private val executor = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "manuscript-correction-watchdog").apply { isDaemon = true }
    }

    override fun schedule(
        delayMs: Long,
        task: () -> Unit,
    ): CorrectionWatchdogTimeout {
        val future = executor.schedule(task, delayMs, TimeUnit.MILLISECONDS)
        return CorrectionWatchdogTimeout { future.cancel(false) }
    }
}

object CorrectionTimeoutPolicy {
    private const val BASE_TIMEOUT_MS = 90_000L
    private const val PER_CHARACTER_TIMEOUT_MS = 150L
    private const val MAX_TIMEOUT_MS = 30L * 60L * 1_000L

    fun forTextLength(characterCount: Int): Long =
        (BASE_TIMEOUT_MS + characterCount.coerceAtLeast(0) * PER_CHARACTER_TIMEOUT_MS)
            .coerceAtMost(MAX_TIMEOUT_MS)
}

class CorrectionRequestWatchdog(
    timeoutMs: Long,
    private val scheduler: CorrectionWatchdogScheduler =
        SystemCorrectionWatchdogScheduler,
    private val elapsedRealtime: () -> Long = { System.nanoTime() / 1_000_000L },
    private val onTimeout: () -> Unit = {},
    private val onTerminal: (CorrectionTerminalResult) -> Unit,
) {
    private val startedAt = elapsedRealtime()
    private val terminal = AtomicReference<CorrectionTerminalResult?>()
    private val timeout = scheduler.schedule(timeoutMs) {
        val delivered = finish(
            CorrectionTerminalResult.RetryableFailure(
                userMessage = "Correction timed out. Tap Retry.",
                elapsedMs = elapsedMs(),
            ),
        )
        if (delivered) onTimeout()
    }

    fun completeBackend(
        backendResult: Result<String>,
        originalText: String,
        modelId: String,
        errorsRemain: Boolean = false,
    ) {
        finish(
            CorrectionTerminalResolver.resolve(
                backendResult = backendResult,
                originalText = originalText,
                modelId = modelId,
                elapsedMs = elapsedMs(),
                errorsRemain = errorsRemain,
            ),
        )
    }

    fun complete(result: CorrectionTerminalResult) {
        finish(result)
    }

    fun startBackend(
        originalText: String,
        modelId: String,
        errorsRemain: Boolean,
        start: () -> LocalPolishRequest,
    ): LocalPolishRequest = try {
        val backendRequest = start()
        LocalPolishRequest {
            var primaryFailure: Throwable? = null
            try {
                backendRequest.cancel()
            } catch (failure: Throwable) {
                primaryFailure = failure
            }
            try {
                cancel()
            } catch (terminalFailure: Throwable) {
                if (primaryFailure == null) {
                    primaryFailure = terminalFailure
                } else if (primaryFailure !== terminalFailure) {
                    primaryFailure.addSuppressed(terminalFailure)
                }
            }
            primaryFailure?.let { throw it }
        }
    } catch (failure: Exception) {
        completeBackend(
            backendResult = Result.failure(failure),
            originalText = originalText,
            modelId = modelId,
            errorsRemain = errorsRemain,
        )
        LocalPolishRequest {}
    }

    fun cancel() {
        finish(
            CorrectionTerminalResult.RetryableFailure(
                userMessage = "Correction canceled. Tap Retry.",
                elapsedMs = elapsedMs(),
            ),
        )
    }

    fun terminalResult(): CorrectionTerminalResult? = terminal.get()

    fun elapsedMs(): Long = (elapsedRealtime() - startedAt).coerceAtLeast(0L)

    fun close() {
        timeout.cancel()
    }

    private fun finish(result: CorrectionTerminalResult): Boolean {
        if (!terminal.compareAndSet(null, result)) return false
        timeout.cancel()
        onTerminal(result)
        return true
    }
}
