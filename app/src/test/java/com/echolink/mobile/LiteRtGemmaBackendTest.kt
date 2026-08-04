package com.echolink.mobile

import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiteRtGemmaBackendTest {
    @Test
    fun everyToneUsesItsFullDistinctPromptContractInProductionGeneration() {
        val prompts = WritingTone.entries.associateWith { tone ->
            LiteRtPromptBuilder.build(
                LocalGenerationRequest(
                    jobId = tone.name,
                    operation = if (tone == WritingTone.PROOFREAD) {
                        WritingOperation.CORRECT_ONLY
                    } else {
                        WritingOperation.TONE_TRANSFORM
                    },
                    previousContext = null,
                    originalTarget = "Keep the exact facts.",
                    firstPassCandidate = null,
                    nextContext = null,
                    qualityMode = QualityMode.THOROUGH,
                    strictRetry = false,
                    tone = tone,
                ),
            )
        }

        assertEquals(23, prompts.size)
        assertEquals(23, prompts.values.toSet().size)
        TonePromptLibrary.allSpecs().forEach { spec ->
            val prompt = requireNotNull(prompts[spec.tone])
            assertTrue(prompt.contains(spec.instruction))
            assertTrue(prompt.contains(spec.must.first()))
            assertTrue(prompt.contains(spec.avoid.first()))
            assertTrue(prompt.contains("Do not use em dashes"))
            assertTrue(prompt.contains("Copy every [[MS_TYPE_0000]] protected token"))
            assertTrue(prompt.contains("Never insert a token inside a word"))
        }
    }

    @Test
    fun reviewerAuditsLexicalDriftAndEveryMajorGrammarClass() {
        val prompt = LiteRtPromptBuilder.build(
            LocalGenerationRequest(
                jobId = "review-contract",
                operation = WritingOperation.REVIEW_CANDIDATE,
                previousContext = "The policy controls publication.",
                originalTarget =
                    "Any volunteer can publish screenshots. This permission is incorrect.",
                firstPassCandidate =
                    "Any volunteer can publish screenshots. This permission is incorrect.",
                nextContext = "Written approval is required.",
                qualityMode = QualityMode.THOROUGH,
                strictRetry = true,
                tone = WritingTone.PROOFREAD,
            ),
        )

        assertTrue(prompt.contains("real-word error"))
        assertTrue(prompt.contains("comma splice"))
        assertTrue(prompt.contains("dangling modifier"))
        assertTrue(prompt.contains("Audit every changed noun"))
        assertTrue(prompt.contains("Never replace a correct word with a similar-looking word"))
        assertTrue(prompt.contains("explicitly says a nearby statement is incorrect"))
        assertTrue(prompt.contains("The target has 1 line"))
    }

    @Test
    fun generationUsesContextAndConservativeDeterministicPolicy() = runBlocking {
        val engine = RecordingLiteRtEngine()
        val backend = LiteRtGemmaBackend(
            engineFactory = { engine },
            cacheDirectory = File("cache"),
            idleReleaseMillis = 60_000L,
        )
        backend.initialize(
            InstalledAiModel(GemmaModelCatalog.fast, File("fast.litertlm")),
            BackendKind.LITERT_CPU,
        )

        val result = backend.generate(
            LocalGenerationRequest(
                jobId = "job-1",
                operation = WritingOperation.CORRECT_ONLY,
                previousContext = "Yesterday the team met.",
                originalTarget = "they was agree to the plan",
                firstPassCandidate = null,
                nextContext = "Today work begins.",
                qualityMode = QualityMode.THOROUGH,
                strictRetry = false,
                tone = WritingTone.PROOFREAD,
            ),
        )

        assertEquals("They agreed to the plan.", result.text)
        assertTrue(result.completed)
        assertTrue(engine.lastPrompt.contains("PREVIOUS_CONTEXT\nYesterday the team met."))
        assertTrue(engine.lastPrompt.contains("TARGET_BEGIN\nthey was agree to the plan\nTARGET_END"))
        assertTrue(engine.lastPrompt.contains("NEXT_CONTEXT\nToday work begins."))
        assertTrue(engine.lastPrompt.contains("Never copy previous or next context"))
        // Deterministic greedy decoding: one input must give one output.
        assertEquals(
            LiteRtSamplingPolicy(topK = 1, topP = 1.0, temperature = 0.1, seed = 42),
            engine.lastSamplingPolicy,
        )
        assertTrue(engine.lastMaximumOutputTokens in 96..2_048)
        assertEquals(BackendKind.LITERT_CPU, engine.initializedBackend)
        backend.releaseResources()
        assertTrue(engine.closed)
    }

    @Test
    fun outputBudgetRejectsRunawayStreamingText() {
        val output = StringBuilder("safe")

        assertTrue(LiteRtOutputBudget.canAppend(output, " correction", 4))
        assertFalse(LiteRtOutputBudget.canAppend(output, "x".repeat(40), 4))
    }

    @Test
    fun headingOutputCannotLeakTheFollowingContext() {
        val leaked = """
            [[MS_MARKDOWN_PREFIX_0002]]Page [[MS_NUMBER_0003]]: Purpose, Schedule, and Ownership

            [[MS_NAME_0004]] is a [[MS_NUMBER_0005]] week digital-access pilot.
        """.trimIndent()

        val cleaned = LiteRtPromptBuilder.cleanOutput(
            leaked,
            "[[MS_MARKDOWN_PREFIX_0002]]Page [[MS_NUMBER_0003]]: Purpose, Schedule, and Ownership",
        )

        assertEquals(
            "[[MS_MARKDOWN_PREFIX_0002]]Page [[MS_NUMBER_0003]]: Purpose, Schedule, and Ownership",
            cleaned,
        )
    }

    @Test
    fun multilineMarkdownListIsNeverTruncatedAsAHeading() {
        val list = """
            [[MS_MARKDOWN_PREFIX_0030]][[MS_NAME_0031]]: deliver the kits.
            [[MS_MARKDOWN_PREFIX_0032]][[MS_NAME_0033]]: verify the seals.
            [[MS_MARKDOWN_PREFIX_0035]][[MS_NAME_0036]]: send the script.
        """.trimIndent()

        assertEquals(list, LiteRtPromptBuilder.cleanOutput(list, list))
    }

    @Test
    fun targetMarkersAreRemovedWithoutReturningReferenceContext() {
        val raw = """
            TARGET_BEGIN
            Dogs wag their tails.
            TARGET_END
            NEXT_CONTEXT
            This must not be returned.
        """.trimIndent()

        assertEquals(
            "Dogs wag their tails.",
            LiteRtPromptBuilder.cleanOutput(raw, "Dogs wag there tails."),
        )
    }

    @Test
    fun addedSentenceMarkAfterProtectedQuotationIsRemoved() {
        val original =
            "Mira said [[MS_QUOTATION_0012]] Her reminder was clear."
        val candidate =
            "Mira said [[MS_QUOTATION_0012]]. Her reminder was clear."

        assertEquals(
            original,
            LiteRtPromptBuilder.cleanOutput(candidate, original),
        )
    }

    @Test
    fun listIntroductionKeepsItsTerminalColon() {
        assertEquals(
            "The team has three immediate tasks:",
            LiteRtPromptBuilder.cleanOutput(
                "The team has three immediate tasks.",
                "The team has three immediate tasks:",
            ),
        )
    }

    @Test
    fun timedOutGenerationIsCancelledAndNeverReturned() = runBlocking {
        val engine = RecordingLiteRtEngine(delayMillis = 200L)
        val backend = LiteRtGemmaBackend(
            engineFactory = { engine },
            cacheDirectory = File("cache"),
            idleReleaseMillis = 60_000L,
            generationTimeoutMillis = 20L,
        )
        backend.initialize(
            InstalledAiModel(GemmaModelCatalog.fast, File("fast.litertlm")),
            BackendKind.LITERT_CPU,
        )

        val result = backend.generate(request("too-slow"))

        assertFalse(result.completed)
        assertTrue(result.text.isEmpty())
        assertEquals(setOf("too-slow"), engine.cancelledJobs)
        backend.releaseResources()
    }

    @Test
    fun paragraphRequestsRunSequentiallyAndModelSwitchClosesOldEngine() = runBlocking {
        val engines = mutableListOf<RecordingLiteRtEngine>()
        val backend = LiteRtGemmaBackend(
            engineFactory = {
                RecordingLiteRtEngine(delayMillis = 40L).also(engines::add)
            },
            cacheDirectory = File("cache"),
            idleReleaseMillis = 60_000L,
        )
        backend.initialize(
            InstalledAiModel(GemmaModelCatalog.fast, File("fast.litertlm")),
            BackendKind.LITERT_CPU,
        )
        val first = async { backend.generate(request("one")) }
        val second = async { backend.generate(request("two")) }
        first.await()
        second.await()

        assertEquals(1, engines.single().maximumConcurrentGenerations.get())

        backend.initialize(
            InstalledAiModel(GemmaModelCatalog.thorough, File("thorough.litertlm")),
            BackendKind.LITERT_GPU,
        )

        assertEquals(2, engines.size)
        assertTrue(engines.first().closed)
        assertFalse(engines.last().closed)
        assertEquals(BackendKind.LITERT_GPU, engines.last().initializedBackend)
        backend.releaseResources()
    }

    @Test
    fun cancellingActiveJobStopsItsConversationAndReturnsNoCorrection() = runBlocking {
        val engine = RecordingLiteRtEngine(delayMillis = 200L)
        val backend = LiteRtGemmaBackend(
            engineFactory = { engine },
            cacheDirectory = File("cache"),
            idleReleaseMillis = 60_000L,
        )
        backend.initialize(
            InstalledAiModel(GemmaModelCatalog.fast, File("fast.litertlm")),
            BackendKind.LITERT_CPU,
        )

        val pending = async { backend.generate(request("cancel-me")) }
        delay(30L)
        backend.cancel("cancel-me")
        val result = pending.await()

        assertEquals(setOf("cancel-me"), engine.cancelledJobs)
        assertFalse(result.completed)
        assertTrue(result.text.isEmpty())
        backend.releaseResources()
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

    private class RecordingLiteRtEngine(
        private val delayMillis: Long = 0L,
    ) : LiteRtEngineClient {
        var initializedBackend: BackendKind? = null
        var lastPrompt: String = ""
        var lastSamplingPolicy: LiteRtSamplingPolicy? = null
        var lastMaximumOutputTokens: Int = 0
        var closed: Boolean = false
        val cancelledJobs = linkedSetOf<String>()
        val maximumConcurrentGenerations = AtomicInteger(0)
        private val activeGenerations = AtomicInteger(0)

        override fun initialize(
            model: InstalledAiModel,
            backend: BackendKind,
            cacheDirectory: File,
        ) {
            initializedBackend = backend
        }

        override suspend fun generate(
            jobId: String,
            systemInstruction: String,
            prompt: String,
            samplingPolicy: LiteRtSamplingPolicy,
            maximumOutputTokens: Int,
        ): String {
            lastPrompt = prompt
            lastSamplingPolicy = samplingPolicy
            lastMaximumOutputTokens = maximumOutputTokens
            val active = activeGenerations.incrementAndGet()
            maximumConcurrentGenerations.updateAndGet { previous -> maxOf(previous, active) }
            try {
                if (delayMillis > 0L) delay(delayMillis)
                return if (jobId in cancelledJobs) "" else "They agreed to the plan."
            } finally {
                activeGenerations.decrementAndGet()
            }
        }

        override fun cancel(jobId: String) {
            cancelledJobs += jobId
        }

        override fun close() {
            closed = true
        }
    }
}
