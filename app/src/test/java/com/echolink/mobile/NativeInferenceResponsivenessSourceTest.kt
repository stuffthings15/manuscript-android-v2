package com.echolink.mobile

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeInferenceResponsivenessSourceTest {
    @Test
    fun llamaRuntimeUsesDedicatedDefaultPriorityThreadAndClosesIt() {
        val source = File(
            System.getProperty("user.dir"),
            "../vendor/llama.cpp/examples/llama.android/lib/src/main/java/" +
                "com/arm/aichat/internal/InferenceEngineImpl.kt",
        ).readText()

        assertTrue(source.contains("Executors.newSingleThreadExecutor"))
        assertTrue(source.contains("Thread.NORM_PRIORITY"))
        assertFalse(source.contains("Process.THREAD_PRIORITY_BACKGROUND"))
        assertFalse(source.contains("Thread.MIN_PRIORITY"))
        assertTrue(source.contains("llamaExecutor.asCoroutineDispatcher()"))
        assertTrue(source.contains("llamaDispatcher.close()"))
        assertFalse(source.contains("Dispatchers.IO.limitedParallelism"))
    }
}
