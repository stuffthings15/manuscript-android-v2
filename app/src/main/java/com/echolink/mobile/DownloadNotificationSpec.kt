package com.echolink.mobile

import java.util.Collections

internal class DownloadNotificationSpec private constructor(
    val title: String,
    val detailText: String,
    val progressText: String?,
    val networkText: String,
    val speedText: String?,
    val etaText: String?,
    val percent: Int?,
    actions: List<DownloadAction>,
) {
    val actions: List<DownloadAction> = Collections.unmodifiableList(ArrayList(actions))

    companion object {
        fun from(presentation: DownloadTelemetryPresentation): DownloadNotificationSpec =
            DownloadNotificationSpec(
                title = presentation.statusText,
                detailText = presentation.detailText,
                progressText = presentation.progressText,
                networkText = presentation.networkText,
                speedText = presentation.speedText,
                etaText = presentation.etaText,
                percent = presentation.percent,
                actions = presentation.actions,
            )
    }
}

internal data class DownloadWorkerNotificationState(
    val presentation: DownloadTelemetryPresentation,
    val attempt: DownloadAttempt,
) {
    companion object {
        fun from(
            snapshot: DownloadUiSnapshot,
            attempt: DownloadAttempt,
        ): DownloadWorkerNotificationState? {
            if (snapshot.telemetry.generation != attempt.generation) return null
            if (snapshot.target != attempt.target) return null
            val presentation = DownloadTelemetryPresentation.from(snapshot.telemetry)
            if (presentation.actions.isNotEmpty() && snapshot.actionableAttempt != attempt) return null
            return DownloadWorkerNotificationState(
                presentation,
                attempt,
            )
        }
    }
}
