package com.echolink.mobile.fixture

import android.graphics.Rect
import android.media.MediaMetadataRetriever
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.FileInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FixtureRenderedVideoProbeDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val assetContext = instrumentation.context
    private val outputContext = instrumentation.targetContext
    private val emulatorContract = FixtureRenderedReturnContract.select("ranchu", null)

    @Test fun exactEe755SettingsTriggerVideoPassesProductionProbe() {
        val result = analyze("ee755-settings-trigger.mp4")

        assertTrue(result.toString(), result.matched)
        assertEquals(227L, result.transitionAtMs)
        assertEquals(1_475L, result.triggerAtMs)
        assertEquals(1_248L, result.triggerDelayMs)
        assertEquals(15, result.encodedFrameCount)
    }

    @Test fun exactEe755SettingsPanelVideoPassesProductionProbe() {
        val result = analyze("ee755-settings-panel.mp4")

        assertTrue(result.toString(), result.matched)
        assertEquals(232L, result.transitionAtMs)
        assertEquals(2_133L, result.triggerAtMs)
        assertEquals(1_901L, result.triggerDelayMs)
        assertEquals(17, result.encodedFrameCount)
    }

    @Test fun realReturnedFixtureWithoutTriggerFailsClosed() {
        val result = analyze("prewarm-no-trigger.mp4")

        assertFalse(result.matched)
        assertEquals(1_579L, result.transitionAtMs)
        assertNull(result.triggerAtMs)
        assertNull(result.triggerDelayMs)
    }

    @Test fun coloredSettingsVideoCannotImpersonateOfficialTrigger() {
        val result = analyze("settings-adversary.mp4")

        assertFalse(result.matched)
        assertNull(result.transitionAtMs)
        assertNull(result.triggerAtMs)
        assertNull(result.triggerDelayMs)
    }

    @Test fun androidBoundedIndexedBatchDecoderExposesTheExactOfficialFrame() {
        val file = copyAsset("ee755-settings-trigger.mp4")
        val retriever = MediaMetadataRetriever()
        try {
            FileInputStream(file).use { retriever.setDataSource(it.fd) }
            val frames = retriever.getFramesAtIndex(6, 3)
            try {
                assertEquals(3, frames.size)
                assertFalse(triggerPresent(frames[0]))
                assertTrue(triggerPresent(frames[1]))
            } finally {
                frames.forEach { it.recycle() }
            }
        } finally {
            retriever.release()
            file.delete()
        }
    }

    private fun analyze(assetName: String): FixtureRenderedVideoProbe.Observation {
        val file = copyAsset(assetName)
        val reference = fixtureReferenceCrop()
        return try {
            FixtureRenderedVideoProbe.analyze(
                path = file.absolutePath,
                triggerRegion = Rect(902, 136, 1_028, 262),
                fixtureReference = reference,
                fixtureReferenceRegion = FIXTURE_REFERENCE_REGION,
                ignoreBeforeMs = 100L,
                contract = emulatorContract,
            )
        } finally {
            reference.recycle()
            file.delete()
        }
    }

    private fun fixtureReferenceCrop(): android.graphics.Bitmap {
        val file = copyAsset("prewarm-no-trigger.mp4")
        val retriever = MediaMetadataRetriever()
        return try {
            FileInputStream(file).use { retriever.setDataSource(it.fd) }
            val frameCount = requireNotNull(
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT)?.toIntOrNull(),
            )
            val startIndex = maxOf(0, frameCount - 3)
            val frames = retriever.getFramesAtIndex(startIndex, frameCount - startIndex)
            try {
                val source = frames.last()
                android.graphics.Bitmap.createBitmap(
                    source,
                    FIXTURE_REFERENCE_REGION.left,
                    FIXTURE_REFERENCE_REGION.top,
                    FIXTURE_REFERENCE_REGION.width(),
                    FIXTURE_REFERENCE_REGION.height(),
                )
            } finally {
                frames.forEach { it.recycle() }
            }
        } finally {
            retriever.release()
            file.delete()
        }
    }

    private fun copyAsset(assetName: String): File = File(
        outputContext.cacheDir,
        "${System.nanoTime()}-$assetName",
    ).also { file ->
        assetContext.assets.open("rendered-video/$assetName").use { input ->
            file.outputStream().use(input::copyTo)
        }
    }

    private fun triggerPresent(bitmap: android.graphics.Bitmap): Boolean {
        val region = Rect(902, 136, 1_028, 262)
        val pixels = IntArray(region.width() * region.height())
        bitmap.getPixels(pixels, 0, region.width(), region.left, region.top, region.width(), region.height())
        return FixtureRenderedTriggerProbe.evaluate(pixels, region.width(), region.height()).present
    }

    private companion object {
        val FIXTURE_REFERENCE_REGION = Rect(52, 136, 880, 262)
    }
}
