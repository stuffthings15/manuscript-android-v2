package com.echolink.mobile

import org.junit.Assert.assertEquals
import org.junit.Test

class AccessibilitySettingsGuideTest {
    @Test
    fun samsungFallbackGuidesTheUserThroughInstalledApps() {
        assertEquals(
            AccessibilitySettingsPrompt.INSTALLED_APPS,
            AccessibilitySettingsGuide.prompt(
                isSamsung = true,
            ),
        )
    }

    @Test
    fun samsungAlwaysGuidesTheUserThroughInstalledAppsBecauseTheResolvedActionOpensTheHomepage() {
        assertEquals(
            AccessibilitySettingsPrompt.INSTALLED_APPS,
            AccessibilitySettingsGuide.prompt(
                isSamsung = true,
            ),
        )
    }

    @Test
    fun nonSamsungRoutesGuideTheUserToManuscript() {
        assertEquals(
            AccessibilitySettingsPrompt.MANUSCRIPT,
            AccessibilitySettingsGuide.prompt(isSamsung = false),
        )
    }

    @Test
    fun samsungAcceptancePathNamesEveryScreenThatMustBeProven() {
        assertEquals(
            listOf(
                AccessibilitySettingsCheckpoint.MANUSCRIPT_CARD,
                AccessibilitySettingsCheckpoint.HIGHLIGHTED_INSTALLED_APPS_ROW,
                AccessibilitySettingsCheckpoint.MANUSCRIPT_SERVICE_ROW,
                AccessibilitySettingsCheckpoint.SERVICE_DETAIL,
                AccessibilitySettingsCheckpoint.ENABLED_STATUS,
            ),
            AccessibilitySettingsGuide.acceptancePath(isSamsung = true),
        )
    }
}
