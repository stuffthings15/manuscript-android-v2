package com.echolink.mobile

import java.io.InterruptedIOException
import java.util.concurrent.CancellationException

internal interface ModelSelfTestOperations {
    fun load()

    fun generate(): Boolean

    fun cleanup()
}

internal fun runGuardedModelSelfTest(
    stopped: () -> Boolean,
    operations: ModelSelfTestOperations,
): Boolean {
    var loadAttempted = false
    var passed = false
    var failure: Throwable? = null

    try {
        throwIfModelSelfTestStopped(stopped)
        loadAttempted = true
        operations.load()
        throwIfModelSelfTestStopped(stopped)
        passed = operations.generate()
    } catch (caught: Throwable) {
        failure = caught
    } finally {
        if (loadAttempted) {
            failure = appendFailure(failure, captureFailure {
                throwIfModelSelfTestStopped(stopped)
            })
            failure = appendFailure(failure, captureFailure(operations::cleanup))
        }
    }

    return when (val caught = failure) {
        null -> passed
        is CancellationException -> throw caught
        is InterruptedIOException -> throw caught
        is Error -> throw caught
        else -> false
    }
}

internal fun throwIfModelSelfTestStopped(stopped: () -> Boolean) {
    if (stopped()) throw InterruptedIOException("AI self-test stopped.")
}

private fun captureFailure(action: () -> Unit): Throwable? = try {
    action()
    null
} catch (failure: Throwable) {
    failure
}

private fun appendFailure(
    primary: Throwable?,
    additional: Throwable?,
): Throwable? {
    if (primary == null) return additional
    if (additional == null || additional === primary) return primary

    return if (failurePriority(additional) > failurePriority(primary)) {
        additional.addSuppressed(primary)
        additional
    } else {
        primary.addSuppressed(additional)
        primary
    }
}

private fun failurePriority(failure: Throwable): Int = when (failure) {
    is Error -> 2
    is CancellationException, is InterruptedIOException -> 1
    else -> 0
}
