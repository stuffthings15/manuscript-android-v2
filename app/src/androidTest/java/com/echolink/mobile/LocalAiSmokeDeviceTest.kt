package com.echolink.mobile

import android.os.SystemClock
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LocalAiSmokeDeviceTest {
    @Test
    fun proofreadUsesContextAndCorrectsWordForms() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val roundId = requireNotNull(
            InstrumentationRegistry.getArguments().getString("roundId"),
        ) {
            "The roundId instrumentation argument is required."
        }
        require(Regex("[a-z0-9-]{3,64}").matches(roundId)) {
            "roundId must contain lowercase letters, digits, and hyphens only."
        }
        val source = "Dogs wag their tails because it help peoples understand how they feels."
        val latch = CountDownLatch(1)
        var result: Result<String>? = null
        val startedAt = SystemClock.elapsedRealtime()

        OnDeviceGrammarEngine.get(context).polish(source, WritingTone.PROOFREAD) {
            result = it
            latch.countDown()
        }

        assertTrue("Local AI timed out.", latch.await(2, TimeUnit.MINUTES))
        val durationMs = SystemClock.elapsedRealtime() - startedAt
        val failure = result?.exceptionOrNull()
        if (failure != null) {
            val paragraphFailure = failure as? ParagraphCorrectionException
            val rejected = paragraphFailure?.rejectedCandidate.orEmpty()
            val probability = Int8GrammarClassifier(context).let { classifier ->
                try {
                    classifier.acceptableProbability(rejected)
                } finally {
                    classifier.releaseResources()
                }
            }
            throw AssertionError(
                "Local AI failed: ${failure.message}; " +
                    "stage=${paragraphFailure?.stage}; " +
                    "candidate=${paragraphFailure?.rejectedCandidate}; " +
                    "validation=${paragraphFailure?.validation}; " +
                    "ruleScore=${GrammarQualityGate.errorScore(rejected)}; " +
                    "repaired=${GrammarSafetyNet.repair(rejected)}; " +
                    "probability=$probability",
                failure,
            )
        }
        val corrected = result?.getOrThrow()
        assertNotNull(corrected)
        val correctedOutput = corrected!!
        assertTrue("The corrected output must not be empty.", correctedOutput.isNotBlank())
        val normalized = correctedOutput.lowercase()
        assertTrue("The valid verb 'wag' must be preserved.", normalized.contains("dogs wag"))
        assertFalse("AI must not change 'wag' to 'was'.", normalized.contains("dogs was"))
        assertTrue("The plural word form must be corrected.", normalized.contains("people"))
        assertFalse("The invalid plural must be removed.", normalized.contains("peoples"))
        assertTrue("Subject-verb agreement must be corrected.", normalized.contains("it helps"))
        assertTrue("Pronoun agreement must be corrected.", normalized.contains("they feel"))

        val modelManager = AiModelManager.get(context)
        val selection = modelManager.selectedRuntime()
        val installed = modelManager.installedModel(selection)
        assertNotNull("The selected model must be installed.", installed)
        val installedModel = installed!!
        assertEquals(
            "The installed model must match the selected artifact.",
            selection.artifact,
            installedModel.artifact,
        )
        val report = JSONObject()
            .put("schema_version", 1)
            .put("round_id", roundId)
            .put("tone", WritingTone.PROOFREAD.name)
            .put("source", source)
            .put("output", correctedOutput)
            .put("output_sha256", sha256(correctedOutput))
            .put("duration_ms", durationMs)
            .put(
                "model",
                JSONObject()
                    .put("family", installedModel.artifact.family.name)
                    .put("tier", installedModel.artifact.tier.name)
                    .put("backend", selection.backend.name)
                    .put("file_name", installedModel.file.name)
                    .put("repository", installedModel.artifact.repository)
                    .put("revision", installedModel.artifact.revision)
                    .put("artifact_sha256", installedModel.artifact.sha256),
            )
        val expectedEvidence = File(
            context.filesDir,
            "test-evidence/local-ai-smoke-$roundId.json",
        )
        val reportText = report.toString(2)
        val reportLocation = DeviceTestEvidenceWriter.writePrivate(
            context,
            expectedEvidence.name,
            reportText,
        )
        assertEquals(expectedEvidence.absolutePath, reportLocation)
        assertEquals(reportText, File(reportLocation).readText())
        Log.i(RESULT_LOG_TAG, "round_id=$roundId;report=$reportLocation")
    }

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }

    companion object {
        private const val RESULT_LOG_TAG = "ManuscriptSmoke"
    }
}
