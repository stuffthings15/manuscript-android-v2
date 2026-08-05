package com.echolink.mobile

import androidx.work.Data
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DownloadWorkerAttemptDecoderTest {
    @Test
    fun eachWorkerAcceptsOnlyItsExactFamilyAndWorkerUuid() {
        val gemma = attempt(
            DownloadTargetKey(
                AiModelFamily.GEMMA,
                AiModelTier.FAST,
                BackendKind.LITERT_GPU,
            ),
            UUID.fromString("cd1ab96e-efc3-4594-8f6f-64d05a4783aa"),
        )
        val legacy = attempt(
            DownloadTargetKey(
                AiModelFamily.LEGACY_QWEN,
                AiModelTier.LEGACY_ADVANCED,
                BackendKind.LEGACY_LLAMA_CPU,
            ),
            UUID.fromString("004432b3-78b7-4483-8898-a35b33e361e3"),
        )

        assertEquals(
            gemma,
            DownloadWorkerAttemptDecoder.decodeGemma(
                DownloadWorkContract.encode(gemma),
                gemma.workIdentity.toUuid(),
            ),
        )
        assertEquals(
            legacy,
            DownloadWorkerAttemptDecoder.decodeLegacy(
                DownloadWorkContract.encode(legacy),
                legacy.workIdentity.toUuid(),
            ),
        )
        assertNull(
            DownloadWorkerAttemptDecoder.decodeGemma(
                DownloadWorkContract.encode(legacy),
                legacy.workIdentity.toUuid(),
            ),
        )
        assertNull(
            DownloadWorkerAttemptDecoder.decodeLegacy(
                DownloadWorkContract.encode(gemma),
                gemma.workIdentity.toUuid(),
            ),
        )
    }

    @Test
    fun uuidMismatchMissingExtraAndPrivateFieldsFailBeforeWorkerUse() {
        val workerId = UUID.fromString("94357535-c4b1-4738-a43f-3cc8ead92613")
        val attempt = attempt(
            DownloadTargetKey(
                AiModelFamily.GEMMA,
                AiModelTier.THOROUGH,
                BackendKind.LITERT_CPU,
            ),
            workerId,
        )
        val encoded = DownloadWorkContract.encode(attempt)

        assertNull(
            DownloadWorkerAttemptDecoder.decodeGemma(
                encoded,
                UUID.fromString("901f7037-4c58-4072-93d8-a31cc63ebcc6"),
            ),
        )
        assertNull(
            DownloadWorkerAttemptDecoder.decodeGemma(
                dataFrom(encoded.keyValueMap - DownloadWorkContract.KEY_GENERATION),
                workerId,
            ),
        )
        assertNull(
            DownloadWorkerAttemptDecoder.decodeGemma(
                dataFrom(encoded.keyValueMap + ("prompt" to "private correction text")),
                workerId,
            ),
        )
    }

    @Test
    fun acceptedAttemptsResolveOnlyCanonicalArtifactAndTier() {
        val gemma = attempt(
            DownloadTargetKey(
                AiModelFamily.GEMMA,
                AiModelTier.THOROUGH,
                BackendKind.LITERT_GPU,
            ),
            UUID.fromString("e6751dd3-82c0-46b6-9ee7-b3ae22fb74fc"),
        )
        val legacy = attempt(
            DownloadTargetKey(
                AiModelFamily.LEGACY_QWEN,
                AiModelTier.LEGACY_BALANCED,
                BackendKind.LEGACY_LLAMA_CPU,
            ),
            UUID.fromString("035161fc-6515-4645-959f-ed82b8a3032a"),
        )

        val gemmaDecoded = requireNotNull(
            DownloadWorkerAttemptDecoder.decodeGemma(
                DownloadWorkContract.encode(gemma),
                gemma.workIdentity.toUuid(),
            ),
        )
        val legacyDecoded = requireNotNull(
            DownloadWorkerAttemptDecoder.decodeLegacy(
                DownloadWorkContract.encode(legacy),
                legacy.workIdentity.toUuid(),
            ),
        )

        assertEquals(GemmaModelCatalog.thorough, DownloadTargetCatalog.artifactFor(gemmaDecoded.target))
        assertEquals(Qwen3Tier.BALANCED, DownloadTargetCatalog.qwenTierFor(legacyDecoded.target))
    }

    private fun attempt(target: DownloadTargetKey, id: UUID): DownloadAttempt = DownloadAttempt(
        target = target,
        generation = DownloadGeneration(9L),
        workIdentity = DownloadWorkIdentity.from(id),
    )

    private fun dataFrom(values: Map<String, Any?>): Data = Data.Builder()
        .putAll(values)
        .build()
}
