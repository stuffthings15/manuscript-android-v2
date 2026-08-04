package com.echolink.mobile

import java.util.Base64
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AiJobRepositoryTest {
    @Test
    fun jobTextAndCandidatesAreEncryptedBeforeRoomStorageAndRestoreExactly() = runBlocking {
        val dao = InMemoryDao()
        val repository = AiJobRepository(dao, ReversingCipher())
        val job = sampleJob()

        repository.save(job)

        val stored = requireNotNull(dao.row)
        assertFalse(stored.originalCiphertext.contains(job.originalText))
        assertFalse(stored.candidatesCiphertext.contains("Corrected paragraph."))
        assertEquals(job, repository.load(job.jobId))
    }

    @Test
    fun immutableSourceRevisionCannotBeSilentlyOverwritten() = runBlocking {
        val dao = InMemoryDao()
        val repository = AiJobRepository(dao, ReversingCipher())
        repository.save(sampleJob())

        val changed = sampleJob().copy(originalText = "User changed this text.")

        assertThrows(ImmutableSourceRevisionException::class.java) {
            runBlocking { repository.save(changed) }
        }
        assertEquals("Original private text.", repository.load("job-1")?.originalText)
    }

    @Test
    fun retentionDeletesOnlyCompletedTemporaryJobsOlderThanCutoff() = runBlocking {
        val dao = InMemoryDao()
        val repository = AiJobRepository(dao, ReversingCipher())
        repository.save(
            sampleJob().copy(
                status = AiJobStatus.COMPLETED,
                completedAtEpochMs = 1_000L,
            ),
        )

        val removed = repository.deleteCompletedBefore(2_000L)

        assertEquals(1, removed)
        assertTrue(dao.row == null)
    }

    private fun sampleJob() = AiCorrectionJob(
        jobId = "job-1",
        originalText = "Original private text.",
        selectionStart = 0,
        selectionEnd = 22,
        operation = WritingOperation.CORRECT_ONLY,
        tone = WritingTone.PROOFREAD,
        qualityMode = QualityMode.THOROUGH,
        sourceApplication = "com.example.messages",
        paragraphBoundaries = listOf(0, 22),
        candidateRevisions = listOf("Corrected paragraph."),
        acceptedChangeIds = setOf("change-1"),
        rejectedChangeIds = emptySet(),
        status = AiJobStatus.ACTIVE,
        createdAtEpochMs = 100L,
        updatedAtEpochMs = 200L,
        completedAtEpochMs = null,
        reviewParagraphIndex = 1,
        reviewScrollY = 120,
    )

    private class ReversingCipher : AiJobTextCipher {
        override fun encrypt(plainText: String): EncryptedJobText {
            val reversed = plainText.reversed().toByteArray()
            return EncryptedJobText(
                ivBase64 = Base64.getEncoder().encodeToString(byteArrayOf(1, 2, 3)),
                ciphertextBase64 = Base64.getEncoder().encodeToString(reversed),
            )
        }

        override fun decrypt(value: EncryptedJobText): String =
            String(Base64.getDecoder().decode(value.ciphertextBase64)).reversed()
    }

    private class InMemoryDao : AiJobDao {
        var row: AiJobEntity? = null

        override suspend fun findById(jobId: String): AiJobEntity? =
            row?.takeIf { it.jobId == jobId }

        override suspend fun upsert(entity: AiJobEntity) {
            row = entity
        }

        override suspend fun deleteCompletedBefore(
            completedStatus: String,
            cutoffEpochMs: Long,
        ): Int {
            val current = row ?: return 0
            if (
                current.status == completedStatus &&
                (current.completedAtEpochMs ?: Long.MAX_VALUE) < cutoffEpochMs
            ) {
                row = null
                return 1
            }
            return 0
        }
    }
}
