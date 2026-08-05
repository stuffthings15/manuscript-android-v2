package com.echolink.mobile.fixture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FixtureRenderedReturnContractTest {
    @Test fun physicalHardwareKeepsThe500MsAcceptanceGate() {
        val contract = FixtureRenderedReturnContract.select(
            hardware = "qcom",
            requestedProfile = null,
        )

        assertEquals(FixtureRenderedReturnContract.Profile.PHYSICAL, contract.profile)
        assertEquals(500L, contract.ceilingMs)
        assertEquals(3, contract.recordingDurationSeconds)
        assertTrue(contract.accepts(transitionAtMs = 1_000L, triggerAtMs = 1_500L))
        assertFalse(contract.accepts(transitionAtMs = 1_000L, triggerAtMs = 1_501L))
    }

    @Test fun verifiedRanchuEmulatorUsesTheSeparate2000MsFunctionalCeiling() {
        val contract = FixtureRenderedReturnContract.select(
            hardware = "ranchu",
            requestedProfile = null,
        )

        assertEquals(FixtureRenderedReturnContract.Profile.VERIFIED_EMULATOR_FUNCTIONAL, contract.profile)
        assertEquals(2_000L, contract.ceilingMs)
        assertEquals(5, contract.recordingDurationSeconds)
        assertTrue(contract.accepts(transitionAtMs = 1_000L, triggerAtMs = 3_000L))
        assertFalse(contract.accepts(transitionAtMs = 1_000L, triggerAtMs = 3_001L))
    }

    @Test fun emulatorArgumentCannotDowngradePhysicalHardware() {
        val contract = FixtureRenderedReturnContract.select(
            hardware = "qcom",
            requestedProfile = "emulator-functional",
        )

        assertEquals(FixtureRenderedReturnContract.Profile.PHYSICAL, contract.profile)
        assertEquals(500L, contract.ceilingMs)
    }

    @Test fun missingHardwareFailsClosedEvenWithEmulatorArgument() {
        val contract = FixtureRenderedReturnContract.select(
            hardware = null,
            requestedProfile = "emulator-functional",
        )

        assertEquals(FixtureRenderedReturnContract.Profile.PHYSICAL, contract.profile)
    }

    @Test fun physicalArgumentCanMakeAnEmulatorRunStricter() {
        val contract = FixtureRenderedReturnContract.select(
            hardware = "goldfish",
            requestedProfile = "physical",
        )

        assertEquals(FixtureRenderedReturnContract.Profile.PHYSICAL, contract.profile)
        assertEquals(500L, contract.ceilingMs)
    }

    @Test fun blankNullOrBackwardsObservationsFailClosed() {
        val contract = FixtureRenderedReturnContract.select("ranchu", null)

        assertFalse(contract.accepts(transitionAtMs = null, triggerAtMs = 1_000L))
        assertFalse(contract.accepts(transitionAtMs = 1_000L, triggerAtMs = null))
        assertFalse(contract.accepts(transitionAtMs = 1_001L, triggerAtMs = 1_000L))
    }
}
