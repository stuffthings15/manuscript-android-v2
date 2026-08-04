package com.echolink.mobile

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FullPageDeviceGrammarTest {
    @Test
    fun localAiProofreadsFrozenFullPageWithoutTransportChanges() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val source = instrumentation.context.assets.open("device_tests/gpt56-full-page-unseen-v2.txt")
            .bufferedReader()
            .use { it.readText() }
        val latch = CountDownLatch(1)
        var result: Result<String>? = null

        OnDeviceGrammarEngine.get(context).polishDocument(
            source = source,
            tone = WritingTone.PROOFREAD,
            sourceApplication = context.packageName,
            qualityModeOverride = QualityMode.THOROUGH,
            onProgress = { completed, total ->
                Log.i("MANUSCRIPT_DEVICE_PROGRESS", "$completed/$total")
            },
        ) {
            result = it.map(DocumentCorrectionResult::text)
            latch.countDown()
        }

        assertTrue("Local AI timed out", latch.await(12, TimeUnit.MINUTES))
        val failure = result!!.exceptionOrNull()
        if (failure != null) {
            val paragraphFailure = failure as? ParagraphCorrectionException
            throw AssertionError(
                "Full-page AI failed: ${failure.message}; " +
                    "paragraph=${paragraphFailure?.paragraphIndex}; " +
                    "stage=${paragraphFailure?.stage}; " +
                    "candidate=${paragraphFailure?.rejectedCandidate}; " +
                    "validation=${paragraphFailure?.validation}",
                failure,
            )
        }
        val corrected = result!!.getOrThrow()
        Log.i("MANUSCRIPT_DEVICE_RESULT", Base64.getEncoder().encodeToString(corrected.toByteArray()))
        assertTrue(corrected.contains("$24,750"))
        assertTrue(corrected.contains("$26,385"))
        assertTrue(corrected.contains("invoice 4482"))
        assertTrue(corrected.contains("186 registered guests"))
    }
}
