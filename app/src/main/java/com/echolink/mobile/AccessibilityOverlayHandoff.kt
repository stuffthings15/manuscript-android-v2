package com.echolink.mobile

interface AccessibilityOverlayActions {
    fun addKeyboardAreaPanel(bounds: KeyboardPanelBounds)
    fun initializeKeyboardAreaPanel()
    fun removeKeyboardAreaPanel()
    fun clearKeyboardAreaPanelReference()
    fun removeWritingTrigger()
    fun onKeyboardAreaPanelReady()
}

internal object AccessibilityOverlayHandoff {
    fun replaceTriggerWithPanel(
        actions: AccessibilityOverlayActions,
        bounds: KeyboardPanelBounds,
    ): Boolean {
        var panelAttached = false
        try {
            actions.addKeyboardAreaPanel(bounds)
            panelAttached = true
            actions.initializeKeyboardAreaPanel()
        } catch (_: RuntimeException) {
            if (panelAttached) {
                runCatching { actions.removeKeyboardAreaPanel() }
                actions.clearKeyboardAreaPanelReference()
            }
            return false
        }
        actions.removeWritingTrigger()
        actions.onKeyboardAreaPanelReady()
        return true
    }
}
