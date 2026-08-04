package com.echolink.mobile

import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalAiRuntimeArchitectureTest {
    @Test
    fun genericBackendReceivesCompleteGenerationContractAndSupportsCancellation() = runBlocking {
        val backend = RecordingBackend()
        val installed = InstalledAiModel(
            artifact = GemmaModelCatalog.fast,
            file = File("gemma-fast.litertlm"),
        )
        val request = LocalGenerationRequest(
            jobId = "job-42",
            operation = WritingOperation.TONE_TRANSFORM,
            previousContext = "Previous paragraph.",
            originalTarget = "dogs is loyal",
            firstPassCandidate = "Dogs are loyal.",
            nextContext = "Next paragraph.",
            qualityMode = QualityMode.THOROUGH,
            strictRetry = true,
            tone = WritingTone.FORMAL,
        )

        backend.initialize(installed, BackendKind.LITERT_CPU)
        val result = backend.generate(request)
        backend.cancel(request.jobId)

        assertEquals("litert-gemma", backend.backendId)
        assertEquals(installed, backend.initializedModel)
        assertEquals(BackendKind.LITERT_CPU, backend.initializedKind)
        assertEquals(request, backend.lastRequest)
        assertEquals("Dogs are loyal.", result.text)
        assertEquals(request.jobId, result.jobId)
        assertTrue(result.completed)
        assertEquals(setOf(request.jobId), backend.cancelledJobs)
    }

    @Test
    fun modelCatalogUsesPinnedArtifactsAndExactIntegrityMetadata() {
        val fast = GemmaModelCatalog.fast
        val thorough = GemmaModelCatalog.thorough

        assertEquals(2_588_147_712L, fast.sizeBytes)
        assertEquals(
            "181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c",
            fast.sha256,
        )
        assertTrue(fast.downloadUrl.contains("6e5c4f1e395deb959c494953478fa5cec4b8008f"))
        assertEquals(3_659_530_240L, thorough.sizeBytes)
        assertEquals(
            "0b2a8980ce155fd97673d8e820b4d29d9c7d99b8fa6806f425d969b145bd52e0",
            thorough.sha256,
        )
        assertTrue(fast.downloadUrl.contains("/resolve/${fast.revision}/"))
        assertTrue(thorough.downloadUrl.contains("/resolve/${thorough.revision}/"))
        assertFalse(fast.downloadUrl.contains("/main/"))
        assertFalse(thorough.downloadUrl.contains("/main/"))
    }

    @Test
    fun hardwarePolicyKeepsUnqualifiedAccelerationAndLargeModelDisabled() {
        val gib = 1_073_741_824L
        val s20 = AiDeviceProfile(
            totalRamBytes = 12 * gib,
            availableRamBytes = 5 * gib,
            usableStorageBytes = 20 * gib,
            availableProcessors = 8,
            is64Bit = true,
            supportedAbis = setOf("arm64-v8a"),
            androidApi = 33,
            hasVulkanHardware = true,
            severeThermalState = false,
            lowMemoryDevice = false,
            deviceFamily = AiDeviceFamily.GALAXY_S20,
        )
        val s23 = s20.copy(
            androidApi = 35,
            deviceFamily = AiDeviceFamily.GALAXY_S23,
        )
        val lowMemory = s20.copy(
            totalRamBytes = 4 * gib,
            availableRamBytes = 1 * gib,
            lowMemoryDevice = true,
            deviceFamily = AiDeviceFamily.UNKNOWN,
        )

        assertEquals(
            AiRuntimeSelection(GemmaModelCatalog.fast, BackendKind.LITERT_CPU),
            AiHardwarePolicy.select(s20, QualityMode.THOROUGH, BackendQualifications()),
        )
        assertEquals(
            AiRuntimeSelection(GemmaModelCatalog.fast, BackendKind.LITERT_CPU),
            AiHardwarePolicy.select(s23, QualityMode.AUTO, BackendQualifications()),
        )
        assertEquals(
            AiRuntimeSelection(GemmaModelCatalog.fast, BackendKind.LITERT_GPU),
            AiHardwarePolicy.select(
                s23,
                QualityMode.FAST,
                BackendQualifications(gemmaGpuQualified = true),
            ),
        )
        assertEquals(
            AiRuntimeSelection(GemmaModelCatalog.thorough, BackendKind.LITERT_GPU),
            AiHardwarePolicy.select(
                s23,
                QualityMode.THOROUGH,
                BackendQualifications(
                    gemmaGpuQualified = true,
                    gemmaThoroughQualified = true,
                ),
            ),
        )
        assertEquals(
            AiRuntimeSelection(GemmaModelCatalog.legacyCompact, BackendKind.LEGACY_LLAMA_CPU),
            AiHardwarePolicy.select(lowMemory, QualityMode.THOROUGH, BackendQualifications()),
        )
        // An 8 GB-class phone (~7.2 GiB reported) must not carry Gemma E2B:
        // the model working set evicts the rest of the system and the whole
        // device lags. It runs the light legacy Qwen tiers instead.
        val eightGbClass = s20.copy(totalRamBytes = (7.2 * gib).toLong())
        assertEquals(
            AiRuntimeSelection(GemmaModelCatalog.legacyCompact, BackendKind.LEGACY_LLAMA_CPU),
            AiHardwarePolicy.select(eightGbClass, QualityMode.AUTO, BackendQualifications()),
        )
    }

    private class RecordingBackend : LocalRewriteBackend {
        override val backendId: String = "litert-gemma"
        var initializedModel: InstalledAiModel? = null
        var initializedKind: BackendKind? = null
        var lastRequest: LocalGenerationRequest? = null
        val cancelledJobs = linkedSetOf<String>()

        override suspend fun initialize(model: InstalledAiModel, backend: BackendKind) {
            initializedModel = model
            initializedKind = backend
        }

        override suspend fun generate(
            request: LocalGenerationRequest,
        ): LocalGenerationResult {
            lastRequest = request
            return LocalGenerationResult(
                jobId = request.jobId,
                text = "Dogs are loyal.",
                completed = true,
            )
        }

        override suspend fun cancel(jobId: String) {
            cancelledJobs += jobId
        }

        override fun releaseResources() = Unit
    }
}
