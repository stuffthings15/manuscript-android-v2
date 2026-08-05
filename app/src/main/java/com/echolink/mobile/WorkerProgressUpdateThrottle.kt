package com.echolink.mobile

internal data class WorkerProgressUpdate(
    val downloadedBytes: Long,
    val totalBytes: Long,
    val percent: Int,
    val terminal: Boolean,
)

internal class WorkerProgressUpdateThrottle(
    private val minimumIntervalMillis: Long = MINIMUM_INTERVAL_MILLIS,
) {
    private var lastPublishedPercent: Int? = null
    private var lastPublishedAtMillis: Long? = null

    init {
        require(minimumIntervalMillis > 0L) { "Worker progress interval must be positive." }
    }

    @Synchronized
    fun next(
        downloadedBytes: Long,
        totalBytes: Long,
        nowMillis: Long,
    ): WorkerProgressUpdate? {
        require(downloadedBytes >= 0L) { "Worker progress bytes cannot be negative." }
        require(nowMillis >= 0L) { "Worker progress time cannot be negative." }

        val percent = if (totalBytes <= 0L) {
            0
        } else {
            ((downloadedBytes.coerceAtMost(totalBytes) * 100L) / totalBytes)
                .toInt()
                .coerceIn(0, 100)
        }
        val terminal = totalBytes > 0L && downloadedBytes >= totalBytes
        val previousTime = lastPublishedAtMillis
        val intervalElapsed = previousTime == null ||
            nowMillis < previousTime ||
            nowMillis - previousTime >= minimumIntervalMillis
        val shouldPublish = lastPublishedPercent == null ||
            percent != lastPublishedPercent ||
            intervalElapsed ||
            terminal
        if (!shouldPublish) return null

        lastPublishedPercent = percent
        lastPublishedAtMillis = nowMillis
        return WorkerProgressUpdate(
            downloadedBytes = downloadedBytes,
            totalBytes = totalBytes,
            percent = percent,
            terminal = terminal,
        )
    }

    private companion object {
        const val MINIMUM_INTERVAL_MILLIS = 1_000L
    }
}
