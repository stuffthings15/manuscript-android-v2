package com.echolink.mobile

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Records the exact meaning-neutral text handed to the live AI. */
@RunWith(AndroidJUnit4::class)
class DeterministicEssayFloorDeviceTest {
    @Test
    fun writesSelectedEssayPreprocessingFloor() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val only = InstrumentationRegistry.getArguments().getString("essay").orEmpty()
        val source = File(context.filesDir, "essays")
            .listFiles { file -> file.extension == "txt" }
            ?.filter { only.isEmpty() || it.name.startsWith(only) }
            ?.sortedBy(File::getName)
            ?.firstOrNull()
        assumeTrue("No matching essay fixture", source != null)
        val output = File(context.filesDir, "essay-floor-${source!!.name}")
        val router = LocalCorrectionRouter(context)
        try {
            output.writeText(router.deterministicRepair(source.readText()))
        } finally {
            router.releaseResources()
        }
        check(output.isFile && output.length() > 0L)
    }
}
