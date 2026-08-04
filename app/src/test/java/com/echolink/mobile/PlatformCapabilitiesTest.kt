package com.echolink.mobile

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlatformCapabilitiesTest {
    @Test
    fun manuscriptDelegatesVoiceAndUsesSafeTextReplacement() {
        val capabilities = AndroidFoundationCapabilities.current

        assertTrue(capabilities.localOnly)
        assertFalse(capabilities.cloudFallback)
        assertFalse(capabilities.appMicrophoneCaptureAllowed)
        assertFalse(capabilities.appMicrophoneCaptureImplemented)
        assertTrue(capabilities.systemVoiceInputDelegated)
        assertTrue(capabilities.processTextSupported)
        assertTrue(capabilities.accessibilityOrOverlayUsed)
        assertTrue(capabilities.userApprovedReplacement)
    }
}
