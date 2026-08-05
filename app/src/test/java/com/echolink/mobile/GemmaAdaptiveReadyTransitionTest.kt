package com.echolink.mobile

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GemmaAdaptiveReadyTransitionTest {
    @Test
    fun durableCpuReadyRunsGpuProofThenReturnsThePromotedBackend() {
        val cpu = AiRuntimeSelection(GemmaModelCatalog.fast, BackendKind.LITERT_CPU)
        val gpu = AiRuntimeSelection(GemmaModelCatalog.fast, BackendKind.LITERT_GPU)
        val installed = InstalledAiModel(GemmaModelCatalog.fast, File("gemma-fast.litertlm"))
        var selected = cpu
        val events = mutableListOf<String>()

        val resolved = resolveGemmaReadySelection(
            completedSelection = cpu,
            installed = installed,
            selectedRuntime = { selected },
            qualifyGpu = {
                events += "gpu-proof"
                selected = gpu
            },
        )

        assertEquals(listOf("gpu-proof"), events)
        assertEquals(gpu, resolved)
    }

    @Test
    fun staleCompletionNeverRunsGpuProofOrClaimsReady() {
        val completed = AiRuntimeSelection(GemmaModelCatalog.fast, BackendKind.LITERT_CPU)
        val current = AiRuntimeSelection(GemmaModelCatalog.thorough, BackendKind.LITERT_CPU)
        val installed = InstalledAiModel(GemmaModelCatalog.fast, File("gemma-fast.litertlm"))
        var gpuProofCalled = false

        val resolved = resolveGemmaReadySelection(
            completedSelection = completed,
            installed = installed,
            selectedRuntime = { current },
            qualifyGpu = { gpuProofCalled = true },
        )

        assertNull(resolved)
        assertTrue(!gpuProofCalled)
    }

    @Test
    fun gpuRejectionReusesTheSameVerifiedThoroughBytesForCpuFallback() {
        val rejectedGpu = AiRuntimeSelection(
            GemmaModelCatalog.thorough,
            BackendKind.LITERT_GPU,
        )
        val cpuFallback = AiRuntimeSelection(
            GemmaModelCatalog.thorough,
            BackendKind.LITERT_CPU,
        )
        val fastFallback = AiRuntimeSelection(
            GemmaModelCatalog.fast,
            BackendKind.LITERT_CPU,
        )

        assertTrue(shouldPreserveRejectedGemmaModel(rejectedGpu, cpuFallback))
        assertTrue(!shouldPreserveRejectedGemmaModel(rejectedGpu, fastFallback))
        assertTrue(!shouldPreserveRejectedGemmaModel(rejectedGpu, rejectedGpu))
    }
}
