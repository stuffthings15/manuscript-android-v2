package com.echolink.mobile

import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FocusedParagraphQualityDeviceTest {
    @Test
    fun rewritesOneFrozenParagraphThroughTheRealInstalledAi() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val targetContext = instrumentation.targetContext
        val testContext = instrumentation.context
        val paragraphIndex = InstrumentationRegistry.getArguments()
            .getString("paragraphIndex")
            ?.toIntOrNull()
            ?: 1
        val source = testContext.assets.open(
            "device_tests/gpt56-five-page-errors-v2.md",
        ).bufferedReader().use { it.readText() }
        val reference = testContext.assets.open(
            "device_tests/gpt56-five-page-proofread-reference-v2.md",
        ).bufferedReader().use { it.readText() }
        val sourceParagraphs = source.trim().split(PARAGRAPH_SEPARATOR)
        val referenceParagraphs = reference.trim().split(PARAGRAPH_SEPARATOR)
        assertTrue(paragraphIndex in 1..sourceParagraphs.size)
        assertTrue(paragraphIndex in 1..referenceParagraphs.size)

        val startedAt = SystemClock.elapsedRealtime()
        val latch = CountDownLatch(1)
        var result: Result<DocumentCorrectionResult>? = null
        OnDeviceGrammarEngine.get(targetContext).polishDocument(
            source = sourceParagraphs[paragraphIndex - 1],
            tone = WritingTone.PROOFREAD,
            sourceApplication = targetContext.packageName,
            qualityModeOverride = QualityMode.THOROUGH,
        ) {
            result = it
            latch.countDown()
        }

        assertTrue("Focused AI correction timed out.", latch.await(5, TimeUnit.MINUTES))
        assertNotNull(result)
        val failure = result!!.exceptionOrNull()
        if (failure != null) {
            val paragraphFailure = failure as? ParagraphCorrectionException
            val rejectedRestored = paragraphFailure?.rejectedCandidate?.let { rejected ->
                runCatching {
                    ProtectedSpanCodec.protect(sourceParagraphs[paragraphIndex - 1])
                        .restore(rejected)
                }.getOrNull()
            }
            val rejectedProbability = rejectedRestored?.let { rejected ->
                val classifier = Int8GrammarClassifier(targetContext)
                try {
                    classifier.acceptableProbability(rejected)
                } finally {
                    classifier.releaseResources()
                }
            }
            val failureReport = JSONObject()
                .put("schema_version", 1)
                .put("paragraph_index", paragraphIndex)
                .put("duration_ms", SystemClock.elapsedRealtime() - startedAt)
                .put("source", sourceParagraphs[paragraphIndex - 1])
                .put("reference", referenceParagraphs[paragraphIndex - 1])
                .put("error", "${failure.javaClass.simpleName}: ${failure.message}")
                .put(
                    "rejected_candidate",
                    paragraphFailure?.rejectedCandidate ?: JSONObject.NULL,
                )
                .put("rejected_restored", rejectedRestored ?: JSONObject.NULL)
                .put(
                    "rejected_grammar_quality_error_score",
                    rejectedRestored?.let(GrammarQualityGate::errorScore) ?: JSONObject.NULL,
                )
                .put(
                    "rejected_classifier_probability",
                    rejectedProbability ?: JSONObject.NULL,
                )
                .put(
                    "hard_failures",
                    paragraphFailure?.validation?.hardFailures?.map(SafetyIssue::name),
                )
            val failureLocation = DeviceTestEvidenceWriter.write(
                targetContext,
                "gpt56-focused-paragraph-$paragraphIndex-failure.json",
                failureReport.toString(2),
            )
            Log.i(LOG_TAG, "paragraph=$paragraphIndex;failure_report=$failureLocation")
            throw failure
        }
        val completed = result!!.getOrThrow()
        val output = completed.text
        val correctionRouter = LocalCorrectionRouter(targetContext)
        val classifierAccepted = try {
            correctionRouter.acceptsGeneratedGrammar(output)
        } finally {
            correctionRouter.releaseResources()
        }
        assertFalse(output.contains('\u2014'))
        val report = JSONObject()
            .put("schema_version", 1)
            .put("paragraph_index", paragraphIndex)
            .put("duration_ms", SystemClock.elapsedRealtime() - startedAt)
            .put("source", sourceParagraphs[paragraphIndex - 1])
            .put("reference", referenceParagraphs[paragraphIndex - 1])
            .put("output", output)
            .put("classifier_accepted", classifierAccepted)
            .put("grammar_quality_error_score", GrammarQualityGate.errorScore(output))
            .put("safety_net_would_change", GrammarSafetyNet.repair(output) != output)
            .put(
                "paragraph_evidence",
                completed.paragraphs.firstOrNull()?.let { paragraph ->
                    JSONObject()
                        .put("first_pass", paragraph.firstPassText)
                        .put("pre_review", paragraph.preReviewText)
                        .put("final", paragraph.text)
                } ?: JSONObject.NULL,
            )
            .put("warnings", completed.warnings)
        val location = DeviceTestEvidenceWriter.write(
            targetContext,
            "gpt56-focused-paragraph-$paragraphIndex.json",
            report.toString(2),
        )
        Log.i(LOG_TAG, "paragraph=$paragraphIndex;report=$location")
    }

    companion object {
        private val PARAGRAPH_SEPARATOR = Regex("\\r?\\n\\s*\\r?\\n")
        private const val LOG_TAG = "ManuscriptFocused"
    }
}
