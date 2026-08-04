package com.echolink.mobile

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Locks the phase-1/2/4 speed contract: clean copy-through, cache, prompt split. */
class SpeedPipelineTest {

    private class CountingBackend : LocalRewriteBackend {
        override val backendId: String = "counting"
        var calls = 0
        override suspend fun initialize(model: InstalledAiModel, backend: BackendKind) = Unit
        override suspend fun generate(request: LocalGenerationRequest): LocalGenerationResult {
            calls += 1
            val rewritten = when (request.operation) {
                WritingOperation.TONE_TRANSFORM ->
                    "The paragraph in question is entirely without error."
                WritingOperation.REVIEW_CANDIDATE ->
                    request.firstPassCandidate ?: request.originalTarget
                else -> request.originalTarget.replace("we was", "we were")
            }
            return LocalGenerationResult(
                jobId = request.jobId,
                text = rewritten,
                completed = true,
            )
        }
        override suspend fun cancel(jobId: String) = Unit
        override fun releaseResources() = Unit
    }

    @Test
    fun everyNonBlankParagraphIsSentToTheModel() = runBlocking {
        val backend = CountingBackend()
        val pipeline = DocumentCorrectionPipeline(primaryBackend = backend)

        val source = listOf(
            "This paragraph is already perfectly clean.",
            "we was ready for the launch",
            "Another paragraph that looks fine to a classifier.",
        ).joinToString("\n\n")
        val result = pipeline.process(
            DocumentCorrectionRequest(
                jobId = "no-skip-1",
                source = source,
                tone = WritingTone.PROOFREAD,
                qualityMode = QualityMode.FAST,
            ),
        )

        val nonBlank = result.paragraphs.count { it.original.isNotBlank() }
        assertEquals(3, nonBlank)
        // Every paragraph reaches the model, and the two the backend echoes
        // back unchanged each get one strict retry before being accepted:
        // 1 corrected + 2 echoed x 2 attempts = 5.
        assertEquals(5, backend.calls)
    }

    @Test
    fun aHalfCorrectedParagraphIsSweptByTheDeterministicRepair() = runBlocking {
        // The model repairs the opening sentence and hands the rest back
        // untouched. That output preserves meaning, so validation accepts it;
        // the residual sweep is what stops raw misspellings reaching the user.
        val lazyBackend = object : LocalRewriteBackend {
            override val backendId: String = "lazy"
            override suspend fun initialize(model: InstalledAiModel, backend: BackendKind) = Unit
            override suspend fun generate(
                request: LocalGenerationRequest,
            ): LocalGenerationResult = LocalGenerationResult(
                jobId = request.jobId,
                text = request.originalTarget.replace("Wen ", "When "),
                completed = true,
            )
            override suspend fun cancel(jobId: String) = Unit
            override fun releaseResources() = Unit
        }
        val pipeline = DocumentCorrectionPipeline(
            primaryBackend = lazyBackend,
            deterministicSentenceRepair = { text -> text.replace("peeple", "people") },
        )

        val result = pipeline.process(
            DocumentCorrectionRequest(
                jobId = "residual-1",
                source = "Wen somebody says that, some peeple will disagree.",
                tone = WritingTone.PROOFREAD,
                qualityMode = QualityMode.FAST,
            ),
        )

        assertTrue(result.text.contains("When somebody"))
        assertFalse("Residual misspelling reached the user.", result.text.contains("peeple"))
    }

    @Test
    fun everyParagraphOutcomeIsLogged() = runBlocking {
        val outcomes = mutableListOf<String>()
        val pipeline = DocumentCorrectionPipeline(
            primaryBackend = CountingBackend(),
            logParagraphOutcome = { outcomes += it },
        )

        pipeline.process(
            DocumentCorrectionRequest(
                jobId = "trace-1",
                source = "we was ready for the launch\n\nA second paragraph here.",
                tone = WritingTone.PROOFREAD,
                qualityMode = QualityMode.FAST,
            ),
        )

        assertEquals(2, outcomes.count { it.contains("path=model") })
        assertTrue(
            outcomes.filter { it.startsWith("paragraph=") }.isNotEmpty(),
        )
        // The residual sweep reports itself too, so a correction that still
        // carries errors can be diagnosed from the log alone.
        assertTrue(outcomes.any { it.startsWith("sweep=") })
    }

    @Test
    fun cachedParagraphReturnsWithoutTheModelAndNewResultsAreStored() = runBlocking {
        val backend = CountingBackend()
        val cache = mutableMapOf<String, String>()
        val pipeline = DocumentCorrectionPipeline(
            primaryBackend = backend,
            correctionCacheGet = cache::get,
            correctionCachePut = { key, value -> cache[key] = value },
        )
        val request = DocumentCorrectionRequest(
            jobId = "cache-1",
            source = "we was ready for the launch",
            tone = WritingTone.PROOFREAD,
            qualityMode = QualityMode.FAST,
        )

        val first = pipeline.process(request)
        assertEquals(1, backend.calls)
        assertTrue(cache.isNotEmpty())

        val second = pipeline.process(request.copy(jobId = "cache-2"))
        assertEquals("cache hit must not call the model", 1, backend.calls)
        assertEquals(first.text, second.text)
        assertTrue(second.paragraphs.single().validated)
    }

    @Test
    fun instructionsAndPayloadAreSeparatedForPromptCaching() {
        val instructions = TonePromptLibrary.instructions(WritingTone.PROOFREAD)
        val payload = TonePromptLibrary.payload("we was ready")

        assertTrue(instructions.contains("Requirements:"))
        assertFalse(instructions.contains("we was ready"))
        assertTrue(payload.startsWith("Text:"))
        assertFalse(payload.contains("Requirements:"))
        assertTrue(
            TonePromptLibrary.compactInstructions(WritingTone.FORMAL)
                .contains("visibly change"),
        )
    }
}
