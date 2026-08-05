package com.echolink.mobile

/**
 * Separates logical lifecycle completion from physical window removal.
 * A stalled platform detach cannot strand the controller, while the original
 * surface ownership remains available for later cleanup.
 */
class TerminalDetachLifecycleCoordinator<T : Any>(
    private val scheduler: DelayedWorkScheduler,
    private val retryDelayMillis: Long,
    private val logicalCompletionDeadlineMillis: Long,
    private val postCompletionRetryDelayMillis: Long,
    private val rapidRetryAttempts: Int,
    private val scheduledCleanupAttempts: Int,
    private val deactivate: (T) -> Unit,
    private val attemptDetach: (T) -> Boolean,
    private val onPhysicalDetach: (T) -> Unit,
) {
    private class Entry<T : Any>(
        val surface: T,
        val onLogicalCompletion: () -> Unit,
        var rapidRetriesRemaining: Int,
        var scheduledCleanupAttemptsRemaining: Int,
    ) {
        var logicalCompletionReported = false
        lateinit var logicalCompletionRunnable: Runnable
        lateinit var rapidRetryRunnable: Runnable
        lateinit var cleanupRetryRunnable: Runnable
    }

    private val entries = mutableListOf<Entry<T>>()

    val hasOwnership: Boolean
        get() = entries.isNotEmpty()

    fun owns(surface: T): Boolean = entries.any { it.surface === surface }

    fun begin(surface: T, onLogicalCompletion: () -> Unit) {
        check(!owns(surface)) { "Terminal detach surface is already owned." }
        deactivate(surface)
        val entry = Entry(
            surface = surface,
            onLogicalCompletion = onLogicalCompletion,
            rapidRetriesRemaining = rapidRetryAttempts,
            scheduledCleanupAttemptsRemaining = scheduledCleanupAttempts,
        )
        entry.logicalCompletionRunnable = Runnable { completeLogically(entry) }
        entry.rapidRetryRunnable = Runnable {
            if (!entries.contains(entry) || detach(entry)) return@Runnable
            if (entry.rapidRetriesRemaining > 0) {
                entry.rapidRetriesRemaining -= 1
                scheduler.postDelayed(entry.rapidRetryRunnable, retryDelayMillis)
            }
        }
        entry.cleanupRetryRunnable = Runnable {
            if (!entries.contains(entry) || detach(entry)) return@Runnable
            if (entry.scheduledCleanupAttemptsRemaining > 0) {
                entry.scheduledCleanupAttemptsRemaining -= 1
                scheduler.postDelayed(entry.cleanupRetryRunnable, postCompletionRetryDelayMillis)
            }
        }
        entries += entry
        scheduler.postDelayed(entry.logicalCompletionRunnable, logicalCompletionDeadlineMillis)
        if (!detach(entry) && entry.rapidRetriesRemaining > 0) {
            entry.rapidRetriesRemaining -= 1
            scheduler.postDelayed(entry.rapidRetryRunnable, retryDelayMillis)
        }
    }

    fun retryFromEvent() {
        entries.toList().forEach(::detach)
    }

    fun destroy() {
        entries.toList().forEach { entry ->
            deactivate(entry.surface)
            completeLogically(entry)
            detach(entry)
        }
    }

    private fun completeLogically(entry: Entry<T>) {
        if (!entries.contains(entry) || entry.logicalCompletionReported) return
        entry.logicalCompletionReported = true
        entry.onLogicalCompletion()
        if (entries.contains(entry) && entry.scheduledCleanupAttemptsRemaining > 0) {
            entry.scheduledCleanupAttemptsRemaining -= 1
            scheduler.postDelayed(entry.cleanupRetryRunnable, postCompletionRetryDelayMillis)
        }
    }

    private fun detach(entry: Entry<T>): Boolean {
        if (!entries.contains(entry)) return true
        val detached = runCatching { attemptDetach(entry.surface) }.getOrDefault(false)
        if (!detached) return false
        entries.remove(entry)
        scheduler.remove(entry.logicalCompletionRunnable)
        scheduler.remove(entry.rapidRetryRunnable)
        scheduler.remove(entry.cleanupRetryRunnable)
        if (!entry.logicalCompletionReported) {
            entry.logicalCompletionReported = true
            entry.onLogicalCompletion()
        }
        onPhysicalDetach(entry.surface)
        return true
    }
}
