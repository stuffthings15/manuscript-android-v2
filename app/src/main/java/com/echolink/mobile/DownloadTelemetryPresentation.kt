package com.echolink.mobile

import java.util.Collections
import java.util.Locale

class DownloadTelemetryPresentation private constructor(
    val statusText: String,
    val detailText: String,
    val progressText: String?,
    val networkText: String,
    val speedText: String?,
    val etaText: String?,
    val percent: Int?,
    val actions: List<DownloadAction>,
) {
    companion object {
        fun uiFailure(): DownloadTelemetryPresentation = DownloadTelemetryPresentation(
            statusText = "AI download failed",
            detailText = "The AI download status could not be loaded. Retry when ready.",
            progressText = null,
            networkText = "Network not detected",
            speedText = null,
            etaText = null,
            percent = null,
            actions = listOf(DownloadAction.RETRY),
        )

        fun from(telemetry: DownloadTelemetry): DownloadTelemetryPresentation {
            val progressText = if (telemetry.phase == DownloadPhase.DOWNLOADING) {
                formatProgress(telemetry.downloadedBytes, telemetry.totalBytes)
            } else {
                null
            }
            val speedText = telemetry.bytesPerSecond?.let {
                "${formatNumber(it)} ${byteUnit(it)}/s"
            }
            val etaText = if (telemetry.phase == DownloadPhase.DOWNLOADING) {
                telemetry.etaSeconds?.let {
                    "$it ${if (it == 1L) "second" else "seconds"} remaining"
                } ?: "Calculating ETA"
            } else {
                null
            }
            val networkText = telemetry.network.userLabel
            val detailText = when (telemetry.phase) {
                DownloadPhase.PREPARING -> "Preparing the AI download."
                DownloadPhase.DOWNLOADING -> buildList {
                    add(requireNotNull(progressText))
                    telemetry.percent?.let { add("$it%") }
                    add(networkText)
                    speedText?.let(::add)
                    add(requireNotNull(etaText))
                }.joinToString(" | ")
                DownloadPhase.VERIFYING -> "Checking the downloaded AI before use."
                DownloadPhase.READY -> "The AI download passed verification and is ready."
                DownloadPhase.WAITING,
                DownloadPhase.PAUSED,
                -> requireNotNull(telemetry.blockReason).userMessage
                DownloadPhase.FAILED -> requireNotNull(telemetry.failure).userMessage
            }
            val statusText = when (telemetry.phase) {
                DownloadPhase.PREPARING -> "Preparing AI download"
                DownloadPhase.DOWNLOADING -> "Downloading AI"
                DownloadPhase.VERIFYING -> "Verifying AI"
                DownloadPhase.READY -> "AI ready"
                DownloadPhase.WAITING -> "Download waiting"
                DownloadPhase.PAUSED -> "Download paused"
                DownloadPhase.FAILED -> "Download failed"
            }
            val actions = if (
                telemetry.phase == DownloadPhase.WAITING &&
                telemetry.blockReason == DownloadBlockReason.WIFI_REQUIRED
            ) {
                listOf(DownloadAction.RETRY, DownloadAction.CANCEL)
            } else {
                buildList {
                    if (telemetry.canRetry) add(DownloadAction.RETRY)
                    if (telemetry.canCancel) add(DownloadAction.CANCEL)
                }
            }

            return DownloadTelemetryPresentation(
                statusText = statusText,
                detailText = detailText,
                progressText = progressText,
                networkText = networkText,
                speedText = speedText,
                etaText = etaText,
                percent = telemetry.percent,
                actions = Collections.unmodifiableList(ArrayList(actions)),
            )
        }

        private fun formatProgress(downloadedBytes: Long, totalBytes: Long?): String =
            if (totalBytes == null) {
                "${formatNumber(downloadedBytes)} ${byteUnit(downloadedBytes)} downloaded"
            } else {
                "${formatNumber(downloadedBytes)} of ${formatNumber(totalBytes)} ${byteUnit(totalBytes)}"
            }

        private fun formatNumber(value: Long): String =
            String.format(Locale.US, "%,d", value)

        private fun byteUnit(value: Long): String = if (value == 1L) "byte" else "bytes"
    }

    override fun equals(other: Any?): Boolean =
        this === other || other is DownloadTelemetryPresentation &&
            statusText == other.statusText &&
            detailText == other.detailText &&
            progressText == other.progressText &&
            networkText == other.networkText &&
            speedText == other.speedText &&
            etaText == other.etaText &&
            percent == other.percent &&
            actions == other.actions

    override fun hashCode(): Int {
        var result = statusText.hashCode()
        result = 31 * result + detailText.hashCode()
        result = 31 * result + (progressText?.hashCode() ?: 0)
        result = 31 * result + networkText.hashCode()
        result = 31 * result + (speedText?.hashCode() ?: 0)
        result = 31 * result + (etaText?.hashCode() ?: 0)
        result = 31 * result + (percent ?: 0)
        result = 31 * result + actions.hashCode()
        return result
    }
}

private val DownloadNetwork.userLabel: String
    get() = when (this) {
        DownloadNetwork.UNKNOWN -> "Network not detected"
        DownloadNetwork.OFFLINE -> "Offline"
        DownloadNetwork.WIFI -> "Wi-Fi"
        DownloadNetwork.MOBILE -> "Mobile data"
        DownloadNetwork.ETHERNET -> "Ethernet"
        DownloadNetwork.OTHER -> "Connected network"
    }
