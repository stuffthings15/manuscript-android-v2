package com.echolink.mobile

import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Measures the user-facing FAST (Auto) path on the same frozen full page the
 * THOROUGH gate uses, so mode-to-mode latency can be compared on one device.
 */
@RunWith(AndroidJUnit4::class)
class FastModeFullPageSpeedDeviceTest {
    @Test
    fun fastModeProofreadsFrozenFullPage() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val source = instrumentation.context.assets
            .open("device_tests/gpt56-full-page-unseen-v2.txt")
            .bufferedReader()
            .use { it.readText() }
        val latch = CountDownLatch(1)
        var result: Result<String>? = null
        val startedAt = SystemClock.elapsedRealtime()
        var firstParagraphMs = 0L

        OnDeviceGrammarEngine.get(context).polishDocument(
            source = source,
            tone = WritingTone.PROOFREAD,
            sourceApplication = context.packageName,
            qualityModeOverride = QualityMode.FAST,
            onParagraphCompleted = { paragraph ->
                if (firstParagraphMs == 0L) {
                    firstParagraphMs = SystemClock.elapsedRealtime() - startedAt
                }
                Log.i(
                    "MANUSCRIPT_FAST_SPEED",
                    "paragraph=${paragraph.index} " +
                        "elapsedMs=${SystemClock.elapsedRealtime() - startedAt} " +
                        "reviewed=${paragraph.reviewed}",
                )
            },
        ) {
            result = it.map(DocumentCorrectionResult::text)
            latch.countDown()
        }

        assertTrue("Local AI timed out", latch.await(12, TimeUnit.MINUTES))
        val totalMs = SystemClock.elapsedRealtime() - startedAt
        val failure = result!!.exceptionOrNull()
        if (failure != null) {
            val paragraphFailure = failure as? ParagraphCorrectionException
            throw AssertionError(
                "FAST full-page AI failed: ${failure.message}; " +
                    "paragraph=${paragraphFailure?.paragraphIndex}; " +
                    "validation=${paragraphFailure?.validation}",
                failure,
            )
        }
        Log.i(
            "MANUSCRIPT_FAST_SPEED",
            "TOTAL totalMs=$totalMs firstParagraphMs=$firstParagraphMs",
        )
        assertTrue("Corrected text must not be blank", result!!.getOrThrow().isNotBlank())
    }
}
