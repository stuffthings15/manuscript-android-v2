package com.echolink.mobile

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A cancelled sibling request or a low-memory trim releases the shared
 * llama.cpp runtime. That must never leave the backend permanently unable to
 * correct: live logs showed a 78 second run ending in "The hidden
 * compatibility AI is not initialized" after an earlier cancel.
 */
class LegacyBackendRecoverySourceTest {
    private val source = File(
        checkNotNull(System.getProperty("echoLinkAndroidDir")),
        "app/src/main/java/com/echolink/mobile/LegacyQwenBackend.kt",
    ).readText()

    @Test
    fun generateReArmsReadinessInsteadOfFailing() {
        assertTrue(source.contains("if (!initialized) initialized = runtime.isReady()"))
    }

    @Test
    fun cancelDoesNotTearDownTheSharedRuntime() {
        val cancelBody = source
            .substringAfter("override suspend fun cancel(jobId: String) {")
            .substringBefore("}")
        assertTrue(cancelBody.contains("cancelledJobs += jobId"))
        assertFalse(
            "Cancelling one job must not release the runtime other jobs are using.",
            cancelBody.contains("runtime.releaseResources()"),
        )
    }

    @Test
    fun explicitReleaseStillClearsReadiness() {
        val releaseBody = source
            .substringAfter("override fun releaseResources() {")
            .substringBefore("}")
        assertTrue(releaseBody.contains("initialized = false"))
        assertTrue(releaseBody.contains("runtime.releaseResources()"))
    }
}
