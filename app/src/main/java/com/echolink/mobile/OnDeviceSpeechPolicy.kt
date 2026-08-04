package com.echolink.mobile

sealed class SpeechCaptureState {
    abstract val canStart: Boolean
    open val canStop: Boolean = false

    data class Unavailable(val reason: String) : SpeechCaptureState() {
        override val canStart = false
    }

    object Ready : SpeechCaptureState() {
        override val canStart = true
    }

    object PermissionRequired : SpeechCaptureState() {
        override val canStart = false
    }

    object Listening : SpeechCaptureState() {
        override val canStart = false
        override val canStop = true
    }

    data class Partial(val transcript: String) : SpeechCaptureState() {
        override val canStart = false
        override val canStop = true
    }

    object Processing : SpeechCaptureState() {
        override val canStart = false
    }

    data class ModelSetup(val message: String) : SpeechCaptureState() {
        override val canStart = false
    }

    data class Review(val transcript: String) : SpeechCaptureState() {
        override val canStart = true
    }

    data class Error(val message: String) : SpeechCaptureState() {
        override val canStart = true
    }
}

object OnDeviceSpeechPolicy {
    const val MINIMUM_API_LEVEL = 31

    fun availability(apiLevel: Int, onDeviceServiceAvailable: Boolean): SpeechCaptureState = when {
        apiLevel < MINIMUM_API_LEVEL -> SpeechCaptureState.Unavailable(
            "On-device speech requires Android 12 or newer.",
        )
        !onDeviceServiceAvailable -> SpeechCaptureState.Unavailable(
            "No on-device speech service is installed for this device.",
        )
        else -> SpeechCaptureState.Ready
    }

    fun bestTranscript(candidates: List<String>?): String? = candidates
        ?.firstOrNull()
        ?.trim()
        ?.takeIf { it.isNotEmpty() }

    fun languageMatches(localeTag: String, candidates: Collection<String>): Boolean {
        val target = java.util.Locale.forLanguageTag(localeTag)
        return candidates.any { candidate ->
            val available = java.util.Locale.forLanguageTag(candidate)
            candidate.equals(localeTag, ignoreCase = true) ||
                available.language.equals(target.language, ignoreCase = true)
        }
    }

    fun errorMessage(errorCode: Int): String = when (errorCode) {
        3 -> "Microphone audio failed. Try again."
        5 -> "The on-device recognizer stopped. Try again."
        6 -> "No speech was heard before the local timeout."
        7 -> "No speech was recognized on this device."
        8 -> "The on-device recognizer is busy. Try again."
        9 -> "Microphone permission is required."
        11 -> "The on-device speech service disconnected."
        12 -> "This language is not supported by the on-device recognizer."
        13 -> "The on-device language model is not installed."
        else -> "On-device speech failed. Nothing was approved."
    }
}
