package com.echolink.mobile

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DownloadForegroundNotificationDeviceTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun builtDownloadingNotificationMatchesTheSharedPresentation() {
        var now = 1_000L
        val calculator = DownloadTelemetryCalculator(clock = { now })
        val first = calculator.record(observation(100L))
        now = 3_000L
        val presentation = DownloadTelemetryPresentation.from(
            calculator.record(observation(500L), first),
        )

        val notification = DownloadForegroundNotificationFactory.build(
            context = context,
            channelId = "task_3f_test",
            smallIcon = R.drawable.ic_manuscript,
            presentation = presentation,
            actionIntents = DownloadNotificationActionIntents(
                retry = pendingIntent("retry", 701),
                cancel = pendingIntent("cancel", 702),
            ),
        )

        assertEquals(presentation.statusText, notification.extras.getCharSequence(Notification.EXTRA_TITLE))
        val rendered = notification.extras.getCharSequence(Notification.EXTRA_BIG_TEXT).toString()
        listOf(
            presentation.detailText,
            requireNotNull(presentation.progressText),
            "${presentation.percent}%",
            presentation.networkText,
            requireNotNull(presentation.speedText),
            requireNotNull(presentation.etaText),
        ).forEach { field -> assertTrue("Missing built field: $field", rendered.contains(field)) }
        assertEquals(100, notification.extras.getInt(Notification.EXTRA_PROGRESS_MAX))
        assertEquals(presentation.percent, notification.extras.getInt(Notification.EXTRA_PROGRESS))
        assertEquals(false, notification.extras.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE))
        assertEquals(listOf("Cancel"), notification.actions.map { it.title.toString() })
    }

    @Test
    fun builtFailedNotificationExposesOnlyTheExactAttemptRetryAction() {
        val attempt = DownloadAttempt(
            target = DownloadTargetKey(
                AiModelFamily.GEMMA,
                AiModelTier.FAST,
                BackendKind.LITERT_CPU,
            ),
            generation = DownloadGeneration(7L),
            workIdentity = DownloadWorkIdentity(11L, 12L),
        )
        val telemetry = DownloadTelemetryCalculator(clock = { 1_000L }).record(
            DownloadObservation(
                generation = attempt.generation,
                phase = DownloadPhase.FAILED,
                downloadedBytes = 500L,
                totalBytes = 1_000L,
                network = DownloadNetwork.OFFLINE,
                failureCode = DownloadFailureCode.DOWNLOAD_INTERRUPTED,
                cancellationAllowed = false,
                retryAllowed = true,
            ),
        )
        val presentation = DownloadTelemetryPresentation.from(telemetry)

        val notification = DownloadForegroundNotificationFactory.build(
            context = context,
            channelId = "task_3f_test",
            smallIcon = R.drawable.ic_manuscript,
            presentation = presentation,
            actionIntents = DownloadNotificationActionIntents(
                retry = DownloadRetryReceiver.pendingIntent(context, attempt),
                cancel = DownloadCancelReceiver.pendingIntent(context, attempt),
            ),
        )

        assertEquals(listOf("Retry AI"), notification.actions.map { it.title.toString() })
        assertEquals(0, notification.extras.getInt(Notification.EXTRA_PROGRESS_MAX))
        assertTrue(
            requireNotNull(notification.extras.getCharSequence(Notification.EXTRA_BIG_TEXT))
                .contains(presentation.detailText),
        )
    }

    private fun pendingIntent(action: String, requestCode: Int): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            requestCode,
            Intent("com.remote.Manuscript.v2.debug.test.$action").setPackage(context.packageName),
            PendingIntent.FLAG_IMMUTABLE,
        )

    private fun observation(downloadedBytes: Long): DownloadObservation = DownloadObservation(
        generation = DownloadGeneration(7L),
        phase = DownloadPhase.DOWNLOADING,
        downloadedBytes = downloadedBytes,
        totalBytes = 1_000L,
        network = DownloadNetwork.WIFI,
        cancellationAllowed = true,
    )
}
