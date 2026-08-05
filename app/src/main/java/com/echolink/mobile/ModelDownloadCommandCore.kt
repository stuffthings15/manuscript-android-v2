package com.echolink.mobile

internal interface ModelDownloadCommandServices {
    fun ensure(target: DownloadTargetKey): DownloadAttempt

    fun terminalAttempt(target: DownloadTargetKey): DownloadAttempt?

    fun activeAttempt(target: DownloadTargetKey): DownloadAttempt?

    fun retry(attempt: DownloadAttempt): DownloadAttempt?

    fun cancel(attempt: DownloadAttempt): Boolean
}

internal class ModelDownloadCommandCore(
    private val services: ModelDownloadCommandServices,
) {
    fun ensure(target: DownloadTargetKey): DownloadAttempt = services.ensure(target)

    fun retryTerminal(target: DownloadTargetKey): DownloadAttempt? {
        val terminal = services.terminalAttempt(target) ?: return null
        return services.retry(terminal)
    }

    fun cancelActiveBeforeRemoval(targets: Iterable<DownloadTargetKey>): Boolean {
        targets.distinct().forEach { target ->
            val active = services.activeAttempt(target) ?: return@forEach
            if (!services.cancel(active)) return false
        }
        return true
    }
}
