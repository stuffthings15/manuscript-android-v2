package com.echolink.mobile

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DocumentCorrectionPipelineTest {
    @Test
    fun allParagraphsProcessAutomaticallyWithProtectedFactsAndProgress() = runBlocking {
        val primary = ScriptedBackend { request ->
            when (request.operation) {
                WritingOperation.CORRECT_ONLY ->
                    request.originalTarget
                        .replace("we was", "We were")
                        .replace("ready", "ready.")
                WritingOperation.REVIEW_CANDIDATE -> requireNotNull(request.firstPassCandidate)
                else -> request.originalTarget
            }
        }
        val progress = mutableListOf<Pair<Int, Int>>()
        val pipeline = DocumentCorrectionPipeline(primaryBackend = primary)
        val source =
            "Email Alex Rivera at alex@example.com on July 14, 2027.\n\nwe was ready"

        val result = pipeline.process(
            DocumentCorrectionRequest(
                jobId = "document-1",
                source = source,
                tone = WritingTone.PROOFREAD,
                qualityMode = QualityMode.FAST,
            ),
        ) { completed, total -> progress += completed to total }

        assertEquals(
            "Email Alex Rivera at alex@example.com on July 14, 2027.\n\nWe were ready.",
            result.text,
        )
        assertEquals(listOf(1 to 2, 2 to 2), progress)
        assertTrue(primary.requests.first().originalTarget.contains("[[MS_"))
        assertFalse(result.text.contains("[[MS_"))
        assertTrue(result.paragraphs.all { it.validated })
    }

    @Test
    fun toneUsesCorrectionThenTransformationThenFreshReview() = runBlocking {
        val primary = ScriptedBackend { request ->
            when (request.operation) {
                WritingOperation.CORRECT_ONLY -> "We were ready."
                WritingOperation.TONE_TRANSFORM -> "We were fully prepared."
                WritingOperation.REVIEW_CANDIDATE -> requireNotNull(request.firstPassCandidate)
                WritingOperation.POLISH_PRESERVE_VOICE -> error("Unexpected operation")
            }
        }
        val pipeline = DocumentCorrectionPipeline(primaryBackend = primary)

        val result = pipeline.process(
            DocumentCorrectionRequest(
                jobId = "tone-1",
                source = "we was ready",
                tone = WritingTone.FORMAL,
                qualityMode = QualityMode.THOROUGH,
            ),
        )

        assertEquals("We were fully prepared.", result.text)
        assertEquals(
            listOf(
                WritingOperation.CORRECT_ONLY,
                WritingOperation.TONE_TRANSFORM,
                WritingOperation.REVIEW_CANDIDATE,
            ),
            primary.requests.map(LocalGenerationRequest::operation),
        )
        assertTrue(primary.requests.last().firstPassCandidate == "We were fully prepared.")
    }

    @Test
    fun improveMapsDirectlyToPolishAndThenRiskReview() = runBlocking {
        val primary = ScriptedBackend { request ->
            when (request.operation) {
                WritingOperation.POLISH_PRESERVE_VOICE -> "We were ready."
                WritingOperation.REVIEW_CANDIDATE -> requireNotNull(request.firstPassCandidate)
                else -> error("Unexpected operation: ${request.operation}")
            }
        }
        val pipeline = DocumentCorrectionPipeline(primaryBackend = primary)

        val result = pipeline.process(
            DocumentCorrectionRequest(
                jobId = "improve-1",
                source = "we was ready",
                tone = WritingTone.IMPROVE,
                qualityMode = QualityMode.THOROUGH,
            ),
        )

        assertEquals("We were ready.", result.text)
        assertEquals(
            listOf(
                WritingOperation.POLISH_PRESERVE_VOICE,
                WritingOperation.REVIEW_CANDIDATE,
            ),
            primary.requests.map(LocalGenerationRequest::operation),
        )
    }

    @Test
    fun hardFailureGetsOneStrictRetryThenQualifiedFallback() = runBlocking {
        val primary = ScriptedBackend { "Dogs are loyal 7 days." }
        val fallback = ScriptedBackend { request ->
            request.originalTarget.replace("dogs is", "Dogs are")
        }
        val pipeline = DocumentCorrectionPipeline(
            primaryBackend = primary,
            fallbackBackend = fallback,
        )

        val result = pipeline.process(
            DocumentCorrectionRequest(
                jobId = "fallback-1",
                source = "dogs is loyal",
                tone = WritingTone.PROOFREAD,
                qualityMode = QualityMode.FAST,
            ),
        )

        assertEquals("Dogs are loyal", result.text)
        assertEquals(2, primary.requests.size)
        assertFalse(primary.requests.first().strictRetry)
        assertTrue(primary.requests.last().strictRetry)
        assertEquals(1, fallback.requests.size)
        assertTrue(fallback.requests.single().strictRetry)
        assertFalse(result.text.contains('\u2014'))
    }

    @Test
    fun thoroughModeReviewsEveryParagraphEvenWhenFirstPassIsUnchanged() = runBlocking {
        val primary = ScriptedBackend { request ->
            request.firstPassCandidate ?: request.originalTarget
        }
        val pipeline = DocumentCorrectionPipeline(primaryBackend = primary)

        pipeline.process(
            DocumentCorrectionRequest(
                jobId = "thorough-1",
                source = "First paragraph.\n\nSecond paragraph.",
                tone = WritingTone.PROOFREAD,
                qualityMode = QualityMode.THOROUGH,
            ),
        )

        assertEquals(
            2,
            primary.requests.count { it.operation == WritingOperation.REVIEW_CANDIDATE },
        )
    }

    @Test
    fun rejectedGrammarQualityGetsOneStrictAiReviewRetry() = runBlocking {
        val primary = ScriptedBackend { request ->
            when (request.operation) {
                WritingOperation.CORRECT_ONLY ->
                    if (request.strictRetry) {
                        request.originalTarget
                    } else {
                        "The amount of columns was too many. There was fewer information."
                    }
                WritingOperation.REVIEW_CANDIDATE ->
                    requireNotNull(request.firstPassCandidate)
                else -> error("Unexpected operation")
            }
        }
        val pipeline = DocumentCorrectionPipeline(
            primaryBackend = primary,
            acceptsGeneratedGrammar = { text ->
                "amount of columns" !in text && "fewer information" !in text
            },
            prepareGrammarRetryCandidate = { text ->
                text
                    .replace("amount of columns was too many", "number of columns was too large")
                    .replace("fewer information", "less information")
            },
        )

        val result = pipeline.process(
            DocumentCorrectionRequest(
                jobId = "quality-retry",
                source = "The amount of columns were too many. There was fewer information.",
                tone = WritingTone.PROOFREAD,
                qualityMode = QualityMode.THOROUGH,
            ),
        )

        assertEquals(
            "The number of columns was too large. There was less information.",
            result.text,
        )
        val reviews = primary.requests.filter {
            it.operation == WritingOperation.REVIEW_CANDIDATE
        }
        assertEquals(1, reviews.size)
        assertFalse(reviews.single().strictRetry)
        val strictRewrite = primary.requests.single {
            it.operation == WritingOperation.CORRECT_ONLY && it.strictRetry
        }
        assertEquals(null, strictRewrite.previousContext)
        assertEquals(null, strictRewrite.nextContext)
        assertEquals(
            "The number of columns was too large. There was less information.",
            strictRewrite.originalTarget,
        )
    }

    @Test
    fun lowClassifierConfidenceIsOnlyARiskSignalAfterStrictAiReview() = runBlocking {
        val corrected =
            "Participants can read the brief and may submit attendance. " +
                "The first link is public; the second link requires a secure code."
        val primary = ScriptedBackend { request ->
            when (request.operation) {
                WritingOperation.CORRECT_ONLY ->
                    if (request.strictRetry) request.originalTarget else corrected
                WritingOperation.REVIEW_CANDIDATE ->
                    requireNotNull(request.firstPassCandidate)
                else -> error("Unexpected operation")
            }
        }
        val pipeline = DocumentCorrectionPipeline(
            primaryBackend = primary,
            acceptsGeneratedGrammar = { false },
            acceptsStrictlyReviewedGrammar = { text -> text == corrected },
            prepareGrammarRetryCandidate = { it },
        )

        val result = pipeline.process(
            DocumentCorrectionRequest(
                jobId = "classifier-risk-only",
                source =
                    "Participants can read the brief and they may submit attendance. " +
                        "The first link is public, the second link require a secure code.",
                tone = WritingTone.PROOFREAD,
                qualityMode = QualityMode.THOROUGH,
            ),
        )

        assertEquals(corrected, result.text)
        assertEquals(
            1,
            primary.requests.count {
                it.operation == WritingOperation.CORRECT_ONLY && it.strictRetry
            },
        )
    }

    @Test
    fun grammarRetryRepairsRestoredListTextAndReencodesProtectedSpans() = runBlocking {
        val primary = ScriptedBackend { request ->
            request.firstPassCandidate ?: request.originalTarget
        }
        val pipeline = DocumentCorrectionPipeline(
            primaryBackend = primary,
            acceptsGeneratedGrammar = { text ->
                "labeling every carton" !in text &&
                    "he must update" !in text
            },
            prepareGrammarRetryCandidate = GrammarSafetyNet::repair,
        )
        val source =
            "- Jonah: deliver the kits, labeling every carton, and he must update the count.\n" +
                "- Mira: send the log to ops@example.org."

        val result = pipeline.process(
            DocumentCorrectionRequest(
                jobId = "protected-list-retry",
                source = source,
                tone = WritingTone.PROOFREAD,
                qualityMode = QualityMode.THOROUGH,
            ),
        )

        assertEquals(
            "- Jonah: deliver the kits, label every carton, and update the count.\n" +
                "- Mira: send the log to ops@example.org.",
            result.text,
        )
        val strictReview = primary.requests.last {
            it.operation == WritingOperation.CORRECT_ONLY && it.strictRetry
        }
        assertTrue(strictReview.originalTarget.contains("[[MS_MARKDOWN_PREFIX_"))
        assertTrue(strictReview.originalTarget.contains("[[MS_EMAIL_"))
        assertFalse(result.text.contains("[[MS_"))
    }

    @Test
    fun nonProofreadTransformationCannotPretendToRunByReturningSourceUnchanged() = runBlocking {
        val primary = ScriptedBackend { request ->
            when (request.operation) {
                WritingOperation.CORRECT_ONLY -> request.originalTarget
                WritingOperation.TONE_TRANSFORM -> request.originalTarget
                else -> request.firstPassCandidate ?: request.originalTarget
            }
        }
        val fallback = ScriptedBackend { request -> request.originalTarget }

        val failure = runCatching {
            DocumentCorrectionPipeline(primary, fallback).process(
                DocumentCorrectionRequest(
                    jobId = "unchanged-tone",
                    source = "Please send the report.",
                    tone = WritingTone.FRIENDLY,
                    qualityMode = QualityMode.FAST,
                ),
            )
        }.exceptionOrNull()

        assertTrue(failure is ParagraphCorrectionException)
    }

    @Test
    fun failedReviewerGetsOneStrictRetryBeforeFallback() = runBlocking {
        val primary = ScriptedBackend { request ->
            when (request.operation) {
                WritingOperation.CORRECT_ONLY ->
                    if (request.strictRetry) {
                        "Dogs are loyal 7 days."
                    } else {
                        "Dogs remain loyal."
                    }
                WritingOperation.REVIEW_CANDIDATE -> "Dogs are loyal 7 days."
                else -> error("Unexpected operation")
            }
        }
        val fallback = ScriptedBackend { "Dogs are loyal." }
        val pipeline = DocumentCorrectionPipeline(primary, fallback)

        val result = pipeline.process(
            DocumentCorrectionRequest(
                jobId = "review-fallback",
                source = "dogs is loyal",
                tone = WritingTone.PROOFREAD,
                qualityMode = QualityMode.THOROUGH,
            ),
        )

        val reviewerRequests = primary.requests.filter {
            it.operation == WritingOperation.REVIEW_CANDIDATE
        }
        assertEquals(1, reviewerRequests.size)
        assertFalse(reviewerRequests.single().strictRetry)
        assertEquals(
            1,
            primary.requests.count {
                it.operation == WritingOperation.CORRECT_ONLY && it.strictRetry
            },
        )
        assertEquals(1, fallback.requests.size)
        assertTrue(result.paragraphs.single().usedFallback)
        assertEquals("Dogs are loyal.", result.text)
    }

    @Test
    fun explicitIncorrectPermissionCanBeCorrectedWithoutWeakeningNormalSafety() {
        val contradictorySource =
            "Any volunteer can publish screenshots. This permission is incorrect."
        val corrected =
            "No volunteer may publish screenshots. This permission is incorrect."

        val allowed = AiSafetyValidator.validate(contradictorySource, corrected)
        val blocked = AiSafetyValidator.validate(
            "Any volunteer can publish screenshots.",
            "No volunteer may publish screenshots.",
        )

        assertTrue(allowed.safe)
        assertFalse(blocked.safe)
        assertTrue(SafetyIssue.NEGATION_CHANGED in blocked.hardFailures)
    }

    @Test
    fun imperativeListMayRemoveRepeatedModalWithoutWeakeningTheCommand() {
        val original =
            "[[MS_MARKDOWN_PREFIX_0000]]Jonah: deliver the kits, label every carton, " +
                "and he must update the count."
        val corrected =
            "[[MS_MARKDOWN_PREFIX_0000]]Jonah: deliver the kits, label every carton, " +
                "and update the count."

        val validation = AiSafetyValidator.validate(original, corrected)

        assertTrue(validation.safe)
        assertFalse(SafetyIssue.OBLIGATION_CHANGED in validation.hardFailures)
    }

    @Test
    fun expandedAndContractedNegationHaveTheSameSafetyMeaning() {
        val original = "They hasn't agreed on the format."
        val corrected = "They have not agreed on the format."

        val validation = AiSafetyValidator.validate(original, corrected)

        assertFalse(SafetyIssue.NEGATION_CHANGED in validation.hardFailures)
    }

    @Test
    fun missingApostropheNegationKeepsTheSameSafetyMeaning() {
        val original = "She didnt bring the bank statement."
        val corrected = "She did not bring the bank statement."

        val validation = AiSafetyValidator.validate(original, corrected)

        assertFalse(SafetyIssue.NEGATION_CHANGED in validation.hardFailures)
    }
    @Test
    fun plainNumericFactChangesAreRejected() {
        val unchanged = AiSafetyValidator.validate(
            "Room 12 has 186 registered guests.",
            "Room 12 has 186 registered guests.",
        )
        val changed = AiSafetyValidator.validate(
            "Room 12 has 186 registered guests.",
            "Room 12 has 187 registered guests.",
        )

        assertTrue(unchanged.safe)
        assertFalse(changed.safe)
        assertTrue(SafetyIssue.PROTECTED_SPAN_CHANGED in changed.hardFailures)
    }

    @Test
    fun writtenNumberFactsCannotChange() {
        val unchanged = AiSafetyValidator.validate(
            "Bring one phone and two projectors.",
            "Bring one phone and two projectors.",
        )
        val changed = AiSafetyValidator.validate(
            "Bring one phone and two projectors.",
            "Bring one phone and three projectors.",
        )

        assertTrue(unchanged.safe)
        assertFalse(changed.safe)
        assertTrue(SafetyIssue.PROTECTED_SPAN_CHANGED in changed.hardFailures)
    }

    @Test
    fun lineStructureChangesAreRejectedBeforeFinalRestoration() {
        val validation = AiSafetyValidator.validate(
            "- First task\n- Second task",
            "- First task - Second task",
        )

        assertFalse(validation.safe)
        assertTrue(SafetyIssue.LINE_STRUCTURE_CHANGED in validation.hardFailures)
    }

    @Test
    fun failedValidationReportsIssueNamesWithoutIncludingUserText() = runBlocking {
        // A truncated answer still fails the whole job (broken engine), and
        // the error names the issue without leaking the user's text.
        val longSource =
            "dogs is loyal and the veterinarian said the annual exam include " +
                "shots teeth and a weight check for every dog over two years old"
        val backend = ScriptedBackend { "Dogs." }

        val failure = runCatching {
            DocumentCorrectionPipeline(backend).process(
                DocumentCorrectionRequest(
                    jobId = "diagnostic",
                    source = longSource,
                    tone = WritingTone.PROOFREAD,
                    qualityMode = QualityMode.FAST,
                ),
            )
        }.exceptionOrNull()

        assertTrue(failure is ParagraphCorrectionException)
        assertTrue(failure?.message.orEmpty().contains("TRUNCATED_OUTPUT"))
        assertFalse(failure?.message.orEmpty().contains("dogs is loyal"))
    }

    @Test
    fun factViolatingProofreadKeepsOriginalParagraphInsteadOfFailingTheDocument() = runBlocking {
        val backend = ScriptedBackend { "Dogs are loyal 7 days." }

        val result = DocumentCorrectionPipeline(backend).process(
            DocumentCorrectionRequest(
                jobId = "diagnostic",
                source = "dogs is loyal",
                tone = WritingTone.PROOFREAD,
                qualityMode = QualityMode.FAST,
            ),
        )

        val paragraph = result.paragraphs.single()
        assertEquals("dogs is loyal", paragraph.text)
        assertFalse(paragraph.validated)
        assertTrue(SafetyIssue.PROTECTED_SPAN_CHANGED in paragraph.risks)
    }

    private class ScriptedBackend(
        private val response: (LocalGenerationRequest) -> String,
    ) : LocalRewriteBackend {
        override val backendId: String = "scripted"
        val requests = mutableListOf<LocalGenerationRequest>()

        override suspend fun initialize(model: InstalledAiModel, backend: BackendKind) = Unit

        override suspend fun generate(
            request: LocalGenerationRequest,
        ): LocalGenerationResult {
            requests += request
            return LocalGenerationResult(
                request.jobId,
                response(request),
                completed = true,
            )
        }

        override suspend fun cancel(jobId: String) = Unit

        override fun releaseResources() = Unit
    }
}
