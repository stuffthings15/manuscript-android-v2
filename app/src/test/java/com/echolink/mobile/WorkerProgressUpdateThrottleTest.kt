package com.echolink.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class WorkerProgressUpdateThrottleTest {
    @Test
    fun firstUpdatePublishesExactProgress() {
        val throttle = WorkerProgressUpdateThrottle()

        val update = throttle.next(
            downloadedBytes = 125L,
            totalBytes = 1_000L,
            nowMillis = 20L,
        )

        assertEquals(
            WorkerProgressUpdate(
                downloadedBytes = 125L,
                totalBytes = 1_000L,
                percent = 12,
                terminal = false,
            ),
            update,
        )
    }

    @Test
    fun unchangedPercentWaitsForOneFullSecond() {
        val throttle = WorkerProgressUpdateThrottle()
        assertNotNull(throttle.next(100L, 1_000L, nowMillis = 5_000L))

        assertNull(throttle.next(109L, 1_000L, nowMillis = 5_999L))
        assertEquals(
            109L,
            throttle.next(109L, 1_000L, nowMillis = 6_000L)?.downloadedBytes,
        )
    }

    @Test
    fun changedPercentPublishesBeforeOneSecond() {
        val throttle = WorkerProgressUpdateThrottle()
        assertNotNull(throttle.next(100L, 1_000L, nowMillis = 1_000L))

        val update = throttle.next(110L, 1_000L, nowMillis = 1_001L)

        assertEquals(11, update?.percent)
    }

    @Test
    fun terminalUpdateIsNeverSuppressedByPercentOrTime() {
        val throttle = WorkerProgressUpdateThrottle()
        assertNotNull(throttle.next(1_000L, 1_000L, nowMillis = 8_000L))

        val terminal = throttle.next(1_000L, 1_000L, nowMillis = 8_001L)

        assertEquals(true, terminal?.terminal)
        assertEquals(100, terminal?.percent)
    }
}
