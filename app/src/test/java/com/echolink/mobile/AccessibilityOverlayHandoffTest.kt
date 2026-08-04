package com.echolink.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AccessibilityOverlayHandoffTest {
    private val bounds = KeyboardPanelBounds(
        widthPx = 1080,
        heightPx = 673,
        leftOffsetPx = 0,
        bottomOffsetPx = 84,
    )

    @Test
    fun successfulHandoffUsesTypedActionsInLifecycleOrder() {
        val actions = StrictOverlayActions()

        val panelAttached = AccessibilityOverlayHandoff.replaceTriggerWithPanel(
            actions = actions,
            bounds = bounds,
        )

        assertTrue(panelAttached)
        assertEquals(
            listOf(
                "panel-added:1080x673",
                "panel-initialized",
                "trigger-removed",
                "keyboard-hidden",
            ),
            actions.events,
        )
    }

    @Test
    fun failedPanelAddRetainsTheWorkingTriggerAndKeyboard() {
        val actions = StrictOverlayActions(failAdd = true)

        val panelAttached = AccessibilityOverlayHandoff.replaceTriggerWithPanel(
            actions = actions,
            bounds = bounds,
        )

        assertFalse(panelAttached)
        assertEquals(listOf("panel-add-attempt:1080x673"), actions.events)
        assertNull(actions.panelReference)
    }

    @Test
    fun failedInitializationRemovesTrackedPanelBeforeClearingItsReference() {
        val actions = StrictOverlayActions(failInitialization = true)

        val panelAttached = AccessibilityOverlayHandoff.replaceTriggerWithPanel(
            actions = actions,
            bounds = bounds,
        )

        assertFalse(panelAttached)
        assertNull(actions.panelReference)
        assertEquals(
            listOf(
                "panel-added:1080x673",
                "panel-initialized",
                "panel-removed",
                "panel-reference-cleared",
            ),
            actions.events,
        )
    }

    @Test
    fun failedPanelRemovalStillClearsReferenceAndRetainsTriggerAndKeyboard() {
        val actions = StrictOverlayActions(
            failInitialization = true,
            failRemoval = true,
        )

        val panelAttached = AccessibilityOverlayHandoff.replaceTriggerWithPanel(
            actions = actions,
            bounds = bounds,
        )

        assertFalse(panelAttached)
        assertNull(actions.panelReference)
        assertEquals(
            listOf(
                "panel-added:1080x673",
                "panel-initialized",
                "panel-remove-attempt",
                "panel-reference-cleared",
            ),
            actions.events,
        )
    }

    private class StrictOverlayActions(
        private val failAdd: Boolean = false,
        private val failInitialization: Boolean = false,
        private val failRemoval: Boolean = false,
    ) : AccessibilityOverlayActions {
        val events = mutableListOf<String>()
        var panelReference: String? = null

        override fun addKeyboardAreaPanel(bounds: KeyboardPanelBounds) {
            if (failAdd) {
                events += "panel-add-attempt:${bounds.widthPx}x${bounds.heightPx}"
                throw IllegalStateException("unknown accessibility overlay token")
            }
            panelReference = "attached"
            events += "panel-added:${bounds.widthPx}x${bounds.heightPx}"
        }

        override fun initializeKeyboardAreaPanel() {
            checkNotNull(panelReference)
            events += "panel-initialized"
            if (failInitialization) throw IllegalStateException("render failed")
        }

        override fun removeKeyboardAreaPanel() {
            checkNotNull(panelReference)
            if (failRemoval) {
                events += "panel-remove-attempt"
                throw IllegalStateException("remove failed")
            }
            events += "panel-removed"
        }

        override fun clearKeyboardAreaPanelReference() {
            checkNotNull(panelReference)
            events += "panel-reference-cleared"
            panelReference = null
        }

        override fun removeWritingTrigger() {
            events += "trigger-removed"
        }

        override fun onKeyboardAreaPanelReady() {
            events += "keyboard-hidden"
        }
    }
}
