package com.echolink.mobile

data class DocumentJobRequest(
    val jobId: String,
    val source: String,
    val selectionStart: Int,
    val selectionEnd: Int,
    val tone: WritingTone,
    val qualityMode: QualityMode,
    val sourceApplication: String?,
)

class PersistentDocumentCorrectionRunner(
    private val store: AiJobStore,
    private val pipeline: DocumentCorrectionPipeline,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    suspend fun run(
        request: DocumentJobRequest,
        onParagraphCompleted: suspend (CorrectedParagraph) -> Unit = {},
        onProgress: (completed: Int, total: Int) -> Unit = { _, _ -> },
    ): DocumentCorrectionResult {
        require(request.selectionStart in 0..request.source.length)
        require(request.selectionEnd in 0..request.source.length)
        require(request.selectionStart <= request.selectionEnd)

        val normalized = LosslessSourceNormalizer.normalize(request.source)
        val protected = ProtectedSpanCodec.protect(normalized.text)
        var job = AiCorrectionJob(
            jobId = request.jobId,
            originalText = request.source,
            selectionStart = request.selectionStart,
            selectionEnd = request.selectionEnd,
            operation = operationFor(request.tone),
            tone = request.tone,
            qualityMode = request.qualityMode,
            sourceApplication = request.sourceApplication,
            paragraphBoundaries = paragraphBoundaries(normalized.text),
            protectedSpanManifest = protected.placeholders,
            createdAtEpochMs = clock(),
            updatedAtEpochMs = clock(),
        )
        store.save(job)

        return try {
            val completed = mutableListOf<String>()
            val result = pipeline.process(
                request = DocumentCorrectionRequest(
                    jobId = request.jobId,
                    source = request.source,
                    tone = request.tone,
                    qualityMode = request.qualityMode,
                ),
                onParagraphCompleted = { paragraph ->
                    completed += paragraph.text
                    job = job.copy(
                        candidateRevisions = completed.toList(),
                        updatedAtEpochMs = clock(),
                    )
                    store.save(job)
                    onParagraphCompleted(paragraph)
                },
                onProgress = onProgress,
            )
            job = job.copy(
                candidateRevisions = result.paragraphs.map(CorrectedParagraph::text),
                status = AiJobStatus.COMPLETED,
                updatedAtEpochMs = clock(),
                completedAtEpochMs = clock(),
            )
            store.save(job)
            result
        } catch (failure: Throwable) {
            job = job.copy(
                status = AiJobStatus.FAILED,
                updatedAtEpochMs = clock(),
                completedAtEpochMs = clock(),
            )
            store.save(job)
            throw failure
        }
    }

    suspend fun restore(jobId: String): AiCorrectionJob? = store.load(jobId)

    private fun operationFor(tone: WritingTone): WritingOperation = when (tone) {
        WritingTone.PROOFREAD -> WritingOperation.CORRECT_ONLY
        WritingTone.IMPROVE -> WritingOperation.POLISH_PRESERVE_VOICE
        else -> WritingOperation.TONE_TRANSFORM
    }

    private fun paragraphBoundaries(value: String): List<Int> {
        val boundaries = mutableListOf(0)
        Regex("\\n\\s*\\n").findAll(value).forEach { match ->
            boundaries += match.range.last + 1
        }
        if (boundaries.lastOrNull() != value.length) boundaries += value.length
        return boundaries.distinct()
    }
}
