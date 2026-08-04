package com.echolink.mobile

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BuildIdentityTest {
    @Test
    fun diagnosticsShowReleaseIdentityWithoutPersonalInformation() {
        val summary = BuildIdentity(
            versionName = "2.0.0-internal-debug",
            versionCode = 1,
            packageName = "com.remote.Manuscript.v2.debug",
            gitCommit = "123456789abc",
            contractVersion = 1,
            signerSha256 = "ABCDEF0123456789",
            localAiReady = true,
            accessibilityEnabled = false,
        ).summary()

        assertTrue(summary.contains("2.0.0-internal-debug (1)"))
        assertTrue(summary.contains("com.remote.Manuscript.v2.debug"))
        assertTrue(summary.contains("123456789abc"))
        assertTrue(summary.contains("Contract: 1"))
        assertTrue(summary.contains("Signer: 456789"))
        assertTrue(summary.contains("Local AI: Ready"))
        assertTrue(summary.contains("Across-app writing: Off"))
        assertFalse(summary.contains("@"))
    }
}
