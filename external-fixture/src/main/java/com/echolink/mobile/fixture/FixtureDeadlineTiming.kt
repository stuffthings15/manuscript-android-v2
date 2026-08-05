package com.echolink.mobile.fixture

object FixtureDeadlineTiming {
    private const val SAMPLE_INTERVAL_MS = 50L

    fun awaitCondition(
        deadlineMs: Long,
        condition: () -> Boolean,
        nowMs: () -> Long,
        pauseMs: (Long) -> Unit,
    ): Boolean {
        while (true) {
            val sampleStartedAt = nowMs()
            if (sampleStartedAt > deadlineMs) return false
            if (condition()) return true
            val sampleCompletedAt = nowMs()
            if (sampleCompletedAt >= deadlineMs) return false
            pauseMs(minOf(SAMPLE_INTERVAL_MS, deadlineMs - sampleCompletedAt))
        }
    }
}
