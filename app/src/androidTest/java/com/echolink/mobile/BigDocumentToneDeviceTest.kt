package com.echolink.mobile

import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Runs a 22-paragraph error-heavy document through one tone end to end on the
 * real device and records per-paragraph latency plus AI usage. The tone comes
 * from the "tone" instrumentation argument; quality mode is the user-facing
 * FAST default so the measured time matches what a customer sees.
 */
@RunWith(AndroidJUnit4::class)
class BigDocumentToneDeviceTest {
    @Test
    fun correctsTwentyParagraphDocumentInSelectedTone() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val source = instrumentation.context.assets
            .open("device_tests/big-document-20-para-v1.txt")
            .bufferedReader()
            .use { it.readText() }
        val arguments = InstrumentationRegistry.getArguments()
        val tone = requireNotNull(arguments.getString("tone")?.let(WritingTone::valueOf)) {
            "The tone instrumentation argument is required."
        }
        val roundId = requireNotNull(arguments.getString("roundId")) {
            "The roundId instrumentation argument is required."
        }

        val paragraphRows = JSONArray()
        val latch = CountDownLatch(1)
        var result: Result<DocumentCorrectionResult>? = null
        val startedAt = SystemClock.elapsedRealtime()
        var lastParagraphAt = startedAt

        OnDeviceGrammarEngine.get(context).polishDocument(
            source = source,
            tone = tone,
            sourceApplication = context.packageName,
            qualityModeOverride = QualityMode.FAST,
            onParagraphCompleted = { paragraph ->
                val now = SystemClock.elapsedRealtime()
                paragraphRows.put(
                    JSONObject()
                        .put("index", paragraph.index)
                        .put("elapsed_ms", now - startedAt)
                        .put("paragraph_ms", now - lastParagraphAt)
                        .put("validated", paragraph.validated)
                        .put("used_fallback", paragraph.usedFallback)
                        .put("risks", paragraph.risks.joinToString(",")),
                )
                lastParagraphAt = now
                Log.i(
                    "MANUSCRIPT_BIGDOC",
                    "tone=$tone paragraph=${paragraph.index} " +
                        "elapsedMs=${now - startedAt}",
                )
            },
        ) {
            result = it
            latch.countDown()
        }

        assertTrue(
            "Big-document correction timed out.",
            latch.await(25, TimeUnit.MINUTES),
        )
        val totalMs = SystemClock.elapsedRealtime() - startedAt
        val failure = result?.exceptionOrNull()
        val corrected = result?.getOrNull()

        val report = JSONObject()
            .put("schema_version", 1)
            .put("round_id", roundId)
            .put("tone", tone.name)
            .put("quality_mode", "FAST")
            .put("source_chars", source.length)
            .put("paragraph_count", paragraphRows.length())
            .put("total_ms", totalMs)
            .put("success", failure == null)
            .put("paragraphs", paragraphRows)
        if (failure != null) {
            val paragraphFailure = failure as? ParagraphCorrectionException
            report.put("error", "${failure.javaClass.simpleName}: ${failure.message}")
                .put("failure_paragraph", paragraphFailure?.paragraphIndex ?: -1)
                .put("failure_stage", paragraphFailure?.stage ?: "")
                .put(
                    "rejected_candidate",
                    paragraphFailure?.rejectedCandidate.orEmpty().take(700),
                )
                .put(
                    "hard_failures",
                    paragraphFailure?.validation?.hardFailures?.joinToString(",") ?: "",
                )
            val failedIndex = paragraphFailure?.paragraphIndex
            val rejected = paragraphFailure?.rejectedCandidate
            if (failedIndex != null && !rejected.isNullOrBlank()) {
                val normalized = LosslessSourceNormalizer.normalize(source)
                val protected = ProtectedSpanCodec.protect(normalized.text)
                val chunk = ParagraphChunker.chunk(protected.encodedText)
                    .firstOrNull { it.index == failedIndex }
                if (chunk != null) {
                    report.put("original_chunk", chunk.text.take(700))
                        .put("diagnosis", AiSafetyValidator.diagnose(chunk.text, rejected))
                }
            }
        } else {
            report.put("warnings", JSONArray(corrected!!.warnings))
                .put(
                    "validated_paragraphs",
                    corrected.paragraphs.count { it.validated },
                )
                .put("corrected_sha_chars", corrected.text.length)
        }
        val dir = File(context.filesDir, "test-evidence")
        dir.mkdirs()
        val file = File(dir, "bigdoc-${tone.name.lowercase()}-$roundId.json")
        file.writeText(report.toString(2))
        Log.i("MANUSCRIPT_BIGDOC", "report=${file.absolutePath} totalMs=$totalMs")

        if (failure != null) {
            throw AssertionError(
                "Big-document $tone failed: ${report.optString("error")}; " +
                    "paragraph=${report.optInt("failure_paragraph")}; " +
                    "hard=${report.optString("hard_failures")}; " +
                    "candidate=${report.optString("rejected_candidate").take(220)}",
                failure,
            )
        }
        // Approved contract: one stubborn paragraph must not destroy the run.
        // A paragraph the model cannot correct safely keeps the user's exact
        // original text and is reported unvalidated, so assert the document
        // completed, that rescued paragraphs are unchanged rather than
        // damaged, and hold a quality floor on how many were corrected.
        val paragraphs = corrected!!.paragraphs
        val rescued = paragraphs.filterNot { it.validated }
        rescued.forEach { paragraph ->
            assertEquals(
                "A rescued paragraph must keep the user's original text.",
                paragraph.original,
                paragraph.text,
            )
        }
        val validatedCount = paragraphs.count { it.validated }
        assertTrue(
            "Too few paragraphs were corrected: $validatedCount of ${paragraphs.size}" +
                " (rescued indexes ${rescued.map { it.index }})",
            validatedCount * 10 >= paragraphs.size * 9,
        )
    }
}
