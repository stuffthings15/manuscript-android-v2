package com.echolink.mobile.fixture

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class S23ProductContractDeviceTest : FixtureDeviceSupport() {
    @After fun manuscriptServiceRemainsBound() = requireHealthyManuscriptService()

    @Test fun generatedFieldsFollowTheLockedTriggerAndKeyboardContract() {
        requireHealthyManuscriptService()
        assertInstalledCorrectionModel()
        val trigger = manuscriptString("external_assistant_open")
        val close = manuscriptString("close_action")
        val modelSetup = manuscriptString("qwen_model_install_action")
        val keyboardBefore = secureSetting("default_input_method")
        withFixture(S23ExternalEditorFixtureActivity.MODE_EMPTY) { descriptionNeverVisible(trigger) }
        withFixture(S23ExternalEditorFixtureActivity.MODE_PASSWORD) { descriptionNeverVisible(trigger) }
        withFixture(S23ExternalEditorFixtureActivity.MODE_NORMAL) { assertTrue(descriptionVisible(trigger, true)) }
        withFixture(S23ExternalEditorFixtureActivity.MODE_MULTILINE) {
            assertTrue(descriptionVisible(trigger, true))
            tapDescription(trigger)
            assertTrue("Actual correction panel did not open.", descriptionVisible(close, true))
            descriptionNeverVisible(modelSetup, 250L)
            tapDescription(close)
        }
        withFixture(S23ExternalEditorFixtureActivity.MODE_TRANSIENT) {
            assertTrue(descriptionVisible(trigger, true))
            Thread.sleep(S23ExternalEditorFixtureActivity.TRANSIENT_CLEAR_DELAY_MS + 500L)
            assertTrue(descriptionVisible(trigger, false))
        }
        assertEquals(keyboardBefore, secureSetting("default_input_method"))
    }
}
