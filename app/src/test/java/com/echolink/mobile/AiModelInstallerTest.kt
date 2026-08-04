package com.echolink.mobile

import java.io.ByteArrayInputStream
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AiModelInstallerTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun resumedDownloadUsesRangeAndMatchingEtagBeforeStagingCandidate() {
        val directory = temporaryFolder.newFolder("models")
        File(directory, "${artifact.fileName}.part").writeText("complete-")
        File(directory, "${artifact.fileName}.etag").writeText("\"v1\"")
        val transport = RecordingTransport(
            RangeDownloadResponse(
                statusCode = 206,
                content = ByteArrayInputStream("model".toByteArray()),
                etag = "\"v1\"",
            ),
        )
        val installer = AiModelInstaller(directory, transport)

        val candidate = installer.stage(
            artifact = artifact,
            stopped = { false },
            progress = { _, _ -> },
        )

        assertEquals(9L, transport.requestedOffset)
        assertEquals("\"v1\"", transport.requestedEtag)
        assertEquals("complete-model", candidate.readText())
        assertEquals("test.candidate.litertlm", candidate.name)
        assertFalse(File(directory, "${artifact.fileName}.part").exists())
    }

    @Test
    fun fullResponseAfterPartialDownloadRestartsInsteadOfAppending() {
        val directory = temporaryFolder.newFolder("models")
        File(directory, "${artifact.fileName}.part").writeText("stale-")
        File(directory, "${artifact.fileName}.etag").writeText("\"old\"")
        val transport = RecordingTransport(
            RangeDownloadResponse(
                statusCode = 200,
                content = ByteArrayInputStream("complete-model".toByteArray()),
                etag = "\"new\"",
            ),
        )
        val installer = AiModelInstaller(directory, transport)

        val candidate = installer.stage(artifact, { false }) { _, _ -> }

        assertEquals(6L, transport.requestedOffset)
        assertEquals("\"old\"", transport.requestedEtag)
        assertEquals("complete-model", candidate.readText())
    }

    @Test
    fun badIntegrityDeletesStagedBytesAndNeverReplacesWorkingModel() {
        val directory = temporaryFolder.newFolder("models")
        val active = File(directory, artifact.fileName).apply { writeText("working") }
        val transport = RecordingTransport(
            RangeDownloadResponse(
                statusCode = 200,
                content = ByteArrayInputStream("bad-model-data".toByteArray()),
                etag = "\"bad\"",
            ),
        )
        val installer = AiModelInstaller(directory, transport)

        val failure = runCatching {
            installer.stage(artifact, { false }) { _, _ -> }
        }.exceptionOrNull()

        assertTrue(failure is AiModelIntegrityException)
        assertEquals("working", active.readText())
        assertFalse(File(directory, "${artifact.fileName}.part").exists())
        assertFalse(File(directory, "test.candidate.litertlm").exists())
    }

    @Test
    fun incompatibleCandidateIsDeletedBeforeReplacementDownloadStarts() {
        val directory = temporaryFolder.newFolder("models")
        val incompatible = File(directory, "test.candidate.litertlm")
            .apply { writeText("old-incompatible-candidate") }
        val transport = object : ModelDownloadTransport {
            override fun open(
                url: String,
                startingOffset: Long,
                ifRangeEtag: String?,
            ): RangeDownloadResponse {
                assertFalse(
                    "An invalid candidate must not consume storage during its replacement.",
                    incompatible.exists(),
                )
                return RangeDownloadResponse(
                    statusCode = 200,
                    content = ByteArrayInputStream("complete-model".toByteArray()),
                    etag = "\"replacement\"",
                )
            }
        }

        val candidate = AiModelInstaller(directory, transport)
            .stage(artifact, { false }) { _, _ -> }

        assertEquals("complete-model", candidate.readText())
    }

    @Test
    fun candidateActivatesOnlyAfterSelfTestAndLeavesNoPreviousCopy() {
        val directory = temporaryFolder.newFolder("models")
        val active = File(directory, artifact.fileName).apply { writeText("working") }
        val candidate = File(directory, "test.candidate.litertlm")
            .apply { writeText("complete-model") }
        val installer = AiModelInstaller(
            directory,
            RecordingTransport(
                RangeDownloadResponse(
                    200,
                    ByteArrayInputStream(byteArrayOf()),
                    "\"unused\"",
                ),
            ),
        )

        val rejected = installer.qualifyAndActivate(artifact, candidate) { false }
        assertFalse(rejected.activated)
        assertEquals("working", active.readText())
        assertEquals("complete-model", candidate.readText())

        val accepted = installer.qualifyAndActivate(artifact, candidate) {
            it.readText() == "complete-model"
        }
        assertTrue(accepted.activated)
        assertEquals("complete-model", accepted.activeFile.readText())
        assertFalse(File(directory, "${artifact.fileName}.previous").exists())
    }

    @Test
    fun legacyCandidateIsRenamedWithoutDownloadingAgain() {
        val directory = temporaryFolder.newFolder("legacy-models")
        val legacyCandidate = File(directory, "${artifact.fileName}.candidate")
            .apply { writeText("complete-model") }
        var opened = false
        val transport = object : ModelDownloadTransport {
            override fun open(
                url: String,
                startingOffset: Long,
                ifRangeEtag: String?,
            ): RangeDownloadResponse {
                opened = true
                error("A valid staged AI must not be downloaded again.")
            }
        }

        val candidate = AiModelInstaller(directory, transport)
            .stage(artifact, { false }) { _, _ -> }

        assertFalse(opened)
        assertFalse(legacyCandidate.exists())
        assertEquals("test.candidate.litertlm", candidate.name)
        assertEquals("complete-model", candidate.readText())
    }

    private class RecordingTransport(
        private val response: RangeDownloadResponse,
    ) : ModelDownloadTransport {
        var requestedOffset: Long = -1L
        var requestedEtag: String? = null

        override fun open(
            url: String,
            startingOffset: Long,
            ifRangeEtag: String?,
        ): RangeDownloadResponse {
            requestedOffset = startingOffset
            requestedEtag = ifRangeEtag
            return response
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
    }
}
