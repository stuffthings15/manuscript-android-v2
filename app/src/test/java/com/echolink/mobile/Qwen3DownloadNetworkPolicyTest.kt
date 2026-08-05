package com.echolink.mobile

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Qwen3DownloadNetworkPolicyTest {
    private val androidRoot = File(checkNotNull(System.getProperty("echoLinkAndroidDir")))
    private val sourceRoot = File(androidRoot, "app/src/main/java/com/echolink/mobile")

    @Test
    fun managerStoresTheChoiceButDoesNotOwnWorkConstraintsOrTransportChecks() {
        val manager = File(sourceRoot, "Qwen3ModelManager.kt").readText()

        assertTrue(manager.contains("enum class Qwen3DownloadNetworkPolicy"))
        assertTrue(manager.contains("WIFI_ONLY"))
        assertTrue(manager.contains("ALLOW_MOBILE_DATA"))
        assertFalse(manager.contains("import androidx.work.NetworkType"))
        assertFalse(manager.contains("requiredNetworkType"))
        assertFalse(manager.contains("ConnectivityManager"))
        assertFalse(manager.contains("NetworkCapabilities"))
        assertFalse(manager.contains("isUnmeteredNetwork"))
    }
}
