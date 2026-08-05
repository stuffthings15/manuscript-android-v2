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
            tapFixtureDescription(FixtureActionContract.TRANSIENT_CLEAR_DESCRIPTION)
            val editorDescription =
                S23ExternalEditorFixtureActivity.editorDescription(S23ExternalEditorFixtureActivity.MODE_TRANSIENT)
            assertTrue(
                "Transient fixture action did not produce a focused empty editor within its bounded gate.",
                waitUntil(FixtureActionContract.ACTION_COMPLETION_GATE_MS) {
                    findFixtureDescription(editorDescription).let { editor ->
                        try {
                            editor != null && editor.isFocused && editor.text.isNullOrEmpty()
                        } finally {
                            editor?.recycle()
                        }
                    }
                }
            )
            assertTrue(productSurfaceMeetsExpectation(trigger, false))
        }
        assertEquals(keyboardBefore, secureSetting("default_input_method"))
    }
}
