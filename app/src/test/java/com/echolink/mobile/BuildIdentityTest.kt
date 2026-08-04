package com.echolink.mobile

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BuildIdentityTest {
    @Test
    fun diagnosticsShowReleaseIdentityWithoutPersonalInformation() {
        val summary = BuildIdentity(
            versionName = "0.29.0-internal-debug",
            versionCode = 29,
            packageName = "com.remote.Manuscript.debug",
            gitCommit = "123456789abc",
            contractVersion = 1,
            signerSha256 = "ABCDEF0123456789",
            localAiReady = true,
            accessibilityEnabled = false,
        ).summary()

        assertTrue(summary.contains("0.29.0-internal-debug (29)"))
        assertTrue(summary.contains("com.remote.Manuscript.debug"))
        assertTrue(summary.contains("123456789abc"))
        assertTrue(summary.contains("Contract: 1"))
        assertTrue(summary.contains("Signer: 456789"))
        assertTrue(summary.contains("Local AI: Ready"))
        assertTrue(summary.contains("Across-app writing: Off"))
        assertFalse(summary.contains("@"))
    }
}
