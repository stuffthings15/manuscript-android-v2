package com.echolink.mobile.fixture

import android.graphics.Bitmap
import android.graphics.Rect
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.os.Build
import java.io.FileInputStream

object FixtureRenderedVideoProbe {
    data class Observation(
        val matched: Boolean,
        val contractProfile: String,
        val ceilingMs: Long,
        val transitionAtMs: Long?,
        val triggerAtMs: Long?,
        val triggerDelayMs: Long?,
        val encodedFrameCount: Int,
    )

    fun analyze(
        path: String,
        triggerRegion: Rect,
        fixtureReference: Bitmap,
        fixtureReferenceRegion: Rect,
        ignoreBeforeMs: Long,
        contract: FixtureRenderedReturnContract.Selection,
    ): Observation {
        val retriever = MediaMetadataRetriever()
        val input = FileInputStream(path)
        return try {
            retriever.setDataSource(input.fd)
            val sampleTimesUs = videoSampleTimes(path)
            var transitionAtMs: Long? = null
            var triggerAtMs: Long? = null
            val decoded = forEachDecodedFrame(retriever, sampleTimesUs) { frameIndex, frame ->
                val timestampUs = sampleTimesUs[frameIndex]
                val timestampMs = timestampUs / 1_000L
                if (
                    transitionAtMs == null && timestampMs >= ignoreBeforeMs &&
                    fixtureReferenceMatches(fixtureReference, frame, fixtureReferenceRegion)
                ) {
                    transitionAtMs = timestampMs
                }
                val transition = transitionAtMs
                if (transition != null && timestampMs >= transition && triggerPresent(frame, triggerRegion)) {
                    triggerAtMs = timestampMs
                }
                triggerAtMs == null
            }
            if (!decoded) {
                transitionAtMs = null
                triggerAtMs = null
            }
            val transition = transitionAtMs
            val trigger = triggerAtMs
            val delay = if (transition != null && trigger != null) trigger - transition else null
            Observation(
                contract.accepts(transitionAtMs, triggerAtMs),
                contract.profile.reportName,
                contract.ceilingMs,
                transitionAtMs,
                triggerAtMs,
                delay,
                sampleTimesUs.size,
            )
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

    private fun forEachDecodedFrame(
        retriever: MediaMetadataRetriever,
        sampleTimesUs: List<Long>,
        action: (frameIndex: Int, frame: Bitmap) -> Boolean,
    ): Boolean {
        if (sampleTimesUs.isEmpty()) return false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            for (startIndex in sampleTimesUs.indices step DECODE_BATCH_SIZE) {
                val count = minOf(DECODE_BATCH_SIZE, sampleTimesUs.size - startIndex)
                val frames = try {
                    retriever.getFramesAtIndex(startIndex, count)
                } catch (_: RuntimeException) {
                    return false
                }
                if (frames.size != count) {
                    frames.forEach(Bitmap::recycle)
                    return false
                }
                var continueDecoding = true
                try {
                    for ((offset, frame) in frames.withIndex()) {
                        if (!action(startIndex + offset, frame)) {
                            continueDecoding = false
                            break
                        }
                    }
                } finally {
                    frames.forEach(Bitmap::recycle)
                }
                if (!continueDecoding) return true
            }
            return true
        }
        for ((frameIndex, timestampUs) in sampleTimesUs.withIndex()) {
            val frame = retriever.getFrameAtTime(timestampUs, MediaMetadataRetriever.OPTION_CLOSEST)
                ?: return false
            val continueDecoding = try { action(frameIndex, frame) } finally { frame.recycle() }
            if (!continueDecoding) return true
        }
        return true
    }

    private fun triggerPresent(frame: Bitmap, region: Rect): Boolean {
        if (!region.validFor(frame)) return false
        val pixels = IntArray(region.width() * region.height())
        frame.getPixels(pixels, 0, region.width(), region.left, region.top, region.width(), region.height())
        return FixtureRenderedTriggerProbe.evaluate(pixels, region.width(), region.height()).present
    }

    private fun fixtureReferenceMatches(reference: Bitmap, frame: Bitmap, region: Rect): Boolean {
        if (!region.validFor(frame) || reference.width != region.width() || reference.height != region.height()) return false
        val first = IntArray(reference.width * reference.height)
        val second = IntArray(first.size)
        reference.getPixels(first, 0, reference.width, 0, 0, reference.width, reference.height)
        frame.getPixels(second, 0, region.width(), region.left, region.top, region.width(), region.height())
        val different = first.indices.count { index ->
            val left = first[index]
            val right = second[index]
            kotlin.math.abs((left ushr 16 and 0xff) - (right ushr 16 and 0xff)) >= CHANNEL_DELTA ||
                kotlin.math.abs((left ushr 8 and 0xff) - (right ushr 8 and 0xff)) >= CHANNEL_DELTA ||
                kotlin.math.abs((left and 0xff) - (right and 0xff)) >= CHANNEL_DELTA
        }
        return different.toDouble() / first.size <= FIXTURE_DIFFERENCE_RATIO
    }

    private fun Rect.validFor(bitmap: Bitmap): Boolean =
        !isEmpty && left >= 0 && top >= 0 && right <= bitmap.width && bottom <= bitmap.height

    private const val CHANNEL_DELTA = 32
    private const val FIXTURE_DIFFERENCE_RATIO = 0.10
    private const val DECODE_BATCH_SIZE = 3
}
