package com.echolink.mobile

import androidx.work.NetworkType
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AiDownloadNetworkPolicyTest {
    private val androidRoot = File(checkNotNull(System.getProperty("echoLinkAndroidDir")))
    private val sourceRoot = File(androidRoot, "app/src/main/java/com/echolink/mobile")

    @Test
    fun wifiOnlyAllowsMeteredWifiButNotCellular() {
        assertEquals(
            NetworkType.CONNECTED,
            AiDownloadNetworkPolicy.WIFI_ONLY.requiredNetworkType,
        )

        val manager = File(sourceRoot, "AiModelManager.kt").readText()
        assertTrue(manager.contains("NetworkCapabilities.TRANSPORT_WIFI"))
        assertTrue(manager.contains("internal fun isDownloadNetworkAllowed()"))
    }

    @Test
    fun workerChecksTheChosenNetworkBeforeAndDuringDownload() {
        val worker = File(sourceRoot, "AiModelDownloadWorker.kt").readText()

        assertTrue(worker.contains("if (!manager.isDownloadNetworkAllowed())"))
        assertTrue(worker.contains("isStopped || !manager.isDownloadNetworkAllowed()"))
    }

    @Test
    fun anUpgradeReplacesTheOldUnmeteredOnlyWorkRequest() {
        val manager = File(sourceRoot, "AiModelManager.kt").readText()

        assertTrue(manager.contains("KEY_ENQUEUED_CONSTRAINT_VERSION"))
        assertTrue(manager.contains("DOWNLOAD_CONSTRAINT_VERSION"))
        assertTrue(manager.contains(".putInt("))
    }
}
