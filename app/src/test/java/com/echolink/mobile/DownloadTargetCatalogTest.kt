package com.echolink.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class DownloadTargetCatalogTest {
    @Test
    fun everyGemmaSelectionRoundTripsExactArtifactAndBackend() {
        listOf(GemmaModelCatalog.fast, GemmaModelCatalog.thorough).forEach { artifact ->
            listOf(BackendKind.LITERT_CPU, BackendKind.LITERT_GPU).forEach { backend ->
                val selection = AiRuntimeSelection(artifact, backend)
                val target = DownloadTargetCatalog.targetFor(selection)

                assertEquals(selection, DownloadTargetCatalog.selectionFor(target))
                assertEquals(artifact, DownloadTargetCatalog.artifactFor(target))
            }
        }
    }

    @Test
    fun everyLegacyTierRoundTripsExactArtifactAndQwenTier() {
        Qwen3Tier.entries.forEach { tier ->
            val artifact = LegacyQwenModelCatalog.forTier(tier)
            val target = DownloadTargetCatalog.targetFor(
                AiRuntimeSelection(artifact, BackendKind.LEGACY_LLAMA_CPU),
            )

            assertEquals(tier, DownloadTargetCatalog.qwenTierFor(target))
            assertEquals(artifact, DownloadTargetCatalog.artifactFor(target))
            assertEquals(
                AiRuntimeSelection(artifact, BackendKind.LEGACY_LLAMA_CPU),
                DownloadTargetCatalog.selectionFor(target),
            )
        }
    }

    @Test
    fun selectionCannotPairArtifactWithWrongBackendFamily() {
        assertThrows(IllegalArgumentException::class.java) {
            DownloadTargetCatalog.targetFor(
                AiRuntimeSelection(GemmaModelCatalog.fast, BackendKind.LEGACY_LLAMA_CPU),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            DownloadTargetCatalog.targetFor(
                AiRuntimeSelection(GemmaModelCatalog.legacyCompact, BackendKind.LITERT_CPU),
            )
        }
    }

    @Test
    fun selectionRejectsNonCanonicalArtifactMetadataForAnOtherwiseValidTarget() {
        val forgedGemma = GemmaModelCatalog.fast.copy(
            repository = "example.invalid/forged-gemma",
            revision = "1111111111111111111111111111111111111111",
            fileName = "forged-gemma.litertlm",
            sizeBytes = 1L,
            sha256 = "0".repeat(64),
        )
        val forgedLegacy = LegacyQwenModelCatalog.forTier(Qwen3Tier.BALANCED).copy(
            repository = "example.invalid/forged-qwen",
            revision = "2222222222222222222222222222222222222222",
            fileName = "forged-qwen.gguf",
            sizeBytes = 2L,
            sha256 = "1".repeat(64),
        )

        listOf(
            AiRuntimeSelection(forgedGemma, BackendKind.LITERT_CPU),
            AiRuntimeSelection(forgedLegacy, BackendKind.LEGACY_LLAMA_CPU),
        ).forEach { selection ->
            assertThrows(IllegalArgumentException::class.java) {
                DownloadTargetCatalog.targetFor(selection)
            }
        }
    }

    @Test
    fun qwenTierLookupRejectsGemmaTarget() {
        assertThrows(IllegalArgumentException::class.java) {
            DownloadTargetCatalog.qwenTierFor(
                DownloadTargetKey(
                    AiModelFamily.GEMMA,
                    AiModelTier.FAST,
                    BackendKind.LITERT_CPU,
                ),
            )
        }
    }
}
