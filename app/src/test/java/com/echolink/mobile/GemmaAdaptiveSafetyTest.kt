package com.echolink.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GemmaAdaptiveSafetyTest {
    private val gib = 1_073_741_824L
    private val capable = AiDeviceProfile(
        totalRamBytes = 12L * gib,
        availableRamBytes = 5L * gib,
        usableStorageBytes = 20L * gib,
        availableProcessors = 8,
        is64Bit = true,
        supportedAbis = setOf("arm64-v8a"),
        androidApi = 35,
        hasVulkanHardware = true,
        severeThermalState = false,
        lowMemoryDevice = false,
        deviceFamily = AiDeviceFamily.GALAXY_S23,
    )

    @Test
    fun qualificationAndRejectionKeysAreExactToArtifactAndBackend() {
        val fastCpu = GemmaAdaptiveQualificationRecords.qualificationKey(
            GemmaModelCatalog.fast,
            BackendKind.LITERT_CPU,
        )
        val fastGpu = GemmaAdaptiveQualificationRecords.qualificationKey(
            GemmaModelCatalog.fast,
            BackendKind.LITERT_GPU,
        )
        val thoroughGpu = GemmaAdaptiveQualificationRecords.qualificationKey(
            GemmaModelCatalog.thorough,
            BackendKind.LITERT_GPU,
        )

        assertNotEquals(fastCpu, fastGpu)
        assertNotEquals(fastGpu, thoroughGpu)
        assertNotEquals(
            GemmaAdaptiveQualificationRecords.rejectionKey(
                GemmaModelCatalog.fast,
                BackendKind.LITERT_GPU,
            ),
            GemmaAdaptiveQualificationRecords.rejectionKey(
                GemmaModelCatalog.thorough,
                BackendKind.LITERT_GPU,
            ),
        )
        assertTrue(
            GemmaAdaptiveQualificationRecords.isQualified(
                GemmaModelCatalog.fast.sha256,
                GemmaModelCatalog.fast,
                BackendKind.LITERT_CPU,
            ),
        )
        assertFalse(
            GemmaAdaptiveQualificationRecords.isQualified(
                GemmaModelCatalog.thorough.sha256,
                GemmaModelCatalog.fast,
                BackendKind.LITERT_CPU,
            ),
        )
    }

    @Test
    fun exactFastGpuRejectionFallsBackToCpuAndNeverSelectsRejectedGpu() {
        assertEquals(
            AiRuntimeSelection(
                LegacyQwenModelCatalog.forTier(Qwen3Tier.BALANCED),
                BackendKind.LEGACY_LLAMA_CPU,
            ),
            AiHardwarePolicy.select(
                capable,
                QualityMode.FAST,
                BackendQualifications(
                    gemmaFastGpuQualified = true,
                    gemmaFastGpuRejected = true,
                ),
            ),
        )
    }

    @Test
    fun qualificationForOneArtifactNeverPromotesAnotherArtifact() {
        assertEquals(
            AiRuntimeSelection(
                LegacyQwenModelCatalog.forTier(Qwen3Tier.ADVANCED),
                BackendKind.LEGACY_LLAMA_CPU,
            ),
            AiHardwarePolicy.select(
                capable,
                QualityMode.THOROUGH,
                BackendQualifications(gemmaFastGpuQualified = true),
            ),
        )
    }

    @Test
    fun rejectingBothFastBackendsUsesTheAlwaysAvailableLegacyFallback() {
        assertEquals(
            AiRuntimeSelection(
                LegacyQwenModelCatalog.forTier(Qwen3Tier.BALANCED),
                BackendKind.LEGACY_LLAMA_CPU,
            ),
            AiHardwarePolicy.select(
                capable,
                QualityMode.FAST,
                BackendQualifications(
                    gemmaFastCpuRejected = true,
                    gemmaFastGpuQualified = true,
                    gemmaFastGpuRejected = true,
                ),
            ),
        )
    }

    @Test
    fun runtimeGateKeepsOneSelectionUntilThatExactSelectionUnlocks() {
        val fastCpu = AiRuntimeSelection(GemmaModelCatalog.fast, BackendKind.LITERT_CPU)
        val thoroughCpu = AiRuntimeSelection(GemmaModelCatalog.thorough, BackendKind.LITERT_CPU)
        val gate = GemmaRuntimeSelectionGate()

        gate.lock(fastCpu)

        assertEquals(fastCpu, gate.select { thoroughCpu })
        assertFalse(gate.unlock(thoroughCpu))
        assertEquals(fastCpu, gate.select { thoroughCpu })
        assertTrue(gate.unlock(fastCpu))
        assertEquals(thoroughCpu, gate.select { thoroughCpu })
    }

    @Test
    fun persistedLockAcceptsOnlyCurrentExactGemmaArtifactAndBackend() {
        assertEquals(
            AiRuntimeSelection(GemmaModelCatalog.fast, BackendKind.LITERT_GPU),
            GemmaAdaptiveQualificationRecords.lockedSelection(
                GemmaModelCatalog.fast.sha256,
                BackendKind.LITERT_GPU.name,
            ),
        )
        assertNull(
            GemmaAdaptiveQualificationRecords.lockedSelection(
                "f".repeat(64),
                BackendKind.LITERT_GPU.name,
            ),
        )
        assertNull(
            GemmaAdaptiveQualificationRecords.lockedSelection(
                GemmaModelCatalog.fast.sha256,
                BackendKind.LEGACY_LLAMA_CPU.name,
            ),
        )
    }
}
