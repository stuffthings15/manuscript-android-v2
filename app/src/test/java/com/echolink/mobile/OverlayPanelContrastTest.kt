package com.echolink.mobile

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The across-app panel is drawn over an application Manuscript does not
 * control, and the GUI build manual requires it to stay legible against a light
 * host and a dark one alike.
 */
class OverlayPanelContrastTest {
    private val service = File(
        System.getProperty("echoLinkAndroidDir") ?: ".",
        "app/src/main/java/com/echolink/mobile/ManuscriptAccessibilityService.kt",
    ).readText()

    private fun constant(name: String): Int =
        Regex("""const val $name = (\d+)""").find(service)
            ?.groupValues
            ?.get(1)
            ?.toInt()
            ?: error("$name is missing from ManuscriptAccessibilityService")

    @Test
    fun theEdgeCarriesTheContrastBecauseTheFillCannot() {
        // Manuscript's surface is dark warm. Against a dark host application a
        // hairline border leaves the panel floating in something the same
        // colour as itself, so the border is thicker than a hairline and the
        // panel is elevated for a system shadow.
        // Read from source rather than referenced directly: the service's
        // companion is private, and widening production visibility so a test
        // can peek at it would be the test changing the product.
        val border = constant("PANEL_BORDER_DP")
        val elevation = constant("PANEL_ELEVATION_DP")
        assertTrue("The panel border must be thicker than a hairline", border >= 2)
        assertTrue("The panel must be elevated so it separates from any host", elevation > 0)
        assertTrue(
            "buildPanel must apply the border width",
            service.contains("strokeWidthDp = PANEL_BORDER_DP"),
        )
        assertTrue(
            "buildPanel must apply the elevation",
            service.contains("elevation = dp(PANEL_ELEVATION_DP).toFloat()"),
        )
    }

    @Test
    fun theResizeControlCanBeHitByAFinger() {
        // It resizes the entire panel and was a 24 dp strip, half the manual's
        // minimum.
        assertTrue(
            service.contains("dp(ManuscriptMetrics.TOUCH_TARGET_DP),"),
        )
    }

    @Test
    fun thePanelUsesTheSharedRadiusAndSpacing() {
        assertTrue(service.contains("ManuscriptMetrics.RADIUS_PANEL_DP"))
        assertTrue(service.contains("ManuscriptMetrics.SPACE_CARD_DP"))
    }
}
