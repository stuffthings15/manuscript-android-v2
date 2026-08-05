package com.echolink.mobile

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadWorkerTypedInputSourceTest {
    private val root = File(checkNotNull(System.getProperty("echoLinkAndroidDir")))
    private val gemma = File(
        root,
        "app/src/main/java/com/echolink/mobile/AiModelDownloadWorker.kt",
    ).readText()
    private val legacy = File(
        root,
        "app/src/main/java/com/echolink/mobile/Qwen3ModelDownloadWorker.kt",
    ).readText()
    private val adapters = File(
        root,
        "app/src/main/java/com/echolink/mobile/ManagerDownloadAttemptPorts.kt",
    ).readText()

    @Test
    fun bothWorkersDecodeExactTypedAttemptBeforeObtainingAnyManager() {
        assertDecodeBeforeManager(
            source = gemma,
            decodeCall = "DownloadWorkerAttemptDecoder.decodeGemma(inputData, id)",
            managerCall = "AiModelManager.get(applicationContext)",
        )
        assertDecodeBeforeManager(
            source = legacy,
            decodeCall = "DownloadWorkerAttemptDecoder.decodeLegacy(inputData, id)",
            managerCall = "Qwen3ModelManager.get(applicationContext)",
        )
    }

    @Test
    fun gemmaWorkerCanNeverTreatFallbackModelAsTheRequestedReadyTarget() {
        assertTrue(adapters.contains("manager.exactInstalledModel(selection)"))
        assertFalse(adapters.contains("manager.installedModel(selection)"))
        assertFalse(gemma.contains("installedLegacyFallbackModel"))
    }

    @Test
    fun oldStringInputContractsAreRemovedFromBothWorkers() {
        listOf(gemma, legacy).forEach { source ->
            assertFalse(source.contains("inputData.getString("))
            assertFalse(source.contains("const val KEY_ARTIFACT_TIER"))
            assertFalse(source.contains("const val KEY_BACKEND"))
            assertFalse(source.contains("const val KEY_TIER"))
        }
    }

    @Test
    fun runtimeNetworkPolicyIsCheckedBeforeAndDuringEveryDownload() {
        listOf(gemma, legacy).forEach { source ->
            assertTrue(source.contains("services.networkDecision(attempt.target)"))
            assertTrue(source.contains("stopped ="))
            assertTrue(source.contains("!services.isCurrent(attempt)"))
            assertTrue(source.contains("services.networkDecision(attempt.target)"))
        }
    }

    private fun assertDecodeBeforeManager(
        source: String,
        decodeCall: String,
        managerCall: String,
    ) {
        val constructorPrefix = source.substringBefore("override suspend fun doWork()")
        assertFalse(constructorPrefix.contains("Manager.get("))
        val body = source.substringAfter("override suspend fun doWork()")
        val decodeIndex = body.indexOf(decodeCall)
        val managerIndex = body.indexOf(managerCall)
        assertTrue(decodeIndex >= 0)
        assertTrue(managerIndex > decodeIndex)
    }
}
