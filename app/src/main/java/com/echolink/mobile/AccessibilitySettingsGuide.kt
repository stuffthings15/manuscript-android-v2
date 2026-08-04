package com.echolink.mobile

enum class AccessibilitySettingsPrompt {
    INSTALLED_APPS,
    MANUSCRIPT,
}

enum class AccessibilitySettingsCheckpoint {
    MANUSCRIPT_CARD,
    HIGHLIGHTED_INSTALLED_APPS_ROW,
    MANUSCRIPT_SERVICE_ROW,
    SERVICE_DETAIL,
    ENABLED_STATUS,
}

object AccessibilitySettingsGuide {
    fun prompt(isSamsung: Boolean): AccessibilitySettingsPrompt = if (isSamsung) {
        AccessibilitySettingsPrompt.INSTALLED_APPS
    } else {
        AccessibilitySettingsPrompt.MANUSCRIPT
    }

    fun acceptancePath(isSamsung: Boolean): List<AccessibilitySettingsCheckpoint> =
        if (isSamsung) {
            listOf(
                AccessibilitySettingsCheckpoint.MANUSCRIPT_CARD,
                AccessibilitySettingsCheckpoint.HIGHLIGHTED_INSTALLED_APPS_ROW,
                AccessibilitySettingsCheckpoint.MANUSCRIPT_SERVICE_ROW,
                AccessibilitySettingsCheckpoint.SERVICE_DETAIL,
                AccessibilitySettingsCheckpoint.ENABLED_STATUS,
            )
        } else {
            listOf(
                AccessibilitySettingsCheckpoint.MANUSCRIPT_CARD,
                AccessibilitySettingsCheckpoint.MANUSCRIPT_SERVICE_ROW,
                AccessibilitySettingsCheckpoint.SERVICE_DETAIL,
                AccessibilitySettingsCheckpoint.ENABLED_STATUS,
            )
        }
}
