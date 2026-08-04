package com.echolink.mobile

import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AllToneFullPageDeviceTest {
    @Test
    fun rewritesSealedFullPageInEveryTone() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val targetContext = instrumentation.targetContext
        val testContext = instrumentation.context
        val corpusText = testContext.assets.open(
            "device_tests/gpt56-five-page-all-tones-v2.json",
        ).bufferedReader().use { it.readText() }
        val corpus = JSONObject(corpusText)
        requireExpectedDocumentId(corpus)
        val source = testContext.assets.open(
            "device_tests/${corpus.getString("source_file")}",
        ).bufferedReader().use { it.readText() }
        val proofreadReference = testContext.assets.open(
            "device_tests/${corpus.getString("proofread_reference_file")}",
        ).bufferedReader().use { it.readText() }
        val arguments = InstrumentationRegistry.getArguments()
        val tone = requireNotNull(arguments.getString("tone")?.let(WritingTone::valueOf)) {
            "The tone instrumentation argument is required."
        }
        val roundId = requireNotNull(arguments.getString("roundId")) {
            "The roundId instrumentation argument is required."
        }
        require(Regex("[a-z0-9-]{3,64}").matches(roundId)) {
            "roundId must contain lowercase letters, digits, and hyphens only."
        }
        val tones = listOf(tone)
        val resultFileName = "gpt56-${tone.name.lowercase()}-$roundId.json"
        val modelManager = AiModelManager.get(targetContext)
        val installedModel = modelManager.installedModel()
        val selection = modelManager.selectedRuntime()
        val packageInfo = targetContext.packageManager.getPackageInfo(targetContext.packageName, 0)
        val results = JSONArray()
        val failures = mutableListOf<String>()
        val report = JSONObject()
            .put("schema_version", 2)
            .put("round_id", roundId)
            .put("tone", tone.name)
            .put("document_id", corpus.getString("document_id"))
            .put("source_sha256", sha256(source))
            .put("proofread_reference_sha256", sha256(proofreadReference))
            .put("source_word_count", source.trim().split(Regex("\\s+")).size)
            .put("application_id", targetContext.packageName)
            .put("version_name", packageInfo.versionName)
            .put("version_code", packageInfo.longVersionCode)
            .put("device_manufacturer", Build.MANUFACTURER)
            .put("device_model", Build.MODEL)
            .put("android_api", Build.VERSION.SDK_INT)
            .put("supported_abis", JSONArray(Build.SUPPORTED_ABIS.toList()))
            .put("ai_file", installedModel?.file?.name ?: JSONObject.NULL)
            .put("ai_tier", installedModel?.artifact?.tier?.name ?: JSONObject.NULL)
            .put("backend", selection.backend.name)
            .put("quality_mode", QualityMode.THOROUGH.name)
            .put("tone_count", tones.size)
            .put("results", results)

        tones.forEachIndexed { index, tone ->
            val latch = CountDownLatch(1)
            var rewriteResult: Result<String>? = null
            val paragraphEvidence = JSONArray()
            val startedAt = SystemClock.elapsedRealtime()
            val request = OnDeviceGrammarEngine.get(targetContext).polishDocument(
                source = source,
                tone = tone,
                sourceApplication = targetContext.packageName,
                qualityModeOverride = QualityMode.THOROUGH,
                onParagraphCompleted = { paragraph ->
                    val elapsedMs = SystemClock.elapsedRealtime() - startedAt
                    paragraphEvidence.put(
                        JSONObject()
                            .put("index", paragraph.index)
                            .put("elapsed_ms", elapsedMs)
                            .put("original", paragraph.original)
                            .put("first_pass", paragraph.firstPassText)
                            .put("pre_review", paragraph.preReviewText)
                            .put("final", paragraph.text)
                            .put("reviewed", paragraph.reviewed)
                            .put("used_fallback", paragraph.usedFallback)
                            .put("validated", paragraph.validated)
                            .put(
                                "risks",
                                JSONArray(paragraph.risks.map(SafetyIssue::name)),
                            ),
                    )
                    Log.i(
                        RESULT_LOG_TAG,
                        "tone=${tone.name};paragraph=${paragraph.index};elapsed_ms=$elapsedMs",
                    )
                },
            ) {
                rewriteResult = it.map(DocumentCorrectionResult::text)
                latch.countDown()
            }
            val finished = latch.await(PER_TONE_TIMEOUT_MINUTES, TimeUnit.MINUTES)
            val durationMs = SystemClock.elapsedRealtime() - startedAt
            if (!finished) request.cancel()

            val resultObject = JSONObject()
                .put("tone", tone.name)
                .put("index", index + 1)
                .put("duration_ms", durationMs)
                .put("paragraph_evidence", paragraphEvidence)
            when {
                !finished -> {
                    failures += "${tone.name}: timeout"
                    resultObject
                        .put("success", false)
                        .put("error", "timeout")
                }
                rewriteResult == null -> {
                    failures += "${tone.name}: missing callback result"
                    resultObject
                        .put("success", false)
                        .put("error", "missing callback result")
                }
                rewriteResult!!.isFailure -> {
                    val error = rewriteResult!!.exceptionOrNull()
                    val message = "${error?.javaClass?.simpleName}: ${error?.message}"
                    failures += "${tone.name}: $message"
                    resultObject
                        .put("success", false)
                        .put("error", message)
                    if (error is ParagraphCorrectionException) {
                        val encodedSource = ProtectedSpanCodec.protect(
                            LosslessSourceNormalizer.normalize(source).text,
                        )
                        val failedChunk = ParagraphChunker.chunk(encodedSource.encodedText)
                            .firstOrNull { it.index == error.paragraphIndex }
                        val expectedTokens = failedChunk?.text
                            ?.let(ProtectedDocument.PLACEHOLDER::findAll)
                            ?.map(MatchResult::value)
                            ?.toList()
                            .orEmpty()
                        val actualTokens = error.rejectedCandidate
                            ?.let(ProtectedDocument.PLACEHOLDER::findAll)
                            ?.map(MatchResult::value)
                            ?.toList()
                            .orEmpty()
                        resultObject
                            .put("failure_paragraph_index", error.paragraphIndex)
                            .put("failure_stage", error.stage ?: JSONObject.NULL)
                            .put(
                                "rejected_candidate",
                                error.rejectedCandidate ?: JSONObject.NULL,
                            )
                            .put(
                                "hard_failures",
                                JSONArray(
                                    error.validation?.hardFailures
                                        ?.map(SafetyIssue::name)
                                        .orEmpty(),
                                ),
                            )
                            .put(
                                "high_risks",
                                JSONArray(
                                    error.validation?.highRisks
                                        ?.map(SafetyIssue::name)
                                        .orEmpty(),
                                ),
                            )
                            .put("expected_protected_tokens", JSONArray(expectedTokens))
                            .put("actual_protected_tokens", JSONArray(actualTokens))
                    }
                }
                else -> {
                    val output = rewriteResult!!.getOrThrow()
                    if (output.contains('\u2014')) {
                        failures += "${tone.name}: output contains an em dash"
                    }
                    resultObject
                        .put("success", !output.contains('\u2014'))
                        .put("output", output)
                        .put("output_sha256", sha256(output))
                        .put("em_dash_count", output.count { it == '\u2014' })
                }
            }
            results.put(resultObject)
            report.put("failure_count", failures.size)
            report.put("completed_tone_count", results.length())
            Log.i(
                RESULT_LOG_TAG,
                "tone=${tone.name};index=${index + 1}/${tones.size};" +
                    "duration_ms=$durationMs;success=${resultObject.getBoolean("success")}",
            )
        }

        report.put("failure_count", failures.size)
        report.put("completed_tone_count", results.length())
        val reportLocation = DeviceTestEvidenceWriter.writePrivate(
            targetContext,
            resultFileName,
            report.toString(2),
        )
        Log.i(RESULT_LOG_TAG, "report=$reportLocation")

        assertEquals(tones.size, results.length())
        assertTrue(
            "Real model failures: ${failures.joinToString()} report=$reportLocation",
            failures.isEmpty(),
        )
    }

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }

    companion object {
        internal fun requireExpectedDocumentId(corpus: JSONObject) {
            require(corpus.getString("document_id") == EXPECTED_DOCUMENT_ID) {
                "Expected document ID $EXPECTED_DOCUMENT_ID."
            }
        }

        private const val EXPECTED_DOCUMENT_ID = "northstar-five-page-all-tones-sealed-v2"
        private const val PER_TONE_TIMEOUT_MINUTES = 30L
        private const val RESULT_LOG_TAG = "ManuscriptAllTones"
    }
}
