package com.echolink.mobile

import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Owns a backend request that may be created after the UI has already closed. */
class DeferredLocalPolishRequest : LocalPolishRequest {
    private val cancelled = AtomicBoolean(false)
    private val cancelDelivered = AtomicBoolean(false)
    private val attached = AtomicReference<LocalPolishRequest?>()

    val isCancelled: Boolean
        get() = cancelled.get()

    fun attach(request: LocalPolishRequest) {
        check(attached.compareAndSet(null, request)) { "A deferred request can only attach once." }
        cancelAttachedIfNeeded()
    }

    override fun cancel() {
        cancelled.set(true)
        cancelAttachedIfNeeded()
    }

    private fun cancelAttachedIfNeeded() {
        if (!cancelled.get()) return
        val request = attached.get() ?: return
        if (cancelDelivered.compareAndSet(false, true)) request.cancel()
    }
}

/** Starts review backend setup away from Android's input and rendering thread. */
class ReviewRequestStarter(
    threadName: String = "manuscript-review-start",
    private val executor: ExecutorService = singleDaemonExecutor(threadName),
) : AutoCloseable {
    private val closed = AtomicBoolean(false)

    val isClosed: Boolean
        get() = closed.get()

    fun execute(work: () -> Unit): Boolean {
        if (closed.get()) return false
        return try {
            executor.execute {
                if (!closed.get()) work()
            }
            true
        } catch (_: RejectedExecutionException) {
            false
        }
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) executor.shutdownNow()
    }
}

/** Drains request ownership now, then performs backend cancellation off the UI thread. */
class ReviewCancellationDispatcher(
    threadName: String = "manuscript-review-cancel",
    private val executor: ExecutorService = singleDaemonExecutor(threadName),
    private val onFailure: (Throwable) -> Unit = {},
) : AutoCloseable {
    private val closed = AtomicBoolean(false)

    val isClosed: Boolean
        get() = closed.get()

    fun cancel(requests: Collection<LocalPolishRequest>): Boolean {
        if (requests.isEmpty()) return true
        if (closed.get()) return false
        return try {
            executor.execute {
                var firstFailure: Throwable? = null
                requests.forEach { request ->
                    try {
                        request.cancel()
                    } catch (failure: Throwable) {
                        if (firstFailure == null) {
                            firstFailure = failure
                        } else if (firstFailure !== failure) {
                            firstFailure?.addSuppressed(failure)
                        }
                    }
                }
                firstFailure?.let(onFailure)
            }
            true
        } catch (_: RejectedExecutionException) {
            false
        }
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) executor.shutdown()
    }
}

private fun singleDaemonExecutor(threadName: String): ExecutorService =
    Executors.newSingleThreadExecutor { task ->
        Thread(task, threadName).apply {
            isDaemon = true
            priority = Thread.MIN_PRIORITY
        }
    }
