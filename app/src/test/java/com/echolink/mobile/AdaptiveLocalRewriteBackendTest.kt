package com.echolink.mobile

import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AdaptiveLocalRewriteBackendTest {
    @Test
    fun gemmaIsPrimaryAndLegacyRunsOnlyWhenExplicitlySelected() = runBlocking {
        val gemma = RecordingBackend("gemma", "Gemma result")
        val legacy = RecordingBackend("legacy", "Legacy result")
        val adaptive = AdaptiveLocalRewriteBackend(gemma, legacy)

        adaptive.initialize(
            InstalledAiModel(GemmaModelCatalog.fast, File("fast.litertlm")),
            BackendKind.LITERT_CPU,
        )
        assertEquals("Gemma result", adaptive.generate(request("gemma-job")).text)
        assertEquals(1, gemma.generateCount)
        assertEquals(0, legacy.generateCount)

        adaptive.initialize(
            InstalledAiModel(GemmaModelCatalog.legacyCompact, File("fallback.gguf")),
            BackendKind.LEGACY_LLAMA_CPU,
        )
        assertTrue(gemma.released)
        assertEquals("Legacy result", adaptive.generate(request("legacy-job")).text)
        assertEquals(1, legacy.generateCount)

        adaptive.cancel("legacy-job")
        assertEquals(setOf("legacy-job"), legacy.cancelled)
        adaptive.releaseResources()
        assertTrue(legacy.released)
    }

    private fun request(jobId: String) = LocalGenerationRequest(
        jobId = jobId,
        operation = WritingOperation.CORRECT_ONLY,
        previousContext = null,
        originalTarget = "dogs is loyal",
        firstPassCandidate = null,
        nextContext = null,
        qualityMode = QualityMode.FAST,
        strictRetry = false,
        tone = WritingTone.PROOFREAD,
    )

    private class RecordingBackend(
        override val backendId: String,
        private val response: String,
    ) : LocalRewriteBackend {
        var generateCount = 0
        var released = false
        val cancelled = linkedSetOf<String>()

        override suspend fun initialize(model: InstalledAiModel, backend: BackendKind) = Unit

        override suspend fun generate(
            request: LocalGenerationRequest,
        ): LocalGenerationResult {
            generateCount += 1
            return LocalGenerationResult(request.jobId, response, completed = true)
        }

        override suspend fun cancel(jobId: String) {
            cancelled += jobId
        }

        override fun releaseResources() {
            released = true
        }
    }
}
