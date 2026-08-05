package com.echolink.mobile.fixture

import java.util.Locale

object FixtureRenderedReturnContract {
    const val PROFILE_ARGUMENT = "renderedReturnProfile"

    enum class Profile(val reportName: String) {
        PHYSICAL("physical-acceptance"),
        VERIFIED_EMULATOR_FUNCTIONAL("verified-emulator-functional"),
    }

    data class Selection(
        val profile: Profile,
        val ceilingMs: Long,
        val recordingDurationSeconds: Int,
    ) {
        fun accepts(transitionAtMs: Long?, triggerAtMs: Long?): Boolean {
            if (transitionAtMs == null || triggerAtMs == null) return false
            val delayMs = triggerAtMs - transitionAtMs
            return delayMs in 0L..ceilingMs
        }
    }

    fun select(hardware: String?, requestedProfile: String?): Selection {
        val verifiedEmulator = hardware
            ?.trim()
            ?.lowercase(Locale.US)
            ?.let(VERIFIED_EMULATOR_HARDWARE::contains) == true
        val physicalRequested = requestedProfile?.trim()?.equals("physical", ignoreCase = true) == true
        return if (verifiedEmulator && !physicalRequested) {
            Selection(
                Profile.VERIFIED_EMULATOR_FUNCTIONAL,
                EMULATOR_FUNCTIONAL_CEILING_MS,
                EMULATOR_RECORDING_DURATION_SECONDS,
            )
        } else {
            Selection(Profile.PHYSICAL, PHYSICAL_CEILING_MS, PHYSICAL_RECORDING_DURATION_SECONDS)
        }
    }

    private val VERIFIED_EMULATOR_HARDWARE = setOf("goldfish", "ranchu")
    private const val PHYSICAL_CEILING_MS = 500L
    private const val EMULATOR_FUNCTIONAL_CEILING_MS = 2_000L
    private const val PHYSICAL_RECORDING_DURATION_SECONDS = 3
    private const val EMULATOR_RECORDING_DURATION_SECONDS = 5
}
