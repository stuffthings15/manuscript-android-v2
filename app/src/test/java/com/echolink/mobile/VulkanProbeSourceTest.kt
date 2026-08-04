package com.echolink.mobile

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Adreno driver aborts the whole process inside
 * vk::Device::createComputePipeline. A native abort cannot be caught, so the
 * trial load must happen somewhere disposable and its death must be
 * interpreted, not retried forever.
 */
class VulkanProbeSourceTest {
    private val mainRoot = File(
        checkNotNull(System.getProperty("echoLinkAndroidDir")),
        "app/src/main",
    )
    private val manifest = File(mainRoot, "AndroidManifest.xml").readText()
    private val probe = source("VulkanProbe.kt")
    private val service = source("VulkanProbeService.kt")
    private val runtime = source("Qwen3GrammarRuntime.kt")

    @Test
    fun probeServiceRunsInItsOwnProcess() {
        assertTrue(manifest.contains("android:name=\".VulkanProbeService\""))
        assertTrue(manifest.contains("android:process=\":vulkanprobe\""))
        assertTrue(manifest.contains("android:exported=\"false\""))
    }

    @Test
    fun aDeadProbeProcessCountsAsCpuOnly() {
        assertTrue(probe.contains("onServiceDisconnected"))
        assertTrue(probe.contains("onBindingDied"))
        assertTrue(probe.contains("onNullBinding"))
        // An attempt that started and never recorded a result means the
        // process died mid-probe: never retry that crash on every launch.
        assertTrue(probe.contains("KEY_ATTEMPT_STARTED"))
        assertTrue(probe.contains("record(context, model, usable = false)"))
    }

    @Test
    fun probeResultIsRememberedPerModel() {
        assertTrue(probe.contains("KEY_PROBED_MODEL"))
        assertTrue(probe.contains("probedModel != model.name"))
    }

    @Test
    fun runtimeUsesTheGpuOnlyAfterAProvenProbe() {
        assertTrue(runtime.contains("private fun vulkanUsable(model: File): Boolean"))
        assertTrue(runtime.contains("BuildConfig.MANUSCRIPT_VULKAN"))
        assertTrue(
            runtime.contains("VulkanProbe.ensureProbed(appContext, model) == VulkanProbe.Decision.USABLE"),
        )
        // Switching backend must force a reload, never reuse a CPU-loaded model.
        assertTrue(runtime.contains("loadedVulkan != useVulkan"))
    }

    @Test
    fun serviceStopsItselfAndReportsBothOutcomes() {
        assertTrue(service.contains("stopSelf()"))
        assertTrue(service.contains("MESSAGE_RESULT"))
        assertTrue(service.contains("probeGeneration"))
    }

    private fun source(name: String): String =
        File(mainRoot, "java/com/echolink/mobile/$name").readText()
}
