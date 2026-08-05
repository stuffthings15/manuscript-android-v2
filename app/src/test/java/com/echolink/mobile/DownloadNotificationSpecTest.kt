package com.echolink.mobile

import org.junit.Assert.assertEquals
import org.junit.Test

class DownloadNotificationSpecTest {
    @Test
    fun downloadingNotificationRetainsEveryTruthfulPresentationFieldAndAction() {
        var now = 1_000L
        val calculator = DownloadTelemetryCalculator(clock = { now })
        val first = calculator.record(
            observation(downloadedBytes = 100L),
        )
        now = 3_000L
        val presentation = DownloadTelemetryPresentation.from(
            calculator.record(
                observation(downloadedBytes = 500L),
                first,
            ),
        )

        val spec = DownloadNotificationSpec.from(presentation)

        assertEquals("Downloading AI", spec.title)
        assertEquals("500 of 1,000 bytes", spec.progressText)
        assertEquals(50, spec.percent)
        assertEquals("Wi-Fi", spec.networkText)
        assertEquals("200 bytes/s", spec.speedText)
        assertEquals("3 seconds remaining", spec.etaText)
        assertEquals(listOf(DownloadAction.CANCEL), spec.actions)
    }

    private fun observation(downloadedBytes: Long): DownloadObservation = DownloadObservation(
        generation = DownloadGeneration(7L),
        phase = DownloadPhase.DOWNLOADING,
        downloadedBytes = downloadedBytes,
        totalBytes = 1_000L,
        network = DownloadNetwork.WIFI,
        cancellationAllowed = true,
    )
}
