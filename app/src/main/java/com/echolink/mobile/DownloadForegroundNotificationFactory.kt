package com.echolink.mobile

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import androidx.annotation.DrawableRes
import androidx.core.app.NotificationCompat

internal data class DownloadNotificationActionIntents(
    val retry: PendingIntent?,
    val cancel: PendingIntent?,
) {
    fun forAction(action: DownloadAction): PendingIntent = when (action) {
        DownloadAction.RETRY -> requireNotNull(retry) {
            "A Retry presentation requires an exact Retry intent."
        }
        DownloadAction.CANCEL -> requireNotNull(cancel) {
            "A Cancel presentation requires an exact Cancel intent."
        }
    }
}

internal object DownloadForegroundNotificationFactory {
    fun build(
        context: Context,
        channelId: String,
        @DrawableRes smallIcon: Int,
        presentation: DownloadTelemetryPresentation,
        actionIntents: DownloadNotificationActionIntents,
    ): Notification {
        val spec = DownloadNotificationSpec.from(presentation)
        val expandedText = listOfNotNull(
            spec.detailText,
            spec.progressText,
            spec.percent?.let { "$it%" },
            spec.networkText,
            spec.speedText,
            spec.etaText,
        ).distinct().joinToString("\n")
        val builder = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(smallIcon)
            .setContentTitle(spec.title)
            .setContentText(spec.detailText)
            .setStyle(NotificationCompat.BigTextStyle().bigText(expandedText))
            .setOnlyAlertOnce(true)
            .setOngoing(DownloadAction.CANCEL in spec.actions)
        if (spec.progressText != null) {
            builder.setProgress(100, spec.percent ?: 0, spec.percent == null)
        }
        spec.actions.forEach { action ->
            builder.addAction(
                0,
                context.getString(
                    when (action) {
                        DownloadAction.RETRY -> R.string.qwen_model_retry_action
                        DownloadAction.CANCEL -> R.string.qwen_download_cancel
                    },
                ),
                actionIntents.forAction(action),
            )
        }
        return builder.build()
    }
}
