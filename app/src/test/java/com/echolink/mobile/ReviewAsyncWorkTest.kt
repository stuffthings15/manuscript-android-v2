package com.echolink.mobile

import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReviewAsyncWorkTest {
    @Test
    fun reviewBackendStartUsesTheCompletedDrawInsteadOfAFixedTimer() {
        val source = File(
            System.getProperty("user.dir"),
            "../app/src/main/java/com/echolink/mobile/MainActivity.kt",
        ).readText()
        val scheduling = source
            .substringAfter("private fun scheduleReviewStart()")
            .substringBefore("private fun showCorrectionOverlay()")

        assertTrue(scheduling.contains("correctionCard.post(pending)"))
        assertFalse(scheduling.contains("postDelayed"))
        assertFalse(source.contains("REVIEW_MODEL_START_AFTER_DRAW_DELAY_MS"))
    }

    @Test
    fun staleTerminalCallbackCannotHideCurrentProgress() {
        val source = File(
            System.getProperty("user.dir"),
            "../app/src/main/java/com/echolink/mobile/MainActivity.kt",
        ).readText()
        val terminalCallback = source
            .substringAfter(") { terminalResult ->")
            .substringBefore("private fun replaceCurrentBlock()")
        val staleReturn = terminalCallback.indexOf("return@polishDocumentTerminal")
        val progressMutation = terminalCallback.indexOf("hideReviewProgress()")

        assertTrue(staleReturn >= 0)
        assertTrue(progressMutation > staleReturn)
    }

    @Test
    fun deferredRequestCancelledBeforeAttachCancelsLateBackendExactlyOnce() {
        val deferred = DeferredLocalPolishRequest()
        val cancellations = AtomicInteger()

        deferred.cancel()
        deferred.attach(LocalPolishRequest { cancellations.incrementAndGet() })
        deferred.cancel()

        assertTrue(deferred.isCancelled)
        assertTrue(cancellations.get() == 1)
    }

    @Test
    fun requestStarterRunsOffCallerAndRejectsWorkAfterClose() {
        val starter = ReviewRequestStarter("review-start-test")
        val caller = Thread.currentThread().name
        val ran = CountDownLatch(1)
        var worker = caller

        assertTrue(
            starter.execute {
                worker = Thread.currentThread().name
                ran.countDown()
            },
        )
        assertTrue(ran.await(2, TimeUnit.SECONDS))
        assertTrue(worker != caller)

        starter.close()

        assertTrue(starter.isClosed)
        assertFalse(starter.execute { error("closed starter ran work") })
    }

    @Test
    fun cancellationDispatcherReturnsBeforeSlowCancelAndDrainsBeforeShutdown() {
        val dispatcher = ReviewCancellationDispatcher("review-cancel-test")
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val request = LocalPolishRequest {
            entered.countDown()
            release.await(2, TimeUnit.SECONDS)
            finished.countDown()
        }

        dispatcher.cancel(listOf(request))

        assertTrue(entered.await(2, TimeUnit.SECONDS))
        assertFalse(finished.await(50, TimeUnit.MILLISECONDS))
        dispatcher.close()
        release.countDown()
        assertTrue(finished.await(2, TimeUnit.SECONDS))
        assertTrue(dispatcher.isClosed)
    }
}
