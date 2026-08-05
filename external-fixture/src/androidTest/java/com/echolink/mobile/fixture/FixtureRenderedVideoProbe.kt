package com.echolink.mobile.fixture

import android.graphics.Bitmap
import android.graphics.Rect
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import java.io.FileInputStream

object FixtureRenderedVideoProbe {
    data class Observation(
        val matched: Boolean,
        val transitionAtMs: Long?,
        val triggerAtMs: Long?,
        val triggerDelayMs: Long?,
        val encodedFrameCount: Int,
    )

    fun analyze(
        path: String,
        triggerRegion: Rect,
        transitionRegion: Rect,
        ignoreBeforeMs: Long,
    ): Observation {
        val retriever = MediaMetadataRetriever()
        val input = FileInputStream(path)
        return try {
            retriever.setDataSource(input.fd)
            val sampleTimesUs = videoSampleTimes(path)
            val baseline = sampleTimesUs.firstOrNull()?.let {
                retriever.getFrameAtTime(it, MediaMetadataRetriever.OPTION_CLOSEST)
            } ?: return Observation(false, null, null, null, sampleTimesUs.size)
            try {
                var transitionAtMs: Long? = null
                var triggerAtMs: Long? = null
                for (timestampUs in sampleTimesUs) {
                    val timestampMs = timestampUs / 1_000L
                    val frame = retriever.getFrameAtTime(timestampUs, MediaMetadataRetriever.OPTION_CLOSEST)
                    if (frame != null) {
                        try {
                            if (
                                transitionAtMs == null && timestampMs >= ignoreBeforeMs &&
                                changedPixelRatio(baseline, frame, transitionRegion) >= TRANSITION_CHANGED_RATIO
                            ) {
                                transitionAtMs = timestampMs
                            }
                            if (transitionAtMs != null && timestampMs >= transitionAtMs && triggerPresent(frame, triggerRegion)) {
                                triggerAtMs = timestampMs
                                break
                            }
                        } finally {
                            frame.recycle()
                        }
                    }
                }
                val delay = if (transitionAtMs != null && triggerAtMs != null) triggerAtMs - transitionAtMs else null
                Observation(delay != null && delay in 0L..500L, transitionAtMs, triggerAtMs, delay, sampleTimesUs.size)
            } finally {
                baseline.recycle()
            }
        } finally {
            retriever.release()
            input.close()
        }
    }

    private fun videoSampleTimes(path: String): List<Long> {
        val extractor = MediaExtractor()
        val input = FileInputStream(path)
        return try {
            extractor.setDataSource(input.fd)
            val track = (0 until extractor.trackCount).firstOrNull { index ->
                extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true
            } ?: return emptyList()
            extractor.selectTrack(track)
            buildList {
                while (extractor.sampleTime >= 0L) {
                    add(extractor.sampleTime)
                    if (!extractor.advance()) break
                }
            }
        } finally {
            extractor.release()
            input.close()
        }
    }

    private fun triggerPresent(frame: Bitmap, region: Rect): Boolean {
        if (!region.validFor(frame)) return false
        val pixels = IntArray(region.width() * region.height())
        frame.getPixels(pixels, 0, region.width(), region.left, region.top, region.width(), region.height())
        return FixtureRenderedTriggerProbe.evaluate(pixels, region.width(), region.height()).present
    }

    private fun changedPixelRatio(baseline: Bitmap, frame: Bitmap, region: Rect): Double {
        if (!region.validFor(baseline) || !region.validFor(frame)) return 0.0
        val first = IntArray(region.width() * region.height())
        val second = IntArray(first.size)
        baseline.getPixels(first, 0, region.width(), region.left, region.top, region.width(), region.height())
        frame.getPixels(second, 0, region.width(), region.left, region.top, region.width(), region.height())
        val changed = first.indices.count { index ->
            val left = first[index]
            val right = second[index]
            kotlin.math.abs((left ushr 16 and 0xff) - (right ushr 16 and 0xff)) >= CHANNEL_DELTA ||
                kotlin.math.abs((left ushr 8 and 0xff) - (right ushr 8 and 0xff)) >= CHANNEL_DELTA ||
                kotlin.math.abs((left and 0xff) - (right and 0xff)) >= CHANNEL_DELTA
        }
        return changed.toDouble() / first.size
    }

    private fun Rect.validFor(bitmap: Bitmap): Boolean =
        !isEmpty && left >= 0 && top >= 0 && right <= bitmap.width && bottom <= bitmap.height

    private const val CHANNEL_DELTA = 32
    private const val TRANSITION_CHANGED_RATIO = 0.20
}
