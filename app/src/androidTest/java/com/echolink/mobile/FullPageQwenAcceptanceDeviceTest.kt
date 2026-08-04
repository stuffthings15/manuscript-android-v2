package com.echolink.mobile

import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FullPageQwenAcceptanceDeviceTest {
    @Test
    fun proofreadCandidatePassesProductionAiSafetyGates() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val targetContext = instrumentation.targetContext
        val testContext = instrumentation.context
        val corpus = JSONObject(
            testContext.assets.open("device_tests/gpt56-five-page-all-tones-v2.json")
                .bufferedReader()
                .use { it.readText() },
        )
        val source = testContext.assets.open(
            "device_tests/${corpus.getString("source_file")}",
        ).bufferedReader().use { it.readText() }
        val latch = CountDownLatch(1)
        var correction: Result<String>? = null
        val startedAt = SystemClock.elapsedRealtime()
        val request = OnDeviceGrammarEngine.get(targetContext).polish(
            source,
            WritingTone.PROOFREAD,
        ) {
            correction = it
            latch.countDown()
        }
        val finished = latch.await(10, TimeUnit.MINUTES)
        if (!finished) request.cancel()
        val durationMs = SystemClock.elapsedRealtime() - startedAt
        val output = correction?.getOrNull()
        val safety = output?.let { AiSafetyValidator.validate(source, it) }
        val accepted =
            finished &&
                output != null &&
                safety?.safe == true &&
                '\u2014' !in output
        val manager = AiModelManager.get(targetContext)
        val installed = manager.installedModel()
        val report = JSONObject()
            .put("document_id", corpus.getString("document_id"))
            .put("tone", WritingTone.PROOFREAD.name)
            .put("duration_ms", durationMs)
            .put("ai_family", installed?.artifact?.family?.name ?: JSONObject.NULL)
            .put("ai_tier", installed?.artifact?.tier?.name ?: JSONObject.NULL)
            .put("backend", manager.selectedRuntime().backend.name)
            .put("output", output ?: JSONObject.NULL)
            .put("safety_passed", safety?.safe ?: false)
            .put("em_dash_count", output?.count { it == '\u2014' } ?: 0)
            .put(
                "error",
                correction?.exceptionOrNull()?.let {
                    "${it.javaClass.simpleName}: ${it.message}"
                } ?: JSONObject.NULL,
            )
        val reportLocation = DeviceTestEvidenceWriter.write(
            targetContext,
            RESULT_FILE_NAME,
            report.toString(2),
        )
        Log.i(RESULT_LOG_TAG, "report=$reportLocation")

        assertTrue(
            "Real local AI candidate failed. report=$reportLocation",
            accepted,
        )
    }

    companion object {
        private const val RESULT_FILE_NAME = "gpt56-five-page-proofread-acceptance-v3.json"
        private const val RESULT_LOG_TAG = "ManuscriptAcceptance"
    }
}
