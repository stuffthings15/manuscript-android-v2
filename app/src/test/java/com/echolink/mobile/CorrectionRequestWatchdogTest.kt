package com.echolink.mobile

import java.util.concurrent.atomic.AtomicLong
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class CorrectionRequestWatchdogTest {
    @Test
    fun validAiTextCompletesAsSuccess() {
        val harness = Harness()
        harness.watchdog.completeBackend(
            backendResult = Result.success("This is correct."),
            originalText = "this are wrong",
            modelId = "gemma-test",
        )

        val result = harness.results.single() as CorrectionTerminalResult.Success
        assertEquals("This is correct.", result.correctedText)
        assertEquals("gemma-test", result.modelId)
    }

    @Test
    fun blankOutputIsRetryableAndKeepsOriginalOutOfTheResult() {
        val harness = Harness()
        harness.watchdog.completeBackend(
            backendResult = Result.success("   "),
            originalText = "Keep this original.",
            modelId = "gemma-test",
        )

        assertTrue(harness.results.single() is CorrectionTerminalResult.RetryableFailure)
    }

    @Test
    fun unchangedOutputWithKnownErrorsIsRetryable() {
        val harness = Harness()
        harness.watchdog.completeBackend(
            backendResult = Result.success("This are wrong."),
            originalText = "This are wrong.",
            modelId = "gemma-test",
            errorsRemain = true,
        )

        assertTrue(harness.results.single() is CorrectionTerminalResult.RetryableFailure)
    }

    @Test
    fun emDashOutputCannotBecomeATerminalSuccess() {
        val harness = Harness()
        harness.watchdog.completeBackend(
            backendResult = Result.success("This is corrected—but forbidden."),
            originalText = "This are wrong.",
            modelId = "gemma-test",
        )

        assertTrue(harness.results.single() is CorrectionTerminalResult.RetryableFailure)
    }

    @Test
    fun modelRequiredHasItsOwnTerminalState() {
        val harness = Harness()
        harness.watchdog.completeBackend(
            backendResult = Result.failure(LocalModelRequiredException()),
            originalText = "Text",
            modelId = "none",
        )

        assertEquals(CorrectionTerminalResult.ModelRequired, harness.results.single())
    }

    @Test
    fun thrownBackendAndAllBackendsFailEndWithRetry() {
        val harness = Harness()
        harness.watchdog.completeBackend(
            backendResult = Result.failure(IllegalStateException("all failed")),
            originalText = "Text",
            modelId = "gemma-test",
        )

        assertTrue(harness.results.single() is CorrectionTerminalResult.RetryableFailure)
    }

    @Test
    fun fallbackSuccessIsStillAValidatedSuccess() {
        val harness = Harness()
        harness.watchdog.completeBackend(
            backendResult = Result.success("Fallback corrected text."),
            originalText = "fallback bad text",
            modelId = "qwen-fallback",
        )

        assertTrue(harness.results.single() is CorrectionTerminalResult.Success)
    }

    @Test
    fun missingCallbackTimesOutToRetryAndCancelsBackend() {
        var backendCancelled = false
        val harness = Harness(onTimeout = { backendCancelled = true })
        harness.clock.addAndGet(1_500)
        harness.scheduler.fire()

        val result = harness.results.single() as CorrectionTerminalResult.RetryableFailure
        assertTrue(result.userMessage.contains("Retry"))
        assertTrue(backendCancelled)
    }

    @Test
    fun userCancellationEndsWithRetry() {
        val harness = Harness()
        harness.clock.addAndGet(250)
        harness.watchdog.cancel()

        val result = harness.results.single() as CorrectionTerminalResult.RetryableFailure
        assertTrue(result.userMessage.contains("Retry"))
        assertEquals(250L, result.elapsedMs)
    }

    @Test
    fun twoBackendCallbacksDeliverExactlyOneTerminalResult() {
        val harness = Harness()
        repeat(2) {
            harness.watchdog.completeBackend(
                backendResult = Result.success("Corrected."),
                originalText = "Bad.",
                modelId = "gemma-test",
            )
        }

        assertEquals(1, harness.results.size)
    }

    @Test
    fun synchronousBackendStartExceptionEndsWithOneRetryableTerminalResult() {
        val harness = Harness()

        val request = harness.watchdog.startBackend(
            originalText = "This are wrong.",
            modelId = "gemma-test",
            errorsRemain = true,
        ) {
            throw IllegalStateException("backend could not start")
        }
        request.cancel()

        assertEquals(1, harness.results.size)
        assertTrue(harness.results.single() is CorrectionTerminalResult.RetryableFailure)
    }

    @Test
    fun throwingBackendCancellationStillDeliversOneRetryAndPreservesThePrimaryFailure() {
        val backendFailure = IllegalStateException("backend cancel failed")
        val terminalFailure = IllegalStateException("terminal observer failed")
        val results = mutableListOf<CorrectionTerminalResult>()
        val watchdog = CorrectionRequestWatchdog(
            timeoutMs = 1_000,
            scheduler = ManualScheduler(),
            elapsedRealtime = { 1_000L },
            onTerminal = { result ->
                results += result
                throw terminalFailure
            },
        )
        val request = watchdog.startBackend(
            originalText = "This are wrong.",
            modelId = "gemma-test",
            errorsRemain = true,
        ) {
            LocalPolishRequest { throw backendFailure }
        }

        val thrown = try {
            request.cancel()
            error("Cancellation must preserve the backend failure.")
        } catch (failure: IllegalStateException) {
            failure
        }

        assertSame(backendFailure, thrown)
        assertEquals(listOf(terminalFailure), thrown.suppressed.toList())
        assertEquals(1, results.size)
        assertTrue(results.single() is CorrectionTerminalResult.RetryableFailure)
        assertEquals(results.single(), watchdog.terminalResult())
    }

    @Test
    fun aRestoredSurfaceCanAttachToTheSameTerminalValue() {
        val harness = Harness()
        harness.watchdog.completeBackend(
            backendResult = Result.success("Restored correction."),
            originalText = "Restore correction.",
            modelId = "gemma-test",
        )

        val restored = harness.watchdog.terminalResult()
        assertEquals(harness.results.single(), restored)
    }

    private class Harness(
        onTimeout: () -> Unit = {},
    ) {
        val clock = AtomicLong(1_000)
        val scheduler = ManualScheduler()
        val results = mutableListOf<CorrectionTerminalResult>()
        val watchdog = CorrectionRequestWatchdog(
            timeoutMs = 1_000,
            scheduler = scheduler,
            elapsedRealtime = clock::get,
            onTimeout = onTimeout,
            onTerminal = results::add,
        )
    }

    private class ManualScheduler : CorrectionWatchdogScheduler {
        private var task: (() -> Unit)? = null

        override fun schedule(delayMs: Long, task: () -> Unit): CorrectionWatchdogTimeout {
            this.task = task
            return CorrectionWatchdogTimeout { this.task = null }
        }

        fun fire() {
            task?.invoke()
        }
    }
}
