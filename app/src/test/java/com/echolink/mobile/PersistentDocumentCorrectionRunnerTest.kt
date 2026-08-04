package com.echolink.mobile

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PersistentDocumentCorrectionRunnerTest {
    @Test
    fun savesImmutableJobBeforeInferenceAndPersistsEveryCompletedParagraph() = runBlocking {
        val store = RecordingJobStore()
        val backend = EchoBackend()
        val runner = PersistentDocumentCorrectionRunner(
            store = store,
            pipeline = DocumentCorrectionPipeline(backend),
            clock = { 1_000L + store.saved.size },
        )
        val progress = mutableListOf<Pair<Int, Int>>()
        val source = "Email Alex Rivera.\n\nSecond paragraph."

        val result = runner.run(
            DocumentJobRequest(
                jobId = "document-job",
                source = source,
                selectionStart = 0,
                selectionEnd = source.length,
                tone = WritingTone.PROOFREAD,
                qualityMode = QualityMode.FAST,
                sourceApplication = "com.example.messages",
            ),
        ) { completed, total -> progress += completed to total }

        assertEquals(source, result.text)
        assertEquals(AiJobStatus.ACTIVE, store.saved.first().status)
        assertTrue(store.saved.first().candidateRevisions.isEmpty())
        assertEquals(
            listOf("Email Alex Rivera.", "Second paragraph."),
            store.saved[2].candidateRevisions,
        )
        assertEquals(AiJobStatus.COMPLETED, store.saved.last().status)
        assertEquals(listOf(1 to 2, 2 to 2), progress)
        assertTrue(store.saved.all { it.originalText == store.saved.first().originalText })
        assertTrue(store.saved.first().protectedSpanManifest.isNotEmpty())
    }

    @Test
    fun failedCorrectionIsPersistedAsFailedWithoutReplacingSource() = runBlocking {
        val store = RecordingJobStore()
        val runner = PersistentDocumentCorrectionRunner(
            store = store,
            pipeline = DocumentCorrectionPipeline(FailingBackend()),
            clock = { 2_000L + store.saved.size },
        )

        runCatching {
            runner.run(
                DocumentJobRequest(
                    jobId = "failed-job",
                    source = "Dogs is loyal.",
                    selectionStart = 0,
                    selectionEnd = 14,
                    tone = WritingTone.PROOFREAD,
                    qualityMode = QualityMode.FAST,
                    sourceApplication = null,
                ),
            )
        }

        assertEquals(AiJobStatus.FAILED, store.saved.last().status)
        assertEquals("Dogs is loyal.", store.saved.last().originalText)
        assertTrue(store.saved.last().candidateRevisions.isEmpty())
    }

    private class RecordingJobStore : AiJobStore {
        val saved = mutableListOf<AiCorrectionJob>()

        override suspend fun save(job: AiCorrectionJob) {
            saved += job
        }

        override suspend fun load(jobId: String): AiCorrectionJob? =
            saved.lastOrNull { it.jobId == jobId }

        override suspend fun deleteCompletedBefore(cutoffEpochMs: Long): Int = 0
    }

    private class EchoBackend : LocalRewriteBackend {
        override val backendId = "echo"
        override suspend fun initialize(model: InstalledAiModel, backend: BackendKind) = Unit
        override suspend fun generate(request: LocalGenerationRequest) =
            LocalGenerationResult(request.jobId, request.firstPassCandidate ?: request.originalTarget, true)
        override suspend fun cancel(jobId: String) = Unit
        override fun releaseResources() = Unit
    }

    private class FailingBackend : LocalRewriteBackend {
        override val backendId = "failing"
        override suspend fun initialize(model: InstalledAiModel, backend: BackendKind) = Unit
        override suspend fun generate(request: LocalGenerationRequest) =
            LocalGenerationResult(request.jobId, "", false)
        override suspend fun cancel(jobId: String) = Unit
        override fun releaseResources() = Unit
    }
}
