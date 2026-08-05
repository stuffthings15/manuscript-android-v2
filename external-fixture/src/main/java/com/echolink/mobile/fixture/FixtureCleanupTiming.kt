package com.echolink.mobile.fixture

object FixtureCleanupTiming {
    const val REMOVAL_GATE_MS = 300L
    const val STABLE_ABSENCE_MS = 750L
    private const val SAMPLE_MS = 50L

    fun awaitRemovalThenStableAbsence(
        isOverlayPresent: () -> Boolean,
        nowMs: () -> Long,
        pauseMs: (Long) -> Unit,
        removalGateMs: Long = REMOVAL_GATE_MS,
    ): Boolean {
        require(removalGateMs >= 0L)
        val removalDeadline = nowMs() + removalGateMs
        while (isOverlayPresent() && nowMs() < removalDeadline) pauseMs(SAMPLE_MS)
        if (isOverlayPresent()) return false

        val stableDeadline = nowMs() + STABLE_ABSENCE_MS
        while (nowMs() < stableDeadline) {
            if (isOverlayPresent()) return false
            pauseMs(SAMPLE_MS)
        }
        return !isOverlayPresent()
    }
}
