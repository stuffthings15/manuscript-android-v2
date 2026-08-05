package com.echolink.mobile

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AiDownloadNetworkPolicyTest {
    private val androidRoot = File(checkNotNull(System.getProperty("echoLinkAndroidDir")))
    private val sourceRoot = File(androidRoot, "app/src/main/java/com/echolink/mobile")

    @Test
    fun managerStoresTheChoiceButDoesNotOwnWorkConstraintsOrTransportChecks() {
        val manager = File(sourceRoot, "AiModelManager.kt").readText()

        assertTrue(manager.contains("enum class AiDownloadNetworkPolicy"))
        assertTrue(manager.contains("WIFI_ONLY"))
        assertTrue(manager.contains("ALLOW_MOBILE_DATA"))
        assertFalse(manager.contains("import androidx.work.NetworkType"))
        assertFalse(manager.contains("requiredNetworkType"))
        assertFalse(manager.contains("ConnectivityManager"))
        assertFalse(manager.contains("NetworkCapabilities"))
        assertFalse(manager.contains("isDownloadNetworkAllowed"))
    }

    @Test
    fun sharedSchedulerUsesOnlyTheCoarseConnectedConstraint() {
        val scheduler = File(sourceRoot, "WorkManagerDownloadAttemptScheduler.kt").readText()

        assertTrue(scheduler.contains("NetworkType.CONNECTED"))
        assertFalse(scheduler.contains("NetworkType.UNMETERED"))
        assertFalse(scheduler.contains("targetPolicy"))
        assertFalse(scheduler.contains("DownloadSchedulingNetworkPolicy"))
    }

    @Test
    fun exactAttemptServicesOwnTheRuntimeTransportDecision() {
        val services = File(sourceRoot, "AndroidDownloadServices.kt").readText()
        val evaluator = File(sourceRoot, "DownloadNetworkPolicyEvaluator.kt").readText()

        assertTrue(services.contains("fun networkDecision(target: DownloadTargetKey)"))
        assertTrue(services.contains("networkReader.current()"))
        assertTrue(services.contains("DownloadNetworkPolicyEvaluator.evaluate("))
        assertTrue(evaluator.contains("DownloadNetwork.MOBILE"))
        assertTrue(evaluator.contains("allowMobileData"))
    }
}
