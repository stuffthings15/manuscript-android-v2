package com.echolink.mobile

data class PlatformCapabilities(
    val localOnly: Boolean,
    val cloudFallback: Boolean,
    val appMicrophoneCaptureAllowed: Boolean,
    val appMicrophoneCaptureImplemented: Boolean,
    val systemVoiceInputDelegated: Boolean,
    val processTextSupported: Boolean,
    val accessibilityOrOverlayUsed: Boolean,
    val userApprovedReplacement: Boolean,
)

object AndroidFoundationCapabilities {
    val current = PlatformCapabilities(
        localOnly = true,
        cloudFallback = false,
        appMicrophoneCaptureAllowed = false,
        appMicrophoneCaptureImplemented = false,
        systemVoiceInputDelegated = true,
        processTextSupported = true,
        accessibilityOrOverlayUsed = true,
        userApprovedReplacement = true,
    )
}
