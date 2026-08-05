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
        val resizeHandle = manuscriptString("resize_review_panel")
        val modelSetup = manuscriptString("qwen_model_install_action")
        val keyboardBefore = secureSetting("default_input_method")
        withFixture(S23ExternalEditorFixtureActivity.MODE_EMPTY) { productSurfaceNeverVisible(trigger) }
        withFixture(S23ExternalEditorFixtureActivity.MODE_PASSWORD) { productSurfaceNeverVisible(trigger) }
        withFixture(S23ExternalEditorFixtureActivity.MODE_NORMAL) { assertTrue(productSurfaceMeetsExpectation(trigger, true)) }
        withFixture(S23ExternalEditorFixtureActivity.MODE_MULTILINE) {
            assertTrue(productSurfaceMeetsExpectation(trigger, true))
            tapFixtureEditorForIme(S23ExternalEditorFixtureActivity.MODE_MULTILINE)
            tapProductSurface(trigger)
            assertTrue("Actual correction panel did not open.", productSurfaceMeetsExpectation(close, true))
            productSurfaceNeverVisible(modelSetup, 250L)
            val collapsedHeight = requireNotNull(v2OverlayHeightContaining(resizeHandle)) {
                "V2 resize handle was not in an accessibility overlay."
            }
            swipeUpOnProductSurface(resizeHandle)
            assertTrue(
                "V2 correction panel did not expand after an upward resize-handle swipe.",
                waitUntil { (v2OverlayHeightContaining(resizeHandle) ?: 0) > collapsedHeight },
            )
            tapProductSurface(close)
            assertTrue("IME and fixture field must return after closing the panel.", waitUntil { fixtureImeAndFocusedFieldPresent() })
        }
        withFixture(S23ExternalEditorFixtureActivity.MODE_TRANSIENT) {
            assertTrue(productSurfaceMeetsExpectation(trigger, true))
            Thread.sleep(S23ExternalEditorFixtureActivity.TRANSIENT_CLEAR_DELAY_MS + 500L)
            assertTrue(productSurfaceMeetsExpectation(trigger, false))
        }
        assertEquals(keyboardBefore, secureSetting("default_input_method"))
    }
}
