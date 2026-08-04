package com.echolink.mobile

import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException
import java.util.concurrent.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AiModelInstallerAdversarialTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun prematureCleanEofPreservesPartialAndEtagForTheExactNextResumeOffset() {
        val directory = temporaryFolder.newFolder("premature-eof")
        val partial = File(directory, "${artifact.fileName}.part").apply {
            writeText("complete-")
        }
        val etag = File(directory, "${artifact.fileName}.etag").apply {
            writeText("\"v1\"")
        }
        val transport = QueueTransport(
            RangeDownloadResponse(206, ByteArrayInputStream("mo".toByteArray()), "\"v1\""),
            RangeDownloadResponse(206, ByteArrayInputStream("del".toByteArray()), "\"v1\""),
        )
        val installer = AiModelInstaller(directory, transport)

        assertThrows(InterruptedIOException::class.java) {
            installer.stage(artifact, { false }) { _, _ -> }
        }

        assertEquals("complete-mo", partial.readText())
        assertEquals("\"v1\"", etag.readText())

        val candidate = installer.stage(artifact, { false }) { _, _ -> }

        assertEquals(listOf(9L, 11L), transport.requests.map { it.offset })
        assertEquals(listOf("\"v1\"", "\"v1\""), transport.requests.map { it.etag })
        assertEquals("complete-model", candidate.readText())
    }

    @Test
    fun alreadyStoppedDownloadNeverOpensTransport() {
        val directory = temporaryFolder.newFolder("already-stopped")
        val transport = CountingTransport()

        assertThrows(InterruptedIOException::class.java) {
            AiModelInstaller(directory, transport).stage(artifact, { true }) { _, _ -> }
        }

        assertEquals(0, transport.openCount)
        assertFalse(File(directory, "${artifact.fileName}.part").exists())
    }

    @Test
    fun readCancellationRemainsPrimaryWhenSyncAndCloseAlsoFailAndAlwaysDisconnects() {
        val directory = temporaryFolder.newFolder("read-cancellation")
        val readCancellation = CancellationException("read cancelled")
        val syncFailure = IOException("sync failed")
        val closeCancellation = CancellationException("close cancelled")
        var disconnected = false
        var syncCalls = 0
        val content = ChunkThenFailureInputStream(
            chunk = "comp".toByteArray(),
            readFailure = readCancellation,
            closeFailure = closeCancellation,
        )
        val transport = QueueTransport(
            RangeDownloadResponse(200, content, "\"read\"") {
                disconnected = true
            },
        )
        val installer = AiModelInstaller(
            modelDirectory = directory,
            transport = transport,
            syncOutput = {
                syncCalls += 1
                throw syncFailure
            },
        )

        val thrown = assertThrows(CancellationException::class.java) {
            installer.stage(artifact, { false }) { _, _ -> }
        }

        assertSame(readCancellation, thrown)
        assertTrue(thrown.suppressed.any { it === syncFailure })
        assertTrue(thrown.suppressed.any { it === closeCancellation })
        assertEquals(1, syncCalls)
        assertTrue(disconnected)
        assertEquals("comp", File(directory, "${artifact.fileName}.part").readText())
        assertEquals("\"read\"", File(directory, "${artifact.fileName}.etag").readText())
    }

    @Test
    fun progressCancellationRemainsPrimaryWhenSyncFailsAndAlwaysDisconnects() {
        val directory = temporaryFolder.newFolder("progress-cancellation")
        val progressCancellation = CancellationException("progress cancelled")
        val syncFailure = IOException("sync failed")
        var disconnected = false
        var syncCalls = 0
        val transport = QueueTransport(
            RangeDownloadResponse(
                200,
                ByteArrayInputStream("complete-model".toByteArray()),
                "\"progress\"",
            ) {
                disconnected = true
            },
        )
        val installer = AiModelInstaller(
            modelDirectory = directory,
            transport = transport,
            syncOutput = {
                syncCalls += 1
                throw syncFailure
            },
        )

        val thrown = assertThrows(CancellationException::class.java) {
            installer.stage(artifact, { false }) { downloaded, _ ->
                if (downloaded > 0L) throw progressCancellation
            }
        }

        assertSame(progressCancellation, thrown)
        assertTrue(thrown.suppressed.any { it === syncFailure })
        assertEquals(1, syncCalls)
        assertTrue(disconnected)
        assertEquals("complete-model", File(directory, "${artifact.fileName}.part").readText())
    }

    @Test
    fun responseCloseCancellationIsNotSwallowedAndAlwaysDisconnects() {
        val directory = temporaryFolder.newFolder("close-cancellation")
        val closeCancellation = CancellationException("close cancelled")
        var disconnected = false
        var syncCalls = 0
        val content = CloseFailureInputStream(
            "complete-model".toByteArray(),
            closeCancellation,
        )
        val transport = QueueTransport(
            RangeDownloadResponse(200, content, "\"close\"") {
                disconnected = true
            },
        )
        val installer = AiModelInstaller(
            modelDirectory = directory,
            transport = transport,
            syncOutput = { syncCalls += 1 },
        )

        val thrown = assertThrows(CancellationException::class.java) {
            installer.stage(artifact, { false }) { _, _ -> }
        }

        assertSame(closeCancellation, thrown)
        assertEquals(1, syncCalls)
        assertTrue(disconnected)
        assertEquals("complete-model", File(directory, "${artifact.fileName}.part").readText())
        assertFalse(File(directory, "test.candidate.litertlm").exists())
    }

    @Test
    fun syncCancellationIsNotSwallowedAndAlwaysDisconnects() {
        val directory = temporaryFolder.newFolder("sync-cancellation")
        val syncCancellation = CancellationException("sync cancelled")
        var disconnected = false
        var syncCalls = 0
        val transport = QueueTransport(
            RangeDownloadResponse(
                200,
                ByteArrayInputStream("complete-model".toByteArray()),
                "\"sync\"",
            ) {
                disconnected = true
            },
        )
        val installer = AiModelInstaller(
            modelDirectory = directory,
            transport = transport,
            syncOutput = {
                syncCalls += 1
                throw syncCancellation
            },
        )

        val thrown = assertThrows(CancellationException::class.java) {
            installer.stage(artifact, { false }) { _, _ -> }
        }

        assertSame(syncCancellation, thrown)
        assertEquals(1, syncCalls)
        assertTrue(disconnected)
        assertEquals("complete-model", File(directory, "${artifact.fileName}.part").readText())
    }

    @Test
    fun changedEtagClosesUnreadBodyAndReopensFromZeroWithoutDuplicateResetProgress() {
        val directory = temporaryFolder.newFolder("etag-restart")
        File(directory, "${artifact.fileName}.part").writeText("complete-")
        File(directory, "${artifact.fileName}.etag").writeText("\"old\"")
        var firstDisconnected = false
        val firstBody = ReadForbiddenInputStream()
        val transport = QueueTransport(
            RangeDownloadResponse(206, firstBody, "\"new\"") {
                firstDisconnected = true
            },
            RangeDownloadResponse(
                200,
                ByteArrayInputStream("complete-model".toByteArray()),
                "\"new\"",
            ),
        )
        val progress = mutableListOf<Long>()

        val candidate = AiModelInstaller(directory, transport).stage(
            artifact = artifact,
            stopped = { false },
        ) { downloaded, _ -> progress += downloaded }

        assertEquals(listOf(9L, 0L), transport.requests.map { it.offset })
        assertEquals(listOf("\"old\"", null), transport.requests.map { it.etag })
        assertFalse(firstBody.readAttempted)
        assertTrue(firstDisconnected)
        assertEquals(1, progress.count { it == 0L })
        assertEquals("complete-model", candidate.readText())
    }

    @Test
    fun resumedFullResponseWithoutEtagClearsOldTagAndPreservesNewPartialForResume() {
        val directory = temporaryFolder.newFolder("full-restart-no-etag")
        val partial = File(directory, "${artifact.fileName}.part").apply { writeText("stale-") }
        val etag = File(directory, "${artifact.fileName}.etag").apply { writeText("\"old\"") }
        val interruption = InterruptedIOException("connection ended")
        var firstDisconnected = false
        val transport = QueueTransport(
            RangeDownloadResponse(
                200,
                ChunkThenFailureInputStream("compl".toByteArray(), interruption),
                null,
            ) {
                firstDisconnected = true
            },
            RangeDownloadResponse(
                206,
                ByteArrayInputStream("ete-model".toByteArray()),
                "\"new\"",
            ),
        )
        val installer = AiModelInstaller(directory, transport)

        val thrown = assertThrows(InterruptedIOException::class.java) {
            installer.stage(artifact, { false }) { _, _ -> }
        }

        assertSame(interruption, thrown)
        assertTrue(firstDisconnected)
        assertEquals("compl", partial.readText())
        assertFalse(etag.exists())

        val candidate = installer.stage(artifact, { false }) { _, _ -> }

        assertEquals(listOf(6L, 5L), transport.requests.map { it.offset })
        assertEquals(listOf("\"old\"", null), transport.requests.map { it.etag })
        assertEquals("complete-model", candidate.readText())
    }

    @Test
    fun zeroOffsetFullResponseWithoutEtagClearsOrphanTagBeforeNewPartialIsWritten() {
        val directory = temporaryFolder.newFolder("zero-offset-orphan-etag")
        val partial = File(directory, "${artifact.fileName}.part").apply { writeBytes(byteArrayOf()) }
        val etag = File(directory, "${artifact.fileName}.etag").apply { writeText("\"old\"") }
        val interruption = InterruptedIOException("connection ended")
        val transport = QueueTransport(
            RangeDownloadResponse(
                200,
                ChunkThenFailureInputStream("comp".toByteArray(), interruption),
                null,
            ),
            RangeDownloadResponse(
                206,
                ByteArrayInputStream("lete-model".toByteArray()),
                "\"new\"",
            ),
        )
        val installer = AiModelInstaller(directory, transport)

        assertThrows(InterruptedIOException::class.java) {
            installer.stage(artifact, { false }) { _, _ -> }
        }

        assertEquals("comp", partial.readText())
        assertFalse(etag.exists())

        val candidate = installer.stage(artifact, { false }) { _, _ -> }

        assertEquals(listOf(0L, 4L), transport.requests.map { it.offset })
        assertEquals(listOf("\"old\"", null), transport.requests.map { it.etag })
        assertEquals("complete-model", candidate.readText())
    }

    @Test
    fun incompatibleCandidateDeletionFailureStopsBeforeNetworkAndPreservesActive() {
        val directory = temporaryFolder.newFolder("candidate-delete")
        val active = File(directory, artifact.fileName).apply { writeText("working") }
        val candidate = File(directory, "test.candidate.litertlm").apply { writeText("bad") }
        val transport = CountingTransport()
        val installer = AiModelInstaller(
            modelDirectory = directory,
            transport = transport,
            deleteFile = { file -> file != candidate && deleteActual(file) },
        )

        assertThrows(AiModelCleanupException::class.java) {
            installer.stage(artifact, { false }) { _, _ -> }
        }

        assertEquals(0, transport.openCount)
        assertEquals("working", active.readText())
        assertEquals("bad", candidate.readText())
    }

    @Test
    fun changedDownloadMarkerDeletionFailureStopsBeforeReopenAndPreservesActive() {
        val directory = temporaryFolder.newFolder("etag-delete")
        val active = File(directory, artifact.fileName).apply { writeText("working") }
        File(directory, "${artifact.fileName}.part").writeText("complete-")
        val etag = File(directory, "${artifact.fileName}.etag").apply { writeText("\"old\"") }
        var disconnected = false
        val unread = ReadForbiddenInputStream()
        val transport = QueueTransport(
            RangeDownloadResponse(206, unread, "\"new\"") { disconnected = true },
        )
        val installer = AiModelInstaller(
            modelDirectory = directory,
            transport = transport,
            deleteFile = { file -> file != etag && deleteActual(file) },
        )

        assertThrows(AiModelCleanupException::class.java) {
            installer.stage(artifact, { false }) { _, _ -> }
        }

        assertEquals(1, transport.requests.size)
        assertFalse(unread.readAttempted)
        assertTrue(disconnected)
        assertEquals("working", active.readText())
        assertTrue(etag.exists())
    }

    @Test
    fun stalePreviousDeletionFailureLeavesActiveAndCandidateUntouched() {
        val directory = temporaryFolder.newFolder("previous-delete")
        val active = File(directory, artifact.fileName).apply { writeText("working") }
        val candidate = File(directory, "test.candidate.litertlm").apply {
            writeText("complete-model")
        }
        val previous = File(directory, "${artifact.fileName}.previous").apply {
            writeText("stale-backup")
        }
        val renames = mutableListOf<Pair<File, File>>()
        val installer = AiModelInstaller(
            modelDirectory = directory,
            transport = CountingTransport(),
            deleteFile = { file -> file != previous && deleteActual(file) },
            renameFile = { source, destination ->
                renames += source to destination
                source.renameTo(destination)
            },
        )

        assertThrows(AiModelCleanupException::class.java) {
            installer.qualifyAndActivate(artifact, candidate) { true }
        }

        assertTrue(renames.isEmpty())
        assertEquals("working", active.readText())
        assertEquals("complete-model", candidate.readText())
        assertEquals("stale-backup", previous.readText())
    }

    @Test
    fun activeBackupRenameFailureLeavesWorkingActiveAndCandidateUntouched() {
        val directory = temporaryFolder.newFolder("active-rename")
        val active = File(directory, artifact.fileName).apply { writeText("working") }
        val candidate = File(directory, "test.candidate.litertlm").apply {
            writeText("complete-model")
        }
        val previous = File(directory, "${artifact.fileName}.previous")
        val installer = AiModelInstaller(
            modelDirectory = directory,
            transport = CountingTransport(),
            renameFile = { source, destination ->
                if (source == active && destination == previous) false else source.renameTo(destination)
            },
        )

        assertThrows(IOException::class.java) {
            installer.qualifyAndActivate(artifact, candidate) { true }
        }

        assertEquals("working", active.readText())
        assertEquals("complete-model", candidate.readText())
        assertFalse(previous.exists())
    }

    @Test
    fun candidateActivationRenameFailureRollsBackAndPreservesOldActive() {
        val directory = temporaryFolder.newFolder("candidate-rename")
        val active = File(directory, artifact.fileName).apply { writeText("working") }
        val candidate = File(directory, "test.candidate.litertlm").apply {
            writeText("complete-model")
        }
        val previous = File(directory, "${artifact.fileName}.previous")
        val installer = AiModelInstaller(
            modelDirectory = directory,
            transport = CountingTransport(),
            renameFile = { source, destination ->
                when {
                    source == candidate && destination == active -> false
                    else -> source.renameTo(destination)
                }
            },
        )

        assertThrows(IOException::class.java) {
            installer.qualifyAndActivate(artifact, candidate) { true }
        }

        assertEquals("working", active.readText())
        assertEquals("complete-model", candidate.readText())
        assertFalse(previous.exists())
    }

    @Test
    fun postCommitPreviousCleanupFailureStillReportsSuccessfulActivation() {
        val directory = temporaryFolder.newFolder("post-commit-cleanup")
        val active = File(directory, artifact.fileName).apply { writeText("working") }
        val candidate = File(directory, "test.candidate.litertlm").apply {
            writeText("complete-model")
        }
        val previous = File(directory, "${artifact.fileName}.previous")
        var candidateCommitted = false
        val installer = AiModelInstaller(
            modelDirectory = directory,
            transport = CountingTransport(),
            deleteFile = { file ->
                if (file == previous && candidateCommitted) false else deleteActual(file)
            },
            renameFile = { source, destination ->
                val renamed = source.renameTo(destination)
                if (renamed && source == candidate && destination == active) {
                    candidateCommitted = true
                }
                renamed
            },
        )

        val activation = installer.qualifyAndActivate(artifact, candidate) { true }

        assertTrue(activation.activated)
        assertEquals(active, activation.activeFile)
        assertEquals("complete-model", active.readText())
        assertEquals("working", previous.readText())
        assertFalse(candidate.exists())
    }

    @Test
    fun postCommitCleanupNeverSwallowsCancellationOrInterruption() {
        listOf(
            CancellationException("cleanup cancelled"),
            InterruptedIOException("cleanup interrupted"),
        ).forEachIndexed { index, expected ->
            val directory = temporaryFolder.newFolder("post-commit-stop-$index")
            val active = File(directory, artifact.fileName).apply { writeText("working") }
            val candidate = File(directory, "test.candidate.litertlm").apply {
                writeText("complete-model")
            }
            val previous = File(directory, "${artifact.fileName}.previous")
            var candidateCommitted = false
            val installer = AiModelInstaller(
                modelDirectory = directory,
                transport = CountingTransport(),
                deleteFile = { file ->
                    if (file == previous && candidateCommitted) throw expected
                    deleteActual(file)
                },
                renameFile = { source, destination ->
                    val renamed = source.renameTo(destination)
                    if (renamed && source == candidate && destination == active) {
                        candidateCommitted = true
                    }
                    renamed
                },
            )

            val thrown = assertThrows(expected.javaClass) {
                installer.qualifyAndActivate(artifact, candidate) { true }
            }

            assertSame(expected, thrown)
            assertEquals("complete-model", active.readText())
            assertEquals("working", previous.readText())
            assertFalse(candidate.exists())
        }
    }

    private data class Request(val offset: Long, val etag: String?)

    private class QueueTransport(vararg responses: RangeDownloadResponse) : ModelDownloadTransport {
        private val responses = responses.toMutableList()
        val requests = mutableListOf<Request>()

        override fun open(
            url: String,
            startingOffset: Long,
            ifRangeEtag: String?,
        ): RangeDownloadResponse {
            requests += Request(startingOffset, ifRangeEtag)
            if (responses.isEmpty()) throw AssertionError("No transport response remains.")
            return responses.removeAt(0)
        }
    }

    private class CountingTransport : ModelDownloadTransport {
        var openCount = 0

        override fun open(
            url: String,
            startingOffset: Long,
            ifRangeEtag: String?,
        ): RangeDownloadResponse {
            openCount += 1
            throw IOException("Transport must not open in this test.")
        }
    }

    private class ChunkThenFailureInputStream(
        private val chunk: ByteArray,
        private val readFailure: Throwable,
        private val closeFailure: Throwable? = null,
    ) : InputStream() {
        private var offset = 0

        override fun read(): Int {
            if (offset < chunk.size) return chunk[offset++].toInt() and 0xff
            throw readFailure
        }

        override fun read(buffer: ByteArray, destinationOffset: Int, length: Int): Int {
            if (offset >= chunk.size) throw readFailure
            val count = minOf(length, chunk.size - offset)
            chunk.copyInto(buffer, destinationOffset, offset, offset + count)
            offset += count
            return count
        }

        override fun close() {
            closeFailure?.let { throw it }
        }
    }

    private class CloseFailureInputStream(
        bytes: ByteArray,
        private val closeFailure: Throwable,
    ) : ByteArrayInputStream(bytes) {
        override fun close() {
            super.close()
            throw closeFailure
        }
    }

    private class ReadForbiddenInputStream : InputStream() {
        var readAttempted = false

        override fun read(): Int {
            readAttempted = true
            throw AssertionError("A changed ETag response body must not be read.")
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            readAttempted = true
            throw AssertionError("A changed ETag response body must not be read.")
        }
    }

    companion object {
        private val artifact = AiModelArtifact(
            family = AiModelFamily.GEMMA,
            tier = AiModelTier.FAST,
            repository = "example/testing",
            revision = "1111111111111111111111111111111111111111",
            fileName = "test.litertlm",
            sizeBytes = 14L,
            sha256 = "3b7f351c58f5b0d956f6755b13056411ee084b5711003b8c678b89d1e042977a",
        )

        private fun deleteActual(file: File): Boolean = !file.exists() || file.delete()
    }
}
